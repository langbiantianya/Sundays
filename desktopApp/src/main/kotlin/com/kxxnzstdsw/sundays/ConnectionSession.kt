package com.kxxnzstdsw.sundays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.TestResult
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep
import com.kxxnzstdsw.sundays.connection.withDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** 向导状态 —— 编辑中的连接 + 当前步骤 + 流程类型（三者必须原子更新，避免 recomposition 间隙 NPE） */
data class WizardState(
    val editingConnection: ConnectionConfig?,
    val step: WizardStep,
    val flow: WizardFlow,
) {
    companion object {
        val Idle = WizardState(null, WizardStep.IDLE, WizardFlow.NORMAL)
    }
}

/**
 * 连接会话状态机 —— 连接列表 / 向导状态 / 引擎侧连接状态的**唯一持有者**。
 *
 * 把 `MainScreen` 原本内联的回调逻辑抽成可独立驱动的状态机：Compose UI 只做展示与事件转发，
 * 集成测试（`ConnectionManagerFlowTest`）可以用同一份状态机跑完整流程（新建 → 填字段 → 测试 → 连接 → 断开），
 * 而不必复制调用方逻辑。
 *
 * ## 与引擎的分工
 * | 操作 | 引擎调用 | 语义 |
 * |---|---|---|
 * | 测试连接 / 连接 | [EngineClient.testConnection] | 按需建 HikariCP 池 + `isValid` 校验（= 初始化连接） |
 * | 断开 | [EngineClient.disconnect] | 释放该配置的连接池 |
 * | 保存 / 删除 | [ConnectionStorage] upsert / delete | JSON 持久化（`~/.config/sundays/connection.json`） |
 * | 窗口关闭 | [EngineClient.close] | 释放实现持有的资源（本地实现=池/驱动/方言，gRPC 实现=channel） |
 *
 * 本状态机只面向 [EngineClient] 接口编程 —— 引擎在同进程（`IdbEngine`）还是跨进程
 * （`GrpcEngineClient`）由 `main.kt` 的装配决定，这里不感知。
 *
 * 编辑已保存连接时若字段发生变化（URL / 凭据 / 库名），旧配置的连接池会先释放 —— 池按字段 hash 缓存，
 * 不释放就会留下永远取不到的僵尸池。
 */
class ConnectionSession(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
) {
    /** 已保存的连接列表（`ConnectionStorage` 的镜像） */
    var connectionList by mutableStateOf(ConnectionStorage.load())
        private set

    /** 当前选中的连接（列表高亮 + 总览面板） */
    var selectedConnection by mutableStateOf<ConnectionConfig?>(null)
        private set

    /** 向导状态 */
    var wizard by mutableStateOf(WizardState.Idle)
        private set

    /** 各连接（按 id）的引擎侧会话状态 */
    var statuses by mutableStateOf<Map<String, ConnectionStatus>>(emptyMap())
        private set

    /**
     * 各连接的状态代次 —— [connect] / [disconnect] 都用它做「结果是否仍然有效」的判定。
     *
     * 两者的 `engine.*` 调用都是 fire-and-forget（拿不到可取消的 Job 句柄），关 sheet 时
     * `disconnect` 与 in-flight 的 `connect` 会同时在飞：谁后写 `statuses` 谁赢，于是
     * 「已关闭的 sheet 又显示成 CONNECTED」或「刚重开的连接被翻成 DISCONNECTED」。
     * 每次发起操作自增该连接的代次，回填前先比对代次，过期结果直接丢弃。
     */
    private val statusGeneration = mutableMapOf<String, Int>()

    /**
     * 自增该连接的状态代次，返回**自增后**的值（供发起方与回填方比对）。
     *
     * 必须用「先自增、再写回、返回自增后值」的显式多行写法：写成
     * `(statusGeneration[id] ?: 0) + 1.also { statusGeneration[id] = it }` 时，`.also` 的接收者是
     * 字面量 `1` 而非和 —— 落库的是常量 `1`，而返回值是「旧值 + 1」。于是第一次操作代次为 1（比对
     * 通过），其后每次操作的返回值都 ≥ 2 而落库恒为 1：比对**永远失败**，回填结果被全部丢弃。
     * 表现即「断开后再点连接」永远停在 CONNECTING（按钮成了禁用的「连接中...」）——连接再也建立不起来。
     */
    private fun bumpStatusGeneration(id: String): Int {
        val next = (statusGeneration[id] ?: 0) + 1
        statusGeneration[id] = next
        return next
    }

    /**
     * 已在数据库浏览区打开的 sheet（按打开顺序）—— 每条 sheet 持有完整的 `ConnectionConfig`，
     * 支持两种生命周期：
     *
     * - **持久化 sheet** = 通过 [save]（落盘到 `ConnectionStorage`）。`ConnectionConfig` 反映
     *   的是磁盘上的最新版本（`connectionList.connections` 中对应项）。
     * - **transient sheet** = 通过 [quickConnectDirect]（不落盘）。配置来自快速连接向导的 draft，
     *   在浏览器区生命周期内有效；sheet 关闭（[closeSheet]）时丢弃。
     *
     * 与 [selectedConnection] 区别：[selectedConnection] 是「连接管理」首屏列表的高亮项，
     * 用于总览面板；[openSheets] 是「数据库浏览」区每个 sheet 的配置集合，**两者独立**——
     * 高亮的不一定开了 sheet，开了 sheet 的也不一定在首屏被选中。
     */
    var openSheets by mutableStateOf<List<ConnectionConfig>>(emptyList())
        private set

    /** 当前激活的 sheet id（[openSheets] 中的某一项的 id） */
    var activeSheetId by mutableStateOf<String?>(null)
        private set

    fun select(config: ConnectionConfig?) {
        selectedConnection = config
        // 选中已保存连接 → 退出向导，右面板切到 ConnectionOverviewPanel
        // （向导进行中点列表不会自动退出，会卡在 BASIC_INFO / QUICK_CONNECT 等步骤）
        if (config != null && wizard != WizardState.Idle) {
            wizard = WizardState.Idle
        }
    }

    fun newConnection() {
        wizard = WizardState(newDraft(), WizardStep.BASIC_INFO, WizardFlow.NORMAL)
    }

    fun quickConnect() {
        wizard = WizardState(newDraft(), WizardStep.QUICK_CONNECT, WizardFlow.QUICK_CONNECT)
    }

    fun edit(config: ConnectionConfig) {
        wizard = WizardState(config, WizardStep.BASIC_INFO, WizardFlow.NORMAL)
    }

    fun updateEditing(config: ConnectionConfig) {
        wizard = wizard.copy(editingConnection = config)
    }

    fun cancelEdit() {
        wizard = WizardState.Idle
    }

    fun goToStep(step: WizardStep) {
        wizard = wizard.copy(step = step)
    }

    /** 向导「上一步」—— 按当前流程回退（QUICK_CONNECT 少一步 BASIC_INFO / CONNECTION_TYPE） */
    fun back() {
        val prev = when (wizard.flow) {
            WizardFlow.QUICK_CONNECT -> when (wizard.step) {
                WizardStep.QUICK_CONNECT -> WizardStep.IDLE
                WizardStep.CREDENTIALS -> WizardStep.QUICK_CONNECT
                WizardStep.TEST_SAVE -> WizardStep.CREDENTIALS
                else -> WizardStep.IDLE
            }
            WizardFlow.NORMAL -> when (wizard.step) {
                WizardStep.BASIC_INFO -> WizardStep.IDLE
                WizardStep.CONNECTION_TYPE -> WizardStep.BASIC_INFO
                WizardStep.CREDENTIALS -> WizardStep.CONNECTION_TYPE
                WizardStep.TEST_SAVE -> WizardStep.CREDENTIALS
                else -> WizardStep.IDLE
            }
        }
        wizard = wizard.copy(step = prev)
    }

    /** 普通流程「保存」—— 持久化并选中；字段变化时先释放旧配置的连接池。
     *
     * 保存后**不**自动打开浏览器 sheet —— 用户保留在连接管理首屏，通过 [selectedConnection]
     * 在右栏看到刚保存的「连接详情」（[ConnectionOverviewPanel]）。需要打开浏览区时由
     * 总览面板的「连接」按钮显式触发（调用 [connect] + [openSheet]）。
     * [quickConnectDirect] 仍然自动 openSheet —— 那是「立即建池浏览」语义，与本方法不同。 */
    fun save(config: ConnectionConfig) {
        connectionList.connections
            .find { it.id == config.id }
            ?.takeIf { it != config }
            ?.let { disconnect(it) }
        connectionList = ConnectionStorage.upsert(config)
        selectedConnection = config
        wizard = WizardState.Idle
    }

    /** 快速连接「连接」—— 不持久化，直接让引擎建池连接 */
    fun quickConnectDirect(config: ConnectionConfig) {
        selectedConnection = config
        wizard = WizardState.Idle
        connect(config)
        // 同 [save]：成功添加连接 = 浏览器区出现新 sheet；不持久化但 transient sheet
        // 仍可在浏览期内被操作（关 sheet 时 closeSheet 会清理）。
        openSheet(config)
    }

    /** 删除连接 —— 先释放其连接池（若存在），再落盘删除 */
    fun delete(id: String) {
        connectionList.connections.find { it.id == id }?.let { disconnect(it) }
        connectionList = ConnectionStorage.delete(id)
        statuses = statuses - id
        statusGeneration.remove(id)
        if (selectedConnection?.id == id) {
            selectedConnection = null
        }
        // 删除时同步关掉其 sheet —— 否则浏览器区会指向已不存在的配置
        if (openSheets.any { it.id == id }) closeSheet(id)
    }

    /**
     * 打开一条 sheet（数据库浏览区一个 tab），并把它设为激活。
     *
     * 幂等 —— 重复打开同 id 不重复入栈，只切换激活态。
     *
     * [save] 与 [quickConnectDirect] 都会在内部调用本方法 —— 这是「添加连接成功后要添加新标签页」
     * 契约的下沉点，调用方无需自行接线。
     */
    fun openSheet(config: ConnectionConfig) {
        openSheets = if (openSheets.any { it.id == config.id }) openSheets else openSheets + config
        activeSheetId = config.id
    }

    /** 关闭一条 sheet —— 释放它在浏览器侧的状态（实际释放由 `DatabaseBrowserState.releasePools`） */
    fun closeSheet(id: String) {
        if (openSheets.none { it.id == id }) return
        val newList = openSheets.filterNot { it.id == id }
        openSheets = newList
        activeSheetId = when {
            activeSheetId != id -> activeSheetId
            newList.isEmpty() -> null
            else -> newList.last().id
        }
    }

    /** 切换激活 sheet —— 调用方在用户点击 sheet 标签时调用。id 必须在 [openSheets] 中；否则忽略 */
    fun selectSheet(id: String) {
        if (openSheets.any { it.id == id }) activeSheetId = id
    }

    /** 是否已打开某条 sheet */
    fun isSheetOpen(id: String): Boolean = openSheets.any { it.id == id }

    /** 建立连接 —— `IdbEngine.testConnection` 建池 + 校验，状态回填列表 / 总览 */
    fun connect(config: ConnectionConfig) {
        val gen = bumpStatusGeneration(config.id)
        statuses = statuses + (config.id to ConnectionStatus(ConnectionState.CONNECTING))
        scope.launch {
            val status = runCatching { engine.testConnection(engineConfig(config)) }.fold(
                onSuccess = { response ->
                    if (response.ok) {
                        ConnectionStatus(ConnectionState.CONNECTED, response.driver)
                    } else {
                        ConnectionStatus(ConnectionState.FAILED, response.error.ifBlank { "连接失败" })
                    }
                },
                onFailure = { ConnectionStatus(ConnectionState.FAILED, it.message ?: "Unknown error") },
            )
            if (statusGeneration[config.id] != gen) return@launch   // 期间又断开/重连 —— 丢弃过期结果
            statuses = statuses + (config.id to status)
        }
    }

    /** 断开连接 —— 释放该配置的连接池（`IdbEngine.disconnect`） */
    fun disconnect(config: ConnectionConfig) {
        bumpStatusGeneration(config.id)
        statuses = statuses + (config.id to ConnectionStatus())
        scope.launch {
            runCatching { engine.disconnect(engineConfig(config)) }
        }
    }

    /**
     * 向导「测试连接」—— 直连引擎测试并同步会话状态，返回值供向导内展示结果。
     * 供 `ConnectionManagerScreen.onTestConnection` 使用（suspend 回调）。
     */
    suspend fun testConnection(config: ConnectionConfig): TestResult {
        val response = engine.testConnection(engineConfig(config))
        statuses = statuses + (config.id to
            if (response.ok) {
                ConnectionStatus(ConnectionState.CONNECTED, response.driver)
            } else {
                ConnectionStatus(ConnectionState.FAILED, response.error.ifBlank { "连接失败" })
            })
        return TestResult(success = response.ok, message = response.error)
    }

    private fun newDraft(): ConnectionConfig =
        // withDialect 套用方言默认值（host/port + 折算 JDBC URL），保证进入凭据步骤即有合法 URL
        ConnectionConfig(id = UUID.randomUUID().toString(), name = "新连接")
            .withDialect(DialectType.MYSQL)
}

/**
 * UI 连接配置 → 引擎 proto 配置。
 *
 * `jdbcUrl` 是连接真相源（`PoolManager` 优先按 URL scheme 反查方言），但 `driver` 也必须带上：
 * `SchemaHandler` / `TableHandler` 等 handler 直接按 `config.driver` 取方言实例，缺了会报
 * `No dialect plugin loaded for driver:`。取 [DialectType.engineDriverName] 而非 `Enum.name`
 * （引擎用 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`）。
 *
 * `database` 一并写入：它参与连接池 key，且 `TableHandler` 会按它做 catalog 过滤 ——
 * 与 `DatabaseBrowserState.engineConn` 保持同构，避免同一连接出现多个不相干的池。
 */
private fun engineConfig(config: ConnectionConfig) = connectionConfig {
    driver = config.dialect.engineDriverName
    jdbcUrl = config.jdbcUrl
    user = config.username
    password = config.password
    database = config.database
}
