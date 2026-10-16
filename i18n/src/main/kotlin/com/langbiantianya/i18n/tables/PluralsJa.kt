package com.langbiantianya.i18n.tables

import com.langbiantianya.i18n.PluralForm
import com.langbiantianya.i18n.PluralStrKey

/** 日本語の複数形 — 日本語は語形変化しないので OTHER 一種のみ。 */
internal val pluralsJa: Map<PluralStrKey, Map<PluralForm, String>> = mapOf(
    PluralStrKey.ROWS_COUNT to mapOf(
        PluralForm.OTHER to "全 {0} 行",
    ),
    PluralStrKey.ROWS_TRUNCATED to mapOf(
        PluralForm.OTHER to "（{0} 行に切り詰めました）",
    ),
    PluralStrKey.TOTAL_RECORDS to mapOf(
        PluralForm.OTHER to "全 {0} 件",
    ),
    PluralStrKey.NOTIFICATIONS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 件",
    ),
    PluralStrKey.NOTIFICATIONS_FAILED_COUNT to mapOf(
        PluralForm.OTHER to " · {0} 件失敗",
    ),
    PluralStrKey.PAGINATION_SUMMARY to mapOf(
        PluralForm.OTHER to "全 {0} 行 · {1} ページ目 · 1 ページあたり {2} 件",
    ),
    PluralStrKey.BACKGROUND_TASKS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0} 件を実行中（全 {1} 件）",
    ),
    PluralStrKey.DANGEROUS_SUMMARY to mapOf(
        PluralForm.OTHER to "次の実行には、元に戻せない書き込み操作が {0} 件含まれます：",
    ),
    PluralStrKey.EXPORT_STATEMENTS_TO_FILES to mapOf(
        PluralForm.OTHER to "{0} 件のステートメントを {1} 個のファイルにエクスポートします",
    ),
    PluralStrKey.EXPORTED_ROWS_PROGRESS to mapOf(
        PluralForm.OTHER to "{0} 行をエクスポート済み…",
    ),
    PluralStrKey.EXPORT_PROGRESS_ROWS to mapOf(
        PluralForm.OTHER to "エクスポート中 · {0} 行を出力済み",
    ),
    PluralStrKey.EXPORTED_ROWS_TO_FILE to mapOf(
        PluralForm.OTHER to "{0} 行をエクスポートしました → {1}",
    ),
    PluralStrKey.EXPORT_FAILED_STATEMENTS to mapOf(
        PluralForm.OTHER to "エクスポート失敗 {0}/{1} 件：",
    ),
    PluralStrKey.EXPORTED_FILES_COUNT to mapOf(
        PluralForm.OTHER to "{0} 個のファイルをエクスポートしました",
    ),
    PluralStrKey.STATEMENTS_TOTAL to mapOf(
        PluralForm.OTHER to "（全 {0} 件のステートメント）",
    ),
    PluralStrKey.STATEMENTS_PROGRESS to mapOf(
        PluralForm.OTHER to "ステートメント {0}/{1}",
    ),
    PluralStrKey.AFFECTED_ROWS to mapOf(
        PluralForm.OTHER to "{0} 行に影響しました（SELECT 文以外）",
    ),
    PluralStrKey.QUERY_RESULT_ROWS to mapOf(
        PluralForm.OTHER to "クエリ結果 · {0} 行",
    ),
    PluralStrKey.GENERATING_INSERTED to mapOf(
        PluralForm.OTHER to "データ生成中… {0} 行を挿入しました",
    ),
    PluralStrKey.GENERATE_COMPLETE_SUMMARY to mapOf(
        PluralForm.OTHER to "データ生成完了 · 全 {0} 行 · {1} 個のスクリプトを処理",
    ),
    PluralStrKey.EXPORT_COMPLETE_FILES to mapOf(
        PluralForm.OTHER to "エクスポート完了 · {0} 個のファイル",
    ),
    PluralStrKey.FILES_WRITTEN to mapOf(
        PluralForm.OTHER to "{0} 個のファイルを出力しました：",
    ),
    PluralStrKey.EXPORT_FAILED_ALL_FILES to mapOf(
        PluralForm.OTHER to "エクスポート失敗 · 0/{0} 個のファイル",
    ),
    PluralStrKey.EXPORT_PARTIAL_FILES to mapOf(
        PluralForm.OTHER to "エクスポートは一部失敗 · {0}/{1} 個のファイル",
    ),
    PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to mapOf(
        PluralForm.OTHER to "成功 {0} 件、失敗 {1} 件：",
    ),
    PluralStrKey.AVAILABLE_PROCESSORS_COUNT to mapOf(
        PluralForm.OTHER to "{0} 個",
    ),
    PluralStrKey.DDL_WITH_LENGTH to mapOf(
        PluralForm.OTHER to "{0}（{1} 文字）",
    ),
    PluralStrKey.TRUNCATED_MAX_ROWS to mapOf(
        PluralForm.OTHER to "切り詰めました：先頭 {0} 行のみを保持します",
    ),
    PluralStrKey.UPTIME_DAYS to mapOf(
        PluralForm.OTHER to "{0} 日",
    ),
    PluralStrKey.UPTIME_HOURS to mapOf(
        PluralForm.OTHER to "{0} 時間",
    ),
    PluralStrKey.UPTIME_MINUTES to mapOf(
        PluralForm.OTHER to "{0} 分",
    ),
)
