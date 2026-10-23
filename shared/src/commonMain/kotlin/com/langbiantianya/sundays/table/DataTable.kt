/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.onRightClick
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

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
 * 跨组件的架构原则（slot-based 扩展点、databind 模式、合理默认值）见
 * `shared/ARCHITECTURE.md` §7 —— 本组件遵守它们，但它们不属于某一个组件。
 * 其中唯一的例外是选中行：调用方未传 `selectedRowId` 时由内部 state 兜底，
 * 以保持调用方零样板代码。
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
 * @param onCellEdit 单元格编辑回调；**传 `null`（默认）= 只读表格**，与改造前行为完全一致。
 *   非 null 时**单击**单元格进入内联编辑（双击同样进），
 *   `Enter` / 失焦提交、`Esc` 取消。值未变化**不触发**回调。
 *
 *   ## 为什么默认关着
 *
 *   这是一个通用组件，SQL 工作台的结果表、造数结果表都拿它渲染 —— 那些结果**不是**
 *   可写的（它们是一次性查询的快照，改了也没有对应的「保存」语义）。所以编辑能力
 *   做成**调用方显式开启**：浏览屏的表预览传非 null，其余调用点保持 `null`。
 *
 *   组件**不执行**任何 SQL —— 它只报告「哪一行哪一列被改成了什么」，由调用方决定
 *   发什么语句、要不要确认、失败怎么回滚。组件里发 SQL 等于把「这张表能不能改」
 *   这个业务判断塞进一个渲染层。
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
    onCellEdit: ((CellEdit) -> Unit)? = null,
    /**
     * 调用方是否确认**这张表真的可写**。
     *
     * 与 [onCellEdit] 刻意分开：前者是「收到编辑后做什么」，后者是「这张表能不能改」。
     * 浏览屏今天传了 `onCellEdit`（接线已就绪）但 [com.langbiantianya.sundays.DatabaseBrowserState.isTableEditable]
     * 恒为 `false`（引擎的 `DATA.LIST` 不回主键）—— 若用 `onCellEdit != null` 当判据，
     * 整行的 `clickable`（选中行）会在**只读浏览**时被去掉，
     * 表现为表预览整行点不动（`H2GuiWalkthroughTest` 实测报 "Failed to inject mouse input"）。
     *
     * 默认跟随 [onCellEdit]（`true` 时才可编辑）；调用方拿不准时传 `false` 保持只读。
     */
    cellEditable: Boolean = onCellEdit != null,
    /**
     * 逐列的可编辑性 —— 在 [cellEditable] 为真时**进一步**收窄。
     *
     * ## 为什么表级一个 `Boolean` 不够
     *
     * 真实的表里总有几类列**不能**在单元格里改，而它们和「这张表可不可写」是两回事：
     *
     * - **主键列**：它同时是「定位这一行」的 `WHERE` 条件。允许改它，就得处理
     *   「先改主键、后面几条改动的 `WHERE` 还能不能命中」这种自相矛盾的序列。
     * - **二进制列**：`DATA.LIST` 对它们返回的是 `"[LOB Data]"` 占位串而不是真实内容，
     *   拿占位串去写回去等于**用一句提示语覆盖掉用户的图片**。
     *
     * 这两类都不是「整张表只读」，恰恰相反，同一张表里绝大多数列照常可改。
     * 所以必须是逐列谓词 —— 用表级开关一刀切，等于为了保护两列而让整张表都不能编辑。
     *
     * 默认 `{ true }` = 不额外收窄，保持既有行为。
     */
    isColumnEditable: (TableColumn) -> Boolean = { true },
    /**
     * 该单元格是否处于「**已改但还没提交**」状态 —— 用 [DataTableTheme.cellPendingBackground] 标出来。
     *
     * 之所以要调用方来判而不是组件自己记：值在调用方手里（暂存缓冲），
     * 组件只负责渲染。这也让「放弃修改」只要把缓冲清空，高亮就自动消失 ——
     * 若组件自己 `remember` 一份待提交列表，调用方放弃时就清不掉它，界面会一直显示脏格。
     */
    isCellPending: (TableRow, TableColumn) -> Boolean = { _, _ -> false },
    /** 该列能否取 SQL NULL —— 决定编辑框里画不画 `∅` 开关（见 [CellValueField]）。 */
    isColumnNullable: (TableColumn) -> Boolean = { true },
    /** 该行是否已标记**待删除** —— 用 [DataTableTheme.cellDeletedBackground] 标出。 */
    isRowDeleted: (TableRow) -> Boolean = { false },
    /**
     * 是否显示行首**复选框列**（多选）。
     *
     * ## 为什么必须是复选框，不能靠点行选中
     *
     * 本组件有两套互斥的手势：只读态下单击是「选中行」，可编辑态下单击是「进单元格编辑」
     * （见 [DataTable.cellEditable] 的说明——两者抢同一个手势，所以编辑态必须去掉整行
     * 的 `clickable`）。批量删除恰恰只在**可编辑**的表上才有意义，于是那一态里
     * 已经没有「单击选中行」这条路了。复选框是唯一不与编辑手势打架、又符合
     * Excel / DataGrip 肌肉记忆的做法。
     *
     * 默认 `false`：只读浏览与不需要多选的调用方**完全不受影响**（不多一列、不多一次测量）。
     */
    rowSelectable: Boolean = false,
    /** 当前选中的行 id 集合（配合 [rowSelectable]）。 */
    selectedRowIds: Set<Any> = emptySet(),
    /** 选中行变化回调。传进来的集合是**新的**实例，调用方替换 state 即可。 */
    onSelectedRowIdsChange: (Set<Any>) -> Unit = {},
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
    val pageRows: List<TableRow> =
        if (pageSize.isAll || serverSidePaging) {
            rows
        } else {
            val start = (currentPage - 1).coerceAtLeast(0) * pageSize.value
            if (start >= rows.size) emptyList() else rows.drop(start).take(pageSize.value)
        }
    val totalPages: Int =
        if (pageSize.isAll) {
            1
        } else {
            maxOf(1, (totalCount + pageSize.value - 1) / pageSize.value)
        }

    // 当 totalCount / pageSize 变化导致 currentPage 越界时自动回退
    LaunchedEffect(totalCount, pageSize, totalPages) {
        if (currentPage > totalPages) onPageChange(totalPages)
    }

    val selectedRow =
        remember(rows, effectiveSelectedRowId) {
            effectiveSelectedRowId?.let { id -> rows.find { it.id == id } }
        }

    // 高度策略：
    // - `fillParentHeight = true`（默认）→ `fillMaxSize()` —— 与 `CodeEditor.maxLines = null` 一致，
    //   不施加高度上限但仍受父容器约束，不会溢出
    // - `fillParentHeight = false` → 按内容自适应高度（外部父容器需自己处理滚动 / 尺寸）
    val outerModifier =
        if (fillParentHeight) {
            modifier.fillMaxSize()
        } else {
            modifier
        }

    Row(modifier = outerModifier) {
        // 主表格区域
        Surface(
            modifier =
                Modifier
                    .weight(1f - detailPanelRatio.coerceAtLeast(0f))
                    .fillMaxHeight(),
            color = theme.rowBackground,
            shape = winShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
        ) {
            // ⚠️ 这里**必须**用 [BoxWithConstraints] 把可视宽读出来，见 [contentWidthFor] 的说明：
            // 表头与表体都挂在 `horizontalScroll` 里，而滚动容器给子项的是**无界宽度**，
            // 此时 `Modifier.fillMaxWidth()` 是空操作、`Modifier.weight(1f)` 拿到 **0** 宽。
            BoxWithConstraints {
                val contentWidth =
                    contentWidthFor(
                        columns,
                        maxWidth,
                        if (rowSelectable) ROW_SELECTION_COLUMN_WIDTH else 0.dp,
                    )
                Column(modifier = Modifier.fillMaxSize()) {
                    // 表头与表体**共用同一个** ScrollState。
                    //
                    // 两处各 `rememberScrollState()` 时，表头能横滚、表体不能 —— 用户拖表头把
                    // 「第 20 列」的名字拖到左边，数据行还停在原处，于是**列名与数据列对不上**，
                    // 按列名读数会读到隔壁那一列。对数据库工具来说这是会读错数据的缺陷，
                    // 不是「体验不好」。
                    val hScroll = rememberScrollState()
                    // 「本页全选」只认**当前页**的行：翻页后选中的行已经不在屏幕上，
                    // 把它们算进来会让表头方框显示成「已全选」而实际只勾了几行。
                    val pageIds = pageRows.map { it.id }.toSet()
                    TableHeader(
                        columns = columns,
                        theme = theme,
                        hScroll = hScroll,
                        contentWidth = contentWidth,
                        rowSelectable = rowSelectable,
                        allSelected = pageIds.isNotEmpty() && pageIds.all { it in selectedRowIds },
                        onToggleAll = {
                            onSelectedRowIdsChange(
                                if (pageIds.isNotEmpty() && pageIds.all { it in selectedRowIds }) {
                                    selectedRowIds - pageIds
                                } else {
                                    selectedRowIds + pageIds
                                },
                            )
                        },
                    )
                    WinDivider(color = theme.borderColor)
                    TableBody(
                        columns = columns,
                        rows = pageRows,
                        theme = theme,
                        selectedRowId = effectiveSelectedRowId,
                        onRowClick = { row -> setSelected(if (row.id == effectiveSelectedRowId) null else row) },
                        contextMenuState = contextMenuState,
                        hScroll = hScroll,
                        editable = cellEditable,
                        onCellEdit = onCellEdit,
                        isColumnEditable = isColumnEditable,
                        isCellPending = isCellPending,
                        isColumnNullable = isColumnNullable,
                        isRowDeleted = isRowDeleted,
                        rowSelectable = rowSelectable,
                        selectedRowIds = selectedRowIds,
                        onSelectedRowIdsChange = onSelectedRowIdsChange,
                        contentWidth = contentWidth,
                    )
                    // 横向滚动条 —— 放在表体与分页栏**之间**。
                    //
                    // 为什么非有不可：`contentWidthFor` 让列一多就宽于视口，于是右侧的列
                    // **滚出屏幕外**。而 `Modifier.horizontalScroll` 默认只响应触控板横扫 /
                    // Shift+滚轮 —— 普通鼠标用户没有任何可见的提示，**根本不知道右边还有列**。
                    // 那不叫「支持横向滚动」，那叫「把数据藏起来了」。
                    //
                    // 用**可见且可拖**的滚动条是因为它同时解决两件事：
                    //   1. 溢出**可见**（拇指宽度 = 可见比例，用户一眼看出还有多少列没露出来）
                    //   2. **可达**（拖着就能到任何一列，不依赖用户知道 Shift+滚轮这回事）
                    //
                    // 与表头、表体共用 [hScroll]，所以拖它时列名与数据列一起走 —— 不会出现
                    // 「数据滑过去了、列名还停在原地」这种会读错列的状态。
                    //
                    // 不做「溢出时才出现」的条件渲染：列数会随数据加载变化（先 5 列后 23 列），
                    // 滚动条忽隐忽现会引发布局跳动，比一条不滚动时的满格轨道更糟。
                    // 满格轨道正是「当前没有溢出」的正确视觉，与 Excel 等表格一致。
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(hScroll),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TABLE_HSCROLLBAR_TAG),
                        // ⚠️ **必须显式给 style，不能用默认样式**。
                        //
                        // 默认 `ScrollbarStyle` 的 `unhoverColor` 在深色档实测是 `#1F232D`，
                        // 而轨道背后就是表格底色 `#232833` —— 每通道只差 4，**肉眼几乎看不出**
                        // 「这里有条滚动条」，更看不出右边还有列。那等于白加：溢出依然不可见。
                        //
                        // `unhoverColor` 给成实色 ⇒ 拇指**常驻可见**（而不是悬停才淡入）。
                        // 这是刻意的取舍：自动隐藏的滚动条很「现代」，但它服务的是
                        // 「已经知道要横滚」的人；这里要解决的是**还不知道右边有列**的人。
                        //
                        // ⚠️ **颜色取 `cellText.color`（墨色），不取任何「表面色」**。
                        //
                        // 这一点是被真窗口像素实测纠正过来的：初版取 `headerBackground`
                        // （深色档 `#2C3240`），理由是「与表头、分页栏同色、已在主题里」。
                        // 理由成立，**结论却是错的** —— 截图量下来拇指确实是 `#2C3240`，
                        // 可它背后的轨道底色就是行底 `#232833`，两者每通道只差 9/10/17：
                        //
                        //   对比度（WCAG，相对亮度比）= **1.15 : 1**
                        //
                        // 1.15:1 意味着「渲染出来了」但「看不出」—— 而这个缺陷的全部要害
                        // 恰恰是「看不出右边还有列」，所以它等于没修。
                        //
                        // 根子在选色**维度**选错了：`headerBackground` / `borderColor` /
                        // `paginationBackground` 全都是**表面色**，而全部现代档的表面色都挤在
                        // `#232833 ~ #333A48` 这一条窄带里，互相之间天然只有 1.1~1.3:1。
                        // 从这条窄带里挑任何一个，都不可能指望它跟背景拉开距离。
                        //
                        // 滚动条拇指不是表面，是**控件**，控件就该用墨色（`cellText.color`）：
                        //   - 深色档 `#CBD1DC` on `#232833` = **9.6 : 1**
                        //   - 浅色档 `#000000` on `#FFFFFF` = **21 : 1**
                        //
                        // 明暗两档都远超 WCAG 对非文本 UI 部件的 3:1，且该色在每套主题里
                        // 都有定义（现代档用常量、复古档取 `onSurface`），不必新造取值。
                        // 代价是拇指比数据醒目 —— 但「醒目」正是此处要的效果：
                        // 它是一个需要被看见才能被使用的控件。
                        //
                        // 这条对比度不是靠肉眼认的，`TableHorizontalScrollbarTest` 会
                        // `captureToImage()` 取真实像素算 WCAG 比值并卡下限。
                        style =
                            ScrollbarStyle(
                                12.dp,
                                8.dp,
                                winShape(4.dp),
                                150,
                                theme.cellText.color,
                                theme.cellText.color,
                            ),
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
                        pageSizeOptions =
                            if (serverSidePaging) {
                                PageSize.ALL_VALUES.filterNot { it.isAll }
                            } else {
                                PageSize.ALL_VALUES
                            },
                    )
                }
            }
        }
        if (showDetailPanel) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier =
                    Modifier
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
        val menuOffset =
            androidx.compose.ui.unit.DpOffset(
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

/**
 * 表头与表体**共同的内容宽度** —— 必须是一个**显式的 `Dp`**，而不是靠 `fillMaxWidth()`。
 *
 * ## 为什么
 *
 * 表头与表体都挂在 `Modifier.horizontalScroll` 里，而**滚动容器给子项的是无界宽度**。
 * 无界宽度下两个惯用写法同时失效：
 *
 * - `Modifier.fillMaxWidth()`：没有上界可填，等于空操作；
 * - `Modifier.weight(1f)`：`Row` 分不出剩余空间，**每个加权子项拿到 0 宽**。
 *
 * 于是数据行整行收缩到只剩「一个字符」的宽度（实测 1024px 视口下塌成 **28px**），
 * 5 个 `weight` 列全挤在 x=0 互相盖住 —— 表预览里除第一列外**全是空白或乱码**。
 * 表头看起来正常，是因为它的 `Row` 在同一条链上恰好先拿到了视口宽度。
 *
 * ## 为什么定宽列的测试一直是绿的
 *
 * `TableColumnAlignmentTest` 用的是 12 列 × **140dp 定宽**，走 `Modifier.width(140.dp)`
 * —— 那是**显式宽度**，在无界容器里照样成立。只有 `weight` 路径会塌。
 * 而 `DatabaseBrowserScreen` 建列时**只给 key 与 header**（`TableColumn(key, header)`），
 * 也就是全部走 `weight` 路径 —— 于是浏览器一连真数据库就必然踩中。
 *
 * ## 怎么算
 *
 * 与 `Row` 的分配规则一致：**先给定宽子项自然宽度，剩下的按权重分**。
 * 于是「有加权列」时内容宽度就是视口宽（不足则由定宽列撑开并触发横滚），
 * 「全是定宽列」时就是定宽之和（超出视口即横滚）。两种情形都保住了
 * 原有的「共用一个 ScrollState、表头表体永远对齐」的契约。
 *
 * ## 加权列的最小宽度 —— 为什么不能只取视口宽
 *
 * 浏览屏建列时**只给 key 与 header**（`TableColumn(key, header)`），`width` 为 null，
 * 全部走 `weight` 路径。而 `weight` 是在**给定内容宽度内**均分的：
 * 视口 650dp × 23 列 = **每列 28dp** —— 宽不过两个字符。
 *
 * 真窗口实测（PG `examquestions.biz_user`，23 个字段）：整张表塌成一团竖条，
 * 表头只剩第一个列名 `id`，其余 22 个列名被压到看不见，数值互相叠着；
 * 而点开右侧「详情」面板读数完全正常 —— **数据是对的，只是网格没法看**。
 *
 * 所以内容宽度必须给加权列一个**下限** [MIN_WEIGHTED_COLUMN_WIDTH]，
 * 列一多就让内容宽于视口、**触发横向滚动**，而不是把所有列挤成不可读的宽度。
 *
 * 100dp 大约容纳 13~14 个字符，够放下常见的列名（`last_login_at` 正好 13 个），
 * 也让一位数字看得清；再窄就得横滚才看得见，那不如一开始就给足宽度。
 */
private fun contentWidthFor(
    columns: List<TableColumn>,
    viewport: Dp,
    /** 复选框列等**不属于 columns 的定宽前缀**。表头与表体都要加，不加就整体错位。 */
    extraFixed: Dp = 0.dp,
): Dp {
    if (columns.isEmpty()) return viewport + extraFixed
    val fixed = columns.fold(extraFixed) { acc, c -> acc + (c.width ?: 0.dp) }
    val weightedCount = columns.count { it.width == null }
    // 分隔线与左右内边距按 1dp / 12dp 估算，量级足够（差几 dp 不影响判断），
    // 真正要紧的是**有没有被固定成一个具体值**。
    val chrome = 24.dp + (columns.size - 1).coerceAtLeast(0).dp
    // ⚠️ 没有 `Dp.times(Int)` —— 必须先在 Float 上乘再转回 Dp，否则编译不过
    val weightedFloor = (weightedCount * MIN_WEIGHTED_COLUMN_WIDTH.value).dp
    val minimum = fixed + weightedFloor + chrome
    // 两种列型都取 maxOf：定宽列加起来比视口窄时，内容宽度也不该小于视口，
    // 否则右侧会空出一块，滚动条与底栏的边界也对不上。
    return maxOf(viewport, minimum)
}

/**
 * 加权列的**最小**内容宽度 —— 见 [contentWidthFor]。
 *
 * 定宽列不受此约束（它们本来就是显式意图），只有 `weight` 列靠下限兜底。
 */
private val MIN_WEIGHTED_COLUMN_WIDTH: Dp = 100.dp

/** 表头容器的 UI 测试 tag —— 见 `TableColumnAlignmentTest`。 */
const val TABLE_HEADER_TAG = "sundays.tableHeader"

/** 表体容器的 UI 测试 tag —— 见 `TableColumnAlignmentTest`。 */
const val TABLE_BODY_TAG = "sundays.tableBody"

/**
 * 横向滚动条的 UI 测试 tag —— 见 `TableHorizontalScrollbarTest`。
 *
 * 需要它是因为「滚动条**在不在**」与「它有多长」都只能从**整体边界**量：
 * 拇指是一条 `Box`，既没有文本也没有 testTag，从语义树里根本没有对应的节点。
 */
const val TABLE_HSCROLLBAR_TAG = "sundays.tableHScrollbar"

/**
 * 分页底栏容器的 UI 测试 tag —— 见 `TablePaginationLayoutTest`。
 *
 * 需要它是因为「按钮标签有没有折行」这件事**只能量整体高度**：
 * 单看某个 `Text` 的语义边界，分行与不分行都可能给出看似正常的宽度。
 */
const val TABLE_PAGINATION_TAG = "sundays.tablePagination"

// ============================================================================
// 分页控件的 tag —— 键盘可达性
//
// 分页栏上一共 7 个可交互控件（每页选择器 + 4 个翻页按钮 + 菜单项），
// 而「首页 / 末页 / 共 N 条」在窄容器下会**消失**（见 PAGINATION_COMPACT_WIDTH）。
// 于是同一个「每页 20 / 下一页」在不同窗口宽度下对应不同的 Tab 序 ——
// 真窗口走查只能靠 tag 定位，没有第二条路。
// ============================================================================

/** 「每页 N」选择器。 */
const val TABLE_PAGE_SIZE_CHIP_TAG = "sundays.pageSizeChip"

/** 「每页」下拉里的单项 —— 前缀 + `PageSize.name`。 */
const val TABLE_PAGE_SIZE_ITEM_TAG = "sundays.pageSizeItem_"

/** 翻页按钮。 */
const val TABLE_NAV_FIRST_TAG = "sundays.navFirst"
const val TABLE_NAV_PREV_TAG = "sundays.navPrev"
const val TABLE_NAV_NEXT_TAG = "sundays.navNext"
const val TABLE_NAV_LAST_TAG = "sundays.navLast"

/** 「当前页 / 总页数」读数。 */
const val TABLE_PAGE_INDICATOR_TAG = "sundays.pageIndicator"

/**
 * 单元格 testTag 的前缀 —— 完整 tag 为 `"$TABLE_CELL_TAG_PREFIX<列 key>"`。
 *
 * 需要它是因为**手势只能从 `Box` 发**（`Text` 自身不认指针事件），而 `Text` 节点
 * 本身没有可注入的 pointer 入口 —— 测试点 `Text` 会报 "Failed to inject touch input"。
 * 挂 tag 后测试能精确点到某个单元格，而不是靠坐标猜。
 */
const val TABLE_CELL_TAG_PREFIX = "sundays.cell."

/**
 * 行首选中方框的 UI 测试 tag —— 见 `DataTableRowSelectionTest`。
 *
 * 需要它的理由与 [TABLE_CELL_TAG_PREFIX] 一样：方框内部就是一个 `Text("✓")`，
 * 既没有可点区域也没有别的稳定特征，测试只能靠 tag 精确定位「第 N 行的复选框」。
 * 完整 tag 为 `"$TABLE_ROW_CHECKBOX_TAG<行 id>"`。
 */
const val TABLE_ROW_CHECKBOX_TAG = "sundays.rowCheckbox."

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
 * 表头 —— 横向滚动由 [hScroll] 与表体**共享**（见 [DataTable] 调用点的说明），
 * 列名与数据列因此永远对齐。
 *
 * [hScroll] 必须由调用方创建而不是这里自己 `remember`：两处各自持有状态就是「表头能滚、
 * 表体不能滚」那个缺陷的成因。
 *
 * [contentWidth] 曾经排在 `horizontalScroll` **外面**，已挪到**内侧** ——
 * §9.24 记着「两种顺序下表头各列的语义宽度都 > 0、真窗口里表头照样只有一个列名，
 * 假设不成立，改动已回退」。那个结论是**错的，判据是错的**：
 * 它量的是「宽度 > 0」，而缺陷版本里每列 44.5dp 当然 > 0。
 *
 * 真正该量的是**表头与表体的逐列对齐**，而那才是坏的（43dp vs 102dp，见下方注释）。
 * 「量了一个对现象不敏感的量」是 §9.24 自己写下的教训，
 * 结果下一轮仍然栽在同一处 —— 这条 KDoc 留着，提醒后来人别再拿「> 0」当判据。
 */
@Composable
private fun TableHeader(
    columns: List<TableColumn>,
    theme: DataTableTheme,
    hScroll: ScrollState,
    /** 见 [contentWidthFor]：必须是显式宽度，不能用 `fillMaxWidth()`（滚动容器内是空操作）。 */
    contentWidth: Dp,
    rowSelectable: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                // ⚠️ **`horizontalScroll` 必须排在 `width` 之前** —— 顺序反了会同时坏掉两件事，
                // 而且坏得**很安静**：不报错、不塌成 0、只是列宽悄悄变成另一个数。
                //
                // `Modifier.width` 是**按传入约束钳位**的（`coerceIn(minWidth, maxWidth)`），
                // 不是「设一个绝对宽度」。排在外面时它拿到的是父 `Column` 给的
                // `maxWidth = 视口宽`，于是 `contentWidth` 被**钳回视口宽**：
                //
                // ```kotlin
                //   .width(contentWidth)        // ← 2346dp 被 coerce 成 1024dp（视口宽）
                //   .horizontalScroll(hScroll)  // ← 视口 = 1024dp，于是 weight 在 1024 里分
                // ```
                //
                // 实测（`runComposeUiTest`，23 个加权列，视口 1024dp，contentWidth 2346dp）：
                //
                // | | 每列间距 | 每列宽 |
                // |---|---|---|
                // | 表头 | **43dp** | 44.5dp（1024 ÷ 23） |
                // | 表体 | **102dp** | 102dp（2346 ÷ 23） |
                //
                // 两者的 `hScroll.maxValue` 都是 1322（= 2346 − 1024），所以**滚动量是对的**，
                // 也正因如此 §9.24 那些断言「两种顺序下都 > 0」全绿 —— 43dp 当然 > 0。
                // **表头与表体在用两套不同的宽度基准**，于是列名与数据列整体错位，
                // 而表头每列只剩 44.5dp（扣掉左右各 8dp 内边距后文本区仅 28dp），
                // `maxLines = 1` + `Ellipsis` 之下 22 个长列名被省略成看不出的一小截，
                // 只有 `id` 这种短名字还认得出 —— 即 §9.24「表头只剩第一个列名」。
                //
                // 挪进滚动层后 `width` 拿到的是**无界**约束，`coerceIn` 不再钳它，
                // 表头与表体用同一个 2346dp 基准分列，两者逐列对齐。
                .horizontalScroll(hScroll)
                .width(contentWidth)
                .background(theme.headerBackground)
                // 供 TableColumnAlignmentTest 量「表头是否真的能横滚」与表体位移是否一致
                .testTag(TABLE_HEADER_TAG)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rowSelectable) {
            // 表头这一格放「全选」。放在**表头**而不是工具条上，是因为它的语义就是
            // 「选中本页所有行」，而只有表头能同时看到列宽与「全选」这两个上下文。
            RowSelectionBox(checked = allSelected, onToggle = onToggleAll)
            VerticalDivider(color = theme.borderColor, modifier = Modifier.height(20.dp))
        }
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

/**
 * 行首的选中方框 —— 表头的「全选」与数据行的「选中本行」共用。
 *
 * 自己画而不用 Material3 `Checkbox`：那个控件带 48dp 最小可点击区域，
 * 放进 20dp 高的表格行会把行高一倍不止，一屏能看的行数直接少一半。
 */
@Composable
private fun RowSelectionBox(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border =
        if (checked) {
            androidx.compose.material3.MaterialTheme.colorScheme.primary
        } else {
            androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
        }
    Box(
        modifier =
            modifier
                .width(ROW_SELECTION_COLUMN_WIDTH)
                // ⚠️ 这里**不能**用 `fillMaxHeight()`。
                //
                // 表头那个 `Row` 是 `horizontalScroll` 里高度不定的行，它拿到的是**整列的
                // 最大高度**约束；给子项加 `fillMaxHeight()` 会把「表头这一行」撑成满高，
                // 于是表头文字被垂直居中到整张表的中间，分页栏被顶到窗口外面 ——
                // 表现是「表格看起来少了一大截、翻页按钮点不到」，而根因在一处修饰符上。
                //
                // 数据行里同一个修饰符是无害的（LazyColumn item 的 maxHeight 是无穷），
                // 正因为「有的地方没事有的地方出事」，它才更难被当场发现。
                .padding(horizontal = 12.dp)
                .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(14.dp)
                    .background(
                        if (checked) {
                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ).border(1.dp, border),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 行首复选框列的宽度 —— 表头与表体共用同一个值，否则两者会错位。 */
private val ROW_SELECTION_COLUMN_WIDTH = 40.dp

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
    editable: Boolean,
    onCellEdit: ((CellEdit) -> Unit)?,
    isColumnEditable: (TableColumn) -> Boolean,
    isCellPending: (TableRow, TableColumn) -> Boolean,
    isColumnNullable: (TableColumn) -> Boolean,
    isRowDeleted: (TableRow) -> Boolean,
    rowSelectable: Boolean,
    selectedRowIds: Set<Any>,
    onSelectedRowIdsChange: (Set<Any>) -> Unit,
    /** 见 [contentWidthFor] —— 行宽必须显式给，不能靠 `fillMaxWidth()`。 */
    contentWidth: Dp,
) {
    if (rows.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(str(StrKey.EMPTY_DATA), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier =
            Modifier
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
                // 行级 `clickable`（选中行）只在**真能编辑**时才去掉。判据不是
                // 「传了 onCellEdit」而是「调用方确认这张表可写」——
                // 后者由 [DataTable.cellEditable] 表达，见其 KDoc；这里经 [TableBody] 转手。
                editable = editable,
                onCellEdit = onCellEdit,
                isColumnEditable = isColumnEditable,
                isCellPending = isCellPending,
                isColumnNullable = isColumnNullable,
                isRowDeleted = isRowDeleted(row),
                rowSelectable = rowSelectable,
                isRowChecked = row.id in selectedRowIds,
                onToggleRowChecked = {
                    onSelectedRowIdsChange(
                        if (row.id in selectedRowIds) selectedRowIds - row.id else selectedRowIds + row.id,
                    )
                },
                contentWidth = contentWidth,
            )
            WinDivider(color = theme.borderColor)
        }
    }
}

/**
 * 一次单元格编辑 —— [DataTable.onCellEdit] 的入参。
 *
 * 用 `data class` 而不是三个裸参数：调用方要发一条 `UPDATE`，需要**主键**来定位行、
 * **列 key** 来定位字段、新旧值都要拿到。三个参数散着传迟早在调用点写错顺序，
 * 而这种错误编译器不拦、运行时表现为「改错了列」。
 */
data class CellEdit(
    /** 目标行的主键（[TableRow.id]）。 */
    val rowId: Any,
    /** 目标列的 [TableColumn.key]。 */
    val columnKey: String,
    /** 编辑前的原值（调用方可用于生成 `WHERE ... = <旧值>` 或写审计日志）。`null` = 原来是 SQL NULL。 */
    val oldValue: String?,
    /**
     * 用户提交的新值；`null` = **显式**要求写成 SQL NULL。
     *
     * 为什么用 `null` 而不是空串：数据库里 `''` 与 `NULL` 是两种东西（见 [CellValueField]
     * 的说明），而协议的 `map<string, string>` 也表达不了 null。所以「置 NULL」
     * 必须在这里有一个与「置空串」**类型上就不同**的表示方式 ——
     * 否则前端迟早会把两者混成同一个 `""` 发出去。
     */
    val newValue: String?,
) {
    init {
        require(columnKey.isNotBlank()) { Msg.text(StrKey.CELL_EDIT_NO_COLUMN_KEY) }
        require(newValue != oldValue) {
            Msg.format(StrKey.CELL_EDIT_SAME_VALUE, oldValue ?: NULL_PLACEHOLDER)
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
    editable: Boolean,
    onCellEdit: ((CellEdit) -> Unit)?,
    isColumnEditable: (TableColumn) -> Boolean,
    /** 该列能否取 SQL NULL —— 决定编辑框里要不要画 `∅`。 */
    isColumnNullable: (TableColumn) -> Boolean,
    isCellPending: (TableRow, TableColumn) -> Boolean,
    /** 整行是否已标记删除 —— 用 [DataTableTheme.cellDeletedBackground] 标出来。 */
    isRowDeleted: Boolean,
    rowSelectable: Boolean,
    isRowChecked: Boolean,
    onToggleRowChecked: () -> Unit,
    /** 行宽 —— 见 [contentWidthFor]。 */
    contentWidth: Dp,
) {
    val background =
        when {
            // 已标记删除优先于选中/斑马纹：它是**后果最重**的状态 ——
            // 提交后这一行就没了。选中只是要看详情，斑马纹只是排版辅助。
            isRowDeleted -> theme.cellDeletedBackground

            isSelected -> theme.rowBackgroundSelected

            isAlternate && theme.rowBackgroundAlt != null -> theme.rowBackgroundAlt

            else -> theme.rowBackground
        }
    val cellStyle = if (isSelected && theme.cellTextSelected != null) theme.cellTextSelected else theme.cellText

    // 正在编辑的单元格列 key —— 提升到**行**这一层而不是各自 remember：
    // 同一时刻只允许一个单元格处于编辑态，双击另一个格子时旧的那个必须**自动退出**，
    // 否则界面上会同时出现两个输入框，而 [DataTable.onCellEdit] 只会收到后者的提交。
    var editingColumn by remember(row.id) { mutableStateOf<String?>(null) }

    // Modifier.onTableRightClick 监听右键 → 显示 context menu
    //
    // ⚠️ 可编辑时整行的 `clickable`（选中行）**必须去掉**：它与单元格的
    // `detectTapGestures` 抢同一个手势，且父节点先拿到 —— 表现是点单元格只切换
    // 选中、永远进不了编辑。改为在单元格里**单击**就进编辑。
    //
    // 判据用 [anyEditableColumn] 而不是入参 `editable`：表级说「可写」但逐列谓词把
    // **所有**列都否掉了时（比如整张表只有一列，而那列恰好是主键），行里并没有任何
    // 单元格会抢手势，此时去掉 `clickable` 只会让整行变成点不动的死区。
    val anyEditableColumn = editable && onCellEdit != null && columns.any { isColumnEditable(it) }
    //
    // ⚠️ [SelectionContainer] 与 [Row] 的**嵌套顺序不能动**。原实现是
    // `SelectionContainer { Row { … } }`；换成 `Row { SelectionContainer { … } }`
    // 之后实测 `TableColumnAlignmentTest` 两条一起红（表体 `maxValue` 变 0，
    // 即整表不再横向溢出）—— 12 列 × 140dp 的定宽列宽被压没了。
    // 「把 SelectionContainer 挪进 Row 里、只包单元格」这个看起来无害的重构，
    // 改变了受约束节点的测量链。
    val rowModifier =
        Modifier
            // ⚠️ **不能**用 `fillMaxWidth()`：本行在 `horizontalScroll` 里，拿到的是无界宽度，
            // fillMaxWidth 会静默失效，整行收缩成一个字符宽、几列叠在一起（见 [contentWidthFor]）。
            .width(contentWidth)
            .background(background)
            .then(if (anyEditableColumn) Modifier else Modifier.clickable(onClick = onClick))
            .onRightClick { offset -> contextMenuState.show(offset, row) }
            .padding(horizontal = 12.dp, vertical = 8.dp)

    // 「正在编辑哪一列」要先过一遍**同一个**判据再往下用。
    //
    // 为什么：`editingColumn` 是 remember 下来的状态，而可编辑性是会变的 ——
    // 用户编辑到一半打开只读开关，或这一页重新载入后该列变成了只读列。
    // 这时若还拿旧值往下判断，既会渲染出一个「不能编辑却顶着编辑框」的格子，
    // 更麻烦的是 `editingColumn != null` 会让整行**跳过 [SelectionContainer]**，
    // 于是这一行再也点不动、也没有任何东西去清掉这个状态 —— 卡死的死区。
    //
    // 在**行这一层**算一次而不是每格算：它与「当前是第几列」无关。
    val editingKey: String? =
        editingColumn?.takeIf { key ->
            editable && onCellEdit != null && columns.any { it.key == key && isColumnEditable(it) }
        }

    @Composable
    fun RowScope.rowContent() {
        TableRowCells(columns = columns, theme = theme) { column, modifier ->
            // 原始值。必须从 `cells` 直取而不是用 [TableRow.formatted]：后者把 null
            // 也变成 `""`，而 NULL 与空串正是这里要区分开的两种东西。
            val rawValue = row.cells[column.key]
            val isNullValue = rawValue == null
            val value = row.formatted(column)
            // 逐列收窄后的最终判据。三个条件缺一不可：
            // 表可写、调用方挂了回调、这一列本身允许改。
            //
            // 已标记删除的行一律不再可编辑：这一行提交后就不存在了，
            // 还在上面改值会让人以为改动会一起生效。
            val canEditThisCell = editable && onCellEdit != null && !isRowDeleted && isColumnEditable(column)
            if (editingKey == column.key) {
                CellEditor(
                    initial = if (isNullValue) null else value,
                    theme = theme,
                    nullable = isColumnNullable(column),
                    onCancel = { editingColumn = null },
                    onCommit = { newValue ->
                        editingColumn = null
                        // 值没变就不发回调：否则「点开又按 Esc」就会写一条
                        // `UPDATE SET x = x`，把表的修改时间白白弄脏。
                        if (newValue != (if (isNullValue) null else value)) {
                            onCellEdit?.invoke(CellEdit(row.id, column.key, if (isNullValue) null else value, newValue))
                        }
                    },
                    modifier = modifier,
                )
            } else {
                TableCell(
                    // NULL 显示成斜体 `NULL` 而不是留白 —— 留白与空串在视觉上完全一样，
                    // 而它们在数据库里是两回事（见 [CellValueField]）。
                    text = if (isNullValue) NULL_PLACEHOLDER else value,
                    column = column,
                    theme = theme,
                    cellStyle = if (isNullValue) (theme.cellNullText ?: cellStyle) else cellStyle,
                    modifier = modifier,
                    // 可编辑时**单击**即进编辑。
                    //
                    // 为什么不是双击：双击要先等判定间隔、且与「选中行」这个高频动作
                    // 语义重叠 —— 用户在可编辑表格里点一下就想改值，选中行是**浏览**态才需要的。
                    cellModifier =
                        if (canEditThisCell) {
                            Modifier
                                .testTag(TABLE_CELL_TAG_PREFIX + column.key)
                                .pointerInput(row.id, column.key) {
                                    detectTapGestures(
                                        onTap = { editingColumn = column.key },
                                        onDoubleTap = { editingColumn = column.key },
                                    )
                                }
                        } else {
                            Modifier
                        }.let { base ->
                            // 待提交底色画在 `cellModifier` 上而不是 `modifier`：
                            // `modifier` 里带着 `weight`，插在它前面会让列宽塌成内容宽
                            // （见 [TableCell] 的注释）。这里已经在 Box 的修饰符链里，
                            // 且在 `.padding(horizontal = 8.dp)` **之前**，所以底色铺满整格宽度。
                            //
                            // 编辑中的那一格**不画**待提交底色：它已经有编辑框自己的底色，
                            // 两层叠在一起会看不清当前是不是在改这一格。
                            if (isCellPending(row, column) && editingKey != column.key) {
                                base.background(theme.cellPendingBackground)
                            } else {
                                base
                            }
                        },
                )
            }
        }
    }

    // 判 [editingKey] 而不是 remember 出来的 [editingColumn]：后者可能因为这一列
    // 变成不可编辑而已经失效（见其上方注释），拿它判会让整行永远停在「编辑态」分支上。
    if (editingKey == null) {
        // 只读态：保留 [SelectionContainer] 让用户拖拽选择单元格文本。
        SelectionContainer {
            Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
                if (rowSelectable) {
                    RowSelectionBox(checked = isRowChecked, onToggle = onToggleRowChecked)
                    VerticalDivider(color = theme.borderColor, modifier = Modifier.height(20.dp))
                }
                rowContent()
            }
        }
    } else {
        // 编辑态：**必须**去掉 [SelectionContainer] —— 它会消费指针事件来支持拖选，
        // 双击/单击因此到不了单元格（实测报 "Failed to inject touch input"）。
        // 这不损失什么：用户此时要做的是「改值」，编辑框自带光标，静态文本本来就不该被选中。
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            // 复选框**不受**编辑态影响：它是这一行唯一的「选中」入口，
            // 拿掉它就等于在可编辑的表上完全没法多选，也就做不了批量删除。
            if (rowSelectable) {
                RowSelectionBox(checked = isRowChecked, onToggle = onToggleRowChecked)
                VerticalDivider(color = theme.borderColor, modifier = Modifier.height(20.dp))
            }
            rowContent()
        }
    }
}

/**
 * 单元格内联编辑器 —— 一个只占**当前列宽**的 `BasicTextField`。
 *
 * ## 为什么不用弹窗
 *
 * 弹窗会打断「连续改多格」的节奏：改一格→确认→关→再定位下一格。表格编辑的价值恰恰
 * 在于连续改，内联编辑才配得上。
 *
 * ## 提交时机
 *
 * `Enter` 提交、`Esc` 取消、**失焦提交**。失焦提交是必须的：用户点下一个格子时当前格
 * 要落盘，否则那次编辑会静默丢失 —— 界面看上去「点了没反应」，比报错更难查。
 */
@Composable
private fun CellEditor(
    initial: String?,
    theme: DataTableTheme,
    /** 该列能否取 NULL —— 为 `false` 时不画 `∅` 开关（往 `NOT NULL` 列写 NULL 必然失败）。 */
    nullable: Boolean,
    onCancel: () -> Unit,
    onCommit: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    // 焦点状态自己跟，不用 `FocusRequester.focused` —— 后者在当前 Compose 版本上
    // 不是可用的公开成员，编译不过；而 `onFocusChanged` 稳定得多。
    var focused by remember { mutableStateOf(false) }

    // 失焦提交。用 `LaunchedEffect(focused)` 而不是在 `onFocusChanged` 里直接提交：
    // 后者在**提交动作本身**导致的失焦上也会触发，会把刚提交的值又提交一次。
    LaunchedEffect(focused) {
        if (!focused) onCommit(text)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 取值控件本身在 [CellValueField]（表内编辑与「新增行」弹窗共用同一套 NULL 约定）。
    // 焦点与 `SetText` 语义挂在**外层**而不是 TextField 上：列宽约束来自
    // `TableRowCells`，与 TextField 的内部节点不在同一层，挂错地方读屏软件
    // 会认为这格「只是个文字」。
    CellValueField(
        value = text,
        nullable = nullable,
        theme = theme,
        onValueChange = { text = it },
        onSubmit = { onCommit(text) },
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    setText { new ->
                        // 入参是 AnnotatedString（与 `TextField` 内部同名回调一致），
                        // 状态里存的是 String，这里取 .text
                        text = new.text
                        true
                    }
                }.onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.Enter -> {
                            onCommit(text)
                            true
                        }

                        Key.Escape -> {
                            onCancel()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
    )
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
        if (column != columns.last()) {
            VerticalDivider(
                color = theme.borderColor,
                modifier = Modifier.height(20.dp),
            )
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
    cellModifier: Modifier = Modifier,
) {
    val style = if (isHeader) theme.headerText else cellStyle
    Box(
        // `cellModifier`（单击进编辑的 `pointerInput` + testTag）挂在 **Box** 上而不是
        // `Text` 上：`Text` 自身不接收指针事件（它没有 pointerInput），手势得由父级认领。
        // 代价是 UI 测试必须点 Box（`Modifier` 所在处）而不是 `Text` 节点。
        //
        // ⚠️ `modifier`（含 `Modifier.weight(...)`）必须排在这两者**之前**。
        // 顺序不是随意的：`weight` 依赖 RowScope，插在中间的节点修饰符会截断它，
        // 表现为所有列宽塌成内容宽、整表不再横向溢出。
        modifier =
            modifier
                .then(cellModifier)
                .padding(horizontal = 8.dp),
        contentAlignment =
            when (column.alignment) {
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
            modifier =
                Modifier
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
                    text = str(StrKey.PER_PAGE),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color,
                    modifier = Modifier.padding(end = 8.dp),
                    maxLines = 1,
                    softWrap = false,
                )
                Box {
                    AssistChip(
                        onClick = { pageSizeExpanded = true },
                        label = { Text(pageSize.labelText(), maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag(TABLE_PAGE_SIZE_CHIP_TAG),
                    )
                    DropdownMenu(
                        expanded = pageSizeExpanded,
                        onDismissRequest = { pageSizeExpanded = false },
                    ) {
                        pageSizeOptions.forEach { size ->
                            WinMenuItem(
                                // 菜单项不获取焦点，只能靠 tag 定位
                                modifier = Modifier.testTag(TABLE_PAGE_SIZE_ITEM_TAG + size.name),
                                text = { Text(size.labelText()) },
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
                        text = plural(PluralStrKey.TOTAL_RECORDS, totalCount, totalCount),
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
                    NavButton(str(StrKey.FIRST_PAGE), enabled = currentPage > 1, tag = TABLE_NAV_FIRST_TAG) {
                        onPageChange(1)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                NavButton(str(StrKey.PREV_PAGE), enabled = currentPage > 1, tag = TABLE_NAV_PREV_TAG) {
                    onPageChange(currentPage - 1)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$currentPage / $totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(TABLE_PAGE_INDICATOR_TAG),
                )
                Spacer(modifier = Modifier.width(8.dp))
                NavButton(str(StrKey.NEXT_PAGE), enabled = currentPage < totalPages, tag = TABLE_NAV_NEXT_TAG) {
                    onPageChange(currentPage + 1)
                }
                if (!compact) {
                    Spacer(modifier = Modifier.width(4.dp))
                    NavButton(str(StrKey.LAST_PAGE), enabled = currentPage < totalPages, tag = TABLE_NAV_LAST_TAG) {
                        onPageChange(totalPages)
                    }
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
private fun NavButton(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    WinButton(
        onClick = onClick,
        enabled = enabled,
        shape = SundaysPalette.buttonShape,
        modifier = Modifier.testTag(tag),
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
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Text(
            text = str(StrKey.DETAIL),
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
                    text = str(StrKey.DETAIL_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.headerText.color.copy(alpha = 0.6f),
                )
            }
        } else {
            SelectionContainer {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    DetailField(str(StrKey.PRIMARY_KEY), row.id.toString(), theme)
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
private fun DetailField(
    label: String,
    value: String,
    theme: DataTableTheme,
) {
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

private val PREVIEW_COLUMNS =
    listOf(
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
        },
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
