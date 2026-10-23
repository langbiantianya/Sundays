/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.dialect.MySQLDialect
import com.langbiantianya.dialect.PostgreSQLDialect
import com.langbiantianya.grpc.connectionConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 原生扫描器的**纯规则**测试：开关、扩展目录、连接 URI。
 *
 * ## 为什么不都靠真库验
 *
 * URI 的形状是**字符串活**，一改就错；而它不碰任何数据库就能验 ——
 * 真库只能验「连得上」，验不出「拼对了没有」。
 */
class NativeExtensionScannerTest {
    private val mysqlDialect = MySQLDialect()
    private val pgDialect = PostgreSQLDialect()

    private fun mysqlConfig(
        url: String = "jdbc:mysql://192.168.1.5:3306/sundays_smoke?useSSL=false",
        user: String = "root",
        password: String = "666666",
        database: String = "sundays_smoke",
    ) = connectionConfig {
        driver = "Mysql"
        jdbcUrl = url
        this.user = user
        this.password = password
        this.database = database
    }

    // ──────────────── 开关 ────────────────

    @Test
    fun `the native scanner is off unless it is turned on explicitly`() {
        // **默认关**是刻意的：两条路的类型映射不同（MySQL 的 TINYINT(1)
        // 原生落成 BOOLEAN、我们的落成 INTEGER），「装没装扩展」不该隐式决定类型。
        val property = "sundays.duckdb.nativeExtension"
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            // 环境变量在测试进程里通常没有 → 仍然是关
            assertFalse(NativeExtensionScanner.isEnabled(), "默认必须是关的")
            System.setProperty(property, "1")
            assertTrue(NativeExtensionScanner.isEnabled(), "显式打开就该生效")
            System.setProperty(property, "true")
            assertTrue(NativeExtensionScanner.isEnabled(), "true 也算打开")
            System.setProperty(property, "0")
            assertFalse(NativeExtensionScanner.isEnabled(), "0 是关")
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    // ──────────────── 扫描器映射 ────────────────

    @Test
    fun `only mysql and postgres have a native scanner`() {
        assertEquals("mysql_scanner", NativeExtensionScanner.scannerFor(mysqlDialect)?.extensionType)
        assertEquals("postgres_scanner", NativeExtensionScanner.scannerFor(pgDialect)?.extensionType)
        // H2 / SQLite / DuckDB 没有官方扫描器 → 走纯 Java 表函数。
        assertNull(NativeExtensionScanner.scannerFor(H2Dialect()))
    }

    // ──────────────── 连接 URI ────────────────

    @Test
    fun `the mysql uri does not pin the database`() {
        // MySQL 上**命名空间就是库**。URI 里带了库就把库钉死了 ——
        // 树上选别的库就看不到（而分析模式列的是**服务器上的所有库**）。
        val uri =
            NativeExtensionScanner.buildUri(
                mysqlConfig(),
                mysqlDialect,
                NativeExtensionScanner.scannerFor(mysqlDialect)!!,
            )
        assertEquals("mysql://root:666666@192.168.1.5:3306", uri)
    }

    @Test
    fun `the postgres uri carries the database`() {
        // PG 上**命名空间是 schema**，库由连接决定 —— URI 必须带上它。
        val config =
            connectionConfig {
                driver = "Postgresql"
                jdbcUrl = "jdbc:postgresql://192.168.1.5:5433/postgres"
                user = "postgres"
                password = "666666"
                database = "postgres"
            }
        val uri =
            NativeExtensionScanner.buildUri(
                config,
                pgDialect,
                NativeExtensionScanner.scannerFor(pgDialect)!!,
            )
        assertEquals("postgresql://postgres:666666@192.168.1.5:5433/postgres", uri)
    }

    @Test
    fun `the credentials in the uri are percent encoded`() {
        // 密码里有 `@` / `:` 就会把 URI 的结构拆坏 ——
        // 轻则连错库，重则**连到别的主机**去。编码之后没有这个歧义。
        val uri =
            NativeExtensionScanner.buildUri(
                mysqlConfig(user = "us@er", password = "p@ss:word"),
                mysqlDialect,
                NativeExtensionScanner.scannerFor(mysqlDialect)!!,
            )
        assertEquals("mysql://us%40er:p%40ss%3Aword@192.168.1.5:3306", uri)
    }

    @Test
    fun `a url without a host has no uri`() {
        // 自定义 JDBC URL（没有主机名）→ 拼不出 URI → 降级到纯 Java 表函数。
        val uri =
            NativeExtensionScanner.buildUri(
                mysqlConfig(url = "jdbc:mysql:custom"),
                mysqlDialect,
                NativeExtensionScanner.scannerFor(mysqlDialect)!!,
            )
        assertNull(uri, "没有主机就拼不出 URI")
    }

    // ──────────────── 扩展目录 ────────────────

    @Test
    fun `the extension directories always contain the user home one`() {
        // 用户自己 `INSTALL` 的扩展在 `~/.duckdb/extensions` ——
        // 漏掉它就等于「用户装了扩展也没人用」。
        val dirs = NativeExtensionScanner.extensionDirectories()
        assertTrue(
            dirs.any { it.replace('\\', '/').endsWith("/.duckdb/extensions") },
            "默认的用户扩展目录必须在清单里：$dirs",
        )
    }

    @Test
    fun `the configured extension directories are used as they are`() {
        // 显式配了就**以配置为准** —— 不再自动补用户目录：
        // 自动补等于偷偷加了一条用户没同意的来源。
        val property = "sundays.duckdb.extensionDirs"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "/opt/duckdb/ext;/srv/duckdb/ext")
            val dirs = NativeExtensionScanner.extensionDirectories()
            assertEquals(listOf("/opt/duckdb/ext", "/srv/duckdb/ext"), dirs)
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
