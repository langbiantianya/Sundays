package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
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
 *
 * **功能标签页**:
 * - "连接管理" → [ConnectionManagerScreen]（左侧连接列表 + 右侧 4 步引导页面）
 * - "代码编辑器" → [EditorDemoScreen]（SQL/Lua 高亮 + 格式化 + 工具栏 + 右键菜单）
 * - "数据表格" → [TableDemoScreen]（1000 行用户数据 + 虚拟滚动 + 分页 + 右键菜单 + 详情面板）
 */
fun main() = application {
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // v2.9 直接模式 — 无 gRPC, 无子进程。App 内的 viewmodel 后续会持有此引用。
    val engine = IdbEngine()
    Window(
        onCloseRequest = {
            engine.close()
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            exitApplication()
        },
        title = "sundays",
    ) {
        // 应用主题（按系统设置自动选择浅/深色）
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
        ) {
            DemoApp()
        }
    }
}

/**
 * 演示 App 顶层 —— 顶部 tab 切换连接管理 / 代码编辑器 / 数据表格三个功能。
 */
@Composable
private fun DemoApp() {
    var selectedTab by remember { mutableStateOf(DemoTab.CONNECTION) }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DemoTabBar(selected = selectedTab, onSelect = { selectedTab = it })
        when (selectedTab) {
            DemoTab.CONNECTION -> ConnectionDemoScreen()
            DemoTab.EDITOR -> EditorDemoScreen()
            DemoTab.TABLE -> TableDemoScreen()
        }
    }
}

/** 演示 tab 枚举。 */
private enum class DemoTab(val label: String) {
    CONNECTION("连接管理"),
    EDITOR("代码编辑器"),
    TABLE("数据表格"),
}

/**
 * Tab 切换栏 —— 顶部单选分段按钮。
 */
@Composable
private fun DemoTabBar(selected: DemoTab, onSelect: (DemoTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        DemoTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = DemoTab.entries.size),
            ) {
                Text(tab.label)
            }
        }
    }
}

/**
 * 系统深色模式判定 —— 等价于 `androidx.compose.foundation.isSystemInDarkTheme()`，避免导入冲突。
 */
@Composable
private fun isSystemInDarkTheme(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

/**
 * 连接管理演示界面 —— 展示 [ConnectionManagerScreen] 的端到端用法。
 *
 * - 左侧连接列表（从 `~/.config/sundays/connection.json` 加载）
 * - 右侧 4 步引导页面（新建/编辑连接）
 * - 支持 MySQL / PostgreSQL / H2 / DuckDB / SQLite
 */
@Composable
private fun ConnectionDemoScreen() {
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
            // 根据当前流程分别处理上一步逻辑
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
    )
}

private data class WizardState(
    val editingConnection: ConnectionConfig?,
    val wizardStep: WizardStep,
    val flow: WizardFlow,
)

/**
 * 代码编辑器演示界面 —— 展示 SQL / Lua 双语言切换、格式化、高亮、右键菜单。
 *
 * **目的**：演示 `shared/` 模块中实现的代码编辑器组件在 desktopApp 中的接线方式。
 * 后续会被真正的数据库管理 UI（如 SQL 编辑器面板、Lua 造数脚本编辑器）替换。
 */
@Composable
private fun EditorDemoScreen() {
    com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors()
    var sql by remember { mutableStateOf("SELECT * FROM users WHERE id = 1;") }
    Column(modifier = Modifier.fillMaxSize()) {
        com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar(
            text = sql,
            onTextChange = { sql = it },
            languageId = "sql",
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
}

/**
 * 数据表格演示界面 —— 展示 1000 行用户数据的：
 * - **虚拟滚动**（LazyColumn）
 * - **行号 / 主键**（id 列）
 * - **分页**（PageSize 枚举）
 * - **databind**（rows 由 state 管理，变化自动重绘）
 * - **单行详情面板**（点击行 → 右侧详情）
 * - **每行每字段可选中**（SelectionContainer）
 * - **右键菜单**（外部注入菜单项）
 *
 * **目的**：演示 `shared/` 模块中实现的 DataTable 组件在 desktopApp 中的接线方式。
 * 后续会被真正的数据库查询结果展示面板替换。
 */
@Composable
private fun TableDemoScreen() {
    val rows = remember { generateDemoUsers(1000) }
    com.kxxnzstdsw.sundays.table.DataTable(
        columns = listOf(
            com.kxxnzstdsw.sundays.table.TableColumn("id", "ID", width = 80.dp),
            com.kxxnzstdsw.sundays.table.TableColumn("name", "姓名"),
            com.kxxnzstdsw.sundays.table.TableColumn("email", "邮箱"),
            com.kxxnzstdsw.sundays.table.TableColumn("age", "年龄", width = 80.dp),
        ),
        rows = rows,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 生成模拟用户数据 —— 真实场景下替换为 `engine.query("SELECT ...")` 的结果。
 */
private fun generateDemoUsers(count: Int): List<com.kxxnzstdsw.sundays.table.TableRow> =
    (1..count).map { i ->
        com.kxxnzstdsw.sundays.table.TableRow(
            id = i.toLong(),
            "id" to i,
            "name" to "user_$i",
            "email" to "user$i@example.com",
            "age" to (18 + i % 50),
        )
    }
