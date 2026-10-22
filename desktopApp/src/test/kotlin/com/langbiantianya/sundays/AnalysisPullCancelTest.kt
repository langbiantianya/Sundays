/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.ExportProgressFrame
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.RequestKt
import com.langbiantianya.grpc.Response
import com.langbiantianya.grpc.analysisListColumnsResponse
import com.langbiantianya.grpc.analysisResponse
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.exportResponse
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.response
import com.langbiantianya.sundays.analysis.PullStage
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拉取被**取消**时，必须叫停还在跑的导出子进程。
 *
 * ## 这一条为什么值得单独一个测试类
 *
 * 导出跑在**另一个进程**里，它正开着那个临时 CSV。
 * Windows 上文件被占用时删不掉 —— 所以取消时必须**先叫停子进程**再删目录，
 * 否则磁盘上留下一个删不掉的孤儿目录（这正是最初的写法犯的错：
 * 导出请求 id 记在 `exportToCsv` 的**返回值**里，而取消发生在导出**途中**时，
 * 那个返回值根本不会来，于是「叫停」这一步被跳过）。
 *
 * ## 为什么用假引擎而不是真 MySQL
 *
 * 真跑一遍要靠「导出还没结束就取消」这个**时机** —— 那是竞态：
 * 表小一点，导出在取消到达之前就结束了，测出来的东西没有判别力
 * （实测：3 行的表在两种写法下都能把临时目录清干净）。
 *
 * 假引擎把导出钉成「**永远不结束**」，于是「取消发生在导出途中」这件事
 * 变成**确定**的 —— 断言才真的在判别「有没有叫停」。
 */
class AnalysisPullCancelTest {
    /**
     * 假引擎 —— 只实现拉取链路真正用到的那两条路。
     *
     * 导出那条路「**永不结束**」，模拟一个还在跑的导出子进程。
     */
    private class FakePullEngine : EngineClient {
        /** 导出请求发出去时带的 id（按发出的顺序）。 */
        val exportRequestIds = mutableListOf<String>()

        /** 「叫停导出」请求里带的 id。 */
        val stopExportIds = mutableListOf<String>()

        override fun handle(request: Request): Flow<Response> =
            flow {
                when (request.category) {
                    Category.EXPORT -> {
                        exportRequestIds += request.id
                        // 一帧「导出行数 1」—— 导出确实开跑了。
                        emit(
                            response {
                                id = request.id
                                success = true
                                stream = true
                                export =
                                    exportResponse {
                                        progress = ExportProgressFrame.newBuilder().setExportedRows(1).build()
                                    }
                            },
                        )
                        // 然后**永远不结束** —— 模拟「导出还在跑」。
                        awaitCancellation()
                    }

                    else -> {
                        error("本用例不走流式路径：${request.category}/${request.action}")
                    }
                }
            }

        override suspend fun invoke(
            connection: ConnectionConfig,
            configure: RequestKt.Dsl.() -> Unit,
        ): Response {
            val req =
                request {
                    id = "fake-stop"
                    this.connection = connection
                    configure()
                }
            return when (req.category) {
                Category.ANALYSIS -> {
                    response {
                        success = true
                        analysis =
                            analysisResponse {
                                listColumns = analysisListColumnsResponse { items += columnDef { name = "id" } }
                            }
                    }
                }

                Category.EXPORT -> {
                    stopExportIds += req.exportRequest.runExport.stopExportId
                    response { success = true }
                }

                else -> {
                    error("本用例不走这条路：${req.category}/${req.action}")
                }
            }
        }

        override suspend fun testConnection(config: ConnectionConfig) = error("本用例不测连接")

        override suspend fun disconnect(config: ConnectionConfig): Boolean = false

        override fun close() = Unit
    }

    private val sourceConfig =
        connectionConfig {
            driver = DialectType.MYSQL.engineDriverName
            jdbcUrl = "jdbc:mysql://127.0.0.1:3306/demo"
            user = "u"
            password = "p"
            database = "demo"
        }

    @Test
    fun `a cancelled pull stops the export subprocess by the export request id`() {
        val engine = FakePullEngine()
        val tempRoot = Files.createTempDirectory("sundays-pull-cancel")
        val puller = EngineAnalysisPuller(engine, tempRoot)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val job =
            scope.launch {
                runCatching {
                    puller.pull(
                        source = sourceConfig,
                        workspace = sourceConfig,
                        workspacePath = tempRoot.resolve("workspace.duckdb"),
                        dialect = DialectType.MYSQL,
                        namespace = "demo",
                        table = "orders",
                    ) { progress ->
                        // 只在**导出已经开跑**之后才取消 —— 那时导出请求已经发出去了，
                        // 正是「叫停子进程」该生效的时刻。
                        if (progress.stage == PullStage.EXPORT && progress.rows > 0) {
                            currentCoroutineContext().job.cancel()
                        }
                    }
                }
            }
        runBlocking { job.join() }
        scope.cancel()

        assertEquals(1, engine.exportRequestIds.size, "导出应当发过一次")
        assertEquals(
            listOf(engine.exportRequestIds.first()),
            engine.stopExportIds,
            "取消时必须按**导出请求的 id** 叫停子进程 —— " +
                "否则子进程继续开着那个 CSV，Windows 上删不掉，磁盘上留下孤儿目录",
        )
    }

    @Test
    fun `a cancelled pull still cleans up its temp directory`() {
        val engine = FakePullEngine()
        val tempRoot = Files.createTempDirectory("sundays-pull-cancel")
        val puller = EngineAnalysisPuller(engine, tempRoot)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val job =
            scope.launch {
                runCatching {
                    puller.pull(
                        source = sourceConfig,
                        workspace = sourceConfig,
                        workspacePath = tempRoot.resolve("workspace.duckdb"),
                        dialect = DialectType.MYSQL,
                        namespace = "demo",
                        table = "orders",
                    ) { progress ->
                        if (progress.stage == PullStage.EXPORT && progress.rows > 0) {
                            currentCoroutineContext().job.cancel()
                        }
                    }
                }
            }
        runBlocking { job.join() }
        scope.cancel()

        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "取消路径上也不能留下临时目录：$leftovers")
    }
}
