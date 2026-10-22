/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.ExportProgressFrame
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.Response
import com.langbiantianya.grpc.exportResponse
import com.langbiantianya.grpc.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import com.langbiantianya.grpc.ConnectionConfig as GrpcConnectionConfig

/**
 * 导出打到的**库**，必须是**被导出的那张表所在的库**。
 *
 * ## 缺陷（真机实测）
 *
 * 预览 `sundays_xdb.probe_shared_name` 能看见数据（一屏两行），点「导出」却报
 *
 * ```text
 * Table 'sundays_smoke.probe_shared_name' doesn't exist
 * ```
 *
 * 报错里那个库名恰好是「用户**没点**的那个」—— 极难自己联想到根因。
 *
 * ## 根因
 *
 * `exportQuery` 取的 catalog 是 [DatabaseBrowserState.currentSchema] —— 也就是
 * 「树里**第一个展开**的库」，而眼前这张表在**另一个**库里。两者不一致时，
 * 导出子进程连到的就是那个错的库。
 *
 * 手动把预填 SQL 改成 `SELECT * FROM sundays_xdb.probe_shared_name` 就能导出成功，
 * 说明数据与引擎都没问题，**只是打错了库**。
 *
 * ## 为什么这里只测「请求里带的 catalog」
 *
 * 真库那条链路（[CrossDatabaseQueryTest]）已经钉住「catalog 会被真的应用到会话上」。
 * 这里要钉的是**前端这一层**：请求里带的 catalog 到底是谁。
 * 用假引擎把 `Request.connection.database` 记下来就够了 ——
 * 那是引擎侧唯一能看到的「前端选了哪个库」。
 */
class ExportCatalogTargetingTest {
    /**
     * 记下每次导出请求里的 catalog 的假引擎。
     *
     * 只回一帧「完成帧」—— 导出要的是「引擎回了什么」，而这里要的是
     * 「前端**发出去**的请求里带的是什么」，两者互不相干。
     */
    private class CapturingEngine : EngineClient {
        val capturedDatabase = mutableListOf<String>()

        override fun handle(request: Request): Flow<Response> =
            flow {
                capturedDatabase += request.connection.database
                emit(
                    response {
                        id = request.id
                        success = true
                        stream = true
                        export =
                            exportResponse {
                                progress =
                                    ExportProgressFrame
                                        .newBuilder()
                                        .setExportedRows(1)
                                        .setCompleted(true)
                                        .setFilePath("out.csv")
                                        .build()
                            }
                    },
                )
            }

        override suspend fun invoke(
            connection: GrpcConnectionConfig,
            configure: com.langbiantianya.grpc.RequestKt.Dsl.() -> Unit,
        ): Response = error("本用例不走非流式路径")

        override suspend fun testConnection(config: GrpcConnectionConfig): com.langbiantianya.grpc.SystemTestConnectionResponse = error("本用例不测连接")

        override suspend fun disconnect(config: GrpcConnectionConfig): Boolean = false

        override fun close() = Unit
    }

    private fun newState(engine: EngineClient) = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))

    @Test
    fun `导出打到的库是被导出的那张表所在的库而不是树里第一个展开的库`() =
        runBlocking {
            val engine = CapturingEngine()
            val state = newState(engine)
            // 树里**先**展开的是另一个库（连接配置里那个）—— 这正是把用户带偏的那个库
            state.expandedDatabases.add("sundays_smoke")
            val out = Files.createTempDirectory("sundays-export-catalog").toFile()

            state.exportQuery(
                sql = "SELECT * FROM `sundays_xdb`.`probe_shared_name`",
                format = DatabaseBrowserState.ExportFormat.CSV,
                outputDir = out.absolutePath,
                fileName = "out.csv",
                tableName = "",
                catalog = "sundays_xdb",
            )

            assertEquals(
                listOf("sundays_xdb"),
                engine.capturedDatabase,
                "导出必须打到这张表所在的库 —— 打到「树里第一个展开的库」就是那个 " +
                    "`Table 'sundays_smoke.probe_shared_name' doesn't exist` 缺陷",
            )
        }

    @Test
    fun `不给 catalog 时退回树里当前展开的库`() =
        runBlocking {
            val engine = CapturingEngine()
            val state = newState(engine)
            state.expandedDatabases.add("sundays_smoke")
            val out = Files.createTempDirectory("sundays-export-catalog2").toFile()

            // SQL 工作台那条路没有「某一张表」，catalog 留空 → 退回 currentSchema()
            state.exportQuery(
                sql = "SELECT 1",
                format = DatabaseBrowserState.ExportFormat.CSV,
                outputDir = out.absolutePath,
                fileName = "out.csv",
                tableName = "",
            )

            assertEquals(
                listOf("sundays_smoke"),
                engine.capturedDatabase,
                "不给 catalog 时必须退回「树里当前展开的库」，行为与过去完全一致",
            )
        }
}
