/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.table.TableRow

/*
 * 分析模式的数据模型 —— 树、结果表、以及它们之间的转换。
 *
 * 这一层全是**纯数据**：不碰引擎、不碰文件系统，所以能进 `commonTest`，
 * 在任何平台上都能跑。
 */

/**
 * 分析模式的数据源条目 —— 一个**可分析**的远端连接。
 *
 * ## 为什么这里只有展示需要的字段
 *
 * 分析模式要连远端、列元数据，最后还要把表**拉**到本地工作区 ——
 * 这些都需要完整的连接配置（URL、账号、库名……）。但那串东西属于
 * **desktopApp 的连接层**（`com.langbiantianya.sundays.connection`），
 * 而 `:shared` 是跨平台模块，不该认识它。
 *
 * 所以这里只留「**界面显示这个数据源需要什么**」：
 * - [id] —— 稳定标识，用于选中态与回调
 * - [name] —— 显示名（连接名）
 * - [dialectLabel] —— 方言名（"MySQL" / "PostgreSQL" …），用于让用户一眼看出
 *   这个源是远端还是本地
 *
 * 真要连的时候，由 desktopApp 侧拿着 [id] 回到连接层取完整配置。
 * 换句话说：**这一层不携带凭据，也不决定连不连**。
 */
data class AnalysisSourceItem(
    val id: String,
    val name: String,
    val dialectLabel: String,
)

/**
 * 结果表的一列。
 *
 * @param name 列名
 * @param type 类型名（数据库自己说的，如 `VARCHAR` / `INTEGER` / `DECIMAL`）
 */
data class AnalysisColumn(
    val name: String,
    val type: String,
)

/**
 * 一次分析查询的结果。
 *
 * ## 为什么行是 `List<List<String?>>` 而不是 `List<Map<String, String?>>`
 *
 * proto 的 `Row` 是 `map<string, Value>` —— **无序**的。
 * 界面上的结果表要按列顺序排，所以行必须按 [columns] 的顺序取值。
 * 用 map 存行就得在渲染时再排一次序，而排序的键（列名）在两次查询之间可能不同；
 * 用有序的 list 则「第 i 格就是第 i 列」这件事由构造时就定死了。
 *
 * @param columns 列定义（顺序即显示顺序）
 * @param rows 行数据，每行与 [columns] 一一对应
 * @param affectedRows 无结果集的语句（建表 / 插入）影响的行数。
 *   **DDL 回 -1** —— JDBC 约定「这不是更新计数」；`INSERT` 才是真实的行数。
 *   界面据此区分「0 行」与「不适用」，别把 -1 显示成「-1 行」。
 * @param truncated 结果被行数上限截断
 * @param elapsedMs 耗时（毫秒）
 * @param error 出错时的错误文案；`null` = 成功
 */
data class AnalysisResult(
    val columns: List<AnalysisColumn> = emptyList(),
    val rows: List<List<String?>> = emptyList(),
    val affectedRows: Int = 0,
    val truncated: Boolean = false,
    val elapsedMs: Long = 0,
    val error: String? = null,
) {
    /** 有错误吗 —— 界面据此决定是显示错误条还是显示结果表。 */
    val failed: Boolean get() = error != null
}

// ──────────────── 数据源树 ────────────────

/**
 * 分析树里一行的**种类**。
 *
 * 树的形状是固定的三层：**根 → 命名空间 → 表**。
 * 「本地工作区」也是根 —— 它是分析模式自带的计算引擎（DuckDB），
 * 和远端源在树上**同级**，只是性质相反（一个可写、一个恒只读）。
 */
enum class AnalysisTreeKind {
    /** 本地工作区（DuckDB 文件）。 */
    WORKSPACE,

    /** 远端数据源。 */
    SOURCE,

    /** 命名空间 —— MySQL 上是**库**，PG 上是 **schema**。 */
    NAMESPACE,

    /** 表。 */
    TABLE,
}

/**
 * 分析树的一行。
 *
 * @param id 稳定标识 —— 展开态按它索引
 * @param label 显示名。**工作区那一行是例外**：它的名字是固定的
 *   （「本地工作区」是应用自带的引擎，不是用户起名的），
 *   由界面按 `StrKey` 取，不靠这里传。
 * @param kind 种类
 * @param dialectLabel 方言标签（只有工作区与远端源有）
 * @param children 已加载的子节点
 * @param loaded 子节点是否**已加载** —— 未加载时画「⋯」而不是空白，
 *   否则用户分不清「这里没有子节点」与「还没去查」
 * @param sourceId 所属远端源 id；`null` = 本地工作区
 * @param namespace 所属命名空间；`null` = 还没到命名空间这一层
 * @param table 表名；`null` = 不是表
 */
data class AnalysisTreeNode(
    val id: String,
    val label: String,
    val kind: AnalysisTreeKind,
    val dialectLabel: String? = null,
    val children: List<AnalysisTreeNode> = emptyList(),
    val loaded: Boolean = false,
    val sourceId: String? = null,
    val namespace: String? = null,
    val table: String? = null,
)

/** 树里一行的测试标记 —— `analysisTreeRow:<节点 id>`。 */
fun analysisTreeRowTag(nodeId: String): String = "analysisTreeRow:$nodeId"

/** 树里一行的**扭子**（展开 / 收起箭头）的测试标记 —— `analysisTreeTwisty:<节点 id>`。 */
fun analysisTreeTwistyTag(nodeId: String): String = "analysisTreeTwisty:$nodeId"

/** 工作区根节点的 id —— 固定值，不随连接变化。 */
const val ANALYSIS_WORKSPACE_NODE_ID: String = "workspace"

/**
 * 工作区的默认 schema —— DuckDB 的 `main`。
 *
 * ## 为什么工作区这一层**不摆**命名空间
 *
 * 远端源的命名空间是**用户必须分辨**的东西（MySQL 的库、PG 的 schema），
 * 所以那一层一定要摆出来。而工作区是应用自带的 DuckDB 文件，
 * 它的 schema 实际上只有 `main` 一个 —— 把它摆在每张表前面，
 * 只会给每张表多加一个没有信息量的中间层。
 *
 * 所以工作区的子节点**直接就是表**（对应设计稿里的 `本地工作区 → orders`）。
 *
 * ⚠️ 代价：用户在 DuckDB 里**自建**的其它 schema 不会出现在树里。
 * 那是本轮的取舍，不是遗漏 —— 拉取进来的 staging 表全在 `main`，
 * 而 `main` 正是「工作区里能查到的表」的集合。
 */
const val WORKSPACE_SCHEMA: String = "main"

/** 命名空间节点的 id —— `ns:<属主>:<命名空间>`。 */
fun analysisNamespaceNodeId(
    owner: String,
    namespace: String,
): String = "ns:$owner:$namespace"

/** 表节点的 id —— `table:<属主>:<命名空间>:<表名>`。 */
fun analysisTableNodeId(
    owner: String,
    namespace: String,
    table: String,
): String = "table:$owner:$namespace:$table"

/**
 * 这个节点**能不能展开**。
 *
 * 只有表是叶子 —— 工作区 / 远端源 / 命名空间都是分组。
 * 判定写成一个函数而不是散在界面里，是为了**界面与测试用同一条规则**：
 * 否则界面按 kind 画扭子、测试按另一套判断，两边迟早对不上。
 */
fun AnalysisTreeNode.expandable(): Boolean = kind != AnalysisTreeKind.TABLE

/**
 * 在树里按 id 找到某个节点。
 *
 * 找不到返回 `null`（而不是抛异常）—— 树的形状会随展开而变，
 * 「这一刻找不到」是**正常状态**（比如节点刚被换掉），不是错误。
 */
fun findAnalysisNode(
    roots: List<AnalysisTreeNode>,
    id: String,
): AnalysisTreeNode? {
    for (node in roots) {
        if (node.id == id) return node
        val hit = findAnalysisNode(node.children, id)
        if (hit != null) return hit
    }
    return null
}

/**
 * 把树里 id 为 [id] 的节点换成 [transform] 之后的那个。
 *
 * ## 为什么要「按 id 替换」而不是「整棵树重建」
 *
 * 展开一个节点时，我们只想换掉**那一个**节点（把它的子节点填上），
 * 其余部分必须原样保留 —— 用户的展开态、滚动位置都挂在这棵树上。
 * 整棵树重建会把「谁展开了」这件事从树上抹掉（那部分状态在界面侧的
 * `expandedIds` 里，但节点本身的 `loaded` / `children` 会一起丢）。
 *
 * ## 为什么找不到就**原样返回**
 *
 * 找不到说明这棵树已经被换掉了（比如数据源被删了）。
 * 那时**什么都不做**才是对的 —— 凭空造一个节点会让树里出现幽灵行。
 */
fun replaceAnalysisNode(
    roots: List<AnalysisTreeNode>,
    id: String,
    transform: (AnalysisTreeNode) -> AnalysisTreeNode,
): List<AnalysisTreeNode> =
    roots.map { node ->
        if (node.id == id) {
            transform(node)
        } else if (node.children.isEmpty()) {
            node
        } else {
            node.copy(children = replaceAnalysisNode(node.children, id, transform))
        }
    }

// ──────────────── 结果 → 表格 ────────────────

/**
 * 列的**绑定键** —— 用下标而不是列名。
 *
 * ## 为什么不用列名
 *
 * 列名**可以重复**（`SELECT id, id FROM t` 是合法的）。而
 * [TableRow.cells] 是 `Map<String, Any?>` —— 键重复时后一列会把前一列**覆盖掉**，
 * 表现为「明明查了两列，表里只显示一列」。
 * 用下标做键就没有这个问题，而**显示**用的仍然是列名（表头）。
 */
fun analysisColumnKey(index: Int): String = "c$index"

/**
 * 分析结果 → 表格的列。
 *
 * 表头用列**名**（用户看得懂），绑定键用**下标**（见 [analysisColumnKey]）。
 */
fun analysisTableColumns(result: AnalysisResult): List<TableColumn> =
    result.columns.mapIndexed { index, column ->
        TableColumn(key = analysisColumnKey(index), header = column.name)
    }

/**
 * 分析结果 → 表格的行。
 *
 * [AnalysisResult.rows] 是**按列顺序**的有序列表（构造时定死了「第 i 格就是第 i 列」），
 * 这里把它转成 `Map` —— 键仍是列下标，与 [analysisTableColumns] 对得上。
 */
fun analysisTableRows(result: AnalysisResult): List<TableRow> =
    result.rows.mapIndexed { rowIndex, cells ->
        TableRow(
            id = rowIndex,
            cells = cells.mapIndexed { columnIndex, value -> analysisColumnKey(columnIndex) to value }.toMap(),
        )
    }
