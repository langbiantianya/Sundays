package com.kxxnzstdsw.sundays

/**
 * 拼 `WHERE` 子句时用到的字面量转义。
 *
 * ## 为什么必须转义
 *
 * `DataListRequest.where` 收的是**裸 SQL 片段**，没有占位符、没有参数化 ——
 * 前端把它原样交给引擎执行。表内搜索词是**用户输入**，直接拼进去就是一个
 * SQL 注入面：搜索 `'; DROP TABLE users; --` 会被引擎当成语句的一部分执行。
 *
 * 排序（`order_by`）不在这里处理 —— 引擎侧 `DatabaseDialect.validateOrderBy`
 * 已经挡了（`name; DROP TABLE users` 会被拒），前端再拼一套规则只会与它漂移。
 */
internal object SqlLiterals {

    /**
     * 把任意字符串转成可安全嵌入 SQL 的**字符串字面量**（含两侧单引号）。
     *
     * 规则：单引号翻倍。SQL 标准（以及 H2 / PG / SQLite / MySQL 默认模式）里
     * 字符串字面量内部的单引号就是靠 `''` 转义的。
     *
     * ## 刻意**不**转义 `%` 与 `_`
     *
     * 它们是 `LIKE` 的通配符，保留它们等于**允许用户写通配查询**（搜 `a%` 找所有
     * 以 a 开头）—— 那是特性不是漏洞。代价是无法做字面量搜索，若将来要「按字面搜」，
     * 得加 `ESCAPE` 子句，而各方言的默认转义字符不一致（MySQL 默认 `\`，标准 SQL 没有
     * 默认），那时候要在**方言层**解决，不是这里。
     *
     * 唯一的反斜杠处理：`\` 在 MySQL 默认模式下是转义字符，而我们要转义的目标里
     * 没有它 —— 一个以 `\` 开头的搜索词（如 Windows 路径 `C:\data`）在 MySQL 下会被
     * 解析成转义序列。这里不处理，因为：同样的字符串在 PG / SQLite / H2 下是安全的，
     * 为 MySQL 一个方言在**所有**字面量上加转义会让另外三个方言的路径反过来出错。
     * 正确做法是让 H2Dialect 这类方言在 `validateOrderBy` 旁边再加一个
     * `validateLiteral` 钩子 —— 那是引擎侧的事。
     */
    fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * 拼 `WHERE` 时该用的 CAST 目标类型 —— **逐方言**，没有公共解。
     *
     * ## 三个方言，三种答案
     *
     * - H2 / PostgreSQL / SQLite / DuckDB → `VARCHAR`
     * - **MySQL → `CHAR`**
     *
     * `CAST(… AS VARCHAR)` 在 MySQL 上是**语法错误**（`You have an error in your SQL syntax`）——
     * 它的 `CAST` 只接受 `CHAR` / `SIGNED` / `DECIMAL` / `DATE` 等，**没有 `VARCHAR`**。
     *
     * 而换成 `CHAR` 在 PostgreSQL 上又是个陷阱：`CHAR` 是 `CHARACTER(1)` 的别名，
     * `CAST(x AS CHAR)` 会**截断成 1 个字符**，于是 `LIKE '%关键词%'` 永远匹配不上任何多字词 ——
     * 比报错更难发现，因为它**不报错**，只是静默搜不出东西。
     *
     * SQLite 对未知类型名按 affinity 规则走（名字里含 `CHAR` → TEXT），所以 `CHAR` 在它那儿也对。
     *
     * ⚠️ 这条是**四方言冒烟**逼出来的：上一轮加 CAST 时在 H2 / SQLite / PG 上都验过，
     * 唯独漏了 MySQL，而当时那句「`CAST(… AS VARCHAR)` 是这几个方言的公共子集」是**错的**。
     * 写「在几个方言上都成立」之前，先让它们**真的都跑一遍**。
     */
    fun castTypeFor(dialect: com.kxxnzstdsw.sundays.connection.DialectType): String =
        when (dialect) {
            com.kxxnzstdsw.sundays.connection.DialectType.MYSQL -> "CHAR"
            else -> "VARCHAR"
        }

    /**
     * 表内搜索词 → `CAST(列 AS <castType>) LIKE '%词%'` 的完整谓词；空词返回 `null`（不过滤）。
     *
     * ## 为什么必须带**列名**、且必须 CAST
     *
     * 原先这里只返回 `"LIKE '%词%'"` —— 一个**没有左操作数**的片段。
     * `SqlLiteralsTest` 把这个片段当成正确输出断言了下来，而**整条链一次也没跑过真库**，
     * 于是发出去的 SQL 是 `WHERE LIKE '%user_25%'`，H2 / SQLite / PostgreSQL **全部语法错误**。
     * 表内搜索这个功能从上线起就没能成功执行过一次。
     *
     * 「单测全绿」在这里是个教训：它绿是因为它只测**片段的转义**，而缺陷在**片段的拼接**上 ——
     * 转义对了不等于拼出来的 SQL 是合法的。判据必须落在**发出去的那条语句**上。
     *
     * 搜索跨**所有列**（调用方用 `OR` 连起来），因为 `DATA.LIST` 的响应只回行数据、
     * **不回列类型**，没有依据挑出「文本列」—— 而用户搜的是「这张表里哪一行有这个词」。
     *
     * **CAST 不是可选的**：不带 CAST 时 `id LIKE '%2%'` 在 H2 直接类型错误，
     * 在 PostgreSQL 是 `operator does not exist: integer ~~ text`。
     * 而 CAST 的**目标类型**逐方言不同，见 [castTypeFor]。
     *
     * 放在 `TablePreviewTab.effectiveWhere()` 里调用，**不在 UI 层拼** ——
     * 拼 SQL 的地方越少，能出错的地方就越少。
     *
     * @param column 结果集里的列名（与 `TableColumn.key` 同源）
     * @param castType 目标类型，见 [castTypeFor]；传错会得到语法错误（MySQL）或静默截断（PG）
     */
    fun likeContains(column: String, term: String, castType: String = "VARCHAR"): String? {
        val col = column.trim()
        val t = term.trim()
        if (t.isEmpty() || col.isEmpty()) return null
        return "CAST($col AS $castType) LIKE ${quote("%$t%")}"
    }
}
