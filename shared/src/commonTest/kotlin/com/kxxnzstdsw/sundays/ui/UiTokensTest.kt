package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 外观 token 契约 —— **每套配色主题都必须给全界面一份 token**。
 *
 * ## 为什么这是本轮最重要的一组断言
 *
 * 界面层的外观决策若散落在各组件里（`if (isClassic) … else …`），加新主题就得翻遍
 * 全代码，漏一个判断就有一块控件出戏，而且**不会报任何错**。抽成 [UiThemeTokens] 之后，
 * 新主题只需在 [ThemePalette.uiTokens] 加一个分支；这份契约就是那个分支的护栏：
 *
 * 1. [every_palette_defines_tokens] 逐主题 × 明暗两档断言 token 非空且自洽。
 * 2. [classic_palettes_turn_on_every_chrome_decision] 断言经典档把**每一项**造型决策都
 *    打开了 —— 只要漏了其中一项，界面就会有一块控件保持现代外观。
 * 3. [modern_palettes_keep_every_chrome_decision_off] 反向断言现代档全关。这条同样是
 *    回归护栏：token 写错会给现有三个主题凭空加上经典外观。
 *
 * 全部是**纯函数断言**（`uiTokens` / `shapeFor` / `selectionColorsFor` 都不依赖组合），
 * 因此不需要 `runComposeUiTest` —— JUnit4 反射会把 `@Composable` 的合成 `composer`
 * 参数当成测试方法的形参而拒绝加载（这也是这些 helper 刻意抽成纯函数的原因）。
 *
 * 「组件侧不出现主题名判断」这条约束无法在 `commonTest` 里自动验证（Kotlin 源码不是
 * classpath 资源），由 `UiTokensTest` 的 KDoc 记录约定，实际以 grep 检查为准。
 */
class UiTokensTest {

    private val brightness = listOf(true, false)

    @Test
    fun every_palette_defines_tokens() {
        ThemePalette.entries.forEach { palette ->
            brightness.forEach { dark ->
                val tokens = palette.uiTokens(dark)
                assertEquals(
                    palette == ThemePalette.WIN_2000 || palette == ThemePalette.WIN_XP,
                    tokens.isClassic,
                    "${palette.label}/dark=$dark 的 chrome 档位应与是否经典主题一致",
                )
                // 经典档必须有真实斜面；现代档必须彻底关掉。两者由 chrome 档位统一派生，
                // 不允许出现「classic 但斜面关闭」这种自相矛盾的组合。
                assertEquals(
                    tokens.isClassic,
                    tokens.bevel.enabled,
                    "${palette.label}/dark=$dark 的斜面开关应与 chrome 档位一致",
                )
            }
        }
    }

    /**
     * 经典档把**每一项**造型决策都打开。
     *
     * 这条是「所有 UI 都要改」的机器可验证形式：将来新增一项 token 而经典档忘了配，
     * 就要把它列进这里的断言清单 —— 漏了会在 code review 被发现，而不是等用户在界面上
     * 看到一块方方正正的现代控件。
     */
    @Test
    fun classic_palettes_turn_on_every_chrome_decision() {
        listOf(ThemePalette.WIN_2000, ThemePalette.WIN_XP).forEach { palette ->
            brightness.forEach { dark ->
                val t = palette.uiTokens(dark)
                assertEquals(ChromeMode.CLASSIC, t.chrome, "${palette.label} 应为经典档")
                assertEquals(ButtonFace.WINDOW, t.buttonFace, "${palette.label} 按钮应取窗口面")
                assertEquals(FieldFace.SURFACE_VARIANT, t.fieldFace, "${palette.label} 输入框应取灰面")
                assertEquals(SelectionMode.INVERTED, t.selection, "${palette.label} 选中态应反色")
                assertEquals(DividerMode.ETCHED, t.divider, "${palette.label} 分割线应蚀刻")
                assertEquals(PanelBorder.BEVEL, t.panelBorder, "${palette.label} 面板应走斜面")
                assertFalse(t.zebraRows, "${palette.label} 不应有斑马纹（经典 Win 列表没有隔行底色）")
                assertTrue(t.disabledInk != Color.Unspecified, "${palette.label} 应显式给出禁用文字色")
                assertTrue(t.bevel.width > 0.dp, "${palette.label} 斜面线宽应为正")
            }
        }
    }

    /** 现代档必须把每一项都关掉 —— 否则会给现有三个主题凭空加上经典外观。 */
    @Test
    fun modern_palettes_keep_every_chrome_decision_off() {
        listOf(ThemePalette.BLUE_GRAY, ThemePalette.CYBERPUNK, ThemePalette.BILI_PINK).forEach { palette ->
            brightness.forEach { dark ->
                val t = palette.uiTokens(dark)
                assertEquals(ChromeMode.MODERN, t.chrome, "${palette.label} 应为现代档")
                assertEquals(BevelStyle.NONE, t.bevel, "${palette.label} 不应有斜面")
                assertEquals(ButtonFace.PRIMARY, t.buttonFace, "${palette.label} 按钮应取 primary")
                assertEquals(FieldFace.SURFACE, t.fieldFace, "${palette.label} 输入框应取 surface")
                assertEquals(SelectionMode.TINTED, t.selection, "${palette.label} 选中态应为淡色底")
                assertEquals(DividerMode.FLAT, t.divider, "${palette.label} 分割线应为实线")
                assertEquals(PanelBorder.LINE, t.panelBorder, "${palette.label} 面板应为单色描边")
                assertTrue(t.zebraRows, "${palette.label} 应保留斑马纹")
                assertEquals(Color.Unspecified, t.disabledInk, "${palette.label} 禁用色应交回 M3 计算")
            }
        }
    }

    /** [shapeFor] 在现代档必须原样返回调用方的圆角 —— 防止经典改造误伤现有三个主题。 */
    @Test
    fun shape_for_is_a_passthrough_for_modern_tokens() {
        val modern = UiThemeTokens.MODERN
        listOf(0.dp, 2.dp, 4.dp, 6.dp, 8.dp, 12.dp).forEach { corner ->
            assertEquals(
                RoundedCornerShape(corner),
                shapeFor(modern, corner),
                "现代档 shapeFor($corner) 应原样返回",
            )
        }
    }

    /** 经典档的圆角一律按 token 的斜面圆角抹平，与调用方传入的值无关。 */
    @Test
    fun shape_for_flattens_corners_for_classic_tokens() {
        listOf(ThemePalette.WIN_2000, ThemePalette.WIN_XP).forEach { palette ->
            val tokens = palette.uiTokens(useDark = false)
            listOf(0.dp, 4.dp, 8.dp, 12.dp).forEach { corner ->
                assertEquals(
                    RoundedCornerShape(tokens.bevel.bevelRadius),
                    shapeFor(tokens, corner),
                    "${palette.label} 的 shapeFor($corner) 应抹平为 ${tokens.bevel.bevelRadius}",
                )
            }
        }
    }

    /**
     * 经典档的选中态必须是反色，且反色后文字要过 AA 4.5:1。
     *
     * 反色做对了但文字压不住会直接毁掉复古感，所以这里把对比度一并钉住。
     */
    @Test
    fun classic_selection_is_inverted_and_readable() {
        listOf(ThemePalette.WIN_2000, ThemePalette.WIN_XP).forEach { palette ->
            brightness.forEach { dark ->
                val scheme = palette.schemeFor(dark)
                val (bg, fg) = selectionColorsFor(palette.uiTokens(dark), scheme, selected = true)
                assertEquals(scheme.primary, bg, "${palette.label}/dark=$dark 选中底色应为 primary 反色")
                assertEquals(scheme.onPrimary, fg, "${palette.label}/dark=$dark 选中文字色应为 onPrimary")
                assertTrue(
                    contrastRatio(fg, bg) >= 4.5,
                    "${palette.label}/dark=$dark 反色选中行对比度低于 AA 4.5:1",
                )
            }
        }
    }

    /** 未选中态必须原样走调用方传入的底色 —— 各调用点原本并不统一，不能一刀切成 surface。 */
    @Test
    fun unselected_color_is_passed_through_untouched() {
        val custom = Color(0xFF123456)
        ThemePalette.entries.forEach { palette ->
            val scheme = palette.schemeFor(useDark = false)
            val (bg, _) = selectionColorsFor(
                palette.uiTokens(useDark = false), scheme, selected = false, unselected = custom,
            )
            assertEquals(custom, bg, "${palette.label} 未选中底色应原样返回调用方传入值")
        }
    }

    /**
     * 经典档的斜面必须真的比它落在的两种控件面更亮 / 更暗。
     *
     * 两种面：按钮面 `background`、输入框面 `surfaceVariant`（见 [FieldFace] 的说明）。
     * Win2000 浅色档的 `surface` 是纯白，而经典凹陷边有一侧就是纯白 `ButtonHighlight` ——
     * 贴在纯白面上会完全隐形。这个隐形问题正是本断言先报出来的。
     */
    @Test
    fun classic_bevel_contrasts_against_every_face_it_lands_on() {
        listOf(ThemePalette.WIN_2000, ThemePalette.WIN_XP).forEach { palette ->
            brightness.forEach { dark ->
                val bevel = palette.uiTokens(dark).bevel
                val scheme = palette.schemeFor(dark)
                mapOf(
                    "background(按钮面)" to scheme.background,
                    "surfaceVariant(输入框面)" to scheme.surfaceVariant,
                ).forEach { (faceName, face) ->
                    val faceLum = luminance(face)
                    assertTrue(
                        luminance(bevel.light) > faceLum,
                        "${palette.label}/dark=$dark 斜面亮边没有比 $faceName 更亮，画不出凸起",
                    )
                    assertTrue(
                        luminance(bevel.dark) < faceLum,
                        "${palette.label}/dark=$dark 斜面暗边没有比 $faceName 更暗，画不出凹陷",
                    )
                }
                assertTrue(luminance(bevel.light) != luminance(bevel.dark), "斜面亮暗同色等于一条平边")
            }
        }
    }

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun Double.pow(e: Double): Double = Math.pow(this, e)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = if (la > lb) la else lb
        val lo = if (la > lb) lb else la
        return (hi + 0.05) / (lo + 0.05)
    }
}
