package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.dialect.MySQLDialect
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **真 MySQL** 上的触发器 / 存储过程列表查询 —— 守住 `INFORMATION_SCHEMA` 的列名。
 *
 * ## 为什么必须是「真 MySQL」而不是 H2 兜底
 *
 * 缺陷本体是一句话：`MySQLDialect` 的 `listTriggers` / `listRoutines` / `getRoutineInfo`
 * 从 H2 抄了 **`REMARKS`** 列。`REMARKS` 是 H2 / Oracle 的列名，MySQL 里不存在：
 *
 * | 表 | 注释列（MySQL 8.4.9 实测全列清单） |
 * |---|---|
 * | `INFORMATION_SCHEMA.ROUTINES` | `ROUTINE_COMMENT` |
 * | `INFORMATION_SCHEMA.TRIGGERS` | **没有**（`ACTION_STATEMENT` 是语句正文，不是注释） |
 *
 * 真 MySQL 上一律报 `Unknown column 'REMARKS' in 'field list'`。
 *
 * **这个错在 H2 / SQLite / DuckDB / PG 上永远照不出来** —— 只有真的连一次 MySQL 才炸，
 * 而炸出来的样子又很像「这个库没有触发器」（对象树那一节整节变红，表和视图却都正常）。
 *
 * ## 为什么断言「注释读回来了」而不只是「没抛异常」
 *
 * `Unknown column` 是**语句解析期**错误，与有没有行无关，所以「不抛异常」这条断言
 * 已经能守住列名的正确性。但它守不住另一半：如果有人把 SELECT 里的注释列**直接删掉**
 * （`description` 恒为 `""`），查询照样成功，用例照样绿 —— 而「存储过程注释读不出来」
 * 仍然是一个真实缺陷。所以这里给过程写了 COMMENT 并断言它原样回来，
 * **把「读哪个列」钉死**。
 *
 * ## 资源纪律
 *
 * 建过程 / 建触发器都在固定的 [SmokeTarget.provision] 库里，且**只 DROP 对象、不 DROP 库** ——
 * `DROP DATABASE` 会在元数据锁上挂死（引擎连接池还握着那个库），历史上正是它把
 * 服务端连接数打满过。详见 `ServerSmokeTarget` 的类注释。
 */
class MySQLRoutineTriggerQueryTest {

    companion object {
        /** 与 [MySqlSmoke.provision] 固定库名保持一致。 */
        private const val SCHEMA = "sundays_smoke"

        private const val TRIGGER = "trg_orders_guard"
        private const val PROCEDURE = "proc_probe_echo"
        private const val TABLE = "routine_probe_base"

        /** 过程的 COMMENT —— 用一个一眼认得出的串，避免「碰巧等于空串」也算过。 */
        private const val PROC_COMMENT = "探针注释-roundtrip-42"
    }

    private lateinit var workspace: File
    private val dialect = MySQLDialect()

    @Before
    fun setUp() {
        assumeTrue("[MySQL] 远程库不可达，跳过", MySqlSmoke.reachable())
        workspace = Files.createTempDirectory("sundays-mysql-routines").toFile()
        MySqlSmoke.registerDialect()
        MySqlSmoke.provision(workspace)

        // 先清掉上一轮可能残留的对象 —— 用例要能重复跑
        MySqlSmoke.direct(SCHEMA).use { c ->
            c.createStatement().use { s ->
                runCatching { s.execute("DROP TRIGGER IF EXISTS $TRIGGER") }
                runCatching { s.execute("DROP PROCEDURE IF EXISTS $PROCEDURE") }
                runCatching { s.execute("DROP TABLE IF EXISTS $TABLE") }
                s.execute("CREATE TABLE $TABLE (id INT PRIMARY KEY, amount DECIMAL(10,2))")
                s.execute(
                    "CREATE TRIGGER $TRIGGER BEFORE INSERT ON $TABLE FOR EACH ROW " +
                        "SET NEW.amount = IF(NEW.amount < 0, 0, NEW.amount)",
                )
                s.execute(
                    "CREATE PROCEDURE $PROCEDURE(IN p_id INT) " +
                        "COMMENT '$PROC_COMMENT' BEGIN SELECT amount FROM $TABLE WHERE id = p_id; END",
                )
            }
        }
    }

    @After
    fun tearDown() {
        // 只回收对象，不碰库 —— 见类注释的资源纪律
        runCatching {
            MySqlSmoke.direct(SCHEMA).use { c ->
                c.createStatement().use { s ->
                    runCatching { s.execute("DROP TRIGGER IF EXISTS $TRIGGER") }
                    runCatching { s.execute("DROP PROCEDURE IF EXISTS $PROCEDURE") }
                    runCatching { s.execute("DROP TABLE IF EXISTS $TABLE") }
                }
            }
        }.onFailure { println("⚠ [MySQL] 清理探针对象失败：$it") }
        workspace.deleteRecursively()
    }

    @Test
    fun `listTriggers reads a real trigger instead of failing on REMARKS`() {
        val rows = MySqlSmoke.direct(SCHEMA).use { c ->
            kotlinx.coroutines.runBlocking { dialect.listTriggers(c, SCHEMA) }
        }
        val names = rows.map { it["name"] }
        assertTrue(
            TRIGGER in names,
            "刚建的触发器应出现在列表里，实际拿到：$names",
        )
    }

    @Test
    fun `listRoutines returns the procedure and reads its COMMENT column`() {
        val rows = MySqlSmoke.direct(SCHEMA).use { c ->
            kotlinx.coroutines.runBlocking { dialect.listRoutines(c, SCHEMA) }
        }
        val byName = rows.associateBy { it["name"] }
        assertTrue(
            PROCEDURE in byName.keys,
            "刚建的过程应出现在列表里，实际拿到：${byName.keys}",
        )
        // 触发器分支也在 listRoutines 里 —— 它原本同样查了 TRIGGERS.REMARKS
        assertTrue(
            TRIGGER in byName.keys,
            "listRoutines 也应带上触发器（它合读 ROUTINES + TRIGGERS），实际：${byName.keys}",
        )
        assertEquals(
            PROC_COMMENT, byName[PROCEDURE]!!["description"],
            "过程注释必须从 ROUTINE_COMMENT 原样读回；为空串说明有人把注释列删了而不是改对",
        )
        assertEquals(
            "", byName[TRIGGER]!!["description"],
            "MySQL 的 TRIGGERS 没有注释列，触发器 description 应为空串而不是拿 ACTION_STATEMENT 顶替",
        )
    }

    @Test
    fun `getRoutineInfo does not hit REMARKS for a procedure or a trigger`() {
        MySqlSmoke.direct(SCHEMA).use { c ->
            kotlinx.coroutines.runBlocking {
                val procInfo = dialect.getRoutineInfo(c, PROCEDURE, SCHEMA)
                assertEquals("PROCEDURE", procInfo["routine_type"], "过程类型应识别为 PROCEDURE")
                assertEquals(
                    PROC_COMMENT, procInfo["description"],
                    "过程详情的注释同样要走 ROUTINE_COMMENT",
                )

                val triggerInfo = dialect.getRoutineInfo(c, TRIGGER, SCHEMA)
                assertEquals("TRIGGER", triggerInfo["routine_type"], "触发器详情应识别为 TRIGGER")
                assertEquals(TABLE, triggerInfo["trigger_table"], "触发器详情应带出所属表名")
            }
        }
    }
}
