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

    private val logger = LoggerFactory.getLogger(ExportProcessManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 默认子进程监听端口
    private const val DEFAULT_EXPORT_HUB_PORT = 50099

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

    // 每个 exportId 的响应 SharedFlow
    private val responseFlows = ConcurrentHashMap<String, MutableSharedFlow<Response>>()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    /**
     * 启动子进程并连接 gRPC channel
     *
     * @param jarPath idb-engine.jar 路径
     */
    fun start(jarPath: String): Int {
        // 原子抢占 — 防止两个 caller 同时启动两个子进程
        if (!_isRunning.compareAndSet(false, true)) {
            logger.warn("Export subprocess already running")
            return hubPort
        }

        hubPort = System.getenv("IDB_EXPORT_HUB_PORT")?.toIntOrNull() ?: DEFAULT_EXPORT_HUB_PORT

        // 1. 启动子进程
        val libsDir = File(jarPath).parentFile?.absoluteFile
        val classPath = buildSubprocessClassPath(jarPath, libsDir)

        try {
            val javaHome = System.getProperty("java.home")
            val javaExe: String = File(File(javaHome), "bin/java").takeIf { it.exists() }?.absolutePath
                ?: File(File(javaHome), "bin/java.exe").takeIf { it.exists() }?.absolutePath
                ?: "java"
            val parentMaxMem = Runtime.getRuntime().maxMemory()
            val childMaxMem = maxOf(parentMaxMem, 256L * 1024 * 1024)

            val builder = ProcessBuilder(
                javaExe,
                "-Xmx${formatMem(childMaxMem)}",
                "-Xms${formatMem(childMaxMem)}",
                "-XX:+UseSerialGC",
                "-Didb.subprocess=true",
                "-Didb.export.hub.port=$hubPort",
                "-cp", classPath,
                "com.kxxnzstdsw.export.ExportSubProcess"
            )
            builder.directory(libsDir ?: File("."))
            builder.redirectErrorStream(false)

            // Windows + Parquet: 设置 HADOOP_HOME 指向 libs/ 目录
            if (System.getProperty("os.name").lowercase().contains("win") && libsDir != null) {
                builder.environment()["HADOOP_HOME"] = libsDir.absolutePath
                builder.environment()["hadoop.home.dir"] = libsDir.absolutePath
            }

            process = builder.start()

            // 监控进程退出
            scope.launch {
                val code = process?.waitFor()
                logger.info("Export subprocess exited with code: $code")
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

            // 2. 连接 gRPC channel（短暂重试等待子进程 server 就绪）
            val ch = ManagedChannelBuilder.forAddress("localhost", hubPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build()
            channel = ch

            val stub = ExportHubGrpc.newStub(ch)
            val responseObserver = object : StreamObserver<ExportHubResponse> {
                override fun onNext(value: ExportHubResponse) {
                    publishResponse(value)
                }

                override fun onError(t: Throwable) {
                    logger.error("ExportHub bidi stream error", t)
                    commandObserver = null
                }

                override fun onCompleted() {
                    logger.info("Subprocess closed ExportHub stream")
                    commandObserver = null
                }
            }
            commandObserver = stub.stream(responseObserver)

            logger.info("Export subprocess started (ExportHub on :$hubPort, connected)")
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
        scope.launch { flow.emit(response) }
        if (exportResp.end) {
            scope.launch {
                delay(50)
                responseFlows.remove(exportResp.id)
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

    /**
 * 拼导出子进程的 classpath —— 引擎 jar **加上**它旁边的每一个 jar 目录。
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
 * 原来只拼 `libs/`，于是导出子进程**装不上 JDBC 驱动**，
 * 连库时抛的是 `ServiceConfigurationError` / `NoClassDefFoundError` ——
 * 那是 `Error` 不是 `Exception`，于是**一帧终止帧都没发**，
 * 主进程永远等着，用户看到的是「点了导出，什么也没发生」。
 *
 * 这里按目录枚举而不是写死某一个：以后再加一个 jar 目录也不会漏。
 */
    private fun buildSubprocessClassPath(jarPath: String, libsDir: File?): String {
        if (libsDir == null || !libsDir.isDirectory) return jarPath
        val jars = libsDir.listFiles { f: File -> f.isDirectory }
            ?.flatMap { dir -> dir.listFiles { f: File -> f.extension == "jar" }?.toList().orEmpty() }
            .orEmpty()
        if (jars.isEmpty()) return jarPath
        return (listOf(jarPath) + jars.map { it.absolutePath }).joinToString(File.pathSeparator)
    }

    /**
     * 给所有**还没收口**的导出补一帧失败。
     *
     * 没有这一步，「子进程没了」这件事只活在日志里；调用方那边是
     * 一个永远不结束的 `collect` —— 用户看到的是彻底没有反馈。
     *
     * 已经收到过 `completed` 的导出不在 [responseFlows] 里（`publishResponse`
     * 收到终止帧后会移除），所以这里天然只处理「还在等」的那些。
     */
    private fun failPendingExports(reason: String) {
        val pending = responseFlows.keys.toList()
        if (pending.isEmpty()) return
        logger.warn("Export subprocess gone, failing ${pending.size} pending export(s): $pending")
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
            logger.error("Cannot start export: stream not open")
            return false
        }
        return try {
            val cmd = ExportCommand.newBuilder()
                .setKind(Kind.START_EXPORT)
                .setId(id)
                .setConnection(connection)
                .putAllPayload(payload)
                .build()
            observer.onNext(cmd)
            logger.info("Sent START_EXPORT command: $id")
            true
        } catch (e: Exception) {
            logger.error("Failed to send START_EXPORT command", e)
            false
        }
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
        try {
            val cmd = ExportCommand.newBuilder()
                .setKind(Kind.STOP_EXPORT)
                .setExportId(exportId)
                .build()
            observer.onNext(cmd)
            logger.info("Sent STOP_EXPORT command: $exportId")
        } catch (e: Exception) {
            logger.error("Failed to send STOP_EXPORT command", e)
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
                try {
                    obs.onNext(ExportCommand.newBuilder().setKind(Kind.SHUTDOWN).build())
                    obs.onCompleted()
                } catch (_: Exception) { /* ignore */ }
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