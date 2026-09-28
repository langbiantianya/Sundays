package com.kxxnzstdsw.client

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.RequestKt
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.request
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * 引擎调用层契约（transport-agnostic invocation contract）。
 *
 * 调用方只面向本接口编程，**不感知引擎在同进程还是跨进程**：
 *
 * | 实现 | 模块 | 通道 | 场景 |
 * |---|---|---|---|
 * | `IdbEngine` | `:engine` | 同 JVM 直接方法调用 | Compose Desktop 同进程、shell 工具、嵌入式 |
 * | `GrpcEngineClient` | `:engine-grpc-client` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、远程引擎 |
 *
 * 两条实现共用同一份 [Request] / [Response] 消息与同一套 (Category, Action) 路由语义，
 * 因此**流式分帧（`stream` / `end`）、envelope options（`traceId` / `dryRun` / `timeoutMs`）、
 * 错误包装（`success=false, error=…`，不抛异常）完全一致**。
 *
 * ## 生命周期
 *
 * - [testConnection] 建立（或复用）该配置的连接池 —— 即「初始化连接」；[disconnect] 释放它。
 * - [close] 释放实现自身持有的资源（本地实现释放连接池 / 驱动 / 方言；gRPC 实现关闭 channel），
 *   幂等。窗口关闭 / 进程退出时调用。
 *
 * ## 线程安全
 *
 * 实现须允许多个协程并发调用。[close] 之后的行为由各实现定义：本地实现释放全局单例状态，
 * gRPC 实现关闭 channel 后请求将失败。
 */
interface EngineClient : AutoCloseable {

    /**
     * 单一主入口 —— 与 gRPC stub `IdbEngineCoroutineStub.handle(req)` 语义完全一致：
     *
     * - 非流式（SCHEMA / TABLE / DATA 写 / SQL.EXPLAIN / SYSTEM / FUNCTION 写 / VIEW / INDEX / FK / TRIGGER）：
     *   返回 1 条 Response（`stream=false`）
     * - 流式（DATA.LIST pageSize=0 / SQL.EXECUTE SELECT / DATA.GENERATE / EXPORT.RUN_EXPORT）：
     *   返回 N 条 Response，最后一条 `end=true`
     *
     * ```kotlin
     * val resp = engine.handle(request {
     *     id = UUID.randomUUID().toString()
     *     category = Category.SCHEMA
     *     action = Action.LIST
     *     connection = connectionConfig { driver = "H2"; database = "test" }
     *     body = RequestBody.SchemaRequest(schemaRequest = schemaRequest { list = schemaListRequest {} })
     * }).first()
     * ```
     */
    fun handle(request: Request): Flow<Response>

    /**
     * 单次（非流式）调用的便捷包装 —— 构造 Request 并 collect 终止帧。
     * 流式调用请使用 [handle] + `collect`。
     */
    suspend fun invoke(connection: ConnectionConfig, configure: RequestKt.Dsl.() -> Unit): Response {
        val req = request {
            id = UUID.randomUUID().toString()
            this.connection = connection
            configure()
        }
        // 非流式响应：collect 直到 end=true（单条响应此值立即为 true）
        var result: Response? = null
        handle(req).collect { resp ->
            if (resp.end || !resp.stream) {
                result = resp
            }
        }
        return result ?: error("No terminal response received")
    }

    /**
     * 测试 / 初始化连接 —— 建立（或复用）该配置的 HikariCP 连接池并做 JDBC `isValid(5)` 校验。
     *
     * 池按配置的 hash key 缓存，重复调用不会重复建池。
     */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse

    /** [testConnection] 的便捷重载 —— 仅凭 JDBC URL + 凭据（driver 由 URL scheme 反查）。 */
    suspend fun testConnection(
        jdbcUrl: String,
        user: String = "",
        password: String = "",
    ): SystemTestConnectionResponse = testConnection(
        connectionConfig {
            this.jdbcUrl = jdbcUrl
            this.user = user
            this.password = password
        }
    )

    /**
     * 断开连接 —— 释放 [config] 对应的全部连接池（含该配置下各 schema 维度的池），
     * 与 [testConnection] 对称。
     *
     * @return 是否真的关闭了连接池（false = 该配置当前没有活跃池）
     */
    suspend fun disconnect(config: ConnectionConfig): Boolean

    /**
     * 释放实现自身持有的资源，幂等。正常路径在窗口关闭 / 进程退出时调用一次。
     */
    override fun close()
}
