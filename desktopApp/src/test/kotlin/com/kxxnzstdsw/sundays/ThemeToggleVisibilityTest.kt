package com.kxxnzstdsw.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 日夜切换按钮的**可见性契约** —— 改动后它是**只存在于设置页**的控件。
 *
 * ## 契约
 *
 * | 界面 | 日夜切换按钮 | 理由 |
 * |---|---|---|
 * | 连接管理首屏 | ❌ 不出现 | 标题行只留 ⚙ 设置入口 |
 * | 数据库浏览屏（含各 sheet 工具栏） | ❌ 不出现 | 同上 |
 * | `AddConnectionDialog` 弹窗 | ❌ 不出现 | 模态弹窗内不提供应用级外观开关 |
 * | **设置页「个性化」** | ✅ 出现 | **唯一**的明暗切换入口 |
 *
 * ## 为什么值得单独钉住
 *
 * 按钮此前放在**四个**入口（两个面板标题行、设置页、未来快捷键），而 `AppearanceState`
 * 的落盘只挂在状态对象上（见 `ThemeMode.kt`）。入口一多就容易出现「某处改了没落盘」。
 * 现在收敛到设置页一处，切换路径唯一，这条负向断言防止它被重新塞回标题行。
 */
@OptIn(ExperimentalTestApi::class)
class ThemeToggleVisibilityTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: com.kxxnzstdsw.engine.IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-theme-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        // 磁盘上没有 drivers/ dialects/ 目录：方言与驱动全部来自应用类路径
        engine = com.kxxnzstdsw.engine.IdbEngine(
            driversDir = File("/nonexistent"),
            dialectsDir = File("/nonexistent"),
        )
    }

    @After
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        try { com.kxxnzstdsw.pool.PoolManager.closeAll() } catch (_: Exception) {}
        tempHome.deleteRecursively()
    }

    private val toggleDescriptions = arrayOf(
        "主题：跟随系统，切换为浅色",
        "主题：浅色，切换为深色",
        "主题：深色，切换为跟随系统",
    )

    @Test
    fun `first screen has no theme toggle`() = runComposeUiTest {
        setContent { MaterialTheme { MainScreen(engine, com.kxxnzstdsw.sundays.ui.rememberAppearanceState()) } }

        // 首屏（连接管理）标题行只剩 ⚙ 设置入口，不应有日夜切换
        toggleDescriptions.forEach { desc ->
            onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
    }

    @Test
    fun `browser screen and dialogs have no theme toggle`() = runComposeUiTest {
        ConnectionStorageTestSupport.seedH2("sheet-toggle", "切换测试 H2")
        setContent { MaterialTheme { MainScreen(engine, com.kxxnzstdsw.sundays.ui.rememberAppearanceState()) } }

        onAllNodesWithText("切换测试 H2")[0].performClick()
        onAllNodesWithText("连接")[0].performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }
        // 切到第二屏（数据库浏览）后仍不应出现
        toggleDescriptions.forEach { desc ->
            onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }

        // AddConnectionDialog 开着时同样不应出现
        onAllNodesWithContentDescription("添加连接")[0].performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("添加数据库连接").fetchSemanticsNodes().isNotEmpty()
        }
        toggleDescriptions.forEach { desc ->
            onAllNodesWithContentDescription(desc).assertCountEquals(0)
        }
    }
}

/** 供本文件使用的连接播种工具（避免与其他测试类的 fixture 互相影响）。 */
private object ConnectionStorageTestSupport {
    fun seedH2(id: String, name: String) {
        com.kxxnzstdsw.sundays.connection.ConnectionStorage.upsert(
            com.kxxnzstdsw.sundays.connection.ConnectionConfig(
                id = id, name = name,
                dialect = com.kxxnzstdsw.sundays.connection.DialectType.H2,
                database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
    }
}
