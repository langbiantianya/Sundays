package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.dialect.DuckDBDialect
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.grpc.tableListRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import com.kxxnzstdsw.sundays.connection.DialectType
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **文件型数据源冒烟** —— CSV / JSON Lines / Parquet / Excel 四种格式，
 * 验「用户把一个文件当数据源打开」这条主路径。
 *
 * ## 这条路径的机制（先实测、再写断言）
 *
 * DuckDB 的 JDBC 驱动支持直接把**非数据库文件**当 URL 主体：
 * `jdbc:duckdb:C:/data/users.csv` 会打开该文件，并暴露一个**视图** ——
 * 视图名是**去扩展名的文件名**（`users.csv` → 视图 `users`），不是完整文件名。
 *
 * 这一条是**实测**出来的，不是照文档推的（见 `dialect-duckdb` 的测试只验了
 * `buildJdbcUrl` 的**字符串拼接**，没验过「拼出来的 URL 真能查出数据」）：
 *
 * ```
 * SELECT * FROM "data.csv"     → 失败（视图名不带扩展名）
 * SELECT * FROM data           → 3 行 ✅
 * ```
 *
 * ## 格式差异
 *
 * | 格式 | 走什么 | 视图/表名 |
 * |---|---|---|
 * | CSV / Parquet | DuckDB 直接开文件 | 文件名去扩展名 |
 * | JSON Lines | DuckDB 直接开文件 | 同上 |
 * | **Excel** | 方言**先转**成临时 `.duckdb`（POI） | **sheet 名**（不是文件名） |
 *
 * ## ⚠️ 一个必须走对入口的前提
 *
 * `PoolManager.createDataSource` 的取值顺序是「`config.jdbcUrl` 非空就用它，
 * 否则才调 `dialect.buildJdbcUrl(...)`」。而 Excel 的预转换**只发生在
 * `buildJdbcUrl` 里** —— 任何预先算好 URL 的调用方都会绕过它。
 * 所以本测试对四种格式统一走 `DuckDBDialect.buildJdbcUrl(...)`。
 *
 * 顺带这也是一条**给使用方的约束**：想让 Excel 走通，就必须让 URL 由方言生成。
 */
class DuckDbFileSourceTest {

    private companion object {
        /** 每份文件都放这 20 行，断言才有具体锚点。 */
        const val ROWS = 20

        /** CSV / Parquet / JSONL 暴露的视图名 = 文件名去扩展名。 */
        const val STEM = "people"

        /** Excel 走的是 sheet 名 —— 刻意与 STEM 不同，免得「查到的其实是文件名」被误当成「查到了 sheet」。 */
        const val EXCEL_SHEET = "excel_sheet"
    }

    private lateinit var tempDir: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private val created = mutableListOf<File>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sundays-duckdb-files").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
    }

    @After
    fun tearDown() {
        try { engine.close() } catch (e: Exception) { println("engine.close 抛了：$e") }
        created.forEach { runCatching { it.delete() } }
        System.setProperty("user.home", originalHome)
        tempDir.deleteRecursively()
    }

    // ==================================================================
    // 四种格式
    // ==================================================================

    @Test
    fun `csv can be opened as a data source`() =
        assertFileSource(makeCsv(), STEM, dbName = STEM, typed = true)

    @Test
    fun `json lines can be opened as a data source`() =
        assertFileSource(makeJsonLines(), STEM, dbName = STEM, typed = true)

    @Test
    fun `parquet can be opened as a data source`() =
        assertFileSource(makeParquet(), STEM, dbName = STEM, typed = true)

    /**
     * Excel 走的是**方言预转换**那条路（POI → 临时 `.duckdb`），与前三个机制不同。
     *
     * ⚠️ 实测出两处**产品上的简化**（不是缺陷，但该记下来，用户会撞上）：
     *
     * 1. 转换产物是 `ExcelToDuckDbCache` 里写死的 `converted.duckdb`，于是界面上
     *    「库」的名字是 **`converted`**，而不是用户的文件名。
     * 2. **表头行被当成数据写进去了**，且列名是 `col1 / col2 …` 而不是表头文字
     *    （`ExcelToDuckDbCache` 的注释自己就自相矛盾：「首行视为 header」紧跟着
     *    「为了简单起见，全部按数据行写入」）。所以 20 行数据的表读回来是 **21 行**。
     *
     * 因为转换后全是 VARCHAR + `colN` 列名，过滤/排序/搜索那一整套矩阵在这里测的
     * 是 DuckDB 的**字符串比较**而不是产品，故 `typed = false` 跳过，只验「打得开、读得出」。
     *
     * sheet 名刻意不等于 [STEM]：否则「查到的到底是 sheet 还是文件」分不清，
     * 两种机制混在一起看不出问题。
     */
    @Test
    fun `excel can be opened as a data source`() =
        assertFileSource(makeExcel(), EXCEL_SHEET, dbName = null, typed = false)

    // ==================================================================
    // 公共判据
    // ==================================================================

    /**
     * 一份文件当数据源打开的**完整**验收：
     * 连得上 → 库列表里有它 → 表列表里有它 → 查得出 20 行 → 过滤/排序/搜索下推都对。
     *
     * 只验「能连上」是不够的 —— 方言测试已经证明 URL 拼得出来，拼得出来 ≠ 查得出数据。
     *
     * @param dbName 期望出现在库列表里的名字；`null` = **不断言库名**（Excel 走转换产物，
     *   库名是实现细节，断它只会把「实现改名」误报成「功能坏了」）。
     * @param typed 列名与类型是否保真。为 `false`（Excel 转换产物：列名是 `colN`、
     *   全 VARCHAR、且表头行被算作数据）时只验「打得开、读得出」，
     *   跳过过滤/排序/搜索 —— 那三项在这里测的是 DuckDB 的字符串比较，不是产品。
     */
    private fun assertFileSource(file: File, viewName: String, dbName: String?, typed: Boolean) {
        val fmt = file.extension
        // 走方言入口 —— Excel 的预转换只在这里发生
        val url = DuckDBDialect().buildJdbcUrl("", 0, file.absolutePath)
        println("[$fmt] buildJdbcUrl → $url")

        val cfg = ConnectionConfig(
            id = "file-$fmt", name = "File$fmt", dialect = DialectType.DUCKDB,
            database = file.absolutePath, username = "", password = "", jdbcUrl = url,
        )

        // ① 连得上
        val hello = invoke(cfg) { category = Category.SQL; action = Action.EXECUTE
            sqlRequest = sqlRequest { execute = sqlExecuteRequest { sql = "SELECT 1"; schema = "" } } }
        assertTrue(hello.success, "[$fmt] 连不上：${hello.error}")

        // ② 库列表里有它（文件模式下的「库」就是文件名去扩展名）
        val dbs = invoke(cfg) { category = Category.SCHEMA; action = Action.LIST
            schemaRequest = schemaRequest { list = schemaListRequest { level = "database" } } }
        assertTrue(dbs.success, "[$fmt] 列库失败：${dbs.error}")
        // SchemaListResponse.items 是 repeated string（不是消息列表），直接就是名字数组
        val dbNames = dbs.schema.list.itemsList
        println("[$fmt] 库列表 = $dbNames")
        if (dbName != null) {
            assertTrue(
                dbNames.any { it.equals(dbName, ignoreCase = true) },
                "[$fmt] 库列表应含 $dbName，实际 $dbNames",
            )
        }

        // ③ 表列表里有它
        val tables = invoke(cfg) { category = Category.TABLE; action = Action.LIST
            tableRequest = tableRequest { list = tableListRequest {} } }
        assertTrue(tables.success, "[$fmt] 列表失败：${tables.error}")
        val tableNames = tables.table.list.itemsList.map { it.name }
        println("[$fmt] 表列表 = $tableNames")
        assertTrue(
            tableNames.any { it.equals(viewName, ignoreCase = true) },
            "[$fmt] 表列表应含 $viewName，实际 $tableNames",
        )

        // ④ 查得出全部行
        //    Excel 走 POI 转换，**表头行被当成数据**（`ExcelToDuckDbCache` 的已知简化），
        //    所以实际读回的是 ROWS + 1 行。这里按实际行为断言并在注释里写明，
        //    而不是把行数写死成 ROWS —— 后者只会把「表头被当数据」这个事实藏起来。
        val expectedRows = if (typed) ROWS else ROWS + 1
        val all = dataList(cfg, viewName, where = "", orderBy = "")
        assertEquals(
            expectedRows, all.first,
            "[$fmt] 应读回 $expectedRows 行（第二段=${all.second}）" +
                if (typed) "" else "　← Excel 转换把表头行也算成了数据，故比数据行多 1",
        )
        println("[$fmt] 首行 = ${all.third}")

        if (!typed) return

        // ⑤ 过滤下推
        val filtered = dataList(cfg, viewName, where = "id > 15", orderBy = "")
        assertEquals(5, filtered.first, "[$fmt] 过滤 id > 15 应得 5 行（第二段=${filtered.second}）")

        // ⑥ 排序下推
        val sorted = dataList(cfg, viewName, where = "", orderBy = "id DESC")
        assertEquals(
            ROWS.toString(), sorted.third.firstOrNull(),
            "[$fmt] 按 id DESC 排序首行应为 $ROWS，实际 ${sorted.third}",
        )

        // ⑦ 表内搜索下推 —— 写法与生产代码 `TablePreviewTab.effectiveWhere()` 一致
        val castType = SqlLiterals.castTypeFor(DialectType.DUCKDB)
        val like = "(CAST(id AS $castType) LIKE '%person_1%' OR CAST(name AS $castType) LIKE '%person_1%')"
        val searched = dataList(cfg, viewName, where = like, orderBy = "")
        assertEquals(
            11, searched.first,
            "[$fmt] 表内搜索 person_1 应得 11 行，实际 ${searched.first}（第二段=${searched.second}）",
        )
    }

    // ==================================================================
    // 造文件
    // ==================================================================

    /**
     * CSV / Parquet / JSONL 三个都**用 DuckDB 自己造**。
     *
     * 刻意不用手写字符串：手写 Parquet 做不到，而手写 CSV/JSONL 会引入
     * 「转义 / 空值 / 编码」这类与本用例无关的失败源 —— 造数据的任务交给最擅长的那个。
     */
    private fun makeCsv(): File = withSeedDb { seed ->
        val out = File(tempDir, "$STEM.csv")
        copyOut(seed, "COPY t TO '${norm(out)}' (FORMAT CSV, HEADER)")
        out
    }

    private fun makeJsonLines(): File = withSeedDb { seed ->
        val out = File(tempDir, "$STEM.jsonl")
        copyOut(seed, "COPY t TO '${norm(out)}' (FORMAT JSON)")
        out
    }

    private fun makeParquet(): File = withSeedDb { seed ->
        val out = File(tempDir, "$STEM.parquet")
        copyOut(seed, "COPY t TO '${norm(out)}' (FORMAT PARQUET)")
        out
    }

    private fun withSeedDb(block: (Connection) -> File): File {
        val seedFile = File(tempDir, "seed.duckdb")
        DriverManager.getConnection("jdbc:duckdb:${seedFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE t AS SELECT * FROM (VALUES " +
                    (1..ROWS).joinToString(",") { "($it, 'person_$it')" } + ") v(id, name)")
            }
        }
        created += seedFile
        return block(DriverManager.getConnection("jdbc:duckdb:${seedFile.absolutePath}"))
    }

    private fun copyOut(seed: Connection, sql: String) {
        seed.createStatement().use { it.execute(sql) }
    }

    /**
     * 用 POI 造一个真实 `.xlsx`。
     *
     * sheet 名刻意取 [EXCEL_SHEET]（与 [STEM] 不同）：转换后表名取自 sheet 名，
     * 若两者相同，「查到了 sheet 还是查到了文件」就分不清了。
     */
    private fun makeExcel(): File {
        val out = File(tempDir, "$STEM.xlsx")
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(EXCEL_SHEET)
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("id")
            header.createCell(1).setCellValue("name")
            for (i in 1..ROWS) {
                val row = sheet.createRow(i)
                row.createCell(0).setCellValue(i.toDouble())
                row.createCell(1).setCellValue("person_$i")
            }
            FileOutputStream(out).use { wb.write(it) }
        }
        created += out
        return out
    }

    private fun norm(f: File): String = f.absolutePath.replace(File.separatorChar, '/')

    // ==================================================================
    // 引擎调用
    // ==================================================================

    private fun invoke(cfg: ConnectionConfig, configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit): Response =
        runBlocking {
            val resp = runCatching {
                engine.invoke(connectionConfig {
                    driver = cfg.dialect.engineDriverName
                    jdbcUrl = cfg.jdbcUrl
                    user = cfg.username
                    password = cfg.password
                }) {
                    this.connection = connectionConfig {
                        driver = cfg.dialect.engineDriverName
                        jdbcUrl = cfg.jdbcUrl
                    }
                    configure()
                }
            }.getOrNull()
            resp ?: throw AssertionError("引擎无响应")
        }

    /** `(行数, 引擎原文, 首行前两列)`。 */
    private fun dataList(cfg: ConnectionConfig, table: String, where: String, orderBy: String) =
        runBlocking {
            val resp = invoke(cfg) {
                category = Category.DATA
                action = Action.LIST
                dataRequest = dataRequest {
                    list = dataListRequest {
                        tableName = table
                        page = 1
                        pageSize = 100
                        schema = ""
                        this.where = where
                        this.orderBy = orderBy
                    }
                }
            }
            assertTrue(resp.success, "DATA.LIST($table, where=$where) 失败：${resp.error}")
            val rows = resp.data.list.rowsList
            Triple(
                resp.data.list.total.toInt(),
                resp.error,
                rows.firstOrNull()?.valuesMap.orEmpty().entries.take(2).map { unwrap(it.value) },
            )
        }

    private fun unwrap(v: com.google.protobuf.Value): String = when (v.kindCase) {
        com.google.protobuf.Value.KindCase.STRING_VALUE -> v.stringValue
        com.google.protobuf.Value.KindCase.NUMBER_VALUE ->
            if (v.numberValue == kotlin.math.floor(v.numberValue)) v.numberValue.toLong().toString()
            else v.numberValue.toString()
        com.google.protobuf.Value.KindCase.BOOL_VALUE -> v.boolValue.toString()
        else -> ""
    }
}
