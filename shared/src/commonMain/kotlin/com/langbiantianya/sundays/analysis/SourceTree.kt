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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/*
 * 分析模式的数据源树 —— 设计稿里的「左树」。
 *
 * ## 为什么自己画，而不是复用浏览模式的 `SchemaTree`
 *
 * 浏览模式的 `SchemaTree` 深度耦合 `DatabaseBrowserState`，而且它的语义是
 * **可写浏览**（右键菜单里有建表 / 改表 / 删表）。分析模式这三样一个都不要
 * —— 远端恒为只读（§3），工作区里的写操作走 SQL 编辑器而不是右键菜单。
 * 复用它等于把「可写浏览」的语义一起搬过来。
 *
 * ## 树的形状
 *
 * ```
 * 本地工作区 (DuckDB)          ← 工作区：恒在，且在第一位
 *   orders                     ← 工作区的子节点直接是表（见 WORKSPACE_SCHEMA）
 * hive_prod (MySQL)
 *   shop                      ← 命名空间（MySQL 的库 / PG 的 schema）
 *     orders                  ← 表
 * ```
 *
 * ## 展开是**懒加载**的
 *
 * 子节点在**点开的时候**才去查 —— 不是启动时把每个源的每个库都列一遍。
 * 后者意味着「打开分析模式」要先连一遍所有远端，用户没点任何东西就已经
 * 等在了网络 I/O 上（而远端可能压根连不上）。
 */

/** 树里一行的**扭子**的宽度 —— 用来给叶子节点留出对齐的空位。 */
private val TWISTY_WIDTH = 14.dp

/** 每深一级的缩进。 */
private val INDENT_STEP = 14.dp

/** 扭子的三种字形。 */
private object Twisty {
    /** 已展开。 */
    const val EXPANDED = "▾"

    /** 已收起。 */
    const val COLLAPSED = "▸"

    /** 还没查过（点开才去查）。 */
    const val NOT_LOADED = "⋯"
}

/**
 * 分析模式的数据源树。
 *
 * ## 这一层只做三件事
 *
 * - 把 [AnalysisTreeNode] 画成缩进的行；
 * - 把「点扭子」翻译成 [onToggle]（展开 / 收起）；
 * - 把「点表」翻译成 [onSelectTable]。
 *
 * 它**不**知道数据从哪来、也不自己查 —— 树的内容由调用方给，
 * 展开动作也由调用方去执行。这是 `:shared`  `commonMain` 的硬约束：
 * 这一层不碰引擎、不碰文件系统。
 *
 * @param roots 树的根节点（工作区 + 各远端源）
 * @param expandedIds 当前展开着的节点 id
 * @param onToggle 扭子被点时回调（展开 / 收起都走它）
 * @param onSelectTable 某个**表**被点时回调
 * @param modifier 修饰符
 */
@Composable
fun SourceTree(
    roots: List<AnalysisTreeNode>,
    expandedIds: Set<String>,
    onToggle: (AnalysisTreeNode) -> Unit,
    onSelectTable: (AnalysisTreeNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        roots.forEach { root ->
            SourceTreeNode(
                node = root,
                depth = 0,
                expandedIds = expandedIds,
                onToggle = onToggle,
                onSelectTable = onSelectTable,
            )
        }
    }
}

/**
 * 树里的**一行**（连同它的子树）。
 *
 * 递归渲染：展开的节点把自己的子节点按 `depth + 1` 再画一遍。
 */
@Composable
private fun SourceTreeNode(
    node: AnalysisTreeNode,
    depth: Int,
    expandedIds: Set<String>,
    onToggle: (AnalysisTreeNode) -> Unit,
    onSelectTable: (AnalysisTreeNode) -> Unit,
) {
    val expanded = node.id in expandedIds
    val expandable = node.expandable()
    // 工作区那一行的名字是**固定**的 —— 它是应用自带的引擎，不是用户起名的。
    // 名字由界面按 `StrKey` 取，会话不传字符串，也就传不错语言。
    val label =
        if (node.kind == AnalysisTreeKind.WORKSPACE) {
            str(StrKey.ANALYSIS_LOCAL_WORKSPACE)
        } else {
            node.label
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(analysisTreeRowTag(node.id))
                .clickable(
                    role = Role.Tab,
                    onClick = {
                        // 点整行的语义按 kind 分：表是「选中它」，分组是「展开 / 收起」。
                        // 分成两种是因为这两个动作的**后果**完全不同 ——
                        // 选中远端表会**拉数据**，展开源只是列个库。
                        if (node.kind == AnalysisTreeKind.TABLE) {
                            onSelectTable(node)
                        } else {
                            onToggle(node)
                        }
                    },
                ).padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(INDENT_STEP * depth))
        if (expandable) {
            Box(
                modifier =
                    Modifier
                        .width(TWISTY_WIDTH)
                        .testTag(analysisTreeTwistyTag(node.id))
                        // 扭子自己也可点 —— 它比整行小得多，是「只展开这一层」最直接的入口。
                        .clickable { onToggle(node) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        when {
                            !node.loaded -> Twisty.NOT_LOADED
                            expanded -> Twisty.EXPANDED
                            else -> Twisty.COLLAPSED
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 叶子留一个同样宽的空位 —— 否则表的字会比分组的名字左移一截。
            Spacer(modifier = Modifier.width(TWISTY_WIDTH))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (node.kind == AnalysisTreeKind.WORKSPACE) FontWeight.Medium else FontWeight.Normal,
            color =
                if (node.kind == AnalysisTreeKind.WORKSPACE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        // 方言标签 —— 只挂在「根」上（工作区与远端源），
        // 让用户一眼看出这一支是本地引擎还是哪种远端库。
        node.dialectLabel?.let { dialect ->
            Box(
                modifier =
                    Modifier
                        .padding(start = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, winShape(3.dp))
                        .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = dialect,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (expanded) {
        node.children.forEach { child ->
            SourceTreeNode(
                node = child,
                depth = depth + 1,
                expandedIds = expandedIds,
                onToggle = onToggle,
                onSelectTable = onSelectTable,
            )
        }
    }
}
