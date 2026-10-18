/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 导出相关的一切 —— 危险语句确认框、导出配置弹窗、导出进度弹窗、结果预览表，
// 以及它们用到的纯函数（语句切分、文件名生成、proto 值展开）。

package com.langbiantianya.sundays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.sql.DangerousSql
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinOutlinedButton
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
import com.google.protobuf.Value as ProtoValue

/** 表预览「导出」按钮的 UI 测试 tag。 */
internal const val TABLE_EXPORT_BTN_TAG = "tableExportBtn"

/** 导出对话框里「开始导出」按钮的 UI 测试 tag。 */
internal const val EXPORT_CONFIRM_BTN_TAG = "exportConfirmBtn"

/** 导出对话框里可编辑 SQL 的 UI 测试 tag。 */
private const val EXPORT_SQL_EDITOR_TAG = "exportSqlEditor"

/** 导出对话框里「预览」按钮的 UI 测试 tag。 */
private const val EXPORT_PREVIEW_BTN_TAG = "exportPreviewBtn"

/** SQL 工作台「导出」按钮的 UI 测试 tag。 */
internal const val SQL_EXPORT_BTN_TAG = "sqlExportBtn"

/**
 * 把一段脚本切成待逐条导出的语句。
 *
 * 复用引擎侧的 [com.langbiantianya.engine.SqlScriptSplitter] 而不是 `split(";")` ——
 * 后者会把字符串字面量、注释、PG 美元引用函数体里的分号当作语句边界，
 * 于是一句 `SELECT ';' AS x` 会被劈成两半。
 *
 * 只保留**读**语句。导出是把查询结果落成文件；把 `UPDATE` / `DROP` 也塞进导出
 * 是没有意义的，而它们的副作用是在执行时发生 —— 这里在**切分之后、发送之前**
 * 就把它们剔掉，用户不会因为点了个「导出」而改动了数据。
 */
internal fun exportStatementsFor(script: String): List<String> =
    com.langbiantianya.engine.SqlScriptSplitter
        .split(script)
        .filter { it.isNotBlank() && DangerousSql.isReadOnlyQuery(it) }

/** SQL 工作台「导出」的进度（多语句时用来显示「第 k/N 条」）。 */
private data class SqlExportProgress(
    val done: Int,
    val total: Int,
)

/**
 * 多语句导出时给第 [index] 条语句生成文件名。
 *
 * ## 规则：只在**确实有多条**时加序号
 *
 * [total] == 1 时原样返回 —— 单条语句却得到 `export_1.csv`，
 * 会让用户以为自己导了个「片段」，而完整脚本导出本来就该是文件名原样。
 *
 * ## 为什么要编号而不是拼进文件名主体
 *
 * `SELECT ...` → `订单_2024.csv` 变成 `订单_2024_1.csv` 还好，
 * 但用户取名成 `报表(1).csv` 时会得到 `报表(1)_1.csv`。把序号插在扩展名前、
 * 只碰扩展名左边那一段，至少不会把括号、引号这类有语法含义的东西搞乱。
 */
internal fun numberedFileName(
    base: String,
    index: Int,
    total: Int,
): String {
    if (total <= 1) return base
    val dot = base.lastIndexOf('.')
    // 无扩展名 / 隐藏文件（`.env` 这种 dot 在开头）都直接追加
    val safeDot = if (dot <= 0) -1 else dot
    val stem = if (safeDot < 0) base else base.substring(0, safeDot)
    val ext = if (safeDot < 0) "" else base.substring(safeDot)
    // ⚠️ `${stem}_` 的花括号不能省：`$stem_` 会被 Kotlin 解析成一个叫 `stem_` 的变量
    return "${stem}_${index + 1}$ext"
}

/**
 * protobuf [ProtoValue] → 可读文本。
 *
 * ⚠️ **不能**直接用 `Value.toString()`：那给的是 `string_value: "另一个库的行"`
 * 这种调试串，拿去和真实内容比永远不相等 —— 而值本身其实是对的。
 */
internal fun unwrapProtoValue(v: ProtoValue): String =
    when (v.kindCase) {
        ProtoValue.KindCase.STRING_VALUE -> {
            v.stringValue
        }

        ProtoValue.KindCase.NUMBER_VALUE -> {
            if (v.numberValue == kotlin.math.floor(v.numberValue)) {
                v.numberValue.toLong().toString()
            } else {
                v.numberValue.toString()
            }
        }

        ProtoValue.KindCase.BOOL_VALUE -> {
            v.boolValue.toString()
        }

        else -> {
            ""
        }
    }

/**
 * 导出对话框 —— 选格式与路径，**编辑导出 SQL**，并预览结果。
 *
 * ## 为什么要从 `AlertDialog` 换成全屏对话框
 *
 * 原实现把格式单选、目录、文件名、目标表全塞进 `AlertDialog.text` 里，最下面
 * 再加一行「将执行：SELECT …」的**只读省略号小字**。现在要把**代码编辑器**和
 * **结果预览表**也放进去 —— `AlertDialog` 那点高度根本放不下，硬塞的结果是
 * 两个都缩成看不见。
 *
 * ## 三个设计决定
 *
 * 1. **起手 SQL = 屏幕上这张表**（过滤 + 排序 + 表内搜索），见
 *    [TablePreviewTab.buildExportSql]。旧实现恒为整张表。
 * 2. **SQL 可编辑，编辑器直接复用工作台的 [CodeEditorWithToolbar]** —— 于是
 *    高亮、关键字/库表字段补全、**格式化按钮**全部天然一致，
 *    不另造一个只会显示不能好好用的输入框。
 * 3. **预览只放行 SELECT / WITH**（[DangerousSql.isReadOnlyQuery]）——
 *    预览是一键发到数据库的动作，必须在发送**之前**就拦住写操作。
 *
 * ## 路径怎么选：系统对话框 + 手动输入，两条路都在
 *
 * 抄一段 `C:\Users\xxx\...` 出来既慢又容易打错，而引擎侧 `ExportRunRequest.output_dir`
 * 收的确实是**绝对路径**、引擎自己没得挑 —— 这个交互欠用户的只能在这里补。所以：
 *
 * - **「浏览…」** 调 [NativePathPicker]（`java.awt.FileDialog`，在 Windows 上就是
 *   资源管理器那套系统对话框；失败时退回 Swing 的目录选择器），一步把
 *   **目录 + 文件名**一起定下来；
 * - **两个文本框仍然可编辑**：网络路径、UNC、刚在别处复制来的路径，
 *   以及无头/CI 环境下压根弹不出系统对话框时，手动输入是唯一能走通的路。
 *   把选择器做成「只能选」反而会在这些场景下把功能整个堵死。
 *
 * @param catalog 当前库 —— 预览要发到它上面。
 * @param statementCount >1 时对话框会把 SQL 当脚本逐条导出（SQL 工作台的多语句入口）。
 */
@Composable
internal fun ExportDialog(
    state: DatabaseBrowserState,
    presetSql: String,
    presetTableName: String,
    catalog: String = "",
    statementCount: Int = 1,
    onConfirm: (DatabaseBrowserState.ExportFormat, String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(DatabaseBrowserState.ExportFormat.CSV) }
    var dir by remember { mutableStateOf(System.getProperty("user.dir") ?: "") }
    // 文件名给一个**合法**的默认：中文 + 空格 + 斜杠在文件系统与 SQL 里都有特殊含义，
    // 让用户在一个已经能跑的值上去改，比让他从一个空框开始猜格式稳妥
    var name by remember { mutableStateOf("export.${DatabaseBrowserState.ExportFormat.CSV.extension}") }
    var tableName by remember { mutableStateOf(presetTableName) }
    // 可编辑的导出 SQL。起手值 = 调用方拼好的（表预览 = 带上过滤与排序的整条 SELECT）。
    var sql by remember(presetSql) { mutableStateOf(presetSql) }
    // 换格式时同步扩展名 —— 否则用户选「Excel」却导出成 export.csv，
    // 打开时才发现格式不对，那比多一个下拉糟糕得多
    var lastFormat by remember { mutableStateOf(format) }
    if (format != lastFormat) {
        lastFormat = format
        name = "export.${format.extension}"
    }
    // 只有选 SQL INSERT 才问目标表；其余格式的「目标表」是参数校验里的硬要求
    val needsTable = format == DatabaseBrowserState.ExportFormat.SQL_INSERT
    val canSubmit =
        dir.isNotBlank() && name.isNotBlank() &&
            (!needsTable || tableName.isNotBlank()) && sql.isNotBlank()
    // 选择器是**阻塞**的模态框，绝不能在重组过程中直接调；
    // 也不该让用户等着期间界面是死的 —— 交给 IO 线程，点完再回主线程写回状态
    val pickPath: (Boolean) -> Unit = { wholeFile ->
        scope.launch {
            val picked =
                if (wholeFile) {
                    NativePathPicker.pickFile(dir, name)
                } else {
                    NativePathPicker.pickDirectory(dir, name)
                } ?: return@launch
            dir = picked.dir
            if (picked.name.isNotBlank()) name = picked.name
        }
    }

    // ---- 预览状态 ----
    // 只放行 SELECT / WITH：预览是一键发到数据库的动作，写操作必须在**发送之前**
    // 就被挡住，不能发出去再说（判据见 DangerousSql.isReadOnlyQuery）。
    val previewable = DangerousSql.isReadOnlyQuery(sql)
    var preview by remember { mutableStateOf<SqlPreviewRows?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    // 预览代次：SQL 一改，上一次的结果立刻作废 —— 否则用户看到的表格
    // 属于上一条语句，而对话框里摆着新写的 SQL，两者对不上却没有任何提示。
    var previewGen by remember { mutableStateOf(0) }

    fun invalidatePreview() {
        previewGen++
        preview = null
        previewError = null
    }
    val runPreview: () -> Unit = {
        val snapshot = sql
        val gen = ++previewGen
        previewing = true
        previewError = null
        scope.launch {
            val result = state.previewExportSql(snapshot, catalog)
            if (gen != previewGen) return@launch // 迟到的响应直接丢
            result
                .onSuccess {
                    preview = it
                    previewError = null
                }.onFailure {
                    preview = null
                    previewError = it.message ?: Msg.text(StrKey.PREVIEW_FAILED)
                }
            previewing = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.92f)
                    .testTag(EXPORT_DIALOG_TAG),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ---- 标题行 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = str(StrKey.EXPORT_RESULT),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (statementCount > 1) {
                        Text(
                            text =
                                plural(
                                    PluralStrKey.EXPORT_STATEMENTS_TO_FILES,
                                    statementCount,
                                    statementCount,
                                    statementCount,
                                ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                ) {
                    // ---- 格式 ----
                    Text(str(StrKey.FORMAT), style = MaterialTheme.typography.labelMedium)
                    // 横向排布：竖排五个单选会把对话框撑到需要滚动才看得到路径输入框
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DatabaseBrowserState.ExportFormat.entries.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = format == f, onClick = { format = f })
                                Text(
                                    str(f.labelKey),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("exportFormat_${f.name}"),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // ---- 输出目录 / 文件名 ----
                    // 文本框 + 系统选择器**并排**：手动输入在网络路径与无头环境下
                    // 是唯一走得通的路，把它拿掉等于把功能堵死。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = dir,
                            onValueChange = { dir = it },
                            label = { Text(str(StrKey.OUTPUT_DIR_ABSOLUTE)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag(EXPORT_DIR_FIELD_TAG),
                        )
                        Spacer(Modifier.width(8.dp))
                        WinOutlinedButton(
                            onClick = { pickPath(false) },
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(EXPORT_BROWSE_DIR_BTN_TAG),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(str(StrKey.BROWSE))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(str(StrKey.FILE_NAME)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag(EXPORT_NAME_FIELD_TAG),
                        )
                        Spacer(Modifier.width(8.dp))
                        // 一步把目录 + 文件名一起定下来 —— 最常用的一条路
                        WinOutlinedButton(
                            onClick = { pickPath(true) },
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(EXPORT_BROWSE_FILE_BTN_TAG),
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(str(StrKey.CHOOSE_FILE))
                        }
                    }
                    if (needsTable) {
                        OutlinedTextField(
                            value = tableName,
                            onValueChange = { tableName = it },
                            label = { Text(str(StrKey.TARGET_TABLE_NAME)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(EXPORT_TABLE_FIELD_TAG),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // ---- SQL（可编辑）----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(StrKey.EXPORT_SQL_EDITABLE), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = str(StrKey.EXPORT_SQL_PREFILLED_HINT),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 直接复用工作台的编辑器：高亮 / 库表字段补全 / **格式化**按钮
                    // 全部天然一致，不另造一个只会显示不能好好用的输入框。
                    //
                    // ⚠️ maxLines 刻意压到 7：起手 SQL 通常是一两行，编辑器再高
                    // 就把下面的**预览区**顶出可视范围 —— 用户打开弹窗看不到「预览」按钮，
                    // 而「先看看会导出什么」恰恰是这个弹窗最该先做的事。
                    // 需要写长 SQL 时它自己内部滚动，不影响可用性。
                    CodeEditorWithToolbar(
                        text = sql,
                        onTextChange = {
                            sql = it
                            invalidatePreview()
                        },
                        languageId = state.sqlDialectProfile().languageId,
                        editorState = rememberCodeEditorState(sql),
                        showLanguageSwitcher = false,
                        minLines = 3,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth().testTag(EXPORT_SQL_EDITOR_TAG),
                    )

                    // ---- 预览 ----
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(StrKey.PREVIEW), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        WinButton(
                            onClick = runPreview,
                            enabled = previewable && !previewing,
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(EXPORT_PREVIEW_BTN_TAG),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (previewing) str(StrKey.PREVIEWING) else str(StrKey.PREVIEW_FIRST_100_ROWS))
                        }
                        if (!previewable) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = str(StrKey.PREVIEW_SELECT_ONLY_HINT),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    when {
                        previewError != null -> {
                            Text(
                                text = previewError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        preview == null -> {
                            Text(
                                text = str(StrKey.PREVIEW_EMPTY_HINT),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 结果表自己内部滚动（heightIn），不再撑高外层内容区 ——
                        // 否则一屏 100 行会把「开始导出」推到很远的下方
                        else -> {
                            SqlPreviewTable(preview!!)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    WinButton(onClick = onDismiss, shape = SundaysPalette.buttonShape) { Text(str(StrKey.CANCEL)) }
                    Spacer(Modifier.width(8.dp))
                    WinButton(
                        onClick = { onConfirm(format, sql, dir, name, tableName) },
                        enabled = canSubmit,
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(EXPORT_CONFIRM_BTN_TAG),
                    ) { Text(str(StrKey.START_EXPORT)) }
                }
            }
        }
    }
}

/** 导出对话框整体的 UI 测试 tag —— 内容区可滚，测试要量它得有个固定锚点。 */
private const val EXPORT_DIALOG_TAG = "exportDialog"

/**
 * 预览结果的只读表格。
 *
 * **刻意不复用 `DataTable`** —— 那张表带分页 / 单元格编辑 / 行选中，
 * 每一项在「预览」这个场景下都是多余的，而且 [DataTable] 的行 id 依赖主键，
 * 而任意 SELECT 的结果集未必有主键。
 */
@Composable
private fun SqlPreviewTable(preview: SqlPreviewRows) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            preview.columns.forEach { col ->
                Text(
                    text = col,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (preview.rows.isEmpty()) {
            Text(
                text = str(StrKey.ZERO_ROWS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            preview.rows.forEach { row ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    preview.columns.forEach { col ->
                        Text(
                            text = row[col].orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Text(
            text =
                plural(PluralStrKey.ROWS_COUNT, preview.rows.size, preview.rows.size) +
                    if (preview.truncated) {
                        plural(PluralStrKey.ROWS_TRUNCATED, preview.rows.size, preview.rows.size)
                    } else {
                        ""
                    },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/**
 * 导出进行中的进度弹窗。
 *
 * ## 为什么是弹窗而不是工具条上的一行小字
 *
 * 导出可能跑好几分钟，期间用户完全不知道应用是**在忙**还是**卡死了**——
 * §9.13 那个缺陷之所以拖了这么久才被认真对待，正是因为界面上什么都不动。
 * 一个明确写着「正在导出」的模态窗，至少把「它在工作」这件事说清楚了。
 *
 * ## 为什么是**不确定**进度条
 *
 * 引擎只推「已导出 N 行」，没有总数（见 [DatabaseBrowserState.ExportProgress] 的说明）。
 * 拿表预览的行数凑一个百分比是**撒谎**：预览可能带过滤，导出的是整张表。
 * 不确定态 + 如实的行数，是这两者之间唯一诚实的组合。
 *
 * ## 为什么只能「后台运行」而不能「取消」
 *
 * 取消要一路传到引擎子进程（`EXPORT.STOP_EXPORT`），而那条链路正是当前最不可靠的一段。
 * 与其摆一个按了没反应的按钮，不如给一个**一定做得到**的动作：
 * 关掉窗口，导出继续，结果照样写进预览顶部的提示行。
 */
@Composable
internal fun ExportProgressDialog(
    fileName: String,
    outputDir: String,
    rowsWritten: Long,
    onBackground: () -> Unit,
) {
    AlertDialog(
        // 「关掉」只是藏起窗口，导出继续 —— 不能让用户以为点 X 就取消了导出
        onDismissRequest = onBackground,
        title = { Text(str(StrKey.EXPORTING)) },
        text = {
            Column {
                Text(
                    text = "$fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = outputDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                // 不确定态：引擎不给总数（见 KDoc）
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag(EXPORT_PROGRESS_DIALOG_TAG),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // 还没收到第一帧时显示「正在连接…」而不是「0 行」——
                    // 「0 行」会被读成「导出失败」，那正是我们要避免的误读
                    text =
                        if (rowsWritten > 0) {
                            plural(PluralStrKey.EXPORTED_ROWS_PROGRESS, rowsWritten.toInt(), rowsWritten)
                        } else {
                            str(StrKey.CONNECTING_ENGINE)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(EXPORT_PROGRESS_ROWS_TAG),
                )
            }
        },
        confirmButton = {
            WinButton(
                onClick = onBackground,
                shape = SundaysPalette.buttonShape,
                modifier = Modifier.testTag(EXPORT_PROGRESS_BACKGROUND_BTN_TAG),
            ) { Text(str(StrKey.RUN_IN_BACKGROUND)) }
        },
    )
}

internal const val EXPORT_DIR_FIELD_TAG = "exportDirField"

internal const val EXPORT_NAME_FIELD_TAG = "exportNameField"

private const val EXPORT_TABLE_FIELD_TAG = "exportTableField"

/** 导出对话框里的「浏览」（系统目录选择器）。 */
private const val EXPORT_BROWSE_DIR_BTN_TAG = "exportBrowseDirBtn"

/** 导出对话框里的「选文件」（系统文件选择器，目录 + 文件名一起定）。 */
private const val EXPORT_BROWSE_FILE_BTN_TAG = "exportBrowseFileBtn"

/** 导出进行中的进度弹窗。 */
private const val EXPORT_PROGRESS_DIALOG_TAG = "exportProgressDialog"

/** 进度弹窗里的行数读数。 */
private const val EXPORT_PROGRESS_ROWS_TAG = "exportProgressRows"

/** 进度弹窗里的「后台运行」按钮。 */
private const val EXPORT_PROGRESS_BACKGROUND_BTN_TAG = "exportProgressBackgroundBtn"
