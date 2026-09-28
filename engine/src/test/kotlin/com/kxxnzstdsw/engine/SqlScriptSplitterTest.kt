package com.kxxnzstdsw.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SqlScriptSplitter] 词法切分测试 —— 覆盖各类引号、注释与 dollar-quoting 中的分号边界。
 *
 * 注意：注释文本会被原样保留在语句中（只有其中的 `;` 不作为边界），
 * 因此带前置注释的脚本切出的语句会包含该注释文本。
 */
class SqlScriptSplitterTest {

    @Test
    fun `trims and drops empty fragments`() {
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScriptSplitter.split("SELECT 1; SELECT 2;"))
        assertEquals(listOf("SELECT 1"), SqlScriptSplitter.split("SELECT 1;;"))
        assertEquals(emptyList(), SqlScriptSplitter.split(""))
        assertEquals(emptyList(), SqlScriptSplitter.split("   \n  "))
        assertEquals(emptyList(), SqlScriptSplitter.split(";"))
        assertEquals(listOf("SELECT 1"), SqlScriptSplitter.split("  SELECT 1  ;  "))
    }

    @Test
    fun `trailing statement without terminator is kept`() {
        assertEquals(listOf("SELECT 1"), SqlScriptSplitter.split("SELECT 1"))
        assertEquals(listOf("SELECT 1", "SELECT 2"), SqlScriptSplitter.split("SELECT 1; SELECT 2"))
    }

    @Test
    fun `semicolon inside single quoted literal does not split`() {
        assertEquals(
            listOf("INSERT INTO t VALUES ('a;b')"),
            SqlScriptSplitter.split("INSERT INTO t VALUES ('a;b')"),
        )
        assertEquals(
            listOf("INSERT INTO t VALUES ('it''s;ok')", "SELECT 2"),
            SqlScriptSplitter.split("INSERT INTO t VALUES ('it''s;ok'); SELECT 2"),
        )
        assertEquals(
            listOf("INSERT INTO t VALUES ('back\\';slash')"),
            SqlScriptSplitter.split("INSERT INTO t VALUES ('back\\';slash')"),
        )
    }

    @Test
    fun `lone semicolon literal is a statement not a separator`() {
        assertEquals(listOf("';'"), SqlScriptSplitter.split("';'"))
        assertEquals(listOf("';'"), SqlScriptSplitter.split("  ';'  "))
    }

    @Test
    fun `double quoted identifier does not split and handles escaped quote`() {
        assertEquals(
            listOf("SELECT \"a;b\" FROM t"),
            SqlScriptSplitter.split("SELECT \"a;b\" FROM t"),
        )
        assertEquals(
            listOf("SELECT \"we\"\"ird;col\"", "SELECT 2"),
            SqlScriptSplitter.split("SELECT \"we\"\"ird;col\"; SELECT 2"),
        )
    }

    @Test
    fun `backtick quoted identifier does not split and handles escaped backtick`() {
        assertEquals(
            listOf("SELECT `a;b` FROM t"),
            SqlScriptSplitter.split("SELECT `a;b` FROM t"),
        )
        assertEquals(
            listOf("SELECT `we``ird;col`", "SELECT 2"),
            SqlScriptSplitter.split("SELECT `we``ird;col`; SELECT 2"),
        )
    }

    @Test
    fun `line comments do not split and are preserved`() {
        assertEquals(
            listOf("SELECT 1", "-- drop; table\nSELECT 2"),
            SqlScriptSplitter.split("SELECT 1; -- drop; table\nSELECT 2"),
        )
        assertEquals(
            listOf("SELECT 1", "# drop; table\nSELECT 2"),
            SqlScriptSplitter.split("SELECT 1; # drop; table\nSELECT 2"),
        )
        // 尾部行注释会作为独立语句保留（未被丢弃）
        assertEquals(
            listOf("SELECT 1", "-- trailing; comment"),
            SqlScriptSplitter.split("SELECT 1; -- trailing; comment"),
        )
    }

    @Test
    fun `block comment spanning lines does not split`() {
        val script = """
            CREATE TABLE t (
              a INT, -- a;b
              /* block; comment
                 spanning; lines */
              b INT
            );
            SELECT 1;
        """.trimIndent()
        assertEquals(
            listOf(
                "CREATE TABLE t (\n  a INT, -- a;b\n  /* block; comment\n     spanning; lines */\n  b INT\n)",
                "SELECT 1",
            ),
            SqlScriptSplitter.split(script),
        )
        assertEquals(listOf("SELECT 1 /* x;y */"), SqlScriptSplitter.split("SELECT 1 /* x;y */"))
    }

    @Test
    fun `unterminated block comment consumes the rest without looping`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2", "/* never closed; oops"),
            SqlScriptSplitter.split("SELECT 1; SELECT 2; /* never closed; oops"),
        )
        assertEquals(
            listOf("/* only a comment; here"),
            SqlScriptSplitter.split("/* only a comment; here"),
        )
    }

    @Test
    fun `dollar quoting keeps semicolons and requires exact tag match`() {
        assertEquals(
            listOf("CREATE FUNCTION f() RETURNS void AS \$\$ SELECT 1; SELECT 2; \$\$ LANGUAGE sql", "SELECT 3"),
            SqlScriptSplitter.split("CREATE FUNCTION f() RETURNS void AS \$\$ SELECT 1; SELECT 2; \$\$ LANGUAGE sql; SELECT 3"),
        )
        assertEquals(
            listOf("DO \$tag\$ BEGIN PERFORM 1; PERFORM 2; END \$tag\$", "SELECT 4"),
            SqlScriptSplitter.split("DO \$tag\$ BEGIN PERFORM 1; PERFORM 2; END \$tag\$; SELECT 4"),
        )
        // 不同 tag 不是闭合标记，整段剩余输入作为一条语句
        assertEquals(
            listOf("DO \$tag\$ body; \$other\$ trailing"),
            SqlScriptSplitter.split("DO \$tag\$ body; \$other\$ trailing"),
        )
    }

    @Test
    fun `positional placeholders are not treated as dollar quoting`() {
        assertEquals(
            listOf("SELECT * FROM t WHERE id = \$1 AND name = \$2"),
            SqlScriptSplitter.split("SELECT * FROM t WHERE id = \$1 AND name = \$2"),
        )
    }

    @Test
    fun `mixed quoting in one script`() {
        val script = """
            -- setup; table
            CREATE TABLE "ord;ers" (
              id INT PRIMARY KEY,
              note VARCHAR(50) DEFAULT 'a;b''c',
              `x` INT -- x;y
            );
            /* insert; rows */
            INSERT INTO "ord;ers" VALUES (1, 'it''s; fine');
            SELECT * FROM "ord;ers" WHERE note = 'a;b''c' LIMIT 1;
        """.trimIndent()
        assertEquals(
            listOf(
                "-- setup; table\nCREATE TABLE \"ord;ers\" (\n  id INT PRIMARY KEY,\n" +
                    "  note VARCHAR(50) DEFAULT 'a;b''c',\n  `x` INT -- x;y\n)",
                "/* insert; rows */\nINSERT INTO \"ord;ers\" VALUES (1, 'it''s; fine')",
                "SELECT * FROM \"ord;ers\" WHERE note = 'a;b''c' LIMIT 1",
            ),
            SqlScriptSplitter.split(script),
        )
    }

    @Test
    fun `realistic ddl and dml script`() {
        val script = """
            -- 初始化; schema
            CREATE SCHEMA IF NOT EXISTS app;
            DROP TABLE IF EXISTS app.users; -- 允许; 重复执行
            CREATE TABLE app.users (
              id SERIAL PRIMARY KEY,
              email TEXT NOT NULL UNIQUE,
              created_at TIMESTAMPTZ DEFAULT now()
            );
            /* 种子; 数据 */
            INSERT INTO app.users (email) VALUES
              ('a@example.com'),
              ('b;c@example.com');
            CREATE OR REPLACE FUNCTION app.count_users() RETURNS bigint AS ${'$'}func${'$'}
            BEGIN
              RETURN (SELECT count(*) FROM app.users);
            END;
            ${'$'}func${'$'};
            SELECT count_users FROM app.users LIMIT 1;
        """.trimIndent()
        val statements = SqlScriptSplitter.split(script)
        assertEquals(6, statements.size)
        assertEquals("-- 初始化; schema\nCREATE SCHEMA IF NOT EXISTS app", statements[0])
        assertEquals("DROP TABLE IF EXISTS app.users", statements[1])
        assertEquals(
            "-- 允许; 重复执行\nCREATE TABLE app.users (\n  id SERIAL PRIMARY KEY,\n" +
                "  email TEXT NOT NULL UNIQUE,\n  created_at TIMESTAMPTZ DEFAULT now()\n)",
            statements[2],
        )
        assertEquals(
            "/* 种子; 数据 */\nINSERT INTO app.users (email) VALUES\n  ('a@example.com'),\n  ('b;c@example.com')",
            statements[3],
        )
        assertEquals(
            "CREATE OR REPLACE FUNCTION app.count_users() RETURNS bigint AS \$func\$\n" +
                "BEGIN\n  RETURN (SELECT count(*) FROM app.users);\nEND;\n\$func\$",
            statements[4],
        )
        assertEquals("SELECT count_users FROM app.users LIMIT 1", statements[5])
    }

    @Test
    fun `isSingleStatement detects absence of top level separator`() {
        assertTrue(SqlScriptSplitter.isSingleStatement("SELECT 1"))
        assertTrue(SqlScriptSplitter.isSingleStatement("SELECT 'a;b'"))
        assertTrue(SqlScriptSplitter.isSingleStatement("-- only; comment"))
        assertTrue(SqlScriptSplitter.isSingleStatement(""))
        assertFalse(SqlScriptSplitter.isSingleStatement("SELECT 1; SELECT 2"))
        // 结尾的分号不构成第二条语句：`SELECT 1;` 只有一条语句，
        // 它决定「是否需要开启 multi_statement」，而这里显然不需要
        assertTrue(SqlScriptSplitter.isSingleStatement("SELECT 1;"))
    }
}
