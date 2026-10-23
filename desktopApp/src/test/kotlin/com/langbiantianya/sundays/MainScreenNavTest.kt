/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.analysis.ANALYSIS_READ_ONLY_BADGE_TAG
import com.langbiantianya.sundays.ui.rememberAppearanceState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **顶层导航**的端到端契约 —— 「连接管理 → 数据分析 → 回来」。
 *
 * ## 现在的导航模型
 *
 * 顶栏**没有**目标切换 chip（只有品牌 + ⚙ 设置）：
 *
 * - 「连接管理」是**首屏** —— chip 只是把当前项再显示一遍；
 * - 「数据分析」的入口在**连接管理的空闲面板**（没选中任何连接时那一块）；
 * - 分析工作台的工具条上有一个**返回**按钮。
 *
 * ## 为什么这条用例必须是**端到端**的
 *
 * 组件级用例（`TopNavBarTest` / `ConnectionManagerFlowTest` / `AnalysisScreenTest`）
 * 只能证明「回调**被调用了**」—— 它们证明不了**装配点真的把回调接到了目标切换上**。
 * 而导航接错线（回调调了、屏幕没换）的症状是「点了没反应」，
 * 那种缺陷只有把 `MainScreen` 整个渲染出来才抓得住。
 *
 * `user.home` 指向临时目录，避免污染真实 `~/.config/sundays/connection.json`。
 */
@OptIn(ExperimentalTestApi::class)
class MainScreenNavTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-nav-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        try {
            PoolManager.closeAll()
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    @Test
    fun `the analysis entry is in the idle panel and the analysis screen goes back`() {
        runComposeUiTest {
            setContent {
                ProvideTestStrings {
                    MaterialTheme {
                        MainScreen(engine, rememberAppearanceState())
                    }
                }
            }

            // 首屏 = 连接管理；空闲面板上有「数据分析」入口（顶栏的 chip 已去掉）
            onNodeWithText(zh(StrKey.CONNECTION_MANAGER_TITLE)).assertIsDisplayed()
            onNodeWithText(zh(StrKey.NAV_ANALYSIS)).assertIsDisplayed()

            // 点它 → 切到分析模式（只读徽标是分析工作台特有的）
            onNodeWithText(zh(StrKey.NAV_ANALYSIS)).performClick()
            onNodeWithTag(ANALYSIS_READ_ONLY_BADGE_TAG).assertIsDisplayed()

            // 分析页的「返回」→ 回到连接管理
            // 入口只有一条，就得也只进得出 —— 进得去出不来是这个仓库最不该出现的东西。
            onNodeWithContentDescription(zh(StrKey.BACK)).performClick()
            onNodeWithText(zh(StrKey.CONNECTION_MANAGER_TITLE)).assertIsDisplayed()
        }
    }
}
