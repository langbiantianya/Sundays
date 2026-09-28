package com.kxxnzstdsw.importer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JsonLinesReader 单元测试 —— 纯内存（StringReader），不触碰任何数据库。
 */
class JsonLinesReaderTest {

    private fun readAll(json: String): Pair<List<String>, List<Map<String, String>>> {
        val reader = JsonLinesReader(StringReader(json))
        val rows = reader.rows().asSequence().toList()
        return reader.columns to rows
    }

    @Test
    fun `multiple objects become one row each`() {
        val (columns, rows) = readAll(
            """
            {"id":1,"name":"Alice"}
            {"id":2,"name":"Bob"}
            """.trimIndent()
        )
        assertEquals(listOf("id", "name"), columns)
        assertEquals(2, rows.size)
        assertEquals(mapOf("id" to "1", "name" to "Alice"), rows[0])
        assertEquals(mapOf("id" to "2", "name" to "Bob"), rows[1])
    }

    @Test
    fun `columns follow the key order of the first line`() {
        val (columns, _) = readAll("""{"b":1,"a":2,"c":3}""" + "\n" + """{"a":9,"b":8}""")
        assertEquals(listOf("b", "a", "c"), columns)
    }

    @Test
    fun `mixed scalar types are converted to their textual form`() {
        val (_, rows) = readAll(
            """{"s":"text","i":42,"neg":-7,"f":1.50,"big":1234567890123,"t":true,"f2":false,"n":null}"""
        )
        val row = rows.single()
        assertEquals("text", row["s"])
        assertEquals("42", row["i"])
        assertEquals("-7", row["neg"])
        assertEquals("1.50", row["f"], "数字应保留原始字面量而不是被格式化")
        assertEquals("1234567890123", row["big"], "大整数不能被转成科学计数法")
        assertEquals("true", row["t"])
        assertEquals("false", row["f2"])
        assertEquals("", row["n"], "null 变成空串而不是字符串 \"null\"")
    }

    @Test
    fun `nested object and array are serialised back to compact json`() {
        val (_, rows) = readAll("""{"obj":{"a":1,"b":[2,3]},"arr":[{"x":1},null],"s":"a\"b"}""")
        val row = rows.single()
        assertEquals("""{"a":1,"b":[2,3]}""", row["obj"])
        assertEquals("""[{"x":1},null]""", row["arr"])
        assertEquals("""a"b""", row["s"], "字符串应完成反转义")
    }

    @Test
    fun `keys missing from a later row are simply absent`() {
        val (_, rows) = readAll("""{"a":1,"b":2}""" + "\n" + """{"a":3}""")
        assertEquals(mapOf("a" to "1", "b" to "2"), rows[0])
        assertEquals(mapOf("a" to "3"), rows[1])
    }

    @Test
    fun `extra keys in later rows are preserved rather than dropped`() {
        val (_, rows) = readAll("""{"a":1}""" + "\n" + """{"a":2,"b":3}""")
        assertEquals(mapOf("a" to "2", "b" to "3"), rows[1], "首行之后的额外键不应被静默丢弃")
    }

    @Test
    fun `blank lines are skipped`() {
        val (_, rows) = readAll(
            """
            {"id":1}

            {"id":2}

            """.trimIndent()
        )
        assertEquals(2, rows.size)
        assertEquals("2", rows[1]["id"])
    }

    @Test
    fun `line without trailing newline still yields its row`() {
        val (_, rows) = readAll("""{"id":1}""")
        assertEquals(1, rows.size)
    }

    @Test
    fun `CRLF input is handled`() {
        val (_, rows) = readAll("""{"id":1}""" + "\r\n" + """{"id":2}""" + "\r\n")
        assertEquals(2, rows.size)
        assertEquals("2", rows[1]["id"])
    }

    @Test
    fun `empty input yields empty columns and no rows`() {
        val reader = JsonLinesReader(StringReader(""))
        assertEquals(emptyList(), reader.columns)
        assertTrue(!reader.rows().hasNext())
    }

    @Test
    fun `malformed json line throws and names the line number`() {
        // 第 3 行是截断的 JSON 对象
        val text = """{"id":1}""" + "\n" + "\n" + """{"id": """ + "\n"
        val e = assertThrows<IllegalStateException> { JsonLinesReader(StringReader(text)).rows().asSequence().toList() }
        assertTrue(e.message!!.contains("第 3 行"), "实际消息: ${e.message}")

        // 孤立的多行对象同样不是合法 JSON Lines —— 报错要指向第一个不完整的行
        val multiLine = """{"id":""" + "\n" + "  1}\n"
        val e2 = assertThrows<IllegalStateException> { JsonLinesReader(StringReader(multiLine)).rows().asSequence().toList() }
        assertTrue(e2.message!!.contains("第 1 行"), "实际消息: ${e2.message}")
    }

    @Test
    fun `non object json line throws and names the line number`() {
        val array = """{"id":1}""" + "\n" + """[1,2]"""
        val e = assertThrows<IllegalStateException> { JsonLinesReader(StringReader(array)).rows().asSequence().toList() }
        assertTrue(e.message!!.contains("第 2 行"), "实际消息: ${e.message}")
        assertTrue(e.message!!.contains("JSON 对象"), "实际消息: ${e.message}")

        val scalar = "42"
        val e2 = assertThrows<IllegalStateException> { JsonLinesReader(StringReader(scalar)).rows().asSequence().toList() }
        assertTrue(e2.message!!.contains("第 1 行"), "实际消息: ${e2.message}")
    }

    @Test
    fun `rows is single pass and rejects a second call`() {
        val reader = JsonLinesReader(StringReader("""{"a":1}""" + "\n"))
        assertEquals(1, reader.rows().asSequence().toList().size)
        val e = assertThrows<IllegalStateException> { reader.rows() }
        assertTrue(e.message!!.contains("只能调用一次"), "实际消息: ${e.message}")
    }
}
