/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.analysis.ANALYSIS_TREE_LIST_TAG
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.TREE_PANE_TOGGLE_TAG
import com.langbiantianya.sundays.tree.objectTreeRowTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 树面板折叠**不丢滚动位置**的契约 —— 浏览模式与分析模式**两侧都要成立**。
 *
 * ## 这条契约是怎么来的
 *
 * 折叠到侧边时整棵树会从组合里移除（44dp 的窄轨塞不下树）——
 * 树自己的滚动容器被拆掉，**记在容器里的滚动位置就跟着没了**。
 * 于是「滚到第 30 张表 → 折叠看一眼右边 → 展开」之后，树回到顶部，
 * 用户得重新滚回去 —— 折叠本该是「让右边宽一点」，不该顺手把左边也弄丢。
 *
 * 修法是**把滚动状态提到面板之外**：浏览模式放进会话（每个连接各一棵，
 * 位置也跟着各归各），分析模式放进屏幕层。
 *
 * ## 为什么两条用例的判据不一样
 *
 * - **分析模式**：树是一个普通 `Column`（不虚拟化），**每一行都在语义树里**，
 *   所以直接量那一行的 y —— 折叠再展开后 y 不变，就是位置没丢。量的是**画面**。
 * - **浏览模式**：树在 `LazyColumn` 里（会虚拟化），滚出去的行**不在语义树里**，
 *   量不到 y；改量滚动状态自己的偏移量 —— 那是**这份状态**的真相。
 *
 * 两条都是「折叠前后同一件事没变」，不是「某个内部字段等于某个常量」。
 */
@OptIn(ExperimentalTestApi::class)
class TreePaneScrollTest {
    /** 表的数量 —— 树要**足够高**才滚得动，否则测的是「本来就没得滚」。 */
    private val tableCount = 40

    // =========================================================================
    // 分析模式（不需要引擎，确定性）
    // =========================================================================

    /** 一棵**很高**的树：一个库 + 40 张表。 */
    private val tallTree =
        listOf(
            ObjectTreeNode(
                id = "workspace",
                label = "workspace.duckdb",
                kind = ObjectTreeKind.DATABASE,
                children =
                    (0 until tableCount).map { i ->
                        ObjectTreeNode(
                            id = "table:$i",
                            label = "T_" + i.toString().padStart(2, '0'),
                            kind = ObjectTreeKind.TABLE,
                        )
                    },
            ),
        )

    @Test
    fun `the analysis tree keeps its scroll position across a collapse and expand`() =
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = listOf(AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL")),
                            treeRoots = tallTree,
                            expandedNodeIds = setOf("workspace"),
                        )
                    }
                }
            }

            // 量的是**第 6 行**：它在初始位置肯定在视口里，
            // 滚下去之后又会跑到视口**上方**（y 变负）——
            // 树不虚拟化，所以它一直都在语义树里，量得到。
            val rowTag = objectTreeRowTag("table:5")
            val topBefore = onNodeWithTag(rowTag).getUnclippedBoundsInRoot().top
            assertTrue(topBefore > 0.dp, "前置条件：第 6 行初始应在视口内（top=$topBefore）")

            onNodeWithTag(ANALYSIS_TREE_LIST_TAG).performTouchInput { swipeUp() }
            waitForIdle()
            val scrolledTop = onNodeWithTag(rowTag).getUnclippedBoundsInRoot().top
            assertTrue(
                scrolledTop < topBefore,
                "前置条件：树应当滚得动（滚前 top=$topBefore，滚后 top=$scrolledTop）",
            )

            // 折叠 → 展开
            onNodeWithTag(TREE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(TREE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            val topAfter = onNodeWithTag(rowTag).getUnclippedBoundsInRoot().top
            assertTrue(
                topAfter - scrolledTop < 1.dp && scrolledTop - topAfter < 1.dp,
                "折叠再展开后滚动位置应保持：折叠前 top=$scrolledTop，展开后 top=$topAfter",
            )
        }

    // =========================================================================
    // 浏览模式（真 H2）
    // =========================================================================

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var connection: ConnectionConfig

    /** 每个用例创建的 scope —— tearDown 里统一 cancel（见 [browser]）。 */
    private val scopes = mutableListOf<CoroutineScope>()

    /** 轮询预算 —— 必须**远小于**外层 `testTimeout`，否则外层先掐断，报的还是那层壳。 */
    private val awaitBudgetMillis = 30_000L

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-tree-scroll").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("H2", H2Dialect())

        val dbName = "treescroll_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { s ->
                repeat(tableCount) { i ->
                    s.executeUpdate("CREATE TABLE T_${i.toString().padStart(2, '0')} (id INT PRIMARY KEY)")
                }
            }
        }

        connection =
            ConnectionConfig(
                id = "ts-${System.nanoTime()}",
                name = "ScrollH2",
                dialect = DialectType.H2,
                username = "sa",
                password = "",
                jdbcUrl = jdbcUrl,
            )
    }

    @After
    fun tearDown() {
        // scope 先 cancel，再关引擎 —— 顺序反了会出现「协程正用着池，池先被关了」的竞态
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        try {
            PoolManager.closeAll()
        } catch (_: Exception) {
        }
        try {
            DriverManager
                .getConnection(jdbcUrl, "sa", "")
                .use { it.createStatement().use { s -> s.execute("SHUTDOWN") } }
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    /**
     * 本次测试用的状态机 —— scope **必须持有并在 tearDown 里取消**。
     *
     * 调度器用 `Unconfined`：`engine.invoke` 在第一个挂起点（Hikari 阻塞 IO）之后
     * 由别的线程恢复并写快照状态，`Default` 上那次写入不在测试节拍里。
     */
    private fun browser(): DatabaseBrowserState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return DatabaseBrowserState(engine, scope)
    }

    /**
     * 自建轮询 —— **不用 `waitUntil`**。
     *
     * `waitUntil` 内部靠 `waitForIdle` 推进，而它只等「**当前已排队**」的任务。
     * 状态写入发生在别的线程（`engine.invoke` 在挂起点后由 IO 线程 resume）时，
     * 那一帧还没被登记为待处理，`waitForIdle` 认为无事可做直接返回，条件检查随即
     * 判 false —— 于是无论等多久都不会过（与 `TreeContextMenuUiTest` 同一个坑）。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitCondition(
        what: String,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > awaitBudgetMillis) {
                throw AssertionError("等待超时：$what（${System.currentTimeMillis() - start}ms）")
            }
            // ⚠️ `waitForIdle` 在前、`advanceTimeByFrame` 在后，两个都要：
            // - `waitForIdle` 把**已排队**的重组帧一次跑完（跨线程写入登记进来后才有效）
            // - `advanceTimeByFrame` 保证即使此刻没有待处理帧，也推进一帧让时钟继续走
            runCatching { waitForIdle() }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    /** 渲染浏览屏，并等到**表叶子**出现在语义树里（树得先有内容才谈得上滚动）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.render(browser: DatabaseBrowserState) {
        val sheet =
            SheetDescriptor(
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
        awaitCondition("状态机拉回库列表") {
            !browser.loadingDatabases && browser.databases.isNotEmpty()
        }
        // 先点**状态机**展开库 —— `Screen` 自己发的那一发 `SCHEMA.LIST`
        // 在 UI 测试里不一定跑完（协程挂在组合作用域上，引擎往返一慢就停在「加载中」）。
        browser.toggleDatabase(browser.databases.first())
        awaitCondition("语义树里出现表叶子") {
            onAllNodesWithTag(schemaTableLeafTag("T_00")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the browser tree keeps its scroll position across a collapse and expand`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            val b = browser()
            render(b)

            onNodeWithTag(SCHEMA_PANEL_TAG).performTouchInput { swipeUp() }
            waitForIdle()
            val offset = b.treeScrollState.firstVisibleItemScrollOffset
            assertTrue(offset > 0, "前置条件：树应当滚得动（offset=$offset）")

            // 折叠 → 展开
            onNodeWithTag(TREE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(TREE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            assertEquals(
                offset,
                b.treeScrollState.firstVisibleItemScrollOffset,
                "折叠再展开后滚动位置应保持",
            )
        }
}
