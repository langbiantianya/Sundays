/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 浏览器屏上流转的小数据类型 —— 跨文件共享、且谁也不属于某一个面板的那些。

package com.langbiantianya.sundays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.dataGenerateRequest
import com.langbiantianya.grpc.dataListRequest
import com.langbiantianya.grpc.dataRequest
import com.langbiantianya.grpc.foreignKeyCreateRequest
import com.langbiantianya.grpc.foreignKeyDeleteRequest
import com.langbiantianya.grpc.foreignKeyListItem
import com.langbiantianya.grpc.foreignKeyListRequest
import com.langbiantianya.grpc.foreignKeyRequest
import com.langbiantianya.grpc.functionListRequest
import com.langbiantianya.grpc.functionRequest
import com.langbiantianya.grpc.generateTable
import com.langbiantianya.grpc.indexListRequest
import com.langbiantianya.grpc.indexRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.schemaListRequest
import com.langbiantianya.grpc.schemaRequest
import com.langbiantianya.grpc.sqlExecuteRequest
import com.langbiantianya.grpc.sqlRequest
import com.langbiantianya.grpc.systemRequest
import com.langbiantianya.grpc.tableColumnListRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableDeleteRequest
import com.langbiantianya.grpc.tableGetDdlRequest
import com.langbiantianya.grpc.tableListRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.grpc.tableUpdateRequest
import com.langbiantianya.grpc.triggerListRequest
import com.langbiantianya.grpc.triggerRequest
import com.langbiantianya.grpc.viewListRequest
import com.langbiantianya.grpc.viewRequest
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionStatus
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.menuShape
import com.langbiantianya.sundays.ui.onRightClick
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.tabStripContainerColor
import com.langbiantianya.sundays.ui.winShape
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * JVM 堆占用快照 —— 底部状态栏的输入。
 *
 * 四个值与 `SystemHandler.info()` 返回的 `MemoryInfo` 一一对应，也与设置页
 * 「系统信息」里那四行**完全同名同序**（堆已用 / 已分配 / 上限 / 空闲）——
 * 状态栏弹窗就是那一段的缩小版，字段少两个就凑不齐。
 *
 * 之所以不直接传 `SystemInfo`：那个类带 11 个本栏用不上的字段（JVM 版本 / OS / PID…），
 * 每 2 秒搬一遍纯属浪费，且会让「保留上一帧」这类降级逻辑要操心整个对象。
 *
 * @property usedBytes `Runtime.totalMemory() - Runtime.freeMemory()`
 * @property totalBytes `Runtime.totalMemory()` —— 已向 OS **申请**到的堆
 * @property freeBytes `Runtime.freeMemory()` —— 上述已分配堆中尚未使用的部分
 * @property maxBytes `Runtime.maxMemory()`（即 `-Xmx`；未设时是 JVM ergonomics 算出的值）
 */
data class EngineMemory(
    val usedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long,
    val maxBytes: Long,
) {
    /**
     * 占用率，钳在 `0f..1f`。分母用**上限**而非已分配量 —— 用户关心的是「离 OOM 还有多远」，
     * 不是「离这次 GC 还有多远」。
     *
     * 钳位不是防御性冗余，是刚需：`used` 是采样瞬间的值，而 `max` 在容器里可能被调整
     * （`Runtime.maxMemory()` 返回 `Long.MAX_VALUE` 或 0 的实现是存在的），不钳位会画出
     * 一条冲出轨道、糊到标签上的进度条。
     */
    val ratio: Float
        get() = if (maxBytes <= 0L) 0f else (usedBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
}

/**
 * 一个数据库浏览 sheet 的完整描述 —— 持有独立的 [DatabaseBrowserState] 与该 sheet 的连接配置 + 引擎会话状态。
 */
data class SheetDescriptor(
    val connection: ConnectionConfig,
    val browser: DatabaseBrowserState,
    val status: ConnectionStatus,
)

/**
 * 右栏展示模式 —— 每 sheet 独立，由 [DatabaseBrowserState.activePane] 持有。
 *
 * - [TABLE] = 表预览（双击表节点打开的标签页区）；初始值
 * - [SQL] = SQL 工作台（编辑器 + 底部结果面板）；由工具栏切换
 * - [GENERATE] = 造数工作台（Lua 脚本编辑 + 造数结果面板）；由工具栏切换
 *
 * 三个 pane 是**同一份 sheet 状态**的两种以上渲染 —— 切换只改变渲染目标，不清空任何状态。
 */
enum class BrowserPane { TABLE, SQL, GENERATE }

/**
 * 一个错误来源的快照 —— 屏级对账用（见 [DatabaseBrowserScreen]）。
 *
 * @property key **唯一且稳定**的来源标识。推送与遗忘两边都用它 ——
 *   两边算得不一样，去重就会失效（第一版正是栽在这里）。
 */
internal data class ErrorSource(
    val key: String,
    val title: String,
    val detail: String,
)

/** 预览结果 —— 列名与前 N 行，供导出对话框的预览表渲染。 */
data class SqlPreviewRows(
    val columns: List<String>,
    /** 列名 → 列类型；与 [columns] 等长（下标一一对应）。 */
    val columnTypes: List<String>,
    val rows: List<Map<String, String>>,
    /** 引擎还在推帧但前端已攒够 [rows] —— UI 要如实说「已截断」，不能装作全量。 */
    val truncated: Boolean = false,
)
