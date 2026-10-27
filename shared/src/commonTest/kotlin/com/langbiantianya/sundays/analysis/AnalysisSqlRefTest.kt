/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「树上的一个对象」到「SQL 里的一段引用」的**纯规则**测试。
 *
 * ## 为什么这组规则住在 `:shared` 里也要测
 *
 * 它有两处调用方：分析工作台（`desktopApp` 的 `AnalysisSession`）与
 * 报表设计（部件编辑页）。两处**必须**给出同一段引用 ——
 * 而「必须」不是靠自觉，是靠这组测试钉住这份**唯一**实现。
 *
 * ## 期望值为什么**手写**，不去调 `SqlIdentifier.quote`
 *
 * 如果断言写成 `assertEquals(quote(dialect, name), analysisTableRef(name, null))`，
 * 那测试只是把两侧绑在一起：任一侧被改坏，两侧一起变，测试照样绿。
 * 手写期望值才有牙齿 —— 它把「DuckDB 用双引号、`main` 不带前缀」
 * 这两个**事实**钉住，而不是把「两侧当前恰好一致」钉住。
 */
class AnalysisSqlRefTest {
    // ──────────────── 表的引用 ────────────────

    @Test
    fun `a table in the workspace has no schema prefix`() {
        // 工作区自己的表都在 `main` 里 —— 而那是最常见的情况。
        // 每次都写成 "main"."orders" 的话，用户复制到别处就多了一截多余的前缀。
        assertEquals("\"orders\"", analysisTableRef("orders", WORKSPACE_SCHEMA))
    }

    @Test
    fun `a table without a namespace has no schema prefix`() {
        assertEquals("\"orders\"", analysisTableRef("orders", null))
        assertEquals("\"orders\"", analysisTableRef("orders", ""))
        assertEquals("\"orders\"", analysisTableRef("orders", "   "))
    }

    @Test
    fun `a table in a data source carries its schema`() {
        // 不带 schema 的引用落在 `main` 里，于是查的可能是**另一个**表 ——
        // 而且**不报错**（`main` 里恰好有同名表的话），只是数据悄悄换了来源。
        assertEquals("\"shop\".\"orders\"", analysisTableRef("orders", "shop"))
    }

    @Test
    fun `a table name with spaces is still one identifier`() {
        // 用户起的表名可能有空格 —— 不加引号的话 `SELECT * FROM my table` 是语法错误。
        assertEquals("\"my table\"", analysisTableRef("my table", WORKSPACE_SCHEMA))
    }

    @Test
    fun `an embedded double quote is doubled`() {
        assertEquals("\"a\"\"b\"", analysisTableRef("a\"b", WORKSPACE_SCHEMA))
    }

    // ──────────────── 字段的引用 ────────────────

    @Test
    fun `a column always carries its table`() {
        // 光有列名在多表查询里说不清是哪张表的列 ——
        // 两个表里都叫 `id` 的字段太常见了。
        assertEquals("\"orders\".\"id\"", analysisColumnRef("orders", "id", WORKSPACE_SCHEMA))
        assertEquals("\"shop\".\"orders\".\"id\"", analysisColumnRef("orders", "id", "shop"))
    }

    @Test
    fun `a column name is quoted on its own`() {
        // 列名与表名**各自**加引号 —— 合成一个 `"shop.orders"` 是另一个东西。
        assertEquals("\"orders\".\"my col\"", analysisColumnRef("orders", "my col", WORKSPACE_SCHEMA))
    }

    // ──────────────── 插进一段 SQL ────────────────

    @Test
    fun `an empty editor gets a statement that runs as is`() {
        // 用户点完树之后最想立刻看到的就是这张表的数据 ——
        // 而 `SELECT * FROM …` 是唯一一条不用猜的起手式。
        assertEquals(
            "SELECT * FROM \"orders\"",
            appendAnalysisTableRef("", "orders", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `a column on an empty editor names its table too`() {
        assertEquals(
            "SELECT \"orders\".\"id\" FROM \"orders\"",
            appendAnalysisColumnRef("", "orders", "id", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `a table is appended on its own line`() {
        assertEquals(
            "SELECT 1\n\"orders\"",
            appendAnalysisTableRef("SELECT 1", "orders", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `a column is appended on its own line`() {
        assertEquals(
            "SELECT 1\n\"orders\".\"id\"",
            appendAnalysisColumnRef("SELECT 1", "orders", "id", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `the trailing whitespace of the old sql is trimmed away`() {
        // 不裁的话，追加的那行前面会留出一串空格 ——
        // 看起来像是 SQL 里有一段没头没尾的空白。
        assertEquals(
            "SELECT 1\n\"orders\"",
            appendAnalysisTableRef("SELECT 1   \n", "orders", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `a blank editor counts as empty`() {
        // 只有空格的编辑器与空编辑器是一回事 —— 差别只是用户先敲了什么。
        assertEquals(
            "SELECT * FROM \"orders\"",
            appendAnalysisTableRef("   \n  ", "orders", WORKSPACE_SCHEMA),
        )
    }

    @Test
    fun `a data source table keeps its schema when appended`() {
        assertEquals(
            "SELECT * FROM \"shop\".\"orders\"",
            appendAnalysisTableRef("", "orders", "shop"),
        )
        assertEquals(
            "SELECT \"shop\".\"orders\".\"id\" FROM \"shop\".\"orders\"",
            appendAnalysisColumnRef("", "orders", "id", "shop"),
        )
    }
}
