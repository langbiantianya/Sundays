/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.SqlIdentifier

/*
 * **分析模式里「树上的一个对象」到「SQL 里的一段引用」** —— 纯函数。
 *
 * ## 为什么这些规则住在 `:shared` 而不是 `desktopApp`
 *
 * 「点一下树上的表，编辑器里就多出一段能跑的引用」这条规则，
 * 现在有**两个地方**要用：
 *
 * - **分析工作台**（`:desktopApp` 的 `AnalysisSession`）：点表 → 引用进 SQL 编辑器；
 * - **报表设计**（`:shared` 的部件编辑页）：点表 → 引用进部件的 SQL。
 *
 * 两处的输入输出完全一样（一个表名 + 一个 schema 名 → 一段引用），
 * 而报表设计这一侧住在跨平台模块里、拿不到会话。
 * 规则只写一份在 `:shared`，两边都调用它 ——
 * 否则「同一个概念有两份实现，改一处忘了另一处」必然发生：
 * 哪天工作台改了引用格式，报表里的引用还停在旧格式，
 * 而**两边各自的测试都是绿的**（它们各自断言的都是自己那份实现）。
 *
 * ## 为什么这些是**纯函数**
 *
 * 它们不碰引擎、不碰文件系统、不读语言设置 —— 给定输入必然给出同一个输出。
 * 所以能进 `commonTest`，在任何平台上都能跑。
 */

/**
 * 表的**限定引用** —— `"schema"."表"`，而 `main` 里的表就只写 `"表"`。
 *
 * ## 为什么 `main` 不带前缀
 *
 * 工作区自己的表都在 `main` 里 —— 而那是最常见的情况。
 * 每次都写成 `"main"."orders"` 的话，用户复制到别处（另一条 SQL、
 * 另一个工具）就多了一截前缀，而那截前缀**大多数时候是多余的**。
 *
 * ## 为什么 schema 不能省
 *
 * 「添加数据源」把每个源的表放进**自己的 schema** ——
 * 于是同一张表名可以在两个源里各有一份（`shop.orders` 与 `crm.orders`）。
 * 不带 schema 的引用落在 `main` 里，于是查的可能是**另一个**表 ——
 * 而且**不报错**（`main` 里恰好有同名表的话），只是数据悄悄换了来源。
 *
 * @param name 表名（未引用）
 * @param namespace 表所在的 schema；`null`、空白或 [WORKSPACE_SCHEMA] = 不带前缀
 * @return 可以直接贴进 SQL 的引用
 */
fun analysisTableRef(
    name: String,
    namespace: String?,
): String {
    val quotedName = SqlIdentifier.quote(DialectType.DUCKDB, name)
    if (namespace.isNullOrBlank() || namespace == WORKSPACE_SCHEMA) return quotedName
    val quotedNamespace = SqlIdentifier.quote(DialectType.DUCKDB, namespace)
    return "$quotedNamespace.$quotedName"
}

/**
 * 字段的**限定引用** —— `"schema"."表"."字段"`。
 *
 * ## 为什么字段**必须带表名**
 *
 * 光有列名在多表查询里是说不清的 ——
 * 两个表里都叫 `id` 的字段太常见了，`"id"` 指哪一个？
 * 而 `"表"."列"` 永远说得清。
 *
 * @param table 表名（未引用）
 * @param column 字段名（未引用）
 * @param namespace 表所在的 schema；`null`、空白或 [WORKSPACE_SCHEMA] = 不带前缀
 * @return 可以直接贴进 SQL 的引用
 */
fun analysisColumnRef(
    table: String,
    column: String,
    namespace: String?,
): String {
    val quotedColumn = SqlIdentifier.quote(DialectType.DUCKDB, column)
    return "${analysisTableRef(table, namespace)}.$quotedColumn"
}

/**
 * 把一张**表**的引用加进一段 SQL。
 *
 * ## 规则只有一条，例外只有一个
 *
 * - SQL **是空的** → 放一条能直接跑的语句（`SELECT * FROM "orders"`）；
 * - SQL **有内容** → 在末尾追加一行**引用的表名**（`"orders"`）。
 *
 * 空的那条不是随手写的：用户点完树之后，**最想立刻看到的就是这张表的数据**，
 * 而 `SELECT * FROM …` 是唯一一条不用猜的起手式。
 *
 * ## 为什么不插到光标处
 *
 * 光标位置住在编辑器的内部状态（`CodeEditorState`）里，那是**界面**的东西；
 * 这里只拿着一个纯字符串。在会话里插光标位置就得把编辑器状态也搬进会话 ——
 * 而那会把「一段文本」与「一个编辑器控件」耦在一起。
 *
 * 追加到末尾虽然不一定是用户想要的位置，但它是**可预期**的：
 * 用户看得到自己多了什么，也能自己挪。
 *
 * @param sql 现在的 SQL
 * @param name 表名（未引用）
 * @param namespace 表所在的 schema；`null`、空白或 [WORKSPACE_SCHEMA] = 不带前缀
 * @return 加了引用的新 SQL
 */
fun appendAnalysisTableRef(
    sql: String,
    name: String,
    namespace: String?,
): String {
    val reference = analysisTableRef(name, namespace)
    return if (sql.isBlank()) {
        "SELECT * FROM $reference"
    } else {
        sql.trimEnd() + "\n" + reference
    }
}

/**
 * 把一个**字段**的引用加进一段 SQL。
 *
 * 规则与 [appendAnalysisTableRef] 一样（空则给一条能跑的语句，否则追加一行），
 * 区别是字段要**带表名** —— 光有列名在多表查询里是说不清的，
 * 而 `"表"."列"` 永远说得清。
 *
 * @param sql 现在的 SQL
 * @param table 表名（未引用）
 * @param column 字段名（未引用）
 * @param namespace 表所在的 schema；`null`、空白或 [WORKSPACE_SCHEMA] = 不带前缀
 * @return 加了引用的新 SQL
 */
fun appendAnalysisColumnRef(
    sql: String,
    table: String,
    column: String,
    namespace: String?,
): String {
    val tableRef = analysisTableRef(table, namespace)
    val reference = "$tableRef.${SqlIdentifier.quote(DialectType.DUCKDB, column)}"
    return if (sql.isBlank()) {
        "SELECT $reference FROM $tableRef"
    } else {
        sql.trimEnd() + "\n" + reference
    }
}
