/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n.tables

import com.langbiantianya.i18n.PluralForm
import com.langbiantianya.i18n.PluralStrKey

/** Deutsche Pluralformen — ONE (genau 1) und OTHER. */
internal val pluralsDe: Map<PluralStrKey, Map<PluralForm, String>> = mapOf(
    PluralStrKey.ROWS_COUNT to mapOf(
        PluralForm.ONE to "{0} Zeile",
        PluralForm.OTHER to "{0} Zeilen",
    ),
    PluralStrKey.ROWS_TRUNCATED to mapOf(
        PluralForm.ONE to "(auf {0} Zeile gekürzt)",
        PluralForm.OTHER to "(auf {0} Zeilen gekürzt)",
    ),
    PluralStrKey.TOTAL_RECORDS to mapOf(
        PluralForm.ONE to "{0} Datensatz",
        PluralForm.OTHER to "{0} Datensätze",
    ),
    PluralStrKey.NOTIFICATIONS_COUNT to mapOf(
        PluralForm.ONE to "{0} Benachrichtigung",
        PluralForm.OTHER to "{0} Benachrichtigungen",
    ),
    PluralStrKey.NOTIFICATIONS_FAILED_COUNT to mapOf(
        PluralForm.ONE to " · {0} fehlgeschlagen",
        PluralForm.OTHER to " · {0} fehlgeschlagen",
    ),
    PluralStrKey.PAGINATION_SUMMARY to mapOf(
        PluralForm.ONE to "{0} Zeile insgesamt · Seite {1} · {2} pro Seite",
        PluralForm.OTHER to "{0} Zeilen insgesamt · Seite {1} · {2} pro Seite",
    ),
    PluralStrKey.BACKGROUND_TASKS_PROGRESS to mapOf(
        PluralForm.ONE to "{0} laufende Aufgabe / {1} gesamt",
        PluralForm.OTHER to "{0} laufende Aufgaben / {1} gesamt",
    ),
    PluralStrKey.DANGEROUS_SUMMARY to mapOf(
        PluralForm.ONE to "Die nächste Ausführung enthält {0} nicht rückgängig zu machende Schreiboperation:",
        PluralForm.OTHER to "Die nächste Ausführung enthält {0} nicht rückgängig zu machende Schreiboperationen:",
    ),
    PluralStrKey.EXPORT_STATEMENTS_TO_FILES to mapOf(
        PluralForm.ONE to "{0} Anweisung wird in {1} Datei exportiert",
        PluralForm.OTHER to "{0} Anweisungen werden in {1} Dateien exportiert",
    ),
    PluralStrKey.EXPORTED_ROWS_PROGRESS to mapOf(
        PluralForm.ONE to "{0} Zeile exportiert…",
        PluralForm.OTHER to "{0} Zeilen exportiert…",
    ),
    PluralStrKey.EXPORT_PROGRESS_ROWS to mapOf(
        PluralForm.ONE to "Export läuft · {0} Zeile geschrieben",
        PluralForm.OTHER to "Export läuft · {0} Zeilen geschrieben",
    ),
    PluralStrKey.EXPORTED_ROWS_TO_FILE to mapOf(
        PluralForm.ONE to "{0} Zeile exportiert → {1}",
        PluralForm.OTHER to "{0} Zeilen exportiert → {1}",
    ),
    PluralStrKey.EXPORT_FAILED_STATEMENTS to mapOf(
        PluralForm.ONE to "{0}/{1} Anweisung fehlgeschlagen:",
        PluralForm.OTHER to "{0}/{1} Anweisungen fehlgeschlagen:",
    ),
    PluralStrKey.EXPORTED_FILES_COUNT to mapOf(
        PluralForm.ONE to "{0} Datei exportiert",
        PluralForm.OTHER to "{0} Dateien exportiert",
    ),
    PluralStrKey.STATEMENTS_TOTAL to mapOf(
        PluralForm.ONE to "({0} Anweisung insgesamt)",
        PluralForm.OTHER to "({0} Anweisungen insgesamt)",
    ),
    PluralStrKey.STATEMENTS_PROGRESS to mapOf(
        PluralForm.ONE to "{0}/{1} Anweisung",
        PluralForm.OTHER to "{0}/{1} Anweisungen",
    ),
    PluralStrKey.AFFECTED_ROWS to mapOf(
        PluralForm.ONE to "{0} Zeile betroffen (keine SELECT-Anweisung)",
        PluralForm.OTHER to "{0} Zeilen betroffen (keine SELECT-Anweisung)",
    ),
    PluralStrKey.QUERY_RESULT_ROWS to mapOf(
        PluralForm.ONE to "Abfrageergebnis · {0} Zeile",
        PluralForm.OTHER to "Abfrageergebnis · {0} Zeilen",
    ),
    PluralStrKey.GENERATING_INSERTED to mapOf(
        PluralForm.ONE to "Datengenerierung… {0} Zeile eingefügt",
        PluralForm.OTHER to "Datengenerierung… {0} Zeilen eingefügt",
    ),
    PluralStrKey.GENERATE_COMPLETE_SUMMARY to mapOf(
        PluralForm.ONE to "Datengenerierung abgeschlossen · {0} Zeile insgesamt · {1} Skript verarbeitet",
        PluralForm.OTHER to "Datengenerierung abgeschlossen · {0} Zeilen insgesamt · {1} Skripte verarbeitet",
    ),
    PluralStrKey.EXPORT_COMPLETE_FILES to mapOf(
        PluralForm.ONE to "Export abgeschlossen · {0} Datei",
        PluralForm.OTHER to "Export abgeschlossen · {0} Dateien",
    ),
    PluralStrKey.FILES_WRITTEN to mapOf(
        PluralForm.ONE to "{0} Datei geschrieben:",
        PluralForm.OTHER to "{0} Dateien geschrieben:",
    ),
    PluralStrKey.EXPORT_FAILED_ALL_FILES to mapOf(
        PluralForm.ONE to "Export fehlgeschlagen · 0/{0} Datei",
        PluralForm.OTHER to "Export fehlgeschlagen · 0/{0} Dateien",
    ),
    PluralStrKey.EXPORT_PARTIAL_FILES to mapOf(
        PluralForm.ONE to "Export teilweise fehlgeschlagen · {0}/{1} Datei",
        PluralForm.OTHER to "Export teilweise fehlgeschlagen · {0}/{1} Dateien",
    ),
    PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to mapOf(
        PluralForm.ONE to "{0} erfolgreich, {1} fehlgeschlagen:",
        PluralForm.OTHER to "{0} erfolgreich, {1} fehlgeschlagen:",
    ),
    PluralStrKey.AVAILABLE_PROCESSORS_COUNT to mapOf(
        PluralForm.ONE to "{0}",
        PluralForm.OTHER to "{0}",
    ),
    PluralStrKey.DDL_WITH_LENGTH to mapOf(
        PluralForm.ONE to "{0} ({1} Zeichen)",
        PluralForm.OTHER to "{0} ({1} Zeichen)",
    ),
    PluralStrKey.TRUNCATED_MAX_ROWS to mapOf(
        PluralForm.ONE to "Gekürzt: auf {0} Zeile begrenzt",
        PluralForm.OTHER to "Gekürzt: auf {0} Zeilen begrenzt",
    ),
    PluralStrKey.UPTIME_DAYS to mapOf(
        PluralForm.ONE to "{0} Tag",
        PluralForm.OTHER to "{0} Tage",
    ),
    PluralStrKey.UPTIME_HOURS to mapOf(
        PluralForm.ONE to "{0} Stunde",
        PluralForm.OTHER to "{0} Stunden",
    ),
    PluralStrKey.UPTIME_MINUTES to mapOf(
        PluralForm.ONE to "{0} Minute",
        PluralForm.OTHER to "{0} Minuten",
    ),
)
