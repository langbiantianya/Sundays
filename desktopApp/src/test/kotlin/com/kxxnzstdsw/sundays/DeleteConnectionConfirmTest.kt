package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionList
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.ConnectionType
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * **删除连接必须先过一道确认框** —— 真窗口走查发现，键盘 Tab 到「删除」按一下回车，
 * 连接连口令一起从 `connection.json` 里没了，界面上没有任何提示、没有撤销。
 *
 * ## 为什么这个缺陷能溜过所有既有测试
 *
 * 因为删除在实现上是**纯状态转移**（`ConnectionSession.delete` → `ConnectionStorage.delete`），
 * 任何测试去断言「删完列表里没有了」都会通过 —— 因为它确实没了。
 * 缺的不是「删得掉」的断言，而是「**不该立刻删**」的断言。
 *
 * ## 覆盖两个入口
 *
 * - 总览面板的「删除」按钮
 * - 列表项 ⋮ 菜单里的「删除」
 *
 * 两者在实现里都收敛到组件内部的 `requestDelete`，所以一起测；将来新增第三个入口
 * 只要还走那条路，也自动被拦住。
 */
@OptIn(ExperimentalTestApi::class)
class DeleteConnectionConfirmTest {

    private val config = ConnectionConfig(
        id = "del-me",
        name = "要删的连接",
        dialect = DialectType.MYSQL,
        host = "192.168.1.5",
        port = 3306,
        database = "sundays_probe",
        username = "root",
        password = "666666",
        connectionType = ConnectionType.CLIENT_SERVER,
        jdbcUrl = "jdbc:mysql://192.168.1.5:3306/sundays_probe?useSSL=false",
    )

    /** 内存态的连接列表 —— 不碰真实 `connection.json`。 */
    private fun newList() = mutableStateOf(ConnectionList(listOf(config)))

    @Test
    fun `deleting from the overview button asks first and keeps the connection on cancel`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            val list = newList()
            val deleted = mutableListOf<String>()

            setContent {
                MaterialTheme {
                    ConnectionManagerScreen(
                        connections = list.value.connections,
                        selectedConnection = config,
                        editingConnection = null,
                        wizardStep = WizardStep.IDLE,
                        wizardFlow = WizardFlow.NORMAL,
                        connectionStatuses = mapOf(config.id to ConnectionStatus(ConnectionState.DISCONNECTED)),
                        onSelectConnection = {},
                        onNewConnection = {},
                        onQuickConnect = {},
                        onEditConnection = {},
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        // 真删除只由确认框点「删除」才走到；这里记录被删的 id 即可
                        onDeleteConnection = { id -> deleted += id },
                        onCancelEdit = {},
                        onWizardNext = {},
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ---- ① 点总览面板的「删除」：应当只弹框，不删
            onNodeWithText("删除").performClick()
            waitForIdle()
            assertEquals(
                emptyList(), deleted,
                "点「删除」还没确认时**绝不能**真的删 —— 这正是走查里按一下回车连接就没了的原因",
            )
            onNodeWithText("确定要删除连接「要删的连接」吗？").assertIsDisplayed()

            // ---- ② 取消：框消失，什么都没发生
            onNodeWithText("取消").performClick()
            waitForIdle()
            onNodeWithText("确定要删除连接「要删的连接」吗？").assertDoesNotExist()
            assertEquals(emptyList(), deleted, "取消不应触发删除")
            assertEquals(1, list.value.connections.size, "取消后连接应仍在列表里")
        }

    @Test
    fun `the confirm dialog deletes only after the user agrees`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            val list = newList()
            val deleted = mutableListOf<String>()

            setContent {
                MaterialTheme {
                    ConnectionManagerScreen(
                        connections = list.value.connections,
                        selectedConnection = config,
                        editingConnection = null,
                        wizardStep = WizardStep.IDLE,
                        wizardFlow = WizardFlow.NORMAL,
                        connectionStatuses = emptyMap(),
                        onSelectConnection = {},
                        onNewConnection = {},
                        onQuickConnect = {},
                        onEditConnection = {},
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        onDeleteConnection = { id ->
                            deleted += id
                            list.value = list.value.copy(
                                connections = list.value.connections.filterNot { it.id == id },
                            )
                        },
                        onCancelEdit = {},
                        onWizardNext = {},
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            onNodeWithText("删除").performClick()
            waitForIdle()

            // 确认框里也有一个「删除」—— 断言此刻**同时**存在两个入口，
            // 再点第二个（框内那个）。这样点的是框内按钮，不靠「哪个先渲染」去猜。
            val deleteLabels = onAllNodesWithText("删除").fetchSemanticsNodes()
            assertEquals(
                2, deleteLabels.size,
                "确认框打开时应同时存在面板的「删除」与框内的「删除」两个入口",
            )

            onAllNodesWithText("删除")[1].performClick()
            waitForIdle()
            assertEquals(listOf(config.id), deleted, "确认后才应真的删，且只删这一个")
            assertEquals(0, list.value.connections.size, "列表应已移除该连接")
        }

    @Test
    fun `deleting from the overflow menu also asks first`() =
        runComposeUiTest(testTimeout = 2.minutes) {
            val list = newList()
            val deleted = mutableListOf<String>()

            setContent {
                MaterialTheme {
                    ConnectionManagerScreen(
                        connections = list.value.connections,
                        // **不选中连接** —— 于是右栏是空闲入口页、**没有**「删除」按钮，
                        // 页面上的「删除」只剩 ⋮ 菜单里那一个，选择器不会有歧义。
                        // （选中时两个同名按钮并存，靠序号点很容易点错入口而「看起来通过」。）
                        selectedConnection = null,
                        editingConnection = null,
                        wizardStep = WizardStep.IDLE,
                        wizardFlow = WizardFlow.NORMAL,
                        connectionStatuses = emptyMap(),
                        onSelectConnection = {},
                        onNewConnection = {},
                        onQuickConnect = {},
                        onEditConnection = {},
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        onDeleteConnection = { id -> deleted += id },
                        onCancelEdit = {},
                        onWizardNext = {},
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ⋮ 菜单里的「删除」
            onNodeWithContentDescription("更多操作").performClick()
            waitForIdle()
            onAllNodesWithText("删除")[0].performClick()
            waitForIdle()

            assertEquals(
                emptyList(), deleted,
                "⋮ 菜单的「删除」同样必须先确认 —— 它是同一个收口，不该有例外",
            )
            onNodeWithText("确定要删除连接「要删的连接」吗？").assertIsDisplayed()
        }
}
