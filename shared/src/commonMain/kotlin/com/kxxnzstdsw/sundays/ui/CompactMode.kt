package com.kxxnzstdsw.sundays.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 紧凑模式 —— 与 [ThemePalette]（色相）、[ThemeMode]（明暗）**正交的第三个轴：尺度**。
 *
 * ## 要解决的问题
 *
 * Material3 出厂尺寸是**触屏语言**：`Button` 最小高 40dp、`OutlinedTextField` 56dp、
 * `ListItem` 72dp、`Switch` 轨道 52dp、导航栏 80dp。这些数字在手指上刚好，在**鼠标**上是浪费 ——
 * 同样的窗口高度，DBeaver / DataGrip 能多列出三四行，而 M3 版式要空掉近三分之一。
 *
 * `SundaysPalette` 只能改**颜色 / 圆角 / 字号**，改不动这些尺寸：它们是 M3 组件内部写死的
 * token 常量，不是主题 token。逐个组件覆写一遍（改 `contentPadding`、传 `Modifier.height` …）
 * 意味着 30+ 个调用点都要改，且每加一个组件就得再改一次 —— 漏一个就会在界面里留下
 * 一块「没缩小的控件」，比全都不缩还难看。
 *
 * ## 解法：缩放 `LocalDensity.density`
 *
 * Compose 里所有 `dp`（含 M3 组件的最小高度、内边距、间距、圆角、图标尺寸）最终都经由
 * `Density.toPx()` 换算像素。因此只要把注入的 `LocalDensity.density` 乘一个系数，
 * **整棵树里所有以 dp 表达的尺寸等比缩小**，而各组件的代码一行都不用动。
 * 这也是 JetBrains 桌面端把「紧凑模式」做成单一缩放开关的做法。
 *
 * ## 字号为什么单独一档
 *
 * `Density` 里 `sp` 的像素值是 `value * density * fontScale` —— **顺带也被 density 缩放了**。
 * 若不管它，13sp 正文会掉到 11.1sp，在 100% DPI 的屏幕上开始费眼，而「控件变小」这件事
 * 并不需要以牺牲可读性为代价。故这里把 `fontScale` 反向补偿回去，让**文字与控件分开缩放**：
 *
 * | | 缩放 | 覆盖的东西 |
 * |---|---|---|
 * | [COMPACT_UI_SCALE] | 0.85 | 控件几何：最小高度 / 内边距 / 间距 / 圆角 / 图标 / 描边 |
 * | [COMPACT_TEXT_SCALE] | 0.92 | 字号与行高（`SundaysPalette.Typography` 的 sp） |
 *
 * 字号只降 8% 而控件降 15%：桌面上的可读性底线比「再多塞一行」重要，真嫌字大应当调
 * 系统的显示缩放，而不是让一个全局开关顺带把字也改小。
 *
 * ## 补偿后的恒等式
 *
 * ```
 * sp 像素 = value × (density × UI_SCALE) × (fontScale × TEXT_SCALE / UI_SCALE)
 *          = value × density × fontScale × TEXT_SCALE     ← 只剩预期的文本缩放
 * ```
 *
 * @see compactDensity 缩放计算（纯函数，可测）
 * @see LocalCompactMode 当前是否紧凑
 */

/** 紧凑档的 **dp** 缩放系数 —— 控件几何（最小高度 / 内边距 / 间距 / 圆角 / 图标 / 描边）。 */
internal const val COMPACT_UI_SCALE = 0.85f

/**
 * 紧凑档的 **sp** 缩放系数 —— 字号与行高。
 *
 * 刻意小于 [COMPACT_UI_SCALE]：控件缩 15% 是「同一屏多几行」，文字也缩 15% 则是
 * 「看得更费劲」，后者不是紧凑模式该给人的东西。
 */
internal const val COMPACT_TEXT_SCALE = 0.92f

/**
 * 当前是否处于紧凑档 —— 由 [SundaysTheme] 按 `compact` 参数注入。
 *
 * 绝大多数组件**不需要**读它：`LocalDensity` 覆盖已经把 dp 尺寸处理掉了。留给那些
 * 「不是尺寸、但也要跟着变」的判断，例如按屏幕宽度决定分几列时多切一列。
 */
val LocalCompactMode = staticCompositionLocalOf { false }

/** 当前是否处于紧凑档。 */
val isCompactMode: Boolean
    @Composable @ReadOnlyComposable get() = LocalCompactMode.current

/**
 * 把 [base] 缩放到紧凑档；[compact] 为 `false` 时**原样返回** [base]。
 *
 * 是纯函数而非 Composable：缩放数学（尤其是 [COMPACT_TEXT_SCALE] 的补偿除法）是本文件
 * 唯一的逻辑，必须能在不组合的情况下被断言 —— 补偿写错一个符号，肉眼在界面上分辨不出。
 *
 * ⚠️ 传入的必须是**平台真实** density。已经有别的覆盖层（如未来接入的
 * `LocalDensity provides` 包装）时，本函数拿到的应是链上最外层已生效的值。
 */
fun compactDensity(base: Density, compact: Boolean): Density = if (!compact) {
    base
} else {
    Density(
        density = base.density * COMPACT_UI_SCALE,
        // 抵消 density 缩放对 sp 的连带影响，再乘上文本档位自身的缩放 —— 见文件头恒等式
        fontScale = base.fontScale * COMPACT_TEXT_SCALE / COMPACT_UI_SCALE,
    )
}

/** [compactDensity] 的组合版：取当前 [LocalDensity] 为基准，注入缩放后的 [Density]。 */
@Composable
fun compactDensity(compact: Boolean): Density = compactDensity(LocalDensity.current, compact)
