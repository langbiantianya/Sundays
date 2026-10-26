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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.menuShape
import com.langbiantianya.sundays.ui.onRightClick
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.tabStripContainerColor
import com.langbiantianya.sundays.ui.winShape
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 「重命名标签」弹窗 —— 标签条「✎」的落地 UI。
 *
 * 用底层 [Dialog]（与 [com.langbiantianya.sundays.connection.AddConnectionDialog] 同款）：
 * Material3 `AlertDialog` 会把内容塞进 `maxWidth = 560.dp` 的容器，且不需要它的槽位。
 * 输入框占位 280.dp + 单行；回车 = 确定（[onPreviewKeyEvent]），Esc = 关闭（[Dialog] 默认行为）。
 *
 * **为什么是弹窗而不是标签内联编辑**：`Tab` 的文本槽位于 `SecondaryScrollableTabRow` 的
 * SubcomposeLayout 内，把可编辑控件（尤其是自己抢焦点的 `BasicTextField`）塞进去会与该布局
 * 的测量 / 焦点链路互相触发。弹窗把编辑面与标签条彻底解耦。
 *
 * @param initialTitle 输入框里的初始值
 * @param onConfirm 点「确定」或按回车 —— 参数是输入框里的新名字
 * @param onDismiss 点「取消」或按 Esc
 * @param title 弹窗标题；`null` = 用通用的「重命名标签」。
 *   传别的标题是因为这个弹窗**不只**服务于标签条 —— 报表重命名也是同一件事，
 *   只是标题该叫「重命名报表」。
 */
@Composable
fun TabRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    var value by remember { mutableStateOf(initialTitle) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            // 弹窗是真正浮在内容之上的一层 —— 用 shadowElevation（真阴影），而不是
            // tonalElevation（色相叠色）。SundaysPalette 已把 surfaceTint 设为透明，
            // tonalElevation 不再产生任何视觉变化，这里改用阴影表达「浮起」。
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title ?: str(StrKey.RENAME_TAB),
                    style = MaterialTheme.typography.titleMedium,
                )
                WinTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(str(StrKey.NAME)) },
                    modifier =
                        Modifier
                            .width(280.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    onConfirm(value)
                                    true
                                } else {
                                    false
                                }
                            },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    WinTextButton(onClick = onDismiss) { Text(str(StrKey.CANCEL)) }
                    WinTextButton(onClick = { onConfirm(value) }) { Text(str(StrKey.CONFIRM)) }
                }
            }
        }
    }
}
