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
     * 用 `.` 连接若干段，每段各自引用。
     *
     * 先各自引用再拼，才不会出现「只有一半被引用」的中间态。
     * 空段直接跳过 —— 调用方不必为「这一级没有名字」单独分叉。
     */
    fun path(dialect: DialectType, vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(".") { quote(dialect, it) }

    /**
     * 库 / 表限定名 —— [schema] 为空时退化为只有表名。
     */
    fun qualified(dialect: DialectType, schema: String, name: String): String =
        path(dialect, schema, name)

    /**
     * 树上某个表节点应当被引用成什么样子。
     *
     * ## MySQL：`database.table`
     *
     * MySQL 的 database **就是**命名空间本身（`USE db` 之后 catalog 与 schema 合一），
     * 所以 `` `examquestions`.`biz_user` `` 是合法且最不容易歧义的写法。
     *
     * ## PostgreSQL：`database.schema.table` —— 本库可以，别库不行
     *
     * 第一版按「PG 里 database 是 catalog，所以绝不能写进去」只给了 `schema.table`。
     * **这个判断是错的**，错在把「跨库不支持」当成了「catalog 这一级不能写」。
     *
     * PG 一直支持 `catalog.schema.table` 三段式（不是 PG 18 才有的），
     * PG 源码 `RangeVarGetCreationNamespace` 里的判据只有一条：
     *
     * ```c
     * if (newRelation->catalogname) {
     *     if (strcmp(newRelation->catalogname, get_database_name(MyDatabaseId)) != 0)
     *         ereport(ERROR, ... "cross-database references are not implemented");
     * }
     * ```
     *
     * 即 **库名必须等于当前连接的库**，仅此而已。真库实测（PG 18.4 / examquestions）：
     *
     * | 连到 | 语句 | 结果 |
     * |---|---|---|
     * | `examquestions` | `FROM examquestions.public.t1` | ✅ 成功 |
     * | `examquestions` | `FROM "examquestions"."public"."t1"` | ✅ 成功 |
     * | `postgres` | `FROM examquestions.public.t1` | ❌ `cross-database references are not implemented` |
     *
     * 而**两段式**的退化路径在 PG 里任何版本、任何库都恒可用，所以 [databaseIsCurrent]
     * 为假时退回去永远安全 —— 这是本函数最保守的一侧。
     *
     * > 顺带钉住一个容易混淆的反例：两段式的 `"examquestions"."biz_user"` 报的是
     * > `relation "examquestions.biz_user" does not exist`（SQLState 42P01，**不是** 0A000）——
     * > 它被解释成「名为 examquestions 的 schema 下的 biz_user」。所以「库名当 schema 用」
     * > 确实会失败，但那**不能推出**「库名不能出现在引用里」——三段式是另一条路径。
     *
     * @param databaseIsCurrent [database] 是否就是**当前连接的**那个库。
     *   只有 true 时 PG 才写 catalog 这一级 —— 否则粘进 SQL 编辑器必定报错，
     *   而「看着像对、粘上就炸」的引用比不给引用更糟。
     */
    fun tableRef(
        dialect: DialectType,
        database: String,
        schema: String,
        table: String,
        databaseIsCurrent: Boolean = false,
    ): String = when {
        // PG 三段式：database + schema 都有，且这个库就是当前连接的库
        dialect == DialectType.POSTGRESQL &&
            databaseIsCurrent && database.isNotBlank() && schema.isNotBlank() ->
            path(dialect, database, schema, table)
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
