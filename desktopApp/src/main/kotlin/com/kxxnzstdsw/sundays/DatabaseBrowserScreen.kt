package com.kxxnzstdsw.sundays

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.protobuf.Value as ProtoValue
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataGenerateRequest
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.grpc.generateTable
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorState
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SettingsEntryButton
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemeModeToggleButton
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinProgressIndicator
import com.kxxnzstdsw.sundays.ui.WinTextButton
import com.kxxnzstdsw.sundays.ui.WinTextField
import com.kxxnzstdsw.sundays.ui.tabStripContainerColor
import com.kxxnzstdsw.sundays.ui.WinIconButton
import com.kxxnzstdsw.sundays.ui.winShape
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
 *   工作台内是**多个 SQL sheet**（标签条：＋ 新建 / ✎ 重命名 / × 关闭 / 滚轮横向滚动），
 *   每个 sheet 一份独立 SQL 与独立结果。
 *   点 **「执行 SQL」** 走 `SQL.EXECUTE` 引擎流式：SELECT 行帧 → 结果表；
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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onCycleTheme: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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
                themeMode = themeMode,
                onCycleTheme = onCycleTheme,
                onOpenSettings = onOpenSettings,
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
            themeMode = themeMode,
            onCycleTheme = onCycleTheme,
            onOpenSettings = onOpenSettings,
        )
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
 * - [GENERATE] = 造数工作台（Lua 脚本编辑 + 造数结果面板）；由工具栏切换
 *
 * 三个 pane 是**同一份 sheet 状态**的两种以上渲染 —— 切换只改变渲染目标，不清空任何状态。
 */
enum class BrowserPane { TABLE, SQL, GENERATE }

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
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「＋添加连接」按钮：固定在最左，独立于 sheet 标签条；
        // 点击后由调用方弹出 AddConnectionDialog。
        WinIconButton(
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
            containerColor = tabStripContainerColor(),
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
        // 主题切换 + 设置入口钉在**最外层**右上角：与左侧「＋」同一行、同高 ——
        // 不占用内容区高度，也不随工作台切换 / sheet 内容变化而移动。
        ThemeModeToggleButton(mode = themeMode, onCycle = onCycleTheme)
        SettingsEntryButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 4.dp))
    }
}

@Composable
private fun EmptySheetsHint(
    onAddSheet: () -> Unit,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 空态下没有标签条也没有工具栏，主题切换 / 设置入口若不单独放一个就会彻底消失 ——
        // 而空态恰恰是用户第一次打开应用最可能停留的地方。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            ThemeModeToggleButton(mode = themeMode, onCycle = onCycleTheme)
            SettingsEntryButton(onClick = onOpenSettings)
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
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
            WinButton(onClick = onAddSheet, shape = SundaysPalette.buttonShape) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加连接")
            }
        }
    }
}

/**
 * BrowserToolBar —— sheet 标签条之上的工具栏，操作当前激活 sheet 的右栏内容。
 *
 * 两个工作台入口：**「SQL 工作台」** 与 **「造数工作台」**。当前正处在某个工作台时，
 * 该按钮变为「返回表预览」（点它回到表预览）；从另一个工作台切过来时直接进入目标工作台。
 * 状态由 [DatabaseBrowserState.activePane] 持有，每 sheet 独立 —— 切换不清空任何工作台状态。
 * 未连接时禁用（两个工作台的执行都必须依赖已建立的连接池）。
 */
@Composable
private fun BrowserToolBar(
    activePane: BrowserPane,
    connected: Boolean,
    onSelectPane: (BrowserPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneToggleButton(
                pane = BrowserPane.SQL,
                activePane = activePane,
                label = "SQL 工作台",
                icon = Icons.Filled.PlayArrow,
                enabled = connected,
                onSelect = onSelectPane,
            )
            PaneToggleButton(
                pane = BrowserPane.GENERATE,
                activePane = activePane,
                label = "造数工作台",
                icon = Icons.Filled.Bolt,
                enabled = connected,
                onSelect = onSelectPane,
            )
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
 * 单个工作台切换按钮 —— 处于该 pane 时显示「返回表预览」（实心），否则显示工作台名（描边）。
 */
@Composable
private fun PaneToggleButton(
    pane: BrowserPane,
    activePane: BrowserPane,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onSelect: (BrowserPane) -> Unit,
) {
    val isActive = activePane == pane
    WinButton(
        onClick = { onSelect(if (isActive) BrowserPane.TABLE else pane) },
        enabled = enabled,
        shape = SundaysPalette.buttonShape,
        // 复古两套下由 WinButton 把斜面翻转为凹陷；现代主题仍用 primary 填充表达激活态
        selected = isActive,
        colors = if (isActive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Icon(
            imageVector = if (isActive) Icons.Filled.TableChart else icon,
            contentDescription = null,
        )
        Spacer(Modifier.width(6.dp))
        Text(if (isActive) "返回表预览" else label)
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
            onSelectPane = sheet.browser::selectPane,
        )
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    dialect = sheet.browser.sqlDialectProfile(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                BrowserPane.GENERATE -> GenerateWorkbenchPane(
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
        shape = winShape(6.dp),
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
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
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
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
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
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
 * SQL 工作台 —— 顶部信息条 + SQL sheet 标签条 + 编辑器（60%） + 结果面板（40%）。
 *
 * 与 [GenerateWorkbenchPane] 同构：**多 sheet**，每个 sheet 的 [CodeEditorState] 与执行结果都由
 * 状态机持有（[DatabaseBrowserState.sqlSheets]），因此切到表预览 / 切到别的 sheet 再切回来时，
 * 文本 / 光标 / 滚动位置 / 结果都保持不变。「执行 SQL」只作用于 [DatabaseBrowserState.currentSqlSheet]。
 *
 * 编辑器用 [CodeEditorWithToolbar]（`:shared` 的 `commonMain/.../editor/ui/CodeEditor.kt`）：
 * `languageId` = **当前连接方言的高亮档位**（[SqlDialectProfile.languageId]，见
 * [DatabaseBrowserState.sqlDialectProfile]）—— 换库即换关键字 / 类型 / 内置函数词表；
 * 隐藏语言切换器（本工作台只处理 SQL），内置「格式化」按钮走注册表里同档位的 SQL formatter，
 * 「执行 SQL」经 `actions` 插槽注入。SQL 语言与 formatter 由 app 启动时的
 * `registerBuiltinEditors()` 一次性注册全部档位（幂等）。
 *
 * 结果面板渲染**当前 sheet** 的四种态：
 * - `running` → 行内 spinner
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
    dialect: SqlDialectProfile,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 顶部信息条：当前执行的 catalog —— 说明 SQL 会落到哪个库
        Surface(
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
                // 当前生效的高亮档位（由连接方言决定）—— 让「关键字随库变」在界面上可见
                Text(
                    text = dialect.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (schema.isNotBlank()) "schema: $schema" else "默认 catalog",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        WorkbenchTabStrip(
            leadingLabel = "SQL",
            titles = state.sqlSheets.map { it.title },
            selectedIndex = state.selectedSqlIndex,
            addDescription = "新建 SQL sheet",
            renameDescription = "重命名当前标签",
            removeDescription = "关闭 SQL sheet",
            onSelect = state::selectSqlSheet,
            onAdd = state::addSqlSheet,
            onRemove = state::removeSqlSheet,
            onRename = state::renameSqlSheet,
        )
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val sheet = state.currentSqlSheet()
        // 上编辑器 + 下结果（fillMaxHeight 60% / 40% 通过 weight 分配）
        Column(modifier = Modifier.fillMaxSize()) {
            if (sheet == null) {
                EmptyHint(
                    title = "没有可编辑的 SQL sheet",
                    description = "点上方「＋」新建一个 SQL sheet。",
                    // 用 weight 而非 fillMaxSize —— 与下方 weighted 子项共存时不会互相挤掉
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                CodeEditorWithToolbar(
                    text = sheet.editor.text,
                    editorState = sheet.editor,
                    onTextChange = { sheet.editor.setText(it) },
                    // 方言档位即语言 id：高亮与「格式化」都走同一档位（见 SqlDialectProfile）
                    languageId = dialect.languageId,
                    // 本工作台只处理 SQL —— 不暴露语言切换器
                    showLanguageSwitcher = false,
                    actions = {
                        WinButton(
                            onClick = { state.executeSql() },
                            enabled = connected && !sheet.running && sheet.editor.text.isNotBlank(),
                            shape = SundaysPalette.buttonShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (sheet.running) "执行中…" else "执行 SQL")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.6f),
                )
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SqlResultArea(
                sheet = sheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
            )
        }
    }
}

/**
 * 工作台标签条 —— SQL sheet 与造数脚本共用（两者行为完全一致，只有文案不同）。
 *
 * 三个能力：
 * 1. **横向滚动**：`SecondaryScrollableTabRow` 自带拖拽滚动 + 选中项自动滚入可视区；
 *    另外挂 [verticalWheelScrollsHorizontally] 让普通鼠标的**纵向滚轮**也能滚动标签条
 *    （Compose 的 `horizontalScroll` 只吃横向滚轮分量，见该函数的 KDoc）。
 * 2. **重命名**：右侧「✎」为**当前选中标签**弹出 [TabRenameDialog]（确定提交 / 取消放弃）；
 *    空名保持原名（由 [DatabaseBrowserState.renameSqlSheet] / [renameGenerateScript] 兜底）。
 * 3. **增删**：右侧「＋」调 [onAdd]；每个标签的「×」调 [onRemove]（只剩一个标签时不渲染「×」，
 *    与预览标签页/造数脚本的既有策略一致）。
 *
 * > **为什么重命名是「按钮 + 弹窗」而不是双击内联编辑**：
 * > 1. 双击不可行 —— `Tab` 内部把 `modifier.selectable(...)` 挂在同一节点上，`Clickable`
 * >    会在 Main pass 消费 down（`handleDownEvent` → `down.consume()`），外层再挂
 * >    `detectTapGestures(onDoubleTap)` 需要**未被消费的 down**，因此永远收不到手势；挂到内容里
 * >    又会抢走 `selectable` 需要的 down（双击成了就单击失灵）。想保留双击语义只能自绘标签
 * >    （连带失去 Material 指示条）。
 * > 2. 内联编辑会**卡死画面** —— `Tab` 的文本槽位于 `SecondaryScrollableTabRow` 的
 * >    SubcomposeLayout 内，把一个自己抢焦点的 `BasicTextField` 塞进去，Compose 场景会永远
 * >    有下一帧要渲染（`SkikoComposeUiTest.waitForIdle()` 实测永不返回，真机表现为界面卡死）。
 * >    弹窗把编辑面与标签条的测量 / 焦点链路彻底解耦。
 *
 * @param leadingLabel 标签条前的固定小标题（「SQL」/「脚本」）
 * @param titles 各标签的显示名（顺序即标签条顺序）
 * @param selectedIndex 当前选中下标（越界时夹到合法范围）
 * @param addDescription 「＋」的无障碍描述，同时是 UI 测试的锚点
 * @param renameDescription 「✎」的无障碍描述
 * @param removeDescription 「×」的无障碍描述
 * @param onRename 提交重命名（index, 新名字）—— 仅在名字真正变化时回调
 */
@Composable
private fun WorkbenchTabStrip(
    leadingLabel: String,
    titles: List<String>,
    selectedIndex: Int,
    addDescription: String,
    renameDescription: String,
    removeDescription: String,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 正在重命名的标签下标（null = 无）—— 纯视图态，不进状态机
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.width(4.dp))
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0)),
            scrollState = scrollState,
            edgePadding = 0.dp,
            containerColor = tabStripContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            modifier = Modifier
                .weight(1f)
                .verticalWheelScrollsHorizontally(scrollState),
        ) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                maxLines = 1,
                            )
                            if (titles.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = removeDescription,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemove(index) },
                                )
                            }
                        }
                    },
                )
            }
        }
        WinIconButton(
            onClick = { renameTarget = selectedIndex.takeIf { it in titles.indices } },
            enabled = titles.isNotEmpty() && selectedIndex in titles.indices,
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = renameDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        WinIconButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = addDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // 重命名弹窗 —— 与标签条解耦：不在 Tab（SubcomposeLayout）内部放可编辑控件
    val target = renameTarget
    val targetTitle = target?.let { titles.getOrNull(it) }
    if (target != null && targetTitle != null) {
        TabRenameDialog(
            initialTitle = targetTitle,
            onConfirm = { newTitle ->
                renameTarget = null
                if (newTitle != targetTitle) onRename(target, newTitle)
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/**
 * 「重命名标签」弹窗 —— 标签条「✎」的落地 UI。
 *
 * 用底层 [Dialog]（与 [com.kxxnzstdsw.sundays.connection.AddConnectionDialog] 同款）：
 * Material3 `AlertDialog` 会把内容塞进 `maxWidth = 560.dp` 的容器，且不需要它的槽位。
 * 输入框占位 280.dp + 单行；回车 = 确定（[onPreviewKeyEvent]），Esc = 关闭（[Dialog] 默认行为）。
 *
 * **为什么是弹窗而不是标签内联编辑**：`Tab` 的文本槽位于 `SecondaryScrollableTabRow` 的
 * SubcomposeLayout 内，把可编辑控件（尤其是自己抢焦点的 `BasicTextField`）塞进去会与该布局
 * 的测量 / 焦点链路互相触发。弹窗把编辑面与标签条彻底解耦。
 */
@Composable
private fun TabRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialTitle) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            // 弹窗是真正浮在内容之上的一层 —— 用 shadowElevation（真阴影），而不是
            // tonalElevation（色相叠色）。SundaysPalette 已把 surfaceTint 设为透明，
            // tonalElevation 不再产生任何视觉变化，这里改用阴影表达「浮起」。
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "重命名标签",
                    style = MaterialTheme.typography.titleMedium,
                )
                WinTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier
                        .width(280.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                onConfirm(value)
                                true
                            } else {
                                false
                            }
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    WinTextButton(onClick = onDismiss) { Text("取消") }
                    WinTextButton(onClick = { onConfirm(value) }) { Text("确定") }
                }
            }
        }
    }
}

/** 滚轮一格（`scrollDelta` 一个单位）对应横向滚动的像素数 —— 约等于一个标签宽（`minTabWidth` 90.dp）。 */
private const val TAB_WHEEL_SCROLL_STEP_PX = 96f

/**
 * Modifier 扩展 —— 把鼠标**纵向**滚轮转发成自己的横向滚动。
 *
 * Compose 的 `horizontalScroll` 走 `Offset.toSingleAxisDeltaFromAngle()`：纵向滚轮分量在横向
 * 滚动容器上被判定为「另一个轴」而返回 0、**不消费**（见 foundation `Scrollable.kt` /
 * `MouseWheelScrollingLogic.kt`）。因此只有横向滚轮（触控板/倾斜滚轮）能滚动标签条，
 * 普通鼠标滚轮完全滚不动 —— 这里补上这一环。
 *
 * 约定：
 * - 只处理**未被消费**的 Scroll 事件（Main pass），因此触控板的横向分量仍优先交给
 *   `horizontalScroll` 自己处理，不会双重滚动；
 * - 处理后 `consume()`，避免同一个滚轮事件再去滚动外层容器；
 * - 纵向增量为 0（横向滚轮）时不拦截。
 */
private fun Modifier.verticalWheelScrollsHorizontally(state: ScrollState): Modifier =
    this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val dy = change.scrollDelta.y
                if (dy == 0f) continue
                change.consume()
                state.dispatchRawDelta(dy * TAB_WHEEL_SCROLL_STEP_PX)
            }
        }
    }

/**
 * SQL 工作台结果面板 —— 渲染**当前 sheet** 的 [DatabaseBrowserState.SqlSheet] 四类结果字段之一为有效值者。
 * 无 sheet（用户删光了）时展示空态。
 */
@Composable
private fun SqlResultArea(
    sheet: DatabaseBrowserState.SqlSheet?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        when {
            sheet == null -> EmptyHint(
                title = "尚未执行 SQL",
                description = "在上方编辑器输入 SQL，点「执行 SQL」即在此查看结果。",
                modifier = Modifier.fillMaxSize(),
            )
            sheet.running -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("执行中…", style = MaterialTheme.typography.labelMedium)
                }
            }
            sheet.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = "错误: ${sheet.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sheet.affectedRows != null -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = "已影响 ${sheet.affectedRows} 行（非 SELECT 语句）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            sheet.rows.isNotEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "查询结果 · ${sheet.rowCount} 行",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DataTable(
                    columns = sheet.columns,
                    rows = sheet.rows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = sheet.resultPageSize,
                    onPageSizeChange = { sheet.resultPageSize = it },
                    currentPage = sheet.resultPage,
                    onPageChange = { sheet.resultPage = it },
                    selectedRowId = sheet.selectedRowId,
                    onSelectedRowChange = { sheet.selectedRowId = it?.id },
                    fillParentHeight = false,
                )
            }
            else -> EmptyHint(
                title = "尚未执行 SQL",
                description = "在上方编辑器输入 SQL，点「执行 SQL」即在此查看结果。",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ============================================================================
// 造数工作台 —— Lua 脚本编辑 + 造数结果
// ============================================================================

/** 造数工作台的 Lua 版本候选 —— 对应引擎 `DataGenerateRequest.lua_version`。 */
private val LUA_VERSIONS = listOf("luajit", "5.1", "5.2", "5.3", "5.4", "5.5")

/** 新建造数脚本的初始内容 —— 顺带把引擎提供的 random_* 辅助函数列出来当速查表。 */
internal const val GENERATE_SCRIPT_TEMPLATE = """-- 造数脚本：insert(表名, { 列 = 值, … }) 逐条写库
-- 辅助：random_int / random_float / random_string / random_name / random_email / random_phone
--      random_date / random_datetime / random_time / random_uuid / random_enum / lastId()
for i = 1, 100 do
  insert("your_table", {
    name  = random_name(),
    email = random_email(),
    age   = random_int(18, 60),
  })
end"""

/**
 * 造数工作台 —— 上半脚本标签条 + Lua 版本 + 编辑器，下半造数结果。
 *
 * 与 [SqlWorkbenchPane] 同构：编辑器的 [CodeEditorState] 由状态机持有（[DatabaseBrowserState.generateScripts]），
 * 因此切到表预览再切回来时，脚本文本 / 光标 / 滚动位置都保持不变。
 */
@Composable
private fun GenerateWorkbenchPane(
    state: DatabaseBrowserState,
    connected: Boolean,
    schema: String,
    modifier: Modifier = Modifier,
) {
    val script = state.currentGenerateScript()
    Column(modifier = modifier) {
        Surface(
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
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "造数工作台",
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
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        WorkbenchTabStrip(
            leadingLabel = "脚本",
            titles = state.generateScripts.map { it.title },
            selectedIndex = state.selectedGenerateIndex,
            addDescription = "新建造数脚本",
            renameDescription = "重命名当前标签",
            removeDescription = "关闭脚本",
            onSelect = state::selectGenerateScript,
            onAdd = state::addGenerateScript,
            onRemove = state::removeGenerateScript,
            onRename = state::renameGenerateScript,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Lua 版本",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LUA_VERSIONS.forEach { version ->
                val selected = state.generateLuaVersion == version
                AssistChip(
                    onClick = { state.generateLuaVersion = version },
                    enabled = connected,
                    label = { Text(version) },
                    colors = if (selected) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.fillMaxSize()) {
            if (script == null) {
                EmptyHint(
                    title = "没有可编辑的脚本",
                    description = "点上方「＋」新建一个 Lua 造数脚本。",
                    // 用 weight 而非 fillMaxSize —— 与下方两个 weighted 子项共存时不会互相挤掉
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                CodeEditorWithToolbar(
                    text = script.editor.text,
                    onTextChange = { script.editor.setText(it) },
                    editorState = script.editor,
                    languageId = "lua",
                    // 本工作台只处理 Lua —— 不暴露语言切换器
                    showLanguageSwitcher = false,
                    actions = {
                        WinButton(
                            onClick = { state.executeGenerate() },
                            enabled = connected && !state.generateRunning,
                            shape = SundaysPalette.buttonShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.generateRunning) "造数中…" else "执行造数")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.6f),
                )
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GenerateResultArea(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
            )
        }
    }
}

/**
 * 造数结果面板 —— 三态：进行中（实时进度）/ 错误 / 结果表（每个脚本一行）。
 *
 * 进度数据来自引擎 `DATA.GENERATE` 的 `gen_progress_frame` 流（每条 INSERT 一帧），
 * 终止帧的 `generate_terminal.tables_processed` 是已处理脚本数。
 */
@Composable
private fun GenerateResultArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        when {
            state.generateRunning -> Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text(
                    text = "造数中… 已插入 ${state.generateTotalInserted} 行",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            state.generateError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = "错误: ${state.generateError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.hasGenerateResult -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "造数完成 · 共 ${state.generateTotalInserted} 行 · 处理 ${state.generateTablesProcessed} 个脚本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DataTable(
                    columns = GENERATE_RESULT_COLUMNS,
                    rows = state.generateResultRows(),
                    modifier = Modifier.fillMaxSize(),
                    pageSize = state.generateResultPageSize,
                    onPageSizeChange = { state.generateResultPageSize = it },
                    currentPage = state.generateResultPage,
                    onPageChange = { state.generateResultPage = it },
                    selectedRowId = state.generateResultSelectedRowId,
                    onSelectedRowChange = { state.generateResultSelectedRowId = it?.id },
                    fillParentHeight = false,
                )
            }
            else -> EmptyHint(
                title = "尚未执行造数",
                description = "在上方编写 Lua 脚本（insert(表名, {列 = 值}) 逐条写库），点「执行造数」即在此查看逐脚本统计。",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 造数结果表列定义（脚本 / 目标表 / 插入行数）。 */
private val GENERATE_RESULT_COLUMNS = listOf(
    TableColumn(key = "script", header = "脚本"),
    TableColumn(key = "table", header = "目标表"),
    TableColumn(key = "inserted", header = "插入行数"),
)

/**
 * 连接方言 → SQL 高亮档位。
 *
 * `when` **穷举且不写 `else`**：将来新增 [DialectType] 时这里编译期就报错，
 * 不会让新方言静默退化到标准档位。
 *
 * 映射放在 desktopApp 而不是 `:shared` 的编辑器模块 —— 编辑器只认 [SqlDialectProfile]，
 * 不依赖连接配置（依赖方向见 desktopApp/ARCHITECTURE.md）。
 */
private fun DialectType.toSqlDialectProfile(): SqlDialectProfile = when (this) {
    DialectType.MYSQL -> SqlDialectProfile.MYSQL
    DialectType.POSTGRESQL -> SqlDialectProfile.POSTGRESQL
    DialectType.H2 -> SqlDialectProfile.H2
    DialectType.DUCKDB -> SqlDialectProfile.DUCKDB
    DialectType.SQLITE -> SqlDialectProfile.SQLITE
    DialectType.UNKNOWN -> SqlDialectProfile.STANDARD
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

    /**
     * 当前选中的连接 —— 由 [DatabaseBrowserScreen] 通过 [bindConnection] 注入。
     *
     * 是 Compose **快照状态**而不只是普通字段：SQL 工作台的高亮档位由它派生，连接（方言）变化
     * 必须触发重组，否则切连接后关键字表还停在上一个方言上。
     */
    var currentConnection: ConnectionConfig? by mutableStateOf(null)
        private set

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

    /** 当前右栏展示的 pane —— 决定渲染表预览 / SQL 工作台 / 造数工作台。每 sheet 独立。 */
    var activePane: BrowserPane by mutableStateOf(BrowserPane.TABLE)

    /** 切换 pane。工具栏按钮调用 —— 只改渲染目标，不动任何工作台状态。 */
    fun selectPane(pane: BrowserPane) {
        activePane = pane
    }


    /**
     * 单个 SQL sheet —— 一份 SQL 文本 + 该 sheet 自己的执行结果。
     *
     * 与 [GenerateScript] 同构：
     * - [editor] 由状态机持有（文本 + 光标 / 选区 + 滚动），切 pane / 切 sheet 后保持不变
     * - 执行结果（[running] / [columns] / [rows] / [affectedRows] / [error] / 结果区视图）
     *   **归属单个 sheet** —— 切到另一个 sheet 不会串显上一次的结果
     * - [generation] 是本 sheet 的 in-flight 失效代次：重新执行 / 删除时自增，
     *   迟到的行帧据此丢弃（不能让上一轮响应覆盖新一轮状态）
     */
    class SqlSheet(title: String) {
        /** 标签条上展示的名字（默认 `SQL N`）—— 用户可重命名，见 [renameSqlSheet]。 */
        var title: String by mutableStateOf(title)

        val editor: CodeEditorState = CodeEditorState()

        /** 该 sheet 是否正在执行（控制「执行 SQL」按钮与结果区 spinner）。 */
        var running: Boolean by mutableStateOf(false)

        /** 最近一次执行结果（成功后回填；失败时为 null + [error]）。 */
        var columns: List<TableColumn> by mutableStateOf(emptyList())
        var rows: List<TableRow> by mutableStateOf(emptyList())
        var rowCount: Int by mutableStateOf(0)

        /** 非 SELECT（DML/DDL）执行成功时记录受影响行数；为 null 表示 SELECT 或未执行。 */
        var affectedRows: Int? by mutableStateOf(null)

        /** 最近一次执行错误（连接失败 / SQL 语法 / 引擎抛异常）。 */
        var error: String? by mutableStateOf(null)

        /** 结果区视图状态（分页 / 选中行）—— 每次重新执行归位。 */
        var resultPage: Int by mutableStateOf(1)
        var resultPageSize: PageSize by mutableStateOf(PageSize.S100)
        var selectedRowId: Any? by mutableStateOf(null)

        /** in-flight 失效代次（见类 KDoc）。 */
        internal var generation: Int = 0
    }

    /** SQL sheet 列表（顺序仅决定标签条顺序 —— 各 sheet 彼此独立执行）。 */
    val sqlSheets: SnapshotStateList<SqlSheet> = mutableStateListOf(SqlSheet("SQL 1"))

    /** 当前选中的 SQL sheet 下标（越界表示无 sheet）。 */
    var selectedSqlIndex: Int by mutableStateOf(0)

    /** 当前选中的 SQL sheet（越界时为 null —— 例如用户删掉了最后一个 sheet）。 */
    fun currentSqlSheet(): SqlSheet? = sqlSheets.getOrNull(selectedSqlIndex)

    /** 新增一个 SQL sheet 并选中它。 */
    fun addSqlSheet() {
        sqlSheets.add(SqlSheet("SQL ${sqlSheets.size + 1}"))
        selectedSqlIndex = sqlSheets.lastIndex
    }

    /** 删除 SQL sheet；删掉后选中项跟随回退（与 [removeGenerateScript] 同策略）。 */
    fun removeSqlSheet(index: Int) {
        if (index !in sqlSheets.indices) return
        // 该 sheet 可能正在执行 —— 自增代次让其 in-flight 行帧作废
        sqlSheets[index].generation++
        sqlSheets.removeAt(index)
        selectedSqlIndex = when {
            sqlSheets.isEmpty() -> -1
            index >= sqlSheets.size -> sqlSheets.lastIndex
            else -> index
        }
    }

    fun selectSqlSheet(index: Int) {
        if (index in sqlSheets.indices) selectedSqlIndex = index
    }

    /** 重命名 SQL sheet —— 去首尾空白；空名（或纯空白）视为无效，保持原名。 */
    fun renameSqlSheet(index: Int, title: String) {
        val sheet = sqlSheets.getOrNull(index) ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        sheet.title = trimmed
    }

    /** 当前连接下激活的 schema —— SQL 执行时作为 catalog 写入 proto config。
     *  默认取第一个展开的数据库，若无则为 `""`（默认 catalog）。 */
    fun currentSchema(): String =
        expandedDatabases.firstOrNull() ?: ""

    /**
     * SQL 工作台的高亮档位 —— 按**当前连接的方言**选关键字 / 类型 / 内置函数词表。
     *
     * 未连接 / 未知方言 → 标准 SQL 档位（`"sql"`）。返回值同时决定编辑器的 `languageId`
     * （高亮 + 格式化）与标题条上显示的方言名。
     */
    fun sqlDialectProfile(): SqlDialectProfile =
        currentConnection?.dialect?.toSqlDialectProfile() ?: SqlDialectProfile.STANDARD

    /**
     * 执行**当前 SQL sheet** 编辑器中的 SQL —— 走 `Category.SQL` / `Action.EXECUTE` 流式通道：
     * SELECT 行帧 → 攒成该 sheet 的 [SqlSheet.columns] / [SqlSheet.rows] / [SqlSheet.rowCount]；
     * 终止帧携带 `execute.affected_rows` 时填入 [SqlSheet.affectedRows]；失败 → [SqlSheet.error]。
     *
     * 与预览加载一样，in-flight 响应通过该 sheet 自己的 [SqlSheet.generation] 失效化：
     * 开始新一次执行前自增，collect 过程中比对，若不相等直接丢弃
     * （避免上一次执行的迟到响应覆盖新一轮状态）。
     */
    fun executeSql() {
        val sheet = currentSqlSheet() ?: return
        currentConnection ?: return
        val sql = sheet.editor.text.trim()
        if (sql.isEmpty()) {
            sheet.error = "SQL 为空"
            sheet.columns = emptyList()
            sheet.rows = emptyList()
            sheet.rowCount = 0
            sheet.affectedRows = null
            return
        }
        sheet.generation++
        val gen = sheet.generation
        sheet.running = true
        sheet.error = null
        sheet.affectedRows = null
        sheet.columns = emptyList()
        sheet.rows = emptyList()
        sheet.rowCount = 0
        // 新一轮结果 → 视图归位（页码 / 选中行都是上一批数据的，指过去没有意义）
        sheet.resultPage = 1
        sheet.selectedRowId = null
        scope.launch {
            val schema = currentSchema()
            // 本请求在 catalog = schema 维度上建池（schema 字段本身留空，见下方注释），
            // 因此登记进 activeDatabases —— 否则 close-sheet 的 releasePools 遍历不到它，
            // 池会一直挂着到 JVM 退出。
            activeDatabases.add(schema)
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
                                // **不要**把 catalog（数据库）名写进 schema —— 引擎会把
                                // req.schema 传给 PoolManager.getConnection → setSearchPath，
                                // 对 H2 是 `SET SCHEMA "<库名>"` → `Schema "X" not found`，
                                // 对 PG 是把库名当 schema 设进 search_path。见 engineConnFor 的 KDoc。
                                this.schema = ""
                                multiStatement = false
                            }
                        }
                    },
                )
            }
            if (gen != sheet.generation) return@launch  // 用户已重新执行 —— 丢弃过期响应
            val flow = result.getOrNull()
            if (flow == null) {
                sheet.error = result.exceptionOrNull()?.message ?: "执行失败"
                sheet.running = false
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
                    if (gen != sheet.generation) {
                        cancelled = true
                        return@collect
                    }
                    if (!resp.success) {
                        sheet.error = resp.error.ifBlank { "SQL 执行失败" }
                    } else if (resp.hasSqlRowFrame()) {
                        frames += resp.sqlRowFrame
                    } else if (resp.hasSql() && resp.sql.hasExecute()) {
                        sheet.affectedRows = resp.sql.execute.affectedRows
                    }
                }
            } catch (e: Exception) {
                if (gen == sheet.generation) sheet.error = e.message ?: e.javaClass.simpleName
                sheet.running = false
                return@launch
            }
            if (gen != sheet.generation) return@launch
            // 把 SqlSelectRowFrame 攒成 columns/rows —— 列名取首帧 keys，后续帧按相同顺序补值
            if (frames.isNotEmpty()) {
                val columnNames = frames.flatMap { it.row.valuesMap.keys }.distinct()
                sheet.columns = columnNames.map { TableColumn(key = it, header = it) }
                sheet.rows = frames.mapIndexed { idx, frame ->
                    val idCell = frame.row.valuesMap.entries
                        .firstOrNull { (k, _) -> k.equals("id", ignoreCase = true) }
                    TableRow(
                        id = idCell?.let { cellValueAsId(it.value) } ?: idx,
                        cells = frame.row.valuesMap.mapValues { (_, v) -> cellValueToAny(v) },
                    )
                }
                sheet.rowCount = frames.size
            } else if (sheet.affectedRows == null && sheet.error == null) {
                sheet.error = "无返回结果"
            }
            sheet.running = false
        }
    }

    // ------------------------------------------------------------------------
    // 造数工作台
    // ------------------------------------------------------------------------

    /**
     * 造数脚本 —— 一份 Lua 脚本 + 该脚本的造数统计。
     *
 * - [editor] 由状态机持有（文本 + 光标 / 选区 + 滚动），切到表预览再切回来保持不变
     * - [inserted] / [lastTable] 由引擎 `gen_progress_frame` 流实时回填
     */
    class GenerateScript(title: String) {
        /** 标签条上展示的名字（默认 `脚本 N`）—— 用户可重命名，见 [renameGenerateScript]。 */
        var title: String by mutableStateOf(title)

        val editor: CodeEditorState = CodeEditorState(GENERATE_SCRIPT_TEMPLATE)
        var inserted: Long by mutableStateOf(0)
        var lastTable: String by mutableStateOf("")
    }

    /** 造数脚本列表（顺序 = 引擎 `tables` 执行顺序 = 多表外键依赖顺序）。 */
    val generateScripts: SnapshotStateList<GenerateScript> =
        mutableStateListOf(GenerateScript("脚本 1"))

    var selectedGenerateIndex: Int by mutableStateOf(0)

    /** 引擎 Lua 运行时（`luajit` / `5.1` ~ `5.5`），随请求 `lua_version` 下发。 */
    var generateLuaVersion: String by mutableStateOf("luajit")

    var generateRunning: Boolean by mutableStateOf(false)

    /** 终止帧回填：已处理的脚本数。 */
    var generateTablesProcessed: Int by mutableStateOf(0)

    var generateError: String? by mutableStateOf(null)

    /** 结果区视图状态（与 SQL 工作台同构：分页 / 选中行归状态机，切 pane 保持）。 */
    var generateResultPage: Int by mutableStateOf(1)
    var generateResultPageSize: PageSize by mutableStateOf(PageSize.DEFAULT)
    var generateResultSelectedRowId: Any? by mutableStateOf(null)

    /** 造数代次 —— 同 [SqlSheet.generation]：切连接 / 重新执行后使 in-flight 进度帧失效。 */
    private var generateGeneration: Int = 0

    /** 当前选中的脚本（越界时为 null —— 例如用户删掉了最后一个脚本）。 */
    fun currentGenerateScript(): GenerateScript? = generateScripts.getOrNull(selectedGenerateIndex)

    /** 新增一个脚本并选中它 —— 初始内容为 [GENERATE_SCRIPT_TEMPLATE] 模板。 */
    fun addGenerateScript() {
        generateScripts.add(GenerateScript("脚本 ${generateScripts.size + 1}"))
        selectedGenerateIndex = generateScripts.lastIndex
    }

    /** 删除脚本；删掉后选中项跟随回退（与预览标签页 `closeTab` 同策略）。 */
    fun removeGenerateScript(index: Int) {
        if (index !in generateScripts.indices) return
        generateScripts.removeAt(index)
        selectedGenerateIndex = when {
            generateScripts.isEmpty() -> -1
            index >= generateScripts.size -> generateScripts.lastIndex
            else -> index
        }
    }

    fun selectGenerateScript(index: Int) {
        if (index in generateScripts.indices) selectedGenerateIndex = index
    }

    /** 重命名造数脚本 —— 规则同 [renameSqlSheet]：去首尾空白，空名保持原名。 */
    fun renameGenerateScript(index: Int, title: String) {
        val script = generateScripts.getOrNull(index) ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        script.title = trimmed
    }

    /** 最近一次造数插入的总行数（各脚本累计）。 */
    val generateTotalInserted: Long get() = generateScripts.sumOf { it.inserted }

    /** 是否已有可展示的造数结果（至少一个脚本插过行）。 */
    val hasGenerateResult: Boolean get() = generateScripts.any { it.inserted > 0 }

    /** 造数结果表数据 —— 每个脚本一行。 */
    fun generateResultRows(): List<TableRow> = generateScripts.mapIndexed { index, script ->
        TableRow(
            id = index.toLong(),
            cells = mapOf(
                "script" to "${index + 1}. ${script.title}",
                "table" to script.lastTable.ifBlank { "—" },
                "inserted" to script.inserted.toString(),
            ),
        )
    }

    /**
     * 执行造数 —— 走 `Category.DATA` / `Action.GENERATE` 流式通道：
     * 每条 INSERT 回一帧 `gen_progress_frame`（含 `script_index` / `inserted` / `table`），
     * 终止帧 `generate_terminal.tables_processed` 是已处理脚本数；失败 → [generateError]。
     *
     * 与 [executeSql] 一样用 [generateGeneration] 失效化 in-flight 响应：
     * 重新执行或切换连接后，迟到的进度帧直接丢弃。
     */
    fun executeGenerate() {
        currentConnection ?: return
        val scripts = generateScripts.filter { it.editor.text.isNotBlank() }
        if (scripts.isEmpty()) {
            generateError = "造数脚本为空"
            return
        }
        generateGeneration++
        val gen = generateGeneration
        generateRunning = true
        generateError = null
        generateTablesProcessed = 0
        generateResultPage = 1
        generateResultSelectedRowId = null
        generateScripts.forEach {
            it.inserted = 0
            it.lastTable = ""
        }
        val schema = currentSchema()
        val luaVersion = generateLuaVersion
        scope.launch {
            // 同 executeSql：登记本请求建池所处的 catalog 维度，供 releasePools 回收。
            activeDatabases.add(schema)
            val result = runCatching {
                engine.handle(
                    request {
                        id = UUID.randomUUID().toString()
                        this.connection = engineConn(database = schema)
                        category = Category.DATA
                        action = Action.GENERATE
                        dataRequest = dataRequest {
                            generate = dataGenerateRequest {
                                // 同 executeSql：schema 必须留空，不能塞 catalog 名。
                                this.schema = ""
                                this.luaVersion = luaVersion
                                // 进度帧的 script_index 是**本次请求内**的下标，
                                // 因此回填时也按这个过滤后的列表定位。
                                scripts.forEach { tables += generateTable { script = it.editor.text } }
                            }
                        }
                    },
                )
            }
            if (gen != generateGeneration) return@launch  // 已重新执行 —— 丢弃过期响应
            val flow = result.getOrNull()
            if (flow == null) {
                generateError = result.exceptionOrNull()?.message ?: "造数失败"
                generateRunning = false
                return@launch
            }
            try {
                var cancelled = false
                flow.collect { resp ->
                    if (cancelled) return@collect
                    if (gen != generateGeneration) {
                        cancelled = true
                        return@collect
                    }
                    if (!resp.success) {
                        generateError = resp.error.ifBlank { "造数失败" }
                    } else if (resp.hasGenProgressFrame()) {
                        val frame = resp.genProgressFrame
                        // 引擎的 `script_index` 是 **1-based**（GenerateHandler 发帧时 +1），
                        // 这里转成 0-based 再定位到本次请求的脚本列表。
                        scripts.getOrNull(frame.scriptIndex - 1)?.let { script ->
                            script.inserted = frame.inserted
                            if (frame.table.isNotBlank()) script.lastTable = frame.table
                        }
                    } else if (resp.hasGenerateTerminal()) {
                        generateTablesProcessed = resp.generateTerminal.tablesProcessed
                    }
                }
            } catch (e: Exception) {
                if (gen == generateGeneration) generateError = e.message ?: e.javaClass.simpleName
                generateRunning = false
                return@launch
            }
            if (gen != generateGeneration) return@launch
            if (generateError == null && !hasGenerateResult) generateError = "没有插入任何数据"
            generateRunning = false
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
        // SQL 工作台状态跨连接无意义 —— 整体复位为单个空 sheet。旧 sheet 对象被丢弃，
        // 其 in-flight 响应写进已脱离的实例，不会再出现在 UI 上；仍自增代次让协程尽早停止累积。
        sqlSheets.forEach { it.generation++ }
        sqlSheets.clear()
        sqlSheets.add(SqlSheet("SQL 1"))
        selectedSqlIndex = 0
        // 造数工作台同理 —— 脚本与统计都绑定在上一连接上，整体复位（自增代次使 in-flight 进度帧失效）
        generateScripts.clear()
        generateScripts.add(GenerateScript("脚本 1"))
        selectedGenerateIndex = 0
        generateRunning = false
        generateGeneration++
        generateTablesProcessed = 0
        generateError = null
        generateResultPage = 1
        generateResultSelectedRowId = null
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
