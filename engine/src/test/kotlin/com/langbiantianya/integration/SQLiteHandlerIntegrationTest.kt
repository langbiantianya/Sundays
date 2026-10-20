/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.dataCreateRequest
import com.langbiantianya.grpc.dataListRequest
import com.langbiantianya.grpc.indexCreateRequest
import com.langbiantianya.grpc.indexDeleteRequest
import com.langbiantianya.grpc.indexListRequest
import com.langbiantianya.grpc.schemaListRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableListRequest
import com.langbiantianya.grpc.viewCreateRequest
import com.langbiantianya.grpc.viewDeleteRequest
import com.langbiantianya.grpc.viewListRequest
import com.langbiantianya.handlers.DataHandler
import com.langbiantianya.handlers.IndexHandler
import com.langbiantianya.handlers.SchemaHandler
import com.langbiantianya.handlers.SystemHandler
import com.langbiantianya.handlers.TableHandler
import com.langbiantianya.handlers.ViewHandler
import com.langbiantianya.testutil.SQLiteFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SQLite 端到端集成测试 — 走 PoolManager + handler，验证多 handler 串联工作。
 */
class SQLiteHandlerIntegrationTest : SQLiteFixture() {
    // ============ SCHEMA ============

    @Test
    fun `SCHEMA LIST returns in-memory placeholder for file-based driver`() =
        runBlocking {
            val res = SchemaHandler.list(config, schemaListRequest { level = "database" })
            assertEquals("database", res.level)
            assertTrue(res.itemsList.isNotEmpty(), "SQLite should report at least the file path")
        }

    // ============ TABLE ============

    @Test
    fun `TABLE CREATE with autoIncrement PK succeeds`() =
        runBlocking {
            val res =
                TableHandler.create(
                    config,
                    tableCreateRequest {
                        tableName = "products"
                        columns.add(
                            com.langbiantianya.grpc.columnDef {
                                name = "id"
                                type = "INT"
                                isPrimaryKey = true
                                autoIncrement = true
                            },
                        )
                        columns.add(
                            com.langbiantianya.grpc.columnDef {
                                name = "name"
                                type = "TEXT"
                                nullable = false
                            },
                        )
                    },
                )
            assertEquals("products", res.created)
            assertTrue(tableExists("products"))
        }

    /**
     * SQLite 的自增主键是**内联**在列定义里的（`INTEGER PRIMARY KEY AUTOINCREMENT`），
     * 建表时不能再追加表级 `PRIMARY KEY (id)` —— 两者同时出现会得到
     * "more than one primary key"。
     *
     * 这条用例守着「内联方言**不**加表级主键」这一侧；
     * 反过来的一侧（DuckDB / MySQL 必须加）见 `TablePrimaryKeyDdlTest`。
     */
    @Test
    fun `TABLE CREATE with autoIncrement PK really has a primary key and no duplicate constraint`() =
        runBlocking {
            TableHandler.create(
                config,
                tableCreateRequest {
                    tableName = "products"
                    columns.add(
                        com.langbiantianya.grpc.columnDef {
                            name = "id"
                            type = "INT"
                            nullable = false
                            isPrimaryKey = true
                            autoIncrement = true
                        },
                    )
                    columns.add(
                        com.langbiantianya.grpc.columnDef {
                            name = "name"
                            type = "TEXT"
                            nullable = false
                        },
                    )
                },
            )
            // `PRAGMA table_info` 的 `pk` 列给出该列在主键里的序号（非主键列是 0）
            val pkColumns =
                withConnection { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("PRAGMA table_info(products)").use { rs ->
                            buildList {
                                while (rs.next()) {
                                    if (rs.getInt("pk") > 0) add(rs.getString("name"))
                                }
                            }
                        }
                    }
                }
            assertEquals(listOf("id"), pkColumns, "products 的主键应当且仅当是 id")
        }

    @Test
    fun `TABLE LIST returns created tables`() =
        runBlocking {
            executeUpdate("CREATE TABLE a (id INTEGER PRIMARY KEY)")
            executeUpdate("CREATE TABLE b (id INTEGER PRIMARY KEY)")
            val res = TableHandler.list(config, tableListRequest {})
            val names = res.itemsList.map { it.name }
            assertTrue("a" in names)
            assertTrue("b" in names)
        }

    // ============ DATA ============

    @Test
    fun `DATA LIST returns inserted rows`() =
        runBlocking {
            TableHandler.create(
                config,
                tableCreateRequest {
                    tableName = "users"
                    columns.add(
                        com.langbiantianya.grpc.columnDef {
                            name = "id"
                            type = "INT"
                            isPrimaryKey = true
                            autoIncrement = true
                        },
                    )
                    columns.add(
                        com.langbiantianya.grpc.columnDef {
                            name = "name"
                            type = "TEXT"
                            nullable = false
                        },
                    )
                },
            )
            DataHandler.create(
                config,
                dataCreateRequest {
                    tableName = "users"
                    values.put("name", "Alice")
                },
            )
            DataHandler.create(
                config,
                dataCreateRequest {
                    tableName = "users"
                    values.put("name", "Bob")
                },
            )
            val res =
                DataHandler.list(
                    config,
                    dataListRequest {
                        tableName = "users"
                        page = 1
                        pageSize = 10
                    },
                )
            assertEquals(2, res.total)
            assertEquals(2, res.rowsCount)
        }

    /**
     * 回归（本缺陷的核心用例）：xerial JDBC 对 SQLite 每一个文本列的
     * `ResultSetMetaData.getColumnTypeName` 都返回 `"TEXT"`。旧实现把 `TEXT`
     * 放进 LOB_TYPES，于是浏览器里所有字符串列全部显示 `[LOB Data]`，真实值拿不到。
     */
    @Test
    fun `DATA LIST returns real TEXT values and masks BLOB`() =
        runBlocking {
            executeUpdate("CREATE TABLE files (id INTEGER PRIMARY KEY, label TEXT, payload BLOB)")
            executeUpdate("INSERT INTO files VALUES (1, 'hello sqlite', X'0102FF')")
            val res =
                DataHandler.list(
                    config,
                    dataListRequest {
                        tableName = "files"
                        page = 1
                        pageSize = 10
                    },
                )
            val row = res.rowsList.first()
            assertEquals("hello sqlite", row.valuesMap["label"]?.stringValue)
            assertEquals("[LOB Data]", row.valuesMap["payload"]?.stringValue)
        }

    // ============ VIEW ============

    @Test
    fun `VIEW CREATE then LIST returns it`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
            ViewHandler.create(
                config,
                viewCreateRequest {
                    name = "v_t"
                    definition = "SELECT id, name FROM t"
                },
            )
            val res = ViewHandler.list(config, viewListRequest {})
            assertEquals(1, res.itemsList.size)
            assertEquals("v_t", res.itemsList[0].name)
            ViewHandler.delete(
                config,
                viewDeleteRequest {
                    name = "v_t"
                    ifExists = true
                },
            )
        }

    // ============ INDEX ============

    @Test
    fun `INDEX CREATE then LIST returns it`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
            IndexHandler.create(
                config,
                indexCreateRequest {
                    tableName = "t"
                    indexName = "idx_t_email"
                    columns.add("email")
                    unique = false
                },
            )
            val res = IndexHandler.list(config, indexListRequest { tableName = "t" })
            assertEquals(1, res.itemsList.size)
            assertEquals("idx_t_email", res.itemsList[0].name)
            IndexHandler.delete(
                config,
                indexDeleteRequest {
                    tableName = "t"
                    indexName = "idx_t_email"
                    ifExists = true
                },
            )
        }

    // ============ SYSTEM ============

    @Test
    fun `SYSTEM SERVER_INFO returns SQLite product`() =
        runBlocking {
            val res = SystemHandler.serverInfo(config)
            assertNotNull(res.version)
            assertTrue(res.version.isNotEmpty())
            val extras = res.extras
            assertTrue(
                extras.structValue.fieldsMap["product"]
                    ?.stringValue
                    ?.contains("SQLite") == true,
            )
            assertEquals("embedded", res.mode)
        }
}
