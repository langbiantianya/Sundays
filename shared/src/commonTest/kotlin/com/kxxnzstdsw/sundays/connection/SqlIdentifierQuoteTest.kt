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

    // ============ tableRef：PG 的 catalog 这一级只在「就是当前库」时才写 ============

    @Test
    fun `postgres writes all three levels when the database is the current one`() {
        // PG **一直**支持 catalog.schema.table 三段式（不是 PG 18 才有的特性）。
        // 源码判据只有一条（RangeVarGetCreationNamespace）：
        //   catalogname != get_database_name(MyDatabaseId) → 报 cross-database references...
        // 真库实测（PG 18.4 / examquestions）：
        //   连到 examquestions：FROM examquestions.public.t1      ✅
        //   连到 postgres     ：FROM examquestions.public.t1      ❌ 0A000
        assertEquals(
            "\"examquestions\".\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "public", "biz_user", true),
        )
    }

    @Test
    fun `postgres drops the catalog level when the database is not the current one`() {
        // 树列的是**同一实例上的所有库**（树里 `examquestions` / `postgres` /
        // `sundays_smoke` 并列），而连接只连了其中一个 —— 所以
        // `database != 当前连接的库` 完全可能发生，那种情况下写 catalog
        // 就会产出「粘进 SQL 编辑器必炸」的引用。
        // 退回 schema.table：那个形态在 PG 里任何版本、任何库都恒可用。
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "public", "biz_user", false),
        )
        // 不显式传时默认就是退化的那一侧 —— 漏传参数不会静默产出三段式
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "public", "biz_user"),
        )
    }

    @Test
    fun `postgres three part form needs both database and schema`() {
        // 变异点：把两个 isNotBlank 去掉时这两条会红 —— 空白段会被引用成 `""`，
        // 产出 `""."public"."t` 这种凭空多一段的引用。
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "", "biz_user", true),
        )
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "examquestions", "  ", "biz_user", true),
        )
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "", "public", "biz_user", true),
        )
    }

    @Test
    fun `tableRef prefers real schema when the dialect has one`() {
        assertEquals(
            "\"public\".\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.H2, "EXAM", "public", "biz_user"),
        )
        assertEquals(
            "\"PUBLIC\".\"USERS\"",
            SqlIdentifier.tableRef(DialectType.H2, "EXAM", "PUBLIC", "USERS"),
        )
    }

    @Test
    fun `mysql keeps database as qualifier and ignores the current-database flag`() {
        // MySQL 的 database 就是命名空间本身（USE db 之后 catalog 与 schema 合一），
        // `db`.`table` 合法，而且**没有** PG 那个「必须等于当前库」的限制
        assertEquals(
            "`examquestions`.`biz_user`",
            SqlIdentifier.tableRef(DialectType.MYSQL, "examquestions", "", "biz_user"),
        )
        assertEquals(
            "`examquestions`.`biz_user`",
            SqlIdentifier.tableRef(DialectType.MYSQL, "examquestions", "", "biz_user", true),
        )
    }

    @Test
    fun `never treats the database name as a schema`() {
        // ⚠️ 「库名当 schema 用」确实会失败 —— 真库实测
        // `"examquestions"."biz_user"` 报的是 42P01
        // `relation "examquestions.biz_user" does not exist`（**不是** 0A000），
        // 因为它被解释成「名为 examquestions 的 schema 下的 biz_user」。
        //
        // 但这条结论**不能**推出「库名不能出现在引用里」—— 三段式是另一条路径。
        // 上一版就是在这儿把「跨库不支持」误读成「catalog 这一级不能写」，
        // 结果 PG 用户复制到的引用永远少一级。见 [SqlIdentifier.tableRef] 的 KDoc。
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
        // 连「就是当前库」都救不回来时也得干净退化，不能产出 `"".""."biz_user"`
        assertEquals(
            "\"biz_user\"",
            SqlIdentifier.tableRef(DialectType.POSTGRESQL, "", "", "biz_user", true),
        )
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

    // ============ path ============

    @Test
    fun `path skips blank segments instead of quoting them`() {
        assertEquals(
            "\"a\".\"b\".\"c\"",
            SqlIdentifier.path(DialectType.POSTGRESQL, "a", "b", "c"),
        )
        assertEquals("\"a\".\"c\"", SqlIdentifier.path(DialectType.POSTGRESQL, "a", "", "c"))
        assertEquals("\"a\".\"c\"", SqlIdentifier.path(DialectType.POSTGRESQL, "a", "   ", "c"))
        assertEquals("`a`.`c`", SqlIdentifier.path(DialectType.MYSQL, "a", "", "c"))
        assertEquals("\"c\"", SqlIdentifier.path(DialectType.POSTGRESQL, "", "", "c"))
    }

    // ============ databaseRef ============

    @Test
    fun `databaseRef uses the dialect quoting`() {
        assertEquals("`exam-questions`", SqlIdentifier.databaseRef(DialectType.MYSQL, "exam-questions"))
        assertEquals("\"exam-questions\"", SqlIdentifier.databaseRef(DialectType.POSTGRESQL, "exam-questions"))
    }
}
