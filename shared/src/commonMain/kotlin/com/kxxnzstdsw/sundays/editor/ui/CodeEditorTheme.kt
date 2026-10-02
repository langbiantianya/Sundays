package com.kxxnzstdsw.sundays.editor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kxxnzstdsw.sundays.editor.SyntaxHighlighter
import com.kxxnzstdsw.sundays.editor.TokenType
import com.kxxnzstdsw.sundays.ui.asTokenColors

/**
 * 代码编辑器视觉主题 — 颜色 + 字体 + 字号。
 *
 * **可扩展性**：
 * - 提供 [Light] / [Dark] 两种内置主题
 * - [default] 根据系统主题自动选择（基于 `isSystemInDarkTheme()`）
 * - 调用方可自定义 [colors] / [textStyle] / [backgroundColor] 提供自有主题
 *
 * 注意：[SyntaxHighlighter] 接收 [colors] map；新增 token 类型只需在该 map 中追加键值对，
 * 无需修改 [CodeEditor] 组件本身。
 */
data class CodeEditorTheme(
    val colors: Map<TokenType, Color>,
    val textStyle: TextStyle,
    val backgroundColor: Color,
    val gutterColor: Color,
) {

    companion object {

        /** 默认浅色主题 — Intellij Light 风格。 */
        val Light: CodeEditorTheme = CodeEditorTheme(
            colors = SyntaxHighlighter.DefaultLightColors,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF000000),
            ),
            backgroundColor = Color(0xFFFAFAFA),
            gutterColor = Color(0xFFE8E8E8),
        )

        /**
         * 默认深色主题 — Intellij Darcula 语法配色，底色改用与 `SundaysPalette` 深色表面
         * 一致的蓝灰炭色（`#1E232D`），而非 Darcula 的中性灰 `#2B2B2B`。
         *
         * 原因：编辑器是嵌在应用界面里的一个**区域**，不是独立窗口。原来的中性灰与应用其余
         * 部分的蓝灰底色并排时会出现一块明显偏暖的「灰补丁」；换成同色系底色后工作台与左树、
         * 标签条融为整体。Darcula 的 10 个 token 配色全部保留（其中 STRING / COMMENT 对该底色
         * 的对比度为 3.9:1，低于 4.5 但仍清晰可读，与 Darcula 原始观感一致）。
         */
        val Dark: CodeEditorTheme = CodeEditorTheme(
            colors = SyntaxHighlighter.DefaultDarkColors,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFFA9B7C6),
            ),
            backgroundColor = Color(0xFF1E232D),
            gutterColor = Color(0xFF262C38),
        )

        /**
         * 默认主题 — 根据当前系统设置自动选择。
         * 必须在 `@Composable` 上下文调用。
         */
        @Composable
        @ReadOnlyComposable
        fun default(): CodeEditorTheme = themed()

        /**
         * 跟随应用配色与明暗档的编辑器主题 —— **[CodeEditor] 的实际默认值**。
         *
         * ## 为什么要从写死常量改成跟随配色
         *
         * 原先 `default()` 只按 [isSystemInDarkTheme] 在 [Light] / [Dark] 间二选一，由此
         * 有两个问题（与 `DataTableTheme` 同源）：
         *
         * 1. **无视用户选的明暗档**。`AppearanceState` 允许强制「始终浅色 / 始终深色」，
         *    而这里看的是系统设置 —— 用户强制浅色、系统是深色时，编辑器会与界面相反。
         * 2. **无视配色主题**。五套配色下编辑器都是同一套 VS / Darcula 配色：Win2000 主题里
         *    SQL 关键字仍是 VS 蓝，是复古感最刺眼的漏网之处。
         *
         * 经典档（Win2000 / WinXP）取 [com.kxxnzstdsw.sundays.ui.SundaysPalette] 里那两组
         * 逐明暗的语法色 —— Delphi / VS6 时代的系统色思路（navy 关键字、maroon 字符串、
         * teal 类型、深绿数字与注释），每个槽位都验过在编辑器底色上的对比度。
         * 现代档保留原有的 VS / Darcula 两套（它们本身就是为「嵌在别的工具里」调过的）。
         */
        @Composable
        @ReadOnlyComposable
        fun themed(): CodeEditorTheme {
            val tokens = com.kxxnzstdsw.sundays.ui.LocalUiTokens.current
            val scheme = androidx.compose.material3.MaterialTheme.colorScheme
            val classic = tokens.isClassic
            val (text, face) = if (classic) {
                // 经典档：底色取输入框面（纯白面上斜面亮边会隐形），文字取前景色
                scheme.onBackground to com.kxxnzstdsw.sundays.ui.fieldFaceColor()
            } else {
                val base = if (com.kxxnzstdsw.sundays.ui.isClassicChrome) Dark else Light
                return base.copy(
                    backgroundColor = scheme.surface,
                    gutterColor = scheme.surfaceVariant,
                )
            }
            return CodeEditorTheme(
                colors = tokens.syntax.asTokenColors(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = text,
                ),
                backgroundColor = face,
                gutterColor = if (classic) scheme.outlineVariant else scheme.surfaceVariant,
            )
        }
    }
}
