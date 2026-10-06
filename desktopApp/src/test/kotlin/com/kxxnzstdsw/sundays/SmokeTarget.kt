package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.dialect.DuckDBDialect
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dialect.MySQLDialect
import com.kxxnzstdsw.dialect.PostgreSQLDialect
import com.kxxnzstdsw.dialect.SQLiteDialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.ConnectionType
import com.kxxnzstdsw.sundays.connection.DialectType
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.Connection
import java.sql.DriverManager

/**
 * 冒烟目标 —— 一个**真的能连上**的库，以及「怎么在它上面隔离出一块探针工作区」。
 *
 * ## 为什么要隔离
 *
 * 远程库（MySQL / PG）是**共享资源**，不能往里随手建表。四种目标统一成一个模型：
 * 每次跑之前 `provision()` 划一块**独享**工作区，跑完 `teardown()` 扔掉。
 * H2 / SQLite 本身就是一个临时库（内存 / 临时文件），天然隔离，工作区名是空串。
 *
 * ## 为什么远程库不可达时要 skip 而不是红
 *
 * CI、别人的机器、没连内网的开发机上，192.168.1.5 根本不存在。这类环境因素不该让构建变红，
 * 但**也绝不能静默跳过** —— 所以在 `DialectSmokeTest` 里用 JUnit `assumeTrue` 显式 skip，
 * 报告里会留下一条「skipped」，而不是一片绿。
 */
interface SmokeTarget {
    val label: String
    val dialectType: DialectType

    fun registerDialect()

    /** TCP 能不能连上 —— 远程库不可达时用它决定 skip。 */
    fun reachable(): Boolean

    /** 划一块独享工作区；返回本次探针连接时该用的 database 名（本地库返回空串）。 */
    fun provision(scope: File): String

    /** 归还工作区。**必须**在 tearDown 里无条件调用 —— 远程库上留下的库是别人的负担。 */
    fun teardown(workspace: String)

    /**
     * 把工作区清成「一张表都没有」。
     *
     * ## 为什么远程库需要这一步
     *
     * 客户端-服务器方言复用**固定库名**（见 [ServerSmokeTarget]）——
     * 每次换一个库就得 `DROP DATABASE`，而 `DROP DATABASE` 会在元数据锁上挂死
     * （引擎连接池还握着那个库），历史上正是它把服务端连接数打满过。
     *
     * 代价就是上一轮留下的表还在，于是下一轮 `CREATE TABLE smoke_items` 直接撞
     * `Table 'smoke_items' already exists`（实测：`DialectSmokeTest` 三条红）。
     *
     * **表级 DDL 不碰库级元数据锁**，所以「建库 + 清表」能在不泄漏连接的前提下
     * 重新给出与「全新库」等价的起点。
     *
     * 本地库每次都是全新的内存库 / 临时文件，默认空实现即可。
     */
    fun resetTables(workspace: String) = Unit

    /** 应用侧的连接描述 —— 走查全程用它构造引擎请求。 */
    fun config(workspace: String): ConnectionConfig

    /** 独立于引擎连接池的直连 —— 事务可见性与「真的落库了吗」靠它判。 */
    fun direct(workspace: String): Connection

    /**
     * 用独立直连数一行。
     *
     * 事务用例靠它判「未提交的数据对别人不可见」——**这必须在引擎之外看**，
     * 在引擎里看只能看到自己那个会话，永远「可见」。
     */
    fun count(workspace: String, table: String, where: String = "1 = 1"): Int =
        direct(workspace).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }

    /**
     * 表还在不在。
     *
     * **刻意不用 `INFORMATION_SCHEMA`** —— 那是 H2 专有的元数据视图，SQLite 上直接语法错。
     * 改成「直接查它，查得动就说明表还在」：这本来就是要回答的问题，且全方言通用。
     */
    fun tableExists(workspace: String, table: String): Boolean = runCatching {
        direct(workspace).use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next() } }
        }
        true
    }.getOrDefault(false)

    /**
     * 引擎能不能回**真实的**外键约束名 —— 且是不是**用户给的那个**名字。
     *
     * - SQLite：`PRAGMA foreign_key_list` **不暴露约束名**，方言只能拼 `fk_<表>_<序号>`
     * - DuckDB：外键子句里的名字被**忽略**，实际写入的是 `<表>_<列>_fkey`
     * - H2 / MySQL / PostgreSQL：走 `INFORMATION_SCHEMA`，名字是真的
     *
     * 两种「不是真名」的形态成因不同（一个拿不到、一个被改写），但对断言的影响一样：
     * 只能断言「列出来了」，断言用户给的名字会在这些方言上永远红，而红因与被测代码无关。
     */
    fun keepsUserGivenForeignKeyName(): Boolean = true
}

/**
 * 本地库：天然隔离，`workspace` 是空串（库名由内存 / 文件 URL 自己带）。
 *
 * 可见性是 `abstract class` 而不是 `private` —— 下面两个 `object` 是 `public` 的，
 * `public` 子类型不能暴露 `private` 的父类型（编译器会直接报错）。
 */
abstract class LocalSmokeTarget : SmokeTarget {
    override fun reachable(): Boolean = true
    override fun provision(scope: File): String = ""
    override fun teardown(workspace: String) = Unit

    /** 让参数化测试的名字读起来是 `[H2]` 而不是 `[H2Smoke@1f2a3b]`。 */
    override fun toString(): String = label
}

/**
 * H2 —— 内存库，**必须**带 `DB_CLOSE_DELAY=-1`。
 *
 * 少了它，最后一个连接关掉库就消失，同 JVM 里另一条直连会连到**一个全新的空库** ——
 * 事务可见性判据会全部假通过。
 */
object H2Smoke : LocalSmokeTarget() {
    override val label = "H2"
    override val dialectType = DialectType.H2
    private var url = ""

    override fun registerDialect() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        url = "jdbc:h2:mem:smoke${System.nanoTime()};DB_CLOSE_DELAY=-1"
    }

    override fun config(workspace: String) = ConnectionConfig(
        id = "smoke-h2", name = "SmokeH2", dialect = DialectType.H2,
        username = "sa", password = "", jdbcUrl = url,
    )

    override fun direct(workspace: String): Connection = DriverManager.getConnection(url, "sa", "")
}

/** SQLite —— 临时**文件**库。`:memory:` 每个连接是独立的库，会让事务判据假通过。 */
object SQLiteSmoke : LocalSmokeTarget() {
    override val label = "SQLite"
    override val dialectType = DialectType.SQLITE
    private var url = ""

    override fun registerDialect() {
        DialectLoader.registerForTesting("Sqlite", SQLiteDialect())
        val f = File(System.getProperty("java.io.tmpdir"), "sundays-smoke-${System.nanoTime()}.db")
        url = "jdbc:sqlite:${f.absolutePath.replace('\\', '/')}"
    }

    override fun config(workspace: String) = ConnectionConfig(
        id = "smoke-sqlite", name = "SmokeSQLite", dialect = DialectType.SQLITE,
        username = "", password = "", jdbcUrl = url,
    )

    override fun direct(workspace: String): Connection = DriverManager.getConnection(url)

    /** 见 [SmokeTarget.keepsUserGivenForeignKeyName]：SQLite 的 PRAGMA 不给约束名。 */
    override fun keepsUserGivenForeignKeyName(): Boolean = false
}

/**
 * DuckDB —— **必须用文件，不能用 `:memory:`**。
 *
 * DuckDB 的内存模式**每个连接都是独立的数据库实例**，于是引擎连接里建的表，
 * 独立直连一条也看不到 → 「写读闭环」与「事务可见性」两条判据会**全部假通过**。
 * 与 SQLite 是同一个坑，但 DuckDB 更容易踩 —— 内存模式是它的默认用法。
 */
object DuckDbSmoke : SmokeTarget {
    override val label = "DuckDB"
    override val dialectType = DialectType.DUCKDB
    private var file: File? = null

    override fun registerDialect() = DialectLoader.registerForTesting("Duckdb", DuckDBDialect())

    override fun reachable(): Boolean = true

    override fun provision(scope: File): String {
        val f = File(scope, "smoke.duckdb")
        file = f
        return f.absolutePath
    }

    override fun teardown(workspace: String) {
        file?.delete()
        file = null
    }

    override fun config(workspace: String) = ConnectionConfig(
        id = "smoke-duckdb", name = "SmokeDuckDB", dialect = DialectType.DUCKDB,
        username = "", password = "", database = workspace, jdbcUrl = urlFor(workspace),
    )

    /**
     * ⚠️ **必须显式传空用户名/密码**，不能只给 URL。
     *
     * 实测：同一个 `.duckdb` 文件，一条连接**不传** user、另一条传 `user=""`，
     * DuckDB 报 `Can't open a connection to same database file with a different
     * configuration than existing connections` —— 在它眼里「没传用户」与「用户是空串」
     * 是**两种不同的配置**。
     *
     * 而引擎那边 `PoolManager` 永远 `username = config.user`，DuckDB 的 config 里那是空串，
     * 所以**池里的连接全是「空用户」连接**。测试的直连若不跟着传空串，第一次开就冲突 ——
     * 于是「写读闭环」「事务可见性」全部报同一个看不懂的连接错误。
     *
     * （`SET schema` 不算配置，已单独验证过 —— 所以不必去对齐 search_path。）
     */
    override fun direct(workspace: String): Connection =
        DriverManager.getConnection(urlFor(workspace), "", "")

    /** 见 [SmokeTarget.keepsUserGivenForeignKeyName]：DuckDB 忽略外键子句里的名字。 */
    override fun keepsUserGivenForeignKeyName(): Boolean = false

    /** 让参数化测试的名字读起来是 `[DuckDB]` 而不是 `[DuckDbSmoke@1a4927d6]`。 */
    override fun toString(): String = label

    /**
     * 走方言自己的 `buildJdbcUrl` 而不是手拼 —— 那是 `ExcelToDuckDbCache` 这类
     * **预处理**唯一会发生的入口。
     *
     * ⚠️ `PoolManager.createDataSource` 的取值顺序是「`config.jdbcUrl` 非空就用它，
     * 否则才调 `dialect.buildJdbcUrl(...)`」。所以**任何预先算好 URL 的调用方都会绕过
     * 方言的预处理** —— 把 `.xlsx` 路径直接塞进 `jdbcUrl`，Excel 转换就不会发生。
     * 文件型数据源测试对四种格式统一走这个入口。
     */
    fun urlFor(workspace: String): String = DuckDBDialect().buildJdbcUrl("", 0, workspace)
}

/**
 * 客户端-服务器库：**固定一个库名，复用；建库 + 清表，但绝不 DROP 库**。
 *
 * ## ⚠️ 这里原本是「每次建临时库、跑完 DROP」—— 那是个会打爆服务端的设计
 *
 * 原实现每次 `provision` 建一个 `sundays_smoke_<nanos>`、`teardown` 里
 * `DROP DATABASE IF EXISTS`。实测连跑几轮全量之后，**MySQL 直接不再收新连接**
 * （端口通、协议层不应答 = `max_connections` 打满），而同一时刻 PostgreSQL 一切正常。
 *
 * 机制是「**先 DROP、再关引擎**」这个顺序的必然结果：
 * 引擎的连接池还握着那个库时，`DROP DATABASE` 会**等元数据锁**；等不到也不放，
 * `boot()` 里的 `.use {}` 于是**永远走不到 close** —— 每次泄漏一个服务端连接。
 * 跑 N 轮泄漏 N 个，够几次就把别人的服务端打满。
 *
 * 改成：**固定库名 + 只重建表**。
 * - `provision`：`CREATE DATABASE`（按方言）+ [resetTables] 清空
 * - `teardown`：**什么都不做** —— 不开连接，就没有连接可泄漏
 *
 * 代价是服务端会**留下一个**库（`sundays_smoke`），且是空的。这是刻意的取舍：
 * 留一个空库远比打爆别人的服务端好。
 *
 * ## ⚠️ 固定库名要求「建库失败必须当场炸」，不能吞
 *
 * 第一版把建库包在 `runCatching { }.onFailure { println(...) }` 里，于是
 * 建库失败只打印一行、**`provision` 照样返回库名**，测试在十几行之后才以
 * `FATAL: database "sundays_smoke" does not exist` 失败 —— 报错指向完全错误的地方。
 * 现在建库失败直接抛出：错在哪就在哪炸。
 *
 * ⚠️ 共用固定库名的前提是同一时刻只有一个 JVM 在跑测试。CI 并行跑时要把库名
 * 加上运行标识（`System.nanoTime()` 或 CI 的 build id），否则两个任务会互相清表。
 */
abstract class ServerSmokeTarget(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    /** 建库 / 连库时的引导库。 */
    private val bootstrapDb: String,
) : SmokeTarget {
    override fun reachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
        true
    }.getOrDefault(false)

    protected abstract fun urlFor(database: String): String

    /** 该方言的标识符引用（MySQL 反引号 / PG 双引号）。 */
    protected abstract fun quoteIdent(name: String): String

    /** 建库。**必须幂等**，且失败必须抛出（见类注释）。 */
    protected abstract fun ensureDatabase(name: String)

    /**
     * 列出库里的**视图**与**基表**（不含其它对象）。
     *
     * ⚠️ **视图必须一并清**：第一版只清基表，视图 `smoke_v_items` 留在库里，
     * 下一轮 `CREATE VIEW smoke_v_items` 直接撞 `already exists`
     * （实测：`DialectSmokeTest` S3 一条红）。
     * 而视图恰恰是 S3 这条用例自己建的 —— 只清表就必然漏。
     */
    protected abstract fun listViews(conn: Connection, workspace: String): List<String>

    protected abstract fun listBaseTables(conn: Connection, workspace: String): List<String>

    /**
     * 把上面那批对象一次性 DROP 掉。**必须视图在前、表在后** —— 视图依赖底下的表，
     * 反过来会被依赖挡住。多表单语句是有意为之：外键与视图的顺序问题一次解决。
     */
    protected abstract fun dropStatements(views: List<String>, tables: List<String>): List<String>

    /** 见 [LocalSmokeTarget.toString]：让参数化测试的名字读起来是 `[MySQL]`。 */
    override fun toString(): String = label

    /**
     * 连**引导库**的直连 —— 建库要在那里发（`CREATE DATABASE` 不能在目标库里建自己）。
     *
     * `protected` 而非 `private`：两个方言子类的 [ensureDatabase] 都要用。
     */
    protected fun boot(): Connection = DriverManager.getConnection(urlFor(bootstrapDb), user, password)

    override fun provision(scope: File): String {
        val name = "sundays_smoke"
        ensureDatabase(name)
        resetTables(name)
        return name
    }

    override fun resetTables(workspace: String) {
        if (workspace.isBlank()) return
        direct(workspace).use { c ->
            val views = listViews(c, workspace)
            val tables = listBaseTables(c, workspace)
            if (views.isEmpty() && tables.isEmpty()) return@use
            dropStatements(views, tables).forEach { sql ->
                c.createStatement().use { it.execute(sql) }
            }
        }
    }

    /** **刻意不 DROP 库** —— 见类注释。开连接就有泄漏的风险，而收益只是「不留个空库」。 */
    override fun teardown(workspace: String) = Unit

    protected fun creds(): Pair<String, String> = user to password
}

/** MySQL —— URL 上的三个参数与方言的 `buildJdbcUrl` 保持一致，否则驱动行为会不同。 */
object MySqlSmoke : ServerSmokeTarget("192.168.1.5", 3306, "root", "666666", "mysql") {
    override val label = "MySQL"
    override val dialectType = DialectType.MYSQL

    override fun registerDialect() = DialectLoader.registerForTesting("Mysql", MySQLDialect())

    override fun urlFor(database: String) =
        "jdbc:mysql://192.168.1.5:3306/$database" +
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

    override fun quoteIdent(name: String) = "`$name`"

    /**
     * MySQL 的 `CREATE DATABASE` **支持** `IF NOT EXISTS`，所以这一句天然幂等。
     *
     * ⚠️ 它**不支持** `IF NOT EXISTS` 之外的可选子句在别家数据库上的等价物 ——
     * `CHARACTER SET utf8mb4` 是 MySQL 专有的，写在这里没问题，但**绝不能**让
     * PostgreSQL 复用同一句（见 [PostgresSmoke.ensureDatabase] —— 那边直接语法错，
     * 而错会被吞掉、最终以「库不存在」的形式在别处炸出来）。
     */
    override fun ensureDatabase(name: String) {
        boot().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE DATABASE IF NOT EXISTS ${quoteIdent(name)} CHARACTER SET utf8mb4")
            }
        }
    }

    override fun listViews(conn: Connection, workspace: String): List<String> =
        conn.prepareStatement(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'VIEW'"
        ).use { st ->
            st.setString(1, workspace)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    override fun listBaseTables(conn: Connection, workspace: String): List<String> =
        conn.prepareStatement(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'"
        ).use { st ->
            st.setString(1, workspace)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    /**
     * 先关外键检查再一把 DROP 完 —— `smoke_child` 引用 `smoke_items`，
     * 不关的话单独 DROP 父表会被外键挡住，而「按依赖顺序排」在测试里维护不起。
     * 视图先删、基表后删（MySQL 里 `DROP VIEW` 与 `DROP TABLE` 不能混在一个语句里）。
     */
    override fun dropStatements(views: List<String>, tables: List<String>): List<String> = buildList {
        add("SET FOREIGN_KEY_CHECKS = 0")
        if (views.isNotEmpty()) add("DROP VIEW IF EXISTS " + views.joinToString(", ") { quoteIdent(it) })
        if (tables.isNotEmpty()) add("DROP TABLE IF EXISTS " + tables.joinToString(", ") { quoteIdent(it) })
        add("SET FOREIGN_KEY_CHECKS = 1")
    }

    override fun config(workspace: String): ConnectionConfig {
        val (u, p) = creds()
        return ConnectionConfig(
            id = "smoke-mysql", name = "SmokeMySQL", dialect = DialectType.MYSQL,
            host = "192.168.1.5", port = 3306, username = u, password = p,
            database = workspace, connectionType = ConnectionType.CLIENT_SERVER,
            jdbcUrl = urlFor(workspace),
        )
    }

    override fun direct(workspace: String): Connection {
        val (u, p) = creds()
        return DriverManager.getConnection(urlFor(workspace), u, p)
    }
}

/** PostgreSQL —— 默认用户名按 `postgres` 处理。 */
object PostgresSmoke : ServerSmokeTarget("192.168.1.5", 5432, "postgres", "666666", "postgres") {
    override val label = "PostgreSQL"
    override val dialectType = DialectType.POSTGRESQL

    override fun registerDialect() = DialectLoader.registerForTesting("Postgresql", PostgreSQLDialect())

    override fun urlFor(database: String) = "jdbc:postgresql://192.168.1.5:5432/$database"

    override fun quoteIdent(name: String) = "\"$name\""

    /**
     * PostgreSQL 的 `CREATE DATABASE` **既不支持 `IF NOT EXISTS`，也不支持
     * `CHARACTER SET`** —— 直接写 MySQL 那一句会语法错。
     *
     * 正确的幂等写法是「先查 `pg_database`，不存在再建」。
     * ⚠️ `CREATE DATABASE` 不能跑在事务块里，这里用的是自动提交的直连，没问题。
     */
    override fun ensureDatabase(name: String) {
        boot().use { c ->
            val exists = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { st ->
                st.setString(1, name)
                st.executeQuery().use { it.next() }
            }
            if (!exists) {
                c.createStatement().use { it.execute("CREATE DATABASE ${quoteIdent(name)}") }
            }
        }
    }

    override fun listViews(conn: Connection, workspace: String): List<String> =
        conn.createStatement().use { s ->
            s.executeQuery("SELECT viewname FROM pg_views WHERE schemaname = 'public'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    override fun listBaseTables(conn: Connection, workspace: String): List<String> =
        conn.createStatement().use { s ->
            // PG 里 database ≈ schema，用户表都在 public 下；`tablename` 只含基表
            s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    /**
     * 视图先、表后，多表单语句 + `CASCADE`：一次解决外键与视图的依赖顺序，
     * 不用在测试里维护拓扑。PG 里 `DROP TABLE ... CASCADE` 会连带把依赖它的视图一起带走，
     * 但视图先删更直白，也让「视图确实被显式删掉了」这件事看得见。
     */
    override fun dropStatements(views: List<String>, tables: List<String>): List<String> = buildList {
        if (views.isNotEmpty()) add("DROP VIEW IF EXISTS " + views.joinToString(", ") { quoteIdent(it) } + " CASCADE")
        if (tables.isNotEmpty()) add("DROP TABLE IF EXISTS " + tables.joinToString(", ") { quoteIdent(it) } + " CASCADE")
    }

    override fun config(workspace: String): ConnectionConfig {
        val (u, p) = creds()
        return ConnectionConfig(
            id = "smoke-postgres", name = "SmokePG", dialect = DialectType.POSTGRESQL,
            host = "192.168.1.5", port = 5432, username = u, password = p,
            database = workspace, connectionType = ConnectionType.CLIENT_SERVER,
            jdbcUrl = urlFor(workspace),
        )
    }

    override fun direct(workspace: String): Connection {
        val (u, p) = creds()
        return DriverManager.getConnection(urlFor(workspace), u, p)
    }
}

/**
 * 冒烟覆盖的五个方言：三个本地（必定可跑）+ 两个远程（不可达则 skip）。
 *
 * 远程两个走**真服务器**（192.168.1.5）—— 它们有一批只在客户端-服务器方言上
 * 才会暴露的东西（`CAST` 目标类型、DDL 隐式提交、标识符折叠、连接池 autoCommit 约定），
 * 本地嵌入式库一条都照不出来。
 */
fun smokeTargets(): List<SmokeTarget> =
    listOf(H2Smoke, SQLiteSmoke, DuckDbSmoke, MySqlSmoke, PostgresSmoke)
