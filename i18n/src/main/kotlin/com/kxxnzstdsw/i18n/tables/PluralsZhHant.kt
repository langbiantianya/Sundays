package com.kxxnzstdsw.i18n.tables

import com.kxxnzstdsw.i18n.PluralForm
import com.kxxnzstdsw.i18n.PluralStrKey

/** 繁体中文复数文案表 —— 中文没有词形变化，只需要 OTHER 一档。 */
internal val pluralsZhHant: Map<PluralStrKey, Map<PluralForm, String>> = mapOf(
    PluralStrKey.ROWS_COUNT to mapOf(
        PluralForm.OTHER to "共 {0} 列",
    ),
    PluralStrKey.ROWS_TRUNCATED to mapOf(
        PluralForm.OTHER to "（已截斷為 {0} 列）",
    ),
    PluralStrKey.TOTAL_RECORDS to mapOf(
        PluralForm.OTHER to "共 {0} 筆",
    ),
    PluralStrKey.NOTIFICATIONS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 筆",
    ),
    PluralStrKey.NOTIFICATIONS_FAILED_COUNT to mapOf(
        PluralForm.OTHER to " · 失敗 {0} 筆",
    ),
    PluralStrKey.PAGINATION_SUMMARY to mapOf(
        PluralForm.OTHER to "共 {0} 列 · 第 {1} 頁 · 每頁 {2}",
    ),
    PluralStrKey.BACKGROUND_TASKS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0} 個進行中 / 共 {1} 個",
    ),
    PluralStrKey.DANGEROUS_SUMMARY to mapOf(
        PluralForm.OTHER to "接下來的執行包含 {0} 筆不可復原的寫入操作：",
    ),
    PluralStrKey.EXPORT_STATEMENTS_TO_FILES to mapOf(
        PluralForm.OTHER to "{0} 筆語句將匯出為 {1} 個檔案",
    ),
    PluralStrKey.EXPORTED_ROWS_PROGRESS to mapOf(
        PluralForm.OTHER to "已匯出 {0} 列…",
    ),
    PluralStrKey.EXPORT_PROGRESS_ROWS to mapOf(
        PluralForm.OTHER to "正在匯出 · 已寫出 {0} 列",
    ),
    PluralStrKey.EXPORTED_ROWS_TO_FILE to mapOf(
        PluralForm.OTHER to "已匯出 {0} 列 → {1}",
    ),
    PluralStrKey.EXPORT_FAILED_STATEMENTS to mapOf(
        PluralForm.OTHER to "匯出失敗 {0}/{1} 筆：",
    ),
    PluralStrKey.EXPORTED_FILES_COUNT to mapOf(
        PluralForm.OTHER to "已匯出 {0} 個檔案",
    ),
    PluralStrKey.STATEMENTS_TOTAL to mapOf(
        PluralForm.OTHER to "（共 {0} 筆語句）",
    ),
    PluralStrKey.STATEMENTS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0}/{1} 筆語句",
    ),
    PluralStrKey.AFFECTED_ROWS to mapOf(
        PluralForm.OTHER to "已影響 {0} 列（非 SELECT 語句）",
    ),
    PluralStrKey.QUERY_RESULT_ROWS to mapOf(
        PluralForm.OTHER to "查詢結果 · {0} 列",
    ),
    PluralStrKey.GENERATING_INSERTED to mapOf(
        PluralForm.OTHER to "造數中… 已插入 {0} 列",
    ),
    PluralStrKey.GENERATE_COMPLETE_SUMMARY to mapOf(
        PluralForm.OTHER to "造數完成 · 共 {0} 列 · 處理 {1} 個指令碼",
    ),
    PluralStrKey.EXPORT_COMPLETE_FILES to mapOf(
        PluralForm.OTHER to "匯出完成 · {0} 個檔案",
    ),
    PluralStrKey.FILES_WRITTEN to mapOf(
        PluralForm.OTHER to "{0} 個檔案已寫出：",
    ),
    PluralStrKey.EXPORT_FAILED_ALL_FILES to mapOf(
        PluralForm.OTHER to "匯出失敗 · 0/{0} 個檔案",
    ),
    PluralStrKey.EXPORT_PARTIAL_FILES to mapOf(
        PluralForm.OTHER to "匯出部分失敗 · {0}/{1} 個檔案",
    ),
    PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to mapOf(
        PluralForm.OTHER to "成功 {0} 個，失敗 {1} 個：",
    ),
    PluralStrKey.AVAILABLE_PROCESSORS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 個",
    ),
    PluralStrKey.DDL_WITH_LENGTH to mapOf(
        PluralForm.OTHER to "{0}（{1} 個字元）",
    ),
    PluralStrKey.TRUNCATED_MAX_ROWS to mapOf(
        PluralForm.OTHER to "已截斷：只保留前 {0} 列",
    ),
    PluralStrKey.UPTIME_DAYS to mapOf(
        PluralForm.OTHER to "{0} 天",
    ),
    PluralStrKey.UPTIME_HOURS to mapOf(
        PluralForm.OTHER to "{0} 小時",
    ),
    PluralStrKey.UPTIME_MINUTES to mapOf(
        PluralForm.OTHER to "{0} 分",
    ),
)
