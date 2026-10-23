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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.analysis.PULL_OUTCOME_TAG
import com.langbiantianya.sundays.analysis.PULL_PROGRESS_GLYPH_TAG
import com.langbiantianya.sundays.analysis.PULL_PROGRESS_INDICATOR_TAG
import com.langbiantianya.sundays.analysis.PullOutcome
import com.langbiantianya.sundays.analysis.PullOutcomeLine
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullProgressIndicator
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拉取进度的界面契约（主设计 §7.4）。
 *
 * ## 这一轮界面变简单了 —— 因为机制变简单了
 *
 * 早先的指示器是三段（导出 → CSV → 导入）外加一行「临时文件多大」。
 * 那是因为数据要**绕一圈磁盘**。现在拉取是 DuckDB 的**联邦直查** ——
 * 行从远端直接流进工作区表，中间没有阶段可分，也**没有临时文件**可占。
 *
 * 于是界面只剩两件事要说：
 * 1. 「正在拉取，已读 N 行」；
 * 2. 取消按钮是真的能点的。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisPullIndicatorTest {
    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
        )

    @Test
    fun `the indicator says it is pulling and how many rows it read`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullProgressIndicator(progress = PullProgress(rows = 1234))
                    }
                }
            }

            onNodeWithText("拉取中").assertIsDisplayed()
            onNodeWithText("已读 1234 行").assertIsDisplayed()
            // 状态字形单独给一个标记：断言要落在**这一格**上。
            onNodeWithTag(PULL_PROGRESS_GLYPH_TAG, useUnmergedTree = true).assertTextEquals("●")
        }
    }

    @Test
    fun `the indicator does not mention a temp file any more`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullProgressIndicator(progress = PullProgress(rows = 10))
                    }
                }
            }

            onNodeWithText("已读 10 行").assertIsDisplayed()
            // 联邦直查**不落盘** —— 没有临时文件，也就没有「占了多少磁盘」这回事。
            assertEquals(
                0,
                onAllNodesWithText("临时文件", substring = true).fetchSemanticsNodes().size,
                "拉取不再有临时文件，界面上不该出现它",
            )
        }
    }

    @Test
    fun `the cancel button reports the cancellation`() {
        var cancelled = false
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullProgressIndicator(
                            progress = PullProgress(rows = 5),
                            onCancel = { cancelled = true },
                        )
                    }
                }
            }

            onNodeWithText("取消拉取").performClick()
        }
        assertTrue(cancelled, "点了取消就该回调 —— 否则是按了没反应的按钮")
    }

    @Test
    fun `there is no cancel button when the caller does not offer one`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullProgressIndicator(progress = PullProgress(rows = 5))
                    }
                }
            }

            assertEquals(
                0,
                onAllNodesWithText("取消拉取").fetchSemanticsNodes().size,
                "没给取消回调就不该画取消按钮",
            )
        }
    }

    @Test
    fun `a finished pull says which table it landed in and how many rows`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullOutcomeLine(
                            outcome =
                                PullOutcome.Done(
                                    PullResult(
                                        stagingTable = "staging_shop_orders",
                                        rowsScanned = 12,
                                        rowsLanded = 12,
                                    ),
                                ),
                        )
                    }
                }
            }

            onNodeWithText("拉取完成").assertIsDisplayed()
            // 不说落在哪张表，用户就不知道下一步该查哪张表。
            onNodeWithText("工作区表 staging_shop_orders").assertIsDisplayed()
            onNodeWithText("行数 12").assertIsDisplayed()
        }
    }

    @Test
    fun `a cancelled pull is not reported as a failure`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullOutcomeLine(outcome = PullOutcome.Cancelled)
                    }
                }
            }

            onNodeWithText("拉取已取消").assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithText("拉取失败：").fetchSemanticsNodes().size,
                "取消不是失败 —— 显示成失败会让用户以为出了错",
            )
        }
    }

    @Test
    fun `a failed pull shows the reason`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullOutcomeLine(outcome = PullOutcome.Failed("表不存在"))
                    }
                }
            }

            onNodeWithText("拉取失败：表不存在").assertIsDisplayed()
        }
    }

    @Test
    fun `the analysis screen shows the pull strip while a pull is running`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            pullProgress = PullProgress(rows = 3),
                        )
                    }
                }
            }

            onNodeWithTag(PULL_PROGRESS_INDICATOR_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `the analysis screen shows the progress instead of a stale outcome`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisScreen(
                            sources = sources,
                            pullProgress = PullProgress(rows = 3),
                            pullOutcome = PullOutcome.Done(PullResult(stagingTable = "staging_x")),
                        )
                    }
                }
            }

            onNodeWithTag(PULL_PROGRESS_INDICATOR_TAG).assertIsDisplayed()
            // 正在拉的时候显示「拉取完成」是骗人 —— 那是**上一次**的终态。
            assertEquals(
                0,
                onAllNodesWithTag(PULL_OUTCOME_TAG).fetchSemanticsNodes().size,
                "正在拉取时不该显示终态",
            )
        }
    }
}
