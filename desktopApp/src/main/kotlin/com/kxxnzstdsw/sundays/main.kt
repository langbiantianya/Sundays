package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.client.grpc.GrpcClientConfig
import com.kxxnzstdsw.client.grpc.GrpcEngineClient
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.AddConnectionDialog
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import com.kxxnzstdsw.sundays.navigation.AppDestination
import com.kxxnzstdsw.sundays.ui.SundaysTheme

/**
 * KMP Desktop 应用入口.
 *
 * **与引擎的集成方式**: UI 只面向 [EngineClient] 接口编程，由本文件这个**装配点**决定引擎的调用方式：
 *
 * | 模式 | 触发条件 | 实现 | 说明 |
 * |---|---|---|---|
 * | **Direct（默认）** | 未设置 `-Dsundays.engine.endpoint` | `IdbEngine()` | 引擎与 UI 同 JVM，零序列化、零子进程 |
 * | **gRPC** | `-Dsundays.engine.endpoint=host:port`（或 `unix://path` / `pipe:name`） | `GrpcEngineClient` | 引擎跑在独立进程，经 gRPC 调用 |
 *
 * 两种模式**调用代码完全相同** —— `ConnectionSession` / `DatabaseBrowserState` 都只持有接口。
 * gRPC 模式需先启动引擎进程：`java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051`。
 *
 * **连接生命周期**: 连接列表/向导的「连接」→ [EngineClient.testConnection](建池 + isValid, 即初始化连接);
 * 「断开」/ 删除连接 → [EngineClient.disconnect](释放该配置的连接池); 窗口关闭 → [EngineClient.close].
 * 连接列表 / 向导 / 引擎会话状态全部由 [ConnectionSession] 持有, [MainScreen] 只做绑定与顶层导航.
 *
 * **顶层导航**: 连接管理 ↔ 数据库浏览. 切换目标会重建屏幕; 各自的内部状态由屏幕自身 `remember` 持有.
 * 导航条本体 (`TopNavBar` / `AppDestination`) 与应用主题 (`SundaysTheme`) 是平台无关的纯 UI,
 * 已上移到 `:shared` 的 `commonMain` —— 本文件只剩「平台窗口 + 引擎实现装配」。
 */
fun main() = application {
    // 注册内置编辑器语言（SQL / Lua）+ formatter —— 幂等，启动时调一次。
    // 不注册则 CodeLanguageRegistry.get("sql") 返回 null，编辑器退化为无高亮纯文本。
    registerBuiltinEditors()
    val engine: EngineClient = createEngineClient()
    Window(
        onCloseRequest = {
            engine.close()
            exitApplication()
        },
        title = "sundays",
    ) {
        SundaysTheme {
            MainScreen(engine)
        }
    }
}

/**
 * 按系统属性 `-Dsundays.engine.endpoint` 选择引擎实现。
 *
 * - 未设置 / 空白 → 同进程 [IdbEngine]（默认，零 IPC 开销）
 * - 设置为 `host:port` / `tcp://host:port` / `unix://<path>` / `pipe:<name>` →
 *   跨进程 [GrpcEngineClient]（需要引擎进程已在该端点监听）
 *
 * 两种实现都实现同一个 [EngineClient]，因此这里的选择对下游状态机完全透明。
 */
internal fun createEngineClient(): EngineClient {
    val endpoint = System.getProperty(ENDPOINT_PROPERTY).orEmpty().trim()
    if (endpoint.isEmpty()) return IdbEngine()
    val config = GrpcClientConfig.fromTarget(endpoint)
    return GrpcEngineClient.connect(config)
}

/** 选择 gRPC 引擎端点的系统属性名。 */
const val ENDPOINT_PROPERTY = "sundays.engine.endpoint"

/**
 * 顶层屏幕 —— 导航条 + 当前目标屏幕。
 *
 * **导航模型：连接管理 ↔ 数据库浏览（多 sheet）**。
 *
 * - **首屏** = 连接管理（左侧列表 + 右侧总览 / 向导）；无 TopNavBar 渲染。
 * - **第二屏** = 数据库浏览，多 sheet：
 *   - 每个 sheet 一个标签，对应一条已建立的连接（独立的 `DatabaseBrowserState` + 左树 + 预览标签页）
 *   - 标签条最左边是 `+` 入口 → 弹出 [AddConnectionDialog]，与 `ConnectionManagerScreen`
 *     内的快速连接 / 添加连接配置同源（共享 `ConnectionWizardContent`）。
 * - 顶层 `TopNavBar` 仅在第二屏渲染（只渲染 `数据库浏览` chip —— 不显示「连接管理」入口，
 *   沿用「不要在标签中显示连接管理」约定）。
 * - 首屏「连接」按钮 / 弹窗保存 / 弹窗快速连接 任一行为 → 都同步调用 [ConnectionSession.openSheet]
 *   + 切到第二屏 + `connect()` 触发引擎建池（异步，`DatabaseBrowserScreen` 观察
 *   `CONNECTING → CONNECTED` 状态）。
 *
 * `internal` 而非 `private`：`MainScreenNavTest` 需要渲染它来验证顶层导航切换
 * （导航状态由本函数持有，无法从外部注入）。
 */
@Composable
internal fun MainScreen(engine: EngineClient) {
    val scope = rememberCoroutineScope()
    val session = remember(engine) { ConnectionSession(engine, scope) }
    // 浏览状态按 sheet id 各自持有（而非 DatabaseBrowserScreen 内部 remember）：
    // sheet 关闭时该 state 也随同 GC；且 releasePools 的异步 disconnect 能跑在长生命周期 scope 上
    val browsers = remember { mutableMapOf<String, DatabaseBrowserState>() }
    var destination by remember { mutableStateOf(AppDestination.CONNECTIONS) }
    // 「+ 添加连接」弹窗开关 —— 仅在第二屏渲染（与 destination 联动）
    var addDialogVisible by remember { mutableStateOf(false) }

    // 首屏 sheet 变更：openSheets 非空 → 切到第二屏；空 → 切回首屏
    // 这样 ConnectManager 「连接」按钮和 AddConnectionDialog 完成后都能触发自动切换。
    LaunchedEffect(session.openSheets.isEmpty()) {
        destination = if (session.openSheets.isEmpty()) {
            AppDestination.CONNECTIONS
        } else {
            AppDestination.DATABASE
        }
    }

    val wizard = session.wizard
    val openSheetIds = session.openSheets
    val activeSheetId = session.activeSheetId

    Column(modifier = Modifier.fillMaxSize()) {
        if (destination == AppDestination.DATABASE) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when (destination) {
            AppDestination.CONNECTIONS -> ConnectionManagerScreen(
                connections = session.connectionList.connections,
                selectedConnection = session.selectedConnection,
                editingConnection = wizard.editingConnection,
                wizardStep = wizard.step,
                wizardFlow = wizard.flow,
                connectionStatuses = session.statuses,
                onSelectConnection = session::select,
                onNewConnection = session::newConnection,
                onQuickConnect = session::quickConnect,
                onEditConnection = session::edit,
                onSaveConnection = session::save,
                onQuickConnectDirect = session::quickConnectDirect,
                onDeleteConnection = session::delete,
                onCancelEdit = session::cancelEdit,
                onWizardNext = session::goToStep,
                onWizardBack = session::back,
                onUpdateEditingConnection = session::updateEditing,
                onTestConnection = session::testConnection,
                // 总览面板「连接」 → 建池 + 打开 sheet + 切到第二屏
                onConnect = { cfg ->
                    session.connect(cfg)
                    session.openSheet(cfg)
                },
                onDisconnect = session::disconnect,
                modifier = Modifier.fillMaxSize(),
            )
            AppDestination.DATABASE -> {
                // 按 sheet id 懒创建浏览器 state —— sheet 关闭时 entry 留作 stale（不会泄漏：
                // DatabaseBrowserState 持有 engine/scope 引用，无生命周期短句的资源）
                val sheetConfigs = openSheetIds  // List<ConnectionConfig>，transient + 持久化 sheet 都直接可用
                sheetConfigs.forEach { cfg ->
                    if (browsers[cfg.id] == null) {
                        browsers[cfg.id] = DatabaseBrowserState(engine, scope)
                    }
                }
                val sheets = sheetConfigs.map { cfg ->
                    val browser = browsers.getValue(cfg.id)
                    SheetDescriptor(
                        connection = cfg,
                        browser = browser,
                        status = session.statuses[cfg.id] ?: ConnectionStatus(),
                    )
                }
                DatabaseBrowserScreen(
                    sheets = sheets,
                    activeSheetId = activeSheetId,
                    connections = session.connectionList.connections,
                    onSelectSheet = session::selectSheet,
                    onCloseSheet = { id ->
                        // 关 sheet 时一并断开连接：浏览器 SCHEMA.LIST 在多 database 下建的池
                        // 由 releasePools 负责；连接管理层的池（testConnection 初始化的那个）
                        // 由 disconnect 负责 —— 两者 key 不同，须都释放，否则留孤儿池。
                        val cfg = sheetConfigs.firstOrNull { it.id == id }
                        browsers[id]?.releasePools()
                        if (cfg != null) session.disconnect(cfg)
                        session.closeSheet(id)
                    },
                    onAddSheet = { addDialogVisible = true },
                    onConnect = session::connect,
                    onDisconnect = session::disconnect,
                    modifier = Modifier.fillMaxSize(),
                )
                if (addDialogVisible) {
                    AddConnectionDialog(
                        // 弹窗复用 ConnectionManagerScreen —— 数据 / 状态全部来自 session，与首屏同源
                        connections = session.connectionList.connections,
                        selectedConnection = session.selectedConnection,
                        editingConnection = wizard.editingConnection,
                        wizardStep = wizard.step,
                        wizardFlow = wizard.flow,
                        connectionStatuses = session.statuses,
                        // 弹窗上下文 = 在第二屏：点击左侧连接项 = 仅切到详情（停在弹窗里），
                        // 由用户显式点右栏「连接」按钮（onConnect 包装）才跳过去并添加 sheet。
                        onSelectConnection = session::select,
                        onNewConnection = session::newConnection,
                        onQuickConnect = session::quickConnect,
                        onEditConnection = session::edit,
                        onSaveConnection = { cfg ->
                            session.save(cfg)
                            addDialogVisible = false
                        },
                        onQuickConnectDirect = { cfg ->
                            session.quickConnectDirect(cfg)
                            addDialogVisible = false
                        },
                        onDeleteConnection = session::delete,
                        onCancelEdit = session::cancelEdit,
                        onWizardNext = session::goToStep,
                        onWizardBack = session::back,
                        onUpdateEditingConnection = session::updateEditing,
                        // 弹窗右栏「连接」按钮：在建池的同时把 cfg 推入 openSheets（添加新标签）
                        // 并关弹窗 —— 即「点击连接按钮后才跳转过去」。
                        onConnect = { cfg ->
                            session.connect(cfg)
                            session.openSheet(cfg)
                            addDialogVisible = false
                        },
                        onDisconnect = session::disconnect,
                        onTestConnection = session::testConnection,
                        onDismiss = {
                            // 关闭弹窗 = 丢弃向导进度，回到 IDLE
                            session.cancelEdit()
                            addDialogVisible = false
                        },
                    )
                }
            }
        }
    }
}
