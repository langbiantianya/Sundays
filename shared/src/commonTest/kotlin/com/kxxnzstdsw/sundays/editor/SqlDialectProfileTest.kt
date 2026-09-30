package com.kxxnzstdsw.sundays.editor

import com.kxxnzstdsw.sundays.editor.formatter.CodeFormatterRegistry
import com.kxxnzstdsw.sundays.editor.formatter.SqlFormatter
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.editor.language.SqlLanguage
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SQL 方言档位测试 —— 覆盖「档位即语言」的注册契约与词表不变量。
 *
 * ## 为什么用 tokenize 断言词表分类
 * `SqlLanguage` 合并「基集 ∪ 方言词表」后按 KEYWORD → TYPE → BUILTIN 的顺序判定，
 * 因此同一个词若被重复归类（例如某方言的 keyword 同时是基集的 builtin），**高亮颜色会被静默改变**。
 * 直接断言「档位声明的每个词都 tokenize 成声明的类型」即可把这类冲突变成测试失败 ——
 * 不需要把私有词表暴露给测试。
 */
class SqlDialectProfileTest {

    private fun typeOf(profile: SqlDialectProfile, word: String): TokenType =
        SqlLanguage(profile).tokenize(word).single().type

    @Test
    fun `profile language ids are unique and match the language`() {
        val ids = SqlDialectProfile.ALL.map { it.languageId }
        assertEquals(ids.size, ids.distinct().size, "档位语言 id 重复: $ids")
        assertEquals("sql", SqlDialectProfile.STANDARD.languageId, "标准档位必须仍是注册表里的 sql")

        SqlDialectProfile.ALL.forEach { profile ->
            val lang = SqlLanguage(profile)
            assertEquals(profile.languageId, lang.id)
            assertEquals(profile.displayName, lang.displayName)
            assertTrue(profile.displayName.isNotBlank(), "${profile.languageId} 缺少显示名")
        }
    }

    @Test
    fun `every profile registers a language and a formatter`() {
        registerBuiltinEditors()
        SqlDialectProfile.ALL.forEach { profile ->
            val lang = CodeLanguageRegistry.get(profile.languageId)
            assertNotNull(lang, "语言未注册: ${profile.languageId}（编辑器会静默退化为纯文本）")
            assertEquals(profile.displayName, lang.displayName)
            assertNotNull(
                CodeFormatterRegistry.get(profile.languageId),
                "formatter 未注册: ${profile.languageId}（工作台的「格式化」按钮会静默禁用）",
            )
        }
    }

    /** 不变量：档位词表里每个词都 tokenize 成它声明的类型，即三类词两两互斥（含与基集的合并结果）。 */
    @Test
    fun `profile words classify as declared`() {
        SqlDialectProfile.ALL.forEach { profile ->
            val lang = SqlLanguage(profile)
            profile.keywords.forEach { word ->
                assertEquals(
                    TokenType.KEYWORD,
                    lang.tokenize(word.lowercase()).single().type,
                    "${profile.displayName}: $word 声明为关键字，实际不是（与基集或其他类别冲突）",
                )
            }
            profile.types.forEach { word ->
                assertEquals(
                    TokenType.TYPE,
                    lang.tokenize(word.lowercase()).single().type,
                    "${profile.displayName}: $word 声明为类型，实际不是（与基集或其他类别冲突）",
                )
            }
            profile.builtins.forEach { word ->
                assertEquals(
                    TokenType.BUILTIN,
                    lang.tokenize(word.lowercase()).single().type,
                    "${profile.displayName}: $word 声明为内置函数，实际不是（与基集或其他类别冲突）",
                )
            }
        }
    }

    /** 标准档位不带任何方言词 —— 只有连上某个方言才认识它特有的词。 */
    @Test
    fun `dialect specific words are unknown to the standard profile`() {
        val dialectWords = mapOf(
            SqlDialectProfile.MYSQL to listOf("ZEROFILL", "STRAIGHT_JOIN", "GROUP_CONCAT", "UNSIGNED"),
            SqlDialectProfile.POSTGRESQL to listOf("LATERAL", "TSVECTOR", "GENERATE_SERIES", "TABLESAMPLE"),
            SqlDialectProfile.H2 to listOf("CSVREAD", "H2VERSION", "MODE", "VARCHAR_IGNORECASE"),
            SqlDialectProfile.DUCKDB to listOf("QUALIFY", "HUGEINT", "QUANTILE_CONT", "PIVOT"),
            SqlDialectProfile.SQLITE to listOf("PRAGMA", "GLOB", "STRFTIME", "WITHOUT"),
        )
        dialectWords.forEach { (profile, words) ->
            words.forEach { word ->
                assertNotEquals(
                    TokenType.IDENTIFIER,
                    typeOf(profile, word),
                    "${profile.displayName} 档位应识别 $word",
                )
                assertEquals(
                    TokenType.IDENTIFIER,
                    typeOf(SqlDialectProfile.STANDARD, word),
                    "$word 不该出现在标准档位（否则档位形同虚设）",
                )
            }
        }
    }

    /** 回归：基集词表在所有档位都必须继续生效（档位只做追加，不能覆盖掉基集）。 */
    @Test
    fun `base vocabulary stays recognized in every profile`() {
        SqlDialectProfile.ALL.forEach { profile ->
            assertEquals(TokenType.KEYWORD, typeOf(profile, "select"), profile.displayName)
            assertEquals(TokenType.KEYWORD, typeOf(profile, "JOIN"), profile.displayName)
            assertEquals(TokenType.TYPE, typeOf(profile, "varchar"), profile.displayName)
            assertEquals(TokenType.BUILTIN, typeOf(profile, "count"), profile.displayName)
        }
    }

    /** 格式化器按同一个 languageId 反查语言，因此关键字大写也随档位。 */
    @Test
    fun `formatter uppercases dialect keywords`() {
        registerBuiltinEditors()
        val source = "pragma table_info(users)"
        assertTrue(
            SqlFormatter(SqlDialectProfile.SQLITE.languageId).format(source).contains("PRAGMA"),
            "SQLite 档位的 formatter 应把 pragma 当关键字大写",
        )
        assertTrue(
            SqlFormatter().format(source).startsWith("pragma"),
            "标准档位的 formatter 不该把 pragma 当关键字（它不是标准 SQL 关键字）",
        )
    }
}
