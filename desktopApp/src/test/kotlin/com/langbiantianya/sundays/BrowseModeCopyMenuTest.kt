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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.notifications.AppNotification
import com.langbiantianya.sundays.notifications.NotificationCenter
import com.langbiantianya.sundays.table.TABLE_COPY_ALL_ROWS_MENU_TAG
import com.langbiantianya.sundays.table.TABLE_COPY_ROW_MENU_TAG
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow
import com.langbiantianya.sundays.ui.SundaysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 浏览模式（数据库管理模式）表格的**右键复制**。
 *
 * ## 为什么浏览模式要单独接一次
 *
 * 分析模式的结果表已经有右键复制了（见 `AnalysisCopyMenuTest`），而浏览模式的
 * `DataTable` 一直**留着一个空的 `contextMenuItems` 插槽没人接** ——
 * 真窗口上右键表预览的行，弹出的是**一个空菜单**。
 *
 * ## 但浏览模式**只给「复制此行」**
 *
 * 表预览是**引擎侧分页**（`DATA.LIST` 带 page / pageSize，回包里只有当前页），
 * 整张表在引擎那边。在那里给一个叫「复制整表」的菜单项，它复制出来的却只有一页 ——
 * 用户以为把整张表拷走了，粘进 Excel 才发现少了几万行。**那比不给更糟。**
 *
 * 所以这一组用例钉的是两件事：
 * 1. 「复制此行」在，且复制到的正是那一行的值（不含表头）；
 * 2. 「复制整表」**不在** —— 空的菜单项是骗人，而给错的更糟。
 */
@OptIn(ExperimentalTestApi::class)
class BrowseModeCopyMenuTest {
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
    }

    /** 造一张有两个行的预览表 —— 直接给 `TablePreviewTab` 塞列与行，不经过引擎。 */
    private fun previewTab(): TablePreviewTab =
        TablePreviewTab(schema = "DEMO", tableName = "USERS").apply {
            columns =
                listOf(
                    TableColumn(key = "id", header = "ID"),
                    TableColumn(key = "name", header = "NAME"),
                )
            rows =
                listOf(
                    TableRow(id = 1, cells = mapOf("id" to 1, "name" to "Alice")),
                    TableRow(id = 2, cells = mapOf("id" to 2, "name" to "Bob")),
                )
        }

    /** 渲染浏览模式的表预览内容（带记录下来的复制出口）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.render(
        copied: MutableList<String>,
        reports: MutableList<Pair<Int, Boolean>>,
        onCopy: (String) -> Boolean = { true },
    ) {
        setContent {
            SundaysTheme {
                ProvideTestStrings {
                    PreviewTabContent(
                        tab = previewTab(),
                        onPageChange = {},
                        onPageSizeChange = {},
                        onCopyRows = { text ->
                            copied += text
                            onCopy(text)
                        },
                        onCopiedRows = { count, ok -> reports += count to ok },
                    )
                }
            }
        }
        waitForIdle()
    }

    /**
     * 右键表预览里的**某一行**。
     *
     * 用**文本**定位那一格：表预览的行没有 testTag（`DataTable` 只给可编辑的单元格打 tag，
     * 而浏览屏默认只读），而「Alice」这个值在整屏上只出现一次。
     */
    private fun androidx.compose.ui.test.ComposeUiTest.rightClickCell(text: String) {
        onNodeWithText(text).performMouseInput { rightClick() }
    }

    @Test
    fun `表预览右键能弹出复制此行`() {
        runComposeUiTest {
            render(mutableListOf(), mutableListOf())
            rightClickCell("Alice")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `表预览右键没有复制整表 - 服务端分页拿不到整张表`() {
        // 给了「复制整表」而它只复制当前页，那是**骗人**：用户以为拷走了整张表。
        runComposeUiTest {
            render(mutableListOf(), mutableListOf())
            rightClickCell("Alice")
            onAllNodesWithTag(TABLE_COPY_ALL_ROWS_MENU_TAG).assertCountEquals(0)
        }
    }

    @Test
    fun `复制此行 - 写出去的是那一行的值且不含表头`() {
        val copied = mutableListOf<String>()
        val reports = mutableListOf<Pair<Int, Boolean>>()
        runComposeUiTest {
            render(copied, reports)
            rightClickCell("Bob")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).performClick()
        }
        assertEquals(listOf("2\tBob"), copied)
        assertEquals(listOf(1 to true), reports)
    }

    @Test
    fun `复制失败 - 失败要报出来而不是静悄悄`() {
        // 剪贴板写不进去时用户点了菜单**什么都不会发生** —— 界面不说一句，
        // 用户只会以为是自己点歪了。所以失败必须原样传给调用方去报。
        val reports = mutableListOf<Pair<Int, Boolean>>()
        runComposeUiTest {
            render(mutableListOf(), reports, onCopy = { false })
            rightClickCell("Alice")
            onNodeWithTag(TABLE_COPY_ROW_MENU_TAG).performClick()
        }
        assertEquals(listOf(1 to false), reports)
    }

    // =========================================================================
    // 状态机那两个出口 —— 写剪贴板与报结果
    // =========================================================================

    private fun newState(
        center: NotificationCenter? = null,
        clipboard: (String) -> Boolean = { true },
    ): DatabaseBrowserState {
        val engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        val jobScope = CoroutineScope(Dispatchers.Unconfined)
        scope = jobScope
        return DatabaseBrowserState(engine, jobScope, notifications = center, clipboard = clipboard)
    }

    @Test
    fun `writeClipboard 走注入的剪贴板 - 不是真剪贴板`() {
        var written: String? = null
        val state =
            newState(
                clipboard = { text ->
                    written = text
                    true
                },
            )

        assertTrue(state.writeClipboard("abc"))
        assertEquals("abc", written)
    }

    @Test
    fun `writeClipboard 把失败如实返回`() {
        val state = newState(clipboard = { false })
        assertFalse(state.writeClipboard("abc"))
    }

    @Test
    fun `notifyCopiedRows 推成功通知并说清复制了几行`() {
        val center = NotificationCenter()
        val state = newState(center = center)

        state.notifyCopiedRows(12, true)

        val note = center.notifications.single()
        assertEquals(AppNotification.Severity.SUCCESS, note.severity)
        assertEquals(zh(PluralStrKey.COPIED_ROWS, 12), note.title)
    }

    @Test
    fun `notifyCopiedRows 推失败通知而不是静悄悄`() {
        val center = NotificationCenter()
        val state = newState(center = center)

        state.notifyCopiedRows(12, false)

        val note = center.notifications.single()
        assertEquals(AppNotification.Severity.ERROR, note.severity)
        assertEquals(zh(StrKey.COPY_TABLE_FAILED), note.title)
    }

    @Test
    fun `没有通知中心时不至于崩`() {
        // 通知中心是**可选**的（测试与只想跑逻辑的调用方传 null）——
        // 复制照样要做，只是没有地方报结果。
        val state = newState(center = null)
        state.notifyCopiedRows(3, true)
    }
}
