package com.kxxnzstdsw.dialect

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Excel 文件 → 临时 DuckDB 文件的预转换缓存。
 *
 * DuckDB 内核不原生支持 .xlsx 文件直查，community extension `excel` 需要网络下载。
 * 这里采用 POI 读取 + 临时 DuckDB 实例的方式把每个 sheet 写入为 VARCHAR 表，
 * 之后通过 [getOrCreate] 返回的临时 .duckdb 文件路径即可走标准的 DuckDB JDBC 流程。
 *
 * ## 转换后用户看到什么
 *
 * | | 库名 | 表名 | 列名 | 数据行数 |
 * |---|---|---|---|---|
 * | `.xlsx`（首行是表头） | 源文件名去扩展名 | sheet 名 | **表头文字** | 数据行数（表头不算） |
 * | `.xlsx`（首行不是表头） | 同上 | sheet 名 | `col1, col2, …` | 全部行 |
 *
 * 「库名沿用源文件名」与「表头不算数据行」都是**补上的**：原先临时库写死成
 * `converted.duckdb`（用户看到的是一棵叫 `converted` 的库），且表头行被当数据写进去、
 * 列名一律 `col1/col2`。两处都在真库冒烟里被照出来 —— 方言自带的测试只验了
 * `buildJdbcUrl` 的字符串拼接，**从没验过转换后到底能不能查出数据**。
 *
 * 线程安全：[cache] 用 ConcurrentHashMap 保证首次转换只发生一次。
 * 生命周期：临时目录在 JVM 关闭时由 [deleteOnExit] 兜底清理；可显式调 [cleanup] 提前释放。
 */
object ExcelToDuckDbCache {
    private val logger = LoggerFactory.getLogger(ExcelToDuckDbCache::class.java)
    private val cache = ConcurrentHashMap<String, File>()
    private val tempDirs = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()

    /**
     * 取得 [excelPath] 对应的 DuckDB 数据库文件路径；若未缓存则用 POI 读取并转换为临时 DuckDB。
     */
    fun getOrCreate(excelPath: String): String {
        val file = cache.getOrPut(excelPath) {
            convert(excelPath)
        }
        return file.absolutePath
    }

    /**
     * 显式清理所有缓存的临时目录。
     */
    fun cleanup() {
        tempDirs.forEach { dir ->
            try {
                dir.deleteRecursively()
            } catch (e: Exception) {
                logger.warn("清理临时目录失败: ${dir.absolutePath}", e)
            }
        }
        tempDirs.clear()
        cache.clear()
    }

    /**
     * 检查 [path] 是否为 Excel 文件路径（不读文件，仅根据后缀判断）。
     */
    fun isExcelFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".xlsx") || lower.endsWith(".xls")
    }

    private fun convert(excelPath: String): File {
        val src = File(excelPath)
        require(src.exists()) { "Excel 文件不存在: $excelPath" }
        val tempDir = Files.createTempDirectory("duckdb-excel-").toFile().apply { deleteOnExit() }
        tempDirs.add(tempDir)

        // 临时库**沿用源文件名**（去扩展名、规整成合法标识符），而不是写死的 `converted`。
        //
        // 原因：DuckDB 的「数据库名」就是文件名去扩展名，而应用里这个名字**直接显示在
        // 库树上**。写死 `converted.duckdb` 的话，用户打开 `销售明细.xlsx` 看到的是一棵
        // 叫 `converted` 的库 —— 既认不出自己的文件，也分不清同时开了几个 Excel。
        // 临时目录本身已经保证了不撞名，名字规整只是为了让它是个合法标识符。
        val dbFile = File(tempDir, "${sanitizeIdentifier(src.nameWithoutExtension)}.duckdb")

        logger.info("开始将 Excel 转换为临时 DuckDB: ${src.absolutePath} → ${dbFile.absolutePath}")

        // 1. 用 POI 读取所有 sheet
        val sheetData: List<Pair<String, List<List<String>>>> = WorkbookFactory.create(src).use { wb ->
            (0 until wb.numberOfSheets).map { i ->
                val sheet = wb.getSheetAt(i)
                val sheetName = sheet.sheetName ?: "Sheet$i"
                val rows = mutableListOf<List<String>>()
                for (rowIdx in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIdx) ?: continue
                    val rowData = (0 until row.lastCellNum).map { cellIdx ->
                        row.getCell(cellIdx)?.toString()?.takeIf { it.isNotEmpty() } ?: ""
                    }
                    rows.add(rowData)
                }
                sheetName to rows
            }
        }

        // 2. 把每个 sheet 写入临时 DuckDB（列一律 VARCHAR —— POI 读出来的是字符串，
        //    猜类型反而会错：身份证号 / 邮编 / 订单号前导零在「猜成数字」时会丢）
        DriverManager.getConnection("jdbc:duckdb:${dbFile.absolutePath}").use { conn ->
            for ((sheetName, rows) in sheetData) {
                if (rows.isEmpty()) continue
                val tableName = sanitizeIdentifier(sheetName)
                val colCount = rows.maxOf { it.size }
                if (colCount == 0) continue

                // 2.1 判定首行是不是表头 —— 见 [looksLikeHeader]
                val hasHeader = looksLikeHeader(rows)
                val header = if (hasHeader) rows.first() else null
                val dataRows = if (hasHeader) rows.drop(1) else rows
                if (dataRows.isEmpty()) continue

                // 2.2 建表 —— 列名优先用表头文字，缺/重名退回 col1, col2, …
                val columns = columnNames(header, colCount)
                val cols = columns.joinToString(", ") { "$it VARCHAR" }
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE $tableName ($cols)")
                }

                // 2.3 插数据
                for (row in dataRows) {
                    insertRow(conn, tableName, row, colCount)
                }
                logger.info(
                    "  ✓ Sheet '$sheetName' → 表 '$tableName'" +
                        "（${dataRows.size} 行 × $colCount 列，${if (hasHeader) "首行为表头" else "无表头"}，列名=${columns}）",
                )
            }
        }

        return dbFile
    }

    /**
     * 首行是不是**表头**。
     *
     * ## 为什么必须判，不能无脑当表头
     *
     * Excel 当数据源时两种都常见：带表头的报表、不带表头的原始导出。
     * 无脑当表头 → 没有表头的文件会**丢掉第一行数据**；无脑当数据 → 有表头的文件
     * 表头会混进结果集（这正是本文件原先的行为：`col1/col2` + 表头行混作数据）。
     *
     * 判据是「**首行没有任何一格像数字，而下面的行有**」——
     * 反过来（首行有数字）就当数据，于是「2024 年销售额」这种表头不会被误吞，
     * 而纯数字开头的数据行也不会被误当表头。
     *
     * 这不是能证明的规则，所以写清楚边界：它认的是**形状**（有没有数字），
     * 认不出「日期」「编号」这类语义上的表头。
     */
    private fun looksLikeHeader(rows: List<List<String>>): Boolean {
        if (rows.size < 2) return false
        val first = rows.first().filter { it.isNotBlank() }
        if (first.isEmpty()) return false
        if (first.any { it.isNumericLike() }) return false
        return rows.drop(1).any { row -> row.any { it.isNumericLike() } }
    }

    private fun String.isNumericLike(): Boolean =
        isNotBlank() && trim().toDoubleOrNull() != null

    /**
     * 列名：优先取表头文字，缺 / 空 / 重复时退回 `col1, col2, …`。
     *
     * **不加引号**（见 [sanitizeIdentifier] 末尾的说明）：加了引号 DuckDB 就大小写敏感，
     * 而应用侧拼 SQL 时从不加引号 —— 列名变成 `userName` 而查询写的是 `username`，
     * 于是「列名保住了大小写」反而让所有查询找不到列。
     */
    private fun columnNames(header: List<String>?, colCount: Int): List<String> {
        val used = mutableSetOf<String>()
        return (0 until colCount).map { i ->
            val raw = header?.getOrNull(i)?.trim()
            var name = if (raw.isNullOrBlank()) "col${i + 1}" else sanitizeIdentifier(raw)
            val base = name
            var n = 2
            while (name.lowercase() in used) {
                name = "${base}_$n"
                n++
            }
            used += name.lowercase()
            name
        }
    }

    private fun insertRow(conn: Connection, tableName: String, row: List<String>, colCount: Int) {
        val placeholders = (1..colCount).joinToString(", ") { "?" }
        conn.prepareStatement("INSERT INTO $tableName VALUES ($placeholders)").use { ps ->
            for (i in 0 until colCount) {
                val value = row.getOrNull(i) ?: ""
                ps.setString(i + 1, value)
            }
            ps.executeUpdate()
        }
    }

    /**
     * 把任意名字规整成合法的 DuckDB 标识符。
     *
     * - 保留**所有 Unicode 字母与数字**（不只是 ASCII）—— 用户的文件名十有八九带中文，
     *   按 ASCII 规整会把 `销售明细` 变成 `____`，比叫 `converted` 还让人摸不着头脑
     * - 其余字符（含空格、标点）一律换成 `_`
     * - 必须以字母或下划线开头
     * - 最大长度 63
     *
     * 结果**不加引号**使用，与本仓其它地方构造标识符的方式保持一致 ——
     * 加引号会让 DuckDB 变成**大小写敏感**，而应用侧拼 SQL 时从不加引号
     * （`WHERE col LIKE …` 里的 `col` 是折成小写的），一改就会对不上。
     */
    private fun sanitizeIdentifier(name: String): String {
        val cleaned = buildString {
            for (ch in name) {
                if (ch.isLetterOrDigit() || ch == '_') append(ch) else append('_')
            }
        }
        val prefixed = when {
            cleaned.isEmpty() -> "_"
            cleaned[0].isLetter() || cleaned[0] == '_' -> cleaned
            else -> "_$cleaned"
        }
        return prefixed.take(63)
    }
}