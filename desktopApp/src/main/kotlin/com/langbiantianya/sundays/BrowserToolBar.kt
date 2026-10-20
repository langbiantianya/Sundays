/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 浏览器屏的**工具栏与标签条** —— sheet 标签、面板切换按钮、连接状态指示、连接失败横幅。

package com.langbiantianya.sundays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.dataGenerateRequest
import com.langbiantianya.grpc.dataListRequest
import com.langbiantianya.grpc.dataRequest
import com.langbiantianya.grpc.foreignKeyCreateRequest
import com.langbiantianya.grpc.foreignKeyDeleteRequest
import com.langbiantianya.grpc.foreignKeyListItem
import com.langbiantianya.grpc.foreignKeyListRequest
import com.langbiantianya.grpc.foreignKeyRequest
import com.langbiantianya.grpc.functionListRequest
import com.langbiantianya.grpc.functionRequest
import com.langbiantianya.grpc.generateTable
import com.langbiantianya.grpc.indexListRequest
import com.langbiantianya.grpc.indexRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.schemaListRequest
import com.langbiantianya.grpc.schemaRequest
import com.langbiantianya.grpc.sqlExecuteRequest
import com.langbiantianya.grpc.sqlRequest
import com.langbiantianya.grpc.systemRequest
import com.langbiantianya.grpc.tableColumnListRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableDeleteRequest
import com.langbiantianya.grpc.tableGetDdlRequest
import com.langbiantianya.grpc.tableListRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.grpc.tableUpdateRequest
import com.langbiantianya.grpc.triggerListRequest
import com.langbiantianya.grpc.triggerRequest
import com.langbiantianya.grpc.viewListRequest
import com.langbiantianya.grpc.viewRequest
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.ui.DragHandle
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SettingsEntryButton
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.menuShape
import com.langbiantianya.sundays.ui.onRightClick
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.tabStripContainerColor
import com.langbiantianya.sundays.ui.winShape
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 「多语句」开关的 UI 测试 tag —— 见 [SqlSheet.multiStatement]。 */
internal const val SQL_MULTI_STATEMENT_CHIP_TAG = "sqlMultiStatementChip"

// ============================================================================
// 键盘可达性：testTag 与可见焦点环
//
// 真窗口 GUI 走查卡死在这两件事上（见 TEST_CASES.md §9.9）：
//   - 定位一个控件只能靠「按 N 次 Tab + 截图猜」，而**大量控件不画焦点指示**，
//     猜错一次就可能误触别的功能（实测误开过「添加数据库连接」弹窗）；
//   - **disabled 的按钮不在 Tab 序里**，于是「再一格就是它」这种推断直接失效。
//
// 所以下面这一组 tag 不是「给测试方便」的小事，而是**键盘可自动化的前提**。
// ============================================================================

/** 左侧「＋添加连接」按钮。 */
private const val SHEET_ADD_BTN_TAG = "sheetAddBtn"

/** 工作台切换按钮（SQL / 造数）。 */
private const val PANE_TOGGLE_SQL_TAG = "paneToggleSql"

private const val PANE_TOGGLE_GENERATE_TAG = "paneToggleGenerate"

/** 表预览工具条「清除」—— 只在有条件时出现，没有 tag 就只能靠猜它是不是在。 */
internal const val TABLE_CLEAR_BTN_TAG = "tableClearBtn"

/** sheet 标签与其关闭按钮 —— 名字会重复（同名连接可开多个 sheet），所以按 id 定位。 */
private fun sheetTabTag(connectionId: String) = "sheetTab_$connectionId"

private fun sheetTabCloseTag(connectionId: String) = "sheetTabClose_$connectionId"

/** 工作台切换按钮按 pane 定位。 */
private fun paneToggleTag(pane: BrowserPane): String =
    when (pane) {
        BrowserPane.TABLE -> "paneToggleTable"
        BrowserPane.SQL -> PANE_TOGGLE_SQL_TAG
        BrowserPane.GENERATE -> PANE_TOGGLE_GENERATE_TAG
    }

// ============================================================================
// Sheet 标签条 / 数据列表：并 ＋ 入口
// ============================================================================

@Composable
internal fun SheetTabRow(
    sheets: List<SheetDescriptor>,
    activeSheetId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「＋添加连接」按钮：固定在最左，独立于 sheet 标签条；
        // 点击后由调用方弹出 AddConnectionDialog。
        WinIconButton(
            onClick = onAdd,
            modifier =
                Modifier
                    .padding(horizontal = 4.dp)
                    .testTag(SHEET_ADD_BTN_TAG),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = str(StrKey.ADD_CONNECTION),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        VerticalDivider(
            modifier = Modifier.height(28.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SecondaryScrollableTabRow(
            selectedTabIndex = sheets.indexOfFirst { it.connection.id == activeSheetId }.coerceAtLeast(0),
            edgePadding = 0.dp,
            containerColor = tabStripContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            modifier = Modifier.weight(1f),
        ) {
            sheets.forEach { sheet ->
                val active = sheet.connection.id == activeSheetId
                Tab(
                    selected = active,
                    onClick = { onSelect(sheet.connection.id) },
                    modifier = Modifier.testTag(sheetTabTag(sheet.connection.id)),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionStatusDot(sheet.status.state, 10.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(text = sheet.connection.name, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = str(StrKey.CLOSE_SHEET),
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .testTag(sheetTabCloseTag(sheet.connection.id))
                                        .clickable { onClose(sheet.connection.id) },
                            )
                        }
                    },
                )
            }
        }
        // 设置入口（⚙）钉在**最外层**右上角：与左侧「＋」同一行、同高 ——
        // 不占用内容区高度，也不随工作台切换 / sheet 内容变化而移动。
        // 日夜切换**已从本屏移除**，明暗改到设置页的「个性化 → 明暗档位」。
        SettingsEntryButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 4.dp))
    }
}

@Composable
internal fun EmptySheetsHint(
    onAddSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 空态下没有标签条也没有工具栏，设置入口若不单独放一个就会彻底消失 ——
        // 而空态恰恰是用户第一次打开应用最可能停留的地方。
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
        ) {
            SettingsEntryButton(onClick = onOpenSettings)
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = str(StrKey.NO_OPEN_CONNECTION),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = str(StrKey.EMPTY_CONNECTION_HINT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            WinButton(onClick = onAddSheet, shape = SundaysPalette.buttonShape) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(str(StrKey.ADD_CONNECTION))
            }
        }
    }
}

@Composable
private fun BrowserToolBar(
    activePane: BrowserPane,
    connected: Boolean,
    runningTaskCount: Int,
    taskPanelOpen: Boolean,
    onToggleTaskPanel: () -> Unit,
    onSelectPane: (BrowserPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneToggleButton(
                pane = BrowserPane.SQL,
                activePane = activePane,
                label = str(StrKey.SQL_WORKBENCH),
                icon = Icons.Filled.PlayArrow,
                enabled = connected,
                onSelect = onSelectPane,
            )
            PaneToggleButton(
                pane = BrowserPane.GENERATE,
                activePane = activePane,
                label = str(StrKey.GENERATE_WORKBENCH),
                icon = Icons.Filled.Bolt,
                enabled = connected,
                onSelect = onSelectPane,
            )
            if (!connected) {
                Text(
                    text = str(StrKey.WORKBENCH_NOT_CONNECTED),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            WinButton(
                onClick = onToggleTaskPanel,
                shape = SundaysPalette.buttonShape,
                colors =
                    if (taskPanelOpen) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                modifier = Modifier.testTag(TASK_PANEL_BTN_TAG),
            ) {
                Icon(
                    imageVector = if (runningTaskCount > 0) Icons.Filled.Bolt else Icons.Filled.TableChart,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(str(StrKey.TASK), maxLines = 1, softWrap = false)
                if (runningTaskCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "($runningTaskCount)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 单个工作台切换按钮 —— 处于该 pane 时显示「返回表预览」（实心），否则显示工作台名（描边）。
 */
@Composable
private fun PaneToggleButton(
    pane: BrowserPane,
    activePane: BrowserPane,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onSelect: (BrowserPane) -> Unit,
) {
    val isActive = activePane == pane
    WinButton(
        onClick = { onSelect(if (isActive) BrowserPane.TABLE else pane) },
        enabled = enabled,
        shape = SundaysPalette.buttonShape,
        // 复古两套下由 WinButton 把斜面翻转为凹陷；现代主题仍用 primary 填充表达激活态
        selected = isActive,
        modifier = Modifier.testTag(paneToggleTag(pane)),
        colors =
            if (isActive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
    ) {
        Icon(
            imageVector = if (isActive) Icons.Filled.TableChart else icon,
            contentDescription = null,
        )
        Spacer(Modifier.width(6.dp))
        Text(if (isActive) str(StrKey.BACK_TO_PREVIEW) else label)
    }
}

/**
 * 激活 sheet 的内容：连接选择条 + 左侧 schema 树 + 右侧 preview 标签页区。
 * 与原单连接 [DatabaseBrowserScreen] 行为完全一致 —— 只是 `bindConnection` / `refreshDatabases`
 * 作用的 [browser] 由激活 sheet 持有，与其它 sheet 互不影响。
 */
@Composable
internal fun ActiveSheetContent(
    sheet: SheetDescriptor,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    schemaPanelWidth: Dp,
    onSchemaPanelWidthChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 注入当前连接（连接变化时清空派生状态），随后在已连接时自动加载数据库列表。
    LaunchedEffect(sheet.connection.id, sheet.status.state) {
        sheet.browser.bindConnection(sheet.connection)
        when {
            sheet.status.state == ConnectionState.CONNECTED -> sheet.browser.refreshDatabases()
            sheet.status.state == ConnectionState.DISCONNECTED -> sheet.browser.releasePools()
            else -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 任务面板的开关是**纯 UI 状态**，放在 composable 的 remember 里而不是状态机里 ——
        // 面板收起不该被「换个 pane 就重置」这种语义波及，而它本身也不需要活过组合。
        var taskPanelOpen by remember(sheet.browser) { mutableStateOf(false) }
        // 工具栏归属当前激活 sheet —— 放在内容区内（而非 sheet 标签条之上），
        // 这样工具与它作用的连接在同一视觉块内，切换 sheet 时工具栏也随之更换。
        BrowserToolBar(
            activePane = sheet.browser.activePane,
            connected = sheet.status.state == ConnectionState.CONNECTED,
            runningTaskCount = sheet.browser.runningTasks.size,
            taskPanelOpen = taskPanelOpen,
            onToggleTaskPanel = { taskPanelOpen = !taskPanelOpen },
            onSelectPane = sheet.browser::selectPane,
        )
        // 任务面板紧贴工具栏之下 —— 它是那个「任务 (N)」按钮的直接延伸，
        // 而不是漂在别处的浮层：用户点按钮是为了「就在这儿看」，不是「去某个地方看」。
        if (taskPanelOpen) {
            BackgroundTaskPanel(
                tasks = sheet.browser.backgroundTasks,
                onClearFinished = sheet.browser::clearFinishedTasks,
            )
        }
        // 连接失败原因此前**只在连接管理页**能看到，浏览屏上只有一个红点 ——
        // 用户在浏览屏看到红点，却不知道是密码错了、网络断了还是驱动没装，
        // 只能切回去猜。失败原因就在手边的 [ConnectionStatus.message] 里，
        // 这里直接摆出来，不用他来回切屏。
        if (sheet.status.state == ConnectionState.FAILED) {
            ConnectionFailureBanner(
                message = sheet.status.message,
                onRetry = { onConnect(sheet.connection) },
            )
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 宽度上限按容器比例给，而不是固定 dp：窗口窄时若还允许拉到 600dp，
        // 右侧工作台会被挤到没法用。0.62 留足了右栏的最低可读宽度。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxPanelWidth = maxWidth * 0.62f
            Row(modifier = Modifier.fillMaxSize()) {
                SchemaTreePanel(
                    state = sheet.browser,
                    connected = sheet.status.state == ConnectionState.CONNECTED,
                    onOpenTable = { schema, table -> sheet.browser.openTab(schema, table) },
                    modifier =
                        Modifier
                            .width(schemaPanelWidth)
                            .fillMaxHeight(),
                )
                DragHandle(
                    width = schemaPanelWidth,
                    onWidthChange = onSchemaPanelWidthChange,
                    defaultWidth = SCHEMA_PANEL_DEFAULT_WIDTH,
                    minWidth = SCHEMA_PANEL_MIN_WIDTH,
                    maxWidth = maxPanelWidth,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    highlightColor = MaterialTheme.colorScheme.primary,
                    // 悬停时换「左右拉伸」指针：分隔条在鼠标下必须看起来可拖，
                    // 否则拖拽功能「存在但没人发现」—— 指针始终是箭头时没人会去试。
                    hoverIcon = ewResizePointerIcon(),
                    // testTag：分隔条画出来只有 1dp 宽，没有 tag 的话测试只能靠坐标猜
                    modifier = Modifier.testTag(SCHEMA_DRAG_HANDLE_TAG),
                )
                when (sheet.browser.activePane) {
                    BrowserPane.TABLE -> {
                        PreviewTabArea(
                            state = sheet.browser,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }

                    BrowserPane.SQL -> {
                        SqlWorkbenchPane(
                            state = sheet.browser,
                            connected = sheet.status.state == ConnectionState.CONNECTED,
                            schema = sheet.browser.currentSchema(),
                            dialect = sheet.browser.sqlDialectProfile(),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }

                    BrowserPane.GENERATE -> {
                        GenerateWorkbenchPane(
                            state = sheet.browser,
                            connected = sheet.status.state == ConnectionState.CONNECTED,
                            schema = sheet.browser.currentSchema(),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 连接状态点 —— sheet 标签上展示引擎会话状态的小色点。
 */
@Composable
private fun ConnectionStatusDot(
    state: ConnectionState,
    dotSize: androidx.compose.ui.unit.Dp,
) {
    val color =
        when (state) {
            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
            ConnectionState.FAILED -> MaterialTheme.colorScheme.error
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
        }
    Canvas(modifier = Modifier.size(dotSize)) {
        drawCircle(color = color)
    }
}

/**
 * 连接失败横幅 —— 浏览屏上的**唯一**失败原因出口。
 *
 * ## 为什么不是 tooltip
 *
 * tooltip 只在指针悬停时出现，而「为什么连不上」是用户**必须**看到才能继续处理的信息：
 * 他可能根本没往色点上放鼠标，或者只瞥了一眼就走了。tooltip 适合补充信息，不适合承载
 * 唯一的诊断入口。横幅是常驻的、切屏也带得走。
 *
 * ## 为什么可滚动
 *
 * 引擎回的失败原因常是一整段带 `Caused by` 的堆栈（几百字符），与 `EmptyHint` 同一个理由：
 * 关键信息常常在**末尾**，横向或纵向一刀切掉的话用户看到的等于没有。
 * 固定高度 + 纵向滚动是这里唯一诚实的选择（详见 `EmptyHint` 的 KDoc）。
 */
@Composable
private fun ConnectionFailureBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 标签「连接失败」不省略号截断 —— 它是这句横幅在语义上的主语
            Text(
                text = str(StrKey.CONNECT_FAILED),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.ifBlank { str(StrKey.NO_REASON_FROM_ENGINE) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // 失败原因多半是「网络断了」这类瞬时问题，就地重试比让他切去连接管理页再切回来顺手。
            // 密码错了也一样 —— 重试一次只是再失败一次，不会有副作用。
            //
            // 不刻意收窄内边距：`WinButton` 没有 contentPadding 参数（v2.21 评估后认定
            // 「为省 30dp 给共享控件加参数」不划算），而 `Modifier.padding` 是**外**缩 —
            // 按钮宽度由 MinWidth 与内容决定，外层 padding 不会让它变窄。按默认尺寸即可，
            // 顺带满足 48dp 的点击区。
            WinButton(onClick = onRetry, shape = SundaysPalette.buttonShape) {
                Text(str(StrKey.RETRY), maxLines = 1, softWrap = false)
            }
        }
    }
}
