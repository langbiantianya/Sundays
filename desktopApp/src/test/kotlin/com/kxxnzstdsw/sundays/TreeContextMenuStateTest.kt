package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.grpc.ConnectionConfig as GrpcConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 一个只占位的引擎 —— 本组用例**不应**发出任何引擎请求。
 *
 * [invoke] 直接 `error()`：任何「本不该发的请求」跑到这里都会**炸出来**，
 * 而不是安静地返回空响应、再让断言去猜哪里不对。
 */
private object StubEngineClient : EngineClient {
    override fun handle(request: com.kxxnzstdsw.grpc.Request): Flow<com.kxxnzstdsw.grpc.Response> =
        flow { throw IllegalStateException("本用例不走流式路径") }

    override suspend fun invoke(
        connection: GrpcConnectionConfig,
        configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit,
    ): com.kxxnzstdsw.grpc.Response = error("本用例不应发出引擎请求")

    override suspend fun testConnection(config: GrpcConnectionConfig) =
        error("本用例不测连接")

    override suspend fun disconnect(config: GrpcConnectionConfig): Boolean = false

    override fun close() = Unit
}

/**
 * 对象树右键「复制名称 / 复制引用名 / 复制建表 DDL」的状态机行为。
 *
 * ## 为什么剪贴板要注入而不是用真的
 *
 * 本机 UI 测试环境**是有头的**（`GraphicsEnvironment.isHeadless() == false`，
 * 实测能写也能读回系统剪贴板）—— 于是「无头必然复制失败」这个假设在本地绿、CI 上未必绿。
 * 更要紧的是：复制动作的**失败路径才是最需要测试的那一条**
 * （失败时必须推错误通知，否则用户点了菜单项什么都感觉不到），
 * 而它在有头环境下根本走不到。
 *
 * 所以两条路都用注入的替身钉死，**与运行环境无关**。真剪贴板那一环由手工走查覆盖
 * （见 `TEST_CASES.md`：右键 → 复制引用名 → 粘到记事本）。
 */
class TreeContextMenuStateTest {

    /** 记录写入内容的替身；[ok] = 模拟「写入成功 / 失败」。 */
    private class RecordingClipboard(private val ok: Boolean = true) : (String) -> Boolean {
        val written = mutableListOf<String>()
        override fun invoke(text: String): Boolean {
            written += text
            return ok
        }
    }

    private fun state(
        dialect: DialectType,
        notifications: NotificationCenter? = null,
        clipboard: (String) -> Boolean = { true },
        bind: Boolean = true,
    ) = DatabaseBrowserState(
        StubEngineClient, CoroutineScope(Dispatchers.Default), notifications, clipboard,
    ).also {
        if (bind && dialect != DialectType.UNKNOWN) {
            it.bindConnection(
                ConnectionConfig(
                    id = "c1",
                    name = "conn",
                    dialect = dialect,
                    jdbcUrl = "jdbc:dummy::memory:",
                ),
            )
        }
    }

    private fun lastNotification(center: NotificationCenter): AppNotification =
        center.notifications.lastOrNull()
            ?: error("复制动作必须留下痕迹 —— 实际收到 ${center.notifications.size} 条")

    // ============ 复制引用名：三种节点形态 ============

    @Test
    fun `表节点给表名引用`() {
        val s = state(DialectType.POSTGRESQL)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "public", table = "biz_user")
        )
        assertEquals("\"public\".\"biz_user\"", text)
    }

    @Test
    fun `字段节点给表点字段的引用而不是裸字段名`() {
        val s = state(DialectType.POSTGRESQL)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(
                database = "examquestions", schema = "public", table = "biz_user", column = "user_name",
            )
        )
        // 裸的 `"user_name"` 在库里可能压根不存在（它在 biz_user 里）——
        // 复制出来的引用粘到 SQL 里必然报错，用户还得自己拼表名。
        assertEquals("\"public\".\"biz_user\".\"user_name\"", text)
    }

    @Test
    fun `库节点给库引用`() {
        val s = state(DialectType.MYSQL)
        val text = s.treeReferenceText(DatabaseBrowserState.TreeTarget(database = "examquestions"))
        assertEquals("`examquestions`", text)
    }

    @Test
    fun `mysql 表节点在没有 schema 时用库名限定`() {
        val s = state(DialectType.MYSQL)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "", table = "biz_user")
        )
        assertEquals("`examquestions`.`biz_user`", text)
    }

    @Test
    fun `pg 表节点在 schema 还没解析出来时不给库名限定`() {
        // 用户刚展开一个库、schema 往返还没回来就右键 —— 这时若拿 catalog 当限定名，
        // 复制出来的是 `"examquestions"."biz_user"`，在 PG 里解析成一个不存在的 schema。
        val s = state(DialectType.POSTGRESQL)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "", table = "biz_user")
        )
        assertEquals("\"biz_user\"", text)
    }

    @Test
    fun `h2 表节点不把库名当限定名`() {
        // H2 的 catalog（库名）与 schema（PUBLIC）是两层，写 catalog 会报 schema 不存在
        val s = state(DialectType.H2)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(database = "EXAM", schema = "", table = "USERS")
        )
        assertEquals("\"USERS\"", text)
    }

    @Test
    fun `没有绑定连接时仍能给出引用而不是崩掉`() {
        val s = state(DialectType.UNKNOWN, bind = false)
        val text = s.treeReferenceText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", table = "biz_user")
        )
        // 未连接 = 方言未知 → 双引号。菜单此刻已经弹出来了，
        // 给一个能看的名字远好过让整条路径不可用。
        assertEquals("\"biz_user\"", text)
    }

    // ============ 复制名称：永远是裸名 ============

    @Test
    fun `复制名称给裸名且不带引用符`() {
        val s = state(DialectType.MYSQL)
        assertEquals(
            "biz_user",
            s.treePlainName(
                DatabaseBrowserState.TreeTarget(database = "d", schema = "s", table = "biz_user")
            ),
        )
        assertEquals(
            "user_name",
            s.treePlainName(
                DatabaseBrowserState.TreeTarget(database = "d", table = "t", column = "user_name")
            ),
        )
        assertEquals("d", s.treePlainName(DatabaseBrowserState.TreeTarget(database = "d")))
    }

    @Test
    fun `字段优先于表名`() {
        // ⚠️ 变异点：把 `?:` 的顺序反过来（先 table 后 column）时这条会红 ——
        // 字段节点会复制出表名，而用户明明点在字段上。
        val s = state(DialectType.POSTGRESQL)
        assertEquals(
            "user_name",
            s.treePlainName(
                DatabaseBrowserState.TreeTarget(
                    database = "d", schema = "s", table = "biz_user", column = "user_name",
                )
            ),
        )
    }

    // ============ 真的写进剪贴板的是「对的那段文本」 ============

    @Test
    fun `复制引用名时写进剪贴板的是引用而不是裸名`() {
        val clip = RecordingClipboard()
        val s = state(DialectType.POSTGRESQL, clipboard = clip)
        s.copyTreeText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "public", table = "biz_user"),
            quoted = true,
        )
        assertEquals(listOf("\"public\".\"biz_user\""), clip.written)
    }

    @Test
    fun `复制名称时写进剪贴板的是裸名`() {
        // ⚠️ 变异点：把 `quoted` 参数忽略（两条路都复制引用）时这条会红。
        // 用户点「复制名称」拿到的却是带引号的串，粘进代码里就成了一个带引号的标识符。
        val clip = RecordingClipboard()
        val s = state(DialectType.POSTGRESQL, clipboard = clip)
        s.copyTreeText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "public", table = "biz_user"),
            quoted = false,
        )
        assertEquals(listOf("biz_user"), clip.written)
    }

    // ============ 通知分级：成功与失败都要有痕迹 ============

    @Test
    fun `复制成功时推 SUCCESS 通知并带上被复制的文本`() {
        val center = NotificationCenter()
        val s = state(DialectType.POSTGRESQL, notifications = center, clipboard = { true })
        s.copyTreeText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "public", table = "biz_user"),
            quoted = true,
        )
        val n = lastNotification(center)
        assertEquals(AppNotification.Severity.SUCCESS, n.severity)
        assertTrue(n.title.contains("引用名"), "标题要说清复制了什么：${n.title}")
        // 用户点了复制却没拿到东西时，通知里的原文是唯一能对照的东西。
        assertEquals("\"public\".\"biz_user\"", n.detail)
    }

    @Test
    fun `剪贴板不可用时推 ERROR 通知而不是静默`() {
        // ⚠️ 本组最要紧的一条。若失败不推通知，用户点了菜单项**什么都不发生**，
        // 界面上没有任何变化，也没有任何可排查的线索（无头 / 剪贴板被占用 /
        // Wayland 无剪贴板服务，三种原因用户都分辨不出来）。
        val center = NotificationCenter()
        val s = state(DialectType.POSTGRESQL, notifications = center, clipboard = { false })
        s.copyTreeText(
            DatabaseBrowserState.TreeTarget(database = "examquestions", schema = "public", table = "biz_user"),
            quoted = true,
        )
        val n = lastNotification(center)
        assertEquals(AppNotification.Severity.ERROR, n.severity)
        assertTrue(n.title.contains("引用名"), "标题要说清是哪一项失败：${n.title}")
    }

    @Test
    fun `成功与失败的标题都指向同一项`() {
        // ⚠️ 变异点：把 title 写成固定文案时这条会红 —— 用户同时复制多项时
        // 分不清是哪一项出的问题。
        val ok = NotificationCenter()
        state(DialectType.MYSQL, notifications = ok, clipboard = { true })
            .copyTreeText(DatabaseBrowserState.TreeTarget(database = "d", table = "t"), quoted = true)
        val bad = NotificationCenter()
        state(DialectType.MYSQL, notifications = bad, clipboard = { false })
            .copyTreeText(DatabaseBrowserState.TreeTarget(database = "d", table = "t"), quoted = true)
        assertEquals("已复制引用名", lastNotification(ok).title)
        assertEquals("复制引用名失败", lastNotification(bad).title)
    }

    @Test
    fun `没有通知中心时不崩`() {
        // 不少调用点（测试、未来的嵌入用法）传 null —— 那时复制仍要写剪贴板，只是不推通知。
        val clip = RecordingClipboard()
        val s = state(DialectType.POSTGRESQL, notifications = null, clipboard = clip)
        s.copyTreeText(DatabaseBrowserState.TreeTarget(database = "d", table = "t"), quoted = true)
        assertEquals(listOf("\"t\""), clip.written)
    }

    // ============ DDL 是表级动作 ============

    @Test
    fun `库节点不该触发 DDL 请求`() {
        // StubEngineClient.invoke 会 error() —— 库节点上若发出请求，这条会炸。
        // UI 侧按 `target.table != null` 决定是否显示 DDL 项，这里钉住这个前提。
        val center = NotificationCenter()
        val s = state(DialectType.POSTGRESQL, notifications = center)
        val dbTarget = DatabaseBrowserState.TreeTarget(database = "examquestions")
        assertNull(dbTarget.table)
        s.copyTreeDdl(dbTarget)
        assertEquals(0, center.notifications.size, "库节点不该有任何复制动作")
    }

    // ============ 菜单状态本身 ============

    @Test
    fun `TreeContextMenuState 的 show 与 dismiss`() {
        val menu = TreeContextMenuState()
        assertNull(menu.target)
        val t = DatabaseBrowserState.TreeTarget(database = "d", table = "t")
        menu.show(androidx.compose.ui.geometry.Offset(3f, 7f), t)
        assertEquals(t, menu.target)
        assertEquals(3f, menu.offset.x)
        menu.dismiss()
        assertNull(menu.target)
    }

    @Test
    fun `连续右键不同节点时目标被替换而不是累积`() {
        // 变异点：show 不覆盖 target 时这条会红 —— 用户右键 B 弹出的菜单
        // 复制出来的却是 A 的名字。
        val menu = TreeContextMenuState()
        menu.show(androidx.compose.ui.geometry.Offset(1f, 1f), DatabaseBrowserState.TreeTarget(database = "a"))
        menu.show(androidx.compose.ui.geometry.Offset(2f, 2f), DatabaseBrowserState.TreeTarget(database = "b"))
        assertEquals("b", menu.target?.database)
    }

    @Test
    fun `ClipboardWriter 对空串返回 false`() {
        // 空串「复制成功」等于往剪贴板里塞了个空 —— 用户粘出来什么都没有，
        // 而通知却说成功。宁可报失败。
        assertFalse(ClipboardWriter.copy(""))
    }

    @Test
    fun `ClipboardWriter 不抛异常`() {
        // 有头环境下应当成功；无头环境下返回 false 但**不抛** ——
        // 抛异常会把右键事件链整个打断（菜单关不掉、界面卡住）。
        val ok = ClipboardWriter.copy("sundays-clipboard-probe")
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            assertFalse(ok)
        } else {
            assertTrue(ok)
        }
    }
}
