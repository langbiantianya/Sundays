package com.kxxnzstdsw.sundays.table

import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * [CellEdit] 的**构造期不变量**。
 *
 * 为什么在 `init` 里拦而不是只在 UI 层判：`CellEdit` 是 [DataTable.onCellEdit] 的公开入参，
 * 将来还会有别的调用方（批量编辑、粘贴）。把「新旧值相同」这种**语义上无意义**的编辑
 * 挡在构造期，是唯一能保证「所有调用方都躲不掉」的位置 —— 组件层的判断可以被绕过，
 * 构造函数不能。
 */
class CellEditTest {

    @Test
    fun `an edit carries the column key so the caller knows which column to change`() {
        val e = CellEdit(rowId = 7, columnKey = "email", oldValue = "a@x", newValue = "b@x")
        // 数据类的主键就是这些字段本身，不需要额外断言；这条钉住「字段名不许改」——
        // 调用方（`DatabaseBrowserState.updateCell`）按名字取它们。
        assertCellEditFields(e, rowId = 7, columnKey = "email", oldValue = "a@x", newValue = "b@x")
    }

    @Test
    fun `a blank column key is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            CellEdit(rowId = 1, columnKey = "  ", oldValue = "a", newValue = "b")
        }
        assertTrue(e.message!!.contains("columnKey"), "错误信息应点明是 columnKey 的问题：${e.message}")
    }

    /**
     * 新旧值相同的编辑**无意义**：发出去就是一条 `UPDATE SET x = x`，
     * 把表的修改时间白白弄脏 —— 用户什么都没做，数据库却认为被改过。
     */
    @Test
    fun `an edit that changes nothing is rejected at construction time`() {
        val e = assertFailsWith<IllegalArgumentException> {
            CellEdit(rowId = 1, columnKey = "name", oldValue = "Alice", newValue = "Alice")
        }
        assertTrue(e.message!!.contains("相同"), "错误信息应点明是「没变」：${e.message}")
    }

    private fun assertTrue(b: Boolean, msg: String) = kotlin.test.assertTrue(b, msg)

    private fun assertCellEditFields(e: CellEdit, rowId: Any, columnKey: String, oldValue: String, newValue: String) {
        kotlin.test.assertEquals(rowId, e.rowId)
        kotlin.test.assertEquals(columnKey, e.columnKey)
        kotlin.test.assertEquals(oldValue, e.oldValue)
        kotlin.test.assertEquals(newValue, e.newValue)
    }
}
