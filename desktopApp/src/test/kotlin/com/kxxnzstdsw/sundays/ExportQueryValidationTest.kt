package com.kxxnzstdsw.sundays

import org.junit.Test
import kotlin.test.assertTrue

/**
 * 导出的**参数校验**契约。
 *
 * ## 为什么只有这几条
 *
 * 引擎侧的 `ExportHandler.executeInMainProcess` 走的是**子进程**：
 * `findEngineJarPath()` 找 `idb-engine.jar` → `ensureSubprocessRunning()` 拉起子进程 →
 * `ExportProcessManager.collectResponses(id)` 等它的响应。
 *
 * 测试环境里**没有打包好的 jar**，`collectResponses` 等的是一个永远不会来的响应 ——
 * 真跑一次导出会**挂死整个测试任务**（实测卡了 4 分钟只能强杀）。
 *
 * 所以这里只验「参数不合法时**在发出去之前**就被拒绝」这一半：
 * 用户填错目录 / 忘了填表名时，引擎那边什么都还没发生，他看到的是一句人话
 * 而不是 JDBC 异常或一个转不出来的圈。真正跑通的那半留给集成测试 / 手工验证。
 */
class ExportQueryValidationTest {

    private fun newState(): DatabaseBrowserState {
        val state = DatabaseBrowserState(
            engine = object : com.kxxnzstdsw.client.EngineClient {
                // 这个假引擎**永远不该被调用** —— 被测的全是「发出去之前就拒绝」的分支。
                // 真走到这里说明参数校验漏了，让测试直接炸出来而不是静默通过。
                override fun handle(request: com.kxxnzstdsw.grpc.Request) =
                    throw AssertionError("不该打到引擎：request=$request")
                override suspend fun testConnection(config: com.kxxnzstdsw.grpc.ConnectionConfig) =
                    throw AssertionError("不该打到引擎")
                override suspend fun disconnect(config: com.kxxnzstdsw.grpc.ConnectionConfig) =
                    throw AssertionError("不该打到引擎")
                override fun close() = Unit
            },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        state.bindConnection(
            com.kxxnzstdsw.sundays.connection.ConnectionConfig(
                id = "t", name = "T", dialect = com.kxxnzstdsw.sundays.connection.DialectType.H2,
                username = "sa", password = "", jdbcUrl = "jdbc:h2:mem:nowhere",
            ),
        )
        return state
    }

    private fun failureMessage(sql: String, format: DatabaseBrowserState.ExportFormat, dir: String, name: String, table: String = "") =
        kotlinx.coroutines.runBlocking {
            newState().exportQuery(sql, format, dir, name, table).exceptionOrNull()?.message.orEmpty()
        }

    @Test
    fun `blank sql is rejected before reaching the engine`() {
        assertTrue(
            failureMessage("   ", DatabaseBrowserState.ExportFormat.CSV, "/tmp", "a.csv").contains("SQL"),
            "空 SQL 应在发出去之前被拒绝",
        )
    }

    @Test
    fun `blank output directory is rejected`() {
        assertTrue(
            failureMessage("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, "  ", "a.csv")
                .contains("输出目录"),
            "空输出目录应被拒绝 —— 引擎会把它当相对路径，用户拿不到文件",
        )
    }

    @Test
    fun `blank file name is rejected`() {
        assertTrue(
            failureMessage("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, "/tmp", "")
                .contains("文件名"),
            "空文件名应被拒绝",
        )
    }

    /**
     * `SQL_INSERT` 必须给目标表名。
     *
     * 这条是**格式特有的硬要求**：`SQL_INSERT` 产出的是 `INSERT INTO <表> VALUES …`，
     * 没有表名引擎无从拼起 —— 而它在 proto 里是 `optional` 的，留空只能到引擎侧才炸。
     */
    @Test
    fun `sql insert export requires a target table name`() {
        val msg = failureMessage(
            "SELECT 1", DatabaseBrowserState.ExportFormat.SQL_INSERT, "/tmp", "a.sql",
        )
        assertTrue(msg.contains("目标表"), "SQL_INSERT 缺目标表名应被拒绝：$msg")
    }

    @Test
    fun `other formats do not require a target table name`() {
        // 反向：CSV / JSON / EXCEL / PARQUET 都不该因为「没填表名」被拦 ——
        // 那会让用户在导出 CSV 时也被问一个无关的问题
        for (f in listOf(
            DatabaseBrowserState.ExportFormat.CSV,
            DatabaseBrowserState.ExportFormat.JSON_LINES,
            DatabaseBrowserState.ExportFormat.EXCEL,
            DatabaseBrowserState.ExportFormat.PARQUET,
        )) {
            val msg = failureMessage("   ", f, "/tmp", "a.out")
            assertTrue(
                msg.contains("SQL") || msg.contains("输出目录") || msg.contains("文件名"),
                "格式 ${f.name} 不该因缺目标表名被拦，实际报错：$msg",
            )
        }
    }

    @Test
    fun `every format maps to a distinct engine value`() {
        val values = DatabaseBrowserState.ExportFormat.entries.map { it.protoValue }
        assertTrue(
            values.size == values.toSet().size,
            "导出格式的引擎取值必须互不相同：$values",
        )
        assertTrue(
            values.containsAll(listOf("CSV", "JSON_LINES", "SQL_INSERT", "EXCEL", "PARQUET")),
            "应覆盖引擎 ExportRunRequest.format 约定的五种：$values",
        )
    }
}
