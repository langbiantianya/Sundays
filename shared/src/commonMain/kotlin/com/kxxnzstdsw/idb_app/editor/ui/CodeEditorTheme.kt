package com.kxxnzstdsw.idb_app.editor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kxxnzstdsw.idb_app.editor.SyntaxHighlighter
import com.kxxnzstdsw.idb_app.editor.TokenType

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

        /** 默认深色主题 — Intellij Darcula 风格。 */
        val Dark: CodeEditorTheme = CodeEditorTheme(
            colors = SyntaxHighlighter.DefaultDarkColors,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFFA9B7C6),
            ),
            backgroundColor = Color(0xFF2B2B2B),
            gutterColor = Color(0xFF313335),
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
