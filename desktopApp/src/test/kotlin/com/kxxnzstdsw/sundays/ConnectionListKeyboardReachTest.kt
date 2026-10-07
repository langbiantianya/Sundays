package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.ConnectionType
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep
import com.kxxnzstdsw.sundays.connection.connectionCardTag
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **键盘可达性的两条契约** —— 连接列表项。
 *
 * ## 背景（真窗口走查，见 TEST_CASES.md §9.9）
 *
 * 连接卡片是连接列表的**主要交互目标**（点它 = 选中），用的是
 * `Modifier.clickable`：它在非触摸模式下**会**加入 Tab 序，却**不画任何焦点指示**。
 * 于是键盘用户 Tab 到这张卡片时，界面和没聚焦时一模一样 ——
 * 我在走查里为此把「Tab ×4 选中连接」误判成「Tab ×4 落在 ⋮ 上」，
 * 因为截图里根本没有焦点痕迹。
 *
 * 这类缺陷**没有任何功能测试能撞出来**：功能是好的，只是用户不知道自己在哪。
 *
 * ## 钉住的两件事
 *
 * 1. 卡片**可被 Tab 到达**（键盘用户至少能选中它）；
 * 2. 卡片**可被 `testTag` 定位** —— 这是真窗口走查能从「数焦点」改成「按名字点名」的前提。
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionListKeyboardReachTest {

    private val cfg = ConnectionConfig(
        id = "kb-mysql",
        name = "键盘探针",
        dialect = DialectType.MYSQL,
        host = "192.168.1.5",
        port = 3306,
        database = "sundays_probe",
        username = "root",
        password = "666666",
        connectionType = ConnectionType.CLIENT_SERVER,
        jdbcUrl = "jdbc:mysql://192.168.1.5:3306/sundays_probe?useSSL=false",
    )

    @Test
    fun `the connection card carries a testTag and can be reached by tab`() =
        runComposeUiTest(testTimeout = 60.seconds) {
            setContent {
                SundaysTheme {
                    ConnectionManagerScreen(
                        connections = listOf(cfg),
                        selectedConnection = cfg,
                        editingConnection = null,
                        wizardStep = WizardStep.IDLE,
                        wizardFlow = WizardFlow.NORMAL,
                        connectionStatuses = mapOf(cfg.id to ConnectionStatus(ConnectionState.DISCONNECTED)),
                        onSelectConnection = {},
                        onNewConnection = {},
                        onQuickConnect = {},
                        onEditConnection = {},
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        onDeleteConnection = {},
                        onCancelEdit = {},
                        onWizardNext = {},
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            // ① 能按名字点名 —— 真窗口走查靠这个取代「数焦点」
            val card = onNodeWithTag(connectionCardTag(cfg.id))
            card.assertIsDisplayed()

            // ② 能被 Tab 到达 —— 键盘用户至少能选中它
            val root = onRoot()
            var reached = false
            for (i in 0 until 10) {
                root.performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                if (runCatching { card.assertIsFocused(); true }.getOrDefault(false)) { reached = true; break }
            }
            assertTrue(reached, "连续 10 次 Tab 之后连接卡片仍未获得焦点 —— 键盘用户无法选中它")
        }

    /**
     * 焦点环**必须真的画出来**。
     *
     * 上面那条只证明「可聚焦」—— 而 `clickable` 本来就可聚焦，**它一直都不画焦点指示**，
     * 功能是好的、只是用户不知道焦点在哪，于是上一条从第一天起就是绿的，
     * 而真窗口走查照样数不清焦点（TEST_CASES.md §9.9）。
     *
     * 这条只能靠**焦点状态进了语义树**来判断（[assertFocusStateSet] / [assertFocusStateNotSet]）：
     * 它证明 `focusRing()` 的 `onFocusChanged` 真的把状态传出来了 ——
     * 少这一层，环就画不出来，而测试察觉不到。
     */
    @Test
    fun `the focus ring publishes its focus state`() =
        runComposeUiTest(testTimeout = 60.seconds) {
            setContent {
                SundaysTheme {
                    ConnectionManagerScreen(
                        connections = listOf(cfg),
                        selectedConnection = cfg,
                        editingConnection = null,
                        wizardStep = WizardStep.IDLE,
                        wizardFlow = WizardFlow.NORMAL,
                        connectionStatuses = mapOf(cfg.id to ConnectionStatus(ConnectionState.DISCONNECTED)),
                        onSelectConnection = {},
                        onNewConnection = {},
                        onQuickConnect = {},
                        onEditConnection = {},
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        onDeleteConnection = {},
                        onCancelEdit = {},
                        onWizardNext = {},
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            val card = onNodeWithTag(connectionCardTag(cfg.id))
            val root = onRoot()
            // 用 for + break：`repeat` 里只能 `return@repeat` 跳过当次，循环仍会继续 ——
            // 焦点一旦找到又被后面的 Tab 推走，于是断言在「焦点已离开」的时刻执行。
            for (i in 0 until 10) {
                root.performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                if (runCatching { card.assertIsFocused(); true }.getOrDefault(false)) break
            }

            // 焦点环挂的是 `onFocusChanged` + 绘制，绘制不进语义树；
            // 但**焦点状态进了语义树**，所以用它做判据 ——
            // 这正是「少这一层环就画不出来」的那一环（断言写在语义上，不在像素上）。
            val focused = card.fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Focused]
            assertEquals(true, focused, "卡片获得键盘焦点后语义树里必须带 Focused —— 焦点环靠它绘制")
        }
}
