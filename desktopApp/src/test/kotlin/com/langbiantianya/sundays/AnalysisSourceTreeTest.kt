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
import com.langbiantianya.sundays.analysis.ANALYSIS_WORKSPACE_NODE_ID
import com.langbiantianya.sundays.analysis.AnalysisTreeKind
import com.langbiantianya.sundays.analysis.AnalysisTreeNode
import com.langbiantianya.sundays.analysis.SourceTree
import com.langbiantianya.sundays.analysis.analysisNamespaceNodeId
import com.langbiantianya.sundays.analysis.analysisTableNodeId
import com.langbiantianya.sundays.analysis.analysisTreeRowTag
import com.langbiantianya.sundays.analysis.analysisTreeTwistyTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据源树的界面契约。
 *
 * ## 这一轮最要紧的两条
 *
 * 1. **分组节点与表节点的点击后果不同** ——
 *    点分组是「展开 / 收起」，点表是「选中它」（远端表会被拉取）。
 *    两种手势若混成一种，用户点远端表时就会**误触发拉取**（一次耗时又占磁盘的动作）。
 * 2. **工作区那一行的名字是固定的** —— 「本地工作区」是应用自带的引擎，
 *    不是用户起名的。名字由界面按 `StrKey` 取，会话不传字符串 ——
 *    于是也就传不错语言。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisSourceTreeTest {
    private val workspace =
        AnalysisTreeNode(
            id = ANALYSIS_WORKSPACE_NODE_ID,
            label = "workspace.duckdb",
            kind = AnalysisTreeKind.WORKSPACE,
            dialectLabel = "DuckDB",
        )

    private val source =
        AnalysisTreeNode(
            id = "c1",
            label = "hive_prod",
            kind = AnalysisTreeKind.SOURCE,
            dialectLabel = "MySQL",
        )

    private val namespace =
        AnalysisTreeNode(
            id = analysisNamespaceNodeId("c1", "shop"),
            label = "shop",
            kind = AnalysisTreeKind.NAMESPACE,
            sourceId = "c1",
            namespace = "shop",
        )

    private val table =
        AnalysisTreeNode(
            id = analysisTableNodeId("c1", "shop", "orders"),
            label = "orders",
            kind = AnalysisTreeKind.TABLE,
            sourceId = "c1",
            namespace = "shop",
            table = "orders",
        )

    @Test
    fun `every node gets a row of its own`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(workspace, source),
                            expandedIds = emptySet(),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            onNodeWithTag(analysisTreeRowTag(ANALYSIS_WORKSPACE_NODE_ID)).assertIsDisplayed()
            onNodeWithTag(analysisTreeRowTag("c1")).assertIsDisplayed()
        }
    }

    @Test
    fun `the workspace row shows the fixed local workspace name`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(workspace),
                            expandedIds = emptySet(),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            // 显示的是**固定名**，不是节点里那个文件名 ——
            // 「本地工作区」是应用自带的引擎，用户改不了它的名字。
            onNodeWithText("本地工作区").assertIsDisplayed()
            onNodeWithText("workspace.duckdb").assertDoesNotExist()
        }
    }

    @Test
    fun `a source row shows its dialect label`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source),
                            expandedIds = emptySet(),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            onNodeWithText("hive_prod").assertIsDisplayed()
            onNodeWithText("MySQL").assertIsDisplayed()
        }
    }

    @Test
    fun `clicking the twisty of a group toggles it`() {
        var toggled: AnalysisTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source),
                            expandedIds = emptySet(),
                            onToggle = { toggled = it },
                            onSelectTable = {},
                        )
                    }
                }
            }

            onNodeWithTag(analysisTreeTwistyTag("c1")).performClick()

            assertEquals("c1", toggled?.id, "点扭子必须回调那个节点")
        }
    }

    @Test
    fun `clicking a group row toggles it as well`() {
        var toggled: AnalysisTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source),
                            expandedIds = emptySet(),
                            onToggle = { toggled = it },
                            onSelectTable = {},
                        )
                    }
                }
            }

            onNodeWithTag(analysisTreeRowTag("c1")).performClick()

            assertEquals("c1", toggled?.id, "整行也要能展开 —— 扭子太小，不是每个人都会去点它")
        }
    }

    @Test
    fun `clicking a table selects it instead of toggling`() {
        var toggled: AnalysisTreeNode? = null
        var selected: AnalysisTreeNode? = null
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source.copy(children = listOf(table))),
                            expandedIds = setOf("c1"),
                            onToggle = { toggled = it },
                            onSelectTable = { selected = it },
                        )
                    }
                }
            }

            onNodeWithTag(analysisTreeRowTag(table.id)).performClick()

            assertEquals(table.id, selected?.id, "点表必须走「选中」")
            assertEquals(null, toggled, "点表**不能**走「展开」—— 那是另一个后果")
        }
    }

    @Test
    fun `the children of an expanded node are rendered`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source.copy(children = listOf(namespace))),
                            expandedIds = setOf("c1"),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            onNodeWithTag(analysisTreeRowTag(namespace.id)).assertIsDisplayed()
            // 没展开的子节点不该出现
            onNodeWithTag(analysisTreeRowTag(table.id)).assertDoesNotExist()
        }
    }

    @Test
    fun `a table row has no twisty`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = listOf(source.copy(children = listOf(table))),
                            expandedIds = setOf("c1"),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            // 表是叶子 —— 给它一个扭子，用户点了却什么也不会发生。
            onNodeWithTag(analysisTreeTwistyTag(table.id)).assertDoesNotExist()
            onNodeWithTag(analysisTreeRowTag(table.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `an empty tree renders nothing but does not crash`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        SourceTree(
                            roots = emptyList(),
                            expandedIds = emptySet(),
                            onToggle = {},
                            onSelectTable = {},
                        )
                    }
                }
            }

            assertTrue(true, "空树不该抛异常")
        }
    }
}
