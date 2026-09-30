package com.kxxnzstdsw.sundays.ui

import kotlin.math.pow
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
        val state = AppearanceState()
        assertEquals(ThemeMode.SYSTEM, state.mode)

        state.cycleMode()
        assertEquals(ThemeMode.LIGHT, state.mode)

        state.cycleMode()
        assertEquals(ThemeMode.DARK, state.mode)

        state.cycleMode()
        assertEquals(ThemeMode.SYSTEM, state.mode)
    }

    @Test
    fun select_jumps_directly_to_a_target() {
        val state = AppearanceState()
        state.selectMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, state.mode)
        state.selectMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, state.mode)
    }

    @Test
    fun initial_constructor_values_are_honoured() {
        assertEquals(ThemeMode.DARK, AppearanceState(initialMode = ThemeMode.DARK).mode)
        assertEquals(
            ThemePalette.CYBERPUNK,
            AppearanceState(initialPalette = ThemePalette.CYBERPUNK).palette,
        )
        assertEquals(
            SystemInfoRefresh.S5,
            AppearanceState(initialSystemInfoRefresh = SystemInfoRefresh.S5).systemInfoRefresh,
        )
    }

    @Test
    fun palette_and_mode_are_independent_axes() {
        // 两轴正交：换配色不应动明暗，反之亦然。串了的话「换成赛博朋克但保持深色」会失效。
        val state = AppearanceState(initialPalette = ThemePalette.BLUE_GRAY, initialMode = ThemeMode.DARK)

        state.selectPalette(ThemePalette.CYBERPUNK)
        assertEquals(ThemePalette.CYBERPUNK, state.palette)
        assertEquals(ThemeMode.DARK, state.mode, "换配色不应改动明暗档位")

        state.selectMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, state.mode)
        assertEquals(ThemePalette.CYBERPUNK, state.palette, "换明暗不应改回配色")
    }

    @Test
    fun every_palette_resolves_both_brightnesses() {
        // 每个配色都必须同时提供可用的浅 / 深两套 —— 只有深色的配色被配到浅色档时，
        // 底色会亮不起来（这正是双轴模型要求每套主题自带两变体的原因）
        ThemePalette.entries.forEach { palette ->
            val light = palette.schemeFor(useDark = false)
            val dark = palette.schemeFor(useDark = true)
            assertTrue(
                luminance(light.background) > 0.5,
                "${palette.label} 的浅色变体底色不够亮：${light.background}",
            )
            assertTrue(
                luminance(dark.background) < 0.2,
                "${palette.label} 的深色变体底色不够暗：${dark.background}",
            )
        }
    }

    @Test
    fun palette_change_fires_the_persist_hook() {
        // 持久化挂在 onChange 上：漏调 = 重启后设置丢失
        val changes = mutableListOf<AppearanceState>()
        val state = AppearanceState(onChange = { changes += it })

        state.selectPalette(ThemePalette.CYBERPUNK)
        state.selectMode(ThemeMode.DARK)
        state.selectSystemInfoRefresh(SystemInfoRefresh.S2)

        assertEquals(3, changes.size, "三次变更应各触发一次回调")
        assertEquals(ThemePalette.CYBERPUNK, changes[0].palette)
        assertEquals(ThemeMode.DARK, changes[1].mode)
        assertEquals(SystemInfoRefresh.S2, changes[2].systemInfoRefresh)
    }

    @Test
    fun redundant_change_does_not_fire_the_hook() {
        // 重复选中同一档不写盘 —— 否则每次点到已选中项都会多一次文件 I/O
        val changes = mutableListOf<AppearanceState>()
        val state = AppearanceState(initialMode = ThemeMode.DARK, onChange = { changes += it })

        state.selectMode(ThemeMode.DARK)
        assertEquals(0, changes.size, "同值重选不应触发落盘")
    }

    @Test
    fun refresh_interval_options_cover_required_steps() {
        // 需求指定的四档 + 关闭；OFF 之外都必须给出有效周期
        assertEquals(
            listOf("关闭", "10 秒", "5 秒", "2 秒", "1 秒"),
            SystemInfoRefresh.entries.map { it.label },
        )
        assertEquals(null, SystemInfoRefresh.OFF.interval, "OFF 不应有周期")
        assertFalse(SystemInfoRefresh.OFF.enabled)
        for (refresh in SystemInfoRefresh.entries.filter { it.enabled }) {
            assertTrue(
                (refresh.interval?.inWholeSeconds ?: 0) > 0,
                "${refresh.label} 应给出正周期",
            )
        }
        assertEquals(1L, SystemInfoRefresh.S1.interval?.inWholeSeconds)
        assertEquals(2L, SystemInfoRefresh.S2.interval?.inWholeSeconds)
        assertEquals(5L, SystemInfoRefresh.S5.interval?.inWholeSeconds)
        assertEquals(10L, SystemInfoRefresh.S10.interval?.inWholeSeconds)
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
