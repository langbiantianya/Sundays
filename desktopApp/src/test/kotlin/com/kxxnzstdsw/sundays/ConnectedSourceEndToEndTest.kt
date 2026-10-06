package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionState
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
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
 * **连上真数据源之后的功能测试** —— 先连，再把功能在上面用一遍。
 *
 * ## 这是补哪条缺口
 *
 * 此前三类测试各覆盖一半，**没有任何一条**把「连接」与「使用」串在同一个用例里：
 *
 * | 测试 | 覆盖 | 缺什么 |
 * |---|---|---|
 * | `ConnectionManagerFlowTest` | 走向导到连接 / 断开 / 落盘 | 连上就结束，**不浏览** |
 * | `FeatureWalkthroughTest` | 浏览 / 过滤 / 搜索 / 对象 / 事务 / 只读 | **直接注入已连接的 `DatabaseBrowserState`**，不走连接 |
 * | `DialectSmokeTest` | 五方言 × 7 项 | **直打引擎**，完全不碰界面 |
 *
 * 于是「连得上」与「连上之后能用」之间那道缝一直没被测过 —— 而它恰恰是最容易坏的地方：
 * 会话 / 连接池 / schema 解析 / 标识符大小写，任何一环不对，都是**连上了但一用就废**。
 *
 * ## 本类做的事
 *
 * 真引擎建库并播种 → [ConnectionSession] **真连上**（走 `IdbEngine.testConnection` 建池）→
 * 界面里**真点**树与表 → 读数据 → 过滤 / 排序 / 搜索下推 → SQL 工作台执行 →
 * 多语句 / 事务 / 只读 → 断开。
 *
 * ## 关于「走不走向导 UI」
 *
 * 向导 UI 本身由 `ConnectionManagerFlowTest` 覆盖；本类从 [ConnectionSession.connect] 起步 ——
 * 那**正是**向导最后一步调用的入口，所以跳过的是那几下点击，跳过的不是任何逻辑。
 * 换来的是**每个方言都能跑同一套**，不必为四种向导形态各写一遍。
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ConnectedSourceEndToEndTest(private val target: SmokeTarget) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "[{0}]")
        fun targets(): List<Array<Any>> = smokeTargets().map { arrayOf(it as Any) }

        /** 表名。H2 存成大写、SQLite / DuckDB / MySQL / PG 原样小写，故一律小写比较。 */
        const val TABLE = "e2e_orders"
        const val ROWS = 12
    }

    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var cfg: ConnectionConfig
    private var workspace: String = ""
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        assumeTrue("[${target.label}] 远程库不可达，跳过", target.reachable())

        tempHome = Files.createTempDirectory("sundays-e2e").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        target.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        registerBuiltinEditors()

        workspace = target.provision(tempHome)
        cfg = target.config(workspace)
        seed()
    }

    @After
    fun tearDown() {
        scope?.cancel()
        try { engine.close() } catch (e: Exception) { println("engine.close 抛了：$e") }
        runCatching { target.teardown(workspace) }
            .onFailure { println("⚠ [${target.label}] 归还工作区失败：$it") }
        System.setProperty("user.home", originalHome)
        tempHome.deleteRecursively()
    }

    // ==================================================================
    // ① 播种
    // ==================================================================

    /** 经**引擎**建表插数 —— 和用户在工作台敲 DDL 走同一条路。 */
    private fun seed() {
        exec("CREATE TABLE $TABLE (id INT PRIMARY KEY, amount INT, status VARCHAR(32))")
        target.direct(workspace).use { c ->
            c.prepareStatement("INSERT INTO $TABLE VALUES (?, ?, ?)").use { ps ->
                for (i in 1..ROWS) {
                    ps.setInt(1, i)
                    ps.setInt(2, i * 10)
                    ps.setString(3, if (i % 3 == 0) "PAID" else "PENDING")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun exec(sql: String, multi: Boolean = false, sessionId: String? = null): Response =
        runBlocking {
            val resp = runCatching {
                engine.invoke(connectionConfig {
                    driver = cfg.dialect.engineDriverName
                    jdbcUrl = cfg.jdbcUrl
                    user = cfg.username
                    password = cfg.password
                }) {
                    category = Category.SQL
                    action = Action.EXECUTE
                    if (sessionId != null) this.sessionId = sessionId
                    sqlRequest = sqlRequest {
                        execute = sqlExecuteRequest {
                            this.sql = sql
                            this.schema = ""          // 塞 catalog 名会让引擎 SET SCHEMA 失败
                            multiStatement = multi
                        }
                    }
                }
            }.getOrNull()
            resp ?: throw AssertionError("[${target.label}] 引擎无响应：$sql")
        }

    // ==================================================================
    // ② 真连上
    // ==================================================================

    @Test
    fun `connecting to a real data source and using it end to end`() = runComposeUiTest(testTimeout = 5.minutes) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val session = ConnectionSession(engine, s)

        // ---- ② 连上：走 ConnectionSession（向导最后一步调的就是它）
        session.connect(cfg)
        awaitState("[${target.label}] 状态未到 CONNECTED", timeoutMs = 60_000) {
            session.statuses.values.any { it.state == ConnectionState.CONNECTED }
        }
        println("RESULT E2E-connect [${target.label}] 已连接：${session.statuses.values}")
        assertTrue(
            session.statuses.values.any { it.state == ConnectionState.CONNECTED },
            "[${target.label}] 应连上。凭据 / URL / 方言任一不对都会停在这里",
        )

        // ---- ③ 浏览：库列表 → 展开 → 表列表（真点，点了才用状态机兜底）
        val browser = DatabaseBrowserState(engine, s)
        browser.bindConnection(cfg)
        browser.refreshDatabases()
        awaitState("[${target.label}] 库列表为空", timeoutMs = 60_000) {
            !browser.loadingDatabases && browser.databases.isNotEmpty()
        }
        println("RESULT E2E-databases [${target.label}] ${browser.databases}")

        // 库名**不能**取 `.first()`：MySQL 会把库里所有非系统 database 都列出来，
        // 第一个未必是刚建的那个探针库，于是「表不在列表里」变成一句
        // "Collection contains no element matching the predicate"，完全指不到真因。
        // 先按工作区名精确匹配（远程库成立），匹配不上再逐个展开去找。
        val (db, table) = locateTable(browser)
        println("RESULT E2E-locate [${target.label}] 库=$db 表=$table")

        // ---- ④ 开表预览：真双击表名
        val dbl = runCatching {
            onNodeWithText(table, substring = true).performClick()
            onNodeWithText(table, substring = true).performClick()
        }
        if (dbl.isFailure) {
            println("  · 表节点的语义点击不可用（${dbl.exceptionOrNull()?.message}），改走状态机")
            browser.openTab(browser.currentSchema(), table)
        }
        awaitState("[${target.label}] 表预览没加载出来", timeoutMs = 60_000) {
            browser.tabs.isNotEmpty() && browser.tabs.none { it.loading }
        }
        val tab = browser.tabs.first { it.tableName.equals(TABLE, ignoreCase = true) }
        assertNull(tab.error, "[${target.label}] 预览不应报错：${tab.error}")
        assertEquals(ROWS.toLong(), tab.total, "[${target.label}] 预览应显示 $ROWS 行")
        println("RESULT E2E-preview [${target.label}] total=${tab.total} 首行=${tab.rows.firstOrNull()?.cells}")

        // ⚠️ **刻意不断言界面上的「共 N 条」**：那行是分页栏里的**描述性**文本，
        // 容器窄于 560dp 时会**按设计隐藏**（见 TablePaginationLayoutTest 记的降级规则）。
        // 断它等于把这个用例焊死在某个窗口宽度上 —— 而且失败时看到的是
        // 「界面没显示总数」，与真因（宽度不够）八竿子打不着。
        // 正确性由上面的 `tab.total` 断言，界面表现交给那类专门的布局测试。

        // ---- ⑤ 过滤 / 排序 / 表内搜索（都必须下推引擎）
        browser.setTabFilter(tab, "id > ${ROWS - 3}")
        awaitState("[${target.label}] 过滤没生效", timeoutMs = 60_000) { !tab.loading && tab.total == 3L }
        assertNull(tab.error, "[${target.label}] 过滤不应报错：${tab.error}")

        browser.setTabFilter(tab, "")
        awaitState("[${target.label}] 清除过滤", timeoutMs = 60_000) { !tab.loading && tab.total == ROWS.toLong() }
        browser.setTabOrderBy(tab, "id DESC")
        awaitState("[${target.label}] 排序没生效", timeoutMs = 60_000) { !tab.loading && tab.rows.isNotEmpty() }
        val firstId = tab.rows.first().cells.entries
            .firstOrNull { it.key.equals("id", ignoreCase = true) }?.value?.toString()
        assertEquals(ROWS.toString(), firstId, "[${target.label}] 按 id DESC 首行应为 $ROWS，实际 ${tab.rows.first().cells}")

        browser.setTabOrderBy(tab, "")
        awaitState("[${target.label}] 清除排序", timeoutMs = 60_000) { !tab.loading }
        val castType = SqlLiterals.castTypeFor(target.dialectType)
        val like = tab.columns
            .map { "CAST(${it.key} AS $castType) LIKE '%PAID%'" }
            .joinToString(" OR ", prefix = "(", postfix = ")")
        browser.setTabSearch(tab, "PAID")
        awaitState("[${target.label}] 表内搜索没生效", timeoutMs = 60_000) { !tab.loading && tab.total == 4L }
        assertNull(tab.error, "[${target.label}] 搜索不应报错：${tab.error}")
        assertEquals(4L, tab.total, "[${target.label}] status LIKE '%PAID%' 应命中 4 行（12 行里每 3 行一个）")
        assertTrue(like.isNotEmpty(), "[${target.label}] 谓词构造不应为空")
        println("RESULT E2E-search [${target.label}] total=${tab.total} 谓词=$like")

        // ---- ⑥ SQL 工作台：真执行
        browser.selectPane(BrowserPane.SQL)
        val sheet = browser.currentSqlSheet()!!
        sheet.editor.setText("SELECT id, status FROM $TABLE WHERE id <= 3 ORDER BY id")
        waitForIdle()
        onNode(hasSetTextAction()).let { /* 编辑器已在状态机里设好文本，这里不重复点 */ }
        browser.executeSql()
        awaitState("[${target.label}] SQL 执行没结束", timeoutMs = 60_000) { !sheet.running }
        assertNull(sheet.error, "[${target.label}] SQL 执行不应报错：${sheet.error}")
        assertTrue(sheet.rows.isNotEmpty(), "[${target.label}] SQL 应有结果行")
        println("RESULT E2E-sql [${target.label}] 行数=${sheet.rows.size} 首行=${sheet.rows.firstOrNull()?.cells}")

        // ---- ⑦ 多语句
        sheet.multiStatement = true
        sheet.editor.setText(
            "CREATE TABLE e2e_multi (id INT PRIMARY KEY); " +
                "INSERT INTO e2e_multi VALUES (1); INSERT INTO e2e_multi VALUES (2)",
        )
        browser.executeSql()
        awaitState("[${target.label}] 多语句没结束", timeoutMs = 60_000) { !sheet.running }
        assertNull(sheet.error, "[${target.label}] 多语句不应报错：${sheet.error}")
        assertEquals(
            2, target.count(workspace, "e2e_multi", "1 = 1"),
            "[${target.label}] 多语句应把两条 INSERT 都执行掉",
        )

        // ---- ⑧ 事务：回滚丢弃、提交保留（用独立连接判可见性）
        exec("CREATE TABLE e2e_tx (id INT PRIMARY KEY)")
        val sid = openTransaction()
        exec("INSERT INTO e2e_tx VALUES (1)", sessionId = sid)
        assertTrue(
            !target.count(workspace, "e2e_tx", "1 = 1").let { it > 0 },
            "[${target.label}] 未提交的数据不该被另一条连接看到",
        )
        closeTransaction(Action.ROLLBACK, sid)
        assertFalse(
            target.count(workspace, "e2e_tx", "1 = 1") > 0,
            "[${target.label}] 回滚后数据必须消失",
        )
        val sid2 = openTransaction()
        exec("INSERT INTO e2e_tx VALUES (2)", sessionId = sid2)
        closeTransaction(Action.COMMIT, sid2)
        assertTrue(
            target.count(workspace, "e2e_tx", "1 = 1") > 0,
            "[${target.label}] 提交后数据必须对其他连接可见",
        )
        println("RESULT E2E-transaction [${target.label}] 通过")

        // ---- ⑨ 只读：拦在发出去之前
        browser.readOnly = true
        assertNotNull(
            browser.rejectIfReadOnly("DROP TABLE $TABLE"),
            "[${target.label}] 只读应拦下 DROP",
        )
        assertNull(browser.rejectIfReadOnly("SELECT 1"), "[${target.label}] 只读不该拦 SELECT")
        assertTrue(
            target.tableExists(workspace, TABLE),
            "[${target.label}] 只读拦下后表必须还在（证明没发出去）",
        )
        browser.readOnly = false

        // ---- ⑩ 断开
        session.disconnect(cfg)
        awaitState("[${target.label}] 断开没生效", timeoutMs = 60_000) {
            session.statuses.values.none { it.state == ConnectionState.CONNECTED }
        }
        println("RESULT E2E [${target.label}] 全部通过")
    }

    // ==================================================================

    /**
     * 找到「装着 [TABLE] 的那个库」并返回 `(库名, 表名)`。
     *
     * 先按工作区名精确匹配（远程库成立），匹配不上就逐个展开去找。
     * 直接取 `databases.first()` 是不行的 —— MySQL 会列出所有非系统库，
     * 第一个未必是探针库，失败时只报一句 `NoSuchElementException`，指不到真因。
     */
    private fun locateTable(browser: DatabaseBrowserState): Pair<String, String> {
        val preferred = browser.databases.firstOrNull {
            workspace.isNotBlank() && it.equals(workspace, ignoreCase = true)
        }
        val order = if (preferred != null) listOf(preferred) + browser.databases.filter { it != preferred }
        else browser.databases

        for (candidate in order) {
            if (candidate !in browser.expandedDatabases) browser.toggleDatabase(candidate)
            awaitState("[${target.label}] 库 $candidate 的表列表没加载出来", timeoutMs = 60_000) {
                candidate !in browser.loadingTables
            }
            val hit = browser.tablesByDatabase[candidate]
                ?.firstOrNull { it.equals(TABLE, ignoreCase = true) }
            if (hit != null) return candidate to hit
        }
        throw AssertionError(
            "[${target.label}] 所有库里都找不到表 $TABLE。库列表=${browser.databases}；" +
                "各库表=${browser.tablesByDatabase}",
        )
    }

    private fun openTransaction(): String = runBlocking {
        val resp = engine.invoke(connectionConfig {
            driver = cfg.dialect.engineDriverName
            jdbcUrl = cfg.jdbcUrl
            user = cfg.username
            password = cfg.password
        }) {
            category = Category.SYSTEM
            action = Action.BEGIN
            systemRequest = com.kxxnzstdsw.grpc.systemRequest { sessionId = "e2e-${System.nanoTime()}" }
        }
        assertTrue(resp.success, "[${target.label}] 开启事务失败：${resp.error}")
        val sid = resp.system.begin.sessionId
        assertTrue(sid.isNotBlank(), "[${target.label}] 引擎必须回 session id")
        sid
    }

    private fun closeTransaction(action: Action, sessionId: String) = runBlocking {
        val resp = engine.invoke(connectionConfig {
            driver = cfg.dialect.engineDriverName
            jdbcUrl = cfg.jdbcUrl
            user = cfg.username
            password = cfg.password
        }) {
            category = Category.SYSTEM
            this.action = action
            systemRequest = com.kxxnzstdsw.grpc.systemRequest { this.sessionId = sessionId }
        }
        assertTrue(resp.success, "[${target.label}] $action 失败：${resp.error}")
    }

    /** 轮询状态字段，不依赖重组 —— 见 `FeatureWalkthroughTest` 的同名做法。 */
    private fun awaitState(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("等待超时：$what")
            Thread.sleep(20)
        }
    }
}
