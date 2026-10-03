package com.kxxnzstdsw.sundays.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.ui.AppearanceState
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemePalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首次启动引导页的行为契约（在 `:desktopApp` 跑 —— `:shared/commonTest` 没有 Compose 测试依赖）。
 *
 * 这一页的全部价值在于「点一下立刻看到真实效果」，所以测试要盯的就是**每一次点选都真的
 * 回调了**（而不是只改了页内某个 local state）。用真 [AppearanceState] 驱动而不是手写
 * lambda 记录器，是为了让「页内状态」与「外部状态」不可能各说各话 ——
 * 若页面只记在 local state 里而没往回写，这里的选中态就会不跟着变。
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {

    private fun androidx.compose.ui.test.ComposeUiTest.render(
        state: AppearanceState,
        dark: Boolean = false,
        onFinish: () -> Unit = {},
    ) {
        setContent {
            SundaysTheme(
                darkTheme = dark,
                palette = state.palette,
                compact = state.compactMode,
            ) {
                OnboardingScreen(
                    palette = state.palette,
                    onPaletteChange = state::selectPalette,
                    themeMode = state.mode,
                    onThemeModeChange = state::selectMode,
                    dark = dark,
                    compactMode = state.compactMode,
                    onCompactModeChange = state::selectCompactMode,
                    onFinish = onFinish,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    // =========================================================================
    // 结构：三组控件一个都不能少
    // =========================================================================

    @Test
    fun `every palette is offered`() = runComposeUiTest {
        render(AppearanceState())
        ThemePalette.entries.forEach {
            onNodeWithText(it.label).assertIsDisplayed()
        }
    }

    @Test
    fun `every theme mode is offered`() = runComposeUiTest {
        render(AppearanceState())
        // 紧凑模式下这一组在初始档，看得见；万一将来布局再变，至少先证明它渲染过
        ThemeMode.entries.forEach {
            onNodeWithText(it.label).assertIsDisplayed()
        }
    }

    @Test
    fun `the three sections are labelled`() = runComposeUiTest {
        render(AppearanceState())
        onNodeWithText("配色主题").assertIsDisplayed()
        onNodeWithText("明暗模式").assertIsDisplayed()
        onNodeWithText("界面密度").assertIsDisplayed()
    }

    // =========================================================================
    // 点选生效：这是本页唯一真正的功能
    // =========================================================================

    @Test
    fun `picking a palette changes the whole app immediately`() = runComposeUiTest {
        val state = AppearanceState()
        render(state)
        assertEquals(ThemePalette.BLUE_GRAY, state.palette)

        onNodeWithText(ThemePalette.CYBERPUNK.label).performClick()
        waitForIdle()

        // 断言的是**外部状态**变了，不是页内显示变了
        assertEquals(ThemePalette.CYBERPUNK, state.palette, "点色卡应回写外部状态（进而落盘 + 换主题）")
    }

    @Test
    fun `picking a theme mode changes the whole app immediately`() = runComposeUiTest {
        val state = AppearanceState()
        render(state)
        assertEquals(ThemeMode.SYSTEM, state.mode)

        onNodeWithText(ThemeMode.DARK.label).performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, state.mode)

        onNodeWithText(ThemeMode.LIGHT.label).performClick()
        waitForIdle()
        assertEquals(ThemeMode.LIGHT, state.mode)
    }

    @Test
    fun `toggling compact mode changes density immediately`() = runComposeUiTest {
        val state = AppearanceState()
        render(state)
        onNode(isToggleable()).assertIsOff()

        onNode(isToggleable()).performClick()
        waitForIdle()
        assertTrue(state.compactMode, "拨开关应回写外部状态（进而缩放 LocalDensity）")
        onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun `the incoming selection is reflected on first render`() = runComposeUiTest {
        // 反向：外部已经选好了什么，页面必须**如实显示**。若页面自己维护一份选中态，
        // 引导页与设置页就会显示两套不同的选择，而用户以为设置页覆盖了引导页的结果。
        val state = AppearanceState(
            initialPalette = ThemePalette.WIN_XP,
            initialMode = ThemeMode.DARK,
            initialCompactMode = true,
        )
        render(state, dark = true)

        onNode(isToggleable()).assertIsOn()
        assertEquals(
            2,
            onAllNodes(isSelectable()).fetchSemanticsNodes()
                .count { it.config[SemanticsProperties.Selected] == true },
            "5 张配色卡 + 3 个明暗单选里，应恰好选中 1 个配色 + 1 个明暗档位",
        )
    }

    @Test
    fun `palette swatches actually differ between the light and dark schemes`() = runComposeUiTest {
        // 这是 [OnboardingScreen] 的 `dark` 参数**存在的前提**：`PaletteCard` 用
        // `option.schemeFor(dark)` 取色，所以一旦 `dark` 为 true / false，同一张色卡必须画出
        // 不同的颜色。锁住这个前提，`dark` 传错（恒为 false、恒为 true）才会立刻显形。
        //
        // 逐个主题比对「同一主题浅色档 vs 深色档」的取色条三色是否相等 ——
        // 若哪天 `schemeFor` 忽略 `useDark`，色卡会全部退化成同一套颜色，本测试转红。
        render(AppearanceState())
        ThemePalette.entries.forEach { option ->
            // 参数名是 `useDark` 不是 `dark` —— 见 ThemePalette.schemeFor 的 KDoc：
            // 叫 `dark` 会遮蔽同名的 `dark` 配色属性，整条 when 退化成 Any
            val light = option.schemeFor(useDark = false)
            val dark = option.schemeFor(useDark = true)
            assertTrue(
                light.surface != dark.surface || light.primary != dark.primary,
                "${option.label} 的浅色 / 深色取色不应完全相同 —— 否则色卡无法反映明暗档位",
            )
        }
    }

    // =========================================================================
    // 收尾
    // =========================================================================

    @Test
    fun `the finish button completes the onboarding`() = runComposeUiTest {
        var finished = false
        val state = AppearanceState(initialOnboardingCompleted = false)
        render(state, onFinish = { finished = true })

        // 刻意**不**调 performScrollTo：收尾条固定在滚动区外面，任何窗口高度下都应直接可点。
        // 若哪天按钮被挪回滚动区里、用户在 1024×768 下够不着，这条会直接红。
        onNodeWithText("开始使用").performClick()
        waitForIdle()

        assertTrue(finished, "「开始使用」必须触发完成回调，否则下次启动引导页会重新出现")
    }

    @Test
    fun `the finish button is visible without scrolling`() = runComposeUiTest {
        // 回归：三组控件在 1024×768 下约 900px 高。按钮原先放在滚动区内，
        // 首次渲染时它的语义 bounds 是 Rect(0,0,0,0) —— 唯一的出口完全在视口外，
        // 用户必须先意识到「下面还有东西」才够得着。固定到底部后必须**直接可点**。
        render(AppearanceState())
        onNodeWithText("开始使用").assertIsDisplayed()
    }

    @Test
    fun `the finish button survives compact mode too`() = runComposeUiTest {
        // 紧凑档把整棵树缩到 85%，理论上更容易放下。但反过来，**将来**若有人把收尾条
        // 挪回滚动区内，这条会比普通档更早暴露问题 —— 两档都断言更保险。
        render(AppearanceState(initialCompactMode = true))
        onNodeWithText("开始使用").assertIsDisplayed()
    }

    @Test
    fun `the header and the finish button are on screen at the same time`() = runComposeUiTest {
        // 引导是「一眼看完」的设计：顶部说明与底部出口应当**同屏可见**，
        // 而不是让人先滚到底才知道还有下一步。
        render(AppearanceState())
        val header = onNodeWithText("欢迎使用 sundays").fetchSemanticsNode().boundsInRoot
        val finish = onNodeWithText("开始使用").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot

        assertTrue(
            header.top >= root.top && finish.bottom <= root.bottom,
            "首尾两个关键节点应同屏可见，实测 header=$header finish=$finish 视口=$root",
        )
        // 收尾条必须在**下**方 —— 否则等于按钮飘到了内容中间
        assertTrue(finish.top > header.bottom, "收尾条应位于内容下方，实测 $header / $finish")
    }

    @Test
    fun `there is no separate skip button`() = runComposeUiTest {
        // 默认值本身就是一份合法答案，「开始使用」已兼任跳过。
        // 单列一个「跳过」反而会让「跳过到底跳到哪」变模糊 —— 而它们的行为其实完全一样。
        render(AppearanceState())
        onAllNodesWithText("跳过").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        onNodeWithText("开始使用").assertIsDisplayed()
    }

    @Test
    fun `choices made before finishing survive`() = runComposeUiTest {
        // 用户先调好外观再点「开始使用」—— 这些选择必须**保留**，不能被引导的默认值覆盖。
        val state = AppearanceState(initialOnboardingCompleted = false)
        render(state, onFinish = state::completeOnboarding)

        onNodeWithText(ThemePalette.BILI_PINK.label).performClick()
        onNodeWithText(ThemeMode.DARK.label).performClick()
        onNodeWithText("开始使用").performClick()
        waitForIdle()

        assertEquals(ThemePalette.BILI_PINK, state.palette)
        assertEquals(ThemeMode.DARK, state.mode)
        assertTrue(state.onboardingCompleted, "完成后应标记为已完成")
    }

    // =========================================================================
    // 设置页重进（firstRun = false）
    // =========================================================================

    private fun androidx.compose.ui.test.ComposeUiTest.renderReplay(
        state: AppearanceState,
        onFinish: () -> Unit = {},
    ) {
        setContent {
            SundaysTheme(darkTheme = false, palette = state.palette, compact = state.compactMode) {
                OnboardingScreen(
                    palette = state.palette,
                    onPaletteChange = state::selectPalette,
                    themeMode = state.mode,
                    onThemeModeChange = state::selectMode,
                    dark = false,
                    compactMode = state.compactMode,
                    onCompactModeChange = state::selectCompactMode,
                    onFinish = onFinish,
                    firstRun = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun `the replay must not claim it is a first run`() = runComposeUiTest {
        // 用户在设置里主动点「重新打开引导」时若还看到「欢迎使用 sundays」，
        // 会以为应用被重置了 —— 首启话术只属于首启。
        renderReplay(AppearanceState())
        onNodeWithText("外观引导").assertIsDisplayed()
        onAllNodesWithText("欢迎使用 sundays").fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "重进引导不应再自称「欢迎使用」")
        }
    }

    @Test
    fun `the replay finishes with a neutral label`() = runComposeUiTest {
        // 已经在主界面里了，再说「开始使用」语义不对
        renderReplay(AppearanceState())
        onNodeWithText("完成").assertIsDisplayed()
        onAllNodesWithText("开始使用").fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "重进引导的收尾按钮不应叫「开始使用」")
        }
    }

    @Test
    fun `the replay still applies and persists the choices`() = runComposeUiTest {
        // 重进不是只读回顾 —— 它是同一套外观状态的另一个编辑入口。
        // 若这条转绿而落盘没跟上，用户会觉得「改了没反应，下次启动又变回去」。
        var finished = false
        val state = AppearanceState(initialOnboardingCompleted = true)
        renderReplay(state, onFinish = { finished = true })

        onNodeWithText(ThemePalette.WIN_2000.label).performClick()
        onNodeWithText(ThemeMode.LIGHT.label).performClick()
        waitForIdle()
        assertEquals(ThemePalette.WIN_2000, state.palette)
        assertEquals(ThemeMode.LIGHT, state.mode)

        onNodeWithText("完成").performClick()
        waitForIdle()
        assertTrue(finished, "「完成」应收起浮层")
        // 重进**不**改 onboardingCompleted —— 它早就是 true
        assertTrue(state.onboardingCompleted)
    }

    @Test
    fun `the replay offers exactly the same three groups`() = runComposeUiTest {
        // 「重新打开引导」应当等价于首启那一页；若某组控件被条件编译 / 漏传，
        // 用户会觉得重进了个简化版
        renderReplay(AppearanceState())
        onNodeWithText("配色主题").assertIsDisplayed()
        onNodeWithText("明暗模式").assertIsDisplayed()
        onNodeWithText("界面密度").assertIsDisplayed()
        ThemePalette.entries.forEach { onNodeWithText(it.label).assertIsDisplayed() }
    }
}
