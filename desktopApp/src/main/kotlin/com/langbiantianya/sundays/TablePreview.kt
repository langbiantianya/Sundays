/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 表格预览区 —— 预览标签条、筛选/排序/搜索工具条、数据表格、新增行弹窗与待提交编辑条。

package com.langbiantianya.sundays

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.sql.SqlLiterals
import com.langbiantianya.sundays.table.CellEdit
import com.langbiantianya.sundays.table.CellValueField
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.DataTableTheme
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.tabStripContainerColor
import kotlinx.coroutines.launch

/** 排序预设菜单项按序号定位（菜单项不获取焦点，没有别的办法）。 */
private fun tableOrderMenuItemTag(index: Int) = "tableOrderItem_$index"

// ============================================================================
// 右侧 标签页 + 预览 DataTable
// ============================================================================

@Composable
internal fun PreviewTabArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.tabs.isEmpty()) {
            EmptyHint(
                title = str(StrKey.NO_OPEN_TABLE),
                description = str(StrKey.NO_OPEN_TABLE_HINT),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TabStrip(
                tabs = state.tabs.map { it.title },
                selectedIndex = state.selectedTabIndex.coerceAtLeast(0),
                onSelect = state::selectTab,
                onClose = state::closeTab,
            )
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val current = state.tabs.getOrNull(state.selectedTabIndex)
            if (current != null) {
                // 单元格编辑这类**瞬时**操作仍用组合作用域 —— 它们随 pane 离开而被取消是对的。
                // 导出不用它：导出动辄几十秒，必须活过 pane 切换（见 [DatabaseBrowserState.startExport]）。
                val scope = rememberCoroutineScope()
                // 导出对话框：挂在 `PreviewTabArea` 这一层而不是 `DataTable` 的右键菜单里 ——
                // 引擎的导出是**子进程**跑一段 SQL，右键菜单塞不下一整套格式/路径/文件名
                var exportSql by remember { mutableStateOf<String?>(null) }
                // 进度弹窗跟踪的是**状态机里的任务**（见 [DatabaseBrowserState.startExport]），
                // 不是本地的 job 变量：任务活过 pane 切换，composable 的 remember 活不过。
                var trackedTaskId by remember { mutableStateOf<String?>(null) }
                if (exportSql != null) {
                    ExportDialog(
                        state = state,
                        presetSql = exportSql!!,
                        presetTableName = current.tableName,
                        catalog = current.schema,
                        onDismiss = { exportSql = null },
                        onConfirm = { fmt, sql, dir, name, tbl ->
                            exportSql = null
                            trackedTaskId =
                                state.startExport(sql, fmt, dir, name, tbl) { result ->
                                    // 提示走 [TablePreviewTab.notice] 而不是 [TablePreviewTab.error]：
                                    // 后者会把表格换成「读取失败」面板，导出成功不该长那样
                                    // 回调跑在引擎协程里、已经离开了组合作用域，所以这里取文案走 `Msg` 而不是 `str`
                                    val notice =
                                        result.fold(
                                            onSuccess = { Msg.plural(PluralStrKey.EXPORTED_ROWS_TO_FILE, it.rowsWritten.toInt(), it.rowsWritten, it.file) },
                                            onFailure = { Msg.format(StrKey.EXPORT_FAILED_WITH_REASON, it.message) },
                                        )
                                    current.notice = notice
                                    // 严重级别与文案分开写：渲染方据 [TablePreviewTab.noticeIsError]
                                    // 上色，不去解析 notice 的文字。
                                    current.noticeIsError = result.isFailure
                                }
                        },
                    )
                }
                // 进度弹窗放在 `PreviewTabContent` **之外** —— 它是模态浮层，
                // 与下面那块可滚动的表格没有布局关系。
                //
                // 只跟踪**自己发起的那个**任务：同时有别的导出在跑时，不该把别人的进度
                // 显示成当前这个标签页的。
                val tracked = trackedTaskId?.let { id -> state.backgroundTasks.firstOrNull { it.id == id } }
                if (tracked != null && tracked.isRunning) {
                    ExportProgressDialog(
                        fileName = tracked.title,
                        outputDir = tracked.subtitle,
                        rowsWritten = tracked.progress ?: 0L,
                        onBackground = { trackedTaskId = null },
                    )
                }
                PreviewTabContent(
                    tab = current,
                    onPageChange = { page -> state.goToTabPage(current, page) },
                    onPageSizeChange = { size -> state.changeTabPageSize(current, size) },
                    // 编辑回调**只把改动放进暂存缓冲**，不发任何请求 ——
                    // 真正写库发生在用户点「提交」时，整批走一个事务（见
                    // [DatabaseBrowserState.applyCellEdits]）。
                    //
                    // 它是同步的，所以不需要 `scope.launch`：原先每次改一格就起一个协程发
                    // 请求，现在只是往 Map 里塞一条，20 次编辑不该换来 20 个协程。
                    //
                    // 失败文案走 `notice` 而不是 `error`：`error` 的渲染分支会把**整块表格**
                    // 换成「读取失败」面板 —— 而暂存被拒时数据是好的、表格也该继续显示，
                    // 用户还要看着它把值改对。两者语义不同，见 [TablePreviewTab.notice]。
                    onCellEdit =
                        if (state.isTableEditable(current)) {
                            { edit ->
                                state.stageCellEdit(current, edit)?.let {
                                    current.notice = it
                                    current.noticeIsError = true
                                }
                            }
                        } else {
                            null
                        },
                    // 与 onCellEdit 同源但**独立**传：行级「选中行」的 clickable 只在
                    // 真能编辑时才让位，否则只读浏览会整行点不动。
                    cellEditable = state.isTableEditable(current),
                    // 主键列与二进制列逐个关掉 —— 表级开关管不了它们（见该谓词 KDoc）。
                    isColumnEditable = { column -> state.isColumnEditable(current, column) },
                    // 该列能否取 NULL —— 决定编辑框里画不画「设为 NULL」的开关。
                    isColumnNullable = { column -> state.isColumnNullable(current, column) },
                    // 待提交高亮：值在状态机的缓冲里，组件只负责画。
                    isCellPending = { row, column -> state.isCellPending(current, row, column) },
                    // 已标记删除的整行要画成删除态底色，否则用户提交前不知道哪几行会消失。
                    isRowDeleted = { row -> state.isRowDeleted(current, row) },
                    // 多选只在「能删」时开：不能删的表摆一列复选框而点了没反应，
                    // 比不给更让人困惑。
                    rowSelectable = state.canDeleteRows(current),
                    selectedRowIds = current.selectedRowIds,
                    onSelectedRowIdsChange = { ids -> current.selectedRowIds = ids },
                    canInsert = state.canInsertRows(current),
                    onDeleteSelected = {
                        val picked = current.selectedRowIds.size
                        val err = state.stageRowDeletes(current, current.selectedRowIds)
                        current.notice = err ?: Msg.format(StrKey.EDIT_DELETED_MARKED, picked)
                        current.noticeIsError = false
                    },
                    onInsertRows = { rows ->
                        val before = current.pendingOps.size
                        val msg = state.stageRowInserts(current, rows)
                        current.notice = msg
                        // 消息有两种含义：一行都没进缓冲（错误）与只跳过了几行（提示）。
                        // 用「缓冲有没有变长」区分，比让状态机多返回一个标志位更难写错。
                        current.noticeIsError = msg != null && current.pendingOps.size == before
                    },
                    insertableColumns = current.insertableColumns,
                    nullableColumns = current.nullableColumns,
                    onApplyEdits = {
                        scope.launch {
                            val count = current.pendingOps.size
                            val err = state.applyCellEdits(current)
                            // 同样走 notice：提交失败时数据库已经回滚了，但**数据是好的**，
                            // 表格必须继续显示 —— 用户要看着那几行改掉冲突值再重试。
                            current.notice =
                                err ?: Msg.format(StrKey.EDIT_APPLIED, count)
                            current.noticeIsError = err != null
                        }
                    },
                    onDiscardEdits = {
                        state.discardCellEdits(current)?.let {
                            current.notice = it
                            current.noticeIsError = false
                        }
                    },
                    onFilterChange = { state.setTabFilter(current, it) },
                    onOrderByChange = { state.setTabOrderBy(current, it) },
                    onSearchChange = { state.setTabSearch(current, it) },
                    onExport = { sql -> exportSql = sql },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
        containerColor = tabStripContainerColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        divider = {},
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = str(StrKey.CLOSE_TAB),
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .clickable { onClose(index) },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 表预览的**过滤 / 排序 / 搜索**行。
 *
 * 三者的定位与分工：
 * - **搜索**：用户友好的表内文本查找，编译成 `LIKE '%词%'`（转义在 [SqlLiterals]）
 * - **过滤**：手写 `WHERE` 片段，给需要精确条件的场景
 * - **排序**：手写 `ORDER BY` 片段（引擎侧 `validateOrderBy` 挡注入）
 *
 * **全部交给引擎执行** —— 前端不在本地过滤。当前页只有 100 行，本地过滤等于
 * 「在这一页里筛」，翻到第 2 页结果就完全变了，而用户以为筛的是整张表。
 *
 * 排序做成一组**预设**（而不是自由文本框）：自由文本框每次输入都会打一次引擎，
 * 而「按 ID 升序」这种意图用下拉点两下就够了，真要复杂排序再进过滤框手写。
 */
@Composable
private fun TableFilterBar(
    tab: TablePreviewTab,
    onFilterChange: (String) -> Unit,
    onOrderByChange: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onExport: ((String) -> Unit)? = null,
) {
    // 本地草稿：受控输入框每敲一个字符都触发一次引擎往返会打爆数据库，
    // 所以文本先落在这里，回车或失焦才提交。
    var searchDraft by remember(tab) { mutableStateOf(tab.searchTerm) }
    var filterOpen by remember(tab) { mutableStateOf(tab.whereClause.isNotBlank()) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchDraft,
            onValueChange = { searchDraft = it },
            label = { Text(str(StrKey.SEARCH)) },
            singleLine = true,
            modifier =
                Modifier
                    .width(200.dp)
                    .testTag(TABLE_SEARCH_FIELD_TAG),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchChange(searchDraft) }),
        )
        Spacer(Modifier.width(8.dp))
        WinButton(
            onClick = { onSearchChange(searchDraft) },
            enabled = !tab.loading && searchDraft != tab.searchTerm,
            shape = SundaysPalette.buttonShape,
            modifier = Modifier.testTag(TABLE_SEARCH_BTN_TAG),
        ) { Text(str(StrKey.SEARCH)) }
        Spacer(Modifier.width(8.dp))
        // 「清除」只在有条件时出现：没有条件时摆一个按不动的按钮是噪音
        if (tab.whereClause.isNotBlank() || tab.searchTerm.isNotEmpty() || tab.orderByClause.isNotBlank()) {
            WinButton(
                onClick = {
                    searchDraft = ""
                    filterOpen = false
                    onFilterChange("")
                    onOrderByChange("")
                    onSearchChange("")
                },
                shape = SundaysPalette.buttonShape,
                modifier = Modifier.testTag(TABLE_CLEAR_BTN_TAG),
            ) { Text(str(StrKey.CLEAR_FILTER)) }
            Spacer(Modifier.width(8.dp))
        }
        // 排序预设 —— 覆盖绝大多数「我想按 X 看」的需求
        var orderOpen by remember { mutableStateOf(false) }
        Box {
            WinButton(
                onClick = { orderOpen = true },
                shape = SundaysPalette.buttonShape,
                modifier = Modifier.testTag(TABLE_ORDER_BTN_TAG),
            ) {
                Text(
                    if (tab.orderByClause.isBlank()) str(StrKey.SORT) else str(StrKey.SORT_WITH_CLAUSE, tab.orderByClause),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            DropdownMenu(expanded = orderOpen, onDismissRequest = { orderOpen = false }) {
                ORDER_PRESETS.forEachIndexed { index, (label, clause) ->
                    WinMenuItem(
                        // ⚠️ 菜单项**必须**逐项带 tag：下拉菜单打开后没有任何一项会获得焦点，
                        // 键盘用户（以及真窗口走查）只能靠 tag 定位，别的办法都没有。
                        modifier = Modifier.testTag(tableOrderMenuItemTag(index)),
                        text = { Text(if (clause.isEmpty()) label else "$label（$clause）") },
                        onClick = {
                            orderOpen = false
                            onOrderByChange(clause)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        WinButton(
            onClick = { filterOpen = !filterOpen },
            shape = SundaysPalette.buttonShape,
            modifier = Modifier.testTag(TABLE_FILTER_BTN_TAG),
        ) { Text(if (filterOpen) str(StrKey.HIDE_FILTER) else str(StrKey.FILTER)) }
        if (onExport != null) {
            Spacer(Modifier.width(8.dp))
            WinButton(
                // 导出的是**屏幕上这张表**：过滤 / 排序 / 表内搜索都进起手 SQL，
                // 而不是恒为整张表（见 [TablePreviewTab.buildExportSql] 的 KDoc）。
                // SQL 在这里拼而不是交给调用方 ——「导出当前视图」是这条按钮的
                // 固有语义，不该由调用方每次重写。
                onClick = { onExport.invoke(tab.buildExportSql()) },
                shape = SundaysPalette.buttonShape,
                modifier = Modifier.testTag(TABLE_EXPORT_BTN_TAG),
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(str(StrKey.EXPORT))
            }
        }
    }
    if (filterOpen) {
        var whereDraft by remember(tab) { mutableStateOf(tab.whereClause) }
        OutlinedTextField(
            value = whereDraft,
            onValueChange = { whereDraft = it },
            label = { Text(str(StrKey.WHERE_CONDITION_HINT)) },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .testTag(TABLE_FILTER_FIELD_TAG),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFilterChange(whereDraft) }),
        )
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            WinButton(
                onClick = { onFilterChange(whereDraft) },
                enabled = !tab.loading && whereDraft != tab.whereClause,
                shape = SundaysPalette.buttonShape,
            ) { Text(str(StrKey.APPLY)) }
            Spacer(Modifier.width(8.dp))
            Text(
                // 错误一定要就地显示：引擎回的 `ORA-00933` 之类不在这里，
                // 用户只会看到「刷新后表格空了」，完全无从判断是自己写错了还是引擎挂了
                text =
                    tab.error
                        ?.takeIf { it.isNotBlank() && whereDraft != tab.whereClause }
                        ?.let { str(StrKey.INVALID_CONDITION, it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 排序预设 —— `(标签, ORDER BY 子句)`。空子句表示「不排序」。 */
private val ORDER_PRESETS: List<Pair<String, String>> =
    listOf(
        Msg.text(StrKey.NO_SORT) to "",
        Msg.text(StrKey.SORT_FIRST_COL_ASC) to "1 ASC",
        Msg.text(StrKey.SORT_FIRST_COL_DESC) to "1 DESC",
    )

/** 表预览过滤行的 UI 测试 tag。 */
private const val TABLE_SEARCH_FIELD_TAG = "tableSearchField"

private const val TABLE_SEARCH_BTN_TAG = "tableSearchBtn"

private const val TABLE_FILTER_BTN_TAG = "tableFilterBtn"

private const val TABLE_FILTER_FIELD_TAG = "tableFilterField"

private const val TABLE_ORDER_BTN_TAG = "tableOrderBtn"

/** 表预览「新增行」「删除选中行」工具条的 UI 测试 tag。 */
private const val TABLE_INSERT_ROWS_BTN_TAG = "tableInsertRowsBtn"

private const val TABLE_DELETE_ROWS_BTN_TAG = "tableDeleteRowsBtn"

/** 新增行弹窗的 UI 测试 tag。行内 tag 后缀是 0 起的行号。 */
private const val TABLE_INSERT_DIALOG_TAG = "tableInsertDialog"

private const val TABLE_INSERT_ROW_TAG = "tableInsertRow"

private const val TABLE_INSERT_ADD_ROW_BTN_TAG = "tableInsertAddRowBtn"

private const val TABLE_INSERT_REMOVE_ROW_BTN_TAG = "tableInsertRemoveRowBtn"

private const val TABLE_INSERT_CONFIRM_BTN_TAG = "tableInsertConfirmBtn"

@Composable
private fun PreviewTabContent(
    tab: TablePreviewTab,
    onPageChange: (Int) -> Unit,
    onPageSizeChange: (PageSize) -> Unit,
    onCellEdit: ((CellEdit) -> Unit)? = null,
    cellEditable: Boolean = onCellEdit != null,
    isColumnEditable: (TableColumn) -> Boolean = { true },
    isColumnNullable: (TableColumn) -> Boolean = { true },
    isCellPending: (TableRow, TableColumn) -> Boolean = { _, _ -> false },
    isRowDeleted: (TableRow) -> Boolean = { false },
    rowSelectable: Boolean = false,
    selectedRowIds: Set<Any> = emptySet(),
    onSelectedRowIdsChange: (Set<Any>) -> Unit = {},
    /** 引擎判定这张表能新增（且当前不是只读）。false = 不画「新增行」入口。 */
    canInsert: Boolean = false,
    /** 点「删除选中行」—— 把复选框选中的行**标记**为待删除（真正删发生在提交时）。 */
    onDeleteSelected: (() -> Unit)? = null,
    /** 点「新增行」—— 打开填行弹窗；回调参数是待新增的行（列 → 值，`null` = SQL NULL）。 */
    onInsertRows: ((List<Map<String, String?>>) -> Unit)? = null,
    /** 弹窗里可填的列（引擎已剔除自增列与二进制列），**按列序**。 */
    insertableColumns: List<String> = emptyList(),
    /** 弹窗里允许取 NULL 的列。 */
    nullableColumns: Set<String> = emptySet(),
    /** 点「提交修改」—— 把暂存缓冲整批送进一个事务（见 [DatabaseBrowserState.applyCellEdits]）。 */
    onApplyEdits: (() -> Unit)? = null,
    /** 点「放弃修改」—— 只清缓冲，不发请求。 */
    onDiscardEdits: (() -> Unit)? = null,
    onFilterChange: (String) -> Unit = {},
    onOrderByChange: (String) -> Unit = {},
    onSearchChange: (String) -> Unit = {},
    /** 导出回调，入参是要导出的 SQL（由 [TableFilterBar] 按整表拼好）。 */
    onExport: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 弹窗的开关状态放这里而不是 `InsertRowsDialog` 内部：弹窗是模态浮层，它的内容要拿到
    // **每次翻页后刷新的** `insertableColumns`，放在外面就不会有一份记过期的列名。
    var insertDialogOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${tab.schema} · ${tab.tableName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                when {
                    tab.loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(str(StrKey.LOADING), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    tab.error != null -> {
                        Text(
                            text = str(StrKey.ERROR_PREFIX, tab.error),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            // **必须限一行**：这一行与下方 `fillMaxSize()` 的 DataTable 同处一个
                            // Column，报错有几百字符时会把表格挤成 0 高 —— 表格整个消失，
                            // 而用户真正需要看的长报错反而被压在这一行里。完整内容在下方错误区可滚。
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    else -> {
                        Text(
                            text =
                                plural(
                                    PluralStrKey.PAGINATION_SUMMARY,
                                    tab.total.toInt(),
                                    tab.total,
                                    tab.page,
                                    tab.pageSize,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 「非读取结果」的提示单独占一行：导出成功/失败**不能**塞进 [TablePreviewTab.error]。
                //
                // 为什么：error 的语义是「这次读取失败了」，渲染分支会把整块表格换成
                // 「读取失败」面板。于是「已导出 25 行」会被显示成**读取失败**，而且用户
                // 刚导出的数据在屏幕上消失 —— 报喜的那一行反而把内容顶没了。
                //
                // 失败文案也走这里：导出不成功时**数据是好的**，只是文件没生成，
                // 显示成「读取失败」同样是假的。
                tab.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        // 原来这里是 `notice.startsWith("导出失败")`。改成读
                        // [TablePreviewTab.noticeIsError]：中文前缀判定在任何非中文界面下
                        // 恒为 false，导出失败会被静默染成成功色 —— 见该字段的 KDoc。
                        color =
                            if (tab.noticeIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        TableFilterBar(
            tab = tab,
            onFilterChange = onFilterChange,
            onOrderByChange = onOrderByChange,
            onSearchChange = onSearchChange,
            onExport = onExport,
        )
        // 「新增行 / 删除选中行」工具条：**只画引擎说能用**的那几个。
        //
        // 为什么不给就别画：一个点了必然报错的按钮，比没有这个按钮更糟 —— 用户会以为
        // 是数据库出问题了。反过来，给了却用不了（比如只读模式）同样是噪音。
        // 判据是引擎随 DATA.LIST 回的能力位，不是前端猜的（见 [TablePreviewTab.canInsert]）。
        if (canInsert || (rowSelectable && selectedRowIds.isNotEmpty())) {
            RowToolsBar(
                canInsert = canInsert,
                selectedCount = selectedRowIds.size,
                busy = tab.applyingEdits,
                onInsert = { insertDialogOpen = true },
                onDeleteSelected = onDeleteSelected,
            )
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        // 待提交工具条：只在**确实有待提交改动**时占一行。
        //
        // 为什么不常驻：一条永远显示「0 处待提交 / 提交 / 放弃」的工具条会占掉一行高度，
        // 而这个高度在表格密集时是实打实的；更要紧的是它会让人以为「改一格就得点一次提交」，
        // 从而在只有一处改动时也走完整套流程。零状态不出现，改动状态必然出现。
        if (tab.pendingOps.isNotEmpty()) {
            PendingEditsBar(
                count = tab.pendingOps.size,
                busy = tab.applyingEdits,
                onApply = onApplyEdits,
                onDiscard = onDiscardEdits,
            )
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        // 新增行弹窗挂在这一层而不是 composable 内部 remember：它要跨 pane 切换吗？
        // 不用 —— 但它的内容要能拿到最新的 [insertableColumns]，而那些每翻一页都会刷新。
        // 放在这里就没有「记住了一份过期列名」的问题。
        if (insertDialogOpen && onInsertRows != null && insertableColumns.isNotEmpty()) {
            InsertRowsDialog(
                columns = insertableColumns,
                nullableColumns = nullableColumns,
                onDismiss = { insertDialogOpen = false },
                onConfirm = { rows ->
                    insertDialogOpen = false
                    onInsertRows(rows)
                },
            )
        }
        when {
            // 错误分支**必须排在最前**：翻页失败时 columns 仍留着上一页的、rows 已清空，
            // 若按「有列就画表格」的顺序判断，用户看到的是一张有表头但零行的空表，
            // 真正的失败原因只在上方那一行被省略号截断的提示里。
            tab.error != null -> {
                EmptyHint(
                    title = str(StrKey.READ_FAILED),
                    description = tab.error,
                    centerContent = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            tab.columns.isNotEmpty() || tab.rows.isNotEmpty() -> {
                DataTable(
                    columns = tab.columns,
                    // 渲染用**叠加了暂存修改**的行；`tab.rows` 始终是数据库里的真值，
                    // 这样「放弃修改」不必回滚任何本地数据（见 [TablePreviewTab.displayRows]）。
                    rows = tab.displayRows(),
                    modifier = Modifier.fillMaxSize(),
                    pageSize = tab.pageSize.toPageSize(),
                    onPageSizeChange = onPageSizeChange,
                    currentPage = tab.page,
                    onPageChange = onPageChange,
                    // 引擎回的是 Long（表可能上亿行），DataTable 要 Int。
                    // 饱和到 Int.MAX_VALUE 而不是直接 toInt()：后者在 >21 亿行时会绕成负数，
                    // 算出 totalPages 为负，分页栏直接消失。
                    totalCount = tab.total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    // 预览是**引擎侧**分页：rows 已经是当前页，DataTable 再本地切一次就见不到第 2 页了
                    serverSidePaging = true,
                    fillParentHeight = false,
                    // 传 null = 只读。什么时候能传非 null，见 [DatabaseBrowserState.isTableEditable]
                    onCellEdit = onCellEdit,
                    cellEditable = cellEditable,
                    isColumnEditable = isColumnEditable,
                    isCellPending = isCellPending,
                    // 三件与「能不能写」有关的都逐项传下去，组件只负责画：
                    // NULL 开关、待删除行底色、复选框列。
                    isColumnNullable = isColumnNullable,
                    isRowDeleted = isRowDeleted,
                    rowSelectable = rowSelectable,
                    selectedRowIds = selectedRowIds,
                    onSelectedRowIdsChange = onSelectedRowIdsChange,
                )
            }

            !tab.loading && tab.error == null -> {
                EmptyHint(
                    title = str(StrKey.NO_DATA),
                    description = str(StrKey.NO_DATA_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Spacer(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 待提交修改的工具条 —— 「待提交 N 处修改」+ 提交 / 放弃。
 *
 * ## 为什么需要显式提交，而不是改一格写一格
 *
 * 改一格立刻发 `UPDATE` 的话，批量改 20 行会变成 20 条**各自独立提交**的语句。
 * 第 13 行撞上唯一索引时，前 12 行已经落库 —— 用户没有任何手段把它们一起撤回。
 * 攒起来一次提交，这 20 条就落进**同一个事务**，失败整批回滚。
 *
 * ## 提交中为什么要禁用两个按钮
 *
 * 事务期间 `SYSTEM.BEGIN` 已经开、连接已经被引擎钉住。再点一次「提交」会开出
 * **第二个**事务会话，而第一个在用户看不见的地方悬着 —— 连接池被长期占用，
 * 数据库里还多了一个没人会提交的锁。所以 [busy] 把两个按钮一起关掉，
 * 而不只是关掉「提交」：提交中清空缓冲会让「正在写的值」凭空消失。
 */
@Composable
private fun PendingEditsBar(
    count: Int,
    busy: Boolean,
    onApply: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(
                text = str(StrKey.EDIT_PENDING_COUNT, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            onDiscard?.let {
                WinButton(
                    onClick = it,
                    enabled = !busy,
                    shape = SundaysPalette.buttonShape,
                ) {
                    Text(str(StrKey.EDIT_DISCARD), maxLines = 1, softWrap = false)
                }
            }
            onApply?.let {
                WinButton(
                    onClick = it,
                    enabled = !busy,
                    shape = SundaysPalette.buttonShape,
                ) {
                    Text(str(StrKey.EDIT_APPLY), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * 表预览里的「新增行 / 删除选中行」工具条。
 *
 * ## 为什么这两个按钮的**可见性**不由「能不能编辑」决定
 *
 * 能编辑的表一定也能勾选行，但反过来不成立：一张没有主键的表可以随便改单元格
 * （按行号定位不了没关系，编辑靠的是整行匹配）却**删不掉**——`DELETE` 的
 * `WHERE` 只能由主键拼。所以 [canInsert] 与 [selectedCount] 各自独立决定画不画，
 * 不能合成一个 `if (editable)`。
 *
 * ## 为什么删除按钮永远在，只是没选中时禁用
 *
 * 按钮不出现的话，用户根本不知道「这里可以删行」；而复选框列就在它左边，
 * 选中之后按钮自然可点。隐藏 + 突然出现比一直灰着更难扫视。
 *
 * ## 为什么删的是「标记」而不是数据
 *
 * 删数据不可逆，而 `WHERE` 只有主键条件。标记 + 提交给了用户一次确认，
 * 也让整批删除能和其它改动落进同一个事务（见 [DatabaseBrowserState.stageRowDeletes]）。
 */
@Composable
private fun RowToolsBar(
    canInsert: Boolean,
    selectedCount: Int,
    busy: Boolean,
    onInsert: () -> Unit,
    onDeleteSelected: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canInsert) {
                WinButton(
                    onClick = onInsert,
                    // 提交中禁用：此刻连接已被事务钉住，再发插入会开出第二个事务会话。
                    enabled = !busy,
                    shape = SundaysPalette.buttonShape,
                    modifier = Modifier.testTag(TABLE_INSERT_ROWS_BTN_TAG),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(str(StrKey.EDIT_INSERT_ROWS), maxLines = 1, softWrap = false)
                }
            }
            if (onDeleteSelected != null) {
                WinButton(
                    onClick = onDeleteSelected,
                    enabled = !busy && selectedCount > 0,
                    shape = SundaysPalette.buttonShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    modifier = Modifier.testTag(TABLE_DELETE_ROWS_BTN_TAG),
                ) {
                    Text(
                        text = str(StrKey.EDIT_DELETE_ROWS, selectedCount),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                // 说明只跟着「已选中」出现：没选中时说「标记后可一起提交」纯属噪音。
                if (selectedCount > 0) {
                    Text(
                        text = str(StrKey.EDIT_DELETE_CONFIRM_HINT),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「新增数据行」弹窗 —— 一张可以随时增删行的小表格。
 *
 * ## 为什么是弹窗里的一张表，而不是「在主表上直接加空行」
 *
 * 主表是**分页的服务端数据**：当前页末尾加的那一行不属于任何一页，引擎也没有
 * 「临时行」这个概念，页码、总数、翻页都会与它对不上。弹窗把这批数据与主表
 * 完全隔开，确认后才进暂存缓冲 —— 那时它才变成一次真正的 `INSERT`。
 *
 * ## 为什么每格都用自己的 [CellValueField]
 *
 * 因为「空串 ≠ NULL」这条约定在新增场景同样成立，而用户在这里**没有任何已有数据
 * 可以参照**，最需要的就是那个显式开关。共用同一套控件还顺带保证了「新增」与
 * 「编辑」在 NULL 语义上不会漂移。
 *
 * ## 为什么默认先摆一行空的
 *
 * 弹窗一打开就是空的，用户会以为它坏了。一行空的输入框既表明「这里可以填」，
 * 也让「不填直接确认」成为一次有意义的操作（此时按 [onConfirm] 传出去的那一行
 * 会被暂存层当空行跳过，见 [DatabaseBrowserState.stageRowInserts]）。
 */
@Composable
private fun InsertRowsDialog(
    columns: List<String>,
    nullableColumns: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Map<String, String?>>) -> Unit,
) {
    // 每行一个 `Map<列名, 值>`；值为 null 就是这一格要写 SQL NULL。
    var rows by remember { mutableStateOf(listOf(emptyMap<String, String?>())) }
    val theme = DataTableTheme.themed()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TABLE_INSERT_DIALOG_TAG),
        title = { Text(str(StrKey.EDIT_INSERT_TITLE)) },
        text = {
            Column {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, row ->
                        InsertRowEditor(
                            index = index,
                            removable = rows.size > 1,
                            row = row,
                            columns = columns,
                            nullableColumns = nullableColumns,
                            theme = theme,
                            onChange = { changed ->
                                rows = rows.toMutableList().also { it[index] = changed }
                            },
                            onRemove = {
                                rows = rows.filterIndexed { i, _ -> i != index }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WinTextButton(
                        onClick = { rows = rows + emptyMap() },
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(TABLE_INSERT_ADD_ROW_BTN_TAG),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(str(StrKey.EDIT_INSERT_ADD_ROW), maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = {
            WinButton(
                onClick = { onConfirm(rows) },
                shape = SundaysPalette.buttonShape,
                modifier = Modifier.testTag(TABLE_INSERT_CONFIRM_BTN_TAG),
            ) {
                Text(str(StrKey.EDIT_INSERT_CONFIRM), maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            WinTextButton(onClick = onDismiss, shape = SundaysPalette.buttonShape) {
                Text(str(StrKey.EDIT_DISCARD), maxLines = 1, softWrap = false)
            }
        },
    )
}

/** 弹窗里的**一行**输入 —— 一行列名 + 一行输入框，左边挂个移除按钮。 */
@Composable
private fun InsertRowEditor(
    index: Int,
    removable: Boolean,
    row: Map<String, String?>,
    columns: List<String>,
    nullableColumns: Set<String>,
    theme: DataTableTheme,
    onChange: (Map<String, String?>) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp).testTag(TABLE_INSERT_ROW_TAG + index)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 列名列：不占宽度的那一格写列名，宽列写行号 —— 列多的时候整行会被
            // 挤到屏外，看不出哪一格是哪一列。
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = columns.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (removable) {
                // 只在多于一行时才给移除按钮：唯一一行时移除会让弹窗变成一张空表，
                // 用户除了关掉再打开没有别的出路。
                WinIconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag(TABLE_INSERT_REMOVE_ROW_BTN_TAG + index),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = str(StrKey.EDIT_INSERT_REMOVE_ROW),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            columns.forEach { column ->
                CellValueField(
                    // 缺键 = 还没填过 = 空串，**不是 NULL**。弹窗里画不出 NULL 的行，
                    // 只有用户点了 ∅ 那一格才是 null —— 这正是约定的核心。
                    value = row[column].orEmpty(),
                    nullable = column in nullableColumns,
                    theme = theme,
                    onValueChange = { value -> onChange(row + (column to value)) },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    textStyle = theme.cellText,
                )
            }
        }
    }
}
