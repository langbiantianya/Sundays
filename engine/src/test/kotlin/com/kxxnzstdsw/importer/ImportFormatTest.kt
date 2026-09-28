package com.kxxnzstdsw.importer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ImportFormat.parse 单元测试。
 */
class ImportFormatTest {

    @Test
    fun `parses the canonical names`() {
        assertEquals(ImportFormat.CSV, ImportFormat.parse("CSV"))
        assertEquals(ImportFormat.JSON_LINES, ImportFormat.parse("JSON_LINES"))
    }

    @Test
    fun `parsing is case insensitive and trims surrounding blanks`() {
        assertEquals(ImportFormat.CSV, ImportFormat.parse("csv"))
        assertEquals(ImportFormat.CSV, ImportFormat.parse("Csv"))
        assertEquals(ImportFormat.JSON_LINES, ImportFormat.parse("json_lines"))
        assertEquals(ImportFormat.JSON_LINES, ImportFormat.parse("  JSON_LINES  "))
    }

    @Test
    fun `unknown value throws and lists the supported formats`() {
        val e = assertThrows<IllegalArgumentException> { ImportFormat.parse("XLSX") }
        val message = e.message!!
        assertTrue(message.contains("XLSX"), "消息应回显非法值: $message")
        assertTrue(message.contains("CSV"), "消息应列出支持值: $message")
        assertTrue(message.contains("JSON_LINES"), "消息应列出支持值: $message")
    }

    @Test
    fun `blank format is rejected rather than silently defaulted`() {
        assertThrows<IllegalArgumentException> { ImportFormat.parse("") }
        assertThrows<IllegalArgumentException> { ImportFormat.parse("   ") }
    }

    @Test
    fun `supported list mirrors the enum entries`() {
        assertEquals(ImportFormat.entries.map { it.name }, ImportFormat.supported)
    }
}
