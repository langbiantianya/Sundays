/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.WinDropdown
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 分析工作台 —— 分析模式的主屏。
 *
 * ## 布局对着设计稿走
 *
 * 稿子里的画面 2（`data-screen="work"`）是三段式：
 *
 * ```
 * ├─ work-toolbar   数据源下拉 ｜ ⊘ 只读 ｜ … ｜ ▶ 运行 ⤓ 生成报表 ⟳ 刷新
 * ├─ 左树 .tree     头（数据源 + 注） / 列表 / 脚（一句话说明）
 * │   └─ 右主区 .work-main   编辑器面板 + 结果面板 + 状态栏
 * ```
 *
 * 本文件是它的**骨架**：工具条、数据源树、只读徽标都是真的；
 * 编辑器与结果面板留给后续里程碑（M4' 的工作台与拉取链路）接上去。
 *
 * ## 这一层为什么不碰引擎
 *
 * `:shared` 是跨平台模块，不认识 `EngineClient`。它只接收**已经算好的**
 * 数据源列表，把选中项回调出去 —— 连不连、怎么拉，全在 desktopApp 侧决定。
 * 这样这份界面代码在别的平台（将来的移动端 / Web）也能直接复用。
 *
 * @param sources 可分析的远端数据源（已按「方言声明了可分析」过滤过）
 * @param selectedSourceId 当前选中的数据源 id；`null` = 未选中
 * @param onSelectSource 选中某个数据源时回调
 * @param pullProgress 正在拉取时的进度；`null` = 没在拉
 * @param pullOutcome 最近一次拉取的**终态**；`null` = 还没拉过（或正在拉）
 * @param onCancelPull 「取消拉取」被点时回调；`null` = 不给取消入口
 */
@Composable
fun AnalysisScreen(
    sources: List<AnalysisSourceItem>,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
    pullProgress: PullProgress? = null,
    pullOutcome: PullOutcome? = null,
    onCancelPull: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AnalysisToolbar(
            sources = sources,
            selectedSourceId = selectedSourceId,
            onSelectSource = onSelectSource,
        )
        // 拉取状态条 —— 在工具条**下面**、树与主区**上面**横贯一条。
        // 放在这里是因为拉取是**跨树与主区**的动作：它从左边树上发起，
        // 结果落在右边的分析区里，中间那条横贯的带子正是它俩的连接。
        if (pullProgress != null || pullOutcome != null) {
            AnalysisPullStrip(
                progress = pullProgress,
                outcome = pullOutcome,
                onCancel = onCancelPull,
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            AnalysisSourceTree(
                sources = sources,
                selectedSourceId = selectedSourceId,
                onSelectSource = onSelectSource,
                modifier = Modifier.width(240.dp).fillMaxHeight(),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
            ) {
                AnalysisWorkArea(sources = sources)
            }
        }
    }
}

/**
 * 拉取状态条 —— 进度指示器与终态行。
 *
 * 两个都传时**只显示进度**：正在拉的时候，终态还没发生，
 * 显示「拉取完成」是骗人。
 */
@Composable
private fun AnalysisPullStrip(
    progress: PullProgress?,
    outcome: PullOutcome?,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (progress != null) {
            PullStageIndicator(
                progress = progress,
                onCancel = onCancel,
            )
        }
        if (progress == null && outcome != null) {
            PullOutcomeLine(outcome = outcome)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 工具条 —— 数据源选择 + 只读徽标。
 *
 * 「运行 / 生成报表 / 刷新」三枚按钮在稿子里在这一行，但它们要到工作台
 * 接上引擎之后才有意义 —— 与其先摆三个点了没反应的按钮，不如先不摆。
 */
@Composable
private fun AnalysisToolbar(
    sources: List<AnalysisSourceItem>,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${str(StrKey.ANALYSIS_SOURCE_HEAD)}：",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // 没有数据源时**不渲染下拉框**：一个点不开的下拉框只会让人反复点它，
        // 而「没有可分析的数据源」这句话由右侧主区的空态承担（说一遍就够）。
        if (sources.isNotEmpty()) {
            WinDropdown(
                value = sources.firstOrNull { it.id == selectedSourceId }?.name.orEmpty(),
                onValueChange = { label ->
                    sources.firstOrNull { it.name == label }?.let { onSelectSource(it.id) }
                },
                items = sources,
                itemLabel = { it.name },
                enabled = true,
                modifier = Modifier.width(220.dp),
            )
        }
        ReadOnlyBadge()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 左树 —— 数据源列表。
 *
 * 稿子里的层级是「数据源 → 库 → 表」三层；本轮只到**第一层**：
 * 库与表要等引擎侧的 `ANALYSIS.LIST_SOURCES` / `LIST_TABLES` 接上工作台会话
 * 之后才有内容。
 */
@Composable
private fun AnalysisSourceTree(
    sources: List<AnalysisSourceItem>,
    selectedSourceId: String?,
    onSelectSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(bottom = 4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = str(StrKey.ANALYSIS_SOURCE_HEAD),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = str(StrKey.ANALYSIS_SOURCE_NOTE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            sources.forEach { source ->
                val selected = source.id == selectedSourceId
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(analysisSourceRowTag(source.id))
                            .clickable(
                                role = Role.Tab,
                                onClick = { onSelectSource(source.id) },
                            ).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    // 方言标签 —— 让用户一眼看出这个源是远端的哪种库
                    Box(
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    winShape(3.dp),
                                ).padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = source.dialectLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 树脚：一句话说明「远端恒为只读 + 计算引擎是谁」
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = str(StrKey.ANALYSIS_SOURCE_FOOT, "DuckDB"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * 右侧主区 —— 编辑器与结果面板的容器。
 *
 * 本轮还没有工作区会话，所以两种情形都走空态：
 * - 一个数据源都没有 → 提示先去建连接
 * - 有数据源但还没拉任何表 → 提示从左侧拉一张表进来
 */
@Composable
private fun AnalysisWorkArea(sources: List<AnalysisSourceItem>) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        if (sources.isEmpty()) {
            Column {
                Text(
                    text = str(StrKey.ANALYSIS_NO_SOURCE),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = str(StrKey.ANALYSIS_NO_SOURCE_HINT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column {
                Text(
                    text = str(StrKey.ANALYSIS_LOCAL_WORKSPACE),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = str(StrKey.ANALYSIS_WORKSPACE_EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = str(StrKey.ANALYSIS_WORKSPACE_EMPTY_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
