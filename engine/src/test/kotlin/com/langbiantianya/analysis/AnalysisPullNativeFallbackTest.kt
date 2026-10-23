/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import com.langbiantianya.dialect.DuckDBDialect
import com.langbiantianya.dispatcher.RequestDispatcher
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.analysisPullRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.request
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「原生扫描器不可用时**降级**」的测试 —— H2 当远端（**不需要网络**）。
 *
 * ## 为什么用 H2 当「没有原生扫描器的远端」
 *
 * DuckDB 只为 MySQL / PostgreSQL 提供扫描器，H2 没有 —— 所以打开原生开关
 * 拉一张 H2 的表时，原生那条路**确定**走不通，必然落到纯 Java 表函数上。
 *
 * 这比「假设某个扩展没装」靠得住：装没装是**环境事实**（而且实测下来，
 * DuckDB JDBC 把这两个扫描器**静态链接**进了驱动本体，见
 * [NativeExtensionScanner] 的文件头），测试不能靠它。
 *
 * ## 这一轮验的两条
 *
 * 1. **原生那条路走不通时，用户照样能拉到数据** —— 看到的是「拉取成功」，
 *    不是「你的扩展没装」；
 * 2. **开关不改变结果** —— 对一个没有扫描器的方言，开与不开拉下来的
 *    行数、列类型**完全一样**。这正是「有没有原生能力不该隐式决定
 *    用户拿到的东西」那条承诺。
 */
class AnalysisPullNativeFallbackTest : H2Fixture() {
    private lateinit var workspaceFile: File

    @BeforeEach
    fun registerDuckDb() {
        DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
        workspaceFile = Files.createTempFile("sundays-pull-native-fallback", ".duckdb").toFile()
        workspaceFile.delete()
    }

    /** 工作区的连接配置 —— 与前端 `AnalysisSession.workspaceConnection()` 同形状。 */
    private fun workspaceConfig(): ConnectionConfig =
        connectionConfig {
            driver = "Duckdb"
            jdbcUrl = "jdbc:duckdb:${workspaceFile.absolutePath}"
            database = workspaceFile.absolutePath
        }

    private fun pullRequest(
        namespace: String,
        table: String,
        stagingTable: String,
    ) = request {
        id = "pull-native-fallback"
        category = Category.ANALYSIS
        action = Action.PULL_TABLE
        connection = workspaceConfig()
        analysisRequest =
            analysisRequest {
                pull =
                    analysisPullRequest {
                        remote = this@AnalysisPullNativeFallbackTest.config
                        this.namespace = namespace
                        this.table = table
                        this.stagingTable = stagingTable
                    }
            }
    }

    /** 落点表里那一列在 DuckDB 里的类型。 */
    private fun columnType(
        stagingTable: String,
        column: String,
    ): String =
        PoolManager
            .getConnection(workspaceConfig())
            .use { conn ->
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery(
                            "SELECT data_type FROM duckdb_columns() " +
                                "WHERE table_name = '$stagingTable' AND lower(column_name) = lower('$column')",
                        ).use { rs ->
                            if (rs.next()) rs.getString(1) else ""
                        }
                }
            }

    @Test
    fun `the pull falls back to the pure java scan when there is no native scanner`() =
        runBlocking {
            executeUpdate("""CREATE TABLE "tiny" (id INT, flag TINYINT)""")
            executeUpdate("""INSERT INTO "tiny" VALUES (1,1),(2,0)""")

            System.setProperty(NATIVE_PROPERTY, "1")
            try {
                val last =
                    RequestDispatcher
                        .dispatch(pullRequest("PUBLIC", "tiny", "staging_fallback"))
                        .toList()
                        .last()

                assertTrue(
                    last.success,
                    "原生那条路走不通也必须拉得下来 —— 用户看到的是「拉取成功」：${last.error}",
                )
                assertEquals(2, last.analysis.pull.rows, "落地行数必须对得上")
            } finally {
                System.clearProperty(NATIVE_PROPERTY)
            }

            // 降级后走的是**我们**的映射：TINYINT → INTEGER。
            assertEquals(
                "INTEGER",
                columnType("staging_fallback", "flag"),
                "降级到纯 Java 表函数后，类型应当是我们的映射（INTEGER）",
            )
        }

    @Test
    fun `turning the native scanner on does not change the result for a dialect without one`() =
        runBlocking {
            executeUpdate("""CREATE TABLE "same" (id INT, flag TINYINT)""")
            executeUpdate("""INSERT INTO "same" VALUES (1,1),(2,0)""")

            // 关（默认）
            System.clearProperty(NATIVE_PROPERTY)
            val off =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "same", "staging_off"))
                    .toList()
                    .last()

            // 开 —— 对 H2 来说原生那条路走不通，于是降级。
            System.setProperty(NATIVE_PROPERTY, "1")
            val on =
                try {
                    RequestDispatcher
                        .dispatch(pullRequest("PUBLIC", "same", "staging_on"))
                        .toList()
                        .last()
                } finally {
                    System.clearProperty(NATIVE_PROPERTY)
                }

            assertTrue(off.success, "默认路径必须成功：${off.error}")
            assertTrue(on.success, "打开开关后也必须成功：${on.error}")
            assertEquals(
                off.analysis.pull.rows,
                on.analysis.pull.rows,
                "开关不该改变落地的行数",
            )
            assertEquals(
                columnType("staging_off", "flag"),
                columnType("staging_on", "flag"),
                "开关不该改变列类型 —— 有没有原生能力不是用户该操心的事",
            )
        }

    private companion object {
        const val NATIVE_PROPERTY = "sundays.duckdb.nativeExtension"
    }
}
