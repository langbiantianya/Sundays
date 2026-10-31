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
import com.langbiantianya.grpc.AnalysisAccessMode
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.analysisListColumnsRequest
import com.langbiantianya.grpc.analysisListSourcesRequest
import com.langbiantianya.grpc.analysisListTablesRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.ANALYSIS_WORKSPACE_NODE_ID
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisMode
import com.langbiantianya.sundays.analysis.AnalysisPullCancelledException
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.DeleteSourceOutcome
import com.langbiantianya.sundays.analysis.PullOutcome
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.WORKSPACE_SCHEMA
import com.langbiantianya.sundays.analysis.analysisColumnNodeId
import com.langbiantianya.sundays.analysis.analysisSchemaNodeId
import com.langbiantianya.sundays.analysis.analysisTableNodeId
import com.langbiantianya.sundays.analysis.appendAnalysisColumnRef
import com.langbiantianya.sundays.analysis.appendAnalysisTableRef
import com.langbiantianya.sundays.analysis.report.AnalysisReport
import com.langbiantianya.sundays.analysis.report.ReportLoadResult
import com.langbiantianya.sundays.analysis.report.ReportStatus
import com.langbiantianya.sundays.analysis.report.ReportStorage
import com.langbiantianya.sundays.analysis.report.ReportWidget
import com.langbiantianya.sundays.analysis.report.ReportWidgetMove
import com.langbiantianya.sundays.analysis.report.moveReportWidget
import com.langbiantianya.sundays.analysis.report.reportDisplayNameText
import com.langbiantianya.sundays.analysis.stagingTableName
import com.langbiantianya.sundays.analysis.workspaceFilePath
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.tree.ObjectTreeColumn
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.findObjectTreeNode
import com.langbiantianya.sundays.tree.replaceObjectTreeNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个远端源在本会话里的全部信息 —— 展示条目 + 引擎配置 + 方言。
 *
 * ## 为什么三样东西捆在一起
 *
 * 拉取一条远端表要同时用到：给界面看的 [AnalysisSourceItem]、
 * 给引擎的连接配置（含凭据）、以及**方言**（决定命名空间的引用形式：
 * MySQL 上是库、PG 上是 schema）。
 *
 * 分成三张并行的 map（按 id 索引）就是三个可能对不上的东西 ——
 * 加一个源要改三处，漏一处就拉错库。捆在一起就没有「漏」这回事。
 *
 * 它住在 `desktopApp` 而不是 `:shared`：引擎配置是 proto 类型，
 * `:shared` 看不见协议层。
 */
data class AnalysisSourceBinding(
    val item: AnalysisSourceItem,
    val engineConfig: ConnectionConfig,
    val dialect: DialectType,
)

/**
 * 分析模式的一个数据源 —— **两类**。
 *
 * | 类 | 是什么 | 怎么进工作区 |
 * |---|---|---|
 * | [REMOTE] | 远端数据库（MySQL / PostgreSQL …） | **DuckDB 官方扩展优先**（`ATTACH` → 建表 → 摘除），走不通就退回 JDBC 表函数 |
 * | [FILE] | 文件或文件夹（parquet / csv / Excel / jsonl） | DuckDB 自己的读法（`read_parquet` / `read_csv_auto` / `read_json_auto` / `read_xlsx`），**建一个视图** |
 *
 * ## 为什么分析模式**自己**有一份清单
 *
 * 连接管理器里的连接是**浏览模式**的东西；分析模式要的是「我在这里加的数据源」。
 * 两者**不共用** —— 共用就会变成「两份可能对不上的真相」
 * （同一个 MySQL 在两处各存一份，改了密码只改一处，另一处连不上）。
 * 所以分析模式的清单**存在工作区 DuckDB 里**（见 [AnalysisSession.REGISTRY_SCHEMA]），
 * 随工作区一起走。
 */
enum class AnalysisSourceKind {
    REMOTE,
    FILE,
}

/**
 * 文件的四种形态 —— 各对应 DuckDB 的一个读法。
 *
 * 为什么不用「一个万能读法」：DuckDB 的 `read_csv_auto` 只认 CSV，
 * `read_parquet` 只认 parquet —— 它们**各管各的格式**，
 * 而一个万能函数就得自己猜格式（猜错了报错比不给还糟）。
 */
enum class AnalysisFileFormat(
    val reader: String,
) {
    PARQUET("read_parquet"),
    CSV("read_csv_auto"),
    JSONL("read_json_auto"),
    EXCEL("read_xlsx"),
    ;

    companion object {
        /**
         * 从**扩展名**猜格式 —— 猜不到就 `null`（让用户自己选）。
         *
         * ## 为什么只认扩展名
         *
         * 猜内容（读几个字节看魔数）在这个场景里**不划算**：
         * parquet 与 csv 的差别一眼就能从文件名看出来，
         * 而「文件夹里装的是什么」本来就只能问用户。
         */
        fun fromPath(path: String): AnalysisFileFormat? {
            val lower = path.lowercase()
            return when {
                lower.endsWith(".parquet") || lower.endsWith(".pq") -> PARQUET
                lower.endsWith(".csv") || lower.endsWith(".tsv") -> CSV
                lower.endsWith(".jsonl") || lower.endsWith(".ndjson") || lower.endsWith(".json") -> JSONL
                lower.endsWith(".xlsx") || lower.endsWith(".xls") -> EXCEL
                else -> null
            }
        }
    }
}

/**
 * 对话框填出来的「待添加的数据源」—— **还没**落进工作区。
 *
 * @param name 显示名 —— **同时**是它在工作区里占的 schema 名
 *   （同名就不用再记一份「显示名 ↔ schema 名」的映射）；
 *   树上的那一行显示的就是它。
 * @param kind 源的类型
 * @param dialect 远程源的方言；文件源恒为 [DialectType.DUCKDB]
 *   （文件是 DuckDB 自己读的，不经 JDBC）
 * @param jdbcUrl 远程源的 JDBC URL —— 由对话框按方言拼出来
 * @param database 远程源的库名（留空 = 该服务器上的全部库）
 * @param user 远程源的用户名
 * @param password 远程源的密码
 * @param path 文件 / 文件夹的路径
 * @param format 文件的格式 —— `null` = 让用户选
 */
data class AnalysisSourceDraft(
    val name: String,
    val kind: AnalysisSourceKind,
    val dialect: DialectType = DialectType.DUCKDB,
    val jdbcUrl: String = "",
    val database: String = "",
    val user: String = "",
    val password: String = "",
    val path: String = "",
    val format: AnalysisFileFormat? = null,
)

/**
 * 预览里勾中的一张远端表。
 *
 * @param namespace 远端的命名空间（MySQL 上是库，PG 上是 schema）
 * @param table 表名
 */
data class AnalysisSelectedTable(
    val namespace: String,
    val table: String,
)

/**
 * 预览的结果 —— 远端的一个库 + 它的表。
 *
 * @param namespace 库 / schema
 * @param tables 表名
 */
data class AnalysisRemotePreview(
    val namespace: String,
    val tables: List<String>,
)

/**
 * 登记在册的一个数据源 —— 「分析模式能用的一个源」。
 *
 * @param name 显示名（同时是它在工作区里占的 schema 名）
 * @param kind 源的类型
 * @param dialect 方言名（远端源用它；文件源是 `DuckDB`）
 * @param jdbcUrl 远程源的 JDBC URL
 * @param database 远程源的库名
 * @param user 远程源的用户名
 * @param password 远程源的密码
 * @param path 文件 / 文件夹的路径
 * @param format 文件的格式（`Parquet` / `CSV` / …）
 */
data class AnalysisRegisteredSource(
    val name: String,
    val kind: AnalysisSourceKind,
    val dialect: String,
    val jdbcUrl: String,
    val database: String,
    val user: String,
    val password: String,
    val path: String,
    val format: String,
) {
    /** 它在工作区里占的 schema 名 —— 与 [name] 同名。 */
    val schemaName: String get() = name
}

/**
 * 分析模式的状态机 —— 源列表、工作区、当前 SQL 与结果、拉取任务。
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
    /**
     * 拉取链路的编排器。
     *
     * 做成构造参数（而不是在类体里现算）是为了让**会话的终态映射**可测：
     * 真实现要连远端、还要起导出子进程，而「拉完了 / 拉挂了 / 拉取消了」
     * 这三条路径是状态机的一部分，必须能**确定性地**各测一遍。
     */
    private val puller: AnalysisPuller = EngineAnalysisPuller(engine),
    /**
     * 报表的落盘。
     *
     * 做成构造参数（而不是在类体里现算）有两个原因：
     * - 测试要指向临时目录，不能写死到真实用户主目录 ——
     *   否则跑一次全量测试，用户的 `reports.json` 里就多出几张测试报表；
     * - 落盘**停用**这件事需要能被单独测到（文件读不出来且改名失败时）。
     */
    private val reportStorage: ReportStorage = ReportStorage(),
    /**
     * 「另存为」对话框 —— **可替换的接缝**。
     *
     * 做成构造参数的理由与 [puller] 一样：**导出流程必须能被确定性地测**。
     * 真实现会弹一个 AWT 模态框 —— 那在无头环境里弹不出来，
     * 在有桌面的机器上弹得出来也**没人去点**，测试会**永远等下去**
     * （模态框还把 AWT 事件线程占住，测试进程退不出去）。
     *
     * 所以这里收的是「挑一个路径」这件事本身，而不是「怎么挑」——
     * 界面那部分（弹框、预填文件名）归 [NativePathPicker]，
     * 会话只关心「挑到哪儿了」。
     *
     * @param initialDir 起始目录
     * @param initialName 预填的文件名
     * @return 挑中的路径；`null` = 用户取消
     */
    private val filePicker: suspend (initialDir: String, initialName: String) -> NativePathPicker.Picked? =
        { initialDir, initialName -> NativePathPicker.pickFile(initialDir, initialName) },
) {
    /**
     * 工作区数据库文件路径 —— `~/.config/sundays/workspace.duckdb`。
     *
     * 路径规则在 `:shared` 的 [workspaceFilePath]（纯逻辑，可跨平台复用）；
     * 这里只把它落到本机的文件系统上。
     */
    val workspacePath: Path = Paths.get(workspaceFile)

    /**
     * 远端源的绑定（按 id 索引）。
     *
     * ## 为什么**不**把源列表也存进来
     *
     * 源列表是**派生数据**：连接列表 × 「方言声明了可分析」= 可分析的源。
     * 派生数据再存一份，就有了两个可能对不上的副本
     * （连接删了、引擎改了能力位声明，存的那份不会自己跟着变）。
     * 所以列表留在调用方现算，这里只收**派生不出来的那部分** ——
     * 凭据、方言，以及派生不出的**状态**：SQL、结果、树的展开态与拉取进度。
     *
     * 它**不是** Compose 状态：没有界面读它，不需要重组。
     */
    private var sourceBindings: Map<String, AnalysisSourceBinding> = emptyMap()

    /** 编辑器里的当前 SQL。 */
    var sql by mutableStateOf("")
        private set

    /** 最近一次查询结果。 */
    var result by mutableStateOf<AnalysisResult?>(null)
        private set

    /** 正在跑 —— 界面据此禁用「运行」按钮。 */
    var running by mutableStateOf(false)
        private set

    /** 拉取中的进度（哪一段、已处理多少行、临时文件多大）。 */
    var pullProgress by mutableStateOf<PullProgress?>(null)
        private set

    /**
     * 最近一次拉取的**终态** —— 完成 / 取消 / 失败，三者互斥。
     *
     * ## 为什么不用「结果 + 错误」两个可空字段
     *
     * 两个可空字段表达不了「取消」：结果是空、错误也是空，
     * 界面只能靠「两个都空 ⇒ 大概是取消了」来**推断**。
     * 而「大概」在状态机里是不该有的字眼 ——
     * 一旦将来多出第四种「什么都没发生」的路径（比如还没点拉取就被切了页），
     * 那两个字面量就再也分不出「取消」与「没开始」。
     *
     * 终态是一个**穷举**（sealed），三种情况各自带自己的载荷，
     * 界面照着渲染即可，不必自己推断。
     */
    var pullOutcome by mutableStateOf<PullOutcome?>(null)
        private set

    /** 正在拉取 —— 界面据此禁用「拉取」按钮。 */
    var pulling by mutableStateOf(false)
        private set

    /** 当前那次拉取的任务 —— 取消要用它。 */
    private var pullJob: Job? = null

    /**
     * 当前那次拉取的**引擎侧请求 id** —— 取消时靠它叫停引擎。
     *
     * 为什么不能靠取消协程：引擎是**另一个进程**（或至少是另一个执行上下文），
     * 前端取消协程只是「不再等结果」，引擎那边照样在扫远端、照样往工作区里写。
     */
    private var pullRequestId: String = ""

    /** 请求 id 的自增尾数 —— 同一毫秒里连点两次也不会撞 id。 */
    private val pullCounter = AtomicLong(0)

    // ──────────────── 数据源树 ────────────────

    /**
     * 数据源树的根节点 —— **只有本地工作区**。
     *
     * ## 为什么树住在会话里而不是界面里
     *
     * 树的**内容**（已加载的子节点）是**派生数据** —— 由引擎的
     * `LIST_SOURCES` / `LIST_TABLES` 查出来。放在界面侧就意味着
     * 「界面自己管一份树数据」，而界面是纯 Compose、拿不到引擎；
     * 于是树只能由会话喂，展开动作也由会话执行。
     *
     * ## 为什么**根**是「工作区 + 远端源」而不是「远端源」
     *
     * 工作区是分析模式的**计算引擎**（DuckDB 文件），它与远端源在树上**同级** ——
     * 一个可写、一个恒只读，性质相反但都是「分析模式能用的东西」。
     * 把工作区塞进某个远端源底下会传达错误的关系。
     */
    var treeRoots by mutableStateOf<List<ObjectTreeNode>>(emptyList())
        private set

    /**
     * 当前展开着的树节点 id。
     *
     * ## 为什么展开态也归会话管
     *
     * 因为**会话比界面活得久**：顶层切到「连接管理」再切回来，
     * `AnalysisScreen` 会离开组合又重新进入，而 [AnalysisSession] 是
     * `remember(engine)` 建的，一直都在。
     *
     * 展开态放在界面侧的话，用户「展开树 → 去连个库 → 回来」会发现树又缩回去了 ——
     * 那不是「界面没保存」，是**放错了地方**。
     */
    var expandedNodeIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 树里正有节点在加载 —— 界面据此显示「加载中」。 */
    var treeLoading by mutableStateOf(false)
        private set

    /** 树加载失败的原因；`null` = 没有错误。 */
    var treeError by mutableStateOf<String?>(null)
        private set

    /**
     * 登记在册的数据源名（= 它们在工作区里占的 schema 名）。
     *
     * ## 为什么界面需要它
     *
     * 右键菜单的「删除数据源」只对**真正登记在册**的源出现 ——
     * 工作区自己的 `main`、用户在 DuckDB 里手工建的 schema 都不是数据源：
     * 删前者是「删掉分析模式的计算引擎」，删后者是「删掉用户自己建的东西」。
     *
     * 判据是**登记表**而不是「树上有没有这一行 schema」：树上那一行只说明
     * 「工作区里有个叫这个名字的 schema」，而登记表才说明
     * 「它是本应用登记过的数据源」。
     */
    var deletableSourceNames by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 最近一次「删除数据源」的**结果**；`null` = 还没删过。
     *
     * ## 为什么与 [treeError] 分开
     *
     * 树脚那行说的是「**加载**失败」。删除失败不是加载失败 ——
     * 两件事塞进同一句话，用户读到的是「加载失败：删除数据源失败：xxx」，
     * 驴唇不对马嘴。
     *
     * ## 为什么结果**两种都要**
     *
     * 失败必须看得见（用户点了，却什么都没发生）。
     * 成功也要说一声：删除是**不可撤销**的（整根 schema 连同里面的表一起没了），
     * 用户需要确认「刚才那一下真的生效了」，而树的变化有时会被滚动挡住。
     */
    var deleteSourceOutcome by mutableStateOf<DeleteSourceOutcome?>(null)
        private set

    // ──────────────── 报表设计 ────────────────

    /**
     * 分析模式当前显示**哪个视图**（工作台 / 报表设计）。
     *
     * ## 为什么住在会话里而不是屏幕层
     *
     * 切到别的顶层页再切回来，用户希望自己**还在报表设计里**。
     * 而屏幕层的 `remember` 活不过这次切页（顶层页切换会重建整棵组合）。
     * 这与 [sql] / [result] 是同一类东西 —— 跨页存活的分析模式状态。
     */
    var analysisMode by mutableStateOf(AnalysisMode.WORKBENCH)
        private set

    /**
     * 全部报表。
     *
     * ## 它**落盘**（v2.40 起）
     *
     * 落在**独立**的 `~/.config/sundays/reports.json`（见 [ReportStorage]）——
     * 不在工作区 DuckDB 里（那是**用户自己的**数据），也不与连接清单挤在一起。
     *
     * 但有一件事从 v2.37 起就没变：部件的形状（**一条 SQL + 一种图表类型**）
     * 从此**不许改** —— 用户已经搭好的报表会因为形状变化而集体作废。
     */
    var reports by mutableStateOf<List<AnalysisReport>>(emptyList())
        private set

    /** 当前选中的报表 id；`null` = 还没选。 */
    var selectedReportId by mutableStateOf<String?>(null)
        private set

    /**
     * 部件 id → 该部件的数据。
     *
     * 只存**跑过**的部件 —— 没跑过的部件在界面上是「还没有数据」，
     * 而不是一张空表（空表会被误读成「查出来就是空的」）。
     */
    var widgetResults by mutableStateOf<Map<String, AnalysisResult>>(emptyMap())
        private set

    /** 编辑页里那条 SQL 的**样例数据**；`null` = 还没预览。 */
    var previewResult by mutableStateOf<AnalysisResult?>(null)
        private set

    /** 正在取样例数据 —— 界面据此禁用「预览」按钮。 */
    var previewRunning by mutableStateOf(false)
        private set

    /** 正在重新取整张报表的数据 —— 界面据此禁用「刷新」按钮。 */
    var refreshRunning by mutableStateOf(false)
        private set

    /**
     * 正在刷新的**单个**部件 id —— 界面据此禁用那个部件的刷新按钮。
     *
     * ## 为什么是**一组**而不是一个
     *
     * 一个一个地刷时，理论上可以有多个部件同时在刷。而它们**必须互斥** ——
     * 工作区是**一个** DuckDB 文件，并发查询会互相抢锁。
     * 所以这里记的是「**正在刷的那些**」，判定「忙不忙」时看它非空即可。
     */
    var refreshingWidgetIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 正在导出报表 —— 界面据此禁用「导出」按钮。 */
    var exportRunning by mutableStateOf(false)
        private set

    /**
     * 报表设计页顶部那条状态提示 —— **最新**的那件事。
     *
     * ## 为什么要分三层
     *
     * - [persistError] —— **保存失败**：数据正在丢，最要紧，压过一切；
     * - 导出**正在进行** —— 说的是「**在等什么**」；
     * - [lastEvent] —— 最近一件**已发生**的事（导出结果、启动时的读文件提醒）；
     * - 都没有 → 没有提示。
     *
     * 分成「保存错误」与「其它事件」两层，是因为它们的**寿命**不同：
     * 保存错误会**一直**挂着（直到下一次保存成功），而导出结果只代表那一次。
     *
     * ## 为什么「正在导出」要压过「上一次的结果」
     *
     * 导出要**重跑每个部件的 SQL** —— 那不是一瞬间的事。
     * 按钮灰掉只说明「点不动了」，没告诉用户**正在忙**；
     * 而上一条「已导出到 …」还挂在屏上，会让人以为**导出已经结束了**。
     */
    val reportStatus: ReportStatus?
        get() {
            val error = persistError
            if (error != null) return ReportStatus.Error(error)
            if (exportRunning) return ReportStatus.Info(Msg.text(StrKey.REPORT_EXPORT_RUNNING))
            return lastEvent
        }

    /** 保存失败的原因；`null` = 上次保存成功（或还没保存过）。 */
    private var persistError by mutableStateOf<String?>(null)

    /** 最近发生的一件事（导出结果 / 读文件提醒）。 */
    private var lastEvent by mutableStateOf<ReportStatus?>(null)

    /**
     * 落盘**停用**了 —— 文件读不出来**而且改名也失败**。
     *
     * ## 为什么这时必须停用
     *
     * 原文件还在，而且我们**读不出来**它。再往它上面写，就是把用户的
     * 报表与「半份新内容」混在一起 —— 下一次它要是读出来了，
     * 读到的是**新写的那几行**加上**旧的坏内容**。
     *
     * 停用是保守的：这一轮的改动存不下来，但**原文件一个字节都没动**。
     */
    private var persistDisabled = false

    /** 报表 id 的计数器 —— 让每张报表有一个**唯一且稳定**的 id。 */
    private val reportCounter = AtomicLong(0)

    /** 部件 id 的计数器 —— 同上。 */
    private val widgetCounter = AtomicLong(0)

    // 启动时把磁盘上的报表读进来 —— 这个 `init` 块必须**声明在**两个计数器**之后**
    // （Kotlin 的初始化顺序 = 声明顺序；放在类开头的话计数器还是 0，
    //  新发的 id 会与磁盘上的撞上）。为什么是这三种结果，见 [loadReports]。
    init {
        loadReports()
    }

    /** 正在加载的节点 id —— 用来挡住**重复**加载（连点两下扭子）。 */
    private val loadingNodeIds = mutableSetOf<String>()

    /**
     * 同步可分析的远端源。
     *
     * 源**不在分析树里**（树只显示 DuckDB 工作区那一支，见 [buildTreeRoots]），
     * 但它们仍然是**拉取**的来源 —— 拉取要靠源 id 找到引擎侧的连接配置。
     * 所以这里留着绑定，只是不再拿它们去拼树。
     */
    fun setSources(bindings: List<AnalysisSourceBinding>) {
        sourceBindings = bindings.associateBy { it.item.id }
        refreshTree()
    }

    /**
     * 重建树的**根**（工作区的各个 schema），但保住已经加载出来的子节点。
     *
     * ## 为什么要「重建根但保留子节点」
     *
     * 工作区里多了一个数据源（或者少了一个）就要重建根 —— 否则树里会留着
     * 已经删掉的源。但**直接重建**会把用户展开的节点连同它的子节点一起抹掉：
     * 加一个源，用户的树就缩回去了。
     *
     * 所以这里只换「根节点自己」（名字、方言标签），
     * 已经加载出来的 `children` 与 `loaded` 原样带走。
     */
    private fun refreshTree(): Job {
        // 工作区那一根**同步**给出去 —— 它不需要查任何东西，
        // 而树的第一行不该因为「去列一下有哪些源」而晚半秒才出现。
        val previous = treeRoots.associateBy { it.id }
        val workspace = workspaceRoot()
        treeRoots =
            listOf(
                previous[workspace.id]?.let { old ->
                    workspace.copy(children = old.children, loaded = old.loaded)
                } ?: workspace,
            )
        return scope.launch {
            try {
                val sources = listSourceSchemas()
                if (sources.isEmpty()) return@launch
                treeRoots = treeRoots + sources
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 根列不出来（工作区文件还读不出来）**不是**致命错误 ——
                // 树上至少还有「本地工作区」那一行。
                treeError = e.message
            }
        }
    }

    /**
     * 树的根节点 —— 工作区的各个 **schema**。
     *
     * ## 为什么根是 schema，而不是「工作区」一个
     *
     * 「添加数据源」把每个数据源的表放进**自己的 schema** ——
     * 那样两个源里有同名表也不会撞，而且 DuckDB 自己就是按 schema 分组的。
     * 于是树直接照着 DuckDB 的目录来：**根 = schema，表 = 表，字段 = 列**。
     *
     * ## 为什么 `main` **恒在**第一位
     *
     * 工作区自己的表住在 `main` 里。它**恒在**，哪怕工作区文件此刻还读不出来
     * （第一次用时目录都还没建）—— 树的第一行不该因为「文件还没建」就空着。
     */
    private fun workspaceRoot(): ObjectTreeNode =
        ObjectTreeNode(
            id = ANALYSIS_WORKSPACE_NODE_ID,
            label = WORKSPACE_SCHEMA,
            labelKey = StrKey.ANALYSIS_LOCAL_WORKSPACE,
            kind = ObjectTreeKind.DATABASE,
            badge = "DuckDB",
            // ⚠️ **必须是「还没加载」** —— 工作区的表是**懒加载**的：
            // 展开它的时候才去查。标成「已加载」的话，展开动作会以为
            // 「查过了，不用再查」，于是树上永远一张表都没有。
            loaded = false,
        )

    /**
     * 工作区里**除 `main` 之外**的 schema —— 每个数据源占一个。
     *
     * 登记表自己的 schema（`sundays`）**不进树** ——
     * 它是应用的内务，不是用户的数据源。
     */
    private suspend fun listSourceSchemas(): List<ObjectTreeNode> {
        // 登记表**先**读：它决定「哪些 schema 是数据源」——
        // 而「哪些能删」正是靠这个集合判定的（见 [deletableSourceNames]）。
        val registered = loadRegisteredSources().associateBy { it.schemaName }
        // ⚠️ `main` 永远排除在外：它是分析模式自带的计算引擎，不是数据源。
        deletableSourceNames = registered.keys - WORKSPACE_SCHEMA
        val schemas =
            runWorkspaceQuery(
                "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name NOT IN ('main', 'information_schema', 'pg_catalog', '$REGISTRY_SCHEMA') " +
                    "ORDER BY schema_name",
            )
        if (schemas.failed) return emptyList()
        return schemas.rows
            .mapNotNull { it.firstOrNull() }
            .filter { it.isNotBlank() }
            .map { schema ->
                val source = registered[schema]
                ObjectTreeNode(
                    id = analysisSchemaNodeId(schema),
                    label = schema,
                    kind = ObjectTreeKind.DATABASE,
                    badge = source?.dialect,
                    loaded = false,
                )
            }
    }

    /** 工作区文件名 —— 只作诊断用（界面上工作区那一行显示的是固定名）。 */
    private fun workspaceFileName(): String {
        val name = Paths.get(workspaceFile).fileName
        return name?.toString() ?: workspaceFile
    }

    /**
     * 展开 / 收起一个**分组**节点。
     *
     * 展开时如果子节点还没查过，就顺手查一次。
     *
     * @param node 被点的节点
     * @return 启动的加载任务；`null` = 没加载（收起了，或已经加载过）
     */
    fun toggleNode(node: ObjectTreeNode): Job? {
        if (node.id in expandedNodeIds) {
            expandedNodeIds = expandedNodeIds - node.id
            return null
        }
        expandedNodeIds = expandedNodeIds + node.id
        return if (!node.loaded) loadChildren(node) else null
    }

    /**
     * 加载某个节点的子节点，并**按 id 替换**那个节点。
     *
     * @param node 要展开的节点
     * @return 启动的任务；`null` = 不用查（正在查，或已经查过了）
     */
    fun loadChildren(node: ObjectTreeNode): Job? {
        if (node.loaded) return null
        if (!loadingNodeIds.add(node.id)) return null
        treeLoading = true
        treeError = null
        // 加载中与失败都**落在那个节点上**，不是落在整棵树的脚下 ——
        // 共享树组件在节点脚下显示它们，用户一眼就知道是哪一个节点出了问题。
        treeRoots = replaceObjectTreeNode(treeRoots, node.id) { it.copy(loading = true, error = null) }
        return scope.launch {
            try {
                val children = fetchChildren(node)
                treeRoots =
                    replaceObjectTreeNode(treeRoots, node.id) {
                        it.copy(children = children, loaded = true, loading = false, error = null)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 加载失败**不算**「这个节点是空的」—— 保持 `loaded = false`，
                // 用户再点一次就能重试；错误原文摆在那个节点脚下。
                treeError = e.message ?: "加载失败"
                treeRoots =
                    replaceObjectTreeNode(treeRoots, node.id) {
                        it.copy(loading = false, error = e.message ?: "加载失败")
                    }
            } finally {
                loadingNodeIds.remove(node.id)
                treeLoading = loadingNodeIds.isNotEmpty()
            }
        }
    }

    /**
     * 按节点的 kind 决定「该查什么」。
     *
     * 两层，各查各的：
     * - 容器（schema）→ 它的表
     * - 表 → 它的字段
     *
     * 字段这一层是**后加**的：分析页的树以前到表为止，用户看不到
     * 「这张表有哪些列」，而这正是写分析 SQL 时最需要的对照信息。
     * 现在它与浏览模式的对象树**完全同形**（库 → 表 → 字段）。
     */
    private suspend fun fetchChildren(node: ObjectTreeNode): List<ObjectTreeNode> =
        when (node.kind) {
            ObjectTreeKind.DATABASE -> listWorkspaceTables(node.label)
            ObjectTreeKind.TABLE -> listWorkspaceColumns(node.parentLabel.orEmpty(), node.label)
            ObjectTreeKind.COLUMN -> emptyList()
        }

    /**
     * 一个 schema 里的表。
     *
     * 走的是**同一套** `ANALYSIS.LIST_TABLES` 路由，只是连接换成了工作区 ——
     * 不另写一条「列工作区表」的通路，免得两处的分派规则各说各话。
     *
     * @param schema 工作区里的 schema（`main` = 工作区自己的表；别的 = 某个数据源）
     */
    private suspend fun listWorkspaceTables(schema: String): List<ObjectTreeNode> {
        val response =
            engine.invoke(workspaceConnection()) {
                category = Category.ANALYSIS
                action = Action.LIST_TABLES
                analysisRequest =
                    analysisRequest {
                        listTables = analysisListTablesRequest { namespace = schema }
                    }
            }
        if (!response.success) {
            throw IllegalStateException(response.error.ifBlank { "列工作区表失败" })
        }
        return response.analysis.listTables.itemsList.map { item ->
            ObjectTreeNode(
                id = analysisTableNodeId(schema, item.name),
                label = item.name,
                kind = ObjectTreeKind.TABLE,
                // 表行的「上级」是它所在的 schema —— 列字段时要用。
                parentLabel = schema,
                namespace = schema,
                // 表的**字段**也是懒加载的 —— 展开这张表的时候才去查。
                // 标成「已加载」的话，展开动作会以为「查过了」，字段就永远出不来。
                loaded = false,
            )
        }
    }

    /**
     * 一张工作区表的**字段**。
     *
     * 走的是 `ANALYSIS.LIST_COLUMNS` 路由 —— 与浏览模式列字段是**同一条**引擎路由，
     * 只是连接换成了工作区。所以两棵树里的字段信息来自同一处，
     * 不会出现「浏览模式说有三个字段、分析模式说有两个」。
     *
     * @param schema 表所在的 schema
     * @param table 表名
     */
    private suspend fun listWorkspaceColumns(
        schema: String,
        table: String,
    ): List<ObjectTreeNode> {
        val response =
            engine.invoke(workspaceConnection()) {
                category = Category.ANALYSIS
                action = Action.LIST_COLUMNS
                analysisRequest =
                    analysisRequest {
                        listColumns =
                            analysisListColumnsRequest {
                                namespace = schema
                                this.table = table
                            }
                    }
            }
        if (!response.success) {
            throw IllegalStateException(response.error.ifBlank { "列字段失败" })
        }
        return response.analysis.listColumns.itemsList.map { col ->
            ObjectTreeNode(
                id = analysisColumnNodeId(schema, table, col.name),
                label = col.name,
                kind = ObjectTreeKind.COLUMN,
                // 字段行的「上级」是表 —— 点它时要把 `表.字段` 一起加进编辑器。
                parentLabel = table,
                // 而它的命名空间是**表所在的那个 schema** ——
                // 写引用时两样都要（`"库"."表"."列"`）。
                namespace = schema,
                column =
                    ObjectTreeColumn(
                        name = col.name,
                        type = columnTypeText(col.type, col.size),
                        primaryKey = col.isPrimaryKey,
                        autoIncrement = col.autoIncrement,
                        // proto3 的 `optional bool nullable` 有 presence 语义：
                        // 没给就是 null，按「默认可空」处理，与引擎的 mapper 一致。
                        nullable = if (col.hasNullable()) col.nullable else true,
                    ),
            )
        }
    }

    /**
     * 拉取成功后刷新工作区那一支 —— 新落地的 staging 表要立刻出现在树上。
     *
     * 刷的是**工作区节点**，不是整棵树：远端那一支没动过，重查一遍是白花钱。
     */
    private fun refreshWorkspaceChildren() {
        val workspace = findObjectTreeNode(treeRoots, ANALYSIS_WORKSPACE_NODE_ID) ?: return
        scope.launch {
            try {
                val children = listWorkspaceTables(WORKSPACE_SCHEMA)
                treeRoots =
                    replaceObjectTreeNode(treeRoots, workspace.id) {
                        it.copy(children = children, loaded = true, loading = false)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败**不**报错：表已经拉进工作区了，只是树没刷上。
                // 这时候报一个错会让用户以为拉取失败了 —— 那比树少刷新一次严重得多。
                treeError = e.message
            }
        }
    }

    // ──────────────── 数据源登记（工作区 DuckDB 里） ────────────────

    /**
     * 预览远端 —— 用**对应驱动**列出它的库与表。
     *
     * ## 为什么**先**用驱动预览，而不是直接用 DuckDB 扩展
     *
     * DuckDB 的扩展（`mysql_scanner`）列的是它**挂上去之后**能看见的东西 ——
     * 而挂载是**连接级**的：挂上去、查完、还得摘掉（摘早了表会整张消失，
     * 见 [NativeExtensionScanner]）。而「这个服务器上有哪些库、哪些表」
     * 用驱动问一句就有，**还不碰工作区**。
     *
     * 所以分工是：**驱动负责预览，DuckDB 负责接入**。
     *
     * @param draft 待预览的远端源
     * @return 每个库 + 它的表
     */
    suspend fun previewRemote(draft: AnalysisSourceDraft): List<AnalysisRemotePreview> {
        val config = remoteEngineConfig(draft)
        return listRemoteNamespaces(config).map { namespace ->
            AnalysisRemotePreview(namespace = namespace, tables = listRemoteTables(config, namespace))
        }
    }

    /**
     * 把一个数据源接进工作区。
     *
     * ## 两条路分别怎么接
     *
     * | 源 | 怎么接 | 结果 |
     * |---|---|---|
     * | 远程数据库 | **DuckDB 官方扩展优先**（`ATTACH` → 建表 → 摘除），走不通就退回 JDBC 表函数 | 工作区里的一张**表** |
     * | 文件 / 文件夹 | DuckDB 自己的读法（`read_parquet` / `read_csv_auto` / `read_json_auto` / `read_xlsx`） | 工作区里的一个**视图**（文件改了，查询跟着变） |
     *
     * ## 源占**自己的 schema**
     *
     * 那样两个源里有同名表也不会撞，而且 DuckDB 自己就是按 schema 分组的 ——
     * 树直接照着它的目录来（根 = schema，表 = 表，字段 = 列）。
     *
     * @param draft 对话框填出来的源
     * @param tables 远程源里勾中的表（文件源为空）
     * @param onProgress 拉表时的进度回调
     * @param onFailure 失败时的原因（说清为什么，别让用户对着「没反应」发呆）
     * @param onSuccess 成功时的回调（对话框据此关掉）
     * @return 启动的任务；`null` = 没启动（源没有名字）
     */
    fun addSource(
        draft: AnalysisSourceDraft,
        tables: List<AnalysisSelectedTable> = emptyList(),
        onProgress: suspend (PullProgress) -> Unit = {},
        onFailure: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ): Job? {
        val name = draft.name.trim()
        if (name.isBlank()) return null
        return scope.launch {
            try {
                ensureRegistry()
                runWorkspaceStatement("CREATE SCHEMA IF NOT EXISTS ${quoteIdent(name)}")
                when (draft.kind) {
                    AnalysisSourceKind.FILE -> addFileSource(draft, name)
                    AnalysisSourceKind.REMOTE -> addRemoteSource(draft, name, tables, onProgress)
                }
                registerSource(draft, name)
                // **等**树刷新完 —— 否则「加完源回头看树」可能还差半秒，
                // 而那半秒里树上没有它（用户会以为没加上）。
                refreshTree().join()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败要说清为什么 —— 用户刚填了一堆参数，
                // 最怕的是「点了没反应」。
                onFailure(e.message ?: "添加数据源失败")
            }
        }
    }

    /**
     * 文件 / 文件夹 → 工作区里的一个**视图**。
     *
     * ## 为什么是视图而不是表
     *
     * 文件会变（今天导出的 CSV，明天是新的）。视图每次查询都重读文件 ——
     * 用户看到的是**最新的那份**，不用「重新添加一次数据源」。
     */
    private suspend fun addFileSource(
        draft: AnalysisSourceDraft,
        schema: String,
    ) {
        val path = draft.path.trim()
        if (path.isBlank()) throw IllegalStateException("路径不能为空")
        val format =
            draft.format
                ?: AnalysisFileFormat.fromPath(path)
                ?: throw IllegalStateException("看不出这是什么格式，请手动选一个")
        // 视图的名字取路径的最后一段（去掉扩展名）——
        // 「orders.parquet」在树里显示成「orders」更像一张表。
        //
        // ⚠️ 分隔符**两种**都要认：Windows 的路径用 `\`，而用户从
        // 别的工具里复制过来的路径可能是 `/`。只认一种的话，
        // 视图名会变成一整条路径（`C:\Users\...\orders`）。
        val trimmed = path.trimEnd('/', '\\')
        val leaf = trimmed.substringAfterLast('/').substringAfterLast('\\').trimEnd('\\')
        val viewName = leaf.substringBeforeLast('.', leaf)
        runWorkspaceStatement(
            "CREATE OR REPLACE VIEW ${quoteIdent(schema)}.${quoteIdent(viewName)} AS " +
                // ⚠️ `escapeLiteral` 已经**含**两侧的引号 —— 外面不要再加一层，
                // 否则 SQL 里会变成 `''路径''`（解析成「空串 + 路径」）。
                "SELECT * FROM ${format.reader}(${escapeLiteral(path)})",
        )
    }

    /**
     * 远程源 → 工作区里的**表**（一张一张来）。
     *
     * 走的是**扩展优先**的拉取（见 `AnalysisAccessMode`）：
     * DuckDB 官方扩展能直连就直连（少一次拷贝、类型用 DuckDB 官方规则），
     * 走不通就退回纯 Java 表函数 —— 用户看到的是「成功」，不是「你的扩展没装」。
     */
    private suspend fun addRemoteSource(
        draft: AnalysisSourceDraft,
        schema: String,
        tables: List<AnalysisSelectedTable>,
        onProgress: suspend (PullProgress) -> Unit,
    ) {
        val source = remoteEngineConfig(draft)
        for (selected in tables) {
            puller.pull(
                requestId = "analysis-source-${System.currentTimeMillis()}-${pullCounter.incrementAndGet()}",
                source = source,
                workspace = workspaceConnection(),
                workspacePath = workspacePath,
                namespace = selected.namespace,
                table = selected.table,
                targetSchema = schema,
                // 落点用**原名** —— 那个源里的表就叫那个名字，
                // 而「拉取远端表」那条老路落的是 `staging_<源库>_<表>`。
                targetTable = selected.table,
                accessMode = AnalysisAccessMode.ANALYSIS_ACCESS_MODE_EXTENSION_FIRST,
                onProgress = onProgress,
            )
        }
    }

    /** 把这个源登记在册（下次启动还在）。 */
    private suspend fun registerSource(
        draft: AnalysisSourceDraft,
        schema: String,
    ) {
        runWorkspaceStatement(
            "DELETE FROM ${quoteIdent(REGISTRY_SCHEMA)}.${quoteIdent(REGISTRY_TABLE)} " +
                "WHERE name = ${escapeLiteral(schema)}",
        )
        runWorkspaceStatement(
            "INSERT INTO ${quoteIdent(REGISTRY_SCHEMA)}.${quoteIdent(REGISTRY_TABLE)} " +
                "(name, kind, dialect, jdbc_url, db_name, user_name, password, path, format, created_at) " +
                "VALUES (${escapeLiteral(schema)}, ${escapeLiteral(draft.kind.name)}, " +
                "${escapeLiteral(draft.dialect.name)}, ${escapeLiteral(draft.jdbcUrl)}, " +
                "${escapeLiteral(draft.database)}, ${escapeLiteral(draft.user)}, " +
                "${escapeLiteral(draft.password)}, ${escapeLiteral(draft.path)}, " +
                "${escapeLiteral(draft.format?.name.orEmpty())}, current_timestamp)",
        )
    }

    /**
     * 删掉一个数据源 —— 工作区里那个 schema 与登记表里那一行。
     *
     * ## 为什么是**两件事**
     *
     * 数据源在磁盘上占**两处**：
     *
     * | 在哪 | 是什么 | 删掉它的后果 |
     * |---|---|---|
     * | 工作区 DuckDB | 一个 schema（`CREATE SCHEMA <name>`） | 里面那些拉进来的表一起没了 |
     * | 登记表（`sundays` schema） | 一行记录（名字 / 方言 / 连接信息） | 下次启动它不再出现 |
     *
     * 只删一处的话：只删 schema → 登记表还留着它，重启后它又「回来」了
     * （而表已经没了 —— 用户看到的是一个**空**的数据源）；
     * 只删登记 → schema 还在、表也还在，但树上多了一根**没有来由**的 schema。
     *
     * ## 顺序为什么是「先 DROP 再 DELETE」
     *
     * 反过来的话，`DROP` 失败就留下一个「有 schema、没登记」的孤儿 ——
     * 用户在树上看得见它，却**没法从菜单里删掉**（菜单只给登记在册的源）。
     * 先 DROP 的话，最坏情况是「schema 没了、登记还在」—— 那只是下次启动时
     * 一次没人管的清理（`listSourceSchemas` 列的是**实际存在的** schema，
     * 登记里的残留不会在树上冒出一根）。
     *
     * ## 为什么删之前要**核对清单**
     *
     * 界面已经保证「菜单只给登记在册的源」，但界面那一层回答的是
     * 「用户点了什么」，这一层回答的是「到底允许删什么」。
     * 核对一次，两边对不上时**这里**说了算 —— 宁可拒绝，
     * 也不要删掉一个不该删的 schema。
     *
     * @param schema 要删的数据源（= 它在工作区里占的 schema 名）
     * @return 启动的任务；`null` = 没启动（名字是空的 / 它不是可删的数据源）
     */
    fun removeSource(schema: String): Job? {
        val name = schema.trim()
        if (name.isBlank()) return null
        // ⚠️ 工作区**不能**删 —— 它是分析模式自带的计算引擎（主设计 M4 要求它可写）。
        // 判据放在**动作这一层**而不是只靠界面：界面那一层是「用户点了什么」，
        // 这一层是「到底允许删什么」。
        if (name == WORKSPACE_SCHEMA) {
            deleteSourceOutcome =
                DeleteSourceOutcome.Failed(Msg.format(StrKey.ANALYSIS_DELETE_SOURCE_REFUSED, name))
            return null
        }
        // 上一次的结果先清掉 —— 否则这次成功了，旧的红字还挂着，
        // 用户会以为「还是没删掉」。
        deleteSourceOutcome = null
        return scope.launch {
            try {
                val registered = queryRegisteredSources()
                // 读不出来 / 不在清单里 —— 两种都**拒绝**。
                // 前者是真「清单读不出来」，后者是「它本来就不是数据源」；
                // 两者对用户的结论是一样的（不能删），所以用同一句话。
                if (registered == null || registered.none { it.schemaName == name }) {
                    deleteSourceOutcome =
                        DeleteSourceOutcome.Failed(Msg.format(StrKey.ANALYSIS_DELETE_SOURCE_REFUSED, name))
                    return@launch
                }
                runWorkspaceStatement("DROP SCHEMA IF EXISTS ${quoteIdent(name)} CASCADE")
                runWorkspaceStatement(
                    "DELETE FROM ${quoteIdent(REGISTRY_SCHEMA)}.${quoteIdent(REGISTRY_TABLE)} " +
                        "WHERE name = ${escapeLiteral(name)}",
                )
                // 那个 schema 没了，它底下的展开态也就没有意义了 ——
                // 留着的话，下次有**同名**的数据源加进来会被自动展开（用户没要求过）。
                expandedNodeIds = expandedNodeIds - analysisSchemaNodeId(name)
                // **等**树刷新完 —— 否则「删完源回头看树」可能还差半秒，
                // 而那半秒里它还在树上（用户会以为没删掉）。
                refreshTree().join()
                deleteSourceOutcome = DeleteSourceOutcome.Deleted(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败要说清为什么 —— 用户刚点了「删除」，
                // 最怕的是「点了没反应」。
                deleteSourceOutcome =
                    DeleteSourceOutcome.Failed(e.message ?: Msg.format(StrKey.ANALYSIS_DELETE_SOURCE_FAILED, name))
            }
        }
    }

    /** 登记在册的数据源。 */
    suspend fun registeredSources(): List<AnalysisRegisteredSource> = loadRegisteredSources()

    /** 登记在册的数据源（读不出来时是**空列表**）。 */
    private suspend fun loadRegisteredSources(): List<AnalysisRegisteredSource> = queryRegisteredSources().orEmpty()

    /**
     * 登记表的内容 —— `null` = **读不出来**（工作区打不开 / 表还没建）。
     *
     * ## 为什么单独分出一个「可空」的版本
     *
     * 「清单是空的」与「清单读不出来」是**两件事**：
     * 前者 = 用户一个数据源都没加；后者 = 工作区本身有问题。
     * 合并成空列表的话，「读不出来」会被当成「没数据源」——
     * 而「删数据源」的第一步正是**核对清单**，读不出来时它会拒绝删除。
     * 这时若拿到的是空列表，拒绝的理由就会变成「它不在清单里」，
     * **说错了原因**（真因是「清单读不出来」）。
     */
    private suspend fun queryRegisteredSources(): List<AnalysisRegisteredSource>? {
        val result =
            runWorkspaceQuery(
                "SELECT name, kind, dialect, jdbc_url, db_name, user_name, password, path, format " +
                    "FROM ${quoteIdent(REGISTRY_SCHEMA)}.${quoteIdent(REGISTRY_TABLE)} ORDER BY created_at",
            )
        if (result.failed) return null
        return result.rows.mapNotNull { row ->
            val name = row.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AnalysisRegisteredSource(
                name = name,
                kind =
                    if (row.getOrNull(1) == AnalysisSourceKind.FILE.name) {
                        AnalysisSourceKind.FILE
                    } else {
                        AnalysisSourceKind.REMOTE
                    },
                dialect = row.getOrNull(2).orEmpty(),
                jdbcUrl = row.getOrNull(3).orEmpty(),
                database = row.getOrNull(4).orEmpty(),
                user = row.getOrNull(5).orEmpty(),
                password = row.getOrNull(6).orEmpty(),
                path = row.getOrNull(7).orEmpty(),
                format = row.getOrNull(8).orEmpty(),
            )
        }
    }

    /** 确保登记表在 —— 幂等（工作区文件可能被用户删了）。 */
    private suspend fun ensureRegistry() {
        runWorkspaceStatement("CREATE SCHEMA IF NOT EXISTS ${quoteIdent(REGISTRY_SCHEMA)}")
        runWorkspaceStatement(
            "CREATE TABLE IF NOT EXISTS ${quoteIdent(REGISTRY_SCHEMA)}.${quoteIdent(REGISTRY_TABLE)} (" +
                "name VARCHAR, kind VARCHAR, dialect VARCHAR, jdbc_url VARCHAR, db_name VARCHAR, " +
                "user_name VARCHAR, password VARCHAR, path VARCHAR, format VARCHAR, created_at TIMESTAMP)",
        )
    }

    /** 在工作区上跑一句查询 —— 失败收成**结果**（带错误），不抛异常。 */
    private suspend fun runWorkspaceQuery(sql: String): AnalysisResult {
        val dirError = ensureWorkspaceDir(workspacePath)
        if (dirError != null) return AnalysisResult(error = dirError)
        return runAnalysisQuery(engine, workspaceConnection(), sql)
    }

    /** 在工作区上跑一句语句 —— **失败就抛**（调用点要的是「要么成，要么说清为什么」）。 */
    private suspend fun runWorkspaceStatement(sql: String) {
        val result = runWorkspaceQuery(sql)
        if (result.failed) throw IllegalStateException(result.error ?: "工作区执行失败")
    }

    /** 远端源的引擎配置 —— **恒为只读**（分析模式对远端只有读的需求）。 */
    private fun remoteEngineConfig(draft: AnalysisSourceDraft): ConnectionConfig =
        connectionConfig {
            driver = draft.dialect.engineDriverName
            jdbcUrl = draft.jdbcUrl
            user = draft.user
            password = draft.password
            if (draft.database.isNotBlank()) database = draft.database
            readOnly = true
        }

    /** 远端的命名空间（MySQL 上是库，PG 上是 schema）。 */
    private suspend fun listRemoteNamespaces(config: ConnectionConfig): List<String> {
        val response =
            engine.invoke(config) {
                category = Category.ANALYSIS
                action = Action.LIST_SOURCES
                analysisRequest = analysisRequest { listSources = analysisListSourcesRequest { } }
            }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "列库失败" })
        return response.analysis.listSources.itemsList
    }

    /** 某个命名空间下的表。 */
    private suspend fun listRemoteTables(
        config: ConnectionConfig,
        namespace: String,
    ): List<String> {
        val response =
            engine.invoke(config) {
                category = Category.ANALYSIS
                action = Action.LIST_TABLES
                analysisRequest =
                    analysisRequest {
                        listTables = analysisListTablesRequest { this.namespace = namespace }
                    }
            }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "列表失败" })
        return response.analysis.listTables.itemsList.map { item ->
            item.name
        }
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
    fun sourceConfig(id: String): ConnectionConfig? {
        // 拆成两段而不是一条长链：一条链上的运算符超过四个就必须折行，
        // 折行之后那几行读起来比原来的单行更难核对 —— 这里拆开更清楚。
        val config = sourceBindings[id]?.engineConfig ?: return null
        return config.toBuilder().setReadOnly(true).build()
    }

    /** 远端源的方言 —— 决定命名空间的引用形式（MySQL 是库，PG 是 schema）。 */
    fun sourceDialect(id: String): DialectType? = sourceBindings[id]?.dialect

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

    // ──────────────── 报表设计 ────────────────

    /**
     * 切到**哪个视图**（工作台 / 报表设计）。
     *
     * ## 为什么不叫 `setAnalysisMode`
     *
     * 属性 `analysisMode` 的私有 setter 在 JVM 上就叫 `setAnalysisMode` ——
     * 同名方法会与它**签名冲突**（platform declaration clash），编译直接过不去。
     *
     * @param mode 目标视图
     */
    fun selectMode(mode: AnalysisMode) {
        analysisMode = mode
    }

    /**
     * 选中某张报表。
     *
     * @param id 报表 id
     */
    fun selectReport(id: String) {
        // 认不出来的 id 直接忽略 —— 界面可能拿着**刚被删掉**的报表 id 回调过来。
        if (reports.none { it.id == id }) return
        selectedReportId = id
    }

    /**
     * 新建一张报表，并选中它。
     *
     * ## 为什么新报表的**名字是空的**
     *
     * 名字里的「报表 1」那个「1」是**要翻译的词** —— 而这一层（会话）不是 Composable，
     * 取不到当前语言。所以这里只留**空名字**，由界面按它在列表里的位置显示「报表 N」。
     */
    fun createReport() {
        val id = "report-${reportCounter.incrementAndGet()}"
        reports = reports + AnalysisReport(id = id, name = "", widgets = emptyList())
        selectedReportId = id
        persistReports()
    }

    /**
     * 重命名报表。
     *
     * @param id 报表 id
     * @param name 新名字
     */
    fun renameReport(
        id: String,
        name: String,
    ) {
        reports = reports.map { report -> if (report.id == id) report.copy(name = name.trim()) else report }
        persistReports()
    }

    /**
     * 删除报表 —— 连同它的部件与它们已经取到的数据。
     *
     * 删掉报表却不删它的部件数据，会留下一堆**永远没人引用**的查询结果
     * （地图越攒越大，而界面上再也看不到它们）。
     *
     * @param id 报表 id
     */
    fun deleteReport(id: String) {
        val removed = reports.firstOrNull { it.id == id } ?: return
        reports = reports.filterNot { it.id == id }
        widgetResults = widgetResults - removed.widgets.map { it.id }.toSet()
        // 删掉的正好是当前选中的那张 —— 顺手选中剩下的第一张，
        // 不然画布会突然空掉，用户得自己再点一次。
        if (selectedReportId == id) {
            selectedReportId = reports.firstOrNull()?.id
        }
        persistReports()
    }

    /**
     * 保存部件。
     *
     * @param reportId 属于哪张报表
     * @param widget 部件（**新建时它的 id 是空的** —— 见 `newReportWidgetDraft`）
     * @param isNew 是新增还是改已有的
     */
    fun saveWidget(
        reportId: String,
        widget: ReportWidget,
        isNew: Boolean,
    ) {
        val saved =
            if (isNew) {
                widget.copy(id = "widget-${widgetCounter.incrementAndGet()}")
            } else {
                widget
            }
        // SQL 改了 → 已经取到的数据**不再对应**这条 SQL，必须丢掉。
        //
        // 为什么不能留着：画布上显示的那张图是**旧 SQL** 跑出来的，
        // 而用户刚改完 SQL 正是要确认「新 SQL 对不对」。
        // 留着旧数据 = 让他对着旧结果确认新查询 —— 那比没有数据更糟。
        val previous =
            reports
                .firstOrNull { it.id == reportId }
                ?.widgets
                ?.firstOrNull { it.id == saved.id }
        if (!isNew && previous != null && previous.sql != saved.sql) {
            widgetResults = widgetResults - saved.id
        }
        reports =
            reports.map { report ->
                if (report.id != reportId) {
                    report
                } else if (isNew) {
                    report.copy(widgets = report.widgets + saved)
                } else {
                    report.copy(widgets = report.widgets.map { if (it.id == saved.id) saved else it })
                }
            }
        persistReports()
    }

    /**
     * 删除部件 —— 连同它已经取到的数据。
     *
     * @param reportId 属于哪张报表
     * @param widgetId 部件 id
     */
    fun deleteWidget(
        reportId: String,
        widgetId: String,
    ) {
        reports =
            reports.map { report ->
                if (report.id != reportId) {
                    report
                } else {
                    report.copy(widgets = report.widgets.filterNot { it.id == widgetId })
                }
            }
        widgetResults = widgetResults - widgetId
        persistReports()
    }

    /**
     * 复制一个部件 —— **插在原部件后面**。
     *
     * ## 为什么插在**后面**而不是前面
     *
     * 「复制」的直觉是「多一个一样的」，而用户接着要做的是**改那个新的**。
     * 插在后面，它就在原部件**紧挨着**的位置 —— 一眼能看出这俩是一对，
     * 而不用在长长的列表里找。
     *
     * ## 为什么**数据也复制**过去
     *
     * 复制出来的部件是**同一条 SQL** —— 它的结果与原件**一模一样**。
     * 而重跑一遍是「多一次查询」—— 工作区是**一个** DuckDB 文件，
     * 查询要排队、可能要等。
     *
     * 复制数据的前提是「SQL 一样 ⇒ 数据一样」，而这一点由
     * [saveWidget] 的那条规则守着：**SQL 一改，旧数据立刻作废**。
     *
     * @param reportId 属于哪张报表
     * @param widgetId 被复制的部件 id
     */
    fun copyWidget(
        reportId: String,
        widgetId: String,
    ) {
        val report = reports.firstOrNull { it.id == reportId } ?: return
        val index = report.widgets.indexOfFirst { it.id == widgetId }
        if (index < 0) return
        val source = report.widgets[index]
        val copy = source.copy(id = "widget-${widgetCounter.incrementAndGet()}")
        val updated = report.widgets.toMutableList().apply { add(index + 1, copy) }
        reports = reports.map { if (it.id == reportId) it.copy(widgets = updated) else it }
        widgetResults[widgetId]?.let { result -> widgetResults = widgetResults + (copy.id to result) }
        persistReports()
    }

    /**
     * 上移 / 下移一个部件。
     *
     * ## 为什么规则在 [moveReportWidget] 而不在这儿
     *
     * 「能不能挪」是**规则**，界面要用它来灰按钮、会话要用它来执行。
     * 两处各写一遍，迟早会出现「界面说不行、会话说行」的裂缝。
     * 所以规则只有一份（纯函数），两处都问它。
     *
     * @param reportId 属于哪张报表
     * @param widgetId 部件 id
     * @param move 方向
     */
    fun moveWidget(
        reportId: String,
        widgetId: String,
        move: ReportWidgetMove,
    ) {
        val report = reports.firstOrNull { it.id == reportId } ?: return
        val updated = moveReportWidget(report.widgets, widgetId, move)
        // 挪不动时清单没变 —— 不用落盘（省一次写，也省一次「文件被占用」的风险）。
        if (updated == report.widgets) return
        reports = reports.map { if (it.id == reportId) it.copy(widgets = updated) else it }
        persistReports()
    }

    /**
     * 只刷新**一个**部件 —— 只重跑它那一条 SQL。
     *
     * ## 为什么它与「刷新整张报表」**共用一把锁**
     *
     * 工作区是**一个** DuckDB 文件 —— 并发查询会互相抢锁，
     * 而且后完成的会把先完成的盖掉，用户看到的是**错配**的结果
     * （A 部件的位置上显示着 B 部件的数据）。
     *
     * @param reportId 属于哪张报表
     * @param widgetId 部件 id
     * @return 启动的任务；`null` = 没跑（工作区忙，或这个部件没有 SQL）
     */
    fun refreshWidget(
        reportId: String,
        widgetId: String,
    ): Job? {
        if (workspaceBusy()) return null
        val report = reports.firstOrNull { it.id == reportId } ?: return null
        val widget = report.widgets.firstOrNull { it.id == widgetId } ?: return null
        val statement = widget.sql.trim()
        if (statement.isEmpty()) return null
        // 同 [refreshReport]：标志**同步**置位，它就是那把锁。
        refreshingWidgetIds = refreshingWidgetIds + widgetId
        val job =
            scope.launch {
                val result = runOnWorkspace(statement)
                widgetResults = widgetResults + (widgetId to result)
            }
        job.invokeOnCompletion { refreshingWidgetIds = refreshingWidgetIds - widgetId }
        return job
    }

    /**
     * 导出报表 —— 写成 `.xlsx`。
     *
     * ## 顺序为什么是「先问路径 → 再刷新 → 再写」
     *
     * - **先问路径**：用户可以在**任何**时候取消，而且取消时一个查询都没跑。
     * - **再刷新**：导出的要是**导出那一刻**的真实数据。
     *   拿内存里那份导出，用户看到的与收到的可能不是一回事。
     * - **最后写**：写的是刚刚刷新出来的数据。
     *
     * @param report 要导出的报表
     * @return 启动的任务；`null` = 没导（工作区忙）
     */
    fun exportReport(report: AnalysisReport): Job? {
        if (workspaceBusy()) return null
        val target = reports.firstOrNull { it.id == report.id } ?: return null
        // 标志**同步**置位 —— 导出期间工作区是**被占着**的，
        // 刷新按钮与部件刷新都要因此变灰。
        exportRunning = true
        val job =
            scope.launch {
                val index = reports.indexOfFirst { it.id == target.id }
                val displayName =
                    reportDisplayNameText(
                        target.name,
                        Msg.format(StrKey.REPORT_DEFAULT_NAME, index + 1),
                    )
                val picked =
                    filePicker(
                        userHomeDir(),
                        "${ReportXlsxWriter.sanitizeFileName(displayName)}.xlsx",
                    ) ?: return@launch
                // 刷新在**弹窗之后** —— 用户取消就不用白跑一遍查询。
                refreshWidgetsOf(target)
                val file = Paths.get(picked.dir, ensureXlsxSuffix(picked.name))
                val ok = ReportXlsxWriter.write(target, widgetResults, file, displayName)
                lastEvent =
                    if (ok) {
                        ReportStatus.Info(Msg.format(StrKey.REPORT_EXPORT_DONE, file.toString()))
                    } else {
                        ReportStatus.Error(Msg.format(StrKey.REPORT_EXPORT_FAILED, file.toString()))
                    }
            }
        job.invokeOnCompletion { exportRunning = false }
        return job
    }

    /**
     * 工作区现在**忙不忙**。
     *
     * 「刷新整张报表」「刷新一个部件」「导出」三件事**互斥** ——
     * 它们都要动工作区这个**一个** DuckDB 文件。
     */
    private fun workspaceBusy(): Boolean = refreshRunning || refreshingWidgetIds.isNotEmpty() || exportRunning

    /** 用户主目录 —— 导出对话框的起始目录。 */
    private fun userHomeDir(): String = System.getProperty("user.home") ?: "."

    /** 保证文件名以 `.xlsx` 结尾 —— 用户在对话框里改了扩展名的话。 */
    private fun ensureXlsxSuffix(name: String): String = if (name.lowercase().endsWith(".xlsx")) name else "$name.xlsx"

    /**
     * 串行跑一张报表的**所有**部件 —— 每跑完一个就放出去。
     *
     * ## 为什么**串行**跑而不是并发
     *
     * 工作区是**一个** DuckDB 文件 —— 并发查询会互相抢锁，
     * 而且后完成的会把先完成的盖掉，用户看到的是**错配**的结果。
     * 串行慢一点，但每个部件拿到的都是**自己**那份。
     */
    private suspend fun refreshWidgetsOf(report: AnalysisReport) {
        report.widgets.forEach { widget ->
            val statement = widget.sql.trim()
            if (statement.isEmpty()) return@forEach
            val result = runOnWorkspace(statement)
            // 每跑完一个就放出去 —— 界面能一块一块地亮起来，
            // 而不是等最后一个跑完才一起出现。
            widgetResults = widgetResults + (widget.id to result)
        }
    }

    /**
     * 把磁盘上的报表读进来。
     *
     * ## 为什么在 `init` 块里调它
     *
     * 它要用到 [reports] 与两个计数器，而 Kotlin 的**初始化顺序**是
     * 「按声明顺序」—— 所以那个 `init` 块必须**声明在**那些属性**之后**。
     *
     * 三种结果（见 [ReportLoadResult]）：
     * - 读到了 → 用它，并把计数器推到最大 id 之后；
     * - 读不出来但**改名保留了** → 按空集继续，但**明确告诉用户**；
     * - 读不出来**而且改名也失败** → 按空集继续，并**停掉落盘**。
     */
    private fun loadReports() {
        when (val result = reportStorage.load()) {
            is ReportLoadResult.Loaded -> {
                reports = result.reports
                bumpCounters(result.reports)
                // 读完自动选中第一张 —— 否则用户打开应用看到的是**空画布**，
                // 明明磁盘上有好几张报表。
                selectedReportId = reports.firstOrNull()?.id
            }

            is ReportLoadResult.BackedUp -> {
                lastEvent =
                    ReportStatus.Warn(
                        Msg.format(StrKey.REPORT_LOAD_BACKED_UP, result.backupPath.toString()),
                    )
            }

            is ReportLoadResult.Unreadable -> {
                persistDisabled = true
                lastEvent =
                    ReportStatus.Error(
                        Msg.format(StrKey.REPORT_LOAD_FAILED, result.reason),
                    )
            }
        }
    }

    /**
     * 把当前报表**落盘**。
     *
     * ## 为什么是**同步**的
     *
     * 报表文件很小（几十 KB），一次写不到 1ms。
     * 而异步写会引出「顺序」问题：两次改动各自起一个写任务，
     * 后起的那个可能先落地 —— 文件就**退回**到旧状态。
     * 同步写没有这些问题，而且它让「保存失败」能**当场**报给用户。
     */
    private fun persistReports() {
        if (persistDisabled) return
        if (reportStorage.save(reports)) {
            persistError = null
        } else {
            persistError = Msg.format(StrKey.REPORT_SAVE_FAILED, reportStorage.path.toString())
        }
    }

    /**
     * 把两个计数器推到**已存在的最大 id** 之后。
     *
     * ## 为什么必须推
     *
     * 磁盘上的 id 是**稳定的**（重开应用还是那个 id）——
     * 而 id 是**计数器**发出来的。若不推，重开应用后新建的报表会拿到
     * `report-1`，而 `report-1` 在磁盘上**已经有一张了**。
     *
     * 于是两个报表共用一个 id：选中一个会选中两个，删一个会删掉两个，
     * 而且部件的 id 也一样 —— 数据会**串**。
     */
    private fun bumpCounters(reports: List<AnalysisReport>) {
        val reportSeq =
            reports
                .mapNotNull { it.id.removePrefix("report-").toLongOrNull() }
                .maxOrNull() ?: 0
        reportCounter.set(reportSeq)
        val widgetSeq =
            reports
                .flatMap { it.widgets }
                .mapNotNull { it.id.removePrefix("widget-").toLongOrNull() }
                .maxOrNull() ?: 0
        widgetCounter.set(widgetSeq)
    }

    /**
     * 取一条 SQL 的**样例数据**。
     *
     * ## 为什么跑在工作区上
     *
     * 部件的 SQL 与工作台的 SQL 是**同一个世界**里的查询 ——
     * 工作台里能查到的表，部件里也能查。反过来就不成立了：
     * 部件的 SQL 要是跑在远端上，用户刚拉下来的数据就用不上。
     *
     * @param sql 要跑的语句
     * @return 启动的任务；`null` = 没跑（SQL 为空，或上一次还在跑）
     */
    fun previewSql(sql: String): Job? {
        if (previewRunning) return null
        val statement = sql.trim()
        if (statement.isEmpty()) return null
        // 标志**同步**置位 —— 它是「预览正在跑」的凭据，
        // 放在协程里置位的话，连点两下会**两次都通过检查**。
        previewRunning = true
        val job = scope.launch { previewResult = runOnWorkspace(statement) }
        job.invokeOnCompletion { previewRunning = false }
        return job
    }

    /**
     * 重新取一张报表里**所有**部件的数据。
     *
     * ## 为什么**串行**跑而不是并发
     *
     * 工作区是**一个** DuckDB 文件 —— 并发查询会互相抢锁，
     * 而且后完成的会把先完成的盖掉，用户看到的是**错配**的结果
     * （A 部件的位置上显示着 B 部件的数据）。串行慢一点，但每个部件
     * 拿到的都是**自己**那份。
     *
     * ## 为什么它与「刷新一个部件」「导出」**互斥**
     *
     * 三者都要动工作区这个**一个** DuckDB 文件（见 [workspaceBusy]）。
     *
     * @param report 要刷新的报表
     * @return 启动的任务；`null` = 没跑（工作区忙）
     */
    fun refreshReport(report: AnalysisReport): Job? {
        if (workspaceBusy()) return null
        // 只刷新**会话里确实有**的那张报表 —— 认不出来就直接不刷。
        // 否则会把**没人引用**的数据塞进会话：界面上根本没有这张报表，
        // 数据却占着位置，而且再也删不掉（删报表时按 id 找，找不到）。
        val target = reports.firstOrNull { it.id == report.id } ?: return null
        // ⚠️ 标志**同步**置位 —— 它是「这把锁已经被占了」的凭据。
        // 放在协程里置位的话，连点两下会**两次都通过检查**，
        // 两个刷新一起跑，后完成的把先完成的盖掉 —— 那正是
        // 「A 部件的位置上显示着 B 部件的数据」的错配。
        refreshRunning = true
        val job = scope.launch { refreshWidgetsOf(target) }
        // 用 `invokeOnCompletion` 而不是协程里的 `finally`：协程**还没开始跑**
        // 就被取消时，`finally` 不会执行，标志会永远卡在 true 上。
        job.invokeOnCompletion { refreshRunning = false }
        return job
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
     * 把某个远端源的一张表拉进工作区。
     *
     * ## 为什么要**显式传 sourceId**，而不是用「当前选中的源」
     *
     * 早先的写法读会话里的「当前选中的源」（那会儿工具条上还有个下拉框，
     * 现在**已经去掉了** —— 源只有一处能选：左树）。但树上点的是
     * **某一张具体的表** —— 它属于哪个源是那棵树自己知道的（表节点带着 `sourceId`），
     * 而「当前选中的源」可能压根不是这张表的源。
     *
     * 两者不一致时，「拉 A 源里的表」会变成「拉 B 源里的同名表」——
     * 而后者**不会报错**（只要 B 源里恰好有那张表），只是数据悄悄换了来源。
     * 所以这里让调用方把源**显式**交出来。
     *
     * @param sourceId 远端源 id
     * @param namespace 命名空间（MySQL 上是库，PG 上是 schema）
     * @param table 表名
     * @return 启动的任务；`null` = 没启动（上一次还在拉，或源不存在）
     */
    fun pull(
        sourceId: String,
        namespace: String,
        table: String,
    ): Job? {
        if (pulling) return null
        val source = sourceConfig(sourceId) ?: return null
        // 请求 id 由**这里**生成 —— 取消时要靠它叫停引擎那边正在跑的拉取
        // （引擎是另一个进程，取消前端协程并不会让它停下来）。
        val requestId = "analysis-pull-${System.currentTimeMillis()}-${pullCounter.incrementAndGet()}"
        pullRequestId = requestId
        pullJob =
            scope.launch {
                pulling = true
                // 终态在**开跑前**清掉：上一次拉取的「完成」不该继续挂在界面上，
                // 否则用户会以为这次的表已经拉好了。
                pullOutcome = null
                pullProgress = null
                try {
                    val result =
                        puller.pull(
                            requestId = requestId,
                            source = source,
                            workspace = workspaceConnection(),
                            workspacePath = workspacePath,
                            namespace = namespace,
                            table = table,
                            onProgress = { progress ->
                                pullProgress = progress
                            },
                        )
                    pullOutcome = PullOutcome.Done(result)
                    // 拉进来的表就在工作区里 —— 树上要立刻看得见它。
                    refreshWorkspaceChildren()
                } catch (e: AnalysisPullCancelledException) {
                    // 用户取消 —— **不是失败**，也不该把协程一起取消掉。
                    pullOutcome = PullOutcome.Cancelled
                } catch (e: CancellationException) {
                    // 取消**不是**失败 —— 取消没有错，只是没做完。
                    // 但要原样抛出去：吞掉它会让协程以为自己正常结束，
                    // 上层就再也等不到「任务结束了」这件事。
                    pullOutcome = PullOutcome.Cancelled
                    throw e
                } catch (e: Exception) {
                    pullOutcome = PullOutcome.Failed(e.message ?: "拉取失败")
                } finally {
                    pulling = false
                }
            }
        return pullJob
    }

    /**
     * 把一张表加进编辑器 —— **带它所在的 schema**。
     *
     * ## 规则住在 `:shared`
     *
     * 具体怎么拼、拼在哪儿，全部由 [appendAnalysisTableRef] 决定 ——
     * 报表设计的部件编辑页也要做**一模一样**的事（点树上的表 → 引用进 SQL），
     * 而那边住在跨平台模块里、拿不到会话。规则只写一份，两边都调它 ——
     * 否则「同一个概念有两份实现，改一处忘了另一处」必然发生。
     *
     * ## 规则只有一条，例外只有一个
     *
     * - 编辑器**是空的** → 放一条能直接跑的语句（`SELECT * FROM "orders"`）；
     * - 编辑器**有内容** → 在末尾追加一行**引用的表名**（`"orders"`）。
     *
     * ## 为什么不插到光标处
     *
     * 光标位置住在编辑器的内部状态（`CodeEditorState`）里，那是**界面**的东西；
     * 会话只拿着一个纯字符串。在会话里插光标位置就得把编辑器状态也搬进会话 ——
     * 而那会把「一段文本」与「一个编辑器控件」耦在一起。
     *
     * 追加到末尾虽然不一定是用户想要的位置，但它是**可预期**的：
     * 用户看得到自己多了什么，也能自己挪。
     *
     * @param name 表名（未引用）
     * @param namespace 表所在的 schema；`null` 或 `main` = 不带前缀
     */
    fun appendToSql(
        name: String,
        namespace: String? = null,
    ) {
        sql = appendAnalysisTableRef(sql, name, namespace)
    }

    /**
     * 把一个**字段**加进编辑器。
     *
     * 规则与 [appendToSql] 一样（空编辑器给一条能跑的语句，否则追加一行），
     * 区别是字段要**带表名** —— 光有列名在多表查询里是说不清的，
     * 而 `"表"."列"` 永远说得清。
     *
     * @param table 表名（未引用）
     * @param column 列名（未引用）
     * @param namespace 表所在的 schema；`null` 或 `main` = 不带前缀
     */
    fun appendColumnToSql(
        table: String,
        column: String,
        namespace: String? = null,
    ) {
        sql = appendAnalysisColumnRef(sql, table, column, namespace)
    }

    /** 取消当前这次拉取。 */
    fun cancelPull() {
        val requestId = pullRequestId
        pullJob?.cancel()
        // 引擎那边也得叫停 —— 前端取消协程，引擎照样在扫远端、照样往工作区里写。
        // 这一步**尽力而为**：叫停失败不影响前端的终态（那边已经是「用户取消」了）。
        scope.launch {
            (puller as? EngineAnalysisPuller)?.cancelPull(workspaceConnection(), requestId)
        }
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
         * 数据源登记表的 schema —— 它自己的东西，**不跟用户的表混在一起**。
         *
         * ## 为什么登记表住在**工作区**里
         *
         * 分析模式的数据源是**它自己的**清单（不跟连接管理器共用 ——
         * 共用就会变成「两份可能对不上的真相」：同一个 MySQL 在两处各存一份，
         * 改了密码只改一处，另一处连不上）。
         * 而工作区 DuckDB 文件就是这个模式唯一的家：源登记在这儿，
         * 随工作区一起走，换机器时一起搬。
         */
        const val REGISTRY_SCHEMA: String = "sundays"

        /** 数据源登记表（工作区里的）。 */
        const val REGISTRY_TABLE: String = "sources"

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
 * 字段的类型文字 —— `VARCHAR(64)` / `INT`。
 *
 * ## 为什么**不能无条件**加括号
 *
 * 长度是 proto 的 `int32`，未指定时是 **0** ——
 * 无条件加括号会让 `VARCHAR` 变成 `VARCHAR(0)`，一个不存在的类型，
 * 用户看了会以为这张表有问题。
 */
private fun columnTypeText(
    type: String,
    size: Int,
): String = if (size > 0) "$type($size)" else type

/**
 * 标识符引用 —— `"名字"`（里面的双引号**加倍**）。
 *
 * ## 为什么写死 DuckDB 的引用符
 *
 * 落点**永远在工作区里**，而工作区恒为 DuckDB —— 没有第二个方言可选。
 * 用方言的引用符（`SqlIdentifier.quote`）在这里反而是**多此一举**：
 * 它会让人以为「落点也可能落在远端」。
 */
private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * 字符串字面量 —— `'文本'`（**含**两侧的引号）。
 *
 * ## 为什么只转义单引号
 *
 * DuckDB 的**普通**字符串字面量**不认反斜杠转义** —— 实测
 * `SELECT 'C:\Users\x'` 原样得到 `C:\Users\x`。
 * （认转义的是 `E'...'` 那种写法；这里不用它。）
 * 所以反斜杠**不能**加倍 —— 加了路径里就真多出两个反斜杠。
 *
 * ## 为什么单引号要加倍
 *
 * 路径里完全可能有单引号（`C:\O'Brien\data.csv`）。不转义的话，
 * 那条 `CREATE VIEW` 会在**别的**字符串上结束 —— 于是路径被截断，
 * 而错误信息指向一个莫名其妙的地方。
 */
private fun escapeLiteral(text: String): String = "'" + text.replace("'", "''") + "'"

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
