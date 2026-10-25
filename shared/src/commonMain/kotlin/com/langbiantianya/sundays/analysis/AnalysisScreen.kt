/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.TREE_PANE_MAX_WIDTH_RATIO
import com.langbiantianya.sundays.tree.TreePaneShell
import com.langbiantianya.sundays.tree.TreePaneToggleButton
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.HorizontalDragHandle
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinIconButton
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
 * @param treeRoots 左侧数据源树的根节点（工作区 + 里面已经拉进来的表）
 * @param expandedNodeIds 当前展开着的树节点 id
 * @param onToggleNode 树的某个节点被展开 / 收起时回调
 * @param onSelectTreeTable 树里的某张**表**被点时回调
 * @param onSelectTreeColumn 树里的某个**字段**被点时回调
 * @param treeLoading 树里正有节点在加载；界面据此显示「加载中」
 * @param treeError 树加载失败的原因；`null` = 没有错误
 * @param onAddSource 「添加数据源」被点时回调 —— 加的东西**直接进工作区 DuckDB**
 *   （它自己的清单，不跟连接管理器共用）
 * @param sql 编辑器里的当前 SQL
 * @param onSqlChange 编辑器内容变化时回调
 * @param result 最近一次查询的结果；`null` = 还没跑过
 * @param running 正在跑 —— 界面据此禁用「运行」按钮
 * @param onRun 「运行」被点时回调
 * @param treePaneHoverIcon 左树分隔条悬停时的指针图标。`null` = 不改指针。
 *
 *   **为什么由调用方给**：「左右拉伸」箭头是**平台专属**资源
 *   （AWT 里只有 `E_RESIZE_CURSOR` / `W_RESIZE_CURSOR`），公共 Compose API 造不出来。
 *   `:shared` 只管「什么时候用」，平台侧管「长什么样」——
 *   与仓库既有的分界一致（`ClipboardWriter` / `NativePathPicker` / `DragHandle` 的 `hoverIcon`）。
 * @param editorPaneHoverIcon 编辑器 / 结果表之间那条分隔条悬停时的指针图标。
 *   桌面端应传**上下拉伸**箭头（`Cursor.NS_RESIZE_CURSOR`）——
 *   与左树那条传**左右拉伸**箭头，方向必须跟着分隔条自身的朝向走。
 */
@Composable
fun AnalysisScreen(
    sources: List<AnalysisSourceItem>,
    pullProgress: PullProgress? = null,
    pullOutcome: PullOutcome? = null,
    onCancelPull: (() -> Unit)? = null,
    treeRoots: List<ObjectTreeNode> = emptyList(),
    expandedNodeIds: Set<String> = emptySet(),
    onToggleNode: (ObjectTreeNode) -> Unit = {},
    onSelectTreeTable: (ObjectTreeNode) -> Unit = {},
    onSelectTreeColumn: (ObjectTreeNode) -> Unit = {},
    treeLoading: Boolean = false,
    treeError: String? = null,
    onAddSource: () -> Unit = {},
    sql: String = "",
    onSqlChange: (String) -> Unit = {},
    result: AnalysisResult? = null,
    running: Boolean = false,
    onRun: () -> Unit = {},
    treePaneHoverIcon: PointerIcon? = null,
    editorPaneHoverIcon: PointerIcon? = null,
    modifier: Modifier = Modifier,
) {
    // 左树的**宽度**与**折叠态** —— 与浏览模式的树面板同一套理由：
    // 布局是**窗口级偏好**，不是「树的数据」的一部分，因此不进会话状态机。
    //
    // 放在这一层（而不是 `AnalysisSession` 里）是因为分析模式**同一时刻只有一个实例**：
    // 切到别的顶层页再切回来，这一屏会被拆掉重建 —— 那时宽度回到默认值，
    // 与浏览模式「切顶层页也会复位」的行为一致（浏览模式保的是**切 sheet**不丢，
    // 那是它把状态放在 `DatabaseBrowserScreen` 的原因）。
    var treePaneWidth by remember { mutableStateOf(ANALYSIS_TREE_PANEL_DEFAULT_WIDTH) }
    var treePaneCollapsed by remember { mutableStateOf(false) }

    // 编辑器面板的**高度** —— 与左树的宽度、折叠态**同类**：布局偏好，住在屏幕层。
    //
    // 为什么不能写死成比例：写一段长 SQL 时编辑器太矮，
    // 跑出来的结果只有两行时结果表又占了七成屏 —— 两种诉求互相打架，
    // 而比例是编译期定死的，用户没有任何办法调。
    //
    // `null` = **还没拖过**，用默认比例（跟着窗口高度走）。
    var editorHeight by remember { mutableStateOf<Dp?>(null) }

    // 左树的**滚动位置** —— 提到面板**外面**持有。
    //
    // 为什么：折叠时整棵树会从组合里移除（44dp 的窄轨塞不下树），
    // 滚动状态若记在面板里就跟着一起没了 —— 展开后树回到顶部，
    // 用户得重新滚回去找刚才那张表。
    //
    // 为什么住在**屏幕层**而不是会话里：它是**这棵树**的滚动位置，
    // 而分析模式同一时刻只有一棵树（不像浏览模式有多个连接各有一棵）；
    // 屏幕层这一层已经持有宽度与折叠态，滚动位置与它们是同一类东西。
    val treeScrollState = rememberScrollState()

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

        // 宽度上限按容器比例给（与浏览模式同一个常量）—— 窗口窄时若还允许拉到固定 dp，
        // 右侧的编辑器与结果表会被挤到没法用。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxPaneWidth = maxWidth * TREE_PANE_MAX_WIDTH_RATIO
            Row(modifier = Modifier.fillMaxSize()) {
                TreePaneShell(
                    collapsed = treePaneCollapsed,
                    onToggleCollapsed = { treePaneCollapsed = !treePaneCollapsed },
                    width = treePaneWidth,
                    onWidthChange = { treePaneWidth = it },
                    defaultWidth = ANALYSIS_TREE_PANEL_DEFAULT_WIDTH,
                    minWidth = ANALYSIS_TREE_PANEL_MIN_WIDTH,
                    maxWidth = maxPaneWidth,
                    collapsedIcon = Icons.Filled.Storage,
                    handleModifier = Modifier.testTag(ANALYSIS_TREE_DRAG_HANDLE_TAG),
                    hoverIcon = treePaneHoverIcon,
                ) { paneModifier ->
                    AnalysisTreePanel(
                        roots = treeRoots,
                        expandedNodeIds = expandedNodeIds,
                        onToggleNode = onToggleNode,
                        onSelectTable = onSelectTreeTable,
                        onSelectColumn = onSelectTreeColumn,
                        onAddSource = onAddSource,
                        onCollapse = { treePaneCollapsed = !treePaneCollapsed },
                        loading = treeLoading,
                        error = treeError,
                        scrollState = treeScrollState,
                        modifier = paneModifier.testTag(ANALYSIS_TREE_PANEL_TAG),
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                ) {
                    // 编辑器与结果表之间那条缝**可拖**。
                    //
                    // 为什么要多套一层 [BoxWithConstraints]：默认高度是**按比例**算出来的
                    // （随窗口高度走），而拖出来的是**绝对高度** —— 两种算法都需要知道
                    // 「编辑器 + 结果表」这一段有多高，所以必须先把状态栏让出去、
                    // 再量剩下的那块。
                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        // 编辑器最多能有多高：要让出分隔条的命中区与结果表的下限。
                        val maxEditorHeight =
                            (maxHeight - ANALYSIS_EDITOR_HANDLE_HEIGHT - MIN_ANALYSIS_RESULT_HEIGHT)
                                .coerceAtLeast(0.dp)
                        // 编辑器的下限**要让位给结果表的下限** —— 两者冲突时（窗口很矮）
                        // **结果表优先**：它是分析模式的产出，看不见就等于白跑；
                        // 而编辑器内部能滚，矮一点只是少显示几行。
                        val minEditorHeight = MIN_ANALYSIS_EDITOR_HEIGHT.coerceAtMost(maxEditorHeight)
                        // 没拖过时的初始高度 —— 沿用原来的权重比（0.42 : 1）。
                        val defaultEditorHeight =
                            ((maxHeight - ANALYSIS_EDITOR_HANDLE_HEIGHT) * EDITOR_PANEL_DEFAULT_RATIO)
                                .coerceIn(minEditorHeight, maxEditorHeight)
                        // 实际生效的高度：拖过的用拖出来的，没拖过的用默认值；
                        // 两者都过一遍钳位 —— 窗口缩窄后上限会变小，
                        // 拖出来的绝对高度可能已经装不下了，布局必须服从当前窗口。
                        val editorHeightNow =
                            (editorHeight ?: defaultEditorHeight)
                                .coerceIn(minEditorHeight, maxEditorHeight)
                        Column(modifier = Modifier.fillMaxSize()) {
                            AnalysisEditorPanel(
                                sql = sql,
                                onSqlChange = onSqlChange,
                                running = running,
                                onRun = onRun,
                                modifier =
                                    Modifier
                                        .height(editorHeightNow)
                                        .testTag(ANALYSIS_EDITOR_PANEL_TAG),
                            )
                            HorizontalDragHandle(
                                height = editorHeightNow,
                                onHeightChange = { editorHeight = it },
                                defaultHeight = defaultEditorHeight,
                                minHeight = MIN_ANALYSIS_EDITOR_HEIGHT,
                                maxHeight = maxEditorHeight,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                highlightColor = MaterialTheme.colorScheme.primary,
                                hitHeight = ANALYSIS_EDITOR_HANDLE_HEIGHT,
                                modifier = Modifier.testTag(ANALYSIS_EDITOR_DRAG_HANDLE_TAG),
                                // 悬停时换「上下拉伸」指针：分隔条在鼠标下必须看起来可拖
                                hoverIcon = editorPaneHoverIcon,
                            )
                            AnalysisResultPanel(
                                result = result,
                                // 一个远端源都没有时，空态要说的是「去建连接」而不是「去跑一条 SQL」——
                                // 没有源就拉不到任何表，跑出来的结果只能是工作区里原有的东西。
                                noSources = sources.isEmpty(),
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .testTag(ANALYSIS_RESULT_PANEL_TAG),
                            )
                        }
                    }
                    AnalysisStatusBar(result = result)
                }
            }
        }
    }
}

/** 结果表容器的测试标记 —— 把「结果区域」与编辑器区分开。 */
const val ANALYSIS_RESULT_TABLE_TAG: String = "analysisResultTable"

/** 「运行」按钮的测试标记。 */
const val ANALYSIS_RUN_BUTTON_TAG: String = "analysisRunButton"

/** 「添加数据源」按钮的测试标记。 */
const val ANALYSIS_ADD_SOURCE_BUTTON_TAG: String = "analysisAddSourceButton"

/** 左树的默认宽度 —— 双击分隔条即复位到这个值。 */
val ANALYSIS_TREE_PANEL_DEFAULT_WIDTH: Dp = 240.dp

/** 左树宽度的下限 —— 再窄就看不到表名了。 */
val ANALYSIS_TREE_PANEL_MIN_WIDTH: Dp = 180.dp

/** 左树面板的 UI 测试 tag —— 面板本身无语义，量宽度得靠它。 */
const val ANALYSIS_TREE_PANEL_TAG: String = "analysisTreePanel"

/** 左树分隔条的 UI 测试 tag —— 拖拽测试需要一个明确的落点，不能靠坐标猜。 */
const val ANALYSIS_TREE_DRAG_HANDLE_TAG: String = "analysisTreeDragHandle"

/**
 * 左树**列表容器**的 UI 测试 tag —— 滚动手势要有一个明确的落点。
 *
 * 为什么不用 `hasScrollAction()` 找：分析屏上还有别的能滚的东西
 * （编辑器里的长 SQL 会自己滚），按能力找会撞上好几个节点。
 */
const val ANALYSIS_TREE_LIST_TAG: String = "analysisTreeList"

/** 编辑器面板的 UI 测试 tag —— 拖拽测试要量「编辑器现在多高」。 */
const val ANALYSIS_EDITOR_PANEL_TAG: String = "analysisEditorPanel"

/** 编辑器与结果表之间那条分隔条的 UI 测试 tag —— 拖拽要有个明确的落点。 */
const val ANALYSIS_EDITOR_DRAG_HANDLE_TAG: String = "analysisEditorDragHandle"

/**
 * 结果**面板**（面板头 + 表）的 UI 测试 tag。
 *
 * 为什么与 [ANALYSIS_RESULT_TABLE_TAG] 分成两个：前者量的是**结果区这一块**，
 * 后者量的是结果区里**放表格的那一块**（不含面板头）。
 * 「结果区有没有被拖到低于它的下限」问的是前者。
 */
const val ANALYSIS_RESULT_PANEL_TAG: String = "analysisResultPanel"

/**
 * 编辑器面板与结果面板的**原始权重比** —— 0.42 : 1。
 *
 * 权重不再直接用于布局（现在是「编辑器固定高度 + 结果表占剩余」），
 * 但这两个数字仍是**默认比例**的来源 —— 见 [EDITOR_PANEL_DEFAULT_RATIO]。
 */
private const val EDITOR_PANEL_WEIGHT = 0.42f

/** 结果面板的原始权重。 */
private const val RESULT_PANEL_WEIGHT = 1f

/**
 * 编辑器在「编辑器 + 结果表」这一段里占的比例 —— 由权重比换算而来。
 *
 * 为什么不直接写死 0.3：权重是**相对**的（0.42 : 1 说的是「编辑器 : 结果表」），
 * 换算成「编辑器占总数的几分之几」才是布局真正要的数字。
 * 两处各写一个数字，改一处忘了另一处，比例就会悄悄漂掉。
 */
private const val EDITOR_PANEL_DEFAULT_RATIO = EDITOR_PANEL_WEIGHT / (EDITOR_PANEL_WEIGHT + RESULT_PANEL_WEIGHT)

/**
 * 分析模式编辑器的**最小高度** —— 再矮就只剩面板头与工具栏，看不见几行 SQL。
 *
 * 120dp = 面板头（约 26dp）+ 工具栏（约 40dp）+ 两行代码。
 */
private val MIN_ANALYSIS_EDITOR_HEIGHT = 120.dp

/**
 * 分析模式结果表的**最小高度** —— 表头 + 几行数据 + 分页栏。
 *
 * 与编辑器抢高度时**优先保结果表**：结果只有几行时结果表也用不满，
 * 但「一行都看不见」是彻底的失败，而「编辑器矮一点」它内部能滚。
 *
 * 它同时是**拖拽的硬边界** —— 编辑器最多长到「总高 - 这个下限」，拖不下去。
 * 公开出来是因为它是一条**契约**（测试要断言「结果表没被拖到低于它」），
 * 而 :desktopApp 的测试看不到 :shared 的 `internal`。
 */
val ANALYSIS_RESULT_PANEL_MIN_HEIGHT: Dp = 140.dp

/** 分析模式结果表的最小高度（内部名，布局算式里用）。 */
private val MIN_ANALYSIS_RESULT_HEIGHT: Dp = ANALYSIS_RESULT_PANEL_MIN_HEIGHT

/**
 * 编辑器与结果表之间那条分隔条的**命中区高度** —— 画线仍只有 1dp。
 *
 * 与布局算式绑定（总高 = 编辑器 + 这条 + 结果表），
 * 所以写成一个常量而不是就地写 8.dp：两处各写一个数字，迟早对不上。
 */
private val ANALYSIS_EDITOR_HANDLE_HEIGHT = 8.dp

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
 *
 * ## 树用的是**与浏览模式同一个**组件
 *
 * `com.langbiantianya.sundays.tree.ObjectTree` —— 库 → 表 → 字段，
 * 与浏览模式的对象树一模一样（同一个组件、同一套画法）。
 * 分析模式以前自己画了一棵树（`SourceTree`），两边的扭子、行高、
 * 「表能不能展开」全不一样；统一之后，用户在两个模式里看到的是同一棵树。
 *
 * ## 面板外壳也是**与浏览模式同一个**
 *
 * [TreePaneShell] —— 折叠到侧边（窄轨）与拖拽调宽。
 * 两侧的树面板因此有一模一样的手感，不会出现「浏览模式能拖、分析模式不能拖」。
 */
@Composable
private fun AnalysisTreePanel(
    roots: List<ObjectTreeNode>,
    expandedNodeIds: Set<String>,
    onToggleNode: (ObjectTreeNode) -> Unit,
    onSelectTable: (ObjectTreeNode) -> Unit,
    onSelectColumn: (ObjectTreeNode) -> Unit,
    onAddSource: () -> Unit,
    onCollapse: () -> Unit,
    loading: Boolean,
    error: String?,
    scrollState: ScrollState,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
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
                // 「+」—— **添加数据源**。
                //
                // 它住在树的**上边**（树面板的头部），因为它加的东西**就是树上的东西**：
                // 加一个源 → 树上多一根 → 展开就是它的表与字段。
                //
                // 这个按钮**不共用**浏览模式的「添加连接」：那条路存的是
                // `connection.json`（浏览模式的连接清单），而分析模式的数据源
                // 存在**工作区 DuckDB 里**（它自己的清单）。共用一份表单
                // 只会让用户以为两边是同一件事 —— 而它们是**两套会话**。
                WinIconButton(
                    onClick = onAddSource,
                    modifier = Modifier.testTag(ANALYSIS_ADD_SOURCE_BUTTON_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = str(StrKey.ANALYSIS_ADD_SOURCE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // 「收起左树」—— 与浏览模式的树面板头**同一个按钮**
                // （`TreePaneToggleButton`）：两侧的手感必须一致。
                Spacer(modifier = Modifier.width(2.dp))
                TreePaneToggleButton(collapsed = false, onClick = onCollapse)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(modifier = Modifier.weight(1f)) {
            // 滚动容器归这里 —— 共享树组件只画内容，不自带滚动。
            // 浏览模式把树放进 `LazyColumn` 的每一项里，容器不一样。
            //
            // 滚动状态由**外面**持有（`AnalysisScreen` 那一层）——
            // 折叠时整棵树会从组合里移除，记在这里的状态会跟着没。
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .testTag(ANALYSIS_TREE_LIST_TAG),
            ) {
                ObjectTree(
                    roots = roots,
                    expandedIds = expandedNodeIds,
                    onToggle = onToggleNode,
                    onSelectTable = onSelectTable,
                    onSelectColumn = onSelectColumn,
                    // 转圈是**无限动画**，会让「等界面空闲」的 UI 测试永远等不到 ——
                    // 分析模式的加载是一瞬的事，用不着它。
                    showLoadingSpinner = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
