package com.kxxnzstdsw.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.DialectType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * 顶层导航的 Compose UI 测试 —— 验证新模型「连接管理 → 数据库浏览（单向）」：
 *
 * 1. 首屏是连接管理，**不渲染**顶层导航条（无 chip 切换入口）。
 * 2. 点列表项 → 选中停留在总览面板，仍无导航条。
 * 3. 点总览面板的「连接」 → 建池并跳到数据库浏览；导航条只显示「数据库浏览」chip（无返回入口）。
 *
 * 这是交付要求「点击连接后跳转 / 不要在连接管理显示标签切换 / 跳转后不显示连接管理标签」在真实组合上的可观察契约。
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
    fun `nav bar is hidden on first screen and only shows database chip after entering browser`() = runComposeUiTest {
        ConnectionStorage.upsert(
            ConnectionConfig(
                id = "nav-1",
                name = "导航测试",
                dialect = DialectType.H2,
                database = "shop",
                // 直接给出 URL —— 持久化层只存 jdbcUrl，加载后字段经 parseJdbcUrl 反推
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
        setContent { MaterialTheme { MainScreen(engine) } }

        // 首屏是连接管理 —— 列表 + 概览，无任何 chip 文本（导航条不渲染）
        onNodeWithText("连接列表").assertExists()
        onAllNodesWithText("连接管理").assertCountEquals(0)
        onAllNodesWithText("数据库浏览").assertCountEquals(0)

        // 选中列表项 → 留在连接管理，导航条仍不渲染
        onNodeWithText("导航测试").performClick()
        onNodeWithText("连接").assertExists()            // 总览面板上的「连接」按钮
        onAllNodesWithText("连接管理").assertCountEquals(0)
        onAllNodesWithText("数据库浏览").assertCountEquals(0)

        // 点总览「连接」 → 建池 + 跳到数据库浏览
        onNodeWithText("连接").performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("数据库 / 表").fetchSemanticsNodes().isNotEmpty()
        }

        // 进入数据库浏览后：导航条只显示「数据库浏览」chip —— 没有返回入口
        onAllNodesWithText("连接管理").assertCountEquals(0)
        assertEquals(
            1,
            onAllNodesWithText("数据库浏览").fetchSemanticsNodes().size,
            "DATABASE chip 应当只渲染一次",
        )
    }
}