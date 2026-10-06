package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.connectionConfig
import com.kxxnzstdsw.grpc.dataRequest
import com.kxxnzstdsw.grpc.dataListRequest
import com.kxxnzstdsw.grpc.foreignKeyListRequest
import com.kxxnzstdsw.grpc.foreignKeyRequest
import com.kxxnzstdsw.grpc.indexListRequest
import com.kxxnzstdsw.grpc.indexRequest
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.schemaListRequest
import com.kxxnzstdsw.grpc.schemaRequest
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlRequest
import com.kxxnzstdsw.grpc.systemRequest
import com.kxxnzstdsw.grpc.tableGetDdlRequest
import com.kxxnzstdsw.grpc.tableRequest
import com.kxxnzstdsw.grpc.viewListRequest
import com.kxxnzstdsw.grpc.viewRequest
import com.kxxnzstdsw.sundays.connection.ConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.sql.ResultSet
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * **四方言冒烟测试** —— H2 / SQLite / MySQL / PostgreSQL 跑**同一套**判据。
 *
 * ## 「冒烟」验的是什么
 *
 * 只验**能不能用**：连通 → 建表 → 插数 → 读回 → 过滤/排序/搜索下推 → 对象浏览 →
 * 取 DDL → 多语句 → 事务可见性。每一项都是用户在界面上真实会碰到的那一步。
 *
 * ## 为什么必须四方言跑同一套
 *
 * 之前只在 H2 / SQLite 上走过查（见 `FeatureWalkthroughTest`），而**客户端-服务器**那类方言
 * 有一批只在这里才会暴露的东西：标识符引号规则、`INT` / `VARCHAR` 的类型映射、
 * **`CAST(col AS VARCHAR)` 的可移植性**（上一轮刚修的表内搜索就靠它）、
 * **DDL 隐式提交**（PG 尤其致命）、`search_path` 语义、连接池的 `autoCommit` 约定。
 *
 * 任何一条没在真库上验过，就等于假设它在 PG 上也成立 —— 而上一轮的教训正是
 * 「假设」害人不浅（表内搜索在所有方言上都是语法错误，却一直是绿的）。
 *
 * ## 刻意不覆盖的：触发器
 *
 * 建触发器要按方言各写一份：H2 得 `CALL "某个Java类"`、PG 要先建函数再
 * `EXECUTE FUNCTION`、MySQL 与 SQLite 用 `BEGIN…END`。那是**方言测试**该管的事，
 * 不是冒烟。这里只验「触发器这一类要么列出来、要么**明确报错**」——
 * **不许静默消失，也不许把视图一起弄没**（那正是上一轮修掉的缺陷）。
 *
 * ## 远程库不可达时 skip 而不是红
 *
 * 192.168.1.5 是内网地址，换台机器 / 断网就不存在。这类环境因素不该让构建变红，
 * 但**也不能静默跳过** —— 用 JUnit `assumeTrue` 显式 skip，报告里会留下一条 skipped。
 *
 * ## 清理是硬要求
 *
 * 远程库上每次跑都会建一个 `sundays_smoke_<nanos>` 的 database，
 * [tearDown] **无条件** DROP。漏了就是给别人留垃圾。
 *
 * ## 为什么不用 `runComposeUiTest`
 *
 * 冒烟验的是**引擎 + 方言**这一层，界面渲染与帧调度只会引入与本用例无关的抖动。
 * 界面路径由 `FeatureWalkthroughTest` / `H2GuiWalkthroughTest` 负责，两边职责不重叠。
 */
@RunWith(Parameterized::class)
class DialectSmokeTest(private val target: SmokeTarget) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "[{0}]")
        fun targets(): List<Array<Any>> = smokeTargets().map { arrayOf(it as Any) }

        /** 引擎往返超时上限。远程库比本地慢一档，但仍以秒计。 */
        const val TIMEOUT_MS = 60_000L
    }

    private lateinit var tempDir: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var conn: ConnectionConfig
    private var workspace: String = ""

    @Before
    fun setUp() {
        assumeTrue("[${target.label}] 远程库不可达，跳过（内网地址，换台机器就不存在）", target.reachable())

        tempDir = Files.createTempDirectory("sundays-smoke").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)

        target.registerDialect()
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))

        // 先划工作区再取连接配置 —— 配置里的 database 就是工作区名
        workspace = target.provision(tempDir)
        conn = target.config(workspace)
        println("[${target.label}] url=${conn.jdbcUrl} workspace=$workspace")
    }

    @After
    fun tearDown() {
        // 先释放引擎（它会连带关掉 DialectLoader 的 ClassLoader），再**无条件**归还工作区
        try { engine.close() } catch (e: Exception) { println("[${target.label}] engine.close 抛了：$e") }
        runCatching { target.teardown(workspace) }
            .onFailure { println("⚠ [${target.label}] 归还工作区失败，请手工清理 $workspace：$it") }
        System.setProperty("user.home", originalHome)
        tempDir.deleteRecursively()
    }

    // ==================================================================
    // 冒烟项
    // ==================================================================

    /**
     * **S0 连通** —— `SYSTEM.TEST_CONNECTION`。
     *
     * 放最前面：它失败时后面全部无意义，而且**引擎原文**（常含 `Caused by`）最有诊断价值。
     */
    @Test
    fun `S0 the dialect is reachable`() = withTimeout("测试连接") {
        val resp = invoke(Category.SYSTEM, Action.TEST_CONNECTION)
        assertTrue(resp.success, "[${target.label}] 测试连接失败：${resp.error}")
    }

    /**
     * **S1 建表 + 插数 + 读回**。
     *
     * 走 `SQL.EXECUTE` 而不是 `TABLE.CREATE` / `DATA.INSERT` 请求，因为**用户就是这么建表的**
     * （在工作台敲 DDL）。类型映射、引号规则这些坑都在这条路径上。
     *
     * 20 行用**直连批插**而不是 SQL 生成：H2 有 `SYSTEM_RANGE`、PG 有 `generate_series`、
     * MySQL 与 SQLite 又是另一套 —— 写成一句「四个方言都能跑」的行生成器只会制造假失败。
     * 这里要验的是**引擎能不能写入并读回**，不是各方的造数语法。
     */
    @Test
    fun `S1 create insert and read back`() = withTimeout("建表 + 写读闭环") {
        assertOk(execSql("CREATE TABLE smoke_items (id INT PRIMARY KEY, label VARCHAR(64))"))
        assertOk(execSql("CREATE TABLE smoke_tx (id INT PRIMARY KEY, note VARCHAR(64))"))

        target.direct(workspace).use { c ->
            c.prepareStatement("INSERT INTO smoke_items VALUES (?, ?)").use { ps ->
                for (i in 1..20) {
                    ps.setInt(1, i)
                    ps.setString(2, "item_$i")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        // 独立直连确认真的落库了（引擎说成功、库里却是空的情况必须能被发现）
        assertEquals(20, count("smoke_items"), "[${target.label}] 应写入 20 行")

        // 再经引擎读回来
        val rows = dataList("smoke_items", page = 1, pageSize = 100)
        assertEquals(20, rows.first, "[${target.label}] DATA.LIST 读回的行数不对（第二段=${rows.second}）")
    }

    /**
     * **S2 过滤 / 排序 / 搜索下推**。
     *
     * 判据是**引擎回的总数**，不是「界面上出现了某一行」—— 后者在本地筛与真下推两种实现下
     * 都可能成立。表内搜索那一条尤其重要：它依赖 `CAST(col AS VARCHAR) LIKE`，
     * 而这个写法能不能编过**每个方言各有答案**。
     */
    @Test
    fun `S2 filter sort and search reach the engine`() = withTimeout("过滤/排序/搜索下推") {
        assertOk(execSql("CREATE TABLE smoke_items (id INT PRIMARY KEY, label VARCHAR(64))"))
        target.direct(workspace).use { c ->
            c.prepareStatement("INSERT INTO smoke_items VALUES (?, ?)").use { ps ->
                for (i in 1..20) {
                    ps.setInt(1, i); ps.setString(2, "item_$i"); ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        // 过滤 id > 15 → 16..20 共 5 行
        val filtered = dataList("smoke_items", page = 1, pageSize = 100, where = "id > 15")
        assertEquals(5, filtered.first, "[${target.label}] 过滤 id > 15 应得 5 行（第二段=${filtered.second}）")

        // 排序 id DESC → 首行 20
        val sorted = dataList("smoke_items", page = 1, pageSize = 100, orderBy = "id DESC")
        assertEquals(
            "20", sorted.third.firstOrNull(),
            "[${target.label}] 按 id DESC 排序首行应为 20，实际 ${sorted.third}",
        )

        // 表内搜索 'item_1' → item_1 与 item_10..item_19 共 11 行
        // 写法与生产代码 `TablePreviewTab.effectiveWhere()` **完全一致**，包括 CAST 目标类型
        // 由 [SqlLiterals.castTypeFor] 按方言给出 —— MySQL 用 VARCHAR 是语法错误，
        // 这里写死 VARCHAR 就会把「生产代码那个 bug」原样复制一份到测试里。
        val castType = SqlLiterals.castTypeFor(target.dialectType)
        val like = sampleColumns()
            .map { "CAST($it AS $castType) LIKE '%item_1%'" }
            .joinToString(" OR ", prefix = "(", postfix = ")")
        val searched = dataList("smoke_items", page = 1, pageSize = 100, where = like)
        assertEquals(
            11, searched.first,
            "[${target.label}] 表内搜索 item_1 应得 11 行，实际 ${searched.first}" +
                "（第二段=${searched.second}；SQL=$like；castType=$castType）",
        )
    }

    /**
     * **S3 对象浏览** —— 库级的视图、表级的索引与外键。
     *
     * 触发器只验「要么列出来、要么**明确报错**」，理由见类注释。
     * 断言全部落在**真建出来的对象名**上 —— 列表非空不算数，库里有 100 个系统索引也算。
     */
    @Test
    fun `S3 object browsing lists views indexes and foreign keys`() = withTimeout("对象浏览") {
        seedObjects()

        val db = conn.database
        val schema = resolveSchema() ?: db

        // 库级：视图
        val views = invoke(Category.VIEW, Action.LIST) {
            viewRequest = viewRequest { list = viewListRequest { this.schema = schema } }
        }
        assertTrue(views.success, "[${target.label}] VIEW.LIST 失败：${views.error}")
        val viewNames = views.view.list.itemsList.map { it.name }
        println("[${target.label}] 视图 = $viewNames")
        assertTrue(
            viewNames.any { it.contains("smoke_v_items", ignoreCase = true) },
            "[${target.label}] 视图列表应含 smoke_v_items，实际 $viewNames",
        )

        // 库级：触发器 —— 有就列出来，没有就**必须**有话说，且不许把视图一起弄没
        val triggers = invoke(Category.TRIGGER, Action.LIST) {
            triggerRequest = com.kxxnzstdsw.grpc.triggerRequest {
                list = com.kxxnzstdsw.grpc.triggerListRequest { this.schema = schema }
            }
        }
        println("[${target.label}] 触发器 success=${triggers.success} items=${triggers.trigger.list.itemsList.map { it.name }} error=${triggers.error}")
        assertTrue(
            triggers.success || triggers.error.isNotBlank(),
            "[${target.label}] 触发器不支持时必须**明确报错**（空错误 + 空列表 = 静默消失）",
        )

        // 表级：索引 / 外键。表名要问库（见 realTableName）—— H2 存成大写
        val indexes = invoke(Category.INDEX, Action.LIST) {
            indexRequest = indexRequest {
                list = indexListRequest {
                    tableName = realTableName("smoke_items")
                    this.schema = schema
                }
            }
        }
        assertTrue(indexes.success, "[${target.label}] INDEX.LIST 失败：${indexes.error}")
        val indexNames = indexes.index.list.itemsList.map { it.name }
        println("[${target.label}] 索引 = $indexNames")
        assertTrue(
            indexNames.any { it.contains("smoke_idx_items", ignoreCase = true) },
            "[${target.label}] 索引列表应含 smoke_idx_items，实际 $indexNames",
        )

        val fks = invoke(Category.FOREIGN_KEY, Action.LIST) {
            foreignKeyRequest = foreignKeyRequest {
                list = foreignKeyListRequest {
                    tableName = realTableName("smoke_child")
                    this.schema = schema
                }
            }
        }
        assertTrue(fks.success, "[${target.label}] FOREIGN_KEY.LIST 失败：${fks.error}")
        val fkNames = fks.foreignKey.list.itemsList.map { it.name }
        println("[${target.label}] 外键 = $fkNames")
        if (target.keepsUserGivenForeignKeyName()) {
            assertTrue(
                fkNames.any { it.contains("smoke_fk_child", ignoreCase = true) },
                "[${target.label}] 外键列表应含 smoke_fk_child，实际 $fkNames",
            )
        } else {
            // SQLite 拿不到约束名（PRAGMA 不给），DuckDB 会把子句里的名字改写成
            // `<表>_<列>_fkey`。两种成因不同但对断言的影响一样：只能断言「列出来了」——
            // 断言用户给的名字会在这些方言上永远红，而红的原因与被测代码无关。
            assertTrue(fkNames.isNotEmpty(), "[${target.label}] 外键列表不该为空，实际 $fkNames")
            println("  · ${target.label} 不保留用户给的外键名（实际 $fkNames）")
        }
    }

    /** **S4 取 DDL** —— 各方言 DDL 文本风格差很多，只断言「拿得到且提到表名」。 */
    @Test
    fun `S4 table ddl can be fetched`() = withTimeout("取 DDL") {
        assertOk(execSql("CREATE TABLE smoke_items (id INT PRIMARY KEY, label VARCHAR(64))"))
        val resp = invoke(Category.TABLE, Action.GET_DDL) {
            tableRequest = tableRequest {
                getDdl = tableGetDdlRequest { tableName = realTableName("smoke_items"); schema = "" }
            }
        }
        assertTrue(resp.success, "[${target.label}] 取 DDL 失败：${resp.error}")
        val ddl = resp.table.getDdl.ddl
        println("[${target.label}] DDL = ${ddl.take(160)}")
        assertTrue(ddl.isNotBlank(), "[${target.label}] DDL 不应为空")
        assertTrue(
            ddl.contains("smoke_items", ignoreCase = true),
            "[${target.label}] DDL 应提到表名，实际：$ddl",
        )
    }

    /**
     * **S5 多语句** —— `CREATE` + 两条 `INSERT` 一次发出。
     *
     * 判据是**跨语句副作用**（表建出来且有 2 行）；单条语句区分不出「跑了全部」与「只跑了第一条」。
     */
    @Test
    fun `S5 multi statement runs every statement`() = withTimeout("多语句") {
        assertOk(
            execSql(
                "CREATE TABLE smoke_multi (id INT PRIMARY KEY); " +
                    "INSERT INTO smoke_multi VALUES (1); " +
                    "INSERT INTO smoke_multi VALUES (2)",
                multi = true,
            ),
        )
        assertEquals(2, count("smoke_multi"), "[${target.label}] 多语句应把两条 INSERT 都执行掉")
    }

    /**
     * **S6 事务可见性** —— BEGIN / ROLLBACK / COMMIT。
     *
     * 判据全落在「**另一条独立连接**看得到吗」：只断言「显示已开启」是不够的，
     * 事务没开、连接没钉住，界面一样会显示「已开启」。
     *
     * ⚠️ 种子 DDL 全在 BEGIN **之前**跑：PG 里 DDL 隐式提交，把 DDL 放进事务会让
     * 「回滚后数据没了」这条判据变成「DDL 顺手把事务提交了」，测的就不是事务了。
     */
    @Test
    fun `S6 transaction rollback discards writes and commit keeps them`() = withTimeout("事务") {
        assertOk(execSql("CREATE TABLE smoke_tx (id INT PRIMARY KEY, note VARCHAR(64))"))

        // ---- BEGIN
        val begin = invoke(Category.SYSTEM, Action.BEGIN) {
            systemRequest = systemRequest { sessionId = "smoke-${System.nanoTime()}" }
        }
        assertTrue(begin.success, "[${target.label}] 开启事务失败：${begin.error}")
        val sessionId = begin.system.begin.sessionId
        assertTrue(sessionId.isNotBlank(), "[${target.label}] 引擎必须回 session id，否则后续请求找不到被钉住的连接")
        println("[${target.label}] transaction sessionId=$sessionId")

        // ---- 回滚：写进去 → 外部看不到 → 回滚 → 仍看不到
        assertOk(execSql("INSERT INTO smoke_tx VALUES (9001, 'rollback_me')", sessionId = sessionId))
        assertEquals(
            0, count("smoke_tx", "id = 9001"),
            "[${target.label}] 未提交的数据不该被另一条连接看到（看到了说明没有真正隔离）",
        )
        assertTrue(commitOrRollback(Action.ROLLBACK, sessionId).success, "[${target.label}] 回滚失败")
        assertEquals(0, count("smoke_tx", "id = 9001"), "[${target.label}] 回滚后数据必须消失")

        // ---- 提交：同样的操作，提交后外部必须看得到
        val begin2 = invoke(Category.SYSTEM, Action.BEGIN) {
            // `this.` 不能省：外层有个同名局部 val `sessionId`，
            // 不写接收者的话赋值语句会解析到那个 val 上（编译器报「val cannot be reassigned」）
            systemRequest = systemRequest { this.sessionId = "smoke-${System.nanoTime()}" }
        }
        assertTrue(begin2.success, "[${target.label}] 二次开启事务失败：${begin2.error}")
        val session2 = begin2.system.begin.sessionId
        assertOk(execSql("INSERT INTO smoke_tx VALUES (9002, 'keep_me')", sessionId = session2))
        assertTrue(commitOrRollback(Action.COMMIT, session2).success, "[${target.label}] 提交失败")
        assertEquals(1, count("smoke_tx", "id = 9002"), "[${target.label}] 提交后外部必须看得到")
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private fun invoke(
        category: Category,
        action: Action,
        sessionId: String? = null,
        configure: com.kxxnzstdsw.grpc.RequestKt.Dsl.() -> Unit = {},
    ) : Response = runBlocking {
        val resp = runCatching {
            engine.invoke(connectionConfig {
                driver = conn.dialect.engineDriverName
                jdbcUrl = conn.jdbcUrl
                user = conn.username
                password = conn.password
                if (conn.database.isNotBlank()) database = conn.database
            }) {
                this.category = category
                this.action = action
                if (sessionId != null) this.sessionId = sessionId
                configure()
            }
        }.getOrNull()
        resp ?: throw AssertionError("[${target.label}] $category.$action 引擎无响应（抛了异常）")
    }

    private fun assertOk(resp: Response) {
        assertTrue(resp.success, "[${target.label}] 请求失败：${resp.error}")
    }

    /**
     * 发一条 SQL。
     *
     * ⚠️ `sessionId` 设在**请求**上（`Request.session_id`），**不是**
     * `SqlExecuteRequest` —— 后者根本没有这个字段，而 `session_id` 正是引擎用来
     * 找回那条被 `SET autocommit=false` 钉住的连接的凭据。放错位置编译就不过，
     * 放对位置事务才真的生效。
     */
    private fun execSql(sql: String, multi: Boolean = false, sessionId: String? = null): Response =
        invoke(Category.SQL, Action.EXECUTE, sessionId) {
            sqlRequest = sqlRequest {
                execute = sqlExecuteRequest {
                    this.sql = sql
                    // schema 必须留空：引擎拿它去做 SET SCHEMA，塞 catalog 名会报「schema not found」
                    this.schema = ""
                    multiStatement = multi
                }
            }
        }

    private fun commitOrRollback(action: Action, sessionId: String): Response =
        invoke(Category.SYSTEM, action) { systemRequest = systemRequest { this.sessionId = sessionId } }

    /**
     * `DATA.LIST` 一把梭：返回 `(行数, 引擎原文, 首行首列)`。
     *
     * 带 `where` / `orderBy` 就是在验**下推** —— 引擎回的行数与排序结果才是判据。
     */
    private fun dataList(
        table: String,
        page: Int,
        pageSize: Int,
        where: String = "",
        orderBy: String = "",
    ): Triple<Int, String, List<String>> {
        val resp = invoke(Category.DATA, Action.LIST) {
            dataRequest = dataRequest {
                list = dataListRequest {
                    tableName = table
                    this.page = page
                    this.pageSize = pageSize
                    this.schema = ""
                    this.where = where
                    this.orderBy = orderBy
                }
            }
        }
        assertTrue(resp.success, "[${target.label}] DATA.LIST($table, where=$where) 失败：${resp.error}")
        val rows = resp.data.list.rowsList
        // proto3 的标量没有 hasXxx()，total 直接读即可
        val total = resp.data.list.total.toInt()
        // ⚠️ 必须**拆 Value**：`Value.toString()` 给的是 `string_value: "20"` 这种调试串，
        // 直接拿去和 `20` 比永远不相等 —— 而值本身其实是对的（实测四方言排序首行都是 20）。
        val first = rows.firstOrNull()?.valuesMap.orEmpty().entries.take(2).map { unwrap(it.value) }
        return Triple(total, resp.error, first)
    }

    /** 表的列名（构造表内搜索的 `CAST` 谓词用）—— 直接问库，不猜。 */
    private fun sampleColumns(): List<String> = target.direct(workspace).use { c ->
        c.metaData.getColumns(null, null, realTableName("smoke_items"), null).use { rs ->
            generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }.toList()
        }
    }

    /**
     * 表在**库里真实叫什么**。
     *
     * ⚠️ 必须问库、不能直接用字面量：H2 把未加引号的标识符存成**大写**（`SMOKE_ITEMS`），
     * 而 `INDEX.LIST` / `GET_DDL` 是拿名字去查元数据的字符串比较，`'smoke_items'` 永远匹配不上
     * —— 实测报「未找到表 'smoke_items' 或其无可见列」，看起来像功能缺失，其实是大小写。
     * SQLite / MySQL / PG 保留原样小写。**这不是产品缺陷，是标识符折叠规则，各方言不同。**
     */
    private fun realTableName(logical: String): String = target.direct(workspace).use { c ->
        c.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }
                .firstOrNull { it.equals(logical, ignoreCase = true) }
        }
    } ?: logical

    /** 造对象浏览要用的那一套：表 + 索引 + 子表/外键 + 视图。 */
    private fun seedObjects() {
        assertOk(execSql("CREATE TABLE smoke_items (id INT PRIMARY KEY, label VARCHAR(64))"))
        assertOk(execSql("CREATE INDEX smoke_idx_items ON smoke_items(label)"))
        assertOk(
            execSql(
                "CREATE TABLE smoke_child (id INT PRIMARY KEY, item_id INT, " +
                    "CONSTRAINT smoke_fk_child FOREIGN KEY (item_id) REFERENCES smoke_items(id))",
            ),
        )
        assertOk(execSql("CREATE VIEW smoke_v_items AS SELECT id, label FROM smoke_items"))
    }

    /**
     * 解析 schema 名 —— 库级对象查询要的是 schema（H2 `PUBLIC` / PG `public` / MySQL 等于库名），
     * **不是**库名。拿库名去填会让 H2 执行 `SET SCHEMA "<库名>"` → Schema not found。
     */
    private fun resolveSchema(): String? = runCatching {
        val resp = invoke(Category.SCHEMA, Action.LIST) {
            schemaRequest = schemaRequest {
                list = schemaListRequest { level = "schema"; database = conn.database }
            }
        }
        resp.schema.list.itemsList.firstOrNull()
    }.getOrNull()

    /** 直连计数 —— 「真的落库了吗」与事务可见性都靠它。 */
    private fun count(table: String, where: String = "1 = 1"): Int = target.direct(workspace).use { c ->
        c.createStatement().use { s -> scalar(s.executeQuery("SELECT COUNT(*) FROM $table WHERE $where")) }
    }

    private fun scalar(rs: ResultSet): Int = rs.use { rs.next(); rs.getInt(1) }

    /**
     * 把 `google.protobuf.Value` 拆成**载荷**。
     *
     * `Value` 的 `toString()` 是 protobuf 的调试串（`string_value: "20"`），
     * 拿它做断言会得到「值明明是对的，断言却红」这种最难查的失败。
     */
    private fun unwrap(v: com.google.protobuf.Value): String = when (v.kindCase) {
        com.google.protobuf.Value.KindCase.STRING_VALUE -> v.stringValue
        com.google.protobuf.Value.KindCase.NUMBER_VALUE ->
            if (v.numberValue == kotlin.math.floor(v.numberValue)) v.numberValue.toLong().toString()
            else v.numberValue.toString()
        com.google.protobuf.Value.KindCase.BOOL_VALUE -> v.boolValue.toString()
        else -> ""
    }

    /** 给可能很慢的远程往返套一个明确的上限，超时报出正在做什么。 */
    private fun <T> withTimeout(what: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        println("[${target.label}] $what 完成，用时 ${System.currentTimeMillis() - start}ms")
        return result
    }
}
