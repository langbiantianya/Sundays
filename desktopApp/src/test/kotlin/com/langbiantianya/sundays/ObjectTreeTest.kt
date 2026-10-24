/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeColumn
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.objectTreeRowTag
import com.langbiantianya.sundays.tree.objectTreeTwistyTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 共享树组件（`com.langbiantianya.sundays.tree.ObjectTree`）的界面契约。
 *
 * ## 为什么它值得单独钉住
 *
 * 浏览器与数据分析**共用**这一个组件 —— 两边的树长得一样、行为一样，
 * 是「统一」这件事的**结果**，不是巧合。改坏它，两边一起坏。
 *
 * ## 这一轮最要紧的三条
 *
 * 1. **点容器是「展开 / 收起」，点表是「选中它」** ——
 *    两个动作的**后果**完全不同（选中远端表会拉数据）。
 *    混成一种手势，用户点表时就会误触发展开。
 * 2. **表的扭子与表行是两个动作** —— 点扭子展开字段，点整行选中表。
 *    同一个手势不能既选中又展开。
 * 3. **字段行画得出类型与约束标记** —— 「这张表有哪些列、都是什么类型」
 *    是写分析 SQL 时最需要的对照信息。
 */
@OptIn(ExperimentalTestApi::class)
class ObjectTreeTest {
    private val workspace =
        ObjectTreeNode(
            id = "workspace",
            label = "workspace.duckdb",
            labelKey = StrKey.ANALYSIS_LOCAL_WORKSPACE,
            kind = ObjectTreeKind.DATABASE,
            badge = "DuckDB",
        )

    private val database =
        ObjectTreeNode(
            id = "db1",
            label = "shop",
            kind = ObjectTreeKind.DATABASE,
        )

    private val table =
        ObjectTreeNode(
            id = "table:db1::orders",
            label = "orders",
            kind = ObjectTreeKind.TABLE,
            parentLabel = "db1",
        )

    private val column =
        ObjectTreeNode(
            id = "column:orders:id",
            label = "id",
            kind = ObjectTreeKind.COLUMN,
            parentLabel = "orders",
            column =
                ObjectTreeColumn(
                    name = "id",
                    type = "INTEGER",
                    primaryKey = true,
                    nullable = false,
                ),
        )

    // ──────────────── 行 ────────────────

    @Test
    fun `every node gets a row of its own`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(workspace, database),
                            expandedIds = emptySet(),
                            onToggle = {},
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag("workspace")).assertIsDisplayed()
            onNodeWithTag(objectTreeRowTag("db1")).assertIsDisplayed()
        }
    }

    @Test
    fun `the fixed label wins over the raw one`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(workspace),
                            expandedIds = emptySet(),
                            onToggle = {},
                        )
                    }
                }
            }

            // 「本地工作区」是应用自带的引擎，用户改不了它的名字 ——
            // 显示的是**固定名**，不是节点里那个文件名。
            onNodeWithText("本地工作区").assertIsDisplayed()
            onNodeWithText("workspace.duckdb").assertDoesNotExist()
        }
    }

    @Test
    fun `a container row shows its badge`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(workspace),
                            expandedIds = emptySet(),
                            onToggle = {},
                        )
                    }
                }
            }

            onNodeWithText("DuckDB").assertIsDisplayed()
        }
    }

    // ──────────────── 点的后果 ────────────────

    @Test
    fun `clicking a container row toggles it`() {
        var toggled: ObjectTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database),
                            expandedIds = emptySet(),
                            onToggle = { toggled = it },
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag("db1")).performClick()

            assertEquals("db1", toggled?.id, "整行也要能展开 —— 扭子太小，不是每个人都会去点它")
        }
    }

    @Test
    fun `clicking a table selects it instead of toggling`() {
        var toggled: ObjectTreeNode? = null
        var selected: ObjectTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table))),
                            expandedIds = setOf("db1"),
                            onToggle = { toggled = it },
                            onSelectTable = { selected = it },
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag(table.id)).performClick()

            assertEquals(table.id, selected?.id, "点表必须走「选中」")
            assertNull(toggled, "点表**不能**走「展开」—— 那是另一个后果")
        }
    }

    @Test
    fun `clicking the twisty of a table toggles it`() {
        var toggled: ObjectTreeNode? = null
        var selected: ObjectTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table))),
                            expandedIds = setOf("db1"),
                            onToggle = { toggled = it },
                            onSelectTable = { selected = it },
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeTwistyTag(table.id)).performClick()

            assertEquals(table.id, toggled?.id, "扭子是「只展开这一层」最直接的入口")
            assertNull(selected, "点扭子不该顺带把表选中")
        }
    }

    // ──────────────── 字段 ────────────────

    @Test
    fun `the columns of an expanded table are rendered`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table.copy(children = listOf(column))))),
                            expandedIds = setOf("db1", table.id),
                            onToggle = {},
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag(column.id)).assertIsDisplayed()
            onNodeWithText("id").assertIsDisplayed()
            onNodeWithText("INTEGER").assertIsDisplayed()
        }
    }

    @Test
    fun `a primary key column is marked as one`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table.copy(children = listOf(column))))),
                            expandedIds = setOf("db1", table.id),
                            onToggle = {},
                        )
                    }
                }
            }

            // 约束标记要画出来 —— 「这是主键」是写 SQL 时绕不开的信息。
            onNodeWithText("PK").assertIsDisplayed()
        }
    }

    @Test
    fun `clicking a column selects it`() {
        var selected: ObjectTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table.copy(children = listOf(column))))),
                            expandedIds = setOf("db1", table.id),
                            onToggle = {},
                            onSelectColumn = { selected = it },
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag(column.id)).performClick()

            assertEquals(column.id, selected?.id, "点字段必须回调那个字段")
            assertEquals("orders", selected?.parentLabel, "字段要带着它属于哪张表")
        }
    }

    // ──────────────── 展开与空树 ────────────────

    @Test
    fun `the children of an expanded node are rendered`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = listOf(database.copy(children = listOf(table))),
                            expandedIds = setOf("db1"),
                            onToggle = {},
                        )
                    }
                }
            }

            onNodeWithTag(objectTreeRowTag(table.id)).assertIsDisplayed()
            // 没展开的子节点不该出现
            onNodeWithTag(objectTreeRowTag(column.id)).assertDoesNotExist()
        }
    }

    @Test
    fun `an empty tree renders nothing but does not crash`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ObjectTree(
                            roots = emptyList(),
                            expandedIds = emptySet(),
                            onToggle = {},
                        )
                    }
                }
            }

            assertTrue(true, "空树不该抛异常")
        }
    }
}
