/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

/**
 * 读回 `.xlsx` 里的单元格 —— **导出的失败方式恰恰是「文件生成了，但打开是空的 / 少一块 / 名字撞了」**。
 *
 * ## 为什么一定要读回来
 *
 * 「写成功」只说明 POI 没抛异常。而导出这件事的失败方式恰恰是
 * 「文件生成了，但**打开是空的 / 少一块 / 名字撞了**」——
 * 那种失败在写的时候**看不出来**，只有读回来才知道。
 *
 * ## 为什么放在**共享**文件里
 *
 * 「读单元格」这件事有两个调用方：验**写文件**的那个测试，
 * 与验**会话导出**的那个测试。两边各写一份的话，
 * 「同一件事怎么读」就会有两份实现 —— 改了一边忘了另一边，
 * 于是两个测试对同一个文件给出**不同的结论**，
 * 而没人说得清哪个是对的。
 */
object XlsxCells {
    /**
     * 读回**某一个**单元格的文字。
     *
     * @param workbook 已打开的工作簿
     * @param sheetIndex 第几个 Sheet（从 0 起）
     * @param rowIndex 第几行（从 0 起）
     * @param columnIndex 第几列（从 0 起）
     * @return 单元格的文字；位置不存在时是空串（不是抛异常 —— 测试里「读不到」通常就是**没有那格**）
     */
    fun text(
        workbook: XSSFWorkbook,
        sheetIndex: Int,
        rowIndex: Int,
        columnIndex: Int,
    ): String {
        val row = workbook.getSheetAt(sheetIndex).getRow(rowIndex) ?: return ""
        val cell = row.getCell(columnIndex) ?: return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toString()
            else -> cell.toString()
        }
    }

    /**
     * 读回**整个文件**里所有单元格的文字。
     *
     * 用来断言「文件里**真的有数据**」—— 只看文件存在与大小是不够的：
     * 一个只有抬头页、部件那页是空的 xlsx，体积照样不为零。
     *
     * @param file 要读的 `.xlsx`
     * @return 所有单元格的文字，按 Sheet → 行 → 列的顺序
     */
    fun allTexts(file: File): List<String> {
        val texts = mutableListOf<String>()
        XSSFWorkbook(file).use { workbook ->
            for (sheetIndex in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIndex)
                for (rowIndex in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    for (columnIndex in 0 until row.lastCellNum.toInt()) {
                        val cell = row.getCell(columnIndex) ?: continue
                        texts.add(
                            when (cell.cellType) {
                                CellType.STRING -> cell.stringCellValue
                                CellType.NUMERIC -> cell.numericCellValue.toString()
                                else -> cell.toString()
                            },
                        )
                    }
                }
            }
        }
        return texts
    }
}
