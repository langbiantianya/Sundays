package com.kxxnzstdsw.client.grpc

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.IdbEngineGrpcKt
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
import com.kxxnzstdsw.grpc.response
import com.kxxnzstdsw.grpc.systemRequest
import com.kxxnzstdsw.grpc.systemTestConnectionResponse
import io.grpc.ManagedChannel
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [EngineClient] 的 **gRPC 实现** —— 跨进程 / 跨语言调用引擎进程。
 *
 * 与同进程实现 `com.kxxnzstdsw.engine.IdbEngine` 的调用代码**完全一致**（同一个接口、同一份
 * proto 消息、同样的 `Flow<Response>`），区别仅在通道：这里多了一次 protobuf 序列化与
 * HTTP/2 帧传输，换来的是进程隔离与远程可达。
 *
 * ```kotlin
 * GrpcEngineClient(GrpcClientConfig.fromTarget("localhost:50051")).use { client ->
 *     val schemas = client.invoke(connection) {
 *         category = Category.SCHEMA
 *         action = Action.LIST
 *         body = RequestBody.SchemaRequest(schemaRequest = schemaRequest { list = schemaListRequest {} })
 *     }
 * }
 * ```
 *
 * ## 错误语义对齐
 *
 * 服务端业务异常已被 `RequestDispatcher` 包成 `success=false` 的 Response，但**传输层**故障
 * （引擎未启动、channel 已关闭、UDS 不存在）会以 gRPC `StatusException` 抛进 Flow。
 * 直接把它们抛给调用方会让「换一种传输就炸」—— 因此这里统一收口为
 * `success=false, error=<message>` 的终止帧，与同进程实现契约一致。
 */
class GrpcEngineClient private constructor(
    private val channel: ManagedChannel,
    private val config: GrpcClientConfig,
    private val ownsChannel: Boolean,
) : EngineClient {

    private val logger = LoggerFactory.getLogger(GrpcEngineClient::class.java)
    private val stub = IdbEngineGrpcKt.IdbEngineCoroutineStub(channel)
    private val closed = AtomicBoolean(false)

    /**
     * 主入口 —— 直接把 [Request] 交给 gRPC stub 的服务端流式 `Handle`。
     *
     * 每次 [handle] 对应一次新的 RPC 调用（stub 的 Flow 在 collect 时才真正建链），
     * 因此像同进程实现一样可以反复调用；取消 collect 会取消底层 RPC。
     */
    override fun handle(request: Request): Flow<Response> = flow {
        stub.handle(request).collect { emit(it) }
    }.catch { e ->
        // 传输层故障 → 与 RequestDispatcher 相同的错误帧约定
        if (e is StatusException || e is StatusRuntimeException) {
            logger.error("gRPC transport error for ${request.id} ${request.category}/${request.action}: ${e.message}")
        }
        emit(
            response {
                id = request.id
                success = false
                error = e.message ?: e.javaClass.simpleName
            }
        )
    }

    /**
     * 连接测试 —— 经 `SYSTEM.TEST_CONNECTION` 路由到引擎端的 `SystemHandler.testConnection`。
     *
     * 该路由在引擎端建（或复用）HikariCP 连接池并做 `isValid(5)` 校验，
     * 因此**远程连接初始化**也走同一条路径，语义与同进程实现一致。
     */
    override suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse {
        val resp = invoke(config) {
            category = Category.SYSTEM
            action = Action.TEST_CONNECTION
            systemRequest = systemRequest {}
        }
        // 传输层失败时 invoke 返回的是错误帧（无 system 负载）—— 转成 ok=false 的响应而非抛异常，
        // 与 SystemHandler.testConnection 自身的 catch 分支保持同一种失败形态。
        if (resp.hasSystem() && resp.system.hasTestConnection()) {
            return resp.system.testConnection
        }
        return systemTestConnectionResponse {
            ok = false
            error = resp.error.ifEmpty { "SYSTEM.TEST_CONNECTION failed: ${resp.system.bodyCase}" }
        }
    }

    /**
     * 断开连接 —— 经 `SYSTEM.DISCONNECT` 释放该配置在**引擎进程内**的连接池。
     *
     * 池活在引擎进程里，客户端本地没有任何池可关，所以这一调用必须走线 —— 这也是
     * `DISCONNECT` 被加入协议的原因（否则远程调用方无法释放连接池）。
     */
    override suspend fun disconnect(config: ConnectionConfig): Boolean {
        val resp = invoke(config) {
            category = Category.SYSTEM
            action = Action.DISCONNECT
            systemRequest = systemRequest {}
        }
        return resp.hasSystem() &&
            resp.system.hasDisconnect() &&
            resp.system.disconnect.closed
    }

    /**
     * 关闭 channel —— 幂等。先 `shutdown()` 优雅等待 [shutdownTimeoutMillis]，
     * 未完成则 `shutdownNow()` 强制中断。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (ownsChannel) {
            channel.shutdown()
            try {
                if (!channel.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    logger.warn("gRPC channel did not terminate within {}ms, forcing shutdown", shutdownTimeoutMillis)
                    channel.shutdownNow()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                channel.shutdownNow()
            }
        }
        config.shutdownGroup(config.clientEventLoopGroup)
    }

    companion object {
        private const val shutdownTimeoutMillis = 3000L

        /**
         * 连接指定端点上的引擎进程。
         *
         * @param config 端点配置（TCP / UDS / 命名管道）
         */
        fun connect(config: GrpcClientConfig = GrpcClientConfig.DEFAULT): GrpcEngineClient {
            val builder = config.channelBuilder()
            return GrpcEngineClient(
                channel = builder.build(),
                config = config,
                ownsChannel = true,
            )
        }

        /**
         * 包装**外部**已建好的 channel（例如复用共享连接、或在测试中注入 in-process channel）。
         * [close] 只做 channel 的优雅关闭，**不**回收外部 EventLoopGroup 的归属方。
         */
        fun wrap(channel: ManagedChannel): GrpcEngineClient =
            GrpcEngineClient(channel, GrpcClientConfig.DEFAULT, ownsChannel = true)
    }
}
