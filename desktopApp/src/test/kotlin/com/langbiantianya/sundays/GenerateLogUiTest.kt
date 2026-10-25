/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.LanguageScope
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.i18n.StringsRegistry
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertTrue

/**
 * 造数实时日志 —— **界面**这一侧的契约。
 *
 * ## 为什么状态层测过的还要在界面里再测一遍
 *
 * 状态层管的是「日志里有什么」，界面管的是「用户**看得到**吗」。
 * 这两件事会各自坏掉：
 *
 * - 状态层好好的，界面忘了渲染 → 用户造完数什么也没看见；
 * - 界面渲染了，但停在开头 → 新行被埋在两百行下面，等于没有实时日志。
 *
 * 第二条尤其容易漏：自动滚动盯的是**序号**而不是条数，
 * 而「日志满了之后条数会跌回去」这件事只有跑起来才看得见。
 *
 * ## 为什么用真引擎跑一条
 *
 * 「引擎每条 INSERT 回一帧」是这份日志的**根**。
 * 只用假帧的话，整条链路（引擎 → 协议 → 状态 → 界面）就少了一段 ——
 * 而那一段恰恰是这次改动之前就存在的部分。
 */
@OptIn(ExperimentalTestApi::class)
class GenerateLogUiTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-generate-log-ui-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("H2", H2Dialect())

        jdbcUrl = "jdbc:h2:mem:genlogui_${System.nanoTime()};DB_CLOSE_DELAY=-1"
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
                id = "genlog-ui-${System.nanoTime()}",
                name = "TestH2",
                dialect = DialectType.H2,
                username = "sa",
                password = "",
                jdbcUrl = jdbcUrl,
            ),
        )
        return state
    }

    /** 渲染造数工作台 —— 高度给足，日志区才有一屏能放下十几行。 */
    private fun ComposeUiTest.setWorkbench(state: DatabaseBrowserState) {
        registerBuiltinEditors()
        setContent {
            SundaysTheme {
                ProvideStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.size(1200.dp, 900.dp)) {
                        GenerateWorkbenchPane(
                            state = state,
                            connected = true,
                            schema = "smoke",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    /** 简体中文文案 —— 把线程语言钉住，免得断言随机器区域设置漂移。 */
    private fun zh(
        key: StrKey,
        vararg args: Any?,
    ): String = LanguageScope.withLanguage(AppLanguage.ZH_HANS) { Msg.format(key, *args) }

    @Test
    fun `the log panel is always there and starts empty`() =
        runComposeUiTest {
            val state = newBrowser()
            setWorkbench(state)

            onNodeWithTag(GENERATE_LOG_PANEL_TAG).assertExists()
            onNodeWithText(zh(StrKey.GENERATE_LOG_EMPTY_TITLE)).assertExists()
        }

    @Test
    fun `every generated row shows up in the log panel`() =
        runComposeUiTest {
            val state = newBrowser()
            setWorkbench(state)

            state.selectPane(BrowserPane.GENERATE)
            state.currentGenerateScript()!!.editor.setText(
                "for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end",
            )
            state.executeGenerate()
            waitUntil(timeoutMillis = 10_000) { !state.generateRunning }

            // 断言的是**列值本身** —— 它不受语言影响，
            // 所以这条断言在六种语言下都成立（这里只跑简体中文）。
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("id=101, total=10", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("id=103, total=30", substring = true).assertExists()
        }

    @Test
    fun `the log scrolls to the newest line by itself`() =
        runComposeUiTest {
            val state = newBrowser()

            // 先灌 100 行**再**渲染 —— 首屏就该停在最后一行。
            //
            // 这一步同时是「自动滚动确实跑了」的**证据**：
            // 100 行远超一屏能显示的十几行，没有滚动的话第 100 行压根不会被组合。
            // 等它真的可见之后，才知道「效果已经执行过了」——
            // 否则后面那次「续滚」的断言就成了竞态（首次执行若恰好赶在灌数据之后，
            // 一次不重跑的滚动也能蒙对）。
            repeat(100) { index -> state.appendGenerateLog(logEntry(index + 1)) }
            setWorkbench(state)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("id=100, total=100", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 再来 100 行 —— 这才是真实场景：行是一行一行冒出来的，日志得跟着往下滚。
            repeat(100) { index -> state.appendGenerateLog(logEntry(100 + index + 1)) }

            // LazyColumn 只组合看得见的项 —— 没有「续滚」的话，
            // 第 200 行压根不会被组合，这个节点也就不存在。
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("id=200, total=200", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

    /** 造一行日志 —— 列值与真实造数同形（`id` / `total` 两列，都是整数）。 */
    private fun logEntry(rowNumber: Int): GenerateLogEntry =
        GenerateLogEntry(
            scriptIndex = 1,
            totalScripts = 1,
            table = "orders",
            rowNumber = rowNumber.toLong(),
            values = mapOf("id" to rowNumber.toDouble(), "total" to rowNumber.toDouble()),
        )

    /**
     * 复数文案 —— 直接读文案表，不走 `CompositionLocal`。
     *
     * `plural()` 是 `@Composable` 的，而断言要在**组合之外**算期望值；
     * 所以这里绕开界面层，直接问文案表要那一条。
     */
    private fun zhPlural(
        key: PluralStrKey,
        count: Int,
        vararg args: Any?,
    ): String = StringsRegistry.of(AppLanguage.ZH_HANS).plural(key, count, *args)

    @Test
    fun `the finished summary shows up in the panel header`() =
        runComposeUiTest {
            val state = newBrowser()
            setWorkbench(state)

            state.selectPane(BrowserPane.GENERATE)
            state.currentGenerateScript()!!.editor.setText(
                "for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end",
            )
            state.executeGenerate()
            waitUntil(timeoutMillis = 10_000) { !state.generateRunning }

            // 摘要（「造数完成 · 共 3 行 · 处理 1 个脚本」）说的是**整个面板**的状态，
            // 不是结果表这一块的 —— 所以它得在**标题栏**上。
            //
            // 判据钉的是「它在标题栏里」而不只是「它存在」：
            // 掉回结果区里的话，这句话照样存在，只是没人第一眼能看到。
            val summary = zhPlural(PluralStrKey.GENERATE_COMPLETE_SUMMARY, 3, 3L, 1)
            onNodeWithText(summary).assert(hasAnyAncestor(hasTestTag(GENERATE_OUTPUT_HEADER_TAG)))
        }

    @Test
    fun `the dropped row notice shows up when the log overflows`() =
        runComposeUiTest {
            val state = newBrowser()
            repeat(GENERATE_LOG_MAX_LINES + GENERATE_LOG_DROP_BATCH + 10) {
                state.appendGenerateLog(GenerateLogEntry(1, 1, "orders", 1L, emptyMap()))
            }
            setWorkbench(state)

            assertTrue(state.generateLogDropped > 0, "前置条件：确实丢行了")
            onNodeWithText(zh(StrKey.GENERATE_LOG_DROPPED, state.generateLogDropped)).assertExists()
        }
}
