/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.str
import kotlinx.coroutines.launch

// ============================================================================
// UI 测试 tag
// ============================================================================

internal const val TABLE_STRUCTURE_DIALOG_TAG = "tableStructureDialog"
internal const val TABLE_STRUCTURE_NAME_TAG = "tableStructureName"
internal const val TABLE_STRUCTURE_SAVE_BTN_TAG = "tableStructureSaveBtn"
internal const val TABLE_STRUCTURE_CANCEL_BTN_TAG = "tableStructureCancelBtn"
internal const val TABLE_STRUCTURE_ADD_COLUMN_BTN_TAG = "tableStructureAddColumnBtn"
internal const val TABLE_STRUCTURE_ADD_FK_BTN_TAG = "tableStructureAddFkBtn"
internal const val TABLE_STRUCTURE_ERROR_TAG = "tableStructureError"
const val TABLE_STRUCTURE_DELETE_CONFIRM_BTN_TAG = "tableStructureDeleteConfirmBtn"

/** 字段行内的 tag —— 后缀是 0 起的行号。 */
internal fun tableStructureColumnRowTag(index: Int): String = "tableStructureColumnRow$index"

internal fun tableStructureColumnNameTag(index: Int): String = "tableStructureColumnName$index"

internal fun tableStructureColumnTypeTag(index: Int): String = "tableStructureColumnType$index"

internal fun tableStructureColumnLengthTag(index: Int): String = "tableStructureColumnLength$index"

internal fun tableStructureColumnRemoveTag(index: Int): String = "tableStructureColumnRemove$index"

internal fun tableStructureFkRowTag(index: Int): String = "tableStructureFkRow$index"

internal fun tableStructureFkRefTableTag(index: Int): String = "tableStructureFkRefTable$index"

internal fun tableStructureFkRemoveTag(index: Int): String = "tableStructureFkRemove$index"

// ============================================================================
// 弹窗
// ============================================================================

/**
 * 「新增表」与「编辑表结构」共用的弹窗。
 *
 * ## 为什么两个入口能共用一个弹窗
 *
 * 两者的**数据形状完全一样**（表名 + 字段列表 + 外键列表），差别只在三个开关上：
 * 表名能不能填（改表名是 `TABLE.RENAME`，与改列不是一回事，放到编辑里会让一批
 * DDL 里混进一条改名，差分就说不清了）、提交走 `TABLE.CREATE` 还是逐条差分、
 * 以及要不要显示「未收录的类型」。
 * 把这些差异做成 [TableStructureDraft.isNew] 这一个字段，弹窗本体一行都不用分叉 ——
 * 一旦分成两个弹窗，字段编辑这套最容易出 bug 的 UI 就要维护两遍。
 *
 * ## 为什么草稿状态在弹窗内部
 *
 * 父层只在「打开那一刻」交出一份 [TableStructureDraft]，之后用户在弹窗里敲字
 * 不应该每敲一下就穿到状态机里重算一遍差分（那是纯计算，白烧）。弹窗内部持一份
 * 拷贝，点保存时才整体交出去；中途关闭则整份丢掉，外界一个字节都没变过。
 */
@Composable
fun TableStructureDialog(
    state: StructureDialogState,
    dialect: DialectType,
    loadRefColumns: suspend (refTable: String) -> List<String>,
    onSave: (TableStructureDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    // keyed on draft：读表结构回来时 draft 会换一份（占位 → 真实结构），
    // 这正是应该重置的时机；用户后续的敲字只改本地这份，不触发重置。
    var draft by remember(state.draft) { mutableStateOf(state.draft) }
    val features = DialectCapabilities.of(dialect)
    val coroutineScope = rememberCoroutineScope()

    // 两条错误来源，**都要**显示出来：
    //   · [state.error] —— 已经发生的失败（读结构失败 / 保存失败）
    //   · [TableStructureDraft.validate] —— 提交前的即时校验
    //
    // 顺序很重要：保存失败时 [state.error] 才是用户唯一能看到的反馈 ——
    // 草稿本身是合法的，validate() 返回 null，而错误文案必须显示出来。
    //
    // ⚠️ 这里的错误**必须每次重组都从 [state.error] 读**，不能 remember 成快照：
    // 保存失败时 [state.draft] 没变（还是同一份），`remember(state.draft)` 的 key
    // 也就没变 —— 快照会永远停在初始值，于是用户点了保存、什么都没发生、
    // 连**为什么**失败都看不到（实测：MySQL 报「表已存在」，界面上只有一片安静）。
    //
    // 提交前的即时校验：不通过就把按钮变灰 + 显示原因，而不是等点下去才弹通知。
    // 保存中 disable，是为了防止连点两次发出两批 DDL —— 事务会话会互相打架。
    //
    // ⚠️ 加载中**不算**校验：那时草稿还是占位内容（表名空、字段空），
    // `validate()` 的结论（「表名不能为空」「至少要一个字段」）对用户没有意义，
    // 显示出来只是噪声。但加载**失败**是另一回事 —— 那是 [state.error]，必须显示。
    val localError = if (state.loading) null else draft.validate()
    val canSave = localError == null && !state.saving && !state.loading

    Dialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            shadowElevation = 8.dp,
            modifier =
                Modifier
                    .widthIn(max = 1080.dp)
                    .fillMaxWidth()
                    .testTag(TABLE_STRUCTURE_DIALOG_TAG),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text =
                        if (draft.isNew) {
                            str(StrKey.TABLE_DIALOG_NEW_TITLE)
                        } else {
                            str(StrKey.TABLE_DIALOG_EDIT_TITLE, draft.originalName)
                        },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                // 表名：新增时可填，编辑时**只读**。改名是另一条 DDL（TABLE.RENAME），
                // 混进字段差分会让「这次保存到底改了什么」说不清，所以这一期不做。
                if (draft.isNew) {
                    WinTextField(
                        value = draft.tableName,
                        onValueChange = { draft = draft.copy(tableName = it) },
                        singleLine = true,
                        label = { Text(str(StrKey.TABLE_DIALOG_NAME)) },
                        modifier =
                            Modifier
                                .width(280.dp)
                                .testTag(TABLE_STRUCTURE_NAME_TAG),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        text = "${str(StrKey.TABLE_DIALOG_NAME)}: ${draft.originalName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (state.loading) {
                        Text(
                            text = str(StrKey.TABLE_DIALOG_LOADING),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ColumnSection(str(StrKey.TABLE_DIALOG_COLUMNS)) {
                        ColumnHeaderRow()
                        draft.columns.forEachIndexed { index, column ->
                            ColumnEditorRow(
                                index = index,
                                column = column,
                                dialect = dialect,
                                canRemove = draft.columns.size > 1,
                                onChange = { changed ->
                                    draft =
                                        draft.copy(
                                            columns = draft.columns.toMutableList().also { it[index] = changed },
                                        )
                                },
                                onRemove = {
                                    draft = draft.copy(columns = draft.columns.filterIndexed { i, _ -> i != index })
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        WinTextButton(
                            onClick = {
                                // 新列默认给个 VARCHAR(255)：空类型在 [TableStructureDraft.validate]
                                // 里算「没选类型」，用户点了保存只会被挡回来，得自己再选一次。
                                // 给个合理默认 = 点一下就能提交，但不是唯一答案（下拉里能换）。
                                draft =
                                    draft.copy(
                                        columns = draft.columns + ColumnDraft(name = "", type = defaultColumnType(dialect)),
                                    )
                            },
                            shape = SundaysPalette.buttonShape,
                            modifier = Modifier.testTag(TABLE_STRUCTURE_ADD_COLUMN_BTN_TAG),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(str(StrKey.TABLE_COLUMN_ADD), maxLines = 1, softWrap = false)
                        }
                    }

                    // 外键区：方言不支持就整块不画。SQLite 虽然支持，但界面上先不给 ——
                    // 与 [DialectCapabilities.supportsForeignKeys] 保持一致，不在这里另开例外。
                    if (features.supportsForeignKeys) {
                        Spacer(Modifier.height(16.dp))
                        ForeignKeySection(
                            draft = draft,
                            onChange = { changed -> draft = draft.copy(foreignKeys = changed) },
                            onRefTableChosen = { refTable ->
                                // 引用表换了，被引用字段的候选就换了：先把旧值清空，
                                // 否则界面上会留下「引用 A 表的 x 字段」而引用表已经是 B，
                                // 提交时报出来的错误很难看懂。
                                draft =
                                    draft.copy(
                                        foreignKeys = draft.foreignKeys.map { it.copy(refColumn = "") },
                                        refColumnsByTable = draft.refColumnsByTable - refTable,
                                    )
                                coroutineScope.launch {
                                    val columns = loadRefColumns(refTable)
                                    draft = draft.copy(refColumnsByTable = draft.refColumnsByTable + (refTable to columns))
                                }
                            },
                        )
                    }

                    if (!features.ddlRollbackable) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = str(StrKey.TABLE_DDL_NO_ROLLBACK),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                val errorText = state.error ?: localError
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TABLE_STRUCTURE_ERROR_TAG),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    WinTextButton(
                        onClick = onDismiss,
                        enabled = !state.saving,
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(TABLE_STRUCTURE_CANCEL_BTN_TAG),
                    ) {
                        Text(str(StrKey.CANCEL), maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.width(8.dp))
                    WinButton(
                        onClick = {
                            // 不在这里清错误：状态机在**开始保存**时就把 error 清成
                            // null（saving = true, error = null），清两遍反而要维护两份状态。
                            onSave(draft)
                        },
                        enabled = canSave,
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(TABLE_STRUCTURE_SAVE_BTN_TAG),
                    ) {
                        Text(str(StrKey.TABLE_DIALOG_SAVE), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * 弹窗的宿主状态。
 *
 * [loading] 与 [error] 分开而不是合并成 `error: String?`：读取进行中弹窗**已经开着**
 * （画的是一个空表格 + 读入中提示），失败时也保留用户在这期间填的任何东西 ——
 * 直接整体关掉/换掉等于把用户刚敲的字悄悄抹掉。
 */
data class StructureDialogState(
    val draft: TableStructureDraft,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * 新列的默认类型 —— 取该方言清单的第一项。
 *
 * 清单本身按「最通用 → 最特殊」排过序，所以第一项就是各方言里最不会出错的那个
 * （PG 是 `integer`、MySQL 是 `INT`、SQLite 是 `INTEGER`）。给默认值的意义是
 * 「点一下就能提交」，不是「只能用这个」——下拉里随时能换。
 */
fun defaultColumnType(dialect: DialectType): String = typeChoices(dialect, "").firstOrNull()?.name ?: "VARCHAR"

// ============================================================================
// 分区与表头
// ============================================================================

/** 一个分区的小标题。 */
@Composable
private fun ColumnScope.SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

// ============================================================================
// 字段区
// ============================================================================

/**
 * 字段区的列宽 —— 表头与每一行**必须**共用同一组常量，否则每加一列整张表就错位。
 *
 * 用固定宽度而不是自适应：这一行的控件数是死的（6 个输入 + 3 个勾选 + 1 个按钮），
 * 自适应只会让「长度」和「默认值」抢走「字段名」的宽度，而字段名恰恰是最该宽的。
 */
private val COL_NAME_WIDTH = 170.dp
private val COL_TYPE_WIDTH = 190.dp
private val COL_LENGTH_WIDTH = 70.dp
private val COL_DEFAULT_WIDTH = 130.dp
private val COL_CHECK_WIDTH = 62.dp

@Composable
private fun ColumnHeaderRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderCell(str(StrKey.TABLE_COLUMN_NAME), COL_NAME_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_TYPE), COL_TYPE_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_LENGTH), COL_LENGTH_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_DEFAULT), COL_DEFAULT_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_PRIMARY_KEY), COL_CHECK_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_NULLABLE), COL_CHECK_WIDTH)
        HeaderCell(str(StrKey.TABLE_COLUMN_AUTO_INCREMENT), COL_CHECK_WIDTH)
        Spacer(Modifier.size(32.dp))
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.width(width).padding(end = 4.dp),
    )
}

/** 一个字段行的编辑器。 */
@Composable
private fun ColumnEditorRow(
    index: Int,
    column: ColumnDraft,
    dialect: DialectType,
    canRemove: Boolean,
    onChange: (ColumnDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tableStructureColumnRowTag(index)),
    ) {
        WinTextField(
            value = column.name,
            onValueChange = { onChange(column.copy(name = it)) },
            singleLine = true,
            modifier =
                Modifier
                    .width(COL_NAME_WIDTH)
                    .padding(end = 4.dp)
                    .testTag(tableStructureColumnNameTag(index)),
        )
        TypeComboField(
            value = column.type,
            dialect = dialect,
            onChange = { onChange(column.copy(type = it)) },
            modifier =
                Modifier
                    .width(COL_TYPE_WIDTH)
                    .padding(end = 4.dp)
                    .testTag(tableStructureColumnTypeTag(index)),
        )
        WinTextField(
            value = column.length,
            onValueChange = { onChange(column.copy(length = it)) },
            singleLine = true,
            modifier =
                Modifier
                    .width(COL_LENGTH_WIDTH)
                    .padding(end = 4.dp)
                    .testTag(tableStructureColumnLengthTag(index)),
        )
        WinTextField(
            value = column.defaultValue,
            onValueChange = { onChange(column.copy(defaultValue = it)) },
            singleLine = true,
            modifier =
                Modifier
                    .width(COL_DEFAULT_WIDTH)
                    .padding(end = 4.dp),
        )
        // 主键勾上就顺手把「允许为空」取消：主键列可空是数据库直接拒绝的
        // （见 TableStructureDraft.validate 的说明），在界面上替他做掉这次矛盾，
        // 比存心让他点保存再被弹回来一次要好。
        Checkbox(
            checked = column.primaryKey,
            onCheckedChange = { checked ->
                onChange(column.copy(primaryKey = checked, nullable = if (checked) false else column.nullable))
            },
        )
        Checkbox(
            checked = column.nullable,
            onCheckedChange = { onChange(column.copy(nullable = it)) },
            // 主键列锁死：取消勾选主键才能改它
            enabled = !column.primaryKey,
        )
        Checkbox(
            checked = column.autoIncrement,
            onCheckedChange = { onChange(column.copy(autoIncrement = it)) },
        )
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = str(StrKey.TABLE_COLUMN_REMOVE, column.trimmedName),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 类型输入：**可编辑的文本框 + 一个下拉箭头**。
 *
 * ## 为什么不用只读的下拉框
 *
 * SQL 的类型空间是开放的：H2 的 `UUID`、`GEOMETRY`，PostGIS 的 `geography`，
 * 用户自建域上的类型…… [typeChoices] 给的是**建议**不是白名单。做成只读下拉，
 * 就会把「清单没收录」变成一道墙 —— 用户被迫从清单里挑一个，而库里真实的类型
 * 反而改不掉。所以能打字，同时给一份按方言排好的清单当候选。
 *
 * 下拉清单的第一项永远是当前值（见 [typeChoices]）：存量表里读出来的类型多半不在
 * 清单里，不置顶的话框里显示的是清单第一项，用户一点保存就把类型改了 —— 而界面上
 * 从头到尾没提示过他改过什么。
 */
@Composable
private fun TypeComboField(
    value: String,
    dialect: DialectType,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val choices = typeChoices(dialect, value)
    Box(modifier = modifier) {
        WinTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                WinMenuItem(
                    text = {
                        Text(
                            text =
                                if (choice.fromCatalog) {
                                    choice.name
                                } else {
                                    str(StrKey.TABLE_COLUMN_TYPE_CUSTOM, choice.name)
                                },
                            maxLines = 1,
                        )
                    },
                    onClick = {
                        open = false
                        onChange(choice.name)
                    },
                )
            }
        }
    }
}

// ============================================================================
// 外键区
// ============================================================================

private val FK_NAME_WIDTH = 150.dp
private val FK_ACTION_WIDTH = 110.dp

/**
 * 外键编辑区。
 *
 * 一行一条外键，每行五个控件。**复合外键**（多个本表列对多个被引用列）画成一行
 * 只读摘要 —— 协议支持但界面这期不支持编辑，直接不显示的话用户会以为工具把它们
 * 删了（保存时确实一条删除语句都不会有，于是两边一起骗人）。写清楚「不支持编辑」
 * 才是诚实的做法。
 */
@Composable
private fun ColumnScope.ForeignKeySection(
    draft: TableStructureDraft,
    onChange: (List<ForeignKeyDraft>) -> Unit,
    onRefTableChosen: (String) -> Unit,
) {
    ColumnSection(str(StrKey.TABLE_DIALOG_FOREIGN_KEYS)) {
        if (draft.foreignKeys.isEmpty()) {
            Text(
                text = str(StrKey.TABLE_FK_REF_TABLE_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        draft.foreignKeys.forEachIndexed { index, fk ->
            ForeignKeyRow(
                index = index,
                fk = fk,
                columnNames = draft.columnNames,
                refTables = draft.refTableCandidates,
                refColumns = draft.refColumnsByTable[fk.refTable].orEmpty(),
                onChange = { changed ->
                    onChange(draft.foreignKeys.toMutableList().also { it[index] = changed })
                },
                onRefTableChosen = onRefTableChosen,
                onRemove = { onChange(draft.foreignKeys.filterIndexed { i, _ -> i != index }) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        WinTextButton(
            onClick = {
                onChange(
                    draft.foreignKeys +
                        ForeignKeyDraft(
                            name = "",
                            onDelete = "NO ACTION",
                            onUpdate = "NO ACTION",
                        ),
                )
            },
            shape = SundaysPalette.buttonShape,
            modifier = Modifier.testTag(TABLE_STRUCTURE_ADD_FK_BTN_TAG),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(str(StrKey.TABLE_FK_ADD), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ForeignKeyRow(
    index: Int,
    fk: ForeignKeyDraft,
    columnNames: List<String>,
    refTables: List<String>,
    refColumns: List<String>,
    onChange: (ForeignKeyDraft) -> Unit,
    onRefTableChosen: (String) -> Unit,
    onRemove: () -> Unit,
) {
    if (fk.isReadOnly) {
        ReadOnlyForeignKeyRow(index = index, fk = fk)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tableStructureFkRowTag(index)),
    ) {
        WinTextField(
            value = fk.name,
            onValueChange = { onChange(fk.copy(name = it)) },
            singleLine = true,
            modifier = Modifier.width(FK_NAME_WIDTH).padding(end = 4.dp),
        )
        SuggestionTextField(
            value = fk.column,
            suggestions = columnNames,
            onChange = { onChange(fk.copy(column = it)) },
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        SuggestionTextField(
            value = fk.refTable,
            suggestions = refTables,
            onChange = { onChange(fk.copy(refTable = it)) },
            // 只有从下拉里选中才去查候选列名 —— 手打一半的表名去查引擎只会白跑一趟。
            onPick = onRefTableChosen,
            modifier = Modifier.weight(1f).padding(end = 4.dp).testTag(tableStructureFkRefTableTag(index)),
        )
        SuggestionTextField(
            value = fk.refColumn,
            suggestions = refColumns,
            onChange = { onChange(fk.copy(refColumn = it)) },
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        ReferentialActionPicker(
            value = fk.onDelete,
            onChange = { onChange(fk.copy(onDelete = it)) },
            modifier = Modifier.width(FK_ACTION_WIDTH).padding(end = 4.dp),
        )
        ReferentialActionPicker(
            value = fk.onUpdate,
            onChange = { onChange(fk.copy(onUpdate = it)) },
            modifier = Modifier.width(FK_ACTION_WIDTH).padding(end = 4.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = str(StrKey.TABLE_FK_REMOVE, fk.name),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 复合外键 —— 只画一行摘要，明确告诉用户「有，但改不了」，而不是静悄悄地不显示。 */
@Composable
private fun ReadOnlyForeignKeyRow(
    index: Int,
    fk: ForeignKeyDraft,
) {
    val o = fk.original ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .testTag(tableStructureFkRowTag(index)),
    ) {
        Text(
            text = "${o.name}: ${fk.column} → ${o.refTable}.${fk.refColumn}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = str(StrKey.TABLE_FK_COMPLEX_READONLY),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(180.dp).padding(end = 4.dp),
        )
    }
}

/**
 * 引用动作（`ON DELETE` / `ON UPDATE`）—— 四个 SQL 标准动作，闭集。
 *
 * 这里**不能**做成可编辑的：[typeChoices] 那种「允许手打」是为了类型空间开放，
 * 而引用动作是 SQL 标准枚举，手打一个标准外的值只会被引擎拒。
 */
@Composable
private fun ReferentialActionPicker(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val options =
        listOf(
            "NO ACTION" to str(StrKey.TABLE_FK_ACTION_NO_ACTION),
            "RESTRICT" to str(StrKey.TABLE_FK_ACTION_RESTRICT),
            "CASCADE" to str(StrKey.TABLE_FK_ACTION_CASCADE),
            "SET NULL" to str(StrKey.TABLE_FK_ACTION_SET_NULL),
        )
    val label = options.firstOrNull { it.first.equals(value, ignoreCase = true) }?.second ?: value
    Box(modifier = modifier) {
        WinTextField(
            value = label,
            // 只读：值只能从四个标准动作里挑
            onValueChange = {},
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (raw, text) ->
                WinMenuItem(
                    text = { Text(text, maxLines = 1) },
                    onClick = {
                        open = false
                        onChange(raw)
                    },
                )
            }
        }
    }
}

/**
 * 带候选提示的文本框 —— 本表字段 / 引用表 / 被引用字段都用它。
 *
 * 可编辑 + 下拉，而不是只读下拉，理由同 [TypeComboField]：用户完全可能引用一张
 * 还没展开到树里的表，或者填一个本次没有拉列名回来的字段。把它挡在框外，用户就只能
 * 去手工展开那棵树先把列名拉回来 —— 而这个「候选从哪来」本身是界面内部的实现细节，
 * 不该逼着用户配合。
 */
@Composable
private fun SuggestionTextField(
    value: String,
    suggestions: List<String>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        WinTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { open = true }) {
                        Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open && suggestions.isNotEmpty(), onDismissRequest = { open = false }) {
            suggestions.forEach { suggestion ->
                WinMenuItem(
                    text = { Text(suggestion, maxLines = 1) },
                    onClick = {
                        open = false
                        onChange(suggestion)
                        // 只在**从清单里选中**时才通知上层，手打的字不通知 ——
                        // 上层要拿这个名字去查引擎列名，逐字符发请求等于把引擎打穿。
                        onPick(suggestion)
                    },
                )
            }
        }
    }
}

// ============================================================================
// 分区容器
// ============================================================================

/** 带一条分隔线的小节容器 —— 字段区与外键区各一个。 */
@Composable
private fun ColumnScope.ColumnSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle(title)
            content()
        }
    }
}
