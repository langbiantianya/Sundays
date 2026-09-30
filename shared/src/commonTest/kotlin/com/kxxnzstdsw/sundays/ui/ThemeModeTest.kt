package com.kxxnzstdsw.sundays.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 主题档位切换的逻辑契约。
 *
 * ## 覆盖范围
 * - [ThemeMode.next] / [ThemeMode.previous]：三档循环的顺序
 * - [ThemeMode.resolveDark]：把档位解析成实际明暗，**含系统值参与**的两条分支
 * - [ThemeModeState]：状态转换（cycle / select）与初始值
 *
 * ## 为什么 [ThemeMode.next] 的顺序要钉住
 *
 * 按钮图标取的是 `next`，文案取的是 `label()` —— 两者都从同一个「下一档」推导。
 * 顺序若被改成 `SYSTEM → DARK`，图标就会与文案不一致：跟随系统时显示月亮、
 * 文案却说「切换为深色」，而实际结果是浅色。这属于「界面说了一套、代码做了一套」，
 * 只有把映射写死才能被测到。
 */
class ThemeModeTest {

    @Test
    fun cycle_order_is_system_light_dark() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.next)
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DARK.next)
    }

    @Test
    fun three_clicks_return_to_starting_mode() {
        // 三档循环必须闭合：点三下回到原点，否则「跟随系统」档会点丢
        var mode = ThemeMode.SYSTEM
        repeat(3) { mode = mode.next }
        assertEquals(ThemeMode.SYSTEM, mode)
    }

    @Test
    fun previous_reverses_next() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, mode.next.previous, "next→previous 应当回到 $mode")
        }
    }

    @Test
    fun explicit_modes_ignore_the_system_value() {
        // 显式档位不能被系统值影响 —— 否则「点了变深色但系统是浅色」就永远切不过去
        assertFalse(ThemeMode.LIGHT.resolveDark(isSystemDark = true))
        assertTrue(ThemeMode.DARK.resolveDark(isSystemDark = false))
    }

    @Test
    fun system_mode_follows_the_system_value() {
        assertEquals(true, ThemeMode.SYSTEM.resolveDark(isSystemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveDark(isSystemDark = false))
    }

    @Test
    fun state_starts_on_system_and_cycles() {
        val state = ThemeModeState()
        assertEquals(ThemeMode.SYSTEM, state.mode)

        state.cycle()
        assertEquals(ThemeMode.LIGHT, state.mode)

        state.cycle()
        assertEquals(ThemeMode.DARK, state.mode)

        state.cycle()
        assertEquals(ThemeMode.SYSTEM, state.mode)
    }

    @Test
    fun select_jumps_directly_to_a_target() {
        val state = ThemeModeState()
        state.select(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, state.mode)
        state.select(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, state.mode)
    }

    @Test
    fun initial_constructor_value_is_honoured() {
        assertEquals(ThemeMode.DARK, ThemeModeState(ThemeMode.DARK).mode)
    }
}
