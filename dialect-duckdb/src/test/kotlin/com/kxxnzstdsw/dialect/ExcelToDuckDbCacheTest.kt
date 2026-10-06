package com.kxxnzstdsw.dialect

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Excel → DuckDB 预转换的**行为**测试。
 *
 * ## 为什么必须单独测，而不能只靠上层冒烟
 *
 * 方言自带的 `DuckDBDialectTest` 原来只验 `buildJdbcUrl` 的**字符串拼接**
 * （「`.xlsx` → 转换后的 URL 以 `.duckdb` 结尾」），**从没打开过转换产物**。
 * 于是两处真问题一路漏到真库冒烟才被照出来：
 *
 * 1. 临时库写死成 `converted.duckdb` → 用户看到的是一棵叫 `converted` 的库
 * 2. 表头行被当数据写入、列名一律 `col1/col2` → 20 行的表读回 21 行
 *
 * 「拼出来的 URL 对不对」与「拼出来的东西用不用得了」是两件事，后者才要断。
 */
class ExcelToDuckDbCacheTest {

    @Test
    fun `converted database keeps the source file name`(@TempDir tempDir: Path) {
        val excel = writeExcel(tempDir, "销售明细.xlsx", header = true, rows = 20)
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)

        // 中文文件名必须**原样保留**：按 ASCII 规整会把「销售明细」变成「____」，
        // 那比叫 `converted` 更让人摸不着头脑 —— 库名是直接显示在应用库树上的。
        assertEquals(
            "销售明细.duckdb", File(dbPath).name,
            "临时库名应沿用源文件名（去掉扩展名），实际 ${File(dbPath).name}",
        )
        val dbNames = queryStrings(dbPath, "SELECT database_name FROM duckdb_databases()")
        println("库列表 = $dbNames")
        assertTrue(
            dbNames.any { it == "销售明细" },
            "DuckDB 侧应能按源文件名列出该库，实际 $dbNames",
        )
    }

    /**
     * 库名里的**特殊字符**也要能过：文件名带空格/括号很常见（`Q1 报表(最终).xlsx`）。
     * 规整后仍应保留可辨认的部分，而不是整串退化成下划线。
     */
    @Test
    fun `special characters in the file name are kept recognisable`(@TempDir tempDir: Path) {
        val excel = writeExcel(tempDir, "Q1 报表(最终).xlsx", header = true, rows = 3)
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)
        println("规整后库名 = ${File(dbPath).name}")
        assertEquals("Q1_报表_最终_.duckdb", File(dbPath).name, "只应把非法字符换成 _，其余原样保留")
    }

    @Test
    fun `header row becomes column names and is not data`(@TempDir tempDir: Path) {
        val excel = writeExcel(tempDir, "with_header.xlsx", header = true, rows = 20)
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)

        val columns = queryStrings(dbPath, "SELECT column_name FROM information_schema.columns WHERE table_name = 'Sheet1' ORDER BY ordinal_position")
        println("列名 = $columns")
        assertEquals(listOf("id", "name"), columns, "列名应取自表头文字，而不是 col1 / col2")

        val count = queryInt(dbPath, "SELECT COUNT(*) FROM Sheet1")
        assertEquals(20, count, "20 行数据的表读回来必须是 20 行 —— 表头行不算数据")
    }

    @Test
    fun `a file without a header keeps every row`(@TempDir tempDir: Path) {
        val excel = writeExcel(tempDir, "no_header.xlsx", header = false, rows = 5)
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)

        val count = queryInt(dbPath, "SELECT COUNT(*) FROM Sheet1")
        assertEquals(5, count, "首行也是数据时不能被当表头吃掉")

        val columns = queryStrings(dbPath, "SELECT column_name FROM information_schema.columns WHERE table_name = 'Sheet1' ORDER BY ordinal_position")
        assertEquals(listOf("col1", "col2"), columns, "没有表头时退回 col1 / col2")
    }

    @Test
    fun `a numeric first row is data rather than a header`(@TempDir tempDir: Path) {
        // 「2024 年销售额」这种表头认不出来（判据只看数字）；
        // 但纯数字开头的行**一定**是数据，不能被当表头吃掉 —— 这是判据的保守侧。
        val excel = writeNumericFirstRow(tempDir)
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)
        assertEquals(3, queryInt(dbPath, "SELECT COUNT(*) FROM Sheet1"), "首行是数字时应全部当数据")
    }

    @Test
    fun `duplicate and blank header cells fall back without colliding`(@TempDir tempDir: Path) {
        val excel = File(tempDir.toFile(), "weird_header.xlsx")
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("id")
            header.createCell(1).setCellValue("id")     // 重名
            header.createCell(2).setCellValue(" ")       // 空白
            for (i in 1..3) {
                val row = sheet.createRow(i)
                row.createCell(0).setCellValue(i.toDouble())
                row.createCell(1).setCellValue("x")
                row.createCell(2).setCellValue("y")
            }
            FileOutputStream(excel).use { wb.write(it) }
        }
        val dbPath = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)
        val columns = queryStrings(dbPath, "SELECT column_name FROM information_schema.columns WHERE table_name = 'Sheet1' ORDER BY ordinal_position")
        println("退化列名 = $columns")
        assertEquals(3, columns.size, "列数不应因重名/空白而变")
        assertEquals(3, columns.toSet().size, "列名必须互不相同，实际 $columns")
    }

    @Test
    fun `conversion is cached per path`(@TempDir tempDir: Path) {
        val excel = writeExcel(tempDir, "cached.xlsx", header = true, rows = 3)
        val a = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)
        val b = ExcelToDuckDbCache.getOrCreate(excel.absolutePath)
        assertEquals(a, b, "同一路径重复取应命中缓存，不重复转换")
    }

    // ------------------------------------------------------------------ 造文件

    private fun writeExcel(
        dir: Path,
        name: String,
        header: Boolean,
        rows: Int,
    ): File {
        val f = File(dir.toFile(), name)
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            var r = 0
            if (header) {
                val h = sheet.createRow(r++)
                h.createCell(0).setCellValue("id")
                h.createCell(1).setCellValue("name")
            }
            for (i in 1..rows) {
                val row = sheet.createRow(r++)
                row.createCell(0).setCellValue(i.toDouble())
                row.createCell(1).setCellValue("person_$i")
            }
            FileOutputStream(f).use { wb.write(it) }
        }
        return f
    }

    private fun writeNumericFirstRow(dir: Path): File {
        val f = File(dir.toFile(), "numeric_first.xlsx")
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            for (i in 1..3) {
                val row = sheet.createRow(i - 1)
                row.createCell(0).setCellValue(i.toDouble())
                row.createCell(1).setCellValue("x")
            }
            FileOutputStream(f).use { wb.write(it) }
        }
        return f
    }

    // ------------------------------------------------------------------ 查询

    private fun queryStrings(dbPath: String, sql: String): List<String> =
        DriverManager.getConnection("jdbc:duckdb:$dbPath", "", "").use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    generateSequence { if (r.next()) r.getString(1) else null }.toList()
                }
            }
        }

    private fun queryInt(dbPath: String, sql: String): Int =
        DriverManager.getConnection("jdbc:duckdb:$dbPath", "", "").use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r -> r.next(); r.getInt(1) }
            }
        }
}
