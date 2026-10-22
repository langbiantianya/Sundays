/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionWizardContent
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.WizardFlow
import com.langbiantianya.sundays.connection.WizardStep
import com.langbiantianya.sundays.connection.selectableOptionTag
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 小窗口下，向导的**主操作必须点得到**。
 *
 * ## 缺陷（真机实测）
 *
 * 第 1 步（名称 + 5 个方言选项）的内容天然有 ~700dp 高，而小屏笔记本的工作区
 * 只有 600 出头 dp。原来的实现把「上一步 / 下一步」放在**内容里**，于是它跟着
 * 内容一起被推出视口：用户看得见「这一步要选方言」，却**看不见、点不到**
 * 「下一步」—— 向导就此走不下去（滚也滚不出来：整列本身就在滚动区里，
 * 而窗口底边又在屏幕外）。
 *
 * ## 修法
 *
 * 步骤布局改成「头部固定 / 内容可滚 / **底部按钮固定**」。于是：
 *
 * - 按钮永远在视口里；
 * - 内容再长也只影响滚动区；
 * - 键盘用户 Tab 到底能落到按钮上。
 *
 * 本类钉的就是这条：**窗口多矮，「下一步」都在，而且点得动**。
 */
@OptIn(ExperimentalTestApi::class)
class WizardSmallWindowTest {
    /** 窗口高度 —— 几档代表「很挤」到「很挤但不至于不可用」。 */
    private val heights = listOf(240.dp, 360.dp, 480.dp)

    @androidx.compose.runtime.Composable
    private fun Wizard(
        height: Dp,
        onNext: () -> Unit,
    ) {
        ProvideTestStrings {
            MaterialTheme {
                Box(Modifier.size(600.dp, height)) {
                    ConnectionWizardContent(
                        editingConnection = ConnectionConfig(id = "x", name = "探针"),
                        wizardStep = WizardStep.BASIC_INFO,
                        wizardFlow = WizardFlow.NORMAL,
                        onSaveConnection = {},
                        onQuickConnectDirect = {},
                        onCancelEdit = {},
                        onWizardNext = { onNext() },
                        onWizardBack = {},
                        onUpdateEditingConnection = {},
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        }
    }

    @Test
    fun `窗口再矮 下一步也必须在视口里且点得动`() =
        runComposeUiTest {
            var height by mutableStateOf(heights.first())
            var clicks = 0
            setContent { Wizard(height) { clicks++ } }
            waitForIdle()

            heights.forEach { h ->
                height = h
                waitForIdle()

                val next = onNodeWithText(zh(StrKey.NEXT_STEP))
                assertTrue(
                    next.isDisplayed(),
                    "窗口高度 ${h.value}dp 时「下一步」必须可见 —— 它被推出视口就等于向导走不下去",
                )
                val clicksBefore = clicks
                next.performClick()
                waitForIdle()
                assertEquals(
                    clicksBefore + 1,
                    clicks,
                    "窗口高度 ${h.value}dp 时「下一步」必须点得动（按钮被压没了就点不动）",
                )
            }
        }

    @Test
    fun `内容放不下时内容区能滚而按钮不动`() =
        runComposeUiTest {
            var height by mutableStateOf(240.dp)
            setContent { Wizard(height) {} }
            waitForIdle()

            // 最后一个方言选项在 240dp 的视口里放不下 —— 内容区必须**能滚**。
            //
            // 这条与上一条是一对：上一条钉「按钮在」，这一条钉「长内容能滚」。
            // 只钉前者的话，把按钮钉住、内容区却不可滚，用户会看到「上面的选项被截断了，
            // 而且滚不动」—— 那是另一种「点了必然失败」。
            //
            // 用 testTag 定位而不是文案：选项的标题是**枚举名**（`SQLITE`），
            // 跟着 [DialectType] 走，写死文案会在改枚举名时静默失效。
            val lastDialect = onNodeWithTag(selectableOptionTag(DialectType.SQLITE.name))
            lastDialect.performScrollTo()
            waitForIdle()
            assertTrue(
                lastDialect.isDisplayed(),
                "内容区必须能滚 —— 否则放不下的选项用户根本选不到",
            )

            // 滚完之后，按钮依然在视口里（它不参与滚动）
            assertTrue(
                onNodeWithText(zh(StrKey.NEXT_STEP)).isDisplayed(),
                "内容滚动不得把按钮带走",
            )
        }
}
