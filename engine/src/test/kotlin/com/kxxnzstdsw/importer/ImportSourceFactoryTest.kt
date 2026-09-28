package com.kxxnzstdsw.importer

import com.kxxnzstdsw.grpc.ImportRunRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ImportSourceFactory 单元测试 —— 只用临时文件，不连接任何数据库。
 */
class ImportSourceFactoryTest {

    private fun write(dir: Path, name: String, content: String, charset: Charset = StandardCharsets.UTF_8): File {
        val file = dir.resolve(name).toFile()
        file.writeBytes(content.toByteArray(charset))
        return file
    }

    @Test
    fun `opens a csv file and returns rows keyed by the header`(@TempDir dir: Path) {
        val file = write(dir, "users.csv", "id,name\n1,Alice\n2,Bob\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setHasHeader(true)
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals(listOf("id", "name"), source.columns)
            assertEquals(2, source.rows().asSequence().toList().size)
        }
    }

    @Test
    fun `opens a json lines file`(@TempDir dir: Path) {
        val file = write(dir, "users.jsonl", """{"id":1,"name":"Alice"}""" + "\n" + """{"id":2,"name":"Bob"}""" + "\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("json_lines")
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals(listOf("id", "name"), source.columns)
            assertEquals(
                mapOf("id" to "2", "name" to "Bob"),
                source.rows().asSequence().toList().last()
            )
        }
    }

    @Test
    fun `blank delimiter falls back to comma`(@TempDir dir: Path) {
        val file = write(dir, "a.csv", "a,b\n1,2\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals(listOf("a", "b"), source.columns)
        }
    }

    @Test
    fun `custom delimiter takes only the first character`(@TempDir dir: Path) {
        val file = write(dir, "semi.csv", "a;b\n1;2\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setDelimiter("||")
            .build()

        ImportSourceFactory.open(req).use { source ->
            // "||" 的第一个字符是 '|'，因此 ';' 不再是分隔符，整行只有一个字段
            assertEquals(listOf("a;b"), source.columns)
        }
    }

    @Test
    fun `custom encoding is honoured`(@TempDir dir: Path) {
        val charset = Charset.forName("GBK")
        val file = write(dir, "gbk.csv", "name\n张三\n", charset)
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setEncoding("GBK")
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals("张三", source.rows().asSequence().toList().single()["name"])
        }
    }

    @Test
    fun `blank encoding falls back to UTF-8`(@TempDir dir: Path) {
        val file = write(dir, "utf8.csv", "name\n张三\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setEncoding("")
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals("张三", source.rows().asSequence().toList().single()["name"])
        }
    }

    @Test
    fun `missing file is rejected with the path in the message`(@TempDir dir: Path) {
        val missing = dir.resolve("nope.csv").toFile()
        val req = ImportRunRequest.newBuilder()
            .setFilePath(missing.absolutePath)
            .setFormat("CSV")
            .build()

        val e = assertThrows<IllegalArgumentException> { ImportSourceFactory.open(req) }
        assertTrue(e.message!!.contains(missing.absolutePath), "消息应包含路径: ${e.message}")
    }

    @Test
    fun `blank file path is rejected`(@TempDir dir: Path) {
        val req = ImportRunRequest.newBuilder()
            .setFilePath("   ")
            .setFormat("CSV")
            .build()

        val e = assertThrows<IllegalArgumentException> { ImportSourceFactory.open(req) }
        assertTrue(e.message!!.contains("file_path"), "实际消息: ${e.message}")
    }

    @Test
    fun `directory path is rejected`(@TempDir dir: Path) {
        val req = ImportRunRequest.newBuilder()
            .setFilePath(dir.toFile().absolutePath)
            .setFormat("CSV")
            .build()

        val e = assertThrows<IllegalArgumentException> { ImportSourceFactory.open(req) }
        assertTrue(e.message!!.contains("不是普通文件"), "实际消息: ${e.message}")
    }

    @Test
    fun `unknown format is rejected`(@TempDir dir: Path) {
        val file = write(dir, "a.csv", "a\n1\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("XLSX")
            .build()

        val e = assertThrows<IllegalArgumentException> { ImportSourceFactory.open(req) }
        assertTrue(e.message!!.contains("XLSX"), "实际消息: ${e.message}")
    }

    @Test
    fun `unknown encoding is rejected with the requested name`(@TempDir dir: Path) {
        val file = write(dir, "a.csv", "a\n1\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setEncoding("NO-SUCH-CHARSET")
            .build()

        val e = assertThrows<IllegalArgumentException> { ImportSourceFactory.open(req) }
        assertTrue(e.message!!.contains("NO-SUCH-CHARSET"), "实际消息: ${e.message}")
    }

    @Test
    fun `hasHeader false keeps every row and uses the supplied target columns`(@TempDir dir: Path) {
        val file = write(dir, "nohdr.csv", "1,Alice\n2,Bob\n")
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .setHasHeader(false)
            .build()

        ImportSourceFactory.open(req, listOf("id", "name")).use { source ->
            assertEquals(listOf("id", "name"), source.columns)
            val rows = source.rows().asSequence().toList()
            assertEquals(2, rows.size, "无表头时首行也是数据")
            assertEquals("Alice", rows[0]["name"])
        }
    }

    @Test
    fun `hasHeader omitted defaults to true so the first row is the header`(@TempDir dir: Path) {
        val file = write(dir, "hdr.csv", "name\nAlice\n")
        // presence 语义：不 setHasHeader() 等同于「有表头」
        val req = ImportRunRequest.newBuilder()
            .setFilePath(file.absolutePath)
            .setFormat("CSV")
            .build()

        ImportSourceFactory.open(req).use { source ->
            assertEquals(listOf("name"), source.columns)
            assertEquals(1, source.rows().asSequence().toList().size)
        }
    }
}
