package com.kxxnzstdsw.sundays

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拼 `WHERE` 子句时的**字面量转义**。
 *
 * 这是安全边界，不是格式校验：`DataListRequest.where` 收的是**裸 SQL 片段**、
 * 没有占位符，前端把它原样交给引擎执行。表内搜索词是用户输入，直接拼进去
 * 就是注入面。
 */
class SqlLiteralsTest {

    @Test
    fun `plain text is quoted as is`() {
        assertEquals("'alice'", SqlLiterals.quote("alice"))
    }

    @Test
    fun `a single quote is doubled`() {
        // SQL 标准里字符串字面量内部���单引号就靠 '' 转义。
        // 不转义的话 `'; DROP TABLE users; --` 会闭合字面量、后面全是 SQL。
        assertEquals("'O''Brien'", SqlLiterals.quote("O'Brien"))
    }

    @Test
    fun `an injection attempt stays inside the literal`() {
        val evil = "'; DROP TABLE users; --"
        val quoted = SqlLiterals.quote(evil)
        // 关键性质：值里不再有**奇数个**能提前闭合字面量的单引号
        assertEquals(
            0,
            quoted.trim('\'').count { it == '\'' } % 2,
            "转义后不应存在能提前闭合字面量的引号：$quoted",
        )
        // 逐字符对照：除成对翻倍外，内容原样保留
        assertEquals("'''; DROP TABLE users; --'", quoted)
    }

    @Test
    fun `an empty term produces no predicate at all`() {
        // 空搜索必须返回 null（= 不加过滤），而不是 `LIKE '%%'` ——
        // 后者虽然结果一样，却让每条查询都带一个无意义的全表扫描谓词
        assertNull(SqlLiterals.likeContains("username", ""))
        assertNull(SqlLiterals.likeContains("username", "   "))
    }

    @Test
    fun `an empty column produces no predicate either`() {
        // 列名缺失时同样返回 null，绝不能拼出「只有 LIKE 没有左操作数」的残句
        assertNull(SqlLiterals.likeContains("", "bob"))
        assertNull(SqlLiterals.likeContains("  ", "bob"))
    }

    @Test
    fun `a term becomes a contains like on that column`() {
        assertEquals(
            "CAST(username AS VARCHAR) LIKE '%bob%'",
            SqlLiterals.likeContains("username", "bob"),
        )
        // 前后空白不算用户输入的一部分
        assertEquals(
            "CAST(username AS VARCHAR) LIKE '%bob%'",
            SqlLiterals.likeContains("  username  ", "  bob  "),
        )
    }

    /**
     * ⚠️ **本文件最重要的一条**：拼出来的片段必须**自带左操作数**。
     *
     * 上一版 `likeContains(term)` 返回的是 `"LIKE '%bob%'"` —— 一个没有列名的残句，
     * 而这里的每一条断言都把那个残句当成了正确输出。于是：
     * - 单测**全绿**（它只验转义，不验拼接）
     * - 整条链**一次没跑过真库**
     * - 发给引擎的是 `WHERE LIKE '%user_25%'`，H2 / SQLite / PG **全部语法错误**
     *
     * 判据必须落在**发出去的那条语句**上，而不是它的某个片段上。
     */
    @Test
    fun `the predicate always has a left operand`() {
        val p = assertNotNull(SqlLiterals.likeContains("username", "bob"))
        assertTrue(
            p.startsWith("CAST("),
            "谓词必须以 CAST(列 AS VARCHAR) 开头（自带左操作数），实际：$p",
        )
        assertFalse(
            p.trimStart().startsWith("LIKE"),
            "谓词不能以 LIKE 开头 —— 那样它就没有左操作数，拼进 WHERE 必然语法错误：$p",
        )
    }

    @Test
    fun `a term with a quote is escaped before being wrapped`() {
        assertEquals(
            "CAST(username AS VARCHAR) LIKE '%O''Brien%'",
            SqlLiterals.likeContains("username", "O'Brien"),
        )
    }

    @Test
    fun `wildcards are left alone so users can search with them`() {
        // `%` / `_` **保留** = 允许用户写通配查询。那是特性，不是漏洞。
        // 搜 `a%` 得到 `LIKE '%a%%'`：中间的 `%` 是用户要的通配，两侧是包夹用的。
        assertEquals(
            "CAST(username AS VARCHAR) LIKE '%a%%'",
            SqlLiterals.likeContains("username", "a%"),
        )
    }
}
