/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str

/**
 * 通知中心面板 —— 只读地展示 [NotificationCenter] 里的通知。
 *
 * ## 为什么与 [NotificationCenter] 一起住在 `:shared`
 *
 * 两者是一对：中心是**状态**，面板是**它的视图**。只搬中心不搬面板，会得到
 * 「状态在 `:shared`、视图在 `:desktopApp`」的分裂 —— 而分析模式（另一个工作台）
 * 要的是**连状态带面板**整套，否则就得在分析模式里重写一遍一模一样的面板。
 *
 * ## 为什么时间格式化是**传进来的**
 *
 * 「本地时区的今天几点」是**平台知识**：`commonMain` 里既没有 `java.util.Calendar`，
 * 也没有时区数据库（`kotlin.time.Instant` 只有 epoch 换算，没有本地时区转换）。
 * 与其为此引入一个新依赖（`kotlinx-datetime`），不如把这件事**留给平台侧** ——
 * 这与本仓库既有的分界一致（`ClipboardWriter` / `NativePathPicker` 也是平台能力）。
 *
 * @param timeFormatter 把毫秒时间戳格式化成「当天 `HH:mm` / 更早 `MM-dd HH:mm`」。
 */
@Composable
fun NotificationCenterPanel(
    center: NotificationCenter,
    timeFormatter: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.width(420.dp).heightIn(max = 420.dp).testTag(NOTIFICATION_PANEL_TAG),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = str(StrKey.NOTIFICATIONS_TITLE),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                val errors = center.notifications.count { it.severity == AppNotification.Severity.ERROR }
                Text(
                    text =
                        buildString {
                            append(plural(PluralStrKey.NOTIFICATIONS_COUNT, center.notifications.size, center.notifications.size))
                            if (errors > 0) {
                                append(plural(PluralStrKey.NOTIFICATIONS_FAILED_COUNT, errors, errors))
                            }
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                WinOutlinedButton(
                    onClick = { center.markAllRead() },
                    enabled = center.unreadCount > 0,
                    shape = SundaysPalette.buttonShape,
                ) {
                    Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(str(StrKey.MARK_ALL_READ))
                }
                Spacer(Modifier.width(6.dp))
                WinOutlinedButton(
                    onClick = { center.clear() },
                    enabled = center.notifications.isNotEmpty(),
                    shape = SundaysPalette.buttonShape,
                    modifier = Modifier.testTag(NOTIFICATION_CLEAR_BTN_TAG),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(str(StrKey.CLEAR_NOTIFICATIONS))
                }
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (center.notifications.isEmpty()) {
                Text(
                    text = str(StrKey.NO_NOTIFICATIONS_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    center.notifications.forEach { n -> NotificationRow(n, timeFormatter) }
                }
            }
        }
    }
}

/** 通知面板里的一条。 */
@Composable
private fun NotificationRow(
    n: AppNotification,
    timeFormatter: (Long) -> String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Icon(
            imageVector =
                when (n.severity) {
                    AppNotification.Severity.SUCCESS -> Icons.Filled.Check
                    AppNotification.Severity.ERROR -> Icons.Filled.Close
                    AppNotification.Severity.INFO -> Icons.Filled.Refresh
                },
            contentDescription = null,
            tint =
                when (n.severity) {
                    AppNotification.Severity.SUCCESS -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = n.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            // 引擎原文完整给出，不截断 —— 排障时省略号等于没有
            Text(
                text = n.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = timeFormatter(n.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 通知面板本身的 UI 测试 tag。 */
private const val NOTIFICATION_PANEL_TAG = "notificationPanel"

/** 通知面板「清空」按钮的 UI 测试 tag。 */
private const val NOTIFICATION_CLEAR_BTN_TAG = "notificationClearBtn"
