/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 树右键菜单**状态机**的行为 —— 坐标换算、目标替换、关闭。
 *
 * ## 为什么这些用例住在 `:shared`
 *
 * 菜单状态（[TreeContextMenuState]）与弹层（[TreeContextMenu]）现在**住在 `:shared`** ——
 * 浏览模式与分析模式共用同一份。它们的用例自然也该跟着住在这儿：
 * 摆在 `desktopApp` 会让人以为「这是浏览模式特有的东西」。
 *
 * ## 为什么坐标换算要单独钉住
 *
 * 这是**真窗口实测**出来的坑，不是理论推演：
 * `onRightClick` 给的是**节点局部**坐标，而菜单是屏级浮层，它要的是 **root** 坐标。
 * 原样透传的后果不是「偏几个像素」，而是「偏整屏」——
 * 真窗口实测（1152×720，density 1.25）右键第 5 行的 `postgres`，
 * 菜单顶边出现在 y≈145 而非 y≈559，**偏上约 414px**，且正好盖在 `examquestions`
 * 那一行上：用户盯着 `examquestions` 点「复制名称」，剪贴板里进来的是 `postgres`。
 * 不报错、剪贴板确有内容，但**菜单与它作用的节点在视觉上对不上**，结果就是错的。
 *
 * 载荷这里用 `String`（分析模式那边就是拿 schema 名当载荷）——
 * 状态机只关心「是哪一个目标」，不关心目标长什么样。
 */
class TreeContextMenuStateTest {
    @Test
    fun `show 与 dismiss`() {
        val menu = TreeContextMenuState<String>()
        assertNull(menu.target)
        // 节点原点与 root 重合时，换算退化为恒等 —— 这条钉住「公式里是加不是减」。
        menu.show(Offset(3f, 7f), Offset.Zero, "a")
        assertEquals("a", menu.target)
        assertEquals(3f, menu.offsetInRoot.x)
        assertEquals(7f, menu.offsetInRoot.y)
        menu.dismiss()
        assertNull(menu.target)
    }

    @Test
    fun `连续右键不同节点时目标被替换而不是累积`() {
        // 变异点：show 不覆盖 target 时这条会红 —— 用户右键 B 弹出的菜单
        // 复制出来的却是 A 的名字。
        val menu = TreeContextMenuState<String>()
        menu.show(Offset(1f, 1f), Offset.Zero, "a")
        menu.show(Offset(2f, 2f), Offset.Zero, "b")
        assertEquals("b", menu.target)
    }

    @Test
    fun `右键坐标按节点在 root 里的位置换算而不是原样透传`() {
        val menu = TreeContextMenuState<String>()
        // 节点在 root 里的原点：面板内第 5 行
        val nodeOrigin = Offset(0f, 430f)
        // 右键点在节点内的位置：偏下一点
        menu.show(Offset(140f, 18f), nodeOrigin, "postgres")

        assertEquals(140f, menu.offsetInRoot.x, 0.01f)
        assertEquals(448f, menu.offsetInRoot.y, 0.01f)
        // ⚠️ 变异点：show 里去掉换算（offsetInRoot = at）时这条必红，
        // 且红掉的 y 会是 18 —— 正是真窗口截图里菜单跑到面板顶部的那个数。
        assertTrue(menu.offsetInRoot.y > 400f, "菜单必须落在被右键的那一行附近，实际 y=${menu.offsetInRoot.y}")
    }

    @Test
    fun `换算只由节点原点与节点内偏移决定不含任何宿主信息`() {
        // 本轮踩的第二个坑，形态比第一个隐蔽：**公式看起来无懈可击**
        // （节点原点 − 宿主原点 + 节点内偏移，教科书式正确），但 `DropdownMenu`
        // 根本不按宿主解析 offset —— 它的基准就是 root。
        //
        // 真窗口实测（density 1.25，宿主原点 y=133）：减去时菜单顶边在屏幕 y≈165，
        // 不减去时 y≈298，**差值恰好 133 = 宿主原点**。也就是说状态上「减宿主」看着
        // 无误，屏幕上却正好偏一个宿主的高度 —— 这种错任何单测都看不出来，
        // 因为单测只能验证公式，验证不了公式对应的屏幕位置。
        //
        // 这里能钉住的只有一件事：**换算的输入里没有宿主这一项**。
        // 任何人想改回「减宿主原点」，都得先把 hostOrigin 接回公式，
        // 而那一步会让下面两条断言立刻红。
        val menu = TreeContextMenuState<String>()

        // 同一个右键点在树里的不同位置 —— 差值必须**只**来自节点原点
        menu.show(Offset(140f, 18f), Offset(0f, 200f), "a")
        val near = menu.offsetInRoot
        menu.show(Offset(140f, 18f), Offset(0f, 900f), "b")
        val far = menu.offsetInRoot

        assertEquals(218f, near.y, 0.01f)
        assertEquals(918f, far.y, 0.01f)
        // 700 = 两个节点原点的差，不多不少
        assertEquals(700f, far.y - near.y, 0.01f)
        // x 同样只来自节点内偏移：菜单左右跟着鼠标走，不受树面板位置影响
        assertEquals(140f, far.x, 0.01f)
    }

    @Test
    fun `节点原点为 root 原点时退化为原样透传`() {
        // 边界：位于 (0,0) 的节点上右键，换算必须是恒等 —— 否则公式里还藏着别的项。
        val menu = TreeContextMenuState<String>()
        menu.show(Offset(12f, 34f), Offset.Zero, "d")
        assertEquals(12f, menu.offsetInRoot.x, 0.01f)
        assertEquals(34f, menu.offsetInRoot.y, 0.01f)
    }

    @Test
    fun `dismiss 只清目标不清坐标`() {
        // 坐标留着无害（下次 show 会覆盖），但如果 dismiss 把坐标一起清了，
        // 而布局恰好在这之间变化，下一次右键就会算在一个过期的基准上。
        val menu = TreeContextMenuState<String>()
        menu.show(Offset(5f, 6f), Offset(1f, 2f), "d")
        menu.dismiss()
        assertNull(menu.target)
        assertEquals(8f, menu.offsetInRoot.y, 0.01f)
    }

    @Test
    fun `showAt 直接用 root 坐标 不做换算`() {
        // 共享树组件（[ObjectTree]）自己知道节点在 root 里的位置，
        // 所以它交出来的是**已经换算好**的 root 坐标 —— 菜单这边不该再换一次。
        val menu = TreeContextMenuState<String>()
        menu.showAt(Offset(140f, 448f), "x")
        assertEquals(140f, menu.offsetInRoot.x, 0.01f)
        assertEquals(448f, menu.offsetInRoot.y, 0.01f)
        assertEquals("x", menu.target)
    }

    @Test
    fun `宿主原点被单独记着 且不参与坐标换算`() {
        // 宿主原点只在**弹层定位**那一步用得上（root 坐标 → Popup parent 的局部坐标），
        // 它**不该**混进 `offsetInRoot` —— 混进去的话菜单会偏一个宿主的高度。
        val menu = TreeContextMenuState<String>()
        menu.updateHostOrigin(Offset(0f, 133f))
        menu.show(Offset(140f, 18f), Offset(0f, 200f), "a")
        assertEquals(218f, menu.offsetInRoot.y, 0.01f)
        assertEquals(133f, menu.hostOrigin.y, 0.01f)
    }
}
