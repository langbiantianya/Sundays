package com.kxxnzstdsw.sundays.connection

/**
 * 标识符引用 —— **前端侧**（对象树右键「复制引用名」）按方言包裹标识符。
 *
 * ## 为什么前端要自己来一份，而不用引擎的 `DatabaseDialect.quoteIdentifier`
 *
 * 「复制引用名」是一个**纯本地**动作：用户右键点一下，期望剪贴板里立刻出现
 * `"biz_user"`，而不是先发一次引擎往返、拿到结果再复制 —— 树上的表名是**已经
 * 在本地状态里**的（`DatabaseBrowserState.tablesByDatabase`），为一个本地动作付一次
 * 网络往返纯属倒退。
 *
 * 因此这里必须有一份前端侧的映射。它与引擎那份**必须保持一致**，所以
 * `SqlIdentifierQuoteTest` 直接对照 5 个真实方言的 `quoteIdentifier` 逐个断言 ——
 * 任一侧改了引号风格而另一侧没跟上，测试立刻红。
 *
 * ## ⚠️ 与 H2Dialect 的一处**故意**分歧：不折叠大小写
 *
 * `H2Dialect.quoteIdentifier` 做的是 `quoteWith(identifier.uppercase(), '"')`。
 * 引擎那边需要 uppercase 是因为它拿到的表名**可能来自用户输入**（`SELECT * FROM users`
 * 在 H2 里命中的是 `USERS`），而 SQL 生成场景必须猜 H2 的折叠规则才能命中。
 *
 * 复制动作的处境完全不同：**树上的名字就是库里真实存着的名字**。
 * - 建表时写的是 `USERS`（未加引号，被 H2 折叠成大写）→ 树上显示 `USERS` → 引用 `"USERS"` ✅
 * - 建表时写的是 `"users"`（加引号，大小写敏感）→ 树上显示 `users` → 引用 `"users"` ✅
 *
 * 若在这里也 uppercase，第二种情况会被复制成 `"USERS"` —— 一个**指向另一张表（或不存在）
 * 的名字**。剪贴板里放一个看着像对、实际会查错表的引用，比不给引用更糟。
 */
object SqlIdentifier {

    /**
     * 用该方言的引用符包裹 [name]。
     *
     * - MySQL → 反引号，内嵌反引号写成两个（`` ` `` → ` `` `）
     * - 其余（PG / SQLite / DuckDB / H2 / 未知）→ 双引号，内嵌双引号写成两个（`"` → `""`）
     *
     * **不做大小写折叠**，理由见本文件的 KDoc。
     */
    fun quote(dialect: DialectType, name: String): String = when (dialect) {
        // MySQL 是唯一用反引号的方言；ANSI 双引号在 MySQL 里默认是**字符串字面量**，
        // 写成 `"biz_user"` 会被当成 SELECT 'biz_user' 而不是标识符 —— 这是 MySQL 的
        // 历史包袱（ANSI_QUOTES 模式才改变），因此这里必须分开写而不是统一走双引号。
        DialectType.MYSQL -> "`" + name.replace("`", "``") + "`"
        DialectType.POSTGRESQL, DialectType.H2, DialectType.DUCKDB, DialectType.SQLITE ->
            "\"" + name.replace("\"", "\"\"") + "\""
        // 未知方言：没有引擎方言可依（`DialectType.UNKNOWN` 对应引擎会直接报
        // 「No dialect plugin loaded」），此时给双引号 —— 四个内置方言里三个用它，
        // 是概率上最不容易让用户手工返工的那个选择。
        DialectType.UNKNOWN -> "\"" + name.replace("\"", "\"\"") + "\""
    }

    /**
     * 库 / 表限定名 —— [schema] 为空时退化为只有表名。
     *
     * 限定名用 `.` 连接而非 `schema.table` 拼字符串：先各自引用再拼，才不会出现
     * 「只有一半被引用」的中间态。
     */
    fun qualified(dialect: DialectType, schema: String, name: String): String =
        if (schema.isBlank()) quote(dialect, name)
        else quote(dialect, schema) + "." + quote(dialect, name)

    /**
     * 树上某个表节点应当被引用成什么样子。
     *
     * ## 为什么「MySQL 拿 database 当限定名、其余拿 schema」
     *
     * MySQL 的 database **就是**命名空间本身（`USE db` 之后 catalog 与 schema 合一），
     * 所以 `` `examquestions`.`biz_user` `` 是合法且是最不容易歧义的写法。
     *
     * 而 PG / SQLite / DuckDB / H2 的 **database（catalog）与 schema 是两层不同的东西**：
     * PG 里 `examquestions` 是 catalog，`public` 才是 schema，写成
     * `"examquestions"."biz_user"` 在 PG 里会被解析成一个**名为 examquestions 的 schema**
     * 下的表 —— 找不到，于是用户看到「明明表就在眼前，引用出来却报错」。
     *
     * 所以判据不是「有没有 database」而是「**这个方言有没有独立 schema 这一层**」。
     */
    fun tableRef(dialect: DialectType, database: String, schema: String, table: String): String =
        when {
            schema.isNotBlank() -> qualified(dialect, schema, table)
            dialect == DialectType.MYSQL && database.isNotBlank() -> qualified(dialect, database, table)
            // 既没有 schema，MySQL 那条路也走不通（database 为空）：只给表名。
            // 这是最小可用形态 —— 用户要的通常是「一个能粘进 SQL 编辑器的引用」，
            // 而不是一定要全限定。
            else -> quote(dialect, table)
        }

    /** 库节点本身的引用（`USE` / 跨库查询里能用）。 */
    fun databaseRef(dialect: DialectType, database: String): String = quote(dialect, database)
}
