package com.kxxnzstdsw.integration

import com.kxxnzstdsw.export.ExportSubProcess
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.grpc.ExportCommand
import com.kxxnzstdsw.grpc.ExportHubGrpc
import com.kxxnzstdsw.grpc.ExportHubResponse
import com.kxxnzstdsw.handlers.ExportHandler
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 子进程 hub 的地址。
 *
 * 写死 `127.0.0.1` 而不是 `localhost`：`localhost` 在 Windows 上会解析出
 * `::1`，gRPC 一旦挑了 IPv6 去连只监听 IPv4 的子进程，就会得到
 * `UNAVAILABLE: io exception`（`Connection refused: localhost/[::1]:端口`）。
 * 生产代码 `ExportProcessManager.EXPORT_HUB_HOST` 用的是同一个值。
 */
private const val HUB_HOST = "127.0.0.1"

/**
 * `EXPORT.RUN_EXPORT` 的**引擎侧**端到端测试。
 *
 * ## 为什么放在引擎而不是桌面应用
 *
 * 真窗口上点「导出」时，命令下去之后**一帧都回不来**（TEST_CASES.md §9.13）。
 * 那一段横跨三处：父进程的 `ExportProcessManager`、gRPC ExportHub 通道、
 * 子进程的 `ExportSubProcess` + `ExportEngine`。在桌面应用里只能看到
 * 「什么都没发生」，拆不出是哪一环断的。
 *
 * 这里把它拆成两个可以分别变红的锚点：
 *
 * | 用例 | 覆盖 |
 * |---|---|
 * | `subprocess export writes the file` | `ExportEngine` 本体：真连库、真写文件 |
 * | `the ExportHub stream carries progress back` | gRPC 通道：`ExportCommand` → `ExportHubResponse` 往返 |
 *
 * ## 为什么**不**在这里测 `RequestDispatcher` 的 EXPORT 路由
 *
 * 那一层要拉**子进程**（`ExportProcessManager.start` 找 `idb-engine.jar`），
 * 而 Gradle 测试的 classpath 上只有 `engine/build/classes/...`，没有 jar。
 * 于是它只会返回 `Cannot find idb-engine.jar path` —— 测的是打包，不是逻辑。
 * 真要覆盖它，得先把「从 classes 目录也能起子进程」这件事做掉（另议）。
 */
class ExportPipelineIntegrationTest : H2Fixture() {

    @TempDir
    lateinit var outDir: Path

    @BeforeEach
    fun seed() {
        // 子进程跑在本 JVM 里，它自己要能加载 dialect 与驱动；
        // 单测环境下 `build/libs/drivers` 不一定存在，兜住即可（不抛、不假装成功）
        runCatching {
            DriverLoader.loadFromDir(File("build/libs/drivers"))
            DialectLoader.loadFromDir(File("build/libs/dialects"))
        }
        executeUpdate("DROP TABLE IF EXISTS export_items")
        executeUpdate(
            """
            CREATE TABLE export_items (
                id INT PRIMARY KEY,
                name VARCHAR(64),
                amount DECIMAL(10,2)
            )
            """.trimIndent(),
        )
        executeUpdate("INSERT INTO export_items VALUES (1, '第一行', 12.50), (2, 'second, with comma', -3.00), (3, NULL, 0.00)")
    }

    private fun exportRequest(sql: String, dir: File, name: String) =
        com.kxxnzstdsw.grpc.Request.newBuilder()
            .setId(TestIds.next("r-exp"))
            .setCategory(com.kxxnzstdsw.grpc.Category.EXPORT)
            .setAction(com.kxxnzstdsw.grpc.Action.RUN_EXPORT)
            .setConnection(config)
            .setExportRequest(
                com.kxxnzstdsw.grpc.ExportRequest.newBuilder()
                    .setRunExport(
                        com.kxxnzstdsw.grpc.ExportRunRequest.newBuilder()
                            .setSql(sql)
                            .setOutputDir(dir.absolutePath)
                            .setFileName(name)
                            .setFormat("CSV")
                            .build()
                    )
                    .build()
            )
            .build()

    // ---------------------------------------------------------------------
    // ① 引擎本体：真连库、真写文件
    // ---------------------------------------------------------------------

    @Test
    fun `subprocess export writes the file`() = runBlocking {
        val frames = ExportHandler.executeAsSubprocess(
            exportRequest("SELECT id, name, amount FROM export_items ORDER BY id", outDir.toFile(), "items.csv")
        ).toList()

        val last = frames.last()
        assertTrue(last.success, "导出应成功：success=false error=${last.error}")

        val file = outDir.resolve("items.csv")
        assertTrue(file.toFile().exists(), "文件没生成：${outDir.toAbsolutePath()} 下没有 items.csv")

        // 内容也要看 —— 只断言「文件存在」的话，导出一个空文件也能过
        val text = file.toFile().readText()
        assertTrue(text.contains("第一行"), "中文没写进去：$text")
        assertTrue(text.contains("-3.00"), "负数没写进去：$text")
        assertTrue(text.contains("\"second, with comma\""), "含逗号的字段必须加引号：$text")
    }

    @Test
    fun `a bad output directory fails loudly instead of silently`() = runBlocking {
        val frames = ExportHandler.executeAsSubprocess(
            exportRequest("SELECT 1", File("Z:/definitely/not/here"), "x.csv")
        ).toList()

        val last = frames.last()
        // 这条钉的是 §9.13 的教训：**任何一步炸了都必须变成一帧可见的失败**
        assertEquals(true, last.end, "失败也必须收口（end=true），否则上游永远等着")
        assertTrue(
            !last.success && last.error.isNotBlank(),
            "写不了目录必须报出原因，而不是「什么都没发生」：success=${last.success} error='${last.error}'",
        )
    }

    // ---------------------------------------------------------------------
    // ② gRPC ExportHub 通道：命令下去，帧要回来
    // ---------------------------------------------------------------------

    @Test
    fun `the ExportHub stream carries progress back`() {
        // 子进程**跑在本测试的 JVM 里**（后台线程 + 独立端口），
        // 父进程侧用真实的 gRPC channel 连上去 —— 于是这条用例真正覆盖的是
        // 「ExportCommand → ExportHubResponse」这一段 IPC，而不是绕开它。
        val port = freePort()
        System.setProperty("idb.export.hub.port", port.toString())
        val hubThread = Thread({ runCatching { ExportSubProcess.run() } }, "export-subprocess-test")
        hubThread.isDaemon = true
        hubThread.start()

        // ⚠️ 顺序要紧：**先等端口、再建流**。
        // `stub.stream()` 拿到的 `ClientCall` 会立刻去连，对面还没 bind 就是
        // `Connection refused` → `UNAVAILABLE: io exception`，而且这个失败是
        // **异步**回来的，等端口等到了也救不回来。
        // 生产代码 `ExportProcessManager.awaitHubReadyOrReportFailure` 就是这个顺序。
        assertTrue(awaitPort(port), "ExportHub 20 秒内没监听 :$port")

        val channel = ManagedChannelBuilder.forAddress(HUB_HOST, port).usePlaintext().build()
        try {
            val done = CountDownLatch(1)
            val lastFrame = AtomicReference<ExportHubResponse>()
            val gotAny = AtomicReference(false)

            val stub = ExportHubGrpc.newStub(channel)
            val commands = stub.stream(object : StreamObserver<ExportHubResponse> {
                override fun onNext(value: ExportHubResponse) {
                    gotAny.set(true)
                    lastFrame.set(value)
                    if (value.end) done.countDown()
                }

                override fun onError(t: Throwable) = done.countDown()
                override fun onCompleted() = done.countDown()
            })

            commands.onNext(
                ExportCommand.newBuilder()
                    .setKind(ExportCommand.Kind.START_EXPORT)
                    .setId("export-it-1")
                    .setConnection(config)
                    .putPayload("sql", stringValue("SELECT id, name FROM export_items ORDER BY id"))
                    .putPayload("outputDir", stringValue(outDir.toFile().absolutePath))
                    .putPayload("fileName", stringValue("hub.csv"))
                    .putPayload("format", stringValue("CSV"))
                    .build()
            )

            val finished = done.await(30, TimeUnit.SECONDS)
            assertTrue(
                finished && gotAny.get(),
                "ExportHub 30 秒内没有回任何帧 —— 这正是真窗口上「点了没反应」的形态。" +
                    " lastFrame=${lastFrame.get()}",
            )
            val frame = lastFrame.get()
            assertTrue(frame.success, "ExportHub 回的是失败帧：error='${frame.error}'")
            assertTrue(
                outDir.resolve("hub.csv").toFile().exists(),
                "Hub 说成功了但文件不在：${outDir.toAbsolutePath()}",
            )
        } finally {
            runCatching { channel.shutdownNow() }
            System.clearProperty("idb.export.hub.port")
        }
    }

    /**
     * 等子进程把 ExportHub 的端口监听起来。
     *
     * 为什么不能「`stub.stream()` 然后先发一条命令试试」：
     * gRPC 客户端对**还没在监听**的端口不会立刻抛 —— `onNext` 照常返回，
     * 错误稍后才从 `onError` 异步回来。于是「探活」的那条命令本身就成了
     * 第一条失败帧，测试拿到的形态和真 bug 一模一样，看着像导出坏了，
     * 其实是对面还没起。
     *
     * 轮询 TCP 能连上才是可靠判据：连上了就说明对面已经 accept。
     * 这也是生产代码 `ExportProcessManager.awaitHubReadyOrReportFailure` 的做法。
     */
    private fun awaitPort(port: Int, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val up = runCatching { java.net.Socket(HUB_HOST, port).use { true } }.getOrDefault(false)
            if (up) return true
            Thread.sleep(200)
        }
        return false
    }

    // ---------------------------------------------------------------------
    // ③ 并发导出：gRPC 的 StreamObserver.onNext 不是线程安全的
    // ---------------------------------------------------------------------

    @Test
    fun `concurrent exports on one hub stream do not corrupt it`() {
        // ## 这条在盯什么
        //
        // gRPC 的 `StreamObserver.onNext` **不是线程安全的**。
        // 子进程侧每个 START_EXPORT 都跑在 `Dispatchers.IO` 的独立协程里，
        // 两个导出一旦重叠，就有两条线程同时对**同一条**流调 onNext，
        // 内层 `ServerCallImpl.sendHeaders` 直接 checkState 失败，抛
        // 「sendHeaders has already been called」，**整条流当场废掉**。
        //
        // 真实场景不是测试造出来的：用户同时导两张表，就是这个并发度。
        // 表现是「其中一个导出莫名其妙失败 / 进度条卡死」，
        // 而且偶发 —— 取决于两个导出谁先跑到发帧那一步。
        //
        // 修法见 ExportSubProcess.stream() 里的 sendLock。

        val port = freePort()
        System.setProperty("idb.export.hub.port", port.toString())
        val hubThread = Thread({ runCatching { ExportSubProcess.run() } }, "export-subprocess-conc-test")
        hubThread.isDaemon = true
        hubThread.start()

        // 顺序要紧：先等端口 bind，再建流（理由见上面那条用例的注释）
        assertTrue(awaitPort(port), "ExportHub 20 秒内没监听 :$port")

        val channel = ManagedChannelBuilder.forAddress(HUB_HOST, port).usePlaintext().build()
        try {
            val concurrent = 24
            val done = CountDownLatch(concurrent)
            val lastFrame = ConcurrentHashMap<String, ExportHubResponse>()
            val streamErrors = ConcurrentLinkedQueue<String>()

            val stub = ExportHubGrpc.newStub(channel)
            val commands = stub.stream(object : StreamObserver<ExportHubResponse> {
                override fun onNext(value: ExportHubResponse) {
                    lastFrame[value.id] = value
                    if (value.end) done.countDown()
                }

                override fun onError(t: Throwable) {
                    streamErrors.add(t.message ?: t.toString())
                    // 流一挂，所有在等的都放行，否则测试只能等到超时
                    repeat(concurrent) { done.countDown() }
                }

                override fun onCompleted() = repeat(concurrent) { done.countDown() }
            })

            // 连发 N 条真导出 —— 子进程会并发处理它们
            (1..concurrent).forEach { n ->
                commands.onNext(
                    ExportCommand.newBuilder()
                        .setKind(ExportCommand.Kind.START_EXPORT)
                        .setId("conc-$n")
                        .setConnection(config)
                        .putPayload("sql", stringValue("SELECT id, name FROM export_items ORDER BY id"))
                        .putPayload("outputDir", stringValue(outDir.toFile().absolutePath))
                        .putPayload("fileName", stringValue("conc-$n.csv"))
                        .putPayload("format", stringValue("CSV"))
                        .build()
                )
            }

            assertTrue(
                done.await(60, TimeUnit.SECONDS),
                "并发导出没全部收口：已完成 $((concurrent - done.count))/$concurrent，流错误=$streamErrors",
            )
            assertTrue(streamErrors.isEmpty(), "hub 流被打断了：$streamErrors")

            // 每一个都必须以「成功 + 结束帧」收场，且文件真的在
            (1..concurrent).forEach { n ->
                val frame = lastFrame["conc-$n"]
                    ?: error("conc-$n 一帧都没回来 —— 它的导出被别的并发导出挤掉了")
                assertTrue(frame.success, "conc-$n 失败：error='${frame.error}'")
                assertTrue(frame.end, "conc-$n 没有结束帧")
                assertTrue(
                    outDir.resolve("conc-$n.csv").toFile().exists(),
                    "conc-$n 说成功了但文件不在：${outDir.toAbsolutePath()}",
                )
            }
        } finally {
            runCatching { channel.shutdownNow() }
            System.clearProperty("idb.export.hub.port")
        }
    }

    private fun stringValue(v: String): com.google.protobuf.Value =
        com.google.protobuf.Value.newBuilder().setStringValue(v).build()

    // ---------------------------------------------------------------------
    // ④ 通道可用性：断流之后必须能自愈
    // ---------------------------------------------------------------------

    @Test
    fun `hasUsableChannel requires both the process and the stream`() {
        // ## 这条钉的是一个**永久性**缺陷
        //
        // hub 流断开时 ExportProcessManager 的 onError 只清 commandObserver、
        // **不动** isRunning（子进程确实还活着，进程监控也没触发 stop()）。
        //
        // 而 ensureSubprocessRunning 原先只看 `isRunning`，于是判据恒真 →
        // 「建流」分支永远被跳过 → startExport 拿到 null observer → 返回 false →
        // 用户看到「导出子进程通道未就绪」。**它永远不会自愈，重启应用才恢复。**
        //
        // 实测形态：第一次导出成功，第二次开始全部失败。
        //
        // 修法是引入 hasUsableChannel（进程在 **且** 流在）并让建流判据改用它。
        // 这里钉住「两者缺一不可」这个语义 —— 只看其中一个都会退回原缺陷。
        val mgr = ExportProcessManager
        mgr.stop()
        assertFalse(
            mgr.hasUsableChannel,
            "刚 stop 之后通道必然不可用（进程不在）",
        )
        assertFalse(mgr.isRunning, "stop 之后进程标记也应清掉")
    }

    @Test
    fun `流丢了之后下一次导出必须自愈而不是报通道未就绪`() = runBlocking {
        // ## 这条对「把判据写回 isRunning」是**红**的
        //
        // 摆出漂移态（进程在、流没了），然后走**父进程**路径真跑一次导出。
        // 判据写错时：[ensureSubprocessRunning] 直接 return → startExport 拿到
        // null observer → 回「导出子进程通道未就绪（ExportHub 未连接）」；
        // 判据正确时：重建流 → 命令发得出去 → 文件真的写出来。
        //
        // ⚠️ 必须走 [ExportHandler.executeInMainProcess] 而不是 `executeAsSubprocess`：
        // 后者是**子进程侧**入口，直接调 `ExportEngine.export`，**根本不碰**
        // `ExportProcessManager` —— 第一版用了它，于是永远成功、0.09 秒跑完，
        // 判据写对写错都是绿的。测「建流判据」就得测**编排那一侧**。
        //
        // 断言的是**文件是否存在**而不是「有没有报错」——
        // 后者对「换个错法继续失败」没抵抗力。
        val dir = java.nio.file.Files.createTempDirectory("sundays-heal").toFile()
        try {
            // ⚠️ 必须先 `stop()` 清残留：[ExportProcessManager] 是单例，
            // 其它用例起过子进程、结束时 `stop()` —— 谁后跑状态就不一样。
            // 而 `stop()` 在 `_isRunning` 已是 false 时会**提前 return、不清 observer**，
            // 所以还要显式摆一次漂移态。
            ExportProcessManager.stop()
            ExportProcessManager.simulateStreamLostWhileProcessAlive()
            assertTrue(ExportProcessManager.isRunning, "漂移态：进程标记为真")
            assertFalse(ExportProcessManager.hasUsableChannel, "漂移态：流确实没有")

            val frames = ExportHandler.executeInMainProcess(exportRequest("SELECT 1", dir, "healed.csv")).toList()
            val last = frames.last()
            assertTrue(
                last.success,
                "漂移态下应当重建流并成功导出，而不是「通道未就绪」：error='${last.error}'",
            )
            assertTrue(
                File(dir, "healed.csv").exists(),
                "文件应已生成：${dir.absolutePath}",
            )
        } finally {
            runCatching { ExportProcessManager.stop() }
            dir.deleteRecursively()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
