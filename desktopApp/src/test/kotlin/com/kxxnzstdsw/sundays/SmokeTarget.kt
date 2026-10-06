package com.kxxnzstdsw.sundays

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

    /** 应用侧的连接描述 —— 走查全程用它构造引擎请求。 */
    fun config(workspace: String): ConnectionConfig

    /** 独立于引擎连接池的直连 —— 事务可见性与「真的落库了吗」靠它判。 */
    fun direct(workspace: String): Connection

    /**
     * 引擎能不能回**真实的**外键约束名。
     *
     * `SQLiteDialect.listForeignKeys` 走 `PRAGMA foreign_key_list`，而该 PRAGMA
     * **不暴露约束名**，方言只能拼一个 `fk_<表>_<序号>`（实测 `fk_smoke_child_0`）。
     * 要真名得解析 `sqlite_master` 的建表 SQL。其余方言走 `INFORMATION_SCHEMA`，名字是真的。
     */
    fun exposesForeignKeyName(): Boolean = true
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

    /** 见 [LocalSmokeTarget.exposesForeignKeyName]：SQLite 拿不到真名。 */
    override fun exposesForeignKeyName(): Boolean = false
}

/**
 * 客户端-服务器库的统一形态：**建一个临时 database，用完 DROP**。
 *
 * 选 database 而不是 schema，是因为 MySQL 上 `schema == database`，而 PG 侧建 database
 * 同样能做到「删掉即彻底回收」—— 两者对称，且都不碰别人的默认库。
 */
abstract class ServerSmokeTarget(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    /** 建 / 删 database 时用的引导库。 */
    private val bootstrapDb: String,
) : SmokeTarget {
    override fun reachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
        true
    }.getOrDefault(false)

    protected abstract fun urlFor(database: String): String

    /** 见 [LocalSmokeTarget.toString]：让参数化测试的名字读起来是 `[MySQL]`。 */
    override fun toString(): String = label

    private fun boot(): Connection = DriverManager.getConnection(urlFor(bootstrapDb), user, password)

    override fun provision(scope: File): String {
        val name = "sundays_smoke_${System.nanoTime()}"
        boot().use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    override fun teardown(workspace: String) {
        if (workspace.isBlank()) return
        runCatching {
            boot().use { c ->
                c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $workspace") }
            }
        }
    }

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

/** 冒烟覆盖的四个方言：两个本地（必定可跑）+ 两个远程（不可达则 skip）。 */
fun smokeTargets(): List<SmokeTarget> = listOf(H2Smoke, SQLiteSmoke, MySqlSmoke, PostgresSmoke)
