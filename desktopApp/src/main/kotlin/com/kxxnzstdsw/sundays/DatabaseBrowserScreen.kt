package com.kxxnzstdsw.sundays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.protobuf.Value as ProtoValue
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 数据库浏览屏幕 —— 第二屏；**多 sheet**：每个已建连接的数据库浏览会话是一个 sheet，
 * sheet 之间互不干扰（左树 / preview 标签页 / 已展开节点 / SQL 工作台状态各自独立）。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │ Sheets:  [＋] [ Conn A ×] [ Conn B ×] ...                               │
 * ├────────────────────────────────────────────────────────────────────────┤
 * │ ToolBar:  [ ▶ SQL 工作台 ]  ← 归属下方这块 sheet 内容                    │
 * ├──────────────┬─────────────────────────────────────────────────────────┤
 * │              │  ┌── Table Preview Tab Area ──┐  切换到 SQL 工作台时:     │
 * │  Schemas     │  │ Tab: [ users | orders ]    │                          │
 * │   ▾ PUBLIC   │  ├───────────────────────────┤  ┌─ 编辑器 (60%) ──────┐ │
 * │      ▸ users │  │  DataTable(rows=preview)   │  │ SELECT * FROM ...  │ │
 * │      ▸ orders│  │                            │  ├─────────────────────┤ │
 * │   ▸ MYDB     │  │                            │  │ 结果 (40%)          │ │
 * │              │  └───────────────────────────┘  └─────────────────────┘ │
 * └──────────────┴─────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 交互
 *
 * - **「＋」sheet 标签**：调 [onAddSheet]，由调用方弹出 [AddConnectionDialog]。
 * - **切换 sheet**：调 [onSelectSheet]，目标 sheet 获得焦点；其它 sheet 的状态保留。
 * - **关闭 sheet**：「×」调 [onCloseSheet]；同时释放该 sheet 的连接池并断开引擎会话。
 * - 选中 sheet 的连接后，左侧自动加载数据库列表(`SCHEMA.LIST level=database`)
 * - 展开数据库节点 → 加载表列表(`TABLE.LIST`)
 * - **双击**表节点 → 打开预览标签页(同一表只存在一个标签页)
 * - 标签页关闭 → `removeTab`; 切换标签页只切换显示, 不重新加载
 * - **「▶ SQL 工作台」工具栏按钮** → 把右栏从表预览切换为 SQL 工作台
 *   （编辑器 + 结果面板），由 `DatabaseBrowserState.activePane` 控制；再次点击切回。
 *   工作台内点 **「执行 SQL」** 走 `SQL.EXECUTE` 引擎流式：SELECT 行帧 → 结果表；
 *   非 SELECT（DML/DDL）→ 单条响应带 `affected_rows`；失败 → 错误条。
 *
 * ## 引擎耦合
 *
 * 通过 [EngineClient.invoke] 走强类型 `SCHEMA.LIST` / `TABLE.LIST` / `DATA.LIST` 路径
 * (与 `ConnectionManagerScreen` 的调用方式一致 —— 详见 desktopApp/ARCHITECTURE.md §引擎接入).
 * SQL 执行额外使用 [EngineClient.handle] 走 `Category.SQL` / `Action.EXECUTE` 流式通道，
 * 收集 `sql_row_frame` + 终止帧 (`RequestDispatcher.streamSqlExecute`) 拼装结果。
 *
 * 状态由调用方持有（[DatabaseBrowserState]，在 `MainScreen` 中按 sheet id 各自 `remember`），
 * 本组件只负责渲染 + 事件转发 + sheet 副作用 —— 因此同一份状态机可脱离 UI 直接驱动
 * （见 `DatabaseBrowserFlowTest`）。
 *
 * @param sheets 已打开 sheet 的描述列表（连接 + 独立 `DatabaseBrowserState` + 当前引擎状态）
 * @param activeSheetId 当前激活的 sheet id（必须在 [sheets] 中）
 * @param connections 全部已保存连接（备用，UI 当前未直接渲染 —— 切换 sheet 在 [onSelectSheet]）
 * @param onSelectSheet 切换激活 sheet
 * @param onCloseSheet 关闭 sheet（调 [DatabaseBrowserState.releasePools] 释放浏览器侧派生池 + 调用方断开引擎会话）
 * @param onAddSheet 「＋」点击回调 —— 调用方弹出 [AddConnectionDialog]
 * @param onConnect 建立连接（调用方经 `EngineClient.testConnection`）
 * @param onDisconnect 断开连接（调用方经 `EngineClient.disconnect`）
 */
@Composable
fun DatabaseBrowserScreen(
    sheets: List<SheetDescriptor>,
    activeSheetId: String?,
    connections: List<ConnectionConfig>,
    onSelectSheet: (String) -> Unit,
    onCloseSheet: (String) -> Unit,
    onAddSheet: () -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 没有 sheet 时不渲染标签条：Material3 的 ScrollableTabRow 不接受 0 个 tab
        // （空列表会在测量时 IndexOutOfBounds）。关闭最后一个 sheet 时，`destination` 由
        // MainScreen 的 LaunchedEffect 在**组合之后**才切回首屏 —— 这中间会有一帧以空列表组合，
        // 因此这里必须走「空态引导」而不是标签条。
        val active = sheets.firstOrNull { it.connection.id == activeSheetId }
        if (active == null) {
            EmptySheetsHint(
                onAddSheet = onAddSheet,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        SheetTabRow(
            sheets = sheets,
            activeSheetId = activeSheetId,
            onSelect = onSelectSheet,
            onClose = onCloseSheet,
            onAdd = onAddSheet,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ActiveSheetContent(
            sheet = active,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 一个数据库浏览 sheet 的完整描述 —— 持有独立的 [DatabaseBrowserState] 与该 sheet 的连接配置 + 引擎会话状态。
 */
data class SheetDescriptor(
    val connection: ConnectionConfig,
    val browser: DatabaseBrowserState,
    val status: ConnectionStatus,
)

/**
 * 右栏展示模式 —— 每 sheet 独立，由 [DatabaseBrowserState.activePane] 持有。
 *
 * - [TABLE] = 表预览（双击表节点打开的标签页区）；初始值
 * - [SQL] = SQL 工作台（编辑器 + 底部结果面板）；由工具栏切换
 */
enum class BrowserPane { TABLE, SQL }

// ============================================================================
// Sheet 标签条 / 数据列表：并 ＋ 入口
// ============================================================================

@Composable
private fun SheetTabRow(
    sheets: List<SheetDescriptor>,
    activeSheetId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「＋添加连接」按钮：固定在最左，独立于 sheet 标签条；
        // 点击后由调用方弹出 AddConnectionDialog。
        IconButton(
            onClick = onAdd,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "添加连接",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        VerticalDivider(
            modifier = Modifier.height(28.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SecondaryScrollableTabRow(
            selectedTabIndex = sheets.indexOfFirst { it.connection.id == activeSheetId }.coerceAtLeast(0),
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            modifier = Modifier.weight(1f),
        ) {
            sheets.forEach { sheet ->
                val active = sheet.connection.id == activeSheetId
                Tab(
                    selected = active,
                    onClick = { onSelect(sheet.connection.id) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionStatusDot(sheet.status.state, 10.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(text = sheet.connection.name, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭 sheet",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onClose(sheet.connection.id) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptySheetsHint(
    onAddSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "尚未打开任何连接",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点击「添加连接」配置一个新连接，或在首屏选中已有连接后点「连接」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddSheet) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("添加连接")
        }
    }
}

/**
 * BrowserToolBar —— sheet 标签条之上的工具栏，操作当前激活 sheet 的右栏内容。
 *
 * 当前提供：
 * - **「▶ SQL 工作台」** —— 切换右栏在表预览与 SQL 工作台之间的展示。
 *   状态由 [DatabaseBrowserState.activePane] 持有，每 sheet 独立。
 *   未连接时禁用（SQL 执行必须依赖已建立的连接池）。
 */
@Composable
private fun BrowserToolBar(
    activePane: BrowserPane,
    connected: Boolean,
    onToggleSqlWorkbench: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val inWorkbench = activePane == BrowserPane.SQL
            Button(
                onClick = onToggleSqlWorkbench,
                enabled = connected,
                colors = if (inWorkbench) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) {
                Icon(
                    imageVector = if (inWorkbench) Icons.Filled.TableChart else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (inWorkbench) "返回表预览" else "SQL 工作台")
            }
            if (!connected) {
                Text(
                    text = "（未连接，工作台不可用）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 激活 sheet 的内容：连接选择条 + 左侧 schema 树 + 右侧 preview 标签页区。
 * 与原单连接 [DatabaseBrowserScreen] 行为完全一致 —— 只是 `bindConnection` / `refreshDatabases`
 * 作用的 [browser] 由激活 sheet 持有，与其它 sheet 互不影响。
 */
@Composable
private fun ActiveSheetContent(
    sheet: SheetDescriptor,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 注入当前连接（连接变化时清空派生状态），随后在已连接时自动加载数据库列表。
    LaunchedEffect(sheet.connection.id, sheet.status.state) {
        sheet.browser.bindConnection(sheet.connection)
        when {
            sheet.status.state == ConnectionState.CONNECTED -> sheet.browser.refreshDatabases()
            sheet.status.state == ConnectionState.DISCONNECTED -> sheet.browser.releasePools()
            else -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 工具栏归属当前激活 sheet —— 放在内容区内（而非 sheet 标签条之上），
        // 这样工具与它作用的连接在同一视觉块内，切换 sheet 时工具栏也随之更换。
        BrowserToolBar(
            activePane = sheet.browser.activePane,
            connected = sheet.status.state == ConnectionState.CONNECTED,
            onToggleSqlWorkbench = sheet.browser::toggleSqlWorkbench,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(modifier = Modifier.fillMaxSize()) {
            SchemaTreePanel(
                state = sheet.browser,
                connected = sheet.status.state == ConnectionState.CONNECTED,
                onOpenTable = { schema, table -> sheet.browser.openTab(schema, table) },
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            when (sheet.browser.activePane) {
                BrowserPane.TABLE -> PreviewTabArea(
                    state = sheet.browser,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                BrowserPane.SQL -> SqlWorkbenchPane(
                    state = sheet.browser,
                    connected = sheet.status.state == ConnectionState.CONNECTED,
                    schema = sheet.browser.currentSchema(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: ConnectionStatus) {
    val (label, color) = when (status.state) {
        ConnectionState.CONNECTED -> "已连接 · ${status.message}" to MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> "连接中…" to MaterialTheme.colorScheme.tertiary
        ConnectionState.FAILED -> "失败 · ${status.message}" to MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * 连接状态点 —— sheet 标签上展示引擎会话状态的小色点。
 */
@Composable
internal fun ConnectionStatusDot(state: ConnectionState, dotSize: androidx.compose.ui.unit.Dp) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.FAILED -> MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Canvas(modifier = Modifier.size(dotSize)) {
        drawCircle(color = color)
    }
}

// ============================================================================
// 左侧 schema/表 树
// ============================================================================

@Composable
private fun SchemaTreePanel(
    state: DatabaseBrowserState,
    connected: Boolean,
    onOpenTable: (schema: String, table: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "数据库 / 表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                !connected -> EmptyHint(
                    title = "未连接",
                    description = "请先在「连接管理」建立连接并返回此页。",
                )
                state.loadingDatabases -> EmptyHint(title = "加载数据库中…", description = null)
                state.errorMessage != null -> EmptyHint(
                    title = "加载失败",
                    description = state.errorMessage,
                )
                state.databases.isEmpty() -> EmptyHint(
                    title = "无数据库",
                    description = "当前连接下未发现任何数据库。",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.databases) { db ->
                        DatabaseNode(
                            name = db,
                            state = state,
                            onOpenTable = onOpenTable,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseNode(
    name: String,
    state: DatabaseBrowserState,
    onOpenTable: (schema: String, table: String) -> Unit,
) {
    val expanded = name in state.expandedDatabases
    val tables = state.tablesByDatabase[name]
    val loading = name in state.loadingTables
    val error = state.tableLoadError[name]

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.toggleDatabase(name) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        if (expanded) {
            when {
                error != null -> Text(
                    text = "  加载失败: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                )
                tables == null -> Text(
                    text = "  加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                )
                tables.isEmpty() -> Text(
                    text = "  (空)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                )
                else -> tables.forEach { tbl ->
                    TableLeaf(
                        tableName = tbl,
                        onOpen = { onOpenTable(name, tbl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TableLeaf(
    tableName: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(tableName) {
                detectTapGestures(onDoubleTap = { onOpen() })
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(20.dp))
        Icon(
            imageVector = Icons.Filled.TableChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = tableName,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyHint(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// 右侧 标签页 + 预览 DataTable
// ============================================================================

@Composable
private fun PreviewTabArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.tabs.isEmpty()) {
            EmptyHint(
                title = "尚无打开的表",
                description = "在左侧双击表名即可在此预览数据。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TabStrip(
                tabs = state.tabs.map { it.title },
                selectedIndex = state.selectedTabIndex.coerceAtLeast(0),
                onSelect = state::selectTab,
                onClose = state::closeTab,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val current = state.tabs.getOrNull(state.selectedTabIndex)
            if (current != null) {
                PreviewTabContent(
                    tab = current,
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                            contentDescription = "关闭标签页",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onClose(index) },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PreviewTabContent(
    tab: TablePreviewTab,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
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
                    tab.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("加载中…", style = MaterialTheme.typography.labelMedium)
                    }
                    tab.error != null -> Text(
                        text = "错误: ${tab.error}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        text = "共 ${tab.total} 行 · 第 ${tab.page} 页 · 每页 ${tab.pageSize}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            tab.columns.isNotEmpty() || tab.rows.isNotEmpty() -> DataTable(
                columns = tab.columns,
                rows = tab.rows,
                modifier = Modifier.fillMaxSize(),
                pageSize = PageSize.S100,
                fillParentHeight = false,
            )
            !tab.loading && tab.error == null -> EmptyHint(
                title = "无数据",
                description = "表为空或无法读取列元数据。",
                modifier = Modifier.fillMaxSize(),
            )
            else -> Spacer(Modifier.fillMaxSize())
        }
    }
}

/**
 * SQL 工作台 —— 编辑器（顶部 60%） + 结果面板（底部 40%）。
 *
 * 编辑器用 [CodeEditorWithToolbar]（`:shared` 的 `commonMain/.../editor/ui/CodeEditor.kt`）：
 * 固定 `languageId = "sql"`（隐藏语言切换器 —— 本工作台只处理 SQL），内置「格式化」按钮
 * 走注册表里的 SQL formatter，「执行 SQL」经 `actions` 插槽注入。
 * SQL 语言与 formatter 由 app 启动时的 `registerBuiltinEditors()` 注册（幂等）。
 *
 * 结果面板四种态：
 * - `loading` → 行内 spinner
 * - `error` → 错误文案
 * - `affectedRows != null`（非 SELECT）→ 「已影响 N 行」
 * - 有 `rows` → [DataTable]
 * - 否则 → 「执行 SQL 后在此查看结果」占位
 */
@Composable
private fun SqlWorkbenchPane(
    state: DatabaseBrowserState,
    connected: Boolean,
    schema: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 顶部信息条：当前执行的 catalog —— 说明 SQL 会落到哪个库
        Surface(
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "SQL 工作台",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (schema.isNotBlank()) "schema: $schema" else "默认 catalog",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 上编辑器 + 下结果（fillMaxHeight 60% / 40% 通过 weight 分配）
        Column(modifier = Modifier.fillMaxSize()) {
            CodeEditorWithToolbar(
                text = state.sqlEditorText,
                onTextChange = { state.sqlEditorText = it },
                languageId = "sql",
                // 本工作台只处理 SQL —— 不暴露语言切换器
                showLanguageSwitcher = false,
                actions = {
                    Button(
                        onClick = { state.executeSql(state.sqlEditorText) },
                        enabled = connected && !state.sqlRunning && state.sqlEditorText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.sqlRunning) "执行中…" else "执行 SQL")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SqlResultArea(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
            )
        }
    }
}

/**
 * SQL 工作台结果面板 —— 状态机持有四类结果字段之一为有效值，渲染其一。
 */
@Composable
private fun SqlResultArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        when {
            state.sqlRunning -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("执行中…", style = MaterialTheme.typography.labelMedium)
                }
            }
            state.sqlError != null -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = "错误: ${state.sqlError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.sqlAffectedRows != null -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = "已影响 ${state.sqlAffectedRows} 行（非 SELECT 语句）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.sqlResultRows.isNotEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "查询结果 · ${state.sqlRowCount} 行",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DataTable(
                    columns = state.sqlResultColumns,
                    rows = state.sqlResultRows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = PageSize.S100,
                    fillParentHeight = false,
                )
            }
            else -> EmptyHint(
                title = "尚未执行 SQL",
                description = "在上方编辑器输入 SQL，点「执行 SQL」或 Ctrl+Enter 即在此查看结果。",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ============================================================================
// 数据库浏览器状态机 —— 与 UI 解耦, 由 [DatabaseBrowserScreen] 创建
// ============================================================================

/**
 * 单个表预览标签页的状态.
 *
 * `key = schema + "::" + tableName` 作为去重主键: 同一张表只能有一个打开的标签页.
 *
 * - [columns] / [rows] 渲染到 [DataTable]
 * - [loading] / [error] 控制顶部信息条
 * - [page] / [pageSize] / [total] 展示分页元信息(仅供 UI 显示)
 */
class TablePreviewTab(
    val schema: String,
    val tableName: String,
    val title: String = tableName,
) {
    val key: String get() = "$schema::$tableName"

    var columns: List<TableColumn> by mutableStateOf(emptyList())
    var rows: List<TableRow> by mutableStateOf(emptyList())
    var loading: Boolean by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
    var total: Long by mutableStateOf(0L)
    var page: Int by mutableStateOf(1)
    var pageSize: Int by mutableStateOf(100)
}

/**
 * 数据库浏览屏幕的状态机 —— 维护数据库/表加载、标签页打开/关闭/选择,
 * 以及当前引擎会话状态(连接 ID 仅由 [DatabaseBrowserScreen] 在外层持有).
 */
class DatabaseBrowserState(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
) {
    /** 数据库名称列表(来自 SCHEMA.LIST level=database) */
    var databases: List<String> by mutableStateOf(emptyList())
        private set

    var loadingDatabases: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    /** 已展开的数据库节点 */
    val expandedDatabases: SnapshotStateSet<String> = mutableStateSetOf()

    /** database → table list(来自 TABLE.LIST) */
    private val _tablesByDatabase = mutableStateMapOf<String, List<String>>()

    val tablesByDatabase: Map<String, List<String>> get() = _tablesByDatabase

    /** 正在加载表的 database 集合(控制行内 spinner) */
    val loadingTables: SnapshotStateSet<String> = mutableStateSetOf()

    /** database → table 加载错误 */
    private val _tableLoadError = mutableStateMapOf<String, String>()
    val tableLoadError: Map<String, String> get() = _tableLoadError

    /** 打开的标签页 */
    var tabs: List<TablePreviewTab> by mutableStateOf(emptyList())
        private set

    var selectedTabIndex: Int by mutableStateOf(-1)
        private set

    /** 当前选中的连接 —— 由 [DatabaseBrowserScreen] 通过 [bindConnection] 注入 */
    private var currentConnection: ConnectionConfig? = null

    /**
     * 会话代次 —— 每次切换连接自增。
     *
     * 异步查询在挂起点之后先比对本值：若已切换连接，丢弃该响应（避免上一个连接的
     * in-flight 结果落到新连接的已清空状态里）。
     */
    private var generation: Int = 0

    // ------------------------------------------------------------------------
    // SQL 工作台
    // ------------------------------------------------------------------------

    /** 当前右栏展示的 pane —— 决定渲染表预览还是 SQL 工作台。每 sheet 独立。 */
    var activePane: BrowserPane by mutableStateOf(BrowserPane.TABLE)

    /** 切换 pane（SQL 工作台 ↔ 表预览）。供工具栏按钮调用。 */
    fun toggleSqlWorkbench() {
        activePane = if (activePane == BrowserPane.SQL) BrowserPane.TABLE else BrowserPane.SQL
    }

    /** 返回当前 SQL 工作台编辑器文本 —— 暴露给 UI 直接驱动编辑器输入。 */
    var sqlEditorText: String by mutableStateOf("")

    /** SQL 工作台状态：空闲 / 加载中 */
    var sqlRunning: Boolean by mutableStateOf(false)

    /** SQL 工作台最近一次执行结果（成功后回填；失败时为 null + [sqlError]）。 */
    var sqlResultColumns: List<TableColumn> by mutableStateOf(emptyList())
    var sqlResultRows: List<TableRow> by mutableStateOf(emptyList())
    var sqlRowCount: Int by mutableStateOf(0)

    /** 非 SELECT（DML/DDL）执行成功时记录受影响行数；为 null 表示 SELECT 或未执行。 */
    var sqlAffectedRows: Int? by mutableStateOf(null)

    /** SQL 工作台最近一次执行错误（连接失败 / SQL 语法 / 引擎抛异常）。 */
    var sqlError: String? by mutableStateOf(null)

    /** 当前连接下激活的 schema —— SQL 执行时作为 catalog 写入 proto config。
     *  默认取第一个展开的数据库，若无则为 `""`（默认 catalog）。 */
    fun currentSchema(): String =
        expandedDatabases.firstOrNull() ?: ""

    /** 最近一次执行的代次 —— 与 [generation] 类似思想，但 SQL 工作台需在
     * 切 pane / 切连接后丢弃 in-flight 结果。 */
    private var sqlGeneration: Int = 0

    /**
     * 执行编辑器中的 SQL —— 走 `Category.SQL` / `Action.EXECUTE` 流式通道：
     * SELECT 行帧 → 攒成 [sqlResultColumns] / [sqlResultRows] / [sqlRowCount]；
     * 终止帧携带 `execute.affected_rows` 时填入 [sqlAffectedRows]；失败 → [sqlError]。
     *
     * 与预览加载一样，in-flight 响应通过 [sqlGeneration] 失效化：开始新一次执行前自增，
     * collect 过程中比对，若不相等直接丢弃（避免上一次执行的迟到响应覆盖新一轮状态）。
     */
    fun executeSql(text: String) {
        val config = currentConnection ?: return
        val sql = text.trim()
        if (sql.isEmpty()) {
            sqlError = "SQL 为空"
            sqlResultColumns = emptyList()
            sqlResultRows = emptyList()
            sqlRowCount = 0
            sqlAffectedRows = null
            return
        }
        sqlEditorText = text
        sqlGeneration++
        val gen = sqlGeneration
        sqlRunning = true
        sqlError = null
        sqlAffectedRows = null
        sqlResultColumns = emptyList()
        sqlResultRows = emptyList()
        sqlRowCount = 0
        scope.launch {
            val schema = currentSchema()
            val result = runCatching {
                engine.handle(
                    request {
                        id = UUID.randomUUID().toString()
                        this.connection = engineConn(database = schema)
                        category = Category.SQL
                        action = Action.EXECUTE
                        sqlRequest = sqlRequest {
                            execute = sqlExecuteRequest {
                                this.sql = sql
                                this.schema = schema
                                multiStatement = false
                            }
                        }
                    },
                )
            }
            if (gen != sqlGeneration) return@launch  // 用户已重新执行 —— 丢弃过期响应
            val flow = result.getOrNull()
            if (flow == null) {
                sqlError = result.exceptionOrNull()?.message ?: "执行失败"
                sqlRunning = false
                return@launch
            }
            val frames = mutableListOf<com.kxxnzstdsw.grpc.SqlSelectRowFrame>()
            try {
                // `collect` + 取消标记：`collectWhile` 是 kotlinx.coroutines 内部 API 不可用。
                // 过期时置 cancelled 并停止累积 —— 流本身仍会被消费到结束（引擎侧已在推帧，
                // 主动 cancel 属于引擎能力范畴），但不再往内存里攒行。
                var cancelled = false
                flow.collect { resp ->
                    if (cancelled) return@collect
                    if (gen != sqlGeneration) {
                        cancelled = true
                        return@collect
                    }
                    if (!resp.success) {
                        sqlError = resp.error.ifBlank { "SQL 执行失败" }
                    } else if (resp.hasSqlRowFrame()) {
                        frames += resp.sqlRowFrame
                    } else if (resp.hasSql() && resp.sql.hasExecute()) {
                        sqlAffectedRows = resp.sql.execute.affectedRows
                    }
                }
            } catch (e: Exception) {
                if (gen == sqlGeneration) sqlError = e.message ?: e.javaClass.simpleName
                sqlRunning = false
                return@launch
            }
            if (gen != sqlGeneration) return@launch
            // 把 SqlSelectRowFrame 攒成 columns/rows —— 列名取首帧 keys，后续帧按相同顺序补值
            if (frames.isNotEmpty()) {
                val columnNames = frames.flatMap { it.row.valuesMap.keys }.distinct()
                sqlResultColumns = columnNames.map { TableColumn(key = it, header = it) }
                sqlResultRows = frames.mapIndexed { idx, frame ->
                    val idCell = frame.row.valuesMap.entries
                        .firstOrNull { (k, _) -> k.equals("id", ignoreCase = true) }
                    TableRow(
                        id = idCell?.let { cellValueAsId(it.value) } ?: idx,
                        cells = frame.row.valuesMap.mapValues { (_, v) -> cellValueToAny(v) },
                    )
                }
                sqlRowCount = frames.size
            } else if (sqlAffectedRows == null && sqlError == null) {
                sqlError = "无返回结果"
            }
            sqlRunning = false
        }
    }

    /**
     * 本屏在当前连接下**建立过连接池**的 database 维度（`""` = 默认 catalog）。
     *
     * 浏览另一个库会用到另一份 proto config（catalog 不同 → 池 key 不同），因此连接管理页的
     * 「断开」只能释放它自己那份池。本屏在**切换连接**或**会话不再连接**时用 [releasePools]
     * 逐个释放自己建立的池，避免遗留孤儿池。
     */
    private val activeDatabases = mutableSetOf<String>()

    // ------------------------------------------------------------------------
    // 公开操作
    // ------------------------------------------------------------------------

    /**
     * 注入/切换当前连接。
     *
     * 连接变化时**清空全部派生状态** —— 数据库列表 / 已展开节点 / 表缓存 / 标签页
     * 都属于上一个连接的会话，跨连接保留会展示错误(甚至不存在的)对象。同一连接重复调用是空操作。
     */
    fun bindConnection(config: ConnectionConfig?) {
        if (currentConnection?.id == config?.id) return
        releasePools()          // 先释放上一连接在本屏建立的池（池 key 含连接字段，换连接后不再可达）
        currentConnection = config
        generation++
        databases = emptyList()
        loadingDatabases = false   // 切连接时若有 in-flight 刷新，其响应会被代次丢弃 —— 必须在此复位，否则转圈停不下来
        errorMessage = null
        expandedDatabases.clear()
        _tablesByDatabase.clear()
        loadingTables.clear()
        _tableLoadError.clear()
        tabs = emptyList()
        selectedTabIndex = -1
        // SQL 工作台状态跨连接无意义 —— 清空并把 sqlGeneration 自增使 in-flight 响应失效
        sqlEditorText = ""
        sqlRunning = false
        sqlGeneration++
        sqlError = null
        sqlAffectedRows = null
        sqlResultColumns = emptyList()
        sqlResultRows = emptyList()
        sqlRowCount = 0
    }

    /**
     * 释放本屏为当前连接建立的全部连接池（含各 database 维度）。
     *
     * 在「切换连接」与「会话不再处于已连接」时调用；不改动其它配置或其它连接管理页建立的池。
     */
    fun releasePools() {
        val config = currentConnection ?: return
        if (activeDatabases.isEmpty()) return
        val targets = activeDatabases.toList()
        activeDatabases.clear()
        scope.launch {
            targets.forEach { database ->
                runCatching { engine.disconnect(engineConnFor(config, database)) }
            }
        }
    }

    fun refreshDatabases() {
        val requestGeneration = generation
        loadingDatabases = true
        errorMessage = null
        activeDatabases.add("")     // 该请求会用到默认 catalog 的池
        scope.launch {
            val result = runCatching {
                engine.invoke(engineConn(database = "")) {
                    category = Category.SCHEMA
                    action = Action.LIST
                    schemaRequest = schemaRequest {
                        list = schemaListRequest { level = "database" }
                    }
                }
            }
            if (requestGeneration != generation) return@launch  // 连接已切换 —— 丢弃过期响应
            // 先写数据、最后清 loading —— 顺序反了会让「轮询 loading」的调用方
            // （DatabaseBrowserFlowTest / 未来任何等待逻辑）读到 loading=false 却拿到旧数据。
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        errorMessage = resp.error.ifBlank { "加载数据库失败" }
                        databases = emptyList()
                    } else {
                        errorMessage = null
                        databases = resp.schema.list.itemsList
                    }
                },
                onFailure = { errorMessage = it.message ?: "Unknown error" },
            )
            loadingDatabases = false
        }
    }

    fun toggleDatabase(name: String) {
        if (expandedDatabases.contains(name)) {
            expandedDatabases.remove(name)
        } else {
            expandedDatabases.add(name)
            if (_tablesByDatabase[name] == null && !loadingTables.contains(name)) {
                loadTables(name)
            }
        }
    }

    private fun loadTables(database: String) {
        val requestGeneration = generation
        loadingTables.add(database)
        _tableLoadError.remove(database)
        activeDatabases.add(database)   // 该请求会用到 database 维度上的池
        scope.launch {
            val result = runCatching {
                engine.invoke(engineConn(database = database)) {
                    category = Category.TABLE
                    action = Action.LIST
                    tableRequest = tableRequest {
                        list = tableListRequest {}
                    }
                }
            }
            if (requestGeneration != generation) return@launch  // 连接已切换 —— 丢弃过期响应
            // 同 refreshDatabases：先写数据再清 loading
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        _tableLoadError[database] = resp.error.ifBlank { "加载表失败" }
                    } else {
                        _tablesByDatabase[database] =
                            resp.table.list.itemsList.map { it.name }
                    }
                },
                onFailure = {
                    _tableLoadError[database] = it.message ?: "Unknown error"
                },
            )
            loadingTables.remove(database)
        }
    }

    /**
     * 打开表的预览标签页 —— 同一表已存在则激活之, 不重复加载.
     */
    fun openTab(schema: String, tableName: String) {
        val tabKey = "$schema::$tableName"
        val existingIndex = tabs.indexOfFirst { it.key == tabKey }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TablePreviewTab(schema = schema, tableName = tableName)
        tabs = tabs + tab
        selectedTabIndex = tabs.size - 1
        loadTabPreview(tab)
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) {
            selectedTabIndex = index
        }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val newTabs = tabs.toMutableList().also { it.removeAt(index) }
        tabs = newTabs
        selectedTabIndex = when {
            newTabs.isEmpty() -> -1
            index >= newTabs.size -> newTabs.size - 1
            else -> index
        }
    }

    private fun loadTabPreview(tab: TablePreviewTab) {
        val requestGeneration = generation
        tab.loading = true
        tab.error = null
        activeDatabases.add(tab.schema)   // 预览用的池绑定在 tab.schema 这个 catalog 上
        scope.launch {
            val result = runCatching {
                engine.invoke(engineConn(database = tab.schema)) {
                    category = Category.DATA
                    action = Action.LIST
                    dataRequest = dataRequest {
                        list = dataListRequest {
                            tableName = tab.tableName
                            page = 1
                            // `pageSize = 0` 在 DATA.LIST 里是**流式**哨兵（逐行 frame，无 paged body），
                            // 预览固定走分页路径，故下限钳到 1。
                            pageSize = tab.pageSize.coerceAtLeast(1)
                        }
                    }
                }
            }
            if (requestGeneration != generation) return@launch  // 连接已切换 —— 丢弃过期响应
            // 同 refreshDatabases / loadTables：先写数据、最后清 loading
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        tab.error = resp.error.ifBlank { "读取失败" }
                        tab.columns = emptyList()
                        tab.rows = emptyList()
                    } else {
                        val paged = resp.data.list
                        tab.total = paged.total
                        tab.page = paged.page
                        tab.pageSize = paged.pageSize
                        // 表可能为空 —— 此时 rowsList 为空, 退化到无列预览
                        val columnNames = paged.rowsList
                            .flatMap { it.valuesMap.keys }
                            .distinct()
                        tab.columns = columnNames.map { TableColumn(key = it, header = it) }
                        tab.rows = paged.rowsList.mapIndexed { idx, row ->
                            // 主键承载: 大小写不敏感查找 "id" / "ID" / "Id" —— 不同方言
                            // (H2 大写 / MySQL 小写) 列名归一策略不同; 找不到则退化为行号.
                            val idCell = row.valuesMap.entries.firstOrNull { (k, _) -> k.equals("id", ignoreCase = true) }
                            TableRow(
                                id = idCell?.let { cellValueAsId(it.value) } ?: idx,
                                cells = row.valuesMap.mapValues { (_, v) -> cellValueToAny(v) },
                            )
                        }
                    }
                },
                onFailure = { tab.error = it.message ?: "Unknown error" },
            )
            tab.loading = false
        }
    }

    // ------------------------------------------------------------------------
    // 引擎连接配置
    // ------------------------------------------------------------------------

    /** 以当前连接构造引擎 proto config（见 [engineConnFor]）。 */
    private fun engineConn(database: String): com.kxxnzstdsw.grpc.ConnectionConfig =
        engineConnFor(currentConnection, database)

    /**
     * 构造引擎 proto [com.kxxnzstdsw.grpc.ConnectionConfig].
     *
     * - [driver] 必填：`SchemaHandler` / `TableHandler` 等 handler 直接按 `config.driver` 取方言
     *   （`PoolManager` 才会优先按 `jdbcUrl` scheme 反查）。取值必须用
     *   [com.kxxnzstdsw.sundays.connection.DialectType.engineDriverName] —— 引擎注册键是
     *   `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`，不是枚举常量名。
     * - [jdbcUrl] 是连接真相源；凭据一并带上，按默认 schema 借出的池即指向该 JDBC URL。
     * - [database] 是 catalog 维度：参与连接池 key，也是 `TableHandler.list` 传给方言的
     *   catalog 过滤值 —— 因此浏览另一个库会得到另一个池（这是有意的：一个池的连接绑定在一个 catalog 上）。
     *
     * 注意**不要**把 database 同步写入 `schema` 字段 —— 那会让 H2 执行 `SET SCHEMA <dbname>`
     * 并报 `Schema "X" not found`（H2 的 schema 是 `PUBLIC`，与 catalog 名无关）。留空即走
     * `CURRENT_SCHEMA` 默认行为。
     */
    private fun engineConnFor(
        config: ConnectionConfig?,
        database: String,
    ): com.kxxnzstdsw.grpc.ConnectionConfig = connectionConfig {
        if (config != null) {
            driver = config.dialect.engineDriverName
            jdbcUrl = config.jdbcUrl
            user = config.username
            password = config.password
            if (database.isNotBlank()) {
                this.database = database
            }
        } else if (database.isNotBlank()) {
            // 无选中连接 —— 引擎会因找不到方言 / 缺少 JDBC URL 返回 error, UI 显示即可.
            this.database = database
        }
    }

    private fun cellValueAsId(v: ProtoValue): Any = when (v.kindCase) {
        ProtoValue.KindCase.NUMBER_VALUE -> v.numberValue.toLong()
        ProtoValue.KindCase.STRING_VALUE -> v.stringValue
        ProtoValue.KindCase.BOOL_VALUE -> v.boolValue.toString()
        else -> v.toString()
    }

    private fun cellValueToAny(v: ProtoValue): Any? = when (v.kindCase) {
        ProtoValue.KindCase.NULL_VALUE -> null
        ProtoValue.KindCase.NUMBER_VALUE -> v.numberValue
        ProtoValue.KindCase.STRING_VALUE -> v.stringValue
        ProtoValue.KindCase.BOOL_VALUE -> v.boolValue
        else -> v.toString()
    }
}
