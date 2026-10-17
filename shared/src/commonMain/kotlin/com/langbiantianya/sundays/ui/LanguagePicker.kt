/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.AppLanguage

/**
 * 语言选择器 —— 引导页与设置页**共用**这一个组件。
 *
 * ## 为什么共用，而不是两处各写一遍
 *
 * 两处的需求完全一样（列出六种语言、标出当前选中、点一下就切），
 * 差别只有「分组标题与说明」那两句文案。做成共用组件后，
 * 「新增一种语言」的改动只有一处 —— 而两处实现不同步正是这种设置项的经典退化。
 *
 * ## 为什么显示母语名而不是当前界面语言的译名
 *
 * [AppLanguage.nativeName] 恒以母语书写（`简体中文` `English` `Русский` `日本語` `Deutsch`）。
 * 一个德语用户看不懂 `Deutsch` 时，他同样看不懂「德语」；而一个**看不懂当前界面语言**
 * 的人（刚装上、日语系统切到英语界面），恰恰只能靠母语名找到自己的语言。
 * 语言选择器是**元语言界面** —— 在这里翻译语言名会让最需要它的人找不到。
 *
 * ## 为什么不用单选按钮组
 *
 * 六种语言横排时按钮组会撑破 1024dp 的最小窗口宽度（chip 加文字约 120dp × 6 已经
 * 接近上限，再加外边距就换行）。横排 chip + 自动换行在任何宽度下都成立。
 */
@Composable
fun LanguagePicker(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppLanguage.entries.forEach { language ->
            LanguageChip(
                language = language,
                selected = language == selected,
                onClick = { onSelect(language) },
            )
        }
    }
}

/** 单个语言条目。 */
@Composable
private fun LanguageChip(
    language: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    Row(
        modifier =
            Modifier
                // `selectable` 而不是 `clickable(role = …)`：后者只设 role 不设 `selected` 语义，
                // 读屏会念「单选按钮」却永远不告诉用户「这一个选中了没有」—— 那比不给 role 更糟。
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).border(if (selected) 2.dp else 1.dp, borderColor, winShape(6.dp))
                .background(selectionContainerColor(selected, MaterialTheme.colorScheme.surface), winShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language.nativeName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = selectionContentColor(selected, MaterialTheme.colorScheme.onSurface),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Check,
                // 选中态已由 Role.RadioButton 的 selected 语义 + 边框表达，不重复朗读
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
