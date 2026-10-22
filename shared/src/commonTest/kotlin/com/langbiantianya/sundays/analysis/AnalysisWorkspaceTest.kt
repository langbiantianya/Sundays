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
import kotlin.test.assertTrue

/**
 * 工作区规则的纯逻辑测试 —— staging 表名与标识符清洗。
 *
 * 这些规则决定了「远端的一张表落到工作区的哪个名字上」，
 * 一旦错了，用户拉进来的数据就对不上号 —— 所以逐条钉住。
 */
class AnalysisWorkspaceTest {
    // ──────────────── staging 表名 ────────────────

    @Test
    fun `staging table name carries both the source and the table`() {
        assertEquals(
            "staging_shop_orders",
            stagingTableName("shop", "orders"),
            "staging 表名应当是「源库_表名」",
        )
    }

    @Test
    fun `two sources with the same table name do not collide`() {
        // 这条是「带源库前缀」那个决定要守的东西：
        // 不同源可能有同名表，不带前缀就会撞名。
        val fromShop = stagingTableName("shop", "orders")
        val fromCrm = stagingTableName("crm", "orders")
        assertTrue(fromShop != fromCrm, "两个源的同名表不该撞成同一个名字：都是 $fromShop")
    }

    // ──────────────── 标识符清洗 ────────────────

    @Test
    fun `illegal characters become underscores`() {
        assertEquals("my_table", sanitizeIdentifierPart("my table"))
        assertEquals("my_table", sanitizeIdentifierPart("my-table"))
        assertEquals("a_b_c", sanitizeIdentifierPart("a.b.c"))
    }

    @Test
    fun `quotes and semicolons cannot survive into the identifier`() {
        // 表名要拼进 SQL 标识符，而源库名 / 表名是**用户起的**。
        // 带引号或分号的原样拼进去就是 SQL 注入的口子。
        //
        // 逐字符替换：每个**不合规**字符换一个下划线，字母数字原样保留
        //   "x'; --"  → x ' ; (空格) - -  →  x + 5 个下划线
        //   "\"quoted\"" → 引号各换一个下划线，中间是字母，原样保留 →  _quoted_
        assertEquals("x_____", sanitizeIdentifierPart("x'; --"))
        assertEquals("_quoted_", sanitizeIdentifierPart("\"quoted\""))
    }

    @Test
    fun `a leading digit gets a leading underscore`() {
        // `123abc` 直接拼进 SQL 是语法错误
        assertEquals("_123abc", sanitizeIdentifierPart("123abc"))
    }

    @Test
    fun `an empty name still yields a non empty identifier`() {
        // 否则拼出来会是 `staging__` —— 名字退化，且与「源库真的叫空串」撞在一起。
        assertEquals("_", sanitizeIdentifierPart(""))
    }

    @Test
    fun `letters and digits are kept as they are`() {
        assertEquals("orders_2024", sanitizeIdentifierPart("orders_2024"))
        assertEquals("表1", sanitizeIdentifierPart("表1"))
    }

    @Test
    fun `the staging name never contains a quote or a semicolon`() {
        // 端到端再确认一次：拼**整个** staging 名，而不只是片段。
        val name = stagingTableName("my db; drop table x--", "orders'")
        assertTrue(';' !in name, "staging 名里不该有分号：$name")
        assertTrue('\'' !in name, "staging 名里不该有引号：$name")
        assertTrue('"' !in name, "staging 名里不该有双引号：$name")
    }

    // ──────────────── 工作区文件 ────────────────

    @Test
    fun `the workspace file lives in the app config directory`() {
        assertEquals(
            "/home/u/.config/sundays/workspace.duckdb",
            workspaceFilePath("/home/u"),
            "工作区应当与应用级文件同目录（~/.config/sundays）",
        )
    }

    @Test
    fun `the workspace file name is stable across sessions`() {
        // 跨会话常驻 = 文件名固定。名字一变，用户上次拉的数据就「不见了」。
        assertEquals(
            workspaceFilePath("/home/u"),
            workspaceFilePath("/home/u"),
            "同一台机器上工作区路径必须稳定",
        )
    }
}
