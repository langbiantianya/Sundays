/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// **造数工作台** —— 生成 Lua 造数脚本、执行、把返回的结果集铺成表格。

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.GenerateHelpers
import com.langbiantianya.sundays.editor.ui.CodeEditorState
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str

// ============================================================================
// 造数工作台 —— Lua 脚本编辑 + 造数结果
// ============================================================================

/** 造数工作台的 Lua 版本候选 —— 对应引擎 `DataGenerateRequest.lua_version`。 */
private val LUA_VERSIONS = listOf("luajit", "5.1", "5.2", "5.3", "5.4", "5.5")

/** 新建造数脚本的初始内容 —— 顺带把引擎提供的 random_* 辅助函数列出来当速查表。 */
internal const val GENERATE_SCRIPT_TEMPLATE = """-- 造数脚本：insert(表名, { 列 = 值, … }) 逐条写库
-- 辅助：random_int / random_float / random_string / random_name / random_email / random_phone
--      random_date / random_datetime / random_time / random_uuid / random_enum / lastId()
for i = 1, 100 do
  insert("your_table", {
    name  = random_name(),
    email = random_email(),
    age   = random_int(18, 60),
  })
end"""

/** 造数日志面板的 UI 测试 tag —— 测试要确认「日志区真的在那儿、真的有内容」。 */
internal const val GENERATE_LOG_PANEL_TAG = "generateLogPanel"

/** 造数日志列表的 UI 测试 tag —— 日志行是惰性组合的，只有列表本身才有稳定边界。 */
internal const val GENERATE_LOG_LIST_TAG = "generateLogList"

/**
 * 造数工作台 —— 上半脚本标签条 + Lua 版本 + 编辑器，下半造数结果。
 *
 * 与 [SqlWorkbenchPane] 同构：编辑器的 [CodeEditorState] 由状态机持有（[DatabaseBrowserState.generateScripts]），
 * 因此切到表预览再切回来时，脚本文本 / 光标 / 滚动位置都保持不变。
 */
@Composable
internal fun GenerateWorkbenchPane(
    state: DatabaseBrowserState,
    connected: Boolean,
    schema: String,
    modifier: Modifier = Modifier,
) {
    val script = state.currentGenerateScript()
    Column(modifier = modifier) {
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
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = str(StrKey.GENERATE_WORKBENCH),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
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
            leadingLabel = str(StrKey.SCRIPT),
            titles = state.generateScripts.map { it.title },
            selectedIndex = state.selectedGenerateIndex,
            addDescription = str(StrKey.NEW_GENERATE_SCRIPT),
            renameDescription = str(StrKey.RENAME_CURRENT_TAB),
            removeDescription = str(StrKey.CLOSE_SCRIPT),
            onSelect = state::selectGenerateScript,
            onAdd = state::addGenerateScript,
            onRemove = state::removeGenerateScript,
            onRename = state::renameGenerateScript,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = str(StrKey.LUA_VERSION),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LUA_VERSIONS.forEach { version ->
                val selected = state.generateLuaVersion == version
                AssistChip(
                    onClick = { state.generateLuaVersion = version },
                    enabled = connected,
                    label = { Text(version) },
                    colors =
                        if (selected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.fillMaxSize()) {
            if (script == null) {
                EmptyHint(
                    title = str(StrKey.NO_EDITABLE_SCRIPT),
                    description = str(StrKey.NO_EDITABLE_SCRIPT_HINT),
                    // 用 weight 而非 fillMaxSize —— 与下方三个 weighted 子项共存时不会互相挤掉
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                )
            } else {
                CodeEditorWithToolbar(
                    text = script.editor.text,
                    onTextChange = { script.editor.setText(it) },
                    editorState = script.editor,
                    languageId = "lua",
                    // 本工作台只处理 Lua —— 不暴露语言切换器
                    showLanguageSwitcher = false,
                    // 补全额外注入造数沙箱的宿主函数（`insert` / `lastId` / `random_*`）。
                    // 它们**只在这个沙箱里存在**，普通 Lua 编辑器里调用会报
                    // `attempt to call a nil value` —— 所以绝不能塞进 `LuaLanguage` 的
                    // BUILTINS，那会让所有 Lua 编辑器都开始推荐不存在的函数。
                    extraCompletions = GenerateHelpers.completions,
                    actions = {
                        WinButton(
                            onClick = { state.executeGenerate() },
                            enabled = connected && !state.generateRunning,
                            shape = SundaysPalette.buttonShape,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.generateRunning) str(StrKey.GENERATING) else str(StrKey.RUN_GENERATE))
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(0.45f),
                )
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 实时日志 —— 夹在编辑器与结果表**之间**。
            //
            // 为什么放这个位置：日志是「正在造什么」的**过程**，结果是「造完了什么」的**结论**。
            // 放在编辑器下面 = 紧贴着触发造数的那个按钮，用户点完就能看着它一行行长出来；
            // 放在结果表下面则会被结果表（造完才有东西、平时是空态或转圈）挤到视野边缘。
            GenerateLogArea(
                state = state,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(0.25f),
            )
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GenerateResultArea(
                state = state,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(0.30f),
            )
        }
    }
}

/**
 * 造数结果面板 —— 三态：进行中（实时进度）/ 错误 / 结果表（每个脚本一行）。
 *
 * 进度数据来自引擎 `DATA.GENERATE` 的 `gen_progress_frame` 流（每条 INSERT 一帧），
 * 终止帧的 `generate_terminal.tables_processed` 是已处理脚本数。
 */
@Composable
private fun GenerateResultArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        when {
            state.generateRunning -> {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text(
                        text =
                            plural(
                                PluralStrKey.GENERATING_INSERTED,
                                state.generateTotalInserted.toInt(),
                                state.generateTotalInserted,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            state.generateError != null -> {
                EmptyHint(
                    title = str(StrKey.GENERATE_FAILED),
                    description = state.generateError,
                    centerContent = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.hasGenerateResult -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text =
                                plural(
                                    PluralStrKey.GENERATE_COMPLETE_SUMMARY,
                                    state.generateTotalInserted.toInt(),
                                    state.generateTotalInserted,
                                    state.generateTablesProcessed,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DataTable(
                        columns = GENERATE_RESULT_COLUMNS,
                        rows = state.generateResultRows(),
                        modifier = Modifier.fillMaxSize(),
                        pageSize = state.generateResultPageSize,
                        onPageSizeChange = { state.generateResultPageSize = it },
                        currentPage = state.generateResultPage,
                        onPageChange = { state.generateResultPage = it },
                        selectedRowId = state.generateResultSelectedRowId,
                        onSelectedRowChange = { state.generateResultSelectedRowId = it?.id },
                        fillParentHeight = false,
                    )
                }
            }

            else -> {
                EmptyHint(
                    title = str(StrKey.NO_GENERATE_EXECUTED),
                    description = str(StrKey.NO_GENERATE_EXECUTED_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 造数日志面板 —— 实时显示引擎每条 INSERT 回传的那一行。
 *
 * ## 数据从哪来
 *
 * 引擎的 `DATA.GENERATE` 流里**每条 INSERT 回一帧** `gen_progress_frame`
 * （表名 / 该脚本已插入的行数 / 脚本下标 / 这一行的数据）。
 * 状态机把它们收进 [DatabaseBrowserState.generateLog]（有界，见 [GENERATE_LOG_MAX_LINES]）。
 *
 * 引擎协议里**没有**独立的日志消息 —— 这份日志是前端拿现成的进度帧拼出来的，
 * 引擎一行代码都不用改。引擎侧此前只把这些帧用来更新计数器，
 * 信息其实早就够了，只是没人把它铺出来。
 *
 * ## 为什么要「实时」而不是「造完再看」
 *
 * 造数是**批量写脏数据**：一段 Lua 循环几千行 INSERT。
 * 哪一行把列名写错了、哪一行类型不对，只有逐行看才能定位 ——
 * 造完之后只剩一句「共插入 3000 行」，错的那行已经无从查起。
 */
@Composable
private fun GenerateLogArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag(GENERATE_LOG_PANEL_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(StrKey.GENERATE_LOG_TITLE),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // 丢行必须**说出来** —— 静默截断等于伪造了一份完整的日志。
                if (state.generateLogDropped > 0) {
                    Text(
                        text = str(StrKey.GENERATE_LOG_DROPPED, state.generateLogDropped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.generateLog.isEmpty()) {
                EmptyHint(
                    title = str(StrKey.GENERATE_LOG_EMPTY_TITLE),
                    description = str(StrKey.GENERATE_LOG_EMPTY_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GenerateLogList(state)
            }
        }
    }
}

/**
 * 日志列表 —— 惰性渲染 + 自动滚到最新一行。
 *
 * ## 为什么用 [LazyColumn] 而不是 `Column` + `verticalScroll`
 *
 * 日志有 [GENERATE_LOG_MAX_LINES] 行的上限，1000 行全组合起来就是 1000 个文本节点，
 * 每来一行都要重新组合一遍 —— 造数到后段会肉眼可见地卡。
 * `LazyColumn` 只组合看得见的那几行。
 *
 * ## 自动滚动盯的是「序号」，条数只是**下标**的来源
 *
 * 条数在丢行时**不是单调的**（一次成批丢行会让它从 1129 掉回 1000），
 * 于是「条数变了」既可能是「又来了一行」，也可能是「旧的被丢了」。
 * [DatabaseBrowserState.generateLogSeq] 每次追加**只 +1** ——
 * 「又来了一行」因此只有**一个**信号。
 *
 * 而条数仍然要读 —— 它就是**滚到第几行**；只是**在组合期只读一次**：
 * 列表的 item 数与要滚的下标必须是**同一个数**，
 * 分两次读的话，中间来一次追加，滚到的下标就落在列表之外了。
 */
@Composable
private fun GenerateLogList(state: DatabaseBrowserState) {
    val listState = rememberLazyListState()
    // 条数在**组合期**只读一次 —— 列表的 item 数与要滚到的下标必须是**同一个数**。
    // 分两次读的话，中间来一次追加，滚到的下标就落在列表之外了。
    val count = state.generateLog.size
    LaunchedEffect(state.generateLogSeq, count) {
        if (count > 0) {
            // 用**挂起式**的 `LazyListState.scrollToItem`，不用 `requestScrollToItem`。
            //
            // 差别在于：`requestScrollToItem` 记下一个「下一次 remeasure 时生效、且只生效一次」
            // 的请求 —— 而 remeasure 常常**同时**要应用数据变更（行数变了）。
            // 那一刻位置是按**key** 维持的（列表是索引键），新行就落在视野之外了；
            // 请求有没有抢赢，取决于它与那次 remeasure 的先后 —— **竞态**。
            //
            // `scrollToItem` 则在滚动块里**同步**做完一次 remeasure：
            // 位置一定落在最后一行，且与其它滚动互斥（滚动块的锁）。
            listState.scrollToItem(count - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(GENERATE_LOG_LIST_TAG),
    ) {
        items(count) { index ->
            // `getOrNull` 而不是 `[index]` —— 丢行会让列表在**布局用到旧下标**之前
            // 短暂地比组合期读到的条数短（丢行发生在后台线程，落到界面线程是下一帧的事）。
            // 那种一帧里的下标越界会把**组合抛掉**（应用崩），而少画一行只是看不见。
            state.generateLog.getOrNull(index)?.let { GenerateLogRow(it) }
        }
    }
}

/**
 * 一行日志 —— 正常行显示「脚本 / 表 / 行号 / 列值」，失败行显示失败原因。
 *
 * 用**等宽字体**：列值是 `name=Alice, age=30` 这种键值对，
 * 等宽对齐才能一眼看出哪一段是哪个字段；比例字体的对齐全是假的。
 */
@Composable
private fun GenerateLogRow(entry: GenerateLogEntry) {
    val error = entry.error
    val text =
        if (error != null) {
            str(StrKey.GENERATE_LOG_ERROR, error)
        } else {
            str(
                StrKey.GENERATE_LOG_LINE,
                entry.scriptIndex,
                entry.totalScripts,
                entry.table,
                entry.rowNumber,
                formatGenerateLogValues(entry.values),
            )
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 造数结果表列定义（脚本 / 目标表 / 插入行数）。 */
private val GENERATE_RESULT_COLUMNS =
    listOf(
        TableColumn(key = "script", header = Msg.text(StrKey.SCRIPT)),
        TableColumn(key = "table", header = Msg.text(StrKey.TARGET_TABLE)),
        TableColumn(key = "inserted", header = Msg.text(StrKey.INSERTED_ROWS)),
    )
