/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.DataCreateRequest
import com.langbiantianya.grpc.DataUpdateRequest
import com.langbiantianya.handlers.DataHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `null_columns` —— 显式声明「这一列写 SQL NULL」。
 *
 * 断言一律用 [java.sql.ResultSet.getObject] / `wasNull()`，**不用 `getString`**：
 * H2 对 NULL 列的 `getString` 可能回字面量 `"NULL"`，用它判会把「真的写成了 NULL」
 * 与「写进了四个字母 N-U-L-L」混为一谈 —— 正好是本次要防的那个错。
 */
class DataNullColumnsIntegrationTest : H2Fixture() {
    /** 单个单元格的真实状态 —— 值 + SQL NULL 标志，两者一起才能区分 `''` 与 NULL。 */
    private data class Cell(
        val value: Any?,
        val wasNull: Boolean,
    )

    private fun cellOf(sql: String): Cell =
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    assertTrue(rs.next(), "查询必须至少返回一行：$sql")
                    val value = rs.getObject(1)
                    Cell(value, rs.wasNull())
                }
            }
        }

    // ---------- DATA.UPDATE ----------

    @Test
    fun `UPDATE 的 null_columns 写进去的是真 NULL`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO t VALUES (1, 'old')")

            DataHandler.update(
                config,
                DataUpdateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putChanges("name", "ignored")
                    .addNullColumns("name")
                    .putWhere("id", "1")
                    .build(),
            )

            val cell = cellOf("SELECT name FROM t WHERE id = 1")
            assertTrue(cell.wasNull, "null_columns 声明的列必须是 SQL NULL")
            assertNull(cell.value)
        }

    @Test
    fun `UPDATE 同一条请求里置 NULL 的列与写普通值的列都正确`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50), age INT)")
            executeUpdate("INSERT INTO t VALUES (1, 'old', 1)")

            DataHandler.update(
                config,
                DataUpdateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putChanges("name", "new")
                    .addNullColumns("age")
                    .putWhere("id", "1")
                    .build(),
            )

            val name = cellOf("SELECT name FROM t WHERE id = 1")
            assertEquals("new", name.value)
            assertTrue(!name.wasNull)
            val age = cellOf("SELECT age FROM t WHERE id = 1")
            assertTrue(age.wasNull, "null_columns 声明的列必须是 SQL NULL")
            assertNull(age.value)
        }

    @Test
    fun `UPDATE 只出现在 null_columns 里、不在 changes 里的列也会被写入`() =
        runBlocking<Unit> {
            // 列集合 = changes.keys ∪ null_columns。若只按 changes 拼 SET 清单，
            // 这一列根本不在 SQL 里 —— 请求「成功」了却什么都没改，是最难查的静默失败
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50), age INT)")
            executeUpdate("INSERT INTO t VALUES (1, 'keep', 30)")

            DataHandler.update(
                config,
                DataUpdateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putChanges("name", "touched")
                    .addNullColumns("age")
                    .putWhere("id", "1")
                    .build(),
            )

            assertEquals("touched", cellOf("SELECT name FROM t WHERE id = 1").value)
            val age = cellOf("SELECT age FROM t WHERE id = 1")
            assertTrue(age.wasNull, "只出现在 null_columns 的列也必须被写成 NULL")
            assertNull(age.value)
        }

    @Test
    fun `UPDATE 没在 null_columns 里的空字符串写成空串而不是 NULL`() =
        runBlocking<Unit> {
            // 本次改动的**核心约定**：清空输入框 = 写 ''，写 NULL 必须显式声明。
            // 两者在查询、导出、唯一索引上行为都不同，猜错会静默改坏数据
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO t VALUES (1, 'old')")

            DataHandler.update(
                config,
                DataUpdateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putChanges("name", "")
                    .putWhere("id", "1")
                    .build(),
            )

            val cell = cellOf("SELECT name FROM t WHERE id = 1")
            assertEquals("", cell.value, "空字符串必须写成空串")
            assertTrue(!cell.wasNull, "空字符串绝不能被当成 NULL")
        }

    // ---------- DATA.CREATE ----------

    @Test
    fun `CREATE 的 null_columns 写进去的是真 NULL`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50), age INT)")

            DataHandler.create(
                config,
                DataCreateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putValues("id", "1")
                    .putValues("name", "ignored")
                    .addNullColumns("name")
                    .putValues("age", "7")
                    .build(),
            )

            val name = cellOf("SELECT name FROM t WHERE id = 1")
            assertTrue(name.wasNull, "null_columns 声明的列必须是 SQL NULL")
            assertNull(name.value)
            assertEquals(7, cellOf("SELECT age FROM t WHERE id = 1").value)
        }

    @Test
    fun `CREATE 同一条请求里置 NULL 的列与写普通值的列都正确`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50), age INT)")

            DataHandler.create(
                config,
                DataCreateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putValues("id", "1")
                    .putValues("name", "Alice")
                    .addNullColumns("age")
                    .build(),
            )

            assertEquals("Alice", cellOf("SELECT name FROM t WHERE id = 1").value)
            val age = cellOf("SELECT age FROM t WHERE id = 1")
            assertTrue(age.wasNull, "null_columns 声明的列必须是 SQL NULL")
            assertNull(age.value)
        }

    @Test
    fun `CREATE 只出现在 null_columns 里、不在 values 里的列也会被写入`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50), age INT)")

            DataHandler.create(
                config,
                DataCreateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putValues("id", "1")
                    .putValues("name", "Alice")
                    .addNullColumns("age")
                    .build(),
            )

            val age = cellOf("SELECT age FROM t WHERE id = 1")
            assertTrue(age.wasNull, "只出现在 null_columns 的列也必须被写成 NULL")
            assertNull(age.value)
        }

    @Test
    fun `CREATE 没在 null_columns 里的空字符串写成空串而不是 NULL`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")

            DataHandler.create(
                config,
                DataCreateRequest
                    .newBuilder()
                    .setTableName("t")
                    .putValues("id", "1")
                    .putValues("name", "")
                    .build(),
            )

            val cell = cellOf("SELECT name FROM t WHERE id = 1")
            assertEquals("", cell.value, "空字符串必须写成空串")
            assertTrue(!cell.wasNull, "空字符串绝不能被当成 NULL")
        }
}
