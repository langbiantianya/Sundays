/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **共用**导出对话框的界面契约。
 *
 * ## 为什么这条测试存在
 *
 * 导出对话框是**浏览模式与分析模式共用**的通用件（分析模式的「导出查询结果」
 * 与浏览模式的「导出表预览」用的是同一个）。
 *
 * 正因为共用，它才有资格守住两条契约：
 *
 * 1. **五种格式全在** —— 不能因为某个模式只需要 CSV 就把别的格式藏起来。
 *    「不要只有 csv」是这一轮明确的要求。
 * 2. **它不认模式** —— 对话框只收「预览回调」，因此两个模式都能用同一个组件。
 *    这里用一个假的预览回调来驱动它 —— 不依赖任何模式的状态机。
 */
@OptIn(ExperimentalTestApi::class)
class ExportDialogSharedTest {
    private val previewRows =
        SqlPreviewRows(
            columns = listOf("id", "label"),
            columnTypes = listOf("INTEGER", "VARCHAR"),
            rows = listOf(mapOf("id" to "1", "label" to "第一行")),
            truncated = false,
        )

    @Test
    fun `五种导出格式全在对话框里`() {
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ExportDialog(
                            presetSql = "SELECT id, label FROM t",
                            presetTableName = "",
                            languageId = "sql-duckdb",
                            onPreview = { Result.success(previewRows) },
                            onConfirm = { _, _, _, _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            // ⚠️ 逐个断言，而不是「数一数有几个」——
            // 数数量的话，将来少了一个格式、数量对不上时才会红，
            // 而「CSV 被换成了别的」这种改动会静悄悄混过去。
            listOf("CSV", "JSON Lines", "SQL INSERT 语句", "Excel 工作簿", "Parquet 列存").forEach { label ->
                onNodeWithText(label).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `预览按钮把编辑器里的 SQL 交给回调`() {
        var previewed = ""
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ExportDialog(
                            presetSql = "SELECT id, label FROM t",
                            presetTableName = "",
                            languageId = "sql-duckdb",
                            onPreview = { sql ->
                                previewed = sql
                                Result.success(previewRows)
                            },
                            onConfirm = { _, _, _, _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            onNodeWithTag(EXPORT_PREVIEW_BTN_TAG).performClick()
        }

        assertEquals("SELECT id, label FROM t", previewed, "预览必须跑对话框里那段 SQL")
    }

    @Test
    fun `确认时把选中的格式与路径交回去`() {
        var confirmed: ExportFormat? = null
        var confirmedSql = ""
        var confirmedDir = ""
        var confirmedName = ""
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        ExportDialog(
                            presetSql = "SELECT id, label FROM t",
                            presetTableName = "",
                            languageId = "sql-duckdb",
                            onPreview = { Result.success(previewRows) },
                            onConfirm = { format, sql, dir, name, _ ->
                                confirmed = format
                                confirmedSql = sql
                                confirmedDir = dir
                                confirmedName = name
                            },
                            onDismiss = {},
                        )
                    }
                }
            }

            // 换成 Excel 再确认 —— 判据是「用户选的那个格式被交回去了」，
            // 而不是「默认值被交回去了」。
            onNodeWithTag("exportFormatRadio_EXCEL").performClick()
            onNodeWithTag(EXPORT_CONFIRM_BTN_TAG).performClick()
        }

        assertEquals(ExportFormat.EXCEL, confirmed, "确认时必须交回用户选的格式")
        assertEquals("SELECT id, label FROM t", confirmedSql, "确认时必须交回可编辑后的 SQL")
        assertTrue(confirmedDir.isNotBlank(), "确认时必须交回输出目录")
        assertEquals("export.xlsx", confirmedName, "换格式时文件名应当跟着换扩展名")
    }
}
