package com.kxxnzstdsw.Sundays.table

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.Sundays.ui.onRightClick

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
 *         DropdownMenuItem(text = { Text("复制") }, onClick = { ... })
 *         DropdownMenuItem(text = { Text("删除") }, onClick = { ... })
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
 * @param primaryKey 主键列的 [TableColumn.key]（默认 `"id"`，用于 detail panel 标题等）
 * @param pageSize 当前分页大小
 * @param onPageSizeChange 分页大小变化回调
 * @param currentPage 当前页码（1-based）
 * @param onPageChange 页码变化回调
 * @param totalCount 总行数（用于分页计算；默认 `rows.size`）
 * @param selectedRowId 当前选中的行 ID（`null` = 无选中）；调用方可选地传入以控制选中状态
 * @param onSelectedRowChange 选中行变化回调（参数可能为 `null` = 取消选中）
 * @param showDetailPanel 是否显示右侧详情面板（默认 `true`）
 * @param detailPanel 详情面板 Composable 插槽；不传则使用默认 [DefaultDetailPanel]
 * @param detailPanelRatio 详情面板与主表格的宽度比（默认 `0.35f` = 主 65% / 详情 35%）
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；目标行通过 [ContextMenuState.targetRow] 访问
 * @param contextMenuState 右键菜单状态；通常用 [rememberContextMenuState] 创建
 */
@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    theme: DataTableTheme = DataTableTheme.default(),
    fillParentHeight: Boolean = true,
    primaryKey: String = "id",
    pageSize: PageSize = PageSize.DEFAULT,
    onPageSizeChange: (PageSize) -> Unit = {},
    currentPage: Int = 1,
    onPageChange: (Int) -> Unit = {},
    totalCount: Int = rows.size,
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

    // 计算当前页的行
    val pageRows: List<TableRow> = if (pageSize.isAll) {
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
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TableHeader(columns = columns, theme = theme)
                HorizontalDivider(color = theme.borderColor)
                TableBody(
                    columns = columns,
                    rows = pageRows,
                    theme = theme,
                    selectedRowId = effectiveSelectedRowId,
                    onRowClick = { row -> setSelected(if (row.id == effectiveSelectedRowId) null else row) },
                    contextMenuState = contextMenuState,
                )
                HorizontalDivider(color = theme.borderColor)
                TablePagination(
                    theme = theme,
                    pageSize = pageSize,
                    onPageSizeChange = onPageSizeChange,
                    currentPage = currentPage,
                    onPageChange = onPageChange,
                    totalPages = totalPages,
                    totalCount = totalCount,
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
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
            ) {
                detailPanel(selectedRow, theme)
            }
        }
    }

    // 右键菜单 —— 全局 Popup，不依赖具体行（位置由 ContextMenuState 记录）
    if (contextMenuState.visible) {
        val density = LocalDensity.current
        val menuOffset = androidx.compose.ui.unit.DpOffset(
            x = with(density) { contextMenuState.position.x.toDp() },
            y = with(density) { contextMenuState.position.y.toDp() },
        )
        androidx.compose.foundation.layout.Box {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { contextMenuState.dismiss() },
                offset = menuOffset,
            ) {
                contextMenuItems(contextMenuState.targetRow)
            }
        }
    }
}

// ============================================================================
// 子组件：表头 (TableHeader)
// ============================================================================

@Composable
private fun TableHeader(
    columns: List<TableColumn>,
    theme: DataTableTheme,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.headerBackground)
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            TableCell(
                text = column.header,
                column = column,
                theme = theme,
                isHeader = true,
                modifier = Modifier.let { mod ->
                    if (column.width != null) mod.width(column.width) else mod.weight(column.weight)
                },
            )
            if (column != columns.last()) VerticalDivider(
                color = theme.borderColor,
                modifier = Modifier.height(20.dp),
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
            .weight(1f),
    ) {
        items(items = rows, key = { it.id }) { row ->
            val isSelected = row.id == selectedRowId
            TableRowView(
                row = row,
                columns = columns,
                theme = theme,
                isSelected = isSelected,
                isAlternate = rows.indexOf(row) % 2 == 1,
                onClick = { onRowClick(row) },
                contextMenuState = contextMenuState,
            )
            HorizontalDivider(color = theme.borderColor)
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
            columns.forEach { column ->
                TableCell(
                    text = row.formatted(column),
                    column = column,
                    theme = theme,
                    cellStyle = cellStyle,
                    modifier = Modifier.let { mod ->
                        if (column.width != null) mod.width(column.width) else mod.weight(column.weight)
                    },
                )
                if (column != columns.last()) VerticalDivider(
                    color = theme.borderColor,
                    modifier = Modifier.height(20.dp),
                )
            }
        }
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
) {
    var pageSizeExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.paginationBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "每页",
                style = MaterialTheme.typography.bodySmall,
                color = theme.headerText.color,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box {
                AssistChip(
                    onClick = { pageSizeExpanded = true },
                    label = { Text(pageSize.label) },
                )
                DropdownMenu(
                    expanded = pageSizeExpanded,
                    onDismissRequest = { pageSizeExpanded = false },
                ) {
                    PageSize.ALL_VALUES.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(size.label) },
                            onClick = {
                                onPageSizeChange(size)
                                pageSizeExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "共 $totalCount 条",
                style = MaterialTheme.typography.bodySmall,
                color = theme.headerText.color,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onPageChange(1) },
                enabled = currentPage > 1,
            ) { Text("首页") }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = { onPageChange(currentPage - 1) },
                enabled = currentPage > 1,
            ) { Text("上一页") }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$currentPage / $totalPages",
                style = MaterialTheme.typography.bodySmall,
                color = theme.headerText.color,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onPageChange(currentPage + 1) },
                enabled = currentPage < totalPages,
            ) { Text("下一页") }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = { onPageChange(totalPages) },
                enabled = currentPage < totalPages,
            ) { Text("末页") }
        }
    }
}

// ============================================================================
// 子组件：默认详情面板 (DefaultDetailPanel)
// ============================================================================

/**
 * 默认详情面板 —— 当调用方不提供 [DataTable.detailPanel] 时使用。
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
        HorizontalDivider(color = theme.borderColor, modifier = Modifier.padding(bottom = 12.dp))
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
                    HorizontalDivider(color = theme.borderColor, modifier = Modifier.padding(vertical = 4.dp))
                    columns.forEach { column ->
                        DetailField(column.header, row.formatted(column), theme)
                        HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
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
    var rows by remember { mutableStateOf(previewUsers()) }
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
    var rows by remember { mutableStateOf(previewUsers()) }
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
    var rows by remember { mutableStateOf(previewUsers(100)) }
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
    var rows by remember { mutableStateOf(previewUsers()) }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
        contextMenuItems = { row ->
            DropdownMenuItem(
                text = { Text("复制 ${row?.id ?: ""}") },
                onClick = { },
            )
            DropdownMenuItem(
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
    var rows by remember { mutableStateOf(previewUsers(5)) }
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
    var rows by remember { mutableStateOf(previewUsers()) }
    DataTable(
        columns = PREVIEW_COLUMNS,
        rows = rows,
        theme = DataTableTheme.Light,
        showDetailPanel = false,
    )
}