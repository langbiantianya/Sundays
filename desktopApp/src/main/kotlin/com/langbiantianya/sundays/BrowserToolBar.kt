/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 浏览器屏的**工具栏与标签条** —— sheet 标签、面板切换按钮、连接状态指示、连接失败横幅。

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.browser.BrowserToolBar
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.tree.TREE_PANE_MAX_WIDTH_RATIO
import com.langbiantianya.sundays.tree.TreePaneShell
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.str

/** 「多语句」开关的 UI 测试 tag —— 见 [SqlSheet.multiStatement]。 */
internal const val SQL_MULTI_STATEMENT_CHIP_TAG = "sqlMultiStatementChip"

/** 表预览工具条「清除」—— 只在有条件时出现，没有 tag 就只能靠猜它是不是在。 */
internal const val TABLE_CLEAR_BTN_TAG = "tableClearBtn"

/**
 * 激活 sheet 的内容：连接选择条 + 左侧 schema 树 + 右侧 preview 标签页区。
 * 与原单连接 [DatabaseBrowserScreen] 行为完全一致 —— 只是 `bindConnection` / `refreshDatabases`
 * 作用的 [browser] 由激活 sheet 持有，与其它 sheet 互不影响。
 *
 * @param schemaPanelWidth 左树面板的宽度 —— **窗口级偏好**，由 [DatabaseBrowserScreen] 持有
 * @param onSchemaPanelWidthChange 拖拽分隔条时的实时回调
 * @param schemaPanelCollapsed 左树面板是不是折叠成了贴边的窄轨
 * @param onSchemaPanelCollapsedChange 折叠 / 展开时的回调
 */
@Composable
internal fun ActiveSheetContent(
    sheet: SheetDescriptor,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    schemaPanelWidth: Dp,
    onSchemaPanelWidthChange: (Dp) -> Unit,
    schemaPanelCollapsed: Boolean,
    onSchemaPanelCollapsedChange: (Boolean) -> Unit,
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
            val maxPanelWidth = maxWidth * TREE_PANE_MAX_WIDTH_RATIO
            Row(modifier = Modifier.fillMaxSize()) {
                // 树面板外壳 —— **折叠到侧边**与**拖拽调宽**两件事都在里面，
                // 与分析模式的左树是同一个组件（`TreePaneShell`）。
                TreePaneShell(
                    collapsed = schemaPanelCollapsed,
                    onToggleCollapsed = { onSchemaPanelCollapsedChange(!schemaPanelCollapsed) },
                    width = schemaPanelWidth,
                    onWidthChange = onSchemaPanelWidthChange,
                    defaultWidth = SCHEMA_PANEL_DEFAULT_WIDTH,
                    minWidth = SCHEMA_PANEL_MIN_WIDTH,
                    maxWidth = maxPanelWidth,
                    collapsedIcon = Icons.Filled.Folder,
                    // testTag：分隔条画出来只有 1dp 宽，没有 tag 的话测试只能靠坐标猜
                    handleModifier = Modifier.testTag(SCHEMA_DRAG_HANDLE_TAG),
                    // 悬停时换「左右拉伸」指针：分隔条在鼠标下必须看起来可拖，
                    // 否则拖拽功能「存在但没人发现」—— 指针始终是箭头时没人会去试。
                    hoverIcon = ewResizePointerIcon(),
                ) { paneModifier ->
                    SchemaTreePanel(
                        state = sheet.browser,
                        connected = sheet.status.state == ConnectionState.CONNECTED,
                        onOpenTable = { schema, table -> sheet.browser.openTab(schema, table) },
                        onCollapse = { onSchemaPanelCollapsedChange(!schemaPanelCollapsed) },
                        modifier = paneModifier,
                    )
                }
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
