package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.response
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浏览屏的**连接失败可见性**契约。
 *
 * ## 为什么要有这条测试
 *
 * 失败原因一直都在 `ConnectionStatus.message` 里，但浏览屏**从不展示**它 ——
 * sheet 标签上只有一个红点。用户看到红点却不知道是密码错了、网络断了还是驱动没装，
 * 只能切回连接管理页去找。数据就在手边，缺的是出口。
 *
 * 这类缺陷特别难靠「以后注意」防住：加一个字段、传一路、界面上忘了渲染，
 * 编译器与运行时都不会有意见。**所以必须有一条断言直接说「这条信息在浏览屏上看得见」**。
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionFailureVisibilityTest {

    /** 一条足够像样的失败原因 —— 真实场景下引擎回的就是这种带 `Caused by` 的长文本。 */
    private val reason =
        "Connection refused: java.net.ConnectException: Connection refused: localhost/127.0.0.1:5432"

    /**
     * 只满足构造 `DatabaseBrowserState` 的最小假引擎。
     *
     * ## 为什么不用真 `IdbEngine`
     *
     * 状态为 FAILED 时 `LaunchedEffect` 既不调 `refreshDatabases` 也不调 `releasePools`，
     * 引擎**一次都不会被调用** —— 造一个真引擎纯属给自己惹麻烦：`tearDown` 里的
     * `engine.close()` 会连带 `DriverLoader.closeAll()` / `DialectLoader.closeAll()`
     * 关掉**全局**的 ClassLoader，跑到它后面的测试类（`ConnectionManagerFlowTest`
     * 就实测多挂了一条）会因方言/驱动拿不回来而失败。
     *
     * 这类「测试之间通过全局单例互相干扰」的坑，代价总是别人付的，所以宁可直接不用。
     */
    private val stubEngine = object : EngineClient {
        override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
            flowOf(response { end = true })

        // 注意 `EngineClient` 的入参是 **proto** 的 `com.kxxnzstdsw.grpc.ConnectionConfig`，
        // 与 UI 层那个同名但不同包的 `shared` 侧 `ConnectionConfig` 不是同一个类型。
        override suspend fun testConnection(
            config: com.kxxnzstdsw.grpc.ConnectionConfig,
        ): com.kxxnzstdsw.grpc.SystemTestConnectionResponse = error("FAILED 状态下不应被调用")

        override suspend fun disconnect(config: com.kxxnzstdsw.grpc.ConnectionConfig): Boolean =
            error("FAILED 状态下不应被调用")

        override fun close() = Unit
    }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var conn: ConnectionConfig
    private var browserScope: CoroutineScope? = null

    @Before
    fun setUp() {
        conn = ConnectionConfig(
            id = "c-${System.nanoTime()}",
            name = "DemoH2",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = "jdbc:h2:mem:failbanner_${System.nanoTime()}",
        )
    }

    @After
    fun tearDown() {
        // 收掉状态机的协程作用域。`DatabaseBrowserScreen` 的 `LaunchedEffect` 挂在它上面，
        // 不取消的话协程会活到 JVM 退出 —— 后跑的测试类在同一 JVM 里等着用全局状态。
        browserScope?.cancel()
        browserScope = null
    }

    /**
     * **刻意不隔离 `user.home`**。
     *
     * 绝大多数 UI 测试都要隔离它（否则会读写真实的 `~/.config/sundays/connection.json`，
     * 那个文件**明文存口令**）。但本类**不触发任何持久化** —— `DatabaseBrowserScreen`
     * 的连接列表由 [DatabaseBrowserScreen] 的 `connections` 参数直接传入，
     * FAILED 状态下 `LaunchedEffect` 也不会去动存储。
     *
     * 也就是说这里的隔离是**纯副作用没有收益**：改一个 JVM 全局属性，
     * 而同一 JVM 里后面还有别的测试类在读它（实测会让 `DatabaseBrowserUiTest`
     * 多挂一条）。不碰全局状态，就不给别人留坑。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.render(
        status: ConnectionStatus,
        onConnect: (ConnectionConfig) -> Unit = {},
    ) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        browserScope = scope
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(
                        SheetDescriptor(
                            connection = conn,
                            browser = DatabaseBrowserState(stubEngine, scope),
                            status = status,
                        ),
                    ),
                    activeSheetId = conn.id,
                    connections = listOf(conn),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = onConnect,
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun `the failure reason is visible on the browser screen`() = runComposeUiTest {
        render(ConnectionStatus(ConnectionState.FAILED, reason))

        // 标签（语义主语）不省略号截断
        onNodeWithText("连接失败").assertIsDisplayed()
        // 原因本身。用 substring 是因为横幅会把它压成两行（maxLines = 2），
        // 但**不能**因此只断言首句 —— 关键信息常常在末尾。
        onNodeWithText(reason, substring = true).assertIsDisplayed()
    }

    @Test
    fun `the retry button calls back on connect`() = runComposeUiTest {
        var retried: ConnectionConfig? = null
        render(ConnectionStatus(ConnectionState.FAILED, reason)) { retried = it }

        onNodeWithText("重试").performClick()
        assertTrue(
            retried != null,
            "「重试」必须真的回调 onConnect —— 失败原因多半是瞬时的（网络抖了下），" +
                "让用户切去连接管理页再切回来是纯粹的二次摩擦",
        )
    }

    @Test
    fun `no failure banner when the connection is healthy`() = runComposeUiTest {
        render(ConnectionStatus(ConnectionState.CONNECTED, "H2"))

        // 横幅只在 FAILED 时出现 —— 已连接时挂着一条「连接失败」比不挂更糟。
        assertEquals(
            0,
            onAllNodesWithText("连接失败").fetchSemanticsNodes().size,
            "已连接时不该出现失败横幅",
        )
    }
}
