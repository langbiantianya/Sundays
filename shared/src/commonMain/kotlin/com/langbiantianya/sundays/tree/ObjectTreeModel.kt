/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

import com.langbiantianya.i18n.StrKey

/*
 * 对象树的**模型** —— 纯数据，不碰界面。
 *
 * ## 为什么有这一层
 *
 * 应用里有**两棵树**：
 *
 * - **数据库管理**（浏览模式）的对象树：库 → 表 → 字段；
 * - **数据分析**（分析模式）的树：工作区 → 表 → 字段。
 *
 * 它们曾经是两套完全不同的代码：浏览模式那套在 `desktopApp`（深度耦合
 * `DatabaseBrowserState` 与右键菜单），分析模式那套在 `:shared`
 * （`SourceTree`，自己画的扭子与行）。两套画法不一样、展开语义不一样、
 * 连「表能不能展开」都不一样 —— 同一个概念有两份实现，改一处就忘了另一处。
 *
 * 所以把**模型**抽出来共用：两边都产出同一种节点，交给**同一个组件**渲染。
 * 本文件是模型的唯一真相源，渲染在 [ObjectTree.kt]。
 *
 * ## 树的形状是固定的：库 → 表 → 字段
 *
 * 三层，不多不少：
 *
 * ```
 * 本地工作区 (DuckDB)      ← 「库」这一层
 *   orders                 ← 表
 *     id    INTEGER        ← 字段
 * ```
 *
 * 「库」在分析模式里是**工作区**（本地 DuckDB 文件），在浏览模式里是
 * **数据库**。两者对树来说是同一种东西 —— 都是「一批表的容器」，
 * 所以共用一个 kind（[ObjectTreeKind.DATABASE]）。
 */

/**
 * 树里一行的种类。
 *
 * ## 为什么只有三种
 *
 * 因为两棵树都要**三层**：容器 → 表 → 字段。
 * 浏览模式里容器是「数据库」，分析模式里容器是「工作区」——
 * 它们语义不同、来源不同，但对树来说是**同一种位置**。
 * 硬要分成两种 kind，就得在每个 `when` 里多写一个分支，
 * 而那两个分支画出来的东西一模一样。
 */
enum class ObjectTreeKind {
    /** 容器 —— 浏览模式是**数据库**，分析模式是**工作区**。 */
    DATABASE,

    /** 表。 */
    TABLE,

    /** 字段（列）。 */
    COLUMN,
}

/**
 * 一个字段的展示信息。
 *
 * ## 为什么这里**不直接**用 proto 的 `ColumnDef`
 *
 * 那是引擎的传输结构。界面只需要「名字 / 类型 / 主键 / 可空 / 自增」，
 * 中间隔一层以后引擎加字段不会波及界面，反过来界面想改展示
 * （比如多显示一列注释）也不必动 proto 依赖。
 *
 * 这与浏览模式那边已有的 `DatabaseBrowserState.ColumnInfo` 是**同一个道理**，
 * 只是那边住在 `desktopApp`、这边住在 `:shared`（跨平台的那一半）。
 */
data class ObjectTreeColumn(
    val name: String,
    /** 类型原文 —— 形如 `VARCHAR(64)` / `INT`。 */
    val type: String,
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    val nullable: Boolean = true,
)

/**
 * 一组「只有名字」的对象 —— 视图 / 触发器 / 索引 / 外键。
 *
 * ## 为什么它们不是节点
 *
 * 它们**不可展开**（点开也没有子节点），而且同类的名字挤在一行里
 * （「索引：idx_a, idx_b」）比一行一个更紧凑 —— 索引动辄十几个。
 * 做成节点的话，树里会多出一堆点开是空的行。
 */
data class ObjectTreeObjectGroup(
    val label: String,
    val items: List<String> = emptyList(),
    /** 这一组加载失败的原因；`null` = 没失败。 */
    val error: String? = null,
)

/**
 * 树里的一个节点。
 *
 * @param id 稳定标识 —— 展开态按它索引
 * @param label 显示名
 * @param kind 种类
 * @param badge 右上角的标签（分析模式的工作区标「DuckDB」）
 * @param parentLabel **父节点的名字**（字段的父是表；表的父是库）。
 *   为什么需要它：字段行本身只有列名，而「这个字段属于哪张表」是
 *   点击它（把它加进编辑器）与右键它（复制它的引用）都必须知道的事。
 *   与其让每个调用方去解析节点 id（那样格式一变就全错），
 *   不如让模型直接把上下文带在身上。
 * @param labelKey 固定文案键 —— **有它就用它代替 [label]**。
 *   用于「名字由应用定、不是用户起名」的那一行（分析模式的「本地工作区」）。
 *   放在模型里而不是让界面各处特判，是为了让「这一行的名字是固定的」
 *   这件事只在一个地方写着。
 * @param children 已加载的子节点
 * @param loaded 子节点是否**已经查过**。
 *   `false` = 还没查过（用户还没展开它）—— 分析模式的会话靠它决定
 *   「要不要再去查一遍」；浏览模式那边不需要（它的数据本来就在状态里）。
 * @param objectGroupsTitle 对象组区的小标题（浏览模式的「对象」）；`null` = 没有这一区
 * @param objectGroups 这一组「只有名字」的对象
 * @param objectGroupsLoading 对象组区正在加载
 * @param loading 这一行正在加载（界面上画一个转圈）
 * @param error 这一行加载失败的原因；`null` = 没失败
 * @param column 字段的信息；只有 [ObjectTreeKind.COLUMN] 有
 */
data class ObjectTreeNode(
    val id: String,
    val label: String,
    val kind: ObjectTreeKind,
    val badge: String? = null,
    val parentLabel: String? = null,
    val labelKey: StrKey? = null,
    val children: List<ObjectTreeNode> = emptyList(),
    val loaded: Boolean = true,
    val objectGroupsTitle: String? = null,
    val objectGroups: List<ObjectTreeObjectGroup> = emptyList(),
    val objectGroupsLoading: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val column: ObjectTreeColumn? = null,
)

/**
 * 这个节点**能不能展开**。
 *
 * 只有字段是叶子 —— 容器与表都是分组（表展开是字段）。
 * 判定写成一个函数而不是散在界面里，是为了**界面与测试用同一条规则**：
 * 否则界面按 kind 画扭子、测试按另一套判断，两边迟早对不上。
 */
fun ObjectTreeNode.expandable(): Boolean = kind != ObjectTreeKind.COLUMN

/**
 * 在树里按 id 找到某个节点。
 *
 * 找不到返回 `null`（而不是抛异常）—— 树的形状会随展开而变，
 * 「这一刻找不到」是**正常状态**（比如节点刚被换掉），不是错误。
 */
fun findObjectTreeNode(
    roots: List<ObjectTreeNode>,
    id: String,
): ObjectTreeNode? {
    for (node in roots) {
        if (node.id == id) return node
        val hit = findObjectTreeNode(node.children, id)
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
 * 整棵树重建会把「谁展开了」这件事从树上抹掉。
 *
 * ## 为什么找不到就**原样返回**
 *
 * 找不到说明这棵树已经被换掉了（比如数据源被删了）。
 * 那时**什么都不做**才是对的 —— 凭空造一个节点会让树里出现幽灵行。
 */
fun replaceObjectTreeNode(
    roots: List<ObjectTreeNode>,
    id: String,
    transform: (ObjectTreeNode) -> ObjectTreeNode,
): List<ObjectTreeNode> =
    roots.map { node ->
        if (node.id == id) {
            transform(node)
        } else if (node.children.isEmpty()) {
            node
        } else {
            node.copy(children = replaceObjectTreeNode(node.children, id, transform))
        }
    }

/** 树里一行的测试标记 —— `objectTreeRow:<节点 id>`。 */
fun objectTreeRowTag(nodeId: String): String = "objectTreeRow:$nodeId"

/** 树里一行的**扭子**（展开 / 收起箭头）的测试标记 —— `objectTreeTwisty:<节点 id>`。 */
fun objectTreeTwistyTag(nodeId: String): String = "objectTreeTwisty:$nodeId"
