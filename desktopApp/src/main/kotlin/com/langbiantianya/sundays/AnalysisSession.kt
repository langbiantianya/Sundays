/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.protobuf.Value
import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.stagingTableName
import com.langbiantianya.sundays.analysis.workspaceFilePath
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 分析模式的状态机 —— 源列表、工作区、当前 SQL 与结果。
 *
 * ## 它与 `ConnectionSession` / `DatabaseBrowserState` 是**并列**的
 *
 * 不继承、不耦合 —— 它们管的是「浏览模式」：按连接开 sheet、管浏览状态、
 * 跑 SQL 看结果。而分析模式是**另一套会话**：
 *
 * - 一个**远端只读源会话**（读元数据、拉数据）
 * - 一个**本地工作区会话**（DuckDB 文件，能写）
 *
 * 两套会话的生命周期、状态形状、失败语义都不一样，硬塞进一套里只会互相牵扯。
 *
 * ## 工作区为什么是**文件**而不是连接
 *
 * 主设计 §2.2 读法 B：DuckDB 是分析模式**自带的计算引擎**（默认且常驻），
 * 不是用户自己建的连接。所以它没有 `connection.json` 里的条目 ——
 * 它是应用的一部分，跨会话常驻（文件复用），退出时正常关闭。
 *
 * ## 两条会话的只读性是**相反**的
 *
 * | 会话 | `read_only` | 为什么 |
 * |---|---|---|
 * | 远端源 | **开** | 分析模式对远端只有读的需求（列元数据 + 拉数据），引擎的 L4 闸门与方言级只读会兜底 |
 * | 本地工作区 | **不开** | M4 的验收明确要求「工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**」 |
 *
 * 这是本文件最要紧的一条：**工作区带上了只读，分析模式就废了**；
 * 反过来远端漏了只读，就成了绕过 L4 的后门。两边都写死，不靠调用方自觉。
 */
class AnalysisSession(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    /**
     * 工作区数据库文件路径 —— 默认落在应用配置目录。
     *
     * 做成构造参数（而不是在类体里现算）有两个原因：
     * - 测试要指向临时目录，不能写死到真实用户主目录；
     * - 路径一旦定下就不该中途变 —— 工作区是**一个**文件，
     *   换一个路径等于换了一个工作区，之前拉进来的数据全都留在旧文件里。
     */
    val workspaceFile: String = workspaceFilePath(System.getProperty("user.home")),
) {
    /**
     * 工作区数据库文件路径 —— `~/.config/sundays/workspace.duckdb`。
     *
     * 路径规则在 `:shared` 的 [workspaceFilePath]（纯逻辑，可跨平台复用）；
     * 这里只把它落到本机的文件系统上。
     */
    val workspacePath: Path = Paths.get(workspaceFile)

    /**
     * 远端源的**完整连接配置**（含凭据）—— 按数据源 id 索引。
     *
     * ## 为什么单独存一份
     *
     * 拉数据需要 URL 与凭据，而界面那份 `AnalysisSourceItem` 只有展示字段
     * （id / 名字 / 方言标签）。让 UI 层在需要时再回连接管理器取配置，
     * 等于把「配置从哪来」这件事在两处各写一遍 —— 那就是第二个真相源。
     *
     * ## 为什么**不**把源列表也存进来
     *
     * 源列表是**派生数据**：连接列表 × 「方言声明了可分析」= 可分析的源。
     * 派生数据再存一份，就有了两个可能对不上的副本
     * （连接删了、引擎改了能力位声明，存的那份不会自己跟着变）。
     * 所以列表留在调用方现算，这里只收**派生不出来的那部分** —— 凭据。
     *
     * 它**不是** Compose 状态：没有界面读它，不需要重组。
     */
    private var sourceConfigs: Map<String, ConnectionConfig> = emptyMap()

    /** 当前选中的数据源 id。 */
    var selectedSourceId by mutableStateOf<String?>(null)
        private set

    /** 编辑器里的当前 SQL。 */
    var sql by mutableStateOf("")
        private set

    /** 最近一次查询结果。 */
    var result by mutableStateOf<AnalysisResult?>(null)
        private set

    /** 正在跑 —— 界面据此禁用「运行」按钮。 */
    var running by mutableStateOf(false)
        private set

    /**
     * 同步可分析的远端源的**连接配置**（按 id 索引）。
     *
     * ## 为什么要剪枝选中项
     *
     * 连接被删掉后，选中项若还留着，界面会指向一个**不存在的源** ——
     * 后续拉取会拿着一个查无此人的 id 去引擎。宁可退回「未选中」。
     */
    fun setSourceConfigs(configs: Map<String, ConnectionConfig>) {
        sourceConfigs = configs
        val current = selectedSourceId
        if (current != null && !configs.containsKey(current)) {
            selectedSourceId = null
        }
    }

    /** 换数据源。 */
    fun selectSource(id: String) {
        selectedSourceId = id
    }

    /**
     * 更新当前 SQL（编辑器每次改动都调）。
     *
     * 叫 `updateSql` 而不是 `setSql` —— 后者与 `var sql` 的 setter 在 JVM 上
     * 撞签名（`setSql(String)`），编译期就会报「Platform declaration clash」。
     */
    fun updateSql(text: String) {
        sql = text
    }

    /**
     * 远端源的会话配置 —— **恒为只读**。
     *
     * ## 为什么前端也要标只读
     *
     * 引擎侧有 L4 闸门（会话级）与方言级只读，那是**兜底**。
     * 前端这一层是为了让「远端恒为只读」在**发请求之前**就成立 ——
     * 少一条走错的路，而不是靠引擎拦。
     *
     * ## 为什么用 `toBuilder()` 复制
     *
     * 原对象是连接管理器里的那一份，在**浏览模式**里是可写的 ——
     * 用户有权在浏览模式里改数据。在这里就地改掉，
     * 就等于把浏览模式也变成只读了。
     */
    fun sourceConfig(id: String): ConnectionConfig? = sourceConfigs[id]?.toBuilder()?.setReadOnly(true)?.build()

    /**
     * 远端表在**工作区**里的落点 —— `staging_<源库>_<表名>`。
     *
     * 规则本体在 `:shared`（纯逻辑，可跨平台复用）；这里只是给调用方一个
     * 不用自己 import 的入口 —— 拉取链路只跟会话打交道。
     */
    fun stagingName(
        sourceNamespace: String,
        table: String,
    ): String = stagingTableName(sourceNamespace, table)

    /**
     * 在**本地工作区**上跑当前 SQL。
     *
     * 跑的是工作区（本地 DuckDB），**不是**远端 —— 远端只读，
     * 在远端跑分析 SQL 已经被引擎的 `EMBEDDED_MODE` 闸门挡住了。
     *
     * ## 为什么返回 `Job`
     *
     * 两个用处：测试要**等**它跑完才能断言结果；
     * 拉取链路要能**取消**它（用户中途改主意）。
     * 没有句柄的话，这两件事都只能靠轮询状态位猜。
     *
     * @return 启动的任务；`null` = 没跑（SQL 为空，或上一条还在跑）
     */
    fun run(): Job? {
        val statement = sql
        if (statement.isBlank() || running) return null
        return scope.launch {
            running = true
            try {
                result = runOnWorkspace(statement)
            } finally {
                running = false
            }
        }
    }

    /**
     * 在工作区上跑一条 SQL，并把失败也收成**结果**而不是异常。
     *
     * ## 为什么失败是结果而不是抛出
     *
     * 界面要显示的是「这句话哪里错了」—— 那是一行要摆出来的文字，
     * 不是要往上抛的异常。抛出去只会变成一个红条 + 堆栈，
     * 而用户要的是 SQL 的错误原文。
     */
    private suspend fun runOnWorkspace(statement: String): AnalysisResult {
        // DuckDB 不会自动建上级目录 —— 目录不存在就直接失败。
        val dirError = ensureWorkspaceDir(workspacePath)
        if (dirError != null) return AnalysisResult(error = dirError)
        return runCatching { runAnalysisQuery(engine, workspaceConnection(), statement) }
            .getOrElse { AnalysisResult(error = it.message ?: "查询失败") }
    }

    /**
     * 工作区的引擎连接配置。
     *
     * `readOnly` **不加** —— 工作区是本地 DuckDB 文件，写它不碰任何远端；
     * 主设计 M4 的验收明确要求「工作区里 `CREATE TABLE tmp AS SELECT …` 可以成功」。
     *
     * `driver` 取 `DialectType.DUCKDB.engineDriverName`（`Duckdb`）——
     * 引擎按这个名字注册方言，用枚举常量名（`DUCKDB`）会报「No dialect plugin loaded」。
     */
    internal fun workspaceConnection(): ConnectionConfig =
        connectionConfig {
            driver = DialectType.DUCKDB.engineDriverName
            jdbcUrl = "jdbc:duckdb:$workspaceFile"
            database = workspaceFile
        }

    companion object {
        /**
         * 建好工作区目录。
         *
         * DuckDB 建库时**不会**自动创建上级目录 —— 目录不存在就直接失败。
         * 所以每次要用工作区之前先确保目录在。
         *
         * @return 失败时的原因（`null` = 成功）
         */
        fun ensureWorkspaceDir(workspacePath: Path): String? =
            try {
                val dir = workspacePath.parent
                if (dir != null && !Files.exists(dir)) {
                    Files.createDirectories(dir)
                }
                null
            } catch (e: Exception) {
                e.message ?: "创建工作区目录失败"
            }
    }
}

/**
 * 发一条分析查询并把响应转成界面要的结果。
 *
 * ## 为什么单独拆成顶层函数
 *
 * 它是**纯逻辑**（给定引擎响应，给出结果），不持有任何状态 ——
 * 这样测试可以直接调它，不需要先搭一个 `AnalysisSession`。
 *
 * @param engine 引擎客户端
 * @param connection 目标连接（工作区）
 * @param sql 要执行的语句
 */
internal suspend fun runAnalysisQuery(
    engine: EngineClient,
    connection: ConnectionConfig,
    sql: String,
): AnalysisResult {
    val started = System.currentTimeMillis()
    val response =
        engine.invoke(connection) {
            category = Category.ANALYSIS
            action = Action.RUN_QUERY
            analysisRequest =
                analysisRequest {
                    runQuery = analysisRunQueryRequest { this.sql = sql }
                }
        }
    val elapsed = System.currentTimeMillis() - started

    if (!response.success) {
        return AnalysisResult(elapsedMs = elapsed, error = response.error.ifBlank { "未知错误" })
    }

    val runQuery = response.analysis.runQuery
    val columns = runQuery.columnsList.map { AnalysisColumn(name = it.name, type = it.type) }
    val rows =
        runQuery.rowsList.map { row ->
            columns.map { column ->
                row.valuesMap[column.name]?.let { valueToText(it) }
            }
        }
    return AnalysisResult(
        columns = columns,
        rows = rows,
        affectedRows = runQuery.affectedRows,
        truncated = runQuery.truncated,
        elapsedMs = elapsed,
    )
}

/**
 * proto 的 `Value` → 展示用的文本。
 *
 * ## 为什么一切都变成字符串
 *
 * 结果表是给人看的，而不同引擎、不同类型对同一个值的呈现方式差别很大
 * （日期、DECIMAL、布尔各有各的写法）。界面层再按列类型格式化一遍，
 * 那一层才知道「这一列是金额，该带千分位」。
 *
 * 这里只保证**信息不丢**：引擎侧的分析查询把每个单元格按字符串取
 * （`ResultSet.getString`），所以走到这里的绝大多数是 [Value.KindCase.STRING_VALUE]；
 * 数值与布尔两支是为将来引擎改口径留的。
 */
private fun valueToText(value: Value): String? =
    when (value.kindCase) {
        Value.KindCase.NULL_VALUE -> null
        Value.KindCase.STRING_VALUE -> value.stringValue
        Value.KindCase.NUMBER_VALUE -> value.numberValue.toString()
        Value.KindCase.BOOL_VALUE -> value.boolValue.toString()
        else -> value.toString()
    }
