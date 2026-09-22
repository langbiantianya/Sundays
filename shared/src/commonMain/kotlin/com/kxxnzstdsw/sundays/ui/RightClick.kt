package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastAll

/**
 * Modifier 扩展 —— 在 Composable 上检测右键点击（鼠标右键 / 双指点击）。
 *
 * ## 行为
 * - 仅在主指针通道（[PointerEventPass.Main]）消费右键事件，避免与父级其他手势冲突
 * - 检测到右键按下时立即调用 [onRightClick]，并消费事件防止冒泡
 * - 等到指针释放后才回到监听状态 —— 防止单次右键触发多次
 *
 * ## 用法
 * ```kotlin
 * Row(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .onRightClick { offset -> contextMenuState.show(offset, currentRow) }
 * )
 * ```
 *
 * ## 实现说明
 *
 * Compose Foundation 内部有 `internal suspend fun PointerInputScope.onRightClickDown`，
 * 但因 `internal` 修饰符无法在 shared 模块中使用。本函数复制其公开 API 调用模式：
 * - `event.buttons.isSecondaryPressed` 判断是否按下右键
 * - `event.changes.fastAll { it.changedToDown() }` 判断是否为 down 事件
 *
 * @param onRightClick 回调 — 参数为按下时的相对 Composable 坐标
 */
fun Modifier.onRightClick(
    onRightClick: (Offset) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.buttons.isSecondaryPressed &&
            event.changes.fastAll { it.changedToDown() }
        ) {
            // 消费事件，防止冒泡到父级（避免与外层滚动冲突）
            event.changes.forEach { it.consume() }
            onRightClick(event.changes[0].position)
            // 等到释放后才返回监听（防止单次右键多次触发）
            waitForUpOrCancellation()?.consume()
        }
    }
}