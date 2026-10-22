/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.sundays.connection.DialectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拉取规则的纯逻辑测试 —— 建表语句、导出的 SELECT。
 *
 * 这些规则决定「远端的一张表落到工作区的哪个名字上、列叫什么名字」，
 * 错了就是「数据拉进来了但查不到」或「SQL 注入的口子」——
 * 所以逐条钉住。
 */
class AnalysisPullTest {
    // ──────────────── 建表语句 ────────────────

    @Test
    fun `the staging table quotes the column names`() {
        // 列名是**用户起的**，可能带空格 —— 不引用就拼不成合法 SQL。
        assertEquals(
            """CREATE TABLE "staging_shop_orders" ("id" VARCHAR, "order no" VARCHAR)""",
            createStagingTableSql("staging_shop_orders", listOf("id", "order no")),
        )
    }

    @Test
    fun `a column name cannot break out of the identifier`() {
        // 列名来自远端的元数据，而远端的表是**别人**建的。
        // 一个叫 `a" , "b` 的列名若原样拼进去，就能多拼出一条语句。
        //
        // 断言写**整句**：引号成对这件事，看整句比数引号个数更清楚
        // （数引号要把「转义后的两个」算成一对，容易数错）。
        assertEquals(
            """CREATE TABLE "staging_x" ("a"" , ""b" VARCHAR)""",
            createStagingTableSql("staging_x", listOf("""a" , "b""")),
        )
    }

    @Test
    fun `every staging column is varchar`() {
        // 理由见 [STAGING_COLUMN_TYPE] 的 KDoc：CSV 把 NULL 写成空串，
        // 带类型的列遇到空串会**整行**导入失败。
        val sql = createStagingTableSql("staging_x", listOf("id", "amount"))
        assertTrue(sql.contains("\"id\" VARCHAR"), sql)
        assertTrue(sql.contains("\"amount\" VARCHAR"), sql)
        assertFalse(sql.contains("INTEGER"), "staging 列一律 VARCHAR：$sql")
        assertFalse(sql.contains("DOUBLE"), "staging 列一律 VARCHAR：$sql")
    }

    @Test
    fun `the drop statement tolerates a missing table`() {
        // 重新拉一张拉过的表时要先删旧的 —— 而第一次拉时它还不存在。
        assertEquals("""DROP TABLE IF EXISTS "staging_x"""", dropStagingTableSql("staging_x"))
    }

    // ──────────────── 导出的 SELECT ────────────────

    @Test
    fun `the select statement uses the dialect's own quoting`() {
        // MySQL 的反引号与 PG 的双引号不能互换：MySQL 里双引号是**字符串字面量**。
        assertEquals(
            "SELECT * FROM `shop`.`orders`",
            pullSelectSql(DialectType.MYSQL, "shop", "orders"),
        )
        assertEquals(
            """SELECT * FROM "sales"."orders"""",
            pullSelectSql(DialectType.POSTGRESQL, "sales", "orders"),
        )
    }

    @Test
    fun `the select statement falls back to the bare table name`() {
        // 命名空间为空时不能拼出「`.`表」」这种空段。
        assertEquals(
            "SELECT * FROM `orders`",
            pullSelectSql(DialectType.MYSQL, "", "orders"),
        )
    }

    @Test
    fun `a table name with a quote cannot break out of the identifier`() {
        val sql = pullSelectSql(DialectType.MYSQL, "shop", "o`ders")
        assertEquals("SELECT * FROM `shop`.`o``ders`", sql)
    }

    // ──────────────── 三段的状态 ────────────────

    @Test
    fun `the stages before the current one are done and the ones after are pending`() {
        // 三段严格有序（导出 → CSV → 导入）。「已完成 / 进行中 / 待执行」
        // 是界面上唯一要画出来的信息 —— 判错了，用户就会以为拉取卡在别的地方。
        assertEquals(PullStageState.DONE, pullStageState(PullStage.EXPORT, PullStage.CSV))
        assertEquals(PullStageState.CURRENT, pullStageState(PullStage.CSV, PullStage.CSV))
        assertEquals(PullStageState.PENDING, pullStageState(PullStage.IMPORT, PullStage.CSV))
    }

    @Test
    fun `at the very beginning only the first stage is current`() {
        // 拉取刚起来时只有一段在动，后两段都还没开始 ——
        // 把没开始的段画成「进行中」会让用户以为跑了三遍。
        assertEquals(PullStageState.CURRENT, pullStageState(PullStage.EXPORT, PullStage.EXPORT))
        assertEquals(PullStageState.PENDING, pullStageState(PullStage.CSV, PullStage.EXPORT))
        assertEquals(PullStageState.PENDING, pullStageState(PullStage.IMPORT, PullStage.EXPORT))
    }

    @Test
    fun `at the very end the first two stages are done`() {
        assertEquals(PullStageState.DONE, pullStageState(PullStage.EXPORT, PullStage.IMPORT))
        assertEquals(PullStageState.DONE, pullStageState(PullStage.CSV, PullStage.IMPORT))
        assertEquals(PullStageState.CURRENT, pullStageState(PullStage.IMPORT, PullStage.IMPORT))
    }

    @Test
    fun `every stage has its own caption key`() {
        // 复制粘贴最容易出的错：三段都指向同一个键 ——
        // 界面上于是三段显示同一句话，而截图上看不出来。
        val keys = PullStage.entries.map { it.labelKey() }
        assertEquals(keys.size, keys.distinct().size, "三段必须各有各的文案键：$keys")
    }
}
