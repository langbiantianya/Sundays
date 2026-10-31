/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import com.langbiantianya.sundays.analysis.AnalysisResult
import kotlinx.serialization.Serializable

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
 *
 * ## 为什么它能**序列化**
 *
 * 报表要落盘（见 `ReportStorage`），而落盘要认得每一种图表类型 ——
 * 枚举名（`PIE` / `BAR` / `TABLE`）就是磁盘上的取值。
 * 序列化名与枚举常量名一致，将来加一种类型不会让老文件读不出来
 * （老文件里根本没有那个名字）。
 */
@Serializable
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
 *
 * ## 为什么它能**序列化**
 *
 * 这三样（类型 / 标题 / SQL）就是部件的**全部**。部件不是别的东西 ——
 * 落盘时写进去的也就是这三样，读回来还是这三样。
 * （部件**查出来的数**不落盘 —— 那是派生数据，见 `ReportStorage`。）
 */
@Serializable
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
 *
 * ## 为什么它能**序列化**
 *
 * 报表的**全部**就是「id + 名字 + 部件清单」这三样。
 * 部件清单的**顺序**也是数据的一部分 —— 顺序即显示顺序，
 * 落盘时按原样存下来，重开应用还是那个顺序。
 */
@Serializable
data class AnalysisReport(
    val id: String,
    val name: String,
    val widgets: List<ReportWidget>,
)

/**
 * 部件的**移动方向**。
 *
 * ## 为什么用枚举而不是一个 `Int` 位移
 *
 * 位移量可以取任意整数（`-1` / `1` / `0` / `7`），而**只有两种**方向有意义。
 * 枚举把「往哪挪」这件事变成**只有两个答案**的问题 ——
 * 调用方写不出 `offset = 0`（原地不动）或者 `offset = 7`（跳七个位置）这种
 * 说不清的东西，因为根本没有那样的取值。
 */
enum class ReportWidgetMove {
    /** 往上挪一位。 */
    UP,

    /** 往下挪一位。 */
    DOWN,
}

/**
 * 这个部件**能不能**往那个方向挪。
 *
 * ## 为什么要有这么一条
 *
 * 界面上的「上移 / 下移」按钮要能**灰掉** —— 第一个部件不能上移、
 * 最后一个不能下移。
 *
 * 而「能不能挪」是**规则**，不是界面：会话在真正执行移动时也要用同一条规则
 * （界面上灰掉的按钮，也可能被别的调用方点到 —— 比如快捷键、或者
 * 界面与状态之间的一帧延迟）。
 * 规则与判定分开写，迟早会出现「界面说不行、会话说行」的裂缝。
 *
 * @param widgets 部件清单
 * @param widgetId 部件 id
 * @param move 方向
 * @return 挪了之后清单会**不一样**吗
 */
fun canMoveReportWidget(
    widgets: List<ReportWidget>,
    widgetId: String,
    move: ReportWidgetMove,
): Boolean {
    val index = widgets.indexOfFirst { it.id == widgetId }
    if (index < 0) return false
    val target = if (move == ReportWidgetMove.UP) index - 1 else index + 1
    return target in widgets.indices
}

/**
 * 把部件往那个方向挪一位。
 *
 * ## 为什么**挪不动时原样返回**
 *
 * 挪不动（已经是第一个 / 最后一个 / 压根没有这个部件）时，
 * 返回的是**同一个清单**（内容相同、顺序相同）。
 *
 * 这样调用方可以直接判「有没有变」来决定要不要落盘，
 * 而不必在调用前先问一遍 [canMoveReportWidget] ——
 * 问两遍就会出现「问的时候能挪，执行的时候不能」这种时间差。
 *
 * @param widgets 部件清单
 * @param widgetId 部件 id
 * @param move 方向
 * @return 挪动后的清单；挪不动时是原清单
 */
fun moveReportWidget(
    widgets: List<ReportWidget>,
    widgetId: String,
    move: ReportWidgetMove,
): List<ReportWidget> {
    val index = widgets.indexOfFirst { it.id == widgetId }
    if (index < 0) return widgets
    val target = if (move == ReportWidgetMove.UP) index - 1 else index + 1
    if (target !in widgets.indices) return widgets
    val moved = widgets.toMutableList()
    val item = moved.removeAt(index)
    moved.add(target, item)
    return moved
}

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

/**
 * 报表设计页顶部那条**状态提示**。
 *
 * ## 为什么是三种而不是「有 / 没有」
 *
 * 这条提示要说的事情有三类，而它们对用户的**分量**完全不同：
 *
 * - [Info] —— 「已导出到 D:/x.xlsx」。好消息，说一声。
 * - [Warn] —— 「报表文件读不出来，已改名保留成 …」。**数据没丢**，
 *   但有一件事用户该知道。
 * - [Error] —— 「报表保存失败：…」。**数据正在丢**，这是最要紧的一类。
 *
 * 合成一句话（「操作完成 / 操作失败」）就等于把「数据没丢但要留意」
 * 与「数据正在丢」混成一句 —— 用户分不出该不该着急。
 *
 * ## 为什么**消息本身**不在这儿
 *
 * 消息是**已经本地化好的字符串** —— 它由产生这件事的那一层
 * （会话 / 导出流程）用当前语言取好，界面只管按轻重上色。
 * 这一层不认识语言，也不该认识。
 */
sealed interface ReportStatus {
    /** 那句话本身 —— 界面只管照着显示。 */
    val message: String

    /** 成功的消息。 */
    data class Info(
        override val message: String,
    ) : ReportStatus

    /** 「没出事，但有件事该知道」的消息。 */
    data class Warn(
        override val message: String,
    ) : ReportStatus

    /** 出错了的消息 —— 数据可能正在丢。 */
    data class Error(
        override val message: String,
    ) : ReportStatus
}
