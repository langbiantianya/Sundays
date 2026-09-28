package com.kxxnzstdsw.engine

/**
 * SQL 脚本分句器。
 *
 * 多语句执行的关键在于「词法层面」切分：直接 `split(";")` 会把字符串字面量、引用标识符、
 * 注释、PostgreSQL dollar-quoted 函数体里的分号当作语句边界，从而把一段合法的 SQL 拆坏。
 * 因此这里实现一个单趟扫描的词法状态机，只在 **顶层**（不在任何引号/注释内）遇到 `;` 时切分。
 *
 * 支持的词法构造：
 * - 单引号字符串 `'...'`：`''` 与反斜杠转义均被识别。
 * - 双引号标识符 `"..."`（PostgreSQL）：`""` 转义。
 * - 反引号标识符 `` `...` ``（MySQL）：双反引号转义。
 * - 行注释 `-- ...` 与 `# ...`（MySQL），至行尾结束。
 * - 块注释（可跨行）；未闭合时把剩余输入整体视为注释内容（而不是报错或死循环），
 *   该尾部文本会作为一条（仅含注释的）语句保留。
 * - PostgreSQL dollar-quoting：`$tag$ ... $tag$`（tag 可省略即 `$$`），闭合 tag 必须与开启 tag 完全一致。
 *
 * **嵌套块注释不支持**：PostgreSQL 允许块注释嵌套（形如「两个左标记、两个右标记」），
 * 而 MySQL/SQLite 不允许，本项目需要同时覆盖这两类方言。选择「不嵌套」意味着在 PostgreSQL 中
 * 遇到嵌套写法时，第一个右标记之后的文本会被视为正常 SQL —— 这比「多吞掉一段内容」更安全，
 * 误判方向不会造成静默的数据丢失。
 *
 * **注释文本本身会被原样保留在语句中**，而不是被剔除：MySQL 的「可执行注释」（左标记后紧跟叹号）携带
 * 真实语义，静默删掉注释等于改变脚本语义。注释的作用仅在于其内部的 `;` 不构成语句边界。
 *
 * 空白语句（如 `;;` 产生的空片段、或纯空白）会被丢弃，因为它不携带任何语义；
 * 但**引号内为空的语句会被保留**（例如脚本就是单个 `';'`），因为丢弃它等于改变程序语义。
 */
object SqlScriptSplitter {

    /**
     * 将脚本切分为顶层语句列表，逐条 trim 并丢弃空片段。
     */
    fun split(script: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        val n = script.length

        while (i < n) {
            val c = script[i]
            when {
                // 单引号字符串
                c == '\'' -> {
                    val end = consumeQuoted(script, i, '\'')
                    current.append(script, i, end)
                    i = end
                }
                // 双引号标识符
                c == '"' -> {
                    val end = consumeQuoted(script, i, '"')
                    current.append(script, i, end)
                    i = end
                }
                // 行注释 -- ...，至行尾；文本原样保留（MySQL 的 /*! */ 可执行注释等不能被丢弃）
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    val end = skipToLineEnd(script, i + 2)
                    current.append(script, i, end)
                    i = end
                }
                // 行注释 # ...，MySQL 风格，至行尾
                c == '#' -> {
                    val end = skipToLineEnd(script, i + 1)
                    current.append(script, i, end)
                    i = end
                }
                // 块注释 /* ... */，可跨行
                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    val close = script.indexOf("*/", i + 2)
                    // 未闭合：剩余输入整体视为注释，避免死循环
                    val end = if (close < 0) n else close + 2
                    current.append(script, i, end)
                    i = end
                }
                // 反引号标识符
                c == '`' -> {
                    val end = consumeQuoted(script, i, '`')
                    current.append(script, i, end)
                    i = end
                }
                c == '$' -> {
                    val tagEnd = dollarTagEnd(script, i)
                    if (tagEnd < 0) {
                        // 不是合法的 dollar-quote 起始（如 $1 占位符），按普通字符处理
                        current.append(c)
                        i++
                    } else {
                        val tag = script.substring(i, tagEnd + 1)
                        val close = script.indexOf(tag, tagEnd + 1)
                        if (close < 0) {
                            // 未闭合：整段作为语句内容保留
                            current.append(script, i, n)
                            i = n
                        } else {
                            val end = close + tag.length
                            current.append(script, i, end)
                            i = end
                        }
                    }
                }
                c == ';' -> {
                    statements.addIfNotBlank(current)
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }

        // 尾部分号结尾的脚本没有"最后一段"，但无终止符的最后一条语句必须保留
        statements.addIfNotBlank(current)
        return statements
    }

    /**
     * 判断脚本是否只含一条（或零条）顶层语句 —— 无需切分。
     */
    fun isSingleStatement(script: String): Boolean = split(script).size <= 1

    /** 跳过行注释，返回行尾之后（不含换行本身）的下标。 */
    private fun skipToLineEnd(script: String, from: Int): Int {
        var i = from
        val n = script.length
        while (i < n && script[i] != '\n' && script[i] != '\r') i++
        return i
    }

    /**
     * 消费一个引号包裹的片段（字符串或标识符）。
     * 支持连续两个同种引号（`''` / `""` / `` `` ``）作为转义，以及反斜杠转义。
     * 返回片段结束后的下标（未闭合时返回输入长度）。
     */
    private fun consumeQuoted(script: String, start: Int, quote: Char): Int {
        val n = script.length
        var i = start + 1
        while (i < n) {
            val ch = script[i]
            if (ch == '\\' && i + 1 < n) {
                // 反斜杠转义：吞掉被转义的字符
                i += 2
                continue
            }
            if (ch == quote && i + 1 < n && script[i + 1] == quote) {
                // 双写转义，如 ''、""、``
                i += 2
                continue
            }
            if (ch == quote) return i + 1
            i++
        }
        return n
    }

    /**
     * 判断 `start` 处是否为 dollar-quote 起始，返回其结束下标（含闭合的 `$`）；
     * 若不是合法起始则返回 -1。
     */
    private fun dollarTagEnd(script: String, start: Int): Int {
        val n = script.length
        var i = start + 1
        while (i < n) {
            val c = script[i]
            if (c == '$') return i
            // tag 只能是 [A-Za-z_][A-Za-z0-9_]*，否则视为普通字符（例如 $1）
            val ok = c == '_' || c.isLetter() && c.code < 128 ||
                (i > start + 1 && c.isDigit() && c.code < 128)
            if (!ok) return -1
            i++
        }
        return -1
    }

    private fun ArrayList<String>.addIfNotBlank(sb: StringBuilder) {
        val s = sb.toString().trim()
        if (s.isNotEmpty()) add(s)
    }
}
