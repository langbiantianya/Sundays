/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import com.langbiantianya.sundays.connection.DialectType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ColumnTypeCatalog.lengthFor] —— 读回表结构时「长度」该显示成什么。
 *
 * ## 这条断言在防什么
 *
 * 引擎从 JDBC 元数据取的那个 `size` **不是长度**：
 *
 * - `BIGINT` 的 `COLUMN_SIZE` 是 **19**（十进制位数）
 * - `INT` 是 **10**
 * - PostgreSQL 的无界 `varchar` 是 **2147483647**（JDBC 的「不限」哨兵）
 *
 * 照搬进「长度」框，用户看到的就是「BIGINT 长度 19」这种莫名其妙的东西；
 * 改别的列再保存，这个 19 还会被当成真的长度写回库里。
 *
 * 真窗口实测的现场：MySQL 上建了一张 `id BIGINT AUTO_INCREMENT PRIMARY KEY` 的表，
 * 重新打开「编辑表结构」，长度框里赫然写着 **19**。
 */
class ColumnTypeCatalogLengthTest {
    @Test
    fun `整数类型没有长度 —— 引擎给的是十进制位数不是长度`() {
        // BIGINT 的位数是 19、INT 是 10 —— 都不该显示成长度
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "BIGINT", 19))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "INT", 10))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "TINYINT", 3))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.H2, "BIGINT", 19))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.POSTGRESQL, "INTEGER", 10))
    }

    @Test
    fun `带修饰的类型名按基名判 —— INT UNSIGNED 还是整数`() {
        // MySQL 的无符号整数回的类型名是 "INT UNSIGNED"，基名仍是 INT
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "INT UNSIGNED", 10))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "BIGINT UNSIGNED", 20))
    }

    @Test
    fun `字符类型的长度原样保留`() {
        assertEquals("64", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "VARCHAR", 64))
        assertEquals("32", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "CHAR", 32))
        assertEquals("255", ColumnTypeCatalog.lengthFor(DialectType.H2, "CHARACTER VARYING", 255))
        assertEquals("30", ColumnTypeCatalog.lengthFor(DialectType.DUCKDB, "VARCHAR", 30))
    }

    @Test
    fun `精度型类型的长度保留 —— DECIMAL(10) 的 10 是精度也是长度参数`() {
        assertEquals("10", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "DECIMAL", 10))
        assertEquals("10", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "NUMERIC", 10))
    }

    @Test
    fun `JDBC 的「不限长度」哨兵不显示成长度`() {
        // PostgreSQL 的无界 varchar 回 2147483647
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.POSTGRESQL, "VARCHAR", 2147483647))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.POSTGRESQL, "TEXT", 2147483647))
    }

    @Test
    fun `清单外的扩展类型保守保留长度`() {
        // 各家方言有大量扩展类型，清单没收全 —— 宁可多显示一个数，也不吞掉真实长度
        assertEquals("30", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "GEOMETRY", 30))
    }

    @Test
    fun `非正数没有长度`() {
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "VARCHAR", 0))
        assertEquals("", ColumnTypeCatalog.lengthFor(DialectType.MYSQL, "VARCHAR", -1))
    }
}
