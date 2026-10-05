package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
 * 左侧库/表树面板的**宽度可拖拽**契约 —— 真 Compose UI 测试（真鼠标拖拽 + 真双击）。
 *
 * ## 为什么必须是 UI 测试而不是纯函数测试
 *
 * [com.kxxnzstdsw.sundays.ui.nextPaneWidth] 的算术已在 `DragHandleTest` 里覆盖，
 * 但真正的交付契约有三件事是纯函数测不到的：
 *
 * 1. 分隔条**接上了** `onWidthChange`（没接的话面板纹丝不动，算术再对也没用）
 * 2. 宽度**真的落在了面板上**（量的是面板本身，不是里面某行文字）
 * 3. 切 sheet 后宽度**不丢**（这条直接对应本轮最重要的架构决策）
 *
 * ## 为什么必须量面板而不是文字
 *
 * 树里的表名长度各不相同，量文字节点宽度等于在断言「你的表名有多长」。
 */
@OptIn(ExperimentalTestApi::class)
class SchemaPanelWidthTest {

    /**
     * 允许损失的 touch slop（dp）—— `detectDragGestures` 判定「这是拖拽」之前吃掉的那段位移。
     *
     * 取 24 留了一点余量：具体数值由平台的 `ViewConfiguration.touchSlop` 决定，
     * 测试环境不该因为某个平台的 slop 是 18 还是 20 就红。
     */
    private val TOUCH_SLOP_BUDGET = 24.dp

    /**
     * 面板宽度占容器的比例上限 —— 这里**独立写死**一份，不去引用生产代码里的常量。
     *
     * 引用同一个常量的话，改了生产值测试会跟着变，永远绿 —— 那就测不出「比例被换成了固定 dp」
     * 这类回归。独立副本才有牙齿。
     */
    private val EXPECTED_MAX_WIDTH_RATIO = 0.62f

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var connA: ConnectionConfig
    private lateinit var connB: ConnectionConfig

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-schema-width").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("H2", H2Dialect())
        registerBuiltinEditors()

        val dbName = "schemawidth_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)") }
        }

        connA = ConnectionConfig(
            id = "a-${System.nanoTime()}",
            name = "Alpha",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = jdbcUrl,
        )
        connB = connA.copy(id = "b-${System.nanoTime()}", name = "Beta")
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try {
            DriverManager.getConnection(jdbcUrl, "sa", "")
                .use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } }
        } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    private fun sheet(c: ConnectionConfig) = SheetDescriptor(
        connection = c,
        browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default)),
        status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
    )

    /** 面板当前宽度（dp）—— 直接量面板，不经过里面的文字节点。 */
    private fun androidx.compose.ui.test.ComposeUiTest.panelWidth(): Dp =
        onNodeWithTag(SCHEMA_PANEL_TAG).getUnclippedBoundsInRoot().width

    /**
     * 在分隔条上按下、水平移动 [by]、松开。
     *
     * 用触摸作用域而不是鼠标作用域：`MouseInjectionScope` 只暴露 `click` / `moveTo` 这类
     * 成品动作，没有 `down` / `up`，而 `detectDragGestures` 需要一次完整的按下-移动-抬起。
     * 触摸与鼠标走的是同一条 `pointerInput` 链路，所以测的是同一份实现。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.dragHandle(by: Dp) {
        onNodeWithTag(SCHEMA_DRAG_HANDLE_TAG).performTouchInput {
            down(center)
            // 一次位移越过 touch slop 就够了；再补一小步让 detectDragGestures 真正进入拖拽态
            moveBy(androidx.compose.ui.geometry.Offset(by.toPx(), 0f))
            up()
        }
    }

    @Test
    fun `dragging the handle right widens the tree panel`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet(connA)),
                    activeSheetId = connA.id,
                    connections = listOf(connA),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val before = panelWidth()
        dragHandle(80.dp)
        waitForIdle()
        val after = panelWidth()

        assertTrue(
            after > before,
            "向右拖 80dp 后面板应变宽：before=$before after=$after",
        )
        // 跟手：增量应接近拖动距离，差额来自 `detectDragGestures` 的 touch slop ——
        // 越过 slop 之前的那段位移只用来判定「这是拖拽不是点击」，不计入 dragAmount。
        // 这是 Compose 拖拽的标准行为，不是缺陷，所以断言留出 slop 预算而非要求严格等量。
        val gained = after - before
        assertTrue(
            gained > 80.dp - TOUCH_SLOP_BUDGET,
            "拖拽应跟手（允许 touch slop 预算）：期望 ≥${80.dp - TOUCH_SLOP_BUDGET}，实际 +$gained",
        )
        assertTrue(
            gained <= 80.dp,
            "面板宽度不该超过实际拖动距离（说明有别的东西在放大）：实际 +$gained",
        )
    }

    @Test
    fun `dragging past the left edge stops at the minimum width`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet(connA)),
                    activeSheetId = connA.id,
                    connections = listOf(connA),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val before = panelWidth()
        dragHandle((-4000).dp)
        waitForIdle()
        val floor = panelWidth()

        assertTrue(
            floor < before,
            "往左拖应当变窄：before=$before floor=$floor",
        )
        assertTrue(
            floor > 1.dp,
            "不能被拖成 0 宽（那样树整个消失，用户没法再拖回来）：floor=$floor",
        )
        // 真正证明「被钳住了」的是这一步：继续往同一个方向猛拖，宽度一动不动。
        // 只断言「floor > 0」的话，一个把宽度卡在任意正数的 bug 也能蒙混过关。
        dragHandle((-4000).dp)
        waitForIdle()
        assertEquals(
            floor, panelWidth(),
            "已到下限后继续拖不应再变 —— 说明确实钳住了，而不是碰巧停在某个值",
        )
    }

    @Test
    fun `double clicking the handle restores the default width`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet(connA)),
                    activeSheetId = connA.id,
                    connections = listOf(connA),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val original = panelWidth()
        dragHandle(80.dp)
        waitForIdle()
        assertTrue(panelWidth() > original, "前置条件：先拖宽")

        onNodeWithTag(SCHEMA_DRAG_HANDLE_TAG).performTouchInput { doubleClick() }
        waitForIdle()

        assertEquals(
            original, panelWidth(),
            "双击应复位到默认宽度 —— 拖乱了之后最快的回退方式",
        )
    }

    @Test
    fun `dragging far right stops at a fraction of the window`() = runComposeUiTest {
        // 上限按**容器比例**给（62%），不是固定 dp：窗口窄时若还允许拉到 600dp，
        // 右侧工作台会被挤到没法用。这条断言钉住的就是这个取舍。
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet(connA)),
                    activeSheetId = connA.id,
                    connections = listOf(connA),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val windowWidth = onRoot().getUnclippedBoundsInRoot().width
        dragHandle(4000.dp)
        waitForIdle()
        val after = panelWidth()

        // 双侧都要卡：只卡上界的话，「换成固定 600dp 上限」也能蒙混过关
        // （默认测试窗口 1024dp 下 600 < 634.88，仍落在上界以内）。
        // 真正把「按比例」和「写死一个 dp」区分开的，是**下界**：必须真的顶到比例上限。
        val cap = windowWidth * EXPECTED_MAX_WIDTH_RATIO
        assertTrue(
            after > cap - 1.dp,
            "面板应顶到比例上限（写死固定 dp 上限会让它停在更小的值）：panel=$after cap=$cap",
        )
        assertTrue(
            after < cap + 1.dp,
            "面板不该超过比例上限（会把右栏挤没）：panel=$after cap=$cap",
        )
    }

    @Test
    fun `the dragged width survives switching to another sheet`() = runComposeUiTest {
        // 本轮最重要的架构决策：宽度状态放在 `DatabaseBrowserScreen` 层而不是
        // `ActiveSheetContent` 内。因为同一时刻只渲染**一个** sheet，宽度若存在
        // `ActiveSheetContent` 里，切 sheet 时组合被拆掉重建、remember 丢失 ——
        // 表现就是「在 A 把树拉宽，切到 B 又缩回默认」。
        val active = mutableStateOf(connA.id)
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet(connA), sheet(connB)),
                    activeSheetId = active.value,
                    connections = listOf(connA, connB),
                    onSelectSheet = { active.value = it },
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val defaultWidth = panelWidth()
        dragHandle(80.dp)
        waitForIdle()
        val dragged = panelWidth()
        // 前置条件：拖拽必须真的生效。否则下面「切过去宽度不变」会平凡成立 ——
        // 两边都是默认值时，相等当然成立，这条断言就白写了。
        assertTrue(
            dragged > defaultWidth,
            "前置条件：先在 A 上把树拖宽（default=$defaultWidth dragged=$dragged）",
        )

        active.value = connB.id
        waitForIdle()
        assertEquals(
            dragged, panelWidth(),
            "布局宽度是窗口级偏好，切 sheet 后应保持",
        )

        active.value = connA.id
        waitForIdle()
        assertEquals(
            dragged, panelWidth(),
            "切回来也应保持",
        )
    }

    @Test
    fun `switching the right pane does not disturb the width`() = runComposeUiTest {
        // 右栏 pane 切换（表预览 ⇄ SQL ⇄ 造数）会把子树移出再移回组合。
        // 宽度同属布局，不该跟着一起被重建掉。
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(
                        SheetDescriptor(
                            connection = connA,
                            browser = browser,
                            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
                        ),
                    ),
                    activeSheetId = connA.id,
                    connections = listOf(connA),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val defaultWidth = panelWidth()
        dragHandle(80.dp)
        waitForIdle()
        val dragged = panelWidth()
        assertTrue(
            dragged > defaultWidth,
            "前置条件：先拖宽（default=$defaultWidth dragged=$dragged）",
        )

        browser.selectPane(BrowserPane.SQL)
        waitForIdle()
        assertEquals(dragged, panelWidth(), "切到 SQL 工作台后宽度应保持")
    }
}
