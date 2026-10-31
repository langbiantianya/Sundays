/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.AnalysisMode
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportLoadResult
import com.langbiantianya.sundays.analysis.report.ReportStatus
import com.langbiantianya.sundays.analysis.report.ReportStorage
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetMove
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import com.langbiantianya.sundays.analysis.report.newReportWidgetDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    /** 「堵住」的会话用的闸门与线程池 —— 见 [newBlockedSession]。 */
    private var blockedGate: CountDownLatch? = null
    private var blockedPool: ExecutorService? = null

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-report-session").toFile()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        // 先把「堵住」的线程放开 —— 不放的话，那个线程会一直挂着，
        // 而测试进程要等非守护线程结束才退出。
        blockedGate?.countDown()
        blockedPool?.shutdownNow()
        scope.cancel()
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    /** 工作区落在临时目录里，不碰用户真实的 `~/.config/sundays`。 */
    private fun newSession(
        reportStorage: ReportStorage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
        filePicker: suspend (initialDir: String, initialName: String) -> NativePathPicker.Picked? = { _, _ -> null },
    ): AnalysisSession =
        AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
            reportStorage = reportStorage,
            filePicker = filePicker,
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

    // ──────────────── 复制部件 ────────────────

    @Test
    fun `copying a part puts the copy right after it`() {
        // 插在后面 —— 用户接着要改的是**那个新的**，它得就在原部件旁边。
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)
        val original = session.reports.first().widgets

        session.copyWidget(reportId, original.first().id)

        val widgets = session.reports.first().widgets
        assertEquals(3, widgets.size)
        assertEquals(original[0].id, widgets[0].id, "原件还在原位")
        assertTrue(widgets[1].id != original[0].id, "复制出来的部件要有自己的 id")
        assertEquals(original[1].id, widgets[2].id, "原件要退到复制件后面")
        assertEquals(original[0].title, widgets[1].title, "复制件的内容与原件一样")
    }

    @Test
    fun `the copy keeps the type the title and the sql`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(type = ReportWidgetType.PIE, title = "占比"), isNew = true)
        val original =
            session.reports
                .first()
                .widgets
                .first()

        session.copyWidget(reportId, original.id)

        val copy = session.reports.first().widgets[1]
        assertEquals(original.type, copy.type)
        assertEquals(original.title, copy.title)
        assertEquals(original.sql, copy.sql)
        assertTrue(copy.id != original.id, "复制出来的部件必须有自己的 id —— 否则数据会互相盖掉")
    }

    @Test
    fun `the copy carries the data over`() {
        // 复制出来的是**同一条 SQL** —— 结果一模一样，不必再跑一遍。
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)
        val original =
            session.reports
                .first()
                .widgets
                .first()
        runBlocking { session.refreshReport(session.reports.first())?.join() }
        assertTrue(session.widgetResults.containsKey(original.id), "先确认数据真的取到了")

        session.copyWidget(reportId, original.id)

        val copy = session.reports.first().widgets[1]
        assertTrue(session.widgetResults.containsKey(copy.id), "复制出来的部件要带着数据")
        assertEquals(session.widgetResults[original.id], session.widgetResults[copy.id])
    }

    // ──────────────── 部件的顺序 ────────────────

    @Test
    fun `moving a part up puts it in front of the one above it`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)
        val original = session.reports.first().widgets

        session.moveWidget(reportId, original[1].id, ReportWidgetMove.UP)

        assertEquals(
            listOf(original[1].id, original[0].id),
            session.reports
                .first()
                .widgets
                .map { it.id },
        )
    }

    @Test
    fun `moving the first part up changes nothing`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙"), isNew = true)
        val original = session.reports.first().widgets

        session.moveWidget(reportId, original[0].id, ReportWidgetMove.UP)

        assertEquals(
            listOf(original[0].id, original[1].id),
            session.reports
                .first()
                .widgets
                .map { it.id },
        )
    }

    // ──────────────── 单个部件刷新 ────────────────

    @Test
    fun `refreshing one part fills only that part`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(title = "甲", sql = "SELECT '甲' AS a, 1 AS b"), isNew = true)
        session.saveWidget(reportId, widget(title = "乙", sql = "SELECT '乙' AS a, 2 AS b"), isNew = true)
        val widgets = session.reports.first().widgets

        runBlocking { session.refreshWidget(reportId, widgets[0].id)?.join() }

        assertEquals(setOf(widgets[0].id), session.widgetResults.keys, "只该刷那一个部件")
        assertTrue(session.refreshingWidgetIds.isEmpty(), "跑完必须把 refreshingWidgetIds 落回去")
    }

    @Test
    fun `refreshing a part that is not in the session changes nothing`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)

        assertNull(session.refreshWidget(reportId, "根本没有这个部件"))
        assertNull(session.refreshWidget("不存在的报表", "w1"))
        assertTrue(session.widgetResults.isEmpty())
    }

    @Test
    fun `starting a second refresh while one is running is refused`() {
        // 运行标志**同步**置位 —— 否则连点两下会两次都通过检查，
        // 两个刷新一起跑，后完成的把先完成的盖掉
        // （A 部件的位置上显示着 B 部件的数据）。
        val session = newSession()
        val reportId = session.newReport()
        // 扫 500 万行 —— 够慢，慢到第二次调用一定落在「正在刷」那个窗口里。
        session.saveWidget(
            reportId,
            widget(sql = "SELECT count(*) AS a, count(*) AS b FROM range(5000000)"),
            isNew = true,
        )

        val first = session.refreshReport(session.reports.first())
        val second = session.refreshReport(session.reports.first())

        assertNotNull(first, "第一次刷新应该跑起来")
        assertNull(second, "已经在刷了就不该再开一遍 —— 两遍一起跑会互相盖掉")
        runBlocking { first.join() }
    }

    @Test
    fun `refreshing one part while another is running is refused`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(
            reportId,
            widget(sql = "SELECT count(*) AS a, count(*) AS b FROM range(5000000)"),
            isNew = true,
        )
        val widgetId =
            session.reports
                .first()
                .widgets
                .first()
                .id

        val first = session.refreshWidget(reportId, widgetId)
        val second = session.refreshWidget(reportId, widgetId)

        assertNotNull(first, "第一次刷新应该跑起来")
        assertNull(second, "同一个部件不能同时刷两遍")
        runBlocking { first.join() }
    }

    // ──────────────── 运行标志什么时候置位 ────────────────

    @Test
    fun `the running flag is taken before the refresh actually starts`() {
        // ⚠️ 这一条**必须确定性地**成立，不能靠「刷新跑得够慢」来赌。
        //
        // 运行标志是「这把锁已经被占了」的凭据 —— 它必须在**调用返回之前**置位。
        // 放在协程里置位的话，连点两下会**两次都通过检查**：两个刷新一起跑，
        // 后完成的把先完成的盖掉（A 部件的位置上显示着 B 部件的数据）。
        //
        // 要确定性地验出这件事，就得让协程**确定不会立刻开跑** ——
        // 这里占住那个唯一的线程，让它排在门外。
        val session = newBlockedSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)

        val job = session.refreshReport(session.reports.first())

        assertTrue(session.refreshRunning, "刷新一启动就该占住工作区 —— 标志必须同步置位")
        assertNull(
            session.refreshReport(session.reports.first()),
            "已经在刷了就不该再开一遍 —— 两遍一起跑会互相盖掉",
        )
        // 断言完了才**放开**那个线程 —— 不放的话，被堵住的刷新永远跑不完，
        // 而用例在等它跑完 —— 那就是自己等自己。
        blockedGate?.countDown()
        runBlocking { job?.join() }
    }

    @Test
    fun `a part being refreshed is marked before the refresh actually starts`() {
        val session = newBlockedSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)
        val widgetId =
            session.reports
                .first()
                .widgets
                .first()
                .id

        val job = session.refreshWidget(reportId, widgetId)

        assertTrue(
            session.refreshingWidgetIds.contains(widgetId),
            "部件一开刷就该被标记 —— 标记是那把锁的凭据",
        )
        assertNull(session.refreshWidget(reportId, widgetId), "同一个部件不能同时刷两遍")
        blockedGate?.countDown()
        runBlocking { job?.join() }
    }

    /**
     * 一个**协程排不上队**的会话。
     *
     * ## 为什么需要它
     *
     * 要验「标志同步置位」，就得让协程**确定不会立刻开跑** ——
     * 否则「标志在协程里置位」的那一版有可能**侥幸**先置上了，测试就变成绿的假象。
     *
     * 做法：占住那个唯一的线程，协程只能在门外排队。
     */
    private fun newBlockedSession(): AnalysisSession {
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit(Runnable { latch.await() })
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        blockedGate = latch
        blockedPool = executor
        return AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
            reportStorage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
            filePicker = { _, _ -> null },
        )
    }

    // ──────────────── 导出 ────────────────

    @Test
    fun `the status line says what the export is waiting for`() {
        // 导出要**重跑每个部件的 SQL** —— 那不是一瞬间的事。
        // 按钮灰掉只说明「点不动了」，没告诉用户**正在忙**；
        // 而上一条「已导出到 …」还挂在屏上，会让人以为**导出已经结束了**。
        //
        // 用「堵住」的会话 —— 导出**确定还在进行中**，不靠「它跑得够慢」来赌。
        val session = newBlockedSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)

        val job = session.exportReport(session.reports.first())

        val status = session.reportStatus
        assertIs<ReportStatus.Info>(status, "导出期间状态条该说明**在等什么**")
        assertEquals(Msg.text(StrKey.REPORT_EXPORT_RUNNING), status.message)
        blockedGate?.countDown()
        runBlocking { job?.join() }
    }

    @Test
    fun `the export writes the file and says where it went`() {
        val target = File(tempHome, "导出/out.xlsx")
        target.parentFile.mkdirs()
        val session =
            newSession(
                filePicker = { _, _ -> NativePathPicker.Picked(dir = target.parentFile.absolutePath, name = target.name) },
            )
        val reportId = session.newReport()
        // SQL 里**故意不出现**结果里的那个值 ——
        // 抬头页会抄一份 SQL 原文，若结果值就在 SQL 里，
        // 断言「文件里有这个值」会被 SQL 原文**兜住**，
        // 于是「导出前没刷新」这种错误就抓不到了（变异验证时被逮到过）。
        session.saveWidget(reportId, widget(sql = "SELECT 42 * 2 AS region, 10 AS n"), isNew = true)

        runBlocking { session.exportReport(session.reports.first())?.join() }

        assertTrue(target.exists(), "导出的文件应该在那个路径上")
        val status = session.reportStatus
        assertIs<ReportStatus.Info>(status, "导出成功了该说一声")
        assertTrue(status.message.contains(target.name), "状态条该说清楚导出到哪儿了")
        // 导出的必须是**导出那一刻**的数据 —— 所以文件里要有部件**真跑出来**的那一行。
        // 只看「文件存在」「文件不为空」是不够的：一个只有抬头页、部件那页是空的
        // xlsx，体积照样不为零。
        val texts = XlsxCells.allTexts(target)
        assertTrue(
            texts.any { it.contains("84") },
            "导出的文件里应该有部件查出来的数据 —— 实际内容：$texts",
        )
    }

    @Test
    fun `cancelling the save dialog writes nothing`() {
        val outDir = File(tempHome, "导出")
        val session = newSession(filePicker = { _, _ -> null })
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)

        runBlocking { session.exportReport(session.reports.first())?.join() }

        assertFalse(outDir.exists(), "用户取消了就不该留下任何文件")
        assertNull(session.reportStatus, "用户取消了，就不该报「导出完成」")
    }

    @Test
    fun `editing the sql drops the data that no longer matches it`() {
        val session = newSession()
        val reportId = session.newReport()
        session.saveWidget(reportId, widget(), isNew = true)
        val original =
            session.reports
                .first()
                .widgets
                .first()
        runBlocking { session.refreshReport(session.reports.first())?.join() }
        assertTrue(session.widgetResults.containsKey(original.id))

        session.saveWidget(reportId, original.copy(sql = "SELECT '别的' AS a, 9 AS b"), isNew = false)

        assertFalse(session.widgetResults.containsKey(original.id), "SQL 改了，旧数据就是**错的**，必须丢掉")
    }

    // ──────────────── 落盘 ────────────────

    @Test
    fun `reports are written to disk after every change`() {
        val storage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json"))
        val session = newSession(storage)
        val reportId = session.newReport()
        session.renameReport(reportId, "销售月报")

        val loaded = storage.load()
        assertIs<ReportLoadResult.Loaded>(loaded)
        assertEquals("销售月报", loaded.reports.single().name, "改完就该落盘 —— 不能等用户记得点保存")
    }

    @Test
    fun `reports come back when the session starts again`() {
        val storage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json"))
        val first = newSession(storage)
        val reportId = first.newReport()
        first.saveWidget(reportId, widget(title = "各月销售额"), isNew = true)
        first.renameReport(reportId, "销售月报")

        val second = newSession(storage)

        assertEquals(1, second.reports.size, "重开应用，报表还在")
        assertEquals("销售月报", second.reports.single().name)
        assertEquals(
            "各月销售额",
            second.reports
                .single()
                .widgets
                .single()
                .title,
        )
        assertEquals(reportId, second.selectedReportId, "读完自动选中第一张 —— 不然用户看到的是空画布")
    }

    @Test
    fun `a new report does not reuse an id that is already on the disk`() {
        // id 是**计数器**发出来的 —— 不把计数器推到最大值，
        // 重开应用后新建的报表会拿到一个**已经被占用**的 id，
        // 于是两个报表共用一个 id：选中一个会选中两个，删一个会删掉两个。
        val storage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json"))
        val first = newSession(storage)
        first.newReport()
        val firstId = first.reports.single().id

        val second = newSession(storage)
        second.newReport()

        assertEquals(2, second.reports.size)
        assertTrue(second.reports[0].id != second.reports[1].id, "两个报表不能共用一个 id")
        assertEquals(firstId, second.reports[0].id, "磁盘上的 id 要原样保留")
    }

    @Test
    fun `a widget id from the disk is not handed out again`() {
        val storage = ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json"))
        val first = newSession(storage)
        val reportId = first.newReport()
        first.saveWidget(reportId, widget(), isNew = true)
        val widgetId =
            first.reports
                .single()
                .widgets
                .single()
                .id

        val second = newSession(storage)
        val newReportId = second.newReport()
        second.saveWidget(newReportId, widget(), isNew = true)

        val ids = second.reports.flatMap { it.widgets }.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "两个部件不能共用一个 id —— 否则数据会互相盖掉")
        assertTrue(ids.contains(widgetId))
    }
}
