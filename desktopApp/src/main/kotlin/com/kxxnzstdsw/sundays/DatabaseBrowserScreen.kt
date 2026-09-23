package com.kxxnzstdsw.sundays

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 数据库浏览屏幕 —— 第二屏。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │ 连接选择器 / 当前连接状态 / 刷新按钮                                     │
 * ├──────────────┬─────────────────────────────────────────────────────────┤
 * │              │  Tab: [ users | orders | ... ]  [×]                     │
 * │  Schemas     ├─────────────────────────────────────────────────────────┤
 * │   ▾ PUBLIC   │                                                          │
 * │      ▸ users │       DataTable(rows=preview)                            │
 * │      ▸ orders│                                                          │
 * │   ▸ MYDB     │                                                          │
 * │              │                                                          │
 * └──────────────┴─────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 交互
 *
 * - 选中连接后, 左侧自动加载数据库列表(`SCHEMA.LIST level=database`)
 * - 展开数据库节点 → 加载表列表(`TABLE.LIST`)
 * - **双击**表节点 → 打开预览标签页(同一表只存在一个标签页)
 * - 标签页关闭 → `removeTab`; 切换标签页只切换显示, 不重新加载
 *
 * ## 引擎耦合
 *
 * 通过 [IdbEngine.invoke] 走强类型 `SCHEMA.LIST` / `TABLE.LIST` / `DATA.LIST` 路径
 * (与 `ConnectionManagerScreen` 的直连方式一致 —— 详见 desktopApp/ARCHITECTURE.md §Direct 模式集成).
 *
 * 状态由调用方持有（[DatabaseBrowserState]，在 `MainScreen` 中 `remember`），本组件只负责
 * 渲染 + 事件转发 + 连接变化时的副作用 —— 因此同一份状态机可脱离 UI 直接驱动
 * （见 `DatabaseBrowserFlowTest`）。
 *
 * @param browser 浏览状态机（数据库 / 表列表、标签页）；由调用方持有以获得跨导航的生命周期
 * @param connections 全部已保存连接（顶部下拉）
 * @param selectedConnection 当前选中连接
 * @param status 当前连接的引擎会话状态（决定树面板是「未连接」还是加载）
 * @param onSelectConnection 切换连接
 * @param onConnect 建立连接（调用方直连 `IdbEngine.testConnection`）
 * @param onDisconnect 断开连接（调用方直连 `IdbEngine.disconnect`）
 */
@Composable
fun DatabaseBrowserScreen(
    browser: DatabaseBrowserState,
    connections: List<ConnectionConfig>,
    selectedConnection: ConnectionConfig?,
    status: ConnectionStatus,
    onSelectConnection: (ConnectionConfig) -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 注入当前连接（连接变化时清空派生状态），随后在已连接时自动加载数据库列表。
    // 合并成单个 effect 保证 bind 先于 refresh 执行。
    //
    // 状态由调用方持有（见 `MainScreen`）：本组件被导航切换销毁时 [DatabaseBrowserState]
    // 仍存活 —— 打开的标签页不会因为切到「连接管理」再切回来而丢失；同时 [DatabaseBrowserState.releasePools]
    // 借助调用方的长生命周期 scope 才能真正把异步 disconnect 跑完（组件自身的 scope 在 dispose 时已取消）。
    LaunchedEffect(selectedConnection?.id, status.state) {
        browser.bindConnection(selectedConnection)
        when {
            selectedConnection == null -> Unit
            status.state == ConnectionState.CONNECTED -> browser.refreshDatabases()
            // 用户断开 / 连接失败 —— 该连接上建立的池已不可用，逐个释放
            else -> browser.releasePools()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ConnectionBar(
            connections = connections,
            selected = selectedConnection,
            status = status,
            onSelect = onSelectConnection,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onRefresh = {
                if (status.state == ConnectionState.CONNECTED) browser.refreshDatabases()
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(modifier = Modifier.fillMaxSize()) {
            SchemaTreePanel(
                state = browser,
                connected = status.state == ConnectionState.CONNECTED,
                onOpenTable = { schema, table -> browser.openTab(schema, table) },
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PreviewTabArea(
                state = browser,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

// ============================================================================
// 顶部连接选择条
// ============================================================================

@Composable
private fun ConnectionBar(
    connections: List<ConnectionConfig>,
    selected: ConnectionConfig?,
    status: ConnectionStatus,
    onSelect: (ConnectionConfig) -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "当前连接",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))

            ConnectionPicker(
                connections = connections,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )

            if (selected != null) {
                StatusChip(status = status)
                Spacer(Modifier.width(4.dp))
                when (status.state) {
                    ConnectionState.CONNECTED -> {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新数据库列表")
                        }
                        Button(onClick = { onDisconnect(selected) }) { Text("断开") }
                    }
                    ConnectionState.CONNECTING -> Button(onClick = {}, enabled = false) { Text("连接中…") }
                    else -> Button(onClick = { onConnect(selected) }) { Text("连接") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPicker(
    connections: List<ConnectionConfig>,
    selected: ConnectionConfig?,
    onSelect: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.let { "${it.name} (${it.dialect.name})" } ?: "未选择连接",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (connections.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("尚未配置任何连接") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                connections.forEach { cfg ->
                    DropdownMenuItem(
                        text = { Text("${cfg.name}  ·  ${cfg.dialect.name}") },
                        onClick = {
                            expanded = false
                            onSelect(cfg)
                        },
                    )
                }
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
        ConnectionState.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.outline
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
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                )
                tables.isEmpty() -> Text(
                    text = "  (空)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
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
            color = MaterialTheme.colorScheme.outline,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
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
                        color = MaterialTheme.colorScheme.outline,
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
    private val engine: IdbEngine,
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
        errorMessage = null
        expandedDatabases.clear()
        _tablesByDatabase.clear()
        loadingTables.clear()
        _tableLoadError.clear()
        tabs = emptyList()
        selectedTabIndex = -1
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
            loadingDatabases = false
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
            loadingTables.remove(database)
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
            tab.loading = false
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
