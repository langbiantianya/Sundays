package com.kxxnzstdsw.sundays

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DangerousSql] 的识别规则。
 *
 * 这套规则的价值全在**误报与漏报之间那条线**：
 * - 漏报 = 用户没弹确认框就执行了 DROP
 * - 误报 = 用户多点一次确认框
 *
 * 宁可误报也不漏报，所以判据往保守一侧偏；测试里对每一条都钉住。
 */
class DangerousSqlTest {

    // ---------------- 应当判为危险 ----------------

    @Test
    fun `a drop table is dangerous`() {
        val f = DangerousSql.scan("DROP TABLE users")
        assertEquals(1, f.size)
        assertEquals(DangerousSql.Finding.Kind.DROP, f.single().kind)
    }

    @Test
    fun `a truncate is dangerous`() {
        assertTrue(DangerousSql.isDangerous("TRUNCATE TABLE users"))
    }

    @Test
    fun `a delete without where is dangerous`() {
        val f = DangerousSql.scan("DELETE FROM users")
        assertEquals(DangerousSql.Finding.Kind.DELETE_WITHOUT_WHERE, f.single().kind)
    }

    @Test
    fun `an update without where is dangerous`() {
        val f = DangerousSql.scan("UPDATE users SET name = 'x'")
        assertEquals(DangerousSql.Finding.Kind.UPDATE_WITHOUT_WHERE, f.single().kind)
    }

    /**
     * 逐句检查 —— 一条脚本里可能有好几条危险语句，确认框要把**全部**列出来。
     * 只报第一条会让用户以为「确认了这一条就没别的事了」。
     */
    @Test
    fun `every dangerous statement in a script is reported`() {
        val f = DangerousSql.scan("""
            UPDATE users SET a = 1;
            DELETE FROM orders;
            DROP TABLE logs;
        """.trimIndent())
        assertEquals(
            listOf(
                DangerousSql.Finding.Kind.UPDATE_WITHOUT_WHERE,
                DangerousSql.Finding.Kind.DELETE_WITHOUT_WHERE,
                DangerousSql.Finding.Kind.DROP,
            ),
            f.map { it.kind },
            "三条危险语句必须全部报出，顺序与原文一致",
        )
    }

    // ---------------- 应当放行 ----------------

    @Test
    fun `a select is harmless`() {
        assertFalse(DangerousSql.isDangerous("SELECT * FROM users"))
    }

    @Test
    fun `a delete with where is allowed`() {
        assertFalse(
            DangerousSql.isDangerous("DELETE FROM users WHERE id = 3"),
            "带 WHERE 的 DELETE 只影响个别行，与「清空全表」不是一个量级",
        )
    }

    @Test
    fun `an update with where is allowed`() {
        assertFalse(DangerousSql.isDangerous("UPDATE users SET name = 'x' WHERE id = 1"))
    }

    @Test
    fun `an insert is harmless`() {
        assertFalse(DangerousSql.isDangerous("INSERT INTO users (id) VALUES (1)"))
    }

    // ---------------- 注释与字符串：三类误报/漏报 ----------------

    @Test
    fun `a dropped table mentioned in a line comment is not flagged`() {
        assertFalse(
            DangerousSql.isDangerous("SELECT 1 -- DROP TABLE users"),
            "整行是注释，绝不能因为里面出现了 DROP 就弹确认框",
        )
    }

    @Test
    fun `a dropped table mentioned in a block comment is not flagged`() {
        assertFalse(DangerousSql.isDangerous("SELECT 1 /* DROP TABLE users */"))
    }

    @Test
    fun `keywords inside a string literal do not trigger`() {
        assertFalse(
            DangerousSql.isDangerous("INSERT INTO audit (msg) VALUES ('DROP TABLE users')"),
            "字符串字面量里的 DROP 不是语句",
        )
    }

    @Test
    fun `a real statement after a comment is still flagged`() {
        // 漏报方向：注释在前、真语句在后 —— 必须仍然抓到
        assertTrue(
            DangerousSql.isDangerous("SELECT 1 -- 先看看表\nDROP TABLE users"),
            "注释后面跟着的真 DROP 不能被一起吃掉",
        )
    }

    @Test
    fun `a quoted identifier is not scanned for keywords`() {
        // PG / H2 里 "drop table" 是合法的列名/表名
        assertFalse(
            DangerousSql.isDangerous("SELECT * FROM \"weird drop table name\""),
            "双引号标识符里的内容不是关键字",
        )
    }

    @Test
    fun `an escaped quote inside a string does not end it early`() {
        // '' 是字符串里的一个单引号。若不处理，'a''b' 会被切成两段，
        // 后半段的 DROP 就漏出来了
        assertFalse(
            DangerousSql.isDangerous("SELECT 'it''s fine' FROM t"),
            "转义单引号不应提前结束字符串",
        )
    }

    // ---------------- 稳健性 ----------------

    @Test
    fun `an unterminated string does not hang the scanner`() {
        // 用户的编辑器里随时可能留半个引号；扫描器必须**有界**地走完，不能死循环
        assertFalse(DangerousSql.isDangerous("SELECT 'abc"))
    }

    @Test
    fun `an unterminated block comment does not hang the scanner`() {
        assertFalse(DangerousSql.isDangerous("SELECT 1 /* 没写完"))
    }

    @Test
    fun `empty and whitespace sql are harmless`() {
        assertFalse(DangerousSql.isDangerous(""))
        assertFalse(DangerousSql.isDangerous("   \n\t "))
        assertFalse(DangerousSql.isDangerous(";;;"))
    }

    @Test
    fun `leading whitespace before a keyword is tolerated`() {
        assertTrue(DangerousSql.isDangerous("   \n  DROP TABLE users"))
    }

    @Test
    fun `lowercase keywords are detected`() {
        assertTrue(DangerousSql.isDangerous("drop table users"))
        assertTrue(DangerousSql.isDangerous("delete from users"))
    }

    /**
     * 词边界：`DROPPED` / `UPDATES` / `deleted_at` 这类**标识符**不是关键字。
     *
     * 用 `contains` 而不是 `\b` 的话，`SELECT * FROM dropped_flags` 会弹确认框 ——
     * 而它只是一次普通查询。误报不致命，但会让确认框很快变成「无脑点掉」的东西，
     * 那时它对真正的危险操作也就失效了。
     */
    @Test
    fun `identifiers that merely contain a keyword are not flagged`() {
        assertFalse(DangerousSql.isDangerous("SELECT * FROM dropped_flags"))
        assertFalse(DangerousSql.isDangerous("SELECT deleted_at FROM audit"))
        assertFalse(DangerousSql.isDangerous("SELECT updates FROM counters"))
    }

    /** 带 WHERE 时不该报，哪怕 WHERE 出现在别的位置（如子查询里）。 */
    @Test
    fun `a where anywhere in the statement counts as filtered`() {
        assertFalse(
            DangerousSql.isDangerous("DELETE FROM users WHERE id IN (SELECT id FROM banned)"),
            "子查询里有 WHERE，外层 DELETE 就不是「全表删」",
        )
    }
}
