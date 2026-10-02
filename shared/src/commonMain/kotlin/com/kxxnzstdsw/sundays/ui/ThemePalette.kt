package com.kxxnzstdsw.sundays.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape

/**
 * 配色主题轴 —— 与 [ThemeMode]（明暗轴）**正交**的两个维度之一。
 *
 * ## 为什么要拆成两个轴
 *
 * 配色由「色相 + 明暗」共同决定，把两者混进一个枚举会产生 `2^N` 个组合项且互相重复
 * （蓝灰浅 / 赛博浅 / 哔哩粉浅 / Win2000 浅 / WinXP 浅 / 蓝灰深 / 赛博深 / 哔哩粉深 /
 * Win2000 深 / WinXP 深 = 10 项，其中「明暗」在每项里都被重复描述了一遍）。拆开后：
 * 主题 5 项 × 明暗 3 档 = 15 种外观，而设置页是两个独立的
 * 单选组 —— 用户心里想的是「换个配色」和「要不要跟着系统变暗」这两件正交的事。
 *
 * 新增配色只需加一个枚举项并实现 [light] / [dark]，[SettingsScreen] 的单选组自动出现。
 */
enum class ThemePalette(
    val label: String,
    val description: String,
) {
    /** 蓝灰专业 IDE 风格 —— 默认，低饱和、长时间看不累。 */
    BLUE_GRAY("蓝灰 IDE", "低饱和靛蓝配色，办公环境下的默认选择"),

    /** 赛博朋克 —— 近黑紫底 + 霓虹青 / 品红 / 荧光绿。 */
    CYBERPUNK("赛博朋克", "近黑紫底配霓虹青 / 品红 / 荧光绿"),

    /** 哔哩粉 —— 暖玫瑰粉主色；浅色档必须压深成酒红，不能直接用品牌粉。 */
    BILI_PINK("哔哩粉", "暖玫瑰粉配色，品牌粉在浅色档已压深成酒红"),

    /** Win2000 —— 经典银灰控件面 `#C0C0C0` + 深蓝选区色，黑字银灰的高对比复古配色。 */
    WIN_2000("Win2000", "经典银灰控件面配深蓝选区色，黑底银字为复古暗色档"),

    /** WinXP —— Luna 奶油底 `#ECE9D8` + 蓝灰 chrome，选区蓝用 `#255EA1` 才够对比度。 */
    WIN_XP("WinXP", "Luna 奶油底配蓝灰 chrome，选区蓝已从 #316AC5 换成 #255EA1");

    /** 该主题的浅色配色。 */
    val light: ColorScheme
        get() = when (this) {
            BLUE_GRAY -> SundaysPalette.LightColorScheme
            CYBERPUNK -> SundaysPalette.CyberpunkLightColorScheme
            BILI_PINK -> SundaysPalette.BiliPinkLightColorScheme
            WIN_2000 -> SundaysPalette.Win2000LightColorScheme
            WIN_XP -> SundaysPalette.WinXpLightColorScheme
        }

    /** 该主题的深色配色。 */
    val dark: ColorScheme
        get() = when (this) {
            BLUE_GRAY -> SundaysPalette.DarkColorScheme
            CYBERPUNK -> SundaysPalette.CyberpunkDarkColorScheme
            BILI_PINK -> SundaysPalette.BiliPinkDarkColorScheme
            WIN_2000 -> SundaysPalette.Win2000DarkColorScheme
            WIN_XP -> SundaysPalette.WinXpDarkColorScheme
        }

    /**
     * 该主题的形状阶梯 —— 复古两套与配色一样是主题的一部分。
     *
     * 蓝灰 / 赛博朋克 / 哔哩粉共用紧凑圆角；Win2000 全直角；WinXP 用 Luna 的递增圆角。
     * 形状走的是 [androidx.compose.material3.MaterialTheme.shapes]，所以 chip / 输入框 /
     * 菜单 / 弹层会自动跟随；只有 `Button` 需要显式传 [buttonShape]。
     */
    val shapes: Shapes
        get() = when (this) {
            BLUE_GRAY, CYBERPUNK, BILI_PINK -> SundaysPalette.Shapes
            WIN_2000 -> SundaysPalette.Win2000Shapes
            WIN_XP -> SundaysPalette.WinXpShapes
        }

    /**
     * 按钮 / chip / 输入框的圆角。
     *
     * 与 `shapes.small` 同值，但单独暴露是因为 Material3 的 `Button` 形状由 token 固定、
     * **不读** `MaterialTheme.shapes`，必须逐个显式传（见 [SundaysPalette.buttonShape]）。
     */
    val buttonShape: Shape get() = shapes.small

    /**
     * 该主题的 3D 斜面边框配色 —— 经典 Win 控件的辨识度主要来自它。
     *
     * 现代三套返回 [BevelStyle.NONE]（`enabled = false`），于是 [winBevel] 是彻底的空
     * 操作，不会给现有界面带来任何变化。只有 Win2000 / WinXP 会启用。
     */
    fun bevelStyle(useDark: Boolean): BevelStyle = when (this) {
        BLUE_GRAY, CYBERPUNK, BILI_PINK -> BevelStyle.NONE
        WIN_2000 -> if (useDark) SundaysPalette.Win2000DarkBevel else SundaysPalette.Win2000LightBevel
        WIN_XP -> if (useDark) SundaysPalette.WinXpDarkBevel else SundaysPalette.WinXpLightBevel
    }

    /**
     * 该主题的**全部界面外观决策** —— 加新主题时唯一需要动的地方。
     *
     * 颜色本身在 [light] / [dark] 两份 `ColorScheme` 里；「这些颜色怎么用」在这里。
     * 组件侧只读 [LocalUiTokens]，不含任何主题名判断，因此新增一套外观不必翻遍全代码。
     * 三个 `when`（[shapes] / [bevelStyle] / 本函数）是穷举的，加枚举项时编译器会
     * 把所有漏网处指出来。
     */
    fun uiTokens(useDark: Boolean): UiThemeTokens = when (this) {
        BLUE_GRAY, CYBERPUNK, BILI_PINK -> UiThemeTokens.MODERN
        WIN_2000 -> UiThemeTokens(
            chrome = ChromeMode.CLASSIC,
            bevel = bevelStyle(useDark),
            buttonFace = ButtonFace.WINDOW,
            // 纯白面上经典凹陷边的亮侧会隐形，退回 Windows Standard 的灰面
            fieldFace = FieldFace.SURFACE_VARIANT,
            selection = SelectionMode.INVERTED,
            divider = DividerMode.ETCHED,
            panelBorder = PanelBorder.BEVEL,
            zebraRows = false,
            disabledInk = SundaysPalette.Win2000DisabledInk,
            syntax = if (useDark) SundaysPalette.Win2000DarkSyntax else SundaysPalette.Win2000LightSyntax,
        )
        WIN_XP -> UiThemeTokens(
            chrome = ChromeMode.CLASSIC,
            bevel = bevelStyle(useDark),
            buttonFace = ButtonFace.WINDOW,
            fieldFace = FieldFace.SURFACE_VARIANT,
            selection = SelectionMode.INVERTED,
            divider = DividerMode.ETCHED,
            panelBorder = PanelBorder.BEVEL,
            zebraRows = false,
            disabledInk = SundaysPalette.WinXpDisabledInk,
            syntax = if (useDark) SundaysPalette.WinXpDarkSyntax else SundaysPalette.WinXpLightSyntax,
        )
    }

    /**
     * 取实际生效的配色。
     *
     * ⚠️ 参数名不能叫 `dark` —— 会遮蔽上面的 `dark` 配色属性，`if (dark) dark else light`
     * 会退化成 `Any`（布尔与 ColorScheme 的最小公共父类型）。这正是本仓库
     * `shared/ARCHITECTURE.md` §2.2 记录过的同名遮蔽陷阱。
     *
     * @param useDark 是否使用深色配色
     */
    fun schemeFor(useDark: Boolean): ColorScheme = if (useDark) dark else light
}
