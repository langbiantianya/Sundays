package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
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
}
