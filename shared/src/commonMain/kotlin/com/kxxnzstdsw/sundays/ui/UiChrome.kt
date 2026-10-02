package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.editor.SyntaxHighlighter
import com.kxxnzstdsw.sundays.editor.TokenType

// ============================================================================
// 外观档位枚举
//
// 刻意用**枚举**而不是布尔开关（`isRetro: Boolean`）：布尔会让「再加一种外观」变成
// 给每个判断点加一个 `||`，而枚举是封闭集合 —— 新增一档只需在下面加一行，编译器
// 会把所有 `when` 的漏网之处指出来。
// ============================================================================

/** 控件造型档位。 */
enum class ChromeMode {
    /** 现代：圆角、无 3D 斜面、淡色选中底。 */
    MODERN,

    /** 经典 Win：直角 / Luna 圆角、3D 斜面、反色选中。 */
    CLASSIC,
}

/** 列表 / 表格的选中态表达。 */
enum class SelectionMode {
    /** 淡色容器底 + 深色字（Material 的现代做法）。 */
    TINTED,

    /** 整行反色：`primary` 实心填充 + `onPrimary` 文字（经典 Win 的做法）。 */
    INVERTED,
}

/** 分组线的画法。 */
enum class DividerMode {
    /** 单色实线。 */
    FLAT,

    /** 「暗 1px + 亮 1px」成对的蚀刻凹槽（经典 Win 的做法）。 */
    ETCHED,
}

/** 实心按钮的容器色取自哪个色槽。 */
enum class ButtonFace {
    /** 用 `primary`（现代实心按钮）。 */
    PRIMARY,

    /** 用 `background` 窗口面 + `onBackground`（经典 Win 的 `ButtonFace` + 黑字）。 */
    WINDOW,
}

/** 输入框的容器色取自哪个色槽。 */
enum class FieldFace {
    /** 用 `surface`。 */
    SURFACE,

    /**
     * 用 `surfaceVariant`。Win2000 浅色档的 `surface` 是纯白，而经典凹陷边有一侧就是纯白
     * `ButtonHighlight` —— 贴在纯白面上那一侧会**完全隐形**。退回「Windows Standard」
     * 的灰面，亮边才读得出来。
     */
    SURFACE_VARIANT,
}

/** 容器（面板 / 卡片）的描边画法。 */
enum class PanelBorder {
    /** 单色 1dp 描边。 */
    LINE,

    /** 3D 凸起斜面。 */
    BEVEL,

    /** 不描边。 */
    NONE,
}

/**
 * SQL / Lua 编辑器的语法高亮配色 —— 10 个 token 槽位。
 *
 * 与 [UiThemeTokens.syntax] 一同构成「编辑器长相」的完整描述。之所以单列而不是塞进
 * `ColorScheme`：语法色是**逐明暗**的（同一 token 在浅底与深底上必须是两套值），而
 * `ColorScheme` 只提供一组「已按明暗选好」的值，粒度对不上。
 *
 * 每个槽位都必须在自己那档的编辑器底色上过对比度 —— 写入时的实测值记在
 * [SundaysPalette] 对应常量的注释里，由 `CodeEditorThemeTest` 复核。
 */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val builtin: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val operator: Color,
    val punctuation: Color,
    val identifier: Color,
    val error: Color,
)

/** [SyntaxColors] -> `SyntaxHighlighter` 需要的 `Map<TokenType, Color>`。 */
fun SyntaxColors.asTokenColors(): Map<TokenType, Color> = mapOf(
    TokenType.KEYWORD to keyword,
    TokenType.BUILTIN to builtin,
    TokenType.TYPE to type,
    TokenType.STRING to string,
    TokenType.NUMBER to number,
    TokenType.COMMENT to comment,
    TokenType.OPERATOR to operator,
    TokenType.PUNCTUATION to punctuation,
    TokenType.IDENTIFIER to identifier,
    TokenType.ERROR to error,
)

/** `Map<TokenType, Color>` -> [SyntaxColors]（把既有的 VS / Darcula 配色并入 token）。 */
fun Map<TokenType, Color>.toSyntaxColors(): SyntaxColors = SyntaxColors(
    keyword = this[TokenType.KEYWORD] ?: Color.Unspecified,
    builtin = this[TokenType.BUILTIN] ?: Color.Unspecified,
    type = this[TokenType.TYPE] ?: Color.Unspecified,
    string = this[TokenType.STRING] ?: Color.Unspecified,
    number = this[TokenType.NUMBER] ?: Color.Unspecified,
    comment = this[TokenType.COMMENT] ?: Color.Unspecified,
    operator = this[TokenType.OPERATOR] ?: Color.Unspecified,
    punctuation = this[TokenType.PUNCTUATION] ?: Color.Unspecified,
    identifier = this[TokenType.IDENTIFIER] ?: Color.Unspecified,
    error = this[TokenType.ERROR] ?: Color.Unspecified,
)

// ============================================================================
// 主题 token —— 界面层唯一的「外观真相来源」
// ============================================================================

/**
 * 一套配色主题的**全部界面外观决策**。
 *
 * ## 为什么要把它抽出来
 *
 * 界面层需要的「长什么样」有七八项：3D 斜面、按钮填充色、输入框底色、选中态画法、
 * 分割线画法、容器描边、表格斑马纹……若这些散落在各组件里靠 `if (isRetro)` 判断，
 * 会出两个问题：
 *
 * 1. **加新主题要翻遍全代码**。每加一套外观就得把所有判断点再挖一遍，漏一个就出戏。
 * 2. **判断逻辑重复**。同一个决定在 20 个组件里各写一遍，改规则要改 20 处。
 *
 * 抽成 token 后：**新主题 = 在 [ThemePalette.uiTokens] 里加一个分支**，其余组件因为
 * 读的是 token 而自动生效。调用点只管问「当前主题的这个 token 是什么」，不含任何
 * 主题名判断 —— 这一点由 [UiTokensTest] 逐项断言。
 *
 * ## 与 [ColorScheme] 的分工
 *
 * [ColorScheme] 管**颜色**，token 管**怎么用这些颜色**。少数颜色仍需单独指定
 * （见 [bevel] 的四条斜面色、`disabledInk`），其余一律由 `ColorScheme` 按字段名推导，
 * 避免同一份色值在两处各写一遍而走样。
 *
 * @property chrome 控件造型档位
 * @property bevel 3D 斜面配色与几何
 * @property buttonFace 实心按钮的容器色取自 `primary` 还是窗口面
 * @property fieldFace 输入框容器色取自 `surface` 还是 `surfaceVariant`
 * @property selection 选中态画法
 * @property divider 分组线画法
 * @property panelBorder 容器描边画法
 * @property zebraRows 表格是否用隔行底色（经典 Win 的列表视图**没有**斑马纹）
 * @property disabledInk 禁用态文字色。禁用的控件不受 WCAG 1.4.3 约束（该条明确排除
 *   inactive 组件），故此处允许低于 4.5:1
 */
@Immutable
data class UiThemeTokens(
    val chrome: ChromeMode,
    val bevel: BevelStyle,
    val buttonFace: ButtonFace,
    val fieldFace: FieldFace,
    val selection: SelectionMode,
    val divider: DividerMode,
    val panelBorder: PanelBorder,
    val zebraRows: Boolean,
    val disabledInk: Color,

    /** 编辑器语法高亮配色（逐明暗）。 */
    val syntax: SyntaxColors,
) {
    /** 是否经典 Win 造型 —— 等价于 [chrome] 判断，只是给调用点读起来更直白。 */
    val isClassic: Boolean get() = chrome == ChromeMode.CLASSIC

    companion object {
        /**
         * 现代档（蓝灰 / 赛博朋克 / 哔哩粉共用）—— **不得有任何 3D 斜面**。
         *
         * [disabledInk] 留空：现代主题的禁用态由 M3 的 `ButtonDefaults` 自己算，
         * 不需要 token 介入，否则会改变现有观感。
         */
        val MODERN: UiThemeTokens = UiThemeTokens(
            chrome = ChromeMode.MODERN,
            bevel = BevelStyle.NONE,
            buttonFace = ButtonFace.PRIMARY,
            fieldFace = FieldFace.SURFACE,
            selection = SelectionMode.TINTED,
            divider = DividerMode.FLAT,
            panelBorder = PanelBorder.LINE,
            zebraRows = true,
            disabledInk = Color.Unspecified,
            // 现代档的语法色不取这里的值：CodeEditorTheme.themed() 走 VS / Darcula 自有配色。
            // 此处只放一份占位，使「每套主题都有完整 token」这条契约成立。
            syntax = SyntaxHighlighter.DefaultDarkColors.toSyntaxColors(),
        )
    }
}

/** 当前生效的外观 token —— 由 [SundaysTheme] 按 [ThemePalette] 注入。 */
val LocalUiTokens = staticCompositionLocalOf { UiThemeTokens.MODERN }

/**
 * 当前生效的 3D 斜面配色。
 *
 * 保留独立 CompositionLocal 是因为 [Modifier.uiBevel] 每帧都要读它，而 token 里其余
 * 字段用得少得多 —— 拆开可以避免斜面关闭时仍然每帧穿透读取整份 token。
 */
val LocalBevelStyle = staticCompositionLocalOf { BevelStyle.NONE }

/** 当前是否经典 Win 造型。 */
val isClassicChrome: Boolean
    @Composable @ReadOnlyComposable get() = LocalUiTokens.current.isClassic

/** 当前生效的配色主题 —— 供不在 `MaterialTheme` 体系内的取值点使用（见 `ThemePalette`）。 */
val LocalPalette = staticCompositionLocalOf { ThemePalette.BLUE_GRAY }

// ============================================================================
// 3D 斜面
// ============================================================================

/**
 * 经典 Win 控件的 3D 斜面边框配色。
 *
 * ## 为什么必须自绘
 *
 * 经典 Win 控件（按钮 / 输入框 / 分组框）的辨识度**主要来自双色斜面**：同一控件的
 * 左上边用亮色、右下边用暗色，中间夹一条外圈色，靠这三道描边制造出「凸起（raised）」
 * 或「凹陷（sunken）」的立体错觉。Material3 的 `BorderStroke` 只有**单色**，
 * `Modifier.border` 也只接受一个颜色，**表达不了「成对异色边」**，所以这套描边只能自绘。
 *
 * 真实取值（Win2000 经典外观的四条系统色）：
 *
 * | 角色 | 色名 | 取值 |
 * |---|---|---|
 * | 外圈 | ButtonShadow | `#808080` |
 * | 内圈亮 | ButtonHighlight | `#FFFFFF` |
 * | 内圈暗 | ButtonDkShadow | `#000000` |
 * | 控件面 | ButtonFace | `#C0C0C0` |
 *
 * 斜面是**装饰**而非文字，因此不受 AA 4.5:1 约束（与 `outline` / `outlineVariant` 同理，
 * 见 `SundaysPaletteTest` 的说明）。但亮暗差过小会让立体感消失，过大则像描边故障。
 *
 * @property enabled 是否绘制。现代档为 `false`，此时 [uiBevel] 是彻底的空操作。
 * @property outer 外圈颜色
 * @property light 凸起态的内圈亮色（左上边）
 * @property dark 凸起态的内圈暗色（右下边）
 * @property width 单条描边粗细
 * @property doubleEdge `true` = Win2000 的 2px 双线（外圈同色 + 内圈亮暗成对）；
 *   `false` = WinXP Luna 的 1px 单线（只有明暗，不画独立外圈）
 * @property bevelRadius 斜面跟随的圆角，必须与控件自身 `shape` 对齐
 */
@Immutable
data class BevelStyle(
    val enabled: Boolean,
    val outer: Color,
    val light: Color,
    val dark: Color,
    val width: Dp,
    val doubleEdge: Boolean = true,
    val bevelRadius: Dp = 0.dp,
) {
    companion object {
        /** 不画斜面 —— 现代三套配色用它。 */
        val NONE: BevelStyle = BevelStyle(
            enabled = false,
            outer = Color.Transparent,
            light = Color.Transparent,
            dark = Color.Transparent,
            width = 0.dp,
        )
    }
}

/**
 * 给控件加经典 Win 的 3D 斜面边框。
 *
 * 画在**内容之后**（`drawWithContent` 而非 `drawBehind`）：M3 控件会先用容器色填满自身
 * 背景，斜面画在之前会被完全盖住。斜面只有 1~2px 且贴着边缘，不会遮挡文字。
 *
 * @param raised `true` = 按钮 / 分组框那种凸起（亮左上、暗右下）；`false` = 输入框那种
 *   凹陷（两者互换）。经典 Win 的切换按钮选中时也是凹陷。
 * @param cornerRadius 圆角；`null`（默认）时取 [BevelStyle.bevelRadius]
 *
 * 现代档下本 Modifier **完全透明**：直接返回 `this`，不产生任何绘制或图层开销。
 */
@Composable
fun Modifier.uiBevel(
    raised: Boolean = true,
    cornerRadius: Dp? = null,
): Modifier {
    val style = LocalBevelStyle.current
    if (!style.enabled) return this
    val light = if (raised) style.light else style.dark
    val dark = if (raised) style.dark else style.light
    val radius = cornerRadius ?: style.bevelRadius
    return this.drawWithContent {
        drawContent()
        val stroke = style.width.toPx()
        val radiusPx = radius.toPx()
        if (style.doubleEdge) {
            // Win2000：外圈四边同色，内圈左上亮 / 右下暗，合成经典 2px 双线边框
            drawBevelRing(0f, radiusPx, style.outer, style.outer, stroke)
            drawBevelRing(stroke, (radiusPx - stroke).coerceAtLeast(0f), light, dark, stroke)
        } else {
            // WinXP Luna：单圈 1px，只有明暗之分
            drawBevelRing(0f, radiusPx, light, dark, stroke)
        }
    }
}

/** [uiBevel] 的别名，保留旧名以便对照阅读。 */
@Composable
fun Modifier.winBevel(raised: Boolean = true, cornerRadius: Dp? = null): Modifier =
    this.uiBevel(raised, cornerRadius)

/** 画一圈「左上用 [light]、右下用 [dark]」的边线。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBevelRing(
    inset: Float,
    radius: Float,
    light: Color,
    dark: Color,
    stroke: Float,
) {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    if (right - left <= 0f || bottom - top <= 0f) return

    fun edge(sx: Float, sy: Float, ex: Float, ey: Float, color: Color) {
        if (sx != ex || sy != ey) {
            drawLine(color, Offset(sx, sy), Offset(ex, ey), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Butt)
        }
    }
    edge(left + radius, top, right - radius, top, light)
    edge(left, top + radius, left, bottom - radius, light)
    edge(left + radius, bottom, right - radius, bottom, dark)
    edge(right, top + radius, right, bottom - radius, dark)
}

// ============================================================================
// token 派生 —— 调用点只需要这些
//
// 每个函数都把「token × ColorScheme」解析成具体取值。主题判断（`when`）只出现在
// 这里，组件侧永远是「读 token、拿结果」，不含任何主题名。
// ============================================================================

/** 圆角的纯逻辑核心，可在普通单元测试里验证。 */
fun shapeFor(tokens: UiThemeTokens, corner: Dp): Shape =
    if (tokens.isClassic) RoundedCornerShape(tokens.bevel.bevelRadius) else RoundedCornerShape(corner)

/**
 * 界面圆角的单一入口。
 *
 * 界面里到处是**硬编码**的 `RoundedCornerShape(8.dp)`：它们直接构造 `Shape`，完全绕过
 * `MaterialTheme.shapes`，因此主题换形状时纹丝不动。对现代档无害，但会让经典档出现
 * 「按钮是直角、卡片却还是 8dp 圆角」——同屏两种圆角，复古感当场破功。
 *
 * 现代档**原样返回** [corner]（行为不变）；经典档按 token 抹平。
 */
@Composable
@ReadOnlyComposable
fun winShape(corner: Dp): Shape = shapeFor(LocalUiTokens.current, corner)

/** 按钮 / 输入框共用的圆角档位。 */
@Composable
@ReadOnlyComposable
fun controlShape(): Shape = shapeFor(LocalUiTokens.current, 4.dp)

/** 选中态的纯逻辑核心。 */
fun selectionColorsFor(
    tokens: UiThemeTokens,
    scheme: ColorScheme,
    selected: Boolean,
    unselected: Color = Color.Unspecified,
): Pair<Color, Color> {
    val base = if (unselected == Color.Unspecified) scheme.surface else unselected
    if (!selected) return base to scheme.onSurface
    return if (tokens.selection == SelectionMode.INVERTED) {
        scheme.primary to scheme.onPrimary
    } else {
        scheme.primaryContainer to scheme.onPrimaryContainer
    }
}

/** 选中态底色。 [unselected] 是各调用点原本的未选中底色，不传则取 `surface`。 */
@Composable
@ReadOnlyComposable
fun selectionContainerColor(selected: Boolean, unselected: Color = Color.Unspecified): Color =
    selectionColorsFor(LocalUiTokens.current, MaterialTheme.colorScheme, selected, unselected).first

/** 选中态文字色。 [unselected] 是各调用点原本的未选中文字色，不传则取 `onSurface`。 */
@Composable
@ReadOnlyComposable
fun selectionContentColor(selected: Boolean, unselected: Color = Color.Unspecified): Color {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    if (!selected) return if (unselected == Color.Unspecified) scheme.onSurface else unselected
    return selectionColorsFor(tokens, scheme, selected = true).second
}

/** 实心按钮的容器色。 */
@Composable
@ReadOnlyComposable
fun buttonFaceColor(): Color {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    return if (tokens.buttonFace == ButtonFace.WINDOW) scheme.background else scheme.primary
}

/** 实心按钮的文字色。 */
@Composable
@ReadOnlyComposable
fun buttonInkColor(): Color {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    return if (tokens.buttonFace == ButtonFace.WINDOW) scheme.onBackground else scheme.onPrimary
}

/** 输入框的容器色。 */
@Composable
@ReadOnlyComposable
fun fieldFaceColor(): Color {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    return if (tokens.fieldFace == FieldFace.SURFACE_VARIANT) scheme.surfaceVariant else scheme.surface
}

/** 容器描边：经典档给 3D 斜面并关掉单色描边，现代档给 1dp 细线。 */
@Composable
@ReadOnlyComposable
fun panelBorderStroke(): BorderStroke? {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    return when (tokens.panelBorder) {
        PanelBorder.BEVEL -> null
        PanelBorder.LINE -> BorderStroke(1.dp, scheme.outlineVariant)
        PanelBorder.NONE -> null
    }
}

/** 表格是否用隔行底色。 */
@Composable
@ReadOnlyComposable
fun zebraRowsEnabled(): Boolean = LocalUiTokens.current.zebraRows

/** 禁用态文字色；token 未指定时（现代档）返回 `null` 交回 M3 自己算。 */
@Composable
@ReadOnlyComposable
fun disabledInkOrNull(): Color? =
    LocalUiTokens.current.disabledInk.takeIf { it != Color.Unspecified }

/** 标签页条的底色：经典档贴窗口面（银灰 / 奶油），现代档贴 `surface`。 */
@Composable
@ReadOnlyComposable
fun tabStripContainerColor(): Color {
    val tokens = LocalUiTokens.current
    val scheme = MaterialTheme.colorScheme
    return if (tokens.isClassic) scheme.background else scheme.surface
}

/**
 * 分组线 —— 经典档是「暗 1px + 亮 1px」成对的蚀刻凹槽，现代档是单色实线。
 *
 * 实心单色线是现代扁平风的标志，在经典主题里会显得格格不入。
 *
 * @param color 指定暗线色；[Color.Unspecified] 则取主题的 `outline`。亮线恒取 `surface`
 *   —— 经典档无法从 `ColorScheme` 拿到恰好合适的高光色，用面色的反色最稳。
 */
@Composable
fun WinDivider(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val tokens = LocalUiTokens.current
    if (tokens.divider == DividerMode.FLAT) {
        // 未指定颜色时完全沿用 M3 默认（该版本没有 HorizontalDividerDefaults，
        // 冒然写死一个值反而会改变现代主题的观感）
        if (color == Color.Unspecified) {
            androidx.compose.material3.HorizontalDivider(modifier = modifier)
        } else {
            androidx.compose.material3.HorizontalDivider(modifier = modifier, color = color)
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dark = if (color == Color.Unspecified) scheme.outline else color
    val light = scheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(dark, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), strokeWidth = stroke)
                drawLine(light, Offset(0f, stroke * 1.5f), Offset(size.width, stroke * 1.5f), strokeWidth = stroke)
            },
    )
}

/** 当前主题的语法高亮配色。现代档由 `CodeEditorTheme.themed()` 走自有配色，此处仅供需要直接取值的调用点。 */
@Composable
@ReadOnlyComposable
fun syntaxColors(): SyntaxColors = LocalUiTokens.current.syntax

/** 编辑器底色：经典档取输入框面（否则纯白面上斜面亮边会隐形），现代档取 `surface`。 */
@Composable
@ReadOnlyComposable
fun editorFaceColor(): Color = fieldFaceColor()

