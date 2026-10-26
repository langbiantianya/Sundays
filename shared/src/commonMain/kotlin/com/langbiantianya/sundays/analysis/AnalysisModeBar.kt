/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.str

/*
 * 分析模式的**两个视图** —— 工作台与报表设计。
 *
 * ## 为什么是「同一模式的两个视图」，而不是两个顶层页
 *
 * 工作台与报表设计共用**同一份工作区**（同一批拉进来的表、同一套 SQL 语义），
 * 切过去再切回来，SQL 还在、报表也还在。做成两个顶层页的话，
 * 「拉好的数据」与「做好的报表」就被分到了两个互不相干的地方 ——
 * 而它们本来就是一件事的两个面。
 *
 * ## 为什么用**标签**而不是下拉框
 *
 * 两个视图是**平级**的、并且会来回切 —— 下拉框每次切换都要「打开 → 看 → 选 → 收起」，
 * 而标签一次点击就到位，还自带「我现在在哪儿」的指示。
 */

/** 分析模式的视图。 */
enum class AnalysisMode {
    /** 工作台 —— 写 SQL、看结果。 */
    WORKBENCH,

    /** 报表设计 —— 把查询拼成报表。 */
    REPORT,
}

/** 分析模式视图切换条上的 UI 测试 tag。 */
object AnalysisModeTags {
    /** 切换条本体。 */
    const val MODE_BAR = "analysisModeBar"

    /** 某个视图的标签（tag = 视图名）。 */
    const val TAB_PREFIX = "analysisModeTab_"
}

/**
 * 分析模式的视图切换条。
 *
 * @param mode 当前视图
 * @param onModeChange 切换视图
 * @param modifier 布局修饰
 */
@Composable
fun AnalysisModeBar(
    mode: AnalysisMode,
    onModeChange: (AnalysisMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabRow(
        selectedTabIndex = mode.ordinal,
        modifier = modifier.fillMaxWidth().testTag(AnalysisModeTags.MODE_BAR),
    ) {
        AnalysisMode.entries.forEach { entry ->
            Tab(
                selected = entry == mode,
                onClick = { onModeChange(entry) },
                text = { Text(str(entry.labelKey())) },
                modifier = Modifier.testTag(AnalysisModeTags.TAB_PREFIX + entry.name),
            )
        }
    }
}

/**
 * 视图 → 文案键。
 *
 * @param mode 视图
 */
fun AnalysisMode.labelKey(): StrKey =
    when (this) {
        AnalysisMode.WORKBENCH -> StrKey.REPORT_WORKBENCH
        AnalysisMode.REPORT -> StrKey.REPORT_DESIGN
    }
