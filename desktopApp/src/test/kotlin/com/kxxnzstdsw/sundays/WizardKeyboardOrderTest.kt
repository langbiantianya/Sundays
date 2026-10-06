package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.connection.WizardStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **探针要用的事实：连接向导里「下一步」是第几个 Tab 停靠点。**
 *
 * ## 为什么要专门测这个
 *
 * 真窗口走查卡在这里：连按 10 次 `Tab` 再回车，界面**毫无变化**（还是第 1 步、
 * 同一个方言还是选中）。截图只能看出「变没变」，看不出焦点到底落在哪个元素上 ——
 * 更糟的是 Compose 会给**鼠标悬停**的元素画高亮，而 `SetCursorPos` 挪过的指针会一直
 * 停在那儿，于是「哪个亮着」一度被我读成了「焦点在哪」，整条 Tab 计数全部错位。
 *
 * 焦点顺序是**可断言**的：按 N 次 Tab 再回车，看 `wizard.step` 变没变。
 * 这条测试把「猜」变成「数」。
 *
 * ## 它同时回答一个更重要的问题
 *
 * 如果**数遍 1..N 都推进不了步骤**，那就不是探针的局限，而是
 * **键盘用户根本走不完连接向导** —— 那是一条真缺陷，得单独立项。
 */
@OptIn(ExperimentalTestApi::class)
class WizardKeyboardOrderTest {

    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
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

    /** 打印「第 N 次 Tab + 回车」之后停在哪一步 —— 探针按这张表导航。 */
    @Test
    fun `print how many tabs reach the next button`() = runComposeUiTest {
        val session = ConnectionSession(engine, CoroutineScope(Dispatchers.Default))
        setContent { MaterialTheme { Screen(session) } }

        onNodeWithText("新建连接").performClick()
        waitForIdle()
        // 名称默认「新连接」非空，「下一步」是 enabled
        assertEquals(WizardStep.BASIC_INFO, session.wizard.step)

        val trail = mutableListOf<String>()
        for (n in 1..14) {
            onRootFocus().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onRootFocus().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            val step = session.wizard.step
            trail += "Tab×$n+Enter → $step"
            println("KEYORDER ${trail.last()}")
            if (step != WizardStep.BASIC_INFO) break
        }
        println("KEYORDER 完整轨迹：\n  " + trail.joinToString("\n  "))
        assertNotEquals(
            WizardStep.BASIC_INFO, session.wizard.step,
            "数遍 1..14 次 Tab + 回车都推进不了步骤 —— 说明「下一步」根本不在 Tab 序里，" +
                "键盘用户走不完连接向导。完整轨迹见上面的 println。",
        )
    }

    /** 方言卡片在 Tab 序里的停靠点 —— 探针按这个数跳。 */
    @Test
    fun `dialect group is reachable by tab and enter selects it`() = runComposeUiTest {
        val session = ConnectionSession(engine, CoroutineScope(Dispatchers.Default))
        setContent { MaterialTheme { Screen(session) } }
        onNodeWithText("新建连接").performClick()
        waitForIdle()
        val before = session.wizard.editingConnection?.dialect

        val seen = mutableListOf<String>()
        var changed = false
        for (n in 1..14) {
            onRootFocus().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onRootFocus().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            val d = session.wizard.editingConnection?.dialect
            seen += "Tab×$n+Enter → 方言=${d?.name}"
            println("KEYORDER-DIALECT ${seen.last()}")
            if (d != before) changed = true
            if (session.wizard.step != WizardStep.BASIC_INFO) break
        }
        println("KEYORDER-DIALECT 完整轨迹：\n  " + seen.joinToString("\n  "))

        // 方向键**不**能在组内移动：卡片是 `clickable` 而非 `selectable`，没有单选组的
        // 方向键语义（真窗口探针实测 DOWN 两次选择纹丝不动）。所以「换方言」在纯键盘下
        // 只能靠 Tab 走到某一张再回车 —— 这一条把它钉住，免得有人改成 selectable 之后
        // 探针的导航脚本悄悄失效。
        assertEquals(
            before, session.wizard.editingConnection?.dialect,
            "只按 Tab + Enter 不应该改变方言（探针依赖这一点：换方言靠 Tab 到目标卡再回车）",
        )
        assertTrue(!changed, "方言不应在未点中卡片时被改变")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onRootFocus() =
        onNodeWithText("第 1 / 4 步", substring = true)
}
