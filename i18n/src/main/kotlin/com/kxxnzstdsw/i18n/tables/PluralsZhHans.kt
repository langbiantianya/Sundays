package com.kxxnzstdsw.i18n.tables

import com.kxxnzstdsw.i18n.PluralForm
import com.kxxnzstdsw.i18n.PluralStrKey

/** 简体中文复数文案表 —— 中文没有词形变化，只需要 OTHER 一档。 */
internal val pluralsZhHans: Map<PluralStrKey, Map<PluralForm, String>> = mapOf(
    PluralStrKey.ROWS_COUNT to mapOf(
        PluralForm.OTHER to "共 {0} 行",
    ),
    PluralStrKey.ROWS_TRUNCATED to mapOf(
        PluralForm.OTHER to "（已截断到 {0} 行）",
    ),
    PluralStrKey.TOTAL_RECORDS to mapOf(
        PluralForm.OTHER to "共 {0} 条",
    ),
    PluralStrKey.NOTIFICATIONS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 条",
    ),
    PluralStrKey.NOTIFICATIONS_FAILED_COUNT to mapOf(
        PluralForm.OTHER to " · {0} 条失败",
    ),
    PluralStrKey.PAGINATION_SUMMARY to mapOf(
        PluralForm.OTHER to "共 {0} 行 · 第 {1} 页 · 每页 {2}",
    ),
    PluralStrKey.BACKGROUND_TASKS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0} 个进行中 / 共 {1} 个",
    ),
    PluralStrKey.DANGEROUS_SUMMARY to mapOf(
        PluralForm.OTHER to "接下来的执行包含 {0} 条不可撤销的写操作：",
    ),
    PluralStrKey.EXPORT_STATEMENTS_TO_FILES to mapOf(
        PluralForm.OTHER to "{0} 条语句将导出为 {1} 个文件",
    ),
    PluralStrKey.EXPORTED_ROWS_PROGRESS to mapOf(
        PluralForm.OTHER to "已导出 {0} 行…",
    ),
    PluralStrKey.EXPORT_PROGRESS_ROWS to mapOf(
        PluralForm.OTHER to "正在导出 · 已写出 {0} 行",
    ),
    PluralStrKey.EXPORTED_ROWS_TO_FILE to mapOf(
        PluralForm.OTHER to "已导出 {0} 行 → {1}",
    ),
    PluralStrKey.EXPORT_FAILED_STATEMENTS to mapOf(
        PluralForm.OTHER to "导出失败 {0}/{1} 条：",
    ),
    PluralStrKey.EXPORTED_FILES_COUNT to mapOf(
        PluralForm.OTHER to "已导出 {0} 个文件",
    ),
    PluralStrKey.STATEMENTS_TOTAL to mapOf(
        PluralForm.OTHER to "（共 {0} 条语句）",
    ),
    PluralStrKey.STATEMENTS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0}/{1} 条语句",
    ),
    PluralStrKey.AFFECTED_ROWS to mapOf(
        PluralForm.OTHER to "已影响 {0} 行（非 SELECT 语句）",
    ),
    PluralStrKey.QUERY_RESULT_ROWS to mapOf(
        PluralForm.OTHER to "查询结果 · {0} 行",
    ),
    PluralStrKey.GENERATING_INSERTED to mapOf(
        PluralForm.OTHER to "造数中… 已插入 {0} 行",
    ),
    PluralStrKey.GENERATE_COMPLETE_SUMMARY to mapOf(
        PluralForm.OTHER to "造数完成 · 共 {0} 行 · 处理 {1} 个脚本",
    ),
    PluralStrKey.EXPORT_COMPLETE_FILES to mapOf(
        PluralForm.OTHER to "导出完成 · {0} 个文件",
    ),
    PluralStrKey.FILES_WRITTEN to mapOf(
        PluralForm.OTHER to "{0} 个文件已写出：",
    ),
    PluralStrKey.EXPORT_FAILED_ALL_FILES to mapOf(
        PluralForm.OTHER to "导出失败 · 0/{0} 个文件",
    ),
    PluralStrKey.EXPORT_PARTIAL_FILES to mapOf(
        PluralForm.OTHER to "导出部分失败 · {0}/{1} 个文件",
    ),
    PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to mapOf(
        PluralForm.OTHER to "成功 {0} 个，失败 {1} 个：",
    ),
    PluralStrKey.AVAILABLE_PROCESSORS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 个",
    ),
    PluralStrKey.DDL_WITH_LENGTH to mapOf(
        PluralForm.OTHER to "{0}（{1} 字符）",
    ),
    PluralStrKey.TRUNCATED_MAX_ROWS to mapOf(
        PluralForm.OTHER to "已截断：只保留前 {0} 行",
    ),
    PluralStrKey.UPTIME_DAYS to mapOf(
        PluralForm.OTHER to "{0} 天",
    ),
    PluralStrKey.UPTIME_HOURS to mapOf(
        PluralForm.OTHER to "{0} 小时",
    ),
    PluralStrKey.UPTIME_MINUTES to mapOf(
        PluralForm.OTHER to "{0} 分",
    ),
)
