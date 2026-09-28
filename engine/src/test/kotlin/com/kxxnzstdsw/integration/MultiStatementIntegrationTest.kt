package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.SqlExecuteResponse
import com.kxxnzstdsw.grpc.SqlRequest
import com.kxxnzstdsw.grpc.SqlSelectRowFrame
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.handlers.SqlEngineHandler
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多语句脚本执行集成测试（v2.16）—— `SqlExecuteRequest.multi_statement = true`。
 *
 * 脚本执行器真正难写的从来不是「按分号切开」，而是下面四条一旦写错就会**静默**造成
 * 半应用的数据库变更的契约：
 *
 * 1. **顺序 + 逐条回执**：每条语句的 `SqlStatementResult.index` 必须从 0 起连续，
 *    且 `affected_rows` 要如实反映该条语句影响的行数 —— 调用方靠它判断迁移/批处理
 *    实际做了什么，错位或漏报会让上层重放已经生效的语句。
 * 2. **遇错即停**：一条语句失败后其后的语句**绝不能继续执行**。这是「半应用迁移」的
 *    根源：脚本 runner 若吞掉错误继续跑，会在一张没建成的表上继续插数据、或把后续
 *    DROP 执行掉，事后无法区分哪一步成功。
 * 3. **词法级切分**：字符串字面量与注释内部的 `;` 不构成语句边界。若退化成
 *    `split(";")`，一条带 `'a;b'` 的 INSERT 会被拦腰截断成非法 SQL。
 * 4. **末句无分号也要执行**：脚本最后一条语句常常省略终结符，丢掉它就是静默少跑一步。
 *
 * 断言一律落在**可观测行为**上：响应字段、H2 里的真实表/行（直接 JDBC 复核），而不是
 * handler 内部状态。仅当响应本身是唯一观测面时才读 `SqlExecuteResponse` 的字段。
 */
class MultiStatementIntegrationTest : H2Fixture() {

    /** 构造一个 `multi_statement` 取值为 [multi] 的 SQL 执行请求。 */
    private fun sqlRequest(sql: String, multi: Boolean = true) = sqlExecuteRequest {
        this.sql = sql
        multiStatement = multi
    }

    /** 把 [SqlExecuteRequest] 包成走 dispatcher 的 SQL.EXECUTE 请求。 */
    private fun dispatchSql(replyId: String, execute: SqlExecuteRequest): Request = request {
        id = replyId
        category = Category.SQL
        action = Action.EXECUTE
        connection = config
        sqlRequest = SqlRequest.newBuilder().setExecute(execute).build()
    }

    /**
     * 直接调用 [SqlEngineHandler.execute] 跑多语句脚本（与 SqlEngineHandlerIntegrationTest 一致）。
     * 走 handler 而非 dispatcher 是因为脚本里含 SELECT 时 dispatcher 只发流式行帧、
     * 终帧不携带 `SqlExecuteResponse`，拿不到 `statements` 明细。此处只关心响应内容。
     */
    private suspend fun executeScript(
        script: String,
        onRow: (suspend (SqlSelectRowFrame) -> Unit)? = null,
    ): SqlExecuteResponse = SqlEngineHandler.execute(config, sqlRequest(script), onRow = onRow)

    @Test
    fun `mixed DDL and DML script executes in order and reports each statement`() = runBlocking {
        val streamedNames = mutableListOf<String?>()
        val resp = executeScript(
            """
            CREATE TABLE ms_mixed (id INT PRIMARY KEY, name VARCHAR(50));
            INSERT INTO ms_mixed VALUES (1, 'alice');
            INSERT INTO ms_mixed VALUES (2, 'bob');
            SELECT name FROM ms_mixed ORDER BY id
            """.trimIndent()
        ) { frame -> streamedNames += frame.row.valuesMap["NAME"]?.stringValue }

        // 逐条回执：索引 0 起连续，顺序即执行顺序
        assertEquals(4, resp.statementsCount)
        resp.statementsList.forEachIndexed { i, st ->
            assertEquals(i, st.index, "第 $i 条回执的 index 应为 $i")
            assertTrue(st.success, "第 $i 条语句应成功: ${st.error}")
        }

        // DDL 影响 0 行；两条 INSERT 各 1 行；SELECT 不改变行数
        assertEquals(0, resp.statementsList[0].affectedRows)
        assertEquals(1, resp.statementsList[1].affectedRows)
        assertEquals(1, resp.statementsList[2].affectedRows)
        assertEquals(0, resp.statementsList[3].affectedRows)

        // 顶层 affectedRows = 所有成功语句行数之和
        assertEquals(
            resp.statementsList.filter { it.success }.sumOf { it.affectedRows },
            resp.affectedRows,
            "affectedRows 必须是成功语句行数之和",
        )
        assertEquals(2, resp.affectedRows)

        // 直接 JDBC 复核数据库的真实状态
        assertTrue(tableExists("ms_mixed"), "脚本执行后表必须存在")
        assertEquals("2", executeQuerySingle("SELECT COUNT(*) FROM ms_mixed"))
        assertEquals<List<String?>>(listOf("alice", "bob"), streamedNames, "SELECT 应流式返回两行且保持顺序")
    }

    @Test
    fun `a failing statement stops the script and skips the remaining statements`() = runBlocking {
        val resp = executeScript(
            """
            CREATE TABLE ms_stop (id INT);
            INSERT INTO no_such_table VALUES (1);
            INSERT INTO ms_stop VALUES (99)
            """.trimIndent()
        )

        // 只有前两条语句留下回执 —— 失败语句之后的那条**根本没有执行**
        assertEquals(2, resp.statementsCount, "错误之后的语句不得被执行")

        val first = resp.statementsList[0]
        assertEquals(0, first.index)
        assertTrue(first.success, "建表语句应成功: ${first.error}")

        val failed = resp.statementsList[1]
        assertEquals(1, failed.index)
        assertFalse(failed.success, "非法 SQL 对应的回执必须标记失败")
        assertTrue(failed.error.isNotBlank(), "失败回执必须携带非空 error")

        assertEquals(0, resp.affectedRows, "脚本中断时只累计成功语句的行数")

        // 关键契约：第 2 条 INSERT 的效果必须缺席，否则就是半应用
        assertTrue(tableExists("ms_stop"), "第 0 条语句已成功，表必须存在")
        assertEquals("0", executeQuerySingle("SELECT COUNT(*) FROM ms_stop"), "第 2 条语句不得被执行")
    }

    @Test
    fun `semicolons inside string literals and comments do not split statements`() = runBlocking {
        executeUpdate("CREATE TABLE ms_lex (id INT PRIMARY KEY, name VARCHAR(50))")

        val resp = executeScript(
            """
            INSERT INTO ms_lex (id, name) VALUES (1, 'semi;colon');
            INSERT INTO ms_lex (id, name) VALUES (2, 'plain') -- trailing comment with ; inside
            """.trimIndent()
        )

        // 若切分退化成 split(";")，这里会得到更多（且非法）的语句
        assertEquals(2, resp.statementsCount, "字面量/注释内的分号不得切分语句")
        assertTrue(resp.statementsList.all { it.success }, "两条语句都应成功")

        // 字面量必须原样落库 —— 分号没有被当成语句边界吞掉
        assertEquals("semi;colon", executeQuerySingle("SELECT name FROM ms_lex WHERE id = 1"))
        // 行注释里的分号同样不切分，第二条 INSERT 正常执行
        assertEquals("plain", executeQuerySingle("SELECT name FROM ms_lex WHERE id = 2"))
    }

    @Test
    fun `trailing statement without a semicolon still executes`() = runBlocking {
        val resp = executeScript("CREATE TABLE ms_tail (id INT);INSERT INTO ms_tail VALUES (7)")

        assertEquals(2, resp.statementsCount, "末条语句无分号也必须被保留")
        assertTrue(resp.statementsList.all { it.success })
        assertEquals(1, resp.statementsList[1].affectedRows)

        assertEquals("7", executeQuerySingle("SELECT id FROM ms_tail"), "末条语句必须真的执行")
    }

    @Test
    fun `blank script fails through the dispatcher instead of reporting success`() = runBlocking {
        val blank = RequestDispatcher.dispatch(
            dispatchSql(TestIds.next("ms-blank"), sqlRequest("   \n  "))
        ).toList()
        assertFalse(blank.last().success, "空白脚本必须失败")
        assertTrue(blank.last().error.isNotBlank(), "空白脚本必须携带非空 error")

        // 只有分号/空白、没有任何可执行语句 —— 也必须失败而不是静默成功
        val noStatement = RequestDispatcher.dispatch(
            dispatchSql(TestIds.next("ms-nostmt"), sqlRequest(" ; ; "))
        ).toList()
        assertFalse(noStatement.last().success, "无语句脚本必须失败")
        assertTrue(noStatement.last().error.isNotBlank(), "无语句脚本必须携带非空 error")
    }

    @Test
    fun `multiStatement false keeps the single-statement behaviour`() = runBlocking {
        executeUpdate("CREATE TABLE ms_single (id INT PRIMARY KEY, name VARCHAR(50))")

        val frames = RequestDispatcher.dispatch(
            dispatchSql(
                TestIds.next("ms-single"),
                sqlRequest("INSERT INTO ms_single VALUES (1, 'solo')", multi = false),
            )
        ).toList()

        val terminal = frames.last()
        assertTrue(terminal.success, "单语句执行必须成功: ${terminal.error}")
        assertTrue(terminal.hasSql(), "单语句响应必须携带 SqlExecuteResponse")
        assertEquals(1, terminal.sql.execute.affectedRows)
        assertEquals(0, terminal.sql.execute.statementsCount, "关闭 multi_statement 不得填充 statements")

        assertEquals("solo", executeQuerySingle("SELECT name FROM ms_single WHERE id = 1"))
    }
}
