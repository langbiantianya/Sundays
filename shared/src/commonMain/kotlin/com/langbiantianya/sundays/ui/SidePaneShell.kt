/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey

/*
 * **侧边面板的外壳** —— 展开态是「面板 + 可拖宽的分隔条」，折叠态是**贴到窗口边上一条窄轨**。
 *
 * ## 为什么它不叫「树面板外壳」
 *
 * 它最初只为**树**而写（浏览模式的库树、分析模式的工作区树），
 * 后来报表设计也需要**可折叠的侧边面板** —— 而且那一侧贴在窗口的**右边**
 * （报表列表）。于是「树面板外壳」这个名字就**不诚实**了：
 * 同一个组件被用在两件不同的事上，名字却只说了其中一件。
 *
 * 仓库的规矩是「同一个概念只有一份实现」；**名字也是实现的一部分** ——
 * 一个名不副实的名字，会让下一个改动的人以为「树的东西不能乱用」。
 * 所以它搬到了 `ui/` 并改名为 `SidePaneShell`。
 *
 * ## 左右两侧是**镜像**的
 *
 * 贴在窗口左边与贴在窗口右边的面板，展开态与折叠态都是**镜像**的：
 *
 * | | 贴在左边 | 贴在右边 |
 * |---|---|---|
 * | 展开态 | 面板在左、分隔条在右 | 分隔条在左、面板在右 |
 * | 折叠态 | 窄轨在窗口最左 | 窄轨在窗口最右 |
 * | 分隔条往哪拖会变宽 | 往右 | 往左 |
 * | 收起按钮的箭头 | 指向左（往贴边的那一侧收） | 指向右 |
 * | 展开按钮的箭头 | 指向右（离开贴边的那一侧） | 指向左 |
 *
 * 这些差异**全部由 [PaneEdge] 一个枚举驱动** —— 散成几个布尔参数的话，
 * 迟早会漏掉一处（比如箭头没跟着镜像），而那种缺陷在界面上极难看出来。
 */

/**
 * 侧边面板贴在窗口的**哪一边**。
 *
 * 之所以做成枚举而不是两个布尔参数：布尔参数会让调用处出现
 * `isLeft = true, isRight = false` 这种「两个里只能有一个为真」的写法，
 * 而传错组合不会报错、只会画出一个方向不对的箭头。
 */
enum class PaneEdge {
    /** 贴在窗口**左边**（树面板那一侧）。 */
    LEFT,

    /** 贴在窗口**右边**。 */
    RIGHT,
}

/**
 * 折叠后那条窄轨的宽度。
 *
 * 44dp = 一个图标按钮（32dp）+ 两侧各 6dp 的留白 —— 再窄按钮就贴边、点起来发飘；
 * 再宽就白占地方（用户折叠面板就是为了把宽度让给主区）。
 */
val SIDE_PANE_COLLAPSED_WIDTH: Dp = 44.dp

/**
 * 侧边面板宽度的**上限** —— 容器宽度的这个比例。
 *
 * 为什么按比例而不是写死一个 dp：窗口窄的时候若还允许拉到 600dp，
 * 主区就被挤到没法用。0.62 留足了主区的最低可读宽度。
 */
const val SIDE_PANE_MAX_WIDTH_RATIO: Float = 0.62f

/** 折叠态窄轨的 UI 测试 tag —— 「现在是不是折叠着的」得有个明确的落点。 */
const val SIDE_PANE_RAIL_TAG: String = "sidePaneRail"

/**
 * 折叠 / 展开按钮的 UI 测试 tag。
 *
 * 两种状态**共用一个** tag：同一时刻屏上只可能有一个（折叠时按钮在窄条上，
 * 展开时按钮在面板头里），所以测试不必区分「找收起按钮」还是「找展开按钮」。
 *
 * ⚠️ 但**同一个屏上有两个可折叠面板时**（报表设计页：左边的树 + 右边的报表列表），
 * 两者必须各给各的 tag —— 否则「按 tag 找这个按钮」会找到两个。
 */
const val SIDE_PANE_TOGGLE_TAG: String = "sidePaneToggle"

/**
 * 侧边面板外壳 —— 负责「折叠到侧边」与「拖拽调宽」两件事。
 *
 * ## 两种形态
 *
 * ```
 * 贴在左边： ┌──────────────┐┃┌────────────────────┐
 *            │  面板内容     │┃│   右边的主区        │
 *            └──────────────┘┻┴────────────────────┘
 *                         ↑ 分隔条（可拖）
 *
 * 折叠后： ┌──┐┌──────────────────────────────┐
 *          │ ›││   右边的主区（拿回了整条宽度）  │
 *          │ 🗀││                              │
 *          └──┘└──────────────────────────────┘
 * ```
 *
 * ## 折叠时内容**整体从组合里移除**
 *
 * 窄轨只有 44dp —— 面板内容塞进去只能看见 44dp 宽的碎片，既没法看也没法点。
 * 与其画一截残影，不如**整个不画**：折叠态下内容根本不在组合里。
 *
 * 代价只有一个，而且是**已经被解决掉**的那个：容器被拆掉时，
 * 住在容器里的**滚动位置**会跟着没（展开后树回到顶部）。
 * 现在的做法是**把滚动状态提到面板之外**（浏览模式在会话里、分析模式在屏幕层）——
 * 容器拆了，状态还在，展开后还在原来那个位置。
 *
 * ## 展开态与滚动位置**都不归本组件管**
 *
 * 展开态（哪些库 / 哪些表是张开的）住在**会话状态**里
 * （浏览模式是 `DatabaseBrowserState`，分析模式是 `AnalysisSession`）；
 * 滚动位置住在**会话 / 屏幕层**（见上）。两者都在面板**外面**，
 * 所以折叠再展开，面板还是原来那个面板、还停在原来那个位置。
 *
 * ## 宽度**不由本组件持有**
 *
 * 宽度是**窗口级偏好**，不是某个面板的内部状态 —— 判据与 [DragHandle] 的 KDoc 同源：
 * 布局宽度应提升到「同一时刻只渲染一个实例」的那一层之外。
 * 所以这里只做受控组件：宽度由调用方持有，拖拽时回调出去。
 *
 * ## 指针图标**由调用方给**
 *
 * 「左右拉伸」箭头是**平台专属**资源（AWT 里只有 `E_RESIZE_CURSOR` / `W_RESIZE_CURSOR`），
 * 公共 Compose API 造不出来 —— 与仓库既有的分界一致（`ClipboardWriter` / `NativePathPicker`）。
 * `:shared` 只管「什么时候用」，平台侧管「长什么样」。
 *
 * @param collapsed 现在是不是折叠着
 * @param onToggleCollapsed 「折叠 / 展开」被点时回调（窄轨上的展开按钮与面板头里的收起按钮共用它）
 * @param width 当前宽度 —— 由调用方持有
 * @param onWidthChange 拖动过程中的实时回调
 * @param defaultWidth 双击分隔条复位到的宽度
 * @param minWidth 宽度下限
 * @param maxWidth 宽度上限（通常按容器宽度给，见 [SIDE_PANE_MAX_WIDTH_RATIO]）
 * @param collapsedIcon 折叠后窄轨上显示的图标 —— 一眼看出「被收起来的是什么」
 * @param edge 这一块贴在窗口的**哪一边** —— 决定分隔条在哪、箭头朝哪、拖动方向感
 * @param railTag 折叠态窄轨的 UI 测试 tag
 * @param toggleTag 折叠 / 展开按钮的 UI 测试 tag（窄轨上的那个用它）
 * @param modifier 外壳那一行（面板 + 分隔条 / 窄轨）的修饰符
 * @param handleModifier 分隔条的修饰符（测试要靠它定位拖拽落点）
 * @param hoverIcon 分隔条悬停时的指针图标；`null` = 不改指针
 * @param content 面板内容 —— 收到的 modifier 里**已经带上面板应有的宽度**，
 *   调用方把它挂在自己的根节点上即可（测试量的正是那个节点）
 */
@Composable
fun SidePaneShell(
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    collapsedIcon: ImageVector,
    edge: PaneEdge = PaneEdge.LEFT,
    railTag: String = SIDE_PANE_RAIL_TAG,
    toggleTag: String = SIDE_PANE_TOGGLE_TAG,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
    hoverIcon: PointerIcon? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        if (collapsed) {
            CollapsedSidePaneRail(
                icon = collapsedIcon,
                onExpand = onToggleCollapsed,
                edge = edge,
                railTag = railTag,
                toggleTag = toggleTag,
            )
        } else {
            // 宽度**交给调用方挂** —— 因为测试要量的正是「面板本身」那个节点，
            // 宽度写在别处（比如外面套一个 Box）的话，量到的就不是面板了。
            val panel = Modifier.width(width).fillMaxHeight()
            if (edge == PaneEdge.LEFT) {
                content(panel)
                SidePaneDragHandle(
                    width = width,
                    onWidthChange = onWidthChange,
                    defaultWidth = defaultWidth,
                    minWidth = minWidth,
                    maxWidth = maxWidth,
                    edge = edge,
                    hoverIcon = hoverIcon,
                    modifier = handleModifier,
                )
            } else {
                SidePaneDragHandle(
                    width = width,
                    onWidthChange = onWidthChange,
                    defaultWidth = defaultWidth,
                    minWidth = minWidth,
                    maxWidth = maxWidth,
                    edge = edge,
                    hoverIcon = hoverIcon,
                    modifier = handleModifier,
                )
                content(panel)
            }
        }
    }
}

/**
 * 侧边面板的**分隔条** —— 与浏览模式、分析工作台共用同一份 [DragHandle]。
 *
 * 贴在窗口右边时，受控的那一块在分隔条**右边**，所以拖动方向是**镜像**的
 * （往左拖才变宽）。
 */
@Composable
private fun SidePaneDragHandle(
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    edge: PaneEdge,
    hoverIcon: PointerIcon?,
    modifier: Modifier,
) {
    DragHandle(
        width = width,
        onWidthChange = onWidthChange,
        defaultWidth = defaultWidth,
        minWidth = minWidth,
        maxWidth = maxWidth,
        color = MaterialTheme.colorScheme.outlineVariant,
        highlightColor = MaterialTheme.colorScheme.primary,
        handleSide = if (edge == PaneEdge.LEFT) HandleSide.LEFT else HandleSide.RIGHT,
        hoverIcon = hoverIcon,
        modifier = modifier,
    )
}

/**
 * 折叠态的窄轨 —— 一个展开按钮 + 一枚「被收起来的是什么」的图标。
 *
 * ## 为什么窄轨上要留一枚图标
 *
 * 收起来之后屏幕上就只剩这一条 44dp 的窄轨。如果它只有一个孤零零的箭头，
 * 用户过一会儿回来看屏幕，会忘了「那个箭头是树」—— 于是不敢点。
 * 留一枚图标（树是数据库、报表列表是文档）就认得出了。
 *
 * ## 贴边那一侧的那条竖线
 *
 * 展开态的分隔条本身就是一条竖线，折叠后它没了 —— 窄轨若也不画线，
 * 主区就会「贴」上来，看着像窄轨的一部分。补一条线，边界才清楚。
 */
@Composable
private fun CollapsedSidePaneRail(
    icon: ImageVector,
    onExpand: () -> Unit,
    edge: PaneEdge,
    railTag: String,
    toggleTag: String,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .width(SIDE_PANE_COLLAPSED_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    // 线画在**靠主区的那一侧** —— 窄轨贴左边时是右边，贴右边时是左边。
                    val x = if (edge == PaneEdge.LEFT) size.width - 0.5f else 0.5f
                    drawLine(
                        color = outline,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                }.testTag(railTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidePaneToggleButton(
            collapsed = true,
            onClick = onExpand,
            edge = edge,
            tag = toggleTag,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 折叠 / 展开按钮该画哪个箭头。
 *
 * ## 规则
 *
 * - **展开态**（按钮的动作是「收起」）：箭头指向**贴边的那一侧** —— 面板往那边收。
 * - **折叠态**（按钮的动作是「展开」）：箭头指向**离开贴边的那一侧** —— 面板往回开。
 *
 * 贴左边时：收起画左箭头、展开画右箭头。
 * 贴右边时：**镜像** —— 收起画右箭头、展开画左箭头。
 *
 * ## 为什么抽成**纯函数**
 *
 * 箭头朝向是**镜像**规则 —— 少改一处，界面上就是「点下去却往反方向收」。
 * 而箭头本身在 UI 测试里**量不出来**（图标没有可断言的身份），
 * 所以把「哪一侧 + 哪个状态 → 哪个箭头」变成一个能单测的纯函数，
 * 这条镜像规则才有回归保护。
 *
 * @param collapsed 现在是不是折叠着
 * @param edge 这一块贴在窗口的**哪一边**
 */
fun sidePaneToggleIcon(
    collapsed: Boolean,
    edge: PaneEdge,
): ImageVector =
    when {
        edge == PaneEdge.LEFT && !collapsed -> Icons.Filled.ChevronLeft
        edge == PaneEdge.LEFT -> Icons.Filled.ChevronRight
        !collapsed -> Icons.Filled.ChevronRight
        else -> Icons.Filled.ChevronLeft
    }

/**
 * 折叠 / 展开按钮。
 *
 * 展开态放在**面板头**里（收起），折叠态放在**窄轨**上（展开）——
 * 同一个按钮、同一个回调，只是位置与朝向不同。
 *
 * ## 箭头的朝向必须跟着**贴边的那一侧**走
 *
 * 展开态的按钮是「收起」，箭头指向**贴边的那一侧**（面板往那边收）；
 * 折叠态的按钮是「展开」，箭头指向**离开贴边的那一侧**（面板往回开）。
 *
 * 贴左边时：收起画左箭头、展开画右箭头（与从前一致）。
 * 贴右边时：收起画右箭头、展开画左箭头 —— **镜像**。
 *
 * 反过来画的话，用户点下去之前猜不出会发生什么。
 *
 * @param collapsed 现在是不是折叠着（决定箭头朝向与无障碍描述）
 * @param onClick 被点时回调
 * @param edge 这一块贴在窗口的**哪一边**
 * @param collapseLabel 收起时的无障碍描述
 * @param expandLabel 展开时的无障碍描述
 * @param tag UI 测试 tag
 * @param modifier 修饰符
 */
@Composable
fun SidePaneToggleButton(
    collapsed: Boolean,
    onClick: () -> Unit,
    edge: PaneEdge = PaneEdge.LEFT,
    collapseLabel: String = str(StrKey.TREE_PANE_COLLAPSE),
    expandLabel: String = str(StrKey.TREE_PANE_EXPAND),
    tag: String = SIDE_PANE_TOGGLE_TAG,
    modifier: Modifier = Modifier,
) {
    WinIconButton(
        onClick = onClick,
        modifier = modifier.testTag(tag),
    ) {
        Icon(
            imageVector = sidePaneToggleIcon(collapsed = collapsed, edge = edge),
            contentDescription = if (collapsed) expandLabel else collapseLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
