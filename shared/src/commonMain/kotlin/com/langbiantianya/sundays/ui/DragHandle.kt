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
import androidx.compose.foundation.layout.height
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

/*
 * 可拖拽的分隔条 —— 两种朝向：**竖向**调左右两栏的宽度，**横向**调上下两块的高度。
 *
 * ## 为什么自己写而不是用现成库
 *
 * 项目里此前没有任何拖拽交互（`DataTable` 的列拖拽还列在架构文档的「未来可能」里），
 * 为一条分隔条引入整个 `reorderable` 依赖不划算。这里只依赖 Compose Foundation 自带的
 * `detectDragGestures` / `detectTapGestures`，因此**仍然留在 `commonMain`**
 * —— 将来 Android / iOS 目标接上时无需平台适配。
 *
 * ## 为什么是「一个核心 + 两种朝向」而不是两个组件
 *
 * 竖向分隔条（左右两栏）与横向分隔条（上下两块）是**同一个概念**：一条能拖、能双击复位、
 * 拖动时高亮、悬停时换指针的缝。它们的手感必须一致 ——
 * 两份实现意味着两处都要记得改，而「只改了一边」是这类组件最常见的退化方式
 * （一边有指针图标、一边没有；一边双击能复位、一边不能）。
 *
 * 所以手势与画线只有一份（[DragHandleCore]），朝向只决定三件事：
 * **拖哪个轴**、**命中区是宽是高**、**画竖线还是横线**。
 *
 * ## 交互
 * - **拖动**调整尺寸，实时跟手（不是松手才跳）
 * - **双击**复位到默认值 —— 拖乱了之后最快的回退方式
 * - 尺寸被钳在 [minWidth] / [maxWidth]（或高度的 [minHeight] / [maxHeight]）之间：
 *   太窄看不到表名，太宽把右栏挤没
 *
 * ## 手感上的三个刻意选择
 *
 * 1. **命中区比视觉宽度宽**（默认 8dp，画线 1dp）。1dp 的线在鼠标下极难点中，
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
 * ## 该调的那一栏在分隔条的**哪一侧** —— 符号约定
 *
 * 受控的尺寸永远是「分隔条**之前**那一块」的尺寸：
 *
 * - 竖向：树在左、分隔条在右 → 往右拖（`+x`）树变宽
 * - 横向：编辑器在上、分隔条在下 → 往下拖（`+y`）编辑器变高
 *
 * 两条路径的**符号方向一致**，所以 [nextPaneWidth] 与 [nextPaneHeight]
 * 可以共用同一份算术（[nextPaneSize]）—— 差别只在传进去的是 `dragAmount.x` 还是 `dragAmount.y`。
 *
 * ## 尺寸该存在哪一层 —— 本组件不持有尺寸
 *
 * [width] / [height] 由调用方持有（纯受控组件）。存哪一层有个可复用的判据：
 * **布局尺寸应提升到「同一时刻只渲染一个实例」的那一层之外。**
 *
 * 浏览屏踩过这个坑：宽度若存在 `ActiveSheetContent` 里，因为同一时刻只渲染**一个**激活
 * sheet，切 sheet 时那棵组合被拆掉重建、`remember` 随之丢失 —— 表现是「在 A 连接把树
 * 拉宽，切到 B 连接又缩回默认」。布局宽度是**窗口级偏好**，不是每个 sheet 各自的。
 * （对照：树本身的展开 / 滚动 / 已加载表仍由每 sheet 的 `DatabaseBrowserState` 持有，
 * 那是真正的 per-sheet 状态，两回事。）
 */

/**
 * 分隔条的朝向 —— 决定**拖哪个轴**、**命中区是宽是高**、**画竖线还是横线**。
 *
 * 之所以做成枚举而不是两个布尔参数：布尔参数会让调用处出现
 * `isVertical = true, isHorizontal = false` 这种「两个里只能有一个为真」的写法，
 * 而传错组合不会报错、只会画出一条不对的线。
 */
private enum class HandleAxis {
    /** 竖向：分隔**左右**两栏，拖动改的是宽度。 */
    VERTICAL,

    /** 横向：分隔**上下**两块，拖动改的是高度。 */
    HORIZONTAL,
}

/**
 * 可拖拽的竖向分隔条 —— 用来调整左右两栏的宽度。
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
 * @param hitWidth 命中区宽度（画线仍只有 1dp）
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
    DragHandleCore(
        axis = HandleAxis.VERTICAL,
        value = width,
        onValueChange = onWidthChange,
        defaultValue = defaultWidth,
        minValue = minWidth,
        maxValue = maxWidth,
        hitSize = hitWidth,
        color = color,
        highlightColor = highlightColor,
        modifier = modifier,
        hoverIcon = hoverIcon,
    )
}

/**
 * 可拖拽的**横向**分隔条 —— 用来调整上下两块的高度。
 *
 * ## 为什么需要它
 *
 * SQL 编辑器此前的高度是**写死的比例**（浏览模式 60% / 40%，分析模式 42% : 1）：
 * 写一段长 SQL 时编辑器太矮，而结果只有两行时结果区又占了七成屏 ——
 * 两种诉求没法同时满足，而比例是**编译期定死**的，用户没有任何办法调。
 *
 * 造数工作台（编辑器 45% / 输出 55%）后来也接上了同一条缝 ——
 * **三处共用一份实现**：同一个概念（上下两块之间那条能拖的缝）不能各做各的，
 * 否则「一边双击能复位、一边不能」这类退化迟早发生。
 *
 * ## 与竖向分隔条的**符号方向一致**
 *
 * 受控的是「分隔条**上面**那一块」的高度，所以**往下拖**（`+y`）编辑器变高 ——
 * 这正是「把缝往下挪，下面那块就变矮、上面那块就变高」的直觉。
 *
 * @param hoverIcon 悬停时显示的指针图标。`null` = 不改指针。桌面端应传**上下拉伸**箭头。
 * @param height 当前高度，由调用方持有
 * @param onHeightChange 拖动过程中的实时回调
 * @param defaultHeight 双击复位到的高度
 * @param minHeight 下限（再矮就看不见工具栏 / 若干行代码了）
 * @param maxHeight 上限（通常由调用方按容器高度减去「另一块的下限」算出）
 * @param color 分隔线颜色
 * @param highlightColor 拖动时的高亮色
 * @param hitHeight 命中区高度（画线仍只有 1dp）
 */
@Composable
fun HorizontalDragHandle(
    height: Dp,
    onHeightChange: (Dp) -> Unit,
    defaultHeight: Dp,
    minHeight: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    color: Color,
    highlightColor: Color,
    hitHeight: Dp = 8.dp,
    hoverIcon: PointerIcon? = null,
) {
    DragHandleCore(
        axis = HandleAxis.HORIZONTAL,
        value = height,
        onValueChange = onHeightChange,
        defaultValue = defaultHeight,
        minValue = minHeight,
        maxValue = maxHeight,
        hitSize = hitHeight,
        color = color,
        highlightColor = highlightColor,
        modifier = modifier,
        hoverIcon = hoverIcon,
    )
}

/**
 * 分隔条的**共同实现** —— 手势、画线、高亮、指针，两种朝向共用这一份。
 *
 * 朝向只影响三处：拖拽取 `x` 还是 `y` 的增量、命中区是「宽」还是「高」、画竖线还是横线。
 */
@Composable
private fun DragHandleCore(
    axis: HandleAxis,
    value: Dp,
    onValueChange: (Dp) -> Unit,
    defaultValue: Dp,
    minValue: Dp,
    maxValue: Dp,
    hitSize: Dp,
    color: Color,
    highlightColor: Color,
    modifier: Modifier,
    hoverIcon: PointerIcon?,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    // 刻意**不**把 `value` 放进 `pointerInput` 的 key：拖拽过程中 value 每帧都在变，
    // 而 key 一变手势检测器就会被重启，正在进行的拖拽当场断掉。
    // 代价是检测器的 lambda 不随 value 重建，所以这里用 `rememberUpdatedState`
    // 显式保证回调里读到的是**最新**值 —— 逐帧累加才对得上。
    val currentValue by rememberUpdatedState(value)
    val currentMaxValue by rememberUpdatedState(maxValue)
    val currentMinValue by rememberUpdatedState(minValue)

    // 指针只在「**真的拖得动**」时才是拉伸箭头。
    //
    // 为什么要有这个条件：分隔条被钳在 [minValue] 与 [maxValue] 之间，两端都可能顶死。
    // 顶死还摆着「可拖」的箭头，就是在**骗**用户说这里能拖 —— 而拖下去纹丝不动。
    // （绝大多数情况下两端不会同时顶死，所以这条几乎总是成立；它挡的是
    //   窗口极窄、`maxValue` 退化成与 `minValue` 相等的那种退化布局。）
    val canMove = canPaneSizeMove(value, minValue, maxValue)
    val cursorModifier =
        if (hoverIcon != null && canMove) {
            Modifier.pointerHoverIcon(hoverIcon)
        } else {
            Modifier
        }

    // 命中区与视觉宽度是**两件事**：命中区 8dp（画线 1dp）。
    val hitModifier =
        when (axis) {
            HandleAxis.VERTICAL -> Modifier.width(hitSize).fillMaxHeight()
            HandleAxis.HORIZONTAL -> Modifier.height(hitSize).fillMaxWidth()
        }
    // 画出来的那条线：竖向是一条竖线，横向是一条横线。
    val lineModifier =
        when (axis) {
            HandleAxis.VERTICAL -> Modifier.fillMaxHeight().width(1.dp)
            HandleAxis.HORIZONTAL -> Modifier.fillMaxWidth().height(1.dp)
        }

    Box(
        modifier =
            modifier
                .then(hitModifier)
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
                        val delta =
                            if (axis == HandleAxis.VERTICAL) {
                                dragAmount.x
                            } else {
                                dragAmount.y
                            }
                        onValueChange(
                            nextPaneSize(
                                current = currentValue,
                                dragDeltaPx = delta,
                                density = density,
                                min = currentMinValue,
                                max = currentMaxValue,
                            ),
                        )
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onValueChange(
                                clampPaneSize(defaultValue, currentMinValue, currentMaxValue),
                            )
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                lineModifier
                    .background(if (dragging) highlightColor else color),
        )
    }
}

/**
 * 分隔条此刻**还能不能拖动**。
 *
 * ## 判据
 *
 * 尺寸被钳在 `[min, max]` 之间，所以「还能动」等价于
 * 「至少还有一边能走」：
 *
 * - `value > min` —— 还能往小的那边拖（变小）
 * - `value < max` —— 还能往大的那边拖（变大）
 *
 * ## 为什么值得单独抽出来
 *
 * 指针图标要用它：分隔条被顶死在两端时若还摆着「拉伸」箭头，就是在骗用户
 * 说这里能拖，而拖下去纹丝不动。这种「图标在骗人」的缺陷比「图标没有」更难查 ——
 * 用户会以为是拖拽坏了，不会想到是图标没跟着状态走。
 *
 * 抽成纯函数是为了能单测：指针图标本身在 UI 测试里量不出来
 * （`pointerHoverIcon` 的节点是 Compose 的 internal），但「能不能动」这段判据
 * 是这段逻辑里唯一会写错的地方。
 *
 * @param value 当前尺寸
 * @param min 下限
 * @param max 上限
 */
fun canPaneSizeMove(
    value: Dp,
    min: Dp,
    max: Dp,
): Boolean {
    val low = if (min <= max) min else max
    val high = if (min <= max) max else min
    return value > low || value < high
}

/**
 * 分隔条此刻**还能不能拖动**（宽度版）。
 *
 * @param width 当前宽度
 * @param minWidth 下限
 * @param maxWidth 上限
 */
fun canPaneWidthMove(
    width: Dp,
    minWidth: Dp,
    maxWidth: Dp,
): Boolean = canPaneSizeMove(width, minWidth, maxWidth)

/**
 * 分隔条此刻**还能不能拖动**（高度版）。
 *
 * @param height 当前高度
 * @param minHeight 下限
 * @param maxHeight 上限
 */
fun canPaneHeightMove(
    height: Dp,
    minHeight: Dp,
    maxHeight: Dp,
): Boolean = canPaneSizeMove(height, minHeight, maxHeight)

/**
 * 把尺寸钳进 `[min, max]`。
 *
 * 拖拽和双击复位两条路径都要钳，抽出来是为了**只写一遍**：
 * 两处各写一次 `coerceIn` 的日子不长，早晚会漏掉一处 —— 而漏掉的后果是
 * 「双击能复位成一个比下限还窄的尺寸」，这种 bug 在界面上极难看出根因。
 *
 * 另有一个前提：`max` 由容器尺寸按比例算出，窗口缩窄后它会**小于**调用方记忆中的当前值，
 * 于是本函数在那一帧把尺寸往回拽 —— 这是要的行为（布局必须服从当前窗口），
 * 但也意味着窗口缩窄后不会保留用户之前拖到的尺寸。
 *
 * ## 为什么上下限要**摆正**
 *
 * `max` 是按容器算出来的，容器极窄时它会小于写死的 `min` —— 而
 * `coerceIn` 遇到 `min > max` 会直接抛 `IllegalArgumentException`。
 * 「窗口太窄」不该让整个应用崩掉，所以这里先把两个端点摆正再钳。
 */
fun clampPaneSize(
    value: Dp,
    min: Dp,
    max: Dp,
): Dp {
    val low = if (min <= max) min else max
    val high = if (min <= max) max else min
    return value.coerceIn(low, high)
}

/**
 * 把尺寸钳进 `[minWidth, maxWidth]`。
 *
 * @param value 待钳的值
 * @param minWidth 下限
 * @param maxWidth 上限
 */
fun clampPaneWidth(
    value: Dp,
    minWidth: Dp,
    maxWidth: Dp,
): Dp = clampPaneSize(value, minWidth, maxWidth)

/**
 * 把高度钳进 `[minHeight, maxHeight]`。
 *
 * @param value 待钳的值
 * @param minHeight 下限
 * @param maxHeight 上限
 */
fun clampPaneHeight(
    value: Dp,
    minHeight: Dp,
    maxHeight: Dp,
): Dp = clampPaneSize(value, minHeight, maxHeight)

/**
 * 把分隔条的拖拽增量换算成新的尺寸。
 *
 * 抽成纯函数是为了能单测 —— 拖拽本身要真鼠标事件，测不了；但「增量 → 新尺寸 →
 * 钳位」这段算术是最容易出边界 bug 的地方（拖到最上 / 最下、增量比剩余空间大）。
 *
 * @param current 当前尺寸
 * @param dragDeltaPx 本次拖动的像素增量（正方向 = 让这一块**变大**）
 * @param density 像素 ↔ dp 换算
 * @param min 下限
 * @param max 上限
 */
fun nextPaneSize(
    current: Dp,
    dragDeltaPx: Float,
    density: Density,
    min: Dp,
    max: Dp,
): Dp {
    val delta = with(density) { dragDeltaPx.toDp() }
    return clampPaneSize(current + delta, min, max)
}

/**
 * 把分隔条的拖拽增量换算成新的左右栏宽度。
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
): Dp = nextPaneSize(current, dragDeltaPx, density, minWidth, maxWidth)

/**
 * 把分隔条的拖拽增量换算成新的高度。
 *
 * 符号约定与 [nextPaneWidth] 一致：**正增量 = 受控的那一块变大**。
 * 横向分隔条受控的是**上面**那一块，所以往下拖（`+y`）它变高。
 *
 * @param current 当前高度
 * @param dragDeltaPx 本次拖动的像素增量（下为正）
 * @param density 像素 ↔ dp 换算
 * @param minHeight 下限
 * @param maxHeight 上限
 */
fun nextPaneHeight(
    current: Dp,
    dragDeltaPx: Float,
    density: Density,
    minHeight: Dp,
    maxHeight: Dp,
): Dp = nextPaneSize(current, dragDeltaPx, density, minHeight, maxHeight)
