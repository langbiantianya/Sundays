/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.language.SqlDialectProfile

/**
 * 前端侧方言元数据 —— **一处**说清「这个方言在前端眼里是什么样」。
 *
 * ## 为什么要把它收成一张表
 *
 * 在本文件出现之前，「某个方言是什么」这件事在 `:shared` 里有**四份**互不相干的硬编码
 * `when`，散落在三个文件里：
 *
 * | 原来在哪 | 回答什么问题 |
 * |---|---|
 * | `ConnectionConfig.defaultsFor` | 默认连接类型 + 默认端口 |
 * | `DialectType.supportedConnectionTypes` | 这个方言能选哪几种连接类型 |
 * | `DialectType.engineDriverName` | 引擎那边的注册名（`Mysql` 而不是 `MYSQL`） |
 * | `table.DialectCapabilities.of` | 前端能力判断（外键 / DDL 回滚） |
 *
 * 外加 `desktopApp` 里第五份：`DialectType.toSqlDialectProfile()`（方言 → SQL 高亮档位），
 * 以及 `ConnectionManagerScreen` 里第六份（方言图标）、第七份（方言说明文案）、
 * `SqlIdentifier` 里第八份（标识符引用符）。
 *
 * 八份 `when` 意味着**加一个方言要改八个地方**，漏一个就是「界面说能连、引擎说没这个驱动」
 * 或者「连上了但 SQL 没有高亮」这类**静默错配**。仓库里已经有
 * `DialectNameContractTest` 在防其中一处漂移 —— 说明这块**确实脆**，
 * 而只防一处、其余七处靠自觉，是不保险的。
 *
 * 现在它们全部读同一张表：**加一个方言 = 加一条元数据**，不再改分支。
 *
 * ## 这张表是「前端侧」的，不是「引擎侧」的
 *
 * 引擎那边有它自己的一份（`api` 模块的 `DatabaseDialect` 的自描述属性）。
 * 两份之间的一致性由 `desktopApp` 的 `DialectNameContractTest` 对照引擎
 * `SYSTEM.LIST_DRIVERS` 的真实返回值校验 —— 沿用仓库既有的「跨模块契约靠测试钉住」做法，
 * 而不是把前端表塞进引擎（那样会让 `:shared` 依赖引擎）。
 *
 * ## 为什么 SQL 高亮档位也在这张表里
 *
 * 「方言 → 高亮档位」原本在 `desktopApp` 的 `DatabaseBrowserState` 里（第五份 `when`）。
 * 放进来是因为它和其余几项是**同一种东西**：方言的一个属性。
 *
 * 注意方向：`:shared` 的**编辑器**模块依然**不依赖** `connection` 包 ——
 * 依赖是 `connection` → `editor.language`（单向），编辑器对连接配置一无所知。
 */
data class DialectMetadata(
    /** 这个元数据描述的方言。 */
    val dialect: DialectType,
    /** 面向用户显示的名字（如 "PostgreSQL"）。 */
    val displayName: String,
    /**
     * 引擎侧注册这个方言用的名字（`Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`）。
     *
     * ⚠️ 与 [DialectType] 的常量名**大小写不同** —— 用 `Enum.name` 会让引擎抛
     * `No dialect plugin loaded for driver: MYSQL`。
     */
    val engineDriverName: String,
    /** 选中该方言时的默认连接类型。 */
    val defaultConnectionType: ConnectionType,
    /** 默认端口（表单 placeholder / 默认值）；`null` = 无默认。 */
    val defaultPort: Int?,
    /** 该方言允许选择的连接类型（连接向导第 2 步的选项）。 */
    val supportedConnectionTypes: List<ConnectionType>,
    /** 该方言是否支持外键（前端据此决定要不要画外键编辑区）。 */
    val supportsForeignKeys: Boolean,
    /** 该方言的 DDL 能不能**真的**回滚（前端据此决定能不能承诺「全成功或全不做」）。 */
    val ddlRollbackable: Boolean,
    /** 该方言对应的 SQL 高亮档位。 */
    val sqlDialectProfile: SqlDialectProfile,
    /**
     * 该方言的 JDBC URL 前缀（如 `jdbc:mysql:`）—— 用于从**裸 JDBC URL** 反查方言。
     *
     * ⚠️ **M3 的 Hive / Impala 会共用 `jdbc:hive2:`** —— 两者走同一个 HiveServer2 协议、
     * 用同一个 `hive-jdbc` 驱动，URL 形状本就相同，前缀**不唯一**。
     * 所以「按 URL 前缀反查方言」这件事**必须有兜底**：前缀命中多个方言时，
     * 以连接配置里显式的 `dialect` 字段（用户意图的真相源）为准。
     */
    val jdbcUrlPrefix: String,
    /** 连接列表里这个方言的图标。 */
    val icon: ImageVector,
    /** 方言说明文案的键；`null` = 没有说明（未知方言）。 */
    val descriptionKey: StrKey?,
    /**
     * 标识符引用符（`"` 或 `` ` ``）。
     *
     * MySQL / Hive / Impala 用**反引号**，其余用 ANSI 双引号 ——
     * MySQL 那边不是口味问题：ANSI 双引号在 MySQL 默认是**字符串字面量**，
     * 写成 `"biz_user"` 会被当成 `SELECT 'biz_user'` 而不是标识符。
     * Hive / Impala 沿用反引号是因为它们**不是** ANSI 方言。
     */
    val identifierQuote: String,
) {
    companion object {
        /**
         * 全部方言的元数据 —— **唯一真相源**。
         *
         * 用 `mapOf` 而不是 `when`：新增方言是**加一行**，不是**加一个分支** ——
         * 后者总有一天会有人忘。
         */
        private val TABLE: Map<DialectType, DialectMetadata> =
            mapOf(
                DialectType.MYSQL to
                    DialectMetadata(
                        dialect = DialectType.MYSQL,
                        displayName = "MySQL",
                        engineDriverName = "Mysql",
                        defaultConnectionType = ConnectionType.CLIENT_SERVER,
                        defaultPort = 3306,
                        supportedConnectionTypes = listOf(ConnectionType.CLIENT_SERVER, ConnectionType.CUSTOM_URL),
                        supportsForeignKeys = true,
                        // MySQL 对 DDL 隐式提交（引擎 KDoc 已写明是软支持）
                        ddlRollbackable = false,
                        sqlDialectProfile = SqlDialectProfile.MYSQL,
                        jdbcUrlPrefix = "jdbc:mysql:",
                        icon = Icons.Default.Storage,
                        descriptionKey = StrKey.DIALECT_MYSQL_DESC,
                        identifierQuote = "`",
                    ),
                DialectType.POSTGRESQL to
                    DialectMetadata(
                        dialect = DialectType.POSTGRESQL,
                        displayName = "PostgreSQL",
                        engineDriverName = "Postgresql",
                        defaultConnectionType = ConnectionType.CLIENT_SERVER,
                        defaultPort = 5432,
                        supportedConnectionTypes = listOf(ConnectionType.CLIENT_SERVER, ConnectionType.CUSTOM_URL),
                        supportsForeignKeys = true,
                        // PG 是内置方言里真正支持事务性 DDL 的。
                        // ⚠️ 本机没有 PG 实例，这条**未经实测**，只有业界共识；
                        // 若哪天真接了 PG，先跑一遍「BEGIN → ALTER → ROLLBACK」再改。
                        ddlRollbackable = true,
                        sqlDialectProfile = SqlDialectProfile.POSTGRESQL,
                        jdbcUrlPrefix = "jdbc:postgresql:",
                        icon = Icons.Default.Storage,
                        descriptionKey = StrKey.DIALECT_POSTGRESQL_DESC,
                        identifierQuote = "\"",
                    ),
                DialectType.H2 to
                    DialectMetadata(
                        dialect = DialectType.H2,
                        displayName = "H2",
                        engineDriverName = "H2",
                        defaultConnectionType = ConnectionType.IN_MEMORY,
                        defaultPort = null,
                        supportedConnectionTypes =
                            listOf(ConnectionType.IN_MEMORY, ConnectionType.FILE_BASED, ConnectionType.CUSTOM_URL),
                        supportsForeignKeys = true,
                        // 实测：CREATE TABLE 一执行就对其它连接可见，压根没进事务。
                        // 回归见 DdlTransactionIntegrationTest「H2 does not roll DDL back」。
                        ddlRollbackable = false,
                        sqlDialectProfile = SqlDialectProfile.H2,
                        jdbcUrlPrefix = "jdbc:h2:",
                        icon = Icons.Default.Memory,
                        descriptionKey = StrKey.DIALECT_H2_DESC,
                        identifierQuote = "\"",
                    ),
                DialectType.DUCKDB to
                    DialectMetadata(
                        dialect = DialectType.DUCKDB,
                        displayName = "DuckDB",
                        engineDriverName = "Duckdb",
                        defaultConnectionType = ConnectionType.EMBEDDED,
                        defaultPort = null,
                        supportedConnectionTypes = listOf(ConnectionType.EMBEDDED, ConnectionType.CUSTOM_URL),
                        supportsForeignKeys = true,
                        ddlRollbackable = false,
                        sqlDialectProfile = SqlDialectProfile.DUCKDB,
                        jdbcUrlPrefix = "jdbc:duckdb:",
                        icon = Icons.Default.Analytics,
                        descriptionKey = StrKey.DIALECT_DUCKDB_DESC,
                        identifierQuote = "\"",
                    ),
                DialectType.SQLITE to
                    DialectMetadata(
                        dialect = DialectType.SQLITE,
                        displayName = "SQLite",
                        engineDriverName = "Sqlite",
                        defaultConnectionType = ConnectionType.FILE_BASED,
                        defaultPort = null,
                        supportedConnectionTypes = listOf(ConnectionType.FILE_BASED, ConnectionType.CUSTOM_URL),
                        supportsForeignKeys = true,
                        ddlRollbackable = false,
                        sqlDialectProfile = SqlDialectProfile.SQLITE,
                        jdbcUrlPrefix = "jdbc:sqlite:",
                        icon = Icons.Default.FolderOpen,
                        descriptionKey = StrKey.DIALECT_SQLITE_DESC,
                        identifierQuote = "\"",
                    ),
                // M3 会在这里加两行：Hive（默认端口 10000）与 Impala（21050）。
                // 两者**共用** `jdbc:hive2:` 前缀与同一个 `hive-jdbc` 驱动 ——
                // 消歧靠连接配置里显式的 `dialect` / `driver` 字段（用户意图的真相源），
                // 详见设计文档 §4.6 的 D1~D4。
                //
                // 为什么 M1 不加：引擎里还没有这两个方言插件，加进来会让
                // 「界面说有、引擎没有」—— 仓库的 `DialectNameContractTest` 会立刻红。
            )

        /**
         * 未知方言的元数据 —— **最保守**的一份。
         *
         * 没连上库 / 连不上时界面上的写入口应当一律收紧 ——
         * 这是与「老引擎一律按只读处理」同一个原则：**宁可少给，不给一个点了必然失败的东西**。
         */
        private val UNKNOWN =
            DialectMetadata(
                dialect = DialectType.UNKNOWN,
                displayName = "Unknown",
                engineDriverName = DialectType.UNKNOWN.name,
                defaultConnectionType = ConnectionType.UNKNOWN,
                defaultPort = null,
                supportedConnectionTypes = emptyList(),
                supportsForeignKeys = false,
                ddlRollbackable = false,
                sqlDialectProfile = SqlDialectProfile.STANDARD,
                jdbcUrlPrefix = "jdbc:",
                icon = Icons.Default.QuestionMark,
                descriptionKey = null,
                identifierQuote = "\"",
            )

        /** 取某个方言的元数据；未知方言返回 [UNKNOWN] 那份保守值。 */
        fun of(dialect: DialectType): DialectMetadata = TABLE[dialect] ?: UNKNOWN

        /** 全部已知方言的元数据（按 [DialectType] 的声明顺序）。 */
        val all: List<DialectMetadata> = DialectType.entries.map { of(it) }
    }
}
