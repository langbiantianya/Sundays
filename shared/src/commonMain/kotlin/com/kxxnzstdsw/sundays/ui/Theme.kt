package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * 应用主题 —— 跟随系统明暗，配色 / 形状由 [palette] 决定，字号为全局共享。
 *
 * 各平台入口（desktop `Window` / 未来的 Android / iOS）只负责创建平台容器，
 * 主题本身是纯 Compose 逻辑，放在 `commonMain` 供所有平台复用。
 *
 * **必须用 [Surface] 承接背景色**：Material3 的 `MaterialTheme` 只注入 colorScheme / shapes /
 * typography，**不注入** `LocalContentColor`（其默认值是 `Color.Black`）。未显式指定颜色的
 * `Text` 因此取黑色 —— 亮色主题下看不出问题，暗色主题下就是黑字贴黑底（连接列表标题、
 * 向导步骤标题、`labelLarge` 小标题等全部看不清）。由 [Surface] 统一提供
 * `background` + `onBackground` 后，所有未着色的文本在任何主题下都有正确对比度。
 *
 * ## 额外注入的 CompositionLocal
 *
 * - [LocalPalette] —— `Button` 的形状由 M3 token 固定、**不读** `MaterialTheme.shapes`，
 *   必须显式传参；[SundaysPalette.buttonShape] 靠它拿到当前主题的圆角。
 * - [LocalBevelStyle] —— Win2000 / WinXP 的 3D 斜面配色。[Modifier.winBevel] 读它决定
 *   画不画、画什么色；现代三套拿到 `BevelStyle.NONE`，于是斜面彻底是空操作。
 * - [LocalDarkMode] —— 生效的明暗档。[ColorScheme] 本身已随 `darkTheme` 变，但
 *   [com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme] / [DataTableTheme] 这类**不在
 *   MaterialTheme 体系内**的取值点需要在两套写死配色间二选一，只有显式发布才有真相可读。
 * - [LocalCompactMode] + [LocalDensity] —— 紧凑档。**与主题无关**（任何配色 / 明暗都适用），
 *   靠缩放 density 把整棵树的 dp 尺度等比缩小，于是 M3 出厂的触屏尺寸自动收到桌面尺度，
 *   组件侧一行都不用改 —— 详见 [CompactMode.kt]。
 *
 * @param darkTheme 是否暗色（默认跟随系统；显式传入便于测试与外观设置）
 * @param palette 配色主题（[ThemePalette]）—— 与 [darkTheme] 正交：同一主题各有浅 / 深两套配色
 * @param compact 紧凑模式 —— 与配色 / 明暗正交的**尺度**轴：控件几何整体缩小、字号轻微下调
 */
@Composable
fun SundaysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.BLUE_GRAY,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    // LocalDensity 必须在读它**之前**取值：下面 CompositionLocalProvider 的参数里
    // 写 `compactDensity(...)` 时，provider 自身尚未生效，LocalDensity.current 仍是平台值。
    val density = compactDensity(LocalDensity.current, compact)
    CompositionLocalProvider(
        LocalPalette provides palette,
        // 界面外观的唯一真相来源：组件只读这个 token，不含任何主题名判断
        LocalUiTokens provides palette.uiTokens(darkTheme),
        LocalBevelStyle provides palette.bevelStyle(darkTheme),
        LocalCompactMode provides compact,
        // 明暗档本身。必须显式发布：编辑器 / 表格的 `themed()` 需要**按应用档位**在两套写死
        // 配色间二选一，而它们不在 MaterialTheme 体系内，拿不到 `darkTheme` 参数 ——
        // 少了这一行它们只能去读系统设置，用户强制档位时就会与整个界面相反。
        LocalDarkMode provides darkTheme,
        // 紧凑档的全部实现就在这一次注入 —— 组件侧无需任何紧凑判断
        LocalDensity provides density,
    ) {
        MaterialTheme(
            colorScheme = palette.schemeFor(darkTheme),
            // 形状逐主题：现代三套共用紧凑圆角，Win2000 全直角，WinXP 用 Luna 圆角
            shapes = palette.shapes,
            // 字号**不**逐主题：有意的取舍，见 SundaysPalette 字号小节的说明
            typography = SundaysPalette.Typography,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}
