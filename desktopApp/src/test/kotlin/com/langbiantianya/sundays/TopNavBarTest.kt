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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.navigation.TopNavBar
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test

/**
 * 顶栏的界面契约 —— **目标切换 chip 已被去掉**。
 *
 * ## 为什么要有这条测试
 *
 * 「连接管理」「数据分析」两个 chip 去掉是**产品决定**：
 *
 * - 「连接管理」是**首屏** —— chip 只是把当前项再显示一遍（切过去等于什么都不做）；
 * - 「数据分析」是**入口** —— 入口该待在用户真正需要它的地方，
 *   现在在**连接管理的空闲面板**里（见 `ConnectionManagerFlowTest`）。
 *
 * chip 很容易被「顺手加回来」—— 顶上多两个按钮看着就挺完整的。
 * 而「分析模式的入口在哪儿」是**产品契约**，不是实现细节：
 * 入口挪了，返回路也得有（分析页工具条上的 ← 按钮，见 `AnalysisScreenTest`）。
 */
@OptIn(ExperimentalTestApi::class)
class TopNavBarTest {
    @Test
    fun `the top bar has no target chips and keeps the brand`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        TopNavBar(onOpenSettings = {})
                    }
                }
            }

            // 品牌还在 —— 顶栏不是空壳。
            onNodeWithText("sundays").assertIsDisplayed()
            // 两个 chip 都没了：目标切换不再发生在顶栏。
            onNodeWithText(zh(StrKey.NAV_CONNECTIONS)).assertDoesNotExist()
            onNodeWithText(zh(StrKey.NAV_ANALYSIS)).assertDoesNotExist()
        }
    }
}
