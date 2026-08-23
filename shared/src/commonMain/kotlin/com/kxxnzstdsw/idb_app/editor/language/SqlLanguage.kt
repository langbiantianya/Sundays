package com.kxxnzstdsw.idb_app.editor.language

import com.kxxnzstdsw.idb_app.editor.CodeLanguage
import com.kxxnzstdsw.idb_app.editor.CodeToken
import com.kxxnzstdsw.idb_app.editor.TokenType

/**
 * SQL 语言 — 支持 5 个方言共用的核心语法子集。
 *
 * ## 覆盖特性
 * - 关键字识别（**大小写不敏感**：SELECT = select = SeLeCt）
 * - 标准 SQL 关键字 + DDL / DML / DCL 子集（与本项目 5 个方言一致 — MySQL/PostgreSQL/H2/DuckDB/SQLite）
 * - 字符串字面量：`'...'`（支持 `''` 转义）、`"..."`（PG 标识符）、`` `...` ``（MySQL 标识符）
 * - 注释：`-- ...` 单行、`/* ... */` 块
 * - 数字：整数、浮点、科学计数法
 * - 标识符：以字母 / 下划线开头，后续可含字母 / 数字 / 下划线
 *
 * ## 已知限制
 * - 不解析方言语义（如不会因为在 SELECT 后就跟关键字识别成 KEYWORD — 由调用方决定语法正确性）
 * - 行内 JSON 字符串、十六进制字面量 `0x...` 不识别为特殊 token（按普通 token 流过去）
 * - 关键字集合基于 SQL:2016 + 5 方言共有的子集；方言独有语法（H2 的 `MODE`、DuckDB 的 `MACRO`）按 IDENTIFIER 处理
 */
class SqlLanguage : CodeLanguage {

    override val id: String = "sql"
    override val displayName: String = "SQL"

    override fun tokenize(source: String): List<CodeToken> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<CodeToken>(source.length / 4 + 16)
        val len = source.length
        var i = 0
        while (i < len) {
            val c = source[i]
            when {
                // 空白
                c.isWhitespace() -> {
                    val start = i
                    while (i < len && source[i].isWhitespace()) i++
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.WHITESPACE))
                }
                // 单行注释 -- ... \n
                c == '-' && i + 1 < len && source[i + 1] == '-' -> {
                    val start = i
                    i += 2
                    while (i < len && source[i] != '\n') i++
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.COMMENT))
                }
                // 块注释 /* ... */ — 支持嵌套（SQL 标准允许）
                c == '/' && i + 1 < len && source[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    var depth = 1
                    while (i < len && depth > 0) {
                        if (i + 1 < len && source[i] == '/' && source[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (i + 1 < len && source[i] == '*' && source[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                    if (depth > 0) {
                        // 未闭合 — 标记 ERROR
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.ERROR))
                    } else {
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.COMMENT))
                    }
                }
                // MySQL 反引号标识符
                c == '`' -> {
                    val start = i
                    i++
                    while (i < len && source[i] != '`') {
                        if (source[i] == '\\' && i + 1 < len) i += 2 else i++
                    }
                    if (i < len) {
                        i++  // 消费闭合反引号
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.IDENTIFIER))
                    } else {
                        // 未闭合 — 标记 ERROR
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.ERROR))
                    }
                }
                // 单引号字符串 '' -- SQL 标准转义：'' 表示一个 '
                c == '\'' -> {
                    val start = i
                    i++
                    var closed = false
                    while (i < len) {
                        when (source[i]) {
                            '\'' -> {
                                if (i + 1 < len && source[i + 1] == '\'') {
                                    // 转义 ''
                                    i += 2
                                } else {
                                    i++  // 消费闭合
                                    closed = true
                                    break
                                }
                            }
                            '\\' -> if (i + 1 < len) i += 2 else i++  // 反斜杠转义（部分方言支持）
                            else -> i++
                        }
                    }
                    out.add(
                        CodeToken(
                            start,
                            i,
                            source.substring(start, i),
                            if (closed) TokenType.STRING else TokenType.ERROR,
                        ),
                    )
                }
                // PostgreSQL 双引号标识符 — 在 SQL 标准里就是字符串，但 PG 习惯当标识符
                c == '"' -> {
                    val start = i
                    i++
                    while (i < len && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < len) i += 2 else i++
                    }
                    if (i < len) {
                        i++
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.IDENTIFIER))
                    } else {
                        out.add(CodeToken(start, i, source.substring(start, i), TokenType.ERROR))
                    }
                }
                // 数字 — 整数 / 小数 / 科学计数法
                c.isDigit() -> {
                    val start = i
                    while (i < len && source[i].isDigit()) i++
                    if (i < len && source[i] == '.') {
                        i++
                        while (i < len && source[i].isDigit()) i++
                    }
                    if (i < len && (source[i] == 'e' || source[i] == 'E')) {
                        i++
                        if (i < len && (source[i] == '+' || source[i] == '-')) i++
                        while (i < len && source[i].isDigit()) i++
                    }
                    out.add(CodeToken(start, i, source.substring(start, i), TokenType.NUMBER))
                }
                // 标识符 / 关键字
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < len && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val word = source.substring(start, i)
                    val type = when (word.uppercase()) {
                        in KEYWORDS -> TokenType.KEYWORD
                        in TYPES -> TokenType.TYPE
                        in BUILTINS -> TokenType.BUILTIN
                        else -> TokenType.IDENTIFIER
                    }
                    out.add(CodeToken(start, i, word, type))
                }
                // 操作符
                else -> {
                    val two = if (i + 1 < len) source.substring(i, i + 2) else ""
                    when {
                        two in MULTI_CHAR_OPS -> {
                            out.add(CodeToken(i, i + 2, two, TokenType.OPERATOR))
                            i += 2
                        }
                        c in SINGLE_CHAR_OPS -> {
                            out.add(CodeToken(i, i + 1, c.toString(), TokenType.OPERATOR))
                            i++
                        }
                        else -> {
                            // 默认按 PUNCTUATION 处理（括号 / 逗号 / 分号 / 点 / 等）
                            out.add(CodeToken(i, i + 1, c.toString(), TokenType.PUNCTUATION))
                            i++
                        }
                    }
                }
            }
        }
        return out
    }

    companion object {
        /** 标准 SQL 关键字（按 SQL:2016 + 5 方言共有子集）。 */
        private val KEYWORDS: Set<String> = setOf(
            // DQL
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET",
            "FETCH", "FIRST", "NEXT", "ROW", "ROWS", "ONLY", "WITH", "RECURSIVE",
            "DISTINCT", "ALL", "AS", "ON", "USING", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "OUTER", "CROSS", "NATURAL", "UNION", "INTERSECT", "EXCEPT",
            // DML
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TRUNCATE",
            "MERGE", "UPSERT", "RETURNING",
            // DDL
            "CREATE", "ALTER", "DROP", "RENAME", "TABLE", "INDEX", "VIEW", "SEQUENCE",
            "TRIGGER", "FUNCTION", "PROCEDURE", "SCHEMA", "DATABASE", "EXTENSION",
            "MATERIALIZED", "TEMPORARY", "TEMP", "UNLOGGED", "IF", "EXISTS",
            "CASCADE", "RESTRICT", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CHECK", "CONSTRAINT", "DEFAULT", "NULL", "NOT", "AUTO_INCREMENT",
            "AUTOINCREMENT", "IDENTITY", "GENERATED", "ALWAYS", "BY", "ALWAYS",
            // DCL
            "GRANT", "REVOKE", "PRIVILEGES", "PUBLIC", "OPTION",
            // TCL
            "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION",
            // 逻辑 / 比较
            "AND", "OR", "IN", "BETWEEN", "LIKE", "ILIKE", "IS", "ESCAPE", "SIMILAR",
            "TO", "ANY", "SOME",
            // 函数相关
            "OVER", "PARTITION", "WINDOW", "RANGE", "PRECEDING", "FOLLOWING",
            "UNBOUNDED", "CURRENT", "ROW",
            // 流程控制
            "CASE", "WHEN", "THEN", "ELSE", "END", "COALESCE", "NULLIF",
            "CAST", "CONVERT", "TREAT",
            // 其他
            "EXPLAIN", "ANALYZE", "VACUUM", "COPY", "DO",
        )

        /** SQL 数据类型。 */
        private val TYPES: Set<String> = setOf(
            "INT", "INTEGER", "SMALLINT", "BIGINT", "TINYINT", "MEDIUMINT",
            "DECIMAL", "NUMERIC", "FLOAT", "REAL", "DOUBLE", "PRECISION",
            "CHAR", "VARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
            "NCHAR", "NVARCHAR", "NTEXT",
            "DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR", "INTERVAL",
            "BOOLEAN", "BOOL",
            "BIT", "BINARY", "VARBINARY", "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB",
            "BYTEA",
            "UUID", "JSON", "JSONB", "XML", "ARRAY", "ENUM", "SET",
            "SERIAL", "BIGSERIAL", "SMALLSERIAL",
            "MONEY", "CURRENCY",
        )

        /** 内置函数 — 5 方言共有子集。 */
        private val BUILTINS: Set<String> = setOf(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "CEIL", "FLOOR",
            "LENGTH", "CHAR_LENGTH", "UPPER", "LOWER", "SUBSTRING", "TRIM",
            "LTRIM", "RTRIM", "REPLACE", "CONCAT", "CONCAT_WS", "SPLIT",
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "EXTRACT", "DATE_TRUNC", "DATE_ADD", "DATE_SUB", "DATEDIFF",
            "IFNULL", "ISNULL", "IF", "IIF",
            "COALESCE", "NULLIF", "GREATEST", "LEAST",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE", "LAG", "LEAD",
            "FIRST_VALUE", "LAST_VALUE",
            "CAST", "CONVERT", "TO_CHAR", "TO_DATE", "TO_NUMBER",
        )

        private val MULTI_CHAR_OPS: Set<String> = setOf(
            "<=", ">=", "<>", "!=", "==", "=>", "::", "||",
        )

        private val SINGLE_CHAR_OPS: Set<Char> = setOf(
            '=', '<', '>', '+', '-', '*', '/', '%', '|', '&', '^', '~',
        )
    }
}
