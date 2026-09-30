package com.kxxnzstdsw.ipc.impl

import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransport
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * TCP 传输 — 与旧 [com.kxxnzstdsw.server.IdbEngineServer] 行为一致，
 * 保留与 Wails Go 主进程的兼容路径（默认 :50051）。
 *
 * **绑定地址**：默认 [IpcConfig.LOOPBACK]（仅回环）。该端口**没有鉴权** —— 连上即可执行
 * 任意 SQL，因此不默认暴露到 `0.0.0.0`；确需跨机时用 `--host 0.0.0.0` 显式开启。
 */
class TcpIpcTransport(private val cfg: IpcConfig) : IpcTransport {
    private val logger = LoggerFactory.getLogger(TcpIpcTransport::class.java)

    override fun scheme(): String = "tcp"
    override fun displayTarget(): String = "${cfg.tcpHost}:${cfg.tcpPort}"

    override fun prepare() {
        logger.info("TCP transport: host={} port={}", cfg.tcpHost, cfg.tcpPort)
    }

    override fun serverBuilder(): ServerBuilder<*> {
        // 绑**指定地址**而非 forPort —— 后者等价于 0.0.0.0，会把无鉴权的引擎端口
        // 暴露给整个网络。默认 [IpcConfig.LOOPBACK]，需跨机访问时由用户显式 --host。
        // 优先 Netty（可指定绑定地址）；JDK ServerBuilder 没有 forAddress 变体，
        // 兜底路径只能 forPort —— 此时若配了非回环地址就告警，避免"以为绑了其实没绑"。
        return try {
            NettyServerBuilder.forAddress(InetSocketAddress(cfg.tcpHost, cfg.tcpPort))
        } catch (t: Throwable) {
            logger.warn("Failed to build Netty server, falling back to JDK ServerBuilder", t)
            if (cfg.tcpHost != IpcConfig.LOOPBACK) {
                logger.warn(
                    "JDK ServerBuilder cannot bind a specific address; falling back to port {} " +
                        "which listens on ALL interfaces ({}) instead of {}", cfg.tcpPort, cfg.tcpHost, cfg.tcpHost
                )
            }
            io.grpc.ServerBuilder.forPort(cfg.tcpPort)
        }
    }

    override fun channelBuilder(): ManagedChannelBuilder<*> =
        ManagedChannelBuilder.forAddress(cfg.tcpHost, cfg.tcpPort).usePlaintext()

    override fun cleanup() { /* no-op */ }
}