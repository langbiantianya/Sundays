/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect

/**
 * 方言共享工具（v2.9 #16 抽取）。
 *
 * 5 个方言实现中,PG/H2/DuckDB/SQLite 四个方言语义高度对齐（ANSI SQL 双引号 +
 * 同一套 SQL 注入防护），companion object 重复声明同一组正则 + 同一段
 * validateSqlFragment/validateOrderBy 主体，仅 DANGEROUS_KEYWORDS 按方言能力增减。
 *
 * 抽取范围:
 * - quoteWith / sanitizeIdentifier — 5 方言通用
 * - validateSqlFragment / validateOrderBy — PG / H2 / DuckDB / SQLite
 * - buildDangerousKeywordRegexes / orderByRegex — 工厂方法
 *
 * MySQL 的 validate* 用 Set.forEach 而非预编译 regex 列表、反引号而非双引号,
 * 风格不同,不在本次抽取范围。
 * H2 的 quoteIdentifier 同样是引号 + uppercase()（H2 未加引号的标识符折叠为大写，
 * 引号内保持大写才能命中 executeUpdate("CREATE TABLE t") 建出的 T）。
 */
object DialectUtil {
    // region ─── 标识符包裹 ───

    /**
     * 用 [quoteChar] 包裹 [identifier] 并转义内嵌引号。
     */
    fun quoteWith(
        identifier: String,
        quoteChar: Char,
        escapeChar: Char = quoteChar,
    ): String {
        val escaped = identifier.replace("$escapeChar", "$escapeChar$escapeChar")
        return "$quoteChar$escaped$quoteChar"
    }

    // endregion

    // region ─── 标识符清洗 ───

    /**
     * 校验标识符不包含危险字符（空字节 / 单引号 / 注释符 / 换行），失败时抛
     * `IllegalArgumentException`。
     *
     * 用在**要拼进 SQL 文本的标识符**上（表名、列名、schema 名）。与
     * [validateSqlFragment] 的分工：这里管「单个标识符本身干不干净」，
     * 那边管「一整段条件里有没有夹带命令」。**两者都过**才允许拼进语句。
     *
     * 之所以不靠引号转义兜底：引号挡得住注入，挡不住**语义错误** ——
     * 一个含换行或注释符的表名即便被正确引用，生成的 DDL 也极难排查。
     *
     * @param label 抛异常时的标签（`"table name"` / `"column name"` …），便于定位是哪一处
     */
    fun sanitizeIdentifier(
        name: String,
        label: String,
    ) {
        if (name.isBlank()) throw IllegalArgumentException("$label cannot be empty")
        if (name.contains('\u0000')) throw IllegalArgumentException("$label contains null byte")
        if (name.contains('\'')) throw IllegalArgumentException("$label contains illegal quote character")
        if (name.contains("--") || name.contains("/*")) {
            throw IllegalArgumentException("$label contains illegal comment marker")
        }
        if (name.contains('\n') || name.contains('\r')) {
            throw IllegalArgumentException("$label contains illegal newline")
        }
    }

    // endregion

    // region ─── SQL 片段校验 ───

    /** 去除单引号字符串内容（含转义引号） */
    private val QUOTED_STRING_REGEX = Regex("'(?:[^']|'')*'")

    /**
     * 危险关键词**基线** —— 5 个方言共用的那一份。
     *
     * 覆盖的是 SQL 注入最常见的入口：改数据（DML）、改结构（DDL）、
     * 跨语句拼接（UNION）、以及借系统权限做事的 GRANT / REVOKE。
     *
     * 各方言的**额外**禁用项由 [buildDangerousKeywordRegexes] 的 `extraKeywords` 传入：
     * PG 加 `COPY` / `DO`；SQLite 加 `ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` /
     * `VACUUM` / `REINDEX`；DuckDB 加 `INSTALL` / `LOAD` / `EXPORT` / `COPY` /
     * `SET` / `RESET` / `CALL`；H2 加 `MERGE`；MySQL 无额外项。
     *
     * MySQL 另维护一份 `Set` + 逐词扫描而没走本工厂 —— 它用反引号而非双引号，
     * 整体风格不同，强行合并反而要在两处各加一个开关。
     */
    public val BASE_DANGEROUS_KEYWORDS: Set<String> =
        setOf(
            "INSERT",
            "UPDATE",
            "DELETE",
            "DROP",
            "ALTER",
            "CREATE",
            "UNION",
            "EXEC",
            "EXECUTE",
            "TRUNCATE",
            "GRANT",
            "REVOKE",
        )

    /**
     * 工厂：把 [extraKeywords] 合并到 [BASE_DANGEROUS_KEYWORDS]，每个词预编译为
     * 大小写不敏感、**带词边界**的正则。
     *
     * 词边界 `\b` 是必需的：不带的话 `INSERT` 会误伤 `INSERTED_AT` 这类列名，
     * 用户完全正常的 WHERE 条件会被当成注入而拒绝执行。
     *
     * @param extraKeywords 本方言额外禁用的词；与基线取并集，重复项自动去重
     */
    public fun buildDangerousKeywordRegexes(extraKeywords: Set<String> = emptySet()): List<Regex> =
        (BASE_DANGEROUS_KEYWORDS + extraKeywords).map { kw ->
            val pattern = "\\b" + kw + "\\b"
            Regex(pattern, RegexOption.IGNORE_CASE)
        }

    /**
     * 校验 SQL 片段（WHERE 条件等）的安全性 —— 失败时抛 `IllegalArgumentException`。
     *
     * ## 算法顺序本身就是安全性的一部分
     *
     * 1. **先查空字节**。NUL 会截断 JDBC 侧的 C 字符串，后面的检查都可能被它绕过。
     * 2. **再摘掉字符串字面量**（[QUOTED_STRING_REGEX] 连转义的单引号一并处理），
     *    然后才查分号与注释符。顺序反了就会误杀 `WHERE name = 'a; b'` 这种合法值。
     * 3. **最后才扫危险关键词**。同理，`WHERE note = 'drop this'` 不该被拒 ——
     *    用户在自己的数据里写这些词是完全正常的。
     *
     * @param sql 待校验的原始片段（不是完整语句）
     * @param label 抛异常时的标签，让调用方能看出是哪一处没过
     * @param keywordRegexes 由 [buildDangerousKeywordRegexes] 生成
     * @throws IllegalArgumentException 命中任一规则
     */
    fun validateSqlFragment(
        sql: String,
        label: String,
        keywordRegexes: List<Regex>,
    ) {
        if (sql.contains('\u0000')) throw IllegalArgumentException("$label contains null byte")
        val bare = sql.replace(QUOTED_STRING_REGEX, "")
        if (bare.contains(';')) throw IllegalArgumentException("$label contains illegal character ';'")
        if (bare.contains("--") || bare.contains("/*")) throw IllegalArgumentException("$label contains illegal comment")
        val upper = bare.uppercase()
        for (regex in keywordRegexes) {
            if (regex.containsMatchIn(upper)) {
                val kw = regex.pattern.substringAfter("\\b").substringBefore("\\b")
                throw IllegalArgumentException("$label contains forbidden keyword: $kw")
            }
        }
    }

    // endregion

    // region ─── ORDER BY 校验 ───

    /**
     * 工厂：生成 ORDER BY 校验正则。
     *
     * 只允许「可选引号包裹的标识符 [可选 ASC|DESC]」的逗号分隔列表，且要求**全串匹配**：
     *
     * ```kotlin
     * DialectUtil.orderByRegex('"')   // "name" DESC, id ASC     —— ANSI 方言
     * DialectUtil.orderByRegex('`')   // `name` DESC, id ASC     —— MySQL
     * ```
     *
     * 用全串匹配而不是 `contains`，是为了堵掉 `id ASC; DROP TABLE x` 这类
     * 「前半截像 ORDER BY、后半截夹带命令」的构造。
     *
     * @param quoteChar 该方言的标识符引号：ANSI 方言传 `"`，MySQL 传反引号
     */
    public fun orderByRegex(quoteChar: Char = '"'): Regex {
        val q = Regex.escape("$quoteChar")
        val pattern = "^\\s*" + q + "?[\\w]+" + q + "?(\\s+(ASC|DESC))?(\\s*,\\s*" + q + "?[\\w]+" + q + "?(\\s+(ASC|DESC))?)?\\s*$"
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /**
     * 校验 ORDER BY 子句格式，不合法时抛 `IllegalArgumentException`。
     *
     * @param sql 待校验的 ORDER BY 子句（不含 `ORDER BY` 关键字本身）
     * @param regex 由 [orderByRegex] 生成
     */
    fun validateOrderBy(
        sql: String,
        regex: Regex,
    ) {
        if (!regex.matches(sql)) {
            throw IllegalArgumentException("Invalid ORDER BY format: $sql")
        }
    }

    // endregion
}
