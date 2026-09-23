package com.kxxnzstdsw.sundays.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JDBC URL 编解码测试 —— 连接流程的“字段 ↔ URL”真相源不变式。
 *
 * 覆盖：5 个方言 × 连接类型的 URL 形状、字段不足时的空 URL（向导据此禁用「下一步」/「保存」）、
 * 显式 query 参数保留、以及持久化加载所依赖的 URL → 字段回解析。
 */
class JdbcUrlTest {

    private fun config(
        dialect: DialectType,
        connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
        host: String = "",
        port: Int? = null,
        database: String = "",
        username: String = "",
        password: String = "",
    ) = ConnectionConfig(
        id = "test",
        name = "test",
        dialect = dialect,
        connectionType = connectionType,
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
    )

    @Test
    fun `client server url carries credentials host port and database`() {
        val url = buildJdbcUrl(
            config(
                dialect = DialectType.MYSQL,
                host = "db.internal",
                port = 3307,
                database = "shop",
                username = "root",
                password = "s3cret",
            )
        )

        assertTrue(url.startsWith("jdbc:mysql://root:s3cret@db.internal:3307/shop"), "url=$url")
        // 无显式参数时补齐 MySQL 方言默认参数（与 MySQLDialect.buildJdbcUrl 一致）
        assertTrue(url.endsWith("?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"), "url=$url")
    }

    @Test
    fun `postgresql url has no query parameters`() {
        val url = buildJdbcUrl(
            config(dialect = DialectType.POSTGRESQL, host = "localhost", port = 5432, database = "app")
        )

        assertEquals("jdbc:postgresql://localhost:5432/app", url)
    }

    @Test
    fun `explicit query parameters replace dialect defaults`() {
        val url = buildJdbcUrl(
            config(dialect = DialectType.MYSQL, host = "localhost", port = 3306, database = "shop"),
            extraQuery = "useSSL=true&connectTimeout=2000",
        )

        assertEquals("jdbc:mysql://localhost:3306/shop?useSSL=true&connectTimeout=2000", url)
    }

    @Test
    fun `client server without host yields empty url`() {
        assertEquals("", buildJdbcUrl(config(dialect = DialectType.MYSQL, database = "shop")))
        assertEquals("", buildJdbcUrl(config(dialect = DialectType.POSTGRESQL)))
    }

    @Test
    fun `h2 urls switch between memory and file shapes`() {
        assertEquals(
            "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            buildJdbcUrl(
                config(
                    dialect = DialectType.H2,
                    connectionType = ConnectionType.IN_MEMORY,
                    database = "testdb",
                )
            ),
        )
        assertEquals(
            "jdbc:h2:file:/tmp/h2data",
            buildJdbcUrl(
                config(
                    dialect = DialectType.H2,
                    connectionType = ConnectionType.FILE_BASED,
                    database = "/tmp/h2data",
                )
            ),
        )
        // H2 需要库名 —— 空库名折算不出 URL（向导据此拦住流程）
        assertEquals(
            "",
            buildJdbcUrl(config(dialect = DialectType.H2, connectionType = ConnectionType.IN_MEMORY)),
        )
    }

    @Test
    fun `duckdb and sqlite urls treat database as path with in-memory fallback`() {
        assertEquals(
            "jdbc:duckdb:/tmp/analytics.duckdb",
            buildJdbcUrl(
                config(
                    dialect = DialectType.DUCKDB,
                    connectionType = ConnectionType.EMBEDDED,
                    database = "/tmp/analytics.duckdb",
                )
            ),
        )
        assertEquals(
            "jdbc:duckdb:",
            buildJdbcUrl(config(dialect = DialectType.DUCKDB, connectionType = ConnectionType.EMBEDDED)),
        )
        assertEquals(
            "jdbc:sqlite:/tmp/app.db",
            buildJdbcUrl(
                config(
                    dialect = DialectType.SQLITE,
                    connectionType = ConnectionType.FILE_BASED,
                    database = "/tmp/app.db",
                )
            ),
        )
        assertEquals(
            "jdbc:sqlite::memory:",
            buildJdbcUrl(config(dialect = DialectType.SQLITE, connectionType = ConnectionType.FILE_BASED)),
        )
    }

    @Test
    fun `client server round trips every field through url and back`() {
        val original = config(
            dialect = DialectType.POSTGRESQL,
            host = "db.internal",
            port = 5433,
            database = "app",
            username = "admin",
            password = "pw",
        )

        val parts = parseJdbcUrl(buildJdbcUrl(original), DialectType.POSTGRESQL)

        assertEquals("db.internal", parts.host)
        assertEquals("5433", parts.port)
        assertEquals("app", parts.database)
        assertEquals("admin", parts.username)
        assertEquals("pw", parts.password)
        assertEquals(ConnectionType.CLIENT_SERVER, parts.connectionType)
    }

    @Test
    fun `embedded round trip recovers database and connection type`() {
        val h2Mem = config(
            dialect = DialectType.H2,
            connectionType = ConnectionType.IN_MEMORY,
            database = "testdb",
        )
        val h2Parsed = parseJdbcUrl(buildJdbcUrl(h2Mem), DialectType.H2)
        assertEquals("testdb", h2Parsed.database)
        assertEquals(ConnectionType.IN_MEMORY, h2Parsed.connectionType)

        val h2File = config(
            dialect = DialectType.H2,
            connectionType = ConnectionType.FILE_BASED,
            database = "/tmp/h2data",
        )
        val h2FileParsed = parseJdbcUrl(buildJdbcUrl(h2File), DialectType.H2)
        assertEquals("/tmp/h2data", h2FileParsed.database)
        assertEquals(ConnectionType.FILE_BASED, h2FileParsed.connectionType)

        val duck = config(
            dialect = DialectType.DUCKDB,
            connectionType = ConnectionType.EMBEDDED,
            database = "/tmp/analytics.duckdb",
        )
        val duckParsed = parseJdbcUrl(buildJdbcUrl(duck), DialectType.DUCKDB)
        assertEquals("/tmp/analytics.duckdb", duckParsed.database)
        assertEquals(ConnectionType.EMBEDDED, duckParsed.connectionType)

        val sqlite = config(
            dialect = DialectType.SQLITE,
            connectionType = ConnectionType.FILE_BASED,
            database = "/tmp/app.db",
        )
        val sqliteParsed = parseJdbcUrl(buildJdbcUrl(sqlite), DialectType.SQLITE)
        assertEquals("/tmp/app.db", sqliteParsed.database)
        assertEquals(ConnectionType.FILE_BASED, sqliteParsed.connectionType)
    }

    @Test
    fun `parse keeps query parameters out of the database name`() {
        val parts = parseJdbcUrl(
            "jdbc:mysql://root@localhost:3306/shop?useSSL=false&serverTimezone=UTC",
            DialectType.MYSQL,
        )

        assertEquals("shop", parts.database)
        assertEquals("localhost", parts.host)
        assertEquals("3306", parts.port)
        assertEquals("root", parts.username)
        assertEquals("", parts.password)
    }

    @Test
    fun `parse returns unknown type for blank or foreign urls`() {
        // 空 URL / 方言不匹配 → 无字段、类型 UNKNOWN（持久化加载据此回退到方言默认类型）
        assertEquals(ConnectionType.UNKNOWN, parseJdbcUrl("", DialectType.MYSQL).connectionType)
        assertEquals(ConnectionType.UNKNOWN, parseJdbcUrl("   ", DialectType.H2).connectionType)
        assertEquals(
            ConnectionType.UNKNOWN,
            parseJdbcUrl("jdbc:mysql://localhost:3306/shop", DialectType.SQLITE).connectionType,
        )
        assertEquals(
            ConnectionType.UNKNOWN,
            parseJdbcUrl("jdbc:h2:tcp://localhost/~/test", DialectType.H2).connectionType,
        )
    }

    @Test
    fun `switching dialect resets fields and yields a url for the new dialect`() {
        val mysql = config(dialect = DialectType.MYSQL, database = "shop", username = "root")

        val h2 = mysql.withDialect(DialectType.H2)
        assertEquals(DialectType.H2, h2.dialect)
        assertEquals(ConnectionType.IN_MEMORY, h2.connectionType)
        // 嵌入式方言不复用原库名 / 凭据 —— 空库名时 URL 也为空（流程要求补齐）
        assertEquals("", h2.database)
        assertEquals("", h2.jdbcUrl)

        val pg = mysql.withDialect(DialectType.POSTGRESQL)
        assertEquals("localhost", pg.host)
        assertEquals(5432, pg.port)
        assertEquals("shop", pg.database)
        assertEquals("root", pg.username, "用户名跨方言保留")
        assertEquals("", pg.password, "切换方言清空密码")
        assertTrue(
            pg.jdbcUrl.startsWith("jdbc:postgresql://root@localhost:5432/shop"),
            "url=${pg.jdbcUrl}",
        )
    }

    @Test
    fun `switching connection type rewrites the url shape`() {
        val memory = config(
            dialect = DialectType.H2,
            connectionType = ConnectionType.IN_MEMORY,
            database = "testdb",
        )

        val file = memory.withConnectionType(ConnectionType.FILE_BASED)

        assertEquals(ConnectionType.FILE_BASED, file.connectionType)
        assertEquals("jdbc:h2:file:testdb", file.jdbcUrl)
    }
}
