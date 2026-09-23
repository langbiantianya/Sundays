package com.kxxnzstdsw.sundays

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStatus

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

/**
 * 顶层导航条 —— 切换 [AppDestination].
 *
 * 设计要点:
 * - 横向 `Row`, 左对齐, 每个目标是一个 chip; 当前目标高亮.
 * - 连接列表由 [ConnectionSession] 持有 (在 Nav 之上), 切换 destination 不会丢失连接.
 */
@Composable
private fun TopNavBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppDestination.entries.forEach { dest ->
                NavChip(
                    destination = dest,
                    selected = current == dest,
                    onClick = { onSelect(dest) },
                )
            }
        }
    }
}

@Composable
private fun NavChip(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon: ImageVector = when (destination) {
        AppDestination.CONNECTIONS -> Icons.Filled.Storage
        AppDestination.DATABASE -> Icons.Filled.TableChart
    }
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        color = bg.copy(alpha = if (selected) 1f else 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg)
            Text(
                text = destination.label,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun isSystemInDarkTheme(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()
