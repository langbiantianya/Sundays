/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.grpc.ConnectionConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicLong

/*
 * DuckDB 的**原生扫描器** —— 拉取的**可选加速**。
 *
 * ## 它是什么
 *
 * DuckDB 自带 `mysql_scanner` / `postgres_scanner`：装上之后可以把远端
 * **直接**当成一张表查（`ATTACH ... (TYPE mysql_scanner)`）。
 * 走这条路时，数据从远端**直接**进 DuckDB —— 少一次拷贝（纯 Java 表函数那条路是
 * 「远端 → Java → DuckDB」），而且类型映射用的是 DuckDB 官方规则（更全）。
 *
 * ## 不用装 —— 扫描器**随 JDBC 驱动一起**（实测）
 *
 * DuckDB JDBC 1.5.5.1 把这两个扫描器**静态链接**进了驱动本体
 * （官方叫 core extension）。实测证据：
 *
 * ```
 * SELECT extension_name, installed_from, install_path FROM duckdb_extensions();
 * -- mysql_scanner | core | <extension_directory>\v1.5.5\windows_amd64\mysql_scanner.duckdb_extension
 * ```
 *
 * `installed_from = core` 意味着它**不来自磁盘上的文件** —— 把
 * `extension_directory` / `extension_directories` 全指到一个空目录，
 * `LOAD mysql_scanner` 照样成功（实测）。所以对 JDBC 驱动来说，
 * 「装没装扩展」根本不是个变量：**它总是可用的**。
 *
 * 那扩展目录配置还有什么用？留给「想换成别的构建」的场景 ——
 * 指到某个目录，DuckDB 就从那儿找同名扩展；加载不上就降级回纯 Java 表函数。
 * 换句话说：它是**可选的加速来源**，不是**能不能用**的前提。
 *
 * ## 为什么**默认关**
 *
 * 两条路的**类型映射不一样**：
 *
 * | 远端列 | 原生扫描器 | 纯 Java 表函数（我们的映射） |
 * |---|---|---|
 * | MySQL `TINYINT(1)` | `BOOLEAN`（`mysql_tinyint1_as_boolean` 默认开） | `INTEGER` |
 * | MySQL `BIT(1)` | `BOOLEAN` | `INTEGER` |
 *
 * 如果「走哪条路」能**隐式**决定类型，那么同一张表在两台机器上拉出来
 * 类型就可能不同 —— 那正是「同一个判据写两遍，迟早会不一致」。
 * 所以这里做成**显式开关**：默认永远是纯 Java 表函数（行为一致），
 * 要性能的用户自己开。
 *
 * ## 开关与目录
 *
 * | 旋钮 | 系统属性 | 环境变量 | 默认 |
 * |---|---|---|---|
 * | 是否允许用原生扫描器 | `sundays.duckdb.nativeExtension` | `SUNDAYS_DUCKDB_NATIVE_EXT` | **关** |
 * | 扩展搜索目录（`;` 分隔） | `sundays.duckdb.extensionDirs` | `SUNDAYS_DUCKDB_EXTENSION_DIRS` | 应用自带目录 + DuckDB 默认的用户目录 |
 *
 * 系统属性排在前面，是为了**测试能改**它（环境变量在进程内改不了）。
 */

/**
 * 原生扫描器 —— 「哪个远端方言对应哪个 DuckDB 扩展」。
 *
 * @param extensionType DuckDB 的 `TYPE` 值（`mysql_scanner` / `postgres_scanner`）
 * @param uriScheme 远端 URI 的 scheme（`mysql` / `postgresql`）
 */
internal data class NativeScanner(
    val extensionType: String,
    val uriScheme: String,
)

/**
 * 原生扫描器的**可选加速**通道。
 *
 * 这条路**永远不是唯一那条**：[AnalysisHandler] 先试它，失败就降级到
 * 纯 Java 表函数（那条路不需要任何扩展，任何有 JDBC 驱动的源都能走）。
 *
 * ## 三段式：挂载 → 建表 → 摘除
 *
 * 这三步**必须分开**，而且**顺序不能换**：
 *
 * | 阶段 | 在事务里吗 | 做什么 |
 * |---|---|---|
 * | [attach] | **否** | `SET extension_directories` → `LOAD` → `ATTACH` |
 * | [createStaging] | **是** | `DROP TABLE IF EXISTS` → `CREATE TABLE … AS SELECT` |
 * | [detach] | **否** | `DETACH` |
 *
 * ## 为什么摘除**必须**排在提交之后（实测抓出来的）
 *
 * DuckDB 不许在一个**还有未完成工作**的事务里 DETACH：
 *
 * ```
 * TransactionContext Error: Cannot detach database "m" because the current
 * transaction has outstanding work on it - commit or rollback first
 * ```
 *
 * 而这条错误更狠的地方在于：**它会中止当前事务**。于是
 *
 * ```
 * CREATE TABLE … → DETACH（报错，事务被中止）→ commit（成了空操作）→ 表整张消失
 * ```
 *
 * 症状极具迷惑性：**拉取报成功、行数也对**，可工作区里就是查不到那张表
 * （`duckdb_tables()` 是空的）。第一版就是把 DETACH 写在 `finally` 里、
 * 也就是提交之前栽的。
 *
 * 顺带一条同样重要的实测结论：**DuckDB JDBC 里一条语句执行失败后，这条语句
 * 就被关闭了**（后续调用报 `Statement was closed`）。所以任何一个阶段失败，
 * 都必须**换一条新语句**继续，绝不能接着用原来那条。
 */
internal object NativeExtensionScanner {
    private val logger = LoggerFactory.getLogger(NativeExtensionScanner::class.java)

    /** 系统属性 → 环境变量（前者优先：测试要能改它）。 */
    private const val ENABLED_PROPERTY = "sundays.duckdb.nativeExtension"

    private const val ENABLED_ENV = "SUNDAYS_DUCKDB_NATIVE_EXT"
    private const val DIRS_PROPERTY = "sundays.duckdb.extensionDirs"
    private const val DIRS_ENV = "SUNDAYS_DUCKDB_EXTENSION_DIRS"

    /**
     * 远端方言的 **JDBC URL 前缀** → 原生扫描器。
     *
     * 键取自方言**自己声明**的 `jdbcUrlPrefix` —— 不是按方言名硬判
     * （那等于把「哪个方言有什么概念」复制一份到别处，副本迟早与本体对不上）。
     */
    private val SCANNERS: Map<String, NativeScanner> =
        mapOf(
            "jdbc:mysql:" to NativeScanner(extensionType = "mysql_scanner", uriScheme = "mysql"),
            "jdbc:postgresql:" to NativeScanner(extensionType = "postgres_scanner", uriScheme = "postgresql"),
        )

    /** ATTACH 的别名自增尾数 —— 每次尝试都用**新**别名，免得撞名。 */
    private val aliasCounter = AtomicLong(0)

    /** 允许用原生扫描器吗 —— **默认关**。 */
    fun isEnabled(): Boolean {
        val flag =
            System.getProperty(ENABLED_PROPERTY)
                ?: System.getenv(ENABLED_ENV)
                ?: return false
        return flag.equals("1", ignoreCase = true) || flag.equals("true", ignoreCase = true)
    }

    /**
     * 扩展搜索目录 —— 装到**第一个**，从**任意一个**加载。
     *
     * 「应用自带目录 + 用户目录」这个组合正是为**随包分发**准备的：
     * 包里带一份、用户自己 `INSTALL` 一份，两边都能用。
     *
     * ## 但对 JDBC 驱动来说它**不是前提**
     *
     * MySQL / PG 的扫描器是**静态链接**进驱动的（见文件头），
     * 所以这个目录配置实际管的是「**换成别的构建**」——
     * 想用更新版的扫描器就把目录指过去；指错了、那儿的东西加载不上，
     * 就降级回纯 Java 表函数（用户看到的仍然是「拉取成功」）。
     *
     * ## 显式配了就**以配置为准**（不再自动补用户目录）
     *
     * 「配了就当没配过」是两种解读，这里选前者：用户显式指定了目录，
     * 就是要**完全**控制扩展从哪儿来 —— 自动补一个用户目录等于
     * 偷偷加了一条他没同意的来源。
     */
    fun extensionDirectories(): List<String> {
        val configured =
            (System.getProperty(DIRS_PROPERTY) ?: System.getenv(DIRS_ENV))
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (configured.isNotEmpty()) return configured.distinct()
        val dirs = mutableListOf<String>()
        // 应用自带的那份：放在**代码所在目录**旁边（Direct 模式下就是应用目录，
        // 服务端模式下就是引擎目录）—— 与 `DriverLoader` 找 jar 的方式同源。
        appDirectory()?.resolve("extensions")?.takeIf { it.isDirectory }?.let { dirs += it.absolutePath }
        // DuckDB 默认的用户目录（`~/.duckdb/extensions`）—— 用户自己 INSTALL 的扩展在那儿，
        // 把它漏掉就等于「用户装了扩展也没人用」。
        System.getProperty("user.home")?.let { home ->
            dirs += File(home, ".duckdb/extensions").absolutePath
        }
        return dirs.distinct()
    }

    /** 代码所在目录（jar 或 class 目录的父目录）。 */
    private fun appDirectory(): File? =
        try {
            val domain = NativeExtensionScanner::class.java.protectionDomain
            val source = domain?.codeSource?.location ?: return null
            File(source.toURI()).parentFile
        } catch (_: Exception) {
            null
        }

    /**
     * 这个远端方言有原生扫描器吗。
     *
     * 没有 → 走纯 Java 表函数（那才是**通用**的那条路）。
     */
    fun scannerFor(dialect: DatabaseDialect): NativeScanner? = SCANNERS[dialect.jdbcUrlPrefix]

    /**
     * 构造扫描器的连接 URI。
     *
     * ## 形状：`scheme://user:password@host:port[/database]`
     *
     * 两种 scheme 都吃这个形状（实测）：MySQL 的扫描器认 `mysql://`，
     * PG 的扫描器认 `postgresql://`（libpq 的 URI 形式）。
     * 于是**连接串的键名差异（`database=` vs `dbname=`）整个绕开了** ——
     * 混着写是不行的（实测：未知键会被直接拒）。
     *
     * ## 路径（也就是「带不带库」）看**命名空间的性质**
     *
     * | 远端 | 命名空间是 | URI 要不要带库 | 挂上之后的限定名 |
     * |---|---|---|---|
     * | MySQL | **库** | **不带** —— 带了就把库钉死，选别的库就看不到了 | `别名."库"."表"` |
     * | PostgreSQL | **schema** | **带**（连接的那个库） | `别名."schema"."表"` |
     *
     * 这个判据就是既有的 `DatabaseDialect.supportsSchema` ——
     * 分析模式列命名空间时用的**同一个**判据，不新增方言知识。
     *
     * 顺带一条实测结论：MySQL 扫描器**不带库**挂载时，会把服务器上的
     * **所有库**都挂成这个挂载点下的 schema —— 于是「树上选哪个库都能拉」，
     * 限定名统一是 `别名."库"."表"`。
     *
     * @return URI；拼不出来（缺主机 / 自定义 URL）时返回 `null` → 降级
     */
    fun buildUri(
        remote: ConnectionConfig,
        dialect: DatabaseDialect,
        scanner: NativeScanner,
    ): String? {
        val endpoint =
            try {
                URI(remote.jdbcUrl.removePrefix("jdbc:"))
            } catch (_: Exception) {
                return null
            }
        val host = endpoint.host ?: return null
        val port = endpoint.port.takeIf { it > 0 } ?: return null
        val builder = StringBuilder()
        builder.append(scanner.uriScheme).append("://")
        if (remote.user.isNotEmpty()) {
            builder.append(percentEncode(remote.user)).append(':')
        }
        builder.append(percentEncode(remote.password)).append('@')
        builder.append(host).append(':').append(port)
        // schema 型方言：库由连接决定，URI 必须带上它；
        // 库型方言：**不带**库 —— 否则就把库钉死了，树上选别的库就看不到。
        if (dialect.supportsSchema) {
            val database = remote.database.ifBlank { endpoint.path?.trim('/').orEmpty() }
            if (database.isNotEmpty()) {
                builder.append('/').append(percentEncode(database))
            }
        }
        return builder.toString()
    }

    /**
     * URI 的 userinfo 段百分号编码。
     *
     * 只放行「非保留字符」—— 其余全部编码。
     *
     * 为什么要编码：密码里出现 `@` / `:` / `/` 就会把 URI 的结构拆坏，
     * 轻则连错库，重则**连到别的主机**去。编码之后就没有这个歧义了。
     */
    private fun percentEncode(value: String): String {
        val out = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val ch = byte.toInt().toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~".toList()) {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /** 新挂载点的别名 —— 每次尝试都用**新**别名，免得撞名。 */
    fun nextAlias(): String = "sundays_remote_${aliasCounter.incrementAndGet()}"

    /**
     * **阶段 ①**：把远端挂到工作区上 —— 在**事务外**做。
     *
     * ## 失败一律**降级**，不抛给用户
     *
     * 这个方言没有对应的扫描器（H2 / SQLite / DuckDB）、连接串拼不出来
     * （自定义 JDBC URL 没有主机）、挂载失败（凭据不对、网络不通、没权限）——
     * 这些都不该让用户看见「拉取失败」：纯 Java 表函数那条路**永远**能走。
     *
     * @param alias 挂载点别名（由调用方生成，好让失败时也知道该摘哪个）
     * @return 挂上了吗；`false` = 这条路不可用 → 调用方降级
     */
    fun attach(
        workspace: Connection,
        remote: ConnectionConfig,
        dialect: DatabaseDialect,
        alias: String,
    ): Boolean {
        val scanner = scannerFor(dialect) ?: return false
        val uri = buildUri(remote, dialect, scanner) ?: return false
        return try {
            workspace.createStatement().use { stmt ->
                applyExtensionDirectories(stmt)
                stmt.execute("LOAD ${scanner.extensionType}")
                stmt.execute(
                    "ATTACH '$uri' AS $alias (TYPE ${scanner.extensionType}, READ_ONLY)",
                )
            }
            true
        } catch (e: SQLException) {
            logger.warn(
                "原生扫描器（{}）不可用，改用纯 Java 表函数：{}",
                scanner.extensionType,
                e.message,
            )
            false
        }
    }

    /**
     * **阶段 ②**：把远端的表搬进工作区 —— 在**事务里**做。
     *
     * 只认自己拼的那一句 `SELECT * FROM <挂载点>.<命名空间>.<表>` ——
     * **不接受**调用方传入的 SQL。所以这条链路上压根没有写入口：
     * 比「会话级只读开关」更强，开关靠每个写入口都记得检查，而这里没有写入口。
     *
     * @param statement 事务里**共用**的那条语句
     * @param alias 阶段 ① 挂上去的别名
     * @param namespace 命名空间（MySQL 上是库，PG 上是 schema）
     * @param table 表名
     * @param staging 落点表名（**已按工作区方言引用好**）
     * @return 落地的行数
     */
    fun createStaging(
        statement: Statement,
        alias: String,
        namespace: String,
        table: String,
        staging: String,
    ): Long {
        val qualified = "$alias.${quoteIdentifier(namespace)}.${quoteIdentifier(table)}"
        statement.execute("DROP TABLE IF EXISTS $staging")
        statement.execute("CREATE TABLE $staging AS SELECT * FROM $qualified")
        statement.executeQuery("SELECT count(*) FROM $staging").use { rs ->
            rs.next()
            return rs.getLong(1)
        }
    }

    /**
     * **阶段 ③**：摘掉挂载 —— 在**提交之后**做。
     *
     * ## 为什么必须摘
     *
     * 挂着的远端会留在 DuckDB 的目录表里，而那条记录的 `path` **含明文密码**
     * （实测：`SELECT path FROM duckdb_databases()` 直接就是
     * `mysql://root:666666@…`）。挂着不摘，等于把密码留在工作区里，
     * 用户在编辑器里 `SELECT * FROM duckdb_databases()` 就能看见。
     *
     * ## 为什么**不能**放在事务里
     *
     * 见类注释：事务里 DETACH 会报错并**中止事务** → 提交落空 → 表整张消失。
     *
     * ## 摘不掉也不致命
     *
     * 失败只记日志：下次的别名是新的，不会撞名；
     * 而「摘不掉」最坏的结果是工作区里多一条带密码的目录记录 ——
     * 比「整次拉取失败」轻得多，所以这里选了「尽力而为」。
     */
    fun detach(
        workspace: Connection,
        alias: String,
    ) {
        runCatching {
            // DETACH 必须不在事务里：把连接切回自动提交，
            // 免得它在一条还开着的事务里执行（那就成了上面说的那个坑）。
            workspace.autoCommit = true
            workspace.createStatement().use { stmt ->
                stmt.execute("DETACH $alias")
            }
        }.onFailure {
            logger.warn("DETACH $alias 失败：{}", it.message)
        }
    }

    /**
     * 扩展搜索目录 —— 只在**真的有目录**时才设。
     *
     * 设了 `extension_directories` 就等于**接管**了 DuckDB 的扩展查找 ——
     * 所以清单里必须包含 DuckDB 默认的用户目录（`extensionDirectories()` 已经带上了）。
     */
    private fun applyExtensionDirectories(statement: Statement) {
        val dirs = extensionDirectories()
        if (dirs.isEmpty()) return
        val literal = dirs.joinToString(", ") { "'${it.replace("'", "''")}'" }
        statement.execute("SET extension_directories = [$literal]")
    }

    /**
     * 标识符引用（DuckDB 用双引号，内嵌的 `"` 写成两个）。
     *
     * 远端的命名空间与表名是**用户起的** —— 不引用就会被当成关键字
     * （`order` / `select` 之类直接语法错）或被大小写规则改写。
     */
    private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
