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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.table.TableCopyMenuItems
import com.langbiantianya.sundays.table.rememberContextMenuState
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.TreeContextMenu
import com.langbiantianya.sundays.tree.TreeContextMenuItem
import com.langbiantianya.sundays.tree.TreeContextMenuState
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.HorizontalDragHandle
import com.langbiantianya.sundays.ui.SIDE_PANE_MAX_WIDTH_RATIO
import com.langbiantianya.sundays.ui.SidePaneShell
import com.langbiantianya.sundays.ui.SidePaneToggleButton
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.plural
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
 * @param deletableSourceNames 登记在册的数据源名（= 它们在工作区里占的 schema 名）。
 *   只有列在这里的才给「删除数据源」这一项 —— 工作区自己的 `main`
 *   与用户手工建的 schema 都不在其中（判据见 [analysisDeleteSourceTarget]）
 * @param onDeleteSource 「删除数据源」被**确认**时回调，参数是那个 schema 名。
 *   真正动手的是 desktopApp（`DROP SCHEMA … CASCADE` + 从登记表里删掉那一行）——
 *   `:shared` 看不见工作区，也不该知道「数据源」在磁盘上是什么
 * @param deleteSourceOutcome 最近一次「删除数据源」的**结果**；
 *   `null` = 还没删过。画在树面板的脚上（成功是常态色、失败是红色）
 * @param sql 编辑器里的当前 SQL
 * @param onSqlChange 编辑器内容变化时回调
 * @param result 最近一次查询的结果；`null` = 还没跑过
 * @param running 正在跑 —— 界面据此禁用「运行」按钮
 * @param onRun 「运行」被点时回调
 * @param exportStatus 「导出查询结果」的**当前状态**；`null` = 没导过。
 *   画在底栏 —— 导出可能跑好几秒，用户必须能看见「在跑」与「跑完了没有」，
 *   否则「点了导出然后什么也没发生」会和真失败分不清
 * @param onExport 「导出」被点时回调 —— 打开导出对话框（格式 / 路径 / 预览都在那儿）
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
    deletableSourceNames: Set<String> = emptySet(),
    onDeleteSource: (String) -> Unit = {},
    deleteSourceOutcome: DeleteSourceOutcome? = null,
    sql: String = "",
    onSqlChange: (String) -> Unit = {},
    result: AnalysisResult? = null,
    running: Boolean = false,
    onRun: () -> Unit = {},
    exportStatus: AnalysisExportStatus? = null,
    onExport: () -> Unit = {},
    /**
     * 把文本写进**系统剪贴板** —— 返回 `false` = 写不进去（无头环境 / 剪贴板被占用）。
     *
     * ## 为什么它是**必填**参数而不是给个默认值
     *
     * 剪贴板是**平台专属**能力（`java.awt`，见 `desktopApp` 的 `ClipboardWriter`），
     * `:shared` 的 `commonMain` 里没有 `java.*` —— 所以只能回调出去。
     *
     * 但**不能给默认值**：默认值（比如 `{ false }`）意味着「忘了接线」这件事
     * 在编译期看不出来，界面上却摆着一个**点了必然失败**的「复制」菜单项 ——
     * 而「宁可少给，不给一个点了必然失败的东西」是这个仓库的明确原则。
     * 必填参数把那条线画在**编译期**。
     */
    onCopyToClipboard: (String) -> Boolean,
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

    // 「删除数据源」的**待确认对象** —— 右键菜单点下去只**记下**它，
    // 真正删之前要用户确认（删掉的是一整个 schema 里的表，不可恢复）。
    //
    // 为什么状态住在**屏幕层**而不是树面板里：树面板在**折叠时整体从组合里移除**
    // （`SidePaneShell` 的窄轨只有 44dp，塞不下树）—— 确认框要是跟着面板走，
    // 用户折叠一下树，正在确认的删除就**无声消失**了（而删除本身还没发生）。
    var pendingDeleteSource by remember { mutableStateOf<String?>(null) }

    // 「复制查询结果」的**终态** —— 复制是**一瞬间**的事，所以状态住在屏幕层就够
    // （切走再回来消息没了无所谓 —— 那种**跨页存活**的动作才需要进会话，
    // 见 `AnalysisSession.exportStatus`）。
    //
    // 为什么要留着它：剪贴板有三条真实的失败路径（无头环境 / 被别的进程占着 /
    // X11 下服务不可用），失败时用户点了菜单**什么都不会发生** ——
    // 界面必须**看得见**这件事，否则那就是「点了必然失败的东西」。
    var copyStatus by remember { mutableStateOf<AnalysisCopyStatus?>(null) }

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
            val maxPaneWidth = maxWidth * SIDE_PANE_MAX_WIDTH_RATIO
            Row(modifier = Modifier.fillMaxSize()) {
                SidePaneShell(
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
                        // 右键菜单：**只有**属于登记在册数据源的节点才给删除项。
                        deletableSourceNames = deletableSourceNames,
                        onRequestDeleteSource = { pendingDeleteSource = it },
                        deleteSourceOutcome = deleteSourceOutcome,
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
                                exporting = exportStatus is AnalysisExportStatus.Running,
                                onExport = onExport,
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
                                // 复制是**平台能力**（剪贴板），由调用方注入。
                                onCopyToClipboard = onCopyToClipboard,
                                onCopied = { count, ok ->
                                    copyStatus =
                                        if (ok) {
                                            AnalysisCopyStatus.Copied(count)
                                        } else {
                                            AnalysisCopyStatus.Failed
                                        }
                                },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .testTag(ANALYSIS_RESULT_PANEL_TAG),
                            )
                        }
                    }
                    AnalysisStatusBar(
                        result = result,
                        exportStatus = exportStatus,
                        copyStatus = copyStatus,
                    )
                }
            }
        }

        // 「删除数据源」的确认框 —— 画在**树面板之外**（见上面那条注释）。
        //
        // 为什么要有确认：删掉的是**整个 schema**（连同里面所有拉进来的表），
        // 而 DuckDB 里没有撤销。误触一次的代价是用户重新拉一遍数据 ——
        // 远端表可能几十上百兆。
        pendingDeleteSource?.let { schema ->
            AlertDialog(
                onDismissRequest = { pendingDeleteSource = null },
                // 标题是**问句**、正文是**后果** —— 与浏览模式「删除表」那个框同构。
                title = { Text(str(StrKey.ANALYSIS_DELETE_SOURCE_CONFIRM, schema)) },
                text = { Text(str(StrKey.ANALYSIS_DELETE_SOURCE_CONFIRM_HINT)) },
                confirmButton = {
                    WinButton(
                        onClick = {
                            // 先关掉确认框再动手：删除是**异步**的
                            // （要发引擎往返），确认框杵在那里会挡住用户的下一次操作。
                            pendingDeleteSource = null
                            onDeleteSource(schema)
                        },
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(ANALYSIS_DELETE_SOURCE_CONFIRM_TAG),
                    ) {
                        Text(str(StrKey.ANALYSIS_DELETE_SOURCE_CONFIRM_BTN), maxLines = 1, softWrap = false)
                    }
                },
                dismissButton = {
                    WinTextButton(
                        onClick = { pendingDeleteSource = null },
                        shape = SundaysPalette.buttonShape,
                    ) {
                        Text(str(StrKey.CANCEL), maxLines = 1, softWrap = false)
                    }
                },
            )
        }
    }
}

/** 结果表容器的测试标记 —— 把「结果区域」与编辑器区分开。 */
const val ANALYSIS_RESULT_TABLE_TAG: String = "analysisResultTable"

/** 「运行」按钮的测试标记。 */
const val ANALYSIS_RUN_BUTTON_TAG: String = "analysisRunButton"

/** 「导出」按钮的测试标记。 */
const val ANALYSIS_EXPORT_BUTTON_TAG: String = "analysisExportButton"

/** 底栏里那一行导出状态的测试标记。 */
const val ANALYSIS_EXPORT_STATUS_TAG: String = "analysisExportStatus"

/** 底栏里那一行复制状态的测试标记。 */
const val ANALYSIS_COPY_STATUS_TAG: String = "analysisCopyStatus"

/** 「添加数据源」按钮的测试标记。 */
const val ANALYSIS_ADD_SOURCE_BUTTON_TAG: String = "analysisAddSourceButton"

/** 分析树右键菜单里「删除数据源」那一项的测试标记。 */
const val ANALYSIS_MENU_DELETE_SOURCE_TAG: String = "analysisMenuDeleteSource"

/** 「删除数据源」确认框里那个「删除」按钮的测试标记。 */
const val ANALYSIS_DELETE_SOURCE_CONFIRM_TAG: String = "analysisDeleteSourceConfirm"

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
 * 编辑器面板 —— 面板头 + SQL 编辑器（**工具条上带「格式化」「导出」与「运行」**）。
 *
 * ## 为什么固定成 DuckDB 方言
 *
 * 分析 SQL **只在本地工作区上跑**（引擎的 `RUN_QUERY` 只允许打在
 * `EMBEDDED_MODE` 的方言上），而工作区就是 DuckDB。
 * 所以编辑器的语言是 `sql-duckdb`，而且**隐藏语言切换器** ——
 * 摆一个能让用户切到 Lua 的下拉框，只会让「这条 SQL 到底打在哪」变得含糊。
 *
 * ## 为什么「运行」「导出」跟「格式化」同一行
 *
 * 它俩都是**对编辑器里这段 SQL 的动作** —— 分开放两行就让人多扫一眼，
 * 而且分析模式原来那条工具条（只读徽标 + 运行）本来就是一行没别的内容的行，
 * 去掉徽标之后**整行都空了**。
 *
 * ## 为什么「导出」紧挨着「运行」
 *
 * 两个动作都作用于**编辑器里这段 SQL**，而且用户常常是「先跑一下看看，
 * 再把它导出去」—— 挨在一起才像一组。
 * 「导出」在左边、「运行」在右边：运行是**看一眼**（快、常用），
 * 导出是**拿走**（慢、要挑地方），前者更常用就该更靠右手。
 */
@Composable
private fun AnalysisEditorPanel(
    sql: String,
    onSqlChange: (String) -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    exporting: Boolean,
    onExport: () -> Unit,
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
                // ⚠️ 导出中禁掉：工作区是**一个** DuckDB 文件，
                // 并发跑两条查询会互相抢锁；导出时更不能再来一条查询。
                WinButton(
                    onClick = onExport,
                    enabled = !exporting && sql.isNotBlank(),
                    modifier = Modifier.testTag(ANALYSIS_EXPORT_BUTTON_TAG),
                ) {
                    Text(text = str(StrKey.ANALYSIS_EXPORT))
                }
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
 * [SidePaneShell] —— 折叠到侧边（窄轨）与拖拽调宽。
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
    deletableSourceNames: Set<String>,
    onRequestDeleteSource: (String) -> Unit,
    deleteSourceOutcome: DeleteSourceOutcome?,
    onCollapse: () -> Unit,
    loading: Boolean,
    error: String?,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    // 右键菜单的状态 —— 与浏览模式**同一份机制**（`:shared` 的 `TreeContextMenuState`）。
    //
    // 目标类型是**数据源的 schema 名**：分析模式的树只有「删除数据源」这一个动作，
    // 没有浏览模式那六项，所以不必把整个节点传出去。
    val treeMenu = remember { TreeContextMenuState<String>() }

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
                // （`SidePaneToggleButton`）：两侧的手感必须一致。
                Spacer(modifier = Modifier.width(2.dp))
                SidePaneToggleButton(collapsed = false, onClick = onCollapse)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    // Popup 的 parent 就是这个 Box，而 `Popup.offset` 相对 parent ——
                    // 所以菜单要把 root 坐标换算成面板内坐标，就靠这里上报的 origin。
                    .onGloballyPositioned { treeMenu.updateHostOrigin(it.positionInRoot()) },
        ) {
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
                    // 右键 —— 判据只有一条：**这个节点属于一个登记在册的数据源**。
                    //
                    // 不属于就**不弹菜单**：弹一个空菜单比不弹更糟 ——
                    // 用户会以为程序卡住了。工作区自己的表（`main`）就属于这一类。
                    onRightClickNode = { node, offset ->
                        analysisDeleteSourceTarget(node, deletableSourceNames)?.let { schema ->
                            treeMenu.showAt(offset, schema)
                        }
                    },
                    // 转圈是**无限动画**，会让「等界面空闲」的 UI 测试永远等不到 ——
                    // 分析模式的加载是一瞬的事，用不着它。
                    showLoadingSpinner = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 右键菜单的**弹层** —— 与浏览模式**同一个组件**
            // （`:shared` 的 `TreeContextMenu`），所以两侧的定位与手感必然一致。
            //
            // 菜单项**只有一条**：删除数据源。分析模式的树没有别的动作 ——
            // 复制引用是**点**字段就干的事（`onSelectColumn`），不需要菜单。
            TreeContextMenu(menu = treeMenu) { schema ->
                TreeContextMenuItem(
                    treeMenu,
                    ANALYSIS_MENU_DELETE_SOURCE_TAG,
                    // 菜单项要写清**删的是哪一个** —— 树上可能有好几个数据源，
                    // 光写「删除数据源」用户得自己猜是哪一张表背后的那个。
                    str(StrKey.ANALYSIS_DELETE_SOURCE, schema),
                ) {
                    onRequestDeleteSource(schema)
                }
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

        // 「删除数据源」的**结果** —— 单独一行，放在树**下面**、树脚**上面**。
        //
        // 为什么成功也要说一声：删除是**不可撤销**的（整根 schema 连同里面的表
        // 一起没了），用户需要确认「刚才那一下真的生效了」。
        // 失败则是**必须**看得见的 —— 用户点了，却什么都没发生。
        //
        // 为什么与树脚那行「加载失败」**分开**：那是加载失败，这是操作失败 ——
        // 混成一句「加载失败：删除数据源失败：xxx」就没法读了。
        val outcomeText: Pair<String, Boolean>? =
            when (val outcome = deleteSourceOutcome) {
                null -> {
                    null
                }

                is DeleteSourceOutcome.Deleted -> {
                    str(StrKey.ANALYSIS_SOURCE_DELETED, outcome.schema) to false
                }

                is DeleteSourceOutcome.Failed -> {
                    str(StrKey.ANALYSIS_DELETE_SOURCE_FAILED, outcome.reason) to true
                }
            }
        if (outcomeText != null) {
            Text(
                text = outcomeText.first,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (outcomeText.second) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
 *
 * ## 结果表的**右键菜单**：复制此行 / 复制整表
 *
 * 只读表本来就有 `SelectionContainer`，单元格文本可以**拖选复制** ——
 * 右键菜单的增量是「按行 / 按整表」这两个**整块**的动作。
 *
 * 菜单的实现住在 `:shared` 的 `table/TableCopyMenu.kt`（与浏览模式的表预览**共用一份**），
 * 这里只负责把**剪贴板**这个平台能力接上去。
 */
@Composable
private fun AnalysisResultPanel(
    result: AnalysisResult?,
    noSources: Boolean,
    onCopyToClipboard: (String) -> Boolean,
    onCopied: (rowCount: Int, ok: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisPanelHead(
            title = str(StrKey.ANALYSIS_RESULT_ROWS, result?.rows?.size ?: 0),
            sub = result?.let { str(StrKey.ANALYSIS_ELAPSED, it.elapsedMs) },
        )
        // 右键菜单的状态 —— **建在 `when` 之外**：
        // 放在分支里的话，条件一变（跑失败 → 跑成功）这个 state 会被丢掉，
        // 而菜单正开着的时候恰恰不该把它丢掉。
        val contextMenuState = rememberContextMenuState()
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
                    val columns = analysisTableColumns(result)
                    val rows = analysisTableRows(result)
                    DataTable(
                        columns = columns,
                        rows = rows,
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
                        // 右键菜单：复制此行 / 复制整表。
                        // 菜单**状态**由这里创建并交给菜单组件 —— 菜单项点完要**自己把菜单关掉**
                        // （菜单不会因为「项被点了」而自动关闭），而关掉它的正是这个 state。
                        contextMenuState = contextMenuState,
                        contextMenuItems = { row ->
                            TableCopyMenuItems(
                                columns = columns,
                                rows = rows,
                                target = row,
                                menu = contextMenuState,
                                onCopy = onCopyToClipboard,
                                onCopied = onCopied,
                            )
                        },
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
 * 底栏 —— 行数与引擎，外加**导出状态**与**复制状态**。
 *
 * ## 为什么**不**写「只读会话」
 *
 * 设计稿的状态栏写的是「引擎 DuckDB · 只读会话」——
 * 但那是**远端**会话的只读，而这一栏说的是**工作区**（本地 DuckDB 文件）。
 * 工作区是**可写**的（M4 的验收明确要求 `CREATE TABLE tmp AS SELECT …` 能成功）。
 * 在这儿写「只读会话」就是**假信息**，会让用户不敢在工作区里建表。
 *
 * ## 为什么导出状态放在**底栏**而不是顶部的进度条
 *
 * 顶部的横贯带子已经给了「拉取」（那是**跨树与主区**的动作）。
 * 导出是**编辑器自己的**动作 —— 它离编辑器近才找得到，
 * 而底栏正好贴着结果区与编辑器下沿。
 *
 * ## 为什么复制状态也在这条栏里
 *
 * 复制是**结果表**上的动作，而结果表就贴着这条栏。
 * 更要紧的是**失败必须看得见**：剪贴板写不进去时（无头环境 /
 * 被别的进程占着）用户点了菜单**什么都不会发生** ——
 * 界面不说一句，用户只会以为是自己点歪了。
 */
@Composable
private fun AnalysisStatusBar(
    result: AnalysisResult?,
    exportStatus: AnalysisExportStatus?,
    copyStatus: AnalysisCopyStatus?,
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
        AnalysisCopyStatusText(copyStatus)
        AnalysisExportStatusText(exportStatus)
        Text(
            text = str(StrKey.ANALYSIS_ENGINE, "DuckDB"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 复制状态的那一行字 —— 成功说「已复制 N 行」，失败说「复制失败」。
 *
 * ## 为什么失败是**红的**
 *
 * 成功与失败的**后果完全不同**：成功的话数据已经在剪贴板里，
 * 失败的话用户以为复制了、其实没有 —— 而他可能正拿着这份「以为复制到了」的数据
 * 去别处粘贴。失败必须**显眼**。
 */
@Composable
private fun AnalysisCopyStatusText(copyStatus: AnalysisCopyStatus?) {
    when (copyStatus) {
        null -> {
            // 没复制过 —— 不占地方（空 composable 什么都不画）
        }

        is AnalysisCopyStatus.Copied -> {
            Text(
                text = plural(PluralStrKey.COPIED_ROWS, copyStatus.rows),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ANALYSIS_COPY_STATUS_TAG),
            )
        }

        AnalysisCopyStatus.Failed -> {
            Text(
                text = str(StrKey.COPY_TABLE_FAILED),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ANALYSIS_COPY_STATUS_TAG),
            )
        }
    }
}

/**
 * 导出状态的那一行字。
 *
 * ## 为什么「正在导出」时不写「0 行」
 *
 * 「0 行」会被读成「导出失败」—— 引擎启动与第一帧之间有延迟，
 * 那段时间里「0 行」是**假信息**。所以还没收到第一帧时只说「正在导出」。
 */
@Composable
private fun AnalysisExportStatusText(exportStatus: AnalysisExportStatus?) {
    when (exportStatus) {
        null -> {
            // 没导过 —— 不占地方（空 composable 什么都不画）
        }

        is AnalysisExportStatus.Running -> {
            Text(
                text =
                    exportStatus.rows?.let {
                        plural(PluralStrKey.EXPORT_PROGRESS_ROWS, it.toInt(), it)
                    } ?: str(StrKey.ANALYSIS_EXPORT_RUNNING),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ANALYSIS_EXPORT_STATUS_TAG),
            )
        }

        is AnalysisExportStatus.Succeeded -> {
            Text(
                text = str(StrKey.ANALYSIS_EXPORT_DONE, exportStatus.rowsWritten.toString(), exportStatus.file),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(ANALYSIS_EXPORT_STATUS_TAG),
            )
        }

        is AnalysisExportStatus.Failed -> {
            Text(
                text = str(StrKey.ANALYSIS_EXPORT_FAILED, exportStatus.reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(ANALYSIS_EXPORT_STATUS_TAG),
            )
        }
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
