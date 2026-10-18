/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

/**
 * 「编辑表结构」弹窗里的一行 —— 一个字段。
 *
 * ## 为什么是**草稿**而不是直接改 tab
 *
 * 保存之前不能有任何东西落到界面上已经「生效」的位置：弹窗一次可能要改七八列，
 * 用户会中途改主意。草稿是弹窗独占的一份拷贝，确认时整批算差分再提交；
 * 放弃则整份丢掉，界面回到打开弹窗前的样子。
 *
 * @property originalName 打开弹窗时的列名。空 = 这是**新增**的列。
 *   差分靠它区分「加列」与「改列」—— 用户把列名从 `a` 改成 `b` 在语义上是
 *   「删 a 加 b」，但交给引擎时必须走 [ColumnDef.new_name]
 *   的改名而不是两步，否则中途失败会真的掉一列数据。
 */
data class ColumnDraft(
    val originalName: String = "",
    val name: String = "",
    val type: String = "",
    val length: String = "",
    val primaryKey: Boolean = false,
    val nullable: Boolean = true,
    val autoIncrement: Boolean = false,
    val defaultValue: String = "",
) {
    /** 是不是这次会话里新加的列。 */
    val isNew: Boolean get() = originalName.isBlank()

    /**
     * 去掉首尾空白后的列名。
     *
     * 差分与去重一律用**它**而不是 [name]：用户手输 `" id "` 与 `"id"` 在
     * 数据库里是同一列，而按原样比较会让它们看起来是两列 —— 于是重复检查漏掉
     * 真正的冲突，而提交时报一个用户看不懂的「列已存在」。
     */
    val trimmedName: String get() = name.trim()

    /** 长度；空串或非数字都按「不指定长度」处理（proto 的 `size` 0 即 unspecified）。 */
    val lengthOrZero: Int get() = length.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0

    /** 这个字段的定义是否与打开弹窗时**完全一致** —— 没变就一条语句都不用发。 */
    fun matches(original: ColumnDraft): Boolean =
        trimmedName.equals(original.trimmedName, ignoreCase = true) &&
            type.trim().equals(original.type.trim(), ignoreCase = true) &&
            lengthOrZero == original.lengthOrZero &&
            primaryKey == original.primaryKey &&
            nullable == original.nullable &&
            autoIncrement == original.autoIncrement &&
            defaultValue.trim() == original.defaultValue.trim()
}

/**
 * 「编辑表结构」弹窗里的一行 —— 一条外键。
 *
 * 复合外键（多个本表列对多个被引用列）在协议上是支持的
 * （`ForeignKeyCreateRequest.columns` / `ref_columns` 都是 repeated），
 * 但**界面上一期只支持单列**：用户填一个列名就够用，而多列编辑会让每个下拉框
 * 都变成一个多选列表框 —— 那是另一个量级的控件。读回来的复合外键仍会显示，
 * 只是标成「暂不支持编辑」，而不是悄悄丢掉（见 [com.langbiantianya.sundays.DatabaseBrowserState]）。
 */
data class ForeignKeyDraft(
    val name: String = "",
    /** 打开弹窗时读到的定义；为空 = 新增的外键。 */
    val original: ForeignKeyDefinition? = null,
    val column: String = "",
    val refTable: String = "",
    val refColumn: String = "",
    val onDelete: String = "NO ACTION",
    val onUpdate: String = "NO ACTION",
) {
    /** 引擎侧 `FOREIGN_KEY.LIST` 的一项，前端只留界面要用的那几个字段。 */
    data class ForeignKeyDefinition(
        val name: String,
        val column: String,
        val refTable: String,
        val refColumn: String,
        val onDelete: String,
        val onUpdate: String,
    )

    val isNew: Boolean get() = original == null

    /**
     * 是否是「读回来就改不了」的那种外键（复合列、或者只有主键的 MySQL 隐式约束）。
     *
     * 界面把它们画成**只读的一行**并说明原因，而不是不显示 —— 不显示的话用户
     * 以为这张表没有外键，保存时又发现库里其实有，以为自己把它们删了。
     */
    val isReadOnly: Boolean get() = original != null && original.column.isBlank()

    /** 除名字外是否有实质改动（名字对但内容一样 → 不发语句）。 */
    fun hasContentChanged(): Boolean {
        val o = original ?: return true
        return column != o.column ||
            refTable != o.refTable ||
            refColumn != o.refColumn ||
            onDelete != o.onDelete ||
            onUpdate != o.onUpdate
    }
}

/**
 * 一张表的结构草稿 —— 「新增表」与「编辑表结构」共用。
 *
 * @property isNew `true` = 从零建表（表名可填、差分为空、走 `TABLE.CREATE`）；
 *   `false` = 编辑已有表（表名只读、算差分走 `TABLE.UPDATE`）。
 */
data class TableStructureDraft(
    val database: String,
    val schema: String,
    val tableName: String = "",
    /** 编辑时的原表名。改名走 `TABLE.RENAME`，与改列是两条不同的语句。 */
    val originalName: String = "",
    val isNew: Boolean = true,
    val columns: List<ColumnDraft> = emptyList(),
    /**
     * 打开弹窗时读到的字段清单 —— **一份与 [columns] 平行的快照，从不随编辑变化**。
     *
     * 为什么非得有它：用户在弹窗里把一列删掉、改名、或把类型改掉之后，[columns] 里
     * 剩下的就是**改完之后**的样子，改之前的定义在界面上已经无处可寻。
     * 没有这份快照，[TableStructureDraft.diff] 连「这一列原来叫什么」都答不出来 ——
     * 改名会被算成「加一列」（旧的定义已经找不回来了），而引擎拿到的是
     * 一个 `ADD COLUMN`，用户却以为改的是 `MODIFY COLUMN`。
     *
     * 同 [originalForeignKeys]：编辑模式下 [columns] ⊆ 快照 ∪ 新增列。
     */
    val originalColumns: List<ColumnDraft> = emptyList(),
    val foreignKeys: List<ForeignKeyDraft> = emptyList(),
    /**
     * 打开弹窗时读到的外键清单 —— **包含后来被用户在弹窗里整行删掉的那些**。
     *
     * 为什么非得有它：用户在弹窗里把一条外键那一行删掉之后，弹窗里的列表就再也
     * 没有它了，而引擎侧那条约束**还在**。没有这份快照就没有任何东西能推出
     * 「该发一条 DROP」，保存会静悄悄地把它留着 —— 用户以为删掉了。
     *
     * 与 [foreignKeys] 的关系永远是「快照 ⊇ 当前」，且当前列表里的每一项都带着
     * 自己的 [ForeignKeyDraft.original] 指回快照里的那一条。
     */
    val originalForeignKeys: List<ForeignKeyDraft> = emptyList(),
    /** 引用表下拉的候选：当前库里除自己以外的表。 */
    val refTableCandidates: List<String> = emptyList(),
    /** 被引用列下拉的候选：`列名 -> 该引用表有哪些列`。 */
    val refColumnsByTable: Map<String, List<String>> = emptyMap(),
) {
    /** 已填的字段名（去空白、大小写不敏感去重）—— 校验与外键下拉都用它。 */
    val columnNames: List<String>
        get() = columns.map { it.trimmedName }.filter { it.isNotBlank() }
}

/** 一次差分算出来的、可提交给引擎的 DDL 操作。给界面显示「将执行什么」，也用于测试。 */
sealed interface StructureChange {
    val label: String

    data class AddColumn(
        val column: ColumnDraft,
    ) : StructureChange {
        override val label: String get() = "+ ${column.trimmedName} ${column.type}"
    }

    data class ModifyColumn(
        val before: ColumnDraft,
        val after: ColumnDraft,
    ) : StructureChange {
        /**
         * 名字变了就显示 `旧 → 新`，否则只显示类型。
         *
         * 改名与改类型**合并成同一条**（引擎的 `MODIFY_COLUMN` 同时收 `type` 与
         * `new_name`），所以两边都有变化时这里得都说出来 —— 只显示类型的话用户
         * 会以为改名的部分没被应用。
         */
        override val label: String
            get() =
                if (!before.trimmedName.equals(after.trimmedName, ignoreCase = true)) {
                    "${before.trimmedName} → ${after.trimmedName} ${after.type}"
                } else {
                    "${after.trimmedName} ${after.type}"
                }
    }

    data class DropColumn(
        val name: String,
    ) : StructureChange {
        override val label: String get() = "- $name"
    }

    data class AddForeignKey(
        val fk: ForeignKeyDraft,
    ) : StructureChange {
        override val label: String get() = "+ FK ${fk.name}"
    }

    data class DropForeignKey(
        val name: String,
    ) : StructureChange {
        override val label: String get() = "- FK $name"
    }
}
