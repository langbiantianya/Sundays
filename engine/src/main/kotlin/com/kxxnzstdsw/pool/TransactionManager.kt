package com.kxxnzstdsw.pool

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.loader.DialectLoader
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个进行中的事务会话 —— 固定一条 JDBC 连接，`autocommit=false`。
 *
 * 事务的前提是**同一批写操作落在同一条连接上**。连接池每次 [PoolManager.getConnection] 都可能
 * 换一条物理连接，所以 BEGIN 时必须把连接「钉」在会话上，直到 COMMIT / ROLLBACK 才归还。
 */
class TransactionSession internal constructor(
    val id: String,
    val connection: Connection,
    val config: ConnectionConfig,
    val driverName: String,
    val startedAt: Long,
) {
    /** 最近一次设置过的 search_path；请求带的 schema 与之相同时跳过重复 SET。 */
    @Volatile
    var appliedSchema: String = ""

    @Volatile
    var closed: Boolean = false
        internal set
}

/**
 * 事务会话管理器（`SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`）。
 *
 * ## 语义
 *
 * - `session_id` 随 [com.kxxnzstdsw.grpc.Request] 下发；**留空 = 无事务**，行为与 v2.15 完全一致
 *   （每条语句独立提交）。这样旧调用方无需任何改动。
 * - BEGIN 为会话分配一条连接并置 `autocommit=false`；同 session 的后续 `DATA.*` 写操作与
 *   `SQL.EXECUTE` 的 DML 全部复用这条连接。
 * - COMMIT / ROLLBACK 结束会话：还原 `autocommit=true` 并把连接归还池中。会话不存在时
 *   返回 `false` 而非抛异常 —— 调用方可能因为网络中断而重复提交。
 * - 引擎关闭（[closeAll]）与断开连接（[PoolManager.close]）都会先回滚并释放会话，
 *   否则钉住的连接会一直占着池容量。
 *
 * **池容量**：`PoolManager` 每个配置的 `maximumPoolSize = 5`，因此同一配置最多 5 个并发事务会话。
 * 第 6 个 BEGIN 会在 5 秒后由 HikariCP 抛连接超时 —— 这是刻意的背压，不是 bug。
 */
object TransactionManager {
    private val logger = LoggerFactory.getLogger(TransactionManager::class.java)
    private val sessions = ConcurrentHashMap<String, TransactionSession>()

    /**
     * 开启事务会话：从池中借一条连接并关闭 autocommit。
     *
     * @param schema 初始 schema（非空时立即设置 search_path）
     */
    fun begin(config: ConnectionConfig, schema: String = ""): TransactionSession {
        val connection = PoolManager.getConnection(config, schema)
        connection.autoCommit = false
        val driver = PoolManager.resolveDialect(config).driverName
        val session = TransactionSession(
            id = UUID.randomUUID().toString(),
            connection = connection,
            config = config,
            driverName = driver,
            startedAt = System.currentTimeMillis(),
        )
        session.appliedSchema = schema
        sessions[session.id] = session
        logger.info("Transaction session started: id={} driver={} database={}", session.id, driver, config.database)
        return session
    }

    /**
     * 取会话的固定连接，并按需切换 search_path。
     *
     * @throws IllegalStateException 会话不存在或已结束（调用方应回落到无事务路径或直接报错）
     */
    fun connectionFor(sessionId: String, config: ConnectionConfig, schema: String): Connection {
        val session = sessions[sessionId]
            ?: throw IllegalStateException(
                "Unknown or already-closed transaction session: '$sessionId' " +
                    "(did the client COMMIT/ROLLBACK it, or was the engine restarted?)"
            )
        val effectiveSchema = schema.ifBlank { config.schema }
        if (effectiveSchema.isNotBlank() && effectiveSchema != session.appliedSchema) {
            DialectLoader.getDialect(config.driver).setSearchPath(session.connection, effectiveSchema)
            session.appliedSchema = effectiveSchema
        }
        return session.connection
    }

    /** 会话是否存在且未结束。 */
    fun exists(sessionId: String): Boolean = sessions.containsKey(sessionId)

    fun get(sessionId: String): TransactionSession? = sessions[sessionId]

    /** 全部活跃会话（诊断 / `SYSTEM.SESSION_INFO`）。 */
    fun active(): List<TransactionSession> = sessions.values.sortedBy { it.startedAt }

    /**
     * 提交并结束会话。
     *
     * @return true = 提交成功；false = 会话不存在（调用方重复提交时不应视为错误）
     */
    fun commit(sessionId: String): Boolean = finish(sessionId, rollbackFirst = false)

    /** 回滚并结束会话。语义与 [commit] 对称。 */
    fun rollback(sessionId: String): Boolean = finish(sessionId, rollbackFirst = true)

    private fun finish(sessionId: String, rollbackFirst: Boolean): Boolean {
        val session = sessions.remove(sessionId) ?: run {
            logger.debug("Transaction session already closed: {}", sessionId)
            return false
        }
        return try {
            if (rollbackFirst) {
                if (!session.connection.autoCommit) session.connection.rollback()
            } else {
                session.connection.commit()
            }
            logger.info("Transaction session {} {}", sessionId, if (rollbackFirst) "rolled back" else "committed")
            true
        } catch (e: Exception) {
            logger.warn("Transaction session {} failed to {}: {}", sessionId, if (rollbackFirst) "rollback" else "commit", e.message)
            false
        } finally {
            release(session)
        }
    }

    /**
     * 结束会话但不提交（引擎关闭 / 断开连接时调用）—— 先回滚未提交的工作，再归还连接。
     *
     * @return 被释放的会话数
     */
    fun closeSessionsFor(config: ConnectionConfig): Int {
        val key = PoolManager.configKeyOf(config)
        val victims = sessions.values.filter { PoolManager.configKeyOf(it.config) == key }
        victims.forEach { discard(it) }
        if (victims.isNotEmpty()) {
            logger.info("Rolled back {} transaction session(s) before closing pools for {}", victims.size, key)
        }
        return victims.size
    }

    /** 回滚并释放全部会话（`PoolManager.closeAll()` / 引擎关闭时调用）。 */
    fun closeAll() {
        val all = sessions.values.toList()
        all.forEach { discard(it) }
        if (all.isNotEmpty()) logger.info("Rolled back {} transaction session(s) on engine shutdown", all.size)
    }

    private fun discard(session: TransactionSession) {
        if (!sessions.remove(session.id, session)) return   // 已被并发 finish 掉
        try {
            if (!session.connection.autoCommit) session.connection.rollback()
        } catch (e: Exception) {
            logger.debug("Rollback during session cleanup failed: {}", e.message)
        } finally {
            release(session)
        }
    }

    /** 还原 autocommit 并把物理连接归还 HikariCP 池。 */
    private fun release(session: TransactionSession) {
        session.closed = true
        try {
            session.connection.autoCommit = true
        } catch (e: Exception) {
            logger.debug("Restoring autoCommit failed for session {}: {}", session.id, e.message)
        }
        try {
            session.connection.close()   // HikariCP 的 close() = 归还连接，不是物理关闭
        } catch (e: Exception) {
            logger.debug("Returning session connection to pool failed: {}", e.message)
        }
    }

    /** 当前活跃会话数（诊断 / 测试用）。 */
    fun activeCount(): Int = sessions.size
}
