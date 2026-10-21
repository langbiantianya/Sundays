/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 下拉选择器的纯逻辑断言 —— **形状解析 / 选中项反查 / 能否展开**。
 *
 * ## 为什么全是纯函数断言
 *
 * 下拉的取色、形状、选中态这三件事里，真正会出错的不是「画得好不好看」，
 * 而是**规则本身**：形状该走主题圆角还是 M3 默认、当前值对应列表里的哪一项、
 * 空列表 / 禁用态该不该弹菜单。这三条都能用普通函数算出来，
 * 所以本文件刻意不起 UI —— `UiTokensTest` 已经论证过这条路：JUnit4 反射会把
 * `@Composable` 的合成 `composer` 参数当成测试方法形参而拒绝加载，
 * 组合函数根本进不了单元测试。
 *
 * 组合层（触发器画成什么样、弹层怎么定位）由界面本身负责，这里不重复断言。
 */
class WinControlsTest {
    /** 现代档的默认形状 —— 显式给一个能认出来的值，避免「断言了个寂寞」。 */
    private val modernDefault = RoundedCornerShape(8.dp)

    /**
     * 经典档的形状解析走**主题圆角**，不是调用方给的 M3 默认值。
     *
     * 这条是 Win2000 / WinXP 两套配色能一眼认出「经典」的前提：
     * 形状若在经典档仍走 M3 的 4dp / 8dp 圆角，边框斜面就成了装饰而不是造型。
     */
    @Test
    fun classic_palette_resolves_shape_to_theme_corner() {
        val win2000 = ThemePalette.WIN_2000.uiTokens(useDark = false)
        val winXp = ThemePalette.WIN_XP.uiTokens(useDark = false)

        assertEquals(
            RoundedCornerShape(0.dp),
            resolveControlShapeFor(win2000, shape = null, modernDefault = modernDefault),
            "Win2000 的控件应当是直角（它的 bevelRadius 是 0dp）",
        )
        assertEquals(
            RoundedCornerShape(3.dp),
            resolveControlShapeFor(winXp, shape = null, modernDefault = modernDefault),
            "WinXP 的控件应当是 3dp 圆角（Luna 的 bevelRadius）",
        )
    }

    /**
     * 现代档**保持 M3 的默认形状** —— 包装层不许顺手改掉现代三套配色的既有观感。
     *
     * 这是 `resolveControlShape` KDoc 里那条「现代主题必须逐像素保持改造前的行为」
     * 的机器可验证形式。
     */
    @Test
    fun modern_palette_keeps_modern_default_shape() {
        listOf(ThemePalette.BLUE_GRAY, ThemePalette.CYBERPUNK, ThemePalette.BILI_PINK).forEach { palette ->
            val tokens = palette.uiTokens(useDark = false)
            assertEquals(
                modernDefault,
                resolveControlShapeFor(tokens, shape = null, modernDefault = modernDefault),
                "${palette.labelKey} 应保持 M3 默认形状",
            )
        }
    }

    /** 调用方显式传了形状就以它为准 —— 经典档也不例外。 */
    @Test
    fun explicit_shape_always_wins() {
        val explicit = RoundedCornerShape(12.dp)
        listOf(ThemePalette.WIN_2000, ThemePalette.BLUE_GRAY).forEach { palette ->
            val tokens = palette.uiTokens(useDark = false)
            assertEquals(
                explicit,
                resolveControlShapeFor(tokens, shape = explicit, modernDefault = modernDefault),
                "${palette.labelKey} 应尊重调用方显式指定的形状",
            )
        }
    }

    /**
     * 选中项靠**显示文本**反查 —— 下拉的「值」与 [WinTextField] 同口径是字符串。
     */
    @Test
    fun dropdown_finds_the_selected_item_by_label() {
        val items = listOf("文本", "整数", "小数", "百分比", "货币", "日期")
        assertEquals(0, dropdownSelectedIndex(items, "文本", itemLabel = { it }))
        assertEquals(5, dropdownSelectedIndex(items, "日期", itemLabel = { it }))
        assertEquals("货币", dropdownSelectedLabel(items, "货币", itemLabel = { it }))
    }

    /**
     * 值对不上任何一项时**不选中**，而不是默默落到第一项。
     *
     * 默默改掉用户的取值比留空更糟：留空至少看得见「没配上」。
     */
    @Test
    fun dropdown_reports_no_selection_for_unknown_value() {
        val items = listOf("文本", "整数")
        assertEquals(-1, dropdownSelectedIndex(items, "浮点数", itemLabel = { it }))
        assertNull(dropdownSelectedLabel(items, "浮点数", itemLabel = { it }))
    }

    /** 空列表同样没有选中项 —— 此时显示 placeholder，而不是崩在索引上。 */
    @Test
    fun dropdown_handles_empty_item_list() {
        val empty = emptyList<String>()
        assertEquals(-1, dropdownSelectedIndex(empty, "文本", itemLabel = { it }))
        assertNull(dropdownSelectedLabel(empty, "文本", itemLabel = { it }))
    }

    /**
     * 标签重复时取**第一个**匹配项 —— 反查规则必须是确定的，
     * 否则同一个值在不同次组合里会高亮到不同的行上。
     */
    @Test
    fun dropdown_resolves_duplicate_labels_to_the_first_item() {
        val items = listOf("连接", "断开", "连接")
        assertEquals(0, dropdownSelectedIndex(items, "连接", itemLabel = { it }))
    }

    /**
     * 禁用态与空列表都不该弹菜单。
     *
     * 空列表弹出的是一块只有内边距的空壳；经典档下那块空壳还是一块
     * 「点得动的银灰方块」，比不弹更糟。
     */
    @Test
    fun dropdown_refuses_to_open_when_disabled_or_empty() {
        assertTrue(dropdownCanOpen(enabled = true, itemCount = 1))
        assertFalse(dropdownCanOpen(enabled = false, itemCount = 3), "禁用态不该展开")
        assertFalse(dropdownCanOpen(enabled = true, itemCount = 0), "空列表不该展开")
        assertFalse(dropdownCanOpen(enabled = false, itemCount = 0), "禁用 + 空列表更不该展开")
    }
}
