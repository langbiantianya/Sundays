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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.width
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisMode
import com.langbiantianya.sundays.analysis.AnalysisModeBar
import com.langbiantianya.sundays.analysis.AnalysisModeTags
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.WORKSPACE_SCHEMA
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportDesignerScreen
import com.langbiantianya.sundays.analysis.report.ReportDesignerTags
import com.langbiantianya.sundays.analysis.report.ReportStatus
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetEditorTags
import com.langbiantianya.sundays.analysis.report.ReportWidgetMove
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.tree.ObjectTreeColumn
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.objectTreeRowTag
import com.langbiantianya.sundays.ui.SIDE_PANE_COLLAPSED_WIDTH
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

    /**
     * 报表设计页上那棵**数据树** —— 工作区 → orders → amount。
     *
     * ## 为什么字段节点要带 [ObjectTreeColumn]
     *
     * 共享树组件画字段行时**先看** `node.column` —— 没有它就整行不画。
     * 只给 `label` 的话，字段在树上根本不会出现，点它的测试就成了空谈。
     */
    private val treeRoots =
        listOf(
            ObjectTreeNode(
                id = "schema:main",
                label = "本地工作区",
                kind = ObjectTreeKind.DATABASE,
                badge = "DuckDB",
                children =
                    listOf(
                        ObjectTreeNode(
                            id = "table:main:orders",
                            label = "orders",
                            kind = ObjectTreeKind.TABLE,
                            namespace = WORKSPACE_SCHEMA,
                            children =
                                listOf(
                                    ObjectTreeNode(
                                        id = "column:main:orders:amount",
                                        label = "amount",
                                        kind = ObjectTreeKind.COLUMN,
                                        parentLabel = "orders",
                                        namespace = WORKSPACE_SCHEMA,
                                        column = ObjectTreeColumn(name = "amount", type = "INTEGER"),
                                    ),
                                ),
                        ),
                    ),
            ),
        )

    /** 树里展开着的节点 —— 工作区与 orders 都要展开，字段才露得出来。 */
    private val expandedTreeIds = setOf("schema:main", "table:main:orders")

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

    // ──────────────── 数据树 ────────────────

    @Test
    fun `the report design shows the data tree`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 树是**看得见的** —— 用户得能看见有哪些表与字段，
            // 否则「方便用户选择」这件事根本无从谈起。
            onNodeWithTag(ReportDesignerTags.DATA_TREE).assertIsDisplayed()
            // 而且树里**真有内容** —— 只摆一个空壳面板不算「显示了树」。
            treeRow("table:main:orders", ReportDesignerTags.DATA_TREE).assertIsDisplayed()
        }

    @Test
    fun `clicking a table in the tree starts a new part filled with that table`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            treeRow("table:main:orders", ReportDesignerTags.DATA_TREE).performClick()
            waitForIdle()

            // 点表 = 开一个新部件，而那条部件的 SQL 已经把表填好了 ——
            // 用户不用记住表名再敲一遍。
            onNodeWithTag(ReportWidgetEditorTags.EDITOR).assertIsDisplayed()
            sqlEditor().assertTextContains("SELECT * FROM \"orders\"")
        }

    @Test
    fun `clicking a column in the editor tree puts the reference into the sql`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id).performClick()
            waitForIdle()

            // 编辑页里那棵树是**另一棵**（画布上那棵被编辑页盖住了，但它还在组合里），
            // 所以必须圈定到编辑页这一棵再点 ——
            // 不圈定的话，同一个 tag 会找到两行，断言反而失败。
            treeRow("column:main:orders:amount", ReportWidgetEditorTags.DATA_TREE).performClick()
            waitForIdle()

            // 字段的引用**必须带表名** —— 光有列名在多表查询里说不清是哪张表的列。
            //
            // 用 `substring = true`：引用是**追加**到已有 SQL 后面的，
            // 整条语句不等于那一句引用。
            sqlEditor().assertTextContains("\"orders\".\"amount\"", substring = true)
        }

    @Test
    fun `a tree click with no report yet makes one first`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val created = mutableListOf<Unit>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = emptyList(),
                            selectedReportId = null,
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            onCreateReport = { created += Unit },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            treeRow("table:main:orders", ReportDesignerTags.DATA_TREE).performClick()
            waitForIdle()

            // 部件总得有个家 —— 一张报表都没有时先建一张。
            // 不这么做的话，这一下点击就**白点了**：用户点了，什么也没发生。
            assertEquals(1, created.size, "还没有报表时点树应当先建一张报表")
            onNodeWithTag(ReportWidgetEditorTags.EDITOR).assertIsDisplayed()
        }

    // ──────────────── 布局与折叠 ────────────────

    @Test
    fun `the data tree sits on the left and the report list on the far right`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            val treeLeft = leftOf(ReportDesignerTags.DATA_TREE)
            val canvasLeft = leftOf(ReportDesignerTags.REPORT_CANVAS)
            val listLeft = leftOf(ReportDesignerTags.REPORT_LIST)

            // 输入在左（树）、主产物在中（画布）、另一类产物在右（报表列表）。
            assertTrue(
                treeLeft < canvasLeft,
                "数据树应当在画布左边：tree=$treeLeft canvas=$canvasLeft",
            )
            assertTrue(
                canvasLeft < listLeft,
                "报表列表应当在画布右边：canvas=$canvasLeft list=$listLeft",
            )
            // 报表列表贴在**窗口最右** —— 右边不该再留一条空白。
            assertEquals(
                rightOf(ReportDesignerTags.DESIGNER),
                rightOf(ReportDesignerTags.REPORT_LIST),
                1f,
                "报表列表应当贴着窗口的右边缘",
            )
        }

    @Test
    fun `the data tree folds away into a rail on the left`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            val canvasBefore = widthOf(ReportDesignerTags.REPORT_CANVAS)
            val treeBefore = widthOf(ReportDesignerTags.DATA_TREE)

            onNodeWithTag(ReportDesignerTags.DATA_TREE_TOGGLE).performClick()
            waitForIdle()

            // 树**整个**从屏上消失 —— 44dp 塞不下树，画一截残影既没法看也没法点。
            onAllNodesWithTag(ReportDesignerTags.DATA_TREE).assertCountEquals(0)
            // 窄轨就是**那个固定宽度**。
            assertEquals(
                SIDE_PANE_COLLAPSED_WIDTH.value,
                widthOf(ReportDesignerTags.DATA_TREE_RAIL),
                1f,
                "窄轨应当就是那个固定宽度",
            )
            // 窄轨必须**比刚才的面板窄** —— 折叠的意义就是把宽度**让出来**。
            //
            // 为什么要单独钉这一条：只比「窄轨 == 那个常量」的话，
            // 常量本身被改大时两边一起变，断言照样成立 ——
            // 而那时候折叠就**什么也没让出**（用户白点了一次）。
            assertTrue(
                widthOf(ReportDesignerTags.DATA_TREE_RAIL) < treeBefore,
                "窄轨应当比面板窄（rail=${widthOf(ReportDesignerTags.DATA_TREE_RAIL)} panel=$treeBefore）",
            )
            // 窄轨贴在**窗口最左**（树那一侧）。
            assertEquals(
                leftOf(ReportDesignerTags.DESIGNER),
                leftOf(ReportDesignerTags.DATA_TREE_RAIL),
                1f,
                "窄轨应当贴在窗口的左边缘",
            )
            // 折叠的**意义**是把宽度让给主区 —— 这一条才是「折叠有没有用」的判据。
            assertTrue(
                widthOf(ReportDesignerTags.REPORT_CANVAS) > canvasBefore,
                "折叠后画布应当变宽：before=$canvasBefore after=${widthOf(ReportDesignerTags.REPORT_CANVAS)}",
            )
            // 另一侧**不受影响** —— 两块面板各收各的。
            onNodeWithTag(ReportDesignerTags.REPORT_LIST).assertIsDisplayed()

            // 再点一下回来。
            onNodeWithTag(ReportDesignerTags.DATA_TREE_TOGGLE).performClick()
            waitForIdle()
            onAllNodesWithTag(ReportDesignerTags.DATA_TREE_RAIL).assertCountEquals(0)
            onNodeWithTag(ReportDesignerTags.DATA_TREE).assertIsDisplayed()
        }

    @Test
    fun `the report list folds away into a rail on the far right`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            val canvasBefore = widthOf(ReportDesignerTags.REPORT_CANVAS)
            val listBefore = widthOf(ReportDesignerTags.REPORT_LIST)

            onNodeWithTag(ReportDesignerTags.REPORT_LIST_TOGGLE).performClick()
            waitForIdle()

            onAllNodesWithTag(ReportDesignerTags.REPORT_LIST).assertCountEquals(0)
            // 窄轨就是**那个固定宽度**。
            assertEquals(
                SIDE_PANE_COLLAPSED_WIDTH.value,
                widthOf(ReportDesignerTags.REPORT_LIST_RAIL),
                1f,
                "窄轨应当就是那个固定宽度",
            )
            // 窄轨必须**比刚才的面板窄** —— 折叠的意义就是把宽度**让出来**。
            assertTrue(
                widthOf(ReportDesignerTags.REPORT_LIST_RAIL) < listBefore,
                "窄轨应当比面板窄（rail=${widthOf(ReportDesignerTags.REPORT_LIST_RAIL)} panel=$listBefore）",
            )
            // 窄轨贴在**窗口最右**（报表列表那一侧）。
            assertEquals(
                rightOf(ReportDesignerTags.DESIGNER),
                rightOf(ReportDesignerTags.REPORT_LIST_RAIL),
                1f,
                "窄轨应当贴在窗口的右边缘",
            )
            assertTrue(
                widthOf(ReportDesignerTags.REPORT_CANVAS) > canvasBefore,
                "折叠后画布应当变宽：before=$canvasBefore after=${widthOf(ReportDesignerTags.REPORT_CANVAS)}",
            )
            // 左侧的数据树**不受影响** —— 两块面板各收各的。
            onNodeWithTag(ReportDesignerTags.DATA_TREE).assertIsDisplayed()

            // 再点一下回来。
            onNodeWithTag(ReportDesignerTags.REPORT_LIST_TOGGLE).performClick()
            waitForIdle()
            onAllNodesWithTag(ReportDesignerTags.REPORT_LIST_RAIL).assertCountEquals(0)
            onNodeWithTag(ReportDesignerTags.REPORT_LIST).assertIsDisplayed()
        }

    @Test
    fun `folding both sides hands the whole width to the canvas`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.DATA_TREE_TOGGLE).performClick()
            waitForIdle()
            onNodeWithTag(ReportDesignerTags.REPORT_LIST_TOGGLE).performClick()
            waitForIdle()

            // 两侧都收起来之后，画布应当占满**除两条窄轨以外**的全部宽度。
            // 留着空白的话，说明有一侧压根没被收走 —— 那正是这条断言要抓的。
            assertEquals(
                widthOf(ReportDesignerTags.DESIGNER),
                widthOf(ReportDesignerTags.REPORT_CANVAS) + 2 * SIDE_PANE_COLLAPSED_WIDTH.value,
                1f,
                "两侧都收起来后，画布应当占满除两条窄轨以外的全部宽度",
            )
        }

    @Test
    fun `the two panels have their own fold descriptions`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 屏幕上有**两块**能折叠的面板 —— 无障碍文案必须**各说各的**。
            // 共用一句的话，读屏念「折叠树面板」，用户照着提示去点，
            // 收起来的却是**另一块** —— 提示反而在误导人。
            onNode(
                hasContentDescription(zh(StrKey.TREE_PANE_COLLAPSE)) and
                    hasAnyAncestor(hasTestTag(ReportDesignerTags.DATA_TREE_TOGGLE)),
                useUnmergedTree = true,
            ).assertExists()
            onNode(
                hasContentDescription(zh(StrKey.REPORT_LIST_PANE_COLLAPSE)) and
                    hasAnyAncestor(hasTestTag(ReportDesignerTags.REPORT_LIST_TOGGLE)),
                useUnmergedTree = true,
            ).assertExists()
        }

    @Test
    fun `the report list panel gets wider when it is dragged towards the left`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            val before = widthOf(ReportDesignerTags.REPORT_LIST)
            // 报表列表贴在**窗口最右** —— 所以往**左**拖才变宽（与左边的树**镜像**）。
            //
            // 拖反了的后果不是「难看」，是**反直觉**：往左拖，面板却**变窄**了。
            // 而分隔条又是「看起来能拖」的样子 —— 用户多半会以为坏了。
            dragHandle(ReportDesignerTags.REPORT_LIST_DRAG_HANDLE, (-40f))
            waitForIdle()

            val after = widthOf(ReportDesignerTags.REPORT_LIST)
            assertTrue(
                after > before,
                "右侧面板往左拖应当变宽：before=$before after=$after",
            )
        }

    // ──────────────── 部件的动作 ────────────────

    /** 两张报表 —— 第二张有**两个**部件，才能验「上移 / 下移」。 */
    private val reportWithTwoWidgets =
        AnalysisReport(
            id = "r2",
            name = "两张部件",
            widgets =
                listOf(
                    widget,
                    ReportWidget("w2", ReportWidgetType.BAR, "各月销售额", "SELECT month, amount FROM sales"),
                ),
        )

    @Test
    fun `every part gets a refresh a copy and two move buttons`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_REFRESH_PREFIX + "w1").assertExists()
            onNodeWithTag(ReportDesignerTags.WIDGET_COPY_PREFIX + "w1").assertExists()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_UP_PREFIX + "w1").assertExists()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_DOWN_PREFIX + "w1").assertExists()
        }

    @Test
    fun `the copy button tells the session which part to copy`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val copied = mutableListOf<Pair<String, String>>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            onCopyWidget = { reportId, widgetId -> copied += reportId to widgetId },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_COPY_PREFIX + "w1").performClick()

            assertEquals(listOf("r1" to "w1"), copied)
        }

    @Test
    fun `the move buttons tell the session which way to move`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val moves = mutableListOf<ReportWidgetMove>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(reportWithTwoWidgets),
                            selectedReportId = "r2",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            onMoveWidget = { _, _, move -> moves += move },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_DOWN_PREFIX + "w1").performClick()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_UP_PREFIX + "w2").performClick()

            assertEquals(listOf(ReportWidgetMove.DOWN, ReportWidgetMove.UP), moves)
        }

    @Test
    fun `the first part cannot move up and the last cannot move down`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(reportWithTwoWidgets),
                            selectedReportId = "r2",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_UP_PREFIX + "w1").assertIsNotEnabled()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_DOWN_PREFIX + "w1").assertIsEnabled()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_UP_PREFIX + "w2").assertIsEnabled()
            onNodeWithTag(ReportDesignerTags.WIDGET_MOVE_DOWN_PREFIX + "w2").assertIsNotEnabled()
        }

    @Test
    fun `the refresh button of a part is disabled while the whole report is refreshing`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            refreshRunning = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_REFRESH_PREFIX + "w1").assertIsNotEnabled()
        }

    @Test
    fun `the refresh button of a part is enabled when nothing is running`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.WIDGET_REFRESH_PREFIX + "w1").assertIsEnabled()
        }

    // ──────────────── 导出与状态提示 ────────────────

    @Test
    fun `the export button asks the session to export the report`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            val exported = mutableListOf<String>()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            onExportReport = { exported += it.id },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.EXPORT_BUTTON).performClick()

            assertEquals(listOf("r1"), exported)
        }

    @Test
    fun `the export button is disabled while an export is running`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            exportRunning = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(ReportDesignerTags.EXPORT_BUTTON).assertIsNotEnabled()
        }

    @Test
    fun `the status line shows the message it is given`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            status = ReportStatus.Error("报表保存失败了"),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithText("报表保存失败了").assertIsDisplayed()
            onNodeWithTag(ReportDesignerTags.STATUS_LINE).assertIsDisplayed()
        }

    @Test
    fun `there is no status line when there is nothing to say`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        ReportDesignerScreen(
                            reports = listOf(report),
                            selectedReportId = "r1",
                            treeRoots = treeRoots,
                            expandedNodeIds = expandedTreeIds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onAllNodesWithTag(ReportDesignerTags.STATUS_LINE).assertCountEquals(0)
        }

    // ──────────────── 圈定范围的取节点 ────────────────

    /**
     * 某一棵**树**里的某一行。
     *
     * @param nodeId 节点 id
     * @param paneTag 那棵树的**面板** tag —— 报表设计页上同时存在两棵树
     *   （画布上那棵与编辑页里那棵），不圈定的话会找到两行
     */
    private fun ComposeUiTest.treeRow(
        nodeId: String,
        paneTag: String,
    ) = onNode(
        hasTestTag(objectTreeRowTag(nodeId)) and hasAnyAncestor(hasTestTag(paneTag)),
        useUnmergedTree = true,
    )

    /**
     * 部件编辑页里的 **SQL 编辑器**。
     *
     * 编辑器把 SQL 放在**可写文本**里（BasicTextField）—— 断言落在那儿，
     * 才真的验到「SQL 进了编辑器」。
     *
     * 为什么要圈定到编辑器那一块：编辑页上还有一个标题输入框，
     * 它同样是可写文本 —— 不圈定就会找到两个节点。
     */
    private fun ComposeUiTest.sqlEditor() =
        onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(ReportWidgetEditorTags.SQL_EDITOR)),
            useUnmergedTree = true,
        )

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

    // ──────────────── 布局的度量 ────────────────

    /**
     * 某个 tag 的节点的**左边界**（dp）。
     *
     * 用 `getUnclippedBoundsInRoot` 而不是「可见区域」——
     * 折叠后窄轨贴着窗口边，可见区域会被裁掉一截，量出来的就不是布局的真实位置了。
     */
    private fun ComposeUiTest.leftOf(tag: String): Float = onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value

    /** 某个 tag 的节点的**右边界**（dp）。 */
    private fun ComposeUiTest.rightOf(tag: String): Float = onNodeWithTag(tag).getUnclippedBoundsInRoot().right.value

    /** 某个 tag 的节点的**宽度**（dp）。 */
    private fun ComposeUiTest.widthOf(tag: String): Float = onNodeWithTag(tag).getUnclippedBoundsInRoot().width.value

    /**
     * 在分隔条上按下、水平移动 [by] dp、松开。
     *
     * 用触摸作用域而不是鼠标作用域：`MouseInjectionScope` 只暴露 `click` / `moveTo` 这类
     * 成品动作，没有 `down` / `up`，而 `detectDragGestures` 需要一次完整的按下-移动-抬起。
     *
     * @param by 移动的像素数（**负数 = 往左**）
     */
    private fun ComposeUiTest.dragHandle(
        tag: String,
        by: Float,
    ) {
        onNodeWithTag(tag).performTouchInput {
            down(center)
            moveBy(Offset(by, 0f))
            up()
        }
    }
}
