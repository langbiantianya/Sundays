/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.dialect.DuckDBDialect
import com.langbiantianya.export.ExportProcessManager
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.ExportRequest
import com.langbiantianya.grpc.ExportRunRequest
import com.langbiantianya.grpc.Request
import com.langbiantianya.handlers.ExportHandler
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.testutil.H2Fixture
import com.langbiantianya.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **嵌入式连接**的导出必须留在**本进程**跑。
 *
 * ## 这条用例在盯什么
 *
 * 导出默认走**导出子进程**（见 `ExportHandler.executeInMainProcess` 的子进程分支）。
 * 那条路对嵌入式方言（DuckDB / SQLite / H2）是**错的**，有两条**实测**事实：
 *
 * 1. **文件锁** —— DuckDB 库文件被主进程开着时，子进程打开同一个文件会失败：
 *    `IO Error: Cannot open file … 另一个程序正在使用此文件`。
 *    分析模式的工作区正是这样一个被主进程占着的 DuckDB 文件 ——
 *    所以「分析工作台导出查询结果」在子进程那条路上**根本跑不起来**。
 * 2. **内存库是另一个库** —— H2 的 `jdbc:h2:mem:<name>` 只在**同一个 JVM** 里共享；
 *    导出子进程是**另一个 JVM**，它看到的是一个**空库**。
 *    导出于是「成功」了，但文件里是**另一份数据** —— 静默的错误比报错更糟。
 *
 * ## 为什么断言的是「文件里有那几行」而不是「有没有报错」
 *
 * 「报错了」对「换个错法继续失败」没有抵抗力。
 * 第 2 条那种失败形态恰恰是**不报错**的 —— 必须直接看内容。
 *
 * ## 为什么要额外断言「没有拉起子进程」
 *
 * 它把「走的是本进程这条路」这件事本身钉住：
 * 万一有人把判据写反（嵌入式也去起子进程），内容断言在**同进程**里仍可能侥幸通过
 * （子进程恰好跑在本 JVM 时内存库是共享的），而这条断言一定红。
 */
class ExportEmbeddedLocalIntegrationTest : H2Fixture() {
    @TempDir
    lateinit var outDir: Path

    @BeforeEach
    fun seed() {
        executeUpdate("DROP TABLE IF EXISTS local_items")
        executeUpdate("CREATE TABLE local_items (id INT PRIMARY KEY, name VARCHAR(64))")
        executeUpdate("INSERT INTO local_items VALUES (1, '第一行'), (2, 'second')")
    }

    private fun exportRequest(
        connection: ConnectionConfig,
        sql: String,
        dir: File,
        name: String,
    ): Request =
        Request
            .newBuilder()
            .setId(TestIds.next("r-exp-local"))
            .setCategory(Category.EXPORT)
            .setAction(Action.RUN_EXPORT)
            .setConnection(connection)
            .setExportRequest(
                ExportRequest
                    .newBuilder()
                    .setRunExport(
                        ExportRunRequest
                            .newBuilder()
                            .setSql(sql)
                            .setOutputDir(dir.absolutePath)
                            .setFileName(name)
                            .setFormat("CSV")
                            .build(),
                    ).build(),
            ).build()

    // ---------------------------------------------------------------------
    // ① 内存库：子进程里那是**另一个库**
    // ---------------------------------------------------------------------

    @Test
    fun `内存库的导出必须在本进程完成`() =
        runBlocking {
            // 先把残留的子进程状态清掉 —— [ExportProcessManager] 是单例，
            // 别的用例起过子进程时「isRunning」可能还是真，那会让下面的断言失真。
            ExportProcessManager.stop()

            val frames =
                withTimeout(60.seconds) {
                    ExportHandler
                        .executeInMainProcess(
                            exportRequest(
                                config,
                                "SELECT id, name FROM local_items ORDER BY id",
                                outDir.toFile(),
                                "mem.csv",
                            ),
                        ).toList()
                }

            val last = frames.last()
            assertTrue(last.success, "导出应成功：success=false error=${last.error}")

            val file = outDir.resolve("mem.csv")
            assertTrue(file.toFile().exists(), "文件没生成：${outDir.toAbsolutePath()} 下没有 mem.csv")

            // ⚠️ 内容必须对 —— 「成功」但内容是**另一个空库**的那种失败正是这条要拦的
            val text = file.toFile().readText()
            assertTrue(text.contains("第一行"), "内存库里那两行没导出来（子进程里是另一个空库）：$text")
            assertTrue(text.contains("second"), "内存库里那两行没导出来：$text")

            assertFalse(
                ExportProcessManager.isRunning,
                "嵌入式导出不该拉起导出子进程 —— 内存库在子进程里是另一个库",
            )
        }

    // ---------------------------------------------------------------------
    // ② DuckDB 工作区文件：被本进程占着
    // ---------------------------------------------------------------------

    @Test
    fun `工作区文件被本进程占着时导出照样成功`() =
        runBlocking {
            DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
            ExportProcessManager.stop()

            val dbFile = outDir.resolve("workspace.duckdb").toFile()
            val jdbcUrl = "jdbc:duckdb:${dbFile.absolutePath}"
            // ⚠️ 这条连接必须**开着**直到导出结束 ——
            // 「文件被主进程占着」正是分析模式工作区的形态；
            // 关掉再导就测不到文件锁那条路了。
            val held = DriverManager.getConnection(jdbcUrl, "", "")
            try {
                held.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE items (id INTEGER, name VARCHAR)")
                    stmt.execute("INSERT INTO items VALUES (1, '第一行'), (2, 'second')")
                }

                val duckConfig =
                    ConnectionConfig
                        .newBuilder()
                        .setDriver("Duckdb")
                        .setJdbcUrl(jdbcUrl)
                        .setDatabase(dbFile.absolutePath)
                        .build()

                val frames =
                    withTimeout(60.seconds) {
                        ExportHandler
                            .executeInMainProcess(
                                exportRequest(
                                    duckConfig,
                                    "SELECT id, name FROM items ORDER BY id",
                                    outDir.toFile(),
                                    "workspace.csv",
                                ),
                            ).toList()
                    }

                val last = frames.last()
                assertTrue(last.success, "导出应成功：success=false error=${last.error}")

                val file = outDir.resolve("workspace.csv")
                assertTrue(file.toFile().exists(), "文件没生成：${outDir.toAbsolutePath()} 下没有 workspace.csv")

                val text = file.toFile().readText()
                assertTrue(text.contains("第一行"), "工作区里的数据没导出来：$text")
                assertTrue(text.contains("second"), "工作区里的数据没导出来：$text")

                assertFalse(
                    ExportProcessManager.isRunning,
                    "嵌入式导出不该拉起导出子进程 —— 子进程打不开主进程占着的库文件",
                )
            } finally {
                held.close()
            }
        }
}
