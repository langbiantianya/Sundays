package com.kxxnzstdsw.sundays.editor

import com.kxxnzstdsw.sundays.editor.language.LuaLanguage
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.editor.language.SqlLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 补全（「提示」）的纯逻辑契约。
 *
 * UI 部分（弹层定位、按键分发）依赖 Compose 布局，无法在纯 JVM 单测里可靠断言；
 * 这里锁的是**能锁住的那一半**：前缀怎么切、候选怎么选、接受后文本怎么变。
 * 三者一旦错，弹层无论画得多好看都是错的。
 */
class CompletionTest {

    // =========================================================================
    // 前缀切分
    // =========================================================================

    @Test
    fun `prefix is the whole word right before the caret`() {
        assertEquals("sel", wordPrefixBefore("select sel", 10))
        assertEquals("users", wordPrefixBefore("select * from users", 19))
    }

    @Test
    fun `prefix stops at the caret when the caret is inside the word`() {
        assertEquals("se", wordPrefixBefore("sel", 2))
        assertEquals("us", wordPrefixBefore("users", 2))
    }

    @Test
    fun `prefix is empty right after a delimiter or a space`() {
        assertEquals("", wordPrefixBefore("select ", 7))
        assertEquals("", wordPrefixBefore("select\tid", 7))
        assertEquals("", wordPrefixBefore("select,id", 7))
        assertEquals("", wordPrefixBefore("select (id", 7))
    }

    @Test
    fun `digits and underscores belong to the word`() {
        assertEquals("my_col_2", wordPrefixBefore("select my_col_2", 15))
    }

    @Test
    fun `a chinese comment does not swallow the word after it`() {
        // 词字符**必须**限定 ASCII：若用 `Char.isLetterOrDigit()`，中文返回 true，
        // 前缀会算成「查询sel」，补全立刻失灵 —— 这正是中文注释下的真实场景。
        assertEquals("sel", wordPrefixBefore("-- 查询sel", 9))
        assertEquals("users", wordPrefixBefore("-- 查所有users", 11))
    }

    @Test
    fun `an out of range caret is clamped instead of throwing`() {
        // 外部塞进来的 TextFieldValue 可能给出越界光标；这条函数每次按键都跑，不能抛
        assertEquals("", wordPrefixBefore("select", 0))
        assertEquals("select", wordPrefixBefore("select", 999))
        assertEquals("", wordPrefixBefore("select", -5))
    }

    // =========================================================================
    // 接受候选
    // =========================================================================

    @Test
    fun `accepting replaces the whole word and parks the caret after it`() {
        val (text, caret) = applyCompletion("select sel", 10, CompletionItem("SELECT", CompletionKind.KEYWORD))
        assertEquals("select SELECT", text)
        assertEquals(13, caret)
    }

    @Test
    fun `accepting from the middle of a word replaces the rest of it too`() {
        // 只追加后缀会留下 `selECT` —— 所以替换范围是**整个词**，不是仅前缀
        val (text, caret) = applyCompletion("sel", 2, CompletionItem("SELECT", CompletionKind.KEYWORD))
        assertEquals("SELECT", text)
        assertEquals(6, caret)
    }

    @Test
    fun `accepting keeps the text after the caret`() {
        val (text, caret) = applyCompletion("sel from t", 3, CompletionItem("SELECT", CompletionKind.KEYWORD))
        assertEquals("SELECT from t", text)
        assertEquals(6, caret)
    }

    // =========================================================================
    // 候选筛选与排序
    // =========================================================================

    private val pool = buildCompletionPool(
        CompletionKind.KEYWORD to setOf("SELECT", "SET", "SERIAL"),
        CompletionKind.TYPE to setOf("INT", "SMALLINT"),
        CompletionKind.BUILTIN to setOf("SUM", "SUBSTRING"),
    )

    private fun labels(prefix: String, limit: Int = 10, caseSensitive: Boolean = false) =
        selectCompletions(pool, prefix, caseSensitive, limit).map { it.label }

    @Test
    fun `shorter words of the same category come first`() {
        // 输入 CO 时 `COLUMN`(6) / `COMMIT`(6) 该赢过 `COLLATE`(7) —— 同类别按长度升序
        val colPool = buildCompletionPool(
            CompletionKind.KEYWORD to setOf("COLLATE", "COLUMN", "COMMIT"),
        )
        val got = selectCompletions(colPool, "CO", true, 10).map { it.label }
        assertEquals(listOf("COLUMN", "COMMIT", "COLLATE"), got)
    }

    @Test
    fun `keywords rank ahead of types which rank ahead of functions`() {
        // 类别优先 → 长度升序 → 字典序：
        // 关键字 SET(3) SELECT(6) SERIAL(6)，类型 SMALLINT，函数 SUM(3) SUBSTRING(9)
        assertEquals(
            listOf("SET", "SELECT", "SERIAL", "SMALLINT", "SUM", "SUBSTRING"),
            labels("S"),
        )
    }

    @Test
    fun `ties are broken alphabetically so the list is stable`() {
        // 词表是 Set（无序），若不额外定序，同一批候选在两次按键间可能换位置 ——
        // 用户往下按方向键时会「跳」。所以池在构造期就排成全序。
        val tied = buildCompletionPool(CompletionKind.KEYWORD to setOf("ZED", "ABC", "MID"))
        assertEquals(listOf("ABC", "MID", "ZED"), tied.map { it.label })
    }

    @Test
    fun `the limit caps the result but never reorders it`() {
        assertEquals(listOf("SET", "SELECT"), labels("S", limit = 2))
    }

    @Test
    fun `an empty prefix or a non positive limit yields nothing`() {
        assertTrue(labels("").isEmpty())
        assertTrue(labels("S", limit = 0).isEmpty())
        assertTrue(labels("S", limit = -1).isEmpty())
    }

    @Test
    fun `case sensitivity decides whether a prefix matches`() {
        assertEquals(listOf("SELECT"), selectCompletions(pool, "sel", false, 5).map { it.label })
        // 大小写敏感时小写前缀匹配不到大写词
        assertTrue(selectCompletions(pool, "sel", true, 5).isEmpty())
        assertEquals(listOf("SELECT"), selectCompletions(pool, "SEL", true, 5).map { it.label })
    }

    // =========================================================================
    // 语言各自的大小写策略
    // =========================================================================

    @Test
    fun `sql completes case insensitively into the canonical upper case form`() {
        val sql = SqlLanguage(SqlDialectProfile.STANDARD)
        // SQL 关键字大小写不敏感，且 SqlFormatter 也会大写 —— 三处口径必须一致
        assertTrue(sql.completionCandidates("sel").any { it.label == "SELECT" })
        assertTrue(sql.completionCandidates("SEL").any { it.label == "SELECT" })
    }

    @Test
    fun `sql dialect profiles contribute their own words`() {
        val mysql = SqlLanguage(SqlDialectProfile.MYSQL)
        // MySQL 档位的专有关键字必须能被补出来，否则切方言后补全像坏了
        val labels = mysql.completionCandidates("unsi").map { it.label }
        assertTrue(labels.contains("UNSIGNED"), "MySQL 档位应能补出 UNSIGNED，实际=$labels")
        // 同一批词不能漏进标准档位 —— 档位只追加、不互相污染
        val standard = SqlLanguage(SqlDialectProfile.STANDARD)
            .completionCandidates("unsi").map { it.label }
        assertTrue(standard.isEmpty(), "标准档位不该认识方言词 UNSIGNED，实际=$standard")
    }

    @Test
    fun `lua stays case sensitive because identifiers are`() {
        val lua = LuaLanguage()
        assertTrue(lua.completionCandidates("prin").isNotEmpty())
        // 大小写敏感：Pri 不是 print，**绝不能**补成 print ——
        // 那等于把语义不同的标识符塞进用户代码，属于制造 bug 而非帮忙
        assertTrue(lua.completionCandidates("Pri").isEmpty(), "Lua 补全不应忽略大小写")
    }

    @Test
    fun `a language without completion support simply returns nothing`() {
        // CodeLanguage.completionCandidates 的默认实现 —— 保证第三方语言零改动
        val bare = object : CodeLanguage {
            override val id = "bare"
            override val displayName = "Bare"
            override fun tokenize(source: String) = emptyList<CodeToken>()
        }
        assertTrue(bare.completionCandidates("se").isEmpty())
    }

    @Test
    fun `candidates never exceed the requested limit`() {
        val sql = SqlLanguage(SqlDialectProfile.STANDARD)
        assertTrue(sql.completionCandidates("s", limit = 3).size <= 3)
        assertTrue(sql.completionCandidates("s", limit = 1).size <= 1)
    }

    @Test
    fun `every candidate of a real language is non blank and uppercase for sql`() {
        val sql = SqlLanguage(SqlDialectProfile.STANDARD)
        for (item in sql.completionCandidates("s", limit = 20)) {
            assertTrue(item.label.isNotBlank())
            assertEquals(item.label.uppercase(), item.label, "SQL 候选应为规范大写形式：${item.label}")
        }
    }

    // =========================================================================
    // 上下文专属候选（extraCompletions）—— 造数沙箱宿主函数
    // =========================================================================

    private val extras = listOf(
        CompletionItem("insert", CompletionKind.BUILTIN, "insert(表名, {列=值})"),
        CompletionItem("lastId", CompletionKind.BUILTIN, "lastId() → 自增ID"),
        CompletionItem("random_int", CompletionKind.BUILTIN, "random_int(min, max)"),
        CompletionItem("random_enum", CompletionKind.BUILTIN, "random_enum({候选…})"),
    )

    private fun extrasFor(prefix: String, limit: Int = 10) =
        selectExtras(prefix, extras, limit).map { it.label }

    @Test
    fun `extras match by exact case`() {
        assertEquals(listOf("insert"), extrasFor("ins"))
        assertEquals(listOf("insert"), extrasFor("insert"))
        // 大小写敏感：`Insert` 不是 `insert` —— 造数宿主函数是真全局，
        // 补成别的名字会改变脚本语义，属于制造 bug
        assertTrue(extrasFor("Ins").isEmpty())
        assertTrue(extrasFor("INSERT").isEmpty())
    }

    @Test
    fun `extras keep the caller order instead of being re-sorted`() {
        // 清单是人工按「最常用 → 最专用」排的，那个顺序比按字母排更有信息量
        assertEquals(
            listOf("random_int", "random_enum"),
            extrasFor("random_"),
        )
    }

    @Test
    fun `extras honour the limit and the empty guards`() {
        assertEquals(listOf("random_int"), extrasFor("random_", limit = 1))
        assertTrue(extrasFor("").isEmpty())
        assertTrue(extrasFor("random_", limit = 0).isEmpty())
    }

    @Test
    fun `the generate sandbox contract exposes exactly the host helpers`() {
        val names = GenerateHelpers.names
        assertTrue(names.containsAll(listOf("insert", "lastId")), "核心两个写库函数必须在：$names")
        assertTrue(names.count { it.startsWith("random_") } == 11, "random_* 应为 11 个，实际=$names")
        // 清单不得有重名 —— 重名会让同一函数在弹层里出现两次
        assertEquals(names.size, names.toSet().size, "宿主函数清单有重名：$names")
        // 每个候选都要有签名：只有函数名的话用户无从判断该传什么参数
        assertTrue(GenerateHelpers.completions.all { !it.detail.isNullOrBlank() }, "存在缺签名的候选")
    }

    @Test
    fun `sandbox disabled entries never overlap the injected helpers`() {
        val overlap = GenerateHelpers.names.toSet() intersect GenerateHelpers.sandboxDisabled.toSet()
        assertTrue(overlap.isEmpty(), "同一个名字既注入又置 nil，行为取决于调用顺序：$overlap")
    }

    @Test
    fun `host helpers are absent from the plain lua language`() {
        // 这是整个设计的关键不变量：`insert` / `random_*` **只在造数沙箱里存在**。
        // 一旦有人把它们塞进 LuaLanguage.BUILTINS，所有普通 Lua 编辑器都会开始
        // 推荐不存在的函数，调用即报 `attempt to call a nil value`。
        val lua = LuaLanguage()
        for (name in GenerateHelpers.names) {
            assertTrue(
                lua.completionCandidates(name).isEmpty(),
                "`$name` 是造数沙箱专属，不该出现在通用 Lua 补全里",
            )
        }
    }
}
