package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.engine.StatementRegistry
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.RequestOptions
import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.importer.ImportSourceFactory
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.grpc.ImportRunRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试 —— 覆盖本轮修复的三个引擎侧缺陷。
 *
 * 三个都是「静默失效」型 bug，修复前不会有任何异常，只有结果错，因此必须断言结果本身。
 */
class EngineFixesRegressionTest {

    private fun h2Config(): ConnectionConfig {
        DialectLoader.registerForTesting("H2", H2Dialect())
        val db = "fix_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        return ConnectionConfig.newBuilder()
            .setDriver("H2").setUser("sa").setPassword("").setDatabase(db)
            .setJdbcUrl("jdbc:h2:mem:$db;DB_CLOSE_DELAY=-1")
            .build()
    }

    /**
     * 修复 #2：导入必须剥掉 UTF-8 BOM。
     *
     * 本项目的 CsvWriter 导出时写 BOM；导入侧不剥会让首个列名变成 "\uFEFFid"，
     * 拼出的 INSERT 被数据库拒收 —— 即「自己导出的文件自己导不回来」。
     */
    @Test
    fun `import strips utf8 bom so exported csv round-trips`() {
        val f = File.createTempFile("bom_roundtrip", ".csv")
        f.deleteOnExit()
        // 与 CsvWriter 输出一致：UTF-8 BOM + 表头
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "id,name\n1,Alice\n".toByteArray())

        val src = ImportSourceFactory.open(
            ImportRunRequest.newBuilder().setFilePath(f.absolutePath).setFormat("CSV").setHasHeader(true).build()
        )
        src.use {
            assertEquals(listOf("id", "name"), it.columns, "BOM 不得污染首列列名")
            val rows = mutableListOf<Map<String, String>>()
            val it0 = it.rows()
            while (it0.hasNext()) rows.add(it0.next())
            assertEquals(1, rows.size)
            assertEquals("1", rows[0]["id"], "BOM 不得污染首列数据值")
        }
    }

    /** 无 BOM 的普通文件必须原样读入 —— 探测逻辑不能吞掉正常字节。 */
    @Test
    fun `import without bom reads normally`() {
        val f = File.createTempFile("nobom", ".csv")
        f.deleteOnExit()
        f.writeBytes("id,name\n1,Alice\n".toByteArray())

        val src = ImportSourceFactory.open(
            ImportRunRequest.newBuilder().setFilePath(f.absolutePath).setFormat("CSV").setHasHeader(true).build()
        )
        src.use {
            assertEquals(listOf("id", "name"), it.columns)
            val rows = mutableListOf<Map<String, String>>()
            val it0 = it.rows()
            while (it0.hasNext()) rows.add(it0.next())
            assertEquals(1, rows.size)
        }
    }

    /**
     * 修复 #3：SYSTEM.CANCEL 必须能用调用方自己发的 Request.id 取消多语句脚本。
     *
     * 修复前脚本按 "<requestId>#<index>" 登记，调用方按原 id 取消一律 cancelled=false，
     * 「取消多语句脚本」这个能力实际不可用。
     */
    @Test
    fun `cancel works with the caller supplied request id on multi statement script`() = runBlocking {
        val cfg = h2Config()
        PoolManager.getConnection(cfg).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE t(id INT)") }
        }
        val requestId = "regress-cancel-${UUID.randomUUID()}"
        // 第一条是会跑很久的查询，保证取消时它仍在运行
        val sql = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 40000) a, SYSTEM_RANGE(1, 40000) b; SELECT 1"

        val job = launch(Dispatchers.Default) {
            RequestDispatcher.dispatch(
                Request.newBuilder().setId(requestId)
                    .setCategory(Category.SQL).setAction(Action.EXECUTE)
                    .setConnection(cfg)
                    .setSqlRequest(sqlRequest {
                        execute = SqlExecuteRequest.newBuilder().setSql(sql).setMultiStatement(true).build()
                    })
                    .build()
            ).toList()
        }
        // 等到脚本里的第一条语句被登记
        var waited = 0
        while (StatementRegistry.activeCount() == 0 && waited < 500) { delay(10); waited++ }
        assertTrue(StatementRegistry.activeCount() > 0, "脚本语句应已登记")

        assertTrue(
            StatementRegistry.cancel(requestId),
            "用调用方的原始 request id 必须能取消多语句脚本",
        )
        job.cancel()
        PoolManager.closeAll()
    }

    /**
     * 修复 #4：timeoutMs 对流式路由生效。
     *
     * 修复前流式分支在超时包装之外直接 return@flow，一条能跑几秒的 SELECT
     * 完全无视 timeoutMs。
     */
    @Test
    fun `timeoutMs applies to streaming routes`() = runBlocking {
        val cfg = h2Config()
        PoolManager.getConnection(cfg).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE big AS SELECT X AS id FROM SYSTEM_RANGE(1, 50000)")
            }
        }
        val started = System.currentTimeMillis()
        val frames = RequestDispatcher.dispatch(
            Request.newBuilder().setId("regress-timeout")
                .setCategory(Category.SQL).setAction(Action.EXECUTE)
                .setConnection(cfg)
                .setOptions(RequestOptions.newBuilder().setTimeoutMs(50).build())
                .setSqlRequest(sqlRequest {
                    execute = SqlExecuteRequest.newBuilder()
                        .setSql("SELECT COUNT(*) FROM big a JOIN big b ON a.id = b.id")
                        .build()
                })
                .build()
        ).toList()
        val elapsed = System.currentTimeMillis() - started

        val last = frames.last()
        assertTrue(
            !last.success,
            "超时后终止帧必须是 success=false，实际 success=${last.success} elapsedMs=$elapsed",
        )
        assertTrue(
            last.error.contains("exceeded", ignoreCase = true),
            "错误信息应说明超时，实际: ${last.error}",
        )
        assertTrue(last.end, "超时帧必须是终止帧")
        assertTrue(elapsed < 30_000, "应在超时后很快返回，实际耗时 ${elapsed}ms")
        PoolManager.closeAll()
    }

    /** 修复 #4 的对照：无 timeoutMs 时长查询必须正常完成（不能被误伤）。 */
    @Test
    fun `streaming route without timeoutMs still completes`() = runBlocking {
        val cfg = h2Config()
        PoolManager.getConnection(cfg).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE small(id INT)") }
            c.createStatement().use { it.execute("INSERT INTO small VALUES (1),(2)") }
        }
        val frames = RequestDispatcher.dispatch(
            Request.newBuilder().setId("regress-no-timeout")
                .setCategory(Category.SQL).setAction(Action.EXECUTE)
                .setConnection(cfg)
                .setSqlRequest(sqlRequest {
                    execute = SqlExecuteRequest.newBuilder().setSql("SELECT * FROM small ORDER BY id").build()
                })
                .build()
        ).toList()
        val last = frames.last()
        assertTrue(last.success, "未设超时的正常查询必须成功: ${last.error}")
        assertFalse(last.end.not() && last.error.isNotEmpty())
        PoolManager.closeAll()
    }
}
