/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 报表**判据**的纯规则 —— 部件的数据形状。
 *
 * ## 为什么这些判据要有自己的用例
 *
 * 它们回答的是「这个部件**画不画得出来**」。判据错一点的后果很具体：
 * 用户写了一条三列的 SQL，界面却画出一张**只画了前两列**的图，
 * 而第三列的数据**静默消失**了 —— 用户不会知道自己漏看了什么。
 *
 * 所以这里逐条钉住：缺列、值不是数字、有负值、没有数据，
 * 每一种都要给出**能区分开**的原因。
 */
class ReportModelsTest {
    /** 两列的结果（类别 + 数值）。 */
    private fun resultOf(
        rows: List<List<String?>>,
        columnCount: Int = 2,
        error: String? = null,
    ): AnalysisResult =
        AnalysisResult(
            columns =
                List(columnCount) { index ->
                    AnalysisColumn(name = "c$index", type = "VARCHAR")
                },
            rows = rows,
            error = error,
        )

    @Test
    fun `a null result has no data to draw`() {
        assertEquals(
            ChartData.Invalid(ChartProblem.NO_DATA),
            chartDataOf(null),
        )
    }

    @Test
    fun `a failed query has no data to draw`() {
        assertEquals(
            ChartData.Invalid(ChartProblem.NO_DATA),
            chartDataOf(resultOf(emptyList(), error = "语法错误")),
        )
    }

    @Test
    fun `a query without columns has nothing to draw`() {
        assertEquals(
            ChartData.Invalid(ChartProblem.NO_COLUMNS),
            chartDataOf(resultOf(emptyList(), columnCount = 0)),
        )
    }

    @Test
    fun `one column is not enough for a chart`() {
        assertEquals(
            ChartData.Invalid(ChartProblem.NEED_TWO_COLUMNS),
            chartDataOf(resultOf(listOf(listOf("华东")), columnCount = 1)),
        )
    }

    @Test
    fun `no rows means no data`() {
        assertEquals(
            ChartData.Invalid(ChartProblem.NO_DATA),
            chartDataOf(resultOf(emptyList())),
        )
    }

    @Test
    fun `two columns become label and value`() {
        val data = chartDataOf(resultOf(listOf(listOf("华东", "128"), listOf("华北", "64"))))
        assertEquals(
            ChartData.Ready(listOf(ChartSlice("华东", 128.0), ChartSlice("华北", 64.0))),
            data,
        )
    }

    @Test
    fun `a value that is not a number is refused outright`() {
        // 跳过那一行，图表会**看起来是对的**（少了一根柱子），
        // 而用户不知道少了什么 —— 那比直接报错糟得多。
        assertEquals(
            ChartData.Invalid(ChartProblem.VALUE_NOT_NUMERIC),
            chartDataOf(resultOf(listOf(listOf("华东", "128"), listOf("华北", "很多")))),
        )
    }

    @Test
    fun `a negative value is refused`() {
        // 饼图算不出角度；条形图画成正值长度则是误导（-50 看起来像 50）。
        assertEquals(
            ChartData.Invalid(ChartProblem.NEGATIVE_VALUES),
            chartDataOf(resultOf(listOf(listOf("退款", "-50")))),
        )
    }

    @Test
    fun `a blank label still counts as a category`() {
        val data = chartDataOf(resultOf(listOf(listOf("", "10"))))
        assertEquals(
            ChartData.Ready(listOf(ChartSlice("", 10.0))),
            data,
        )
    }

    @Test
    fun `the scale is the biggest value`() {
        assertEquals(
            128.0,
            chartValueScale(listOf(ChartSlice("a", 128.0), ChartSlice("b", 64.0))),
        )
        assertEquals(0.0, chartValueScale(emptyList()), "没有数据时不该崩")
    }

    @Test
    fun `colors cycle instead of running out`() {
        // 超过色板长度时要**循环**而不是抛异常 ——
        // 十几个类别是常事（一个部门一张表的那种报表）。
        assertEquals(chartColor(0), chartColor(8))
        assertTrue(chartColor(0) != chartColor(1))
    }

    // ──────────────── 新部件的草稿 ────────────────

    @Test
    fun `a new widget has no id yet`() {
        // id 是**存下来之后**才有的身份 —— 新建中的部件还没进报表，
        // 先发一个 id 出去就是承诺了一个它可能永远用不到的身份。
        val draft = newReportWidgetDraft()
        assertEquals("", draft.id)
        assertEquals("", draft.title)
        assertEquals("", draft.sql)
    }

    @Test
    fun `a new widget defaults to a bar chart`() {
        // 条形图比饼图更好读（人眼比长度比角度准得多），
        // 所以它是「随便画点什么」的默认值。
        assertEquals(ReportWidgetType.BAR, newReportWidgetDraft().type)
    }

    @Test
    fun `a new widget keeps what it was given`() {
        val draft =
            newReportWidgetDraft(
                type = ReportWidgetType.PIE,
                title = "大区占比",
                sql = "SELECT region, amount FROM orders",
            )
        assertEquals(ReportWidgetType.PIE, draft.type)
        assertEquals("大区占比", draft.title)
        assertEquals("SELECT region, amount FROM orders", draft.sql)
    }

    // ──────────────── 文案映射 ────────────────

    @Test
    fun `every chart problem has its own sentence`() {
        // 四种画不出来的原因必须**说四句不同的话** ——
        // 合成一句「图表数据有问题」等于什么都没说。
        val keys = ChartProblem.entries.map { chartProblemText(it) }
        assertEquals(keys.size, keys.toSet().size, "不同的原因不能共用同一句话：$keys")
    }

    @Test
    fun `every widget type has its own sentence`() {
        val keys = ReportWidgetType.entries.map { reportWidgetTypeText(it) }
        assertEquals(keys.size, keys.toSet().size, "不同的类型不能共用同一句话：$keys")
    }

    // ──────────────── 样例数据是不是过期 ────────────────

    @Test
    fun `a preview that was never taken is not stale`() {
        // 还没预览过时根本没有样例数据，也就没有「过期」这回事。
        assertFalse(isPreviewStale(null, "SELECT 1"))
    }

    @Test
    fun `a preview of the current sql is not stale`() {
        assertFalse(isPreviewStale("SELECT 1", "SELECT 1"))
    }

    @Test
    fun `a preview of an older sql is stale`() {
        // 预览完又改了 SQL —— 眼前的样例数据是**旧 SQL** 跑出来的，
        // 不说清楚的话，用户会以为那就是新 SQL 的结果。
        assertTrue(isPreviewStale("SELECT 1", "SELECT 2"))
    }

    // ──────────────── 部件的顺序 ────────────────

    /** 三个部件 —— 顺序即显示顺序。 */
    private fun widgets() =
        listOf(
            ReportWidget("w1", ReportWidgetType.BAR, "甲", "SELECT 1 AS a, 2 AS b"),
            ReportWidget("w2", ReportWidgetType.PIE, "乙", "SELECT 1 AS a, 2 AS b"),
            ReportWidget("w3", ReportWidgetType.TABLE, "丙", "SELECT 1 AS a, 2 AS b"),
        )

    @Test
    fun `the first widget cannot move up`() {
        // 已经是第一个了 —— 再往上挪没有意义，按钮要灰掉。
        assertFalse(canMoveReportWidget(widgets(), "w1", ReportWidgetMove.UP))
    }

    @Test
    fun `the last widget cannot move down`() {
        assertFalse(canMoveReportWidget(widgets(), "w3", ReportWidgetMove.DOWN))
    }

    @Test
    fun `a widget in the middle can move both ways`() {
        assertTrue(canMoveReportWidget(widgets(), "w2", ReportWidgetMove.UP))
        assertTrue(canMoveReportWidget(widgets(), "w2", ReportWidgetMove.DOWN))
    }

    @Test
    fun `an unknown widget cannot move at all`() {
        assertFalse(canMoveReportWidget(widgets(), "根本没有这个部件", ReportWidgetMove.UP))
    }

    @Test
    fun `moving up puts the widget in front of the one above it`() {
        val moved = moveReportWidget(widgets(), "w2", ReportWidgetMove.UP)
        assertEquals(listOf("w2", "w1", "w3"), moved.map { it.id })
    }

    @Test
    fun `moving down puts the widget behind the one below it`() {
        val moved = moveReportWidget(widgets(), "w2", ReportWidgetMove.DOWN)
        assertEquals(listOf("w1", "w3", "w2"), moved.map { it.id })
    }

    @Test
    fun `moving a widget that cannot move changes nothing`() {
        val original = widgets()
        assertEquals(original, moveReportWidget(original, "w1", ReportWidgetMove.UP))
        assertEquals(original, moveReportWidget(original, "w3", ReportWidgetMove.DOWN))
    }

    @Test
    fun `moving an unknown widget changes nothing`() {
        val original = widgets()
        assertEquals(original, moveReportWidget(original, "根本没有这个部件", ReportWidgetMove.UP))
    }

    // ──────────────── 报表的显示名 ────────────────

    @Test
    fun `a named report shows its name`() {
        assertEquals("销售月报", reportDisplayNameText("销售月报", "报表 3"))
    }

    @Test
    fun `a report without a name falls back to the given one`() {
        // 兜底名由调用方按语言算好（「报表 N」）—— 会话层不是 Composable，
        // 它取不到当前语言，所以兜底名得由它自己算好后传进来。
        assertEquals("报表 3", reportDisplayNameText("", "报表 3"))
        assertEquals("报表 3", reportDisplayNameText("   ", "报表 3"))
    }
}
