/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「导航目标 → 用户可见的模式」的映射契约。
 *
 * ## 为什么这条映射要有测试
 *
 * 用户只该面对**两种**模式（数据库管理模式 / 数据分析模式），而导航目标有五个
 * （首屏 / 连接管理 / 数据库浏览 / 数据分析 / 设置）。设置页要显示「当前在哪个模式」，
 * 就得有一份从目标到模式的映射 —— 而那份映射一旦写错，用户看到的就是
 * 「我在分析模式，却显示当前是数据库管理模式」，于是他会点错按钮。
 *
 * 映射写成**纯函数**（[AppDestination.mode]）就是为了能这样被钉住：
 * 它既不碰界面也不碰引擎，出错时立刻能看见。
 */
class AppModeTest {
    @Test
    fun `首屏不属于任何模式`() {
        assertEquals(AppMode.NONE, AppDestination.START.mode)
    }

    @Test
    fun `连接管理与数据库浏览都属于数据库管理模式`() {
        // 「连接管理」是这条模式的**入口**，「数据库浏览」是它里面的工作区 ——
        // 用户眼里是同一件事，不该让他以为自己换了个模式。
        assertEquals(AppMode.DATABASE_MANAGEMENT, AppDestination.CONNECTIONS.mode)
        assertEquals(AppMode.DATABASE_MANAGEMENT, AppDestination.DATABASE.mode)
    }

    @Test
    fun `数据分析目标属于数据分析模式`() {
        assertEquals(AppMode.DATA_ANALYSIS, AppDestination.ANALYSIS.mode)
    }

    @Test
    fun `设置页本身不属于任何模式`() {
        // 设置页是**横切**在所有模式之上的全局页。设置页显示的「当前模式」
        // 取自「进来之前的那一屏」（见 desktopApp 的 destinationBeforeSettings），
        // 绝不取这个值 —— 否则设置页会永远显示「未选择模式」。
        assertEquals(AppMode.NONE, AppDestination.SETTINGS.mode)
    }
}
