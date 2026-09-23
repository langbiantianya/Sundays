package com.kxxnzstdsw.loader

import com.kxxnzstdsw.dialect.DatabaseDialect
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.*

/**
 * 方言插件动态加载器。
 * 扫描指定目录中的 JAR 文件，通过 SPI（ServiceLoader）发现并注册所有 DatabaseDialect 实现。
 */
object DialectLoader {
    private val logger = LoggerFactory.getLogger(DialectLoader::class.java)
    private val dialects = mutableMapOf<String, DatabaseDialect>()
    private var dialectClassLoader: URLClassLoader? = null

    /**
     * 获取 JAR 包所在目录
     */
    private fun getJarDir(): File {
        val codeSource = DialectLoader::class.java.protectionDomain.codeSource
        return File(codeSource.location.toURI()).parentFile
    }

    /**
     * 加载方言插件 — 两条来源，目录插件覆盖同名 classpath 方言：
     *
     * 1. **应用类路径 SPI**（[loadFromClasspath]）—— Direct 模式 / 开发运行场景：`dialect-*` 模块
     *    作为普通依赖随应用类路径加载，服务文件位于 `META-INF/services/`。
     * 2. **插件目录**—— JAR 分发场景：扫描 [dir] 与 JAR 同级 `dialects/` 目录中的插件 JAR。
     *
     * 幂等：重复调用不会重复注册（同名以最后一次加载为准）。未做任何注册（既无目录也无
     * classpath 方言）时保持静默 —— 调用方在真正用到方言时才会拿到 `No dialect plugin ...` 错误。
     */
    fun loadFromDir(dir: File) {
        loadFromClasspath()

        val jarDialects = File(getJarDir(), "dialects")
        val candidates = listOf(dir.absoluteFile, jarDialects).filter { it.isDirectory }

        if (candidates.isEmpty()) {
            logger.debug("No dialects directory found (tried: ${dir.absolutePath}, ${jarDialects.absolutePath})")
            return
        }

        for (candidate in candidates) {
            loadFromSingleDir(candidate)
        }
    }

    /**
     * 从应用类路径发现方言（`ServiceLoader`，使用 [DialectLoader] 自身的类加载器）。
     *
     * 与目录加载的区别：这里不新建 `URLClassLoader`，方言类与 `DatabaseDialect` 接口由同一个
     * 类加载器解析 —— Direct 模式下 UI 与引擎同 JVM 时必须走这条路径，否则接口类型不匹配。
     * 已注册的同名方言不被覆盖（目录插件优先）。
     */
    private fun loadFromClasspath() {
        val loader = DialectLoader::class.java.classLoader ?: return
        val discovered = try {
            ServiceLoader.load(DatabaseDialect::class.java, loader).toList()
        } catch (e: Throwable) {
            // ServiceConfigurationError 等 —— 单个坏插件不应阻断引擎启动
            logger.warn("Classpath dialect scan failed: ${e.message}")
            return
        }

        var count = 0
        for (dialect in discovered) {
            if (dialects.containsKey(dialect.driverName)) continue
            dialects[dialect.driverName] = dialect
            count++
        }
        if (count > 0) {
            logger.info("Registered $count dialect plugin(s) from application classpath")
        } else {
            logger.debug("No dialect plugins found on application classpath")
        }
    }

    private fun loadFromSingleDir(dir: File) {
        val jars = dir.listFiles { f -> f.extension == "jar" } ?: return
        if (jars.isEmpty()) {
            logger.debug("No dialect JARs found in ${dir.absolutePath}")
            return
        }

        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        val parent = dialectClassLoader ?: Thread.currentThread().contextClassLoader
        val classLoader = URLClassLoader(urls, parent)

        val serviceLoader = ServiceLoader.load(DatabaseDialect::class.java, classLoader)
        var count = 0
        for (dialect in serviceLoader) {
            try {
                dialects[dialect.driverName] = dialect
                count++
                logger.info("Registered dialect plugin: ${dialect.javaClass.name} (driver=${dialect.driverName})")
            } catch (e: Exception) {
                logger.warn("Failed to register dialect: ${dialect.javaClass.name}", e)
            }
        }

        if (count > 0) {
            dialectClassLoader = classLoader
            logger.info("Loaded $count dialect plugin(s) from ${dir.absolutePath}")
        } else {
            classLoader.close()
            logger.debug("No valid dialect plugins found in ${dir.absolutePath}")
        }
    }

    /**
     * 获取指定驱动名的方言实例
     * @param driverName 驱动枚举名（如 "Mysql"、"Postgresql"）
     */
    fun getDialect(driverName: String): DatabaseDialect {
        return dialects[driverName]
            ?: throw UnsupportedOperationException("No dialect plugin loaded for driver: $driverName")
    }

    /**
     * 通过 JDBC URL 前缀反查方言实例（无匹配返回 null）。
     *
     * 前缀由 [DatabaseDialect.jdbcUrlPrefix] 声明；多个方言同时匹配时取**最长前缀**，
     * 以支持前缀嵌套（如 `jdbc:h2:` 与更具体的 `jdbc:h2:tcp:`）。
     */
    fun getDialectByJdbcUrl(jdbcUrl: String): DatabaseDialect? {
        val url = jdbcUrl.trim()
        if (url.isEmpty()) return null
        return dialects.values
            .filter { it.jdbcUrlPrefix.isNotBlank() && url.startsWith(it.jdbcUrlPrefix, ignoreCase = true) }
            .maxByOrNull { it.jdbcUrlPrefix.length }
    }

    /**
     * 列出所有已加载的方言实例（v2.8 新增 — 用于前端动态渲染连接表单）。
     *
     * 返回的是当前快照，按 driverName 排序保证稳定顺序。
     * 调用方不应持有此集合后假设它永远不会变（loadFromDir 可累积注册）；
     * 每次调用都会拿到最新注册表。
     */
    fun getAllDialects(): List<DatabaseDialect> {
        return dialects.values.sortedBy { it.driverName }
    }

    /**
     * 直接注册一个方言实例（用于测试 — 跳过 SPI 扫描）。
     */
    fun registerForTesting(driverName: String, dialect: DatabaseDialect) {
        dialects[driverName] = dialect
    }

    fun closeAll() {
        dialectClassLoader?.let {
            try { it.close() } catch (_: Exception) {}
        }
        dialectClassLoader = null
        dialects.clear()
    }
}
