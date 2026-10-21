/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 右下角通知中心的入口按钮 —— 铃铛 + 未读数。
 *
 * 它与 [NotificationCenterPanel] 是一对：一个是入口，一个是面板。
 * 两者都是**纯展示**（不碰引擎、不碰状态），所以都住在 `:shared` ——
 * 分析模式（另一个工作台）要通知时，入口与面板能整套复用。
 */
@Composable
fun NotificationBell(
    unread: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(winShape(4.dp))
                .background(
                    if (open) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                ).clickable(
                    onClickLabel = if (open) str(StrKey.CLOSE_NOTIFICATION_CENTER) else str(StrKey.OPEN_NOTIFICATION_CENTER),
                    onClick = onToggle,
                ).testTag(NOTIFICATION_BELL_TAG)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = str(StrKey.NOTIFICATION_CENTER),
            tint = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        if (unread > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 通知中心入口按钮的 UI 测试 tag。 */
private const val NOTIFICATION_BELL_TAG = "notificationBell"
