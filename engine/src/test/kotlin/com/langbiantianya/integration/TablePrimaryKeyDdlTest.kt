/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.ColumnDef
import com.langbiantianya.grpc.TableCreateRequest
import com.langbiantianya.handlers.TableHandler
import com.langbiantianya.testutil.DuckDBFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 建表语句里**主键写在哪** —— 回归用例。
 *
 * ## 这个用例在防什么
 *
 * `TABLE.CREATE` 由两部分拼成：每列的列定义 + 可选的**表级** `PRIMARY KEY (cols)`。
 * 两者只能留一个，留两个会得到 "more than one primary key"。
 *
 * 早先判断「要不要加表级主键」的依据是「**有没有自增主键列**」——
 * 于是把 DuckDB 与 MySQL 的主键一并弄丢了：
 *
 * - DuckDB 的自增主键列定义是 `BIGINT DEFAULT nextval('seq_id')`，
 *   **主键不在列定义里**，必须靠表级 `PRIMARY KEY (id)` 才成立。
 *   丢了它，DuckDB 照样把表建出来 —— 只是**这张表没有主键**，
 *   而界面上还显示着「主键」二字。
 * - MySQL 直接建不出表（`there can be only one auto column and it must be defined as a key`）。
 *
 * 所以判据改成问方言自己「主键写在哪」（`DatabaseDialect.emitsInlinePrimaryKey`），
 * 而本类把「**主键真的存在**」钉成断言 —— 只断言「表建出来了」是抓不住这个缺陷的
 * （DuckDB 建得出来的只是一张没主键的表）。
 */
class TablePrimaryKeyDdlTest : DuckDBFixture() {
    /**
     * DuckDB 的自增主键表**必须带表级 PRIMARY KEY**。
     *
     * 判据查 `information_schema.table_constraints` —— 那是 DuckDB 自己对
     * 「这张表有哪些约束」的回答，与列定义无关。
     */
    @Test
    fun `DuckDB 建自增主键表时必须真的有 PRIMARY KEY`() =
        runBlocking {
            TableHandler.create(
                config,
                TableCreateRequest
                    .newBuilder()
                    .setTableName("products")
                    .addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("id")
                            .setType("BIGINT")
                            .setNullable(false)
                            .setIsPrimaryKey(true)
                            .setAutoIncrement(true)
                            .build(),
                    ).addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("name")
                            .setType("VARCHAR")
                            .setSize(100)
                            .setNullable(false)
                            .build(),
                    ).build(),
            )

            assertTrue(tableExists("products"), "表应当建出来")
            // ⚠️ 不能只看**第一行**：DuckDB 把 `NOT NULL` 也记成一条 CHECK 约束，
            // `table_constraints` 里第一行往往就是它。只取一行会误判成「没有主键」。
            val constraints =
                withConnection { conn ->
                    conn.createStatement().use { stmt ->
                        stmt
                            .executeQuery(
                                "SELECT constraint_type FROM information_schema.table_constraints " +
                                    "WHERE table_name = 'products'",
                            ).use { rs ->
                                buildList {
                                    while (rs.next()) add(rs.getString(1))
                                }
                            }
                    }
                }
            assertTrue(
                constraints.any { it.equals("PRIMARY KEY", ignoreCase = true) },
                "products 应当有 PRIMARY KEY 约束，实际约束：$constraints",
            )
        }

    /**
     * 主键真能挡住重复值 —— 进一步证明那不是「一个查得到但没生效的装饰」。
     */
    @Test
    fun `DuckDB 自增主键表拒收重复的主键值`() =
        runBlocking {
            TableHandler.create(
                config,
                TableCreateRequest
                    .newBuilder()
                    .setTableName("products")
                    .addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("id")
                            .setType("BIGINT")
                            .setNullable(false)
                            .setIsPrimaryKey(true)
                            .setAutoIncrement(true)
                            .build(),
                    ).build(),
            )
            executeUpdate("INSERT INTO main.products DEFAULT VALUES")
            val failed =
                runCatching { executeUpdate("INSERT INTO main.products (id) VALUES (1)") }.isFailure
            assertTrue(failed, "主键存在时，重复的 id 必须被拒")
        }

    /**
     * 自增序列照旧工作 —— 修主键不能把「不填 id 也能插」弄坏。
     */
    @Test
    fun `DuckDB 自增主键的序列照旧填值`() =
        runBlocking {
            TableHandler.create(
                config,
                TableCreateRequest
                    .newBuilder()
                    .setTableName("products")
                    .addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("id")
                            .setType("BIGINT")
                            .setNullable(false)
                            .setIsPrimaryKey(true)
                            .setAutoIncrement(true)
                            .build(),
                    ).addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("name")
                            .setType("VARCHAR")
                            .setSize(100)
                            .setNullable(false)
                            .build(),
                    ).build(),
            )
            executeUpdate("INSERT INTO main.products (name) VALUES ('Alice')")
            assertEquals("1", executeQuerySingle("SELECT id FROM main.products LIMIT 1"))
        }
}
