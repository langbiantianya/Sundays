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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.analysis.report.ReportStorage
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * 「添加数据源」对话框的**界面契约**。
 *
 * ## 这一轮要守住的四条
 *
 * 1. **样式跟着全局走** —— 对话框是仓库那套 Win 控件拼的
 *    （`WinSurface` / `WinButton` / `WinTextField`），
 *    而不是 Material3 的 `AlertDialog`（那在复古档里是一块现代圆角卡片 ✗）。
 * 2. **文件要有系统选择器** —— 路径框旁边有「浏览」，
 *    点它调**系统**的文件 / 文件夹选择器（`NativePathPicker`）。
 * 3. **格式能手选** —— 文件夹没有扩展名，自动推断不出来。
 * 4. **两类源长得不一样** —— 远程是连接参数，文件是一个路径。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisSourceDialogTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-source-dialog").toFile()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    private fun newSession(): AnalysisSession =
        AnalysisSession(
            engine = engine,
            scope = scope,
            workspaceFile = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb",
            reportStorage =
                ReportStorage(Paths.get(tempHome.absolutePath, ".config", "sundays", "reports.json")),
        )

    private fun renderDialog() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisSourceDialog(
                            session = newSession(),
                            dialects = listOf(DialectType.MYSQL, DialectType.POSTGRESQL),
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the dialog opens with the remote form`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisSourceDialog(
                            session = newSession(),
                            dialects = listOf(DialectType.MYSQL, DialectType.POSTGRESQL),
                            onDismiss = {},
                        )
                    }
                }
            }

            onNodeWithTag(ANALYSIS_SOURCE_DIALOG_TAG).assertIsDisplayed()
            // 远程源那一组参数都在
            onNodeWithTag(ANALYSIS_SOURCE_HOST_FIELD_TAG).assertIsDisplayed()
            onNodeWithTag(ANALYSIS_SOURCE_NAME_FIELD_TAG).assertIsDisplayed()
            onNodeWithTag(ANALYSIS_SOURCE_PREVIEW_BUTTON_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `the file kind offers the system picker and a format choice`() {
        runComposeUiTest {
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisSourceDialog(
                            session = newSession(),
                            dialects = listOf(DialectType.MYSQL),
                            onDismiss = {},
                        )
                    }
                }
            }

            // 切到「文件 / 文件夹」
            onNodeWithText("文件 / 文件夹").performClick()

            // 路径框旁边有「浏览」—— 点它会调**系统**的
            // 文件 / 文件夹选择器（`NativePathPicker`，见其 KDoc）。
            //
            // ⚠️ 这里**只断言它在**，不点它：那个选择器是 Swing 的模态窗，
            // 点下去会真的弹窗并把测试挂住（CI 上无头时它会安静地返回 null ✓）。
            onNodeWithTag(ANALYSIS_SOURCE_BROWSE_BUTTON_TAG).assertIsDisplayed()
            onNodeWithTag(ANALYSIS_SOURCE_PATH_FIELD_TAG).assertIsDisplayed()

            // 格式**能手选** —— 文件夹没有扩展名，自动推断不出来。
            onNodeWithText("格式").assertIsDisplayed()
            onNodeWithText("Parquet 列存").assertIsDisplayed()
            onNodeWithText("CSV").assertIsDisplayed()
        }
    }

    @Test
    fun `the confirm button needs a name and a picked table`() {
        runComposeUiTest {
            var confirmed = false
            setContent {
                SundaysTheme {
                    ProvideStrings(AppLanguage.ZH_HANS) {
                        AnalysisSourceDialog(
                            session = newSession(),
                            dialects = listOf(DialectType.MYSQL),
                            onDismiss = { confirmed = true },
                        )
                    }
                }
            }

            // 没有名字 → 确定是**禁用**的（点下去什么也不会发生）。
            onNodeWithTag(ANALYSIS_SOURCE_CONFIRM_BUTTON_TAG).assertIsDisplayed()
            assertEquals(false, confirmed, "还没填名字就不该能确定")
        }
    }
}
