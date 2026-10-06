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
     * 表内搜索词 → `LIKE '%词%'` 的完整谓词；空词返回 `null`（表示不过滤）。
     *
     * 放在 `TablePreviewTab.effectiveWhere()` 里调用，**不在 UI 层拼** ——
     * 拼 SQL 的地方越少，能出错的地方就越少。
     */
    fun likeContains(term: String): String? {
        val t = term.trim()
        if (t.isEmpty()) return null
        return "LIKE ${quote("%$t%")}"
    }
}
