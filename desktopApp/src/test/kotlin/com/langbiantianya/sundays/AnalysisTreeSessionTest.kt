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
import com.langbiantianya.sundays.analysis.report.ReportStorage
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.findObjectTreeNode
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
import java.nio.file.Paths
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
            reportStorage =
                ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
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

    private fun workspaceNode(session: AnalysisSession): ObjectTreeNode =
        assertNotNull(
            findObjectTreeNode(session.treeRoots, ANALYSIS_WORKSPACE_NODE_ID),
            "工作区节点必须一直在树里",
        )

    // ──────────────── 根 ────────────────

    @Test
    fun `the tree holds the workspace only and not the saved connections`() {
        // 树是「**我的分析工作区里有什么**」的视图：工作区 + 已经拉进来的表。
        // 保存的连接配置**属于连接管理器** —— 同一份配置在两处都列一遍，
        // 就会变成**两份可能对不上的真相**（改了名字只更新一处、两处列的表不一样）。
        val session = newSession()
        session.setSources(listOf(binding("c1"), binding("c2")))

        assertEquals(1, session.treeRoots.size, "树里只有工作区一个根")
        assertEquals(ANALYSIS_WORKSPACE_NODE_ID, session.treeRoots[0].id)
        assertEquals(ObjectTreeKind.DATABASE, session.treeRoots[0].kind)
        assertEquals("DuckDB", session.treeRoots[0].badge, "工作区要标出引擎")
        assertNull(findObjectTreeNode(session.treeRoots, "c1"), "保存的连接不能出现在分析树的根里")
        assertNull(findObjectTreeNode(session.treeRoots, "c2"), "保存的连接不能出现在分析树的根里")
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
        // 源行已经不在分析树里了（树只显示 DuckDB 那一支）——
        // 这条留下的只有一个根：工作区标着它的引擎。
        val session = newSession()
        session.setSources(listOf(binding("c1")))

        assertEquals("DuckDB", session.treeRoots[0].badge, "工作区要标出引擎")
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
        assertEquals(ObjectTreeKind.TABLE, loaded.children[0].kind)
        assertEquals("tree_probe", loaded.children[0].label)
    }

    // ──────────────── 字段层 ────────────────

    @Test
    fun `expanding a table lists its columns`() {
        // 分析页的树以前**到表为止** —— 用户看不到「这张表有哪些列」，
        // 而这正是写分析 SQL 时最需要的对照信息。
        // 现在它与浏览模式的对象树**完全同形**（库 → 表 → 字段）。
        val session = newSession()
        session.updateSql("CREATE TABLE col_probe (id INTEGER, name VARCHAR)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())

        runBlocking { session.toggleNode(workspaceNode(session))?.join() }
        val table = workspaceNode(session).children.single()
        assertEquals(ObjectTreeKind.TABLE, table.kind)

        runBlocking { session.toggleNode(table)?.join() }

        val expanded = findObjectTreeNode(session.treeRoots, table.id)
        assertNotNull(expanded, "表节点必须还在树里")
        assertEquals(
            listOf("id", "name"),
            expanded.children.map { it.label },
            "字段必须按名字列出来",
        )
        assertEquals(ObjectTreeKind.COLUMN, expanded.children[0].kind)
        assertEquals("INTEGER", expanded.children[0].column?.type, "字段要带着类型")
        assertEquals("col_probe", expanded.children[0].parentLabel, "字段要带着它属于哪张表")
    }

    @Test
    fun `a column carries its type and its constraints`() {
        val session = newSession()
        session.updateSql("CREATE TABLE pk_probe (id INTEGER PRIMARY KEY, note VARCHAR)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())

        runBlocking { session.toggleNode(workspaceNode(session))?.join() }
        val table = workspaceNode(session).children.single()
        runBlocking { session.toggleNode(table)?.join() }

        val id = findObjectTreeNode(session.treeRoots, table.id)?.children?.first { it.label == "id" }
        assertNotNull(id, "主键列必须在树里")
        assertTrue(id.column?.primaryKey == true, "主键列要标出主键")
        assertTrue(id.column?.nullable == false, "主键列不可为空")
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
        // 工作区**打不开** —— 路径指向的是一个**目录**，DuckDB 开不了它。
        //
        // ⚠️ 为什么不能写成「目录根本不存在」：会话在启动时会**把工作区的目录建出来**
        // （`ensureWorkspaceDir`）—— 于是「目录不存在」这条理由当场失效，
        // 工作区反而能打开了，测的就不是「打不开」而是「建得出来」。
        // 失败若被当成「这里一个子节点都没有」，用户会以为是自己弄丢了表。
        val session =
            AnalysisSession(
                engine = engine,
                scope = scope,
                // 指向 tempHome **这个目录**本身 —— 它不是文件，DuckDB 开不了。
                workspaceFile = tempHome.absolutePath,
                reportStorage =
                    ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
            )
        session.setSources(emptyList())

        runBlocking { session.toggleNode(workspaceNode(session))?.join() }

        val error = assertNotNull(session.treeError, "加载失败必须留下原因")
        assertTrue(error.isNotBlank(), "错误不能是空串")
        assertFalse(session.treeLoading, "跑完必须把 loading 落回去")
        assertEquals(
            emptyList(),
            workspaceNode(session).children,
            "失败的节点不能被填成「空列表」——那是「查过了，确实没有」",
        )
        // 错误还要落在**那个节点**上 —— 共享树组件在节点脚下显示它，
        // 用户一眼就知道是哪一个节点出了问题（而不是整棵树都坏了）。
        assertNotNull(
            workspaceNode(session).error,
            "失败的原因必须落在那个节点上",
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
    fun `the connection list does not change the tree at all`() {
        // 树里只有工作区 —— 所以连接列表怎么变（加连接、删连接、改名），
        // 树都**不受影响**：它是「我的工作区里有什么」，不是「我连了哪些库」。
        val session = newSession()
        session.updateSql("CREATE TABLE survive_probe (id INT)")
        runBlocking { session.run()?.join() }
        session.setSources(emptyList())
        runBlocking { session.toggleNode(workspaceNode(session))?.join() }

        session.setSources(listOf(binding("c1"), binding("c2")))
        session.setSources(emptyList())

        assertEquals(1, session.treeRoots.size, "树里始终只有工作区")
        val workspace = workspaceNode(session)
        assertTrue(workspace.loaded, "已经加载过的节点不该被重新变成「没加载」")
        assertEquals(listOf("survive_probe"), workspace.children.map { it.label })
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
