package com.kxxnzstdsw.sundays.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 主题配色契约 —— [SundaysPalette] 的两套配色必须满足界面可读性的硬约束。
 *
 * ## 为什么这些断言必须存在
 *
 * 配色是**手挑的常量**，编译期不保证任何两个颜色放在一起可读。真正的风险有三条，
 * 都在这里钉住：
 *
 * 1. **暗色主题的黑字黑底回归**。Material3 的 `MaterialTheme` 不注入 `LocalContentColor`
 *    （默认 `Color.Black`），`SundaysTheme` 必须靠 `Surface` 兜底 —— 少一层就又变成
 *    暗色下黑字贴黑底。这里对 `onSurface` / `onSurfaceVariant` / `onBackground` 逐个断言。
 * 2. **语义色在两套配色间错位**。`primary` / `tertiary` / `error` 是被大量复用去画
 *    状态点、标签文字的（`StatusChip` / `ConnectionStatusDot` 都是拿 `colorScheme.primary`
 *    直接当文字色用），所以它们必须**同时**在 `surface` 和 `background` 上可读 —— 只测
 *    `surface` 会漏掉工具栏条这类压在 `background` 上的用法。
 * 3. **主色反过来读**。`onPrimary` / `onPrimaryContainer` / `onErrorContainer` 这几个
 *    「反色」角色一旦配错方向，填充实心按钮时就是浅底浅字。
 *
 * `outline` / `outlineVariant` **不纳入**文字对比度断言：它们是 1~2dp 的边框与分割线角色，
 * 本来就该贴近底色（对分割线要求 4.5:1 会让所有层级线都变成刺眼的粗线）。
 */
class SundaysPaletteTest {

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertContrast(scheme: ColorScheme, label: String, fg: Color, bg: Color) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            ratio >= 4.5,
            "$label: $fg 压在 $bg 上对比度只有 $ratio，低于 WCAG AA 4.5:1",
        )
    }

    private fun assertSchemeReadable(scheme: ColorScheme, label: String) {
        // 正文与次要文字 —— 这两组覆盖了界面上几乎所有「没显式设色的 Text」
        assertContrast(scheme, "$label/onSurface", scheme.onSurface, scheme.surface)
        assertContrast(scheme, "$label/onSurface@background", scheme.onSurface, scheme.background)
        assertContrast(scheme, "$label/onSurfaceVariant", scheme.onSurfaceVariant, scheme.surface)
        assertContrast(scheme, "$label/onSurfaceVariant@background", scheme.onSurfaceVariant, scheme.background)
        assertContrast(scheme, "$label/onSurfaceVariant@surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
        assertContrast(scheme, "$label/onBackground", scheme.onBackground, scheme.background)

        // 语义色当文字色用（状态点 / 状态 chip / 树节点）—— surface 与 background 两处都要测，
        // 因为工具栏条是压在 background 上而非 surface 上
        assertContrast(scheme, "$label/primary@surface", scheme.primary, scheme.surface)
        assertContrast(scheme, "$label/primary@background", scheme.primary, scheme.background)
        assertContrast(scheme, "$label/tertiary@surface", scheme.tertiary, scheme.surface)
        assertContrast(scheme, "$label/error@surface", scheme.error, scheme.surface)

        // 反色：填充实心按钮 / 错误容器时前景压在主色或容器色上
        assertContrast(scheme, "$label/onPrimary", scheme.onPrimary, scheme.primary)
        assertContrast(scheme, "$label/onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertContrast(scheme, "$label/onErrorContainer", scheme.onErrorContainer, scheme.errorContainer)
    }

    @Test
    fun light_scheme_keeps_text_readable() = assertSchemeReadable(SundaysPalette.LightColorScheme, "light")

    @Test
    fun dark_scheme_keeps_text_readable() = assertSchemeReadable(SundaysPalette.DarkColorScheme, "dark")

    @Test
    fun schemes_disagree_on_brightness() {
        // 浅色底必须是亮的、深色底必须是暗的 —— 配反了说明某套方案整体写反了
        assertTrue(
            luminance(SundaysPalette.LightColorScheme.background) > 0.5,
            "light background ${SundaysPalette.LightColorScheme.background} 不是亮色",
        )
        assertTrue(
            luminance(SundaysPalette.DarkColorScheme.background) < 0.2,
            "dark background ${SundaysPalette.DarkColorScheme.background} 不是暗色",
        )
    }

    @Test
    fun tonal_tint_is_disabled_so_elevated_surfaces_stay_flat() {
        // surfaceTint = transparent：`Surface(tonalElevation = …)` 在底色等于 surface 时
        // 会把 surfaceTint 叠上去（ColorScheme.applyTonalElevation）。若 tint 有色，工具栏
        // 就会叠出一层色偏；透明色让分层只由 outlineVariant 分割线表达。
        assertEquals(Color.Transparent, SundaysPalette.LightColorScheme.surfaceTint)
        assertEquals(Color.Transparent, SundaysPalette.DarkColorScheme.surfaceTint)
    }
}
