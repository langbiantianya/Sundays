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

/** Русские формы множественного числа — ONE / FEW / MANY, как требует CLDR. */
internal val pluralsRu: Map<PluralStrKey, Map<PluralForm, String>> =
    mapOf(
        PluralStrKey.ROWS_COUNT to
            mapOf(
                PluralForm.ONE to "Всего {0} строка",
                PluralForm.FEW to "Всего {0} строки",
                PluralForm.MANY to "Всего {0} строк",
            ),
        PluralStrKey.ROWS_TRUNCATED to
            mapOf(
                PluralForm.ONE to "(усечено: {0} строка)",
                PluralForm.FEW to "(усечено: {0} строки)",
                PluralForm.MANY to "(усечено: {0} строк)",
            ),
        PluralStrKey.TOTAL_RECORDS to
            mapOf(
                PluralForm.ONE to "Всего {0} запись",
                PluralForm.FEW to "Всего {0} записи",
                PluralForm.MANY to "Всего {0} записей",
            ),
        PluralStrKey.NOTIFICATIONS_COUNT to
            mapOf(
                PluralForm.ONE to "{0} уведомление",
                PluralForm.FEW to "{0} уведомления",
                PluralForm.MANY to "{0} уведомлений",
            ),
        PluralStrKey.NOTIFICATIONS_FAILED_COUNT to
            mapOf(
                PluralForm.ONE to " · с ошибкой: {0} уведомление",
                PluralForm.FEW to " · с ошибкой: {0} уведомления",
                PluralForm.MANY to " · с ошибкой: {0} уведомлений",
            ),
        PluralStrKey.PAGINATION_SUMMARY to
            mapOf(
                PluralForm.ONE to "Всего {0} строка · страница {1} · по {2} на странице",
                PluralForm.FEW to "Всего {0} строки · страница {1} · по {2} на странице",
                PluralForm.MANY to "Всего {0} строк · страница {1} · по {2} на странице",
            ),
        PluralStrKey.BACKGROUND_TASKS_PROGRESS to
            mapOf(
                PluralForm.ONE to "{0} задача выполняется / всего {1}",
                PluralForm.FEW to "{0} задачи выполняются / всего {1}",
                PluralForm.MANY to "{0} задач выполняется / всего {1}",
            ),
        PluralStrKey.DANGEROUS_SUMMARY to
            mapOf(
                PluralForm.ONE to "Следующий запуск содержит {0} необратимую операцию:",
                PluralForm.FEW to "Следующий запуск содержит {0} необратимые операции:",
                PluralForm.MANY to "Следующий запуск содержит {0} необратимых операций:",
            ),
        PluralStrKey.EXPORT_STATEMENTS_TO_FILES to
            mapOf(
                PluralForm.ONE to "{0} инструкция будет экспортирована в файлы: {1}",
                PluralForm.FEW to "{0} инструкции будут экспортированы в файлы: {1}",
                PluralForm.MANY to "{0} инструкций будут экспортированы в файлы: {1}",
            ),
        PluralStrKey.EXPORTED_ROWS_PROGRESS to
            mapOf(
                PluralForm.ONE to "Экспортировано {0} строка…",
                PluralForm.FEW to "Экспортировано {0} строки…",
                PluralForm.MANY to "Экспортировано {0} строк…",
            ),
        PluralStrKey.EXPORT_PROGRESS_ROWS to
            mapOf(
                PluralForm.ONE to "Экспорт · записано {0} строка",
                PluralForm.FEW to "Экспорт · записано {0} строки",
                PluralForm.MANY to "Экспорт · записано {0} строк",
            ),
        PluralStrKey.EXPORTED_ROWS_TO_FILE to
            mapOf(
                PluralForm.ONE to "Экспортировано {0} строка → {1}",
                PluralForm.FEW to "Экспортировано {0} строки → {1}",
                PluralForm.MANY to "Экспортировано {0} строк → {1}",
            ),
        PluralStrKey.EXPORT_FAILED_STATEMENTS to
            mapOf(
                PluralForm.ONE to "Не экспортирована {0} инструкция из {1}:",
                PluralForm.FEW to "Не экспортированы {0} инструкции из {1}:",
                PluralForm.MANY to "Не экспортировано {0} инструкций из {1}:",
            ),
        PluralStrKey.EXPORTED_FILES_COUNT to
            mapOf(
                PluralForm.ONE to "Экспортировано {0} файл",
                PluralForm.FEW to "Экспортировано {0} файла",
                PluralForm.MANY to "Экспортировано {0} файлов",
            ),
        PluralStrKey.STATEMENTS_TOTAL to
            mapOf(
                PluralForm.ONE to "(всего {0} инструкция)",
                PluralForm.FEW to "(всего {0} инструкции)",
                PluralForm.MANY to "(всего {0} инструкций)",
            ),
        PluralStrKey.STATEMENTS_PROGRESS to
            mapOf(
                PluralForm.ONE to "{0}/{1} инструкция",
                PluralForm.FEW to "{0}/{1} инструкции",
                PluralForm.MANY to "{0}/{1} инструкций",
            ),
        PluralStrKey.AFFECTED_ROWS to
            mapOf(
                PluralForm.ONE to "Затронуто {0} строка (не SELECT)",
                PluralForm.FEW to "Затронуто {0} строки (не SELECT)",
                PluralForm.MANY to "Затронуто {0} строк (не SELECT)",
            ),
        PluralStrKey.QUERY_RESULT_ROWS to
            mapOf(
                PluralForm.ONE to "Результат запроса · {0} строка",
                PluralForm.FEW to "Результат запроса · {0} строки",
                PluralForm.MANY to "Результат запроса · {0} строк",
            ),
        PluralStrKey.GENERATING_INSERTED to
            mapOf(
                PluralForm.ONE to "Генерация данных… вставлено {0} строка",
                PluralForm.FEW to "Генерация данных… вставлено {0} строки",
                PluralForm.MANY to "Генерация данных… вставлено {0} строк",
            ),
        PluralStrKey.GENERATE_COMPLETE_SUMMARY to
            mapOf(
                PluralForm.ONE to "Генерация завершена · всего {0} строка · обработано скриптов: {1}",
                PluralForm.FEW to "Генерация завершена · всего {0} строки · обработано скриптов: {1}",
                PluralForm.MANY to "Генерация завершена · всего {0} строк · обработано скриптов: {1}",
            ),
        PluralStrKey.EXPORT_COMPLETE_FILES to
            mapOf(
                PluralForm.ONE to "Экспорт завершён · {0} файл",
                PluralForm.FEW to "Экспорт завершён · {0} файла",
                PluralForm.MANY to "Экспорт завершён · {0} файлов",
            ),
        PluralStrKey.FILES_WRITTEN to
            mapOf(
                PluralForm.ONE to "Записано {0} файл:",
                PluralForm.FEW to "Записано {0} файла:",
                PluralForm.MANY to "Записано {0} файлов:",
            ),
        PluralStrKey.EXPORT_FAILED_ALL_FILES to
            mapOf(
                PluralForm.ONE to "Экспорт не выполнен · 0/{0} файл",
                PluralForm.FEW to "Экспорт не выполнен · 0/{0} файла",
                PluralForm.MANY to "Экспорт не выполнен · 0/{0} файлов",
            ),
        PluralStrKey.EXPORT_PARTIAL_FILES to
            mapOf(
                PluralForm.ONE to "Экспорт выполнен частично · {0}/{1} файл",
                PluralForm.FEW to "Экспорт выполнен частично · {0}/{1} файла",
                PluralForm.MANY to "Экспорт выполнен частично · {0}/{1} файлов",
            ),
        PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS to
            mapOf(
                PluralForm.ONE to "Успешно: {0} файл · с ошибкой: {1}:",
                PluralForm.FEW to "Успешно: {0} файла · с ошибкой: {1}:",
                PluralForm.MANY to "Успешно: {0} файлов · с ошибкой: {1}:",
            ),
        PluralStrKey.AVAILABLE_PROCESSORS_COUNT to
            mapOf(
                PluralForm.ONE to "{0} процессор",
                PluralForm.FEW to "{0} процессора",
                PluralForm.MANY to "{0} процессоров",
            ),
        PluralStrKey.DDL_WITH_LENGTH to
            mapOf(
                PluralForm.ONE to "{0} ({1} символ)",
                PluralForm.FEW to "{0} ({1} символа)",
                PluralForm.MANY to "{0} ({1} символов)",
            ),
        PluralStrKey.TRUNCATED_MAX_ROWS to
            mapOf(
                PluralForm.ONE to "Усечено: показано {0} строка",
                PluralForm.FEW to "Усечено: показано {0} строки",
                PluralForm.MANY to "Усечено: показано {0} строк",
            ),
        PluralStrKey.UPTIME_DAYS to
            mapOf(
                PluralForm.ONE to "{0} день",
                PluralForm.FEW to "{0} дня",
                PluralForm.MANY to "{0} дней",
            ),
        PluralStrKey.UPTIME_HOURS to
            mapOf(
                PluralForm.ONE to "{0} час",
                PluralForm.FEW to "{0} часа",
                PluralForm.MANY to "{0} часов",
            ),
        PluralStrKey.UPTIME_MINUTES to
            mapOf(
                PluralForm.ONE to "{0} минута",
                PluralForm.FEW to "{0} минуты",
                PluralForm.MANY to "{0} минут",
            ),
    )
