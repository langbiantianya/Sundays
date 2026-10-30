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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.analysis.ANALYSIS_RESULT_TABLE_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_TREE_DRAG_HANDLE_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_TREE_PANEL_TAG
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.objectTreeRowTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SIDE_PANE_COLLAPSED_WIDTH
import com.langbiantianya.sundays.ui.SIDE_PANE_RAIL_TAG
import com.langbiantianya.sundays.ui.SIDE_PANE_TOGGLE_TAG
import com.langbiantianya.sundays.ui.SundaysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 树面板的**折叠到侧边**与**宽度可调**契约 —— 浏览模式与分析模式**两侧都要成立**。
 *
 * ## 为什么两侧写在一个文件里
 *
 * 这两件事的实现是**同一个组件**（`com.langbiantianya.sundays.ui.SidePaneShell`），
 * 两边的差异只在「谁持有状态」与「面板长什么样」。把两条契约写在同一个文件里，
 * 才能一眼看出「两边的手感一致」这件事是被测过的，而不是碰巧。
 *
 * ## 折叠到底意味着什么
 *
 * 折叠不是「把面板变窄」—— 是**把整棵树从屏幕上拿走**，左边只留一条 44dp 的窄轨。
 * 所以断言有三条：
 *
 * 1. 窄轨出现了，面板与树都没了；
 * 2. **右边的区域变宽了**（这才是折叠的目的 —— 把宽度让给右边）；
 * 3. 再点一下能原样回来。
 *
 * ## 宽度可调是**两件独立的事**
 *
 * 分析模式此前是写死的 240dp，想看长表名只能干瞪眼；浏览模式早就能拖。
 * 因此两侧都要各有一条「拖得动」的断言 —— 只测一边的话，
 * 回归时另一边的把手可能悄悄变成死的（拖下去纹丝不动，界面也不报错）。
 */
@OptIn(ExperimentalTestApi::class)
class TreePaneCollapseTest {
    /**
     * 允许损失的 touch slop（dp）—— `detectDragGestures` 判定「这是拖拽」之前吃掉的那段位移。
     *
     * 与 `SchemaPanelWidthTest` 取同一个预算：具体数值由平台的 `ViewConfiguration.touchSlop` 决定，
     * 测试环境不该因为某个平台的 slop 是 18 还是 20 就红。
     */
    private val touchSlopBudget = 24.dp

    private val treeRoots =
        listOf(
            ObjectTreeNode(
                id = "workspace",
                label = "workspace.duckdb",
                kind = ObjectTreeKind.DATABASE,
                badge = "DuckDB",
            ),
        )

    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
        )

    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        // 引擎只是个空壳 —— 这个测试只关心**布局**，不碰任何数据。
        // 驱动目录指到不存在的路径，免得真去加载方言。
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        runCatching { PoolManager.closeAll() }
    }

    // ---------------------------------------------------------------------------
    // 断言用的度量
    // ---------------------------------------------------------------------------

    /** 某个 tag 的节点当前宽度（dp）—— 量的是**面板本身**，不是里面的文字。 */
    private fun androidx.compose.ui.test.ComposeUiTest.widthOf(tag: String): Dp = onNodeWithTag(tag).getUnclippedBoundsInRoot().width

    /**
     * 在分隔条上按下、水平移动 [by]、松开。
     *
     * 用触摸作用域而不是鼠标作用域：`MouseInjectionScope` 只暴露 `click` / `moveTo` 这类
     * 成品动作，没有 `down` / `up`，而 `detectDragGestures` 需要一次完整的按下-移动-抬起。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.dragHandle(
        tag: String,
        by: Dp,
    ) {
        onNodeWithTag(tag).performTouchInput {
            down(center)
            moveBy(Offset(by.toPx(), 0f))
            up()
        }
    }

    // ---------------------------------------------------------------------------
    // 分析模式
    // ---------------------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.setAnalysisScreen() {
        registerBuiltinEditors()
        setContent {
            SundaysTheme {
                ProvideStrings(AppLanguage.ZH_HANS) {
                    AnalysisScreen(
                        sources = sources,
                        treeRoots = treeRoots,
                    )
                }
            }
        }
    }

    @Test
    fun `the analysis tree panel can be dragged wider`() =
        runComposeUiTest {
            setAnalysisScreen()

            val before = widthOf(ANALYSIS_TREE_PANEL_TAG)
            dragHandle(ANALYSIS_TREE_DRAG_HANDLE_TAG, 80.dp)
            waitForIdle()
            val after = widthOf(ANALYSIS_TREE_PANEL_TAG)

            assertTrue(
                after > before,
                "分析模式的左树应当能拖宽：before=$before after=$after",
            )
            val gained = after - before
            assertTrue(
                gained > 80.dp - touchSlopBudget,
                "拖拽应跟手（允许 touch slop 预算）：期望 ≥${80.dp - touchSlopBudget}，实际 +$gained",
            )
        }

    @Test
    fun `the analysis tree panel collapses into a rail and the right side gets the space back`() =
        runComposeUiTest {
            setAnalysisScreen()

            val resultBefore = widthOf(ANALYSIS_RESULT_TABLE_TAG)
            assertTrue(
                onAllNodesWithTag(SIDE_PANE_RAIL_TAG).fetchSemanticsNodes().isEmpty(),
                "前置条件：一开始不该有窄轨",
            )

            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            // 窄轨在，面板与树都不在了。
            assertEquals(
                1,
                onAllNodesWithTag(SIDE_PANE_RAIL_TAG).fetchSemanticsNodes().size,
                "折叠后应当只剩一条窄轨",
            )
            onAllNodesWithTag(ANALYSIS_TREE_PANEL_TAG).assertCountEquals(0)
            onAllNodesWithTag(objectTreeRowTag("workspace")).assertCountEquals(0)

            // **折叠的目的**是把宽度让给右边 —— 这一条才是「折叠有没有用」的判据。
            val resultAfter = widthOf(ANALYSIS_RESULT_TABLE_TAG)
            assertTrue(
                resultAfter > resultBefore,
                "折叠后右边的结果区应变宽：before=$resultBefore after=$resultAfter",
            )

            // 再点一下回来。
            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            onAllNodesWithTag(SIDE_PANE_RAIL_TAG).assertCountEquals(0)
            assertEquals(
                1,
                onAllNodesWithTag(ANALYSIS_TREE_PANEL_TAG).fetchSemanticsNodes().size,
                "展开后左树面板应当回来",
            )
            assertEquals(
                1,
                onAllNodesWithTag(objectTreeRowTag("workspace")).fetchSemanticsNodes().size,
                "展开后树应当回来",
            )
        }

    @Test
    fun `the collapsed analysis rail is narrow and has no drag handle`() =
        runComposeUiTest {
            // 窄轨上不该还挂着一个分隔条 —— 44dp 的轨道没有「可调宽」可言，
            // 留个把手在那儿就是「看起来能拖，拖了纹丝不动」。
            setAnalysisScreen()

            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            assertEquals(
                SIDE_PANE_COLLAPSED_WIDTH,
                widthOf(SIDE_PANE_RAIL_TAG),
                "窄轨应当就是那个固定宽度",
            )
            onAllNodesWithTag(ANALYSIS_TREE_DRAG_HANDLE_TAG).assertCountEquals(0)
        }

    // ---------------------------------------------------------------------------
    // 浏览模式
    // ---------------------------------------------------------------------------

    private fun browseConnection() =
        ConnectionConfig(
            id = "collapse-${System.nanoTime()}",
            name = "Alpha",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = "jdbc:h2:mem:collapse;DB_CLOSE_DELAY=-1",
        )

    private fun androidx.compose.ui.test.ComposeUiTest.setBrowserScreen(conn: ConnectionConfig) {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets =
                        listOf(
                            SheetDescriptor(
                                connection = conn,
                                browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default)),
                                // 未连接：树面板显示「未连接」的空态，
                                // 但**面板头与外壳照样在** —— 折叠是布局行为，与连没连无关。
                                status = ConnectionStatus(ConnectionState.DISCONNECTED, "H2"),
                            ),
                        ),
                    activeSheetId = conn.id,
                    connections = listOf(conn),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `the browser tree panel collapses into a rail and comes back`() =
        runComposeUiTest {
            val conn = browseConnection()
            setBrowserScreen(conn)

            val before = widthOf(SCHEMA_PANEL_TAG)
            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            assertEquals(
                1,
                onAllNodesWithTag(SIDE_PANE_RAIL_TAG).fetchSemanticsNodes().size,
                "折叠后应当只剩一条窄轨",
            )
            onAllNodesWithTag(SCHEMA_PANEL_TAG).assertCountEquals(0)
            assertEquals(
                SIDE_PANE_COLLAPSED_WIDTH,
                widthOf(SIDE_PANE_RAIL_TAG),
                "窄轨应当就是那个固定宽度",
            )
            assertTrue(
                before > SIDE_PANE_COLLAPSED_WIDTH,
                "前置条件：展开态的面板应当比窄轨宽（before=$before）",
            )

            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            onAllNodesWithTag(SIDE_PANE_RAIL_TAG).assertCountEquals(0)
            assertEquals(
                1,
                onAllNodesWithTag(SCHEMA_PANEL_TAG).fetchSemanticsNodes().size,
                "展开后面板应当回来",
            )
            assertEquals(
                before,
                widthOf(SCHEMA_PANEL_TAG),
                "展开后宽度应当回到折叠前 —— 折叠不该顺手改掉用户拖好的宽度",
            )
        }

    @Test
    fun `the browser tree panel keeps its dragged width across a collapse and expand`() =
        runComposeUiTest {
            // 折叠**不该**把用户拖好的宽度弄丢 —— 否则「拖宽 → 折叠看一眼 → 展开」
            // 之后宽度跳回默认，用户得重新拖一遍。
            val conn = browseConnection()
            setBrowserScreen(conn)

            dragHandle(SCHEMA_DRAG_HANDLE_TAG, 80.dp)
            waitForIdle()
            val dragged = widthOf(SCHEMA_PANEL_TAG)
            assertTrue(
                dragged > SCHEMA_PANEL_DEFAULT_WIDTH,
                "前置条件：先拖宽（default=${SCHEMA_PANEL_DEFAULT_WIDTH} dragged=$dragged）",
            )

            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(SIDE_PANE_TOGGLE_TAG).performClick()
            waitForIdle()

            assertEquals(
                dragged,
                widthOf(SCHEMA_PANEL_TAG),
                "折叠再展开后宽度应当还是拖好的那个",
            )
        }
}
