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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据源树与结果表的**纯规则**测试。
 *
 * 这一层全是纯数据变换（给定输入必然给出同一个输出），
 * 所以能在任何平台上跑 —— 不需要引擎，也不需要数据库。
 */
class AnalysisTreeTest {
    // ──────────────── 节点 id ────────────────

    @Test
    fun `namespace and table ids are distinct per owner`() {
        // 两个源里**同名**的表必须有两个不同的 id ——
        // 否则树上第二棵树节点会顶掉第一棵，用户点到的表与看到的不一致。
        val a = analysisTableNodeId("c1", "shop", "orders")
        val b = analysisTableNodeId("c2", "shop", "orders")
        assertTrue(a != b, "不同源的同名表必须有不同的 id：$a vs $b")
    }

    @Test
    fun `namespace and table ids differ in kind`() {
        // 命名空间节点与表节点的 id 不能撞 ——
        // 撞了的话「按 id 替换节点」会替换掉错的那个。
        val namespace = analysisNamespaceNodeId("c1", "shop")
        val table = analysisTableNodeId("c1", "shop", "orders")
        assertTrue(namespace != table, "命名空间与表的 id 必须不同：$namespace vs $table")
    }

    @Test
    fun `the workspace table id is built from the workspace owner`() {
        assertEquals(
            "table:workspace:main:orders",
            analysisTableNodeId(ANALYSIS_WORKSPACE_NODE_ID, WORKSPACE_SCHEMA, "orders"),
        )
    }

    // ──────────────── 可展开性 ────────────────

    @Test
    fun `only tables are leaves`() {
        assertTrue(node(AnalysisTreeKind.WORKSPACE).expandable(), "工作区是分组，可展开")
        assertTrue(node(AnalysisTreeKind.SOURCE).expandable(), "远端源是分组，可展开")
        assertTrue(node(AnalysisTreeKind.NAMESPACE).expandable(), "命名空间是分组，可展开")
        assertFalse(node(AnalysisTreeKind.TABLE).expandable(), "表是叶子，不可展开")
    }

    // ──────────────── 按 id 找 / 按 id 替换 ────────────────

    @Test
    fun `a nested node can be found by its id`() {
        val tree = listOf(source("c1", listOf(namespace("c1", "shop", listOf(table("c1", "shop", "orders"))))))

        val found = assertNotNull(findAnalysisNode(tree, "table:c1:shop:orders"), "深处的表必须能被找到")
        assertEquals("orders", found.label)
    }

    @Test
    fun `an unknown id is simply not found`() {
        val tree = listOf(source("c1"))
        assertNull(findAnalysisNode(tree, "nope"), "找不到不是错误，是正常状态")
    }

    @Test
    fun `replacing a node keeps the rest of the tree intact`() {
        val tree =
            listOf(
                source("c1", listOf(namespace("c1", "shop"))),
                source("c2"),
            )

        val replaced =
            replaceAnalysisNode(tree, "ns:c1:shop") {
                it.copy(children = listOf(table("c1", "shop", "orders")), loaded = true)
            }

        // 换掉的那一处换了
        val shop = assertNotNull(findAnalysisNode(replaced, "ns:c1:shop"))
        assertEquals(1, shop.children.size)
        assertTrue(shop.loaded, "换过的节点应当标记成已加载")
        // 其余的**一点没动**
        assertEquals(2, replaced.size, "根节点的数量不能变")
        assertEquals(emptyList(), replaced[1].children, "没点名的分支不能被动过")
        assertEquals("c2", replaced[1].id)
    }

    @Test
    fun `replacing an unknown id leaves the tree unchanged`() {
        val tree = listOf(source("c1"))
        val replaced = replaceAnalysisNode(tree, "nope") { it.copy(label = "改过了") }
        assertEquals(tree, replaced, "找不到的 id 不能凭空造出节点")
    }

    @Test
    fun `replacing a root node works`() {
        val tree = listOf(source("c1"), source("c2"))
        val replaced = replaceAnalysisNode(tree, "c2") { it.copy(label = "新的名字") }
        assertEquals("新的名字", replaced[1].label)
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

    // ──────────────── 造节点的小工具 ────────────────

    private fun node(kind: AnalysisTreeKind) = AnalysisTreeNode(id = "x", label = "x", kind = kind)

    private fun source(
        id: String,
        children: List<AnalysisTreeNode> = emptyList(),
    ) = AnalysisTreeNode(
        id = id,
        label = id,
        kind = AnalysisTreeKind.SOURCE,
        children = children,
        loaded = children.isNotEmpty(),
    )

    private fun namespace(
        owner: String,
        name: String,
        children: List<AnalysisTreeNode> = emptyList(),
    ) = AnalysisTreeNode(
        id = analysisNamespaceNodeId(owner, name),
        label = name,
        kind = AnalysisTreeKind.NAMESPACE,
        sourceId = owner,
        namespace = name,
        children = children,
        loaded = children.isNotEmpty(),
    )

    private fun table(
        owner: String,
        namespace: String,
        name: String,
    ) = AnalysisTreeNode(
        id = analysisTableNodeId(owner, namespace, name),
        label = name,
        kind = AnalysisTreeKind.TABLE,
        sourceId = owner,
        namespace = namespace,
        table = name,
    )
}
