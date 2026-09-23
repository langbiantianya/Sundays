package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen

/**
 * KMP Desktop 应用入口 (v2.12 双模式架构).
 *
 * **与引擎的集成方式**: 直接依赖 `:engine` 模块, 通过 [IdbEngine] facade 直接调用引擎方法,
 * 不需要启动子进程、不需要 gRPC channel. 引擎和 UI 共享同一个 JVM, 共享同一组连接池和方言插件
 * （方言 JAR + JDBC 驱动由 `desktopApp/build.gradle.kts` 的 `runtimeOnly` 依赖上到应用类路径,
 * `DialectLoader` 的 classpath SPI 扫描随 `IdbEngine()` 构造 bootstrap）。
 *
 * **连接生命周期**: 连接列表/向导的「连接」→ [IdbEngine.testConnection]（建池 + isValid，即初始化连接）；
 * 「断开」/ 删除连接 → [IdbEngine.disconnect]（释放该配置的连接池）；窗口关闭 → [IdbEngine.close]。
 * 连接列表 / 向导 / 引擎会话状态全部由 [ConnectionSession] 持有，`MainScreen` 只做绑定。
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
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        ) {
            MainScreen(engine)
        }
    }
}

@Composable
private fun MainScreen(engine: IdbEngine) {
    val scope = rememberCoroutineScope()
    val session = remember(engine) { ConnectionSession(engine, scope) }
    val wizard = session.wizard

    ConnectionManagerScreen(
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
        modifier = Modifier.fillMaxSize().safeContentPadding(),
    )
}

@Composable
private fun isSystemInDarkTheme(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()
