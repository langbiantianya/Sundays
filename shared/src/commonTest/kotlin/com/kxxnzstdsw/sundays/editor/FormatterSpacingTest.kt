package com.kxxnzstdsw.sundays.editor

import com.kxxnzstdsw.sundays.editor.formatter.LuaFormatter
import com.kxxnzstdsw.sundays.editor.formatter.SqlFormatter
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 格式化器的**空格 / 缩进 / 注释**契约。
 *
 * 背景：旧实现只有一个 `pendingSpace` 标志，在写每个 token **之前**决定补不补空格，
 * 于是「前一个 token 想留空格」与「当前 token 不想要前导空格」打架，实测产出三处畸形：
 *
 * | 输入 | 旧输出 | 问题 |
 * |---|---|---|
 * | `select id, name` | `SELECT id , name` | 逗号**前**多一个空格 |
 * | `where a>1` | `WHERE a> 1` | 比较符**左粘右不粘** |
 * | `select\n  a, -- a\n  b` | `SELECT\na , -- a\nb` | 续行缩进被吞，多行 SELECT 的列对齐被抹平 |
 *
 * 第三条尤其值得记一笔：旧 KDoc 白纸黑字写着「不强制缩进（保持输入的缩进）」，
 * 而实现恰恰**丢弃**了缩进 —— 那是文档与实现不符，不是设计。
 */
class FormatterSpacingTest {

    @BeforeTest
    fun registerLanguages() {
        // formatter 内部按 languageId 反查语言，查不到就原样返回 ——
        // 不注册的话下面每条断言都会「通过」（输入 = 输出），测试就成了摆设。
        SqlFormatter.register()
        LuaFormatter.register()
    }

    private fun sql(source: String) =
        SqlFormatter(SqlDialectProfile.STANDARD.languageId).format(source)

    private fun lua(source: String) = LuaFormatter().format(source)

    // =========================================================================
    // 标点：前不留 / 后留
    // =========================================================================

    @Test
    fun `a comma has no space before it and one after`() {
        assertEquals("SELECT id, name", sql("select id,name"))
        assertEquals("SELECT id, name", sql("select id , name"))
    }

    @Test
    fun `a semicolon has no space before it`() {
        assertEquals("SELECT 1;\nSELECT 2;", sql("select 1 ; select 2 ;"))
    }

    @Test
    fun `a dot binds tight to both sides`() {
        assertEquals("SELECT t.col", sql("select t . col"))
    }

    @Test
    fun `a function call eats the space before its paren`() {
        // `count (*)` → `count(*)`：跟的是函数名，不该留空格
        assertEquals("SELECT count(*), max(x)", sql("select count (*) , max (x)"))
    }

    @Test
    fun `a paren after a keyword keeps its space`() {
        // `IN (1, 2)` 是标准写法 —— 判据必须是**上一个 token 的类型**而不是标点本身
        assertEquals(
            "SELECT *\nFROM t\nWHERE id IN (1, 2, 3)",
            sql("select * from t where id in (1,2,3)"),
        )
    }

    @Test
    fun `a closing paren does not leave a trailing space`() {
        // `) ` 的空格必须吃掉：贴左的是闭括号，不是只吃前导
        assertEquals("SELECT (1)", sql("select ( 1 )"))
        assertEquals("SELECT f(1), g(2)", sql("select f( 1 ) , g( 2 )"))
    }

    // =========================================================================
    // 操作符：两侧对称
    // =========================================================================

    @Test
    fun `comparison operators are spaced on both sides`() {
        // 旧实现只在操作符**后**留空格，得到 `a> 1`（左粘右不粘）
        assertEquals(
            "SELECT *\nFROM t\nWHERE a > 1 AND b < 2",
            sql("select * from t where a>1 and b<2"),
        )
    }

    @Test
    fun `the postgres cast operator stays tight`() {
        // `a::int` 的惯例是贴紧的，两侧都不留
        assertEquals("SELECT a::int\nFROM t", sql("select a :: int from t"))
    }

    @Test
    fun `lua operators are spaced on both sides too`() {
        assertEquals("if a > b and c < d then", lua("if a>b and c<d then"))
    }

    // =========================================================================
    // 缩进：原样搬运
    // =========================================================================

    @Test
    fun `continuation line indentation survives`() {
        // 旧实现把 `  a` 变成 `a`，多行 SELECT / INSERT 的列对齐被抹平
        assertEquals(
            "SELECT\n  a, -- a\n  b\nFROM t",
            sql("select\n  a, -- a\n  b\nfrom t"),
        )
    }

    @Test
    fun `deep indentation is not normalized away`() {
        // 格式化器**不做**重新缩进（那需要真正的语法分析），只搬运不重排
        assertEquals(
            "INSERT INTO t\n    (a, b, c)\nVALUES\n    (1, 2, 3)",
            sql("insert into t\n    (a,b,c)\nvalues\n    (1,2,3)"),
        )
    }

    @Test
    fun `tabs in the indentation are preserved too`() {
        assertEquals("SELECT\n\ta\nFROM t", sql("select\n\ta\nfrom t"))
    }

    // =========================================================================
    // 注释
    // =========================================================================

    @Test
    fun `a head comment stays on its own line`() {
        assertEquals("-- 查所有用户\nSELECT id, name\nFROM users", sql("-- 查所有用户\nselect id,name from users"))
    }

    @Test
    fun `a trailing comment stays on the line it belongs to`() {
        assertEquals(
            "SELECT id, -- 第一列\n       name\nFROM users",
            sql("select id, -- 第一列\n       name from users"),
        )
    }

    @Test
    fun `a trailing comment is never swallowed by the clause newline rule`() {
        // 最危险的一类：把 `-- 注释` 之后的真实 SQL 挪到注释行上，整条语句就变成注释了
        assertEquals(
            "SELECT id\nFROM users -- 结尾注释\nWHERE id > 1",
            sql("select id from users -- 结尾注释\nwhere id > 1"),
        )
    }

    @Test
    fun `keywords inside a comment are not uppercased`() {
        assertEquals("-- select from where\nSELECT 1", sql("-- select from where\nselect 1"))
    }

    @Test
    fun `a block comment keeps its internal lines and only trims trailing spaces`() {
        assertEquals("/* 表头\n   第二行 */\nSELECT 1", sql("/* 表头   \n   第二行 */\nselect 1"))
    }

    @Test
    fun `trailing spaces on a line comment are trimmed`() {
        // 行注释是最常带尾随空白的一类（写完随手敲空格）—— 旧实现在无换行时原样返回，
        // 于是 `select 1 -- 备注   ` 格式化后行尾还挂着三个空格。
        //
        // 关键：**注释后面必须还有代码**。若注释在文件末尾，`format()` 收尾的 `trimEnd()`
        // 会顺手把它擦掉，测试就「永远绿」—— 这条断言曾经因此完全没牙齿
        // （变异验证时回退修复，测试依然通过）。
        assertEquals("SELECT 1 -- 备注\nFROM t", sql("select 1 -- 备注   \nfrom t"))
        assertEquals("local a = 1 -- 备注\nprint(a)", lua("local a = 1 -- 备注   \nprint(a)"))
        // 注意**不要**顺带断言「清掉 `*/` 之前的空格」：那是注释内容的一部分
        // （块注释里常放代码样例 / ASCII 图），碰它就违反了下方「注释内容永不改写」的契约。
    }

    @Test
    fun `an unterminated block comment does not eat the rest of the file`() {
        // tokenizer 把它标成 ERROR；formatter 必须原样吐出而不是吞掉
        assertEquals("SELECT 1\n/* 没闭合", sql("select 1\n/* 没闭合"))
    }

    @Test
    fun `a file that is only a comment is left alone`() {
        assertEquals("-- 只有注释", sql("-- 只有注释"))
    }

    // =========================================================================
    // 空行
    // =========================================================================

    @Test
    fun `one blank line between clauses is kept as a paragraph break`() {
        assertEquals("SELECT a\n\nFROM t", sql("select a\n\nfrom t"))
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        // 否则「格式化两次」会累积：第一次压成 1 个，第二次还是 1 个（幂等），
        // 但手工堆砌的大片空白必须收掉
        assertEquals("SELECT a\n\nFROM t", sql("select a\n\n\n\nfrom t"))
    }

    // =========================================================================
    // 幂等性
    // =========================================================================

    @Test
    fun `formatting twice changes nothing`() {
        // 这条曾经**假通过**：旧实现对 `IN (1, 2)` 输出 `IN(1, 2)`，再格式化一次又变回
        // `IN (1, 2)` —— 看着像"空白微调"，其实是 `lastType` 被 WHITESPACE 覆盖的 bug。
        val cases = listOf(
            "select id, name from users where age >= 18",
            "select * from t where id in (1,2,3)",
            "select\n  a, -- a\n  b\nfrom t",
            "select count(*), max(x) from t",
            "-- 头注释\nselect 1 /* 尾 */ from t where a>1",
            "select a::int from t",
            "select 1;\n\n-- 段\n\nselect 2;",
        )
        for (src in cases) {
            val once = sql(src)
            assertEquals(once, sql(once), "SQL 格式化不幂等：输入=$src")
        }
    }

    @Test
    fun `lua formatting twice changes nothing`() {
        val cases = listOf(
            "local a=1;return a",
            "local t={1,2,3}",
            "return (a+b)",
            "if a>b and c<d then",
            "-- 注释\nlocal a = 1 -- 尾\nprint(a)",
            "local t = {\n  1,\n  2\n}",
        )
        for (src in cases) {
            val once = lua(src)
            assertEquals(once, lua(once), "Lua 格式化不幂等：输入=$src")
        }
    }

    // =========================================================================
    // 不变量
    // =========================================================================

    @Test
    fun `no line ever ends with trailing whitespace`() {
        val cases = listOf(
            "select a, \n b from t ",
            // 每条都刻意让行尾空白**不在**文件末尾，否则收尾 trimEnd 会兜住，断言形同虚设
            "select 1 -- 注释   \nfrom t",
            "select 1 /* 尾 */   \nfrom t",
            "/* a   \n b   */\nselect 1",
        )
        for (src in cases) {
            val out = sql(src)
            assertFalse(
                out.lines().any { it != it.trimEnd() },
                "输出存在行尾空白：${out.replace(" ", "·").replace("\n", "\\n")}",
            )
        }
    }

    @Test
    fun `an empty or blank input is returned untouched`() {
        assertEquals("", sql(""))
        assertEquals("   \n  ", sql("   \n  "))
    }

    @Test
    fun `comment content is never modified beyond trailing space`() {
        // 大小写 / 内部空格 / 分隔符一律原样
        val src = "-- TODO: fix  this   Later\nSELECT 1"
        assertTrue(sql(src).startsWith("-- TODO: fix  this   Later"), "注释内容被改动：${sql(src)}")
    }
}
