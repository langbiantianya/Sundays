/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.TableColumnListRequest
import com.langbiantianya.grpc.TableColumnListResponse
import com.langbiantianya.grpc.TableCreateRequest
import com.langbiantianya.grpc.TableCreateResponse
import com.langbiantianya.grpc.TableDeleteRequest
import com.langbiantianya.grpc.TableDeleteResponse
import com.langbiantianya.grpc.TableGetDdlRequest
import com.langbiantianya.grpc.TableGetDdlResponse
import com.langbiantianya.grpc.TableListRequest
import com.langbiantianya.grpc.TableListResponse
import com.langbiantianya.grpc.TableRenameRequest
import com.langbiantianya.grpc.TableRenameResponse
import com.langbiantianya.grpc.TableTruncateRequest
import com.langbiantianya.grpc.TableTruncateResponse
import com.langbiantianya.grpc.TableUpdateRequest
import com.langbiantianya.grpc.TableUpdateResponse
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.tableColumnListResponse
import com.langbiantianya.grpc.tableCreateResponse
import com.langbiantianya.grpc.tableDeleteResponse
import com.langbiantianya.grpc.tableGetDdlResponse
import com.langbiantianya.grpc.tableListItem
import com.langbiantianya.grpc.tableListResponse
import com.langbiantianya.grpc.tableRenameResponse
import com.langbiantianya.grpc.tableTruncateResponse
import com.langbiantianya.grpc.tableUpdateResponse
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表结构元数据 Handler —— `TABLE` category 的 7 个 action。
 *
 * 表层只做三件事：**校验必填参数**、**把 SQL 拼装交给方言 SPI**、**维护列类型缓存**。
 * 真正的方言差异（DDL 语法、标识符引用、`GET_DDL` 的重建策略）全在 `DatabaseDialect` 里，
 * 这里看不到方言分支。
 *
 * ## 三个跨 action 的不变量
 *
 * 1. **建表 / 改表 / 删表 / 重命名 / 清空后都要 [DataHandler.invalidateColumnTypeCache]。**
 *    `DATA.*` 的参数绑定靠这个缓存决定 `setLong` / `setString` / `setBytes`，
 *    漏掉一次失效就会把新列按旧类型绑定进去。
 * 2. **每个 action 都在 `withContext(Dispatchers.IO)` 里借连接并 `use {}` 归还**，
 *    因此**不接受 `session_id`** —— 表结构变更不参与事务会话。
 * 3. **SQL 片段校验由方言做**（`validateSqlFragment` / `validateOrderBy`），
 *    本层不重复实现一份关键词黑名单。
 */
object TableHandler {
    /**
     * LIST —— 列出表（`schema` 可选，PostgreSQL / H2 有效，MySQL 忽略）。
     *
     * 走 `information_schema`（方言内部），因此天然跨库 —— 这也是
     * “表列得出来、点开却报 `Table 'x.y' doesn't exist`” 这个缺陷的指纹：
     * 能列出来不代表会话当前库就是用户在树上点的那一个。
     */
    suspend fun list(
        config: ConnectionConfig,
        req: TableListRequest,
    ): TableListResponse =
        withContext(Dispatchers.IO) {
            val schema = req.schema
            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val tables = dialect.listTables(conn, config.database, schema)
                tableListResponse {
                    tables.forEach { row ->
                        this.items +=
                            tableListItem {
                                name = row["name"] ?: ""
                                type = row["type"] ?: "TABLE"
                            }
                    }
                }
            }
        }

    /**
     * LIST 的列分支 —— 请求带了 `columnList` 时由 dispatcher 路由到这里（同一个 `Action.LIST`）。
     *
     * 返回每个列的 name / type / size / nullable / isPrimaryKey / defaultValue / autoIncrement。
     */
    suspend fun columnList(
        config: ConnectionConfig,
        req: TableColumnListRequest,
    ): TableColumnListResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName' in payload")
            val schema = req.schema
            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val cols = dialect.listColumns(conn, config.database, schema, req.tableName)
                tableColumnListResponse {
                    cols.forEach { col -> this.items += buildColumnDef(col) }
                }
            }
        }

    /**
     * 把方言返回的 `Map<String, Any?>` 列描述转成 typed `ColumnDef`。
     *
     * `nullable` / `defaultValue` 用**有则设、无则不设**的方式写入：它们在 proto3 里带
     * presence（`optional` / 字符串非空判定），无条件写会把「方言没给这个信息」和
     * 「方言说它是 null」混成同一个值，调用方无法区分。
     */
    private fun buildColumnDef(col: Map<String, Any?>) =
        columnDef {
            name = col["name"]?.toString() ?: ""
            type = col["type"]?.toString() ?: ""
            size = (col["size"] as? Number)?.toInt() ?: 0
            isPrimaryKey = col["isPrimaryKey"] as? Boolean ?: false
            autoIncrement = col["autoIncrement"] as? Boolean ?: false
            // `nullable` is `optional` in proto3; preserve presence
            val nullable = col["nullable"] as? Boolean
            if (nullable != null) this.nullable = nullable
            val defaultValue = col["defaultValue"]?.toString()
            if (defaultValue != null) this.defaultValue = defaultValue
        }

    /**
     * CREATE —— 建表。`tableName` + `columns[]` 必填。
     *
     * ## 自增主键为什么可能不追加表级 `PRIMARY KEY`
     *
     * 方言在处理自增列时已经**内联**输出了主键（SQLite 的 `INTEGER PRIMARY KEY AUTOINCREMENT`、
     * DuckDB 的 `SEQUENCE + DEFAULT nextval`），此时再追加 `PRIMARY KEY (cols)` 会得到
     * “more than one primary key”。因此只要存在 `isPrimaryKey && autoIncrement` 的列，
     * 就以方言的内联形式为准。
     *
     * `options` 由方言翻译（MySQL `engine`/`charset`/`collate`/`comment`；
     * PostgreSQL 只认 `comment`，走 `COMMENT ON TABLE`）。
     *
     * ## [sessionId]：把建表并进调用方的 DDL 批次
     *
     * 一次「编辑表结构」通常是「建表 + 若干条外键」这种组合。逐条自动提交的话,
     * 外键那条失败就留下一张**没有外键的表**——而用户在界面上看到的可能是「建表也失败了」。
     * 带 [sessionId] 时整批落进同一个事务（见 [DataHandler.withBoundConnection]）。
     *
     * ## ⚠️ DDL 能不能真回滚,取决于方言,这里保证不了
     *
     * `autocommit=false` 只关掉自动提交,**能不能回滚 DDL 是数据库自己的事**：
     * PostgreSQL / H2 支持事务性 DDL,ROLLBACK 真能把 `CREATE TABLE` 撤掉;
     * MySQL 对 DDL 隐式提交（`DialectCapability.DDL_TRANSACTION` 在那里是「软支持」,见
     * `MySQLDialect` 的 KDoc）,DuckDB / SQLite 则根本不声明这个能力。
     * 所以「传了 sessionId」≠「原子」,别把它当成后者的保证。
     */
    suspend fun create(
        config: ConnectionConfig,
        req: TableCreateRequest,
        sessionId: String = "",
    ): TableCreateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            if (req.columnsList.isEmpty()) throw IllegalArgumentException("Missing 'columns' array")
            val schema = req.schema

            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext DataHandler.withBoundConnection(config, schema, sessionId) { conn ->
                val columnDefs =
                    req.columnsList.map { col ->
                        val name = col.name
                        if (name.isBlank()) throw IllegalArgumentException("Column missing 'name'")
                        val type = col.type
                        if (type.isBlank()) throw IllegalArgumentException("Column missing 'type'")
                        val size = if (col.size == 0) null else col.size
                        val nullable = if (col.hasNullable()) col.nullable else true
                        val isPrimaryKey = col.isPrimaryKey
                        val defaultValue = col.defaultValue.ifBlank { null }
                        val autoIncrement = col.autoIncrement

                        dialect.buildColumnDefinition(name, type, size, nullable, isPrimaryKey, defaultValue, autoIncrement)
                    }

                val primaryKeys =
                    req.columnsList.mapNotNull { col ->
                        if (col.isPrimaryKey) col.name else null
                    }
                val autoIncrementColumns =
                    req.columnsList.mapNotNull { col ->
                        if (col.isPrimaryKey && col.autoIncrement) col.name else null
                    }
                // SQLite / DuckDB 等方言在 autoIncrement 主键的列定义里已 inline 输出 PRIMARY KEY，
                // 此时不能再追加表级 PRIMARY KEY (会触发 "more than one primary key")。
                val dialectEmitsInlinePK = autoIncrementColumns.isNotEmpty()

                for (stmt in dialect.buildPreCreateStatements(dialect.quoteIdentifier(req.tableName), autoIncrementColumns)) {
                    conn.createStatement().use { it.execute(stmt) }
                }

                val sql =
                    buildString {
                        append("CREATE TABLE ")
                        if (req.hasIfNotExists() && req.ifNotExists) append("IF NOT EXISTS ")
                        append(dialect.quoteIdentifier(req.tableName))
                        append(" (")
                        append(columnDefs.joinToString(", "))
                        if (primaryKeys.isNotEmpty() && !dialectEmitsInlinePK) {
                            append(", PRIMARY KEY (")
                            append(primaryKeys.joinToString(", ") { dialect.quoteIdentifier(it) })
                            append(")")
                        }
                        append(")")
                        append(dialect.buildTableOptionsSQL(req.optionsMap))
                    }

                conn.createStatement().use { it.execute(sql) }

                for (stmt in dialect.buildPostCreateStatements(dialect.quoteIdentifier(req.tableName), req.optionsMap)) {
                    conn.createStatement().use { it.execute(stmt) }
                }

                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
                tableCreateResponse { created = req.tableName }
            }
        }

    /**
     * UPDATE —— `operation` 必须是 `ADD_COLUMN` / `DROP_COLUMN` / `MODIFY_COLUMN`，否则抛
     * `IllegalArgumentException("Unknown operation: …")`。
     *
     * `MODIFY_COLUMN` 的契约：`column.name` 必填，且 `type` 与 `newName` **至少有其一** ——
     * 两者都空就等于什么也没改，直接拒绝比让方言猜要好。
     *
     * **能力按方言不同**：SQLite 只支持 RENAME（无 `ALTER COLUMN`），
     * DuckDB / SQLite 改约束类操作走 table-rebuild，都由方言 SPI 自己判断。
     *
     * ## 为什么 [sessionId] 在这里最要紧
     *
     * 一次「保存表结构」会算出**一串** `ADD_COLUMN` / `DROP_COLUMN` / `MODIFY_COLUMN`。
     * 逐条自动提交的话,用户「删两列、加三列」中途撞上一次类型不兼容,
     * 库里就停在「删了一列、加了一列」的半截结构——而界面上他只看到一句报错。
     *
     * 带 [sessionId] 时整串落进同一个事务；能否真回滚仍取决于方言,见 [create] 的说明。
     */
    suspend fun update(
        config: ConnectionConfig,
        req: TableUpdateRequest,
        sessionId: String = "",
    ): TableUpdateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            if (req.operation.isBlank()) throw IllegalArgumentException("Missing 'operation' (ADD_COLUMN|DROP_COLUMN|MODIFY_COLUMN)")
            val schema = req.schema

            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext DataHandler.withBoundConnection(config, schema, sessionId) { conn ->
                val sql =
                    when (req.operation) {
                        "ADD_COLUMN" -> {
                            val column = req.column
                            if (column.name.isBlank()) throw IllegalArgumentException("Missing 'column' for ADD_COLUMN")
                            val size = if (column.size == 0) null else column.size
                            val nullable = if (column.hasNullable()) column.nullable else true
                            val defaultValue = column.defaultValue.ifBlank { null }
                            val colDef = dialect.buildColumnDefinition(column.name, column.type, size, nullable, false, defaultValue)
                            dialect.buildAddColumnSQL(req.tableName, colDef)
                        }

                        "DROP_COLUMN" -> {
                            if (req.columnName.isBlank()) throw IllegalArgumentException("Missing 'columnName' for DROP_COLUMN")
                            dialect.buildDropColumnSQL(req.tableName, req.columnName)
                        }

                        "MODIFY_COLUMN" -> {
                            val column = req.column
                            if (column.name.isBlank()) throw IllegalArgumentException("Missing 'column' for MODIFY_COLUMN")
                            val type = column.type.ifBlank { null }
                            val size = if (column.size == 0) null else column.size
                            val nullable = if (column.hasNullable()) column.nullable else true
                            val defaultValue = column.defaultValue.ifBlank { null }
                            val newName = column.newName.ifBlank { null }
                            if (type == null && newName == null) {
                                throw IllegalArgumentException("Column requires 'type' or 'newName'")
                            }
                            dialect.buildModifyColumnSQL(req.tableName, column.name, type, size, nullable, defaultValue, newName)
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown operation: ${req.operation}")
                        }
                    }

                conn.createStatement().use { it.execute(sql) }
                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
                tableUpdateResponse {
                    tableName = req.tableName
                    operation = req.operation
                }
            }
        }

    /**
     * GET_DDL —— 返回建表语句原文（字符串）。
     *
     * 取法按方言不同，是**引擎层契约**而不是偶然行为：MySQL 用 `SHOW CREATE TABLE`；
     * PostgreSQL 从 `information_schema` + `pg_catalog` 重建（主键 / UNIQUE / CHECK / 索引）；
     * H2 按列重建并用 `TABLE_CONSTRAINTS` 过滤同名系统表；
     * SQLite / DuckDB 直接读 `sqlite_master` / `duckdb` 内部目录里的原文。
     * 只有「读原文」的那些方言能保证给出用户当时写的那份 DDL。
     */
    suspend fun getDDL(
        config: ConnectionConfig,
        req: TableGetDdlRequest,
    ): TableGetDdlResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName' in payload")
            val schema = req.schema
            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)
            return@withContext connection.use { conn ->
                tableGetDdlResponse { ddl = dialect.getCreateTableDDL(conn, req.tableName) }
            }
        }

    /** DELETE —— `if_exists` 未设置时按「不存在就报错」处理，不做静默忽略。 */
    suspend fun delete(
        config: ConnectionConfig,
        req: TableDeleteRequest,
        sessionId: String = "",
    ): TableDeleteResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val schema = req.schema

            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext DataHandler.withBoundConnection(config, schema, sessionId) { conn ->
                val ifClause = if (req.hasIfExists() && req.ifExists) "IF EXISTS " else ""
                val sql = "DROP TABLE ${ifClause}${dialect.quoteIdentifier(req.tableName)}"
                conn.createStatement().use { it.execute(sql) }
                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
                tableDeleteResponse { deleted = req.tableName }
            }
        }

    /**
     * RENAME —— `oldName` 留空时回退读 `tableName`（两个字段都空才拒绝）。
     *
     * 新旧两个名字都要失效列类型缓存：缓存 key 里带表名，只清一个会在另一半上留下脏数据。
     */
    suspend fun rename(
        config: ConnectionConfig,
        req: TableRenameRequest,
    ): TableRenameResponse =
        withContext(Dispatchers.IO) {
            val oldName =
                req.oldName.ifBlank {
                    req.tableName.ifBlank {
                        throw IllegalArgumentException("Missing 'oldName' (or 'tableName')")
                    }
                }
            if (req.newName.isBlank()) throw IllegalArgumentException("Missing 'newName'")
            val schema = req.schema

            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)
            return@withContext connection.use { conn ->
                dialect.renameTable(conn, oldName, req.newName)
                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, oldName)
                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.newName)
                tableRenameResponse {
                    renamed = oldName
                    newName = req.newName
                }
            }
        }

    /**
     * TRUNCATE —— 委托方言 SPI `truncateTable`。
     *
     * SQLite 没有 `TRUNCATE`，方言用 `DELETE FROM` + 清 `sqlite_sequence` 模拟；
     * MySQL 走真正的 `TRUNCATE TABLE`（会隐式提交）。差异全部收敛在方言里。
     */
    suspend fun truncate(
        config: ConnectionConfig,
        req: TableTruncateRequest,
    ): TableTruncateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
            val schema = req.schema

            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                dialect.truncateTable(conn, req.tableName)
                DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
                tableTruncateResponse { truncated = req.tableName }
            }
        }
}
