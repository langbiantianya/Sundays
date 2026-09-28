package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DialectInfo
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.SystemDisconnectResponse
import com.kxxnzstdsw.grpc.SystemInfoResponse
import com.kxxnzstdsw.grpc.SystemListDriversResponse
import com.kxxnzstdsw.grpc.SystemServerInfoResponse
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
import com.kxxnzstdsw.grpc.dialectInfo
import com.kxxnzstdsw.grpc.memoryInfo
import com.kxxnzstdsw.grpc.systemDisconnectResponse
import com.kxxnzstdsw.grpc.systemInfoResponse
import com.kxxnzstdsw.grpc.systemListDriversResponse
import com.kxxnzstdsw.grpc.systemServerInfoResponse
import com.kxxnzstdsw.grpc.systemTestConnectionResponse
import com.kxxnzstdsw.engine.StatementRegistry
import com.kxxnzstdsw.grpc.SystemBeginResponse
import com.kxxnzstdsw.grpc.SystemCancelResponse
import com.kxxnzstdsw.grpc.SystemCommitResponse
import com.kxxnzstdsw.grpc.SystemRollbackResponse
import com.kxxnzstdsw.grpc.SystemSessionInfoResponse
import com.kxxnzstdsw.grpc.systemBeginResponse
import com.kxxnzstdsw.grpc.systemCancelResponse
import com.kxxnzstdsw.grpc.systemCommitResponse
import com.kxxnzstdsw.grpc.systemRollbackResponse
import com.kxxnzstdsw.grpc.systemSessionInfoResponse
import com.kxxnzstdsw.grpc.transactionSession
import com.kxxnzstdsw.pool.TransactionManager
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory

object SystemHandler {

    fun info(): SystemInfoResponse {
        val runtime = Runtime.getRuntime()
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val runtimeBean = ManagementFactory.getRuntimeMXBean()

        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        return systemInfoResponse {
            jvmVersion = System.getProperty("java.version")
            jvmVendor = System.getProperty("java.vendor")
            jvmName = System.getProperty("java.vm.name")
            osName = osBean.name
            osArch = osBean.arch
            osVersion = osBean.version
            availableProcessors = osBean.availableProcessors
            memory = memoryInfo {
                max = maxMemory
                total = totalMemory
                used = usedMemory
                free = freeMemory
            }
            uptime = runtimeBean.uptime
            pid = runtimeBean.pid
        }
    }

    /**
     * TEST_CONNECTION — 测试数据库连接是否有效
     */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse = withContext(Dispatchers.IO) {
        try {
            // 仅凭 JDBC URL 也能测试 —— 方言由 URL scheme 反查（PoolManager.resolveDialect）。
            // 解析失败会抛出可读错误，被下面的 catch 转成 ok=false。
            val driverName = PoolManager.resolveDialect(config).driverName
            val connection = PoolManager.getConnection(config)
            connection.use { conn ->
                val ok = conn.isValid(5)
                systemTestConnectionResponse {
                    this.ok = ok
                    driver = driverName
                    host = config.host
                    port = config.port
                    database = config.database
                }
            }
        } catch (e: Exception) {
            systemTestConnectionResponse {
                ok = false
                error = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * DISCONNECT (v2.15) — 释放 [config] 对应的全部 HikariCP 连接池（含各 schema 维度）。
     *
     * 与 [testConnection] 对称：前者建池（= 初始化连接），本者释放池。池按
     * `PoolManager` 的两段式 key（`sha256(配置)#sha256(schema)`）按配置前缀定位，
     * 因此一次调用即可关掉该配置下所有 schema 维度的池。
     *
     * 远端（gRPC）调用方经 `SYSTEM.DISCONNECT` 路由落到这里，语义与本地直连完全一致。
     *
     * @return 是否真的关闭了连接池（false = 该配置当前没有活跃池）
     */
    suspend fun disconnect(config: ConnectionConfig): SystemDisconnectResponse =
        withContext(Dispatchers.IO) {
            systemDisconnectResponse { closed = PoolManager.close(config) }
        }

    /**
     * CANCEL (v2.16) — 中止正在执行的请求。
     *
     * 协程取消对阻塞 JDBC 调用无效，因此这里对运行中的 [java.sql.Statement] 直接调
     * `Statement.cancel()`，让数据库侧真正停下来。被取消的请求会由其自身的异常路径
     * 收口成 `success=false, error="cancelled"` 的终止帧（携带原请求 id）。
     *
     * @param requestId 目标请求 id（调用方自己分配的 `Request.id`）
     * @return cancelled=true 表示已找到目标并发出取消；false = 该请求不在运行 / 已结束
     */
    fun cancel(requestId: String): SystemCancelResponse = systemCancelResponse {
        if (requestId.isBlank()) {
            error = "SYSTEM.CANCEL requires 'target_request_id' (the id of the request to cancel)"
            return@systemCancelResponse
        }
        this.requestId = requestId
        cancelled = StatementRegistry.cancel(requestId)
        if (!cancelled) {
            error = "No running statement for request id '$requestId' — it may have already finished"
        }
    }

    /**
     * BEGIN (v2.16) — 开启事务会话。
     *
     * 为会话固定一条连接并置 `autocommit=false`；调用方把返回的 `session_id` 随后续
     * `DATA.*` / `SQL.EXECUTE` 请求下发，直到 COMMIT / ROLLBACK。
     */
    fun begin(config: ConnectionConfig, schema: String = ""): SystemBeginResponse {
        val session = TransactionManager.begin(config, schema)
        return systemBeginResponse {
            sessionId = session.id
            startedAt = session.startedAt
            driver = session.driverName
            database = config.database
        }
    }

    /** COMMIT (v2.16) — 提交并结束事务会话。重复提交返回 committed=false 而非报错。 */
    fun commit(sessionId: String): SystemCommitResponse = systemCommitResponse {
        this.sessionId = sessionId
        committed = TransactionManager.commit(sessionId)
        if (!committed) error = "Unknown or already-closed transaction session: '$sessionId'"
    }

    /** ROLLBACK (v2.16) — 回滚并结束事务会话。语义与 [commit] 对称。 */
    fun rollback(sessionId: String): SystemRollbackResponse = systemRollbackResponse {
        this.sessionId = sessionId
        rolledBack = TransactionManager.rollback(sessionId)
        if (!rolledBack) error = "Unknown or already-closed transaction session: '$sessionId'"
    }

    /**
     * SESSION_INFO (v2.16) — 事务会话状态。
     *
     * [sessionId] 非空时返回该会话（不存在则 `active_sessions=0`）；留空则列出全部活跃会话。
     */
    fun sessionInfo(sessionId: String = ""): SystemSessionInfoResponse {
        val target = if (sessionId.isBlank()) TransactionManager.active()
        else listOfNotNull(TransactionManager.get(sessionId))
        return systemSessionInfoResponse {
            activeSessions = target.size
            sessions.addAll(
                target.map { s ->
                    transactionSession {
                        this.sessionId = s.id
                        startedAt = s.startedAt
                        driver = s.driverName
                        database = s.config.database
                        schema = s.appliedSchema
                        autoCommit = s.connection.autoCommit
                    }
                }
            )
        }
    }

    /**
     * SERVER_INFO — 获取数据库服务端信息（版本、模式等）
     *
     * 方言返回的 Map 字段直接映射到 SystemServerInfoResponse 的已知字段（version/catalog/current_database/mode），
     * 未知字段打包进 extras: google.protobuf.Value。
     */
    suspend fun serverInfo(config: ConnectionConfig): SystemServerInfoResponse = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)
        connection.use { conn ->
            val info = dialect.getServerInfo(conn)
            val extras = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            val response = systemServerInfoResponse {
                info.forEach { (k, v) ->
                    when (k) {
                        "version" -> version = v
                        "catalog" -> catalog = v
                        "current_database" -> currentDatabase = v
                        "mode" -> mode = v
                        else -> extras[k] = JsonPrimitive(v)
                    }
                }
            }
            if (extras.isNotEmpty()) {
                response.toBuilder().setExtras(
                    PayloadAdapter.toValue(
                        kotlinx.serialization.json.buildJsonObject {
                            extras.forEach { (k, v) -> put(k, v) }
                        }
                    )
                ).build()
            } else {
                response
            }
        }
    }

    /**
     * LIST_DRIVERS (v2.8) — 列出引擎已加载的所有方言插件及连接元数据。
     *
     * 前端调用此接口后，可以根据返回的 DialectInfo.connectionType / requiresHost /
     * supportsUser 等字段动态决定连接表单的字段显隐与默认值。
     *
     * 不需要 connection（与 INFO 一样纯元数据查询），但 Request 仍可携带 connection 字段（被忽略）。
     */
    fun listDrivers(): SystemListDriversResponse {
        val items = DialectLoader.getAllDialects().map { dialect ->
            dialectInfo {
                driverName = dialect.driverName
                displayName = dialect.displayName
                jdbcDriverClassName = dialect.jdbcDriverClassName
                jdbcUrlExample = dialect.jdbcUrlExample
                connectionType = dialect.connectionType.name
                requiresHost = dialect.requiresHost
                requiresPort = dialect.requiresPort
                defaultPort = dialect.defaultPort ?: 0
                supportsUser = dialect.supportsUser
                supportsPassword = dialect.supportsPassword
                supportsSchema = dialect.supportsSchema
                supportsCrossDatabase = dialect.supportsCrossDatabase
                capabilities.addAll(dialect.capabilities.map { it.name })
            }
        }
        return systemListDriversResponse {
            this.items.addAll(items)
        }
    }
}