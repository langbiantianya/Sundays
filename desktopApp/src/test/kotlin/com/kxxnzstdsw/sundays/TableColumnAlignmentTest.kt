package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 表格的**列对齐契约** —— 表头与表体必须共用同一个横向滚动状态。
 *
 * 回归背景：修复前 `DataTable` 的表头自己 `rememberScrollState()` 并加 `horizontalScroll`，
 * 而表体的 `LazyColumn` **完全没有**横向滚动。列固定宽度之和超出可视区时：表头能滚、
 * 表体不能滚，两者错位 —— 用户按列名读数会读到隔壁那一列。对数据库工具来说这是读错数据。
 *
 * 断言直接读语义的 `HorizontalScrollAxisRange`（`value` = 当前偏移，`maxValue` = 可滚总量），
 * 而**不**去量渲染几何：视口外的子节点语义边界会被裁剪钳成 0，量出来的差值是假象。
 */
@OptIn(ExperimentalTestApi::class)
class TableColumnAlignmentTest {

    /** 12 列 × 140dp = 1680dp，远超测试视口宽度，强制触发横向溢出。 */
    private fun wideColumns(): List<TableColumn> = (1..12).map {
        TableColumn(key = "c$it", header = "col$it", width = 140.dp)
    }

    private fun rows(): List<TableRow> = (1..3).map { r ->
        TableRow(id = r, cells = (1..12).associate { i -> "c$i" to "r${r}v$i" })
    }

    private fun ComposeUiTest.renderWideTable() {
        setContent {
            SundaysTheme {
                DataTable(
                    columns = wideColumns(),
                    rows = rows(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.hScroll(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            ?: error("节点 $tag 没有横向滚动轴")

    @Test
    fun `the body is horizontally scrollable at all`() = runComposeUiTest {
        renderWideTable()
        // 修复前表体连 `horizontalScroll` 都没有，这个轴范围压根不存在
        val body = hScroll(TABLE_BODY_TAG)
        assertTrue(
            body.maxValue() > 0f,
            "表体应可横向滚动（maxValue=${body.maxValue()}）—— 修复前它没有横向滚动，超出部分被裁且滚不到",
        )
    }

    @Test
    fun `header and body share one horizontal scroll offset`() = runComposeUiTest {
        renderWideTable()
        // `ScrollAxisRange.value` 是 `() -> Float`（延迟求值），要调用它取当前偏移
        val headerBefore = hScroll(TABLE_HEADER_TAG).value()
        val bodyBefore = hScroll(TABLE_BODY_TAG).value()
        assertEquals(headerBefore, bodyBefore, 0.01f, "初始偏移应一致")

        // 横向滚动：必须用**滚轮**，不能用鼠标拖拽 —— `Modifier.horizontalScroll` 默认
        // 只响应滚动事件（触控板 / Shift+滚轮），不绑拖拽手势。
        onNodeWithTag(TABLE_HEADER_TAG).performMouseInput {
            moveTo(Offset(center.x, center.y))
            scroll(120f, ScrollWheel.Horizontal)
        }
        waitForIdle()

        val headerAfter = hScroll(TABLE_HEADER_TAG).value()
        val bodyAfter = hScroll(TABLE_BODY_TAG).value()

        assertTrue(
            headerAfter > headerBefore,
            "表头应已横向滚动（$headerBefore → $headerAfter）",
        )
        // 关键断言：表体跟着滚了同样的量 —— 否则列名与数据列会错位，用户按列名读会读错列
        assertEquals(
            headerAfter, bodyAfter, 0.01f,
            "表头与表体必须共用同一个 ScrollState，否则列名与数据列错位",
        )
    }

    // ========================================================================
    // weight 列 —— 本组三条在修 bug 之前**全都是绿的**
    // ========================================================================

    /**
     * 复现背景：本文件原先只用**定宽列**（`width = 140.dp`）。定宽走 `Modifier.width()`，
     * 那在 `horizontalScroll` 的无界约束里依然成立，所以一直测不出问题。
     *
     * 而 `DatabaseBrowserScreen` 建列时只给 key 与 header（`TableColumn(key, header)`），
     * 全部走 **`weight` 路径** —— 而 `weight` 在无界宽度下拿到的是 **0**。
     * 结果：数据行整行收缩成一个字符宽（实测 1024px 视口下塌成 **28px**），
     * 5 个 `weight` 列全挤在 x=0 互相盖住，**表预览里除第一列外全是空白**。
     *
     * 也就是说：只要连上真数据库点开任意一张表，这个缺陷立刻可见；
     * 而全部既有测试都因为只测定宽列而放过了它。
     */
    private fun weightColumns(): List<TableColumn> =
        listOf("id", "customer_id", "amount", "status", "placed_at")
            .map { TableColumn(key = it, header = it) }

    private fun ComposeUiTest.renderWeightTable() {
        setContent {
            SundaysTheme {
                DataTable(
                    columns = weightColumns(),
                    rows = listOf(
                        TableRow(
                            id = 1L,
                            cells = mapOf(
                                "id" to "1", "customer_id" to "2", "amount" to "123.45",
                                "status" to "PENDING", "placed_at" to "2024-02-02 01:01:00",
                            ),
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                    showDetailPanel = false,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun `weight columns make the data row span the viewport instead of collapsing`() =
        runComposeUiTest {
            renderWeightTable()
            val viewportWidth = onNodeWithTag(TABLE_BODY_TAG).fetchSemanticsNode()
                .boundsInRoot.width
            // 用**合并树**：数据行挂了 `clickable`，行内所有单元格会合并成一个语义节点，
            // 它的边界就是整行的边界（未合并的树里只能量到文本自身，那是另一回事）。
            val rowWidth = onNodeWithText("PENDING").fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                rowWidth > viewportWidth * 0.8f,
                "数据行应铺满视口（视口 $viewportWidth，实测行宽 $rowWidth）—— " +
                    "塌成几十像素就意味着几列叠在一起，表预览只有第一列看得见",
            )
        }

    @Test
    fun `weight columns in the body are spread apart and not stacked`() = runComposeUiTest {
        renderWeightTable()
        // 逐格取边界：五个值必须**落在五个不同的横向位置**。
        // 缺陷未修时它们的 left 全都等于行起点（x=0），也就是互相压在一起。
        val values = listOf("1", "2", "123.45", "PENDING", "2024-02-02 01:01:00")
        val lefts = values.map { value ->
            val node = onAllNodesWithText(value, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.width > 0 }
            assertNotNull(node, "表预览里找不到单元格 $value —— 它的宽度塌成了 0，根本没画出来")
            node!!.boundsInRoot.left
        }
        assertEquals(
            values.size, lefts.distinct().size,
            "五个单元格应落在五个不同的横坐标上，实测 left = $lefts（都相同就是叠在一起了）",
        )
    }

    @Test
    fun `weight body columns line up with the weight header columns`() = runComposeUiTest {
        renderWeightTable()
        // 表头与表体的列起点必须一致 —— 否则「按列名读数」会读到隔壁那一列。
        val headerLefts = weightColumns().map {
            onNodeWithText(it.key, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        }
        val bodyLefts = listOf("1", "2", "123.45", "PENDING", "2024-02-02 01:01:00").map {
            onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes()
                .first { n -> n.boundsInRoot.left > 0 && n.boundsInRoot.top > 30f }
                .boundsInRoot.left
        }
        headerLefts.forEachIndexed { i, hl ->
            assertEquals(
                hl, bodyLefts[i], 2f,
                "第 ${i + 1} 列（${weightColumns()[i].key}）表头在 $hl、表体在 ${bodyLefts[i]}，错位了",
            )
        }
    }
}
