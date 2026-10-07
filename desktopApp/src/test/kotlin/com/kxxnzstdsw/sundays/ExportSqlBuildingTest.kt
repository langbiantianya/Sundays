package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.sundays.table.TableColumn
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导出弹窗的**起手 SQL** 拼装。
 *
 * ## 为什么值得单测
 *
 * 旧实现恒为 `SELECT * FROM <表>` —— 与用户眼前的过滤、排序、表内搜索全都对不上：
 * 屏幕上筛出 3 行、点导出却拿到全表几万行，而对话框里那句「将执行」还是
 * 一行省略号小字，用户根本无从判断将要导出什么。
 *
 * 而现在这条 SQL 会**显示在可编辑的编辑器里**、并决定实际导出的内容 ——
 * 拼错了用户看不出来，只能事后发现文件不对。
 */
class ExportSqlBuildingTest {

    @Test
    fun `无过滤无排序时就是整表 SELECT`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        assertEquals("SELECT * FROM orders", tab.buildExportSql())
    }

    @Test
    fun `带上手动过滤`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.whereClause = "status = 'paid'"
        assertEquals(
            "SELECT * FROM orders WHERE status = 'paid'",
            tab.buildExportSql(),
        )
    }

    @Test
    fun `带上排序`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.orderByClause = "created_at DESC"
        assertEquals(
            "SELECT * FROM orders ORDER BY created_at DESC",
            tab.buildExportSql(),
        )
    }

    @Test
    fun `过滤与排序同时带上且顺序正确`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.whereClause = "status = 'paid'"
        tab.orderByClause = "created_at DESC"
        // 顺序必须是 WHERE 在前 ORDER BY 在后 —— 反过来是语法错误，
        // 而这条 SQL 是直接发给引擎执行的，错了会整条挂掉
        assertEquals(
            "SELECT * FROM orders WHERE status = 'paid' ORDER BY created_at DESC",
            tab.buildExportSql(),
        )
    }

    @Test
    fun `表内搜索也算过滤`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.columns = listOf(
            TableColumn(key = "id", header = "id"),
            TableColumn(key = "label", header = "label"),
        )
        tab.searchTerm = "第一行"
        val sql = tab.buildExportSql()

        assertTrue(sql.startsWith("SELECT * FROM orders WHERE "), "搜索词应进 WHERE：$sql")
        assertTrue("label" in sql, "搜索应铺到已知列上：$sql")
    }

    @Test
    fun `过滤与搜索同时存在时是 AND 关系`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.columns = listOf(TableColumn(key = "label", header = "label"))
        tab.whereClause = "status = 'paid'"
        tab.searchTerm = "第一行"

        val sql = tab.buildExportSql()
        assertTrue("status = 'paid' AND" in sql, "手动过滤与搜索应是 AND：$sql")
    }

    @Test
    fun `空白过滤与空白排序不留下多余子句`() {
        val tab = TablePreviewTab(schema = "shop", tableName = "orders")
        tab.whereClause = "   "
        tab.orderByClause = "  "
        // 多出 `WHERE` / `ORDER BY` 而后面没内容 = 语法错误，导出直接失败
        assertEquals("SELECT * FROM orders", tab.buildExportSql())
    }
}

/**
 * 多语句导出：怎么切、切完留什么。
 *
 * ## 为什么「写语句」必须被剔掉
 *
 * 导出是「把查询结果落成文件」。而 `UPDATE` / `DROP` 的副作用发生在**执行时** ——
 * 用户点了个叫「导出」的按钮却改了数据，是不能接受的。所以切分之后立刻过滤。
 */
class ExportStatementSplittingTest {

    @Test
    fun `多条 SELECT 被切成多条`() {
        val stmts = exportStatementsFor(
            """
            SELECT id FROM a
            ;
            SELECT name FROM b
            """.trimIndent(),
        )
        assertEquals(2, stmts.size, "应切成两条：$stmts")
        assertTrue("FROM a" in stmts[0], "第 1 条内容不对：${stmts[0]}")
        assertTrue("FROM b" in stmts[1], "第 2 条内容不对：${stmts[1]}")
    }

    @Test
    fun `字符串里的分号不被误切`() {
        val stmts = exportStatementsFor("SELECT id, ';' AS sep FROM a")
        assertEquals(1, stmts.size, "字面量里的分号是数据，不是语句边界：$stmts")
    }

    @Test
    fun `注释里的分号不被误切`() {
        val stmts = exportStatementsFor(
            """
            -- 初始化; schema
            SELECT 1
            """.trimIndent(),
        )
        assertEquals(1, stmts.size, "注释里的分号不构成边界：$stmts")
    }

    @Test
    fun `写语句被剔掉`() {
        val stmts = exportStatementsFor(
            """
            SELECT 1;
            DROP TABLE t;
            SELECT 2
            """.trimIndent(),
        )
        assertEquals(2, stmts.size, "只留读语句：$stmts")
        assertTrue(stmts.none { "DROP" in it }, "DROP 绝不能进导出：$stmts")
    }

    @Test
    fun `全是写语句时导出列表为空`() {
        assertTrue(exportStatementsFor("DELETE FROM a").isEmpty())
        assertTrue(exportStatementsFor("   ").isEmpty())
    }

    @Test
    fun `拼回弹窗再切一次仍是原来的条数`() {
        // ⚠️ 变异验证：弹窗的起手 SQL 一旦用 `"\n"` 拼（丢掉分号），
        // 用户在弹窗里点「开始导出」时再切一次就切不开 —— 两条被当成**一条**
        // 整段发给引擎，报一句指向第二行的语法错：
        //     near 'SELECT id FROM xxx' at line 2
        // 而且标题还写着「2 条语句将导出为 2 个文件」，两个信息互相矛盾。
        // 这条把「拼 → 再切」这个往返钉死。
        val original = listOf("SELECT id FROM a", "SELECT id FROM b")
        val joinedBySemicolon = original.joinToString(";\n")
        assertEquals(original, exportStatementsFor(joinedBySemicolon))

        // 对照：换行拼接（丢分号）确实切不开 —— 这正是修复前的行为
        val joinedByNewline = original.joinToString("\n")
        assertEquals(
            1, exportStatementsFor(joinedByNewline).size,
            "丢掉分号后切不开（这条断言记录的是**反例**，不要照它写实现）",
        )
    }
}

/**
 * 多条语句的文件命名。
 *
 * 规则：**只有确实多条时才加序号** —— 单条却得到 `export_1.csv`，
 * 会让用户以为自己导了个片段。
 */
class NumberedFileNameTest {

    @Test
    fun `单条语句不加序号`() {
        assertEquals("export.csv", numberedFileName("export.csv", 0, 1))
    }

    @Test
    fun `多条按序号排列`() {
        assertEquals("export_1.csv", numberedFileName("export.csv", 0, 3))
        assertEquals("export_2.csv", numberedFileName("export.csv", 1, 3))
        assertEquals("export_3.csv", numberedFileName("export.csv", 2, 3))
    }

    @Test
    fun `无扩展名时也照加`() {
        assertEquals("报表_1", numberedFileName("报表", 0, 2))
    }

    @Test
    fun `只动最后一个点左边那段`() {
        // 用户取名成「报表(1).xlsx」，加序号不能把括号也搅进去
        assertEquals("报表(1)_2.xlsx", numberedFileName("报表(1).xlsx", 1, 4))
    }

    @Test
    fun `隐藏文件不把开头的点当扩展名`() {
        // `.env` 的 dot 在下标 0 —— 当成扩展名会切出空主体
        assertEquals(".env_1", numberedFileName(".env", 0, 2))
    }
}

/**
 * 「预览」按钮的白名单 —— 只有读语句能被一键发到数据库。
 *
 * ## 为什么是白名单而不是黑名单
 *
 * 用「不含危险关键字」是不够的：没被列进黑名单的写操作
 * （`CREATE` / `ALTER` / `CALL` / 存储过程…）会静默跑掉。
 * 所以只放行 `SELECT` 与 `WITH`。
 */
class ExportPreviewGuardTest {

    @Test
    fun `SELECT 可以预览`() {
        assertTrue(DangerousSql.isReadOnlyQuery("SELECT * FROM t"))
        assertTrue(DangerousSql.isReadOnlyQuery("  select id from t  "))
    }

    @Test
    fun `CTE 可以预览`() {
        assertTrue(DangerousSql.isReadOnlyQuery("WITH x AS (SELECT 1) SELECT * FROM x"))
    }

    @Test
    fun `带前置注释的 SELECT 仍然可以预览`() {
        // 判据必须**先剥注释** —— 否则 `-- 说明\nSELECT …` 会因为开头是 `--`
        // 而被判成「不是只读」，明明安全却点不了预览
        assertTrue(DangerousSql.isReadOnlyQuery("-- 查询用户\nSELECT * FROM t"))
        assertTrue(DangerousSql.isReadOnlyQuery("/* 头 */ SELECT 1"))
    }

    @Test
    fun `写语句一律拒绝`() {
        listOf(
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "DROP TABLE t",
            "CREATE TABLE t (a INT)",
            "ALTER TABLE t ADD b INT",
            "INSERT INTO t VALUES (1)",
            "TRUNCATE TABLE t",
            "CALL some_proc()",
        ).forEach {
            assertFalse(DangerousSql.isReadOnlyQuery(it), "「$it」不该能预览")
        }
    }

    @Test
    fun `注释里的关键字不算语义`() {
        // `scan` 与 `isReadOnlyQuery` 都**先剥注释**，所以注释里的 DROP：
        //   - 不会让 isReadOnlyQuery 误判为「不是只读」（开头仍是 SELECT）
        //   - 也不该让 scan 报危险 —— 注释不是语义，它**本来就没被执行**
        //
        // 这条钉的是「注释不参与判定」这个契约。反过来若哪天有人让 scan
        // 改成看原始文本，这里会红，而那恰恰是一个误报来源。
        val sql = "SELECT 1 -- DROP TABLE t"
        assertTrue(DangerousSql.isReadOnlyQuery(sql), "剥掉注释后是 SELECT")
        assertFalse(DangerousSql.isDangerous(sql), "注释里的 DROP 不该被当成会执行的操作")
    }

    @Test
    fun `真正的写操作仍然被 scan 命中`() {
        // 上一条的对照组：注释里的不算，真语句里的算。
        // 两者只有一条断言的话，很容易把「注释不参与判定」修成「整体不判定」。
        assertTrue(DangerousSql.isDangerous("SELECT 1; DROP TABLE t"))
        assertFalse(DangerousSql.isReadOnlyQuery("SELECT 1; DROP TABLE t"), "含写操作就不是纯只读")
    }

    @Test
    fun `只读语句后面跟一句写操作时必须拒绝`() {
        // ⚠️ 这条是**变异验证**：第一版实现只看整段开头，于是
        // `SELECT 1; DROP TABLE t` 因为「第一个词是 SELECT」被放行 ——
        // 而这正是导出对话框里最容易被粘贴出来的形态（从别处抄来的一段脚本）。
        // 只看开头 = 用户点一下「预览」就把表删了。
        assertFalse(
            DangerousSql.isReadOnlyQuery("SELECT 1; DROP TABLE t"),
            "逐句判：第 2 句是 DROP，整体就不能算只读",
        )
        assertFalse(DangerousSql.isReadOnlyQuery("SELECT * FROM a; UPDATE t SET x = 1"))
        assertFalse(DangerousSql.isReadOnlyQuery("WITH x AS (SELECT 1) SELECT * FROM x; DELETE FROM t"))
    }

    @Test
    fun `多条纯 SELECT 全部放行`() {
        // 逐句判的另一面：不能因为「有第二条」就一律拒绝 ——
        // 那会让 SQL 工作台里最常见的多查询脚本完全没法预览。
        assertTrue(DangerousSql.isReadOnlyQuery("SELECT 1; SELECT 2"))
        assertTrue(DangerousSql.isReadOnlyQuery("SELECT 1;\nSELECT 2;\n"))
        // 空片段（`;;`）不构成语句，不该把它算成「有一条不是 SELECT」
        assertTrue(DangerousSql.isReadOnlyQuery("SELECT 1;;"))
    }

    @Test
    fun `空输入不算只读`() {
        // 返回 true 会让「预览」按钮亮着，点下去报一个莫名其妙的引擎错误
        assertFalse(DangerousSql.isReadOnlyQuery(""))
        assertFalse(DangerousSql.isReadOnlyQuery("   "))
        assertFalse(DangerousSql.isReadOnlyQuery("-- 只有注释"))
    }
}
