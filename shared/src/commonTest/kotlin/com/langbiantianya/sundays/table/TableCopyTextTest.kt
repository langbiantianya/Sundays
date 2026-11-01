/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 结果表「复制」生成的**文本**长什么样。
 *
 * ## 为什么这些判据打在**文本**上而不是界面上
 *
 * 复制出去的文本是**给用户粘走的东西** —— 它对不对，用户一眼就知道，
 * 而界面上的那层（菜单项、文案、反馈）都只是它的**搬运工**。
 * 所以真正的判据落在**纯函数**上：转义规则、表头要不要带、
 * NULL 变成什么 —— 这些都是**错了就没法补救**的地方
 * （粘进 Excel 之后，一行被拆成两行，用户根本看不出来）。
 *
 * ## 为什么 `NULL` 复制成**空串**
 *
 * 与引擎侧的 CSV writer 一致（`it?.toString() ?: ""`）。
 * 写成 `NULL` 字样的话，粘进 Excel 会得到一个**内容是 "NULL" 的文本格**，
 * 与字符串 `'NULL'` 分不开；而空串至少还像「没有值」。
 */
class TableCopyTextTest {
    private val columns =
        listOf(
            TableColumn(key = "region", header = "区域"),
            TableColumn(key = "n", header = "数量"),
        )

    private val rows =
        listOf(
            TableRow(id = 0, cells = mapOf("region" to "华东", "n" to 1284L)),
            TableRow(id = 1, cells = mapOf("region" to "华南", "n" to 932L)),
        )

    @Test
    fun `整表复制带表头且用制表符分隔`() {
        assertEquals(
            "区域\t数量\n华东\t1284\n华南\t932",
            tableCopyText(columns, rows),
        )
    }

    @Test
    fun `复制一行不带表头`() {
        // 表头 + 一行数据粘过去会被读成**两条记录** ——
        // 而 Excel 自己「复制一个单元格」也只给值，不给表头。
        assertEquals("华东\t1284", rowCopyText(columns, rows[0]))
    }

    @Test
    fun `NULL 复制成空串而不是 NULL 字样`() {
        val withNull = listOf(TableRow(id = 0, cells = mapOf("region" to null, "n" to "1")))
        assertEquals("区域\t数量\n\t1", tableCopyText(columns, withNull))
    }

    @Test
    fun `值里的制表符换行引号会被正确转义`() {
        // 不转义的话，一个带换行的值粘过去会被拆成**两行** ——
        // 那就是「复制出来的数据是错的」，比不复制更糟。
        val nasty =
            listOf(
                TableRow(
                    id = 0,
                    cells =
                        mapOf(
                            "region" to "a\tb",
                            "n" to "x\"y\nz",
                        ),
                ),
            )
        assertEquals(
            "区域\t数量\n\"a\tb\"\t\"x\"\"y\nz\"",
            tableCopyText(columns, nasty),
        )
    }

    @Test
    fun `不含特殊字符的值不加引号`() {
        // 无条件加引号的话，每个格子上都多出一对引号 ——
        // 粘进 Excel 会得到一列**带引号的文本**（数字也不能当数字算了）。
        assertEquals("华东\t1284", rowCopyText(columns, rows[0]))
    }

    @Test
    fun `空表只有表头`() {
        // 表头本身是有用的信息（「这张表有哪些列」）——
        // 即使没有数据行，把列名给用户也是对的。
        assertEquals("区域\t数量", tableCopyText(columns, emptyList()))
    }

    @Test
    fun `列的顺序按列定义而不是按映射顺序`() {
        // `cells` 是 Map，迭代顺序不保证；而表头与数据必须**逐列对齐**，
        // 顺序错一列的话，用户粘出来的是「地区对应数量」这种张冠李戴。
        val shuffled =
            listOf(
                TableRow(
                    id = 0,
                    cells =
                        linkedMapOf(
                            "n" to "1284",
                            "region" to "华东",
                        ),
                ),
            )
        assertEquals("区域\t数量\n华东\t1284", tableCopyText(columns, shuffled))
    }

    @Test
    fun `只有一列时不会多出制表符`() {
        val one = listOf(TableColumn(key = "region", header = "区域"))
        assertEquals("区域\n华东", tableCopyText(one, listOf(rows[0])))
    }

    @Test
    fun `缺列的行少一格而不是错位`() {
        // 少一格会让后面的列**左移** —— 而这里只有一个列，
        // 所以少一格就是空。宁可少一格，也不要把别的列的值挪过来。
        val missing = listOf(TableRow(id = 0, cells = mapOf("n" to "1284")))
        assertEquals("区域\t数量\n\t1284", tableCopyText(columns, missing))
    }
}
