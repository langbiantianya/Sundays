package com.kxxnzstdsw.sundays.ui

import androidx.compose.material3.ColorScheme

/**
 * 配色主题轴 —— 与 [ThemeMode]（明暗轴）**正交**的两个维度之一。
 *
 * ## 为什么要拆成两个轴
 *
 * 配色由「色相 + 明暗」共同决定，把两者混进一个枚举会产生 `2^N` 个组合项且互相重复
 * （蓝灰浅 / 赛博浅 / 哔哩粉浅 / 蓝灰深 / 赛博深 / 哔哩粉深 = 6 项，其中「明暗」在每项里
 * 都被重复描述了一遍）。拆开后：主题 3 项 × 明暗 3 档 = 9 种外观，而设置页是两个独立的
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
    BILI_PINK("哔哩粉", "暖玫瑰粉配色，品牌粉在浅色档已压深成酒红");

    /** 该主题的浅色配色。 */
    val light: ColorScheme
        get() = when (this) {
            BLUE_GRAY -> SundaysPalette.LightColorScheme
            CYBERPUNK -> SundaysPalette.CyberpunkLightColorScheme
            BILI_PINK -> SundaysPalette.BiliPinkLightColorScheme
        }

    /** 该主题的深色配色。 */
    val dark: ColorScheme
        get() = when (this) {
            BLUE_GRAY -> SundaysPalette.DarkColorScheme
            CYBERPUNK -> SundaysPalette.CyberpunkDarkColorScheme
            BILI_PINK -> SundaysPalette.BiliPinkDarkColorScheme
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
