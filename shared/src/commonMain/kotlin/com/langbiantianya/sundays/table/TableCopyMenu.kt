/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.str

/**
 * 结果表右键菜单里的「复制此行」—— UI 测试标记。
 */
const val TABLE_COPY_ROW_MENU_TAG: String = "tableCopyRowMenu"

/**
 * 结果表右键菜单里的「复制整表」—— UI 测试标记。
 */
const val TABLE_COPY_ALL_ROWS_MENU_TAG: String = "tableCopyAllRowsMenu"

/**
 * 把整张表复制成**制表符分隔**（TSV）的纯文本 —— 表头 + 全部行。
 *
 * ## 为什么是 **TSV** 而不是 CSV
 *
 * 剪贴板不是文件，它要去的地方是**表格软件**（Excel / Google Sheets / 记事本）。
 *
 * - **CSV** 粘进 Excel 会弹「文本导入向导」—— 用户看到的是一个要他一步步点的对话框；
 * - **TSV** 直接按列落格，一粘就是一张表。
 *
 * 这也是所有数据库工具「复制到剪贴板」的默认做法。
 *
 * ## 为什么表头**在**、而单行复制**不带**表头
 *
 * - 「复制整表」要粘进 Excel 当一张表用 → **必须有表头**，否则用户不知道哪一列是什么；
 * - 「复制此行」多半是粘进聊天 / 邮件里 → 带一行表头的话，粘过去就变成「一行表头 + 一行数据」，
 *   读的人会以为那是两条记录。Excel 自己「复制一个单元格」也只给值，不给表头。
 *
 * ## 复制的是**原始值**，不是显示值
 *
 * [TableColumn.formatter] 是**显示层**的转换：它可能截断（`abc…`）、
 * 也可能把值换成符号（`✓`）。拿那种文本去粘进表格，用户拿到的是**假数据**。
 * 所以这里取的是**原始值的 `toString()`**。
 *
 * ⚠️ **SQL NULL 复制成空串**（不是 `NULL` 字样、也不是显示层那个斜体 `NULL`）：
 * 引擎侧的 CSV writer 也是这么写的（`it?.toString() ?: ""`）——
 * 粘进 Excel 之后那是个**空格**，而写成 `NULL` 会变成一个**内容是 "NULL" 的文本格**，
 * 与字符串 `'NULL'` 分不开。空串至少还像「没有值」。
 *
 * ## 转义规则与引擎侧 CSV writer **同一套**
 *
 * 含制表符 / 换行 / 双引号的值用 `"` 包起来，里面的 `"` 写成两个 `""`
 * （与 `engine` 的 `CsvWriter.escapeField` 一致）。不转义的话，
 * 一个带换行的值会被粘成**两行** —— 那就是**复制出来的数据是错的**，比不复制更糟。
 *
 * @param columns 列定义（表头取 [TableColumn.header]，取值取 [TableColumn.key]）
 * @param rows 全部行
 * @return TSV 纯文本（行间用 `\n`）
 */
fun tableCopyText(
    columns: List<TableColumn>,
    rows: List<TableRow>,
): String =
    buildString {
        append(tsvLine(columns.map { it.header }))
        for (row in rows) {
            append('\n')
            append(tsvLine(rowCellTexts(columns, row)))
        }
    }

/**
 * 把**一行**复制成 TSV —— **不含表头**（理由见 [tableCopyText]）。
 *
 * @param columns 列定义
 * @param row 目标行
 * @return TSV 纯文本（一行）
 */
fun rowCopyText(
    columns: List<TableColumn>,
    row: TableRow,
): String = tsvLine(rowCellTexts(columns, row))

/**
 * 一行 TSV —— 单元格之间用**制表符**隔开。
 */
private fun tsvLine(values: List<String>): String = values.joinToString("\t") { tsvEscape(it) }

/**
 * 某一行在各列上的**原始值**文本 —— `null` 变成空串（见 [tableCopyText] 的说明）。
 */
private fun rowCellTexts(
    columns: List<TableColumn>,
    row: TableRow,
): List<String> = columns.map { column -> row.cells[column.key]?.toString().orEmpty() }

/**
 * TSV 的转义 —— 含制表符 / 换行 / 双引号的值用 `"` 包起来，里面的 `"` 写成两个。
 *
 * 与引擎侧 CSV writer 的 `escapeField` **同一套规则**，只是分隔符从逗号换成制表符 ——
 * 同一份数据在「导出成文件」与「复制到剪贴板」两条路上不该有两套转义。
 */
private fun tsvEscape(value: String): String =
    if (value.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * 结果表右键菜单里的「复制」两项 —— **复制此行** 与 **复制整表**。
 *
 * ## 为什么住在 `:shared` 的 `table/` 而不是分析模式里
 *
 * 「把一张表复制成文本」是**一个概念**：分析模式的结果表要它，
 * 将来浏览模式的表预览 / SQL 工作台的结果表也要它。
 * 住在分析模式里的话，浏览模式只能用**第二份**实现 ——
 * 而两份实现迟早漂移（转义规则不一样、有的带表头有的不带），
 * 用户从两个模式复制同一张表却拿到**不一样的文本**，那比没有更糟。
 *
 * ## 为什么**剪贴板**是参数而不是直接调
 *
 * 剪贴板是**平台专属能力**（`java.awt`，见 `desktopApp` 的 `ClipboardWriter`）。
 * `:shared` 的 `commonMain` 里没有 `java.*` —— 所以这里只能把「写剪贴板」这件事
 * **回调**出去，由平台侧实现。
 *
 * ## 为什么「点了菜单项先关菜单」是这里的事
 *
 * 菜单**不会**因为「菜单项被点了」而自动关闭 —— 它只认 dismiss 路径
 * （点在菜单外 / 按 Esc）。不关的话，菜单会杵在表上不消失，
 * 下一次右键别的行时新菜单被压在旧菜单底下，表现为「右键没反应了」。
 *
 * 顺序也重要：**先关再干活** —— 复制整张 5000 行的表可能要几十毫秒，
 * 菜单杵在这段时间里既挡视线又挡下一次右键。
 *
 * @param columns 列定义
 * @param rows 全部行（「复制整表」用）
 * @param target 这次右键落在哪一行（`null` = 空白处，此时**不给**「复制此行」）
 * @param menu 菜单状态（用来关掉自己）
 * @param onCopy 写剪贴板；返回 `false` = 失败（剪贴板不可用 / 被占用）
 * @param onCopied 结果回调：复制了几行、成没成
 */
@Composable
fun TableCopyMenuItems(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    target: TableRow?,
    menu: ContextMenuState,
    onCopy: (String) -> Boolean,
    onCopied: (rowCount: Int, ok: Boolean) -> Unit,
) {
    if (target != null) {
        WinMenuItem(
            text = { Text(str(StrKey.COPY_ROW)) },
            onClick = {
                menu.dismiss()
                onCopied(1, onCopy(rowCopyText(columns, target)))
            },
            modifier = Modifier.testTag(TABLE_COPY_ROW_MENU_TAG),
        )
    }
    WinMenuItem(
        text = { Text(str(StrKey.COPY_ALL_ROWS)) },
        onClick = {
            menu.dismiss()
            onCopied(rows.size, onCopy(tableCopyText(columns, rows)))
        },
        modifier = Modifier.testTag(TABLE_COPY_ALL_ROWS_MENU_TAG),
    )
}
