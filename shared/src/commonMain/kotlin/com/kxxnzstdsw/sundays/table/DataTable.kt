package com.kxxnzstdsw.sundays.table

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinMenuItem
import com.kxxnzstdsw.sundays.ui.onRightClick
import com.kxxnzstdsw.sundays.ui.winShape

/**
 * 可扩展的虚拟滚动数据表格 —— 支持：
 * - **大量数据**：基于 [LazyColumn] 的虚拟滚动
 * - **可配置表头**：[TableColumn] 自定义列宽 / 文本对齐 / 值格式化
 * - **分页**：[PageSize] 枚举（10/20/50/100/200/300/500/全部）
 * - **databind**：行数据由调用方管理 state 传入，data 更新自动重绘
 * - **单行详情预览**：点击行 → 右侧详情面板
 * - **单元格文本可选中**：每行包裹 [SelectionContainer]
 * - **右键菜单可扩展**：[contextMenuItems] 插槽传入自定义菜单项
 * - **数据库主键**：[TableRow.id] 承载主键，详情面板 / 选中状态识别
 *
 * ## 架构
 *
 * ```
 * DataTable (Row: 表格 + 详情面板)
 * ├── TablePanel (Column)
 * │   ├── TableHeader (Row, sticky at top)
 * │   ├── LazyColumn (rows, virtualized)
 * │   │   └── TableRow (SelectionContainer { Row { cells } })
 * │   └── TablePagination (Row)
 * ├── TableDetailPanel (default or caller-provided)
 * └── ContextMenu (DropdownMenu, pop-up on right-click)
 * ```
 *
 * ## 用法示例
 *
 * ```kotlin
 * var rows by remember {
 *     mutableStateOf(
 *         (1..1000).map { i ->
 *             TableRow(
 *                 id = i.toLong(),
 *                 "id" to i,
 *                 "name" to "user_$i",
 *                 "email" to "user$i@example.com",
 *                 "age" to (18 + i % 50),
 *             )
 *         }
 *     )
 * }
 * DataTable(
 *     columns = listOf(
 *         TableColumn(key = "id", header = "ID", width = 80.dp, alignment = TextAlign.End),
 *         TableColumn(key = "name", header = "姓名"),
 *         TableColumn(key = "email", header = "邮箱"),
 *         TableColumn(key = "age", header = "年龄", width = 80.dp, alignment = TextAlign.End),
 *     ),
 *     rows = rows,
 *     contextMenuItems = { row ->
 *         WinMenuItem(text = { Text("复制") }, onClick = { ... })
 *         WinMenuItem(text = { Text("删除") }, onClick = { ... })
 *     },
 * )
 * ```
 *
 * @param columns 列定义
 * @param rows 行数据（**调用方管理 state**；变化时表格自动重绘）
 * @param modifier Compose modifier
 * @param theme 表格主题
 * @param fillParentHeight 是否填充父容器剩余高度；默认 `true`（与 `CodeEditor.maxLines = null` 一致 —— 不施加高度上限，
 *   但仍受父容器约束、不会溢出）。设为 `false` 时表格按内容自适应高度（外部父容器需自己处理滚动 / 尺寸）
 * @param pageSize 当前分页大小
 * @param onPageSizeChange 分页大小变化回调
 * @param currentPage 当前页码（1-based）
 * @param onPageChange 页码变化回调
 * @param totalCount 总行数（用于分页计算；默认 `rows.size`）
 * @param serverSidePaging 分页是否发生在**数据源侧**（引擎 / 服务端）而非本地；默认 `false` = 本地切片。
 *   设为 `true` 时 [rows] 被视为「数据源返回的当前页」，[DataTable] 不再本地切片，只负责分页栏与页码计算。
 *   两种模式混用会切空数据：第 2 页起 `rows.drop((page-1) * size)` 的起点必然越界。
 *   该模式下分页大小下拉**自动排除** [PageSize.ALL] —— 「全部」在数据源侧是流式读取哨兵（`pageSize = 0`），
 *   不是一个合法的分页大小，放出来会静默退化成每次取 1 行。
 * @param selectedRowId 当前选中的行 ID（`null` = 无选中）；调用方可选地传入以控制选中状态
 * @param onSelectedRowChange 选中行变化回调（参数可能为 `null` = 取消选中）
 * @param showDetailPanel 是否显示右侧详情面板（默认 `true`）
 * @param detailPanel 详情面板 Composable 插槽；不传则使用默认 [DefaultDetailPanel]
 * @param detailPanelRatio 详情面板与主表格的宽度比（默认 `0.35f` = 主 65% / 详情 35%）
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；目标行通过 [ContextMenuState.payload] 访问
 * @param contextMenuState 右键菜单状态；通常用 [rememberContextMenuState] 创建
 */
@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    theme: DataTableTheme = DataTableTheme.default(),
    fillParentHeight: Boolean = true,
    pageSize: PageSize = PageSize.DEFAULT,
    onPageSizeChange: (PageSize) -> Unit = {},
    currentPage: Int = 1,
    onPageChange: (Int) -> Unit = {},
    totalCount: Int = rows.size,
    serverSidePaging: Boolean = false,
    selectedRowId: Any? = null,
    onSelectedRowChange: (TableRow?) -> Unit = {},
    showDetailPanel: Boolean = true,
    detailPanelRatio: Float = 0.35f,
    detailPanel: @Composable (TableRow?, DataTableTheme) -> Unit = { row, t -> DefaultDetailPanel(row, columns, t) },
    contextMenuState: ContextMenuState = rememberContextMenuState(),
    contextMenuItems: @Composable (TableRow?) -> Unit = {},
) {
    require(columns.isNotEmpty()) { "DataTable requires at least one column" }
    require(detailPanelRatio in 0f..1f) { "detailPanelRatio must be in [0, 1], got $detailPanelRatio" }

    // 选中行 ID（内部 state；外部 selectedRowId prop 优先）
    var internalSelectedRowId by remember { mutableStateOf<Any?>(null) }
    val effectiveSelectedRowId = selectedRowId ?: internalSelectedRowId

    fun setSelected(row: TableRow?) {
        if (selectedRowId == null) internalSelectedRowId = row?.id
        onSelectedRowChange(row)
    }

    // 计算当前页的行。
    // [serverSidePaging] 时**必须**跳过本地切片：调用方给的 rows 已经是「数据源返回的那一页」，
    // 再按 (currentPage-1) 偏移一次会在第 2 页起直接切空（start 越界 → emptyList）。
    val pageRows: List<TableRow> = if (pageSize.isAll || serverSidePaging) {
        rows
    } else {
        val start = (currentPage - 1).coerceAtLeast(0) * pageSize.value
        if (start >= rows.size) emptyList() else rows.drop(start).take(pageSize.value)
    }
    val totalPages: Int = if (pageSize.isAll) 1
    else maxOf(1, (totalCount + pageSize.value - 1) / pageSize.value)

    // 当 totalCount / pageSize 变化导致 currentPage 越界时自动回退
    LaunchedEffect(totalCount, pageSize, totalPages) {
        if (currentPage > totalPages) onPageChange(totalPages)
    }

    val selectedRow = remember(rows, effectiveSelectedRowId) {
        effectiveSelectedRowId?.let { id -> rows.find { it.id == id } }
    }

    // 高度策略：
    // - `fillParentHeight = true`（默认）→ `fillMaxSize()` —— 与 `CodeEditor.maxLines = null` 一致，
    //   不施加高度上限但仍受父容器约束，不会溢出
    // - `fillParentHeight = false` → 按内容自适应高度（外部父容器需自己处理滚动 / 尺寸）
    val outerModifier = if (fillParentHeight) {
        modifier.fillMaxSize()
    } else {
        modifier
    }

    Row(modifier = outerModifier) {
        // 主表格区域
        Surface(
            modifier = Modifier
                .weight(1f - detailPanelRatio.coerceAtLeast(0f))
                .fillMaxHeight(),
            color = theme.rowBackground,
            shape = winShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 表头与表体**共用同一个** ScrollState。
                //
                // 两处各 `rememberScrollState()` 时，表头能横滚、表体不能 —— 用户拖表头把
                // 「第 20 列」的名字拖到左边，数据行还停在原处，于是**列名与数据列对不上**，
                // 按列名读数会读到隔壁那一列。对数据库工具来说这是会读错数据的缺陷，
                // 不是「体验不好」。
                val hScroll = rememberScrollState()
                TableHeader(columns = columns, theme = theme, hScroll = hScroll)
                WinDivider(color = theme.borderColor)
                TableBody(
                    columns = columns,
                    rows = pageRows,
                    theme = theme,
                    selectedRowId = effectiveSelectedRowId,
                    onRowClick = { row -> setSelected(if (row.id == effectiveSelectedRowId) null else row) },
                    contextMenuState = contextMenuState,
                    hScroll = hScroll,
                )
                WinDivider(color = theme.borderColor)
                TablePagination(
                    theme = theme,
                    pageSize = pageSize,
                    onPageSizeChange = onPageSizeChange,
                    currentPage = currentPage,
                    onPageChange = onPageChange,
                    totalPages = totalPages,
                    totalCount = totalCount,
                    // 服务端分页下「全部」不是合法取值（见 KDoc），下拉里不提供
                    pageSizeOptions = if (serverSidePaging) {
                        PageSize.ALL_VALUES.filterNot { it.isAll }
                    } else {
                        PageSize.ALL_VALUES
                    },
                )
            }
        }
        if (showDetailPanel) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier = Modifier
                    .weight(detailPanelRatio.coerceAtLeast(0.05f))
                    .fillMaxHeight(),
                color = theme.rowBackground,
                shape = winShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
            ) {
                detailPanel(selectedRow, theme)
            }
        }
    }

    // 右键菜单 —— 浮在表格之上，不绑定具体行（位置由 ContextMenuState 记录）
    if (contextMenuState.visible) {
        val density = LocalDensity.current
        val menuOffset = androidx.compose.ui.unit.DpOffset(
            x = with(density) { contextMenuState.position.x.toDp() },
            y = with(density) { contextMenuState.position.y.toDp() },
        )
        DropdownMenu(
            expanded = true,
            onDismissRequest = { contextMenuState.dismiss() },
            offset = menuOffset,
        ) {
            contextMenuItems(contextMenuState.payload)
        }
    }
}

// ============================================================================
// 子组件：表头 (TableHeader)
// ============================================================================

/** 表头容器的 UI 测试 tag —— 见 `TableColumnAlignmentTest`。 */
const val TABLE_HEADER_TAG = "sundays.tableHeader"

/** 表体容器的 UI 测试 tag —— 见 `TableColumnAlignmentTest`。 */
const val TABLE_BODY_TAG = "sundays.tableBody"

/**
 * 分页底栏容器的 UI 测试 tag —— 见 `TablePaginationLayoutTest`。
 *
 * 需要它是因为「按钮标签有没有折行」这件事**只能量整体高度**：
 * 单看某个 `Text` 的语义边界，分行与不分行都可能给出看似正常的宽度。
 */
const val TABLE_PAGINATION_TAG = "sundays.tablePagination"

/**
 * 分页底栏进入「紧凑档」的宽度阈值 —— 低于它就隐藏「首页 / 末页 / 共 N 条」。
 *
 * ## 为什么是这个取舍
 *
 * 底栏由两组构成：左组（每页选择器 + 共 N 条）是对**当前结果集**的描述，
 * 右组（首页 / 上一页 / 页码 / 下一页 / 末页）是对**结果集**的操作。
 * 容器不够宽时该牺牲哪一组，答案很明确 —— 牺牲描述，保留操作。
 *
 * ## 这不是假想问题，是当前可达的布局
 *
 * 浏览屏的表预览把 **35% 宽度让给详情面板**（`DataTable.detailPanelRatio`），
 * 而库/表树面板又可以被拖到窗口的 62%（见 `DragHandle` 的宽度上限）。两者叠加：
 *
 * ```
 * 窗口 1024dp（= MIN_WINDOW_SIZE，用户不能再拖窄）
 *   − 树面板 635dp（1024 × 0.62）
 *   = 主表区 389dp
 *   × 0.65（主表 : 详情）
 *   = 底栏 253dp
 * ```
 *
 * 而底栏在宽档下的需求约 **530dp**。253 远不够，`Row` 于是把子项压到零尺寸 ——
 * 「下一页」会**整颗消失**（语义边界量到 `Rect(0,0,0,0)`），页码同理。
 * 这不是排版瑕疵，是功能消失。
 *
 * ## 降级的顺序
 *
 * 1. **< 560dp 隐藏「首页 / 末页 / 共 N 条」** —— 结果集通常几十页，首末页几乎不用；
 *    总条数在表头上下文里也能推知。
 * 2. **左组用 `weight(1f, fill = false)` 承担剩余压缩** —— `Row` 先测量无 weight 的
 *    子项，所以右组永远拿到自然宽度；即便在 253dp 这种极端情况下被压扁的也只是
 *    「每页」选择器，**翻页按钮绝不会被压没**。
 *
 * 实测三档窗口下的底栏宽度与右组需求（`TablePaginationLayoutTest` 钉住）：
 *
 * | 底栏 | 右组需求 | 结果 |
 * |---|---|---|
 * | 660dp | 342dp | 宽档，全部显示 |
 * | 450dp | 215dp | 紧凑档，右组宽裕 |
 * | 248dp | 215dp | 紧凑档，左组被压扁，右组完好 |
 */
private val PAGINATION_COMPACT_WIDTH: Dp = 560.dp

/**
 * 表头 —— 横向滚动由 [hScroll] 与表体**共享**（见 [DataTable] 调用点的说明）。
 *
 * [hScroll] 必须由调用方创建而不是这里自己 `remember`：两处各自持有状态就是「表头能滚、
 * 表体不能滚」那个缺陷的成因。
 */
@Composable
private fun TableHeader(
    columns: List<TableColumn>,
    theme: DataTableTheme,
    hScroll: ScrollState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.headerBackground)
            .horizontalScroll(hScroll)
            // 供 TableColumnAlignmentTest 量「表头是否真的能横滚」与表体位移是否一致
            .testTag(TABLE_HEADER_TAG)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableRowCells(columns = columns, theme = theme) { column, modifier ->
            TableCell(
                text = column.header,
                column = column,
                theme = theme,
                isHeader = true,
                modifier = modifier,
            )
        }
    }
}

// ============================================================================
// 子组件：表体 (TableBody) —— 虚拟滚动
// ============================================================================

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TableBody(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    theme: DataTableTheme,
    selectedRowId: Any?,
    onRowClick: (TableRow) -> Unit,
    contextMenuState: ContextMenuState,
    hScroll: ScrollState,
) {
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("暂无数据", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            // 与表头共用：横向滚动一份状态，列名与数据列永远对齐
            .horizontalScroll(hScroll)
            .testTag(TABLE_BODY_TAG),
    ) {
        itemsIndexed(items = rows, key = { _, row -> row.id }) { index, row ->
            val isSelected = row.id == selectedRowId
            TableRowView(
                row = row,
                columns = columns,
                theme = theme,
                isSelected = isSelected,
                isAlternate = index % 2 == 1,
                onClick = { onRowClick(row) },
                contextMenuState = contextMenuState,
            )
            WinDivider(color = theme.borderColor)
        }
    }
}

// ============================================================================
// 子组件：单行 (TableRowView)
// ============================================================================

@Composable
private fun TableRowView(
    row: TableRow,
    columns: List<TableColumn>,
    theme: DataTableTheme,
    isSelected: Boolean,
    isAlternate: Boolean,
    onClick: () -> Unit,
    contextMenuState: ContextMenuState,
) {
    val background = when {
        isSelected -> theme.rowBackgroundSelected
        isAlternate && theme.rowBackgroundAlt != null -> theme.rowBackgroundAlt
        else -> theme.rowBackground
    }
    val cellStyle = if (isSelected && theme.cellTextSelected != null) theme.cellTextSelected else theme.cellText

    // SelectionContainer 让用户在单元格内拖拽选择文本
    // Modifier.onTableRightClick 监听右键 → 显示 context menu
    SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClick = onClick)
                .onRightClick { offset -> contextMenuState.show(offset, row) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableRowCells(columns = columns, theme = theme) { column, modifier ->
                TableCell(
                    text = row.formatted(column),
                    column = column,
                    theme = theme,
                    cellStyle = cellStyle,
                    modifier = modifier,
                )
            }
        }
    }
}

// ============================================================================
// 单元格序列 —— 表头与数据行共用的列宽 / 分隔线布局
// ============================================================================

/**
 * 遍历 [columns] 渲染单元格，并在列之间插入竖直分隔线（最后一列不加）。
 *
 * 表头（[TableHeader]）与数据行（[TableRowView]）的差异只有「单元格内容」与「文本样式」，
 * 列宽策略（[TableColumn.width] 优先，否则按 [TableColumn.weight] 分配）与分隔线完全一致 —— 抽到这里
 * 避免两处各写一份、改一处漏一处。
 *
 * [cell] 收到的 `Modifier` 已按上述列宽策略算好，调用方直接透传给单元格根节点即可。
 */
@Composable
private fun RowScope.TableRowCells(
    columns: List<TableColumn>,
    theme: DataTableTheme,
    cell: @Composable (column: TableColumn, modifier: Modifier) -> Unit,
) {
    columns.forEach { column ->
        cell(
            column,
            if (column.width != null) Modifier.width(column.width) else Modifier.weight(column.weight),
        )
        if (column != columns.last()) VerticalDivider(
            color = theme.borderColor,
            modifier = Modifier.height(20.dp),
        )
    }
}

// ============================================================================
// 子组件：单元格 (TableCell) —— 同时支持表头和数据行
// ============================================================================

@Composable
private fun TableCell(
    text: String,
    column: TableColumn,
    theme: DataTableTheme,
    modifier: Modifier = Modifier,
    isHeader: Boolean = false,
    cellStyle: TextStyle = theme.cellText,
) {
    val style = if (isHeader) theme.headerText else cellStyle
    Box(
        modifier = modifier.padding(horizontal = 8.dp),
        contentAlignment = when (column.alignment) {
            TextAlign.Start, TextAlign.Left -> Alignment.CenterStart
            TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
            TextAlign.Center -> Alignment.Center
            else -> Alignment.CenterStart
        },
    ) {
        Text(
            text = text,
            style = style,
            textAlign = column.alignment,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ============================================================================
// 子组件：分页栏 (TablePagination)
// ============================================================================

@Composable
private fun TablePagination(
    theme: DataTableTheme,
    pageSize: PageSize,
    onPageSizeChange: (PageSize) -> Unit,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    totalPages: Int,
    totalCount: Int,
    pageSizeOptions: List<PageSize>,
) {
    var pageSizeExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 窄容器下隐藏「首页 / 末页 / 共 N 条」—— 见 [PAGINATION_COMPACT_WIDTH] 的说明。
        val compact = maxWidth < PAGINATION_COMPACT_WIDTH
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.paginationBackground)
                .testTag(TABLE_PAGINATION_TAG)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左组 `weight(1f, fill = false)`：`Row` 先测量无 weight 的子项，右组因此
            // 永远按自然宽度布局，剩余空间全归左组（`fill = false` 让左组在够宽时也
            // 只占自身宽度，视觉上仍是「贴左 / 贴右」两端对齐）。
            // 极端窄容器下被压扁的只有「每页」选择器，翻页按钮不会被压没。
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "每页",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color,
                    modifier = Modifier.padding(end = 8.dp),
                    maxLines = 1,
                    softWrap = false,
                )
                Box {
                    AssistChip(
                        onClick = { pageSizeExpanded = true },
                        label = { Text(pageSize.label, maxLines = 1, softWrap = false) },
                    )
                    DropdownMenu(
                        expanded = pageSizeExpanded,
                        onDismissRequest = { pageSizeExpanded = false },
                    ) {
                        pageSizeOptions.forEach { size ->
                            WinMenuItem(
                                text = { Text(size.label) },
                                onClick = {
                                    onPageSizeChange(size)
                                    pageSizeExpanded = false
                                },
                            )
                        }
                    }
                }
                if (!compact) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "共 $totalCount 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.headerText.color,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    NavButton("首页", enabled = currentPage > 1) { onPageChange(1) }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                NavButton("上一页", enabled = currentPage > 1) { onPageChange(currentPage - 1) }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$currentPage / $totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
                NavButton("下一页", enabled = currentPage < totalPages) { onPageChange(currentPage + 1) }
                if (!compact) {
                    Spacer(modifier = Modifier.width(4.dp))
                    NavButton("末页", enabled = currentPage < totalPages) { onPageChange(totalPages) }
                }
            }
        }
    }
}

/**
 * 分页导航按钮 —— 标签**禁止折行**。
 *
 * ## 关于 `maxLines = 1` + `softWrap = false`
 *
 * 这是一道**纵深防御，当前没有测试覆盖**（说清楚，免得后人以为它被钉住了）：
 * 真正把标签从「竖排堆叠」里救出来的是**左组的 `weight`** —— 右组永远拿到自然宽度，
 * 在最坏可达布局（248dp 底栏）下它也只占 215dp，压根不会被压缩。
 * 去掉这两行，四个测试照样全绿。
 *
 * 仍然保留的理由：它挡的是**曾经真实发生过的回归**（首轮 H2 走查截图里
 * 「下一页」三个字竖排堆叠、底栏整体长高一倍多）。任何人把 `detailPanelRatio`
 * 调大、把两个按钮之间的 `Spacer` 拉宽、或去掉左组的 `weight`，右组立刻会被压缩，
 * 而竖排是最难看的那种失败方式。成本是零。
 *
 * ## 已知但**没做**的取舍：按钮内边距偏宽
 *
 * M3 出厂 24dp 水平内边距让「上一页」这样的三字按钮要 **87dp**。收紧到 10dp
 * （约 59dp）显然更配得上 Win 风格，也让底栏余量从 7dp 涨到 48dp。
 *
 * **但本轮没有做**：实测 24dp 在所有可达布局下都装得下，没有任何一个测试能证明
 * 收紧的价值，而实现它要往共享的 `WinButton` 上加一个 `contentPadding` 参数 ——
 * 为一个尚未发生的溢出扩张全应用的控件 API 不划算。这是审美与余量的优化，
 * 不是缺陷修复，等它真的成为问题再说。
 */
@Composable
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    WinButton(
        onClick = onClick,
        enabled = enabled,
        shape = SundaysPalette.buttonShape,
    ) {
        Text(text = label, maxLines = 1, softWrap = false)
    }
}

// ============================================================================
// 子组件：默认详情面板 (DefaultDetailPanel)
// ============================================================================

/**
 * 默认详情面板 —— 当调用方不提供 [DataTable] 的 `detailPanel` 插槽时使用。
 *
 * 渲染选中行的所有列（key → formatted value）+ 主键标识。
 */
@Composable
fun DefaultDetailPanel(
    row: TableRow?,
    columns: List<TableColumn>,
    theme: DataTableTheme,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "详情",
            style = theme.headerText,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        WinDivider(color = theme.borderColor, modifier = Modifier.padding(bottom = 12.dp))
        if (row == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "← 点击左侧行查看详情",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color.copy(alpha = 0.6f),
                )
            }
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    DetailField("主键", row.id.toString(), theme)
                    WinDivider(color = theme.borderColor, modifier = Modifier.padding(vertical = 4.dp))
                    columns.forEach { column ->
                        DetailField(column.header, row.formatted(column), theme)
                        WinDivider(color = theme.borderColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, theme: DataTableTheme) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.headerText.color.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = theme.cellText,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ============================================================================
// IntelliJ Compose Multiplatform Preview Composables
// ============================================================================

/** 演示数据：1000 行模拟用户表。 */
private fun previewUsers(count: Int = 1000): List<TableRow> =
    (1..count).map { i ->
        TableRow(
            id = i.toLong(),
            "id" to i.toLong(),
            "name" to "user_$i",
            "email" to "user$i@example.com",
            "age" to (18 + i % 50),
            "active" to (i % 3 != 0),
        )
    }

private val PREVIEW_COLUMNS = listOf(
    TableColumn(key = "id", header = "ID", width = 80.dp, alignment = TextAlign.End),
    TableColumn(key = "name", header = "姓名"),
    TableColumn(key = "email", header = "邮箱"),
    TableColumn(key = "age", header = "年龄", width = 80.dp, alignment = TextAlign.End),
    TableColumn(
        key = "active",
        header = "状态",
        width = 80.dp,
        alignment = TextAlign.Center,
        formatter = { if (it == true) "✓" else "✗" },
    ),
)

/**
 * 默认浅色主题预览 — 1000 行用户数据演示虚拟滚动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview(name = "DataTable / Light", widthDp = 1000, heightDp = 600)
private fun DataTableLightPreview() {
    val rows = remember { previewUsers() }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
    )
}

/**
 * 默认深色主题预览。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview(name = "DataTable / Dark", widthDp = 1000, heightDp = 600, backgroundColor = 0xFF2B2B2B)
private fun DataTableDarkPreview() {
    val rows = remember { previewUsers() }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Dark,
    )
}

/**
 * 一键浅/深色对比预览。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@PreviewLightDark
@Preview(name = "DataTable / LightDark", widthDp = 1000, heightDp = 600)
private fun DataTableLightDarkPreview() {
    val rows = remember { previewUsers(100) }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.default(),
    )
}

/**
 * 上下文菜单扩展预览 —— 调用方注入「复制」「删除」菜单项。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview(name = "DataTable / Context Menu", widthDp = 1000, heightDp = 600)
private fun DataTableContextMenuPreview() {
    val rows = remember { previewUsers() }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
        contextMenuItems = { row ->
            WinMenuItem(
                text = { Text("复制 ${row?.id ?: ""}") },
                onClick = { },
            )
            WinMenuItem(
                text = { Text("删除 ${row?.id ?: ""}") },
                onClick = { },
            )
        }
    )
}

/**
 * 小数据集预览 — 5 行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview(name = "DataTable / Small", widthDp = 800, heightDp = 400)
private fun DataTableSmallPreview() {
    val rows = remember { previewUsers(5) }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
        pageSize = PageSize.ALL,
    )
}

/**
 * 隐藏详情面板预览 — 只显示表格。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Preview(name = "DataTable / No Detail Panel", widthDp = 800, heightDp = 400)
private fun DataTableNoDetailPanelPreview() {
    val rows = remember { previewUsers() }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
        showDetailPanel = false,
    )
}
