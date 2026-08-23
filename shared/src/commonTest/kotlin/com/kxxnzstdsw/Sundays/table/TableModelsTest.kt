package com.kxxnzstdsw.Sundays.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 表格数据模型 + 分页逻辑测试。
 *
 * ## 覆盖范围
 * - [TableColumn]：定义与 formatter
 * - [TableRow]：构造器（map / vararg pairs）、[formatted] 应用 formatter
 * - [PageSize]：枚举值 / [isAll] / [fromInt] / [ALL_VALUES]
 * - [ContextMenuState]：[show] / [dismiss] 状态转换
 */
class TableModelsTest {

    @Test
    fun table_row_vararg_constructor_builds_cells_map() {
        val row = TableRow(
            id = 1L,
            "id" to 1L,
            "name" to "Alice",
            "age" to 30,
        )
        assertEquals(1L, row.id)
        assertEquals(3, row.cells.size)
        assertEquals(1L, row.cells["id"])
        assertEquals("Alice", row.cells["name"])
        assertEquals(30, row.cells["age"])
    }

    @Test
    fun table_row_map_constructor_keeps_cells() {
        val cells = mapOf<String, Any?>("id" to 7, "name" to "Bob")
        val row = TableRow(id = 7, cells = cells)
        assertEquals(7, row.id)
        assertSame(cells, row.cells)
    }

    @Test
    fun table_row_formatted_applies_formatter() {
        val row = TableRow(id = 1L, "age" to 30, "active" to true)
        val ageCol = TableColumn(key = "age", header = "年龄", formatter = { (it as? Int)?.toString() ?: "—" })
        val activeCol = TableColumn(
            key = "active",
            header = "状态",
            formatter = { if (it == true) "✓" else "✗" },
        )
        assertEquals("30", row.formatted(ageCol))
        assertEquals("✓", row.formatted(activeCol))
    }

    @Test
    fun table_row_formatted_uses_toString_when_no_formatter() {
        val row = TableRow(id = 1L, "name" to "Charlie")
        val col = TableColumn(key = "name", header = "姓名")
        assertEquals("Charlie", row.formatted(col))
    }

    @Test
    fun table_row_formatted_returns_empty_for_null_value_without_formatter() {
        val row = TableRow(id = 1L, "name" to null)
        val col = TableColumn(key = "name", header = "姓名")
        assertEquals("", row.formatted(col))
    }

    @Test
    fun page_size_enum_has_expected_values() {
        // 验证 8 个枚举值：10/20/50/100/200/300/500/ALL
        assertEquals(8, PageSize.entries.size)
        assertEquals(10, PageSize.S10.value)
        assertEquals(20, PageSize.S20.value)
        assertEquals(50, PageSize.S50.value)
        assertEquals(100, PageSize.S100.value)
        assertEquals(200, PageSize.S200.value)
        assertEquals(300, PageSize.S300.value)
        assertEquals(500, PageSize.S500.value)
        assertEquals(0, PageSize.ALL.value)
    }

    @Test
    fun page_size_is_all_only_true_for_all_variant() {
        assertTrue(PageSize.ALL.isAll)
        assertEquals(false, PageSize.S10.isAll)
        assertEquals(false, PageSize.S500.isAll)
    }

    @Test
    fun page_size_labels_match_expected_strings() {
        assertEquals("10", PageSize.S10.label)
        assertEquals("20", PageSize.S20.label)
        assertEquals("全部", PageSize.ALL.label)
    }

    @Test
    fun page_size_from_int_resolves_known_values() {
        assertSame(PageSize.S10, PageSize.fromInt(10))
        assertSame(PageSize.S500, PageSize.fromInt(500))
        assertSame(PageSize.ALL, PageSize.fromInt(0))
    }

    @Test
    fun page_size_from_int_falls_back_to_default_for_unknown() {
        assertSame(PageSize.DEFAULT, PageSize.fromInt(42))
        assertSame(PageSize.DEFAULT, PageSize.fromInt(-1))
    }

    @Test
    fun page_size_default_is_s20() {
        assertSame(PageSize.S20, PageSize.DEFAULT)
    }

    @Test
    fun page_size_all_values_lists_in_enum_order() {
        val all = PageSize.ALL_VALUES
        assertEquals(8, all.size)
        assertEquals(PageSize.S10, all.first())
        assertEquals(PageSize.ALL, all.last())
        // 升序
        for (i in 0 until all.size - 1) {
            assertTrue(all[i].value < all[i + 1].value || all[i + 1].value == 0)
        }
    }

    @Test
    fun context_menu_state_initial_state_is_hidden() {
        val state = ContextMenuState()
        assertEquals(false, state.visible)
        assertNull(state.targetRow)
    }

    @Test
    fun context_menu_state_show_sets_position_and_target() {
        val state = ContextMenuState()
        val row = TableRow(id = 1L, "id" to 1L)
        val pos = androidx.compose.ui.geometry.Offset(100f, 200f)
        state.show(pos, row)
        assertEquals(true, state.visible)
        assertSame(row, state.targetRow)
        assertEquals(pos, state.position)
    }

    @Test
    fun context_menu_state_show_supports_null_row() {
        val state = ContextMenuState()
        state.show(androidx.compose.ui.geometry.Offset(50f, 50f), null)
        assertEquals(true, state.visible)
        assertNull(state.targetRow)
    }

    @Test
    fun context_menu_state_dismiss_resets_state() {
        val state = ContextMenuState()
        state.show(androidx.compose.ui.geometry.Offset(10f, 10f), TableRow(id = 1L, "id" to 1L))
        state.dismiss()
        assertEquals(false, state.visible)
        assertNull(state.targetRow)
    }

    @Test
    fun table_column_default_values() {
        val col = TableColumn(key = "id", header = "ID")
        assertEquals(null, col.width)
        assertEquals(1f, col.weight)
    }

    @Test
    fun table_column_with_custom_formatter() {
        val col = TableColumn(
            key = "status",
            header = "状态",
            formatter = { v -> if (v as? Boolean == true) "ON" else "OFF" },
        )
        assertEquals("ON", col.formatter(true))
        assertEquals("OFF", col.formatter(false))
        assertEquals("OFF", col.formatter(null))
    }
}