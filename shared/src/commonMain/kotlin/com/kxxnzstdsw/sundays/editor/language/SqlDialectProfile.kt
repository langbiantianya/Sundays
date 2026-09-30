package com.kxxnzstdsw.sundays.editor.language

/**
 * SQL 方言档位 —— 决定 SQL 编辑器用哪一套**关键字 / 类型 / 内置函数**。
 *
 * ## 与基线的关系
 * 基线（[SqlLanguage] 里的 SQL:2016 共有子集）在**所有**档位都生效；本档位只追加该方言
 * **特有**的词。三类词在一个档位内**必须互斥**（同一个词不能既算 keyword 又算 builtin）——
 * tokenize 的判定顺序是 KEYWORD → TYPE → BUILTIN，重复归类会静默改变高亮颜色。
 * 该不变量由 `SqlDialectProfileTest` 强制。
 *
 * ## 为什么 [languageId] 就是 [com.kxxnzstdsw.sundays.editor.CodeLanguage.id]
 * 注册表按 id 索引语言，formatter 也按同一个 id 索引 —— 因此「方言」在这里就是**一种语言**：
 * 换方言 = 换 `languageId`。这样编辑器组件（`CodeEditor` / `CodeEditorWithToolbar`）零改动，
 * 且格式化按钮能自动找到方言档位的 formatter（若另起一套映射，格式化会因找不到 formatter 而禁用）。
 *
 * ## 与连接配置的关系
 * 本文件在 `:shared` 的编辑器模块里，**不依赖** `connection.DialectType` —— 「连接的方言 →
 * 档位」的映射由 desktopApp 决定（它同时看得见两侧），避免编辑器模块耦合连接配置。
 */
data class SqlDialectProfile(
    /** [com.kxxnzstdsw.sundays.editor.CodeLanguage.id]（注册表主键），也是 formatter 的 languageId。 */
    val languageId: String,
    /** 语言下拉框 / 工作台标题条上显示的名字。 */
    val displayName: String,
    /** 方言特有保留字 —— 追加到基线关键字。 */
    val keywords: Set<String> = emptySet(),
    /** 方言特有类型名 —— 追加到基线类型。 */
    val types: Set<String> = emptySet(),
    /** 方言特有内置函数 —— 追加到基线内置函数。 */
    val builtins: Set<String> = emptySet(),
) {
    companion object {
        /** 标准 SQL（SQL:2016 共有子集）—— 未连接 / 未知方言时使用；也是 `CodeLanguageRegistry` 里的 `"sql"`。 */
        val STANDARD = SqlDialectProfile(
            languageId = "sql",
            displayName = "SQL",
        )

        /** MySQL 8.x —— 管理语句、存储引擎 / 字符集子句、MySQL 专有函数。 */
        val MYSQL = SqlDialectProfile(
            languageId = "sql-mysql",
            displayName = "MySQL",
            keywords = setOf(
                // 管理 / 会话语句
                "SHOW", "DESCRIBE", "USE", "FLUSH", "RESET", "PURGE", "LOCK", "UNLOCK",
                // 优化器 / 写入提示
                "IGNORE", "FORCE", "STRAIGHT_JOIN", "SQL_CALC_FOUND_ROWS",
                "HIGH_PRIORITY", "LOW_PRIORITY", "DELAYED",
                // 列定义子句
                "ZEROFILL", "UNSIGNED", "COLLATE", "CHARACTER", "CHARSET",
                // DDL 子句
                "ENGINE", "ALGORITHM", "ROW_FORMAT", "CHANGE", "MODIFY", "AFTER", "BEFORE",
                // 触发器体
                "EACH", "NEW", "OLD",
                // 运算符
                "DIV", "XOR",
            ),
            types = setOf("GEOMETRY", "POINT", "POLYGON", "LINESTRING"),
            builtins = setOf(
                "GROUP_CONCAT", "FOUND_ROWS", "LAST_INSERT_ID",
                "DATE_FORMAT", "STR_TO_DATE", "UNIX_TIMESTAMP", "FROM_UNIXTIME", "TIMESTAMPDIFF",
                "TIMESTAMPADD", "WEEKDAY", "DAYOFWEEK", "DAYOFMONTH", "MONTHNAME", "QUARTER",
                "LOCATE", "INSTR", "FIELD", "ELT",
                "JSON_EXTRACT", "JSON_OBJECT", "JSON_ARRAY",
                "MD5", "SHA1", "SHA2", "RAND",
            ),
        )

        /** PostgreSQL 12+ —— 幂等 / 并发 DDL、正则与数组函数、PG 专有类型。 */
        val POSTGRESQL = SqlDialectProfile(
            languageId = "sql-postgresql",
            displayName = "PostgreSQL",
            keywords = setOf(
                // 查询子句
                "LATERAL", "TABLESAMPLE", "NULLS", "LAST", "FILTER", "WITHIN", "GROUPING",
                "CUBE", "ROLLUP", "SETS",
                // 写入冲突
                "CONFLICT", "NOTHING",
                // 函数 / 类型定义
                "VARIADIC", "INOUT", "RETURNS", "STRICT", "COST", "STORED", "VIRTUAL",
                // 对象与维护
                "RULE", "POLICY", "ROLE", "TABLESPACE", "CONCURRENTLY", "REFRESH",
                "INHERITS", "OWNED", "REINDEX", "CLUSTER",
            ),
            types = setOf(
                "INET", "CIDR", "MACADDR", "TSVECTOR", "TSQUERY", "TIMESTAMPTZ", "MULTIRANGE",
                "LINE", "LSEG", "BOX", "PATH", "CIRCLE",
            ),
            builtins = setOf(
                "GENERATE_SERIES", "UNNEST", "ARRAY_LENGTH", "ARRAY_AGG", "STRING_AGG",
                "DATE_PART", "AGE", "PG_SLEEP", "PG_TYPEOF",
                "REGEXP_REPLACE", "REGEXP_MATCHES", "RANDOM", "GEN_RANDOM_UUID",
                "JSONB_AGG", "JSONB_BUILD_OBJECT", "TO_JSONB",
            ),
        )

        /** H2 2.x —— `MODE`、CSV 导入导出、H2 专有函数。 */
        val H2 = SqlDialectProfile(
            languageId = "sql-h2",
            displayName = "H2",
            keywords = setOf(
                "MODE", "SET_MODE", "SCRIPT", "BACKUP", "CSVWRITE", "CSVREAD", "RECOMPILE",
                "MEMORY", "CACHED", "AUTO_SERVER", "AUTOCOMMIT", "ALIAS", "USER", "PASSWORD",
            ),
            types = setOf("VARCHAR_IGNORECASE", "GEOMETRY"),
            builtins = setOf(
                "H2VERSION", "RANDOM_UUID", "FILE_READ", "FILE_WRITE",
                "DATEADD", "PARSEDATETIME", "FORMATDATETIME",
                "HASH", "COMPRESS", "EXPAND", "RAWTOHEX", "HEXTORAW", "TRUNCATE_VALUE",
            ),
        )

        /** DuckDB 1.x —— `QUALIFY` / `PIVOT`、宏、文件读取函数与嵌套类型。 */
        val DUCKDB = SqlDialectProfile(
            languageId = "sql-duckdb",
            displayName = "DuckDB",
            keywords = setOf(
                "PIVOT", "UNPIVOT", "QUALIFY", "SUMMARIZE", "EXCLUDE",
                "ASOF", "POSITIONAL", "SEMI", "ANTI", "ATTACH", "DETACH", "MACRO",
            ),
            types = setOf(
                "HUGEINT", "UHUGEINT", "UINTEGER", "UBIGINT", "USMALLINT", "UTINYINT",
                "LIST", "STRUCT", "MAP", "BITSTRING",
            ),
            builtins = setOf(
                "QUANTILE_CONT", "QUANTILE_DISC", "LIST_AGG", "HISTOGRAM", "STR_SPLIT",
                "DATE_DIFF", "EPOCH", "EPOCH_MS",
                "READ_CSV", "READ_PARQUET", "READ_JSON",
            ),
        )

        /** SQLite 3.x —— `PRAGMA`、附加数据库、冲突处理与日期 / JSON 函数。 */
        val SQLITE = SqlDialectProfile(
            languageId = "sql-sqlite",
            displayName = "SQLite",
            keywords = setOf(
                "PRAGMA", "GLOB", "WITHOUT", "ROWID", "REINDEX", "STRICT",
                "ATTACH", "DETACH",
                // 冲突解决与触发器体
                "RAISE", "ABORT", "FAIL", "INSTEAD",
                // 事务模式
                "DEFERRED", "IMMEDIATE", "EXCLUSIVE",
            ),
            builtins = setOf(
                "STRFTIME", "JULIANDAY", "UNIXEPOCH", "FORMAT", "PRINTF",
                "LIKELY", "UNLIKELY", "LIKELIHOOD",
                "CHANGES", "TOTAL_CHANGES", "LAST_INSERT_ROWID",
                "SQLITE_VERSION", "LOAD_EXTENSION",
                "JSON_EXTRACT", "JSON_OBJECT", "JSON_ARRAY", "GROUP_CONCAT",
            ),
        )

        /** 全部档位 —— 注册顺序即 [STANDARD] 在前（`"sql"` 为基集语言）。 */
        val ALL: List<SqlDialectProfile> = listOf(STANDARD, MYSQL, POSTGRESQL, H2, DUCKDB, SQLITE)
    }
}
