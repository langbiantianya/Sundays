/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.str

/*
 * **报表设计用的数据树** —— 一条窄窄的树，让人看得见「有哪些数据可以选」。
 *
 * ## 为什么报表设计要有自己的树
 *
 * 部件的形状约定是「第一列类别、第二列数值」—— 而写出一条**符合这个约定**的 SQL，
 * 前提是知道「有哪些表、每张表有哪些字段」。没有树的话，用户只能：
 *
 * 1. 切回工作台，把树展开，把表名与字段名**记下来**；
 * 2. 切回报表设计，凭记忆把名字敲进 SQL；
 * 3. 敲错了还要再切回去核对一遍。
 *
 * 三次来回、两次凭记忆 —— 而名字一旦记错，报错会等到**跑 SQL** 时才出现。
 * 把树摆在写 SQL 的地方，这三步就都省了。
 *
 * ## 为什么画的是**同一个**树组件
 *
 * 树本身是 `com.langbiantianya.sundays.tree.ObjectTree` —— 浏览模式与分析工作台
 * 已经共用的那一个。报表设计**不重画**一棵树：同一个东西有两套画法，
 * 用户就要学两遍「点表会发生什么」。
 *
 * ## 这一层**不碰引擎**
 *
 * 与分析工作台同一条规矩：`:shared` 只接收**已经算好的**树（节点、展开态），
 * 把用户动作回调出去 —— 展开一个节点要查子节点，那是会话的事。
 *
 * ## 树的**点击后果**由调用方决定
 *
 * 这一层只负责「点表」与「点字段」两件事**发生了**，
 * 至于它们导致什么（插进正在写的 SQL / 开一个新部件），由调用方给回调决定。
 * 同一个组件在两处用、两种后果 —— 那不是「两套实现」，而是**同一个动作在两种上下文里**。
 */

/** 报表数据树里各处的 UI 测试 tag。 */
object ReportDataTreeTags {
    /** 树面板本体。 */
    const val PANE = "reportDataTree"

    /** 树的滚动容器。 */
    const val LIST = "reportDataTreeList"

    /** 「添加数据源」按钮。 */
    const val ADD_SOURCE = "reportDataTreeAddSource"
}

/**
 * 数据树窄条的**宽度** —— 200dp。
 *
 * ## 为什么是固定值，而不是「跟窗口按比例」
 *
 * 树里显示的是**名字**（表名、字段名），而名字的长度不随窗口按比例地变 ——
 * 按比例给，窗口一小就把名字截掉一半，而截掉一半的表名是**认不出来的**。
 *
 * ## 为什么不给它一个下限 / 上限
 *
 * 分析工作台那棵树是可以拖宽窄的（`TreePaneShell`），因为它旁边是**结果表** ——
 * 两者抢宽度，用户自己知道该让给谁。
 * 报表设计这一页的树旁边是**窄条**（报表列表）与**画布**，
 * 而画布里的部件卡片是**定宽**的（固定 300dp 高、宽度随窗口）——
 * 树抢走的那点宽度，画布里的表格就得横向多滚一截。
 * 所以这里给一个固定值：布局是设计决定的，不交给用户调。
 */
val REPORT_DATA_TREE_WIDTH: Dp = 200.dp

/**
 * 报表设计用的**数据树** —— 一条窄窄的树。
 *
 * @param roots 树的根节点（工作区 + 已经拉进来的数据源）
 * @param expandedNodeIds 当前展开着的树节点 id
 * @param onToggleNode 某个节点被展开 / 收起时回调
 * @param onSelectTable 某个**表**被点时回调
 * @param onSelectColumn 某个**字段**被点时回调
 * @param onAddSource 「添加数据源」被点时回调。
 *   给这个入口的理由：树上一个源都没有时，用户在报表设计里就**没有任何路可走**——
 *   而「先回工作台加源、再回来」是一条用户想不到、也不该由他去想的路
 * @param hint 树脚下的一句话说明 —— 说明「点它会发生什么」。
 *   **必填**：这一句是**上下文**决定的（画布上点表是「开一个新部件」，
 *   编辑页里点表是「把引用加进 SQL」），而两句话不一样 ——
 *   给个默认值就等于让其中一处**说错**动作
 * @param paneTag 这一块面板的 UI 测试 tag。
 *   **为什么由调用方给**：报表设计页上同时存在**两棵**这棵树 ——
 *   画布上那棵与部件编辑页里那棵（编辑页是盖在画布上的**覆盖层**，
 *   底下那棵并没有被移除，只是被挡住了）。
 *   两棵用同一个 tag 的话，「按 tag 找这棵树」会找到两个 ——
 *   断言反而失败。给它们各自的 tag，测试才能指认「是哪一棵」
 * @param scrollState 滚动状态。
 *   **由外面持有**，是为了在「打开部件编辑页 → 关掉」这一轮里，
 *   树还是停在用户刚才滚动到的位置 —— 而不是每次都弹回顶部
 * @param modifier 布局修饰
 */
@Composable
fun ReportDataTreePane(
    roots: List<ObjectTreeNode>,
    expandedNodeIds: Set<String> = emptySet(),
    onToggleNode: (ObjectTreeNode) -> Unit = {},
    onSelectTable: (ObjectTreeNode) -> Unit = {},
    onSelectColumn: (ObjectTreeNode) -> Unit = {},
    onAddSource: () -> Unit = {},
    hint: String,
    paneTag: String = ReportDataTreeTags.PANE,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag(paneTag),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 头：标题 + 「添加数据源」 ──
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(StrKey.REPORT_DATA_TREE_TITLE),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 「+」—— 与分析工作台的树面板头**同一个含义**：
                // 加的东西直接进工作区 DuckDB（它自己的清单，不跟连接管理器共用）。
                WinIconButton(
                    onClick = onAddSource,
                    modifier = Modifier.testTag(ReportDataTreeTags.ADD_SOURCE),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = str(StrKey.ANALYSIS_ADD_SOURCE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── 树 ──
            if (roots.isEmpty()) {
                // 一个源都没有 —— 说清「下一步做什么」，而不是摆一棵空树。
                EmptyHint(
                    title = str(StrKey.REPORT_DATA_TREE_EMPTY),
                    description = str(StrKey.REPORT_DATA_TREE_EMPTY_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .testTag(ReportDataTreeTags.LIST),
                ) {
                    ObjectTree(
                        roots = roots,
                        expandedIds = expandedNodeIds,
                        onToggle = onToggleNode,
                        onSelectTable = onSelectTable,
                        onSelectColumn = onSelectColumn,
                        // 不画转圈：转圈是**无限动画**，会让「等界面空闲」的 UI 测试永远等不到。
                        showLoadingSpinner = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── 脚：点它会发生什么 ──
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = hint,
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
