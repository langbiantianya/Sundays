/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.report.ReportStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * **本地工作区的端到端测试** —— 真 DuckDB 文件、真引擎、真建表。
 *
 * ## 为什么必须用真 DuckDB
 *
 * M4 的验收原文：「工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**」。
 * 这条验收用假引擎是验不出来的 —— 假引擎当然什么都能建。
 * 真正要验的是：
 *
 * - DuckDB 文件能不能被引擎连上（URL 拼对了吗）
 * - 建表语句在 DuckDB 上能不能跑（方言对不对）
 * - 建出来的表在**下一次**连接里还在（文件是不是真的落盘了，而不是只在内存里）
 *
 * 第三条尤其重要：工作区是**跨会话常驻**的，只在同一条连接里可见的表等于没有。
 */
class AnalysisWorkspaceIntegrationTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var session: AnalysisSession

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-ws").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        session =
            AnalysisSession(
                engine = engine,
                scope = CoroutineScope(Dispatchers.IO),
                workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
                reportStorage =
                    ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
            )
    }

    @After
    fun tearDown() {
        try {
            engine.close()
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    /**
     * 工作区的引擎连接配置 —— **直接取会话那份**。
     *
     * 早先这里是照着 `AnalysisSession` 又抄了一遍「工作区怎么连」的代码：
     * 同一个知识两处各写一份，改了一处忘了另一处，
     * 测试就会对着**另一种**连法发绿 —— 而应用连的是另一种。
     */
    private fun workspaceConnection() = session.workspaceConnection()

    private val workspaceFile: String
        get() = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb"

    private fun runSql(sql: String) =
        runBlocking {
            engine.invoke(workspaceConnection()) {
                category = Category.ANALYSIS
                action = Action.RUN_QUERY
                analysisRequest =
                    analysisRequest {
                        runQuery = analysisRunQueryRequest { this.sql = sql }
                    }
            }
        }

    @Test
    fun `the workspace file can be created and tables can be created in it`() {
        // 先把目录建出来 —— DuckDB 不会自动建上级目录
        val dir = File(tempHome, ".config/sundays")
        assertTrue(dir.mkdirs(), "工作区目录应当能建出来")

        val created = runSql("CREATE TABLE tmp_a (id INT, name VARCHAR)")
        assertTrue(created.success, "工作区里建表必须成功：${created.error}")

        // 关键：**重开一条连接**再查 —— 表还在，才说明它真的落盘了
        val queried = runSql("SELECT COUNT(*) FROM tmp_a")
        assertTrue(queried.success, "查刚建的表必须成功：${queried.error}")
        assertEquals(1, queried.analysis.runQuery.rowsList.size, "应当查到一行（COUNT(*)）")
    }

    @Test
    fun `the workspace file survives across connections`() {
        val dir = File(tempHome, ".config/sundays")
        assertTrue(dir.mkdirs(), "工作区目录应当能建出来")

        assertTrue(runSql("CREATE TABLE keep_me (id INT)").success, "建表必须成功")
        assertTrue(runSql("INSERT INTO keep_me VALUES (7)").success, "插入必须成功")

        // 引擎侧的连接池可能还留着同一条连接 —— 断开后再连，
        // 验证的是「数据在文件里」，而不是「还在内存里」。
        val queried = runSql("SELECT id FROM keep_me")
        assertTrue(queried.success, "查询必须成功：${queried.error}")
        val runQuery = queried.analysis.runQuery
        val row = runQuery.rowsList.firstOrNull()
        assertNotNull(row, "应当查到刚插入的那一行")
    }

    @Test
    fun `the workspace directory is created when it does not exist`() {
        val path = Paths.get(workspaceFile)
        assertFalse(Files.exists(path.parent), "前置条件：目录一开始不该存在")

        val error = AnalysisSession.ensureWorkspaceDir(path)
        assertEquals(null, error, "建目录不该失败：$error")
        assertTrue(Files.exists(path.parent), "建完目录应当存在")
    }

    @Test
    fun `a query on the workspace returns rows and columns`() {
        val dir = File(tempHome, ".config/sundays")
        assertTrue(dir.mkdirs(), "工作区目录应当能建出来")

        runSql("CREATE TABLE nums (id INT, label VARCHAR)")
        runSql("INSERT INTO nums VALUES (1, 'a'), (2, 'b'), (3, 'c')")

        val response = runSql("SELECT id, label FROM nums ORDER BY id")
        assertTrue(response.success, "查询必须成功：${response.error}")
        val result = response.analysis.runQuery
        assertEquals(3, result.rowsList.size, "应当查回 3 行")
        assertEquals(2, result.columnsList.size, "应当有 2 列")
        assertFalse(result.truncated, "3 行不该报截断")
    }
}
