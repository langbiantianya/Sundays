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
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.analysisListColumnsRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.grpc.exportRequest
import com.langbiantianya.grpc.exportRunRequest
import com.langbiantianya.grpc.importRequest
import com.langbiantianya.grpc.importRunRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.sundays.analysis.PULL_CSV_FILE_NAME
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.analysis.PullStage
import com.langbiantianya.sundays.analysis.createStagingTableSql
import com.langbiantianya.sundays.analysis.dropStagingTableSql
import com.langbiantianya.sundays.analysis.pullSelectSql
import com.langbiantianya.sundays.analysis.stagingTableName
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.UUID

/**
 * 拉取链路的**真实现** —— 引擎 + 临时文件。
 *
 * 三段的形状、为什么拆成两次既有调用，见 [AnalysisPuller] 的 KDoc。
 * 这里只讲实现细节。
 */
class EngineAnalysisPuller(
    private val engine: EngineClient,
    /**
     * 临时目录的**根** —— 每次拉取在它下面建一个自己的子目录。
     *
     * 做成构造参数（默认系统临时目录）是为了让测试能**确定性地**断言
     * 「拉取结束后没有留下任何临时目录」：指着一个测试自己给的根去数，
     * 而不是去数全局的临时目录（那会跟并行跑的别的测试打架）。
     */
    private val tempRoot: Path = Paths.get(System.getProperty("java.io.tmpdir")),
) : AnalysisPuller {
    override suspend fun pull(
        source: ConnectionConfig,
        workspace: ConnectionConfig,
        workspacePath: Path,
        dialect: DialectType,
        namespace: String,
        table: String,
        onProgress: suspend (PullProgress) -> Unit,
    ): PullResult {
        val staging = stagingTableName(namespace, table)
        val tempDir = Files.createTempDirectory(tempRoot, PULL_TEMP_DIR_PREFIX)
        // 导出子进程的请求 id —— **在导出开始之前**就定下来。
        //
        // 为什么不能等导出返回再记：取消发生在导出**途中**时，导出根本不会返回，
        // 于是 `finally` 里那句「叫停子进程」就永远拿不到 id ——
        // 子进程继续开着那个 CSV，Windows 上文件被占用时删不掉，
        // 磁盘上就留下一个孤儿目录。
        //
        // ⚠️ 这是实测抓出来的：最初的写法把 id 记在 `exportToCsv` 的**返回值**里，
        // 取消路径上果然残留了一个装着 `pull.csv` 的临时目录。
        val exportRequestId = UUID.randomUUID().toString()
        // 导出**有没有真的开始** —— 决定要不要叫停子进程。
        //
        // 没开始就不用叫停：引擎的停止分支会先把导出子进程**拉起来**
        // （`ensureSubprocessRunning`），而一个从头到尾没导出过的拉取
        // 不该为了「停一个不存在的东西」而白等子进程启动。
        var exportStarted = false
        try {
            // 列远端的列 —— 放在导出**之前**：它是秒级的，
            // 早失败好过拉完几分钟才发现表根本读不到。
            val columns = listRemoteColumns(source, namespace, table)

            // ① 导出到 CSV
            onProgress(PullProgress(PullStage.EXPORT))
            exportStarted = true
            val exported = exportToCsv(source, dialect, namespace, table, tempDir, exportRequestId, onProgress)

            // ② CSV 已落盘 —— 大小在此刻可知
            val bytes = runCatching { Files.size(exported.path) }.getOrDefault(0L)
            onProgress(PullProgress(PullStage.CSV, rows = exported.rows, bytes = bytes))

            // ③ 导入工作区
            onProgress(PullProgress(PullStage.IMPORT, rows = 0, bytes = bytes))
            val imported =
                importIntoWorkspace(
                    workspace = workspace,
                    workspacePath = workspacePath,
                    csv = exported.path,
                    staging = staging,
                    columns = columns,
                    csvBytes = bytes,
                    onProgress = onProgress,
                )

            return PullResult(
                stagingTable = staging,
                rowsExported = exported.rows,
                rowsImported = imported.rowsInserted,
                rowsFailed = imported.rowsFailed,
                csvBytes = bytes,
            )
        } finally {
            withContext(NonCancellable) {
                // 停掉可能还在跑的导出子进程 —— 顺序不能反：
                // 先停（子进程放开文件）再删。
                if (exportStarted) {
                    stopExportQuietly(source, exportRequestId)
                }
                deleteTempDir(tempDir)
            }
        }
    }

    // ──────────────── ① 导出 ────────────────

    /** 一次导出的产物。 */
    private data class CsvExport(
        val path: Path,
        val rows: Long,
    )

    /**
     * 远端 → 临时 CSV。
     *
     * @param requestId 本次导出的请求 id —— **由调用方生成**，
     *   好让取消时「叫停子进程」那一步一定拿得到它（见 [pull] 的说明）。
     *
     * ## 为什么用 `handle` 而不是 `invoke`
     *
     * 导出是**流式**的：每导出一批推一帧进度，终止帧带 `file_path`。
     * `invoke` 只 collect 到第一条终止帧就返回，中间进度与最终文件路径都会丢。
     *
     * ## 为什么有看门狗
     *
     * 引擎在「命令没发出去」或「子进程中途没了」时**一帧都不回**，
     * 光靠 `collect` 结束前的检查是够不着的 —— 得有人替它兜底。
     * 没有这一行时，真窗口上点「拉取」的表现是：进度条永远不动，
     * 界面既不成功也不失败。
     */
    private suspend fun exportToCsv(
        source: ConnectionConfig,
        dialect: DialectType,
        namespace: String,
        table: String,
        tempDir: Path,
        requestId: String,
        onProgress: suspend (PullProgress) -> Unit,
    ): CsvExport {
        val selectSql = pullSelectSql(dialect, namespace, table)
        // 子消息在 DSL **之外**构造：`sql` / `outputDir` 这些字段名会与外层
        // `request` 的兄弟消息撞名，内联赋值会被解析到外层去。
        val runReq =
            exportRunRequest {
                sql = selectSql
                outputDir = tempDir.toString()
                fileName = PULL_CSV_FILE_NAME
                format = "CSV"
            }
        val req =
            request {
                id = requestId
                this.connection = source
                category = Category.EXPORT
                action = Action.RUN_EXPORT
                exportRequest = com.langbiantianya.grpc.exportRequest { runExport = runReq }
            }

        var filePath = ""
        var rows = 0L
        val finished =
            withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
                engine.handle(req).collect { resp ->
                    if (!resp.success) throw IllegalStateException(resp.error.ifBlank { "导出失败" })
                    val progress = resp.export.progress
                    rows = progress.exportedRows
                    // 进度要**逐帧**往外抛，而不是只留最后一帧：
                    // 进度条的价值全在「动起来」这件事上，只报最终行数等于没报。
                    onProgress(PullProgress(PullStage.EXPORT, rows = rows))
                    if (progress.completed) {
                        filePath = progress.filePath
                        if (progress.error.isNotBlank()) throw IllegalStateException(progress.error)
                    }
                }
                true
            }
        if (finished != true) {
            throw IllegalStateException("导出超时（${EXPORT_TIMEOUT_MS / 1000} 秒）")
        }
        if (filePath.isBlank()) {
            throw IllegalStateException("导出结束但没有拿到文件路径")
        }
        return CsvExport(path = Path.of(filePath), rows = rows)
    }

    /**
     * 叫停导出子进程 —— **尽力而为**，失败也不影响清理。
     *
     * 引擎的停止分支走 `stop_export_id`：子进程收到后置取消标志、
     * 结束当前导出并**关掉写入器** —— 文件句柄随之释放。
     */
    private suspend fun stopExportQuietly(
        source: ConnectionConfig,
        exportRequestId: String,
    ) {
        runCatching {
            val runReq = exportRunRequest { stopExportId = exportRequestId }
            engine.invoke(source) {
                category = Category.EXPORT
                action = Action.RUN_EXPORT
                exportRequest = com.langbiantianya.grpc.exportRequest { runExport = runReq }
            }
        }
    }

    // ──────────────── ③ 导入 ────────────────

    /** 一次导入的统计。 */
    internal data class ImportStats(
        val rowsRead: Long,
        val rowsInserted: Long,
        val rowsFailed: Long,
    )

    /**
     * CSV → 工作区的 staging 表。
     *
     * ## 为什么要先建表
     *
     * 导入是「往**已存在的**目标表里 INSERT」—— 引擎不会替你建表。
     * 而建表又必须**先删旧的**：重新拉一张拉过的表时，
     * 不删就会撞「Table already exists」，而「重新拉一次覆盖掉旧的」才是用户想要的动作。
     *
     * ## 失败时把表删掉
     *
     * 导入失败会留下**半张表** —— 那比「没有表」更坏：
     * 用户看着树里那张表，以为拉成功了。
     * 删掉它，界面上就只剩一条「拉取失败」，没有误导性的半成品。
     *
     * ## 为什么是 `internal` 而不是 `private`
     *
     * 导入这一半**不需要远端**：给它一个本地 CSV + 一个本地工作区就能验。
     * 而导出那一半要真连远端（且要起子进程），在 CI 上既慢又不稳。
     * 把两半分开，才能各自有**跑得起来**的测试。
     */
    internal suspend fun importIntoWorkspace(
        workspace: ConnectionConfig,
        workspacePath: Path,
        csv: Path,
        staging: String,
        columns: List<String>,
        csvBytes: Long = 0L,
        onProgress: suspend (PullProgress) -> Unit = {},
    ): ImportStats {
        // DuckDB 不会自动建上级目录 —— 目录不存在就直接失败。
        // 而用户第一次打开分析模式时，那个目录**一定**不存在。
        val dirError = AnalysisSession.ensureWorkspaceDir(workspacePath)
        if (dirError != null) throw IllegalStateException(dirError)

        runWorkspaceStatement(workspace, dropStagingTableSql(staging))
        runWorkspaceStatement(workspace, createStagingTableSql(staging, columns))

        val requestId = UUID.randomUUID().toString()
        val runReq =
            importRunRequest {
                filePath = csv.toString()
                format = "CSV"
                tableName = staging
                // 有表头：CSV 的首行就是列名，导入侧照着它拼 INSERT。
                hasHeader = true
            }
        val req =
            request {
                id = requestId
                this.connection = workspace
                category = Category.IMPORT
                action = Action.RUN_IMPORT
                importRequest = com.langbiantianya.grpc.importRequest { runImport = runReq }
            }

        var stats = ImportStats(rowsRead = 0, rowsInserted = 0, rowsFailed = 0)
        try {
            engine.handle(req).collect { resp ->
                if (resp.hasImportProgress()) {
                    val progress = resp.importProgress
                    stats =
                        ImportStats(
                            rowsRead = progress.rowsRead,
                            rowsInserted = progress.rowsInserted,
                            rowsFailed = progress.rowsFailed,
                        )
                    onProgress(
                        PullProgress(
                            stage = PullStage.IMPORT,
                            rows = stats.rowsInserted,
                            bytes = csvBytes,
                        ),
                    )
                }
                if (resp.hasImport() && resp.import.hasResult()) {
                    val result = resp.import.result
                    if (!result.success) throw IllegalStateException(result.error.ifBlank { "导入失败" })
                    stats =
                        ImportStats(
                            rowsRead = result.rowsRead,
                            rowsInserted = result.rowsInserted,
                            rowsFailed = result.rowsFailed,
                        )
                }
                if (!resp.success && resp.error.isNotBlank()) {
                    throw IllegalStateException(resp.error)
                }
            }
        } catch (e: Exception) {
            // 半张表比没有表更坏 —— 删掉，别让用户对着它以为拉成功了。
            runCatching { runWorkspaceStatement(workspace, dropStagingTableSql(staging)) }
            throw e
        }
        return stats
    }

    // ──────────────── 辅助 ────────────────

    /** 远端的列名（按 ordinal 顺序）。 */
    private suspend fun listRemoteColumns(
        source: ConnectionConfig,
        namespace: String,
        table: String,
    ): List<String> {
        val resp =
            engine.invoke(source) {
                category = Category.ANALYSIS
                action = Action.LIST_COLUMNS
                analysisRequest =
                    analysisRequest {
                        listColumns =
                            analysisListColumnsRequest {
                                this.namespace = namespace
                                this.table = table
                            }
                    }
            }
        if (!resp.success) {
            throw IllegalStateException(resp.error.ifBlank { "读取远端列失败" })
        }
        val columns = resp.analysis.listColumns.itemsList
        return columns.map { it.name }
    }

    /** 在工作区上跑一条语句（建表 / 删表）。 */
    private suspend fun runWorkspaceStatement(
        workspace: ConnectionConfig,
        sql: String,
    ) {
        val resp =
            engine.invoke(workspace) {
                category = Category.ANALYSIS
                action = Action.RUN_QUERY
                analysisRequest =
                    analysisRequest {
                        runQuery = analysisRunQueryRequest { this.sql = sql }
                    }
            }
        if (!resp.success) {
            throw IllegalStateException(resp.error.ifBlank { "工作区执行失败：$sql" })
        }
    }

    /**
     * 删掉临时目录（含里面的 CSV）。
     *
     * ## 为什么重试
     *
     * 取消时导出子进程可能**还没来得及**关掉文件句柄，
     * 而 Windows 上文件被占用时删不掉。删一次失败就放弃，
     * 留下的正是「取消之后仍然占着磁盘」的那个文件 —— 恰恰是这条验收要防的。
     */
    private suspend fun deleteTempDir(dir: Path) {
        repeat(DELETE_RETRY_ATTEMPTS) {
            val ok =
                runCatching {
                    if (!Files.exists(dir)) return@runCatching true
                    Files.walk(dir).use { stream ->
                        stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
                    }
                    true
                }.getOrDefault(false)
            if (ok) return
            delay(DELETE_RETRY_DELAY_MS)
        }
    }

    private companion object {
        /** 导出看门狗（毫秒）—— 与浏览模式的导出同一个量级。 */
        const val EXPORT_TIMEOUT_MS = 5 * 60 * 1000L

        /** 临时目录名前缀。 */
        const val PULL_TEMP_DIR_PREFIX = "sundays-analysis-pull-"

        /** 删临时目录的重试次数与间隔。 */
        const val DELETE_RETRY_ATTEMPTS = 5
        const val DELETE_RETRY_DELAY_MS = 200L
    }
}
