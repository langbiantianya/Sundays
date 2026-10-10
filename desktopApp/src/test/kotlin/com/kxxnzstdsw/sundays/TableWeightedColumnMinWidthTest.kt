package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.TABLE_BODY_TAG
import com.kxxnzstdsw.sundays.table.TABLE_HEADER_TAG
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归：**列一多，整张表塌成不可读的宽度**。
 *
 * ## 症状（真窗口走查发现）
 *
 * PG `192.168.1.5:5432` → `examquestions` 库 → 双击 `biz_user`（**23 个字段**）：
 * 网格里所有列被压成窄竖条，表头只剩第一个列名 `id`（后面 22 个列名被压没了），
 * 数值互相叠着显示成 `2 1 1 8 0 0` 这样的一团。
 * 而点开右侧「详情」面板读数完全正常 —— **数据是对的，只是网格没法看**。
 *
 * ## 根因
 *
 * 浏览屏建列时只给 `TableColumn(key, header)`，`width` 为 null，全部走 `weight` 路径。
 * 而 `weight` 是在**给定内容宽度内**均分的，`DataTable.contentWidthFor` 当时直接取视口宽：
 *
 * ```
 * 视口 ≈ 650dp ÷ 23 列 = 每列 28dp   // 宽不过两个字符
 * ```
 *
 * 修法是给加权列一个**最小宽度**下限，列一多就让内容宽于视口、触发横向滚动。
 *
 * ## 为什么必须新建这一类用例
 *
 * `TableColumnAlignmentTest` 的 12 列**全是 `width = 140.dp` 定宽**，走
 * `Modifier.width(...)` —— 那是显式宽度，**下限根本管不着**，所以那条用例改前改后都绿。
 * 换句话说：现有测试**测不到**浏览屏实际使用的那条路径（全是 weighted 列）。
 * 本类全部用**不指定 width** 的列，与浏览屏一致。
 */
@OptIn(ExperimentalTestApi::class)
class TableWeightedColumnMinWidthTest {

    /**
     * 与 [com.kxxnzstdsw.sundays.DatabaseBrowserScreen] 建列方式一致：
     * 只给 key 与 header，`width` 留空 → 走 `weight` 路径。
     */
    private fun weightedColumns(count: Int): List<TableColumn> =
        (1..count).map { TableColumn(key = "c$it", header = "column_name_$it") }

    private fun rows(count: Int, cols: Int): List<TableRow> = (1..count).map { r ->
        TableRow(id = r, cells = (1..cols).associate { i -> "c$i" to "value-${r}-$i" })
    }

    private fun ComposeUiTest.renderWeightedTable(colCount: Int) {
        setContent {
            SundaysTheme {
                DataTable(
                    columns = weightedColumns(colCount),
                    rows = rows(3, colCount),
                    // 关掉详情面板，把整块视口都留给网格 —— 走查里的默认形态另有 35% 给它
                    showDetailPanel = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    /**
     * 当前可横向滚动的总量。
     *
     * ⚠️ `SemanticsProperties.HorizontalScrollAxisRange.maxValue` 是 **`() -> Float`**
     * 而不是 `Float` —— 漏掉那次调用会得到一个函数对象，比较运算符报的是
     * 「Unresolved reference 'compareTo' on receiver of type '() -> Float'」，
     * 与真正的问题（列被压扁）毫无关系，很容易被当成测试写错而绕开。
     */
    private fun ComposeUiTest.hScrollMax(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            ?.maxValue
            ?.invoke()
            ?: error("节点 $tag 没有横向滚动轴")

    /**
     * 主判据：23 个加权列**必须能横向滚动**。
     *
     * 改前 `maxValue` 恒为 0（内容宽度 == 视口宽，没有任何东西溢出），
     * 于是每列被均分成 28dp。
     */
    @Test
    fun `多列时网格能横向滚动而不是把列压扁`() = runComposeUiTest {
        renderWeightedTable(colCount = 23)

        val body = hScrollMax(TABLE_BODY_TAG)
        val header = hScrollMax(TABLE_HEADER_TAG)

        assertTrue(
            body > 0f,
            "⚠️ 23 列的表没有横向可滚量（maxValue=$body）—— 说明每列被均分到约 28dp，" +
                "整张表会塌成竖条、列名互相盖住。列一多就该横滚。",
        )
        assertTrue(
            header > 0f,
            "表头同样必须能横滚（maxValue=$header），否则表名与数据列会错位",
        )
    }

    /**
     * 反向护栏：**列不多时不该凭空多出一条横向滚动条**。
     *
     * 最小宽度的代价是「列多时内容宽于视口」，反过来「列少时必须仍然等于视口」——
     * 否则所有表都被塞进一条永远用不上的滚动条里，比原来的问题更烦。
     *
     * 4 列 × 100dp + 内边距 ≈ 429dp，远小于测试视口（默认 1024dp）。
     */
    @Test
    fun `少列时不产生多余的横向滚动`() = runComposeUiTest {
        renderWeightedTable(colCount = 4)

        assertTrue(
            hScrollMax(TABLE_BODY_TAG) == 0f,
            "4 列时不该有横向可滚量 —— 下限把内容撑宽过头了",
        )
    }

    /**
     * **逐列对齐** —— 表头第 i 列与表体第 i 个单元格必须在**同一个横坐标**。
     *
     * ## 为什么这条断言放在这个类里，而不是 `TableColumnAlignmentTest`
     *
     * 那边的 `weight body columns line up with the weight header columns` 量的是**同一件事**，
     * 但它用 **5 列**：`contentWidth = maxOf(1024 视口, 5×100+28) = 1024`，**恰好不溢出**。
     * 不溢出时 `Modifier.width(1024)` 不会被钳（见下），表头表体本来就是对齐的 ——
     * 于是那条用例在缺陷版本上**照样绿**。换句话说：它量的场景与缺陷发生的场景不重合。
     *
     * 本类用 **23 列**：`contentWidth = 2346`，**溢出视口 1322** —— 正是缺陷现场。
     *
     * ## 缺陷机制（已确证，见 `DataTable.TableHeader` 的注释）
     *
     * `Modifier.width` 是**按传入约束钳位**的（`coerceIn(minWidth, maxWidth)`），不是「设绝对宽度」。
     * 表头写 `.width(contentWidth).horizontalScroll(...)` 时，它拿到的 `maxWidth` 是父 `Column`
     * 给的**视口宽**，于是 2346dp 被**钳回 1024dp**，`weight` 在 1024 里分 23 列（每列 44.5dp）；
     * 而表体的行在滚动层**内侧**量到的是无界约束，2346dp 生效（每列 102dp）。
     *
     * 实测（修复前）表头列 x = 20 / 63 / 106 / 149，表体 x = 20 / 121 / 222 / 323 —— 完全两套。
     * 表头每列扣掉内边距只剩 28dp 文本区，`Ellipsis` 之下 22 个长列名被压没，
     * 只有 `id` 这种短名字还认得出 —— 即 §9.24「表头只剩第一个列名」。
     */
    @Test
    fun `表头每一列都与表体对应列对齐`() = runComposeUiTest {
        renderWeightedTable(colCount = 23)

        // 取第 1、2、5、8 列：跨过首列，且分散在视口内外。
        // 选这几个不是随手 —— 缺陷版本里第 2 列就已经差了近 60px，
        // 用「第 8 列」能顺带证明**错位是累积的**而不是恒定偏移。
        val probes = listOf(1, 2, 5, 8)
        probes.forEach { i ->
            val header = onAllNodesWithText("column_name_$i").fetchSemanticsNodes()
                .first { it.boundsInRoot.width > 0 }
            // ⚠️ 必须 `useUnmergedTree = true`：只读行的 `clickable` 把整行合并成一个语义节点，
            // 合并树上按文本查到的是「整行」（x=0、宽 1024），不是某个单元格。
            val body = onAllNodesWithText("value-1-$i", useUnmergedTree = true).fetchSemanticsNodes()
                .first { it.boundsInRoot.width > 0 }

            assertEquals(
                body.boundsInRoot.left, header.boundsInRoot.left, 1f,
                "第 $i 列错位：表头在 x=${header.boundsInRoot.left}、表体在 x=${body.boundsInRoot.left}。" +
                    "按列名读数会读到隔壁那一列，对数据库工具来说这是读错数据。",
            )
        }
    }

    /**
     * **表头列宽必须与表体同基准** —— 表头文本宽度不该被视口宽度摊薄。
     *
     * 缺陷版本里表头每列 44.5dp，扣掉左右各 8dp 内边距后文本区仅 28dp，
     * 13 个字符的 `column_name_1` 被 `Ellipsis` 压成看不出的一小截。
     *
     * 这里用「表头列起点间距」而不是「文本宽度」当判据：文本宽度受字体影响、
     * 换主题就会变；而**列起点间距**是布局量，只在基准宽度错掉时才变 ——
     * 判据必须**对要抓的缺陷敏感、对无关变化不敏感**。
     */
    @Test
    fun `表头列宽不被视口宽度摊薄`() = runComposeUiTest {
        renderWeightedTable(colCount = 23)

        val leftOf = { i: Int ->
            onAllNodesWithText("column_name_$i").fetchSemanticsNodes()
                .first { it.boundsInRoot.width > 0 }.boundsInRoot.left
        }
        val spacing = leftOf(2) - leftOf(1)
        val viewport = onNodeWithTag(TABLE_BODY_TAG).fetchSemanticsNode().boundsInRoot.width

        assertTrue(
            spacing > viewport / 23,
            "表头列起点间距只有 $spacing，而视口摊到 23 列是 ${viewport / 23} —— " +
                "说明表头仍按**视口宽**分列，而表体按 contentWidth 分列，两者错位。",
        )
    }

    /**
     * ⚠️ 这里**不再**留「表头列名可见」的弱断言。
     *
     * §9.24 试过两条，都在缺陷版本上绿：
     * 1. 「列名的语义节点都在」—— 列宽塌成 0 时 `Text` 仍在语义树里，查得到
     * 2. 「列名的语义宽度 > 0」—— 缺陷版本里是 44.5dp，当然 > 0
     *
     * 两条都是「量了一个对现象不敏感的量」。**本类上面两条量的是横坐标**，
     * 它会随基准宽度错掉而变化 —— 写断言前先问「这个量真的会因为我要抓的缺陷而变化吗」。
     */
}
