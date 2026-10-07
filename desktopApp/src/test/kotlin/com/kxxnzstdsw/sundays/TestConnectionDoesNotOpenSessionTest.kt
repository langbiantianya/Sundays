package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dialect.MySQLDialect
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.systemTestConnectionResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionType
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **「测试连接」是探测，不是一次会话** —— 钉住它不留池、不改会话状态。
 *
 * ## 用户报告的现象
 *
 * > 测试连接后要自己断开连接，不然影响保存后手动点击连接
 *
 * 展开就是这条路径：
 *
 * ```
 * 向导「测试连接」 → 引擎建池 + 状态 CONNECTED（池留在引擎里）
 *   → 保存（新建连接时没有旧配置可断开）
 *   → 总览面板显示「已连接」、按钮变成「断开」
 *   → 用户点那个按钮想连接 —— 拿到的是**断开**
 * ```
 *
 * 根因在引擎侧：`IdbEngine.testConnection` 会**建（或复用）HikariCP 连接池**，
 * 它的 KDoc 自己写着「这一步即『初始化连接』」。而「连接状态」是**按会话**记的，
 * 两者混在一起，探测就顺手开了一个会话。
 *
 * ## 为什么用 fake engine 而不是真 H2
 *
 * 真引擎也能测状态，但**看不见池** —— `PoolManager` 的池注册表没有对外的查询接口。
 * 这里要断言的恰恰是「池有没有被还回去」，所以用 fake 记录 `disconnect` 调用次数。
 * 真引擎那条链路（`IdbEngine` → `PoolManager`）由既有测试覆盖，本类只管**会话层**的语义。
 */
class TestConnectionDoesNotOpenSessionTest {

    /**
     * 只记录「测试连接 / 断开」两个动作的 fake —— 其余接口一律不支持，
     * 一旦本类误用了别的入口会立刻炸，而不是静默通过。
     */
    private class RecordingEngine(
        private val ok: Boolean = true,
    ) : EngineClient {
        var testCalls = 0
        var disconnectCalls = 0

        override fun handle(request: Request) = throw UnsupportedOperationException("本用例不覆盖 handle")

        override suspend fun testConnection(config: com.kxxnzstdsw.grpc.ConnectionConfig):
            com.kxxnzstdsw.grpc.SystemTestConnectionResponse {
            testCalls++
            return systemTestConnectionResponse {
                this.ok = this@RecordingEngine.ok
                driver = "Mysql"
            }
        }

        override suspend fun disconnect(config: com.kxxnzstdsw.grpc.ConnectionConfig): Boolean {
            disconnectCalls++
            return true
        }

        override fun close() = Unit
    }

    private lateinit var originalHome: String
    private lateinit var tempHome: File

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-probe-home").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.registerForTesting("Mysql", MySQLDialect())
    }

    private fun config() = ConnectionConfig(
        id = "probe-1",
        name = "探针",
        dialect = DialectType.MYSQL,
        host = "192.168.1.5",
        port = 3306,
        database = "sundays_probe",
        username = "root",
        password = "666666",
        connectionType = ConnectionType.CLIENT_SERVER,
        jdbcUrl = "jdbc:mysql://192.168.1.5:3306/sundays_probe?useSSL=false",
    )

    private fun session(engine: EngineClient) =
        ConnectionSession(engine, kotlinx.coroutines.CoroutineScope(Dispatchers.Default))

    @Test
    fun `testing a connection leaves no session behind`() = runBlocking {
        val engine = RecordingEngine(ok = true)
        val session = session(engine)
        val cfg = config()

        val result = session.testConnection(cfg)

        assertTrue(result.success, "探测本身应成功")
        assertEquals(1, engine.testCalls)
        assertEquals(
            1, engine.disconnectCalls,
            "探测完必须把刚建的池还回去 —— 否则「连接」按钮会变成「断开」，" +
                "用户保存后再点那个按钮拿到的是断开（这正是用户报告的那条）",
        )
        assertEquals(
            ConnectionState.DISCONNECTED, session.statuses[cfg.id]?.state,
            "「测过了」不等于「连上了」：探测结束后状态必须是未连接",
        )
    }

    @Test
    fun `a failed probe also releases the pool`() = runBlocking {
        val engine = RecordingEngine(ok = false)
        val session = session(engine)
        val cfg = config()

        val result = session.testConnection(cfg)

        assertFalse(result.success)
        assertEquals(
            1, engine.disconnectCalls,
            "探测失败时引擎可能已建了池，必须照样还回去，不能留一个坏池",
        )
        assertEquals(ConnectionState.FAILED, session.statuses[cfg.id]?.state)
    }

    @Test
    fun `probing an already connected session does not drop it`() = runBlocking {
        val engine = RecordingEngine(ok = true)
        val session = session(engine)
        val cfg = config()

        // 先真的连上（这条路径本身就是「建池且保持」）
        session.connect(cfg)
        // connect() 是 fire-and-forget，等它落定
        val deadline = System.currentTimeMillis() + 5_000
        while (session.statuses[cfg.id]?.state != ConnectionState.CONNECTED &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(
            ConnectionState.CONNECTED, session.statuses[cfg.id]?.state,
            "前置条件：此时应是一次活着的会话",
        )
        val disconnectsBefore = engine.disconnectCalls

        session.testConnection(cfg)

        assertEquals(
            disconnectsBefore, engine.disconnectCalls,
            "已经在连着的时候再测一次，**不能**顺手把用户的会话掐掉",
        )
        assertEquals(
            ConnectionState.CONNECTED, session.statuses[cfg.id]?.state,
            "探测不应改变一个已建立会话的状态",
        )
    }

    @Test
    fun `after probing, connect still reaches connected`() = runBlocking {
        // 这条是**用户报告那句话本身**：探测 → 保存 → 手动点「连接」，应当连得上。
        val engine = RecordingEngine(ok = true)
        val session = session(engine)
        val cfg = config()

        session.testConnection(cfg)
        session.save(cfg)                 // 保存（新建连接，没有旧配置可断开）
        session.connect(cfg)              // 用户手动点「连接」

        val deadline = System.currentTimeMillis() + 5_000
        while (session.statuses[cfg.id]?.state != ConnectionState.CONNECTED &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(
            ConnectionState.CONNECTED, session.statuses[cfg.id]?.state,
            "探测过之后手动点连接仍应连得上",
        )
    }

    @After
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }
}
