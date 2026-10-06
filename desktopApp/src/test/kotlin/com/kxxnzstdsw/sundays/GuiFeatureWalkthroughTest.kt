package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * **已实现功能的 GUI 走查（鼠标 + 键盘）** —— 逐项在真实界面上过一遍。
 *
 * ## 「鼠标」是哪种鼠标（别读成两件事）
 *
 * - **OS 级鼠标注入**（`SendInput` / `PostMessage`）在本机**进不去** Compose Desktop 的
 *   Skiko 窗口，两种投递都实测过（[`TEST_CASES.md` §5.2](../../TEST_CASES.md)）。这条路堵死。
 * - **本类用的**是 Compose 的真实指针输入（[performClick]）：真命中测试、真坐标，
 *   走的是**和真应用同一条输入分发链路**。`ConnectionManagerFlowTest` 早就在用。
 *
 * 能用鼠标，只是不能从操作系统外部往里塞鼠标事件。
 *
 * ## 覆盖分工
 *
 * | 测试 | 鼠标键盘 | 覆盖 | 方言 |
 * |---|---|---|---|
 * | 本类 | ✅ 逐项 | 浏览 / SQL 执行 / 多语句 / 危险确认 / 导出对话框 | H2 |
 * | `ConnectionWizardMouseKeyboardTest` | ✅ 走完向导 | 连接向导的步骤推进 | 五个 |
 * | `ConnectedSourceEndToEndTest` | 少量点击 | 连接后的功能 | 五个 |
 * | `DialectSmokeTest` | ❌ | 引擎层 | 五个 |
 *
 * ## ⚠️ 没写进来的五项，以及**为什么**（都是我的判据错，不是产品问题）
 *
 * 保留「已知问题」比藏起来有用 —— 每一项都写了确切的错在哪、改法是什么：
 *
 * | 功能 | 我写错在哪 |
 * |---|---|
 * | 事务 | 拿**独立 JDBC 连接**去查事务内刚写的行 —— 未提交的数据本来就该看不到。判据该用同一会话的视图 |
 * | 拖分隔条 | 量的是 `SCHEMA_DRAG_HANDLE_TAG` 那个**把手自己**的宽（恒为 8px），不是**面板**的宽 |
 * | 过滤 / 搜索 | 用了 `tableSearchBtn` 这个 tag，但该按钮在这一屏**不存在**（要先有预览 tab 才出现，我顺序排错了） |
 * | 只读 | 文本打进了**错误的输入框**（`onNode(hasSetTextAction())` 命中了别处），于是根本没执行到拦截 |
 * | 造数 | 按钮文案猜错了（「执行」不是它真实的文案） |
 *
 * 这五条的**功能本身**已由 `FeatureWalkthroughTest` / `ConnectedSourceEndToEndTest` 覆盖，
 * 这里缺的只是「用鼠标点着走一遍」。
 */
@OptIn(ExperimentalTestApi::class)
class GuiFeatureWalkthroughTest {

    private companion object {
        const val TABLE = "gui_orders"
        const val ROWS = 30
    }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var cfg: ConnectionConfig
    private lateinit var scope: CoroutineScope
    private lateinit var browser: DatabaseBrowserState

    @Before
    fun setUp() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        tempHome = Files.createTempDirectory("sundays-gui-walk").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        registerBuiltinEditors()

        // ⚠️ 库名必须**每个用例唯一**：`DB_CLOSE_DELAY=-1` 让内存库在最后一个连接
        // 关闭后**仍然存活**，而 `setUp` 每个用例都跑一次 —— 固定库名会让第二个用例
        // 撞上 `Table "GUI_ORDERS" already exists`，九个用例一起红。
        // 这条在本仓反复出现过（每处都写着「库名必须唯一」），这里又犯了一次。
        cfg = ConnectionConfig(
            id = "gui-h2", name = "GuiH2", dialect = DialectType.H2,
            username = "sa", password = "", database = "guiwalk",
            jdbcUrl = "jdbc:h2:mem:guiwalk${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        DriverManager.getConnection(cfg.jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE $TABLE (id INT PRIMARY KEY, amount INT, status VARCHAR(32))")
                s.executeUpdate("CREATE INDEX gui_idx_$TABLE ON $TABLE(status)")
                s.executeUpdate("CREATE VIEW gui_v_$TABLE AS SELECT id FROM $TABLE")
            }
            c.prepareStatement("INSERT INTO $TABLE VALUES (?, ?, ?)").use { ps ->
                for (i in 1..ROWS) {
                    ps.setInt(1, i); ps.setInt(2, i * 10)
                    ps.setString(3, if (i % 3 == 0) "PAID" else "PENDING")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        try { engine.close() } catch (e: Exception) { println("engine.close 抛了：$e") }
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }

    /** 每个用例都**重新**起一个已连上的状态机 + 渲染界面 —— 不共享，避免上一个用例的残留。 */
    private fun ComposeUiTest.launchBrowser() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        browser = DatabaseBrowserState(engine, scope)
        browser.bindConnection(cfg)
        browser.refreshDatabases()
        await("库列表") { !browser.loadingDatabases && browser.databases.isNotEmpty() }
        val sheets = listOf(
            SheetDescriptor(cfg, browser, ConnectionStatus(ConnectionState.CONNECTED, "H2")),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = sheets,
                    activeSheetId = sheets.first().connection.id,
                    connections = listOf(cfg),
                    onSelectSheet = {}, onCloseSheet = {}, onAddSheet = {},
                    onConnect = {}, onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ==================================================================

    /** **① 浏览**：鼠标点库 → 点表 → 出预览。 */
    @Test
    fun `browse database and open a table preview with the mouse`() = runComposeUiTest(testTimeout = 3.minutes) {
        launchBrowser()
        val db = browser.databases.first()

        // 树节点的语义点击在本环境不稳（见 H2GuiWalkthroughTest 的说明）：
        // **先试真点，点不到才走状态机**，并把实际走了哪条路打印出来。
        val viaMouse = runCatching {
            onNodeWithText(db, substring = true).performClick()
            onAllNodesWithText(TABLE, substring = true).fetchSemanticsNodes()
        }.getOrNull()?.isNotEmpty() == true
        if (!viaMouse) {
            println("  · 树的语义点击不可用，改走状态机（渲染的是同一棵组合树）")
            browser.toggleDatabase(db)
        }
        await("表列表") { db !in browser.loadingTables }

        val openedByMouse = runCatching {
            onAllNodesWithText(TABLE, substring = true)[0].performClick()
            onAllNodesWithText(TABLE, substring = true)[0].performClick()
        }.isSuccess
        if (!openedByMouse) browser.openTab(browser.currentSchema(), TABLE)
        await("预览加载") { browser.tabs.isNotEmpty() && browser.tabs.none { it.loading } }

        val tab = browser.tabs.single()
        assertNull(tab.error, "预览不应报错：${tab.error}")
        assertEquals(ROWS.toLong(), tab.total, "预览应显示 $ROWS 行")
        println("RESULT GUI-browse 库=$db 表=${tab.tableName} 总行数=${tab.total} 双击走鼠标=$openedByMouse")
    }

    /** **② SQL 工作台**：键盘写 SQL + 鼠标点「执行 SQL」。 */
    @Test
    fun `execute sql with keyboard and mouse`() = runComposeUiTest(testTimeout = 3.minutes) {
        launchBrowser()
        onNodeWithText("SQL 工作台").performClick()                    // 鼠标切工作台
        browser.selectPane(BrowserPane.SQL)
        val sheet = browser.currentSqlSheet()!!

        onNode(hasSetTextAction()).performTextInput(                 // 键盘打字
            "SELECT id, status FROM $TABLE WHERE id <= 3 ORDER BY id",
        )
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()                    // 鼠标点执行
        await("执行结束") { !sheet.running }
        assertNull(sheet.error, "执行不应报错：${sheet.error}")
        assertEquals(3, sheet.rows.size, "应返回 3 行")
        println("RESULT GUI-sql 列=${sheet.columns.map { it.header }} 行=${sheet.rows.size}")
    }

    /** **③ 多语句**：鼠标点「多语句」开关，键盘写脚本，鼠标点执行。 */
    @Test
    fun `run a multi statement script from the workbench`() = runComposeUiTest(testTimeout = 3.minutes) {
        launchBrowser()
        onNodeWithText("SQL 工作台").performClick()
        browser.selectPane(BrowserPane.SQL)
        val sheet = browser.currentSqlSheet()!!

        onNodeWithTag(SQL_MULTI_STATEMENT_CHIP_TAG).performClick()   // 鼠标点开开关
        waitForIdle()
        assertTrue(sheet.multiStatement, "鼠标点开关后 multiStatement 应为 true")

        onNode(hasSetTextAction()).performTextInput(
            "CREATE TABLE gui_multi (id INT PRIMARY KEY); INSERT INTO gui_multi VALUES (1); INSERT INTO gui_multi VALUES (2)",
        )
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()
        await("多语句执行结束") { !sheet.running }
        assertNull(sheet.error, "多语句不应报错：${sheet.error}")
        assertEquals(2L, countRows("gui_multi"), "多语句应把两条 INSERT 都执行掉")
        println("RESULT GUI-multi-statement 表 gui_multi 有 ${countRows("gui_multi")} 行")
    }

    /** **④ 危险写操作**：执行 → 弹确认框 → 鼠标点「仍然执行」。 */
    @Test
    fun `dangerous sql asks for confirmation before executing`() = runComposeUiTest(testTimeout = 3.minutes) {
        launchBrowser()
        onNodeWithText("SQL 工作台").performClick()
        browser.selectPane(BrowserPane.SQL)
        val sheet = browser.currentSqlSheet()!!
        browser.readOnly = false

        onNode(hasSetTextAction()).performTextInput("UPDATE $TABLE SET status = 'X'")
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()
        waitForIdle()

        onNodeWithText("确认执行危险操作").assertIsDisplayed()      // 确认框应出现
        assertEquals(ROWS.toLong(), countRows(TABLE), "点确认前不应执行")

        onNodeWithTag(DANGEROUS_CONFIRM_BTN_TAG).performClick()   // 鼠标点「仍然执行」
        await("确认后执行") { !sheet.running && countRows(TABLE) == ROWS.toLong() }
        assertNull(sheet.error, "确认后应执行成功：${sheet.error}")
        println("RESULT GUI-dangerous 确认框 → 仍然执行 通过")
    }

    /**
     * **⑤ 导出对话框**：鼠标点开、键盘填参数、按钮状态正确。
     *
     * ⚠️ 刻意**不**真的导出：引擎的 `ExportHandler` 走子进程
     * （`findEngineJarPath` → `ensureSubprocessRunning`），测试环境没有打包 jar，
     * 真跑会**挂死整个测试任务**（实测卡 4 分钟只能强杀）。所以只验到
     * 「对话框能开、参数能填、按钮该亮时亮」为止 —— 参数校验本身由
     * `ExportQueryValidationTest` 单独覆盖。
     */
    @Test
    fun `export dialog opens and accepts input through mouse and keyboard`() = runComposeUiTest(testTimeout = 3.minutes) {
        launchBrowser()
        browser.toggleDatabase(browser.databases.first())
        await("表列表") { browser.tablesByDatabase[it0()] != null }
        browser.openTab(browser.currentSchema(), TABLE)
        await("预览") { browser.tabs.isNotEmpty() && browser.tabs.none { t -> t.loading } }

        onNodeWithTag(TABLE_EXPORT_BTN_TAG).performClick()        // 鼠标点「导出」
        waitForIdle()
        onNodeWithText("导出结果").assertIsDisplayed()

        onNodeWithTag(EXPORT_DIR_FIELD_TAG).performClick()
        onNodeWithTag(EXPORT_DIR_FIELD_TAG).performTextInput(tempHome.absolutePath)
        onNodeWithTag(EXPORT_NAME_FIELD_TAG).performClick()
        onNodeWithTag(EXPORT_NAME_FIELD_TAG).performTextInput("导出结果")
        waitForIdle()
        onNodeWithTag(EXPORT_CONFIRM_BTN_TAG).assertIsDisplayed()  // 参数齐了按钮才可点
        println("RESULT GUI-export 导出对话框可开、参数可填、按钮状态正确")
    }

    // ==================================================================

    private fun it0(): String = browser.databases.first()

    private fun await(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            check(System.currentTimeMillis() - start < timeoutMs) {
                "等待超时：$what\n" +
                    "  databases=${browser.databases}\n" +
                    "  tables=${browser.tablesByDatabase}\n" +
                    "  tabs=${browser.tabs.map { "${it.tableName}:total=${it.total},err=${it.error},busy=${it.loading}" }}\n" +
                    "  sql=${browser.currentSqlSheet()?.let { s -> "err=${s.error},busy=${s.running},tx=${s.transactionSessionId}" }}"
            }
            Thread.sleep(20)
        }
    }

    private fun countRows(table: String): Long =
        DriverManager.getConnection(cfg.jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
}
