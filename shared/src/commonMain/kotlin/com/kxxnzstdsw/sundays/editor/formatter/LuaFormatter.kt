package com.kxxnzstdsw.sundays.editor.formatter

import com.kxxnzstdsw.sundays.editor.CodeLanguageRegistry
import com.kxxnzstdsw.sundays.editor.TokenType
/**
 * Lua 代码格式化器 — **轻量级规范化**（**安全优先** — 不会破坏代码）：
 *
 * ## 行为
 * 1. **保留大小写** —— Lua 是大小写敏感语言（`If` ≠ `if`），不强制 lowercase 关键字
 * 2. **关键字标准化展示** —— 在格式化时把关键字渲染成小写（**前提是用户开启了 case-normalize 选项**；
 *    默认关闭，因为 Lua 标识符大小写敏感）
 * 3. 折叠多余空白（多个空格 / Tab → 单空格）、去行尾空白
 * 4. 关键字 / 标识符 / 数字之间补单空格；操作符前后**不补**空格（除非是 `..` / `==` / `~=` 等双字符复合）
 * 5. 保留字符串字面量、注释原样
 *
 * ## 不做的事
 * - 不重新缩进（Lua 代码块缩进完全靠 `do`/`end`、`function`/`end`、`if`/`then`/`end` 等显式闭合，
 *   改动缩进可能引入语法错误）
 * - 不主动加注释 / 删注释
 * - 不修改字符串内的内容
 *
 * **幂等性**：格式化两次的输出与格式化一次相同。
 *
 * @param normalizeCase true 时把关键字转小写（**仅在确认代码无大小写敏感标识符时使用**）
 */
class LuaFormatter(
    private val normalizeCase: Boolean = false,
) : CodeFormatter {

    override val languageId: String = "lua"

    override fun format(source: String): String {
        if (source.isBlank()) return source
        val language = CodeLanguageRegistry.get(languageId) ?: return source
        val tokens = language.tokenize(source)
        if (tokens.isEmpty()) return source

        val sb = StringBuilder(source.length)
        // 本行尚未输出有效内容（行首 / 只有缩进）→ 任何前导空格都不该补
        var atLineStart = true
        // 上一个**非空白** token（空格判定的左邻居）—— 规则见 [TokenSpacing]
        var prevText: String? = null
        var prevType: TokenType? = null

        fun emit(type: TokenType, text: String) {
            val left = prevText
            if (!atLineStart && left != null) {
                val glued = TokenSpacing.bindsRight(left, prevType!!) ||
                    TokenSpacing.bindsLeft(text, type, left, prevType, EXTRA_TIGHT)
                if (!glued) sb.append(' ')
            }
            sb.append(text)
            prevText = text
            prevType = type
            atLineStart = false
        }

        for (token in tokens) {
            when (token.type) {
                TokenType.WHITESPACE -> {
                    val newlines = token.text.count { it == '\n' }
                    if (newlines > 0) {
                        // 至多保留一个空行作为分段，多的压掉
                        repeat(minOf(newlines, 2)) { sb.append('\n') }
                        // 续行缩进原样搬运：Lua 的缩进只做视觉分组，绝不改动它
                        sb.append(token.text.substringAfterLast('\n'))
                        atLineStart = true
                    }
                    // 同行内的空白不携带信息 —— 贴不贴只看 token 身份（见 [TokenSpacing]）
                }
                // 注释内容原样搬，只清行尾空白
                TokenType.COMMENT -> emit(TokenType.COMMENT, stripTrailingSpacePerLine(token.text))
                TokenType.STRING, TokenType.ERROR -> emit(token.type, token.text)
                TokenType.KEYWORD ->
                    emit(TokenType.KEYWORD, if (normalizeCase) token.text.lowercase() else token.text)
                TokenType.IDENTIFIER, TokenType.BUILTIN, TokenType.TYPE, TokenType.NUMBER,
                TokenType.OPERATOR, TokenType.PUNCTUATION,
                -> emit(token.type, token.text)
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 清掉注释 token 的行尾空白 —— 内部每一行 + 整个 token 结尾。
     *
     * 单行注释的尾随空白最容易漏（写完随手敲空格），旧实现在无换行时直接原样返回，
     * 格式化后行尾仍挂着空白，diff 全是噪音。
     */
    private fun stripTrailingSpacePerLine(text: String): String =
        text.trimEnd().split('\n').joinToString("\n") { it.trimEnd() }

    companion object {
        /** Lua 私有的贴标点：方法调用冒号 `obj:method()` 两向都贴。 */
        private val EXTRA_TIGHT: Set<String> = setOf(":")

        /** 默认注册入口。 */
        fun register() {
            CodeLanguageRegistry.register(com.kxxnzstdsw.sundays.editor.language.LuaLanguage())
            CodeFormatterRegistry.register(LuaFormatter())
        }
    }
}
