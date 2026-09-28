package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.dialect.DatabaseDialect
import com.kxxnzstdsw.engine.StatementRegistry
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.ImportProgressFrame
import com.kxxnzstdsw.grpc.ImportResultResponse
import com.kxxnzstdsw.grpc.ImportRunRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.importProgressFrame
import com.kxxnzstdsw.grpc.importResponse
import com.kxxnzstdsw.grpc.importResultResponse
import com.kxxnzstdsw.importer.ImportSourceFactory
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Types
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 数据导入 Handler（v2.16）—— `IMPORT.RUN_IMPORT`。
 *
 * 与 `EXPORT.RUN_EXPORT` 构成对称的读 / 写方向：导出的 5 种格式里，[ImportSourceFactory]
 * 覆盖最常用的两种文本格式（CSV / JSON_LINES），它们是同类产品里导入用得最多的目标格式。
 *
 * ## 为什么在主进程而不是子进程
 *
 * 导出走独立子进程是为了隔离 POI / Parquet / Hadoop 的内存与类加载开销；导入只是
 * 「读文件 + 批量 prepared insert」，没有重依赖，放主进程反而让**取消**变得可用：批次语句
 * 登记到 [StatementRegistry]，`SYSTEM.CANCEL` 能立刻打断正在执行的 `executeBatch()`，
 * 而不必等一个子进程被 kill。
 *
 * ## 事务
 *
 * `request.sessionId` 非空时所有批次落在事务会话的固定连接上，整个导入可被
 * `SYSTEM.ROLLBACK` 撤销 —— 「导入 50 万行、发现列对错了、回滚」是真实用法。
 * 留空则每批独立提交（与 v2.15 的写路径一致）。
 */
object ImportHandler {
    private val logger = LoggerFactory.getLogger(ImportHandler::class.java)

    private const val DEFAULT_BATCH_SIZE = 500

    /** 每读入这么多行推一帧进度，避免大文件把下游 Channel 灌满。 */
    private const val PROGRESS_INTERVAL = 5_000L

    /**
     * 主进程入口 —— 由 [com.kxxnzstdsw.dispatcher.RequestDispatcher] 调用。
     *
     * 输出帧序列：若干 `ImportProgressFrame`（`stream=true, end=false`）+
     * 一条终止帧（`end=true`，携带 `ImportResultResponse`）。
     */
    fun executeInMainProcess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.importRequest.runImport
        val cancelled = AtomicBoolean(false)
        // 当前批次的语句 —— 取消时既要置停标志（批次之间生效），也要打断正在跑的 executeBatch()
        val currentStatement = AtomicReference<PreparedStatement?>(null)

        StatementRegistry.registerCanceler(id) {
            cancelled.set(true)
            currentStatement.get()?.let { runCatching { it.cancel() } }
        }

        try {
            coroutineScope {
                val ch = Channel<Response>(Channel.BUFFERED)
                val job = launch(Dispatchers.IO) {
                    try {
                        val result = runImport(id, config, runReq, request.sessionId, cancelled, currentStatement) { progress ->
                            ch.send(
                                Response.newBuilder()
                                    .setId(id)
                                    .setSuccess(true)
                                    .setStream(true)
                                    .setEnd(false)
                                    .setImportProgress(progress)
                                    .build()
                            )
                        }
                        ch.send(
                            Response.newBuilder()
                                .setId(id)
                                .setSuccess(result.success)
                                .setStream(true)
                                .setEnd(true)
                                .setError(if (result.success) "" else result.error)
                                .setImport(importResponse { this.result = result })
                                .build()
                        )
                    } catch (e: Exception) {
                        logger.error("Import failed for request {}", id, e)
                        val message = if (cancelled.get()) "cancelled" else (e.message ?: e.javaClass.simpleName)
                        ch.send(
                            Response.newBuilder()
                                .setId(id)
                                .setSuccess(false)
                                .setStream(true)
                                .setEnd(true)
                                .setError(message)
                                .setImport(importResponse { result = failedResult(message) })
                                .build()
                        )
                    } finally {
                        ch.close()
                    }
                }
                for (msg in ch) emit(msg)
                job.join()
            }
        } finally {
            StatementRegistry.unregister(id)
        }
    }

    /**
     * 执行导入全流程：打开源 →（可选）清表 → 批量插入 → 汇总统计。
     *
     * @param onProgress 进度回调（suspend —— 由调用方桥接到 Channel，背压自然传导到读取循环）
     */
    private suspend fun runImport(
        requestId: String,
        config: ConnectionConfig,
        req: ImportRunRequest,
        sessionId: String,
        cancelled: AtomicBoolean,
        currentStatement: AtomicReference<PreparedStatement?>,
        onProgress: suspend (ImportProgressFrame) -> Unit,
    ): ImportResultResponse {
        if (req.tableName.isBlank()) {
            throw IllegalArgumentException("Missing 'tableName' in IMPORT.RUN_IMPORT payload")
        }
        val batchSize = if (req.batchSize > 0) req.batchSize else DEFAULT_BATCH_SIZE

        val conn = PoolManager.getConnection(config, req.schema, sessionId)
        try {
            val dialect = DialectLoader.getDialect(config.driver)

            // 目标表列名（按 ordinal 排序）：无表头的 CSV 靠它确定列集合 ——
            // 文件本身给不出列名，只能从表元数据取。
            // 有表头时也会用到：用于校验文件表头与表的列是否对得上（见下）。
            val targetColumns = dialect.listColumns(
                conn,
                config.database.ifBlank { "" },
                req.schema,
                req.tableName,
            ).mapNotNull { it["name"]?.toString() }

            ImportSourceFactory.open(req, targetColumns).use { source ->
                if (source.columns.isEmpty()) {
                    // 空文件 / 空表：没有列就无从拼 INSERT，返回 0 行成功，
                    // 而不是抛错让前端以为导入崩了
                    return importResultResponse { success = true }
                }

                val table = qualified(dialect, req.tableName, req.schema)
                val columns = source.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
                val placeholders = source.columns.joinToString(", ") { "?" }

                if (req.truncateFirst) {
                    // 走方言 SPI：MySQL/PG 用 TRUNCATE，SQLite/DuckDB 的实现在内部自行退化
                    dialect.truncateTable(conn, req.tableName)
                    logger.info("Import {}: cleared {} before load", requestId, req.tableName)
                }

                val insertSql = "INSERT INTO $table ($columns) VALUES ($placeholders)"
                var rowsRead = 0L
                var rowsInserted = 0L
                var rowsFailed = 0L
                var firstError: String? = null

                conn.prepareStatement(insertSql).use { stmt ->
                    // ignoreErrors 时逐行执行、登记整条语句（见下方注释），批次路径则按批登记
                    if (req.ignoreErrors) currentStatement.set(stmt)
                    try {
                        val iterator = source.rows()
                        val pendingRows = ArrayList<Map<String, String>>(batchSize)
                        while (iterator.hasNext()) {
                            if (cancelled.get()) throw IllegalStateException("cancelled")
                            val row = iterator.next()
                            rowsRead++
                            try {
                                if (req.ignoreErrors) {
                                    // 逐行执行而不是 addBatch：批量里一行失败会让**整批**无法归因，
                                    // 而且 PostgreSQL 会把失败语句所在的隐式事务整体作废，
                                    // 「整批失败后再逐行重试」只会连锁报同样的错。
                                    // 用户显式要求容忍坏行时，正确性优先于吞吐。
                                    stmt.clearParameters()
                                    bindRow(stmt, source.columns, row)
                                    rowsInserted += stmt.executeUpdate().coerceAtLeast(0).toLong()
                                } else {
                                    bindRow(stmt, source.columns, row)
                                    stmt.addBatch()
                                    pendingRows += row
                                }
                            } catch (e: Exception) {
                                rowsFailed++
                                if (firstError == null) firstError = "row $rowsRead: ${e.message}"
                                if (!req.ignoreErrors) {
                                    // 遇错即停：抛出根因，前端看到的是原因而不是一个统计数字
                                    throw IllegalArgumentException("Import aborted at row $rowsRead: ${e.message}", e)
                                }
                                continue
                            }

                            if (!req.ignoreErrors && pendingRows.size >= batchSize) {
                                rowsInserted += flushBatch(stmt, currentStatement, cancelled)
                                pendingRows.clear()
                            }
                            if (rowsRead % PROGRESS_INTERVAL == 0L) {
                                onProgress(progress(rowsRead, rowsInserted, rowsFailed))
                            }
                        }
                        if (pendingRows.isNotEmpty()) {
                            rowsInserted += flushBatch(stmt, currentStatement, cancelled)
                            pendingRows.clear()
                        }
                    } finally {
                        currentStatement.set(null)
                    }
                }

                onProgress(progress(rowsRead, rowsInserted, rowsFailed))
                logger.info(
                    "Import {} finished: read={} inserted={} failed={} table={}",
                    requestId, rowsRead, rowsInserted, rowsFailed, req.tableName,
                )
                return importResultResponse {
                    success = true
                    this.rowsRead = rowsRead
                    this.rowsInserted = rowsInserted
                    this.rowsFailed = rowsFailed
                    // 跳过的坏行摘要放在 error 里回传给调用方 —— success 仍为 true，
                    // 前端据此决定是「完成」还是「完成但有跳过」
                    error = firstError.orEmpty()
                }
            }
        } finally {
            // 会话连接由 TransactionManager 持有，COMMIT/ROLLBACK 之前不能归还
            if (sessionId.isBlank()) conn.close()
        }
    }

    /**
     * 执行一批并登记语句以便取消。
     *
     * @return 本批实际写入行数
     */
    private fun flushBatch(
        stmt: PreparedStatement,
        currentStatement: AtomicReference<PreparedStatement?>,
        cancelled: AtomicBoolean,
    ): Long {
        currentStatement.set(stmt)
        try {
            val counts = stmt.executeBatch()
            // 部分驱动对某些语句返回 SUCCESS_NO_INFO(-2)，累加会得到负值 —— 只统计明确的行数
            return counts.filter { it >= 0 }.sumOf { it.toLong() }
        } catch (e: Exception) {
            // cancel() 引发的 SQLException 走这里；交由上层统一转成 success=false / error="cancelled"
            if (cancelled.get()) throw IllegalStateException("cancelled", e)
            throw e
        } finally {
            currentStatement.set(null)
            runCatching { stmt.clearBatch() }
        }
    }

    private fun bindRow(stmt: PreparedStatement, columns: List<String>, row: Map<String, String>) {
        columns.forEachIndexed { index, column ->
            // 一律以字符串写入，由驱动按目标列类型做隐式转换 —— 与 DataHandler 的
            // 「UI 传来的都是字符串」约定一致。
            // 缺失列写 NULL 而不是空串：数值/日期列遇到 '' 会解析失败，整批报废。
            val value = row[column]
            if (value == null) stmt.setNull(index + 1, Types.NULL) else stmt.setString(index + 1, value)
        }
    }

    private fun qualified(dialect: DatabaseDialect, table: String, schema: String): String =
        if (schema.isBlank()) dialect.quoteIdentifier(table)
        else "${dialect.quoteIdentifier(schema)}.${dialect.quoteIdentifier(table)}"

    private fun progress(read: Long, inserted: Long, failed: Long) = importProgressFrame {
        rowsRead = read
        rowsInserted = inserted
        rowsFailed = failed
        message = "read=$read inserted=$inserted failed=$failed"
    }

    private fun failedResult(message: String) = importResultResponse {
        success = false
        error = message
    }
}
