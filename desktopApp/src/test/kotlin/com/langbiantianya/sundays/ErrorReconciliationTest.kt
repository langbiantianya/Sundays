/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.notifications.AppNotification
import com.langbiantianya.sundays.notifications.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 只占位：本组用例不碰引擎，只需要一个构造得出来的 [EngineClient]。 */
private class StubEngine : EngineClient {
    override fun handle(request: com.langbiantianya.grpc.Request) = kotlinx.coroutines.flow.flow<com.langbiantianya.grpc.Response> {}

    override suspend fun invoke(
        connection: com.langbiantianya.grpc.ConnectionConfig,
        configure: com.langbiantianya.grpc.RequestKt.Dsl.() -> Unit,
    ): com.langbiantianya.grpc.Response = error("不调用")

    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = error("不调用")

    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = false

    override fun close() = Unit
}

/**
 * 屏级错误对账：**推送与遗忘必须用同一个 key**。
 *
 * ## 这条在盯什么
 *
 * 第一版把 `pushOnce` 的指纹写成 `标题|详情`，而 `forgetSourcesNotIn` 传的是
 * `mapValues { … }.keys`（形如 `sql:连接id:SQL 1`）—— 两者对不上，于是每一轮
 * 对账都把所有来源 forget 掉、再当成新的重推一遍。
 *
 * 真窗口上的表现：**同一个错误连点三次「执行 SQL」→ 徽标 2**，去重形同虚设。
 * 而屏里那个 `derivedStateOf` 测不到，所以只能把「算指纹」抽成
 * [collectErrorSources] 这个纯函数来验。
 */
class ErrorReconciliationTest {
    @Test
    fun `同一批错误反复对账只推一条`() {
        val c = NotificationCenter()
        // 三轮完全相同的对账（模拟「连点三次执行同一个错误」）
        repeat(3) {
            val sources = collectErrorSources(listOf(connectionFailed("密码错")))
            sources.forEach { s ->
                c.pushOnce(s.key, AppNotification.Severity.ERROR, s.title, s.detail)
            }
            c.forgetSourcesNotIn(sources.map { it.key }.toSet())
        }

        assertEquals(
            1,
            c.notifications.size,
            "同一个持续存在的错误只该有一条通知 —— 连点三次不该变成 3 条",
        )
    }

    @Test
    fun `错误消失后再现会重新推送`() {
        val c = NotificationCenter()

        fun reconcile(sheets: List<SheetDescriptor>) {
            val sources = collectErrorSources(sheets)
            sources.forEach { s ->
                c.pushOnce(s.key, AppNotification.Severity.ERROR, s.title, s.detail)
            }
            c.forgetSourcesNotIn(sources.map { it.key }.toSet())
        }

        reconcile(listOf(connectionFailed("密码错")))
        assertEquals(1, c.notifications.size)

        // 连接恢复 → 本帧没有错误 → 全部来源被遗忘
        reconcile(listOf(okSheet()))

        // 又断了
        reconcile(listOf(connectionFailed("密码错")))
        assertEquals(2, c.notifications.size, "复发必须再报一次")
    }

    @Test
    fun `错误文本变化视为新的一条`() {
        val c = NotificationCenter()

        fun reconcile(sheets: List<SheetDescriptor>) {
            val sources = collectErrorSources(sheets)
            sources.forEach { s ->
                c.pushOnce(s.key, AppNotification.Severity.ERROR, s.title, s.detail)
            }
            c.forgetSourcesNotIn(sources.map { it.key }.toSet())
        }

        reconcile(listOf(connectionFailed("Table 'a' doesn't exist")))
        reconcile(listOf(connectionFailed("Table 'b' doesn't exist")))

        assertEquals(
            2,
            c.notifications.size,
            "「表 a 不存在」变成「表 b 不存在」是**新的一件事**，必须报",
        )
    }

    @Test
    fun `没有错误时不产生任何通知`() {
        val c = NotificationCenter()
        val sources = collectErrorSources(listOf(okSheet()))
        sources.forEach { it -> c.pushOnce(it.key, AppNotification.Severity.ERROR, it.title, it.detail) }
        assertTrue(sources.isEmpty())
        assertTrue(c.notifications.isEmpty())
    }

    // ---- 造状态 ----

    private fun sheet(
        status: ConnectionStatus = ConnectionStatus(state = ConnectionState.CONNECTED),
        configure: DatabaseBrowserState.() -> Unit = {},
    ): SheetDescriptor {
        val browser = DatabaseBrowserState(StubEngine(), CoroutineScope(Dispatchers.Default))
        browser.configure()
        return SheetDescriptor(
            connection =
                ConnectionConfig(
                    id = "c1",
                    name = "探针",
                    dialect = DialectType.MYSQL,
                ),
            browser = browser,
            status = status,
        )
    }

    /**
     * ⚠️ 只设**一个**来源。
     *
     * 第一版同时设了 `status = FAILED` 和 `sqlSheets.error` —— 那是**两个**错误来源，
     * 于是断言「1 条」拿到 2 条，红的是测试造错了状态，不是产品行为。
     * 这一点本身就是值得记的：屏级对账收的是**所有**来源，测试里随手多设一个字段就多一条。
     */
    private fun connectionFailed(message: String): SheetDescriptor =
        sheet(
            status = ConnectionStatus(state = ConnectionState.FAILED, message = message),
        )

    private fun sqlFailed(message: String): SheetDescriptor = sheet { sqlSheets.first().error = message }

    private fun okSheet(): SheetDescriptor = sheet()
}
