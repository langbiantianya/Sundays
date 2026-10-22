/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

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
 * 数据源行的测试标记 —— `analysisSourceRow:<id>`。
 *
 * ## 为什么不给它一个更花哨的标记
 *
 * 这个标记存在的原因是**断言要精确**：数据源的名字同时出现在工具条的下拉框里
 * 和左树的行里，只按文本找会一次命中两个节点，断言就只能说「至少有一个」。
 * 而「左树里那行显示的是哪个源」正是要守的东西 —— 按标记找就没有这个歧义。
 */
fun analysisSourceRowTag(id: String): String = "analysisSourceRow:$id"

/**
 * 只读徽标的测试标记 —— `analysisReadOnlyBadge`。
 *
 * ## 为什么需要它（一次假绿换来的）
 *
 * 「徽标不可关」这条判据，查的是**徽标那个容器**上有没有可点语义。
 * 按文本找节点会找到徽标**里面那个 Text**（两个短字「⊘ 只读」）——
 * 而可点语义挂在**容器**上，Text 自己没有，于是断言恒绿。
 *
 * 这就是「断言落错层」的典型：测试绿着，功能却是错的。
 * 标记把断言钉到**正确的那个节点**上。
 */
const val ANALYSIS_READ_ONLY_BADGE_TAG: String = "analysisReadOnlyBadge"

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
