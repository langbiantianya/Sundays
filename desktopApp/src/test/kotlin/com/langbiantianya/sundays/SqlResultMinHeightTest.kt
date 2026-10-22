/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.langbiantianya.client.EngineClient
import com.langbiantianya.sundays.connection.DialectMetadata
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.table.TABLE_BODY_TAG
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertTrue

/**
 * SQL 工作台的结果区在小窗口下**必须还能看到数据**。
 *
 * ## 缺陷（真机实测）
 *
 * 结果区与编辑器原本是 60% / 40% 的**纯比例**分配。窗口一小，结果区被压到
 * 130dp 出头 —— 而它内部要塞「查询结果 · N 行」+ 表头 + 数据区 + 横向滚动条 +
 * 分页栏，于是**数据区被挤成 0 高度**：
 *
 * - 标题写着「查询结果 · 2 行」；
 * - 分页栏好好地在那儿；
 * - **一行数据都没有**。
 *
 * 用户看到的是「查出来了但没结果」，而把窗口拉高数据就出来了 —— 典型的
 * 「数据在、界面不给看」。
 *
 * ## 修法
 *
 * 结果区给一个**最小高度**（够放下表头 + 一行数据 + 分页栏），多出来的高度归
 * 编辑器（它内部能滚，结果区不能 —— 数据看不见就等于白查）。
 */
@OptIn(ExperimentalTestApi::class)
class SqlResultMinHeightTest {
    /** 不做任何事的假引擎 —— 本用例只测布局，不碰数据库。 */
    private object NoopEngine : EngineClient {
        override fun handle(request: com.langbiantianya.grpc.Request) = kotlinx.coroutines.flow.flow<com.langbiantianya.grpc.Response> {}

        override suspend fun invoke(
            connection: com.langbiantianya.grpc.ConnectionConfig,
            configure: com.langbiantianya.grpc.RequestKt.Dsl.() -> Unit,
        ): com.langbiantianya.grpc.Response = error("本用例不走引擎")

        override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = error("本用例不测连接")

        override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = false

        override fun close() = Unit
    }

    @Test
    fun `窗口很小时结果表仍要有数据行的高度`() =
        runComposeUiTest {
            val state = DatabaseBrowserState(NoopEngine, CoroutineScope(Dispatchers.Default))
            val sheet = state.sqlSheets.first()
            sheet.columns = listOf(TableColumn(key = "id", header = "id"))
            sheet.rows = listOf(TableRow(id = 1, cells = mapOf("id" to 1L)))

            setContent {
                ProvideTestStrings {
                    MaterialTheme {
                        // 320dp 高：正好落进「结果区被压没数据」的那一档
                        Box(Modifier.size(760.dp, 320.dp)) {
                            SqlWorkbenchPane(
                                state = state,
                                connected = true,
                                schema = "smoke",
                                dialect = DialectMetadata.of(DialectType.MYSQL).sqlDialectProfile,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val body = onNodeWithTag(TABLE_BODY_TAG).fetchSemanticsNode()
            assertTrue(
                body.boundsInRoot.height > 0f,
                "结果表的数据区高度为 0 —— 标题写着「查询结果 · N 行」却一行数据都没有，" +
                    "实测高度 ${body.boundsInRoot.height}",
            )
        }
}
