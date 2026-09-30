package com.kxxnzstdsw.sundays.connection

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试 —— 凭据文件的权限。
 *
 * `connection.json` 里存的是**明文数据库口令**。默认 umask 下 `createDirectories` /
 * `writeString` 产出 755 / 644，也就是同机任何用户都能读到口令。
 */
class ConnectionStoragePermissionsTest {

    @Test
    fun `saved credential file is owner only`() {
        val cfg = ConnectionConfig(
            id = "perm-test-1",
            name = "权限测试",
            dialect = DialectType.MYSQL,
            host = "db.example.com",
            port = 3306,
            username = "root",
            password = "super-secret",
            database = "shop",
        )
        assertTrue(ConnectionStorage.save(ConnectionList(connections = listOf(cfg))), "保存应成功")

        val file = Paths.get(System.getProperty("user.home"), ".config", "sundays", "connection.json")
        assertTrue(Files.exists(file), "凭据文件应存在")

        val perms = Files.getPosixFilePermissions(file)
        val mode = PosixFilePermissions.toString(perms)
        assertEquals("rw-------", mode, "凭据文件必须仅属主可读写，实际 $mode")
    }

    /** 凭据能被原样读回 —— 收紧权限不能破坏功能。 */
    @Test
    fun `credentials still round trip after permission tightening`() {
        // jdbcUrl 是持久化的真相源，构造时显式填好。
        // （不能用 withDialect —— 那是「切换方言」，resetFor 会清空 password / jdbcUrl。）
        val cfg = ConnectionConfig(
            id = "perm-test-2",
            name = "往返测试",
            dialect = DialectType.POSTGRESQL,
            host = "pg.example.com",
            port = 5432,
            username = "app",
            password = "p@ss/w:ord",
            database = "analytics",
            connectionType = ConnectionType.CLIENT_SERVER,
            jdbcUrl = buildJdbcUrl(
                ConnectionConfig(
                    id = "tmp",
                    name = "tmp",
                    dialect = DialectType.POSTGRESQL,
                    host = "pg.example.com",
                    port = 5432,
                    username = "app",
                    password = "p@ss/w:ord",
                    database = "analytics",
                )
            ),
        )
        ConnectionStorage.save(ConnectionList(connections = listOf(cfg)))
        val loaded = ConnectionStorage.load().connections.firstOrNull { it.id == "perm-test-2" }
        assertTrue(loaded != null, "应能读回刚保存的连接")
        assertEquals("app", loaded.username)
        // 含 @ / / / : 的密码必须原样保留
        assertEquals("p@ss/w:ord", loaded.password, "含特殊字符的密码必须无损往返")
        assertEquals("pg.example.com", loaded.host, "host 不应被密码里的 @ 截断")
        assertEquals(5432, loaded.port)
        assertEquals("analytics", loaded.database, "库名不应被密码里的 / 截断")
    }
}
