package com.kxxnzstdsw.sundays

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.kxxnzstdsw.sundays.settings.formatBytes
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SettingsEntryButton
import com.kxxnzstdsw.sundays.ui.SundaysPalette
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
import kotlinx.coroutines.delay
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
    onOpenSettings: () -> Unit = {},
    memoryProbe: (suspend () -> EngineMemory?)? = null,
    modifier: Modifier = Modifier,
) {
    // 堆占用的**读数与「面板是否展开」都提到屏级**，不放在状态栏自己的组合里 ——
    // 详情面板必须画在本屏最外层 Box：若画在状态栏那个只有 20dp 高的 Box 内，
    // 浮出父级边界的部分收不到指针事件，点击会**穿透**到面板下方的表格行上（选中一行）。
    // 提到屏级后面板落在有完整边界的根 Box 里，既能正常接收点击，也能被 UI 测试断言。
    var memory by remember { mutableStateOf<EngineMemory?>(null) }
    var memoryDetailOpen by remember { mutableStateOf(false) }
    // 面板自身的悬停。状态栏的悬停由 EngineMemoryStatusBar 内的 interactionSource 提供，
    // 但它的值只活在那个组件里 —— 关闭判定必须在屏级做（见下面的宽限期逻辑），
    // 故这里另存一份。
    var memoryPanelHovered by remember { mutableStateOf(false) }
    // 状态栏悬停 —— 同样需要在屏级可见
    var memoryBarHovered by remember { mutableStateOf(false) }
    // 状态栏实测高度 —— 详情面板要锚在它正上方，写死一个常数会在字体缩放 / 紧凑档下错位
    val barHeight = remember { mutableIntStateOf(0) }

    if (memoryProbe != null) {
        // 轮询循环的 key 必须是 Unit，不能是 memoryProbe：调用方每次重组传进来的都是**新 lambda
        // 实例**，拿它当 key 会让定时器每次重组都被重启（永远等不到下一次触发）——
        // 与 SettingsScreen 的 rememberUpdatedState 是同一个坑，这里用 rememberUpdatedState 避开。
        val probe by rememberUpdatedState(memoryProbe)
        LaunchedEffect(Unit) {
            while (true) {
                // 串行 await + delay：上一次没回来就不会发下一次，避免引擎被打爆。
                // 失败保留上一帧读数，不清空 —— gRPC 偶发超时不该让数字来回闪。
                runCatching { probe() }.onSuccess { m -> if (m != null) memory = m }
                delay(MEMORY_POLL_MILLIS)
            }
        }
    }

    // 悬停移出自动关闭 —— **必须有宽限期**。指针从状态栏移到面板的途中会短暂地「两边都不在」，
    // 不延时就会在用户还没走到面板时就把面板关了。
    // key 含两个悬停态：任一变 true 都会重启本 effect，从而取消正在跑的计时器。
    val pointerInside = memoryBarHovered || memoryPanelHovered
    LaunchedEffect(memoryDetailOpen, pointerInside) {
        if (!memoryDetailOpen || pointerInside) return@LaunchedEffect
        delay(MEMORY_PANEL_CLOSE_GRACE_MILLIS)
        // 延时结束后再确认一次：期间指针可能已经回来了（此时 effect 已被重启，不会走到这行，
        // 但显式判断让「宽限期」这个意图在代码里自洽，不依赖重启时机）
        if (!memoryBarHovered && !memoryPanelHovered) memoryDetailOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 没有 sheet 时不渲染标签条：Material3 的 ScrollableTabRow 不接受 0 个 tab
            // （空列表会在测量时 IndexOutOfBounds）。关闭最后一个 sheet 时，`destination` 由
            // MainScreen 的 LaunchedEffect 在**组合之后**才切回首屏 —— 这中间会有一帧以空列表组合，
            // 因此这里必须走「空态引导」而不是标签条。
            val active = sheets.firstOrNull { it.connection.id == activeSheetId }
            if (active == null) {
                // weight(1f) 而非 fillMaxSize()：底部状态栏要占掉一条，内容区必须让出高度。
                // 无状态栏时（memoryProbe == null）weight 仍分到全部高度，与改造前一致。
                EmptySheetsHint(
                    onAddSheet = onAddSheet,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SheetTabRow(
                    sheets = sheets,
                    activeSheetId = activeSheetId,
                    onSelect = onSelectSheet,
                    onClose = onCloseSheet,
                    onAdd = onAddSheet,
                    onOpenSettings = onOpenSettings,
                )
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ActiveSheetContent(
                    sheet = active,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    modifier = Modifier.weight(1f),
                )
            }

            // 底部状态栏（IDEA 式的 JVM 堆占用）—— 与「有没有 sheet」无关：
            // 空态恰恰是用户第一次打开应用停留的地方，此时恰恰最需要知道内存还剩多少。
            if (memoryProbe != null) {
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EngineMemoryStatusBar(
                    memory = memory,
                    detailOpen = memoryDetailOpen,
                    onToggleDetail = { memoryDetailOpen = !memoryDetailOpen },
                    onMeasuredHeight = { barHeight.intValue = it },
                    onHoverChange = { memoryBarHovered = it },
                )
            }
        }

        if (memoryDetailOpen && memoryProbe != null) {
            val density = LocalDensity.current
            EngineMemoryDetailPanel(
                memory = memory,
                onDismiss = { memoryDetailOpen = false },
                onHoverChange = { memoryPanelHovered = it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 底部留出「状态栏 + 其上方那条分割线」的高度，面板正好贴在它上沿
                    .padding(
                        end = 10.dp,
                        bottom = with(density) { (barHeight.intValue + 1.dp.roundToPx()).toDp() },
                    ),
            )
        }
    }
}

/**
 * JVM 堆占用快照 —— 底部状态栏的输入。
 *
 * 四个值与 `SystemHandler.info()` 返回的 `MemoryInfo` 一一对应，也与设置页
 * 「系统信息」里那四行**完全同名同序**（堆已用 / 已分配 / 上限 / 空闲）——
 * 状态栏弹窗就是那一段的缩小版，字段少两个就凑不齐。
 *
 * 之所以不直接传 `SystemInfo`：那个类带 11 个本栏用不上的字段（JVM 版本 / OS / PID…），
 * 每 2 秒搬一遍纯属浪费，且会让「保留上一帧」这类降级逻辑要操心整个对象。
 *
 * @property usedBytes `Runtime.totalMemory() - Runtime.freeMemory()`
 * @property totalBytes `Runtime.totalMemory()` —— 已向 OS **申请**到的堆
 * @property freeBytes `Runtime.freeMemory()` —— 上述已分配堆中尚未使用的部分
 * @property maxBytes `Runtime.maxMemory()`（即 `-Xmx`；未设时是 JVM ergonomics 算出的值）
 */
data class EngineMemory(
    val usedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long,
    val maxBytes: Long,
) {
    /**
     * 占用率，钳在 `0f..1f`。分母用**上限**而非已分配量 —— 用户关心的是「离 OOM 还有多远」，
     * 不是「离这次 GC 还有多远」。
     *
     * 钳位不是防御性冗余，是刚需：`used` 是采样瞬间的值，而 `max` 在容器里可能被调整
     * （`Runtime.maxMemory()` 返回 `Long.MAX_VALUE` 或 0 的实现是存在的），不钳位会画出
     * 一条冲出轨道、糊到标签上的进度条。
     */
    val ratio: Float
        get() = if (maxBytes <= 0L) 0f else (usedBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
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
        // 设置入口（⚙）钉在**最外层**右上角：与左侧「＋」同一行、同高 ——
        // 不占用内容区高度，也不随工作台切换 / sheet 内容变化而移动。
        // 日夜切换**已从本屏移除**，明暗改到设置页的「个性化 → 明暗档位」。
        SettingsEntryButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 4.dp))
    }
}

@Composable
private fun EmptySheetsHint(
    onAddSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 空态下没有标签条也没有工具栏，设置入口若不单独放一个就会彻底消失 ——
        // 而空态恰恰是用户第一次打开应用最可能停留的地方。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
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
 * 底部状态栏 —— IDEA 式的 JVM 堆占用指示器（一条细进度条 + 「已用 / 上限」）。
 *
 * ## 为什么挂在最外层 Column 而不是 sheet 内容里
 *
 * 堆占用是**进程级**指标，不是某条连接的：同一个引擎进程服务着全部 sheet，指标挂在
 * 任一 sheet 上都是取同一份数据，却会在「关掉最后一个 sheet」时一起消失 —— 而空态恰恰
 * 是用户第一次打开应用停留的地方。因此它渲染在 [DatabaseBrowserScreen] 的最外层，
 * 与 `active` 无关。
 *
 * ## 数据源：引擎 `SYSTEM.INFO`，不是本进程 Runtime
 *
 * 直接读 UI 侧 `Runtime.getRuntime()` 看着更省事，但在 **gRPC 模式**下引擎是**另一个进程**
 * —— 真正持有查询结果集、把堆撑爆的是它，不是渲染界面的这个。此时显示 UI 堆会是一个
 * 「看着很闲、实际快 OOM」的假指标。故复用 `fetchSystemInfo`（`SystemHandler.info()`
 * 返回 `Runtime.totalMemory/freeMemory/maxMemory`），Direct / gRPC 两种模式都指向**引擎**堆。
 *
 * ## 拉取失败保留上一次的值
 *
 * 拉取失败保留上一次的值这件事由调用方（[DatabaseBrowserScreen]）负责 —— 它持有轮询状态，
 * 本组件只做呈现。
 *
 * ## 可点击性
 *
 * 悬停时给出**三重**反馈：指针变手型（`pointerHoverIcon`）、底色转为 `surfaceVariant`、
 * 文字转 `onSurface`。只做其中之一都不够 —— 只换指针在没接鼠标的触控板上等于没有，
 * 只换底色用户仍不确定能不能点。
 *
 * @param memory 最新读数；`null` = 探针一次都没成功过
 * @param detailOpen 详情面板是否展开。展开期间**保持**高亮，否则指针一移开状态栏就恢复原样，
 *   视觉上像是面板已经关了
 * @param onToggleDetail 点击回调（由调用方切换展开态）
 * @param onMeasuredHeight 实测行高回调 —— 详情面板要锚在本行正上方，写死常数会在
 *   字体缩放 / 紧凑档下错位
 * @param onHoverChange 本行是否在指针下。屏级用它和面板的悬停一起判定「指针已移开」
 *   （关闭逻辑必须在屏级，见 [DatabaseBrowserScreen]）
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EngineMemoryStatusBar(
    memory: EngineMemory?,
    detailOpen: Boolean,
    onToggleDetail: () -> Unit,
    onMeasuredHeight: (Int) -> Unit,
    onHoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 悬停态同时喂给屏级：关闭判定要的是「状态栏**或**面板都不在指针下」
    LaunchedEffect(hovered) { onHoverChange(hovered) }
    val highlighted = hovered || detailOpen
    val ink = if (highlighted) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 外层铺满、**内容收缩并右对齐**：底部只占内容那么宽，悬停高亮也只覆盖内容本身。
    // 铺满整行的话，那是一条横贯窗口的色带，视觉上比内存数字本身还重 ——
    // 而且会让「可点区域」大到能在离内容很远的地方误触。
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .onSizeChanged { onMeasuredHeight(it.height) }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggleDetail,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                // 4~6dp：再薄就在高 DPI 屏上只剩一条抗锯齿的糊边
                .padding(horizontal = 10.dp, vertical = 4.dp)
                // 底色放在 padding **之后**：高亮才是一个包住内边距的完整小块，
                // 而不是只染了文字那一截
                .background(
                    color = if (highlighted) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                    shape = winShape(4.dp),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "堆内存",
                style = MaterialTheme.typography.labelSmall,
                color = ink,
            )
            Spacer(Modifier.width(6.dp))
            // 轨道 + 填充两段 Box，不用 Canvas —— 圆角交给 winShape，与全应用的容器写法一致
            Box(
                modifier = Modifier
                    .width(MEMORY_BAR_WIDTH)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, winShape(2.dp)),
            ) {
                if (memory != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(memory.ratio)
                            .background(memoryFillColor(memory.ratio), winShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (memory == null) {
                    "— / —"
                } else {
                    "${formatHeapBytes(memory.usedBytes)} / ${formatHeapBytes(memory.maxBytes)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ink,
            )
        }
    }
}

/**
 * 堆内存详情面板 —— 设置页「系统信息」内存段的**缩小版**。
 *
 * 只取内存四行（堆已用 / 已分配 / 上限 / 空闲），标签顺序与取值格式都**复用**设置页那套
 * （`formatBytes`）：同一个数字在两处显示成同一个字符串，是「与设置中的一样」这句话的
 * 最低要求。JVM 版本 / OS / PID 那些不在这里重复 —— 那是设置页「系统信息」分类的职责。
 *
 * ## 为什么是「根 Box 里的层内浮层」而不是 `Popup`
 *
 * 试过 desktop 的 [Popup]，两个问题让它出局：
 *
 * 1. desktop 的 `Popup` 会开一个**独立原生窗口** —— 点一下状态栏弹出一个新窗口去看 4 行字，
 *    既不是 IDEA 的观感，也会抢走主窗口焦点。
 * 2. 它的内容在另一个组合根里，**不进 UI 测试的语义树**，`onNodeWithText` 一律找不到。
 *
 * 故改为由 [DatabaseBrowserScreen] 在**最外层 Box** 里直接绘制。代价是要自己管
 * 「面板画在哪」（底部留出状态栏高度，见调用点），换来的是同一棵语义树 + 不弹窗 + 不抢焦点。
 *
 * ## 宽度按内容收缩
 *
 * 不用 `widthIn(min = …)` 也不让任何一行 `fillMaxWidth()`：那会把「堆已用」和它的取值
 * 推到面板两端，中间拉出一条空档，面板看着像一张没排版的表。改成每行自己 `Spacer` 撑开
 * 固定间距、整块由最宽的一行决定 —— 面板宽度从 200dp 收到约 150dp。
 *
 * ## 悬停移出即自动关闭
 *
 * 见 [DatabaseBrowserScreen] 里的宽限期逻辑：面板与状态栏**都**不在指针下时延时关闭，
 * 这样「从状态栏移到面板」的途中不会误关。
 *
 * [memory] 为 `null`（探针一次都没成功）时仍可打开，但逐行显示「—」而不是空白面板 ——
 * 空面板会让用户以为程序坏了。
 */
@Composable
private fun EngineMemoryDetailPanel(
    memory: EngineMemory?,
    onDismiss: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **离开组合时必须主动回报「未悬停」**。这是上一版埋下的自引入回归：
    // 面板的悬停标志由 reportsHover 的 Enter/Exit 驱动，而指针事件只在节点**存在**时才会发。
    // 用户点面板上的「×」关闭时（以及任何让面板离开组合的路径），`pointerInput` 随节点一起
    // 被销毁，**不会**补发一个 Exit —— 标志就永久停在 true。屏级 `pointerInside` 于是恒真，
    // 宽限期关闭逻辑此后彻底失效（面板再也不会自动关）。
    // 只在 onDismiss 里置位是不够的：断开连接、切换 tab、memoryProbe 变 null 同样会让面板消失。
    // 用 DisposableEffect 兜住「组合销毁」这一统一出口，并经 rememberUpdatedState 取最新回调，
    // 避免每次重组都重启 effect（重启会先跑一次 onDispose，等于凭空制造一次 false 抖动）。
    val latestHoverCallback by rememberUpdatedState(onHoverChange)
    DisposableEffect(Unit) {
        onDispose { latestHoverCallback(false) }
    }

    Surface(
        // testTag：面板本身不带语义，测试量不到它的边界（只能量到里面的文字节点）。
        // 宽度契约要断言的是**面板**而不是某一行文字。
        modifier = modifier.testTag(MEMORY_DETAIL_PANEL_TAG).reportsHover(onHoverChange),
        shape = winShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // 浮层是本文件里**唯一**允许投影的地方：它确实压在内容之上
        // （见 shared/ARCHITECTURE.md §5.4 约束 3）
        shadowElevation = 6.dp,
    ) {
        // IntrinsicSize.Max：面板宽度 = 最宽那一行文字，而不是容器宽度。
        // 不用它的话 Column 会一路撑到父级最大宽度，面板变成一条横贯窗口的色带。
        // 分组线仍是 WinDivider —— 它收到空 modifier 时不填满，在本布局里正好横跨内容宽度。
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "JVM 内存", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                // 显式关闭按钮：自动关闭只在「指针移开」时触发，指针停在面板上时
                // 用户仍需要一个明确的关闭手段
                WinIconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭内存详情",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = "引擎 SYSTEM.INFO",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MemoryInfoRow("堆已用", memory?.let { formatBytes(it.usedBytes) })
            MemoryInfoRow("堆已分配", memory?.let { formatBytes(it.totalBytes) })
            MemoryInfoRow("堆上限", memory?.let { formatBytes(it.maxBytes) })
            MemoryInfoRow("堆空闲", memory?.let { formatBytes(it.freeBytes) })
            Spacer(Modifier.height(2.dp))
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "占用 " + (memory?.let { "${(it.ratio * 100).toInt()}%" } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 把本节点的**指针进出**转成布尔回调。
 *
 * 为什么不用现成方案：这版 Compose（1.11.1）**没有** `Modifier.pointerEnter/pointerExit`，
 * `hoverable` 也不在本文件依赖范围内；而 `MutableInteractionSource.collectIsHoveredAsState`
 * 需要节点挂了 `clickable` —— 给一个只读面板挂空 `onClick` 属于骗语义，读屏会念出一个
 * 假的按钮。故直接监听 `PointerEventType.Enter/Exit`。
 *
 * 必须在 `PointerEventPass.Initial` 收：面板压在内容之上，事件先到它，若在 `Main` pass
 * 才处理，可能已经被下层节点消费掉。
 */
private fun Modifier.reportsHover(onHoverChange: (Boolean) -> Unit): Modifier =
    this.pointerInput(onHoverChange) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> onHoverChange(true)
                    PointerEventType.Exit -> onHoverChange(false)
                    else -> Unit
                }
            }
        }
    }

/**
 * 详情面板里的一行「标签 / 取值」。
 *
 * **不** `fillMaxWidth()` + `SpaceBetween`：那会把取值推到面板右端、与标签之间拉出一条
 * 大空档，面板看起来像一张没排版的表。改为固定 `Spacer` 间距、整行按内容收缩，
 * 面板宽度由最宽的一行决定。
 *
 * 与设置页 `InfoRow` 的差别只有排版方向（那边要顶满整栏便于纵向对齐，这边要收紧），
 * 标签与取值的呈现完全一致。
 */
@Composable
private fun MemoryInfoRow(label: String, value: String?) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 进度条填充色 —— 只分两档。
 *
 * 低于 [MEMORY_ALERT_RATIO] 用 `primary`（本应用低饱和靛蓝），到线转 `error`。
 *
 * **为什么不做绿→黄→红三档**：那套配色属于「仪表盘」语言，与 §5.4 定的低饱和 IDE 观感
 * 打架，而且中间那档黄在五套配色里没有稳定、都达标的对应色（各主题 `tertiary` 的实际色相
 * 差异很大）。两档既够用 —— 用户要的是「要不要担心」，不是「精确的连续读数」。
 */
@Composable
private fun memoryFillColor(ratio: Float): Color =
    if (ratio >= MEMORY_ALERT_RATIO) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

/**
 * 堆字节数 → 紧凑可读（`512M` / `2.0G`），**不带小数位与空格**。
 *
 * 与设置页 `SettingsScreen.formatBytes`（`512.0 MB` / `4.00 GB`）刻意不同：那边是给人
 * 逐行对照精确数值的，宽度不敏感；这里只有 5dp 高、且右边还跟着一个「/ 上限」，
 * 必须省掉小数点和单位里的空格才不至于把状态栏撑宽。两处不是重复，是两种呈现密度。
 */
internal fun formatHeapBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> {
        val gb = bytes / (1024.0 * 1024 * 1024)
        // 一位小数，但「2.0G」这种整值就退成「2G」—— 少一个字符是一个
        if (gb % 1.0 == 0.0) "${gb.toInt()}G" else "%.1fG".format(gb)
    }
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)}M"
    bytes >= 1024L -> "${bytes / 1024L}K"
    bytes < 0 -> "—"
    else -> "${bytes}B"
}

/** 堆占用轮询间隔（毫秒）。2 秒：肉眼能看出趋势，又不至于每秒打一次引擎。 */
private const val MEMORY_POLL_MILLIS = 2_000L

/** 进度条轨道宽度。IDEA 的指示器就是这个量级 —— 够读出比例，又不抢内容的视线。 */
private val MEMORY_BAR_WIDTH = 140.dp

/**
 * 指针移出（状态栏 + 面板都不在指针下）到自动关闭的**宽限期**。
 *
 * 不是装饰性延时：指针从状态栏移到面板的途中必然有一小段「两边都不在」，不延时就会在
 * 用户还没走到面板时把它关掉。200ms 够跨过这段空隙，又短到「我不想要它了」几乎立即生效。
 */
private const val MEMORY_PANEL_CLOSE_GRACE_MILLIS = 200L

/** 详情面板的 UI 测试 tag —— 面板本身无语义，测试要量它的边界就得靠它。 */
internal const val MEMORY_DETAIL_PANEL_TAG = "engineMemoryDetailPanel"

/** 占用率到这个值就把进度条染成 `error`。 */
private const val MEMORY_ALERT_RATIO = 0.85f

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
    // **必须显式持有** —— LazyColumn 不传 state 时内部用的是 `rememberLazyListState()`，
    // 而这个 remember 的宿主是**调用点的组合作用域**：右栏 pane 切换（表预览 ⇄ SQL ⇄ 造数）
    // 会把整棵子树移出再移回组合，remember 随之销毁、滚动位置回到顶部。
    // 结果就是「看一眼 SQL 回来，树又滚回顶部，得重新展开找到刚才那张表」——
    // 与本屏「每 sheet 状态互相独立、跨视图不丢失」的既有契约直接冲突。
    val listState = rememberLazyListState()
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
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
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

/**
 * 树上的一个表叶子 —— 单击打开数据预览。
 *
 * ## 为什么从 `detectTapGestures(onDoubleTap)` 换成 `clickable`
 *
 * 旧写法只挂手势，**不带任何语义**：读屏用户听到的只是一个静态文本，没有「打开表」这个动作，
 * 键盘用户 Tab 过去按 Enter 也没反应 —— 等于这条路径对两类用户完全不存在。
 * 换用 `clickable` 后三者一起拿到：语义动作（`onClickLabel`）、键盘焦点与 Enter/Space 激活、
 * 鼠标单击。
 *
 * **双击不会因此失效**：一次双击会产生两次 `onClick`，第二次进 [DatabaseBrowserState.openTab]
 * 时该表已存在，按 key 命中「只激活、不新增」的分支，是幂等的。旧的手势检测器还要额外
 * 引入 `awaitPointerEventScope`，与 [reportsHover] 里同样的手写监听重复。
 */
@Composable
private fun TableLeaf(
    tableName: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "打开表 $tableName",
                role = Role.Button,
                onClick = onOpen,
            )
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

/**
 * 空态 / 错误态占位 —— 标题 + 可选的说明文字。
 *
 * ## 为什么必须能滚
 *
 * [description] 在实际使用中常常是**引擎返回的整段报错**（几百字符起步），不是一句提示语。
 * 原来的写法是「`fillMaxSize()` + `verticalArrangement = Center`」：内容一旦超出容器高度，
 * 居中溢出会**同时裁掉首尾** —— 而报错最关键的位置（`Caused by:`、出错的表名列名）恰恰
 * 在末尾，等于把诊断线索藏起来，且用户没有任何手段去看它。
 *
 * ## 为什么「滚动」与「居中」要分两层
 *
 * 不能简单地把 `verticalScroll` 挂在原来那个 `Column` 上：`verticalScroll` 会用
 * 「高度无上界」的约束去测量子节点，于是 `Arrangement.Center` 恒等于顶对齐，短文案
 * 就不再居中了。故外层 `Box` 负责居中、内层 `Column` 负责滚动，两者职责分开。
 *
 * @param centerContent 是否垂直居中。占位提示（无数据 / 无结果）居中好看；
 *   错误则传 `false` 顶对齐 —— 报错要从上往下顺着读，居中会让首行悬在半空。
 */
@Composable
private fun EmptyHint(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    centerContent: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (centerContent) Alignment.Center else Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    // fillMaxWidth 在**内**层：宽度必须占满，否则长单词（无空格的 SQL 片段 /
                    // 堆栈行）会按固有宽度撑出去、反而触发外层的横向裁切。
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
                    onPageChange = { page -> state.goToTabPage(current, page) },
                    onPageSizeChange = { size -> state.changeTabPageSize(current, size) },
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
    onPageChange: (Int) -> Unit,
    onPageSizeChange: (PageSize) -> Unit,
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
                        // **必须限一行**：这一行与下方 `fillMaxSize()` 的 DataTable 同处一个
                        // Column，报错有几百字符时会把表格挤成 0 高 —— 表格整个消失，
                        // 而用户真正需要看的长报错反而被压在这一行里。完整内容在下方错误区可滚。
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            // 错误分支**必须排在最前**：翻页失败时 columns 仍留着上一页的、rows 已清空，
            // 若按「有列就画表格」的顺序判断，用户看到的是一张有表头但零行的空表，
            // 真正的失败原因只在上方那一行被省略号截断的提示里。
            tab.error != null -> EmptyHint(
                title = "读取失败",
                description = tab.error,
                centerContent = false,
                modifier = Modifier.fillMaxSize(),
            )
            tab.columns.isNotEmpty() || tab.rows.isNotEmpty() -> DataTable(
                columns = tab.columns,
                rows = tab.rows,
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
            sheet.error != null -> EmptyHint(
                title = "执行失败",
                description = sheet.error,
                // 报错顶对齐、从上往下读
                centerContent = false,
                modifier = Modifier.fillMaxSize(),
            )
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
            state.generateError != null -> EmptyHint(
                title = "造数失败",
                description = state.generateError,
                centerContent = false,
                modifier = Modifier.fillMaxSize(),
            )
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

    /**
     * 本 tab 的 in-flight 失效代次 —— 语义与 [SqlSheet.generation] 一致，但作用域是**单个 tab**。
     *
     * 之前预览只有屏级的 [DatabaseBrowserState.generation]（连接级），管不到「同一张表上
     * 连着翻两页」：两次 `loadTabPreview` 并发在跑，谁先返回谁先写，**慢的那次会覆盖快的那次**。
     * 症状是信息条写着「第 2 页」而表里是第 1 页的行 —— 翻页越快越容易撞上。
     * 这与「旧流异常抹掉新请求的 running」是同一类缺陷：多个 in-flight 请求写同一份状态，
     * 却没有一个把它们区分开的令牌。
     */
    internal var requestGeneration: Int = 0
}

/**
 * 引擎侧的 [TablePreviewTab.pageSize]（Int）→ UI 侧的 [PageSize]（枚举）。
 *
 * 之所以两边类型不同：协议里 pageSize 是整数，而分页下拉要展示的是带标签的固定档位。
 * 精确匹配不到时（引擎把 500 钳成了 1000 之类）取数值最接近的档位 ——
 * 宁可让下拉显示一个近似值，也不要退化成 [PageSize.ALL]：那会让分页栏在服务端分页下
 * 变成「不分页」，而 rows 只有一页，界面看着正常、翻页却永远不出新数据。
 */
private fun Int.toPageSize(): PageSize =
    PageSize.entries.firstOrNull { it.value == this }
        ?: PageSize.entries.filterNot { it.isAll }.minByOrNull { kotlin.math.abs(it.value - this) }
        ?: PageSize.S100

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
                // **两行都要在代次守卫内**。running 是「这个 sheet 正在执行」的单一真相源，
                // 一旦过期请求把它抹成 false，用户会看到转圈消失、以为没在跑，于是**再点一次执行** ——
                // 两次执行叠在一起，后一次的结果被后一代次接管，前一次的错误也再没机会显示。
                // 守卫外的写法正是在制造这个「看起来空闲、实际在跑」的空窗。
                if (gen == sheet.generation) {
                    sheet.error = e.message ?: e.javaClass.simpleName
                    sheet.running = false
                }
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
                // 同 executeSql：错误与 running 必须一起在代次守卫内，
                // 否则上一次造数请求的异常会抹掉新请求的「执行中」，用户就会再点一次，两次造数叠着跑。
                if (gen == generateGeneration) {
                    generateError = e.message ?: e.javaClass.simpleName
                    generateRunning = false
                }
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
        // 先释放上一连接在本屏建立的池（池 key 含连接字段，换连接后不再可达）。
        // releasePools 内部会调 invalidateInFlight —— 代次自增 / loading 复位都在那里做，
        // 这里**不要**再写一遍：曾经两份各写一半，漏掉的那一半就是「切连接后转圈停不下来」的来源。
        releasePools()
        currentConnection = config
        databases = emptyList()
        errorMessage = null
        expandedDatabases.clear()
        _tablesByDatabase.clear()
        _tableLoadError.clear()
        tabs = emptyList()
        selectedTabIndex = -1
        // SQL 工作台状态跨连接无意义 —— 整体复位为单个空 sheet。旧 sheet 对象被丢弃，
        // 其 in-flight 响应写进已脱离的实例，不会再出现在 UI 上；代次已在 invalidateInFlight 里自增。
        sqlSheets.clear()
        sqlSheets.add(SqlSheet("SQL 1"))
        selectedSqlIndex = 0
        // 造数工作台同理 —— 脚本与统计都绑定在上一连接上，整体复位
        generateScripts.clear()
        generateScripts.add(GenerateScript("脚本 1"))
        selectedGenerateIndex = 0
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
        // **必须**在下面两行早退之前作废 in-flight：「没有当前连接」和「一个池都没建过」
        // 都是合法且常见的路径（首次进入就断开 / 只读了一屏就断开），跳过这一步的后果见
        // [invalidateInFlight] 的 KDoc —— 最典型的是界面永远卡在「执行中…」。
        invalidateInFlight()
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

    /**
     * 作废本屏全部 in-flight 请求，并复位所有「正在执行 / 正在加载」标志。
     *
     * 池被释放**不会**让先前的请求自己结束：引擎端的流可能还挂着，协程还活着并仍会往
     * 已复位（或已换连接）的状态里写。不同步作废就有两个后果：
     *
     * 1. **转圈停不下来** —— 唯一会写 `running = false` 的是那条协程本身，而它此时要么已被
     *    代次挡住提前 return、要么还卡在流上。界面于是永远显示「执行中…」，用户既看不到结果
     *    也无法再点一次。
     * 2. **重连后旧数据继续写入** —— 同一个 sheet 对象跨断开/重连是存活的，上一条连接的
     *    行帧会落进新连接的结果表。
     *
     * 标签页的 `loading` 同样要复位：`loadTabPreview` 在代次不符时是 `return@launch` **早退**、
     * 不清 loading，漏掉这一项表预览就会一直转。
     */
    private fun invalidateInFlight() {
        generation++
        loadingDatabases = false
        loadingTables.clear()
        sqlSheets.forEach { sheet ->
            sheet.generation++
            sheet.running = false
        }
        generateGeneration++
        generateRunning = false
        // 预览同理：自增 tab 级代次让在飞的载入作废，再手动清 loading ——
        // `loadTabPreview` 的守卫是**早退**（不清 loading），漏了这一项表预览会一直转。
        tabs.forEach {
            it.requestGeneration++
            it.loading = false
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

    /**
     * 翻到指定页。页码先落到 tab 上再发请求，这样加载中信息条显示的就是**目标页**
     * 而不是上一页（否则会出现「点了下一页，转圈时却写着第 1 页」的错位）。
     */
    fun goToTabPage(tab: TablePreviewTab, page: Int) {
        if (page < 1 || page == tab.page) return
        tab.page = page
        // 换页后旧页的选中行不再存在于本页，清掉避免详情面板显示上一批数据
        tab.rows = emptyList()
        loadTabPreview(tab, page)
    }

    /**
     * 改分页大小。**必须回到第 1 页** —— 页大小变了以后旧页码多半越界
     * （100 行/页的第 5 页在 20 行/页下变成第 25 页，而表只有 100 行 = 5 页），
     * 不归位就会停在一个永远加载不出内容、又没有「越界回退」可救的页上。
     */
    fun changeTabPageSize(tab: TablePreviewTab, size: PageSize) {
        val next = size.value.coerceAtLeast(1)
        if (next == tab.pageSize) return
        tab.pageSize = next
        tab.page = 1
        tab.rows = emptyList()
        loadTabPreview(tab, targetPage = 1)
    }

    /**
     * 载入某个表预览标签页的**指定页**。
     *
     * [targetPage] 默认取 tab 自己记的页码：翻页是「改 tab.page + 重新载入」这一对动作，
     * 页码的唯一真相源在 tab 上，把默认值放在这里可以让首次打开（tab.page 初始为 1）
     * 与翻页共用同一条路径，不会出现两条逻辑漂移。
     *
     * 参数名不叫 `page`：`dataListRequest` 的 DSL 里同名属性会把它解析成 builder 自己的
     * `val page`，导致「给 builder 赋值」变成给只读局部变量赋值，编译直接失败。
     */
    private fun loadTabPreview(tab: TablePreviewTab, targetPage: Int = tab.page) {
        val sessionGeneration = generation
        // tab 级代次：同一张表上的并发载入靠它区分。连翻两页时两次请求都在飞，
        // 没有这个令牌就是「谁后返回谁说了算」—— 慢的第 1 页会盖掉快的第 2 页。
        tab.requestGeneration++
        val tabGeneration = tab.requestGeneration
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
                            // **不能写死 1**：写死等于分页器是装饰品 —— 引擎每次都回第 1 页，
                            // 而表超过一页时用户永远看不到后面的行。
                            page = targetPage.coerceAtLeast(1)
                            // `pageSize = 0` 在 DATA.LIST 里是**流式**哨兵（逐行 frame，无 paged body），
                            // 预览固定走分页路径，故下限钳到 1。
                            pageSize = tab.pageSize.coerceAtLeast(1)
                        }
                    }
                }
            }
            // 两级守卫都要在**写任何状态之前**判：连接换了，或这个 tab 已经翻到别的页去了。
            // 早退时**不清 loading** 是有意的 —— 清它会把新一轮的转圈也一并抹掉。
            if (sessionGeneration != generation) return@launch
            if (tabGeneration != tab.requestGeneration) return@launch
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
