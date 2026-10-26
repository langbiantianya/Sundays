/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import com.langbiantianya.sundays.analysis.AnalysisResult

/*
 * **报表设计**的数据模型 —— 一张报表由若干**部件**组成，每个部件就是
 * **一条 SQL + 一种图表类型**。
 *
 * ## 为什么部件就是「一条 SQL」
 *
 * 报表里的每一块（一张饼图、一根条形图、一张表）背后都是**一次查询**。
 * 把查询单独抽出来当「部件」，报表本身就成了「部件的清单」——
 * 这跟 BI 工具的做法一致（一个仪表盘由若干个卡片拼成，每个卡片自带查询），
 * 而且带来两个直接的好处：
 *
 * - **部件可以单独预览** —— 加部件时就能看到这条 SQL 跑出来的样例数据，
 *   不用先把整张报表搭出来再看效果；
 * - **部件可以复用与重排** —— 换顺序、加一块、删一块都不影响别的部件。
 *
 * ## 这一层是**纯数据**
 *
 * 不碰引擎、不碰文件系统 —— 所以能进 `commonTest`，在任何平台上都能跑。
 * 「跑 SQL」由 desktopApp 侧决定（它认识引擎与工作区）。
 */

/**
 * 部件的图表类型。
 *
 * ## 为什么只有三种
 *
 * - [PIE] —— 占比。一眼看出「谁占大头」。
 * - [BAR] —— 比大小。**比饼图更好读**（人眼比长度比角度准得多），
 *   所以它才是「默认推荐」。
 * - [TABLE] —— 原样铺开。SQL 返回什么就显示什么，不做任何加工。
 *
 * 不做折线 / 面积 / 散点 —— 那些要么需要**时间轴**（横轴是有序的数值，
 * 不是类别），要么需要**两个数值维度**（散点）。现在加进来只能是
 * 「画出来看不懂」的半成品，而半成品比没有更糟。
 */
enum class ReportWidgetType {
    /** 饼图 —— 第一列是类别，第二列是数值。 */
    PIE,

    /** 条形图 —— 第一列是类别，第二列是数值。 */
    BAR,

    /** 表格 —— 原样铺开 SQL 的结果。 */
    TABLE,
}

/**
 * 报表里的一个部件。
 *
 * @param id 部件 id —— 在报表内唯一（用来定位它的数据与编辑中的那一个）。
 *   **新建中的部件是空串**：id 是「存下来之后」才有的身份，见 [newReportWidgetDraft]
 * @param type 图表类型
 * @param title 标题（显示在部件上方；留空时界面用「部件 N」兜底）
 * @param sql 部件的查询 —— **一条** SQL
 */
data class ReportWidget(
    val id: String,
    val type: ReportWidgetType,
    val title: String,
    val sql: String,
)

/**
 * 新部件的**草稿** —— id 是空的。
 *
 * ## 为什么新建的部件没有 id
 *
 * id 是**存下来之后**才有的东西：报表住在会话里，由会话在保存的那一刻发 id。
 * 新建中的部件还没进报表 —— 它可能这一刻就被用户「取消」了。
 * 那时若先发了一个 id，等于承诺了一个它永远不会用到的身份，
 * 而这些 id 会一直留在会话的计数里，让后面的部件都带着一个空洞的序号。
 *
 * 所以这里只给**内容**（类型 / 标题 / SQL），id 交给保存的那一刻发。
 *
 * ## 默认类型为什么是**条形图**
 *
 * 条形图比饼图更好读（人眼比长度比角度准得多），所以它是「随便画点什么」的默认值。
 *
 * @param type 图表类型
 * @param title 标题
 * @param sql 查询
 */
fun newReportWidgetDraft(
    type: ReportWidgetType = ReportWidgetType.BAR,
    title: String = "",
    sql: String = "",
): ReportWidget = ReportWidget(id = "", type = type, title = title, sql = sql)

/**
 * 眼前的样例数据是不是**过期**的 —— 预览之后 SQL 又被改过。
 *
 * ## 为什么必须说出来
 *
 * 预览完再改 SQL，底下的样例数据还是**旧 SQL** 跑出来的。
 * 不说清楚的话，用户会以为眼前的数据就是新 SQL 的结果 ——
 * 而他恰恰是在确认「新 SQL 对不对」，看错了就等于没看。
 *
 * ## 为什么 `previewedSql = null`（还没预览过）不算过期
 *
 * 还没预览过时根本没有样例数据，也就没有「过期」这回事 ——
 * 那时界面显示的是「还没有预览」。
 *
 * @param previewedSql 上一次预览时的那条 SQL；`null` = 还没预览过
 * @param currentSql 现在编辑器里的 SQL
 * @return 样例数据是不是旧 SQL 跑出来的
 */
fun isPreviewStale(
    previewedSql: String?,
    currentSql: String,
): Boolean = previewedSql != null && previewedSql != currentSql

/**
 * 一张报表。
 *
 * @param id 报表 id
 * @param name 报表名
 * @param widgets 部件清单 —— 顺序即显示顺序
 */
data class AnalysisReport(
    val id: String,
    val name: String,
    val widgets: List<ReportWidget>,
)

/**
 * 图表里的一个类别 —— 饼图的一个扇区、条形图的一根柱子。
 *
 * @param label 类别名（SQL 第一列）
 * @param value 数值（SQL 第二列）
 */
data class ChartSlice(
    val label: String,
    val value: Double,
)

/**
 * 图表**画不出来**的原因。
 *
 * ## 为什么是枚举而不是一句文案
 *
 * 原因要**六种语言**都说得通，而这句话是在 `:shared` 里产生的（它不认识语言）。
 * 所以这里只产出**种类**，具体那句话由界面层按语言去取 —— 与
 * `DeleteSourceOutcome` / `PullOutcome` 同一个做法。
 */
enum class ChartProblem {
    /** SQL 没有返回任何列。 */
    NO_COLUMNS,

    /** 饼图 / 条形图需要两列，只有一列。 */
    NEED_TWO_COLUMNS,

    /** 第二列里有**不能当数字**的值 —— 类别列与数值列的约定被打破了。 */
    VALUE_NOT_NUMERIC,

    /** 第二列里有**负值** —— 饼图算不出角度，条形图画出来会误导。 */
    NEGATIVE_VALUES,

    /** SQL 跑成功了，但一行数据都没有。 */
    NO_DATA,
}

/**
 * 图表的**数据** —— 要么能画，要么说清为什么画不了。
 *
 * 穷举而不是「可空的 List」：「画不出来」有**四种**原因，
 * 而它们要用**不同的提示**（没列 / 列不够 / 值不是数字 / 没数据）——
 * 合成一句「图表数据有问题」等于什么都没说。
 */
sealed interface ChartData {
    /** 能画 —— [slices] 就是那几个类别。 */
    data class Ready(
        val slices: List<ChartSlice>,
    ) : ChartData

    /** 画不了 —— [problem] 是原因。 */
    data class Invalid(
        val problem: ChartProblem,
    ) : ChartData
}

/**
 * 把一次查询的结果变成图表的数据。
 *
 * ## 数据形状的约定
 *
 * - **第一列 = 类别**（饼图的扇区名、条形图的横轴标签）
 * - **第二列 = 数值**（扇区的大小、柱子的长度）
 *
 * 约定写在这里而不是「用户自己看着办」，是因为它一旦不明说，
 * 用户会写出一条三列的 SQL，然后看到一张**只画了前两列**的图 ——
 * 「我的第三列呢」。说不清是哪种形状，就直接告诉他「缺列」或「值不是数字」。
 *
 * ## 为什么**值不是数字**就整张图都不画
 *
 * 跳过不合法的那一行，图表会**看起来是对的**（少了一根柱子），
 * 而用户不知道少了什么 —— 那比直接报错糟得多。
 *
 * @param result 一次查询的结果（`null` = 还没跑过）
 * @return 能画就返回 [ChartData.Ready]，否则返回 [ChartData.Invalid]
 */
fun chartDataOf(result: AnalysisResult?): ChartData {
    if (result == null) return ChartData.Invalid(ChartProblem.NO_DATA)
    if (result.error != null) return ChartData.Invalid(ChartProblem.NO_DATA)
    if (result.columns.isEmpty()) return ChartData.Invalid(ChartProblem.NO_COLUMNS)
    if (result.columns.size < 2) return ChartData.Invalid(ChartProblem.NEED_TWO_COLUMNS)
    val slices =
        result.rows.map { row ->
            val label = row.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""
            val raw = row.getOrNull(1)
            val value = raw?.trim()?.toDoubleOrNull()
            if (value == null) {
                null
            } else {
                ChartSlice(label = label, value = value)
            }
        }
    if (slices.any { it == null }) return ChartData.Invalid(ChartProblem.VALUE_NOT_NUMERIC)
    if (slices.isEmpty()) return ChartData.Invalid(ChartProblem.NO_DATA)
    val ready = slices.filterNotNull()
    // 负值一律**不画** —— 饼图算不出角度（扇区会画到零线以下），
    // 条形图画成正值长度则是**误导**（-50 看起来像 50）。
    // 「是不是口径错了」该由用户确认，界面不替他猜。
    if (ready.any { it.value < 0.0 }) return ChartData.Invalid(ChartProblem.NEGATIVE_VALUES)
    return ChartData.Ready(ready)
}

/**
 * 图表的**取值范围** —— 条形图按它算柱长。
 *
 * @param slices 图表的数据（数值都非负 —— 负值在 [chartDataOf] 那里就被拦下了）
 * @return 最大值；没有数据时是 `0.0`
 */
fun chartValueScale(slices: List<ChartSlice>): Double = slices.maxOfOrNull { it.value } ?: 0.0
