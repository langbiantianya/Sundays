/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.dispatcher.RequestDispatcher
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.Request
import com.langbiantianya.grpc.Response
import com.langbiantianya.grpc.analysisListColumnsRequest
import com.langbiantianya.grpc.analysisListSourcesRequest
import com.langbiantianya.grpc.analysisListTablesRequest
import com.langbiantianya.grpc.analysisRequest
import com.langbiantianya.grpc.analysisRunQueryRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.LanguageScope
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.testutil.H2Fixture
import com.langbiantianya.testutil.RemoteLikeDialect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ANALYSIS` 四条路由的集成测试（主设计 §3.5）。
 *
 * ## 这些路由在分析模式里扮演什么角色
 *
 * 远端只做两件事：**列元数据**（这三条 LIST）与**被拉取**（走既有的
 * `EXPORT.RUN_EXPORT`）。真正的计算发生在本地 DuckDB 工作区上。
 *
 * ## 断言落在「结果是什么」上，不只落在「没报错」上
 *
 * 只断言「没报错」的话，一个永远返回空列表的实现也能过。所以每条用例都断言
 * **具体的内容**：命名空间里有东西、表名对得上、列名对得上、行数对得上。
 */
class AnalysisHandlerIntegrationTest : H2Fixture() {
    @BeforeEach
    fun fixLanguage() {
        // 固定语言：引擎的错误文案默认跟着**系统区域设置**走，
        // 而错误文案是要**原样上屏**的 —— 测试断言它，就得先把语言钉住。
        LanguageScope.setProcessLanguage(AppLanguage.ZH_HANS)
    }

    // ──────────────── LIST_SOURCES ────────────────

    @Test
    fun `list sources returns the schema namespaces of an embedded dialect`() =
        runBlocking {
            val response = dispatchOne(listSourcesReq())

            assertTrue(response.success, "列命名空间必须成功：${response.error}")
            val items = response.analysis.listSources.itemsList
            assertTrue(items.isNotEmpty(), "H2 至少有 PUBLIC 这个 schema，实际是空的")
            assertTrue(
                items.any { it.equals("PUBLIC", ignoreCase = true) },
                "命名空间里应当有 PUBLIC，实际是 $items",
            )
        }

    @Test
    fun `list sources on a dialect without schema support returns the database namespaces`() =
        runBlocking {
            DialectLoader.registerForTesting("RemoteLike", RemoteLikeDialect())

            val response = dispatchOne(listSourcesReq(driver = "RemoteLike"))

            assertTrue(response.success, "列命名空间必须成功：${response.error}")
            val items = response.analysis.listSources.itemsList
            // 远端方言没有第二级 schema —— 命名空间就是「库」，
            // 而 H2 的 catalog 就是配置里的那个库名。
            //
            // 比较**忽略大小写**：H2 会把未加引号的标识符转成大写
            // （`test_xxx` → `TEST_XXX`），而 MySQL 在 Linux 上表名/库名是大小写敏感的。
            // 引擎按数据库返回的**原样**返回（不擅自改写），所以断言只能忽略大小写。
            assertEquals(1, items.size, "库型方言应当只回一个命名空间，实际是 $items")
            assertTrue(
                items[0].equals(dbName, ignoreCase = true),
                "命名空间应当是配置里的那个库名（$dbName），实际是 ${items[0]}",
            )
        }

    @Test
    fun `list sources works in a read only session`() =
        runBlocking {
            // 分析模式连远端时带的就是 read_only = true —— 元数据必须照样读得到。
            // 这条用例守的是「L4 闸门没有把分析模式自己挡在门外」。
            val response = dispatchOne(listSourcesReq(readOnly = true))

            assertTrue(response.success, "只读会话里也该能列命名空间：${response.error}")
            val items = response.analysis.listSources.itemsList
            assertTrue(items.isNotEmpty(), "只读会话列出的命名空间不该是空的")
        }

    // ──────────────── LIST_TABLES ────────────────

    @Test
    fun `list tables returns the tables of a namespace`() =
        runBlocking {
            executeUpdate("CREATE TABLE orders (id INT, amount INT)")

            val response = dispatchOne(listTablesReq(namespace = "PUBLIC"))

            assertTrue(response.success, "列表必须成功：${response.error}")
            val items = response.analysis.listTables.itemsList
            val names = items.map { it.name }
            // 忽略大小写比较：H2 把未加引号的标识符转大写，MySQL 在 Linux 上又大小写敏感，
            // 引擎按数据库返回的原样返回（不擅自改写），断言只能这样比。
            assertTrue(
                names.any { it.equals("orders", ignoreCase = true) },
                "表清单里应当有 orders，实际是 $names",
            )
        }

    @Test
    fun `list tables rejects a blank namespace`() =
        runBlocking {
            val response = dispatchOne(listTablesReq(namespace = ""))

            assertFalse(response.success, "空命名空间必须被拒")
            assertTrue(response.error.contains("namespace"), "错误要说清缺的是 namespace：${response.error}")
        }

    // ──────────────── LIST_COLUMNS ────────────────

    @Test
    fun `list columns returns the columns of a table`() =
        runBlocking {
            executeUpdate("CREATE TABLE orders (id INT, amount INT)")

            val response = dispatchOne(listColumnsReq(namespace = "PUBLIC", table = "orders"))

            assertTrue(response.success, "列列必须成功：${response.error}")
            val items = response.analysis.listColumns.itemsList
            val names = items.map { it.name.uppercase() }.sorted()
            assertEquals(listOf("AMOUNT", "ID"), names, "列清单应当是这两列，实际是 $names")
        }

    // ──────────────── RUN_QUERY ────────────────

    @Test
    fun `run query returns the rows of a select`() =
        runBlocking {
            executeUpdate("CREATE TABLE orders (id INT, amount INT)")
            executeUpdate("INSERT INTO orders VALUES (1, 100), (2, 200), (3, 300)")

            val response = dispatchOne(runQueryReq("SELECT id, amount FROM orders ORDER BY id"))

            assertTrue(response.success, "查询必须成功：${response.error}")
            val result = response.analysis.runQuery
            assertEquals(3, result.rowsList.size, "行数应当是 3")
            val columnNames = result.columnsList.map { it.name.uppercase() }.sorted()
            assertEquals(listOf("AMOUNT", "ID"), columnNames, "列定义应当是这两列，实际是 $columnNames")
            assertFalse(result.truncated, "3 行不该报截断")
        }

    @Test
    fun `run query reports affected rows for a statement without a result set`() =
        runBlocking {
            // M4 的验收要求：工作区里 `CREATE TABLE tmp AS SELECT …` 要能成功。
            // 这条用例证明「没有结果集的语句」也走得通，而不是只支持 SELECT。
            val response = dispatchOne(runQueryReq("CREATE TABLE tmp_a (id INT)"))

            assertTrue(response.success, "建表必须成功：${response.error}")
            assertEquals(0, response.analysis.runQuery.affectedRows, "建表的 affected_rows 应当是 0（DDL 没有行数）")
            val rows = response.analysis.runQuery.rowsList
            assertTrue(rows.isEmpty(), "建表不该带回行")
        }

    @Test
    fun `run query is rejected on a dialect that is not embedded`() =
        runBlocking {
            DialectLoader.registerForTesting("RemoteLike", RemoteLikeDialect())

            val response = dispatchOne(runQueryReq("SELECT 1", driver = "RemoteLike"))

            assertFalse(response.success, "远端方言必须拒绝分析查询 —— 否则它就是绕过只读闸门的后门")
            assertTrue(
                response.error.contains("本地工作区"),
                "错误要明确说「只能跑在本地工作区」：${response.error}",
            )
        }

    @Test
    fun `run query truncates at the row limit and says so`() =
        runBlocking {
            // SYSTEM_RANGE 生成 1..5001 的整数 —— 正好比上限多一行。
            val response = dispatchOne(runQueryReq("SELECT X FROM SYSTEM_RANGE(1, 5001)"))

            assertTrue(response.success, "查询必须成功：${response.error}")
            val result = response.analysis.runQuery
            assertEquals(5000, result.rowsList.size, "应当只回上限那么多行")
            assertTrue(result.truncated, "超限必须显式告知截断 —— 界面靠它提示用户")
        }

    @Test
    fun `run query exactly at the row limit is not reported as truncated`() =
        runBlocking {
            // 边界：正好等于上限时**不该**报截断。
            // 判据写错的话，结果就会是「每次都显示『已截断』」，久而久之没人再信这个提示。
            val response = dispatchOne(runQueryReq("SELECT X FROM SYSTEM_RANGE(1, 5000)"))

            assertTrue(response.success, "查询必须成功：${response.error}")
            assertEquals(5000, response.analysis.runQuery.rowsList.size, "行数应当正好是上限")
            assertFalse(response.analysis.runQuery.truncated, "正好等于上限不该报截断")
        }

    @Test
    fun `run query rejects a blank statement`() =
        runBlocking {
            val response = dispatchOne(runQueryReq("   "))

            assertFalse(response.success, "空 SQL 必须被拒")
            assertTrue(response.error.contains("sql"), "错误要说清缺的是 sql：${response.error}")
        }

    // ──────────────── 请求构造与派发 ────────────────

    private fun listSourcesReq(
        driver: String = "H2",
        readOnly: Boolean = false,
    ): Request =
        request {
            id = "analysis-list-sources"
            category = Category.ANALYSIS
            action = Action.LIST_SOURCES
            connection = conn(driver, readOnly)
            analysisRequest = analysisRequest { listSources = analysisListSourcesRequest { } }
        }

    private fun listTablesReq(namespace: String): Request =
        request {
            id = "analysis-list-tables"
            category = Category.ANALYSIS
            action = Action.LIST_TABLES
            connection = conn("H2", false)
            analysisRequest =
                analysisRequest {
                    listTables = analysisListTablesRequest { this.namespace = namespace }
                }
        }

    private fun listColumnsReq(
        namespace: String,
        table: String,
    ): Request =
        request {
            id = "analysis-list-columns"
            category = Category.ANALYSIS
            action = Action.LIST_COLUMNS
            connection = conn("H2", false)
            analysisRequest =
                analysisRequest {
                    listColumns =
                        analysisListColumnsRequest {
                            this.namespace = namespace
                            this.table = table
                        }
                }
        }

    private fun runQueryReq(
        sql: String,
        driver: String = "H2",
    ): Request =
        request {
            id = "analysis-run-query"
            category = Category.ANALYSIS
            action = Action.RUN_QUERY
            connection = conn(driver, false)
            analysisRequest =
                analysisRequest {
                    runQuery = analysisRunQueryRequest { this.sql = sql }
                }
        }

    private fun conn(
        driver: String,
        readOnly: Boolean,
    ): ConnectionConfig =
        config
            .toBuilder()
            .setDriver(driver)
            .setReadOnly(readOnly)
            .build()

    private suspend fun dispatchOne(req: Request): Response {
        val responses = RequestDispatcher.dispatch(req).toList()
        assertNotNull(responses.lastOrNull(), "派发必须给出响应")
        return responses.last()
    }
}
