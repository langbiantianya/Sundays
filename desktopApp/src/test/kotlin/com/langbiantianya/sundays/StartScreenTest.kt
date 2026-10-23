/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.navigation.START_MODE_ANALYSIS_TAG
import com.langbiantianya.sundays.navigation.START_MODE_CONNECTIONS_TAG
import com.langbiantianya.sundays.navigation.StartScreen
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **启动首屏**的界面契约 —— 两个模式都在，且各点各的。
 *
 * ## 为什么要有这一屏
 *
 * 应用有两种活儿：传统的「连库 → 浏览表结构与数据」，
 * 与**只读**的「数据分析」（远端只读源 → 本地工作区里算）。
 * 它们是两套会话、两种心智模型 —— 所以「选模式」要在**该问的时候**问：进应用时。
 *
 * ## 这条用例守住什么
 *
 * ① **两个选项都在** —— 少一个就等于把这个模式藏起来了（而它本来就有内容）；
 * ② **各点各的** —— 两个入口接错了线，症状是「点了没反应」或「点了进错屏」，
 * 而那种缺陷只有**真的点**才抓得住。
 */
@OptIn(ExperimentalTestApi::class)
class StartScreenTest {
    @Test
    fun `both modes are offered and each one reports its own click`() {
        var connections = 0
        var analysis = 0

        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        StartScreen(
                            onOpenConnections = { connections++ },
                            onOpenAnalysis = { analysis++ },
                        )
                    }
                }
            }

            onNodeWithTag(START_MODE_CONNECTIONS_TAG).assertIsDisplayed()
            onNodeWithTag(START_MODE_ANALYSIS_TAG).assertIsDisplayed()

            onNodeWithTag(START_MODE_CONNECTIONS_TAG).performClick()
            onNodeWithTag(START_MODE_ANALYSIS_TAG).performClick()
        }

        assertEquals(1, connections, "点「连接管理」必须回调它自己那条")
        assertEquals(1, analysis, "点「数据分析」必须回调它自己那条")
    }
}
