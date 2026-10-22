/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.ANALYSIS_WORKSPACE_NODE_ID
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.AnalysisTreeKind
import com.langbiantianya.sundays.analysis.AnalysisTreeNode
import com.langbiantianya.sundays.analysis.findAnalysisNode
import com.langbiantianya.sundays.connection.DialectType
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据源树在**会话**里的行为 —— 真 DuckDB 工作区、真引擎。
 *
 * ## 这一轮要守住的四条
 *
 * 1. **工作区在第一位**，且**永远**在（它不是某个连接，是分析模式自带的引擎）；
 * 2. **展开是懒加载的** —— 点开才去查，不是启动时把每个源的每个库都列一遍；
 * 3. **加载失败不能变成「这里没有子节点」** —— 错误要能被看见，且能重试；
 * 4. **连接列表变了不能把用户的树抹掉** —— 已经加载出来的子节点要留着。
 */
class AnalysisTreeSessionTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-tree").toFile()
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

    private fun newSession(): AnalysisSession =
        AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
        )

    private fun binding(
        id: String,
        driver: String = DialectType.MYSQL.engineDriverName,
    ) = AnalysisSourceBinding(
        item = AnalysisSourceItem(id = id, name = "源 $id", dialectLabel = "MySQL"),
        engineConfig =
            connectionConfig {
                this.driver = driver
                jdbcUrl = "jdbc:mysql://127.0.0.1:3306/demo"
                user = "u"
                password = "p"
                database = "demo"
            },
        dialect = DialectType.MYSQL,
    )

    private fun workspaceNode(session: AnalysisSession): AnalysisTreeNode =
        assertNotNull(
            findAnalysisNode(session.treeRoots, ANALYSIS_WORKSPACE_NODE_ID),
            "工作区节点必须一直在树里",
        )

    // ──────────────── 根 ────────────────

    @Test
    fun `the tree starts with the workspace and then the sources`() {
        val session = newSession()
        session.setSources(listOf(binding("c1"), binding("c2")))

        assertEquals(3, session.treeRoots.size, "工作区 + 两个源")
        assertEquals(ANALYSIS_WORKSPACE_NODE_ID, session.treeRoots[0].id, "工作区必须在第一位")
        assertEquals(AnalysisTreeKind.WORKSPACE, session.treeRoots[0].kind)
        assertEquals("c1", session.treeRoots[1].id)
        assertEquals("c2", session.treeRoots[2].id)
    }

    @Test
    fun `the workspace is in the tree even when there is no source at all`() {
        // 没有远端源时分析模式仍然能用 —— 工作区就是它的计算引擎。
        // 把工作区也藏掉的话，界面就成了一块空白板。
        val session = newSession()
        session.setSources(emptyList())

        assertEquals(1, session.treeRoots.size)
        assertEquals(ANALYSIS_WORKSPACE_NODE_ID, session.treeRoots[0].id)
    }

    @Test
    fun `the source rows keep their dialect label`() {
        val session = newSession()
        session.setSources(listOf(binding("c1")))

        assertEquals("MySQL", session.treeRoots[1].dialectLabel, "用户要一眼看出这是哪种远端库")
        assertEquals("DuckDB", session.treeRoots[0].dialectLabel, "工作区也要标出引擎")
    }

    // ──────────────── 懒加载 ────────────────

    @Test
    fun `expanding the workspace lists its tables`() {
        val session = newSession()
        session.updateSql("CREATE TABLE tree_probe (id INT)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())

        val node = workspaceNode(session)
        assertEquals(emptyList(), node.children, "前置条件：还没展开过")
        assertFalse(node.loaded, "前置条件：还没加载过")

        runBlocking { session.toggleNode(node)?.join() }

        val loaded = workspaceNode(session)
        assertTrue(loaded.loaded, "展开过就该标记成已加载")
        assertEquals(
            listOf("tree_probe"),
            loaded.children.map { it.label },
            "工作区里刚建的表必须出现在树上",
        )
        assertEquals(AnalysisTreeKind.TABLE, loaded.children[0].kind)
        assertEquals("tree_probe", loaded.children[0].table)
    }

    @Test
    fun `toggling a node twice collapses it again`() {
        val session = newSession()
        session.setSources(emptyList())
        val node = workspaceNode(session)

        runBlocking { session.toggleNode(node)?.join() }
        assertTrue(ANALYSIS_WORKSPACE_NODE_ID in session.expandedNodeIds, "第一次点开是展开")

        session.toggleNode(workspaceNode(session))
        assertFalse(ANALYSIS_WORKSPACE_NODE_ID in session.expandedNodeIds, "再点一次是收起")
    }

    @Test
    fun `a collapsed node keeps its loaded children`() {
        // 收起只是「不看」，不是「忘掉」—— 再点开时不该重新去查一遍。
        val session = newSession()
        session.updateSql("CREATE TABLE keep_probe (id INT)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())

        runBlocking { session.toggleNode(workspaceNode(session))?.join() }
        session.toggleNode(workspaceNode(session))
        runBlocking { session.toggleNode(workspaceNode(session))?.join() }

        assertEquals(
            listOf("keep_probe"),
            workspaceNode(session).children.map { it.label },
            "收起再展开之后，表还得在",
        )
    }

    // ──────────────── 失败 ────────────────

    @Test
    fun `a failing load reports the reason instead of pretending the node is empty`() {
        // 引擎里**没有**这个方言 —— 请求必然失败。
        // 失败若被当成「这个源下一个库都没有」，用户会以为是自己连错了库。
        val session = newSession()
        session.setSources(listOf(binding("c1", driver = "NoSuchDialect")))

        runBlocking { session.toggleNode(session.treeRoots[1])?.join() }

        val error = assertNotNull(session.treeError, "加载失败必须留下原因")
        assertTrue(error.isNotBlank(), "错误不能是空串")
        assertFalse(session.treeLoading, "跑完必须把 loading 落回去")
        assertEquals(
            emptyList(),
            findAnalysisNode(session.treeRoots, "c1")?.children,
            "失败的节点不能被填成「空列表」——那是「查过了，确实没有」",
        )
    }

    // ──────────────── 树不被抹掉 ────────────────

    @Test
    fun `refreshing the sources keeps the loaded children`() {
        // 连接列表变一下（比如加了个连接）不该把用户展开的树缩回去。
        val session = newSession()
        session.updateSql("CREATE TABLE survive_probe (id INT)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())
        runBlocking { session.toggleNode(workspaceNode(session))?.join() }

        session.setSources(listOf(binding("c1")))

        val workspace = workspaceNode(session)
        assertTrue(workspace.loaded, "已经加载过的节点不该被重新变成「没加载」")
        assertEquals(listOf("survive_probe"), workspace.children.map { it.label })
    }

    @Test
    fun `a removed source disappears from the tree`() {
        val session = newSession()
        session.setSources(listOf(binding("c1"), binding("c2")))

        session.setSources(listOf(binding("c1")))

        assertEquals(2, session.treeRoots.size, "工作区 + 剩下的那个源")
        assertNull(findAnalysisNode(session.treeRoots, "c2"), "删掉的源不能还留在树上")
    }

    // ──────────────── 把表名加进编辑器 ────────────────

    @Test
    fun `appending a table name to an empty editor seeds a runnable statement`() {
        val session = newSession()

        session.appendToSql("orders")

        assertEquals("SELECT * FROM \"orders\"", session.sql, "空编辑器应当直接给一条能跑的语句")
    }

    @Test
    fun `appending a table name to a non empty editor appends a quoted reference`() {
        val session = newSession()
        session.updateSql("SELECT 1")

        session.appendToSql("orders")

        assertEquals("SELECT 1\n\"orders\"", session.sql, "有内容时只追加一个引用，让用户自己挪位置")
    }

    @Test
    fun `an appended table name is quoted`() {
        // 名字是**用户起的**，可能带空格 —— 不引用就是一条跑不了的语句。
        val session = newSession()

        session.appendToSql("my orders")

        assertEquals("SELECT * FROM \"my orders\"", session.sql)
    }
}
