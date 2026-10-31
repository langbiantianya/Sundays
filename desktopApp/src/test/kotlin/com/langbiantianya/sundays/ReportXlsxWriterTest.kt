/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 报表导出成 `.xlsx` —— 把文件**读回来**验，而不是只看「写成功了」。
 *
 * ## 为什么一定要读回来
 *
 * 「写成功」只说明 POI 没抛异常。而导出这件事的失败方式恰恰是
 * 「文件生成了，但**打开是空的 / 少一块 / 名字撞了**」——
 * 那种失败在写的时候**看不出来**，只有读回来才知道。
 *
 * ## 这一轮最要紧的两条
 *
 * 1. **图表落成表格**（类别 / 数值 / 占比）—— 收件人拿到的数字是**对的**；
 * 2. **数据画不出来时原样导出** —— 数据不丢。
 */
class ReportXlsxWriterTest {
    private val dir: Path = Files.createTempDirectory("sundays-report-xlsx")

    private fun target(name: String = "报表.xlsx"): Path = dir.resolve(name)

    private fun widget(
        id: String,
        type: ReportWidgetType,
        title: String,
        sql: String = "SELECT region, amount FROM orders",
    ) = ReportWidget(id = id, type = type, title = title, sql = sql)

    private fun report(widgets: List<ReportWidget>) = AnalysisReport(id = "report-1", name = "销售月报", widgets = widgets)

    /** 两列的结果（类别 + 数值）。 */
    private fun result(
        rows: List<List<String?>>,
        columnNames: List<String> = listOf("region", "amount"),
    ) = AnalysisResult(
        columns = columnNames.map { AnalysisColumn(name = it, type = "VARCHAR") },
        rows = rows,
    )

    /** 读回一个单元格 —— 见 [XlsxCells]：读单元格这件事只有一份实现。 */
    private fun cell(
        workbook: XSSFWorkbook,
        sheetIndex: Int,
        rowIndex: Int,
        columnIndex: Int,
    ): String = XlsxCells.text(workbook, sheetIndex, rowIndex, columnIndex)

    @Test
    fun `the workbook has a summary sheet plus one sheet per part`() {
        val file = target()
        assertTrue(
            ReportXlsxWriter.write(
                report(
                    listOf(
                        widget("w1", ReportWidgetType.TABLE, "明细"),
                        widget("w2", ReportWidgetType.BAR, "占比"),
                    ),
                ),
                emptyMap(),
                file,
                "销售月报",
            ),
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertEquals(3, workbook.numberOfSheets, "两个部件 + 一个抬头页")
            assertEquals("明细", workbook.getSheetAt(1).sheetName)
            assertEquals("占比", workbook.getSheetAt(2).sheetName)
        }
    }

    @Test
    fun `the summary sheet carries the sql of every part`() {
        // 收件人拿到一叠数字时，第一个问题是「这些数是怎么来的」。
        // 把每个部件的 SQL 摆在明面上，答案就在文件里。
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.TABLE, "明细", "SELECT 1 FROM t"))),
            emptyMap(),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertEquals("销售月报", cell(workbook, 0, 0, 1), "抬头页要写着报表名")
            assertTrue(cell(workbook, 0, 0, 0).isNotBlank(), "报表名那一列要有标签")
            val sqlColumn = (0..sheet.lastRowNum).firstOrNull { cell(workbook, 0, it, 2).contains("SELECT") }
            assertTrue(sqlColumn != null, "抬头页里必须能找到部件的 SQL")
        }
    }

    @Test
    fun `a table part is written out row by row`() {
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.TABLE, "明细"))),
            mapOf(
                "w1" to
                    result(
                        rows = listOf(listOf("华东", "128"), listOf("华北", "64")),
                        columnNames = listOf("region", "amount"),
                    ),
            ),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertEquals("region", cell(workbook, 1, 0, 0), "第一行是列名")
            assertEquals("amount", cell(workbook, 1, 0, 1))
            assertEquals("华东", cell(workbook, 1, 1, 0))
            assertEquals("128", cell(workbook, 1, 1, 1))
            assertEquals("华北", cell(workbook, 1, 2, 0))
        }
    }

    @Test
    fun `a table part keeps its cells as text`() {
        // 用户的数据不能被「顺手」转成数字 —— `007` 变成 `7`、
        // 长 ID 变成科学计数法，那叫篡改。
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.TABLE, "明细"))),
            mapOf("w1" to result(rows = listOf(listOf("007", "12345678901234567890")))),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertEquals("007", cell(workbook, 1, 1, 0), "编号的前导零不能丢")
        }
    }

    @Test
    fun `a chart part gets a share column`() {
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.BAR, "占比"))),
            mapOf("w1" to result(rows = listOf(listOf("华东", "75"), listOf("华北", "25")))),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertEquals("region", cell(workbook, 1, 0, 0), "前两列沿用 SQL 自己的列名")
            assertEquals("amount", cell(workbook, 1, 0, 1))
            assertTrue(cell(workbook, 1, 0, 2).isNotBlank(), "第三列是占比")
            assertEquals("华东", cell(workbook, 1, 1, 0))
            assertEquals("75.0", cell(workbook, 1, 1, 1))
        }
    }

    @Test
    fun `the shares add up to one hundred percent`() {
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.PIE, "占比"))),
            mapOf("w1" to result(rows = listOf(listOf("华东", "75"), listOf("华北", "25")))),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(1)
            val first = sheet.getRow(1).getCell(2).numericCellValue
            val second = sheet.getRow(2).getCell(2).numericCellValue
            assertEquals(1.0, first + second, 1e-9, "占比加起来必须是 100%")
            assertEquals(0.75, first, 1e-9)
        }
    }

    @Test
    fun `a chart part whose data cannot be drawn is written out raw`() {
        // 画不出来只说明这个形状画不成图，不说明数据没用 ——
        // 丢掉那些数才是真正的损失。
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.BAR, "有负值"))),
            mapOf("w1" to result(rows = listOf(listOf("退款", "-50")))),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertTrue(cell(workbook, 1, 0, 0).isNotBlank(), "第一行要说明「这是原样导出」")
            assertEquals("region", cell(workbook, 1, 1, 0), "接着是原来那张表的列名")
            assertEquals("退款", cell(workbook, 1, 2, 0), "数据不能丢")
            assertEquals("-50", cell(workbook, 1, 2, 1))
        }
    }

    @Test
    fun `a failed query is written as the error`() {
        val file = target()
        ReportXlsxWriter.write(
            report(listOf(widget("w1", ReportWidgetType.TABLE, "跑不通"))),
            mapOf("w1" to AnalysisResult(error = "语法错误")),
            file,
            "销售月报",
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertTrue(cell(workbook, 1, 0, 0).contains("语法错误"), "失败的原因要写在文件里")
        }
    }

    @Test
    fun `two parts with the same title get different sheet names`() {
        // Excel 不允许两个 Sheet 同名 —— 撞名的后果是**整个导出失败**。
        val file = target()
        assertTrue(
            ReportXlsxWriter.write(
                report(
                    listOf(
                        widget("w1", ReportWidgetType.TABLE, "明细"),
                        widget("w2", ReportWidgetType.TABLE, "明细"),
                    ),
                ),
                emptyMap(),
                file,
                "销售月报",
            ),
        )

        XSSFWorkbook(file.toFile()).use { workbook ->
            assertEquals(3, workbook.numberOfSheets)
            assertTrue(workbook.getSheetAt(1).sheetName != workbook.getSheetAt(2).sheetName)
        }
    }

    @Test
    fun `a sheet name is trimmed to what excel accepts`() {
        val long = "很长的标题".repeat(20)
        val cleaned = ReportXlsxWriter.sanitizeSheetName(long)
        assertTrue(cleaned.length <= ReportXlsxWriter.MAX_SHEET_NAME, "Sheet 名不能超过 31 个字符")

        assertFalse(ReportXlsxWriter.sanitizeSheetName("a/b\\c:d*e?f[g]h").any { it in "\\/:*?[]" })
        assertEquals("Sheet", ReportXlsxWriter.sanitizeSheetName("   "), "洗干净之后什么都不剩就用兜底名")
    }

    @Test
    fun `a file name with illegal characters is cleaned up`() {
        val cleaned = ReportXlsxWriter.sanitizeFileName("销售/报表:2026?")
        assertFalse(cleaned.any { it in "\\/:*?\"<>|" }, "文件名里有非法字符：$cleaned")
        assertEquals("report", ReportXlsxWriter.sanitizeFileName("   "), "什么都不剩就用兜底名")
    }
}
