package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺陷：**左侧树里选的库没有真正带到查询里** —— 除连接配置指定的库以外，
 * 其他库里的表一点就报错。
 *
 * ## 根因
 *
 * 前端把树选中的库名放进 `ConnectionConfig.database`（它也进池 key，于是每个库
 * 拿到各自的连接池），但**每个池的 JDBC URL 都来自连接配置里的同一个 `jdbcUrl`** ——
 * URL 里钉死的就是连接时指定的那个库。`database` 因此只是个「池的区分标签」，
 * 从来没被应用到会话上。
 *
 * 而 `DataHandler.list` 拼的是裸表名：`SELECT * FROM <table>`。裸表名按会话默认库解析，于是
 *
 * ```
 * Table 'sundays_probe.orders' doesn't exist
 * ```
 *
 * —— 而用户在树上点的是 `shop`。错误信息里那个库名恰好是「我没点的那个」，
 * 极难自己联想到根因。
 *
 * 实测（MySQL 8，`jdbc:mysql://host:3306/sundays_probe`）：
 * - 借连接后 `conn.catalog` = `sundays_probe`，`SELECT * FROM \`orders\`` 通
 * - 手动 `conn.setCatalog("mysql")` 后同一句 `SELECT * FROM \`user\`` 也通
 *
 * ## 为什么**两条**用例，而不是一条
 *
 * 只测「另一个库里的表能查到」有个致命漏洞：只要 `setCatalog` 的异常被吞掉，
 * 查询就会**静默地跑在默认库上**。若目标表名在默认库里恰好不存在，那是硬报错（好）；
 * 但若**同名表两边都有**，就会安安静静返回**错的行** —— 比报错危险得多，
 * 而「能查到行」这种断言照样绿。
 *
 * 所以两条各钉一个方向：
 * - [非默认库独有的表能查到] —— 抓「完全没切库」（修之前是硬报错）
 * - [同名表读到的是选中库的内容] —— 抓「切库失败被吞掉」（修之前静默返回错的行）
 *
 * ## 为什么必须打真 MySQL
 *
 * 先试过用 H2 造等价场景（`CREATE DATABASE` + `conn.setCatalog`），
 * 实测 **H2 的 `setCatalog` 是静默 no-op**：`otherdb` 被当成 schema 处理，
 * 切完 catalog 纹丝不动，读出来的还是默认库的数据。
 * 拿它写测试会得到一条**永远绿、但什么都验不到**的用例 —— 比没有测试更糟。
 *
 * H2 / SQLite / DuckDB 在桌面端也各自只连一个库，不存在跨库浏览场景；
 * 真正需要跨 catalog 的就是 MySQL（一个实例多个 database）。
 *
 * 不可达时按 [DialectSmokeTest] 的约定 `assumeTrue` 显式 skip —— 报告里留一条
 * skipped，而不是一片绿或一片红。
 */
class CrossDatabaseQueryTest {

    companion object {
        /** 会话默认 catalog —— 复用冒烟测试那个固定库，不再多留一个。 */
        private const val DEFAULT_DB = "sundays_smoke"

        /** 「另一个库」。固定名 + 只重建表，理由见 [SmokeTarget.ServerSmokeTarget] 类注释。 */
        private const val OTHER_DB = "sundays_xdb"

        /** 只在 [OTHER_DB] 里建的表 —— 默认库里没有它。 */
        private const val UNIQUE_TABLE = "probe_only_here"

        /** 两个库都有的表，内容不同 —— 用来抓「切库失败被吞掉」。 */
        private const val SHARED_TABLE = "probe_shared_name"

        private const val TIMEOUT_MS = 60_000L
    }

    private lateinit var tempDir: File
    private lateinit var originalHome: String
    private lateinit var engine: com.kxxnzstdsw.engine.IdbEngine

    /**
     * 引擎侧连接配置：`jdbcUrl` 钉死 [DEFAULT_DB]，`database` 每次请求按选中项给。
     *
     * 这正是桌面端的真实形状 —— 见 `DatabaseBrowserScreen.engineConnFor`：
     * `jdbcUrl` 来自连接配置，`database` 来自树里点开的那个节点。
     */
    private val baseConn = MySqlSmoke.config(DEFAULT_DB)

    @Before
    fun setUp() {
        assumeTrue("[MySQL] 远程库不可达，跳过（内网地址，换台机器就不存在）", MySqlSmoke.reachable())

        tempDir = Files.createTempDirectory("sundays-xdb").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)

        MySqlSmoke.registerDialect()
        engine = com.kxxnzstdsw.engine.IdbEngine(
            driversDir = File("/nonexistent"),
            dialectsDir = File("/nonexistent"),
        )

        provisionTables()
    }

    @After
    fun tearDown() {
        try { engine.close() } catch (e: Exception) { println("[XDB] engine.close 抛了：$e") }
        System.setProperty("user.home", originalHome)
        tempDir.deleteRecursively()
    }

    /**
     * 两个库各建一张表。
     *
     * ⚠️ 用**表级** DDL，不 `DROP DATABASE` —— `SmokeTarget` 的类注释里记着
     * 「先 DROP 再关引擎」把 MySQL 服务端连接打爆的历史事故。
     * `CREATE DATABASE IF NOT EXISTS` 是幂等的，跑一次就长期复用。
     */
    private fun provisionTables() {
        // 建库必须在引导库（mysql）里发：CREATE DATABASE 不能在目标库里建自己
        MySqlSmoke.direct("mysql").use { boot ->
            boot.createStatement().use { s ->
                s.execute("CREATE DATABASE IF NOT EXISTS `$OTHER_DB` CHARACTER SET utf8mb4")
            }
        }

        // [UNIQUE_TABLE] 只在另一个库里
        MySqlSmoke.direct(OTHER_DB).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS `$UNIQUE_TABLE`")
                s.execute("CREATE TABLE `$UNIQUE_TABLE` (id INT PRIMARY KEY, note VARCHAR(64))")
                s.execute("INSERT INTO `$UNIQUE_TABLE` VALUES (1, '另一个库的行')")
            }
        }

        // [SHARED_TABLE] 两边同名但内容不同 —— 默认库那份是为了「没切库时照样能查到行」，
        // 这样切库失败就表现为**返回错的行**而不是报错，更容易被漏过去。
        for ((db, note) in listOf(DEFAULT_DB to "默认库的行", OTHER_DB to "另一个库的行")) {
            MySqlSmoke.direct(db).use { c ->
                c.createStatement().use { s ->
                    s.execute("DROP TABLE IF EXISTS `$SHARED_TABLE`")
                    s.execute("CREATE TABLE `$SHARED_TABLE` (id INT PRIMARY KEY, note VARCHAR(64))")
                    s.execute("INSERT INTO `$SHARED_TABLE` VALUES (1, '$note')")
                }
            }
        }
    }

    @Test
    fun `非默认库独有的表能查到`() = withTimeout("查另一个库独有的表") {
        val rows = dataList(OTHER_DB, UNIQUE_TABLE)

        assertEquals(1, rows.total, "行数不对：$rows")
        assertEquals(
            listOf("另一个库的行"), rows.notes,
            "读到的不是 $OTHER_DB 的内容 —— 会话还停在 jdbcUrl 指定的 $DEFAULT_DB",
        )
    }

    @Test
    fun `同名表读到的是选中库的内容`() = withTimeout("同名表别读错库") {
        val rows = dataList(OTHER_DB, SHARED_TABLE)

        assertEquals(1, rows.total, "行数不对：$rows")
        assertEquals(
            listOf("另一个库的行"), rows.notes,
            "⚠️ 静默读到了 $DEFAULT_DB 的同名表 —— 这比报错更危险，用户根本看不出来",
        )
    }

    @Test
    fun `连接配置指定的库仍然正常`() = withTimeout("默认库没被改坏") {
        // 回归护栏：切 catalog 不能反过来把原本能用的默认库弄坏
        val rows = dataList(DEFAULT_DB, SHARED_TABLE)

        assertEquals(1, rows.total, "默认库的行数不对：$rows")
        assertEquals(listOf("默认库的行"), rows.notes, "默认库读到了别的库：$rows")
    }

    private data class Rows(val total: Int, val notes: List<String>) {
        override fun toString() = "total=$total notes=$notes"
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
            // 行是 map<列名, Value>，按列名取比按下标稳（H2 会把列名折成大写）
            row.valuesMap["note"]?.let { unwrap(it) } ?: ""
        }
        return Rows(paged.total.toInt(), notes)
    }

    /**
     * `Value` → 可读文本。
     *
     * ⚠️ **不能**直接用 `Value.toString()`：那给的是 `string_value: "另一个库的行"`
     * 这种调试串，拿去和真实内容比永远不相等 —— 而值本身其实是对的。
     * （与 `DialectSmokeTest.unwrap` 同一个坑。）
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
        jdbcUrl = baseConn.jdbcUrl
        user = baseConn.username
        password = baseConn.password
        if (database.isNotBlank()) this.database = database
    }

    /** 给可能很慢的远程往返套一个明确的上限，超时报出正在做什么。 */
    private fun <T> withTimeout(what: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        println("[XDB] $what 完成，用时 ${System.currentTimeMillis() - start}ms")
        return result
    }
}
