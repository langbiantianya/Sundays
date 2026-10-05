package com.kxxnzstdsw.sundays.ui

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
 * ## 手感上的两个刻意选择
 *
 * 1. **命中区比视觉宽度宽**（[hitWidth] = 8dp，画线 1dp）。1dp 的线在鼠标下极难点中，
 *    这是所有 IDE 分隔条的通行做法。
 * 2. **拖动时高亮**。让用户知道「抓住了」，否则拖到一半不知道从哪松手。
 *
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

    Box(
        modifier = modifier
            .width(hitWidth)
            .fillMaxHeight()
            .background(if (dragging) highlightColor else Color.Transparent)
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
            }
            .pointerInput(Unit) {
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
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(if (dragging) highlightColor else color),
        )
    }
}

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
fun clampPaneWidth(value: Dp, minWidth: Dp, maxWidth: Dp): Dp = value.coerceIn(minWidth, maxWidth)

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
