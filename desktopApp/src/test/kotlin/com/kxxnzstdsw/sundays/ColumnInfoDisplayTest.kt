package com.kxxnzstdsw.sundays

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 树节点展开后的**字段行**展示形态。
 *
 * ## 为什么 `typeWithSize` 要单独测
 *
 * proto 的 `ColumnDef.size` 是 `int32`，**未指定时是 0**。而绝大多数列的类型
 * 是带长度的（`VARCHAR(64)` / `DECIMAL(10,2)`），但也有不带��（`TEXT` / `JSONB` /
 * `DATETIME`）—— 后者的 size 同样是 0。
 *
 * 所以「无条件加括号」会把 `TEXT` 显示成 `TEXT(0)`，那是个不存在的类型，
 * 用户看了会以为这张表有问题。
 */
class ColumnInfoDisplayTest {

    private fun col(
        name: String = "col",
        type: String = "VARCHAR",
        size: Int = 0,
        nullable: Boolean = true,
        pk: Boolean = false,
        ai: Boolean = false,
    ) = DatabaseBrowserState.ColumnInfo(name, type, size, nullable, pk, ai)

    @Test
    fun `有长度时把长度拼进类型`() {
        assertEquals("VARCHAR(64)", col(type = "VARCHAR", size = 64).typeWithSize)
        assertEquals("DECIMAL(10)", col(type = "DECIMAL", size = 10).typeWithSize)
    }

    @Test
    fun `长度为 0 时不追加括号`() {
        // ⚠️ 这条是钉「不追加」而非「追加空括号」的：
        // `TEXT(0)` 会被读成「这个字段的类型叫 TEXT(0)」，而它其实是不带长度的 TEXT
        assertEquals("TEXT", col(type = "TEXT", size = 0).typeWithSize)
        assertEquals("JSONB", col(type = "JSONB", size = 0).typeWithSize)
        assertEquals(
            "VARCHAR", col(type = "VARCHAR", size = 0).typeWithSize,
            "proto 的 size 未指定就是 0 —— 同样是 0，绝不能显示成 VARCHAR(0)",
        )
    }

    @Test
    fun `字段名与类型各自独立保留`() {
        val c = col(name = "created_at", type = "DATETIME", size = 0, pk = false, ai = false)
        assertEquals("created_at", c.name)
        assertEquals("DATETIME", c.typeWithSize)
    }

    @Test
    fun `主键与自增标记独立于类型文本`() {
        val c = col(name = "id", type = "BIGINT", size = 0, pk = true, ai = true)
        // 约束**不混进类型串** —— `BIGINT PK` 读起来像「这是一种新类型」，
        // 而 PK / AI / 非空 是约束，与「这是什么」是两件事
        assertEquals("BIGINT", c.typeWithSize)
        assertTrue(c.primaryKey)
        assertTrue(c.autoIncrement)
    }

    @Test
    fun `默认可空`() {
        // 引擎侧 mapper 的约定：proto 没给 nullable 就是「可空」，
        // UI 必须跟着走 —— 反了会让绝大多数列凭空多一个「非空」标记
        assertTrue(col().nullable)
        assertFalse(col(nullable = false).nullable)
    }
}
