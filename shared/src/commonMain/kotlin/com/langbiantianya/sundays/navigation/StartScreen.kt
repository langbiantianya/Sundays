/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.uiBevel
import com.langbiantianya.sundays.ui.winShape

/**
 * **启动首屏** —— 选一个模式进去。
 *
 * ## 为什么要有这一屏
 *
 * 应用现在有**两种**活儿：传统的「连库 → 浏览表结构与数据」，
 * 与**只读**的「数据分析」（远端只读源 → 本地工作区里算）。
 * 它们是两套会话、两套界面、两种心智模型。
 *
 * 早先顶栏有两个 chip 用来切，但 chip 是**常驻**的 —— 用户每次都要自己
 * 决定「这次用哪个」，而多数时候他心里是有答案的（要做分析就直接做分析）。
 * 把它做成**启动首屏**，就是把「选模式」这件事放在**该问的时候**问：
 * 进应用时问一次，之后一路走下去。
 *
 * ## 两条路都是**真的**能走
 *
 * 「数据分析」进去的是分析工作台（没有可分析的数据源时它会**说清**
 * 「没有可分析的数据源」并指路连接管理）；「连接管理」进去的是连接列表。
 * 两个入口都不是摆设 —— 各自都有**内容**等着。
 *
 * @param onOpenConnections 进「连接管理」（传统的连库与浏览工具）
 * @param onOpenAnalysis 进「数据分析」（只读分析工作台）
 */
@Composable
fun StartScreen(
    onOpenConnections: () -> Unit,
    onOpenAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = str(StrKey.START_MODE_TITLE),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StartModeCard(
                title = str(StrKey.NAV_CONNECTIONS),
                description = str(StrKey.START_MODE_CONNECTIONS_HINT),
                icon = Icons.Default.Storage,
                onClick = onOpenConnections,
                modifier = Modifier.testTag(START_MODE_CONNECTIONS_TAG),
            )
            StartModeCard(
                title = str(StrKey.NAV_ANALYSIS),
                description = str(StrKey.START_MODE_ANALYSIS_HINT),
                icon = Icons.Default.Insights,
                onClick = onOpenAnalysis,
                modifier = Modifier.testTag(START_MODE_ANALYSIS_TAG),
            )
        }
    }
}

/**
 * 一个模式的入口卡片 —— 图标 + 标题 + 一句话说明，整块可点。
 *
 * ## 为什么是**卡片**而不是两个按钮
 *
 * 这两个选项对**不熟悉的人**是完全陌生的两件事（一个是老工具，一个是新模式），
 * 按钮只给一个名字，用户得靠猜；卡片多一行说明，「点哪个」这件事就不用猜了。
 */
@Composable
private fun StartModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .width(260.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .uiBevel(raised = true),
        shape = winShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「连接管理」入口卡片的测试标记。 */
const val START_MODE_CONNECTIONS_TAG: String = "startModeConnections"

/** 「数据分析」入口卡片的测试标记。 */
const val START_MODE_ANALYSIS_TAG: String = "startModeAnalysis"
