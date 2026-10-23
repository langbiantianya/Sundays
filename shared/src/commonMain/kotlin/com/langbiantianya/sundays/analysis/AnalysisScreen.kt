/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.str

/**
 * 分析工作台 —— 分析模式的主屏。
 *
 * ## 布局对着设计稿走
 *
 * 稿子里的画面 2（`data-screen="work"`）是三段式：
 *
 * ```
 * ├─ work-toolbar   ⊘ 只读 ｜ … ｜ ▶ 运行
 * ├─ 左树 .tree     头（数据源 + 注） / 列表 / 脚（一句话说明）
 * │   └─ 右主区 .work-main   编辑器面板 + 结果面板 + 状态栏
 * ```
 *
 * ## 右侧主区的三段为什么这么分
 *
 * - **编辑器**在上：SQL 是这里唯一的输入源，跑不了别的。
 * - **结果**在中间：它才是分析模式要给人看的东西，占的面积最大。
 * - **状态栏**在底：行数 / 耗时 / 引擎 —— 这些是「看结果时**顺带**要知道」的信息，
 *   放高会跟结果抢注意力。
 *
 * ## 这一层为什么不碰引擎
 *
 * `:shared` 是跨平台模块，不认识 `EngineClient`。它只接收**已经算好的**数据
 * （树、SQL、结果），把用户动作回调出去 —— 连不连、怎么拉，全在 desktopApp 侧决定。
 * 这样这份界面代码在别的平台（将来的移动端 / Web）也能直接复用。
 *
 * ## 结果表为什么是**只读**的
 *
 * 分析模式的结果是**算出来的**，不是「一张可以编辑的表」。
 * 而且它的来源可能是远端（恒为只读）也可能是工作区（本地可写）——
 * 在结果表上给编辑入口，等于给远端数据开了一个写入的口子。
 * 所以 `onCellEdit = null`：这一层**不提供**编辑入口，只读由引擎与方言保证（§6.5）。
 *
 * @param sources 可分析的远端数据源（已按「方言声明了可分析」过滤过）—— 供**空态**判断用：
 *   一个都没有时，右侧要说清「下一步做什么」，而不是摆一张空表
 * @param pullProgress 正在拉取时的进度；`null` = 没在拉
 * @param pullOutcome 最近一次拉取的**终态**；`null` = 还没拉过（或正在拉）
 * @param onCancelPull 「取消拉取」被点时回调；`null` = 不给取消入口
 * @param treeRoots 左侧数据源树的根节点（工作区 + 各远端源）
 * @param expandedNodeIds 当前展开着的树节点 id
 * @param onToggleNode 树的某个**分组**节点被展开 / 收起时回调
 * @param onSelectTreeTable 树里的某张**表**被点时回调
 * @param treeLoading 树里正有节点在加载；界面据此显示「加载中」
 * @param treeError 树加载失败的原因；`null` = 没有错误
 * @param sql 编辑器里的当前 SQL
 * @param onSqlChange 编辑器内容变化时回调
 * @param result 最近一次查询的结果；`null` = 还没跑过
 * @param running 正在跑 —— 界面据此禁用「运行」按钮
 * @param onRun 「运行」被点时回调
 */
@Composable
fun AnalysisScreen(
    sources: List<AnalysisSourceItem>,
    pullProgress: PullProgress? = null,
    pullOutcome: PullOutcome? = null,
    onCancelPull: (() -> Unit)? = null,
    treeRoots: List<AnalysisTreeNode> = emptyList(),
    expandedNodeIds: Set<String> = emptySet(),
    onToggleNode: (AnalysisTreeNode) -> Unit = {},
    onSelectTreeTable: (AnalysisTreeNode) -> Unit = {},
    treeLoading: Boolean = false,
    treeError: String? = null,
    sql: String = "",
    onSqlChange: (String) -> Unit = {},
    result: AnalysisResult? = null,
    running: Boolean = false,
    onRun: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 拉取状态条 —— 在树与主区**上面**横贯一条。
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
            AnalysisTreePanel(
                roots = treeRoots,
                expandedNodeIds = expandedNodeIds,
                onToggleNode = onToggleNode,
                onSelectTable = onSelectTreeTable,
                loading = treeLoading,
                error = treeError,
                modifier = Modifier.width(TREE_PANEL_WIDTH).fillMaxHeight(),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
            ) {
                AnalysisEditorPanel(
                    sql = sql,
                    onSqlChange = onSqlChange,
                    running = running,
                    onRun = onRun,
                    modifier = Modifier.weight(EDITOR_PANEL_WEIGHT),
                )
                AnalysisResultPanel(
                    result = result,
                    // 一个远端源都没有时，空态要说的是「去建连接」而不是「去跑一条 SQL」——
                    // 没有源就拉不到任何表，跑出来的结果只能是工作区里原有的东西。
                    noSources = sources.isEmpty(),
                    modifier = Modifier.weight(RESULT_PANEL_WEIGHT),
                )
                AnalysisStatusBar(result = result)
            }
        }
    }
}

/** 结果表容器的测试标记 —— 把「结果区域」与编辑器区分开。 */
const val ANALYSIS_RESULT_TABLE_TAG: String = "analysisResultTable"

/** 「运行」按钮的测试标记。 */
const val ANALYSIS_RUN_BUTTON_TAG: String = "analysisRunButton"

/** 左树的宽度。 */
private val TREE_PANEL_WIDTH = 240.dp

/** 编辑器面板占主区的高度比例 —— 剩下的给结果表。 */
private const val EDITOR_PANEL_WEIGHT = 0.42f

/** 结果面板占主区的高度比例。 */
private const val RESULT_PANEL_WEIGHT = 1f

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
            PullProgressIndicator(
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
 * 编辑器面板 —— 面板头 + SQL 编辑器（**工具条上带「格式化」与「运行」**）。
 *
 * ## 为什么固定成 DuckDB 方言
 *
 * 分析 SQL **只在本地工作区上跑**（引擎的 `RUN_QUERY` 只允许打在
 * `EMBEDDED_MODE` 的方言上），而工作区就是 DuckDB。
 * 所以编辑器的语言是 `sql-duckdb`，而且**隐藏语言切换器** ——
 * 摆一个能让用户切到 Lua 的下拉框，只会让「这条 SQL 到底打在哪」变得含糊。
 *
 * ## 为什么「运行」跟「格式化」同一行
 *
 * 它俩都是**对编辑器里这段 SQL 的动作** —— 分开放两行就让人多扫一眼，
 * 而且分析模式原来那条工具条（只读徽标 + 运行）本来就是一行没别的内容的行，
 * 去掉徽标之后**整行都空了**。
 */
@Composable
private fun AnalysisEditorPanel(
    sql: String,
    onSqlChange: (String) -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisPanelHead(
            title = str(StrKey.ANALYSIS_EDITOR_TITLE),
            sub = str(StrKey.ANALYSIS_EDITOR_HINT, "DuckDB"),
        )
        CodeEditorWithToolbar(
            text = sql,
            onTextChange = onSqlChange,
            languageId = SqlDialectProfile.DUCKDB.languageId,
            showLanguageSwitcher = false,
            actions = {
                WinButton(
                    onClick = onRun,
                    // 正在跑的时候禁掉：连点两下就是两条并发查询，
                    // 而工作区是**一个** DuckDB 文件 —— 后一条会把前一条的结果盖掉。
                    enabled = !running,
                    modifier = Modifier.testTag(ANALYSIS_RUN_BUTTON_TAG),
                ) {
                    Text(text = "▶ ${str(StrKey.ANALYSIS_RUN)}")
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 左树面板 —— 头（数据源 + 注）+ 树 + 脚（一句话说明）。
 *
 * 头与脚是**固定文案**，中间那一大块才是树。
 */
@Composable
private fun AnalysisTreePanel(
    roots: List<AnalysisTreeNode>,
    expandedNodeIds: Set<String>,
    onToggleNode: (AnalysisTreeNode) -> Unit,
    onSelectTable: (AnalysisTreeNode) -> Unit,
    loading: Boolean,
    error: String?,
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

        Box(modifier = Modifier.weight(1f)) {
            SourceTree(
                roots = roots,
                expandedIds = expandedNodeIds,
                onToggle = onToggleNode,
                onSelectTable = onSelectTable,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 加载中 —— 单独一行放在树**下面**，而不是把扭子换成转圈：
        // 无限动画会让「等界面空闲」的 UI 测试永远等不到，
        // 而一行静态文字既能告诉用户「在加载」，又不会卡住测试。
        if (loading) {
            Text(
                text = str(StrKey.ANALYSIS_TREE_LOADING),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }

        // 树脚：一句话说明「远端恒为只读 + 计算引擎是谁」
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (error != null) {
            // 出错时**顶掉**那句说明 —— 出错原因比「引擎是谁」更需要被看见。
            Text(
                text = str(StrKey.ANALYSIS_TREE_ERROR, error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        } else {
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
}

/**
 * 编辑器面板 —— 面板头 + SQL 编辑器。
 *
 * ## 为什么固定成 DuckDB 方言
 *
 * 分析 SQL **只在本地工作区上跑**（引擎的 `RUN_QUERY` 只允许打在
 * `EMBEDDED_MODE` 的方言上），而工作区就是 DuckDB。
 * 所以编辑器的语言是 `sql-duckdb`，而且**隐藏语言切换器** ——
 * 摆一个能让用户切到 Lua 的下拉框，只会让「这条 SQL 到底打在哪」变得含糊。
 */
@Composable
private fun AnalysisEditorPanel(
    sql: String,
    onSqlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisPanelHead(
            title = str(StrKey.ANALYSIS_EDITOR_TITLE),
            sub = str(StrKey.ANALYSIS_EDITOR_HINT, "DuckDB"),
        )
        CodeEditorWithToolbar(
            text = sql,
            onTextChange = onSqlChange,
            languageId = SqlDialectProfile.DUCKDB.languageId,
            showLanguageSwitcher = false,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * 结果面板 —— 面板头 + 只读结果表。
 *
 * 三种态：
 * - 还没跑过 → 空态（说清下一步做什么）
 * - 跑失败了 → 错误原文（顶对齐，不居中 —— 报错要从上往下顺着读）
 * - 跑成功了 → 结果表
 */
@Composable
private fun AnalysisResultPanel(
    result: AnalysisResult?,
    noSources: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisPanelHead(
            title = str(StrKey.ANALYSIS_RESULT_ROWS, result?.rows?.size ?: 0),
            sub = result?.let { str(StrKey.ANALYSIS_ELAPSED, it.elapsedMs) },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(ANALYSIS_RESULT_TABLE_TAG),
        ) {
            when {
                result == null -> {
                    EmptyHint(
                        title =
                            if (noSources) {
                                str(StrKey.ANALYSIS_NO_SOURCE)
                            } else {
                                str(StrKey.ANALYSIS_RESULT_EMPTY)
                            },
                        description =
                            if (noSources) {
                                str(StrKey.ANALYSIS_NO_SOURCE_HINT)
                            } else {
                                str(StrKey.ANALYSIS_EDITOR_HINT, "DuckDB")
                            },
                    )
                }

                result.failed -> {
                    EmptyHint(
                        title = str(StrKey.ANALYSIS_RESULT_FAILED),
                        description = result.error,
                        centerContent = false,
                    )
                }

                else -> {
                    DataTable(
                        columns = analysisTableColumns(result),
                        rows = analysisTableRows(result),
                        // 只读：`onCellEdit = null` —— 结果不是一张可以改的表。
                        // `cellEditable` 也显式给 `false`：前者是「收到编辑后做什么」，
                        // 后者是「这张表能不能改」，两者不是一回事。
                        onCellEdit = null,
                        cellEditable = false,
                        // `PageSize.ALL` —— 分析结果集是**一次查询返回的完整集合**
                        // （引擎侧封顶 5000 行），分页纯属**客户端**的视图安排；
                        // 用默认的「每页 20 行」的话，一张 5000 行的结果要点 250 页。
                        // 全部行靠 `LazyColumn` 的虚拟滚动扛住。
                        pageSize = PageSize.ALL,
                        serverSidePaging = false,
                        // 详情面板是浏览模式的遗产 —— 分析结果要看的是整张表。
                        showDetailPanel = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // 截断提示 —— 静默截断等于骗人：用户会以为这张表就这么大。
        if (result?.truncated == true) {
            Text(
                text = str(StrKey.ANALYSIS_RESULT_TRUNCATED, result.rows.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * 底栏 —— 行数与引擎。
 *
 * ## 为什么**不**写「只读会话」
 *
 * 设计稿的状态栏写的是「引擎 DuckDB · 只读会话」——
 * 但那是**远端**会话的只读，而这一栏说的是**工作区**（本地 DuckDB 文件）。
 * 工作区是**可写**的（M4 的验收明确要求 `CREATE TABLE tmp AS SELECT …` 能成功）。
 * 在这儿写「只读会话」就是**假信息**，会让用户不敢在工作区里建表。
 */
@Composable
private fun AnalysisStatusBar(
    result: AnalysisResult?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = str(StrKey.ANALYSIS_ROW_COUNT, result?.rows?.size ?: 0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = str(StrKey.ANALYSIS_ENGINE, "DuckDB"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 面板头 —— 左边标题，右边一句补充说明。 */
@Composable
private fun AnalysisPanelHead(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
