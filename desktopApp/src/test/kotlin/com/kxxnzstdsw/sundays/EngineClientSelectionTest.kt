package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.client.grpc.GrpcEngineClient
import com.kxxnzstdsw.engine.IdbEngine
import org.junit.After
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 引擎实现装配测试 —— [createEngineClient] 必须按 `-Dsundays.engine.endpoint` 选中正确的
 * [EngineClient] 实现。
 *
 * 这条分支是有用户可见后果的：属性没被读到就会静默退回同进程直连（用户以为连的是远程引擎，
 * 实际连的是自己进程里的引擎），或者端点写错时连到错误的进程。因此两种分支都要钉住。
 */
class EngineClientSelectionTest {

    @After
    fun tearDown() {
        System.clearProperty(ENDPOINT_PROPERTY)
    }

    @Test
    fun `defaults to in-process engine when no endpoint is configured`() {
        System.clearProperty(ENDPOINT_PROPERTY)

        val client: EngineClient = createEngineClient()
        try {
            assertIs<IdbEngine>(client, "no endpoint must mean same-JVM direct invocation")
        } finally {
            client.close()
        }
    }

    @Test
    fun `blank endpoint falls back to in-process engine`() {
        System.setProperty(ENDPOINT_PROPERTY, "   ")

        val client: EngineClient = createEngineClient()
        try {
            assertIs<IdbEngine>(client, "a blank endpoint is not a remote target")
        } finally {
            client.close()
        }
    }

    @Test
    fun `configured endpoint yields a grpc client`() {
        System.setProperty(ENDPOINT_PROPERTY, "localhost:50951")

        val client: EngineClient = createEngineClient()
        try {
            assertIs<GrpcEngineClient>(client, "a configured endpoint must select the gRPC transport")
        } finally {
            client.close()
        }
    }

    @Test
    fun `unix endpoint is accepted`() {
        System.setProperty(ENDPOINT_PROPERTY, "unix:///tmp/idb-engine.sock")

        val client: EngineClient = createEngineClient()
        try {
            assertIs<GrpcEngineClient>(client)
        } finally {
            client.close()
        }
    }

    @Test
    fun `malformed endpoint fails loudly instead of silently going in-process`() {
        System.setProperty(ENDPOINT_PROPERTY, "not-an-endpoint")

        // 端点写错必须在装配时暴露，不能悄悄退回本地引擎让用户以为连的是远程
        val failure = runCatching { createEngineClient() }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException,
            "expected IllegalArgumentException for a malformed endpoint, got: $failure",
        )
    }
}
