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
         * 必须在 `@Composable` 上下文调用（依赖 [isSystemInDarkTheme]）。
         */
        @Composable
        @ReadOnlyComposable
        fun default(): CodeEditorTheme =
            if (isSystemInDarkTheme()) Dark else Light
    }
}
