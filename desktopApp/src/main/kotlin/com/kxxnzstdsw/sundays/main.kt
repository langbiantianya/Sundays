package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.navigation.AppDestination
import com.kxxnzstdsw.sundays.navigation.TopNavBar
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
 * `internal` 而非 `private`：`MainScreenNavTest` 需要渲染它来验证顶层导航切换
 * （导航状态由本函数持有，无法从外部注入）。
 */
@Composable
internal fun MainScreen(engine: EngineClient) {
    val scope = rememberCoroutineScope()
    val session = remember(engine) { ConnectionSession(engine, scope) }
    // 浏览状态在此持有（而非 DatabaseBrowserScreen 内部）：切到「连接管理」再切回来时
    // 已打开的标签页不丢失；且 releasePools 的异步 disconnect 能跑在这个长生命周期 scope 上
    val browser = remember(engine) { DatabaseBrowserState(engine, scope) }
    var destination by remember { mutableStateOf(AppDestination.CONNECTIONS) }
    val wizard = session.wizard

    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBar(
            current = destination,
            onSelect = { destination = it },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                onConnect = session::connect,
                onDisconnect = session::disconnect,
                modifier = Modifier.fillMaxSize(),
            )
            AppDestination.DATABASE -> DatabaseBrowserScreen(
                browser = browser,
                connections = session.connectionList.connections,
                selectedConnection = session.selectedConnection,
                status = session.selectedConnection?.let { session.statuses[it.id] }
                    ?: ConnectionStatus(),
                onSelectConnection = session::select,
                onConnect = session::connect,
                onDisconnect = session::disconnect,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
