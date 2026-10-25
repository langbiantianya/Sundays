/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.langbiantianya.client.EngineClient
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.analysis.ANALYSIS_EDITOR_DRAG_HANDLE_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_EDITOR_PANEL_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_RESULT_PANEL_MIN_HEIGHT
import com.langbiantianya.sundays.analysis.ANALYSIS_RESULT_PANEL_TAG
import com.langbiantianya.sundays.analysis.ANALYSIS_RESULT_TABLE_TAG
import com.langbiantianya.sundays.analysis.AnalysisScreen
import com.langbiantianya.sundays.analysis.AnalysisSourceItem
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.connection.DialectMetadata
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.editor.ui.registerBuiltinEditors
import com.langbiantianya.sundays.ui.ProvideStrings
import com.langbiantianya.sundays.ui.SundaysTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL 编辑器的**高度可调** —— 浏览模式（数据管理）与分析模式**两侧都要成立**。
 *
 * ## 这条需求为什么两边都得做
 *
 * 两侧都是「上面一个 SQL 编辑器、下面一个结果区」，而它们此前的高度都是
 * **写死的比例**（浏览模式 60% / 40%，分析模式 0.42 : 1）。
 *
 * 比例是编译期定死的，而两种诉求是**互相打架**的：
 *
 * - 写一段长 SQL 时，编辑器那点高度不够 —— 一屏只看得到几行；
 * - 跑出来的结果只有两行时，结果区却占掉七成屏 —— 屏幕全是空的。
 *
 * 同一个概念（两个模式之间）的布局不能各做各的，所以两侧共用
 * `:shared` 的 `HorizontalDragHandle`，行为一致。
 *
 * ## 三条判据
 *
 * 1. **拖得动**：拖那条缝，编辑器的高度**真的变**，而且是跟手的（不是松手才跳）。
 * 2. **挤不没结果区**：结果区有**下限**（浏览模式 180dp）——
 *    数据看不见就等于白查，这条是既有契约（见 `SqlResultMinHeightTest`），
 *    拖拽**不能**把它破坏掉。
 * 3. **是窗口级偏好**：切 sheet 不复位（与左树宽度同一层、同一套理由）。
 */
@OptIn(ExperimentalTestApi::class)
class SqlEditorHeightTest {
    /**
     * 允许损失的 touch slop（dp）—— `detectDragGestures` 判定「这是拖拽」之前吃掉的那段位移。
     *
     * 与 `TreePaneCollapseTest` 取同一个预算：具体数值由平台的 `ViewConfiguration.touchSlop` 决定，
     * 测试环境不该因为某个平台的 slop 是 18 还是 20 就红。
     */
    private val touchSlopBudget = 24.dp

    /** 拖拽的距离 —— 足够大，能盖过 slop，又不至于把结果区压没。 */
    private val dragDistance = 80.dp

    /** 不做任何事的假引擎 —— 这个测试只关心**布局**，不碰数据库。 */
    private object NoopEngine : EngineClient {
        override fun handle(request: com.langbiantianya.grpc.Request) = kotlinx.coroutines.flow.flow<com.langbiantianya.grpc.Response> {}

        override suspend fun invoke(
            connection: com.langbiantianya.grpc.ConnectionConfig,
            configure: com.langbiantianya.grpc.RequestKt.Dsl.() -> Unit,
        ): com.langbiantianya.grpc.Response = error("本用例不走引擎")

        override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = error("本用例不测连接")

        override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = false

        override fun close() = Unit
    }

    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        // 引擎只是个空壳 —— 这个测试只关心**布局**，不碰任何数据。
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        runCatching { PoolManager.closeAll() }
    }

    // ---------------------------------------------------------------------------
    // 断言用的度量
    // ---------------------------------------------------------------------------

    /** 某个 tag 的节点当前高度（dp）—— 量的是**面板本身**，不是里面的文字。 */
    private fun ComposeUiTest.heightOf(tag: String): Dp = onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    /**
     * 在分隔条上按下、竖直移动 [by]、松开。
     *
     * 用触摸作用域而不是鼠标作用域：`MouseInjectionScope` 只暴露 `click` / `moveTo` 这类
     * 成品动作，没有 `down` / `up`，而 `detectDragGestures` 需要一次完整的按下-移动-抬起。
     *
     * @param by 正数 = 往下拖 = **上面那一块变高**（受控的是分隔条**之前**的那一块）
     */
    private fun ComposeUiTest.dragHandleVertically(
        tag: String,
        by: Dp,
    ) {
        onNodeWithTag(tag).performTouchInput {
            down(center)
            moveBy(Offset(0f, by.toPx()))
            up()
        }
    }

    // ---------------------------------------------------------------------------
    // 浏览模式
    // ---------------------------------------------------------------------------

    /**
     * 渲染浏览模式的 SQL 工作台。
     *
     * @param height 工作台容器的高度 —— 决定「编辑器 + 结果区」这一段有多高。
     *   拖拽能不能发生**取决于剩余空间**：结果区有下限，编辑器长到顶就长不动了。
     */
    private fun ComposeUiTest.setBrowseWorkbench(height: Dp) {
        val state = DatabaseBrowserState(NoopEngine, CoroutineScope(Dispatchers.Default))
        // 高度是**受控**的：工作台自己不持有，由外面拿着 ——
        // 与真实用法一致（`DatabaseBrowserScreen` 持有，切 sheet 才不丢）。
        // 不接上这个回调的话，拖拽会「拖了没反应」——
        // 组件把新高度回调出去了，只是没人接。
        var editorHeight by mutableStateOf<Dp?>(null)
        setContent {
            MaterialTheme {
                Box(Modifier.size(760.dp, height)) {
                    SqlWorkbenchPane(
                        state = state,
                        connected = true,
                        schema = "smoke",
                        dialect = DialectMetadata.of(DialectType.MYSQL).sqlDialectProfile,
                        editorHeight = editorHeight,
                        onEditorHeightChange = { editorHeight = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the browse mode sql editor can be dragged taller`() =
        runComposeUiTest {
            // 容器给足高度 —— 默认比例下编辑器已经占了六成，
            // 想往上拖就得先有「结果区从 40% 退到它的下限」那段空间。
            setBrowseWorkbench(1000.dp)

            val editorBefore = heightOf(SQL_EDITOR_PANE_TAG)
            val resultBefore = heightOf(SQL_RESULT_AREA_TAG)

            dragHandleVertically(SQL_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()

            val editorAfter = heightOf(SQL_EDITOR_PANE_TAG)
            val resultAfter = heightOf(SQL_RESULT_AREA_TAG)

            assertTrue(
                editorAfter > editorBefore,
                "往下拖应当让编辑器变高：before=$editorBefore after=$editorAfter",
            )
            val gained = editorAfter - editorBefore
            assertTrue(
                gained > dragDistance - touchSlopBudget,
                "拖拽应跟手（允许 touch slop 预算）：期望 ≥${dragDistance - touchSlopBudget}，实际 +$gained",
            )
            // 结果区让出来的高度应当**正好**等于编辑器多出来的高度 ——
            // 这条钉住「两者共享同一段空间」，而不是编辑器长出来把结果区挤到别处去了。
            assertEquals(
                gained,
                resultBefore - resultAfter,
                "编辑器多出来的高度应当正好从结果区那里来",
            )
        }

    @Test
    fun `the browse mode sql editor can be dragged shorter`() =
        runComposeUiTest {
            setBrowseWorkbench(1000.dp)

            val editorBefore = heightOf(SQL_EDITOR_PANE_TAG)
            val resultBefore = heightOf(SQL_RESULT_AREA_TAG)

            dragHandleVertically(SQL_EDITOR_DRAG_HANDLE_TAG, -dragDistance)
            waitForIdle()

            val editorAfter = heightOf(SQL_EDITOR_PANE_TAG)
            assertTrue(
                editorAfter < editorBefore,
                "往上拖应当让编辑器变矮：before=$editorBefore after=$editorAfter",
            )
            // 变矮的那部分**全部**让给结果区 —— 结果多出来的空间不能凭空消失。
            assertEquals(
                editorBefore - editorAfter,
                heightOf(SQL_RESULT_AREA_TAG) - resultBefore,
                "编辑器让出来的高度应当正好进结果区",
            )
        }

    @Test
    fun `dragging the browse mode editor down never squeezes the result area below its floor`() =
        runComposeUiTest {
            // 结果区的**下限**是既有契约（`SqlResultMinHeightTest`）：
            // 数据看不见就等于白查。所以拖拽不能突破它。
            setBrowseWorkbench(1000.dp)

            val editorBefore = heightOf(SQL_EDITOR_PANE_TAG)

            // 一路往下拖 —— 远超「结果区能退让」的幅度。
            repeat(6) {
                dragHandleVertically(SQL_EDITOR_DRAG_HANDLE_TAG, dragDistance)
                waitForIdle()
            }

            assertTrue(
                heightOf(SQL_EDITOR_PANE_TAG) > editorBefore,
                "前置条件：编辑器确实变高了（否则下面那条断言是空的）",
            )
            assertTrue(
                heightOf(SQL_RESULT_AREA_TAG) >= MIN_SQL_RESULT_HEIGHT - 1.dp,
                "结果区不该被拖到低于它的下限 ${MIN_SQL_RESULT_HEIGHT}：实际 ${heightOf(SQL_RESULT_AREA_TAG)}",
            )
        }

    @Test
    fun `in a short window the result area keeps its floor and the editor yields`() =
        runComposeUiTest {
            // 窗口很矮时，编辑器的下限（96dp）**要让位**给结果区的下限（180dp）——
            // 数据看不见就等于白查，而编辑器内部能滚，矮一点只是少显示几行。
            //
            // 这条在实现时真的错过一次：编辑器那 96dp 的下限当时是硬的，
            // 把结果区压到了 134dp，破了「结果区至少 180dp」的既有契约。
            setBrowseWorkbench(320.dp)

            assertTrue(
                heightOf(SQL_RESULT_AREA_TAG) >= MIN_SQL_RESULT_HEIGHT - 1.dp,
                "窗口很矮时结果区仍要保住下限 ${MIN_SQL_RESULT_HEIGHT}：实际 ${heightOf(SQL_RESULT_AREA_TAG)}",
            )
        }

    @Test
    fun `the sql editor height survives the workbench being torn down and rebuilt`() =
        runComposeUiTest {
            // 高度是**窗口级偏好** —— 与左树面板宽度同一层、同一套理由：
            // 写在 `ActiveSheetContent` 里的话，那棵组合一旦被拆掉重建，
            // 高度就复位了（「在 A 连接把编辑器拉高，关掉再打开又缩回去」）。
            //
            // 触发「组合被拆掉」的那一步是**关掉最后一个 sheet**：
            // 此时 `DatabaseBrowserScreen` 走的是「空态引导」分支，
            // `ActiveSheetContent` 整个从组合里消失 —— `remember` 随之丢失。
            // （切到**另一个已打开**的 sheet 不会拆组合，所以那条路测不出状态层的问题。）
            val connA = browseConnection("a")
            val browserA = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default)).also { it.selectPane(BrowserPane.SQL) }
            val sheetA =
                SheetDescriptor(
                    connection = connA,
                    browser = browserA,
                    status = ConnectionStatus(ConnectionState.DISCONNECTED, "H2"),
                )

            var openSheets by mutableStateOf(listOf(sheetA))
            var activeId by mutableStateOf(connA.id)
            setContent {
                MaterialTheme {
                    DatabaseBrowserScreen(
                        sheets = openSheets,
                        activeSheetId = activeId,
                        connections = listOf(connA),
                        onSelectSheet = {},
                        onCloseSheet = {},
                        onAddSheet = {},
                        onConnect = {},
                        onDisconnect = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            waitForIdle()

            val editorBefore = heightOf(SQL_EDITOR_PANE_TAG)
            dragHandleVertically(SQL_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()
            val dragged = heightOf(SQL_EDITOR_PANE_TAG)
            assertTrue(
                dragged > editorBefore,
                "前置条件：编辑器确实变高了（否则下面那条断言是空的）：before=$editorBefore after=$dragged",
            )

            // 关掉最后一个 sheet —— `ActiveSheetContent` 整个从组合里消失。
            openSheets = emptyList()
            activeId = ""
            waitForIdle()

            // 再打开一个 —— `ActiveSheetContent` 重新组合。
            openSheets = listOf(sheetA)
            activeId = connA.id
            waitForIdle()

            assertEquals(
                dragged,
                heightOf(SQL_EDITOR_PANE_TAG),
                "工作台被拆掉又重建之后，编辑器高度应当还是用户拖好的那个",
            )
        }

    private fun browseConnection(suffix: String) =
        ConnectionConfig(
            id = "editor-height-$suffix-${System.nanoTime()}",
            name = "Alpha$suffix",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = "jdbc:h2:mem:editor_height_$suffix;DB_CLOSE_DELAY=-1",
        )

    // ---------------------------------------------------------------------------
    // 分析模式
    // ---------------------------------------------------------------------------

    private val sources =
        listOf(
            AnalysisSourceItem(id = "c1", name = "本地 MySQL", dialectLabel = "MySQL"),
        )

    private fun ComposeUiTest.setAnalysisScreen(height: Dp? = null) {
        registerBuiltinEditors()
        setContent {
            SundaysTheme {
                ProvideStrings(AppLanguage.ZH_HANS) {
                    if (height == null) {
                        AnalysisScreen(sources = sources)
                    } else {
                        // 固定尺寸的容器 —— 用来模拟「窗口很矮」那种情形。
                        Box(Modifier.size(1400.dp, height)) {
                            AnalysisScreen(sources = sources)
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `in a short window the analysis result table keeps its floor`() =
        runComposeUiTest {
            // 与浏览模式同一件事：结果表的下限**优先于**编辑器的下限。
            // 结果表是分析模式的产出 —— 把它挤没了，用户看到的就是
            // 「跑完了但什么都没有」。
            setAnalysisScreen(height = 260.dp)

            assertTrue(
                heightOf(ANALYSIS_RESULT_PANEL_TAG) >= ANALYSIS_RESULT_PANEL_MIN_HEIGHT - 1.dp,
                "窗口很矮时结果表仍要保住下限 ${ANALYSIS_RESULT_PANEL_MIN_HEIGHT}：" +
                    "实际 ${heightOf(ANALYSIS_RESULT_PANEL_TAG)}",
            )
        }

    @Test
    fun `the analysis mode editor panel can be dragged taller`() =
        runComposeUiTest {
            setAnalysisScreen()

            val editorBefore = heightOf(ANALYSIS_EDITOR_PANEL_TAG)
            val resultBefore = heightOf(ANALYSIS_RESULT_TABLE_TAG)

            dragHandleVertically(ANALYSIS_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()

            val editorAfter = heightOf(ANALYSIS_EDITOR_PANEL_TAG)
            assertTrue(
                editorAfter > editorBefore,
                "分析模式往下拖应当让编辑器面板变高：before=$editorBefore after=$editorAfter",
            )
            val gained = editorAfter - editorBefore
            assertTrue(
                gained > dragDistance - touchSlopBudget,
                "拖拽应跟手（允许 touch slop 预算）：期望 ≥${dragDistance - touchSlopBudget}，实际 +$gained",
            )
            // 结果表让出来的高度应当**正好**等于编辑器多出来的高度。
            assertEquals(
                gained,
                resultBefore - heightOf(ANALYSIS_RESULT_TABLE_TAG),
                "编辑器多出来的高度应当正好从结果表那里来",
            )
        }

    @Test
    fun `the analysis mode editor can be dragged shorter`() =
        runComposeUiTest {
            setAnalysisScreen()

            // 先往下拖一次 —— 编辑器**可能本来就贴着下限**（窗口很矮时），
            // 那时直接往上拖是拖不动的。先把它拉起来，才谈得上「拖矮」。
            dragHandleVertically(ANALYSIS_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()
            val editorBefore = heightOf(ANALYSIS_EDITOR_PANEL_TAG)

            dragHandleVertically(ANALYSIS_EDITOR_DRAG_HANDLE_TAG, -dragDistance)
            waitForIdle()

            val editorAfter = heightOf(ANALYSIS_EDITOR_PANEL_TAG)
            assertTrue(
                editorAfter < editorBefore,
                "分析模式往上拖应当让编辑器面板变矮：before=$editorBefore after=$editorAfter",
            )
        }

    @Test
    fun `dragging the analysis editor down never squeezes the result table away`() =
        runComposeUiTest {
            // 结果表是分析模式的**产出** —— 把它拖没了，
            // 用户看到的就是「跑完了但什么都没有」。
            setAnalysisScreen()

            val editorBefore = heightOf(ANALYSIS_EDITOR_PANEL_TAG)

            // 拖到**顶死**为止：编辑器的上限是「总高 - 结果区下限」，
            // 所以拖够多次就一定会撞上上限 —— 那时结果区应当正好停在它的下限上。
            // 次数给够（10 次），确保「撞上限」这件事一定发生，
            // 而不是「还没拖到上限」就停手（那样这条断言就成了空断言）。
            repeat(10) {
                dragHandleVertically(ANALYSIS_EDITOR_DRAG_HANDLE_TAG, dragDistance)
                waitForIdle()
            }

            assertTrue(
                heightOf(ANALYSIS_EDITOR_PANEL_TAG) > editorBefore,
                "前置条件：编辑器确实变高了（否则下面那条断言是空的）",
            )
            // 判据是「结果区没有低于它的**下限**」，而不是「结果表还在」——
            // 后者太弱：一路拖到底也许只把它挤矮了一点点，
            // 而「数据看不见就等于白查」这条契约要的正是那个**下限**。
            assertTrue(
                heightOf(ANALYSIS_RESULT_PANEL_TAG) >= ANALYSIS_RESULT_PANEL_MIN_HEIGHT - 1.dp,
                "结果区不该被拖到低于它的下限 ${ANALYSIS_RESULT_PANEL_MIN_HEIGHT}：" +
                    "实际 ${heightOf(ANALYSIS_RESULT_PANEL_TAG)}",
            )
            assertTrue(
                heightOf(ANALYSIS_RESULT_TABLE_TAG) > 0.dp,
                "一路往下拖之后结果表仍在（当前高度 ${heightOf(ANALYSIS_RESULT_TABLE_TAG)}）",
            )
        }
}
