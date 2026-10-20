/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 可拖拽的竖向分隔条 —— 用来调整左右两栏的宽度。
 *
 * ## 为什么自己写而不是用现成库
 *
 * 项目里此前没有任何拖拽交互（`DataTable` 的列拖拽还列在架构文档的「未来可能」里），
 * 为一条分隔条引入整个 `reorderable` 依赖不划算。这里只依赖 Compose Foundation 自带的
 * `detectDragGestures` / `detectTapGestures`，因此**仍然留在 `commonMain`**
 * —— 将来 Android / iOS 目标接上时无需平台适配。
 *
 * ## 交互
 * - **拖动**调整宽度，实时跟手（不是松手才跳）
 * - **双击**复位到 [defaultWidth] —— 拖乱了之后最快的回退方式
 * - 宽度被钳在 [minWidth] 与 [maxWidth] 之间：太窄看不到表名，太宽把右栏挤没
 *
 * ## 手感上的三个刻意选择
 *
 * 1. **命中区比视觉宽度宽**（[hitWidth] = 8dp，画线 1dp）。1dp 的线在鼠标下极难点中，
 *    这是所有 IDE 分隔条的通行做法。
 * 2. **拖动时高亮**。让用户知道「抓住了」，否则拖到一半不知道从哪松手。
 * 3. **悬停时换指针**（[hoverIcon]）。见下面「指针为什么由调用方给」。
 *
 * ## 指针为什么由调用方给
 *
 * 分隔条在鼠标下必须**看起来可拖** —— 指针始终是默认的箭头时，用户根本不知道
 * 那里能拖（实测：这条链路做出来之后，鼠标停在分隔条上仍是箭头，
 * 拖拽功能「存在但没人发现」）。
 *
 * 但「左右拉伸箭头」这种图标是**平台专属**的：Compose 的公共 API 只提供
 * 默认 / 十字 / 文本 / 手型四种指针，左右拉伸箭头得由平台侧造
 * （桌面端是 `java.awt.Cursor.E_RESIZE_CURSOR` —— 注意 AWT 里**没有**
 * `EW_RESIZE_CURSOR` 这个常量，东西向双箭头只有 `E_` / `W_` 这两个同形不同热区的名字）。
 * 而本组件住在 `commonMain`，不能引用任何平台类型 —— 所以图标由**调用方**
 * （平台所在的那一层）传进来。
 *
 * ## 宽度该存在哪一层 —— 本组件不持有宽度
 *
 * [width] 由调用方持有（纯受控组件）。存哪一层有个可复用的判据：
 * **布局宽度应提升到「同一时刻只渲染一个实例」的那一层之外。**
 *
 * 浏览屏踩过这个坑：宽度若存在 `ActiveSheetContent` 里，因为同一时刻只渲染**一个**激活
 * sheet，切 sheet 时那棵组合被拆掉重建、`remember` 随之丢失 —— 表现是「在 A 连接把树
 * 拉宽，切到 B 连接又缩回默认」。布局宽度是**窗口级偏好**，不是每个 sheet 各自的。
 * （对照：树本身的展开 / 滚动 / 已加载表仍由每 sheet 的 `DatabaseBrowserState` 持有，
 * 那是真正的 per-sheet 状态，两回事。）
 *
 * @param hoverIcon 悬停时显示的指针图标。`null` = 不改指针（平台没有指针概念的移动端）。
 *   桌面端应传**左右拉伸**箭头。
 * @param width 当前宽度，由调用方持有（这样切视图 / 切 sheet 时能保持）
 * @param onWidthChange 拖动过程中的实时回调
 * @param defaultWidth 双击复位到的宽度
 * @param minWidth 下限
 * @param maxWidth 上限（通常由调用方按容器宽度给，如 `containerWidth * 0.6f`）
 * @param color 分隔线颜色
 * @param highlightColor 拖动时的高亮色
 */
@Composable
fun DragHandle(
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    color: Color,
    highlightColor: Color,
    hitWidth: Dp = 8.dp,
    hoverIcon: PointerIcon? = null,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    // 刻意**不**把 `width` 放进 `pointerInput` 的 key：拖拽过程中 width 每帧都在变，
    // 而 key 一变手势检测器就会被重启，正在进行的拖拽当场断掉。
    // 代价是检测器的 lambda 不随 width 重建，所以这里用 `rememberUpdatedState`
    // 显式保证回调里读到的是**最新**宽度 —— 逐帧累加才对得上。
    val currentWidth by rememberUpdatedState(width)
    val currentMaxWidth by rememberUpdatedState(maxWidth)
    val currentMinWidth by rememberUpdatedState(minWidth)

    // 指针只在「**真的拖得动**」时才是左右拉伸箭头。
    //
    // 为什么要有这个条件：分隔条被钳在 [minWidth] 与 [maxWidth] 之间，两端都可能顶死。
    // 顶死还摆着「可拖」的箭头，就是在**骗**用户说这里能拖 —— 而拖下去纹丝不动。
    // （绝大多数情况下两端不会同时顶死，所以这条几乎总是成立；它挡的是
    //   窗口极窄、`maxWidth` 退化成与 `minWidth` 相等的那种退化布局。）
    val canMove = canPaneWidthMove(width, minWidth, maxWidth)
    val cursorModifier =
        if (hoverIcon != null && canMove) {
            Modifier.pointerHoverIcon(hoverIcon)
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .width(hitWidth)
                .fillMaxHeight()
                .background(if (dragging) highlightColor else Color.Transparent)
                .then(cursorModifier)
                .pointerInput(density) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        // 只消费**位置变化**：不消费的话这个 8dp 宽的 Box 会把拖拽
                        // 事件往父级的滚动容器传，拖分隔条时顺带把列表滚了
                        change.consume()
                        onWidthChange(
                            nextPaneWidth(
                                current = currentWidth,
                                dragDeltaPx = dragAmount.x,
                                density = density,
                                minWidth = currentMinWidth,
                                maxWidth = currentMaxWidth,
                            ),
                        )
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onWidthChange(
                                clampPaneWidth(defaultWidth, currentMinWidth, currentMaxWidth),
                            )
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(if (dragging) highlightColor else color),
        )
    }
}

/**
 * 分隔条此刻**还能不能拖动**。
 *
 * ## 判据
 *
 * 宽度被钳在 `[minWidth, maxWidth]` 之间，所以「还能动」等价于
 * 「至少还有一边能走」：
 *
 * - `width > minWidth` —— 还能往左拖（变窄）
 * - `width < maxWidth` —— 还能往右拖（变宽）
 *
 * ## 为什么值得单独抽出来
 *
 * 指针图标要用它：分隔条被顶死在两端时若还摆着「左右拉伸」箭头，就是在骗用户
 * 说这里能拖，而拖下去纹丝不动。这种「图标在骗人」的缺陷比「图标没有」更难查 ——
 * 用户会以为是拖拽坏了，不会想到是图标没跟着状态走。
 *
 * 抽成纯函数是为了能单测：指针图标本身在 UI 测试里量不出来
 * （`pointerHoverIcon` 的节点是 Compose 的 internal），但「能不能动」这段判据
 * 是这段逻辑里唯一会写错的地方。
 *
 * @param width 当前宽度
 * @param minWidth 下限
 * @param maxWidth 上限
 */
fun canPaneWidthMove(
    width: Dp,
    minWidth: Dp,
    maxWidth: Dp,
): Boolean = width > minWidth || width < maxWidth

/**
 * 把宽度钳进 `[minWidth, maxWidth]`。
 *
 * 拖拽和双击复位两条路径都要钳，抽出来是为了**只写一遍**：
 * 两处各写一次 `coerceIn` 的日子不长，早晚会漏掉一处 —— 而漏掉的后果是
 * 「双击能复位成一个比下限还窄的宽度」，这种 bug 在界面上极难看出根因。
 *
 * 另有一个前提：`maxWidth` 由容器宽度按比例算出，窗口缩窄后它会**小于**调用方记忆中的当前宽度，
 * 于是本函数在那一帧把宽度往回拽 —— 这是要的行为（布局必须服从当前窗口），
 * 但也意味着窗口缩窄后不会保留用户之前拖到的宽度。
 */
fun clampPaneWidth(
    value: Dp,
    minWidth: Dp,
    maxWidth: Dp,
): Dp = value.coerceIn(minWidth, maxWidth)

/**
 * 把分隔条的拖拽增量换算成新的左右栏宽度。
 *
 * 抽成纯函数是为了能单测 —— 拖拽本身要真鼠标事件，测不了；但「增量 → 新宽度 →
 * 钳位」这段算术是最容易出边界 bug 的地方（拖到最左 / 最右、增量比剩余空间大）。
 *
 * @param current 当前宽度
 * @param dragDeltaPx 本次拖动的像素增量（右为正）
 * @param density 像素 ↔ dp 换算
 * @param minWidth 下限
 * @param maxWidth 上限
 */
fun nextPaneWidth(
    current: Dp,
    dragDeltaPx: Float,
    density: Density,
    minWidth: Dp,
    maxWidth: Dp,
): Dp {
    val delta = with(density) { dragDeltaPx.toDp() }
    return clampPaneWidth(current + delta, minWidth, maxWidth)
}
