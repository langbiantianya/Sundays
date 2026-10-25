/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

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
import com.langbiantianya.sundays.ui.DragHandle
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.str

/*
 * 树面板的**外壳** —— 展开态是「面板 + 可拖宽的分隔条」，折叠态是**贴到屏幕左边的一条窄轨**。
 *
 * ## 为什么两棵树要用同一个外壳
 *
 * 应用里有两棵数据库树：浏览模式（数据管理）与分析模式。它们此前在**布局**上也是各做各的：
 * 浏览模式的树面板能拖宽（`DragHandle` + `ActiveSheetContent` 里那份宽度状态），
 * 分析模式的树面板却是写死的 `TREE_PANEL_WIDTH = 240.dp` —— 想看长表名只能干瞪眼。
 *
 * 同一个概念有两份实现，改一处就忘了另一处 —— 这正是本组件存在的理由：
 * **折叠到侧边**与**拖拽调宽**这两件事，两个模式现在走同一段代码、同一套手感。
 */

/**
 * 折叠后那条窄轨的宽度。
 *
 * 44dp = 一个图标按钮（32dp）+ 两侧各 6dp 的留白 —— 再窄按钮就贴边、点起来发飘；
 * 再宽就白占地方（用户折叠树就是为了把宽度让给右边）。
 */
val TREE_PANE_COLLAPSED_WIDTH: Dp = 44.dp

/**
 * 树面板宽度的**上限** —— 容器宽度的这个比例。
 *
 * 为什么按比例而不是写死一个 dp：窗口窄的时候若还允许拉到 600dp，
 * 右侧的工作台就被挤到没法用。0.62 留足了右栏的最低可读宽度。
 */
const val TREE_PANE_MAX_WIDTH_RATIO: Float = 0.62f

/** 折叠态窄轨的 UI 测试 tag —— 「现在是不是折叠着的」得有个明确的落点。 */
const val TREE_PANE_RAIL_TAG: String = "treePaneRail"

/**
 * 折叠 / 展开按钮的 UI 测试 tag。
 *
 * 两种状态**共用一个** tag：同一时刻屏上只可能有一个（折叠时按钮在窄条上，
 * 展开时按钮在面板头里），所以测试不必区分「找收起按钮」还是「找展开按钮」。
 */
const val TREE_PANE_TOGGLE_TAG: String = "treePaneToggle"

/**
 * 树面板外壳 —— 负责「折叠到侧边」与「拖拽调宽」两件事。
 *
 * ## 两种形态
 *
 * ```
 * 展开： ┌──────────────┐┃┌────────────────────┐
 *        │  面板内容     │┃│   右边的主区        │
 *        └──────────────┘┻┴────────────────────┘
 *                     ↑ 分隔条（可拖）
 *
 * 折叠： ┌──┐┌──────────────────────────────┐
 *        │ ›││   右边的主区（拿回了整条宽度）  │
 *        │ 🗀││                              │
 *        └──┘└──────────────────────────────┘
 * ```
 *
 * ## 折叠时内容**整体从组合里移除**
 *
 * 窄轨只有 44dp —— 树塞进去只能看见 44dp 宽的碎片，既没法看也没法点。
 * 与其画一截残影，不如**整个不画**：折叠态下树的内容根本不在组合里。
 *
 * 代价只有一个，而且是**已经被解决掉**的那个：容器被拆掉时，
 * 住在容器里的**滚动位置**会跟着没（展开后树回到顶部）。
 * 现在的做法是**把滚动状态提到面板之外**（浏览模式在会话里、分析模式在屏幕层）——
 * 容器拆了，状态还在，展开后树还在原来那个位置。
 *
 * ## 展开态与滚动位置**都不归本组件管**
 *
 * 展开态（哪些库 / 哪些表是张开的）住在**会话状态**里
 * （浏览模式是 `DatabaseBrowserState`，分析模式是 `AnalysisSession`）；
 * 滚动位置住在**会话 / 屏幕层**（见上）。两者都在面板**外面**，
 * 所以折叠再展开，这棵树还是原来那棵树、还停在原来那个位置。
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
 * @param maxWidth 宽度上限（通常按容器宽度给，见 [TREE_PANE_MAX_WIDTH_RATIO]）
 * @param collapsedIcon 折叠后窄轨上显示的图标 —— 一眼看出「被收起来的是什么」
 * @param modifier 外壳那一行（面板 + 分隔条 / 窄轨）的修饰符
 * @param handleModifier 分隔条的修饰符（测试要靠它定位拖拽落点）
 * @param hoverIcon 分隔条悬停时的指针图标；`null` = 不改指针
 * @param content 面板内容 —— 收到的 modifier 里**已经带上面板应有的宽度**，
 *   调用方把它挂在自己的根节点上即可（测试量的正是那个节点）
 */
@Composable
fun TreePaneShell(
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    collapsedIcon: ImageVector,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
    hoverIcon: PointerIcon? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        if (collapsed) {
            CollapsedTreePaneRail(icon = collapsedIcon, onExpand = onToggleCollapsed)
        } else {
            // 宽度**交给调用方挂** —— 因为测试要量的正是「面板本身」那个节点，
            // 宽度写在别处（比如外面套一个 Box）的话，量到的就不是面板了。
            content(Modifier.width(width).fillMaxHeight())
            DragHandle(
                width = width,
                onWidthChange = onWidthChange,
                defaultWidth = defaultWidth,
                minWidth = minWidth,
                maxWidth = maxWidth,
                color = MaterialTheme.colorScheme.outlineVariant,
                highlightColor = MaterialTheme.colorScheme.primary,
                hoverIcon = hoverIcon,
                modifier = handleModifier,
            )
        }
    }
}

/**
 * 折叠态的窄轨 —— 一个展开按钮 + 一枚「被收起来的是什么」的图标。
 *
 * ## 为什么窄轨上要留一枚图标
 *
 * 收起来之后屏幕上就只剩这一条 44dp 的窄轨。如果它只有一个孤零零的箭头，
 * 用户过一会儿回来看屏幕，会忘了「左边那个箭头是树」—— 于是不敢点。
 * 留一枚图标（浏览模式是文件夹、分析模式是数据库）就认得出了。
 *
 * ## 右边那条竖线
 *
 * 展开态的分隔条本身就是一条竖线，折叠后它没了 —— 窄轨若也不画线，
 * 右边的主区就会「贴」上来，看着像窄轨的一部分。补一条线，边界才清楚。
 */
@Composable
private fun CollapsedTreePaneRail(
    icon: ImageVector,
    onExpand: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .width(TREE_PANE_COLLAPSED_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    drawLine(
                        color = outline,
                        start = Offset(size.width - 0.5f, 0f),
                        end = Offset(size.width - 0.5f, size.height),
                        strokeWidth = 1f,
                    )
                }.testTag(TREE_PANE_RAIL_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TreePaneToggleButton(collapsed = true, onClick = onExpand)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 折叠 / 展开按钮。
 *
 * 展开态放在**面板头**里（收起），折叠态放在**窄轨**上（展开）——
 * 同一个按钮、同一个回调，只是位置与朝向不同。
 *
 * 朝向必须跟着状态走：展开态是「往左收」所以画左箭头，折叠态是「往右开」所以画右箭头。
 * 反过来画的话，用户点下去之前猜不出会发生什么。
 *
 * @param collapsed 现在是不是折叠着（决定箭头朝向与无障碍描述）
 * @param onClick 被点时回调
 * @param modifier 修饰符
 */
@Composable
fun TreePaneToggleButton(
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WinIconButton(
        onClick = onClick,
        modifier = modifier.testTag(TREE_PANE_TOGGLE_TAG),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
            contentDescription = str(if (collapsed) StrKey.TREE_PANE_EXPAND else StrKey.TREE_PANE_COLLAPSE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
