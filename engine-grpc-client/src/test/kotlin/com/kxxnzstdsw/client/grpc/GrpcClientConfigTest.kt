package com.kxxnzstdsw.client.grpc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 端点字符串解析测试 —— `-Dsundays.engine.endpoint=...` 是使用者直接接触的输入，
 * 解析错误必须在连接前就以可读信息暴露，而不是变成一个连不上的 channel。
 */
class GrpcClientConfigTest {

    @Test
    fun `bare host and port parses as TCP`() {
        val cfg = GrpcClientConfig.fromTarget("127.0.0.1:50051")

        assertEquals(GrpcTransportKind.TCP, cfg.kind)
        assertEquals("127.0.0.1", cfg.host)
        assertEquals(50051, cfg.port)
        assertEquals("127.0.0.1:50051", cfg.target())
    }

    @Test
    fun `tcp scheme is accepted and stripped`() {
        val cfg = GrpcClientConfig.fromTarget("tcp://db-host:9000")

        assertEquals(GrpcTransportKind.TCP, cfg.kind)
        assertEquals("db-host", cfg.host)
        assertEquals(9000, cfg.port)
    }

    @Test
    fun `unix scheme keeps full path including slashes`() {
        val cfg = GrpcClientConfig.fromTarget("unix:///var/run/idb/idb-engine.sock")

        assertEquals(GrpcTransportKind.UNIX, cfg.kind)
        assertEquals("/var/run/idb/idb-engine.sock", cfg.udsPath)
        assertEquals("/var/run/idb/idb-engine.sock", cfg.target())
    }

    @Test
    fun `pipe scheme keeps pipe name`() {
        val cfg = GrpcClientConfig.fromTarget("pipe:idb-engine")

        assertEquals(GrpcTransportKind.PIPE, cfg.kind)
        assertEquals("idb-engine", cfg.pipeName)
        assertEquals("idb-engine", cfg.target())
    }

    @Test
    fun `IPv6 literal is parsed without losing the port`() {
        val cfg = GrpcClientConfig.fromTarget("[::1]:50052")

        assertEquals(GrpcTransportKind.TCP, cfg.kind)
        assertEquals("::1", cfg.host)
        assertEquals(50052, cfg.port)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(50051, GrpcClientConfig.fromTarget("  localhost:50051  ").port)
    }

    @Test
    fun `default endpoint targets the server default port`() {
        assertEquals(50051, GrpcClientConfig.DEFAULT.port)
        assertEquals("localhost:50051", GrpcClientConfig.DEFAULT.target())
    }

    @Test
    fun `target without port is rejected with supported formats listed`() {
        val e = assertFailsWith<IllegalArgumentException> {
            GrpcClientConfig.fromTarget("localhost")
        }

        assertTrue(
            e.message!!.contains("host:port"),
            "error must state the supported formats, got: ${e.message}",
        )
    }

    @Test
    fun `non numeric port is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            GrpcClientConfig.fromTarget("localhost:not-a-port")
        }

        assertTrue(e.message!!.contains("not-a-port"), "error must echo the bad port, got: ${e.message}")
    }

    @Test
    fun `empty target is rejected`() {
        assertFailsWith<IllegalArgumentException> { GrpcClientConfig.fromTarget("   ") }
    }

    @Test
    fun `malformed IPv6 authority is rejected`() {
        assertFailsWith<IllegalArgumentException> { GrpcClientConfig.fromTarget("[::1:50052") }
    }
}
