/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.ANALYSIS_WORKSPACE_NODE_ID
import com.langbiantianya.sundays.analysis.report.ReportStorage
import com.langbiantianya.sundays.tree.ObjectTreeKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「添加数据源」在**会话**里的行为 —— 真 DuckDB 工作区 + 真文件。
 *
 * ## 这一轮要守住的四条
 *
 * 1. **加进去的东西真的在工作区里** —— 不是只登记了一行字；
 * 2. **它出现在树上** —— 加了源却看不见，等于没加；
 * 3. **源登记在册** —— 下次启动还在（工作区 DuckDB 里那张登记表）；
 * 4. **文件源走的是 DuckDB 自己的读法** —— 建的是**视图**，
 *    文件改了查询就跟着变（不是「拷了一份进工作区」）。
 */
class AnalysisSourceSessionTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-source").toFile()
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

    private fun csvFile(
        name: String,
        content: String,
    ): File = File(tempHome, name).apply { writeText(content) }

    /** 一个「订单文件」源 —— CSV 落在临时目录里。 */
    private fun csvDraft(
        file: File,
        name: String = "订单文件",
    ) = AnalysisSourceDraft(
        name = name,
        kind = AnalysisSourceKind.FILE,
        path = file.absolutePath,
        format = AnalysisFileFormat.CSV,
    )

    /** 把这个源接进工作区，并等它落定。 */
    private fun addSource(
        session: AnalysisSession,
        draft: AnalysisSourceDraft,
    ): String? {
        var reason: String? = null
        runBlocking {
            val job =
                session.addSource(
                    draft = draft,
                    onFailure = { failure -> reason = failure },
                )
            job?.join()
        }
        return reason
    }

    // ──────────────── 文件源 ────────────────

    @Test
    fun `a csv file becomes a source in the workspace and shows up in the tree`() {
        val csv = csvFile("orders.csv", "id,amount\n1,10\n2,20\n")
        val session = newSession()
        session.setSources(emptyList())

        val reason = addSource(session, csvDraft(csv))
        assertEquals(null, reason, "加源不该失败：$reason")

        // 树上多了一根 —— 根 = 工作区的各个 schema，而源占它自己的 schema。
        val source = session.treeRoots.last()
        assertEquals("订单文件", source.label, "源的显示名就是它的 schema 名")
        assertEquals(ObjectTreeKind.DATABASE, source.kind)
        assertNotNull(
            findObjectTreeNode(session.treeRoots, ANALYSIS_WORKSPACE_NODE_ID),
            "工作区那一根必须还在",
        )
    }

    @Test
    fun `the file source is a view over the file and can be queried`() {
        val csv = csvFile("orders.csv", "id,amount\n1,10\n2,20\n")
        val session = newSession()
        session.setSources(emptyList())

        addSource(session, csvDraft(csv))

        // 展开那个源 → 它的表（视图）→ 字段
        val source = session.treeRoots.last()
        runBlocking { session.toggleNode(source)?.join() }
        // 重新从树上取 —— `source` 是**展开前**的那一份快照，它的 children 还是空的。
        val loadedSource = assertNotNull(findObjectTreeNode(session.treeRoots, source.id))
        val table = loadedSource.children.single()
        assertEquals("orders", table.label, "视图的名字取文件名（去掉扩展名）")

        runBlocking { session.toggleNode(table)?.join() }
        val expanded = assertNotNull(findObjectTreeNode(session.treeRoots, table.id))
        assertEquals(
            listOf("id", "amount"),
            expanded.children.map { it.label },
            "字段必须列得出来",
        )

        // 它**真的能查** —— 不是只画在树上的一行字。
        session.updateSql("SELECT count(*) AS n FROM \"订单文件\".\"orders\"")
        runBlocking { session.run()?.join() }
        val result = assertNotNull(session.result, "必须能跑出结果")
        assertTrue(!result.failed, "查询不该失败：${result.error}")
        assertEquals("2", result.rows.firstOrNull()?.firstOrNull(), "视图里应该有两行")
    }

    @Test
    fun `a file source is registered so it survives the next start`() {
        val csv = csvFile("orders.csv", "id,amount\n1,10\n")
        val session = newSession()
        session.setSources(emptyList())

        addSource(session, csvDraft(csv))

        val registered = runBlocking { session.registeredSources() }
        val row = registered.singleOrNull()
        assertNotNull(row, "源必须登记在册")
        assertEquals("订单文件", row.name)
        assertEquals(AnalysisSourceKind.FILE, row.kind)
        assertEquals(csv.absolutePath, row.path)
    }

    @Test
    fun `a second session on the same workspace still sees the registered source`() {
        val csv = csvFile("orders.csv", "id,amount\n1,10\n")
        val first = newSession()
        first.setSources(emptyList())
        addSource(first, csvDraft(csv))

        // 「下次启动」= 同一个工作区文件上的**另一个会话**
        val second = newSession()
        second.setSources(emptyList())
        val registered = runBlocking { second.registeredSources() }
        assertEquals(
            listOf("订单文件"),
            registered.map { it.name },
            "登记表在工作区 DuckDB 里，换个会话也读得到",
        )
    }

    @Test
    fun `a file with an unknown format is refused instead of guessed`() {
        // 猜格式猜错了，报错会指向一个莫名其妙的地方（路径被截断的视图）。
        // 与其那样，不如让用户自己选一个。
        val odd = File(tempHome, "data.bin").apply { writeText("x") }
        val session = newSession()
        session.setSources(emptyList())

        val reason =
            addSource(
                session,
                AnalysisSourceDraft(
                    name = "神秘文件",
                    kind = AnalysisSourceKind.FILE,
                    path = odd.absolutePath,
                    format = null,
                ),
            )

        assertNotNull(reason, "看不出格式就得说清为什么")
        assertTrue(reason.contains("格式"), "错误要说清是「格式看不出来」：$reason")
    }

    @Test
    fun `a source without a name is not added at all`() {
        val csv = csvFile("orders.csv", "id,amount\n1,10\n")
        val session = newSession()
        session.setSources(emptyList())

        val job =
            session.addSource(
                draft = csvDraft(csv, name = "  "),
            )
        assertEquals(null, job, "没有名字的源不该启动任何任务")
    }
}
