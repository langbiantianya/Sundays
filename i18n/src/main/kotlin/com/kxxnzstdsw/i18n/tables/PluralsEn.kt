package com.kxxnzstdsw.i18n.tables

import com.kxxnzstdsw.i18n.PluralForm
import com.kxxnzstdsw.i18n.PluralStrKey

/** English plurals — English needs ONE (exactly 1) and OTHER. */
internal val pluralsEn: Map<PluralStrKey, Map<PluralForm, String>> = mapOf(
    PluralStrKey.ROWS_COUNT to mapOf(
        PluralForm.ONE to "{0} row",
        PluralForm.OTHER to "{0} rows",
    ),
    PluralStrKey.ROWS_TRUNCATED to mapOf(
        PluralForm.ONE to "(truncated to {0} row)",
        PluralForm.OTHER to "(truncated to {0} rows)",
    ),
    PluralStrKey.TOTAL_RECORDS to mapOf(
        PluralForm.ONE to "{0} record",
        PluralForm.OTHER to "{0} records",
    ),
    PluralStrKey.NOTIFICATIONS_COUNT to mapOf(
        PluralForm.ONE to "{0} notification",
        PluralForm.OTHER to "{0} notifications",
    ),
    PluralStrKey.NOTIFICATIONS_FAILED_COUNT to mapOf(
        PluralForm.ONE to " · {0} failed",
        PluralForm.OTHER to " · {0} failed",
    ),
    PluralStrKey.PAGINATION_SUMMARY to mapOf(
        PluralForm.ONE to "{0} row total · Page {1} · {2} per page",
        PluralForm.OTHER to "{0} rows total · Page {1} · {2} per page",
    ),
    PluralStrKey.BACKGROUND_TASKS_PROGRESS to mapOf(
        PluralForm.ONE to "{0} running / {1} total",
        PluralForm.OTHER to "{0} running / {1} total",
    ),
    PluralStrKey.DANGEROUS_SUMMARY to mapOf(
        PluralForm.ONE to "The next run includes {0} irreversible write operation:",
        PluralForm.OTHER to "The next run includes {0} irreversible write operations:",
    ),
    PluralStrKey.EXPORT_STATEMENTS_TO_FILES to mapOf(
        PluralForm.ONE to "{0} statement will be exported to {1} file",
        PluralForm.OTHER to "{0} statements will be exported to {1} files",
    ),
    PluralStrKey.EXPORTED_ROWS_PROGRESS to mapOf(
        PluralForm.ONE to "Exported {0} row…",
        PluralForm.OTHER to "Exported {0} rows…",
    ),
    PluralStrKey.EXPORT_PROGRESS_ROWS to mapOf(
        PluralForm.ONE to "Exporting · {0} row written",
        PluralForm.OTHER to "Exporting · {0} rows written",
    ),
    PluralStrKey.EXPORTED_ROWS_TO_FILE to mapOf(
        PluralForm.ONE to "Exported {0} row → {1}",
        PluralForm.OTHER to "Exported {0} rows → {1}",
    ),
    PluralStrKey.EXPORT_FAILED_STATEMENTS to mapOf(
        PluralForm.ONE to "{0}/{1} statement failed to export:",
        PluralForm.OTHER to "{0}/{1} statements failed to export:",
    ),
    PluralStrKey.EXPORTED_FILES_COUNT to mapOf(
        PluralForm.ONE to "Exported {0} file",
        PluralForm.OTHER to "Exported {0} files",
    ),
    PluralStrKey.STATEMENTS_TOTAL to mapOf(
        PluralForm.ONE to "({0} statement)",
        PluralForm.OTHER to "({0} statements)",
    ),
    PluralStrKey.STATEMENTS_PROGRESS to mapOf(
        PluralForm.ONE to "{0}/{1} statement",
        PluralForm.OTHER to "{0}/{1} statements",
    ),
    PluralStrKey.AFFECTED_ROWS to mapOf(
        PluralForm.ONE to "{0} row affected (non-SELECT statement)",
        PluralForm.OTHER to "{0} rows affected (non-SELECT statement)",
    ),
    PluralStrKey.QUERY_RESULT_ROWS to mapOf(
        PluralForm.ONE to "Query result · {0} row",
        PluralForm.OTHER to "Query result · {0} rows",
    ),
    PluralStrKey.GENERATING_INSERTED to mapOf(
        PluralForm.ONE to "Generating data… {0} row inserted",
        PluralForm.OTHER to "Generating data… {0} rows inserted",
    ),
    PluralStrKey.GENERATE_COMPLETE_SUMMARY to mapOf(
        PluralForm.ONE to "Data generation complete · {0} row · {1} script",
        PluralForm.OTHER to "Data generation complete · {0} rows · {1} scripts",
    ),
    PluralStrKey.EXPORT_COMPLETE_FILES to mapOf(
        PluralForm.ONE to "Export complete · {0} file",
        PluralForm.OTHER to "Export complete · {0} files",
    ),
    PluralStrKey.FILES_WRITTEN to mapOf(
        PluralForm.ONE to "{0} file written:",
        PluralForm.OTHER to "{0} files written:",
    ),
    PluralStrKey.EXPORT_FAILED_ALL_FILES to mapOf(
        PluralForm.ONE to "Export failed · 0/{0} file",
        PluralForm.OTHER to "Export failed · 0/{0} files",
    ),
    PluralStrKey.EXPORT_PARTIAL_FILES to mapOf(
        PluralForm.ONE to "Export partially failed · {0}/{1} file",
        PluralForm.OTHER to "Export partially failed · {0}/{1} files",
    ),
    PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to mapOf(
        PluralForm.ONE to "{0} succeeded, {1} failed:",
        PluralForm.OTHER to "{0} succeeded, {1} failed:",
    ),
    PluralStrKey.AVAILABLE_PROCESSORS_COUNT to mapOf(
        PluralForm.ONE to "{0} processor",
        PluralForm.OTHER to "{0} processors",
    ),
    PluralStrKey.DDL_WITH_LENGTH to mapOf(
        PluralForm.ONE to "{0} ({1} character)",
        PluralForm.OTHER to "{0} ({1} characters)",
    ),
    PluralStrKey.TRUNCATED_MAX_ROWS to mapOf(
        PluralForm.ONE to "Truncated: only the first {0} row is kept",
        PluralForm.OTHER to "Truncated: only the first {0} rows are kept",
    ),
    PluralStrKey.UPTIME_DAYS to mapOf(
        PluralForm.ONE to "{0} day",
        PluralForm.OTHER to "{0} days",
    ),
    PluralStrKey.UPTIME_HOURS to mapOf(
        PluralForm.ONE to "{0} hour",
        PluralForm.OTHER to "{0} hours",
    ),
    PluralStrKey.UPTIME_MINUTES to mapOf(
        PluralForm.ONE to "{0} minute",
        PluralForm.OTHER to "{0} minutes",
    ),
)
