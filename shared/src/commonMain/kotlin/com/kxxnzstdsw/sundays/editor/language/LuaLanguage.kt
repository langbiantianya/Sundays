package com.kxxnzstdsw.sundays.editor.language

import com.kxxnzstdsw.sundays.editor.CodeLanguage
import com.kxxnzstdsw.sundays.editor.CodeToken
import com.kxxnzstdsw.sundays.editor.TokenType

/**
 * Lua 语言 — 兼容 Lua 5.1 / 5.2 / 5.3 / 5.4 / 5.5 / LuaJIT（共享核心语法）。
 *
 * ## 覆盖特性
 * - 关键字 / 内置函数识别（**大小写敏感** — `local` 是关键字，`LOCAL` 是标识符）
 * - 长字符串 `[[ ... ]]` 与长注释 `--[[ ... ]]`（支持 `[==[ ... ]==]` 指定层级 — 用于嵌套）
 * - 短字符串 `'...'` / `"..."`（支持反斜杠转义）
 * - 数字：十进制、十六进制（`0x`）、浮点（`.5` / `5.` / `5.0`）、科学计数法（`1e10`）、Lua 5.3+ 整数后缀
 * - 操作符：标准 + Lua 特有 `..`（字符串连接）、`#`（长度）、`::`（标签）、`//`（Lua 5.3+ 整数除法）
 *
 * ## 已知限制
 * - 不解析函数调用语法（`foo.bar:baz(1, 2)` 整段按 IDENTIFIER + PUNCTUATION 流过去）
 * - 不识别 LuaJIT 特有扩展（如 `bit.*` 操作）— 按普通标识符处理
 * - 长字符串 / 块注释层级解析只支持 `[=*]` 等长形式（不支持跨层级错误嵌套）
 */
class LuaLanguage : CodeLanguage {

    override val id: String = "lua"
    override val displayName: String = "Lua"

    override fun tokenize(source: String): List<CodeToken> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<CodeToken>(source.length / 4 + 16)
        val len = source.length
        var i = 0
        while (i < len) {
            val c = source[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < len && source[i].isWhitespace()) i++
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.WHITESPACE))
                }
                // 单行注释 -- ...\n（也可能扩展为块注释 --[[ ... ]]）
                c == '-' && i + 1 < len && source[i + 1] == '-' -> {
                    // 检查是否是块注释 --[[ ... ]] 或 --[==[ ... ]==]
                    if (i + 3 < len && source[i + 2] == '[') {
                        // --[=*[
                        var j = i + 3
                        while (j < len && source[j] == '=') j++
                        if (j < len && source[j] == '[') {
                            val blockResult = consumeLongBracket(source, i, isComment = true)
                            out.add(blockResult)
                            i = blockResult.end
                            continue
                        }
                    }
                    val start = i
                    i += 2
                    while (i < len && source[i] != '\n') i++
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.COMMENT))
                }
                // 长字符串 [[ ... ]] 或 [==[ ... ]==]
                c == '[' && i + 1 < len && source[i + 1] in LONG_BRACKET_LEVELS_OR_FIRST_EQ -> {
                    // 必须是 `[` 跟 0..N 个 `=` 再跟 `[` — 才是长字符串开头
                    // 例如 `[[` 或 `[==[`，但不接受 `[=`
                    var j = i + 1
                    while (j < len && source[j] == '=') j++
                    if (j < len && source[j] == '[') {
                        val blockResult = consumeLongBracket(source, i, isComment = false)
                        out.add(blockResult)
                        i = blockResult.end
                    } else {
                        // 单个 [ 是普通操作符（table index）
                        out.add(CodeToken(i, i + 1, "[", TokenType.OPERATOR))
                        i++
                    }
                }
                // 短字符串 '...' 或 "..."
                c == '\'' || c == '"' -> {
                    val start = i
                    val quote = c
                    i++
                    var closed = false
                    while (i < len) {
                        when (source[i]) {
                            quote -> {
                                i++
                                closed = true
                                break
                            }
                            '\\' -> if (i + 1 < len) i += 2 else i++  // 转义序列（\\n \\t \\r 等）
                            '\n' -> break  // 字符串不允许跨行 — 未闭合
                            else -> i++
                        }
                    }
                    out.add(
                        CodeToken(
                            start,
                            i,
                            source.substring(start, i),
                            if (closed) TokenType.STRING else TokenType.ERROR,
                        ),
                    )
                }
                // 数字
                c.isDigit() || (c == '.' && i + 1 < len && source[i + 1].isDigit()) -> {
                    val start = i
                    // 十六进制：0x...
                    if (c == '0' && i + 1 < len && (source[i + 1] == 'x' || source[i + 1] == 'X')) {
                        i += 2
                        while (i < len && source[i] in HEX_DIGITS) i++
                    } else {
                        // 十进制 / 浮点
                        while (i < len && source[i].isDigit()) i++
                        if (i < len && source[i] == '.') {
                            i++
                            while (i < len && source[i].isDigit()) i++
                        }
                        if (i < len && (source[i] == 'e' || source[i] == 'E')) {
                            i++
                            if (i < len && (source[i] == '+' || source[i] == '-')) i++
                            while (i < len && source[i].isDigit()) i++
                        }
                    }
                    // Lua 5.3+ 整数后缀（LL / ULL / ULLL）
                    if (i < len && (source[i] == 'U' || source[i] == 'L' || source[i] == 'u' || source[i] == 'l')) {
                        while (i < len && source[i] in "LlUu") i++
                    }
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.NUMBER))
                }
                // 标识符 / 关键字
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < len && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val word = source.substring(start, i)
                    val type = when {
                        // 大小写敏感
                        word in KEYWORDS -> TokenType.KEYWORD
                        word in BUILTINS -> TokenType.BUILTIN
                        // Lua 字面量 nil / true / false 算关键字
                        else -> TokenType.IDENTIFIER
                    }
                    out.add(CodeToken(start, i, word, type))
                }
                // 操作符（双字符优先）
                else -> {
                    val two = if (i + 1 < len) source.substring(i, i + 2) else ""
                    when {
                        // ::label:: 标签 — `::` 开头或结尾视为整体操作符
                        two == "::" -> {
                            out.add(CodeToken(i, i + 2, "::", TokenType.OPERATOR))
                            i += 2
                        }
                        two in MULTI_CHAR_OPS -> {
                            out.add(CodeToken(i, i + 2, two, TokenType.OPERATOR))
                            i += 2
                        }
                        c in SINGLE_CHAR_OPS -> {
                            out.add(CodeToken(i, i + 1, c.toString(), TokenType.OPERATOR))
                            i++
                        }
                        else -> {
                            out.add(CodeToken(i, i + 1, c.toString(), TokenType.PUNCTUATION))
                            i++
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * 消费 `[[ ... ]]` / `[==[ ... ]==]` / `--[[ ... ]]` / `--[==[ ... ]==]` 整段。
     *
     * @param isComment true 表示 `--[...]` 块注释；false 表示 `[[...]]` 长字符串
     * @return 单个 CodeToken，type 由是否闭合决定（COMMENT/STRING vs ERROR）
     */
    private fun consumeLongBracket(source: String, start: Int, isComment: Boolean): CodeToken {
        val len = source.length
        val prefix = if (isComment) "--" else ""
        var i = start + prefix.length
        // 计算层级 = 第二个字符之后的连续 = 数量
        if (i >= len || source[i] != '[') {
            // 防御：理论上调用方已保证，但 fallback 到普通注释 / 字符串
            val end = (if (isComment) consumeLineComment(source, start) else start + 2).coerceAtMost(len)
            return CodeToken(start, end, source.substring(start, end), if (isComment) TokenType.COMMENT else TokenType.STRING)
        }
        i++  // skip '['
        var level = 0
        while (i < len && source[i] == '=') {
            level++
            i++
        }
        if (i >= len || source[i] != '[') {
            // 不是合法的 [ 开头（实际上不应到达这里）
            val end = (start + prefix.length + 1).coerceAtMost(len)
            return CodeToken(start, end, source.substring(start, end), if (isComment) TokenType.COMMENT else TokenType.STRING)
        }
        i++  // skip last '['
        // 找闭合 ]=*]
        while (i < len) {
            if (source[i] == ']') {
                var j = i + 1
                var closeLevel = 0
                while (j < len && closeLevel < level && source[j] == '=') {
                    closeLevel++
                    j++
                }
                if (closeLevel == level && j < len && source[j] == ']') {
                    val end = j + 1
                    return CodeToken(
                        start,
                        end,
                        source.substring(start, end),
                        if (isComment) TokenType.COMMENT else TokenType.STRING,
                    )
                }
                i++
            } else {
                i++
            }
        }
        // 未闭合
        return CodeToken(start, len, source.substring(start, len), TokenType.ERROR)
    }

    private fun consumeLineComment(source: String, start: Int): Int {
        var i = start + 2
        val len = source.length
        while (i < len && source[i] != '\n') i++
        return i
    }

    companion object {

        /** Lua 关键字 — 大小写敏感。 */
        private val KEYWORDS: Set<String> = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for",
            "function", "goto", "if", "in", "local", "nil", "not", "or",
            "repeat", "return", "then", "true", "until", "while",
        )

        /** Lua 内置函数 / 标准库。 */
        private val BUILTINS: Set<String> = setOf(
            // 基础
            "print", "tostring", "tonumber", "type", "error", "assert",
            "pcall", "xpcall", "select",
            // table
            "table", "pairs", "ipairs", "next", "unpack", "rawget", "rawset",
            "rawequal", "rawlen", "setmetatable", "getmetatable",
            // string
            "string", "string.format", "string.sub", "string.find", "string.gmatch",
            "string.gsub", "string.match", "string.char", "string.byte", "string.len",
            "string.lower", "string.upper", "string.rep", "string.reverse",
            // math
            "math", "math.abs", "math.floor", "math.ceil", "math.sqrt",
            "math.pow", "math.max", "math.min", "math.random", "math.randomseed",
            "math.sin", "math.cos", "math.tan", "math.log", "math.exp",
            "math.huge", "math.pi",
            // io
            "io", "io.write", "io.read", "io.open", "io.close", "io.flush",
            // os
            "os", "os.time", "os.date", "os.clock", "os.exit",
            // debug
            "debug", "debug.traceback", "debug.getinfo", "debug.getlocal",
            "debug.setlocal", "debug.getupvalue", "debug.setupvalue",
            // Lua 5.2+
            "load", "loadstring", "loadfile", "dofile", "require",
            // 协程
            "coroutine", "coroutine.create", "coroutine.resume", "coroutine.yield",
            "coroutine.wrap", "coroutine.status",
        )

        private val MULTI_CHAR_OPS: Set<String> = setOf(
            "..", "..=", "<<", ">>", "<=", ">=", "==", "~=", "::", "//",
            "+=", "-=", "*=", "/=", "%=", "^=", "&=", "|=", ">>=", "<<=", "::",
        )

        private val SINGLE_CHAR_OPS: Set<Char> = setOf(
            '+', '-', '*', '/', '%', '^', '#',
            '=', '<', '>', '~', '&', '|',
            ':',
        )

        /** 长括号开头第二位允许的字符：= 或 [（即 `--[[` / `--[==[` 两种形式）。 */
        private val LONG_BRACKET_LEVELS_OR_FIRST_EQ: Set<Char> = setOf('[', '=')

        private val HEX_DIGITS: Set<Char> = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
            'A', 'B', 'C', 'D', 'E', 'F',
        )
    }
}
