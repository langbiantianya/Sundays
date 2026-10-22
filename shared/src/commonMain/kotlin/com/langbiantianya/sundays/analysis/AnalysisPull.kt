/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.SqlIdentifier

/*
 * 拉取链路的**纯规则**与**纯数据** —— 三段进度、staging 表的建表语句、导出的 SELECT。
 *
 * 真正会碰引擎与文件系统的部分在 `desktopApp` 的 `AnalysisPuller` 里；
 * 这里全是给定输入必然给出同一个输出的纯函数，能进 `commonTest`。
 */

/**
 * 拉取的三个阶段。
 *
 * ## 为什么是三段而不是两段
 *
 * 拉取要碰**两个**数据库（远端读 + 本地写），中间必然落一个文件。
 * 把「文件」当成一个阶段，是因为它是**用户能看见的占用**：
 * 一张大表拉一半时，临时 CSV 已经吃了几百 MB —— 界面上得有一行地方说清
 * 「现在占了多少磁盘」，否则用户不敢拉大表。
 *
 * 三段与引擎现成的两段进度一一对应：
 * - [EXPORT] ← `EXPORT.RUN_EXPORT` 的 `ExportProgressFrame`
 * - [CSV] ← 文件已落盘（大小在此刻可知）
 * - [IMPORT] ← `IMPORT.RUN_IMPORT` 的 `ImportProgressFrame`
 */
enum class PullStage {
    /** ① 远端 → CSV。 */
    EXPORT,

    /** ② CSV 落盘。 */
    CSV,

    /** ③ CSV → 工作区 staging 表。 */
    IMPORT,
}

/**
 * 拉取进度。
 *
 * @param stage 当前阶段
 * @param rows 该阶段已处理的行数（① 是已导出行数，③ 是已插入行数）
 * @param bytes 临时 CSV 的大小；② 之后才有值
 */
data class PullProgress(
    val stage: PullStage,
    val rows: Long = 0,
    val bytes: Long = 0,
)

/**
 * 拉取的结果。
 *
 * @param stagingTable 数据落进工作区的那张表的**完整**名字（含 `staging_` 前缀）
 * @param rowsExported 远端导出的行数
 * @param rowsImported 真正插进工作区的行数
 * @param rowsFailed 导入时被跳过的坏行数（`ignore_errors` 打开时才会非零）
 * @param csvBytes 临时 CSV 的大小（字节）
 */
data class PullResult(
    val stagingTable: String,
    val rowsExported: Long = 0,
    val rowsImported: Long = 0,
    val rowsFailed: Long = 0,
    val csvBytes: Long = 0,
)

/**
 * staging 表的列类型 —— **一律 `VARCHAR`**。
 *
 * ## 为什么不给远端的类型
 *
 * 看着「把远端的 `INT` 映成 `INTEGER`、`DECIMAL` 映成 `DECIMAL`」更贴心，
 * 但那条路会**整行丢数据**，实测如下：
 *
 * 1. CSV 导出把 **NULL 写成空串**（`CsvWriter.writeRow`：`it?.toString() ?: ""`）；
 * 2. 导入侧按**字符串**写值（`ImportHandler.bindRow`：`stmt.setString`），
 *    由驱动按目标列类型做隐式转换；
 * 3. 而 `INSERT INTO t (a) VALUES ('')` 在 `a INTEGER` 上**会失败** ——
 *    空串转不成整数。
 *
 * 于是：源表里只要有一列可空且是数值，**每一行**都会导入失败。
 * 打开 `ignore_errors` 也救不了 —— 失败的是**整行**，那一行的其它列也跟着没了。
 *
 * 而 `VARCHAR` 列对任何值都收得下（空串就是空串），**零行丢失**。
 *
 * ## 那类型怎么办
 *
 * staging 是**落地层**（数据仓库的惯例：原始层存文本，类型由下游建模决定）。
 * 要按数值聚合，在工作区里建一张**建模表**即可 ——
 * `CREATE TABLE orders AS SELECT TRY_CAST(id AS BIGINT) AS id, … FROM staging_shop_orders`。
 * `TRY_CAST`（而不是 `CAST`）让「转不动」的值落成 NULL 而不是让整条查询失败。
 *
 * 这条建模语句由界面在拉取完成后**直接给出来**（可一键复制），
 * 用户不需要自己回忆该怎么写。
 */
const val STAGING_COLUMN_TYPE: String = "VARCHAR"

/** 拉取用的临时 CSV 文件名 —— 每次拉取落在**各自**的临时目录里，所以重名无妨。 */
const val PULL_CSV_FILE_NAME: String = "pull.csv"

/**
 * 建 staging 表的语句。
 *
 * ## 为什么列名要**引用**而不是清洗
 *
 * staging 表的列名必须与 CSV 的**表头**逐字一致 —— 导入侧是拿表头里的名字
 * 去拼 `INSERT INTO … ("列名")` 的（`ImportHandler` 取 `source.columns`，
 * 而 CSV 源的列名就是表头）。清洗过的名字与表头对不上，
 * 插进去的就是「列不存在」。
 *
 * 而列名是**用户起的**，可能带空格、逗号、引号 —— 所以必须按 SQL 标准引用
 * （`"名字"`，内嵌 `"` 写成两个），不能拼裸名字。
 *
 * 引用走既有的 [SqlIdentifier]（与引擎的 `quoteIdentifier` 由
 * `SqlIdentifierQuoteTest` 逐方言对齐），不另写一份。
 *
 * @param table staging 表名（已由 [stagingTableName] 清洗过）
 * @param columns 列名（远端表的真实列名）
 */
fun createStagingTableSql(
    table: String,
    columns: List<String>,
): String {
    val defs =
        columns.map { column ->
            "${SqlIdentifier.quote(DialectType.DUCKDB, column)} $STAGING_COLUMN_TYPE"
        }
    val name = SqlIdentifier.quote(DialectType.DUCKDB, table)
    return "CREATE TABLE $name (${defs.joinToString(", ")})"
}

/**
 * 删掉 staging 表（不存在也不报错）的语句。
 *
 * 拉取一张**已经拉过**的表时先删旧的 —— 否则第二次拉取会撞
 * 「Table already exists」，而「重新拉一次覆盖掉旧的」才是用户想要的动作。
 */
fun dropStagingTableSql(table: String): String = "DROP TABLE IF EXISTS ${SqlIdentifier.quote(DialectType.DUCKDB, table)}"

/**
 * 导出用的 SELECT —— `SELECT * FROM <命名空间>.<表>`。
 *
 * ## 为什么带命名空间
 *
 * 命名空间在 MySQL 上是**库**、在 PG 上是 **schema**（主设计 §3.5 的中性叫法）。
 * 不带它的话，导出的是「连接默认库里那张同名表」——
 * 而用户点的是树里的**某一张**表，两者可能不是同一张。
 *
 * 引用形式按方言走 [SqlIdentifier.qualified]：MySQL 是 `` `库`.`表` ``、
 * PG 是 `"schema"."表"` —— 各自都是那个方言认的形式。
 */
fun pullSelectSql(
    dialect: DialectType,
    namespace: String,
    table: String,
): String = "SELECT * FROM ${SqlIdentifier.qualified(dialect, namespace, table)}"

/**
 * 拉取的**终态** —— 成功 / 取消 / 失败，三者互斥。
 *
 * ## 为什么不用「三个可空字段」来表达
 *
 * 早先的写法是 `pullResult` / `pullError` 两个可空字段，取消靠「两个都空」来推断。
 * 那是个**推断**，不是**事实**：界面得写「结果为空且错误为空 ⇒ 大概是取消了」，
 * 而「大概」在状态机里是不该出现的字眼 —— 一旦将来多一种「什么都没发生」的路径
 * （比如用户还没点拉取就被切了页），那两个字面量就再也分不出「取消」与「没开始」。
 *
 * 做成 sealed interface 之后，「取消」是**被显式记下来的那一种**，
 * 界面上也就能直接把 `Cancelled` 显示成「拉取已取消」而不是空白。
 */
sealed interface PullOutcome {
    /** 拉完了 —— [result] 里是行数、落点与临时文件大小。 */
    data class Done(
        val result: PullResult,
    ) : PullOutcome

    /** 用户中途取消了（不是失败 —— 取消没有错，只是没做完）。 */
    data object Cancelled : PullOutcome

    /** 拉失败了 —— [reason] 是引擎或文件系统给的原文。 */
    data class Failed(
        val reason: String,
    ) : PullOutcome
}

/**
 * 每一段对应的**文案键**。
 *
 * ## 为什么映射放在这里，而不是界面文件里
 *
 * 因为它是**可断言的**：三段各自对应哪个键，必须**互不相同**。
 * 复制粘贴最容易出的错就是三段都指向同一个键 —— 界面上于是三段显示同一句话，
 * 而截图上看不出来、点起来才发现。
 *
 * 放在界面文件里就没有这种断言可写（界面文件的测试要么不写，要么写成 UI 截图式的脆弱断言）；
 * 放在这里则一条 `distinct()` 就钉住了。
 */
fun PullStage.labelKey(): StrKey =
    when (this) {
        PullStage.EXPORT -> StrKey.ANALYSIS_PULL_STAGE_EXPORT
        PullStage.CSV -> StrKey.ANALYSIS_PULL_STAGE_CSV
        PullStage.IMPORT -> StrKey.ANALYSIS_PULL_STAGE_IMPORT
    }

/**
 * 某一段在当前进度下的**状态**。
 *
 * 为什么要把它做成一个显式的类型，而不是界面上随手比一下 `ordinal`：
 * 界面与测试**共用**同一条判定，才不会出现「界面把已完成画成待执行」
 * 而测试又在测另一套逻辑的情况。
 */
enum class PullStageState {
    /** 已经走完。 */
    DONE,

    /** 正在进行。 */
    CURRENT,

    /** 还没到。 */
    PENDING,
}

/**
 * [stage] 在当前进度 [current] 下的状态。
 *
 * 三段是**严格有序**的（导出 → CSV → 导入），所以状态只由两者的先后决定：
 * 排在前面的是走完的，排在后面的是待办的。
 */
fun pullStageState(
    stage: PullStage,
    current: PullStage,
): PullStageState =
    when {
        stage.ordinal < current.ordinal -> PullStageState.DONE
        stage.ordinal == current.ordinal -> PullStageState.CURRENT
        else -> PullStageState.PENDING
    }
