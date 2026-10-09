package com.kxxnzstdsw.sundays

import androidx.compose.ui.geometry.Offset
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
        // 节点原点与 root 重合时，换算退化为恒等 —— 这条钉住「公式里是加不是减」。
        menu.show(Offset(3f, 7f), Offset.Zero, t)
        assertEquals(t, menu.target)
        assertEquals(3f, menu.offsetInRoot.x)
        assertEquals(7f, menu.offsetInRoot.y)
        menu.dismiss()
        assertNull(menu.target)
    }

    @Test
    fun `连续右键不同节点时目标被替换而不是累积`() {
        // 变异点：show 不覆盖 target 时这条会红 —— 用户右键 B 弹出的菜单
        // 复制出来的却是 A 的名字。
        val menu = TreeContextMenuState()
        menu.show(Offset(1f, 1f), Offset.Zero, DatabaseBrowserState.TreeTarget(database = "a"))
        menu.show(Offset(2f, 2f), Offset.Zero, DatabaseBrowserState.TreeTarget(database = "b"))
        assertEquals("b", menu.target?.database)
    }

    @Test
    fun `右键坐标按节点在 root 里的位置换算而不是原样透传`() {
        // 本组是这次修的真 bug：`onRightClick` 给的是**节点局部**坐标，
        // `DropdownMenu.offset` 要的是 **root** 坐标。原样透传的话，节点在树里第几行，
        // 菜单就偏多少 —— 真窗口实测右键第 5 行偏上 414px，菜单压在别的节点上，
        // 用户看着 A 点复制、剪贴板里进来的是 B。
        val menu = TreeContextMenuState()
        // 节点在 root 里的原点：面板内第 5 行
        val nodeOrigin = Offset(0f, 430f)
        // 右键点在节点内的位置：偏下一点
        menu.show(Offset(140f, 18f), nodeOrigin, DatabaseBrowserState.TreeTarget(database = "postgres"))

        assertEquals(140f, menu.offsetInRoot.x, 0.01f)
        assertEquals(448f, menu.offsetInRoot.y, 0.01f)
        // ⚠️ 变异点：show 里去掉换算（offsetInRoot = at）时这条必红，
        // 且红掉的 y 会是 18 —— 正是真窗口截图里菜单跑到面板顶部的那个数。
        assertTrue(menu.offsetInRoot.y > 400f, "菜单必须落在被右键的那一行附近，实际 y=${menu.offsetInRoot.y}")
    }

    @Test
    fun `换算只由节点原点与节点内偏移决定不含任何宿主信息`() {
        // 本轮踩的第二个坑，形态比第一个隐蔽：**公式看起来无懈可击**
        // （节点原点 − 宿主原点 + 节点内偏移，教科书式正确），但 `DropdownMenu`
        // 根本不按宿主解析 offset —— 它的基准就是 root。
        //
        // 真窗口实测（density 1.25，宿主原点 y=133）：减去时菜单顶边在屏幕 y≈165，
        // 不减去时 y≈298，**差值恰好 133 = 宿主原点**。也就是说状态上「减宿主」看着
        // 无误，屏幕上却正好偏一个宿主的高度 —— 这种错任何单测都看不出来，
        // 因为单测只能验证公式，验证不了公式对应的屏幕位置。
        //
        // 这里能钉住的只有一件事：**换算的输入里没有宿主这一项**。
        // 任何人想改回「减宿主原点」，都得先把 [TreeContextMenuState] 的 hostOrigin
        // 字段接回来，而那一步会让下面两条断言立刻红。
        val menu = TreeContextMenuState()

        // 同一个右键点在树里的不同位置 —— 差值必须**只**来自节点原点
        menu.show(Offset(140f, 18f), Offset(0f, 200f), DatabaseBrowserState.TreeTarget(database = "a"))
        val near = menu.offsetInRoot
        menu.show(Offset(140f, 18f), Offset(0f, 900f), DatabaseBrowserState.TreeTarget(database = "b"))
        val far = menu.offsetInRoot

        assertEquals(218f, near.y, 0.01f)
        assertEquals(918f, far.y, 0.01f)
        // 700 = 两个节点原点的差，不多不少
        assertEquals(700f, far.y - near.y, 0.01f)
        // x 同样只来自节点内偏移：菜单左右跟着鼠标走，不受树面板位置影响
        assertEquals(140f, far.x, 0.01f)
    }

    @Test
    fun `节点原点为 root 原点时退化为原样透传`() {
        // 边界：位于 (0,0) 的节点上右键，换算必须是恒等 —— 否则公式里还藏着别的项。
        val menu = TreeContextMenuState()
        menu.show(Offset(12f, 34f), Offset.Zero, DatabaseBrowserState.TreeTarget(database = "d"))
        assertEquals(12f, menu.offsetInRoot.x, 0.01f)
        assertEquals(34f, menu.offsetInRoot.y, 0.01f)
    }

    @Test
    fun `dismiss 只清目标不清坐标`() {
        // 坐标留着无害（下次 show 会覆盖），但如果 dismiss 把坐标一起清了，
        // 而布局恰好在这之间变化，下一次右键就会算在一个过期的基准上。
        val menu = TreeContextMenuState()
        menu.show(Offset(5f, 6f), Offset(1f, 2f), DatabaseBrowserState.TreeTarget(database = "d"))
        menu.dismiss()
        assertNull(menu.target)
        assertEquals(8f, menu.offsetInRoot.y, 0.01f)
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
