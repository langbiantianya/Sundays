package com.kxxnzstdsw.sundays.connection

import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试 —— 凭据文件的权限。
 *
 * `connection.json` 里存的是**明文数据库口令**。默认 umask 下 `createDirectories` /
 * `writeString` 产出 755 / 644，也就是同机任何用户都能读到口令。
 *
 * ## ⚠️ 为什么必须隔离 `user.home`
 *
 * 本类**直接调 `ConnectionStorage.save(...)`**，而它写的是 `~/.config/sundays/connection.json`
 * ——**真实用户的那一份**。原版没隔离，于是：
 *
 * - 跑一次全量测试，用户的 `connection.json` 里就多出 `perm-test-1` / `perm-test-2`
 *   两条指向 `db.example.com` / `pg.example.com` 的假连接；
 * - 更糟的是 `save` 是**整体覆盖**语义 —— 一条只含测试数据的写入，就把用户真实的
 *   连接列表**清空了**。
 *
 * 这不是假设：跑完一轮全量后，真实 `connection.json` 里就只剩 `perm-test-2`。
 * 断言里读路径用的也是同一个 `user.home`，所以只要**写**之前先改掉它，两边自然一致。
 */
class ConnectionStoragePermissionsTest {

    private lateinit var tempHome: Path
    private lateinit var originalHome: String

    @Before
    fun redirectHome() {
        tempHome = Files.createTempDirectory("sundays-storage-perm-test")
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.toString())
    }

    @After
    fun restoreHome() {
        System.setProperty("user.home", originalHome)
        runCatching { tempHome.toFile().deleteRecursively() }
    }

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

        // ⚠️ POSIX 权限是**文件系统**的属性，Windows 上压根没有这个概念（访问控制走 ACL）。
        // 这里必须 **skip** 而不是断言失败：否则等于要求 Windows 实现它做不到的事，
        // 于是这条与平台无关的「凭据要收紧」契约会被永远标红 —— 而红久了就没人再看它，
        // 真正该守的 POSIX 平台反而没人守。
        //
        // 顺带说明：把「保存本身成功」和「权限收紧」拆成两件事之后，本条红的原因
        // 就从「Windows 存不下连接」（真缺陷，已修）变成了「Windows 没有 POSIX 权限」
        // （平台事实，跳过即可）—— **同一处红，根因完全不同**，这正是不看栈底
        // 只看断言消息时最容易搞混的地方。
        assumeTrue(
            "当前文件系统不支持 POSIX 权限（Windows 上访问控制走 ACL），跳过权限断言",
            runCatching { Files.getPosixFilePermissions(file) }.isSuccess,
        )

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
