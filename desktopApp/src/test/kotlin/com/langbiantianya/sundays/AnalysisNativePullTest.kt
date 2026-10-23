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
import kotlin.test.assertTrue

/**
 * **原生扫描器**（可选加速）的端到端测试 —— 真 MySQL → 真 DuckDB 工作区。
 *
 * ## 怎么证明「走的是哪条路」
 *
 * 两条路的**类型映射不一样**，而这正好是一个**可观测**的差别（实测，见下表）。
 *
 * ## ⚠️ `TINYINT(1)` **不能**用来区分（踩过的坑）
 *
 * 早先这个测试用 `TINYINT(1)` 当判据，结论是「原生把它落成 BOOLEAN」——
 * **可那是错的**：MySQL Connector/J 把 `TINYINT(1)` 报成 `Types.BIT`
 * （`tinyInt1isBit` 默认开），于是**两条路都是 BOOLEAN**，判据根本不成立。
 *
 * 真正能区分的是**宽度**：原生扫描器用 DuckDB 的原生类型，
 * 而我们的映射一律「装得下就行」（`SMALLINT` → `INTEGER`）。
 *
 * | 远端列 | JDBC 侧看到 | 原生扫描器 | 纯 Java 表函数（我们） |
 * |---|---|---|---|
 * | `TINYINT(1)` | `BIT` | `BOOLEAN` | `BOOLEAN` ← **不区分** |
 * | `SMALLINT` | `SMALLINT` | `SMALLINT` | `INTEGER` ← **判据** |
 * | `INT UNSIGNED` | `INT`（无符号） | `UINTEGER` | `BIGINT` |
 * | `BIGINT UNSIGNED` | `BIGINT`（无符号） | `UBIGINT` | `DECIMAL(20,0)` |
 *
 * 所以判据落在 `SMALLINT` 那一列上 ——
 * 「SMALLINT」= 走了原生，「INTEGER」= 走了纯 Java 表函数。
 * 这比断言「某个内部标志」实在：它断言的是**用户拿到的东西**。
 *
 * ## 这一轮验的三条
 *
 * 1. 显式打开时，原生扫描器**真的**被用上了（类型是官方的 `SMALLINT`）；
 * 2. **默认没打开**时走的是纯 Java 表函数（类型是我们的 `INTEGER`）——
 *    「装没装扩展」不该隐式决定用户拿到的类型；
 * 3. 拉完之后工作区里**没有残留的远端挂载** —— 挂着的那条目录记录里
 *    **含明文密码**（`duckdb_databases()` 的 `path` 列）。
 */
class AnalysisNativePullTest {
    private lateinit var tempDir: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var workspaceConfig: ConnectionConfig
    private lateinit var workspacePath: Path
    private var database: String = ""

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sundays-native-pull").toFile()
        MySqlSmoke.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        database = MySqlSmoke.provision(tempDir)
        MySqlSmoke.direct(database).use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS `native_probe`")
                // `SMALLINT` 是**唯一能区分两条路**的宽度（见类注释的类型对照表）。
                st.execute("CREATE TABLE `native_probe` (id INT NOT NULL, level SMALLINT, label VARCHAR(32))")
                st.execute("INSERT INTO `native_probe` VALUES (1,10,'a'),(2,20,'b')")
            }
        }

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
        System.clearProperty(NATIVE_PROPERTY)
        scope.cancel()
        runCatching {
            MySqlSmoke.direct(database).use { c ->
                c.createStatement().use { st -> st.execute("DROP TABLE IF EXISTS `native_probe`") }
            }
        }
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    private fun pull(): Pair<String, Long> =
        runBlocking {
            val result =
                EngineAnalysisPuller(engine).pull(
                    requestId = "native-pull",
                    source = sourceConfig,
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    namespace = database,
                    table = "native_probe",
                ) { }
            result.stagingTable to result.rowsLanded
        }

    /** 落点表里那一列的 DuckDB 类型。 */
    private fun columnType(
        stagingTable: String,
        column: String,
    ): String =
        runBlocking {
            val result =
                runAnalysisQuery(
                    engine,
                    workspaceConfig,
                    """SELECT typeof("$column") FROM "$stagingTable" LIMIT 1""",
                )
            assertTrue(!result.failed, "查列类型不该失败：${result.error}")
            val rows = result.rows
            val firstRow = rows.firstOrNull()
            val cell = firstRow?.firstOrNull()
            cell.orEmpty()
        }

    /** 工作区里还挂着几个远端。 */
    private fun attachedRemoteCount(): Long =
        runBlocking {
            val result =
                runAnalysisQuery(
                    engine,
                    workspaceConfig,
                    "SELECT count(*) FROM duckdb_databases() WHERE database_name LIKE 'sundays_remote_%'",
                )
            assertTrue(!result.failed, "查挂载点不该失败：${result.error}")
            val rows = result.rows
            val firstRow = rows.firstOrNull()
            val cell = firstRow?.firstOrNull()
            cell?.toLongOrNull() ?: -1L
        }

    @Test
    fun `the native scanner is used when it is enabled and available`() {
        assumeTrue("内网 MySQL 不可达，跳过（内网地址，换台机器就不存在）", MySqlSmoke.reachable())
        assumeTrue("mysql_scanner 扩展加载不了，跳过（驱动里没带这个扫描器）", nativeScannerLoadable())

        System.setProperty(NATIVE_PROPERTY, "1")

        val (staging, rows) = pull()

        assertEquals(2L, rows, "行数必须对得上")
        // 原生扫描器用 DuckDB 的**原生**类型 —— `SMALLINT` 还是 `SMALLINT`。
        // 这就是「走的确实是原生那条路」的证据。
        assertEquals(
            "SMALLINT",
            columnType(staging, "level"),
            "原生扫描器保留 SMALLINT；落成 INTEGER 就说明走的不是原生",
        )
    }

    @Test
    fun `the native scanner is not used unless it is turned on`() {
        assumeTrue("内网 MySQL 不可达，跳过（内网地址，换台机器就不存在）", MySqlSmoke.reachable())

        // 不开开关 → 走纯 Java 表函数。
        //
        // 这一条锁的是「跨机器一致」的承诺：**同一张表**，
        // 装没装扩展、拉不拉得到原生，都不该改变用户拿到的类型。
        val (staging, rows) = pull()

        assertEquals(2L, rows, "行数必须对得上")
        assertEquals(
            "INTEGER",
            columnType(staging, "level"),
            "默认走的是纯 Java 表函数，类型应当是我们的映射（SMALLINT → INTEGER）",
        )
    }

    @Test
    fun `the remote is detached after the native pull`() {
        assumeTrue("内网 MySQL 不可达，跳过（内网地址，换台机器就不存在）", MySqlSmoke.reachable())
        assumeTrue("mysql_scanner 扩展加载不了，跳过（驱动里没带这个扫描器）", nativeScannerLoadable())

        System.setProperty(NATIVE_PROPERTY, "1")

        pull()

        // 挂着的远端会留在 DuckDB 的目录表里，而那条记录的 `path` **含明文密码** ——
        // 挂着不摘，用户在编辑器里 `SELECT * FROM duckdb_databases()` 就能看见。
        //
        // 顺带这条也钉住了「DETACH 必须在提交之后」：DETACH 若落在事务里，
        // DuckDB 会中止事务 → 提交落空 → **整张表消失**（症状是「拉取报成功、
        // 工作区里查不到表」）。所以这个断言和上一条「表还在」是同一个故事的两面。
        assertEquals(
            0L,
            attachedRemoteCount(),
            "拉完之后不该残留远端挂载 —— 目录记录里含明文密码",
        )
    }

    /** `mysql_scanner` 能不能加载 —— 加载不了就 skip（不是失败）。 */
    private fun nativeScannerLoadable(): Boolean =
        runCatching {
            val probe = Files.createTempFile("sundays-ext-probe", ".duckdb").toFile()
            probe.delete()
            java.sql.DriverManager
                .getConnection("jdbc:duckdb:${probe.absolutePath}")
                .use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("LOAD mysql_scanner")
                    }
                }
            true
        }.getOrDefault(false)

    private companion object {
        const val NATIVE_PROPERTY = "sundays.duckdb.nativeExtension"
    }
}
