package com.kxxnzstdsw.sundays.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinTextButton

/**
 * 「添加数据库连接」弹窗 —— 在已有界面（例如 [DatabaseBrowserScreen] 的「＋」按钮）调用，
 * 弹出与首屏 [ConnectionManagerScreen] **完全一致** 的界面（左侧连接列表 + 右侧向导 / 总览）。
 *
 * 设计要点：
 * - 复用 [ConnectionManagerScreen] 作为内容 —— 弹窗里的左侧列表 / 右侧向导 / 选中连接总览
 *   / 入口按钮 / 步骤指示器全部与首屏同源；只是渲染在 [Dialog] 内。
 * - 行为契约（选中连接、编辑、新建 / 快速连接向导、保存、快速连接直接连库）由调用方按
 *   [ConnectionSession] 的同一套方法接线 —— 与首屏的接线逐项一致。
 * - 弹窗尺寸 = 父容器（窗口）尺寸，`fillMaxSize()` 占满整窗；`ConnectionManagerScreen`
 *   内部的 verticalScroll 处理字段过多时的滚动。
 * - 标题 / 关闭按钮由本组件提供；其余交互入口（新建 / 快速连接 / 下一步 / 保存 / 连接等）
 *   全部沿用 [ConnectionManagerScreen] 自身的按钮。
 *
 * 实现说明：这里用底层 [Dialog] 而不是 Material3 `AlertDialog`。Material3 的 `AlertDialog` /
 * `BasicAlertDialog` 默认实现会把内容包在 `sizeIn(minWidth = 280.dp, maxWidth = 560.dp)` 的
 * 容器里（`DefaultBasicAlertDialogOverride`），宽度上限恒为 560dp —— 与
 * `DialogProperties.usePlatformDefaultWidth` 无关，因此无法占满窗口。改用 [Dialog] +
 * `usePlatformDefaultWidth = false`（宽度约束 = 父窗口）后自行提供标题栏，才能真正铺满。
 *
 * @param onDismiss 弹窗关闭请求（外部点击 / Esc / 关闭按钮）。调用方通常在此把向导重置为 IDLE。
 */
@Composable
fun AddConnectionDialog(
    connections: List<ConnectionConfig>,
    selectedConnection: ConnectionConfig?,
    editingConnection: ConnectionConfig?,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    connectionStatuses: Map<String, ConnectionStatus>,
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
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // 解除平台默认宽度上限：内容拿到的宽度约束 = 父窗口宽度（RootMeasurePolicy）。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏：标题 + 关闭按钮（Material3 AlertDialog 原先自带的这两个槽位，这里自行提供）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "添加数据库连接",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    WinTextButton(onClick = onDismiss, shape = SundaysPalette.buttonShape) { Text("关闭") }
                }

                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 弹窗内容占满剩余空间；ConnectionManagerScreen 外层 Row 为 fillMaxHeight，
                // 这里把宽度也撑满（默认由父容器决定宽度）。
                ConnectionManagerScreen(
                    connections = connections,
                    selectedConnection = selectedConnection,
                    editingConnection = editingConnection,
                    wizardStep = wizardStep,
                    wizardFlow = wizardFlow,
                    connectionStatuses = connectionStatuses,
                    onSelectConnection = onSelectConnection,
                    onNewConnection = onNewConnection,
                    onQuickConnect = onQuickConnect,
                    onEditConnection = onEditConnection,
                    onSaveConnection = onSaveConnection,
                    onQuickConnectDirect = onQuickConnectDirect,
                    onDeleteConnection = onDeleteConnection,
                    onCancelEdit = onCancelEdit,
                    onWizardNext = onWizardNext,
                    onWizardBack = onWizardBack,
                    onUpdateEditingConnection = onUpdateEditingConnection,
                    onTestConnection = onTestConnection,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
