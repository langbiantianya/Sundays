/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.langbiantianya.i18n.StrKey

/**
 * 通用「确认 / 取消」对话框 —— 一句话 + 两个按钮。
 *
 * ## 为什么要有这么一个通用件
 *
 * 「删掉之前先问一句」这个动作在应用里到处都有（删报表、删部件、删数据源、
 * 删连接……）。每个调用方自己拼一个 `AlertDialog`，就会出现**同一句话的不同说法**
 * 与**按钮顺序不一致** —— 而这类差异没有任何信息量，只是各写各的。
 *
 * 已有更专门的确认框（`DangerousSqlConfirmDialog`）不并进来：它要列出**每一条**
 * 危险语句，还要用错误色的「仍然执行」按钮 —— 那是另一种形状的确认，
 * 硬塞进这里只会让两边都别扭。
 *
 * @param text 确认说明 —— 要说清**删掉的是什么**、**会失去什么**
 * @param confirmText 确定按钮上的字；`null` = 用通用的「确定」
 * @param onConfirm 点「确定」
 * @param onDismiss 点「取消」或按 Esc
 * @param modifier Compose modifier
 */
@Composable
fun ConfirmDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        text = { Text(text) },
        confirmButton = {
            WinButton(
                onClick = onConfirm,
                shape = SundaysPalette.buttonShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.testTag(CONFIRM_DIALOG_CONFIRM_TAG),
            ) { Text(confirmText ?: str(StrKey.CONFIRM)) }
        },
        dismissButton = {
            WinButton(onClick = onDismiss, shape = SundaysPalette.buttonShape) { Text(str(StrKey.CANCEL)) }
        },
    )
}

/**
 * 通用确认框「确定」按钮的 UI 测试 tag。
 *
 * 与对话框放在一起 —— tag 是这段 UI 的契约：按钮改名、挪位、换实现都会让
 * UI 测试失效，而 tag 与它所标记的控件分居两个模块时，那种失效要等到测试红才知道。
 */
const val CONFIRM_DIALOG_CONFIRM_TAG = "confirmDialogConfirmBtn"
