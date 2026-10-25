/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.google.protobuf.Value
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.generateProgressFrame
import com.langbiantianya.grpc.row
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 造数实时日志 —— 纯函数与状态层的契约。
 *
 * ## 为什么必须用**真引擎**
 *
 * 「引擎每条 INSERT 回一帧 `gen_progress_frame`」是**协议契约**，
 * 而「帧里的 `inserted` 是该脚本内的行号」是引擎的实现细节 ——
 * 两者都得用真引擎（H2 + 真 Lua 沙箱 + 真流式通道）验。
 *
 * 假的帧只能验「映射函数写对了」，验不了「引擎真的这么发」——
 * 而后者恰恰是这份日志能不能立住的前提。
 *
 * ## 三条硬契约
 *
 * 1. **每条 INSERT 一行** —— 不是每张表一行。否则出错时看不出是哪一行。
 * 2. **日志有界，丢掉的行数说出来** —— 静默截断等于伪造了一份完整的日志。
 * 3. **日志属于这一轮** —— 重新执行、切连接都要清空，
 *    否则两轮的行混在一起，看不出哪一行是哪一轮插的。
 */
class GenerateLogTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-generate-log-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // 显式注入 H2 方言 —— 其它测试类调 engine.close() 会清空方言注册表，
        // 而 IdbEngine 的 bootstrap 是全局幂等的（AtomicBoolean 只置一次 true）。
        DialectLoader.registerForTesting("H2", H2Dialect())

        jdbcUrl = "jdbc:h2:mem:genlog_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, total INT)")
            }
        }
    }

    @After
    fun tearDown() {
        try {
            PoolManager.closeAll()
        } catch (_: Exception) {
        }
        try {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } }
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    private fun newBrowser(): DatabaseBrowserState {
        val state = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        state.bindConnection(
            ConnectionConfig(
                id = "genlog-${System.nanoTime()}",
                name = "TestH2",
                dialect = DialectType.H2,
                username = "sa",
                password = "",
                jdbcUrl = jdbcUrl,
            ),
        )
        return state
    }

    /** 跑一轮造数并等它结束。 */
    private suspend fun DatabaseBrowserState.run(script: String) {
        selectPane(BrowserPane.GENERATE)
        currentGenerateScript()!!.editor.setText(script)
        executeGenerate()
        withTimeout(10_000) { while (generateRunning) delay(20) }
    }

    // =========================================================================
    // 纯函数 —— 列值怎么显示
    // =========================================================================

    @Test
    fun `log values are sorted by column name so the log does not jitter`() {
        // proto 的 map 字段不保证顺序，收到时的遍历序是哈希序 ——
        // 不排序的话同一份数据每次渲染出来的列序可能不同，日志在用户眼前会「跳来跳去」。
        val text =
            formatGenerateLogValues(
                linkedMapOf(
                    "total" to 30,
                    "id" to 7,
                    "name" to "Alice",
                ),
            )
        assertEquals("id=7, name=Alice, total=30", text)
    }

    @Test
    fun `a SQL NULL shows up as NULL rather than as an empty value`() {
        assertEquals("nick=NULL", formatGenerateLogValues(mapOf("nick" to null)))
    }

    @Test
    fun `whole doubles lose the fractional zero the engine leaves behind`() {
        // 引擎把整数塞进 double（JSON 化的副作用），显示层收拾干净 ——
        // 但 0.5 该留还是留，不能把小数一并砍掉。
        assertEquals("age=30", formatGenerateLogValues(mapOf("age" to 30.0)))
        assertEquals("ratio=0.5", formatGenerateLogValues(mapOf("ratio" to 0.5)))
    }

    @Test
    fun `overlong values are truncated instead of blowing up the line`() {
        val long = "x".repeat(GENERATE_LOG_VALUE_MAX_CHARS + 20)
        val text = formatGenerateLogValues(mapOf("blob" to long))
        assertEquals("blob=" + "x".repeat(GENERATE_LOG_VALUE_MAX_CHARS) + "…", text)
    }

    @Test
    fun `a progress frame maps to exactly one log entry`() {
        val frame =
            generateProgressFrame {
                table = "orders"
                inserted = 7
                scriptInserted = 7
                scriptIndex = 2
                totalScripts = 3
                data =
                    row {
                        values.put("id", Value.newBuilder().setNumberValue(101.0).build())
                        values.put("name", Value.newBuilder().setStringValue("Alice").build())
                    }
            }

        val entry = generateLogEntryOf(frame) { protoValueToAny(it) }

        assertEquals("orders", entry.table)
        assertEquals(7L, entry.rowNumber)
        assertEquals(2, entry.scriptIndex)
        assertEquals(3, entry.totalScripts)
        assertNull(entry.error)
        assertEquals(101.0, entry.values["id"])
        assertEquals("Alice", entry.values["name"])
    }

    /**
     * `google.protobuf.Value` → 展示用值 —— 与状态机里那份同义。
     *
     * 状态机那份是私有的，这里复制一份只为测「帧 → 行」这段映射；
     * 状态机自己的那份由状态层用例（真引擎）覆盖。
     */
    private fun protoValueToAny(v: Value): Any? =
        when (v.kindCase) {
            Value.KindCase.NULL_VALUE -> null
            Value.KindCase.NUMBER_VALUE -> v.numberValue
            Value.KindCase.STRING_VALUE -> v.stringValue
            Value.KindCase.BOOL_VALUE -> v.boolValue
            else -> v.toString()
        }

    // =========================================================================
    // 状态层 —— 真引擎
    // =========================================================================

    @Test
    fun `every inserted row shows up in the log`() =
        runBlocking {
            val state = newBrowser()
            state.run("for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end")

            assertNull(state.generateError, "造数不应失败: ${state.generateError}")
            assertEquals(3, state.generateLog.size, "每条 INSERT 一行日志")

            val first = state.generateLog.first()
            assertEquals("orders", first.table)
            assertEquals(1L, first.rowNumber, "行号是该脚本内的行数")
            assertEquals(1, first.scriptIndex, "引擎的 script_index 是 1-based")
            assertEquals(1, first.totalScripts)
            assertEquals("id=101, total=10", formatGenerateLogValues(first.values))

            // 行号逐行递增 —— 用户靠它判断「插到第几行了」。
            assertEquals(listOf(1L, 2L, 3L), state.generateLog.map { it.rowNumber })
        }

    @Test
    fun `the log carries the values that were actually written`() =
        runBlocking {
            val state = newBrowser()
            state.run("for i = 1, 2 do insert('orders', {id = 500 + i, total = i}) end")

            // 日志里的列值必须与库里那一行**对得上** —— 日志说写的和真写的不一致，
            // 那这份日志就是骗人的。
            val stored =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT id, total FROM orders ORDER BY id").use { rs ->
                            buildList {
                                while (rs.next()) add(rs.getInt(1) to rs.getInt(2))
                            }
                        }
                    }
                }
            assertEquals(listOf(501 to 1, 502 to 2), stored)

            assertEquals(
                listOf("id=501, total=1", "id=502, total=2"),
                state.generateLog.map { formatGenerateLogValues(it.values) },
            )
        }

    @Test
    fun `running the generation again starts with a fresh log`() =
        runBlocking {
            val state = newBrowser()
            state.run("for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end")
            assertEquals(3, state.generateLog.size)

            state.run("insert('orders', {id = 900, total = 9})")

            assertEquals(1, state.generateLog.size, "重新执行应当从空日志开始，而不是把两轮混在一起")
            assertEquals("id=900, total=9", formatGenerateLogValues(state.generateLog.single().values))
        }

    @Test
    fun `switching connections clears the log`() =
        runBlocking {
            val state = newBrowser()
            state.run("insert('orders', {id = 700, total = 7})")
            assertTrue(state.generateLog.isNotEmpty(), "前置条件：这一轮确实留下了日志")

            val newUrl = "jdbc:h2:mem:genlog2_${System.nanoTime()};DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(newUrl, "sa", "").use { conn ->
                conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
            }
            state.bindConnection(
                ConnectionConfig(
                    id = "genlog-2-${System.nanoTime()}",
                    name = "TestH2",
                    dialect = DialectType.H2,
                    username = "sa",
                    password = "",
                    jdbcUrl = newUrl,
                ),
            )

            assertTrue(state.generateLog.isEmpty(), "日志属于上一连接，切连接应当清空")
            assertEquals(0, state.generateLogDropped)
        }

    @Test
    fun `a failed generation leaves the reason in the log`() =
        runBlocking {
            val state = newBrowser()
            // 表不存在 → 引擎回一个 success=false 的响应。
            state.run("insert('no_such_table', {id = 1})")

            assertNotNull(state.generateError, "造数应当失败")
            val failures = state.generateLog.filter { it.error != null }
            assertEquals(1, failures.size, "失败也进日志 —— 一份不显示失败的日志是不完整的")
            assertTrue(
                failures.single().error!!.isNotBlank(),
                "失败行要带上原因原文，不能只是一句「失败」",
            )
        }

    @Test
    fun `an empty script is reported in the log too`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.GENERATE)
            state.currentGenerateScript()!!.editor.setText("   ")
            state.executeGenerate()

            assertNotNull(state.generateError, "空脚本应当直接报错")
            assertFalse(state.generateRunning)
            assertEquals(1, state.generateLog.size, "空脚本也要在日志里留一行")
            val reason = state.generateLog.single().error
            assertNotNull(reason, "失败行要带上原因原文")
            assertTrue(reason!!.isNotBlank(), "失败原因不能是空的")
        }

    // =========================================================================
    // 有界缓冲 —— 丢行必须说出来
    // =========================================================================

    @Test
    fun `the log is bounded and reports how many rows it dropped`() {
        val state = newBrowser()
        val total = GENERATE_LOG_MAX_LINES + 200

        repeat(total) { index ->
            state.appendGenerateLog(
                GenerateLogEntry(
                    scriptIndex = 1,
                    totalScripts = 1,
                    table = "orders",
                    rowNumber = index + 1L,
                    values = emptyMap(),
                ),
            )
        }

        assertTrue(
            state.generateLog.size <= GENERATE_LOG_MAX_LINES + GENERATE_LOG_DROP_BATCH,
            "日志条数必须有界：实际 ${state.generateLog.size}",
        )
        assertTrue(state.generateLogDropped > 0, "丢掉的行数要记下来，不能静默截断")
        assertEquals(
            total,
            state.generateLog.size + state.generateLogDropped,
            "保留的行 + 丢掉的行 = 追加的行数 —— 一行都不能凭空消失",
        )
        assertEquals(total.toLong(), state.generateLog.last().rowNumber, "最后一行是最新追加的那行")
        assertTrue(state.generateLog.first().rowNumber > 1, "最旧的行已经被丢掉了")
    }

    @Test
    fun `the append sequence increases on every append`() {
        // 自动滚动盯的是这个序号 —— 每次追加只 +1，
        // 于是「又来了一行」只有一个信号。（条数不是：一次成批丢行会让它先涨后跌。）
        val state = newBrowser()
        var last = state.generateLogSeq
        repeat(50) {
            state.appendGenerateLog(GenerateLogEntry(1, 1, "orders", it + 1L, emptyMap()))
            assertTrue(state.generateLogSeq > last, "序号每次追加都必须递增")
            last = state.generateLogSeq
        }
    }
}
