/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.export

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * CSV 导出写入器
 * - 输出 UTF-8 BOM 头，保证 Excel 打开中文不乱码
 * - 字段包含逗号、双引号、换行符时自动转义
 */
class CsvWriter(
    private val outputFile: File,
) : ExportWriter {
    private var writer: BufferedWriter? = null
    private var exportedRows = 0L

    override fun writeHeader(columns: List<String>) {
        writer =
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(outputFile), StandardCharsets.UTF_8),
            )
        // 写入 UTF-8 BOM
        //
        // ⚠️ 这里必须写成 \uFEFF 转义，不能把 U+FEFF 这个字符本身打进来。
        // ktlintFormat 会对源码做整体规范化，实测它会把这个不可见的
        // BOM 字符从字符串字面量里静默删掉，结果是 write 变成了空字符串——
        // 而代码、注释、KDoc 全部原样保留，编译照过、测试照过，
        // 只有真正用 Excel 打开导出的 CSV 时才会发现中文全是乱码。
        // \uFEFF 这个写法对 ktlint 来说就是普通的 ASCII，不会被它碰。
        writer?.write("\uFEFF")
        writer?.write(columns.joinToString(",") { escapeField(it) })
        writer?.newLine()
    }

    override fun writeRow(row: List<Any?>) {
        val line = row.joinToString(",") { escapeField(it?.toString() ?: "") }
        writer?.write(line)
        writer?.newLine()
        exportedRows++
    }

    override fun flush() {
        writer?.flush()
    }

    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    override fun getExportedRows(): Long = exportedRows

    /**
     * 转义 CSV 字段
     * - 包含逗号、双引号、换行符的字段用双引号包裹
     * - 字段内双引号用两个双引号转义
     */
    private fun escapeField(field: String): String =
        when {
            field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r') -> {
                "\"${field.replace("\"", "\"\"")}\""
            }

            else -> {
                field
            }
        }
}
