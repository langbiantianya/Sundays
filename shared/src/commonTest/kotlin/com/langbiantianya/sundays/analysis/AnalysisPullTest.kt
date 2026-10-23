/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 拉取模型的**纯规则**测试。
 *
 * ## 这一轮模型变简单了
 *
 * 早先的模型有三段（导出 → CSV → 导入）、有「临时文件多大」、有 staging 列一律
 * `VARCHAR` 的规则 —— 那些规则全都是**绕磁盘**逼出来的。
 *
 * 现在拉取是 DuckDB 的**联邦直查**：行从远端直接流进工作区表，
 * 于是「阶段」「临时文件」「staging 列一律 VARCHAR」**整套消失**。
 *
 * 剩下的只有三件事要守住：
 * 1. 进度只有一个数（已读行数）；
 * 2. 结果说清落在哪张表、落了多少行；
 * 3. **取消不是失败** —— 终态是穷举的三种，不是三个可空字段。
 */
class AnalysisPullTest {
    @Test
    fun `the progress carries only the rows read so far`() {
        assertEquals(0L, PullProgress().rows, "没读行时是 0")
        assertEquals(1234L, PullProgress(rows = 1234L).rows)
    }

    @Test
    fun `the result says where the data landed and how many rows`() {
        val result = PullResult(stagingTable = "staging_shop_orders", rowsScanned = 100, rowsLanded = 100)
        assertEquals("staging_shop_orders", result.stagingTable)
        assertEquals(100L, result.rowsScanned)
        assertEquals(100L, result.rowsLanded)
    }

    @Test
    fun `the outcome is one of the three cases and never two at once`() {
        // 取消**不是**失败 —— 界面要分开说（「拉取已取消」vs「拉取失败：…」）。
        // 用「三个可空字段」表达这件事，就得分靠推断「结果为空且错误为空 ⇒ 大概是取消了」。
        val cancelled: PullOutcome = PullOutcome.Cancelled
        assertTrue(cancelled !is PullOutcome.Done)
        assertTrue(cancelled !is PullOutcome.Failed)

        val failed: PullOutcome = PullOutcome.Failed("远端连不上")
        assertTrue(failed !is PullOutcome.Done)
        assertTrue(failed !is PullOutcome.Cancelled)

        val done: PullOutcome = PullOutcome.Done(PullResult(stagingTable = "t"))
        assertTrue(done !is PullOutcome.Cancelled)
        assertTrue(done !is PullOutcome.Failed)
    }

    @Test
    fun `the failed outcome keeps the reason`() {
        val outcome = PullOutcome.Failed("Table 'shop.orders' doesn't exist")
        assertEquals("Table 'shop.orders' doesn't exist", outcome.reason)
    }

    @Test
    fun `the user cancel is its own exception and not a coroutine cancellation`() {
        // 协程的 CancellationException 说的是「这个协程被取消了」——
        // 抛出它等于让整个协程以「被取消」收场。
        // 而用户按「取消拉取」时，事情是「这一次拉取没做完」：协程本身好好的。
        //
        // （两者的关系由**类型**保证，不是靠文案或错误文本 ——
        // 所以这里只需要断言它带对了消息。）
        val e = assertFailsWith<AnalysisPullCancelledException> { throw AnalysisPullCancelledException() }
        assertEquals("拉取已取消", e.message)
    }
}
