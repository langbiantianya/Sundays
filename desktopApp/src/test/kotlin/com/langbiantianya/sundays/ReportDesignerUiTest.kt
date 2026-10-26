/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisMode
import com.langbiantianya.sundays.analysis.AnalysisModeBar
import com.langbiantianya.sundays.analysis.AnalysisModeTags
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportDesignerScreen
import com.langbiantianya.sundays.analysis.report.ReportDesignerTags
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetEditorTags
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 报表设计页面的界面契约。
 *
 * ## 这一轮最要紧的两条
 *
 * 1. **加部件时能看到样例数据** —— 部件的形状约定是「第一列类别、第二列数值」，
 *    而约定只有**看得见数据**才能被遵守。看不到的话，用户只能先把整张报表
 *    搭出来才知道自己写错了 SQL。
 * 2. **删报表要先问一句** —— 删掉的是一整张报表（连同它里面的部件），
 *    不可恢复。
 *
 * ## 为什么「画不出图表」要**说清原因**
 *
 * 图表的数据形状有五种坏法（没列 / 只有一列 / 值不是数字 / 有负值 / 没数据）。
 * 合成一句「图表数据有问题」等于什么都没说 —— 用户不知道自己该改 SQL 的哪一处。
 */
@OptIn(ExperimentalTestApi::class)
class ReportDesignerUiTest {
    private val widget =
        ReportWidget(
            id = "w1",
            type = ReportWidgetType.PIE,
            title = "大区占比",
            sql = "SELECT region, amount FROM orders",
        )

    private val report = AnalysisReport(id = "r1", name = "销售月报", widgets = listOf(widget))

    /** 两列的样例数据 —— 类别 + 数值。 */
    private val sampleResult =
        AnalysisResult(
            columns = listOf(AnalysisColumn("region", "VARCHAR"), AnalysisColumn("amount", "INTEGER")),
            rows = listOf(listOf("华东", "128"), listOf("华北", "64")),
        )

    @Test
    fun `the report list shows the reports it is given`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 报表名会在**两处**出现（列表项 + 画布标题）——
            // 所以断言要**圈定范围**，否则「找到了 2 个」反而让断言失败。
            onNode(
                hasText("销售月报") and hasAnyAncestor(hasTestTag(ReportDesignerTags.REPORT_LIST)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            onNodeWithTag(ReportDesignerTags.REPORT_LIST).assertIsDisplayed()
        }

    @Test
    fun `a report without a name is numbered by its place in the list`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports =
                                listOf(
                                    AnalysisReport(id = "r1", name = "", widgets = emptyList()),
                                    AnalysisReport(id = "r2", name = "", widgets = emptyList()),
                                ),
                            selectedReportId = "r1",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 编号按**列表位置**算 —— 按创建顺序编号的话，删掉第一张之后
            // 剩下的会变成「报表 2、报表 3」，列表里明明只有两张。
            listNodeWithText(zh(StrKey.REPORT_DEFAULT_NAME, 1)).assertIsDisplayed()
            listNodeWithText(zh(StrKey.REPORT_DEFAULT_NAME, 2)).assertIsDisplayed()
        }

    @Test
    fun `adding a part opens the editor and the editor offers the three chart types`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithText(zh(StrKey.REPORT_ADD_WIDGET)).performClick()
            waitForIdle()

            onNodeWithTag(ReportWidgetEditorTags.EDITOR).assertIsDisplayed()
            onNodeWithTag(ReportWidgetEditorTags.TYPE_PIE).assertIsDisplayed()
            onNodeWithTag(ReportWidgetEditorTags.TYPE_BAR).assertIsDisplayed()
            onNodeWithTag(ReportWidgetEditorTags.TYPE_TABLE).assertIsDisplayed()
        }

    @Test
    fun `the editor previews the sql of the part being edited`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val previewed = mutableListOf<String>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            onPreviewSql = { previewed += it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id).performClick()
            waitForIdle()
            onNodeWithTag(ReportWidgetEditorTags.PREVIEW_BUTTON).performClick()
            waitForIdle()

            assertEquals(listOf(widget.sql), previewed, "预览必须把**这条部件的 SQL** 交出去")
        }

    @Test
    fun `the sample data shows up next to the sql`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            previewResult = sampleResult,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id).performClick()
            waitForIdle()

            onNodeWithTag(ReportWidgetEditorTags.SAMPLE_TABLE).assertIsDisplayed()
            // 样例数据的**原始行**要看得见 —— 用户要拿它核对 SQL 写得对不对。
            // 同一个词也会出现在图表图例里，所以要圈定在**样例数据表**内。
            sampleCellWithText("华东").assertIsDisplayed()
            sampleCellWithText("128").assertIsDisplayed()
        }

    @Test
    fun `a chart that cannot be drawn says why`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            previewResult =
                                AnalysisResult(
                                    columns =
                                        listOf(
                                            AnalysisColumn("region", "VARCHAR"),
                                            AnalysisColumn("amount", "VARCHAR"),
                                        ),
                                    rows = listOf(listOf("华东", "很多")),
                                ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id).performClick()
            waitForIdle()

            onNodeWithText(zh(StrKey.REPORT_CHART_CANNOT_DRAW)).assertIsDisplayed()
            // 原因要**具体** ——「值不是数字」与「缺列」要能分开，
            // 否则用户不知道自己该改 SQL 的哪一处。
            onNodeWithText(zh(StrKey.CHART_PROBLEM_VALUE_NOT_NUMERIC)).assertIsDisplayed()
        }

    @Test
    fun `a failed preview shows the error instead of an empty table`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            previewResult = AnalysisResult(error = "Catalog Error: Table with name orders does not exist"),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id).performClick()
            waitForIdle()

            onNodeWithText(zh(StrKey.REPORT_RESULT_FAILED)).assertIsDisplayed()
            onNodeWithText("Catalog Error: Table with name orders does not exist").assertIsDisplayed()
        }

    @Test
    fun `deleting a report asks first and only deletes after the user agrees`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val deleted = mutableListOf<String>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            onDeleteReport = { deleted += it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.REPORT_ITEM_DELETE_PREFIX + report.id).performClick()
            waitForIdle()

            assertTrue(deleted.isEmpty(), "点「删除」还没确认时**绝不能**真的删 —— 那是不可恢复的")
            onNodeWithText(zh(StrKey.REPORT_DELETE_CONFIRM)).assertIsDisplayed()

            onNodeWithText(zh(StrKey.CANCEL)).performClick()
            waitForIdle()
            assertTrue(deleted.isEmpty(), "取消不应触发删除")
        }

    @Test
    fun `deleting a part asks first too`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val deleted = mutableListOf<Pair<String, String>>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            onDeleteWidget = { reportId, widgetId -> deleted += reportId to widgetId },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_DELETE_PREFIX + widget.id).performClick()
            waitForIdle()

            assertTrue(deleted.isEmpty(), "删部件同样要先确认")
            onNodeWithText(zh(StrKey.REPORT_WIDGET_DELETE_CONFIRM)).assertIsDisplayed()
        }

    @Test
    fun `the refresh button is disabled while a refresh is running`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            refreshRunning = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 正在刷的时候按钮还亮着，就成了「点了没反应」——
            // 那比明确地灰掉更让人困惑。
            onNodeWithTag(ReportDesignerTags.REFRESH_BUTTON).assertIsNotEnabled()
        }

    @Test
    fun `the refresh button is enabled when nothing is running`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.REFRESH_BUTTON).assertIsEnabled()
        }

    @Test
    fun `the mode bar switches between the workbench and the report design`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            var mode = AnalysisMode.WORKBENCH
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        AnalysisModeBar(
                            mode = mode,
                            onModeChange = { mode = it },
                        )
                    }
                }
            }

            onNodeWithTag(AnalysisModeTags.TAB_PREFIX + AnalysisMode.REPORT.name).performClick()
            waitForIdle()

            assertEquals(AnalysisMode.REPORT, mode)
        }

    // ──────────────── 圈定范围的取节点 ────────────────

    /**
     * 报表**列表里**的那个文本节点。
     *
     * 报表名会在**两处**出现（列表项 + 画布标题），不圈定范围的话
     * `onNodeWithText` 会找到两个节点，断言反而失败 —— 那是断言写得不对，
     * 不是界面出了问题。
     */
    private fun ComposeUiTest.listNodeWithText(text: String) =
        onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(ReportDesignerTags.REPORT_LIST)),
            useUnmergedTree = true,
        )

    /** 样例数据表里的那个文本节点 —— 同样的理由（图表图例里也有相同的词）。 */
    private fun ComposeUiTest.sampleCellWithText(text: String) =
        onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(ReportWidgetEditorTags.SAMPLE_TABLE)),
            useUnmergedTree = true,
        )
}
