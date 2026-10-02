package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.table.PageSize
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
                // `wide` 是**故意造的宽表**：默认每页 100 行时它只有一页，分页器就退化成装饰品，
                // 页码相关的缺陷在 2 行的 users 上一个都测不出来。25 行 / 每页 10 行 = 3 页。
                stmt.executeUpdate("CREATE TABLE wide (id INT PRIMARY KEY, tag VARCHAR(32))")
                (1..25).forEach { stmt.executeUpdate("INSERT INTO wide VALUES ($it, 'row$it')") }
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
        val sheet = state.currentSqlSheet()!!
        sheet.editor.setText("SELECT id, name FROM users ORDER BY id")
        state.executeSql()

        // 异步流式收集 —— 等到该 sheet 的 running 复位
        withTimeout(5_000) {
            while (sheet.running) delay(20)
        }
        assertNull(sheet.error, "应无错误: ${sheet.error}")
        assertEquals(2, sheet.rowCount, "users 表有 2 行")
        assertEquals(2, sheet.rows.size)
        assertTrue(
            sheet.columns.any { it.key.equals("id", ignoreCase = true) },
            "列名应包含 id",
        )
        assertTrue(
            sheet.columns.any { it.key.equals("name", ignoreCase = true) },
            "列名应包含 name",
        )
        assertNull(sheet.affectedRows, "SELECT 不应填 affected_rows")
    }

    @Test
    fun `executeSql empty text sets error without calling engine`() = runBlocking {
        val state = newBrowser()
        val sheet = state.currentSqlSheet()!!
        sheet.editor.setText("   \n   ")
        state.executeSql()
        assertEquals("SQL 为空", sheet.error)
        assertFalse(sheet.running)
    }

    @Test
    fun `executeSql DDL surfaces affected_rows instead of rows`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        val sheet = state.currentSqlSheet()!!
        sheet.editor.setText("CREATE TABLE sqlbench_tmp (id INT)")
        state.executeSql()

        withTimeout(5_000) {
            while (sheet.running) delay(20)
        }
        assertNull(sheet.error, "应无错误: ${sheet.error}")
        assertNotNull(sheet.affectedRows, "DDL 应填 affected_rows")
        assertEquals(0, sheet.rows.size, "DDL 不应产生 SELECT 行帧")
    }

    /**
     * 多 SQL sheet —— 每个 sheet 一份独立文本 + 独立结果，切换只改渲染目标，不串显另一个 sheet 的结果。
     */
    @Test
    fun `sql sheets keep their own editor text and results`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        val first = state.currentSqlSheet()!!
        first.editor.setText("SELECT id, name FROM users ORDER BY id")
        state.executeSql()
        withTimeout(5_000) { while (first.running) delay(20) }
        assertEquals(2, first.rowCount, "users 有 2 行")

        state.addSqlSheet()
        val second = state.currentSqlSheet()!!
        assertEquals(2, state.sqlSheets.size)
        assertEquals(1, state.selectedSqlIndex, "新增 sheet 后应选中它")
        assertEquals("", second.editor.text, "新 sheet 应为空文本")

        second.editor.setText("SELECT id FROM orders")
        state.executeSql()
        withTimeout(5_000) { while (second.running) delay(20) }
        assertEquals(3, second.rowCount, "orders 有 3 行")

        // 切回第一个 sheet —— 文本与结果都还在，且没有被第二个 sheet 的执行覆盖
        state.selectSqlSheet(0)
        assertEquals("SELECT id, name FROM users ORDER BY id", first.editor.text)
        assertEquals(2, first.rowCount)
        assertEquals("SELECT id FROM orders", second.editor.text)
        assertEquals(3, second.rowCount)

        // 删除选中 sheet 后选中项回退，剩余 sheet 内容不受影响
        state.removeSqlSheet(1)
        assertEquals(1, state.sqlSheets.size)
        assertEquals(0, state.selectedSqlIndex)
        assertEquals(2, state.currentSqlSheet()!!.rowCount)
    }

    @Test
    fun `renameSqlSheet trims the name and ignores blank input`() {
        val state = newBrowser()
        state.addSqlSheet()
        assertEquals("SQL 2", state.currentSqlSheet()!!.title, "新建 sheet 的默认名")

        state.renameSqlSheet(1, "  用户查询  ")
        assertEquals("用户查询", state.currentSqlSheet()!!.title, "应去掉首尾空白")

        state.renameSqlSheet(1, "   ")
        assertEquals("用户查询", state.currentSqlSheet()!!.title, "空名应保持原名")

        state.renameSqlSheet(9, "越界")
        assertEquals(2, state.sqlSheets.size)
        assertEquals("SQL 1", state.sqlSheets.first().title, "越界下标不得改到别的 sheet")
    }

    @Test
    fun `renameGenerateScript renames the script and its result row label`() {
        val state = newBrowser()
        assertEquals("脚本 1", state.currentGenerateScript()!!.title)

        state.renameGenerateScript(0, " 订单造数 ")
        assertEquals("订单造数", state.currentGenerateScript()!!.title)
        assertEquals(
            "1. 订单造数",
            state.generateResultRows().single().cells["script"],
            "造数结果表的脚本列应跟随重命名",
        )

        state.renameGenerateScript(0, "")
        assertEquals("订单造数", state.currentGenerateScript()!!.title, "空名应保持原名")
    }

    /**
     * SQL 工作台的高亮档位由**连接方言**决定 —— 换库即换关键字 / 类型 / 内置函数词表。
     */
    @Test
    fun `sql dialect profile follows the connected database`() {
        val state = newBrowser()   // TestConnectionFactory 建的是 H2 连接
        assertEquals("sql-h2", state.sqlDialectProfile().languageId)

        val base = TestConnectionFactory.build(jdbcUrl)
        state.bindConnection(base.copy(id = "mysql-1", dialect = DialectType.MYSQL))
        assertEquals(SqlDialectProfile.MYSQL, state.sqlDialectProfile())

        state.bindConnection(base.copy(id = "sqlite-1", dialect = DialectType.SQLITE))
        assertEquals("sql-sqlite", state.sqlDialectProfile().languageId)

        // 未知方言 / 未连接 → 标准档位（不冒充任何方言的关键字）
        state.bindConnection(base.copy(id = "unknown-1", dialect = DialectType.UNKNOWN))
        assertEquals(SqlDialectProfile.STANDARD, state.sqlDialectProfile())
        state.bindConnection(null)
        assertEquals(SqlDialectProfile.STANDARD, state.sqlDialectProfile())
    }

    @Test
    fun `bindConnection clears SQL workbench state when switching connections`() = runBlocking {
        val state = newBrowser()
        state.selectPane(BrowserPane.SQL)
        val sheet = state.currentSqlSheet()!!
        sheet.editor.setText("SELECT 1")
        state.executeSql()
        withTimeout(5_000) { while (sheet.running) delay(20) }
        assertTrue(sheet.rows.isNotEmpty() || sheet.affectedRows != null)
        state.addSqlSheet()
        assertEquals(2, state.sqlSheets.size)

        // 切到新连接（不同 db 名）
        val newJdbc = "jdbc:h2:mem:bdbtest2_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(newJdbc, "sa", "").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
        }
        state.bindConnection(TestConnectionFactory.build(newJdbc))

        assertEquals(1, state.sqlSheets.size, "切换连接应复位为单个 sheet")
        assertEquals(0, state.selectedSqlIndex)
        val fresh = state.currentSqlSheet()!!
        assertEquals("SQL 1", fresh.title, "切换连接应复位为默认名的单个 sheet")
        assertEquals("", fresh.editor.text, "切换连接应清空编辑器文本")
        assertEquals(0, fresh.rows.size)
        assertNull(fresh.affectedRows)
        assertNull(fresh.error)
        assertFalse(fresh.running)
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

    // =========================================================================
    // 宽表分页 —— 分页器曾经是装饰品
    // =========================================================================

    /**
     * 回归：表预览的**分页器曾经是死的**。
     *
     * `PreviewTabContent` 传的是 `pageSize = PageSize.S100` 而**没有**传 `currentPage` /
     * `onPageChange` / `totalCount`，`loadTabPreview` 又把请求里的 `page` 写死成 1。
     * 两侧都停在第 1 页：信息条写着「共 25 行」，分页器却是「1 / 1」且所有按钮禁用 ——
     * 100 行以外的数据**在界面上根本不存在**，且没有任何迹象提示还有更多。
     */
    @Test
    fun `paging a wide table really asks the engine for other pages`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        val db = state.firstLoadedDatabase()
        val table = state.tablesFor(db, "WIDE")

        state.openTab(db, table)
        val tab = state.tabs.single()
        tab.awaitSettled()
        assertNull(tab.error, "wide tab error: ${tab.error}")

        // 先缩到每页 10 行，25 行 = 3 页 —— 默认的 100 行/页下宽表也只有一页，测不出翻页
        state.changeTabPageSize(tab, PageSize.S10)
        tab.awaitSettled()
        assertEquals(10, tab.pageSize, "page size should follow the picker")
        assertEquals(1, tab.page, "changing the page size must go back to page 1")
        assertEquals(10, tab.rows.size, "page 1 should hold a full page")

        // 翻到最后一页：若请求里仍写死 page=1，这里拿到的还是第 1 页那 10 行
        state.goToTabPage(tab, 3)
        tab.awaitSettled()
        assertEquals(3, tab.page, "page should follow the request")
        assertEquals(25L, tab.total, "total is the table size, not the page size")
        assertEquals(5, tab.rows.size, "last page holds the remainder")
        // 内容必须是第 3 页那 5 行（id 21~25），而不是第 1 页的 —— 只断言行数会被
        // 「恰好也是 5 行」的假象骗过，必须核对具体内容
        val tags = tab.rows.mapNotNull { it.cells["TAG"] as? String }.sorted()
        assertEquals(listOf("row21", "row22", "row23", "row24", "row25"), tags)

        // 往回翻，确认不是单向生效
        state.goToTabPage(tab, 2)
        tab.awaitSettled()
        assertEquals(2, tab.page)
        assertEquals(10, tab.rows.size)
        assertEquals("row11", tab.rows.firstNotNullOfOrNull { it.cells["TAG"] as? String })
    }

    /**
     * 回归：改页大小后页码必须归位；选**同一个**大小则不该触发任何重载。
     *
     * 10 行/页的第 3 页在 20 行/页下越界（25 行只有 2 页）。不归位就会停在一个永远加载不出
     * 内容的页上 —— `DataTable` 的「越界自动回退」也等不到新 `totalCount` 来救。
     */
    @Test
    fun `changing the page size pulls the view back to page one`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        val db = state.firstLoadedDatabase()
        state.openTab(db, state.tablesFor(db, "WIDE"))
        val tab = state.tabs.single()
        tab.awaitSettled()

        state.changeTabPageSize(tab, PageSize.S10)
        tab.awaitSettled()
        state.goToTabPage(tab, 3)
        tab.awaitSettled()
        assertEquals(3, tab.page)

        // 选同一个大小：必须是 no-op，否则每次点开下拉都会重置用户的页码
        state.changeTabPageSize(tab, PageSize.S10)
        assertEquals(3, tab.page, "same page size must not reset the view")

        // 换成 S20：3 页变 2 页，旧页码 3 已越界
        state.changeTabPageSize(tab, PageSize.S20)
        tab.awaitSettled()
        assertEquals(1, tab.page, "page size change must reset to page 1")
        assertEquals(20, tab.rows.size)
        assertEquals(20, tab.pageSize)
    }

    /**
     * 回归：**后发起的请求必须赢**。
     *
     * 预览原先只有屏级（连接级）代次，管不到「同一张表上连着翻页」：两次 `loadTabPreview`
     * 并发在跑，谁先返回谁先写，**慢的那次会覆盖快的那次**。连点两次「下一页」就可能出现
     * 「信息条写着第 2 页、表里却是第 1 页的行」。
     *
     * 这里**故意不等**第一次就发第二次，把并发窗口开到最大。修复前本测试是**间歇性**失败
     * （取决于两次请求的完成顺序），所以末尾再多等一会儿、断言的是最终稳定状态。
     */
    @Test
    fun `a superseded page request never overwrites the newer one`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        val db = state.firstLoadedDatabase()
        state.openTab(db, state.tablesFor(db, "WIDE"))
        val tab = state.tabs.single()
        tab.awaitSettled()

        state.changeTabPageSize(tab, PageSize.S10)
        tab.awaitSettled()

        // 不 await，直接连发两次翻页 —— 第 1 次的响应很可能在第 2 次之后才落地
        state.goToTabPage(tab, 2)
        state.goToTabPage(tab, 3)
        tab.awaitSettled()
        // 再给「迟到的旧响应」留出落地窗口
        delay(300)
        delay(300)

        assertNull(tab.error, "旧请求的错误不应污染新页: ${tab.error}")
        assertEquals(3, tab.page, "最终页码必须是最后一次请求的页")
        assertEquals(
            listOf("row21", "row22", "row23", "row24", "row25"),
            tab.rows.mapNotNull { it.cells["TAG"] as? String }.sorted(),
            "表里必须是第 3 页的行 —— 旧响应覆盖回来的话这里会是第 2 页那批",
        )
    }

    /**
     * 回归：**断开连接必须作废 in-flight 状态**。
     *
     * 池被释放不会让已发出的请求自己结束 —— 引擎端的流还挂着，协程还活着，只是会被代次挡住。
     * 若 `releasePools` 不顺带复位「正在执行」标志，唯一会写 `running = false` 的那条协程已经
     * 提前 return 了，界面就**永远**停在「执行中…」；更糟的是这批行帧在重连后仍会往这个
     * 存活的 sheet 里灌。
     *
     * 注意走的是 `releasePools`（断开路径），不是 `bindConnection`：后者因连接 id 未变会早退，
     * 正是原先漏作废的那条路径。
     */
    @Test
    fun `releasing pools clears every in flight running flag`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        val db = state.firstLoadedDatabase()
        state.openTab(db, state.tablesFor(db, "WIDE"))
        val tab = state.tabs.single()
        tab.awaitSettled()

        // 摆出「三处都正在跑」的现场
        val sheet = state.sqlSheets.first()
        sheet.running = true
        state.generateRunning = true
        tab.loading = true

        state.releasePools()

        assertFalse(sheet.running, "SQL 工作台会永远卡在「执行中…」")
        assertFalse(state.generateRunning, "造数工作台会永远卡在「造数中…」")
        assertFalse(tab.loading, "预览会永远卡在转圈（loadTabPreview 早退时不清 loading）")
    }

    /**
     * 回归：`bindConnection` 的派生状态复位不能因为重构而丢字段。
     *
     * 代次自增与 loading 复位曾分散在 `bindConnection` / `releasePools` 两处各写一半，
     * 现在统一由 `releasePools` → `invalidateInFlight` 负责。切连接仍必须全部复位。
     */
    @Test
    fun `binding a different connection resets the derived state`() = runBlocking {
        val state = newBrowser()
        state.refreshDatabases()
        val db = state.firstLoadedDatabase()
        state.openTab(db, state.tablesFor(db, "WIDE"))
        state.sqlSheets.first().running = true
        state.generateRunning = true

        state.bindConnection(TestConnectionFactory.build("jdbc:h2:mem:bdbtest_other_${System.nanoTime()};DB_CLOSE_DELAY=-1"))

        assertTrue(state.tabs.isEmpty(), "预览标签页应清空")
        assertTrue(state.databases.isEmpty(), "库列表应清空")
        assertFalse(state.sqlSheets.first().running, "新连接的 sheet 不得继承 running")
        assertFalse(state.generateRunning, "换连接后不得仍显示造数中")
        assertEquals(1, state.sqlSheets.size, "SQL 工作台应复位为单个空 sheet")
    }

    // -------------------------------------------------------------------------
    // 上面几项的公共脚手架
    // -------------------------------------------------------------------------

    /** 等库列表加载完，返回第一个库名。 */
    private suspend fun DatabaseBrowserState.firstLoadedDatabase(): String {
        withTimeout(5_000) { while (databases.isEmpty()) delay(50) }
        return databases.first()
    }

    /** 展开某个库、取出指定表名（H2 归一为大写，故大小写不敏感比对）。 */
    private suspend fun DatabaseBrowserState.tablesFor(db: String, wanted: String): String {
        if (db !in tablesByDatabase) toggleDatabase(db)
        withTimeout(5_000) { while (db in loadingTables) delay(50) }
        tableLoadError[db]?.let { throw AssertionError("加载表列表失败: $it") }
        return tablesByDatabase.getValue(db).first { it.uppercase() == wanted.uppercase() }
    }

    /** 等一个预览标签页的 in-flight 请求落定。 */
    private suspend fun TablePreviewTab.awaitSettled() {
        withTimeout(5_000) { while (loading) delay(10) }
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
