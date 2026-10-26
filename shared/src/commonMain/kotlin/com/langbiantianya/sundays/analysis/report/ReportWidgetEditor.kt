/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.AnalysisResult
import com.langbiantianya.sundays.analysis.analysisTableColumns
import com.langbiantianya.sundays.analysis.analysisTableRows
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.str

/*
 * **部件编辑页** —— 改一个部件：它的类型、标题、SQL，以及**这条 SQL 跑出来的样例数据**。
 *
 * ## 为什么是**覆盖**设计页面而不是弹窗
 *
 * 这一页要同时摆下三样东西：SQL 编辑器、样例数据、图表预览。
 * 弹窗没有地方放编辑器 —— 它天生就是给「一句话 + 两个按钮」用的。
 * 挤在一个小窗口里的话，编辑器只剩两三行高，写 SQL 变成一种折磨。
 *
 * ## 为什么样例数据要**当场能看**
 *
 * 部件的形状约定是「第一列类别、第二列数值」—— 这是一条**约定**，
 * 而约定只有看得见数据才能被遵守。用户写完 SQL 若只能「保存后到画布上看」，
 * 那就要先建好整张报表才知道自己写错了 —— 试错成本被放大到不可接受。
 * 所以这一页把样例数据摆在 SQL 旁边：**改一句，看一眼**。
 */

/** 部件编辑页里各处的 UI 测试 tag。 */
object ReportWidgetEditorTags {
    /** 编辑页本体。 */
    const val EDITOR = "reportWidgetEditor"

    /** 标题输入框。 */
    const val TITLE_FIELD = "reportWidgetTitleField"

    /** SQL 编辑器。 */
    const val SQL_EDITOR = "reportWidgetSqlEditor"

    /** 「预览样例数据」按钮。 */
    const val PREVIEW_BUTTON = "reportWidgetPreviewBtn"

    /** 样例数据表。 */
    const val SAMPLE_TABLE = "reportWidgetSampleTable"

    /** 图表预览区。 */
    const val CHART_PREVIEW = "reportWidgetChartPreview"

    /** 「保存」按钮。 */
    const val SAVE_BUTTON = "reportWidgetSaveBtn"

    /** 类型单选组 —— 饼图。 */
    const val TYPE_PIE = "reportWidgetTypePie"

    /** 类型单选组 —— 条形图。 */
    const val TYPE_BAR = "reportWidgetTypeBar"

    /** 类型单选组 —— 表格。 */
    const val TYPE_TABLE = "reportWidgetTypeTable"
}

/**
 * 部件编辑页。
 *
 * ## 草稿住在**这一层**，不住在会话里
 *
 * 正在编辑的东西是「**还没保存**的草稿」—— 会话只该存已经存下来的报表。
 * 放在这一层还有一个附带的好处：点「取消」天然什么都不用做（草稿随这一层一起消失），
 * 不需要「存一份旧的，改完再恢复」这种容易出错的回滚逻辑。
 *
 * @param widget 编辑中的部件 —— 新建时是 [newReportWidgetDraft] 的产物
 * @param isNew 是新建还是改已有的
 * @param previewResult 最近一次预览的结果；`null` = 还没预览
 * @param previewRunning 正在取样例数据
 * @param onPreview 取样例数据 —— 参数是**当前草稿里的 SQL**
 * @param onSave 保存 —— 参数是最终的部件
 * @param onDismiss 关闭这一页（不保存）
 * @param modifier 布局修饰
 */
@Composable
fun ReportWidgetEditor(
    widget: ReportWidget,
    isNew: Boolean,
    previewResult: AnalysisResult? = null,
    previewRunning: Boolean = false,
    onPreview: (String) -> Unit = {},
    onSave: (ReportWidget) -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 草稿 —— 三样都从 [widget] 起头，改动只落在这里。
    //
    // `remember(widget.id)`：换一个部件就换一份草稿。
    // （新建的部件 id 是空串，但编辑页每次都是**新组合**出来的 ——
    //  关掉再打开，状态已经随组合一起没了，不会串到上一个部件上。）
    var title by remember(widget.id) { mutableStateOf(widget.title) }
    var type by remember(widget.id) { mutableStateOf(widget.type) }
    var sql by remember(widget.id) { mutableStateOf(widget.sql) }

    // **上一次预览时**的 SQL —— 用来判断眼前的样例数据是不是已经过期了。
    //
    // 预览完再改 SQL，样例数据就还是**旧 SQL** 跑出来的；
    // 不说清楚的话，用户会以为眼前的数据就是新 SQL 的结果。
    var previewedSql by remember(widget.id) { mutableStateOf<String?>(null) }

    // 样例数据的分页大小 —— 放在这一层是因为它只关乎「这一页怎么看」。
    var samplePageSize by remember(widget.id) { mutableStateOf(PageSize.DEFAULT) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ReportWidgetEditorTags.EDITOR),
    ) {
        // ── 顶栏：标题 + 取消 / 保存 ──
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(if (isNew) StrKey.REPORT_WIDGET_NEW else StrKey.REPORT_WIDGET_EDIT),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            WinTextButton(onClick = onDismiss) { Text(str(StrKey.CANCEL)) }
            WinTextButton(
                onClick = { onSave(widget.copy(type = type, title = title, sql = sql)) },
                modifier = Modifier.testTag(ReportWidgetEditorTags.SAVE_BUTTON),
            ) { Text(str(StrKey.SAVE)) }
        }
        HorizontalDivider()

        // ── 类型 + 标题 ──
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WinTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(str(StrKey.REPORT_TITLE_LABEL)) },
                modifier =
                    Modifier
                        .width(260.dp)
                        .testTag(ReportWidgetEditorTags.TITLE_FIELD),
            )
            Text(
                text = str(StrKey.REPORT_TYPE_LABEL),
                style = MaterialTheme.typography.labelLarge,
            )
            ReportWidgetType.entries.forEach { option ->
                ReportWidgetTypeOption(
                    type = option,
                    selected = option == type,
                    onSelect = { type = option },
                )
            }
        }
        HorizontalDivider()

        // ── 主体：左边写 SQL，右边看样例数据 ──
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CodeEditorWithToolbar(
                text = sql,
                onTextChange = { sql = it },
                languageId = SqlDialectProfile.DUCKDB.languageId,
                // 部件的 SQL 固定跑在工作区（DuckDB）上 —— 让用户切方言
                // 只会写出一条**跑不了**的语句，不如不给这个选项。
                showLanguageSwitcher = false,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(ReportWidgetEditorTags.SQL_EDITOR),
            )
            Spacer(Modifier.width(12.dp))
            ReportSampleDataPane(
                type = type,
                result = previewResult,
                running = previewRunning,
                stale = isPreviewStale(previewedSql, sql),
                pageSize = samplePageSize,
                onPageSizeChange = { samplePageSize = it },
                onPreview = {
                    previewedSql = sql
                    onPreview(sql)
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * 类型单选组里的一项 —— 一个单选钮 + 它的名字。
 *
 * 用 `RadioButton`（而不是三个按钮）是因为它是**单选**：两个按钮同时「亮着」
 * 会让用户以为自己同时选中了两种图。
 *
 * @param type 这个选项代表的类型
 * @param selected 是不是当前选中的
 * @param onSelect 选中它
 * @param modifier 布局修饰
 */
@Composable
private fun ReportWidgetTypeOption(
    type: ReportWidgetType,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag =
        when (type) {
            ReportWidgetType.PIE -> ReportWidgetEditorTags.TYPE_PIE
            ReportWidgetType.BAR -> ReportWidgetEditorTags.TYPE_BAR
            ReportWidgetType.TABLE -> ReportWidgetEditorTags.TYPE_TABLE
        }
    Row(
        modifier = modifier.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = str(reportWidgetTypeText(type)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * **样例数据**面板 —— 这条 SQL 跑出来是什么。
 *
 * ## 为什么样例数据与图表预览要**同时**给
 *
 * 图表是**结论**（占比、比大小），样例数据是**原始行**。
 * 只给结论，用户核对不了「这个数对不对」；只给原始行，用户看不出该做成什么图。
 * 两者并排，用户一眼就能确认「形状对不对」——
 * 而「形状对不对」正是部件这件事的全部难点。
 *
 * @param type 部件的类型（决定要不要画图表预览）
 * @param result 最近一次预览的结果；`null` = 还没预览
 * @param running 正在取样例数据
 * @param stale 样例数据是不是**旧 SQL** 跑出来的
 * @param pageSize 样例数据的分页大小
 * @param onPageSizeChange 改分页大小
 * @param onPreview 重新取样例数据
 * @param modifier 布局修饰
 */
@Composable
private fun ReportSampleDataPane(
    type: ReportWidgetType,
    result: AnalysisResult?,
    running: Boolean,
    stale: Boolean,
    pageSize: PageSize,
    onPageSizeChange: (PageSize) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(StrKey.REPORT_SAMPLE_DATA),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            WinTextButton(
                onClick = onPreview,
                // 正在取的时候禁掉：连点两下就是两条并发查询，
                // 而工作区是**一个** DuckDB 文件 —— 后一条会把前一条的结果盖掉。
                enabled = !running,
                modifier = Modifier.testTag(ReportWidgetEditorTags.PREVIEW_BUTTON),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(str(StrKey.REPORT_PREVIEW_SAMPLE))
            }
        }
        when {
            running -> {
                Text(
                    text = str(StrKey.REPORT_PREVIEW_RUNNING),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            result == null -> {
                EmptyHint(
                    title = str(StrKey.REPORT_PREVIEW_EMPTY),
                    description = str(StrKey.REPORT_PREVIEW_HINT),
                    centerContent = false,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            result.failed -> {
                EmptyHint(
                    title = str(StrKey.REPORT_RESULT_FAILED),
                    description = result.error,
                    centerContent = false,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            else -> {
                if (stale) {
                    Text(
                        text = str(StrKey.REPORT_PREVIEW_STALE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (type != ReportWidgetType.TABLE) {
                    ReportChartPreview(
                        type = type,
                        result = result,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .testTag(ReportWidgetEditorTags.CHART_PREVIEW),
                    )
                }
                DataTable(
                    columns = analysisTableColumns(result),
                    rows = analysisTableRows(result),
                    pageSize = pageSize,
                    onPageSizeChange = onPageSizeChange,
                    serverSidePaging = false,
                    showDetailPanel = false,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag(ReportWidgetEditorTags.SAMPLE_TABLE),
                )
            }
        }
    }
}
