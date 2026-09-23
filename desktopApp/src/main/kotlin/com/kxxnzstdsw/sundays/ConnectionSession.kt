package com.kxxnzstdsw.sundays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kxxnzstdsw.engine.IdbEngine
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
 *
 * | 操作 | 引擎调用 | 语义 |
 * |---|---|---|
 * | 测试连接 / 连接 | [IdbEngine.testConnection] | 按需建 HikariCP 池 + `isValid` 校验（= 初始化连接） |
 * | 断开 | [IdbEngine.disconnect] | 释放该配置的连接池 |
 * | 保存 / 删除 | [ConnectionStorage] upsert / delete | JSON 持久化（`~/.config/sundays/connection.json`） |
 * | 窗口关闭 | [IdbEngine.close] | 释放全部池 / 驱动 / 方言 |
 *
 * 编辑已保存连接时若字段发生变化（URL / 凭据 / 库名），旧配置的连接池会先释放 —— 池按字段 hash 缓存，
 * 不释放就会留下永远取不到的僵尸池。
 */
class ConnectionSession(
    private val engine: IdbEngine,
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

    fun select(config: ConnectionConfig?) {
        selectedConnection = config
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

    /** 普通流程「保存」—— 持久化并选中；字段变化时先释放旧配置的连接池 */
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
    }

    /** 删除连接 —— 先释放其连接池（若存在），再落盘删除 */
    fun delete(id: String) {
        connectionList.connections.find { it.id == id }?.let { disconnect(it) }
        connectionList = ConnectionStorage.delete(id)
        statuses = statuses - id
        if (selectedConnection?.id == id) {
            selectedConnection = null
        }
    }

    /** 建立连接 —— `IdbEngine.testConnection` 建池 + 校验，状态回填列表 / 总览 */
    fun connect(config: ConnectionConfig) {
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
            statuses = statuses + (config.id to status)
        }
    }

    /** 断开连接 —— 释放该配置的连接池（`IdbEngine.disconnect`） */
    fun disconnect(config: ConnectionConfig) {
        scope.launch {
            runCatching { engine.disconnect(engineConfig(config)) }
            statuses = statuses + (config.id to ConnectionStatus())
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

/** UI 连接配置 → 引擎 proto 配置：只传 JDBC URL + 凭据，方言由 URL scheme 反查（v2.11） */
private fun engineConfig(config: ConnectionConfig) = connectionConfig {
    jdbcUrl = config.jdbcUrl
    user = config.username
    password = config.password
}
