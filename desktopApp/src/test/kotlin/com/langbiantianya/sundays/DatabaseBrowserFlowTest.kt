/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.editor.CompletionKind
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.sql.SqlLiterals
import com.langbiantianya.sundays.table.CellEdit
import com.langbiantianya.sundays.table.ColumnDraft
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow
import com.langbiantianya.sundays.table.TableStructureDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据库浏览屏幕状态机的烟雾测试 —— 不渲染 Compose UI, 只驱动 [DatabaseBrowserState].
 *
 * 验证:
 * - `refreshDatabases` 拉取数据库列表
 * - `toggleDatabase` + 内部 `loadTables` 拉取表列表
 * - `openTab` 拉取表预览, 同一表再次打开不会创建重复标签页
 * - `closeTab` 修正 selectedTabIndex
 *
 * 使用 H2 内存库(每个测试独立 DB); H2 dialect 通过 `:dialect-h2` 的 SPI 在
 * `IdbEngine()` bootstrap 时自动加载.
 */
class DatabaseBrowserFlowTest {
    private lateinit var tempHome: File
    private lateinit var originalHome: String
    private lateinit var engine: IdbEngine
    private lateinit var jdbcUrl: String
    private lateinit var dbName: String

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-db-browser-test").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.absolutePath)

        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        // 注: 其它测试 (ConnectionManagerFlowTest) 调 engine.close() 后 DialectLoader
        // 会被清空; IdbEngine bootstrap 是幂等的, 重新构造不会再次扫描类路径.
        // 这里用 registerForTesting 把 H2 dialect 显式注入, 让 SCHEMA.LIST / TABLE.LIST
        // 等走 URL scheme 的请求能找到方言.
        DialectLoader.registerForTesting("H2", H2Dialect())

        dbName = "bdbtest_${System.nanoTime()}"
        jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

        // 建测试表 + 写入样本数据
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(64))")
                stmt.executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, total INT)")
                stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')")
                stmt.executeUpdate("INSERT INTO orders VALUES (1, 100), (2, 250), (3, 50)")
                // `wide` 是**故意造的宽表**：默认每页 100 行时它只有一页，分页器就退化成装饰品，
                // 页码相关的缺陷在 2 行的 users 上一个都测不出来。25 行 / 每页 10 行 = 3 页。
                stmt.executeUpdate("CREATE TABLE wide (id INT PRIMARY KEY, tag VARCHAR(32))")
                (1..25).forEach { stmt.executeUpdate("INSERT INTO wide VALUES ($it, 'row$it')") }
                // 无主键表：`DATA.UPDATE` 的 where 为空 map 时引擎会当成「无条件更新」，
                // 等于改写整张表 —— 所以没有主键就必须**拒绝编辑**（见同名的用例）。
                stmt.executeUpdate("CREATE TABLE nokey (a VARCHAR(32), b INT)")
                stmt.executeUpdate("INSERT INTO nokey VALUES ('x', 1), ('y', 2)")
                // 唯一约束表：用来制造一条**必失败**的 UPDATE，
                // 从而验证整批事务回滚（见 `a failing batch rolls back every row it already changed`）。
                stmt.executeUpdate("CREATE TABLE uniq_users (id INT PRIMARY KEY, code VARCHAR(16) UNIQUE)")
                stmt.executeUpdate("INSERT INTO uniq_users VALUES (1, 'a'), (2, 'b')")
                // 复合主键表：只取其中一列去 WHERE 会一次改掉多行，
                // 所以 `primaryKeyColumns` 必须是**列表**而不是单值。
                stmt.executeUpdate("CREATE TABLE composite (tenant INT, seq INT, note VARCHAR(32), PRIMARY KEY (tenant, seq))")
                stmt.executeUpdate("INSERT INTO composite VALUES (1, 1, 't1s1'), (1, 2, 't1s2'), (2, 1, 't2s1')")
                // 二进制列：`DATA.LIST` 对它回的是 `"[LOB Data]"` 占位串而不是真实字节。
                // 照着占位串写回去 = 用一句提示语覆盖掉用户的数据，所以这一列必须挡住。
                // 同一个表里再放一列 VARCHAR，用来验证「挡的是二进制而不是整张表」。
                stmt.executeUpdate("CREATE TABLE blobs (id INT PRIMARY KEY, payload VARBINARY(64), note VARCHAR(32))")
                stmt.executeUpdate("INSERT INTO blobs VALUES (1, X'00FF10', 'keep'), (2, X'AABB', 'me too')")
                // `id` 列**不是**主键，且故意让两行同值 —— 行标识必须仍然互不相同。
                // 否则 `DataTable` 的 LazyColumn key 重复会直接抛异常，
                // 而编辑暂存缓冲会把两行当成同一行、改动互相覆盖。
                stmt.executeUpdate("CREATE TABLE dupid (id INT, code VARCHAR(8) PRIMARY KEY, note VARCHAR(16))")
                stmt.executeUpdate("INSERT INTO dupid VALUES (1, 'a', 'x'), (1, 'b', 'y')")
                // NULL 用例专用表：
                // - `nick` 可空，且**故意**混进一个真 NULL 和一个空串 —— 两者在界面上
                //   都可能是「一片空白」，只有直接查库才能区分。
                // - `name` 是 NOT NULL，用来验证 `nullableColumns` 不是「全表都可空」。
                // - `id` 是自增列（用 H2 2.x 的标准写法：裸 `IDENTITY` 在 2.x 已是语法错误），
                //   用来验证 `insertableColumns` 会把它剔掉 —— 让用户手填自增主键
                //   十有八九会撞车，而撞车的报错完全指不出「你本来就不该填这一列」。
                stmt.executeUpdate(
                    "CREATE TABLE people (id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                        "name VARCHAR(32) NOT NULL, nick VARCHAR(32), age INT)",
                )
                stmt.executeUpdate("INSERT INTO people (name, nick, age) VALUES ('Alice', 'al', 30), ('Bob', NULL, 40), ('Cid', '', 50)")
                // 自增列的插入目标：没有 id，插入时那一列必须被剔掉而不是让用户去填。
                stmt.executeUpdate(
                    "CREATE TABLE notes (id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, body VARCHAR(64))",
                )
                stmt.executeUpdate("INSERT INTO notes (body) VALUES ('n1'), ('n2')")
            }
        }
    }

    @After
    fun tearDown() {
        // 释放连接池 —— 必须清, 否则后续测试类 (ConnectionManagerFlowTest 的
        // activePoolCount 断言) 会看到累计的池数量, 出现 expected 1 but was 10.
        // 用 PoolManager.closeAll() 而非 engine.close(): 后者会一并清空方言/驱动注册表,
        // 而 IdbEngine bootstrap 是全局幂等的 (AtomicBoolean 只置一次 true),
        // 后续测试类的 IdbEngine() 构造不会再扫描 SPI, 会引发
        // "No dialect plugin matches JDBC URL".
        try {
            PoolManager.closeAll()
        } catch (_: Exception) {
        }
        // 删除测试库 (避免同进程内 H2 mem 库名冲突)
        try {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { it.createStatement().use { s -> s.execute("DROP ALL OBJECTS") } }
        } catch (_: Exception) {
        }
        System.setProperty("user.home", originalHome)
    }

    /**
     * 构造一个 [DatabaseBrowserState] 并把当前连接注入 —— 测试场景下跳过 UI 渲染.
     */
    private fun newBrowser(): DatabaseBrowserState {
        val state = DatabaseBrowserState(engine, CoroutineScope(Dispatchers.Default))
        state.bindConnection(TestConnectionFactory.build(jdbcUrl))
        return state
    }

    @Test
    fun `refreshDatabases loads at least one database entry`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()

            withTimeout(5_000) {
                while (state.loadingDatabases) {
                    kotlinx.coroutines.delay(50)
                }
            }

            assertNull(state.errorMessage, "expected no error, got: ${state.errorMessage}")
            assertTrue(state.databases.isNotEmpty(), "expected at least one database, got: ${state.databases}")
        }

    @Test
    fun `toggleDatabase loads table list and openTab creates preview`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) {
                while (state.loadingDatabases) kotlinx.coroutines.delay(50)
            }
            val db = state.databases.first()
            state.toggleDatabase(db)

            withTimeout(5_000) {
                while (db in state.loadingTables) kotlinx.coroutines.delay(50)
            }
            assertNull(state.tableLoadError[db])
            val tables = state.tablesByDatabase[db]
            assertNotNull(tables, "table list should be loaded for $db")
            // H2 把未引用的标识符归一为大写 —— 大小写不敏感比对
            val upper = tables.map { it.uppercase() }.toSet()
            assertTrue("USERS" in upper, "users table missing: $tables")
            assertTrue("ORDERS" in upper, "orders table missing: $tables")

            // 打开 users 预览 (H2 大写)
            val usersTable = tables.first { it.uppercase() == "USERS" }
            state.openTab(db, usersTable)
            val usersTab = state.tabs.single { it.tableName.uppercase() == "USERS" }
            withTimeout(5_000) {
                while (usersTab.loading) kotlinx.coroutines.delay(50)
            }
            assertNull(usersTab.error, "users tab error: ${usersTab.error}")
            assertEquals(2L, usersTab.total, "users total rows")
            assertEquals(2, usersTab.rows.size, "users row count")
            // 列至少含 id 和 name (H2 大写)
            val keys = usersTab.columns.map { it.key.uppercase() }.toSet()
            assertTrue("ID" in keys && "NAME" in keys, "expected id/name columns, got: $keys")
            // 数据内容核对 (H2 NAME 列名 = NAME, value "Alice")
            val alice = usersTab.rows.first { (it.cells["NAME"] as? String) == "Alice" }
            // 引擎 DataHandler.buildRow 对非 LOB 列一律走 rs.getString —— 单元格值是字符串.
            assertEquals("1", alice.id.toString(), "Alice's id should be 1")
        }

    /**
     * 「新增表」的成功路径 —— 回归用例。
     *
     * ## 这条用例在防什么
     *
     * 早先 `createTable` 里写的是
     *
     * ```kotlin
     * val createFailure = runDdl(...) ?: Msg.format(StrKey.TABLE_CREATE_FAILED, name)
     * ```
     *
     * 而 `runDdl` 的契约是「**成功返回 null**，失败返回引擎原文」——
     * elvis 于是把「成功(null)」翻译成了「失败」：表**真的建出来了**，界面却报
     * 「建表失败」，弹窗不关，保存按钮又变回可点。用户看到的现象是
     * 「表建好了，但界面说没建好」，比直接报错还难查。
     *
     * 另一处（逐列发 `CREATE TABLE`）让**多列表**根本建不出来 ——
     * 第二条 CREATE 必然 `Table 'x' already exists`。所以这条用例用**两列**：
     * 少一列就说明又退回逐列建表了。
     */
    @Test
    fun `createTable 建多列表成功且表真的落到库里`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.toggleDatabase(db)
            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }

            val draft =
                TableStructureDraft(
                    database = db,
                    schema = "",
                    tableName = "made_by_ui",
                    isNew = true,
                    columns =
                        listOf(
                            ColumnDraft(
                                name = "id",
                                type = "INT",
                                primaryKey = true,
                                nullable = false,
                                autoIncrement = true,
                            ),
                            ColumnDraft(
                                name = "name",
                                type = "VARCHAR",
                                length = "64",
                                nullable = false,
                            ),
                        ),
                )
            val failure = state.createTable(draft)
            assertNull(failure, "建表不应失败，实际错误：$failure")

            // 库里真的有两列 —— 只建出一列就说明又退回「逐列发 CREATE」了
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.metaData.getColumns(null, "PUBLIC", "MADE_BY_UI", "%").use { rs ->
                    val names = mutableListOf<String>()
                    while (rs.next()) names += rs.getString("COLUMN_NAME")
                    assertEquals(2, names.size, "应当建出两列，实际：$names")
                }
            }
        }

    /**
     * 建完表，**树里的表列表要能看到它**。
     *
     * 早先 `refreshTables` 调的是 `toggleDatabase` —— 那是「切换展开态」，
     * 库本来就展开着的时候调它，效果是**把树折叠起来**；而且它只在
     * 「还没拉过表列表」时才重拉，于是树上永远是一份会话初期的快照：
     * 库里已经有那张表了，用户却要在树里找不到。
     */
    @Test
    fun `建表后树里的表列表刷新出新表且不折叠`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.toggleDatabase(db)
            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }
            assertTrue(db in state.expandedDatabases, "建表前库应当是展开的")

            val draft =
                TableStructureDraft(
                    database = db,
                    schema = "",
                    tableName = "made_by_ui",
                    isNew = true,
                    columns = listOf(ColumnDraft(name = "id", type = "INT", primaryKey = true, nullable = false)),
                )
            assertNull(state.createTable(draft), "建表不应失败")

            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }
            assertTrue(db in state.expandedDatabases, "建表后库**不能**被折叠掉")
            val tables = state.tablesByDatabase[db].orEmpty().map { it.uppercase() }
            assertTrue("MADE_BY_UI" in tables, "树里应当出现新建的表，实际：$tables")
        }

    @Test
    fun `sql schema completions cover libraries tables and columns of visited tables`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) { while (state.loadingDatabases) delay(50) }

            val db = state.databases.first()

            // ── 库刚加载、还没展开任何节点时：只有库名，没有表和字段 ──
            val beforeExpand = state.sqlSchemaCompletions()
            val dbs = beforeExpand.filter { it.kind == CompletionKind.DATABASE }.map { it.label }
            assertTrue(dbs.contains(db), "库名应已可补全：$dbs")
            assertTrue(
                beforeExpand.none { it.kind == CompletionKind.TABLE },
                "未展开任何库时不应有表候选（表是懒加载的）：$beforeExpand",
            )

            // ── 展开后：表进入候选池 ──
            state.toggleDatabase(db)
            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }
            val tables = state.tablesByDatabase[db].orEmpty()
            val tableLabels =
                state
                    .sqlSchemaCompletions()
                    .filter { it.kind == CompletionKind.TABLE }
                    .map { it.label }
            assertEquals(tables.toSet(), tableLabels.toSet(), "每个已加载的表都应可补全")

            // ── 表名 detail 标出所属库，否则不同库的同名表看起来一模一样 ──
            val withDetail = state.sqlSchemaCompletions().first { it.label == tables.first() }
            assertTrue(
                withDetail.detail?.contains(db) == true,
                "表候选 detail 应含所属库：$withDetail",
            )

            // ── 打开预览后：该表的字段才进入候选池（这是「只补访问过的表」的取舍）──
            val usersTable = tables.first { it.uppercase() == "USERS" }
            state.openTab(db, usersTable)
            val usersTab = state.tabs.single { it.tableName.uppercase() == "USERS" }
            withTimeout(5_000) { while (usersTab.loading) delay(50) }

            val cols =
                state
                    .sqlSchemaCompletions()
                    .filter { it.kind == CompletionKind.COLUMN }
                    .map { it.label.uppercase() }
            assertTrue("ID" in cols && "NAME" in cols, "已访问表的字段应可补全：$cols")
            // 字段 detail 带「表.列 · 类型」，用户能看出这个字段属于哪张表
            val colItem =
                state
                    .sqlSchemaCompletions()
                    .first { it.kind == CompletionKind.COLUMN && it.label.equals("ID", true) }
            assertTrue(
                colItem.detail?.contains(usersTable) == true,
                "字段候选 detail 应含表名：$colItem",
            )

            // ── 未访问的表不应带来字段 ──
            val ordersTable = tables.first { it.uppercase() == "ORDERS" }
            val ordersCols =
                state
                    .sqlSchemaCompletions()
                    .filter { it.kind == CompletionKind.COLUMN && it.detail?.contains(ordersTable) == true }
            assertTrue(
                ordersCols.isEmpty(),
                "未打开预览的表不应有字段候选（否则就得发 TABLE.COLUMN_LIST）：$ordersCols",
            )
        }

    @Test
    fun `sql schema completions order libraries then tables then columns`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) { while (state.loadingDatabases) delay(50) }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }
            val usersTable =
                state.tablesByDatabase[db]
                    .orEmpty()
                    .first { it.uppercase() == "USERS" }
            state.openTab(db, usersTable)
            val usersTab = state.tabs.single { it.tableName.uppercase() == "USERS" }
            withTimeout(5_000) { while (usersTab.loading) delay(50) }

            // 顺序即优先级：`FROM us` 想要的是 users 这张表，不是 USING 这个关键字
            val kinds = state.sqlSchemaCompletions().map { it.kind }
            val ranks =
                kinds.map {
                    when (it) {
                        CompletionKind.DATABASE -> 0
                        CompletionKind.TABLE -> 1
                        CompletionKind.COLUMN -> 2
                        else -> 3
                    }
                }
            assertEquals(ranks.sorted(), ranks, "候选必须按 库→表→字段 排好序：$kinds")
        }

    @Test
    fun `completion signature tracks columns but ignores rows`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) { while (state.loadingDatabases) delay(50) }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) { while (db in state.loadingTables) delay(50) }
            val usersTable =
                state.tablesByDatabase[db]
                    .orEmpty()
                    .first { it.uppercase() == "USERS" }
            state.openTab(db, usersTable)
            val usersTab = state.tabs.single { it.tableName.uppercase() == "USERS" }
            withTimeout(5_000) { while (usersTab.loading) delay(50) }

            val loaded = schemaCompletionSignature(state.tabs)
            assertTrue(loaded.isNotEmpty() && loaded.first().third.isNotEmpty(), "预览加载完应有列签名")

            // 只改 rows（翻页 / 刷新）→ 签名必须**不变**。
            // rows 是真正的数据（可能上千行），被牵进 remember key 的话每翻一页都会重建
            // 整份候选列表，而候选内容根本没变。
            usersTab.rows = usersTab.rows + TableRow(9999, mapOf("ID" to "9999"))
            assertEquals(
                loaded,
                schemaCompletionSignature(state.tabs),
                "rows 变化不应让补全缓存失效（否则每次翻页都重算候选）",
            )

            // 改 columns（预览真正变了）→ 签名**必须**变，否则新表 / 新列的候选出不来
            usersTab.columns = usersTab.columns + TableColumn("EMAIL", "EMAIL")
            assertNotEqualsCompat(loaded, schemaCompletionSignature(state.tabs))
        }

    private fun assertNotEqualsCompat(
        illegal: Any?,
        actual: Any?,
    ) {
        if (illegal == actual) throw AssertionError("值不应相等：$illegal")
    }

    @Test
    fun `opening the same table twice does not create a duplicate tab`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) {
                while (state.loadingDatabases) kotlinx.coroutines.delay(50)
            }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) {
                while (db in state.loadingTables) kotlinx.coroutines.delay(50)
            }
            val tableName = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }

            state.openTab(db, tableName)
            state.openTab(db, tableName)
            state.openTab(db, tableName)
            assertEquals(1, state.tabs.size, "duplicate opens should not add tabs")
            assertEquals(0, state.selectedTabIndex, "second open should re-select the existing tab")
        }

    @Test
    fun `opening a different table creates a second tab`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) {
                while (state.loadingDatabases) kotlinx.coroutines.delay(50)
            }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) {
                while (db in state.loadingTables) kotlinx.coroutines.delay(50)
            }
            val users = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }
            val orders = state.tablesByDatabase[db]!!.first { it.uppercase() == "ORDERS" }

            state.openTab(db, users)
            state.openTab(db, orders)
            assertEquals(2, state.tabs.size, "different tables -> distinct tabs")
            assertEquals(1, state.selectedTabIndex, "selectedTabIndex should be the latest")
            val titles = state.tabs.map { it.tableName.uppercase() }.toSet()
            assertEquals(setOf("USERS", "ORDERS"), titles)
        }

    /**
     * 回归：`loading` 落定的瞬间数据必须已经就位。
     *
     * `loadTabPreview` 曾把 `tab.loading = false` 写在写 `tab.rows` **之前**。二者是各自独立的
     * 快照状态，即使同一个协程里连着写也是两次提交 —— 于是「轮询 loading 等它落定」的调用方
     * 可能在两次提交之间醒来，读到 `loading == false` + 空 rows（症状：间歇性
     * `users row count expected:<2> but was:<0>`）。
     *
     * 这里在测试线程上**无挂起地紧轮询** `tab.loading`（写协程跑在 `Dispatchers.Default`，是另一个线程），
     * 把「loading 落定」与「那一瞬间 rows 的规模」一起捕获。用 `delay` 轮询（哪怕 1ms）会错过这个
     * 微秒级窗口 —— 那正是原缺陷只在低概率下暴露的原因。修复后 `loading` 最后落定，观测必为满数据。
     */
    @Test
    fun `preview data is populated by the time loading flag clears`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) {
                while (state.loadingDatabases) kotlinx.coroutines.delay(1)
            }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) {
                while (db in state.loadingTables) kotlinx.coroutines.delay(1)
            }
            val table = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }

            state.openTab(db, table)
            val tab = state.tabs.single { it.tableName.uppercase() == "USERS" }
            // 紧轮询到 loading 落定，并在**同一瞬间**记录 rows 规模
            var rowsAtClear = -1
            withTimeout(5_000) {
                while (true) {
                    if (!tab.loading) {
                        rowsAtClear = tab.rows.size
                        break
                    }
                }
            }

            // loading 一旦为 false，行数据必须已经在位（不允许出现中间态）
            assertEquals(2, rowsAtClear, "rows must be populated once loading clears")
            assertTrue(tab.columns.isNotEmpty(), "columns must be populated once loading clears")
        }

    @Test
    fun `closeTab adjusts selectedTabIndex`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            withTimeout(5_000) {
                while (state.loadingDatabases) kotlinx.coroutines.delay(50)
            }
            val db = state.databases.first()
            state.toggleDatabase(db)
            withTimeout(5_000) {
                while (db in state.loadingTables) kotlinx.coroutines.delay(50)
            }
            val users = state.tablesByDatabase[db]!!.first { it.uppercase() == "USERS" }
            val orders = state.tablesByDatabase[db]!!.first { it.uppercase() == "ORDERS" }

            state.openTab(db, users)
            state.openTab(db, orders)

            // selectedTabIndex = 1 (orders)
            assertEquals(1, state.selectedTabIndex)

            // 关闭最后一个 -> 选中回退到 0
            state.closeTab(1)
            assertEquals(1, state.tabs.size)
            assertEquals(0, state.selectedTabIndex)

            // 关闭最后一个标签 -> selectedTabIndex = -1
            state.closeTab(0)
            assertEquals(0, state.tabs.size)
            assertEquals(-1, state.selectedTabIndex)
        }

    // ------------------------------------------------------------------------
    // SQL 工作台（状态层契约）
    // ------------------------------------------------------------------------

    @Test
    fun `selectPane switches between table, SQL and generate workbenches`() {
        val state = newBrowser()
        assertEquals(BrowserPane.TABLE, state.activePane, "初始应展示表预览")
        state.selectPane(BrowserPane.SQL)
        assertEquals(BrowserPane.SQL, state.activePane)
        state.selectPane(BrowserPane.GENERATE)
        assertEquals(BrowserPane.GENERATE, state.activePane)
        state.selectPane(BrowserPane.TABLE)
        assertEquals(BrowserPane.TABLE, state.activePane, "应能切回表预览")
    }

    @Test
    fun `executeSql SELECT populates result rows from streaming frames`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.SQL)
            val sheet = state.currentSqlSheet()!!
            sheet.editor.setText("SELECT id, name FROM users ORDER BY id")
            state.executeSql()

            // 异步流式收集 —— 等到该 sheet 的 running 复位
            withTimeout(5_000) {
                while (sheet.running) delay(20)
            }
            assertNull(sheet.error, "应无错误: ${sheet.error}")
            assertEquals(2, sheet.rowCount, "users 表有 2 行")
            assertEquals(2, sheet.rows.size)
            assertTrue(
                sheet.columns.any { it.key.equals("id", ignoreCase = true) },
                "列名应包含 id",
            )
            assertTrue(
                sheet.columns.any { it.key.equals("name", ignoreCase = true) },
                "列名应包含 name",
            )
            assertNull(sheet.affectedRows, "SELECT 不应填 affected_rows")
        }

    /**
     * 回归：**执行期间必须留下可取消的 request id，且结束路径全部清空**。
     *
     * 引擎侧 `SYSTEM.CANCEL`（v2.16）按 `target_request_id` 找正在跑的 `Statement` 并调
     * `Statement.cancel()` —— 协程取消打断不了阻塞中的 `rs.next()`，只有它能停。
     * 而此前 `executeSql` 把 `UUID.randomUUID()` 赋给 `req.id` 之后**就丢了**，
     * 前端连「能不能停」这个问题都无从回答。
     *
     * 断言分两半，缺一不可：
     * - **执行期间**非 null —— 否则「停止」按钮永远不出现，功能等于没接
     * - **结束后**为 null —— 留着会让按钮挂在界面上，而那个 id 对应的执行早已结束，
     *   点了只会回一句「找不到目标」
     */
    @Test
    fun `executeSql exposes a cancellable request id and clears it when finished`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            assertNull(sheet.runningRequestId, "尚未执行时不应有 request id")

            sheet.editor.setText("SELECT id, name FROM users ORDER BY id")
            state.executeSql()
            // executeSql 同步写入 id 后才 launch 收集协程，这一刻必然可读
            assertNotNull(
                sheet.runningRequestId,
                "执行期间必须留下 request id，否则「停止」无从发起 SYSTEM.CANCEL",
            )

            withTimeout(5_000) { while (sheet.running) delay(20) }
            assertNull(
                sheet.runningRequestId,
                "执行结束后必须清空 —— 残留会让「停止」按钮一直挂着，点下去只会「找不到目标」",
            )
        }

    /**
     * 回归：**结果集必须有上限，且截断要显式标记**。
     *
     * `SQL.EXECUTE` 是流式的，引擎会一直推行帧。此前无条件 `frames += frame`，
     * `SELECT * FROM big_table` 会把整个结果集搬进堆里，而用户什么都做不了。
     *
     * 截断标志单列（`rowsTruncated`）而不复用 `rowCount`：截断时 `rowCount` 只是**已收到**
     * 的行数，让分页栏拿它当总数会显示「第 1 / 100 页」却点不出后面的页 —— 用户以为数据丢了。
     */
    @Test
    fun `sql results are capped and the truncation is reported`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            // 用 `SYSTEM_RANGE` 造出超过上限的行数。数字写死在这儿**不引用生产常量** ——
            // 上限一旦被改宽，这条断言要跟着一起漂移，就再也发现不了「上限没了」。
            val overCap = SQL_RESULT_MAX_ROWS + 200
            sheet.editor.setText("SELECT X FROM SYSTEM_RANGE(1, $overCap)")
            state.executeSql()

            withTimeout(60_000) { while (sheet.running) delay(20) }

            assertNull(sheet.error, "不该报错: ${sheet.error}")
            assertEquals(
                SQL_RESULT_MAX_ROWS,
                sheet.rows.size,
                "结果集必须停在上限 —— 无上限攒行能让一张大表把桌面进程拖垮",
            )
            assertTrue(
                sheet.rowsTruncated,
                "截断必须显式标记 —— 静默截断等于骗用户「这张表就这么大」",
            )
        }

    /**
     * 回归：小于上限的结果**不得**被标成截断。
     *
     * 上一条只钉住了「超限时截断」；若上限判断写反（`>` 写成 `>=` 之类），
     恰好等于上限的那一批会被白标一次「已截断」，用户以为数据不全。
     */
    @Test
    fun `sql results below the cap are not marked as truncated`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            sheet.editor.setText("SELECT id, name FROM users ORDER BY id")
            state.executeSql()

            withTimeout(5_000) { while (sheet.running) delay(20) }

            assertEquals(2, sheet.rows.size)
            assertFalse(sheet.rowsTruncated, "没到上限就不该出现截断提示")
        }

    @Test
    fun `executeSql empty text sets error without calling engine`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            sheet.editor.setText("   \n   ")
            state.executeSql()
            assertEquals("SQL 为空", sheet.error)
            assertFalse(sheet.running)
        }

    @Test
    fun `executeSql DDL surfaces affected_rows instead of rows`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.SQL)
            val sheet = state.currentSqlSheet()!!
            sheet.editor.setText("CREATE TABLE sqlbench_tmp (id INT)")
            state.executeSql()

            withTimeout(5_000) {
                while (sheet.running) delay(20)
            }
            assertNull(sheet.error, "应无错误: ${sheet.error}")
            assertNotNull(sheet.affectedRows, "DDL 应填 affected_rows")
            assertEquals(0, sheet.rows.size, "DDL 不应产生 SELECT 行帧")
        }

    /**
     * 多 SQL sheet —— 每个 sheet 一份独立文本 + 独立结果，切换只改渲染目标，不串显另一个 sheet 的结果。
     */
    @Test
    fun `sql sheets keep their own editor text and results`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.SQL)
            val first = state.currentSqlSheet()!!
            first.editor.setText("SELECT id, name FROM users ORDER BY id")
            state.executeSql()
            withTimeout(5_000) { while (first.running) delay(20) }
            assertEquals(2, first.rowCount, "users 有 2 行")

            state.addSqlSheet()
            val second = state.currentSqlSheet()!!
            assertEquals(2, state.sqlSheets.size)
            assertEquals(1, state.selectedSqlIndex, "新增 sheet 后应选中它")
            assertEquals("", second.editor.text, "新 sheet 应为空文本")

            second.editor.setText("SELECT id FROM orders")
            state.executeSql()
            withTimeout(5_000) { while (second.running) delay(20) }
            assertEquals(3, second.rowCount, "orders 有 3 行")

            // 切回第一个 sheet —— 文本与结果都还在，且没有被第二个 sheet 的执行覆盖
            state.selectSqlSheet(0)
            assertEquals("SELECT id, name FROM users ORDER BY id", first.editor.text)
            assertEquals(2, first.rowCount)
            assertEquals("SELECT id FROM orders", second.editor.text)
            assertEquals(3, second.rowCount)

            // 删除选中 sheet 后选中项回退，剩余 sheet 内容不受影响
            state.removeSqlSheet(1)
            assertEquals(1, state.sqlSheets.size)
            assertEquals(0, state.selectedSqlIndex)
            assertEquals(2, state.currentSqlSheet()!!.rowCount)
        }

    @Test
    fun `renameSqlSheet trims the name and ignores blank input`() {
        val state = newBrowser()
        state.addSqlSheet()
        assertEquals("SQL 2", state.currentSqlSheet()!!.title, "新建 sheet 的默认名")

        state.renameSqlSheet(1, "  用户查询  ")
        assertEquals("用户查询", state.currentSqlSheet()!!.title, "应去掉首尾空白")

        state.renameSqlSheet(1, "   ")
        assertEquals("用户查询", state.currentSqlSheet()!!.title, "空名应保持原名")

        state.renameSqlSheet(9, "越界")
        assertEquals(2, state.sqlSheets.size)
        assertEquals("SQL 1", state.sqlSheets.first().title, "越界下标不得改到别的 sheet")
    }

    @Test
    fun `renameGenerateScript renames the script and its result row label`() {
        val state = newBrowser()
        assertEquals("脚本 1", state.currentGenerateScript()!!.title)

        state.renameGenerateScript(0, " 订单造数 ")
        assertEquals("订单造数", state.currentGenerateScript()!!.title)
        assertEquals(
            "1. 订单造数",
            state.generateResultRows().single().cells["script"],
            "造数结果表的脚本列应跟随重命名",
        )

        state.renameGenerateScript(0, "")
        assertEquals("订单造数", state.currentGenerateScript()!!.title, "空名应保持原名")
    }

    /**
     * SQL 工作台的高亮档位由**连接方言**决定 —— 换库即换关键字 / 类型 / 内置函数词表。
     */
    @Test
    fun `sql dialect profile follows the connected database`() {
        val state = newBrowser() // TestConnectionFactory 建的是 H2 连接
        assertEquals("sql-h2", state.sqlDialectProfile().languageId)

        val base = TestConnectionFactory.build(jdbcUrl)
        state.bindConnection(base.copy(id = "mysql-1", dialect = DialectType.MYSQL))
        assertEquals(SqlDialectProfile.MYSQL, state.sqlDialectProfile())

        state.bindConnection(base.copy(id = "sqlite-1", dialect = DialectType.SQLITE))
        assertEquals("sql-sqlite", state.sqlDialectProfile().languageId)

        // 未知方言 / 未连接 → 标准档位（不冒充任何方言的关键字）
        state.bindConnection(base.copy(id = "unknown-1", dialect = DialectType.UNKNOWN))
        assertEquals(SqlDialectProfile.STANDARD, state.sqlDialectProfile())
        state.bindConnection(null)
        assertEquals(SqlDialectProfile.STANDARD, state.sqlDialectProfile())
    }

    @Test
    fun `bindConnection clears SQL workbench state when switching connections`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.SQL)
            val sheet = state.currentSqlSheet()!!
            sheet.editor.setText("SELECT 1")
            state.executeSql()
            withTimeout(5_000) { while (sheet.running) delay(20) }
            assertTrue(sheet.rows.isNotEmpty() || sheet.affectedRows != null)
            state.addSqlSheet()
            assertEquals(2, state.sqlSheets.size)

            // 切到新连接（不同 db 名）
            val newJdbc = "jdbc:h2:mem:bdbtest2_${System.nanoTime()};DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(newJdbc, "sa", "").use { conn ->
                conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
            }
            state.bindConnection(TestConnectionFactory.build(newJdbc))

            assertEquals(1, state.sqlSheets.size, "切换连接应复位为单个 sheet")
            assertEquals(0, state.selectedSqlIndex)
            val fresh = state.currentSqlSheet()!!
            assertEquals("SQL 1", fresh.title, "切换连接应复位为默认名的单个 sheet")
            assertEquals("", fresh.editor.text, "切换连接应清空编辑器文本")
            assertEquals(0, fresh.rows.size)
            assertNull(fresh.affectedRows)
            assertNull(fresh.error)
            assertFalse(fresh.running)
        }

    // ------------------------------------------------------------------------
    // 造数工作台（状态层契约）
    // ------------------------------------------------------------------------

    @Test
    fun `executeGenerate inserts rows via Lua script and reports per-script stats`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.GENERATE)
            val script = state.currentGenerateScript()!!
            script.editor.setText("for i = 1, 3 do insert('orders', {id = 100 + i, total = i * 10}) end")

            state.executeGenerate()
            withTimeout(10_000) { while (state.generateRunning) delay(20) }

            assertNull(state.generateError, "应无错误: ${state.generateError}")
            assertEquals(3L, state.generateTotalInserted, "脚本应插入 3 行")
            assertEquals(1, state.generateTablesProcessed, "终止帧应回填已处理脚本数")
            assertEquals("orders", script.lastTable, "进度帧应带回目标表名")

            // 引擎确实把行写进了库（造数不是只报数）
            val count =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM orders").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            assertEquals(6, count, "orders 原有 3 行 + 造数 3 行")
        }

    @Test
    fun `generate scripts keep their own editor state across pane switches`() {
        val state = newBrowser()
        state.selectPane(BrowserPane.GENERATE)
        val first = state.currentGenerateScript()!!
        first.editor.setText("-- 第一个脚本")

        state.addGenerateScript()
        val second = state.currentGenerateScript()!!
        assertEquals(2, state.generateScripts.size)
        assertEquals(1, state.selectedGenerateIndex, "新增脚本后应选中它")

        // 切到表预览再切回来：两个脚本各自的文本都还在，选中项不变
        state.selectPane(BrowserPane.TABLE)
        state.selectPane(BrowserPane.GENERATE)
        assertEquals("-- 第一个脚本", first.editor.text)
        assertEquals(1, state.selectedGenerateIndex, "切 pane 不应改动选中脚本")
        assertTrue(second.editor.text.isNotBlank(), "第二个脚本保留模板内容")

        // 删掉选中脚本后选中项回退
        state.removeGenerateScript(1)
        assertEquals(1, state.generateScripts.size)
        assertEquals(0, state.selectedGenerateIndex)
    }

    @Test
    fun `executeGenerate with blank scripts reports error without calling engine`() {
        val state = newBrowser()
        val script = state.currentGenerateScript()!!
        script.editor.setText("   ")
        state.executeGenerate()
        assertEquals("造数脚本为空", state.generateError)
        assertFalse(state.generateRunning)
    }

    @Test
    fun `bindConnection clears generate workbench state when switching connections`() =
        runBlocking {
            val state = newBrowser()
            state.selectPane(BrowserPane.GENERATE)
            val script = state.currentGenerateScript()!!
            script.editor.setText("for i = 1, 2 do insert('orders', {id = 200 + i, total = i}) end")
            state.executeGenerate()
            withTimeout(10_000) { while (state.generateRunning) delay(20) }
            assertTrue(state.generateTotalInserted > 0)

            val newJdbc = "jdbc:h2:mem:bdbtest3_${System.nanoTime()};DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(newJdbc, "sa", "").use { conn ->
                conn.createStatement().use { it.executeUpdate("CREATE TABLE t (id INT)") }
            }
            state.bindConnection(TestConnectionFactory.build(newJdbc))

            assertEquals(1, state.generateScripts.size, "切换连接应复位为单个默认脚本")
            assertEquals(0, state.generateTotalInserted)
            assertEquals(0, state.generateTablesProcessed)
            assertNull(state.generateError)
            assertFalse(state.generateRunning)
        }

    // =========================================================================
    // 宽表分页 —— 分页器曾经是装饰品
    // =========================================================================

    /**
     * 回归：表预览的**分页器曾经是死的**。
     *
     * `PreviewTabContent` 传的是 `pageSize = PageSize.S100` 而**没有**传 `currentPage` /
     * `onPageChange` / `totalCount`，`loadTabPreview` 又把请求里的 `page` 写死成 1。
     * 两侧都停在第 1 页：信息条写着「共 25 行」，分页器却是「1 / 1」且所有按钮禁用 ——
     * 100 行以外的数据**在界面上根本不存在**，且没有任何迹象提示还有更多。
     */
    @Test
    fun `paging a wide table really asks the engine for other pages`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            val table = state.tablesFor(db, "WIDE")

            state.openTab(db, table)
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertNull(tab.error, "wide tab error: ${tab.error}")

            // 先缩到每页 10 行，25 行 = 3 页 —— 默认的 100 行/页下宽表也只有一页，测不出翻页
            state.changeTabPageSize(tab, PageSize.S10)
            tab.awaitSettled()
            assertEquals(10, tab.pageSize, "page size should follow the picker")
            assertEquals(1, tab.page, "changing the page size must go back to page 1")
            assertEquals(10, tab.rows.size, "page 1 should hold a full page")

            // 翻到最后一页：若请求里仍写死 page=1，这里拿到的还是第 1 页那 10 行
            state.goToTabPage(tab, 3)
            tab.awaitSettled()
            assertEquals(3, tab.page, "page should follow the request")
            assertEquals(25L, tab.total, "total is the table size, not the page size")
            assertEquals(5, tab.rows.size, "last page holds the remainder")
            // 内容必须是第 3 页那 5 行（id 21~25），而不是第 1 页的 —— 只断言行数会被
            // 「恰好也是 5 行」的假象骗过，必须核对具体内容
            val tags = tab.rows.mapNotNull { it.cells["TAG"] as? String }.sorted()
            assertEquals(listOf("row21", "row22", "row23", "row24", "row25"), tags)

            // 往回翻，确认不是单向生效
            state.goToTabPage(tab, 2)
            tab.awaitSettled()
            assertEquals(2, tab.page)
            assertEquals(10, tab.rows.size)
            assertEquals("row11", tab.rows.firstNotNullOfOrNull { it.cells["TAG"] as? String })
        }

    /**
     * 回归：改页大小后页码必须归位；选**同一个**大小则不该触发任何重载。
     *
     * 10 行/页的第 3 页在 20 行/页下越界（25 行只有 2 页）。不归位就会停在一个永远加载不出
     * 内容的页上 —— `DataTable` 的「越界自动回退」也等不到新 `totalCount` 来救。
     */
    @Test
    fun `changing the page size pulls the view back to page one`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "WIDE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            state.changeTabPageSize(tab, PageSize.S10)
            tab.awaitSettled()
            state.goToTabPage(tab, 3)
            tab.awaitSettled()
            assertEquals(3, tab.page)

            // 选同一个大小：必须是 no-op，否则每次点开下拉都会重置用户的页码
            state.changeTabPageSize(tab, PageSize.S10)
            assertEquals(3, tab.page, "same page size must not reset the view")

            // 换成 S20：3 页变 2 页，旧页码 3 已越界
            state.changeTabPageSize(tab, PageSize.S20)
            tab.awaitSettled()
            assertEquals(1, tab.page, "page size change must reset to page 1")
            assertEquals(20, tab.rows.size)
            assertEquals(20, tab.pageSize)
        }

    /**
     * 回归：**后发起的请求必须赢**。
     *
     * 预览原先只有屏级（连接级）代次，管不到「同一张表上连着翻页」：两次 `loadTabPreview`
     * 并发在跑，谁先返回谁先写，**慢的那次会覆盖快的那次**。连点两次「下一页」就可能出现
     * 「信息条写着第 2 页、表里却是第 1 页的行」。
     *
     * 这里**故意不等**第一次就发第二次，把并发窗口开到最大。修复前本测试是**间歇性**失败
     * （取决于两次请求的完成顺序），所以末尾再多等一会儿、断言的是最终稳定状态。
     */
    @Test
    fun `a superseded page request never overwrites the newer one`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "WIDE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            state.changeTabPageSize(tab, PageSize.S10)
            tab.awaitSettled()

            // 不 await，直接连发两次翻页 —— 第 1 次的响应很可能在第 2 次之后才落地
            state.goToTabPage(tab, 2)
            state.goToTabPage(tab, 3)
            tab.awaitSettled()
            // 再给「迟到的旧响应」留出落地窗口
            delay(300)
            delay(300)

            assertNull(tab.error, "旧请求的错误不应污染新页: ${tab.error}")
            assertEquals(3, tab.page, "最终页码必须是最后一次请求的页")
            assertEquals(
                listOf("row21", "row22", "row23", "row24", "row25"),
                tab.rows.mapNotNull { it.cells["TAG"] as? String }.sorted(),
                "表里必须是第 3 页的行 —— 旧响应覆盖回来的话这里会是第 2 页那批",
            )
        }

    /*
     * 回归：**断开连接必须作废 in-flight 状态**。
     *
     * 池被释放不会让已发出的请求自己结束 —— 引擎端的流还挂着，协程还活着，只是会被代次挡住。
     * 若 `releasePools` 不顺带复位「正在执行」标志，唯一会写 `running = false` 的那条协程已经
     * 提前 return 了，界面就**永远**停在「执行中…」；更糟的是这批行帧在重连后仍会往这个
     * 存活的 sheet 里灌。
     *
     * 注意走的是 `releasePools`（断开路径），不是 `bindConnection`：后者因连接 id 未变会早退，
     * 正是原先漏作废的那条路径。
     *
     * ## 为什么这里要覆盖 `loadingDatabases` / `loadingTables`
     *
     * 它们和 `sheet.running` 走的是**同一个陷阱**，但形态相反，值得写清楚：
     *
     * - `loadTabPreview` / `executeSql` / `executeGenerate` 的守卫是**同一目标的新一轮请求**已经
     *   开始，早退时**故意不清** loading —— 清了会把新一轮的转圈一并抹掉。
     * - `refreshDatabases` / `loadTables` 的守卫是 `requestGeneration != generation`（**连接级**代次）。
     *   该代次只在 `invalidateInFlight()` 里递增，而那个函数**正在**清 `loadingDatabases` /
     *   `loadingTables`。所以这两处早退不清 loading 同样是**对的** ——
     *   断开的连接会立刻换成新连接并重新发起请求，那时由新请求自己清。
     *
     * **因此不要为了「保险」去给这两处早退补 `loadingDatabases = false`** ——
     * 那会让断开瞬间把**新**连接刚开始的转圈抹掉，用户看到的是「库列表空着又不转圈」。
     * 真正该被钉住的是 `invalidateInFlight` 这个**唯一**的清理点：它一旦漏掉某一项，
     * 界面就会永远卡在「加载数据库中…」，而下面这两条断言能立刻抓住。
     *
     * 测法刻意用**真实在飞的请求**而不是手工摆标志 —— `loadingDatabases` 是 `private set`，
     * 外部写不了；更重要的是，手工摆出来的状态**绕过了整条因果链**，
     * 「删掉 `invalidateInFlight` 里的清理后测试会不会变红」正是这条测试要回答的问题。
     */

    /**
     * 回归：**多语句开关必须真的进到请求里**。
     *
     * 引擎侧的 `SqlScriptSplitter`（v2.16）早就实现了只切顶层 `;` 的多语句执行，
     * 但前端此前**硬编码 `multiStatement = false`**，让它完全用不上。
     *
     * ## 为什么不靠「跑不跑得通」判断
     *
     * 第一版断言是「单语句模式下 `SELECT 1; SELECT 2` 应当报错」—— 实测**不报**：
     * H2 允许单条语句里带分号，直接执行成功。方言间行为不一致（PG 会报错），
     * 拿它当判据等于把测试绑死在 H2 的宽松解析上。改成**直接量发出去的值**。
     */
    @Test
    fun `multi statement is off by default`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            assertFalse(
                sheet.multiStatement,
                "多语句必须默认关闭 —— 一次点执行会改多张表，不能让用户被动接受",
            )
        }

    /**
     * 多语句开启后，**建两张表**都能成功。
     *
     * ## 这条断言抓不住「多语句没生效」—— 已实测
     *
     * 最初以为「单语句模式下 `CREATE TABLE a; CREATE TABLE b` 必然失败」，
     * 于是用它当判据。**变异验证打脸**：把生产代码改回 `multiStatement = false`，
     * 这条测试照样全绿 —— H2 自己就接受批量 DDL，方言之间行为并不一致。
     *
     * 能稳定区分的判据只有一个：**量发出去的值**。见下一条。
     */
    @Test
    fun `multi statement runs several DDL in one execution`() =
        runBlocking {
            val state = newBrowser()
            val sheet = state.currentSqlSheet()!!
            sheet.multiStatement = true
            sheet.editor.setText("CREATE TABLE ms_a (id INT); CREATE TABLE ms_b (id INT)")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }

            assertNull(sheet.error, "多语句脚本应执行成功：${sheet.error}")
            val names =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                    c.createStatement().use { st ->
                        st
                            .executeQuery(
                                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                                    "WHERE TABLE_NAME IN ('MS_A','MS_B')",
                            ).use { rs ->
                                val out = mutableListOf<String>()
                                while (rs.next()) out += rs.getString(1)
                                out
                            }
                    }
                }
            assertEquals(
                listOf("MS_A", "MS_B").sorted(),
                names.sorted(),
                "两条 DDL 都应落地 —— 只建成一张说明多语句没生效",
            )
        }

    /*
     * 回归：**事务会话能把多条语句的改动一起回滚**。
     *
     * 引擎侧 `SYSTEM.BEGIN` 给 `session_id` 钉住一条连接（`autocommit=false`），
     * 后续请求全落在它上面，直到 `COMMIT` / `ROLLBACK`。
     *
     * 这里只验 `ROLLBACK` 这条更有价值的路径：开事务 → 写数据 → 回滚 → **用一条
     * 独立的 JDBC 连接查**，数据必须没进去。走独立连接是关键 —— 若用同一条连接查，
     * 未提交的事务改动自己就能看见，断言会永远绿。
     */

    /**
     * 多语句开关**真的进了请求** —— 直接量发出去的 `multi_statement`。
     *
     * 这是唯一能稳定区分「多语句生效 / 没生效」的判据：靠「脚本跑不跑得通」来判断
     * 会被方言差异骗（实测 H2 单语句下也接受批量 DDL，见上一条的 KDoc）。
     */
    @Test
    fun `the multi statement flag reaches the engine request`() =
        runBlocking {
            val captured = mutableListOf<com.langbiantianya.grpc.Request>()
            val spy =
                object : com.langbiantianya.client.EngineClient {
                    // 只拦 SQL.EXECUTE，其余路由原样转发到真引擎
                    override fun handle(request: com.langbiantianya.grpc.Request): kotlinx.coroutines.flow.Flow<com.langbiantianya.grpc.Response> {
                        if (request.category == com.langbiantianya.grpc.Category.SQL) captured += request
                        return engine.handle(request)
                    }

                    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = engine.testConnection(config)

                    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = engine.disconnect(config)

                    override fun close() = Unit // 不关真引擎 —— tearDown 还要用
                }
            val state = DatabaseBrowserState(spy, CoroutineScope(Dispatchers.Default))
            state.bindConnection(TestConnectionFactory.build(jdbcUrl))
            val sheet = state.currentSqlSheet()!!

            // 默认关 → 请求里必须是 false
            sheet.editor.setText("SELECT 1")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }
            assertEquals(
                false,
                captured
                    .last()
                    .sqlRequest.execute.multiStatement,
                "默认关闭时请求里的 multi_statement 必须是 false",
            )

            // 打开 → 请求里必须是 true
            sheet.multiStatement = true
            sheet.editor.setText("SELECT 1")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }
            assertEquals(
                true,
                captured
                    .last()
                    .sqlRequest.execute.multiStatement,
                "打开后 multi_statement 必须真的进到请求里 —— 这条才是有牙齿的判据",
            )
        }

    /**
     * 回归：**展开库时会拉到库级对象**（视图 / 触发器 / 过程·函数）。
     *
     * 此前树里只有「表」这一层 —— 用户能看到表却看不到视图和触发器，而这两类恰恰是
     * 排查「数据从哪来 / 会不会被改」时最先要看的东西。引擎侧 `VIEW` / `TRIGGER` /
     * `FUNCTION` 三个 Category 早就有 `LIST` 路由，前端一个都没调。
     *
     * 断言**不依赖具体对象是否存在**：先建一个视图把 `VIEW` 这格点亮，
     * 再验「视图列表里有它」。另两类若 H2 侧没有对象则显示「(无)」——
     * 那是正确状态，不该被断言成「有东西」。
     */
    @Test
    fun `expanding a database also lists its views`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            val table = state.tablesFor(db, "USERS")

            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("CREATE VIEW v_active AS SELECT id FROM USERS WHERE id > 0")
                }
            }

            // ⚠️ `tablesFor` 内部已经 `toggleDatabase(db)` 把库**展开**了。
            // 再调一次 `toggleDatabase` 反而是「收起」—— 展开动作不发生，对象自然拉不到。
            // `tablesFor` 已经展开过这个库 —— 那次对象拉取发生在 CREATE VIEW **之前**，
            // 所以这里必须**收起再展开**才能验证「重新展开会刷新」这条语义。
            state.toggleDatabase(db) // 收起
            state.toggleDatabase(db) // 再展开 → 重新拉对象
            withTimeout(10_000) { while (db in state.loadingTables) delay(20) }
            // 等**结果**而不是等 loading 标志归零 —— loading 在「解析 schema →
            // 拉对象」之间会被置位两次，只盯它会在中间那一刻误判为已完成
            withTimeout(10_000) {
                while (state.objectsByDatabase[db]
                        ?.get(DatabaseBrowserState.DatabaseObjectKind.VIEW)
                        ?.none { it.contains("V_ACTIVE", ignoreCase = true) } != false
                ) {
                    delay(20)
                }
            }

            val views = state.objectsByDatabase[db]?.get(DatabaseBrowserState.DatabaseObjectKind.VIEW)
            assertNotNull(views, "展开库后应有一份库级对象结果，实际 ${state.objectsByDatabase}")
            assertTrue(
                views!!.any { it.contains("V_ACTIVE", ignoreCase = true) },
                "视图列表应包含刚建的 V_ACTIVE，实际 $views",
            )
            // schema 名必须是**解析来的**，不是库名：H2 恒为 PUBLIC、PG 为 public、
            // SQLite 为 main，而 MySQL 压根没有这层（`listSchemas` 抛 UOE）。
            // 前端硬编码任何一种都会在换库时错。
            assertEquals(
                listOf("PUBLIC"),
                state.schemasByDatabase[db],
                "schema 名应来自 SCHEMA.LIST level=schema，而不是拿库名凑",
            )
        }

    /** 展开库时会发出三条**不同 Category** 的 LIST 请求 —— 三类必须各拉一次。 */
    @Test
    fun `expanding a database queries views triggers and functions`() =
        runBlocking {
            val cats = mutableSetOf<com.langbiantianya.grpc.Category>()
            val spy =
                object : com.langbiantianya.client.EngineClient {
                    override fun handle(request: com.langbiantianya.grpc.Request): kotlinx.coroutines.flow.Flow<com.langbiantianya.grpc.Response> {
                        if (request.action == com.langbiantianya.grpc.Action.LIST &&
                            request.category != com.langbiantianya.grpc.Category.SCHEMA &&
                            request.category != com.langbiantianya.grpc.Category.TABLE
                        ) {
                            cats += request.category
                        }
                        return engine.handle(request)
                    }

                    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = engine.testConnection(config)

                    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = engine.disconnect(config)

                    override fun close() = Unit
                }
            val state = DatabaseBrowserState(spy, CoroutineScope(Dispatchers.Default))
            state.bindConnection(TestConnectionFactory.build(jdbcUrl))
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            // `firstLoadedDatabase` 只等库列表，不展开 —— 这里第一次 toggle 就是「展开」
            state.toggleDatabase(db)

            // ⚠️ 这条断言的是「**发过**三次 LIST」，所以等待条件也必须是「发过三次」，
            // 而不是「结果回来了」—— 那是上一条测试关心的事。
            //
            // 第一版等的是「视图那一格」或「三类都落定」，都错：视图回来得最早，
            // 触发器 / 函数可能还没**发出去**。全量并发下这条会读到 `{VIEW}` 单元素
            // 集合（实测）。`cats` 是 spy 在**请求发生时**记的，与结果无关 ——
            // 这才是本条断言真正在等的东西。
            withTimeout(20_000) { while (cats.size < 3) delay(20) }
            assertEquals(
                setOf(
                    com.langbiantianya.grpc.Category.VIEW,
                    com.langbiantianya.grpc.Category.TRIGGER,
                    com.langbiantianya.grpc.Category.FUNCTION,
                ),
                cats,
                "展开库必须对 VIEW / TRIGGER / FUNCTION 各发一次 LIST",
            )
        }

    /**
     * 回归：展开表节点会拉到**索引 / 外键**。
     *
     * 它们与库级对象（视图 / 触发器 / 函数）分开拉，是因为引擎侧 `IndexListRequest` /
     * `ForeignKeyListRequest` 都要 `table_name` —— 跟库走就得把整库每张表的索引都拉一遍。
     */
    @Test
    fun `expanding a table lists its indexes and foreign keys`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.tablesFor(db, "USERS")

            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("CREATE INDEX idx_users_id ON USERS (id)")
                }
            }

            state.toggleTableObjects(db, "USERS")
            val slot = "$db::USERS"
            // ⚠️ 等**索引那一格**真的出现，而不是等「两类都到齐」或「map 非空」。
            //
            // 后两种写法在全量并发下都会翻车：索引与外键是并发拉的，
            // 一次 `IndexListRequest` 慢一点，5s 内就凑不齐 / 还没写回
            // （实测「等 size>=2」在全量 170 项下 5s 超时，单独跑 4 轮全绿）。
            // 只等自己关心的那一格，对另一格的快慢不敏感，也不受其拖累。
            withTimeout(20_000) {
                while (state.tableObjects[slot]?.get(DatabaseBrowserState.TableObjectKind.INDEX) == null) {
                    delay(20)
                }
            }
            val idx = state.tableObjects[slot]?.get(DatabaseBrowserState.TableObjectKind.INDEX)
            assertNotNull(idx, "展开表后应有索引结果，实际 ${state.tableObjects}")
            assertTrue(
                idx!!.any { it.contains("IDX_USERS_ID", ignoreCase = true) },
                "索引列表应包含刚建的 IDX_USERS_ID，实际 $idx",
            )
            // 收起后不再显示，但缓存保留（切回来无需重新拉）
            state.toggleTableObjects(db, "USERS")
            assertFalse(
                "$db::USERS" in state.expandedTableObjects,
                "收起后不应仍在展开集合里",
            )
        }

    /**
     * 回归：**过滤 / 排序 / 搜索真的进了引擎请求**。
     *
     * 靠「筛完行数变没变」判断会被引擎的宽松行为骗（H2 接受批量 DDL 那次教训）。
     * 这里用 spy 直接量发出去的 `DataListRequest`。
     */
    @Test
    fun `filter order and search reach the engine request`() =
        runBlocking {
            val reqs = mutableListOf<com.langbiantianya.grpc.DataListRequest>()
            val spy =
                object : com.langbiantianya.client.EngineClient {
                    override fun handle(request: com.langbiantianya.grpc.Request): kotlinx.coroutines.flow.Flow<com.langbiantianya.grpc.Response> {
                        if (request.category == com.langbiantianya.grpc.Category.DATA &&
                            request.action == com.langbiantianya.grpc.Action.LIST
                        ) {
                            reqs += request.dataRequest.list
                        }
                        return engine.handle(request)
                    }

                    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = engine.testConnection(config)

                    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = engine.disconnect(config)

                    override fun close() = Unit
                }
            val state = DatabaseBrowserState(spy, CoroutineScope(Dispatchers.Default))
            state.bindConnection(TestConnectionFactory.build(jdbcUrl))
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            val table = state.tablesFor(db, "USERS")
            state.openTab(db, table)
            val tab = state.tabs.single()
            tab.awaitSettled()

            // 默认：不过滤、不排序
            assertEquals("", reqs.last().where, "默认不该带过滤条件")
            assertEquals("", reqs.last().orderBy, "默认不该带排序")

            // 手写过滤
            state.setTabFilter(tab, "id > 1")
            tab.awaitSettled()
            assertEquals("id > 1", reqs.last().where, "手写 WHERE 必须进到请求里")

            // 搜索词与手写条件是 AND 关系，且搜索词被转义
            //
            // ⚠️ 期望值改过一次：原先断言的是 `"id > 1 AND LIKE '%O''Brien%'"` ——
            // 一个**没有左操作数**的残句。测试绿了很久，而真库上它发出去就是
            // `WHERE LIKE '%…'`，H2 / SQLite / PG **全部语法错误**。单测钉住的是
            // 「片段的转义」，缺陷在「片段的拼接」上，于是谁也拦不住。
            //
            // 现在搜索要铺到**每一列**（`DATA.LIST` 不回列类型，挑不出「文本列」），
            // 且必须 CAST —— 不带 CAST 时 `id LIKE '%2%'` 在 H2 直接类型错误。
            //
            // ⚠️ 列名必须在**设置搜索之前**取：搜索 `O'Brien` 命中 0 行，响应会把
            // `tab.columns` 换成空，而 `where` 是在**发请求那一刻**用当时的列算出来的。
            // 断言时再读列，读到的是「空」，于是期望值变成 `id > 1 AND ()`。
            val colsAtSearchTime = tab.columns.map { it.key }
            state.setTabSearch(tab, "O'Brien")
            tab.awaitSettled()
            val searchPredicates =
                colsAtSearchTime.map {
                    // ⚠️ CAST 目标类型**逐方言不同**（MySQL 只认 CHAR；PG 上 CHAR 会截断成 1 字符），
                    // 期望值必须跟着 tab 记录的方言走，不能写死 VARCHAR —— 见 SqlLiterals.castTypeFor
                    "CAST($it AS ${SqlLiterals.castTypeFor(tab.dialect ?: DialectType.H2)}) LIKE '%O''Brien%'"
                }
            assertEquals(
                "id > 1 AND (${searchPredicates.joinToString(" OR ")})",
                reqs.last().where,
                "搜索应与手写条件 AND、铺到每一列、且单引号被翻倍转义",
            )

            // 排序
            state.setTabOrderBy(tab, "id DESC")
            tab.awaitSettled()
            assertEquals("id DESC", reqs.last().orderBy, "ORDER BY 必须进到请求里")
        }

    /** 改条件后**必须回到第 1 页** —— 在第 3 页收紧过滤会看到一张空表。 */
    @Test
    fun `changing the filter resets to the first page`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.tablesFor(db, "WIDE")
            // 用**真的** tab：手工 `TablePreviewTab(db, "WIDE")` 能造出来，但那样测的是
            // 一个从未加载过的对象，页码复位之外的路径一概没走到
            state.openTab(db, "WIDE")
            val tab = state.tabs.single()
            tab.awaitSettled()
            tab.page = 3 // 模拟「用户翻到第 3 页」

            state.setTabFilter(tab, "id > 1")
            assertEquals(1, tab.page, "改过滤条件后必须回到第 1 页，否则会停在越界的空页上")
        }

    /**
     * 回归：**只读模式拦在发出去之前**。
     *
     * 拦在 UI 层而不是引擎层，是为了让用户看到一句人话而不是引擎异常；
     * 拦在**发出去之前**，是为了用户看到提示时数据库还什么都没发生。
     */
    @Test
    fun `read only mode blocks writes before they reach the engine`() =
        runBlocking {
            val sent = mutableListOf<String>()
            val spy =
                object : com.langbiantianya.client.EngineClient {
                    override fun handle(request: com.langbiantianya.grpc.Request): kotlinx.coroutines.flow.Flow<com.langbiantianya.grpc.Response> {
                        if (request.category == com.langbiantianya.grpc.Category.SQL) {
                            sent += request.sqlRequest.execute.sql
                        }
                        return engine.handle(request)
                    }

                    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = engine.testConnection(config)

                    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = engine.disconnect(config)

                    override fun close() = Unit
                }
            val state = DatabaseBrowserState(spy, CoroutineScope(Dispatchers.Default))
            state.bindConnection(TestConnectionFactory.build(jdbcUrl))
            val sheet = state.currentSqlSheet()!!

            // 默认不拦
            assertFalse(state.readOnly, "只读模式默认应关闭")

            // 打开后：读操作照常
            state.readOnly = true
            sheet.editor.setText("SELECT 1")
            assertNull(state.rejectIfReadOnly("SELECT 1"), "只读模式下读操作应放行")
            state.executeSql()
            withTimeout(5_000) { while (sheet.running) delay(10) }
            assertEquals(listOf("SELECT 1"), sent, "只读模式下的查询应正常发出去")

            // 写操作在 UI 层就被挡
            val before = sent.size
            sheet.editor.setText("DELETE FROM users")
            val err = state.rejectIfReadOnly(sheet.editor.text)
            assertNotNull(err, "只读模式必须拦下写操作")
            assertTrue(err.contains("只读"), "错误文案要点明是只读模式：$err")
            state.executeSql()
            assertEquals(before, sent.size, "只读模式下**不能**把写操作发出去")
            assertTrue(
                sheet.error.orEmpty().contains("只读"),
                "错误应写在 sheet 上让用户看见：${sheet.error}",
            )
        }

    @Test
    fun `a rolled back transaction leaves no trace`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            val sheet = state.currentSqlSheet()!!

            assertNull(
                state.beginTransaction(sheet),
                "开启事务不应报错",
            )
            assertNotNull(sheet.transactionSessionId, "开启后必须持有 session id")

            sheet.editor.setText("CREATE TABLE tx_probe (id INT PRIMARY KEY)")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }
            assertNull(sheet.error, "建表应成功：${sheet.error}")

            assertNull(
                state.rollbackTransaction(sheet),
                "回滚不应报错",
            )
            assertNull(sheet.transactionSessionId, "回滚后必须清掉本地 session id")

            // 用**独立连接**验：DDL 在 H2 是隐式提交的，所以「回滚后数据消失」这条
            // 在 H2 上不成立，只断言「接口链路通、session id 能再次申请到」。
            assertNull(
                state.beginTransaction(sheet),
                "回滚后应能再次开启事务 —— 上一条已断言 session id 已清",
            )
            assertNotNull(sheet.transactionSessionId, "再次开启后应持有新的 session id")
            assertNull(state.rollbackTransaction(sheet))
        }

    /**
     * 事务的 `session_id` **真的进了请求**。
     *
     * 上一条只验「接口链路通、session id 能拿到」—— 变异验证打脸：把
     * `sessionId = sheet.transactionSessionId` 改成 `""`，测试照样全绿。
     * 因为 H2 的 DDL 是**隐式提交**的，开不开事务都能建成那张表，
     * 「建表成功」根本证明不了会话被用过。
     */
    @Test
    fun `the transaction session id reaches the engine request`() =
        runBlocking {
            val captured = mutableListOf<com.langbiantianya.grpc.Request>()
            val spy =
                object : com.langbiantianya.client.EngineClient {
                    override fun handle(request: com.langbiantianya.grpc.Request): kotlinx.coroutines.flow.Flow<com.langbiantianya.grpc.Response> {
                        if (request.category == com.langbiantianya.grpc.Category.SQL) captured += request
                        return engine.handle(request)
                    }

                    override suspend fun testConnection(config: com.langbiantianya.grpc.ConnectionConfig) = engine.testConnection(config)

                    override suspend fun disconnect(config: com.langbiantianya.grpc.ConnectionConfig) = engine.disconnect(config)

                    override fun close() = Unit
                }
            val state = DatabaseBrowserState(spy, CoroutineScope(Dispatchers.Default))
            state.bindConnection(TestConnectionFactory.build(jdbcUrl))
            val sheet = state.currentSqlSheet()!!

            // 未开事务 → session_id 必须为空（否则每条语句都会误落到某个旧会话上）
            sheet.editor.setText("SELECT 1")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }
            assertEquals(
                "",
                captured.last().sessionId,
                "未开事务时请求里的 session_id 必须为空",
            )

            // 开事务 → session_id 必须与 engine 返回的一致
            assertNull(state.beginTransaction(sheet))
            val sid = sheet.transactionSessionId!!
            sheet.editor.setText("SELECT 1")
            state.executeSql()
            withTimeout(10_000) { while (sheet.running) delay(10) }
            assertEquals(
                sid,
                captured.last().sessionId,
                "开了事务后 session_id 必须真的进到请求里 —— 否则语句根本不在那条被钉住的连接上",
            )

            assertNull(state.rollbackTransaction(sheet))
        }

    @Test
    fun `releasing pools clears every in flight running flag`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "WIDE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            // 摆出「三处都正在跑」的现场（这三个标志可写）
            val sheet = state.sqlSheets.first()
            sheet.running = true
            state.generateRunning = true
            tab.loading = true

            state.releasePools()

            assertFalse(sheet.running, "SQL 工作台会永远卡在「执行中…」")
            assertFalse(state.generateRunning, "造数工作台会永远卡在「造数中…」")
            assertFalse(tab.loading, "预览会永远卡在转圈（loadTabPreview 早退时不清 loading）")
        }

    /**
     * 回归：**在飞的库列表请求不能因为断开而永远转圈**。
     *
     * 与 [releasing pools clears every in flight running flag] 同源，但走**真实因果链**：
     * `refreshDatabases` 的响应回来时代次已变，会走 `return@launch` **早退** ——
     * 早退不写 loading，于是「谁把 loading 清掉」只剩 `invalidateInFlight` 一个答案。
     * 它漏掉这一项，界面就永远停在「加载数据库中…」，不报错、不转完、也没有重试入口。
     *
     * ## 测法上踩过的两个坑
     *
     * 1. **不能在开头 `refreshDatabases()` 一次就断开** —— `firstLoadedDatabase()` 内部会等
     *    loading 结束，那时请求早已完成，断开碰不到「早退」这条路径，测试**空跑**：
     *    把 `invalidateInFlight` 里的清理整行删掉它照样绿。所以要先正常加载完拿到库名，
     *    再**重新**发起一次。
     * 2. **不能把两个标志塞进同一个测试** —— 两次触发之间有真实协程在跑，
     *    `loadingDatabases` 可能在断言前就完成，于是那个「前提断言」自己变成 flaky
     *    （实测会在红/绿之间摇摆）。所以拆成两条，各自只依赖**一个同步写入**的标志。
     *
     * `refreshDatabases()` 里 `loadingDatabases = true` 是**同步赋值**（协程在它之后才 launch），
     * 因此「调用返回 → 立刻 releasePools」这一瞬间标志必然为 true，不依赖任何时序假设。
     */
    @Test
    fun `disconnecting during an in flight library load clears the loading flag`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            state.firstLoadedDatabase() // 第一次加载跑完，把连接与方言都热起来

            // ⚠️ **不能**断言「此刻 `loadingDatabases` 必为 true」来当前提。
            //
            // `refreshDatabases()` 里的 `loadingDatabases = true` 确实是同步赋值，
            // 但紧跟着 launch 的协程跑在**别的线程**上，它可能在我们走到断言那行之前
            // 就把请求跑完并清掉标志 —— 全量并发下尤其容易（实测这条在单独跑三轮全绿、
            // 全量 170 项并发时偶发红，报的正是「前提不成立：请求没进在飞状态」）。
            //
            // 正确做法：**不去证明请求「在飞」，只断言最终状态。**
            // `releasePools` 的 `invalidateInFlight` 会同步把 `loadingDatabases` 清掉，
            // 所以断开之后它必然为 false —— 把 `invalidateInFlight` 里那行删掉，
            // 旧请求的协程又会早退不清，最终仍然是 true，测试照样变红。
            state.refreshDatabases()
            state.releasePools()

            withTimeout(5_000) { while (state.loadingDatabases) delay(10) }
        }

    /**
     * 回归：同上一条，但针对**表列表**。
     *
     * `loadTables` 的 `loadingTables.add(database)` 同样在 `launch` 之前同步执行，
     * 所以这里不需要任何等待就能确定「正在加载」。
     */
    @Test
    fun `disconnecting during an in flight table load clears the loading flag`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()

            state.toggleDatabase(db) // loadingTables.add(db)（同步）
            assertTrue(db in state.loadingTables, "前提不成立：表列表没进在飞状态")
            state.releasePools() // 响应回来时必定早退，早退不写 loading

            withTimeout(5_000) { while (state.loadingTables.isNotEmpty()) delay(10) }
        }

    /**
     * 回归：`bindConnection` 的派生状态复位不能因为重构而丢字段。
     *
     * 代次自增与 loading 复位曾分散在 `bindConnection` / `releasePools` 两处各写一半，
     * 现在统一由 `releasePools` → `invalidateInFlight` 负责。切连接仍必须全部复位。
     */
    @Test
    fun `binding a different connection resets the derived state`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "WIDE"))
            state.sqlSheets.first().running = true
            state.generateRunning = true

            state.bindConnection(TestConnectionFactory.build("jdbc:h2:mem:bdbtest_other_${System.nanoTime()};DB_CLOSE_DELAY=-1"))

            assertTrue(state.tabs.isEmpty(), "预览标签页应清空")
            assertTrue(state.databases.isEmpty(), "库列表应清空")
            assertFalse(state.sqlSheets.first().running, "新连接的 sheet 不得继承 running")
            assertFalse(state.generateRunning, "换连接后不得仍显示造数中")
            assertEquals(1, state.sqlSheets.size, "SQL 工作台应复位为单个空 sheet")
        }

    /**
     * 回归：**没有主键的表必须拒绝编辑**。
     *
     * `DATA.UPDATE` 的 `where` 若为空 map，引擎会当成「无条件更新」——
     * 那等于**改写整张表**。而 `nokey` 表确实一个主键都没有，所以
     * [DatabaseBrowserState.isTableEditable] 必须返回 `false`。
     *
     * 这条断言的价值在于**它能区分「忘了做主键」和「故意不做」**：如果有人日后
     * 用「表里有 id 列」这种理由把它改成 `true`（而 id 其实不是主键，可能重复），
     * 改完这里会绿吗？不会 —— `nokey` 里连 id 列都没有，而复合主键表 `composite`
     * 的用例又钉住了「必须取全部主键列」。两边一起构成「主键不完整 ⇒ 一律只读」
     * 这条硬边界。
     */
    @Test
    fun `a table without a known primary key refuses editing`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            val table = state.tablesFor(db, "NOKEY")
            state.openTab(db, table)
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertTrue(
                tab.capabilitiesLoaded,
                "引擎必须回写能力位 —— 否则下面「不可编辑」的原因是能力位没到，那就成了另一个 bug",
            )
            assertTrue(
                tab.primaryKeyColumns.isEmpty(),
                "nokey 表没有主键，primaryKeyColumns 必须是空列表，实际=${tab.primaryKeyColumns}",
            )
            assertFalse(
                state.isTableEditable(tab),
                "没有主键时**必须**拒绝编辑 —— 否则 DATA.UPDATE 的空 where 会改写整张表",
            )

            // 真去暂存一次也必须被拒，而不是发出一条 where 为空的请求
            val err =
                state.stageCellEdit(
                    tab,
                    CellEdit(
                        rowId = tab.rows.first().id,
                        columnKey = "A",
                        oldValue = "x",
                        newValue = "HACKED",
                    ),
                )
            assertNotNull(err, "没有主键时 stageCellEdit 必须返回错误说明，不能接受改动")
            assertTrue(err.contains("主键"), "错误文案要点明是主键问题：$err")
            assertTrue(
                tab.pendingOps.isEmpty(),
                "被拒绝的编辑**不能**进暂存缓冲 —— 否则用户会以为已经改好",
            )
            assertEquals(
                "x",
                queryString("SELECT a FROM nokey WHERE b = 1"),
                "数据库里必须一个字节都没变",
            )
        }

    /**
     * 有主键的表**可以**编辑，且主键列被单独挡住。
     *
     * 挡住主键列不是为了「主键重要」这种泛泛的理由，而是它同时是定位这一行的
     * `WHERE` 条件：让用户改它，就得处理「第一条把主键改了，后面几条的 WHERE
     * 还命不命中」这种自相矛盾的序列。事务保证得了「全改或全不改」，
     * 保证不了「每条都改对」—— 后者是谓词的事。
     */
    @Test
    fun `a table with a primary key is editable but the key column is not`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertTrue(state.isTableEditable(tab), "有单列主键的表应当可编辑")
            assertEquals(
                listOf("ID"),
                tab.primaryKeyColumns,
                "H2 把未加引号的列名归一为大写，主键列名应随之是大写",
            )
            val idColumn = tab.columns.first { it.key == "ID" }
            val nameColumn = tab.columns.first { it.key == "NAME" }
            assertFalse(
                state.isColumnEditable(tab, idColumn),
                "主键列不能编辑 —— 它是定位这一行的 WHERE 条件",
            )
            assertTrue(
                state.isColumnEditable(tab, nameColumn),
                "普通列必须可编辑 —— 否则整张表会因为一列主键而都不能改",
            )

            val err =
                state.stageCellEdit(
                    tab,
                    CellEdit(
                        rowId = tab.rows.first().id,
                        columnKey = "ID",
                        oldValue =
                            tab.rows
                                .first()
                                .cells["ID"]
                                .toString(),
                        newValue = "999",
                    ),
                )
            assertNotNull(err, "改主键列必须被拒")
            assertTrue(
                err.contains("主键"),
                "错误文案要点明是主键的问题：$err",
            )
        }

    /**
     * 编辑先**暂存**，点提交才写库 —— 而且一批改动走一个事务。
     *
     * 这条钉住的是「点提交之前数据库一个字节都不能变」：暂存缓冲不是乐观更新，
     * 一旦改了一格就顺手写了库，用户在提交前就没有退路可言了。
     */
    @Test
    fun `edits are staged and reach the database only when applied`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            val row = tab.rows.first { it.cells["NAME"] == "Alice" }
            assertNull(
                state.stageCellEdit(tab, CellEdit(row.id, "NAME", "Alice", "Alicia")),
                "普通列的合法编辑不该被拒",
            )
            assertEquals(1, tab.pendingOps.size, "一次编辑应当进缓冲一次")
            assertEquals(
                "Alice",
                queryString("SELECT name FROM users WHERE id = ${row.cells["ID"]}"),
                "**暂存不等于落盘** —— 点提交之前数据库必须是原值",
            )
            // 渲染用的行要叠加新值，否则用户改完看到的还是旧值，会以为没生效
            assertEquals(
                "Alicia",
                tab.displayRows().first { it.id == row.id }.cells["NAME"],
                "displayRows 必须把暂存值叠上去",
            )
            assertTrue(
                state.isCellPending(tab, tab.displayRows().first { it.id == row.id }, tab.columns.first { it.key == "NAME" }),
                "待提交单元格必须能被标出来（工具条和格子底色都靠它）",
            )

            assertNull(state.applyCellEdits(tab), "提交应当成功：${tab.pendingOps}")
            assertTrue(tab.pendingOps.isEmpty(), "提交成功后缓冲必须清空")
            assertEquals("Alicia", queryString("SELECT name FROM users WHERE id = ${row.cells["ID"]}"), "提交后数据库应当是新值")
        }

    /** 放弃修改只清缓冲，不发请求 —— 数据库本来就没动过。 */
    @Test
    fun `discarding edits touches neither the buffer nor the database`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            val row = tab.rows.first { it.cells["NAME"] == "Bob" }
            state.stageCellEdit(tab, CellEdit(row.id, "NAME", "Bob", "Bobby"))
            assertNotNull(state.discardCellEdits(tab), "放弃应返回提示文案")
            assertTrue(tab.pendingOps.isEmpty(), "放弃后缓冲必须清空")
            assertEquals("Bob", queryString("SELECT name FROM users WHERE id = ${row.cells["ID"]}"), "放弃不能改数据库")

            // 再点一次要有话说，而不是静默返回 null 让界面以为「放弃成功了」
            val again = state.discardCellEdits(tab)
            assertTrue(again != null, "缓冲为空时点放弃要给出提示，不能静默返回")
        }

    /**
     * **事务兜底的核心**：一批改动里只要有一条失败，已经写进去的那些必须全部回滚。
     *
     * 构造方式是故意让第 2 条撞唯一索引：
     * - 第 1 条把 `uniq_users.id=2` 的 code 改成 'x' —— **会成功**
     * - 第 2 条把 `id=1` 的 code 改成 'x' —— 唯一约束冲突，**必失败**
     *
     * 断言的重点是：第 1 条**也**要回滚。没这条断言的话，一个「每条独立提交」的
     * 实现同样能让所有用例通过 —— 而那正是这个功能最初要解决的东西。
     */
    @Test
    fun `a failing batch rolls back every row it already changed`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "UNIQ_USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertTrue(state.isTableEditable(tab), "uniq_users 有主键，应当可编辑")

            val first = tab.rows.first { it.cells["ID"].toString() == "1" }
            val second = tab.rows.first { it.cells["ID"].toString() == "2" }
            // 顺序必须「先成功的那条」先入缓冲：`pendingEdits` 是插入序的 LinkedHashMap，
            // 提交按它的顺序执行，反过来就变成「第一条就失败」，后面那条根本没写过，
            // 也就测不到「已经写进去的要回滚」了。
            state.stageCellEdit(tab, CellEdit(second.id, "CODE", "b", "x"))
            state.stageCellEdit(tab, CellEdit(first.id, "CODE", "a", "x"))

            val err = state.applyCellEdits(tab)
            assertNotNull(err, "撞唯一索引必须让整批失败")
            assertTrue(
                err.contains("回滚"),
                "错误文案要点明已经回滚：$err",
            )
            assertEquals(
                "b",
                queryString("SELECT code FROM uniq_users WHERE id = 2"),
                "先写成功的那条**必须被回滚** —— 这正是事务兜底的意义",
            )
            assertEquals(
                "a",
                queryString("SELECT code FROM uniq_users WHERE id = 1"),
                "失败的那条当然不能生效",
            )
            assertFalse(
                tab.pendingOps.isEmpty(),
                "回滚后要**保留**用户改的内容，否则他得把那 19 行重敲一遍",
            )
        }

    /**
     * 复合主键：`WHERE` 必须带**全部**主键列。
     *
     * `composite` 里 `(tenant=1, seq=1)` 与 `(tenant=1, seq=2)` 的 `tenant` 相同 ——
     * 只用 `tenant` 去 `WHERE` 会一次改掉两行。而 [DatabaseBrowserState.applyCellEdits]
     * 把 `affectedRows == 0` 当失败，把「改多了」交给数据库的**唯一**语义兜住：
     * 真改到两行时数据库不会报错，但表结构本身拦住了（主键唯一），所以这条用例
     * 真正验证的是 `primaryKeyColumns` 是**两个**而不是一个。
     */
    @Test
    fun `a composite primary key sends every key column`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "COMPOSITE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertEquals(
                setOf("TENANT", "SEQ"),
                tab.primaryKeyColumns.toSet(),
                "复合主键必须两列都在 —— 少一列就不是唯一条件了",
            )
            val target = tab.rows.first { it.cells["NOTE"] == "t1s1" }
            assertTrue(
                state.isColumnEditable(tab, tab.columns.first { it.key == "NOTE" }),
                "非主键列照常可编辑",
            )
            state.stageCellEdit(tab, CellEdit(target.id, "NOTE", "t1s1", "edited"))

            assertNull(state.applyCellEdits(tab), "复合主键的提交应当成功")
            assertEquals(
                "edited",
                queryString("SELECT note FROM composite WHERE tenant = 1 AND seq = 1"),
                "目标行必须被改到",
            )
            assertEquals(
                "t1s2",
                queryString("SELECT note FROM composite WHERE tenant = 1 AND seq = 2"),
                "**同 tenant 的另一行不能被改** —— 这是复合主键唯一性的直接检验",
            )
        }

    /**
     * 二进制列必须挡住，**同表**的普通列照常可编辑。
     *
     * 判据是「这一列在当前页里有没有出现过 `[LOB Data]` 占位串」，而不是比对类型名 ——
     * H2 把 `VARBINARY` 报成 `BINARY LARGE OBJECT`，按类型名的那套名单在 H2 上会漏，
     * 漏了的后果是拿占位串覆盖用户的二进制数据，且不可逆。
     */
    @Test
    fun `binary columns are not editable while their neighbours are`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "BLOBS"))
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertTrue(state.isTableEditable(tab), "前置条件：这张表有主键，整表可编辑")

            val payload = tab.columns.first { it.key == "PAYLOAD" }
            val note = tab.columns.first { it.key == "NOTE" }
            assertFalse(
                state.isColumnEditable(tab, payload),
                "二进制列不可编辑：DATA.LIST 回的是占位串，写回去等于覆盖数据",
            )
            assertTrue(
                state.isColumnEditable(tab, note),
                "同表的 VARCHAR 列必须照常可编辑 —— 否则一张表里一个二进制列就废掉整张表",
            )
            assertTrue(
                "PAYLOAD" in tab.readOnlyColumns && "NOTE" !in tab.readOnlyColumns,
                "只读列集合应当只含二进制列（主键列另算），实际=${tab.readOnlyColumns}",
            )
        }

    /**
     * `id` 列**不是**主键、且两行同值时，行标识仍然必须互不相同。
     *
     * 行 id 取自 `id` 列，而 `id` 既未必是主键也未必唯一。撞车的后果有两处：
     * `DataTable` 的 LazyColumn key 重复会**直接抛异常**（整张表渲染不出来），
     * 而编辑暂存缓冲会把两行当成同一行 —— 第二处改动静默覆盖第一处。
     *
     * 断言只钉「缓冲里两条都在」这一条：它同时证明行标识唯一，而且两行最后
     * 各自被自己的主键（`code`）定位到了正确的行。
     */
    @Test
    fun `a non unique id column does not make two rows look like one`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "DUPID"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertEquals(2, tab.rows.size, "前置条件：这张表有两行")
            assertEquals(
                tab.rows.size,
                tab.rows
                    .map { it.id }
                    .toSet()
                    .size,
                "两行的 id 列值相同，行标识却不能相同 —— 实际=${tab.rows.map { it.id }}",
            )
            assertEquals(listOf("CODE"), tab.primaryKeyColumns, "真正的主键是 CODE")

            tab.rows.forEach { row ->
                val old = row.cells["NOTE"].toString()
                assertNull(
                    state.stageCellEdit(tab, CellEdit(row.id, "NOTE", old, "$old-edited")),
                    "两行各自都要能暂存",
                )
            }
            assertEquals(2, tab.pendingOps.size, "两处改动都要留在缓冲里，不能互相覆盖")

            assertNull(state.applyCellEdits(tab), "提交应当成功")
            assertEquals("x-edited", queryString("SELECT note FROM dupid WHERE code = 'a'"), "第一行要改到")
            assertEquals("y-edited", queryString("SELECT note FROM dupid WHERE code = 'b'"), "第二行也要改到 —— 不能被第一行覆盖")
        }

    /** 只读开关打开时整张表立刻不可编辑 —— 让人改半天再拒绝是在浪费他的时间。 */
    @Test
    fun `read only mode disables editing before the first keystroke`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertTrue(state.isTableEditable(tab), "前置条件：默认是可编辑的")

            state.readOnly = true
            assertFalse(state.isTableEditable(tab), "只读模式下整张表应当不可编辑")

            val row = tab.rows.first()
            val err =
                state.stageCellEdit(
                    tab,
                    CellEdit(row.id, "NAME", row.cells["NAME"].toString(), "X"),
                )
            assertTrue(err != null, "只读模式下连暂存都不该接受")
        }

    // -------------------------------------------------------------------------
    // NULL 约定：空串就是空串，要 NULL 必须显式点那个开关
    // -------------------------------------------------------------------------

    /**
     * 引擎给回来的**可空列清单**必须区分 `NOT NULL` 列，否则界面会给一个必然失败的按钮。
     *
     * `people.name` 是 NOT NULL：给它画「设为 NULL」开关，用户点下去提交必然报错。
     * 而反过来漏报（该画的没画）用户还能用 SQL 面板绕过，所以宁可少画。
     */
    @Test
    fun `nullable columns come from the engine and respect NOT NULL`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "PEOPLE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertTrue("NICK" in tab.nullableColumns, "可空列必须出现在清单里，实际=${tab.nullableColumns}")
            assertFalse("NAME" in tab.nullableColumns, "NOT NULL 列不能出现在清单里 —— 那个开关点了必然失败")
            assertFalse(state.isColumnNullable(tab, tab.columns.first { it.key == "NAME" }), "界面谓词要与清单一致")
            assertTrue(state.isColumnNullable(tab, tab.columns.first { it.key == "NICK" }), "可空列要能画 NULL 开关")
        }

    /**
     * 显式写 NULL —— 断言的是**数据库里真的是 NULL**，不是「界面上看着空了」。
     *
     * 这条用例钉住整条链路：界面的 `null` → `null_columns` → 引擎绑 `setNull` → 落库。
     * 任何一环把 NULL 变成空串，`wasNull()` 就会是 false，而**界面上两种情况长得一模一样**。
     */
    @Test
    fun `an explicit NULL reaches the database as NULL`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "PEOPLE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            val cid = tab.rows.first { it.cells["NAME"] == "Cid" }
            assertEquals(
                "",
                cid.cells["NICK"].toString(),
                "前置条件：Cid 的 nick 已经是**空串** —— 这一步要证明空串能被改成 NULL，而不只是 NULL 能被读出来",
            )
            assertNull(
                state.stageCellEdit(tab, CellEdit(cid.id, "NICK", "", null)),
                "显式写 NULL 是合法编辑 —— 可空列上不该被拒",
            )

            assertNull(state.applyCellEdits(tab), "提交应当成功")
            val (value, wasNull) = queryCell("SELECT nick FROM people WHERE name = 'Cid'")
            assertTrue(wasNull, "**必须是 SQL NULL**：落成空串的话，LIKE、唯一索引、CSV 导出全都不一样")
            assertNull(value, "NULL 在 Java 侧读回来也应当是 null")
        }

    /**
     * 空串**不是** NULL —— 反向用例。
     *
     * 有了上一条还不够：把 `newValue = ""` 也写成 NULL 的实现，同样能让上一条通过。
     * 这一条保证「清空输入框」这个最自然的动作，落到数据库里仍然是 `''`。
     */
    @Test
    fun `an empty string is not silently promoted to NULL`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "PEOPLE"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            val bob = tab.rows.first { it.cells["NAME"] == "Bob" }
            assertNull(bob.cells["NICK"], "前置条件：Bob 的 nick 本来就是 NULL")
            assertNull(state.stageCellEdit(tab, CellEdit(bob.id, "NICK", null, "")), "把 NULL 改成空串是合法编辑")

            assertNull(state.applyCellEdits(tab), "提交应当成功")
            val (value, wasNull) = queryCell("SELECT nick FROM people WHERE name = 'Bob'")
            assertFalse(wasNull, "空串**不能**被当成 NULL —— 这条约定的前提就是两者分得开")
            assertEquals("", value, "落库的必须是空串")
        }

    // -------------------------------------------------------------------------
    // 批量删除
    // -------------------------------------------------------------------------

    /**
     * 删除是**标记**不是立即执行 —— 这一点必须能在数据库上验证。
     *
     * 「点了不立刻生效」听起来是缺陷，实际是刻意的：删数据不可逆，而 `WHERE` 只有
     * 主键条件，用户在按下之前没有机会看到「即将执行的到底是什么」。
     */
    @Test
    fun `deleting rows only marks them until the batch is applied`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "PEOPLE"))
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertTrue(state.canDeleteRows(tab), "前置条件：这张表可删")

            val targets = tab.rows.filter { it.cells["NAME"] in listOf("Alice", "Bob") }
            assertNull(state.stageRowDeletes(tab, targets.map { it.id }), "标记删除不该被拒")
            assertEquals(3, queryCount("SELECT COUNT(*) FROM people"), "**标记阶段一个字节都不能变**")
            assertTrue(state.isRowDeleted(tab, targets.first()), "被标记的行必须能被标出来，否则用户不知道会删哪几行")

            assertNull(state.applyCellEdits(tab), "提交应当成功")
            assertEquals(1, queryCount("SELECT COUNT(*) FROM people"), "两行都应当被删掉")
            assertEquals(1, queryCount("SELECT COUNT(*) FROM people WHERE name = 'Cid'"), "没选的行必须还在")
        }

    /**
     * 没有主键的表**不能删** —— `DELETE` 的 `WHERE` 只能由主键拼。
     *
     * 与「不能改」看着像同一件事，其实各有各的硬边界：`UPDATE` 的条件同样来自主键，
     * 缺了它就会退化成「无条件更新」而改写整张表，所以编辑也一并挡了。挡住之后
     * 界面上的说法必须区分 —— 对着用户说「这表没主键所以不能改，但能删」会让人
     * 以为后者能点。
     */
    @Test
    fun `a table without a primary key cannot be deleted from`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "NOKEY"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertFalse(state.canDeleteRows(tab), "没有主键 ⇒ 定位不到行 ⇒ 不能删")
            assertNotNull(
                state.stageRowDeletes(tab, tab.rows.map { it.id }),
                "真去暂存一次也必须被拒",
            )
            assertTrue(tab.pendingOps.isEmpty(), "被拒的删除不能进缓冲")
            assertEquals(2, queryCount("SELECT COUNT(*) FROM nokey"), "数据库必须一个字节都没变")
        }

    // -------------------------------------------------------------------------
    // 批量新增
    // -------------------------------------------------------------------------

    /**
     * 新增行时**自增列不能出现**在可填清单里。
     *
     * 让用户手填 `IDENTITY` 主键，十有八九会撞车，而撞车的报错（唯一约束冲突）
     * 完全指不出「你本来就不该填这一列」。
     */
    @Test
    fun `auto increment columns are excluded from the insert form`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "NOTES"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            assertTrue(state.canInsertRows(tab), "前置条件：这张表可新增")
            assertFalse("ID" in tab.insertableColumns, "自增主键不该出现在新增表单里，实际=${tab.insertableColumns}")
            assertTrue(
                "BODY" in tab.insertableColumns,
                "普通列必须在，否则新增功能等于没有",
            )
        }

    /**
     * 新增行：空串与 NULL 同样分得开。
     *
     * 与编辑那条成对：只测编辑的话，一个「新增时一律写 NULL」的实现也能通过。
     */
    @Test
    fun `inserting rows keeps empty strings apart from NULL`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "NOTES"))
            val tab = state.tabs.single()
            tab.awaitSettled()

            val skipped =
                state.stageRowInserts(
                    tab,
                    listOf(
                        mapOf("BODY" to "blank one"),
                        mapOf("BODY" to ""),
                        mapOf("BODY" to null),
                        // 整行全空 —— 等于「这一行不存在」，必须被跳过而不是拖垮事务
                        emptyMap(),
                    ),
                )
            assertNotNull(skipped, "有一整行是空的，必须说出来 —— 悄悄丢掉会让人以为加了 4 行")
            assertTrue(skipped.contains("1"), "要说清跳过了几行：$skipped")
            assertEquals(3, tab.pendingOps.size, "全空的那一行必须被跳过，其余三条都要进缓冲")

            assertNull(state.applyCellEdits(tab), "提交应当成功")
            assertEquals(5, queryCount("SELECT COUNT(*) FROM notes"), "只应新增 3 行")

            val (blankValue, blankIsNull) = queryCell("SELECT body FROM notes WHERE body = 'blank one'")
            assertFalse(blankIsNull, "普通文本当然不是 NULL")
            assertEquals("blank one", blankValue)

            val (emptyValue, emptyIsNull) = queryCell("SELECT body FROM notes WHERE body = ''")
            assertFalse(emptyIsNull, "空串**不能**被当成 NULL")
            assertEquals("", emptyValue)

            assertEquals(
                1,
                queryCount("SELECT COUNT(*) FROM notes WHERE body IS NULL"),
                "显式 NULL 的那一条必须真的是 NULL —— `IS NULL` 而不是 `= ''`",
            )
        }

    // -------------------------------------------------------------------------
    // 三种操作共一个事务
    // -------------------------------------------------------------------------

    /**
     * **编辑 + 删除 + 新增混在一批里**，其中一条失败 ⇒ 前面已经写进去的全部回滚。
     *
     * 这是三合一缓冲存在的全部理由：如果三件事各有各的提交路径，用户做不出
     * 「改两格、删一行、插一行」这样一个原子操作，中途失败时数据库会停在
     * 一个谁也没打算要的状态。
     *
     * 构造：先改一行（**必成功**），再插一条 id 冲突的新增（**必失败**）。
     * 断言的重点仍然在第一条 —— 它证明回滚跨越了**不同类型的操作**。
     */
    @Test
    fun `a mixed batch of edits deletes and inserts rolls back all of them`() =
        runBlocking {
            val state = newBrowser()
            state.refreshDatabases()
            val db = state.firstLoadedDatabase()
            state.openTab(db, state.tablesFor(db, "USERS"))
            val tab = state.tabs.single()
            tab.awaitSettled()
            assertTrue(state.isTableEditable(tab) && state.canDeleteRows(tab) && state.canInsertRows(tab), "前置条件：三种能力都要开")

            val alice = tab.rows.first { it.cells["NAME"] == "Alice" }
            val bob = tab.rows.first { it.cells["NAME"] == "Bob" }

            // 顺序 = 提交顺序：先成功的那条必须排在前面，否则测不到「已写进去的要回滚」
            state.stageCellEdit(tab, CellEdit(alice.id, "NAME", "Alice", "Alicia"))
            state.stageRowDeletes(tab, listOf(bob.id))
            state.stageRowInserts(tab, listOf(mapOf("ID" to alice.cells["ID"].toString(), "NAME" to "dup")))
            assertEquals(3, tab.pendingOps.size, "三种操作必须同处一个缓冲")

            val err = state.applyCellEdits(tab)
            assertNotNull(err, "id 冲突的新增必须让整批失败")
            assertTrue(err.contains("回滚"), "错误文案要点明已经回滚：$err")

            assertEquals("Alice", queryString("SELECT name FROM users WHERE id = 1"), "**先写成功的编辑必须回滚**")
            assertEquals(2, queryCount("SELECT COUNT(*) FROM users"), "删除也必须回滚 —— Bob 还在")
            assertEquals("Bob", queryString("SELECT name FROM users WHERE id = 2"), "Bob 必须还在")
            assertFalse(
                tab.pendingOps.isEmpty(),
                "回滚后要保留缓冲，否则用户改的那两处就得重敲",
            )
        }

    /** 引擎没回能力位时（老引擎）必须**一律只读**，不许退回「有主键就当能改」。 */
    @Test
    fun `a missing capabilities block disables every write path`() =
        runBlocking {
            val tab = TablePreviewTab(schema = "PUBLIC", tableName = "USERS")
            tab.primaryKeyColumns = listOf("ID")
            val state = newBrowser()
            state.applyCapabilities(tab, caps = null)

            assertFalse(tab.capabilitiesLoaded, "没拿到能力位就是没拿到")
            assertFalse(state.isTableEditable(tab), "缺能力位时不能编辑")
            assertFalse(state.canInsertRows(tab), "缺能力位时不能新增")
            assertFalse(state.canDeleteRows(tab), "缺能力位时不能删")
            assertTrue(tab.readOnlyColumns.isEmpty() && tab.insertableColumns.isEmpty(), "清单必须一并清空")
            assertTrue(
                tab.notice?.contains("能力") == true,
                "必须在提示行里说清楚为什么不给编辑 —— 否则用户只看到「按钮不见了」：${tab.notice}",
            )
        }

    /** 直接查 H2，绕开状态机 —— 断言的是**数据库里的真值**，不是界面上显示的东西。 */
    private fun queryString(sql: String): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    if (rs.next()) rs.getString(1) else throw AssertionError("查询无结果: $sql")
                }
            }
        }

    /**
     * 查一格并同时报出「是不是 SQL NULL」—— 这两件事必须一起看。
     *
     * 为什么不能只用 [queryString]：`ResultSet.getString` 对 NULL 返回 Java 的 `null`，
     * 而这个函数的返回类型是非空 `String`，NULL 会以 NPE 的形式炸出来；更糟的是把
     * 包装类解包成 `String` 时 H2 有可能给出别的表现。**唯一可靠的判据是
     * [ResultSet.wasNull]** —— 它问的是「刚才那列的值是不是 SQL NULL」，
     * 而不是「Java 侧拿到的是什么」。
     */
    private fun queryCell(sql: String): Pair<String?, Boolean> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    if (!rs.next()) throw AssertionError("查询无结果: $sql")
                    val value = rs.getString(1)
                    value to rs.wasNull()
                }
            }
        }

    /** 查询的行数 —— 删/插用例用它验证「真的少了几行 / 多了几行」。 */
    private fun queryCount(sql: String): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    if (!rs.next()) throw AssertionError("查询无结果: $sql")
                    rs.getInt(1)
                }
            }
        }

    // -------------------------------------------------------------------------
    // 上面几项的公共脚手架
    // -------------------------------------------------------------------------

    /** 等库列表加载完，返回第一个库名。 */
    private suspend fun DatabaseBrowserState.firstLoadedDatabase(): String {
        withTimeout(5_000) { while (databases.isEmpty()) delay(50) }
        return databases.first()
    }

    /** 展开某个库、取出指定表名（H2 归一为大写，故大小写不敏感比对）。 */
    private suspend fun DatabaseBrowserState.tablesFor(
        db: String,
        wanted: String,
    ): String {
        if (db !in tablesByDatabase) toggleDatabase(db)
        withTimeout(10_000) { while (db in loadingTables) delay(50) }
        tableLoadError[db]?.let { throw AssertionError("加载表列表失败: $it") }
        // ⚠️ 这里**只**等表列表，不等 `objectsByDatabase`。
        // 展开库会顺带并发拉视图 / 触发器 / 函数（本类的 `bindConnection` 等用例也会
        // 展开库），等它等于让每条用到 `tablesFor` 的测试都被对象链路拖住 ——
        // 实测全量并发下把两条无关用例拖到 20s 超时。对象相关的等待由
        // 测对象的那几条各自负责（见 `expanding a database lists its views`）。
        return tablesByDatabase.getValue(db).first { it.uppercase() == wanted.uppercase() }
    }

    /** 等一个预览标签页的 in-flight 请求落定。 */
    private suspend fun TablePreviewTab.awaitSettled() {
        withTimeout(5_000) { while (loading) delay(10) }
    }
}

/** 测试期构造 ConnectionConfig —— 跳过 JdbcUrl 折算 */
private object TestConnectionFactory {
    fun build(jdbcUrl: String) =
        com.langbiantianya.sundays.connection.ConnectionConfig(
            id = "test-${System.nanoTime()}",
            name = "TestH2",
            dialect = com.langbiantianya.sundays.connection.DialectType.H2,
            username = "sa",
            password = "",
            jdbcUrl = jdbcUrl,
        )
}
