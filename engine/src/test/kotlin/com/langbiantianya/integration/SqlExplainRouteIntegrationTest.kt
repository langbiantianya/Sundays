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
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.SqlRequest
import com.langbiantianya.grpc.sqlExplainRequest
import com.langbiantianya.testutil.H2Fixture
import com.langbiantianya.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL.EXPLAIN dispatcher 路由测试（v2.6 新增）。
 *
 * 历史上 SQL.EXPLAIN 在 RequestDispatcher.handleSql 中未被路由（仅 SqlEngineHandler.explain 实现）。
 * 表驱动重构后，必须通过 dispatcher 验证端到端可达。
 */
class SqlExplainRouteIntegrationTest : H2Fixture() {
    private fun explainRequest(sql: String): Request =
        Request
            .newBuilder()
            .setId(TestIds.next("r-explain"))
            .setCategory(Category.SQL)
            .setAction(Action.EXPLAIN)
            .setConnection(config)
            .setSqlRequest(
                SqlRequest
                    .newBuilder()
                    .setExplain(sqlExplainRequest { this.sql = sql })
                    .build(),
            ).build()

    @Test
    fun `SQL EXPLAIN is routed via dispatcher and returns non-blank rows`() =
        runBlocking {
            executeUpdate("CREATE TABLE ${TestIds.nextSql("t_explain")} (id INT, name VARCHAR(64))")
            val resp =
                RequestDispatcher
                    .dispatch(
                        explainRequest("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'T_EXPLAIN_%'"),
                    ).toList()

            val r = resp.single()
            assertTrue(r.success, "EXPLAIN must succeed via dispatcher: ${r.error}")
            assertTrue(r.hasSql())
            assertTrue(r.sql.hasExplain())
            // rows 字段是 google.protobuf.Value 列表，non-empty 即视为有解释计划
            assertTrue(r.sql.explain.rowsCount >= 1, "expected at least 1 EXPLAIN row")
        }

    @Test
    fun `SQL EXPLAIN fails gracefully on missing SQL`() =
        runBlocking {
            val resp =
                RequestDispatcher
                    .dispatch(
                        explainRequest(""),
                    ).toList()
            val r = resp.single()
            // empty sql → IllegalArgumentException → dispatcher wraps as error frame
            assertEquals(false, r.success)
            assertTrue(r.error.isNotEmpty())
        }
}
