package com.kxxnzstdsw.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.rememberThemeModeState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * 日夜切换按钮的**可见性契约**。
 *
 * ## 为什么这条要单独钉住
 *
 * `ConnectionManagerScreen` 被两个界面复用：首屏（连接管理）与 `AddConnectionDialog` 弹窗
 * （弹窗内嵌同一份屏幕）。按钮由 `themeMode: ThemeMode?` 控制显隐：
 * 首屏传档位 → 显示；弹窗不传 → **不显示**。
 *
 * 这正是上一轮的缺陷形态：弹窗拿到参数默认值 `SYSTEM` + 空回调，按钮照常渲染，
 * 用户点下去毫无反应 —— 一个「死按钮」。它能编译、测试全绿，只有真正打开弹窗才看得见。
 * 因此这里用「弹窗里不能出现该按钮」把它钉住。
 *
 * 另外验证按钮真的能改档位（不是摆设），以及浏览器屏的按钮在最外层（打开第二个 sheet 后
 * 仍应存在）。
 */
@OptIn(ExperimentalTestApi::class)
class ThemeToggleVisibilityTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-theme-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    private fun seed(id: String, name: String) {
        ConnectionStorage.upsert(
            ConnectionConfig(
                id = id, name = name, dialect = DialectType.H2, database = "shop",
                jdbcUrl = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            )
        )
    }

    @Test
    fun `first screen shows the theme toggle and cycles the mode`() = runComposeUiTest {
        // rememberThemeModeState() 是 @Composable，必须在 setContent 内建；但要在测试里
        // 断言 mode，就得把实例提到外面 —— 用 lateinit 在组合内赋值，组合跑完即可读。
        lateinit var state: com.kxxnzstdsw.sundays.ui.ThemeModeState
        setContent {
            val s = rememberThemeModeState()
            state = s
            MaterialTheme { MainScreen(engine, s) }
        }

        // 首屏 = 连接管理，传了档位 → 按钮存在
        onAllNodesWithContentDescription("主题：跟随系统，切换为浅色").assertCountEquals(1)

        // 点一下：SYSTEM → LIGHT，按钮文案随之变化（证明回调真的写回了状态）
        onAllNodesWithContentDescription("主题：跟随系统，切换为浅色")[0].performClick()
        onAllNodesWithContentDescription("主题：浅色，切换为深色").assertCountEquals(1)

        // 再点：LIGHT → DARK
        onAllNodesWithContentDescription("主题：浅色，切换为深色")[0].performClick()
        onAllNodesWithContentDescription("主题：深色，切换为跟随系统").assertCountEquals(1)

        // 闭环：第三下回到 SYSTEM
        onAllNodesWithContentDescription("主题：深色，切换为跟随系统")[0].performClick()
        onAllNodesWithContentDescription("主题：跟随系统，切换为浅色").assertCountEquals(1)
    }

    @Test
    fun `add connection dialog does not show the theme toggle`() = runComposeUiTest {
        seed("theme-dialog", "弹窗主题测试 H2")
        setContent { MaterialTheme { MainScreen(engine, rememberThemeModeState()) } }

        // 切到第二屏（连上 → 打开 sheet），点「＋」打开 AddConnectionDialog
        onAllNodesWithText("弹窗主题测试 H2")[0].performClick()
        onAllNodesWithText("连接")[0].performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription("添加连接")[0].performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("添加数据库连接").fetchSemanticsNodes().isNotEmpty()
        }

        // 弹窗开着时，主题按钮总数仍为 1（浏览器屏最外层那一个）——
        // 弹窗内的那份若还在渲染，总数会变成 2
        val toggles = onAllNodesWithContentDescription("主题：跟随系统，切换为浅色")
        assertEquals(
            1,
            toggles.fetchSemanticsNodes().size,
            "AddConnectionDialog 内不应出现日夜切换按钮（模态弹窗里改全局主题会让人失去判断）",
        )
    }

    @Test
    fun `browser screen keeps the toggle in the outermost layer across sheets`() = runComposeUiTest {
        seed("sheet-a", "A 连接")
        lateinit var state: com.kxxnzstdsw.sundays.ui.ThemeModeState
        setContent {
            val s = rememberThemeModeState()
            state = s
            MaterialTheme { MainScreen(engine, s) }
        }

        onAllNodesWithText("A 连接")[0].performClick()
        onAllNodesWithText("连接")[0].performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("添加连接").fetchSemanticsNodes().isNotEmpty()
        }
        // 切到第二屏后按钮仍在（最外层，不随工具栏 / sheet 内容变化而消失）
        onAllNodesWithContentDescription("主题：跟随系统，切换为浅色").assertCountEquals(1)

        // 显式档位在浏览屏同样可切换
        onAllNodesWithContentDescription("主题：跟随系统，切换为浅色")[0].performClick()
        assertEquals(ThemeMode.LIGHT, state.mode, "浏览屏的按钮必须写回顶层持有的同一个状态")
    }
}
