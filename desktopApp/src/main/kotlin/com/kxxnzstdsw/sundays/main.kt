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
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.navigation.AppDestination
import com.kxxnzstdsw.sundays.navigation.TopNavBar
import com.kxxnzstdsw.sundays.ui.SundaysTheme

/**
 * KMP Desktop 应用入口 (v2.12 双模式架构).
 *
 * **与引擎的集成方式**: 直接依赖 `:engine` 模块, 通过 [IdbEngine] facade 直接调用引擎方法,
 * 不需要启动子进程、不需要 gRPC channel. 引擎和 UI 共享同一个 JVM, 共享同一组连接池和方言插件
 * (方言 JAR + JDBC 驱动由 `desktopApp/build.gradle.kts` 的 `runtimeOnly` 依赖上到应用类路径,
 * `DialectLoader` 的 classpath SPI 扫描随 `IdbEngine()` 构造 bootstrap).
 *
 * **连接生命周期**: 连接列表/向导的「连接」→ [IdbEngine.testConnection](建池 + isValid, 即初始化连接);
 * 「断开」/ 删除连接 → [IdbEngine.disconnect](释放该配置的连接池); 窗口关闭 → [IdbEngine.close].
 * 连接列表 / 向导 / 引擎会话状态全部由 [ConnectionSession] 持有, [MainScreen] 只做绑定与顶层导航.
 *
 * **顶层导航**: 连接管理 ↔ 数据库浏览. 切换目标会重建屏幕; 各自的内部状态由屏幕自身 `remember` 持有.
 * 导航条本体 (`TopNavBar` / `AppDestination`) 与应用主题 (`SundaysTheme`) 是平台无关的纯 UI,
 * 已上移到 `:shared` 的 `commonMain` —— 本文件只剩「平台窗口 + 引擎状态机接线」。
 */
fun main() = application {
    val engine = IdbEngine()
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
 * 顶层屏幕 —— 导航条 + 当前目标屏幕。
 *
 * `internal` 而非 `private`：`MainScreenNavTest` 需要渲染它来验证顶层导航切换
 * （导航状态由本函数持有，无法从外部注入）。
 */
@Composable
internal fun MainScreen(engine: IdbEngine) {
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
