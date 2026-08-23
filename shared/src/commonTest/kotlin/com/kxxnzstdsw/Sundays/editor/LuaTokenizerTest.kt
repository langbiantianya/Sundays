package com.kxxnzstdsw.Sundays.editor

import com.kxxnzstdsw.Sundays.editor.language.LuaLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lua 词法分析器测试。
 *
 * ## 覆盖范围
 * - 关键字（**大小写敏感** — `if` ≠ `If`）
 * - 内置函数 / 标准库
 * - 短字符串（'...'、"..."）含转义
 * - 长字符串（[[ ... ]] 与 [==[ ... ]==]）
 * - 单行注释（--）与块注释（--[[ ... ]] / --[==[ ... ]==]）
 * - 数字（十进制 / 十六进制 / 浮点 / 科学计数法 / 整数后缀）
 * - 标识符（含下划线开头）
 * - 操作符（Lua 特有 `..`、`//`、`#`、`::`）
 *
 * ## 测试原则
 * 每个测试只验证一个语法点，与 SQL 测试风格保持一致。
 */
class LuaTokenizerTest {

    private val lang = LuaLanguage()

    @Test
    fun empty_string_returns_empty_tokens() {
        assertEquals(emptyList(), lang.tokenize(""))
    }

    @Test
    fun keywords_recognized_case_sensitive() {
        val tokens = lang.tokenize("if then else end while")
        val keywords = tokens.filter { it.type == TokenType.KEYWORD }
        assertEquals(5, keywords.size)
    }

    @Test
    fun uppercase_keywords_not_recognized_as_keyword() {
        val tokens = lang.tokenize("IF THEN ELSE")
        val idents = tokens.filter { it.type == TokenType.IDENTIFIER }
        assertEquals(3, idents.size)
    }

    @Test
    fun nil_true_false_are_keywords() {
        val tokens = lang.tokenize("nil true false")
        val keywords = tokens.filter { it.type == TokenType.KEYWORD }
        assertEquals(3, keywords.size)
    }

    @Test
    fun builtins_recognized() {
        val tokens = lang.tokenize("print pairs ipairs tonumber tostring error pcall")
        val builtins = tokens.filter { it.type == TokenType.BUILTIN }
        assertEquals(7, builtins.size)
    }

    @Test
    fun identifiers_can_contain_underscore_and_digits() {
        val tokens = lang.tokenize("foo _bar var_1 a1b2c3")
        val idents = tokens.filter { it.type == TokenType.IDENTIFIER }
        assertEquals(4, idents.size)
    }

    @Test
    fun single_line_comment() {
        val tokens = lang.tokenize("local x = 1 -- comment\nprint(x)")
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("-- comment", comment.text)
    }

    @Test
    fun block_comment_simple() {
        val tokens = lang.tokenize("--[[ comment ]] local x = 1")
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("--[[ comment ]]", comment.text)
    }

    @Test
    fun block_comment_with_level() {
        val tokens = lang.tokenize("--[==[ multi line\n comment ]==] local x = 1")
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("--[==[ multi line\n comment ]==]", comment.text)
    }

    @Test
    fun block_comment_unterminated_is_error() {
        val tokens = lang.tokenize("--[[ unterminated")
        val error = tokens.first { it.type == TokenType.ERROR }
        assertTrue(error.text.startsWith("--[["))
    }

    @Test
    fun single_quoted_string_with_escape() {
        val tokens = lang.tokenize("'hello\\nworld'")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRING, tokens[0].type)
        assertEquals("'hello\\nworld'", tokens[0].text)
    }

    @Test
    fun double_quoted_string() {
        val tokens = lang.tokenize("\"hello\"")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRING, tokens[0].type)
    }

    @Test
    fun unterminated_string_is_error() {
        val tokens = lang.tokenize("'oops")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.ERROR, tokens[0].type)
    }

    @Test
    fun long_string_basic() {
        val tokens = lang.tokenize("[[multi\nline\nstring]]")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.STRING, tokens[0].type)
        assertEquals("[[multi\nline\nstring]]", tokens[0].text)
    }

    @Test
    fun long_string_with_level() {
        val tokens = lang.tokenize("[==[ contains [[ inside ]] ]==]")
        val s = tokens.first { it.type == TokenType.STRING }
        assertEquals("[==[ contains [[ inside ]] ]==]", s.text)
    }

    @Test
    fun long_string_unterminated_is_error() {
        val tokens = lang.tokenize("[[never closed")
        val e = tokens.first { it.type == TokenType.ERROR }
        assertTrue(e.text.startsWith("[["))
    }

    @Test
    fun integer_numbers() {
        val tokens = lang.tokenize("0 1 42 1000")
        tokens.filter { it.type == TokenType.NUMBER }.let { nums ->
            assertEquals(4, nums.size)
            nums.forEach { assertTrue(it.text.all(Char::isDigit)) }
        }
    }

    @Test
    fun hex_numbers() {
        val tokens = lang.tokenize("0xff 0X1A 0xABCDEF")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(3, nums.size)
        assertEquals("0xff", nums[0].text)
        assertEquals("0X1A", nums[1].text)
    }

    @Test
    fun float_numbers() {
        val tokens = lang.tokenize("3.14 .5 5. 1.0")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(4, nums.size)
    }

    @Test
    fun scientific_notation() {
        val tokens = lang.tokenize("1e10 2.5e-3 1E+5")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(3, nums.size)
        assertEquals("1e10", nums[0].text)
        assertEquals("2.5e-3", nums[1].text)
    }

    @Test
    fun integer_suffixes() {
        val tokens = lang.tokenize("1LL 1ULL 1L 1u")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(4, nums.size)
    }

    @Test
    fun string_concat_operator() {
        val tokens = lang.tokenize("a .. b")
        val op = tokens.first { it.type == TokenType.OPERATOR }
        assertEquals("..", op.text)
    }

    @Test
    fun length_operator() {
        val tokens = lang.tokenize("#t")
        val op = tokens.first { it.type == TokenType.OPERATOR }
        assertEquals("#", op.text)
    }

    @Test
    fun integer_division_operator() {
        val tokens = lang.tokenize("a // b")
        val op = tokens.first { it.type == TokenType.OPERATOR }
        assertEquals("//", op.text)
    }

    @Test
    fun label_operator() {
        val tokens = lang.tokenize("::name::")
        val ops = tokens.filter { it.type == TokenType.OPERATOR }
        // :: 开 + :: 闭 — 2 个 `::` 操作符
        assertEquals(2, ops.size)
        assertEquals("::", ops[0].text)
        assertEquals("::", ops[1].text)
    }

    @Test
    fun comparison_operators() {
        val tokens = lang.tokenize("a == b ~= c <= d >= e < f > g")
        val ops = tokens.filter { it.type == TokenType.OPERATOR }
        // == ~= <= >= < > 6 个（注意：`a = b` 中的 `=` 是单字符 OP，但这里没有单 `=`）
        assertEquals(6, ops.size)
    }

    @Test
    fun tokens_cover_entire_input_no_gaps() {
        val source = "local x = 1; print(x) -- comment\nreturn x"
        val tokens = lang.tokenize(source)
        assertEquals(0, tokens.first().start)
        assertEquals(source.length, tokens.last().end)
        for (i in 1 until tokens.size) {
            assertEquals(
                tokens[i - 1].end,
                tokens[i].start,
                "gap or overlap between token $i",
            )
        }
    }

    @Test
    fun token_start_end_match_text() {
        val source = "local function foo() return 42 end"
        val tokens = lang.tokenize(source)
        for (token in tokens) {
            assertEquals(source.substring(token.start, token.end), token.text)
        }
    }

    @Test
    fun realistic_lua_function() {
        val source = """
            local function greet(name)
              local msg = 'Hello, ' .. name
              print(msg)
              return msg
            end
        """.trimIndent()
        val tokens = lang.tokenize(source)
        // 关键字：local function local return end = 5
        assertEquals(5, tokens.count { it.type == TokenType.KEYWORD })
        // 字符串 1 个（'Hello, '）
        assertEquals(1, tokens.count { it.type == TokenType.STRING })
        // 内置函数 1 个（print）
        assertEquals(1, tokens.count { it.type == TokenType.BUILTIN })
    }

    @Test
    fun realistic_table_insert_script() {
        // 模拟项目 §5.7 造数引擎实际使用的 Lua 脚本
        val source = """
            for i = 1, 100 do
              insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65)})
            end
        """.trimIndent()
        val tokens = lang.tokenize(source)
        // 关键字：for do end = 3
        assertEquals(3, tokens.count { it.type == TokenType.KEYWORD })
        // 字符串：'users' 和 'user_' 两个字符串（'user_'..i 拼接，.. 是操作符）
        assertEquals(2, tokens.count { it.type == TokenType.STRING })
        // 数字：1, 100, 18, 65
        assertEquals(4, tokens.count { it.type == TokenType.NUMBER })
    }
}
