/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.langbiantianya.sundays.ui.SettingsEntryButton
import com.langbiantianya.sundays.ui.selectionContainerColor
import com.langbiantianya.sundays.ui.selectionContentColor
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 顶层导航条 —— 品牌 + 目标切换 chip + 设置入口。
 *
 * ## 为什么它现在才出现
 *
 * 以前应用是「连接管理 → 数据库浏览」的**单向**两屏结构，没有回得去的入口，
 * 所以不需要导航条：浏览屏是「从某个连接点进去」的下钻页，不是并列目标。
 * 数据分析模式是**并列**的顶层目标（连远端只读做分析，与浏览库是两种活儿），
 * 于是需要一个能在两个模式之间切换的地方 —— 那就是这条导航条。
 *
 * ## 布局对着设计稿走
 *
 * 稿子里的样子是：`sundays` 品牌标记 ｜ `连接管理` `数据分析` 两个 chip ｜ 右侧 ⚙。
 * 选中态用 [selectionContainerColor] / [selectionContentColor] 而不是裸写
 * `primaryContainer` —— 那两个函数按 `SelectionMode.INVERTED` 分支，经典档
 * （Win2000 / WinXP）里选中态要翻转成「实心主色底」，写死容器色会在那两档里
 * 看不出选中与否。
 *
 * @param current 当前目标 —— 对应的 chip 呈选中态
 * @param onNavigate 点某个 chip 时回调
 * @param onOpenSettings 点 ⚙ 时回调
 */
@Composable
fun TopNavBar(
    current: AppDestination,
    onNavigate: (AppDestination) -> Unit,
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

            // 目标切换 chip —— 只放「并列的顶层目标」
            //
            // 为什么不给 DATABASE 一个 chip：浏览屏是「从某个连接点进去」的**下钻页**，
            // 不是与连接管理并列的目标。给它一个 chip 会让人以为能从分析屏直接跳到
            // 「某个连接的浏览页」—— 而那件事的语义是「选一个连接」，不是「选一个模式」。
            NavChip(
                label = str(AppDestination.CONNECTIONS.labelKey),
                selected = current == AppDestination.CONNECTIONS,
                onClick = { onNavigate(AppDestination.CONNECTIONS) },
            )
            NavChip(
                label = str(AppDestination.ANALYSIS.labelKey),
                selected = current == AppDestination.ANALYSIS,
                onClick = { onNavigate(AppDestination.ANALYSIS) },
            )

            Box(modifier = Modifier.weight(1f))
            SettingsEntryButton(onClick = onOpenSettings)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 一个导航 chip。
 *
 * 选中态除了底色，还画一条 **2dp 的下边**（稿子里是 `inset 0 -2px 0 0 primary`）——
 * 光靠底色在低对比度的经典档里不够醒目，那条边才是「我在这一页」的确定信号。
 */
@Composable
private fun NavChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = winShape(4.dp)
    // 颜色在**外面**取：`drawBehind` 的 lambda 不是 @Composable 的，
    // 在里面读 MaterialTheme 编译不过（而且那也是把「读主题」藏进了绘制代码里）。
    val underlineColor = MaterialTheme.colorScheme.primary
    val underline =
        if (selected) {
            Modifier.drawBehind {
                drawRect(
                    color = underlineColor,
                    topLeft = Offset(0f, size.height - 2.dp.toPx()),
                    size = Size(size.width, 2.dp.toPx()),
                )
            }
        } else {
            Modifier
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = selectionContentColor(selected, MaterialTheme.colorScheme.onSurfaceVariant),
        modifier =
            Modifier
                .clickable(role = Role.Tab, onClick = onClick)
                .background(selectionContainerColor(selected, MaterialTheme.colorScheme.surface), shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .then(underline)
                .semantics { this.role = Role.Tab },
    )
}
