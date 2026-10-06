package com.kxxnzstdsw.sundays

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertNull(SqlLiterals.likeContains(""))
        assertNull(SqlLiterals.likeContains("   "))
    }

    @Test
    fun `a term becomes a contains like`() {
        assertEquals("LIKE '%bob%'", SqlLiterals.likeContains("bob"))
        // 前后空白不算用户输入的一部分
        assertEquals("LIKE '%bob%'", SqlLiterals.likeContains("  bob  "))
    }

    @Test
    fun `a term with a quote is escaped before being wrapped`() {
        assertEquals("LIKE '%O''Brien%'", SqlLiterals.likeContains("O'Brien"))
    }

    @Test
    fun `wildcards are left alone so users can search with them`() {
        // `%` / `_` **保留** = 允许用户写通配查询。那是特性，不是漏洞。
        // 搜 `a%` 得到 `LIKE '%a%%'`：中间的 `%` 是用户要的通配，两侧是包夹用的。
        assertEquals("LIKE '%a%%'", SqlLiterals.likeContains("a%"))
    }
}
