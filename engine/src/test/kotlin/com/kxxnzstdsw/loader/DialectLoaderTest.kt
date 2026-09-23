package com.kxxnzstdsw.loader

import com.kxxnzstdsw.dialect.DuckDBDialect
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dialect.MySQLDialect
import com.kxxnzstdsw.dialect.PostgreSQLDialect
import com.kxxnzstdsw.dialect.SQLiteDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DialectLoader 单元测试 — 验证 SPI 加载器能正确发现 dialects/ 目录中的 JAR，
 * 并能通过 registerForTesting 直接注入方言。
 */
class DialectLoaderTest {

    @AfterEach
    fun tearDown() {
        DialectLoader.closeAll()
    }

    @Test
    fun `loadFromDir discovers dialects from build artifacts`() {
        val dirs = listOf(
            File("engine/build/libs/dialects"),
            File("../engine/build/libs/dialects"),
            File("../../engine/build/libs/dialects"),
            File("build/libs/dialects")
        )
        val dir = dirs.firstOrNull { it.isDirectory }
        if (dir == null) {
            // 没有 dialects 目录，跳过（开发环境可能没构建）
            println("Skipping: no dialects/ directory found in build artifacts")
            return
        }

        DialectLoader.loadFromDir(dir)

        // 至少能拿到一个方言
        val drivers = listOf("Mysql", "Postgresql", "H2")
        val found = drivers.filter { try {
            DialectLoader.getDialect(it); true
        } catch (_: Exception) { false } }
        assertTrue(found.isNotEmpty(),
            "spike: 至少一个方言应被加载。在 ${dir.absolutePath} 下找到：${dir.listFiles()?.map { it.name }}")
    }

    @Test
    fun `loadFromDir registers dialects from the application classpath when no directory exists`() {
        // Direct 模式（desktopApp）：dialect-* 模块随应用类路径加载，磁盘上没有 dialects/ 目录
        DialectLoader.loadFromDir(File("build/no-such-dialects-dir"))

        val drivers = DialectLoader.getAllDialects().map { it.driverName }
        assertEquals(
            listOf("Duckdb", "H2", "Mysql", "Postgresql", "Sqlite"),
            drivers,
            "classpath SPI 应注册全部内置方言",
        )
        // 类路径方言同样支持 URL 反查 —— 仅凭 JDBC URL 建池的前提
        assertEquals("Sqlite", DialectLoader.getDialectByJdbcUrl("jdbc:sqlite:/tmp/x.db")?.driverName)
        assertEquals("H2", DialectLoader.getDialect("H2").driverName)
    }

    @Test
    fun `registerForTesting accepts arbitrary dialect and getDialect returns it`() {
        // H2Dialect 是个真实实现（继承所有抽象方法），把它当 "test dialect" 用
        DialectLoader.registerForTesting("H2", H2Dialect())
        val got = DialectLoader.getDialect("H2")
        assertEquals("H2", got.driverName)
        assertNotNull(got)
    }

    @Test
    fun `getDialect throws when no plugin registered`() {
        try {
            DialectLoader.getDialect("NonexistentDB")
            fail("应抛 UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("NonexistentDB"))
        }
    }

    @Test
    fun `closeAll clears registered dialects`() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.closeAll()
        try {
            DialectLoader.getDialect("H2")
            fail("closeAll 后应找不到 H2")
        } catch (e: UnsupportedOperationException) {
            // 预期
        }
    }

    @Test
    fun `registerForTesting overwrites previous entry`() {
        val first = H2Dialect()
        DialectLoader.registerForTesting("H2", first)
        assertEquals(first, DialectLoader.getDialect("H2"))

        val second = H2Dialect()
        DialectLoader.registerForTesting("H2", second)
        assertEquals(second, DialectLoader.getDialect("H2"))
    }

    @Test
    fun `getDialectByJdbcUrl resolves every registered dialect from its own prefix`() {
        listOf(H2Dialect(), MySQLDialect(), PostgreSQLDialect(), DuckDBDialect(), SQLiteDialect())
            .forEach { DialectLoader.registerForTesting(it.driverName, it) }

        val cases = mapOf(
            "jdbc:h2:mem:x;DB_CLOSE_DELAY=-1" to "H2",
            "jdbc:mysql://localhost:3306/db?useSSL=false" to "Mysql",
            "jdbc:postgresql://localhost:5432/db" to "Postgresql",
            "jdbc:duckdb:/tmp/x.duckdb" to "Duckdb",
            "jdbc:sqlite:/tmp/x.db" to "Sqlite",
        )
        cases.forEach { (url, expected) ->
            assertEquals(expected, DialectLoader.getDialectByJdbcUrl(url)?.driverName, "URL: $url")
        }
    }

    @Test
    fun `getDialectByJdbcUrl returns null for unknown or blank url`() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        assertNull(DialectLoader.getDialectByJdbcUrl("jdbc:oracle:thin:@localhost:1521/xe"))
        assertNull(DialectLoader.getDialectByJdbcUrl(""))
        assertNull(DialectLoader.getDialectByJdbcUrl("   "))
    }
}
