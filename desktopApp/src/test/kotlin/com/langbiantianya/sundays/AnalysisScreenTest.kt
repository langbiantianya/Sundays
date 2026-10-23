/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.ANALYSIS_READ_ONLY_BADGE_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_RESULT_TABLE_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_RUN_BUTTON_TAG
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.AnalysisTreeKind
import com.langbiantianya.sundays.analysis.AnalysisTreeNode
import com.langbiantianya.sundays.analysis.analysisTreeRowTag
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.table.TABLE_CELL_TAG_PREFIX
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 分析工作台的界面契约（主设计 §7.5）。
 *
 * ## 这一轮最要紧的一条：结果表**没有**编辑入口
 *
 * 分析模式的结果是**算出来的**，而且它可能来自远端（恒为只读）。
 * 在结果表上留一个编辑入口，等于给远端数据开了一个写入的口子 ——
 * 而分析模式对远端**只有读的需求**。
 *
 * 所以 `onCellEdit = null`：点单元格**不进编辑态**。
 *
 * ## 另一条：只读徽标**不可关**
 *
 * 分析模式连的是远端，而远端**恒为只读**。这不只是「现在开着只读」，
 * 而是「根本关不掉」——
 *
 * - 会话级的 `read_only` 由分析模式自己带上，不落 `connection.json`，
 *   前端没有关它的余地；
 * - 方言级的只读是方言的**实现事实**（引擎里压根没有往它写入的路径），
 *   更无从「解除」。
 *
 * 所以界面上**不能**出现任何「关闭只读」的入口。给了又关不掉的东西，
 * 用户点了发现没反应，下次就不信这个徽标了 —— 而它恰恰是最该被信的那句话。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisScreenTest {
    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
            AnalysisSourceItem(id = "c2", name = "分析 PG", dialectLabel = "PostgreSQL"),
        )

    private val result =
        AnalysisResult(
            columns = listOf(AnalysisColumn("region", "VARCHAR"), AnalysisColumn("n", "INTEGER")),
            rows = listOf(listOf("华东", "1284"), listOf("华南", "932")),
            elapsedMs = 42,
        )

    private val treeRoots =
        listOf(
            AnalysisTreeNode(
                id = "workspace",
                label = "workspace.duckdb",
                kind = AnalysisTreeKind.WORKSPACE,
                dialectLabel = "DuckDB",
            ),
            AnalysisTreeNode(
                id = "c1",
                label = "本地 MySQL",
                kind = AnalysisTreeKind.SOURCE,
                dialectLabel = "MySQL",
            ),
        )

    @Test
    fun `the read only badge is shown and has no close affordance`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                        )
                    }
                }
            }

            val badge = onNodeWithText("只读")
            badge.assertIsDisplayed()

            // 判据落在**徽标容器**上，不是里面那个 Text。
            //
            // ⚠️ 这里踩过一次假绿：按文本找会找到「⊘ 只读」那两个字的 Text 节点，
            // 而可点语义挂在**容器**（Surface）上 —— Text 自己没有可点语义，
            // 于是「徽标不可关」这条断言恒绿，哪怕徽标其实做成了开关。
            // 变异验证（给徽标加上 onClick 语义）必须让这条断言变红。
            val badgeBox = onNodeWithTag(ANALYSIS_READ_ONLY_BADGE_TAG)
            assertFalse(
                badgeBox.fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
                "只读徽标不能可点：它不是开关，远端只读是既定事实",
            )

            // 更宽的一条：整屏**不该有任何开关**（Switch / Checkbox 之类）。
            // 远端恒为只读 —— 界面上放一个能拨的东西，就是在骗用户。
            assertEquals(
                0,
                onAllNodes(isToggleable()).fetchSemanticsNodes().size,
                "分析屏不该有任何可切换的控件 —— 远端只读是既定事实，没有开关可拨",
            )
        }
    }

    @Test
    fun `the analysis screen lists the data sources in the tree`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            treeRoots = treeRoots,
                        )
                    }
                }
            }

            // 数据源的名字同时出现在工具条的下拉框里和左树的行里 ——
            // 按标记找树里那一行，断言才说得清「是哪一行」。
            onNodeWithTag(analysisTreeRowTag("c1")).assertIsDisplayed()
            onNodeWithTag(analysisTreeRowTag("workspace")).assertIsDisplayed()
        }
    }

    @Test
    fun `an empty source list shows the actionable empty state`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = emptyList(),
                        )
                    }
                }
            }

            // 空态必须**说清下一步做什么** —— 只写「没有数据源」等于让用户自己猜。
            onNodeWithText("没有可分析的数据源").assertIsDisplayed()
            onNodeWithText("在「连接管理」里建一个连接；它的方言声明了「可分析」才会出现在这里").assertIsDisplayed()
        }
    }

    // ──────────────── 结果表只读 ────────────────

    @Test
    fun `the back button calls back`() {
        // 顶栏的目标切换 chip 去掉之后，分析模式只有**一条**入口
        // （连接管理的空闲面板）—— 若这里没有返回路，用户进去就回不来了。
        var backs = 0
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            onBack = { backs++ },
                        )
                    }
                }
            }

            onNodeWithContentDescription(zh(StrKey.BACK)).performClick()
        }
        assertEquals(1, backs, "点「返回」必须回调一次")
    }

    @Test
    fun `the source is picked in the tree only and not in the toolbar`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            treeRoots = treeRoots,
                        )
                    }
                }
            }

            // 源**只有一处**能选：左树。
            //
            // 早先工具条上还有一个「数据源」下拉框 —— 那是**两份可能对不上的真相**：
            // 下拉框里选了 A 源、树上点了 B 源的表，界面上「当前源是 A」，
            // 拉下去的却是 B 源的**同名表** —— 而那**不会报错**（只要恰好有那张表），
            // 只是数据悄悄换了来源。
            //
            // 判据：源的名字在屏幕上**只出现一次**（树里那一行）。
            // 下拉框在的时候是两次 —— 判据因此有分辨力。
            onAllNodesWithText("本地 MySQL").assertCountEquals(1)
        }
    }

    @Test
    fun `the result table has no editing entry`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            result = result,
                        )
                    }
                }
            }

            onNodeWithTag(ANALYSIS_RESULT_TABLE_TAG).assertIsDisplayed()

            // 判据：**可编辑**的单元格才会被 DataTable 挂上编辑标记
            // （`sundays.cell.<列键>`）—— 只读的单元格连这个标记都没有，
            // 它压根没准备接收编辑手势。
            //
            // ⚠️ 这里**不能**写成「点一下单元格，看有没有冒出输入框」：
            // 那种写法是**假绿** —— 实测过，把结果表改成可编辑后，
            // 点单元格**照样**不冒输入框（编辑手势挂在单元格的 Box 上，
            // 而 `Text` 自己不收指针事件，见 `DataTable` 里 `TableCell` 的 KDoc），
            // 于是那条断言无论只读还是可编辑都恒绿 ——
            // 绿得像模像样，却什么都没守住。
            //
            // 一个测不出来的断言等于没有断言，所以这里用**结构**判据：
            // 「这个单元格压根没有编辑入口」这件事本身就该看得见。
            onNodeWithTag(TABLE_CELL_TAG_PREFIX + "c0").assertDoesNotExist()
        }
    }

    @Test
    fun `the result table shows the columns and the rows`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            result = result,
                        )
                    }
                }
            }

            onNodeWithText("region").assertIsDisplayed()
            onNodeWithText("华东").assertIsDisplayed()
            onNodeWithText("932").assertIsDisplayed()
            // 面板头上有行数 —— 2 行
            onNodeWithText("结果（2 行）").assertIsDisplayed()
        }
    }

    @Test
    fun `a failed query shows the reason instead of an empty table`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            result = AnalysisResult(error = "Catalog Error: Table with name orders does not exist"),
                        )
                    }
                }
            }

            onNodeWithText("查询失败").assertIsDisplayed()
            onNodeWithText("Catalog Error: Table with name orders does not exist").assertIsDisplayed()
        }
    }

    // ──────────────── 运行 ────────────────

    @Test
    fun `the run button calls back`() {
        var runs = 0
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            onRun = { runs++ },
                        )
                    }
                }
            }

            onNodeWithTag(ANALYSIS_RUN_BUTTON_TAG).performClick()
        }

        assertEquals(1, runs, "点「运行」必须回调一次")
    }

    @Test
    fun `the run button is disabled while a query is running`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            running = true,
                            onRun = {},
                        )
                    }
                }
            }

            // 正在跑的时候再点一下就是**第二条并发查询** ——
            // 而工作区是**一个** DuckDB 文件，后一条会把前一条的结果盖掉。
            onNodeWithTag(ANALYSIS_RUN_BUTTON_TAG).assertIsNotEnabled()
        }
    }

    // ──────────────── 编辑器 ────────────────

    @Test
    fun `the editor shows the current sql`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            sql = "SELECT * FROM staging_shop_orders",
                        )
                    }
                }
            }

            // 编辑器把 SQL 放在**可写文本**里（BasicTextField）——
            // 断言落在那儿，才真的验到「SQL 进了编辑器」。
            onNode(hasSetTextAction()).assertTextContains("SELECT * FROM staging_shop_orders")
        }
    }
}
