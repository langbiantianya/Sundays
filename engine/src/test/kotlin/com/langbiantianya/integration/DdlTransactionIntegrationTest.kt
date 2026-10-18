/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.dispatcher.RequestDispatcher
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ColumnDef
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.SystemRequest
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.foreignKeyCreateRequest
import com.langbiantianya.grpc.foreignKeyListRequest
import com.langbiantianya.grpc.foreignKeyRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.systemRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableDeleteRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.grpc.tableUpdateRequest
import com.langbiantianya.handlers.ForeignKeyHandler
import com.langbiantianya.pool.TransactionManager
import com.langbiantianya.testutil.H2Fixture
import com.langbiantianya.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DDL 事务会话 —— `TABLE.CREATE` / `UPDATE` / `DELETE` 与 `FOREIGN_KEY.CREATE` / `DELETE`
 * 接受 `session_id`，整批表结构变更因此能落在**同一条连接**上一次提交。
 *
 * ## 这里真正要钉住的是什么
 *
 * 不是「事务 API 存在」（那由 [TransactionIntegrationTest] 负责），而是**一条极容易
 * 静默失效的借用语义**：这些方法原先自己 `PoolManager.getConnection(config, schema)`
 * 借连接并在 `use { }` 里归还。一旦它们拿到会话连接还顺手 `close()`，
 * `close()` 会把连接还回 HikariCP，后续 DDL 落到**另一条**物理连接上 ——
 * 而界面上看不出任何异常，会话里还残留着一条已经不属于任何事务的连接。
 *
 * 所以每条用例的形状都是 **BEGIN → 若干条 DDL → COMMIT → 另借连接看结果**。
 * 另借连接是关键：在会话自己的连接上查，什么都证明不了。
 *
 * ## ⚠️ **不要**把这些用例改成断言「ROLLBACK 能撤掉 DDL」—— 实测撤不掉
 *
 * 本文件最初就是那么写的，6 条用例红在同一条断言上。直接 JDBC 的最小复现是：
 * `autoCommit = false` → `CREATE TABLE probe_t` → `rollback()` → 另借连接查，
 * **表还在**。H2 因此并不支持事务性 DDL，尽管 `ARCHITECTURE.md` 的能力矩阵
 * 给 H2 打了 `DDL_TRANSACTION ✓`。
 *
 * 这不是本次改动造成的，也不是 `sessionId` 透传失败 —— 数据写入在同一套机制下
 * 回滚得好好的（见 [TransactionIntegrationTest]）。能回滚 DDL 是**数据库自己的性质**：
 * PostgreSQL 支持，MySQL 对 DDL 隐式提交，DuckDB / SQLite 干脆不行。
 *
 * 于是本文件的定位变成两件事，都必须如实写出来：
 * 1. 整批 DDL 能一次提交、全部生效；
 * 2. **已知的边界** —— [H2 does not roll DDL back] 把这条边界钉成测试，
 *    免得日后有人基于「已经加了事务」的错觉去承诺原子性。
 */
class DdlTransactionIntegrationTest : H2Fixture() {
    private suspend fun dispatch(request: Request) = RequestDispatcher.dispatch(request).toList().single()

    private suspend fun begin() =
        dispatch(
            request {
                id = TestIds.next("ddl-begin")
                category = Category.SYSTEM
                action = Action.BEGIN
                connection = config
                systemRequest = SystemRequest.getDefaultInstance()
            },
        )

    private suspend fun endSession(
        action: Action,
        sessionId: String,
    ) = dispatch(
        request {
            id = TestIds.next("ddl-end")
            category = Category.SYSTEM
            this.action = action
            connection = config
            this.sessionId = sessionId
            systemRequest = systemRequest { this.sessionId = sessionId }
        },
    )

    private fun intCol(
        name: String,
        primaryKey: Boolean = false,
    ): ColumnDef =
        columnDef {
            this.name = name
            type = "INT"
            isPrimaryKey = primaryKey
        }

    private fun createTableReq(
        table: String,
        sessionId: String,
    ) = request {
        id = TestIds.next("ddl-create")
        category = Category.TABLE
        action = Action.CREATE
        connection = config
        this.sessionId = sessionId
        tableRequest =
            tableRequest {
                create =
                    tableCreateRequest {
                        tableName = table
                        columns += intCol("id", primaryKey = true)
                        columns += intCol("age")
                    }
            }
    }

    private fun addColumnReq(
        table: String,
        column: String,
        sessionId: String,
    ) = request {
        id = TestIds.next("ddl-addcol")
        category = Category.TABLE
        action = Action.UPDATE
        connection = config
        this.sessionId = sessionId
        tableRequest =
            tableRequest {
                update =
                    tableUpdateRequest {
                        tableName = table
                        operation = "ADD_COLUMN"
                        this.column =
                            columnDef {
                                name = column
                                type = "INT"
                            }
                    }
            }
    }

    private fun createFkReq(
        table: String,
        name: String,
        refColumn: String,
        sessionId: String,
    ) = request {
        id = TestIds.next("ddl-fk")
        category = Category.FOREIGN_KEY
        action = Action.CREATE
        connection = config
        this.sessionId = sessionId
        foreignKeyRequest =
            foreignKeyRequest {
                create =
                    foreignKeyCreateRequest {
                        tableName = table
                        fkName = name
                        columns += "id"
                        refTable = "ddl_parent"
                        refColumns += refColumn
                        onDelete = "CASCADE"
                    }
            }
    }

    /**
     * 断言外键真的落库 —— 走引擎自己的 `FOREIGN_KEY.LIST`，而不是查 H2 的元数据表。
     *
     * 理由有两个：查 `INFORMATION_SCHEMA.TABLE_CONSTRAINTS` 要自己对付 H2 把约束名
     * 大写化的规矩（实测踩过）；而用户点开「外键」看到的就是这份列表 ——
     * 断言它等于断言**界面能显示的东西**，比断言一个中间表名有意义。
     */
    private suspend fun fkExists(
        table: String,
        name: String,
    ): Boolean =
        ForeignKeyHandler
            .list(config, foreignKeyListRequest { tableName = table })
            .itemsList
            .any { it.name.equals(name, ignoreCase = true) }

    @Test
    fun `a table and its foreign keys commit as one batch`() =
        runBlocking {
            executeUpdate("CREATE TABLE ddl_parent (id INT PRIMARY KEY)")
            val sessionId = begin().system.begin.sessionId

            val created = dispatch(createTableReq("ddl_child", sessionId))
            assertTrue(created.success, "建表应当成功：${created.error}")

            val fk = dispatch(createFkReq("ddl_child", "fk_ok", "id", sessionId))
            assertTrue(fk.success, "合法外键应当成功：${fk.error}")

            val committed = endSession(Action.COMMIT, sessionId)
            assertTrue(committed.system.commit.committed, "COMMIT must report success")

            assertTrue(tableExists("ddl_child"), "子表必须落库")
            assertTrue(fkExists("ddl_child", "fk_ok"), "外键必须真的落库，否则界面上看不到它")
            assertEquals(0, TransactionManager.activeCount(), "会话结束后不得残留")
        }

    /**
     * 「一次保存表结构」最典型的形状：连着几条 `ALTER TABLE ... ADD COLUMN`。
     *
     * 断言的是**整串**都生效。只查最后一条的话，「前几条忘了带 sessionId」
     * 这种半对半错的实现照样能通过。
     */
    @Test
    fun `a chain of ALTER TABLE statements all take effect`() =
        runBlocking {
            executeUpdate("CREATE TABLE ddl_alter (id INT PRIMARY KEY)")
            val sessionId = begin().system.begin.sessionId

            listOf("a", "b", "c").forEach { col ->
                val added = dispatch(addColumnReq("ddl_alter", col, sessionId))
                assertTrue(added.success, "ADD COLUMN $col failed: ${added.error}")
            }
            assertEquals(
                "3",
                columnCount("ddl_alter", "('A','B','C')"),
                "会话连接上三列都该在",
            )

            endSession(Action.COMMIT, sessionId)

            assertEquals(
                "3",
                columnCount("ddl_alter", "('A','B','C')"),
                "三列必须**全部**生效 —— 漏一条就是半截表结构",
            )
            assertEquals(0, TransactionManager.activeCount())
        }

    @Test
    fun `DROP TABLE inside a session takes effect on commit`() =
        runBlocking {
            executeUpdate("CREATE TABLE ddl_drop (id INT PRIMARY KEY)")
            val sessionId = begin().system.begin.sessionId

            val dropped =
                dispatch(
                    request {
                        id = TestIds.next("ddl-drop")
                        category = Category.TABLE
                        action = Action.DELETE
                        connection = config
                        this.sessionId = sessionId
                        tableRequest =
                            tableRequest {
                                delete =
                                    tableDeleteRequest {
                                        tableName = "ddl_drop"
                                        ifExists = true
                                    }
                            }
                    },
                )
            assertTrue(dropped.success, "DROP TABLE failed: ${dropped.error}")
            endSession(Action.COMMIT, sessionId)

            assertEquals(false, tableExists("ddl_drop"), "提交后这张表必须消失")
            assertEquals(0, TransactionManager.activeCount())
        }

    /**
     * 已知的**边界**，如实钉住。
     *
     * 这条用例存在的唯一目的，是让「DDL 改动已经进了事务」这件事不再被误读成
     * 「DDL 改动可以回滚」。`ARCHITECTURE.md` 给 H2 的 `DDL_TRANSACTION` 打了 ✓，
     * 实测不成立；这里把事实写成断言，日后谁想改能力矩阵，先得解释这条为什么红。
     *
     * 注意它**不**是在断言实现有 bug：`sessionId` 透传是好的（见上面三条），
     * 能不能回滚 DDL 由数据库决定，H2 的答案就是不行。
     */
    @Test
    fun `H2 does not roll DDL back`() =
        runBlocking {
            val sessionId = begin().system.begin.sessionId
            val created = dispatch(createTableReq("ddl_norollback", sessionId))
            assertTrue(created.success, "建表应当成功：${created.error}")

            endSession(Action.ROLLBACK, sessionId)

            assertTrue(
                tableExists("ddl_norollback"),
                "H2 上 DDL 不参与回滚 —— 这条记录的是**已知限制**，不是期望行为；" +
                    "若哪天它变绿了，说明 H2 支持了事务性 DDL，能力矩阵与界面提示都该跟着改",
            )
            assertEquals(0, TransactionManager.activeCount(), "但会话本身必须正常结束、连接归还")
        }

    /** 数一张表里落在 [names] 内的列。 */
    private fun columnCount(
        table: String,
        names: String,
    ): String =
        executeQuerySingle(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_NAME = '${table.uppercase()}' AND COLUMN_NAME IN $names",
        )!!
}
