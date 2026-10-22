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
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.Response
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.dataCreateRequest
import com.langbiantianya.grpc.dataRequest
import com.langbiantianya.grpc.importRequest
import com.langbiantianya.grpc.importRunRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.schemaRequest
import com.langbiantianya.grpc.sqlExecuteRequest
import com.langbiantianya.grpc.sqlRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.LanguageScope
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L4 只读闸门的**会话级**回归测试（主设计 §3.6 的第 2 类）。
 *
 * ## 它守的是什么
 *
 * 客户端在请求的连接配置上带 `read_only = true`（分析模式连远端时就会带），
 * 引擎必须**在进入 handler 之前**拒绝一切写路由。
 *
 * ## 断言落在哪
 *
 * **落在「数据库里发生了什么」上**，不落在「错误文案长什么样」上：
 * 写路由被拒之后，表没被建出来、行没被插进去。
 * 只断言错误文案的测试，在「handler 进去了、只是最后抛了异常」的实现下**照样绿**。
 */
class ReadOnlySessionGateTest : H2Fixture() {
    @BeforeEach
    fun fixLanguage() {
        // 固定语言：引擎的错误文案默认跟着**系统区域设置**走，
        // 而错误文案是要**原样上屏**的 —— 测试断言它，就得先把语言钉住。
        LanguageScope.setProcessLanguage(AppLanguage.ZH_HANS)
    }

    @Test
    fun `read only session rejects table create and the table is never created`() =
        runBlocking {
            val response = dispatchOne(tableCreateReq(readOnly = true, tableName = "t_blocked"))

            assertFalse(response.success, "只读会话不该让建表成功")
            assertTrue(
                response.error.contains("只读"),
                "错误必须明确说是只读拦截，实际是：${response.error}",
            )
            assertFalse(
                tableExists("t_blocked"),
                "表不该被建出来 —— 说明 handler 真的进去了",
            )
        }

    @Test
    fun `read only session rejects data insert and the row is never written`() =
        runBlocking {
            executeUpdate("CREATE TABLE t_rows (id INT)")
            val before = countRows("t_rows")

            val response = dispatchOne(dataInsertReq(readOnly = true, table = "t_rows"))

            assertFalse(response.success, "只读会话不该让插数据成功")
            assertTrue(response.error.contains("只读"), "实际错误：${response.error}")
            assertTrue(
                countRows("t_rows") == before,
                "行数不该变 —— 变了就说明 handler 真的进去了",
            )
        }

    @Test
    fun `read only session rejects import`() =
        runBlocking {
            executeUpdate("CREATE TABLE t_import (id INT)")

            val response = dispatchOne(importReq(readOnly = true, table = "t_import"))

            assertFalse(response.success, "只读会话不该让导入成功")
            assertTrue(response.error.contains("只读"), "实际错误：${response.error}")
            assertFalse(tableExists("t_import_target"), "导入不该建出目标表")
        }

    @Test
    fun `read only session rejects sql execute including a plain insert`() =
        runBlocking {
            executeUpdate("CREATE TABLE t_sql (id INT)")

            val response = dispatchOne(sqlReq(readOnly = true, sql = "INSERT INTO t_sql (id) VALUES (1)"))

            assertFalse(response.success, "只读会话不该让 SQL 写入成功")
            assertTrue(response.error.contains("只读"), "实际错误：${response.error}")
            assertTrue(countRows("t_sql") == 0L, "一行都不该插进去")
        }

    @Test
    fun `the very same insert succeeds in a normal session`() =
        runBlocking {
            // 这一条是闸门的**另一半**：证明它只挡只读会话，没把功能打死。
            // 没有它，上面几条可能只是「引擎坏了」而不是「闸门在工作」。
            executeUpdate("CREATE TABLE t_ok (id INT)")

            val response = dispatchOne(sqlReq(readOnly = false, sql = "INSERT INTO t_ok (id) VALUES (1)"))

            assertTrue(response.success, "普通会话里同一个 INSERT 必须照常成功：${response.error}")
            assertTrue(countRows("t_ok") == 1L, "行数应当是 1")
        }

    @Test
    fun `the gate short circuits before the handler is entered`() =
        runBlocking {
            // 判据：**换一个连不上的连接配置**。
            //
            // 若 handler 真被调进去了，它第一件事就是建连接池 —— 那必然得到一个
            // 连接错误（UnknownHost / Connection refused）。而我们拿到的是**只读错误**，
            // 于是「handler 根本没被调用」这件事就被证出来了。
            //
            // 为什么不直接用「假 handler 计数」：`RequestDispatcher.routes` 是
            // 构造期建好的私有表，注入假 handler 要改引擎的装配方式 ——
            // 代价远大于这条判据的收益，而两者证明的是同一件事。
            val unreachable =
                config
                    .toBuilder()
                    .setHost("no-such-host.invalid")
                    .setPort(1)
                    .build()

            val blocked = dispatchOne(tableCreateReq(readOnly = true, tableName = "t_x", conn = unreachable))
            assertTrue(blocked.error.contains("只读"), "只读会话里应当是只读错误，实际：${blocked.error}")

            val notBlocked = dispatchOne(tableCreateReq(readOnly = false, tableName = "t_x", conn = unreachable))
            assertFalse(
                notBlocked.error.contains("只读"),
                "普通会话不该被只读闸门拦下 —— 它应该去连那个不存在的host然后失败。实际：${notBlocked.error}",
            )
        }

    @Test
    fun `read routes still work in a read only session`() =
        runBlocking {
            executeUpdate("CREATE TABLE t_read (id INT)")

            val response = dispatchOne(schemaListReq(readOnly = true))

            assertTrue(response.success, "只读会话里读路由必须照常工作：${response.error}")
        }

    // ──────────────── 请求构造 ────────────────

    private fun tableCreateReq(
        readOnly: Boolean,
        tableName: String,
        conn: ConnectionConfig = config,
    ): Request =
        request {
            id = "gate-table-create"
            category = Category.TABLE
            action = Action.CREATE
            connection = conn.toBuilder().setReadOnly(readOnly).build()
            tableRequest =
                tableRequest {
                    create =
                        tableCreateRequest {
                            this.tableName = tableName
                            columns +=
                                columnDef {
                                    name = "id"
                                    type = "INT"
                                }
                        }
                }
        }

    private fun dataInsertReq(
        readOnly: Boolean,
        table: String,
    ): Request =
        request {
            id = "gate-data-create"
            category = Category.DATA
            action = Action.CREATE
            connection = config.toBuilder().setReadOnly(readOnly).build()
            dataRequest =
                dataRequest {
                    create =
                        dataCreateRequest {
                            tableName = table
                            values.put("id", "1")
                        }
                }
        }

    private fun importReq(
        readOnly: Boolean,
        table: String,
    ): Request =
        request {
            id = "gate-import"
            category = Category.IMPORT
            action = Action.RUN_IMPORT
            connection = config.toBuilder().setReadOnly(readOnly).build()
            importRequest =
                importRequest {
                    runImport =
                        importRunRequest {
                            filePath = "no-such-file.csv"
                            format = "CSV"
                            tableName = "${table}_target"
                        }
                }
        }

    private fun sqlReq(
        readOnly: Boolean,
        sql: String,
    ): Request =
        request {
            id = "gate-sql"
            category = Category.SQL
            action = Action.EXECUTE
            connection = config.toBuilder().setReadOnly(readOnly).build()
            sqlRequest = sqlRequest { execute = sqlExecuteRequest { this.sql = sql } }
        }

    private fun schemaListReq(readOnly: Boolean): Request =
        request {
            id = "gate-schema-list"
            category = Category.SCHEMA
            action = Action.LIST
            connection = config.toBuilder().setReadOnly(readOnly).build()
            schemaRequest = schemaRequest { list = com.langbiantianya.grpc.schemaListRequest { level = "database" } }
        }

    // ──────────────── 辅助 ────────────────

    private suspend fun dispatchOne(req: Request): Response = RequestDispatcher.dispatch(req).toList().last()

    private fun countRows(table: String): Long =
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
}
