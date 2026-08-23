package com.kxxnzstdsw.Sundays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.kxxnzstdsw.Sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.Sundays.editor.ui.registerBuiltinEditors
import com.kxxnzstdsw.Sundays.table.DataTable
import com.kxxnzstdsw.Sundays.table.DataTableTheme
import com.kxxnzstdsw.Sundays.table.PageSize
import com.kxxnzstdsw.Sundays.table.TableColumn
import com.kxxnzstdsw.Sundays.table.TableRow
import com.kxxnzstdsw.Sundays.table.rememberContextMenuState
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * KMP Desktop 应用入口 (v2.9 双模式架构).
 *
 * **与引擎的集成方式**: 直接依赖 `:engine` 模块, 通过 [IdbEngine] facade 直接调用引擎方法,
 * 不需要启动子进程、不需要 gRPC channel. 引擎和 UI 共享同一个 JVM, 共享同一组连接池和方言插件.
 *
 * **演示功能**：顶部 [DemoTab] 提供两个演示切换 —
 * - "代码编辑器" → [EditorDemoScreen]（SQL/Lua 高亮 + 格式化 + 工具栏 + 右键菜单）
 * - "数据表格" → [TableDemoScreen]（1000 行用户数据 + 虚拟滚动 + 分页 + 右键菜单 + 详情面板）
 */
fun main() = application {
    // 注册内置代码编辑器语言 + 格式化器（SQL / Lua）
    // 重复调用幂等；UI 顶层调用一次即可
    registerBuiltinEditors()

    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // v2.9 直接模式 — 无 gRPC, 无子进程。App 内的 viewmodel 后续会持有此引用。
    val engine = IdbEngine()
    Window(
        onCloseRequest = {
            engine.close()
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            exitApplication()
        },
        title = "Sundays",
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
 * 演示 App 顶层 —— 顶部 tab 切换代码编辑器 / 数据表格两个演示。
 */
@Composable
private fun DemoApp() {
    var selectedTab by remember { mutableStateOf(DemoTab.EDITOR) }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DemoTabBar(selected = selectedTab, onSelect = { selectedTab = it })
        when (selectedTab) {
            DemoTab.EDITOR -> EditorDemoScreen()
            DemoTab.TABLE -> TableDemoScreen()
        }
    }
}

/** 演示 tab 枚举。 */
private enum class DemoTab(val label: String) {
    EDITOR("代码编辑器"),
    TABLE("数据表格"),
}

/**
 * Tab 切换栏 —— 顶部单选分段按钮。
 */
@Composable
private fun DemoTabBar(selected: DemoTab, onSelect: (DemoTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        DemoTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, DemoTab.entries.size),
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
 * 代码编辑器演示界面 —— 展示 SQL / Lua 双语言切换、格式化、高亮、右键菜单。
 *
 * **目的**：演示 `shared/` 模块中实现的代码编辑器组件在 desktopApp 中的接线方式。
 * 后续会被真正的数据库管理 UI（如 SQL 编辑器面板、Lua 造数脚本编辑器）替换。
 */
@Composable
private fun EditorDemoScreen() {
    var sqlText by remember {
        mutableStateOf(
            """
            SELECT id, name, email FROM users WHERE created_at > '2024-01-01' ORDER BY id DESC LIMIT 100
            """.trimIndent(),
        )
    }
    var luaText by remember {
        mutableStateOf(
            """
            for i = 1, 100 do
              insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65)})
            end
            """.trimIndent(),
        )
    }
    var currentLang by remember { mutableStateOf("sql") }

    if (currentLang == "sql") {
        CodeEditorWithToolbar(
            text = sqlText,
            onTextChange = { sqlText = it },
            languageId = "sql",
            onLanguageChange = { currentLang = it },
        )
    } else {
        CodeEditorWithToolbar(
            text = luaText,
            onTextChange = { luaText = it },
            languageId = "lua",
            onLanguageChange = { currentLang = it },
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
    // 模拟 1000 行数据库用户数据 —— 真实场景下 rows 由 engine 查询结果驱动
    var rows by remember {
        mutableStateOf(generateDemoUsers(count = 1000))
    }

    // 当前页 / 分页大小 state（databind：表格实时反映调用方 state 变化）
    var pageSize by remember { mutableStateOf(PageSize.S50) }
    var currentPage by remember { mutableStateOf(1) }

    // 右键菜单状态
    val contextMenuState = rememberContextMenuState()

    // 列定义
    val columns = remember {
        listOf(
            TableColumn(key = "id", header = "ID", width = 80.dp, alignment = TextAlign.End),
            TableColumn(key = "name", header = "姓名"),
            TableColumn(key = "email", header = "邮箱"),
            TableColumn(key = "age", header = "年龄", width = 80.dp, alignment = TextAlign.End),
            TableColumn(
                key = "active",
                header = "状态",
                width = 80.dp,
                alignment = TextAlign.Center,
                formatter = { if (it == true) "✓" else "✗" },
            ),
        )
    }

    DataTable(
        columns = columns,
        rows = rows,
        theme = DataTableTheme.default(),
        pageSize = pageSize,
        onPageSizeChange = { pageSize = it; currentPage = 1 },
        currentPage = currentPage,
        onPageChange = { currentPage = it },
        totalCount = rows.size,
        contextMenuState = contextMenuState,
        // 调用方注入右键菜单 —— 演示 "复制主键" + "删除（模拟）"
        contextMenuItems = { row ->
            DropdownMenuItem(
                text = { Text("复制主键 ${row?.id ?: ""}") },
                onClick = { /* copyToClipboard(row?.id.toString()) */ },
            )
            DropdownMenuItem(
                text = { Text("标记为已读") },
                onClick = { /* ... */ },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    // databind 演示：从 rows 中删除该行，UI 自动重绘
                    row?.let { r -> rows = rows.filter { it.id != r.id } }
                },
            )
        },
    )
}

/**
 * 生成模拟用户数据 —— 真实场景下替换为 `engine.query("SELECT ...")` 的结果。
 */
private fun generateDemoUsers(count: Int): List<TableRow> =
    (1..count).map { i ->
        TableRow(
            id = i.toLong(),
            "id" to i.toLong(),
            "name" to "user_$i",
            "email" to "user$i@example.com",
            "age" to (18 + i % 50),
            "active" to (i % 3 != 0),
        )
    }