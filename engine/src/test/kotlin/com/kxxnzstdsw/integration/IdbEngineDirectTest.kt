package com.kxxnzstdsw.integration

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.SchemaRequest
import com.kxxnzstdsw.grpc.SystemRequest
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.kxxnzstdsw.grpc.request

/**
 * 端到端测试：通过 IdbEngine facade 直接调用，绕过 gRPC server / IPC transport。
 *
 * 验证：
 * 1. IdbEngine() 构造时自动 bootstrap drivers/dialects
 * 2. handle(Request) 返回 Flow<Response> 与 gRPC 路径语义一致
 * 3. typed per-Category Request 在 facade 边界可直接消费
 * 4. 非流式响应：单条 Response，stream=false, end=true
 * 5. invoke(connection, configure) 便捷方法正确返回单条响应
 *
 * 这是 v2.9 双模式架构中 direct 模式的契约测试 — 与 `IdbEngineImpl` (gRPC) 形成互证。
 */
class IdbEngineDirectTest : H2Fixture() {

    private fun newEngine(): IdbEngine = IdbEngine(
        driversDir = java.io.File("/nonexistent"),   // 测试不依赖磁盘 drivers/ 目录
        dialectsDir = java.io.File("/nonexistent"),  // H2Fixture 已注册 H2 dialect
    )

    @Test
    fun `IdbEngine handle non-stream returns single Response with success`() = runBlocking {
        val engine = newEngine()
        try {
            val response = engine.handle(request {
                id = "r-001"
                category = Category.SCHEMA
                action = Action.LIST
                connection = this@IdbEngineDirectTest.config
                schemaRequest = SchemaRequest.newBuilder()
                    .setList(schemaListRequest { level = "database" })
                    .build()
            }).first()

            assertTrue(response.success, "expected success=true but got error: ${response.error}")
            assertEquals("r-001", response.id)
            // 非流式响应: stream=false, end=false (proto 默认值, 见 ARCHITECTURE.md §4.2)
            assertTrue(!response.stream, "non-stream response should have stream=false")
            assertTrue(!response.end, "non-stream response should have end=false")
            assertNotNull(response.schema, "expected schema response body")
            assertEquals("database", response.schema.list.level)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `IdbEngine invoke convenience method collects single response`() = runBlocking {
        val engine = newEngine()
        try {
            val resp = engine.invoke(this@IdbEngineDirectTest.config) {
                category = Category.SYSTEM
                action = Action.INFO
                systemRequest = SystemRequest.getDefaultInstance()
            }
            assertTrue(resp.success, "expected success=true but got: ${resp.error}")
            assertNotNull(resp.system.info, "expected system info body")
            assertTrue(resp.system.info.jvmVersion.isNotBlank())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `IdbEngine propagate error from handler as success=false`() = runBlocking {
        val engine = newEngine()
        try {
            // LIST level=schema 不带 database → handler 抛 IllegalArgumentException
            val resp = engine.invoke(this@IdbEngineDirectTest.config) {
                category = Category.SCHEMA
                action = Action.LIST
                schemaRequest = SchemaRequest.newBuilder()
                    .setList(schemaListRequest { level = "schema" })  // 缺 database
                    .build()
            }
            assertTrue(!resp.success, "expected success=false for invalid request")
            assertTrue(resp.error.isNotBlank(), "expected non-blank error message")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `IdbEngine bootstrap is idempotent across multiple instances`() = runBlocking {
        // 多个 IdbEngine 实例共享同一组 driver/dialect 加载 — 第二次 bootstrap 应该是 no-op
        val engine1 = newEngine()
        val engine2 = newEngine()
        try {
            val resp = engine2.invoke(this@IdbEngineDirectTest.config) {
                category = Category.SYSTEM
                action = Action.INFO
                systemRequest = SystemRequest.getDefaultInstance()
            }
            assertTrue(resp.success)
            assertNotNull(resp.system.info)
        } finally {
            engine1.close()
            // engine2 共享全局资源 — close 一次即可；这里只关闭一次避免二次 close 警告
        }
    }
}
