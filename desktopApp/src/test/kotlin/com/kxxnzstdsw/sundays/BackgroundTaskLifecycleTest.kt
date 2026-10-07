package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.sundays.DatabaseBrowserState.BackgroundTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 [ExportAlwaysTerminatesTest] 同一个别名 —— `ConnectionConfig` 两边同名（proto / 应用侧）。 */
private typealias GrpcConnectionConfig = com.kxxnzstdsw.grpc.ConnectionConfig

/**
 * 后台任务列表的状态机行为。
 *
 * ## 为什么这组测试盯的是「状态」而不是「界面」
 *
 * 「后台导出」这件事在这之前是**假的**：协程挂在 `PreviewTabArea` 的
 * `rememberCoroutineScope()` 上，用户一切 pane 那个协程就被取消，而界面上不留任何痕迹。
 * 「后台运行」那个按钮恰恰在暗示它能活着跑完。
 *
 * 所以这里断言的是**状态机层面的三件事**：
 * 1. 任务登记在 [DatabaseBrowserState] 上（不是 composable 的 remember）
 * 2. 任务状态会随导出进度推进并最终收口
 * 3. 「清除已完成」不碰运行中的任务
 */
class BackgroundTaskLifecycleTest {

    /**
     * 一个**成功的**导出引擎 —— 立刻回报完成帧与文件路径。
     *
     * 用成功而不是失败来测「收口」，是因为这样任务状态会停在 SUCCEEDED，
     * 断言「收口后状态不再变」这件事才不会被「反正都会失败」掩盖掉。
     * 失败路径由 `非成功引擎` 覆盖。
     */
    private class CompletingEngine(private val file: File) : EngineClient {
        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flow {
                emit(progressFrame(request.id, completed = false, rows = 7, filePath = ""))
                emit(progressFrame(request.id, completed = true, rows = 7, filePath = file.absolutePath))
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

    /** 引擎直接失败 —— 用来验「失败也必须收口，且给出原因」。 */
    private class FailingEngine : EngineClient {
        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flow { throw IllegalStateException("输出目录不存在") }

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

    /**
     * 每次调用挂起，直到测试显式 [release] 那一次调用。
     *
     * ## 为什么需要它
     *
     * 「清除已完成不该误伤运行中的」这条要同时造出**一个已完成 + 一个运行中**。
     * 用 [CompletingEngine] 做不到：它立即完成，等断言跑起来时第二个早就收口了，
     * 于是 `clearFinishedTasks` 把它也清掉，测试报
     * 「expected [task-b] but was []」—— 红的不是被测逻辑，是时序。
     *
     * 挂起把时序交给测试控制，这条断言才真的在测它声称的东西。
     */
    /**
     * 第 1 次调用正常完成，第 2 次**永不收口**。
     *
     * ## 为什么不用「挂起 + 按序号放行」
     *
     * `state.startExport` 是**异步**的（在 `scope.launch` 里才发请求），两次调用的
     * `handle()` 到达顺序不保证。第一版按「第 0 次调用」放行，全量并发下第二次先到，
     * 于是放错了那一个 → `awaitSettled` 等 15 秒超时。
     *
     * 换成「第一次完成、第二次自己就不收口」，测试**串行**发起并等第一次收口，
     * 顺序就由测试定死，不需要任何关于到达顺序的假设。
     */
    private class CompletesThenHangsEngine(private val file: File) : EngineClient {
        private val calls = java.util.concurrent.atomic.AtomicInteger()

        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flow {
                emit(progressFrame(request.id, completed = false, rows = 1, filePath = ""))
                if (calls.getAndIncrement() == 0) {
                    emit(progressFrame(request.id, completed = true, rows = 1, filePath = file.absolutePath))
                } else {
                    // 永不收口 —— 模拟「一条一直在跑」
                    kotlinx.coroutines.awaitCancellation()
                }
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

    private fun newState(
        engine: EngineClient,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    ) = DatabaseBrowserState(engine, scope)

    @Test
    fun `任务收口后变成成功，进度落在结果文件上`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sundays-task-ok").toFile()
        val out = File(dir, "a.csv")
        val state = newState(CompletingEngine(out))

        val taskId = state.startExport("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "a.csv")

        // 刚登记时：运行中，且**进度是 null 而不是 0** ——
        // 「0 行」会被读成「导出失败」，而引擎起子进程本来就要几秒
        val fresh = state.backgroundTasks.first { it.id == taskId }
        assertTrue(fresh.isRunning, "刚登记就该是运行中")
        assertEquals(null, fresh.progress, "没收到首帧时不能报 0 行")

        awaitSettled(state, taskId)

        val done = state.backgroundTasks.first { it.id == taskId }
        assertEquals(BackgroundTask.Status.SUCCEEDED, done.status)
        assertEquals(out.absolutePath, done.detail, "成功时 detail 应是结果文件路径")
        assertEquals(7L, done.progress, "收口后进度应是最终行数")
        assertTrue("完成" in done.statusLine, done.statusLine)

        dir.deleteRecursively()
    }

    @Test
    fun `失败也必须收口并给出原因`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sundays-task-fail").toFile()
        val state = newState(FailingEngine())
        val taskId = state.startExport("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "a.csv")

        awaitSettled(state, taskId)

        val done = state.backgroundTasks.first { it.id == taskId }
        assertEquals(BackgroundTask.Status.FAILED, done.status)
        assertTrue(
            done.detail!!.contains("输出目录不存在"),
            "失败原因要带引擎给的原文：${done.detail}",
        )
        dir.deleteRecursively()
    }

    @Test
    fun `运行中的任务计入徽标，已完成的立刻退出计数`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sundays-task-badge").toFile()
        val state = newState(CompletingEngine(File(dir, "a.csv")))
        assertEquals(0, state.runningTasks.size, "初始没有运行中任务")

        val id = state.startExport("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "a.csv")
        assertEquals(1, state.runningTasks.size, "登记后徽标该是 1")

        awaitSettled(state, id)
        assertEquals(0, state.runningTasks.size, "收口后徽标该归零")
        assertEquals(1, state.backgroundTasks.size, "但任务本身还留在列表里")
        dir.deleteRecursively()
    }

    @Test
    fun `清除已完成不会误伤运行中的任务`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sundays-task-clear").toFile()
        val state = newState(CompletesThenHangsEngine(File(dir, "a.csv")))

        // 串行发起：先让第一条跑完（引擎第 1 次调用会正常完成），
        // 再发起第二条（引擎第 2 次调用永不收口 = 运行中）。
        // 不并发发起，就不需要对「两次 handle 谁先到」做任何假设。
        val first = state.startExport("SELECT 1", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "first.csv")
        awaitSettled(state, first)

        val second = state.startExport("SELECT 2", DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "run2.csv")
        // 等它真的跑起来（拿到第一帧进度），否则下面「恰好一个在跑」是空断言
        withTimeout(10_000) {
            while (state.backgroundTasks.first { it.id == second }.progress == null) delay(10)
        }

        assertEquals(2, state.backgroundTasks.size)
        assertEquals(1, state.runningTasks.size, "此刻恰好一个在跑")

        state.clearFinishedTasks()

        assertEquals(
            listOf(second),
            state.backgroundTasks.map { it.id },
            "只清掉已完成的，运行中的必须留着 —— 否则用户一点就把正在跑的导出从视野里弄没了",
        )
        assertEquals(1, state.runningTasks.size, "运行中的那条还在徽标计数里")
        dir.deleteRecursively()
    }

    @Test
    fun `批量导出登记 N 个任务且文件名带序号`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sundays-task-batch").toFile()
        val state = newState(CompletingEngine(File(dir, "out.csv")))
        val stmts = listOf("SELECT 1", "SELECT 2", "SELECT 3")

        val ids = state.startExportBatch(stmts, DatabaseBrowserState.ExportFormat.CSV, dir.absolutePath, "export.csv")

        assertEquals(3, ids.size, "每条语句一个任务 —— 面板要能回答「哪一条失败了」")
        assertEquals(3, state.backgroundTasks.size)
        val titles = state.backgroundTasks.map { it.title }
        assertTrue(
            listOf("export_1.csv", "export_2.csv", "export_3.csv").all { it in titles },
            "文件应带序号：$titles",
        )

        withTimeout(20_000) { while (state.runningTasks.isNotEmpty()) delay(10) }
        assertTrue(state.backgroundTasks.none { it.isRunning }, "串行跑完应该全部收口")
        assertTrue(
            state.backgroundTasks.all { it.status == BackgroundTask.Status.SUCCEEDED },
            "每条都要有终态：${state.backgroundTasks.map { it.statusLine }}",
        )
        dir.deleteRecursively()
    }

    @Test
    fun `批量导出空语句列表不登记任何任务`() {
        val state = newState(CompletingEngine(File(".", "x.csv")))
        assertTrue(state.startExportBatch(emptyList(), DatabaseBrowserState.ExportFormat.CSV, ".", "x.csv").isEmpty())
        assertTrue(state.backgroundTasks.isEmpty())
    }

    @Test
    fun `运行中且没收到首帧时状态行不报 0 行`() {
        val task = BackgroundTask(
            id = "t",
            kind = BackgroundTask.Kind.EXPORT,
            title = "a.csv",
            subtitle = "dir",
            status = BackgroundTask.Status.RUNNING,
            startedAt = 0L,
        )
        // 「正在连接引擎…」而不是「已写出 0 行」—— 后者会被读成失败
        assertEquals("正在连接引擎…", task.statusLine)
        assertTrue("0" !in task.statusLine, "不能出现 0 行：${task.statusLine}")

        assertTrue("1234" in task.copy(progress = 1234).statusLine)
    }

    @Test
    fun `状态行如实区分成功与失败`() {
        val base = BackgroundTask(
            id = "t",
            kind = BackgroundTask.Kind.EXPORT,
            title = "a.csv",
            subtitle = "dir",
            startedAt = 0L,
            status = BackgroundTask.Status.RUNNING,
        )
        assertTrue("完成" in base.copy(status = BackgroundTask.Status.SUCCEEDED, detail = "C:\\x\\a.csv").statusLine)
        val failed = base.copy(status = BackgroundTask.Status.FAILED, detail = "目录不存在")
        assertTrue("失败" in failed.statusLine, failed.statusLine)
        assertTrue("目录不存在" in failed.statusLine, failed.statusLine)
    }

    /** 等某个任务收口。测试里绝不能 `runBlocking` 嵌套 —— 那会开新事件循环、看不见取消信号。 */
    private suspend fun awaitSettled(state: DatabaseBrowserState, taskId: String) {
        withTimeout(15_000) {
            while (state.backgroundTasks.firstOrNull { it.id == taskId }?.isRunning == true) delay(10)
        }
        assertTrue(
            state.backgroundTasks.any { it.id == taskId },
            "任务被清掉了？「清除已完成」不该在测试期间发生",
        )
    }
}
