/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey

// 表结构草稿的**校验与差分** —— 纯函数，不碰引擎也不碰 Compose。
//
// ## 为什么要单独成文件
//
// 这两件事是「保存表结构」里最容易出错、又最难靠界面测试覆盖的部分：
// 一个漏判就会让引擎收到一串语义错误的 DDL，或者把用户的表改坏。
// 拆成纯函数后可以直接对**构造出来的草稿**断言，不需要数据库、不需要界面 ——
// 这类断言跑得快、失败时直接告诉你是哪一条差分不对。

/**
 * 校验草稿。返回**第一条**该给用户看的问题，`null` = 通过。
 *
 * 为什么只返回第一条而不是全部：一次弹窗能改七八列，报错时逐条列出来会让用户
 * 改一个错一个、改完再来下一个。而「先改哪一个」本身没有优先级可言 ——
 * 报一条、改了、再报下一条，是最短的路径。
 *
 * 校验顺序也刻意从「最容易犯、后果最重」排起：表名 → 有没有列 → 列名 → 类型 →
 * 主键 → 外键。用户第一次点保存多半是漏填名字，那个先说。
 */
fun TableStructureDraft.validate(): String? {
    if (isNew && tableName.trim().isEmpty()) return Msg.text(StrKey.TABLE_NAME_EMPTY)
    if (columns.isEmpty()) return Msg.text(StrKey.TABLE_NO_COLUMNS)

    columns.forEachIndexed { index, column ->
        if (column.trimmedName.isEmpty()) {
            return Msg.format(StrKey.TABLE_COLUMN_NAME_EMPTY, index + 1)
        }
        if (column.type.trim().isEmpty()) {
            return Msg.format(StrKey.TABLE_COLUMN_TYPE_EMPTY, column.trimmedName)
        }
    }

    // 主键列必须 NOT NULL —— 这是**数据库自己的规则**，H2 / PG / MySQL 全都拒绝
    // 一个可空的主键列。与其让用户填完提交时才从引擎错误里看到一句
    // 「NULL not allowed for PRIMARY KEY」，不如在提交前用中文说清楚。
    columns.filter { it.primaryKey }.forEach { column ->
        if (column.nullable) return Msg.format(StrKey.TABLE_PK_NOT_NULL, column.trimmedName)
    }

    // 列名重复：比对用**去空白 + 忽略大小写**，理由见 [ColumnDraft.trimmedName]。
    val seen = mutableSetOf<String>()
    columns.forEach { column ->
        if (!seen.add(column.trimmedName.lowercase())) {
            return Msg.format(StrKey.TABLE_COLUMN_NAME_DUPLICATE, column.trimmedName)
        }
    }

    foreignKeys.forEachIndexed { index, fk ->
        if (fk.isReadOnly) return null
        if (fk.name.isBlank() || fk.column.isBlank() || fk.refTable.isBlank() || fk.refColumn.isBlank()) {
            return Msg.format(StrKey.TABLE_FK_INCOMPLETE, index + 1)
        }
        // 外键的列必须是**这张表里存在**的列。这里只查名字存不存在，不查类型匹配 ——
        // 类型匹配要连被引用表一起查，而那可能是一次网络往返；先挡住「填错名字」这个
        // 十有八九是手滑的情况，类型不匹配留给引擎报错（它报得比我们准）。
        if (!columnNames.any { it.equals(fk.column, ignoreCase = true) }) {
            return Msg.format(StrKey.TABLE_FK_INCOMPLETE, index + 1)
        }
    }
    return null
}

/**
 * 把草稿算成一串 DDL 操作。
 *
 * ## 配对靠 [ColumnDraft.originalName]，不靠现在的列名
 *
 * 用户把 `email` 改成 `mail` 时，草稿里那一行的 `originalName` 还是 `email`、
 * `trimmedName` 变成了 `mail`。按**当前名字**去原表里找就找不到这一行，于是会被
 * 同时算成「删 email」和「加 mail」—— 而那会**真的丢掉整列数据**：中途失败的话
 * email 已经没了，mail 是空的。`originalName` 是这条列在整个弹窗生命周期里的
 * 稳定标识，只用它配对。
 *
 * ## 差分为什么按这个顺序排
 *
 * **先删、再改、后加**，最后才处理外键。理由全是「失败时的残留状态」：
 *
 * - 删列放在最前：中途失败时多出来的列无害（用户看不到它、也不会去读它），
 *   而多删一列是丢数据。
 * - 加列放在改列之后：加列失败只少一列；反过来先加后删，一旦后者失败就多一列。
 * - 改列（含改名）夹在中间：它的破坏性只在类型不兼容时才显现。
 * - 外键放在最后：外键依赖「这张表现在有这些列」。放前面的话，新增列还没加上
 *   就会去建引用它的外键，直接失败。
 *
 * ## 改名与改类型**合成一条**
 *
 * 引擎的 `MODIFY_COLUMN` 同时收 `type` 与 `new_name`，一次就能改完。
 * 拆成「先改名再改类型」多一条往返，而中途失败会留下一个改了名但类型没改的列 ——
 * 用户看到的是「改名生效了但类型没变」，比全不改更难查。
 */
fun TableStructureDraft.diff(): List<StructureChange> {
    if (isNew) {
        // 新建表：全部字段与外键都是「加」，不需要差分。
        return columns
            .filter { it.trimmedName.isNotBlank() }
            .map { StructureChange.AddColumn(it) } +
            foreignKeys
                .filter { !it.isReadOnly && it.name.isNotBlank() }
                .map { StructureChange.AddForeignKey(it) }
    }

    val changes = mutableListOf<StructureChange>()

    // 「改之前」一律取自 [TableStructureDraft.originalColumns] 这份快照，**不是**从
    // [TableStructureDraft.columns] 反推 —— 后者已经是改完之后的样子了。
    // 配对的键是 [ColumnDraft.originalName]：它是这一列在整个弹窗生命周期里
    // 唯一不变的标识，而当前列名会被用户改掉。
    val originals = originalColumns

    // 1) 删：快照里有、当前草稿里已经没有的列
    originals
        .filter { original ->
            columns.none { it.originalName.equals(original.trimmedName, ignoreCase = true) }
        }.forEach { changes += StructureChange.DropColumn(it.trimmedName) }

    // 2) 改 / 改名：两边都有，逐个比
    originals.forEach { original ->
        val after = columns.firstOrNull { it.originalName.equals(original.trimmedName, ignoreCase = true) }
        if (after != null && !after.matches(original)) {
            changes += StructureChange.ModifyColumn(original, after)
        }
    }

    // 3) 加：当前草稿里 new 出来的（originalName 为空 = 用户在弹窗里加的行）
    columns.filter { it.isNew }.forEach { changes += StructureChange.AddColumn(it) }

    // 4) 外键：协议只有 CREATE / DELETE，**没有 ALTER**。所以外键的任何改动
    //    （改名、改列、改引用、改 ON DELETE/UPDATE）都只能拆成「删了按新定义重建」。
    //
    //    删除有三个来源，少一个就有一条约束会静悄悄地留在库里：
    //      a) 用户在弹窗里把那一行整行删了 —— 只能靠 [TableStructureDraft.originalForeignKeys]
    //         这份快照才看得见（当前列表里已经没有它了）
    //      b) 用户改了名字或内容 —— 重建的前半步
    //      c) 复合外键（isReadOnly）**不是**删除来源：界面改不了它，绝不能顺手删掉重建
    val dropForeignKeys = mutableListOf<StructureChange>()
    val addForeignKeys = mutableListOf<StructureChange>()

    /** 已经进过 dropForeignKeys 的约束名 —— 避免同一条 DROP 发两遍。 */
    val dropped = mutableSetOf<String>()

    /** 仍在草稿里、且不需要重建的原约束名 —— 用来识别「整行删掉了」。 */
    val kept = mutableSetOf<String>()

    foreignKeys.forEach { fk ->
        val original = fk.original
        if (fk.isReadOnly) {
            // 复合外键：原样留着。**必须**记进 kept，否则下面「整行删掉了」的扫描会
            // 把它当成一条该 DROP 的约束 —— 那就等于一次普通保存毁掉用户没打算动的外键。
            original?.let { kept += it.name }
            return@forEach
        }
        if (original == null) {
            if (fk.name.isNotBlank()) addForeignKeys += StructureChange.AddForeignKey(fk)
            return@forEach
        }
        val renamed = !fk.name.equals(original.name, ignoreCase = true)
        // 只改名字、内容没变 → 仍然要重建：约束名本身就是这条外键的身份，
        // 而引擎没有「改约束名」这条语句。不重建的话用户改完名点保存，
        // 界面报「已保存」，库里一个字都没动 —— 静默失败比报错难查得多。
        if (!renamed && !fk.hasContentChanged()) {
            kept += original.name
            return@forEach
        }
        dropped += original.name
        dropForeignKeys += StructureChange.DropForeignKey(original.name)
        addForeignKeys += StructureChange.AddForeignKey(fk)
    }
    // (a) 整行删掉的外键：快照里有、当前草稿里既没保留也没重建。
    originalForeignKeys.forEach { original ->
        if (original.name !in kept && original.name !in dropped) {
            dropped += original.name
            dropForeignKeys += StructureChange.DropForeignKey(original.name)
        }
    }
    // 删除在前、重建在后：同名约束还在的时候先建会被引擎直接拒
    // （PG: constraint "fk_x" already exists）。
    changes += dropForeignKeys
    changes += addForeignKeys

    return changes
}
