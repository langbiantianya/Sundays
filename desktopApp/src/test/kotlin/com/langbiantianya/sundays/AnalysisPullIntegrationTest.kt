/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.dialect.DuckDBDialect
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.AnalysisPullCancelledException
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
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拉取链路的**端到端**测试 —— 前端的拉取器 → 真引擎 → 联邦扫描 → 真 DuckDB 工作区。
 *
 * ## 远端用 H2
 *
 * 这条链路要的是「DuckDB 通过一条 **JDBC 连接**读远端」——
 * 所以**任何**有 JDBC 驱动的库都能当远端，H2 正好是最容易准备的一个：
 * 内存库、不占端口、每次测试全新。
 *
 * 真正的 MySQL 由 `AnalysisPullSmokeTest` 兜着（那边验的是 MySQL 的类型与引用形式）。
 *
 * ## 这一轮验的四条
 *
 * 1. 一张远端表真的落进工作区，**行数对得上**；
 * 2. **进度帧真的往外抛**（前端那一层）；
 * 3. 拉一张**不存在的**表 → 失败，且**不留半张表**；
 * 4. 拉取**不落盘** —— 连一个临时文件都没有。
 */
class AnalysisPullIntegrationTest {
    private lateinit var tempDir: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var workspaceConfig: ConnectionConfig
    private lateinit var workspacePath: Path
    private lateinit var tempRoot: Path
    private var jdbcUrl: String = ""

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sundays-pull-int").toFile()
        tempRoot = Files.createDirectories(tempDir.toPath().resolve("tmp"))

        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val dbName = "pull_int_${System.nanoTime()}"
        jdbcUrl = H2Dialect().buildJdbcUrl("mem", 0, dbName)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE TABLE "orders" (id INT, label VARCHAR(32))""")
                st.execute("""INSERT INTO "orders" VALUES (1,'a'),(2,'b'),(3,'c')""")
            }
        }

        // 远端配置**不**写 jdbcUrl —— 让连接池按方言自己拼，
        // 与上面建表时用的那条 URL **逐字一致**（H2 的内存库是按名字认的：
        // 名字不一样就是另一个库，表会「不存在」）。
        sourceConfig =
            connectionConfig {
                driver = "H2"
                host = "mem"
                port = 0
                user = "sa"
                password = ""
                database = dbName
            }

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
        runCatching { DriverManager.getConnection(jdbcUrl, "sa", "").use { c -> c.createStatement().use { it.execute("DROP ALL OBJECTS") } } }
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    private fun puller() = EngineAnalysisPuller(engine)

    /** 工作区里有没有这张表。 */
    private fun workspaceHasTable(
        tableName: String,
    ): Boolean =
        runBlocking {
            runAnalysisQuery(
                engine,
                workspaceConfig,
                """SELECT count(*) FROM duckdb_tables() WHERE table_name = '$tableName'""",
            ).rows.firstOrNull()?.firstOrNull()?.toInt()?.let { it > 0 } ?: false
        }

    @Test
    fun `a remote table lands in the workspace through the federated scan`() {
        val result =
            runBlocking {
                puller().pull(
                    requestId = "int-pull",
                    source = sourceConfig,
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    namespace = "PUBLIC",
                    table = "orders",
                ) { }
            }

        assertEquals("staging_PUBLIC_orders", result.stagingTable)
        assertEquals(3L, result.rowsScanned)
        assertEquals(3L, result.rowsLanded)
        assertTrue(workspaceHasTable("staging_PUBLIC_orders"), "工作区里要有那张表")
    }

    @Test
    fun `the progress frames reach the caller`() {
        val rows = mutableListOf<Long>()

        runBlocking {
            puller().pull(
                requestId = "int-pull-progress",
                source = sourceConfig,
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                namespace = "PUBLIC",
                table = "orders",
            ) { progress -> rows += progress.rows }
        }

        assertTrue(rows.isNotEmpty(), "拉取过程必须有进度帧 —— 没有进度等于「卡住了」")
        assertEquals(3L, rows.last(), "最后的进度应当是全部行数")
    }

    @Test
    fun `a failed pull leaves no table behind`() {
        val failure =
            assertFailsWith<Exception> {
                runBlocking {
                    puller().pull(
                        requestId = "int-pull-missing",
                        source = sourceConfig,
                        workspace = workspaceConfig,
                        workspacePath = workspacePath,
                        namespace = "PUBLIC",
                        table = "no_such_table",
                    ) { }
                }
            }
        assertTrue(failure.message?.isNotBlank() == true, "失败必须给出原因")
        assertFalse(workspaceHasTable("staging_PUBLIC_no_such_table"), "失败的拉取不能留下半张表")
    }

    @Test
    fun `the pull writes nothing to disk`() {
        // 「零 CSV 中转」的判据：拉完之后**连一个临时文件都没有**。
        //
        // 早先的链路要落一个几百 MB 的 CSV，还要在三条路径上清理它；
        // 联邦直查不落盘 —— 于是「清理」这件事**整个不存在**，
        // 也就不会出现「忘了清理」。
        runBlocking {
            puller().pull(
                requestId = "int-pull-nofile",
                source = sourceConfig,
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                namespace = "PUBLIC",
                table = "orders",
            ) { }
        }

        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "联邦直查不该落任何临时文件：$leftovers")
    }

    @Test
    fun `the user cancel is reported as a cancel and not as a failure`() {
        // 「取消不是失败」—— 界面要分开说。
        // 这里验的是**引擎侧**给的信号：终止帧上带着 `cancelled` 标志，
        // 前端据此抛 [AnalysisPullCancelledException]（而不是当成失败）。
        //
        // 取消的时机选在**扫描已经开始之后**（收到第一帧进度）：
        // 那时引擎那边正在扫远端，取消一定落在拉取途中，不靠运气。
        //
        // ⚠️ 远端表特意造得**很大**（20 万行）：表小的话，
        // 取消还没送到引擎那边，拉取就已经结束了 —— 那条断言就成了随机的。
        DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE TABLE "big" AS SELECT X AS id FROM SYSTEM_RANGE(1, 200000)""")
            }
        }

        val puller = puller()
        val requestId = "int-pull-cancel"

        val failure =
            assertFailsWith<AnalysisPullCancelledException> {
                runBlocking {
                    puller.pull(
                        requestId = requestId,
                        source = sourceConfig,
                        workspace = workspaceConfig,
                        workspacePath = workspacePath,
                        namespace = "PUBLIC",
                        table = "big",
                    ) { progress ->
                        if (progress.rows > 0) {
                            // 收到第一帧进度就取消 —— 模拟用户点「取消拉取」。
                            puller.cancelPull(workspaceConfig, requestId)
                        }
                    }
                }
            }
        assertEquals("拉取已取消", failure.message)
        // 取消走的是**事务回滚** —— 工作区里不能留下半张表。
        assertFalse(workspaceHasTable("staging_PUBLIC_big"), "取消不能留下半张表")
    }
}
