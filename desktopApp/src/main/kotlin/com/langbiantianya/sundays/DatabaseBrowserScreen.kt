/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 数据库浏览第二屏的**入口** —— 只做三件事：算布局、把 sheet 内容交给 `ActiveSheetContent`、
// 把屏级共享的副作用（内存面板、通知中心、表结构弹窗、删表确认）挂在最外层。
// 这里**刻意不放**任何分面板的实现。原先这些都在同一个文件里，两千行上下全是界面代码，
// 改一个弹窗要在满屏滚动里找它的状态字段。现在按功能域分文件，见 desktopApp/ARCHITECTURE.md。

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.sundays.browser.EmptySheetsHint
import com.langbiantianya.sundays.browser.SheetTabRow
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.notifications.AppNotification
import com.langbiantianya.sundays.notifications.NotificationBell
import com.langbiantianya.sundays.notifications.NotificationCenter
import com.langbiantianya.sundays.notifications.NotificationCenterPanel
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.ui.WinDivider
import kotlinx.coroutines.delay

/**
 * 数据库浏览屏幕 —— 第二屏；**多 sheet**：每个已建连接的数据库浏览会话是一个 sheet，
 * sheet 之间互不干扰（左树 / preview 标签页 / 已展开节点 / SQL 工作台状态各自独立）。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │ Sheets:  [＋] [ Conn A ×] [ Conn B ×] ...                               │
 * ├────────────────────────────────────────────────────────────────────────┤
 * │ ToolBar:  [ ▶ SQL 工作台 ]  ← 归属下方这块 sheet 内容                    │
 * ├──────────────┬─────────────────────────────────────────────────────────┤
 * │              │  ┌── Table Preview Tab Area ──┐  切换到 SQL 工作台时:     │
 * │  Schemas     │  │ Tab: [ users | orders ]    │                          │
 * │   ▾ PUBLIC   │  ├───────────────────────────┤  ┌─ 编辑器 (60%) ──────┐ │
 * │      ▸ users │  │  DataTable(rows=preview)   │  │ SELECT * FROM ...  │ │
 * │      ▸ orders│  │                            │  ├─────────────────────┤ │
 * │   ▸ MYDB     │  │                            │  │ 结果 (40%)          │ │
 * │              │  └───────────────────────────┘  └─────────────────────┘ │
 * └──────────────┴─────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 交互
 *
 * - **「＋」sheet 标签**：调 [onAddSheet]，由调用方弹出 [AddConnectionDialog]。
 * - **切换 sheet**：调 [onSelectSheet]，目标 sheet 获得焦点；其它 sheet 的状态保留。
 * - **关闭 sheet**：「×」调 [onCloseSheet]；同时释放该 sheet 的连接池并断开引擎会话。
 * - 选中 sheet 的连接后，左侧自动加载数据库列表(`SCHEMA.LIST level=database`)
 * - 展开数据库节点 → 加载表列表(`TABLE.LIST`)
 * - **双击**表节点 → 打开预览标签页(同一表只存在一个标签页)
 * - 标签页关闭 → `removeTab`; 切换标签页只切换显示, 不重新加载
 * - **「▶ SQL 工作台」工具栏按钮** → 把右栏从表预览切换为 SQL 工作台
 *   （编辑器 + 结果面板），由 `DatabaseBrowserState.activePane` 控制；再次点击切回。
 *   工作台内是**多个 SQL sheet**（标签条：＋ 新建 / ✎ 重命名 / × 关闭 / 滚轮横向滚动），
 *   每个 sheet 一份独立 SQL 与独立结果。
 *   点 **「执行 SQL」** 走 `SQL.EXECUTE` 引擎流式：SELECT 行帧 → 结果表；
 *   非 SELECT（DML/DDL）→ 单条响应带 `affected_rows`；失败 → 错误条。
 *
 * ## 引擎耦合
 *
 * 通过 [EngineClient.invoke] 走强类型 `SCHEMA.LIST` / `TABLE.LIST` / `DATA.LIST` 路径
 * (与 `ConnectionManagerScreen` 的调用方式一致 —— 详见 desktopApp/ARCHITECTURE.md §引擎接入).
 * SQL 执行额外使用 [EngineClient.handle] 走 `Category.SQL` / `Action.EXECUTE` 流式通道，
 * 收集 `sql_row_frame` + 终止帧 (`RequestDispatcher.streamSqlExecute`) 拼装结果。
 *
 * 状态由调用方持有（[DatabaseBrowserState]，在 `MainScreen` 中按 sheet id 各自 `remember`），
 * 本组件只负责渲染 + 事件转发 + sheet 副作用 —— 因此同一份状态机可脱离 UI 直接驱动
 * （见 `DatabaseBrowserFlowTest`）。
 *
 * @param sheets 已打开 sheet 的描述列表（连接 + 独立 `DatabaseBrowserState` + 当前引擎状态）
 * @param activeSheetId 当前激活的 sheet id（必须在 [sheets] 中）
 * @param connections 全部已保存连接（备用，UI 当前未直接渲染 —— 切换 sheet 在 [onSelectSheet]）
 * @param onSelectSheet 切换激活 sheet
 * @param onCloseSheet 关闭 sheet（调 [DatabaseBrowserState.releasePools] 释放浏览器侧派生池 + 调用方断开引擎会话）
 * @param onAddSheet 「＋」点击回调 —— 调用方弹出 [AddConnectionDialog]
 * @param onConnect 建立连接（调用方经 `EngineClient.testConnection`）
 * @param onDisconnect 断开连接（调用方经 `EngineClient.disconnect`）
 */
@Composable
fun DatabaseBrowserScreen(
    sheets: List<SheetDescriptor>,
    activeSheetId: String?,
    connections: List<ConnectionConfig>,
    onSelectSheet: (String) -> Unit,
    onCloseSheet: (String) -> Unit,
    onAddSheet: () -> Unit,
    onConnect: (ConnectionConfig) -> Unit,
    onDisconnect: (ConnectionConfig) -> Unit,
    onOpenSettings: () -> Unit = {},
    memoryProbe: (suspend () -> EngineMemory?)? = null,
    /**
     * 屏级共享的通知中心。
     *
     * 由调用方 `remember` 一个传进来，而不是本函数内部建 ——
     * 因为 [DatabaseBrowserState] 也要往里推任务成败通知，而那些 state 由调用方创建，
     * 两边必须是**同一个**实例。默认参数给一个空实现是为了让既有测试不必改。
     */
    notifications: NotificationCenter = remember { NotificationCenter() },
    modifier: Modifier = Modifier,
) {
    // 堆占用的**读数与「面板是否展开」都提到屏级**，不放在状态栏自己的组合里 ——
    // 详情面板必须画在本屏最外层 Box：若画在状态栏那个只有 20dp 高的 Box 内，
    // 浮出父级边界的部分收不到指针事件，点击会**穿透**到面板下方的表格行上（选中一行）。
    // 提到屏级后面板落在有完整边界的根 Box 里，既能正常接收点击，也能被 UI 测试断言。
    var memory by remember { mutableStateOf<EngineMemory?>(null) }
    var memoryDetailOpen by remember { mutableStateOf(false) }
    // 面板自身的悬停。状态栏的悬停由 EngineMemoryStatusBar 内的 interactionSource 提供，
    // 但它的值只活在那个组件里 —— 关闭判定必须在屏级做（见下面的宽限期逻辑），
    // 故这里另存一份。
    var memoryPanelHovered by remember { mutableStateOf(false) }
    // 状态栏悬停 —— 同样需要在屏级可见
    var memoryBarHovered by remember { mutableStateOf(false) }
    // 状态栏实测高度 —— 详情面板要锚在它正上方，写死一个常数会在字体缩放 / 紧凑档下错位
    val barHeight = remember { mutableIntStateOf(0) }

    // ------------------------------------------------------------------
    // 通知中心：错误对账
    // ------------------------------------------------------------------
    //
    // 为什么不逐点埋点：报错散落在 `ConnectionStatus` / `TablePreviewTab.error` /
    // `SqlSheet.error` / `DatabaseBrowserState.generateError` /
    // `state.errorMessage` 五处，每一处埋一次推送，等于把「什么算一次错误」的判断
    // 散到五个文件里。
    //
    // 改成**屏级对账**：每个错误来源算一个指纹，本帧与上帧比对 ——
    //   - 新出现的指纹 → 推一条
    //   - 这一帧没有的指纹 → 从去重集合移除（**下次再犯会再报**）
    //
    // ⚠️ 去重是必须的：连接失败出现在**每一次重组**里，
    // 没有它就会刷出几十条一模一样的通知，把面板淹掉。
    // 直接用注入进来的那个中心 —— 它同时被各 [DatabaseBrowserState] 拿着推任务通知，
    // 本屏只是把入口与面板画出来。**不能另建一个**，否则任务通知进 A 面板、
    // 报错进 B 面板，用户会以为通知丢了。
    val notificationCenter = notifications
    //
    // ⚠️ [ErrorSource.key] 必须唯一且稳定，且**推送与遗忘两边用同一个 key**。
    // 第一版这里把 pushOnce 的指纹写成 `title|detail`，而 forgetSourcesNotIn 传的是
    // `mapValues { … }.keys`（形如 `sql:连接id:SQL 1`）—— 两者对不上，于是每次
    // 都把所有来源 forget 掉、再当成新的重推一遍。真窗口上表现为「同一个错误连点三次
    // → 徽标 2」，去重形同虚设。
    //
    // 所以把「这一帧有哪些错误」抽成纯函数 [collectErrorSources]：指纹在这里算一次，
    // 两边共用同一个 key。顺带它可单测 —— 而屏里的 derivedStateOf 本身测不到。
    val errorSources by remember { derivedStateOf { collectErrorSources(sheets) } }
    LaunchedEffect(errorSources) {
        val center = notificationCenter
        errorSources.forEach { src ->
            center.pushOnce(src.key, AppNotification.Severity.ERROR, src.title, src.detail)
        }
        // 遗忘**这一帧没有**的来源 —— 否则「失败 → 修好 → 又失败」只会报第一次
        center.forgetSourcesNotIn(errorSources.map { it.key }.toSet())
    }

    if (memoryProbe != null) {
        // 轮询循环的 key 必须是 Unit，不能是 memoryProbe：调用方每次重组传进来的都是**新 lambda
        // 实例**，拿它当 key 会让定时器每次重组都被重启（永远等不到下一次触发）——
        // 与 SettingsScreen 的 rememberUpdatedState 是同一个坑，这里用 rememberUpdatedState 避开。
        val probe by rememberUpdatedState(memoryProbe)
        LaunchedEffect(Unit) {
            while (true) {
                // 串行 await + delay：上一次没回来就不会发下一次，避免引擎被打爆。
                // 失败保留上一帧读数，不清空 —— gRPC 偶发超时不该让数字来回闪。
                runCatching { probe() }.onSuccess { m -> if (m != null) memory = m }
                delay(MEMORY_POLL_MILLIS)
            }
        }
    }

    // 悬停移出自动关闭 —— **必须有宽限期**。指针从状态栏移到面板的途中会短暂地「两边都不在」，
    // 不延时就会在用户还没走到面板时就把面板关了。
    // key 含两个悬停态：任一变 true 都会重启本 effect，从而取消正在跑的计时器。
    val pointerInside = memoryBarHovered || memoryPanelHovered
    LaunchedEffect(memoryDetailOpen, pointerInside) {
        if (!memoryDetailOpen || pointerInside) return@LaunchedEffect
        delay(MEMORY_PANEL_CLOSE_GRACE_MILLIS)
        // 延时结束后再确认一次：期间指针可能已经回来了（此时 effect 已被重启，不会走到这行，
        // 但显式判断让「宽限期」这个意图在代码里自洽，不依赖重启时机）
        if (!memoryBarHovered && !memoryPanelHovered) memoryDetailOpen = false
    }

    // 左侧库/表树宽度 —— **刻意放在这一层**（而不是 `ActiveSheetContent` 里）。
    //
    // 理由：同一时刻只渲染**一个**激活 sheet，宽度若存在 `ActiveSheetContent` 内，
    // 切 sheet 时那棵组合被拆掉重建，`remember` 随之丢失 —— 于是「在 A 连接把树拉宽，
    // 切到 B 连接又缩回 320dp」。布局宽度是**窗口级偏好**，不是每个 sheet 各自的。
    //
    // 不落盘（用户明确选择）：拖好的宽度只活在本次会话，重启回 320dp。
    // 树本身的状态（展开 / 滚动 / 已加载的表）由每 sheet 的 `DatabaseBrowserState` 持有，
    // 与这里的纯布局宽度是两回事，别混。
    var schemaPanelWidth by remember { mutableStateOf(SCHEMA_PANEL_DEFAULT_WIDTH) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 没有 sheet 时不渲染标签条：Material3 的 ScrollableTabRow 不接受 0 个 tab
            // （空列表会在测量时 IndexOutOfBounds）。关闭最后一个 sheet 时，`destination` 由
            // MainScreen 的 LaunchedEffect 在**组合之后**才切回首屏 —— 这中间会有一帧以空列表组合，
            // 因此这里必须走「空态引导」而不是标签条。
            val active = sheets.firstOrNull { it.connection.id == activeSheetId }
            if (active == null) {
                // weight(1f) 而非 fillMaxSize()：底部状态栏要占掉一条，内容区必须让出高度。
                // 无状态栏时（memoryProbe == null）weight 仍分到全部高度，与改造前一致。
                EmptySheetsHint(
                    onAddSheet = onAddSheet,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SheetTabRow(
                    sheets = sheets.map { it.toSummary() },
                    activeSheetId = activeSheetId,
                    onSelect = onSelectSheet,
                    onClose = onCloseSheet,
                    onAdd = onAddSheet,
                    onOpenSettings = onOpenSettings,
                )
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ActiveSheetContent(
                    sheet = active,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    schemaPanelWidth = schemaPanelWidth,
                    onSchemaPanelWidthChange = { schemaPanelWidth = it },
                    modifier = Modifier.weight(1f),
                )
            }

            // 底部状态栏（IDEA 式的 JVM 堆占用）—— 与「有没有 sheet」无关：
            // 空态恰恰是用户第一次打开应用停留的地方，此时恰恰最需要知道内存还剩多少。
            if (memoryProbe != null) {
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EngineMemoryStatusBar(
                    memory = memory,
                    detailOpen = memoryDetailOpen,
                    onToggleDetail = { memoryDetailOpen = !memoryDetailOpen },
                    onMeasuredHeight = { barHeight.intValue = it },
                    onHoverChange = { memoryBarHovered = it },
                    // 通知中心入口放在状态栏**最右** —— 与堆内存同行，右端对齐。
                    // 位置是它该在的地方：常驻、不抢视线，又在「发生了什么」最该被看见的角落。
                    trailing = {
                        NotificationBell(
                            unread = notificationCenter.unreadCount,
                            open = notificationCenter.panelOpen,
                            onToggle = { notificationCenter.panelOpen = !notificationCenter.panelOpen },
                        )
                    },
                )
            }
        }

        if (memoryDetailOpen && memoryProbe != null) {
            val density = LocalDensity.current
            EngineMemoryDetailPanel(
                memory = memory,
                onDismiss = { memoryDetailOpen = false },
                onHoverChange = { memoryPanelHovered = it },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        // 底部留出「状态栏 + 其上方那条分割线」的高度，面板正好贴在它上沿
                        .padding(
                            end = 10.dp,
                            bottom = with(density) { (barHeight.intValue + 1.dp.roundToPx()).toDp() },
                        ),
            )
        }

        // 通知面板：与内存详情面板**同级**、同样锚在状态栏正上方。
        // 打开面板本身就算「已读」—— 用户是来看的，不是来确认红点的。
        if (notificationCenter.panelOpen) {
            LaunchedEffect(Unit) { notificationCenter.markAllRead() }
            val density = LocalDensity.current
            NotificationCenterPanel(
                center = notificationCenter,
                timeFormatter = ::formatNotificationTime,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 10.dp,
                            bottom = with(density) { (barHeight.intValue + 1.dp.roundToPx()).toDp() },
                        ),
            )
        }
    }
}
