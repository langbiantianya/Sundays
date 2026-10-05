package com.kxxnzstdsw.sundays

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TABLE_BODY_TAG
import com.kxxnzstdsw.sundays.table.TABLE_PAGINATION_TAG
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 分页底栏的**布局契约** —— 窄容器下不得把按钮标签压成竖排，更不得把按钮压没。
 *
 * ## 回归背景
 *
 * 浏览屏的表预览把 35% 宽度让给详情面板（`detailPanelRatio`），库/表树面板又可以
 * 被拖到窗口的 62%。两者叠加，在最小窗口（`MIN_WINDOW_SIZE` = 1024dp）下底栏只剩：
 *
 * ```
 * (1024 − 1024×0.62) × 0.65 ≈ 248dp
 * ```
 *
 * 而底栏在宽档下约需 **510dp**。`Row` 装不下时按比例压缩子项：
 *
 * - 标签被挤到不足一字宽 → `Text` 默认 `softWrap = true` 把「下一页」**竖排堆叠**，
 *   按钮长高一倍多、顶出底栏（第一版走查发现的症状）；
 * - 再挤一点 → 整颗按钮**被压成零尺寸直接消失**（语义边界量到 `Rect(0,0,0,0)`），
 *   翻页功能没了（走查后加测时才发现的第二个症状，比竖排更严重）。
 *
 * ## 为什么必须开小窗口，而不是在默认窗口里套 `Modifier.width(...)`
 *
 * `SundaysTheme` 内部有一层 `Surface(Modifier.fillMaxSize())`（见 `Theme.kt`），
 * 它把整棵子树的约束钉成**测试窗口的尺寸**。实测：在主题内给任意节点套
 * `Modifier.width(420.dp)` / `Modifier.size(...)`，量出来仍然是 1024 —— 宽度
 * 修饰符被上游约束吃掉，**静默失效**。
 *
 * 这个坑有一个很坏的性质：**它让测试「通过」而不是失败**。窄容器根本没构造出来，
 * 断言量到的是宽容器，分页条本来就是单行，于是「不折行」这条断言永远为真 ——
 * 一条永远不会红的测试比没有测试更危险。
 *
 * 正确做法是 [runDesktopComposeUiTest] 的 `width` / `height` 参数：直接开小窗口，
 * 主题的 `fillMaxSize()` 填充的就是这个尺寸。这也更贴近真实 —— 用户把窗口拖窄、
 * 把树面板拉宽，本来就是这个 bug 的触发路径。
 *
 * 下面三档窗口对应三个真实场景（`DataTable` 占满窗口，底栏 = (窗口−8) × 0.65）：
 *
 * | 窗口 | 底栏 | 对应真实场景 |
 * |---|---|---|
 * | 1024 | 660dp | 窗口最小 + 树面板收窄，底栏充裕 |
 * | 700 | 450dp | 窗口 1024 + 默认 320dp 树面板（**最常见**） |
 * | 390 | 248dp | 窗口 1024 + 树面板拖到 62%（**最坏可达**） |
 *
 * ## 为什么量底栏整体高度，而不是量单个 Text
 *
 * 分行与不分行，单看某个 `Text` 的语义边界都可能给出看似正常的宽度；
 * 只有底栏总高度会把「多出两行」这件事放大成可断言的量。
 */
@OptIn(ExperimentalTestApi::class)
class TablePaginationLayoutTest {

    private fun columns() = listOf(
        TableColumn(key = "id", header = "ID"),
        TableColumn(key = "status", header = "STATUS"),
    )

    /**
     * 250 行 / 每页 100 → 3 页。这样「首页」「上一页」禁用、「下一页」「末页」可用 ——
     * **禁用态按钮的文字宽度与可用态不同**，只有两者都在时才会暴露挤压问题。
     */
    private fun manyRows(): List<TableRow> =
        (1..250).map { i -> TableRow(id = i, cells = mapOf("id" to "$i", "status" to "row$i")) }

    /** 在 [widthPx] 宽的测试窗口里渲染一张 3 页的表。 */
    private fun ComposeUiTest.renderTable() {
        setContent {
            SundaysTheme {
                DataTable(
                    columns = columns(),
                    rows = manyRows(),
                    pageSize = PageSize.S100,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.barBounds() =
        onNodeWithTag(TABLE_PAGINATION_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** 底栏高度（dp）。语义边界给的是像素，按测试环境的 density 折算。 */
    private fun ComposeUiTest.barHeightDp(): Float = barBounds().height / density.density

    private fun ComposeUiTest.tableBodyWidthDp(): Float =
        onAllNodesWithTag(TABLE_BODY_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot.width / density.density

    /**
     * 导航**按钮**（而不是按钮里的文字）的边界。
     *
     * 用合并树查询：M3 的 `Button` 自带 `mergeDescendants`，合并节点才是那颗可点击的
     * 按钮；未合并树拿到的是里面的 `Text`，量不到内边距。
     */
    private fun ComposeUiTest.buttonBounds(label: String) =
        onAllNodesWithText(label).fetchSemanticsNodes().single().boundsInRoot

    /**
     * 单行底栏的高度上限。
     *
     * 构成：按钮最小高（M3 约 40dp）+ 上下各 8dp 内边距 ≈ 56dp。留到 **72dp**
     * 是为了不把断言绑死在某个主题的具体控件高度上 —— 只要「标签折成两行」
     * 就一定超过它（三行必然溢出得更明显）。
     */
    private val SINGLE_LINE_LIMIT_DP = 72f

    /**
     * 紧凑档的判定阈值 —— **独立于生产代码写死**。
     *
     * 引用 `DataTable.kt` 里的常量会让这条断言自我循环：阈值一旦被改宽，
     * 测试跟着变宽，两边一起漂移就再也发现不了。生产侧是
     * `PAGINATION_COMPACT_WIDTH`，这里只当作「这个宽度确实属于窄容器」的旁证。
     */
    private val COMPACT_THRESHOLD_DP = 560f

    /**
     * 单颗导航按钮的宽度下限。
     *
     * 三个汉字按 `bodySmall` 约 13dp/字 = 39dp，加 M3 出厂 2×24dp 内边距 = **87dp**。
     * 取 75dp（约自然宽的 86%）留出字体度量余量 —— 回归时实测塌到 48dp。
     */
    private val MIN_NAV_BUTTON_WIDTH_DP = 75f

    @Test
    fun `the pagination bar stays on one line in the default window layout`() =
        runDesktopComposeUiTest(width = 700, height = 400) {
            renderTable()

            // 前置旁证：确认 450dp 左右的底栏真的造出来了（见类注释里「静默失效」那段）。
            // 少了它，主题哪天不再填满窗口也会一路绿灯。
            val bodyWidthDp = tableBodyWidthDp()
            assertTrue(
                bodyWidthDp < COMPACT_THRESHOLD_DP,
                "窄容器没造出来：表体宽 ${bodyWidthDp}dp ≥ $COMPACT_THRESHOLD_DP dp，" +
                    "这条断言会退化成在宽容器上空跑",
            )
            assertTrue(
                bodyWidthDp > 400f,
                "底栏应落在「装得下紧凑档、放不下宽档」的区间，实际表体宽 ${bodyWidthDp}dp",
            )

            val heightDp = barHeightDp()
            assertTrue(
                heightDp <= SINGLE_LINE_LIMIT_DP,
                "默认窗口（700dp）下分页底栏应保持单行，实际高度 ${heightDp}dp —— " +
                    "按钮标签被压成竖排了",
            )
        }

    @Test
    fun `the least used controls are dropped before anything gets squeezed`() =
        runDesktopComposeUiTest(width = 700, height = 400) {
            renderTable()

            // 紧凑档隐藏「首页 / 末页 / 共 N 条」：结果集通常几十页，首末页几乎不用；
            // 总条数在表头上下文里也能推知。牺牲描述、保留操作。
            assertEquals(
                0, onAllNodesWithText("首页").fetchSemanticsNodes().size,
                "窄容器下应隐藏「首页」——宁可少两个低频按钮，也不要把标签压变形",
            )
            assertEquals(
                0, onAllNodesWithText("末页").fetchSemanticsNodes().size,
                "窄容器下应隐藏「末页」",
            )
            assertEquals(
                0, onAllNodesWithText("共 250 条").fetchSemanticsNodes().size,
                "窄容器下应隐藏「共 N 条」",
            )
            // 翻页本身必须完好 —— 不能为了排版把真正在用的功能砍掉
            onNodeWithText("上一页").assertIsDisplayed()
            onNodeWithText("下一页").assertIsDisplayed()
            onNodeWithText("1 / 3").assertIsDisplayed()
        }

    /**
     * 最坏可达布局（窗口最小 + 树面板拖满）下底栏只有约 248dp，**连紧凑档都装不下** ——
     * 左组会被压扁，于是只剩两条真正要守的契约：
     *
     * 1. **翻页按钮绝不能被压成零尺寸**。之前用 `assertIsDisplayed` 量到过
     *    `Rect(0,0,0,0)` —— 按钮还在语义树里，界面上却已经点不到了。
     * 2. **底栏不能被顶高**。这一条同时守住左右两组的「禁止折行」设置。
     *
     * 注意这里的挤压对象是**左组**（每页选择器）：右组只占约 215dp，在这个
     * 248dp 的底栏里仍然放得下 —— 这正是左组挂 `weight(1f, fill = false)` 的意义。
     * 去掉 weight 后 `Row` 会按比例分给两组，实测「下一页」从 87dp 塌到 12dp。
     */
    @Test
    fun `page navigation survives the narrowest reachable layout`() =
        runDesktopComposeUiTest(width = 390, height = 400) {
            renderTable()

            val bar = barBounds()
            val prev = buttonBounds("上一页")
            val next = buttonBounds("下一页")
            val pageNo = onAllNodesWithText("1 / 3", useUnmergedTree = true)
                .fetchSemanticsNodes().single().boundsInRoot

            for ((label, node) in listOf("上一页" to prev, "1 / 3" to pageNo, "下一页" to next)) {
                assertTrue(
                    node.width > 0f && node.height > 0f,
                    "「$label」在最坏布局下被压成零尺寸（$node）—— 按钮等于消失",
                )
                assertTrue(
                    node.left >= bar.left - 1f && node.right <= bar.right + 1f,
                    "「$label」溢出底栏（$node vs 底栏 $bar）",
                )
            }

            // 两个按钮是**同一个** [NavButton]、同样三个字，宽度本该相等。
            // 不等 = 右组被压缩了，且压缩只落在末尾那颗上。
            // 这条不需要任何魔法常数就能抓住回归。
            assertEquals(
                prev.width, next.width, 0.5f,
                "「上一页」与「下一页」宽度应相等——不等说明右组被挤压了（$prev vs $next）",
            )
            // 兜底：单颗按钮的宽度下限。
            assertTrue(
                prev.width >= MIN_NAV_BUTTON_WIDTH_DP,
                "导航按钮被压到 ${prev.width}dp，低于下限 $MIN_NAV_BUTTON_WIDTH_DP dp —— " +
                    "左组的 weight 失效了",
            )

            val heightDp = barHeightDp()
            assertTrue(
                heightDp <= SINGLE_LINE_LIMIT_DP,
                "最坏布局下标签被压成竖排，底栏暴涨到 ${heightDp}dp —— " +
                    "某处标签的 maxLines/softWrap 兜底失效了",
            )
        }

    @Test
    fun `wide containers keep every jump`() = runDesktopComposeUiTest(width = 1024, height = 400) {
        renderTable()

        onNodeWithText("首页").assertIsDisplayed()
        onNodeWithText("上一页").assertIsDisplayed()
        onNodeWithText("下一页").assertIsDisplayed()
        onNodeWithText("末页").assertIsDisplayed()
        onNodeWithText("共 250 条").assertIsDisplayed()

        val bodyWidthDp = tableBodyWidthDp()
        assertTrue(
            bodyWidthDp >= COMPACT_THRESHOLD_DP,
            "宽容器（1024dp 窗口）下主表区应远宽于紧凑阈值，实际 ${bodyWidthDp}dp",
        )
        assertTrue(
            barHeightDp() <= SINGLE_LINE_LIMIT_DP,
            "宽容器下底栏也应单行",
        )
    }
}
