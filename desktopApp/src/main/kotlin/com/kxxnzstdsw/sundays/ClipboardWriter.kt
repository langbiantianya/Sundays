package com.kxxnzstdsw.sundays

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 系统剪贴板写入 —— 桌面端唯一出口。
 *
 * ## 为什么自己写而不用 `LocalClipboardManager`
 *
 * 1. `androidx.compose.ui.platform.LocalClipboardManager` 在 Compose Multiplatform 1.8 起
 *    已被标记废弃、1.11 里只剩兼容壳，且它内部最终也是转调 AWT —— 多包一层
 *    `CompositionLocal`，换来的是一个正在消失的 API。
 * 2. `LocalClipboard`（Skiko 新接口）需要显式构造 `Clipboard` 实例并自己处理
 *    `ClipboardEntry` 的生命周期，**没有**系统剪贴板回读能力；而本项目的用法恰恰
 *    需要「写进去 + 用户在别处粘出来」这条真实链路。
 * 3. `desktopApp` 是纯 JVM 模块，直接用 AWT 不引入任何新依赖。
 *
 * ## 失败必须**返回 false 而不是抛**
 *
 * 三个真实存在的失败场景：
 * - 无头环境（`GraphicsEnvironment.isHeadless()`）—— UI 测试就跑在这里；
 * - 剪贴板被另一个进程长时间占用（Windows 上偶发 `IllegalStateException: Clipboard busy`）；
 * - X11 / Wayland 下剪贴板服务不可用。
 *
 * 这三种都不该让用户的右键操作崩掉整个应用 —— 调用方据此推一条错误通知即可
 * （见 [DatabaseBrowserState.copyTreeDdl]）。
 */
object ClipboardWriter {

    /**
     * 把 [text] 写入系统剪贴板。
     *
     * @return `true` = 写入成功；`false` = 环境不支持或剪贴板忙。
     */
    fun copy(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return false
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            true
        } catch (_: Throwable) {
            // ⚠️ 捕获 Throwable 而不是 Exception：`Toolkit.getDefaultToolkit()` 在无头
            // 环境抛的是 `HeadlessException`（Exception 子类，能被 Exception 抓到），
            // 但 AWT 在初始化失败时也可能抛 `Error`（如 `AWTError`）。
            // 剪贴板是**便利功能**，它出问题绝不该带走调用方的主流程。
            false
        }
    }
}
