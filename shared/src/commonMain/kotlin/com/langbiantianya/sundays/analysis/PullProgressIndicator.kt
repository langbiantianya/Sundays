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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/*
 * 拉取进度指示器 —— 「正在拉取」＋ 已读行数 ＋ 取消。
 *
 * ## 它为什么不再有「三段」
 *
 * 早先的版本是三段（导出 → CSV → 导入），因为那时数据要**绕一圈磁盘**：
 * 远端导出成 CSV、CSV 落盘、再导入工作区。那三段的耗时差着数量级，
 * 所以要分段告诉用户「现在卡在哪一段」。
 *
 * 现在的拉取是 **DuckDB 的联邦直查** —— 行从远端**直接**流进工作区表，
 * 中间**没有阶段可分**。于是这里只剩一段：
 * 「正在拉取，已读 N 行」。
 *
 * 顺带消失的还有「临时文件 312.4 MB」那行 —— 联邦直查**不落盘**，
 * 没有临时文件可占，也就不用告诉用户它占了多少磁盘。
 *
 * ## 为什么用**静态**字形而不是转圈
 *
 * 转圈（`WinProgressIndicator`）是**无限动画**：
 * - 在界面里它永远停不下来，截图永远截不到一个「静止」的态；
 * - 在 UI 测试里它会让「等空闲」永远等不到 —— 动画帧一直排着队。
 * 这里用 `●` 一个字形表达「正在动」，既能截图也能测。
 */

/**
 * 拉取进度指示器。
 *
 * @param progress 当前进度（已读行数）
 * @param onCancel 取消回调；`null` = 不给取消入口
 * @param modifier 修饰符
 */
@Composable
fun PullProgressIndicator(
    progress: PullProgress,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 取文案要在**组合期**做完：semantics 的 lambda 不是组合作用域，
    // 在里面调 `str()` 编译不过。
    val pullingLabel = str(StrKey.ANALYSIS_PULLING)
    val rowsLabel = str(StrKey.ANALYSIS_PULL_ROWS, progress.rows)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(PULL_PROGRESS_INDICATOR_TAG)
                .semantics {
                    // 读屏用户要听到「正在拉取，已读多少行」——
                    // 一个字形对读屏是没有意义的。
                    contentDescription = "$pullingLabel：$rowsLabel"
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PullStateChip()
            Text(
                text = rowsLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * 「正在拉取」那枚小片 —— 一个字形 + 一句文案。
 *
 * ## 为什么底色与字色是**成对**取的
 *
 * `primaryContainer` / `onPrimaryContainer` —— 写死颜色会在某套配色下
 * 撞成「底色和字色分不清」。
 */
@Composable
private fun PullStateChip(modifier: Modifier = Modifier) {
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = winShape(4.dp),
        modifier = modifier.testTag(PULL_PROGRESS_CHIP_TAG),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "●",
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier.testTag(PULL_PROGRESS_GLYPH_TAG),
            )
            Text(
                text = str(StrKey.ANALYSIS_PULLING),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
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
 * 指示器说的是「正在拉取」，它只在**拉取进行中**有意义。
 * 拉完之后它要么停在原样（看着像还在跑），要么消失（用户不知道成没成）。
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
                Text(
                    text = str(StrKey.ANALYSIS_ROW_COUNT, outcome.result.rowsLanded),
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

// ──────────────── 测试标记 ────────────────

/** 拉取进度指示器的测试标记 —— `pullProgressIndicator`。 */
const val PULL_PROGRESS_INDICATOR_TAG: String = "pullProgressIndicator"

/** 「正在拉取」那枚小片的测试标记 —— `pullProgressChip`。 */
const val PULL_PROGRESS_CHIP_TAG: String = "pullProgressChip"

/** 那枚小片里**状态字形**的测试标记 —— `pullProgressGlyph`。 */
const val PULL_PROGRESS_GLYPH_TAG: String = "pullProgressGlyph"

/** 拉取终态行的测试标记 —— `pullOutcome`。 */
const val PULL_OUTCOME_TAG: String = "pullOutcome"
