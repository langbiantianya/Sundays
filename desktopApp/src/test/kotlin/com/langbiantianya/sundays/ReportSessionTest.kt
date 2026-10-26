/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.AnalysisMode
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import com.langbiantianya.sundays.analysis.report.newReportWidgetDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 报表在**会话**里的行为 —— 真 DuckDB 工作区、真引擎。
 *
 * ## 这一轮最要紧的两条
 *
 * 1. **部件的 id 是保存时才有的** —— 新建中的部件 id 是空的，
 *    由会话在存下来那一刻发。id 早了就是承诺了一个它可能永远用不到的身份。
 * 2. **样例数据是真的跑出来的** —— 「预览」不是拿假数据糊弄界面，
 *    它真的在工作区上跑那条 SQL，把结果交给界面。
 *
 * ## 为什么删部件要连它的数据一起删
 *
 * 删掉部件却不删它的查询结果，会留下一堆**永远没人引用**的数据 ——
 * 地图越攒越大，而界面上再也看不到它们。
 */
class ReportSessionTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-report-session").toFile()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    /** 工作区落在临时目录里，不碰用户真实的 `~/.config/sundays`。 */
    private fun newSession(): AnalysisSession =
        AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
        )

    /** 建一张报表，返回它的 id。 */
    private fun AnalysisSession.newReport(): String {
        createReport()
        return assertNotNull(selectedReportId, "新建报表后必须选中它")
    }

    /** 一个部件草稿（id 是空的 —— 由会话在保存时发）。 */
    private fun widget(
        type: ReportWidgetType = ReportWidgetType.BAR,
        title: String = "标题",
        sql: String = "SELECT '华东' AS region, 10 AS n",
    ): ReportWidget = newReportWidgetDraft(type = type, title = title, sql = sql)

    // ──────────────── 视图 ────────────────

    @Test
    fun `the session starts on the workbench`() {
        assertEquals(AnalysisMode.WORKBENCH, newSession().analysisMode)
    }

    @Test
    fun `the mode can be switched and stays switched`() {
        // 切到别的顶层页再切回来，用户希望自己还在报表设计里 ——
        // 而顶层页切换会重建整棵组合，屏幕层的 remember 活不过这次切页。
        val session = newSession()
        session.selectMode(AnalysisMode.REPORT)
        assertEquals(AnalysisMode.REPORT, session.analysisMode)
    }

    // ──────────────── 报表 ────────────────

    @Test
    fun `a new report is created and selected`() {
        val session = newSession()
        assertTrue(session.reports.isEmpty())

        val id = session.newReport()

        assertEquals(1, session.reports.size)
        assertEquals(id, session.selectedReportId)
    }

    @Test
    fun `a new report has no name yet`() {
        // 名字里的「报表 1」那个「1」是要翻译的词，而会话不是 Composable，
        // 取不到当前语言 —— 所以这里只留空名字，由界面按位置显示「报表 N」。
        val session = newSession()
        session.newReport()
        assertEquals("", session.reports.first().name)
    }

    @Test
    fun `a report can be renamed`() {
        val session = newSession()
        val id = session.newReport()

        session.renameReport(id, "  销售月报  ")

        assertEquals("销售月报", session.reports.first().name, "名字两端的多余空白要去掉")
    }

    @Test
    fun `selecting an unknown report changes nothing`() {
        // 界面可能拿着**刚被删掉**的报表 id 回调过来 —— 那时不能把选中态清空。
        val session = newSession()
        val id = session.newReport()

        session.selectReport("不存在的报表")

        assertEquals(id, session.selectedReportId)
    }

    @Test
    fun `deleting the selected report selects another one`() {
        val session = newSession()
        val first = session.newReport()
        val second = session.newReport()
        assertEquals(second, session.selectedReportId)

        session.deleteReport(second)

        assertEquals(1, session.reports.size)
        assertEquals(first, session.selectedReportId, "删掉的正是选中那张时，要顺手选中剩下的")
    }

    @Test
    fun `deleting the last report leaves nothing selected`() {
        val session = newSession()
        val id = session.newReport()

        session.deleteReport(id)

        assertTrue(session.reports.isEmpty())
        assertNull(session.selectedReportId)
    }

    @Test
    fun `deleting a report drops its widget data too`() {
        val session = newSession()
        val id = session.newReport()
        session.saveWidget(id, widget(), isNew = true)
        runBlocking { session.refreshReport(session.reports.first())?.join() }
        assertTrue(session.widgetResults.isNotEmpty(), "先确认数据真的取到了")

        session.deleteReport(id)

        assertTrue(session.widgetResults.isEmpty(), "删掉报表却留着它的数据，就是留了一堆没人引用的垃圾")
    }

    // ──────────────── 部件 ────────────────

    @Test
    fun `a saved widget gets an id`() {
        val session = newSession()
        val reportId = session.newReport()

        session.saveWidget(reportId, widget(), isNew = true)

        val widgets = session.reports.first().widgets
        assertEquals(1, widgets.size)
        assertTrue(widgets.first().id.isNotBlank(), "新建的部件必须拿到 id —— 否则它的数据无处安放")
        assertEquals("标题", widgets.first().title)
    }

    @Test
    fun `two saved widgets get different ids`() {
        val session = newSession()
        val reportId = session.newReport()

        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)

        val widgets = session.reports.first().widgets
        assertEquals(2, widgets.size)
        assertTrue(widgets[0].id != widgets[1].id, "两个部件不能共用一个 id —— 否则数据会互相盖掉")
    }

    @Test
    fun `editing a widget keeps its id and its place`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)
        val original = session.reports.first().widgets

        session.saveWidget(
            reportId,
            original.first().copy(title = "甲改过了"),
            isNew = false,
        )

        val widgets = session.reports.first().widgets
        assertEquals(2, widgets.size)
        assertEquals("甲改过了", widgets[0].title)
        assertEquals(original[0].id, widgets[0].id, "改一个部件不能换掉它的 id —— 它的数据还挂在那个 id 上")
        assertEquals(original[1].id, widgets[1].id)
    }

    @Test
    fun `deleting a widget removes it and its data`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)
        val report = session.reports.first()
        val widgetId = report.widgets.first().id
        runBlocking { session.refreshReport(report)?.join() }
        assertTrue(session.widgetResults.containsKey(widgetId))

        session.deleteWidget(reportId, widgetId)

        val after = session.reports.first()
        assertTrue(after.widgets.isEmpty())
        assertFalse(session.widgetResults.containsKey(widgetId), "删掉部件却留着它的数据，就是留了一堆没人引用的垃圾")
    }

    // ──────────────── 样例数据 ────────────────

    @Test
    fun `the preview really runs the sql on the workspace`() {
        // 「预览」不是拿假数据糊弄界面 —— 它真的跑那条 SQL。
        val session = newSession()

        runBlocking { session.previewSql("SELECT '华东' AS region, 128 AS n")?.join() }

        val result = assertNotNull(session.previewResult, "预览完必须有结果")
        assertFalse(result.failed, "预览不该失败：${result.error}")
        assertEquals(1, result.rows.size)
        assertEquals("华东", result.rows.first().first())
        assertEquals(2, result.columns.size, "样例数据要把两列都带回来 —— 图表要靠它们画")
    }

    @Test
    fun `a blank statement is not previewed at all`() {
        val session = newSession()

        assertNull(session.previewSql("   \n "), "空语句不该启动一次查询")
        assertNull(session.previewResult)
    }

    @Test
    fun `a failing preview reports the error instead of throwing`() {
        // 界面要显示的是「这句话哪里错了」—— 那是一行要摆出来的文字，
        // 不是要往上抛的异常。
        val session = newSession()

        runBlocking { session.previewSql("SELECT * FROM 不存在的表")?.join() }

        val result = assertNotNull(session.previewResult, "失败也要有结果 —— 否则界面没话说")
        assertTrue(result.failed, "这条 SQL 跑不通")
        assertTrue(!result.error.isNullOrBlank(), "失败必须说清原因")
        assertFalse(session.previewRunning, "跑完必须把 previewRunning 落回去")
    }

    @Test
    fun `refreshing a report fills the data of every widget`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)

        runBlocking { session.refreshReport(session.reports.first())?.join() }

        val report = session.reports.first()
        val ids = report.widgets.map { it.id }
        assertEquals(ids.toSet(), session.widgetResults.keys, "每个部件都要拿到自己那份数据")
    }

    @Test
    fun `refreshing a report leaves the widgets in the right order`() {
        // 串行跑是为了「每个部件拿到的都是自己那份」——
        // 并发的话，后完成的会把先完成的盖掉，界面上出现错配。
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲", sql = "SELECT '甲' AS a, 1 AS b"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙", sql = "SELECT '乙' AS a, 2 AS b"), isNew = true)

        runBlocking { session.refreshReport(session.reports.first())?.join() }

        val report = session.reports.first()
        val ids = report.widgets.map { it.id }
        val firstRow = session.widgetResults[ids[0]]
        val secondRow = session.widgetResults[ids[1]]
        assertEquals("甲", firstRow?.rows?.first()?.first(), "第一个部件的位置上显示的必须是第一个部件的数据")
        assertEquals("乙", secondRow?.rows?.first()?.first())
    }

    @Test
    fun `refreshing a report that is not in the session changes nothing`() {
        val session = newSession()
        val ghost =
            AnalysisReport(
                id = "不存在的报表",
                name = "",
                widgets = listOf(ReportWidget("w9", ReportWidgetType.BAR, "幽灵", "SELECT 1 AS a, 2 AS b")),
            )

        runBlocking { session.refreshReport(ghost)?.join() }

        assertTrue(session.widgetResults.isEmpty(), "刷新一张不存在的报表不该把数据塞进会话")
        assertFalse(session.refreshRunning, "跑完必须把 refreshRunning 落回去")
    }
}
