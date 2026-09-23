package com.kxxnzstdsw.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * 顶层导航的 Compose UI 测试 —— 验证应用启动落在「连接管理」，点击导航可切到「数据库浏览」。
 *
 * 这是交付要求「添加前端导航 + 第二个界面」在真实组合上的可观察契约：
 * 导航条渲染两个目标，切换后第二个屏幕（其左侧「数据库 / 表」树面板）出现。
 */
@OptIn(ExperimentalTestApi::class)
class MainScreenNavTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-nav-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    @Test
    fun `nav bar switches from connection manager to database browser`() = runComposeUiTest {
        setContent { MaterialTheme { MainScreen(engine) } }

        // 两个导航目标都在
        onNodeWithText("连接管理").assertExists()
        onNodeWithText("数据库浏览").assertExists()

        // 默认落在连接管理：出现其左侧列表入口；数据库浏览屏幕尚未展示
        onNodeWithText("新建连接").assertExists()
        assertEquals(
            0,
            onAllNodesWithText("数据库 / 表").fetchSemanticsNodes().size,
            "database browser panel should not be rendered initially",
        )

        // 切到数据库浏览
        onNodeWithText("数据库浏览").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("数据库 / 表").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("数据库 / 表").assertExists()
        // 未选择连接 → 第二屏给出「未连接」提示
        onNodeWithText("未连接").assertExists()
    }
}
