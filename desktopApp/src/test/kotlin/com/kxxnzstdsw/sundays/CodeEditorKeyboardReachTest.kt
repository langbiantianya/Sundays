package com.kxxnzstdsw.sundays

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **代码编辑器的键盘可达性** —— 守住「Tab 能走进编辑器」这条契约。
 *
 * ## 为什么要有这条
 *
 * 真窗口走查时，从「执行造数」按钮按一次 Tab，焦点直接跳到左侧树的「＋」
 * （按回车弹出「添加数据库连接」弹窗）—— 中间没有任何一站是编辑器。
 * 于是我一度以为「编辑器不在 Tab 序里、键盘用户完全用不了两个工作台」。
 *
 * 本测试就是来**否掉这个结论**的：单独渲染 `CodeEditorWithToolbar` 时，
 * 连续 Tab 之内焦点确实会落到编辑器上。**是这条断言把一个过强的判断拉回事实**：
 * 编辑器可聚焦，真实界面里只是**遍历顺序与直觉相反**（编辑器排在工具条按钮之前，
 * 于是「从执行造数往后 Tab」正好绕开了它）。
 *
 * ## 为什么仍然值得留着
 *
 * 「编辑器可聚焦」是一条会被无声破坏的契约：哪天有人给它加一层
 * `focusProperties { canFocus = false }`、或换掉 `BasicTextField`，
 * 界面看上去毫无变化，而**键盘用户会彻底失去两个工作台**。
 * 这类回归没有任何功能测试能撞出来 —— 所以显式钉住。
 *
 * ## 走查方法上的教训（同一件事的另一面）
 *
 * 光有这条断言不够：真实界面里**大量控件不画焦点指示**，
 * 于是「按了 N 次 Tab 后焦点在哪」只能靠截图猜。本测试能给出确定答案，
 * 真窗口探针给不出 —— 这个差距就是 §9.9 记的那个卡点。
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorKeyboardReachTest {

    @Test
    fun `the editor is reachable by tabbing`() = runComposeUiTest(
        testTimeout = 60.seconds,
    ) {
        registerBuiltinEditors()

        setContent {
            SundaysTheme {
                CodeEditorWithToolbar(
                    text = "SELECT 1",
                    onTextChange = {},
                    languageId = "sql",
                    onLanguageChange = {},
                )
            }
        }
        waitForIdle()

        // 先确认编辑器节点确实存在且可编辑 —— 否则「Tab 不到」只是因为它没渲染
        onNode(hasSetTextAction()).assertExists("编辑器应渲染为可编辑节点")

        val root = onRoot()
        var reached = false
        repeat(12) {
            root.performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            val focused = runCatching {
                onNode(hasSetTextAction()).assertIsFocused()
                true
            }.getOrDefault(false)
            if (focused) {
                reached = true
                return@repeat
            }
        }
        check(reached) {
            "连续 12 次 Tab 之后焦点仍未落到编辑器 —— 键盘用户将无法使用 SQL / 造数工作台"
        }
    }
}
