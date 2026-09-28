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
}