/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import com.google.protobuf.Value
import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.dialect.DialectCapability
import com.langbiantianya.engine.StatementRegistry
import com.langbiantianya.grpc.AnalysisListColumnsRequest
import com.langbiantianya.grpc.AnalysisListColumnsResponse
import com.langbiantianya.grpc.AnalysisListSourcesResponse
import com.langbiantianya.grpc.AnalysisListTablesRequest
import com.langbiantianya.grpc.AnalysisListTablesResponse
import com.langbiantianya.grpc.AnalysisPullRequest
import com.langbiantianya.grpc.AnalysisPullResponse
import com.langbiantianya.grpc.AnalysisRunQueryRequest
import com.langbiantianya.grpc.AnalysisRunQueryResponse
import com.langbiantianya.grpc.ColumnDef
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.PayloadAdapter
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.Response
import com.langbiantianya.grpc.Row
import com.langbiantianya.grpc.analysisListColumnsResponse
import com.langbiantianya.grpc.analysisListSourcesResponse
import com.langbiantianya.grpc.analysisListTablesResponse
import com.langbiantianya.grpc.analysisPullProgressFrame
import com.langbiantianya.grpc.analysisPullResponse
import com.langbiantianya.grpc.analysisResponse
import com.langbiantianya.grpc.analysisRunQueryResponse
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.row
import com.langbiantianya.grpc.tableListItem
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ANALYSIS` 类别的四个路由（主设计 §3.5）：列数据源、列表、列列、跑分析 SQL。
 *
 * ## 这四条路由是「只读浏览远端」的引擎侧实现
 *
 * 分析模式里远端只做两件事：**列元数据**（本文件的三个 LIST）与**被拉取**（走
 * 既有的 `EXPORT.RUN_EXPORT`，不经过这里）。真正的计算发生在本地 DuckDB 工作区上。
 *
 * ## 「命名空间」是树的那一层的中性叫法
 *
 * MySQL 上它是**库**，PG 上它是 **schema** —— 两者对 SPI 都是
 * `listTables(conn, namespace)` 的同一个参数位。判定走的是既有的
 * [DatabaseDialect.supportsSchema]：
 *
 * - `true`（PG / H2 / DuckDB）→ 命名空间是 schema，取 `listSchemas(conn, database)`
 * - `false`（MySQL）→ 命名空间是库，取 `listDatabases(conn)`
 *
 * **不用方言名硬判**：方言名是字符串常量，判据写在字符串上就等于把
 * 「哪个方言有什么概念」复制一份到引擎里 —— 那份副本迟早与方言本身对不上。
 *
 * ## 为什么 `RUN_QUERY` 只允许打在嵌入式方言上
 *
 * `RUN_QUERY` 的语义是「在**计算引擎**上跑分析 SQL」，它必须能写
 * （工作区里的 `CREATE TABLE tmp AS SELECT …` 是 M4 的验收要求），
 * 所以它**不能**被 L4 只读闸门挡住 —— 但它**绝不能**打在远端上，
 * 否则它就成了绕过 L4 的后门。
 *
 * 判据用既有的 [DialectCapability.EMBEDDED_MODE]：它表达的正是
 * 「这个方言是本地嵌入式」。不新造同义能力位。
 */
object AnalysisHandler {
    /**
     * 分析查询的行数上限。
     *
     * 与浏览模式的 SQL 结果上限（5000）取同一个值 —— 两处数字相同不是为了省事，
     * 而是**同一个语义**：「一次结果最多铺多少行到界面上」。上限在这里做，
     * 而截断的事实由响应里的 `truncated` 字段显式告知，界面据此提示用户。
     */
    private const val MAX_ROWS = 5000

    /**
     * 拉取进度的阶段名 —— 与前端 `PullStage` 的取值一一对应。
     *
     * 为什么是**字符串**而不是枚举：进度帧走 proto，而 proto 里放枚举要多一个
     * 消息类型；阶段只有两三个值，字符串够用 —— 而且它**只出现在进度帧**里，
     * 前端拿它去认阶段，认不出就当「不知道」。
     */
    private const val PULL_STAGE_SCAN = "scan"

    /** 取消时的错误文案 —— 与前端的「拉取已取消」区分开。 */
    private const val PULL_CANCELLED_TEXT = "cancelled"

    /**
     * 列这个连接的「命名空间」。
     *
     * 无参：命名空间由**连接配置**决定（PG 的库在 `connection.database` 里，
     * 不出现在树里），所以这个请求不需要带额外参数。
     */
    suspend fun listSources(config: ConnectionConfig): AnalysisListSourcesResponse =
        withContext(Dispatchers.IO) {
            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val namespaces = listNamespaces(conn, dialect, config.database)
                analysisListSourcesResponse {
                    items += namespaces
                }
            }
        }

    /**
     * 列一个命名空间下的表。
     */
    suspend fun listTables(
        config: ConnectionConfig,
        req: AnalysisListTablesRequest,
    ): AnalysisListTablesResponse =
        withContext(Dispatchers.IO) {
            val namespace = req.namespace
            if (namespace.isBlank()) throw IllegalArgumentException("Missing 'namespace' in payload")
            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val tables = listTablesOf(conn, dialect, config.database, namespace)
                analysisListTablesResponse {
                    tables.forEach { item ->
                        this.items +=
                            tableListItem {
                                name = item["name"] ?: ""
                                type = item["type"] ?: "TABLE"
                            }
                    }
                }
            }
        }

    /**
     * 列一张表的列。
     */
    suspend fun listColumns(
        config: ConnectionConfig,
        req: AnalysisListColumnsRequest,
    ): AnalysisListColumnsResponse =
        withContext(Dispatchers.IO) {
            val namespace = req.namespace
            val table = req.table
            if (namespace.isBlank()) throw IllegalArgumentException("Missing 'namespace' in payload")
            if (table.isBlank()) throw IllegalArgumentException("Missing 'table' in payload")
            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val cols = listColumnsOf(conn, dialect, config.database, namespace, table)
                analysisListColumnsResponse {
                    cols.forEach { col ->
                        this.items +=
                            columnDef {
                                name = col["name"]?.toString() ?: ""
                                type = col["type"]?.toString() ?: ""
                                size = (col["size"] as? Number)?.toInt() ?: 0
                                isPrimaryKey = col["isPrimaryKey"] as? Boolean ?: false
                                val nullable = col["nullable"] as? Boolean
                                if (nullable != null) this.nullable = nullable
                                val defaultValue = col["defaultValue"]?.toString()
                                if (defaultValue != null) this.defaultValue = defaultValue
                            }
                    }
                }
            }
        }

    /**
     * 在**计算引擎**（本地嵌入式方言）上跑一条分析 SQL。
     *
     * 支持两类语句：
     * - 有结果集的（`SELECT`）→ 返回列与行
     * - 没有结果集的（`CREATE TABLE tmp AS SELECT …`）→ 返回 `affected_rows`
     *
     * 第二类是 M4 的验收要求：分析产物要能落在工作区里。
     */
    suspend fun runQuery(
        config: ConnectionConfig,
        req: AnalysisRunQueryRequest,
    ): AnalysisRunQueryResponse =
        withContext(Dispatchers.IO) {
            val sql = req.sql
            if (sql.isBlank()) throw IllegalArgumentException("Missing 'sql' in payload")
            val dialect = DialectLoader.getDialect(config.driver)

            // 远端方言一律拒绝 —— 否则这就是绕过 L4 闸门的后门。
            // 判据是能力位，不是「是不是本机地址」：后者判不准（远程主机上的
            // DuckDB 也会被误判成本地），而且把安全判据寄托在网络拓扑上是脆的。
            if (!dialect.capabilities.contains(DialectCapability.EMBEDDED_MODE)) {
                throw UnsupportedOperationException(Msg.text(StrKey.ENGINE_ANALYSIS_LOCAL_ONLY))
            }

            val connection = PoolManager.getConnection(config)
            return@withContext connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val hasResultSet = stmt.execute(sql)
                    if (hasResultSet) {
                        val rs = stmt.resultSet
                        val meta = rs.metaData
                        analysisRunQueryResponse {
                            buildColumnsOf(meta)?.let { columns += it }
                            var count = 0
                            while (rs.next()) {
                                // 先判上限再收行：正好等于上限时**不该**报截断，
                                // 只有真的还有下一行才算被截掉。
                                if (count >= MAX_ROWS) {
                                    truncated = true
                                    break
                                }
                                rows += buildRow(rs, meta)
                                count++
                            }
                        }
                    } else {
                        analysisRunQueryResponse {
                            affectedRows = stmt.updateCount
                        }
                    }
                }
            }
        }

    // ──────────────── 拉取：DuckDB 联邦直查 ────────────────

    /**
     * 把远端的一张表拉进本地工作区（DuckDB）—— **联邦直查，零 CSV 中转**。
     *
     * ## 引擎侧发生了什么
     *
     * 1. 借一条远端 JDBC 连接（凭据只在请求体里，**不进 SQL 文本**）；
     * 2. 在工作区的 DuckDB 上注册一个**纯 Java 表函数** —— 它把这条远端连接
     *    变成 DuckDB 里的一张表（见 [JdbcScanTableFunction]）；
     * 3. 执行 `CREATE TABLE <落点> AS SELECT * FROM <表函数>()`。
     *
     * 就这么多。**没有导出、没有 CSV、没有临时文件** —— 早先那条
     * 「远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`」的路已经删掉。
     *
     * ## 远端**只读**由构造保证
     *
     * 表函数只执行引擎自己拼的那一句 `SELECT * FROM <命名空间>.<表>`，
     * **不接受**调用方传入的 SQL。所以这条链路上压根没有写入口 ——
     * 比「会话级只读开关」更强：开关靠每个写入口都记得检查，而这里没有写入口。
     *
     * ## 半张表不会留下
     *
     * DROP + CREATE 包在一个**事务**里（JDBC 的 `setAutoCommit(false)`）：
     * 失败 / 取消 → 回滚 → 旧表原样还在，新表一点痕迹都不留。
     */
    fun executePull(request: Request): Flow<Response> =
        flow {
            val id = request.id
            val cancelled = AtomicBoolean(false)
            try {
                coroutineScope {
                    val ch = Channel<Response>(Channel.BUFFERED)
                    val job =
                        launch(Dispatchers.IO) {
                            try {
                                val result =
                                    runPull(request, cancelled) { rows ->
                                        // trySend 而不是 send：回调跑在 DuckDB 的线程上，
                                        // 不能阻塞（进度是尽力而为的，丢了就丢了）。
                                        ch.trySend(pullProgressFrame(id, rows))
                                    }
                                ch.send(pullTerminalFrame(id, result))
                            } catch (e: Exception) {
                                val isCancel = cancelled.get()
                                val message =
                                    if (isCancel) {
                                        PULL_CANCELLED_TEXT
                                    } else {
                                        e.message ?: e.javaClass.simpleName
                                    }
                                ch.send(pullFailedFrame(id, message, isCancel))
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
     * 拉取的主体 —— 借连接、注册表函数、在事务里建表。
     *
     * @param onRows 每读完一批远端行的回调（累计行数）
     */
    private suspend fun runPull(
        request: Request,
        cancelled: AtomicBoolean,
        onRows: (Long) -> Unit,
    ): AnalysisPullResponse {
        val pull = request.analysisRequest.pull
        if (pull.namespace.isBlank()) {
            throw IllegalArgumentException("Missing 'namespace' in ANALYSIS.PULL_TABLE payload")
        }
        if (pull.table.isBlank()) {
            throw IllegalArgumentException("Missing 'table' in ANALYSIS.PULL_TABLE payload")
        }
        if (pull.stagingTable.isBlank()) {
            throw IllegalArgumentException("Missing 'staging_table' in ANALYSIS.PULL_TABLE payload")
        }
        val remote = pull.remote

        val started = System.currentTimeMillis()
        // 远端的引用形式按**远端方言**（MySQL 是库名，PG 是 schema）。
        //
        // 用 `PoolManager.resolveDialect` 而不是 `DialectLoader.getDialect(driver)` ——
        // 前者在 URL 非空时**按 URL 反查**方言，而连接池也是这么建的；
        // 两处用同一套解析，引用形式才不会与实际连上的那个库对不上。
        val remoteDialect = PoolManager.resolveDialect(remote)
        val selectSql =
            "SELECT * FROM ${remoteDialect.quoteIdentifier(pull.namespace)}.${remoteDialect.quoteIdentifier(pull.table)}"

        val remoteConnection = PoolManager.getConnection(remote)
        return try {
            val workspaceConnection = PoolManager.getConnection(request.connection)
            try {
                // 工作区里的表名按**工作区方言**（DuckDB）引用 —— 落点在那儿，不在远端。
                val workspaceDialect = PoolManager.resolveDialect(request.connection)
                val staging = workspaceDialect.quoteIdentifier(pull.stagingTable)

                val functionName = JdbcScanRegistry.functionName(remote, pull.namespace, pull.table)
                JdbcScanRegistry.ensureRegistered(
                    workspaceConnection,
                    functionName,
                    JdbcScanTableFunction(remoteConnection, selectSql, onRows),
                )

                workspaceConnection.createStatement().use { stmt ->
                    StatementRegistry.registerCanceler(request.id) {
                        cancelled.set(true)
                        runCatching { stmt.cancel() }
                    }
                    // 事务：DROP + CREATE AS 一起提交。
                    //
                    // 失败 / 取消 → 回滚 → **旧表原样还在**，新表一点痕迹都不留。
                    // （DuckDB 的 DDL 是事务性的。）
                    workspaceConnection.autoCommit = false
                    try {
                        stmt.execute("DROP TABLE IF EXISTS $staging")
                        stmt.execute("CREATE TABLE $staging AS SELECT * FROM $functionName()")
                        workspaceConnection.commit()
                    } catch (e: Exception) {
                        throw e
                    }
                    // 行数：以工作区里那张表为准（不是「远端读了多少」——
                    // 两者在失败时可能对不上，而落地的行数才是用户关心的）。
                    val rows =
                        stmt.executeQuery("SELECT count(*) FROM $staging").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    AnalysisPullResponse
                        .newBuilder()
                        .setStagingTable(pull.stagingTable)
                        .setRows(rows)
                        .setElapsedMs(System.currentTimeMillis() - started)
                        .build()
                }
            } finally {
                workspaceConnection.close()
            }
        } finally {
            remoteConnection.close()
        }
    }

    /** 进度帧 —— 「已从远端读出多少行」。 */
    private fun pullProgressFrame(
        id: String,
        rows: Long,
    ): Response =
        Response
            .newBuilder()
            .setId(id)
            .setSuccess(true)
            .setStream(true)
            .setEnd(false)
            .setAnalysisPullProgress(
                analysisPullProgressFrame {
                    this.rows = rows
                    stage = PULL_STAGE_SCAN
                },
            ).build()

    /** 终止帧 —— 成功。 */
    private fun pullTerminalFrame(
        id: String,
        result: AnalysisPullResponse,
    ): Response =
        Response
            .newBuilder()
            .setId(id)
            .setSuccess(true)
            .setStream(true)
            .setEnd(true)
            .setAnalysis(analysisResponse { pull = result })
            .build()

    /** 终止帧 —— 失败 / 取消。 */
    private fun pullFailedFrame(
        id: String,
        message: String,
        cancelled: Boolean,
    ): Response =
        Response
            .newBuilder()
            .setId(id)
            .setSuccess(false)
            .setStream(true)
            .setEnd(true)
            .setError(message)
            .setAnalysis(
                analysisResponse {
                    pull =
                        AnalysisPullResponse
                            .newBuilder()
                            .setCancelled(cancelled)
                            .build()
                },
            ).build()

    // ──────────────── 命名空间 / 表 / 列：按方言能力分派 ────────────────

    /**
     * 列命名空间。
     *
     * `supportsSchema = true`（PG / H2 / DuckDB）→ 命名空间是 schema，
     * 取当前库下的 schema 列表。
     *
     * `supportsSchema = false`（MySQL）→ 命名空间就是**库**，取库列表。
     * MySQL 的 `listSchemas` 在这种情况下只会回 `[database]` 单元素，
     * 那不是「命名空间列表」，是「当前库的名字」—— 两者语义不同，不能混用。
     */
    private suspend fun listNamespaces(
        conn: Connection,
        dialect: DatabaseDialect,
        database: String,
    ): List<String> =
        if (dialect.supportsSchema) {
            dialect.listSchemas(conn, database)
        } else {
            dialect.listDatabases(conn)
        }

    /**
     * 列一个命名空间下的表。
     *
     * 命名空间落到 SPI 的哪个参数位，取决于它到底是 schema 还是库：
     * - schema 型 → `listTables(conn, database, namespace)`
     * - 库型 → `listTables(conn, namespace, "")`（MySQL 的 `listTables` 只认 database 位）
     */
    private suspend fun listTablesOf(
        conn: Connection,
        dialect: DatabaseDialect,
        database: String,
        namespace: String,
    ): List<Map<String, String>> =
        if (dialect.supportsSchema) {
            dialect.listTables(conn, database, namespace)
        } else {
            dialect.listTables(conn, namespace, "")
        }

    /**
     * 列一张表的列。
     *
     * 与 [listTablesOf] 同样的分派，但**库型**那一支要把库名同时放进
     * `database` 与 `schema` 两个参数位 —— 这是浏览模式一直在用的写法
     * （`TableHandler.columnList` 传的是 `listColumns(conn, config.database, schema, table)`，
     * 而那个 `schema` 位装的从来就是库名）：MySQL 未覆写 `listColumns`，
     * 走的是 SPI 默认的 JDBC `metaData.getColumns(catalog, schemaPattern, …)`，
     * 两个位都得对上库名才取得到列。
     */
    private suspend fun listColumnsOf(
        conn: Connection,
        dialect: DatabaseDialect,
        database: String,
        namespace: String,
        table: String,
    ): List<Map<String, Any?>> =
        if (dialect.supportsSchema) {
            dialect.listColumns(conn, database, namespace, table)
        } else {
            dialect.listColumns(conn, namespace, namespace, table)
        }

    // ──────────────── 结果集 → 协议消息 ────────────────

    /**
     * 结果集的列定义。
     *
     * 类型名取 JDBC 的 `getColumnTypeName` —— 那是**数据库自己说的**类型名
     * （`VARCHAR` / `INTEGER` / `DECIMAL` …），比从 `getColumnType` 的 JDBC 常量
     * 反推更贴近用户在自己库里看到的东西。
     */
    private fun buildColumnsOf(meta: ResultSetMetaData): List<ColumnDef> {
        val out = mutableListOf<com.langbiantianya.grpc.ColumnDef>()
        for (i in 1..meta.columnCount) {
            out +=
                columnDef {
                    name = meta.getColumnName(i)
                    type = meta.getColumnTypeName(i)
                }
        }
        return out
    }

    /**
     * 结果集的一行。
     *
     * 单元格一律按**字符串**取：分析模式的结果表是给人看的，
     * 而不同方言的日期 / 数值 / 二进制各有各的 `getObject` 行为，
     * 按类型分派只会得到一堆方言差异。原始类型信息在列定义里（`type`），
     * 数值格式化交给界面。
     */
    private fun buildRow(
        rs: ResultSet,
        meta: ResultSetMetaData,
    ): Row =
        row {
            for (i in 1..meta.columnCount) {
                val raw = rs.getString(i)
                val value: Value =
                    if (raw == null) {
                        PayloadAdapter.toValue(JsonNull)
                    } else {
                        PayloadAdapter.toValue(JsonPrimitive(raw))
                    }
                values.put(meta.getColumnName(i), value)
            }
        }
}
