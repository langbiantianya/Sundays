package com.kxxnzstdsw.sundays.editor.formatter

import com.kxxnzstdsw.sundays.editor.CodeLanguageRegistry
import com.kxxnzstdsw.sundays.editor.TokenType
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile

/**
 * SQL 代码格式化器 — **轻量级规范化**：
 *
 * ## 行为
 * 1. 关键字大写（`select` → `SELECT`，`from` → `FROM` ...）
 * 2. 主子句换行（`SELECT` / `FROM` / `WHERE` / ... 起始 — 前置换行）
 * 3. **保留输入的行首缩进** —— 怎么缩进进来就怎么缩进出去（见下「为什么保留缩进」）
 * 4. 保留字符串字面量、注释、数字、标识符**原样**（不修改大小写）
 * 5. 空白折叠：多个空格 / Tab → 单空格；**保留原有的单个空行**作为分段
 * 6. 逗号 / 分号**前不留空格、后留一个**；比较与算术操作符**两侧对称各一个**；
 *    函数调用括号 `count(` **不留前置空格**（`in (1, 2)` 则保留）
 * 7. 注释内每一行的行尾空白清掉
 *
 * ## 为什么保留缩进而不是重新缩进
 *
 * 重新缩进需要真正的语法分析（判断 `(` 嵌套层级、`CASE` 块等），半吊子的重排比不改更糟 ——
 * 用户的视觉分组会被改乱却看不出为什么。所以这里**原样搬运**：输入 `select\n  a,\n  b`
 * 就输出 `SELECT\n  a,\n  b`。旧实现声称「保持输入的缩进」却实际上把续行缩进**全丢了**
 * （`  a` → `a`），多行 SELECT 的列对齐被抹平 —— 那是文档与实现不符，不是设计。
 *
 * ## 空格怎么决定
 *
 * 规则只有一条，且**与输入里原本有没有空白无关**：
 *
 * ```
 * 两个相邻 token 之间要空格  ⟺  左 token 不贴右  且  右 token 不贴左
 * ```
 *
 * 判定由 [TokenSpacing] 的两个纯函数完成（`bindsLeft` / `bindsRight`）。
 * 旧实现维护一个 `pendingSpace` 标志，在写每个 token **之前**决定补不补空格，
 * 踩了三个必然的坑：逗号前多空格（`id , name`）、比较符左粘右不粘（`id> 1`），
 * 以及 `a:: int` / `t. col` / `( 1 )` 这类「token 说好了贴紧，空白 token 又把标志翻回去」的
 * 反复横跳 —— 详见 [TokenSpacing] 的类 KDoc。
 *
 * **幂等性**：格式化两次的输出与格式化一次相同 —— 满足「重复格式化无副作用」。
 */
class SqlFormatter(override val languageId: String = SqlDialectProfile.STANDARD.languageId) : CodeFormatter {

    override fun format(source: String): String {
        if (source.isBlank()) return source
        val language = CodeLanguageRegistry.get(languageId) ?: return source
        val tokens = language.tokenize(source)
        if (tokens.isEmpty()) return source

        val sb = StringBuilder(source.length)
        // 本行尚未输出有效内容（行首 / 只有缩进）—— 此刻任何前导空格都不该补
        var atLineStart = true
        // 上一个**非空白** token，即空格判定的左邻居。
        // 跨行时**不清空**：换行本身已由 atLineStart 兜底，留着反而能正确处理
        // `SELECT\n  a` 这类续行里紧跟的操作符（`a\n  + b` 判成 `a + b` 才对）。
        var prevText: String? = null
        var prevType: TokenType? = null

        fun emit(type: TokenType, text: String) {
            val left = prevText
            if (!atLineStart && left != null) {
                val glued = TokenSpacing.bindsRight(left, prevType!!) ||
                    TokenSpacing.bindsLeft(text, type, left, prevType, NO_EXTRA_TIGHT)
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
                        // 至多保留一个空行（2 个 \n）作为分段，多的压掉 —— 否则一段段
                        // 注释之间的空行会被逐次累积，格式几次之后满屏都是空行。
                        repeat(minOf(newlines, 2)) { sb.append('\n') }
                        // 续行缩进**原样搬运**：最后一个 \n 之后的空白就是本行缩进（见类 KDoc）
                        sb.append(token.text.substringAfterLast('\n'))
                        atLineStart = true
                    }
                    // 同行内的空白**不携带任何信息** —— 输出至多一个空格，且贴不贴由 token
                    // 身份决定（见 [TokenSpacing]）。旧实现在这里把 pendingSpace 置回 true，
                    // 于是 `a:: int`、`t. col`、`( 1 )` 全被撑开一个空格。
                }

                TokenType.COMMENT ->
                    // 注释内容原样搬，只清行尾空白（[stripTrailingSpacePerLine]）
                    emit(TokenType.COMMENT, stripTrailingSpacePerLine(token.text))

                TokenType.STRING, TokenType.ERROR -> emit(token.type, token.text)

                TokenType.KEYWORD -> {
                    val upper = token.text.uppercase()
                    if (upper in CLAUSE_KEYWORDS && !atLineStart && sb.isNotEmpty()) {
                        // 主子句另起一行
                        sb.append('\n')
                        atLineStart = true
                    }
                    emit(TokenType.KEYWORD, upper)
                }

                TokenType.IDENTIFIER, TokenType.BUILTIN, TokenType.TYPE, TokenType.NUMBER,
                TokenType.OPERATOR, TokenType.PUNCTUATION,
                -> emit(token.type, token.text)
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 清掉注释 token 的行尾空白。
     *
     * **两处都要清**：块注释内部每一行，以及整个 token 的结尾。
     * 旧实现只在 `text.contains('\n')` 时才动手，于是**行注释**的尾随空白整个漏网 ——
     * 而行注释（`-- ...`）恰恰是最常带尾随空白的那一类（写完随手敲空格），
     * 结果 `select 1 -- 备注   ` 格式化后行尾仍有三个空格，版本 diff 里全是噪音。
     *
     * 安全性：注释 token 一律止于行尾或块注释的闭合标记，内部空白不影响语义，删掉即可。
     */
    private fun stripTrailingSpacePerLine(text: String): String =
        text.trimEnd().split('\n').joinToString("\n") { it.trimEnd() }

    companion object {
        /** 主子句关键字 — 强制另起一行。 */
        private val CLAUSE_KEYWORDS: Set<String> = setOf(
            "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
            "OFFSET", "FETCH", "UNION", "INTERSECT", "EXCEPT", "VALUES", "SET",
            "ON", "RETURNING", "WITH", "INSERT INTO", "DELETE FROM",
        )

        /** SQL 没有「语言私有贴标点」（`:` 在 SQL 里不是独立标点，Lua 才有 `a:b()`）。 */
        private val NO_EXTRA_TIGHT: Set<String> = emptySet()

        /**
         * SQL 家族注册入口 —— 应用启动时调用 `SqlFormatter.register()`。
         *
         * 逐个档位注册「语言 + formatter」：两者共用同一个 `languageId`，因此
         * `CodeFormatterRegistry.get(languageId)` 才能命中方言档位（否则 SQL 工作台切到方言档位后
         * 「格式化」按钮会静默消失）；formatter 内部按同一个 id 反查语言，**关键字大写也随方言**。
         */
        fun register() {
            com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile.ALL.forEach { profile ->
                CodeLanguageRegistry.register(
                    com.kxxnzstdsw.sundays.editor.language.SqlLanguage(profile),
                )
                CodeFormatterRegistry.register(SqlFormatter(profile.languageId))
            }
        }
    }
}
