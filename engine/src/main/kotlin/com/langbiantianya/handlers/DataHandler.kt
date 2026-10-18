/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.google.protobuf.Value
import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.engine.StatementRegistry
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.DataCreateRequest
import com.langbiantianya.grpc.DataCreateResponse
import com.langbiantianya.grpc.DataDeleteRequest
import com.langbiantianya.grpc.DataDeleteResponse
import com.langbiantianya.grpc.DataListPagedResponse
import com.langbiantianya.grpc.DataListRequest
import com.langbiantianya.grpc.DataRowFrame
import com.langbiantianya.grpc.DataUpdateRequest
import com.langbiantianya.grpc.DataUpdateResponse
import com.langbiantianya.grpc.EmptyValuePolicy
import com.langbiantianya.grpc.PayloadAdapter
import com.langbiantianya.grpc.Row
import com.langbiantianya.grpc.TableCapabilities
import com.langbiantianya.grpc.dataCreateResponse
import com.langbiantianya.grpc.dataDeleteResponse
import com.langbiantianya.grpc.dataListPagedResponse
import com.langbiantianya.grpc.dataRowFrame
import com.langbiantianya.grpc.dataUpdateResponse
import com.langbiantianya.grpc.row
import com.langbiantianya.grpc.tableCapabilities
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object DataHandler {
    private val logger = LoggerFactory.getLogger(DataHandler::class.java)

    /** 二进制单元格在前端的占位符 —— 二进制列不做 getString，只回这个常量 */
    private const val BINARY_PLACEHOLDER = "[LOB Data]"

    /**
     * 二进制列的 JDBC 类型码（[java.sql.Types]）—— 二进制判定的**第一依据**。
     *
     * 类型码由 JDBC 规范定义，跨驱动稳定；类型名则是厂商自定义字符串，差异极大。
     */
    private val BINARY_SQL_TYPES = setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)

    /**
     * 二进制列的类型名兜底集合 —— 仅在类型码**无法判定**时补充。
     *
     * 存在的意义：个别驱动会把二进制列的类型码映射成 [Types.LONGVARCHAR] 这类
     * 文本码（JDBC 2.0 时代的粗粒度映射），此时只能靠类型名认出二进制。
     * 集合刻意收窄到各家驱动对二进制的标准叫法，避免再出现
     * 「把 TEXT 误认成 LOB」这类误伤。
     */
    private val BINARY_TYPE_NAMES = setOf("BLOB", "BYTEA", "BINARY", "VARBINARY", "LONGVARBINARY", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB")

    /**
     * 流式 fetch size (JDBC 游标行数)
     */
    private const val STREAM_FETCH_SIZE = 100

    /**
     * `DatabaseMetaData.getTables` 里代表「真表」的 `TABLE_TYPE` 取值。
     *
     * MySQL 驱动回 `TABLE`，H2 / PostgreSQL 回 SQL 标准叫法 `BASE TABLE` ——
     * 见 [isBaseTable] 的注释。
     */
    private val BASE_TABLE_TYPES = setOf("TABLE", "BASE TABLE")

    /**
     * 列类型缓存 — 每次 create/update/delete 都查方言 listColumns 是浪费
     * key: "driver|database|schema|table"
     * TableHandler.create/update/delete 时通过 [invalidateColumnTypeCache] 失效
     */
    private val columnTypeCache = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * 流式模式（req.hasPageSize() && req.pageSize == 0）通过 [onRow] 每行回调一次；
     * 否则返回分页 [DataListPagedResponse]。
     */
    suspend fun list(
        config: ConnectionConfig,
        req: DataListRequest,
        sessionId: String = "",
        requestId: String = "",
        onRow: (suspend (DataRowFrame) -> Unit)? = null,
    ): DataListPagedResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val page = if (req.page == 0) 1 else req.page
            val pageSize = if (req.hasPageSize()) req.pageSize else 50

            val whereRaw = req.where.ifBlank { null }
            val orderByRaw = req.orderBy.ifBlank { null }
            val schema = req.schema

            val connection = PoolManager.getConnection(config, schema, sessionId)
            val dialect = DialectLoader.getDialect(config.driver)

            // 按方言规则校验 SQL 片段安全性
            if (!whereRaw.isNullOrBlank()) dialect.validateSqlFragment(whereRaw, "where")
            if (!orderByRaw.isNullOrBlank()) {
                dialect.validateSqlFragment(orderByRaw, "orderBy")
                dialect.validateOrderBy(orderByRaw)
            }

            val whereSql = if (!whereRaw.isNullOrBlank()) " WHERE $whereRaw" else ""
            val orderBySql = if (!orderByRaw.isNullOrBlank()) " ORDER BY $orderByRaw" else ""

            val conn = connection
            return@withContext try {
                // 查询总行数（带 WHERE）
                val countSql = "SELECT COUNT(*) AS cnt FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql"
                val total =
                    conn.prepareStatement(countSql).use { countStmt ->
                        countStmt.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong("cnt") else 0L
                        }
                    }

                if (pageSize == 0 && onRow != null) {
                    // 流式全量模式：使用 JDBC 游标逐行读取
                    val sql = "SELECT * FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql$orderBySql"
                    val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                    try {
                        conn
                            .prepareStatement(
                                sql,
                                ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY,
                            ).use { stmt ->
                                // 登记到 StatementRegistry —— 大表全量读取时 SYSTEM.CANCEL 才能真正打断 rs.next()
                                StatementRegistry.register(requestId, stmt)
                                try {
                                    stmt.fetchSize = STREAM_FETCH_SIZE
                                    stmt.executeQuery().use { rs ->
                                        while (rs.next()) {
                                            onRow(
                                                dataRowFrame {
                                                    this.total = total
                                                    this.page = 0
                                                    this.pageSize = 1
                                                    row = buildRow(rs)
                                                },
                                            )
                                        }
                                    }
                                } finally {
                                    StatementRegistry.unregister(requestId)
                                }
                            }
                    } finally {
                        dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
                    }
                    // 流式模式不回传 paged body — 返回空 paged 仅占位（dispatcher 不会使用）
                    dataListPagedResponse {
                        this.total = total
                        this.page = 0
                        this.pageSize = 0
                    }
                } else {
                    // 普通分页模式
                    val offset = (page - 1) * pageSize
                    val sql = "SELECT * FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql$orderBySql LIMIT ? OFFSET ?"
                    // 写能力随数据一起回 —— 前端不再自己发 TABLE.COLUMN_LIST 去猜主键。
                    // 只在普通分页分支算：流式分支（pageSize=0）只用来逐行回 frame，
                    // 前端不会拿它做写操作，为它查元数据是纯浪费。
                    val capabilities = buildTableCapabilities(conn, config, schema, req.tableName)
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setInt(1, pageSize)
                        stmt.setInt(2, offset)
                        stmt.executeQuery().use { rs ->
                            dataListPagedResponse {
                                this.total = total
                                this.page = page
                                this.pageSize = pageSize
                                while (rs.next()) {
                                    this.rows += buildRow(rs)
                                }
                                this.capabilities = capabilities
                            }
                        }
                    }
                }
            } finally {
                // 会话连接由 TransactionManager 持有，COMMIT/ROLLBACK 之前不能归还池
                if (sessionId.isBlank()) conn.close()
            }
        }

    /**
     * Build a typed Row proto from a JDBC ResultSet. Each cell is packed as Value;
     * the column set is dynamic (table-specific) so cell values stay Value while the
     * row envelope is typed.
     *
     * 只有**二进制**列被替换成占位符，其余（含 TEXT/LONGTEXT/CLOB/NCLOB 等大对象）
     * 一律用 `rs.getString(i)` 取真实值 —— 表格内编辑数据依赖真实值。
     */
    private fun buildRow(rs: ResultSet): Row {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount
        return row {
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val value: Value =
                    if (isBinaryColumn(metaData, i)) {
                        PayloadAdapter.toValue(JsonPrimitive(BINARY_PLACEHOLDER))
                    } else {
                        PayloadAdapter.toValue(JsonPrimitive(rs.getString(i)))
                    }
                values.put(columnName, value)
            }
        }
    }

    /**
     * 判定某列是否为二进制列 —— 决定该单元格是回占位符还是回真实值。
     *
     * 为什么按「二进制 vs 文本」分，而不是按「长度 / 是不是 LOB」分：
     * - LOB 是**长度**概念，二进制与文本**各有** LOB。旧实现用类型名集合 `LOB_TYPES`
     *   判定，而 `TEXT` 是 SQLite(xerial JDBC) 所有文本列的类型名，于是浏览任何表，
     *   每个字符串列都变成 `[LOB Data]`，真实值彻底拿不到，表格内编辑无从下手；
     *   同样 `LONGTEXT` 也会误伤 MySQL 的 TEXT 列。
     * - 真正不能读的是**二进制**：对 BLOB/BYTEA 调 `getString()`，某些驱动会抛异常，
     *   另一些会把字节按本地字符集解码成乱码。所以判据只认二进制。
     * - 因此 H2 的 `CLOB`(`CHARACTER LARGE OBJECT`)、MySQL 的 `LONGTEXT`、
     *   SQLite 的 `TEXT` 都必须回真实值，它们的内容本身就是文本，`getString()` 安全。
     *
     * 判定顺序：先看 [java.sql.Types] 类型码（跨驱动稳定），再退化到类型名兜底，
     * 用于覆盖把二进制映射成文本码的老驱动。
     */
    private fun isBinaryColumn(
        metaData: java.sql.ResultSetMetaData,
        columnIndex: Int,
    ): Boolean = isBinaryColumnType(metaData.getColumnType(columnIndex), metaData.getColumnTypeName(columnIndex))

    /**
     * 二进制判定的**唯一**实现 —— 读单元格（[buildRow]）与算写能力
     * （[buildTableCapabilities]）两处共用。
     *
     * 为什么必须共用：两处对「二进制」的判断一旦分叉，后果是不对称且不可逆的。
     * 能力侧若把二进制列误判成可编辑，前端就会把 [BINARY_PLACEHOLDER] 当成用户填的值
     * 写回数据库，用一句提示语覆盖掉真实数据。
     *
     * @param sqlTypeCode [java.sql.Types] 类型码；拿不到时传 [Types.OTHER]（只会走名字兜底）
     * @param typeName 驱动给的类型名，允许 null
     */
    private fun isBinaryColumnType(
        sqlTypeCode: Int,
        typeName: String?,
    ): Boolean {
        if (sqlTypeCode in BINARY_SQL_TYPES) return true
        return typeName != null && typeName.uppercase() in BINARY_TYPE_NAMES
    }

    /**
     * 计算这张表的写能力 —— 随 [DataListPagedResponse] 一起回给前端。
     *
     * 判据全部来自**列定义 + 对象类型**，前端据此决定「编辑 / 新增 / 删除」入口能不能用：
     * - `can_update` / `can_delete` = 有主键列 **且** 是真表；
     * - `can_insert` = 是真表 **且** 至少有一列既非自增也非二进制（没主键照样能 INSERT）；
     * - `read_only_columns` = 主键列 + 二进制列；`editable_columns` = 全部列 − 该集合；
     * - `insertable_columns` = 全部列 − 自增列 − 二进制列（自增列交给数据库生成）；
     * - `nullable_columns` = 允许取 NULL 的列（前端据此决定画不画「设为 NULL」）；
     * - `primary_key_columns` = 主键列（按列序）—— 前端拿它拼 `UPDATE` / `DELETE` 的 `WHERE`，
     *   复合主键必须整组返回。
     *
     * **视图一律不放行**（即便它有列、部分可写）：视图的写语义由建视图者决定，
     * 猜错了会出现「引擎说能改、数据库报不可更新」这种前端无法解释的失败，
     * 比直接禁掉入口更糟。
     *
     * ## 为什么不做缓存
     *
     * 直觉上「翻页很频繁，元数据查一遍太贵」，所以很容易想加个 map。但缓存换来的
     * 是一整类**陈旧状态**：建表 / 改列 / 加主键之后，缓存仍按旧能力把写入口亮出来，
     * 用户点下去撞数据库报错；反过来更糟 —— 缓存说不能写而其实能写，功能就凭空消失，
     * 且这类问题只在「碰巧清过缓存」时才复现。相对地，一次元数据查询的代价与一页
     * `SELECT *` + `COUNT(*)` 相比可以忽略。宁可每次多查一次元数据，也不要发一份
     * 「自己都不确定还对不对」的权限说明。
     *
     * ## 失败时一律收紧
     *
     * `listColumns` / `getTables` 抛异常时**不能让整个 `DATA.LIST` 失败** ——
     * 数据读得出来却因为一条次要元数据失败而整页报错，比按钮少两个严重得多。
     * 捕获后返回「什么都不许做」的能力（见 [denyAllCapabilities]）并 `logger.warn`。
     */
    private suspend fun buildTableCapabilities(
        conn: Connection,
        config: ConnectionConfig,
        schema: String,
        tableName: String,
    ): TableCapabilities =
        try {
            val dialect: DatabaseDialect = DialectLoader.getDialect(config.driver)
            val columns = dialect.listColumns(conn, config.database ?: "", schema, tableName)
            val isBaseTable = isBaseTable(conn, config, schema, tableName)
            // 自增与二进制类型码来自 JDBC 元数据：各方言的 listColumns 目前没有统一吐出
            // autoIncrement，且 H2 走 INFORMATION_SCHEMA 时类型名是 SQL 标准名（如
            // `BINARY VARYING`）而非驱动类型名，只靠类型名会漏判二进制列。
            val jdbcColumns = loadJdbcColumnMeta(conn, config, schema, tableName)

            val readOnly = mutableListOf<String>()
            val editable = mutableListOf<String>()
            val insertable = mutableListOf<String>()
            val nullable = mutableListOf<String>()
            val primaryKey = mutableListOf<String>()
            var hasPrimaryKey = false
            for (col in columns) {
                val name = col["name"] as? String ?: continue
                val jdbc = jdbcColumns[name.lowercase()]
                val isBinary =
                    isBinaryColumnType(
                        jdbc?.typeCode ?: Types.OTHER,
                        jdbc?.typeName ?: col["type"] as? String,
                    )
                val isPrimaryKey = col["isPrimaryKey"] as? Boolean ?: false
                val isAutoIncrement = jdbc?.autoIncrement ?: (col["autoIncrement"] as? Boolean ?: false)
                if (isPrimaryKey) {
                    hasPrimaryKey = true
                    primaryKey += name
                }

                if (isPrimaryKey || isBinary) readOnly += name
                if (!isPrimaryKey && !isBinary) editable += name
                if (!isAutoIncrement && !isBinary) insertable += name
                if (col["nullable"] as? Boolean ?: false) nullable += name
            }

            val writable = isBaseTable && hasPrimaryKey
            tableCapabilities {
                canUpdate = writable
                canInsert = isBaseTable && insertable.isNotEmpty()
                canDelete = writable
                readOnlyColumns += readOnly
                editableColumns += editable
                insertableColumns += insertable
                nullableColumns += nullable
                primaryKeyColumns += primaryKey
                // 空串写空串，不是 NULL —— 见 EmptyValuePolicy 的注释。
                emptyValuePolicy = EmptyValuePolicy.EMPTY_AS_STRING
            }
        } catch (e: Exception) {
            logger.warn(
                "buildTableCapabilities failed for driver=${config.driver} table=$tableName: ${e.message}",
                e,
            )
            denyAllCapabilities()
        }

    /**
     * 「什么都不许做」的能力 —— 元数据查失败时的**收紧**方向，绝不退回「都放行」。
     *
     * `emptyValuePolicy` 也退回 `UNSPECIFIED`：前端据此知道引擎**没给出**空串语义约定，
     * 于是连写入口都一并禁用，而不是拿默认值去猜空串是 NULL 还是空串。
     */
    private fun denyAllCapabilities(): TableCapabilities =
        tableCapabilities {
            canUpdate = false
            canInsert = false
            canDelete = false
            emptyValuePolicy = EmptyValuePolicy.EMPTY_VALUE_UNSPECIFIED
        }

    /**
     * JDBC `DatabaseMetaData.getColumns` 抽出的列元数据 —— 自增标记与二进制类型码的来源。
     *
     * 查不到（部分驱动不支持）时返回空 map，调用方退回方言给的类型名判定；
     * **任何异常都只丢这一项**，不影响其余能力计算。
     */
    private fun loadJdbcColumnMeta(
        conn: Connection,
        config: ConnectionConfig,
        schema: String,
        tableName: String,
    ): Map<String, JdbcColumnMeta> {
        val result = LinkedHashMap<String, JdbcColumnMeta>()
        val (catalog, lookupSchema) = metadataLookupScope(config.driver, config.database, schema)
        for (cand in metadataLookupNames(config.driver, tableName)) {
            try {
                conn.metaData.getColumns(catalog, lookupSchema, cand, "%").use { rs ->
                    // 老驱动可能根本没有 IS_AUTOINCREMENT 列 —— 先问一次，别在每行里试错
                    val hasAutoIncrement =
                        runCatching {
                            (1..rs.metaData.columnCount).any { i ->
                                rs.metaData.getColumnName(i).equals("IS_AUTOINCREMENT", ignoreCase = true)
                            }
                        }.getOrDefault(false)
                    while (rs.next()) {
                        val name = rs.getString("COLUMN_NAME") ?: continue
                        val typeCode = rs.getInt("DATA_TYPE")
                        val typeName = rs.getString("TYPE_NAME")
                        val autoIncrement =
                            hasAutoIncrement &&
                                "YES".equals(rs.getString("IS_AUTOINCREMENT"), ignoreCase = true)
                        result.putIfAbsent(name.lowercase(), JdbcColumnMeta(typeCode, typeName ?: "", autoIncrement))
                    }
                }
            } catch (e: Exception) {
                logger.warn(
                    "getColumns failed for driver=${config.driver} table=$cand: ${e.message}",
                    e,
                )
            }
        }
        return result
    }

    /**
     * 判断对象是**真表**而非视图 —— `getTables` 里存在 `TABLE_TYPE` 为表的记录才算。
     *
     * 不用「不是 VIEW 就是表」：临时表、系统表、厂商自造类型（`SYSTEM TABLE`、`FOREIGN TABLE`）
     * 都会被那条捷径放行，而这些对象的写语义各不相同。
     *
     * 为什么认两种拼法：`TABLE_TYPE` 的取值在 JDBC 上并不统一 —— MySQL 驱动回
     * `TABLE`，而 H2 / PostgreSQL（`information_schema.tables` 的标准叫法）回 `BASE TABLE`。
     * 只认 `TABLE` 会把 H2 与 PG 上的**每一张真表**都判成非表，写入口全灭且无从排查。
     */
    private fun isBaseTable(
        conn: Connection,
        config: ConnectionConfig,
        schema: String,
        tableName: String,
    ): Boolean {
        val (catalog, lookupSchema) = metadataLookupScope(config.driver, config.database, schema)
        for (cand in metadataLookupNames(config.driver, tableName)) {
            conn.metaData.getTables(catalog, lookupSchema, cand, null).use { rs ->
                while (rs.next()) {
                    if (BASE_TABLE_TYPES.any { it.equals(rs.getString("TABLE_TYPE"), ignoreCase = true) }) return true
                }
            }
        }
        return false
    }

    /**
     * 元数据查表名的候选名 —— 照抄 `DatabaseDialect.listColumns` 默认实现的口径。
     *
     * H2 把未加引号的标识符存成大写，直接拿前端传来的小写名查 `getTables` 会得到空结果，
     * 于是真表被当成视图（写入口全灭）。加上大写候选即可，两边口径不一致会出现
     * 「列查得到、表查不到」的半吊子状态。
     */
    private fun metadataLookupNames(
        driver: String,
        tableName: String,
    ): List<String> =
        buildList {
            add(tableName)
            if (driver.equals("H2", ignoreCase = true)) add(tableName.uppercase())
        }.distinct()

    /** 元数据查询的 (catalog, schema) —— H2 不吃 catalog 且空 schema 要补 PUBLIC，其余原样传。 */
    private fun metadataLookupScope(
        driver: String,
        database: String?,
        schema: String,
    ): Pair<String?, String> =
        if (driver.equals("H2", ignoreCase = true)) {
            null to schema.ifBlank { "PUBLIC" }
        } else {
            database?.takeIf { it.isNotBlank() } to schema
        }

    /** JDBC 元数据里的单列信息。 */
    private data class JdbcColumnMeta(
        val typeCode: Int,
        val typeName: String,
        val autoIncrement: Boolean,
    )

    /**
     * 本次要写进 SQL 的列清单 —— `values/changes` 的键 ∪ `null_columns`。
     *
     * 为什么 `null_columns` 里没出现在 map 里的列也要进来：用户「只想把某一列置 NULL」时，
     * 该列**根本不在 map 里**。若只按 map 的键拼列清单，SQL 里就没有这一列 ——
     * 请求看起来成功、实际什么都没改，是最难排查的一类静默失败。
     *
     * 用 [LinkedHashSet] 保持 map 的原有顺序（便于 SQL 与绑定下标一一对应），
     * 同时忽略重复列名（map 里已有的列不会被 `null_columns` 顶掉位置）。
     */
    private fun targetColumns(
        values: Map<String, String>,
        nullColumns: List<String>,
    ): List<String> = LinkedHashSet(values.keys).apply { addAll(nullColumns) }.toList()

    /**
     * 绑定一个待写列 —— 在 `null_columns` 里就写 SQL NULL，否则按列类型绑定字符串值。
     *
     * 走 [bindTypedValue] 的 [JsonNull] 分支（`setObject(idx, null)`）而不是把字符串
     * `"NULL"` 塞进去：后者在不同驱动下分别会被存成字面量 `"NULL"` 或真 NULL，
     * 取决于驱动对 `setString` 的处理，行为不可预期。
     *
     * 列名比对忽略大小写，与 [loadColumnTypes] 的 `columnTypes[name.lowercase()]` 口径保持一致 ——
     * H2 回的是大写列名，不统一口径就会判不出 `null_columns`。
     *
     * [rawValue] 为 null 表示该列**只**出现在 `null_columns` 里；但只要它出现在
     * `nullColumns` 中就绑 NULL —— 与它在 map 里出现与否无关，两处同时出现时
     * 以 `null_columns` 为准（它是显式声明，优先级高于 map 里的占位值）。
     */
    private fun bindColumnValue(
        stmt: PreparedStatement,
        paramIndex: Int,
        sqlType: String?,
        column: String,
        rawValue: String?,
        nullColumns: List<String>,
    ) {
        if (nullColumns.any { it.equals(column, ignoreCase = true) }) {
            bindTypedValue(stmt, paramIndex, sqlType, JsonNull)
        } else {
            bindTypedValue(stmt, paramIndex, sqlType, JsonPrimitive(rawValue ?: ""))
        }
    }

    suspend fun create(
        config: ConnectionConfig,
        req: DataCreateRequest,
        sessionId: String = "",
    ): DataCreateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val schema = req.schema
            val dialect = DialectLoader.getDialect(config.driver)

            withBoundConnection(config, schema, sessionId) { conn ->
                val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

                val nullColumns = req.nullColumnsList
                val columns = targetColumns(req.valuesMap, nullColumns)
                val columnsSql = columns.joinToString(", ") { dialect.quoteIdentifier(it) }
                val placeholders = columns.joinToString(", ") { "?" }
                val sql = "INSERT INTO ${dialect.quoteIdentifier(req.tableName)} ($columnsSql) VALUES ($placeholders)"

                conn.prepareStatement(sql).use { stmt ->
                    columns.forEachIndexed { index, name ->
                        bindColumnValue(
                            stmt,
                            index + 1,
                            columnTypes[name.lowercase()],
                            name,
                            req.valuesMap[name],
                            nullColumns,
                        )
                    }
                    val affectedRows = stmt.executeUpdate()
                    dataCreateResponse { this.affectedRows = affectedRows }
                }
            }
        }

    suspend fun update(
        config: ConnectionConfig,
        req: DataUpdateRequest,
        sessionId: String = "",
    ): DataUpdateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val schema = req.schema
            val dialect = DialectLoader.getDialect(config.driver)

            withBoundConnection(config, schema, sessionId) { conn ->
                val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

                val nullColumns = req.nullColumnsList
                val columns = targetColumns(req.changesMap, nullColumns)
                val setClause = columns.joinToString(", ") { "${dialect.quoteIdentifier(it)} = ?" }
                val whereClause = req.whereMap.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
                val sql = "UPDATE ${dialect.quoteIdentifier(req.tableName)} SET $setClause WHERE $whereClause"

                conn.prepareStatement(sql).use { stmt ->
                    var paramIndex = 1
                    columns.forEach { name ->
                        bindColumnValue(
                            stmt,
                            paramIndex++,
                            columnTypes[name.lowercase()],
                            name,
                            req.changesMap[name],
                            nullColumns,
                        )
                    }
                    req.whereMap.forEach { (name, value) ->
                        bindTypedValue(stmt, paramIndex++, columnTypes[name.lowercase()], JsonPrimitive(value))
                    }
                    val affectedRows = stmt.executeUpdate()
                    dataUpdateResponse { this.affectedRows = affectedRows }
                }
            }
        }

    suspend fun delete(
        config: ConnectionConfig,
        req: DataDeleteRequest,
        sessionId: String = "",
    ): DataDeleteResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val schema = req.schema
            val dialect = DialectLoader.getDialect(config.driver)

            withBoundConnection(config, schema, sessionId) { conn ->
                val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

                val whereClause = req.whereMap.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
                val sql = "DELETE FROM ${dialect.quoteIdentifier(req.tableName)} WHERE $whereClause"

                conn.prepareStatement(sql).use { stmt ->
                    req.whereMap.entries.forEachIndexed { index, (name, value) ->
                        bindTypedValue(stmt, index + 1, columnTypes[name.lowercase()], JsonPrimitive(value))
                    }
                    val affectedRows = stmt.executeUpdate()
                    dataDeleteResponse { this.affectedRows = affectedRows }
                }
            }
        }

    /**
     * 借连接执行，并在**无事务会话时**归还连接池。
     *
     * 会话连接的生死由 `SYSTEM.COMMIT` / `ROLLBACK` 决定 —— 在这里 `close()` 会把连接还回
     * HikariCP，后续语句将落到另一条物理连接上，事务语义直接失效。
     *
     * `internal` 而不是 `private`：表结构变更（[TableHandler.create] / `update` / `delete`
     * 与 `ForeignKeyHandler.create` / `delete`）要的是**同一套**借用与归还语义 ——
     * 那几处各写一遍「记得别关连接」的 try/finally 的话,漏一处就是一个静默的事务失效,
     * 而且从代码上完全看不出来。
     *
     * [block] 是 **suspend** 的：方言 SPI 里的 `addForeignKey` / `dropForeignKey` 要在
     * 库之间发往返,写死成普通函数就得在连接借出的作用域里 `runBlocking` —— 那会在
     * IO 线程上再堵一个线程,连接池一满就自锁。
     */
    internal suspend fun <T> withBoundConnection(
        config: ConnectionConfig,
        schema: String,
        sessionId: String,
        block: suspend (java.sql.Connection) -> T,
    ): T {
        val conn = PoolManager.getConnection(config, schema, sessionId)
        return try {
            block(conn)
        } finally {
            if (sessionId.isBlank()) conn.close()
        }
    }

    /**
     * 通过方言的 listColumns SPI 获取表的列类型映射（columnName -> TYPE_NAME）。
     * 带缓存 — 高频 create/update/delete 调用避免每次都查元数据。
     * TableHandler 应在 create/update/delete 后调用 [invalidateColumnTypeCache] 失效。
     */
    private fun loadColumnTypes(
        conn: java.sql.Connection,
        driver: String,
        database: String?,
        schema: String,
        tableName: String,
    ): Map<String, String> {
        val cacheKey = columnTypeCacheKey(driver, database, schema, tableName)
        columnTypeCache[cacheKey]?.let { return it }

        val types =
            try {
                val dialect =
                    com.langbiantianya.loader.DialectLoader
                        .getDialect(driver)
                val cols =
                    kotlinx.coroutines.runBlocking {
                        dialect.listColumns(conn, database ?: "", schema, tableName)
                    }
                cols
                    .mapNotNull { col ->
                        val name = col["name"] as? String ?: return@mapNotNull null
                        val type = col["type"] as? String ?: return@mapNotNull null
                        name.lowercase() to type
                    }.toMap()
            } catch (e: Exception) {
                // 不能确定列类型时,绑定回退到 setString() — 但需记录,避免静默丢错
                // (可能错类型写入 — 列类型探测失败时记录以便诊断)
                logger.warn("loadColumnTypes failed for driver=$driver table=$tableName: ${e.message}", e)
                emptyMap()
            }
        columnTypeCache[cacheKey] = types
        return types
    }

    private fun columnTypeCacheKey(
        driver: String,
        database: String?,
        schema: String,
        table: String,
    ): String = "$driver|${database ?: ""}|$schema|$table"

    /**
     * 失效表的列类型缓存 — 由 TableHandler 在 CREATE/ADD_COLUMN/DROP_COLUMN/MODIFY_COLUMN/RENAME/DROP/TRUNCATE 后调用。
     */
    fun invalidateColumnTypeCache(
        driver: String,
        database: String?,
        schema: String,
        table: String,
    ) {
        columnTypeCache.remove(columnTypeCacheKey(driver, database, schema, table))
    }

    private fun bindTemporal(
        stmt: PreparedStatement,
        paramIndex: Int,
        raw: String,
        sqlType: Int,
    ) {
        try {
            when (sqlType) {
                Types.TIMESTAMP_WITH_TIMEZONE -> {
                    stmt.setObject(
                        paramIndex,
                        OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        sqlType,
                    )
                }

                Types.TIME_WITH_TIMEZONE -> {
                    stmt.setObject(
                        paramIndex,
                        OffsetTime.parse(raw),
                        sqlType,
                    )
                }

                else -> {
                    stmt.setObject(paramIndex, raw, sqlType)
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Cannot bind '$raw' as SQL type $sqlType for parameter $paramIndex: ${e.message}",
                e,
            )
        }
    }

    private fun bindTypedValue(
        stmt: PreparedStatement,
        paramIndex: Int,
        sqlType: String?,
        value: JsonElement,
    ) {
        if (value is JsonNull) {
            stmt.setObject(paramIndex, null)
            return
        }
        val raw = value.jsonPrimitive.content
        val type = sqlType?.uppercase()

        when (type) {
            "TINYINT", "SMALLINT", "INT2", "INT", "MEDIUMINT", "INT4", "INTEGER",
            "YEAR", "BIGINT", "INT8", "SERIAL", "BIGSERIAL", "SMALLSERIAL",
            -> {
                try {
                    stmt.setLong(paramIndex, raw.toLong())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }

            "FLOAT", "REAL", "FLOAT4", "DOUBLE", "DOUBLE PRECISION", "FLOAT8",
            "NUMERIC", "DECIMAL", "MONEY",
            -> {
                try {
                    stmt.setDouble(paramIndex, raw.toDouble())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }

            "BOOL", "BOOLEAN", "BIT" -> {
                val v = raw.trim().lowercase()
                when (v) {
                    "true", "1", "t", "yes", "y" -> stmt.setBoolean(paramIndex, true)
                    "false", "0", "f", "no", "n" -> stmt.setBoolean(paramIndex, false)
                    else -> stmt.setString(paramIndex, raw)
                }
            }

            "DATE" -> {
                bindTemporal(stmt, paramIndex, raw, Types.DATE)
            }

            "TIME" -> {
                bindTemporal(stmt, paramIndex, raw, Types.TIME)
            }

            "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> {
                bindTemporal(
                    stmt,
                    paramIndex,
                    raw,
                    Types.TIMESTAMP,
                )
            }

            "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> {
                bindTemporal(
                    stmt,
                    paramIndex,
                    raw,
                    Types.TIMESTAMP_WITH_TIMEZONE,
                )
            }

            "TIMETZ", "TIME WITH TIME ZONE" -> {
                bindTemporal(
                    stmt,
                    paramIndex,
                    raw,
                    Types.TIME_WITH_TIMEZONE,
                )
            }

            "BLOB", "BINARY", "VARBINARY", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "BYTEA" -> {
                stmt.setBytes(paramIndex, raw.toByteArray(Charsets.UTF_8))
            }

            else -> {
                stmt.setString(paramIndex, raw)
            }
        }
    }
}
