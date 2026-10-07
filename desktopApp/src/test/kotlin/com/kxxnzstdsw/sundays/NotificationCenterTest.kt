package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 只占位：本组用例不碰引擎，只需要一个构造得出来的 [EngineClient]。 */
private class StubEngine : EngineClient {
    override fun handle(request: com.kxxnzstdsw.grpc.Request) =
        kotlinx.coroutines.flow.flow<com.kxxnzstdsw.grpc.Response> {}

    override suspend fun invoke(
        connection: com.kxxnzstdsw.grpc.ConnectionConfig,
        configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit,
    ): com.kxxnzstdsw.grpc.Response = error("不调用")

    override suspend fun testConnection(config: com.kxxnzstdsw.grpc.ConnectionConfig) =
        error("不调用")

    override suspend fun disconnect(config: com.kxxnzstdsw.grpc.ConnectionConfig) = false

    override fun close() = Unit
}

/**
 * 通知中心的行为。
 *
 * ## 为什么去重是这里最要紧的一条
 *
 * 屏级错误对账每次重组都会看到同一个 `status.message` —— 连接失败是**持续**的状态，
 * 不是一次事件。没有去重就会在几秒内刷出几十条一模一样的通知，
 * 把真正值得看的那条挤出去，面板也就失去意义了。
 *
 * 但去重不能做成「一辈子只报一次」：错误消失（用户改好密码重连成功）之后再次失败，
 * 用户是关心第二次的。所以配了 `forgetSourcesNotIn` 让来源能「重新变得可报」。
 */
class NotificationCenterTest {

    @Test
    fun `push 按时间倒序，最新的在最前`() {
        val c = NotificationCenter()
        c.push(AppNotification.Severity.INFO, "第一条", "")
        c.push(AppNotification.Severity.INFO, "第二条", "")
        c.push(AppNotification.Severity.INFO, "第三条", "")

        assertEquals(listOf("第三条", "第二条", "第一条"), c.notifications.map { it.title })
    }

    @Test
    fun `pushOnce 同一个来源只报一次`() {
        val c = NotificationCenter()
        repeat(5) { c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败", "密码错") }

        assertEquals(
            1, c.notifications.size,
            "同一个持续存在的错误只该报一次 —— 连接失败出现在每一次重组里",
        )
        assertEquals(1, c.unreadCount)
    }

    @Test
    fun `不同来源各报一次`() {
        val c = NotificationCenter()
        c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        c.pushOnce("conn:B", AppNotification.Severity.ERROR, "连接失败 · B", "网络不通")
        c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败 · A", "密码错")

        assertEquals(2, c.notifications.size, "两个连接的失败都该在")
    }

    @Test
    fun `来源消失后再犯会重新报`() {
        val c = NotificationCenter()
        val src = "conn:A|密码错"

        c.pushOnce(src, AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        assertEquals(1, c.notifications.size)

        // 用户修好密码 → 错误消失 → 对账循环调 forgetSourcesNotIn(空集)
        c.forgetSourcesNotIn(emptySet())

        // 密码又错了 → 同一个来源应当再报一次
        c.pushOnce(src, AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        assertEquals(
            2, c.notifications.size,
            "「失败 → 恢复 → 又失败」必须报第二次，否则用户看不到复发",
        )
    }

    @Test
    fun `forgetSourcesNotIn 只遗忘不在集合里的`() {
        val c = NotificationCenter()
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        c.pushOnce("B", AppNotification.Severity.ERROR, "B", "")
        assertEquals(2, c.notifications.size)

        // B 消失了，A 还在
        c.forgetSourcesNotIn(setOf("A"))
        c.pushOnce("B", AppNotification.Severity.ERROR, "B", "")
        assertEquals(3, c.notifications.size, "B 消失过，应当能再报")

        // A 从未消失，不该重复
        c.forgetSourcesNotIn(setOf("A"))
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        assertEquals(3, c.notifications.size, "持续存在的 A 不该重复报")
    }

    @Test
    fun `错误文本变化视为新来源`() {
        // 「表 X 不存在」改成「表 Y 不存在」是**新的一件事**，必须报
        val c = NotificationCenter()
        c.pushOnce("tab:t1|Table 'a' doesn't exist", AppNotification.Severity.ERROR, "读取 a 失败", "Table 'a' doesn't exist")
        c.forgetSourcesNotIn(setOf("tab:t1|Table 'b' doesn't exist"))
        c.pushOnce("tab:t1|Table 'b' doesn't exist", AppNotification.Severity.ERROR, "读取 a 失败", "Table 'b' doesn't exist")

        assertEquals(2, c.notifications.size)
    }

    @Test
    fun `全部已读清零未读但保留列表`() {
        val c = NotificationCenter()
        c.push(AppNotification.Severity.ERROR, "连接失败", "密码错")
        assertEquals(1, c.unreadCount)

        c.markAllRead()
        assertEquals(0, c.unreadCount)
        assertEquals(1, c.notifications.size, "已读不等于删除 —— 用户还要回看")
    }

    @Test
    fun `清空同时清掉列表、未读与去重集合`() {
        val c = NotificationCenter()
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        c.clear()

        assertTrue(c.notifications.isEmpty())
        assertEquals(0, c.unreadCount)
        // 去重集合也清了 —— 否则清空之后同样的错误永远不再报，那是不可接受的
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        assertEquals(1, c.notifications.size, "清空后同样的错误要能再报")
    }

    @Test
    fun `超出上限时丢最旧的`() {
        val c = NotificationCenter()
        repeat(80) { c.push(AppNotification.Severity.INFO, "第 $it 条", "") }
        // 通知是「最近发生过的事」，不是日志 —— 留太多会把有价值的挤出去
        assertTrue(c.notifications.size <= 60, "实际 ${c.notifications.size}")
        assertEquals("第 79 条", c.notifications.first().title, "最新的必须在最前")
    }
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
            1, c.notifications.size,
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
    fun `错误文本变化视为新的一��`() {
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
            2, c.notifications.size,
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
            connection = ConnectionConfig(
                id = "c1", name = "探针", dialect = DialectType.MYSQL,
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
    private fun connectionFailed(message: String): SheetDescriptor = sheet(
        status = ConnectionStatus(state = ConnectionState.FAILED, message = message),
    )

    private fun sqlFailed(message: String): SheetDescriptor =
        sheet { sqlSheets.first().error = message }

    private fun okSheet(): SheetDescriptor = sheet()
}
