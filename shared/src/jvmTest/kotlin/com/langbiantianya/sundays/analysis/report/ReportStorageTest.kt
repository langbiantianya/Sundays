/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 报表的落盘 —— 往返、原子写，以及**读不出来时怎么办**。
 *
 * ## 这一轮最要紧的一条
 *
 * **读不出来时绝不静默清空。**
 *
 * 静默当成空集（`ConnectionStorage` 现在的做法）会把「文件坏了」
 * 变成「数据没了」—— 而后者**不可逆**。所以这里钉住：
 * 原文件被**改名保留**，一个字节都不动。
 *
 * ## 为什么路径是**参数**而不是写死
 *
 * 写死的话，测试就会去动 `user.home` 那个**全局**状态 ——
 * 而全局状态在并行跑的测试里是会互相踩的
 * （`ConnectionStoragePermissionsTest` 的注释里记着这个坑）。
 */
class ReportStorageTest {
    private val dir: Path = Files.createTempDirectory("sundays-report-storage")

    private fun file(): Path = dir.resolve("reports.json")

    private fun storage(): ReportStorage = ReportStorage(file())

    private fun sampleReports() =
        listOf(
            AnalysisReport(
                id = "report-1",
                name = "销售月报",
                widgets =
                    listOf(
                        ReportWidget("widget-1", ReportWidgetType.BAR, "各月销售额", "SELECT 1 AS a, 2 AS b"),
                        ReportWidget("widget-2", ReportWidgetType.PIE, "大区占比", "SELECT 1 AS a, 2 AS b"),
                    ),
            ),
        )

    @Test
    fun `a missing file loads as an empty list`() {
        // 文件不存在**不是错误** —— 那是「第一次用」的正常状态。
        val result = storage().load()
        assertIs<ReportLoadResult.Loaded>(result)
        assertTrue(result.reports.isEmpty())
    }

    @Test
    fun `reports survive a round trip`() {
        val storage = storage()
        assertTrue(storage.save(sampleReports()))

        val result = storage.load()
        assertIs<ReportLoadResult.Loaded>(result)
        assertEquals(sampleReports(), result.reports)
    }

    @Test
    fun `the widget order survives a round trip`() {
        // 顺序即显示顺序 —— 落盘时按原样存下来，重开应用还是那个顺序。
        val storage = storage()
        storage.save(sampleReports())

        val result = storage.load()
        assertIs<ReportLoadResult.Loaded>(result)
        assertEquals(
            listOf("widget-1", "widget-2"),
            result.reports
                .first()
                .widgets
                .map { it.id },
        )
    }

    @Test
    fun `the chart type survives a round trip`() {
        val storage = storage()
        storage.save(sampleReports())

        val result = storage.load()
        assertIs<ReportLoadResult.Loaded>(result)
        assertEquals(
            listOf(ReportWidgetType.BAR, ReportWidgetType.PIE),
            result.reports
                .first()
                .widgets
                .map { it.type },
        )
    }

    @Test
    fun `a corrupt file is kept under a new name instead of being thrown away`() {
        // 静默清空 = 把「文件坏了」变成「数据没了」，而后者不可逆。
        // 改名保留：原文件一个字节都没动，用户还能自己打开看。
        val garbage = "这不是 JSON，只是一堆乱码"
        Files.writeString(file(), garbage)

        val result = storage().load()

        assertIs<ReportLoadResult.BackedUp>(result)
        assertTrue(result.reports.isEmpty(), "读不出来就不该凭空造出报表")
        assertFalse(Files.exists(file()), "原文件要让位 —— 不然下一次保存就把它盖了")
        assertTrue(Files.exists(result.backupPath), "原文件必须还在，只是改了名")
        assertEquals(garbage, Files.readString(result.backupPath), "改名不该动一个字节")
    }

    @Test
    fun `a file from a newer version is not read`() {
        // 版本是**将来的** —— 我们读出来的多半是**错的**，
        // 而「读错」比「读不到」更危险（用户会拿错数据当真的）。
        Files.writeString(file(), """{"version":99,"reports":[]}""")

        val result = storage().load()

        assertIs<ReportLoadResult.BackedUp>(result)
        assertTrue(Files.exists(result.backupPath))
    }

    @Test
    fun `no temp file is left behind after saving`() {
        // 原子写是「写临时文件 → 替换」—— 临时文件用完就该没了。
        // 留着它，下次保存会拿它当起点，而那是个**半截**文件。
        storage().save(sampleReports())

        assertFalse(Files.exists(dir.resolve("reports.json.tmp")))
    }

    @Test
    fun `the saved file is json with a version`() {
        storage().save(sampleReports())

        val content = Files.readString(file())
        assertTrue(content.contains("\"version\""), "文件里要带版本号，将来格式变了靠它分派")
        assertTrue(content.contains("销售月报"))
    }

    @Test
    fun `the storage knows which file it manages`() {
        // 界面上要把路径告诉用户（「你的报表文件在这儿」）。
        assertEquals(file(), storage().path)
    }

    @Test
    fun `the default file sits in the app config directory`() {
        val default = ReportStorage()
        assertEquals(
            Paths.get(System.getProperty("user.home"), ".config", "sundays", "reports.json"),
            default.path,
        )
    }
}
