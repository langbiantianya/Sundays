package com.kxxnzstdsw.sundays.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SqlIdentifier] —— 对象树「复制引用名」的内容契约。
 *
 * ## 为什么这组测试的期望值要**手写**，不去调引擎的 `quoteIdentifier`
 *
 * 如果断言写成 `assertEquals(dialect.quoteIdentifier(name), SqlIdentifier.quote(d, name))`，
 * 那测试只是把两侧绑在一起：任一侧被改坏，两侧一起变，测试照样绿。
 * 手写期望值才有牙齿 —— 它把「MySQL 必须用反引号」这个**事实**钉住，
 * 而不是把「两侧当前恰好一致」钉住。
 *
 * 下面的期望值来源是各方言 `quoteIdentifier` 的当前实现（逐个读过，不是猜的）。
 */
class SqlIdentifierQuoteTest {

    // ============ quote：五种方言的引号风格 ============

    @Test
    fun `mysql uses backtick not double quote`() {
        // ⚠️ 这是本组最要紧的一条：ANSI 双引号在 MySQL 里默认是**字符串字面量**，
        // `"biz_user"` 会被解析成 SELECT 'biz_user'。写成双引号 = 复制出一个
        // 「看着对、粘上去查的是常量」的东西，比不给引用更糟。
        assertEquals("`biz_user`", SqlIdentifier.quote(DialectType.MYSQL, "biz_user"))
    }

    @Test
    fun `mysql escapes embedded backtick by doubling`() {
        assertEquals("`a``b`", SqlIdentifier.quote(DialectType.MYSQL, "a`b"))
    }

    @Test
    fun `postgresql and embedded dialects use double quote`() {
        assertEquals("\"biz_user\"", SqlIdentifier.quote(DialectType.POSTGRESQL, "biz_user"))
        assertEquals("\"biz_user\"", SqlIdentifier.quote(DialectType.H2, "biz_user"))
        assertEquals("\"biz_user\"", SqlIdentifier.quote(DialectType.DUCKDB, "biz_user"))
        assertEquals("\"biz_user\"", SqlIdentifier.quote(DialectType.SQLITE, "biz_user"))
    }

    @Test
    fun `unknown dialect falls back to double quote`() {
        assertEquals("\"x\"", SqlIdentifier.quote(DialectType.UNKNOWN, "x"))
    }

    @Test
    fun `embedded double quote is doubled not backslash escaped`() {
        // MySQL 默认 sql_mode 下反斜杠不是转义符，backslash-escape 会把名字改掉；
        // 标准 SQL 与所有内置方言都是「引号写两遍」。
        assertEquals("\"a\"\"b\"", SqlIdentifier.quote(DialectType.POSTGRESQL, "a\"b"))
        assertEquals("\"a\"\"b\"", SqlIdentifier.quote(DialectType.SQLITE, "a\"b"))
    }

    // ============ 大小写：H2 是唯一有分歧的方言 ============

    @Test
    fun `h2 does not fold case when quoting a known stored name`() {
        // ⚠️ 与 `H2Dialect.quoteIdentifier` **故意**不同（那里 uppercase）。
        // 复制场景下树上显示的就是库里真实存着的名字：用户用 `CREATE TABLE "users"`
        // 建出来的表存的就是小写 users，引用必须是 "users"；折成 "USERS" 会指向
        // 另一张表或根本不存在。
        assertEquals("\"users\"", SqlIdentifier.quote(DialectType.H2, "users"))
        // 未加引号建表时被 H2 折叠成大写的，树上显示的就是 USERS，引用也保持 USERS。
        assertEquals("\"USERS\"", SqlIdentifier.quote(DialectType.H2, "USERS"))
    }

    @Test
    fun `no dialect folds case`() {
        assertEquals("\"bizUser\"", SqlIdentifier.quote(DialectType.POSTGRESQL, "bizUser"))
        assertEquals("`bizUser`", SqlIdentifier.quote(DialectType.MYSQL, "bizUser"))
    }

    // ============ qualified ============

    @Test
    fun `qualified quotes each part separately`() {
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.qualified(DialectType.POSTGRESQL, "public", "biz_user"),
        )
        assertEquals(
            "`examquestions`.`biz_user`",
            SqlIdentifier.qualified(DialectType.MYSQL, "examquestions", "biz_user"),
        )
    }

    @Test
    fun `qualified drops the schema when it is blank`() {
        assertEquals("\"biz_user\"", SqlIdentifier.qualified(DialectType.POSTGRESQL, "", "biz_user"))
        assertEquals("\"biz_user\"", SqlIdentifier.qualified(DialectType.POSTGRESQL, "   ", "biz_user"))
    }

    @Test
    fun `qualified escapes a quote inside the schema part too`() {
        // 变异点：只 escape 表名、漏掉 schema 段的话，
        // `"we""ird".t` 会退化成 `"we"ird".t` —— 前半段引号不闭合，整条 SQL 语法错误。
        assertEquals(
            "\"we\"\"ird\".\"t\"",
            SqlIdentifier.qualified(DialectType.POSTGRESQL, "we\"ird", "t"),
        )
    }

    // ============ tableRef：schema 层是否存在是判据 ============

    @Test
    fun `tableRef prefers real schema when the dialect has one`() {
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "public", "biz_user"),
        )
        assertEquals(
            "\"PUBLIC\".\"USERS\"",
            SqlIdentifier.tableRef(DialectType.H2, "EXAM", "PUBLIC", "USERS"),
        )
    }

    @Test
    fun `tableRef falls back to database for mysql only`() {
        // MySQL 的 database 就是命名空间本身，`db`.`table` 合法。
        assertEquals(
            "`examquestions`.`biz_user`",
            SqlIdentifier.tableRef(DialectType.MYSQL, "examquestions", "", "biz_user"),
        )
    }

    @Test
    fun `tableRef never uses database as qualifier on dialects with a separate schema layer`() {
        // ⚠️ 本组第二要紧的一条：PG 里 `examquestions` 是 **catalog** 而 `public` 才是
        // schema。写成 "examquestions"."biz_user" 会被解析成一个名为 examquestions 的
        // schema 下的表 —— 找不到，而用户眼前明明就是这张表。
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "", "biz_user"),
        )
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.H2, "EXAM", "", "biz_user"),
        )
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.DUCKDB, "exam", "", "biz_user"),
        )
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.SQLITE, "exam", "", "biz_user"),
        )
    }

    @Test
    fun `tableRef with no schema and no database degrades to bare quoted name`() {
        // 退化后仍要**按方言**引用：MySQL 给 `` `biz_user` `` 而不是 `"biz_user"`
        // —— 后者在 MySQL 里是字符串字面量，正是本组第一条测试在防的那件事。
        assertEquals("`biz_user`", SqlIdentifier.tableRef(DialectType.MYSQL, "", "", "biz_user"))
        assertEquals("`biz_user`", SqlIdentifier.tableRef(DialectType.MYSQL, "", "  ", "biz_user"))
        assertEquals("\"biz_user\"", SqlIdentifier.tableRef(DialectType.POSTGRESQL, "", "", "biz_user"))
    }

    @Test
    fun `tableRef with empty schema falls through to the mysql branch not the schema branch`() {
        // 变异点：把 `isNotBlank` 写成 `isNotEmpty` 时这条会红 ——
        // 空白 schema 会去 quote 一个空串，产出 `""`.`t` 这种凭空多一段的引用。
        assertEquals(
            "`examquestions`.`t`",
            SqlIdentifier.tableRef(DialectType.MYSQL, "examquestions", "  ", "t"),
        )
    }

    // ============ databaseRef ============

    @Test
    fun `databaseRef uses the dialect quoting`() {
        assertEquals("`exam-questions`", SqlIdentifier.databaseRef(DialectType.MYSQL, "exam-questions"))
        assertEquals("\"exam-questions\"", SqlIdentifier.databaseRef(DialectType.POSTGRESQL, "exam-questions"))
    }
}
