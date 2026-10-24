/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分析模式的**纯规则**测试 —— 节点 id 的规则，以及结果 → 表格的换算。
 *
 * ## 树模型本身在**共享**的包里
 *
 * 分析模式与浏览模式共用一个树组件（`com.langbiantianya.sundays.tree`），
 * 模型的通用规则（谁能展开、按 id 找、按 id 替换）在那边的
 * `ObjectTreeModelTest` 里。这里的只留**分析模式特有**的那几条：
 * 节点 id 怎么拼、结果怎么变成表格。
 */
class AnalysisTreeTest {
    // ──────────────── 节点 id ────────────────

    @Test
    fun `table ids differ per namespace`() {
        // 两个命名空间里**同名**的表必须有两个不同的 id ——
        // 否则树上第二张表节点会顶掉第一张，用户点到的表与看到的不一致。
        val a = analysisTableNodeId("shop", "orders")
        val b = analysisTableNodeId("warehouse", "orders")
        assertTrue(a != b, "不同命名空间的同名表必须有不同的 id：$a vs $b")
    }

    @Test
    fun `table and column ids never collide`() {
        // 撞了的话「按 id 替换节点」会替换掉错的那个。
        val table = analysisTableNodeId("main", "orders")
        val column = analysisColumnNodeId("main", "orders", "orders")
        assertTrue(table != column, "表节点与字段节点的 id 必须不同：$table vs $column")
    }

    @Test
    fun `the workspace table id is built from the workspace schema`() {
        assertEquals("table:main:orders", analysisTableNodeId(WORKSPACE_SCHEMA, "orders"))
    }

    @Test
    fun `the column id carries the schema and the table it belongs to`() {
        // 而**同名表**在不同 schema 里也不该撞（每个数据源一个 schema）。
        assertEquals("column:main:orders:id", analysisColumnNodeId("main", "orders", "id"))
        assertEquals("column:main:items:id", analysisColumnNodeId("main", "items", "id"))
        assertEquals("column:crm:orders:id", analysisColumnNodeId("crm", "orders", "id"))
    }

    @Test
    fun `a source node id is built from the schema it occupies`() {
        // 数据源在工作区里占**一个 schema**，而那个 schema 就以它命名 ——
        // 所以 id 与 schema 名是对应的（`schema:订单文件`）。
        assertEquals("schema:订单文件", analysisSchemaNodeId("订单文件"))
        assertTrue(
            analysisSchemaNodeId("订单文件") != analysisTableNodeId("main", "订单文件"),
            "数据源节点与表节点的 id 不能撞",
        )
    }

    // ──────────────── 结果 → 表格 ────────────────

    @Test
    fun `the result columns are keyed by their index not by their name`() {
        // `SELECT id, id FROM t` 是合法的 —— 两列同名。
        // 若用列名做键，后一列会把前一列**覆盖掉**，表里就只剩一列。
        val result =
            AnalysisResult(
                columns = listOf(AnalysisColumn("id", "INTEGER"), AnalysisColumn("id", "INTEGER")),
                rows = listOf(listOf("1", "2")),
            )

        val columns = analysisTableColumns(result)
        assertEquals(listOf("c0", "c1"), columns.map { it.key }, "键必须按下标")
        assertEquals(listOf("id", "id"), columns.map { it.header }, "表头仍然用列名")
    }

    @Test
    fun `the result rows keep every cell of a duplicated column`() {
        val result =
            AnalysisResult(
                columns = listOf(AnalysisColumn("id", "INTEGER"), AnalysisColumn("id", "INTEGER")),
                rows = listOf(listOf("1", "2"), listOf("3", "4")),
            )

        val rows = analysisTableRows(result)
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), rows[0].cells.values.toList(), "两列的值都要在")
        assertEquals(listOf("3", "4"), rows[1].cells.values.toList())
    }

    @Test
    fun `the columns and the rows agree on the keys`() {
        // 两边各用一套键 → 表头在、**格子全空**。
        //
        // 这条不变量单独钉住：列用列名、行用下标（或反过来）时，
        // 表还是能渲染出来，只是每一格都没内容 —— 看上去像「查出来全是 NULL」。
        val result =
            AnalysisResult(
                columns = listOf(AnalysisColumn("id", "INTEGER"), AnalysisColumn("id", "INTEGER")),
                rows = listOf(listOf("1", "2")),
            )

        val columnKeys = analysisTableColumns(result).map { it.key }
        val rowKeys = analysisTableRows(result)[0].cells.keys.toList()
        assertEquals(columnKeys, rowKeys, "列与行必须用同一套键，否则格子全是空的")
    }

    @Test
    fun `a null cell stays null instead of becoming an empty string`() {
        val result =
            AnalysisResult(
                columns = listOf(AnalysisColumn("a", "VARCHAR")),
                rows = listOf(listOf(null)),
            )

        val rows = analysisTableRows(result)
        assertTrue(rows[0].cells.containsKey("c0"))
        assertNull(rows[0].cells["c0"], "NULL 必须还是 NULL")
    }

    @Test
    fun `an empty result produces no columns and no rows`() {
        val result = AnalysisResult()
        assertEquals(emptyList(), analysisTableColumns(result))
        assertEquals(emptyList(), analysisTableRows(result))
    }

    @Test
    fun `the column key is stable across rows`() {
        assertEquals("c0", analysisColumnKey(0))
        assertEquals("c1", analysisColumnKey(1))
        assertTrue(analysisColumnKey(0) != analysisColumnKey(1))
    }
}
