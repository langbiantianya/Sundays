/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.metadata

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * JDBC `DatabaseMetaData` 层面的**列元数据**读取 —— 自增标记与二进制类型码的唯一来源。
 *
 * ## 为什么要有这一层
 *
 * 「这一列是不是自增的」这件事，**各方言的 `listColumns` 都不吐**：
 *
 * - MySQL / PostgreSQL 走 [com.langbiantianya.dialect.DatabaseDialect.listColumns] 的默认实现
 *   （JDBC `getColumns`），只取 name / type / size / nullable / isPrimaryKey / defaultValue；
 * - H2 / DuckDB / SQLite 各自覆写了 `listColumns`（走 `INFORMATION_SCHEMA` 或 `PRAGMA`），
 *   同样没有 `autoIncrement` 这一项。
 *
 * 而这个信息**在 JDBC 标准里是有的**：`getColumns` 的结果集带一列 `IS_AUTOINCREMENT`。
 * 早先只有 `DataHandler` 自己去查它（`DATA.LIST` 的能力位计算要用「自增列不可插入」），
 * 于是 `TABLE.LIST`（读表结构）这条路**查不到自增标记** ——
 * 界面上的「自增」复选框永远不勾，用户改别的列再保存，就把自增**悄悄丢了**。
 *
 * 两边各查一遍又会漂移：查表名候选、H2 的 catalog/schema 口径都得同步。
 * 所以抽成这一处，两条链路共用。
 */
object JdbcColumnMetadata {
    private val logger = LoggerFactory.getLogger(JdbcColumnMetadata::class.java)

    /**
     * 读一张表的 JDBC 列元数据 —— key 是**列名的小写形式**。
     *
     * 用小写做 key：不同数据源对同一列的大小写处理不一致（H2 存大写、MySQL 存原样），
     * 调用方拿到的列名要能直接和这里对上，就必须统一到同一种口径。
     *
     * 查不到（部分驱动不支持 `getColumns`）时返回**空 map**，调用方退回方言自己给的信息；
     * **任何异常都只丢这一项**并 `logger.warn`，绝不让整条读表结构的请求失败 ——
     * 少一个「自增」勾选，远比「表结构打不开」轻。
     */
    fun load(
        conn: Connection,
        driver: String,
        database: String?,
        schema: String,
        tableName: String,
    ): Map<String, JdbcColumnMeta> {
        val result = LinkedHashMap<String, JdbcColumnMeta>()
        val (catalog, lookupSchema) = lookupScope(driver, database, schema)
        for (cand in lookupNames(driver, tableName)) {
            try {
                conn.metaData.getColumns(catalog, lookupSchema, cand, "%").use { rs ->
                    // 老驱动可能根本没有 IS_AUTOINCREMENT 列 —— 先问一次，别在每行里试错
                    val hasAutoIncrement =
                        runCatching {
                            (1..rs.metaData.columnCount).any { i ->
                                rs.metaData.getColumnName(i).equals("IS_AUTOINCREMENT", ignoreCase = true)
                            }
                        }.getOrDefault(false)
                    while (rs.next()) {
                        val name = rs.getString("COLUMN_NAME") ?: continue
                        val typeCode = rs.getInt("DATA_TYPE")
                        val typeName = rs.getString("TYPE_NAME")
                        val autoIncrement =
                            hasAutoIncrement &&
                                "YES".equals(rs.getString("IS_AUTOINCREMENT"), ignoreCase = true)
                        result.putIfAbsent(name.lowercase(), JdbcColumnMeta(typeCode, typeName ?: "", autoIncrement))
                    }
                }
            } catch (e: Exception) {
                logger.warn(
                    "getColumns failed for driver=$driver table=$cand: ${e.message}",
                    e,
                )
            }
        }
        return result
    }

    /**
     * 元数据查表名的候选名 —— 与 `DatabaseDialect.listColumns` 默认实现同一口径。
     *
     * H2 把未加引号的标识符存成大写，直接拿前端传来的小写名查 `getColumns` 会得到空结果，
     * 于是真表被当成没有列。加上大写候选即可。
     */
    fun lookupNames(
        driver: String,
        tableName: String,
    ): List<String> =
        buildList {
            add(tableName)
            if (driver.equals("H2", ignoreCase = true)) add(tableName.uppercase())
        }.distinct()

    /** 元数据查询的 (catalog, schema) —— H2 不吃 catalog 且空 schema 要补 PUBLIC，其余原样传。 */
    fun lookupScope(
        driver: String,
        database: String?,
        schema: String,
    ): Pair<String?, String> =
        if (driver.equals("H2", ignoreCase = true)) {
            null to schema.ifBlank { "PUBLIC" }
        } else {
            database?.takeIf { it.isNotBlank() } to schema
        }
}

/**
 * JDBC 元数据里的单列信息。
 *
 * @property typeCode JDBC 类型码（[java.sql.Types]）—— 二进制判定的**第一依据**。
 * @property typeName 驱动给的类型名 —— 类型码缺失时的**兜底**依据。
 * @property autoIncrement 是否自增。
 */
data class JdbcColumnMeta(
    val typeCode: Int,
    val typeName: String,
    val autoIncrement: Boolean,
)
