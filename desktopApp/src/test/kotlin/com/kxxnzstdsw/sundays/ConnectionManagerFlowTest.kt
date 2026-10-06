package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
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

        // ⚠️ 显式注入 H2 方言 —— **本类所有用例都要**，放在 setUp 而不是某个用例体内。
        //
        // 原因：`engine.close()` 会连带清空 `DialectLoader` 的注册表，而 SPI bootstrap
        // 是全局幂等的（只扫一次），所以**别的测试类跑过之后这里就再也解析不到方言了**。
        // 之前只有第二个用例补了这一句，第一个用例靠「恰好跑在别人前面」——
        // 加上 `ConnectedSourceEndToEndTest`（五个方言、每个都 close 一次引擎）之后，
        // 它就稳定红了（`ComposeTimeoutException: Condition still not satisfied`，
        // 症状是「等连接成功等满 10s」，完全指不到「方言没注册」）。
        //
        // 教训：**测试之间通过全局单例互相干扰，代价总是别人付的。** 与其靠顺序，
        // 不如让每个类都自备依赖。
        DialectLoader.registerForTesting("H2", H2Dialect())
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
        // 标签由 connectionStatuses 驱动：connect() 同步置 CONNECTING（标签「连接中」）、
        // 异步回填 CONNECTED（标签「已连接」）。测试连接那步已让状态是 CONNECTED，
        // 因此不能只等状态 —— 直接等 UI 标签收敛，避免中间态一闪而过抓不到。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("已连接").assertExists()

        // 「断开」→ 释放引擎连接池
        onNodeWithText("断开").performClick()
        waitUntil(timeoutMillis = 10_000) { PoolManager.activePoolCount() == 0 }
        waitUntil(timeoutMillis = 10_000) {
            session.statuses.values.all { it.state == ConnectionState.DISCONNECTED }
        }
    }

    /**
     * 回归：断开后再点「连接」必须能重新连上。
     *
     * `connect` / `disconnect` 都用 `statusGeneration` 做「结果是否仍然有效」的判定，而
     * `bumpStatusGeneration` 曾因运算符优先级写成 `(statusGeneration[id] ?: 0) + 1.also { … }` ——
     * `.also` 的接收者是字面量 `1`：代次落库恒为 1，返回值却是「旧值 + 1」。第一次 connect 的
     * 1 == 1 比对通过（所以首连正常），其后每次操作返回值 ≥ 2 与落库的 1 永不相等 → 回填结果被
     * 全部丢弃，UI 永远停在「连接中...」（按钮被禁用，且再也点不动）。本测试断言断开后的重连
     * 能回到 CONNECTED 且连接池真的重建。
     */
    @Test
    fun `reconnect after disconnect reaches connected again`() = runComposeUiTest {
        // 本方法不依赖执行顺序：别的测试调 `engine.close()` 会清空方言注册表，而 bootstrap 全局幂等
        // （AtomicBoolean 只置一次）不会重扫 SPI —— 显式注入 H2 方言，约定同 DatabaseBrowserFlowTest。
        DialectLoader.registerForTesting("H2", H2Dialect())

        val session = newSession()
        setContent { MaterialTheme { Screen(session) } }

        onNodeWithText("快速连接").performClick()
        onNodeWithText("H2").performClick()
        onNode(hasSetTextAction()).performTextInput("reconnectflow")
        onNodeWithText("下一步").performClick()

        // 首次连接：建池 + isValid → 总览状态行「已连接」
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { PoolManager.activePoolCount() >= 1 }

        // 断开：释放连接池，状态行回「未连接」
        onNodeWithText("断开").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("未连接").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { PoolManager.activePoolCount() == 0 }

        // 重连：必须重新走完 CONNECTING → CONNECTED（修复前代次比对永久失败，结果被丢弃，
        // 界面永远停在禁用的「连接中...」）且池重建
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("已连接").assertExists()
        waitUntil(timeoutMillis = 10_000) { PoolManager.activePoolCount() >= 1 }
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

    /**
     * 回归：在向导流程中（普通 / 快速）点列表里的已保存连接，应退出向导并切到该连接的详情面板，
     * 而不是继续渲染当前向导步骤。覆盖 [ConnectionSession.select] 在 wizard != Idle 时重置为 Idle 的契约。
     */
    @Test
    fun `selecting a saved connection while in wizard exits the wizard to overview`() = runComposeUiTest {
        ConnectionStorage.upsert(
            ConnectionConfig(
                id = "saved-1",
                name = "本地 H2",
                dialect = DialectType.H2,
                database = "shop",
            )
        )
        val session = newSession()
        setContent { MaterialTheme { Screen(session) } }

        // 普通向导：BASIC_INFO 步骤，渲染「下一步」
        onNodeWithText("新建连接").performClick()
        onNodeWithText("下一步").assertExists()
        assertEquals(com.kxxnzstdsw.sundays.connection.WizardStep.BASIC_INFO, session.wizard.step)
        assertNotNull(session.wizard.editingConnection)

        // 点列表里的已保存连接 → 退出向导，右面板切到 ConnectionOverviewPanel
        onNodeWithText("本地 H2").performClick()
        assertEquals(com.kxxnzstdsw.sundays.connection.WizardStep.IDLE, session.wizard.step)
        assertEquals(null, session.wizard.editingConnection)
        // 总览面板上才有的「连接」按钮出现，向导的「下一步」按钮消失
        onNodeWithText("连接").assertExists()
        onNodeWithText("下一步").assertDoesNotExist()

        // 快速连接向导同样适用 —— 列表头的快速连接按钮是 Icon(contentDescription = "快速连接")
        onNodeWithContentDescription("快速连接").performClick()
        // QUICK_CONNECT 步骤选方言 → 直接跳到 CREDENTIALS，才有「下一步」
        onNodeWithText("H2").performClick()
        onNodeWithText("下一步").assertExists()
        assertEquals(com.kxxnzstdsw.sundays.connection.WizardStep.CREDENTIALS, session.wizard.step)
        onNodeWithText("本地 H2").performClick()
        assertEquals(com.kxxnzstdsw.sundays.connection.WizardStep.IDLE, session.wizard.step)
        onNodeWithText("连接").assertExists()
        onNodeWithText("下一步").assertDoesNotExist()
    }
}
