package com.kxxnzstdsw.export

import com.kxxnzstdsw.handlers.ExportHandler
import com.kxxnzstdsw.grpc.ExportCommand
import com.kxxnzstdsw.grpc.ExportCommand.Kind
import com.kxxnzstdsw.grpc.ExportHubGrpc
import com.kxxnzstdsw.grpc.ExportHubResponse
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 导出子进程入口（gRPC 模式 — 子进程作为 gRPC server）
 *
 * 监听端口从 -Didb.export.hub.port 读取（默认 50099）。
 * 每个父进程连接对应一个 bidi stream：
 * - 接收 ExportCommand 流（START_EXPORT / STOP_EXPORT / SHUTDOWN）
 * - 推送 ExportHubResponse 流（每条命令的执行进度）
 */
object ExportSubProcess {

    private val logger = LoggerFactory.getLogger(ExportSubProcess::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var server: io.grpc.Server? = null

    @Volatile
    private var shouldStop = false

    // 当前正在执行的 export id → 协程引用
    private val activeExports = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    @JvmStatic
    fun main(args: Array<String>) {
        run()
    }

    fun run() {
        val port = System.getProperty("idb.export.hub.port")?.toIntOrNull() ?: 50099
        logger.info("Export SubProcess starting (gRPC mode), listening on :$port"); System.err.println("[export-sub] 启动，监听 :$port")

        // 加载 drivers / dialects（子进程独立 classpath）
        try {
            val baseDir = findBaseDir()
            com.kxxnzstdsw.loader.DriverLoader.loadFromDir(File(baseDir, "drivers"))
            com.kxxnzstdsw.loader.DialectLoader.loadFromDir(File(baseDir, "dialects"))
            logger.info("Loaded drivers and dialects from $baseDir"); System.err.println("[export-sub] 驱动/方言已加载: $baseDir")
        } catch (e: Exception) {
            logger.warn("Failed to load drivers/dialects", e); System.err.println("[export-sub] 驱动/方言加载失败: ${e.message}")
        }

        // 优雅退出 hook
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown hook triggered")
            shouldStop = true
            try { server?.shutdownNow() } catch (_: Exception) {}
            stopAllExports()
        })

        server = ServerBuilder.forPort(port)
            .addService(ExportHubImpl())
            .maxInboundMessageSize(256 * 1024 * 1024)
            .build()
            .also { it.start() }

        logger.info("ExportHub gRPC server started on :$port"); System.err.println("[export-sub] ExportHub 已启动 :$port")

        try {
            server?.awaitTermination()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            logger.error("Server failed", e)
        } finally {
            stopAllExports()
            logger.info("Export SubProcess stopped")
            exitProcess(0)
        }
    }

    /**
     * gRPC 服务实现
     */
    private class ExportHubImpl : ExportHubGrpc.ExportHubImplBase() {
        override fun stream(responseObserver: StreamObserver<ExportHubResponse>): StreamObserver<ExportCommand> {
            logger.info("Parent connected to ExportHub"); System.err.println("[export-sub] 父进程已连接")

            // ⚠️ gRPC 的 `StreamObserver.onNext` **不是线程安全的**。
            //
            // 每个 START_EXPORT 都跑在 `scope`(Dispatchers.IO) 的独立协程里，
            // 多个导出并发时就会有多条线程同时对**同一条**流调 onNext，
            // 内层 `ServerCallImpl.sendHeaders` 直接 `checkState(!headersSent)`
            // 抛 "sendHeaders has already been called" —— 整条流当场废掉，
            // 用户的体感就是「导到一半卡死 / 莫名其妙失败」。
            //
            // 真实场景：同时导两张表、或一个探活命令和真导出重叠。
            // 所以这里给每条流配一把锁，把「发帧」串行化。
            // 用对象锁而不是全局锁：不同父进程连接之间互不阻塞。
            val sendLock = Any()

            /** 线程安全地发一帧；流已关闭时返回 false 而不是把异常抛回业务协程。 */
            fun sendFrame(response: ExportHubResponse): Boolean = synchronized(sendLock) {
                try {
                    responseObserver.onNext(response)
                    true
                } catch (e: Exception) {
                    // 流已被父进程关闭时会走到这里。不该让一个已废的流把导出协程带崩
                    logger.warn("Failed to send frame on ExportHub stream", e)
                    System.err.println("[export-sub] 发帧失败（流可能已关闭）: ${e.message}")
                    false
                }
            }

            return object : StreamObserver<ExportCommand> {
                override fun onNext(cmd: ExportCommand) {
                    logger.debug("Subprocess received command: ${cmd.kind}"); System.err.println("[export-sub] 收到命令 ${cmd.kind} id=${cmd.id}")
                    when (cmd.kind) {
                        // `::sendFrame` 必须显式取引用：裸写 `sendFrame` 会被 Kotlin 当成一次调用
// （报 "Function invocation expected"），而不是一个函数值。
Kind.START_EXPORT -> handleStartExport(cmd, ::sendFrame)
                        Kind.STOP_EXPORT -> handleStopExport(cmd.exportId)
                        Kind.SHUTDOWN -> {
                            logger.info("Received SHUTDOWN")
                            synchronized(sendLock) { runCatching { responseObserver.onCompleted() } }
                            scope.launch {
                                kotlinx.coroutines.delay(100)
                                server?.shutdownNow()
                            }
                        }
                        Kind.KIND_UNSPECIFIED, Kind.UNRECOGNIZED -> {
                            logger.warn("Received unspecified/unrecognized command, ignored")
                        }
                    }
                }

                override fun onError(t: Throwable) {
                    logger.error("ExportHub stream error", t)
                    stopAllExports()
                }

                override fun onCompleted() {
                    logger.info("Parent closed ExportHub stream")
                }
            }
        }
    }

    /**
     * 处理 START_EXPORT — 启动独立协程调用 ExportHandler.executeAsSubprocess
     *
     * @param send 线程安全的发帧函数（见 [ExportHubImpl.stream] 里关于 onNext 线程安全的说明）
     */
    private fun handleStartExport(cmd: ExportCommand, send: (ExportHubResponse) -> Boolean) {
        val id = cmd.id
        if (id.isBlank()) {
            send(
                ExportHubResponse.newBuilder()
                    .setId("unknown")
                    .setSuccess(false)
                    .setError("Missing export id")
                    .setEnd(true)
                    .build()
            )
            return
        }
        if (activeExports.containsKey(id)) {
            send(
                ExportHubResponse.newBuilder()
                    .setId(id)
                    .setSuccess(false)
                    .setError("Export $id already running")
                    .setEnd(true)
                    .build()
            )
            return
        }

        val job = scope.launch {
            try {
                // 构造 typed Request 对象（ExportHandler.executeAsSubprocess 需要）。
                // 注：子进程 wire 上的 ExportCommand 仍使用 map<string,Value> payload；
                // 我们在内部把它填进 typed ExportRequest.run_export 中。
                val request = com.kxxnzstdsw.grpc.Request.newBuilder()
                    .setId(id)
                    .setCategory(com.kxxnzstdsw.grpc.Category.EXPORT)
                    .setAction(com.kxxnzstdsw.grpc.Action.RUN_EXPORT)
                    .setConnection(cmd.connection)
                    .setExportRequest(
                        com.kxxnzstdsw.grpc.ExportRequest.newBuilder().setRunExport(
                            buildExportRunFromPayload(cmd.payloadMap)
                        )
                    )
                    .build()

                // executeAsSubprocess 直接返回 Flow<ExportHubResponse>（subprocess wire shape），
                // 省去 typed Response ↔ Value 的来回转换
                ExportHandler.executeAsSubprocess(request).collect { hubResp ->
                    System.err.println("[export-sub] 回帧 id=${hubResp.id} end=${hubResp.end} success=${hubResp.success} 有数据=${hubResp.hasData()}")
                    send(hubResp)
                }
            } catch (e: Exception) {
                logger.error("Export failed: $id", e); System.err.println("[export-sub] 导出抛异常 id=$id: $e")
                send(
                    ExportHubResponse.newBuilder()
                        .setId(id)
                        .setSuccess(false)
                        .setError(e.message ?: "Export failed")
                        .setEnd(true)
                        .build()
                )
            } finally {
                activeExports.remove(id)
            }
        }
        activeExports[id] = job
    }

    private fun handleStopExport(exportId: String) {
        if (exportId.isBlank()) {
            stopAllExports()
            return
        }
        val job = activeExports.remove(exportId)
        if (job != null) {
            logger.info("Cancelling export: $exportId")
            ExportEngine.isCancelled = true
        }
    }

    private fun stopAllExports() {
        if (activeExports.isNotEmpty()) {
            logger.info("Stopping all exports...")
            ExportEngine.isCancelled = true
            activeExports.clear()
        }
    }

    private fun findBaseDir(): File {
        val currentDir = File(".").absoluteFile
        val libsDir = File(currentDir, "libs")
        if (libsDir.exists()) return currentDir
        val parent = currentDir.parentFile
        if (parent != null && File(parent, "libs").exists()) return parent
        return currentDir
    }

    /**
     * 从子进程 wire 上的 map<string,Value> payload 构造 typed ExportRunRequest。
     * Value → string 通过 com.google.protobuf.Value 的 stringValue / numberValue 字段读取。
     */
    private fun buildExportRunFromPayload(
        payload: Map<String, com.google.protobuf.Value>
    ): com.kxxnzstdsw.grpc.ExportRunRequest {
        val b = com.kxxnzstdsw.grpc.ExportRunRequest.newBuilder()
        payload["sql"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.sql = it.stringValue }
        payload["outputDir"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.outputDir = it.stringValue }
        payload["fileName"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.fileName = it.stringValue }
        payload["format"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.format = it.stringValue }
        payload["tableName"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.tableName = it.stringValue }
        payload["fetchSize"]?.let {
            when (it.kindCase) {
                com.google.protobuf.Value.KindCase.NUMBER_VALUE -> b.fetchSize = it.numberValue.toInt()
                com.google.protobuf.Value.KindCase.STRING_VALUE -> b.fetchSize = it.stringValue.toIntOrNull() ?: 0
                else -> {}
            }
        }
        payload["stopExportId"]?.takeIf { it.kindCase == com.google.protobuf.Value.KindCase.STRING_VALUE }
            ?.let { b.stopExportId = it.stringValue }
        return b.build()
    }
}
