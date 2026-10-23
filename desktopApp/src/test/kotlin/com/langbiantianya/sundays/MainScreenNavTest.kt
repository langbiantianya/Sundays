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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.analysis.ANALYSIS_READ_ONLY_BADGE_TAG
import com.langbiantianya.sundays.navigation.START_MODE_ANALYSIS_TAG
import com.langbiantianya.sundays.navigation.START_MODE_CONNECTIONS_TAG
import com.langbiantianya.sundays.ui.rememberAppearanceState
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **顶层导航**的端到端契约 —— 「启动首屏 → 连接管理 → 数据分析」。
 *
 * ## 现在的导航模型
 *
 * 启动落在**首屏**（选一个模式进去）：
 *
 * - 「连接管理」→ 传统的连库与浏览工具；
 * - 「数据分析」→ 只读分析工作台（**直接**进，不经过连接列表）。
 *
 * 首屏之后：连接管理的空闲面板上挂着「数据分析」入口。
 * 分析工作台**没有返回按钮**（它是一块**独立**的工作区，不是「退掉这个功能」）。
 *
 * ## 为什么这条用例必须是**端到端**的
 *
 * 组件级用例（`TopNavBarTest` / `StartScreenTest` / `ConnectionManagerFlowTest` /
 * `AnalysisScreenTest`）只能证明「回调**被调用了**」——
 * 它们证明不了**装配点真的把回调接到了目标切换上**。
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
    fun `the start screen lets the user pick the analysis mode directly`() {
        runComposeUiTest {
            setContent {
                ProvideTestStrings {
                    MaterialTheme {
                        MainScreen(engine, rememberAppearanceState())
                    }
                }
            }

            // 启动落在**首屏**：两个模式都在，选哪个都能直接进去。
            onNodeWithTag(START_MODE_CONNECTIONS_TAG).assertIsDisplayed()
            onNodeWithTag(START_MODE_ANALYSIS_TAG).assertIsDisplayed()

            // 选「数据分析」→ **直接**进分析工作台（不必先经过连接列表）
            onNodeWithTag(START_MODE_ANALYSIS_TAG).performClick()
            onNodeWithTag(ANALYSIS_READ_ONLY_BADGE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `the start screen can go to the connection manager and from there into the analysis mode`() {
        runComposeUiTest {
            setContent {
                ProvideTestStrings {
                    MaterialTheme {
                        MainScreen(engine, rememberAppearanceState())
                    }
                }
            }

            // 首屏 → 连接管理
            onNodeWithTag(START_MODE_CONNECTIONS_TAG).performClick()
            onNodeWithText(zh(StrKey.CONNECTION_MANAGER_TITLE)).assertIsDisplayed()

            // 连接管理的空闲面板上有「数据分析」入口（顶栏的 chip 已去掉）
            onNodeWithText(zh(StrKey.NAV_ANALYSIS)).assertIsDisplayed()

            // 点它 → 切到分析模式（只读徽标是分析工作台特有的）
            onNodeWithText(zh(StrKey.NAV_ANALYSIS)).performClick()
            onNodeWithTag(ANALYSIS_READ_ONLY_BADGE_TAG).assertIsDisplayed()
        }
    }
}
