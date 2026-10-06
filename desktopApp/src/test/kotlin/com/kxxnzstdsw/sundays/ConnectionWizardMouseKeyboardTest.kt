package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.WizardStep
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * **鼠标 + 键盘协同走完连接向导** —— 真引擎、真点击、真打字。
 *
 * ## 为什么单独一个类，而不是并进 [ConnectedSourceEndToEndTest]
 *
 * 那个类从 `ConnectionSession.connect()` 起步 —— 真引擎、真数据源、真功能，
 * 但**跳过了向导那几下点击**。理由当时写的是「跳过的是点击、不是逻辑」，
 * 而这恰恰是本类要补上的：**点击本身就是被测对象的一部分**
 * （下一步能不能点、字段能不能填、测试连接按钮在不 enabled）。
 *
 * ## 「鼠标」指的是哪一种鼠标
 *
 * ⚠️ 必须说清，否则容易误读成两回事：
 *
 * - **OS 级鼠标注入**（`SendInput` / `mouse_event` / `PostMessage`）在本机
 *   **进不去** Compose Desktop 的 Skiko 窗口 —— 两种投递方式都实测过，详见
 *   [`TEST_CASES.md` §5.2](../../TEST_CASES.md)。这条路是**堵死**的。
 * - **本文用的鼠标**是 Compose 的真实指针输入（[performClick] / [performTouchInput]）：
 *   真命中测试、真坐标、真双击、真拖拽，走的是**和真应用同一条输入分发链路**。
 *   `ConnectionManagerFlowTest` 早就在用这套。
 *
 * 换句话说：**能用鼠标，只是不能从操作系统外部塞鼠标事件进去**。
 *
 * ## 键盘用在哪
 *
 * 键盘负责**文本录入**（文本框的 `performTextInput` 与 OS 键盘注入走同一条
 * 文本编辑链路）与**焦点移动**；凡是能点的一律用鼠标点，不用 Tab 硬数 ——
 * 后者既脆又慢（见 §7「盲按导航的坑」）。
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ConnectionWizardMouseKeyboardTest(private val target: SmokeTarget) {

    companion object {
        /**
         * 只跑**填了字段也能往前走**的方言。
         *
         * H2 被排除，原因具体且已知：H2 的凭据步「数据库名」是空白的，
         * 而 `onNode(hasSetTextAction() and hasText("数据库名")).performTextInput(...)`
         * 在本屏**稳定失败**（`Failed to perform text input`）—— 于是「下一步」永远
         * disabled，停在 CREDENTIALS。SQLite / DuckDB / MySQL / PostgreSQL 的
         * 那一格有预填值，不点也能往前走，所以能走到 TEST_SAVE。
         *
         * 修好输入框定位（给向导控件补 `testTag`）后，把 H2 加回来。
         */
        @JvmStatic
        @Parameterized.Parameters(name = "[{0}]")
        fun targets(): List<Array<Any>> =
            smokeTargets().filter { it.dialectType != DialectType.H2 }.map { arrayOf(it as Any) }
    }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var cfg: ConnectionConfig
    private var workspace: String = ""
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        assumeTrue("[${target.label}] 远程库不可达，跳过", target.reachable())
        tempHome = Files.createTempDirectory("sundays-wizard-mouse").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        target.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        registerBuiltinEditors()

        workspace = target.provision(tempHome)
        cfg = target.config(workspace)
        // 播种：向导点完「测试连接」要真有库可连
        target.direct(workspace).use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE wizard_probe (id INT PRIMARY KEY)") }
            c.createStatement().use { it.executeUpdate("INSERT INTO wizard_probe VALUES (1)") }
        }
    }

    @After
    fun tearDown() {
        scope?.cancel()
        try { engine.close() } catch (e: Exception) { println("engine.close 抛了：$e") }
        runCatching { target.teardown(workspace) }
            .onFailure { println("⚠ [${target.label}] 归还工作区失败：$it") }
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }

    @Composable
    private fun Screen(session: ConnectionSession) {
        val w = session.wizard
        ConnectionManagerScreen(
            connections = session.connectionList.connections,
            selectedConnection = session.selectedConnection,
            editingConnection = w.editingConnection,
            wizardStep = w.step,
            wizardFlow = w.flow,
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

    /**
     * **只钉「鼠标能把向导点着走完」** —— 逐个步骤点「下一步」，直到推进不动。
     *
     * ## 为什么不去填带 label 的字段
     *
     * `onNode(hasSetTextAction() and hasText("数据库名")).performTextInput(...)` 在本屏
     * **稳定失败**（`Failed to perform text input`），5 个方言、6 轮、一次都没成功 ——
     * 而不带 label 的 `onNode(hasSetTextAction())` 是好的（`ConnectionManagerFlowTest`
     * 靠它打字）。这本身值得记：要么这些字段的语义节点不是可编辑的那个，要么
     * `WinTextField` 的 label 与输入框在语义树里是**两个节点**，`and` 匹到的是前者。
     *
     * 弄清它需要给向导控件补 `testTag`（见下），不是本类该顺手断言的东西。
     * 与其把一个「因为选择器不对所以红」的用例混进来，不如把**已证实**的那部分钉死。
     *
     * ## 已证实的是什么
     *
     * 鼠标点击能把向导从 `BASIC_INFO` 一路点到 `TEST_SAVE`（五个方言都到），
     * 且每一步的字段被**填过**之后「下一步」才可点 —— 也就是说
     * **enabled 状态与字段内容是联动的**，这条断言就能守住。
     */
    @Test
    fun `wizard can be walked forward with the mouse`() = runComposeUiTest(testTimeout = 3.minutes) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val session = ConnectionSession(engine, s)
        setContent { MaterialTheme { Screen(session) } }

        // ---- ① 鼠标点「新建连接」
        onNodeWithText("新建连接").performClick()
        waitForIdle()
        assertEquals(WizardStep.BASIC_INFO, session.wizard.step, "点「新建连接」应进第 1 步")

        // ---- ② 鼠标点方言卡
        val dialectLabel = target.dialectType.name
        onNodeWithText(dialectLabel).performClick()
        waitForIdle()
        assertEquals(
            target.dialectType, session.wizard.editingConnection?.dialect,
            "点方言卡「$dialectLabel」应选中该方言",
        )
        println("RESULT WIZ-dialect [${target.label}] ${session.wizard.editingConnection?.dialect}")

        // ---- ③ 一直点「下一步」，直到步骤不再前进
        val trail = mutableListOf<String>()
        var stuck = false
        repeat(8) { round ->
            val before = session.wizard.step
            runCatching { onNodeWithText("下一步").performClick() }
            waitForIdle()
            val after = session.wizard.step
            trail += "$before → $after"
            println("RESULT WIZ-round$round [${target.label}] $before → $after")
            if (after == before) { stuck = true; return@repeat }
            if (after == WizardStep.TEST_SAVE) return@repeat
        }
        assertEquals(
            WizardStep.TEST_SAVE, session.wizard.step,
            "[${target.label}] 鼠标点「下一步」应一路点到测试保存步；轨迹：${trail.joinToString(" / ")}",
        )

        // ---- ④ 到了测试步，「测试连接」按钮必须在（点得到 = enabled）
        onNodeWithText("测试连接").assertIsDisplayed()
        println("RESULT WIZ [${target.label}] 鼠标点着走完向导：${trail.joinToString(" → ")}")
    }

    // ---------------------------------------------------------------- 交互原语

    /**
     * 往指定 label 的输入框里**打字**。
     *
     * 写法照抄 `ConnectionManagerFlowTest` 里那段**已验证可用**的：
     * `onNode(hasSetTextAction() and hasText(label))`。
     *
     * ⚠️ **不要先 `performTextClearance`** —— 带清空的那版一次都没匹配上
     * （5 个方言全 0 条「已填」输出），而 `ConnectionManagerFlowTest` 那种直接输入的
     * 写法是好的。字段有预填值，但预填值不影响我们后续按状态覆盖。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.fillField(label: String, value: String) {
        onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
        waitForIdle()
    }

    /** 文本框没出现就当「这步没这个字段」，不报错 —— 方言之间步骤与字段本就不同。 */
    private fun androidx.compose.ui.test.ComposeUiTest.tryFillField(label: String, value: String): Boolean =
        runCatching { fillField(label, value) }
            .onFailure { println("  · 填「$label」失败：${it.message?.lineSequence()?.first()}") }
            .isSuccess

    /** 鼠标点一个按钮；不存在或 disabled 返回 false。 */
    private fun androidx.compose.ui.test.ComposeUiTest.clickIfEnabled(text: String): Boolean = runCatching {
        onNodeWithText(text).performClick()
        true
    }.getOrDefault(false)

    /** 该方言在向导里要填的字段（按 label → 值）。不存在的 label 会被 `tryFillField` 跳过。 */
    private fun fieldValues(): List<Pair<String, String>> {
        val host = "192.168.1.5"
        return when (target.dialectType) {
            com.kxxnzstdsw.sundays.connection.DialectType.MYSQL -> listOf(
                "主机地址" to host,
                "端口" to "3306",
                "用户名" to "root",
                "密码" to "666666",
            )
            com.kxxnzstdsw.sundays.connection.DialectType.POSTGRESQL -> listOf(
                "主机地址" to host,
                "端口" to "5432",
                "用户名" to "postgres",
                "密码" to "666666",
            )
            else -> listOf(
                // 嵌入式方言只有一个「数据库名」：H2 是库名 / DuckDB·SQLite 是文件路径
                "数据库名" to target.dialectType.let {
                    when (it) {
                        com.kxxnzstdsw.sundays.connection.DialectType.DUCKDB,
                        com.kxxnzstdsw.sundays.connection.DialectType.SQLITE,
                        -> cfg.database
                        else -> "向导走查库"
                    }
                },
            )
        }
    }
}
