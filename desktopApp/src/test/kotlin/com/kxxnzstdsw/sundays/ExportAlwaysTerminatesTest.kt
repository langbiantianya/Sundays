package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.ConnectionConfig as GrpcConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「导出必须给得出结论」——**不允许静默**。
 *
 * ## 这条契约是被真窗口走查逼出来的
 *
 * 真窗口 + 真 MySQL，点「开始导出」：对话框关了，文件没生成，界面上**一个字都没有**。
 * 用户既不知道成没成，也不知道该不该重试。
 *
 * 根因在引擎那条流式路由（`EXPORT.RUN_EXPORT`）的两个缺口：
 *
 * 1. `ExportProcessManager.startExport` 失败时**只写日志就返回**（已修：改成返回 Boolean，
 *    由 `ExportHandler` 收口成 `success=false` 的终止帧）
 * 2. 主进程是 `collectResponses(id).collect {}` —— 那个 SharedFlow 只有子进程回帧才被写。
 *    命令没发出去时**一帧都没有**，`collect` 于是永久挂起。
 *
 * 第 2 条在前端也拦得住（已修：`exportQuery` 用 `withTimeoutOrNull` 兜底），
 * 这条测试就是钉住那个兜底。
 *
 * ## 为什么用 fake engine 而不是真引擎
 *
 * 真导出要拉子进程、找 `idb-engine.jar`、连真库，那条链路本身就会因环境而红，
 * 说明不了「有没有收口」。这里直接测**契约**：
 * 无论引擎回什么，导出都必须在有限时间内以「成功」或「失败」**收口**，
 * 且两者都要带上用户看得懂的原因。
 */
class ExportAlwaysTerminatesTest {

    /** 引擎回了进度帧但永远不给完成帧 —— 复刻子进程中途消失那条最坏路径。 */
    private class NeverCompletingEngine : EngineClient {
        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flow {
                emit(progressFrame(request.id, completed = false, rows = 12, filePath = ""))
                awaitCancellation()
            }

        override suspend fun invoke(
            connection: GrpcConnectionConfig,
            configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit,
        ): com.kxxnzstdsw.grpc.Response = error("本用例不走非流式路径")

        override suspend fun testConnection(
            config: GrpcConnectionConfig,
        ): com.kxxnzstdsw.grpc.SystemTestConnectionResponse = error("本用例不测连接")

        override suspend fun disconnect(config: GrpcConnectionConfig): Boolean = false

        override fun close() = Unit

        private fun progressFrame(id: String, completed: Boolean, rows: Long, filePath: String) =
            com.kxxnzstdsw.grpc.response {
                this.id = id
                success = true
                stream = true
                export = com.kxxnzstdsw.grpc.exportResponse {
                    progress = com.kxxnzstdsw.grpc.ExportProgressFrame.newBuilder()
                        .setExportedRows(rows)
                        .setCompleted(completed)
                        .setFilePath(filePath)
                        .build()
                }
            }
    }

    /** 引擎正常完成：回报完成帧与文件路径。 */
    private class CompletingEngine(private val file: File) : EngineClient {
        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flow {
                emit(com.kxxnzstdsw.grpc.response {
                    id = request.id
                    success = true
                    stream = true
                    export = com.kxxnzstdsw.grpc.exportResponse {
                        progress = com.kxxnzstdsw.grpc.ExportProgressFrame.newBuilder()
                            .setExportedRows(1)
                            .setCompleted(true)
                            .setFilePath(file.absolutePath)
                            .build()
                    }
                })
            }

        override suspend fun invoke(
            connection: GrpcConnectionConfig,
            configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit,
        ): com.kxxnzstdsw.grpc.Response = error("本用例不走非流式路径")

        override suspend fun testConnection(
            config: GrpcConnectionConfig,
        ): com.kxxnzstdsw.grpc.SystemTestConnectionResponse = error("本用例不测连接")

        override suspend fun disconnect(config: GrpcConnectionConfig): Boolean = false

        override fun close() = Unit
    }

    private fun newState(engine: EngineClient) =
        DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))

    /**
     * 跑一次导出。
     *
     * 刻意**不**在这里写 `runBlocking`：外层的 [withTestGuard] 也要在同一个协程里
     * 等它，套一层 `runBlocking` 会开一个新事件循环、看不见外层的取消信号 ——
     * 于是挂死的实现会把整个测试任务拖死，而不是「被测出来变红」。
     */
    private suspend fun exportCsv(
        state: DatabaseBrowserState,
        dir: File,
        name: String,
    ) = state.exportQuery("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, name)

    /**
     * 变异验证的看门狗：把挂死的实现**测出来**，而不是让整个测试任务卡住。
     *
     * 只有跑变异（把 `withTimeoutOrNull` 去掉）时才会真的触发。
     * `exportTimeoutMs` 在那条用例里是 300ms，这里给它 20s —— 足够「正确实现」返回，
     * 又短到挂死的实现一定会被这里截断。
     */
    private fun <T> withTestGuard(timeoutMs: Long, block: suspend () -> T): T? =
        runBlocking { withTimeoutOrNull(timeoutMs) { block() } }

    @Test
    fun `an export that never completes must still produce a visible conclusion`() {
        val out = Files.createTempDirectory("sundays-export").toFile()
        val state = newState(NeverCompletingEngine())
        // 测试里把看门狗调到 300ms：生产值是 5 分钟，等不起；而被测的正是
        // 「超时也会变成一条用户看得见的失败」这条行为，与超时**多长**无关。
        state.exportTimeoutMs = 300

        val result = withTestGuard(20_000) { exportCsv(state, out, "never.csv") }

        assertTrue(
            result != null,
            "引擎永远不给完成帧时，导出必须**返回**失败而不是挂起 —— " +
                "这里返回 null 表示 20 秒内一次都没结束，正是真窗口上「点了没反应」的成因",
        )
        assertTrue(
            result!!.isFailure,
            "没给完成帧时应判为失败并让界面显示出来：$result",
        )
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.isNotBlank(), "失败必须带可显示的原因（界面原样显示给用户）：msg='$msg'")
    }

    @Test
    fun `a blank output directory is rejected before any request is sent`() {
        val out = Files.createTempDirectory("sundays-export2").toFile()
        val state = newState(NeverCompletingEngine())
        val result = withTestGuard(20_000) { runBlocking { exportCsv(state, out, "") } }
        // 前端校验先于引擎调用 —— 让引擎才报错的话，用户看到的是一句 JDBC 异常，
        // 还得在对话框里猜是自己哪里填错了
        assertTrue(result != null && result.isFailure, "空文件名必须被拒：$result")
        assertEquals(
            "请填写文件名", result.exceptionOrNull()?.message,
            "校验失败的原因要指名道姓，而不是「导出失败」四个字",
        )
    }

    @Test
    fun `the happy path reports the written file and row count`() {
        // 只测失败的话，一个永远失败的实现也能全绿 —— 成功那条也必须有结论
        val out = Files.createTempDirectory("sundays-export3").toFile()
        val file = out.resolve("ok.csv").also { it.writeText("id\n1\n") }
        val result = runBlocking { exportCsv(newState(CompletingEngine(file)), out, "ok.csv") }

        assertTrue(result.isSuccess, "引擎回报完成帧与文件路径时应成功：$result")
        val ok = result.getOrThrow()
        assertEquals(file.absolutePath, ok.file, "成功结论里必须带真实文件路径，否则用户不知道文件在哪")
        assertEquals(1L, ok.rowsWritten, "导出行数要原样透出")
    }

    @Test
    fun `the default watchdog is long enough not to interrupt a normal export`() {
        // 反向护栏：看门狗不是为了「快点报错」，是为了「一直不报」兜底。
        // 定得太短会把正常的大表导出砍掉 —— 那比慢更糟。
        assertTrue(
            newState(NeverCompletingEngine()).exportTimeoutMs >= 60_000,
            "默认超时至少要有一分钟，否则大表导出会被误杀：${newState(NeverCompletingEngine()).exportTimeoutMs}",
        )
    }

    @Suppress("unused")
    private fun unusedFileImport() = File(".") // 保留 File 引用（上面的类型签名需要）
}