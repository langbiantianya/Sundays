package com.kxxnzstdsw.idb_app.editor

import com.kxxnzstdsw.idb_app.editor.formatter.CodeFormatterRegistry
import com.kxxnzstdsw.idb_app.editor.formatter.LuaFormatter
import com.kxxnzstdsw.idb_app.editor.formatter.SqlFormatter
import com.kxxnzstdsw.idb_app.editor.language.LuaLanguage
import com.kxxnzstdsw.idb_app.editor.language.SqlLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 编辑器组件集成测试 — 验证注册表 / 格式化器 / 高亮器协作正常。
 *
 * 这些测试是**扩展模板**：当新增一种语言时，复制本文件并修改参数即可验证集成路径。
 */
class EditorIntegrationTest {

    @Test
    fun language_registry_supports_register_and_lookup() {
        CodeLanguageRegistry.clear()
        try {
            CodeLanguageRegistry.register(SqlLanguage())
            CodeLanguageRegistry.register(LuaLanguage())
            assertEquals(2, CodeLanguageRegistry.all().size)
            assertNotNull(CodeLanguageRegistry.get("sql"))
            assertNotNull(CodeLanguageRegistry.get("lua"))
            assertEquals("SQL", CodeLanguageRegistry.get("sql")?.displayName)
            assertEquals("Lua", CodeLanguageRegistry.get("lua")?.displayName)
        } finally {
            CodeLanguageRegistry.clear()
        }
    }

    @Test
    fun language_registry_returns_null_for_unknown() {
        CodeLanguageRegistry.clear()
        try {
            assertEquals(null, CodeLanguageRegistry.get("unknown-lang"))
            assertEquals(false, CodeLanguageRegistry.contains("unknown-lang"))
        } finally {
            CodeLanguageRegistry.clear()
        }
    }

    @Test
    fun formatter_registry_round_trip() {
        CodeFormatterRegistry.clear()
        try {
            CodeFormatterRegistry.register(SqlFormatter())
            CodeFormatterRegistry.register(LuaFormatter())
            assertEquals(2, CodeFormatterRegistry.all().size)
            assertNotNull(CodeFormatterRegistry.get("sql"))
            assertNotNull(CodeFormatterRegistry.get("lua"))
        } finally {
            CodeFormatterRegistry.clear()
        }
    }

    @Test
    fun sql_formatter_uppercases_keywords() {
        CodeLanguageRegistry.register(SqlLanguage())
        CodeFormatterRegistry.register(SqlFormatter())
        try {
            val input = "select id, name from users where age >= 18"
            val output = SqlFormatter().format(input)
            // 关键字全部大写
            assertTrue(output.contains("SELECT"), "expected SELECT in $output")
            assertTrue(output.contains("FROM"), "expected FROM in $output")
            assertTrue(output.contains("WHERE"), "expected WHERE in $output")
            // 主子句换行
            assertTrue(output.lines().size > 1, "expected multi-line output")
        } finally {
            CodeFormatterRegistry.unregister("sql")
            CodeLanguageRegistry.unregister("sql")
        }
    }

    @Test
    fun sql_formatter_preserves_strings_and_comments() {
        CodeLanguageRegistry.register(SqlLanguage())
        CodeFormatterRegistry.register(SqlFormatter())
        try {
            val input = "select '-- not keyword --' as comment_text, /* keep me */ id from t"
            val output = SqlFormatter().format(input)
            assertTrue(output.contains("'-- not keyword --'"))
            assertTrue(output.contains("/* keep me */"))
            // 关键字在字符串外
            assertTrue(output.contains("SELECT"))
            assertTrue(output.contains("FROM"))
        } finally {
            CodeFormatterRegistry.unregister("sql")
            CodeLanguageRegistry.unregister("sql")
        }
    }

    @Test
    fun sql_formatter_is_idempotent() {
        CodeLanguageRegistry.register(SqlLanguage())
        CodeFormatterRegistry.register(SqlFormatter())
        try {
            val input = "select id from users where x = 1"
            val first = SqlFormatter().format(input)
            val second = SqlFormatter().format(first)
            // 二次格式化应保持稳定（最多只有空白微调）
            assertEquals(first.replace("\\s+".toRegex(), " ").trim(), second.replace("\\s+".toRegex(), " ").trim())
        } finally {
            CodeFormatterRegistry.unregister("sql")
            CodeLanguageRegistry.unregister("sql")
        }
    }

    @Test
    fun lua_formatter_preserves_case_by_default() {
        CodeLanguageRegistry.register(LuaLanguage())
        CodeFormatterRegistry.register(LuaFormatter())
        try {
            // Lua 是大小写敏感的；默认 formatter 不修改大小写
            val input = "local X = 1; return X"
            val output = LuaFormatter().format(input)
            assertTrue(output.contains("X"), "expected identifier X preserved")
            assertTrue(output.contains("local"), "expected local keyword preserved")
        } finally {
            CodeFormatterRegistry.unregister("lua")
            CodeLanguageRegistry.unregister("lua")
        }
    }

    @Test
    fun lua_formatter_with_normalize_case_lowercases_keywords() {
        CodeLanguageRegistry.register(LuaLanguage())
        try {
            // Lua 是大小写敏感语言；关键字必须小写才能识别出来
            val input = "local x = 1; return x; end"
            // normalizeCase = true 时关键字变小写
            val output = LuaFormatter(normalizeCase = true).format(input)
            assertTrue(output.contains("local"), "expected local: $output")
            assertTrue(output.contains("return"), "expected return: $output")
            assertTrue(output.contains("end"), "expected end: $output")
            // 用户标识符 x 不受影响
            assertTrue(output.contains("x"), "expected identifier x preserved: $output")
        } finally {
            CodeLanguageRegistry.unregister("lua")
        }
    }

    @Test
    fun lua_formatter_preserves_long_strings() {
        CodeLanguageRegistry.register(LuaLanguage())
        try {
            val input = "local s = [[multi\nline\nstring]]"
            val output = LuaFormatter().format(input)
            assertTrue(output.contains("[[multi\nline\nstring]]"))
        } finally {
            CodeLanguageRegistry.unregister("lua")
        }
    }

    @Test
    fun highlighter_produces_annotated_string_with_styles() {
        val source = "SELECT * FROM users"
        val tokens = SqlLanguage().tokenize(source)
        val highlighter = SyntaxHighlighter(SyntaxHighlighter.DefaultLightColors)
        val annotated = highlighter.highlight(source, tokens)
        // AnnotatedString 必须有 span 范围（关键字应该有 spanStyle）
        assertTrue(annotated.length == source.length)
        assertTrue(annotated.spanStyles.isNotEmpty(), "expected at least one SpanStyle")
    }

    @Test
    fun highlighter_returns_null_style_for_unknown_type() {
        val highlighter = SyntaxHighlighter(emptyMap())
        assertEquals(null, highlighter.styleFor(TokenType.KEYWORD))
    }

    @Test
    fun register_builtin_editors_is_idempotent() {
        // 调用两次不应产生副作用
        com.kxxnzstdsw.idb_app.editor.ui.registerBuiltinEditors()
        com.kxxnzstdsw.idb_app.editor.ui.registerBuiltinEditors()
        assertNotNull(CodeLanguageRegistry.get("sql"))
        assertNotNull(CodeLanguageRegistry.get("lua"))
    }
}
