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
 * | PostgreSQL | `jdbc:postgresql://host[:port][/db]` —— **不带 userinfo**，见 [buildJdbcUrl] |
 * | H2（内存） | `jdbc:h2:mem:<db>;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE` |
 * | H2（文件） | `jdbc:h2:file:<path>` |
 * | DuckDB | `jdbc:duckdb:<path>`（空 = 内存库） |
 * | SQLite | `jdbc:sqlite:<path>`（空 / `:memory:` = 内存库） |
 */

/** MySQL 连接池默认参数 —— 与 `MySQLDialect.buildJdbcUrl` 一致（无显式参数时补齐）。 */
private const val MYSQL_DEFAULT_PARAMS =
    "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

/**
 * userinfo 的 percent-encoding —— 与 [decodeUserInfo] 成对。
 *
 * 只编码 RFC 3986 userinfo 段里真正有歧义的字符（`@ / : ? # [ ] %` 与控制字符），
 * 保留字母数字与 `-._~!$&'()*+,;=`，这样绝大多数用户名/密码生成的 URL 仍然人类可读，
 * 与改造前完全一致。空格编码为 `%20`（而非 `+`）—— userinfo 段不做 `+`→空格 的还原。
 */
private fun encodeUserInfo(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when {
            ch.isLetterOrDigit() && ch.code < 128 -> append(ch)
            ch in "-._~!\$&'()*+,;=" -> append(ch)
            else -> {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
    }
}

/**
 * userinfo 的 percent-decoding —— 与 [encodeUserInfo] 成对。
 *
 * 按**字节**收集再一次性 UTF-8 解码，因此 `中文%2Fabc` 这类多字节字符能正确还原；
 * 无法解码的 `%` 序列原样保留，保证「手写的、没编码过的 URL」也能照常解析（向后兼容）。
 */
private fun decodeUserInfo(raw: String): String {
    if (!raw.contains('%')) return raw
    val bytes = ArrayList<Byte>(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val v = raw.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) {
                bytes.add(v.toByte())
                i += 3
                continue
            }
        }
        // 非转义字符：按 UTF-8 字节落盘（ASCII 即单字节）
        c.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
        i++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

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
        // MySQL 与 PostgreSQL 形状同源，但**凭据是否进 URL 完全不同**，必须分开写 ——
        // 见 [mysqlCredPart] 与下面 PostgreSQL 分支的说明。
        DialectType.MYSQL -> clientServerUrl(config, database, "jdbc:mysql", MYSQL_DEFAULT_PARAMS, extraQuery)
        DialectType.POSTGRESQL -> clientServerUrl(config, database, "jdbc:postgresql", "", extraQuery)

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
 * client/server 类方言的 URL 拼装 —— MySQL 与 PostgreSQL 共用的骨架。
 *
 * ## 凭据进不进 URL：两个方言的答案相反，且**不能统一**
 *
 * 这不是一个可以「顺手统一」的格式细节，而是两个驱动各自的能力边界：
 *
 * - **MySQL**（Connector/J）解析 URL 时认 `user:pass@` 这段 userinfo，所以凭据照旧写进去。
 * - **PostgreSQL**（`org.postgresql.Driver`）**根本不支持 userinfo**。实测 42.7.11：
 *
 *   ```
 *   parseURL("jdbc:postgresql://postgres:666666@192.168.1.5:5432/postgres")
 *     PGHOST   = postgres:666666@192.168.1.5   ← 整段被当成主机名
 *     PGDBNAME = postgres
 *     PGPORT   = 5432
 *     （没有任何 PGUSER —— 凭据压根没被识别）
 *     → java.net.UnknownHostException: postgres:666666@192.168.1.5
 *   ```
 *
 *   驱动按最后一个 `:` 切端口、其余整段当 host，于是这串"主机名"永远解析不出来，
 *   外层再包成一句无信息量的「尝试连线已失败」。**PG 连接因此 100% 连不上**。
 *
 * ## 凭据不写进 URL，会不会丢？
 *
 * 不会，三处都不依赖 PG 的 URL 带凭据：
 *
 * - 引擎 `PoolManager.createDataSource` 把 `config.user` / `config.password` **单独**传给 HikariCP
 * - 持久化 `PersistedConnectionConfig` 把 `username` / `password` **独立于** `jdbcUrl` 存盘，
 *   加载时也只从这两个字段取，从不从 URL 反解凭据
 * - 引擎自己的 `PostgreSQLDialect.buildJdbcUrl` 产出的也是 `jdbc:postgresql://host:port/db`，
 *   本函数现在与它一致
 *
 * 顺带的好处：URL 不再承载明文口令，而 `PoolManager` 建池时会把 URL 打进日志
 * （`url=$resolvedJdbcUrl`）—— 凭据进 URL 等于把口令写进日志文件。
 *
 * @param defaultQuery 无显式参数时补的方言默认参数（PG 传空串）
 */
private fun clientServerUrl(
    config: ConnectionConfig,
    database: String,
    scheme: String,
    defaultQuery: String,
    extraQuery: String,
): String {
    val host = config.host.trim()
    if (host.isEmpty()) return ""
    val port = config.port?.takeIf { it > 0 } ?: config.displayPort
    val portPart = if (port > 0) ":$port" else ""
    val dbPart = if (database.isNotEmpty()) "/$database" else ""
    val query = extraQuery.ifBlank { defaultQuery }
    return "$scheme://${credPartFor(config)}$host$portPart$dbPart${if (query.isNotBlank()) "?$query" else ""}"
}

/** 只有 MySQL 把凭据写进 URL 的 userinfo 段 —— PG 恒返回空串（理由见 [clientServerUrl]）。 */
private fun credPartFor(config: ConnectionConfig): String {
    if (config.dialect != DialectType.MYSQL) return ""
    val user = config.username.trim()
    if (user.isEmpty()) return ""
    // userinfo 必须 percent-encoding：用户名/密码里的 `@` 与 `/` 是合法字符，
    // 不编码的话解析侧会把它们误当作 host/db 分隔符（见 parseClientServerUrl 的
    // 「最后一个 @」约定），导致 URL 往返后 host/库名/凭据全部错位。
    val pass = if (config.password.isNotEmpty()) ":${encodeUserInfo(config.password)}" else ""
    return "${encodeUserInfo(user)}$pass@"
}

/**
 * 把存量 URL 归一化成 [buildJdbcUrl] 会产出的形状。
 *
 * ## 为什么需要它
 *
 * 本缺陷存在期间，向导给 PG 存下的每一条连接的 URL 里都带着一截 userinfo
 * （`jdbc:postgresql://user:pass@host:5432/db`），而那正是连不上的原因。
 * 只改 [buildJdbcUrl] 的话，**老配置依然是坏的** —— 用户打开列表点连接，还是那句
 * 「尝试连线已失败」，等于修复对他们不生效。存量配置必须一并收拾掉。
 *
 * ## 为什么只动 PostgreSQL
 *
 * - 只处理 **userinfo 段**（`//` 与第一个 `/` 之间的 `@` 前缀），authority 的
 *   host:port 与 `?` 后的参数**逐字保留** —— 参数里可能有 `sslmode` / `currentSchema`。
 * - MySQL 的 userinfo 是**驱动认的**，留着；这里只做归一化，不是清洗。
 * - 凭据不会丢：`PersistedConnectionConfig` 里本来就单独存着 `username` / `password`。
 *
 * ## 为什么这条不写成迁移
 *
 * 它不改动磁盘格式、不改版本号，加载时在内存里归一化即可 ——
 * 只有当用户下次保存时才会落成新形状。改版本号 + 扫盘迁移要处理「读旧文件失败」
 * 这类分支，而这里根本不需要碰磁盘就能拿到同样的效果。
 */
internal fun normalizeJdbcUrl(url: String, dialect: DialectType): String {
    if (dialect != DialectType.POSTGRESQL) return url
    val scheme = "jdbc:postgresql:"
    if (!url.startsWith(scheme, ignoreCase = true)) return url

    val rest = url.substring(scheme.length)
    // 少了 `//` 的 URL 不是 driver 能用的形状，原样放过 —— 别把它改成一个
    // 「看起来更对」但同样连不上的样子，那只会掩盖真正的问题。
    if (!rest.startsWith("//")) return url
    val body = rest.substring(2)

    val slashAt = body.indexOf('/')
    val authority = if (slashAt < 0) body else body.substring(0, slashAt)
    val atAt = authority.lastIndexOf('@')
    if (atAt < 0) return url // 本来就没有 userinfo —— 已是目标形状

    val tail = if (slashAt < 0) "" else body.substring(slashAt)
    return scheme + "//" + authority.substring(atAt + 1) + tail
}

/**
 * 把用户手工输入的 JDBC URL 并回配置 —— 向导 URL 输入框（URL → 字段方向）的唯一实现。
 *
 * ## 凭据只在 URL **真的写了** userinfo 时才被采纳
 *
 * PG 的 URL 正常**不带**凭据（PG 驱动不认 userinfo，见 [buildJdbcUrl]）。若在这里
 * 无条件用解析结果覆盖 `username` / `password`，那么用户只是在 URL 末尾补了个
 * `?sslmode=require`、点一下输入框，就会把认真填过的用户名密码**静默清空** ——
 * 而且 URL 依然合法，向导照常放行保存，直到连库时才报一句「认证失败」。
 * 「URL 里没写凭据」和「用户要清空凭据」是完全不同的两件事，
 * 所以判据是 [UrlParts.hasUserInfo] 而非「`username` 是否为空」。要清空请用密码框。
 *
 * URL 本身经 [normalizeJdbcUrl] 归一化：用户粘进来一段 MySQL 形状的 PG URL
 * （`//u:p@h:5432/db`）时，就地摘掉那截驱动不认的 userinfo 而不是留着它连不上。
 */
internal fun ConnectionConfig.withParsedJdbcUrl(url: String): ConnectionConfig {
    val parts = parseJdbcUrl(url, dialect)
    return copy(
        jdbcUrl = normalizeJdbcUrl(url, dialect),
        host = parts.host,
        port = parts.port.toIntOrNull(),
        database = parts.database,
        username = if (parts.hasUserInfo) parts.username else username,
        password = if (parts.hasUserInfo) parts.password else password,
    )
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
    /**
     * URL 里**是否出现过** userinfo 段（`//` 与 host 之间那段 `user[:pass]@`）。
     *
     * 为什么需要它：PG 的 URL 正常就不带凭据（见 [buildJdbcUrl]），所以
     * `username == ""` 有两种截然不同的含义 ——「用户没填」和「这个 URL 压根没写凭据」。
     * 调用方若拿 `username` 空与否去覆盖配置里的凭据字段，第二种情况会把用户
     * 认真填过的用户名密码**静默清空**。有了这个标志才能把两者分开。
     */
    val hasUserInfo: Boolean = false,
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

    // 库名分隔：取**最后一个** `/` —— 密码里未经编码的 `/` 不能截断 host 段。
    // （由 buildJdbcUrl 生成的 URL 已对 userinfo 做 percent-encoding，因此这里的
    //  「最后一个 /」只会落在 host:port 与库名之间。）
    val slashIdx = withoutScheme.lastIndexOf('/')
    val hostPart = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
    val afterSlash = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""

    // 数据库名 = `?` 之前的部分
    val database = afterSlash.substringBefore('?')

    // `credentials@host:port` —— 取**最后一个** `@` 作为 userinfo 分隔：
    // 密码里未编码的 `@` 是合法字符，取首个会把 host 截断（host 变成 "ss@db.example.com"）。
    val atIdx = hostPart.lastIndexOf('@')
    val credPart = if (atIdx >= 0) hostPart.substring(0, atIdx) else ""
    val hostColonPort = if (atIdx >= 0) hostPart.substring(atIdx + 1) else hostPart
    val colonIdx = hostColonPort.lastIndexOf(':')
    val host = if (colonIdx >= 0) hostColonPort.substring(0, colonIdx) else hostColonPort
    val port = if (colonIdx >= 0) hostColonPort.substring(colonIdx + 1) else ""

    // `username[:password]` —— 密码按首个 `:` 切分（用户名里的 `:` 非法）
    val user = decodeUserInfo(credPart.substringBefore(':'))
    val password = decodeUserInfo(credPart.substringAfter(':', missingDelimiterValue = ""))

    return UrlParts(
        host = host,
        port = port,
        database = database,
        username = user,
        password = password,
        connectionType = ConnectionType.CLIENT_SERVER,
        hasUserInfo = atIdx >= 0,
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
