package com.kxxnzstdsw.idb_app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
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
import com.kxxnzstdsw.idb_app.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.idb_app.editor.ui.registerBuiltinEditors
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
        title = "idb_app",
    ) {
        EditorDemoScreen()
    }
}

/**
 * 代码编辑器演示界面 —— 展示 SQL / Lua 双语言切换、格式化、高亮。
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

    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
}
