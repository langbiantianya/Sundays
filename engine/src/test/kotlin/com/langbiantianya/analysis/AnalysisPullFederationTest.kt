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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「拉取 = DuckDB 联邦直查远端」的端到端测试。
 *
 * ## 为什么用 H2 当「远端」
 *
 * 这条路由的本质是「DuckDB 通过一条 **JDBC 连接**读远端」——
 * 所以**任何**有 JDBC 驱动的库都能当远端，H2 正好是最容易准备的一个：
 * 内存库、不占端口、每次测试全新。
 *
 * 真正的 MySQL 另有 `desktopApp` 的冒烟测试兜着（那边要验的是**真 MySQL 的
 * 类型与引用形式**，H2 验不了那部分）。
 *
 * ## 这一轮最要紧的两条
 *
 * 1. **远端表真的落进了工作区** —— 行数、内容都要对；
 * 2. **失败不留半张表** —— 而且**旧表还在**（事务回滚）。
 */
class AnalysisPullFederationTest : H2Fixture() {
    private lateinit var workspaceFile: File

    @BeforeEach
    fun registerDuckDb() {
        // 工作区是 DuckDB —— 联邦查询的那一端。
        DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
        workspaceFile = Files.createTempFile("sundays-pull-federation", ".duckdb").toFile()
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
        id = "pull-federation"
        category = Category.ANALYSIS
        action = Action.PULL_TABLE
        connection = workspaceConfig()
        analysisRequest =
            analysisRequest {
                pull =
                    analysisPullRequest {
                        remote = this@AnalysisPullFederationTest.config
                        this.namespace = namespace
                        this.table = table
                        this.stagingTable = stagingTable
                    }
            }
    }

    /**
     * 直接问工作区（真 DuckDB 文件）一张表的行数。
     *
     * 走 **PoolManager** 而不是自己 `DriverManager.getConnection` ——
     * DuckDB 的 JDBC 不允许同一个文件用**不同配置**再开一条连接，
     * 而池里的连接带着 Hikari 传下来的属性。问同一份数据就该用同一条路。
     */
    private fun workspaceRows(
        stagingTable: String,
    ): Long =
        PoolManager
            .getConnection(workspaceConfig())
            .use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT count(*) FROM \"$stagingTable\"").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

    /** 工作区里有没有这张表。 */
    private fun workspaceHasTable(
        tableName: String,
    ): Boolean =
        PoolManager
            .getConnection(workspaceConfig())
            .use { conn ->
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery(
                            "SELECT count(*) FROM duckdb_tables() WHERE table_name = '$tableName'",
                        ).use { rs ->
                            rs.next()
                            rs.getLong(1) > 0
                        }
                }
            }

    @Test
    fun `a remote table lands in the workspace through a federated scan`() =
        runBlocking {
            // 名字**加引号**建：H2 把未加引号的标识符折成大写，
            // 而树里给出的表名是目录里的**原样**（`information_schema` 怎么说就怎么传），
            // 所以测试也得用「原样小写」建表 —— 否则验的是大小写规则，不是联邦扫描。
            executeUpdate("""CREATE TABLE "orders" (id INT, label VARCHAR(32))""")
            executeUpdate("""INSERT INTO "orders" VALUES (1,'a'),(2,'b'),(3,'c')""")

            val responses = RequestDispatcher.dispatch(pullRequest("PUBLIC", "orders", "staging_test_orders")).toList()
            val last = responses.last()

            assertTrue(last.success, "拉取必须成功：${last.error}")
            assertEquals(3, last.analysis.pull.rows, "落地行数必须对得上")
            assertEquals("staging_test_orders", last.analysis.pull.stagingTable)
            assertEquals(3L, workspaceRows("staging_test_orders"), "工作区里那张表必须真的有 3 行")
        }

    @Test
    fun `the scanned rows keep their values`() =
        runBlocking {
            executeUpdate("""CREATE TABLE "kv" (k VARCHAR(16), v INT)""")
            executeUpdate("""INSERT INTO "kv" VALUES ('x', 10), ('y', 20)""")

            val last =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "kv", "staging_test_kv"))
                    .toList()
                    .last()
            assertTrue(last.success, "拉取必须成功：${last.error}")

            val values =
                PoolManager
                    .getConnection(workspaceConfig())
                    .use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt
                                .executeQuery("SELECT k, v FROM \"staging_test_kv\" ORDER BY k")
                                .use { rs ->
                                    buildList {
                                        while (rs.next()) {
                                            add(rs.getString(1) to rs.getInt(2))
                                        }
                                    }
                                }
                        }
                    }
            assertEquals(listOf("x" to 10, "y" to 20), values, "值必须一字不差地搬过来")
        }

    @Test
    fun `a failed pull leaves no table behind`() =
        runBlocking {
            val last =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "no_such_table", "staging_missing"))
                    .toList()
                    .last()

            assertFalse(last.success, "拉一张不存在的表必须失败")
            assertTrue(last.error.isNotBlank(), "失败必须给出原因")
            // 失败之后**不能**留下半张表。
            assertFalse(workspaceHasTable("staging_missing"), "失败的拉取不能留下半张表")
        }

    @Test
    fun `a failed re-pull keeps the old table`() =
        runBlocking {
            executeUpdate("""CREATE TABLE "old" (id INT)""")
            executeUpdate("""INSERT INTO "old" VALUES (7)""")

            // 第一次：成功
            val ok =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "old", "staging_re"))
                    .toList()
                    .last()
            assertTrue(ok.success, "第一次拉取必须成功：${ok.error}")

            // 第二次：源表没了 → 失败，但**旧表还在**（事务回滚）
            executeUpdate("""DROP TABLE "old"""")
            val failed =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "old", "staging_re"))
                    .toList()
                    .last()
            assertFalse(failed.success, "第二次拉取（源表没了）必须失败")

            assertEquals(1L, workspaceRows("staging_re"), "失败的重新拉取不能把旧表弄丢 —— 事务回滚")
        }

    @Test
    fun `the same table can be pulled twice`() =
        runBlocking {
            executeUpdate("""CREATE TABLE "twice" (id INT)""")
            executeUpdate("""INSERT INTO "twice" VALUES (1),(2)""")

            val first =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "twice", "staging_twice"))
                    .toList()
                    .last()
            assertTrue(first.success, "第一次拉取必须成功：${first.error}")
            assertEquals(2, first.analysis.pull.rows, "第一次的行数")

            // ## 为什么这条用例必须存在
            //
            // 表函数注册在 DuckDB **实例**上，**活得比一次拉取久**。
            // 而它闭包里的连接在第一次拉取结束时就归还连接池了 ——
            // 于是「同一张表拉第二次」会复用那个**过期**的闭包，
            // 报 `Connection is closed`（实测）。
            //
            // 症状极具迷惑性：第一次好好的，第二次必失败，
            // 而失败信息指向「连接」，与「拉了两次同一张表」毫无关系。
            val second =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "twice", "staging_twice"))
                    .toList()
                    .last()
            assertTrue(second.success, "第二次拉取必须成功：${second.error}")
            assertEquals(2, second.analysis.pull.rows, "第二次的行数")
            assertEquals(2L, workspaceRows("staging_twice"), "工作区里那张表必须真的有 2 行")
        }

    @Test
    fun `a missing namespace or table is rejected`() =
        runBlocking {
            val noNamespace =
                RequestDispatcher
                    .dispatch(pullRequest("", "orders", "staging_x"))
                    .toList()
                    .last()
            assertFalse(noNamespace.success, "缺命名空间必须被拒")
            assertTrue(noNamespace.error.contains("namespace"), "错误要说清缺的是 namespace：${noNamespace.error}")

            val noTable =
                RequestDispatcher
                    .dispatch(pullRequest("PUBLIC", "", "staging_x"))
                    .toList()
                    .last()
            assertFalse(noTable.success, "缺表名必须被拒")
            assertTrue(noTable.error.contains("table"), "错误要说清缺的是 table：${noTable.error}")
        }
}
