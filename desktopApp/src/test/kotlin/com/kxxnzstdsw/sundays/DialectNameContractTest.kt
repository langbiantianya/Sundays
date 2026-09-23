package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.DialectType
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DialectType.engineDriverName` ↔ 引擎方言注册名的跨模块一致性测试。
 *
 * 请求里的 proto `ConnectionConfig.driver` 必须等于 `DatabaseDialect.driverName`，否则
 * `SchemaHandler` / `TableHandler` 等按 `config.driver` 取方言的 handler 会抛
 * `No dialect plugin loaded for driver: X`。前端枚举常量名是全大写（`MYSQL`），
 * 引擎用 `Mysql` —— 两者**大小写不同**，所以需要一个显式映射并把它钉住。
 *
 * 本测试直接向引擎索取已注册方言（`SYSTEM.LIST_DRIVERS` 的数据源），任何一侧改名都会失败。
 */
class DialectNameContractTest {

    @Before
    fun setUp() {
        // IdbEngine bootstrap 是全局一次性的，其它测试类调过 engine.close() 会清空方言表。
        // loadFromDir 会重新扫一遍应用类路径 SPI（幂等，已存在的名字不覆盖）。
        IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
    }

    @Test
    fun `every DialectType maps to a driver name the engine actually registers`() {
        val registered = SystemHandler.listDrivers().itemsList.map { it.driverName }.toSet()
        assertTrue(
            registered.isNotEmpty(),
            "expected dialect plugins on the test classpath, found none",
        )

        val mappable = DialectType.entries - DialectType.UNKNOWN
        mappable.forEach { dialect ->
            assertTrue(
                dialect.engineDriverName in registered,
                "DialectType.$dialect maps to '${dialect.engineDriverName}', " +
                    "but the engine registered only $registered",
            )
        }
    }

    @Test
    fun `engineDriverName is not the raw enum name for dialects whose casing differs`() {
        // 钉住具体的错法：直接用 Enum.name 会在引擎侧查不到方言
        assertEquals("Mysql", DialectType.MYSQL.engineDriverName)
        assertEquals("Postgresql", DialectType.POSTGRESQL.engineDriverName)
        assertEquals("Duckdb", DialectType.DUCKDB.engineDriverName)
        assertEquals("Sqlite", DialectType.SQLITE.engineDriverName)
        assertEquals("H2", DialectType.H2.engineDriverName)
    }
}
