/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 拉取进度指示器 —— 三段（导出 → CSV → 导入）＋ 一行占用。
 *
 * ## 为什么不是一条进度条
 *
 * 拉取是**跨两个数据库**的长任务：远端读 → 落一个 CSV → 本地写。
 * 一条进度条只能说「还在动」，说不出**卡在哪一段** —— 而这三段的耗时差着数量级
 * （导出要扫全表，CSV 落盘是秒级，导入要逐行写 DuckDB）。
 * 用户看到进度条不动，会以为是卡死了；看到「正在导入工作区」就知道它在干活。
 *
 * ## 为什么把「文件」单算一段
 *
 * 因为它是**用户能看见的占用**：拉一张大表时，临时 CSV 已经吃了几百 MB。
 * 「临时文件 312.4 MB」这句话必须出现在界面上，否则用户不敢拉大表
 * —— 不知道要占多少磁盘的按钮，等于不敢按的按钮。
 *
 * ## 为什么用**静态**字形而不是转圈
 *
 * 转圈（`WinProgressIndicator`）是**无限动画**：
 * - 在界面里它永远停不下来，截图永远截不到一个「静止」的态；
 * - 在 UI 测试里它会让「等空闲」永远等不到 —— 动画帧一直排着队。
 * 三段的状态本来就是「已走完 / 正在走 / 还没走」，用 `✓ ● ○` 三个字形
 * 表达得更准，也更合这个项目的复古档调性。
 */
@Composable
fun PullStageIndicator(
    progress: PullProgress,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val currentLabel = str(progress.stage.labelKey())
    // 取文案要在**组合期**做完：semantics 的 lambda 不是组合作用域，
    // 在里面调 `str()` 编译不过。
    val pullingLabel = str(StrKey.ANALYSIS_PULLING)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(PULL_STAGE_INDICATOR_TAG)
                .semantics {
                    // 读屏用户要听到「现在在哪一段」—— 三段字形对读屏是没有意义的。
                    contentDescription = "$pullingLabel：$currentLabel"
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PullStage.entries.forEachIndexed { index, stage ->
                if (index > 0) {
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                StageChip(
                    stage = stage,
                    state = pullStageState(stage, progress.stage),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(StrKey.ANALYSIS_PULL_ROWS, progress.rows),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 大小在 ① 结束之后才有值 —— 之前显示「0 B」是**假信息**，
            // 用户会以为文件已经建好了却只有 0 字节。不显示比显示个假的强。
            if (progress.bytes > 0) {
                Text(
                    text = str(StrKey.ANALYSIS_PULL_SIZE, formatBytes(progress.bytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (onCancel != null) {
                WinOutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        text = str(StrKey.ANALYSIS_PULL_CANCEL),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** 一段的样子 —— 字形 + 文案 + 配色，三样都随状态变。 */
@Composable
private fun StageChip(
    stage: PullStage,
    state: PullStageState,
    modifier: Modifier = Modifier,
) {
    val content = chipContentColor(state)
    Surface(
        color = chipBackgroundColor(state),
        shape = winShape(4.dp),
        modifier = modifier.testTag(pullStageChipTag(stage)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stageGlyph(state),
                style = MaterialTheme.typography.labelMedium,
                color = content,
                // 状态字形单独给一个标记：断言要落在**这一格**上，
                // 而不是「屏上有个 ✓」—— 后者分不清是哪一段走完了。
                modifier = Modifier.testTag(pullStageGlyphTag(stage)),
            )
            Text(
                text = str(stage.labelKey()),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (state == PullStageState.CURRENT) FontWeight.Medium else FontWeight.Normal,
                color = content,
            )
        }
    }
}

/**
 * 拉取的**终态**一行 —— 完成 / 取消 / 失败。
 *
 * ## 为什么终态要单独一行，而不是把指示器留在原地
 *
 * 指示器说的是「现在在哪一段」，它只在**拉取进行中**有意义。
 * 拉完之后它要么停在最后一段（看着像还在跑），要么消失（用户不知道成没成）。
 * 所以终态另起一行，把「成 / 没成 / 为什么没成」说清楚。
 */
@Composable
fun PullOutcomeLine(
    outcome: PullOutcome,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(PULL_OUTCOME_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (outcome) {
            is PullOutcome.Done -> {
                Text(
                    text = str(StrKey.ANALYSIS_PULL_DONE),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 成功必须说清**落在哪张表** —— 不然用户不知道下一步该查哪张表。
                Text(
                    text = str(StrKey.ANALYSIS_STAGING_TABLE, outcome.result.stagingTable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PullOutcome.Cancelled -> {
                Text(
                    text = str(StrKey.ANALYSIS_PULL_CANCELLED),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is PullOutcome.Failed -> {
                Text(
                    text = str(StrKey.ANALYSIS_PULL_FAILED, outcome.reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ──────────────── 零件 ────────────────

/**
 * 状态字形 —— `✓` 已完成 / `●` 正在进行 / `○` 还没到。
 *
 * ## 为什么不用颜色**单独**表达状态
 *
 * 只靠颜色区分状态，在**色觉障碍**下就分不出来了；只靠字形区分，
 * 在五套配色（蓝灰 / 赛博 / 哔哩粉 / Win2000 / WinXP）里都成立。
 * 这里两者都做：字形是底线，颜色是加分。
 */
private fun stageGlyph(state: PullStageState): String =
    when (state) {
        PullStageState.DONE -> "✓"
        PullStageState.CURRENT -> "●"
        PullStageState.PENDING -> "○"
    }

/** 段的小底色。 */
@Composable
private fun chipBackgroundColor(state: PullStageState): Color =
    when (state) {
        PullStageState.DONE -> MaterialTheme.colorScheme.surfaceVariant
        PullStageState.CURRENT -> MaterialTheme.colorScheme.primaryContainer
        PullStageState.PENDING -> MaterialTheme.colorScheme.surface
    }

/**
 * 段上的字色。
 *
 * 底色与字色是**成对**取的（`primaryContainer` / `onPrimaryContainer`）——
 * 写死颜色会在某套配色下撞成「底色和字色分不清」。
 */
@Composable
private fun chipContentColor(state: PullStageState): Color =
    when (state) {
        PullStageState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
        PullStageState.CURRENT -> MaterialTheme.colorScheme.onPrimaryContainer
        PullStageState.PENDING -> MaterialTheme.colorScheme.outline
    }

/** 拉取进度指示器的测试标记 —— `pullStageIndicator`。 */
const val PULL_STAGE_INDICATOR_TAG: String = "pullStageIndicator"

/** 某一段的测试标记 —— `pullStageChip:<阶段名>`。 */
fun pullStageChipTag(stage: PullStage): String = "pullStageChip:${stage.name}"

/** 某一段的**状态字形**的测试标记 —— `pullStageGlyph:<阶段名>`。 */
fun pullStageGlyphTag(stage: PullStage): String = "pullStageGlyph:${stage.name}"

/** 拉取终态行的测试标记 —— `pullOutcome`。 */
const val PULL_OUTCOME_TAG: String = "pullOutcome"
