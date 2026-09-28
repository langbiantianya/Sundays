package com.kxxnzstdsw.importer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CsvReader 单元测试 —— 纯内存（StringReader），不触碰任何数据库。
 * 重点是引号 / 转义 / 换行 / CRLF 这些「朴素 split 会切错」的场景。
 */
class CsvReaderTest {

    private fun readAll(
        csv: String,
        delimiter: Char = ',',
        hasHeader: Boolean = true,
        targetColumns: List<String> = emptyList(),
    ): Pair<List<String>, List<Map<String, String>>> {
        val reader = CsvReader(StringReader(csv), delimiter, hasHeader, targetColumns)
        val rows = reader.rows().asSequence().toList()
        return reader.columns to rows
    }

    @Test
    fun `plain csv exposes header as columns`() {
        val (columns, rows) = readAll("id,name,age\n1,Alice,30\n2,Bob,25\n")
        assertEquals(listOf("id", "name", "age"), columns)
        assertEquals(2, rows.size)
        assertEquals(mapOf("id" to "1", "name" to "Alice", "age" to "30"), rows[0])
        assertEquals(mapOf("id" to "2", "name" to "Bob", "age" to "25"), rows[1])
    }

    @Test
    fun `row keys follow column order`() {
        val (_, rows) = readAll("b,a,c\n1,2,3\n")
        assertEquals(listOf("b", "a", "c"), rows.single().keys.toList())
    }

    @Test
    fun `quoted field keeps embedded delimiter`() {
        val (_, rows) = readAll("id,desc\n1,\"a,b,c\"\n")
        assertEquals("a,b,c", rows.single()["desc"])
    }

    @Test
    fun `doubled quote is unescaped inside quoted field`() {
        val (_, rows) = readAll("id,desc\n1,\"say \"\"hi\"\"\"\n")
        assertEquals("say \"hi\"", rows.single()["desc"])
    }

    @Test
    fun `quoted field keeps embedded newline as a single row`() {
        val (_, rows) = readAll("id,desc\n1,\"line1\nline2\"\n2,plain\n")
        assertEquals(2, rows.size, "引号内的换行不应把一条记录拆成两行")
        assertEquals("line1\nline2", rows[0]["desc"])
        assertEquals("2", rows[1]["id"])
    }

    @Test
    fun `CRLF endings do not leak carriage return into last field`() {
        val (columns, rows) = readAll("id,name\r\n1,Alice\r\n2,Bob\r\n")
        assertEquals(listOf("id", "name"), columns)
        assertEquals(2, rows.size)
        assertEquals("Alice", rows[0]["name"])
        assertEquals("Bob", rows[1]["name"])
        assertTrue(rows.none { it.values.any { v -> v.contains('\r') } }, "字段里不应残留 \\r")
    }

    @Test
    fun `trailing newline does not produce an extra empty row`() {
        val (_, rows) = readAll("id\n1\n2\n")
        assertEquals(2, rows.size)
    }

    @Test
    fun `blank lines are skipped`() {
        val (_, rows) = readAll("id,name\n\n1,Alice\n\n\n2,Bob\n\n")
        assertEquals(2, rows.size)
        assertEquals("1", rows[0]["id"])
        assertEquals("2", rows[1]["id"])
    }

    @Test
    fun `headerless csv keeps every row as data and takes columns from the target table`() {
        val (columns, rows) = readAll(
            "1,Alice\n2,Bob\n",
            hasHeader = false,
            targetColumns = listOf("id", "name"),
        )

        assertEquals(listOf("id", "name"), columns)
        assertEquals(2, rows.size, "无表头时首行也是数据，一行都不能丢")
        assertEquals(mapOf("id" to "1", "name" to "Alice"), rows[0])
        assertEquals(mapOf("id" to "2", "name" to "Bob"), rows[1])
    }

    @Test
    fun `headerless csv without target columns fails loudly instead of inventing names`() {
        // 拿首行数据值冒充列名只会拼出必然失败的 INSERT，且错误信息毫无指向性
        assertThrows<IllegalArgumentException> {
            CsvReader(StringReader("1,Alice\n"), ',', hasHeader = false)
        }
    }

    @Test
    fun `ragged rows are padded and truncated instead of failing`() {
        val (columns, rows) = readAll("a,b,c\n1,2\n1,2,3,4\n")
        assertEquals(listOf("a", "b", "c"), columns)
        assertEquals(mapOf("a" to "1", "b" to "2", "c" to ""), rows[0], "缺列补空串")
        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "3"), rows[1], "多列截断")
    }

    @Test
    fun `empty input yields empty columns and no rows`() {
        val reader = CsvReader(StringReader(""), ',', true)
        assertEquals(emptyList(), reader.columns)
        assertTrue(!reader.rows().hasNext())
    }

    @Test
    fun `header only input yields columns but no rows`() {
        val (columns, rows) = readAll("id,name\n")
        assertEquals(listOf("id", "name"), columns)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `custom delimiter is honoured`() {
        val (columns, rows) = readAll("id;desc\n1;\"x;y\"\n", delimiter = ';')
        assertEquals(listOf("id", "desc"), columns)
        assertEquals("x;y", rows.single()["desc"])
    }

    @Test
    fun `non ascii values survive untouched`() {
        val (_, rows) = readAll("姓名,城市\n张三,北京\n")
        assertEquals("张三", rows.single()["姓名"])
        assertEquals("北京", rows.single()["城市"])
    }

    @Test
    fun `empty quoted field stays an empty string`() {
        val (_, rows) = readAll("a,b\n\"\",x\n")
        assertEquals("", rows.single()["a"])
    }

    @Test
    fun `rows is single pass and rejects a second call`() {
        val reader = CsvReader(StringReader("a\n1\n"), ',', true)
        assertEquals(1, reader.rows().asSequence().toList().size)
        val e = assertThrows<IllegalStateException> { reader.rows() }
        assertTrue(e.message!!.contains("只能调用一次"), "实际消息: ${e.message}")
    }

    @Test
    fun `rowSequence is a convenience wrapper over rows`() {
        val reader = CsvReader(StringReader("a,b\n1,2\n3,4\n"), ',', true)
        assertEquals(2, reader.rowSequence().count())
    }
}
