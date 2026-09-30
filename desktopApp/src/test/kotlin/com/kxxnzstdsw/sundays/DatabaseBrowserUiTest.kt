package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DatabaseBrowserScreen] 的 Compose UI 端到端测试 —— 真引擎 + 真点击（H2 内存库）。
 *
 * 验证交付契约：
 * 1. 已连接时左侧渲染出数据库节点
 * 2. 单击数据库节点展开表列表
 * 3. **双击**表名 → 右侧出现预览标签页，并渲染出预览数据
 * 4. 重复双击同一张表不会新增第二个标签页
 */
@OptIn(ExperimentalTestApi::class)
class DatabaseBrowserUiTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var dbName: String
    private lateinit var connection: ConnectionConfig

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-db-browser-ui").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // 与 DatabaseBrowserFlowTest 相同的理由：显式注册 H2 dialect，绕过 IdbEngine
        // 全局幂等 bootstrap 在 engine.close() 之后不再扫描 SPI 的问题。
        DialectLoader.registerForTesting("H2", H2Dialect())

        dbName = "bdbtestui_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(64))")
                stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')")
                // 250 行 —— 结果区分页（每页 100 → 3 页）的验证载体
                stmt.executeUpdate("CREATE TABLE big (id INT PRIMARY KEY)")
                stmt.executeUpdate("INSERT INTO big SELECT X FROM SYSTEM_RANGE(1, 250)")
                // 造数工作台的目标表（空表，脚本跑完应正好 3 行）
                stmt.executeUpdate("CREATE TABLE gen_target (id INT PRIMARY KEY, label VARCHAR(64))")
            }
        }

        connection = ConnectionConfig(
            id = "ui-${System.nanoTime()}",
            name = "UIH2",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = jdbcUrl,
        )
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try { DriverManager.getConnection(jdbcUrl, "sa", "").use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } } } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    @Test
    fun `double clicking a table opens exactly one preview tab`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 左侧数据库节点渲染（H2 的 SCHEMA.LIST 返回 catalog，已归一为大写 —— 忽略大小写匹配）
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(dbName, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(dbName, substring = true, ignoreCase = true).performClick()

        // 展开后表名可见（H2 归一大写）；此时只有左侧树叶子，尚无标签页
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("USERS").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, usersTabCount(), "no tab should exist before opening")

        // 树叶子是文本匹配的第一个节点（标签页在右侧标签条，位置靠后）
        onAllNodesWithText("USERS")[0].performTouchInput { doubleClick() }

        // 预览数据渲染（样本行 Alice）→ 证明 DATA.LIST 已拉回并渲染
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { usersTabCount() == 1 }
        assertEquals(1, usersTabCount(), "double click should open exactly one tab for USERS")

        // 再次双击同一张表 —— 不得新增第二个标签页
        onAllNodesWithText("USERS")[0].performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(1, usersTabCount(), "same table must not add a duplicate tab")
    }

    /**
     * 当前语义树中标题为 USERS 的标签页数量。
     *
     * `SemanticsProperties.Selected` 只由 Material3 `Tab` 设置（左侧树叶子没有 Selected 语义），
     * 因此 `hasText("USERS") + keyIsDefined(Selected)` 精确圈定标签条上的标签页。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.usersTabCount(): Int =
        onAllNodes(
            hasText("USERS") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        ).fetchSemanticsNodes().size

    /**
     * SQL 工作台的 UI 端到端冒烟 —— 工具栏按钮 → 编辑器 → 执行 → 结果表。
     *
     * 覆盖消费者可见路径：工具栏「SQL 工作台」按钮把右栏从表预览切到工作台，
     * 顶部编辑器输入 SQL 后点「执行 SQL」，底部结果面板渲染出 `SQL.EXECUTE` 的行帧内容。
     * 断言锚点是**引擎真实返回的数据**（Alice / Bob），不是「组件已渲染」这类 wiring 断言。
     */
    @Test
    fun `sql workbench executes a SELECT and shows result rows`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 初始是表预览态 —— 工具栏按钮显示「SQL 工作台」（未进入工作台）
        onNodeWithText("SQL 工作台").assertIsDisplayed()

        // 点工具栏按钮 → 切到 SQL 工作台（按钮变为「返回表预览」，工具条出现 schema 提示）
        onNodeWithText("SQL 工作台").performClick()
        onNodeWithText("返回表预览").assertIsDisplayed()
        onNodeWithText("SQL 工作台", substring = true).assertExists()
        assertEquals(BrowserPane.SQL, browser.activePane, "工具栏点击后应切到 SQL pane")

        // 输入 SQL —— 直接写状态机字段（CodeEditor 的输入通道需要 focus + IME，
        // 在 skiko 无头环境下不稳定；执行链路才是本测试的验证目标）
        browser.currentSqlSheet()!!.editor.setText("SELECT id, name FROM users ORDER BY id")
        waitForIdle()

        // 「执行 SQL」按钮此时可用（connected + 文本非空 + 未在执行）
        onNodeWithText("执行 SQL").assertIsDisplayed()
        onNodeWithText("执行 SQL").performClick()

        // 结果表渲染出引擎真实返回的行 —— 证明 SQL.EXECUTE 流式链路打通
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Bob", substring = true).assertCountEquals(1)
        // 结果区标题标明行数
        onNodeWithText("查询结果 · 2 行").assertIsDisplayed()
    }

    /**
     * SQL 工作台多 sheet —— 「＋」新建第二个 sheet，两个 sheet 各自的 SQL 文本与查询结果
     * 互不串显；「×」关闭后选中项回退。
     *
     * 断言锚点是**每个 sheet 自己的引擎结果**（users 2 行 vs big 250 行），不是「组件已渲染」。
     */
    @Test
    fun `sql workbench keeps per-sheet text and results`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        // 第一个 sheet：跑 users 查询
        val first = browser.currentSqlSheet()!!
        first.editor.setText("SELECT id, name FROM users ORDER BY id")
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("查询结果 · 2 行").fetchSemanticsNodes().isNotEmpty()
        }

        // 「＋」新建第二个 sheet —— 标签条多出「SQL 2」并被选中，编辑器为空
        onNodeWithContentDescription("新建 SQL sheet").performClick()
        waitForIdle()
        assertEquals(2, browser.sqlSheets.size, "点「＋」应新增一个 SQL sheet")
        assertEquals("SQL 2", browser.currentSqlSheet()!!.title, "新建后应选中新 sheet")
        onNodeWithText("SQL 2").assertIsDisplayed()
        onNode(hasSetTextAction()).assertTextEquals("")

        // 第二个 sheet 跑另一条查询 —— 结果区渲染它自己的 250 行
        val second = browser.currentSqlSheet()!!
        second.editor.setText("SELECT id FROM big")
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("查询结果 · 250 行").fetchSemanticsNodes().isNotEmpty()
        }

        // 切回第一个 sheet —— 编辑器与结果都回到它自己那一份，不串显第二个 sheet 的
        onNodeWithText("SQL 1").performClick()
        waitForIdle()
        assertTrue(browser.selectedSqlIndex == 0, "点标签应切回第一个 sheet")
        onNode(hasSetTextAction()).assertTextEquals("SELECT id, name FROM users ORDER BY id")
        onNodeWithText("查询结果 · 2 行").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("查询结果 · 250 行").fetchSemanticsNodes().size)

        // 「×」关闭第二个 sheet —— 只剩一个时不再渲染关闭按钮，选中回退到第一个
        onAllNodesWithContentDescription("关闭 SQL sheet")[1].performClick()
        waitForIdle()
        assertEquals(1, browser.sqlSheets.size, "点「×」应删除该 SQL sheet")
        assertEquals(0, browser.selectedSqlIndex, "删除后选中项应回退")
        onNodeWithText("SQL 2").assertDoesNotExist()
        onNode(hasSetTextAction()).assertTextEquals("SELECT id, name FROM users ORDER BY id")
    }

    /**
     * 标签重命名 —— 选中标签后点「✎」弹出重命名弹窗：确定提交、取消放弃（保持原名）。
     *
     * 断言锚点是状态机里的真实名字，不只是渲染文本。
     */
    @Test
    fun `sql tab rename commits on confirm and discards on cancel`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        // 点「✎」→ 弹窗出现，输入框预填当前标签名
        onNodeWithContentDescription("重命名当前标签").performClick()
        waitForIdle()
        onNodeWithText("重命名标签").assertIsDisplayed()
        // 输入框预填当前标签名
        onNode(hasSetTextAction() and hasText("SQL 1")).assertExists()
        // 用「名称」label 定位弹窗输入框（值本身会变，label 不变），整段替换后确认
        onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("用户查询")
        waitForIdle()
        onNodeWithText("确定").performClick()
        waitForIdle()

        assertEquals("用户查询", browser.currentSqlSheet()!!.title, "「确定」应提交重命名")
        onNodeWithText("用户查询").assertIsDisplayed()
        onNodeWithText("SQL 1").assertDoesNotExist()

        // 再来一次 → 改名后点「取消」，名字应保持不变
        onNodeWithContentDescription("重命名当前标签").performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText("名称")).performTextReplacement("临时名字")
        waitForIdle()
        onNodeWithText("取消").performClick()
        waitForIdle()

        assertEquals("用户查询", browser.currentSqlSheet()!!.title, "「取消」应放弃重命名")
        onNodeWithText("用户查询").assertIsDisplayed()
    }

    /**
     * 标签条滚动 —— 标签总宽超出右栏时：
     * 1. 新建/选中的标签自动滚入可视区；
     * 2. 普通鼠标的**纵向滚轮**也能横向滚动标签条（Compose 的 `horizontalScroll` 只吃横向分量）。
     */
    @Test
    fun `sql tab strip scrolls with the mouse wheel and follows the selection`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        // 加到 15 个 sheet —— 总宽远超右栏（每标签至少 90dp），末尾标签初始在可视区外
        repeat(14) { browser.addSqlSheet() }
        waitForIdle()
        assertEquals(15, browser.sqlSheets.size)
        onNodeWithText("SQL 15").assertIsDisplayed()

        // 选中回到第一个 sheet → 标签条跟着滚回起点，末尾标签移出可视区
        browser.selectSqlSheet(0)
        waitForIdle()
        onNodeWithText("SQL 1").assertIsDisplayed()
        onNodeWithText("SQL 15").assertIsNotDisplayed()

        // 纵向滚轮 → 横向滚动（自定义转发；Compose 自身会忽略横向容器上的纵向分量）
        onNodeWithText("SQL 1").performMouseInput {
            moveTo(center)
            scroll(300f)
        }
        waitForIdle()
        onNodeWithText("SQL 15").assertIsDisplayed()
    }

    /**
     * SQL 工作台的高亮档位跟随连接方言。
     *
     * 两条可观察锚点：
     * 1. 标题条显示当前档位名（H2 → SQLite），切方言连接即变；
     * 2. 「格式化」按钮仍可用 —— 按钮的 enabled 取决于 `CodeFormatterRegistry.get(languageId)`，
     *    而 `languageId` 就是方言档位 id。**漏注册方言 formatter 会让按钮静默禁用**，这里守住它。
     */
    @Test
    fun `sql workbench follows the connected dialect`() = runComposeUiTest {
        // 真实 app 在 main() 里注册语言 + formatter（见 registerBuiltinEditors 的 KDoc）；
        // 本测试直接渲染屏幕、不跑 main()，因此复现同一步 —— 否则 languageId 查不到语言，
        // 编辑器静默退化纯文本、「格式化」按钮也恒为 disabled，断言就失去意义。
        registerBuiltinEditors()

        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()
        // 连接是 H2 → 标题条显示 H2 档位（连接名是 UIH2、状态条是「已连接 · H2」，精确匹配不会撞上）
        onNodeWithText("H2").assertIsDisplayed()
        onNodeWithText("格式化").assertIsEnabled()

        // 换成 SQLite 方言的连接 → 档位标签随之切换
        browser.bindConnection(connection.copy(id = "ui-sqlite", dialect = DialectType.SQLITE))
        waitForIdle()
        onNodeWithText("SQLite").assertIsDisplayed()
        onNodeWithText("H2").assertDoesNotExist()
        onNodeWithText("格式化").assertIsEnabled()
    }

    /**
     * 编辑器**输入通道**回归 —— 用户报「编辑器无法输入」。
     *
     * 此前 UI 测试直接写 `browser.sqlEditorText` 绕过输入，等于没验证这条路径。
     * 本测试走真实输入：渲染 SQL 工作台 → 对编辑器 `performTextInput` →
     * 断言状态机 `sqlEditorText` 确实被写入（onTextChange 链路通）。
     */
    @Test
    fun `sql workbench editor accepts typed input`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        // 编辑器内的 BasicTextField 是唯一可编辑节点
        val editor = onNode(hasSetTextAction())
        editor.assertExists()

        // 点击应让编辑器获得焦点（真实键盘输入的前提）
        editor.performClick()
        waitForIdle()
        assertTrue(
            editor.fetchSemanticsNode().config.contains(SemanticsProperties.Focused),
            "点击编辑器后应获得焦点 —— 否则真实键盘输入无法进入",
        )

        // 回归：输入区必须撑满编辑框，而不是只有一行高。
        //
        // 曾经的缺陷 —— `verticalScroll` 用无界高度测量内容，子项上的 fillMaxHeight() 失效，
        // BasicTextField 退化成一行（实测 19dp）；编辑框下方大片区域点不到 → 「无法输入」。
        // 单行文本高度约 19dp，任何一行都远小于 100dp，故该阈值能可靠捕获回归。
        val bounds = editor.fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.height > 100f,
            "输入区高度应撑满编辑框（实测 ${bounds.height}dp）—— 过小说明又退化成一行高，点击无法聚焦",
        )

        editor.performTextInput("SELECT 1")
        waitForIdle()

        assertEquals(
            "SELECT 1",
            browser.currentSqlSheet()!!.editor.text,
            "输入应经 onTextChange 写回状态机 —— 否则说明输入通道断开",
        )
    }

    /**
     * SQL 工作台状态保持 —— 切到表预览再切回，SQL 文本与查询结果必须与切换前一致。
     *
     * 契约：编辑器文本与最近一次执行结果（结果行 / 行数标题）都挂在 [DatabaseBrowserState] 上，
     * 右栏两个 pane 只是同一份状态的两种渲染 —— 切换不得清空任何一项。
     */
    @Test
    fun `sql text and result survive toggling back to table pane`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        val editor = onNode(hasSetTextAction())
        editor.performClick()
        waitForIdle()
        val sql = "SELECT id, name FROM users ORDER BY id"
        editor.performTextInput(sql)
        waitForIdle()

        onNodeWithText("执行 SQL").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("查询结果 · 2 行").fetchSemanticsNodes().isNotEmpty()
        }

        // 切回表预览：工作台内容应离开语义树
        onNodeWithText("返回表预览").performClick()
        waitForIdle()
        assertEquals(BrowserPane.TABLE, browser.activePane)
        assertEquals(0, onAllNodesWithText("查询结果 · 2 行").fetchSemanticsNodes().size)

        // 再切回工作台 —— SQL 文本与查询结果都应原样恢复
        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()
        assertEquals(sql, browser.currentSqlSheet()!!.editor.text, "切回工作台后 SQL 文本应保持不变")
        onNodeWithText("查询结果 · 2 行").assertIsDisplayed()
        onAllNodesWithText("Alice", substring = true).assertCountEquals(1)
        onAllNodesWithText("Bob", substring = true).assertCountEquals(1)
        onNode(hasSetTextAction()).assertTextEquals(sql)

        // 继续输入应落在切换前的老光标位置（文末）—— 光标被重置到文首会让用户接着写的内容插到最前面
        onNode(hasSetTextAction()).performTextInput(" ORDER BY name")
        waitForIdle()
        assertEquals(
            "$sql ORDER BY name",
            browser.currentSqlSheet()!!.editor.text,
            "切回工作台后光标应在原位置 —— 被重置到文首会让后续输入插到 SQL 最前面",
        )
    }

    /**
     * 结果区视图状态（页码 / 选中行）在 pane 切换后保持 —— 与 SQL 文本同一契约。
     *
     * 页码与选中行此前是 `DataTable` 内部 / 未接线的默认值，切走再回来就回到第 1 页、无选中行。
     */
    @Test
    fun `result page and selected row survive toggling back to table pane`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        val resultSheet = browser.currentSqlSheet()!!
        resultSheet.editor.setText("SELECT id FROM big")
        waitForIdle()
        onNodeWithText("执行 SQL").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("查询结果 · 250 行").fetchSemanticsNodes().isNotEmpty()
        }

        // 翻到第 2 页（每页 100 → 3 页），并选中该页首行
        onNodeWithText("下一页").performClick()
        waitForIdle()
        onNodeWithText("2 / 3").assertExists()
        onAllNodesWithText("101")[0].performClick()
        waitForIdle()
        assertEquals(2, resultSheet.resultPage, "点「下一页」应推进结果页码")
        // 选中行会在单元格与详情面板中各出现一次（具体节点数由布局决定，先记下来做切走前后的对比）
        val selectedRowNodesBefore = onAllNodesWithText("101", substring = true).fetchSemanticsNodes().size
        assertTrue(selectedRowNodesBefore > 1, "选中行应在单元格与详情面板中同时可见")

        // 切走再切回
        onNodeWithText("返回表预览").performClick()
        waitForIdle()
        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        onNodeWithText("2 / 3").assertExists()
        assertEquals(2, resultSheet.resultPage, "切回工作台后应仍停在第 2 页")
        assertEquals(
            selectedRowNodesBefore,
            onAllNodesWithText("101", substring = true).fetchSemanticsNodes().size,
            "切回工作台后结果区渲染应与切走前一致（同一页 + 同一选中行）",
        )
        assertEquals("101", resultSheet.selectedRowId, "切回工作台后选中行应保留")
    }

    /**
     * 编辑器滚动位置在 pane 切换后保持 —— 长 SQL 滚到中间切走再回来，不应被弹回顶部。
     */
    @Test
    fun `editor scroll position survives toggling back to table pane`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("SQL 工作台").performClick()
        val scrollSheet = browser.currentSqlSheet()!!
        scrollSheet.editor.setText((1..200).joinToString("\n") { "SELECT $it" })
        waitForIdle()

        val editor = onNode(hasSetTextAction())
        editor.performTouchInput { swipeUp() }
        waitForIdle()
        val scrolled = scrollSheet.editor.scrollState.value
        assertTrue(scrolled > 0, "200 行 SQL 超出可视区应能滚动（实测 offset=$scrolled）")

        onNodeWithText("返回表预览").performClick()
        waitForIdle()
        onNodeWithText("SQL 工作台").performClick()
        waitForIdle()

        assertEquals(scrolled, scrollSheet.editor.scrollState.value, "切回工作台后滚动位置应保持不变")
    }

    /**
     * 造数工作台端到端 —— 工具栏进入 → 编辑器输入 Lua → 执行 → 结果统计，
     * 且**切到表预览再切回来后脚本与结果原样回显**（与 SQL 工作台同一契约）。
     *
     * 断言锚点是引擎真实效果：目标表 `gen_target` 里确实多出 3 行。
     */
    @Test
    fun `generate workbench inserts rows and restores state after pane toggle`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 工具栏进入造数工作台（此时 SQL 与造数两个入口都可见）
        onNodeWithText("造数工作台").performClick()
        onNodeWithText("返回表预览").assertIsDisplayed()
        assertEquals(BrowserPane.GENERATE, browser.activePane, "工具栏点击后应切到造数 pane")

        val script = browser.currentGenerateScript()!!
        script.editor.setText("")
        waitForIdle()
        val editor = onNode(hasSetTextAction())
        editor.performClick()
        waitForIdle()
        editor.performTextInput("for i = 1, 3 do insert('gen_target', {id = i, label = 'row_'..i}) end")
        waitForIdle()

        onNodeWithText("执行造数").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("造数完成 · 共 3 行 · 处理 1 个脚本").fetchSemanticsNodes().isNotEmpty()
        }
        // 结果表出现目标表列 —— 编辑器文本里也含同名串，故要求整串相等，只命中单元格
        onNodeWithText("gen_target", substring = false).assertIsDisplayed()

        // 引擎真的把行写进了库
        val rows = DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM gen_target").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(3, rows, "gen_target 应有脚本插入的 3 行")

        // 切到表预览再切回：脚本文本、光标位置、造数统计都应原样恢复
        onNodeWithText("返回表预览").performClick()
        waitForIdle()
        assertEquals(BrowserPane.TABLE, browser.activePane)
        assertEquals(
            0,
            onAllNodesWithText("造数完成 · 共 3 行 · 处理 1 个脚本").fetchSemanticsNodes().size,
            "工作台离开组合后结果面板不应留在表预览上",
        )

        onNodeWithText("造数工作台").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).assertTextEquals(script.editor.text)
        assertEquals(3L, browser.generateTotalInserted, "切回后插入行数应保留")
        onNodeWithText("造数完成 · 共 3 行 · 处理 1 个脚本").assertIsDisplayed()

        // 光标仍在文末 —— 继续输入应追加到脚本末尾，而不是插到开头
        onNode(hasSetTextAction()).performTextInput("\n-- done")
        waitForIdle()
        assertTrue(
            script.editor.text.endsWith("-- done"),
            "切回后光标应在原位置，实际文本: ${script.editor.text.takeLast(40)}",
        )
    }
 }
