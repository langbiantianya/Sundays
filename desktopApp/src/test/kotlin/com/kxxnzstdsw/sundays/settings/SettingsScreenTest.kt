package com.kxxnzstdsw.sundays.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.ui.AppearanceState
import com.kxxnzstdsw.sundays.ui.SystemInfoRefresh
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemePalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置页行为契约（在 `:desktopApp` 跑 —— `:shared/commonTest` 没有 Compose 测试依赖）。
 *
 * 覆盖用户看得见、坏掉就难受的几件事：
 * 1. 左分类 / 右内容结构 + **左上角返回按钮**
 * 2. **配色主题与明暗是两个独立单选组**（双轴模型）
 * 3. **自动刷新间隔可选项**（关闭 / 10 / 5 / 2 / 1 秒）与选择回写
 * 4. 改档位要**落盘**（`onChange` 钩子被调用，不是只改内存字段）
 * 5. 系统信息三态渲染
 * 6. **紧凑模式开关**可来回切换、反映传入档位、并与其他轴互不干扰
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    @Composable
    private fun render(
        state: AppearanceState,
        systemInfo: SystemInfoState = SystemInfoState.Idle,
        onRequest: () -> Unit = {},
        onBack: () -> Unit = {},
        onOpenOnboarding: () -> Unit = {},
    ) {
        MaterialTheme {
            SettingsScreen(
                palette = state.palette,
                onPaletteChange = state::selectPalette,
                themeMode = state.mode,
                onThemeModeChange = state::selectMode,
                systemInfoRefresh = state.systemInfoRefresh,
                onSystemInfoRefreshChange = state::selectSystemInfoRefresh,
                systemInfo = systemInfo,
                onRequestSystemInfo = onRequest,
                onBack = onBack,
                compactMode = state.compactMode,
                onCompactModeChange = state::selectCompactMode,
                onOpenOnboarding = onOpenOnboarding,
            )
        }
    }

    // =========================================================================
    // 「重新打开引导」入口
    // =========================================================================

    @Test
    fun `the personalization pane offers a way back into the onboarding`() {
        runComposeUiTest {
            setContent { render(AppearanceState()) }
            // 引导页做的正是这三组事，入口必须和它们在**同一个分类**里；
            // 放到「系统信息」那边会让入口与它要配置的东西失去关联。
            // 该分组在滚动区下方（5 配色 + 3 明暗 + 密度 + 入口），需先滚到底再断言。
            onNodeWithText("重新打开引导").performScrollTo().assertIsDisplayed()
            onNodeWithText("外观引导").assertIsDisplayed()
        }
    }

    @Test
    fun `the onboarding entry invokes its callback`() {
        runComposeUiTest {
            var opened = 0
            setContent { render(AppearanceState(), onOpenOnboarding = { opened++ }) }

            onNodeWithText("重新打开引导").performScrollTo().performClick()
            assertEquals(1, opened, "按钮应触发 onOpenOnboarding")
        }
    }

    @Test
    fun `the onboarding entry is absent from the system info category`() {
        // 引导只管外观；混进系统信息分类会让人以为它还能配别的东西
        runComposeUiTest {
            setContent { render(AppearanceState()) }
            onNodeWithText("系统信息").performClick()
            waitForIdle()
            onNodeWithText("重新打开引导").assertDoesNotExist()
        }
    }

    @Test
    fun `both categories are listed and default to personalization`() {        runComposeUiTest {
            setContent { render(AppearanceState()) }

            // 「个性化」出现两次是预期结构：左侧分类行 + 右侧内容标题
            onAllNodesWithText("个性化").assertCountEquals(2)
            onAllNodesWithText("系统信息").assertCountEquals(1)
            onNodeWithText("配色主题").assertIsDisplayed()
        }
    }

    @Test
    fun `back button is at top-left and invokes the callback`() {
        runComposeUiTest {
            var backs = 0
            setContent { render(AppearanceState(), onBack = { backs++ }) }

            val back = onNodeWithContentDescription("返回")
            back.assertIsDisplayed()
            back.performClick()
            assertEquals(1, backs, "左上角返回按钮应触发 onBack")
        }
    }

    @Test
    fun `palette and brightness are separate choice groups`() {
        runComposeUiTest {
            val state = AppearanceState()
            setContent { render(state) }

            // 配色组两项都在
            onNodeWithText("蓝灰 IDE").assertIsDisplayed()
            onNodeWithText("赛博朋克").assertIsDisplayed()
            // 明暗组三项都在 —— 两组同屏共存即双轴模型
            onNodeWithText("跟随系统").assertIsDisplayed()
            onNodeWithText("浅色").assertIsDisplayed()
            onNodeWithText("深色").assertIsDisplayed()

            // 选赛博朋克：只改配色，明暗不动
            onNodeWithText("赛博朋克").performClick()
            assertEquals(ThemePalette.CYBERPUNK, state.palette)
            assertEquals(ThemeMode.SYSTEM, state.mode, "换配色不应改动明暗")

            // 选深色：只改明暗，配色不动（双轴互不干扰的关键断言）
            onNodeWithText("深色").performClick()
            assertEquals(ThemeMode.DARK, state.mode)
            assertEquals(ThemePalette.CYBERPUNK, state.palette, "换明暗不应改回配色")
        }
    }

    @Test
    fun `change fires the persist hook for every axis`() {
        runComposeUiTest {
            val persisted = mutableListOf<AppearanceState>()
            val state = AppearanceState(onChange = { persisted += it })
            setContent { render(state) }

            onNodeWithText("赛博朋克").performClick()
            assertEquals(ThemePalette.CYBERPUNK, persisted.last().palette, "配色变更必须落盘")

            onNodeWithText("深色").performClick()
            assertEquals(ThemeMode.DARK, persisted.last().mode, "明暗变更必须落盘")
        }
    }

    @Test
    fun `system info offers all refresh intervals`() {
        runComposeUiTest {
            val state = AppearanceState()
            setContent { render(state) }
            onNodeWithText("系统信息").performClick()

            // 需求指定的四档 + 关闭，全部可见
            listOf("关闭", "10 秒", "5 秒", "2 秒", "1 秒").forEach {
                onNodeWithText(it).assertIsDisplayed()
            }

            onNodeWithText("2 秒").performClick()
            assertEquals(SystemInfoRefresh.S2, state.systemInfoRefresh)
            assertEquals(
                SystemInfoRefresh.S2,
                state.systemInfoRefresh,
                "刷新间隔选择应回写到状态（进而落盘）",
            )
        }
    }

    @Test
    fun `switching to system info requests the data`() {
        runComposeUiTest {
            var requests = 0
            setContent { render(AppearanceState(), onRequest = { requests++ }) }

            onNodeWithText("系统信息").performClick()
            assertEquals(1, requests, "首次切入系统信息应请求一次")
        }
    }

    @Test
    fun `system info pane shows loading instead of stale data`() {
        runComposeUiTest {
            setContent {
                render(AppearanceState(), systemInfo = SystemInfoState.Loading)
            }
            onNodeWithText("系统信息").performClick()

            onNodeWithText("读取系统信息…").assertIsDisplayed()
            onAllNodesWithText("JVM 版本").assertCountEquals(0)
        }
    }

    @Test
    fun `system info pane renders engine values`() {
        runComposeUiTest {
            val info = SystemInfo(
                jvmVersion = "25.0.4.1",
                jvmVendor = "Red Hat",
                jvmName = "OpenJDK 64-Bit Server VM",
                osName = "Linux",
                osArch = "amd64",
                osVersion = "7.2",
                availableProcessors = 16,
                memoryMax = 4L * 1024 * 1024 * 1024,
                memoryTotal = 2L * 1024 * 1024 * 1024,
                memoryUsed = 512L * 1024 * 1024,
                memoryFree = 1536L * 1024 * 1024,
                uptimeMillis = 3_723_000L,   // 1 小时 2 分
                pid = 4242,
            )
            setContent {
                render(AppearanceState(), systemInfo = SystemInfoState.Loaded(info))
            }
            onNodeWithText("系统信息").performClick()

            onNodeWithText("JVM 版本").assertIsDisplayed()
            onNodeWithText("25.0.4.1").assertIsDisplayed()
            onNodeWithText("Red Hat").assertIsDisplayed()
            onNodeWithText("Linux 7.2").assertIsDisplayed()
            onNodeWithText("16 个").assertIsDisplayed()
            // 字节转可读（MB 档一位小数、GB 档两位）+ 时长格式化
            onNodeWithText("512.0 MB").assertIsDisplayed()
            onNodeWithText("4.00 GB").assertIsDisplayed()
            onNodeWithText("1 小时 2 分").assertIsDisplayed()
            onNodeWithText("4242").assertIsDisplayed()
        }
    }

    @Test
    fun `system info pane shows failure message`() {
        runComposeUiTest {
            setContent {
                render(AppearanceState(), systemInfo = SystemInfoState.Failed("引擎不可达"))
            }
            onNodeWithText("系统信息").performClick()
            onNodeWithText("读取失败：引擎不可达").assertIsDisplayed()
        }
    }

    @Test
    fun `auto refresh keeps requesting while the interval is on`() {
        runComposeUiTest {
            var requests = 0
            val state = AppearanceState(initialSystemInfoRefresh = SystemInfoRefresh.S1)
            setContent {
                render(
                    state,
                    systemInfo = SystemInfoState.Loaded(SystemInfo(jvmVersion = "x")),
                    onRequest = { requests++ },
                )
            }
            onNodeWithText("系统信息").performClick()
            val afterSwitch = requests

            // 1 秒档：等它至少再触发两次（周期若没生效，这里会超时失败）
            waitUntil(timeoutMillis = 10_000) { requests >= afterSwitch + 2 }
        }
    }

    @Test
    fun `manual refresh button triggers a request`() {
        runComposeUiTest {
            var requests = 0
            setContent {
                render(
                    AppearanceState(),
                    systemInfo = SystemInfoState.Loaded(SystemInfo(jvmVersion = "x")),
                    onRequest = { requests++ },
                )
            }
            onNodeWithText("系统信息").performClick()
            val afterSwitch = requests
            onNodeWithText("刷新").performClick()
            assertTrue(requests > afterSwitch, "「刷新」按钮应再发一次请求")
        }
    }

    @Test
    fun `compact mode is off by default and toggles both ways`() {
        runComposeUiTest {
            val state = AppearanceState()
            setContent { render(state) }

            // 「界面密度」排在配色（5 项）+ 明暗（3 项）之后，测试视口下在折叠线以下 ——
            // 必须先滚过去，否则 performClick 的坐标会落在别的节点上，且不报错。
            onNodeWithText("紧凑模式").performScrollTo().assertIsDisplayed()
            onNode(isToggleable()).assertIsOff()
            assertFalse(state.compactMode, "默认应为标准密度")

            onNodeWithText("紧凑模式").performClick()
            assertTrue(state.compactMode, "点开关应切到紧凑")
            onNode(isToggleable()).assertIsOn()

            onNodeWithText("紧凑模式").performClick()
            assertFalse(state.compactMode, "再点一次应回到标准")
            onNode(isToggleable()).assertIsOff()
        }
    }

    @Test
    fun `compact mode reflects the state it was given`() {
        // 开关渲染的是**传入的档位**而不是自己存一份：两者一旦脱节，就会出现
        // 「重启后开关显示关闭、界面却是紧凑的」这种没法自查的现象。
        runComposeUiTest {
            setContent { render(AppearanceState(initialCompactMode = true)) }
            onNode(isToggleable()).assertIsOn()
        }
    }

    @Test
    fun `compact toggle is persisted and independent of the other axes`() {
        runComposeUiTest {
            val persisted = mutableListOf<AppearanceState>()
            val state = AppearanceState(onChange = { persisted += it })
            setContent { render(state) }

            onNodeWithText("紧凑模式").performScrollTo().performClick()

            assertTrue(persisted.last().compactMode, "紧凑变更必须落盘")
            // 尺度轴与配色 / 明暗正交：动它不该顺带改别人，也不该被别人改掉
            assertEquals(ThemePalette.BLUE_GRAY, persisted.last().palette)
            assertEquals(ThemeMode.SYSTEM, persisted.last().mode)
        }
    }
}
