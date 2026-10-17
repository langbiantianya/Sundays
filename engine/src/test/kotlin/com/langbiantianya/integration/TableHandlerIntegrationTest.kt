/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.ColumnDef
import com.langbiantianya.grpc.TableColumnListRequest
import com.langbiantianya.grpc.TableCreateRequest
import com.langbiantianya.grpc.TableDeleteRequest
import com.langbiantianya.grpc.TableGetDdlRequest
import com.langbiantianya.grpc.TableListRequest
import com.langbiantianya.grpc.TableRenameRequest
import com.langbiantianya.grpc.TableTruncateRequest
import com.langbiantianya.grpc.TableUpdateRequest
import com.langbiantianya.grpc.tableColumnListRequest
import com.langbiantianya.grpc.tableDeleteRequest
import com.langbiantianya.grpc.tableGetDdlRequest
import com.langbiantianya.grpc.tableRenameRequest
import com.langbiantianya.grpc.tableTruncateRequest
import com.langbiantianya.handlers.TableHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableHandlerIntegrationTest : H2Fixture() {
    @Test
    fun `LIST returns tables in schema`() =
        runBlocking {
            executeUpdate("CREATE TABLE t1 (id INT)")
            executeUpdate("CREATE TABLE t2 (id INT)")
            val result = TableHandler.list(config, TableListRequest.getDefaultInstance())
            // H2 默认把表名存储为大写
            assertTrue(result.itemsList.any { it.name.equals("t1", ignoreCase = true) })
            assertTrue(result.itemsList.any { it.name.equals("t2", ignoreCase = true) })
        }

    @Test
    fun `LIST with tableName returns columns and PK info`() =
        runBlocking {
            executeUpdate("CREATE TABLE users (id INT NOT NULL PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, age INT)")
            val result =
                TableHandler.columnList(
                    config,
                    tableColumnListRequest { tableName = "users" },
                )
            assertEquals(3, result.itemsCount)
            val id = result.itemsList.first { it.name.equals("id", ignoreCase = true) }
            assertEquals(true, id.isPrimaryKey)
            assertEquals(false, id.nullable)
        }

    @Test
    fun `CREATE table with columns including autoIncrement PK`() =
        runBlocking {
            val payload =
                TableCreateRequest
                    .newBuilder()
                    .setTableName("products")
                    .addColumns(
                        ColumnDef
                            .newBuilder()
                            .setName("id")
                            .setType("INT")
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
                    ).build()
            TableHandler.create(config, payload)
            assertTrue(tableExists("products"))
        }

    @Test
    fun `UPDATE ADD_COLUMN adds new column`() =
        runBlocking {
            executeUpdate("CREATE TABLE T (id INT)")
            TableHandler.update(
                config,
                TableUpdateRequest
                    .newBuilder()
                    .setTableName("T")
                    .setOperation("ADD_COLUMN")
                    .setColumn(
                        ColumnDef
                            .newBuilder()
                            .setName("DESCRIPTION")
                            .setType("VARCHAR")
                            .setSize(255)
                            .setNullable(true)
                            .build(),
                    ).build(),
            )
            withConnection { conn ->
                conn.metaData.getColumns(null, "PUBLIC", "T", "DESCRIPTION").use { rs ->
                    assertTrue(rs.next(), "新列未创建")
                }
            }
        }

    @Test
    fun `UPDATE DROP_COLUMN removes column`() =
        runBlocking {
            executeUpdate("CREATE TABLE T (id INT, description VARCHAR(255))")
            TableHandler.update(
                config,
                TableUpdateRequest
                    .newBuilder()
                    .setTableName("T")
                    .setOperation("DROP_COLUMN")
                    .setColumnName("description")
                    .build(),
            )
            withConnection { conn ->
                conn.metaData.getColumns(null, "PUBLIC", "T", "DESCRIPTION").use { rs ->
                    assertFalse(rs.next(), "列仍存在")
                }
            }
        }

    @Test
    fun `UPDATE MODIFY_COLUMN changes column type and renames`() =
        runBlocking {
            executeUpdate("CREATE TABLE T (id INT, price INT)")
            // H2 单条 ALTER 只能改一个属性 — 分两步：先 rename，再改 type
            TableHandler.update(
                config,
                TableUpdateRequest
                    .newBuilder()
                    .setTableName("T")
                    .setOperation("MODIFY_COLUMN")
                    .setColumn(
                        ColumnDef
                            .newBuilder()
                            .setName("PRICE")
                            .setNewName("UNIT_PRICE")
                            .build(),
                    ).build(),
            )
            TableHandler.update(
                config,
                TableUpdateRequest
                    .newBuilder()
                    .setTableName("T")
                    .setOperation("MODIFY_COLUMN")
                    .setColumn(
                        ColumnDef
                            .newBuilder()
                            .setName("UNIT_PRICE")
                            .setType("DECIMAL")
                            .setSize(10)
                            .setNullable(false)
                            .build(),
                    ).build(),
            )
            withConnection { conn ->
                conn.metaData.getColumns(null, "PUBLIC", "T", "UNIT_PRICE").use { rs ->
                    assertTrue(rs.next())
                }
            }
        }

    @Test
    fun `GET_DDL returns CREATE TABLE DDL`() =
        runBlocking {
            executeUpdate("CREATE TABLE users (id INT NOT NULL PRIMARY KEY, name VARCHAR(50))")
            val result =
                TableHandler.getDDL(
                    config,
                    tableGetDdlRequest { tableName = "users" },
                )
            assertTrue(result.ddl.contains("CREATE TABLE", ignoreCase = true))
            assertTrue(result.ddl.contains("PRIMARY KEY", ignoreCase = true))
        }

    @Test
    fun `DELETE drops table`() =
        runBlocking {
            executeUpdate("CREATE TABLE t (id INT)")
            TableHandler.delete(
                config,
                tableDeleteRequest { tableName = "t" },
            )
            assertFalse(tableExists("t"))
        }

    @Test
    fun `RENAME renames table`() =
        runBlocking {
            executeUpdate("CREATE TABLE old_t (id INT)")
            TableHandler.rename(
                config,
                tableRenameRequest {
                    oldName = "old_t"
                    newName = "new_t"
                },
            )
            assertTrue(tableExists("new_t"))
            assertFalse(tableExists("old_t"))
        }

    @Test
    fun `TRUNCATE empties table`() =
        runBlocking {
            executeUpdate("CREATE TABLE t (id INT)")
            executeUpdate("INSERT INTO t VALUES (1), (2), (3)")
            TableHandler.truncate(
                config,
                tableTruncateRequest { tableName = "t" },
            )
            assertEquals("0", executeQuerySingle("SELECT COUNT(*) FROM t"))
        }
}
