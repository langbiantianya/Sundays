/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.DataCreateRequest
import com.langbiantianya.grpc.DataDeleteRequest
import com.langbiantianya.grpc.DataListRequest
import com.langbiantianya.grpc.DataUpdateRequest
import com.langbiantianya.handlers.DataHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataHandlerIntegrationTest : H2Fixture() {
    private fun seed() {
        executeUpdate("CREATE TABLE users (id INT AUTO_INCREMENT PRIMARY KEY, age INT NOT NULL, name VARCHAR(50))")
        for (i in 1..20) {
            executeUpdate("INSERT INTO users (age, name) VALUES (${i + 10}, 'name_$i')")
        }
    }

    @Test
    fun `LIST returns paginated data with total count`() =
        runBlocking<Unit> {
            seed()
            val result =
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("users")
                        .setPage(1)
                        .setPageSize(5)
                        .build(),
                )
            assertEquals(20L, result.total)
            assertEquals(5, result.rowsCount)
        }

    @Test
    fun `LIST with where clause filters rows`() =
        runBlocking<Unit> {
            seed()
            val result =
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("users")
                        .setPage(1)
                        .setPageSize(50)
                        .setWhere("age > 25")
                        .build(),
                )
            assertNotNull(result.total)
            assertEquals(5L, result.total)
        }

    @Test
    fun `LIST with orderBy sorts results`() =
        runBlocking<Unit> {
            seed()
            val result =
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("users")
                        .setPage(1)
                        .setPageSize(3)
                        .setOrderBy("age DESC")
                        .build(),
                )
            val rows = result.rowsList

            fun ageOf(row: com.langbiantianya.grpc.Row): Int? =
                row.valuesMap.entries
                    .firstOrNull { it.key.equals("age", ignoreCase = true) }
                    ?.value
                    ?.stringValue
                    ?.toInt()
            val firstAge = ageOf(rows[0])
            val thirdAge = ageOf(rows[2])
            assertNotNull(firstAge)
            assertNotNull(thirdAge)
            assertTrue(firstAge > thirdAge)
        }

    @Test
    fun `LIST with pageSize 0 streams rows via onRow callback`() =
        runBlocking<Unit> {
            seed()
            var count = 0
            DataHandler.list(
                config,
                DataListRequest
                    .newBuilder()
                    .setTableName("users")
                    .setPageSize(0)
                    .build(),
            ) { _ -> count++ }
            assertEquals(20, count)
        }

    @Test
    fun `LIST rejects WHERE with dangerous keyword`() =
        runBlocking<Unit> {
            seed()
            try {
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("users")
                        .setWhere("1=1 OR DROP TABLE x")
                        .build(),
                )
                error("应抛 IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("DROP"))
            }
        }

    @Test
    fun `CREATE inserts a row and returns affectedRows`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50), age INT)")
            val result =
                DataHandler.create(
                    config,
                    DataCreateRequest
                        .newBuilder()
                        .setTableName("t")
                        .putValues("name", "Alice")
                        .putValues("age", "30")
                        .build(),
                )
            assertEquals(1, result.affectedRows)
            assertEquals("Alice", executeQuerySingle("SELECT name FROM t LIMIT 1"))
        }

    @Test
    fun `UPDATE changes a row by where clause`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO t VALUES (1, 'old')")
            val result =
                DataHandler.update(
                    config,
                    DataUpdateRequest
                        .newBuilder()
                        .setTableName("t")
                        .putChanges("name", "new")
                        .putWhere("id", "1")
                        .build(),
                )
            assertEquals(1, result.affectedRows)
            assertEquals("new", executeQuerySingle("SELECT name FROM t WHERE id=1"))
        }

    @Test
    fun `DELETE removes rows by where clause`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
            val result =
                DataHandler.delete(
                    config,
                    DataDeleteRequest
                        .newBuilder()
                        .setTableName("t")
                        .putWhere("id", "1")
                        .build(),
                )
            assertEquals(1, result.affectedRows)
            assertEquals("1", executeQuerySingle("SELECT COUNT(*) FROM t"))
        }

    /**
     * 回归：文本与大对象列必须返回真实值。
     * H2 的 CLOB 类型名是 `CHARACTER LARGE OBJECT`，旧实现因为只按类型名匹配 LOB 集合
     * 而漏掉它，导致大文本列只能拿到占位符 —— 表格内编辑无从下手。
     */
    @Test
    fun `LIST returns real text values for VARCHAR and CLOB, masks BLOB`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE docs (id INT PRIMARY KEY, title VARCHAR(50), body CLOB, raw BLOB)")
            executeUpdate("INSERT INTO docs VALUES (1, '标题', '长文本正文内容', X'0102FF')")
            val result =
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("docs")
                        .setPage(1)
                        .setPageSize(10)
                        .build(),
                )
            val row = result.rowsList.first()
            // H2 把未加引号的标识符大写，所以列名查找必须忽略大小写

            fun cell(name: String): String? =
                row.valuesMap.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
                    ?.stringValue
            assertEquals("标题", cell("title"))
            assertEquals("长文本正文内容", cell("body"))
            // 二进制仍走占位符 —— 不能对 BLOB 调 getString
            assertEquals("[LOB Data]", cell("raw"))
        }

    @Test
    fun `INSERT binds LocalDate type for DATE column`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE events (id INT PRIMARY KEY, event_date DATE)")
            DataHandler.create(
                config,
                DataCreateRequest
                    .newBuilder()
                    .setTableName("events")
                    .putValues("id", "1")
                    .putValues("event_date", "2024-06-15")
                    .build(),
            )
            assertEquals("2024-06-15", executeQuerySingle("SELECT event_date FROM events"))
        }
}
