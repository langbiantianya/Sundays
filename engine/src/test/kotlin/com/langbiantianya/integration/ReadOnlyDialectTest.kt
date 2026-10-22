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
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.grpc.userCreateRequest
import com.langbiantianya.grpc.userRequest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.LanguageScope
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.testutil.H2Fixture
import com.langbiantianya.testutil.ReadOnlyDialect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L3 方言级只读的回归测试（主设计 §3.6 的第 1 类）。
 *
 * ## 它守的是什么
 *
 * **只读方言上根本不存在往它写的路径** —— 而且这条保证**与从哪个模式进来无关**：
 * 从浏览模式进来、或者会话上没带 `read_only` 标记，写操作照样被拒。
 *
 * ## 为什么这条测试不是可有可无的
 *
 * 方言 SPI 里大多数写方法有「默认抛异常」的实现，所以「只读方言不实现写方法」
 * 看起来是自动成立的。**但建表与写数据不走 SPI 的写方法** —— 引擎自己按方言
 * 给的 `buildColumnDefinition` / `quoteIdentifier` 拼出 DDL 与 DML 再走 JDBC。
 * 少了 [com.langbiantianya.handlers.requireWritable] 这道闸门，
 * TABLE.CREATE / DATA.* 在只读方言上**照样能跑通**。
 *
 * @see com.langbiantianya.handlers.ReadOnlyDialectGuard
 */
class ReadOnlyDialectTest : H2Fixture() {
    @BeforeEach
    fun registerReadOnlyDialect() {
        LanguageScope.setProcessLanguage(AppLanguage.ZH_HANS)
        DialectLoader.registerForTesting("ReadOnlyFake", ReadOnlyDialect())
    }

    /** 指向同一个 H2 内存库，但方言是只读的那个。 */
    private val readOnlyConfig: ConnectionConfig =
        config
            .toBuilder()
            .setDriver("ReadOnlyFake")
            .build()

    @Test
    fun `a read only dialect rejects table create even in a normal session`() =
        runBlocking {
            // 注意：会话上**没有** read_only 标记 —— 被拒的理由只能是方言本身只读。
            val response = dispatchOne(tableCreateReq(readOnlyConfig, "t_ro"))

            assertFalse(response.success, "只读方言上不该能建表")
            assertTrue(
                response.error.contains("只读"),
                "错误必须明确说是只读方言，实际：${response.error}",
            )
            assertFalse(tableExists("t_ro"), "表不该被建出来")
        }

    @Test
    fun `a read only dialect rejects data insert even in a normal session`() =
        runBlocking {
            executeUpdate("CREATE TABLE t_ro_rows (id INT)")
            val before = countRows("t_ro_rows")

            val response = dispatchOne(dataInsertReq(readOnlyConfig, "t_ro_rows"))

            assertFalse(response.success, "只读方言上不该能插数据")
            assertTrue(response.error.contains("只读"), "实际错误：${response.error}")
            assertTrue(countRows("t_ro_rows") == before, "行数不该变")
        }

    @Test
    fun `a read only dialect rejects user create`() =
        runBlocking {
            val response = dispatchOne(userCreateReq(readOnlyConfig, "u_ro"))

            assertFalse(response.success, "只读方言上不该能建用户")
            assertTrue(response.error.contains("只读"), "实际错误：${response.error}")
        }

    @Test
    fun `the same write still works on a normal dialect`() =
        runBlocking {
            // 闸门的**另一半**：证明它只挡只读方言，没把正常方言打死。
            val response = dispatchOne(tableCreateReq(config, "t_rw"))

            assertTrue(response.success, "普通方言上建表必须照常成功：${response.error}")
            assertTrue(tableExists("t_rw"), "表应当建出来")
        }

    @Test
    fun `the dialect level guard is independent of the session read only flag`() =
        runBlocking {
            // L3 与 L4 是**两道独立的防线**：
            //   L3 = 这个**方言**根本不该被写（与模式无关）
            //   L4 = 这个**会话**不许写
            // 两条都成立时，错误应该来自 L3 —— 因为它更靠前、更根本。
            val withFlag = dispatchOne(tableCreateReq(readOnlyConfig, "t_flag", readOnly = true))
            val withoutFlag = dispatchOne(tableCreateReq(readOnlyConfig, "t_flag", readOnly = false))

            assertFalse(withFlag.success, "带标记也该被拒")
            assertFalse(withoutFlag.success, "不带标记也该被拒 —— 方言只读与模式无关")
        }

    // ──────────────── 请求构造 ────────────────

    private fun tableCreateReq(
        conn: ConnectionConfig,
        tableName: String,
        readOnly: Boolean = false,
    ): Request =
        request {
            id = "dialect-table-create"
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
        conn: ConnectionConfig,
        table: String,
    ): Request =
        request {
            id = "dialect-data-create"
            category = Category.DATA
            action = Action.CREATE
            connection = conn
            dataRequest =
                dataRequest {
                    create =
                        dataCreateRequest {
                            tableName = table
                            values.put("id", "1")
                        }
                }
        }

    private fun userCreateReq(
        conn: ConnectionConfig,
        user: String,
    ): Request =
        request {
            id = "dialect-user-create"
            category = Category.USER
            action = Action.CREATE
            connection = conn
            userRequest =
                userRequest {
                    create =
                        userCreateRequest {
                            this.user = user
                            password = "secret"
                        }
                }
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
