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
import com.langbiantianya.grpc.analysisListSourcesRequest
import com.langbiantianya.grpc.analysisListTablesRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.sundays.analysis.ANALYSIS_WORKSPACE_NODE_ID
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.AnalysisTreeKind
import com.langbiantianya.sundays.analysis.AnalysisTreeNode
import com.langbiantianya.sundays.analysis.PullOutcome
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.WORKSPACE_SCHEMA
import com.langbiantianya.sundays.analysis.analysisNamespaceNodeId
import com.langbiantianya.sundays.analysis.analysisTableNodeId
import com.langbiantianya.sundays.analysis.findAnalysisNode
import com.langbiantianya.sundays.analysis.replaceAnalysisNode
import com.langbiantianya.sundays.analysis.stagingTableName
import com.langbiantianya.sundays.analysis.workspaceFilePath
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.SqlIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
     * 凭据、方言，以及派生不出的**状态**：选中项、SQL、结果。
     *
     * 它**不是** Compose 状态：没有界面读它，不需要重组。
     */
    private var sourceBindings: Map<String, AnalysisSourceBinding> = emptyMap()

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

    // ──────────────── 数据源树 ────────────────

    /**
     * 数据源树的根节点 —— 工作区 + 各远端源。
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
    var treeRoots by mutableStateOf<List<AnalysisTreeNode>>(emptyList())
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

    /** 正在加载的节点 id —— 用来挡住**重复**加载（连点两下扭子）。 */
    private val loadingNodeIds = mutableSetOf<String>()

    /**
     * 同步可分析的远端源。
     *
     * ## 为什么要剪枝选中项
     *
     * 连接被删掉后，选中项若还留着，界面会指向一个**不存在的源** ——
     * 后续拉取会拿着一个查无此人的 id 去引擎。宁可退回「未选中」。
     */
    fun setSources(bindings: List<AnalysisSourceBinding>) {
        sourceBindings = bindings.associateBy { it.item.id }
        val current = selectedSourceId
        if (current != null && !sourceBindings.containsKey(current)) {
            selectedSourceId = null
        }
        refreshTree()
    }

    /**
     * 重建树的**根**，但保住已经加载出来的子节点。
     *
     * ## 为什么要「重建根但保留子节点」
     *
     * 连接列表一变（加一个连接、改个名字）就要重建根 —— 否则树里会留着
     * 已经删掉的源。但**直接重建**会把用户展开的节点连同它的子节点一起抹掉：
     * 改个连接名，用户的树就缩回去了。
     *
     * 所以这里只换「根节点自己」（名字、方言标签），
     * 已经加载出来的 `children` 与 `loaded` 原样带走。
     */
    private fun refreshTree() {
        val previous = treeRoots.associateBy { it.id }
        treeRoots =
            buildTreeRoots().map { node ->
                val old = previous[node.id] ?: return@map node
                node.copy(children = old.children, loaded = old.loaded)
            }
    }

    /**
     * 树的根节点 —— 工作区 + 各远端源。
     *
     * 工作区**永远**在第一位：它是分析模式的默认落点（拉进来的表都在那儿）。
     */
    private fun buildTreeRoots(): List<AnalysisTreeNode> {
        val workspace =
            AnalysisTreeNode(
                id = ANALYSIS_WORKSPACE_NODE_ID,
                label = workspaceFileName(),
                kind = AnalysisTreeKind.WORKSPACE,
                dialectLabel = "DuckDB",
            )
        val sources =
            sourceBindings.values.map { binding ->
                AnalysisTreeNode(
                    id = binding.item.id,
                    label = binding.item.name,
                    kind = AnalysisTreeKind.SOURCE,
                    dialectLabel = binding.item.dialectLabel,
                )
            }
        return listOf(workspace) + sources
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
    fun toggleNode(node: AnalysisTreeNode): Job? {
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
    fun loadChildren(node: AnalysisTreeNode): Job? {
        if (node.loaded) return null
        if (!loadingNodeIds.add(node.id)) return null
        treeLoading = true
        treeError = null
        return scope.launch {
            try {
                val children = fetchChildren(node)
                treeRoots =
                    replaceAnalysisNode(treeRoots, node.id) {
                        it.copy(children = children, loaded = true)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 加载失败**不算**「这个节点是空的」—— 保持 `loaded = false`，
                // 用户再点一次就能重试；错误原文摆在树脚下。
                treeError = e.message ?: "加载失败"
            } finally {
                loadingNodeIds.remove(node.id)
                treeLoading = loadingNodeIds.isNotEmpty()
            }
        }
    }

    /**
     * 按节点的 kind 决定「该查什么」。
     *
     * 三种分组各查各的：
     * - 工作区 → 它的表（DuckDB 的 `main` schema）
     * - 远端源 → 它的命名空间（MySQL 的库 / PG 的 schema）
     * - 命名空间 → 它的表
     */
    private suspend fun fetchChildren(node: AnalysisTreeNode): List<AnalysisTreeNode> =
        when (node.kind) {
            AnalysisTreeKind.WORKSPACE -> listWorkspaceTables()
            AnalysisTreeKind.SOURCE -> listNamespaces(node.sourceId ?: node.id)
            AnalysisTreeKind.NAMESPACE -> listTables(node.sourceId, node.namespace)
            AnalysisTreeKind.TABLE -> emptyList()
        }

    /** 远端源的命名空间。 */
    private suspend fun listNamespaces(sourceId: String): List<AnalysisTreeNode> {
        val config = sourceConfig(sourceId) ?: return emptyList()
        val response =
            engine.invoke(config) {
                category = Category.ANALYSIS
                action = Action.LIST_SOURCES
                analysisRequest = analysisRequest { listSources = analysisListSourcesRequest { } }
            }
        if (!response.success) {
            throw IllegalStateException(response.error.ifBlank { "列命名空间失败" })
        }
        return response.analysis.listSources.itemsList.map { namespace ->
            AnalysisTreeNode(
                id = analysisNamespaceNodeId(sourceId, namespace),
                label = namespace,
                kind = AnalysisTreeKind.NAMESPACE,
                sourceId = sourceId,
                namespace = namespace,
            )
        }
    }

    /** 某个命名空间下的表（远端）。 */
    private suspend fun listTables(
        sourceId: String?,
        namespace: String?,
    ): List<AnalysisTreeNode> {
        if (sourceId == null || namespace == null) return emptyList()
        val config = sourceConfig(sourceId) ?: return emptyList()
        val response =
            engine.invoke(config) {
                category = Category.ANALYSIS
                action = Action.LIST_TABLES
                analysisRequest =
                    analysisRequest {
                        listTables = analysisListTablesRequest { this.namespace = namespace }
                    }
            }
        if (!response.success) {
            throw IllegalStateException(response.error.ifBlank { "列表失败" })
        }
        return response.analysis.listTables.itemsList.map { item ->
            AnalysisTreeNode(
                id = analysisTableNodeId(sourceId, namespace, item.name),
                label = item.name,
                kind = AnalysisTreeKind.TABLE,
                sourceId = sourceId,
                namespace = namespace,
                table = item.name,
            )
        }
    }

    /**
     * 工作区里的表。
     *
     * 走的是**同一套** `ANALYSIS.LIST_TABLES` 路由，只是连接换成了工作区 ——
     * 不另写一条「列工作区表」的通路，免得两处的分派规则各说各话。
     */
    private suspend fun listWorkspaceTables(): List<AnalysisTreeNode> {
        val response =
            engine.invoke(workspaceConnection()) {
                category = Category.ANALYSIS
                action = Action.LIST_TABLES
                analysisRequest =
                    analysisRequest {
                        listTables = analysisListTablesRequest { namespace = WORKSPACE_SCHEMA }
                    }
            }
        if (!response.success) {
            throw IllegalStateException(response.error.ifBlank { "列工作区表失败" })
        }
        return response.analysis.listTables.itemsList.map { item ->
            AnalysisTreeNode(
                id = analysisTableNodeId(ANALYSIS_WORKSPACE_NODE_ID, WORKSPACE_SCHEMA, item.name),
                label = item.name,
                kind = AnalysisTreeKind.TABLE,
                namespace = WORKSPACE_SCHEMA,
                table = item.name,
            )
        }
    }

    /**
     * 拉取成功后刷新工作区那一支 —— 新落地的 staging 表要立刻出现在树上。
     *
     * 刷的是**工作区节点**，不是整棵树：远端那一支没动过，重查一遍是白花钱。
     */
    private fun refreshWorkspaceChildren() {
        val workspace = findAnalysisNode(treeRoots, ANALYSIS_WORKSPACE_NODE_ID) ?: return
        scope.launch {
            try {
                val children = listWorkspaceTables()
                treeRoots =
                    replaceAnalysisNode(treeRoots, workspace.id) {
                        it.copy(children = children, loaded = true)
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
     * 早先的写法用 `selectedSourceId`（工具条下拉框里的那个）。但树上点的是
     * **某一张具体的表** —— 它属于哪个源是那棵树自己知道的（表节点带着 `sourceId`），
     * 而下拉框里的「当前源」可能压根不是这张表的源。
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
        val dialect = sourceDialect(sourceId) ?: return null
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
                            source = source,
                            workspace = workspaceConnection(),
                            workspacePath = workspacePath,
                            dialect = dialect,
                            namespace = namespace,
                            table = table,
                        ) { progress ->
                            pullProgress = progress
                        }
                    pullOutcome = PullOutcome.Done(result)
                    // 拉进来的表就在工作区里 —— 树上要立刻看得见它。
                    refreshWorkspaceChildren()
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
     * 把一张工作区的表名加进编辑器。
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
     */
    fun appendToSql(name: String) {
        val quoted = SqlIdentifier.quote(DialectType.DUCKDB, name)
        sql =
            if (sql.isBlank()) {
                "SELECT * FROM $quoted"
            } else {
                sql.trimEnd() + "\n" + quoted
            }
    }

    /** 取消当前这次拉取。 */
    fun cancelPull() {
        pullJob?.cancel()
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
