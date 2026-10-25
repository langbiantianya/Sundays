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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
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
 * 造数工作台的**编辑器高度可调** + **日志与结果合并成一个输出面板**。
 *
 * ## 为什么造数工作台也要能拖
 *
 * 它与 SQL 工作台是同一个形状（上面一个编辑器、下面一个输出区），而此前
 * 造数那侧的高度是**写死的比例**（编辑器 45% / 日志 25% / 结果 30%）。
 * 比例是编译期定死的，而两种诉求互相打架：
 *
 * - 写一段几百行的 Lua 循环时，编辑器那点高度不够 —— 一屏只看得到十几行；
 * - 只想看一眼「刚才那批数据长什么样」时，输出区却占掉七成屏。
 *
 * 两边共用 `:shared` 的 `HorizontalDragHandle`，手感必然一致。
 *
 * ## 四条判据
 *
 * 1. **拖得动**：拖那条缝，编辑器的高度**真的变**，而且是跟手的（不是松手才跳），
 *    并且**输出面板等量反向** —— 高度守恒，不是凭空多出来的。
 * 2. **挤不没输出面板**：输出面板有**下限**（[MIN_GENERATE_OUTPUT_HEIGHT]）——
 *    日志看不见就等于没有日志，拖拽**不能**把它破坏掉。
 * 3. **是窗口级偏好**：工作台被拆掉又重建之后，高度还在（与左树宽度、SQL 编辑器高度同一层）。
 * 4. **日志与结果在同一个面板里**：这是这次「合并」本身要立的契约 ——
 *    此前它们是**两个**各自独立的 `Surface`，各自带标题栏、各占一块比例。
 */
@OptIn(ExperimentalTestApi::class)
class GenerateEditorHeightTest {
    /**
     * 允许损失的 touch slop（dp）—— `detectDragGestures` 判定「这是拖拽」之前吃掉的那段位移。
     *
     * 与 `SqlEditorHeightTest` 取同一个预算：具体数值由平台的 `ViewConfiguration.touchSlop` 决定，
     * 测试环境不该因为某个平台的 slop 是 18 还是 20 就红。
     */
    private val touchSlopBudget = 24.dp

    /** 拖拽的距离 —— 足够大，能盖过 slop，又不至于把输出面板压没。 */
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
    // 工作台
    // ---------------------------------------------------------------------------

    /**
     * 渲染造数工作台。
     *
     * @param height 容器高度 —— 决定「编辑器 + 输出面板」这一段有多高。
     *   拖拽能不能发生**取决于剩余空间**：输出面板有下限，编辑器长到顶就长不动了。
     */
    private fun ComposeUiTest.setGenerateWorkbench(height: Dp) {
        registerBuiltinEditors()
        val state = DatabaseBrowserState(NoopEngine, CoroutineScope(Dispatchers.Default))
        // 高度是**受控**的：工作台自己不持有，由外面拿着 ——
        // 与真实用法一致（`DatabaseBrowserScreen` 持有，切 sheet 才不丢）。
        // 不接上这个回调的话，拖拽会「拖了没反应」——
        // 组件把新高度回调出去了，只是没人接。
        var editorHeight by mutableStateOf<Dp?>(null)
        setContent {
            SundaysTheme {
                ProvideStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.size(760.dp, height)) {
                        GenerateWorkbenchPane(
                            state = state,
                            connected = true,
                            schema = "smoke",
                            editorHeight = editorHeight,
                            onEditorHeightChange = { editorHeight = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the generate editor can be dragged taller`() =
        runComposeUiTest {
            setGenerateWorkbench(1000.dp)

            val editorBefore = heightOf(GENERATE_EDITOR_PANE_TAG)
            val outputBefore = heightOf(GENERATE_OUTPUT_PANEL_TAG)

            dragHandleVertically(GENERATE_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()

            val editorAfter = heightOf(GENERATE_EDITOR_PANE_TAG)
            val outputAfter = heightOf(GENERATE_OUTPUT_PANEL_TAG)

            val gained = editorAfter - editorBefore
            assertTrue(
                gained > 0.dp,
                "往下拖应当让编辑器变高：before=$editorBefore after=$editorAfter",
            )
            // 跟手：拖多少就得多少（只允许 touch slop 那点损失）。
            // 「松手才跳」的实现也能满足上面那条，但这条钉不住 —— 跟手是手感。
            assertTrue(
                gained > dragDistance - touchSlopBudget,
                "拖拽应跟手（允许 touch slop 预算）：期望 ≥${dragDistance - touchSlopBudget}，实际 +$gained",
            )
            // 高度是**守恒**的：编辑器多出来的那一块，正好是从输出面板那里拿的。
            // 不是守恒的话，另一块就会凭空变大或变小 —— 拖一下整个布局都跟着变形。
            assertEquals(
                gained.value,
                (outputBefore - outputAfter).value,
                1f,
                "编辑器多出来的高度应当正好从输出面板那里来",
            )
        }

    @Test
    fun `the generate editor can be dragged shorter`() =
        runComposeUiTest {
            setGenerateWorkbench(1000.dp)

            // 先往下拖一次 —— 编辑器**可能本来就贴着下限**，那时直接往上拖是拖不动的。
            dragHandleVertically(GENERATE_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()
            val editorBefore = heightOf(GENERATE_EDITOR_PANE_TAG)

            dragHandleVertically(GENERATE_EDITOR_DRAG_HANDLE_TAG, -dragDistance)
            waitForIdle()

            val editorAfter = heightOf(GENERATE_EDITOR_PANE_TAG)
            assertTrue(
                editorAfter < editorBefore,
                "往上拖应当让编辑器变矮：before=$editorBefore after=$editorAfter",
            )
        }

    @Test
    fun `dragging the generate editor down never squeezes the output panel below its floor`() =
        runComposeUiTest {
            // 输出面板是造数过程的**唯一**窗口 —— 日志看不见就等于没有日志。
            // 拖拽不能突破它的下限。
            setGenerateWorkbench(1000.dp)

            // 一路往下拖 —— 远超「输出面板能退让」的幅度。
            repeat(8) {
                dragHandleVertically(GENERATE_EDITOR_DRAG_HANDLE_TAG, dragDistance)
                waitForIdle()
            }

            assertTrue(
                heightOf(GENERATE_OUTPUT_PANEL_TAG) >= MIN_GENERATE_OUTPUT_HEIGHT - 1.dp,
                "输出面板不该被拖到低于它的下限 ${MIN_GENERATE_OUTPUT_HEIGHT}：" +
                    "实际 ${heightOf(GENERATE_OUTPUT_PANEL_TAG)}",
            )
        }

    @Test
    fun `in a short window the output panel keeps its floor and the editor yields`() =
        runComposeUiTest {
            // 窗口很矮时，编辑器的下限（96dp）**要让位**给输出面板的下限。
            //
            // 这条在 SQL 工作台那边真的错过一次：编辑器那 96dp 的下限当时是硬的，
            // 把结果区压到了下限以下，破了「结果区至少 180dp」的既有契约。
            // 同一个坑不该在这里再踩一遍。
            setGenerateWorkbench(480.dp)

            assertTrue(
                heightOf(GENERATE_OUTPUT_PANEL_TAG) >= MIN_GENERATE_OUTPUT_HEIGHT - 1.dp,
                "窗口很矮时输出面板仍要保住下限 ${MIN_GENERATE_OUTPUT_HEIGHT}：" +
                    "实际 ${heightOf(GENERATE_OUTPUT_PANEL_TAG)}",
            )
        }

    @Test
    fun `the log and the result table live in the same output panel`() =
        runComposeUiTest {
            // 这就是「合并」本身要立的契约：日志与结果表**在同一个面板里**。
            //
            // 此前它们是两个各自独立的 `Surface`，各自带一个标题栏、各占一块比例。
            // 判据打在「共同的祖先面板」上 —— 只断言「两个都存在」的话，
            // 拆回两个面板也照样通过。
            setGenerateWorkbench(1000.dp)

            onNodeWithTag(GENERATE_LOG_PANEL_TAG)
                .assert(hasAnyAncestor(hasTestTag(GENERATE_OUTPUT_PANEL_TAG)))
            onNodeWithTag(GENERATE_RESULT_AREA_TAG)
                .assert(hasAnyAncestor(hasTestTag(GENERATE_OUTPUT_PANEL_TAG)))
        }

    @Test
    fun `the generate editor height survives the workbench being torn down and rebuilt`() =
        runComposeUiTest {
            // 高度是**窗口级偏好** —— 与左树面板宽度、SQL 编辑器高度同一层。
            // 写在 `ActiveSheetContent` 里的话，那棵组合一旦被拆掉重建，
            // 高度就复位了（「在 A 连接把编辑器拉高，关掉再打开又缩回去」）。
            //
            // 触发「组合被拆掉」的那一步是**关掉最后一个 sheet**：
            // 此时 `DatabaseBrowserScreen` 走的是「空态引导」分支，
            // `ActiveSheetContent` 整个从组合里消失 —— `remember` 随之丢失。
            val conn = generateConnection()
            val browser = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default)).also { it.selectPane(BrowserPane.GENERATE) }
            val sheet =
                SheetDescriptor(
                    connection = conn,
                    browser = browser,
                    status = ConnectionStatus(ConnectionState.DISCONNECTED, "H2"),
                )

            var openSheets by mutableStateOf(listOf(sheet))
            var activeId by mutableStateOf(conn.id)
            setContent {
                MaterialTheme {
                    DatabaseBrowserScreen(
                        sheets = openSheets,
                        activeSheetId = activeId,
                        connections = listOf(conn),
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

            val editorBefore = heightOf(GENERATE_EDITOR_PANE_TAG)
            dragHandleVertically(GENERATE_EDITOR_DRAG_HANDLE_TAG, dragDistance)
            waitForIdle()
            val dragged = heightOf(GENERATE_EDITOR_PANE_TAG)
            assertTrue(
                dragged > editorBefore,
                "前置条件：编辑器确实变高了（否则下面那条断言是空的）：before=$editorBefore after=$dragged",
            )

            // 关掉最后一个 sheet —— `ActiveSheetContent` 整个从组合里消失。
            openSheets = emptyList()
            activeId = ""
            waitForIdle()

            // 再打开一个 —— `ActiveSheetContent` 重新组合。
            openSheets = listOf(sheet)
            activeId = conn.id
            waitForIdle()

            assertEquals(
                dragged,
                heightOf(GENERATE_EDITOR_PANE_TAG),
                "工作台被拆掉又重建之后，编辑器高度应当还是用户拖好的那个",
            )
        }

    private fun generateConnection() =
        ConnectionConfig(
            id = "generate-height-${System.nanoTime()}",
            name = "Alpha",
            dialect = DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = "jdbc:h2:mem:generate_height_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
}
