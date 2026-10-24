/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.AnalysisAccessMode
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.PullOutcome
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分析会话状态机的测试 —— 真 DuckDB 工作区、真引擎。
 *
 * ## 这一轮最要紧的一条：两条会话的只读方向是**相反**的
 *
 * | 会话 | `read_only` | 弄反了会怎样 |
 * |---|---|---|
 * | 远端源 | **开** | 分析模式成了绕过 L4 闸门的后门 |
 * | 本地工作区 | **关** | 工作区里连 `CREATE TABLE` 都不能做 —— M4 的验收直接不通过 |
 *
 * 这两条一正一反，任何一边写反了都**不会立刻报错**：工作区带只读只是「建表失败」，
 * 远端漏只读只是「还能写」。所以必须各有断言钉住。
 */
class AnalysisSessionTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-session").toFile()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    /** 工作区落在临时目录里，不碰用户真实的 `~/.config/sundays`。 */
    private fun newSession(puller: AnalysisPuller = EngineAnalysisPuller(engine)): AnalysisSession =
        AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
            puller = puller,
        )

    /**
     * 一个**立刻返回**的假拉取器。
     *
     * 用来测会话的**终态映射**（完成 / 取消 / 失败）——
     * 真实现要连远端、还要起导出子进程，那三条路径就没法**确定性地**各测一遍。
     * 而终态映射恰恰是状态机里最容易写反的地方：
     * 把「取消」当成「失败」，界面上就会显示「拉取失败：cancelled」，
     * 用户以为出了错，其实只是自己按了取消。
     */
    private class FakePuller(
        private val body: suspend () -> PullResult,
    ) : AnalysisPuller {
        override suspend fun pull(
            requestId: String,
            source: ConnectionConfig,
            workspace: ConnectionConfig,
            workspacePath: Path,
            namespace: String,
            table: String,
            targetSchema: String,
            targetTable: String,
            accessMode: AnalysisAccessMode,
            onProgress: suspend (com.langbiantianya.sundays.analysis.PullProgress) -> Unit,
        ): PullResult = body()
    }

    /** 一条远端源的基础配置（**不带**只读 —— 只读由会话统一加）。 */
    private fun sourceConfig() =
        connectionConfig {
            driver = DialectType.MYSQL.engineDriverName
            jdbcUrl = "jdbc:mysql://127.0.0.1:3306/demo"
            user = "u"
            password = "p"
            database = "demo"
        }

    /** 一个远端源在会话里的绑定。 */
    private fun binding(
        id: String,
        config: com.langbiantianya.grpc.ConnectionConfig = sourceConfig(),
    ) = AnalysisSourceBinding(
        item = AnalysisSourceItem(id = id, name = "源 $id", dialectLabel = "MySQL"),
        engineConfig = config,
        dialect = DialectType.MYSQL,
    )

    // ──────────────── 只读方向 ────────────────

    @Test
    fun `the workspace connection is writable`() {
        // 反了这条，M4 的验收「工作区里 CREATE TABLE tmp AS SELECT … 可以成功」就不成立。
        val config = newSession().workspaceConnection()
        assertFalse(config.readOnly, "工作区必须可写 —— 它是分析模式的计算引擎，不是只读的远端")
        assertEquals(DialectType.DUCKDB.engineDriverName, config.driver, "工作区必须落在 DuckDB 上")
        assertTrue(config.jdbcUrl.startsWith("jdbc:duckdb:"), "工作区 URL 形状不对：${config.jdbcUrl}")
    }

    @Test
    fun `the remote source session is read only`() {
        val session = newSession()
        session.setSources(listOf(binding("c1")))

        val config = assertNotNull(session.sourceConfig("c1"), "源的配置应当能取到")
        assertTrue(config.readOnly, "远端源必须只读 —— 分析模式对远端只有读的权限")
    }

    @Test
    fun `the stored source config is not mutated by the read only marking`() {
        // 只读是**这次会话**的性质，不是「这条连接是什么」的性质。
        // 浏览模式里同一条连接是可写的 —— 在这里就地改掉，就把浏览模式也废了。
        val stored = sourceConfig()
        val session = newSession()
        session.setSources(listOf(binding("c1", stored)))

        session.sourceConfig("c1")

        assertFalse(stored.readOnly, "浏览模式的那份配置不能被就地改成只读")
    }

    // ──────────────── 状态迁移 ────────────────

    @Test
    fun `a blank statement does not run at all`() {
        val session = newSession()
        session.updateSql("   \n  ")

        assertNull(session.run(), "空语句不该启动一次查询")
        assertNull(session.result, "空语句不该产生结果")
    }

    @Test
    fun `the staging name follows the shared rule`() {
        // 规则本体在 :shared，这里只验「会话转出来的名字与那条规则一致」——
        // 两边各写一份拼表名的代码，就是两个真相源。
        assertEquals("staging_shop_orders", newSession().stagingName("shop", "orders"))
    }

    // ──────────────── 工作区上真的跑起来 ────────────────

    @Test
    fun `running a statement on the workspace fills the result`() {
        val session = newSession()
        session.updateSql("CREATE TABLE t (id INT, label VARCHAR)")

        runBlocking { session.run()?.join() }

        val result = assertNotNull(session.result, "跑完必须有结果")
        assertFalse(result.failed, "建表不该失败：${result.error}")
        // DuckDB 的 JDBC 对 DDL 回 **-1**（JDBC 约定：这不是「更新计数」），不是 0。
        // 断言写 0 会把「语句不产生行数」和「语句没生效」混成同一件事。
        assertEquals(-1, result.affectedRows, "DDL 没有行数概念，DuckDB 按 JDBC 约定回 -1")
        assertTrue(result.columns.isEmpty(), "DDL 没有结果集，不该有列")
        assertTrue(result.rows.isEmpty(), "DDL 没有结果集，不该有行")
        assertFalse(session.running, "跑完必须把 running 落回去")
    }

    @Test
    fun `a select returns the rows and the columns`() {
        val session = newSession()
        session.updateSql("CREATE TABLE nums (id INT, label VARCHAR)")
        runBlocking { session.run()?.join() }
        session.updateSql("INSERT INTO nums VALUES (1, 'a'), (2, 'b')")
        runBlocking { session.run()?.join() }

        session.updateSql("SELECT id, label FROM nums ORDER BY id")
        runBlocking { session.run()?.join() }

        val result = assertNotNull(session.result)
        assertFalse(result.failed, "查询不该失败：${result.error}")
        assertEquals(2, result.columns.size, "两列")
        assertEquals(listOf("id", "label"), result.columns.map { it.name })
        assertEquals(2, result.rows.size, "两行")
        assertEquals(listOf<String?>("1", "a"), result.rows[0])
        assertEquals(listOf<String?>("2", "b"), result.rows[1])
    }

    @Test
    fun `a broken statement becomes an error result instead of an exception`() {
        // 界面要显示的是「这句话哪里错了」—— 那是一行要摆出来的文字，
        // 抛出去只会变成一个红条 + 堆栈。
        val session = newSession()
        session.updateSql("SELECT * FROM table_that_does_not_exist")

        runBlocking { session.run()?.join() }

        val result = assertNotNull(session.result, "出错也要有结果，否则界面无从显示")
        assertTrue(result.failed, "SQL 出错必须是失败结果")
        assertTrue(!result.error.isNullOrBlank(), "失败结果必须带原因")
        assertFalse(session.running, "跑完必须把 running 落回去")
    }

    @Test
    fun `the workspace directory is created on demand`() {
        // DuckDB 不会自动建上级目录 —— 目录不存在就直接失败。
        // 而用户第一次打开分析模式时，那个目录**一定**不存在。
        val session = newSession()
        assertFalse(Files.exists(session.workspacePath.parent), "前置条件：目录一开始不该存在")

        session.updateSql("SELECT 1")
        runBlocking { session.run()?.join() }

        assertTrue(Files.exists(session.workspacePath.parent), "用工作区之前必须把目录建出来")
        val result = assertNotNull(session.result)
        assertFalse(result.failed, "建完目录就该能查：${result.error}")
    }

    // ──────────────── 拉取的终态 ────────────────

    @Test
    fun `a finished pull lands in the done outcome`() {
        val session = newSession(FakePuller { PullResult(stagingTable = "staging_x", rowsLanded = 3) })
        session.setSources(listOf(binding("c1")))

        runBlocking { session.pull("c1", "demo", "orders")?.join() }

        val outcome = assertNotNull(session.pullOutcome, "拉完了必须有终态")
        assertTrue(outcome is PullOutcome.Done, "应当落在完成态：$outcome")
        assertEquals("staging_x", (outcome as PullOutcome.Done).result.stagingTable)
        assertFalse(session.pulling, "跑完必须把 pulling 落回去")
    }

    @Test
    fun `a cancelled pull lands in the cancelled outcome and not in the failed one`() {
        // 取消不是失败 —— 显示成失败会让用户以为出了错。
        val session = newSession(FakePuller { throw CancellationException("cancelled") })
        session.setSources(listOf(binding("c1")))

        runBlocking { session.pull("c1", "demo", "orders")?.join() }

        val outcome = assertNotNull(session.pullOutcome, "取消也必须留下终态")
        assertTrue(outcome is PullOutcome.Cancelled, "取消必须落在取消态：$outcome")
        assertFalse(session.pulling, "取消完必须把 pulling 落回去")
    }

    @Test
    fun `a failed pull lands in the failed outcome with the reason`() {
        val session = newSession(FakePuller { throw IllegalStateException("表不存在") })
        session.setSources(listOf(binding("c1")))

        runBlocking { session.pull("c1", "demo", "orders")?.join() }

        val outcome = assertNotNull(session.pullOutcome, "失败也必须有终态")
        assertTrue(outcome is PullOutcome.Failed, "应当落在失败态：$outcome")
        assertEquals("表不存在", (outcome as PullOutcome.Failed).reason)
        assertFalse(session.pulling, "失败完必须把 pulling 落回去")
    }

    @Test
    fun `a pull of an unknown source does not start at all`() {
        // 源 id 是**显式**传进来的 —— 传错（或传一个已删掉的源）时，
        // 宁可不启动：拿着查无此人的 id 去引擎，得到的只会是一句看不懂的错。
        val session = newSession(FakePuller { PullResult(stagingTable = "staging_x") })

        assertNull(session.pull("nope", "demo", "orders"), "源不存在时不该启动拉取")
        assertNull(session.pullOutcome, "没启动就没有终态")
        assertFalse(session.pulling, "没启动就不该在拉取中")
    }
}
