/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 浏览器屏的**顶部/侧边 chrome** —— 内存状态栏与详情面板、通知铃与通知面板、后台任务面板。
// 三者放一起是因为它们共享同一条性质：只读地展示状态，不接受任何领域事件。

package com.langbiantianya.sundays

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.ConnectionState
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 底部状态栏 —— IDEA 式的 JVM 堆占用指示器（一条细进度条 + 「已用 / 上限」）。
 *
 * ## 为什么挂在最外层 Column 而不是 sheet 内容里
 *
 * 堆占用是**进程级**指标，不是某条连接的：同一个引擎进程服务着全部 sheet，指标挂在
 * 任一 sheet 上都是取同一份数据，却会在「关掉最后一个 sheet」时一起消失 —— 而空态恰恰
 * 是用户第一次打开应用停留的地方。因此它渲染在 [DatabaseBrowserScreen] 的最外层，
 * 与 `active` 无关。
 *
 * ## 数据源：引擎 `SYSTEM.INFO`，不是本进程 Runtime
 *
 * 直接读 UI 侧 `Runtime.getRuntime()` 看着更省事，但在 **gRPC 模式**下引擎是**另一个进程**
 * —— 真正持有查询结果集、把堆撑爆的是它，不是渲染界面的这个。此时显示 UI 堆会是一个
 * 「看着很闲、实际快 OOM」的假指标。故复用 `fetchSystemInfo`（`SystemHandler.info()`
 * 返回 `Runtime.totalMemory/freeMemory/maxMemory`），Direct / gRPC 两种模式都指向**引擎**堆。
 *
 * ## 拉取失败保留上一次的值
 *
 * 拉取失败保留上一次的值这件事由调用方（[DatabaseBrowserScreen]）负责 —— 它持有轮询状态，
 * 本组件只做呈现。
 *
 * ## 可点击性
 *
 * 悬停时给出**三重**反馈：指针变手型（`pointerHoverIcon`）、底色转为 `surfaceVariant`、
 * 文字转 `onSurface`。只做其中之一都不够 —— 只换指针在没接鼠标的触控板上等于没有，
 * 只换底色用户仍不确定能不能点。
 *
 * @param memory 最新读数；`null` = 探针一次都没成功过
 * @param detailOpen 详情面板是否展开。展开期间**保持**高亮，否则指针一移开状态栏就恢复原样，
 *   视觉上像是面板已经关了
 * @param onToggleDetail 点击回调（由调用方切换展开态）
 * @param onMeasuredHeight 实测行高回调 —— 详情面板要锚在本行正上方，写死常数会在
 *   字体缩放 / 紧凑档下错位
 * @param onHoverChange 本行是否在指针下。屏级用它和面板的悬停一起判定「指针已移开」
 *   （关闭逻辑必须在屏级，见 [DatabaseBrowserScreen]）
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EngineMemoryStatusBar(
    memory: EngineMemory?,
    detailOpen: Boolean,
    onToggleDetail: () -> Unit,
    onMeasuredHeight: (Int) -> Unit,
    onHoverChange: (Boolean) -> Unit,
    /** 行尾插槽 —— 通知中心入口挂在这里（见 [DatabaseBrowserScreen] 的状态栏注释）。 */
    trailing: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 悬停态同时喂给屏级：关闭判定要的是「状态栏**或**面板都不在指针下」
    LaunchedEffect(hovered) { onHoverChange(hovered) }
    val highlighted = hovered || detailOpen
    val ink =
        if (highlighted) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    // 外层铺满、**内容收缩并右对齐**：底部只占内容那么宽，悬停高亮也只覆盖内容本身。
    // 铺满整行的话，那是一条横贯窗口的色带，视觉上比内存数字本身还重 ——
    // 而且会让「可点区域」大到能在离内容很远的地方误触。
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier =
                Modifier
                    .onSizeChanged { onMeasuredHeight(it.height) }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggleDetail,
                    ).pointerHoverIcon(PointerIcon.Hand)
                    // 4~6dp：再薄就在高 DPI 屏上只剩一条抗锯齿的糊边
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    // 底色放在 padding **之后**：高亮才是一个包住内边距的完整小块，
                    // 而不是只染了文字那一截
                    .background(
                        color =
                            if (highlighted) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        shape = winShape(4.dp),
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = str(StrKey.HEAP_MEMORY),
                style = MaterialTheme.typography.labelSmall,
                color = ink,
            )
            Spacer(Modifier.width(6.dp))
            // 轨道 + 填充两段 Box，不用 Canvas —— 圆角交给 winShape，与全应用的容器写法一致
            Box(
                modifier =
                    Modifier
                        .width(MEMORY_BAR_WIDTH)
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, winShape(2.dp)),
            ) {
                if (memory != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(memory.ratio)
                                .background(memoryFillColor(memory.ratio), winShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text =
                    if (memory == null) {
                        "— / —"
                    } else {
                        "${formatHeapBytes(memory.usedBytes)} / ${formatHeapBytes(memory.maxBytes)}"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = ink,
            )
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * 把「当前所有 sheet 的所有报错」收成一份来源清单。
 *
 * ## 为什么不逐点埋点
 *
 * 报错散落在 `ConnectionStatus` / `TablePreviewTab.error` / `SqlSheet.error` /
 * `DatabaseBrowserState.generateError` / `state.errorMessage` 五处。
 * 每一处埋一次推送，等于把「什么算一次错误」的判断散到五个文件里，
 * 而且新增一处报错来源就必然漏埋一次。
 *
 * ## 抽成纯函数的原因
 *
 * 屏里那个 `derivedStateOf` 测不到 —— 于是第一版把两边的 key 算得不一样
 * （`pushOnce` 用 `title|detail`，`forgetSourcesNotIn` 用 map 的结构 key），
 * **去重完全失效**，真窗口上表现为「同一个错误连点三次 → 徽标 2」。
 * 有了这个函数，同一份 key 既能被屏用，也能被测试直接验。
 */
internal fun collectErrorSources(sheets: List<SheetDescriptor>): List<ErrorSource> =
    buildList {
        // key = **结构标识 + 错误文本的哈希**。
        //
        // ⚠️ 少了后半段就会漏报：同一个 SQL sheet 里 `Table 'a' doesn't exist`
        // 变成 `Table 'b' doesn't exist` 时结构标识没变 → 去重命中 → 用户看到的
        // 还是上一次的错误，而界面上早就换了。
        //
        // 用哈希而不是原文：key 会进内存集合，原文可能有几百字、还带换行。
        // 哈希冲突的风险可以忽略 —— 这里是「同源同文才算同一次」的去重，
        // 不是安全校验；真撞上了表现是少报一次通知，不会误报或报错。
        fun keyOf(
            where: String,
            detail: String,
        ) = "$where#${detail.hashCode()}"

        sheets.forEach { s ->
            val b = s.browser
            if (s.status.state == ConnectionState.FAILED && s.status.message.isNotBlank()) {
                add(
                    ErrorSource(
                        keyOf("conn:" + s.connection.id, s.status.message),
                        Msg.format(StrKey.CONNECT_FAILED_PREFIX, s.connection.name),
                        s.status.message,
                    ),
                )
            }
            b.errorMessage?.takeIf { it.isNotBlank() }?.let {
                add(ErrorSource(keyOf("dbs:" + s.connection.id, it), Msg.text(StrKey.LOAD_DATABASES_FAILED), it))
            }
            b.generateError?.takeIf { it.isNotBlank() }?.let {
                add(ErrorSource(keyOf("gen:" + s.connection.id, it), Msg.text(StrKey.GENERATE_FAILED), it))
            }
            b.tabs.forEach { t ->
                t.error?.takeIf { it.isNotBlank() }?.let {
                    add(
                        ErrorSource(
                            keyOf("tab:" + t.key, it),
                            Msg.format(StrKey.READ_TABLE_FAILED, t.tableName),
                            it,
                        ),
                    )
                }
            }
            b.sqlSheets.forEach { q ->
                q.error?.takeIf { it.isNotBlank() }?.let {
                    add(ErrorSource(keyOf("sql:" + s.connection.id + ":" + q.title, it), Msg.text(StrKey.SQL_EXECUTION_FAILED), it))
                }
            }
        }
    }

/**
 * 堆内存详情面板 —— 设置页「系统信息」内存段的**缩小版**。
 *
 * 只取内存四行（堆已用 / 已分配 / 上限 / 空闲），标签顺序与取值格式都**复用**设置页那套
 * （`formatBytes`）：同一个数字在两处显示成同一个字符串，是「与设置中的一样」这句话的
 * 最低要求。JVM 版本 / OS / PID 那些不在这里重复 —— 那是设置页「系统信息」分类的职责。
 *
 * ## 为什么是「根 Box 里的层内浮层」而不是 `Popup`
 *
 * 试过 desktop 的 [Popup]，两个问题让它出局：
 *
 * 1. desktop 的 `Popup` 会开一个**独立原生窗口** —— 点一下状态栏弹出一个新窗口去看 4 行字，
 *    既不是 IDEA 的观感，也会抢走主窗口焦点。
 * 2. 它的内容在另一个组合根里，**不进 UI 测试的语义树**，`onNodeWithText` 一律找不到。
 *
 * 故改为由 [DatabaseBrowserScreen] 在**最外层 Box** 里直接绘制。代价是要自己管
 * 「面板画在哪」（底部留出状态栏高度，见调用点），换来的是同一棵语义树 + 不弹窗 + 不抢焦点。
 *
 * ## 宽度按内容收缩
 *
 * 不用 `widthIn(min = …)` 也不让任何一行 `fillMaxWidth()`：那会把「堆已用」和它的取值
 * 推到面板两端，中间拉出一条空档，面板看着像一张没排版的表。改成每行自己 `Spacer` 撑开
 * 固定间距、整块由最宽的一行决定 —— 面板宽度从 200dp 收到约 150dp。
 *
 * ## 悬停移出即自动关闭
 *
 * 见 [DatabaseBrowserScreen] 里的宽限期逻辑：面板与状态栏**都**不在指针下时延时关闭，
 * 这样「从状态栏移到面板」的途中不会误关。
 *
 * [memory] 为 `null`（探针一次都没成功）时仍可打开，但逐行显示「—」而不是空白面板 ——
 * 空面板会让用户以为程序坏了。
 */
@Composable
internal fun EngineMemoryDetailPanel(
    memory: EngineMemory?,
    onDismiss: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **离开组合时必须主动回报「未悬停」**。这是上一版埋下的自引入回归：
    // 面板的悬停标志由 reportsHover 的 Enter/Exit 驱动，而指针事件只在节点**存在**时才会发。
    // 用户点面板上的「×」关闭时（以及任何让面板离开组合的路径），`pointerInput` 随节点一起
    // 被销毁，**不会**补发一个 Exit —— 标志就永久停在 true。屏级 `pointerInside` 于是恒真，
    // 宽限期关闭逻辑此后彻底失效（面板再也不会自动关）。
    // 只在 onDismiss 里置位是不够的：断开连接、切换 tab、memoryProbe 变 null 同样会让面板消失。
    // 用 DisposableEffect 兜住「组合销毁」这一统一出口，并经 rememberUpdatedState 取最新回调，
    // 避免每次重组都重启 effect（重启会先跑一次 onDispose，等于凭空制造一次 false 抖动）。
    val latestHoverCallback by rememberUpdatedState(onHoverChange)
    DisposableEffect(Unit) {
        onDispose { latestHoverCallback(false) }
    }

    Surface(
        // testTag：面板本身不带语义，测试量不到它的边界（只能量到里面的文字节点）。
        // 宽度契约要断言的是**面板**而不是某一行文字。
        modifier = modifier.testTag(MEMORY_DETAIL_PANEL_TAG).reportsHover(onHoverChange),
        shape = winShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // 浮层是本文件里**唯一**允许投影的地方：它确实压在内容之上
        // （见 shared/ARCHITECTURE.md §5.4 约束 3）
        shadowElevation = 6.dp,
    ) {
        // IntrinsicSize.Max：面板宽度 = 最宽那一行文字，而不是容器宽度。
        // 不用它的话 Column 会一路撑到父级最大宽度，面板变成一条横贯窗口的色带。
        // 分组线仍是 WinDivider —— 它收到空 modifier 时不填满，在本布局里正好横跨内容宽度。
        Column(
            modifier =
                Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = str(StrKey.JVM_MEMORY), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                // 显式关闭按钮：自动关闭只在「指针移开」时触发，指针停在面板上时
                // 用户仍需要一个明确的关闭手段
                WinIconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = str(StrKey.CLOSE_MEMORY_DETAIL),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                text = str(StrKey.ENGINE_SYSTEM_INFO),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MemoryInfoRow(str(StrKey.HEAP_USED), memory?.let { formatBytes(it.usedBytes) })
            MemoryInfoRow(str(StrKey.HEAP_ALLOCATED), memory?.let { formatBytes(it.totalBytes) })
            MemoryInfoRow(str(StrKey.HEAP_MAX), memory?.let { formatBytes(it.maxBytes) })
            MemoryInfoRow(str(StrKey.HEAP_FREE), memory?.let { formatBytes(it.freeBytes) })
            Spacer(Modifier.height(2.dp))
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = str(StrKey.MEMORY_USAGE, memory?.let { "${(it.ratio * 100).toInt()}%" } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 把本节点的**指针进出**转成布尔回调。
 *
 * 为什么不用现成方案：这版 Compose（1.11.1）**没有** `Modifier.pointerEnter/pointerExit`，
 * `hoverable` 也不在本文件依赖范围内；而 `MutableInteractionSource.collectIsHoveredAsState`
 * 需要节点挂了 `clickable` —— 给一个只读面板挂空 `onClick` 属于骗语义，读屏会念出一个
 * 假的按钮。故直接监听 `PointerEventType.Enter/Exit`。
 *
 * 必须在 `PointerEventPass.Initial` 收：面板压在内容之上，事件先到它，若在 `Main` pass
 * 才处理，可能已经被下层节点消费掉。
 */
private fun Modifier.reportsHover(onHoverChange: (Boolean) -> Unit): Modifier =
    this.pointerInput(onHoverChange) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Enter -> onHoverChange(true)
                    PointerEventType.Exit -> onHoverChange(false)
                    else -> Unit
                }
            }
        }
    }

/**
 * 详情面板里的一行「标签 / 取值」。
 *
 * **不** `fillMaxWidth()` + `SpaceBetween`：那会把取值推到面板右端、与标签之间拉出一条
 * 大空档，面板看起来像一张没排版的表。改为固定 `Spacer` 间距、整行按内容收缩，
 * 面板宽度由最宽的一行决定。
 *
 * 与设置页 `InfoRow` 的差别只有排版方向（那边要顶满整栏便于纵向对齐，这边要收紧），
 * 标签与取值的呈现完全一致。
 */
@Composable
private fun MemoryInfoRow(
    label: String,
    value: String?,
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 进度条填充色 —— 只分两档。
 *
 * 低于 [MEMORY_ALERT_RATIO] 用 `primary`（本应用低饱和靛蓝），到线转 `error`。
 *
 * **为什么不做绿→黄→红三档**：那套配色属于「仪表盘」语言，与 §5.4 定的低饱和 IDE 观感
 * 打架，而且中间那档黄在五套配色里没有稳定、都达标的对应色（各主题 `tertiary` 的实际色相
 * 差异很大）。两档既够用 —— 用户要的是「要不要担心」，不是「精确的连续读数」。
 */
@Composable
private fun memoryFillColor(ratio: Float): Color =
    if (ratio >= MEMORY_ALERT_RATIO) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

/**
 * 堆字节数 → 紧凑可读（`512M` / `2.0G`），**不带小数位与空格**。
 *
 * 与设置页 `SettingsScreen.formatBytes`（`512.0 MB` / `4.00 GB`）刻意不同：那边是给人
 * 逐行对照精确数值的，宽度不敏感；这里只有 5dp 高、且右边还跟着一个「/ 上限」，
 * 必须省掉小数点和单位里的空格才不至于把状态栏撑宽。两处不是重复，是两种呈现密度。
 */
internal fun formatHeapBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> {
            val gb = bytes / (1024.0 * 1024 * 1024)
            // 一位小数，但「2.0G」这种整值就退成「2G」—— 少一个字符是一个
            if (gb % 1.0 == 0.0) "${gb.toInt()}G" else "%.1fG".format(gb)
        }

        bytes >= 1024L * 1024 -> {
            "${bytes / (1024L * 1024)}M"
        }

        bytes >= 1024L -> {
            "${bytes / 1024L}K"
        }

        bytes < 0 -> {
            "—"
        }

        else -> {
            "${bytes}B"
        }
    }

/** 堆占用轮询间隔（毫秒）。2 秒：肉眼能看出趋势，又不至于每秒打一次引擎。 */
internal const val MEMORY_POLL_MILLIS = 2_000L

/** 进度条轨道宽度。IDEA 的指示器就是这个量级 —— 够读出比例，又不抢内容的视线。 */
private val MEMORY_BAR_WIDTH = 140.dp

/**
 * 指针移出（状态栏 + 面板都不在指针下）到自动关闭的**宽限期**。
 *
 * 不是装饰性延时：指针从状态栏移到面板的途中必然有一小段「两边都不在」，不延时就会在
 * 用户还没走到面板时把它关掉。200ms 够跨过这段空隙，又短到「我不想要它了」几乎立即生效。
 */
internal const val MEMORY_PANEL_CLOSE_GRACE_MILLIS = 200L

/** 详情面板的 UI 测试 tag —— 面板本身无语义，测试要量它的边界就得靠它。 */
internal const val MEMORY_DETAIL_PANEL_TAG = "engineMemoryDetailPanel"

/** 占用率到这个值就把进度条染成 `error`。 */
private const val MEMORY_ALERT_RATIO = 0.85f

/*
 * BrowserToolBar —— sheet 标签条之上的工具栏，操作当前激活 sheet 的右栏内容。
 *
 * 两个工作台入口：**「SQL 工作台」** 与 **「造数工作台」**。当前正处在某个工作台时，
 * 该按钮变为「返回表预览」（点它回到表预览）；从另一个工作台切过来时直接进入目标工作台。
 * 状态由 `DatabaseBrowserState.activePane` 持有，每 sheet 独立 —— 切换不清空任何工作台状态。
 * 未连接时禁用（两个工作台的执行都必须依赖已建立的连接池）。
 *
 * 右侧的**「任务」入口**不属于任何 pane —— 导出/导入这类后台任务跨 pane 存在，
 * 「后台运行」的语义恰恰是「我先干别的，回头再看它」，所以这个入口必须在任何 pane 都看得见。
 * 徽标上的数字只数**运行中**的任务：已完成的留在面板里，不该在工具栏上一直占位。
 */

/**
 * 任务面板 —— 后台任务列表。
 *
 * ## 为什么它必须常驻可唤起，而不是「导出完弹个通知」
 *
 * 后台任务的特点是**用户主动离开**它（切 pane、去别的表、干活别的）。
 * 一次性通知只在用户**没走开**的时候有用 —— 而他走开了正是这件事发生的前提。
 * 所以入口得在任何 pane 都在（[BrowserToolBar] 右侧），面板则按需唤起。
 *
 * ## 展示什么
 *
 * 每条任务：**状态图标 + 文件名 + 目录/格式 + 一行状态说明**。
 * 状态说明走 [DatabaseBrowserState.BackgroundTask.statusLine]，
 * 其中运行中未收到首帧时是「正在连接引擎…」而不是「0 行」——
 * 「0 行」会被读成「导出失败」。
 *
 * ⚠️ 面板**不提供取消**。取消要一路传到引擎子进程（`EXPORT.STOP_EXPORT`），
 * 而那条链路是本项目最不可靠的一段（§9.15 的四层收口就是为它做的）。
 * 与其摆一个按了没反应的按钮，不如只给「清除已完成」——
 * 那是**一定做得到**的动作（纯本地状态删除）。
 */
@Composable
internal fun BackgroundTaskPanel(
    tasks: List<DatabaseBrowserState.BackgroundTask>,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().testTag(TASK_PANEL_TAG),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = str(StrKey.BACKGROUND_TASKS),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text =
                        plural(
                            PluralStrKey.BACKGROUND_TASKS_PROGRESS,
                            tasks.count { it.isRunning },
                            tasks.count { it.isRunning },
                            tasks.size,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                // 只给「清除已完成」—— 正在跑的行留着，否则用户一点就把它们从视野里弄没了
                WinOutlinedButton(
                    onClick = onClearFinished,
                    enabled = tasks.any { !it.isRunning },
                    shape = SundaysPalette.buttonShape,
                    modifier = Modifier.testTag(TASK_CLEAR_BTN_TAG),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(str(StrKey.CLEAR_FINISHED))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (tasks.isEmpty()) {
                Text(
                    text = str(StrKey.NO_BACKGROUND_TASKS_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                tasks.forEach { task -> BackgroundTaskRow(task) }
            }
        }
    }
}

/** 任务面板里的一行。 */
@Composable
private fun BackgroundTaskRow(task: DatabaseBrowserState.BackgroundTask) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态图标：运行中用进度圈（它在动，一眼看出「还在跑」）；收口后换成静态图标
        when (task.status) {
            DatabaseBrowserState.BackgroundTask.Status.RUNNING -> {
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }

            DatabaseBrowserState.BackgroundTask.Status.SUCCEEDED -> {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = str(StrKey.DONE),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }

            DatabaseBrowserState.BackgroundTask.Status.FAILED -> {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = str(StrKey.FAILED),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 目录可能很长，副标题单行省略；完整路径在状态行/失败原因里给
                text = task.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.statusLine,
                style = MaterialTheme.typography.bodySmall,
                color =
                    when (task.status) {
                        DatabaseBrowserState.BackgroundTask.Status.RUNNING -> MaterialTheme.colorScheme.primary
                        DatabaseBrowserState.BackgroundTask.Status.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 任务面板本身的 UI 测试 tag —— 面板没有语义，测试要量它得靠它。 */
private const val TASK_PANEL_TAG = "backgroundTaskPanel"

/** 「清除已完成」按钮的 UI 测试 tag。 */
private const val TASK_CLEAR_BTN_TAG = "backgroundTaskClearBtn"

/**
 * 通知时间 → `HH:mm`（当天）或 `MM-dd HH:mm`（更早）。
 *
 * 通知是「最近发生过的事」，跨天的那几条看一眼就知道是昨天的事就够了。
 *
 * ## 为什么它留在 `:desktopApp` 而不是跟着面板上移
 *
 * 「本地时区里的今天几点」是**平台知识**：JVM 有 `java.util.Calendar` 与时区数据库，
 * 而 `commonMain` 里既没有 `Calendar` 也没有时区转换（`kotlin.time.Instant`
 * 只做 epoch 换算）。为此引入一个新依赖（`kotlinx-datetime`）不值得 ——
 * 与 `ClipboardWriter` / `NativePathPicker` 一样，**平台能力留在平台侧**，
 * `:shared` 的通知面板通过参数接收格式化结果。
 */
internal fun formatNotificationTime(ts: Long): String {
    val cal =
        java.util.Calendar
            .getInstance()
            .apply { timeInMillis = ts }

    fun p(v: Int) = "%02d".format(v)
    val hm = "${p(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${p(cal.get(java.util.Calendar.MINUTE))}"
    val today = java.util.Calendar.getInstance()
    val sameDay =
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        hm
    } else {
        "${p(cal.get(java.util.Calendar.MONTH) + 1)}-${p(cal.get(java.util.Calendar.DAY_OF_MONTH))} $hm"
    }
}
