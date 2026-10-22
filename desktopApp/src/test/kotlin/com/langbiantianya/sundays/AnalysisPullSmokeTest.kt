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
import com.langbiantianya.sundays.analysis.PullStage
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import kotlin.time.Duration.Companion.minutes

/**
 * 拉取链路的**端到端**测试 —— 真 MySQL（内网）→ 临时 CSV → 真 DuckDB 工作区。
 *
 * ## 这一轮验的四条
 *
 * 1. 一张远端表真的落进工作区，**行数对得上**；
 * 2. 临时 CSV 在**成功**路径上被清理；
 * 3. 工作区里能跑**窗口函数**聚合（M4 的验收原文）；
 * 4. 工作区里 `CREATE TABLE tmp AS SELECT …` **成功**（本地写允许）。
 *
 * ## 为什么导出那一半只能对着真库验
 *
 * 导出跑在**子进程**里（引擎的 `ExportProcessManager`），它要连远端、
 * 要写文件、要跟父进程通信 —— 假引擎代不了。
 * 而导入那一半不碰远端，由 `AnalysisPullIntegrationTest` 用手写 CSV 覆盖。
 * 两边分工不重叠。
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

    private fun puller() = EngineAnalysisPuller(engine, tempRoot)

    private fun query(sql: String) = runBlocking { runAnalysisQuery(engine, workspaceConfig, sql) }

    @Test
    fun `a remote table is pulled into the workspace with the right row count`() {
        val stages = mutableListOf<PullStage>()

        val result =
            runBlocking {
                puller().pull(
                    source = sourceConfig,
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    dialect = DialectType.MYSQL,
                    namespace = database,
                    table = "analysis_pull_probe",
                ) { progress -> stages += progress.stage }
            }

        assertEquals("staging_${database}_analysis_pull_probe", result.stagingTable)
        assertEquals(3L, result.rowsExported, "导出的行数")
        assertEquals(3L, result.rowsImported, "插进工作区的行数")
        assertEquals(0L, result.rowsFailed, "不该有坏行")
        assertTrue(result.csvBytes > 0, "临时 CSV 应当有内容")

        // 三段进度都走到了
        assertTrue(stages.contains(PullStage.EXPORT), "应当走过导出段：$stages")
        assertTrue(stages.contains(PullStage.CSV), "应当走过 CSV 段：$stages")
        assertTrue(stages.contains(PullStage.IMPORT), "应当走过导入段：$stages")

        // 真的落进工作区了
        val counted = query("""SELECT COUNT(*) FROM "${result.stagingTable}"""")
        assertFalse(counted.failed, "查刚拉进来的表必须成功：${counted.error}")
        assertEquals(1, counted.rows.size)
        assertEquals("3", counted.rows[0][0], "行数必须对得上")
    }

    @Test
    fun `the temp csv is gone after a successful pull`() {
        runBlocking {
            puller().pull(
                source = sourceConfig,
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                dialect = DialectType.MYSQL,
                namespace = database,
                table = "analysis_pull_probe",
            ) { }
        }

        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "拉取结束后不能留下临时目录：$leftovers")
    }

    @Test
    fun `the workspace can run a window function over the pulled data`() {
        val result =
            runBlocking {
                puller().pull(
                    source = sourceConfig,
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    dialect = DialectType.MYSQL,
                    namespace = database,
                    table = "analysis_pull_probe",
                ) { }
            }

        // staging 的列是 VARCHAR（原始落地层），所以聚合前要显式转换 ——
        // `TRY_CAST` 让「转不动」的值落成 NULL，而不是让整条查询失败。
        val windowed =
            query(
                """
                SELECT id, sum(TRY_CAST(id AS BIGINT)) OVER (ORDER BY id) AS running
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
        val result =
            runBlocking {
                puller().pull(
                    source = sourceConfig,
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    dialect = DialectType.MYSQL,
                    namespace = database,
                    table = "analysis_pull_probe",
                ) { }
            }

        val created =
            query(
                """
                CREATE TABLE tmp_model AS
                SELECT TRY_CAST(id AS BIGINT) AS id, label FROM "${result.stagingTable}"
                """.trimIndent(),
            )
        assertFalse(created.failed, "工作区里建表必须成功：${created.error}")

        val counted = query("SELECT COUNT(*) FROM tmp_model")
        assertFalse(counted.failed, counted.error)
        assertEquals("3", counted.rows[0][0])
    }

    @Test
    fun `a cancelled pull leaves no temp directory behind`() {
        // 「三条路径上都被清理」里的**取消**那条。
        //
        // 取消这一条最危险：导出跑在**另一个进程**里，它正开着那个 CSV 文件；
        // Windows 上文件被占用时删不掉 —— 所以取消时必须**先叫停子进程**再删目录。
        //
        // 取消的时机选在**导出已经开跑**之后（进度帧带上了行数）：
        // 那时临时目录已经建好、导出子进程也已经起来了 ——
        // 取消一定落在拉取途中，不靠运气。
        //
        // ⚠️ 这一条断言的是「临时目录被清干净」；「叫停子进程」那条**判别力更强**的断言
        // 在 `AnalysisPullCancelTest`（假引擎把导出钉成「永远不结束」，时机是确定的）。
        val job =
            scope.launch {
                runCatching {
                    puller().pull(
                        source = sourceConfig,
                        workspace = workspaceConfig,
                        workspacePath = workspacePath,
                        dialect = DialectType.MYSQL,
                        namespace = database,
                        table = "analysis_pull_probe",
                    ) { progress ->
                        if (progress.stage == PullStage.EXPORT && progress.rows > 0) {
                            // 用协程自己的 Job，别用外面那个变量 ——
                            // 外面那个在 launch 返回之前还没赋值。
                            coroutineContext.job.cancel()
                        }
                    }
                }
            }
        runBlocking { job.join() }

        assertTrue(job.isCancelled, "这次拉取应当是被取消的")
        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "取消路径上也不能留下临时目录：$leftovers")
    }
}
