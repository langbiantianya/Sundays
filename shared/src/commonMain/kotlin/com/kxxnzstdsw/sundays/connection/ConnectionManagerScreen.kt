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
import androidx.compose.ui.unit.dp

/**
 * 连接管理器主界面 (v2.9).
 *
 * 布局: 左侧连接列表 + 右侧连接信息引导页面
 *
 * ## 引导流程
 *
 * | 流程 | 步骤序列 | 总步骤数 |
 * |---|---|---|
 * | 普通新建 | BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE | 4 |
 * | 快速连接 | QUICK_CONNECT → CREDENTIALS → TEST_SAVE | 3 |
 * | 编辑已有 | BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE | 4 |
 *
 * 调用方维护 [WizardState] (editingConnection + wizardStep + flow)，进入不同入口时**同步**设置 flow：
 *
 * ```kotlin
 * var wizardState by remember {
 *     mutableStateOf(WizardState(editingConnection = null, wizardStep = WizardStep.IDLE, flow = WizardFlow.NORMAL))
 * }
 *
 * ConnectionManagerScreen(
 *     connections = connectionList.connections,
 *     editingConnection = wizardState.editingConnection,
 *     wizardStep = wizardState.wizardStep,
 *     wizardFlow = wizardState.flow,
 *     onSelectConnection = { ... },
 *     onNewConnection = { wizardState = WizardState(newCfg, WizardStep.BASIC_INFO, WizardFlow.NORMAL) },
 *     onQuickConnect = { wizardState = WizardState(newCfg, WizardStep.QUICK_CONNECT, WizardFlow.QUICK_CONNECT) },
 *     onEditConnection = { conn -> wizardState = WizardState(conn, WizardStep.BASIC_INFO, WizardFlow.NORMAL) },
 * )
 * ```
 *
 * @param connections 所有保存的连接配置
 * @param selectedConnection 当前选中的连接 (用于左侧列表高亮)
 * @param editingConnection 当前正在编辑的连接 (null = 未编辑)
 * @param wizardStep 当前引导步骤
 * @param wizardFlow 当前引导流程类型 (用于步骤指示器自适应)
 * @param onSelectConnection 选择连接
 * @param onNewConnection 新建连接回调 (普通流程)
 * @param onQuickConnect 快速连接回调
 * @param onEditConnection 编辑已有连接回调
 * @param onSaveConnection 保存连接
 * @param onDeleteConnection 删除连接
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
    onDeleteConnection: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        // 左侧: 连接列表
        ConnectionListPanel(
            connections = connections,
            selectedConnection = selectedConnection,
            onSelectConnection = onSelectConnection,
            onNewConnection = onNewConnection,
            onQuickConnect = onQuickConnect,
            onEditConnection = onEditConnection,
            onDeleteConnection = onDeleteConnection,
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // 右侧: 连接信息引导页面
        ConnectionWizardPanel(
            editingConnection = editingConnection,
            wizardStep = wizardStep,
            wizardFlow = wizardFlow,
            onSaveConnection = onSaveConnection,
            onCancelEdit = onCancelEdit,
            onWizardNext = onWizardNext,
            onWizardBack = onWizardBack,
            onUpdateEditingConnection = onUpdateEditingConnection,
            onNewConnection = onNewConnection,
            onQuickConnect = onQuickConnect,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

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
    onSelectConnection: (ConnectionConfig?) -> Unit,
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
    onEditConnection: (ConnectionConfig) -> Unit,
    onDeleteConnection: (String) -> Unit,
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
                IconButton(onClick = onQuickConnect) {
                    Icon(Icons.Default.Bolt, contentDescription = "快速连接")
                }
                IconButton(onClick = onNewConnection) {
                    Icon(Icons.Default.Add, contentDescription = "新建连接")
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
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
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
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${connection.dialect.name} • ${connection.connectionString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    DropdownMenuItem(
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

/**
 * 右侧连接信息引导面板.
 */
@Composable
private fun ConnectionWizardPanel(
    editingConnection: ConnectionConfig?,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    onSaveConnection: (ConnectionConfig) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    onNewConnection: () -> Unit,
    onQuickConnect: () -> Unit,
    modifier: Modifier = Modifier,
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
            WizardStep.IDLE -> IdlePanel(
                onNewConnection = onNewConnection,
                onQuickConnect = onQuickConnect,
            )
            WizardStep.QUICK_CONNECT -> editingConnection?.let { config ->
                QuickConnectStep(
                    editingConnection = config,
                    stepIndex = 1,
                    totalSteps = totalSteps,
                    onSelectDialect = { newConfig ->
                        onUpdateEditingConnection(newConfig)
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
                    onNext = { onWizardNext(WizardStep.CONNECTION_TYPE) },
                    onCancel = onCancelEdit,
                )
            }
            WizardStep.CONNECTION_TYPE -> editingConnection?.let { config ->
                ConnectionTypeStep(
                    editingConnection = config,
                    stepIndex = stepIndexOf(wizardStep, wizardFlow),
                    totalSteps = totalSteps,
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
                    onSave = onSaveConnection,
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

/** 空闲状态面板 */
@Composable
private fun IdlePanel(
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
        Button(onClick = onQuickConnect) {
            Icon(Icons.Default.Bolt, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("快速连接")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onNewConnection) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("新建连接")
        }
    }
}

/** 快速连接步骤 — 预置方言快捷入口 */
@Composable
private fun QuickConnectStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onSelectDialect: (ConnectionConfig) -> Unit,
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
                onClick = {
                    onSelectDialect(
                        editingConnection.copy(
                            dialect = DialectType.MYSQL,
                            connectionType = ConnectionType.CLIENT_SERVER,
                            port = 3306,
                        )
                    )
                },
            )
            QuickConnectCard(
                title = "PostgreSQL",
                description = "客户端-服务器模式",
                port = 5432,
                icon = Icons.Default.Storage,
                onClick = {
                    onSelectDialect(
                        editingConnection.copy(
                            dialect = DialectType.POSTGRESQL,
                            connectionType = ConnectionType.CLIENT_SERVER,
                            port = 5432,
                        )
                    )
                },
            )
            QuickConnectCard(
                title = "H2",
                description = "内存数据库",
                port = null,
                icon = Icons.Default.Memory,
                onClick = {
                    onSelectDialect(
                        editingConnection.copy(
                            dialect = DialectType.H2,
                            connectionType = ConnectionType.IN_MEMORY,
                        )
                    )
                },
            )
            QuickConnectCard(
                title = "DuckDB",
                description = "嵌入式 OLAP",
                port = null,
                icon = Icons.Default.Analytics,
                onClick = {
                    onSelectDialect(
                        editingConnection.copy(
                            dialect = DialectType.DUCKDB,
                            connectionType = ConnectionType.EMBEDDED,
                        )
                    )
                },
            )
            QuickConnectCard(
                title = "SQLite",
                description = "文件数据库",
                port = null,
                icon = Icons.Default.FolderOpen,
                onClick = {
                    onSelectDialect(
                        editingConnection.copy(
                            dialect = DialectType.SQLITE,
                            connectionType = ConnectionType.FILE_BASED,
                        )
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
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
        shape = RoundedCornerShape(12.dp),
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
                    shape = RoundedCornerShape(4.dp),
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

/** 普通流程步骤 1: 基础信息 (名称 + 方言选择) */
@Composable
private fun BasicInfoStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(editingConnection) { mutableStateOf(editingConnection.name) }
    var dialect by remember(editingConnection) { mutableStateOf(editingConnection.dialect) }

    StepLayout(
        title = "基础信息",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
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
                    isSelected = dialect == d,
                    onClick = { dialect = d },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onNext,
                enabled = name.isNotBlank(),
            ) {
                Text("下一步")
                Icon(Icons.Default.ArrowForward, null)
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(dialect.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = when (dialect) {
                        DialectType.MYSQL -> "MySQL 客户端-服务器模式"
                        DialectType.POSTGRESQL -> "PostgreSQL 客户端-服务器模式"
                        DialectType.H2 -> "H2 内存/嵌入式数据库"
                        DialectType.DUCKDB -> "DuckDB 嵌入式 OLAP"
                        DialectType.SQLITE -> "SQLite 文件数据库"
                        DialectType.UNKNOWN -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 普通流程步骤 2: 连接类型 */
@Composable
private fun ConnectionTypeStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    var connectionType by remember(editingConnection) {
        mutableStateOf(editingConnection.connectionType)
    }

    val availableTypes = when (editingConnection.dialect) {
        DialectType.MYSQL, DialectType.POSTGRESQL -> listOf(ConnectionType.CLIENT_SERVER)
        DialectType.H2 -> listOf(ConnectionType.IN_MEMORY, ConnectionType.EMBEDDED, ConnectionType.FILE_BASED)
        DialectType.DUCKDB -> listOf(ConnectionType.EMBEDDED)
        DialectType.SQLITE -> listOf(ConnectionType.FILE_BASED)
        DialectType.UNKNOWN -> emptyList()
    }

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
                    isSelected = connectionType == ct,
                    onClick = { connectionType = ct },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onNext) {
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = when (connectionType) {
                        ConnectionType.CLIENT_SERVER -> "客户端-服务器"
                        ConnectionType.EMBEDDED -> "嵌入式"
                        ConnectionType.IN_MEMORY -> "内存数据库"
                        ConnectionType.FILE_BASED -> "文件数据库"
                        ConnectionType.UNKNOWN -> "未知"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (connectionType) {
                        ConnectionType.CLIENT_SERVER -> "需要主机地址和端口"
                        ConnectionType.EMBEDDED -> "数据库文件嵌入在应用中"
                        ConnectionType.IN_MEMORY -> "数据存储在内存中，重启后消失"
                        ConnectionType.FILE_BASED -> "数据存储在单个文件中"
                        ConnectionType.UNKNOWN -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 认证信息步骤 (普通流程 3 / 快速连接 2) */
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
    var host by remember(editingConnection) { mutableStateOf(editingConnection.host.ifBlank { "localhost" }) }
    var port by remember(editingConnection) { mutableStateOf(editingConnection.port?.toString()?.ifBlank { editingConnection.displayPort.toString() } ?: editingConnection.displayPort.toString()) }
    var database by remember(editingConnection) { mutableStateOf(editingConnection.database) }
    var username by remember(editingConnection) { mutableStateOf(editingConnection.username) }
    var password by remember(editingConnection) { mutableStateOf(editingConnection.password) }
    var filePath by remember(editingConnection) { mutableStateOf(editingConnection.filePath) }
    var jdbcUrl by remember(editingConnection) {
        mutableStateOf(
            editingConnection.jdbcUrl.ifBlank {
                buildJdbcUrl(
                    editingConnection.dialect,
                    "localhost",
                    editingConnection.displayPort.toString(),
                    "",
                    "",
                    "",
                )
            }
        )
    }

    /** 防止循环: 只在字段→URL 时为 true */
    var isSyncingFromFields by remember { mutableStateOf(false) }
    /** 防止循环: 只在 URL→字段 时为 true */
    var isSyncingFromUrl by remember { mutableStateOf(false) }

    /** 从 individual fields 同步到 URL（保留已有的 ?额外参数） */
    fun syncToUrl() {
        if (isSyncingFromUrl) return
        isSyncingFromFields = true
        val extraParams = jdbcUrl.substringAfter('?', "")
        val base = buildJdbcUrl(editingConnection.dialect, host, port, database, username, password)
        jdbcUrl = if (extraParams.isNotBlank()) "$base?$extraParams" else base
        isSyncingFromFields = false
    }

    /** 从 URL 同步到 individual fields */
    fun syncFromUrl(url: String) {
        if (isSyncingFromFields) return
        isSyncingFromUrl = true
        val parsed = parseJdbcUrl(url, editingConnection.dialect)
        host = parsed.host
        port = parsed.port
        database = parsed.database
        username = parsed.username
        password = parsed.password
        isSyncingFromUrl = false
    }

    fun apply() = onUpdateEditingConnection(
        editingConnection.copy(
            host = host,
            port = port.toIntOrNull(),
            database = database,
            username = username,
            password = password,
            filePath = filePath,
            jdbcUrl = jdbcUrl,
        )
    )

    StepLayout(
        title = "连接详情",
        step = stepIndex,
        totalSteps = totalSteps,
        onCancel = onCancel,
    ) {
        when (editingConnection.connectionType) {
            ConnectionType.CLIENT_SERVER -> {
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        syncToUrl()
                        apply()
                    },
                    label = { Text("主机地址") },
                    placeholder = { Text("例如: localhost 或 192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Wifi, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it.filter { c -> c.isDigit() }
                        syncToUrl()
                        apply()
                    },
                    label = { Text("端口") },
                    placeholder = { Text(editingConnection.displayPort.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = database,
                    onValueChange = {
                        database = it
                        syncToUrl()
                        apply()
                    },
                    label = { Text("数据库名") },
                    placeholder = { Text("例如: testdb") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Storage, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        apply()
                    },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        apply()
                    },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                OutlinedTextField(
                    value = jdbcUrl,
                    onValueChange = { url ->
                        jdbcUrl = url
                        syncFromUrl(url)
                        apply()
                    },
                    label = { Text("JDBC URL") },
                    placeholder = { Text("jdbc:mysql://host:3306/db?useSSL=false") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                )
            }

            ConnectionType.EMBEDDED, ConnectionType.IN_MEMORY -> {
                OutlinedTextField(
                    value = database,
                    onValueChange = {
                        database = it
                        onUpdateEditingConnection(editingConnection.copy(database = database))
                    },
                    label = { Text("数据库名称") },
                    placeholder = { Text("例如: testdb") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Storage, null) },
                )
            }

            ConnectionType.FILE_BASED -> {
                OutlinedTextField(
                    value = filePath,
                    onValueChange = {
                        filePath = it
                        onUpdateEditingConnection(editingConnection.copy(filePath = filePath))
                    },
                    label = { Text("文件路径") },
                    placeholder = { Text("例如: /path/to/database.db") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = database,
                    onValueChange = {
                        database = it
                        onUpdateEditingConnection(editingConnection.copy(database = database))
                    },
                    label = { Text("数据库名 (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Label, null) },
                )
            }

            ConnectionType.UNKNOWN -> {}
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onNext) {
                Text("下一步")
                Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

/** 从 individual fields 构建 JDBC URL */
private fun buildJdbcUrl(
    dialect: DialectType,
    host: String,
    port: String,
    database: String,
    username: String,
    password: String,
): String {
    if (host.isBlank()) return ""
    val scheme = when (dialect) {
        DialectType.MYSQL -> "jdbc:mysql"
        DialectType.POSTGRESQL -> "jdbc:postgresql"
        DialectType.H2 -> "jdbc:h2"
        DialectType.DUCKDB -> "jdbc:duckdb"
        DialectType.SQLITE -> "jdbc:sqlite"
        DialectType.UNKNOWN -> "jdbc"
    }
    val portPart = if (port.isNotBlank()) ":$port" else ""
    val dbPart = if (database.isNotBlank()) "/$database" else ""
    val credPart = if (username.isNotBlank()) "$username${if (password.isNotBlank()) ":$password" else ""}@" else ""
    return "$scheme://$credPart$host$portPart$dbPart"
}

/** 从 JDBC URL 解析 host / port / database（仅处理 MySQL / PostgreSQL） */
private data class UrlParts(
    val host: String,
    val port: String,
    val database: String,
    val username: String,
    val password: String,
)

private fun parseJdbcUrl(url: String, dialect: DialectType): UrlParts {
    if (url.isBlank()) return UrlParts("", "", "", "", "")
    try {
        val scheme = when (dialect) {
            DialectType.MYSQL -> "jdbc:mysql"
            DialectType.POSTGRESQL -> "jdbc:postgresql"
            else -> return UrlParts("", "", "", "", "")
        }
        val withoutScheme = url.removePrefix(scheme).removePrefix("://")

        // 提取 hostPart (credentials@host:port 或 host:port)
        val slashIdx = withoutScheme.indexOf('/')
        val hostPart = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
        val afterSlash = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1) else ""

        // 提取 ? 前的数据库部分
        val questionIdx = afterSlash.indexOf('?')
        val dbPart = if (questionIdx >= 0) afterSlash.substring(0, questionIdx) else afterSlash

        // 解析 host:port
        val atIdx = hostPart.indexOf('@')
        val credPart = if (atIdx >= 0) hostPart.substring(0, atIdx) else ""
        val hostColonPort = if (atIdx >= 0) hostPart.substring(atIdx + 1) else hostPart
        val colonIdx = hostColonPort.lastIndexOf(':')
        val h = if (colonIdx >= 0) hostColonPort.substring(0, colonIdx) else hostColonPort
        val p = if (colonIdx >= 0) hostColonPort.substring(colonIdx + 1) else ""

        // 解析 username:password
        val colonCredIdx = credPart.indexOf(':')
        val u = if (colonCredIdx >= 0) credPart.substring(0, colonCredIdx) else credPart
        val pw = if (colonCredIdx >= 0) credPart.substring(colonCredIdx + 1) else ""

        return UrlParts(h, p, dbPart, u, pw)
    } catch (_: Exception) {
        return UrlParts("", "", "", "", "")
    }
}

/** 测试 & 保存步骤 (普通流程 4 / 快速连接 3) */
@Composable
private fun TestSaveStep(
    editingConnection: ConnectionConfig,
    stepIndex: Int,
    totalSteps: Int,
    onSave: (ConnectionConfig) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
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
            Button(
                onClick = { isTesting = true },
                enabled = !isTesting,
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
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
            TextButton(onClick = onBack) { Text("上一步") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSave(editingConnection) }) {
                Icon(Icons.Default.Save, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存")
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
        }
        if (connection.database.isNotBlank()) {
            SummaryRow("数据库", connection.database)
        }
        if (connection.connectionType == ConnectionType.CLIENT_SERVER) {
            SummaryRow("用户", connection.username)
        }
        if (connection.filePath.isNotBlank()) {
            SummaryRow("文件路径", connection.filePath)
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
            IconButton(onClick = onCancel) {
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
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        content()
    }
}