package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.engine.StatementRegistry
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.grpc.systemRequest
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 查询取消集成测试（v2.16）—— `SYSTEM.CANCEL` 对运行中的 Statement 调 `Statement.cancel()`。
 *
 * 取消这件事**只能端到端验证**：协程取消对阻塞的 JDBC 调用无效，上游 Flow 被取消只会挂起
 * 协程，数据库里那条语句照跑；唯一的证据是 `Statement.cancel()` 真的让数据库侧停了下来。
 * 因此这里不 mock、不看内部字段（唯一例外是 [StatementRegistry.activeCount]，因为「语句有没有
 * 被登记」本身就是取消能力的前置契约，没有别的可观测量），而是用一条**确实会跑很久**的查询：
 *
 * 1. 长 SELECT 经 dispatcher 在后台协程里执行时，请求会被登记 → 取消返回 `cancelled=true`
 *    → 原查询的终止帧变成 `success=false`。三条链在一起才说明取消真的生效，
 *    少任何一条都可能是「假取消」（返回 true 但查询照跑，或查询自己很快跑完了）。
 * 2. 取消不存在的请求、空请求 id 都必须**软失败**（`cancelled=false` + 可读 error，不抛异常），
 *    否则前端一个手误就能把整条控制通道打挂。
 * 3. 正常结束的请求必须**注销**自己的 Statement —— 泄漏会让后续同 id 的取消误伤一条已经
 *    结束（甚至已关闭）的语句，正是取消功能最危险的失败模式。
 */
class CancelIntegrationTest : H2Fixture() {

    /** 明确会跑上数秒的查询：两张 SYSTEM_RANGE 交叉连接，H2 无法把它优化成常量。 */
    private val longQuery = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 30000) a, SYSTEM_RANGE(1, 30000) b"

    private fun executeSqlRequest(id: String, sql: String): Request = request {
        this.id = id
        category = Category.SQL
        action = Action.EXECUTE
        connection = config
        sqlRequest = sqlRequest { execute = sqlExecuteRequest { this.sql = sql } }
    }

    private fun cancelRequest(id: String, targetRequestId: String): Request = request {
        this.id = id
        category = Category.SYSTEM
        action = Action.CANCEL
        connection = config
        systemRequest = systemRequest { this.targetRequestId = targetRequestId }
    }

    private suspend fun dispatchSingle(req: Request): Response =
        RequestDispatcher.dispatch(req).toList().single()

    /** 有界轮询直到语句被登记，返回是否在期限内观察到。 */
    private suspend fun awaitRegistered(timeoutMs: Long = 10_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (StatementRegistry.activeCount() == 0) delay(10)
            true
        } ?: false

    @Test
    fun `SYSTEM CANCEL interrupts a long running SELECT`() = runBlocking<Unit> {
        val targetId = "cancel-select-1"
        val frames = AtomicReference<List<Response>>(emptyList())

        val job = launch(Dispatchers.IO) {
            frames.set(RequestDispatcher.dispatch(executeSqlRequest(targetId, longQuery)).toList())
        }

        assertTrue(
            awaitRegistered(),
            "长查询执行期间 Statement 必须被登记到 StatementRegistry（否则 SYSTEM.CANCEL 无从命中）",
        )

        // 语句已登记 = 已进入 stmt.execute()；给执行线程一点时间把 executingCommand 挂上，
        // 让 H2 的 cancel() 能命中正在跑的 Command。缓存命中窗口是微秒级，这里只是稳态化。
        delay(100)

        val cancelResp = dispatchSingle(cancelRequest("cancel-req-1", targetId))

        assertTrue(cancelResp.success, "取消请求本身应成功路由: ${cancelResp.error}")
        assertTrue(cancelResp.hasSystem() && cancelResp.system.hasCancel(), "缺少 cancel 响应体")
        val cancel = cancelResp.system.cancel
        assertTrue(cancel.cancelled, "已登记的运行中请求必须被取消: ${cancel.error}")
        assertEquals(targetId, cancel.requestId)

        withTimeout(15_000) { job.join() }

        val collected = frames.get()
        assertTrue(collected.isNotEmpty(), "被取消的查询至少应产出一帧终止帧")
        val terminal = collected.last()
        assertFalse(terminal.success, "被取消的查询终止帧必须是失败（success=false）")
        assertTrue(
            terminal.error.isNotBlank(),
            "失败帧必须带非空 error（具体措辞由驱动决定，H2/DuckDB/SQLite 各不相同）",
        )
    }

    @Test
    fun `cancelling an unknown request id fails softly`() = runBlocking<Unit> {
        val resp = dispatchSingle(cancelRequest("cancel-req-unknown", "no-such-request-id"))

        assertTrue(resp.success, "路由本身不应抛异常: ${resp.error}")
        val cancel = resp.system.cancel
        assertFalse(cancel.cancelled, "不存在的请求不可能被取消")
        assertTrue(cancel.error.isNotBlank(), "应给出非空错误说明")
    }

    @Test
    fun `cancelling with a blank id reports the missing field`() = runBlocking<Unit> {
        val resp = dispatchSingle(cancelRequest("cancel-req-blank", ""))

        assertTrue(resp.success, "空 id 也应软失败而非抛异常: ${resp.error}")
        val cancel = resp.system.cancel
        assertFalse(cancel.cancelled)
        assertTrue(
            cancel.error.contains("target_request_id"),
            "错误信息应点名缺失字段 target_request_id，实际为: ${cancel.error}",
        )
    }

    @Test
    fun `a completed query does not leak its statement registration`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE cancel_leak (id INT)")
        executeUpdate("INSERT INTO cancel_leak VALUES (1), (2), (3)")

        val frames = RequestDispatcher.dispatch(
            executeSqlRequest("cancel-normal-1", "SELECT * FROM cancel_leak")
        ).toList()
        assertTrue(frames.isNotEmpty(), "正常查询应产出行帧与终止帧")
        assertTrue(frames.last().success, "正常查询的终止帧应成功: ${frames.last().error}")

        val drained = withTimeoutOrNull(5_000) {
            while (StatementRegistry.activeCount() != 0) delay(10)
            true
        } ?: false
        assertTrue(drained, "查询结束后 Statement 必须注销，实际仍有 ${StatementRegistry.activeCount()} 条残留")
    }
}
