package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 连接管理流程的端到端测试 —— 真引擎（classpath 加载的方言插件 + JDBC 驱动）+ 真点击。
 *
 * 验证的是 desktopApp 的完整链路：`ConnectionManagerScreen` 事件 → [ConnectionSession] →
 * `IdbEngine.testConnection` / `IdbEngine.disconnect`，以及向导各步骤对配置的回写
 * （名称、连接类型、JDBC URL 折算）。
 *
 * `user.home` 指向临时目录，避免污染真实 `~/.config/sundays/connection.json`。
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionManagerFlowTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-test-home").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        // 磁盘上没有 drivers/ dialects/ 目录：方言与驱动全部来自应用类路径（Direct 模式的真实部署形态）
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        engine.close()
        System.setProperty("user.home", originalHome)
    }

    private fun newSession() = ConnectionSession(engine, CoroutineScope(Dispatchers.Default))

    @Composable
    private fun Screen(session: ConnectionSession) {
        val wizard = session.wizard
        ConnectionManagerScreen(
            connections = session.connectionList.connections,
            selectedConnection = session.selectedConnection,
            editingConnection = wizard.editingConnection,
            wizardStep = wizard.step,
            wizardFlow = wizard.flow,
            connectionStatuses = session.statuses,
            onSelectConnection = session::select,
            onNewConnection = session::newConnection,
            onQuickConnect = session::quickConnect,
            onEditConnection = session::edit,
            onSaveConnection = session::save,
            onQuickConnectDirect = session::quickConnectDirect,
            onDeleteConnection = session::delete,
            onCancelEdit = session::cancelEdit,
            onWizardNext = session::goToStep,
            onWizardBack = session::back,
            onUpdateEditingConnection = session::updateEditing,
            onTestConnection = session::testConnection,
            onConnect = session::connect,
            onDisconnect = session::disconnect,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun `quick connect to h2 builds url tests and connects through the engine`() = runComposeUiTest {
        val session = newSession()
        setContent { MaterialTheme { Screen(session) } }

        onNodeWithText("快速连接").performClick()
        onNodeWithText("H2").performClick()

        // 库名为空 → 折算不出 URL → 「下一步」禁用（不允许把无 URL 的配置带进流程）
        onNodeWithText("下一步").assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextInput("flowtest")
        onNodeWithText("jdbc:h2:mem:flowtest", substring = true).assertExists()
        onNodeWithText("下一步").performClick()

        // 测试连接 → 引擎按需建池 + isValid
        onNodeWithText("测试连接").performClick()
        waitUntil(timeoutMillis = 10_000) { session.statuses.values.any { it.state == ConnectionState.CONNECTED } }
        onNodeWithText("连接成功!", substring = true).assertExists()
        assertEquals(1, PoolManager.activePoolCount(), "测试连接应初始化出一个连接池")

        // 快速连接流程最后一步「连接」→ 不落盘，直接选中并连库
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) { session.selectedConnection != null }
        assertTrue(session.connectionList.connections.isEmpty(), "快速连接不写入持久化列表")
        onNodeWithText("已连接").assertExists()

        // 「断开」→ 释放引擎连接池
        onNodeWithText("断开").performClick()
        waitUntil(timeoutMillis = 10_000) { PoolManager.activePoolCount() == 0 }
        waitUntil(timeoutMillis = 10_000) {
            session.statuses.values.all { it.state == ConnectionState.DISCONNECTED }
        }
    }

    @Test
    fun `connection without jdbc url cannot be connected from the overview`() = runComposeUiTest {
        // 历史遗留：v2.11 之前保存的连接可能没有 jdbcUrl（引擎侧无法建池）
        ConnectionStorage.upsert(
            ConnectionConfig(id = "legacy-1", name = "遗留 H2", dialect = DialectType.H2)
        )
        val session = newSession()
        setContent { MaterialTheme { Screen(session) } }

        onNodeWithText("遗留 H2").performClick()

        onNodeWithText("缺少 JDBC URL", substring = true).assertExists()
        onNodeWithText("连接").assertIsNotEnabled()
        assertEquals(0, PoolManager.activePoolCount())
    }

    @Test
    fun `normal flow keeps the typed name and persists a usable jdbc url`() = runComposeUiTest {
        val session = newSession()
        setContent { MaterialTheme { Screen(session) } }

        onNodeWithText("新建连接").performClick()
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput("本地 MySQL")
        onNodeWithText("下一步").performClick()   // → 连接类型（MySQL 仅客户端-服务器）
        onNodeWithText("客户端-服务器").assertExists()
        onNodeWithText("下一步").performClick()   // → 连接详情
        // 客户端-服务器步骤有 6 个文本框：按 label 定位「数据库名」
        onNode(hasSetTextAction() and hasText("数据库名")).performTextInput("shop")
        onNodeWithText("下一步").performClick()   // → 测试并保存

        // 摘要里的名称来自 BASIC_INFO 步骤的输入（名称曾在内联 state 里丢失）
        onNodeWithText("本地 MySQL").assertExists()
        onNodeWithText("jdbc:mysql://localhost:3306/shop", substring = true).assertExists()

        onNodeWithText("保存").performClick()
        waitUntil(timeoutMillis = 10_000) { session.connectionList.connections.isNotEmpty() }

        val saved = session.connectionList.connections.single()
        assertEquals("本地 MySQL", saved.name)
        assertEquals("localhost", saved.host)
        assertEquals(3306, saved.port)
        assertEquals("shop", saved.database)
        assertTrue(saved.jdbcUrl.startsWith("jdbc:mysql://localhost:3306/shop"), "url=${saved.jdbcUrl}")
        assertNotNull(session.selectedConnection)

        // 落到临时 user.home 的 JSON 里（真实持久化路径）
        val json = File(tempHome, ".config/sundays/connection.json").readText()
        assertTrue(json.contains("本地 MySQL"), "连接应写入 connection.json: $json")
        assertTrue(json.contains("jdbc:mysql://localhost:3306/shop"), "connection.json: $json")
    }
}
