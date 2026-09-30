package com.kxxnzstdsw.loader

import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertTrue

/**
 * 回归测试 —— 坏插件 JAR 不得拖垮引擎启动。
 *
 * `ServiceLoader` 的迭代器在遇到无法加载的 provider 时抛 `ServiceConfigurationError`。
 * 修复前两个 loader 的 try/catch 都写在循环**体内**，护不住 `hasNext()/next()`，异常一路
 * 逃出 `loadFromDir` —— 用户往 `drivers/` 或 `dialects/` 里放一个损坏 / 版本不兼容的
 * JAR，整个引擎就起不来。
 *
 * 修法要点（不要退回「把整个循环包一层 try」）：JDK 每读一条 provider 就前进一格，
 * 因此 `next()` 抛错后**可以继续**扫出后面的合法 provider；把整个循环包起来会在第一个
 * 坏条目处放弃其后所有插件。守卫必须落在 `hasNext()` / `next()` 两次调用上。
 */
class BrokenPluginResilienceTest {

    /** 造一个 `META-INF/services` 指向不存在类的坏插件 JAR。 */
    private fun brokenJar(dir: File, serviceFile: String, missingClass: String): File {
        dir.mkdirs()
        val jar = File(dir, "broken-${serviceFile.substringAfterLast('.')}.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/$serviceFile"))
            out.write(missingClass.toByteArray())
            out.closeEntry()
        }
        jar.deleteOnExit()
        return jar
    }

    @Test
    fun `a broken driver jar does not abort driver loading`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sundays-probe-drv-${System.nanoTime()}")
        brokenJar(dir, "java.sql.Driver", "com.nonexistent.NoSuchDriver")

        // 修复前：这里抛 ServiceConfigurationError
        DriverLoader.loadFromDir(dir)
        dir.deleteRecursively()
    }

    @Test
    fun `a broken dialect jar does not abort dialect loading`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sundays-probe-dia-${System.nanoTime()}")
        brokenJar(dir, "com.kxxnzstdsw.dialect.DatabaseDialect", "com.nonexistent.NoSuchDialect")

        // 修复前：这里抛 ServiceConfigurationError
        DialectLoader.loadFromDir(dir)
        dir.deleteRecursively()
    }

    /** 坏插件在旁时，classpath 上已有的方言仍必须加载成功（坏的不影响好的）。 */
    @Test
    fun `valid dialects still load when a broken jar sits beside them`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sundays-probe-mix-${System.nanoTime()}")
        brokenJar(dir, "com.kxxnzstdsw.dialect.DatabaseDialect", "com.nonexistent.NoSuchDialect")
        val before = DialectLoader.getAllDialects().size

        DialectLoader.loadFromDir(dir)

        assertTrue(
            DialectLoader.getAllDialects().size >= before,
            "坏插件不应导致已注册方言丢失",
        )
        dir.deleteRecursively()
    }
}
