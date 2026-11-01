/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.sundays.analysis.ANALYSIS_COPY_STATUS_TAG
import com.langbiantianya.sundays.analysis.AnalysisColumn
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.table.TABLE_COPY_ALL_ROWS_MENU_TAG
import com.langbiantianya.sundays.table.TABLE_COPY_ROW_MENU_TAG
import com.langbiantianya.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 分析模式结果表的**右键复制**（主设计 M4 的最后一项）。
 *
 * ## 为什么要有这一组用例
 *
 * 结果表是**只读**的 —— 用户能做的只有「看」和「拿走」。
 * 「拿走」这条路上有三个环节，缺一个整条路就断：
 *
 * 1. **右键弹得出菜单** —— `onRightClick` 是手写的 `pointerInput`
 *    （`ui/RightClick.kt`，因为 Foundation 那个是 `internal`）。
 *    挂错位置 / 被 `clickable` 抢走 / 被 `SelectionContainer` 吃掉，
 *    菜单都不出现，而纯逻辑那边一切正常。
 * 2. **复制的是对的那段文本** —— 文本对不对由 `:shared` 的
 *    `TableCopyTextTest` 钉（转义、表头、NULL 都在那边）；
 *    这里只钉「菜单项接的是哪一段」。
 * 3. **成没成**看得见 —— 剪贴板有三条真实的失败路径（无头 / 被占用 /
 *    X11 下服务不可用），失败时用户点了菜单**什么都不会发生**。
 *
 * ## 为什么剪贴板要**注入**而不是用真的
 *
 * 真剪贴板在无头环境里必然失败、在有头环境里必然成功 ——
 * 环境决定了走哪条路，测试就只能测其中一半，
 * 而**失败那条**（最要紧的那条）永远测不到。
 * `TreeContextMenuStateTest` 里也是同一个理由。
 */
@OptIn(ExperimentalTestApi::class)
class AnalysisCopyMenuTest {
    private val result =
        AnalysisResult(
            columns = listOf(AnalysisColumn("region", "VARCHAR"), AnalysisColumn("n", "INTEGER")),
            rows = listOf(listOf("华东", "1284"), listOf("华南", "932")),
            elapsedMs = 7,
        )

    /** 记录写入剪贴板的文本；[ok] = 模拟「写入成功 / 失败」。 */
    private class RecordingClipboard(
        private val ok: Boolean = true,
    ) : (String) -> Boolean {
        val written = mutableListOf<String>()

        override fun invoke(text: String): Boolean {
            written += text
            return ok
        }
    }

    /** 渲染分析屏（带结果表）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.render(
        clipboard: (String) -> Boolean,
    ) {
        registerBuiltinEditors()
        setContent {
            SundaysTheme {
                ProvideTestStrings {
                    AnalysisScreen(
                        sources = emptyList(),
                        result = result,
                        onCopyToClipboard = clipboard,
                    )
                }
            }
        }
    }

    /**
     * 右键结果表里的**某一行**。
     *
     * 用**文本**定位那一格：结果表里的行没有 testTag（`DataTable` 只给
     * 可编辑的单元格打 tag，而分析结果是只读的）——
     * 而「华东」这个值在整屏上只出现一次。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.rightClickCell(text: String) {
        onNodeWithText(text).performMouseInput { rightClick() }
    }

    @Test
    fun `右键结果表的一行 - 菜单里有复制此行与复制整表`() {
        runComposeUiTest {
            render(RecordingClipboard())
            rightClickCell("华东")

            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).assertIsDisplayed()
            onNodeWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `复制此行 - 剪贴板里是那一行的值且不含表头`() {
        val clipboard = RecordingClipboard()
        runComposeUiTest {
            render(clipboard)
            rightClickCell("华南")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).performClick()
        }

        assertEquals(listOf("华南\t932"), clipboard.written)
    }

    @Test
    fun `复制整表 - 剪贴板里是表头加全部行`() {
        val clipboard = RecordingClipboard()
        runComposeUiTest {
            render(clipboard)
            rightClickCell("华东")
            onNodeWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).performClick()
        }

        // 表头**在** —— 粘进 Excel 当一张表用，没有列名用户就不知道哪一列是什么。
        assertEquals(listOf("region\tn\n华东\t1284\n华南\t932"), clipboard.written)
    }

    @Test
    fun `复制成功 - 底栏说已复制几行`() {
        runComposeUiTest {
            render(RecordingClipboard())
            rightClickCell("华东")
            onNodeWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).performClick()

            onNodeWithTag(ANALYSIS_COPY_STATUS_TAG).assertIsDisplayed()
            onNodeWithText(zh(PluralStrKey.COPIED_ROWS, 2)).assertIsDisplayed()
        }
    }

    @Test
    fun `复制失败 - 底栏明说失败而不是静悄悄`() {
        // 剪贴板写不进去时用户点了菜单**什么都不会发生** ——
        // 界面不说一句，用户只会以为是自己点歪了。
        runComposeUiTest {
            render(RecordingClipboard(ok = false))
            rightClickCell("华东")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).performClick()

            onNodeWithTag(ANALYSIS_COPY_STATUS_TAG).assertIsDisplayed()
            onNodeWithTag(ANALYSIS_COPY_STATUS_TAG).assertTextContains(zh(com.langbiantianya.i18n.StrKey.COPY_TABLE_FAILED))
        }
    }

    @Test
    fun `点了菜单项之后菜单消失`() {
        // 菜单**不会**因为「菜单项被点了」而自动关闭 —— 它只认 dismiss 路径。
        // 不关的话菜单会杵在表上，下一次右键被压在旧菜单底下，表现为「右键没反应了」。
        runComposeUiTest {
            render(RecordingClipboard())
            rightClickCell("华东")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).performClick()

            onAllNodesWithTag(TABLE_COPY_ROW_MENU_TAG).assertCountEquals(0)
            onAllNodesWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).assertCountEquals(0)
        }
    }

    @Test
    fun `没跑出结果时没有复制菜单可点`() {
        // 结果表不存在时（还没跑 / 跑失败）菜单**不该**出现 ——
        // 给一个「点了复制空表」的入口是「点了必然失败的东西」。
        val clipboard = RecordingClipboard()
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                SundaysTheme {
                    ProvideTestStrings {
                        AnalysisScreen(
                            sources = emptyList(),
                            result = null,
                            onCopyToClipboard = clipboard,
                        )
                    }
                }
            }
            onAllNodesWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).assertCountEquals(0)
        }
        assertTrue(clipboard.written.isEmpty(), "没有结果时不该有任何复制动作")
    }
}
