/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 导出的**公共件** —— 格式、结果、进度，以及「跑一次导出」这件事本身。
//
// 这几样**不属于**任何一种模式：浏览模式导出表预览、分析模式导出 SQL 编辑器的查询结果，
// 走的是**同一条引擎链路**（`EXPORT.RUN_EXPORT`）、**同一套格式**、**同一种文件**。
// 所以它们住在**顶层**而不是某个状态机里 ——
// 挂在 `DatabaseBrowserState` 下面时，分析模式要用它就得先造一个浏览模式的状态机，
// 那不是「复用」，是「借用一个不相干的对象」。

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 导出格式 —— 与引擎的 `ExportRunRequest.format` 一一对应。
 *
 * ## 为什么是**顶层**枚举而不是挂在某个状态机下面
 *
 * 浏览模式与分析模式**都要**用它（同一个对话框、同一个文件后缀规则）。
 * 挂在 `DatabaseBrowserState` 下面时，分析模式要用它就得先造一个浏览模式的状态机 ——
 * 那不是复用，是借用一个不相干的对象。
 *
 * @property labelKey 界面上的格式名（走 i18n，不是硬编码）
 * @property protoValue 发给引擎的格式标识
 * @property extension 该格式的**文件扩展名**（不含点）
 */
enum class ExportFormat(
    val labelKey: StrKey,
    val protoValue: String,
    val extension: String,
) {
    CSV(StrKey.FORMAT_CSV, "CSV", "csv"),
    JSON_LINES(StrKey.FORMAT_JSON_LINES, "JSON_LINES", "jsonl"),
    SQL_INSERT(StrKey.FORMAT_SQL_INSERT, "SQL_INSERT", "sql"),
    EXCEL(StrKey.FORMAT_EXCEL, "EXCEL", "xlsx"),
    PARQUET(StrKey.FORMAT_PARQUET, "PARQUET", "parquet"),
}

/**
 * 导出结果 —— 引擎终止帧回的实际文件路径。
 *
 * @property file 落盘文件的**绝对路径**
 * @property rowsWritten 写出去的行数
 */
data class ExportResult(
    val file: String,
    val rowsWritten: Long,
)

/**
 * 导出进度的一帧 —— 引擎每导出一批就推一次。
 *
 * ## 为什么只有行数、没有百分比
 *
 * 引擎的 `ExportProgressFrame` 里就只有 `exported_rows` / `column_count` /
 * `completed`，**没有总行数**：导出走的是一条普通 `SELECT`，引擎不会为了报进度
 * 先跑一遍 `COUNT(*)`（大表上那是白等一遍）。
 *
 * 前端**不能**拿预览那一行的行数来充当总数 —— 预览可能带着过滤/搜索条件，
 * 而导出的是整张表，两者对不上。报一个假的百分比比不报更糟，
 * 所以进度条走不确定态（indeterminate），旁边如实显示「已导出 N 行」。
 */
data class ExportProgress(
    val rowsWritten: Long,
    val completed: Boolean,
)

/**
 * 导出的**看门狗超时**（毫秒）。
 *
 * 取 5 分钟：真正的大表导出按行推进、可能跑很久，这个值不该误伤正常导出；
 * 但它必须存在 —— 引擎那条流式路由在「命令没发出去」或「子进程中途没了」时
 * 可能**一帧都不回**，此时 `collect` 会永远挂着，界面既不成功也不失败，
 * 用户看到的是「点了导出，然后什么也没发生」。
 *
 * 与其指望每一层都记得收口，不如在这层兜一道底：
 * 超时也要变成一条**用户看得见**的失败。
 */
const val DEFAULT_EXPORT_TIMEOUT_MS: Long = 5 * 60 * 1000

/**
 * 导出一段 SQL 的结果 —— 发 `EXPORT.RUN_EXPORT`。
 *
 * ## 这是**唯一**一份「跑一次导出」的实现
 *
 * 浏览模式（表预览 / SQL 工作台）与分析模式（SQL 编辑器的查询结果）都调它 ——
 * 同一套格式、同一个看门狗、同一种失败文案。
 *
 * ## 为什么用 [EngineClient.handle] 而不是 [EngineClient.invoke]
 *
 * 导出是**流式**的：每导出一批推一帧 `ExportProgressFrame`，终止帧带 `completed=true`
 * 与最终 `file_path`。`invoke` 只 collect 到**第一条**终止帧就返回，中间进度与最终
 * 文件路径都会丢。
 *
 * ## 参数先在**前端**校验
 *
 * 让引擎那边才报错的话，用户看到的是一句 JDBC 异常，
 * 还要在对话框里猜是自己哪里填错了。
 *
 * @param connection 导出打到的连接（浏览模式是当前连接，分析模式是工作区）
 * @param format `SQL_INSERT` 时 [tableName] 必填 —— 引擎要拼出 `INSERT INTO <表>`
 * @param timeoutMs 看门狗；超时也要变成一条**用户看得见**的失败
 * @param onProgress 每收到一帧进度就调一次（**在引擎的协程里**，不是主线程）。
 *   UI 层用它驱动进度弹窗；不给就用默认的空实现。
 * @return 失败时 `Result.failure`；成功时 [ExportResult]
 */
suspend fun runExport(
    engine: EngineClient,
    connection: ConnectionConfig,
    sql: String,
    format: ExportFormat,
    outputDir: String,
    fileName: String,
    tableName: String = "",
    timeoutMs: Long = DEFAULT_EXPORT_TIMEOUT_MS,
    onProgress: (ExportProgress) -> Unit = {},
): Result<ExportResult> {
    if (sql.isBlank()) return Result.failure(IllegalArgumentException(Msg.text(StrKey.SQL_EMPTY)))
    if (outputDir.isBlank()) {
        return Result.failure(IllegalArgumentException(Msg.text(StrKey.OUTPUT_DIR_REQUIRED)))
    }
    if (fileName.isBlank()) {
        return Result.failure(IllegalArgumentException(Msg.text(StrKey.FILE_NAME_REQUIRED)))
    }
    if (format == ExportFormat.SQL_INSERT && tableName.isBlank()) {
        return Result.failure(IllegalArgumentException(Msg.text(StrKey.EXPORT_TABLE_NAME_REQUIRED)))
    }
    // 在 DSL 之外构造：`sql` / `outputDir` 等字段名会与外层 `exportRequest` 的兄弟消息
    // 撞名，内联赋值会被解析到外层去（见 updateCell 的 KDoc）
    val runReq =
        com.langbiantianya.grpc.exportRunRequest {
            this.sql = sql
            this.outputDir = outputDir
            this.fileName = fileName
            this.format = format.protoValue
            this.tableName = tableName
        }
    return runCatching {
        val req =
            com.langbiantianya.grpc.request {
                id = UUID.randomUUID().toString()
                this.connection = connection
                category = Category.EXPORT
                action = Action.RUN_EXPORT
                exportRequest = com.langbiantianya.grpc.exportRequest { runExport = runReq }
            }
        var file = ""
        var rows = 0L
        // ⚠️ `withTimeoutOrNull` 不是「防御性编程」，是这条契约的**实现**：
        // 引擎在命令没发出去 / 子进程中途消失时会**一帧都不回**，
        // 光靠 `collect` 结束前的检查是够不着的 —— 得有人替它兜底。
        // 没有这一行时，真窗口上点「开始导出」的表现是：对话框关了，
        // 文件没有，界面上一个字都没有（实测，见 TEST_CASES.md §9.13）。
        val finished =
            withTimeoutOrNull(timeoutMs) {
                engine.handle(req).collect { resp ->
                    if (!resp.success) throw IllegalStateException(resp.error.ifBlank { Msg.text(StrKey.EXPORT_FAILED) })
                    val p = resp.export.progress
                    rows = p.exportedRows
                    // 进度要**逐帧**往外抛，而不是只留最后一帧：
                    // 进度弹窗的价值全在「动起来」这件事上，只报最终行数等于没报
                    onProgress(ExportProgress(rowsWritten = p.exportedRows, completed = p.completed))
                    if (p.completed) {
                        file = p.filePath
                        if (p.error.isNotBlank()) throw IllegalStateException(p.error)
                    }
                }
                true
            }
        if (finished != true) {
            throw IllegalStateException(Msg.format(StrKey.EXPORT_TIMEOUT, timeoutMs / 1000))
        }
        if (file.isBlank()) throw IllegalStateException(Msg.text(StrKey.EXPORT_NO_FILE_REPORTED))
        ExportResult(file = file, rowsWritten = rows)
    }
}
