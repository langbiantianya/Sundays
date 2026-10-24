/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.AnalysisAccessMode
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.analysisPullRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.systemRequest
import com.langbiantianya.sundays.analysis.AnalysisPullCancelledException
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.analysis.stagingTableName
import kotlinx.coroutines.flow.collect
import java.nio.file.Path

/**
 * 拉取链路的**真实现** —— 一条 `ANALYSIS.PULL_TABLE` 路由。
 *
 * 形状与为什么没有 CSV 中转，见 [AnalysisPuller] 的 KDoc。这里只讲实现细节。
 */
class EngineAnalysisPuller(
    private val engine: EngineClient,
) : AnalysisPuller {
    override suspend fun pull(
        requestId: String,
        source: ConnectionConfig,
        workspace: ConnectionConfig,
        workspacePath: Path,
        namespace: String,
        table: String,
        targetSchema: String,
        targetTable: String,
        accessMode: AnalysisAccessMode,
        onProgress: suspend (PullProgress) -> Unit,
    ): PullResult {
        // DuckDB 不会自动建上级目录 —— 目录不存在就直接失败。
        // 而用户第一次打开分析模式时，那个目录**一定**不存在。
        val dirError = AnalysisSession.ensureWorkspaceDir(workspacePath)
        if (dirError != null) throw IllegalStateException(dirError)

        val staging = targetTable
        val req =
            request {
                id = requestId
                this.connection = workspace
                category = Category.ANALYSIS
                action = Action.PULL_TABLE
                analysisRequest =
                    analysisRequest {
                        pull =
                            analysisPullRequest {
                                remote = source
                                this.namespace = namespace
                                this.table = table
                                stagingTable = staging
                                this.targetSchema = targetSchema
                                this.accessMode = accessMode
                            }
                    }
            }

        var rows = 0L
        engine.handle(req).collect { resp ->
            // 进度帧：已从远端读出多少行。
            // 进度要**逐帧**往外抛，而不是只留最后一帧：
            // 进度条的价值全在「动起来」这件事上，只报最终行数等于没报。
            if (resp.hasAnalysisPullProgress()) {
                rows = resp.analysisPullProgress.rows
                onProgress(PullProgress(rows = rows))
            }
            if (resp.hasAnalysis() && resp.analysis.hasPull()) {
                val pull = resp.analysis.pull
                if (pull.cancelled) {
                    // 用户取消 —— **不是失败**。引擎把取消单独标了出来，
                    // 这里就不必去比对错误文本（那种写法在文案一改就失效）。
                    throw AnalysisPullCancelledException()
                }
                if (!resp.success) {
                    throw IllegalStateException(resp.error.ifBlank { "拉取失败" })
                }
                return@collect
            }
            if (!resp.success && resp.error.isNotBlank()) {
                throw IllegalStateException(resp.error)
            }
        }
        return PullResult(
            stagingTable = staging,
            rowsScanned = rows,
            rowsLanded = rows,
        )
    }

    /**
     * 叫停引擎那边正在跑的拉取。
     *
     * ## 为什么**必须**显式叫停
     *
     * 取消前端协程**不会**让引擎停下来 —— 引擎是**另一个进程**（或另一个线程），
     * 它那边还在扫远端、还在往工作区里写。
     * 所以取消要**两件事**一起做：
     * ① 取消前端的协程（不再等结果）；
     * ② 给引擎发一条 `SYSTEM.CANCEL`，带上**这次请求的 id**。
     *
     * ② 之所以拿得到那个 id，是因为请求 id 由**调用方**生成（见 [AnalysisPuller.pull]）——
     * 由引擎自己生成的话，取消方就永远不知道该叫停哪一个。
     *
     * 失败也不影响取消的结果：引擎那边停不停是它的事，
     * 而前端的终态已经是「用户取消」。
     */
    suspend fun cancelPull(
        workspace: ConnectionConfig,
        requestId: String,
    ) {
        runCatching {
            engine.invoke(workspace) {
                category = Category.SYSTEM
                action = Action.CANCEL
                systemRequest =
                    systemRequest {
                        targetRequestId = requestId
                    }
            }
        }
    }
}
