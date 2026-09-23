package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.TestResult
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * KMP Desktop 应用入口 (v2.9 双模式架构).
 *
 * **与引擎的集成方式**: 直接依赖 `:engine` 模块, 通过 [IdbEngine] facade 直接调用引擎方法,
 * 不需要启动子进程、不需要 gRPC channel. 引擎和 UI 共享同一个 JVM, 共享同一组连接池和方言插件.
 */
fun main() = application {
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = IdbEngine()
    Window(
        onCloseRequest = {
            engine.close()
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
    var connectionList by remember { mutableStateOf(ConnectionStorage.load()) }
    var selectedConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
    var wizardState by remember {
        mutableStateOf(
            WizardState(
                editingConnection = null,
                wizardStep = WizardStep.IDLE,
                flow = WizardFlow.NORMAL,
            )
        )
    }

    ConnectionManagerScreen(
        connections = connectionList.connections,
        selectedConnection = selectedConnection,
        editingConnection = wizardState.editingConnection,
        wizardStep = wizardState.wizardStep,
        wizardFlow = wizardState.flow,
        onSelectConnection = { selectedConnection = it },
        onNewConnection = {
            wizardState = WizardState(
                editingConnection = ConnectionConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "新连接",
                ),
                wizardStep = WizardStep.BASIC_INFO,
                flow = WizardFlow.NORMAL,
            )
        },
        onQuickConnect = {
            wizardState = WizardState(
                editingConnection = ConnectionConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "新连接",
                ),
                wizardStep = WizardStep.QUICK_CONNECT,
                flow = WizardFlow.QUICK_CONNECT,
            )
        },
        onEditConnection = { conn ->
            wizardState = WizardState(
                editingConnection = conn,
                wizardStep = WizardStep.BASIC_INFO,
                flow = WizardFlow.NORMAL,
            )
        },
        onSaveConnection = { config ->
            connectionList = ConnectionStorage.upsert(config)
            selectedConnection = config
            wizardState = WizardState(
                editingConnection = null,
                wizardStep = WizardStep.IDLE,
                flow = WizardFlow.NORMAL,
            )
        },
        onQuickConnectDirect = { config ->
            // 快速连接：不写入 ConnectionStorage，仅设为当前选中
            selectedConnection = config
            wizardState = WizardState(
                editingConnection = null,
                wizardStep = WizardStep.IDLE,
                flow = WizardFlow.NORMAL,
            )
        },
        onDeleteConnection = { id ->
            connectionList = ConnectionStorage.delete(id)
            if (selectedConnection?.id == id) {
                selectedConnection = null
            }
        },
        onCancelEdit = {
            wizardState = WizardState(
                editingConnection = null,
                wizardStep = WizardStep.IDLE,
                flow = WizardFlow.NORMAL,
            )
        },
        onWizardNext = { step ->
            wizardState = wizardState.copy(wizardStep = step)
        },
        onWizardBack = {
            val prevStep = when (wizardState.flow) {
                WizardFlow.QUICK_CONNECT -> when (wizardState.wizardStep) {
                    WizardStep.QUICK_CONNECT -> WizardStep.IDLE
                    WizardStep.CREDENTIALS -> WizardStep.QUICK_CONNECT
                    WizardStep.TEST_SAVE -> WizardStep.CREDENTIALS
                    else -> WizardStep.IDLE
                }
                WizardFlow.NORMAL -> when (wizardState.wizardStep) {
                    WizardStep.BASIC_INFO -> WizardStep.IDLE
                    WizardStep.CONNECTION_TYPE -> WizardStep.BASIC_INFO
                    WizardStep.CREDENTIALS -> WizardStep.CONNECTION_TYPE
                    WizardStep.TEST_SAVE -> WizardStep.CREDENTIALS
                    else -> WizardStep.IDLE
                }
                else -> WizardStep.IDLE
            }
            wizardState = wizardState.copy(wizardStep = prevStep)
        },
        onUpdateEditingConnection = { config ->
            wizardState = wizardState.copy(editingConnection = config)
        },
        onTestConnection = { config ->
            // 直连引擎（非 gRPC / 非子进程）：启用/复用连接池并做一次 isValid 校验。
            // 只传 jdbcUrl + 凭据，方言由 URL scheme 反查。
            val result = engine.testConnection(
                jdbcUrl = config.jdbcUrl,
                user = config.username,
                password = config.password,
            )
            TestResult(success = result.ok, message = result.error)
        },
        modifier = Modifier.fillMaxSize().safeContentPadding(),
    )
}

private data class WizardState(
    val editingConnection: ConnectionConfig?,
    val wizardStep: WizardStep,
    val flow: WizardFlow,
)

@Composable
private fun isSystemInDarkTheme(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()
