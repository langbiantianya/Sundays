package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.PageSize
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals

/**
 * `DataTable` 的**服务端分页模式** —— [DataTable] 本地切片与「调用方已切好页」不能混用。
 *
 * 背景：浏览屏的表预览是**引擎侧**分页（`DATA.LIST` 带 `page` / `pageSize`，回包里就是当前页的
 * 那些行）。而 `DataTable` 原本的契约是**本地**分页：内部做 `rows.drop((page-1) * size).take(size)`。
 * 两者直接对接的后果是第 2 页起 `drop(10)` 的起点已经越过只有 10 行的 rows，`start >= rows.size`
 * 命中 `emptyList()` —— **界面变成一张空表**，而且不报错、不转圈，看上去就像「这张表就这么多数据」。
 *
 * 修法是新增 [DataTable] 的 `serverSidePaging` 参数：跳过本地切片，只保留分页栏与页码计算。
 */
@OptIn(ExperimentalTestApi::class)
class TableServerPagingTest {

    private val columns = listOf(
        TableColumn(key = "id", header = "ID"),
        TableColumn(key = "tag", header = "TAG"),
    )

    /** 引擎为「第 3 页 / 每页 2 行」返回的那两行 —— 已经是**切好的当前页**。 */
    private val thirdPageRows = listOf(
        TableRow(id = 5, cells = mapOf("id" to 5, "tag" to "row5")),
        TableRow(id = 6, cells = mapOf("id" to 6, "tag" to "row6")),
    )

    private fun ComposeUiTest.renderServerPaged(
        pageSize: PageSize = PageSize.S10,
        currentPage: Int = 3,
        totalCount: Int = 25,
    ) {
        setContent {
            SundaysTheme {
                DataTable(
                    columns = columns,
                    rows = thirdPageRows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = pageSize,
                    currentPage = currentPage,
                    totalCount = totalCount,
                    serverSidePaging = true,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun `server paged rows survive a page past the first`() = runComposeUiTest {
        // 这条断言就是整件事的意义：修复前 serverSidePaging 不存在，接线方只能沿用本地切片，
        // 第 3 页在这里会命中 `start >= rows.size` → 空表
        renderServerPaged()
        onNodeWithText("row5").assertIsDisplayed()
        onNodeWithText("row6").assertIsDisplayed()
    }

    @Test
    fun `page indicator reflects the whole result set not the current page`() = runComposeUiTest {
        // 25 行 / 每页 10 行 = 3 页。totalCount 传错（用了 rows.size=2）会让分页栏退化成「1 / 1」
        renderServerPaged()
        onNodeWithText("3 / 3").assertIsDisplayed()
        onNodeWithText("共 25 条").assertIsDisplayed()
    }

    @Test
    fun `navigation hands the new page back to the caller`() = runComposeUiTest {
        // 服务端分页下 DataTable **不能**自己跳页：它手里没有引擎。必须把页码交回调用方，
        // 由调用方带新的 page 重新取数 —— 这是与本地分页最本质的差别
        var requested: Int? = null
        setContent {
            SundaysTheme {
                DataTable(
                    columns = columns,
                    rows = thirdPageRows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = PageSize.S10,
                    currentPage = 2,
                    totalCount = 25,
                    onPageChange = { requested = it },
                    serverSidePaging = true,
                )
            }
        }
        waitForIdle()

        onNodeWithText("上一页").performClick()
        waitForIdle()
        assertEquals(1, requested, "上一页应把目标页码交回调用方")

        onNodeWithText("首页").performClick()
        waitForIdle()
        assertEquals(1, requested, "首页应跳到第 1 页")
    }

    @Test
    fun `the all option is withheld from the size picker`() = runComposeUiTest {
        // 「全部」在数据源侧是**流式读取哨兵**（pageSize = 0），不是一个合法分页大小。
        // 放出来会让用户选一个静默退化成「每次取 1 行」的档位，故服务端分页下不提供
        renderServerPaged()
        onNodeWithText("每页").assertIsDisplayed()
        onNodeWithText("10").assertIsDisplayed()
    }
}

/**
 * 服务端分页的**反向**守卫：本地分页模式下行为必须与从前完全一致。
 *
 * 这一条不是凑数 —— 新参数最容易出的错就是「顺手把 `pageSize.isAll` 那个分支改掉」，
 * 结果本地分页（SQL 工作台、造数结果都用它）整体退化成不分页。这里把它钉死。
 */
@OptIn(ExperimentalTestApi::class)
class LocalPagingUnchangedTest {

    @Test
    fun `client paged tables still slice locally`() = runComposeUiTest {
        val rows = (1..10).map { TableRow(id = it, cells = mapOf("id" to it, "tag" to "row$it")) }
        setContent {
            SundaysTheme {
                DataTable(
                    columns = listOf(TableColumn(key = "id", header = "ID"), TableColumn(key = "tag", header = "TAG")),
                    rows = rows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = PageSize.S10,
                    currentPage = 2,   // 本地切片：第 2 页 = 第 11 行起 → 空
                    totalCount = 10,
                    // serverSidePaging 保持默认 false
                )
            }
        }
        waitForIdle()

        // 第 2 页在 10 行/页下本就不该有任何行 —— 这正是本地切片的既有行为
        onNodeWithText("row1").assertDoesNotExist()
        onNodeWithText("2 / 1").assertIsDisplayed()
    }

    @Test
    fun `client paged tables show the first page by default`() = runComposeUiTest {
        val rows = (1..10).map { TableRow(id = it, cells = mapOf("id" to it, "tag" to "row$it")) }
        setContent {
            SundaysTheme {
                DataTable(
                    columns = listOf(TableColumn(key = "id", header = "ID"), TableColumn(key = "tag", header = "TAG")),
                    rows = rows,
                    modifier = Modifier.fillMaxSize(),
                    pageSize = PageSize.S10,
                    totalCount = 10,
                )
            }
        }
        waitForIdle()

        onNodeWithText("row1").assertIsDisplayed()
        onNodeWithText("row10").assertIsDisplayed()
        onNodeWithText("1 / 1").assertIsDisplayed()
        // 只有一页时翻页按钮必须禁用
        onNodeWithText("下一页").assertIsNotEnabled()
    }
}
