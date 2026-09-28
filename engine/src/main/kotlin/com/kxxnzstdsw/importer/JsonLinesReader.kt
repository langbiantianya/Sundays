package com.kxxnzstdsw.importer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JSON Lines 导入读取器 —— 与 [com.kxxnzstdsw.export.JsonLinesWriter] 互为逆过程。
 *
 * 输入约定：每行一个独立的 JSON 对象，空行跳过。解析复用 engine 已有的
 * kotlinx.serialization-json，不额外引入依赖，也不自己手写 JSON 解析器。
 *
 * 取值规则（导入侧一切都是字符串，转换交给上层的列类型推断）：
 * - `null` → 空字符串（区分「没有值」与字符串 "null"）
 * - 数字 / 布尔 → 原始字面量文本（`1.50` 保留为 `1.50`，`true` 为 `true`）
 * - 字符串 → 解转义后的内容
 * - 对象 / 数组 → 紧凑 JSON 文本（`{"a":1}` / `[1,2]`），
 *   不做「只取第一个字段」之类的猜测，否则数据会静默丢失
 *
 * [columns] 取**首行**出现过的键序：JSON 没有表头概念，而分批 insert 必须先知道
 * 目标列集合。后续行出现的额外键依然会出现在 [rows] 的返回值里（不丢数据），
 * 但不会进 [columns]，因为列集合在读取第一行时就已经固定。
 *
 * **坏行绝不静默吞掉**：非法 JSON 或非对象行一律抛 [IllegalStateException]，
 * 消息里带 1 起的物理行号（空行也计入行号，与用户在编辑器里看到的一致）和解析错误。
 * 是否跳过（`ignore_errors`）是上层的策略决定，读取器替它做决定就等于让数据丢失不可见。
 *
 * **已知边界**：kotlinx.serialization 即便在 `isLenient = false` 下也接受**未加引号的字符串值**
 * （`{"id": oops}` 会被读成 `{"id": "oops"}`）。这是库的行为，不是本读取器的选择 ——
 * 要拒绝它就得自带一个 JSON 词法扫描器，收益不抵复杂度。结构层面的错误
 * （截断对象、非对象行、`{id:1}` 这类未加引号的键）仍会被正常拒绝。
 * 实际数据文件里的字符串值都由导出工具加了引号，因此该边界不会影响正常导入。
 *
 * [rows] 与 [CsvReader.rows] 一致是单遍的：第二次调用抛 [IllegalStateException]。
 */
class JsonLinesReader(source: Reader) : ImportSource {

    private val reader: BufferedReader =
        if (source is BufferedReader) source else BufferedReader(source)

    private val rowsCalled = AtomicBoolean(false)

    /** 首行解析出的对象：既决定 [columns]，也要作为第一行数据产出 */
    private val firstObject: JsonObject?

    /** 已读到的物理行号（1 基），空行同样计入，保证报错行号与编辑器一致 */
    private var lineNumber = 0

    init {
        val firstLine = readNextLine()
        firstObject = if (firstLine == null) null else parseObject(firstLine, lineNumber)
    }

    override val columns: List<String> get() = firstObject?.keys?.toList() ?: emptyList()

    override fun rows(): Iterator<Map<String, String>> {
        check(rowsCalled.compareAndSet(false, true)) {
            "JsonLinesReader.rows() 只能调用一次：底层 Reader 是单遍流，无法回绕"
        }
        return RowIterator()
    }

    override fun close() {
        reader.close()
    }

    private inner class RowIterator : Iterator<Map<String, String>> {

        private var pending: JsonObject? = firstObject
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
            val value = next ?: throw NoSuchElementException("JsonLinesReader 已读到文件末尾")
            next = null
            computed = false
            return value
        }

        private fun advance(): Map<String, String>? {
            val obj = pending ?: readNextObject() ?: return null
            pending = null
            return toRow(obj)
        }
    }

    /** 读取下一个非空行对象；EOF 返回 null */
    private fun readNextObject(): JsonObject? {
        val line = readNextLine() ?: return null
        return parseObject(line, lineNumber)
    }

    /** 读一行原始文本（跳过空行），并把 [lineNumber] 推进到它的行号 */
    private fun readNextLine(): String? {
        while (true) {
            val line = reader.readLine() ?: return null
            lineNumber++
            if (line.isBlank()) continue
            return line
        }
    }

    private fun parseObject(line: String, lineNo: Int): JsonObject {
        val element: JsonElement = try {
            json.parseToJsonElement(line)
        } catch (e: SerializationException) {
            throw IllegalStateException(
                "JSON Lines 第 $lineNo 行不是合法 JSON: ${e.message ?: e::class.simpleName}; 内容: ${line.snippet()}",
                e
            )
        }
        if (element !is JsonObject) {
            throw IllegalStateException(
                "JSON Lines 第 $lineNo 行必须是 JSON 对象，实际是 ${element.typeLabel()}: ${line.snippet()}"
            )
        }
        return element
    }

    private fun toRow(obj: JsonObject): Map<String, String> {
        val row = LinkedHashMap<String, String>(obj.size.coerceAtLeast(1))
        for ((key, value) in obj) {
            row[key] = value.toCell()
        }
        return row
    }

    private fun JsonElement.toCell(): String = when (this) {
        // JsonNull 也是 JsonPrimitive，必须先判它，否则会渲染成字面量 "null"
        is JsonNull -> ""
        is JsonPrimitive -> content
        // 对象 / 数组：紧凑 JSON 文本，保证不丢结构信息
        is JsonObject, is JsonArray -> toString()
    }

    private fun JsonElement.typeLabel(): String = when (this) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (isString) "字符串" else "标量"
        is JsonObject -> "对象"
        is JsonArray -> "数组"
    }

    /** 报错时只带有限长度的内容，避免超长单行把日志刷爆 */
    private fun String.snippet(): String =
        if (length <= 120) this else take(120) + "…"

    private companion object {
        val json = Json
    }
}
