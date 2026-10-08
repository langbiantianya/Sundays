package com.kxxnzstdsw.export

import com.google.protobuf.Value
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.ExportCommand
import com.kxxnzstdsw.grpc.ExportCommand.Kind
import com.kxxnzstdsw.grpc.ExportHubGrpc
import com.kxxnzstdsw.grpc.ExportHubResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.ExportProgressFrame
import com.kxxnzstdsw.grpc.ExportResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导出子进程管理器（gRPC 模式 — 父进程作为 gRPC client）
 *
 * 拓扑：
 * ```
 *   [父进程]                                       [子进程]
 *   ManagedChannel                                gRPC Server
 *       │                                              ▲
 *   ExportHubCoroutineStub ──── bidi stream ────► ExportHubImpl
 *       │  sends: ExportCommand (START/STOP/SHUTDOWN)    │
 *       │  receives: ExportHubResponse (progress)           │
 * ```
 *
 * 子进程监听在固定端口（默认 50099），由父进程通过命令行参数告知端口号。
 * 每个 exportId 的进度响应通过内部的 SharedFlow 转发给上游 gRPC StreamObserver。
 */
object ExportProcessManager {

    /**
     * 连子进程用的地址 —— **写死 IPv4 回环，不写 `localhost`**。
     *
     * 导出子进程永远和父进程在同一台机器上，所以根本没有「跨主机」这一说。
     * 而 `localhost` 是有歧义的：Windows 上它同时解析出 `127.0.0.1` 和 `::1`，
     * 顺序还可能变。gRPC 的地址解析一旦挑了 `::1`，而对端只监听了 IPv4，
     * 就会得到一句莫名其妙的 `UNAVAILABLE: io exception`
     * （真实堆栈：`Connection refused: getsockopt: localhost/[0:0:0:0:0:0:0:1]:60467`）。
     *
     * 这不是测试里才有的问题：同样的 `localhost` 就在生产链路上（建流 + 探活），
     * 一旦命中，用户看到的就是「导出无反应」，且**偶发、无法复现**。
     *
     * 写死 `127.0.0.1` 没有歧义，Windows / Linux / macOS 上都必然存在。
     */
    private const val EXPORT_HUB_HOST = "127.0.0.1"

    private val logger = LoggerFactory.getLogger(ExportProcessManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 默认子进程监听端口
    private const val DEFAULT_EXPORT_HUB_PORT = 50099

    /**
     * 父进程用来指定 hub 端口的系统属性名 —— 同样会传给子进程。
     *
     * 优先级高于环境变量 `IDB_EXPORT_HUB_PORT`：测试里要能指定一个空闲端口，
     * 而环境变量在 JVM 起来之前就定死了、改不了。
     */
    const val HUB_PORT_PROPERTY = "idb.export.hub.port"

    // 子进程 process
    @Volatile
    private var process: Process? = null

    // 子进程侧 hub 端口
    @Volatile
    private var hubPort: Int = DEFAULT_EXPORT_HUB_PORT

    // 父进程侧 gRPC channel（连接子进程）
    @Volatile
    private var channel: ManagedChannel? = null

    // 当前 bidi stream 的 request observer（用于向子进程发送 ExportCommand）
    @Volatile
    private var commandObserver: StreamObserver<ExportCommand>? = null

    /**
     * 发命令的串行化锁。
     *
     * gRPC 客户端的 `StreamObserver.onNext` 同样**不是线程安全的**：并发调用会让
     * `ClientCallImpl` 内部的写竞争把流搞坏（丢帧 / 整条流异常终止）。
     * 用户同时导两张表时，两个 `startExport` 就在并发调它 —— 所以所有发命令的
     * 路径（start / stop / shutdown）都必须过这把锁。
     *
     * 锁对象本身也替换：换流的同时换锁，避免新流去抢旧流正在持有的锁。
     */
    @Volatile
    private var sendLock = Any()

    /**
     * 线程安全地向子进程发一条命令。
     *
     * @return `true` = 帧已交给流；`false` = 没有可用通道或发送抛异常。
     */
    private fun sendCommand(observer: StreamObserver<ExportCommand>, cmd: ExportCommand): Boolean =
        synchronized(sendLock) {
            try {
                observer.onNext(cmd)
                true
            } catch (e: Exception) {
                logger.warn("Failed to send command ${cmd.kind}", e)
                System.err.println("[export] 命令发送失败 ${cmd.kind}: ${e.message}")
                false
            }
        }

    // 每个 exportId 的响应 SharedFlow
    private val responseFlows = ConcurrentHashMap<String, MutableSharedFlow<Response>>()

    /**
     * 已经收到过终止帧的 exportId。
     *
     * 存在的理由是一个具体的竞态：`publishResponse` 收到终止帧后**不能**立刻把
     * flow 从 [responseFlows] 里删掉 —— `ExportHandler` 是先 `startExport`
     * 再 `collectResponses(id)` 的，删早了会造出一条新的空流，终止帧就丢了。
     * 所以删除被 `delay(50)` 推后。
     *
     * 而这 50ms 里子进程一死，[failPendingExports] 就会给一条**已经成功**的导出
     * 补一帧失败 —— 于是界面上「导出成功了」后面又跟一句失败。
     * 有了这个集合，「已收口」就不必用「已从 map 里消失」来表达。
     */
    private val endedExports = ConcurrentHashMap.newKeySet<String>()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    /**
     * 父进程自己的导出日志 —— 与子进程日志写到**同一个文件**。
     *
     * ## 为什么父进程也要落盘
     *
     * 子进程日志早就有文件重定向，但父进程这边只有 `System.err` / SLF4J。
     * 桌面应用被 IDE / Gradle 启动时那些 stderr **用户根本看不到** ——
     * 于是排查「第一次导出成功、第二次报通道未就绪」时，能看到的只有子进程日志，
     * 而**断流发生在父进程这一侧**，日志正好在盲区里。
     *
     * 写成同目录的 `export-manager.log`：父子两条链路一起看，顺序也连得上。
     */
    private fun managerLog(message: String) {
        logger.info(message)
        System.err.println(message)
        runCatching {
            val file = resolveLibsDir(null)?.resolve("export-manager.log") ?: return@runCatching
            file.parentFile?.mkdirs()
            file.appendText("${System.currentTimeMillis()} $message`n")
        }
    }

    /**
     * 导出通道**当下能不能用** —— 判据是「进程在」**且**「流在」。
     *
     * ## 为什么不直接用 [isRunning]
     *
     * 这两个状态会**不一致**：[onError] / [onCompleted] 在 hub 流断开时只清
     * `commandObserver`、**不动** `_isRunning`（子进程确实还活着，进程监控线程
     * 也没触发 `stop()`）。于是 `isRunning == true` 而 `commandObserver == null`。
     *
     * 后果是致命的：[ExportHandler.ensureSubprocessRunning] 原先只看 `isRunning`，
     * 于是「跳过建流」→ `startExport` 拿到 null observer → 返回 false →
     * 用户看到「导出子进程通道未就绪」。而**它永远不会自愈**：
     * 那个判据永远为真，重建流的代码永远不执行。
     * 实测形态：第一次导出成功，第二次开始**全部**失败，重启应用才恢复。
     *
     * 所以凡是「能不能发命令」的判断都必须走这个，不能走 [isRunning]。
     */
    val hasUsableChannel: Boolean get() = _isRunning.get() && commandObserver != null

    /**
     * 子进程**此刻**还活着吗 —— 判据是 [Process.isAlive]，**不是** [_isRunning]。
     *
     * ## 为什么 [isRunning] 当不了这个判据
     *
     * `_isRunning` 的语义只是「**曾经**拉起过一个子进程」，而且**只有**本对象自己会改它：
     * [stop] 改、进程监控线程改。子进程**自己崩掉**（OOM、驱动炸、被外部杀掉）时，
     * 标记还留在 true 上 —— 于是：
     *
     * - 「能不能发命令」→ [hasUsableChannel] 判 false，正确
     * - 「要不要重启子进程」→ 旧代码看 `isRunning`，得到「在，不用重启」，**错了**
     *
     * 后果实测（`ExportPipelineIntegrationTest` 稳定复现）：
     * 漂移态（标记说进程在、实际已死）下不去重启，只去 [awaitHubReadyOrReportFailure]，
     * 而那是在等一个**已经没人监听的端口** —— 白等满 30 秒后
     * 「导出子进程通道未就绪」，**永远不会自愈**。
     *
     * 「重启要等 30 秒才失败」和「根本不自愈」是两个量级的差别，
     * 所以这条判据必须是「进程**现在**还在不在」，而不是「上次拉起时在不在」。
     */
    val isProcessAlive: Boolean get() = runCatching { process?.isAlive == true }.getOrDefault(false)

    /**
     * **仅测试用**：把状态摆成「进程还活着、但流没了」这个漂移态。
     *
     * ## 为什么需要这个钩子
     *
     * 那个漂移态是 `onError` 的自然产物，**正常路径下无法从外部构造** ——
     * 而它恰恰是本缺陷的触发条件。没有它，「修了没有」就只能靠真窗口里
     * 碰一次断流来试，那既不可复现也不该成为回归手段。
     *
     * 有了它，[ExportPipelineIntegrationTest] 才能真正端到端地验：
     * 摆出漂移态 → 发起导出 → 断言**不是**「通道未就绪」，
     * 也就是「修法真的会重建通道」。这一条对把判据写回 `isRunning` 的变异是红的。
     *
     * ⚠️ 名字里的 `WhileProcessAlive` 是历史遗留，实测下来它造的其实是**更糟**的一档：
     * 调用前测试刚 `stop()` 过，**子进程是真的没了**，只是标记被强行按成 true。
     * 于是漂移态 = 「标记说进程在、实际已死」，正是子进程自己崩掉后的真实形态。
     * 保留这个名字是为了不改已有调用点，但判断代码时按
     * 「`isRunning` 与 `isProcessAlive` 不一致」来理解，不要当成「进程健在、只是流断了」。
     *
     * 不用反射是刻意的：`commandObserver` 是 private 字段，
     * 反射改它既脆弱又绕过类型检查。
     */
    internal fun simulateStreamLostWhileProcessAlive() {
        _isRunning.set(true)
        commandObserver = null
    }

    /**
     * 启动子进程并连接 gRPC channel
     *
     * @param jarPath idb-engine.jar 路径
     */
    fun start(jarPath: String?): Int {
        // 原子抢占 — 防止两个 caller 同时启动两个子进程
        if (!_isRunning.compareAndSet(false, true)) {
            logger.warn("Export subprocess already running"); managerLog("[export] Export subprocess already running（复用）")
            return hubPort
        }

        hubPort = System.getProperty(HUB_PORT_PROPERTY)?.toIntOrNull()
            ?: System.getenv("IDB_EXPORT_HUB_PORT")?.toIntOrNull()
            ?: DEFAULT_EXPORT_HUB_PORT
        // ⚠️ 端口被占就换一个，别让整个导出死掉。
        //
        // 原来固定 50099：上一个 sundays 的导出子进程还没退（或者另一个实例正在导出），
        // 新子进程就会 `BindException: Address already in use` 启动失败 —— 端口是**进程之间**
        // 的约定，不是用户的配置，不该由用户来承担这个冲突。
        // 端口号会通过 `-Didb.export.hub.port` 传给子进程，父子两边自然一致。
        if (!isPortFree(hubPort)) {
            val fallback = freePort()
            logger.warn("导出 hub 端口 $hubPort 已被占用，改用 $fallback"); System.err.println("[export] hub 端口 $hubPort 被占用，改用 $fallback")
            hubPort = fallback
        }

        // 1. 启动子进程
        //
        // ⚠️ `jarPath` 允许为 null —— 那表示「classpath 上没有 idb-engine.jar」，
        // 此时退回**本进程自己的 classpath**。
        //
        // 为什么要这条退路：Gradle 跑测试/`:run` 时，classpath 上是
        // `engine/build/classes/...` 目录而不是 jar，于是 `findEngineJarPath()` 返回 null，
        // 导出链路在**开发与 CI 环境**直接返回「Cannot find idb-engine.jar path」。
        // 换句话说这条链路只在打包产物里被验证过 —— 那正是它烂掉这么久的原因。
        val libsDir = resolveLibsDir(jarPath)
        val classPath = buildSubprocessClassPath(jarPath, libsDir)

        try {
            val javaHome = System.getProperty("java.home")
            val javaExe: String = File(File(javaHome), "bin/java").takeIf { it.exists() }?.absolutePath
                ?: File(File(javaHome), "bin/java.exe").takeIf { it.exists() }?.absolutePath
                ?: "java"
            val parentMaxMem = Runtime.getRuntime().maxMemory()
            val childMaxMem = maxOf(parentMaxMem, 256L * 1024 * 1024)

            // ⚠️ 参数走 **`@argfile`**，不直接拼命令行。
            //
            // 开发 / 测试环境下 classpath 是整个 Gradle 测试运行时（几百个条目），
            // 直接拼进命令行会在 Windows 上撞 `CreateProcess error=206
            // 「文件名或扩展名太长」` —— 于是子进程**根本起不来**。
            // Java 9+ 支持 `@file` 从文件读参数，这里每行一个参数、带空格的加引号。
            val javaArgs = listOf(
                "-Xmx${formatMem(childMaxMem)}",
                "-Xms${formatMem(childMaxMem)}",
                "-XX:+UseSerialGC",
                "-Didb.subprocess=true",
                "-D$HUB_PORT_PROPERTY=$hubPort",
                "-cp",
                classPath,
                "com.kxxnzstdsw.export.ExportSubProcess",
            )
            val argFile = (libsDir ?: File(".")).let { dir ->
                dir.mkdirs()
                File(dir, "export-subprocess.args")
            }
            argFile.writeText(javaArgs.joinToString("\n") { if (it.contains(' ')) "\"$it\"" else it })

            val builder = ProcessBuilder(javaExe, "@${argFile.absolutePath}")
            builder.directory(libsDir ?: File("."))
            // ⚠️ 子进程的 stdout / stderr **落盘到文件**，不继承父进程。
            //
            // 原来 `builder.redirectErrorStream(false)` 且没有 redirect，
            // 于是子进程的日志跟着父进程的 stdout 走 —— 而桌面应用是被
            // IDE / Gradle / 快捷方式启动的，那些 stdout **用户根本看不到**。
            // 导出链路一旦出问题，唯一的诊断入口就是「界面没反应」，
            // 连子进程有没有起来都判断不了。
            //
            // 现在固定写到 `engine/build/libs/export-subprocess.log`：
            // 出问题直接看这个文件就行。
            val logFile = runCatching {
                val dir = libsDir ?: File(".")
                dir.mkdirs()
                File(dir, "export-subprocess.log").also { it.delete() }
            }.getOrNull()
            if (logFile != null) {
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                logger.info("导出子进程日志: ${logFile.absolutePath}"); System.err.println("[export] 子进程日志: ${logFile.absolutePath}")
            } else {
                builder.redirectErrorStream(false)
            }

            // Windows + Parquet: 设置 HADOOP_HOME 指向 libs/ 目录
            if (System.getProperty("os.name").lowercase().contains("win") && libsDir != null) {
                builder.environment()["HADOOP_HOME"] = libsDir.absolutePath
                builder.environment()["hadoop.home.dir"] = libsDir.absolutePath
            }

            process = builder.start()
            managerLog("[export] 子进程已启动 pid=${process?.pid()} classpath=${classPath.split(File.pathSeparator).size} 项 cwd=${libsDir}")

            // 监控进程退出
            scope.launch {
                val code = process?.waitFor()
                logger.info("Export subprocess exited with code: $code"); System.err.println("[export] 子进程退出 code=$code")
                // ⚠️ 子进程没了 ⇒ 所有**在等**的导出都不可能再有回帧。
                //
                // 这一步以前只是 `stop()`：流被关掉、observer 置空，而
                // 每个 exportId 的 SharedFlow **一帧都没写**。主进程那边
                // `collectResponses(id).collect { }` 于是永远挂着 ——
                // 界面上「点了导出，什么也没发生」，磁盘上也没有文件。
                //
                // 子进程在启动阶段就崩掉（classpath 缺驱动等）是最容易触发它的场景：
                // 连 ExportHub 都没起来，自然一帧都发不出来。
                failPendingExports("导出子进程已退出（code=$code），导出中止")
                stop()
            }

            // 2. gRPC 流**不在这里建** ——
            //
            // 原来是在这里 `stub.stream(...)`。但 `stream()` 是**立即返回**的：
            // 子进程那会儿还没 bind（要 2~3 秒才起得来），gRPC 立刻回调
            // `onError(UNAVAILABLE)` → 我们把 `commandObserver` 置成 null，
            // 而 `isRunning` 还是 true → 之后**再也不会重建这条流**。
            // 于是 `startExport` 永远拿到 null observer，命令发不出去。
            //
            // 真正的连接挪到 [awaitHubReadyOrReportFailure]：端口能连上之后再建流。
            logger.info("Export subprocess launched (ExportHub will listen on :$hubPort)")
        } catch (e: Exception) {
            logger.error("Failed to start export subprocess", e)
            stop()
            throw e
        }
        return hubPort
    }

    /**
     * 将子进程返回的 ExportHubResponse 转发到对应 exportId 的 SharedFlow
     *
     * 子进程返回的是 subprocess wire 形态（ExportHubResponse with Value data），
     * 我们在主进程边界把它转换为对外的 typed Response（ExportProgressFrame body）。
     */
    private fun publishResponse(exportResp: ExportHubResponse) {
        val flow = responseFlows.computeIfAbsent(exportResp.id) {
            MutableSharedFlow<Response>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        // 转换为对外暴露的 typed gRPC Response
        val response = if (exportResp.success && exportResp.hasData() && exportResp.data.kindCase == Value.KindCase.STRUCT_VALUE) {
            // progress frame — 解 Value → JsonObject → typed ExportProgressFrame
            val progress = PayloadAdapter.toJsonElement(exportResp.data) as? kotlinx.serialization.json.JsonObject
            if (progress != null) {
                val pb = ExportProgressFrame.newBuilder()
                    .setExportedRows(progress["exportedRows"]?.jsonPrimitive?.longOrNull ?: 0L)
                    .setColumnCount(progress["columnCount"]?.jsonPrimitive?.intOrNull ?: 0)
                    .setCompleted(progress["completed"]?.jsonPrimitive?.booleanOrNull ?: false)
                progress["filePath"]?.jsonPrimitive?.let { if (it.content.isNotEmpty()) pb.setFilePath(it.content) }
                progress["error"]?.jsonPrimitive?.let { if (it.content.isNotEmpty()) pb.setError(it.content) }
                val exportRespMsg = ExportResponse.newBuilder().setProgress(pb).build()
                Response.newBuilder()
                    .setId(exportResp.id)
                    .setSuccess(true)
                    .setStream(true)
                    .setEnd(exportResp.end)
                    .setExport(exportRespMsg)
                    .build()
            } else {
                errorResponse(exportResp)
            }
        } else {
            errorResponse(exportResp)
        }
        scope.launch {
            runCatching { flow.emit(response) }
                .onFailure { System.err.println("[export] 帧投递失败 id=${exportResp.id}: $it") }
            // ⚠️ `${exportResp.end}` 的花括号不能省：`$exportResp.end` 只会替换
            // `$exportResp` 这一个标识符（打出整条 protobuf 消息的 toString），
            // `.end` 会变成字面量 —— 于是日志里出现过
            // 「帧已投递 id=id: "3226356f-…" end=id: "3226356f-…"」这种鬼话。
            System.err.println("[export] 帧已投递 id=${exportResp.id} end=${exportResp.end}")
        }
        if (exportResp.end) {
            // 终止帧一到就**同步**记账。
            //
            // 为什么不能靠「把 flow 从 map 里删掉」来表达「这个导出结束了」：
            // `ExportHandler` 是先 `startExport`、**后** `collectResponses(id)`，
            // 删早了它会 `computeIfAbsent` 造一条**全新的**空流，
            // 终止帧就永远送不到 —— 所以真正删除必须延后（见下）。
            // 延后就留出一个窗口：子进程在终止帧后 50ms 内死掉，
            // [failPendingExports] 会把一条**已经成功**的导出补一帧失败。
            endedExports.add(exportResp.id)
            scope.launch {
                delay(50)
                responseFlows.remove(exportResp.id)
                endedExports.remove(exportResp.id)
            }
        }
    }

    private fun errorResponse(exportResp: ExportHubResponse): Response =
        Response.newBuilder()
            .setId(exportResp.id)
            .setSuccess(exportResp.success)
            .setError(exportResp.error)
            .setStream(exportResp.stream)
            .setEnd(exportResp.end)
            .build()

    /**
     * 获取指定 exportId 的响应流（供 ExportHandler.collectResponses 调用）
     */
    fun collectResponses(exportId: String): SharedFlow<Response> {
        val flow = responseFlows.computeIfAbsent(exportId) {
            MutableSharedFlow<Response>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        return flow.asSharedFlow()
    }
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * 等子进程的 ExportHub **真的能连上**，然后建立 hub 流。幂等。
     *
     * ## 为什么不能是「起进程 → 延时 N 毫秒 → 建流」
     *
     * `stub.stream()` 是**立即返回**的，而子进程要 2~3 秒才起得来（JVM + 方言插件）。
     * 于是 gRPC 立刻回调 `onError(UNAVAILABLE)`，我们把 `commandObserver` 置成 null，
     * 而 `isRunning` 仍是 true —— 之后再没有任何代码会重建这条流。
     * 表现是：命令永远发不出去（`startExport` 拿到 null observer），
     * 上游无限等待，界面上「点了导出，什么也没发生」（TEST_CASES.md §9.15）。
     *
     * 轮询 TCP 连接是这里唯一靠得住的判据：**能连上就说明对面已经在 accept**，
     * 此时再建流就不会撞 UNAVAILABLE。
     */
    suspend fun awaitHubReadyOrReportFailure(timeoutMs: Long = 30_000) {
        if (commandObserver != null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        var ready = false
        while (System.currentTimeMillis() < deadline && !ready) {
            ready = runCatching { java.net.Socket(EXPORT_HUB_HOST, hubPort).use { true } }.getOrDefault(false)
            if (!ready) kotlinx.coroutines.delay(100)
        }
        if (!ready) {
            managerLog("[export] 等待 hub 端口 $hubPort 就绪超时（${timeoutMs}ms，observer=${commandObserver != null}）")
            failPendingExports("导出子进程在 ${timeoutMs / 1000} 秒内没有就绪")
            return
        }
        managerLog("[export] hub 端口 $hubPort 已就绪，建立流")
        val ch = ManagedChannelBuilder.forAddress(EXPORT_HUB_HOST, hubPort)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .build()
        channel = ch

        val stub = ExportHubGrpc.newStub(ch)
        val responseObserver = object : StreamObserver<ExportHubResponse> {
            override fun onNext(value: ExportHubResponse) {
                System.err.println("[export] 收到子进程帧 id=${value.id} end=${value.end} success=${value.success} 有数据=${value.hasData()}")
                publishResponse(value)
            }

            override fun onError(t: Throwable) {
                logger.error("ExportHub bidi stream error", t)
                managerLog("[export] hub 流出错（commandObserver 置空，isRunning 保持不变）: $t")
                commandObserver = null
                // ⚠️ 旧 channel 必须在这里释放 —— 它已经废了（流断了），
                // 而 [awaitHubReadyOrReportFailure] 重建时会**新建**一个。
                // 不关的话每次断流都漏一条连接池，导出用得越久漏得越多。
                runCatching { channel?.shutdownNow() }
                channel = null
                // 流断了 ⇒ 这一局里所有在等的导出都不可能再有回帧。
                // 不补失败帧的话，上游就是无限等待（§9.15 的老毛病）。
                failPendingExports("导出通道断开：${t.message ?: t::class.java.simpleName}")
            }

            override fun onCompleted() {
                managerLog("[export] 子进程关闭了 hub 流（commandObserver 置空）")
                commandObserver = null
                runCatching { channel?.shutdownNow() }
                channel = null
                failPendingExports("导出通道已关闭")
            }
        }
        // 换流的同时换锁：新流不去抢旧流正在持有的锁
        sendLock = Any()
        commandObserver = stub.stream(responseObserver)
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /**
     * 引擎的运行时资源目录（`build/libs`，下面挂着 `drivers/` 与 `dialects/`）。
     *
     * 优先从 jar 路径推；推不出来（Gradle 测试 / `:run` 的 classpath 上只有 classes 目录）
     * 就从当前工作目录往上找 `engine/build/libs`。
     */
    private fun resolveLibsDir(jarPath: String?): File? {
        jarPath?.let { return File(it).parentFile?.absoluteFile }
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            val candidate = File(cur, "engine/build/libs")
            if (candidate.isDirectory) return candidate.absoluteFile
            cur = cur.parentFile
        }
        return null
    }

    /**
     * 拼导出子进程的 classpath —— **本进程的 classpath** + 资源目录下的每一个 jar。
     *
     * ## 为什么先放本进程的 classpath
     *
     * 原来是把 `idb-engine.jar` 放第一位。在打包环境那是对的，但在**开发 / 测试**环境里
     * jar 常常是陈旧的（`./gradlew test` 不会重建它），于是子进程跑的是**旧代码** ——
     * 实测就撞上过：
     * `NoClassDefFoundError: ExportSubProcess$ExportHubImpl$stream$1`。
     *
     * 先用本进程的 classpath：它永远和父进程是同一份代码；
     * 再把 `drivers/` / `dialects/` 里的 jar 追加上去补资源。
     * 打包环境里本进程的 classpath 就是那个 jar，行为不变。
     *
     * ## 为什么不能只拼 `libs/`
     *
     * `engine/build/libs/` 下并排放着**三个** jar 目录：
     *
     * | 目录 | 内容 | 少了会怎样 |
     * |---|---|---|
     * | `libs/` | 第三方依赖（gRPC / HikariCP / protobuf…） | 引擎自己起不来 |
     * | `drivers/` | **JDBC 驱动**（mysql-connector-j 等） | 连不上任何真实库 |
     * | `dialects/` | 方言插件 | 认不出 `MYSQL` |
     *
     * 原来只拼 `libs/`，于是导出子进程**装不上 JDBC 驱动**。
     * 按目录枚举而不是写死某一个：以后再加一个 jar 目录也不会漏。
     */
    private fun buildSubprocessClassPath(jarPath: String?, libsDir: File?): String {
        val entries = LinkedHashSet<String>()
        jarPath?.takeIf { it.isNotBlank() }?.let { entries += it }
        System.getProperty("java.class.path", "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { entries += it }
        if (libsDir != null && libsDir.isDirectory) {
            libsDir.listFiles { f: File -> f.isDirectory }
                ?.flatMap { dir -> dir.listFiles { f: File -> f.extension == "jar" }?.toList().orEmpty() }
                ?.forEach { entries += it.absolutePath }
        }
        return entries.joinToString(File.pathSeparator)
    }
    /**
     * 给所有**还没收口**的导出补一帧失败。
     *
     * 没有这一步，「子进程没了」这件事只活在日志里；调用方那边是
     * 一个永远不结束的 `collect` —— 用户看到的是彻底没有反馈。
     *
     * 已经收到过 `completed` 的导出由 [endedExports] 记着 —— 它们的 flow 还留在
     * [responseFlows] 里（要等 `ExportHandler.collectResponses` 订阅上才能删），
     * 但它们**已经结束了**，不能再收到失败帧。
     */
    private fun failPendingExports(reason: String) {
        val pending = responseFlows.keys.filterNot { it in endedExports }
        if (pending.isEmpty()) return
        logger.warn("Export subprocess gone, failing ${pending.size} pending export(s): $pending"); System.err.println("[export] 子进程消失，给 ${pending.size} 个在等的导出补失败帧")
        pending.forEach { exportId ->
            val flow = responseFlows.remove(exportId) ?: return@forEach
            scope.launch {
                flow.emit(
                    Response.newBuilder()
                        .setId(exportId)
                        .setSuccess(false)
                        .setStream(true)
                        .setEnd(true)
                        .setError(reason)
                        .build()
                )
            }
        }
    }

    /**
     * 发送导出启动命令到子进程。
     *
     * ## 返回值为什么不能省
     *
     * 原来这里是 `Unit` + 「失败只写日志」。而调用方 [com.kxxnzstdsw.handlers.ExportHandler]
     * 发完命令就去 `collectResponses(id)` —— **那个 SharedFlow 只有子进程回帧才会被写**。
     * 于是「通道没就绪」这一种失败，前端的表现是：
     *
     * ```
     * 点「开始导出」 → 对话框关闭 → 引擎什么都没回 → collect 永久挂起
     *   → 既没有成功、也没有失败 → 界面上一个字都没有，磁盘上也没有文件
     * ```
     *
     * 用户既不知道成没成，也不知道该不该重试；而日志默认不进控制台，等于**彻底静默**。
     * 所以这里把「命令有没有真的交给 gRPC 流」变成返回值，由调用方收口成终止帧。
     *
     * @return `true` = 命令已交给流；`false` = 通道未就绪或发送抛异常。
     */
    fun startExport(id: String, connection: ConnectionConfig, payload: Map<String, Value>): Boolean {
        val observer = commandObserver
        if (observer == null) {
            managerLog("[export] 通道未就绪，命令没发出去 id=$id（isRunning=$isRunning observer=${commandObserver != null}）")
            return false
        }
        val cmd = ExportCommand.newBuilder()
            .setKind(Kind.START_EXPORT)
            .setId(id)
            .setConnection(connection)
            .putAllPayload(payload)
            .build()
        val ok = sendCommand(observer, cmd)
        if (ok) {
            managerLog("[export] 命令已下发 id=$id")
        } else {
            logger.error("Failed to send START_EXPORT command for $id")
        }
        return ok
    }

    /**
     * 发送导出停止命令到子进程
     */
    fun stopExport(exportId: String) {
        val observer = commandObserver
        if (observer == null) {
            logger.warn("Cannot stop export: stream not open")
            return
        }
        val cmd = ExportCommand.newBuilder()
            .setKind(Kind.STOP_EXPORT)
            .setExportId(exportId)
            .build()
        if (sendCommand(observer, cmd)) {
            logger.info("Sent STOP_EXPORT command: $exportId")
        }
    }

    /**
     * 停止子进程并清理
     */
    fun stop() {
        if (!_isRunning.getAndSet(false)) {
            return
        }
        logger.info("Stopping export subprocess...")
        try {
            commandObserver?.let { obs ->
                // SHUTDOWN 也走 sendCommand —— 它可能和某个还在跑的 startExport 撞上
                sendCommand(obs, ExportCommand.newBuilder().setKind(Kind.SHUTDOWN).build())
                runCatching { synchronized(sendLock) { obs.onCompleted() } }
            }
            commandObserver = null
        } catch (_: Exception) { /* ignore */ }

        try { channel?.shutdownNow()?.awaitTermination(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        channel = null

        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null

        responseFlows.clear()
        logger.info("Export subprocess stopped")
    }

    private fun formatMem(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)}g"
            else -> "${bytes / (1024 * 1024)}m"
        }
    }
}
