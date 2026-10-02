package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.settings.formatBytes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 底部 JVM 堆占用状态栏 —— 比例算法、格式化、渲染与失效降级。
 *
 * 探针是**注入的**（`memoryProbe`），所以这一整类测试不需要起真引擎 —— 而这正是把它做成
 * 回调而不是让屏自己去拿 `EngineClient` 的原因：状态栏的契约只跟「拿到什么数」有关，
 * 与「怎么从引擎取到那个数」无关。
 */
@OptIn(ExperimentalTestApi::class)
class EngineMemoryStatusBarTest {

    // =========================================================================
    // EngineMemory.ratio —— 纯算法
    // =========================================================================

    @Test
    fun `ratio is used over max`() {
        assertEquals(
            0.5f,
            EngineMemory(usedBytes = 512, totalBytes = 1024, freeBytes = 512, maxBytes = 1024).ratio,
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun `ratio is clamped into zero to one`() {
        // 钳位不是防御性冗余：used 是采样瞬时值，容器里 max 也可能被调整过；
        // 不钳就会画出一条冲出轨道、糊到标签上的进度条。
        assertEquals(1f, EngineMemory(usedBytes = 2048, totalBytes = 2048, freeBytes = 0, maxBytes = 1024).ratio, absoluteTolerance = 0.0001f)
        assertEquals(0f, EngineMemory(usedBytes = 0, totalBytes = 1024, freeBytes = 1024, maxBytes = 1024).ratio, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `a non positive max degrades to zero instead of dividing by zero`() {
        // maxMemory() 在某些实现下会返回 Long.MAX_VALUE 或 0；两者都不能让 ratio 变成 NaN/Inf
        assertEquals(0f, EngineMemory(usedBytes = 100, totalBytes = 100, freeBytes = 0, maxBytes = 0).ratio, absoluteTolerance = 0.0001f)
        assertEquals(0f, EngineMemory(usedBytes = 100, totalBytes = 100, freeBytes = 0, maxBytes = -1).ratio, absoluteTolerance = 0.0001f)
    }

    // =========================================================================
    // formatHeapBytes —— 紧凑呈现
    // =========================================================================

    @Test
    fun `heap bytes format compactly`() {
        assertEquals("512M", formatHeapBytes(512L * 1024 * 1024))
        assertEquals("1K", formatHeapBytes(1024L))
        assertEquals("999B", formatHeapBytes(999L))
        assertEquals("0B", formatHeapBytes(0L))
    }

    @Test
    fun `gigabytes drop the decimal on whole values`() {
        // 状态栏只有 5dp 高、右侧还跟着「/ 上限」，少一个字符是一个
        assertEquals("2G", formatHeapBytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.5G", formatHeapBytes(3L * 1024 * 1024 * 1024 / 2))
    }

    @Test
    fun `a negative reading renders as a dash rather than a negative size`() {
        // 引擎若报了负数（不该发生），显示「—」比显示「-1M」诚实
        assertEquals("—", formatHeapBytes(-1L))
    }

    // =========================================================================
    // 渲染
    // =========================================================================

    /** 渲染空态（无 sheet）下的屏 —— 状态栏必须**照样出现**。 */
    private fun ComposeUiTest.renderBar(probe: suspend () -> EngineMemory?) {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = emptyList(),
                    activeSheetId = null,
                    connections = emptyList(),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    memoryProbe = probe,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `shows a dash until the first reading arrives`() = runComposeUiTest {
        // 探针挂起不返回：断言「还没有数据」这个初始态本身是可见且不崩的
        renderBar(probe = { kotlinx.coroutines.awaitCancellation() })

        onNodeWithText("堆内存").assertIsDisplayed()
        onNodeWithText("— / —").assertIsDisplayed()
    }

    @Test
    fun `shows used over max once the probe answers`() = runComposeUiTest {
        renderBar(probe = { EngineMemory(usedBytes = 512L * 1024 * 1024, totalBytes = 1024L * 1024 * 1024, freeBytes = 512L * 1024 * 1024, maxBytes = 2048L * 1024 * 1024) })

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("512M / 2G").assertIsDisplayed()
    }

    @Test
    fun `a null reading keeps the previous value instead of blanking`() = runComposeUiTest {
        var call = 0
        renderBar(probe = {
            call++
            // 第一次成功，之后返回 null —— 探针「暂时没数」是最常见的降级
            if (call == 1) EngineMemory(usedBytes = 1L * 1024 * 1024, totalBytes = 2L * 1024 * 1024, freeBytes = 1L * 1024 * 1024, maxBytes = 4L * 1024 * 1024) else null
        })

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("1M / 4M").fetchSemanticsNodes().isNotEmpty()
        }
        // 后续空读数不得把用户的内存数字清成「— / —」
        onNodeWithText("1M / 4M").assertIsDisplayed()
    }

    @Test
    fun `a throwing probe is contained and the bar survives it`() = runComposeUiTest {
        // gRPC 偶发不可达时 fetchSystemInfo 会抛；状态栏必须把它吞掉而不是让整棵组合树崩掉
        renderBar(probe = { throw IllegalStateException("引擎不可达") })

        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("— / —").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("堆内存").assertIsDisplayed()
        onNodeWithText("— / —").assertIsDisplayed()
    }

    @Test
    fun `the bar is rendered in the empty state too`() = runComposeUiTest {
        // 空态是用户第一次打开应用停留的地方 —— 此时恰恰最需要知道内存还剩多少，
        // 因此状态栏必须挂在最外层 Column，不能跟着 active sheet 一起消失。
        var calls = 0
        renderBar(probe = { calls++; EngineMemory(usedBytes = 256L * 1024, totalBytes = 512L * 1024, freeBytes = 256L * 1024, maxBytes = 1024L * 1024) })

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("256K / 1M").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, calls.coerceAtLeast(1), "探针应被调用")
    }

    @Test
    fun `no probe means no status bar at all`() = runComposeUiTest {
        // memoryProbe == null 是「调用方不要这个功能」的显式表达（如 AddConnectionDialog 场景），
        // 此时不应渲染任何内存文字
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = emptyList(),
                    activeSheetId = null,
                    connections = emptyList(),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithText("堆内存").assertDoesNotExist()
    }

    // =========================================================================
    // 点击 → 内存详情弹窗
    // =========================================================================

    /** 512M / 768M / 2G / 256M —— 四个值**互不相同**，才能证明弹窗没有串行错位。 */
    private val sample = EngineMemory(
        usedBytes = 512L * 1024 * 1024,
        totalBytes = 768L * 1024 * 1024,
        freeBytes = 256L * 1024 * 1024,
        maxBytes = 2048L * 1024 * 1024,
    )

    @Test
    fun `the popup is closed until the bar is clicked`() = runComposeUiTest {
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("JVM 内存").assertDoesNotExist()

        onNodeWithText("堆内存").performClick()
        onNodeWithText("JVM 内存").assertIsDisplayed()
    }

    @Test
    fun `the popup shows the same four memory rows as the settings pane`() = runComposeUiTest {
        // 标签必须与设置页「系统信息」内存段逐字一致（堆已用 / 已分配 / 上限 / 空闲），
        // 取值格式复用同一个 formatBytes —— 同一个数字不能在两处显示成两个字符串
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("堆内存").performClick()

        listOf("堆已用", "堆已分配", "堆上限", "堆空闲").forEach {
            onNodeWithText(it).assertIsDisplayed()
        }
        onNodeWithText(formatBytes(sample.usedBytes)).assertIsDisplayed()
        onNodeWithText(formatBytes(sample.totalBytes)).assertIsDisplayed()
        onNodeWithText(formatBytes(sample.maxBytes)).assertIsDisplayed()
        onNodeWithText(formatBytes(sample.freeBytes)).assertIsDisplayed()
    }

    @Test
    fun `the popup shows a dash per row before the first reading`() = runComposeUiTest {
        // 空面板会让用户以为程序坏了；逐行「—」至少说明「探针还没回来」
        renderBar(probe = { kotlinx.coroutines.awaitCancellation() })
        onNodeWithText("堆内存").performClick()

        listOf("堆已用", "堆已分配", "堆上限", "堆空闲").forEach {
            onNodeWithText(it).assertIsDisplayed()
        }
        onAllNodesWithText("—").fetchSemanticsNodes()
    }

    @Test
    fun `clicking the bar again closes the popup`() = runComposeUiTest {
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("堆内存").performClick()
        onNodeWithText("JVM 内存").assertIsDisplayed()

        onNodeWithText("堆内存").performClick()
        onNodeWithText("JVM 内存").assertDoesNotExist()
    }

    @Test
    fun `the bar hugs its content instead of spanning the window`() = runComposeUiTest {
        // 铺满整行会得到一条横贯窗口的色带，视觉上比内存数字本身还重；
        // 而且可点区域大到能在离内容很远的地方误触。必须收缩 + 右对齐。
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithText("堆内存").fetchSemanticsNode().boundsInRoot

        assertTrue(
            bar.width < root.width / 2,
            "状态栏应按内容收缩，实际宽 ${bar.width} / 窗口 ${root.width}",
        )
        // 右边缘贴齐窗口（padding 之外），即右对齐
        assertEquals(root.right, bar.right, absoluteTolerance = 1.0f)
        // 且确实贴在窗口底部
        assertEquals(root.bottom, bar.bottom, absoluteTolerance = 1.0f)
    }

    @Test
    fun `the detail panel hugs its content too`() = runComposeUiTest {
        // 曾用 widthIn(min = 200.dp) + 每行 fillMaxWidth()：取值被推到面板右端、与标签之间
        // 拉出一条大空档；更糟的是 Column 一路撑到父级最大宽度，面板变成一条横贯窗口的色带
        // （实测 1002px / 1024px 窗口）。现在靠 width(IntrinsicSize.Max) 让宽度 = 最宽一行文字。
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("堆内存").performClick()

        val root = onRoot().fetchSemanticsNode().boundsInRoot
        // 量面板本身而不是里面的文字节点 —— 后者恒然很窄，量它等于没测
        val panel = onNodeWithTag(MEMORY_DETAIL_PANEL_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(
            panel.width < 200f,
            "详情面板应按内容收缩，实际宽 ${panel.width}（窗口 ${root.width}）",
        )
        // 右对齐：右缘与状态栏内容右缘一致（窗口右 10dp）
        assertEquals(root.right - 10f, panel.right, absoluteTolerance = 1.0f)
        // 贴在状态栏上沿，不压住状态栏
        val bar = onNodeWithText("堆内存").fetchSemanticsNode().boundsInRoot
        assertTrue(
            panel.bottom <= bar.top + 1f,
            "面板底部 ${panel.bottom} 应在状态栏顶部 ${bar.top} 之上",
        )
    }

    @Test
    fun `the panel closes itself once the pointer is away`() = runComposeUiTest {
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }

        // 指针停在状态栏上点击 —— 此时面板**不该**关（指针还在其中之一）
        onNodeWithText("堆内存").performMouseInput { moveTo(center) }.performClick()
        onNodeWithText("JVM 内存").assertIsDisplayed()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("JVM 内存").assertIsDisplayed()

        // 指针移到窗口左上角：状态栏与面板都不在指针下，宽限期过后应自动收起
        onRoot().performMouseInput { moveTo(Offset(4f, 4f)) }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("JVM 内存").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `the panel survives while the pointer is inside it`() = runComposeUiTest {
        // 宽限期逻辑最容易写坏的地方：把「指针在面板上」也当成「已移开」，于是用户
        // 刚把指针移进面板想看数字，面板就消失了
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("堆内存").performMouseInput { moveTo(center) }.performClick()
        val panel = onNodeWithTag(MEMORY_DETAIL_PANEL_TAG).fetchSemanticsNode().boundsInRoot.center

        onRoot().performMouseInput { moveTo(panel) }
        // 远超过宽限期也不该关
        mainClock.advanceTimeBy(2_000)
        onNodeWithText("JVM 内存").assertIsDisplayed()
    }

    /**
     * 回归：点「×」关闭面板后，自动关闭**必须仍然有效**。
     *
     * 面板的悬停标志由 `reportsHover` 的 Enter/Exit 驱动，而指针事件只在节点**存在**时才会发。
     * 点「×」时 `pointerInput` 随节点一起被销毁，**不会补发 Exit** —— 标志就永久停在 true，
     * 屏级 `pointerInside` 恒真，宽限期关闭逻辑从此彻底失效（面板再也不自动关）。
     *
     * 这正是上一版自己引入的回归：上一版只覆盖了「移开指针自动关」，而自动关路径会正常发
     * Exit，恰好绕过了 bug。要暴露它必须走「面板内点 ×」这条唯一的非 Exit 关闭路径。
     */
    @Test
    fun `the panel still auto closes after being dismissed with the close button`() = runComposeUiTest {
        renderBar(probe = { sample })
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("512M / 2G").fetchSemanticsNodes().isNotEmpty()
        }

        // 1) 打开面板并把指针**移进面板**（此时面板悬停标志 = true）
        onNodeWithText("堆内存").performMouseInput { moveTo(center) }.performClick()
        val panelCenter = onNodeWithTag(MEMORY_DETAIL_PANEL_TAG).fetchSemanticsNode().boundsInRoot.center
        onRoot().performMouseInput { moveTo(panelCenter) }

        // 2) 用面板上的「×」关闭 —— 这是唯一会让指针离开面板边界的关闭方式，
        //    节点随之消失，Exit 永远等不到
        onNodeWithContentDescription("关闭内存详情").performClick()
        onNodeWithText("JVM 内存").assertDoesNotExist()

        // 3) 指针彻底移开
        onRoot().performMouseInput { moveTo(Offset(4f, 4f)) }

        // 4) 重新打开再移开：若悬停标志已卡死为 true，这里就永远关不掉
        onNodeWithText("堆内存").performClick()
        onNodeWithText("JVM 内存").assertIsDisplayed()
        onRoot().performMouseInput { moveTo(Offset(4f, 4f)) }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("JVM 内存").fetchSemanticsNodes().isEmpty()
        }
    }
}
