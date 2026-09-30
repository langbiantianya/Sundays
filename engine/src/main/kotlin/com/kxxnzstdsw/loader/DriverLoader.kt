package com.kxxnzstdsw.loader

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver
import java.sql.DriverManager
import java.util.*

object DriverLoader {
    private val logger = LoggerFactory.getLogger(DriverLoader::class.java)
    private var driverClassLoader: URLClassLoader? = null

    fun getClassLoader(): ClassLoader? = driverClassLoader

    /**
     * 获取 JAR 包所在目录
     */
    private fun getJarDir(): File {
        val codeSource = DriverLoader::class.java.protectionDomain.codeSource
        return File(codeSource.location.toURI()).parentFile
    }

    /**
     * 加载指定目录下的 JDBC 驱动，同时查找 JAR 同级 drivers/ 和 CWD drivers/
     */
    fun loadFromDir(dir: File) {
        // 优先从 JAR 同级目录查找，其次从 CWD 查找
        val jarDrivers = File(getJarDir(), "drivers")
        val candidates = listOf(dir.absoluteFile, jarDrivers).filter { it.isDirectory }

        if (candidates.isEmpty()) {
            logger.debug("No drivers directory found (tried: ${dir.absolutePath}, ${jarDrivers.absolutePath})")
            return
        }

        for (candidate in candidates) {
            loadFromSingleDir(candidate)
        }
    }

    private fun loadFromSingleDir(dir: File) {
        val jars = dir.listFiles { f -> f.extension == "jar" } ?: return
        if (jars.isEmpty()) {
            logger.debug("No driver JARs found in ${dir.absolutePath}")
            return
        }

        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        val parent = driverClassLoader ?: Thread.currentThread().contextClassLoader
        // **单个** URLClassLoader 覆盖整个目录 —— 不要按 JAR 拆分。
        // 驱动实例是从这个 loader 加载的，注册之后仍会惰性加载 H2/MySQL 等内部类，
        // 提前关闭它会让这些类在真正建连时 NoClassDefFoundError。真实部署的 drivers/
        // 有 5 个 JAR，正是这条路径。
        val classLoader = URLClassLoader(urls, parent)

        var count = 0
        var failures = 0
        var consecutiveFailures = 0
        val iterator = ServiceLoader.load(Driver::class.java, classLoader).iterator()
        // ServiceLoader 的**迭代器**会抛 ServiceConfigurationError（META-INF/services 里
        // 声明了无法加载的类时）。原实现把 try/catch 写在循环体内，只护住 registerDriver，
        // 护不住 hasNext()/next() —— 异常一路逃出 loadFromDir 中断整个引擎启动。
        //
        // JDK 每读一条 provider 就前进一格，因此 next() 抛错后**可以继续**扫出后面的
        // 合法 provider（已实测：坏条目在前、好条目在后时，好条目仍被发现）。
        // hasNext() 抛错则不再继续 —— 此时无法安全判断是否还有内容，避免死循环。
        while (true) {
            val hasNext = try {
                iterator.hasNext()
            } catch (e: Throwable) {
                logger.warn("Aborting driver scan of ${dir.absolutePath} (hasNext failed): ${e.message}")
                break
            }
            if (!hasNext) break

            val driver = try {
                iterator.next()
            } catch (e: Throwable) {
                failures++
                logger.warn("Skipping unloadable JDBC driver entry in ${dir.absolutePath}: ${e.message}")
                // 防御：理论上 JDK 每次都会前进；若某实现不前进则在此止损
                if (++consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                    logger.warn("Too many consecutive driver failures, aborting scan of ${dir.absolutePath}")
                    break
                }
                continue
            }
            consecutiveFailures = 0
            try {
                DriverManager.registerDriver(driver)
                count++
                logger.info("Registered dynamic JDBC driver: ${driver.javaClass.name}")
            } catch (e: Exception) {
                logger.warn("Failed to register driver: ${driver.javaClass.name}", e)
            }
        }

        if (count > 0) {
            driverClassLoader = classLoader
            Thread.currentThread().contextClassLoader = classLoader
            logger.info("Loaded $count dynamic JDBC driver(s) from ${dir.absolutePath}")
        } else {
            classLoader.close()
            logger.warn(
                "No valid JDBC drivers found in ${dir.absolutePath}" +
                    if (failures == 0) "" else " (skipped $failures broken plugin entr(ies))"
            )
        }
    }

    /**
     * 连续失败上限 —— 仅用于防御「迭代器不前进」的病态实现，正常路径不会触及。
     * 取一个宽松值：真实目录里坏条目的数量远小于它。
     */
    private const val MAX_CONSECUTIVE_FAILURES = 1000

    fun closeAll() {
        driverClassLoader?.let {
            try { it.close() } catch (_: Exception) {}
        }
        driverClassLoader = null
    }
}
