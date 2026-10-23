/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSetMetaData
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 联邦扫描的**类型映射**测试。
 *
 * ## 这条映射为什么这么要紧
 *
 * 一个装不下的值会让 DuckDB 在写入时**报错** —— 而那意味着
 * **这张表整张拉不下来**。所以判据只有一条：
 * **宁可类型粗一点，也不要让整次拉取失败**。
 *
 * 典型例子：MySQL 的 `INT UNSIGNED` 最大 42 亿，装不进 DuckDB 的 32 位
 * `INTEGER`（最大 21 亿）—— 按「一律 INTEGER」映射，那张表就**永远拉不下来**。
 *
 * ## 为什么用动态代理伪造元数据
 *
 * 无符号整数、精度超范围的 DECIMAL 这些分支，**H2 与 DuckDB 都造不出来**
 * （H2 没有无符号类型）。而这些恰恰是最需要守住的分支。
 * 代理一份元数据就能把它们**逐条**钉住 —— 比「造一个真的无符号列」可靠得多
 * （还不依赖某个具体数据库的版本行为）。
 */
class JdbcScanTypeMappingTest {
    /** 一列的元数据：JDBC 类型 + 是否带符号 + 精度 + 标度。 */
    private data class Column(
        val type: Int,
        val signed: Boolean = true,
        val precision: Int = 0,
        val scale: Int = 0,
    )

    private fun metaOf(vararg columns: Column): ResultSetMetaData =
        Proxy.newProxyInstance(
            ResultSetMetaData::class.java.classLoader,
            arrayOf(ResultSetMetaData::class.java),
        ) { _, method, args ->
            val index = (args?.get(0) as? Int)?.minus(1) ?: -1
            val column = columns.getOrNull(index)
            when (method.name) {
                "getColumnCount" -> columns.size
                "getColumnType" -> column?.type ?: Types.VARCHAR
                "isSigned" -> column?.signed ?: true
                "getPrecision" -> column?.precision ?: 0
                "getScale" -> column?.scale ?: 0
                "getColumnLabel" -> "c${index + 1}"
                "getColumnName" -> "c${index + 1}"
                "toString" -> "fakeMeta"
                "hashCode" -> 0
                "equals" -> false
                else -> defaultValueOf(method.returnType)
            }
        } as ResultSetMetaData

    private fun defaultValueOf(type: Class<*>): Any? =
        when (type) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Boolean::class.javaPrimitiveType -> false
            else -> null
        }

    private fun columnOf(
        column: Column,
    ): ScanColumn =
        JdbcScanTableFunction.scanColumnOf(
            metaOf(column),
            1,
        )

    @Test
    fun `the common scalar types keep their meaning`() {
        assertEquals(ScanColumnKind.BOOLEAN, columnOf(Column(Types.BOOLEAN)).kind)
        assertEquals(ScanColumnKind.INT32, columnOf(Column(Types.INTEGER)).kind)
        assertEquals(ScanColumnKind.INT64, columnOf(Column(Types.BIGINT)).kind)
        assertEquals(ScanColumnKind.DOUBLE, columnOf(Column(Types.DOUBLE)).kind)
        assertEquals(ScanColumnKind.FLOAT, columnOf(Column(Types.REAL)).kind)
        assertEquals(ScanColumnKind.DATE, columnOf(Column(Types.DATE)).kind)
        assertEquals(ScanColumnKind.TIMESTAMP, columnOf(Column(Types.TIMESTAMP)).kind)
    }

    @Test
    fun `an unsigned 32 bit integer widens instead of overflowing`() {
        // MySQL 的 `INT UNSIGNED` 最大 42 亿 —— 装不进 DuckDB 的 32 位 INTEGER。
        // 按 32 位映射的后果是**整次拉取失败**（DuckDB 写入时报错）。
        val column = columnOf(Column(Types.INTEGER, signed = false))
        assertEquals(ScanColumnKind.INT64, column.kind, "无符号 32 位必须落到 64 位")
    }

    @Test
    fun `an unsigned 64 bit integer becomes a decimal`() {
        // 有符号 64 位装不下 1.8e19 → 落到 DECIMAL(20, 0)（20 位十进制，够用）。
        val column = columnOf(Column(Types.BIGINT, signed = false))
        assertEquals(ScanColumnKind.DECIMAL, column.kind)
        assertEquals(20, column.precision)
        assertEquals(0, column.scale)
    }

    @Test
    fun `a decimal keeps its precision when duckdb can hold it`() {
        val column = columnOf(Column(Types.DECIMAL, precision = 10, scale = 2))
        assertEquals(ScanColumnKind.DECIMAL, column.kind)
        assertEquals(10, column.precision)
        assertEquals(2, column.scale)
    }

    @Test
    fun `a decimal beyond duckdb range falls back to text`() {
        // DuckDB 的 DECIMAL 上限是 38 位。精度超范围的（MySQL 允许到 65 位）
        // 落到**文本** —— 宁可让下游用 TRY_CAST 建模，也不要在拉取时炸掉。
        val column = columnOf(Column(Types.DECIMAL, precision = 65, scale = 10))
        assertEquals(ScanColumnKind.VARCHAR, column.kind, "超范围的 DECIMAL 必须落成文本")
    }

    @Test
    fun `a decimal with unknown precision falls back to text`() {
        // 精度未知（有些驱动对 `numeric` 报 0）→ 同样是文本，而不是猜一个精度。
        val column = columnOf(Column(Types.NUMERIC, precision = 0, scale = 0))
        assertEquals(ScanColumnKind.VARCHAR, column.kind)
    }

    @Test
    fun `the exotic types fall back to text`() {
        // TIME / TIMESTAMP WITH TIME ZONE / BLOB / JSON / 几何… → 文本。
        // DuckDB 的写出接口没有 TIME 与带时区时间戳的对应类型，硬套会丢信息。
        assertEquals(ScanColumnKind.VARCHAR, columnOf(Column(Types.TIME)).kind)
        assertEquals(ScanColumnKind.VARCHAR, columnOf(Column(Types.TIMESTAMP_WITH_TIMEZONE)).kind)
        assertEquals(ScanColumnKind.VARCHAR, columnOf(Column(Types.BLOB)).kind)
        assertEquals(ScanColumnKind.VARCHAR, columnOf(Column(Types.VARCHAR)).kind)
    }

    @Test
    fun `every kind maps to a duckdb type and the decimal keeps its precision`() {
        // 声明的类型与写出用的 setter **必须**对上 ——
        // 对不上时 DuckDB 会在写入时报「Expected vector type BIGINT, found INTEGER」。
        // 所以每种 kind 都得有一个能写出来的 DuckDB 类型。
        ScanColumnKind.entries.forEach { kind ->
            val type = JdbcScanTableFunction.duckDbTypeOf(ScanColumn("c", kind, precision = 10, scale = 2))
            assertTrue(type is org.duckdb.DuckDBColumnType || type is org.duckdb.DuckDBLogicalType, "$kind 没有对应的 DuckDB 类型")
        }
        // DECIMAL 必须**带上精度** —— 不带精度的 DECIMAL 会按默认精度建列，
        // 于是远端 DECIMAL(38,10) 的值写不进去（整次拉取失败）。
        val decimal = JdbcScanTableFunction.duckDbTypeOf(ScanColumn("c", ScanColumnKind.DECIMAL, precision = 38, scale = 10))
        assertTrue(decimal is org.duckdb.DuckDBLogicalType, "DECIMAL 必须用带精度的逻辑类型")
    }
}
