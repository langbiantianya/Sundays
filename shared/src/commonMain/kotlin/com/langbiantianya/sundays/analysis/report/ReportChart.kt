/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.ui.str
import java.util.Locale
import kotlin.math.min

/*
 * **图表** —— 饼图与条形图，都用 Compose 的 `Canvas` 手绘。
 *
 * ## 为什么不用现成的图表库
 *
 * 现成的 Compose 图表库（Vico / KoalaPlot 之类）要么引第三方依赖，
 * 要么只支持 Android —— 而本项目的界面层住在 `:shared` 的 `commonMain`
 * （将来的移动端 / Web 也要用）。为一个「饼图 + 条形图」引入一整套
 * 图表框架不划算，而且它们的 API 会把「画什么」与「怎么画」焊在一起 ——
 * 而这里恰恰需要自己控制两件事：
 *
 * 1. **数据形状的约定**（第一列类别、第二列数值）—— 库只管画，
 *    不管你的 SQL 返回了什么形状；
 * 2. **画不出来时要说清原因**（缺列 / 值不是数字 / 有负值）——
 *    库只会画出一张空图。
 *
 * 手绘这两个图形要多少代码？`drawArc` + `drawRect`，加起来不到一百行。
 *
 * ## 图例为什么**在外面**而不是画在 Canvas 里
 *
 * Canvas 里画不了文字（要自己摆 `drawText`，还得处理字体度量）。
 * 所以图例用普通 `Text` 排 —— 顺带白拿了文本选择、自动换行与
 * 无障碍语义。
 */

/** 图表的**色板** —— 按顺序取，超出则循环。 */
private val CHART_PALETTE =
    listOf(
        Color(0xFF4E79A7),
        Color(0xFFF28E2B),
        Color(0xFF59A14F),
        Color(0xFFE15759),
        Color(0xFFB07AA1),
        Color(0xFF76B7B2),
        Color(0xFFEDC948),
        Color(0xFF9C755F),
    )

/**
 * 取第 [index] 个类别的颜色。
 *
 * @param index 类别序号（**0-based**）
 */
fun chartColor(index: Int): Color = CHART_PALETTE[index.mod(CHART_PALETTE.size)]

/**
 * 图表预览 —— 按类型画图，或者说清**为什么画不出来**。
 *
 * ## 为什么单独抽出来
 *
 * 同一个部件会在**两个地方**出现：报表画布上的卡片、部件编辑页里的预览。
 * 两处必须画**同一张图**、说**同一句原因** —— 所以判据（[chartDataOf]）与画法
 * 只留这一份，两处都调它。
 *
 * ## 为什么图表区**自己**能滚
 *
 * 卡片的高度是**固定**的（见 `ReportDesignerScreen`），而图表的高度取决于
 * 类别个数 —— 十来个类别就撑破了。图表区自己带一个滚动，
 * 类别再多也只是要滚一下，而卡片不会被撑变形。
 *
 * ## 为什么画不出来时**不用** `EmptyHint`
 *
 * `EmptyHint` 内部自己带一个纵向滚动（为了容得下几百字符的报错原文）。
 * 而图表区**外面**已经有一个滚动了 —— 滚动套滚动，内层会拿到
 * 「无限高」的约束，Compose 直接抛异常。
 * 图表的原因只有一句话，短得很，用不着滚动。
 *
 * @param type 图表类型（[ReportWidgetType.TABLE] 不走这里 —— 表格没有「图表」）
 * @param result 该部件的数据；`null` = 还没取过
 * @param modifier 布局修饰
 */
@Composable
fun ReportChartPreview(
    type: ReportWidgetType,
    result: AnalysisResult?,
    modifier: Modifier = Modifier,
) {
    when (val data = chartDataOf(result)) {
        is ChartData.Invalid -> {
            ChartProblemHint(problem = data.problem, modifier = modifier)
        }

        is ChartData.Ready -> {
            Box(modifier = modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    when (type) {
                        ReportWidgetType.PIE -> {
                            PieChart(slices = data.slices, modifier = Modifier.fillMaxWidth())
                        }

                        ReportWidgetType.BAR -> {
                            BarChart(slices = data.slices, modifier = Modifier.fillMaxWidth())
                        }

                        ReportWidgetType.TABLE -> {
                            // 表格没有图表形态 —— 调用方不该走到这里；
                            // 真走到了也不能崩，画一片空白比崩掉好。
                            Box(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「画不出图表」的原因提示 —— 标题 + 一句原因。
 *
 * 刻意**不用** [EmptyHint]：它内部带纵向滚动，套进图表区自己的滚动里会炸
 * （内层拿到无限高约束）。
 *
 * @param problem 原因
 * @param modifier 布局修饰
 */
@Composable
private fun ChartProblemHint(
    problem: ChartProblem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = str(StrKey.REPORT_CHART_CANNOT_DRAW),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = str(chartProblemText(problem)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 饼图 —— 若干个扇区。
 *
 * ## 为什么扇区之间留一道**缝**
 *
 * 相邻的两个扇区颜色相近时，边界会糊在一起 ——
 * 用户根本看不出这是两块。留一道底色的缝，边界就清楚了。
 *
 * @param slices 图表数据（类别 + 数值）
 * @param modifier 布局修饰
 */
@Composable
fun PieChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .weight(1f)
                    .height(180.dp),
        ) {
            if (total <= 0.0) return@Canvas
            // 半径取短边的一半，再留出缝的宽度。
            val radius = min(size.width, size.height) / 2f - CHART_SLICE_GAP_PX
            if (radius <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            var startAngle = -90f
            slices.forEachIndexed { index, slice ->
                val sweep = (slice.value / total * 360.0).toFloat()
                drawArc(
                    color = chartColor(index),
                    startAngle = startAngle,
                    // 留一道缝：扇区实角减去缝宽。
                    sweepAngle = (sweep - CHART_SLICE_GAP_DEG).coerceAtLeast(0.5f),
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                )
                startAngle += sweep
            }
        }
        ChartLegend(slices = slices)
    }
}

/**
 * 条形图 —— 若干根柱子。
 *
 * ## 为什么**竖着**排（标签在上、柱子在下）而不是横条
 *
 * 类别名往往比数值长得多（「华东大区」比「128」长一个量级）。
 * 竖排时标签在上方、整行宽度都留给标签；横排时标签在左边，
 * 左边一窄，标签就得折行或截断。
 *
 * @param slices 图表数据（类别 + 数值）
 * @param modifier 布局修饰
 */
@Composable
fun BarChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
) {
    val scale = chartValueScale(slices)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slices.forEachIndexed { index, slice ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = slice.label.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(14.dp),
                    ) {
                        if (scale <= 0.0) return@Canvas
                        val ratio = (slice.value / scale).toFloat().coerceIn(0f, 1f)
                        drawRoundRect(
                            color = chartColor(index),
                            size = Size(size.width * ratio, size.height),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                    Text(
                        text = formatChartValue(slice.value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 图例 —— 类别名 + 数值，配一块颜色。
 *
 * @param slices 图表数据
 * @param modifier 布局修饰
 */
@Composable
private fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(180.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        slices.forEachIndexed { index, slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .background(chartColor(index), shape = CircleShape),
                )
                Text(
                    text = slice.label.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatChartValue(slice.value),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 数值 → 显示文本。
 *
 * ## 为什么要**去掉 `.0`**
 *
 * 整数就该显示成 `128` 而不是 `128.0` —— 用户看到 `128.0` 会以为是金额或比例，
 * 而它其实就是个计数。
 *
 * ## 为什么小数点用 `Locale.ROOT`
 *
 * 默认区域设置下德语区会写成 `128,50` —— 同一份数据在两种语言下显示成两种
 * 数字格式，截图对比时会以为出了 bug。图表里的数值是**数据**，不是文案，
 * 所以固定用 `Locale.ROOT`。
 *
 * @param value 数值
 */
fun formatChartValue(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format(Locale.ROOT, "%.2f", value)
}

/** 饼图扇区之间那道缝的角度宽度（度）。 */
private const val CHART_SLICE_GAP_DEG = 1.5f

/** 饼图扇区之间那道缝的像素宽度。 */
private const val CHART_SLICE_GAP_PX = 1f
