/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.str

/**
 * 单元格取值的输入控件 —— 一个文本框 + 一个显式的「设为 NULL」开关。
 *
 * ## 这个控件存在的理由：**空串 ≠ NULL**
 *
 * 数据库里 `''` 和 `NULL` 是两种东西：前者参与 `LIKE`、排序、唯一索引，
 * 后者全部不参与；导成 CSV 时一个写空、一个什么都不写。用户在一个输入框里
 * 「清空」时想的是哪一个？本项目定的约定是：**清空就是空串**，要 NULL 必须
 * 点右边那个 `∅`。
 *
 * 为什么不用「输入 NULL 两个字」这种魔法串：文本列里真的可能存在 `"null"`
 * 这个值（一个标签叫 null 的分类、一段被误录的文本）。一旦约定成魔法串，
 * 这些值就**既写不进去也改不了**，而用户在界面上完全看不出发生了什么。
 * 显式按钮没有这个歧义：点了就是 NULL，不点就一定不是。
 *
 * 唯一的代价是多一个控件、用户可能不知道它存在 —— 所以它在 NULL 状态下
 * 必须**看得见**（文本框变成斜体 `NULL` 占位），而不是悄悄清空输入框。
 *
 * ## 受控组件
 *
 * [value] 是唯一真相源：`null` 表示 NULL，其余表示字面文本（包括空串）。
 * 组件内部不另存一份文本状态，这样「外部把值改成 NULL」与「用户点了 ∅」
 * 走的是同一条路径，不会出现两处状态打架。
 */
@Composable
fun CellValueField(
    value: String?,
    /** 该列能否取 NULL。[nullable] 为 `false` 时不画 `∅` 开关 —— 往 `NOT NULL` 列写 NULL 必然失败，
     * 让用户点一个注定失败的按钮没有意义。 */
    nullable: Boolean,
    theme: DataTableTheme,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = theme.cellText,
    containerColor: Color = theme.cellEditingBackground,
    enabled: Boolean = true,
    /** 回车提交。`null` = 不接管回车（由外层决定）。 */
    onSubmit: (() -> Unit)? = null,
) {
    val isNull = value == null
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = value.orEmpty(),
            onValueChange = { onValueChange(it) },
            singleLine = true,
            enabled = enabled,
            // NULL 状态下**禁用输入**而不是清空文本框：清空会让用户以为这格是空的，
            // 而它其实是 NULL —— 两者在导出和查询上完全不同。
            readOnly = isNull,
            textStyle = if (isNull) textStyle.copy(fontStyle = FontStyle.Italic) else textStyle,
            placeholder =
                if (isNull) {
                    { Text(NULL_PLACEHOLDER, style = textStyle.copy(fontStyle = FontStyle.Italic), maxLines = 1) }
                } else {
                    null
                },
            keyboardOptions =
                androidx.compose.foundation.text
                    .KeyboardOptions(capitalization = KeyboardCapitalization.None),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = containerColor,
                    unfocusedContainerColor = containerColor,
                    disabledContainerColor = containerColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            modifier = Modifier.weight(1f),
        )
        if (nullable) {
            Spacer(Modifier.width(2.dp))
            NullToggle(
                isNull = isNull,
                enabled = enabled,
                onToggle = { onValueChange(if (isNull) "" else null) },
            )
        }
    }
}

/**
 * 「设为 NULL / 取消 NULL」的小方钮。
 *
 * 为什么自己画而不用 Material3 的 [androidx.compose.material3.Checkbox]：
 * 那个控件带 48dp 的最小可点击区域，直接放进表格会把行高从 20dp 撑到 48dp，
 * 一屏能看的行数直接少一半。它内部的画法（方框 + 对勾）并不复杂。
 *
 * 用 `∅` / `✕` 两个符号而不是文字：它只有 24dp 宽，塞不下任何语种的单词。
 * 可访问名走 [contentDescription]，所以读屏软件念到的是完整的一句话。
 */
@Composable
private fun NullToggle(
    isNull: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val tint =
        if (isNull) {
            androidx.compose.material3.MaterialTheme.colorScheme.primary
        } else {
            androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
        }
    // `semantics { }` 的 lambda **不是** @Composable，所以 `str(...)` 必须在外面求值 ——
    // 直接写进 lambda 里编译不过（"invocations can only happen from the context of a
    // @Composable function"）。
    val description = str(if (isNull) StrKey.EDIT_CLEAR_NULL else StrKey.EDIT_SET_NULL)
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .background(
                    if (isNull) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ).border(1.dp, tint)
                .clickable(enabled = enabled) { onToggle() }
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isNull) NULL_TOGGLE_OFF_GLYPH else NULL_TOGGLE_ON_GLYPH,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            // 方钮里那点空间放不下 13sp 的字，压小一档并把高度钉住，
            // 否则它会把整格高度顶起来。
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/**
 * NULL 状态下的占位文字。
 *
 * 刻意**不用** `StrKey` 的译名而固定写 `NULL`：这是一个 SQL 关键字而不是自然语言，
 * 界面切到日语时它也仍然该显示 `NULL`。真正会误导人的是把它译成「空」——
 * 那个词指的是空串，不是 NULL。
 */
const val NULL_PLACEHOLDER = "NULL"

private const val NULL_TOGGLE_ON_GLYPH = "∅"

private const val NULL_TOGGLE_OFF_GLYPH = "✕"
