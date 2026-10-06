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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import com.kxxnzstdsw.sundays.ui.WinMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
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
import com.kxxnzstdsw.grpc.foreignKeyListRequest
import com.kxxnzstdsw.grpc.foreignKeyRequest
import com.kxxnzstdsw.grpc.functionListRequest
import com.kxxnzstdsw.grpc.functionRequest
import com.kxxnzstdsw.grpc.indexListRequest
import com.kxxnzstdsw.grpc.indexRequest
import com.kxxnzstdsw.grpc.triggerListRequest
import com.kxxnzstdsw.grpc.triggerRequest
import com.kxxnzstdsw.grpc.viewListRequest
import com.kxxnzstdsw.grpc.viewRequest
import com.kxxnzstdsw.grpc.systemRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.editor.CompletionItem
import com.kxxnzstdsw.sundays.editor.CompletionKind
import com.kxxnzstdsw.sundays.editor.GenerateHelpers
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorState
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.sundays.settings.formatBytes
import com.kxxnzstdsw.sundays.table.CellEdit
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.DragHandle
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

    // 左侧库/表树宽度 —— **刻意放在这一层**（而不是 `ActiveSheetContent` 里）。
    //
    // 理由：同一时刻只渲染**一个**激活 sheet，宽度若存在 `ActiveSheetContent` 内，
    // 切 sheet 时那棵组合被拆掉重建，`remember` 随之丢失 —— 于是「在 A 连接把树拉宽，
    // 切到 B 连接又缩回 320dp」。布局宽度是**窗口级偏好**，不是每个 sheet 各自的。
    //
    // 不落盘（用户明确选择）：拖好的宽度只活在本次会话，重启回 320dp。
    // 树本身的状态（展开 / 滚动 / 已加载的表）由每 sheet 的 `DatabaseBrowserState` 持有，
    // 与这里的纯布局宽度是两回事，别混。
    var schemaPanelWidth by remember { mutableStateOf(SCHEMA_PANEL_DEFAULT_WIDTH) }

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
                    schemaPanelWidth = schemaPanelWidth,
                    onSchemaPanelWidthChange = { schemaPanelWidth = it },
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

/** 左侧库/表树的默认宽度 —— 双击分隔条即复位到这个值。 */
private val SCHEMA_PANEL_DEFAULT_WIDTH: Dp = 320.dp

/** 左侧库/表树宽度的下限 —— 再窄就看不到表名了。 */
private val SCHEMA_PANEL_MIN_WIDTH: Dp = 180.dp

/** 库/表树面板的 UI 测试 tag —— 面板本身无语义，量宽度得靠它。 */
internal const val SCHEMA_PANEL_TAG = "schemaTreePanel"

/** 「多语句」开关的 UI 测试 tag —— 见 [SqlSheet.multiStatement]。 */
internal const val SQL_MULTI_STATEMENT_CHIP_TAG = "sqlMultiStatementChip"

/** 「事务」按钮的 UI 测试 tag —— 见 [DatabaseBrowserState.beginTransaction]。 */
internal const val SQL_TRANSACTION_BTN_TAG = "sqlTransactionBtn"

/** 分隔条的 UI 测试 tag —— 拖拽测试需要一个明确的落点，不能靠坐标猜。 */
internal const val SCHEMA_DRAG_HANDLE_TAG = "schemaDragHandle"

/**
 * SQL 结果集在内存里最多攒多少行 —— 超出即停止累积并标 `rowsTruncated`。
 *
 * ## 为什么必须有上限
 *
 * `SQL.EXECUTE` 是**流式**的，引擎会一直推 `sql_row_frame`。此前前端无条件
 * `frames += resp.sqlRowFrame`，于是 `SELECT * FROM big_table` 会把整个结果集
 * 搬进堆里 —— 一张千万行的表就能把桌面进程拖垮，而用户能做的只有看着进度条
 * 慢慢爬。**引擎侧有 `Statement.cancel()` 可用，但前端当时连 request id 都没留下**，
 * 既不能停也不能少收。
 *
 * ## 为什么是 5000
 *
 * 桌面工具的默认结果窗口通常是几百到几千行。5000 行按每行 5 列估算约几 MB，
 * 远低于让 JVM 频繁 GC 的量级；再往上（比如 10 万）用户在表格里翻不到底，
 * 留着也只是在占内存 —— 真要全量数据，用户该用导出而不是结果面板。
 *
 * 截断时**必须**显式告知（`SqlResultArea` 的提示条）：静默截断等于骗人 ——
 * 用户会以为这张表就这么大。
 */
internal const val SQL_RESULT_MAX_ROWS = 5000

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
    schemaPanelWidth: Dp,
    onSchemaPanelWidthChange: (Dp) -> Unit,
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
        // 连接失败原因此前**只在连接管理页**能看到，浏览屏上只有一个红点 ——
        // 用户在浏览屏看到红点，却不知道是密码错了、网络断了还是驱动没装，
        // 只能切回去猜。失败原因就在手边的 [ConnectionStatus.message] 里，
        // 这里直接摆出来，不用他来回切屏。
        if (sheet.status.state == ConnectionState.FAILED) {
            ConnectionFailureBanner(
                message = sheet.status.message,
                onRetry = { onConnect(sheet.connection) },
            )
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 宽度上限按容器比例给，而不是固定 dp：窗口窄时若还允许拉到 600dp，
        // 右侧工作台会被挤到没法用。0.62 留足了右栏的最低可读宽度。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxPanelWidth = maxWidth * 0.62f
            Row(modifier = Modifier.fillMaxSize()) {
                SchemaTreePanel(
                    state = sheet.browser,
                    connected = sheet.status.state == ConnectionState.CONNECTED,
                    onOpenTable = { schema, table -> sheet.browser.openTab(schema, table) },
                    modifier = Modifier
                        .width(schemaPanelWidth)
                        .fillMaxHeight(),
                )
                DragHandle(
                    width = schemaPanelWidth,
                    onWidthChange = onSchemaPanelWidthChange,
                    defaultWidth = SCHEMA_PANEL_DEFAULT_WIDTH,
                    minWidth = SCHEMA_PANEL_MIN_WIDTH,
                    maxWidth = maxPanelWidth,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    highlightColor = MaterialTheme.colorScheme.primary,
                    // testTag：分隔条画出来只有 1dp 宽，没有 tag 的话测试只能靠坐标猜
                    modifier = Modifier.testTag(SCHEMA_DRAG_HANDLE_TAG),
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

/**
 * 连接失败横幅 —— 浏览屏上的**唯一**失败原因出口。
 *
 * ## 为什么不是 tooltip
 *
 * tooltip 只在指针悬停时出现，而「为什么连不上」是用户**必须**看到才能继续处理的信息：
 * 他可能根本没往色点上放鼠标，或者只瞥了一眼就走了。tooltip 适合补充信息，不适合承载
 * 唯一的诊断入口。横幅是常驻的、切屏也带得走。
 *
 * ## 为什么可滚动
 *
 * 引擎回的失败原因常是一整段带 `Caused by` 的堆栈（几百字符），与 `EmptyHint` 同一个理由：
 * 关键信息常常在**末尾**，横向或纵向一刀切掉的话用户看到的等于没有。
 * 固定高度 + 纵向滚动是这里唯一诚实的选择（详见 `EmptyHint` 的 KDoc）。
 */
@Composable
private fun ConnectionFailureBanner(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 标签「连接失败」不省略号截断 —— 它是这句横幅在语义上的主语
            Text(
                text = "连接失败",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.ifBlank { "引擎未给出原因" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // 失败原因多半是「网络断了」这类瞬时问题，就地重试比让他切去连接管理页再切回来顺手。
            // 密码错了也一样 —— 重试一次只是再失败一次，不会有副作用。
            //
            // 不刻意收窄内边距：`WinButton` 没有 contentPadding 参数（v2.21 评估后认定
            // 「为省 30dp 给共享控件加参数」不划算），而 `Modifier.padding` 是**外**缩 —
            // 按钮宽度由 MinWidth 与内容决定，外层 padding 不会让它变窄。按默认尺寸即可，
            // 顺带满足 48dp 的点击区。
            WinButton(onClick = onRetry, shape = SundaysPalette.buttonShape) {
                Text("重试", maxLines = 1, softWrap = false)
            }
        }
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
        // testTag：面板本身不带语义，测试量不到它的边界（里面的文字节点宽度会随表名变化）。
        // 宽度契约要断言的恰恰是**面板**，所以必须给它一个锚点。
        modifier = modifier.testTag(SCHEMA_PANEL_TAG),
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
                else -> {
                    tables.forEach { tbl ->
                        val slot = "$name::$tbl"
                        TableLeaf(
                            tableName = tbl,
                            onOpen = { onOpenTable(name, tbl) },
                            expanded = slot in state.expandedTableObjects,
                            onToggleObjects = { state.toggleTableObjects(name, tbl) },
                            indexNames = state.tableObjects[slot]
                                ?.get(DatabaseBrowserState.TableObjectKind.INDEX).orEmpty(),
                            foreignKeyNames = state.tableObjects[slot]
                                ?.get(DatabaseBrowserState.TableObjectKind.FOREIGN_KEY).orEmpty(),
                        )
                    }
                    // 库级对象（视图 / 触发器 / 过程·函数）排在表之后。
                    // 顺序有讲究：用户 90% 的时间在找表，把表放前面、对象放后面，
                    // 免得每展开一个库都先滚过一屏用不上的东西。
                    val objErr = state.objectLoadError.entries
                        .firstOrNull { it.key.startsWith("$name::") }?.value
                    val objBusy = state.loadingObjects.any { it.first == name }
                    val objs = state.objectsByDatabase[name]
                    when {
                        objErr != null -> Text(
                            text = "  对象加载失败: $objErr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                        )
                        objs == null && objBusy -> Text(
                            text = "  对象 加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                        )
                        else -> Column(modifier = Modifier.padding(start = 24.dp)) {
                            Text(
                                text = "对象",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DatabaseBrowserState.DatabaseObjectKind.entries.forEach { kind ->
                                ObjectGroupRow(
                                    label = kind.label,
                                    items = objs?.get(kind).orEmpty(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 对象分组下的一行：`标签: 名字1, 名字2 …`。空列表显示「(无)」。 */
@Composable
private fun ObjectGroupRow(label: String, items: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (items.isEmpty()) "(无)" else items.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
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
    expanded: Boolean = false,
    onToggleObjects: (() -> Unit)? = null,
    indexNames: List<String> = emptyList(),
    foreignKeyNames: List<String> = emptyList(),
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = "打开表 $tableName",
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleObjects != null) {
                // 展开箭头：与库节点的 chevron 同一套视觉语言。
                // 单独一个小按钮而不是让整行双击 —— 整行单击已经占用在「打开预览」上，
                // 同一个手势不能既开预览又展开对象。
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "收起 $tableName 的索引与外键" else "展开 $tableName 的索引与外键",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onToggleObjects() },
                )
                Spacer(Modifier.width(2.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }
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
        if (expanded) {
            // 索引 / 外键放在**表节点下**而不是库节点下：引擎侧这两条路由要
            // `table_name`，跟着表走才对得上；放库下就得把整库每张表的索引都拉一遍。
            Column(modifier = Modifier.padding(start = 44.dp)) {
                ObjectGroupRow("索引", indexNames)
                ObjectGroupRow("外键", foreignKeyNames)
            }
        }
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
                val scope = rememberCoroutineScope()
                PreviewTabContent(
                    tab = current,
                    onPageChange = { page -> state.goToTabPage(current, page) },
                    onPageSizeChange = { size -> state.changeTabPageSize(current, size) },
                    // 编辑回调只在 [DatabaseBrowserState.isTableEditable] 为真时挂上 ——
                    // 它依赖引擎回传主键列名，而 `DATA.LIST` 目前不回（见该函数 KDoc）。
                    // 失败文案写回 tab.error：错误条已经在预览顶部，位置现成，
                    // 不必为此再引入一个弹窗。
                    onCellEdit = if (state.isTableEditable(current)) {
                        { edit ->
                            scope.launch {
                                val err = state.updateCell(current, edit)
                                if (err != null) current.error = err
                            }
                        }
                    } else {
                        null
                    },
                    // 与 onCellEdit 同源但**独立**传：行级「选中行」的 clickable 只在
                    // 真能编辑时才让位，否则只读浏览会整行点不动。
                    cellEditable = state.isTableEditable(current),
                    onFilterChange = { state.setTabFilter(current, it) },
                    onOrderByChange = { state.setTabOrderBy(current, it) },
                    onSearchChange = { state.setTabSearch(current, it) },
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
) {
    // 本地草稿：受控输入框每敲一个字符都触发一次引擎往返会打爆数据库，
    // 所以文本先落在这里，回车或失焦才提交。
    var searchDraft by remember(tab) { mutableStateOf(tab.searchTerm) }
    var filterOpen by remember(tab) { mutableStateOf(tab.whereClause.isNotBlank()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchDraft,
            onValueChange = { searchDraft = it },
            label = { Text("搜索") },
            singleLine = true,
            modifier = Modifier
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
        ) { Text("搜索") }
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
            ) { Text("清除") }
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
                    if (tab.orderByClause.isBlank()) "排序" else "排序：${tab.orderByClause}",
                    maxLines = 1,
                    softWrap = false,
                )
            }
            DropdownMenu(expanded = orderOpen, onDismissRequest = { orderOpen = false }) {
                ORDER_PRESETS.forEach { (label, clause) ->
                    WinMenuItem(
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
        ) { Text(if (filterOpen) "隐藏过滤" else "过滤") }
    }
    if (filterOpen) {
        var whereDraft by remember(tab) { mutableStateOf(tab.whereClause) }
        OutlinedTextField(
            value = whereDraft,
            onValueChange = { whereDraft = it },
            label = { Text("WHERE 条件（不含 WHERE 关键字）") },
            singleLine = true,
            modifier = Modifier
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
            ) { Text("应用") }
            Spacer(Modifier.width(8.dp))
            Text(
                // 错误一定要就地显示：引擎回的 `ORA-00933` 之类不在这里，
                // 用户只会看到「刷新后表格空了」，完全无从判断是自己写错了还是引擎挂了
                text = tab.error?.takeIf { it.isNotBlank() && whereDraft != tab.whereClause }
                    ?.let { "条件有误：$it" } ?: "",
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
private val ORDER_PRESETS: List<Pair<String, String>> = listOf(
    "不排序" to "",
    "第一列升序" to "1 ASC",
    "第一列降序" to "1 DESC",
)

/**
 * 危险写操作的确认框。
 *
 * ## 为什么把**每一条**都列出来
 *
 * 一条脚本里可能有好几条危险语句。只报第一条的话，用户点完确认会以为
 * 「确认了这一条就没别的事了」，而第二条照样执行 —— 那比不拦更危险。
 */
@Composable
private fun DangerousSqlConfirmDialog(
    findings: List<DangerousSql.Finding>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认执行危险操作") },
        text = {
            Column {
                Text(
                    text = "接下来的执行包含 ${findings.size} 条不可撤销的写操作：" +
                        "一旦执行，数据库里没有撤销点。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                findings.forEach { f ->
                    Text(
                        text = "· ${f.kind.label}：${f.statement}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            WinButton(
                onClick = onConfirm,
                shape = SundaysPalette.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(DANGEROUS_CONFIRM_BTN_TAG),
            ) { Text("仍然执行") }
        },
        dismissButton = {
            WinButton(onClick = onDismiss, shape = SundaysPalette.buttonShape) { Text("取消") }
        },
    )
}

/** 危险操作确认框「仍然执行」按钮的 UI 测试 tag。 */
internal const val DANGEROUS_CONFIRM_BTN_TAG = "dangerousConfirmBtn"

/** SQL 工作台「只读」开关的 UI 测试 tag。 */
internal const val SQL_READONLY_CHIP_TAG = "sqlReadonlyChip"

/** 表预览过滤行的 UI 测试 tag。 */
internal const val TABLE_SEARCH_FIELD_TAG = "tableSearchField"
internal const val TABLE_SEARCH_BTN_TAG = "tableSearchBtn"
internal const val TABLE_FILTER_BTN_TAG = "tableFilterBtn"
internal const val TABLE_FILTER_FIELD_TAG = "tableFilterField"
internal const val TABLE_ORDER_BTN_TAG = "tableOrderBtn"

@Composable
private fun PreviewTabContent(
    tab: TablePreviewTab,
    onPageChange: (Int) -> Unit,
    onPageSizeChange: (PageSize) -> Unit,
    onCellEdit: ((CellEdit) -> Unit)? = null,
    cellEditable: Boolean = onCellEdit != null,
    onFilterChange: (String) -> Unit = {},
    onOrderByChange: (String) -> Unit = {},
    onSearchChange: (String) -> Unit = {},
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
        TableFilterBar(
            tab = tab,
            onFilterChange = onFilterChange,
            onOrderByChange = onOrderByChange,
            onSearchChange = onSearchChange,
        )
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
                // 传 null = 只读。什么时候能传非 null，见 [DatabaseBrowserState.isTableEditable]
                onCellEdit = onCellEdit,
                cellEditable = cellEditable,
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
    // 补全候选：库 / 表 / 字段，全部来自已有状态（见 [DatabaseBrowserState.sqlSchemaCompletions]）。
    // 缓存签名见 [schemaCompletionSignature] —— 它刻意不含 `tab.rows`。
    val schemaCompletions = remember(
        state.databases,
        state.tablesByDatabase,
        schemaCompletionSignature(state.tabs),
    ) {
        state.sqlSchemaCompletions()
    }

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
        // 事务 / 多语句开关的点击都要发引擎请求，用组合作用域发起
        val actionScope = rememberCoroutineScope()
        // 待确认的危险操作。**非 null 时弹确认框，不执行**——
        // 判据在 [DangerousSql]，与只读模式（[DatabaseBrowserState.readOnly]）是两道独立的防线
        var pendingDangerous by remember { mutableStateOf<List<DangerousSql.Finding>?>(null) }
        if (pendingDangerous != null && sheet != null) {
            DangerousSqlConfirmDialog(
                findings = pendingDangerous!!,
                onConfirm = {
                    pendingDangerous = null
                    state.executeSql()
                },
                onDismiss = { pendingDangerous = null },
            )
        }
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
                    // 注入当前连接的库 / 表 / 字段（**零额外请求**，见 sqlSchemaCompletions）。
                    // 关键字由上面的 `languageId` 按方言档位自动带来，无需重复注入。
                    // 忽略大小写：标识符大小写行为因方言而异，敲 `FROM USERS` 也该能补出 `users`
                    extraCompletions = schemaCompletions,
                    extraCompletionsCaseSensitive = false,
                    actions = {
                        // 只读模式开关 —— 摆最左，因为它决定后面所有按钮按下去会发生什么
                        FilterChip(
                            selected = state.readOnly,
                            onClick = { state.readOnly = !state.readOnly },
                            enabled = !sheet.running,
                            label = { Text("只读", maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag(SQL_READONLY_CHIP_TAG),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 多语句开关 —— 放在最左侧，因为它改的是**执行语义**，
                        // 而执行 / 停止 / 事务都排在它后面，视觉上是「先定模式再操作」。
                        //
                        // 做成逐 sheet 的开关而不是全局默认开：多语句意味着
                        // 「一次点执行会改多张表」，用户得能对某个脚本明确表态。
                        // 用 [FilterChip] 而不是复选框：它自己就是「选中态 + 可禁用」
                        // 的两态控件，与旁边的 WinButton 视觉重量一致。
                        FilterChip(
                            selected = sheet.multiStatement,
                            onClick = { sheet.multiStatement = !sheet.multiStatement },
                            enabled = !sheet.running,
                            label = { Text("多语句", maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag(SQL_MULTI_STATEMENT_CHIP_TAG),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 事务按钮组 —— 在执行 / 停止之前。
                        // 开着事务时「提交 / 回滚」必须一眼可见：数据没落盘这件事
                        // 靠状态栏那行小字提醒是不够的，用户关窗口就全丢了。
                        if (sheet.transactionSessionId != null) {
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.commitTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                shape = SundaysPalette.buttonShape,
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("提交")
                            }
                            Spacer(Modifier.width(4.dp))
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.rollbackTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                shape = SundaysPalette.buttonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("回滚")
                            }
                            Spacer(Modifier.width(4.dp))
                        } else {
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.beginTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                enabled = connected && !sheet.running,
                                shape = SundaysPalette.buttonShape,
                                modifier = Modifier.testTag(SQL_TRANSACTION_BTN_TAG),
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("事务")
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        // 执行中：主按钮让位给「停止」。
                        //
                        // 两个按钮**互斥**而不是并存 —— 执行期间 `enabled` 已是 false，
                        // 摆在一起只会让用户去点一个按不动的按钮。「停止」单独占位还顺带
                        // 避免了「点错了以为是执行」的重灾区。
                        if (sheet.running && sheet.runningRequestId != null) {
                            WinButton(
                                onClick = { state.cancelSql() },
                                shape = SundaysPalette.buttonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("停止")
                            }
                        } else {
                            WinButton(
                                onClick = {
                                    // 危险操作要**先确认再发**：判据是 [DangerousSql]，
                                    // 拦在引擎之前 —— 用户看到确认框时，数据库还什么都没发生。
                                    val findings = DangerousSql.scan(sheet.editor.text)
                                    if (findings.isEmpty()) {
                                        state.executeSql()
                                    } else {
                                        pendingDangerous = findings
                                    }
                                },
                                enabled = connected && !sheet.running && sheet.editor.text.isNotBlank(),
                                shape = SundaysPalette.buttonShape,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (sheet.running) "执行中…" else "执行 SQL")
                            }
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
                    // 截断必须**显式**说出来。静默截断等于骗人：用户会以为这张表就这么大，
                    // 而导出去的数据却是全的。措辞说清「收到多少」而不是「一共多少」——
                    // 后者我们并不知道，引擎没有回总行数。
                    if (sheet.rowsTruncated) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "已截断：只保留前 $SQL_RESULT_MAX_ROWS 行",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                    // 补全额外注入造数沙箱的宿主函数（`insert` / `lastId` / `random_*`）。
                    // 它们**只在这个沙箱里存在**，普通 Lua 编辑器里调用会报
                    // `attempt to call a nil value` —— 所以绝不能塞进 `LuaLanguage` 的
                    // BUILTINS，那会让所有 Lua 编辑器都开始推荐不存在的函数。
                    extraCompletions = GenerateHelpers.completions,
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
     * 本表的主键列名；`null` = **未知** ⇒ 该表不可编辑（见 [DatabaseBrowserState.isTableEditable]）。
     *
     * 之所以现在恒为 `null`：`DATA.LIST` 的响应里只有 `rows`，**没有列定义**，
     * 而 `ColumnDef.is_primary_key` 只在 `TABLE.CREATE` / `TABLE.UPDATE` 的请求侧出现。
     * 前端拿不到「哪一列是主键」这个信息。
     *
     * 填上它不需要改 `DataTable`、也不需要改 `updateCell` —— 只差引擎在
     * `DataListPagedResponse` 里多回一个 `primary_key` 字段。**宁可先不给编辑，
     * 也不能用行索引或「猜测的 id 列」去发 `UPDATE`** —— 后者会静默改错行。
     */
    var primaryKeyColumn: String? by mutableStateOf(null)

    /**
     * 过滤条件（`WHERE` 子句的**内容**，不含 `WHERE` 关键字）。
     *
     * 空串 = 不过滤。改动会让本页回到第 1 页 —— 在第 3 页上把过滤条件收紧，
     * 结果集可能只剩 2 页，停在第 3 页会看到一张空表。
     */
    var whereClause: String by mutableStateOf("")

    /**
     * 排序子句（`ORDER BY` 的**内容**，不含 `ORDER BY` 关键字）。空串 = 不排序。
     *
     * 引擎侧 `DataListRequest.order_by` 收的就是裸子串，并由 `DatabaseDialect.validateOrderBy`
     * 挡注入（`name; DROP TABLE users` 这类会被拒）—— 所以**不要**在前端再拼一次校验，
     * 两处校验规则会漂移。
     */
    var orderByClause: String by mutableStateOf("")

    /** 表内文本搜索词 —— 编译成 `WHERE` 里的 `LIKE '%词%'`（见 [applyTextSearch]）。 */
    var searchTerm: String by mutableStateOf("")

    /**
     * 把本 tab 的**生效过滤条件**算出来 —— 手工 `WHERE` 与表内搜索的合并。
     *
     * 两者都是 AND 关系：用户手填 `status = 'paid'` 又搜 `bob`，结果就是
     * `status = 'paid' AND name LIKE '%bob%'`。
     *
     * @return `null` 表示没有过滤条件（不传 `where`）。
     */
    fun effectiveWhere(): String? {
        val manual = whereClause.trim()
        val like = SqlLiterals.likeContains(searchTerm)
        return when {
            manual.isNotEmpty() && like != null -> "$manual AND $like"
            manual.isNotEmpty() -> manual
            else -> like
        }
    }

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
 * SQL 工作台补全候选的**缓存签名** —— 只取会影响候选内容的字段。
 *
 * 用途是作为 `remember` 的 key。Compose 的 `remember` 用 `equals`（结构相等，不是引用）
 * 比较 key，因此：
 * - [TablePreviewTab.columns] 变化（预览刚加载完）→ 签名变 → 候选重算 ✓
 * - [TablePreviewTab.rows] 变化（翻页 / 刷新数据）→ 签名**不变** → 不重算 ✓
 *
 * 第二条是这里唯一需要小心的点：`rows` 是真正的数据（可能上千行），一旦被牵进 key，
 * 每翻一页都会重建整份候选列表 —— 而候选内容根本没变。
 *
 * 同理**不能**直接用 `tabs` 当 key：双击打开表只改 `tab.columns`，`tabs` 列表引用不变，
 * `remember` 不会重跑，字段候选就永远出不来。
 */
internal fun schemaCompletionSignature(
    tabs: List<TablePreviewTab>,
): List<Triple<String, String, List<Pair<String, String>>>> = tabs.map { tab ->
    Triple(tab.schema, tab.tableName, tab.columns.map { it.key to it.header })
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

    // ------------------------------------------------------------------
    // 对象浏览：视图 / 触发器 / 函数（schema 级）与 索引 / 外键（表级）
    // ------------------------------------------------------------------

    /**
     * 库级对象的三种：**视图 / 触发器 / 过程·函数**。
     *
     * 引擎侧它们是**库级**的（`ViewListRequest.schema` / `TriggerListRequest.schema` /
     * `FunctionListRequest.schema` 都只要一个 schema，不像索引/外键还要 `table_name`），
     * 所以归在库节点下、跟表平级。
     *
     * 之前只列出「表」，是 DataGrip / Navicat 意义上的**对象浏览器缺失一半**：
     * 用户能看表却看不到视图和触发器，而这两类恰恰是排查「数据从哪来 / 会不会被改」时
     * 最先要看的东西。
     */
    enum class DatabaseObjectKind(val label: String) {
        VIEW("视图"),
        TRIGGER("触发器"),
        FUNCTION("过程 / 函数"),
    }

    /** database → 该类对象名列表（来自各自的 `*ListRequest`）。 */
    private val _objectsByDatabase = mutableStateMapOf<String, Map<DatabaseObjectKind, List<String>>>()

    val objectsByDatabase: Map<String, Map<DatabaseObjectKind, List<String>>>
        get() = _objectsByDatabase

    /** 正在加载库级对象的 `(库, 类型)` 组合 —— 控制那一行的小 spinner。 */
    val loadingObjects: SnapshotStateSet<Pair<String, DatabaseObjectKind>> = mutableStateSetOf()

    /** 库级对象加载错误：`"库::类型" -> 文案`。 */
    private val _objectLoadError = mutableStateMapOf<String, String>()
    val objectLoadError: Map<String, String> get() = _objectLoadError

    /** 表级对象的两种：**索引 / 外键**（引擎侧要 `table_name`，所以挂表节点下）。 */
    enum class TableObjectKind(val label: String) {
        INDEX("索引"),
        FOREIGN_KEY("外键"),
    }

    /** `"库::表" -> 该类对象名列表。 */
    private val _tableObjects = mutableStateMapOf<String, Map<TableObjectKind, List<String>>>()

    val tableObjects: Map<String, Map<TableObjectKind, List<String>>> get() = _tableObjects

    val loadingTableObjects: SnapshotStateSet<Pair<String, TableObjectKind>> = mutableStateSetOf()

    /** 已展开表对象列表的表：`"库::表"`。 */
    val expandedTableObjects: SnapshotStateSet<String> = mutableStateSetOf()

    /**
     * 展开 / 收起某张表的**索引 / 外键**。
     *
     * 与库级对象不同，这里**不缓存**：每张表的对象是「这张表的」，不存在跨表复用，
     * 而缓存会让「别处新建的索引看不到」。
     */
    fun toggleTableObjects(database: String, table: String) {
        val slot = "$database::$table"
        if (slot in expandedTableObjects) {
            expandedTableObjects.remove(slot)
        } else {
            expandedTableObjects.add(slot)
            loadTableObjects(database, table)
        }
    }

    /**
     * `库 -> 该库的 schema 名列表`（`SCHEMA.LIST level=schema` 的结果）。
     *
     * 单独存一份是为了**表级对象**（索引 / 外键）能直接取用：那两条路由同样要
     * schema 名，而它们是「每张表一次往返」，绝不能每张表都重新解析一遍 schema。
     */
    private val _schemasByDatabase = mutableStateMapOf<String, List<String>>()
    val schemasByDatabase: Map<String, List<String>> get() = _schemasByDatabase

    /**
     * 表级对象要用的 schema 名。空列表（还没解析 / 该库不支持 schema 层）时留空 ——
     * 多数方言把空串当作「默认 schema」，而 H2 方言的 `listViews` 明确要求 `PUBLIC`。
     */
    private val tableSchemaOrDefault: String
        get() = expandedDatabases.firstNotNullOfOrNull { _schemasByDatabase[it]?.firstOrNull() } ?: ""
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

        /**
         * 本轮执行的引擎 **request id** —— 「停止」按钮的凭据。
         *
         * 引擎侧 `SYSTEM.CANCEL` 按 `target_request_id` 找正在跑的 `Statement` 并调
         * `Statement.cancel()`（协程取消打断不了阻塞中的 JDBC 调用，只有它能）。
         * id 由 [DatabaseBrowserState.executeSql] 在构造请求时生成 —— 此前它生成完就被丢弃，
         * 前端**根本没法取消**一条跑了十分钟的查询。
         *
         * 生命周期：请求发出前写入，任何结束路径（成功 / 失败 / 异常 / 代次作废）都清空。
         * 非 null 是「可停止」的唯一判据，不另开一个 `cancellable` 标志 —— 两个字段必然漂移。
         */
        var runningRequestId: String? by mutableStateOf(null)

        /**
         * 是否按**多语句脚本**执行（`SqlExecuteRequest.multi_statement`）。
         *
         * 引擎侧的 `SqlScriptSplitter`（v2.16）只切**顶层** `;`，逐条执行、首个失败即停，
         * 并为每条单独登记 `Statement` 以便取消 —— 能力早就齐了，此前前端**硬编码
         * `multiStatement = false`** 让它完全用不上。
         *
         * 做成**逐 sheet 的开关**而不是全局默认开：多语句意味着「一次点执行会改多张表」，
         * 与「选中一段就跑这一段」的心智模型冲突，用户得能对某个脚本明确表态。
         */
        var multiStatement: Boolean by mutableStateOf(false)

        /**
         * 本 sheet 当前是否挂在一个**事务会话**里（`SYSTEM.BEGIN` 之后）。
         *
         * 事务会话在引擎侧给该 `session_id` **钉住一条连接**（`autocommit=false`），
         * 后续请求全落在这条连接上，直到 `COMMIT` / `ROLLBACK`。
         * 前端必须自己持有这个 `sessionId` —— 引擎不会把「你上次开的事务」记在某处。
         */
        var transactionSessionId: String? by mutableStateOf(null)

        /**
         * 结果集是否被 [SQL_RESULT_MAX_ROWS] 截断。
         *
         * 与 `rowCount` 分开存：截断时 `rowCount` 只是**已收到**的行数，
         * 把它当成总数会让分页栏显示「第 1 / 60 页」而点不出后面的页 —— 用户以为数据丢了。
         */
        var rowsTruncated: Boolean by mutableStateOf(false)

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
     * SQL 工作台补全用的 schema 候选 —— 库 → 表 → 字段，全部**来自已有状态，零额外请求**。
     *
     * ## 覆盖范围（与「不额外打扰数据库」这个前提绑定）
     *
     * | 类别 | 来源 | 何时有 |
     * |---|---|---|
     * | 库 | [databases]（`SCHEMA.LIST`） | 连接建立时 |
     * | 表 | [tablesByDatabase]（`TABLE.LIST`） | 展开该库时（复用树上的懒加载结果） |
     * | 字段 | [tabs] 里各 tab 的 `columns` | **打开过该表的预览**时 |
     *
     * 字段只覆盖「访问过的表」是刻意的：全库全表意味着对每张表发一次
     * `TABLE.COLUMN_LIST` —— 一个 200 张表的库就是 200 次串行往返，而用户在敲 `sel`
     * 时根本用不到其中 99%。这里选择零等待、零额外往返，用得越多越全。
     *
     * ## 排序：库 → 表 → 字段 → （语言关键字）
     *
     * 库/表/字段整体**优先于**关键字，因为 `FROM us` 想要的是 `users` 这张表而不是
     * `USING` 这个关键字。同类内部保持引擎返回的顺序，不擅自重排 —— 引擎的排序未必
     * 字典序，改了会让人对不上 `SHOW TABLES` 的结果。
     *
     * ## 去重
     *
     * 表名**不去重**：不同库可能有同名表，标签都是裸表名（SQL 里 `FROM t` 就是裸名），
     * 但 [CompletionItem.detail] 标出所属库 —— 两条都留着，用户能分辨。
     */
    fun sqlSchemaCompletions(): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()

        for (db in databases) {
            if (db.isNotBlank()) out.add(CompletionItem(db, CompletionKind.DATABASE, "库"))
        }
        for ((db, tables) in tablesByDatabase) {
            val tableHint = if (db.isBlank()) "表" else "$db · 表"
            for (t in tables) {
                if (t.isNotBlank()) out.add(CompletionItem(t, CompletionKind.TABLE, tableHint))
            }
        }
        for (tab in tabs) {
            if (tab.columns.isEmpty()) continue
            val tableLabel = if (tab.schema.isBlank()) tab.tableName else "${tab.schema}.${tab.tableName}"
            for (col in tab.columns) {
                val name = col.key
                if (name.isBlank()) continue
                out.add(
                    CompletionItem(
                        label = name,
                        kind = CompletionKind.COLUMN,
                        detail = if (col.header.isBlank()) tableLabel else "$tableLabel · ${col.header}",
                    ),
                )
            }
        }
        return out
    }

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
        // 只读模式先拦：拦在**发出去之前**，引擎那边什么都没发生，
        // 用户看到的是一句人话而不是引擎异常
        rejectIfReadOnly(sql)?.let {
            sheet.error = it
            return
        }
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
        sheet.rowsTruncated = false
        // 新一轮结果 → 视图归位（页码 / 选中行都是上一批数据的，指过去没有意义）
        sheet.resultPage = 1
        sheet.selectedRowId = null
        // request id **必须在 launch 之前**生成并落到 sheet 上，而不是在协程体内。
        //
        // 两个原因：① 「停止」按钮的可用性判据就是它，协程体内的赋值意味着
        // `executeSql()` 返回的那一刻它还是 null —— 用户点得再快也赶不上，表现为
        // 「刚点执行、停止按钮要等一会儿才冒出来」；② 引擎侧 `SYSTEM.CANCEL` 靠它找
        // 正在跑的 `Statement`，id 晚一步就可能晚于请求真正开始的那一步。
        val requestId = UUID.randomUUID().toString()
        sheet.runningRequestId = requestId
        scope.launch {
            val schema = currentSchema()
            // 本请求在 catalog = schema 维度上建池（schema 字段本身留空，见下方注释），
            // 因此登记进 activeDatabases —— 否则 close-sheet 的 releasePools 遍历不到它，
            // 池会一直挂着到 JVM 退出。
            activeDatabases.add(schema)
            val result = runCatching {
                engine.handle(
                    request {
                        id = requestId
                        this.connection = engineConn(database = schema)
                        // 事务会话：非空时引擎把本次执行钉在 [beginTransaction] 开的那条
                        // 连接上（`autocommit=false`），COMMIT / ROLLBACK 之前不会落盘。
                        // 留空 = 无事务，每条语句独立提交（原有行为）。
                        sessionId = sheet.transactionSessionId.orEmpty()
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
                                // 逐 sheet 的开关（见 [SqlSheet.multiStatement]）。引擎侧
                                // `SqlScriptSplitter` 只切顶层 `;`，引号 / 注释 / PG 美元引用里
                                // 的分号不会被误切。
                                multiStatement = sheet.multiStatement
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
                sheet.runningRequestId = null
                return@launch
            }
            val frames = mutableListOf<com.kxxnzstdsw.grpc.SqlSelectRowFrame>()
            var truncated = false
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
                        // 攒够上限就停：**继续收只会在堆里再压一份同样的数据**，
                        // 引擎侧仍会推完（已发的帧收不回来），但我们不再留。
                        if (frames.size >= SQL_RESULT_MAX_ROWS) {
                            truncated = true
                            cancelled = true
                            return@collect
                        }
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
                    sheet.runningRequestId = null
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
                sheet.rowsTruncated = truncated
            } else if (sheet.affectedRows == null && sheet.error == null) {
                sheet.error = "无返回结果"
            }
            sheet.running = false
            sheet.runningRequestId = null
        }
    }

    /**
     * 停止当前 sheet 正在跑的 SQL —— 发 `SYSTEM.CANCEL`（v2.16 已有，此前前端从未接上）。
     *
     * ## 为什么「停止」必须走引擎而不能只取消协程
     *
     * 跑在 `Dispatchers.IO` 上的 `rs.next()` 即使协程被取消也**继续阻塞** ——
     * 真正能停掉数据库侧工作的只有 `Statement.cancel()`，那正是 `StatementRegistry`
     * 在引擎侧做的事。前端取消协程的结果是：转圈没了，数据库还在满负荷跑。
     *
     * 取消成功后，引擎会在**原 request id** 上回一帧 `success=false, error="cancelled"`，
     * 由 [executeSql] 的 collect 落进 [SqlSheet.error] —— 因此这里**不**自己写状态，
     * 避免两条路径各写一次、出现「显示已停止但引擎还在跑」或反之。
     */
    fun cancelSql() {
        val sheet = currentSqlSheet() ?: return
        val requestId = sheet.runningRequestId ?: return
        val schema = currentSchema()
        scope.launch {
            val resp = runCatching {
                engine.invoke(engineConn(database = schema)) {
                    category = Category.SYSTEM
                    action = Action.CANCEL
                    systemRequest = systemRequest { targetRequestId = requestId }
                }
            }
            // 只在**没找到目标**时补一句提示：请求已经自己结束时引擎会回 cancelled=false，
            // 那不是错误，那正是「已经跑完了」。真正失败（gRPC 断了等）才写 error。
            val cancelResp = resp.getOrNull()?.system?.cancel
            if (resp.isFailure) {
                sheet.error = "无法停止：${resp.exceptionOrNull()?.message ?: "未知错误"}"
            } else if (cancelResp != null && !cancelResp.cancelled && cancelResp.error.isNotBlank()) {
                sheet.error = "无法停止：${cancelResp.error}"
            }
        }
    }

    // ------------------------------------------------------------------------
    // 数据编辑（表预览）
    // ------------------------------------------------------------------------

    /**
     * 该表预览是否**可编辑** —— 当前一律 `false`。
     *
     * ## 为什么默认关着（这是本轮最重要的一条判断）
     *
     * [DataTable] 的编辑需要一个能**唯一定位行**的 `where` 条件。浏览屏拿不到主键：
     * `DATA.LIST` 的 `DataListPagedResponse` 只回 `rows`、**不回列定义**，而
     * `ColumnDef.is_primary_key` 只出现在 `TABLE.CREATE` / `TABLE.UPDATE` 里 ——
     * 前端无从知道哪一列是主键。
     *
     * 唯一能立刻拿到的候选是 `id` 列（[TablePreviewTab] 的行 id 就取自它），但
     * **「有 id 列」不等于「id 是主键」**：一张表的 `id` 完全可能只是个普通可重复列。
     * 那时发出去的 `WHERE id = ?` 会**同时改掉多行** —— 静默的数据损坏，
     * 比「不能编辑」严重得多。
     *
     * 所以这里**明确关着**，而不是「先能用着」。解锁条件是引擎在 `DATA.LIST` 响应里
     * 回主键列名（见 [TablePreviewTab.primaryKeyColumn]），届时只需把它接上，
     * `DataTable` 与本方法都不必改。
     *
     * 引擎侧的 `DATA.UPDATE` / `DATA.CREATE` / `DATA.DELETE` **早已实现**且是参数化的
     * （`map<string, string>`，没有注入面），缺的只是这一个主键信息。
     */
    fun isTableEditable(tab: TablePreviewTab): Boolean = tab.primaryKeyColumn != null

    /**
     * 改一个单元格 —— 发 `DATA.UPDATE`（参数化，无注入面）。
     *
     * @return 失败时返回引擎的错误文案（供 UI 显示）；成功返回 `null`。
     *
     * **调用方必须先校验** [isTableEditable]：这里 `where` 为空 map 时，引擎会把它当成
     * 「无条件更新」，等于改写整张表。
     */
    suspend fun updateCell(tab: TablePreviewTab, edit: CellEdit): String? {
        val pk = tab.primaryKeyColumn ?: return "该表没有可用主键，编辑已禁用"
        val row = tab.rows.firstOrNull { it.id == edit.rowId } ?: return "目标行已不在当前页"
        val old = row.cells[pk]?.toString() ?: return "主键 $pk 不在行数据里"

        val catalog = tab.schema.ifBlank { currentSchema() }
        activeDatabases.add(catalog)
        // 先在**内层 DSL 之外**把 update 请求构造好。
        //
        // 为什么不直接内联：`dataUpdateRequest { }` 的 lambda 接收者会被外层
        // `dataRequest { }` 的同名兄弟字段（`schema` / `where` / `tableName`）遮蔽 ——
        // 内层写 `schema = ""` 会解析到外层去，编译报「val cannot be reassigned」。
        // Kotlin 的内联 lambda 没有标签可用来消歧（`this@xxx` 对内联 lambda 不可用），
        // 所以把构造提到外面是唯一干净的做法。
        val updateReq = com.kxxnzstdsw.grpc.dataUpdateRequest {
            tableName = tab.tableName
            // catalog 走 engineConnFor 的 database 维度，schema 字段留空
            // —— 写进去会让 H2 执行 `SET SCHEMA "<库名>"` 而报「Schema not found」，
            // 见 engineConnFor 的 KDoc。
            //
            // 局部变量刻意叫 `catalog` 而不是 `schema`：同名的话这里的 `schema = ""`
            // 会解析到那个 val 上（仍是「val cannot be reassigned」），只是换了张脸。
            schema = ""
            // map 字段用 `put(k, v)`（DslMap 的扩展函数），不是 `=` 赋值
            changes.put(edit.columnKey, edit.newValue)
            where.put(pk, old)
        }
        val result = runCatching {
            engine.invoke(engineConnFor(currentConnection, catalog)) {
                category = Category.DATA
                action = Action.UPDATE
                dataRequest = dataRequest { update = updateReq }
            }
        }
        val resp = result.getOrNull() ?: return result.exceptionOrNull()?.message ?: "更新失败"
        if (!resp.success) return resp.error.ifBlank { "更新失败" }
        // 本地也改一份：否则用户改完看到还是旧值，会以为没生效而再改一次。
        tab.rows = tab.rows.map { r ->
            if (r.id != edit.rowId) r else r.copy(cells = r.cells + (edit.columnKey to edit.newValue))
        }
        return null
    }

    /**
     * **只读模式** —— 开着时本屏发往引擎的一切写请求一律拒绝。
     *
     * 只在 UI 层拦、不动引擎：这是**用户自己**的开关，出错时立刻能看见「只读模式下
     * 写操作被拒绝」而不是一个引擎异常。真正需要强制只读（防止别的客户端写）时
     * 应该在连接串上用只读账号。
     *
     * 挡的是「已经发出去之前」，所以代价为零 —— 引擎那边什么都没发生。
     */
    var readOnly: Boolean by mutableStateOf(false)

    /**
     * 检查一次 SQL 在当前只读模式下能不能发 —— 不能时返回该给用户看的文案。
     *
     * @return `null` = 可以发。
     */
    fun rejectIfReadOnly(sql: String): String? {
        if (!readOnly) return null
        val writes = DangerousSql.scan(sql)
        if (writes.isEmpty()) return null
        return "只读模式下不允许写操作，已拦下：${writes.joinToString("；") { it.statement }}"
    }

    // ------------------------------------------------------------------------
    // 事务会话（v2.16 引擎能力，此前前端未接）
    // ------------------------------------------------------------------------

    /**
     * 开一个事务会话 —— 发 `SYSTEM.BEGIN`，返回 `session_id`。
     *
     * 引擎侧给该 id **钉住一条 JDBC 连接**（`autocommit=false`），此后这个 sheet 的
     * 每次执行都带 `session_id`，全部落在这条连接上，直到 `COMMIT` / `ROLLBACK`。
     *
     * @return 失败时返回错误文案；成功返回 `null`（session id 已写进 [SqlSheet.transactionSessionId]）。
     */
    suspend fun beginTransaction(sheet: DatabaseBrowserState.SqlSheet): String? {
        if (sheet.transactionSessionId != null) return "该 sheet 已在事务中"
        val resp = runCatching {
            engine.invoke(engineConn(database = currentSchema())) {
                category = Category.SYSTEM
                action = Action.BEGIN
                systemRequest = systemRequest { sessionId = UUID.randomUUID().toString() }
            }
        }.getOrNull() ?: return "开启事务失败：引擎无响应"
        if (!resp.success) return resp.error.ifBlank { "开启事务失败" }
        val body = resp.system
        // 引擎回显 session id；拿不到就当失败 —— 前端**必须**持有它，
        // 后续请求靠它找到那条被钉住的连接，拿不到等于事务没开。
        val sid = body?.begin?.sessionId.orEmpty()
        if (sid.isBlank()) return "引擎未返回 session id，事务未开启"
        sheet.transactionSessionId = sid
        return null
    }

    /** 提交事务 —— 发 `SYSTEM.COMMIT`。 */
    suspend fun commitTransaction(sheet: DatabaseBrowserState.SqlSheet): String? =
        endTransaction(sheet, Action.COMMIT, "提交事务失败")

    /** 回滚事务 —— 发 `SYSTEM.ROLLBACK`。 */
    suspend fun rollbackTransaction(sheet: DatabaseBrowserState.SqlSheet): String? =
        endTransaction(sheet, Action.ROLLBACK, "回滚事务失败")

    private suspend fun endTransaction(
        sheet: DatabaseBrowserState.SqlSheet,
        action: com.kxxnzstdsw.grpc.Action,
        fallback: String,
    ): String? {
        val sid = sheet.transactionSessionId ?: return "当前不在事务中"
        val resp = runCatching {
            engine.invoke(engineConn(database = currentSchema())) {
                category = Category.SYSTEM
                this.action = action
                systemRequest = systemRequest { sessionId = sid }
            }
        }.getOrNull() ?: return "$fallback：引擎无响应"
        if (!resp.success) return resp.error.ifBlank { fallback }
        // 无论引擎报成功与否都清本地 id：`SYSTEM.ROLLBACK` 失败时那条连接仍被钉着，
        // 但用户已经看到错误并会重试 —— 留着 id 会让「提交」按钮一直亮着，
        // 而下一次点击会打到一条引擎已经不认识的会话上。
        sheet.transactionSessionId = null
        return null
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
            // 断开后请求已经作废，留着 id 会让「停止」按钮继续挂在界面上 ——
            // 而那个 id 对应的引擎侧执行早已不存在，点了只会回一句「找不到目标」。
            sheet.runningRequestId = null
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
            // 连接已切换 —— 丢弃过期响应。
            //
            // ⚠️ **这里不补 `loadingDatabases = false` 是有意的，不要「顺手修好」**：
            // `generation` 只由 [invalidateInFlight] 递增，而它**正在**清 `loadingDatabases`；
            // 断开后新连接会立刻重新调用 [refreshDatabases] 并自己清。
            // 在这里清会把**新**连接刚开始的转圈一并抹掉 —— 用户看到的是
            // 「库列表空着、又不转圈」，比转圈更让人以为已经加载完。
            if (requestGeneration != generation) return@launch
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
            // 库级对象与表**并发**拉：它们互不依赖，串行会让展开一棵库慢一倍。
            //
            // 每次展开都重新拉，不做「拉过就跳过」：用户在**别处**（SQL 工作台、造数）
            // 建的视图 / 触发器，这里若不重拉就永远看不到 —— 而「树里看不到刚建的
            // 对象」正是用户最不能接受的一种「列表不更新」。展开是低频动作，
            // 多三次往返换来正确性，划算。
            loadDatabaseObjects(name)
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
            // 同 [refreshDatabases]：早退**不**清 `loadingTables`（理由见那里的注释），
            // 清理点同样只有 [invalidateInFlight] 的 `loadingTables.clear()`
            if (requestGeneration != generation) return@launch
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
     * 拉某个库的**库级对象**（视图 / 触发器 / 过程·函数）。
     *
     * ## 先解析 schema 名，再拉对象 —— 不能拿库名凑
     *
     * `VIEW.LIST` / `TRIGGER.LIST` / `FUNCTION.LIST` 的 `schema` 参数要的是
     * **schema 名**（H2 恒为 `PUBLIC`、PG 为 `public`、SQLite 为 `main`），
     * 而我们手上只有**库名**（catalog）—— 两者不是一回事，这正是 [engineConnFor]
     * 的 KDoc 反复警告的那条：把库名塞进 `schema` 会让 H2 执行
     * `SET SCHEMA "<库名>"` → `Schema not found`。
     *
     * 所以先走一次 `SCHEMA.LIST level=schema` 拿到**真实的 schema 列表**，再对每个
     * schema 拉三类对象。多数库只有一个默认 schema，代价是多一次往返；
     * 换来的是「不靠猜」—— 前端没有任何地方硬编码 `PUBLIC` 之类。
     *
     * @param resolvedSchemas 由 [loadSchemasOf] 解析出的 schema 名；为空表示还没解析完
     */
    private suspend fun loadDatabaseObjectsFor(
        database: String,
        resolvedSchemas: List<String>,
    ) {
        for (kind in DatabaseObjectKind.entries) {
            loadingObjects += database to kind
            scope.launch {
                val names = mutableListOf<String>()
                var failure: String? = null
                // 一个 schema 失败不该让其他 schema 的同类对象也消失
                for (s in resolvedSchemas) {
                    val resp = runCatching {
                        engine.invoke(engineConn(database = database)) {
                            category = when (kind) {
                                DatabaseObjectKind.VIEW -> Category.VIEW
                                DatabaseObjectKind.TRIGGER -> Category.TRIGGER
                                DatabaseObjectKind.FUNCTION -> Category.FUNCTION
                            }
                            action = Action.LIST
                            when (kind) {
                                DatabaseObjectKind.VIEW ->
                                    viewRequest = viewRequest { list = viewListRequest { schema = s } }
                                DatabaseObjectKind.TRIGGER ->
                                    triggerRequest = triggerRequest { list = triggerListRequest { schema = s } }
                                DatabaseObjectKind.FUNCTION ->
                                    functionRequest = functionRequest {
                                        list = functionListRequest { schema = s }
                                    }
                            }
                        }
                    }.getOrNull()
                    when {
                        resp == null -> failure = failure ?: "引擎无响应"
                        !resp.success ->
                            failure = failure ?: resp.error.ifBlank { "加载失败" }
                        else -> {
                            // 五类的 list 元素都是消息（视图带 definition、触发器带
                            // timing/event 等），树里只显示 `.name`；其余字段留给
                            // 将来的对象详情面板。
                            names += when (kind) {
                                DatabaseObjectKind.VIEW -> resp.view.list.itemsList.map { it.name }
                                DatabaseObjectKind.TRIGGER -> resp.trigger.list.itemsList.map { it.name }
                                DatabaseObjectKind.FUNCTION -> resp.function.list.itemsList.map { it.name }
                            }
                        }
                    }
                }
                // 先写数据、最后清 loading —— 与 refreshDatabases 同一个顺序约定
                if (failure != null) {
                    _objectLoadError["$database::${kind.name}"] = failure
                } else {
                    _objectsByDatabase[database] =
                        (_objectsByDatabase[database] ?: emptyMap())
                            .plus(kind to names.filter { it.isNotBlank() }.distinct())
                }
                loadingObjects.remove(database to kind)
            }
        }
    }

    /**
     * 解析某个库下的 schema 名，然后拉它的库级对象。
     *
     * `SCHEMA.LIST level=schema` 在 **MySQL 上抛 `UnsupportedOperationException`**
     * （MySQL 的 database 与 schema 合一，没有这层概念）。那种情况下对象列表
     * **本来就无从谈起**，于是走 `emptyList()` 跳过并把 loading 清掉 ——
     * 界面显示「(无)」而不是永远转圈。
     */
    fun loadDatabaseObjects(database: String) {
        for (kind in DatabaseObjectKind.entries) {
            loadingObjects += database to kind
        }
        scope.launch {
            val schemas = runCatching {
                engine.invoke(engineConn(database = database)) {
                    category = Category.SCHEMA
                    action = Action.LIST
                    schemaRequest = schemaRequest {
                        list = schemaListRequest {
                            level = "schema"
                            this.database = database
                        }
                    }
                }
            }.getOrNull()?.takeIf { it.success }?.schema?.list?.itemsList.orEmpty()
            // 存下来给表级对象（索引 / 外键）复用 —— 那是「每张表一次往返」，
            // 不能每张表都重新解析一遍 schema
            _schemasByDatabase[database] = schemas

            // 先撤掉占位：真正的 loading 由 loadDatabaseObjectsFor 重新置上，
            // 否则「解析 schema 期间」和「拉对象期间」之间会有一段假转圈
            DatabaseObjectKind.entries.forEach { loadingObjects.remove(database to it) }
            if (schemas.isEmpty()) {
                // 写一份空结果：界面显示「(无)」，不再反复重试
                DatabaseObjectKind.entries.forEach { k ->
                    _objectsByDatabase[database] =
                        (_objectsByDatabase[database] ?: emptyMap()).plus(k to emptyList<String>())
                }
                return@launch
            }
            loadDatabaseObjectsFor(database, schemas)
        }
    }

    /**
     * 拉某张表的**表级对象**（索引 / 外键）。
     *
     * 引擎侧这两类要 `table_name`（`IndexListRequest` / `ForeignKeyListRequest`），
     * 所以不能跟库级对象一起拉 —— 那是「每张表一次往返」，一张几十列的库直接爆炸。
     * 代价是它们只在**单独展开表节点时**才可见，这也是 DataGrip 的做法。
     */
    fun loadTableObjects(database: String, table: String) {
        val slot = "$database::$table"
        // schema 还没解析（用户没展开过这个库，或直接点了表）时先解析一次
        if (_schemasByDatabase[database] == null) loadDatabaseObjects(database)
        for (kind in TableObjectKind.entries) {
            loadingTableObjects += slot to kind
            scope.launch {
                val resp = runCatching {
                    engine.invoke(engineConn(database = database)) {
                        category = when (kind) {
                            TableObjectKind.INDEX -> Category.INDEX
                            TableObjectKind.FOREIGN_KEY -> Category.FOREIGN_KEY
                        }
                        action = Action.LIST
                        when (kind) {
                            TableObjectKind.INDEX -> indexRequest = indexRequest {
                                list = indexListRequest {
                                    tableName = table
                                    schema = tableSchemaOrDefault
                                }
                            }
                            TableObjectKind.FOREIGN_KEY -> foreignKeyRequest = foreignKeyRequest {
                                list = foreignKeyListRequest {
                                    tableName = table
                                    schema = tableSchemaOrDefault
                                }
                            }
                        }
                    }
                }.getOrNull()
                if (resp != null && resp.success) {
                    val names = when (kind) {
                        // 索引 / 外键的 list 元素是消息（带 columns / ref_table 等），
                        // 树里只显示名字 —— 其余字段留给将来的对象详情面板。
                        TableObjectKind.INDEX -> resp.index.list.itemsList.map { it.name }
                        TableObjectKind.FOREIGN_KEY -> resp.foreignKey.list.itemsList.map { it.name }
                    }
                    _tableObjects[slot] = (_tableObjects[slot] ?: emptyMap()).plus(kind to names)
                }
                loadingTableObjects.remove(slot to kind)
            }
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
     * 改过滤 / 排序条件 —— **必须回到第 1 页**。
     *
     * 在第 3 页把过滤条件收紧，结果集可能只剩 2 页；停在第 3 页用户看到的是
     * 一张**空表**，而引擎没报错、总数也变了 —— 那是最难自查的一种「看起来坏了」。
     */
    fun setTabFilter(tab: TablePreviewTab, where: String) {
        if (tab.whereClause == where) return
        tab.whereClause = where
        tab.page = 1
        loadTabPreview(tab, targetPage = 1)
    }

    fun setTabOrderBy(tab: TablePreviewTab, orderBy: String) {
        if (tab.orderByClause == orderBy) return
        tab.orderByClause = orderBy
        tab.page = 1
        loadTabPreview(tab, targetPage = 1)
    }

    fun setTabSearch(tab: TablePreviewTab, term: String) {
        if (tab.searchTerm == term) return
        tab.searchTerm = term
        tab.page = 1
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
                            // 过滤与排序**全部交给引擎**：`where` / `order_by` 是裸 SQL 片段，
                            // 引擎侧 `DatabaseDialect.validateOrderBy` 负责挡排序注入。
                            // 前端不在本地过滤 —— 那样只能筛当前页，翻页后结果就不对了。
                            where = tab.effectiveWhere().orEmpty()
                            orderBy = tab.orderByClause.trim()
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