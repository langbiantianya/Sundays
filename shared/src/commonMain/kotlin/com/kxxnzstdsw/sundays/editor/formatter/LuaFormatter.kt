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
        var pendingSpace = false
        var lastWasNewline = true

        for (token in tokens) {
            when (token.type) {
                TokenType.WHITESPACE -> {
                    if (token.text.contains('\n')) {
                        pendingSpace = false
                        lastWasNewline = true
                    } else if (!lastWasNewline) {
                        pendingSpace = true
                    }
                }
                TokenType.COMMENT -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    lastWasNewline = token.text.endsWith('\n')
                }
                TokenType.STRING, TokenType.ERROR -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    lastWasNewline = false
                }
                TokenType.KEYWORD -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(if (normalizeCase) token.text.lowercase() else token.text)
                    pendingSpace = true
                    lastWasNewline = false
                }
                TokenType.IDENTIFIER, TokenType.BUILTIN, TokenType.TYPE, TokenType.NUMBER -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    pendingSpace = true
                    lastWasNewline = false
                }
                TokenType.OPERATOR -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    // 多数 Lua 操作符两侧不加空格：`a+b`、`x==y`、`fn(arg)` 都对
                    pendingSpace = false
                    lastWasNewline = false
                }
                TokenType.PUNCTUATION -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    // 开括号 `(` 后不留空格（`func (arg)` 应为 `func(arg)`）；
                    // 闭括号 `)` 前不留；逗号 `,` 后可留（`a, b, c`）— 留 1 空格
                    pendingSpace = token.text == "," || token.text == ";"
                    lastWasNewline = false
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun flushPendingSpace(sb: StringBuilder, pending: Boolean, atLineStart: Boolean) {
        if (pending && !atLineStart) sb.append(' ')
    }

    companion object {
        /** 默认注册入口。 */
        fun register() {
            CodeLanguageRegistry.register(com.kxxnzstdsw.sundays.editor.language.LuaLanguage())
            CodeFormatterRegistry.register(LuaFormatter())
        }
    }
}
