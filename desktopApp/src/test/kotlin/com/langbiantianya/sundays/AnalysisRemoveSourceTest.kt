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
import com.langbiantianya.sundays.analysis.DeleteSourceOutcome
import com.langbiantianya.sundays.analysis.WORKSPACE_SCHEMA
import com.langbiantianya.sundays.analysis.analysisSchemaNodeId
import com.langbiantianya.sundays.analysis.report.ReportStorage
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
 * 「删除数据源」在**会话**里的行为 —— 真 DuckDB 工作区、真引擎。
 *
 * ## 这一轮要守住的
 *
 * 1. **删得掉**：工作区里那个 schema 与登记表里那一行**都要**没；
 * 2. **只删该删的**：工作区自己的 `main` 与用户手工建的 schema **不能**被删；
 * 3. **失败看得见**：拒绝与出错都要留下一个**说得清**的结果；
 * 4. **不误伤**：删一个源不能把工作区自己的表带走。
 *
 * ## 为什么用**文件数据源**而不是远端库
 *
 * 远端源要一个真的 MySQL / PostgreSQL 才能接进来 —— 那不是单元测试该有的依赖。
 * 文件源走的是 DuckDB 自己的读法（`read_csv_auto` + 建视图），
 * 而它在工作区里同样占**一个 schema**、同样登记在册 ——
 * 「删除数据源」要动的那两处（schema + 登记表）**一模一样**。
 */
class AnalysisRemoveSourceTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-remove-source").toFile()
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

    // ──────────────── 辅助 ────────────────

    /** 把一个 CSV 文件作为「文件数据源」接进工作区，并等它接完。 */
    private fun addFileSource(
        session: AnalysisSession,
        name: String,
    ) {
        val csv = File(tempHome, "$name.csv")
        csv.writeText("id,name\n1,Alice\n")
        var failure: String? = null
        val job =
            session.addSource(
                AnalysisSourceDraft(
                    name = name,
                    kind = AnalysisSourceKind.FILE,
                    path = csv.absolutePath,
                    format = AnalysisFileFormat.CSV,
                ),
                onFailure = { failure = it },
            )
        runBlocking { job?.join() }
        assertNull(failure, "添加数据源不该失败：$failure")
    }

    /** 在工作区上跑一句查询，返回结果的行数。 */
    private fun queryRows(
        session: AnalysisSession,
        sql: String,
    ): Int {
        session.updateSql(sql)
        runBlocking { session.run()?.join() }
        val result = session.result
        assertNotNull(result, "查询要有结果")
        assertNull(result.error, "查询不该失败：${result.error}")
        return result.rows.size
    }

    // ──────────────── 删得掉 ────────────────

    @Test
    fun `删掉数据源后 工作区里那个 schema 与登记表里那一行都没了`() {
        val session = newSession()
        addFileSource(session, "orders_csv")

        assertTrue(
            "orders_csv" in session.deletableSourceNames,
            "登记在册的数据源必须出现在「可删」清单里：${session.deletableSourceNames}",
        )
        assertNotNull(
            session.treeRoots.firstOrNull { it.id == analysisSchemaNodeId("orders_csv") },
            "数据源要在树上有一根",
        )

        runBlocking { session.removeSource("orders_csv")?.join() }

        // ① 工作区里那个 schema 没了
        assertEquals(
            0,
            queryRows(
                session,
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'orders_csv'",
            ),
            "schema 必须真的被 DROP 掉",
        )
        // ② 登记表里那一行也没了
        assertEquals(
            emptyList(),
            runBlocking { session.registeredSources() }.map { it.schemaName },
            "登记表里不能还留着这一行",
        )
        // ③ 「可删」清单与树都跟着更新
        assertFalse(
            "orders_csv" in session.deletableSourceNames,
            "删掉之后不能再出现在「可删」清单里",
        )
        assertNull(
            session.treeRoots.firstOrNull { it.id == analysisSchemaNodeId("orders_csv") },
            "删掉之后树上不能还留着这一根",
        )
    }

    @Test
    fun `删除成功后留下一个「已删除」的结果`() {
        val session = newSession()
        addFileSource(session, "orders_csv")

        runBlocking { session.removeSource("orders_csv")?.join() }

        val outcome = assertNotNull(session.deleteSourceOutcome, "删除成功要留下结果")
        assertEquals(
            DeleteSourceOutcome.Deleted("orders_csv"),
            outcome,
            "结果必须是「删掉了哪个源」，而不是一句笼统的话",
        )
    }

    @Test
    fun `删掉数据源会连它带进来的表一起删掉`() {
        val session = newSession()
        addFileSource(session, "orders_csv")
        // 前置条件：那个源里确实有东西
        assertTrue(
            queryRows(
                session,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'orders_csv'",
            ) > 0,
            "前置条件：数据源里至少有一张表",
        )

        runBlocking { session.removeSource("orders_csv")?.join() }

        assertEquals(
            0,
            queryRows(
                session,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'orders_csv'",
            ),
            "数据源带进来的表必须一起没了",
        )
    }

    @Test
    fun `删掉数据源不动工作区自己的表`() {
        val session = newSession()
        session.updateSql("CREATE TABLE keep_me (id INTEGER)")
        runBlocking { session.run()?.join() }
        addFileSource(session, "orders_csv")

        runBlocking { session.removeSource("orders_csv")?.join() }

        assertEquals(
            1,
            queryRows(
                session,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '$WORKSPACE_SCHEMA' AND table_name = 'keep_me'",
            ),
            "工作区自己的表不能被误删",
        )
    }

    // ──────────────── 只删该删的 ────────────────

    @Test
    fun `工作区自己不能被删`() {
        // `main` 是分析模式**自带的计算引擎**（主设计 M4 要求它可写）——
        // 删掉它等于删掉整个分析模式的工作区。
        //
        // ⚠️ 这里特意先加一个**名字就叫 `main`** 的数据源 ——
        // 用户什么名字都起得来，而它一登记，登记表里就有一行 `main`。
        // 那时 `DROP SCHEMA main CASCADE` 会把**整个工作区**连同里面的表一起删掉，
        // 所以这道闸必须在「登记表里**有** main」的情况下也拦得住。
        val session = newSession()
        session.updateSql("CREATE TABLE workspace_probe (id INTEGER)")
        runBlocking { session.run()?.join() }
        // 树的第一根是工作区 —— 它由 `setSources` 触发的那次刷新放上去
        // （工作区根**不需要**查任何东西，所以那一次是**同步**的）。
        session.setSources(emptyList())
        addFileSource(session, WORKSPACE_SCHEMA)

        val job = session.removeSource(WORKSPACE_SCHEMA)

        assertNull(job, "工作区不该进入删除流程")
        assertTrue(
            session.deleteSourceOutcome is DeleteSourceOutcome.Failed,
            "拒绝也要留下一个结果：${session.deleteSourceOutcome}",
        )
        assertEquals(
            1,
            queryRows(
                session,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '$WORKSPACE_SCHEMA' AND table_name = 'workspace_probe'",
            ),
            "工作区的表必须还在",
        )
        assertNotNull(
            session.treeRoots.firstOrNull { it.id == ANALYSIS_WORKSPACE_NODE_ID },
            "工作区节点必须还在树里",
        )
    }

    @Test
    fun `没登记过的 schema 不能被删`() {
        // 用户在 DuckDB 里手工建的 schema **不是数据源** ——
        // 菜单里不会给它「删除数据源」，而这里钉住的是**动作那一层**的同一道闸。
        val session = newSession()
        session.updateSql("CREATE SCHEMA manual_schema")
        runBlocking { session.run()?.join() }

        runBlocking { session.removeSource("manual_schema")?.join() }

        assertTrue(
            session.deleteSourceOutcome is DeleteSourceOutcome.Failed,
            "没登记过的 schema 必须被拒绝：${session.deleteSourceOutcome}",
        )
        assertEquals(
            1,
            queryRows(
                session,
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'manual_schema'",
            ),
            "用户自己建的 schema 必须还在",
        )
    }

    @Test
    fun `可删清单里永远没有工作区自己`() {
        // ⚠️ 特意登记一个**名字就叫 `main`** 的数据源：
        // 只有「登记表里真的有 main」时，这条断言才不是**假绿** ——
        // 否则 `deletableSourceNames = registered.keys`（漏掉排除工作区）
        // 这处改动根本不会让清单变，测试也就抓不住它。
        //
        // 而一旦 `main` 进了清单，右键工作区里的表就会给出「删除数据源」，
        // 那一下是 `DROP SCHEMA main CASCADE`：整个工作区连同里面的表一起没了。
        val session = newSession()
        addFileSource(session, WORKSPACE_SCHEMA)

        assertTrue(
            WORKSPACE_SCHEMA in runBlocking { session.registeredSources() }.map { it.schemaName },
            "前置条件：登记表里确实有一行叫 main",
        )
        assertFalse(
            WORKSPACE_SCHEMA in session.deletableSourceNames,
            "工作区不能出现在「可删」清单里：${session.deletableSourceNames}",
        )
    }
}
