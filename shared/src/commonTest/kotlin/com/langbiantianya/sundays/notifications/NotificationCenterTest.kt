/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 通知中心的行为。
 *
 * ## 为什么去重是这里最要紧的一条
 *
 * 屏级错误对账每次重组都会看到同一个 `status.message` —— 连接失败是**持续**的状态，
 * 不是一次事件。没有去重就会在几秒内刷出几十条一模一样的通知，
 * 把真正值得看的那条挤出去，面板也就失去意义了。
 *
 * 但去重不能做成「一辈子只报一次」：错误消失（用户改好密码重连成功）之后再次失败，
 * 用户是关心第二次的。所以配了 `forgetSourcesNotIn` 让来源能「重新变得可报」。
 */
class NotificationCenterTest {
    @Test
    fun `push 按时间倒序，最新的在最前`() {
        val c = NotificationCenter()
        c.push(AppNotification.Severity.INFO, "第一条", "")
        c.push(AppNotification.Severity.INFO, "第二条", "")
        c.push(AppNotification.Severity.INFO, "第三条", "")

        assertEquals(listOf("第三条", "第二条", "第一条"), c.notifications.map { it.title })
    }

    @Test
    fun `pushOnce 同一个来源只报一次`() {
        val c = NotificationCenter()
        repeat(5) { c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败", "密码错") }

        assertEquals(
            1,
            c.notifications.size,
            "同一个持续存在的错误只该报一次 —— 连接失败出现在每一次重组里",
        )
        assertEquals(1, c.unreadCount)
    }

    @Test
    fun `不同来源各报一次`() {
        val c = NotificationCenter()
        c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        c.pushOnce("conn:B", AppNotification.Severity.ERROR, "连接失败 · B", "网络不通")
        c.pushOnce("conn:A", AppNotification.Severity.ERROR, "连接失败 · A", "密码错")

        assertEquals(2, c.notifications.size, "两个连接的失败都该在")
    }

    @Test
    fun `来源消失后再犯会重新报`() {
        val c = NotificationCenter()
        val src = "conn:A|密码错"

        c.pushOnce(src, AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        assertEquals(1, c.notifications.size)

        // 用户修好密码 → 错误消失 → 对账循环调 forgetSourcesNotIn(空集)
        c.forgetSourcesNotIn(emptySet())

        // 密码又错了 → 同一个来源应当再报一次
        c.pushOnce(src, AppNotification.Severity.ERROR, "连接失败 · A", "密码错")
        assertEquals(
            2,
            c.notifications.size,
            "「失败 → 恢复 → 又失败」必须报第二次，否则用户看不到复发",
        )
    }

    @Test
    fun `forgetSourcesNotIn 只遗忘不在集合里的`() {
        val c = NotificationCenter()
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        c.pushOnce("B", AppNotification.Severity.ERROR, "B", "")
        assertEquals(2, c.notifications.size)

        // B 消失了，A 还在
        c.forgetSourcesNotIn(setOf("A"))
        c.pushOnce("B", AppNotification.Severity.ERROR, "B", "")
        assertEquals(3, c.notifications.size, "B 消失过，应当能再报")

        // A 从未消失，不该重复
        c.forgetSourcesNotIn(setOf("A"))
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        assertEquals(3, c.notifications.size, "持续存在的 A 不该重复报")
    }

    @Test
    fun `错误文本变化视为新来源`() {
        // 「表 X 不存在」改成「表 Y 不存在」是**新的一件事**，必须报
        val c = NotificationCenter()
        c.pushOnce("tab:t1|Table 'a' doesn't exist", AppNotification.Severity.ERROR, "读取 a 失败", "Table 'a' doesn't exist")
        c.forgetSourcesNotIn(setOf("tab:t1|Table 'b' doesn't exist"))
        c.pushOnce("tab:t1|Table 'b' doesn't exist", AppNotification.Severity.ERROR, "读取 a 失败", "Table 'b' doesn't exist")

        assertEquals(2, c.notifications.size)
    }

    @Test
    fun `全部已读清零未读但保留列表`() {
        val c = NotificationCenter()
        c.push(AppNotification.Severity.ERROR, "连接失败", "密码错")
        assertEquals(1, c.unreadCount)

        c.markAllRead()
        assertEquals(0, c.unreadCount)
        assertEquals(1, c.notifications.size, "已读不等于删除 —— 用户还要回看")
    }

    @Test
    fun `清空同时清掉列表、未读与去重集合`() {
        val c = NotificationCenter()
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        c.clear()

        assertTrue(c.notifications.isEmpty())
        assertEquals(0, c.unreadCount)
        // 去重集合也清了 —— 否则清空之后同样的错误永远不再报，那是不可接受的
        c.pushOnce("A", AppNotification.Severity.ERROR, "A", "")
        assertEquals(1, c.notifications.size, "清空后同样的错误要能再报")
    }

    @Test
    fun `超出上限时丢最旧的`() {
        val c = NotificationCenter()
        repeat(80) { c.push(AppNotification.Severity.INFO, "第 $it 条", "") }
        // 通知是「最近发生过的事」，不是日志 —— 留太多会把有价值的挤出去
        assertTrue(c.notifications.size <= 60, "实际 ${c.notifications.size}")
        assertEquals("第 79 条", c.notifications.first().title, "最新的必须在最前")
    }

    @Test
    fun `每条通知的 id 互不相同`() {
        // ⚠️ 这一条钉住的是一个**曾经存在**的 bug：原实现写的是
        // `AtomicLong(notifySeq).incrementAndGet()` —— 每次都 new 一个从 0 开始的
        // 原子对象，而 `notifySeq` 那个 `var` 永远是 0，于是每条通知的 id 都是 "n-1"。
        // 当时没出事只是因为 id 从来没被当成 key 用过；「id 唯一」却是它存在的唯一理由。
        val c = NotificationCenter()
        repeat(20) { c.push(AppNotification.Severity.INFO, "第 $it 条", "") }

        val ids = c.notifications.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "通知 id 必须互不相同：$ids")
    }

    @Test
    fun `时间戳取自注入的时钟`() {
        // 时钟是注入的（commonMain 里没有 System.currentTimeMillis），
        // 所以「这条通知是什么时候来的」在测试里是确定的。
        val c = NotificationCenter(now = { 1_700_000_000_000L })
        c.push(AppNotification.Severity.INFO, "固定时刻", "")

        assertEquals(1_700_000_000_000L, c.notifications.single().timestamp)
    }
}
