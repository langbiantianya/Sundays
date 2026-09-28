package com.kxxnzstdsw.client.grpc

import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollSocketChannel
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** 引擎端点传输种类 —— 与服务端 `IdbEngineServer --ipc` 的三种取值一一对应。 */
enum class GrpcTransportKind {
    /** TCP loopback（默认，:50051）。 */
    TCP,

    /** Unix Domain Socket（Linux / macOS / BSD）。 */
    UNIX,

    /** Windows 命名管道（`pipe:<name>`）。客户端可连，服务端需自行提供该端点。 */
    PIPE,
}

/**
 * 引擎端点配置 —— 描述「gRPC 引擎进程在哪里」，并据此创建 [ManagedChannelBuilder]。
 *
 * 与服务端的 `IpcConfig`（位于 `:engine`）刻意**不共享类型**：客户端模块不依赖引擎实现，
 * 少一层耦合即少一处循环依赖风险。两者按同一套取值约定（`tcp` / `unix` / `pipe`）互通。
 *
 * [fromTarget] 接受的写法：
 * ```
 * localhost:50051           → TCP
 * tcp://127.0.0.1:50051     → TCP
 * unix:///tmp/idb.sock      → UNIX
 * pipe:idb-engine           → PIPE
 * ```
 */
data class GrpcClientConfig(
    val kind: GrpcTransportKind = GrpcTransportKind.TCP,
    val host: String = "localhost",
    val port: Int = 50051,
    val udsPath: String = "/tmp/idb-engine.sock",
    val pipeName: String = "idb-engine",
) {
    private val logger = LoggerFactory.getLogger(GrpcClientConfig::class.java)

    /**
     * 本配置创建过的客户端 EventLoopGroup（仅 TCP / UNIX 分支）。
     *
     * Netty 不会替调用方关闭外部传入的 group，因此由 [shutdownGroup] 在 channel 关闭后回收。
     * 不参与 data class 的 equals —— 它是运行时资源，不是配置本身。
     */
    internal var clientEventLoopGroup: EventLoopGroup? = null
        private set

    /** 日志与错误信息中显示的端点。 */
    fun target(): String = when (kind) {
        GrpcTransportKind.TCP -> "$host:$port"
        GrpcTransportKind.UNIX -> udsPath
        GrpcTransportKind.PIPE -> pipeName
    }

    /**
     * 创建 channel builder（尚未 `build()`）。同一实例只应调用一次。
     *
     * Linux 上优先 epoll（native 加速），否则回退 NIO —— 与服务端
     * `UnixSocketIpcTransport` 的降级策略保持一致。
     */
    fun channelBuilder(): ManagedChannelBuilder<*> = when (kind) {
        GrpcTransportKind.TCP -> {
            val group = newEventLoopGroup("tcp")
            clientEventLoopGroup = group
            NettyChannelBuilder.forAddress(host, port)
                .channelType(
                    if (Epoll.isAvailable()) EpollSocketChannel::class.java else NioSocketChannel::class.java
                )
                .eventLoopGroup(group)
                .usePlaintext()
        }

        GrpcTransportKind.UNIX -> {
            val group = newEventLoopGroup("uds")
            clientEventLoopGroup = group
            NettyChannelBuilder.forAddress(DomainSocketAddress(udsPath))
                .channelType(
                    if (Epoll.isAvailable()) EpollDomainSocketChannel::class.java else NioDomainSocketChannel::class.java
                )
                .eventLoopGroup(group)
                .usePlaintext()
        }

        // gRPC-Java 通过 URI scheme 原生识别 `pipe:<name>`，无需 native 依赖
        GrpcTransportKind.PIPE -> Grpc.newChannelBuilder("pipe:$pipeName", InsecureChannelCredentials.create())
    }

    private fun newEventLoopGroup(transport: String): EventLoopGroup =
        if (Epoll.isAvailable()) {
            EpollEventLoopGroup().also { logger.debug("gRPC client {} transport: epoll (native)", transport) }
        } else {
            NioEventLoopGroup().also { logger.debug("gRPC client {} transport: nio", transport) }
        }

    /** 关闭 [channelBuilder] 创建的 EventLoopGroup（幂等，异常仅告警）。 */
    internal fun shutdownGroup(group: EventLoopGroup?) {
        try {
            group?.shutdownGracefully(0, 2, TimeUnit.SECONDS)?.sync()
        } catch (e: Exception) {
            logger.warn("gRPC client EventLoopGroup shutdown failed: ${e.message}")
        }
    }

    companion object {
        /** 默认端点 —— `localhost:50051`，与服务端 `IdbEngineServer` 的默认 TCP 端口一致。 */
        val DEFAULT = GrpcClientConfig()

        /**
         * 解析端点字符串。格式无法识别时抛 [IllegalArgumentException]，并在消息中列出支持的写法。
         */
        fun fromTarget(raw: String): GrpcClientConfig {
            val target = raw.trim()
            require(target.isNotEmpty()) { "Empty engine target" }
            return when {
                target.startsWith("pipe:") -> GrpcClientConfig(
                    kind = GrpcTransportKind.PIPE,
                    pipeName = target.removePrefix("pipe:"),
                )

                target.startsWith("unix:") -> parseUnix(target)

                target.startsWith("tcp://") -> parseTcp(target.removePrefix("tcp://"))

                // 裸 `host:port`（含 IPv6 字面量 `[::1]:50051`）
                target.contains(':') -> parseTcp(target)

                else -> throw IllegalArgumentException(
                    "Unsupported engine target: '$raw' " +
                        "(expected host:port, tcp://host:port, unix://<path> or pipe:<name>)"
                )
            }
        }

        /**
         * `unix://` URI 的 authority 段为空，路径从第三个 `/` 开始：
         * `unix:///var/run/x.sock` 去掉 `unix:` 后是 `///var/run/x.sock`，
         * 必须再去掉 `//` 这段 authority 分隔符，否则会拿 `///var/...` 去连 socket（连不上）。
         * 不带前导斜杠的相对名（`unix://engine.sock`）原样保留。
         */
        private fun parseUnix(raw: String): GrpcClientConfig {
            val rest = raw.removePrefix("unix:")
            val path = if (rest.startsWith("//")) rest.substring(2) else rest
            require(path.isNotEmpty()) { "Empty unix socket path in engine target: '$raw'" }
            return GrpcClientConfig(kind = GrpcTransportKind.UNIX, udsPath = path)
        }

        private fun parseTcp(authority: String): GrpcClientConfig {
            val (h, p) = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                require(end > 0) { "Malformed IPv6 authority: '$authority'" }
                authority.substring(1, end) to authority.substring(end + 1).removePrefix(":")
            } else {
                val idx = authority.lastIndexOf(':')
                require(idx > 0) { "Malformed authority: '$authority' (expected host:port)" }
                authority.substring(0, idx) to authority.substring(idx + 1)
            }
            val port = p.toIntOrNull() ?: throw IllegalArgumentException("Invalid port in engine target: '$p'")
            return GrpcClientConfig(kind = GrpcTransportKind.TCP, host = h, port = port)
        }
    }
}
