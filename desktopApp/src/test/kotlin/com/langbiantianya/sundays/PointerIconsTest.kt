/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.input.pointer.PointerIcon
import org.junit.Test
import java.awt.Cursor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 桌面端的指针图标 —— [ewResizePointerIcon] 的契约。
 *
 * ## 为什么这条断言在
 *
 * 分隔条在鼠标下必须看起来**可拖**。指针不对时，用户根本不会去试那条缝 ——
 * 拖拽功能「存在但没人发现」是最难被报上来的缺陷类型：
 * 没人会写「那个箭头不对」，只会写「这个面板能不能调宽」。
 *
 * 指针类型是**平台专属**的（`java.awt.Cursor`），所以断言只能落在桌面端这一层：
 * `:shared` 那边只管「什么时候用这个指针」，造不出指针来，也就验不了。
 *
 * 比对用 `equals` 而不是看类名：Compose 桌面端的 `PointerIcon` 实现是 internal 的
 * `AwtCursor`，它按**光标类型**判等 —— 正好是我们真正关心的那件事。
 */
class PointerIconsTest {
    @Test
    fun `桌面端能造出左右拉伸指针且类型正确`() {
        val icon = ewResizePointerIcon()
        assertNotNull(icon, "桌面端应当造得出左右拉伸指针；拿不到说明 AWT 不可用")
        assertEquals(
            // ⚠️ AWT 里**没有** `EW_RESIZE_CURSOR`；东西向双箭头是 `E_RESIZE_CURSOR`
            // （`E` 与 `W` 是同一形状的两个热区，画出来一样）。
            PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)),
            icon,
            "指针应当是**左右拉伸**箭头（E_RESIZE），而不是别的形状",
        )
    }

    @Test
    fun `指针是稳定的单例`() {
        // 每次重组都新建一个 AWT 光标会让 Compose 的 modifier 节点反复比对、
        // 反复重设系统光标 —— 表现为光标「抖一下」。同一个图标必须每次拿到同一个。
        assertEquals(
            ewResizePointerIcon(),
            ewResizePointerIcon(),
            "同一个图标应当每次返回同一个实例",
        )
    }
}
