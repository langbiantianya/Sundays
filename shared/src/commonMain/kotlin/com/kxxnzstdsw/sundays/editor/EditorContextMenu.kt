package com.kxxnzstdsw.sundays.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kxxnzstdsw.sundays.ui.ContextMenuState

/**
 * 编辑器右键菜单 payload —— 在右键点击时随菜单一起传递给菜单项 lambda。
 *
 * ## 字段
 * - [text]：当前编辑器的全部文本
 * - [languageId]：当前语言 ID（`null` 表示纯文本模式）
 *
 * ## 用法
 * ```kotlin
 * contextMenuItems = { payload ->
 *     payload?.let { p ->
 *         DropdownMenuItem(text = { Text("复制") }, onClick = { copyToClipboard(p.text) })
 *     }
 * }
 * ```
 */
data class EditorContextMenuPayload(
    val text: String,
    val languageId: String?,
)

/** 编辑器专用 `ContextMenuState<EditorContextMenuPayload>` 类型别名。 */
typealias EditorContextMenuState = ContextMenuState<EditorContextMenuPayload>

/**
 * 创建并 [remember] 一个 [EditorContextMenuState]。
 *
 * ```kotlin
 * val state = rememberEditorContextMenuState()
 * CodeEditor(
 *     text = sql,
 *     onTextChange = { sql = it },
 *     languageId = "sql",
 *     contextMenuState = state,
 *     contextMenuItems = { payload ->
 *         DropdownMenuItem(text = { Text("复制") }, onClick = { copy(payload?.text ?: "") })
 *     },
 * )
 * ```
 */
@Composable
fun rememberEditorContextMenuState(): EditorContextMenuState =
    remember { ContextMenuState<EditorContextMenuPayload>() }