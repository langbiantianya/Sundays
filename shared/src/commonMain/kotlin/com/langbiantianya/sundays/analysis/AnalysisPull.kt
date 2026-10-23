/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

/*
 * 拉取的**纯模型** —— 进度、结果、终态。
 *
 * 真正会碰引擎的部分在 `desktopApp` 的 `AnalysisPuller` 里；
 * 这里全是纯数据，能进 `commonTest`。
 */

/**
 * 拉取进度。
 *
 * ## 为什么只有**一个数**
 *
 * 早先的模型有三段（导出 → CSV → 导入），因为那时数据要**绕一圈磁盘**：
 * 远端导出成 CSV、CSV 落盘、再导入工作区。
 *
 * 现在的拉取是 **DuckDB 的联邦直查** —— 行从远端**直接**流进工作区表，
 * 中间**没有阶段可分**：「扫描」与「写入」是同一条流水线上的两件事，
 * 拆成两段只会让界面显示一个永远不出现的阶段。
 *
 * 同理，「临时文件占了多少磁盘」这件事**整个消失了** ——
 * 联邦直查**不落盘**，也就没有临时文件可占。
 *
 * @param rows 已从远端**读出**的行数（还没落表，但已经从远端拿到了）
 */
data class PullProgress(
    val rows: Long = 0,
)

/**
 * 拉取的结果。
 *
 * @param stagingTable 数据落进工作区的那张表的**完整**名字（含 `staging_` 前缀）
 * @param rowsScanned 从远端读出的行数
 * @param rowsLanded 真正落到工作区表里的行数
 */
data class PullResult(
    val stagingTable: String,
    val rowsScanned: Long = 0,
    val rowsLanded: Long = 0,
)

/**
 * 拉取的**终态** —— 成功 / 取消 / 失败，三者互斥。
 *
 * ## 为什么不用「三个可空字段」来表达
 *
 * 早先的写法是 `pullResult` / `pullError` 两个可空字段，取消靠「两个都空」来推断。
 * 那是个**推断**，不是**事实**：界面得写「结果为空且错误为空 ⇒ 大概是取消了」，
 * 而「大概」在状态机里是不该有的字眼 —— 一旦将来多出第四种「什么都没发生」的路径
 * （比如还没点拉取就被切了页），那两个字面量就再也分不出「取消」与「没开始」。
 *
 * 做成 sealed interface 之后，「取消」是**被显式记下来的那一种**，
 * 界面上也就能直接把 `Cancelled` 显示成「拉取已取消」而不是空白。
 */
sealed interface PullOutcome {
    /** 拉完了 —— [result] 里是行数与落点。 */
    data class Done(
        val result: PullResult,
    ) : PullOutcome

    /** 用户中途取消了（不是失败 —— 取消没有错，只是没做完）。 */
    data object Cancelled : PullOutcome

    /** 拉失败了 —— [reason] 是引擎给的原文。 */
    data class Failed(
        val reason: String,
    ) : PullOutcome
}

/**
 * 拉取被**用户取消**了 —— 不是失败。
 *
 * ## 为什么不用 `CancellationException`
 *
 * 协程的 `CancellationException` 说的是「**这个协程**被取消了」——
 * 抛出它等于让整个协程以「被取消」收场。
 * 而用户按「取消拉取」时，事情是「**这一次拉取**没做完」：
 * 协程本身好好的，只是这次拉取的结果没有意义。
 *
 * 分成两个异常，界面才能把「用户取消」与「协程被取消」分开处理：
 * 前者把终态记成 [PullOutcome.Cancelled] 就收工，后者还要把异常抛回去。
 */
class AnalysisPullCancelledException(
    message: String = "拉取已取消",
) : Exception(message)
