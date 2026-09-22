package com.kxxnzstdsw.sundays.editor

import com.kxxnzstdsw.sundays.editor.language.SqlLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL 词法分析器测试。
 *
 * ## 覆盖范围
 * - 关键字（大小写不敏感）
 * - 字符串字面量（'...' 与 '' 转义）
 * - 单行注释（--）与块注释（/* */）
 * - 数字（整数 / 浮点 / 科学计数法）
 * - 标识符 / 类型 / 内置函数分类
 * - MySQL 反引号与 PG 双引号标识符
 * - 未闭合字符串 / 块注释 → ERROR token
 *
 * 设计原则：每个测试只验证一个行为点，便于扩展时定位失败原因。
 */
class SqlTokenizerTest {

    private val lang = SqlLanguage()

    @Test
    fun empty_string_returns_empty_tokens() {
        assertEquals(emptyList(), lang.tokenize(""))
    }

    @Test
    fun whitespace_only_returns_whitespace_tokens() {
        val tokens = lang.tokenize("   \n\t  ")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.WHITESPACE, tokens[0].type)
    }

    @Test
    fun keywords_recognized_case_insensitive() {
        val tokens = lang.tokenize("select SELECT Select SeLeCt")
        val keywords = tokens.filter { it.type == TokenType.KEYWORD }
        assertEquals(4, keywords.size)
        assertEquals("select", keywords[0].text)
        assertEquals("SELECT", keywords[1].text)
        assertEquals("Select", keywords[2].text)
        assertEquals("SeLeCt", keywords[3].text)
    }

    @Test
    fun types_recognized() {
        val tokens = lang.tokenize("INT VARCHAR DECIMAL BOOLEAN")
        val types = tokens.filter { it.type == TokenType.TYPE }
        assertEquals(4, types.size)
    }

    @Test
    fun builtins_recognized() {
        val tokens = lang.tokenize("COUNT SUM AVG NOW")
        val builtins = tokens.filter { it.type == TokenType.BUILTIN }
        assertEquals(4, builtins.size)
    }

    @Test
    fun identifiers_classified_as_identifier() {
        val tokens = lang.tokenize("users order_id _private camelCase")
        val idents = tokens.filter { it.type == TokenType.IDENTIFIER }
        assertEquals(4, idents.size)
    }

    @Test
    fun string_with_single_quote_escape() {
        val tokens = lang.tokenize("'hello' 'it''s'")
        val strings = tokens.filter { it.type == TokenType.STRING }
        assertEquals(2, strings.size)
        assertEquals("'hello'", strings[0].text)
        assertEquals("'it''s'", strings[1].text)
    }

    @Test
    fun unterminated_string_marked_as_error() {
        val tokens = lang.tokenize("'hello world")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.ERROR, tokens[0].type)
    }

    @Test
    fun mysql_backtick_identifier() {
        val tokens = lang.tokenize("`column name`")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
        assertEquals("`column name`", tokens[0].text)
    }

    @Test
    fun postgresql_double_quote_identifier() {
        val tokens = lang.tokenize("\"col_name\"")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.IDENTIFIER, tokens[0].type)
    }

    @Test
    fun single_line_comment() {
        val tokens = lang.tokenize("SELECT 1 -- this is comment\nFROM t")
        // SELECT, WHITESPACE, 1, WHITESPACE, COMMENT, WHITESPACE, FROM, WHITESPACE, t
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("-- this is comment", comment.text)
    }

    @Test
    fun block_comment_basic() {
        val tokens = lang.tokenize("SELECT /* hint */ 1")
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("/* hint */", comment.text)
    }

    @Test
    fun block_comment_nested() {
        val tokens = lang.tokenize("/* outer /* inner */ still comment */")
        val comment = tokens.first { it.type == TokenType.COMMENT }
        assertEquals("/* outer /* inner */ still comment */", comment.text)
    }

    @Test
    fun block_comment_unterminated_marked_as_error() {
        val tokens = lang.tokenize("/* unterminated")
        val error = tokens.first { it.type == TokenType.ERROR }
        assertTrue(error.text.startsWith("/*"))
    }

    @Test
    fun integer_numbers() {
        val tokens = lang.tokenize("1 42 100 999999")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(4, nums.size)
    }

    @Test
    fun float_numbers() {
        val tokens = lang.tokenize("1.5 3.14 .5 10.")
        tokens.filter { it.type == TokenType.NUMBER }.let { nums ->
            assertTrue(nums.isNotEmpty())
            assertTrue(nums.any { it.text == "1.5" })
            assertTrue(nums.any { it.text == "3.14" })
        }
    }

    @Test
    fun scientific_notation_numbers() {
        val tokens = lang.tokenize("1e10 2.5E-3 1e+5")
        val nums = tokens.filter { it.type == TokenType.NUMBER }
        assertEquals(3, nums.size)
        assertEquals("1e10", nums[0].text)
        assertEquals("2.5E-3", nums[1].text)
        assertEquals("1e+5", nums[2].text)
    }

    @Test
    fun operators_single_char() {
        val tokens = lang.tokenize("a = b + c * d / e - f")
        val ops = tokens.filter { it.type == TokenType.OPERATOR }
        // 应有 =, +, *, /, - 五个操作符（标识符之间的空格被 WHITE space 处理）
        assertTrue(ops.size >= 5)
    }

    @Test
    fun operators_multi_char() {
        val tokens = lang.tokenize("a <= b <> c != d")
        val ops = tokens.filter { it.type == TokenType.OPERATOR }
        assertEquals(3, ops.size)
        assertEquals("<=", ops[0].text)
        assertEquals("<>", ops[1].text)
        assertEquals("!=", ops[2].text)
    }

    @Test
    fun punctuation_recognized() {
        val tokens = lang.tokenize("func(arg1, arg2)")
        val puncts = tokens.filter { it.type == TokenType.PUNCTUATION }
        assertTrue(puncts.any { it.text == "(" })
        assertTrue(puncts.any { it.text == ")" })
        assertTrue(puncts.any { it.text == "," })
    }

    @Test
    fun tokens_cover_entire_input_no_gaps() {
        val source = "SELECT * FROM users WHERE id = 1 -- comment"
        val tokens = lang.tokenize(source)
        // 第一个 token 必须从 0 开始；最后一个 token 必须以 source.length 结尾；相邻 token 无重叠
        assertEquals(0, tokens.first().start)
        assertEquals(source.length, tokens.last().end)
        for (i in 1 until tokens.size) {
            assertEquals(tokens[i - 1].end, tokens[i].start, "gap or overlap between tokens[${i - 1}] and tokens[$i]")
        }
    }

    @Test
    fun token_start_end_match_text() {
        val source = "SELECT 'hello' FROM x"
        val tokens = lang.tokenize(source)
        for (token in tokens) {
            assertEquals(source.substring(token.start, token.end), token.text)
        }
    }

    @Test
    fun realistic_select_query() {
        val source = """
            SELECT u.id, u.name
            FROM users u
            WHERE u.created_at > '2024-01-01'
              AND u.age >= 18
            ORDER BY u.name DESC
            LIMIT 100
        """.trimIndent()
        val tokens = lang.tokenize(source)
        // 关键字个数：SELECT / FROM / WHERE / AND / ORDER / BY / LIMIT = 7
        // （ORDER BY 是两个独立关键字 token）
        assertEquals(7, tokens.count { it.type == TokenType.KEYWORD })
        // 字符串 1 个（日期）
        assertEquals(1, tokens.count { it.type == TokenType.STRING })
        // 数字 2 个（18、100；'2024-01-01' 在字符串内不算数字）
        assertEquals(2, tokens.count { it.type == TokenType.NUMBER })
    }
}
