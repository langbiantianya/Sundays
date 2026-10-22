/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.sundays.analysis.ANALYSIS_READ_ONLY_BADGE_TAG
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.analysisSourceRowTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 分析工作台的界面契约（主设计 §7.5）。
 *
 * ## 这一轮最要紧的一条：只读徽标**不可关**
 *
 * 分析模式连的是远端，而远端**恒为只读**。这不只是「现在开着只读」，
 * 而是「根本关不掉」——
 *
 * - 会话级的 `read_only` 由分析模式自己带上，不落 `connection.json`，
 *   前端没有关它的余地；
 * - 方言级的只读是方言的**实现事实**（引擎里压根没有往它写入的路径），
 *   更无从「解除」。
 *
 * 所以界面上**不能**出现任何「关闭只读」的入口。给了又关不掉的东西，
 * 用户点了发现没反应，下次就不信这个徽标了 —— 而它恰恰是最该被信的那句话。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisScreenTest {
    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
            AnalysisSourceItem(id = "c2", name = "分析 PG", dialectLabel = "PostgreSQL"),
        )

    @Test
    fun `the read only badge is shown and has no close affordance`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            selectedSourceId = "c1",
                            onSelectSource = {},
                        )
                    }
                }
            }

            val badge = onNodeWithText("只读")
            badge.assertIsDisplayed()

            // 判据落在**徽标容器**上，不是里面那个 Text。
            //
            // ⚠️ 这里踩过一次假绿：按文本找会找到「⊘ 只读」那两个字的 Text 节点，
            // 而可点语义挂在**容器**（Surface）上 —— Text 自己没有可点语义，
            // 于是「徽标不可关」这条断言恒绿，哪怕徽标其实做成了开关。
            // 变异验证（给徽标加上 onClick 语义）必须让这条断言变红。
            val badgeBox = onNodeWithTag(ANALYSIS_READ_ONLY_BADGE_TAG)
            assertFalse(
                badgeBox.fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
                "只读徽标不能可点：它不是开关，远端只读是既定事实",
            )

            // 更宽的一条：整屏**不该有任何开关**（Switch / Checkbox 之类）。
            // 远端恒为只读 —— 界面上放一个能拨的东西，就是在骗用户。
            assertEquals(
                0,
                onAllNodes(isToggleable()).fetchSemanticsNodes().size,
                "分析屏不该有任何可切换的控件 —— 远端只读是既定事实，没有开关可拨",
            )
        }
    }

    @Test
    fun `the analysis screen lists the data sources`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            selectedSourceId = "c1",
                            onSelectSource = {},
                        )
                    }
                }
            }

            // 数据源的名字同时出现在工具条的下拉框里和左树的行里 ——
            // 按标记找树里那一行，断言才说得清「是哪一行」。
            onNodeWithTag(analysisSourceRowTag("c1")).assertIsDisplayed()
            onNodeWithTag(analysisSourceRowTag("c2")).assertIsDisplayed()
        }
    }

    @Test
    fun `an empty source list shows the actionable empty state`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = emptyList(),
                            selectedSourceId = null,
                            onSelectSource = {},
                        )
                    }
                }
            }

            // 空态必须**说清下一步做什么** —— 只写「没有数据源」等于让用户自己猜。
            onNodeWithText("没有可分析的数据源").assertIsDisplayed()
            onNodeWithText("在「连接管理」里建一个连接；它的方言声明了「可分析」才会出现在这里").assertIsDisplayed()
        }
    }
}
