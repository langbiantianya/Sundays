package com.kxxnzstdsw.client.grpc

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.SchemaRequest
import com.kxxnzstdsw.grpc.SqlRequest
import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.server.IdbEngineImpl
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端测试：[GrpcEngineClient] 走**真实 gRPC 服务端**（TCP + 真实 HTTP/2 帧 + protobuf 编解码）
 * 调用真实引擎，验证「调用层抽象」在跨进程传输下的契约成立。
 *
 * 覆盖：
 * 1. 远程连接初始化 —— `testConnection` 走 `SYSTEM.TEST_CONNECTION`，在引擎进程内建池并校验
 * 2. **契约平价** —— 同一个 [Request] 分别经 gRPC 与同进程 [IdbEngine] 处理，Response 逐字节相等
 * 3. 流式 SELECT —— 服务端流式 `Handle` 的分帧与终止帧语义
 * 4. 远程连接释放 —— `SYSTEM.DISCONNECT` 在引擎进程内关池，且幂等（第二次返回 false）
 * 5. 传输层故障 —— 引擎不可达时 `testConnection` 返回 `ok=false` 而非抛异常（与本地实现同形）
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrpcEngineClientTest {

    private lateinit var server: Server
    private lateinit var endpoint: GrpcClientConfig
    private var client: GrpcEngineClient? = null

    private val dbName = "grpcclient_${UUID.randomUUID().toString().replace("-", "").take(8)}"
    private val jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

    private val config: ConnectionConfig = connectionConfig {
        driver = "H2"
        this.jdbcUrl = this@GrpcEngineClientTest.jdbcUrl
        user = "sa"
        password = ""
        database = dbName
    }

    @BeforeAll
    fun startEngine() {
        // 方言显式注册：H2 驱动 + dialect-h2 已在本模块 test classpath 上
        DialectLoader.registerForTesting("H2", H2Dialect())

        // 端口 0 = 由 OS 分配空闲端口，随后从 server.port 读回真实端口（无端口探测竞态）
        server = NettyServerBuilder.forPort(0)
            .addService(IdbEngineImpl(IdbEngine(File("/nonexistent"), File("/nonexistent"))).bindService())
            .build()
            .start()
        endpoint = GrpcClientConfig(host = "localhost", port = server.port)
    }

    @AfterAll
    fun stopEngine() {
        client?.close()
        server.shutdownNow()
        server.awaitTermination(10, TimeUnit.SECONDS)
        PoolManager.closeAll()
    }

    @BeforeEach
    fun resetData() {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS t")
                st.execute("CREATE TABLE t (id INT, name VARCHAR(50))")
                st.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')")
            }
        }
    }

    private fun newClient(): GrpcEngineClient =
        GrpcEngineClient.connect(endpoint).also { client = it }

    @Test
    fun `remote testConnection initializes pool in engine process`() = runBlocking {
        val remote = newClient()

        val result = remote.testConnection(config)

        assertTrue(result.ok, "remote testConnection should succeed: ${result.error}")
        assertEquals("H2", result.driver)
    }

    @Test
    fun `testConnection from jdbcUrl only works over grpc`() = runBlocking {
        val remote = newClient()

        // 只给 JDBC URL + 凭据：driver/host/port/database 全空，方言由 URL scheme 反查
        val result = remote.testConnection(jdbcUrl = jdbcUrl, user = "sa")

        assertTrue(result.ok, "url-only testConnection should succeed: ${result.error}")
        assertEquals("H2", result.driver)
    }

    @Test
    fun `grpc response is identical to in-process response for same request`() = runBlocking {
        val remote = newClient()
        val local = IdbEngine(File("/nonexistent"), File("/nonexistent"))

        fun build() = request {
            id = "parity-1"
            category = Category.SCHEMA
            action = Action.LIST
            connection = config
            schemaRequest = SchemaRequest.newBuilder()
                .setList(schemaListRequest { level = "database" })
                .build()
        }

        val overGrpc = remote.handle(build()).toList()
        val inProcess = local.handle(build()).toList()
        assertEquals(overGrpc, inProcess, "gRPC 与同进程响应必须逐帧一致")
        assertTrue(overGrpc.isNotEmpty())
        assertTrue(overGrpc.last().success, "expected success, got: ${overGrpc.last().error}")
    }

    @Test
    fun `streaming select delivers row frames then terminal frame`() = runBlocking {
        val remote = newClient()

        val frames = remote.handle(
            request {
                id = "stream-1"
                category = Category.SQL
                action = Action.EXECUTE
                connection = config
                sqlRequest = SqlRequest.newBuilder()
                    .setExecute(sqlExecuteRequest { sql = "SELECT id, name FROM t ORDER BY id" })
                    .build()
            }
        ).toList()

        val rowFrames = frames.filter { it.hasSqlRowFrame() }
        assertEquals(3, rowFrames.size, "expected one frame per row, got ${rowFrames.size}")
        // buildRow 统一用 rs.getString() 归一为 protobuf string_value（引擎侧既有约定）
        assertEquals("1", rowFrames[0].sqlRowFrame.row.valuesMap["ID"]?.stringValue)
        assertEquals("a", rowFrames[0].sqlRowFrame.row.valuesMap["NAME"]?.stringValue)
        assertEquals("3", rowFrames[2].sqlRowFrame.row.valuesMap["ID"]?.stringValue)

        // 流式响应以 end=true 的终止帧收尾，且所有帧共享同一 request id
        val last = frames.last()
        assertTrue(last.end, "streaming response must end with end=true")
        assertTrue(frames.all { it.id == "stream-1" }, "all frames must carry the request id")
    }

    @Test
    fun `invoke convenience works over grpc and carries typed system payload`() = runBlocking {
        val remote: EngineClient = newClient()

        val resp = remote.invoke(config) {
            category = Category.SYSTEM
            action = Action.INFO
        }

        assertTrue(resp.success, "expected success, got: ${resp.error}")
        assertNotNull(resp.system.info, "expected system info body")
        assertTrue(resp.system.info.jvmVersion.isNotBlank())
    }

    @Test
    fun `disconnect releases engine-side pools and is idempotent`() = runBlocking {
        val remote = newClient()

        assertTrue(remote.testConnection(config).ok, "precondition: pool must exist")
        assertTrue(remote.disconnect(config), "first disconnect must report a closed pool")
        assertFalse(remote.disconnect(config), "second disconnect must report no active pool")
    }

    @Test
    fun `disconnect of never-connected config reports false`() = runBlocking {
        val remote = newClient()
        val unused = connectionConfig {
            driver = "H2"
            jdbcUrl = "jdbc:h2:mem:never_connected_${UUID.randomUUID().toString().take(8)};DB_CLOSE_DELAY=-1"
            user = "sa"
        }

        assertFalse(remote.disconnect(unused), "disconnect must be a no-op returning false for unknown config")
    }

    @Test
    fun `transport failure surfaces as ok=false instead of throwing`() = runBlocking {
        // 指向一个没有引擎监听的端口：channel 建链成功但 RPC 失败
        val dead = GrpcEngineClient.connect(GrpcClientConfig(host = "localhost", port = 1))
        try {
            val result = dead.testConnection(config)

            assertFalse(result.ok, "unreachable engine must report ok=false")
            assertTrue(result.error.isNotBlank(), "failure reason must be carried in error field")
        } finally {
            dead.close()
        }
    }

    @Test
    fun `close is idempotent`() {
        val c = GrpcEngineClient.connect(endpoint)
        c.close()
        c.close()
    }

    @Test
    fun `non-streaming sql execute returns affected rows over grpc`() = runBlocking {
        val remote = newClient()

        val resp = remote.handle(
            request {
                id = "dml-1"
                category = Category.SQL
                action = Action.EXECUTE
                connection = config
                sqlRequest = SqlRequest.newBuilder()
                    .setExecute(SqlExecuteRequest.newBuilder().setSql("DELETE FROM t WHERE id > 1").build())
                    .build()
            }
        ).first()

        assertTrue(resp.success, "expected success, got: ${resp.error}")
        assertEquals(2, resp.sql.execute.affectedRows)
    }
}
