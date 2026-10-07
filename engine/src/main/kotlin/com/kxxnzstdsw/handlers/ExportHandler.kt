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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
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
     * 导出链路的日志。
     *
     * 以前这条链路是**完全静默**的：失败既不回帧、也不打日志到用户能看见的地方，
     * 于是「点了导出没反应」既没法从界面判断、也没法从日志判断
     * （TEST_CASES.md §9.13）。现在每个环节都留痕：
     * 子进程侧记「开始 / 引擎返回失败 / 抛异常」，父进程侧记「命令已下发 / 没等到完成帧」。
     */
    private val logger = LoggerFactory.getLogger(ExportHandler::class.java)

    /**
     * 同时写 SLF4J **和** stderr。
     *
     * 为什么要两份：桌面应用里 SLF4J 可能根本没有绑定（`engine` 用
     * `implementation(libs.logback.classic)`，而消费方未必把它带到运行期），
     * 于是 `logger.info(...)` 全部进黑洞 —— 导出出问题时的唯一现象就是「界面没反应」。
     *
     * 导出子进程的 stderr 已经被重定向到 `engine/build/libs/export-subprocess.log`，
     * 所以这里的 stderr 在**父子两侧**都会落盘，排障时看那一个文件就够。
     */
    private fun log(message: String) {
        logger.info(message)
        System.err.println("[export] $message")
    }

    private fun logWarn(message: String) {
        logger.warn(message)
        System.err.println("[export][warn] $message")
    }

    private fun logError(message: String, t: Throwable) {
        logger.error(message, t)
        System.err.println("[export][error] $message: $t")
        t.printStackTrace()
    }

    /**
     * 主进程模式入口 — 由 [com.kxxnzstdsw.dispatcher.RequestDispatcher] 直接调用。
     * 编排子进程 + 收集子进程响应并组装为 typed Response 流。
     */
    fun executeInMainProcess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.exportRequest.runExport

        /**
         * 找不到 jar **不再是致命错误** —— 交给 [ExportProcessManager] 退回「本进程 classpath」。
         *
         * 原来这里直接返回 `Cannot find idb-engine.jar path`，而 Gradle 跑测试 / `:run` 时
         * classpath 上根本没有 jar（只有 `engine/build/classes/...`）。
         * 于是**开发与 CI 环境里这条链路压根不可用**，能验它的只有打包产物 ——
         * 这就是它坏掉这么久却没人发现的原因。
         */
        val jarPath = findEngineJarPath()
        if (jarPath == null) {
            logWarn("classpath 上没有 idb-engine.jar，改用本进程 classpath 启动导出子进程")
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
        // ⚠️ **SharedFlow 永远不会自己结束**，所以这里必须显式收口。
        //
        // 少了这一步：子进程把活干完了、文件也写了、完成帧也回传了，
        // 可父进程的 `collect` 还在等「下一帧」—— 桌面那边永远收不到结论，
        // 直到看门狗（5 分钟）超时。实测就是「文件已经导出成功，界面却一直转」。
        //
        // ⚠️ 用 `transformWhile` 而不是「collect 里 `return@collect`」：
        // 后者只是**跳过本次回调**，下一帧照样进循环，等于没收口。
        // `transformWhile` 在**同一个挂起循环**里既吐出完成帧又停下，
        // 期间不会「取一帧再取一帧」那种窗口丢帧的竞态。
        //
        // 注意 `emit` 在这里是 `transformWhile` 的**内部**发射（转到下游），
        // 真正吐给上游调用方的那个 `emit` 在下面的 `onEach` 里 —— 那个位置
        // 与本 flow 的收集协程同上下文，才是合法的。
        // `completed` 在下面 onEach 里被置位；这里先声明
        var completed = false
        responses
            .transformWhile { frame ->
                emit(frame)
                !(frame.export.hasProgress() && frame.export.progress.completed)
            }
            .onEach { frame ->
                if (frame.export.hasProgress() && frame.export.progress.completed) completed = true
                log("主进程转发帧 completed=${frame.export.hasProgress() && frame.export.progress.completed}")
                emit(frame)
            }
            .collect { }
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
     *
     * ## ⚠️ 为什么是 `callbackFlow` + `Channel`，而不是 `flow { }`
     *
     * 原实现是在 `flow { }` 里跑 `ExportEngine.export(…) { progress -> emit(…) }`。
     * `emit` 必须在**收集方所在的协程**里调用，而 [ExportEngine.export] 内部有自己的
     * `withContext(Dispatchers.IO)` —— 于是**每一帧**都抛：
     *
     * ```
     * Flow invariant is violated:
     *   Flow was collected in [… BlockingCoroutine …],
     *   but emission happened in [… UndispatchedCoroutine …, Dispatchers.IO].
     * Please refer to 'flow' documentation or use 'flowOn' instead
     * ```
     *
     * 抛在第一帧上、又被 `catch` 接住再 `emit` 一次（那同样不合法），结果整个 flow
     * **一帧都没出去**就死了。子进程看到的是「导出没回执」，父进程就是无限等待，
     * 界面上「点了导出，什么也没发生」，磁盘上也没有文件 —— 正是 TEST_CASES.md §9.13 那个现象。
     *
     * 只加 `flowOn` **不够**（试过，`ExportEngine.export` 的 `withContext` 照样另起协程）。
     * 干净的做法是让两边彻底解耦：
     *
     * - 工作协程只往 [Channel] `trySend`（Channel 没有「收集方协程」这种约束）
     * - `send`/`emit` 全部发生在**本 flow 自己的协程**里
     *
     * 顺带修掉的另一处：引擎失败时**只返回** `ExportResult(success=false)` 而不抛，
     * 那条路径上 `onProgress` 一次都不会被调 —— 必须在返回后补一帧终止帧。
     */
    fun executeAsSubprocess(request: Request): Flow<ExportHubResponse> = callbackFlow {
        val id = request.id
        val config = request.connection
        val frames = Channel<ExportHubResponse>(Channel.UNLIMITED)

        // 真正干活的协程：它只往 [frames] 里 `trySend`，**不碰 emit**。
        // 见本函数 KDoc —— 从别人的协程里 emit 会撞上 Flow 不变量。
        val worker = launch(Dispatchers.IO) {
            fun failure(message: String) = exportHubResponse {
                this.id = id
                success = false
                error = message
                end = true
            }

            try {
                val exportRequest = parseExportRequest(request.exportRequest.runExport)
                log("导出开始: id=$id driver=${config.driver} sql=${exportRequest.sql}")
                val result = ExportEngine.export(config, exportRequest) { progress ->
                    val data = buildJsonObject {
                        put("exportedRows", progress.exportedRows)
                        put("columnCount", progress.columnCount)
                        put("completed", progress.completed)
                        if (progress.filePath != null) put("filePath", progress.filePath)
                        if (progress.error != null) put("error", progress.error)
                    }
                    frames.trySend(
                        exportHubResponse {
                            this.id = id
                            success = true
                            stream = true
                            end = progress.completed
                            this.data = PayloadAdapter.toValue(data)
                        }
                    )
                }
                // ⚠️ **ExportEngine 失败时不抛异常，只返回 `ExportResult(success=false)`**
                // （它内部 catch 后构造失败结果）。于是失败路径上 `onProgress` 一次都不会被调，
                // 不在这里补一帧的话，整个流**一帧不发**就结束 ——
                // 父进程 `collectResponses(id)` 永远等不到东西，界面上「点了导出，什么也没发生」。
                if (!result.success) {
                    logWarn("导出失败（引擎已返回失败结果）: id=$id error=${result.error}")
                    frames.trySend(failure(result.error ?: "导出失败"))
                } else {
                    log("导出完成: id=$id rows=${result.exportedRows} file=${result.filePath}")
                }
            } catch (e: Throwable) {
                // ⚠️ 必须是 **Throwable**，不是 Exception。
                // 驱动装不上时抛的是 `ServiceConfigurationError` / `NoClassDefFoundError` ——
                // 那是 `Error`。只 catch Exception 的话这类失败直接逃逸，一帧终止帧都不发。
                logError("导出抛异常: id=$id", e)
                frames.trySend(failure(e.message ?: e::class.java.simpleName))
            } finally {
                frames.close()
            }
        }

        // emit 只在**本 flow 自己的协程**里做 —— 这是 Flow 的硬性要求
        for (frame in frames) { send(frame) }
        worker.join()
        close()
    }

    /**
     * 启动或复用导出子进程
     *
     * ## ⚠️ 判据是「通道能不能用」，不是「进程在不在」
     *
     * 原来写的是 `if (!ExportProcessManager.isRunning)`。但 hub 流断开时
     * [com.kxxnzstdsw.export.ExportProcessManager] 的 `onError` 只清 observer、
     * **不动** `isRunning`（子进程确实还活着）—— 于是这个判据为真，
     * 整个「建流」分支被跳过，`startExport` 拿到 null observer 返回 false，
     * 用户看到「导出子进程通道未就绪」。
     *
     * 更糟的是它**永远不会自愈**：判据恒真，重建流的代码永远不执行 ——
     * 实测形态是「第一次导出成功，之后全部失败，重启应用才恢复」。
     *
     * 所以这里用 [com.kxxnzstdsw.export.ExportProcessManager.hasUsableChannel]：
     * 通道不在就重建（进程还在就只重连，进程没了才重启）。
     */
    private suspend fun ensureSubprocessRunning(jarPath: String?) {
        if (ExportProcessManager.hasUsableChannel) return
        // 进程可能还在（只是流断了），这时 `start` 里的 CAS 会挡住重复拉起，
        // 后面的 awaitHubReady 会直接重建流 —— 两条路都能收敛到「通道可用」。
        if (!ExportProcessManager.isRunning) {
            ExportProcessManager.start(jarPath)
        }
        // ⚠️ 原来这里只 `delay(200ms)`，然后就 `observer.onNext(cmd)`。
        //
        // 子进程要 2~3 秒才起得来（JVM + 加载方言插件），200ms 之后它还没 bind，
        // gRPC 直接回 `UNAVAILABLE: io exception` —— 而那个错误**只被记了一行日志**，
        // `commandObserver` 置空。于是命令没发出去、也没人回帧，桌面那边永远等
        // （TEST_CASES.md §9.15）。改成**真的等端口能连上**再返回：
        // 这是唯一能保证「命令发出时对面在听」的办法。
        ExportProcessManager.awaitHubReadyOrReportFailure()
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
