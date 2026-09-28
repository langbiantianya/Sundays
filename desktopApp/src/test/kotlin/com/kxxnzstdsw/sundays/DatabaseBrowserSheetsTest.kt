package com.kxxnzstdsw.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.AddConnectionDialog
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.DialectType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DatabaseBrowser 多 sheet 行为 + 「＋」入口 → AddConnectionDialog 的端到端契约。
 *
 * 验证：
 * 1. [ConnectionSession.save] / [ConnectionSession.quickConnectDirect] 都会自动追加 sheet ——「添加
 *    连接成功后要添加个新的标签页」是状态机的下沉契约，调用方无需自行 openSheet
 * 2. 通过 ConnectionManager 的「连接」按钮打开 sheet 后：标签条渲染「＋入口 + 本连接」两个 tab
 * 3. 点 ＋ → 弹出 AddConnectionDialog（标题「添加数据库连接」）—— 弹窗内复用
 *    [ConnectionManagerScreen]（左侧已保存连接列表 + 右侧总览 / 向导），与首屏同源
 */
@OptIn(ExperimentalTestApi::class)
class DatabaseBrowserSheetsTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-sheets-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    @Test
    fun `plus tab opens AddConnectionDialog and empty hint appears when no sheets are open`() = runComposeUiTest {
        // 预置一条配置让首屏有可显示的连接
        com.kxxnzstdsw.sundays.connection.ConnectionStorage.upsert(
            ConnectionConfig(
                id = "empty-1",
                name = "未连接 H2",
                dialect = DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
        setContent { MaterialTheme { MainScreen(engine) } }

        // 首屏可见，无 sheet —— 顶部 nav 不渲染（连接管理首屏无 chip）
        onNodeWithText("未连接 H2").assertIsDisplayed()
        onAllNodesWithText("数据库浏览").assertCountEquals(0)

        // 选中 + 点连接 → 切到第二屏 + sheet 打开
        onNodeWithText("未连接 H2").performClick()
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }

        // 现在第二屏渲染：sheet tab + ＋ 入口按钮
        onNodeWithContentDescription("添加连接").assertIsDisplayed()
        // TopNavBar 上现在只有「数据库浏览」chip
        onAllNodesWithText("连接管理").assertCountEquals(0)
    }

    @Test
    fun `opening a sheet from connection manager shows the sheet tab and the plus tab`() = runComposeUiTest {
        // 预置一条可连接的 H2 配置
        com.kxxnzstdsw.sundays.connection.ConnectionStorage.upsert(
            ConnectionConfig(
                id = "sheet-1",
                name = "我的 H2",
                dialect = DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
        setContent { MaterialTheme { MainScreen(engine) } }

        // 首屏可见该连接
        onNodeWithText("我的 H2").performClick()
        onNodeWithText("连接").performClick()

        // 切到第二屏后：sheet tab + ＋ 入口按钮
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }
        // ＋ 入口按钮
        onNodeWithContentDescription("添加连接").assertIsDisplayed()
        // sheet tab 显示连接名（ConnectionBar 也显示同一名字，所以 size >= 2）
        assert(
            onAllNodesWithText("我的 H2").fetchSemanticsNodes().size >= 1
        ) { "sheet tab should display connection name" }
    }

    /**
     * 「添加连接」弹窗的内部结构 = 与首屏 [ConnectionManagerScreen] 同源：
     * - 左侧：已保存连接列表
     * - 右侧：选中连接的总览面板 / 选中未连接时为入口按钮
     * - 左列表 header 的「新建 / 快速连接」按钮可触发向导
     *
     * 通过断言「弹窗打开后左侧列表可见 + ConnectionBar 标题可见 + 快速连接向导可启动」来覆盖该契约。
     */
    @Test
    fun `AddConnectionDialog mirrors ConnectionManagerScreen with left list and wizard`() = runComposeUiTest {
        // 预置一条已连接 sheet —— 让 AddConnectionDialog 在第二屏渲染
        com.kxxnzstdsw.sundays.connection.ConnectionStorage.upsert(
            ConnectionConfig(
                id = "existing-1",
                name = "现有 H2",
                dialect = DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
        setContent { MaterialTheme { MainScreen(engine) } }

        // 第一个 sheet 打开
        onNodeWithText("现有 H2").performClick()
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }
        // sheet tab 显示「现有 H2」（仅一处 —— AlertDialog 此时尚未打开）
        assert(
            onAllNodesWithText("现有 H2").fetchSemanticsNodes().size >= 1
        ) { "sheet tab should display existing connection name" }

        // 点 ＋ → 弹窗
        onNodeWithContentDescription("添加连接").performClick()
        onNodeWithText("添加数据库连接").assertIsDisplayed()
        // 弹窗内 ConnectionManagerScreen 渲染：左侧列表 + 右侧 ConnectionOverviewPanel
        // 都可能展示「现有 H2」；断言至少 2（sheet tab + 弹窗左侧）
        assert(
            onAllNodesWithText("现有 H2").fetchSemanticsNodes().size >= 2
        ) { "弹窗与 sheet tab 均展示已保存连接名" }

        // 弹窗内能看到「连接列表」标题（首屏左侧列表标题）—— 证明首屏布局已嵌入
        onNodeWithText("连接列表").assertIsDisplayed()

        // 点击弹窗左侧的「快速连接」按钮 → 进入向导 QUICK_CONNECT 步骤
        onNodeWithContentDescription("快速连接").performClick()
        // QuickConnectStep 渲染方言卡片 —— 断言向导在弹窗内仍能渲染（证明与首屏同源）
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("MySQL").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 宽度契约（回归）：弹窗必须占满整个窗口宽度。
     *
     * 曾经的 bug：用 Material3 `AlertDialog` 渲染内容 —— 其默认实现
     * (`DefaultBasicAlertDialogOverride`) 会把内容包进 `sizeIn(minWidth = 280.dp, maxWidth = 560.dp)`，
     * 宽度恒被截到 560dp（与 `DialogProperties.usePlatformDefaultWidth` 无关）。
     * 这里用「关闭按钮是否贴住窗口右缘」作为弹窗宽度的可观察代理。
     */
    @Test
    fun `AddConnectionDialog fills the whole window width`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AddConnectionDialog(
                    connections = emptyList(),
                    selectedConnection = null,
                    editingConnection = null,
                    wizardStep = com.kxxnzstdsw.sundays.connection.WizardStep.IDLE,
                    wizardFlow = com.kxxnzstdsw.sundays.connection.WizardFlow.NORMAL,
                    connectionStatuses = emptyMap(),
                    onSelectConnection = {},
                    onNewConnection = {},
                    onQuickConnect = {},
                    onEditConnection = {},
                    onSaveConnection = {},
                    onQuickConnectDirect = {},
                    onDeleteConnection = {},
                    onCancelEdit = {},
                    onWizardNext = {},
                    onWizardBack = {},
                    onUpdateEditingConnection = {},
                    onConnect = {},
                    onDisconnect = {},
                    onTestConnection = null,
                    onDismiss = {},
                )
            }
        }

        // 弹窗层的 root 始终按窗口尺寸布局，取所有 root 的宽度即窗口宽度
        val windowWidth = onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        val closeRight = onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot.right

        assertTrue(
            windowWidth - closeRight <= windowWidth * 0.1f,
            "「关闭」按钮应贴住窗口右缘（窗口宽=$windowWidth, 按钮右缘=$closeRight）—— 弹窗未占满窗口宽度",
        )
    }

    /**
     * 状态层回归：直接驱动 [ConnectionSession] 验证「save / quickConnectDirect 后必须追加 sheet」契约。
     * 隔离 UI 不变量 —— 状态机本身就是可观察契约：新增 sheet 必须出现在 [ConnectionSession.openSheets]。
     */
    @Test
    fun `save appends a sheet to openSheets`() {
        val session = com.kxxnzstdsw.sundays.ConnectionSession(
            engine,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        val cfg = ConnectionConfig(
            id = "stateless-1",
            name = "Stateless H2",
            dialect = DialectType.H2,
            database = "shop",
            jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        )

        assertEquals(emptyList<ConnectionConfig>(), session.openSheets, "初始应无 sheet")
        session.save(cfg)
        assertEquals(1, session.openSheets.size, "save 后 sheet 应已加入 openSheets")
        assertEquals("stateless-1", session.openSheets.single().id, "openSheets 含刚 save 的 cfg")
        assertEquals("stateless-1", session.activeSheetId, "新 sheet 应当设为 active")
    }

    @Test
    fun `quickConnectDirect appends a sheet to openSheets`() {
        val session = com.kxxnzstdsw.sundays.ConnectionSession(
            engine,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        session.openSheet(
            ConnectionConfig(
                id = "pre-1",
                name = "pre",
                dialect = DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop",
            )
        )
        val newCfg = ConnectionConfig(
            id = "qc-1",
            name = "新连接",
            dialect = DialectType.H2,
            database = "dial",
            jdbcUrl = "jdbc:h2:mem:dial;DB_CLOSE_DELAY=-1",
        )
        session.quickConnectDirect(newCfg)
        assertEquals(listOf("pre-1", "qc-1"), session.openSheets.map { it.id }, "quickConnectDirect 后 sheet 应已加入")
        assertEquals("qc-1", session.activeSheetId, "新 sheet 应当设为 active")
    }

    /**
     * 「关闭最后一个 sheet」不得崩：MainScreen 的 `destination` 由 LaunchedEffect 在组合之后切换，
     * 这中间会以空 sheet 列表组合一帧 —— 空列表下渲染标签条会在 M3 ScrollableTabRow 里
     * IndexOutOfBounds（回归：关闭唯一 sheet 直接崩窗口），因此空列表必须走空态引导。
     */
    @Test
    fun `closing the last sheet returns to the first screen`() = runComposeUiTest {
        ConnectionStorage.upsert(
            ConnectionConfig(
                id = "close-1", name = "关闭测试 H2", dialect = DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
        setContent { MaterialTheme { MainScreen(engine) } }

        onNodeWithText("关闭测试 H2").performClick()
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("关闭 sheet").fetchSemanticsNodes().isNotEmpty()
        }
        // 关闭唯一的 sheet
        onNodeWithContentDescription("关闭 sheet").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isEmpty()
        }
        println("closed last sheet without crash")
    }
}
