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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
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
                    // 用 weight 而非 fillMaxSize —— 与下方两个 weighted 子项共存时不会互相挤掉
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
                            .weight(0.6f),
                )
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GenerateResultArea(
                state = state,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(0.4f),
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

/** 造数结果表列定义（脚本 / 目标表 / 插入行数）。 */
private val GENERATE_RESULT_COLUMNS =
    listOf(
        TableColumn(key = "script", header = Msg.text(StrKey.SCRIPT)),
        TableColumn(key = "table", header = Msg.text(StrKey.TARGET_TABLE)),
        TableColumn(key = "inserted", header = Msg.text(StrKey.INSERTED_ROWS)),
    )
