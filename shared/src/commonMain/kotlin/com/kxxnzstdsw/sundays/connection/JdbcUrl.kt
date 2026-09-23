package com.kxxnzstdsw.sundays.connection

/**
 * JDBC URL 编解码 —— 连接配置的**真相源**（v2.12）。
 *
 * `ConnectionConfig.jdbcUrl` 是引擎侧唯一消费的连接标识（`PoolManager` 非空 URL 时直接用 URL 建池，
 * 方言由 URL scheme 反查），因此 UI 侧的所有字段编辑都必须收敛到一条合法 URL 上：
 *
 * - [buildJdbcUrl]：字段 → URL，覆盖全部 5 个方言 × 每个方言支持的连接类型
 * - [parseJdbcUrl]：URL → 字段，供持久化加载（[ConnectionStorage]）与向导的 URL 输入框反向同步
 *
 * **与引擎的一致性**：URL 形状镜像 `engine` 侧各方言的 `DatabaseDialect.buildJdbcUrl`
 * （`Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`）。新增方言或改动 URL 规则时**两处同步**：
 *
 * | 方言 | URL 形状 |
 * |---|---|
 * | MySQL | `jdbc:mysql://[user[:pass]@]host[:port][/db][?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC]` |
 * | PostgreSQL | `jdbc:postgresql://[user[:pass]@]host[:port][/db]` |
 * | H2（内存） | `jdbc:h2:mem:<db>;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE` |
 * | H2（文件） | `jdbc:h2:file:<path>` |
 * | DuckDB | `jdbc:duckdb:<path>`（空 = 内存库） |
 * | SQLite | `jdbc:sqlite:<path>`（空 / `:memory:` = 内存库） |
 */

/** MySQL 连接池默认参数 —— 与 `MySQLDialect.buildJdbcUrl` 一致（无显式参数时补齐）。 */
private const val MYSQL_DEFAULT_PARAMS =
    "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

/**
 * 从 [ConnectionConfig] 的字段构建 JDBC URL。
 *
 * @param extraQuery 显式 query 参数（不含 `?`）。非空时**完全替代**方言默认参数；为空时补 MySQL 默认参数。
 *   向导的 JDBC URL 输入框把用户书写/解析出的参数经此参数回传，保证 `?...` 不丢失。
 * @return 字段不足以构成 URL 时返回 `""`（例如 CLIENT_SERVER 缺主机、H2 缺库名）。
 */
internal fun buildJdbcUrl(config: ConnectionConfig, extraQuery: String = ""): String {
    val database = config.database.trim()
    return when (config.dialect) {
        DialectType.MYSQL, DialectType.POSTGRESQL -> {
            val host = config.host.trim()
            if (host.isEmpty()) return ""
            val scheme = if (config.dialect == DialectType.MYSQL) "jdbc:mysql" else "jdbc:postgresql"
            val port = config.port?.takeIf { it > 0 } ?: config.displayPort
            val portPart = if (port > 0) ":$port" else ""
            val dbPart = if (database.isNotEmpty()) "/$database" else ""
            val user = config.username.trim()
            val credPart =
                if (user.isEmpty()) ""
                else "$user${if (config.password.isNotEmpty()) ":${config.password}" else ""}@"
            val query = extraQuery.ifBlank {
                if (config.dialect == DialectType.MYSQL) MYSQL_DEFAULT_PARAMS else ""
            }
            "$scheme://$credPart$host$portPart$dbPart${if (query.isNotBlank()) "?$query" else ""}"
        }

        DialectType.H2 -> when {
            database.isEmpty() -> ""
            config.connectionType == ConnectionType.FILE_BASED -> "jdbc:h2:file:$database"
            else -> "jdbc:h2:mem:$database;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        }

        // DuckDB：database 为空 = 内存库（引擎 DuckDBDialect 语义）
        DialectType.DUCKDB -> "jdbc:duckdb:$database"

        // SQLite：database 为空 / :memory: = 内存库（引擎 SQLiteDialect 语义）
        DialectType.SQLITE -> when (database) {
            "", ":memory:" -> "jdbc:sqlite::memory:"
            else -> "jdbc:sqlite:$database"
        }

        DialectType.UNKNOWN -> ""
    }
}

/**
 * JDBC URL 的字段投影。
 *
 * [connectionType] 由 URL 形状反推（H2 `mem:` → IN_MEMORY，`file:` → FILE_BASED，DuckDB → EMBEDDED，
 * SQLite → FILE_BASED）；URL 为空、前缀不匹配或形状不可识别时为 [ConnectionType.UNKNOWN]，
 * 调用方据此回退到方言默认连接类型。
 */
internal data class UrlParts(
    val host: String = "",
    val port: String = "",
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val connectionType: ConnectionType = ConnectionType.UNKNOWN,
)

/** 从 JDBC URL 解析字段（方言决定解析规则；不匹配 / 空 URL 返回空 [UrlParts]）。 */
internal fun parseJdbcUrl(url: String, dialect: DialectType): UrlParts {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return UrlParts()

    return when (dialect) {
        DialectType.MYSQL -> parseClientServerUrl(trimmed, "jdbc:mysql")
        DialectType.POSTGRESQL -> parseClientServerUrl(trimmed, "jdbc:postgresql")
        DialectType.H2 -> parseH2Url(trimmed)
        DialectType.DUCKDB -> {
            if (!trimmed.startsWith("jdbc:duckdb:", ignoreCase = true)) UrlParts()
            else UrlParts(
                database = trimmed.substring("jdbc:duckdb:".length).substringBefore(';'),
                connectionType = ConnectionType.EMBEDDED,
            )
        }
        DialectType.SQLITE -> {
            if (!trimmed.startsWith("jdbc:sqlite:", ignoreCase = true)) UrlParts()
            else {
                val body = trimmed.substring("jdbc:sqlite:".length).substringBefore('?')
                UrlParts(
                    database = if (body == ":memory:") "" else body,
                    connectionType = ConnectionType.FILE_BASED,
                )
            }
        }
        DialectType.UNKNOWN -> UrlParts()
    }
}

/** 解析 `jdbc:mysql://user:pass@host:port/db?params` / `jdbc:postgresql://...` 形状。 */
private fun parseClientServerUrl(url: String, scheme: String): UrlParts {
    if (!url.startsWith(scheme, ignoreCase = true)) return UrlParts()
    val withoutScheme = url.substring(scheme.length).removePrefix("://")

    val slashIdx = withoutScheme.indexOf('/')
    val hostPart = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
    val afterSlash = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""

    // 数据库名 = `?` 之前的部分
    val database = afterSlash.substringBefore('?')

    // `credentials@host:port`
    val atIdx = hostPart.indexOf('@')
    val credPart = if (atIdx >= 0) hostPart.substring(0, atIdx) else ""
    val hostColonPort = if (atIdx >= 0) hostPart.substring(atIdx + 1) else hostPart
    val colonIdx = hostColonPort.lastIndexOf(':')
    val host = if (colonIdx >= 0) hostColonPort.substring(0, colonIdx) else hostColonPort
    val port = if (colonIdx >= 0) hostColonPort.substring(colonIdx + 1) else ""

    // `username[:password]`
    val user = credPart.substringBefore(':')
    val password = credPart.substringAfter(':', missingDelimiterValue = "")

    return UrlParts(
        host = host,
        port = port,
        database = database,
        username = user,
        password = password,
        connectionType = ConnectionType.CLIENT_SERVER,
    )
}

/** 解析 `jdbc:h2:mem:<db>;...` / `jdbc:h2:file:<path>`；其他 H2 形态（tcp/ssl）不识别。 */
private fun parseH2Url(url: String): UrlParts {
    if (!url.startsWith("jdbc:h2:", ignoreCase = true)) return UrlParts()
    val body = url.substring("jdbc:h2:".length)
    return when {
        body.startsWith("mem:", ignoreCase = true) -> UrlParts(
            database = body.substring(4).substringBefore(';'),
            connectionType = ConnectionType.IN_MEMORY,
        )
        body.startsWith("file:", ignoreCase = true) -> UrlParts(
            database = body.substring(5).substringBefore(';'),
            connectionType = ConnectionType.FILE_BASED,
        )
        else -> UrlParts()
    }
}
