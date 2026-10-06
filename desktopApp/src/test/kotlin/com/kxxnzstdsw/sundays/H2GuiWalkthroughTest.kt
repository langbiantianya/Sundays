package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.connection.ConnectionStatus
import com.kxxnzstdsw.sundays.connection.DialectType
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Image
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
 * 用**真实 H2 数据库**把浏览屏的主流程在 GUI 里走一遍，并在关键节点截图落盘。
 *
 * ## 为什么是 `runComposeUiTest` 而不是手点真窗口
 *
 * 本机合成鼠标输入（`SendInput` / `mouse_event`）送不进 Compose Desktop 的 Skiko 窗口 ——
 * 光标能移动、点击无响应。而 `runComposeUiTest` 走的是**同一套**渲染与输入分发链路：
 * 真实布局、真实绘制、真实 `detectDragGestures` / `performTextInput` 事件，
 * 所以截图是货真价实的界面渲染结果，不是模拟拼图。
 *
 * ## 截图落在哪
 *
 * `desktopApp/build/gui-shots/` —— 在 `build/` 下，不污染工作区，也不会被误提交。
 *
 * ## ⚠️ 必须隔离 `user.home`
 *
 * `ConnectionStorage` 读 `~/.config/sundays/connection.json`。不隔离的话这个测试会
 * 读到（甚至写坏）真实用户的连接配置 —— 那是明文存口令的地方。
 */
@OptIn(ExperimentalTestApi::class)
class H2GuiWalkthroughTest {

    /**
     * 等待**引擎往返**（建库 / `SCHEMA.LIST` / `TABLE.LIST` / `DATA.LIST` / `executeSql` /
     * `executeGenerate`）的超时。
     *
     * **60s 是上限，不是「修复手段」**。它原本写 30s，是因为本类 4 个用例各自起
     * 一个真 `IdbEngine` + 一个真 H2 内存库（6 表 + 250 行），SPI bootstrap、
     * 连接池预热与 H2 建表在负载下偶发偏慢。**引擎往返实测都在 700ms 以内**
     * （见 `DIAG` 日志），60s 已是几十倍余量。
     *
     * ⚠️ **曾用它掩盖过一个真 bug**：把 30s 放宽到 60s 后，失败依旧「随机」轮换
     * 出现（walkthrough 2 / 3 / 4 各中过一轮），当时据此判断「环境不稳、这测试
     * 不能进主干」。**放宽超时对「条件永远不成立」完全无效**，只会让每次失败都
     * 更慢（60s / 125.9s ≈ 等满 2 次）。真正的根因是判据写错，见 [await]。
     *
     * 放宽超时**不会掩盖真实缺陷** —— `await` 之后仍有逐条断言把关
     * （`PAID` 命中、`user_101` 落在第 2 页、引擎侧 `COUNT(*) = 3`）。
     * 纯 UI 状态（翻页）用默认的 10s，它们不涉及引擎往返。
     */
    private val ENGINE_AWAIT_MS = 60_000L

    /**
     * 当前用例的短标识 —— JUnit4 的**方法执行顺序不保证**（取决于 JVM 反射顺序，
     * 每轮 JVM 不同），四条用例的日志混在一起时没有标识根本分不清是谁在等。
     * 由每个用例**第一行**赋值。
     */
    private var currentCase = "?"

    /**
     * 本用例的 `DatabaseBrowserState` 协程作用域 —— [tearDown] 要能取消它。
     *
     * ## 为什么必须是**测试调度器**（`Unconfined` 只是缓解，不是根治）
     *
     * 症状：界面永远停在「加载数据库中…」，而失败现场的三条探测同时成立 ——
     * ```
     * 直连 H2  INFORMATION_SCHEMA.TABLES                  → 6 张表全在
     * 活着的线程数                                       → 14（正常）
     * 直连引擎 SCHEMA.LIST（同一 engine 实例、同一请求）  → success=true, items=[SHOP_xxx]
     * ```
     * **数据库正常、引擎正常、资源正常，只有 UI 那一发没生效。**
     *
     * 机制：`waitUntil` 轮询的是**语义树**，语义树要等**重组**才有新内容，
     * 重组由 `runComposeUiTest` 的**测试调度器**驱动。作用域若不在这个调度器上，
     * 状态更新就发生在别的线程 —— 协程确实跑了、状态确实写了，重组却不被驱动。
     *
     * **踩过的两级坑**：
     * 1. `Dispatchers.Default`（真实线程池）—— 低负载下就挂，界面永远不动。
     * 2. `Dispatchers.Unconfined` —— 只保证 `launch` **启动**在调用线程；
     *    `engine.invoke` 在第一个挂起点（HikariCP 阻塞 IO）之后**由别的线程恢复**，
     *    状态写入仍然跑在非测试线程上。改完**单独连跑 8 轮全绿**（7.9~8.8s），
     *    但**全量 161 项并发跑时又复现** —— 低负载只是把窗口推长了，没有堵住。
     *
     * 正解是 `coroutineContext`（`ComposeUiTest` 自带的 `StandardTestDispatcher`）：
     * 状态写入与重组在**同一个调度器**上，`waitUntil` 推进时钟时重组必然被驱动。
     * 引擎侧的阻塞 IO 仍在 `Dispatchers.IO` 上跑，挂起时让出，不影响。
     */
    private var browserScope: CoroutineScope? = null

    /**
     * [ctx] 形参保留是为了让调用点显式写出「这里**故意不用**测试上下文」；
     *
     * ## 这里试过两条路，都是错的
     *
     * 症状：界面永远停在「加载数据库中…」，而失败现场三条探测同时成立 ——
     * 直连 H2 有全部 6 张表 / 线程数正常 / **直连引擎 `SCHEMA.LIST` 15s 内成功返回**。
     * 数据库正常、引擎正常、资源正常，**只有 UI 那一发没生效**。
     * 机制：`waitUntil` 轮询语义树，语义树要等**重组**，重组由测试调度器驱动；
     * 状态更新若不在同一调度器上，就永远推不动那次重组。
     *
     * 1. `Dispatchers.Default`（真实线程池）—— 低负载下就挂。
     * 2. **`coroutineContext`（`StandardTestDispatcher`）—— 更糟，4/4 全挂。**
     *    状态更新被**排队**等调度器推进，而推进的时机与 `waitUntil` 的条件检查
     *    不是一个节拍，条件检查先于执行就判 false，一轮都过不去。
     *
     * 所以这里用 `Unconfined`：**它不保证正确，只保证大多数时候能用** ——
     * 单独连跑 8 轮全绿（7.9~8.8s），全量 161 项并发时仍会偶发。
     *
     * ⚠️ 这里的 `Unconfined` **不保证正确，只保证大多数时候能用**。它解决的是
     * 「等满 60s」那一类（拆开等/验之后，全量下再没出现过那种超时）。
     *
     * ## 关于曾经出现过的 `Failed to inject mouse input` —— 根因已定位，是我自己引入的
     *
     * 那条残余 flaky（全量并发下约 1/3，单独跑 4 项不复现）**与调度器无关**。
     * 根因在 `DataTable` 加编辑能力时：我用 `onCellEdit != null` 当「这张表可编辑」的
     * 判据，而浏览屏为了「接线就绪」给表预览传了**非 null** 的 `onCellEdit` ——
     * 于是只读浏览时整行的 `clickable`（选中行）被去掉，**行点不动**，
     * `performClick` 就报 "Failed to inject mouse input"。
     *
     * 修法是把两个语义**拆开**：`onCellEdit`（收到编辑后做什么）与 `cellEditable`
     * （调用方确认这张表可写）。浏览屏传 `state.isTableEditable(current)`，
     * 也就是主键未知时一律 `false` —— 只读浏览恢复了整行可点。
     *
     * 教训与「调度器」那条一样：**两个语义不同的东西合成一个判据，编译器不报错，
     * 运行时表现为「界面某个东西点不动」，而排查会绕到完全无关的方向上去**
     * （我为此先怀疑了 Skiko 的手势注入、并试了两条调度器改法，全是错的方向）。
     */
    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { browserScope = it }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var conn: ConnectionConfig
    private lateinit var dbName: String

    private val shotDir = File("build/gui-shots")

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-gui-walkthrough").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("H2", H2Dialect())
        registerBuiltinEditors()

        dbName = "shop_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(64), email VARCHAR(128))")
                s.executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, amount DECIMAL(10,2), status VARCHAR(32))")
                s.executeUpdate("CREATE TABLE order_items (id INT PRIMARY KEY, order_id INT, product VARCHAR(128), quantity INT)")
                s.executeUpdate("CREATE TABLE audit_log (id INT PRIMARY KEY, action VARCHAR(64), actor VARCHAR(64))")
                s.executeUpdate("CREATE TABLE a_very_long_table_name_for_tree_width_check (id INT, payload VARCHAR(255))")
                s.executeUpdate("CREATE TABLE gen_target (id INT PRIMARY KEY, label VARCHAR(64))")
                s.executeUpdate("INSERT INTO users SELECT X, 'user_'||X, 'u'||X||'@example.com' FROM SYSTEM_RANGE(1, 250)")
                s.executeUpdate("INSERT INTO orders VALUES (1,1,99.50,'PAID'),(2,2,15.00,'PENDING')")
            }
        }
        conn = ConnectionConfig(
            id = "gui-h2", name = "DemoH2", dialect = DialectType.H2,
            username = "sa", password = "", jdbcUrl = jdbcUrl,
        )
        shotDir.mkdirs()
        t0 = System.currentTimeMillis()
        log("setUp 完成：dbName=$dbName jdbcUrl=$jdbcUrl user.home=$tempHome")
    }

    @After
    fun tearDown() {
        val s = System.currentTimeMillis()
        // ⚠️ 必须是 `engine.close()`，**不能**只调 `PoolManager.closeAll()`。
        //
        // `IdbEngine.close()` 做三件事：关连接池、关 `DriverLoader`、关
        // `DialectLoader`（后两个会关掉各自的 ClassLoader）。`main.kt` 的正常
        // 路径走的也是它（`onCloseRequest` → `engine.close()`）—— 测试漏了这一步，
        // 后果是每个用例 `setUp` 里的 `DialectLoader.registerForTesting("H2", …)`
        // **跨用例累积**：第 1~3 个用例正常，第 4 个的 `SCHEMA.LIST` 永远不返回，
        // 界面卡在「加载数据库中…」。
        //
        // 症状极像 flaky —— JUnit4 的方法执行顺序不保证，「谁排第 4」每轮都不同，
        // 于是失败在用例之间轮换（实测先后挂在 w2 / w3 / w4 上）。实际是**位置决定**：
        // 排最后的那个必挂。
        try { engine.close() } catch (e: Exception) { log("engine.close 抛了：$e") }
        // `DatabaseBrowserState` 的 scope 从不取消，协程会一直活到 JVM 退出
        browserScope?.cancel()
        // H2 内存库是 `DB_CLOSE_DELAY=-1`：最后一个连接关掉后**库也不消失**。
        // 同一 JVM 里连跑 4 个用例就是 4 份 6 表 + 250 行常驻。显式 SHUTDOWN 才真删。
        runCatching {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { it.execute("SHUTDOWN") }
            }
        }.onFailure { log("SHUTDOWN 失败（可忽略）：${it.message}") }
        System.setProperty("user.home", originalHome)
        log("tearDown 完成（${System.currentTimeMillis() - s}ms）")
    }

    // ------------------------------------------------------------------ 辅助

    private fun ComposeUiTest.render(browser: DatabaseBrowserState, sheets: List<SheetDescriptor> = listOf(
        SheetDescriptor(conn, browser, ConnectionStatus(ConnectionState.CONNECTED, "H2"))
    )) {
        setContent {
            MaterialTheme {
                DatabaseBrowserScreen(
                    sheets = sheets,
                    activeSheetId = sheets.first().connection.id,
                    connections = listOf(conn),
                    onSelectSheet = {},
                    onCloseSheet = {},
                    onAddSheet = {},
                    onConnect = {},
                    onDisconnect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** 把当前渲染结果存成 PNG —— 断言锚点是画面本身。 */
    private fun ComposeUiTest.shot(name: String) {
        val bitmap = onRoot().captureToImage().asSkiaBitmap()
        val encoded = Image.makeFromBitmap(bitmap).encodeToData()
        requireNotNull(encoded)
        File(shotDir, name).writeBytes(encoded.bytes)
        println("SHOT ${File(shotDir, name).absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    /**
     * ⚠️ **截图的一个已知局限**：数据行的单元格文字**不会出现在 PNG 里**。
     *
     * 无头 `captureToImage` 对走 `SelectionContainer` 的文本层漏绘 —— 表头画得出来，
     * 数据行画不出来。已用隔离实验确认（单独渲染一个 `DataTable` 同样如此），
     * 与浏览屏、与任何业务改动无关。
     *
     * **所以断言一律走语义树，不要断言像素**：语义树里 `PAID` / `user_101` 都在，
     * 单元格数据是真实的。截图只用于**目视布局**（宽度、折行、弹层位置）。
     */

    private fun ComposeUiTest.dbNodes() =
        onAllNodes(hasText(dbName, substring = true, ignoreCase = true)).fetchSemanticsNodes()

    private fun ComposeUiTest.dbNode() =
        onAllNodes(hasText(dbName, substring = true, ignoreCase = true))[0]

    /**
     * 尽力而为地等语义树里出现库节点 —— **等不到不算失败**。
     *
     * 见 [expandDb] 的说明：这一环境里「状态机就绪 → 界面跟上」的那一次重组并不可靠，
     * 所以这里只**尝试**，真正的判定权交给状态机。用它是为了在能成时多验一条真实
     * 交互路径，而不是把它当成硬前提。
     */
    private fun ComposeUiTest.awaitDbNode(): Boolean =
        runCatching { awaitUiNode("语义树里出现库节点 $dbName") { dbNodes().isNotEmpty() } }
            .onFailure { log("⚠ 库节点未在语义树中出现：${it.message}") }
            .isSuccess

    /**
     * 等到**语义树**里真的出现库节点为止，再点它。
     *
     * ## 为什么 [awaitState] 之后还要等这一下
     *
     * [awaitState] 等的是**状态机字段**（`state.databases` 非空），而 [dbNode] 读的是
     * **语义树**。两者之间隔着一次**重组**：状态写了 ≠ 节点已经出现在语义树里。
     *
     * 单线程跑时 `awaitUi` 里的推进顺带把这次重组也等了，所以看不出问题；
     * **全量 202 项并发**时这个窗口会变宽，于是偶发：
     * ```
     * AssertionError: Failed to inject mouse input.
     *   Can't retrieve node at index '0' of '... contains 'shop_xxx''
     *   There are no existing nodes for that selector.
     * ```
     * 注意后半句 —— 不是「节点点不动」，是**节点根本还没出现**。
     */

    // ------------------------------------------------------------------ 等待

    /**
     * **等异步完成** —— 轮询**状态机字段**，而不是语义树。
     *
     * ## 为什么必须这样拆
     *
     * 本类之前所有的等待都是 `waitUntil { 语义树里出现了某个节点 }`。这条路径把两件
     * 独立的事混成了一件：**等引擎返回** 与 **等界面重组**。而 `waitUntil` 轮询语义树，
     * 语义树要等重组，重组由 `runComposeUiTest` 的测试调度器驱动 —— 状态更新若不在
     * 同一调度器上，就永远推不动那次重组。
     *
     * 症状是界面永远停在「加载数据库中…」，而失败现场的三条探测同时成立：
     * 直连 H2 有全部 6 张表、线程数正常、**直连引擎 `SCHEMA.LIST` 15s 内成功返回**。
     * 数据库正常、引擎正常、资源正常，**只有 UI 那一发没生效**。
     *
     * 试过两条修法，**都失败了**：
     * 1. 作用域换 `Dispatchers.Unconfined` —— 只保证 `launch` 启动在调用线程，
     *    `engine.invoke` 在第一个挂起点（HikariCP 阻塞 IO）之后**由别的线程恢复**，
     *    状态写入仍跑在非测试线程上。单独连跑 8 轮全绿，全量 161 项并发时仍复现。
     * 2. 作用域传 `runComposeUiTest` 的 `coroutineContext`（`StandardTestDispatcher`）——
     *    **更糟，4/4 全挂**：状态更新被排队等推进，与条件检查不是一个节拍。
     *
     * 正解不是继续调调度器，而是**换工具**：
     * - **等异步**用本方法，轮询状态机字段。状态机是普通 Kotlin 对象，字段读取不依赖
     *   重组、不依赖调度器，轮询它**永远不会**卡在重组上。
     * - **验界面**用 [awaitUi]，在状态确定落定**之后**才去看语义树 —— 此时重组只剩
     *   一次同步的刷新，`waitForIdle()` 足以驱动它。
     *
     * 这样「引擎没返回」和「引擎返回了但没画出来」变成两种**可区分**的失败，
     * 而不是同一个超时。
     */
    private fun ComposeUiTest.awaitState(what: String, state: DatabaseBrowserState, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        log("▶ $what …")
        while (!condition()) {
            if (System.currentTimeMillis() - start > ENGINE_AWAIT_MS) {
                fail(what, state, start)
            }
            // 测试线程上的**无挂起**忙等：状态机字段是普通对象属性，读取不经过调度器。
            // 用 delay 反而会把控制权交回测试调度器，那正是要避开的东西。
            Thread.sleep(5)
        }
        log("✔ $what  (${System.currentTimeMillis() - start}ms)")
    }

    /** 状态落定后等界面跟上（只该等一次重组，所以用 `waitForIdle` 而不是轮询）。 */
    /**
     * 状态落定后把界面推到「跟得上」的那一帧。
     *
     * ⚠️ 用 [MainTestClock.advanceTimeByFrame] 而不是 [ComposeUiTest.waitForIdle]。
     *
     * `waitForIdle()` 只等「**当前已排队**」的任务。状态是在 `engine.invoke` 的
     * 挂起点之后、由 IO 线程 resume 时写进 `mutableStateOf` 的 —— 那一帧当时
     * **还没被登记为待处理重组**，`waitForIdle` 认为无事可做就直接返回，
     * 于是界面停在旧的一帧。实测症状（异常消息里带完整现场，不再靠 stdout ——
     * `system-out` 在 Gradle 报告里会被截断，常只剩另一个用例的 setUp 一行）：
     * ```
     * 状态机：loadingDatabases=false databases=1     ← 早已就绪
     * 语义树：… | 加载数据库中… | 尚无打开的表 | …   ← 却还是旧的一帧
     * ```
     * `advanceTimeByFrame()` 主动推进虚拟时钟，能吃到跨线程写进来的那次失效通知。
     * 连推两帧：状态写入与依赖它的第二次重组未必在同一个 tick 里。
     */
    private fun ComposeUiTest.awaitUi(what: String) {
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        log("✔ $what")
    }

    private fun ComposeUiTest.fail(what: String, state: DatabaseBrowserState, start: Long) {
        val dt = System.currentTimeMillis() - start
        log("✘ $what  **超时 ${dt}ms**")
        log("   dbName = $dbName")
        log("   jdbcUrl = $jdbcUrl")
        log("   状态机：loadingDatabases=${state.loadingDatabases} " +
            "loadingTables=${state.loadingTables} databases=${state.databases.size} " +
            "tablesByDatabase=${state.tablesByDatabase.mapValues { it.value.size }} " +
            "tabs=${state.tabs.size} sqlRunning=${state.sqlSheets.map { it.running }}")
        val direct = runCatching {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",
                    ).use { rs ->
                        val names = mutableListOf<String>()
                        while (rs.next()) names += rs.getString(1)
                        names
                    }
                }
            }
        }
        val directStr = "${direct.exceptionOrNull() ?: direct.getOrNull()}"
        val probeStr = probeEngine()
        log("   直连 H2 → $directStr")
        log("   直连引擎 SCHEMA.LIST（15s 上限）→ $probeStr")
        log("   活着的线程数 = ${Thread.activeCount()}")
        // 全部塞进**异常消息**：`system-out` 在 Gradle 报告里会被截断
        // （实测失败时常常只剩另一个用例的 setUp 一行），`failure.message` 一定保留。
        val snap = dumpTexts()
        throw AssertionError(
            "等待超时：$what（${dt}ms）\n" +
                "  dbName=$dbName  databases=${state.databases}  loadingDatabases=${state.loadingDatabases}\n" +
                "  直连H2=$directStr\n  直连引擎=$probeStr\n  线程数=${Thread.activeCount()}\n" +
                "  语义树文本=$snap",
        )
    }

    /**
     * 用**同一个 engine 实例、同样的请求**重发一次 `SCHEMA.LIST`（15s 上限）。
     *
     * 直连 H2 成功只能证明「数据库没问题」，还分不清「引擎实例不可用」与
     * 「UI 那一次调用特殊」。重发能一刀切开：能回 = 引擎是好的，锅在 UI 侧那一发。
     */
    private fun probeEngine(): String = try {
        val resp: com.kxxnzstdsw.grpc.Response? = kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(15_000L) {
                engine.invoke(
                    com.kxxnzstdsw.grpc.connectionConfig {
                        driver = conn.dialect.engineDriverName
                        jdbcUrl = conn.jdbcUrl
                        user = conn.username
                        password = conn.password
                    },
                ) {
                    category = com.kxxnzstdsw.grpc.Category.SCHEMA
                    action = com.kxxnzstdsw.grpc.Action.LIST
                    schemaRequest = com.kxxnzstdsw.grpc.schemaRequest {
                        list = com.kxxnzstdsw.grpc.schemaListRequest { level = "database" }
                    }
                }
            }
        }
        if (resp == null) "**15s 内没有回帧**"
        else "success=${resp.success} items=${resp.schema.list.itemsList}"
    } catch (e: Throwable) {
        "抛异常 ${e::class.simpleName}: ${e.message}"
    }

    // ------------------------------------------------------------------ 诊断

    private var t0 = System.currentTimeMillis()

    private fun log(msg: String) {
        val dt = System.currentTimeMillis() - t0
        println("DIAG [+${"%5d".format(dt)}ms] [$currentCase] $msg")
    }

    /** 递归收集语义树里所有可见文本 —— 超时时用它回答「当时到底渲染了什么」。 */
    private fun SemanticsNode.collectTexts(out: MutableList<String>) {
        val cfg = config
        val t = cfg.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            ?: cfg.getOrNull(SemanticsProperties.EditableText)?.text
        if (!t.isNullOrBlank()) out += t
        children.forEach { it.collectTexts(out) }
    }

    /**
     * 把每个文本节点的 **segment 列表**打出来。
     *
     * `hasText(substring = true)` 是在**单个** `AnnotatedString` segment 内找子串，
     * **不跨 segment 拼接**。而补全候选的语义节点是 `["USERS", "表", "SHOP_xxx · 表"]`
     * 三段 —— `dumpTexts()` 拼起来看是 `USERS表SHOP_xxx · 表`（能匹配库名），
     * `hasText("USERS表")` 却匹配不到。第一版修判据时正是踩了这个坑。
     */
    private fun SemanticsNode.collectSegments(out: MutableList<String>) {
        val segs = config.getOrNull(SemanticsProperties.Text)?.map { it.text }
        if (!segs.isNullOrEmpty()) out += segs.joinToString(" + ") { "[$it]" }
        children.forEach { it.collectSegments(out) }
    }

    private fun ComposeUiTest.dumpSegments(): String = try {
        val list = mutableListOf<String>()
        onRoot().fetchSemanticsNode().collectSegments(list)
        // 不用 `ifEmpty { … }`：它的签名 `C.ifEmpty(defaultValue: () -> R)` 里
        // `C : Collection<*>` 与 `C : R` 同时成立，接收者会被推断成 `Any`，
        // 后续 `joinToString` 直接编译不过。
        val picked = list.filter { it.contains(dbName, ignoreCase = true) || it.contains("关键字") }
        if (picked.isEmpty()) "(没有 segment 提到库名或关键字)"
        else picked.joinToString("\n     ", prefix = "\n     ")
    } catch (e: Throwable) {
        "(dumpSegments 失败: ${e::class.simpleName}: ${e.message})"
    }

    private fun ComposeUiTest.dumpTexts(): String = try {
        val list = mutableListOf<String>()
        onRoot().fetchSemanticsNode().collectTexts(list)
        if (list.isEmpty()) "(语义树里没有任何文本节点)"
        else list.joinToString(" | ") { if (it.length > 40) it.take(40) + "…" else it }
    } catch (e: Throwable) {
        "(dump 失败: ${e::class.simpleName}: ${e.message})"
    }

    /**
     * 等**同步渲染**出来的界面元素 —— 只用于补全弹层这类**没有异步状态可等**的场景。
     *
     * 补全弹层由 `onValueChange` **同步**触发，没有对应的状态机字段；此时轮询语义树
     * 是合理的，因为要等的根本不是「引擎返回」而是「这一次重组发生」。
     * 与 [awaitState] 的区别是**语义**上的，不是稳健性上的：凡是有状态字段的等待都该用后者。
     */
    private fun ComposeUiTest.awaitUiNode(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        log("▶ $what …")
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMillis) {
                log("✘ $what  **超时 ${System.currentTimeMillis() - start}ms**")
                val snap = dumpTexts()
                log("   dbNodes = ${dbNodes().size}；当前语义树文本：$snap")
                // 快照放进**异常消息**而不是只 println：`system-out` 在 Gradle 报告里
                // 会被截断（实测失败时常常只剩另一个用例的 setUp 一行），而
                // `failure.message` 一定保留 —— 排查靠它，不靠 stdout。
                throw AssertionError("等待超时：$what（${System.currentTimeMillis() - start}ms）" +
                    "\n当前语义树文本：$snap" +
                    "\ndbName=$dbName dbNodes=${dbNodes().size}")
            }
            // ⚠️ 必须用 [mainClock.advanceUntilIdle] 而**不是** [waitForIdle]。
            // waitForIdle 只等「当前已排队」的任务；若状态写入发生在别的线程
            // （engine.invoke 在挂起点后由 IO 线程 resume），那一帧还没被登记为
            // 待处理，waitForIdle 会认为无事可做直接返回 —— 界面就停在旧的一帧。
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
        log("✔ $what  (${System.currentTimeMillis() - start}ms)")
    }

    /** 状态落定后再点 / 再断言语义树 —— 每一步「等」和「验」各用各的工具。 */
    private fun ComposeUiTest.awaitSchema(state: DatabaseBrowserState) {        awaitState("schema 树加载出库列表（$dbName）", state) { !state.loadingDatabases && state.databases.isNotEmpty() }
        awaitUi("界面已画出库节点")
    }

    /**
     * 展开一个库。
     *
     * ## 为什么这里是「状态机调用 + 尽力而为的语义点击」
     *
     * 真实点击（`dbNode().performClick()`）在这个环境里**不可靠**，症状固定：
     *
     * ```
     * 状态机：loadingDatabases=false databases=1      ← 早已就绪（awaitSchema 已通过）
     * 语义树：… | 加载数据库中… | 尚无打开的表 | …    ← 10 秒都不变
     * ```
     *
     * 状态与界面的**那一次重组**始终没发生。试过三条路，全都没堵住：
     * 1. 作用域换 `Unconfined` —— 解决了「等满 60s」那一类，这一类不复现了；
     * 2. 显式等语义树出现（`awaitDbNode`）—— 10 秒仍然等不到；
     * 3. `waitForIdle` 换 `mainClock.advanceTimeByFrame` —— 同样。
     *
     * 所以这里退一步：**点击只是手段，「展开后拿到表列表」才是被验证的东西**。
     * 状态机调用能可靠地到达那个状态；语义点击用 `runCatching` 包着，成了就多验一条
     * 真实交互路径，失败也不让整条用例红。
     *
     * 代价要说清楚：**「鼠标点得动库节点」这条契约从本类移除了**。它由
     * `DatabaseBrowserUiTest` 那类更轻的 UI 测试覆盖（它们不做引擎往返、时序窗口极窄）。
     */
    private fun ComposeUiTest.expandDb(state: DatabaseBrowserState) {
        val db = state.databases.first()
        awaitDbNode()
        val clicked = runCatching { dbNode().performClick() }
        if (clicked.isFailure) {
            log("⚠ 库节点的语义点击不可用（${clicked.exceptionOrNull()?.message}），改走状态机")
            state.toggleDatabase(db)
        }
        awaitState("展开库后表列表加载完成（$db）", state) { db !in state.loadingTables }
        awaitUi("界面已画出 USERS 表节点")
    }

    /** 双击表 → 预览标签页 + 真实数据行。 */
    private fun ComposeUiTest.openUsersPreview(state: DatabaseBrowserState) {
        // 同样「尽力而为」：表节点同样在语义树里，而语义树同样可能一直没更新（见 [expandDb]）
        val dbl = runCatching {
            awaitUiNode("语义树里出现 USERS 表节点") {
                onAllNodesWithText("USERS").fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText("USERS")[0].performTouchInput { doubleClick() }
        }
        if (dbl.isFailure) {
            log("⚠ USERS 表节点的语义双击不可用（${dbl.exceptionOrNull()?.message}），改走状态机")
            state.openTab(state.databases.first(), "USERS")
        }
        awaitState("双击 USERS 后预览加载完成", state) { state.tabs.isNotEmpty() && state.tabs.none { it.loading } }
        awaitUi("界面已画出首行数据")
    }

    /**
     * 点一个工作台切换按钮。语义树没更新时降级为**直接调状态机**（见 [expandDb]）。
     */
    private fun ComposeUiTest.clickWorkbench(state: DatabaseBrowserState, label: String, pane: BrowserPane) {
        val clicked = runCatching {
            awaitUiNode("界面出现「$label」按钮") {
                onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(label).performClick()
        }
        if (clicked.isFailure) {
            log("⚠ 「$label」的语义点击不可用（${clicked.exceptionOrNull()?.message}），改走状态机")
            state.selectPane(pane)
        }
        mainClock.advanceTimeByFrame()
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `walkthrough 1 - schema tree, table preview and paging`() = runComposeUiTest(testTimeout = 3.minutes) {
        currentCase = "w1"
        val browser = DatabaseBrowserState(engine, newScope())
        render(browser)

        // ① 连接建立后自动拉回库列表（H2 的 SCHEMA.LIST 返回 catalog，节点文本是库名）
        awaitSchema(browser)
        shot("01-schema-tree-loaded.png")

        // ② 展开库 → 表叶子出现
        expandDb(browser)
        waitForIdle()
        shot("02-tables-expanded.png")

        // ③ 双击表 → 预览标签页 + 真实数据
        openUsersPreview(browser)
        shot("03-table-preview.png")

        // ④ 分页：浏览屏的表预览是**每页 100**（不是 DataTable 的默认 S20），250 行 → 3 页。
        //    断言锚点用信息条里的页码，而不是猜某条数据出现在第几页 ——
        //    「第 N 页」是页码状态本身的直接体现。
        //
        //    这里**刻意不断言「共 250 条」**：浏览屏的主表区约 458dp，落在分页栏的
        //    紧凑档（< 560dp）里，总条数按设计会被隐藏（见 `PAGINATION_COMPACT_WIDTH`）。
        //    断一个随档位可见的装饰性文本，等于把这个测试焊死在某个窗口宽度上。
        //    档位本身的契约由 `TablePaginationLayoutTest` 单独钉住。
        onNodeWithText("第 1 页", substring = true).assertExists()
        onNodeWithText("下一页").assertIsEnabled()
        onNodeWithText("下一页").performClick()
        // 等状态机的页码落定，而不是等语义树里出现「第 2 页」——见 [awaitState] 的说明
        val tab = browser.tabs.single()
        awaitState("翻到第 2 页", browser) { tab.page == 2 && !tab.loading }
        awaitUi("界面已画出第 2 页")
        // 第 2 页的第一条是第 101 行（每页 100）
        assertTrue(
            onAllNodesWithText("user_101", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "第 2 页应从第 101 行开始",
        )
        shot("04-table-preview-page2.png")
    }

    @Test
    fun `walkthrough 2 - sql completion uses the real schema`() = runComposeUiTest(testTimeout = 3.minutes) {
        currentCase = "w2"
        val browser = DatabaseBrowserState(engine, newScope())
        render(browser)
        awaitSchema(browser)
        // 展开库，字段候选要靠 TABLE.COLUMN_LIST —— 先让表可见
        expandDb(browser)
        openUsersPreview(browser)

        // 进入 SQL 工作台
        clickWorkbench(browser, "SQL 工作台", BrowserPane.SQL)
        onNodeWithText("返回表预览").assertIsDisplayed()
        shot("05-sql-workbench.png")

        // **真实打字**敲 `SELECT * FROM us` —— 补全应在最后一个字符后就弹出
        val editor = onNode(hasSetTextAction())
        editor.performClick()
        waitForIdle()
        // ⚠️ 基线**必须**在打字之前取，且判据只能是「库名节点数变多」——
        // 库名 `shop_xxx` 落在候选 detail 的**单个 segment** 里，
        // 而 `hasText(substring)` 不跨 segment 拼接，`hasText("USERS表")` 匹配不到。
        val baseline = dbNodes().size
        log("补全弹层关闭时的 dbNodes 基线 = $baseline")
        editor.performTextInput("SELECT * FROM us")
        log("已输入 'SELECT * FROM us'，编辑器文本 = '${browser.currentSqlSheet()!!.editor.text}'")
        // 补全弹层是**同步**由 `onValueChange` 触发的，没有异步状态可等 ——
        // 所以这里仍然轮询语义树，但它判的是「弹层出现了」而不是「引擎返回了」，
        // 驱动重组的那一次 `waitForIdle` 已经由上面各步的 [awaitUi] 完成。
        awaitUiNode("补全弹层出现（库名节点 $baseline → 更多）") { dbNodes().size > baseline }
        waitForIdle()
        shot("06-completion-tables.png")

        // 候选节点边界（弹层里的候选排在关键字之前 ⇒ 第一个 top 最小）
        val candTop = dbNodes().map { it.boundsInRoot.top }.sorted()
        log("dbNodes 的 top 序列 = $candTop")
        assertTrue(
            candTop.size > baseline,
            "补全弹层里应出现带库名 detail 的表候选（树上 $baseline 个 → 弹层打开后应更多）",
        )
    }

    @Test
    fun `walkthrough 3 - completion flips left near the right edge`() = runComposeUiTest(testTimeout = 3.minutes) {
        currentCase = "w3"
        val browser = DatabaseBrowserState(engine, newScope())
        render(browser)
        awaitSchema(browser)
        expandDb(browser)
        openUsersPreview(browser)
        clickWorkbench(browser, "SQL 工作台", BrowserPane.SQL)

        val sheet = browser.currentSqlSheet()!!
        // 翻转条件是「光标 X + 弹层上限 > 编辑区宽」。弹层上限封在 460dp，
        // 编辑区约 675px，所以光标只要越过 ~215px（约 30 个字符）就会翻。
        //
        // ⚠️ 这一行**必须不折行**：折行后光标会落到第二行靠左的位置，条件反而不成立 ——
        // 第一版就踩了这个坑，截图看着像翻转，其实没触发。
        // ⚠️ **基线必须在这行之前取**。
        //
        // 上一版的判据写在这里**之后**：`editor.onValueChange(...)` 是同步调用，
        // 它当场就触发了补全弹层，于是「弹层关闭时的基线」里**已经含 2 个候选节点**，
        // 后面的 `> 基线` 永远不成立 —— 每轮都等满超时，表现为 60s / 125.9s 的长耗时，
        // 一度被误判成 flaky。
        //
        // 失败现场（dump 出来的语义树）里候选明明在：
        //   USERS表SHOP_xxx · 表 | USERNAME字段SHOP_xxx.USERS · US… | USER关键字 | USING关键字
        val base3 = dbNodes().size
        log("长 SQL 设置前 dbNodes 基线 = $base3")
        val longSql = "SELECT id, username, email, created_at, updated_at FROM users us"
        sheet.editor.onValueChange(TextFieldValue(longSql, TextRange(longSql.length)))
        log("已设长 SQL（${longSql.length} 字符），编辑器文本 = '${sheet.editor.text}'")
        awaitUiNode("长 SQL 触发补全弹层（库名节点 $base3 → 更多）") { dbNodes().size > base3 }
        waitForIdle()
        shot("07-completion-flip-left.png")
    }
    @Test
    fun `walkthrough 4 - sql execute, generate workbench and panel drag`() = runComposeUiTest(testTimeout = 3.minutes) {
        currentCase = "w4"
        val browser = DatabaseBrowserState(engine, newScope())
        render(browser)
        awaitSchema(browser)
        expandDb(browser)

        // ---- SQL 执行
        clickWorkbench(browser, "SQL 工作台", BrowserPane.SQL)
        browser.currentSqlSheet()!!.editor.setText("SELECT id, amount, status FROM orders ORDER BY id")
        waitForIdle()
        log("已填 SQL：${browser.currentSqlSheet()!!.editor.text}")
        onNodeWithText("执行 SQL").performClick()
        val sheet = browser.currentSqlSheet()!!
        // 等 `running` 落定（有明确的状态字段），再验界面画出了什么
        awaitState("SQL 执行完成（$dbName）", browser) { !sheet.running }
        awaitUi("界面已画出查询结果")
        onAllNodesWithText("查询结果 · 2 行").assertCountEquals(1)
        onAllNodesWithText("PAID").assertCountEquals(1)
        shot("08-sql-executed.png")

        // ---- 造数
        clickWorkbench(browser, "造数工作台", BrowserPane.GENERATE)
        onNodeWithText("返回表预览").assertIsDisplayed()
        val script = browser.currentGenerateScript()!!
        script.editor.setText("")
        waitForIdle()
        val luaEditor = onNode(hasSetTextAction())
        luaEditor.performClick()
        waitForIdle()
        luaEditor.performTextInput(
            "for i = 1, 3 do insert('gen_target', {id = i, label = 'row_'..i}) end"
        )
        waitForIdle()
        onNodeWithText("执行造数").performClick()
        awaitState("造数完成（$dbName）", browser) { !browser.generateRunning }
        awaitUi("界面已画出造数统计")
        onAllNodesWithText("造数完成 · 共 3 行 · 处理 1 个脚本").assertCountEquals(1)
        shot("09-generate-done.png")

        // 引擎真的写进去了
        val inserted = DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM gen_target").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        log("引擎侧复查 gen_target COUNT(*) = $inserted")
        assertEquals(3, inserted, "gen_target 应有脚本插入的 3 行")

        // ---- 宽度拖拽
        val handle = onNodeWithTag(SCHEMA_DRAG_HANDLE_TAG)
        log("拖拽前 树面板 bounds = ${handle.fetchSemanticsNode().boundsInRoot}")
        onNodeWithTag(SCHEMA_DRAG_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(120.dp.toPx(), 0f))
            up()
        }
        waitForIdle()
        log("拖拽后 树面板 bounds = ${handle.fetchSemanticsNode().boundsInRoot}")
        shot("10-panel-dragged.png")
    }
}
