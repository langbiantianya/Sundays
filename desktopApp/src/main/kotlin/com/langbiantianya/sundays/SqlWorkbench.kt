/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// **SQL 工作台** —— 多语句编辑、执行、事务开关、结果区、标签重命名。

package com.langbiantianya.sundays

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorState
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.editor.ui.rememberCodeEditorState
import com.langbiantianya.sundays.settings.formatBytes
import com.langbiantianya.sundays.sql.DangerousSql
import com.langbiantianya.sundays.sql.DangerousSqlConfirmDialog
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.TabRenameDialog
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinIconButton
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
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

/** 「事务」按钮的 UI 测试 tag —— 见 [DatabaseBrowserState.beginTransaction]。 */
private const val SQL_TRANSACTION_BTN_TAG = "sqlTransactionBtn"

/**
 * SQL 结果集在内存里最多攒多少行 —— 超出即停止累积并标 `rowsTruncated`。
 *
 * ## 为什么必须有上限
 *
 * `SQL.EXECUTE` 是**流式**的，引擎会一直推 `sql_row_frame`。此前前端无条件
 * `frames += resp.sqlRowFrame`，于是 `SELECT * FROM big_table` 会把整个结果集
 * 搬进堆里 —— 一张千万行的表就能把桌面进程拖垮，而用户能做的只有看着进度条
 * 慢慢爬。**引擎侧有 `Statement.cancel()` 可用，但前端当时连 request id 都没留下**，
 * 既不能停也不能少收。
 *
 * ## 为什么是 5000
 *
 * 桌面工具的默认结果窗口通常是几百到几千行。5000 行按每行 5 列估算约几 MB，
 * 远低于让 JVM 频繁 GC 的量级；再往上（比如 10 万）用户在表格里翻不到底，
 * 留着也只是在占内存 —— 真要全量数据，用户该用导出而不是结果面板。
 *
 * 截断时**必须**显式告知（`SqlResultArea` 的提示条）：静默截断等于骗人 ——
 * 用户会以为这张表就这么大。
 */
internal const val SQL_RESULT_MAX_ROWS = 5000

/** SQL 工作台「只读」开关的 UI 测试 tag。 */
internal const val SQL_READONLY_CHIP_TAG = "sqlReadonlyChip"

/**
 * SQL 工作台 —— 顶部信息条 + SQL sheet 标签条 + 编辑器（60%） + 结果面板（40%）。
 *
 * 与 [GenerateWorkbenchPane] 同构：**多 sheet**，每个 sheet 的 [CodeEditorState] 与执行结果都由
 * 状态机持有（[DatabaseBrowserState.sqlSheets]），因此切到表预览 / 切到别的 sheet 再切回来时，
 * 文本 / 光标 / 滚动位置 / 结果都保持不变。「执行 SQL」只作用于 [DatabaseBrowserState.currentSqlSheet]。
 *
 * 编辑器用 [CodeEditorWithToolbar]（`:shared` 的 `commonMain/.../editor/ui/CodeEditor.kt`）：
 * `languageId` = **当前连接方言的高亮档位**（[SqlDialectProfile.languageId]，见
 * [DatabaseBrowserState.sqlDialectProfile]）—— 换库即换关键字 / 类型 / 内置函数词表；
 * 隐藏语言切换器（本工作台只处理 SQL），内置「格式化」按钮走注册表里同档位的 SQL formatter，
 * 「执行 SQL」经 `actions` 插槽注入。SQL 语言与 formatter 由 app 启动时的
 * `registerBuiltinEditors()` 一次性注册全部档位（幂等）。
 *
 * 结果面板渲染**当前 sheet** 的四种态：
 * - `running` → 行内 spinner
 * - `error` → 错误文案
 * - `affectedRows != null`（非 SELECT）→ 「已影响 N 行」
 * - 有 `rows` → [DataTable]
 * - 否则 → 「执行 SQL 后在此查看结果」占位
 */
@Composable
internal fun SqlWorkbenchPane(
    state: DatabaseBrowserState,
    connected: Boolean,
    schema: String,
    dialect: SqlDialectProfile,
    modifier: Modifier = Modifier,
) {
    // 补全候选：库 / 表 / 字段，全部来自已有状态（见 [DatabaseBrowserState.sqlSchemaCompletions]）。
    // 缓存签名见 [schemaCompletionSignature] —— 它刻意不含 `tab.rows`。
    val schemaCompletions =
        remember(
            state.databases,
            state.tablesByDatabase,
            schemaCompletionSignature(state.tabs),
        ) {
            state.sqlSchemaCompletions()
        }

    Column(modifier = modifier) {
        // 顶部信息条：当前执行的 catalog —— 说明 SQL 会落到哪个库
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = str(StrKey.SQL_WORKBENCH),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                // 当前生效的高亮档位（由连接方言决定）—— 让「关键字随库变」在界面上可见
                Text(
                    text = dialect.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (schema.isNotBlank()) str(StrKey.SCHEMA_LABEL, schema) else str(StrKey.DEFAULT_CATALOG),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        WorkbenchTabStrip(
            leadingLabel = "SQL",
            titles = state.sqlSheets.map { it.title },
            selectedIndex = state.selectedSqlIndex,
            addDescription = str(StrKey.NEW_SQL_SHEET),
            renameDescription = str(StrKey.RENAME_CURRENT_TAB),
            removeDescription = str(StrKey.CLOSE_SQL_SHEET),
            onSelect = state::selectSqlSheet,
            onAdd = state::addSqlSheet,
            onRemove = state::removeSqlSheet,
            onRename = state::renameSqlSheet,
        )
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val sheet = state.currentSqlSheet()
        // 事务 / 多语句开关的点击都要发引擎请求，用组合作用域发起
        val actionScope = rememberCoroutineScope()
        // 待确认的危险操作。**非 null 时弹确认框，不执行**——
        // 判据在 [DangerousSql]，与只读模式（[DatabaseBrowserState.readOnly]）是两道独立的防线
        var pendingDangerous by remember { mutableStateOf<List<DangerousSql.Finding>?>(null) }
        if (pendingDangerous != null && sheet != null) {
            DangerousSqlConfirmDialog(
                findings = pendingDangerous!!,
                onConfirm = {
                    pendingDangerous = null
                    state.executeSql()
                },
                onDismiss = { pendingDangerous = null },
            )
        }

        // ---- 导出（与表预览共用同一个对话框，见 [ExportDialog]）----
        // 待导出的语句列表。null = 没点导出；空列表 = 点了但一条有效语句都没有。
        var exportStatements by remember { mutableStateOf<List<String>?>(null) }
        // 本次发起的任务 id —— 进度弹窗只跟踪它们（别人的任务不该显示成我的）
        var trackedTaskIds by remember { mutableStateOf<List<String>>(emptyList()) }
        exportStatements?.let { statements ->
            // ⚠️ **必须用 `";\n"` 拼，不能用 `"\n"`** ——
            // [com.langbiantianya.engine.SqlScriptSplitter] 是按**分号**切语句的。
            // 用换行拼出来的起手 SQL 在弹窗里再切一次时切不开，会被当成**一条**语句
            // 整段发给引擎，于是报一句莫名其妙的语法错：
            //     near 'SELECT id FROM xxx' at line 2
            // （真窗口实测抓到，标题写着「2 条语句」而实际按 1 条跑，两个信息还互相矛盾。）
            val joined = statements.joinToString(";\n")
            ExportDialog(
                state = state,
                presetSql = joined,
                // 多语句时不给「目标表」预设：逐条导出时每条自己带 WHERE / 列，
                // 同一个目标表名多半是错的，让用户自己填
                presetTableName = if (statements.size == 1) "" else "",
                catalog = schema,
                statementCount = statements.size,
                onDismiss = { exportStatements = null },
                onConfirm = { fmt, sql, dir, name, tbl ->
                    // 编辑器里改过的 SQL 优先于点按钮时那份快照 ——
                    // 否则用户在对话框里改了 SQL，导出的却是旧的
                    val stmts = exportStatementsFor(if (sql.isNotBlank()) sql else joined)
                    exportStatements = null
                    if (stmts.isNotEmpty()) {
                        // 每条语句一个任务（见 [DatabaseBrowserState.startExportBatch]）——
                        // 面板要能回答「哪一条失败了」，合成一条就答不了。
                        trackedTaskIds =
                            state.startExportBatch(
                                statements = stmts,
                                format = fmt,
                                outputDir = dir,
                                baseFileName = name,
                                tableName = tbl,
                            ) { results ->
                                val ok = results.mapNotNull { it.getOrNull() }
                                val failed = results.filter { it.isFailure }
                                sheet?.let { s ->
                                    s.error =
                                        when {
                                            failed.isEmpty() -> {
                                                null
                                            }

                                            // 只报第一条失败的原文，其余只带计数 —— 一屏塞十条
                                            // 异常既读不下去，也会把「哪条错了」淹没掉
                                            // 这里在协程里（导出已开跑），不是组合上下文，走 Msg
                                            else -> {
                                                Msg.plural(
                                                    PluralStrKey.EXPORT_FAILED_STATEMENTS,
                                                    failed.size,
                                                    failed.size,
                                                    stmts.size,
                                                ) +
                                                    failed.first().exceptionOrNull()?.message
                                            }
                                        }
                                    if (ok.isNotEmpty()) {
                                        s.notice =
                                            buildString {
                                                append(Msg.plural(PluralStrKey.EXPORTED_FILES_COUNT, ok.size, ok.size))
                                                if (stmts.size > 1) {
                                                    append(Msg.plural(PluralStrKey.STATEMENTS_TOTAL, stmts.size, stmts.size))
                                                }
                                                append("：")
                                                append(
                                                    ok.joinToString("；") {
                                                        it.file.substringAfterLast('\\').substringAfterLast('/')
                                                    },
                                                )
                                            }
                                        // 「部分成功」也算失败色：只要有语句没导出来，
                                        // 用户就该看见这一条不是纯成功播报。判定依据是
                                        // `failed` 集合本身，不是那句文案长什么样。
                                        s.noticeIsError = failed.isNotEmpty()
                                    }
                                }
                            }
                    }
                },
            )
        }
        // 进度弹窗跟踪本次发起的全部任务；任一条还在跑就显示。
        // 「后台运行」只把弹窗收起来 —— 任务在 [DatabaseBrowserState.backgroundTasks] 里继续，
        // 工具栏的「任务 (N)」入口能看到（见 [BrowserToolBar]）。
        val trackedTasks =
            trackedTaskIds.mapNotNull { id ->
                state.backgroundTasks.firstOrNull { it.id == id }
            }
        val anyRunning = trackedTasks.any { it.isRunning }
        if (anyRunning) {
            ExportProgressDialog(
                fileName =
                    if (trackedTasks.size > 1) {
                        plural(
                            PluralStrKey.STATEMENTS_PROGRESS,
                            trackedTasks.size,
                            trackedTasks.count { it.isRunning },
                            trackedTasks.size,
                        )
                    } else {
                        trackedTasks.firstOrNull()?.title ?: "export"
                    },
                outputDir = trackedTasks.firstOrNull()?.subtitle.orEmpty(),
                rowsWritten = trackedTasks.firstOrNull { it.isRunning }?.progress ?: 0L,
                onBackground = { trackedTaskIds = emptyList() },
            )
        }
        // 上编辑器 + 下结果（fillMaxHeight 60% / 40% 通过 weight 分配）
        Column(modifier = Modifier.fillMaxSize()) {
            if (sheet == null) {
                EmptyHint(
                    title = str(StrKey.NO_EDITABLE_SQL_SHEET),
                    description = str(StrKey.NO_EDITABLE_SQL_SHEET_HINT),
                    // 用 weight 而非 fillMaxSize —— 与下方 weighted 子项共存时不会互相挤掉
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
            } else {
                CodeEditorWithToolbar(
                    text = sheet.editor.text,
                    editorState = sheet.editor,
                    onTextChange = { sheet.editor.setText(it) },
                    // 方言档位即语言 id：高亮与「格式化」都走同一档位（见 SqlDialectProfile）
                    languageId = dialect.languageId,
                    // 本工作台只处理 SQL —— 不暴露语言切换器
                    showLanguageSwitcher = false,
                    // 注入当前连接的库 / 表 / 字段（**零额外请求**，见 sqlSchemaCompletions）。
                    // 关键字由上面的 `languageId` 按方言档位自动带来，无需重复注入。
                    // 忽略大小写：标识符大小写行为因方言而异，敲 `FROM USERS` 也该能补出 `users`
                    extraCompletions = schemaCompletions,
                    extraCompletionsCaseSensitive = false,
                    actions = {
                        // 只读模式开关 —— 摆最左，因为它决定后面所有按钮按下去会发生什么
                        FilterChip(
                            selected = state.readOnly,
                            onClick = { state.readOnly = !state.readOnly },
                            enabled = !sheet.running,
                            label = { Text(str(StrKey.READ_ONLY), maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag(SQL_READONLY_CHIP_TAG),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 多语句开关 —— 放在最左侧，因为它改的是**执行语义**，
                        // 而执行 / 停止 / 事务都排在它后面，视觉上是「先定模式再操作」。
                        //
                        // 做成逐 sheet 的开关而不是全局默认开：多语句意味着
                        // 「一次点执行会改多张表」，用户得能对某个脚本明确表态。
                        // 用 [FilterChip] 而不是复选框：它自己就是「选中态 + 可禁用」
                        // 的两态控件，与旁边的 WinButton 视觉重量一致。
                        FilterChip(
                            selected = sheet.multiStatement,
                            onClick = { sheet.multiStatement = !sheet.multiStatement },
                            enabled = !sheet.running,
                            label = { Text(str(StrKey.MULTI_STATEMENT), maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag(SQL_MULTI_STATEMENT_CHIP_TAG),
                        )
                        Spacer(Modifier.width(4.dp))
                        // 事务按钮组 —— 在执行 / 停止之前。
                        // 开着事务时「提交 / 回滚」必须一眼可见：数据没落盘这件事
                        // 靠状态栏那行小字提醒是不够的，用户关窗口就全丢了。
                        if (sheet.transactionSessionId != null) {
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.commitTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                shape = SundaysPalette.buttonShape,
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(str(StrKey.COMMIT))
                            }
                            Spacer(Modifier.width(4.dp))
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.rollbackTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                shape = SundaysPalette.buttonShape,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(str(StrKey.ROLLBACK))
                            }
                            Spacer(Modifier.width(4.dp))
                        } else {
                            WinButton(
                                onClick = {
                                    actionScope.launch {
                                        val err = state.beginTransaction(sheet)
                                        if (err != null) sheet.error = err
                                    }
                                },
                                enabled = connected && !sheet.running,
                                shape = SundaysPalette.buttonShape,
                                modifier = Modifier.testTag(SQL_TRANSACTION_BTN_TAG),
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(str(StrKey.TRANSACTION))
                            }
                            Spacer(Modifier.width(4.dp))
                        }

                        // 执行中：主按钮让位给「停止」。
                        //
                        // 两个按钮**互斥**而不是并存 —— 执行期间 `enabled` 已是 false，
                        // 摆在一起只会让用户去点一个按不动的按钮。「停止」单独占位还顺带
                        // 避免了「点错了以为是执行」的重灾区。
                        if (sheet.running && sheet.runningRequestId != null) {
                            WinButton(
                                onClick = { state.cancelSql() },
                                shape = SundaysPalette.buttonShape,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(str(StrKey.STOP))
                            }
                        } else {
                            WinButton(
                                onClick = {
                                    // 危险操作要**先确认再发**：判据是 [DangerousSql]，
                                    // 拦在引擎之前 —— 用户看到确认框时，数据库还什么都没发生。
                                    val findings = DangerousSql.scan(sheet.editor.text)
                                    if (findings.isEmpty()) {
                                        state.executeSql()
                                    } else {
                                        pendingDangerous = findings
                                    }
                                },
                                enabled = connected && !sheet.running && sheet.editor.text.isNotBlank(),
                                shape = SundaysPalette.buttonShape,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (sheet.running) str(StrKey.EXECUTING) else str(StrKey.EXECUTE_SQL))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        // 导出：把**当前 sheet 的 SQL** 原样送进导出对话框。
                        //
                        // 多语句在这里是**逐条导出成多个文件**（见 [exportStatements]）——
                        // 不是把整段脚本塞给一次导出：一段脚本里若有两条 SELECT，
                        // 一次导出只能拿到最后一组结果集，前一条的结果会被丢掉。
                        // 逐条导出才符合「每条查询各自一份结果」的直觉。
                        WinButton(
                            onClick = { exportStatements = exportStatementsFor(sheet.editor.text) },
                            enabled = connected && !sheet.running && sheet.editor.text.isNotBlank(),
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(SQL_EXPORT_BTN_TAG),
                        ) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(str(StrKey.EXPORT))
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(0.6f),
                )
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SqlResultArea(
                sheet = sheet,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(0.4f),
            )
        }
    }
}

/**
 * 工作台标签条 —— SQL sheet 与造数脚本共用（两者行为完全一致，只有文案不同）。
 *
 * 三个能力：
 * 1. **横向滚动**：`SecondaryScrollableTabRow` 自带拖拽滚动 + 选中项自动滚入可视区；
 *    另外挂 [verticalWheelScrollsHorizontally] 让普通鼠标的**纵向滚轮**也能滚动标签条
 *    （Compose 的 `horizontalScroll` 只吃横向滚轮分量，见该函数的 KDoc）。
 * 2. **重命名**：右侧「✎」为**当前选中标签**弹出 [TabRenameDialog]（确定提交 / 取消放弃）；
 *    空名保持原名（由 [DatabaseBrowserState.renameSqlSheet] / [renameGenerateScript] 兜底）。
 * 3. **增删**：右侧「＋」调 [onAdd]；每个标签的「×」调 [onRemove]（只剩一个标签时不渲染「×」，
 *    与预览标签页/造数脚本的既有策略一致）。
 *
 * > **为什么重命名是「按钮 + 弹窗」而不是双击内联编辑**：
 * > 1. 双击不可行 —— `Tab` 内部把 `modifier.selectable(...)` 挂在同一节点上，`Clickable`
 * >    会在 Main pass 消费 down（`handleDownEvent` → `down.consume()`），外层再挂
 * >    `detectTapGestures(onDoubleTap)` 需要**未被消费的 down**，因此永远收不到手势；挂到内容里
 * >    又会抢走 `selectable` 需要的 down（双击成了就单击失灵）。想保留双击语义只能自绘标签
 * >    （连带失去 Material 指示条）。
 * > 2. 内联编辑会**卡死画面** —— `Tab` 的文本槽位于 `SecondaryScrollableTabRow` 的
 * >    SubcomposeLayout 内，把一个自己抢焦点的 `BasicTextField` 塞进去，Compose 场景会永远
 * >    有下一帧要渲染（`SkikoComposeUiTest.waitForIdle()` 实测永不返回，真机表现为界面卡死）。
 * >    弹窗把编辑面与标签条的测量 / 焦点链路彻底解耦。
 *
 * @param leadingLabel 标签条前的固定小标题（「SQL」/「脚本」）
 * @param titles 各标签的显示名（顺序即标签条顺序）
 * @param selectedIndex 当前选中下标（越界时夹到合法范围）
 * @param addDescription 「＋」的无障碍描述，同时是 UI 测试的锚点
 * @param renameDescription 「✎」的无障碍描述
 * @param removeDescription 「×」的无障碍描述
 * @param onRename 提交重命名（index, 新名字）—— 仅在名字真正变化时回调
 */
@Composable
internal fun WorkbenchTabStrip(
    leadingLabel: String,
    titles: List<String>,
    selectedIndex: Int,
    addDescription: String,
    renameDescription: String,
    removeDescription: String,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 正在重命名的标签下标（null = 无）—— 纯视图态，不进状态机
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.width(4.dp))
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0)),
            scrollState = scrollState,
            edgePadding = 0.dp,
            containerColor = tabStripContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            modifier =
                Modifier
                    .weight(1f)
                    .verticalWheelScrollsHorizontally(scrollState),
        ) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                maxLines = 1,
                            )
                            if (titles.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = removeDescription,
                                    modifier =
                                        Modifier
                                            .size(16.dp)
                                            .clickable { onRemove(index) },
                                )
                            }
                        }
                    },
                )
            }
        }
        WinIconButton(
            onClick = { renameTarget = selectedIndex.takeIf { it in titles.indices } },
            enabled = titles.isNotEmpty() && selectedIndex in titles.indices,
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = renameDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        WinIconButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = addDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // 重命名弹窗 —— 与标签条解耦：不在 Tab（SubcomposeLayout）内部放可编辑控件
    val target = renameTarget
    val targetTitle = target?.let { titles.getOrNull(it) }
    if (target != null && targetTitle != null) {
        TabRenameDialog(
            initialTitle = targetTitle,
            onConfirm = { newTitle ->
                renameTarget = null
                if (newTitle != targetTitle) onRename(target, newTitle)
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** 滚轮一格（`scrollDelta` 一个单位）对应横向滚动的像素数 —— 约等于一个标签宽（`minTabWidth` 90.dp）。 */
private const val TAB_WHEEL_SCROLL_STEP_PX = 96f

/**
 * Modifier 扩展 —— 把鼠标**纵向**滚轮转发成自己的横向滚动。
 *
 * Compose 的 `horizontalScroll` 走 `Offset.toSingleAxisDeltaFromAngle()`：纵向滚轮分量在横向
 * 滚动容器上被判定为「另一个轴」而返回 0、**不消费**（见 foundation `Scrollable.kt` /
 * `MouseWheelScrollingLogic.kt`）。因此只有横向滚轮（触控板/倾斜滚轮）能滚动标签条，
 * 普通鼠标滚轮完全滚不动 —— 这里补上这一环。
 *
 * 约定：
 * - 只处理**未被消费**的 Scroll 事件（Main pass），因此触控板的横向分量仍优先交给
 *   `horizontalScroll` 自己处理，不会双重滚动；
 * - 处理后 `consume()`，避免同一个滚轮事件再去滚动外层容器；
 * - 纵向增量为 0（横向滚轮）时不拦截。
 */
private fun Modifier.verticalWheelScrollsHorizontally(state: ScrollState): Modifier =
    this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val dy = change.scrollDelta.y
                if (dy == 0f) continue
                change.consume()
                state.dispatchRawDelta(dy * TAB_WHEEL_SCROLL_STEP_PX)
            }
        }
    }

/**
 * SQL 工作台结果面板 —— 渲染**当前 sheet** 的 [DatabaseBrowserState.SqlSheet] 四类结果字段之一为有效值者。
 * 无 sheet（用户删光了）时展示空态。
 */
@Composable
private fun SqlResultArea(
    sheet: DatabaseBrowserState.SqlSheet?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        // 导出提示条：与表预览的 [TablePreviewTab.notice] 同思路 ——
        // 导出成功不该被下一次 SELECT 前的清空抹掉，也不该把结果区顶成「执行失败」。
        // 它排在所有结果分支**之前**：导出成功后紧接着看结果的用户，
        // 第一眼要看到的是「文件已经写到哪儿了」。
        if (sheet?.notice != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            // 失败时整条染错误色 —— 由 [SqlSheet.noticeIsError] 决定，
                            // **不再**靠 `notice.startsWith("导出失败")` 那种中文前缀判定：
                            // 一旦界面语言不是中文，那个判断恒为 false，所有失败提示都会
                            // 静默变回成功色。这是从数据模型上修，不是从文案上绕。
                            if (sheet.noticeIsError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = sheet.notice!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            // ⚠️ 有导出结果时**不要**再显示「尚未执行 SQL」—— 那句话与刚出现的
            // 「已导出 2 个文件」并排，读起来像自相矛盾（导出明明已经发生过了）。
            // 此时只留顶部那行提示，不占满整块区域。
            sheet == null -> {
                EmptyHint(
                    title = str(StrKey.NO_SQL_EXECUTED),
                    description = str(StrKey.NO_SQL_EXECUTED_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            sheet.notice != null && sheet.error == null &&
                sheet.rows.isEmpty() && sheet.affectedRows == null && !sheet.running -> {
                // 占位：结果区空，但已经有话说清楚了（顶部那行）
            }

            sheet.running -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(str(StrKey.EXECUTING), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            sheet.error != null -> {
                EmptyHint(
                    title = str(StrKey.EXECUTE_FAILED),
                    description = sheet.error,
                    // 报错顶对齐、从上往下读
                    centerContent = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            sheet.affectedRows != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    Text(
                        // `affectedRows` 是 `by mutableStateOf` 的委托属性，编译器不给智能转换，
                        // 所以这里显式兜底 —— 外层 `affectedRows != null` 分支已经保证了它非空
                        text = plural(PluralStrKey.AFFECTED_ROWS, sheet.affectedRows ?: 0, sheet.affectedRows ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            sheet.rows.isNotEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = plural(PluralStrKey.QUERY_RESULT_ROWS, sheet.rowCount, sheet.rowCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 截断必须**显式**说出来。静默截断等于骗人：用户会以为这张表就这么大，
                        // 而导出去的数据却是全的。措辞说清「收到多少」而不是「一共多少」——
                        // 后者我们并不知道，引擎没有回总行数。
                        if (sheet.rowsTruncated) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = plural(PluralStrKey.TRUNCATED_MAX_ROWS, SQL_RESULT_MAX_ROWS, SQL_RESULT_MAX_ROWS),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DataTable(
                        columns = sheet.columns,
                        rows = sheet.rows,
                        modifier = Modifier.fillMaxSize(),
                        pageSize = sheet.resultPageSize,
                        onPageSizeChange = { sheet.resultPageSize = it },
                        currentPage = sheet.resultPage,
                        onPageChange = { sheet.resultPage = it },
                        selectedRowId = sheet.selectedRowId,
                        onSelectedRowChange = { sheet.selectedRowId = it?.id },
                        fillParentHeight = false,
                    )
                }
            }

            else -> {
                EmptyHint(
                    title = str(StrKey.NO_SQL_EXECUTED),
                    description = str(StrKey.NO_SQL_EXECUTED_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
