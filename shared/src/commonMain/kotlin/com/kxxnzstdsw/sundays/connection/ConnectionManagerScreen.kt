package com.kxxnzstdsw.sundays.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.ui.SettingsEntryButton
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinMenuItem
import com.kxxnzstdsw.sundays.ui.WinProgressIndicator
import com.kxxnzstdsw.sundays.ui.selectionIndicatorColors
import com.kxxnzstdsw.sundays.ui.uiBevel
import com.kxxnzstdsw.sundays.ui.WinOutlinedButton
import com.kxxnzstdsw.sundays.ui.WinTextButton
import com.kxxnzstdsw.sundays.ui.WinTextField
import com.kxxnzstdsw.sundays.ui.isClassicChrome
import com.kxxnzstdsw.sundays.ui.WinIconButton
import com.kxxnzstdsw.sundays.ui.winShape
import com.kxxnzstdsw.sundays.ui.selectionContainerColor
import com.kxxnzstdsw.sundays.ui.selectionContentColor
import kotlinx.coroutines.launch

/**
 * 连接管理器主界面 (v2.12).
 *
 * 布局: 左侧连接列表 + 右侧连接信息引导页面 / 选中连接总览
 *
 * ## 引导流程
 *
 * | 流程 | 步骤序列 | 总步骤数 |
 * |---|---|---|
 * | 普通新建 | BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE | 4 |
 * | 快速连接 | QUICK_CONNECT → CREDENTIALS → TEST_SAVE | 3 |
 * | 编辑已有 | BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE | 4 |
 *
 * 调用方维护向导状态（三字段原子更新的容器：`editingConnection` + `step` + `flow`），
 * 进入不同入口时**同步**设置 flow：
 *
 * ```kotlin
 * var wizardState by remember {
 *     mutableStateOf(WizardState(editingConnection = null, step = WizardStep.IDLE, flow = WizardFlow.NORMAL))
 * }
 *
 * ConnectionManagerScreen(
 *     connections = connectionList.connections,
 *     editingConnection = wizardState.editingConnection,
 *     wizardStep = wizardState.step,
 *     wizardFlow = wizardState.flow,
 *     onSelectConnection = { ... },
 *     onNewConnection = { wizardState = WizardState(newCfg, WizardStep.BASIC_INFO, WizardFlow.NORMAL) },
 *     onQuickConnect = { wizardState = WizardState(newCfg, WizardStep.QUICK_CONNECT, WizardFlow.QUICK_CONNECT) },
 *     onEditConnection = { conn -> wizardState = WizardState(conn, WizardStep.BASIC_INFO, WizardFlow.NORMAL) },
 * )
 * ```
 *
 * ## 引擎耦合点（组件本身不依赖引擎）
 *
 * | 关注点 | 注入方式 |
 * |---|---|
 * | 测试连接 | [onTestConnection] —— 调用方直连 `IdbEngine.testConnection` |
 * | 连接 / 断开 | [onConnect] / [onDisconnect] —— 调用方直连 `IdbEngine.testConnection` / `IdbEngine.disconnect` |
 * | 连接状态 | [connectionStatuses] —— 调用方从引擎侧维护后回传（列表项状态点 + 总览面板） |
 *
 * URL 真相源：[buildJdbcUrl] / [parseJdbcUrl] 负责字段 ↔ JDBC URL 双向折算，覆盖全部方言与连接类型；
 * 组件内的所有编辑都通过 [onUpdateEditingConnection] 回写调用方，调用方持有唯一真相
 * （不回写则向导内的输入丢失）。
 *
 * @param connections 所有保存的连接配置
 * @param selectedConnection 当前选中的连接 (用于左侧列表高亮 + 总览面板)
 * @param editingConnection 当前正在编辑的连接 (null = 未编辑)
 * @param wizardStep 当前引导步骤
 * @param wizardFlow 当前引导流程类型 (用于步骤指示器自适应)
 * @param connectionStatuses 各连接 (按 id) 的引擎侧会话状态；缺省视为未连接
 * @param onSelectConnection 选择连接
 * @param onNewConnection 新建连接回调 (普通流程)
 * @param onQuickConnect 快速连接回调
 * @param onEditConnection 编辑已有连接回调
 * @param onSaveConnection 保存连接 —— 普通流程末步「保存」直接调用；快速连接末步走 [onQuickConnectDirect]
 * @param onQuickConnectDirect 快速连接（不保存到 ConnectionStorage，由调用方直接连库）
 * @param onTestConnection 测试连接回调 —— 由调用方直连引擎实现（如 `IdbEngine.testConnection`）；
 *   null 时“测试连接”按钮禁用
 * @param onConnect 建立连接回调（总览面板「连接」按钮）
 * @param onDisconnect 断开连接回调（总览面板「断开」按钮）
 * @param onDeleteConnection 删除连接
 * @param onCancelEdit 取消编辑/向导（回到空闲态）
 * @param onWizardNext 前进到指定步骤
 * @param onWizardBack 回退一步（按当前 flow 计算）
 * @param onUpdateEditingConnection 编辑回写 —— 向导内所有字段变更都经此回调写回调用方状态
 * @param modifier Compose modifier
 */
@Composable
fun ConnectionManagerScreen(
    connections: List<ConnectionConfig>,
    selectedConnection: ConnectionConfig?,
    editingConnection: ConnectionConfig?,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    onSelectConnection: (ConnectionConfig?) -> Unit,
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
    onEditConnection: (ConnectionConfig) -> Unit,
    onSaveConnection: (ConnectionConfig) -> Unit,
    onQuickConnectDirect: (ConnectionConfig) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,
    connectionStatuses: Map<String, ConnectionStatus> = emptyMap(),
    onConnect: (ConnectionConfig) -> Unit = {},
    onDisconnect: (ConnectionConfig) -> Unit = {},
    // 嵌在模态弹窗（AddConnectionDialog）里时为 true —— 弹窗中不渲染 ⚙ 设置入口：
    // 模态弹窗里跳去改应用级设置会让人失去「当前处于什么界面」的判断。
    isEmbeddedInDialog: Boolean = false,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    /**
     * 待确认删除的连接 —— **非 null 时只弹框，不删**。
     *
     * ## 为什么必须有这一步
     *
     * 删除连接**立刻**从 `connection.json` 落盘移除，而没有撤销、没有回收站：
     * 一次误按就是配置连口令一起没了，用户只能重新把主机 / 端口 / 用户 / 密码打一遍。
     * 更糟的是这个界面**可以纯键盘操作**（Tab 到连接项 → Tab 到「删除」→ Enter），
     * 而焦点停在按钮上时 Enter 的语义就是「点它」—— 我在真窗口走查时正是这样
     * 一路 Tab 过去、按了一下回车，连接就没了，界面上没有任何提示。
     *
     * ## 为什么收在**这里**而不是两个入口各改一遍
     *
     * 删除有两个入口：列表项的 ⋮ 菜单与总览面板的「删除」按钮。收在组件内部
     * （`requestDelete` 统一转成 `pendingDelete`）才能保证**新增入口自动也被拦住**；
     * 分别改的话，下一个人加第三个入口时又会漏。
     *
     * 模式与 [com.kxxnzstdsw.sundays.ui.DangerousSql] 的危险 SQL 确认框同源：
     * 「先记下待办，弹框让用户明确点头，再真执行」。
     */
    var pendingDelete by remember { mutableStateOf<ConnectionConfig?>(null) }
    val requestDelete: (String) -> Unit = { id ->
        // 列表已经变了（被别处删掉）就静默放弃 —— 弹一个空框只会让人莫名其妙
        connections.firstOrNull { it.id == id }?.let { pendingDelete = it }
    }

    Row(modifier = modifier.fillMaxHeight()) {
        // 左侧: 连接列表
        ConnectionListPanel(
            connections = connections,
            selectedConnection = selectedConnection,
            connectionStatuses = connectionStatuses,
            onSelectConnection = onSelectConnection,
            onNewConnection = onNewConnection,
            onQuickConnect = onQuickConnect,
            onEditConnection = onEditConnection,
            onDeleteConnection = requestDelete,
            isEmbeddedInDialog = isEmbeddedInDialog,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .width(250.dp)
                .fillMaxHeight(),
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // 右侧: 连接总览 / 连接信息引导页面 —— 最小宽度 280 dp（与左侧已保存列表对齐），
// 实际宽度随父容器扩展（wizard 内字段过多时由自身 verticalScroll 处理），
// 布局视觉左右对称但右侧可呼吸。
        ConnectionWizardPanel(
            editingConnection = editingConnection,
            selectedConnection = selectedConnection,
            connectionStatus = selectedConnection?.let { connectionStatuses[it.id] } ?: ConnectionStatus(),
            wizardStep = wizardStep,
            wizardFlow = wizardFlow,
            onSaveConnection = onSaveConnection,
            onQuickConnectDirect = onQuickConnectDirect,
            onCancelEdit = onCancelEdit,
            onWizardNext = onWizardNext,
            onWizardBack = onWizardBack,
            onUpdateEditingConnection = onUpdateEditingConnection,
            onNewConnection = onNewConnection,
            onQuickConnect = onQuickConnect,
            onTestConnection = onTestConnection,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onEditConnection = onEditConnection,
            onDeleteConnection = requestDelete,
            modifier = Modifier
                .widthIn(min = 350.dp)
                .fillMaxHeight(),
        )
    }

    // 确认框渲染在 Row **之外**：它是模态浮层，与左右两栏的布局无关。
    pendingDelete?.let { target ->
        DeleteConnectionConfirmDialog(
            connection = target,
            onConfirm = {
                pendingDelete = null
                onDeleteConnection(target.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 删除连接的二次确认框。
 *
 * ## 文案要点
 *
 * - **点名道姓**：只说「确定要删除吗」太轻 —— 列表里有好几个连接时用户分不清删的是哪个，
 *   所以把连接名直接写进正文。
 * - **说清不可逆**：明确写出「不会断开数据库、也不会删库里的数据，但连接配置（含口令）
 *   会从本地删掉且无法撤销」。用户真正怕的往往是「会不会把库删了」，这句话把边界划清楚，
 *   免得他为了保险反而不敢删。
 *
 * ## 按钮顺序与默认焦点
 *
 * M3 `AlertDialog` 的 `confirmButton` 在 `dismissButton` **之后**渲染，因此 Tab 序是
 * 「取消 → 删除」—— 第一个停靠点是撤销方向。删除按钮用 `error` 配色，让它即便在 Tab 序末尾
 * 也一眼可辨。
 */
@Composable
private fun DeleteConnectionConfirmDialog(
    connection: ConnectionConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除连接") },
        text = {
            Column {
                Text("确定要删除连接「${connection.name}」吗？")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "将删除的是本地连接配置（含保存的口令），无法撤销。\n" +
                        "不会断开数据库，也不会删除库中的任何数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            WinTextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            WinButton(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = SundaysPalette.buttonShape,
            ) { Text("删除") }
        },
    )
}

/** 引擎侧连接会话状态 */
enum class ConnectionState {
    /** 未建立连接（无连接池） */
    DISCONNECTED,

    /** 正在建池 / 校验 */
    CONNECTING,

    /** 连接池已就绪且校验通过 */
    CONNECTED,

    /** 建池或校验失败 */
    FAILED,
}

/**
 * 单个连接的会话状态 + 说明（失败原因 / 成功时报告的方言驱动名）。
 *
 * 由集成层维护（`IdbEngine.testConnection` / `disconnect` 的结果），组件只做展示。
 */
data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val message: String = "",
)

/** 引导步骤 */
enum class WizardStep {
    IDLE,           // 空闲状态 (未编辑)
    QUICK_CONNECT,  // 快速连接：选方言 (跳过 BASIC_INFO / CONNECTION_TYPE)
    BASIC_INFO,     // 普通流程：基础信息 (名称 + 方言选择)
    CONNECTION_TYPE, // 普通流程：连接类型 (CLIENT_SERVER / EMBEDDED / FILE_BASED / IN_MEMORY)
    CREDENTIALS,    // 认证信息 (主机/端口/用户名/密码 或 文件路径)
    TEST_SAVE,      // 测试连接 & 保存
}

/** 当前引导流程类型 (用于步骤指示器自适应) */
enum class WizardFlow { QUICK_CONNECT, NORMAL }

/** 当前流程的总步骤数 (用于步骤指示器自适应) */
private fun totalStepsFor(flow: WizardFlow): Int = when (flow) {
    WizardFlow.QUICK_CONNECT -> 3   // 快速连接：选方言 → 凭据 → 测试保存
    WizardFlow.NORMAL -> 4          // 普通：基础 → 类型 → 凭据 → 测试保存
}

/**
 * 左侧连接列表面板.
 */
@Composable
private fun ConnectionListPanel(
    connections: List<ConnectionConfig>,
    selectedConnection: ConnectionConfig?,
    connectionStatuses: Map<String, ConnectionStatus>,
    onSelectConnection: (ConnectionConfig?) -> Unit,
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
    onEditConnection: (ConnectionConfig) -> Unit,
    onDeleteConnection: (String) -> Unit,
    isEmbeddedInDialog: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        // 标题 + 新建/快速连接按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "连接列表",
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                WinIconButton(onClick = onQuickConnect) {
                    Icon(Icons.Default.Bolt, contentDescription = "快速连接")
                }
                WinIconButton(onClick = onNewConnection) {
                    Icon(Icons.Default.Add, contentDescription = "新建连接")
                }
                // 只留设置入口（⚙）。日夜切换**已从本屏移除** —— 明暗改到设置页的
                // 「个性化 → 明暗档位」单选组里，标题行不再放应用级外观按钮。
                // 嵌在弹窗里时连设置入口也不渲染。
                if (!isEmbeddedInDialog) {
                    SettingsEntryButton(onClick = onOpenSettings)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 连接列表
        if (connections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无保存的连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(connections, key = { it.id }) { conn ->
                    ConnectionListItem(
                        connection = conn,
                        isSelected = conn.id == selectedConnection?.id,
                        status = connectionStatuses[conn.id] ?: ConnectionStatus(),
                        onClick = { onSelectConnection(conn) },
                        onEdit = { onEditConnection(conn) },
                        onDelete = { onDeleteConnection(conn.id) },
                    )
                }
            }
        }
    }
}

/**
 * 连接列表项.
 */
@Composable
private fun ConnectionListItem(
    connection: ConnectionConfig,
    isSelected: Boolean,
    status: ConnectionStatus,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = winShape(8.dp),
        contentColor = selectionContentColor(isSelected),
        color = selectionContainerColor(isSelected),
        border = if (isClassicChrome) null else BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 数据库类型图标
            Icon(
                imageVector = when (connection.dialect) {
                    DialectType.MYSQL -> Icons.Default.Storage
                    DialectType.POSTGRESQL -> Icons.Default.Storage
                    DialectType.H2 -> Icons.Default.Memory
                    DialectType.DUCKDB -> Icons.Default.Analytics
                    DialectType.SQLITE -> Icons.Default.FolderOpen
                    DialectType.UNKNOWN -> Icons.Default.QuestionMark
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusDot(status.state)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${connection.dialect.name} • ${connection.connectionString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                WinIconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    WinMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    WinMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                    )
                }
            }
        }
    }
}

/** 连接状态色点 —— 列表项 / 总览面板共用 */
@Composable
private fun ConnectionStatusDot(state: ConnectionState, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(connectionStateColor(state), androidx.compose.foundation.shape.CircleShape),
    )
}

@Composable
private fun connectionStateColor(state: ConnectionState) = when (state) {
    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    ConnectionState.FAILED -> MaterialTheme.colorScheme.error
}

/** 连接状态文案 */
private fun connectionStateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.DISCONNECTED -> "未连接"
    ConnectionState.CONNECTING -> "连接中"
    ConnectionState.CONNECTED -> "已连接"
    ConnectionState.FAILED -> "连接失败"
}

/**
 * 右侧连接信息引导面板.
 */
@Composable
private fun ConnectionWizardPanel(
    editingConnection: ConnectionConfig?,
    selectedConnection: ConnectionConfig?,
    connectionStatus: ConnectionStatus,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    onSaveConnection: (ConnectionConfig) -> Unit,
    onQuickConnectDirect: (ConnectionConfig) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
    onEditConnection: (ConnectionConfig) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,
    onConnect: (ConnectionConfig) -> Unit = {},
    onDisconnect: (ConnectionConfig) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ConnectionWizardContent(
        editingConnection = editingConnection,
        wizardStep = wizardStep,
        wizardFlow = wizardFlow,
        onSaveConnection = onSaveConnection,
        onQuickConnectDirect = onQuickConnectDirect,
        onCancelEdit = onCancelEdit,
        onWizardNext = onWizardNext,
        onWizardBack = onWizardBack,
        onUpdateEditingConnection = onUpdateEditingConnection,
        onTestConnection = onTestConnection,
        modifier = modifier,
        idleContent = {
            // 全屏场景：IDLE 时若已选中连接显示总览，否则显示入口按钮
            if (selectedConnection != null) {
                ConnectionOverviewPanel(
                    connection = selectedConnection,
                    status = connectionStatus,
                    onConnect = { onConnect(selectedConnection) },
                    onDisconnect = { onDisconnect(selectedConnection) },
                    onEdit = { onEditConnection(selectedConnection) },
                    onDelete = { onDeleteConnection(selectedConnection.id) },
                )
            } else {
                IdlePanel(
                    onNewConnection = onNewConnection,
                    onQuickConnect = onQuickConnect,
                )
            }
        },
    )
}

/**
 * 连接配置向导内容 —— 仅渲染向导步骤本身（QUICK_CONNECT / BASIC_INFO / CONNECTION_TYPE /
 * CREDENTIALS / TEST_SAVE），IDLE 步骤的内容由 [idleContent] 槽位提供。
 *
 * 抽出本组件的目的：
 * - [ConnectionManagerScreen] 在 IDLE 槽位提供「总览面板 / 入口按钮」
 * - [AddConnectionDialog] 在 IDLE 槽位仅提供「入口按钮」，**不**渲染总览面板
 *   （弹窗里展示选中连接的引擎状态总览会误导 —— 那是连接管理全屏的职责）。
 *
 * 步骤组件（QuickConnectStep / BasicInfoStep / ...）全部沿用 v2 内的同名实现 —— 仅本函数对外可见。
 */
@Composable
fun ConnectionWizardContent(
    editingConnection: ConnectionConfig?,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    onSaveConnection: (ConnectionConfig) -> Unit,
    onQuickConnectDirect: (ConnectionConfig) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,
    idleContent: @Composable () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val totalSteps = totalStepsFor(wizardFlow)

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .verticalScroll(scrollState),
    ) {
        // 使用 safe render 避免 editingConnection 为 null 时崩溃
        when (wizardStep) {
            WizardStep.IDLE -> idleContent()
            WizardStep.QUICK_CONNECT -> editingConnection?.let { config ->
                QuickConnectStep(
                    stepIndex = 1,
                    totalSteps = totalSteps,
                    onSelectDialect = { dialect ->
                        onUpdateEditingConnection(config.withDialect(dialect))
                        onWizardNext(WizardStep.CREDENTIALS)
                    },
                    onBack = onWizardBack,
                    onCancel = onCancelEdit,
                )
            }
            WizardStep.BASIC_INFO -> editingConnection?.let { config ->
                BasicInfoStep(
                    editingConnection = config,
                    stepIndex = stepIndexOf(wizardStep, wizardFlow),
                    totalSteps = totalSteps,
                    onNameChange = { onUpdateEditingConnection(config.copy(name = it)) },
                    onDialectChange = { newDialect ->
                        onUpdateEditingConnection(config.withDialect(newDialect))
                    },
                    onNext = { onWizardNext(WizardStep.CONNECTION_TYPE) },
                    onCancel = onCancelEdit,
                )
            }
            WizardStep.CONNECTION_TYPE -> editingConnection?.let { config ->
                ConnectionTypeStep(
                    editingConnection = config,
                    stepIndex = stepIndexOf(wizardStep, wizardFlow),
                    totalSteps = totalSteps,
                    onConnectionTypeChange = { onUpdateEditingConnection(config.withConnectionType(it)) },
                    onNext = { onWizardNext(WizardStep.CREDENTIALS) },
                    onBack = onWizardBack,
                    onCancel = onCancelEdit,
                )
            }
            WizardStep.CREDENTIALS -> editingConnection?.let { config ->
                CredentialsStep(
                    editingConnection = config,
                    stepIndex = stepIndexOf(wizardStep, wizardFlow),
                    totalSteps = totalSteps,
                    onNext = { onWizardNext(WizardStep.TEST_SAVE) },
                    onBack = onWizardBack,
                    onCancel = onCancelEdit,
                    onUpdateEditingConnection = onUpdateEditingConnection,
                )
            }
            WizardStep.TEST_SAVE -> editingConnection?.let { config ->
                TestSaveStep(
                    editingConnection = config,
                    stepIndex = stepIndexOf(wizardStep, wizardFlow),
                    totalSteps = totalSteps,
                    wizardFlow = wizardFlow,
                    onSave = onSaveConnection,
                    onQuickConnectDirect = onQuickConnectDirect,
                    onTestConnection = onTestConnection,
                    onBack = onWizardBack,
                    onCancel = onCancelEdit,
                )
            }
        }
    }
}

/** 根据当前流程计算步骤索引 (1-based) */
private fun stepIndexOf(step: WizardStep, flow: WizardFlow): Int = when (flow) {
    WizardFlow.QUICK_CONNECT -> when (step) {
        WizardStep.QUICK_CONNECT -> 1
        WizardStep.CREDENTIALS -> 2
        WizardStep.TEST_SAVE -> 3
        else -> 0
    }
    WizardFlow.NORMAL -> when (step) {
        WizardStep.BASIC_INFO -> 1
        WizardStep.CONNECTION_TYPE -> 2
        WizardStep.CREDENTIALS -> 3
        WizardStep.TEST_SAVE -> 4
        else -> 0
    }
}

/**
 * 选中连接总览 (`wizardStep == IDLE` 且已选中连接时取代空闲引导页).
 *
 * 展示引擎侧会话状态与连接信息，并提供 连接 / 断开 / 编辑 / 删除 操作 —— 「连接」的语义是
 * 让引擎用该配置建连接池并做 `isValid` 校验（`IdbEngine.testConnection`），
 * 「断开」是释放该配置的连接池（`IdbEngine.disconnect`）。
 */
@Composable
private fun ConnectionOverviewPanel(
    connection: ConnectionConfig,
    status: ConnectionStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 历史配置可能没有 jdbcUrl（v2.11 之前保存的空 URL）—— 引擎侧无法建池，先引导去补全
        val connectable = buildJdbcUrl(connection).isNotBlank()

        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionStatusDot(status.state, 12.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(connection.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${connection.dialect.name} • ${connection.connectionType.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().uiBevel(raised = true),
            shape = winShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("连接状态", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusDot(status.state)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionStateLabel(status.state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.state == ConnectionState.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (status.message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.state == ConnectionState.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("连接信息", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionSummary(connection)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WinTextButton(onClick = onDelete, shape = SundaysPalette.buttonShape) { Text("删除") }
            Spacer(modifier = Modifier.width(8.dp))
            WinOutlinedButton(onClick = onEdit, shape = SundaysPalette.buttonShape) {
                Icon(Icons.Default.Edit, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("编辑")
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (status.state) {
                ConnectionState.CONNECTED -> WinOutlinedButton(onClick = onDisconnect, shape = SundaysPalette.buttonShape) {
                    Icon(Icons.Default.LinkOff, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("断开")
                }
                ConnectionState.CONNECTING -> WinButton(onClick = {}, enabled = false, shape = SundaysPalette.buttonShape) {
                    WinProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("连接中...")
                }
                ConnectionState.DISCONNECTED, ConnectionState.FAILED -> Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    WinButton(onClick = onConnect, enabled = connectable, shape = SundaysPalette.buttonShape) {
                        Icon(Icons.Default.Bolt, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("连接")
                    }
                    if (!connectable) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "缺少 JDBC URL —— 请先「编辑」补全连接信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 空闲状态面板 —— IDLE 时显示入口按钮（新建 / 快速连接）。可在 ConnectionManagerScreen 全屏使用，也可在 [AddConnectionDialog] 内复用（弹窗场景不渲染左侧连接列表与总览）。 */
@Composable
internal fun IdlePanel(
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "数据库连接管理",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "创建新连接或从列表选择一个进行编辑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        WinButton(onClick = onQuickConnect, shape = SundaysPalette.buttonShape) {
            Icon(Icons.Default.Bolt, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("快速连接")
        }
        Spacer(modifier = Modifier.height(12.dp))
        WinOutlinedButton(onClick = onNewConnection, shape = SundaysPalette.buttonShape) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("新建连接")
        }
    }
}

/** 快速连接步骤 — 预置方言快捷入口 */
@Composable
private fun QuickConnectStep(
    stepIndex: Int,
    totalSteps: Int,
    onSelectDialect: (DialectType) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    StepLayout(
        title = "快速连接",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        Text(
            text = "选择数据库类型，快速创建连接",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 快速连接卡片
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickConnectCard(
                title = "MySQL",
                description = "客户端-服务器模式",
                port = 3306,
                icon = Icons.Default.Storage,
                onClick = { onSelectDialect(DialectType.MYSQL) },
            )
            QuickConnectCard(
                title = "PostgreSQL",
                description = "客户端-服务器模式",
                port = 5432,
                icon = Icons.Default.Storage,
                onClick = { onSelectDialect(DialectType.POSTGRESQL) },
            )
            QuickConnectCard(
                title = "H2",
                description = "内存数据库",
                port = null,
                icon = Icons.Default.Memory,
                onClick = { onSelectDialect(DialectType.H2) },
            )
            QuickConnectCard(
                title = "DuckDB",
                description = "嵌入式 OLAP",
                port = null,
                icon = Icons.Default.Analytics,
                onClick = { onSelectDialect(DialectType.DUCKDB) },
            )
            QuickConnectCard(
                title = "SQLite",
                description = "文件数据库",
                port = null,
                icon = Icons.Default.FolderOpen,
                onClick = { onSelectDialect(DialectType.SQLITE) },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text("← 返回") }
        }
    }
}

/** 快速连接卡片 */
@Composable
private fun QuickConnectCard(
    title: String,
    description: String,
    port: Int?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = winShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (port != null) {
                Surface(
                    shape = winShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = ":$port",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 普通流程步骤 1: 基础信息 (名称 + 方言选择) —— 编辑直接回写调用方，不保留本地副本 */
@Composable
private fun BasicInfoStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onNameChange: (String) -> Unit,
    onDialectChange: (DialectType) -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    StepLayout(
        title = "基础信息",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        WinTextField(
            value = editingConnection.name,
            onValueChange = onNameChange,
            label = { Text("连接名称") },
            placeholder = { Text("例如: 测试环境 MySQL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("数据库方言", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialectType.entries.filter { it != DialectType.UNKNOWN }.forEach { d ->
                DialectOption(
                    dialect = d,
                    isSelected = editingConnection.dialect == d,
                    onClick = { if (d != editingConnection.dialect) onDialectChange(d) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            WinTextButton(onClick = onCancel, shape = SundaysPalette.buttonShape) { Text("取消") }
            Spacer(modifier = Modifier.width(8.dp))
            WinButton(
                onClick = onNext,
                enabled = editingConnection.name.isNotBlank(),
                shape = SundaysPalette.buttonShape,
            ) {
                Text("下一步")
                Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

/**
 * 可选中选项行 —— 单选按钮 + 标题 + 说明。
 *
 * [DialectOption] 与 [ConnectionTypeOption] 的外观完全一致，差异只在「标题 / 说明」两段文案
 * （各自的 `when` 映射），因此布局收敛到这里，两个选项只负责产出文案。
 */
@Composable
private fun SelectableOptionRow(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = winShape(8.dp),
        contentColor = selectionContentColor(isSelected),
        color = selectionContainerColor(isSelected),
        border = if (isClassicChrome) null else BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(colors = selectionIndicatorColors(), selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 数据库方言选项 */
@Composable
private fun DialectOption(
    dialect: DialectType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    SelectableOptionRow(
        title = dialect.name,
        description = when (dialect) {
            DialectType.MYSQL -> "MySQL 客户端-服务器模式"
            DialectType.POSTGRESQL -> "PostgreSQL 客户端-服务器模式"
            DialectType.H2 -> "H2 内存/嵌入式数据库"
            DialectType.DUCKDB -> "DuckDB 嵌入式 OLAP"
            DialectType.SQLITE -> "SQLite 文件数据库"
            DialectType.UNKNOWN -> ""
        },
        isSelected = isSelected,
        onClick = onClick,
    )
}

/** 普通流程步骤 2: 连接类型 —— 选项来自 [DialectType.supportedConnectionTypes]，选择直接回写调用方 */
@Composable
private fun ConnectionTypeStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val availableTypes = editingConnection.dialect.supportedConnectionTypes

    StepLayout(
        title = "连接类型",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        Text("选择连接模式", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            availableTypes.forEach { ct ->
                ConnectionTypeOption(
                    connectionType = ct,
                    isSelected = editingConnection.connectionType == ct,
                    onClick = { if (editingConnection.connectionType != ct) onConnectionTypeChange(ct) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            WinButton(onClick = onNext, shape = SundaysPalette.buttonShape) {
                Text("下一步")
                Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

/** 连接类型选项 */
@Composable
private fun ConnectionTypeOption(
    connectionType: ConnectionType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    SelectableOptionRow(
        title = when (connectionType) {
            ConnectionType.CLIENT_SERVER -> "客户端-服务器"
            ConnectionType.EMBEDDED -> "嵌入式"
            ConnectionType.IN_MEMORY -> "内存数据库"
            ConnectionType.FILE_BASED -> "文件数据库"
            ConnectionType.UNKNOWN -> "未知"
        },
        description = when (connectionType) {
            ConnectionType.CLIENT_SERVER -> "需要主机地址和端口"
            ConnectionType.EMBEDDED -> "数据库文件嵌入在应用中"
            ConnectionType.IN_MEMORY -> "数据存储在内存中，重启后消失"
            ConnectionType.FILE_BASED -> "数据存储在单个文件中"
            ConnectionType.UNKNOWN -> ""
        },
        isSelected = isSelected,
        onClick = onClick,
    )
}

/**
 * 认证信息步骤 (普通流程 3 / 快速连接 2) —— 所有编辑经 [onUpdateEditingConnection] 立即回写调用方，
 * 字段与 JDBC URL 始终同步（URL 由 [buildJdbcUrl] 从字段折算，显式 query 参数原样保留）。
 *
 * 按连接类型渲染：
 * - `CLIENT_SERVER`：主机 / 端口 / 数据库 / 用户名 / 密码 + JDBC URL 输入框（双向同步）
 * - `IN_MEMORY` / `EMBEDDED` / `FILE_BASED`：单个目标字段（库名 or 文件路径，写入 `database`），
 *   URL 由方言规则折算（H2 `mem:` / `file:`、DuckDB、SQLite）
 *
 * 字段不足（折算 URL 为空串）时「下一步」禁用 —— 保证向导不会把无 URL 的配置带进引擎。
 */
@Composable
private fun CredentialsStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
) {
    val canProceed = buildJdbcUrl(editingConnection).isNotBlank()

    /** 回写字段变更并重建 URL —— 显式 query 参数（`?useSSL=false&...`）原样保留 */
    fun apply(next: ConnectionConfig) {
        val query = editingConnection.jdbcUrl.substringAfter('?', "")
        onUpdateEditingConnection(next.copy(jdbcUrl = buildJdbcUrl(next, query)))
    }

    /** JDBC URL 输入框 → 字段：解析出的 host / port / database / 凭据回写，URL 保持用户输入原样 */
    fun applyUrl(url: String) {
        val parts = parseJdbcUrl(url, editingConnection.dialect)
        onUpdateEditingConnection(
            editingConnection.copy(
                jdbcUrl = url,
                host = parts.host,
                port = parts.port.toIntOrNull(),
                database = parts.database,
                username = parts.username,
                password = parts.password,
            )
        )
    }

    StepLayout(
        title = "连接详情",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        when (editingConnection.connectionType) {
            ConnectionType.CLIENT_SERVER -> {
                WinTextField(
                    value = editingConnection.host,
                    onValueChange = { apply(editingConnection.copy(host = it)) },
                    label = { Text("主机地址") },
                    placeholder = { Text("例如: localhost 或 192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Wifi, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                WinTextField(
                    value = editingConnection.port?.takeIf { it > 0 }?.toString() ?: "",
                    onValueChange = { apply(editingConnection.copy(port = it.filter(Char::isDigit).toIntOrNull())) },
                    label = { Text("端口") },
                    placeholder = { Text(editingConnection.displayPort.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))

                WinTextField(
                    value = editingConnection.database,
                    onValueChange = { apply(editingConnection.copy(database = it)) },
                    label = { Text("数据库名") },
                    placeholder = { Text("例如: testdb") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Storage, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                WinTextField(
                    value = editingConnection.username,
                    onValueChange = { apply(editingConnection.copy(username = it)) },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                WinTextField(
                    value = editingConnection.password,
                    onValueChange = { apply(editingConnection.copy(password = it)) },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                )

                WinDivider(modifier = Modifier.padding(vertical = 16.dp))

                WinTextField(
                    value = editingConnection.jdbcUrl,
                    onValueChange = ::applyUrl,
                    label = { Text("JDBC URL") },
                    placeholder = { Text("jdbc:mysql://host:3306/db?useSSL=false") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                )
            }

            ConnectionType.IN_MEMORY, ConnectionType.EMBEDDED, ConnectionType.FILE_BASED -> {
                val (label, placeholder, icon) = embeddedFieldSpec(editingConnection)

                WinTextField(
                    value = editingConnection.database,
                    onValueChange = { apply(editingConnection.copy(database = it)) },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(icon, null) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("JDBC URL（由上方字段折算）", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = winShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = editingConnection.jdbcUrl.ifBlank { "（待补齐字段）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            ConnectionType.UNKNOWN -> {}
        }

        if (!canProceed) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "请补齐必填字段 —— 连接必须能折算出一条合法的 JDBC URL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            WinButton(onClick = onNext, enabled = canProceed, shape = SundaysPalette.buttonShape) {
                Text("下一步")
                Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

/** 嵌入式 / 内存 / 文件型连接的单个目标字段描述（label / placeholder / 图标） */
private fun embeddedFieldSpec(
    connection: ConnectionConfig,
): Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector> = when (connection.dialect) {
    DialectType.H2 -> if (connection.connectionType == ConnectionType.FILE_BASED) {
        Triple("数据库文件路径", "例如: /path/to/data（H2 自动补 .mv.db）", Icons.Default.FolderOpen)
    } else {
        Triple("数据库名称", "例如: testdb", Icons.Default.Storage)
    }
    DialectType.DUCKDB -> Triple(
        "数据库文件路径（留空 = 内存库）",
        "例如: /path/to/data.duckdb",
        Icons.Default.FolderOpen,
    )
    DialectType.SQLITE -> Triple(
        "数据库文件路径（留空 = 内存库）",
        "例如: /path/to/data.db",
        Icons.Default.FolderOpen,
    )
    else -> Triple("数据库", "例如: testdb", Icons.Default.Storage)
}

/** 测试 & 保存步骤 (普通流程 4 / 快速连接 3) */
@Composable
private fun TestSaveStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    wizardFlow: WizardFlow,
    onSave: (ConnectionConfig) -> Unit,
    onQuickConnectDirect: (ConnectionConfig) -> Unit,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val isQuickConnect = wizardFlow == WizardFlow.QUICK_CONNECT
    val confirmLabel = if (isQuickConnect) "连接" else "保存"
    val onConfirm: (ConnectionConfig) -> Unit =
        if (isQuickConnect) onQuickConnectDirect else onSave
    val canConfirm = buildJdbcUrl(editingConnection).isNotBlank()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    StepLayout(
        title = "测试并保存",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        // 连接信息摘要
        Surface(
            modifier = Modifier.fillMaxWidth().uiBevel(raised = true),
            shape = winShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("连接信息摘要", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionSummary(connection = editingConnection)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 测试按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            WinButton(
                onClick = {
                    val probe = onTestConnection ?: return@WinButton
                    scope.launch {
                        isTesting = true
                        testResult = try {
                            probe(editingConnection)
                        } catch (e: Exception) {
                            TestResult(success = false, message = e.message ?: "Unknown error")
                        }
                        isTesting = false
                    }
                },
                enabled = !isTesting && onTestConnection != null && canConfirm,
                shape = SundaysPalette.buttonShape,
            ) {
                if (isTesting) {
                    WinProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试中...")
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("测试连接")
                }
            }
        }

        // 测试结果
        testResult?.let { result ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().uiBevel(raised = true),
                shape = winShape(8.dp),
                color = if (result.success) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (result.success) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (result.success) "连接成功!"
                               else "连接失败: ${result.message}",
                        color = if (result.success) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            WinButton(
                onClick = { onConfirm(editingConnection) },
                enabled = canConfirm,
                shape = SundaysPalette.buttonShape,
            ) {
                Icon(
                    imageVector = if (isQuickConnect) Icons.Default.Bolt else Icons.Default.Save,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(confirmLabel)
            }
        }
    }
}

/** 连接信息摘要 */
@Composable
private fun ConnectionSummary(connection: ConnectionConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SummaryRow("名称", connection.name)
        SummaryRow("方言", connection.dialect.name)
        SummaryRow("类型", connection.connectionType.name)
        if (connection.connectionType == ConnectionType.CLIENT_SERVER) {
            SummaryRow("主机", connection.host)
            SummaryRow("端口", connection.displayPort.toString())
            if (connection.database.isNotBlank()) {
                SummaryRow("数据库", connection.database)
            }
            SummaryRow("用户", connection.username)
        } else if (connection.database.isNotBlank()) {
            SummaryRow(if (connection.connectionType == ConnectionType.IN_MEMORY) "数据库" else "目标", connection.database)
        }
        if (connection.jdbcUrl.isNotBlank()) {
            SummaryRow("JDBC URL", connection.jdbcUrl)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 测试结果 */
data class TestResult(val success: Boolean, val message: String = "")

/** 步骤布局 */
@Composable
private fun StepLayout(
    title: String,
    step: Int,
    totalSteps: Int,
    onCancel: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "第 $step / $totalSteps 步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WinIconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 步骤指示器
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(totalSteps) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index < step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            winShape(2.dp),
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        content()
    }
}
