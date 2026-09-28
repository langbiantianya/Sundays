package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.DataCreateRequest
import com.kxxnzstdsw.grpc.DataRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.SystemRequest
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.systemRequest
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.pool.TransactionManager
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 事务会话集成测试（v2.16）—— `SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`。
 *
 * 事务的前提是**同一批写操作落在同一条 JDBC 连接上**，而连接池每次 `getConnection`
 * 都可能换一条物理连接。因此这里要钉住的不是「有没有事务 API」，而是三条真实约束：
 *
 * 1. 会话内的写操作复用**同一条**连接（否则 `ROLLBACK` 撤不掉你以为写过的东西）；
 * 2. 未 COMMIT 的数据对**其他连接**不可见（隔离性），COMMIT 后可可见；
 * 3. 会话结束时连接**归还池中**且 `autocommit` 复位 —— 不归还就会把池容量耗光，
 *    而 `maximumPoolSize` 只有 5，泄漏几个会话之后整个连接就再也借不到连接了。
 *
 * 第 3 条尤其容易在实现里漏掉：会话连接如果被写路径顺手 `close()`，事务语义会**静默失效**
 * （后续语句落到另一条连接上，COMMIT 只提交了最后一条）。所以这里既验证回滚生效，
 * 也验证池没有被会话拖住。
 */
class TransactionIntegrationTest : H2Fixture() {

    private val tableName = "tx_users"

    private fun prepareTable() {
        executeUpdate("CREATE TABLE $tableName (id INT PRIMARY KEY, name VARCHAR(50))")
    }

    /** 走 dispatcher 的通用单条请求（非流式）。 */
    private suspend fun dispatch(request: Request) = RequestDispatcher.dispatch(request).toList().single()

    private fun beginRequest(id: String = TestIds.next("tx-begin")) = request {
        this.id = id
        category = Category.SYSTEM
        action = Action.BEGIN
        connection = config
        systemRequest = SystemRequest.getDefaultInstance()
    }

    private fun sessionCommand(
        action: Action,
        sessionId: String,
    ) = request {
        id = TestIds.next("tx-cmd")
        category = Category.SYSTEM
        this.action = action
        connection = config
        this.sessionId = sessionId
        systemRequest = systemRequest { this.sessionId = sessionId }
    }

    /** 在指定会话（sessionId 为空则无事务）插入一行。 */
    private fun insertRequest(rowId: Int, name: String, sessionId: String = "") = request {
        id = TestIds.next("tx-insert")
        category = Category.DATA
        action = Action.CREATE
        connection = config
        this.sessionId = sessionId
        dataRequest = DataRequest.newBuilder()
            .setCreate(
                DataCreateRequest.newBuilder()
                    .setTableName(tableName)
                    .putValues("id", rowId.toString())
                    .putValues("name", name)
                    .build()
            )
            .build()
    }

    @Test
    fun `rollback discards writes made inside the session`() = runBlocking {
        prepareTable()

        val begun = dispatch(beginRequest())
        assertTrue(begun.success, "BEGIN failed: ${begun.error}")
        val sessionId = begun.system.begin.sessionId
        assertTrue(sessionId.isNotBlank(), "BEGIN must return a session id")
        assertEquals("H2", begun.system.begin.driver)

        val written = dispatch(insertRequest(1, "alice", sessionId))
        assertTrue(written.success, "session write failed: ${written.error}")

        // 未提交：会话自身能看到（同一连接），但这里先从池里另借一条连接验证隔离性
        assertEquals(
            null,
            executeQuerySingle("SELECT name FROM $tableName WHERE id = 1"),
            "未 COMMIT 的数据不应对会话外的连接可见",
        )

        val rolledBack = dispatch(sessionCommand(Action.ROLLBACK, sessionId))
        assertTrue(rolledBack.system.rollback.rolledBack, "ROLLBACK must report success")

        assertEquals(
            null,
            executeQuerySingle("SELECT name FROM $tableName WHERE id = 1"),
            "回滚后该行必须不存在",
        )
        assertEquals(0, TransactionManager.activeCount(), "会话结束后不得残留")
    }

    @Test
    fun `commit persists writes made inside the session`() = runBlocking {
        prepareTable()

        val sessionId = dispatch(beginRequest()).system.begin.sessionId
        assertTrue(dispatch(insertRequest(7, "bob", sessionId)).success)

        val committed = dispatch(sessionCommand(Action.COMMIT, sessionId))
        assertTrue(committed.system.commit.committed, "COMMIT must report success")

        assertEquals("bob", executeQuerySingle("SELECT name FROM $tableName WHERE id = 7"))
        assertEquals(0, TransactionManager.activeCount())
    }

    @Test
    fun `session writes share one physical connection`() = runBlocking {
        prepareTable()

        val sessionId = dispatch(beginRequest()).system.begin.sessionId
        val fromPool = TransactionManager.get(sessionId)?.connection
        assertNotNull(fromPool, "session must hold a pinned connection")
        assertFalse(fromPool.autoCommit, "session connection must have autocommit disabled")

        // 两次写操作取到的必须是同一条连接 —— 这是事务语义的地基
        dispatch(insertRequest(1, "a", sessionId))
        dispatch(insertRequest(2, "b", sessionId))
        assertTrue(TransactionManager.get(sessionId)!!.connection === fromPool, "写操作不得换连接")

        dispatch(sessionCommand(Action.COMMIT, sessionId))
    }

    @Test
    fun `session commands on an unknown session fail softly`() = runBlocking {
        val bogus = "no-such-session-${System.nanoTime()}"

        val commit = dispatch(sessionCommand(Action.COMMIT, bogus))
        assertFalse(commit.system.commit.committed, "重复提交不应抛异常，只是 committed=false")
        assertTrue(commit.system.commit.error.isNotBlank(), "应说明会话不存在")

        val rollback = dispatch(sessionCommand(Action.ROLLBACK, bogus))
        assertFalse(rollback.system.rollback.rolledBack)
        assertTrue(rollback.system.rollback.error.isNotBlank())
    }

    @Test
    fun `writing with an unknown session id is rejected instead of silently auto-committing`() = runBlocking {
        prepareTable()

        val resp = dispatch(insertRequest(9, "ghost", "no-such-session-${System.nanoTime()}"))

        // 静默退化成自动提交是危险的：调用方以为在事务里，实际已经落库
        assertFalse(resp.success, "未知会话必须失败而不是静默自动提交")
        assertTrue(resp.error.isNotBlank())
        assertEquals(null, executeQuerySingle("SELECT name FROM $tableName WHERE id = 9"))
    }

    @Test
    fun `session info lists the active session and reports autocommit off`() = runBlocking {
        val sessionId = dispatch(beginRequest()).system.begin.sessionId

        val info = dispatch(sessionCommand(Action.SESSION_INFO, sessionId))
        assertTrue(info.success, "SESSION_INFO failed: ${info.error}")
        assertEquals(1, info.system.sessionInfo.activeSessions)

        val session = info.system.sessionInfo.sessionsList.single()
        assertEquals(sessionId, session.sessionId)
        assertFalse(session.autoCommit, "会话内 autocommit 必须为 false")
        assertEquals("H2", session.driver)
        assertTrue(session.startedAt > 0)

        assertEquals(1, dispatch(sessionCommand(Action.SESSION_INFO, "")).system.sessionInfo.activeSessions)

        dispatch(sessionCommand(Action.ROLLBACK, sessionId))
        assertEquals(0, dispatch(sessionCommand(Action.SESSION_INFO, "")).system.sessionInfo.activeSessions)
    }

    @Test
    fun `disconnect rolls back sessions before closing the pool`() = runBlocking {
        prepareTable()

        val sessionId = dispatch(beginRequest()).system.begin.sessionId
        assertTrue(dispatch(insertRequest(3, "carol", sessionId)).success)
        assertEquals(1, TransactionManager.activeCount())

        // 断开连接：会话必须被回滚并释放，否则钉住的连接会一直占着池容量
        val closed = PoolManager.close(config)

        assertTrue(closed, "expected the pool to be closed")
        assertEquals(0, TransactionManager.activeCount(), "断开连接后不得残留事务会话")
        assertEquals(
            null,
            executeQuerySingle("SELECT name FROM $tableName WHERE id = 3"),
            "未提交的会话数据必须被回滚",
        )
    }

    @Test
    fun `transactions still work after the pool is rebuilt`() = runBlocking {
        prepareTable()

        // 先建一个会话并断开，再重开会话 —— 验证关闭路径没有污染 TransactionManager
        PoolManager.close(config)
        assertEquals(0, TransactionManager.activeCount())

        val sessionId = dispatch(beginRequest()).system.begin.sessionId
        assertTrue(dispatch(insertRequest(4, "dave", sessionId)).success)
        assertTrue(dispatch(sessionCommand(Action.COMMIT, sessionId)).system.commit.committed)

        assertEquals("dave", executeQuerySingle("SELECT name FROM $tableName WHERE id = 4"))
    }
}
