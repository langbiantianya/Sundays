package com.kxxnzstdsw.handlers

import com.google.protobuf.Value
import com.kxxnzstdsw.engine.SqlScriptSplitter
import com.kxxnzstdsw.engine.StatementRegistry
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Row
import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.SqlExecuteResponse
import com.kxxnzstdsw.grpc.SqlExplainRequest
import com.kxxnzstdsw.grpc.SqlExplainResponse
import com.kxxnzstdsw.grpc.SqlSelectRowFrame
import com.kxxnzstdsw.grpc.SqlStatementResult
import com.kxxnzstdsw.grpc.sqlStatementResult
import com.kxxnzstdsw.grpc.row
import com.kxxnzstdsw.grpc.sqlExecuteResponse
import com.kxxnzstdsw.grpc.sqlExplainResponse
import com.kxxnzstdsw.grpc.sqlSelectRowFrame
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.sql.ResultSet

object SqlEngineHandler {
    /**
     * 执行一条 SQL（或一段多语句脚本）。
     *
     * @param onRow 流式回调；SELECT 查询时逐行调用一次；非 SELECT 时不调用
     * @param sessionId 事务会话 id（v2.16）。非空时复用会话钉住的连接（autocommit=false），
     *   **不归还连接池** —— 连接的生死由 `SYSTEM.COMMIT` / `ROLLBACK` 决定。
     * @param requestId 本次请求的 id（v2.16）。非空时把正在执行的 Statement 登记到
     *   [StatementRegistry]，使 `SYSTEM.CANCEL` 能调用 `Statement.cancel()` 真正中止查询。
     *   多语句脚本按语句逐个登记，因此取消总是命中**当前正在跑的那一条**。
     * @return SELECT 走 [onRow] 路径；非 SELECT / 多语句返回 [SqlExecuteResponse]
     */
    suspend fun execute(
        config: ConnectionConfig,
        req: SqlExecuteRequest,
        sessionId: String = "",
        requestId: String = "",
        onRow: (suspend (SqlSelectRowFrame) -> Unit)? = null,
    ): SqlExecuteResponse = withContext(Dispatchers.IO) {
        if (req.sql.isBlank()) throw IllegalArgumentException("Missing 'sql' in payload")
        if (req.multiStatement) {
            return@withContext executeScript(config, req, onRow, sessionId, requestId)
        }
        val conn = PoolManager.getConnection(config, req.schema, sessionId)
        try {
            executeOne(conn, config, req.sql, onRow, requestId)
        } finally {
            releaseIfUnbound(conn, sessionId)
        }
    }

    /**
     * 多语句脚本 —— 按语句边界拆分后**顺序执行**。
     *
     * 遇错即停：失败的那条记录在 `statements` 里（`success=false` + error），
     * 其后的语句不再执行 —— 与 psql / DBeaver 的默认脚本行为一致。
     * `affected_rows` 为所有成功语句的行数合计，便于调用方一行拿到总数。
     */
    private suspend fun executeScript(
        config: ConnectionConfig,
        req: SqlExecuteRequest,
        onRow: (suspend (SqlSelectRowFrame) -> Unit)?,
        sessionId: String,
        requestId: String,
    ): SqlExecuteResponse = withContext(Dispatchers.IO) {
        val parts = SqlScriptSplitter.split(req.sql)
        if (parts.isEmpty()) {
            throw IllegalArgumentException("multi_statement script contains no executable statement")
        }
        val conn = PoolManager.getConnection(config, req.schema, sessionId)
        val results = mutableListOf<SqlStatementResult>()
        var totalAffected = 0
        try {
            for ((index, sql) in parts.withIndex()) {
                val result = try {
                    val resp = executeOne(conn, config, sql, onRow, "$requestId#$index")
                    sqlStatementResult {
                        this.index = index
                        this.sql = sql
                        affectedRows = resp.affectedRows
                        success = true
                    }
                } catch (e: Exception) {
                    // 取消导致的异常与普通 SQL 错误同形 —— 记录后停止执行后续语句
                    sqlStatementResult {
                        this.index = index
                        this.sql = sql
                        this.success = false
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
                results += result
                if (result.success) {
                    totalAffected += result.affectedRows
                } else {
                    break   // 遇错即停
                }
            }
        } finally {
            releaseIfUnbound(conn, sessionId)
        }
        sqlExecuteResponse {
            affectedRows = totalAffected
            statements.addAll(results)
        }
    }

    /** 在给定连接上执行单条 SQL。Statement 全程登记到 [StatementRegistry] 以支持取消。 */
    private suspend fun executeOne(
        conn: java.sql.Connection,
        config: ConnectionConfig,
        sql: String,
        onRow: (suspend (SqlSelectRowFrame) -> Unit)?,
        requestId: String,
    ): SqlExecuteResponse {
        val dialect = DialectLoader.getDialect(config.driver)
        return conn.createStatement(
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY
        ).use { stmt ->
            StatementRegistry.register(requestId, stmt)
            try {
                val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                try {
                    val hasResultSet = stmt.execute(sql)
                    if (hasResultSet) {
                        if (onRow != null) {
                            // 游标流式模式
                            stmt.fetchSize = 100
                            stmt.resultSet.use { rs ->
                                var pageIdx = 0
                                while (rs.next()) {
                                    onRow(
                                        sqlSelectRowFrame {
                                            this.total = -1L
                                            this.page = pageIdx
                                            this.pageSize = 1
                                            row = buildRow(rs)
                                        }
                                    )
                                    pageIdx++
                                }
                            }
                            sqlExecuteResponse { }
                        } else {
                            // 非流式模式：此路径 dispatcher 不会调用（dispatcher 总走流式），保留占位
                            sqlExecuteResponse { }
                        }
                    } else {
                        sqlExecuteResponse { this.affectedRows = stmt.updateCount }
                    }
                } finally {
                    dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
                }
            } finally {
                StatementRegistry.unregister(requestId)
            }
        }
    }

    /**
     * 归还连接 —— **仅限无事务会话时**。
     *
     * 会话连接由 [TransactionManager] 持有，在 COMMIT / ROLLBACK 之前 close() 会把它
     * 还回池中，后续语句将落到另一条物理连接上，事务语义直接失效。
     */
    private fun releaseIfUnbound(conn: java.sql.Connection, sessionId: String) {
        if (sessionId.isBlank()) conn.close()
    }

    /**
     * Build a typed Row proto from a JDBC ResultSet.
     */
    private fun buildRow(rs: ResultSet): Row {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount
        return row {
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val columnType = metaData.getColumnTypeName(i)
                val value: Value = if (columnType in listOf("BLOB", "LONGTEXT", "BYTEA", "TEXT")) {
                    PayloadAdapter.toValue(JsonPrimitive("[LOB Data]"))
                } else {
                    PayloadAdapter.toValue(JsonPrimitive(rs.getString(i)))
                }
                values.put(columnName, value)
            }
        }
    }

    /**
     * EXPLAIN — 返回 SQL 的执行计划
     */
    suspend fun explain(config: ConnectionConfig, req: SqlExplainRequest): SqlExplainResponse = withContext(Dispatchers.IO) {
        if (req.sql.isBlank()) throw IllegalArgumentException("Missing 'sql' in payload")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val rows = dialect.explainSQL(conn, req.sql)
            sqlExplainResponse {
                rows.forEach { row ->
                    val r = row {
                        row.forEach { (k, v) ->
                            values.put(k, PayloadAdapter.toValue(JsonPrimitive(v)))
                        }
                    }
                    this.rows += r
                }
            }
        }
    }
}