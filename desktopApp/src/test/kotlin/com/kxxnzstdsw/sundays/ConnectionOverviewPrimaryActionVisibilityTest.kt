package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Test
import com.kxxnzstdsw.sundays.connection.CONNECT_OVERVIEW_ACTIONS_TAG
import com.kxxnzstdsw.sundays.connection.CONNECT_OVERVIEW_CARD_TAG
import com.kxxnzstdsw.sundays.connection.CONNECT_OVERVIEW_PANEL_TAG
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep
import kotlin.test.assertTrue

/**
 * 连接总览面板的**首屏可用性** —— 「连接 / 编辑 / 删除」必须开屏就看得见。
 *
 * ## 这条契约是怎么来的
 *
 * §9.4 曾把「这三个按钮看不见」记成产品缺陷，查了两轮。真窗口复验后发现：
 * **布局逐像素是对的**，看见的是**截图探针自己的 bug**（DPI 逻辑/物理坐标混用，
 * 只截到窗口左上角一块，见 TEST_CASES.md §9.12）。
 *
 * 但复验过程中量出一个**真会咬人**的数字：总览面板的内容高度约 388dp，
 * 而最小窗口（[MIN_WINDOW_SIZE] 1024×640dp）扣掉标题栏与上下内边距后只剩约 568dp。
 * 余量看着够，可它**只够再塞四行**「名称 / 方言 / 端口…」——
 * 哪天连接摘要多几行（比如加 SSH、证书、连接串），按钮就会被顶出可视区。
 *
 * 那时用户看到的是：卡片正常、下面什么都没有、也没有滚动条提示，
 * 首屏唯一的「连接」入口凭空消失。这条测试就是钉住那个余量。
 *
 * ## 为什么必须量边界而不是数语义节点
 *
 * 语义树里按钮一直都在（这也是当初误判成「画到窗口外」的原因之一）。
 * 「渲染出来但落在可视区外」只有量得到的边界能证伪。
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionOverviewPrimaryActionVisibilityTest {

    /**
     * 最小支持窗口扣掉非内容区后，右栏真正能用的高度。
     *
     * - 1024×640dp 是 [MIN_WINDOW_SIZE]，比它更小的窗口根本开不出来
     * - 标题栏约 24dp
     * - 向导内容的上下内边距各 24dp（`ConnectionWizardContent` 的 padding）
     */
    private val MIN_CONTENT_HEIGHT = (640 - 24 - 48).dp

    private val conn = ConnectionConfig(
        id = "vis-1",
        name = "可见性连接",
        dialect = DialectType.MYSQL,
        username = "root",
        password = "",
        host = "192.168.1.5",
        port = 3306,
        database = "sundays_probe",
        jdbcUrl = "jdbc:mysql://192.168.1.5:3306/sundays_probe",
    )

    /** 量「布局算出来的」边界 —— 不可见的节点才量得到，语义树本身证明不了「在不在屏上」。 */
    private fun boundsOf(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot()

    /**
     * 模拟「窗口可视区」的盒子 —— 它的尺寸就是被测窗口的尺寸。
     *
     * 单独加 tag 而不是量 `onRoot()`：测试场景本身固定 1024×768，
     * 真正的「窗口」是我们套进去的那个定尺寸盒子。
     */
    private companion object {
        const val VIEWPORT_TAG = "overviewViewport"
    }

    /**
     * 最小支持窗口的内容高度（外层盒子给的高度）。
     *
     * 640dp 就是 [MIN_WINDOW_SIZE] 的下限：比它更小的窗口由 `EnforceMinimumWindowSize` 夹回来。
     * 实测在此高度下按钮行底边在 441dp，**余量约 200dp**（≈ 六行摘要）——
     * 哪天连接摘要多几行，这条余量就是防线。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.renderOverview(heightDp: Int) {
        setContent {
            // density=1 让测试场景的 1024×768px 直接等于 1024×768dp ——
            // 于是可以把「最小窗口的内容高度」用一个固定高度的 Box 精确框出来。
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .size(1024.dp, heightDp.dp)
                                .testTag(VIEWPORT_TAG),
                        ) {
                            ConnectionManagerScreen(
                                connections = listOf(conn),
                                selectedConnection = conn,
                                editingConnection = null,
                                wizardStep = WizardStep.IDLE,
                                wizardFlow = WizardFlow.NORMAL,
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
                                connectionStatuses = mapOf(
                                    conn.id to ConnectionStatus(ConnectionState.DISCONNECTED),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `connect edit and delete are inside the viewport at the minimum window size`() =
        runComposeUiTest {
            renderOverview(640)

            val root = boundsOf(onNodeWithTag(VIEWPORT_TAG))
            val actions = onNodeWithTag(CONNECT_OVERVIEW_ACTIONS_TAG)
            val actionsBounds = boundsOf(actions)

            assertTrue(
                actionsBounds.height.value > 0f && actionsBounds.width.value > 0f,
                "按钮行应真的有尺寸（为 0 说明它被排到了可视区外或没被布局）：$actionsBounds",
            )
            assertTrue(
                actionsBounds.bottom.value <= root.bottom.value,
                "「连接」按钮必须落在可视区底边之内，否则首屏唯一的连接入口看不见：" +
                    "buttons=$actionsBounds root=$root",
            )
            // 逐个按钮再钉一次：整行在界内还不够，右对齐的「连接」最靠右，最容易先出界
            for (label in listOf("删除", "编辑", "连接")) {
                val nodes = onAllNodes(hasText(label))
                val count = nodes.fetchSemanticsNodes().size
                assertTrue(count == 1, "「$label」应恰好命中一个按钮，实际 $count")
                val b = boundsOf(nodes[0])
                assertTrue(
                    b.top.value >= root.top.value && b.bottom.value <= root.bottom.value &&
                        b.left.value >= root.left.value && b.right.value <= root.right.value,
                    "「$label」按钮必须完整落在窗口内：button=$b root=$root",
                )
            }
        }

    @Test
    fun `the action row stays reachable by scrolling when the panel is shorter than its content`() =
        runComposeUiTest {
            // 这个高度**低于**应用允许的最小窗口，是「内容装不下」时的样子。
            //
            // ⚠️ 这里量的是 **unclipped** 边界：按钮被顶到视口外时，它仍然报得出真实的
            // 宽高与位置，只是位置在视口下沿之外。所以「不可见」必须靠**与视口比大小**
            // 判定，不能靠「尺寸是不是 0」—— 早先按 0×0 写这条断言，结果完全测不出东西。
            //
            // 有了这个前提，上面那条用例里「按钮在界内」才是有意义的正面结论：
            // 一头证明「装得下」，另一头证明「装不下时至少还够得着」。
            renderOverview(300)
            val root = boundsOf(onNodeWithTag(VIEWPORT_TAG))
            val actions = onNodeWithTag(CONNECT_OVERVIEW_ACTIONS_TAG)

            val before = boundsOf(actions)
            assertTrue(
                before.height.value > 0f,
                "按钮行应真的有尺寸（为 0 说明它根本没被布局）：$before",
            )
            assertTrue(
                before.bottom.value > root.bottom.value,
                "视口比内容矮时按钮行应被顶到界外（这条断言守护的是那个前提）：" +
                    "buttons=$before root=$root",
            )
            // 语义树里它还得在 —— 可达性不能靠「渲染出来」证明，键盘/读屏走的是语义树
            assertTrue(
                onAllNodes(hasText("连接")).fetchSemanticsNodes().size == 1,
                "按钮即使在界外也必须留在语义树里",
            )
            // 滚动能把它带进视野 —— 这是「被顶下去」与「彻底够不着」的分界
            actions.performScrollTo()
            waitForIdle()
            val after = boundsOf(actions)
            assertTrue(
                after.bottom.value <= root.bottom.value && after.top.value >= root.top.value,
                "滚动后按钮行应完整进入视口：after=$after root=$root",
            )
        }

    @Test
    fun `the info card and the action row share the same content width`() = runComposeUiTest {
        // 右栏两段必须**左右对齐**：卡片铺满而按钮行按内容收缩时，
        // `Arrangement.End` 会把按钮推到与卡片右缘不一致的位置，
        // 看上去像「右边少了一块内边距」（§9.12 里那条误判的视觉来源之一）。
        renderOverview(640)
        val card = boundsOf(onNodeWithTag(CONNECT_OVERVIEW_CARD_TAG))
        val actions = boundsOf(onNodeWithTag(CONNECT_OVERVIEW_ACTIONS_TAG))
        assertTrue(
            kotlin.math.abs(card.left.value - actions.left.value) < 1f,
            "卡片与按钮行左缘应一致：card=$card actions=$actions",
        )
        assertTrue(
            kotlin.math.abs(card.right.value - actions.right.value) < 1f,
            "卡片与按钮行右缘应一致（按钮行必须 fillMaxWidth，不能按内容收缩）：" +
                "card=$card actions=$actions",
        )
        // 两者都应落在总览列内，而不是把列撑宽 —— 这一条才是「右边距没有消失」的正面写法
        val panel = boundsOf(onNodeWithTag(CONNECT_OVERVIEW_PANEL_TAG))
        assertTrue(
            card.right.value <= panel.right.value + 0.5f,
            "卡片不该超出总览列：panel=$panel card=$card",
        )
    }
}