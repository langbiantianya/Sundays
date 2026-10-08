package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺陷：**PostgreSQL 用户库里，导航树把整个系统目录当成用户的表列出来**。
 *
 * 真窗口走查（探针 PG `192.168.1.5:5432`，库 `sundays_smoke`）展开该库时看到的第一屏是
 *
 * ```
 * pg_aggregate / pg_aio s / pg_am / pg_amop / pg_attrdef / pg_attribute /
 * pg_auth_members / pg_authid / pg_available_extension_versions / …
 * ```
 *
 * 真正的业务表（`smoke_items` / `smoke_tx` …）被挤到 60 多行之后，用户要找的那张表
 * 要滚过一整个 PostgreSQL 系统目录才看得见。
 *
 * ## 根因
 *
 * `PostgreSQLDialect.listTables` 在 **schema 为空** 时（UI 正是这么调的 ——
 * `DatabaseBrowserScreen.kt` 里 `tableRequest { list = tableListRequest {} }`，
 * 不带 schema）走这条 SQL：
 *
 * ```sql
 * WHERE table_schema = ANY(current_schemas(true))
 * ```
 *
 * `current_schemas(include_implicit boolean)` 的 `true` 表示**连隐式的也返回**，
 * 而 `pg_catalog` 正是隐式搜索路径的第一项。实测（`search_path = "$user", public`）：
 *
 * ```
 * current_schemas(true)  = {pg_catalog, public}
 * current_schemas(false) = {public}
 * ```
 *
 * 致命的一环是 `information_schema.tables` **并不只暴露用户表**：PG 的系统目录表
 * （`pg_class` / `pg_attribute` / `pg_type` …）在里面同样以 `BASE TABLE` 出现，
 * `table_schema` 就是 `pg_catalog`。实测目标库有 **64 张系统表 + 80 个系统视图**。
 * 两个条件一叠加，整个系统目录就全进了结果集。
 *
 * 改成 `current_schemas(false)`（只返回 search_path 里**显式**写出的 schema，
 * 默认即 `public`）即可 —— **不必**再补 `NOT IN ('pg_catalog', …)`：`(false)`
 * 本来就不返回它，而 `information_schema` 连 `(true)` 都不在（见上面实测）。
 *
 * ## 为什么这条以前没被任何单测抓到
 *
 * 1. `DialectSmokeTest` 是四方言参数化的，**没有任何一条断言表列表的内容**——
 *    S1 只验「建表后能写读回来」，走的是 DATA.LIST，不经过 listTables。
 * 2. `DatabaseBrowserFlowTest` 用的是 **H2**，而 H2 走 `CURRENT_SCHEMA`，
 *    天然只返回 `PUBLIC`，结构上就撞不到这个坑。
 * 3. 真库这条只有 GUI 走查能暴露 —— 这也是为什么必须真窗口点一遍。
 *
 * ⚠️ 这也解释了为什么 **MySQL 侧从来没出过这个问题**：MySQL 的系统对象住在
 * *独立的 database*（`information_schema` / `performance_schema` / `mysql` / `sys`），
 * 在**库列表**那一层就被挡掉了，根本到不了表列表。PG 的系统对象藏在 **schema 层**，
 * 库列表挡不住 —— 同一份「不算系统对象」的意图，两个方言的过滤点天然不在一层。
 *
 * 不可达时按 [DialectSmokeTest] 的约定 `assumeTrue` 显式 skip，报告里留一条 skipped。
 */
class PostgreSQLSystemObjectTest {

    companion object {
        /** 复用冒烟测试的固定库，不再多留一个。 */
        private const val DB = "sundays_smoke"

        /** 本测试建的表 —— 同时充当「正向判据」：过滤过头把用户表滤掉也要红。 */
        private const val USER_TABLE = "probe_user_table"

        /**
         * 两个 schema：**在** search_path 里。用来钉住「`current_schemas(false)` 取的是
         * search_path 里的**全部** schema，而不是碰巧等于 public」。
         *
         * ⚠️ 这一条连接参数是本文件的关键：没有它，所有断言都只在 `public` 上验过，
         * 于是「把条件写成 `table_schema = 'public'`」这种改法会**全绿通过** ——
         * 而那种改法让用户自己建的 schema 里的表永远列不出来。
         * 判据见 [search_path 里的多个 schema 都可见]。
         */
        private const val ON_PATH_SCHEMA = "probe_ns"
        private const val ON_PATH_TABLE = "probe_ns_table"

        /**
         * 一个**不在** search_path 里的 schema。
         *
         * `current_schemas(false)` 的语义就是「搜索路径里的那批」，所以它的表**不该**
         * 出现在 `schema=""` 的列表里，显式点名时才有 —— 见 [不在搜索路径里的 schema 只在显式点名时可见]。
         *
         * 这条抓的是「干脆列举全部 schema」那种改法（`NOT IN ('pg_catalog')` 之类）：
         * 那种改法在只有 public 的库上与本实现**完全等价**，只有真的多一个 schema 才分得开。
         */
        private const val OFF_PATH_SCHEMA = "probe_hidden"
        private const val OFF_PATH_TABLE = "probe_hidden_table"

        /**
         * PG 系统目录表的代表性名字。
         *
         * 只列**确实会在 `information_schema.tables` 里以 BASE TABLE 出现**的那些，
         * 否则名单里混进 `pg_aios` 这种实际是 `VIEW` 的对象，断言语义就含混了。
         */
        private val KNOWN_SYSTEM_TABLES = listOf(
            "pg_class", "pg_attribute", "pg_type", "pg_index", "pg_constraint",
            "pg_aggregate", "pg_namespace", "pg_proc", "pg_settings",
        )

        /**
         * 为什么**没有**一条「information_schema 的表不进列表」的断言。
         *
         * 写过，删了 —— 它是一条**永远绿、什么都验不到**的用例，比没有测试更糟：
         *
         * - `information_schema.tables` 里确实有 69 个 `information_schema` schema 下的对象
         * - 但 `information_schema` **压根不在 `current_schemas()` 的返回值里**
         *   （实测：`(true)` = `{pg_catalog, public}`，连 `(true)` 都没有它）
         *
         * 也就是说：无论 `listTables` 写成 `true` 还是 `false`，这条断言都成立。
         * 缺陷能被抓住，靠的是 [KNOWN_SYSTEM_TABLES] 那一条 ——
         * **断言必须落在真实会被打破的地方**，否则只是在给一条死代码拍照。
         */
    }

    private lateinit var tempDir: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        assumeTrue("[PostgreSQL] 远程库不可达，跳过（内网地址，换台机器就不存在）", PostgresSmoke.reachable())

        tempDir = Files.createTempDirectory("sundays-pg-sys").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)

        PostgresSmoke.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))

        // ⚠️ 表级 DDL，不 DROP DATABASE —— `ServerSmokeTarget` 的类注释里记着
        // 「先 DROP 再关引擎」把服务端连接打爆的历史事故。
        PostgresSmoke.direct(DB).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS \"$USER_TABLE\"")
                s.execute("CREATE TABLE \"$USER_TABLE\" (id INT PRIMARY KEY, label VARCHAR(64))")

                for ((schema, table) in listOf(ON_PATH_SCHEMA to ON_PATH_TABLE, OFF_PATH_SCHEMA to OFF_PATH_TABLE)) {
                    s.execute("CREATE SCHEMA IF NOT EXISTS \"$schema\"")
                    s.execute("DROP TABLE IF EXISTS \"$schema\".\"$table\"")
                    s.execute("CREATE TABLE \"$schema\".\"$table\" (id INT PRIMARY KEY, label VARCHAR(64))")
                }
            }
        }
    }

    @After
    fun tearDown() {
        try { engine.close() } catch (e: Exception) { println("[PG-SYS] engine.close 抛了：$e") }
        dropOwnSchemas()
        System.setProperty("user.home", originalHome)
        tempDir.deleteRecursively()
    }

    /**
     * 把自己建的两个 schema 删掉。
     *
     * ⚠️ **必须删**：`sundays_smoke` 是 `DialectSmokeTest` / `CrossDatabaseQueryTest` 的
     * **共享固定库**，不是本测试私有的。留着它们会污染别人 ——
     *
     * 实测踩到过：这里建了 `probe_hidden` / `probe_ns`（字母序排在 `public` 前面），
     * 而 `DialectSmokeTest.resolveSchema()` 取的是 schema 列表**第一个**，
     * 于是它拿着 `probe_hidden` 去查视图，S3 直接红成「视图列表为空」。
     *
     * 那条链路上一环都不相干：不是视图坏了，是**别人的查询打到了错的 schema**。
     * （`resolveSchema()` 本身也已改成点名 `public` —— 两处都修，才不靠运气。）
     */
    private fun dropOwnSchemas() {
        runCatching {
            PostgresSmoke.direct(DB).use { c ->
                c.createStatement().use { s ->
                    for (schema in listOf(ON_PATH_SCHEMA, OFF_PATH_SCHEMA)) {
                        s.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE")
                    }
                }
            }
        }.onFailure { println("[PG-SYS] 清理 schema 失败：$it") }
    }

    /**
     * 主判据：**UI 走的那条路径**（`tableListRequest {}`，schema 空）不得返回系统对象。
     *
     * 正反两个方向都要：
     * - 反向（无系统表）抓 `current_schemas(true)` 回归
     * - 正向（有用户表）抓「干脆不查了」这种过度修复 —— 那同样是把用户害了
     */
    @Test
    fun `不指定 schema 的表列表里没有系统目录表`() {
        val names = listTables(schema = "").map { it.lowercase() }

        println("[PG-SYS] TABLE.LIST(schema=\"\") = $names")

        val leaked = names.filter { it in KNOWN_SYSTEM_TABLES.map(String::lowercase) }
        assertEquals(
            emptyList(), leaked,
            "⚠️ 用户表的列表里混进了 PG 系统目录表 —— 展开任意业务库都会被 60 多行系统表淹没",
        )

        assertTrue(
            names.none { it.startsWith("pg_") },
            "不应出现任何 pg_ 前缀的对象（pg_ 也是 PG 保留前缀），实际含：${names.filter { it.startsWith("pg_") }}",
        )

        assertTrue(
            names.contains(USER_TABLE.lowercase()),
            "⚠️ 反向护栏：过滤过头把**用户表**也滤掉了。用户表必须在列表里，实际 $names",
        )
    }

    /**
     * `search_path` 里的**多个** schema 都可见 —— 这条钉住的是
     * `current_schemas(false)` 而**不是**硬编码 `= 'public'`。
     *
     * 引擎连接的 search_path 由 [engineConfig] 的 `?currentSchema=` 定成
     * `public, probe_ns`（见 [ON_PATH_SCHEMA] 的注释）。于是：
     *
     * - `current_schemas(false)` = `{public, probe_ns}` → 两张表都该列出来
     * - 硬编码 `table_schema = 'public'` → 只列 public，**`probe_ns_table` 被漏掉**
     *
     * 只有把库撑成多 schema 才能分开这两种实现 —— 在只有 public 的库上它们完全等价，
     * 测试会一直绿着，然后某天用户建了自己的 schema 就发现表列不出来。
     */
    @Test
    fun `search_path 里的多个 schema 都可见`() {
        val names = listTables(schema = "").map { it.lowercase() }

        println("[PG-SYS] TABLE.LIST(schema=\"\") = $names")

        assertTrue(
            USER_TABLE.lowercase() in names,
            "public 里的用户表必须在列表里，实际 $names",
        )
        assertTrue(
            ON_PATH_TABLE.lowercase() in names,
            "⚠️ $ON_PATH_SCHEMA 在 search_path 里，它的表却没列出来 —— " +
                "过滤条件被写死成 'public' 之类了？那会让用户自建 schema 里的表永远不可见。实际 $names",
        )
    }

    /**
     * 反过来：**不在** search_path 里的 schema，其表不该混进默认列表。
     *
     * 抓的是「干脆列举全部 schema」那种改法（`NOT IN ('pg_catalog')` 之类）——
     * 那种改法在本库上与本实现**完全等价**，只有真的多一个 schema 才分得开。
     */
    @Test
    fun `不在搜索路径里的 schema 只在显式点名时可见`() {
        val implicit = listTables(schema = "").map { it.lowercase() }
        val explicit = listTables(schema = OFF_PATH_SCHEMA).map { it.lowercase() }

        println("[PG-SYS] TABLE.LIST(schema=\"\")            = $implicit")
        println("[PG-SYS] TABLE.LIST(schema=\"$OFF_PATH_SCHEMA\") = $explicit")

        assertTrue(
            OFF_PATH_TABLE.lowercase() !in implicit,
            "$OFF_PATH_SCHEMA 不在 search_path 里，它的表不该出现在 schema=\"\" 的列表中 —— " +
                "说明过滤条件被改成了「列举全部 schema」。混入的是：" +
                implicit.filter { it == OFF_PATH_TABLE.lowercase() },
        )
        assertEquals(
            listOf(OFF_PATH_TABLE.lowercase()), explicit,
            "显式点名 $OFF_PATH_SCHEMA 时必须能列到它的表（且只有它）",
        )
    }

    /**
     * 显式指定业务 schema 的那条路径同样要干净。
     *
     * UI 目前不下发 schema（恒为空），但这是 `listTables` 的另一半实现 ——
     * 只修一半的话，日后任何一处补上 `schema = "public"` 就会把老问题原样带回来。
     */
    @Test
    fun `显式指定业务 schema 时同样没有系统目录表`() {
        val names = listTables(schema = "public").map { it.lowercase() }

        println("[PG-SYS] TABLE.LIST(schema=\"public\") = $names")

        assertTrue(
            names.none { it.startsWith("pg_") },
            "schema=public 时不应出现系统对象，实际 $names",
        )
        assertTrue(
            names.contains(USER_TABLE.lowercase()),
            "schema=public 时用户表必须在列表里，实际 $names",
        )
    }

    /**
     * 回归护栏：`SCHEMA.LIST` 一直是对的（`pg_namespace` 那侧有 `NOT LIKE 'pg_%'`），
     * 这条把「两层都要干净」钉住。
     *
     * ⚠️ 同一个「不算系统对象」的判断必须两层都在，且都要有断言 ——
     * 本次缺陷正是 `listSchemas` 过滤了、`listTables` 忘了。
     */
    @Test
    fun `schema 列表不含系统 schema`() {
        val resp: Response = runBlocking {
            engine.invoke(engineConfig()) {
                category = Category.SCHEMA
                action = Action.LIST
                schemaRequest = schemaRequest {
                    // ⚠️ `level` 必须给 `"schema"`：默认是 `"database"`，那样拿到的是库列表，
                    // 这条用例会**永远绿**（库列表里本来就没有 pg_catalog），等于什么都没验。
                    list = schemaListRequest {
                        level = "schema"
                        database = DB
                    }
                }
            }
        }
        assertTrue(resp.success, "SCHEMA.LIST 失败：${resp.error}")

        // `SchemaListResponse.items` 是 `repeated string`（库名或 schema 名），
        // 不是对象列表 —— 这一层 proto 与 TABLE 那边不一样。
        val schemas = resp.schema.list.itemsList
        println("[PG-SYS] SCHEMA.LIST = $schemas")

        assertTrue(
            schemas.none { it.startsWith("pg_") || it == "information_schema" },
            "schema 列表不应含系统 schema，实际 $schemas",
        )
        // 正向护栏：过滤过头把用户 schema 也滤掉，同样是把用户害了
        assertTrue(
            ON_PATH_SCHEMA in schemas && OFF_PATH_SCHEMA in schemas,
            "本测试建的用户 schema 必须出现在列表里，实际 $schemas",
        )
    }

    private fun listTables(schema: String): List<String> {
        val resp: Response = runBlocking {
            engine.invoke(engineConfig()) {
                category = Category.TABLE
                action = Action.LIST
                tableRequest = tableRequest {
                    list = tableListRequest { this.schema = schema }
                }
            }
        }
        assertTrue(resp.success, "TABLE.LIST(schema=\"$schema\") 失败：${resp.error}")
        return resp.table.list.itemsList.map { it.name }
    }

    /**
     * 引擎侧连接配置 —— 形状照抄 `CrossDatabaseQueryTest.engineConfig`：
     * `jdbcUrl` 与凭据来自连接配置，`database` 带上被选中的库。
     *
     * 主机/端口/口令都取自 [PostgresSmoke.config] 而不是在这里重写一遍 ——
     * 凭据换地址时只改一处，测试不会偷偷指向另一台机器。
     */
    private val baseConn = PostgresSmoke.config(DB)

    /**
     * `?currentSchema=` 是 PG JDBC 驱动对 `search_path` 的连接参数。
     *
     * ⚠️ 它的作用不是「多列一个 schema」，而是**把库撑成多 schema**：
     * 只有当 search_path 里有 `public` 以外的东西时，
     * 「`current_schemas(false)`」和「硬编码 `= 'public'`」才是两种不同的实现，
     * 前者对、后者错 —— 而在只有 public 的库上两者**完全等价**，测试会一直绿着。
     * 理由见 [search_path 里的多个 schema 都可见]。
     *
     * `probe_hidden` **故意不放进来** —— 它是 [不在搜索路径里的 schema 只在显式点名时可见] 的判据。
     */
    private fun engineConfig() = connectionConfig {
        driver = baseConn.dialect.engineDriverName
        jdbcUrl = "${baseConn.jdbcUrl}?currentSchema=public,$ON_PATH_SCHEMA"
        user = baseConn.username
        password = baseConn.password
        database = DB
    }
}
