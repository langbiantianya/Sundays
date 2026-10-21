/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import com.langbiantianya.sundays.connection.DialectType

/**
 * 一个可选的字段类型 —— 「表结构编辑」弹窗里类型下拉框的一项。
 *
 * @property name 写进 proto `ColumnDef.type` 的类型名。用户可以直接看到它并改成别的，
 *   所以**必须是数据库真的认识的名字**，不能是内部别名。
 * @property takesLength 该类型是否接受长度 / 精度参数（`VARCHAR(64)` 里的那个 64）。
 * @property defaultLength 勾选 [takesLength] 时预填的值；`null` = 留空让用户自己填
 *   （MySQL 的 `TEXT` 不接受长度，PostgreSQL 的 `VARCHAR` 不写长度表示不限，两者都该留空）。
 * @property note 备注。**只写用户不看就会踩的坑**，别把类型文档抄进来。
 */
data class ColumnTypeSpec(
    val name: String,
    val takesLength: Boolean = false,
    val defaultLength: Int? = null,
    val note: String? = null,
)

/** 类型下拉框的分组 —— 按「用来存什么」分，而不是按类型名字母序。 */
enum class ColumnTypeGroup {
    INTEGER,
    DECIMAL,
    STRING,
    BINARY,
    DATE_TIME,
    BOOLEAN,
    OTHER,
}

/** 一组带分组的类型。 */
data class ColumnTypeEntry(
    val group: ColumnTypeGroup,
    val specs: List<ColumnTypeSpec>,
)

/**
 * 各方言的字段类型清单。
 *
 * ## 为什么不用 SQL 高亮器的那份类型词表
 *
 * `SqlLanguage` 里确实有一份 `BASE_TYPES`，但它有两个问题让它不能直接拿来当下拉框：
 *
 * 1. **粒度不对。** 里面把 `PRECISION`、`INTERVAL`、`DOUBLE` 拆成了独立项 ——
 *    那是为了让高亮器认得 `DOUBLE PRECISION` 的前半截，可 `PRECISION` 本身不是类型，
 *    放进下拉框点下去就是一条建不出来的列。
 * 2. **不区分方言。** 它是「超集」，各家的专有类型混在一起；SQLite 的 `REAL` 和
 *    MySQL 的 `MEDIUMINT` 会同时出现在同一张清单里。
 *
 * 高亮器要的是「认得出来」，下拉框要的是「挑了就能建出来」—— 两者要的东西相反。
 *
 * ## 清单是**建议**不是**白名单**
 *
 * 用户仍可以输入清单里没有的类型名（SQL 的类型空间是开放的，各家方言还有大量
 * 扩展类型）。强行只让选清单内的，会让「这个类型我们没收录」变成一道墙。
 * 所以下拉框给的是默认值与提示，不是限制。
 */
object ColumnTypeCatalog {
    /** 一个不写长度就完全成立的类型。 */
    private fun t(name: String) = ColumnTypeSpec(name)

    /** 一个不带长度的类型（带备注）。 */
    private fun t(
        name: String,
        note: String,
    ) = ColumnTypeSpec(name, note = note)

    /** 一个带默认长度的类型 —— 勾上长度框就会预填它。 */
    private fun t(
        name: String,
        length: Int,
    ) = ColumnTypeSpec(name, takesLength = true, defaultLength = length)

    /**
     * 绝大多数方言共有的部分。
     *
     * 取的是**交集**：只要有一个内置方言不认，就不放进共用段 —— 共用段里的类型
     * 在任何方言下都该能建出来。
     */
    private val COMMON =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(
                    t("TINYINT"),
                    t("SMALLINT"),
                    t("INT"),
                    t("INTEGER"),
                    t("BIGINT"),
                ),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.DECIMAL,
                listOf(
                    ColumnTypeSpec("DECIMAL", takesLength = true, defaultLength = 10),
                    ColumnTypeSpec("NUMERIC", takesLength = true, defaultLength = 10),
                    ColumnTypeSpec("FLOAT", takesLength = true, defaultLength = null),
                    t("DOUBLE"),
                    t("REAL"),
                ),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.STRING,
                listOf(
                    ColumnTypeSpec("CHAR", takesLength = true, defaultLength = 1),
                    ColumnTypeSpec("VARCHAR", takesLength = true, defaultLength = 255),
                    t("TEXT"),
                    ColumnTypeSpec("VARCHAR", takesLength = true, defaultLength = null, note = "不填长度"),
                ),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.BINARY,
                listOf(t("BINARY", 16), t("VARBINARY", 255), t("BLOB")),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.DATE_TIME,
                listOf(t("DATE"), t("TIME"), t("DATETIME"), t("TIMESTAMP")),
            ),
            ColumnTypeEntry(ColumnTypeGroup.BOOLEAN, listOf(t("BOOLEAN"), t("BOOL"))),
        )

    private val MYSQL_ONLY =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(
                    t("MEDIUMINT"),
                    t(
                        "AUTO_INCREMENT",
                        note = "自增；与主键一起用时最省事",
                    ),
                ),
            ),
            ColumnTypeEntry(ColumnTypeGroup.STRING, listOf(t("TINYTEXT"), t("MEDIUMTEXT"), t("LONGTEXT"))),
            ColumnTypeEntry(
                ColumnTypeGroup.BINARY,
                listOf(t("TINYBLOB"), t("MEDIUMBLOB"), t("LONGBLOB")),
            ),
            ColumnTypeEntry(ColumnTypeGroup.DATE_TIME, listOf(t("YEAR", 4))),
            ColumnTypeEntry(ColumnTypeGroup.OTHER, listOf(t("JSON"))),
        )

    private val PG_ONLY =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(
                    t("SERIAL", note = "自增主键：INT + 序列一步到位"),
                    t("BIGSERIAL", note = "BIGINT 自增"),
                    t("SMALLSERIAL", note = "SMALLINT 自增"),
                ),
            ),
            ColumnTypeEntry(ColumnTypeGroup.STRING, listOf(t("TEXT"), t("VARCHAR", 255))),
            ColumnTypeEntry(ColumnTypeGroup.BINARY, listOf(t("BYTEA", note = "PostgreSQL 的二进制类型"))),
            ColumnTypeEntry(ColumnTypeGroup.OTHER, listOf(t("UUID"), t("JSONB"), t("JSON"))),
        )

    private val H2_ONLY =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(
                    t("IDENTITY", note = "H2 的自增写法：IDENTITY 或 GENERATED BY DEFAULT AS IDENTITY"),
                    t("SERIAL"),
                    t("BIGSERIAL"),
                ),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.STRING,
                listOf(
                    t("CLOB", note = "大文本；H2 里等价于 CHARACTER LARGE OBJECT"),
                    ColumnTypeSpec("VARCHAR", takesLength = false, note = "不填长度 = 不限"),
                ),
            ),
            ColumnTypeEntry(
                ColumnTypeGroup.BINARY,
                listOf(t("BINARY LARGE OBJECT", note = "大二进制；简写 BLOB / VARBINARY 也行")),
            ),
            ColumnTypeEntry(ColumnTypeGroup.OTHER, listOf(t("UUID"))),
        )

    private val DUCKDB_ONLY =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(t("HUGEINT", note = "128 位整数"), t("UTINYINT"), t("USMALLINT"), t("UINTEGER"), t("UBIGINT")),
            ),
            ColumnTypeEntry(ColumnTypeGroup.BINARY, listOf(t("BLOB"), t("BYTEA"))),
            ColumnTypeEntry(ColumnTypeGroup.OTHER, listOf(t("UUID"), t("JSON"))),
        )

    /**
     * SQLite 只有**类型亲和性**（affinity），没有真正的类型系统。
     *
     * 清单因此短得反常：列出 `INTEGER` / `REAL` / `TEXT` / `BLOB` / `NUMERIC` 五种亲和性
     * 就够了，其余写法 SQLite 都会按规则归到其中一种。把 `VARCHAR` / `DATETIME` 之类
     * 列进去反而是误导 —— 它们在 SQLite 里不是类型，只是决定亲和性的**关键字**。
     */
    private val SQLITE_ONLY =
        listOf(
            ColumnTypeEntry(
                ColumnTypeGroup.INTEGER,
                listOf(
                    ColumnTypeSpec("INTEGER", note = "含 INT / BIGINT / SMALLINT 等一切整数写法"),
                ),
            ),
            ColumnTypeEntry(ColumnTypeGroup.DECIMAL, listOf(t("REAL"))),
            ColumnTypeEntry(
                ColumnTypeGroup.STRING,
                listOf(ColumnTypeSpec("TEXT", note = "含 VARCHAR / CHAR / CLOB 等一切字符串写法")),
            ),
            ColumnTypeEntry(ColumnTypeGroup.BINARY, listOf(t("BLOB"))),
        )

    /**
     * 取某个方言的类型清单（已按 [ColumnTypeGroup] 排好序）。
     *
     * [DialectType.UNKNOWN] 走共用段：没连上库时也要能填点什么，
     * 否则用户改个连接类型才发现下拉框是空的。
     */
    fun forDialect(dialect: DialectType): List<ColumnTypeEntry> {
        // ⚠️ SQLite 是**唯一**不拼通用段的方言：它没有真正的类型系统，只有亲和性。
        // 通用段里的 `TINYINT` / `AUTO_INCREMENT` / `VARBINARY` 在 SQLite 里不是类型，
        // 只是决定亲和性的关键字 —— 把它们列进下拉框，用户点下去建出来的列
        // 与他以为的不是一回事。所以 SQLite 只给那 4 个亲和性。
        if (dialect == DialectType.SQLITE) return SQLITE_ONLY
        val extra =
            when (dialect) {
                DialectType.MYSQL -> MYSQL_ONLY

                DialectType.POSTGRESQL -> PG_ONLY

                DialectType.H2 -> H2_ONLY

                DialectType.DUCKDB -> DUCKDB_ONLY

                DialectType.SQLITE -> SQLITE_ONLY

                // M3 会在这里加 Hive / Impala：只读数仓方言，引擎里没有写入路径，
                // 「表结构编辑」这条界面根本不该出现在它们身上。
                // 届时给一个空清单（而不是共用段）—— 清单非空就意味着「可以选类型建表」，
                // 那是在给一个必然失败的入口发通行证。

                DialectType.UNKNOWN -> emptyList()
            }
        // 方言专有段插在共用段**之后**：共用段都是安全选择，专有段是「这个库特有的」，
        // 放前面会让用户先看到一堆 VECTOR / GEOMETRY 之类他多半用不上的东西。
        return COMMON + extra
    }

    /** 展平成一维，供类型下拉框直接用。 */
    fun flatFor(dialect: DialectType): List<ColumnTypeSpec> = forDialect(dialect).flatMap { it.specs }

    /**
     * 已知某个实际类型名属于哪一段 —— 用来给「从库里读出来的旧表」匹配默认值。
     *
     * 大小写不敏感：H2 把未加引号的列名与类型都归一为大写，而用户自己敲的可能是小写。
     */
    fun specFor(
        dialect: DialectType,
        typeName: String,
    ): ColumnTypeSpec? {
        val wanted = typeName.trim().uppercase()
        return flatFor(dialect).firstOrNull { it.name.uppercase() == wanted }
    }

    /**
     * 读回表结构时，**长度**该显示成什么 —— 引擎给的 `size` **不是**长度。
     *
     * ## 为什么不能照搬
     *
     * 引擎从 JDBC 元数据取的那个数字，对不同类型含义完全不同：
     *
     * | 实际类型 | 引擎给的 `size` | 是不是长度 |
     * |---|---|---|
     * | `VARCHAR(64)` | 64 | **是** |
     * | `BIGINT` | 19 | **不是** —— 那是十进制位数 |
     * | `INT` | 10 | 不是 —— 位数 |
     * | PostgreSQL 的无界 `varchar` | 2147483647 | 不是 —— JDBC 的「不限」哨兵 |
     *
     * 照搬进「长度」框，用户就会看到「BIGINT 长度 19」这种莫名其妙的东西；
     * 改别的列再保存，这个 19 还会被当成真的长度写回去。
     *
     * ## 判据
     *
     * 1. `size <= 0` → 没有长度。
     * 2. `size >=` [UNLIMITED_LENGTH_THRESHOLD] → 视为「不限长度」（JDBC 的哨兵值）→ 没有长度。
     * 3. 类型在清单里且 [ColumnTypeSpec.takesLength] 为 `false` → 这个类型不接受长度参数 → 没有长度。
     * 4. 类型在清单里且接受长度 → 就是它。
     * 5. 类型**不在**清单里（各家方言的大量扩展类型）→ 保守保留 —— 扩展类型里
     *    `VARCHAR(30)` 这类带长度的很常见，宁可多显示一个数，也不要把真实长度吞掉。
     *
     * @param typeName 库里读出来的原始类型名 —— 可能是 `INT UNSIGNED`、`DECIMAL(10,2)`
     *   这种带修饰的写法，比较时只取**基名**（空格与括号之前的那一段）。
     */
    fun lengthFor(
        dialect: DialectType,
        typeName: String,
        size: Int,
    ): String {
        if (size <= 0 || size >= UNLIMITED_LENGTH_THRESHOLD) return ""
        val base = typeName.trim().substringBefore(' ').substringBefore('(')
        val spec = specFor(dialect, base)
        if (spec != null && !spec.takesLength) return ""
        return size.toString()
    }

    /**
     * JDBC 用来表示「长度不限」的哨兵值的下界。
     *
     * PostgreSQL 的无界 `varchar` 在 `getColumns` 里回 `COLUMN_SIZE = 2147483647`
     * （`Integer.MAX_VALUE`）。真实的字符类型长度在实践中远小于这个量级
     * （MySQL 的 `VARCHAR` 上限 65535，`DECIMAL` 精度上限 65），
     * 所以拿它当分界线是安全的。
     */
    private const val UNLIMITED_LENGTH_THRESHOLD = 1_000_000
}
