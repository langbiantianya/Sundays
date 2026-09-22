package com.kxxnzstdsw.sundays.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * 通用右键菜单状态 —— 由调用方 [remember] 创建。
 *
 * ## 设计要点
 * - **通用组件**：不耦合特定 UI（既可用于表格，也可用于代码编辑器、列表等）
 * - **位置透明**：菜单的位置由调用方在右键事件中传入
 * - **目标透出**：[targetRow] 等上下文信息通过菜单插槽传递给菜单项 lambda
 *
 * ## 用法
 *
 * ```kotlin
 * // 1. 创建状态
 * val contextMenuState = rememberContextMenuState()
 *
 * // 2. 在目标 Composable 上监听右键
 * Modifier.onRightClick { offset ->
 *     contextMenuState.show(offset, payload = currentRow)
 * }
 *
 * // 3. 全局菜单 Popup
 * if (contextMenuState.visible) {
 *     DropdownMenu(
 *         expanded = true,
 *         onDismissRequest = { contextMenuState.dismiss() },
 *         offset = ...,
 *     ) {
 *         contextMenuItems(contextMenuState.payload)
 *     }
 * }
 * ```
 *
 * @param T payload 类型 — 通常为目标对象（行、编辑器上下文等）；`null` 表示"空白处右键"
 */
@Stable
class ContextMenuState<T : Any> {

    /** 当前右键点击的绝对坐标（相对 Composable 根的偏移）。 */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 菜单是否显示。 */
    var visible: Boolean by mutableStateOf(false)
        private set

    /** 当前右键点击的目标 payload；`null` 表示空白处右键。 */
    var payload: T? by mutableStateOf(null)
        private set

    /**
     * 显示菜单。
     *
     * @param atPosition 右键点击的坐标
     * @param payload 关联到此次点击的上下文对象（可为 `null`）
     */
    fun show(atPosition: Offset, payload: T?) {
        position = atPosition
        this.payload = payload
        visible = true
    }

    /** 关闭菜单并清空 payload。 */
    fun dismiss() {
        visible = false
        payload = null
    }
}

/**
 * 创建并 [remember] 一个 `ContextMenuState<Unit>` —— 适用于不需要 payload 的场景。
 */
@Composable
fun rememberContextMenuState(): ContextMenuState<Unit> =
    remember { ContextMenuState() }