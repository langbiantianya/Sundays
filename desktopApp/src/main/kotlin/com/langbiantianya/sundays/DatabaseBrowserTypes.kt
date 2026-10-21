/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 浏览器屏上流转的小数据类型 —— 跨文件共享、且谁也不属于某一个面板的那些。

package com.langbiantianya.sundays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import com.langbiantianya.sundays.browser.SheetSummary
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.ConnectionStatus

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
 * sheet → 标签条要的**窄形状**。
 *
 * 标签条（`:shared` 的 `SheetTabRow`）只需要「谁的连接、叫什么、什么状态」，
 * 不需要 `DatabaseBrowserState`（那是引擎接线的产物）。转换点放在这里 ——
 * `:desktopApp` 侧唯一知道「sheet 是什么」的地方。
 */
internal fun SheetDescriptor.toSummary(): SheetSummary =
    SheetSummary(
        id = connection.id,
        name = connection.name,
        status = status,
    )

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
