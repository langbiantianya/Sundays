package com.kxxnzstdsw.sundays.connection

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * 连接配置持久化测试 —— 落盘只有 `jdbcUrl` + 凭据，其余字段必须在加载时重建；
 * v1（含 `host` / `port` / `database` / `filePath`）文件要能迁移成 v2。
 *
 * `user.home` 指向临时目录，读写的是测试自己的 `connection.json`。
 */
class ConnectionStorageTest {

    private lateinit var tempHome: Path
    private lateinit var originalHome: String

    @BeforeTest
    fun redirectHome() {
        tempHome = Files.createTempDirectory("sundays-storage-test")
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.toString())
    }

    @AfterTest
    fun restoreHome() {
        System.setProperty("user.home", originalHome)
    }

    private fun configFile(): Path = tempHome.resolve(".config/sundays/connection.json")

    /** 字段 → URL：模拟向导保存前的状态（URL 由字段折算） */
    private fun ConnectionConfig.withUrl(): ConnectionConfig = copy(jdbcUrl = buildJdbcUrl(this))

    @Test
    fun `round trip restores embedded fields from the url`() {
        val sqlite = ConnectionConfig(
            id = "c1",
            name = "本地 SQLite",
            dialect = DialectType.SQLITE,
            connectionType = ConnectionType.FILE_BASED,
            database = "/tmp/app.db",
        ).withUrl()
        val h2 = ConnectionConfig(
            id = "c2",
            name = "内存 H2",
            dialect = DialectType.H2,
            connectionType = ConnectionType.IN_MEMORY,
            database = "testdb",
        ).withUrl()

        ConnectionStorage.save(ConnectionList(connections = listOf(sqlite, h2)))
        val loaded = ConnectionStorage.load().connections.associateBy { it.id }

        val loadedSqlite = loaded.getValue("c1")
        assertEquals("/tmp/app.db", loadedSqlite.database)
        assertEquals(ConnectionType.FILE_BASED, loadedSqlite.connectionType)
        assertEquals("jdbc:sqlite:/tmp/app.db", loadedSqlite.jdbcUrl)

        val loadedH2 = loaded.getValue("c2")
        assertEquals("testdb", loadedH2.database)
        assertEquals(ConnectionType.IN_MEMORY, loadedH2.connectionType)
        assertEquals("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE", loadedH2.jdbcUrl)
    }

    @Test
    fun `upsert and delete keep the file consistent`() {
        val mysql = ConnectionConfig(
            id = "m1",
            name = "本地 MySQL",
            dialect = DialectType.MYSQL,
            host = "localhost",
            port = 3306,
            database = "shop",
            username = "root",
            jdbcUrl = "jdbc:mysql://root@localhost:3306/shop?useSSL=false",
        )
        ConnectionStorage.upsert(mysql)
        assertEquals(1, ConnectionStorage.load().connections.size)

        // 同名 id 更新而不是追加
        ConnectionStorage.upsert(mysql.copy(name = "改名后"))
        val afterUpdate = ConnectionStorage.load().connections.single()
        assertEquals("改名后", afterUpdate.name)

        ConnectionStorage.delete("m1")
        assertTrue(ConnectionStorage.load().connections.isEmpty())
    }

    @Test
    fun `v1 file without jdbcUrl is migrated using its derived fields`() {
        val legacy = """
            {
              "connections": [
                {
                  "id": "old1",
                  "name": "旧 SQLite",
                  "dialect": "SQLITE",
                  "host": "",
                  "port": 0,
                  "database": "",
                  "username": "",
                  "password": "",
                  "connectionType": "FILE_BASED",
                  "filePath": "/tmp/legacy.db",
                  "jdbcUrl": "",
                  "createdAt": 1,
                  "updatedAt": 2
                }
              ],
              "version": 1
            }
        """.trimIndent()
        val file = configFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, legacy)

        val migrated = ConnectionStorage.load().connections.single()

        assertEquals("old1", migrated.id)
        assertEquals("jdbc:sqlite:/tmp/legacy.db", migrated.jdbcUrl)
        assertEquals("/tmp/legacy.db", migrated.database)

        // 迁移后回写为 v2：不再包含 v1 派生字段
        val rewritten = Files.readString(file)
        assertTrue(rewritten.contains("\"version\": 2"), rewritten)
        assertTrue(!rewritten.contains("filePath"), rewritten)
        assertTrue(rewritten.contains("jdbc:sqlite:/tmp/legacy.db"), rewritten)
    }

    @Test
    fun `missing file loads as empty list`() {
        assertTrue(ConnectionStorage.load().connections.isEmpty())
    }
}
