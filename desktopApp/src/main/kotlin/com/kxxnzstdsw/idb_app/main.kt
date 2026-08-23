package com.kxxnzstdsw.idb_app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kxxnzstdsw.engine.IdbEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * KMP Desktop 应用入口 (v2.9 双模式架构).
 *
 * **与引擎的集成方式**: 直接依赖 `:engine` 模块, 通过 [IdbEngine] facade 直接调用引擎方法,
 * 不需要启动子进程、不需要 gRPC channel. 引擎和 UI 共享同一个 JVM, 共享同一组连接池和方言插件.
 *
 * KMP `jvmMain` (本项目 `desktopApp/`) 的典型 wiring:
 * ```kotlin
 * val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
 * val engine = IdbEngine()                              // 自动 bootstrap drivers/dialects
 *
 * engineScope.launch {
 *     val resp = engine.handle(request {
 *         id = UUID.randomUUID().toString()
 *         category = Category.SYSTEM
 *         action = Action.INFO
 *         body = RequestBody.SystemRequest(SystemRequest.getDefaultInstance())
 *     }).first()
 *     // UI 端用 resp.system.info.jvmVersion 等字段渲染
 * }
 * ```
 *
 * 流式响应 (DATA.LIST pageSize=0 / SQL.EXECUTE SELECT / DATA.GENERATE / EXPORT):
 * ```kotlin
 * engine.handle(request { ... }).collect { resp ->
 *     when {
 *         resp.dataRowFrame != null -> renderRow(resp.dataRowFrame)
 *         resp.sqlRowFrame != null  -> renderRow(resp.sqlRowFrame)
 *         resp.end                  -> finishLoading()
 *     }
 * }
 * ```
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
        title = "idb_app",
    ) {
        App()
    }
}
