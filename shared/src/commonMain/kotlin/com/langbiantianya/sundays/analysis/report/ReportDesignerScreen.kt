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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.analysisTableColumns
import com.langbiantianya.sundays.analysis.analysisTableRows
import com.langbiantianya.sundays.analysis.appendAnalysisColumnRef
import com.langbiantianya.sundays.analysis.appendAnalysisTableRef
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.ui.ConfirmDialog
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.PaneEdge
import com.langbiantianya.sundays.ui.SidePaneShell
import com.langbiantianya.sundays.ui.SidePaneToggleButton
import com.langbiantianya.sundays.ui.TabRenameDialog
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.str

/*
 * **报表设计页面** —— 一张报表由若干**部件**组成，每个部件就是
 * 「一条 SQL + 一种图表类型」。
 *
 * ## 布局为什么是「左边数据树 + 中间画布 + 最右边报表列表」
 *
 * 三块各司其职，位置由「它是**哪一类**东西」决定：
 *
 * - **数据树在左**：它是一份**输入**（有哪些表、哪些字段）。
 *   输入放左边 —— 与浏览模式、分析工作台**同一个方向**，
 *   用户不用为报表设计重新建立空间感。
 * - **画布在中**：它是**主产物**（部件一块块往下排），占最大的一块地方。
 * - **报表列表在最右**：它是**另一类产物**（一张张报表）。
 *   放在最右边，它「不参与画布的编辑」这件事一眼看得出来。
 *
 * ## 为什么两侧都能**折叠收纳**
 *
 * 这一页有三样东西：输入、主区、产物。用户在看画布的时候，
 * 输入多半已经用完了（树只在「要起一个部件」时才想起），
 * 产物也只是**看一眼**（确认选中的是哪张报表）。
 *
 * 而两块都摊开时，画布被挤到中间窄窄一条 —— 可画布才是这一页的主角。
 * 所以两侧都给一个「收起来」的按钮：树收成**窗口最左**的一条窄轨，
 * 报表列表收成**窗口最右**的一条窄轨。
 *
 * 手感与浏览模式 / 分析工作台**完全一致** —— 同一个 `SidePaneShell`，
 * 连折叠按钮的箭头方向都是同一套（往贴边的那一侧收）。
 *
 * ## 为什么中间还要插一棵**数据树**
 *
 * 部件的形状约定是「第一列类别、第二列数值」，
 * 而写出一条符合这个约定的 SQL，前提是知道**有哪些表、哪些字段**。
 * 没有树的话，用户只能切回工作台把名字**记下来**，再切回来凭记忆敲 ——
 * 敲错了要等**跑 SQL** 时才报错。
 *
 * 所以树摆在报表设计这一页里：点一张表，就**开一个新部件**并把引用填好，
 * 用户接着改聚合、改标题就行 —— 不用记、不用抄。
 *
 * ## 部件为什么排成**纵向列表**而不是网格
 *
 * 网格要求每个部件有**固定**的宽高 —— 而部件的内容是 SQL 查出来的，
 * 形状不可控（一张表可能有 3 列也可能有 30 列）。纵向列表让每个部件
 * 自己决定高度：一行一行往下排，宽窄由数据说话。
 */

/** 报表设计页面里各处的 UI 测试 tag。 */
object ReportDesignerTags {
    /** 报表列表。 */
    const val REPORT_LIST = "reportList"

    /** 当前报表的部件画布。 */
    const val REPORT_CANVAS = "reportCanvas"

    /** 某个部件卡片（tag = 部件 id）。 */
    const val WIDGET_CARD_PREFIX = "reportWidgetCard_"

    /** 某个部件的「编辑」按钮（tag = 部件 id）。 */
    const val WIDGET_EDIT_PREFIX = "reportWidgetEdit_"

    /** 某个部件的「删除」按钮（tag = 部件 id）。 */
    const val WIDGET_DELETE_PREFIX = "reportWidgetDelete_"

    /** 某张报表的「重命名」按钮（tag = 报表 id）。 */
    const val REPORT_ITEM_RENAME_PREFIX = "reportItemRename_"

    /** 某张报表的「删除」按钮（tag = 报表 id）。 */
    const val REPORT_ITEM_DELETE_PREFIX = "reportItemDelete_"

    /** 报表画布上的「刷新」按钮。 */
    const val REFRESH_BUTTON = "reportRefreshBtn"

    /** 画布上那棵**数据树**的面板。 */
    const val DATA_TREE = "reportDesignerDataTree"

    /**
     * 数据树折叠后的**窄轨**（贴在窗口**最左**）。
     *
     * 与报表列表的窄轨**各给各的**：同一屏上有两条窄轨，
     * 共用一个 tag 的话「按 tag 找窄轨」会找到两个。
     */
    const val DATA_TREE_RAIL = "reportDesignerDataTreeRail"

    /** 数据树的「折叠 / 展开」按钮。 */
    const val DATA_TREE_TOGGLE = "reportDesignerDataTreeToggle"

    /** 数据树与画布之间那条**可拖的分隔条**。 */
    const val DATA_TREE_DRAG_HANDLE = "reportDesignerDataTreeDragHandle"

    /**
     * 报表列表折叠后的**窄轨**（贴在窗口**最右**）。
     *
     * 与数据树的窄轨**各给各的** —— 理由同上。
     */
    const val REPORT_LIST_RAIL = "reportDesignerReportListRail"

    /** 报表列表的「折叠 / 展开」按钮。 */
    const val REPORT_LIST_TOGGLE = "reportDesignerReportListToggle"

    /** 报表列表与画布之间那条**可拖的分隔条**。 */
    const val REPORT_LIST_DRAG_HANDLE = "reportDesignerReportListDragHandle"

    /** 报表设计页面本体。 */
    const val DESIGNER = "reportDesigner"
}

/**
 * 报表列表面板的**默认**宽度 —— 220dp。
 *
 * ## 为什么是「默认」而不是「唯一」
 *
 * 报表列表住在**可拖宽的侧边面板**里（`SidePaneShell`）——
 * 用户拖出来的宽度是他要的，不该被布局按回去。
 */
val REPORT_LIST_PANEL_DEFAULT_WIDTH: Dp = 220.dp

/**
 * 报表列表面板的**宽度下限** —— 170dp。
 *
 * 一行要放得下「报表名 + 重命名 + 删除」三个东西 —— 再窄就要溢出了，
 * 而溢出的话重命名与删除的按钮会**点不到**。
 */
val REPORT_LIST_PANEL_MIN_WIDTH: Dp = 170.dp

/**
 * 报表设计页里**每块**侧边面板的宽度上限 —— 窗口宽的 32%。
 *
 * ## 为什么不是共享的那个 [SIDE_PANE_MAX_WIDTH_RATIO]（62%）
 *
 * 那个上限是给**只有一块**侧边面板的界面用的（浏览模式 / 分析工作台）——
 * 62% 留给主区还剩 38%，够主区用。
 *
 * 这一页有**两块**：各占 62% 的话两块加起来 124%，画布被挤成 0 宽 ——
 * 而画布才是这一页的主角（部件全画在它上面）。
 * 32% × 2 = 64%，剩下的 36% 留给画布 —— 与「一块面板占 62%」的余量同一个量级。
 */
private const val REPORT_DESIGNER_PANE_MAX_WIDTH_RATIO: Float = 0.32f

/**
 * 报表设计页面。
 *
 * ## 这一层不碰引擎
 *
 * 与分析工作台同一条规矩：`:shared` 只接收**已经算好的**数据、把用户动作
 * 回调出去。「跑一条部件 SQL」由 desktopApp 侧决定（它认识引擎与工作区）。
 *
 * @param reports 全部报表
 * @param selectedReportId 当前选中的报表 id；`null` = 还没选
 * @param widgetResults 部件 id → 该部件的数据（跑过一次才有）
 * @param previewResult 编辑页里那条 SQL 的**样例数据**；`null` = 还没预览
 * @param previewRunning 正在取样例数据
 * @param treeRoots 数据树的根节点（工作区 + 已经拉进来的数据源）
 * @param expandedNodeIds 当前展开着的树节点 id
 * @param onToggleNode 树的某个节点被展开 / 收起时回调
 * @param onAddSource 「添加数据源」被点时回调
 * @param onSelectReport 选中某张报表
 * @param onCreateReport 新建一张报表
 * @param onRenameReport 重命名报表
 * @param onDeleteReport 删除报表
 * @param onSaveWidget 保存部件（参数：报表 id、部件、是不是新增）
 * @param onDeleteWidget 删除部件（参数：报表 id、部件 id）
 * @param onPreviewSql 预览一条 SQL 的样例数据
 * @param onRefreshReport 重新取一张报表里所有部件的数据
 * @param refreshRunning 正在刷新 —— 界面据此禁用「刷新」按钮
 * @param paneHoverIcon 两侧分隔条悬停时的**指针图标**。
 *   `null` = 不改指针。指针图标是**平台专属**资源（桌面端是 AWT 的
 *   `E_RESIZE_CURSOR`），公共 Compose API 造不出来 —— 与仓库既有的分界一致。
 *   两侧**共用一份**：左右拉伸的箭头只有一个，左右之分由分隔条自己的位置决定
 * @param modifier 布局修饰
 */
@Composable
fun ReportDesignerScreen(
    reports: List<AnalysisReport>,
    selectedReportId: String?,
    widgetResults: Map<String, AnalysisResult> = emptyMap(),
    previewResult: AnalysisResult? = null,
    previewRunning: Boolean = false,
    refreshRunning: Boolean = false,
    treeRoots: List<ObjectTreeNode> = emptyList(),
    expandedNodeIds: Set<String> = emptySet(),
    onToggleNode: (ObjectTreeNode) -> Unit = {},
    onAddSource: () -> Unit = {},
    onSelectReport: (String) -> Unit = {},
    onCreateReport: () -> Unit = {},
    onRenameReport: (String, String) -> Unit = { _, _ -> },
    onDeleteReport: (String) -> Unit = {},
    onSaveWidget: (String, ReportWidget, Boolean) -> Unit = { _, _, _ -> },
    onDeleteWidget: (String, String) -> Unit = { _, _ -> },
    onPreviewSql: (String) -> Unit = {},
    onRefreshReport: (AnalysisReport) -> Unit = {},
    paneHoverIcon: PointerIcon? = null,
    modifier: Modifier = Modifier,
) {
    // 正在编辑的部件 —— `null` = 没在编辑。
    //
    // 状态住在**屏幕层**（这个 composable 里）而不是会话里：编辑中的东西是
    // 「还没保存的草稿」，会话只该存**已经存下来的**报表。
    var editingWidget by remember { mutableStateOf<ReportWidget?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    // 重命名与删除的**待确认对象** —— 与分析模式删数据源一个做法：
    // 先记下对象，真正动手之前要让用户确认。
    var renamingReportId by remember { mutableStateOf<String?>(null) }
    var deletingReportId by remember { mutableStateOf<String?>(null) }
    var deletingWidget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 树的**滚动位置** —— 提到面板**外面**持有。
    //
    // 为什么：画布上的树与部件编辑页里的树是**同一棵树**（同一份数据、同一份展开态），
    // 而编辑页是**盖在**画布上的覆盖层。开编辑页时画布那棵树会从组合里移除，
    // 滚动状态若记在面板里就跟着一起没了 —— 用户展开到一半的树，
    // 每开一次编辑页就要重新滚回去找。
    val treeScrollState = rememberScrollState()

    // ── 两侧面板的折叠 / 宽度状态 ──
    //
    // 为什么住在**屏幕层**（这个 composable 里）而不是会话里：
    // 宽度是**窗口级偏好**，不是某张报表的属性 —— 切到别的顶层页再切回来，
    // 用户希望面板还是那样宽、还是那样收着。
    // （对照：报表本身、部件本身、树的展开态才是**会话**的状态。）
    var treePaneCollapsed by remember { mutableStateOf(false) }
    var treePaneWidth by remember { mutableStateOf(REPORT_DATA_TREE_DEFAULT_WIDTH) }
    var reportListCollapsed by remember { mutableStateOf(false) }
    var reportListWidth by remember { mutableStateOf(REPORT_LIST_PANEL_DEFAULT_WIDTH) }

    val selected = reports.firstOrNull { it.id == selectedReportId }
    val selectedIndex = reports.indexOfFirst { it.id == selectedReportId }

    Box(modifier = modifier.fillMaxSize().testTag(ReportDesignerTags.DESIGNER)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 每块侧边面板的宽度上限 —— 按**窗口宽**的比例给，
            // 不然窗口窄的时候两块侧边面板就把画布挤没了（见常量的说明）。
            val maxPaneWidth = maxWidth * REPORT_DESIGNER_PANE_MAX_WIDTH_RATIO
            Row(modifier = Modifier.fillMaxSize()) {
                // ── 左：数据树 ──
                //
                // 它是**输入**（有哪些表、哪些字段），输入在左 —— 与浏览模式、
                // 分析工作台同一个方向。折叠后收成**窗口最左**的一条窄轨。
                SidePaneShell(
                    collapsed = treePaneCollapsed,
                    onToggleCollapsed = { treePaneCollapsed = !treePaneCollapsed },
                    width = treePaneWidth,
                    onWidthChange = { treePaneWidth = it },
                    defaultWidth = REPORT_DATA_TREE_DEFAULT_WIDTH,
                    minWidth = REPORT_DATA_TREE_MIN_WIDTH,
                    maxWidth = maxPaneWidth,
                    collapsedIcon = Icons.Filled.Storage,
                    edge = PaneEdge.LEFT,
                    railTag = ReportDesignerTags.DATA_TREE_RAIL,
                    toggleTag = ReportDesignerTags.DATA_TREE_TOGGLE,
                    handleModifier = Modifier.testTag(ReportDesignerTags.DATA_TREE_DRAG_HANDLE),
                    hoverIcon = paneHoverIcon,
                ) { paneModifier ->
                    // 中间那棵**数据树** —— 点一张表就开一个新部件。
                    //
                    // 为什么这里是「开新部件」而不是「把引用插进某处」：
                    // 画布上**还没有正在写的 SQL**，引用插给谁？
                    // 所以这里最自然的动作是「拿这张表起一个部件」——
                    // 而起手的那条 SQL 由部件编辑页接手，用户在那里继续改。
                    ReportDataTreePane(
                        roots = treeRoots,
                        expandedNodeIds = expandedNodeIds,
                        onToggleNode = onToggleNode,
                        onSelectTable = { node ->
                            // 部件总得有个家 —— 一张报表都没有时先建一张。
                            //
                            // 为什么不是「提示用户先建报表」：那样这一下点击就**白点了**。
                            // 用户在报表设计里点了一张表，他要的就是「把它放进报表」，
                            // 报表还不存在只是因为还没建 —— 顺手建一张正是他要的结果。
                            if (selected == null) onCreateReport()
                            editingWidget =
                                newReportWidgetDraft(
                                    sql = appendAnalysisTableRef("", node.label, node.namespace),
                                )
                            editingIsNew = true
                        },
                        onSelectColumn = { node ->
                            // 字段的父节点就是它那张表 —— 引用要**带表名**。
                            val table = node.parentLabel ?: return@ReportDataTreePane
                            if (selected == null) onCreateReport()
                            editingWidget =
                                newReportWidgetDraft(
                                    sql = appendAnalysisColumnRef("", table, node.label, node.namespace),
                                )
                            editingIsNew = true
                        },
                        onAddSource = onAddSource,
                        hint = str(StrKey.REPORT_DATA_TREE_CANVAS_HINT),
                        paneTag = ReportDesignerTags.DATA_TREE,
                        scrollState = treeScrollState,
                        onToggleCollapsed = { treePaneCollapsed = !treePaneCollapsed },
                        toggleTag = ReportDesignerTags.DATA_TREE_TOGGLE,
                        edge = PaneEdge.LEFT,
                        modifier = paneModifier,
                    )
                }
                ReportCanvas(
                    report = selected,
                    reportIndex = selectedIndex,
                    widgetResults = widgetResults,
                    refreshRunning = refreshRunning,
                    onAddWidget = {
                        editingWidget = newReportWidgetDraft()
                        editingIsNew = true
                    },
                    onEditWidget = {
                        editingWidget = it
                        editingIsNew = false
                    },
                    onDeleteWidget = { reportId, widgetId -> deletingWidget = reportId to widgetId },
                    onRefreshReport = onRefreshReport,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                // ── 右：报表列表 ──
                //
                // 它是**另一类产物**（一张张报表），贴在**窗口最右** ——
                // 「不参与画布的编辑」这件事一眼看得出来。
                // 折叠后收成**窗口最右**的一条窄轨。
                SidePaneShell(
                    collapsed = reportListCollapsed,
                    onToggleCollapsed = { reportListCollapsed = !reportListCollapsed },
                    width = reportListWidth,
                    onWidthChange = { reportListWidth = it },
                    defaultWidth = REPORT_LIST_PANEL_DEFAULT_WIDTH,
                    minWidth = REPORT_LIST_PANEL_MIN_WIDTH,
                    maxWidth = maxPaneWidth,
                    collapsedIcon = Icons.Filled.Description,
                    edge = PaneEdge.RIGHT,
                    railTag = ReportDesignerTags.REPORT_LIST_RAIL,
                    toggleTag = ReportDesignerTags.REPORT_LIST_TOGGLE,
                    handleModifier = Modifier.testTag(ReportDesignerTags.REPORT_LIST_DRAG_HANDLE),
                    hoverIcon = paneHoverIcon,
                ) { paneModifier ->
                    ReportListPane(
                        reports = reports,
                        selectedReportId = selectedReportId,
                        onSelect = onSelectReport,
                        onCreate = onCreateReport,
                        onRename = { renamingReportId = it },
                        onDelete = { deletingReportId = it },
                        onToggleCollapsed = { reportListCollapsed = !reportListCollapsed },
                        toggleTag = ReportDesignerTags.REPORT_LIST_TOGGLE,
                        edge = PaneEdge.RIGHT,
                        modifier = paneModifier,
                    )
                }
            }
        }

        // 部件编辑页 —— **覆盖**在设计页面之上。
        //
        // 为什么是覆盖而不是另开一页：它要同时摆下「SQL 编辑器」与
        // 「样例数据 + 图表预览」，两边都要地方 —— 挤在一个小窗口里，
        // 两边都不够用。
        val editing = editingWidget
        if (editing != null) {
            ReportWidgetEditor(
                widget = editing,
                isNew = editingIsNew,
                previewResult = previewResult,
                previewRunning = previewRunning,
                // 编辑页里的树 —— 与画布上那棵**同一棵**（同一份数据与展开态），
                // 滚动位置也共用 —— 于是「打开编辑页 → 关掉」这一轮里，
                // 用户刚才展开到哪、滚到哪，都在原处。
                treeRoots = treeRoots,
                expandedNodeIds = expandedNodeIds,
                onToggleNode = onToggleNode,
                onAddSource = onAddSource,
                treeScrollState = treeScrollState,
                onPreview = onPreviewSql,
                onSave = { saved ->
                    selected?.let { onSaveWidget(it.id, saved, editingIsNew) }
                    editingWidget = null
                },
                onDismiss = { editingWidget = null },
            )
        }
    }

    // 重命名弹窗 —— 复用标签条那一个（同一个概念：给一个东西改个名字，
    // 只该有一份实现）。
    val renaming = renamingReportId?.let { id -> reports.firstOrNull { it.id == id } }
    if (renaming != null) {
        TabRenameDialog(
            initialTitle = renaming.name,
            title = str(StrKey.REPORT_RENAME),
            onConfirm = { name ->
                onRenameReport(renaming.id, name)
                renamingReportId = null
            },
            onDismiss = { renamingReportId = null },
        )
    }

    // 删除确认 —— 报表与部件都要**先问一句**（删掉的是一整张报表 / 一个部件）。
    val deletingReport = deletingReportId?.let { id -> reports.firstOrNull { it.id == id } }
    if (deletingReport != null) {
        ConfirmDialog(
            text = str(StrKey.REPORT_DELETE_CONFIRM),
            onConfirm = {
                onDeleteReport(deletingReport.id)
                deletingReportId = null
            },
            onDismiss = { deletingReportId = null },
        )
    }
    val deletingWidgetPair = deletingWidget
    if (deletingWidgetPair != null) {
        ConfirmDialog(
            text = str(StrKey.REPORT_WIDGET_DELETE_CONFIRM),
            onConfirm = {
                onDeleteWidget(deletingWidgetPair.first, deletingWidgetPair.second)
                deletingWidget = null
            },
            onDismiss = { deletingWidget = null },
        )
    }
}

/**
 * 报表**列表** —— **最右边**那一列窄条。
 *
 * ## 为什么放在最右边
 *
 * 它是**另一类产物**（一张张报表），不是画布的内容 —— 放在最右边，
 * 「这一块不参与画布的编辑」一眼看得出来。而树（输入）在最左，
 * 中间留给画布（主产物），两头的性质各不相同。
 *
 * @param reports 全部报表
 * @param selectedReportId 当前选中的报表 id
 * @param onSelect 选中某张报表
 * @param onCreate 新建报表
 * @param onRename 请求重命名（弹窗由上层管）
 * @param onDelete 请求删除（确认由上层管）
 * @param onToggleCollapsed 「折叠 / 展开」这一列
 * @param toggleTag 折叠 / 展开按钮的 UI 测试 tag
 * @param edge 这一列贴在窗口的**哪一边** —— 决定折叠按钮的箭头朝哪
 * @param modifier 布局修饰
 */
@Composable
private fun ReportListPane(
    reports: List<AnalysisReport>,
    selectedReportId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleCollapsed: () -> Unit,
    toggleTag: String,
    edge: PaneEdge,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(StrKey.REPORT_LIST_TITLE),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                WinIconButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = str(StrKey.REPORT_NEW))
                }
                // 「收起报表列表」—— 与左边的数据树**同一个按钮组件**
                // （`SidePaneToggleButton`），只是**贴边的那一侧相反**，
                // 所以箭头是**镜像**的：收起时指向右边（往窗口边收）。
                //
                // 它有**自己的一对文案**：屏幕上两块面板都能收，
                // 读屏念「折叠树面板」只会念错一块。
                Spacer(modifier = Modifier.width(2.dp))
                SidePaneToggleButton(
                    collapsed = false,
                    onClick = onToggleCollapsed,
                    edge = edge,
                    collapseLabel = str(StrKey.REPORT_LIST_PANE_COLLAPSE),
                    expandLabel = str(StrKey.REPORT_LIST_PANE_EXPAND),
                    tag = toggleTag,
                )
            }
            if (reports.isEmpty()) {
                EmptyHint(
                    title = str(StrKey.REPORT_EMPTY),
                    description = str(StrKey.REPORT_EMPTY_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(ReportDesignerTags.REPORT_LIST),
                ) {
                    itemsIndexed(reports, key = { _, report -> report.id }) { index, report ->
                        ReportListItem(
                            report = report,
                            index = index,
                            selected = report.id == selectedReportId,
                            onSelect = { onSelect(report.id) },
                            onRename = { onRename(report.id) },
                            onDelete = { onDelete(report.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 报表列表里的**一项** —— 名字 + 重命名 + 删除。
 *
 * @param report 报表
 * @param index 它在列表里的序号（**0-based**，用于「报表 N」的兜底名字）
 * @param selected 当前是不是选中的那一张
 * @param onSelect 点它
 * @param onRename 重命名
 * @param onDelete 删除
 * @param modifier 布局修饰
 */
@Composable
private fun ReportListItem(
    report: AnalysisReport,
    index: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ).padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reportDisplayName(report, index),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        WinIconButton(
            onClick = onRename,
            modifier = Modifier.testTag(ReportDesignerTags.REPORT_ITEM_RENAME_PREFIX + report.id),
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = str(StrKey.REPORT_RENAME),
                modifier = Modifier.size(14.dp),
            )
        }
        WinIconButton(
            onClick = onDelete,
            modifier = Modifier.testTag(ReportDesignerTags.REPORT_ITEM_DELETE_PREFIX + report.id),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = str(StrKey.REPORT_DELETE),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 报表**画布** —— **中间**那一大片：当前报表的部件一块一块往下排。
 *
 * ## 为什么它在中间
 *
 * 左边是输入（数据树）、右边是另一类产物（报表列表）——
 * 主产物（部件）夹在中间，占下最大的一块地方。
 * 两侧都能折叠收纳，正是为了让它能占满。
 *
 * @param report 当前报表；`null` = 还没选
 * @param reportIndex 它在报表列表里的序号（**0-based**，用于「报表 N」的兜底名字）
 * @param widgetResults 部件 id → 数据
 * @param refreshRunning 正在刷新
 * @param onAddWidget 添加部件
 * @param onEditWidget 编辑部件
 * @param onDeleteWidget 删除部件
 * @param onRefreshReport 重新取所有部件的数据
 * @param modifier 布局修饰
 */
@Composable
private fun ReportCanvas(
    report: AnalysisReport?,
    reportIndex: Int,
    widgetResults: Map<String, AnalysisResult>,
    refreshRunning: Boolean,
    onAddWidget: () -> Unit,
    onEditWidget: (ReportWidget) -> Unit,
    onDeleteWidget: (String, String) -> Unit,
    onRefreshReport: (AnalysisReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.testTag(ReportDesignerTags.REPORT_CANVAS),
    ) {
        if (report == null) {
            EmptyHint(
                title = str(StrKey.REPORT_EMPTY),
                description = str(StrKey.REPORT_EMPTY_HINT),
                modifier = Modifier.fillMaxSize(),
            )
            return@Surface
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reportDisplayName(report, reportIndex),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                WinIconButton(
                    onClick = { onRefreshReport(report) },
                    // 正在刷的时候禁掉：再点一下不会跑第二遍（会话会挡），
                    // 但按钮还亮着就成了「点了没反应」—— 那比明确地灰掉更让人困惑。
                    enabled = !refreshRunning,
                    modifier = Modifier.testTag(ReportDesignerTags.REFRESH_BUTTON),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = str(StrKey.REFRESH))
                }
            }
            if (report.widgets.isEmpty()) {
                EmptyHint(
                    title = str(StrKey.REPORT_NO_WIDGET),
                    description = str(StrKey.REPORT_NO_WIDGET_HINT),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                // 用 `LazyColumn` 而不是「Column + verticalScroll」——
                // 后者给子节点的是**无限高**的约束，而卡片里装着
                // `DataTable`（自己的 LazyColumn）与 `EmptyHint`（自己的纵向滚动）：
                // 滚动套滚动，内层拿到无限高约束，Compose 直接抛异常。
                // `LazyColumn` 的每一项都有**有界**的高度，问题自然消失。
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(report.widgets, key = { _, widget -> widget.id }) { index, widget ->
                        ReportWidgetCard(
                            widget = widget,
                            index = index,
                            result = widgetResults[widget.id],
                            onEdit = { onEditWidget(widget) },
                            onDelete = { onDeleteWidget(report.id, widget.id) },
                            modifier = Modifier.fillMaxWidth().height(REPORT_WIDGET_CARD_HEIGHT),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                WinTextButton(onClick = onAddWidget) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(str(StrKey.REPORT_ADD_WIDGET))
                }
            }
        }
    }
}

/**
 * 部件**卡片** —— 标题 + 图表 / 表格。
 *
 * @param widget 部件
 * @param index 它在报表里的序号（**0-based**，用于「部件 N」的兜底标题）
 * @param result 该部件的数据；`null` = 还没取过
 * @param onEdit 编辑
 * @param onDelete 删除
 * @param modifier 布局修饰
 */
@Composable
private fun ReportWidgetCard(
    widget: ReportWidget,
    index: Int,
    result: AnalysisResult?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.small,
                ).padding(12.dp)
                .testTag(ReportDesignerTags.WIDGET_CARD_PREFIX + widget.id),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    widget.title.ifBlank {
                        str(StrKey.REPORT_WIDGET_FALLBACK_TITLE, index + 1)
                    },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = str(reportWidgetTypeText(widget.type)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
            WinIconButton(
                onClick = onEdit,
                modifier = Modifier.testTag(ReportDesignerTags.WIDGET_EDIT_PREFIX + widget.id),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = str(StrKey.REPORT_WIDGET_EDIT),
                    modifier = Modifier.size(14.dp),
                )
            }
            WinIconButton(
                onClick = onDelete,
                modifier = Modifier.testTag(ReportDesignerTags.WIDGET_DELETE_PREFIX + widget.id),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = str(StrKey.REPORT_WIDGET_DELETE),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ReportWidgetBody(
            widget = widget,
            result = result,
            // 卡片高度是固定的，**内容区占掉剩下的那份** ——
            // 表格与图表区各自拿到一个有界的高度（否则里面的滚动会炸）。
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * 部件的**内容** —— 按类型画图表、铺表格，或者说明为什么画不出来。
 *
 * @param widget 部件
 * @param result 该部件的数据；`null` = 还没取过
 * @param modifier 布局修饰
 */
@Composable
fun ReportWidgetBody(
    widget: ReportWidget,
    result: AnalysisResult?,
    modifier: Modifier = Modifier,
) {
    // 还没取过数据 —— 表格与图表**共用**这一个提示：
    // 「还没有数据」与「查询没返回数据」是两回事，混成一句就分不清了。
    if (result == null) {
        ReportWidgetNoData()
        return
    }
    when (widget.type) {
        ReportWidgetType.TABLE -> {
            if (result.failed) {
                EmptyHint(
                    title = str(StrKey.REPORT_RESULT_FAILED),
                    description = result.error,
                    centerContent = false,
                    modifier = modifier.fillMaxWidth(),
                )
            } else {
                DataTable(
                    columns = analysisTableColumns(result),
                    rows = analysisTableRows(result),
                    onCellEdit = null,
                    cellEditable = false,
                    pageSize = PageSize.ALL,
                    serverSidePaging = false,
                    showDetailPanel = false,
                    modifier = modifier.fillMaxWidth(),
                )
            }
        }

        ReportWidgetType.PIE,
        ReportWidgetType.BAR,
        -> {
            ReportChartPreview(
                type = widget.type,
                result = result,
                modifier = modifier,
            )
        }
    }
}

/** 部件还没取过数据时的提示。 */
@Composable
private fun ReportWidgetNoData() {
    EmptyHint(
        title = str(StrKey.REPORT_WIDGET_NO_DATA),
        description = str(StrKey.REPORT_WIDGET_NO_DATA_HINT),
        modifier = Modifier.fillMaxWidth().height(120.dp),
    )
}

/**
 * 部件卡片的高度 —— **固定**值。
 *
 * ## 为什么固定
 *
 * 卡片里装着 `DataTable`（自己的 LazyColumn）与图表区（自己的滚动）。
 * 高度不定的话，卡片的高度就由内容决定，而内容里套着滚动 ——
 * 「内容多高」与「卡片多高」互相等着对方，结果是内层拿到无限高约束、
 * Compose 抛异常。固定高度把这件事定死：卡片多高是设计决定的，不是数据决定的。
 *
 * ## 为什么不给不同类型不同的高度
 *
 * 一个高度就够。表格与图表都是「一块内容」，真要区别对待，
 * 也是按**数据行数**分档，而不是按类型 —— 但那要让用户先说清楚行数，
 * 反而更绕。
 */
private val REPORT_WIDGET_CARD_HEIGHT = 300.dp

/**
 * 报表的**显示名** —— 没起名字的报表用「报表 N」兜底。
 *
 * ## 为什么兜底名按**列表位置**算，而不是按创建顺序
 *
 * 新报表刚建出来时名字是空的（见会话的 `createReport`）——
 * 那一层不是 Composable，取不到当前语言，所以「报表」这个词只能在这里拼。
 * 而「报表 1」里的那个 1 必须是**它在列表里的位置**：
 * 按创建顺序编号的话，删掉第 1 张之后，剩下的报表会变成「报表 2、报表 3」——
 * 列表里明明只有两张，却写着 2 和 3，用户会以为还有一张没显示出来。
 *
 * @param report 报表
 * @param index 它在列表里的序号（**0-based**）
 */
@Composable
private fun reportDisplayName(
    report: AnalysisReport,
    index: Int,
): String = report.name.ifBlank { str(StrKey.REPORT_DEFAULT_NAME, index + 1) }
