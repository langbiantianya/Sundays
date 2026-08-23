package com.kxxnzstdsw.server

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransport
import com.kxxnzstdsw.ipc.IpcTransportRegistry
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.pool.PoolManager
import io.grpc.Server
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * 引擎入口 (v2.9 双模式架构)
 *
 * **两种运行模式**（通过 `--mode` CLI 参数选择，默认 `grpc`）：
 *
 * | `--mode` | 子系统 | 适用场景 |
 * |---|---|---|
 * | `grpc`（默认） | 启动 gRPC 服务端，监听 IPC transport (TCP/UDS/pipe) | 跨进程、跨语言、远程调试 |
 * | `direct` | 仅加载 drivers/dialects，构造 [IdbEngine] 后阻塞主线程 | 与 KMP Desktop 在同 JVM；或 shell 测试 |
 *
 * `direct` 模式下**没有网络/管道监听**，KMP / desktopApp 通过 `implementation(project(":engine"))`
 * 把引擎作为库引入，直接调用 `IdbEngine().handle(request)` 即可。
 *
 * 通信层（仅 grpc 模式）：gRPC over HTTP/2 + protobuf；传输层由 [com.kxxnzstdsw.ipc.IpcTransport] SPI 抽象，
 * 支持 TCP（默认 :50051）/ UDS（Linux/macOS/BSD）/ Windows 命名管道（pipe:<name>）。
 *
 * 默认传输由 OS 自动检测（Windows=pipe, POSIX=unix），可通过 CLI 参数 `--ipc` 覆盖。
 * 完整参数列表：`java -jar idb-engine.jar --help`。
 */
object IdbEngineServer {
    private val logger = LoggerFactory.getLogger(IdbEngineServer::class.java)

    // 在 shutdown hook 中由 finally 块填入 — hook 需要它来优雅关闭 server
    private val activeServer = AtomicReference<Server?>()
    private val activeTransport = AtomicReference<IpcTransport?>()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // 先解析 --mode（与 IPC 无关，独立参数）
            val mode = parseMode(args)
            when (mode) {
                Mode.GRPC -> {
                    val cfg = IpcConfig.fromArgs(args)
                    runGrpc(cfg)
                }
                Mode.DIRECT -> {
                    runDirect()
                }
            }
        } catch (e: IllegalStateException) {
            System.err.println("idb-engine: ${e.message}")
            System.err.println("Try --help for usage.")
            exitProcess(2)
        }
    }

    /**
     * 从 CLI 参数解析 `--mode`（独立于 IPC，单独解析以便在 IpcConfig 之前决定入口）。
     * 未传则返回 `Mode.GRPC`（向后兼容）。
     */
    private fun parseMode(args: Array<String>): Mode {
        var i = 0
        while (i < args.size) {
            if (args[i] == "--mode") {
                val v = args.getOrNull(i + 1)
                    ?: throw IllegalStateException("--mode requires a value (grpc|direct)")
                return when (v.trim().lowercase()) {
                    "grpc" -> Mode.GRPC
                    "direct" -> Mode.DIRECT
                    else -> throw IllegalStateException(
                        "Invalid --mode value '$v' (expected: grpc | direct)"
                    )
                }
            }
            i++
        }
        return Mode.GRPC
    }

    /** 运行模式枚举。 */
    private enum class Mode { GRPC, DIRECT }

    /**
     * 启动 gRPC 服务并阻塞至收到终止信号。
     *
     * @param cfg 已解析的 IPC 传输配置
     */
    fun runGrpc(cfg: IpcConfig) {
        val transport = IpcTransportRegistry.resolve(cfg)
        transport.prepare()
        activeTransport.set(transport)
        logger.info("IDB Engine starting (gRPC mode), transport=${transport.describe()}")

        // 动态加载 drivers/ 目录下的 JDBC 驱动
        DriverLoader.loadFromDir(File("drivers"))

        // 动态加载 dialects/ 目录下的方言插件
        DialectLoader.loadFromDir(File("dialects"))

        // 单帧最大 256 MiB（与历史 MAX_FRAME_SIZE 对齐）
        val maxMsgBytes = 256L * 1024L * 1024L

        val server = transport.serverBuilder()
            .addService(IdbEngineImpl().bindService())
            .maxInboundMessageSize(maxMsgBytes.toInt())
            .build()
        activeServer.set(server)

        // 关闭钩子 — 必须先 server.shutdown() + awaitTermination,再关池/驱动/方言。
        // 否则 in-flight handler 持连接时 PoolManager.closeAll() 会抛 "pool closed" 异常。
        // finally 块也执行相同序列 — 这里是兜底(JVM 被信号杀掉时 finally 不跑)
        Runtime.getRuntime().addShutdownHook(Thread({
            logger.info("Shutdown hook triggered")
            try {
                val srv = activeServer.get()
                if (srv != null && !srv.isShutdown) {
                    srv.shutdown()
                    if (!srv.awaitTermination(30, TimeUnit.SECONDS)) {
                        srv.shutdownNow()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                logger.warn("Server shutdown in hook failed: ${e.message}")
            }
            try {
                activeTransport.get()?.cleanup()
            } catch (e: Exception) {
                logger.warn("Transport cleanup in hook failed: ${e.message}")
            }
            try { PoolManager.closeAll() } catch (e: Exception) { logger.warn("PoolManager.closeAll failed: ${e.message}") }
            try { DriverLoader.closeAll() } catch (e: Exception) { logger.warn("DriverLoader.closeAll failed: ${e.message}") }
            try { DialectLoader.closeAll() } catch (e: Exception) { logger.warn("DialectLoader.closeAll failed: ${e.message}") }
        }, "idb-engine-shutdown"))

        try {
            server.start()
            logger.info("IDB Engine gRPC server started, ${transport.describe()}")
            server.awaitTermination()
        } catch (e: InterruptedException) {
            logger.info("Server interrupted, shutting down")
        } catch (e: Exception) {
            logger.error("Server failed", e)
        } finally {
            try {
                server.shutdown()
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            transport.cleanup()
            try { PoolManager.closeAll() } catch (e: Exception) { logger.warn("PoolManager.closeAll failed: ${e.message}") }
            try { DriverLoader.closeAll() } catch (e: Exception) { logger.warn("DriverLoader.closeAll failed: ${e.message}") }
            try { DialectLoader.closeAll() } catch (e: Exception) { logger.warn("DialectLoader.closeAll failed: ${e.message}") }
            logger.info("IDB Engine stopped")
            exitProcess(0)
        }
    }

    @Suppress("unused")
    private fun serverBuilderForCompatibility(port: Int): ServerBuilder<*> =
        ServerBuilder.forPort(port)

    /**
     * 直接模式入口：仅加载 drivers/dialects，构造 [IdbEngine] 后阻塞主线程，
     * 供 shell 测试 / 守护进程使用。KMP frontend 不走此入口 — 直接构造 [IdbEngine] 即可。
     *
     * 不启动 gRPC server、不监听 IPC transport。JVM ShutdownHook 触发时优雅关闭 pools。
     */
    fun runDirect(
        driversDir: File = File("drivers"),
        dialectsDir: File = File("dialects"),
    ) {
        logger.info("IDB Engine starting (direct mode), driversDir={}, dialectsDir={}",
            driversDir.absolutePath, dialectsDir.absolutePath)
        IdbEngine.runDirectMode(driversDir, dialectsDir)
    }
}