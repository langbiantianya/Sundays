package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.TABLE_BODY_TAG
import com.kxxnzstdsw.sundays.table.TABLE_HEADER_TAG
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
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
}
