/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.dialect.contract.ConnectionFieldIds
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.SettingsEntryButton
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.isClassicChrome
import com.langbiantianya.sundays.ui.selectionContainerColor
import com.langbiantianya.sundays.ui.selectionContentColor
import com.langbiantianya.sundays.ui.selectionIndicatorColors
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.uiBevel
import com.langbiantianya.sundays.ui.winShape
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
    /**
     * 方言 → 连接表单规格 的注册表（由装配点从引擎的 `SYSTEM.LIST_DRIVERS` 拉来）。
     *
     * **默认值是空注册表** —— 那意味着「所有方言都走旧的按连接类型分支」，
     * 也就是本组件**不接引擎也能照常工作**。
     *
     * 查不到规格时**回退旧分支**是设计好的双向通道（设计文档 §5.7 的 Q6 结论）：
     * 引擎比前端新、或者方言插件还没声明 `connectionForm`，都靠它兜住。
     */
    dialectForms: DialectFormRegistry = DialectFormRegistry.EMPTY,
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
     * 模式与 [com.langbiantianya.sundays.ui.DangerousSql] 的危险 SQL 确认框同源：
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
            modifier =
                Modifier
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
            dialectForms = dialectForms,
            modifier =
                Modifier
                    // ⚠️ 这里**曾经**试过改成 `fillMaxWidth()`：本意是「右栏是从属区域，
                    // 应当占满剩余宽度」，想顺带治 §9.4 的按钮溢出。**实测毫无变化** ——
                    // 步骤条宽度与改之前逐像素一致（各 383px、总宽 1550px），说明右栏
                    // 本来就是满宽的，溢出来自更里面。所以改动被回退了：
                    // 留一个验证不了的「修复」只会让人以为问题解决了。
                    //
                    // §9.4 的真因仍未隔离，已知事实见 TEST_CASES.md §9.4。
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
        title = { Text(str(StrKey.DELETE_CONNECTION)) },
        text = {
            Column {
                Text(str(StrKey.CONFIRM_DELETE_CONNECTION, connection.name))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = str(StrKey.DELETE_CONNECTION_WARNING) + str(StrKey.DELETE_CONNECTION_NOTE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            WinTextButton(onClick = onDismiss) { Text(str(StrKey.CANCEL)) }
        },
        confirmButton = {
            WinButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                shape = SundaysPalette.buttonShape,
            ) { Text(str(StrKey.DELETE)) }
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
    IDLE, // 空闲状态 (未编辑)
    QUICK_CONNECT, // 快速连接：选方言 (跳过 BASIC_INFO / CONNECTION_TYPE)
    BASIC_INFO, // 普通流程：基础信息 (名称 + 方言选择)
    CONNECTION_TYPE, // 普通流程：连接类型 (CLIENT_SERVER / EMBEDDED / FILE_BASED / IN_MEMORY)
    CREDENTIALS, // 认证信息 (主机/端口/用户名/密码 或 文件路径)
    TEST_SAVE, // 测试连接 & 保存
}

/** 当前引导流程类型 (用于步骤指示器自适应) */
enum class WizardFlow { QUICK_CONNECT, NORMAL }

/** 当前流程的总步骤数 (用于步骤指示器自适应) */
private fun totalStepsFor(flow: WizardFlow): Int =
    when (flow) {
        WizardFlow.QUICK_CONNECT -> 3

        // 快速连接：选方言 → 凭据 → 测试保存
        WizardFlow.NORMAL -> 4 // 普通：基础 → 类型 → 凭据 → 测试保存
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
        modifier =
            modifier
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
                text = str(StrKey.CONNECTION_LIST),
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                WinIconButton(onClick = onQuickConnect) {
                    Icon(Icons.Default.Bolt, contentDescription = str(StrKey.QUICK_CONNECT))
                }
                WinIconButton(onClick = onNewConnection) {
                    Icon(Icons.Default.Add, contentDescription = str(StrKey.NEW_CONNECTION))
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = str(StrKey.NO_SAVED_CONNECTIONS),
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

    // ⚠️ [focusRing] 不是装饰：这张卡片是**列表项的主要交互目标**（点它=选中），
    // 而 `Modifier.clickable` 在非触摸模式下会加入 Tab 序却不画任何焦点指示 ——
    // 键盘用户 Tab 得到这里时，界面和没聚焦时一模一样。
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRing()
                .clickable(onClick = onClick)
                .testTag(connectionCardTag(connection.id)),
        shape = winShape(8.dp),
        contentColor = selectionContentColor(isSelected),
        color = selectionContainerColor(isSelected),
        border =
            if (isClassicChrome) {
                null
            } else {
                BorderStroke(
                    1.dp,
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 数据库类型图标
            Icon(
                imageVector = DialectMetadata.of(connection.dialect).icon,
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
                    text = "${connection.dialect.name} • ${connection.connectionString(str(StrKey.NO_FILE_SPECIFIED), str(StrKey.IN_MEMORY_DB_LABEL))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                WinIconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = str(StrKey.MORE_ACTIONS))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    WinMenuItem(
                        text = { Text(str(StrKey.EDIT)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    WinMenuItem(
                        text = { Text(str(StrKey.DELETE)) },
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
private fun ConnectionStatusDot(
    state: ConnectionState,
    size: Dp = 8.dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .background(connectionStateColor(state), androidx.compose.foundation.shape.CircleShape),
    )
}

@Composable
private fun connectionStateColor(state: ConnectionState) =
    when (state) {
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.FAILED -> MaterialTheme.colorScheme.error
    }

/**
 * 连接状态文案 —— **返回键而不是成品串**。
 *
 * `str()` 是 `@Composable`，而这里是普通函数（`when` 只做映射，不该有副作用），
 * 因此把「取文案」留给渲染处：`str(connectionStateLabel(status.state))`。
 */
private fun connectionStateLabel(state: ConnectionState): StrKey =
    when (state) {
        ConnectionState.DISCONNECTED -> StrKey.NOT_CONNECTED
        ConnectionState.CONNECTING -> StrKey.CONNECTING
        ConnectionState.CONNECTED -> StrKey.CONNECTED
        ConnectionState.FAILED -> StrKey.CONNECT_FAILED
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
    dialectForms: DialectFormRegistry = DialectFormRegistry.EMPTY,
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
        dialectForms = dialectForms,
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
    dialectForms: DialectFormRegistry = DialectFormRegistry.EMPTY,
    idleContent: @Composable () -> Unit = {},
) {
    val totalSteps = totalStepsFor(wizardFlow)

    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
    ) {
        // 使用 safe render 避免 editingConnection 为 null 时崩溃
        when (wizardStep) {
            WizardStep.IDLE -> {
                // 总览面板也可能比窗口高（连接摘要长、条目多），所以这一屏自己带滚动。
                //
                // ⚠️ 这里**不能**再在整列上挂 `verticalScroll`：那会让整列的高度约束变成
                // 「无限高」，`weight` 就再也分不出剩余空间了（见 [StepLayout] 的 KDoc）。
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                ) {
                    idleContent()
                }
            }

            WizardStep.QUICK_CONNECT -> {
                editingConnection?.let { config ->
                    QuickConnectStep(
                        modifier = Modifier.weight(1f),
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
            }

            WizardStep.BASIC_INFO -> {
                editingConnection?.let { config ->
                    BasicInfoStep(
                        modifier = Modifier.weight(1f),
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
            }

            WizardStep.CONNECTION_TYPE -> {
                editingConnection?.let { config ->
                    ConnectionTypeStep(
                        modifier = Modifier.weight(1f),
                        editingConnection = config,
                        stepIndex = stepIndexOf(wizardStep, wizardFlow),
                        totalSteps = totalSteps,
                        onConnectionTypeChange = { onUpdateEditingConnection(config.withConnectionType(it)) },
                        onNext = { onWizardNext(WizardStep.CREDENTIALS) },
                        onBack = onWizardBack,
                        onCancel = onCancelEdit,
                    )
                }
            }

            WizardStep.CREDENTIALS -> {
                editingConnection?.let { config ->
                    CredentialsStep(
                        modifier = Modifier.weight(1f),
                        editingConnection = config,
                        stepIndex = stepIndexOf(wizardStep, wizardFlow),
                        totalSteps = totalSteps,
                        onNext = { onWizardNext(WizardStep.TEST_SAVE) },
                        onBack = onWizardBack,
                        onCancel = onCancelEdit,
                        onUpdateEditingConnection = onUpdateEditingConnection,
                        dialectForms = dialectForms,
                    )
                }
            }

            WizardStep.TEST_SAVE -> {
                editingConnection?.let { config ->
                    TestSaveStep(
                        modifier = Modifier.weight(1f),
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
}

/** 根据当前流程计算步骤索引 (1-based) */
private fun stepIndexOf(
    step: WizardStep,
    flow: WizardFlow,
): Int =
    when (flow) {
        WizardFlow.QUICK_CONNECT -> {
            when (step) {
                WizardStep.QUICK_CONNECT -> 1
                WizardStep.CREDENTIALS -> 2
                WizardStep.TEST_SAVE -> 3
                else -> 0
            }
        }

        WizardFlow.NORMAL -> {
            when (step) {
                WizardStep.BASIC_INFO -> 1
                WizardStep.CONNECTION_TYPE -> 2
                WizardStep.CREDENTIALS -> 3
                WizardStep.TEST_SAVE -> 4
                else -> 0
            }
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(CONNECT_OVERVIEW_PANEL_TAG),
    ) {
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(CONNECT_OVERVIEW_CARD_TAG)
                    .uiBevel(raised = true),
            shape = winShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(str(StrKey.CONNECTION_STATUS), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusDot(status.state)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(connectionStateLabel(status.state)),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (status.state == ConnectionState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
                if (status.message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (status.state == ConnectionState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(str(StrKey.CONNECTION_INFO), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionSummary(connection)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(CONNECT_OVERVIEW_ACTIONS_TAG),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WinTextButton(onClick = onDelete, shape = SundaysPalette.buttonShape) { Text(str(StrKey.DELETE)) }
            Spacer(modifier = Modifier.width(8.dp))
            WinOutlinedButton(onClick = onEdit, shape = SundaysPalette.buttonShape) {
                Icon(Icons.Default.Edit, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(str(StrKey.EDIT))
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (status.state) {
                ConnectionState.CONNECTED -> {
                    WinOutlinedButton(onClick = onDisconnect, shape = SundaysPalette.buttonShape) {
                        Icon(Icons.Default.LinkOff, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str(StrKey.DISCONNECT))
                    }
                }

                ConnectionState.CONNECTING -> {
                    WinButton(onClick = {}, enabled = false, shape = SundaysPalette.buttonShape) {
                        WinProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str(StrKey.CONNECTING_ELLIPSIS))
                    }
                }

                ConnectionState.DISCONNECTED, ConnectionState.FAILED -> {
                    Column(
                        horizontalAlignment = Alignment.End,
                    ) {
                        WinButton(onClick = onConnect, enabled = connectable, shape = SundaysPalette.buttonShape) {
                            Icon(Icons.Default.Bolt, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(str(StrKey.CONNECT))
                        }
                        if (!connectable) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = str(StrKey.MISSING_JDBC_URL),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
            text = str(StrKey.CONNECTION_MANAGER_TITLE),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = str(StrKey.CONNECTION_MANAGER_HINT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        WinButton(onClick = onQuickConnect, shape = SundaysPalette.buttonShape) {
            Icon(Icons.Default.Bolt, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(str(StrKey.QUICK_CONNECT))
        }
        Spacer(modifier = Modifier.height(12.dp))
        WinOutlinedButton(onClick = onNewConnection, shape = SundaysPalette.buttonShape) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(str(StrKey.NEW_CONNECTION))
        }
    }
}

/** 快速连接步骤 — 预置方言快捷入口 */
@Composable
private fun QuickConnectStep(
    modifier: Modifier = Modifier,
    stepIndex: Int,
    totalSteps: Int,
    onSelectDialect: (DialectType) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    StepLayout(
        modifier = modifier,
        title = str(StrKey.QUICK_CONNECT),
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text(str(StrKey.BACK_WITH_ARROW)) }
            }
        },
    ) {
        Text(
            text = str(StrKey.QUICK_CONNECT_HINT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 快速连接卡片
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickConnectCard(
                title = "MySQL",
                description = str(StrKey.CONN_TYPE_CLIENT_SERVER),
                port = 3306,
                icon = Icons.Default.Storage,
                onClick = { onSelectDialect(DialectType.MYSQL) },
            )
            QuickConnectCard(
                title = "PostgreSQL",
                description = str(StrKey.CONN_TYPE_CLIENT_SERVER),
                port = 5432,
                icon = Icons.Default.Storage,
                onClick = { onSelectDialect(DialectType.POSTGRESQL) },
            )
            QuickConnectCard(
                title = "H2",
                description = str(StrKey.CONN_TYPE_IN_MEMORY),
                port = null,
                icon = Icons.Default.Memory,
                onClick = { onSelectDialect(DialectType.H2) },
            )
            QuickConnectCard(
                title = "DuckDB",
                description = str(StrKey.CONN_TYPE_EMBEDDED_OLAP),
                port = null,
                icon = Icons.Default.Analytics,
                onClick = { onSelectDialect(DialectType.DUCKDB) },
            )
            QuickConnectCard(
                title = "SQLite",
                description = str(StrKey.CONN_TYPE_FILE),
                port = null,
                icon = Icons.Default.FolderOpen,
                onClick = { onSelectDialect(DialectType.SQLITE) },
            )
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
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRing()
                .clickable(onClick = onClick)
                .testTag(quickConnectCardTag(title)),
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
    modifier: Modifier = Modifier,
    stepIndex: Int,
    totalSteps: Int,
    onNameChange: (String) -> Unit,
    onDialectChange: (DialectType) -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    StepLayout(
        modifier = modifier,
        title = str(StrKey.BASIC_INFO),
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                WinTextButton(onClick = onCancel, shape = SundaysPalette.buttonShape) { Text(str(StrKey.CANCEL)) }
                Spacer(Modifier.width(8.dp))
                WinButton(
                    onClick = onNext,
                    enabled = editingConnection.name.isNotBlank(),
                    shape = SundaysPalette.buttonShape,
                ) {
                    Text(str(StrKey.NEXT_STEP))
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        },
    ) {
        WinTextField(
            value = editingConnection.name,
            onValueChange = onNameChange,
            label = { Text(str(StrKey.CONNECTION_NAME)) },
            placeholder = { Text(str(StrKey.CONNECTION_NAME_HINT)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(str(StrKey.DIALECT), style = MaterialTheme.typography.labelLarge)
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
    // [focusRing] 必需：这行是向导里「选方言 / 选连接类型」的唯一点击目标，
    // 而 `clickable` 在非触摸模式下会进 Tab 序却不画焦点指示。
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRing()
                .clickable(onClick = onClick)
                .testTag(selectableOptionTag(title)),
        shape = winShape(8.dp),
        contentColor = selectionContentColor(isSelected),
        color = selectionContainerColor(isSelected),
        border =
            if (isClassicChrome) {
                null
            } else {
                BorderStroke(
                    1.dp,
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                )
            },
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
    val descriptionKey = DialectMetadata.of(dialect).descriptionKey
    val description = descriptionKey?.let { str(it) } ?: ""
    SelectableOptionRow(
        title = dialect.name,
        description = description,
        isSelected = isSelected,
        onClick = onClick,
    )
}

/** 普通流程步骤 2: 连接类型 —— 选项来自 [DialectType.supportedConnectionTypes]，选择直接回写调用方 */
@Composable
private fun ConnectionTypeStep(
    editingConnection: ConnectionConfig,
    modifier: Modifier = Modifier,
    stepIndex: Int,
    totalSteps: Int,
    onConnectionTypeChange: (ConnectionType) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val availableTypes = editingConnection.dialect.supportedConnectionTypes

    StepLayout(
        modifier = modifier,
        title = str(StrKey.CONNECTION_TYPE),
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text(str(StrKey.PREV_STEP)) }
                Spacer(Modifier.width(8.dp))
                WinButton(onClick = onNext, shape = SundaysPalette.buttonShape) {
                    Text(str(StrKey.NEXT_STEP))
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        },
    ) {
        Text(str(StrKey.SELECT_CONNECTION_MODE), style = MaterialTheme.typography.labelLarge)
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
        title = str(connectionType.titleKey),
        description = connectionType.descriptionKey?.let { str(it) }.orEmpty(),
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
    modifier: Modifier = Modifier,
    stepIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    dialectForms: DialectFormRegistry = DialectFormRegistry.EMPTY,
) {
    val canProceed = buildJdbcUrl(editingConnection).isNotBlank()

    /** 回写字段变更并重建 URL —— 显式 query 参数（`?useSSL=false&...`）原样保留 */
    fun apply(next: ConnectionConfig) {
        val query = editingConnection.jdbcUrl.substringAfter('?', "")
        onUpdateEditingConnection(next.copy(jdbcUrl = buildJdbcUrl(next, query)))
    }

    /**
     * JDBC URL 输入框 → 字段：地址字段按 URL 回写，URL 经 [withParsedJdbcUrl] 归一化。
     *
     * 凭据的处置规则（URL 没写 userinfo 时不许清空已填的凭据）见 [withParsedJdbcUrl] ——
     * 那条判据住在可测的纯函数里，不埋在这个 Composable 里。
     */
    fun applyUrl(url: String) {
        onUpdateEditingConnection(editingConnection.withParsedJdbcUrl(url))
    }

    StepLayout(
        modifier = modifier,
        title = str(StrKey.CONNECTION_DETAILS),
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text(str(StrKey.PREV_STEP)) }
                Spacer(Modifier.width(8.dp))
                WinButton(onClick = onNext, enabled = canProceed, shape = SundaysPalette.buttonShape) {
                    Text(str(StrKey.NEXT_STEP))
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        },
    ) {
        // ── spec 分支优先，回退旧的枚举分支 ──
        //
        // 判据是「**有没有 spec**」，不是「方言是不是新的」：
        // 引擎比前端新（前端还没拿到 spec）、或者方言插件还没声明
        // `connectionForm`，都靠旧分支兜住（设计文档 §5.7 的 Q6 结论）。
        //
        // 旧分支**只许修 bug，不许加功能** —— 两套渲染一旦分叉，
        // 「MySQL 的表单到底按哪套渲染」就没人说得清了。
        val spec = dialectForms.of(editingConnection.dialect)
        val specForm = dialectForms.formFor(editingConnection.dialect, editingConnection.connectionType)

        if (spec != null && specForm != null) {
            ConnectionFormFields(
                form = specForm,
                spec = spec,
                connection = editingConnection,
                onFieldChange = { fieldId, value ->
                    if (fieldId == ConnectionFieldIds.JDBC_URL) {
                        // URL 输入框走**反解析**：改 URL 会覆盖其它字段。
                        applyUrl(value)
                    } else {
                        apply(editingConnection.withFieldValue(fieldId, value))
                    }
                },
                onAuthMethodChange = { method -> apply(editingConnection.copy(authMethod = method)) },
            )
        } else {
            when (editingConnection.connectionType) {
                ConnectionType.CLIENT_SERVER -> {
                    WinTextField(
                        value = editingConnection.host,
                        onValueChange = { apply(editingConnection.copy(host = it)) },
                        label = { Text(str(StrKey.HOST)) },
                        placeholder = { Text(str(StrKey.HOST_HINT)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Wifi, null) },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WinTextField(
                        value = editingConnection.port?.takeIf { it > 0 }?.toString() ?: "",
                        onValueChange = { apply(editingConnection.copy(port = it.filter(Char::isDigit).toIntOrNull())) },
                        label = { Text(str(StrKey.PORT)) },
                        placeholder = { Text(editingConnection.displayPort.toString()) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WinTextField(
                        value = editingConnection.database,
                        onValueChange = { apply(editingConnection.copy(database = it)) },
                        label = { Text(str(StrKey.DATABASE_NAME)) },
                        placeholder = { Text(str(StrKey.DATABASE_NAME_HINT)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Storage, null) },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WinTextField(
                        value = editingConnection.username,
                        onValueChange = { apply(editingConnection.copy(username = it)) },
                        label = { Text(str(StrKey.USERNAME)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    WinTextField(
                        value = editingConnection.password,
                        onValueChange = { apply(editingConnection.copy(password = it)) },
                        label = { Text(str(StrKey.PASSWORD)) },
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
                    val (labelKey, placeholderKey, icon) = embeddedFieldSpec(editingConnection)

                    WinTextField(
                        value = editingConnection.database,
                        onValueChange = { apply(editingConnection.copy(database = it)) },
                        label = { Text(labelKey?.let { str(it) } ?: DATABASE_NAME_LABEL_UNTRANSLATED) },
                        placeholder = { Text(str(placeholderKey)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(icon, null) },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(str(StrKey.JDBC_URL_DERIVED), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = winShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = editingConnection.jdbcUrl.ifBlank { str(StrKey.FIELDS_PENDING) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                // 自定义 JDBC URL 逃生舱：**只给一个 URL 输入框**。
                //
                // 为什么不给主机 / 端口 / 库名：这些字段在 CUSTOM_URL 模式下**没有意义** ——
                // URL 本身就是连接的全部定义。给一套字段再让用户手工拼 URL，
                // 等于让两处真相源打架（改字段会重写 URL，改 URL 会覆盖字段）。
                // 这里只把 URL 交给用户，字段那套留给 CLIENT_SERVER 等类型。
                ConnectionType.CUSTOM_URL -> {
                    Text(
                        text = str(StrKey.DESC_CUSTOM_URL),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    WinTextField(
                        value = editingConnection.jdbcUrl,
                        onValueChange = { onUpdateEditingConnection(editingConnection.copy(jdbcUrl = it)) },
                        label = { Text("JDBC URL") },
                        placeholder = { Text("jdbc:hive2://host:10000/db") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                    )
                }

                ConnectionType.UNKNOWN -> {}
            }
        }

        if (!canProceed) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = str(StrKey.REQUIRED_FIELDS_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * H2 内存库的字段名 —— **尚未收录进文案表**（表里有 `DATABASE`＝「数据库」，
 * 但没有完全对应的「数据库名称」）。键补齐后删掉本常量与 [embeddedFieldSpec] 的 null 分支。
 */
private const val DATABASE_NAME_LABEL_UNTRANSLATED = "数据库名称"

/**
 * 嵌入式 / 内存 / 文件型连接的单个目标字段描述（label / placeholder / 图标）。
 *
 * label / placeholder 以**键**返回：本函数是普通 Kotlin 代码，`str()` 是 `@Composable`
 * 拿不到，由调用处（[CredentialsStep]）取文案后再交给 `WinTextField`。
 * label 为 `null` 表示该分支还没有对应键（见 [DATABASE_NAME_LABEL_UNTRANSLATED]）。
 */
private fun embeddedFieldSpec(
    connection: ConnectionConfig,
): Triple<StrKey?, StrKey, androidx.compose.ui.graphics.vector.ImageVector> =
    when (connection.dialect) {
        DialectType.H2 -> {
            if (connection.connectionType == ConnectionType.FILE_BASED) {
                Triple(StrKey.DATABASE_FILE_PATH, StrKey.DATABASE_FILE_PATH_HINT_H2, Icons.Default.FolderOpen)
            } else {
                Triple(null, StrKey.DATABASE_NAME_HINT, Icons.Default.Storage)
            }
        }

        DialectType.DUCKDB -> {
            Triple(
                StrKey.DATABASE_FILE_PATH_LABEL,
                StrKey.DATABASE_FILE_PATH_HINT_DUCKDB,
                Icons.Default.FolderOpen,
            )
        }

        DialectType.SQLITE -> {
            Triple(
                StrKey.DATABASE_FILE_PATH_LABEL,
                StrKey.DATABASE_FILE_PATH_HINT_SQLITE,
                Icons.Default.FolderOpen,
            )
        }

        else -> {
            Triple(StrKey.DATABASE, StrKey.DATABASE_NAME_HINT, Icons.Default.Storage)
        }
    }

/** 测试 & 保存步骤 (普通流程 4 / 快速连接 3) */
@Composable
private fun TestSaveStep(
    editingConnection: ConnectionConfig,
    modifier: Modifier = Modifier,
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
    val confirmLabel = if (isQuickConnect) str(StrKey.CONNECT) else str(StrKey.SAVE)
    val onConfirm: (ConnectionConfig) -> Unit =
        if (isQuickConnect) onQuickConnectDirect else onSave
    val canConfirm = buildJdbcUrl(editingConnection).isNotBlank()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    StepLayout(
        modifier = modifier,
        title = str(StrKey.TEST_AND_SAVE),
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                WinTextButton(onClick = onBack, shape = SundaysPalette.buttonShape) { Text(str(StrKey.PREV_STEP)) }
                Spacer(Modifier.width(8.dp))
                WinButton(
                    onClick = { onConfirm(editingConnection) },
                    enabled = canConfirm,
                    shape = SundaysPalette.buttonShape,
                ) {
                    Icon(
                        imageVector = if (isQuickConnect) Icons.Default.Bolt else Icons.Default.Save,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(confirmLabel)
                }
            }
        },
    ) {
        // 连接信息摘要
        Surface(
            modifier = Modifier.fillMaxWidth().uiBevel(raised = true),
            shape = winShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(str(StrKey.CONNECTION_SUMMARY), style = MaterialTheme.typography.labelLarge)
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
                        testResult =
                            try {
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
                    Text(str(StrKey.TESTING))
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(str(StrKey.TEST_CONNECTION))
                }
            }
        }

        // 测试结果
        testResult?.let { result ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().uiBevel(raised = true),
                shape = winShape(8.dp),
                color =
                    if (result.success) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint =
                            if (result.success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                            if (result.success) {
                                str(StrKey.CONNECT_SUCCESS)
                            } else {
                                str(StrKey.CONNECT_FAILED_WITH_REASON, result.message)
                            },
                        color =
                            if (result.success) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                    )
                }
            }
        }
    }
}

/** 连接信息摘要 */
@Composable
private fun ConnectionSummary(connection: ConnectionConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SummaryRow(str(StrKey.NAME), connection.name)
        SummaryRow(str(StrKey.DIALECT_SUMMARY), connection.dialect.name)
        SummaryRow(str(StrKey.TYPE_LABEL), connection.connectionType.name)
        if (connection.connectionType == ConnectionType.CLIENT_SERVER) {
            SummaryRow(str(StrKey.HOST_SUMMARY), connection.host)
            SummaryRow(str(StrKey.PORT), connection.displayPort.toString())
            if (connection.database.isNotBlank()) {
                SummaryRow(str(StrKey.DATABASE), connection.database)
            }
            SummaryRow(str(StrKey.USER), connection.username)
        } else if (connection.database.isNotBlank()) {
            SummaryRow(
                if (connection.connectionType == ConnectionType.IN_MEMORY) {
                    str(StrKey.DATABASE)
                } else {
                    str(StrKey.TARGET)
                },
                connection.database,
            )
        }
        if (connection.jdbcUrl.isNotBlank()) {
            SummaryRow("JDBC URL", connection.jdbcUrl)
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
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
data class TestResult(
    val success: Boolean,
    val message: String = "",
)

/**
 * 步骤布局 —— **头部固定 / 内容可滚 / 底部按钮固定**。
 *
 * ## 为什么底部按钮必须钉在滚动区**之外**
 *
 * 原实现把「上一步 / 下一步」那一行放在**内容里**，于是它跟着内容一起滚。
 * 内容比窗口高时（5 个方言选项 + 名称框就要 ~700dp；小屏笔记本的工作区只有
 * 600 出头 dp），按钮被推到视口之外 —— 用户看得见「这一步有 5 个方言要选」，
 * 却**看不到也点不到**「下一步」，向导就此走不下去。
 *
 * 真机实测（本机 1536×864 @125% DPI，工作区 691dp）：第 1 步的「下一步」
 * 落在窗口底边之下，**不可见、滚不出来、Tab 也聚焦不到**。
 *
 * 改成「内容可滚 + 按钮固定」之后：
 *
 * - 按钮**永远**在视口里 —— 窗口多小都点得到；
 * - 内容再长也只影响滚动区，不影响操作区；
 * - Tab 顺序也随之合理：先走完表单，再落到按钮上。
 *
 * ## 为什么整列上**不能**再挂 `verticalScroll`
 *
 * `verticalScroll` 会把内容的最大高度约束改成「无限高」，而 `weight` 正是靠
 * 「父容器还剩多少高度」来分配空间的 —— 两者叠在一起，`weight` 就分不出剩余了
 * （内容区会塌成 0 高，按钮反而被顶到屏幕外）。
 *
 * 所以滚动**下沉到本函数内部**：整列保持有界高度，内容区自己滚。
 * 整列的高度由调用方给（[ConnectionWizardContent] 用 `weight(1f)` 分配剩余空间）。
 */
@Composable
private fun StepLayout(
    title: String,
    step: Int,
    totalSteps: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** 底部固定操作区 —— 「上一步 / 下一步」等按钮。**不参与滚动**。 */
    footer: @Composable ColumnScope.() -> Unit,
    /** 内容区 —— 随窗口高度不够而滚动。 */
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
                    text = str(StrKey.WIZARD_STEP, step, totalSteps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WinIconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = str(StrKey.CLOSE))
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
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                if (index < step) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                winShape(2.dp),
                            ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 内容区：可滚。`fillMaxHeight` 让它**填满**剩余高度 —— 否则内容短的时候
        // 这一块会缩成内容高度，底部按钮就跟着往上跑而不是钉在底部。
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
        ) {
            content()
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 底部操作区：固定，不参与滚动
        footer()
    }
}

// ============================================================================
// 键盘可达性：testTag
//
// 真窗口 GUI 走查卡死在这里（见 TEST_CASES.md §9.9）：列表项与向导卡片都用
// `Modifier.clickable` —— 它在非触摸模式下**会**加入 Tab 序，却**不画任何焦点指示**，
// 于是「Tab 了几次、焦点在哪」只能靠截图猜，猜错就误触别的功能。
//
// 下面这三个 tag 让「点某个具体控件」不再依赖焦点计数。
// ============================================================================

/** 连接列表项（点它 = 选中）。 */
fun connectionCardTag(connectionId: String) = "connectionCard_$connectionId"

/** 连接总览面板根列 —— 量布局用，见 TEST_CASES.md §9.12。 */
const val CONNECT_OVERVIEW_PANEL_TAG = "connectOverviewPanel"

/** 连接总览面板的「连接信息 / 连接状态」卡片。 */
const val CONNECT_OVERVIEW_CARD_TAG = "connectOverviewCard"

/** 连接总览面板底部的「删除 / 编辑 / 连接」按钮行 —— 首屏唯一的连接入口。 */
const val CONNECT_OVERVIEW_ACTIONS_TAG = "connectOverviewActions"

/** 快速连接步的方言卡（MySQL / PostgreSQL / H2 / DuckDB / SQLite）。 */
fun quickConnectCardTag(title: String) = "quickConnectCard_$title"

/** 向导里的单选项（方言 / 连接类型）。 */
fun selectableOptionTag(title: String) = "selectableOption_$title"
