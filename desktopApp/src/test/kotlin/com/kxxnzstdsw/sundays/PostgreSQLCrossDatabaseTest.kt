package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
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
 * 缺陷：**PostgreSQL 上从左侧树选的库根本没生效** —— 连上之后无论点哪个库，
 * 读到的都是连接配置里那个 bootstrap 库的内容。
 *
 * 真窗口走查（探针 PG `192.168.1.5:5432`，连接配置里库 = `postgres`）：
 * 点开 `sundays_smoke` 与 `examquestions`，两者都显示 `(空)`。
 *
 * ## 根因
 *
 * PG 的**数据库是连接的启动参数** —— JDBC 没有 MySQL 那种 `USE`，
 * 连接一建立就锁死在 URL 指的那个库上。而
 * [com.kxxnzstdsw.dialect.DatabaseDialect.switchCatalog] 对 PG 是**空实现**
 * （当时的判断写着「PG catalog == database，连上就锁死，无意义」）。
 *
 * 那个判断只覆盖了「连接配置的库就是唯一要用的库」这一种情形，漏掉了 sundays 的实际用法：
 * **一个连接浏览多个库**（PG 方言 `supportsCrossDatabase = true`）。
 *
 * 于是建池用的 URL 始终是 `jdbc:postgresql://host:5432/postgres`，
 * 用户点 `sundays_smoke` 时 `information_schema` 查的仍是 `postgres` 库 ——
 * 静默读到错的库，比报错危险得多。
 *
 * 修法见 [PostgreSQLDialect.jdbcUrlForCatalog]：切库只能发生在**建池时**，
 * 把 URL 的库名段换掉。
 *
 * ## 为什么必须打真 PG
 *
 * H2 造不出等价场景：它的 `setCatalog` 是**静默 no-op**
 * （`CrossDatabaseQueryTest` 的类注释里记着这条）。
 * 拿它写测试会得到一条**永远绿、但什么都验不到**的用例 —— 比没有测试更糟。
 *
 * 不可达时按 [DialectSmokeTest] 的约定 `assumeTrue` 显式 skip。
 */
class PostgreSQLCrossDatabaseTest {

    companion object {
        /**
         * 连接配置里的库（bootstrap）。
         *
         * ⚠️ **必须**与 [TARGET_DB] 不同 —— 本类验的就是「URL 指 A、选 B」时读不读得到 B。
         * 两者相同的话，`jdbcUrlForCatalog` 改写出来的 URL 与原 URL 一模一样，
         * 测试会一直绿着，而缺陷照旧。
         */
        private const val BOOTSTRAP_DB = "postgres"

        /** 树上被选中的库。 */
        private const val TARGET_DB = "sundays_smoke"

        /** 只在 [TARGET_DB] 里的表。 */
        private const val UNIQUE_TABLE = "pgx_only_in_target"

        /** 两个库都有的表，内容不同 —— 用来抓「静默读错库」。 */
        private const val SHARED_TABLE = "pgx_shared_name"
    }

    private lateinit var tempDir: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine

    /**
     * 引擎侧连接配置 —— 这正是桌面端的真实形状：
     * `jdbcUrl` 来自连接配置（**指向 bootstrap 库**），
     * `database` 来自树里点开的那个节点。
     */
    private val baseConn = PostgresSmoke.config(BOOTSTRAP_DB)

    @Before
    fun setUp() {
        assumeTrue("[PostgreSQL] 远程库不可达，跳过（内网地址，换台机器就不存在）", PostgresSmoke.reachable())

        tempDir = Files.createTempDirectory("sundays-pg-xdb").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)

        PostgresSmoke.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))

        provisionTables()
    }

    @After
    fun tearDown() {
        try { engine.close() } catch (e: Exception) { println("[PG-XDB] engine.close 抛了：$e") }
        dropOwnTables()
        System.setProperty("user.home", originalHome)
        tempDir.deleteRecursively()
    }

    /**
     * 把自己建的表删掉 —— 两个库都是**共享固定库**（`sundays_smoke` / `postgres`），
     * 不是本测试私有的。留着只会给后来的人制造「这库里怎么有张没人认领的表」。
     *
     * ⚠️ 用 `DROP TABLE IF EXISTS`，幂等 —— 清理失败不该让测试变红。
     */
    private fun dropOwnTables() {
        val owned = listOf(UNIQUE_TABLE, SHARED_TABLE, "pgx_only_in_bootstrap")
        runCatching {
            for (db in listOf(BOOTSTRAP_DB, TARGET_DB)) {
                PostgresSmoke.direct(db).use { c ->
                    c.createStatement().use { s ->
                        for (table in owned) s.execute("DROP TABLE IF EXISTS \"$table\" CASCADE")
                    }
                }
            }
        }.onFailure { println("[PG-XDB] 清理表失败：$it") }
    }

    /**
     * 两个库各建一张表。
     *
     * ⚠️ 表级 DDL，`DROP TABLE IF EXISTS` + `CREATE` 是幂等的 ——
     * `ServerSmokeTarget` 的类注释里记着「先 DROP DATABASE 再关引擎」把服务端连接打爆的历史事故。
     */
    private fun provisionTables() {
        // [UNIQUE_TABLE] 只在目标库里
        PostgresSmoke.direct(TARGET_DB).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS \"$UNIQUE_TABLE\"")
                s.execute("CREATE TABLE \"$UNIQUE_TABLE\" (id INT PRIMARY KEY, note VARCHAR(64))")
                s.execute("INSERT INTO \"$UNIQUE_TABLE\" VALUES (1, '目标库独有的行')")
            }
        }

        // [SHARED_TABLE] 两边同名但内容不同 —— bootstrap 库那份是为了让
        // 「没切库时照样能查到行」，于是切库失败表现为**返回错的行**而不是报错，更容易被漏过去。
        for ((db, note) in listOf(BOOTSTRAP_DB to "bootstrap库的行", TARGET_DB to "目标库的行")) {
            PostgresSmoke.direct(db).use { c ->
                c.createStatement().use { s ->
                    s.execute("DROP TABLE IF EXISTS \"$SHARED_TABLE\"")
                    s.execute("CREATE TABLE \"$SHARED_TABLE\" (id INT PRIMARY KEY, note VARCHAR(64))")
                    s.execute("INSERT INTO \"$SHARED_TABLE\" VALUES (1, '$note')")
                }
            }
        }
    }

    /**
     * `TABLE.LIST` 必须来自**选中的那个库**。
     *
     * 这条对应 GUI 里「展开库 → 列出表」；修复前这里列的是 `postgres` 库的系统目录，
     * 修复 `listTables` 之后变成 `(空)` —— 两个缺陷叠在一起，只有连着看才说得清。
     */
    @Test
    fun `表列表来自选中的库而不是连接配置的库`() {
        val names = listTables(TARGET_DB).map { it.lowercase() }
        println("[PG-XDB] TABLE.LIST(database=$TARGET_DB) = $names")

        assertTrue(
            UNIQUE_TABLE.lowercase() in names,
            "⚠️ 表列表里没有只在 $TARGET_DB 里的 $UNIQUE_TABLE —— " +
                "说明查的还是 $BOOTSTRAP_DB（URL 里那个库）。实际 $names",
        )
    }

    /** 反向护栏：bootstrap 库独有的东西**不能**漏进来。 */
    @Test
    fun `bootstrap 库独有的表不混进目标库的列表`() {
        PostgresSmoke.direct(BOOTSTRAP_DB).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS \"pgx_only_in_bootstrap\"")
                s.execute("CREATE TABLE \"pgx_only_in_bootstrap\" (id INT PRIMARY KEY)")
            }
        }

        val names = listTables(TARGET_DB).map { it.lowercase() }

        assertTrue(
            "pgx_only_in_bootstrap" !in names,
            "⚠️ $BOOTSTRAP_DB 独有的表混进了 $TARGET_DB 的列表 —— 说明查的根本是 $BOOTSTRAP_DB。实际 $names",
        )
    }

    /**
     * **同名表读到的是选中库的内容**。
     *
     * 这一条最要紧：只测「另一个库里的表能查到」有个致命漏洞 ——
     * 若切库失败被吞掉，而**两个库恰好都有同名表**，查询会安安静静返回**错的行**，
     * 「能查到行」这种断言照样绿。比报错危险得多。
     */
    @Test
    fun `同名表读到的是选中库的内容`() {
        val rows = dataList(TARGET_DB, SHARED_TABLE)

        assertEquals(1, rows.total, "行数不对：$rows")
        assertEquals(
            listOf("目标库的行"), rows.notes,
            "⚠️ 静默读到了 $BOOTSTRAP_DB 的同名表 —— 这比报错更危险，用户根本看不出来",
        )
    }

    /** 回归护栏：连接配置里指定的库仍然正常 —— 切库逻辑不能反过来把它弄坏。 */
    @Test
    fun `连接配置指定的库仍然正常`() {
        val rows = dataList(BOOTSTRAP_DB, SHARED_TABLE)

        assertEquals(1, rows.total, "默认库的行数不对：$rows")
        assertEquals(listOf("bootstrap库的行"), rows.notes, "默认库读到了别的库：$rows")
    }

    private data class Rows(val total: Int, val notes: List<String>) {
        override fun toString() = "total=$total notes=$notes"
    }

    private fun listTables(database: String): List<String> {
        val resp: Response = runBlocking {
            engine.invoke(engineConfig(database)) {
                category = Category.TABLE
                action = Action.LIST
                tableRequest = tableRequest { list = tableListRequest {} }
            }
        }
        assertTrue(resp.success, "TABLE.LIST(database=$database) 失败：${resp.error}")
        return resp.table.list.itemsList.map { it.name }
    }

    private fun dataList(database: String, table: String): Rows {
        val resp: Response = runBlocking {
            engine.invoke(engineConfig(database)) {
                category = Category.DATA
                action = Action.LIST
                dataRequest = dataRequest {
                    list = dataListRequest {
                        tableName = table
                        page = 1
                        pageSize = 50
                        schema = ""
                        where = ""
                        orderBy = ""
                    }
                }
            }
        }
        assertTrue(resp.success, "DATA.LIST($database.$table) 失败：${resp.error}")
        val paged = resp.data.list
        val notes = paged.rowsList.map { row ->
            row.valuesMap["note"]?.let { unwrap(it) } ?: ""
        }
        return Rows(paged.total.toInt(), notes)
    }

    /**
     * `Value` → 可读文本。
     *
     * ⚠️ **不能**直接用 `Value.toString()`：那给的是 `string_value: "目标库的行"`
     * 这种调试串，拿去和真实内容比永远不相等 —— 而值本身其实是对的。
     * （与 `CrossDatabaseQueryTest` / `DialectSmokeTest.unwrap` 同一个坑。）
     */
    private fun unwrap(v: com.google.protobuf.Value): String = when (v.kindCase) {
        com.google.protobuf.Value.KindCase.STRING_VALUE -> v.stringValue
        com.google.protobuf.Value.KindCase.NUMBER_VALUE ->
            if (v.numberValue == kotlin.math.floor(v.numberValue)) v.numberValue.toLong().toString()
            else v.numberValue.toString()
        com.google.protobuf.Value.KindCase.BOOL_VALUE -> v.boolValue.toString()
        else -> ""
    }

    private fun engineConfig(database: String) = connectionConfig {
        driver = baseConn.dialect.engineDriverName
        // ⚠️ 固定用 [BOOTSTRAP_DB] 的 URL —— 本类验的正是「URL 指 A、选 B」。
        jdbcUrl = baseConn.jdbcUrl
        user = baseConn.username
        password = baseConn.password
        this.database = database
    }
}
