/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.connection.DialectMetadata
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinOutlinedButton
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.WinSurface
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.str
import kotlinx.coroutines.launch

/*
 * 「添加数据源」对话框 —— **分析模式专属**。
 *
 * ## 为什么**不共用**浏览模式的「添加连接」
 *
 * 那两条路加的是**两种东西**：
 *
 * | | 浏览模式的「添加连接」 | 分析模式的「添加数据源」 |
 * |---|---|---|
 * | 存哪儿 | `connection.json`（连接清单） | **工作区 DuckDB** 里的登记表 |
 * | 给谁用 | 浏览模式的对象树 | 分析模式的树 |
 * | 是什么 | 一条「怎么连」的配方 | 一条**已经接进 DuckDB** 的数据源 |
 *
 * 共用一份表单，会让用户以为「加在这儿 = 加在那儿」——
 * 而实际上是**两套会话、两份清单**（共用就会变成「两份可能对不上的真相」：
 * 同一个 MySQL 在两处各存一份，改了密码只改一处，另一处连不上）。
 *
 * ## 样式**跟着全局走**
 *
 * 用的是仓库那一套 Win 控件（[WinSurface] / [WinButton] / [WinTextField] /
 * [WinDivider]），而不是 Material3 的 `AlertDialog` ——
 * 后者在复古档里仍然是一块**现代圆角卡片**，和满屏的 Win 风格对不上。
 * 这与 [ExportDialog] 是同一套做法。
 */

/** 对话框的测试标记。 */
const val ANALYSIS_SOURCE_DIALOG_TAG: String = "analysisSourceDialog"

/** 「预览」按钮的测试标记。 */
const val ANALYSIS_SOURCE_PREVIEW_BUTTON_TAG: String = "analysisSourcePreview"

/** 「浏览…」按钮（系统文件 / 文件夹选择器）的测试标记。 */
const val ANALYSIS_SOURCE_BROWSE_BUTTON_TAG: String = "analysisSourceBrowse"

/** 「确定」按钮的测试标记。 */
const val ANALYSIS_SOURCE_CONFIRM_BUTTON_TAG: String = "analysisSourceConfirm"

/** 「名称」输入框的测试标记。 */
const val ANALYSIS_SOURCE_NAME_FIELD_TAG: String = "analysisSourceNameField"

/** 「路径」输入框的测试标记。 */
const val ANALYSIS_SOURCE_PATH_FIELD_TAG: String = "analysisSourcePathField"

/** 「主机」输入框的测试标记。 */
const val ANALYSIS_SOURCE_HOST_FIELD_TAG: String = "analysisSourceHostField"

/**
 * 「添加数据源」对话框。
 *
 * @param session 分析会话 —— 预览与保存都走它
 * @param dialects **引擎下发**的「可分析」方言（能力位筛过的）——
 *   不写死名单，否则引擎改了声明而前端没改，
 *   界面就会给出「点进去必然失败」的入口
 * @param onDismiss 关掉对话框
 */
@Composable
internal fun AnalysisSourceDialog(
    session: AnalysisSession,
    dialects: List<DialectType>,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(AnalysisSourceKind.REMOTE) }
    var name by remember { mutableStateOf("") }
    var dialect by remember { mutableStateOf(dialects.firstOrNull() ?: DialectType.MYSQL) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var database by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var format by remember { mutableStateOf<AnalysisFileFormat?>(null) }
    var preview by remember { mutableStateOf<List<AnalysisRemotePreview>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<AnalysisSelectedTable>>(emptySet()) }
    var previewing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val draft =
        AnalysisSourceDraft(
            name = name,
            kind = kind,
            dialect = dialect,
            jdbcUrl = jdbcUrlOf(dialect, host, port, database),
            database = database,
            user = user,
            password = password,
            path = path,
            format = format,
        )

    // ⚠️ 用底层 [Dialog] 而不是 Material3 的 `AlertDialog`：
    // `AlertDialog` 会把内容包在 `sizeIn(minWidth = 280.dp, maxWidth = 560.dp)`
    // 的容器里（`DefaultBasicAlertDialogOverride`），宽度上限**恒为 560dp**，
    // 而且它的容器是**现代圆角卡片** —— 复古档里满屏 Win 风格中间浮一块
    // Material 卡片，样式就断了。
    // 自己用 [WinSurface] 铺一块，形状、斜面、描边全跟着主题走 ✓。
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WinSurface(
            modifier =
                Modifier
                    .fillMaxWidth(0.92f)
                    .testTag(ANALYSIS_SOURCE_DIALOG_TAG),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ---- 标题行 ----
                Text(
                    text = str(StrKey.ANALYSIS_ADD_SOURCE),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 两类源 —— 用两个按钮而不是下拉框：
                    // 「远程」与「文件」是**两种完全不同的形状**（连接参数 vs 一个路径），
                    // 摆成两个按钮一眼就分得清；下拉框要多点一次才知道还有另一个。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WinOutlinedButton(
                            onClick = { kind = AnalysisSourceKind.REMOTE },
                            enabled = kind != AnalysisSourceKind.REMOTE,
                            shape = SundaysPalette.buttonShape,
                        ) {
                            Text(str(StrKey.ANALYSIS_SOURCE_KIND_REMOTE))
                        }
                        WinOutlinedButton(
                            onClick = { kind = AnalysisSourceKind.FILE },
                            enabled = kind != AnalysisSourceKind.FILE,
                            shape = SundaysPalette.buttonShape,
                        ) {
                            Text(str(StrKey.ANALYSIS_SOURCE_KIND_FILE))
                        }
                    }

                    WinTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(str(StrKey.NAME)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(ANALYSIS_SOURCE_NAME_FIELD_TAG),
                    )

                    if (kind == AnalysisSourceKind.REMOTE) {
                        RemoteSourceForm(
                            dialects = dialects,
                            dialect = dialect,
                            onDialect = { dialect = it },
                            host = host,
                            onHost = { host = it },
                            port = port,
                            onPort = { port = it },
                            database = database,
                            onDatabase = { database = it },
                            user = user,
                            onUser = { user = it },
                            password = password,
                            onPassword = { password = it },
                        )
                    } else {
                        // 路径：**文本框 + 系统选择器**并排。
                        //
                        // 文本框不能拿掉 —— 网络路径、UNC、刚在别处复制来的路径，
                        // 以及无头 / CI 环境里压根弹不出系统对话框时，
                        // 手动输入是唯一走得通的路。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WinTextField(
                                value = path,
                                onValueChange = {
                                    path = it
                                    // 换了路径，格式跟着变 —— 但**不覆盖**用户手选过的那个
                                    // （手选是更明确的意图）。
                                    if (format == null) format = AnalysisFileFormat.fromPath(it)
                                },
                                label = { Text(str(StrKey.ANALYSIS_SOURCE_PATH)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag(ANALYSIS_SOURCE_PATH_FIELD_TAG),
                            )
                            Spacer(Modifier.width(8.dp))
                            WinOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val picked = NativePathPicker.pickPath(path) ?: return@launch
                                        path = picked
                                        if (format == null) format = AnalysisFileFormat.fromPath(picked)
                                    }
                                },
                                shape = SundaysPalette.buttonShape,
                                modifier = Modifier.testTag(ANALYSIS_SOURCE_BROWSE_BUTTON_TAG),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(str(StrKey.BROWSE))
                            }
                        }
                        Text(
                            text = str(StrKey.ANALYSIS_SOURCE_FILE_HINT),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 格式**必须能手选** —— 文件夹没有扩展名，
                        // 自动推断不出来（「这个文件夹里是 csv 还是 parquet」）。
                        Text(
                            text = str(StrKey.FORMAT),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnalysisFileFormat.entries.forEach { candidate ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = format == candidate,
                                        onClick = { format = candidate },
                                    )
                                    Text(
                                        text = formatLabel(candidate),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    if (kind == AnalysisSourceKind.REMOTE) {
                        WinOutlinedButton(
                            onClick = {
                                error = null
                                previewing = true
                                scope.launch {
                                    try {
                                        preview = session.previewRemote(draft)
                                        selected = emptySet()
                                    } catch (e: Exception) {
                                        error = e.message
                                    } finally {
                                        previewing = false
                                    }
                                }
                            },
                            enabled = !previewing && host.isNotBlank(),
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(ANALYSIS_SOURCE_PREVIEW_BUTTON_TAG),
                        ) {
                            Text(str(if (previewing) StrKey.PREVIEWING else StrKey.PREVIEW))
                        }
                        if (previewing) {
                            WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        }
                        preview.forEach { group ->
                            PreviewGroup(
                                group = group,
                                selected = selected,
                                onToggle = { table ->
                                    selected =
                                        if (table in selected) {
                                            selected - table
                                        } else {
                                            selected + table
                                        }
                                },
                                onToggleAll = {
                                    val tables =
                                        group.tables.map { AnalysisSelectedTable(group.namespace, it) }
                                    selected =
                                        if (tables.all { it in selected }) {
                                            selected - tables.toSet()
                                        } else {
                                            selected + tables
                                        }
                                },
                            )
                        }
                    }

                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    WinTextButton(
                        onClick = onDismiss,
                        enabled = !saving,
                        shape = SundaysPalette.buttonShape,
                    ) {
                        Text(str(StrKey.CANCEL))
                    }
                    Spacer(Modifier.width(8.dp))
                    WinButton(
                        onClick = {
                            error = null
                            saving = true
                            scope.launch {
                                session.addSource(
                                    draft = draft,
                                    tables = selected.toList(),
                                    onProgress = { _: PullProgress -> },
                                    onFailure = { reason ->
                                        saving = false
                                        error = reason
                                    },
                                    onSuccess = {
                                        saving = false
                                        onDismiss()
                                    },
                                )
                            }
                        },
                        enabled =
                            !saving &&
                                name.isNotBlank() &&
                                (kind == AnalysisSourceKind.FILE || selected.isNotEmpty()),
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(ANALYSIS_SOURCE_CONFIRM_BUTTON_TAG),
                    ) {
                        Text(str(StrKey.ANALYSIS_ADD_SOURCE))
                    }
                }
            }
        }
    }
}

/**
 * 远程源的表单 —— 方言 / 主机 / 端口 / 库 / 用户 / 密码。
 *
 * ## 为什么**不**复用浏览模式的连接表单
 *
 * 那张表单是**方言自描述**的（每种方言要填什么，由引擎下发的规格决定），
 * 而且它管的是「怎么连」；这里管的是「**这是什么数据源**」——
 * 少一层「连接类型」（客户端 / 嵌入式 / 自定义 URL），因为分析模式的数据源
 * 只可能是**远程数据库**或**文件**。
 */
@Composable
private fun RemoteSourceForm(
    dialects: List<DialectType>,
    dialect: DialectType,
    onDialect: (DialectType) -> Unit,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    database: String,
    onDatabase: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dialects.forEach { candidate ->
                WinOutlinedButton(
                    onClick = { onDialect(candidate) },
                    enabled = candidate != dialect,
                    shape = SundaysPalette.buttonShape,
                ) {
                    Text(DialectMetadata.of(candidate).displayName)
                }
            }
        }
        WinTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(str(StrKey.HOST)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(ANALYSIS_SOURCE_HOST_FIELD_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WinTextField(
                value = port,
                onValueChange = onPort,
                label = { Text(str(StrKey.PORT)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            WinTextField(
                value = database,
                onValueChange = onDatabase,
                label = { Text(str(StrKey.DATABASE_NAME)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WinTextField(
                value = user,
                onValueChange = onUser,
                label = { Text(str(StrKey.USERNAME)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            WinTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text(str(StrKey.PASSWORD)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 预览里的一个库 + 它的表（**可勾选**）。
 *
 * ## 为什么能多选也能单选
 *
 * 用户要接的东西粒度不一样：有人只想看一张表，有人要一整个库。
 * 勾选框天生两者都支持 —— 而「全选」那一行让「整个库」只要点一下 ✓。
 */
@Composable
private fun PreviewGroup(
    group: AnalysisRemotePreview,
    selected: Set<AnalysisSelectedTable>,
    onToggle: (AnalysisSelectedTable) -> Unit,
    onToggleAll: () -> Unit,
) {
    val tables = group.tables.map { AnalysisSelectedTable(group.namespace, it) }
    val allSelected = tables.isNotEmpty() && tables.all { it in selected }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allSelected, onCheckedChange = { onToggleAll() })
            Text(
                text = group.namespace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = str(StrKey.ANALYSIS_SOURCE_SELECT_ALL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tables.forEach { table ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = table in selected,
                    onCheckedChange = { onToggle(table) },
                    modifier = Modifier.padding(start = 24.dp),
                )
                Text(
                    text = table.table,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (tables.isEmpty()) {
            Text(
                text = str(StrKey.PREVIEW_EMPTY_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

/**
 * 拼出远程源的 JDBC URL。
 *
 * ## 为什么按**方言**拼，而不是让用户自己填 URL
 *
 * 「自己填 URL」是浏览模式**高级**选项里的那条路 —— 那里用户已经知道
 * 自己要连什么。而这里用户填的是「主机 / 端口 / 库」这些**参数**；
 * URL 的形状是**方言的事**（`jdbc:mysql://` 与 `jdbc:postgresql://` 不同），
 * 让用户手拼 URL 就是把方言知识复制到用户脑子里。
 */
private fun jdbcUrlOf(
    dialect: DialectType,
    host: String,
    port: String,
    database: String,
): String {
    val prefix = DialectMetadata.of(dialect).jdbcUrlPrefix
    val hostPart = host.trim()
    // 空端口 / 空库名就**整段省掉** —— URL 后面挂个空的「:」或「/」，
    // 驱动那边认不出来，报错还指向一个莫名其妙的地方。
    val portPart = if (port.isBlank()) "" else ":${port.trim()}"
    val dbPart = if (database.isBlank()) "" else "/${database.trim()}"
    return "$prefix$hostPart$portPart$dbPart"
}

/** 文件格式的显示名 —— 复用导入那套（同一批格式，不另起一份名）。 */
@Composable
private fun formatLabel(format: AnalysisFileFormat): String =
    str(
        when (format) {
            AnalysisFileFormat.PARQUET -> StrKey.FORMAT_PARQUET
            AnalysisFileFormat.CSV -> StrKey.FORMAT_CSV
            AnalysisFileFormat.JSONL -> StrKey.FORMAT_JSON_LINES
            AnalysisFileFormat.EXCEL -> StrKey.FORMAT_EXCEL
        },
    )
