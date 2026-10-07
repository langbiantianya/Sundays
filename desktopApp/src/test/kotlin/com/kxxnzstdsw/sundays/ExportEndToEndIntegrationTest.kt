package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * **导出全链路**端到端测试：桌面状态层 → 引擎 → **真子进程** → 磁盘上真有文件。
 *
 * ## 为什么必须是这一层
 *
 * 之前只有两处覆盖，都不够：
 *
 * - 桌面侧 `ExportAlwaysTerminatesTest`：用 fake engine，验的是「一定会收口」
 * - 引擎侧 `ExportPipelineIntegrationTest`：验的是引擎本体与 gRPC 通道
 *
 * 两段拼起来的中间那截 ——「桌面发出 EXPORT.RUN_EXPORT → 引擎找到并拉起子进程
 * → 子进程回帧 → 文件落地」—— 没人验过。而它恰恰是坏掉的地方：
 * `executeAsSubprocess` 在 `withContext` 里 `emit`，**每一帧**都撞上 Flow 不变量
 * （TEST_CASES.md §9.15），于是子进程一帧不发、桌面永远等，界面上「点了没反应」。
 *
 * ## 为什么以前「测不到」
 *
 * Gradle 跑测试时 classpath 上是 `engine/build/classes/...` 而不是 `idb-engine.jar`，
 * `findEngineJarPath()` 返回 null，导出路由直接回「Cannot find idb-engine.jar path」。
 * **这条链路只在打包产物里存在过** —— 现在补了「退回本进程 classpath」的启动方式，
 * 开发与 CI 环境也能验它。
 *
 * 远程库不可达时显式 skip（沿用 `DialectSmokeTest` 的做法），报告里留一条 skipped。
 */
class ExportEndToEndIntegrationTest {

    private var engine: IdbEngine? = null
    private val workspaces = mutableListOf<Pair<SmokeTarget, String>>()

    @After
    fun tearDown() {
        runCatching { PoolManager.closeAll() }
        runCatching { com.kxxnzstdsw.export.ExportProcessManager.stop() }
        engine?.close()
        workspaces.forEach { (t, w) -> runCatching { t.teardown(w) } }
        workspaces.clear()
    }

    @Test
    fun `exporting a real table writes a real file`() {
        val target = MySqlSmoke
        assumeTrue(
            "远程 MySQL 不可达（${target.label}），跳过导出端到端测试",
            target.reachable(),
        )
        target.registerDialect()
        val scope = Files.createTempDirectory("sundays-export-e2e").toFile()
        val ws = target.provision(scope)
        workspaces += target to ws

        val cfg: ConnectionConfig = target.config(ws)
        target.direct(ws).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS e2e_items")
                st.executeUpdate(
                    "CREATE TABLE e2e_items (id INT PRIMARY KEY, label VARCHAR(64), amount DECIMAL(10,2))"
                )
                st.executeUpdate(
                    "INSERT INTO e2e_items VALUES (1, '第一行', 12.50), (2, 'second, with comma', -3.00), (3, NULL, 0.00)"
                )
            }
        }

        val eng = IdbEngine().also { engine = it }
        val state = DatabaseBrowserState(eng, CoroutineScope(Dispatchers.IO))
        // 用真实的桌面配置：driver 字段决定引擎按哪个方言建池
        val desktopCfg = ConnectionConfig(
            id = "e2e",
            name = "e2e",
            dialect = DialectType.MYSQL,
            username = cfg.username,
            password = cfg.password,
            jdbcUrl = cfg.jdbcUrl,
        )
        // ⚠️ 不 bind 这一行的话 `engineConn` 会发出一个 **driver 为空** 的连接配置，
        // 引擎直接回「No dialect plugin loaded for driver: 」。
        // （这正是加完日志后第一眼看到的东西 —— 之前它被静默吞掉了。）
        state.bindConnection(desktopCfg)

        val outDir = Files.createTempDirectory("sundays-export-out").toFile()
        val progress = mutableListOf<Long>()

        val result = runBlocking {
            withTimeoutOrNull(120_000) {
                state.exportQuery(
                    sql = "SELECT id, label, amount FROM e2e_items ORDER BY id",
                    format = DatabaseBrowserState.ExportFormat.CSV,
                    outputDir = outDir.absolutePath,
                    fileName = "e2e.csv",
                    onProgress = { progress += it.rowsWritten },
                )
            }
        }

        assertTrue(result != null, "导出 120 秒内没有返回 —— 又一次静默挂起")
        assertTrue(result.isSuccess, "导出失败：${result.exceptionOrNull()?.message}")

        val written = outDir.listFiles()?.filter { it.name.startsWith("e2e") }.orEmpty()
        assertTrue(written.isNotEmpty(), "目录里没有导出文件：${outDir.list()?.toList()}")
        val file = written.first()
        // ⚠️ 文件名不能变成 e2e.csv.csv —— 调用方给的已经带扩展名了
        assertTrue(
            file.name.equals("e2e.csv", ignoreCase = true),
            "扩展名被拼了两次：实际落盘 ${file.name}",
        )
        val text = file.readText()
        assertTrue(text.contains("第一行"), "中文没写进去：$text")
        assertTrue(text.contains("-3.00"), "负数没写进去：$text")
        assertTrue(text.contains("\"second, with comma\""), "含逗号的字段必须加引号：$text")
        assertTrue(progress.isNotEmpty(), "进度回调一次都没被调")
        assertTrue(
            progress.any { it > 0 },
            "进度里没有任何非零行数（弹窗会一直显示「正在连接引擎…」）：$progress",
        )
    }
}
