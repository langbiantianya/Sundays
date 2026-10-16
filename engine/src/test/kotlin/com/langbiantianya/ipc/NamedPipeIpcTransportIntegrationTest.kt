/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.ipc

import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.IdbEngineGrpc
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.Response
import com.langbiantianya.ipc.impl.NamedPipeIpcTransport
import com.langbiantianya.testutil.TestIds
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Windows 命名管道传输集成测试 — 当前仅验证客户端可达性。
 *
 * 由于 grpc-Java 1.68.0（及最新 1.83.0）**不暴露公共 API** 用于将 gRPC Server 绑定到
 * Windows 命名管道，服务端测试需要 JNA + Win32 `CreateNamedPipe` 自实现，标记为未来工作。
 *
 * 客户端 [NamedPipeIpcTransport.channelBuilder] 使用 [io.grpc.Grpc.newChannelBuilder] + `pipe:<name>` URI，
 * 在 Windows 上可用（在其他平台上构造失败但不在此测试范围）。
 */
class NamedPipeIpcTransportIntegrationTest {

    @Test
    fun `client can construct channel builder for pipe name`() {
        val name = TestIds.uniqueName("idb-test")
        val transport = NamedPipeIpcTransport(IpcConfig(kind = IpcKind.PIPE, pipeName = name))
        transport.prepare()
        val channelBuilder = transport.channelBuilder()
        assertNotNull(channelBuilder)
        assertTrue(transport.displayTarget().startsWith("pipe:"))
    }

    @Test
    fun `server builder throws UnsupportedOperationException documenting grpc limitation`() {
        assertTimeout(Duration.ofSeconds(2)) {
            val name = TestIds.uniqueName("idb-test")
            val transport = NamedPipeIpcTransport(IpcConfig(kind = IpcKind.PIPE, pipeName = name))
            try {
                transport.serverBuilder()
                fail("expected UnsupportedOperationException")
            } catch (e: UnsupportedOperationException) {
                assertTrue(e.message!!.contains("named pipe"))
            }
        }
    }
}
