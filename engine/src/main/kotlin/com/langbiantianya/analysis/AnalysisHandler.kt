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
import com.langbiantianya.grpc.AnalysisAccessMode
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
import com.langbiantianya.metadata.JdbcColumnMetadata
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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    /** 降级这类「用户不该看见」的细节时用它记一笔。 */
    private val logger = LoggerFactory.getLogger(AnalysisHandler::class.java)

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
                // 自增标记来自 JDBC 元数据：各方言的 listColumns 都不吐这一项
                // （MySQL / PG 走默认实现，H2 / DuckDB / SQLite 走各自覆写）。
                //
                // ⚠️ 为什么分析模式也要补：浏览模式与分析模式**共用同一个树组件**
                // （`:shared` 的 `tree/ObjectTree.kt`），两边的字段行必须画得一模一样 ——
                // 浏览模式那一侧补了，这里不补就成「同一个组件、两种信息」。
                val jdbcColumns = JdbcColumnMetadata.load(conn, config.driver, config.database, namespace, table)
                analysisListColumnsResponse {
                    cols.forEach { col ->
                        val name = col["name"]?.toString() ?: ""
                        val autoIncrement =
                            jdbcColumns[name.lowercase()]?.autoIncrement
                                ?: (col["autoIncrement"] as? Boolean ?: false)
                        this.items +=
                            columnDef {
                                this.name = name
                                this.type = col["type"]?.toString() ?: ""
                                this.size = (col["size"] as? Number)?.toInt() ?: 0
                                this.isPrimaryKey = col["isPrimaryKey"] as? Boolean ?: false
                                this.autoIncrement = autoIncrement
                                // `nullable` 是 proto3 的 `optional`，保留 presence：
                                // 「方言没给这个信息」与「方言说它是 null」必须分得开。
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
                // 落点可以带 schema（「添加数据源」把每个源的表放进自己的 schema）。
                // 两段都**分别**引用 —— 拼成一段再引用会把 schema 名里的引号吃掉。
                val staging =
                    if (pull.targetSchema.isBlank()) {
                        workspaceDialect.quoteIdentifier(pull.stagingTable)
                    } else {
                        "${workspaceDialect.quoteIdentifier(pull.targetSchema)}." +
                            workspaceDialect.quoteIdentifier(pull.stagingTable)
                    }

                // 「正在跑的那条语句」—— 取消要打在**它**身上（见下面的说明）。
                val runningStatement = AtomicReference<Statement?>(null)

                StatementRegistry.registerCanceler(request.id) {
                    cancelled.set(true)
                    // ## 为什么取消要打在**正在跑的那条语句**上
                    //
                    // DuckDB JDBC 的 `Statement.cancel()` 只对「**本语句上正在执行的查询**」
                    // 生效 —— 源码第一件事就是 `if (!stmtRefLock.isLocked()) return;`。
                    // 拿一条**新**语句去 cancel 是**空操作**（实测：取消发出去，
                    // 拉取照跑到底，还报「成功」）。
                    //
                    // 而 `SYSTEM.CANCEL` 到引擎时能拿到的只有**请求 id**，
                    // 引擎得自己知道那条语句是哪一条 —— 所以由 [runInTransaction]
                    // 把「正在跑的那条」登记到这里。
                    runCatching { runningStatement.get()?.cancel() }
                }

                val rows =
                    loadIntoWorkspace(
                        workspace = workspaceConnection,
                        remote = remote,
                        remoteDialect = remoteDialect,
                        namespace = pull.namespace,
                        table = pull.table,
                        staging = staging,
                        selectSql = selectSql,
                        remoteConnection = remoteConnection,
                        onRows = onRows,
                        runningStatement = runningStatement,
                        cancelled = cancelled,
                        preferExtension = pull.accessMode == AnalysisAccessMode.ANALYSIS_ACCESS_MODE_EXTENSION_FIRST,
                    )

                AnalysisPullResponse
                    .newBuilder()
                    .setStagingTable(pull.stagingTable)
                    .setRows(rows)
                    .setElapsedMs(System.currentTimeMillis() - started)
                    .build()
            } finally {
                workspaceConnection.close()
            }
        } finally {
            remoteConnection.close()
        }
    }

    /**
     * 把远端的一张表搬进工作区 —— **两条后端**，先试原生扫描器。
     *
     * | 后端 | 什么时候用 | 特点 |
     * |---|---|---|
     * | 原生扫描器（`mysql_scanner` / `postgres_scanner`） | 请求说「扩展优先」，或全局开关**显式开了** | 少一次拷贝、类型用 DuckDB 官方规则；**没有实时行数进度** |
     * | 纯 Java 表函数 | 其余情况 | 任何有 JDBC 驱动的源都能走；实时行数进度；类型映射是我们自己定的（跨机器一致） |
     *
     * 原生那条路走不通就**降级**到纯 Java —— 用户看到的是「拉取成功」，
     * 不是「你的扩展没装」。
     *
     * @param preferExtension 请求说的是「扩展优先」吗（见 `AnalysisAccessMode`）。
     *   「拉取远端表」一直走纯 Java（类型映射跨机器一致）；
     *   「添加数据源」要的是 DuckDB **原生接入**，所以由请求说明 —— 而不是拧一个
     *   全局开关，让两条路由悄悄换掉类型映射。
     */
    private fun loadIntoWorkspace(
        workspace: Connection,
        remote: ConnectionConfig,
        remoteDialect: DatabaseDialect,
        namespace: String,
        table: String,
        staging: String,
        selectSql: String,
        remoteConnection: Connection,
        onRows: (Long) -> Unit,
        runningStatement: AtomicReference<Statement?>,
        cancelled: AtomicBoolean,
        preferExtension: Boolean,
    ): Long {
        // ① 原生扫描器
        //
        // 三段式，顺序**不能换**：挂载（事务外）→ 建表（事务内）→ 摘除（提交之后）。
        // 摘除若落在事务里，DuckDB 会中止事务 → 提交落空 → 整张表消失
        // （症状是「拉取报成功、可工作区里查不到表」）。详见 [NativeExtensionScanner]。
        if (preferExtension || NativeExtensionScanner.isEnabled()) {
            val alias = NativeExtensionScanner.nextAlias()
            if (NativeExtensionScanner.attach(workspace, remote, remoteDialect, alias)) {
                val native =
                    try {
                        runInTransaction(workspace, runningStatement, cancelled) { stmt ->
                            NativeExtensionScanner.createStaging(stmt, alias, namespace, table, staging)
                        }
                    } catch (e: Exception) {
                        // 取消**不是**「这条路走不通」—— 取消了就不要再降级重来一遍。
                        if (e is PullCancelledException) throw e
                        // 原生这条路失败 → 降级到纯 Java 表函数。
                        // 失败的原因不该让用户看见：纯 Java 那条路**永远**能走。
                        logger.warn("原生扫描器拉取失败，改用纯 Java 表函数：{}", e.message)
                        null
                    } finally {
                        // 摘除在**提交之后**（finally 是在 runInTransaction 返回之后才跑的）。
                        // 挂在工作区上的远端，其目录记录里**含明文密码** —— 必须摘掉。
                        NativeExtensionScanner.detach(workspace, alias)
                    }
                if (native != null) {
                    // 原生路径**没有实时进度**（DuckDB 的 CTAS 不吐行数），
                    // 只在落地之后报一次总数。
                    onRows(native)
                    return native
                }
            }
        }

        // ② 纯 Java 表函数（默认路径）
        //
        // 表函数是**无状态**的：这一次扫描的连接、SQL、进度回调都放在**上下文**里，
        // 用一个**句柄**（字符串）指出来，句柄作为 SQL 参数传进去。
        //
        // 为什么不能把连接直接塞进闭包：注册在 DuckDB 实例上的函数活得比一次拉取久，
        // 第一次拉取一结束连接就还回池了 —— 第二次拉同一张表就会撞上
        // `Connection is closed`（实测）。
        val handle =
            JdbcScanRegistry.beginScan(
                ScanContext(
                    connection = remoteConnection,
                    selectSql = selectSql,
                    onChunk = onRows,
                ),
            )
        try {
            // 注册表函数**必须在事务外** ——
            // 它自己会开一条语句，用完就关；而 JDBC 规定「语句关闭时若还有未提交的事务，
            // 事务会被结束」—— 于是事务里的建表跟着一起没了
            // （症状：拉取报成功、行数也对，可工作区里查不到那张表）。
            JdbcScanRegistry.ensureRegistered(
                workspace,
                JdbcScanTableFunction(JdbcScanRegistry::contextOf),
            )
            val rows =
                runInTransaction(workspace, runningStatement, cancelled) { stmt ->
                    stmt.execute("DROP TABLE IF EXISTS $staging")
                    stmt.execute(
                        "CREATE TABLE $staging AS SELECT * FROM ${JdbcScanRegistry.FUNCTION_NAME}('$handle')",
                    )
                    stmt.executeQuery("SELECT count(*) FROM $staging").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                } ?: 0L
            onRows(rows)
            return rows
        } finally {
            // 扫描结束（成功 / 失败 / 取消都一样）→ 收掉上下文。
            // 漏掉就是一次泄漏：表里挂着一个已经关掉的连接。
            JdbcScanRegistry.endScan(handle)
        }
    }

    /**
     * 在一个事务里做一件事。
     *
     * - 成功 → 提交；
     * - 返回 `null`（这条路不可用）→ **回滚**（可能已经中止了）→ 返回 `null`；
     * - 抛异常 → 回滚 → 抛出去。
     *
     * ## 为什么「回滚」这么要紧
     *
     * DuckDB 在一条语句失败时会**中止当前事务** —— 此后同一条连接上的
     * 任何语句都报「transaction is aborted」。所以「原生那条路失败 →
     * 降级到纯 Java」必须**换一个干净的事务**，否则降级本身也会失败。
     *
     * ## 为什么**提交必须在语句还开着的时候**发生（实测抓出来的）
     *
     * JDBC 规定：语句 `close()` 时若还有未提交的事务，事务会被**结束**。
     * 于是「先 `use { }` 关语句、再 commit」的写法里，commit 落到的是
     * 一个**已经结束**的事务 —— 建出来的表**整张消失**（症状：拉取报成功、
     * 行数也对，可工作区里查不到那张表）。
     *
     * 所以这里的事务体**共用一条语句**，提交也在它还开着的时候做。
     *
     * ## 「正在跑的那条语句」要交出去
     *
     * 取消（`SYSTEM.CANCEL`）要打在**本事务正在用的这条语句**上 ——
     * DuckDB JDBC 的 `Statement.cancel()` 只对「本语句上正在执行的查询」生效
     * （拿一条新语句去 cancel 是空操作，实测）。
     * 所以这里把语句登记到 [runningStatement]，取消方拿得到它。
     *
     * ## 提交前再看一眼取消标志
     *
     * 取消可能落在「扫描还没开始」的时候 —— 那时没有语句可打断，
     * 但用户**确实**按了取消。所以提交前要再看一眼标志：
     * 取消了就**回滚**（不留表）并把这次拉取收成「取消」。
     */
    private fun <T> runInTransaction(
        connection: Connection,
        runningStatement: AtomicReference<Statement?>,
        cancelled: AtomicBoolean,
        body: (java.sql.Statement) -> T?,
    ): T? {
        connection.autoCommit = false
        return try {
            connection.createStatement().use { stmt ->
                runningStatement.set(stmt)
                try {
                    val result = body(stmt)
                    when {
                        result == null -> {
                            runCatching { connection.rollback() }
                            null
                        }

                        cancelled.get() -> {
                            // 取消落在扫描之前 —— 提交就等于「用户不要了还留下表」。
                            runCatching { connection.rollback() }
                            throw PullCancelledException()
                        }

                        else -> {
                            // 提交必须在语句还开着的时候（见上面的说明）。
                            connection.commit()
                            result
                        }
                    }
                } finally {
                    runningStatement.set(null)
                }
            }
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
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

/**
 * 拉取被用户取消 —— **内部信号**。
 *
 * ## 为什么要有它
 *
 * 界面要分开说「用户取消了」和「拉取失败了」—— 前者是**正常的**，
 * 后者要显示错误信息。而「取消」在引擎里有两种到达方式：
 *
 * - 取消打在**正在跑的那条语句**上 → DuckDB 抛「INTERRUPT Error」；
 * - 取消落在**扫描开始之前** → 没有语句可打断，只能由我们自己收口。
 *
 * 两种都要收成同一个「取消」信号，界面的处理才只有**一条**路。
 * 外层（[AnalysisHandler.executePull]）看到取消标志就把它写成
 * `cancelled=true` 的终止帧，前端据此抛「已取消」而不是「失败」。
 */
internal class PullCancelledException : RuntimeException("pull cancelled")
