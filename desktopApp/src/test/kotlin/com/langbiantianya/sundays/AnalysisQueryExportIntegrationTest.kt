/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.AnalysisExportStatus
import com.langbiantianya.sundays.analysis.report.ReportStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 分析工作台的「导出查询结果」端到端测试 —— 真 DuckDB 工作区、真引擎、真文件。
 *
 * ## 为什么必须用**真**工作区
 *
 * 分析模式的查询恒在**本地工作区**（DuckDB 文件）上跑，而那个文件
 * **正被主进程占着** —— 这正是「导出子进程打不开它」的那条实测事实。
 *
 * 用假引擎验不到这件事：假引擎根本不会去开文件。
 * 而这条能力恰恰**只**在真工作区上才成立。
 *
 * ## 它守住的是什么
 *
 * | 行为 | 判据 |
 * |---|---|
 * | 导出会真的落一个文件 | 目录里有文件，且**内容**是工作区里的那两行 |
 * | 走的是引擎那条既有链路 | 状态是 `Succeeded`，行数与内容都对得上 |
 * | 预览只放行读语句 | `UPDATE` 在**发出去之前**就被拒 |
 */
class AnalysisQueryExportIntegrationTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var session: AnalysisSession

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-export").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        session =
            AnalysisSession(
                engine = engine,
                scope = CoroutineScope(Dispatchers.IO),
                workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
                reportStorage =
                    ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
            )
        // DuckDB 建库不会自动建上级目录 —— 先把目录建出来
        assertTrue(File(tempHome, ".config/sundays").mkdirs(), "工作区目录应当能建出来")
    }

    @After
    fun tearDown() {
        try {
            engine.close()
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    private fun runSql(sql: String) =
        runBlocking {
            engine.invoke(session.workspaceConnection()) {
                category = Category.ANALYSIS
                action = Action.RUN_QUERY
                analysisRequest =
                    analysisRequest {
                        runQuery = analysisRunQueryRequest { this.sql = sql }
                    }
            }
        }

    // ---------------------------------------------------------------------
    // ① 导出：真的落一个文件
    // ---------------------------------------------------------------------

    @Test
    fun `导出会真的在工作区里查出数据并落成文件`() {
        runSql("CREATE TABLE nums (id INT, label VARCHAR)")
        runSql("INSERT INTO nums VALUES (1, '第一行'), (2, 'second')")

        val outDir = Files.createTempDirectory("sundays-analysis-out").toFile()
        val job =
            session.startQueryExport(
                sql = "SELECT id, label FROM nums ORDER BY id",
                format = ExportFormat.CSV,
                outputDir = outDir.absolutePath,
                fileName = "nums.csv",
            )
        assertNotNull(job, "导出任务应当被启动")

        // 等它跑完 —— 状态与文件都要在**跑完之后**才有意义
        val finished =
            runBlocking {
                withTimeoutOrNull(120_000) {
                    job!!.join()
                    true
                }
            }
        assertTrue(finished == true, "导出 120 秒内没结束 —— 又一次静默挂起")

        val status = session.exportStatus
        assertTrue(
            status is AnalysisExportStatus.Succeeded,
            "导出应当成功，实际状态：$status",
        )
        val succeeded = status as AnalysisExportStatus.Succeeded
        assertEquals(2L, succeeded.rowsWritten, "应当导出 2 行")

        val file = outDir.resolve("nums.csv")
        assertTrue(file.exists(), "文件没生成：${outDir.absolutePath} 下没有 nums.csv")
        // ⚠️ 内容也要看 —— 只断言「文件存在」的话，导出一个空文件也能过
        val text = file.readText()
        assertTrue(text.contains("第一行"), "工作区里的数据没导出来：$text")
        assertTrue(text.contains("second"), "工作区里的数据没导出来：$text")
    }

    // ---------------------------------------------------------------------
    // ② 失败必须看得见
    // ---------------------------------------------------------------------

    @Test
    fun `导出会失败时状态里必须有原因而不是静悄悄`() {
        val outDir = Files.createTempDirectory("sundays-analysis-out-bad").toFile()
        val job =
            session.startQueryExport(
                sql = "SELECT * FROM 这张表不存在",
                format = ExportFormat.CSV,
                outputDir = outDir.absolutePath,
                fileName = "bad.csv",
            )
        assertNotNull(job, "导出任务应当被启动")
        val finished =
            runBlocking {
                withTimeoutOrNull(120_000) {
                    job!!.join()
                    true
                }
            }
        assertTrue(finished == true, "导出 120 秒内没结束")

        val status = session.exportStatus
        assertTrue(
            status is AnalysisExportStatus.Failed,
            "查不到表必须变成一条**看得见**的失败，实际状态：$status",
        )
        val failed = status as AnalysisExportStatus.Failed
        assertTrue(failed.reason.isNotBlank(), "失败必须带原因")
    }

    // ---------------------------------------------------------------------
    // ③ 预览：只放行读语句
    // ---------------------------------------------------------------------

    @Test
    fun `预览在发出去之前就拒绝写语句`() {
        runSql("CREATE TABLE nums (id INT, label VARCHAR)")
        runSql("INSERT INTO nums VALUES (1, '第一行')")

        val rejected =
            runBlocking {
                session.previewExportSql("UPDATE nums SET label = 'x'")
            }
        assertTrue(
            rejected.isFailure,
            "写语句必须在**发出去之前**被拒 —— 预览是「点一下就发到数据库」的动作",
        )

        // ⚠️ 还要验「数据没被动过」—— 只看「返回了失败」是不够的：
        // 万一是**发出去之后**才失败（表不存在之类），那条断言照样绿，
        // 而用户的表已经被改过了 —— 那正是这个闸门要防的事。
        val after = runSql("SELECT label FROM nums")
        assertTrue(after.success, "查询必须成功：${after.error}")
        assertEquals(
            "第一行",
            after.analysis.runQuery.rowsList
                .first()
                .valuesMap["label"]
                ?.stringValue,
            "预览不能把数据改掉 —— 它只该看，不该写",
        )
    }

    @Test
    fun `预览能取回工作区里的前几行`() {
        runSql("CREATE TABLE nums (id INT, label VARCHAR)")
        runSql("INSERT INTO nums VALUES (1, '第一行'), (2, 'second')")

        val preview = runBlocking { session.previewExportSql("SELECT id, label FROM nums ORDER BY id") }
        assertTrue(preview.isSuccess, "预览必须成功：${preview.exceptionOrNull()?.message}")
        val rows = preview.getOrThrow()
        assertEquals(listOf("id", "label"), rows.columns, "列名应当是查询出来的两列")
        assertEquals(2, rows.rows.size, "两行都该在预览里")
        assertTrue(rows.rows[0].containsValue("第一行"), "预览里要有那行数据：${rows.rows}")
    }
}
