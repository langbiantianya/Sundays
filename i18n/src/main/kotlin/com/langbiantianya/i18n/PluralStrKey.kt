/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

/**
 * 带数量的文案键 —— 与 [StrKey] 分离，因为译文不再是「一个字符串」。
 *
 * ## 什么样的句子该进这里
 *
 * 判据只有一条：**数量的写法会跟着数值变**。
 *
 * | 句子 | 进不进 | 原因 |
 * |---|---|---|
 * | 共 5 行 | ✅ | 俄语 `5 строк` / `1 строка`，名词要变 |
 * | 导出失败 · 2/5 个文件 | ✅ | 同上 |
 * | 错误: {0} | ❌ | `{0}` 是文件名或原始报错，永远是宾格 |
 * | 已导出 3 行 → output.csv | ❌ | 两个插值都不是「可数名词 + 数量」 |
 * | 正在导出 · 已写出 {0} 行 | ✅ | 数量 + 名词 |
 *
 * 后一行那种**一句话里带数量又带别的成分**的，是最容易漏判的 ——
 * 俄语里 `строк` 一样要变，只是整句的语序也要一起交给译者。
 *
 * ## 中文表为什么只有 OTHER
 *
 * 中文用量词而不是词形变化：`共 1 行` 与 `共 5 行` 写法完全一样，
 * 所以 [com.langbiantianya.i18n.tables.pluralsZhHans] 每个键只给 `PluralForm.OTHER`。
 * 这不是省事，是这六种语言里中文的正确规则（见 [pluralForm]）。
 *
 * ## 变体里仍然要有 `{0}`
 *
 * 各语言表的 `{0}` 语义统一为**那个数量**。调用方传一次，
 * 每种语言自己决定它前面要不要加数词、要不要带单位。
 */
enum class PluralStrKey {
    ROWS_COUNT,
    ROWS_TRUNCATED,
    TOTAL_RECORDS,
    NOTIFICATIONS_COUNT,
    NOTIFICATIONS_FAILED_COUNT,
    PAGINATION_SUMMARY,
    BACKGROUND_TASKS_PROGRESS,
    DANGEROUS_SUMMARY,
    EXPORT_STATEMENTS_TO_FILES,
    EXPORTED_ROWS_PROGRESS,
    EXPORT_PROGRESS_ROWS,
    EXPORTED_ROWS_TO_FILE,
    EXPORT_FAILED_STATEMENTS,
    EXPORTED_FILES_COUNT,
    STATEMENTS_TOTAL,
    STATEMENTS_PROGRESS,
    AFFECTED_ROWS,
    QUERY_RESULT_ROWS,
    GENERATING_INSERTED,
    GENERATE_COMPLETE_SUMMARY,
    EXPORT_COMPLETE_FILES,
    FILES_WRITTEN,
    EXPORT_FAILED_ALL_FILES,
    EXPORT_PARTIAL_FILES,
    EXPORT_SUCCESS_FAILURE_COUNTS,
    AVAILABLE_PROCESSORS_COUNT,
    DDL_WITH_LENGTH,
    TRUNCATED_MAX_ROWS,
    UPTIME_DAYS,
    UPTIME_HOURS,
    UPTIME_MINUTES,
}
