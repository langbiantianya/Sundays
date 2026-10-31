/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ChartData
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetType
import com.langbiantianya.sundays.analysis.report.chartDataOf
import com.langbiantianya.sundays.analysis.report.reportWidgetTypeText
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * 报表 → `.xlsx`。
 *
 * ## 为什么在 **desktopApp** 这一侧写，而不是走引擎
 *
 * 三条理由：
 *
 * 1. **数据已经在这儿了** —— 部件数据就在前端的 `widgetResults` 里。
 *    走引擎就得把「部件清单 + 每块的数据」整个传过去，再让引擎写文件 ——
 *    为了同一个结果，多出一条协议、一份 proto、一个路由。
 * 2. **导出的必须是「屏幕上的那份」** —— 走引擎意味着**重跑一遍 SQL**，
 *    而重跑的结果可能与屏幕上看到的不一样（数据变了）。
 *    发出去的文件与眼前对不上，比慢一点糟糕得多。
 * 3. **POI 已经在打包运行时里了** —— `dialect-duckdb` 带着 POI
 *    （Excel 预转换要用它），所以这里直接用它**不新增任何运行时依赖**，
 *    jlink 的模块清单也不用动。
 *
 * ## 为什么图表落成**表格**
 *
 * POI 的 XDDF 能画真图表，但那份代码要伺候 Excel / WPS / LibreOffice
 * 三方的差异，而「表格 + 占比」在哪儿都打得开。
 *
 * 仓库的原则是「宁可少给，不给一个点了必然失败的东西」——
 * 一个发出去打不开的 xlsx，比一个只有数字的 xlsx 糟得多。
 *
 * ## 数字为什么**一半写数字、一半写文本**
 *
 * - **图表部件的数值列**写成**真数字** —— 那是聚合结果，本来就是数值，
 *   收件人能直接求和、能画图。
 * - **表格部件的单元格**原样写成**文本** —— 那一列可能是编号
 *   （`007` 写成数字就变成 `7`）、可能是长 ID（写成数字会变科学计数法）。
 *   把用户的数据「顺手」转成数字，是**篡改**。
 */

/**
 * 把一张报表写成 `.xlsx`。
 *
 * @param report 要导出的报表
 * @param results 部件 id → 该部件的数据（没跑过的部件没有数据）
 * @param target 目标文件
 * @param displayName 报表的显示名（没起名字时用「报表 N」兜底）
 * @return 写成功了吗
 */
object ReportXlsxWriter {
    /** Excel 的 Sheet 名上限 —— 超过它 Excel 会拒绝打开。 */
    const val MAX_SHEET_NAME: Int = 31

    /** Excel 单元格最多 32767 个字符 —— 截断到 32000，留一点余量。 */
    const val MAX_CELL_CHARS: Int = 32000

    /** Sheet 名兜底 —— 用户没起名字、标题又被清干净时的最后一道。 */
    const val DEFAULT_SHEET_NAME: String = "Sheet"

    /** 文件名里不能有的字符（Windows 的规矩）。 */
    private val ILLEGAL_FILE_CHARS = Regex("""[\\/:*?"<>|]""")

    /** Excel 的 Sheet 名里不能有的字符。 */
    private val ILLEGAL_SHEET_CHARS = Regex("""[\\/:*?\[\]]""")

    /**
     * 写出一份 `.xlsx`。
     *
     * @param report 要导出的报表
     * @param results 部件 id → 该部件的数据（没跑过的部件没有数据）
     * @param target 目标文件
     * @param displayName 报表的显示名（没起名字时用「报表 N」兜底）
     * @return 写成功了吗
     */
    fun write(
        report: AnalysisReport,
        results: Map<String, AnalysisResult>,
        target: Path,
        displayName: String,
    ): Boolean =
        try {
            target.parent?.let { dir -> Files.createDirectories(dir) }
            XSSFWorkbook().use { workbook ->
                writeSummarySheet(workbook, report, displayName)
                val used = mutableSetOf<String>()
                report.widgets.forEachIndexed { index, widget ->
                    val sheetName = uniqueSheetName(sheetNameOf(widget, index), used)
                    writeWidgetSheet(workbook, sheetName, widget, results[widget.id])
                }
                Files.newOutputStream(target).use { out -> workbook.write(out) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

    /**
     * 第一个 Sheet —— **报表本身**：名字、导出时间，以及每个部件的 SQL。
     *
     * ## 为什么要有这一页
     *
     * 收件人拿到一叠数字时，第一个问题是「这些数是怎么来的」。
     * 把**每个部件的 SQL** 摆在明面上，答案就在文件里 ——
     * 而报表的**可信度**正是由这个建立的（见主设计 §8.1 的铁律：
     * 报表里每个数字都要能追到一条用户看得见的 SQL）。
     */
    private fun writeSummarySheet(
        workbook: XSSFWorkbook,
        report: AnalysisReport,
        displayName: String,
    ) {
        val sheet = workbook.createSheet(sanitizeSheetName(Msg.text(StrKey.REPORT_EXPORT_SHEET_REPORT), DEFAULT_SHEET_NAME))
        var rowIndex = 0
        writeRow(sheet, rowIndex++, listOf(Msg.text(StrKey.REPORT_EXPORT_LABEL_NAME), displayName))
        writeRow(sheet, rowIndex++, listOf(Msg.text(StrKey.REPORT_EXPORT_LABEL_TIME), exportTimestamp()))
        rowIndex++ // 空一行，把「抬头」与「清单」隔开
        writeRow(
            sheet,
            rowIndex++,
            listOf(
                Msg.text(StrKey.REPORT_EXPORT_LABEL_PART),
                Msg.text(StrKey.REPORT_EXPORT_LABEL_TYPE),
                "SQL",
            ),
        )
        report.widgets.forEachIndexed { index, widget ->
            writeRow(
                sheet,
                rowIndex++,
                listOf(
                    widget.title.ifBlank { Msg.format(StrKey.REPORT_WIDGET_FALLBACK_TITLE, index + 1) },
                    Msg.text(reportWidgetTypeText(widget.type)),
                    widget.sql,
                ),
            )
        }
        sheet.setColumnWidth(0, 24 * 256)
        sheet.setColumnWidth(1, 14 * 256)
        sheet.setColumnWidth(2, 80 * 256)
    }

    /**
     * 一个部件一个 Sheet。
     *
     * - **表格部件** —— SQL 的列名 + 数据行，原样铺开；
     * - **饼图 / 条形图部件** —— 「类别 | 数值 | 占比%」三列；
     * - **数据画不出来时** —— 原样导出 SQL 的结果，另加一行说明。
     */
    private fun writeWidgetSheet(
        workbook: XSSFWorkbook,
        sheetName: String,
        widget: ReportWidget,
        result: AnalysisResult?,
    ) {
        val sheet = workbook.createSheet(sheetName)
        if (result == null) {
            writeRow(sheet, 0, listOf(Msg.text(StrKey.REPORT_WIDGET_NO_DATA)))
            return
        }
        if (result.failed) {
            writeRow(sheet, 0, listOf("${Msg.text(StrKey.REPORT_RESULT_FAILED)}: ${result.error.orEmpty()}"))
            return
        }
        when (widget.type) {
            ReportWidgetType.TABLE -> {
                writeTableSheet(sheet, result, startRow = 0)
            }

            ReportWidgetType.PIE,
            ReportWidgetType.BAR,
            -> {
                writeChartSheet(workbook, sheet, result)
            }
        }
    }

    /** 表格部件 —— 列名 + 数据行，一个字都不改。 */
    private fun writeTableSheet(
        sheet: Sheet,
        result: AnalysisResult,
        startRow: Int,
    ) {
        writeRow(sheet, startRow, result.columns.map { it.name })
        result.rows.forEachIndexed { index, row -> writeRow(sheet, startRow + 1 + index, row) }
        sheet.setColumnWidth(0, 24 * 256)
        for (column in 1 until result.columns.size) {
            sheet.setColumnWidth(column, 16 * 256)
        }
    }

    /**
     * 图表部件 —— 「类别 | 数值 | 占比%」。
     *
     * ## 为什么数据不合法时**原样导出**
     *
     * 画不出来只说明**这个形状画不成图**（缺列 / 值不是数字 / 有负值），
     * 不说明数据没用。用户拿到的仍然是**那些数** —— 而丢掉它们才是真正的损失。
     */
    private fun writeChartSheet(
        workbook: XSSFWorkbook,
        sheet: Sheet,
        result: AnalysisResult,
    ) {
        when (val chart = chartDataOf(result)) {
            is ChartData.Invalid -> {
                writeRow(sheet, 0, listOf(Msg.text(StrKey.REPORT_EXPORT_UNCHARTABLE)))
                writeTableSheet(sheet, result, startRow = 1)
            }

            is ChartData.Ready -> {
                val total = chart.slices.sumOf { it.value }
                writeRow(
                    sheet,
                    0,
                    listOf(
                        result.columns
                            .getOrNull(0)
                            ?.name
                            .orEmpty(),
                        result.columns
                            .getOrNull(1)
                            ?.name
                            .orEmpty(),
                        Msg.text(StrKey.REPORT_EXPORT_LABEL_SHARE),
                    ),
                )
                val percentStyle = workbook.createCellStyle()
                percentStyle.dataFormat = workbook.createDataFormat().getFormat("0.0%")
                chart.slices.forEachIndexed { index, slice ->
                    val row = sheet.createRow(index + 1)
                    row.createCell(0).setCellValue(slice.label)
                    row.createCell(1).setCellValue(slice.value)
                    val share = row.createCell(2)
                    // 占比写**数字**（0~1），靠单元格格式显示成百分数 ——
                    // 这样收件人既能看懂，也能拿它继续算。
                    share.setCellValue(if (total > 0.0) slice.value / total else 0.0)
                    share.cellStyle = percentStyle
                }
                sheet.setColumnWidth(0, 24 * 256)
                sheet.setColumnWidth(1, 16 * 256)
                sheet.setColumnWidth(2, 12 * 256)
            }
        }
    }

    /** 写一行 —— 值全是文本（数字在需要的地方单独写成真数字）。 */
    private fun writeRow(
        sheet: Sheet,
        rowIndex: Int,
        values: List<String?>,
    ) {
        val row: Row = sheet.createRow(rowIndex)
        values.forEachIndexed { columnIndex, value ->
            row.createCell(columnIndex).setCellValue((value ?: "").take(MAX_CELL_CHARS))
        }
    }

    /** 部件的 Sheet 名 —— 用它的标题，没标题就用「部件 N」。 */
    private fun sheetNameOf(
        widget: ReportWidget,
        index: Int,
    ): String = widget.title.ifBlank { Msg.format(StrKey.REPORT_WIDGET_FALLBACK_TITLE, index + 1) }

    /**
     * 一个**没被用过**的 Sheet 名。
     *
     * ## 为什么要有这一步
     *
     * Excel 不允许两个 Sheet 同名，而「部件 1」与「部件 1」是完全可能的
     * （用户复制了一个部件，标题没改）。撞名的后果不是「名字不好看」，
     * 而是 POI 直接抛异常、**整个导出失败**。
     */
    private fun uniqueSheetName(
        base: String,
        used: MutableSet<String>,
    ): String {
        val clean = sanitizeSheetName(base, DEFAULT_SHEET_NAME)
        if (used.add(clean)) return clean
        var counter = 2
        while (true) {
            val suffix = " ($counter)"
            val candidate = clean.take(MAX_SHEET_NAME - suffix.length) + suffix
            if (used.add(candidate)) return candidate
            counter++
        }
    }

    /**
     * Excel 的 Sheet 名有规矩：**不超过 31 个字符**，且不能含 `\ / ? * [ ] :`，
     * 也不能以 `'` 开头或结尾。
     *
     * @param name 想要的 Sheet 名
     * @param fallback 清洗完什么都不剩时用这个
     * @return 合规的 Sheet 名
     */
    fun sanitizeSheetName(
        name: String,
        fallback: String = DEFAULT_SHEET_NAME,
    ): String {
        val cleaned = name.replace(ILLEGAL_SHEET_CHARS, "_").trim().trim('\'')
        if (cleaned.isEmpty()) return fallback.take(MAX_SHEET_NAME)
        return cleaned.take(MAX_SHEET_NAME)
    }

    /**
     * 文件名里不能有的字符（Windows 的规矩）：`\ / : * ? " < > |`，
     * 而且不能以 `.` 结尾（Windows 会当成没有扩展名）。
     *
     * @param name 想要的文件名（不含扩展名）
     * @return 合法的文件名
     */
    fun sanitizeFileName(name: String): String {
        val cleaned =
            name
                .replace(ILLEGAL_FILE_CHARS, "_")
                .trim()
                .trim('.')
                .trim()
        return cleaned.ifBlank { "report" }
    }

    /** 导出时间 —— 落在抬头页上，让收件人知道这是**什么时候**的数据。 */
    private fun exportTimestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
