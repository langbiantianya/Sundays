/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.sql.SqlLiterals
import com.langbiantianya.sundays.table.TableRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * **已实现功能的真机走查** —— 在 **H2 与 SQLite 两个方言**上，把最近落地的五项功能
 * 逐条走一遍，每条都落到**真实数据库**上验。
 *
 * ## 为什么是 `runComposeUiTest` 而不是手点真窗口
 *
 * 本机合成鼠标输入（`SendInput` / `mouse_event`）送不进 Compose Desktop 的 Skiko 窗口：
 * 光标能移动、点击无响应（见 `H2GuiWalkthroughTest` 的类注释）。`runComposeUiTest` 走的是
 * **同一套**渲染与输入分发链路 —— 真实布局、真实绘制、真实点击 —— 所以截图是货真价实的
 * 界面渲染结果。**这与「启动程序」是等价的**，只是驱动方式不同。
 *
 * ## 为什么必须两个方言都跑
 *
 * 单跑一个方言，这些能力差异**一条都测不出来**：
 *
 * | | H2 | SQLite |
 * |---|---|---|
 * | 视图 / 索引 / 外键 | 有 | 有 |
 * | 触发器 / 过程·函数 | 有 | **无**（`SQLiteDialect` 明确抛 `UnsupportedOperationException`） |
 * | 标识符大小写 | 全**大写** | 原样**小写** |
 * | 事务隔离表现 | 未提交对他人不可见 | 未提交时对方可能被 `SQLITE_BUSY` 锁住 |
 *
 * 「不支持」这一侧尤其要紧：应用必须把它**显示成一条错误**并**停止转圈**，
 * 绝不能永远空转，更不能连累同一分组里的视图一起消失 —— 单跑 H2 永远看不到这条路径。
 *
 * ## 断言打在哪
 *
 * 无头 `captureToImage` 对走 `SelectionContainer` 的文本层漏绘（`H2GuiWalkthroughTest`
 * 已确认），数据行的文字不会出现在 PNG 里。所以分工固定：
 * **状态机 + 独立 JDBC 连接负责「对不对」，截图只负责「长什么样」**。
 *
 * ## 执行结果（2026-10-06，一次执行 = H2 × 5 条 + SQLite × 5 条 = **10 条**）
 *
 * 10 条全过。**首轮执行是 4 / 10**，四条红测全部是真缺陷：
 *
 * | # | 缺陷 | 影响面 | 根因 | 修在哪 |
 * |---|---|---|---|---|
 * | 1 | 表内搜索**完全不可用** —— 发出去的 SQL 是 `WHERE LIKE '%词%'`，**没有左操作数** | H2 / SQLite / PG **全部方言** | `SqlLiterals.likeContains` 只返回裸片段，而 `SqlLiteralsTest` 把这个片段当正确输出断言了下来 —— 整条链**一次没跑过真库** | [SqlLiterals.likeContains] |
 * | 2 | 表级对象（索引 / 外键）**首次展开必然为空** | 全部方言 | schema 还没解析完就发查询 → schema 传空串 → 请求**成功**、返回空列表、**不重试** | TC-OB2 |
 * | 3 | **一类对象失败会连累其它类** —— SQLite 上触发器不支持，整个「对象」区被一行红字顶掉，**能正常列出的视图一起消失** | 有不支持项的方言 | 渲染层「任意一类出错 → 整个对象区换成一行错误」 | TC-OB1 |
 * | 4 | 表内搜索无谓地**要求**转义正确却从不真正执行 | — | 与 #1 同源，已随 #1 修复 | — |
 *
 * **#1 是最值得记的一条**：缺陷在「片段的**拼接**」上，而测试只盯「片段的**转义**」。
 * 判据必须落在**发出去的那条语句**上 —— 这也是本次走查把它逼出来的唯一原因。
 *
 * ## 走查过程中修掉的**测试自身**缺陷
 *
 * 这四条都不是被测代码的问题，但每一条都让走查**误报**过一次：
 *
 * | 判据缺陷 | 后果 |
 * |---|---|
 * | 等待条件用 `(库, 类型)` 而集合里存的是 `(slot, 类型)` | 条件恒真，白等 → 把「应用没写数据」误报成「界面没显示」 |
 * | `hasText("视图")` 用了默认 `substring = false`，而实际文本是 `"视图："` | 永远匹配不上，误报「界面没渲染」 |
 * | 等待条件只看 `loadingObjects` 清空 | `loadDatabaseObjects` 先撤占位再逐类置上，中间有窗口 → 在数据落地前就断言（见 [settledObjectKinds]） |
 * | 用 `onNode(hasScrollAction())` 找树 | 整屏有多个可滚动节点，且**按序号取会随库名长度漂移**；改用「逐个候选试一遍」 |
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class FeatureWalkthroughTest(
    private val target: WalkthroughTarget,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "[{0}]")
        fun targets(): List<Array<Any>> = WalkthroughTarget.all().map { arrayOf(it as Any) }
    }

    /** 等待引擎往返的上限。实测往返都在 700ms 内，60s 是几十倍余量。 */
    private val engineAwaitMs = 60_000L

    private var browserScope: CoroutineScope? = null

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { browserScope = it }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var conn: ConnectionConfig

    /** 建触发器 / 函数别名的结果 —— 报告要照实写，不能把「没建成」写成「界面没显示」。 */
    private var triggerBuild: String? = null
    private var functionBuild: String? = null

    private val shotDir = File("build/gui-shots")

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-feature-walkthrough").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // ⚠️ 必须在**每个用例**都注册方言：`engine.close()` 会连带清空 `DialectLoader`
        // 的注册表，而 SPI bootstrap 是全局幂等的（只扫一次）—— 于是别的测试类跑过之后
        // 这里就再也解析不到方言，症状是「等库列表等满 60s」或「等连接成功等满 10s」，
        // 完全指不到「方言没注册」。本类原来靠 `target.open()` 注册（只有本地目标会注册），
        // 加上 `ConnectionWizardMouseKeyboardTest` 之后就稳定红了。
        target.registerDialect()
        target.open(tempHome)
        registerBuiltinEditors()

        seedDatabase()

        conn = target.connectionConfig()
        shotDir.mkdirs()
        println("SETUP [${target.name}] url=${target.jdbcUrl} trigger=$triggerBuild function=$functionBuild")
    }

    /**
     * 造一份**带视图 / 索引 / 外键**的库。
     *
     * DDL 全部写成两个方言都接受的最小公共形式；行数据用**批插**而不是
     * `SYSTEM_RANGE`（H2 专有）或递归 CTE（SQLite 专有）。
     */
    private fun seedDatabase() {
        val host = WalkthroughSupport::class.java.name
        target.openDirect().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(64), email VARCHAR(128))")
                s.executeUpdate(
                    "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, amount DECIMAL(10,2), " +
                        "status VARCHAR(32), CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id))",
                )
                s.executeUpdate("CREATE TABLE gen_target (id INT PRIMARY KEY, label VARCHAR(64))")
                s.executeUpdate("CREATE INDEX idx_orders_status ON orders(status)")
                s.executeUpdate("CREATE VIEW v_active_users AS SELECT id, username FROM users WHERE id <= 3")
            }
            c.prepareStatement("INSERT INTO users VALUES (?, ?, ?)").use { ps ->
                for (i in 1..250) {
                    ps.setInt(1, i)
                    ps.setString(2, "user_$i")
                    ps.setString(3, "u$i@example.com")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.createStatement().use { s ->
                s.executeUpdate("INSERT INTO orders VALUES (1,1,99.50,'PAID'),(2,2,15.00,'PENDING')")
                triggerBuild = target.createTrigger(s, host)
                functionBuild = target.createFunction(s, host)
            }
        }
    }

    @After
    fun tearDown() {
        // 顺序与 H2GuiWalkthroughTest 一致：先关引擎（它会连 DialectLoader 的 ClassLoader 一起关），
        // 再取消协程作用域。SQLite 还要额外删临时文件。
        try {
            engine.close()
        } catch (e: Exception) {
            println("engine.close 抛了：$e")
        }
        browserScope?.cancel()
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }

    // ==================================================================
    // TC-OB：对象浏览
    // ==================================================================

    /**
     * ## TC-OB1 — 库级对象：视图 / 触发器 / 过程·函数
     *
     * | | |
     * |---|---|
     * | **前置** | 连接已建立，库列表已加载 |
     * | **步骤** | ① 展开库节点 ② 等待三类对象分组落定 |
     * | **预期** | ① **schema 名先被解析出来**（H2 → `PUBLIC`，SQLite → `main`）<br>② 支持的种类列出真实对象名<br>③ 不支持的种类要么报错、要么为空，但**绝不能永远转圈**，也**绝不能把支持的种类一起弄没** |
     * | **实际** | **H2 ✅ / SQLite ✅**。首轮执行时是红的 —— 两个方言都过是修完之后的结果 |
     *
     * ### 判据为什么这样写
     *
     * - **「先解析 schema」是硬前提**：`VIEW.LIST` / `TRIGGER.LIST` / `FUNCTION.LIST` 的
     *   `schema` 要的是 schema 名而**不是库名**。拿库名去填会变成 `SET SCHEMA "<库名>"`
     *   → `Schema not found`，列表全空 —— 而请求**成功**、返回空列表，界面上看不出任何异常。
     * - **「不支持的一侧」只有跑 SQLite 才走得到**：H2 三样都有，这条路径永远测不到。
     *   首轮缺陷 #3 正是在这里暴露的：渲染层「任意一类出错 → 整个对象区换成一行错误」，
     *   于是 SQLite 上触发器不支持，**能正常列出的视图一起消失**。
     * - **等待条件必须判「每一类都落定」，不能判「loading 清空」** —— 见 [settledObjectKinds]。
     */
    @Test
    fun `OB1 object browser lists views and reports unsupported kinds`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)

            val db = browser.databases.first()
            // ⚠️ 必须**展开**库：对象分组是库节点的子行，库没展开时树上根本没有它们。
            // 展开同时会并发拉库级对象与表列表（见 `toggleDatabase`）。
            browser.toggleDatabase(db)
            awaitState("展开库", browser) { db in browser.expandedDatabases }

            // ⚠️ 等待条件不能只看 `loadingObjects` 清空 ——
            // `loadDatabaseObjects` 先把三类占位全撤掉、再由 `loadDatabaseObjectsFor` 逐类置上，
            // 中间存在一个「谁都不在 loading 里」的窗口，照那个条件等会在数据落地前就返回。
            // 真正的「落定」是**每一类**要么进了结果、要么进了错误。
            awaitState("库级对象全部落定", browser) {
                settledObjectKinds(browser, db).size == DatabaseBrowserState.DatabaseObjectKind.entries.size
            }
            awaitUi("界面已画出对象分组")

            val schemas = browser.schemasByDatabase[db].orEmpty()
            val objects = browser.objectsByDatabase[db].orEmpty()
            val errors = browser.objectLoadError
            println("RESULT OB1 [${target.name}] schema=$schemas objects=$objects errors=$errors")
            assertTrue(schemas.isNotEmpty(), "schema 名必须先解析出来（实际 $schemas），否则对象查询全落空")

            for (kind in DatabaseBrowserState.DatabaseObjectKind.entries) {
                val errKey = "$db::${kind.name}"
                val errorText = errors[errKey]
                if (kind.name in target.supportsDatabaseObjects) {
                    assertNull(errorText, "[${target.name}] 支持${Msg.text(kind.labelKey)}，不应报错：$errorText")
                    assertTrue(
                        kind in objects,
                        "[${target.name}] ${Msg.text(kind.labelKey)} 应出现在对象分组里（key 集合=${objects.keys}）",
                    )
                } else {
                    // 不支持的一侧：**要么**明确报错、**要么**给出空列表；两者都行，
                    // 但绝不能是「永远 loading」也不能是「把支持的种类一起弄没了」。
                    assertTrue(
                        errorText != null || objects[kind].isNullOrEmpty(),
                        "[${target.name}] ${Msg.text(kind.labelKey)} 不受支持，应报错或为空，实际 objects=${objects[kind]} errors=$errors",
                    )
                    println("  · ${Msg.text(kind.labelKey)} 不受支持 → error=${errorText ?: "(空列表)"}")
                }
            }

            // 视图两个方言都支持，且名字是我们自己建的 —— 这是最硬的一条锚点
            val views = objects[DatabaseBrowserState.DatabaseObjectKind.VIEW].orEmpty()
            assertTrue(
                views.any { it.equals("v_active_users", ignoreCase = true) },
                "[${target.name}] 视图列表应含 v_active_users，实际 $views",
            )

            // 界面上也要真的画出来。
            //
            // ⚠️ 树是 **LazyColumn**，而对象分组排在所有表**之后**（见渲染处的注释：
            // 「用户 90% 的时间在找表，把表放前面」）。树不高时那些行在折叠线以下 ——
            // 离屏的 lazy item **没有语义节点**，于是 `onNodeWithText(zh(StrKey.VIEWS))` 直接找不到。
            // 第一版就是这么误判成「界面没渲染」的。必须先滚到它。
            //
            // ⚠️ 也不能用 `onNode(hasScrollAction())`：整屏有多个可滚动节点，而 `onNode` 要求
            // **恰好**命中一个；而且**按序号取会漂** —— 库里有多少东西、行高多少，都会改变
            // 「树排在第几个」（SQLite 的库名是一整条文件路径，行数与 H2 不同）。
            //
            // 只按 `left < 400` 会挑中顶部导航条（横向）或详情面板里的小滚动区，所以先筛出
            // 「**纵向**且靠左」的一组候选。
            //
            // ⚠️ **不能只认一个**：更严格的判据（额外要求 LazyColumn 专有的 `ScrollToIndex`）
            // 在并发下**偶发**失配 —— 该动作是否出现在语义里取决于那一帧 lazy 布局有没有
            // 铺开 item，实测 SQLite + 高负载时整棵树都匹配不上。
            // 判据随环境抖动的解法不是加更多条件，而是**把候选逐个试一遍**。
            val candidates =
                onAllNodes(hasScrollAction())
                    .fetchSemanticsNodes()
                    .mapIndexedNotNull { i, node ->
                        if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                            node.boundsInRoot.left < 400f
                        ) {
                            i
                        } else {
                            null
                        }
                    }
            assertTrue(candidates.isNotEmpty(), "左侧应至少有一个纵向可滚动节点")

            val scrolled =
                candidates.any { idx ->
                    runCatching {
                        onAllNodes(hasScrollAction())[idx]
                            // ⚠️ `substring = true` 不能省：分组行的文本是 `"视图："`（带全角冒号），
                            // 而 `hasText` 默认要求**完全相等**，写成 "视图" 就永远匹配不上。
                            .performScrollToNode(hasText(zh(StrKey.VIEWS), substring = true))
                    }.isSuccess
                }
            assertTrue(scrolled, "应在左侧某个纵向滚动节点里找到「视图」分组（试了 ${candidates.size} 个）")
            onAllNodesWithText(zh(StrKey.VIEWS), substring = true).fetchSemanticsNodes().let {
                assertTrue(it.isNotEmpty(), "树上应出现「视图」分组")
            }

            // 不支持的种类必须在**界面上单独**说清楚，且不得连累能列出来的那几类。
            //
            // 这条是双方言矩阵才照得出来的：SQLite 上 TRIGGER / FUNCTION 必然失败，
            // 若错误被画成「整个对象区」的一行红字，视图就会跟着消失 —— 而视图恰恰
            // 是这几类里唯一真有内容的。修掉之前 SQLite 这条一直红。
            val text =
                onRoot().fetchSemanticsNode().let { node ->
                    val acc = StringBuilder()

                    fun walk(s: SemanticsNode) {
                        s.config.getOrNull(SemanticsProperties.Text)?.forEach { acc.append(it.text).append('|') }
                        s.children.forEach { walk(it) }
                    }
                    walk(node)
                    acc.toString()
                }
            for (kind in DatabaseBrowserState.DatabaseObjectKind.entries) {
                if (kind.name !in target.supportsDatabaseObjects) {
                    assertTrue(
                        text.contains(Msg.text(kind.labelKey)) && (errors["$db::${kind.name}"]?.let { text.contains(it.take(12)) } ?: true),
                        "[${target.name}] ${Msg.text(kind.labelKey)} 不受支持，界面上应有它自己的说明；文本=$text",
                    )
                }
            }
            assertTrue(
                text.contains("v_active_users") || text.contains("V_ACTIVE_USERS"),
                "[${target.name}] 视图名应出现在树上（不被失败的那几类连累）；文本=$text",
            )
            shot("ob1-object-browser-${target.name.lowercase()}.png")
        }

    /**
     * ## TC-OB2 — 表级对象：索引 / 外键
     *
     * | | |
     * |---|---|
     * | **前置** | 库已展开、表列表已加载 |
     * | **步骤** | ① 展开某张表的「索引 / 外键」节点 ② 等待加载完成 |
     * | **预期** | 索引列表含 `idx_orders_status`；外键列表含 `fk_orders_user` |
     * | **实际** | **H2 ✅ / SQLite ✅**。首轮红过：表级对象**首次展开必然为空**（缺陷 #2）——schema 还没解析完就发了查询，schema 传空串，请求**成功**、返回空列表、**不重试**，用户必须收起再展开一次 |
     *
     * ### 判据为什么这样写
     *
     * - **这两个挂表节点下而不是库节点下**：引擎侧 `IndexListRequest` / `ForeignKeyListRequest`
     *   要 `table_name`，跟库级对象一起拉就是「每张表一次往返」，几十张表的库直接爆炸。
     * - **外键名在 SQLite 上只能断言「列出来了」**：见 [target.exposesForeignKeyName] 的分支 ——
     *   `SQLiteDialect.listForeignKeys` 走 `PRAGMA foreign_key_list`，而该 PRAGMA
     *   **不暴露约束名**，方言只能拼一个 `fk_<表>_<序号>`。H2 / PG / MySQL 走
     *   `INFORMATION_SCHEMA`，名字是真的。
     */
    @Test
    fun `OB2 table objects list indexes and foreign keys`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)
            val db = browser.databases.first()
            browser.toggleDatabase(db)
            awaitState("展开库后表列表加载完成", browser) { db !in browser.loadingTables }
            awaitUi("界面已画出表节点")

            val table = browser.findTable(db, "ORDERS")
            browser.toggleTableObjects(db, table)
            val slot = "$db::$table"
            // ⚠️ 集合里存的是 **`(slot, kind)`**（slot = "库::表"），不是 `(库, kind)`。
            // 写成 `(db to it)` 时条件恒真 —— await 立刻通过，于是拿一个空 `objs` 去断言，
            // 第一版就是这么把「应用没写数据」误报成「界面没显示」的。
            awaitState("表级对象加载完成（$slot）", browser) {
                DatabaseBrowserState.TableObjectKind.entries.all { (slot to it) !in browser.loadingTableObjects }
            }
            awaitUi("界面已画出表级对象")

            val objs = browser.tableObjects[slot].orEmpty()
            // 直连引擎再发一次同一请求：把「应用没调」与「引擎调了但失败」分开 ——
            // 表级对象只在成功时才写进 `_tableObjects`，失败时界面上是空列表，
            // 与「真的没有索引」长得一模一样。
            val probe = probeIndexList(db, table)
            val diag =
                "slot=$slot schema=${browser.schemasByDatabase[db]} objs=$objs " +
                    "engineProbe=$probe"
            println("RESULT OB2 [${target.name}] $diag")

            val idx = objs[DatabaseBrowserState.TableObjectKind.INDEX].orEmpty()
            assertTrue(
                idx.any { it.contains("idx_orders_status", ignoreCase = true) },
                "[${target.name}] 索引列表应含 idx_orders_status，实际 $idx；$diag",
            )
            val fks = objs[DatabaseBrowserState.TableObjectKind.FOREIGN_KEY].orEmpty()
            if (target.exposesForeignKeyName) {
                assertTrue(
                    fks.any { it.contains("fk_orders_user", ignoreCase = true) },
                    "[${target.name}] 外键列表应含 fk_orders_user，实际 $fks；$diag",
                )
            } else {
                // SQLite 拿不到真名（PRAGMA foreign_key_list 不暴露约束名），只断言「列出来了」
                assertTrue(fks.isNotEmpty(), "[${target.name}] 外键列表不该为空，实际 $fks；$diag")
                println("  · SQLite 的外键名是方言拼出来的（PRAGMA 不给真名）：$fks")
            }

            shot("ob2-table-objects-${target.name.lowercase()}.png")
        }

    // ==================================================================
    // TC-DQ：过滤 / 排序 / 表内搜索
    // ==================================================================

    /**
     * ## TC-DQ1 / DQ2 / DQ3 — 过滤 / 排序 / 表内搜索
     *
     * | | |
     * |---|---|
     * | **前置** | `USERS` 预览已打开（250 行） |
     * | **步骤** | ① 填过滤 `id > 200` → 应用<br>② 清过滤，排序 `id DESC` → 应用<br>③ 清排序，搜索 `user_25`<br>④ 手工过滤与搜索同时存在 |
     * | **预期** | ① 总数 = 50（201..250），且**回到第 1 页**<br>② 首行 `id` = 250<br>③ 总数 = 2（`user_25` 与 `user_250`；`user_251..259` 在 1..250 里根本不存在）<br>④ 两者是 **AND** 关系 |
     * | **实际** | **H2 ✅ / SQLite ✅**。TC-DQ3 首轮红过（缺陷 #1：表内搜索**完全不可用**） |
     *
     * ### 判据为什么这样写
     *
     * - **必须下推给引擎**（`DATA.LIST` 带 `where` / `order_by`），不能前端本地筛 ——
     *   本地筛在服务端分页下只能看到当前页，**界面看着正常但结果是错的**。
     * - **判据用引擎回的总数**，不用「界面上出现了某一行」：后者在本地筛与真下推
     *   两种实现下**都可能成立**。
     * - **三个子场景之间必须先互相清干净**，否则过滤条件会被 AND 叠加。
     */
    @Test
    fun `DQ1 filter sort and search are pushed down to the engine`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)
            val db = browser.databases.first()
            browser.toggleDatabase(db)
            awaitState("展开库后表列表加载完成", browser) { db !in browser.loadingTables }

            val table = browser.findTable(db, "USERS")
            browser.openTab(browser.currentSchema(), table)
            val tab = browser.tabs.single()
            awaitState("USERS 预览加载完成", browser) { tab.rows.isNotEmpty() && !tab.loading }
            assertEquals(250L, tab.total, "未过滤时 USERS 应为 250 行")

            // ---- 过滤：id > 200 → 201..250 共 50 行
            browser.setTabFilter(tab, "id > 200")
            awaitState("过滤 id > 200 生效", browser) { !tab.loading && tab.total == 50L }
            assertEquals(1, tab.page, "改过滤条件必须回到第 1 页（否则可能停在一个已不存在的页上）")
            assertNull(tab.error, "过滤不应报错")
            assertTrue(
                onAllNodesWithText("user_201", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "过滤后应看到第 201 行起的数据（若失败，先确认是不是本地筛而不是下推）",
            )
            println("RESULT DQ1-filter [${target.name}] total=${tab.total} first=${tab.rows.first().cells}")

            // ---- 排序：清掉过滤后按 id DESC，首行应是 250
            browser.setTabFilter(tab, "")
            awaitState("清除过滤", browser) { !tab.loading && tab.total == 250L }
            browser.setTabOrderBy(tab, "id DESC")
            awaitState("排序 id DESC 生效", browser) { !tab.loading && tab.rows.isNotEmpty() }
            val firstId =
                tab.rows
                    .first()
                    .cellOf("id")
                    ?.toString()
            println("RESULT DQ2-sort [${target.name}] firstRow=${tab.rows.first().cells}")
            assertEquals(
                "250",
                firstId,
                "[${target.name}] 按 id DESC 排序后首行应为 250，实际 ${tab.rows.first().cells}",
            )

            // ---- 表内搜索：'user_25' → `user_25` 与 `user_250` 共 **2** 行
            //      （只有 1..250，`user_251..259` 根本不存在 —— 我第一版写 11 是数错了）
            browser.setTabOrderBy(tab, "")
            awaitState("清除排序", browser) { !tab.loading }
            browser.setTabSearch(tab, "user_25")
            awaitState("表内搜索 user_25 生效", browser) { !tab.loading && tab.total == 2L }
            assertNull(tab.error, "搜索不应报错")
            println("RESULT DQ3-search [${target.name}] total=${tab.total} rows=${tab.rows.map { it.cells }}")

            // 三个条件都在时必须 AND 叠加（不是互相覆盖）。
            // 上面已经清过过滤，所以这里**重新**放一个过滤再断言 ——
            // 期望值里带 `id > 200` 却在过滤已空时断言，是第一版写错的地方。
            browser.setTabFilter(tab, "id > 100")
            awaitState("过滤与搜索同时存在", browser) { !tab.loading }
            assertEquals(
                "id > 100 AND (${tab.columns.joinToString(" OR ") { c -> "CAST(${c.key} AS VARCHAR) LIKE '%user_25%'" }})",
                tab.effectiveWhere(),
                "手工过滤与表内搜索应是 AND 关系，且搜索要铺到每一列上",
            )

            shot("dq-filter-sort-search-${target.name.lowercase()}.png")
        }

    // ==================================================================
    // TC-MS / TC-TX / TC-RO：多语句 / 事务 / 只读
    // ==================================================================

    /**
     * ## TC-MS1 — 多语句执行
     *
     * | | |
     * |---|---|
     * | **前置** | SQL 工作台 |
     * | **步骤** | 打开「多语句」，执行 `CREATE TABLE …; INSERT …; INSERT …` |
     * | **预期** | 无报错，且**探针表里恰好 2 行** |
     * | **实际** | **H2 ✅ / SQLite ✅**（首轮即过） |
     *
     * 判据刻意用「建表 + 插数据」两条语句：开多语句时两条都执行；只跑第一条的话
     * 表压根不存在。**只用单条 `CREATE` 区分不出这两种情况** —— 判据必须落在
     * 跨语句的副作用上。
     */
    @Test
    fun `MS1 multi statement runs every statement in the script`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)
            browser.selectPane(BrowserPane.SQL)

            val sheet = browser.currentSqlSheet()!!
            sheet.multiStatement = true
            sheet.editor.setText(
                "CREATE TABLE multi_stmt_probe (id INT PRIMARY KEY); " +
                    "INSERT INTO multi_stmt_probe VALUES (1); " +
                    "INSERT INTO multi_stmt_probe VALUES (2)",
            )
            waitForIdle()

            browser.executeSql()
            awaitState("多语句执行结束", browser) { !sheet.running }

            println("RESULT MS1 [${target.name}] error=${sheet.error} affected=${sheet.affectedRows} rows=${sheet.rows.size}")
            assertNull(sheet.error, "[${target.name}] 多语句执行不应报错，实际 ${sheet.error}")

            val probe = queryInt("SELECT COUNT(*) FROM multi_stmt_probe")
            assertEquals(2, probe, "[${target.name}] 多语句应把两条 INSERT 都执行掉，实际 count=$probe")

            shot("ms-multi-statement-${target.name.lowercase()}.png")
        }

    /**
     * ## TC-TX1 / TX2 / TX3 — 事务（BEGIN / ROLLBACK / COMMIT）
     *
     * | | |
     * |---|---|
     * | **前置** | SQL 工作台 |
     * | **步骤** | ① 点「事务」BEGIN<br>② 在事务里 `INSERT`<br>③ 用**另一条独立 JDBC 连接**查<br>④ 点「回滚」，再查<br>⑤ 再 BEGIN → INSERT → 「提交」，再查 |
     * | **预期** | ① `transactionSessionId` **非空**（拿不到等于事务没开）<br>②③ 独立连接**看不到**这行<br>④ 回滚后**仍看不到**<br>⑤ 提交后独立连接**看得到** |
     * | **实际** | **H2 ✅ / SQLite ✅**（首轮即过） |
     *
     * ### 判据为什么这样写
     *
     * - **只断言「按钮能点」是不够的**：事务没开、连接没钉住，界面一样显示「已开启」。
     *   本轮唯一能真正验到**数据可见性**的用例，判据全部落在「另一条独立连接看得到吗」。
     * - **SQLite 另有一层**：文件库在他人持写事务时可能直接 `SQLITE_BUSY`。
     *   「被锁」与「读到旧值」一样都证明**未提交的数据对他人不可见**，语义等价 ——
     *   但必须显式写出来，否则换个平台它就变成一个查不到的错误。见 [countElsewhere]。
     */
    @Test
    fun `TX1 rollback discards writes and commit keeps them`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)
            browser.selectPane(BrowserPane.SQL)
            val sheet = browser.currentSqlSheet()!!

            // ---- BEGIN：必须拿到 session id，拿不到等于事务没开
            val beginErr = runBlocking { browser.beginTransaction(sheet) }
            assertNull(beginErr, "[${target.name}] 开启事务不应失败，实际 $beginErr")
            assertNotNull(sheet.transactionSessionId, "引擎必须回 session id，否则后续请求找不到被钉住的连接")
            println("RESULT TX1 [${target.name}] sessionId=${sheet.transactionSessionId}")

            // ---- 在事务里插一行
            sheet.editor.setText("INSERT INTO users VALUES (9001, 'tx_user', 'tx@example.com')")
            browser.executeSql()
            awaitState("事务内 INSERT 执行完", browser) { !sheet.running }
            assertNull(sheet.error, "[${target.name}] 事务内 INSERT 不应报错，实际 ${sheet.error}")

            // 独立连接此刻**必须看不到**
            assertTrue(
                !visibleElsewhere("id = 9001"),
                "[${target.name}] 未提交的数据不该被另一条连接看到 —— 看到了说明事务没有真正隔离",
            )

            // ---- ROLLBACK
            val rollbackErr = runBlocking { browser.rollbackTransaction(sheet) }
            assertNull(rollbackErr, "[${target.name}] 回滚不应失败，实际 $rollbackErr")
            assertTrue(!visibleElsewhere("id = 9001"), "[${target.name}] 回滚后数据必须消失")

            // ---- 再来一次，这次提交
            runBlocking { browser.beginTransaction(sheet) }
            sheet.editor.setText("INSERT INTO users VALUES (9002, 'tx_user2', 'tx2@example.com')")
            browser.executeSql()
            awaitState("第二次事务内 INSERT 执行完", browser) { !sheet.running }
            val commitErr = runBlocking { browser.commitTransaction(sheet) }
            assertNull(commitErr, "[${target.name}] 提交不应失败，实际 $commitErr")

            val afterCommit = countElsewhere("id = 9002")
            assertNotNull(afterCommit, "[${target.name}] 提交后独立连接必须能查到（SQLite 此刻不该再被锁）")
            assertEquals(1, afterCommit, "[${target.name}] 提交后数据必须对其他连接可见")
            println("RESULT TX3 [${target.name}] 提交后外部可见行数=$afterCommit")

            shot("tx-transaction-${target.name.lowercase()}.png")
        }

    /**
     * ## TC-RO1 / RO2 — 只读模式
     *
     * | | |
     * |---|---|
     * | **前置** | 表预览已打开 |
     * | **步骤** | ① 关闭只读，试 `DROP TABLE`<br>② 打开只读，试 `DROP` / `UPDATE` / `SELECT 1`<br>③ **回头查库**确认表还在<br>④ 关掉只读 |
     * | **预期** | ① 不拦<br>② `DROP` / `UPDATE` 被拦，`SELECT` 放行<br>③ 表还在、行数不变 —— 证明**根本没发出去**，而不是发出去被引擎拒<br>④ 不再拦 |
     * | **实际** | **H2 ✅ / SQLite ✅**（首轮即过） |
     *
     * 只读必须**在发出去之前**拦（[DatabaseBrowserState.rejectIfReadOnly]），代价才是零 ——
     * 引擎那边什么都没发生。所以除了断言返回值，还要**回头查库确认表还在**
     * （区分「没发」与「发了被引擎拒」）。
     */
    @Test
    fun `RO1 read only blocks writes before they reach the engine`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val browser = DatabaseBrowserState(engine, newScope())
            render(browser)
            awaitSchema(browser)
            val db = browser.databases.first()
            browser.toggleDatabase(db)
            awaitState("展开库后表列表加载完成", browser) { db !in browser.loadingTables }
            val table = browser.findTable(db, "USERS")

            // 关着时：不拦任何东西
            assertFalse(browser.readOnly, "只读默认应是关的")
            assertNull(browser.rejectIfReadOnly("DROP TABLE $table"), "只读关闭时不应拦")

            // 打开后：写操作被拦，读操作放行
            browser.readOnly = true
            assertNotNull(browser.rejectIfReadOnly("DROP TABLE $table"), "只读开启后 DROP 必须被拦")
            assertNotNull(
                browser.rejectIfReadOnly("UPDATE $table SET username = 'x'"),
                "UPDATE 必须被拦",
            )
            assertNull(
                browser.rejectIfReadOnly("SELECT 1"),
                "只读不该拦 SELECT，实际 ${browser.rejectIfReadOnly("SELECT 1")}",
            )

            // 拦完回头查库：表必须还在（证明没发出去）
            assertTrue(tableExists(table), "只读拦下写操作后，$table 表必须还在")
            assertEquals(250, countElsewhere("1 = 1") ?: -1, "表里应仍有 250 行")

            // 界面上那个开关也真实存在（它才是用户能点的入口）——
            // 先切到 SQL 工作台，否则那个工具栏压根没渲染。
            browser.selectPane(BrowserPane.SQL)
            awaitUi("切到 SQL 工作台，只读开关已渲染")
            // ⚠️ 必须 waitForIdle：切 pane 后子树才刚进入组合，此刻取快照去读
            // `onNodeWithTag` 会撞上「快照创建后才写入的状态」。
            waitForIdle()
            onNodeWithTag(SQL_READONLY_CHIP_TAG).assertExists()

            // 再关掉
            browser.readOnly = false
            assertNull(browser.rejectIfReadOnly("DROP TABLE $table"), "关掉只读后不应再拦")

            shot("ro-readonly-${target.name.lowercase()}.png")
        }

    // ==================================================================
    // 辅助
    // ==================================================================

    private fun ComposeUiTest.render(browser: DatabaseBrowserState) {
        val sheets =
            listOf(
                SheetDescriptor(conn, browser, ConnectionStatus(ConnectionState.CONNECTED, target.name)),
            )
        setContent {
            ProvideTestStrings {
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
    }

    /**
     * 等异步完成 —— 轮询**状态机字段**而不是语义树。
     *
     * 语义树要等重组，重组由测试调度器驱动；状态机是普通 Kotlin 对象，读它不经过调度器，
     * 因此永远不会卡在重组上。「引擎没返回」与「返回了但没画出来」必须能分开判定。
     */
    private fun ComposeUiTest.awaitState(
        what: String,
        browser: DatabaseBrowserState,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        println("▶ $what …")
        while (!condition()) {
            if (System.currentTimeMillis() - start > engineAwaitMs) {
                throw AssertionError(
                    "等待超时：$what（${System.currentTimeMillis() - start}ms）[${target.name}]\n" +
                        "  databases=${browser.databases} loadingDatabases=${browser.loadingDatabases}\n" +
                        "  loadingObjects=${browser.loadingObjects} objects=${browser.objectsByDatabase}\n" +
                        "  loadingTableObjects=${browser.loadingTableObjects} tableObjects=${browser.tableObjects}\n" +
                        "  tabs=${browser.tabs.map {
                            "${it.tableName}:rows=${it.rows.size},total=${it.total},loading=${it.loading},error=${it.error}"
                        }}\n" +
                        "  sqlSheets=${browser.sqlSheets.map {
                            "running=${it.running},error=${it.error},tx=${it.transactionSessionId}"
                        }}",
                )
            }
            Thread.sleep(5)
        }
        println("✔ $what  (${System.currentTimeMillis() - start}ms)")
    }

    private fun ComposeUiTest.awaitSchema(browser: DatabaseBrowserState) {
        awaitState("schema 树加载出库列表", browser) { !browser.loadingDatabases && browser.databases.isNotEmpty() }
        awaitUi("界面已画出库节点")
    }

    /**
     * 状态落定后把界面推到跟得上的一帧。
     *
     * 必须用 `advanceTimeByFrame` 而不是 `waitForIdle`：状态是在引擎挂起点之后由 IO 线程
     * resume 时写进 `mutableStateOf` 的，那一帧当时**还没被登记为待处理重组**，
     * `waitForIdle` 会认为无事可做直接返回。连推两帧 —— 状态写入与依赖它的第二次重组
     * 未必在同一个 tick。
     */
    private fun ComposeUiTest.awaitUi(what: String) {
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        println("✔ $what")
    }

    private fun ComposeUiTest.shot(name: String) {
        val bitmap = onRoot().captureToImage().asSkiaBitmap()
        val encoded = Image.makeFromBitmap(bitmap).encodeToData()
        requireNotNull(encoded)
        File(shotDir, name).writeBytes(encoded.bytes)
        println("SHOT ${File(shotDir, name).absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    /** 方言之间的标识符大小写不同（H2 全大写、SQLite 原样），查表名必须忽略大小写。 */
    private fun DatabaseBrowserState.findTable(
        db: String,
        name: String,
    ): String {
        val hit = tablesByDatabase[db]?.firstOrNull { it.equals(name, ignoreCase = true) }
        return hit ?: throw AssertionError("表 $name 不在 ${tablesByDatabase[db]}（库 $db）")
    }

    /**
     * 哪些库级对象种类已经**落定**（进了结果或进了错误），且不在 loading 里。
     *
     * 判据不能是「`loadingObjects` 清空了」—— `loadDatabaseObjects` 会先撤占位再逐类置上，
     * 中间有一个三类都不在 loading 的窗口，照那个条件等会在数据落地前就返回断言。
     */
    private fun settledObjectKinds(
        browser: DatabaseBrowserState,
        db: String,
    ): Set<DatabaseBrowserState.DatabaseObjectKind> {
        val results = browser.objectsByDatabase[db] ?: return emptySet()
        return DatabaseBrowserState.DatabaseObjectKind.entries
            .filter { kind ->
                (db to kind) !in browser.loadingObjects &&
                    (kind in results || "$db::${kind.name}" in browser.objectLoadError)
            }.toSet()
    }

    /** 同上，列名（H2 的结果集元数据是大写）。 */
    private fun TableRow.cellOf(name: String): Any? = cells.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** 走一条**独立于引擎**的直连取整数值。 */
    private fun queryInt(sql: String): Int =
        target.openDirect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /**
     * 独立连接数出来的行数；**查不到时返回 null**（被锁 / 连不上）。
     *
     * 「null = 不可见」是刻意的：SQLite 文件库在他人持写事务时会 `SQLITE_BUSY`，
     * 那同样证明未提交的数据对他人不可见。
     */
    private fun countElsewhere(where: String): Int? =
        runCatching {
            target.openDirect().use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM users WHERE $where").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }.getOrNull()

    private fun visibleElsewhere(where: String): Boolean = (countElsewhere(where) ?: 0) > 0

    /**
     * 表还在不在。
     *
     * **刻意不用 `INFORMATION_SCHEMA`** —— 那是 H2 专有的元数据视图，SQLite 上直接语法错。
     * 改成「直接查它，查得动就说明表还在」：这本来就是我们真正要回答的问题，
     * 而且两个方言通用。
     */
    private fun tableExists(name: String): Boolean =
        runCatching {
            target.openDirect().use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM $name").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
            true
        }.getOrDefault(false)

    /**
     * 直连引擎发一次 `INDEX.LIST`，只为诊断用。
     *
     * 为什么需要它：表级对象**只在响应成功时**才写进 `_tableObjects`，失败时界面上是
     * 空列表 —— 与「这张表真的没有索引」**长得一模一样**。没有这次探针，
     * 「应用没发请求」「发了但失败」「确实没有」三者是同一个现象。
     */
    private fun probeIndexList(
        database: String,
        table: String,
    ): String =
        runCatching {
            runBlocking {
                val schema =
                    engine
                        .invoke(
                            com.langbiantianya.grpc.connectionConfig {
                                driver = conn.dialect.engineDriverName
                                jdbcUrl = target.jdbcUrl
                                user = conn.username
                                password = conn.password
                                this.database = database
                            },
                        ) {
                            category = com.langbiantianya.grpc.Category.SCHEMA
                            action = com.langbiantianya.grpc.Action.LIST
                            schemaRequest =
                                com.langbiantianya.grpc.schemaRequest {
                                    list =
                                        com.langbiantianya.grpc.schemaListRequest {
                                            level = "schema"
                                            this.database = database
                                        }
                                }
                        }?.takeIf { it.success }
                        ?.schema
                        ?.list
                        ?.itemsList
                        .orEmpty()
                        .firstOrNull() ?: ""

                val resp =
                    engine.invoke(
                        com.langbiantianya.grpc.connectionConfig {
                            driver = conn.dialect.engineDriverName
                            jdbcUrl = target.jdbcUrl
                            user = conn.username
                            password = conn.password
                            this.database = database
                        },
                    ) {
                        category = com.langbiantianya.grpc.Category.INDEX
                        action = com.langbiantianya.grpc.Action.LIST
                        indexRequest =
                            com.langbiantianya.grpc.indexRequest {
                                list =
                                    com.langbiantianya.grpc.indexListRequest {
                                        tableName = table
                                        this.schema = schema
                                    }
                            }
                    }
                "schema='$schema' success=${resp.success} error='${resp.error}' items=${resp.index.list.itemsList.map { it.name }}"
            }
        }.getOrElse { "探针本身抛了：${it::class.simpleName}: ${it.message}" }
}

/**
 * 供 H2 的 `CREATE TRIGGER … CALL` / `CREATE ALIAS` 反射调用的静态方法宿主。
 *
 * 必须是**顶层类**且方法为 `static`（Kotlin 里写 `@JvmStatic` 的 companion 方法，
 * 生成的静态转发才能被 H2 通过反射找到）。
 */
class WalkthroughSupport {
    companion object {
        /** 函数别名宿主：H2 只需要它存在且签名对。 */
        @JvmStatic
        fun doubleIt(v: Int): Int = v * 2

        /** 触发器宿主：H2 在删行前回调它，什么都不做。 */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun fire(
            conn: java.sql.Connection,
            oldRow: Array<Any?>?,
            newRow: Array<Any?>?,
        ) {
            // 刻意空实现：我们要验的是「应用能不能把触发器显示出来」，不是触发器的行为
        }
    }
}
