package com.kxxnzstdsw.importer

import com.kxxnzstdsw.grpc.ImportRunRequest
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

/**
 * 导入读取器工厂 —— proto 请求到 [ImportSource] 的唯一入口。
 *
 * 把「路径 -> 字符集 -> 分隔符 -> 读取器」这一串默认值的决策集中在这里，
 * 业务层就只需要 `ImportSourceFactory.open(req).use { ... }` 一行。
 */
object ImportSourceFactory {

    /**
     * 按请求打开文件并返回匹配的读取器。调用方负责 [ImportSource.close]（`use {}` 即可）。
     *
     * @param targetColumns 目标表的列名（**按 ordinal 排序**）。仅在 CSV + `has_header = false`
     *   时使用 —— 无表头的文件本身给不出列名，必须由引擎从表元数据提供。
     *
     * 默认值（proto3 的标量默认值都是「零值」，所以这些兜底必须显式写出来）：
     * - `format`：交由 [ImportFormat.parse] 判定，空串视为非法而不是默默当成 CSV。
     * - `delimiter`：取第一个字符；空串默认 `,`。
     * - `encoding`：空串默认 UTF-8（与 [com.kxxnzstdsw.export.CsvWriter] 导出的编码一致）。
     * - `hasHeader`：**未设置时默认 true**。`has_header` 在 proto 里是 `optional`，
     *   正是为了区分「没传」与「显式 false」—— 这两者意图相反，猜错会把首行数据当表头吃掉。
     *
     * 文件不存在 / 不可读 / 编码非法 / 无表头但未提供目标列时抛 [IllegalArgumentException]，
     * 消息里带文件路径，便于前端直接把路径回显给用户。
     */
    fun open(req: ImportRunRequest, targetColumns: List<String> = emptyList()): ImportSource {
        val path = req.filePath
        require(path.isNotBlank()) { "导入文件路径不能为空: file_path" }

        val format = ImportFormat.parse(req.format)
        val file = File(path)
        require(file.isFile) { "导入文件不存在或不是普通文件: $path" }
        require(file.canRead()) { "导入文件不可读: $path" }

        val charset = resolveCharset(req.encoding)
        val delimiter = req.delimiter.firstOrNull() ?: DEFAULT_DELIMITER
        // presence 语义：未设置 → 有表头
        val hasHeader = if (req.hasHasHeader()) req.hasHeader else true

        val reader = openReader(file, charset, path)
        return try {
            when (format) {
                ImportFormat.CSV -> CsvReader(reader, delimiter, hasHeader, targetColumns)
                ImportFormat.JSON_LINES -> JsonLinesReader(reader)
            }
        } catch (e: Throwable) {
            // 构造期失败（读取器是急切解析表头的）不能泄漏已打开的文件句柄
            runCatching { reader.close() }
            throw e
        }
    }

    private fun resolveCharset(encoding: String): Charset {
        if (encoding.isBlank()) return StandardCharsets.UTF_8
        return try {
            Charset.forName(encoding.trim())
        } catch (e: IllegalCharsetNameException) {
            throw IllegalArgumentException("导入文件编码名非法: '$encoding' — ${e.message}", e)
        } catch (e: UnsupportedCharsetException) {
            throw IllegalArgumentException("JVM 不支持的导入文件编码: '$encoding'", e)
        }
    }

    /**
     * 打开 Reader 并剥掉 UTF-8 BOM。
     *
     * **为什么必须剥**：本项目的 [com.kxxnzstdsw.export.CsvWriter] 导出时会写 UTF-8 BOM
     * （让 Excel 正确识别中文）。若导入侧不剥，首个列名会变成 `"\uFEFFid"`，
     * 拼出的 `INSERT INTO t ("\uFEFFid", name)` 会被数据库拒收 —— 即「自己导出的文件
     * 自己导不回来」。BOM 是**文件级**前缀而非格式语义，因此放在这一层统一处理，
     * CSV 与 JSON Lines 两种读取器都受益。
     *
     * 只认 UTF-8 BOM（EF BB BF）：GBK 等其它编码不以它开头，不受影响。
     */
    private fun openReader(file: File, charset: Charset, path: String): BufferedReader = try {
        BufferedReader(InputStreamReader(BomStrippingInputStream(FileInputStream(file)), charset))
    } catch (e: IOException) {
        // canRead() 只能挡住大部分情况，权限在检查之后被改、或路径指向目录仍可能落到这里
        throw IllegalArgumentException("导入文件无法打开: $path — ${e.message}", e)
    }

    /**
     * 惰性剥离 UTF-8 BOM 的输入流 —— 只在第一次读取时判断前三个字节，
     * 探测后立即决定「吞掉」或「回推」，因此不破坏流式导入。
     */
    private class BomStrippingInputStream(input: InputStream) : InputStream() {
        // PushbackInputStream：非 BOM 文件把已读的字节原样推回，不丢任何数据
        private val delegate = PushbackInputStream(input, UTF8_BOM.size)
        private var checked = false

        override fun read(): Int {
            stripBomOnce()
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            stripBomOnce()
            return delegate.read(b, off, len)
        }

        private fun stripBomOnce() {
            if (checked) return
            checked = true
            val head = ByteArray(UTF8_BOM.size)
            var n = 0
            while (n < head.size) {
                val b = delegate.read()
                if (b < 0) break
                head[n++] = b.toByte()
            }
            if (n == head.size && head.contentEquals(UTF8_BOM)) return   // 命中 BOM：已吞掉
            if (n > 0) delegate.unread(head, 0, n)                          // 未命中：全部回推
        }

        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()

        companion object {
            private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        }
    }

    private const val DEFAULT_DELIMITER = ','
}
