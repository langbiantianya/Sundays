/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val sqlite =
            ConnectionConfig(
                id = "c1",
                name = "本地 SQLite",
                dialect = DialectType.SQLITE,
                connectionType = ConnectionType.FILE_BASED,
                database = "/tmp/app.db",
            ).withUrl()
        val h2 =
            ConnectionConfig(
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
        val mysql =
            ConnectionConfig(
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
        val legacy =
            """
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

        // 迁移后回写为当前格式（v3）：不再包含 v1 派生字段
        val rewritten = Files.readString(file)
        assertTrue(rewritten.contains("\"version\": 3"), rewritten)
        assertTrue(!rewritten.contains("filePath"), rewritten)
        assertTrue(rewritten.contains("jdbc:sqlite:/tmp/legacy.db"), rewritten)
    }

    /**
     * v2 文件（无 `authMethod` / `properties`）要被 v3 读出来，并**回写成 v3**。
     *
     * v3 只比 v2 多两个带默认值的字段，所以 v2 文件**不需要迁移也能读** ——
     * 迁移的价值只是「顺手把文件写回 v3」，免得下次又按 v2 读一遍。
     * 这个用例盯的就是「别把老用户的连接读丢了」。
     */
    @Test
    fun `v2 file is read and rewritten as v3 with default auth method`() {
        val v2 =
            """
            {
              "connections": [
                {
                  "id": "v2a",
                  "name": "旧 MySQL",
                  "dialect": "MYSQL",
                  "jdbcUrl": "jdbc:mysql://localhost:3306/shop",
                  "username": "root",
                  "password": "666666",
                  "createdAt": 1,
                  "updatedAt": 2
                }
              ],
              "version": 2
            }
            """.trimIndent()
        val file = configFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, v2)

        val loaded = ConnectionStorage.load().connections.single()

        // 老记录一条不少，凭据与地址照旧
        assertEquals("v2a", loaded.id)
        assertEquals("旧 MySQL", loaded.name)
        assertEquals("root", loaded.username)
        assertEquals("666666", loaded.password)
        assertEquals("localhost", loaded.host)
        assertEquals(3306, loaded.port)
        assertEquals("shop", loaded.database)

        // 缺省的两个新字段取默认值：账密认证 + 无方言特有参数
        assertEquals(AuthMethod.PASSWORD, loaded.authMethod)
        assertTrue(loaded.properties.isEmpty())

        // 回写成 v3
        val rewritten = Files.readString(file)
        assertTrue(rewritten.contains("\"version\": 3"), rewritten)
        assertTrue(rewritten.contains("\"authMethod\""), rewritten)
    }

    /**
     * 自定义 URL 的连接存盘再加载，**必须仍然是自定义 URL**。
     *
     * 判据是「URL 前缀对不上这个方言」—— 一旦退回方言默认连接类型，
     * 用户那条「我自己写的 URL」就变成了「按主机/端口/库名连的另一种连接」，
     * 而自定义 URL 下的主机/端口/库名**本来就没有意义**（字段是空的）。
     */
    @Test
    fun `a custom url connection round trips as a custom url`() {
        val custom =
            ConnectionConfig(
                id = "c1",
                name = "自建库",
                dialect = DialectType.MYSQL,
                connectionType = ConnectionType.CUSTOM_URL,
                jdbcUrl = "jdbc:oracle:thin:@//db.example.invalid:1521/xe",
                username = "scott",
                password = "tiger",
            )

        ConnectionStorage.save(ConnectionList(connections = listOf(custom)))
        val loaded = ConnectionStorage.load().connections.single()

        assertEquals(ConnectionType.CUSTOM_URL, loaded.connectionType, "自定义 URL 加载后被认成了普通连接")
        assertEquals("jdbc:oracle:thin:@//db.example.invalid:1521/xe", loaded.jdbcUrl)
        // 凭据走独立字段，从不从 URL 反解
        assertEquals("scott", loaded.username)
        assertEquals("tiger", loaded.password)
        // 主机/库名对自定义 URL 没有意义 —— 保持为空，而不是解析出垃圾
        assertEquals("", loaded.host)
        assertEquals("", loaded.database)
    }

    @Test
    fun `missing file loads as empty list`() {
        assertTrue(ConnectionStorage.load().connections.isEmpty())
    }

    /**
     * 老配置里的 PG URL 带 userinfo —— 那正是「PG 连不上」的原因，加载时必须归一化。
     *
     * 这个用例盯的是**归一化不许偷走凭据**：URL 里的 `user:pass@` 被摘掉之后，
     * 用户名密码只能从 `PersistedConnectionConfig` 的独立字段来。少一个字段，
     * 用户的 PG 连接就会从「连不上」变成「连上但认证失败」—— 换了个错法而已。
     */
    @Test
    fun `legacy postgresql url is normalized on load without losing credentials`() {
        val legacy =
            """
            {
              "connections": [
                {
                  "id": "pg1",
                  "name": "旧 PG",
                  "dialect": "POSTGRESQL",
                  "jdbcUrl": "jdbc:postgresql://postgres:666666@192.168.1.5:5432/postgres",
                  "username": "postgres",
                  "password": "666666",
                  "createdAt": 1,
                  "updatedAt": 2
                }
              ],
              "version": 2
            }
            """.trimIndent()
        val file = configFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, legacy)

        val loaded = ConnectionStorage.load().connections.single()

        // 坏掉的 userinfo 段被摘掉 —— 这串 URL 直接交给驱动必然 UnknownHostException
        assertEquals("jdbc:postgresql://192.168.1.5:5432/postgres", loaded.jdbcUrl)
        // 凭据毫发无损：PG 连接靠它们认证
        assertEquals("postgres", loaded.username)
        assertEquals("666666", loaded.password)
        // 地址字段仍从 URL 重建得出
        assertEquals("192.168.1.5", loaded.host)
        assertEquals(5432, loaded.port)
        assertEquals("postgres", loaded.database)

        // 归一化只发生在内存里，不主动改写用户的磁盘文件
        assertTrue(Files.readString(file).contains("jdbc:postgresql://postgres:666666@"))
    }

    /** 新建 PG 连接时 URL 就不带 userinfo —— 落盘形状与内存一致。 */
    @Test
    fun `newly saved postgresql connection stores a credential free url`() {
        val pg =
            ConnectionConfig(
                id = "pg2",
                name = "新 PG",
                dialect = DialectType.POSTGRESQL,
                connectionType = ConnectionType.CLIENT_SERVER,
                host = "192.168.1.5",
                port = 5432,
                database = "postgres",
                username = "postgres",
                password = "666666",
            ).withUrl()

        assertEquals("jdbc:postgresql://192.168.1.5:5432/postgres", pg.jdbcUrl)

        ConnectionStorage.save(ConnectionList(connections = listOf(pg)))
        val loaded = ConnectionStorage.load().connections.single()
        assertEquals("jdbc:postgresql://192.168.1.5:5432/postgres", loaded.jdbcUrl)
        assertEquals("postgres", loaded.username)
        assertEquals("666666", loaded.password)
    }
}
