/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 共享树模型的**纯规则**测试。
 *
 * 这一层全是纯数据变换（给定输入必然给出同一个输出），
 * 所以能在任何平台上跑 —— 不需要引擎，也不需要数据库。
 *
 * ## 为什么这些规则住在**共享**的包里
 *
 * 树现在是浏览模式与分析模式**共用**的组件，模型也共用一份。
 * 那么「按 id 找」「按 id 替换」「谁可以展开」这些规则就只有**一处**实现 ——
 * 两边各写一份的话，改一处就忘了另一处，而两边的树迟早会对不上。
 */
class ObjectTreeModelTest {
    // ──────────────── 可展开性 ────────────────

    @Test
    fun `only columns are leaves`() {
        assertTrue(node(ObjectTreeKind.DATABASE).expandable(), "容器是分组，可展开")
        assertTrue(node(ObjectTreeKind.TABLE).expandable(), "表是分组，可展开（展开是字段）")
        assertFalse(node(ObjectTreeKind.COLUMN).expandable(), "字段是叶子，不可展开")
    }

    // ──────────────── 按 id 找 / 按 id 替换 ────────────────

    @Test
    fun `a nested node can be found by its id`() {
        val tree = listOf(database("db1", listOf(table("db1", "orders", listOf(column("orders", "id"))))))

        val found = assertNotNull(findObjectTreeNode(tree, "table:db1::orders"), "深处的表必须能被找到")
        assertEquals("orders", found.label)
    }

    @Test
    fun `an unknown id is simply not found`() {
        val tree = listOf(database("db1"))
        assertNull(findObjectTreeNode(tree, "nope"), "找不到不是错误，是正常状态")
    }

    @Test
    fun `replacing a node keeps the rest of the tree intact`() {
        val tree =
            listOf(
                database("db1", listOf(table("db1", "orders"))),
                database("db2"),
            )

        val replaced =
            replaceObjectTreeNode(tree, "table:db1::orders") {
                it.copy(children = listOf(column("orders", "id")), loaded = true)
            }

        // 换掉的那一处换了
        val orders = assertNotNull(findObjectTreeNode(replaced, "table:db1::orders"))
        assertEquals(1, orders.children.size)
        assertTrue(orders.loaded, "换过的节点应当标记成已加载")
        // 其余的**一点没动**
        assertEquals(2, replaced.size, "根节点的数量不能变")
        assertEquals(emptyList(), replaced[1].children, "没点名的分支不能被动过")
        assertEquals("db2", replaced[1].id)
    }

    @Test
    fun `replacing an unknown id leaves the tree unchanged`() {
        val tree = listOf(database("db1"))
        val replaced = replaceObjectTreeNode(tree, "nope") { it.copy(label = "改过了") }
        assertEquals(tree, replaced, "找不到的 id 不能凭空造出节点")
    }

    @Test
    fun `replacing a root node works`() {
        val tree = listOf(database("db1"), database("db2"))
        val replaced = replaceObjectTreeNode(tree, "db2") { it.copy(label = "新的名字") }
        assertEquals("新的名字", replaced[1].label)
    }

    // ──────────────── 测试标记 ────────────────

    @Test
    fun `the row and twisty tags are derived from the node id`() {
        assertEquals("objectTreeRow:db1", objectTreeRowTag("db1"))
        assertEquals("objectTreeTwisty:db1", objectTreeTwistyTag("db1"))
    }

    @Test
    fun `the row tag and the twisty tag never collide`() {
        // 行与扭子挂在不同节点上，靠 tag 区分 —— 撞了就分不清点的是哪一个。
        assertTrue(objectTreeRowTag("x") != objectTreeTwistyTag("x"))
    }

    // ──────────────── 造节点的小工具 ────────────────

    private fun node(kind: ObjectTreeKind) = ObjectTreeNode(id = "x", label = "x", kind = kind)

    private fun database(
        id: String,
        children: List<ObjectTreeNode> = emptyList(),
    ) = ObjectTreeNode(
        id = id,
        label = id,
        kind = ObjectTreeKind.DATABASE,
        children = children,
    )

    private fun table(
        db: String,
        name: String,
        children: List<ObjectTreeNode> = emptyList(),
    ) = ObjectTreeNode(
        id = "table:$db::$name",
        label = name,
        kind = ObjectTreeKind.TABLE,
        parentLabel = db,
        children = children,
    )

    private fun column(
        table: String,
        name: String,
    ) = ObjectTreeNode(
        id = "column:$table:$name",
        label = name,
        kind = ObjectTreeKind.COLUMN,
        parentLabel = table,
        column = ObjectTreeColumn(name = name, type = "INTEGER"),
    )
}
