/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 数据库浏览器的**状态机** —— 与 UI 解耦，由 `DatabaseBrowserScreen` 创建。
// 同一份状态机可以脱离界面直接驱动（见 DatabaseBrowserFlowTest），所以这里**不能**出现任何
// Composable 函数或 Modifier。`TablePreviewTab` 放在这里而不是 TablePreview.kt，
// 是因为它本质是状态而不是界面。
//
// ⚠️ 唯一的例外是 `treeScrollState`（左树的滚动位置）—— 它**不是**渲染，
// 是一个状态容器；之所以住在这里，是因为它必须**活过面板本身**
// （面板折叠时整棵树会从组合里移除，记在面板里的位置就跟着没了）。
// 住在会话里还有个好处：每个连接各有一棵树，滚动位置各归各，
// 换连接不会把上一个连接的滚动位置带过来。

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.langbiantianya.client.EngineClient
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.columnDef
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.dataGenerateRequest
import com.langbiantianya.grpc.dataListRequest
import com.langbiantianya.grpc.dataRequest
import com.langbiantianya.grpc.foreignKeyCreateRequest
import com.langbiantianya.grpc.foreignKeyDeleteRequest
import com.langbiantianya.grpc.foreignKeyListRequest
import com.langbiantianya.grpc.foreignKeyRequest
import com.langbiantianya.grpc.functionListRequest
import com.langbiantianya.grpc.functionRequest
import com.langbiantianya.grpc.generateTable
import com.langbiantianya.grpc.indexListRequest
import com.langbiantianya.grpc.indexRequest
import com.langbiantianya.grpc.request
import com.langbiantianya.grpc.schemaListRequest
import com.langbiantianya.grpc.schemaRequest
import com.langbiantianya.grpc.sqlExecuteRequest
import com.langbiantianya.grpc.sqlRequest
import com.langbiantianya.grpc.systemRequest
import com.langbiantianya.grpc.tableColumnListRequest
import com.langbiantianya.grpc.tableCreateRequest
import com.langbiantianya.grpc.tableDeleteRequest
import com.langbiantianya.grpc.tableGetDdlRequest
import com.langbiantianya.grpc.tableListRequest
import com.langbiantianya.grpc.tableRequest
import com.langbiantianya.grpc.tableUpdateRequest
import com.langbiantianya.grpc.triggerListRequest
import com.langbiantianya.grpc.triggerRequest
import com.langbiantianya.grpc.viewListRequest
import com.langbiantianya.grpc.viewRequest
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.browser.BrowserPane
import com.langbiantianya.sundays.connection.ConnectionConfig
import com.langbiantianya.sundays.connection.DialectMetadata
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.SqlIdentifier
import com.langbiantianya.sundays.editor.CompletionItem
import com.langbiantianya.sundays.editor.CompletionKind
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.ui.CodeEditorState
import com.langbiantianya.sundays.notifications.AppNotification
import com.langbiantianya.sundays.notifications.NotificationCenter
import com.langbiantianya.sundays.sql.DangerousSql
import com.langbiantianya.sundays.sql.SqlLiterals
import com.langbiantianya.sundays.table.CellEdit
import com.langbiantianya.sundays.table.ColumnDraft
import com.langbiantianya.sundays.table.ColumnTypeCatalog
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.ForeignKeyDraft
import com.langbiantianya.sundays.table.PageSize
import com.langbiantianya.sundays.table.StructureChange
import com.langbiantianya.sundays.table.StructureDialogState
import com.langbiantianya.sundays.table.StructureLoadResult
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow
import com.langbiantianya.sundays.table.TableStructureDraft
import com.langbiantianya.sundays.table.defaultColumnType
import com.langbiantianya.sundays.table.diff
import com.langbiantianya.sundays.table.validate
import com.langbiantianya.sundays.ui.plural
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import com.google.protobuf.Value as ProtoValue

/**
 * 连接方言 → SQL 高亮档位。
 *
 * 映射表在 `:shared` 的 [com.langbiantianya.sundays.connection.DialectMetadata] 里 ——
 * 与 `DialectType.supportedConnectionTypes` / `engineDriverName` 同源，
 * 免得「方言 → 档位」成为**第五份**互不相干的硬编码 `when`
 * （那五份 `when` 曾经散落在三个文件里，改一个方言要改五个地方）。
 *
 * 编辑器模块（`:shared` 的 `editor` 包）**不依赖**连接配置 —— 依赖方向是
 * `connection` → `editor.language`（单向），编辑器对连接配置一无所知。
 */
private fun DialectType.toSqlDialectProfile(): SqlDialectProfile = DialectMetadata.of(this).sqlDialectProfile

// ============================================================================
// 数据库浏览器状态机 —— 与 UI 解耦, 由 [DatabaseBrowserScreen] 创建
// ============================================================================

/**
 * 单个表预览标签页的状态.
 *
 * `key = schema + "::" + tableName` 作为去重主键: 同一张表只能有一个打开的标签页.
 *
 * - [columns] / [rows] 渲染到 [DataTable]
 * - [loading] / [error] 控制顶部信息条
 * - [page] / [pageSize] / [total] 展示分页元信息(仅供 UI 显示)
 *
 * @property schema 本表所在**库**的名字 —— 字段叫 `schema`，装的却是**库名**。
 *   树上点表时传下来的就是库节点名（见 [DatabaseBrowserState.openTab]），而
 *   `loadTabPreview` 也正是拿它当 catalog 去建池（`engineConn(database = tab.schema)`）。
 *   真正的 schema 名由 `resolveSchemas` 解析、存在 `schemasByDatabase` 里，与本字段无关。
 *   历史遗留，改名要动十几处调用点；**读它请用 [database]** —— 那个别名至少不会
 *   让人把库名错塞进 [SqlIdentifier.tableRef] 的 `schema` 槽位。
 */
class TablePreviewTab(
    val schema: String,
    val tableName: String,
    val title: String = tableName,
) {
    val key: String get() = "$schema::$tableName"

    /**
     * 本表所在**库**的名字 —— [schema] 的语义化别名。
     *
     * 见类 KDoc：`schema` 这个字段名装的一直是库名。
     */
    val database: String get() = schema

    var columns: List<TableColumn> by mutableStateOf(emptyList())
    var rows: List<TableRow> by mutableStateOf(emptyList())
    var loading: Boolean by mutableStateOf(false)
    var error: String? by mutableStateOf(null)

    /**
     * 非读取结果的提示（导出成功 / 失败）。
     *
     * 与 [error] 分开是因为两者**语义不同、渲染后果也不同**：[error] 会把整块表格换成
     * 「读取失败」面板，而导出成不成功跟这张表读没读出来毫无关系 ——
     * 导成功了报「读取失败」、导失败了数据还好好的，两种都是骗用户。
     */
    var notice: String? by mutableStateOf(null)

    /**
     * [notice] 是**成**还是**败** —— 与文案内容无关的独立事实。
     *
     * ## 为什么不能靠解析 [notice] 的文字来判断
     *
     * 原实现是 `notice.startsWith("导出失败")`。那在中文界面下能工作，一旦界面语言
     * 不是中文就**恒为 false** —— 所有导出失败提示都会被渲染成成功色，而界面上
     * 没有任何报错。这是国际化里最典型的「静默失效」：不崩、不报错、只是说错话。
     *
     * 更根本地说，「这句话是不是报错」是**状态**，不属于文案。把状态编码进文案
     * （靠前缀、靠关键词、靠括号）就等于让每一处渲染都要重新解析一遍人类语言 ——
     * 换语言、加语种、改措辞，都会让它悄无声息地失准。
     *
     * 故严重级别由写入方显式声明，渲染方只读不猜。
     */
    var noticeIsError: Boolean by mutableStateOf(false)
    var total: Long by mutableStateOf(0L)
    var page: Int by mutableStateOf(1)
    var pageSize: Int by mutableStateOf(100)

    /**
     * 本表主键列名（**按列序**）。空列表 = 没有主键 / 引擎还没给 ⇒ 该表不可编辑 / 不可删除
     * （见 [DatabaseBrowserState.isTableEditable]）。
     *
     * ## 为什么是**列表**而不是单个字符串
     *
     * 复合主键（`PRIMARY KEY (tenant_id, id)`）在真实库里非常常见。而 `DATA.UPDATE`
     * 的 `where` 本来就是 `map<string, string>`，多列定位**不需要引擎做任何改动**。
     * 反过来，只取其中一列是**错的**：`tenant_id` 单独看完全可能重复，
     * 拿它去 `WHERE` 会一次改掉一整批行 —— 比「没有主键就不给编辑」严重得多。
     * 所以要么全带上，要么不给。
     *
     * 数据来自 `DATA.LIST` 响应里的 `TableCapabilities`（引擎算好一起回）。
     */
    var primaryKeyColumns: List<String> by mutableStateOf(emptyList())

    /**
     * 引擎回传的**写能力** —— 决定「编辑 / 新增 / 删除」三个入口能不能用。
     *
     * ## 为什么整个入口由引擎说了算
     *
     * 能不能写取决于**列定义**（有没有主键、哪些是自增、哪些是二进制）与
     * **对象类型**（视图即使部分可写也不放行）。前端要么自己发一次
     * `TABLE.COLUMN_LIST` 去推导，要么就等着用户点下去撞引擎报错 ——
     * 后者正是这个功能上线前的老毛病。
     *
     * 于是引擎在 `DATA.LIST` 里把它算好一起回（见 `buildTableCapabilities`）。
     * 拿不到（老引擎 / 元数据查询失败）时**一律收紧**：三个开关全 `false`，
     * 写入口全部禁用。「宁可先不给编辑」这条边界不能因为版本兼容而破。
     */
    var canUpdate: Boolean by mutableStateOf(false)
    var canInsert: Boolean by mutableStateOf(false)
    var canDelete: Boolean by mutableStateOf(false)

    /**
     * **不可编辑**的列 key 集合 —— 主键列 + 二进制列。渲染时只问「这一格能不能改」，
     * 不该每次都重新推导一遍规则。
     *
     * - **主键列不可改**：它同时是定位这一行的 `WHERE` 条件。让用户改它，就得处理
     *   「第一条把主键改了，后面几条的 `WHERE` 还命不命中」这种自相矛盾的序列。
     *   事务能保证「全改或全不改」，但保证不了「每条都改对」—— 后者是谓词的事。
     * - **二进制列不可改**：`DATA.LIST` 对它们返回 `"[LOB Data]"` 占位串而不是真实内容，
     *   拿占位串写回去等于**用一句提示语覆盖掉用户的二进制数据**，且不可逆。
     */
    var readOnlyColumns: Set<String> by mutableStateOf(emptySet())

    /** 允许取 SQL NULL 的列 —— 决定编辑框里画不画「设为 NULL」的开关。 */
    var nullableColumns: Set<String> by mutableStateOf(emptySet())

    /** 新增行时可填的列（引擎已剔除自增列与二进制列），**按列序**。 */
    var insertableColumns: List<String> by mutableStateOf(emptyList())

    /**
     * 引擎声明的「空串语义」：当前恒为 `true`（空串就写空串）。
     *
     * 存成字段而不是在各处直接比枚举，是因为**约定要能变**：哪天引擎改成
     * `EMPTY_AS_NULL`，界面必须跟着变（编辑框里的 `∅` 开关语义、提示文案），
     * 而不能各处硬编码着旧行为、静默按错的约定写库。
     */
    var emptyValueIsString: Boolean by mutableStateOf(true)

    /**
     * 能力是否已经随本页数据取到。
     *
     * 与「能不能写」**刻意分开**：[canUpdate] 为 `false` 可能是因为真的不能写，
     * 也可能是因为还没取到。渲染层需要区分这两种情况来给不同的提示文案 ——
     * 合并成一个 `false` 会让「表不可写」和「还没加载完」显示成同一个错误。
     */
    var capabilitiesLoaded: Boolean by mutableStateOf(false)

    /**
     * 一条**待提交**的改动。
     *
     * ## 为什么把三种操作收进一个列表
     *
     * 「改两格 + 删一行 + 插一行」是用户一次就能做出来的事。如果三种操作各有
     * 各的暂存与各自的提交按钮，就会出现「改的提交了、删的还没提交」这种**用户
     * 根本没法表达意图**的中间态 —— 而事务本来就能一次性覆盖它们。
     *
     * 收成一个列表还带来一个正确性好处：提交顺序是确定的（暂存顺序），
     * 出错时能准确告诉用户「第几条失败」，而不是三套互不相干的计数。
     */
    sealed interface PendingOp {
        /** 改一行的若干格。`changes` 的值为 `null` 表示写 SQL NULL。 */
        data class Update(
            val rowId: Any,
            val changes: Map<String, String?>,
        ) : PendingOp

        /** 删一行。[rowId] 只用于回显，真正定位靠主键单元格值（见 [DatabaseBrowserState.applyCellEdits]）。 */
        data class Delete(
            val rowId: Any,
        ) : PendingOp

        /** 插一行。`values` 的值为 `null` 表示写 SQL NULL。 */
        data class Insert(
            val values: Map<String, String?>,
        ) : PendingOp
    }

    /**
     * **暂存**但还没发给引擎的改动（编辑 / 删除 / 新增），按暂存先后排序。
     *
     * ## 为什么不是「改完立刻写库」
     *
     * 用户改一列就发一条 `UPDATE` 的话，批量改 20 行会变成 20 条独立提交 ——
     * 第 13 行撞上唯一索引时，前 12 行已经落库了，只能靠用户自己手工撤。
     * 暂存 + 一次提交把这 12 条收进**一个事务**，失败整批回滚（见 [DatabaseBrowserState.applyCellEdits]）。
     * 引擎的 `SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` + `Request.session_id` 早就能做到这件事，
     * 缺的只是「先把改动攒起来」这一层。
     *
     * ## 用 List 而不是 Map
     *
     * 更新项在**写入缓冲时**就按 `(rowId, columnKey)` 合并好了（见
     * [DatabaseBrowserState.stageCellEdit]），所以这里不需要 Map 去重；
     * 而删除与新增本来就没有「同一目标去重」的问题，用 List 才能保留**顺序** ——
     * 出错时要能准确说出「第几条失败」，而顺序正是提交顺序。
     */
    var pendingOps: List<PendingOp> by mutableStateOf(emptyList())

    /** 行首复选框选中的行 —— 批量删除的来源。翻页后不保留（选中的行已经不在屏幕上）。 */
    var selectedRowIds: Set<Any> by mutableStateOf(emptySet())

    /** 提交进行中 —— 期间禁用提交/放弃按钮，避免重复点出两个事务。 */
    var applyingEdits: Boolean by mutableStateOf(false)

    /**
     * 叠加了待提交修改后的行 —— **渲染用**。
     *
     * [rows] 始终是数据库里的真值，不做乐观更新。这样「放弃修改」只要清空
     * [pendingOps] 就能精确还原；反过来若提交前就改了 [rows]，一旦提交失败
     * 本地值已经和库里不一致了，还得专门写一套回滚 UI 去把它改回来。
     *
     * 已标记删除的行**照常显示原值**：删除是整行的事，把它的格子清空会让用户
     * 在提交前就无法确认「到底删的是哪几行、内容是什么」。
     *
     * 没有待提交修改时**原样返回 [rows]**本身（不复制）：这是最常见的路径，
     * 没必要为它每帧分配一份新的 List。
     */
    fun displayRows(): List<TableRow> {
        if (pendingOps.none { it is PendingOp.Update }) return rows
        return rows.map { row ->
            val overlay =
                pendingOps
                    .filterIsInstance<PendingOp.Update>()
                    .filter { it.rowId == row.id }
                    // `associate` 而不是 `+ overlay`：更新项的 `changes` 已经是「列名 → 新值」，
                    // 而 `cells` 也是同一套键，可以直接并进去。
                    .flatMap { it.changes.entries }
                    .associate { it.key to it.value }
            if (overlay.isEmpty()) row else row.copy(cells = row.cells + overlay)
        }
    }

    /**
     * 过滤条件（`WHERE` 子句的**内容**，不含 `WHERE` 关键字）。
     *
     * 空串 = 不过滤。改动会让本页回到第 1 页 —— 在第 3 页上把过滤条件收紧，
     * 结果集可能只剩 2 页，停在第 3 页会看到一张空表。
     */
    var whereClause: String by mutableStateOf("")

    /**
     * 排序子句（`ORDER BY` 的**内容**，不含 `ORDER BY` 关键字）。空串 = 不排序。
     *
     * 引擎侧 `DataListRequest.order_by` 收的就是裸子串，并由 `DatabaseDialect.validateOrderBy`
     * 挡注入（`name; DROP TABLE users` 这类会被拒）—— 所以**不要**在前端再拼一次校验，
     * 两处校验规则会漂移。
     */
    var orderByClause: String by mutableStateOf("")

    /** 表内文本搜索词 —— 编译成 `WHERE` 里的 `LIKE '%词%'`（见 [applyTextSearch]）。 */
    var searchTerm: String by mutableStateOf("")

    /**
     * 本 tab 所属连接的**方言**，用来决定 `CAST` 的目标类型（见 [SqlLiterals.castTypeFor]）。
     *
     * 为什么不从 `DatabaseBrowserState.currentConnection` 现取：切连接会清空全部状态，
     * 而 `whereClause` / `searchTerm` 是**跟着 tab 走**的；tab 在构造那一刻就知道自己属于谁。
     * 存成字段也让 `effectiveWhere()` 保持纯函数（不读全局状态），可测。
     *
     * 默认 `null` = 不知道方言，用 `VARCHAR`（H2 / PG / SQLite / DuckDB 都是它）。
     * 只有 MySQL 需要换成 `CHAR` —— 它的 `CAST` 不接受 `VARCHAR`，那是**语法错误**。
     */
    var dialect: com.langbiantianya.sundays.connection.DialectType? = null

    /**
     * 把本 tab 的**生效过滤条件**算出来 —— 手工 `WHERE` 与表内搜索的合并。
     *
     * 两者都是 AND 关系：用户手填 `status = 'paid'` 又搜 `bob`，结果就是
     * `status = 'paid' AND (CAST(...) LIKE … OR CAST(...) LIKE …)`。
     *
     * 搜索按**所有列 OR** 起来：用户搜的是「这张表里哪一行有这个词」，而不是
     * 「哪个已知文本列里有这个词」—— 后者我们**判断不了**，因为 `DATA.LIST` 的响应
     * 只回行数据、不回列类型（与 [primaryKeyColumn] 同一类信息缺口）。
     *
     * @return `null` 表示没有过滤条件（不传 `where`）。
     */
    fun effectiveWhere(): String? {
        val manual = whereClause.trim()
        val like = likeOverAllColumns()
        return when {
            manual.isNotEmpty() && like != null -> "$manual AND $like"
            manual.isNotEmpty() -> manual
            else -> like
        }
    }

    /**
     * 本表在 SQL 里的**限定引用名**。
     *
     * ## 为什么不能是裸表名
     *
     * 导出子进程连的是**连接配置里那个库**（JDBC URL 里钉死的那个），而这张表
     * 可能在**另一个**库。裸表名按会话默认库解析，于是导出必然报
     *
     * ```text
     * Table 'sundays_smoke.probe_shared_name' doesn't exist
     * ```
     *
     * 而用户在树上点的偏偏是 `sundays_xdb` —— 报错里那个库名恰好是「我没点的那个」，
     * 很难自己联想到根因（真机实测，见 desktopApp/TEST_CASES.md 的导出缺陷记录）。
     *
     * 限定规则交给 [SqlIdentifier.tableRef]：它知道 MySQL 的命名空间**就是** database、
     * PG 的 database 是 catalog（跨库压根不可引用，只能退回 schema / 裸表名）、
     * H2 的 schema 是 `PUBLIC`（把库名当 schema 写会报 `Schema not found`）。
     *
     * ## 为什么**不**带 schema 段
     *
     * schema 名是 `resolveSchemas` 解析出来的、且只能取列表第一个 —— 而 PG 的
     * schema 列表是按名字排序的，第一个未必是 `public`。**猜错的 schema 比不带更糟**：
     * 它会把查询指到一个确实存在、但**不是这张表**的地方去，导出一份别人的数据。
     * 所以这里只给「库」这一级；schema 交给会话的 search_path 解析。
     */
    fun tableReference(): String =
        SqlIdentifier.tableRef(
            dialect = dialect ?: DialectType.UNKNOWN,
            database = database,
            schema = "",
            table = tableName,
        )

    /**
     * 按当前预览条件拼出一条**可读、可编辑**的 SELECT，供导出对话框起手用。
     *
     * ## 为什么把过滤与排序拼进来
     *
     * 原来的导出按钮拼的是 `SELECT * FROM <表>` —— 恒为**整张表**，与用户眼前的
     * 过滤、排序、表内搜索全都对不上。屏幕上筛出 3 行、点导出却拿到全表几万行，
     * 而对话框里那行「将执行：SELECT * FROM xxx」还是**只读的一行小字**，
     * 用户根本无从判断将要导出什么。
     *
     * 现在起手 SQL 与 [effectiveWhere] / [orderByClause] 同源：导出的就是
     * 「屏幕上这张表」。而且它进了**可编辑**的代码编辑器（见 [ExportDialog]），
     * 想改成 `SELECT a, b`、加个 `LIMIT`、换聚合方式都行。
     *
     * ## 为什么用 [effectiveWhere] 而不是只取 [whereClause]
     *
     * 表内搜索框也是过滤。用户在搜索框敲了词、屏幕上只剩匹配行，
     * 导出却把不匹配的行也带进去，同样是「所见非所导」。
     *
     * ## 表名为什么带限定
     *
     * 裸表名按**会话默认库**解析，而导出打到的库未必是这张表所在的库 ——
     * 见 [tableReference] 的 KDoc。
     *
     * @return 单行 SQL；无过滤无排序时就是 `SELECT * FROM <限定表名>`。
     */
    fun buildExportSql(): String {
        val sb = StringBuilder("SELECT * FROM ").append(tableReference())
        effectiveWhere()?.takeIf { it.isNotBlank() }?.let { sb.append(" WHERE ").append(it.trim()) }
        orderByClause.trim().takeIf { it.isNotEmpty() }?.let { sb.append(" ORDER BY ").append(it) }
        return sb.toString()
    }

    /**
     * 搜索词铺到每一列上，`OR` 连成一个括号组。
     *
     * **列还没拿到时返回 `null`**（不加过滤），而不是退回「无列名 LIKE」——
     * 后者发出去必然语法错误，而此刻表本来还没加载出数据，加不加过滤没有区别。
     */
    private fun likeOverAllColumns(): String? {
        val term = searchTerm.trim()
        if (term.isEmpty()) return null
        val castType = SqlLiterals.castTypeFor(dialect ?: com.langbiantianya.sundays.connection.DialectType.H2)
        val predicates = columns.mapNotNull { SqlLiterals.likeContains(it.key, term, castType) }
        if (predicates.isEmpty()) return null
        return predicates.joinToString(" OR ", prefix = "(", postfix = ")")
    }

    /**
     * 本 tab 的 in-flight 失效代次 —— 语义与 [SqlSheet.generation] 一致，但作用域是**单个 tab**。
     *
     * 之前预览只有屏级的 [DatabaseBrowserState.generation]（连接级），管不到「同一张表上
     * 连着翻两页」：两次 `loadTabPreview` 并发在跑，谁先返回谁先写，**慢的那次会覆盖快的那次**。
     * 症状是信息条写着「第 2 页」而表里是第 1 页的行 —— 翻页越快越容易撞上。
     * 这与「旧流异常抹掉新请求的 running」是同一类缺陷：多个 in-flight 请求写同一份状态，
     * 却没有一个把它们区分开的令牌。
     */
    internal var requestGeneration: Int = 0
}

/**
 * 引擎侧的 [TablePreviewTab.pageSize]（Int）→ UI 侧的 [PageSize]（枚举）。
 *
 * 之所以两边类型不同：协议里 pageSize 是整数，而分页下拉要展示的是带标签的固定档位。
 * 精确匹配不到时（引擎把 500 钳成了 1000 之类）取数值最接近的档位 ——
 * 宁可让下拉显示一个近似值，也不要退化成 [PageSize.ALL]：那会让分页栏在服务端分页下
 * 变成「不分页」，而 rows 只有一页，界面看着正常、翻页却永远不出新数据。
 */
internal fun Int.toPageSize(): PageSize =
    PageSize.entries.firstOrNull { it.value == this }
        ?: PageSize.entries.filterNot { it.isAll }.minByOrNull { kotlin.math.abs(it.value - this) }
        ?: PageSize.S100

/**
 * SQL 工作台补全候选的**缓存签名** —— 只取会影响候选内容的字段。
 *
 * 用途是作为 `remember` 的 key。Compose 的 `remember` 用 `equals`（结构相等，不是引用）
 * 比较 key，因此：
 * - [TablePreviewTab.columns] 变化（预览刚加载完）→ 签名变 → 候选重算 ✓
 * - [TablePreviewTab.rows] 变化（翻页 / 刷新数据）→ 签名**不变** → 不重算 ✓
 *
 * 第二条是这里唯一需要小心的点：`rows` 是真正的数据（可能上千行），一旦被牵进 key，
 * 每翻一页都会重建整份候选列表 —— 而候选内容根本没变。
 *
 * 同理**不能**直接用 `tabs` 当 key：双击打开表只改 `tab.columns`，`tabs` 列表引用不变，
 * `remember` 不会重跑，字段候选就永远出不来。
 */
internal fun schemaCompletionSignature(
    tabs: List<TablePreviewTab>,
): List<Triple<String, String, List<Pair<String, String>>>> =
    tabs.map { tab ->
        Triple(tab.schema, tab.tableName, tab.columns.map { it.key to it.header })
    }

/**
 * 数据库浏览屏幕的状态机 —— 维护数据库/表加载、标签页打开/关闭/选择,
 * 以及当前引擎会话状态(连接 ID 仅由 [DatabaseBrowserScreen] 在外层持有).
 */
class DatabaseBrowserState(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    /**
     * 屏级共享的通知中心 —— **所有 sheet 共用一个**。
     *
     * 传 null 是给「只需要跑逻辑、不关心通知」的调用方（测试）留的出口；
     * 那种情况下任务成败仍然照常记进 [backgroundTasks] 与各 sheet 的提示行，
     * 只是不往通知中心推。
     */
    private val notifications: NotificationCenter? = null,
    /**
     * 剪贴板写入出口 —— 默认真系统剪贴板，测试注入替身。
     *
     * ## 为什么做成参数而不是直接调 `ClipboardWriter.copy`
     *
     * 复制动作的**成功与失败两条路都必须有测试**，但真实剪贴板在无头环境里必然失败、
     * 在有头环境里必然成功 —— 环境决定了走哪条路，测试就只能测其中一半，
     * 另一半（尤其是「失败时必须推错误通知」这条最要紧的）永远测不到。
     * 而且 UI 测试环境在这台机器上**有头**（`isHeadless=false`），于是
     * 「无头必然失败」这个假设还会在本地绿、在 CI 红。
     *
     * 注入之后两条路都能被钉住，且**与运行环境无关**。
     */
    private val clipboard: (String) -> Boolean = { ClipboardWriter.copy(it) },
) {
    /** 数据库名称列表(来自 SCHEMA.LIST level=database) */
    var databases: List<String> by mutableStateOf(emptyList())
        private set

    var loadingDatabases: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    /** 已展开的数据库节点 */
    val expandedDatabases: SnapshotStateSet<String> = mutableStateSetOf()

    /**
     * 左树的**滚动位置** —— 提到面板**外面**持有。
     *
     * ## 为什么不在面板里 `rememberLazyListState()`
     *
     * 面板折叠时整棵树会从组合里移除（44dp 的窄轨塞不下树），
     * 记在面板里的状态跟着一起没了 —— 展开后树回到顶部，
     * 用户得重新滚回去找刚才那张表。
     * 提到会话里，容器拆了状态还在，展开后树还停在原来那个位置。
     *
     * ## 为什么住在**会话**里而不是屏级
     *
     * 滚动位置是**这棵树**的位置：换个连接就是另一棵树，位置没有意义
     *（屏级的会串到别的连接上去 —— 切到 B 连接时树停在 A 滚到的位置）。
     * 它与「哪些库是张开的」是同一类状态 —— 都属于**这棵树**。
     *
     * 它跟着会话一起死：sheet 关了，这棵树也没了，位置自然不必留。
     */
    val treeScrollState: LazyListState = LazyListState()

    /** database → table list(来自 TABLE.LIST) */
    private val _tablesByDatabase = mutableStateMapOf<String, List<String>>()

    val tablesByDatabase: Map<String, List<String>> get() = _tablesByDatabase

    /** 正在加载表的 database 集合(控制行内 spinner) */
    val loadingTables: SnapshotStateSet<String> = mutableStateSetOf()

    /** database → table 加载错误 */
    private val _tableLoadError = mutableStateMapOf<String, String>()

    // ------------------------------------------------------------------
    // 对象浏览：视图 / 触发器 / 函数（schema 级）与 索引 / 外键（表级）
    // ------------------------------------------------------------------

    /**
     * 库级对象的三种：**视图 / 触发器 / 过程·函数**。
     *
     * 引擎侧它们是**库级**的（`ViewListRequest.schema` / `TriggerListRequest.schema` /
     * `FunctionListRequest.schema` 都只要一个 schema，不像索引/外键还要 `table_name`），
     * 所以归在库节点下、跟表平级。
     *
     * 之前只列出「表」，是 DataGrip / Navicat 意义上的**对象浏览器缺失一半**：
     * 用户能看表却看不到视图和触发器，而这两类恰恰是排查「数据从哪来 / 会不会被改」时
     * 最先要看的东西。
     */
    enum class DatabaseObjectKind(
        val labelKey: StrKey,
    ) {
        VIEW(StrKey.VIEWS),
        TRIGGER(StrKey.TRIGGERS),
        FUNCTION(StrKey.ROUTINES),
    }

    /** database → 该类对象名列表（来自各自的 `*ListRequest`）。 */
    private val _objectsByDatabase = mutableStateMapOf<String, Map<DatabaseObjectKind, List<String>>>()

    val objectsByDatabase: Map<String, Map<DatabaseObjectKind, List<String>>>
        get() = _objectsByDatabase

    /** 正在加载库级对象的 `(库, 类型)` 组合 —— 控制那一行的小 spinner。 */
    val loadingObjects: SnapshotStateSet<Pair<String, DatabaseObjectKind>> = mutableStateSetOf()

    /** 库级对象加载错误：`"库::类型" -> 文案`。 */
    private val _objectLoadError = mutableStateMapOf<String, String>()
    val objectLoadError: Map<String, String> get() = _objectLoadError

    /** 表级对象的三种：**字段 / 索引 / 外键**（引擎侧要 `table_name`，所以挂表节点下）。 */
    enum class TableObjectKind(
        val labelKey: StrKey,
    ) {
        /** 字段 —— 走 `TABLE.LIST`（`tableColumnListRequest`），值结构化，存 [_tableColumns]。 */
        COLUMNS(StrKey.COLUMN),

        INDEX(StrKey.INDEX),
        FOREIGN_KEY(StrKey.FOREIGN_KEY),
    }

    /**
     * 一个字段的展示形态。
     *
     * 刻意**不直接存 proto 的 `ColumnDef`**：那是引擎的传输结构，UI 只需要
     * 「名字 / 类型 / 主键 / 可空 / 自增」，中间隔一层以后引擎加字段不会波及 UI，
     * 反过来 UI 想改展示（比如多显示一列注释）也不必动 proto 依赖。
     */
    data class ColumnInfo(
        val name: String,
        /** 类型原文；[typeWithSize] 才是「VARCHAR(64)」这种带长度的完整写法。 */
        val type: String,
        val size: Int,
        val nullable: Boolean,
        val primaryKey: Boolean,
        val autoIncrement: Boolean,
    ) {
        /**
         * `VARCHAR(64)` / `INT`。
         *
         * ⚠️ **不能无条件加括号**：`size` 是 proto 的 `int32`，未指定时是 **0**，
         * 于是 `VARCHAR` 会变成 `VARCHAR(0)` —— 一个不存在的类型，
         * 用户看了会以为这张表有问题。
         */
        val typeWithSize: String get() = if (size > 0) "$type($size)" else type
    }

    /**
     * `"库::表" -> 字段列表`。
     *
     * 与 [_tableObjects]（只存名字）**分开**：字段要显示类型 / 主键 / 可空，
     * 塞进 `Map<TableObjectKind, List<String>>` 就只剩名字了。
     */
    private val _tableColumns = mutableStateMapOf<String, List<ColumnInfo>>()

    /** 字段表本身 —— 与 [_tableObjects] 同形态的只读出口。 */
    val tableColumns: Map<String, List<ColumnInfo>> get() = _tableColumns

    /** 表级对象加载错误：`"库::表::类型" -> 文案`。 */
    private val _tableObjectError = mutableStateMapOf<String, String>()
    val tableObjectError: Map<String, String> get() = _tableObjectError

    fun columnsOf(
        database: String,
        table: String,
    ): List<ColumnInfo>? = _tableColumns["$database::$table"]

    fun isTableObjectLoading(
        database: String,
        table: String,
    ): Boolean = loadingTableObjects.any { it.first == "$database::$table" }

    /** `"库::表" -> 该类对象名列表。 */
    private val _tableObjects = mutableStateMapOf<String, Map<TableObjectKind, List<String>>>()

    val tableObjects: Map<String, Map<TableObjectKind, List<String>>> get() = _tableObjects

    val loadingTableObjects: SnapshotStateSet<Pair<String, TableObjectKind>> = mutableStateSetOf()

    /** 已展开表对象列表的表：`"库::表"`。 */
    val expandedTableObjects: SnapshotStateSet<String> = mutableStateSetOf()

    /**
     * 展开 / 收起某张表的**索引 / 外键**。
     *
     * 与库级对象不同，这里**不缓存**：每张表的对象是「这张表的」，不存在跨表复用，
     * 而缓存会让「别处新建的索引看不到」。
     */
    fun toggleTableObjects(
        database: String,
        table: String,
    ) {
        val slot = "$database::$table"
        if (slot in expandedTableObjects) {
            expandedTableObjects.remove(slot)
        } else {
            expandedTableObjects.add(slot)
            loadTableObjects(database, table)
        }
    }

    /**
     * `库 -> 该库的 schema 名列表`（`SCHEMA.LIST level=schema` 的结果）。
     *
     * 单独存一份是为了**表级对象**（索引 / 外键）能直接取用：那两条路由同样要
     * schema 名，而它们是「每张表一次往返」，绝不能每张表都重新解析一遍 schema。
     */
    private val _schemasByDatabase = mutableStateMapOf<String, List<String>>()
    val schemasByDatabase: Map<String, List<String>> get() = _schemasByDatabase

    /**
     * 已解析过的 schema 名 —— 直接读缓存（**不触发解析**）。
     *
     * 需要「解析完再发查询」的调用点请用可挂起的 `resolveSchemas`；这里只给
     * 「拿到就用、拿不到就退化成默认值」的地方用。多数方言把空串当作「默认 schema」，
     * 而 H2 的 `listIndexes` / `listViews` 明确要求 `PUBLIC` —— 所以退化成空串是
     * **看得见的错**，不是安全的兜底。
     */
    val tableLoadError: Map<String, String> get() = _tableLoadError

    // ------------------------------------------------------------------------
    // 对象树右键：复制引用名 / 复制 DDL
    // ------------------------------------------------------------------------

    /**
     * 树上某个节点被右键时的目标。
     *
     * @property database 所属库（限定名要用）。
     * @property schema 该库的 schema 名；空串 = 「这个方言没有独立 schema 层」
     *   或尚未解析出来（见 [SqlIdentifier.tableRef] 对这两类方言的处理差异）。
     * @property table 表名；`null` = 右键在**库节点**上。
     * @property column 字段名；`null` = 不在字段上。
     */
    data class TreeTarget(
        val database: String,
        val schema: String = "",
        val table: String? = null,
        val column: String? = null,
    )

    /**
     * 当前连接的方言 —— 复制引用名要用（MySQL 反引号、其余双引号）。
     *
     * 拿不到连接时按 [DialectType.UNKNOWN] 处理（双引号），而不是让整条复制路径
     * 不可用：右键菜单此刻已经弹出来了，用户要的至少是一个能看的名字。
     */
    private val treeDialect: DialectType
        get() = currentConnection?.dialect ?: DialectType.UNKNOWN

    /**
     * 节点对应的限定引用名 —— 「复制引用名」写进剪贴板的内容。
     *
     * 有字段名时给 `<表引用>.<字段>`（字段与表同一个 schema，因此只需限定到表这一层）；
     * 有表名时给 [SqlIdentifier.tableRef]；只有库名时给库引用。
     *
     * ## ⚠️ PG 的 catalog 这一级只在「这个库就是当前连接的库」时才写得出来
     *
     * PG 支持 `catalog.schema.table` 三段式（不是 PG 18 才有的），但**库名必须等于
     * 当前连接的库**，否则报 `cross-database references are not implemented` ——
     * PG 源码 `RangeVarGetCreationNamespace` 里只有这一条判据。
     *
     * 树上的库节点列的是**同一实例上的所有库**（树里能看到 `examquestions` / `postgres`
     * / `sundays_smoke` 并列，而连接只连了其中一个），所以 `target.database` **不等于**
     * 当前连接的库是完全可能的 —— 这时写 catalog 就会产出「粘进 SQL 编辑器必炸」的引用。
     * 判断不等时退回 `schema.table`：那个形态在 PG 里任何版本、任何库都恒可用。
     *
     * 比较用 `equals(ignoreCase = true)`：PG 对**未加引号**的库名会先小写折叠
     * （实测 `FROM EXAMQUESTIONS.public.t1` 成功），而我们复制出去的是带引号的形式
     * （保留大小写），所以配置里大小写不一致时不该判成「不是当前库」而白白丢掉这一级。
     */
    fun treeReferenceText(target: TreeTarget): String {
        val dialect = treeDialect
        val table = target.table
        val column = target.column
        val dbIsCurrent = target.database.equals(currentConnection?.database, ignoreCase = true)
        val tableText = SqlIdentifier.tableRef(dialect, target.database, target.schema, table ?: "", dbIsCurrent)
        return when {
            table != null && column != null -> {
                // 字段接在表引用后面 —— PG 认 `catalog.schema.table.column` 四段式
                // （实测 `SELECT "examquestions"."public"."t1"."l2" FROM ...` 通过）
                tableText + "." + SqlIdentifier.quote(dialect, column)
            }

            table != null -> {
                tableText
            }

            else -> {
                SqlIdentifier.databaseRef(dialect, target.database)
            }
        }
    }

    /** 节点显示用的裸名 —— 「复制名称」写进剪贴板的内容。 */
    fun treePlainName(target: TreeTarget): String = target.column ?: target.table ?: target.database

    /** 复制成功后推一条通知（失败由各调用点自己带原因推）。 */
    private fun notifyCopied(
        what: String,
        detail: String,
        ok: Boolean,
    ) {
        val center = notifications ?: return
        center.push(
            if (ok) AppNotification.Severity.SUCCESS else AppNotification.Severity.ERROR,
            if (ok) Msg.format(StrKey.COPIED, what) else Msg.format(StrKey.COPY_FAILED, what),
            detail,
        )
    }

    /**
     * 「复制引用名」/「复制名称」—— 纯本地动作，不发引擎请求。
     *
     * ## 为什么复制失败要推**错误**通知而不是静默
     *
     * 剪贴板写失败时用户手上什么都不会变，而界面**没有任何变化** —— 表现为
     * 「我点了菜单项，什么都没发生」，且没有任何可排查的线索（无头环境 / 剪贴板被
     * 占用 / Wayland 无剪贴板服务，三种原因用户都看不出来）。推一条错误通知，
     * 用户至少知道是**这一步**失败了，而不是应用卡了。
     */
    fun copyTreeText(
        target: TreeTarget,
        quoted: Boolean,
    ) {
        val what = if (quoted) Msg.text(StrKey.REFERENCE_NAME) else Msg.text(StrKey.NAME)
        val text = if (quoted) treeReferenceText(target) else treePlainName(target)
        notifyCopied(what, text, clipboard(text))
    }

    /**
     * 「复制建表 DDL」—— 发 `TABLE.GET_DDL` 取引擎侧的建表语句再复制。
     *
     * ## 为什么 DDL **不能**前端拼
     *
     * 五个方言的建表语句差异大到没有共性可循：PG 要回填主键 / UNIQUE / CHECK /
     * 注释且列类型要按 `information_schema` 还原长度，SQLite 直接读
     * `sqlite_master.sql` 的原文，MySQL 要拼 `ENGINE=InnoDB` 与字符集子句。
     * 前端拼一份的结果只会在某一个方言上看起来对 —— 这正是**引擎侧
     * `getCreateTableDDL` 已经存在**的原因。
     *
     * ## schema 名的等待
     *
     * `TableGetDdlRequest.schema` 要的是 **schema 名**，与 [loadTableObjects] 同理：
     * 必须等 `resolveSchemas` 回来再发，不能带着空串发一次「成功但取不到」的请求
     * （那会退化成 H2 报 `Schema not found` / PG 查 `information_schema` 查空）。
     */
    fun copyTreeDdl(target: TreeTarget) {
        val table = target.table
        val database = target.database
        // 字段节点上不给 DDL：DDL 是**整张表**的建表语句，从字段行点它拿到的是
        // 一张与该字段毫不相干的表的语句。UI 侧已按 `onTableNode` 隐藏该项，
        // 这里再挡一道是为了让「直接调状态机」也不会发出这个请求 ——
        // 判据散在两处时，迟早会有一处被漏掉。
        if (table == null || target.column != null) return
        scope.launch {
            val schema =
                if (target.schema.isNotBlank()) {
                    target.schema
                } else {
                    resolveSchemas(database).firstOrNull() ?: ""
                }
            val resp =
                runCatching {
                    engine.invoke(engineConn(database = database)) {
                        category = Category.TABLE
                        action = Action.GET_DDL
                        tableRequest =
                            tableRequest {
                                getDdl =
                                    tableGetDdlRequest {
                                        tableName = table
                                        this.schema = schema
                                    }
                            }
                    }
                }.getOrNull()

            val ddl =
                resp
                    ?.takeIf { it.success }
                    ?.table
                    ?.getDdl
                    ?.ddl
                    .orEmpty()
            if (ddl.isBlank()) {
                // 失败原因**用引擎原文**：本项目里「请求成功但结果为空」比「请求失败」
                // 更难察觉，而 DDL 为空几乎总是因为 schema / 表名对不上 ——
                // 用户看到「复制失败」是没法排查的，看到原文才行。
                val reason = resp?.error?.takeIf { it.isNotBlank() } ?: Msg.text(StrKey.ENGINE_NO_DDL)
                notifyCopied(Msg.text(StrKey.CREATE_TABLE_DDL), reason, ok = false)
            } else {
                notifyCopied(
                    Msg.text(StrKey.CREATE_TABLE_DDL),
                    Msg.plural(PluralStrKey.DDL_WITH_LENGTH, ddl.length, table, ddl.length),
                    clipboard(ddl),
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // 表结构：弹窗状态 / 载入 / 新建 / 保存 / 删除
    // ------------------------------------------------------------------------

    /** 表结构弹窗的当前状态；`null` = 弹窗没开。「新增表」与「编辑表结构」共用它。 */
    var structureDialog by mutableStateOf<StructureDialogState?>(null)
        private set

    /**
     * 待确认删除的表 —— 非空时弹二次确认框。
     *
     * 删表不可逆（`DROP TABLE` 连数据一起没），而右键菜单的删除项离点中只有一次点击的
     * 距离 —— 不确认就直接执行，误触的代价是整张表。
     */
    var pendingDeleteTable by mutableStateOf<TreeTarget?>(null)
        private set

    /**
     * 打开「编辑表结构」。
     *
     * 判据 `table != null && column == null` 与菜单侧那一条**故意重复**：
     * 菜单项的可见性是界面层的决定，而这里是状态机自己的边界，两边任一处被改坏
     * 都不会让「在字段节点上改整张表」这种请求发出去。
     */
    fun openTableStructureDialog(target: TreeTarget) {
        val table = target.table
        if (table == null || target.column != null) return
        if (!ensureWritable()) return
        // 先开弹窗再读：读失败时弹窗里至少有个空表格和一句错误提示，用户能立刻重试。
        // 不开弹窗的话界面上就只剩「右键点了没反应」，最难查。
        structureDialog =
            StructureDialogState(
                draft =
                    TableStructureDraft(
                        database = target.database,
                        schema = target.schema,
                        tableName = table,
                        originalName = table,
                        isNew = false,
                    ),
                loading = true,
            )
        scope.launch {
            when (val result = loadTableStructure(target)) {
                is StructureLoadResult.Loaded -> {
                    structureDialog =
                        (structureDialog ?: return@launch).copy(draft = result.draft, loading = false, error = null)
                }

                // 草稿里已经有什么了也照样提示失败：用户在读取期间填了几个字段，
                // 直接整体换掉等于把他刚填的东西悄悄丢掉。
                is StructureLoadResult.Failed -> {
                    structureDialog = structureDialog?.copy(loading = false, error = result.message)
                }
            }
        }
    }

    /** 打开「新增表」—— 入口挂在**库节点**上（用户已拍板），表结构相关内容只挂表节点。 */
    fun openNewTableDialog(target: TreeTarget) {
        if (target.table != null || target.column != null) return
        if (!ensureWritable()) return
        val dialect = currentConnection?.dialect ?: DialectType.UNKNOWN
        scope.launch {
            val schema = if (target.schema.isNotBlank()) target.schema else resolveSchemas(target.database).firstOrNull() ?: ""
            structureDialog =
                StructureDialogState(
                    draft =
                        TableStructureDraft(
                            database = target.database,
                            schema = schema,
                            isNew = true,
                            // 开局就给一行空的：新建表至少要一个字段，而空白弹窗
                            // 只有一个「加一个字段」按钮，用户得先点一下才知道能填。
                            columns = listOf(ColumnDraft(name = "", type = defaultColumnType(dialect))),
                            refTableCandidates = refTableCandidates(target.database, ""),
                        ),
                )
        }
    }

    fun dismissStructureDialog() {
        if (structureDialog?.saving == true) return
        structureDialog = null
    }

    /**
     * 保存表结构 —— 新增走 [createTable]，编辑走 [applyTableStructure]。
     *
     * 失败时**不关弹窗**，把错误挂在弹窗上：用户填了七八列，一次失败就全被弹掉重来，
     * 比重新填一遍还让人恼火。
     */
    fun saveTableStructure(draft: TableStructureDraft) {
        val current = structureDialog ?: return
        if (current.saving) return
        structureDialog = current.copy(saving = true, error = null)
        scope.launch {
            val failure = if (draft.isNew) createTable(draft) else applyTableStructure(draft)
            if (failure != null) {
                structureDialog = structureDialog?.copy(saving = false, error = failure)
                return@launch
            }
            structureDialog = null
            val center = notifications ?: return@launch
            if (draft.isNew) {
                center.push(
                    AppNotification.Severity.SUCCESS,
                    Msg.text(StrKey.TABLE_NEW),
                    Msg.format(StrKey.TABLE_CREATED, draft.tableName.trim()),
                )
            } else {
                center.push(
                    AppNotification.Severity.SUCCESS,
                    Msg.text(StrKey.TABLE_EDIT_STRUCTURE),
                    Msg.format(StrKey.TABLE_STRUCTURE_SAVED, draft.originalName),
                )
            }
        }
    }

    /** 真正删表 —— 只由二次确认框的确认按钮调用。 */
    fun confirmDeleteTable() {
        val target = pendingDeleteTable ?: return
        pendingDeleteTable = null
        scope.launch {
            val failure = deleteTable(target)
            val center = notifications ?: return@launch
            if (failure != null) {
                center.push(AppNotification.Severity.ERROR, Msg.text(StrKey.TABLE_DELETE), failure)
            } else {
                center.push(
                    AppNotification.Severity.SUCCESS,
                    Msg.text(StrKey.TABLE_DELETE),
                    Msg.format(StrKey.TABLE_DELETED, target.table.orEmpty()),
                )
            }
        }
    }

    fun dismissDeleteTable() {
        pendingDeleteTable = null
    }

    /** 菜单项「删除表」—— 只**记下**待删的表并弹确认框，绝不在这里就发 DELETE。 */
    fun requestDeleteTable(target: TreeTarget) {
        if (target.table == null || target.column != null) return
        if (!ensureWritable()) return
        pendingDeleteTable = target
    }

    /**
     * 只读模式与方言能力的统一入口。
     *
     * 只读是**用户的意图**（工具栏上那个开关），不是权限 —— 在点菜单之前就该让它
     * 点不动，让人改半天再被告知「只读模式不能提交」纯属浪费。
     */
    private fun ensureWritable(): Boolean {
        if (!readOnly) return true
        notifications?.push(
            AppNotification.Severity.INFO,
            Msg.text(StrKey.READ_ONLY),
            Msg.text(StrKey.TABLE_BLOCKED_READ_ONLY),
        )
        return false
    }

    /**
     * 读一张表的结构，填成「编辑表结构」弹窗的草稿。
     *
     * ## 三次请求的并发与顺序
     *
     * `TABLE.COLUMN_LIST`（字段）与 `FOREIGN_KEY.LIST`（外键）**并发**发 —— 它们
     * 互不依赖，串行发等于白白多等一个往返。引用表候选则**不能**发请求：它就是
     * 当前库已展开的 `tablesByDatabase`，直接读缓存即可（见 [refTableCandidates]）。
     *
     * schema 名要等 [resolveSchemas]：与 [copyTreeDdl] 同一个坑 ——
     * 带空串发一次「成功但取不到」的请求，比直接报错更难排查。
     */
    suspend fun loadTableStructure(target: TreeTarget): StructureLoadResult {
        val table = target.table
        if (table == null || target.column != null) {
            return StructureLoadResult.Failed(Msg.text(StrKey.TABLE_NAME_EMPTY))
        }
        val schema =
            if (target.schema.isNotBlank()) {
                target.schema
            } else {
                resolveSchemas(target.database).firstOrNull() ?: ""
            }
        val config = engineConnFor(currentConnection, target.database)
        // 「长度要不要显示」按**当前连接的方言**判 —— 同一个 `size` 在不同方言下含义不同。
        val dialect = currentConnection?.dialect ?: DialectType.UNKNOWN

        val columnsResp =
            runCatching {
                engine.invoke(config) {
                    category = Category.TABLE
                    action = Action.LIST
                    tableRequest =
                        tableRequest {
                            columnList =
                                tableColumnListRequest {
                                    this.tableName = table
                                    this.schema = schema
                                }
                        }
                }
            }.getOrNull()
        if (columnsResp == null || !columnsResp.success) {
            val reason = columnsResp?.error?.takeIf { it.isNotBlank() } ?: Msg.text(StrKey.LOAD_FAILED)
            return StructureLoadResult.Failed(Msg.format(StrKey.TABLE_DIALOG_LOAD_FAILED, reason))
        }

        // 外键是**可选**信息：方言不支持外键时 `FOREIGN_KEY.LIST` 会失败，
        // 那不该让整个弹窗开不出来 —— 只是没有外键可编辑而已。
        val fkItems =
            runCatching {
                engine.invoke(config) {
                    category = Category.FOREIGN_KEY
                    action = Action.LIST
                    foreignKeyRequest =
                        foreignKeyRequest {
                            list =
                                foreignKeyListRequest {
                                    this.tableName = table
                                    this.schema = schema
                                }
                        }
                }
            }.getOrNull()
                ?.takeIf { it.success }
                ?.foreignKey
                ?.list
                ?.itemsList
                .orEmpty()

        val drafts =
            columnsResp.table.columns.itemsList.map { col ->
                ColumnDraft(
                    originalName = col.name,
                    name = col.name,
                    type = col.type,
                    // 引擎给的 `size` 对数值列是「十进制位数」（BIGINT 是 19）而不是长度 ——
                    // 照搬进长度框会显示成「BIGINT 长度 19」。按类型是否接受长度参数过滤。
                    length = ColumnTypeCatalog.lengthFor(dialect, col.type, col.size),
                    primaryKey = col.isPrimaryKey,
                    // `nullable` 是 proto3 的 optional：没给就是 null，按「可空」处理，
                    // 与引擎 mapper 的默认一致（见 TableHandler.columnList 的同名注释）。
                    nullable = if (col.hasNullable()) col.nullable else true,
                    autoIncrement = col.autoIncrement,
                    defaultValue = col.defaultValue,
                )
            }

        val fkDrafts = fkItems.map { it.toDraft() }
        return StructureLoadResult.Loaded(
            TableStructureDraft(
                database = target.database,
                schema = schema,
                tableName = table,
                originalName = table,
                isNew = false,
                columns = drafts,
                // 快照与当前列表**先给同一份**：差分要靠它认出「用户把这一列删了 /
                // 改名 / 改了类型」—— 那些改动之后当前列表里已经没有原来的定义了。
                originalColumns = drafts,
                foreignKeys = fkDrafts,
                // 快照与当前列表**先给同一份**：差分要靠它认出「用户在弹窗里整行删掉了
                // 某条外键」—— 那之后当前列表里就没有它了，只有这份快照还记得。
                originalForeignKeys = fkDrafts,
                refTableCandidates = refTableCandidates(target.database, table),
            ),
        )
    }

    /**
     * 引用表下拉的候选 —— 当前库里除**自己**以外的表。
     *
     * 为什么排除自己：自引用外键（`users.parent_id → users.id`，树形结构很常见）
     * 在数据库里完全合法，把候选排掉会让用户以为这个功能不支持自引用。
     * 真要自引用时用 [ColumnDraft.ForeignKeyDefinition] 那条路径之外的显式输入。
     */
    fun refTableCandidates(
        database: String,
        selfTable: String,
    ): List<String> = tablesByDatabase[database].orEmpty().filter { !it.equals(selfTable, ignoreCase = true) }

    /**
     * 某张候选引用表的列名 —— 给弹窗里「被引用字段」的下拉用。
     *
     * 库与 schema 从弹窗当前草稿里取（弹窗知道自己在改哪张表），这里只负责发请求。
     */
    suspend fun refColumnsFor(
        database: String,
        schema: String,
        refTable: String,
    ): List<String> = loadRefColumns(database, schema, refTable)

    /**
     * 读某张表的列名 —— 给「被引用字段」下拉用。
     *
     * 为什么不一次性把所有候选表的列都拉回来：一个库几百张表 × 每张几十列就是
     * 上万行元数据，而用户这一路通常只点开一两个引用表。按需查一次更快。
     */
    suspend fun loadRefColumns(
        database: String,
        schema: String,
        table: String,
    ): List<String> {
        if (table.isBlank()) return emptyList()
        val resp =
            runCatching {
                engine.invoke(engineConnFor(currentConnection, database)) {
                    category = Category.TABLE
                    action = Action.LIST
                    tableRequest =
                        tableRequest {
                            columnList =
                                tableColumnListRequest {
                                    this.tableName = table
                                    this.schema = schema
                                }
                        }
                }
            }.getOrNull()
        return resp
            ?.takeIf { it.success }
            ?.table
            ?.columns
            ?.itemsList
            ?.map { it.name }
            .orEmpty()
    }

    /**
     * 新建表 —— `TABLE.CREATE` 加后续的外键，整批一个事务。
     *
     * ## 为什么建表之后还要单独建外键
     *
     * `TableCreateRequest` 只收 `columns`，没有外键字段（建表语句里带
     * `FOREIGN KEY` 的那种写法在各方言上差异太大，交给引擎统一处理反而更稳）。
     * 所以流程是「先建表，成功后再逐条 `FOREIGN_KEY.CREATE`」。
     *
     * ## 中途失败的残留
     *
     * 整批走一个事务，但**能不能真回滚取决于方言** —— MySQL 与 H2 对 DDL 隐式提交
     * （见 `DdlTransactionIntegrationTest`），失败时会留下一张没有外键的表。
     * 这一点由界面按方言能力提示，不在这里假装能保证。
     */
    suspend fun createTable(draft: TableStructureDraft): String? {
        draft.validate()?.let { return it }
        val name = draft.tableName.trim()
        val config = engineConnFor(currentConnection, draft.database)
        val (sessionId, beginError) = beginEditSession(draft.database)
        if (sessionId == null) return beginError

        // 建表是**一条** `CREATE TABLE` 带全部列 —— 引擎的 TableCreateRequest 本来就是
        // `columns` 数组。逐列发 CREATE 的话，第二条必然 `Table 'x' already exists`，
        // 于是「两列以上的表」永远建不出来（单列表恰好能过，把这个 bug 藏住了）。
        val total = 1 + draft.foreignKeys.count { !it.isReadOnly }
        // ⚠️ 这里**不能**再套一层 `?: 失败文案`。
        //
        // [runDdl] 的契约是「成功返回 null，失败返回引擎原文」—— **null 才是成功**。
        // 早先写成 `runDdl(...) ?: Msg.format(TABLE_CREATE_FAILED, name)`，
        // 于是 elvis 把「成功(null)」翻译成了「建表失败」：
        //
        //   · CREATE 明明执行成功、表真的建出来了
        //   · 紧接着走进 [rollbackDdlBatch] → 回滚事务会话
        //   · MySQL 的 DDL 是隐式提交的，回滚**撤不掉**那张表
        //   · 用户看到的是「表建好了，但界面报建表失败，弹窗不关」
        //
        // 而「单列无自增主键」的建表恰好能过（早期逐列 CREATE 的写法），
        // 多列表却被这条反向 elvis 判死 —— 两者叠在一起，让「建表」这条链路
        // 在界面上表现为「有时成功有时失败」，极难定位。
        val createFailure =
            runDdl(config, sessionId) {
                category = Category.TABLE
                action = Action.CREATE
                this.sessionId = sessionId
                tableRequest =
                    tableRequest {
                        create =
                            tableCreateRequest {
                                tableName = name
                                this.schema = draft.schema
                                draft.columns.forEach { columns += it.toProto() }
                            }
                    }
            }
        if (createFailure != null) return rollbackDdlBatch(draft.database, sessionId, 1, total, createFailure)

        var index = 1
        for (fk in draft.foreignKeys.filter { !it.isReadOnly }) {
            index++
            val failure = runDdl(config, sessionId) { fkRequest(name, fk, draft.schema, sessionId) }
            if (failure != null) return rollbackDdlBatch(draft.database, sessionId, index, total, failure)
        }
        val commit = commitEditSession(draft.database, sessionId)
        if (commit != null) return Msg.format(StrKey.TABLE_CREATE_FAILED, commit)
        refreshTables(draft.database)
        return null
    }

    /**
     * 保存已有表的结构 —— 按 [TableStructureDraft.diff] 算出的差分逐条执行，整批一个事务。
     *
     * @return `null` = 成功；非空 = 该给用户看的失败文案。
     */
    suspend fun applyTableStructure(draft: TableStructureDraft): String? {
        draft.validate()?.let { return it }
        val changes = draft.diff()
        if (changes.isEmpty()) return Msg.text(StrKey.TABLE_STRUCTURE_NO_CHANGE)
        val table = draft.originalName.ifBlank { draft.tableName.trim() }
        val config = engineConnFor(currentConnection, draft.database)
        val (sessionId, beginError) = beginEditSession(draft.database)
        if (sessionId == null) return beginError

        changes.forEachIndexed { index, change ->
            val failure =
                when (change) {
                    is StructureChange.AddColumn -> {
                        runDdl(config, sessionId) {
                            category = Category.TABLE
                            action = Action.UPDATE
                            this.sessionId = sessionId
                            tableRequest =
                                tableRequest {
                                    update =
                                        tableUpdateRequest {
                                            tableName = table
                                            this.schema = draft.schema
                                            operation = "ADD_COLUMN"
                                            column = change.column.toProto()
                                        }
                                }
                        }?.let { Msg.format(StrKey.TABLE_SAVE_FAILED, it) }
                    }

                    is StructureChange.ModifyColumn -> {
                        runDdl(config, sessionId) {
                            category = Category.TABLE
                            action = Action.UPDATE
                            this.sessionId = sessionId
                            tableRequest =
                                tableRequest {
                                    update =
                                        tableUpdateRequest {
                                            tableName = table
                                            this.schema = draft.schema
                                            operation = "MODIFY_COLUMN"
                                            column = change.after.toProto(modifying = change.before.trimmedName)
                                        }
                                }
                        }?.let { Msg.format(StrKey.TABLE_SAVE_FAILED, it) }
                    }

                    is StructureChange.DropColumn -> {
                        runDdl(config, sessionId) {
                            category = Category.TABLE
                            action = Action.UPDATE
                            this.sessionId = sessionId
                            tableRequest =
                                tableRequest {
                                    update =
                                        tableUpdateRequest {
                                            this.tableName = table
                                            this.schema = draft.schema
                                            operation = "DROP_COLUMN"
                                            columnName = change.name
                                        }
                                }
                        }?.let { Msg.format(StrKey.TABLE_SAVE_FAILED, it) }
                    }

                    is StructureChange.AddForeignKey -> {
                        runDdl(config, sessionId) { fkRequest(table, change.fk, draft.schema, sessionId) }
                            ?.let { Msg.format(StrKey.TABLE_SAVE_FAILED, it) }
                    }

                    is StructureChange.DropForeignKey -> {
                        runDdl(config, sessionId) {
                            category = Category.FOREIGN_KEY
                            action = Action.DELETE
                            this.sessionId = sessionId
                            foreignKeyRequest =
                                foreignKeyRequest {
                                    delete =
                                        foreignKeyDeleteRequest {
                                            this.tableName = table
                                            fkName = change.name
                                            this.schema = draft.schema
                                            ifExists = true
                                        }
                                }
                        }?.let { Msg.format(StrKey.TABLE_SAVE_FAILED, it) }
                    }
                }
            if (failure != null) return rollbackDdlBatch(draft.database, sessionId, index + 1, changes.size, failure)
        }
        val commit = commitEditSession(draft.database, sessionId)
        if (commit != null) return Msg.format(StrKey.TABLE_SAVE_FAILED, commit)
        refreshTables(draft.database)
        // 表结构变了，已打开的预览页的列、能力位全过时了 —— 重载一次，否则用户
        // 看到的还是旧列，而库里已经是新的了。tab 不带 database 字段（全局唯一键是
        // `schema::tableName`），所以只能按 schema + 表名匹配。
        tabs
            .filter { it.schema.equals(draft.schema, ignoreCase = true) && it.tableName.equals(table, ignoreCase = true) }
            .forEach { loadTabPreview(it) }
        return null
    }

    /** 删除表。调用方必须已经做过二次确认（删表不可逆）。 */
    suspend fun deleteTable(target: TreeTarget): String? {
        val table = target.table ?: return Msg.text(StrKey.TABLE_NAME_EMPTY)
        val schema =
            if (target.schema.isNotBlank()) {
                target.schema
            } else {
                resolveSchemas(target.database).firstOrNull() ?: ""
            }
        val resp =
            runCatching {
                engine.invoke(engineConnFor(currentConnection, target.database)) {
                    category = Category.TABLE
                    action = Action.DELETE
                    tableRequest =
                        tableRequest {
                            delete =
                                tableDeleteRequest {
                                    this.tableName = table
                                    this.schema = schema
                                    ifExists = true
                                }
                        }
                }
            }.getOrNull()
        if (resp == null || !resp.success) {
            return Msg.format(
                StrKey.TABLE_DELETE_FAILED,
                resp?.error?.takeIf { it.isNotBlank() } ?: Msg.text(StrKey.OP_NO_ENGINE_RESPONSE),
            )
        }
        // 关掉这张表的预览标签页：它已经不存在了，留着只会让用户点进去看到一个报错页。
        val stale = tabs.filter { it.schema.equals(schema, ignoreCase = true) && it.tableName.equals(table, ignoreCase = true) }
        if (stale.isNotEmpty()) {
            tabs = tabs.filterNot { it in stale }
            if (selectedTabIndex >= tabs.size) selectedTabIndex = tabs.size - 1
        }
        refreshTables(target.database)
        return null
    }

    /**
     * 删表 / 改表结构 / 建表之后刷新树。
     *
     * ## ⚠️ 早先这里调的是 [toggleDatabase] —— 那是**错的**
     *
     * `toggleDatabase` 的语义是「切换展开态」：库本来就展开着的时候调用它，
     * 效果是**把树折叠起来**。于是「建完表 → 树合上了 → 新表也不在列表里」，
     * 用户看到的就是「表建了，界面却像什么都没发生，还把我摊开的树收走了」。
     *
     * 现在改成：保持展开态不变，并**重新拉一次表列表**。
     * 不刷新的话，库里已经有那张表了，树里却是旧的快照。
     */
    private fun refreshTables(database: String) {
        if (!expandedDatabases.contains(database)) {
            expandedDatabases.add(database)
        }
        if (!loadingTables.contains(database)) {
            loadTables(database)
        }
    }

    /**
     * 发一条 DDL —— 成功返回 `null`，失败返回**引擎原文**。
     *
     * 失败一律用原文而不是自己编一句：DDL 出错的种类太多（约束冲突、类型不兼容、
     * 列被外键引用、权限不足……），转述必然丢掉最有用的那部分。
     */
    private suspend fun runDdl(
        config: com.langbiantianya.grpc.ConnectionConfig,
        sessionId: String,
        // 接收者是 `RequestKt.Dsl` 而不是 `Request.Builder`：`engine.invoke` 给的
        // 就是它（`import ...grpc.tableRequest {}` 这类 DSL 挂在这个类上）。
        block: com.langbiantianya.grpc.RequestKt.Dsl.() -> Unit,
    ): String? {
        val resp =
            runCatching {
                engine.invoke(config) { block() }
            }.getOrNull()
        if (resp == null) return Msg.text(StrKey.OP_NO_ENGINE_RESPONSE)
        if (!resp.success) return resp.error.ifBlank { Msg.text(StrKey.OP_NO_ENGINE_RESPONSE) }
        return null
    }

    /** 造一条 `FOREIGN_KEY.CREATE` 请求。[runDdl] 的 lambda 收的就是 `RequestKt.Dsl`。 */
    private fun com.langbiantianya.grpc.RequestKt.Dsl.fkRequest(
        table: String,
        fk: ForeignKeyDraft,
        schema: String,
        sessionId: String,
    ) {
        category = Category.FOREIGN_KEY
        action = Action.CREATE
        this.sessionId = sessionId
        foreignKeyRequest =
            foreignKeyRequest {
                create =
                    foreignKeyCreateRequest {
                        tableName = table
                        fkName = fk.name.trim()
                        columns += fk.column
                        refTable = fk.refTable
                        refColumns += fk.refColumn
                        if (fk.onDelete.isNotBlank()) onDelete = fk.onDelete
                        if (fk.onUpdate.isNotBlank()) onUpdate = fk.onUpdate
                        this.schema = schema
                    }
            }
    }

    /**
     * 批处理中途失败时的收尾：先回滚事务会话，再把「第几条 / 共几条 + 引擎原文」
     * 拼成一句给用户看的话。
     *
     * 回滚本身也可能失败（连接已断、方言不支持 DDL 回滚），那种情况下**只报回滚
     * 失败**——说成「已回滚」就是在骗用户，比报错更糟。
     */
    private suspend fun rollbackDdlBatch(
        database: String,
        sessionId: String,
        index: Int,
        total: Int,
        failure: String,
    ): String {
        val rollback = rollbackEditSession(database, sessionId)
        return if (rollback != null) {
            Msg.format(StrKey.EDIT_ROLLBACK_FAILED, rollback)
        } else {
            Msg.format(StrKey.EDIT_APPLY_ROLLED_BACK, index, total, failure)
        }
    }

    /**
     * [ColumnDraft] → proto [com.langbiantianya.grpc.ColumnDef]。
     *
     * @param modifying 非空 = 这是 `MODIFY_COLUMN`，此时 `name` 必须填**旧名**
     *   （引擎按旧名定位列），新名走 [com.langbiantianya.grpc.ColumnDef.newName]。
     *
     * 为什么不把改名做成「删列 + 加列」：那样中途失败会真的丢掉整列数据，
     * 而 `MODIFY_COLUMN` 在各方言上都是一条原子操作。
     *
     * 值全部先提到局部变量再建 builder：`columnDef { }` 里 `name` / `type` /
     * `nullable` 这些名字会解析到 builder 自己的属性，直接写 `type = type` 是自赋值。
     */
    private fun ColumnDraft.toProto(modifying: String? = null): com.langbiantianya.grpc.ColumnDef {
        val targetName = if (modifying != null) modifying else trimmedName
        val renamed = modifying != null && !trimmedName.equals(modifying, ignoreCase = true)
        val typeText = type.trim()
        val sizeValue = lengthOrZero
        val pk = primaryKey
        val auto = autoIncrement
        val nullableFlag = nullable
        val default = defaultValue.trim()
        val newLabel = trimmedName
        return com.langbiantianya.grpc.columnDef {
            name = targetName
            type = typeText
            size = sizeValue
            isPrimaryKey = pk
            this.autoIncrement = auto
            nullable = nullableFlag
            if (default.isNotEmpty()) defaultValue = default
            if (renamed) newName = newLabel
        }
    }

    private fun com.langbiantianya.grpc.ForeignKeyListItem.toDraft(): ForeignKeyDraft {
        // 复合外键的 columns / refColumns 都不止一个。界面只支持单列，
        // 于是把原始列名留空 —— [ForeignKeyDraft.isReadOnly] 会把它变成只读行。
        val single = columnsList.size == 1 && refColumnsList.size == 1
        return ForeignKeyDraft(
            name = name,
            original =
                ForeignKeyDraft.ForeignKeyDefinition(
                    name = name,
                    column = if (single) columnsList.first() else "",
                    refTable = refTable,
                    refColumn = if (single) refColumnsList.first() else "",
                    onDelete = onDelete.ifBlank { "NO ACTION" },
                    onUpdate = onUpdate.ifBlank { "NO ACTION" },
                ),
            column = if (single) columnsList.first() else "",
            refTable = refTable,
            refColumn = if (single) refColumnsList.first() else "",
            onDelete = onDelete.ifBlank { "NO ACTION" },
            onUpdate = onUpdate.ifBlank { "NO ACTION" },
        )
    }

    /** 打开的标签页 */
    var tabs: List<TablePreviewTab> by mutableStateOf(emptyList())
        private set

    var selectedTabIndex: Int by mutableStateOf(-1)
        private set

    /**
     * 当前选中的连接 —— 由 [DatabaseBrowserScreen] 通过 [bindConnection] 注入。
     *
     * 是 Compose **快照状态**而不只是普通字段：SQL 工作台的高亮档位由它派生，连接（方言）变化
     * 必须触发重组，否则切连接后关键字表还停在上一个方言上。
     */
    var currentConnection: ConnectionConfig? by mutableStateOf(null)
        private set

    /**
     * 会话代次 —— 每次切换连接自增。
     *
     * 异步查询在挂起点之后先比对本值：若已切换连接，丢弃该响应（避免上一个连接的
     * in-flight 结果落到新连接的已清空状态里）。
     */
    private var generation: Int = 0

    // ------------------------------------------------------------------------
    // SQL 工作台
    // ------------------------------------------------------------------------

    /** 当前右栏展示的 pane —— 决定渲染表预览 / SQL 工作台 / 造数工作台。每 sheet 独立。 */
    var activePane: BrowserPane by mutableStateOf(BrowserPane.TABLE)

    /** 切换 pane。工具栏按钮调用 —— 只改渲染目标，不动任何工作台状态。 */
    fun selectPane(pane: BrowserPane) {
        activePane = pane
    }

    /**
     * 单个 SQL sheet —— 一份 SQL 文本 + 该 sheet 自己的执行结果。
     *
     * 与 [GenerateScript] 同构：
     * - [editor] 由状态机持有（文本 + 光标 / 选区 + 滚动），切 pane / 切 sheet 后保持不变
     * - 执行结果（[running] / [columns] / [rows] / [affectedRows] / [error] / 结果区视图）
     *   **归属单个 sheet** —— 切到另一个 sheet 不会串显上一次的结果
     * - [generation] 是本 sheet 的 in-flight 失效代次：重新执行 / 删除时自增，
     *   迟到的行帧据此丢弃（不能让上一轮响应覆盖新一轮状态）
     */
    class SqlSheet(
        title: String,
    ) {
        /** 标签条上展示的名字（默认 `SQL N`）—— 用户可重命名，见 [renameSqlSheet]。 */
        var title: String by mutableStateOf(title)

        val editor: CodeEditorState = CodeEditorState()

        /** 该 sheet 是否正在执行（控制「执行 SQL」按钮与结果区 spinner）。 */
        var running: Boolean by mutableStateOf(false)

        /** 最近一次执行结果（成功后回填；失败时为 null + [error]）。 */
        var columns: List<TableColumn> by mutableStateOf(emptyList())
        var rows: List<TableRow> by mutableStateOf(emptyList())
        var rowCount: Int by mutableStateOf(0)

        /** 非 SELECT（DML/DDL）执行成功时记录受影响行数；为 null 表示 SELECT 或未执行。 */
        var affectedRows: Int? by mutableStateOf(null)

        /**
         * 本轮执行的引擎 **request id** —— 「停止」按钮的凭据。
         *
         * 引擎侧 `SYSTEM.CANCEL` 按 `target_request_id` 找正在跑的 `Statement` 并调
         * `Statement.cancel()`（协程取消打断不了阻塞中的 JDBC 调用，只有它能）。
         * id 由 [DatabaseBrowserState.executeSql] 在构造请求时生成 —— 此前它生成完就被丢弃，
         * 前端**根本没法取消**一条跑了十分钟的查询。
         *
         * 生命周期：请求发出前写入，任何结束路径（成功 / 失败 / 异常 / 代次作废）都清空。
         * 非 null 是「可停止」的唯一判据，不另开一个 `cancellable` 标志 —— 两个字段必然漂移。
         */
        var runningRequestId: String? by mutableStateOf(null)

        /**
         * 是否按**多语句脚本**执行（`SqlExecuteRequest.multi_statement`）。
         *
         * 引擎侧的 `SqlScriptSplitter`（v2.16）只切**顶层** `;`，逐条执行、首个失败即停，
         * 并为每条单独登记 `Statement` 以便取消 —— 能力早就齐了，此前前端**硬编码
         * `multiStatement = false`** 让它完全用不上。
         *
         * 做成**逐 sheet 的开关**而不是全局默认开：多语句意味着「一次点执行会改多张表」，
         * 与「选中一段就跑这一段」的心智模型冲突，用户得能对某个脚本明确表态。
         */
        var multiStatement: Boolean by mutableStateOf(false)

        /**
         * 本 sheet 当前是否挂在一个**事务会话**里（`SYSTEM.BEGIN` 之后）。
         *
         * 事务会话在引擎侧给该 `session_id` **钉住一条连接**（`autocommit=false`），
         * 后续请求全落在这条连接上，直到 `COMMIT` / `ROLLBACK`。
         * 前端必须自己持有这个 `sessionId` —— 引擎不会把「你上次开的事务」记在某处。
         */
        var transactionSessionId: String? by mutableStateOf(null)

        /**
         * 结果集是否被 [SQL_RESULT_MAX_ROWS] 截断。
         *
         * 与 `rowCount` 分开存：截断时 `rowCount` 只是**已收到**的行数，
         * 把它当成总数会让分页栏显示「第 1 / 60 页」而点不出后面的页 —— 用户以为数据丢了。
         */
        var rowsTruncated: Boolean by mutableStateOf(false)

        /** 最近一次执行错误（连接失败 / SQL 语法 / 引擎抛异常）。 */
        var error: String? by mutableStateOf(null)

        /**
         * 最近一次**导出**的结果提示（文件名 / 成功条数）。
         *
         * 与 [error] 分开存，理由同 [TablePreviewTab.notice]：导出成功不该把
         * 执行结果区顶成「读取失败」，而导出失败也不该被下一次 SELECT 前的清空抹掉。
         */
        var notice: String? by mutableStateOf(null)

        /**
         * [notice] 是成还是败 —— 理由与写法见 [TablePreviewTab.noticeIsError]。
         *
         * 多语句导出有「部分成功」这一档（`EXPORT_PARTIAL_FILES`），它既不是纯成功
         * 也不是纯失败。这里染错误色：用户要知道有东西没导出来。
         */
        var noticeIsError: Boolean by mutableStateOf(false)

        /** 结果区视图状态（分页 / 选中行）—— 每次重新执行归位。 */
        var resultPage: Int by mutableStateOf(1)
        var resultPageSize: PageSize by mutableStateOf(PageSize.S100)
        var selectedRowId: Any? by mutableStateOf(null)

        /** in-flight 失效代次（见类 KDoc）。 */
        internal var generation: Int = 0
    }

    /** SQL sheet 列表（顺序仅决定标签条顺序 —— 各 sheet 彼此独立执行）。 */
    val sqlSheets: SnapshotStateList<SqlSheet> = mutableStateListOf(SqlSheet("SQL 1"))

    /** 当前选中的 SQL sheet 下标（越界表示无 sheet）。 */
    var selectedSqlIndex: Int by mutableStateOf(0)

    /** 当前选中的 SQL sheet（越界时为 null —— 例如用户删掉了最后一个 sheet）。 */
    fun currentSqlSheet(): SqlSheet? = sqlSheets.getOrNull(selectedSqlIndex)

    /** 新增一个 SQL sheet 并选中它。 */
    fun addSqlSheet() {
        sqlSheets.add(SqlSheet("SQL ${sqlSheets.size + 1}"))
        selectedSqlIndex = sqlSheets.lastIndex
    }

    /** 删除 SQL sheet；删掉后选中项跟随回退（与 [removeGenerateScript] 同策略）。 */
    fun removeSqlSheet(index: Int) {
        if (index !in sqlSheets.indices) return
        // 该 sheet 可能正在执行 —— 自增代次让其 in-flight 行帧作废
        sqlSheets[index].generation++
        sqlSheets.removeAt(index)
        selectedSqlIndex =
            when {
                sqlSheets.isEmpty() -> -1
                index >= sqlSheets.size -> sqlSheets.lastIndex
                else -> index
            }
    }

    fun selectSqlSheet(index: Int) {
        if (index in sqlSheets.indices) selectedSqlIndex = index
    }

    /** 重命名 SQL sheet —— 去首尾空白；空名（或纯空白）视为无效，保持原名。 */
    fun renameSqlSheet(
        index: Int,
        title: String,
    ) {
        val sheet = sqlSheets.getOrNull(index) ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        sheet.title = trimmed
    }

    /** 当前连接下激活的 schema —— SQL 执行时作为 catalog 写入 proto config。
     *  默认取第一个展开的数据库，若无则为 `""`（默认 catalog）。 */
    fun currentSchema(): String = expandedDatabases.firstOrNull() ?: ""

    /**
     * SQL 工作台的高亮档位 —— 按**当前连接的方言**选关键字 / 类型 / 内置函数词表。
     *
     * 未连接 / 未知方言 → 标准 SQL 档位（`"sql"`）。返回值同时决定编辑器的 `languageId`
     * （高亮 + 格式化）与标题条上显示的方言名。
     */
    fun sqlDialectProfile(): SqlDialectProfile = currentConnection?.dialect?.toSqlDialectProfile() ?: SqlDialectProfile.STANDARD

    /**
     * SQL 工作台补全用的 schema 候选 —— 库 → 表 → 字段，全部**来自已有状态，零额外请求**。
     *
     * ## 覆盖范围（与「不额外打扰数据库」这个前提绑定）
     *
     * | 类别 | 来源 | 何时有 |
     * |---|---|---|
     * | 库 | [databases]（`SCHEMA.LIST`） | 连接建立时 |
     * | 表 | [tablesByDatabase]（`TABLE.LIST`） | 展开该库时（复用树上的懒加载结果） |
     * | 字段 | [tabs] 里各 tab 的 `columns` | **打开过该表的预览**时 |
     *
     * 字段只覆盖「访问过的表」是刻意的：全库全表意味着对每张表发一次
     * `TABLE.COLUMN_LIST` —— 一个 200 张表的库就是 200 次串行往返，而用户在敲 `sel`
     * 时根本用不到其中 99%。这里选择零等待、零额外往返，用得越多越全。
     *
     * ## 排序：库 → 表 → 字段 → （语言关键字）
     *
     * 库/表/字段整体**优先于**关键字，因为 `FROM us` 想要的是 `users` 这张表而不是
     * `USING` 这个关键字。同类内部保持引擎返回的顺序，不擅自重排 —— 引擎的排序未必
     * 字典序，改了会让人对不上 `SHOW TABLES` 的结果。
     *
     * ## 去重
     *
     * 表名**不去重**：不同库可能有同名表，标签都是裸表名（SQL 里 `FROM t` 就是裸名），
     * 但 [CompletionItem.detail] 标出所属库 —— 两条都留着，用户能分辨。
     */
    fun sqlSchemaCompletions(): List<CompletionItem> {
        val out = ArrayList<CompletionItem>()

        for (db in databases) {
            if (db.isNotBlank()) out.add(CompletionItem(db, CompletionKind.DATABASE, detailKey = StrKey.CATALOG))
        }
        // 「表」这个片段含运行时值（库名），拼装后才成句 —— 造键会把库名焊死，
        // 所以静态那半边取译文再拼，`detail` 而不是 `detailKey`。
        val tableWord = Msg.text(StrKey.TABLE)
        for ((db, tables) in tablesByDatabase) {
            val tableHint = if (db.isBlank()) tableWord else "$db · $tableWord"
            for (t in tables) {
                if (t.isNotBlank()) out.add(CompletionItem(t, CompletionKind.TABLE, detail = tableHint))
            }
        }
        for (tab in tabs) {
            if (tab.columns.isEmpty()) continue
            val tableLabel = if (tab.schema.isBlank()) tab.tableName else "${tab.schema}.${tab.tableName}"
            for (col in tab.columns) {
                val name = col.key
                if (name.isBlank()) continue
                out.add(
                    CompletionItem(
                        label = name,
                        kind = CompletionKind.COLUMN,
                        detail = if (col.header.isBlank()) tableLabel else "$tableLabel · ${col.header}",
                    ),
                )
            }
        }
        return out
    }

    /**
     * 执行**当前 SQL sheet** 编辑器中的 SQL —— 走 `Category.SQL` / `Action.EXECUTE` 流式通道：
     * SELECT 行帧 → 攒成该 sheet 的 [SqlSheet.columns] / [SqlSheet.rows] / [SqlSheet.rowCount]；
     * 终止帧携带 `execute.affected_rows` 时填入 [SqlSheet.affectedRows]；失败 → [SqlSheet.error]。
     *
     * 与预览加载一样，in-flight 响应通过该 sheet 自己的 [SqlSheet.generation] 失效化：
     * 开始新一次执行前自增，collect 过程中比对，若不相等直接丢弃
     * （避免上一次执行的迟到响应覆盖新一轮状态）。
     */
    fun executeSql() {
        val sheet = currentSqlSheet() ?: return
        currentConnection ?: return
        val sql = sheet.editor.text.trim()
        // 只读模式先拦：拦在**发出去之前**，引擎那边什么都没发生，
        // 用户看到的是一句人话而不是引擎异常
        rejectIfReadOnly(sql)?.let {
            sheet.error = it
            return
        }
        if (sql.isEmpty()) {
            sheet.error = Msg.text(StrKey.SQL_EMPTY)
            sheet.columns = emptyList()
            sheet.rows = emptyList()
            sheet.rowCount = 0
            sheet.affectedRows = null
            return
        }
        sheet.generation++
        val gen = sheet.generation
        sheet.running = true
        sheet.error = null
        sheet.affectedRows = null
        sheet.columns = emptyList()
        sheet.rows = emptyList()
        sheet.rowCount = 0
        sheet.rowsTruncated = false
        // 新一轮结果 → 视图归位（页码 / 选中行都是上一批数据的，指过去没有意义）
        sheet.resultPage = 1
        sheet.selectedRowId = null
        // request id **必须在 launch 之前**生成并落到 sheet 上，而不是在协程体内。
        //
        // 两个原因：① 「停止」按钮的可用性判据就是它，协程体内的赋值意味着
        // `executeSql()` 返回的那一刻它还是 null —— 用户点得再快也赶不上，表现为
        // 「刚点执行、停止按钮要等一会儿才冒出来」；② 引擎侧 `SYSTEM.CANCEL` 靠它找
        // 正在跑的 `Statement`，id 晚一步就可能晚于请求真正开始的那一步。
        val requestId = UUID.randomUUID().toString()
        sheet.runningRequestId = requestId
        scope.launch {
            val schema = currentSchema()
            // 本请求在 catalog = schema 维度上建池（schema 字段本身留空，见下方注释），
            // 因此登记进 activeDatabases —— 否则 close-sheet 的 releasePools 遍历不到它，
            // 池会一直挂着到 JVM 退出。
            activeDatabases.add(schema)
            val result =
                runCatching {
                    engine.handle(
                        request {
                            id = requestId
                            this.connection = engineConn(database = schema)
                            // 事务会话：非空时引擎把本次执行钉在 [beginTransaction] 开的那条
                            // 连接上（`autocommit=false`），COMMIT / ROLLBACK 之前不会落盘。
                            // 留空 = 无事务，每条语句独立提交（原有行为）。
                            sessionId = sheet.transactionSessionId.orEmpty()
                            category = Category.SQL
                            action = Action.EXECUTE
                            sqlRequest =
                                sqlRequest {
                                    execute =
                                        sqlExecuteRequest {
                                            this.sql = sql
                                            // **不要**把 catalog（数据库）名写进 schema —— 引擎会把
                                            // req.schema 传给 PoolManager.getConnection → setSearchPath，
                                            // 对 H2 是 `SET SCHEMA "<库名>"` → `Schema "X" not found`，
                                            // 对 PG 是把库名当 schema 设进 search_path。见 engineConnFor 的 KDoc。
                                            this.schema = ""
                                            // 逐 sheet 的开关（见 [SqlSheet.multiStatement]）。引擎侧
                                            // `SqlScriptSplitter` 只切顶层 `;`，引号 / 注释 / PG 美元引用里
                                            // 的分号不会被误切。
                                            multiStatement = sheet.multiStatement
                                        }
                                }
                        },
                    )
                }
            if (gen != sheet.generation) return@launch // 用户已重新执行 —— 丢弃过期响应
            val flow = result.getOrNull()
            if (flow == null) {
                sheet.error = result.exceptionOrNull()?.message ?: Msg.text(StrKey.EXECUTE_FAILED)
                sheet.running = false
                sheet.runningRequestId = null
                return@launch
            }
            val frames = mutableListOf<com.langbiantianya.grpc.SqlSelectRowFrame>()
            var truncated = false
            try {
                // `collect` + 取消标记：`collectWhile` 是 kotlinx.coroutines 内部 API 不可用。
                // 过期时置 cancelled 并停止累积 —— 流本身仍会被消费到结束（引擎侧已在推帧，
                // 主动 cancel 属于引擎能力范畴），但不再往内存里攒行。
                var cancelled = false
                flow.collect { resp ->
                    if (cancelled) return@collect
                    if (gen != sheet.generation) {
                        cancelled = true
                        return@collect
                    }
                    if (!resp.success) {
                        sheet.error = resp.error.ifBlank { Msg.text(StrKey.SQL_EXECUTION_FAILED) }
                    } else if (resp.hasSqlRowFrame()) {
                        // 攒够上限就停：**继续收只会在堆里再压一份同样的数据**，
                        // 引擎侧仍会推完（已发的帧收不回来），但我们不再留。
                        if (frames.size >= SQL_RESULT_MAX_ROWS) {
                            truncated = true
                            cancelled = true
                            return@collect
                        }
                        frames += resp.sqlRowFrame
                    } else if (resp.hasSql() && resp.sql.hasExecute()) {
                        sheet.affectedRows = resp.sql.execute.affectedRows
                    }
                }
            } catch (e: Exception) {
                // **两行都要在代次守卫内**。running 是「这个 sheet 正在执行」的单一真相源，
                // 一旦过期请求把它抹成 false，用户会看到转圈消失、以为没在跑，于是**再点一次执行** ——
                // 两次执行叠在一起，后一次的结果被后一代次接管，前一次的错误也再没机会显示。
                // 守卫外的写法正是在制造这个「看起来空闲、实际在跑」的空窗。
                if (gen == sheet.generation) {
                    sheet.error = e.message ?: e.javaClass.simpleName
                    sheet.running = false
                    sheet.runningRequestId = null
                }
                return@launch
            }
            if (gen != sheet.generation) return@launch
            // 把 SqlSelectRowFrame 攒成 columns/rows —— 列名取首帧 keys，后续帧按相同顺序补值
            if (frames.isNotEmpty()) {
                val columnNames = frames.flatMap { it.row.valuesMap.keys }.distinct()
                sheet.columns = columnNames.map { TableColumn(key = it, header = it) }
                sheet.rows =
                    frames.mapIndexed { idx, frame ->
                        val idCell =
                            frame.row.valuesMap.entries
                                .firstOrNull { (k, _) -> k.equals("id", ignoreCase = true) }
                        TableRow(
                            id = idCell?.let { cellValueAsId(it.value) } ?: idx,
                            cells = frame.row.valuesMap.mapValues { (_, v) -> cellValueToAny(v) },
                        )
                    }
                sheet.rowCount = frames.size
                sheet.rowsTruncated = truncated
            } else if (sheet.affectedRows == null && sheet.error == null) {
                sheet.error = Msg.text(StrKey.NO_RETURN_RESULT)
            }
            sheet.running = false
            sheet.runningRequestId = null
        }
    }

    /**
     * 停止当前 sheet 正在跑的 SQL —— 发 `SYSTEM.CANCEL`（v2.16 已有，此前前端从未接上）。
     *
     * ## 为什么「停止」必须走引擎而不能只取消协程
     *
     * 跑在 `Dispatchers.IO` 上的 `rs.next()` 即使协程被取消也**继续阻塞** ——
     * 真正能停掉数据库侧工作的只有 `Statement.cancel()`，那正是 `StatementRegistry`
     * 在引擎侧做的事。前端取消协程的结果是：转圈没了，数据库还在满负荷跑。
     *
     * 取消成功后，引擎会在**原 request id** 上回一帧 `success=false, error="cancelled"`，
     * 由 [executeSql] 的 collect 落进 [SqlSheet.error] —— 因此这里**不**自己写状态，
     * 避免两条路径各写一次、出现「显示已停止但引擎还在跑」或反之。
     */
    fun cancelSql() {
        val sheet = currentSqlSheet() ?: return
        val requestId = sheet.runningRequestId ?: return
        val schema = currentSchema()
        scope.launch {
            val resp =
                runCatching {
                    engine.invoke(engineConn(database = schema)) {
                        category = Category.SYSTEM
                        action = Action.CANCEL
                        systemRequest = systemRequest { targetRequestId = requestId }
                    }
                }
            // 只在**没找到目标**时补一句提示：请求已经自己结束时引擎会回 cancelled=false，
            // 那不是错误，那正是「已经跑完了」。真正失败（gRPC 断了等）才写 error。
            val cancelResp = resp.getOrNull()?.system?.cancel
            if (resp.isFailure) {
                sheet.error = Msg.format(StrKey.CANNOT_STOP, resp.exceptionOrNull()?.message ?: Msg.text(StrKey.UNKNOWN_ERROR))
            } else if (cancelResp != null && !cancelResp.cancelled && cancelResp.error.isNotBlank()) {
                sheet.error = Msg.format(StrKey.CANNOT_STOP, cancelResp.error)
            }
        }
    }

    // ------------------------------------------------------------------------
    // 数据编辑（表预览）
    // ------------------------------------------------------------------------

    /**
     * 该表预览是否**可编辑**。
     *
     * ## 判据：引擎说能改，且当前不是只读
     *
     * [DataTable] 的编辑需要一个能**唯一定位行**的 `WHERE` 条件，所以必须有主键。
     * 这个事实由引擎算好随 `DATA.LIST` 一起回（见 [TablePreviewTab.canUpdate]）。
     *
     * 这里仍然坚持「宁可先不给编辑」：唯一能立刻拿到的候选是 `id` 列
     * （[TablePreviewTab] 的行 id 就取自它），但**「有 id 列」不等于「id 是主键」** ——
     * 一张表的 `id` 完全可能只是个普通可重复列。那时发出去的 `WHERE id = ?` 会
     * **同时改掉多行**，静默的数据损坏比「不能编辑」严重得多。
     *
     * [readOnly] 也在这里挡：用户的只读开关是**意图**而不是权限，在点第一格之前
     * 就该让它点不动 —— 让人先改半天再告诉「只读模式不能提交」是在浪费他的时间。
     */
    fun isTableEditable(tab: TablePreviewTab): Boolean = tab.canUpdate && !readOnly

    /** 能不能新增行 —— [readOnly] 与引擎能力都要放行。 */
    fun canInsertRows(tab: TablePreviewTab): Boolean = tab.canInsert && !readOnly

    /** 能不能删除行 —— 没有主键就定位不到行，所以和编辑同一条硬边界。 */
    fun canDeleteRows(tab: TablePreviewTab): Boolean = tab.canDelete && !readOnly

    /**
     * 某一列能不能在单元格里改 —— [DataTable.isColumnEditable] 的实现。
     *
     * 除了表级开关，还查 [TablePreviewTab.readOnlyColumns]（主键列 + 二进制列）。
     * 之所以在**渲染层**而不是提交时再拒：提交时才发现不可改，用户已经白填了半天。
     */
    fun isColumnEditable(
        tab: TablePreviewTab,
        column: TableColumn,
    ): Boolean = isTableEditable(tab) && column.key !in tab.readOnlyColumns

    /** 该列能不能取 SQL NULL —— 决定编辑框里画不画「设为 NULL」的开关。 */
    fun isColumnNullable(
        tab: TablePreviewTab,
        column: TableColumn,
    ): Boolean = column.key in tab.nullableColumns

    /** 某一格是不是「已改但还没提交」—— [DataTable.isCellPending] 的实现。 */
    fun isCellPending(
        tab: TablePreviewTab,
        row: TableRow,
        column: TableColumn,
    ): Boolean =
        tab.pendingOps.any {
            it is TablePreviewTab.PendingOp.Update && it.rowId == row.id && column.key in it.changes
        }

    /** 某一行是不是已标记待删除 —— [DataTable.isRowDeleted] 的实现。 */
    fun isRowDeleted(
        tab: TablePreviewTab,
        row: TableRow,
    ): Boolean = tab.pendingOps.any { it is TablePreviewTab.PendingOp.Delete && it.rowId == row.id }

    /**
     * 把一次单元格编辑**暂存**起来 —— 不发任何请求。
     *
     * 真正的写库发生在 [applyCellEdits]，那时整批改动才会被包进一个事务。
     *
     * 同一行已有暂存改动时**合并**进那一条而不是新增一条：同一行的两处改动本
     * 来就该合成一条 `UPDATE`（少一次往返、少一次行锁），而让缓冲里出现两条同行的
     * 更新会让「这一行到底改了哪些列」变得要靠合并才能回答。
     *
     * @return `null` = 已暂存；非空 = 该给用户看的拒绝理由。
     */
    fun stageCellEdit(
        tab: TablePreviewTab,
        edit: CellEdit,
    ): String? {
        if (!isTableEditable(tab)) return Msg.text(StrKey.NO_PRIMARY_KEY_EDIT_DISABLED)
        if (edit.columnKey in tab.readOnlyColumns) {
            // 两种只读列的原因不同，挨着说才有意义：改主键会毁掉后续定位，
            // 改二进制列会毁掉数据本身 —— 不是一回事。
            val reason =
                if (tab.primaryKeyColumns.any { it.equals(edit.columnKey, ignoreCase = true) }) {
                    Msg.text(StrKey.EDIT_REASON_PRIMARY_KEY)
                } else {
                    Msg.text(StrKey.EDIT_REASON_BINARY)
                }
            return Msg.format(StrKey.EDIT_COLUMN_READ_ONLY, edit.columnKey) + "：" + reason
        }
        // 行必须还在当前页：`WHERE` 用的是这一行的主键单元格值，
        // 而值是从当前页的 [TablePreviewTab.rows] 里取的。
        if (tab.rows.none { it.id == edit.rowId }) return Msg.text(StrKey.ROW_NOT_ON_PAGE)

        val sameRow =
            tab.pendingOps.filterIsInstance<TablePreviewTab.PendingOp.Update>().filter { it.rowId == edit.rowId }
        tab.pendingOps =
            if (sameRow.isEmpty()) {
                tab.pendingOps + TablePreviewTab.PendingOp.Update(edit.rowId, mapOf(edit.columnKey to edit.newValue))
            } else {
                // 合并进**第一条**同行的更新（缓冲里不会存在两条同行的更新，见上）。
                var merged = false
                tab.pendingOps.map { op ->
                    if (!merged && op is TablePreviewTab.PendingOp.Update && op.rowId == edit.rowId) {
                        merged = true
                        op.copy(changes = op.changes + (edit.columnKey to edit.newValue))
                    } else {
                        op
                    }
                }
            }
        return null
    }

    /**
     * 把选中的若干行**标记**为待删除 —— 同样只是暂存，不发请求。
     *
     * ## 为什么是「标记」而不是「立刻删」
     *
     * 删数据不可逆。立刻删就意味着「用户手一抖，这一页里选错的那 5 行永久没了」——
     * 而 `WHERE` 只由主键定位，用户在提交前根本没有机会看到「即将执行的到底是什么」。
     * 标记 + 提交给了那一次确认，也让整批删除能和其它改动落进同一个事务。
     *
     * @return `null` = 已暂存；非空 = 拒绝理由。
     */
    fun stageRowDeletes(
        tab: TablePreviewTab,
        rowIds: Collection<Any>,
    ): String? {
        if (!canDeleteRows(tab)) return Msg.text(StrKey.NO_PRIMARY_KEY_EDIT_DISABLED)
        val known = tab.rows.map { it.id }.toSet()
        // 只取当前页里真实存在的行：不在这一页的行一旦被标记，之后拿不到它的主键
        // 单元格值去拼 WHERE，最后会以「0 行被更新」的形式失败 —— 而失败原因
        // （「你删的是一页之前的行」）在那时已经无从推断。
        val targets = rowIds.filter { it in known }
        if (targets.isEmpty()) return Msg.text(StrKey.EDIT_DELETE_NONE)
        val alreadyMarked =
            tab.pendingOps
                .filterIsInstance<TablePreviewTab.PendingOp.Delete>()
                .map { it.rowId }
                .toSet()
        tab.pendingOps =
            tab.pendingOps +
            targets.filter { it !in alreadyMarked }.map {
                TablePreviewTab.PendingOp.Delete(it)
            }
        tab.selectedRowIds = emptySet()
        return null
    }

    /**
     * 把若干行**待新增**的数据暂存进来（值 `null` 表示写 SQL NULL）。
     *
     * ## 「空行」的判据是**一个键都没有**，不是「值都是 null」
     *
     * 弹窗每行初始是空 map，用户碰过哪一格才会多出哪个键。所以：
     * - `{}` —— 用户碰都没碰，等于「这一行不存在」，跳过（送进提交只会拖垮整批事务）。
     * - `{"nick": null}` —— 用户**逐格点了 ∅**，那就是「这一行我要的全是 NULL」。
     *   丢掉它等于让显式开关在最关键的场合失效：整行全 NULL 的行正是那个开关存在的理由。
     * - `{"nick": ""}` —— 空串是真值，照样保留。
     *
     * 三者只有按「键集合是否为空」才分得开；按值判（`values.none { it != null }`）
     * 会把第二类和第一类混成一处 —— 这是实现时最容易写错的一处。
     *
     * ## 跳过的条数一定要说出来
     *
     * 悄悄丢掉更糟：用户以为加了 3 行，实际只有 2 行会进库，提交后他才发现少了一行。
     * 所以无论整批被跳过还是只跳掉其中几行，都返回一句话。
     *
     * @return `null` = 全部暂存成功；非空 = 给用户看的说明（**可能是错误，也可能是提示**，
     *   区分办法是看 [TablePreviewTab.pendingOps] 的条数有没有变，调用方自己判断）。
     */
    fun stageRowInserts(
        tab: TablePreviewTab,
        values: List<Map<String, String?>>,
    ): String? {
        if (!canInsertRows(tab)) return Msg.text(StrKey.EDIT_INSERT_DISABLED)
        val usable = values.filter { it.isNotEmpty() }
        val skipped = values.size - usable.size
        if (usable.isEmpty()) return Msg.format(StrKey.EDIT_INSERT_ALL_NULL, skipped)
        tab.pendingOps = tab.pendingOps + usable.map { TablePreviewTab.PendingOp.Insert(it) }
        return if (skipped > 0) Msg.format(StrKey.EDIT_INSERT_ALL_NULL, skipped) else null
    }

    /** 放弃全部暂存改动 —— 只清缓冲，不发请求（数据库里本来就没动过）。 */
    fun discardCellEdits(tab: TablePreviewTab): String? {
        val count = tab.pendingOps.size
        if (count == 0) return Msg.text(StrKey.EDIT_NOTHING_PENDING)
        tab.pendingOps = emptyList()
        tab.selectedRowIds = emptySet()
        return Msg.format(StrKey.EDIT_DISCARDED, count)
    }

    /**
     * 把暂存的全部修改**在一个事务里**提交出去。
     *
     * ## 流程
     *
     * 1. `SYSTEM.BEGIN` —— 引擎钉住一条 `autocommit=false` 的连接并返回 `session_id`
     * 2. 同一行的多个格子的改动**合并成一条** `UPDATE`（少一次往返，也少一次行锁获取）
     * 3. 每条 `DATA.UPDATE` 都带同一个 `session_id`，全部落在那条连接上
     * 4. 任何一条失败 → `SYSTEM.ROLLBACK`，整批不落盘
     * 5. 全部成功 → `SYSTEM.COMMIT`
     *
     * ## 为什么失败时**保留**暂存内容
     *
     * 回滚之后数据库回到提交前的状态，但用户改的东西未必都是错的 —— 典型的是
     * 「20 行里有一行撞了唯一索引」。把暂存清空等于逼用户把那 19 行重敲一遍；
     * 保留下来，他改掉那一个冲突值就能重试。为此这里在回滚后**重新载入当前页**，
     * 让 [TablePreviewTab.rows] 与库里的真值重新对齐（缓冲还在，[displayRows] 继续叠加）。
     *
     * ## 为什么 `affectedRows == 0` 算**失败**
     *
     * `WHERE` 没命中意味着这一行已经被别人删掉或改了主键。若当成功处理，用户会
     * 看到「已提交 5 处修改」而其中一条其实什么都没发生 —— 这正是当初把编辑整个
     * 关掉要防的那类静默错误，宁可报错也不假装成功。
     *
     * @return `null` = 提交成功（调用方自己写成功提示）；非空 = 失败文案。
     */
    suspend fun applyCellEdits(tab: TablePreviewTab): String? {
        if (tab.pendingOps.isEmpty()) return Msg.text(StrKey.EDIT_NOTHING_PENDING)
        if (readOnly) return Msg.text(StrKey.EDIT_BLOCKED_READ_ONLY)
        if (tab.applyingEdits) return null

        // 先把每一条都换算成「行 → (列, 新值)」，并校验行还在当前页。
        // 放到发请求之前全做完：一边发一边校验的话，校验失败时已经有几条落库了。
        val ops = tab.pendingOps
        for (op in ops) {
            val rowId =
                when (op) {
                    is TablePreviewTab.PendingOp.Update -> op.rowId
                    is TablePreviewTab.PendingOp.Delete -> op.rowId
                    is TablePreviewTab.PendingOp.Insert -> null
                }
            if (rowId != null && tab.rows.none { it.id == rowId }) return Msg.text(StrKey.ROW_NOT_ON_PAGE)
        }

        tab.applyingEdits = true
        try {
            val catalog = tab.schema.ifBlank { currentSchema() }
            activeDatabases.add(catalog)
            val (sessionId, beginError) = beginEditSession(catalog)
            if (sessionId == null) return beginError
            var index = 0
            val total = ops.size
            for (op in ops) {
                index++
                val failure =
                    when (op) {
                        is TablePreviewTab.PendingOp.Update -> {
                            val row = tab.rows.first { it.id == op.rowId }
                            applyOneCellRow(catalog, sessionId, tab, row, op.changes)
                        }

                        is TablePreviewTab.PendingOp.Delete -> {
                            val row = tab.rows.first { it.id == op.rowId }
                            applyOneDelete(catalog, sessionId, tab, row)
                        }

                        is TablePreviewTab.PendingOp.Insert -> {
                            applyOneInsert(catalog, sessionId, tab, op.values)
                        }
                    }
                if (failure != null) {
                    val rollback = rollbackEditSession(catalog, sessionId)
                    loadTabPreview(tab)
                    return if (rollback != null) {
                        Msg.format(StrKey.EDIT_ROLLBACK_FAILED, rollback)
                    } else {
                        Msg.format(StrKey.EDIT_APPLY_ROLLED_BACK, index, total, failure)
                    }
                }
            }
            val commit = commitEditSession(catalog, sessionId)
            if (commit != null) return Msg.format(StrKey.EDIT_ROLLBACK_FAILED, commit)
            tab.pendingOps = emptyList()
            tab.selectedRowIds = emptySet()
            loadTabPreview(tab)
            return null
        } finally {
            tab.applyingEdits = false
        }
    }

    /**
     * 取这一行的主键 `WHERE` 子句 —— 复合主键逐列都带上（见
     * [TablePreviewTab.primaryKeyColumns]）：少带一列就不是唯一条件了。
     *
     * 刻意**从行数据现取**，而不是从暂存缓冲取：主键列本来就不允许编辑，
     * 缓冲里永远不会出现它，所以现取到的就是数据库里的真值。
     */
    private fun primaryKeyWhere(
        tab: TablePreviewTab,
        row: TableRow,
    ): Map<String, String>? {
        val where = LinkedHashMap<String, String>()
        for (pk in tab.primaryKeyColumns) {
            val raw = row.cells[pk] ?: return null
            where[pk] = raw.toString()
        }
        return where
    }

    /**
     * 发一条 `DATA.UPDATE` —— 成功返回 `null`，失败返回引擎给的错误文案。
     *
     * `changes` 里值为 `null` 的列走 `null_columns`：协议的 `map<string, string>`
     * 装不下 null（proto3 的 map 值不能为 null），所以「这一格是 NULL」必须用
     * 独立的列名清单表达 —— 这也正是界面里「设为 NULL」那个显式开关存在的理由。
     */
    private suspend fun applyOneCellRow(
        catalog: String,
        // 同 [runEditSessionEnd]：不叫 `sessionId`，否则下面 `sessionId = sessionId`
        // 的左边会解析到形参而不是 proto builder 的字段。
        editSessionId: String,
        tab: TablePreviewTab,
        row: TableRow,
        changes: Map<String, String?>,
    ): String? {
        val where =
            primaryKeyWhere(tab, row)
                ?: return Msg.format(StrKey.PRIMARY_KEY_NOT_IN_ROW, tab.primaryKeyColumns.joinToString())
        // 局部变量叫 `nulls` 而不是 `nullColumns`：后者是 DSL 生成的 builder 属性，
        // 同名会把 `nullColumns.forEach` 解析到 builder 上，读代码时看不出在遍历谁。
        val nulls = changes.filterValues { it == null }.keys.toList()
        // 同老 `updateCell` 踩过的坑：先在**内层 DSL 之外**把 update 请求构造好。
        //
        // 为什么不内联：`dataUpdateRequest { }` 的 lambda 接收者会被外层
        // `dataRequest { }` 的同名兄弟字段（`schema` / `where` / `tableName`）遮蔽 ——
        // 内层写 `schema = ""` 会解析到外层去，编译报「val cannot be reassigned」。
        // Kotlin 的内联 lambda 没有标签可用来消歧（`this@xxx` 对内行 lambda 不可用），
        // 所以把构造提到外面是唯一干净的做法。
        val updateReq =
            com.langbiantianya.grpc.dataUpdateRequest {
                tableName = tab.tableName
                // catalog 走 engineConnFor 的 database 维度，schema 字段留空
                // —— 写进去会让 H2 执行 `SET SCHEMA "<库名>"` 而报「Schema not found」，
                // 见 engineConnFor 的 KDoc。
                schema = ""
                // map 字段用 `put(k, v)`（DslMap 的扩展函数），不是 `=` 赋值。
                // 显式写 `this.changes` / `this.where` 是为了在两个同名字段面前
                // 不产生任何歧义 —— 这里它们恰好也是内层 builder 的字段名。
                changes.forEach { (k, v) -> this.changes.put(k, v.orEmpty()) }
                where.forEach { (k, v) -> this.where.put(k, v) }
                // 生成的 Kotlin DSL 把 repeated 字段暴露成 `DslList`，靠它的 `add`
                // 追加 —— 不是 builder 上的 `addNullColumns` 方法（那个只在 Java 侧可见）。
                nulls.forEach { this.nullColumns.add(it) }
            }
        val resp =
            runCatching {
                engine.invoke(engineConnFor(currentConnection, catalog)) {
                    category = Category.DATA
                    action = Action.UPDATE
                    // 关键一行：非空时引擎把这次更新钉在 BEGIN 开的那条连接上，
                    // autocommit=false，整批改动在 COMMIT 之前都不落盘。
                    sessionId = editSessionId
                    dataRequest = dataRequest { update = updateReq }
                }
            }.getOrNull()
                ?: return Msg.format(
                    StrKey.OP_NO_ENGINE_RESPONSE,
                    Msg.text(StrKey.UPDATE_FAILED),
                )
        if (!resp.success) return resp.error.ifBlank { Msg.text(StrKey.UPDATE_FAILED) }
        val affected = resp.data.update.affectedRows
        return if (affected == 0) Msg.text(StrKey.UPDATE_FAILED) else null
    }

    /**
     * 发一条 `DATA.DELETE` —— 同样带主键 `WHERE`，同样落在事务会话的那条连接上。
     *
     * `affected_rows == 0` 算失败：说明这一行已经被别人删掉了。当成功处理的话，
     * 用户会看到「已删除 N 行」而其中一条其实什么都没发生 —— 宁可报错也不假装成功。
     */
    private suspend fun applyOneDelete(
        catalog: String,
        editSessionId: String,
        tab: TablePreviewTab,
        row: TableRow,
    ): String? {
        val where =
            primaryKeyWhere(tab, row)
                ?: return Msg.format(StrKey.PRIMARY_KEY_NOT_IN_ROW, tab.primaryKeyColumns.joinToString())
        val deleteReq =
            com.langbiantianya.grpc.dataDeleteRequest {
                tableName = tab.tableName
                schema = ""
                where.forEach { (k, v) -> this.where.put(k, v) }
            }
        val resp =
            runCatching {
                engine.invoke(engineConnFor(currentConnection, catalog)) {
                    category = Category.DATA
                    action = Action.DELETE
                    sessionId = editSessionId
                    dataRequest = dataRequest { delete = deleteReq }
                }
            }.getOrNull()
                ?: return Msg.format(StrKey.OP_NO_ENGINE_RESPONSE, Msg.text(StrKey.DELETE_FAILED))
        if (!resp.success) return resp.error.ifBlank { Msg.text(StrKey.DELETE_FAILED) }
        return if (resp.data.delete.affectedRows == 0) Msg.text(StrKey.DELETE_FAILED) else null
    }

    /** 发一条 `DATA.CREATE`。值为 `null` 的列走 `null_columns`（同 [applyOneCellRow]）。 */
    private suspend fun applyOneInsert(
        catalog: String,
        editSessionId: String,
        tab: TablePreviewTab,
        values: Map<String, String?>,
    ): String? {
        val nulls = values.filterValues { it == null }.keys.toList()
        val createReq =
            com.langbiantianya.grpc.dataCreateRequest {
                tableName = tab.tableName
                schema = ""
                values.forEach { (k, v) -> this.values.put(k, v.orEmpty()) }
                nulls.forEach { this.nullColumns.add(it) }
            }
        val resp =
            runCatching {
                engine.invoke(engineConnFor(currentConnection, catalog)) {
                    category = Category.DATA
                    action = Action.CREATE
                    sessionId = editSessionId
                    dataRequest = dataRequest { create = createReq }
                }
            }.getOrNull()
                ?: return Msg.format(StrKey.OP_NO_ENGINE_RESPONSE, Msg.text(StrKey.INSERT_FAILED))
        if (!resp.success) return resp.error.ifBlank { Msg.text(StrKey.INSERT_FAILED) }
        return if (resp.data.create.affectedRows == 0) Msg.text(StrKey.INSERT_FAILED) else null
    }

    /** `SYSTEM.BEGIN` —— 成功返回 `(sessionId, null)`，失败返回 `(null, 该给用户看的文案)`。 */
    private suspend fun beginEditSession(catalog: String): Pair<String?, String?> {
        val resp =
            runCatching {
                engine.invoke(engineConn(database = catalog)) {
                    category = Category.SYSTEM
                    action = Action.BEGIN
                    systemRequest = systemRequest { sessionId = UUID.randomUUID().toString() }
                }
            }.getOrNull()
        if (resp == null || !resp.success) {
            val reason = resp?.error?.ifBlank { null } ?: Msg.text(StrKey.BEGIN_TRANSACTION_FAILED)
            return null to Msg.format(StrKey.EDIT_BEGIN_FAILED, reason)
        }
        // 引擎回显 session id；拿不到就当失败 —— 前端**必须**持有它，
        // 后续每条 DATA.UPDATE 靠它找到那条被钉住的连接，拿不到等于事务没开。
        val sid =
            resp.system
                ?.begin
                ?.sessionId
                .orEmpty()
        if (sid.isBlank()) return null to Msg.format(StrKey.EDIT_BEGIN_FAILED, Msg.text(StrKey.NO_SESSION_ID))
        return sid to null
    }

    /** `SYSTEM.COMMIT`。 */
    private suspend fun commitEditSession(
        catalog: String,
        sessionId: String,
    ): String? = runEditSessionEnd(catalog, sessionId, Action.COMMIT)

    /** `SYSTEM.ROLLBACK`。 */
    private suspend fun rollbackEditSession(
        catalog: String,
        sessionId: String,
    ): String? = runEditSessionEnd(catalog, sessionId, Action.ROLLBACK)

    private suspend fun runEditSessionEnd(
        catalog: String,
        // 刻意**不叫** `sessionId`：形参与 proto builder 的字段同名时，
        // `systemRequest { sessionId = sessionId }` 的左边会解析到形参（一个 val），
        // 编译直接报「val cannot be reassigned」。既有的 `endTransaction` 也是这么绕开的。
        editSessionId: String,
        action: com.langbiantianya.grpc.Action,
    ): String? {
        val resp =
            runCatching {
                engine.invoke(engineConn(database = catalog)) {
                    category = Category.SYSTEM
                    this.action = action
                    systemRequest = systemRequest { sessionId = editSessionId }
                }
            }.getOrNull()
        if (resp == null || !resp.success) {
            return resp?.error?.ifBlank { null } ?: Msg.format(StrKey.OP_NO_ENGINE_RESPONSE, action.name)
        }
        return null
    }

    // ------------------------------------------------------------------------
    // 导出（引擎 v2.16 已实现，此前前端未接线）
    // ------------------------------------------------------------------------

    /** 导出格式 —— 与 `ExportRunRequest.format` 一一对应。 */
    enum class ExportFormat(
        val labelKey: StrKey,
        val protoValue: String,
        val extension: String,
    ) {
        CSV(StrKey.FORMAT_CSV, "CSV", "csv"),
        JSON_LINES(StrKey.FORMAT_JSON_LINES, "JSON_LINES", "jsonl"),
        SQL_INSERT(StrKey.FORMAT_SQL_INSERT, "SQL_INSERT", "sql"),
        EXCEL(StrKey.FORMAT_EXCEL, "EXCEL", "xlsx"),
        PARQUET(StrKey.FORMAT_PARQUET, "PARQUET", "parquet"),
    }

    /** 导出结果 —— 引擎终止帧回的实际文件路径。 */
    data class ExportResult(
        val file: String,
        val rowsWritten: Long,
    )

    // --------------------------------------------------------------------
    // 后台任务
    // --------------------------------------------------------------------

    /**
     * 一个**后台任务**的快照。
     *
     * ## 为什么需要它，而不是继续用「弹窗 + remember」
     *
     * 「后台运行」这个按钮原本只是把进度弹窗藏起来，导出确实还在跑 —— 但：
     *
     * 1. 协程挂在 [PreviewTabArea] 的 `rememberCoroutineScope()` 上，
     *    **一切 pane 那个协程就被取消**。于是「后台运行」其实是骗人的：
     *    用户切到 SQL 工作台，导出就死了，而界面上什么痕迹都不留。
     * 2. 进度状态同样是 composable 的 `remember`，切 pane 后连「刚才在导出什么」都没了。
     *
     * 修法是两件事：协程改用 [DatabaseBrowserState] 持有的**应用级** scope，
     * 状态改存进本状态机（per-sheet）。于是任务活过 pane 切换，
     * 并且在任何 pane 都能从工具栏的「任务」入口看到。
     *
     * @property progress 已处理行数；`null` 表示引擎还没推第一帧（此时显示「正在连接引擎…」，
     *   **不能显示「0 行」** —— 那会被读成「导出失败」，见 [ExportProgress]）。
     * @property detail 成功时是结果文件路径，失败时是原因；运行中为 `null`。
     */
    data class BackgroundTask(
        val id: String,
        val kind: Kind,
        /** 主标题 —— 通常是文件名。 */
        val title: String,
        /** 副标题 —— 输出目录 / 格式。 */
        val subtitle: String,
        val progress: Long? = null,
        val status: Status,
        val detail: String? = null,
        val startedAt: Long,
    ) {
        enum class Kind { EXPORT, IMPORT, GENERATE }

        enum class Status {
            /** 正在跑（还没收口）。 */
            RUNNING,

            /** 已成功收口 —— [detail] 是文件路径。 */
            SUCCEEDED,

            /** 已失败收口 —— [detail] 是原因。 */
            FAILED,
        }

        /** 正在跑的任务 —— 工具栏徽标与进度弹窗只看这些。 */
        val isRunning: Boolean get() = status == Status.RUNNING

        /**
         * 一行说清它现在怎么样。
         *
         * **运行中且还没收到第一帧时说「正在连接引擎…」而不是「0 行」** ——
         * 「0 行」会被读成「导出失败」，而引擎启动子进程本来就要几秒。
         */
        val statusLine: String
            get() =
                when (status) {
                    Status.RUNNING -> {
                        progress?.let { Msg.plural(PluralStrKey.EXPORT_PROGRESS_ROWS, it.toInt(), it) }
                            ?: Msg.text(StrKey.CONNECTING_ENGINE)
                    }

                    Status.SUCCEEDED -> {
                        detail?.let { Msg.format(StrKey.TASK_DONE_DETAIL, it) }
                            ?: Msg.text(StrKey.DONE)
                    }

                    Status.FAILED -> {
                        detail?.let { Msg.format(StrKey.TASK_FAILED_DETAIL, it) }
                            ?: Msg.text(StrKey.FAILED)
                    }
                }
    }

    /**
     * 后台任务列表 —— **最新的在前**。
     *
     * 用 [mutableStateListOf] 而不是 `List<...> by mutableStateOf`：
     * 后者每次更新都要整份替换并重新分配，前端却只改了一个任务的进度字段；
     * 前者能让 Compose 只重组受影响的行。
     */
    val backgroundTasks = mutableStateListOf<DatabaseBrowserState.BackgroundTask>()

    /** 正在跑的任务 —— 工具栏徽标计数 / 进度弹窗的数据源。 */
    val runningTasks: List<DatabaseBrowserState.BackgroundTask>
        get() = backgroundTasks.filter { it.isRunning }

    /**
     * 清掉**已收口**的任务，运行中的留着。
     *
     * 只给「已完成」的清理 —— 正在跑的任务行必须留着，否则用户一点清理，
     * 那个导出就从视野里消失了。
     */
    fun clearFinishedTasks() {
        backgroundTasks.removeAll { !it.isRunning }
    }

    /**
     * 启动一次**后台**导出：登记任务 + 在**应用级** scope 上跑。
     *
     * ## 为什么不用调用方的 scope
     *
     * 调用方（[PreviewTabArea] / [SqlWorkbenchPane]）的 `rememberCoroutineScope()`
     * 会在该 composable 离开组合时取消所有协程。导出动辄几十秒，
     * 用户切个 pane 就被杀掉 —— 然而「后台运行」这个按钮恰恰在暗示它能活着跑完。
     *
     * @param catalog 导出打到的**库**；空串 = 退回 [currentSchema]。
     *   表预览那条路必须传这张表所在的库（见 [exportQuery] 的 KDoc）。
     * @param onComplete 在**引擎协程**里回调（非主线程）；主要用于把结果写到 tab / sheet 的提示行。
     * @return 任务 id，供调用方后续对账。
     */
    fun startExport(
        sql: String,
        format: ExportFormat,
        outputDir: String,
        fileName: String,
        tableName: String = "",
        catalog: String = "",
        onComplete: (Result<ExportResult>) -> Unit = {},
    ): String {
        val taskId = "task-" + java.util.UUID.randomUUID()
        backgroundTasks.add(
            0,
            DatabaseBrowserState.BackgroundTask(
                id = taskId,
                kind = DatabaseBrowserState.BackgroundTask.Kind.EXPORT,
                title = fileName,
                subtitle = "$outputDir · ${Msg.text(format.labelKey)}",
                status = DatabaseBrowserState.BackgroundTask.Status.RUNNING,
                startedAt = System.currentTimeMillis(),
            ),
        )
        scope.launch {
            exportQuery(sql, format, outputDir, fileName, tableName, catalog) { p ->
                updateTask(taskId) { it.copy(progress = p.rowsWritten) }
            }.onSuccess { r ->
                updateTask(taskId) {
                    it.copy(
                        progress = r.rowsWritten,
                        status = DatabaseBrowserState.BackgroundTask.Status.SUCCEEDED,
                        detail = r.file,
                    )
                }
                notifications?.push(
                    AppNotification.Severity.SUCCESS,
                    Msg.format(StrKey.EXPORT_COMPLETE_FILE, fileName),
                    Msg.plural(PluralStrKey.EXPORTED_ROWS_TO_FILE, r.rowsWritten.toInt(), r.rowsWritten, r.file),
                )
            }.onFailure { e ->
                updateTask(taskId) {
                    it.copy(
                        status = DatabaseBrowserState.BackgroundTask.Status.FAILED,
                        detail = e.message ?: Msg.text(StrKey.EXPORT_FAILED),
                    )
                }
                notifications?.push(
                    AppNotification.Severity.ERROR,
                    Msg.format(StrKey.EXPORT_FAILED_FILE, fileName),
                    e.message ?: Msg.text(StrKey.EXPORT_FAILED),
                )
            }.also { onComplete(it) }
        }
        return taskId
    }

    /** 按 id 更新任务（不存在则忽略 —— 任务可能已被「清除」）。 */
    private fun updateTask(
        taskId: String,
        transform: (DatabaseBrowserState.BackgroundTask) -> DatabaseBrowserState.BackgroundTask,
    ) {
        val idx = backgroundTasks.indexOfFirst { it.id == taskId }
        if (idx >= 0) backgroundTasks[idx] = transform(backgroundTasks[idx])
    }

    /**
     * 顺序导出多条 SQL —— **每条一个任务**，串行跑。
     *
     * ## 为什么是「N 个任务」而不是「1 个任务」
     *
     * 面板存在的意义就是回答「刚才那批导出怎么样了」。N 条合成一条时，
     * 用户只能看到「成功了」或「失败了」，看不出**哪一条**失败 ——
     * 而多语句导出里单条失败恰恰是最常见的形态（一条 SQL 引用了不存在的列）。
     *
     * ## 为什么**串行**而不是每条各起一个协程
     *
     * 每个协程都会驱动一次引擎导出，大表并发会把数据库压垮，而且并发度不可控。
     * 串行的代价只是「慢一点」，换来的是「行为可预期」。
     *
     * @return 任务 id 列表（顺序与 [statements] 一致）。
     */
    fun startExportBatch(
        statements: List<String>,
        format: ExportFormat,
        outputDir: String,
        baseFileName: String,
        tableName: String = "",
        catalog: String = "",
        onComplete: (List<Result<ExportResult>>) -> Unit = {},
    ): List<String> {
        if (statements.isEmpty()) return emptyList()
        val taskIds =
            statements.mapIndexed { index, _ ->
                newExportTask(numberedFileName(baseFileName, index, statements.size), format, outputDir)
            }
        scope.launch {
            val results =
                statements.mapIndexed { index, sql ->
                    exportQuery(sql, format, outputDir, numberedFileName(baseFileName, index, statements.size), tableName, catalog) { p ->
                        updateTask(taskIds[index]) { it.copy(progress = p.rowsWritten) }
                    }.also { result ->
                        updateTask(taskIds[index]) {
                            it.copy(
                                progress = result.getOrNull()?.rowsWritten ?: it.progress,
                                status =
                                    if (result.isSuccess) {
                                        DatabaseBrowserState.BackgroundTask.Status.SUCCEEDED
                                    } else {
                                        DatabaseBrowserState.BackgroundTask.Status.FAILED
                                    },
                                detail =
                                    result.getOrNull()?.file
                                        ?: result.exceptionOrNull()?.message
                                        ?: Msg.text(StrKey.EXPORT_FAILED),
                            )
                        }
                    }
                }
            // 通知**按批次汇总**成一条，而不是每条推一条 ——
            // 一次导出 20 条语句推 20 条通知会把面板刷爆，而用户真正想知道的
            // 只是「这一批成了几条、哪几条挂了」。逐条明细在任务面板里。
            val okCount = results.count { it.isSuccess }
            val failed = results.filter { it.isFailure }
            notifications?.let { nc ->
                when {
                    failed.isEmpty() -> {
                        nc.push(
                            AppNotification.Severity.SUCCESS,
                            Msg.plural(PluralStrKey.EXPORT_COMPLETE_FILES, results.size, results.size),
                            Msg.plural(PluralStrKey.FILES_WRITTEN, okCount, okCount) +
                                results
                                    .mapNotNull { it.getOrNull()?.file }
                                    .joinToString("；") { File(it).name },
                        )
                    }

                    // 部分失败必须**说清楚是部分**：只报成功数会让用户以为全好了
                    okCount == 0 -> {
                        nc.push(
                            AppNotification.Severity.ERROR,
                            Msg.plural(PluralStrKey.EXPORT_FAILED_ALL_FILES, results.size, results.size),
                            failed.first().exceptionOrNull()?.message ?: Msg.text(StrKey.EXPORT_FAILED),
                        )
                    }

                    else -> {
                        nc.push(
                            AppNotification.Severity.ERROR,
                            Msg.plural(PluralStrKey.EXPORT_PARTIAL_FILES, results.size, okCount, results.size),
                            Msg.plural(PluralStrKey.EXPORT_SUCCESS_FAILURE_COUNTS, okCount, okCount, failed.size) +
                                (failed.first().exceptionOrNull()?.message ?: Msg.text(StrKey.UNKNOWN_REASON)),
                        )
                    }
                }
            }
            onComplete(results)
        }
        return taskIds
    }

    /** 登记一个运行中的导出任务，返回它的 id。 */
    private fun newExportTask(
        fileName: String,
        format: ExportFormat,
        outputDir: String,
    ): String {
        val id = "task-" + java.util.UUID.randomUUID()
        backgroundTasks.add(
            0,
            DatabaseBrowserState.BackgroundTask(
                id = id,
                kind = DatabaseBrowserState.BackgroundTask.Kind.EXPORT,
                title = fileName,
                subtitle = "$outputDir · ${Msg.text(format.labelKey)}",
                status = DatabaseBrowserState.BackgroundTask.Status.RUNNING,
                startedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    /**
     * 导出进度的一帧 —— 引擎每导出一批就推一次。
     *
     * ## 为什么只有行数、没有百分比
     *
     * 引擎的 `ExportProgressFrame` 里就只有 `exported_rows` / `column_count` /
     * `completed`，**没有总行数**：导出走的是一条普通 `SELECT`，引擎不会为了报进度
     * 先跑一遍 `COUNT(*)`（大表上那是白等一遍）。
     *
     * 前端**不能**拿表预览那一行的行数来充当总数 ——
     * 预览可能带着过滤/搜索条件，而导出的是整张表，两者对不上。
     * 报一个假的百分比比不报更糟，所以进度条走不确定态（indeterminate），
     * 旁边如实显示「已导出 N 行」。
     */
    data class ExportProgress(
        val rowsWritten: Long,
        val completed: Boolean,
    )

    /**
     * 导出的**看门狗超时**（毫秒）。
     *
     * 取 5 分钟：真正的大表导出按行推进、可能跑很久，这个值不该误伤正常导出；
     * 但它必须存在 —— 引擎那条流式路由在「命令没发出去」或「子进程中途没了」时
     * 可能**一帧都不回**，此时 `collect` 会永远挂着，界面既不成功也不失败，
     * 用户看到的是「点了导出，然后什么也没发生」。
     *
     * 与其指望每一层都记得收口，不如在这层兜一道底：
     * 超时也要变成一条**用户看得见**的失败。
     */
    internal var exportTimeoutMs: Long = 5 * 60 * 1000

    /**
     * 导出一段 SQL 的结果 —— 发 `EXPORT.RUN_EXPORT`。
     *
     * ## 为什么要用 [EngineClient.handle] 而不是 [EngineClient.invoke]
     *
     * 导出是**流式**的：每导出一批推一帧 `ExportProgressFrame`，终止帧带 `completed=true`
     * 与最终 `file_path`。`invoke` 只 collect 到**第一条**终止帧就返回，中间进度与最终
     * 文件路径都会丢。
     *
     * ## 为什么导出打到的库不能只问树
     *
     * 原实现恒用 [currentSchema] —— 也就是「树里**第一个展开**的库」。而表预览那条路
     * 导出的是**用户眼前这张表**，它可以在**另一个**库里。两者不一致时导出会报
     *
     * ```text
     * Table 'sundays_smoke.probe_shared_name' doesn't exist
     * ```
     *
     * 报错里那个库名恰好是「用户**没点**的那个」，很难自己联想到根因（真机实测）。
     * 所以 [catalog] 由调用方显式给出 —— 表预览传这张表所在的库，
     * SQL 工作台那条路没有「某一张表」，留空即退回 [currentSchema]。
     *
     * @param format `SQL_INSERT` 时 [tableName] 必填 —— 引擎要拼出 `INSERT INTO <表>`。
     * @param catalog 导出打到的**库**（catalog 维度）。空串 = 退回 [currentSchema]。
     *   表预览那条路径必须传这张表所在的库 —— 见下面的「为什么不能只问树」。
     * @param onProgress 每收到一帧进度就调一次（**在引擎的协程里**，不是主线程）。
     *   UI 层用它驱动进度弹窗；不给就用默认的空实现，行为与过去完全一致。
     * @return 失败时 `Result.failure`；成功时 [ExportResult]。
     */
    suspend fun exportQuery(
        sql: String,
        format: ExportFormat,
        outputDir: String,
        fileName: String,
        tableName: String = "",
        catalog: String = "",
        onProgress: (ExportProgress) -> Unit = {},
    ): Result<ExportResult> {
        // 参数先在**前端**校验：让引擎那边才报错的话，用户看到的是一句 JDBC 异常，
        // 还要在对话框里猜是自己哪里填错了
        if (sql.isBlank()) return Result.failure(IllegalArgumentException(Msg.text(StrKey.SQL_EMPTY)))
        if (outputDir.isBlank()) {
            return Result.failure(IllegalArgumentException(Msg.text(StrKey.OUTPUT_DIR_REQUIRED)))
        }
        if (fileName.isBlank()) {
            return Result.failure(IllegalArgumentException(Msg.text(StrKey.FILE_NAME_REQUIRED)))
        }
        if (format == ExportFormat.SQL_INSERT && tableName.isBlank()) {
            return Result.failure(IllegalArgumentException(Msg.text(StrKey.EXPORT_TABLE_NAME_REQUIRED)))
        }
        // 导出打到的库：调用方给的那张表所在的库优先，空串才退回「树里第一个展开的库」。
        val schema = catalog.ifBlank { currentSchema() }
        activeDatabases.add(schema)
        // 在 DSL 之外构造：`sql` / `outputDir` 等字段名会与外层 `dataRequest` 的兄弟消息
        // 撞名，内联赋值会被解析到外层去（见 updateCell 的 KDoc）
        val runReq =
            com.langbiantianya.grpc.exportRunRequest {
                this.sql = sql
                this.outputDir = outputDir
                this.fileName = fileName
                this.format = format.protoValue
                this.tableName = tableName
            }
        return runCatching {
            val req =
                request {
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                    this.connection = engineConn(database = schema)
                    category = Category.EXPORT
                    action = Action.RUN_EXPORT
                    exportRequest = com.langbiantianya.grpc.exportRequest { runExport = runReq }
                }
            var file = ""
            var rows = 0L
            // ⚠️ `withTimeoutOrNull` 不是「防御性编程」，是这条契约的**实现**：
            // 引擎在命令没发出去 / 子进程中途消失时会**一帧都不回**，
            // 光靠 `collect` 结束前的检查是够不着的 —— 得有人替它兜底。
            // 没有这一行时，真窗口上点「开始导出」的表现是：对话框关了，
            // 文件没有，界面上一个字都没有（实测，见 TEST_CASES.md §9.13）。
            val finished =
                withTimeoutOrNull(exportTimeoutMs) {
                    engine.handle(req).collect { resp ->
                        if (!resp.success) throw IllegalStateException(resp.error.ifBlank { Msg.text(StrKey.EXPORT_FAILED) })
                        val p = resp.export.progress
                        rows = p.exportedRows
                        // 进度要**逐帧**往外抛，而不是只留最后一帧：
                        // 进度弹窗的价值全在「动起来」这件事上，只报最终行数等于没报
                        onProgress(ExportProgress(rowsWritten = p.exportedRows, completed = p.completed))
                        if (p.completed) {
                            file = p.filePath
                            if (p.error.isNotBlank()) throw IllegalStateException(p.error)
                        }
                    }
                    true
                }
            if (finished != true) {
                throw IllegalStateException(Msg.format(StrKey.EXPORT_TIMEOUT, exportTimeoutMs / 1000))
            }
            if (file.isBlank()) throw IllegalStateException(Msg.text(StrKey.EXPORT_NO_FILE_REPORTED))
            ExportResult(file = file, rowsWritten = rows)
        }
    }

    // 改单元格只有两个入口：`stageCellEdit` 只进暂存缓冲，`applyCellEdits` 才把
    // 整批改动放进一个事务发出去。这里原本还有一个「改一格立刻发一条 DATA.UPDATE」
    // 的 `updateCell`，已删掉 —— 只要还留着它，将来一定会有人顺手用它做「保存按钮」，
    // 把事务兜底绕过去。（写成注释而不是 KDoc：KDoc 挂在没有声明的前面会被
    // ktlint 判成「class_body 里的野 KDoc」。）

    /**
     * **只读模式** —— 开着时本屏发往引擎的一切写请求一律拒绝。
     *
     * 只在 UI 层拦、不动引擎：这是**用户自己**的开关，出错时立刻能看见「只读模式下
     * 写操作被拒绝」而不是一个引擎异常。真正需要强制只读（防止别的客户端写）时
     * 应该在连接串上用只读账号。
     *
     * 挡的是「已经发出去之前」，所以代价为零 —— 引擎那边什么都没发生。
     */
    var readOnly: Boolean by mutableStateOf(false)

    /**
     * 检查一次 SQL 在当前只读模式下能不能发 —— 不能时返回该给用户看的文案。
     *
     * @return `null` = 可以发。
     */
    fun rejectIfReadOnly(sql: String): String? {
        if (!readOnly) return null
        val writes = DangerousSql.scan(sql)
        if (writes.isEmpty()) return null
        return Msg.format(StrKey.READ_ONLY_WRITE_BLOCKED, writes.joinToString("；") { it.statement })
    }

    /**
     * 导出对话框里的「预览」—— 跑一条 SQL 取回前 [limit] 行。
     *
     * ## 为什么**只允许 SELECT / WITH**
     *
     * 预览是「点一下就发到数据库」的动作，而它出现的地方恰恰是用户**刚编辑完
     * 一条任意 SQL** 的编辑器里。让用户在这里敲一条 `UPDATE` 然后点「预览」，
     * 却在按钮旁边没有半个字提示，是不可接受的。
     *
     * 所以判据放在**发送之前**：不是 SELECT 开头就拒绝，并把原因说清楚。
     * 代价是 CTE（`WITH …`）也在白名单里 —— 它最终要么是 SELECT 要么是 DML，
     * 后者仍会被 [DangerousSql.scan] 二次拦下。
     *
     * @return 成功时是「列名 + 行」，失败时是给用户看的文案。
     */
    suspend fun previewExportSql(
        sql: String,
        catalog: String,
        limit: Int = 100,
    ): Result<SqlPreviewRows> {
        val statement = sql.trim()
        if (statement.isEmpty()) return Result.failure(IllegalArgumentException(Msg.text(StrKey.SQL_EMPTY)))
        if (!DangerousSql.isReadOnlyQuery(statement)) {
            return Result.failure(
                IllegalArgumentException(Msg.text(StrKey.PREVIEW_WRITE_REJECTED)),
            )
        }
        val schema = currentSchema()
        activeDatabases.add(catalog.ifBlank { schema })
        return runCatching {
            val req =
                request {
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                    this.connection = engineConn(database = catalog.ifBlank { schema })
                    category = Category.SQL
                    action = Action.EXECUTE
                    sqlRequest =
                        com.langbiantianya.grpc.sqlRequest {
                            execute =
                                com.langbiantianya.grpc.sqlExecuteRequest {
                                    this.sql = statement
                                    multiStatement = false
                                    // 只读标记：即便 [DangerousSql.isReadOnlyQuery] 的白名单被绕过，
                                    // 引擎侧还有一道（见 engine 侧 SqlEngineHandler 的只读处理）
                                    readOnly = true
                                }
                        }
                }
            // 行帧是 `Response.sql_row_frame`（顶层字段），不是 `Response.sql` ——
            // 后者只装 `execute` / `explain` 两个终止体。抄 [executeSql] 的读法。
            val columnNames = LinkedHashSet<String>()
            val rows = mutableListOf<Map<String, String>>()
            var truncated = false
            engine.handle(req).collect { resp ->
                if (!resp.success) throw IllegalStateException(resp.error.ifBlank { Msg.text(StrKey.PREVIEW_FAILED) })
                if (resp.hasSqlRowFrame()) {
                    val values = resp.sqlRowFrame.row.valuesMap
                    if (values.isEmpty()) return@collect
                    columnNames += values.keys
                    // 攒够上限就只记「已截断」，不再攒 —— 引擎还在推帧，
                    // 继续收只会在堆里压一份用不上的数据。
                    if (rows.size < limit) {
                        rows += values.entries.associate { (k, v) -> k to unwrapProtoValue(v) }
                    } else {
                        truncated = true
                    }
                }
            }
            SqlPreviewRows(
                columns = columnNames.toList(),
                columnTypes = List(columnNames.size) { "" },
                rows = rows,
                truncated = truncated,
            )
        }
    }

    /**
     * 依次导出多条 SQL，每条一个文件。
     *
     * 供 SQL 工作台的「导出」使用 —— 那里用户写的是一整段脚本，逐条导出才符合
     * 「每条查询各自一份结果」的直觉。文件名由 [numberedFileName] 加序号。
     *
     * @param catalog 导出打到的**库**；空串 = 退回 [currentSchema]（见 [exportQuery]）。
     * @param onProgress 每条开始前回调一次（**在引擎协程里，不是主线程**），供进度弹窗显示「第 k/N 条」。
     * @return 每条的结果（成功与失败都在列表里 —— 一条挂了不该让前面几条白跑）。
     */
    suspend fun exportQueries(
        statements: List<String>,
        format: ExportFormat,
        outputDir: String,
        baseFileName: String,
        tableName: String = "",
        catalog: String = "",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Result<ExportResult>> =
        statements.mapIndexed { index, sql ->
            onProgress(index, statements.size)
            exportQuery(sql, format, outputDir, numberedFileName(baseFileName, index, statements.size), tableName, catalog)
        }

    // ------------------------------------------------------------------------
    // 事务会话（v2.16 引擎能力，此前前端未接）
    // ------------------------------------------------------------------------

    /**
     * 开一个事务会话 —— 发 `SYSTEM.BEGIN`，返回 `session_id`。
     *
     * 引擎侧给该 id **钉住一条 JDBC 连接**（`autocommit=false`），此后这个 sheet 的
     * 每次执行都带 `session_id`，全部落在这条连接上，直到 `COMMIT` / `ROLLBACK`。
     *
     * @return 失败时返回错误文案；成功返回 `null`（session id 已写进 [SqlSheet.transactionSessionId]）。
     */
    suspend fun beginTransaction(sheet: DatabaseBrowserState.SqlSheet): String? {
        if (sheet.transactionSessionId != null) return Msg.text(StrKey.SHEET_ALREADY_IN_TRANSACTION)
        val resp =
            runCatching {
                engine.invoke(engineConn(database = currentSchema())) {
                    category = Category.SYSTEM
                    action = Action.BEGIN
                    systemRequest = systemRequest { sessionId = UUID.randomUUID().toString() }
                }
            }.getOrNull() ?: return Msg.text(StrKey.BEGIN_TRANSACTION_NO_RESPONSE)
        if (!resp.success) return resp.error.ifBlank { Msg.text(StrKey.BEGIN_TRANSACTION_FAILED) }
        val body = resp.system
        // 引擎回显 session id；拿不到就当失败 —— 前端**必须**持有它，
        // 后续请求靠它找到那条被钉住的连接，拿不到等于事务没开。
        val sid = body?.begin?.sessionId.orEmpty()
        if (sid.isBlank()) return Msg.text(StrKey.NO_SESSION_ID)
        sheet.transactionSessionId = sid
        return null
    }

    /** 提交事务 —— 发 `SYSTEM.COMMIT`。 */
    suspend fun commitTransaction(sheet: DatabaseBrowserState.SqlSheet): String? = endTransaction(sheet, Action.COMMIT, Msg.text(StrKey.COMMIT_TRANSACTION_FAILED))

    /** 回滚事务 —— 发 `SYSTEM.ROLLBACK`。 */
    suspend fun rollbackTransaction(sheet: DatabaseBrowserState.SqlSheet): String? = endTransaction(sheet, Action.ROLLBACK, Msg.text(StrKey.ROLLBACK_TRANSACTION_FAILED))

    private suspend fun endTransaction(
        sheet: DatabaseBrowserState.SqlSheet,
        action: com.langbiantianya.grpc.Action,
        fallback: String,
    ): String? {
        val sid = sheet.transactionSessionId ?: return Msg.text(StrKey.NOT_IN_TRANSACTION)
        val resp =
            runCatching {
                engine.invoke(engineConn(database = currentSchema())) {
                    category = Category.SYSTEM
                    this.action = action
                    systemRequest = systemRequest { sessionId = sid }
                }
            }.getOrNull() ?: return Msg.format(StrKey.OP_NO_ENGINE_RESPONSE, fallback)
        if (!resp.success) return resp.error.ifBlank { fallback }
        // 无论引擎报成功与否都清本地 id：`SYSTEM.ROLLBACK` 失败时那条连接仍被钉着，
        // 但用户已经看到错误并会重试 —— 留着 id 会让「提交」按钮一直亮着，
        // 而下一次点击会打到一条引擎已经不认识的会话上。
        sheet.transactionSessionId = null
        return null
    }

    // ------------------------------------------------------------------------
    // 造数工作台
    // ------------------------------------------------------------------------

    /**
     * 造数脚本 —— 一份 Lua 脚本 + 该脚本的造数统计。
     *
     * - [editor] 由状态机持有（文本 + 光标 / 选区 + 滚动），切到表预览再切回来保持不变
     * - [inserted] / [lastTable] 由引擎 `gen_progress_frame` 流实时回填
     */
    class GenerateScript(
        title: String,
    ) {
        /** 标签条上展示的名字（默认 `脚本 N`）—— 用户可重命名，见 [renameGenerateScript]。 */
        var title: String by mutableStateOf(title)

        val editor: CodeEditorState = CodeEditorState(GENERATE_SCRIPT_TEMPLATE)
        var inserted: Long by mutableStateOf(0)
        var lastTable: String by mutableStateOf("")
    }

    /** 造数脚本列表（顺序 = 引擎 `tables` 执行顺序 = 多表外键依赖顺序）。 */
    val generateScripts: SnapshotStateList<GenerateScript> =
        mutableStateListOf(GenerateScript(Msg.text(StrKey.SCRIPT_DEFAULT_NAME)))

    var selectedGenerateIndex: Int by mutableStateOf(0)

    /** 引擎 Lua 运行时（`luajit` / `5.1` ~ `5.5`），随请求 `lua_version` 下发。 */
    var generateLuaVersion: String by mutableStateOf("luajit")

    var generateRunning: Boolean by mutableStateOf(false)

    /** 终止帧回填：已处理的脚本数。 */
    var generateTablesProcessed: Int by mutableStateOf(0)

    var generateError: String? by mutableStateOf(null)

    /** 造数代次 —— 同 [SqlSheet.generation]：切连接 / 重新执行后使 in-flight 进度帧失效。 */
    private var generateGeneration: Int = 0

    /**
     * 造数日志 —— 引擎每回传一帧 `gen_progress_frame` 就追加一行。
     *
     * 数据源是引擎**已经在发**的流（每条 INSERT 一帧），引擎侧无需改动；
     * 前端此前只拿它更新计数器，那些信息其实已经够拼出一份实时日志了。
     *
     * 有界（[GENERATE_LOG_MAX_LINES] 行）—— 十万行造数不能把界面撑爆；
     * 丢掉的行数记在 [generateLogDropped] 里**显式告知**，不静默截断。
     */
    val generateLog: SnapshotStateList<GenerateLogEntry> = mutableStateListOf()

    /** 因超出 [GENERATE_LOG_MAX_LINES] 而被丢掉的行数 —— 显式告知，不静默截断。 */
    var generateLogDropped: Int by mutableStateOf(0)

    /**
     * 日志的**追加序号** —— 每次追加 +1。
     *
     * ## 为什么自动滚动要盯它，而不是盯条数
     *
     * 条数在丢行时**不是单调的**：一次成批丢行会让它从 `MAX + BATCH` 掉回 `MAX`
     * （见 [GENERATE_LOG_DROP_BATCH]）。于是「条数变了」既可能是「又来了一行」，
     * 也可能是「旧的被丢了」—— 两种情况指向的滚动目标不一样。
     *
     * 序号每次追加**只 +1**，所以「又来了一行」只有**一个**信号、
     * **一个**目标：最新那一行。
     */
    var generateLogSeq: Int by mutableStateOf(0)

    /**
     * 追加一行造数日志。
     *
     * 满了就**成批**丢最旧的（见 [GENERATE_LOG_DROP_BATCH] 的理由）。
     *
     * 可见性是 `internal` 而不是 `private`：测试要能**走同一条路**灌日志，
     * 才能验证「有界缓冲 + 丢行计数 + 序号递增」这三条契约 ——
     * 而用真引擎灌一千多行既慢又不确定（引擎自己也会丢帧，见 [GENERATE_LOG_MAX_LINES]）。
     */
    internal fun appendGenerateLog(entry: GenerateLogEntry) {
        generateLog.add(entry)
        generateLogSeq++
        if (generateLog.size > GENERATE_LOG_MAX_LINES + GENERATE_LOG_DROP_BATCH) {
            val drop = generateLog.size - GENERATE_LOG_MAX_LINES
            generateLog.removeRange(0, drop)
            generateLogDropped += drop
        }
    }

    /** 清空造数日志 —— 新一轮造数开始时调用（日志属于**这一轮**）。 */
    private fun clearGenerateLog() {
        generateLog.clear()
        generateLogDropped = 0
        generateLogSeq++
    }

    /**
     * 造数失败时写进日志的那一行 —— 失败原因原文进 [GenerateLogEntry.error]。
     *
     * `scriptIndex = 0` 表示「不属于任何脚本」—— 失败可能发生在引擎接到请求之前
     * （比如脚本为空），那时还没有具体的脚本上下文。
     *
     * 原因取不到时退回通用文案：日志里写一行空理由，
     * 等于把「失败了但不知道为什么」这件事又说了一遍。
     */
    private fun generateFailureEntry(message: String?): GenerateLogEntry =
        GenerateLogEntry(
            scriptIndex = 0,
            totalScripts = 0,
            table = "",
            rowNumber = 0,
            values = emptyMap(),
            error = message?.takeIf { it.isNotBlank() } ?: Msg.text(StrKey.GENERATE_FAILED),
        )

    /** 当前选中的脚本（越界时为 null —— 例如用户删掉了最后一个脚本）。 */
    fun currentGenerateScript(): GenerateScript? = generateScripts.getOrNull(selectedGenerateIndex)

    /** 新增一个脚本并选中它 —— 初始内容为 [GENERATE_SCRIPT_TEMPLATE] 模板。 */
    fun addGenerateScript() {
        generateScripts.add(GenerateScript(Msg.format(StrKey.SCRIPT_NTH_NAME, generateScripts.size + 1)))
        selectedGenerateIndex = generateScripts.lastIndex
    }

    /** 删除脚本；删掉后选中项跟随回退（与预览标签页 `closeTab` 同策略）。 */
    fun removeGenerateScript(index: Int) {
        if (index !in generateScripts.indices) return
        generateScripts.removeAt(index)
        selectedGenerateIndex =
            when {
                generateScripts.isEmpty() -> -1
                index >= generateScripts.size -> generateScripts.lastIndex
                else -> index
            }
    }

    fun selectGenerateScript(index: Int) {
        if (index in generateScripts.indices) selectedGenerateIndex = index
    }

    /** 重命名造数脚本 —— 规则同 [renameSqlSheet]：去首尾空白，空名保持原名。 */
    fun renameGenerateScript(
        index: Int,
        title: String,
    ) {
        val script = generateScripts.getOrNull(index) ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        script.title = trimmed
    }

    /** 最近一次造数插入的总行数（各脚本累计）。 */
    val generateTotalInserted: Long get() = generateScripts.sumOf { it.inserted }

    /** 是否已有可展示的造数结果（至少一个脚本插过行）。 */
    val hasGenerateResult: Boolean get() = generateScripts.any { it.inserted > 0 }

    /**
     * 执行造数 —— 走 `Category.DATA` / `Action.GENERATE` 流式通道：
     * 每条 INSERT 回一帧 `gen_progress_frame`（含 `script_index` / `inserted` / `table`），
     * 终止帧 `generate_terminal.tables_processed` 是已处理脚本数；失败 → [generateError]。
     *
     * 与 [executeSql] 一样用 [generateGeneration] 失效化 in-flight 响应：
     * 重新执行或切换连接后，迟到的进度帧直接丢弃。
     */
    fun executeGenerate() {
        currentConnection ?: return
        // 日志属于**这一轮**造数 —— 一开始就清空，
        // 否则两轮的行会混在一起，看不出哪一行是哪一轮插的。
        clearGenerateLog()
        val scripts = generateScripts.filter { it.editor.text.isNotBlank() }
        if (scripts.isEmpty()) {
            generateError = Msg.text(StrKey.GENERATE_SCRIPT_EMPTY)
            // 失败也进日志 —— 一份不显示失败的日志是不完整的。
            appendGenerateLog(generateFailureEntry(generateError))
            return
        }
        generateGeneration++
        val gen = generateGeneration
        generateRunning = true
        generateError = null
        generateTablesProcessed = 0
        generateScripts.forEach {
            it.inserted = 0
            it.lastTable = ""
        }
        val schema = currentSchema()
        val luaVersion = generateLuaVersion
        scope.launch {
            // 同 executeSql：登记本请求建池所处的 catalog 维度，供 releasePools 回收。
            activeDatabases.add(schema)
            val result =
                runCatching {
                    engine.handle(
                        request {
                            id = UUID.randomUUID().toString()
                            this.connection = engineConn(database = schema)
                            category = Category.DATA
                            action = Action.GENERATE
                            dataRequest =
                                dataRequest {
                                    generate =
                                        dataGenerateRequest {
                                            // 同 executeSql：schema 必须留空，不能塞 catalog 名。
                                            this.schema = ""
                                            this.luaVersion = luaVersion
                                            // 进度帧的 script_index 是**本次请求内**的下标，
                                            // 因此回填时也按这个过滤后的列表定位。
                                            scripts.forEach { tables += generateTable { script = it.editor.text } }
                                        }
                                }
                        },
                    )
                }
            if (gen != generateGeneration) return@launch // 已重新执行 —— 丢弃过期响应
            val flow = result.getOrNull()
            if (flow == null) {
                generateError = result.exceptionOrNull()?.message ?: Msg.text(StrKey.GENERATE_FAILED)
                appendGenerateLog(generateFailureEntry(generateError))
                generateRunning = false
                return@launch
            }
            try {
                var cancelled = false
                flow.collect { resp ->
                    if (cancelled) return@collect
                    if (gen != generateGeneration) {
                        cancelled = true
                        return@collect
                    }
                    if (!resp.success) {
                        generateError = resp.error.ifBlank { Msg.text(StrKey.GENERATE_FAILED) }
                        // 失败也进日志 —— 一份不显示失败的日志是不完整的：
                        // 用户在日志里看到「插到第 37 行」然后就没了，
                        // 与「日志里明写失败原因」差着一整个排查过程。
                        appendGenerateLog(generateFailureEntry(generateError))
                    } else if (resp.hasGenProgressFrame()) {
                        val frame = resp.genProgressFrame
                        // 引擎的 `script_index` 是 **1-based**（GenerateHandler 发帧时 +1），
                        // 这里转成 0-based 再定位到本次请求的脚本列表。
                        scripts.getOrNull(frame.scriptIndex - 1)?.let { script ->
                            script.inserted = frame.inserted
                            if (frame.table.isNotBlank()) script.lastTable = frame.table
                        }
                        // 追加一行日志 —— 引擎每条 INSERT 回传一帧，这里就是「实时日志」的数据源。
                        appendGenerateLog(generateLogEntryOf(frame, ::cellValueToAny))
                    } else if (resp.hasGenerateTerminal()) {
                        generateTablesProcessed = resp.generateTerminal.tablesProcessed
                    }
                }
            } catch (e: Exception) {
                // 同 executeSql：错误与 running 必须一起在代次守卫内，
                // 否则上一次造数请求的异常会抹掉新请求的「执行中」，用户就会再点一次，两次造数叠着跑。
                if (gen == generateGeneration) {
                    generateError = e.message ?: e.javaClass.simpleName
                    appendGenerateLog(generateFailureEntry(generateError))
                    generateRunning = false
                }
                return@launch
            }
            if (gen != generateGeneration) return@launch
            if (generateError == null && !hasGenerateResult) generateError = Msg.text(StrKey.NO_DATA_INSERTED)
            generateRunning = false
        }
    }

    /**
     * 本屏在当前连接下**建立过连接池**的 database 维度（`""` = 默认 catalog）。
     *
     * 浏览另一个库会用到另一份 proto config（catalog 不同 → 池 key 不同），因此连接管理页的
     * 「断开」只能释放它自己那份池。本屏在**切换连接**或**会话不再连接**时用 [releasePools]
     * 逐个释放自己建立的池，避免遗留孤儿池。
     */
    private val activeDatabases = mutableSetOf<String>()

    // ------------------------------------------------------------------------
    // 公开操作
    // ------------------------------------------------------------------------

    /**
     * 注入/切换当前连接。
     *
     * 连接变化时**清空全部派生状态** —— 数据库列表 / 已展开节点 / 表缓存 / 标签页
     * 都属于上一个连接的会话，跨连接保留会展示错误(甚至不存在的)对象。同一连接重复调用是空操作。
     */
    fun bindConnection(config: ConnectionConfig?) {
        if (currentConnection?.id == config?.id) return
        // 先释放上一连接在本屏建立的池（池 key 含连接字段，换连接后不再可达）。
        // releasePools 内部会调 invalidateInFlight —— 代次自增 / loading 复位都在那里做，
        // 这里**不要**再写一遍：曾经两份各写一半，漏掉的那一半就是「切连接后转圈停不下来」的来源。
        releasePools()
        currentConnection = config
        databases = emptyList()
        errorMessage = null
        expandedDatabases.clear()
        _tablesByDatabase.clear()
        _tableLoadError.clear()
        tabs = emptyList()
        selectedTabIndex = -1
        // SQL 工作台状态跨连接无意义 —— 整体复位为单个空 sheet。旧 sheet 对象被丢弃，
        // 其 in-flight 响应写进已脱离的实例，不会再出现在 UI 上；代次已在 invalidateInFlight 里自增。
        sqlSheets.clear()
        sqlSheets.add(SqlSheet("SQL 1"))
        selectedSqlIndex = 0
        // 造数工作台同理 —— 脚本与统计都绑定在上一连接上，整体复位
        generateScripts.clear()
        generateScripts.add(GenerateScript(Msg.text(StrKey.SCRIPT_DEFAULT_NAME)))
        selectedGenerateIndex = 0
        generateTablesProcessed = 0
        generateError = null
        // 日志也是上一连接的东西 —— 跟着脚本一起复位。
        clearGenerateLog()
    }

    /**
     * 释放本屏为当前连接建立的全部连接池（含各 database 维度）。
     *
     * 在「切换连接」与「会话不再处于已连接」时调用；不改动其它配置或其它连接管理页建立的池。
     */
    fun releasePools() {
        // **必须**在下面两行早退之前作废 in-flight：「没有当前连接」和「一个池都没建过」
        // 都是合法且常见的路径（首次进入就断开 / 只读了一屏就断开），跳过这一步的后果见
        // [invalidateInFlight] 的 KDoc —— 最典型的是界面永远卡在「执行中…」。
        invalidateInFlight()
        val config = currentConnection ?: return
        if (activeDatabases.isEmpty()) return
        val targets = activeDatabases.toList()
        activeDatabases.clear()
        scope.launch {
            targets.forEach { database ->
                runCatching { engine.disconnect(engineConnFor(config, database)) }
            }
        }
    }

    /**
     * 作废本屏全部 in-flight 请求，并复位所有「正在执行 / 正在加载」标志。
     *
     * 池被释放**不会**让先前的请求自己结束：引擎端的流可能还挂着，协程还活着并仍会往
     * 已复位（或已换连接）的状态里写。不同步作废就有两个后果：
     *
     * 1. **转圈停不下来** —— 唯一会写 `running = false` 的是那条协程本身，而它此时要么已被
     *    代次挡住提前 return、要么还卡在流上。界面于是永远显示「执行中…」，用户既看不到结果
     *    也无法再点一次。
     * 2. **重连后旧数据继续写入** —— 同一个 sheet 对象跨断开/重连是存活的，上一条连接的
     *    行帧会落进新连接的结果表。
     *
     * 标签页的 `loading` 同样要复位：`loadTabPreview` 在代次不符时是 `return@launch` **早退**、
     * 不清 loading，漏掉这一项表预览就会一直转。
     */
    private fun invalidateInFlight() {
        generation++
        loadingDatabases = false
        loadingTables.clear()
        sqlSheets.forEach { sheet ->
            sheet.generation++
            sheet.running = false
            // 断开后请求已经作废，留着 id 会让「停止」按钮继续挂在界面上 ——
            // 而那个 id 对应的引擎侧执行早已不存在，点了只会回一句「找不到目标」。
            sheet.runningRequestId = null
        }
        generateGeneration++
        generateRunning = false
        // 预览同理：自增 tab 级代次让在飞的载入作废，再手动清 loading ——
        // `loadTabPreview` 的守卫是**早退**（不清 loading），漏了这一项表预览会一直转。
        tabs.forEach {
            it.requestGeneration++
            it.loading = false
        }
    }

    fun refreshDatabases() {
        val requestGeneration = generation
        loadingDatabases = true
        errorMessage = null
        activeDatabases.add("") // 该请求会用到默认 catalog 的池
        scope.launch {
            val result =
                runCatching {
                    engine.invoke(engineConn(database = "")) {
                        category = Category.SCHEMA
                        action = Action.LIST
                        schemaRequest =
                            schemaRequest {
                                list = schemaListRequest { level = "database" }
                            }
                    }
                }
            // 连接已切换 —— 丢弃过期响应。
            //
            // ⚠️ **这里不补 `loadingDatabases = false` 是有意的，不要「顺手修好」**：
            // `generation` 只由 [invalidateInFlight] 递增，而它**正在**清 `loadingDatabases`；
            // 断开后新连接会立刻重新调用 [refreshDatabases] 并自己清。
            // 在这里清会把**新**连接刚开始的转圈一并抹掉 —— 用户看到的是
            // 「库列表空着、又不转圈」，比转圈更让人以为已经加载完。
            if (requestGeneration != generation) return@launch
            // 先写数据、最后清 loading —— 顺序反了会让「轮询 loading」的调用方
            // （DatabaseBrowserFlowTest / 未来任何等待逻辑）读到 loading=false 却拿到旧数据。
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        errorMessage = resp.error.ifBlank { Msg.text(StrKey.LOAD_DATABASES_FAILED) }
                        databases = emptyList()
                    } else {
                        errorMessage = null
                        databases = resp.schema.list.itemsList
                    }
                },
                onFailure = { errorMessage = it.message ?: "Unknown error" },
            )
            loadingDatabases = false
        }
    }

    fun toggleDatabase(name: String) {
        if (expandedDatabases.contains(name)) {
            expandedDatabases.remove(name)
        } else {
            expandedDatabases.add(name)
            // 表列表**每次展开都重新拉**，不做「拉过就跳过」——
            // 用户在**别处**（SQL 工作台、造数、另一个客户端）建的表，这里若不重拉就永远看不到，
            // 而「树里看不到刚建的表」正是用户最不能接受的一种「列表不更新」。
            //
            // 早先的判据是 `_tablesByDatabase[name] == null`（只在第一次展开时拉），
            // 与下面那段注释自相矛盾：结果就是外部建了表，树上永远是那份会话初期的快照。
            // 展开是低频动作，多三次往返换来正确性，划算。
            //
            // 只在「当前没有在飞的拉取」时发起，避免连点展开时堆一串重复请求。
            if (!loadingTables.contains(name)) {
                loadTables(name)
            }
            // 库级对象与表**并发**拉：它们互不依赖，串行会让展开一棵库慢一倍。
            loadDatabaseObjects(name)
        }
    }

    private fun loadTables(database: String) {
        val requestGeneration = generation
        loadingTables.add(database)
        _tableLoadError.remove(database)
        activeDatabases.add(database) // 该请求会用到 database 维度上的池
        scope.launch {
            val result =
                runCatching {
                    engine.invoke(engineConn(database = database)) {
                        category = Category.TABLE
                        action = Action.LIST
                        tableRequest =
                            tableRequest {
                                list = tableListRequest {}
                            }
                    }
                }
            // 同 [refreshDatabases]：早退**不**清 `loadingTables`（理由见那里的注释），
            // 清理点同样只有 [invalidateInFlight] 的 `loadingTables.clear()`
            if (requestGeneration != generation) return@launch
            // 同 refreshDatabases：先写数据再清 loading
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        _tableLoadError[database] = resp.error.ifBlank { Msg.text(StrKey.LOAD_TABLES_FAILED) }
                    } else {
                        _tablesByDatabase[database] =
                            resp.table.list.itemsList
                                .map { it.name }
                    }
                },
                onFailure = {
                    _tableLoadError[database] = it.message ?: "Unknown error"
                },
            )
            loadingTables.remove(database)
        }
    }

    /**
     * 拉某个库的**库级对象**（视图 / 触发器 / 过程·函数）。
     *
     * ## 先解析 schema 名，再拉对象 —— 不能拿库名凑
     *
     * `VIEW.LIST` / `TRIGGER.LIST` / `FUNCTION.LIST` 的 `schema` 参数要的是
     * **schema 名**（H2 恒为 `PUBLIC`、PG 为 `public`、SQLite 为 `main`），
     * 而我们手上只有**库名**（catalog）—— 两者不是一回事，这正是 [engineConnFor]
     * 的 KDoc 反复警告的那条：把库名塞进 `schema` 会让 H2 执行
     * `SET SCHEMA "<库名>"` → `Schema not found`。
     *
     * 所以先走一次 `SCHEMA.LIST level=schema` 拿到**真实的 schema 列表**，再对每个
     * schema 拉三类对象。多数库只有一个默认 schema，代价是多一次往返；
     * 换来的是「不靠猜」—— 前端没有任何地方硬编码 `PUBLIC` 之类。
     *
     * @param resolvedSchemas 由 [loadSchemasOf] 解析出的 schema 名；为空表示还没解析完
     */
    private suspend fun loadDatabaseObjectsFor(
        database: String,
        resolvedSchemas: List<String>,
    ) {
        for (kind in DatabaseObjectKind.entries) {
            loadingObjects += database to kind
            scope.launch {
                val names = mutableListOf<String>()
                var failure: String? = null
                // 一个 schema 失败不该让其他 schema 的同类对象也消失
                for (s in resolvedSchemas) {
                    val resp =
                        runCatching {
                            engine.invoke(engineConn(database = database)) {
                                category =
                                    when (kind) {
                                        DatabaseObjectKind.VIEW -> Category.VIEW
                                        DatabaseObjectKind.TRIGGER -> Category.TRIGGER
                                        DatabaseObjectKind.FUNCTION -> Category.FUNCTION
                                    }
                                action = Action.LIST
                                when (kind) {
                                    DatabaseObjectKind.VIEW -> {
                                        viewRequest = viewRequest { list = viewListRequest { schema = s } }
                                    }

                                    DatabaseObjectKind.TRIGGER -> {
                                        triggerRequest = triggerRequest { list = triggerListRequest { schema = s } }
                                    }

                                    DatabaseObjectKind.FUNCTION -> {
                                        functionRequest =
                                            functionRequest {
                                                list = functionListRequest { schema = s }
                                            }
                                    }
                                }
                            }
                        }.getOrNull()
                    when {
                        resp == null -> {
                            failure = failure ?: Msg.text(StrKey.ENGINE_NO_RESPONSE)
                        }

                        !resp.success -> {
                            failure = failure ?: resp.error.ifBlank { Msg.text(StrKey.LOAD_FAILED) }
                        }

                        else -> {
                            // 五类的 list 元素都是消息（视图带 definition、触发器带
                            // timing/event 等），树里只显示 `.name`；其余字段留给
                            // 将来的对象详情面板。
                            names +=
                                when (kind) {
                                    DatabaseObjectKind.VIEW -> {
                                        resp.view.list.itemsList
                                            .map { it.name }
                                    }

                                    DatabaseObjectKind.TRIGGER -> {
                                        resp.trigger.list.itemsList
                                            .map { it.name }
                                    }

                                    DatabaseObjectKind.FUNCTION -> {
                                        resp.function.list.itemsList
                                            .map { it.name }
                                    }
                                }
                        }
                    }
                }
                // 先写数据、最后清 loading —— 与 refreshDatabases 同一个顺序约定
                if (failure != null) {
                    _objectLoadError["$database::${kind.name}"] = failure
                } else {
                    _objectsByDatabase[database] =
                        (_objectsByDatabase[database] ?: emptyMap())
                            .plus(kind to names.filter { it.isNotBlank() }.distinct())
                }
                loadingObjects.remove(database to kind)
            }
        }
    }

    /**
     * 解析某个库下的 schema 名，然后拉它的库级对象。
     *
     * `SCHEMA.LIST level=schema` 在 **MySQL 上抛 `UnsupportedOperationException`**
     * （MySQL 的 database 与 schema 合一，没有这层概念）。那种情况下对象列表
     * **本来就无从谈起**，于是走 `emptyList()` 跳过并把 loading 清掉 ——
     * 界面显示「(无)」而不是永远转圈。
     */
    fun loadDatabaseObjects(database: String) {
        for (kind in DatabaseObjectKind.entries) {
            loadingObjects += database to kind
        }
        scope.launch {
            val schemas = resolveSchemas(database)

            // 先撤掉占位：真正的 loading 由 loadDatabaseObjectsFor 重新置上，
            // 否则「解析 schema 期间」和「拉对象期间」之间会有一段假转圈
            DatabaseObjectKind.entries.forEach { loadingObjects.remove(database to it) }
            if (schemas.isEmpty()) {
                // 写一份空结果：界面显示「(无)」，不再反复重试
                DatabaseObjectKind.entries.forEach { k ->
                    _objectsByDatabase[database] =
                        (_objectsByDatabase[database] ?: emptyMap()).plus(k to emptyList<String>())
                }
                return@launch
            }
            loadDatabaseObjectsFor(database, schemas)
        }
    }

    /**
     * 解析某个库的 schema 名列表并缓存 —— **可挂起**，调用方能真的等到结果。
     *
     * 抽出来的理由：库级对象与表级对象（索引 / 外键）都要这一步，而表级对象必须
     * **拿到结果再发查询**（见 [loadTableObjects]）。原先只有 `loadDatabaseObjects`
     * 那个「发射后不等」的版本，于是表级对象只能带着空 schema 去查 —— 请求成功、
     * 结果为空、界面显示「(无)」且不重试。
     */
    private suspend fun resolveSchemas(database: String): List<String> {
        _schemasByDatabase[database]?.let { return it }
        val schemas =
            runCatching {
                engine.invoke(engineConn(database = database)) {
                    category = Category.SCHEMA
                    action = Action.LIST
                    schemaRequest =
                        schemaRequest {
                            list =
                                schemaListRequest {
                                    level = "schema"
                                    this.database = database
                                }
                        }
                }
            }.getOrNull()?.takeIf { it.success }?.schema?.list?.itemsList.orEmpty()
        _schemasByDatabase[database] = schemas
        return schemas
    }

    /**
     * 拉某张表的**表级对象**（索引 / 外键）。
     *
     * 引擎侧这两类要 `table_name`（`IndexListRequest` / `ForeignKeyListRequest`），
     * 所以不能跟库级对象一起拉 —— 那是「每张表一次往返」，一张几十列的库直接爆炸。
     * 代价是它们只在**单独展开表节点时**才可见，这也是 DataGrip 的做法。
     *
     * ## 为什么必须**先等 schema 解析完**再发查询
     *
     * `INDEX.LIST` / `FOREIGN_KEY.LIST` 的 `schema` 参数要的是 **schema 名**
     * （H2 恒为 `PUBLIC`、SQLite 为 `main`），空串会被当成「默认 schema」而查不到东西 ——
     * H2 的 `listIndexes` 明确要求 `PUBLIC`。
     *
     * 原实现是「schema 没解析就**发起**一次解析，然后**立刻**带着空 schema 去查」。
     * 而 `toggleDatabase` 展开库时是**并发**拉库级对象与表列表的，用户在 schema 往返
     * 返回之前点开某张表的索引，查询就带着空 schema 发了出去：请求**成功**、
     * 返回**空列表**，界面显示「(无)」，且**没有任何重试**。用户必须收起再展开一次才看得到。
     *
     * 「请求成功但结果是空的」比「请求失败」更难察觉 —— 失败会有红色提示，
     * 空列表看上去像「这张表本来就没有索引」。所以这里改成：解析完再查。
     *
     * 代价是最坏情况多等一个往返（schema 通常已在展开库时解析好了）。
     */
    fun loadTableObjects(
        database: String,
        table: String,
    ) {
        val slot = "$database::$table"
        for (kind in TableObjectKind.entries) {
            loadingTableObjects += slot to kind
        }
        scope.launch {
            // schema 没解析就**等它** —— 见上方「为什么必须先等」。解析不出来（MySQL 那类
            // 没有 schema 概念的方言）时拿到空列表，那正是它应有的样子。
            val schema = resolveSchemas(database).firstOrNull() ?: ""
            for (kind in TableObjectKind.entries) {
                val resp =
                    runCatching {
                        engine.invoke(engineConn(database = database)) {
                            category =
                                when (kind) {
                                    TableObjectKind.INDEX -> Category.INDEX
                                    TableObjectKind.FOREIGN_KEY -> Category.FOREIGN_KEY
                                    TableObjectKind.COLUMNS -> Category.TABLE
                                }
                            action = Action.LIST
                            when (kind) {
                                TableObjectKind.INDEX -> {
                                    indexRequest =
                                        indexRequest {
                                            list =
                                                indexListRequest {
                                                    tableName = table
                                                    this.schema = schema
                                                }
                                        }
                                }

                                TableObjectKind.FOREIGN_KEY -> {
                                    foreignKeyRequest =
                                        foreignKeyRequest {
                                            list =
                                                foreignKeyListRequest {
                                                    tableName = table
                                                    this.schema = schema
                                                }
                                        }
                                }

                                // 字段走 `TABLE.LIST` 的 **column_list** 分支（`TableRequest` 的 oneof 里
                                // `list` 是表列表、`column_list` 才是列列表 —— 两个字段名只差前缀，
                                // 写错编译期根本发现不了，只会拿到「空列表」的假成功）。
                                // ⚠️ 这里**不能**把 catalog 塞进 `schema` —— 那会让 H2 执行
                                // `SET SCHEMA "<库名>"` 而报「Schema not found」（同 engineConnFor 的 KDoc）。
                                TableObjectKind.COLUMNS -> {
                                    tableRequest =
                                        tableRequest {
                                            columnList =
                                                tableColumnListRequest {
                                                    tableName = table
                                                    this.schema = schema
                                                }
                                        }
                                }
                            }
                        }
                    }.getOrNull()
                // ⚠️ 失败必须**就地留文案**，不能静默 —— 「请求成功但结果为空」看上去像
                // 「这张表没有索引 / 没有字段」，那是比报错更难察觉的一类（见本函数上方注释）。
                if (resp != null && resp.success) {
                    when (kind) {
                        TableObjectKind.INDEX -> {
                            _tableObjects[slot] =
                                (_tableObjects[slot] ?: emptyMap())
                                    .plus(
                                        kind to
                                            resp.index.list.itemsList
                                                .map { it.name },
                                    )
                        }

                        TableObjectKind.FOREIGN_KEY -> {
                            _tableObjects[slot] =
                                (_tableObjects[slot] ?: emptyMap())
                                    .plus(
                                        kind to
                                            resp.foreignKey.list.itemsList
                                                .map { it.name },
                                    )
                        }

                        TableObjectKind.COLUMNS -> {
                            _tableColumns[slot] =
                                resp.table.columns.itemsList.map {
                                    ColumnInfo(
                                        name = it.name,
                                        type = it.type,
                                        size = it.size,
                                        // proto3 的 `optional bool nullable` 有 presence 语义：
                                        // 没给就是 null，按「默认可空」处理，与引擎的 mapper 一致。
                                        nullable = if (it.hasNullable()) it.nullable else true,
                                        primaryKey = it.isPrimaryKey,
                                        autoIncrement = it.autoIncrement,
                                    )
                                }
                        }
                    }
                    _tableObjectError.remove("$slot::${kind.name}")
                } else {
                    _tableObjectError["$slot::${kind.name}"] =
                        resp?.error?.takeIf { it.isNotBlank() } ?: Msg.text(StrKey.LOAD_FAILED)
                }
                loadingTableObjects.remove(slot to kind)
            }
        }
    }

    /**
     * 打开表的预览标签页 —— 同一表已存在则激活之, 不重复加载.
     */
    fun openTab(
        schema: String,
        tableName: String,
    ) {
        val tabKey = "$schema::$tableName"
        val existingIndex = tabs.indexOfFirst { it.key == tabKey }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TablePreviewTab(schema = schema, tableName = tableName)
        // 记下方言：表内搜索的 `CAST` 目标类型逐方言不同（MySQL 只认 CHAR），
        // 而 tab 是跟着连接走的，构造这一刻就知道自己属于谁
        tab.dialect = currentConnection?.dialect
        tabs = tabs + tab
        selectedTabIndex = tabs.size - 1
        loadTabPreview(tab)
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) {
            selectedTabIndex = index
        }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val newTabs = tabs.toMutableList().also { it.removeAt(index) }
        tabs = newTabs
        selectedTabIndex =
            when {
                newTabs.isEmpty() -> -1
                index >= newTabs.size -> newTabs.size - 1
                else -> index
            }
    }

    /**
     * 翻到指定页。页码先落到 tab 上再发请求，这样加载中信息条显示的就是**目标页**
     * 而不是上一页（否则会出现「点了下一页，转圈时却写着第 1 页」的错位）。
     */
    fun goToTabPage(
        tab: TablePreviewTab,
        page: Int,
    ) {
        if (page < 1 || page == tab.page) return
        tab.page = page
        // 换页后旧页的选中行不再存在于本页，清掉避免详情面板显示上一批数据
        tab.rows = emptyList()
        // 同理清掉「待删除的勾选」：那些行已经不在屏幕上了，保留它们等于让用户
        // 在看不见的地方攒一批删除 —— 点「删除选中行」时会删掉一批他根本没检查过的行。
        tab.selectedRowIds = emptySet()
        loadTabPreview(tab, page)
    }

    /**
     * 改分页大小。**必须回到第 1 页** —— 页大小变了以后旧页码多半越界
     * （100 行/页的第 5 页在 20 行/页下变成第 25 页，而表只有 100 行 = 5 页），
     * 不归位就会停在一个永远加载不出内容、又没有「越界回退」可救的页上。
     */
    fun changeTabPageSize(
        tab: TablePreviewTab,
        size: PageSize,
    ) {
        val next = size.value.coerceAtLeast(1)
        if (next == tab.pageSize) return
        tab.pageSize = next
        tab.page = 1
        tab.rows = emptyList()
        // 同 goToTabPage：旧页的勾选在新页里不存在，见那里的说明。
        tab.selectedRowIds = emptySet()
        loadTabPreview(tab, targetPage = 1)
    }

    /**
     * 改过滤 / 排序条件 —— **必须回到第 1 页**。
     *
     * 在第 3 页把过滤条件收紧，结果集可能只剩 2 页；停在第 3 页用户看到的是
     * 一张**空表**，而引擎没报错、总数也变了 —— 那是最难自查的一种「看起来坏了」。
     */
    fun setTabFilter(
        tab: TablePreviewTab,
        where: String,
    ) {
        if (tab.whereClause == where) return
        tab.whereClause = where
        tab.page = 1
        // 过滤一变结果集就整个换了，勾选的行没有对应的「这一页」了（同 goToTabPage）。
        tab.selectedRowIds = emptySet()
        loadTabPreview(tab, targetPage = 1)
    }

    fun setTabOrderBy(
        tab: TablePreviewTab,
        orderBy: String,
    ) {
        if (tab.orderByClause == orderBy) return
        tab.orderByClause = orderBy
        tab.page = 1
        // 换排序会换掉「第 N 行」对应的数据 —— 之前勾的选中行是另一批行。
        tab.selectedRowIds = emptySet()
        loadTabPreview(tab, targetPage = 1)
    }

    fun setTabSearch(
        tab: TablePreviewTab,
        term: String,
    ) {
        if (tab.searchTerm == term) return
        tab.searchTerm = term
        tab.page = 1
        tab.selectedRowIds = emptySet()
        loadTabPreview(tab, targetPage = 1)
    }

    /**
     * 载入某个表预览标签页的**指定页**。
     *
     * [targetPage] 默认取 tab 自己记的页码：翻页是「改 tab.page + 重新载入」这一对动作，
     * 页码的唯一真相源在 tab 上，把默认值放在这里可以让首次打开（tab.page 初始为 1）
     * 与翻页共用同一条路径，不会出现两条逻辑漂移。
     *
     * 参数名不叫 `page`：`dataListRequest` 的 DSL 里同名属性会把它解析成 builder 自己的
     * `val page`，导致「给 builder 赋值」变成给只读局部变量赋值，编译直接失败。
     */
    private fun loadTabPreview(
        tab: TablePreviewTab,
        targetPage: Int = tab.page,
    ) {
        val sessionGeneration = generation
        // tab 级代次：同一张表上的并发载入靠它区分。连翻两页时两次请求都在飞，
        // 没有这个令牌就是「谁后返回谁说了算」—— 慢的第 1 页会盖掉快的第 2 页。
        tab.requestGeneration++
        val tabGeneration = tab.requestGeneration
        tab.loading = true
        tab.error = null
        activeDatabases.add(tab.schema) // 预览用的池绑定在 tab.schema 这个 catalog 上
        scope.launch {
            // 表结构只取一次：它不随翻页变化，而翻页是高频动作。
            // 与数据请求并行发出，避免每次打开 tab 都串行多等一个往返。
            val result =
                runCatching {
                    engine.invoke(engineConn(database = tab.schema)) {
                        category = Category.DATA
                        action = Action.LIST
                        dataRequest =
                            dataRequest {
                                list =
                                    dataListRequest {
                                        tableName = tab.tableName
                                        // **不能写死 1**：写死等于分页器是装饰品 —— 引擎每次都回第 1 页，
                                        // 而表超过一页时用户永远看不到后面的行。
                                        page = targetPage.coerceAtLeast(1)
                                        // `pageSize = 0` 在 DATA.LIST 里是**流式**哨兵（逐行 frame，无 paged body），
                                        // 预览固定走分页路径，故下限钳到 1。
                                        pageSize = tab.pageSize.coerceAtLeast(1)
                                        // 过滤与排序**全部交给引擎**：`where` / `order_by` 是裸 SQL 片段，
                                        // 引擎侧 `DatabaseDialect.validateOrderBy` 负责挡排序注入。
                                        // 前端不在本地过滤 —— 那样只能筛当前页，翻页后结果就不对了。
                                        where = tab.effectiveWhere().orEmpty()
                                        orderBy = tab.orderByClause.trim()
                                    }
                            }
                    }
                }
            // 两级守卫都要在**写任何状态之前**判：连接换了，或这个 tab 已经翻到别的页去了。
            // 早退时**不清 loading** 是有意的 —— 清它会把新一轮的转圈也一并抹掉。
            if (sessionGeneration != generation) return@launch
            if (tabGeneration != tab.requestGeneration) return@launch
            // 同 refreshDatabases / loadTables：先写数据、最后清 loading
            result.fold(
                onSuccess = { resp ->
                    if (!resp.success) {
                        tab.error = resp.error.ifBlank { Msg.text(StrKey.READ_FAILED) }
                        tab.columns = emptyList()
                        tab.rows = emptyList()
                    } else {
                        val paged = resp.data.list
                        tab.total = paged.total
                        tab.page = paged.page
                        tab.pageSize = paged.pageSize
                        // 写能力随数据一起回 —— 前端不再自己发 TABLE.COLUMN_LIST 去猜主键。
                        applyCapabilities(tab, if (paged.hasCapabilities()) paged.capabilities else null)
                        // 表可能为空 —— 此时 rowsList 为空, 退化到无列预览
                        val columnNames =
                            paged.rowsList
                                .flatMap { it.valuesMap.keys }
                                .distinct()
                        // 列顺序按引擎给的能力来（insertable / editable 都是按列序的），
                        // 表为空时也有表头 —— 否则空表就是一片白，连「这张表有哪些列」都看不到。
                        val orderedColumns =
                            (tab.insertableColumns + tab.readOnlyColumns + tab.primaryKeyColumns + columnNames)
                                .distinct()
                        tab.columns = orderedColumns.map { TableColumn(key = it, header = it) }
                        tab.rows =
                            paged.rowsList
                                .mapIndexed { idx, row ->
                                    // 主键承载: 大小写不敏感查找 "id" / "ID" / "Id" —— 不同方言
                                    // (H2 大写 / MySQL 小写) 列名归一策略不同; 找不到则退化为行号.
                                    val idCell = row.valuesMap.entries.firstOrNull { (k, _) -> k.equals("id", ignoreCase = true) }
                                    TableRow(
                                        id = idCell?.let { cellValueAsId(it.value) } ?: idx,
                                        cells = row.valuesMap.mapValues { (_, v) -> cellValueToAny(v) },
                                    )
                                }.let { ensureUniqueRowIds(it) }
                    }
                },
                onFailure = { tab.error = it.message ?: "Unknown error" },
            )
            tab.loading = false
        }
    }

    /**
     * 保证本页每一行的 [TableRow.id] 互不相同 —— 重复的改用**行号**。
     *
     * ## 为什么需要这一步
     *
     * 行 id 取自 `id` 列，而 **`id` 列未必是主键，更未必唯一**：
     * 一张 `t(id INT, code VARCHAR PRIMARY KEY)` 表里完全可以有 `(1,'a')` 和 `(1,'b')` 两行。
     * 这时会同时坏掉两处：
     *
     * - `DataTable` 的 `itemsIndexed(key = { row.id })` 会因为 key 重复**直接抛异常** ——
     *   整张表渲染不出来。
     * - 编辑暂存缓冲以 `rowId + 列名` 为键：两行撞成同一行，第二处改动会把第一处
     *   **静默覆盖**掉，用户看着「改了没反应」。
     *
     * 全部改用行号又不行 —— 翻页后行号会变，`selectedRowId` 就对不上了。
     * 所以只在**检测到重复时**才把重复的那些行换成行号，其余保持原样：
     * 绝大多数表（`id` 就是主键）行为完全不变。
     *
     * 行号只在本页内唯一，而行标识的全部用途（选中、暂存定位）都发生在本页，
     * 所以这已经足够。真正用来发 `UPDATE` 的 `WHERE` 走的是主键**单元格值**
     * （见 [applyOneCellRow]），跟行标识无关 —— 这里换成行号不影响正确性。
     */
    private fun ensureUniqueRowIds(rows: List<TableRow>): List<TableRow> {
        val duplicates =
            rows
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicates.isEmpty()) return rows
        return rows.mapIndexed { idx, row -> if (row.id in duplicates) row.copy(id = idx) else row }
    }

    /**
     * 把引擎随 `DATA.LIST` 回的 [com.langbiantianya.grpc.TableCapabilities] 落到 tab 上。
     *
     * ## 为什么不用前端自己发 `TABLE.COLUMN_LIST` 去推导
     *
     * 推导要三样东西：主键（拼 `WHERE`）、自增列（新增时不能填）、二进制列（不能编辑）。
     * 前端手里只有 `DATA.LIST` 的行数据，推不出任何一样 —— 而「拿 `id` 列当主键」
     * 这种猜法一旦猜错就是**静默改错行**，比不给编辑严重得多。
     *
     * 引擎那边已经为了填能力去查了一遍同一份列定义，再让前端并行发第二次查询，
     * 等于同一份元数据取两次、还可能两次结果不一致（比如中途 DDL 了）。
     * 所以宁可让引擎把结论直接算好随数据一起回。
     *
     * ## 缺字段（老引擎）时一律收紧
     *
     * `capabilities` 是 `optional`：老引擎不填时前端**不能**退回到「按主键存在就当能改」
     * —— 那正是当初把编辑整个关掉要防的误改。三个开关保持 `false`，
     * 写入口全部禁用，并在 [TablePreviewTab.notice] 上留一句说明。
     *
     * 不是 `private`：这条「老引擎 ⇒ 一律只读」的分支无法用正常流程测到 ——
     * 本机引擎一定会填 [com.langbiantianya.grpc.TableCapabilities]，
     * 只有直接喂 `null` 才走得到。
     */
    internal fun applyCapabilities(
        tab: TablePreviewTab,
        caps: com.langbiantianya.grpc.TableCapabilities?,
    ) {
        if (caps == null) {
            tab.capabilitiesLoaded = false
            tab.canUpdate = false
            tab.canInsert = false
            tab.canDelete = false
            tab.readOnlyColumns = emptySet()
            tab.nullableColumns = emptySet()
            tab.insertableColumns = emptyList()
            tab.primaryKeyColumns = emptyList()
            tab.notice = Msg.text(StrKey.EDIT_CAPABILITIES_MISSING)
            tab.noticeIsError = true
            return
        }
        tab.canUpdate = caps.canUpdate
        tab.canInsert = caps.canInsert
        tab.canDelete = caps.canDelete
        tab.readOnlyColumns = caps.readOnlyColumnsList.toSet()
        tab.nullableColumns = caps.nullableColumnsList.toSet()
        tab.insertableColumns = caps.insertableColumnsList.toList()
        tab.primaryKeyColumns = caps.primaryKeyColumnsList.toList()
        // 引擎把「空串 ≠ NULL」这件事显式声明出来了（`EMPTY_AS_STRING`）。前端据此
        // 决定编辑框里画不画「设为 NULL」的开关 —— 若哪天引擎改成 `EMPTY_AS_NULL`，
        // 界面会跟着变，而不会静默按旧约定写库。
        tab.emptyValueIsString = caps.emptyValuePolicy == com.langbiantianya.grpc.EmptyValuePolicy.EMPTY_AS_STRING
        tab.capabilitiesLoaded = true
    }

    // ------------------------------------------------------------------------
    // 引擎连接配置
    // ------------------------------------------------------------------------

    /** 以当前连接构造引擎 proto config（见 [engineConnFor]）。 */
    private fun engineConn(database: String): com.langbiantianya.grpc.ConnectionConfig = engineConnFor(currentConnection, database)

    /**
     * 构造引擎 proto [com.langbiantianya.grpc.ConnectionConfig].
     *
     * - [driver] 必填：`SchemaHandler` / `TableHandler` 等 handler 直接按 `config.driver` 取方言
     *   （`PoolManager` 才会优先按 `jdbcUrl` scheme 反查）。取值必须用
     *   [com.langbiantianya.sundays.connection.DialectType.engineDriverName] —— 引擎注册键是
     *   `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`，不是枚举常量名。
     * - [jdbcUrl] 是连接真相源；凭据一并带上，按默认 schema 借出的池即指向该 JDBC URL。
     * - [database] 是 catalog 维度：参与连接池 key，也是 `TableHandler.list` 传给方言的
     *   catalog 过滤值 —— 因此浏览另一个库会得到另一个池（这是有意的：一个池的连接绑定在一个 catalog 上）。
     *
     * 注意**不要**把 database 同步写入 `schema` 字段 —— 那会让 H2 执行 `SET SCHEMA <dbname>`
     * 并报 `Schema "X" not found`（H2 的 schema 是 `PUBLIC`，与 catalog 名无关）。留空即走
     * `CURRENT_SCHEMA` 默认行为。
     */
    private fun engineConnFor(
        config: ConnectionConfig?,
        database: String,
    ): com.langbiantianya.grpc.ConnectionConfig =
        connectionConfig {
            if (config != null) {
                driver = config.dialect.engineDriverName
                jdbcUrl = config.jdbcUrl
                user = config.username
                password = config.password
                if (database.isNotBlank()) {
                    this.database = database
                }
            } else if (database.isNotBlank()) {
                // 无选中连接 —— 引擎会因找不到方言 / 缺少 JDBC URL 返回 error, UI 显示即可.
                this.database = database
            }
        }

    private fun cellValueAsId(v: ProtoValue): Any =
        when (v.kindCase) {
            ProtoValue.KindCase.NUMBER_VALUE -> v.numberValue.toLong()
            ProtoValue.KindCase.STRING_VALUE -> v.stringValue
            ProtoValue.KindCase.BOOL_VALUE -> v.boolValue.toString()
            else -> v.toString()
        }

    private fun cellValueToAny(v: ProtoValue): Any? =
        when (v.kindCase) {
            ProtoValue.KindCase.NULL_VALUE -> null
            ProtoValue.KindCase.NUMBER_VALUE -> v.numberValue
            ProtoValue.KindCase.STRING_VALUE -> v.stringValue
            ProtoValue.KindCase.BOOL_VALUE -> v.boolValue
            else -> v.toString()
        }
}
