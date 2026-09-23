package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据库浏览屏幕状态机的烟雾测试 —— 不渲染 Compose UI, 只驱动 [DatabaseBrowserState].
 *
 * 验证:
 * - `refreshDatabases` 拉取数据库列表
 * - `toggleDatabase` + 内部 `loadTables` 拉取表列表
 * - `openTab` 拉取表预览, 同一表再次打开不会创建重复标签页
 * - `closeTab` 修正 selectedTabIndex
 *
 * 使用 H2 内存库(每个测试独立 DB); H2 dialect 通过 `:dialect-h2` 的 SPI 在
 * `IdbEngine()` bootstrap 时自动加载.
 */
class DatabaseBrowserFlowTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var dbName: String

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-db-browser-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // 注: 其它测试 (ConnectionManagerFlowTest) 调 engine.close() 后 DialectLoader
        // 会被清空; IdbEngine bootstrap 是幂等的, 重新构造不会再次扫描类路径.
        // 这里用 registerForTesting 把 H2 dialect 显式注入, 让 SCHEMA.LIST / TABLE.LIST
        // 等走 URL scheme 的请求能找到方言.
        DialectLoader.registerForTesting("H2", H2Dialect())

        dbName = "bdbtest_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

        // 建测试表 + 写入样本数据
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(64))")
                stmt.executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, total INT)")
                stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')")
                stmt.executeUpdate("INSERT INTO orders VALUES (1, 100), (2, 250), (3, 50)")
            }
        }
    }

    @After
    fun tearDown() {
        // 释放连接池 —— 必须清, 否则后续测试类 (ConnectionManagerFlowTest 的
        // activePoolCount 断言) 会看到累计的池数量, 出现 expected 1 but was 10.
        // 用 PoolManager.closeAll() 而非 engine.close(): 后者会一并清空方言/驱动注册表,
        // 而 IdbEngine bootstrap 是全局幂等的 (AtomicBoolean 只置一次 true),
        // 后续测试类的 IdbEngine() 构造不会再扫描 SPI, 会引发
        // "No dialect plugin matches JDBC URL".
        try { PoolManager.closeAll() } catch (_: Exception) {}
        // 删除测试库 (避免同进程内 H2 mem 库名冲突)
        try { DriverManager.getConnection(jdbcUrl, "sa", "").use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } } } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    /**
     * 构造一个 [DatabaseBrowserState] 并把当前连接注入 —— 测试场景下跳过 UI 渲染.
     */
    private fun newBrowser(): DatabaseBrowserState {
        val state = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        state.bindConnection(TestConnectionFactory.build(jdbcUrl))
        return state
    }

    @Test
    fun `refreshDatabases loads at least one database entry`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()

        withTimeout(5_000) {
            while (state.loadingDatabases) {
                kotlinx.coroutines.delay(50)
            }
        }

        assertNull(state.errorMessage, "expected no error, got: ${state.errorMessage}")
        assertTrue(state.databases.isNotEmpty(), "expected at least one database, got: ${state.databases}")
    }

    @Test
    fun `toggleDatabase loads table list and openTab creates preview`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        withTimeout(5_000) {
            while (state.loadingDatabases) kotlinx.coroutines.delay(50)
        }
        val db = state.databases.first()
        state.toggleDatabase(db)

        withTimeout(5_000) {
            while (db in state.loadingTables) kotlinx.coroutines.delay(50)
        }
        assertNull(state.tableLoadError[db])
        val tables = state.tablesByDatabase[db]
        assertNotNull(tables, "table list should be loaded for $db")
        // H2 把未引用的标识符归一为大写 —— 大小写不敏感比对
        val upper = tables.map { it.uppercase() }.toSet()
        assertTrue("USERS" in upper, "users table missing: $tables")
        assertTrue("ORDERS" in upper, "orders table missing: $tables")

        // 打开 users 预览 (H2 大写)
        val usersTable = tables.first { it.uppercase() == "USERS" }
        state.openTab(db, usersTable)
        val usersTab = state.tabs.single { it.tableName.uppercase() == "USERS" }
        withTimeout(5_000) {
            while (usersTab.loading) kotlinx.coroutines.delay(50)
        }
        assertNull(usersTab.error, "users tab error: ${usersTab.error}")
        assertEquals(2L, usersTab.total, "users total rows")
        assertEquals(2, usersTab.rows.size, "users row count")
        // 列至少含 id 和 name (H2 大写)
        val keys = usersTab.columns.map { it.key.uppercase() }.toSet()
        assertTrue("ID" in keys && "NAME" in keys, "expected id/name columns, got: $keys")
        // 数据内容核对 (H2 NAME 列名 = NAME, value "Alice")
        val alice = usersTab.rows.first { (it.cells["NAME"] as? String) == "Alice" }
        // 引擎 DataHandler.buildRow 对非 LOB 列一律走 rs.getString —— 单元格值是字符串.
        assertEquals("1", alice.id.toString(), "Alice's id should be 1")
    }

    @Test
    fun `opening the same table twice does not create a duplicate tab`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        withTimeout(5_000) {
            while (state.loadingDatabases) kotlinx.coroutines.delay(50)
        }
        val db = state.databases.first()
        state.toggleDatabase(db)
        withTimeout(5_000) {
            while (db in state.loadingTables) kotlinx.coroutines.delay(50)
        }
        val tableName = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }

        state.openTab(db, tableName)
        state.openTab(db, tableName)
        state.openTab(db, tableName)
        assertEquals(1, state.tabs.size, "duplicate opens should not add tabs")
        assertEquals(0, state.selectedTabIndex, "second open should re-select the existing tab")
    }

    @Test
    fun `opening a different table creates a second tab`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        withTimeout(5_000) {
            while (state.loadingDatabases) kotlinx.coroutines.delay(50)
        }
        val db = state.databases.first()
        state.toggleDatabase(db)
        withTimeout(5_000) {
            while (db in state.loadingTables) kotlinx.coroutines.delay(50)
        }
        val users = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }
        val orders = state.tablesByDatabase[db]!!.first { it.uppercase() == "ORDERS" }

        state.openTab(db, users)
        state.openTab(db, orders)
        assertEquals(2, state.tabs.size, "different tables -> distinct tabs")
        assertEquals(1, state.selectedTabIndex, "selectedTabIndex should be the latest")
        val titles = state.tabs.map { it.tableName.uppercase() }.toSet()
        assertEquals(setOf("USERS", "ORDERS"), titles)
    }

    @Test
    fun `closeTab adjusts selectedTabIndex`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        withTimeout(5_000) {
            while (state.loadingDatabases) kotlinx.coroutines.delay(50)
        }
        val db = state.databases.first()
        state.toggleDatabase(db)
        withTimeout(5_000) {
            while (db in state.loadingTables) kotlinx.coroutines.delay(50)
        }
        val users = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }
        val orders = state.tablesByDatabase[db]!!.first { it.uppercase() == "ORDERS" }

        state.openTab(db, users)
        state.openTab(db, orders)

        // selectedTabIndex = 1 (orders)
        assertEquals(1, state.selectedTabIndex)

        // 关闭最后一个 -> 选中回退到 0
        state.closeTab(1)
        assertEquals(1, state.tabs.size)
        assertEquals(0, state.selectedTabIndex)

        // 关闭最后一个标签 -> selectedTabIndex = -1
        state.closeTab(0)
        assertEquals(0, state.tabs.size)
        assertEquals(-1, state.selectedTabIndex)
    }
}

/** 测试期构造 ConnectionConfig —— 跳过 JdbcUrl 折算 */
private object TestConnectionFactory {
    fun build(jdbcUrl: String) = com.kxxnzstdsw.sundays.connection.ConnectionConfig(
        id = "test-${System.nanoTime()}",
        name = "TestH2",
        dialect = com.kxxnzstdsw.sundays.connection.DialectType.H2,
        username = "sa",
        password = "",
        jdbcUrl = jdbcUrl,
    )
}
