package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.export.ExportEngine
import com.kxxnzstdsw.export.ExportFormat
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.export.ExportRequest
import com.kxxnzstdsw.grpc.ExportHubResponse
import com.kxxnzstdsw.grpc.ExportResponse
import com.kxxnzstdsw.grpc.ExportRunRequest
import com.kxxnzstdsw.grpc.ExportStopResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.exportHubResponse
import com.kxxnzstdsw.grpc.exportResponse
import com.kxxnzstdsw.grpc.exportStopResponse
import com.kxxnzstdsw.grpc.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 数据导出 Handler（gRPC 统一入口）
 *
 * 双模式入口：
 *  - 主进程：`executeInMainProcess(request): Flow<Response>`（typed Response）
 *  - 子进程：`executeAsSubprocess(request): Flow<ExportHubResponse>`（subprocess wire shape）
 *
 * 两者共享 `parseExportRequest()`：把 typed `ExportRunRequest` → ExportRequest。
 */
object ExportHandler {

    /**
     * 主进程模式入口 — 由 [com.kxxnzstdsw.dispatcher.RequestDispatcher] 直接调用。
     * 编排子进程 + 收集子进程响应并组装为 typed Response 流。
     */
    fun executeInMainProcess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.exportRequest.runExport

        val jarPath = findEngineJarPath()
        if (jarPath == null) {
            emit(
                response {
                    this.id = id
                    success = false
                    error = "Cannot find idb-engine.jar path"
                }
            )
            return@flow
        }

        // 停止导出分支
        val stopExportId = runReq.stopExportId.ifBlank { null }
        if (stopExportId != null) {
            ensureSubprocessRunning(jarPath)
            ExportProcessManager.stopExport(stopExportId)
            emit(
                response {
                    this.id = id
                    success = true
                    export = exportResponse {
                        stop = exportStopResponse { stopped = stopExportId }
                    }
                }
            )
            return@flow
        }

        // 启动导出分支。
        //
        // ⚠️ **订阅必须早于下发命令**，且 `startExport` 的失败必须自己收口成终止帧。
        //
        // [ExportProcessManager.collectResponses] 拿到的是 `replay = 0` 的 SharedFlow：
        // **没有订阅者时 emit 出去的值直接丢**。原来「先 startExport 再 collect」，
        // 小表（几十行）的整轮导出可能在订阅建立之前就回完了 —— 帧全丢，
        // `collect` 永远等不到东西。
        //
        // 更糟的是另一条路径：`startExport` 在「通道没就绪」时只写日志就返回，
        // 于是既没有命令、也没有人回帧 —— 前端 `collect` 永久挂起，
        // 对话框关了、文件没有、界面一句话都没有（实测，见 TEST_CASES.md §9.13）。
        // 所以这里两处都要堵：先订阅，收不到就**一定**回一帧 `success=false`。
        ensureSubprocessRunning(jarPath)
        // 转发给子进程的 ExportCommand 仍然使用 map<string,Value> payload — 子进程 wire 协议保持旧形态
        val responses = ExportProcessManager.collectResponses(id)
        val started = ExportProcessManager.startExport(id, config, runReqToPayloadMap(runReq))
        if (!started) {
            emit(
                response {
                    this.id = id
                    success = false
                    error = "导出子进程通道未就绪（ExportHub 未连接），导出没有启动"
                }
            )
            return@flow
        }

        // 收集子进程响应（已由 ExportProcessManager 转为 typed Response）转发给上游 gRPC StreamObserver
        var completed = false
        responses.collect {
            if (it.export.hasProgress() && it.export.progress.completed) completed = true
            emit(it)
        }
        // 流结束了却从没收到 `completed` 帧 —— 与其让上游无限等待，
        // 不如把「没等到完成」这件事本身作为失败报出去。
        if (!completed) {
            emit(
                response {
                    this.id = id
                    success = false
                    error = "导出已结束但没有收到完成帧（子进程可能中途退出）"
                }
            )
        }
    }

    /**
     * 子进程模式入口 — 由 [com.kxxnzstdsw.export.ExportSubProcess] 直接调用。
     * 直接调用 ExportEngine.export 并 emit 子进程 wire 形态的 ExportHubResponse。
     * 不走 typed Response 通道，避免在子进程内部做 typed ↔ Value 的来回转换。
     */
    fun executeAsSubprocess(request: Request): Flow<ExportHubResponse> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.exportRequest.runExport
        try {
            val exportRequest = parseExportRequest(runReq)
            withContext(Dispatchers.IO) {
                ExportEngine.export(config, exportRequest) { progress ->
                    // 直接 emit — lambda 本就是 suspend,无需 runBlocking 桥接
                    // (旧实现 runBlocking { emit } 每帧分配一个 EventLoop)
                    val data = buildJsonObject {
                        put("exportedRows", progress.exportedRows)
                        put("columnCount", progress.columnCount)
                        put("completed", progress.completed)
                        if (progress.filePath != null) put("filePath", progress.filePath)
                        if (progress.error != null) put("error", progress.error)
                    }
                    emit(
                        exportHubResponse {
                            this.id = id
                            success = true
                            stream = true
                            end = progress.completed
                            this.data = PayloadAdapter.toValue(data)
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            // ⚠️ 必须是 **Throwable**，不是 Exception。
            //
            // 驱动装不上时抛的不是异常而是错误：`java.lang.ServiceConfigurationError`
            // / `NoClassDefFoundError` 都是 `Error`。原来只 catch Exception，
            // 于是这类失败**直接逃逸**，一帧终止帧都不发 ——
            // 主进程那边 `collectResponses(id).collect { }` 永远挂着，
            // 界面上「点了导出，什么也没发生」，磁盘上也没有文件。
            //
            // 实测（TEST_CASES.md §9.13）：子进程的 classpath 由
            // `ExportProcessManager.start` 从 `engine/build/libs/libs/*.jar` 拼出来，
            // 那里面**没有 MySQL 驱动**，于是每个真实库的导出都会走到这条路径。
            // 打包分发时驱动在不在那儿另说，但「无论哪一步炸了都必须给出结论」这条契约
            // 必须在代码里成立 —— 否则一次环境问题就变成一次静默。
            emit(
                exportHubResponse {
                    this.id = id
                    success = false
                    error = e.message ?: e::class.java.simpleName
                    end = true
                }
            )
        }
    }

    /**
     * 启动或复用导出子进程
     */
    private suspend fun ensureSubprocessRunning(jarPath: String) {
        if (!ExportProcessManager.isRunning) {
            ExportProcessManager.start(jarPath)
            // 等待子进程初始化 + 连接到主进程 ExportHub
            delay(200.milliseconds)
        }
    }

    /**
     * 解析 typed `ExportRunRequest` → ExportRequest
     */
    private fun parseExportRequest(runReq: ExportRunRequest): ExportRequest {
        val sql = runReq.sql.ifBlank { throw IllegalArgumentException("缺少参数 'sql'") }
        val outputDir = runReq.outputDir.ifBlank { throw IllegalArgumentException("缺少参数 'outputDir'") }
        val fileName = runReq.fileName.ifBlank { throw IllegalArgumentException("缺少参数 'fileName'") }
        val formatStr = runReq.format.ifBlank { throw IllegalArgumentException("缺少参数 'format'") }.uppercase()
        val format = try {
            ExportFormat.valueOf(formatStr)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "不支持的格式: $formatStr，支持: CSV, JSON_LINES, SQL_INSERT, EXCEL, PARQUET"
            )
        }
        val tableName = runReq.tableName.ifBlank { null }
        val fetchSize = if (runReq.fetchSize == 0) 1000 else runReq.fetchSize

        return ExportRequest(
            sql = sql,
            outputDir = outputDir,
            fileName = fileName,
            format = format,
            tableName = tableName,
            fetchSize = fetchSize
        )
    }

    /**
     * 把 typed `ExportRunRequest` 打成子进程 wire 的 `map<string, Value>` 形态。
     */
    private fun runReqToPayloadMap(runReq: ExportRunRequest): Map<String, com.google.protobuf.Value> {
        val obj = JsonObject(
            linkedMapOf(
                "sql" to JsonPrimitive(runReq.sql),
                "outputDir" to JsonPrimitive(runReq.outputDir),
                "fileName" to JsonPrimitive(runReq.fileName),
                "format" to JsonPrimitive(runReq.format),
                "tableName" to JsonPrimitive(runReq.tableName),
                "fetchSize" to JsonPrimitive(runReq.fetchSize)
            )
        )
        return PayloadAdapter.toPayloadMap(obj)
    }

    /**
     * 通过 java.class.path 找到 idb-engine.jar
     */
    private fun findEngineJarPath(): String? {
        val classPath = System.getProperty("java.class.path", "")
        val paths = classPath.split(File.pathSeparator)
        val cwd = File(".").absoluteFile
        for (raw in paths) {
            val candidate = if (File(raw).isAbsolute) File(raw) else File(cwd, raw)
            if (candidate.exists() && candidate.name.contains("idb-engine.jar")) {
                return candidate.absolutePath
            }
        }
        return File(".", "idb-engine.jar").takeIf { it.exists() }?.absolutePath
    }
}