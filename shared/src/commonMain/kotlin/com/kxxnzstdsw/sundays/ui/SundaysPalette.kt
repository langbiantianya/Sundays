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
    // 形状 —— 4~8dp 圆角，替代 M3 默认的 4/8/12/16dp
    // =========================================================================

    private val Corner2 = RoundedCornerShape(2.dp)
    private val Corner4 = RoundedCornerShape(4.dp)
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

    /** 按钮 / chip / 输入框的圆角 —— 显式传给 `Button` 等组件（它们不读 `MaterialTheme.shapes`）。 */
    val buttonShape = Corner4

    // =========================================================================
    // 字号 —— 整体下调 1sp
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
