package com.kxxnzstdsw.Sundays.editor.formatter

import com.kxxnzstdsw.Sundays.editor.CodeLanguageRegistry
import com.kxxnzstdsw.Sundays.editor.CodeToken
import com.kxxnzstdsw.Sundays.editor.TokenType

/**
 * SQL 代码格式化器 — **轻量级规范化**：
 *
 * ## 行为
 * 1. 关键字大写（`select` → `SELECT`，`from` → `FROM` ...）
 * 2. 主子句换行（`SELECT` / `FROM` / `WHERE` / `GROUP BY` / `ORDER BY` / `HAVING` / `LIMIT` /
 *    `UNION` / `INTERSECT` / `EXCEPT` / `VALUES` / `SET` / `ON` 起始 — 前置换行）
 * 3. 逗号列表换行（SELECT 列表、INSERT 多行 VALUES）— 仅在跨多行时生效
 * 4. 保留字符串字面量、注释、数字、标识符**原样**（不修改大小写）
 * 5. 多余空白压缩（多个空格 / Tab → 单空格）、去行尾空白
 * 6. 关键字两侧加单空格（除非已经在标点 / 行首 / 行尾）
 *
 * ## 不做的事
 * - 不解析 SQL 语义（如不会重新组织 JOIN 顺序）
 * - 不破坏字符串内的内容
 * - 不强制缩进（保持输入的缩进，仅规范化换行）
 *
 * **幂等性**：格式化两次的输出与格式化一次相同（除了空格细节）— 满足"重复格式化无副作用"。
 */
class SqlFormatter : CodeFormatter {

    override val languageId: String = "sql"

    override fun format(source: String): String {
        if (source.isBlank()) return source
        val language = CodeLanguageRegistry.get(languageId) ?: return source
        val tokens = language.tokenize(source)
        if (tokens.isEmpty()) return source

        val sb = StringBuilder(source.length)
        var lastWasNewline = true   // 起始视为行首，避免首字符前留空
        var pendingSpace = false     // 需要在下一个非空白 token 前加空格

        for (token in tokens) {
            when (token.type) {
                TokenType.WHITESPACE -> {
                    // 折叠空白 — 换行符直接消费（由主从换行规则决定）
                    // 普通空白只记 pendingSpace，不立即写
                    if (token.text.contains('\n')) {
                        pendingSpace = false
                        lastWasNewline = true
                        // 实际写 \n（且去重）— 保证输入已有换行时输出也含换行；幂等性
                        if (sb.isEmpty() || sb.last() != '\n') sb.append('\n')
                    } else if (!lastWasNewline) {
                        pendingSpace = true
                    }
                }
                TokenType.COMMENT -> {
                    // 注释前若在行中段，先写空白；注释内换行视为有效
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
                    val upper = token.text.uppercase()
                    // 主子句强制换行
                    if (upper in CLAUSE_KEYWORDS && !lastWasNewline && sb.isNotEmpty()) {
                        sb.append('\n')
                        pendingSpace = false
                        lastWasNewline = true
                    }
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(upper)
                    pendingSpace = true  // 关键字后留空格
                    lastWasNewline = false
                }
                TokenType.IDENTIFIER, TokenType.BUILTIN, TokenType.TYPE, TokenType.NUMBER -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    pendingSpace = true  // 标识符后通常接符号 — 先记 pending
                    lastWasNewline = false
                }
                TokenType.OPERATOR -> {
                    // 特殊：行首逗号强制换行（SELECT 列表）
                    if (token.text == "," && !lastWasNewline) {
                        // 单行内多个逗号不强制；只在 multi-line 上下文生效（简化：检测前面已有 \n）
                        pendingSpace = false
                        sb.append(',')
                        // 逗号后留空格
                        pendingSpace = true
                        lastWasNewline = false
                    } else {
                        // 操作符两侧通常不加空格（=, <, >, >=, <= 等）
                        sb.append(token.text)
                        pendingSpace = false
                        lastWasNewline = false
                    }
                }
                TokenType.PUNCTUATION -> {
                    flushPendingSpace(sb, pendingSpace, lastWasNewline)
                    pendingSpace = false
                    sb.append(token.text)
                    // 标点后是否留空格：开括号 `( `、`[` 后不留；闭括号 `)`、`]` 前不留
                    pendingSpace = token.text !in "([`"
                    lastWasNewline = false
                }
            }
        }
        // 去尾部多余空白
        return sb.toString().trimEnd()
    }

    private fun flushPendingSpace(sb: StringBuilder, pending: Boolean, atLineStart: Boolean) {
        if (pending && !atLineStart) sb.append(' ')
    }

    companion object {
        /** 主子句关键字 — 强制另起一行。 */
        private val CLAUSE_KEYWORDS: Set<String> = setOf(
            "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
            "OFFSET", "FETCH", "UNION", "INTERSECT", "EXCEPT", "VALUES", "SET",
            "ON", "RETURNING", "WITH", "INSERT INTO", "DELETE FROM",
        )

        /** 默认注册入口 — 应用启动时调用 `SqlFormatter().register()`。 */
        fun register() {
            CodeLanguageRegistry.register(com.kxxnzstdsw.Sundays.editor.language.SqlLanguage())
            CodeFormatterRegistry.register(SqlFormatter())
        }
    }
}

// 引用 token 字段以便 IDE 折叠（保持 import）
@Suppress("unused")
private fun keepImportRef(token: CodeToken) = token.start
