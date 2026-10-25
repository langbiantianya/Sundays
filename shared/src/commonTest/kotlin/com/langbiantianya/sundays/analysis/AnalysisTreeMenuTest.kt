/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 分析模式树的**右键菜单判据** —— 哪些节点给「删除数据源」，删的是哪一个。
 *
 * ## 为什么这条判据要有自己的用例
 *
 * 它回答的是「这一下要不要弹菜单、弹出来的菜单删谁」。
 * 判据错了一点的后果很具体：用户右键 `orders`（它属于 `crm`），
 * 菜单却把 `warehouse` 删了 —— **不可撤销**。
 *
 * 所以这里逐条钉住：工作区自己的表不给、没登记的 schema 不给、
 * 登记过的源要给且要给对名字。
 */
class AnalysisTreeMenuTest {
    /** 登记在册的两个数据源。 */
    private val registered = setOf("crm", "warehouse")

    private fun tableNode(
        schema: String,
        name: String = "orders",
    ) = ObjectTreeNode(
        id = analysisTableNodeId(schema, name),
        label = name,
        kind = ObjectTreeKind.TABLE,
        parentLabel = schema,
        namespace = schema,
    )

    private fun columnNode(
        schema: String,
        table: String = "orders",
        column: String = "id",
    ) = ObjectTreeNode(
        id = analysisColumnNodeId(schema, table, column),
        label = column,
        kind = ObjectTreeKind.COLUMN,
        parentLabel = table,
        namespace = schema,
    )

    private fun schemaNode(schema: String) =
        ObjectTreeNode(
            id = analysisSchemaNodeId(schema),
            label = schema,
            kind = ObjectTreeKind.DATABASE,
        )

    // ──────────────── 给的那一种 ────────────────

    @Test
    fun `登记在册的源里的表给删除项 且删的是那个源`() {
        assertEquals(
            "crm",
            analysisDeleteSourceTarget(tableNode("crm"), registered),
            "右键 crm 里的表，删的必须是 crm",
        )
    }

    @Test
    fun `源容器那一行也给删除项`() {
        // 它显示的就是 schema 名，而 schema 名就是数据源名。
        assertEquals("warehouse", analysisDeleteSourceTarget(schemaNode("warehouse"), registered))
    }

    @Test
    fun `字段也归它所在的源`() {
        assertEquals("crm", analysisDeleteSourceTarget(columnNode("crm"), registered))
    }

    // ──────────────── 不给的那几种 ────────────────

    @Test
    fun `工作区自己的表不给删除项`() {
        // `main` 是分析模式**自带的计算引擎**（主设计 M4 要求它可写），
        // 不是数据源 —— 删它等于删掉整个分析模式的工作区。
        assertNull(analysisDeleteSourceTarget(tableNode(WORKSPACE_SCHEMA), registered))
    }

    @Test
    fun `工作区根那一行不给删除项`() {
        // 工作区根的 `label` 是 `main`（显示名走 `labelKey`）——
        // 它不在登记表里，所以自然拿不到删除项，不需要额外特判。
        val workspace =
            ObjectTreeNode(
                id = ANALYSIS_WORKSPACE_NODE_ID,
                label = WORKSPACE_SCHEMA,
                labelKey = com.langbiantianya.i18n.StrKey.ANALYSIS_LOCAL_WORKSPACE,
                kind = ObjectTreeKind.DATABASE,
            )
        assertNull(analysisDeleteSourceTarget(workspace, registered))
    }

    @Test
    fun `没登记过的 schema 不给删除项`() {
        // 用户在 DuckDB 里手工建的 schema 不是「数据源」——
        // 菜单里给它一个「删除数据源」是**说错了它是什么**。
        assertNull(analysisDeleteSourceTarget(tableNode("manual_schema"), registered))
    }

    @Test
    fun `清单为空时什么都不给`() {
        assertNull(analysisDeleteSourceTarget(tableNode("crm"), emptySet()))
    }

    @Test
    fun `没有 namespace 的表不给删除项`() {
        // 防御：万一某个表节点没带 namespace，宁可不给，也不能瞎猜一个源去删。
        val orphan =
            ObjectTreeNode(
                id = "table::orphan",
                label = "orphan",
                kind = ObjectTreeKind.TABLE,
            )
        assertNull(analysisDeleteSourceTarget(orphan, registered))
    }
}
