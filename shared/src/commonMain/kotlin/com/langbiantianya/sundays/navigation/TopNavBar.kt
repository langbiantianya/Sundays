/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langbiantianya.sundays.ui.SettingsEntryButton
import com.langbiantianya.sundays.ui.winShape

/**
 * 顶栏 —— 品牌 + 设置入口。
 *
 * ## 为什么**没有**目标切换 chip
 *
 * 早先这里摆着「连接管理」「数据分析」两个 chip（第 3 片加的）。去掉它们不是为了
 * 好看，是那两个 chip **各说各话**：
 *
 * - **「连接管理」是首屏** —— 用户已经在连接管理里了，这个 chip 只是把当前项
 *   再显示一遍（切过去等于什么都不做）。
 * - **「数据分析」是入口** —— 入口该待在**用户真正需要它的地方**，而不是常驻在
 *   顶栏抢注意力。它现在在**连接管理的空闲面板**里（没选中任何连接时，
 *   右侧那一块正好在问「接下来做什么」）。
 *
 * 去掉 chip 之后顶栏只剩**品牌 + ⚙ 设置** —— 品牌让人知道这是哪个应用，
 * ⚙ 是**唯一**一个跨屏可达的设置入口（外观 / 语言都在设置页里切，
 * 顶栏再摆一份只会让人以为点一下就能换配色）。
 */
@Composable
fun TopNavBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 品牌标记 —— 稿子里是一个纯色方块 + 文字
            Box(
                modifier =
                    Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.primary, winShape(3.dp)),
            )
            Text(
                text = "sundays",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Box(modifier = Modifier.weight(1f))
            SettingsEntryButton(onClick = onOpenSettings)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
