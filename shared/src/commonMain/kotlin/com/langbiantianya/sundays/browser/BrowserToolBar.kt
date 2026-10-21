/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.ui.SettingsEntryButton
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.tabStripContainerColor

// ============================================================================
// 键盘可达性：testTag 与可见焦点环
//
// 真窗口 GUI 走查卡死在这两件事上（见 desktopApp 的 TEST_CASES.md §9.9）：
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

/**
 * sheet 标签条 —— 每个已打开的连接一个标签，外加「＋添加连接」与设置入口。
 *
 * 它只认 [SheetSummary]（窄形状），不认持有引擎状态的 `SheetDescriptor` ——
 * 理由见 [SheetSummary] 的 KDoc。
 */
@Composable
fun SheetTabRow(
    sheets: List<SheetSummary>,
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
            selectedTabIndex = sheets.indexOfFirst { it.id == activeSheetId }.coerceAtLeast(0),
            edgePadding = 0.dp,
            containerColor = tabStripContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            modifier = Modifier.weight(1f),
        ) {
            sheets.forEach { sheet ->
                val active = sheet.id == activeSheetId
                Tab(
                    selected = active,
                    onClick = { onSelect(sheet.id) },
                    modifier = Modifier.testTag(sheetTabTag(sheet.id)),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionStatusDot(sheet.status.state, 10.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(text = sheet.name, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = str(StrKey.CLOSE_SHEET),
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .testTag(sheetTabCloseTag(sheet.id))
                                        .clickable { onClose(sheet.id) },
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

/** 一个 sheet 都没有时的空态提示。 */
@Composable
fun EmptySheetsHint(
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

/**
 * 工作台工具条 —— SQL / 造数两个切换按钮 + 后台任务入口。
 *
 * 它只读**参数**里的状态（当前 pane、是否已连接、后台任务数），不持有任何状态 ——
 * 所以它能待在 `:shared` 里，而不用管那些状态住在哪。
 */
@Composable
fun BrowserToolBar(
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
    icon: ImageVector,
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

/** 工具栏「任务」按钮的 UI 测试 tag。 */
const val TASK_PANEL_BTN_TAG = "backgroundTaskPanelBtn"
