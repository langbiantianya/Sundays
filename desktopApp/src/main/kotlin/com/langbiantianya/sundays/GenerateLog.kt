/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.grpc.GenerateProgressFrame

/*
 * **造数日志** —— 引擎每回传一帧进度就追加一行。
 *
 * ## 数据源：引擎已经在流式回传了
 *
 * `DATA.GENERATE` 的流里每条 INSERT 回一帧 [GenerateProgressFrame]：
 * 表名、该脚本已插入的行数、脚本下标、以及**这一行的实际数据**。
 * 引擎侧不需要任何改动 —— 前端此前只拿它更新计数器，
 * 那些信息其实已经够拼出一份实时的造数日志了。
 *
 * ## 为什么每条 INSERT 一行，而不是每张表一行
 *
 * 「每张表一行」只剩一个总数，造数出错时（比如某个脚本写了别的表、
 * 或者数据类型不对）**看不出是哪一行出的问题**；
 * 而「每条 INSERT 一行」能直接看到写进去的是什么 ——
 * 这正是造数这种「批量写脏数据」的场景最需要的东西。
 *
 * ## 行号**可能跳号** —— 这不是 bug
 *
 * 引擎的帧通道容量是 1024 且**丢最旧**（`DROP_OLDEST`）：
 * 造得太快时消费端跟不上，帧会被丢掉。
 * 于是日志里的「第 N 行」会从 7 直接跳到 9。
 *
 * 刻意**不**把它藏起来 —— 跳号本身就是「引擎丢过帧」的事实，
 * 而行号是引擎回传的真值（日志显示的是**引擎说**插到第几行，不是本地数的）。
 * 把它抹平成连续的行号，等于伪造一份不存在的日志。
 */

/**
 * 造数日志**保留**的行数 —— 每次丢行之后回到这个数。
 *
 * 十万行造数不能把界面撑爆，所以日志必须有界。
 *
 * 注意它不是「缓冲区里的硬上限」：硬上限是这个数加上 [GENERATE_LOG_DROP_BATCH]
 * （成批丢，丢完才回到本数）。原因见 [GENERATE_LOG_DROP_BATCH]。
 */
const val GENERATE_LOG_MAX_LINES: Int = 1000

/**
 * 成批丢行的**批量** —— 同时也是缓冲区在两次丢行之间的余量。
 *
 * ## 为什么不是「超一行丢一行」
 *
 * 丢掉的是**最前面**的行，而 `SnapshotStateList.removeAt(0)` 是 O(n) 的数组搬移 ——
 * 十万行造数就是 5×10⁹ 次搬移，界面会直接卡死。
 *
 * 更要命的是**重组**：删掉第 0 行会让后面每一行都换了位置，
 * 于是**可见的每一行都要重组一次**。逐行删 = 每来一行就重排一次列表。
 *
 * 成批丢（每 128 行搬一次）把这笔开销摊薄到 1/128。
 * 代价是缓冲区在两次丢行之间会涨到 `MAX_LINES + DROP_BATCH` ——
 * 这是**故意的**：宁可多留 128 行，也不肯每行都重排一次。
 */
const val GENERATE_LOG_DROP_BATCH: Int = 128

/** 单个值在日志里显示的最大字符数 —— 再长也截断（后面加省略号）。 */
const val GENERATE_LOG_VALUE_MAX_CHARS: Int = 40

/**
 * 造数日志的一行。
 *
 * @param scriptIndex 脚本下标（**1-based**，与引擎的 `script_index` 一致）；`0` = 失败行
 * @param totalScripts 本次请求的脚本总数
 * @param table 目标表名
 * @param rowNumber 该脚本内已插入的行数（引擎回传的真值，**可能跳号**）
 * @param values 这一行的实际数据（列名 → 值）
 * @param error 失败行的引擎原文；`null` = 正常行
 */
class GenerateLogEntry(
    val scriptIndex: Int,
    val totalScripts: Int,
    val table: String,
    val rowNumber: Long,
    val values: Map<String, Any?>,
    val error: String? = null,
)

/**
 * 把一行的数据拼成「列=值, 列=值」的文本。
 *
 * ## 为什么按**列名排序**而不是沿用引擎的顺序
 *
 * 引擎那边是按 Lua 表的键序拼的，但**跨了 protobuf 就没了** ——
 * proto 的 `map` 字段不保证顺序，收到时的遍历序是哈希序。
 * 不排序的话同一份数据每次渲染出来的列序可能不同，
 * 日志在用户眼前会「跳来跳去」。按列名排序换来**稳定**。
 *
 * @param values 列名 → 值（`null` = SQL NULL）
 */
fun formatGenerateLogValues(values: Map<String, Any?>): String =
    values.entries
        .sortedBy { it.key }
        .joinToString(", ") { (column, value) -> "$column=${formatGenerateLogValue(value)}" }

/**
 * 单个值的显示文本 —— `null` 显示成 `NULL`，超长截断。
 *
 * 整数的 `Double` 要去掉 `.0`：`1` 显示成 `1.0` 是引擎那边
 * JSON 化时把整数塞进了 double，改不动；但显示层可以收拾干净。
 */
private fun formatGenerateLogValue(value: Any?): String {
    val text =
        when (value) {
            null -> "NULL"
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
    return if (text.length > GENERATE_LOG_VALUE_MAX_CHARS) {
        text.take(GENERATE_LOG_VALUE_MAX_CHARS) + "…"
    } else {
        text
    }
}

/**
 * 把引擎回传的一帧进度翻成一行日志。
 *
 * 抽成纯函数是为了能单测 —— 「帧 → 行」这段映射是这段逻辑里唯一会写错的地方，
 * 而它在界面上表现为「日志里少了一列 / 行号不对」，很难一眼看出根因。
 *
 * @param frame 引擎的进度帧
 * @param valueOf 帧里 `google.protobuf.Value` 的取值函数（由状态机注入：
 *   那是它自己的私有转换，在这里重复一份就会出现两份可能对不上的真相）
 */
fun generateLogEntryOf(
    frame: GenerateProgressFrame,
    valueOf: (com.google.protobuf.Value) -> Any?,
): GenerateLogEntry =
    GenerateLogEntry(
        scriptIndex = frame.scriptIndex,
        totalScripts = frame.totalScripts,
        table = frame.table,
        rowNumber = frame.inserted,
        values = frame.data.valuesMap.mapValues { (_, value) -> valueOf(value) },
    )
