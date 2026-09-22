package com.kxxnzstdsw.sundays.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 把 tokens 映射为 Compose 可渲染的 [AnnotatedString]。
 *
 * **解耦设计**：
 * - 词法分析只负责"这是什么东西"（[CodeToken] / [TokenType]）
 * - 样式映射只负责"这种东西长什么样"（颜色 / 字体 / 字重）
 * - 这样可以独立演进：新增 [TokenType] 不影响样式，新增样式不影响 tokenizer
 *
 * **扩展指南**：重写 [styleFor] 提供自定义颜色映射；默认实现提供一个合理的浅色主题。
 * UI 层通常会包一层 `remember { Highlighter(theme.colors) }` 复用实例。
 *
 * @param colors 颜色映射表 — key 是 [TokenType]，value 是 Compose [Color]
 * @param boldKeywords 是否给关键字加粗（默认 true）
 */
class SyntaxHighlighter(
    private val colors: Map<TokenType, Color>,
    private val boldKeywords: Boolean = true,
) {

    /**
     * 把源码 + token 序列渲染为带样式的 [AnnotatedString]。
     *
     * **性能注意**：
     * - 使用 [AnnotatedString.Builder] 一次性拼接，避免 [buildAnnotatedString] 反复构建
     * - 单次扫描处理所有 token，相邻 token 同类型时合并 `withStyle` 调用次数
     */
    fun highlight(source: String, tokens: List<CodeToken>): AnnotatedString {
        val builder = AnnotatedString.Builder(source.length)
        for (token in tokens) {
            val style = styleFor(token.type)
            if (style != null) {
                builder.withStyle(style) {
                    append(token.text)
                }
            } else {
                builder.append(token.text)
            }
        }
        return builder.toAnnotatedString()
    }

    /**
     * 给定 [TokenType] 返回对应的 [SpanStyle]。
     * 返回 `null` 表示不应用样式（token 仍然 append 到 [AnnotatedString]，只是无颜色）。
     */
    fun styleFor(type: TokenType): SpanStyle? {
        val color = colors[type] ?: return null
        val isBold = boldKeywords && (type == TokenType.KEYWORD || type == TokenType.BUILTIN)
        return if (isBold) {
            SpanStyle(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        } else {
            SpanStyle(color = color)
        }
    }

    companion object {
        /** 默认浅色主题 — 用作 fallback，调用方通常会传入自定义主题。 */
        val DefaultLightColors: Map<TokenType, Color> = mapOf(
            TokenType.KEYWORD to Color(0xFF0000FF),       // blue
            TokenType.BUILTIN to Color(0xFFAF00DB),       // purple
            TokenType.TYPE to Color(0xFF2B91AF),          // teal
            TokenType.STRING to Color(0xFFA31515),        // dark red
            TokenType.NUMBER to Color(0xFF098658),        // dark green
            TokenType.COMMENT to Color(0xFF008000),      // green
            TokenType.OPERATOR to Color(0xFF000000),     // black
            TokenType.PUNCTUATION to Color(0xFF666666),  // gray
            TokenType.IDENTIFIER to Color(0xFF000000),    // black
            TokenType.ERROR to Color(0xFFFF0000),         // red
            // WHITESPACE 无样式
        )

        /** 默认深色主题 — Intellij Darcula 风格。 */
        val DefaultDarkColors: Map<TokenType, Color> = mapOf(
            TokenType.KEYWORD to Color(0xFFCC7832),       // orange
            TokenType.BUILTIN to Color(0xFF8888C6),       // light purple
            TokenType.TYPE to Color(0xFF4FC1FF),          // sky blue
            TokenType.STRING to Color(0xFF6A8759),        // light green
            TokenType.NUMBER to Color(0xFF6897BB),        // blue
            TokenType.COMMENT to Color(0xFF808080),       // gray
            TokenType.OPERATOR to Color(0xFFB9B9B9),      // light gray
            TokenType.PUNCTUATION to Color(0xFFB9B9B9),   // light gray
            TokenType.IDENTIFIER to Color(0xFFA9B7C6),    // light gray
            TokenType.ERROR to Color(0xFFFF6B68),         // red
        )
    }
}
