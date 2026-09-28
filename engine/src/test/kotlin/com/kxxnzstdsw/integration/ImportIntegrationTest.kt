package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ImportRequest
import com.kxxnzstdsw.grpc.ImportRunRequest
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SystemRequest
import com.kxxnzstdsw.grpc.request
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `IMPORT.RUN_IMPORT` 端到端集成测试（v2.16）。
 *
 * 导入是「本地文件 → 批量 INSERT」的写路径，也是用户最容易把**生产数据导坏**的操作：
 * 一个表头解析错、一次取消没生效、一个坏行没被统计，都会让「看起来成功」的导入悄悄丢数据。
 * 因此这里钉住的不是「API 能不能调用」，而是四条真正会被用户感知的契约：
 *
 * 1. **帧协议**：进度帧（`stream=true, end=false`）先于终止帧（`end=true`），且每一帧都带
 *    请求 id —— 客户端靠它把并发的多个导入流拆开，缺 id 就没法路由。
 * 2. **真实解析**：CSV 引号内的逗号 / 双引号必须作为字段内容而不是分隔符；JSON Lines 的
 *    嵌套对象必须落库为紧凑 JSON 文本而不是被丢掉。朴素 `split(",")` 会静默错位数据。
 * 3. **落库结果**：所有断言都回到 JDBC 直接查库，而不是只信响应里的计数 —— 响应对了但库
 *    里没有，才是导入最危险的失败模式。
 * 4. **失败可见**：文件不存在必须干净失败（`success=false` + 带路径的错误）而不是空导入成功。
 *
 * 目标表都在测试里先 `executeUpdate` 建好；临时文件用 JUnit `@TempDir`，测试结束自动清理。
 */
class ImportIntegrationTest : H2Fixture() {

    /** 走 dispatcher 的 IMPORT 流式路由，返回完整帧序列。 */
    private suspend fun runImport(
        id: String = TestIds.next("r-imp"),
        sessionId: String = "",
        configure: ImportRunRequest.Builder.() -> Unit,
    ): List<Response> {
        val request = request {
            this.id = id
            category = Category.IMPORT
            action = Action.RUN_IMPORT
            connection = config
            this.sessionId = sessionId
            importRequest = ImportRequest.newBuilder()
                .setRunImport(ImportRunRequest.newBuilder().apply(configure).build())
                .build()
        }
        return RequestDispatcher.dispatch(request).toList()
    }

    /** 派发一条 SYSTEM 事务命令（BEGIN / COMMIT / ROLLBACK）。 */
    private suspend fun dispatchSystem(action: Action, sessionId: String = ""): Response =
        RequestDispatcher.dispatch(
            request {
                id = TestIds.next("r-sys")
                category = Category.SYSTEM
                this.action = action
                connection = config
                this.sessionId = sessionId
                systemRequest = SystemRequest.newBuilder().setSessionId(sessionId).build()
            }
        ).toList().single()

    private fun file(dir: Path, name: String, content: String): File =
        dir.resolve(name).toFile().apply { writeText(content) }

    private fun rowCount(table: String): Int =
        executeQuerySingle("SELECT COUNT(*) FROM $table")!!.toInt()

    @Test
    fun `CSV with header imports rows including quoted commas and escaped quotes`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_csv (id INT, name VARCHAR(50), note VARCHAR(200))")
        val csv = file(
            dir, "people.csv",
            "id,name,note\n" +
                "1,Alice,\"hello, world\"\n" +
                "2,Bob,\"said \"\"hi\"\" and, left\"\n" +
                "3,Carol,plain\n"
        )

        val frames = runImport {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_csv"
        }

        val terminal = frames.last()
        assertTrue(terminal.end, "最后一帧必须是终止帧（end=true）")
        assertTrue(terminal.success, "导入应成功：${terminal.error}")
        assertTrue(terminal.hasImport(), "终止帧必须携带 import.result")

        val result = terminal.import.result
        assertTrue(result.success)
        assertEquals(3L, result.rowsRead, "表头行不计入 rowsRead")
        assertEquals(3L, result.rowsInserted)
        assertEquals(0L, result.rowsFailed)

        // 回到 JDBC 验证真实数据 —— 引号内的逗号/双引号必须原样保留
        assertEquals(3, rowCount("import_csv"))
        assertEquals("hello, world", executeQuerySingle("SELECT note FROM import_csv WHERE id = 1"))
        assertEquals("said \"hi\" and, left", executeQuerySingle("SELECT note FROM import_csv WHERE id = 2"))
        assertEquals("Carol", executeQuerySingle("SELECT name FROM import_csv WHERE id = 3"))
    }

    @Test
    fun `progress frames precede the terminal frame and every frame carries the request id`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_progress (id INT PRIMARY KEY, name VARCHAR(50))")
        val csv = file(
            dir, "many.csv",
            buildString {
                append("id,name\n")
                for (i in 1..6) append("$i,name_$i\n")
            }
        )

        val requestId = TestIds.next("r-imp")
        val frames = runImport(requestId) {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_progress"
            batchSize = 2
        }

        assertTrue(frames.isNotEmpty(), "至少要有终止帧")
        assertTrue(frames.all { it.id == requestId }, "每一帧都必须携带请求 id")

        val progress = frames.filter { it.stream && !it.end }
        assertTrue(progress.isNotEmpty(), "小 batchSize 下应至少有一帧进度（终止前会补一帧）")
        progress.forEach { assertTrue(it.hasImportProgress(), "进度帧必须携带 importProgress") }
        val inserted = progress.map { it.importProgress.rowsInserted }
        assertEquals(inserted.sorted(), inserted, "rowsInserted 必须单调不减")

        assertTrue(frames.last().end, "最后一帧必须是 end=true")
        assertEquals(6, rowCount("import_progress"))
    }

    @Test
    fun `JSON_LINES keeps numbers booleans and nested objects`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_json (id INT, active BOOLEAN, meta VARCHAR(200))")
        val jsonl = file(
            dir, "rows.jsonl",
            """{"id":1,"active":true,"meta":{"a":1}}""" + "\n" +
                """{"id":2,"active":false,"meta":{"b":[1,2],"c":"x"}}""" + "\n"
        )

        val frames = runImport {
            filePath = jsonl.absolutePath
            format = "JSON_LINES"
            tableName = "import_json"
        }

        val terminal = frames.last()
        assertTrue(terminal.success, "导入应成功：${terminal.error}")
        assertEquals(2L, terminal.import.result.rowsInserted)
        assertEquals(0L, terminal.import.result.rowsFailed)

        assertEquals(2, rowCount("import_json"))
        // 布尔值确实按布尔落库（而不是被当字符串拒绝）
        assertEquals(1, executeQuerySingle("SELECT COUNT(*) FROM import_json WHERE active = TRUE")!!.toInt())
        assertEquals(1, executeQuerySingle("SELECT COUNT(*) FROM import_json WHERE active = FALSE")!!.toInt())
        // 嵌套对象以紧凑 JSON 文本落库，结构不被丢弃
        assertEquals("""{"a":1}""", executeQuerySingle("SELECT meta FROM import_json WHERE id = 1"))
        assertEquals("""{"b":[1,2],"c":"x"}""", executeQuerySingle("SELECT meta FROM import_json WHERE id = 2"))
    }

    @Test
    fun `truncateFirst clears the table before loading`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_trunc (id INT, name VARCHAR(50))")
        executeUpdate("INSERT INTO import_trunc VALUES (99, 'stale'), (100, 'older')")
        assertEquals(2, rowCount("import_trunc"))

        val csv = file(dir, "fresh.csv", "id,name\n1,Alice\n2,Bob\n")

        val frames = runImport {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_trunc"
            truncateFirst = true
        }

        assertTrue(frames.last().success, "导入应成功：${frames.last().error}")
        assertEquals(2, rowCount("import_trunc"), "导入前必须清表，只保留导入的行")
        assertEquals(null, executeQuerySingle("SELECT name FROM import_trunc WHERE id = 99"), "旧行必须已被清掉")
        assertEquals("Alice", executeQuerySingle("SELECT name FROM import_trunc WHERE id = 1"))
    }

    /**
     * `ignoreErrors = true` 必须**跳过坏行并计入 `rowsFailed`**，好行照常落库。
     *
     * 这条契约踩过一个真实的坑：H2 的 `PreparedStatement.setString` 从不在绑定期做类型转换，
     * 类型错误要到 `executeBatch()` 才抛。若只在 `bindRow()` / `addBatch()` 外面 `catch`，
     * `rowsFailed` 永远是 0，而整个导入会因为一次 `executeBatch()` 异常被判死刑 ——
     * 用户显式打开了「容忍坏行」却什么都没容忍到。
     *
     * 因此 `ignoreErrors` 路径改为**逐行 `executeUpdate`**：批量里一行失败会让整批无法归因，
     * 且 PostgreSQL 会把失败语句所在的隐式事务整体作废，「整批失败后逐行重试」只会连锁报错。
     * 代价是吞吐下降 —— 这是用户主动选择容忍坏行时的合理取舍。
     */
    @Test
    fun `ignoreErrors skips bad rows and counts them`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_skip (id INT, name VARCHAR(50))")
        // 中间一行 id 不是数字 —— H2 在写入时必然报类型转换错误
        val csv = file(dir, "dirty.csv", "id,name\n1,Alice\nnot-a-number,Bob\n3,Carol\n")

        val frames = runImport {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_skip"
            ignoreErrors = true
        }

        val terminal = frames.last()
        assertTrue(terminal.end)
        assertTrue(terminal.success, "坏行应被跳过而不是让整个导入失败：${terminal.error}")
        assertEquals(3L, terminal.import.result.rowsRead)
        assertEquals(2L, terminal.import.result.rowsInserted)
        assertEquals(1L, terminal.import.result.rowsFailed, "坏行必须被计数，否则数据丢失不可见")
        // 坏行的根因要回传给调用方 —— success=true 但 error 里有摘要，
        // 前端据此提示「完成，但有 1 行被跳过」
        assertTrue(
            terminal.import.result.error.contains("row 3", ignoreCase = true) ||
                terminal.import.result.error.contains("conversion", ignoreCase = true),
            "应说明被跳过的行或原因：${terminal.import.result.error}",
        )

        assertEquals(2, rowCount("import_skip"), "好行必须落库，坏行不得入库")
        assertEquals("1", executeQuerySingle("SELECT COUNT(*) FROM import_skip WHERE id = 1"))
        assertEquals("1", executeQuerySingle("SELECT COUNT(*) FROM import_skip WHERE id = 3"))
    }

    /**
     * 默认策略是**遇错即停**：坏行让导入失败，而不是静默跳过。
     *
     * 注意这里**不断言「零残留」**：非事务路径下走的是 `addBatch`/`executeBatch`，
     * 而 H2 对批次元素是逐个执行的 —— 坏行之前已经成功的元素会留在表里。
     * 想要「要么全成功、要么全不写」的原子性，必须把导入放进事务会话
     * （见下一个用例）—— 这也正是事务功能存在的意义。
     */
    @Test
    fun `without ignoreErrors a bad row aborts the whole import`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_strict (id INT, name VARCHAR(50))")
        val csv = file(dir, "dirty-strict.csv", "id,name\n1,Alice\nnot-a-number,Bob\n3,Carol\n")

        val frames = runImport {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_strict"
            // batchSize = 1 让「遇错即停」可精确观察：批次一大，坏行所在批次里的
            // 后续行早已随 addBatch 一起提交，失败点会被批边界掩盖。
            batchSize = 1
        }

        val terminal = frames.last()
        assertTrue(terminal.end)
        assertFalse(terminal.success, "默认策略是遇错即停，不能静默容忍")
        assertTrue(terminal.error.isNotBlank(), "必须暴露根因")
        // 坏行之后的行不得写入：遇错即停意味着检测到失败就停止读文件
        assertEquals(null, executeQuerySingle("SELECT name FROM import_strict WHERE id = 3"))
        // 坏行之前已成功提交的行保留（非事务路径不保证原子性，见下方事务用例）
        assertEquals("Alice", executeQuerySingle("SELECT name FROM import_strict WHERE id = 1"))
    }

    /**
     * 事务会话里导入失败 → ROLLBACK 后一行都不留。
     *
     * 这是「非事务导入会留下半截数据」的正确答案：把导入包进 `SYSTEM.BEGIN` / `ROLLBACK`。
     * 跨能力组合（导入 × 事务）必须被钉住 —— 单独看两个功能都对，组合起来却不原子的情况
     * 正是数据事故的典型来源。
     */
    @Test
    fun `a failed import inside a transaction rolls back completely`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_atomic (id INT, name VARCHAR(50))")
        val csv = file(dir, "atomic.csv", "id,name\n1,Alice\nnot-a-number,Bob\n")

        val begun = dispatchSystem(Action.BEGIN)
        assertTrue(begun.success, "BEGIN failed: ${begun.error}")
        val sessionId = begun.system.begin.sessionId

        val frames = runImport(sessionId = sessionId) {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_atomic"
        }
        assertFalse(frames.last().success, "坏行必须让导入失败")

        val rolledBack = dispatchSystem(Action.ROLLBACK, sessionId)
        assertTrue(rolledBack.system.rollback.rolledBack)

        assertEquals(0, rowCount("import_atomic"), "回滚后不得残留任何导入行")
    }

    @Test
    fun `a missing file fails cleanly without inserting anything`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_missing (id INT, name VARCHAR(50))")
        val missing = dir.resolve("does-not-exist.csv").toFile()

        val frames = runImport {
            filePath = missing.absolutePath
            format = "CSV"
            tableName = "import_missing"
        }

        val terminal = frames.last()
        assertTrue(terminal.end)
        assertFalse(terminal.success, "文件不存在必须失败")
        assertTrue(
            terminal.error.contains(missing.absolutePath),
            "错误信息必须包含路径，便于前端回显：${terminal.error}",
        )
        assertTrue(terminal.hasImport(), "失败终止帧也必须携带 import.result")
        assertFalse(terminal.import.result.success)
        assertTrue(terminal.import.result.error.isNotBlank())

        assertEquals(0, rowCount("import_missing"), "失败导入不得插入任何行")
    }

    @Test
    fun `headerless CSV imports every row using the target table columns`(@TempDir dir: Path) = runBlocking<Unit> {
        executeUpdate("CREATE TABLE import_noheader (id INT, name VARCHAR(50), email VARCHAR(80))")
        // 第一行也是数据（1,Alice,...），不能被当作表头吃掉
        val csv = file(dir, "noheader.csv", "1,Alice,alice@example.com\n2,Bob,bob@example.com\n")

        val frames = runImport {
            filePath = csv.absolutePath
            format = "CSV"
            tableName = "import_noheader"
            hasHeader = false
        }

        val terminal = frames.last()
        assertTrue(terminal.success, "导入应成功：${terminal.error}")
        assertEquals(2L, terminal.import.result.rowsRead, "无表头时首行也是数据")
        assertEquals(2L, terminal.import.result.rowsInserted)

        assertEquals(2, rowCount("import_noheader"))
        // 值必须按目标表的列顺序落位
        assertEquals("Alice", executeQuerySingle("SELECT name FROM import_noheader WHERE id = 1"))
        assertEquals("alice@example.com", executeQuerySingle("SELECT email FROM import_noheader WHERE id = 1"))
        assertEquals("Bob", executeQuerySingle("SELECT name FROM import_noheader WHERE id = 2"))
    }
}
