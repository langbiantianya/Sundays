package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dialect.SQLiteDialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.DialectType
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 走查目标 —— 一个**真的能连上**的嵌入式数据库，以及「对它应该怎么断言」。
 *
 * ## 为什么要抽象，而不是在用例里 `when (dialect)`
 *
 * 因为两个方言的**能力本身不同**，断言也就必须不同，而差异点不止一处：
 *
 * | | H2 | SQLite |
 * |---|---|---|
 * | 视图 / 索引 / 外键 | 有 | 有 |
 * | 触发器 / 过程·函数 | 有 | **无**（业务层抛 `UnsupportedOperationException`） |
 * | schema 名 | `PUBLIC` | `main` / `temp` |
 * | 标识符大小写 | 库表名全**大写** | 原样**小写** |
 * | 内存库语义 | 同 JVM 多连接共享（`DB_CLOSE_DELAY=-1`） | `:memory:` **每连接私有** |
 *
 * 最后一行是**必须**踩准的：事务用例靠「另一条独立 JDBC 连接看得到吗」判可见性，
 * 而 SQLite 的 `:memory:` 每个连接都是**独立的库** —— 用它的话，独立连接永远看不到任何东西，
 * 「回滚后看不到」会**假通过**。所以 SQLite 一律走**临时文件**。
 *
 * 把这些差异集中在这里，用例里就能只写「这个功能应该成立吗」，不被方言细节淹没。
 *
 * @property name 测试名后缀（参数化测试会显示成 `[H2] …` / `[SQLite] …`）
 * @property supportsDatabaseObjects 该方言**确实支持**的库级对象种类。
 *   不在其中的种类，应用**应当报「不支持」并停止转圈**，而不是永远空转或整块崩掉。
 * @property triggerDdl 建触发器的 DDL；`null` = 该方言没有这个概念，连建都不必试。
 * @property functionDdl 建函数别名的 DDL；`null` 同上。
 * @property exposesForeignKeyName 引擎能否回**真实的**外键约束名。
 *
 *   SQLite 的 `SQLiteDialect.listForeignKeys` 走 `PRAGMA foreign_key_list`，而那个 PRAGMA
 *   **不暴露约束名**（只有 id / seq / table / from / to / on_update / on_delete / match），
 *   于是方言只能拼一个 `fk_<表>_<序号>`。要拿真名只能解析 `sqlite_master` 的建表 SQL。
 *   所以这不是「查错了」，是**方言拿不到** —— 用例对它只能断言「列出来了」，
 *   断言具体名字会让用例在 SQLite 上永远红，而红的原因跟被测代码无关。
 */
class WalkthroughTarget(
    val name: String,
    private val dialectType: DialectType,
    private val driverName: String,
    val registerDialect: () -> Unit,
    private val urlFactory: (File) -> String,
    private val user: String,
    private val password: String,
    val supportsDatabaseObjects: Set<String>,
    private val triggerDdl: ((String) -> String)?,
    private val functionDdl: ((String) -> String)?,
    val exposesForeignKeyName: Boolean = true,
) {

    lateinit var jdbcUrl: String
        private set

    /** 把方言注册进 [com.kxxnzstdsw.loader.DialectLoader]，并定下 JDBC URL。 */
    fun open(tempDir: File) {
        registerDialect()
        jdbcUrl = urlFactory(tempDir)
    }

    /** 应用侧的连接描述 —— 走查全程用它渲染界面。 */
    fun connectionConfig(): ConnectionConfig = ConnectionConfig(
        id = "walkthrough-${name.lowercase()}",
        name = "Demo${name}",
        dialect = dialectType,
        username = user,
        password = password,
        jdbcUrl = jdbcUrl,
    )

    /** 开一条**独立于引擎连接池**的直连 —— 事务可见性靠它判。 */
    fun openDirect(): Connection =
        DriverManager.getConnection(jdbcUrl, user, password).also { c ->
            // SQLite 默认不强制外键；这里显式开，让「外键确实存在」不只是元数据摆设。
            if (dialectType == DialectType.SQLITE) runCatching { c.createStatement().use { it.execute("PRAGMA foreign_keys=ON") } }
        }

    /** 建触发器。返回 `null` 表示该方言没这个概念（连试都不必试）。 */
    fun createTrigger(stmt: java.sql.Statement, hostClass: String): String? =
        triggerDdl?.let { runCatching { stmt.executeUpdate(it(hostClass)); "ok" }.getOrElse { "failed: ${it.message}" } }

    /** 建函数别名（H2 的 `CREATE ALIAS`）。 */
    fun createFunction(stmt: java.sql.Statement, hostClass: String): String? =
        functionDdl?.let { runCatching { stmt.executeUpdate(it(hostClass)); "ok" }.getOrElse { "failed: ${it.message}" } }

    /** 走查日志用的一行摘要。 */
    override fun toString(): String = name

    companion object {
        /**
         * H2 —— 触发器与函数**都有**的一方。
         *
         * 触发器必须 `CALL` 一个 Java 静态方法（H2 没有内联触发器语法），
         * 所以 DDL 里塞了 [hostClass] 这个反射宿主。
         */
        val H2 = WalkthroughTarget(
            name = "H2",
            dialectType = DialectType.H2,
            driverName = "H2",
            registerDialect = { DialectLoader.registerForTesting("H2", H2Dialect()) },
            // DB_CLOSE_DELAY=-1：最后一个连接关掉后库仍在，同 JVM 的独立连接才看得到同一份数据
            urlFactory = { dir -> "jdbc:h2:mem:wt${System.nanoTime()};DB_CLOSE_DELAY=-1" },
            user = "sa",
            password = "",
            supportsDatabaseObjects = setOf("VIEW", "TRIGGER", "FUNCTION"),
            triggerDdl = { host ->
                """CREATE TRIGGER trg_users_guard BEFORE DELETE ON users
                   FOR EACH ROW CALL "$host.fire""""
            },
            functionDdl = { host -> """CREATE ALIAS double_it FOR "$host.doubleIt"""" },
        )

        /**
         * SQLite —— **没有**触发器与过程/函数的一方。
         *
         * 走**临时文件**而不是 `:memory:`：后者每个连接是独立的库，会让事务用例的
         * 「另一条连接看得到吗」永远答「看不到」，于是回滚/提交全都假通过。
         *
         * 附带一个 SQLite 才有的坑：文件库在别人持有写事务时会返回 `SQLITE_BUSY`。
         * 走查用例把「读不到 / 被锁」都算作「未提交的数据对他人不可见」——
         * 这在语义上是对的，而且必须显式写出来，否则换个平台就变成一个查不到的错误。
         */
        val SQLITE = WalkthroughTarget(
            name = "SQLite",
            dialectType = DialectType.SQLITE,
            driverName = "Sqlite",
            registerDialect = { DialectLoader.registerForTesting("Sqlite", SQLiteDialect()) },
            urlFactory = { dir ->
                // busy_timeout 让「别人持写锁」从立刻报错变成等待，避免用例偶发超时
                "jdbc:sqlite:${File(dir, "wt.db").absolutePath.replace('\\', '/')}?busy_timeout=5000"
            },
            user = "",
            password = "",
            // 触发器 / 过程·函数留空 —— `SQLiteDialect` 明确对它们抛 UnsupportedOperationException
            supportsDatabaseObjects = setOf("VIEW"),
            triggerDdl = null,
            functionDdl = null,
            exposesForeignKeyName = false,
        )

        /** 走查覆盖的两个方言。 */
        fun all(): List<WalkthroughTarget> = listOf(H2, SQLITE)
    }
}