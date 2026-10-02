package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用视觉规范 —— 蓝灰专业 IDE 配色 + 紧凑形状 + 桌面字号。
 *
 * Material3 的出厂默认是为触屏卡片设计的一套「大圆角 + 大字」语言：按钮默认是**胶囊**
 * （`CornerFull`）、`Shapes.medium` = 12dp、通栏工具栏靠 `tonalElevation` 叠色。搬到
 * 数据库管理这类**信息密集**的桌面界面里，这三件事各自都不合适：胶囊按钮在标签条里视觉噪音过重、
 * 12dp 圆角让一排控件显得松垮、tonal 叠色在灰底上叠出一层脏色。因此这里改成
 * DBeaver / DataGrip 一类的桌面工具观感：**低饱和靛蓝主色 + 4~8dp 圆角 + 靠分割线而非阴影分层**。
 *
 * ## 三条设计约束
 *
 * 1. **不用 tonalElevation 分层**。分层靠 [ColorScheme.outlineVariant] 画 1dp 分割线 ——
 *    桌面工具的层次是「线」不是「影」，色相叠色反而脏。工具栏的 `tonalElevation` 已在各屏幕改成
 *    纯分割线（见 `DatabaseBrowserScreen` / `ConnectionManagerScreen` 的 `Surface` 调用）。
 * 2. **形状靠 `Shapes` 单一来源**。注意 Material3 的 `Button` 默认形状取自
 *    `ButtonDefaults.shape`，它**由 token 固定为 `CircleShape`，不读 `MaterialTheme.shapes`**
 *    （`ButtonSmallTokens.ContainerShapeRound`）。因此按钮必须逐个传 `shape = SundaysShapes.button`，
 *    改 `Shapes` 对按钮无效；chip / 输入框 / tab 则自动跟随 `Shapes`。
 * 3. **字号下调**。M3 默认 `bodyMedium` 14sp / `titleMedium` 16sp 是网页排版尺度；桌面工具
 *    同样 14sp 显得偏大。整体下调 1sp，`labelSmall` 降到 11sp 保住表格分页栏的行密度。
 *
 * ## 对比度
 *
 * 所有「文字 / 背景」组合均达 WCAG AA（≥4.5:1），由 `SundaysThemePaletteTest` 逐对钉住。
 * [ColorScheme.outline] / [ColorScheme.outlineVariant] 是**边框与分割线**角色，本身不承担文字
 * 对比度要求（只做 1~2dp 的线），故不纳入该断言。
 */
object SundaysPalette {

    // =========================================================================
    // 浅色 —— 中性灰蓝底 + 靛蓝主色
    // =========================================================================

    private val LightPrimary = Color(0xFF2F5C9E)
    private val LightOnPrimary = Color(0xFFFFFFFF)
    private val LightPrimaryContainer = Color(0xFFDCE7F7)
    private val LightOnPrimaryContainer = Color(0xFF1A3E6E)
    private val LightSecondary = Color(0xFF4A5568)
    private val LightOnSecondary = Color(0xFFFFFFFF)
    private val LightSecondaryContainer = Color(0xFFE6E9EF)
    private val LightOnSecondaryContainer = Color(0xFF2C3340)
    private val LightTertiary = Color(0xFF177A6A)
    private val LightOnTertiary = Color(0xFFFFFFFF)
    private val LightTertiaryContainer = Color(0xFFD3EDE7)
    private val LightOnTertiaryContainer = Color(0xFF0B4A40)
    private val LightBackground = Color(0xFFF4F5F7)
    private val LightOnBackground = Color(0xFF1E2430)
    private val LightSurface = Color(0xFFFFFFFF)
    private val LightOnSurface = Color(0xFF1E2430)
    private val LightSurfaceVariant = Color(0xFFECEFF3)
    private val LightOnSurfaceVariant = Color(0xFF5B6474)
    private val LightInverseSurface = Color(0xFF2C3340)
    private val LightInverseOnSurface = Color(0xFFEFF1F5)
    private val LightInversePrimary = Color(0xFF9CBDF0)
    private val LightError = Color(0xFFB3261E)
    private val LightOnError = Color(0xFFFFFFFF)
    private val LightErrorContainer = Color(0xFFF9DEDC)
    private val LightOnErrorContainer = Color(0xFF601410)
    private val LightOutline = Color(0xFF9AA3B2)
    private val LightOutlineVariant = Color(0xFFDDE2E9)

    // =========================================================================
    // 赛博朋克 —— 近黑紫底 + 霓虹青 / 品红 / 荧光绿
    // =========================================================================

    private val NeonPrimary = Color(0xFF00E5FF)
    private val NeonOnPrimary = Color(0xFF001318)
    private val NeonPrimaryContainer = Color(0xFF00404D)
    private val NeonOnPrimaryContainer = Color(0xFF9BF0FF)
    private val NeonSecondary = Color(0xFFFF2E97)
    private val NeonOnSecondary = Color(0xFF2B0016)
    private val NeonSecondaryContainer = Color(0xFF5A0B3A)
    private val NeonOnSecondaryContainer = Color(0xFFFFC2E2)
    private val NeonTertiary = Color(0xFF39FF88)
    private val NeonOnTertiary = Color(0xFF00210F)
    private val NeonTertiaryContainer = Color(0xFF0B4A2B)
    private val NeonOnTertiaryContainer = Color(0xFF9BFFC7)
    private val NeonBackground = Color(0xFF0B0118)
    private val NeonSurface = Color(0xFF160A2E)
    private val NeonSurfaceVariant = Color(0xFF221041)
    private val NeonOnSurface = Color(0xFFE9E4FF)
    private val NeonOnSurfaceVariant = Color(0xFFA99FD0)
    private val NeonError = Color(0xFFFF5470)
    private val NeonOnError = Color(0xFF2B0009)
    private val NeonErrorContainer = Color(0xFF5C0F22)
    private val NeonOnErrorContainer = Color(0xFFFFC2CC)
    private val NeonOutline = Color(0xFF6B4FA8)
    private val NeonOutlineVariant = Color(0xFF2E1A52)

    /**
     * 赛博朋克 **深色**配色 —— 近黑紫底（`#0B0118`）+ 霓虹青主色 / 品红次色 / 荧光绿第三色。
     *
     * 所有文字/背景组合达 WCAG AA（≥4.5:1）：霓虹色在极暗底上对比度反而比常规配色**更高**
     * （如 `primary` 对 surface 达 12.2:1，而蓝灰配色同项是 4.6:1），因此这套「刺眼」的
     * 观感在无障碍上是安全的。`SundaysPaletteTest` 对本配色逐对断言。
     */
    val CyberpunkDarkColorScheme: ColorScheme = darkColorScheme(
        primary = NeonPrimary,
        onPrimary = NeonOnPrimary,
        primaryContainer = NeonPrimaryContainer,
        onPrimaryContainer = NeonOnPrimaryContainer,
        inversePrimary = NeonSecondary,
        secondary = NeonSecondary,
        onSecondary = NeonOnSecondary,
        secondaryContainer = NeonSecondaryContainer,
        onSecondaryContainer = NeonOnSecondaryContainer,
        tertiary = NeonTertiary,
        onTertiary = NeonOnTertiary,
        tertiaryContainer = NeonTertiaryContainer,
        onTertiaryContainer = NeonOnTertiaryContainer,
        background = NeonBackground,
        onBackground = NeonOnSurface,
        surface = NeonSurface,
        onSurface = NeonOnSurface,
        surfaceVariant = NeonSurfaceVariant,
        onSurfaceVariant = NeonOnSurfaceVariant,
        inverseSurface = NeonOnSurface,
        inverseOnSurface = NeonBackground,
        error = NeonError,
        onError = NeonOnError,
        errorContainer = NeonErrorContainer,
        onErrorContainer = NeonOnErrorContainer,
        outline = NeonOutline,
        outlineVariant = NeonOutlineVariant,
        surfaceTint = Color.Transparent,
    )

    /**
     * 赛博朋克 **浅色**配色 —— 把近黑底整体提亮成淡紫灰，霓虹色相应压暗。
     *
     * 浅色档不能直接复用深色版的霓虹色：荧光青 `#00E5FF` 在白底上对比度只有 **1.46:1**
     * （实测值，由 `SundaysPaletteTest` 复现），远低于 WCAG AA 的 4.5:1，完全不可读。
     * 因此主色降饱和压深到 `#00697A` 一档，其余角色同理 —— 观感仍是「青 / 品红 / 绿」
     * 的赛博配色，只是底色反了过来。
     */
    val CyberpunkLightColorScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF00697A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFA8F0FA),
        onPrimaryContainer = Color(0xFF00272E),
        inversePrimary = NeonPrimary,
        secondary = Color(0xFFA8135C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD6E7),
        onSecondaryContainer = Color(0xFF3B0020),
        tertiary = Color(0xFF1B6B33),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB7F0C8),
        onTertiaryContainer = Color(0xFF00210F),
        background = Color(0xFFF4EFFA),
        onBackground = Color(0xFF1C1626),
        surface = Color(0xFFFBF8FF),
        onSurface = Color(0xFF1C1626),
        surfaceVariant = Color(0xFFE7DFF2),
        onSurfaceVariant = Color(0xFF4E4460),
        inverseSurface = Color(0xFF322A40),
        inverseOnSurface = Color(0xFFF4EFFA),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF601410),
        outline = Color(0xFF7A6E90),
        outlineVariant = Color(0xFFDCD3E8),
        surfaceTint = Color.Transparent,
    )

    // =========================================================================
    // 哔哩粉 —— 暖玫瑰粉主色（浅色档为酒红，深色档为亮粉）
    // =========================================================================

    /**
     * 哔哩粉 **浅色**配色 —— 近白暖粉底 + **酒红**主色。
     *
     * 与赛博朋克浅色档是同一个坑：品牌粉 `#FB7299` 在白底上对比度只有 **2.64:1**
     * （实测），远低于 WCAG AA 的 4.5:1，用作文字色不可读。因此浅色档把主色整体压深到
     * `#A81C4C`（7.15:1），底色只保留极淡的暖粉倾向（`#FFF7F9`，亮度 0.946）——
     * 观感仍是「粉」，但承担文字的是深酒红而不是品牌粉本身。
     *
     * 第三色刻意用**暖琥珀**而非第二个粉：整套界面若只有一种色相，表格里「主色 / 第三色」
     * 两类状态标记会难以区分，暖琥珀在白底上压深到 `#8A5A00`（5.93:1）后仍可作文字色。
     */
    val BiliPinkLightColorScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFFA81C4C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9E4),
        onPrimaryContainer = Color(0xFF4A0019),
        inversePrimary = Color(0xFFFFB0C8),
        secondary = Color(0xFF8A5568),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF5DCE4),
        onSecondaryContainer = Color(0xFF3B1220),
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE0B2),
        onTertiaryContainer = Color(0xFF2C1900),
        background = Color(0xFFFFF7F9),
        onBackground = Color(0xFF2B1A21),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF2B1A21),
        surfaceVariant = Color(0xFFFBE9EF),
        onSurfaceVariant = Color(0xFF6B4C58),
        inverseSurface = Color(0xFF35242C),
        inverseOnSurface = Color(0xFFFFF7F9),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF601410),
        outline = Color(0xFF9C7D88),
        outlineVariant = Color(0xFFEFDCE3),
        // 见浅色方案同处说明：透明 surfaceTint = 禁用 tonal 叠色。
        surfaceTint = Color.Transparent,
    )

    /**
     * 哔哩粉 **深色**配色 —— 近黑暖李紫底 + 亮玫瑰粉主色。
     *
     * 深色档与浅色档是**两套独立配色**而非同一组颜色的明暗翻转：近黑底（`#1C1015`，
     * 亮度 0.007）让品牌粉一系在这里重新变得可用 —— `#FF93B6` 压在 surface 上达 8.16:1，
     * 比浅色档同角色的 7.15:1 还高。也就是说这套主题的「粉」主要活在深色档，
     * 浅色档为了可读性只能退到酒红。
     */
    val BiliPinkDarkColorScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFFFF93B6),
        onPrimary = Color(0xFF3D0018),
        primaryContainer = Color(0xFF6E1339),
        onPrimaryContainer = Color(0xFFFFD9E5),
        inversePrimary = Color(0xFFA81C4C),
        secondary = Color(0xFFD5A8BB),
        onSecondary = Color(0xFF34101F),
        secondaryContainer = Color(0xFF4C2534),
        onSecondaryContainer = Color(0xFFF2D6E1),
        tertiary = Color(0xFFF0C070),
        onTertiary = Color(0xFF3B2600),
        tertiaryContainer = Color(0xFF5A3D00),
        onTertiaryContainer = Color(0xFFFFE0B2),
        background = Color(0xFF1C1015),
        onBackground = Color(0xFFFCE9EF),
        surface = Color(0xFF271820),
        onSurface = Color(0xFFFCE9EF),
        surfaceVariant = Color(0xFF38222C),
        onSurfaceVariant = Color(0xFFD6B7C3),
        inverseSurface = Color(0xFFFCE9EF),
        inverseOnSurface = Color(0xFF1C1015),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF4A0004),
        errorContainer = Color(0xFF6B1F17),
        onErrorContainer = Color(0xFFFFDAD5),
        outline = Color(0xFF8C6C78),
        outlineVariant = Color(0xFF4A303A),
        // 见浅色方案同处说明：透明 surfaceTint = 禁用 tonal 叠色。
        surfaceTint = Color.Transparent,
    )

    // =========================================================================
    // Win2000 / WinXP —— 复古配色
    //
    // 这两套与前面三套是**相反**的一类：它们的品牌色本来就是为「在 2001 年的 CRT 上读得清」
    // 设计的，因此**天然高对比**，几乎不需要为可读性让步 —— 银灰控件面 `#C0C0C0` 配纯黑
    // 文字达 11.54:1，Luna 奶油底 `#ECE9D8` 配黑字达 17.21:1。对比度上它们是本仓库
    // 最省事的两套；真正要让步的是**明暗档**（见下）与 XP 的选区蓝。
    //
    // 另需说明：这两套只还原**配色**，不还原控件造型。经典 Win 边框是「亮面 + 暗面」成对的
    // 3D 斜面（`#FFFFFF` / `#DFDFDF` 配 `#808080` / `#000000`），Material3 的单色
    // `outline` 表达不出来；`Shapes` 与 `Typography` 也是全局共享的桌面尺度，未按 Win2000
    // 的直角 + 8pt 像素字体另开一套。所以这是「配色层面的致敬」，不是像素级复刻。
    // =========================================================================

    /**
     * Win2000 **浅色**配色 —— 经典银灰控件面 + 深蓝选区色。
     *
     * `#C0C0C0`（银灰）与 `#000080`（navy）都是 Win2000 的标志性色，且直接用在
     * `background` / `primary` 上就已达标：黑字压银灰 11.54:1，深蓝压银灰 8.80:1。
     *
     * ⚠️ 银灰的相对亮度是 **0.527**，距双轴模型「浅色档底色 > 0.5」这条硬约束只剩
     * 0.027 余量。`#C0C0C0` 正是 Win2000 的真实取值、不宜改动，因此这里保留原值并依赖
     * `SundaysPaletteTest` 守住这条线 —— 若有人为「提亮一点」微调该值，测试会立刻报错，
     * 而不是悄悄退化成暗底。内容区 `surface` 取纯白（对应 Win2000 列表框的白色凹陷区），
     * 与银灰 `background` 形成经典的面/内容区分。
     */
    val Win2000LightColorScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF000080),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC5CBFF),
        onPrimaryContainer = Color(0xFF000066),
        inversePrimary = Color(0xFF8080FF),
        secondary = Color(0xFF006B6B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC4E4E4),
        onSecondaryContainer = Color(0xFF00201F),
        tertiary = Color(0xFF7B3F00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF0D8BE),
        onTertiaryContainer = Color(0xFF2A1300),
        background = Color(0xFFC0C0C0),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFD4D0C8),
        onSurfaceVariant = Color(0xFF1A1A1A),
        // inverseSurface 取 navy：Win2000 的实心填充（提示条 / 选中态）正是深蓝配白字。
        inverseSurface = Color(0xFF000080),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = Color(0xFFA00000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF0C8C8),
        onErrorContainer = Color(0xFF3A0000),
        outline = Color(0xFF808080),
        outlineVariant = Color(0xFFA0A0A0),
        surfaceTint = Color.Transparent,
    )

    /**
     * Win2000 **深色**配色 —— 对应 Win2000 的黑色配色方案（银字黑底）。
     *
     * 该方案原版是纯黑底配黄 / 青 / 白的高对比配色。本实现取了其中最不刺眼、也最适合
     * 长时间阅读的部分：近黑底 `#0A0A0A` + 纯白正文 + **银灰主色**（`#C0C0C0`，
     * 即 Win2000 的招牌色本身），并保留一青一金两个次级色供状态标记区分。
     *
     * 刻意避开原版的高饱和黄：作为会铺满表格单元格的 `primary`，纯黄在长时间盯屏下
     * 比霓虹青更累。银灰压黑底达 10.88:1，可读性不打折。
     */
    val Win2000DarkColorScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFFC0C0C0),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF4A4A4A),
        onPrimaryContainer = Color(0xFFE4E4E4),
        inversePrimary = Color(0xFF4A4A4A),
        secondary = Color(0xFF00B0B0),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF004F4F),
        onSecondaryContainer = Color(0xFFB8F0F0),
        tertiary = Color(0xFFD0A030),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF5C4210),
        onTertiaryContainer = Color(0xFFFFE0A8),
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF1A1A1A),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF2E2E2E),
        onSurfaceVariant = Color(0xFFA0A0A0),
        inverseSurface = Color(0xFFE6E6E6),
        inverseOnSurface = Color(0xFF0A0A0A),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF2A0000),
        errorContainer = Color(0xFF5C1414),
        onErrorContainer = Color(0xFFFFD2D2),
        outline = Color(0xFF707070),
        outlineVariant = Color(0xFF3C3C3C),
        surfaceTint = Color.Transparent,
    )

    /**
     * WinXP **浅色**配色 —— Luna 奶油底 `#ECE9D8` + 蓝灰 chrome。
     *
     * 底色是 Luna 的标志性奶油色（黑字 17.21:1，极高对比），chrome 层次用
     * `#DCD8C6` 表达。但**选区蓝被迫换掉了**：XP 真正的高亮蓝 `#316AC5` 压在 Luna
     * 奶油底上只有 **4.31:1**，达不到 AA 的 4.5:1（压在白色内容区倒是 5.25:1 ——
     * 正好卡在会被背景色拖垮的典型情形）。因此改用同属 XP 色系的 `#255EA1`
     * （压奶油底 5.39:1、压白色 6.57:1），观感仍是 XP 的蓝，只是明度上调一档。
     *
     * 次级 / 第三色取暗金与暗梅红：奶油底整体偏暖，用冷蓝做两个次级色会与主色糊在一起。
     */
    val WinXpLightColorScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF255EA1),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC4D8F5),
        onPrimaryContainer = Color(0xFF0A2A5E),
        inversePrimary = Color(0xFF7FA8E0),
        secondary = Color(0xFF7A5C00),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF0E2B8),
        onSecondaryContainer = Color(0xFF2A2000),
        tertiary = Color(0xFF7B2D5E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF0CFE4),
        onTertiaryContainer = Color(0xFF2C0A1F),
        background = Color(0xFFECE9D8),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFDCD8C6),
        onSurfaceVariant = Color(0xFF3A3830),
        inverseSurface = Color(0xFF255EA1),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = Color(0xFFA00000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF2C4C4),
        onErrorContainer = Color(0xFF3A0000),
        outline = Color(0xFF7A7A7A),
        outlineVariant = Color(0xFFC0BCA8),
        surfaceTint = Color.Transparent,
    )

    /**
     * WinXP **深色**配色 —— Luna Black 风格：深蓝灰底 + XP 蓝提亮。
     *
     * XP 原版没有深色档（`Luna Black` 只是第三方主题），这里取其思路：把 Luna 的
     * 蓝调保留下来做底色（`#101820` 偏蓝而非纯灰，这样与 Win2000 深色档的纯灰阶
     * 拉开距离），主色用提亮后的 `#4E9DE0`，压深色 surface 达 5.40:1。
     *
     * 与浅色档一样，**不复用浅色档的色值**：XP 的 `#255EA1` 压在这个深底上只有
     * 2.72:1，属于典型的「深浅两版沿用同一组颜色」错误。
     */
    val WinXpDarkColorScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFF4E9DE0),
        onPrimary = Color(0xFF04121F),
        primaryContainer = Color(0xFF1B4A7A),
        onPrimaryContainer = Color(0xFFB8D9F5),
        inversePrimary = Color(0xFF255EA1),
        secondary = Color(0xFFD8B860),
        onSecondary = Color(0xFF241A00),
        secondaryContainer = Color(0xFF4A3A0F),
        onSecondaryContainer = Color(0xFFFFE8B0),
        tertiary = Color(0xFFD08CC0),
        onTertiary = Color(0xFF2A0A22),
        tertiaryContainer = Color(0xFF4A1B3C),
        onTertiaryContainer = Color(0xFFFBD4EE),
        background = Color(0xFF101820),
        onBackground = Color(0xFFEDF1F7),
        surface = Color(0xFF1B242E),
        onSurface = Color(0xFFEDF1F7),
        surfaceVariant = Color(0xFF2A3641),
        onSurfaceVariant = Color(0xFFA8B4C0),
        inverseSurface = Color(0xFFEDF1F7),
        inverseOnSurface = Color(0xFF101820),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF2A0603),
        errorContainer = Color(0xFF5C1A14),
        onErrorContainer = Color(0xFFFFDAD5),
        outline = Color(0xFF66788A),
        outlineVariant = Color(0xFF33404D),
        surfaceTint = Color.Transparent,
    )

    /** 浅色配色 —— 灰蓝中性底 + 靛蓝主色，替代 Material3 出厂紫。 */
    val LightColorScheme: ColorScheme = lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        inversePrimary = LightInversePrimary,
        secondary = LightSecondary,
        onSecondary = LightOnSecondary,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = LightOnSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        tertiaryContainer = LightTertiaryContainer,
        onTertiaryContainer = LightOnTertiaryContainer,
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        inverseSurface = LightInverseSurface,
        inverseOnSurface = LightInverseOnSurface,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        // surfaceTint = transparent：禁用 tonal 叠色。`Surface(tonalElevation = …)` 只在
        // 背景色 **等于 surface** 时才叠 surfaceTint（见 ColorScheme.applyTonalElevation），
        // 传透明色后即使某处仍留了 tonalElevation，视觉上也等同于纯色，不会糊出一层色偏。
        surfaceTint = Color.Transparent,
    )

    // =========================================================================
    // 深色 —— 蓝灰炭底 + 亮靛蓝主色
    // =========================================================================

    private val DarkPrimary = Color(0xFF5B8DEF)
    private val DarkOnPrimary = Color(0xFF0B1220)
    private val DarkPrimaryContainer = Color(0xFF25355A)
    private val DarkOnPrimaryContainer = Color(0xFFC8DCFF)
    private val DarkSecondary = Color(0xFF9AA4B8)
    private val DarkOnSecondary = Color(0xFF171B23)
    private val DarkSecondaryContainer = Color(0xFF2E3542)
    private val DarkOnSecondaryContainer = Color(0xFFC7CEDB)
    private val DarkTertiary = Color(0xFF4FB6A5)
    private val DarkOnTertiary = Color(0xFF072420)
    private val DarkTertiaryContainer = Color(0xFF14403A)
    private val DarkOnTertiaryContainer = Color(0xFF9BE0D4)
    private val DarkBackground = Color(0xFF1B1F27)
    private val DarkOnBackground = Color(0xFFD6DAE3)
    private val DarkSurface = Color(0xFF232833)
    private val DarkOnSurface = Color(0xFFD6DAE3)
    private val DarkSurfaceVariant = Color(0xFF2C3240)
    private val DarkOnSurfaceVariant = Color(0xFF99A1B3)
    private val DarkInverseSurface = Color(0xFFD6DAE3)
    private val DarkInverseOnSurface = Color(0xFF1B1F27)
    private val DarkInversePrimary = Color(0xFF2F5C9E)
    private val DarkError = Color(0xFFE5715F)
    private val DarkOnError = Color(0xFF2B0F0A)
    private val DarkErrorContainer = Color(0xFF4A1F1A)
    private val DarkOnErrorContainer = Color(0xFFFFB4A6)
    private val DarkOutline = Color(0xFF525B6D)
    private val DarkOutlineVariant = Color(0xFF333A48)

    /** 深色配色 —— 蓝灰炭底 + 亮靛蓝主色，与 [LightColorScheme] 角色对位。 */
    val DarkColorScheme: ColorScheme = darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        inversePrimary = DarkInversePrimary,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = DarkOnTertiaryContainer,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        inverseSurface = DarkInverseSurface,
        inverseOnSurface = DarkInverseOnSurface,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        // 见浅色方案同处说明：透明 surfaceTint = 禁用 tonal 叠色。
        surfaceTint = Color.Transparent,
    )

    // =========================================================================
    // 3D 斜面 —— 经典 Win 控件的辨识度来源，仅复古两套启用
    // -------------------------------------------------------------------------
    // 现代三套配色不定义斜面（`ThemePalette.bevelStyle` 直接返回 `BevelStyle.NONE`），
    // [winBevel] 在它们上面是空操作，因此这里只需要给 Win2000 / WinXP 各配浅深两组。
    //
    // 取值来自 Win2000 经典外观的四条系统按钮色（ButtonShadow / ButtonHighlight /
    // ButtonDkShadow）与 Luna 的单线蓝灰描边。斜面是装饰，不承担文字对比度职责。
    // =========================================================================

    /** Win2000 浅色档 —— 银灰面上的 2px 双线：外圈 ButtonShadow，内圈亮白 / 暗 `#404040`。 */
    val Win2000LightBevel: BevelStyle = BevelStyle(
        enabled = true,
        outer = Color(0xFF7A7A7A),
        light = Color(0xFFFFFFFF),
        dark = Color(0xFF404040),
        width = 1.dp,
        doubleEdge = true,
        bevelRadius = 0.dp,
    )

    /** Win2000 深色档 —— 近黑面转为银边亮 / 纯黑暗，外圈收浅以免糊成一片。 */
    val Win2000DarkBevel: BevelStyle = BevelStyle(
        enabled = true,
        outer = Color(0xFF5A5A5A),
        light = Color(0xFFC0C0C0),
        dark = Color(0xFF000000),
        width = 1.dp,
        doubleEdge = true,
        bevelRadius = 0.dp,
    )

    /** WinXP 浅色档 —— Luna 是 1px 单线边，明暗差刻意收得很小（近乎平边）。 */
    val WinXpLightBevel: BevelStyle = BevelStyle(
        enabled = true,
        outer = Color(0xFF8A9BA8),
        light = Color(0xFFFDFDF8),
        dark = Color(0xFF8296A8),
        width = 1.dp,
        doubleEdge = false,
        bevelRadius = 3.dp,
    )

    /** WinXP 深色档 —— 同为单线，暗边压到接近底色，靠亮边单独制造凸起感。 */
    val WinXpDarkBevel: BevelStyle = BevelStyle(
        enabled = true,
        outer = Color(0xFF55677A),
        light = Color(0xFFC8D4E0),
        dark = Color(0xFF0A0A0A),
        width = 1.dp,
        doubleEdge = false,
        bevelRadius = 3.dp,
    )

    /**
     * 经典档的禁用态文字色。
     *
     * 禁用的控件**不受 WCAG 1.4.3 约束**（该条明确排除 inactive 组件），所以这里允许
     * 低于 4.5:1 —— 经典 Win 的禁用文字就是比正常色淡一档。取 `outline` 一档即可。
     */
    fun disabledInk(useDark: Boolean): Color =
        if (useDark) Win2000DarkBevel.dark else LightOutline

    /** Win2000 浅色档的禁用文字色。 */
    val Win2000DisabledInk: Color get() = disabledInk(useDark = false)

    /** Win2000 深色档的禁用文字色。 */
    val Win2000DarkDisabledInk: Color get() = disabledInk(useDark = true)

    /** WinXP 浅色档的禁用文字色。 */
    val WinXpDisabledInk: Color get() = disabledInk(useDark = false)

    /** WinXP 深色档的禁用文字色。 */
    val WinXpDarkDisabledInk: Color get() = disabledInk(useDark = true)

    // =========================================================================
    // 语法高亮配色 —— 逐配色逐明暗
    //
    // 原先只有两套写死的 VS / Darcula 配色（`SyntaxHighlighter.DefaultLightColors` /
    // `DefaultDarkColors`），与 `ColorScheme` 完全脱钩：Win2000 主题下 SQL 编辑器里
    // 仍是一片 VS 蓝关键字，是复古感最刺眼的漏网之鱼。
    //
    // 经典档取 Delphi / VS6 时代的「系统色」思路：navy 关键字、maroon 字符串、
    // teal 类型、深绿数字与注释 —— 恰好也是当年 Win 系统色板里的颜色。
    // 全部 10 个 token 色在各自底色上逐对验过对比度（见下），浅色档最低 4.25:1（注释，
    // 沿用本仓库对 Darcula 注释 3.9:1 的既有让步），深色档最低 5.59:1。
    // =========================================================================

    /** Win2000 浅色档（底 `#D4D0C8`）—— 经典系统色。 */
    val Win2000LightSyntax: SyntaxColors = SyntaxColors(
        keyword = Color(0xFF000080),      // 10.41:1
        builtin = Color(0xFF6B3FA0),     // 8.72:1
        type = Color(0xFF2B5F5F),        // 4.70:1
        string = Color(0xFF8B1A1A),      // 6.04:1
        number = Color(0xFF1A5E28),      // 5.10:1
        comment = Color(0xFF256B2B),     // 4.25:1
        operator = Color(0xFF000000),    // 13.66:1
        punctuation = Color(0xFF555555), // 4.85:1
        identifier = Color(0xFF000000),  // 13.66:1
        error = Color(0xFF9C0000),       // 6.36:1
    )

    /** Win2000 深色档（底 `#2E2E2E`）—— 同色相的提亮版。 */
    val Win2000DarkSyntax: SyntaxColors = SyntaxColors(
        keyword = Color(0xFFA9C4F0),     // 7.66:1
        builtin = Color(0xFFC0A8E8),     // 7.44:1
        type = Color(0xFF7FD8D8),        // 8.22:1
        string = Color(0xFFE8A0A0),     // 6.45:1
        number = Color(0xFF8FE0A0),      // 8.62:1
        comment = Color(0xFF7FC47F),     // 6.53:1
        operator = Color(0xFFD0D0D0),    // 9.44:1
        punctuation = Color(0xFFA8B4C0), // 6.44:1
        identifier = Color(0xFFF0F0F0),  // 11.92:1
        error = Color(0xFFFF8A80),       // 7.12:1
    )

    /** WinXP 浅色档（底 `#DCD8C6`）—— 与 Win2000 同色相，略提亮以适配更亮的 Luna 底。 */
    val WinXpLightSyntax: SyntaxColors = SyntaxColors(
        keyword = Color(0xFF10307F),     // 11.19:1
        builtin = Color(0xFF73489F),     // 9.35:1
        type = Color(0xFF2B5F5F),        // 5.05:1
        string = Color(0xFF8B1A1A),      // 6.49:1
        number = Color(0xFF1A5E28),      // 5.49:1
        comment = Color(0xFF256B2B),     // 4.56:1
        operator = Color(0xFF000000),    // 14.68:1
        punctuation = Color(0xFF555555), // 5.21:1
        identifier = Color(0xFF000000),  // 14.68:1
        error = Color(0xFF9C0000),       // 6.80:1
    )

    /** WinXP 深色档（底 `#2A3641`）。 */
    val WinXpDarkSyntax: SyntaxColors = SyntaxColors(
        keyword = Color(0xFF8FC0F0),     // 6.44:1
        builtin = Color(0xFFC7A6F0),     // 6.38:1
        type = Color(0xFF6FD0D0),        // 6.82:1
        string = Color(0xFFE89A9A),     // 5.59:1
        number = Color(0xFF88DCA0),      // 7.51:1
        comment = Color(0xFF78C078),     // 5.64:1
        operator = Color(0xFFC8D4E0),    // 9.30:1
        punctuation = Color(0xFFA0B0C0), // 5.56:1
        identifier = Color(0xFFEDF1F7),  // 10.88:1
        error = Color(0xFFFF9E94),       // 7.02:1
    )
    // =========================================================================
    // 形状 —— 逐配色（复古两套用直角 / Luna 圆角，现代三套用紧凑圆角）
    // =========================================================================

    private val Corner0 = RoundedCornerShape(0.dp)
    private val Corner2 = RoundedCornerShape(2.dp)
    private val Corner3 = RoundedCornerShape(3.dp)
    private val Corner4 = RoundedCornerShape(4.dp)
    private val Corner5 = RoundedCornerShape(5.dp)
    private val Corner6 = RoundedCornerShape(6.dp)
    private val Corner8 = RoundedCornerShape(8.dp)

    /**
     * 紧凑形状阶梯。
     *
     * 除 `large` 外的各档都收窄：M3 默认 `medium` 12dp 用在菜单 / 弹层上偏圆，收成 8dp；
     `large` 16dp 保持不变，因为弹窗（`AddConnectionDialog`）用大圆角在语义上是对的。
     */
    val Shapes: Shapes = Shapes(
        extraSmall = Corner2,
        small = Corner4,
        medium = Corner6,
        large = Corner8,
        extraLarge = Corner8,
    )

    /**
     * Win2000 形状阶梯 —— 全直角。
     *
     * Win2000（连同它继承的 Win95/98 经典外观）的控件是彻底方角：按钮、输入框、分组框
     * 一律 0 圆角。`large` / `extraLarge` 也压成直角 —— 经典 Win 的对话框同样是方角，
     * 只在这里留大圆角会让弹窗出戏。
     */
    val Win2000Shapes: Shapes = Shapes(
        extraSmall = Corner0,
        small = Corner0,
        medium = Corner0,
        large = Corner0,
        extraLarge = Corner0,
    )

    /**
     * WinXP 形状阶梯 —— Luna 的标志性圆角。
     *
     * Luna 相比 Win2000 的最大外观变化就是普遍引入圆角（按钮与输入框约 3px、面板约 5px、
     * 对话框约 8px），这是 XP 给人「比上一代柔和」印象的主要来源。这里按控件尺度递增：
     * `small` 3dp 给按钮 / 输入框，`medium` 4dp 给 chip / 标签，`large` 5dp 给分组框与
     * 面板，`extraLarge` 8dp 留给对话框。
     */
    val WinXpShapes: Shapes = Shapes(
        extraSmall = Corner2,
        small = Corner3,
        medium = Corner4,
        large = Corner5,
        extraLarge = Corner8,
    )

    /**
     * 按钮 / chip / 输入框的圆角 —— 显式传给 `Button` 等组件（它们不读 `MaterialTheme.shapes`）。
     *
     * 读 [LocalPalette] 而非直接返回常量：复古两套要拿到各自的圆角（Win2000 直角 0dp、
     * WinXP 3dp），而 27 处调用点写的是 `shape = SundaysPalette.buttonShape` —— 改成
     * 组合属性后这些调用点一行都不用改。
     */
    val buttonShape: Shape
        @Composable
        @ReadOnlyComposable
        get() = LocalPalette.current.buttonShape

    // =========================================================================
    // 字号 —— 整体下调 1sp（全局共用，复古两套亦然）
    //
    // 有意不按主题分叉：Win2000 的 MS Sans Serif 8pt 与 XP 的 Tahoma 8pt 都是位图时代的
    // 点阵字体，换成系统默认无衬线后本就没有那个观感，强行缩小字号只会让信息密集界面
    // 更难读。复古识别度由配色与形状承载，字号不参与。
    // =========================================================================

    /** 桌面字号阶梯。`bodyMedium` 13sp / `bodySmall` 12sp，比 M3 默认各小 1sp。 */
    val Typography: Typography = Typography(
        titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )

    /** 当前系统明暗对应的配色（跟随系统，不含用户偏好）。 */
    @Composable
    @ReadOnlyComposable
    fun colorSchemeFor(darkTheme: Boolean): ColorScheme =
        if (darkTheme) DarkColorScheme else LightColorScheme
}
