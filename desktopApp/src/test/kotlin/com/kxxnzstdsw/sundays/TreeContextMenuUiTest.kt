package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 对象树右键菜单的 **UI 层**契约 —— 真引擎（H2 内存库）+ 真鼠标右键 + 真点击菜单项。
 *
 * ## 为什么纯状态机测试不够
 *
 * [TreeContextMenuStateTest] 证明了「引用名算得对」「通知分级对」，但下面这几件事
 * 纯逻辑测不到，而它们恰好是用户在真窗口里最先撞上的：
 *
 * 1. **右键真的弹得出菜单** —— `Modifier.onRightClick` 是手写的 `pointerInput`
 *    （`RightClick.kt`，因为 Foundation 那个是 `internal`）。挂错位置 / 被 `clickable`
 *    抢走 / 被 LazyColumn 回收，菜单都不出现，而状态机那边一切正常。
 * 2. **菜单挂在哪种节点上就有哪些项** —— 那是 UI 层的 `onTableNode` 判据。
 * 3. **右键不会顺带把表预览打开** —— 两个手势共用一行。
 * 4. **复制真的走到了通知中心** —— 用户看不到「已复制」就等于不知道成没成。
 *
 * ## 为什么只有 4 个用例（而不是每个菜单项一个）
 *
 * 每个 `runComposeUiTest` 都要新建一个 [IdbEngine] + 一份 H2 内存库 + 建表，
 * 全量并发下这些资源是共享的。拆成 10 个用例时，实测在全量里**每轮都有 1~2 条**
 * 被外层 1 分钟预算掐断（报 `UncompletedCoroutinesError: After waiting for 1m,
 * the test body did not run to completion`）—— 红的是排队，不是被测逻辑。
 *
 * 合并成「每种节点一个用例」之后：引擎往返减到 1/3，每个用例内部顺序走完，
 * 外层预算显式给到 2 分钟（内层等待一律 30s，见 [awaitCondition]）。
 * 同一节点的多项断言本来就是一个交互流程，拆开测没有额外收益。
 *
 * ## 为什么 UI 层**不**断言菜单位置与外部 dismiss
 *
 * 这一节存在的理由是：第一版**没有**位置断言，菜单位置错位这个真缺陷整个漏了过去，
 * 直到真窗口走查才发现。所以值得写清楚「补断言为什么补不上」——
 * 这两件事在 `runComposeUiTest` 下都**测不到**，是**量不到**而不是**没坏**：
 *
 * 1. **位置**：Popup 内节点的 `getUnclippedBoundsInRoot()` 与真实渲染位置对不上。
 *    诊断数据（宿主原点 y=106、节点原点 y=192、换算出 offset.y=98）配上量到的
 *    bounds top=161，反推出的基准既不是 root(0) 也不是宿主(106)，而是 63 ——
 *    一个在任何模型下都说不通的数；真窗口同一次右键量得的是另一个值。
 * 2. **点菜单外 / Esc 关闭**：`focusable` Popup 在测试环境拿不到焦点
 *    （节点报 `Focused = 'false'`），`pressKey(Escape)` 送进去没人接；
 *    `dismissOnClickOutside` 的外部点击检测在注入事件下也不触发（实测 30s 超时）。
 *
 * 在这里硬写基于这些量的断言，只会得到**恒绿或恒红**的假信号：
 * 它量的不是渲染结果，却会让「位置对不对 / 能不能关」看起来已被自动化覆盖。
 * 因此位置契约由 [TreeContextMenuStateTest]（换算公式）+ 真窗口走查承担，
 * 见 `TEST_CASES.md` §9.27。
 *
 * ## 剪贴板注入的是**记录型替身**
 *
 * 真剪贴板在 CI/无头环境不可用，而本机 UI 测试环境反而是**有头**的
 * （`isHeadless=false`，实测能写能读）—— 用真剪贴板会让结果依赖运行环境。
 * 这里注入 [Recorder]，断言的是「**写进去的是哪段文本**」。
 * 真剪贴板那一环（粘到记事本里）由手工走查覆盖，见 `TEST_CASES.md`。
 */
@OptIn(ExperimentalTestApi::class)
class TreeContextMenuUiTest {

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var dbName: String
    private lateinit var connection: ConnectionConfig

    /** 记录所有被复制出去的文本 —— 断言锚点是「剪贴板里该出现什么」。 */
    private class Recorder : (String) -> Boolean {
        val written = mutableListOf<String>()
        override fun invoke(text: String): Boolean {
            written += text
            return true
        }
    }

    private lateinit var recorder: Recorder
    private lateinit var center: NotificationCenter

    /** 每个用例创建的 scope —— tearDown 里统一 cancel（见 [browser]）。 */
    private val scopes = mutableListOf<CoroutineScope>()

    /** 轮询预算 —— 必须**远小于**外层 `testTimeout`，否则外层先掐断，报的还是那层壳。 */
    private val awaitBudgetMillis = 30_000L

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-tree-menu").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("H2", H2Dialect())

        dbName = "treemenu_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(64))")
                s.executeUpdate("INSERT INTO users VALUES (1,'Alice')")
            }
        }

        connection = ConnectionConfig(
            id = "tm-${System.nanoTime()}",
            name = "TreeH2",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = jdbcUrl,
        )
        recorder = Recorder()
        center = NotificationCenter()
    }

    @After
    fun tearDown() {
        // scope 先 cancel，再关引擎 —— 顺序反了会出现「协程正用着池，池先被关了」的竞态
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try {
            DriverManager.getConnection(jdbcUrl, "sa", "")
                .use { it.createStatement().use { s -> s.execute("SHUTDOWN") } }
        } catch (_: Exception) {}
        System.setProperty("user.home", originalHome)
    }

    /**
     * 本次测试用的状态机 —— scope **必须持有并在 tearDown 里取消**。
     *
     * `DatabaseBrowserState` 的 scope 从不自己取消（它是应用级的，跨 pane 切换要活着），
     * 所以测试里若随手传一个 scope 就丢掉引用，那些「拉库列表 / 拉表列表」的协程
     * 会活到用例**结束之后**，`runComposeUiTest` 的收尾检查直接报
     * `UncompletedCoroutinesError` —— 红的是这个，被测逻辑其实全对
     * （`H2GuiWalkthroughTest` 那边也显式 cancel，同一个坑）。
     *
     * 调度器用 `Unconfined`：`engine.invoke` 在第一个挂起点（Hikari 阻塞 IO）之后
     * 由别的线程恢复并写快照状态，`Default` 上那次写入不在测试节拍里。
     */
    private fun browser(): DatabaseBrowserState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return DatabaseBrowserState(engine, scope, center, recorder)
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 自建轮询 —— **不用 `waitUntil`**。
     *
     * ## 为什么
     *
     * `waitUntil` 内部靠 `waitForIdle` 推进，而它只等「**当前已排队**」的任务。
     * 状态写入发生在别的线程（`engine.invoke` 在挂起点后由 IO 线程 resume）时，
     * 那一帧还没被登记为待处理，`waitForIdle` 认为无事可做直接返回，条件检查随即
     * 判 false —— 于是无论等多久都不会过，实测报 `ComposeTimeoutException`
     * （外层再被 `runComposeUiTest` 的收尾检查包成 `UncompletedCoroutinesError`，
     * **后者是掩盖问题的那层壳**，排查时先看 suppressed 里那条）。
     *
     * 改成显式推进时钟 + 短暂休眠，与 `H2GuiWalkthroughTest.awaitUiNode` 同一个做法。
     *
     * ## 为什么超时异常里带上「在等什么」
     *
     * `system-out` 在 Gradle 报告里会被截断（实测失败时常常只剩另一个用例的
     * `setUp` 一行），而 `failure.message` 一定保留 —— 排查靠它，不靠 stdout。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitCondition(
        what: String,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > awaitBudgetMillis) {
                throw AssertionError("等待超时：$what（${System.currentTimeMillis() - start}ms）")
            }
            // ⚠️ `waitForIdle` 在前、`advanceTimeByFrame` 在后，两个都要：
            // - `waitForIdle` 把**已排队**的重组帧一次跑完（跨线程写入登记进来后才有效）
            // - `advanceTimeByFrame` 保证即使此刻没有待处理帧，也推进一帧让时钟继续走
            // 只写前者在「队列恰好是空的」那一瞬会什么都不做，条件随即判 false；
            // 只写后者则一次只能推一帧，几十帧的链式重组会超时。
            // `H2GuiWalkthroughTest.awaitUiNode` 只写了后者，本类在它基础上补了前者。
            // （`mainClock` 没有 `advanceUntilIdle`，`ComposeUiTest` 上才有这个方法。）
            runCatching { waitForIdle() }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    /**
     * 渲染浏览屏（剪贴板与通知中心都注入替身），并等到库节点可见。
     *
     * 先等状态机（把**引擎往返**彻底等掉）再等语义树：等的是两件独立的事，
     * 混成一件就等不到（见 [awaitCondition]）。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.render(browser: DatabaseBrowserState) {
        val sheet = SheetDescriptor(
            connection = connection,
            browser = browser,
            status = ConnectionStatus(ConnectionState.CONNECTED, "H2"),
        )
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = listOf(sheet),
                    activeSheetId = connection.id,
                    connections = listOf(connection),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    notifications = center,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        awaitCondition("状态机拉回库列表") {
            !browser.loadingDatabases && browser.databases.isNotEmpty()
        }
        // 先点**状态机**展开库，再等语义树里出库节点 ——
        // `Screen` 是自己发 `SCHEMA.LIST` 的（见 `DatabaseBrowserScreen` 的 LaunchedEffect），
        // 但那一发在 UI 测试里**不一定跑完**：它的协程挂在组合作用域上，
        // 引擎往返一慢，语义树就停在「加载数据库中…」（`H2GuiWalkthroughTest` 记的同一现象）。
        // 这里不依赖它：直接调状态机展开，等价且确定。
        browser.toggleDatabase(browser.databases.first())
        awaitCondition("语义树里出现库节点 $dbName") {
            onAllNodesWithText(dbName, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 等表叶子出现在语义树里。 */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitTableLeaf(text: String) =
        awaitCondition("语义树里出现表节点 $text") {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    /** 等该库的 schema 名解析完成 —— 复制引用与 DDL 都要用它。 */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitSchema(state: DatabaseBrowserState) =
        awaitCondition("schema 解析完成（${state.databases.firstOrNull()}）") {
            state.schemasByDatabase[state.databases.first()]?.isNotEmpty() == true
        }

    /** 等表叶子就位（库已在 [render] 里展开过）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitTableLeafReady() =
        awaitTableLeaf("USERS")

    /**
     * 注入一次**右键**。
     *
     * 用 `MouseInjectionScope.rightClick()`（按下 `MouseButton.Secondary`）而不是
     * `performTouchInput { longClick() }` —— 后者是**触摸**长按，走触摸事件通道，
     * 而 `RightClick.kt` 认的是 `PointerEvent.buttons.isSecondaryPressed`，
     * 触摸长按永远不会置起那个位。用触摸长按测右键菜单，红的是测试不是产品。
     *
     * ## ⚠️ 这里**不**断言菜单位置 —— 而这是一次踩坑后的刻意选择
     *
     * 第一版**没有任何**位置断言，于是真缺陷整个漏了过去：`onRightClick` 给的是节点局部
     * 坐标，`DropdownMenu.offset` 要的是 root 坐标，原样透传后菜单恒定弹在面板顶部
     * （真窗口实测偏上 414px，**正好盖在另一个节点那一行** —— 用户盯着 A 点复制，
     * 剪贴板里进来的是 B，且全程不报错）。
     *
     * 补断言时才发现**在 UI 测试里补不了**：Popup 内节点的 bounds 与真实渲染对不上，
     * 反推出的基准是个在任何模型下都说不通的数。细节与契约归属见类 KDoc 的
     * 「为什么 UI 层不断言菜单位置与外部 dismiss」一节。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.rightClickOn(tag: String) {
        onNodeWithTag(tag).performMouseInput { rightClick() }
        awaitCondition("右键菜单出现在 $tag") { onAllNodesWithTagSafe(TREE_MENU_COPY_NAME_TAG) > 0 }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagSafe(tag: String): Int =
        runCatching { onAllNodesWithTag(tag).fetchSemanticsNodes().size }.getOrDefault(0)

    private fun androidx.compose.ui.test.ComposeUiTest.clickMenuItem(tag: String) {
        // ⚠️ 必须等「**新增**一次写入」而不是「有过写入」：
        // 「复制建表 DDL」要发一次引擎往返，而同一个用例里前面已经复制过引用名了。
        // 只等 `written.isNotEmpty()` 的话条件立刻满足，`recorder.written.last()`
        // 读到的仍是**上一次**的内容 —— 断言 DDL 时拿到的却是 `"PUBLIC"."USERS"`，
        // 红的是测试而不是产品（换回带动画的 DropdownMenu 时反而碰巧能过，
        // 换成 Popup 后菜单出现得更快，竞态就稳定暴露了）。
        val before = recorder.written.size
        onNodeWithTag(tag).performClick()
        awaitCondition("菜单项 $tag 生效（新增一次复制）") { recorder.written.size > before }
        // 菜单必须**自己关掉** —— 它是一个 Popup，不关的话下一次右键的菜单会被压在
        // 旧 Popup 底下，第二次右键什么也看不到（实测卡在这里 30s 超时）。
        // 这条顺带钉住一条真实契约：点了菜单项之后用户能立刻在别处右键，
        // 而不是一个再也关不掉的浮层杵在树上。
        awaitCondition("菜单项 $tag 之后菜单关闭") { onAllNodesWithTagSafe(TREE_MENU_COPY_NAME_TAG) == 0 }
    }

    // ==================================================================
    // 库节点
    // ==================================================================

    @Test
    fun `右键库节点 - 菜单项集合与两种复制的内容`() = runComposeUiTest(testTimeout = 2.minutes) {
        val b = browser()
        render(b)
        awaitTableLeafReady()
        val db = b.databases.first()

        rightClickOn(schemaDbNodeTag(db))
        onNodeWithTag(TREE_MENU_COPY_NAME_TAG).assertExists()
        onNodeWithTag(TREE_MENU_COPY_REF_TAG).assertExists()
        // ⚠️ 库节点**不该**有 DDL 项：「这个库的建表语句」不存在这种东西。
        // 有了它，用户点了只会拿到一个报错。
        assertEquals(0, onAllNodesWithTagSafe(TREE_MENU_COPY_DDL_TAG), "库节点上不应出现「复制建表 DDL」")

        clickMenuItem(TREE_MENU_COPY_NAME_TAG)
        // H2 的库名在树上显示什么，裸名就是什么
        assertEquals(listOf(db), recorder.written)

        rightClickOn(schemaDbNodeTag(db))
        clickMenuItem(TREE_MENU_COPY_REF_TAG)
        assertEquals(listOf(db, "\"$db\""), recorder.written)
    }

    // ==================================================================
    // 表节点
    // ==================================================================

    @Test
    fun `右键表节点 - 引用名与建表 DDL 都进剪贴板并留下通知`() = runComposeUiTest(testTimeout = 2.minutes) {
        val b = browser()
        render(b)
        awaitTableLeafReady()
        awaitSchema(b)

        rightClickOn(schemaTableLeafTag("USERS"))
        // ⚠️ 表节点**必须**有 DDL 项 —— 这是本次功能的主入口
        onNodeWithTag(TREE_MENU_COPY_DDL_TAG).assertExists()

        clickMenuItem(TREE_MENU_COPY_REF_TAG)
        assertEquals(listOf("\"PUBLIC\".\"USERS\""), recorder.written)
        assertTrue(
            center.notifications.any { it.severity == AppNotification.Severity.SUCCESS },
            "复制成功必须有一条 SUCCESS 通知 —— 用户看不到就不知道成没成",
        )

        rightClickOn(schemaTableLeafTag("USERS"))
        clickMenuItem(TREE_MENU_COPY_DDL_TAG)

        val ddl = recorder.written.last()
        assertTrue(
            ddl.contains("CREATE TABLE", ignoreCase = true),
            "复制到的应是建表语句，实际：$ddl",
        )
        assertTrue(ddl.contains("USERNAME", ignoreCase = true), "DDL 应含 USERS 的字段，实际：$ddl")
        // DDL 必须**来自引擎**：五个方言的建表语句差异大到没有共性可循
        // （PG 回填主键/UNIQUE/CHECK，SQLite 读 sqlite_master 原文…），
        // 前端拼一份只会在某一个方言上看起来对。
    }

    @Test
    fun `右键表节点 - 菜单弹出但不顺带打开表预览`() = runComposeUiTest(testTimeout = 2.minutes) {
        // `onRightClick` 与 `clickable` 共用这一行。菜单弹出时若 `b.tabs` 也变了，
        // 用户右键的同时右栏就换了一张表 —— 而他根本没点左键。
        val b = browser()
        render(b)
        awaitTableLeafReady()
        assertEquals(0, b.tabs.size, "右键前不应有标签页")

        // rightClickOn 内部已等「菜单出现」，所以这条同时钉住了「菜单确实弹了」
        rightClickOn(schemaTableLeafTag("USERS"))
        onNodeWithTag(TREE_MENU_COPY_NAME_TAG).assertExists()

        assertEquals(0, b.tabs.size, "菜单弹出时不应顺带打开表预览")
    }

    // ==================================================================
    // 菜单生命周期
    // ==================================================================

    @Test
    fun `菜单项点了之后立刻消失且状态清空`() = runComposeUiTest(testTimeout = 2.minutes) {
        // 「点菜单项 → 菜单消失 → 下一次右键能弹新菜单」这条链路分两截，各由不同机制保证：
        // - 点**菜单项**走 `menu.dismiss()`（在 onClick 里显式调用）→ 这条用例覆盖
        // - 点**菜单外** / 按 Esc 走 `Popup.onDismissRequest` → UI 测试里**测不到**（见下）
        //
        // 本条只管前一截，并确认 dismiss 之后状态确实干净（`target` 清空），
        // 否则下一次右键会拿旧菜单顶替新菜单。
        val b = browser()
        render(b)
        awaitTableLeafReady()
        val db = b.databases.first()

        rightClickOn(schemaTableLeafTag("USERS"))
        onNodeWithTag(TREE_MENU_COPY_DDL_TAG).assertExists()

        clickMenuItem(TREE_MENU_COPY_NAME_TAG)
        assertEquals(listOf("USERS"), recorder.written)

        // 换到库节点再右键：菜单项集合必须跟着换（表节点有 DDL，库节点没有）
        rightClickOn(schemaDbNodeTag(db))
        assertEquals(
            0, onAllNodesWithTagSafe(TREE_MENU_COPY_DDL_TAG),
            "dismiss 不彻底时，第二次右键弹的还是上一次的菜单",
        )
        onNodeWithTag(TREE_MENU_COPY_REF_TAG).assertExists()
    }

    // ==================================================================
    // 字段节点
    // ==================================================================

    @Test
    fun `右键字段 - 给表点字段引用且没有 DDL 项`() = runComposeUiTest(testTimeout = 2.minutes) {
        val b = browser()
        render(b)
        awaitTableLeafReady()
        // 展开表节点 → 字段行。同样走状态机而不是点那个 16dp 的小箭头：
        // 那个 Icon 上的 `clickable` 与外层 Row 的右键检测共用事件通道，
        // 语义点击在这里的时序窗口比等一个节点出现还窄。
        b.toggleTableObjects(b.databases.first(), "USERS")
        awaitCondition("字段行出现 USERNAME") {
            onAllNodesWithText("USERNAME", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        awaitSchema(b)

        rightClickOn(schemaColumnTag("USERNAME"))
        onNodeWithTag(TREE_MENU_COPY_NAME_TAG).assertExists()
        // DDL 是**整张表**的，从字段上点它拿到的是一张毫不相干的表的语句
        assertEquals(0, onAllNodesWithTagSafe(TREE_MENU_COPY_DDL_TAG), "字段节点上不应出现「复制建表 DDL」")

        clickMenuItem(TREE_MENU_COPY_REF_TAG)
        // 裸的 "USERNAME" 在库里不存在（它在 USERS 表里）——
        // 复制出来的引用必须能直接粘进 SQL 跑通。
        assertEquals(listOf("\"PUBLIC\".\"USERS\".\"USERNAME\""), recorder.written)
    }
}
