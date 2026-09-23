package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DatabaseBrowserScreen] 的 Compose UI 端到端测试 —— 真引擎 + 真点击（H2 内存库）。
 *
 * 验证交付契约：
 * 1. 已连接时左侧渲染出数据库节点
 * 2. 单击数据库节点展开表列表
 * 3. **双击**表名 → 右侧出现预览标签页，并渲染出预览数据
 * 4. 重复双击同一张表不会新增第二个标签页
 */
@OptIn(ExperimentalTestApi::class)
class DatabaseBrowserUiTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var dbName: String
    private lateinit var connection: ConnectionConfig

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-db-browser-ui").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // 与 DatabaseBrowserFlowTest 相同的理由：显式注册 H2 dialect，绕过 IdbEngine
        // 全局幂等 bootstrap 在 engine.close() 之后不再扫描 SPI 的问题。
        DialectLoader.registerForTesting("H2", H2Dialect())

        dbName = "bdbtestui_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(64))")
                stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')")
            }
        }

        connection = ConnectionConfig(
            id = "ui-${System.nanoTime()}",
            name = "UIH2",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = jdbcUrl,
        )
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try { DriverManager.getConnection(jdbcUrl, "sa", "").use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } } } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    @Test
    fun `double clicking a table opens exactly one preview tab`() = runComposeUiTest {
        val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    browser = browser,
                    connections = listOf(connection),
                    selectedConnection = connection,
                    status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
                    onSelectConnection = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 左侧数据库节点渲染（H2 的 SCHEMA.LIST 返回 catalog，已归一为大写 —— 忽略大小写匹配）
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(dbName, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(dbName, substring = true, ignoreCase = true).performClick()

        // 展开后表名可见（H2 归一大写）；此时只有左侧树叶子，尚无标签页
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("USERS").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, usersTabCount(), "no tab should exist before opening")

        // 树叶子是文本匹配的第一个节点（标签页在右侧标签条，位置靠后）
        onAllNodesWithText("USERS")[0].performTouchInput { doubleClick() }

        // 预览数据渲染（样本行 Alice）→ 证明 DATA.LIST 已拉回并渲染
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { usersTabCount() == 1 }
        assertEquals(1, usersTabCount(), "double click should open exactly one tab for USERS")

        // 再次双击同一张表 —— 不得新增第二个标签页
        onAllNodesWithText("USERS")[0].performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(1, usersTabCount(), "same table must not add a duplicate tab")
    }

    /**
     * 当前语义树中标题为 USERS 的标签页数量。
     *
     * `SemanticsProperties.Selected` 只由 Material3 `Tab` 设置（左侧树叶子没有 Selected 语义），
     * 因此 `hasText("USERS") + keyIsDefined(Selected)` 精确圈定标签条上的标签页。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.usersTabCount(): Int =
        onAllNodes(
            hasText("USERS") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        ).fetchSemanticsNodes().size
}
