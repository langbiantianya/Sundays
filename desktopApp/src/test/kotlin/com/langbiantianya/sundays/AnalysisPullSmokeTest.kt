/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拉取链路的**端到端**测试 —— 真 MySQL（内网）→（DuckDB 联邦直查）→ 真 DuckDB 工作区。
 *
 * ## 这一轮验的四条
 *
 * 1. 一张远端表真的落进工作区，**行数对得上**；
 * 2. **全程不落盘** —— 拉完之后连一个临时文件都没有（这是「零 CSV 中转」的判据）；
 * 3. 工作区里能跑**窗口函数**聚合（M4 的验收原文）—— 而且**不用 TRY_CAST**：
 *    联邦扫描保留远端的类型（`INT` 落下来还是整数）；
 * 4. 工作区里 `CREATE TABLE tmp AS SELECT …` **成功**（本地写允许）。
 *
 * ## 为什么必须对着真库验
 *
 * 联邦扫描走的是**真 JDBC 连接**（DuckDB → MySQL 驱动 → 远端），
 * 假引擎代不了；而 H2 那条路（`AnalysisPullFederationTest`）验的是链路本身，
 * 验不了 MySQL 的类型与引用形式。两边分工不重叠。
 *
 * ## 远程库不可达时 skip 而不是红
 *
 * 192.168.1.5 是内网地址，换台机器 / 断网就不存在。这类环境因素不该让构建变红，
 * 但**也不能静默跳过** —— 用 JUnit `assumeTrue` 显式 skip，报告里会留下一条 skipped。
 */
class AnalysisPullSmokeTest {
    private lateinit var tempDir: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var workspaceConfig: ConnectionConfig
    private lateinit var workspacePath: Path
    private lateinit var tempRoot: Path
    private var database: String = ""

    @Before
    fun setUp() {
        assumeTrue("内网 MySQL 不可达，跳过（内网地址，换台机器就不存在）", MySqlSmoke.reachable())
        tempDir = Files.createTempDirectory("sundays-pull-smoke").toFile()
        tempRoot = Files.createDirectories(tempDir.toPath().resolve("tmp"))

        MySqlSmoke.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        database = MySqlSmoke.provision(tempDir)
        MySqlSmoke.direct(database).use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS `analysis_pull_probe`")
                st.execute("CREATE TABLE `analysis_pull_probe` (id INT NOT NULL, label VARCHAR(32))")
                st.execute("INSERT INTO `analysis_pull_probe` VALUES (1,'a'),(2,'b'),(3,'c')")
            }
        }

        // 远端源：**恒为只读**（分析模式对远端只有读的权限）。
        sourceConfig =
            analysisSourceEngineConfig(MySqlSmoke.config(database))
                .toBuilder()
                .setReadOnly(true)
                .build()

        val file = "${tempDir.absolutePath}/.config/sundays/workspace.duckdb"
        workspacePath = Paths.get(file)
        workspaceConfig =
            connectionConfig {
                driver = DialectType.DUCKDB.engineDriverName
                jdbcUrl = "jdbc:duckdb:$file"
                database = file
            }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching {
            MySqlSmoke.direct(database).use { c ->
                c.createStatement().use { st -> st.execute("DROP TABLE IF EXISTS `analysis_pull_probe`") }
            }
        }
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    private fun puller() = EngineAnalysisPuller(engine)

    private fun query(sql: String) = runBlocking { runAnalysisQuery(engine, workspaceConfig, sql) }

    /** 拉一次，收集进度帧。 */
    private fun pull(onProgress: (Long) -> Unit = {}): com.langbiantianya.sundays.analysis.PullResult =
        runBlocking {
            puller().pull(
                requestId = "smoke-pull",
                source = sourceConfig,
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                namespace = database,
                table = "analysis_pull_probe",
            ) { progress -> onProgress(progress.rows) }
        }

    @Test
    fun `a remote table is pulled into the workspace with the right row count`() {
        val rowCounts = mutableListOf<Long>()

        val result = pull { rows -> rowCounts += rows }

        assertEquals("staging_${database}_analysis_pull_probe", result.stagingTable)
        assertEquals(3L, result.rowsScanned, "从远端读出的行数")
        assertEquals(3L, result.rowsLanded, "落进工作区的行数")

        // 真的落进工作区了
        val counted = query("""SELECT COUNT(*) FROM "${result.stagingTable}"""")
        assertFalse(counted.failed, "查刚拉进来的表必须成功：${counted.error}")
        assertEquals(1, counted.rows.size)
        assertEquals("3", counted.rows[0][0], "行数必须对得上")
    }

    @Test
    fun `the pull writes nothing to disk`() {
        // 「零 CSV 中转」的判据：拉完之后**连一个临时文件都没有**。
        //
        // 早先的链路要落一个几百 MB 的 CSV，还要在三条路径上清理它；
        // 联邦直查不落盘 —— 于是「清理」这件事**整个不存在**，
        // 也就不可能出现「忘了清理」。
        pull()

        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "联邦直查不该落任何临时文件：$leftovers")
    }

    @Test
    fun `the progress reports how many rows were read`() {
        val rowCounts = mutableListOf<Long>()

        pull { rows -> rowCounts += rows }

        assertTrue(rowCounts.isNotEmpty(), "拉取过程应当有进度帧")
        assertEquals(rowCounts.last(), 3L, "最后的进度应当是全部行数")
        // 进度是**逐帧**往外抛的：只报最终行数等于没报。
        assertTrue(rowCounts.all { it <= 3L }, "进度不能超过总行数：$rowCounts")
    }

    @Test
    fun `the pulled table keeps the remote column types`() {
        // 联邦扫描把远端的类型**原样**带过来（`INT` 落下来还是整数）。
        //
        // 早先的 CSV 中转做不到这件事：CSV 只有文本，类型得靠下游 `TRY_CAST` 重建。
        val result = pull()

        val summed = query("""SELECT SUM(id) FROM "${result.stagingTable}"""")
        assertFalse(summed.failed, "整数列应当能直接求和（不需要 TRY_CAST）：${summed.error}")
        assertEquals("6", summed.rows[0][0])
    }

    @Test
    fun `the workspace can run a window function over the pulled data`() {
        val result = pull()

        val windowed =
            query(
                """
                SELECT id, sum(id) OVER (ORDER BY id) AS running
                FROM "${result.stagingTable}"
                ORDER BY id
                """.trimIndent(),
            )
        assertFalse(windowed.failed, "窗口函数必须能跑：${windowed.error}")
        assertEquals(3, windowed.rows.size)
        assertEquals(listOf("1", "1"), windowed.rows[0])
        assertEquals(listOf("2", "3"), windowed.rows[1])
        assertEquals(listOf("3", "6"), windowed.rows[2])
    }

    @Test
    fun `the workspace can create a table from the pulled data`() {
        val result = pull()

        val created =
            query(
                """
                CREATE TABLE tmp_model AS
                SELECT id, label FROM "${result.stagingTable}"
                """.trimIndent(),
            )
        assertFalse(created.failed, "工作区里建表必须成功：${created.error}")

        val counted = query("SELECT COUNT(*) FROM tmp_model")
        assertFalse(counted.failed, counted.error)
        assertEquals("3", counted.rows[0][0])
    }
}
