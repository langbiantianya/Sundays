/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.sundays.table.ColumnTypeCatalog
import com.langbiantianya.sundays.table.ColumnTypeSpec

/**
 * 「读取表结构」的结果。
 *
 * 显式区分成功与失败而不是返回 `null`：读取失败时弹窗**仍要打开** ——
 * 里面至少能看到一个空表格和错误提示，用户能立刻重试；而直接不打开的话，
 * 界面上就只剩「右键菜单点了没反应」这一个现象，最难查。
 */
sealed interface StructureLoadResult {
    data class Loaded(
        val draft: TableStructureDraft,
    ) : StructureLoadResult

    data class Failed(
        val message: String,
    ) : StructureLoadResult
}

/**
 * 类型下拉框在给定方言下的候选项 —— 带「当前值」兜底。
 *
 * ## 为什么要把「当前值」放在第一位
 *
 * 用户打开一张**存量表**时，类型是库里读出来的真实值，而它未必在
 * [ColumnTypeCatalog] 里 —— 各家方言有大量扩展类型，清单不可能收全。
 * 若下拉框只列清单，这个值就会「看不见」：框里显示的还是清单第一项，
 * 用户一点「保存」就把类型改了，而界面上根本没提示过它变过。
 *
 * 所以：**当前值不在清单里时，把它插到最前面**并标出来。
 */
fun typeChoices(
    dialect: com.langbiantianya.sundays.connection.DialectType,
    current: String,
): List<TypeChoice> {
    val known = ColumnTypeCatalog.flatFor(dialect)
    val trimmed = current.trim()

    // 清单里同一个名字可能出现多次（`VARCHAR` 有「默认 255」与「不限」两条，
    // 是给长度框用的两个预设）。这里只取**第一条**置顶 —— 全部置顶的话下拉框
    // 开头会出现好几条一模一样的 `VARCHAR`，用户会以为点错了。
    val head: List<TypeChoice> =
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val hit = known.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            if (hit != null) {
                listOf(TypeChoice(hit.name, fromCatalog = true, spec = hit))
            } else {
                // 库里读出来的类型不在清单里 —— 补一个并标出来，绝不悄悄换成清单第一项
                listOf(TypeChoice(trimmed, fromCatalog = false, spec = ColumnTypeSpec(trimmed)))
            }
        }
    val rest = known.filterNot { it.name.equals(trimmed, ignoreCase = true) }
    return head + rest.map { TypeChoice(it.name, fromCatalog = true, spec = it) }
}

/** 类型下拉框的一项。[spec] 为 `null` = 该类型不在清单里（用户自填或清单没收录）。 */
data class TypeChoice(
    val name: String,
    val fromCatalog: Boolean,
    val spec: ColumnTypeSpec? = null,
)
