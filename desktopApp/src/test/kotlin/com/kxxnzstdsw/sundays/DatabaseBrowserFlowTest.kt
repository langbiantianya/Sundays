package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * 回归：`loading` 落定的瞬间数据必须已经就位。
     *
     * `loadTabPreview` 曾把 `tab.loading = false` 写在写 `tab.rows` **之前**。二者是各自独立的
     * 快照状态，即使同一个协程里连着写也是两次提交 —— 于是「轮询 loading 等它落定」的调用方
     * 可能在两次提交之间醒来，读到 `loading == false` + 空 rows（症状：间歇性
     * `users row count expected:<2> but was:<0>`）。
     *
     * 这里在测试线程上**无挂起地紧轮询** `tab.loading`（写协程跑在 `Dispatchers.Default`，是另一个线程），
     * 把「loading 落定」与「那一瞬间 rows 的规模」一起捕获。用 `delay` 轮询（哪怕 1ms）会错过这个
     * 微秒级窗口 —— 那正是原缺陷只在低概率下暴露的原因。修复后 `loading` 最后落定，观测必为满数据。
     */
    @Test
    fun `preview data is populated by the time loading flag clears`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        withTimeout(5_000) {
            while (state.loadingDatabases) kotlinx.coroutines.delay(1)
        }
        val db = state.databases.first()
        state.toggleDatabase(db)
        withTimeout(5_000) {
            while (db in state.loadingTables) kotlinx.coroutines.delay(1)
        }
        val table = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }

        state.openTab(db, table)
        val tab = state.tabs.single { it.tableName.uppercase() == "USERS" }
        // 紧轮询到 loading 落定，并在**同一瞬间**记录 rows 规模
        var rowsAtClear = -1
        withTimeout(5_000) {
            while (true) {
                if (!tab.loading) {
                    rowsAtClear = tab.rows.size
                    break
                }
            }
        }

        // loading 一旦为 false，行数据必须已经在位（不允许出现中间态）
        assertEquals(2, rowsAtClear, "rows must be populated once loading clears")
        assertTrue(tab.columns.isNotEmpty(), "columns must be populated once loading clears")
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

    // ------------------------------------------------------------------------
    // SQL 工作台（状态层契约）
    // ------------------------------------------------------------------------

    @Test
    fun `selectPane switches between table, SQL and generate workbenches`() {
        val state = newBrowser()
        assertEquals(BrowserPane.TABLE, state.activePane, "初始应展示表预览")
        state.selectPane(BrowserPane.SQL)
        assertEquals(BrowserPane.SQL, state.activePane)
        state.selectPane(BrowserPane.GENERATE)
        assertEquals(BrowserPane.GENERATE, state.activePane)
        state.selectPane(BrowserPane.TABLE)
        assertEquals(BrowserPane.TABLE, state.activePane, "应能切回表预览")
    }

    @Test
    fun `executeSql SELECT populates result rows from streaming frames`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        state.sqlEditorText = "SELECT id, name FROM users ORDER BY id"
        state.executeSql(state.sqlEditorText)

        // 异步流式收集 —— 等到 sqlRunning 复位
        withTimeout(5_000) {
            while (state.sqlRunning) delay(20)
        }
        assertNull(state.sqlError, "应无错误: ${state.sqlError}")
        assertEquals(2, state.sqlRowCount, "users 表有 2 行")
        assertEquals(2, state.sqlResultRows.size)
        assertTrue(
            state.sqlResultColumns.any { it.key.equals("id", ignoreCase = true) },
            "列名应包含 id",
        )
        assertTrue(
            state.sqlResultColumns.any { it.key.equals("name", ignoreCase = true) },
            "列名应包含 name",
        )
        assertNull(state.sqlAffectedRows, "SELECT 不应填 affected_rows")
    }

    @Test
    fun `executeSql empty text sets error without calling engine`() = runBlocking {
        val state = newBrowser()
        state.executeSql("   \n   ")
        assertEquals("SQL 为空", state.sqlError)
        assertFalse(state.sqlRunning)
    }

    @Test
    fun `executeSql DDL surfaces affected_rows instead of rows`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        state.sqlEditorText = "CREATE TABLE sqlbench_tmp (id INT)"
        state.executeSql(state.sqlEditorText)

        withTimeout(5_000) {
            while (state.sqlRunning) delay(20)
        }
        assertNull(state.sqlError, "应无错误: ${state.sqlError}")
        assertNotNull(state.sqlAffectedRows, "DDL 应填 affected_rows")
        assertEquals(0, state.sqlResultRows.size, "DDL 不应产生 SELECT 行帧")
    }

    @Test
    fun `bindConnection clears SQL workbench state when switching connections`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        state.sqlEditorText = "SELECT 1"
        state.executeSql(state.sqlEditorText)
        withTimeout(5_000) { while (state.sqlRunning) delay(20) }
        assertTrue(state.sqlResultRows.isNotEmpty() || state.sqlAffectedRows != null)

        // 切到新连接（不同 db 名）
        val newJdbc = "jdbc:h2:mem:bdbtest2_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(newJdbc, "sa", "").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
        }
        state.bindConnection(TestConnectionFactory.build(newJdbc))

        assertEquals("", state.sqlEditorText, "切换连接应清空编辑器文本")
        assertEquals(0, state.sqlResultRows.size)
        assertNull(state.sqlAffectedRows)
        assertNull(state.sqlError)
        assertFalse(state.sqlRunning)
    }

    // ------------------------------------------------------------------------
    // 造数工作台（状态层契约）
    // ------------------------------------------------------------------------

    @Test
    fun `executeGenerate inserts rows via Lua script and reports per-script stats`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.GENERATE)
        val script = state.currentGenerateScript()!!
        script.editor.setText("for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end")

        state.executeGenerate()
        withTimeout(10_000) { while (state.generateRunning) delay(20) }

        assertNull(state.generateError, "应无错误: ${state.generateError}")
        assertEquals(3L, state.generateTotalInserted, "脚本应插入 3 行")
        assertEquals(1, state.generateTablesProcessed, "终止帧应回填已处理脚本数")
        assertEquals("orders", script.lastTable, "进度帧应带回目标表名")

        // 引擎确实把行写进了库（造数不是只报数）
        val count = DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM orders").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(6, count, "orders 原有 3 行 + 造数 3 行")
    }

    @Test
    fun `generate scripts keep their own editor state across pane switches`() {
        val state = newBrowser()
        state.selectPane(BrowserPane.GENERATE)
        val first = state.currentGenerateScript()!!
        first.editor.setText("-- 第一个脚本")

        state.addGenerateScript()
        val second = state.currentGenerateScript()!!
        assertEquals(2, state.generateScripts.size)
        assertEquals(1, state.selectedGenerateIndex, "新增脚本后应选中它")

        // 切到表预览再切回来：两个脚本各自的文本都还在，选中项不变
        state.selectPane(BrowserPane.TABLE)
        state.selectPane(BrowserPane.GENERATE)
        assertEquals("-- 第一个脚本", first.editor.text)
        assertEquals(1, state.selectedGenerateIndex, "切 pane 不应改动选中脚本")
        assertTrue(second.editor.text.isNotBlank(), "第二个脚本保留模板内容")

        // 删掉选中脚本后选中项回退
        state.removeGenerateScript(1)
        assertEquals(1, state.generateScripts.size)
        assertEquals(0, state.selectedGenerateIndex)
    }

    @Test
    fun `executeGenerate with blank scripts reports error without calling engine`() {
        val state = newBrowser()
        val script = state.currentGenerateScript()!!
        script.editor.setText("   ")
        state.executeGenerate()
        assertEquals("造数脚本为空", state.generateError)
        assertFalse(state.generateRunning)
    }

    @Test
    fun `bindConnection clears generate workbench state when switching connections`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.GENERATE)
        val script = state.currentGenerateScript()!!
        script.editor.setText("for i = 1, 2 do insert('orders', {id = 200 + i, total = i}) end")
        state.executeGenerate()
        withTimeout(10_000) { while (state.generateRunning) delay(20) }
        assertTrue(state.generateTotalInserted > 0)

        val newJdbc = "jdbc:h2:mem:bdbtest3_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(newJdbc, "sa", "").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
        }
        state.bindConnection(TestConnectionFactory.build(newJdbc))

        assertEquals(1, state.generateScripts.size, "切换连接应复位为单个默认脚本")
        assertEquals(0, state.generateTotalInserted)
        assertEquals(0, state.generateTablesProcessed)
        assertNull(state.generateError)
        assertFalse(state.generateRunning)
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
