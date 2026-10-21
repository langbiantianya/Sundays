/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect

import com.langbiantianya.dialect.contract.ConnectionFormSpec
import com.langbiantianya.dialect.contract.ConnectionFormTypes
import com.langbiantianya.dialect.contract.StandardForms
import com.langbiantianya.i18n.StrKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * H2 方言 —— 嵌入式/进程内数据库，也是引擎**集成测试的主载体方言**
 * （方言级测试 + engine 端到端集成测试都跑在 H2 上）。
 *
 * ## URL 约定：`jdbc:h2:mem:<database>;DB_CLOSE_DELAY=-1`
 *
 * - `mem:<database>` —— 进程内内存数据库；database 以 `/path/data.mv.db` 形式给出时
 *   切到 H2 文件持久化
 * - `DB_CLOSE_DELAY=-1` —— **最后一个连接关闭后数据库仍存活到 JVM 退出**，
 *   这样连接池关掉重建后数据还在，测试才能断言「写进去的东西还在」
 * - host/port 在 H2 场景下无意义，一律忽略
 *
 * ## 为什么没有 `TRIGGERS` capability
 *
 * H2 2.3.232 的 `INFORMATION_SCHEMA.TRIGGERS` 表存在，但 schema 与列名跟其他方言
 * 差异较大，`DatabaseDialect` 的默认 `UnsupportedOperationException` 实现已经覆盖了
 * 这个语义；真要用触发器，生产场景走 MySQL / PostgreSQL 即可，不必在这里硬接。
 *
 * ## 两条约束
 *
 * - **流式查询与 PostgreSQL 相同** —— 必须临时关掉 `autoCommit`，H2 不认
 *   `setFetchSize(Integer.MIN_VALUE)` 那条服务端游标约定。
 * - **User 概念差异大。** H2 的 USER 表与其他方言结构不同，引擎级集成测试不依赖
 *   USER CRUD；`supportsUser` / `supportsPassword` 因此为 `false`。
 *
 * ## `DDL_TRANSACTION` 是**软支持**：H2 对 DDL 隐式提交
 *
 * 声明了这个能力，意思却是「批量改表结构中途失败时，已生效的部分会留在库里」。
 * 这是实测出来的，不是照文档抄的：`autoCommit = false` 的连接上执行
 * `CREATE TABLE`，**在任何 commit / rollback 之前**另借一条连接就查得到这张表，
 * 说明它压根没进事务。回归见
 * `DdlTransactionIntegrationTest.H2 does not roll DDL back`（那条用例故意断言
 * 「回滚后表还在」—— 记录的是**已知限制**，不是期望行为）。
 *
 * 界面上因此**不能**对 H2 用户承诺「这次改表结构要么全成、要么什么都没发生」。
 */
class H2Dialect : DatabaseDialect {
    private val logger = LoggerFactory.getLogger(H2Dialect::class.java)
    override val driverName = "H2"
    override val jdbcDriverClassName = "org.h2.Driver"
    override val jdbcUrlPrefix = "jdbc:h2:"

    // v2.8: 前端连接表单元数据
    override val displayName = "H2 (In-Memory)"
    override val connectionType = ConnectionType.IN_MEMORY
    override val requiresHost = false
    override val requiresPort = false
    override val supportsUser = false
    override val supportsPassword = false
    override val supportsSchema = true
    override val supportsCrossDatabase = true
    override val jdbcUrlExample = "jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1"

    /**
     * 连接表单规格 —— 前端按它渲染凭据页。
     *
     * H2 有**两种**连接类型，表单形状不同，所以声明**两份** form：
     *
     * - `IN_MEMORY` —— 填库名（`jdbc:h2:mem:<db>`）
     * - `FILE_BASED` —— 填文件路径（`jdbc:h2:file:<path>`，H2 自己补 `.mv.db`）
     *
     * ⚠️ 内存库的字段标签过去用的是一句**硬编码中文**（「数据库名称」），
     * 因为文案表里当时只有 `DATABASE`（「数据库」）而没有完全对应的那句。
     * 现在改用 `StrKey.DATABASE_NAME`（「数据库名」）—— 顺手去掉一处漏出，
     * 代价是那一句的措辞从「数据库名称」变成「数据库名」。
     */
    override val connectionForm =
        ConnectionFormSpec(
            forms =
                listOf(
                    StandardForms.embedded(
                        connectionType = ConnectionFormTypes.IN_MEMORY,
                        labelKey = StrKey.DATABASE_NAME,
                        placeholderKey = StrKey.DATABASE_NAME_HINT,
                    ),
                    StandardForms.embedded(
                        connectionType = ConnectionFormTypes.FILE_BASED,
                        labelKey = StrKey.DATABASE_FILE_PATH,
                        placeholderKey = StrKey.DATABASE_FILE_PATH_HINT_H2,
                    ),
                ),
        )

    override val capabilities =
        setOf(
            DialectCapability.USERS,
            DialectCapability.PRIVILEGES,
            DialectCapability.ROUTINES,
            DialectCapability.VIEWS,
            DialectCapability.INDEXES,
            DialectCapability.FOREIGN_KEYS,
            DialectCapability.MULTI_SCHEMA,
            DialectCapability.EXPORT,
            DialectCapability.DDL_TRANSACTION,
            DialectCapability.EMBEDDED_MODE,
        )

    override fun buildJdbcUrl(
        host: String,
        port: Int,
        database: String,
    ): String {
        // H2 内存数据库：host/port 忽略，database 名直接用于 `mem:<name>`
        val safeName = sanitizeIdentifier(database, "database name")
        // CASE_INSENSITIVE_IDENTIFIERS=TRUE — 让带引号的小写标识符也能匹配到默认存储的大写表/列名
        // （H2 默认对带引号的标识符大小写敏感，会导致 "users" 匹配不到 USERS）
        return "jdbc:h2:mem:$safeName;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    }

    override fun configureConnectionForStreaming(conn: Connection): Boolean {
        // H2 内存数据库无需服务端游标 — 默认即可
        return conn.autoCommit
    }

    override fun setSearchPath(
        conn: Connection,
        schema: String,
    ) {
        if (schema.isBlank()) return
        conn.createStatement().use { stmt ->
            stmt.execute("SET SCHEMA ${quoteIdentifier(schema)}")
        }
    }

    override fun buildSetSearchPathSql(schema: String): String? {
        if (schema.isBlank()) return null
        return "SET SCHEMA ${quoteIdentifier(schema)}"
    }

    companion object {
        /** ORDER BY 格式校验：标识符可用双引号包裹 （v2.9 #16 用 DialectUtil 工厂生成） */
        private val ORDER_BY_REGEX = DialectUtil.orderByRegex('"')

        /** 危险关键词（H2 增加 MERGE，v2.9 #16 用 DialectUtil 工厂） */
        private val DANGEROUS_KEYWORD_REGEXES: List<Regex> =
            DialectUtil.buildDangerousKeywordRegexes(setOf("MERGE"))

        /** 可带 size 后缀的类型 */
        private val SIZABLE_TYPES = setOf("VARCHAR", "CHAR", "VARBINARY", "BINARY")

        /** 默认值中不需要加引号的字面量 */
        private val UNQUOTED_DEFAULT_REGEX =
            Regex(
                """\d+(\.\d+)?|NULL|CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME|NOW\(\)|IDENTITY""",
                RegexOption.IGNORE_CASE,
            )
    }

    private fun sanitizeIdentifier(
        name: String,
        label: String,
    ): String {
        if (name.isBlank()) throw IllegalArgumentException("$label cannot be empty")
        // 拒绝 null 字节（0x00）— 防 SQL 截断
        if (name.contains('\u0000')) throw IllegalArgumentException("$label contains null byte")
        // 拒绝单引号 — 防 SQL 注入跳出字面量
        if (name.contains('\'')) throw IllegalArgumentException("$label contains single quote")
        // 拒绝反斜杠 — 防转义序列注入
        if (name.contains('\\')) throw IllegalArgumentException("$label contains backslash")
        // 拒绝分号 — 防语句分割
        if (name.contains(';')) throw IllegalArgumentException("$label contains semicolon")
        return name
    }

    /**
     * 字符串字面量转义（用于 DDL 中无法参数化的位置）。
     * 拒绝 null 字节、分号、换行；保留单引号转义为 \\\'
     */
    internal fun escapeSqlString(
        value: String,
        label: String,
    ): String {
        if (value.contains('\u0000')) throw IllegalArgumentException("$label contains null byte")
        if (value.contains(';')) throw IllegalArgumentException("$label contains semicolon")
        if (value.contains('\n')) throw IllegalArgumentException("$label contains newline")
        return value.replace("'", "''")
    }

    override fun quoteIdentifier(identifier: String): String {
        // 必须加引号：不加引号时 ORDER / USER 等保留字直接语法报错（SELECT * FROM ORDER → "expected identifier"），
        // 且用户以 CREATE TABLE "userProfiles" 建出的表无法用不带引号的标识符访问。
        // 引号内保持大写：H2 把未加引号的标识符折叠为大写存储，
        // executeUpdate("CREATE TABLE t") 实际建出的就是 T，"T" 才能命中它。
        return DialectUtil.quoteWith(identifier.uppercase(), '"')
    }

    // region Schema / Table / User 管理

    override suspend fun listDatabases(conn: Connection): List<String> =
        withContext(Dispatchers.IO) {
            // H2 单实例：连接的 catalog 即为该实例的 database 名
            // metaData.databaseProductName + url 解析：更简单是用 conn.catalog
            val db = conn.catalog
            if (db.isNullOrBlank()) {
                // 单连接未 catalog 时回退空列表
                return@withContext emptyList()
            }
            return@withContext listOf(db)
        }

    override suspend fun listSchemas(
        conn: Connection,
        database: String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            // H2 没有跨库概念，但接受 database 参数用于 assert 连接 catalog 一致
            if (database.isNotBlank()) {
                val currentCatalog = conn.catalog
                if (!currentCatalog.isNullOrBlank() &&
                    !currentCatalog.equals(database, ignoreCase = true)
                ) {
                    logger.warn(
                        "listSchemas called with database='$database' but connection catalog is '$currentCatalog'",
                    )
                }
            }
            val schemas = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                stmt
                    .executeQuery(
                        "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
                            "WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA', 'SYSTEM_LOBS') " +
                            "ORDER BY SCHEMA_NAME",
                    ).use { rs ->
                        while (rs.next()) schemas.add(rs.getString(1))
                    }
            }
            schemas
        }

    /**
     * H2 专用实现 — 通过 INFORMATION_SCHEMA.COLUMNS + INFORMATION_SCHEMA.KEY_COLUMN_USAGE 查询，
     * 不依赖 JDBC metaData 的 case-sensitive lookup（metaData 在 H2 上对小写 unquoted 表名会返回空）。
     *
     * 注：H2 INFORMATION_SCHEMA 中列名存储为大写；CASE_INSENSITIVE_IDENTIFIERS=TRUE 让查询条件
     * 大小写不敏感，但返回的列名仍为大写 — 故对结果列名也做大写化以便统一匹配。
     */
    override suspend fun listColumns(
        conn: Connection,
        database: String,
        schema: String,
        tableName: String,
    ): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            // schema 过滤：若调用方传了 schema 才用，否则不限定 schema（H2 默认 schema 可能是 PUBLIC/SA 等）
            val targetSchema = schema.ifBlank { "" }
            val result = mutableListOf<Map<String, Any?>>()

            // 1. 主键列表 — JOIN CONSTRAINTS → KEY_COLUMN_USAGE
            val primaryKeys = mutableSetOf<String>()
            val pkSql =
                if (targetSchema.isNotBlank()) {
                    """SELECT KU.COLUMN_NAME
               FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE KU
               JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC
                 ON KU.CONSTRAINT_NAME = TC.CONSTRAINT_NAME AND KU.CONSTRAINT_SCHEMA = TC.CONSTRAINT_SCHEMA
               WHERE TC.CONSTRAINT_TYPE = 'PRIMARY KEY'
                 AND KU.TABLE_NAME = ?
                 AND TC.TABLE_SCHEMA = ?"""
                } else {
                    """SELECT KU.COLUMN_NAME
               FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE KU
               JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC
                 ON KU.CONSTRAINT_NAME = TC.CONSTRAINT_NAME AND KU.CONSTRAINT_SCHEMA = TC.CONSTRAINT_SCHEMA
               WHERE TC.CONSTRAINT_TYPE = 'PRIMARY KEY'
                 AND KU.TABLE_NAME = ?"""
                }
            conn.prepareStatement(pkSql).use { ps ->
                ps.setString(1, tableName.uppercase())
                if (targetSchema.isNotBlank()) ps.setString(2, targetSchema.uppercase())
                ps.executeQuery().use { rs ->
                    while (rs.next()) primaryKeys.add(rs.getString(1))
                }
            }

            // 2. 列定义 — DATA_TYPE 是 VARCHAR（如 INTEGER / CHARACTER VARYING）
            // H2 默认把未加引号的标识符存为大写 — 输出的 name 也保持大写（与系统一致）
            // 排除系统 schema（INFORMATION_SCHEMA / SYSTEM_LOBS）避免误命中（如 USERS 系统表）
            val colSql =
                if (targetSchema.isNotBlank()) {
                    """SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, IS_NULLABLE, COLUMN_DEFAULT
               FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?
                 AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'SYSTEM_LOBS')
               ORDER BY ORDINAL_POSITION"""
                } else {
                    """SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, IS_NULLABLE, COLUMN_DEFAULT
               FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = ?
                 AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'SYSTEM_LOBS')
               ORDER BY ORDINAL_POSITION"""
                }
            conn.prepareStatement(colSql).use { ps ->
                ps.setString(1, tableName.uppercase())
                if (targetSchema.isNotBlank()) ps.setString(2, targetSchema.uppercase())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val dataTypeStr = rs.getString(2) ?: ""
                        val charLen = rs.getLong(3)
                        // wasNull() 只反映“最近一次”读取，必须在每次读之后立刻取，
                        // 否则 charLen 的空值标记会被后面的 numPrec 覆盖（VARCHAR 长度因此被吞成 0）
                        val charLenWasNull = rs.wasNull()
                        val numPrec = rs.getLong(4)
                        val numPrecWasNull = rs.wasNull()
                        // H2 对 BLOB / BINARY LARGE OBJECT 的 NUMERIC_PRECISION 回
                        // Long.MAX_VALUE（9223372036854775807），用 getInt 读会抛
                        // "Numeric value out of range"，整张表的列定义一条都拿不到 ——
                        // 含二进制列的表连列清单都列不出来。故按 Long 读再夹到 Int 区间。
                        val rawSize =
                            if (!charLenWasNull && charLen > 0) {
                                charLen
                            } else if (!numPrecWasNull) {
                                numPrec
                            } else {
                                0L
                            }
                        val size = rawSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                        val nullable = rs.getString(5)
                        val isPk = name in primaryKeys || name.lowercase() in primaryKeys
                        result.add(
                            mapOf(
                                "name" to name,
                                "type" to dataTypeStr,
                                "size" to size,
                                "nullable" to ("YES".equals(nullable, ignoreCase = true)),
                                "isPrimaryKey" to isPk,
                                "defaultValue" to rs.getString(6),
                            ),
                        )
                    }
                }
            }
            result
        }

    override suspend fun createSchema(
        conn: Connection,
        name: String,
        options: Map<String, String>,
        ifNotExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(name, "schema name")
            conn.createStatement().use { stmt ->
                val ifClause = if (ifNotExists) "IF NOT EXISTS " else ""
                stmt.execute("CREATE SCHEMA ${ifClause}${quoteIdentifier(safeName)}")
            }
            true
        }

    override suspend fun deleteSchema(
        conn: Connection,
        name: String,
        ifExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(name, "schema name")
            conn.createStatement().use { stmt ->
                // H2 必须使用 CASCADE 防止有对象依赖时失败
                val ifClause = if (ifExists) "IF EXISTS " else ""
                stmt.execute("DROP SCHEMA ${ifClause}${quoteIdentifier(safeName)} CASCADE")
            }
            true
        }

    override suspend fun listTables(
        conn: Connection,
        database: String,
        schema: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val tables = mutableListOf<Map<String, String>>()
            val query =
                if (schema.isNotBlank()) {
                    "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') ORDER BY TABLE_NAME"
                } else {
                    "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = CURRENT_SCHEMA AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') " +
                        "ORDER BY TABLE_NAME"
                }
            conn.prepareStatement(query).use { stmt ->
                if (schema.isNotBlank()) stmt.setString(1, schema)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tables.add(
                            mapOf(
                                "name" to rs.getString("TABLE_NAME"),
                                "type" to rs.getString("TABLE_TYPE"),
                            ),
                        )
                    }
                }
            }
            tables
        }

    override suspend fun listUsers(conn: Connection): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val users = mutableListOf<Map<String, String>>()
            conn.createStatement().use { stmt ->
                // H2 INFORMATION_SCHEMA.USERS 使用 USER_NAME 列（不是 NAME）
                stmt.executeQuery("SELECT USER_NAME FROM INFORMATION_SCHEMA.USERS ORDER BY USER_NAME").use { rs ->
                    while (rs.next()) {
                        users.add(mapOf("user" to rs.getString(1)))
                    }
                }
            }
            users
        }

    override suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean,
        tableName: String?,
        withGrantOption: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeUser = sanitizeIdentifier(user, "user name")
            val safeSchema = sanitizeIdentifier(schema, "schema name")
            val safePrivs =
                privileges.map { priv ->
                    val p = priv.trim().uppercase()
                    if (!p.matches(Regex("^[A-Z_ ]+$"))) {
                        throw IllegalArgumentException("Invalid privilege name: $p — 仅允许字母/空格/下划线")
                    }
                    p
                }
            val privList = safePrivs.joinToString(", ")
            // H2 不支持 WITH GRANT OPTION 语法（虽然有 GRANT OPTION 列在 INFORMATION_SCHEMA）
            if (withGrantOption) {
                logger.warn("H2 方言不支持 WITH GRANT OPTION 语法 — 参数被忽略")
            }
            val onTarget =
                if (tableName.isNullOrBlank()) {
                    "SCHEMA ${quoteIdentifier(safeSchema)}"
                } else {
                    "TABLE ${quoteIdentifier(safeSchema)}.${quoteIdentifier(sanitizeIdentifier(tableName, "table name"))}"
                }
            val sql =
                if (isGrant) {
                    "GRANT $privList ON $onTarget TO ${quoteIdentifier(safeUser)}"
                } else {
                    "REVOKE $privList ON $onTarget FROM ${quoteIdentifier(safeUser)}"
                }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            true
        }

    override suspend fun createUser(
        conn: Connection,
        user: String,
        password: String,
        host: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            // H2 没有 host 概念 — host 参数必须为 "%" 或空
            if (host.isNotBlank() && host != "%") {
                throw IllegalArgumentException(
                    "H2 不支持 host 参数（必须为空或 '%'，表示不限定 host）",
                )
            }
            val safeUser = sanitizeIdentifier(user, "user name")
            val escapedPwd = password.replace("'", "''")
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE USER ${quoteIdentifier(safeUser)} PASSWORD '$escapedPwd'")
            }
            true
        }

    override suspend fun deleteUser(
        conn: Connection,
        user: String,
        host: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (host.isNotBlank() && host != "%") {
                throw IllegalArgumentException("H2 不支持 host 参数")
            }
            val safeUser = sanitizeIdentifier(user, "user name")
            conn.createStatement().use { stmt ->
                stmt.execute("DROP USER ${quoteIdentifier(safeUser)}")
            }
            true
        }

    override suspend fun updatePassword(
        conn: Connection,
        user: String,
        password: String,
        host: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (host.isNotBlank() && host != "%") {
                throw IllegalArgumentException("H2 不支持 host 参数")
            }
            val safeUser = sanitizeIdentifier(user, "user name")
            val escapedPwd = password.replace("'", "''")
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER USER ${quoteIdentifier(safeUser)} SET PASSWORD '$escapedPwd'")
            }
            true
        }

    override suspend fun listPrivileges(
        conn: Connection,
        user: String,
        host: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            if (host.isNotBlank() && host != "%") {
                throw IllegalArgumentException("H2 不支持 host 参数")
            }
            val safeUser = sanitizeIdentifier(user, "user name")
            val privileges = mutableListOf<Map<String, String>>()
            conn
                .prepareStatement(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE " +
                        "FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES " +
                        "WHERE GRANTEE = ? ORDER BY TABLE_SCHEMA, TABLE_NAME, PRIVILEGE_TYPE",
                ).use { stmt ->
                    stmt.setString(1, safeUser)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            privileges.add(
                                mapOf(
                                    "schema" to rs.getString("TABLE_SCHEMA"),
                                    "table" to rs.getString("TABLE_NAME"),
                                    "privilege" to rs.getString("PRIVILEGE_TYPE"),
                                ),
                            )
                        }
                    }
                }
            privileges
        }

    override suspend fun listAllGrants(
        conn: Connection,
        user: String,
        host: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            if (host.isNotBlank() && host != "%") {
                throw IllegalArgumentException("H2 不支持 host 参数")
            }
            val safeUser = sanitizeIdentifier(user, "user name")
            val grants = mutableListOf<Map<String, String>>()
            // H2 UNION schema 级 + table 级权限
            conn
                .prepareStatement(
                    """SELECT TABLE_SCHEMA AS schema, TABLE_NAME AS objname, 'TABLE' AS objtype,
                      GROUP_CONCAT(DISTINCT PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE) AS privileges
               FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES
               WHERE GRANTEE = ?
               GROUP BY TABLE_SCHEMA, TABLE_NAME
               UNION ALL
               (SELECT TABLE_SCHEMA, NULL, 'SCHEMA',
                       GROUP_CONCAT(DISTINCT PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE)
                FROM INFORMATION_SCHEMA.SCHEMA_PRIVILEGES
                WHERE GRANTEE = ?
                GROUP BY TABLE_SCHEMA)
               ORDER BY schema, objname""",
                ).use { stmt ->
                    stmt.setString(1, safeUser)
                    stmt.setString(2, safeUser)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            grants.add(
                                mapOf(
                                    "schema" to rs.getString("schema"),
                                    "table" to rs.getString("objname"),
                                    "object_type" to rs.getString("objtype"),
                                    "privileges" to (rs.getString("privileges") ?: ""),
                                ),
                            )
                        }
                    }
                }
            grants
        }

    // endregion

    // region DDL 构建

    override fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?,
        autoIncrement: Boolean,
    ): String =
        buildString {
            append(quoteIdentifier(name))
            append(" ")
            if (autoIncrement && isPrimaryKey) {
                // H2 自增列：IDENTITY / BIGINT GENERATED BY DEFAULT AS IDENTITY
                // IDENTITY 在 H2 中是 INT 等价物；BIGINT 用 GENERATED BY DEFAULT
                val upperType = type.uppercase()
                when (upperType) {
                    "INT", "INTEGER" -> {
                        append("INT AUTO_INCREMENT")
                    }

                    "BIGINT" -> {
                        append("BIGINT AUTO_INCREMENT")
                    }

                    else -> {
                        append(buildTypeSpec(type, size))
                        append(" GENERATED BY DEFAULT AS IDENTITY")
                    }
                }
            } else {
                append(buildTypeSpec(type, size))
            }
            if (!autoIncrement && !nullable) append(" NOT NULL")
            if (defaultValue != null && !autoIncrement) {
                append(" DEFAULT ")
                append(
                    if (UNQUOTED_DEFAULT_REGEX.matches(defaultValue)) {
                        defaultValue
                    } else {
                        "'${defaultValue.replace("'", "''")}'"
                    },
                )
            }
        }

    override fun buildAddColumnSQL(
        tableName: String,
        columnDef: String,
    ): String = "ALTER TABLE ${quoteIdentifier(tableName)} ADD COLUMN $columnDef"

    override fun buildDropColumnSQL(
        tableName: String,
        columnName: String,
    ): String = "ALTER TABLE ${quoteIdentifier(tableName)} DROP COLUMN ${quoteIdentifier(columnName)}"

    /**
     * H2 的 ALTER COLUMN 是单语句：每次只能改一个属性（TYPE / SET NOT NULL / DROP NOT NULL / SET DEFAULT / RENAME TO）。
     * 这里只取 modify 第一优先属性（type > nullable > default > rename），调用方需多次调用以应用多个变更。
     */
    override fun buildModifyColumnSQL(
        tableName: String,
        name: String,
        type: String?,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?,
    ): String {
        val table = quoteIdentifier(tableName)
        val col = quoteIdentifier(name)

        // H2 单条 ALTER TABLE 只能改一属性 — 优先级：type > newName > defaultValue > nullable
        return when {
            !type.isNullOrBlank() -> {
                val typeSpec = buildTypeSpec(type, size)
                "ALTER TABLE $table ALTER COLUMN $col $typeSpec"
            }

            newName != null -> {
                "ALTER TABLE $table ALTER COLUMN $col RENAME TO ${quoteIdentifier(newName)}"
            }

            defaultValue != null -> {
                val literal =
                    if (UNQUOTED_DEFAULT_REGEX.matches(defaultValue)) {
                        defaultValue
                    } else {
                        "'${defaultValue.replace("'", "''")}'"
                    }
                "ALTER TABLE $table ALTER COLUMN $col SET DEFAULT $literal"
            }

            !nullable -> {
                "ALTER TABLE $table ALTER COLUMN $col SET NOT NULL"
            }

            nullable -> {
                "ALTER TABLE $table ALTER COLUMN $col DROP NOT NULL"
            }

            else -> {
                throw IllegalArgumentException("MODIFY_COLUMN 调用缺少任何修改属性")
            }
        }
    }

    override suspend fun getCreateTableDDL(
        conn: Connection,
        tableName: String,
    ): String =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            val targetSchema = "PUBLIC" // H2 测试场景下用户表都在 PUBLIC；INFORMATION_SCHEMA.USERS 等系统表也在该 schema，需过滤

            // 1. 查询列定义 — 显式按 TABLE_SCHEMA = 'PUBLIC' 过滤，避免命中 INFORMATION_SCHEMA.USERS 等同名系统表
            val columns = mutableListOf<String>()
            conn
                .prepareStatement(
                    "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT, " +
                        "NUMERIC_PRECISION, NUMERIC_SCALE " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ? ORDER BY ORDINAL_POSITION",
                ).use { stmt ->
                    stmt.setString(1, safeTable)
                    stmt.setString(2, targetSchema)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val colName = rs.getString("COLUMN_NAME")
                            val dataType = rs.getString("DATA_TYPE") ?: ""
                            val charLen = rs.getInt("CHARACTER_MAXIMUM_LENGTH")
                            val charLenWasNull = rs.wasNull()
                            val numPrec = rs.getInt("NUMERIC_PRECISION")
                            val numPrecWasNull = rs.wasNull()
                            val numScale = rs.getInt("NUMERIC_SCALE")
                            val numScaleWasNull = rs.wasNull()
                            val nullable = rs.getString("IS_NULLABLE") == "YES"
                            val defaultVal = rs.getString("COLUMN_DEFAULT")

                            val typeSpec =
                                when (dataType.uppercase()) {
                                    "CHARACTER VARYING" -> if (!charLenWasNull && charLen > 0) "VARCHAR($charLen)" else "VARCHAR"
                                    "CHARACTER" -> if (!charLenWasNull && charLen > 0) "CHAR($charLen)" else "CHAR"
                                    "DECIMAL", "NUMERIC" -> if (!numPrecWasNull && numPrec > 0) "DECIMAL($numPrec, ${if (numScaleWasNull) 0 else numScale})" else "DECIMAL"
                                    "INTEGER" -> "INT"
                                    else -> dataType
                                }

                            buildString {
                                append("${quoteIdentifier(colName)} $typeSpec")
                                if (!nullable) append(" NOT NULL")
                                if (defaultVal != null) append(" DEFAULT $defaultVal")
                            }.let { columns.add(it) }
                        }
                    }
                }

            if (columns.isEmpty()) {
                throw IllegalArgumentException("未找到表 '$safeTable' 或其无可见列")
            }

            // 2. 查询主键（H2 无 INDEX_COLUMNS.PRIMARY_KEY 列，改用 TABLE_CONSTRAINTS）
            val primaryKeys = mutableListOf<String>()
            conn
                .prepareStatement(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ? AND CONSTRAINT_NAME = " +
                        "(SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ? AND CONSTRAINT_TYPE = 'PRIMARY KEY')",
                ).use { stmt ->
                    stmt.setString(1, safeTable)
                    stmt.setString(2, targetSchema)
                    stmt.setString(3, safeTable)
                    stmt.setString(4, targetSchema)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"))
                    }
                }

            // 3. UNIQUE 约束（H2：使用 INDEXES 表 + JOIN INDEX_COLUMNS）
            // H2 的 INDEX_TYPE_NAME 实际存的是 'UNIQUE INDEX'，等值比较 'UNIQUE' 永远不命中，
            // 会让重建 DDL 静默丢掉 UNIQUE 子句 — 与 listIndexes 一样用包含匹配。
            val uniqueConstraints = mutableListOf<String>()
            conn
                .prepareStatement(
                    "SELECT ix.INDEX_NAME, GROUP_CONCAT(ic.COLUMN_NAME ORDER BY ic.ORDINAL_POSITION) AS COLS " +
                        "FROM INFORMATION_SCHEMA.INDEXES ix " +
                        "JOIN INFORMATION_SCHEMA.INDEX_COLUMNS ic USING (TABLE_SCHEMA, TABLE_NAME, INDEX_NAME) " +
                        "WHERE ix.TABLE_NAME = ? AND ix.TABLE_SCHEMA = ? AND UPPER(ix.INDEX_TYPE_NAME) LIKE '%UNIQUE%' " +
                        "GROUP BY ix.INDEX_NAME",
                ).use { stmt ->
                    stmt.setString(1, safeTable)
                    stmt.setString(2, targetSchema)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            uniqueConstraints.add("UNIQUE (${rs.getString("COLS")})")
                        }
                    }
                }

            buildString {
                append("CREATE TABLE ${quoteIdentifier(safeTable)} (\n")
                val allDefs = mutableListOf<String>()
                allDefs.addAll(columns)
                if (primaryKeys.isNotEmpty()) {
                    allDefs.add("PRIMARY KEY (${primaryKeys.joinToString(", ") { quoteIdentifier(it) }})")
                }
                allDefs.addAll(uniqueConstraints)
                append("  ${allDefs.joinToString(",\n  ")}\n")
                append(")")
            }
        }

    override fun buildTableOptionsSQL(options: Map<String, String>): String = "" // H2 无表级引擎选项

    override fun buildPostCreateStatements(
        tableName: String,
        options: Map<String, String>,
    ): List<String> {
        val statements = mutableListOf<String>()
        val comment = options["comment"]
        if (comment != null) {
            val escaped = comment.replace("'", "''")
            statements.add("COMMENT ON TABLE $tableName IS '$escaped'")
        }
        return statements
    }

    // endregion

    // region SQL 安全校验

    override fun validateSqlFragment(
        sql: String,
        label: String,
    ) {
        DialectUtil.validateSqlFragment(sql, label, DANGEROUS_KEYWORD_REGEXES)
    }

    override fun validateOrderBy(sql: String) {
        DialectUtil.validateOrderBy(sql, ORDER_BY_REGEX)
    }

    // endregion

    // region 函数/存储过程管理

    override suspend fun listRoutines(
        conn: Connection,
        schema: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            val routines = mutableListOf<Map<String, String>>()
            val seenNames = mutableSetOf<String>()

            // 1. TRIGGERS
            conn
                .prepareStatement(
                    "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS " +
                        "FROM INFORMATION_SCHEMA.TRIGGERS " +
                        "WHERE TRIGGER_SCHEMA = ? " +
                        "ORDER BY TRIGGER_NAME",
                ).use { stmt ->
                    stmt.setString(1, targetSchema)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val tn = rs.getString("TRIGGER_NAME") ?: continue
                            seenNames.add(tn.uppercase())
                            routines.add(
                                mapOf(
                                    "name" to tn,
                                    "routine_type" to "TRIGGER",
                                    "return_type" to "TRIGGER",
                                    "language" to "java",
                                    "security_definer" to "SECURITY INVOKER",
                                    "volatility" to "VOLATILE",
                                    "arg_count" to "0",
                                    "arg_names" to "",
                                    "schema" to targetSchema,
                                    "description" to (rs.getString("REMARKS") ?: ""),
                                    "trigger_table" to (rs.getString("EVENT_OBJECT_TABLE") ?: ""),
                                ),
                            )
                        }
                    }
                }

            // 2. ROUTINES（真实 CREATE FUNCTION/PROCEDURE — H2 2.x 起可用）
            try {
                conn
                    .prepareStatement(
                        "SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, REMARKS " +
                            "FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ?",
                    ).use { stmt ->
                        stmt.setString(1, targetSchema)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val name = rs.getString("ROUTINE_NAME") ?: continue
                                seenNames.add(name.uppercase())
                                routines.add(
                                    mapOf(
                                        "name" to name,
                                        "routine_type" to (rs.getString("ROUTINE_TYPE") ?: "FUNCTION"),
                                        "return_type" to (rs.getString("DATA_TYPE") ?: ""),
                                        "language" to "java",
                                        "security_definer" to "SECURITY INVOKER",
                                        "volatility" to "VOLATILE",
                                        "arg_count" to "0",
                                        "arg_names" to "",
                                        "schema" to targetSchema,
                                        "description" to (rs.getString("REMARKS") ?: ""),
                                        "trigger_table" to "",
                                    ),
                                )
                            }
                        }
                    }
            } catch (_: Exception) {
                // ROUTINES 表不存在 — 跳过
            }

            // 3. FUNCTION_ALIASES（CREATE ALIAS 注册的 Java 方法别名）
            try {
                conn
                    .prepareStatement(
                        "SELECT ALIAS_NAME, ALIAS_SCHEMA, REMARKS " +
                            "FROM INFORMATION_SCHEMA.FUNCTION_ALIASES WHERE ALIAS_SCHEMA = ?",
                    ).use { stmt ->
                        stmt.setString(1, targetSchema)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val name = rs.getString("ALIAS_NAME") ?: continue
                                if (!seenNames.add(name.uppercase())) continue
                                routines.add(
                                    mapOf(
                                        "name" to name,
                                        "routine_type" to "FUNCTION",
                                        "return_type" to "java_method",
                                        "language" to "java",
                                        "security_definer" to "SECURITY INVOKER",
                                        "volatility" to "VOLATILE",
                                        "arg_count" to "0",
                                        "arg_names" to "",
                                        "schema" to targetSchema,
                                        "description" to (rs.getString("REMARKS") ?: ""),
                                        "trigger_table" to "",
                                    ),
                                )
                            }
                        }
                    }
            } catch (_: Exception) {
                // FUNCTION_ALIASES 表不存在 — 跳过
            }

            routines
        }

    override suspend fun getRoutineDDL(
        conn: Connection,
        routineName: String,
        schema: String,
    ): String =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            // H2 函数/触发器的 DDL 可通过 SYSTEM_RANGE 重建困难 — 返回简单的占位 DDL
            // 真实场景需客户端事先保存 DDL
            throw UnsupportedOperationException("H2 不支持从元数据重建函数/触发器 DDL，请保留 CREATE 时的原始 SQL")
        }

    override suspend fun createRoutine(
        conn: Connection,
        ddl: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            conn.createStatement().use { stmt -> stmt.execute(ddl) }
            true
        }

    override suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean,
        cascade: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(routineName, "routine name")
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            val sql =
                buildString {
                    when (routineType.uppercase()) {
                        "TRIGGER" -> {
                            append("DROP TRIGGER ")
                            if (ifExists) append("IF EXISTS ")
                            append(quoteIdentifier(safeName))
                        }

                        "FUNCTION", "PROCEDURE" -> {
                            append("DROP ALIAS ")
                            if (ifExists) append("IF EXISTS ")
                            append(quoteIdentifier(safeName))
                        }

                        else -> {
                            throw IllegalArgumentException("Unsupported routine type: $routineType")
                        }
                    }
                }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            true
        }

    override suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>,
    ): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(routineName, "routine name")
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            val result = mutableMapOf<String, Any?>()
            val placeholders = args.joinToString(", ") { "?" }
            val qualifiedName = quoteIdentifier(safeName)
            val isProcedure = routineType.equals("PROCEDURE", ignoreCase = true)

            // PROCEDURE → CALL；FUNCTION → SELECT scalar，使用参数化绑定
            val sql =
                if (isProcedure) {
                    "CALL $qualifiedName($placeholders)"
                } else {
                    "SELECT $qualifiedName($placeholders) AS result"
                }
            conn.prepareStatement(sql).use { stmt ->
                for ((i, v) in args.withIndex()) {
                    if (v == null) {
                        stmt.setNull(i + 1, java.sql.Types.NULL)
                    } else {
                        stmt.setString(i + 1, v)
                    }
                }
                if (isProcedure) {
                    // H2 PreparedStatement 不支持 executeUpdate()，只能 execute()
                    stmt.execute()
                    result["update_count"] = stmt.updateCount
                } else {
                    stmt.executeQuery().use { rs ->
                        val metaData = rs.metaData
                        val columnCount = metaData.columnCount
                        if (rs.next()) {
                            if (columnCount == 1) {
                                result["result"] = rs.getObject(1)
                            } else {
                                val row = mutableMapOf<String, Any?>()
                                for (col in 1..columnCount) row[metaData.getColumnLabel(col)] = rs.getObject(col)
                                result["result"] = row
                            }
                            result["row_count"] = 1
                        }
                    }
                }
            }
            result["routine_type"] = routineType.uppercase()
            result["schema"] = targetSchema
            result
        }

    override suspend fun getRoutineInfo(
        conn: Connection,
        routineName: String,
        schema: String,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            // H2：优先查 TRIGGERS
            conn
                .prepareStatement(
                    "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS " +
                        "FROM INFORMATION_SCHEMA.TRIGGERS " +
                        "WHERE TRIGGER_NAME = ? AND TRIGGER_SCHEMA = ?",
                ).use { stmt ->
                    stmt.setString(1, routineName)
                    stmt.setString(2, targetSchema)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            return@withContext mapOf(
                                "name" to routineName,
                                "routine_type" to "TRIGGER",
                                "schema" to targetSchema,
                                "language" to "java",
                                "return_type" to "TRIGGER",
                                "volatility" to "VOLATILE",
                                "security_definer" to "SECURITY INVOKER",
                                "arg_count" to "0",
                                "arg_names" to "",
                                "description" to (rs.getString("REMARKS") ?: ""),
                                "trigger_table" to (rs.getString("EVENT_OBJECT_TABLE") ?: ""),
                            )
                        }
                    }
                }
            // 尝试查 ROUTINES
            try {
                conn
                    .prepareStatement(
                        "SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, REMARKS " +
                            "FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_NAME = ? AND ROUTINE_SCHEMA = ?",
                    ).use { stmt ->
                        stmt.setString(1, routineName)
                        stmt.setString(2, targetSchema)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                return@withContext mapOf(
                                    "name" to routineName,
                                    "routine_type" to (rs.getString("ROUTINE_TYPE") ?: "FUNCTION"),
                                    "schema" to targetSchema,
                                    "language" to "java",
                                    "return_type" to (rs.getString("DATA_TYPE") ?: ""),
                                    "volatility" to "VOLATILE",
                                    "security_definer" to "SECURITY INVOKER",
                                    "arg_count" to "0",
                                    "arg_names" to "",
                                    "description" to (rs.getString("REMARKS") ?: ""),
                                    "trigger_table" to "",
                                )
                            }
                        }
                    }
            } catch (_: Exception) {
            }
            // 尝试查 FUNCTION_ALIASES（CREATE ALIAS 注册的 Java 方法别名）
            try {
                conn
                    .prepareStatement(
                        "SELECT ALIAS_NAME, ALIAS_SCHEMA, REMARKS " +
                            "FROM INFORMATION_SCHEMA.FUNCTION_ALIASES WHERE ALIAS_NAME = ? AND ALIAS_SCHEMA = ?",
                    ).use { stmt ->
                        stmt.setString(1, routineName)
                        stmt.setString(2, targetSchema)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                return@withContext mapOf(
                                    "name" to routineName,
                                    "routine_type" to "FUNCTION",
                                    "schema" to targetSchema,
                                    "language" to "java",
                                    "return_type" to "java_method",
                                    "volatility" to "VOLATILE",
                                    "security_definer" to "SECURITY INVOKER",
                                    "arg_count" to "0",
                                    "arg_names" to "",
                                    "description" to (rs.getString("REMARKS") ?: ""),
                                    "trigger_table" to "",
                                )
                            }
                        }
                    }
            } catch (_: Exception) {
            }
            throw IllegalArgumentException("未找到函数/触发器 '$routineName'，schema: '$targetSchema'")
        }

    override suspend fun debugRoutine(
        conn: Connection,
        routineName: String,
        schema: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            val safeName = sanitizeIdentifier(routineName, "routine name")
            val results = mutableListOf<Map<String, String>>()

            // EXPLAIN — 使用参数化语句并校验 routineName 安全性
            // H2 的 EXPLAIN 不支持占位符名称，必须拼接但已 sanitize
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("EXPLAIN SELECT ${quoteIdentifier(safeName)}()").use { rs ->
                        if (rs.next()) {
                            results.add(
                                mapOf(
                                    "type" to "EXPLAIN",
                                    "output" to (rs.getString(1) ?: ""),
                                ),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                results.add(mapOf("type" to "EXPLAIN", "output" to "(EXPLAIN not available)"))
            }

            // INFO
            val info = getRoutineInfo(conn, safeName, targetSchema)
            results.add(
                mapOf(
                    "type" to "INFO",
                    "output" to
                        buildString {
                            appendLine("Name: ${info["name"]}")
                            appendLine("Schema: ${info["schema"]}")
                            appendLine("Type: ${info["routine_type"]}")
                            appendLine("Language: ${info["language"]}")
                            appendLine("Return: ${info["return_type"]}")
                            if (info["trigger_table"]?.isNotBlank() == true) {
                                appendLine("Trigger Table: ${info["trigger_table"]}")
                            }
                        },
                ),
            )

            results
        }

    /**
     * 验证 DDL 语法 — 不实际执行，仅通过正则解析校验语法骨架。
     * 对 H2 仅做粗粒度的形式校验（不允许分号注入；类型/名称合法）。
     */
    override suspend fun validateRoutineDDL(
        conn: Connection,
        ddl: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val trimmed = ddl.trim().trimEnd(';')
            if (trimmed.contains(';')) throw IllegalArgumentException("DDL 不允许包含多条语句（分号）")

            // 1. 解析 DDL 类型 + 名称
            // 先把 `"name"` / `` `name` `` 引号去掉再匹配，统一为 `CREATE TYPE schema.name` 形式
            val normalized = trimmed.replace(Regex("""[`"]"""), "")
            val match =
                Regex("""(?i)CREATE\s+(?:OR\s+REPLACE\s+)?(FUNCTION|PROCEDURE|TRIGGER|ALIAS)\s+(\w+)(?:\.(\w+))?""").find(normalized)
                    ?: throw IllegalArgumentException("无法从 DDL 中解析对象类型/名称")

            val objType = match.groupValues[1].uppercase()
            // match group[2] = schema (or single-segment name); group[3] = name (only when schema.name form)
            val (objSchema, objName) =
                if (match.groupValues[3].isNotBlank()) {
                    match.groupValues[2] to match.groupValues[3]
                } else {
                    (conn.catalog ?: "") to match.groupValues[2]
                }
            if (objSchema.isBlank()) throw IllegalArgumentException("无法定位对象所属 schema")

            // 2. 名称安全检查（不允许注入字符）
            val allowedName = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
            if (!allowedName.matches(objName)) throw IllegalArgumentException("对象名称 '$objName' 包含非法字符")
            if (objSchema.isNotEmpty() && !allowedName.matches(objSchema)) throw IllegalArgumentException("schema 名称 '$objSchema' 包含非法字符")

            // 3. 校验 DDL 类型匹配
            when (objType) {
                "ALIAS" -> {
                    // CREATE ALIAS 必须含 FOR "<JavaMethod>"
                    if (!Regex("""(?i)\bFOR\s+"?[\w$.]+"?\s*$""").containsMatchIn(trimmed)) {
                        throw IllegalArgumentException("CREATE ALIAS 必须以 FOR \"<JavaMethod>\" 结尾")
                    }
                }

                "TRIGGER" -> {
                    // CREATE TRIGGER 必须含 BEFORE/AFTER + INSERT/UPDATE/DELETE + ON <table>
                    val required = listOf("ON", "BEFORE|AFTER|INSTEAD OF", "INSERT|UPDATE|DELETE")
                    for (pattern in required) {
                        if (!Regex("(?i)\\b(?:$pattern)\\b").containsMatchIn(trimmed)) {
                            throw IllegalArgumentException("CREATE TRIGGER 必须包含关键字: $pattern")
                        }
                    }
                }

                "FUNCTION", "PROCEDURE" -> {
                    // 必须含 RETURNS（FUNCTION）或不强制
                    if (objType == "FUNCTION" && !Regex("(?i)\\bRETURNS\\b").containsMatchIn(trimmed)) {
                        throw IllegalArgumentException("CREATE FUNCTION 必须包含 RETURNS 子句")
                    }
                }
            }

            true
        }

    // endregion

    // region Views

    override suspend fun listViews(
        conn: Connection,
        schema: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val views = mutableListOf<Map<String, String>>()
            val query =
                if (schema.isNotBlank()) {
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS " +
                        "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME"
                } else {
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS " +
                        "WHERE TABLE_SCHEMA = CURRENT_SCHEMA ORDER BY TABLE_NAME"
                }
            conn.prepareStatement(query).use { stmt ->
                if (schema.isNotBlank()) stmt.setString(1, schema)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) views.add(mapOf("name" to rs.getString(1)))
                }
            }
            views
        }

    override suspend fun createView(
        conn: Connection,
        viewName: String,
        definition: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(viewName, "view name")
            // definition 必须是纯 SELECT 语句；防御性注入：仅允许非分号
            if (definition.contains(';')) throw IllegalArgumentException("视图定义不允许包含分号")
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE VIEW ${quoteIdentifier(safeName)} AS $definition")
            }
            true
        }

    override suspend fun dropView(
        conn: Connection,
        viewName: String,
        ifExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeIdentifier(viewName, "view name")
            val sql =
                buildString {
                    append("DROP VIEW ")
                    if (ifExists) append("IF EXISTS ")
                    append(quoteIdentifier(safeName))
                }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            true
        }

    override suspend fun getViewDDL(
        conn: Connection,
        viewName: String,
        schema: String,
    ): String =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            conn
                .prepareStatement(
                    "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS " +
                        "WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?",
                ).use { stmt ->
                    stmt.setString(1, viewName)
                    stmt.setString(2, targetSchema)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val def = rs.getString(1) ?: ""
                            return@withContext "CREATE VIEW ${quoteIdentifier(viewName)} AS $def"
                        }
                    }
                }
            throw IllegalArgumentException("未找到视图 '$viewName'")
        }

    // endregion

    // region Indexes

    override suspend fun listIndexes(
        conn: Connection,
        tableName: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            val indexes = mutableListOf<Map<String, String>>()
            // H2 INDEX_TYPE_NAME: "UNIQUE INDEX" / "PRIMARY KEY" / "INDEX"
            conn
                .prepareStatement(
                    "SELECT ix.INDEX_NAME, ix.INDEX_TYPE_NAME, GROUP_CONCAT(ic.COLUMN_NAME ORDER BY ic.ORDINAL_POSITION) AS COLS " +
                        "FROM INFORMATION_SCHEMA.INDEXES ix " +
                        "JOIN INFORMATION_SCHEMA.INDEX_COLUMNS ic USING (TABLE_SCHEMA, TABLE_NAME, INDEX_NAME) " +
                        "WHERE ix.TABLE_NAME = ? " +
                        "GROUP BY ix.INDEX_NAME, ix.INDEX_TYPE_NAME " +
                        "ORDER BY ix.INDEX_NAME",
                ).use { stmt ->
                    stmt.setString(1, safeTable)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val typeName = rs.getString("INDEX_TYPE_NAME") ?: ""
                            val isUnique = typeName.contains("UNIQUE", ignoreCase = true) || typeName.equals("PRIMARY KEY", ignoreCase = true)
                            indexes.add(
                                mapOf(
                                    "name" to rs.getString("INDEX_NAME"),
                                    "unique" to isUnique.toString(),
                                    "columns" to rs.getString("COLS"),
                                ),
                            )
                        }
                    }
                }
            indexes
        }

    override suspend fun createIndex(
        conn: Connection,
        tableName: String,
        indexName: String,
        columns: List<String>,
        unique: Boolean,
        ifNotExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            val safeIndex = sanitizeIdentifier(indexName, "index name")
            val safeCols = columns.joinToString(", ") { quoteIdentifier(it) }
            val sql =
                buildString {
                    if (unique) {
                        append("CREATE UNIQUE INDEX ")
                    } else {
                        append("CREATE INDEX ")
                    }
                    if (ifNotExists) append("IF NOT EXISTS ")
                    append(quoteIdentifier(safeIndex))
                    append(" ON ${quoteIdentifier(safeTable)} ($safeCols)")
                }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            true
        }

    override suspend fun dropIndex(
        conn: Connection,
        indexName: String,
        tableName: String?,
        ifExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeIndex = sanitizeIdentifier(indexName, "index name")
            conn.createStatement().use { stmt ->
                val ifClause = if (ifExists) "IF EXISTS " else ""
                stmt.execute("DROP INDEX ${ifClause}${quoteIdentifier(safeIndex)}")
            }
            true
        }

    // endregion

    // region Foreign Keys

    override suspend fun listForeignKeys(
        conn: Connection,
        tableName: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            // H2 没有 KEY_COLUMN_USAGE.REFERENCED_TABLE_NAME — 通过 DatabaseMetaData 跨方言获取外键
            val fks = mutableListOf<Map<String, String>>()
            // 按 FK 名 + KEY_SEQ 分组聚合列
            conn.metaData.getImportedKeys(null, null, tableName).use { rs ->
                val grouped = linkedMapOf<String, MutableMap<String, Any>>()
                while (rs.next()) {
                    val fkName = rs.getString("FK_NAME") ?: continue
                    val col = rs.getString("FKCOLUMN_NAME") ?: ""
                    val refCol = rs.getString("PKCOLUMN_NAME") ?: ""
                    val refTable = rs.getString("PKTABLE_NAME") ?: ""
                    val keySeq = rs.getInt("KEY_SEQ")
                    val updateRule = rs.getInt("UPDATE_RULE")
                    val deleteRule = rs.getInt("DELETE_RULE")

                    val entry =
                        grouped.getOrPut(fkName) {
                            mutableMapOf(
                                "name" to fkName,
                                "columns" to mutableListOf<String>(),
                                "ref_table" to refTable,
                                "ref_columns" to mutableListOf<String>(),
                                "on_update" to mapRule(updateRule),
                                "on_delete" to mapRule(deleteRule),
                            )
                        }
                    @Suppress("UNCHECKED_CAST")
                    (entry["columns"] as MutableList<String>)
                        .also { while (it.size < keySeq) it.add("") }
                        .let { if (keySeq in 1..it.size) it[keySeq - 1] = col else it.add(col) }
                    @Suppress("UNCHECKED_CAST")
                    (entry["ref_columns"] as MutableList<String>)
                        .also { while (it.size < keySeq) it.add("") }
                        .let { if (keySeq in 1..it.size) it[keySeq - 1] = refCol else it.add(refCol) }
                }
                grouped.values.forEach { entry ->
                    fks.add(
                        mapOf(
                            "name" to (entry["name"] as String),
                            "columns" to (entry["columns"] as List<String>).joinToString(","),
                            "ref_table" to (entry["ref_table"] as String),
                            "ref_columns" to (entry["ref_columns"] as List<String>).joinToString(","),
                            "on_update" to (entry["on_update"] as String),
                            "on_delete" to (entry["on_delete"] as String),
                        ),
                    )
                }
            }
            fks
        }

    private fun mapRule(code: Int): String =
        when (code) {
            java.sql.DatabaseMetaData.importedKeyCascade -> "CASCADE"
            java.sql.DatabaseMetaData.importedKeySetNull -> "SET NULL"
            java.sql.DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT"
            java.sql.DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
            java.sql.DatabaseMetaData.importedKeyNoAction -> "NO ACTION"
            else -> "RESTRICT"
        }

    override suspend fun addForeignKey(
        conn: Connection,
        tableName: String,
        fkName: String,
        columns: List<String>,
        refTable: String,
        refColumns: List<String>,
        onDelete: String?,
        onUpdate: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            val safeFk = sanitizeIdentifier(fkName, "foreign key name")
            val cols = columns.joinToString(", ") { quoteIdentifier(it) }
            val refCols = refColumns.joinToString(", ") { quoteIdentifier(it) }
            val sql =
                buildString {
                    append("ALTER TABLE ${quoteIdentifier(safeTable)} ADD CONSTRAINT ${quoteIdentifier(safeFk)} ")
                    append("FOREIGN KEY ($cols) REFERENCES ${quoteIdentifier(refTable)} ($refCols)")
                    onDelete?.takeIf { it.isNotBlank() }?.let { append(" ON DELETE $it") }
                    onUpdate?.takeIf { it.isNotBlank() }?.let { append(" ON UPDATE $it") }
                }
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            true
        }

    override suspend fun dropForeignKey(
        conn: Connection,
        tableName: String,
        fkName: String,
        ifExists: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            val safeFk = sanitizeIdentifier(fkName, "foreign key name")
            conn.createStatement().use { stmt ->
                // H2 没有 IF EXISTS for ALTER TABLE DROP CONSTRAINT — 需预查询
                if (ifExists) {
                    val exists =
                        stmt
                            .executeQuery(
                                "SELECT 1 FROM information_schema.table_constraints " +
                                    "WHERE table_name='$safeTable' AND constraint_name='$safeFk' LIMIT 1",
                            ).use { rs -> rs.next() }
                    if (!exists) return@withContext true
                }
                stmt.execute("ALTER TABLE ${quoteIdentifier(safeTable)} DROP CONSTRAINT ${quoteIdentifier(safeFk)}")
            }
            true
        }

    // endregion

    // region Triggers

    override suspend fun listTriggers(
        conn: Connection,
        schema: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val targetSchema = if (schema.isNotBlank()) schema else "PUBLIC"
            val triggers = mutableListOf<Map<String, String>>()
            conn
                .prepareStatement(
                    "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS " +
                        "FROM INFORMATION_SCHEMA.TRIGGERS " +
                        "WHERE TRIGGER_SCHEMA = ? " +
                        "ORDER BY TRIGGER_NAME",
                ).use { stmt ->
                    stmt.setString(1, targetSchema)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            triggers.add(
                                mapOf(
                                    "name" to rs.getString("TRIGGER_NAME"),
                                    "table" to rs.getString("EVENT_OBJECT_TABLE"),
                                    "description" to (rs.getString("REMARKS") ?: ""),
                                    "schema" to targetSchema,
                                ),
                            )
                        }
                    }
                }
            triggers
        }

    override suspend fun getTriggerDDL(
        conn: Connection,
        triggerName: String,
        schema: String,
    ): String =
        withContext(Dispatchers.IO) {
            // H2 不存储触发器原始 DDL — 返回占位说明
            throw UnsupportedOperationException("H2 不暴露触发器原始 DDL，请保留 CREATE 时的原始 SQL")
        }

    // endregion

    // region Table Operations

    override suspend fun renameTable(
        conn: Connection,
        oldName: String,
        newName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeOld = sanitizeIdentifier(oldName, "table name")
            val safeNew = sanitizeIdentifier(newName, "table name")
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE ${quoteIdentifier(safeOld)} RENAME TO ${quoteIdentifier(safeNew)}")
            }
            true
        }

    override suspend fun truncateTable(
        conn: Connection,
        tableName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val safeTable = sanitizeIdentifier(tableName, "table name")
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE ${quoteIdentifier(safeTable)}")
            }
            true
        }

    // endregion

    // region SQL — EXPLAIN

    override suspend fun explainSQL(
        conn: Connection,
        sql: String,
    ): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            if (sql.contains(';')) throw IllegalArgumentException("EXPLAIN 不允许包含分号")
            val rows = mutableListOf<Map<String, String>>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("EXPLAIN $sql").use { rs ->
                    val meta = rs.metaData
                    val columnCount = meta.columnCount
                    while (rs.next()) {
                        val row = mutableMapOf<String, String>()
                        for (i in 1..columnCount) {
                            row[meta.getColumnLabel(i)] = rs.getString(i) ?: ""
                        }
                        rows.add(row)
                    }
                }
            }
            rows
        }

    // endregion

    // region Server

    override suspend fun getServerInfo(conn: Connection): Map<String, String> =
        withContext(Dispatchers.IO) {
            val meta = conn.metaData
            mapOf(
                "product" to (meta.databaseProductName ?: ""),
                "version" to (meta.databaseProductVersion ?: ""),
                "driver" to (meta.driverName ?: ""),
                "driverVersion" to (meta.driverVersion ?: ""),
                "url" to (meta.url ?: ""),
                "userName" to (meta.userName ?: ""),
            )
        }

    // endregion

    private fun buildTypeSpec(
        type: String,
        size: Int?,
    ): String =
        if (size != null && type.uppercase() in SIZABLE_TYPES) {
            "$type($size)"
        } else {
            type
        }
}
