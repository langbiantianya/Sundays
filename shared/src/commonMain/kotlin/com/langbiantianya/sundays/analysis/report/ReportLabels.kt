/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import com.langbiantianya.i18n.StrKey

/*
 * 报表的**文案映射** —— 模型里的「种类」到界面上的「一句话」。
 *
 * ## 为什么单独一个文件
 *
 * `:shared` 的这一层不认识语言，它只产出**种类**（`ChartProblem.NEED_TWO_COLUMNS`、
 * `ReportWidgetType.PIE`）。把「种类 → 文案键」散落在各个界面文件里，
 * 就会出现「这里说缺列、那里说列不够」—— 而同一个意思只能有**一句话**。
 *
 * 所以这两个映射集中在这里：一个地方改，六种语言一起改。
 */

/**
 * 图表画不出来的原因 → 文案键。
 *
 * @param problem 原因
 */
fun chartProblemText(problem: ChartProblem): StrKey =
    when (problem) {
        ChartProblem.NO_COLUMNS -> StrKey.CHART_PROBLEM_NO_COLUMNS
        ChartProblem.NEED_TWO_COLUMNS -> StrKey.CHART_PROBLEM_NEED_TWO_COLUMNS
        ChartProblem.VALUE_NOT_NUMERIC -> StrKey.CHART_PROBLEM_VALUE_NOT_NUMERIC
        ChartProblem.NEGATIVE_VALUES -> StrKey.CHART_PROBLEM_NEGATIVE_VALUES
        ChartProblem.NO_DATA -> StrKey.CHART_PROBLEM_NO_DATA
    }

/**
 * 图表类型 → 文案键。
 *
 * @param type 类型
 */
fun reportWidgetTypeText(type: ReportWidgetType): StrKey =
    when (type) {
        ReportWidgetType.PIE -> StrKey.REPORT_TYPE_PIE
        ReportWidgetType.BAR -> StrKey.REPORT_TYPE_BAR
        ReportWidgetType.TABLE -> StrKey.REPORT_TYPE_TABLE
    }
