/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 「⊘ 只读」徽标 —— 分析模式的**不可关闭**只读标记。
 *
 * ## 它为什么不能关
 *
 * 分析模式连的是**远端**数据源，而远端**恒为只读**。这不是一个「当前打开了只读、
 * 可以关掉」的状态 —— 引擎侧的 L4 闸门（`ReadOnlyGate`）与方言级的
 * `READ_ONLY_DIALECT` 都不接受「关掉只读」这种请求：
 *
 * - 会话级的 `read_only` 是**请求级**开关，不落 `connection.json`；
 *   它由分析模式自己带上，前端没有关它的余地
 * - 方言级只读更是方言的**实现事实**（引擎里压根没有往它写入的路径），
 *   前端更无从「解除」
 *
 * 所以这里**刻意不提供任何开关** —— 给一个点了必然失败（或更糟：点了让人以为
 * 只读被关掉了）的按钮，违反「宁可少给」的原则。
 *
 * ## 为什么用 tertiary 色
 *
 * tertiary 在本项目的五套配色里是**独立的一组色**（青绿 / 品红 / 琥珀 / 暗梅红 …），
 * 与 primary（蓝 / 紫）明确区分开：读到这里就知道这是「另一类信息」，
 * 而不是「又一个蓝色按钮」。
 *
 * 底色用 `tertiaryContainer`、字色用 `onTertiaryContainer` —— 这一对是成对的，
 * 写死颜色会在某套配色下撞成「底色和字色分不清」。
 *
 * ## 提示文字去哪了
 *
 * 稿子在这里用的是 HTML `title`（悬停提示）。本仓库**一处**实验性 M3 API 都没用过
 * （见 `WinControls` 的 KDoc），为了一个悬停提示引入 `TooltipBox` 不划算；
 * 于是完整的解释放在**无障碍描述**里（读屏能读到），界面上可见的那句解释
 * 放在数据源树的下沿（`ANALYSIS_SOURCE_FOOT`）。
 */
@Composable
fun ReadOnlyBadge(modifier: Modifier = Modifier) {
    val badge = str(StrKey.ANALYSIS_READONLY_BADGE)
    val hint = str(StrKey.ANALYSIS_READONLY_HINT)

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = winShape(4.dp),
        modifier =
            modifier
                .testTag(ANALYSIS_READ_ONLY_BADGE_TAG)
                .semantics {
                    // 徽标本身只有两个短字（「⊘ 只读」），解释对它来说是必需的：
                    // 用户要能知道**为什么**不能写、以及能不能关。
                    contentDescription = "$badge。$hint"
                },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⊘",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}
