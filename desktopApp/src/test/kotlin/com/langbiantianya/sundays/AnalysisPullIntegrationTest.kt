/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.connection.DialectType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 拉取链路的**导入那一半**的端到端测试 —— 真 DuckDB 工作区、真引擎、手写的 CSV。
 *
 * ## 为什么只测导入那一半
 *
 * 导入这一半**不需要远端**：给它一个本地 CSV + 一个本地工作区就能验。
 * 而导出那一半要真连远端、还要起一个导出子进程 —— 在 CI 上既慢又不稳。
 * 把两半分开，才能各自有**跑得起来**的测试；
 * 整条链路的端到端另有 `AnalysisPullSmokeTest`（对着真 MySQL，不可达时跳过）。
 *
 * ## 这一轮最要紧的一条：**空串不能毁掉一行**
 *
 * CSV 导出把 NULL 写成空串。若 staging 表的列带类型，
 * `INSERT INTO t (a) VALUES ('')` 在 `a INTEGER` 上会**整行失败** ——
 * 源表里只要有一列可空且是数值，**每一行**都会丢。
 * 所以 staging 列一律 VARCHAR，而这一条测试就是钉住它。
 */
class AnalysisPullIntegrationTest {
    private lateinit var tempHome: File
    private lateinit var engine: IdbEngine
    private lateinit var workspaceConfig: ConnectionConfig
    private lateinit var workspacePath: Path
    private lateinit var csvDir: Path

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-analysis-pull").toFile()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
        val file = "${tempHome.absolutePath}/.config/sundays/workspace.duckdb"
        workspacePath = Paths.get(file)
        workspaceConfig =
            connectionConfig {
                driver = DialectType.DUCKDB.engineDriverName
                jdbcUrl = "jdbc:duckdb:$file"
                database = file
            }
        csvDir = Files.createDirectories(tempHome.toPath().resolve("csv"))
    }

    @After
    fun tearDown() {
        try {
            engine.close()
        } catch (_: Exception) {
            // 关引擎失败不该让测试红 —— 它只是回收资源
        }
    }

    /**
     * 写一个 CSV —— **带 UTF-8 BOM**，与引擎的 `CsvWriter` 一致。
     *
     * 带 BOM 是为了顺带证明导入侧会剥掉它：剥不掉的话首个列名会变成
     * `﻿id`，拼出的 INSERT 被数据库拒收。
     */
    private fun csv(
        name: String,
        content: String,
    ): Path {
        val path = csvDir.resolve(name)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(StandardCharsets.UTF_8)
        Files.write(path, bytes)
        return path
    }

    private fun puller() = EngineAnalysisPuller(engine)

    private fun query(sql: String) = runBlocking { runAnalysisQuery(engine, workspaceConfig, sql) }

    @Test
    fun `a csv lands in the workspace with the right rows`() {
        val path = csv("a.csv", "id,label\n1,alpha\n2,beta\n")

        val stats =
            runBlocking {
                puller().importIntoWorkspace(
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    csv = path,
                    staging = "staging_shop_orders",
                    columns = listOf("id", "label"),
                )
            }

        assertEquals(2L, stats.rowsInserted, "两行都该插进去")
        assertEquals(0L, stats.rowsFailed, "不该有坏行")

        val result = query("""SELECT id, label FROM "staging_shop_orders" ORDER BY id""")
        assertFalse(result.failed, "查刚拉进来的表必须成功：${result.error}")
        assertEquals(2, result.rows.size)
        assertEquals(listOf("1", "alpha"), result.rows[0])
        assertEquals(listOf("2", "beta"), result.rows[1])
    }

    @Test
    fun `an empty field lands as an empty string instead of failing the row`() {
        // 这是「staging 列一律 VARCHAR」要守的东西：
        // CSV 里的空串是源表的 NULL，若目标列带类型，这一行就会**整行失败**。
        val path = csv("b.csv", "id,label\n1,\n2,beta\n")

        val stats =
            runBlocking {
                puller().importIntoWorkspace(
                    workspace = workspaceConfig,
                    workspacePath = workspacePath,
                    csv = path,
                    staging = "staging_x",
                    columns = listOf("id", "label"),
                )
            }

        assertEquals(2L, stats.rowsInserted, "有空值的行也必须插进去 —— 不能整行丢")
        assertEquals(0L, stats.rowsFailed)

        val result = query("""SELECT id, label FROM "staging_x" ORDER BY id""")
        assertFalse(result.failed, result.error)
        assertEquals(2, result.rows.size)
        assertEquals(listOf("1", ""), result.rows[0], "空值落成空串")
    }

    @Test
    fun `re-importing the same table replaces the old rows`() {
        // 重新拉一张拉过的表：先删旧的再建新的，不能撞「Table already exists」，
        // 也不能把两次的数据叠在一起。
        val first = csv("c1.csv", "id,label\n1,alpha\n")
        runBlocking {
            puller().importIntoWorkspace(workspaceConfig, workspacePath, first, "staging_x", listOf("id", "label"))
        }

        val second = csv("c2.csv", "id,label\n2,beta\n")
        runBlocking {
            puller().importIntoWorkspace(workspaceConfig, workspacePath, second, "staging_x", listOf("id", "label"))
        }

        val result = query("""SELECT id, label FROM "staging_x"""")
        assertFalse(result.failed, result.error)
        assertEquals(1, result.rows.size, "旧数据应当被替换掉，而不是叠上去")
        assertEquals(listOf("2", "beta"), result.rows[0])
    }

    @Test
    fun `a mismatched header fails the import and leaves no half table`() {
        // CSV 的表头与建表用的列名对不上 → INSERT 引用了不存在的列 → 导入失败。
        //
        // 失败后**不能**留下半张表：用户看着树里那张表会以为拉成功了。
        val path = csv("d.csv", "id,extra\n1,alpha\n")

        val failed =
            runCatching {
                runBlocking {
                    puller().importIntoWorkspace(
                        workspace = workspaceConfig,
                        workspacePath = workspacePath,
                        csv = path,
                        staging = "staging_x",
                        columns = listOf("id", "label"),
                    )
                }
            }

        assertTrue(failed.isFailure, "列对不上时导入必须失败，而不是静默丢列")
        val tables = query("""SELECT table_name FROM information_schema.tables WHERE table_name = 'staging_x'""")
        assertFalse(tables.failed, tables.error)
        assertEquals(0, tables.rows.size, "失败的导入不能留下半张表")
    }

    @Test
    fun `the column names of the workspace table are the csv header names`() {
        // 列名带空格也要能落库 —— 引用对了就没事。
        val path = csv("e.csv", "order no,amount\nA-1,10\n")

        runBlocking {
            puller().importIntoWorkspace(
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                csv = path,
                staging = "staging_x",
                columns = listOf("order no", "amount"),
            )
        }

        val result = query("""SELECT "order no", amount FROM "staging_x"""")
        assertFalse(result.failed, result.error)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("A-1", "10"), result.rows[0])
    }

    @Test
    fun `the progress callback reports the imported rows`() {
        val path = csv("f.csv", "id,label\n1,alpha\n2,beta\n3,gamma\n")
        val seen = mutableListOf<Long>()

        runBlocking {
            puller().importIntoWorkspace(
                workspace = workspaceConfig,
                workspacePath = workspacePath,
                csv = path,
                staging = "staging_x",
                columns = listOf("id", "label"),
            ) { progress -> seen += progress.rows }
        }

        assertTrue(seen.isNotEmpty(), "导入必须有进度帧 —— 界面的进度条靠它")
        assertNotNull(seen.maxOrNull())
    }

    @Test
    fun `a failed pull leaves no temp directory behind`() {
        // 「三条路径上都被清理」里的**失败**那条。
        //
        // 失败的原因是什么**不重要**（连不上远端 / 方言没注册 / 表不存在）——
        // 重要的是：临时目录是在拉取**一开始**就建出来的，
        // 而失败之后它必须被删掉，否则磁盘上留下一个空目录。
        val tempRoot = Files.createDirectories(tempHome.toPath().resolve("tmp"))
        val brokenSource =
            connectionConfig {
                driver = DialectType.MYSQL.engineDriverName
                jdbcUrl = "jdbc:mysql://127.0.0.1:1/nonexistent"
                user = "u"
                password = "p"
                database = "nonexistent"
            }

        val failed =
            runCatching {
                runBlocking {
                    EngineAnalysisPuller(engine, tempRoot).pull(
                        source = brokenSource,
                        workspace = workspaceConfig,
                        workspacePath = workspacePath,
                        dialect = DialectType.MYSQL,
                        namespace = "nonexistent",
                        table = "nonexistent",
                    ) { }
                }
            }

        assertTrue(failed.isFailure, "拉不到远端时必须失败，而不是假装成功")
        val leftovers = Files.list(tempRoot).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "失败路径上也不能留下临时目录：$leftovers")
    }
}
