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
import com.langbiantianya.sundays.analysis.PULL_STAGE_INDICATOR_TAG
import com.langbiantianya.sundays.analysis.PullOutcome
import com.langbiantianya.sundays.analysis.PullOutcomeLine
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.analysis.PullStage
import com.langbiantianya.sundays.analysis.PullStageIndicator
import com.langbiantianya.sundays.analysis.pullStageGlyphTag
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拉取进度的界面契约（主设计 §7.4）。
 *
 * ## 这一轮最要紧的一条：三段要**分得清**
 *
 * 拉取跨两个数据库、三段耗时差着数量级。指示器若画不出「现在卡在哪一段」，
 * 用户就只能看着一条不动的进度条猜 —— 那跟没有指示器一样。
 * 所以断言落在**每段的状态字形**上，而且按段寻址（不是「屏上有个 ✓」）。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisPullIndicatorTest {
    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
        )

    @Test
    fun `the indicator shows the three stages and marks the current one`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullStageIndicator(progress = PullProgress(PullStage.CSV, rows = 100))
                    }
                }
            }

            onNodeWithText("导出到 CSV").assertIsDisplayed()
            onNodeWithText("写入 CSV").assertIsDisplayed()
            onNodeWithText("导入工作区").assertIsDisplayed()

            // 状态字形按**段**断言 —— 屏上有个 ✓ 不够，得知道是哪一段的 ✓。
            onNodeWithTag(pullStageGlyphTag(PullStage.EXPORT), useUnmergedTree = true).assertTextEquals("✓")
            onNodeWithTag(pullStageGlyphTag(PullStage.CSV), useUnmergedTree = true).assertTextEquals("●")
            onNodeWithTag(pullStageGlyphTag(PullStage.IMPORT), useUnmergedTree = true).assertTextEquals("○")
        }
    }

    @Test
    fun `the indicator shows the processed rows and the temp file size`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullStageIndicator(
                            progress = PullProgress(PullStage.IMPORT, rows = 1234, bytes = 1024L * 1024),
                        )
                    }
                }
            }

            onNodeWithText("已处理 1234 行").assertIsDisplayed()
            onNodeWithText("临时文件 1.0 MB").assertIsDisplayed()
        }
    }

    @Test
    fun `the indicator hides the file size before the file exists`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullStageIndicator(progress = PullProgress(PullStage.EXPORT, rows = 10))
                    }
                }
            }

            onNodeWithText("已处理 10 行").assertIsDisplayed()
            // 「临时文件 0 B」是**假信息**：用户会以为文件已经建好却只有 0 字节。
            // 不显示比显示个假的强。
            assertEquals(
                0,
                onAllNodesWithText("临时文件 0 B").fetchSemanticsNodes().size,
                "文件还没落盘时不该报大小",
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
                        PullStageIndicator(
                            progress = PullProgress(PullStage.EXPORT, rows = 5),
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
    fun `a finished pull says which table it landed in`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        PullOutcomeLine(
                            outcome =
                                PullOutcome.Done(
                                    PullResult(stagingTable = "staging_shop_orders", rowsImported = 12),
                                ),
                        )
                    }
                }
            }

            onNodeWithText("拉取完成").assertIsDisplayed()
            // 不说落在哪张表，用户就不知道下一步该查哪张表。
            onNodeWithText("工作区表 staging_shop_orders").assertIsDisplayed()
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
                            selectedSourceId = "c1",
                            onSelectSource = {},
                            pullProgress = PullProgress(PullStage.EXPORT, rows = 3),
                        )
                    }
                }
            }

            onNodeWithTag(PULL_STAGE_INDICATOR_TAG).assertIsDisplayed()
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
                            selectedSourceId = "c1",
                            onSelectSource = {},
                            pullProgress = PullProgress(PullStage.IMPORT, rows = 3),
                            pullOutcome = PullOutcome.Done(PullResult(stagingTable = "staging_x")),
                        )
                    }
                }
            }

            onNodeWithTag(PULL_STAGE_INDICATOR_TAG).assertIsDisplayed()
            // 正在拉的时候显示「拉取完成」是骗人 —— 那是**上一次**的终态。
            assertEquals(
                0,
                onAllNodesWithTag(PULL_OUTCOME_TAG).fetchSemanticsNodes().size,
                "正在拉取时不该显示终态",
            )
        }
    }
}
