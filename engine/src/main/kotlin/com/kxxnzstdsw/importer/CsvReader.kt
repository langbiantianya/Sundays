package com.kxxnzstdsw.importer

import java.io.BufferedReader
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean
/**
 * CSV 导入读取器 —— 与 [com.kxxnzstdsw.export.CsvWriter] 互为逆过程。
 *
 * 刻意不使用 `String.split(",")` 或任何「按分隔符切行」的朴素做法：真实世界里
 * 字段里出现分隔符、双引号甚至换行都是常态（Excel 导出的 CSV 就是这样），
 * 朴素切分会把一行拆成多行，导入的数据会静默错位。这里实现了一个按字符推进的
 * RFC 4180 风格状态机（见文件末尾的 [CsvRecordParser]），只从 [Reader] 逐字符读，
 * 不把整个文件装进内存，因此大文件也能边读边批量写入。
 *
 * 语义约定（都是刻意选择，不做「猜」）：
 * - **[hasHeader] = true**：首行是列名，不产出为数据行。
 * - **[hasHeader] = false**：**每一行都是数据**，一行都不丢；列名必须由调用方通过
 *   `fallbackColumns` 提供（引擎从目标表的元数据取）。文件本身给不出列名时，
 *   与其拿首行数据值冒充列名拼出一条必然失败的 INSERT，不如构造期就报错。
 * - **列数不齐的行**：缺列补空串、多列截断。数据文件参差不齐比直接失败常见得多，
 *   整批中止对用户没有帮助；真正的坏行由更上层的「按列类型转换失败」来拦。
 * - **空行**：整行为空（单字段且为空）的记录被跳过，不产出空行。
 * - **引号未闭合**：读到文件末尾即按已读内容收尾，不抛异常（宽容优于整批失败）。
 * - **[rows] 是单遍的**：底层 Reader 只能顺序读一遍，因此同一实例第二次调用
 *   [rows] 抛 [IllegalStateException]，而不是安静地返回空迭代器 ——
 *   后者会让「读了 0 行」这种严重 bug 伪装成「文件是空的」。
 */
class CsvReader(
    source: Reader,
    private val delimiter: Char = ',',
    private val hasHeader: Boolean = true,
    fallbackColumns: List<String> = emptyList(),
) : ImportSource {

    private val reader: BufferedReader =
        if (source is BufferedReader) source else BufferedReader(source)

    private val parser = CsvRecordParser(reader, delimiter)

    /** 列名；构造时即确定好，保证 rows() 之前就能拿到 columns */
    private val header: List<String>
    /** rows() 只允许调用一次（见类注释） */
    private val rowsCalled = AtomicBoolean(false)

    /**
     * @param fallbackColumns `hasHeader = false` 时使用的列名 —— 无表头的 CSV 里
     *   文件本身给不出列名，只能由调用方（引擎从目标表的元数据拿到）提供。
     *   为空时构造失败：宁可明确报错，也不要拿首行**数据值**冒充列名，
     *   那样会拼出一条 `INSERT INTO t ("1","Alice")` 这种必然失败、且错误信息毫无指向性的语句。
     */
    init {
        if (hasHeader) {
            // 有表头：首条记录即列名，不产出为数据行
            val first = parser.nextRecord()
            header = first ?: emptyList()
        } else {
            require(fallbackColumns.isNotEmpty()) {
                "has_header=false 的 CSV 必须由调用方提供目标列名（fallbackColumns 为空）；" +
                    "无表头文件本身无法确定列集合"
            }
            header = fallbackColumns
            // 不消费任何记录：无表头时**每一行都是数据**
        }
    }

    override val columns: List<String> get() = header

    override fun rows(): Iterator<Map<String, String>> {
        check(rowsCalled.compareAndSet(false, true)) {
            "CsvReader.rows() 只能调用一次：底层 Reader 是单遍流，无法回绕"
        }
        // 无表头时不消费任何记录，因此这里不需要 pending 的「首行」
        return RowIterator(null)
    }

    override fun close() {
        reader.close()
    }

    /**
     * 把一条记录按列名对齐成一行：不足补空串、超出截断。
     * 用 LinkedHashMap 保证行内键序与 [columns] 一致。
     */
    private fun toRow(record: List<String>): Map<String, String> {
        val row = LinkedHashMap<String, String>(header.size.coerceAtLeast(1))
        for (i in header.indices) {
            row[header[i]] = record.getOrElse(i) { "" }
        }
        return row
    }

    private inner class RowIterator(private var pending: List<String>?) :
        Iterator<Map<String, String>> {

        private var next: Map<String, String>? = null
        private var computed = false

        override fun hasNext(): Boolean {
            if (!computed) {
                next = advance()
                computed = true
            }
            return next != null
        }

        override fun next(): Map<String, String> {
            if (!computed) hasNext()
            val value = next ?: throw NoSuchElementException("CsvReader 已读到文件末尾")
            next = null
            computed = false
            return value
        }

        private fun advance(): Map<String, String>? {
            while (true) {
                val record = pending ?: parser.nextRecord() ?: return null
                pending = null
                if (isBlankRecord(record)) continue
                return toRow(record)
            }
        }
    }

    private companion object {
        /** 整行为空（只有一个空字段）视为空行，跳过 */
        fun isBlankRecord(record: List<String>): Boolean =
            record.size == 1 && record[0].isEmpty()
    }
}

/**
 * 按字符推进的 CSV 记录解析器。
 *
 * 之所以不直接用 `Reader.readLine()`：引号内的换行属于字段内容而不是记录边界，
 * 而 `readLine` 无法区分。逐字符读虽然比按行读慢，但解析器本身只做状态转移，
 * 真正的 IO 由外层 [BufferedReader] 兜底。
 */
private class CsvRecordParser(
    private val reader: Reader,
    private val delimiter: Char,
) {

    /** 单字符回退槽：处理 CRLF 时需要「读一个看看，不是就还回去」 */
    private var pushed = NOTHING

    /**
     * 读取下一条记录，EOF 返回 null（文件末尾没有多余的空记录）。
     */
    fun nextRecord(): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var sawAny = false

        while (true) {
            val c = read()
            if (c == -1) {
                if (!sawAny) return null
                fields.add(field.toString())
                return fields
            }
            sawAny = true

            if (inQuotes) {
                if (c == QUOTE.code) {
                    val next = read()
                    if (next == QUOTE.code) {
                        // "" -> 字面量引号
                        field.append(QUOTE)
                        fieldStarted = true
                    } else {
                        // 引号闭合：后续字符（含可能的分隔符/换行）回到非引号分支处理
                        inQuotes = false
                        if (next != -1) pushed = next
                    }
                } else {
                    // 引号内的换行与分隔符都是普通字符
                    field.append(c.toChar())
                    fieldStarted = true
                }
                continue
            }

            when (c) {
                QUOTE.code -> {
                    if (fieldStarted) {
                        // 非字段开头的引号按字面量处理
                        field.append(QUOTE)
                    } else {
                        inQuotes = true
                        fieldStarted = true
                    }
                }

                delimiter.code -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    fieldStarted = false
                }

                LF -> {
                    fields.add(field.toString())
                    return fields
                }

                CR -> {
                    // CRLF / CR 均可作为行尾：吃掉紧随的 LF，末字段不会沾上多余的 \r
                    val next = read()
                    if (next != LF && next != -1) pushed = next
                    fields.add(field.toString())
                    return fields
                }

                else -> {
                    field.append(c.toChar())
                    fieldStarted = true
                }
            }
        }
    }

    private fun read(): Int {
        if (pushed != NOTHING) {
            val c = pushed
            pushed = NOTHING
            return c
        }
        return reader.read()
    }

    private companion object {
        const val NOTHING = -2
        val QUOTE = '"'
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}
