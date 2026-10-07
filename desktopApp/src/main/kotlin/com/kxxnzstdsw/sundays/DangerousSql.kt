package com.kxxnzstdsw.sundays

/**
 * **危险写操作**的识别 —— 只读模式之外的第二道防线。
 *
 * ## 为什么两道都要
 *
 * 只读模式是**连接级开关**：一旦打开，整个会话的写操作都被挡在引擎侧。但用户常常
 * 是「想写、怕写错」，不是「不许写」—— 让他为了跑一条 \`UPDATE\` 去把连接改成只读，
 * 代价太大。���是真正执行前的那一次确认。
 *
 * ## 为什么要**静态判断**而不是让引擎报错
 *
 * 引擎拦下 \`DROP TABLE\` 时**已经执行了**（或者已经进了事务）。前端在**发出去之前**
 * 拦，代价是零 —— 用户看到一个确认框，确认框背后什么��没发生。
 *
 * 判据刻意**保守**：宁可多问一次，不可漏问一次。误报的代价是用户多点一下，
 * 漏报的代价是数据没了。
 */
internal object DangerousSql {

    /** 一条被判为「需要用户确认」的写操作。 */
    data class Finding(
        /** 命中的类别，用于给确认框一个具体标题（「这会删除表」而不是「危险操作」）。 */
        val kind: Kind,
        /** 命中的那一句（去掉分号与首尾空白），用于在确认框里回显。 */
        val statement: String,
    ) {
        enum class Kind(val label: String) {
            DROP("删除表 / 视图 / 索引"),
            TRUNCATE("清空表"),
            DELETE_WITHOUT_WHERE("无 WHERE 的 DELETE"),
            UPDATE_WITHOUT_WHERE("无 WHERE 的 UPDATE"),
            GRANT("授权"),
        }
    }

    /**
     * 去掉注释与字符串字面量，返回「可执行代码骨架」。
     *
     * ## 为什么必须先剥掉它们
     *
     * 朴素的关键词匹配会被**注释和字符串**骗到，三种典型误报/漏报：
     * - \`-- DROP TABLE t\` 整行是注释，**不该**被当成删除
     * - \`SELECT 'delete from t'\` 里有个字符串，**不该**被当成无 WHERE 的删除
     * - \`/* update */\` 同理
     *
     * 而漏报同样致命：\`INSERT INTO log VALUES ('DROP TABLE')\` 若不剥字符串就会误判，
     * \`SELECT 1 -- 后面才是真的 DROP\` 若不剥注释就会漏判。
     */
    fun stripCommentsAndLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                // 行注释 -- ... 到行尾。
                // ⚠️ **必须把换行符写回输出**：吃掉它的话，`SELECT 1 -- 注释\nDROP TABLE t`
                // 会变成 `SELECT 1 DROP TABLE t` —— 真语句被接到了同一行的中段，
                // `head.startsWith("DROP")` 于是不匹配，**漏报**。
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    while (i < n && sql[i] != '\n') i++
                    if (i < n) {
                        out.append('\n')
                        i++
                    }
                }
                // 块注释 /* ... */
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    // 同样要留一个分隔符，否则 `SELECT 1 /*x*/DROP TABLE t` 会粘成
                    // `SELECT 1 DROP TABLE t` —— 与行注释同一个漏报
                    out.append(' ')
                }
                // 字符串 '...'（标准 SQL 用 '' 转义）
                c == '\'' -> {
                    i++
                    while (i < n) {
                        if (sql[i] == '\'') {
                            if (i + 1 < n && sql[i + 1] == '\'') i += 2 else { i++; break }
                        } else i++
                    }
                    out.append(' ')   // 用空格占位，保持语句分隔
                }
                // 双引号标识符 "..."（PG / H2 的引号标识符）—— 里面的内容不是关键字
                c == '"' -> {
                    i++
                    while (i < n) {
                        if (sql[i] == '"') {
                            if (i + 1 < n && sql[i + 1] == '"') i += 2 else { i++; break }
                        } else i++
                    }
                    out.append(' ')
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 按顶层 \`;\` 切句。字符串与注释已在调用前剥掉，这里只需认分号。 */
    private fun statements(sql: String): List<String> =
        sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 判断一条 SQL 里有没有需要确认的写操作。
     *
     * @return 逐句检查，**所有**命中项（一条脚本里可能有好几条危险语句，
     *   确认框要把它们全列出来，而不是只报第一条 —— 只报第一条会让用户以为
     *   确认了就没别的事了）。
     */
    fun scan(sql: String): List<Finding> {
        val out = mutableListOf<Finding>()
        for (raw in statements(stripCommentsAndLiterals(sql))) {
            val upper = raw.uppercase()
            // 判据是「**这一句里出现了**危险关键字」，不是「第一个词是它」。
            //
            // 早先写成 `head.startsWith("DROP ")`，而 `SELECT 1 -- 注释\nDROP TABLE t`
            // 整句的第一个词是 SELECT，真 DROP 落在中段 —— **漏报**。
            // 漏报意味着用户没看到确认框就把表删了，那正是这个功能要防的事。
            //
            // 用 `\b` 词边界而不是 `contains`：`DROPPED` / `UPDATES` 这类标识符
            // 不该被当成关键字。
            fun has(kw: String) = Regex("\\b$kw\\b").containsMatchIn(upper)
            when {
                has("DROP") -> out += Finding(Finding.Kind.DROP, raw)
                has("TRUNCATE") -> out += Finding(Finding.Kind.TRUNCATE, raw)
                has("GRANT") -> out += Finding(Finding.Kind.GRANT, raw)
                has("DELETE") && !has("WHERE") ->
                    out += Finding(Finding.Kind.DELETE_WITHOUT_WHERE, raw)
                has("UPDATE") && !has("WHERE") ->
                    out += Finding(Finding.Kind.UPDATE_WITHOUT_WHERE, raw)
            }
        }
        return out
    }

    fun isDangerous(sql: String): Boolean = scan(sql).isNotEmpty()

    /**
     * 这条 SQL 是否**只会读**（可安全用于「预览」）。
     *
     * ## 判据与 [scan] 的关系：白名单，且**逐句**判
     *
     * 导出对话框里的「预览」是一键发到数据库的动作，而它就摆在一个用户刚编辑完的
     * SQL 编辑器旁边。用黑名单（"不含危险关键字"）是不够的 —— 没被列进黑名单的
     * 写操作（`CREATE` / `ALTER` / `CALL` / 存储过程…）会静默跑掉。
     *
     * ⚠️ **必须逐句判，不能只看整段的开头**。
     * 只看开头时，`SELECT 1; DROP TABLE t` 会因为「第一个词是 SELECT」而被放行 ——
     * 而它在导出对话框里是最容易粘贴出来的形态（一段从别处抄来的脚本）。
     * 判据改成「**每一句**都以 SELECT / WITH 开头」，与 [scan] 同样逐句遍历。
     *
     * 放行两种开头：`SELECT` 与 `WITH`（CTE）。后者最终要么落到 SELECT，
     * 要么落到 DML —— 而 DML 整体不在白名单里，一样会被拒。
     *
     * ⚠️ **注释与字面量必须先剥掉**：否则
     * `-- 查询用户\nSELECT * FROM t` 会因为开头是 `--` 而被判成「不是只读」，
     * 明明安全却点不了预览。复用 [stripCommentsAndLiterals] 而不是另写一份，
     * 是为了让它与 [scan] 永远保持同一套词法规则。
     */
    fun isReadOnlyQuery(sql: String): Boolean {
        val stmts = statements(stripCommentsAndLiterals(sql))
        if (stmts.isEmpty()) return false
        return stmts.all { raw ->
            val head = raw.trimStart().uppercase()
            head.startsWith("SELECT") || head.startsWith("WITH")
        }
    }
}
