/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.ColumnBadge
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.onRightClick
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/*
 * 对象树 —— **两棵树共用的那一个组件**。
 *
 * ## 为什么是「一个组件」而不是「两套画法」
 *
 * 应用里有两棵树：
 *
 * | 树 | 住在 | 形状 |
 * |---|---|---|
 * | 浏览模式的对象树 | `desktopApp` 的 `SchemaTree.kt` | 数据库 → 表 → 字段 |
 * | 分析模式的树 | `:shared` 的 `SourceTree.kt` | 工作区 → 表（原来到表为止） |
 *
 * 它们曾经各画各的：浏览模式那套用 `Icons.Filled.ExpandMore` 箭头 + 图标 +
 * 字段行带 `PK/AI/非空` 标记；分析模式那套用 `▾ ▸ ⋯` 三个字符当扭子。
 * 两边的「表能不能展开」「点表会发生什么」也不一样。
 *
 * 同一个概念有两份实现，改一处就忘了另一处 —— 于是本组件把**浏览模式那套**
 * 定为唯一真相源（它信息更全、也更成熟），分析模式改用它。
 *
 * ## 这一层只做三件事
 *
 * - 把 [ObjectTreeNode] 画成缩进的行；
 * - 把「点扭子」翻译成 [onToggle]（展开 / 收起）；
 * - 把「点表」「点字段」翻译成 [onSelectTable] / [onSelectColumn]。
 *
 * 它**不**知道数据从哪来、也不自己查 —— 树的内容由调用方给，
 * 展开动作也由调用方去执行。这是 `:shared` `commonMain` 的硬约束：
 * 这一层不碰引擎、不碰文件系统。
 *
 * ## 滚动容器**不归它管**
 *
 * 浏览模式把树放进 `LazyColumn` 的每一项里（按数据库分项），
 * 分析模式自己套一个可滚动的 `Column`。两种用法要的容器不一样，
 * 所以这里只画内容，滚动由调用方决定。
 */

/**
 * 容器（数据库 / 工作区）那一行的左边距。
 *
 * 12dp 是浏览模式留下来的值 —— 它与面板的 12dp 内边距叠在一起，
 * 看起来是「离面板左边 24dp」。改动它会让整棵树在面板里横向挪位。
 */
private val DATABASE_ROW_START = 12.dp

/** 表那一行的左边距 —— 比容器多缩进 8dp。 */
private val TABLE_ROW_START = 20.dp

/** 字段组（标题 + 字段行）的容器的左边距。 */
private val FIELD_GROUP_START = 44.dp

/** 字段行自己再缩进 12dp（容器之内）。 */
private val COLUMN_ROW_START = 12.dp

/** 容器级对象组（视图 / 触发器）的容器的左边距。 */
private val OBJECT_GROUP_START = 24.dp

/**
 * 对象树。
 *
 * @param roots 树的根节点
 * @param expandedIds 当前展开着的节点 id
 * @param onToggle 某个节点被展开 / 收起时回调
 * @param onSelectTable 某个**表**被点时回调（浏览模式 = 打开预览；分析模式 = 把表名加进编辑器）
 * @param onSelectColumn 某个**字段**被点时回调；`null` = 字段行不可点
 * @param onRightClickNode 某个节点被右键时回调，参数是**节点**与**相对 root 的坐标**；
 *   `null` = 整棵树没有右键菜单
 * @param rowTag 一行的测试标记；默认按节点 id 生成
 * @param twistyTag 扭子的测试标记；默认按节点 id 生成
 * @param showLoadingSpinner 加载中时是否画那个**转圈**。
 *   默认关 —— 转圈是**无限动画**，会让「等界面空闲」的 UI 测试永远等不到；
 *   浏览模式开着（它有真窗口的用户盯着），分析模式关着（它的加载是一瞬的事）。
 * @param modifier 修饰符
 */
@Composable
fun ObjectTree(
    roots: List<ObjectTreeNode>,
    expandedIds: Set<String>,
    onToggle: (ObjectTreeNode) -> Unit,
    onSelectTable: (ObjectTreeNode) -> Unit = {},
    onSelectColumn: ((ObjectTreeNode) -> Unit)? = null,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)? = null,
    rowTag: (ObjectTreeNode) -> String = { objectTreeRowTag(it.id) },
    twistyTag: (ObjectTreeNode) -> String = { objectTreeTwistyTag(it.id) },
    showLoadingSpinner: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        roots.forEach { root ->
            ObjectTreeNodeView(
                node = root,
                expandedIds = expandedIds,
                onToggle = onToggle,
                onSelectTable = onSelectTable,
                onSelectColumn = onSelectColumn,
                onRightClickNode = onRightClickNode,
                rowTag = rowTag,
                twistyTag = twistyTag,
                showLoadingSpinner = showLoadingSpinner,
            )
        }
    }
}

/**
 * 树里的**一行**（连同它的子树）。
 *
 * 递归渲染：展开的节点把自己的子节点再画一遍。
 */
@Composable
private fun ObjectTreeNodeView(
    node: ObjectTreeNode,
    expandedIds: Set<String>,
    onToggle: (ObjectTreeNode) -> Unit,
    onSelectTable: (ObjectTreeNode) -> Unit,
    onSelectColumn: ((ObjectTreeNode) -> Unit)?,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
    twistyTag: (ObjectTreeNode) -> String,
    showLoadingSpinner: Boolean,
) {
    val expanded = node.id in expandedIds
    Column {
        when (node.kind) {
            ObjectTreeKind.DATABASE -> DatabaseRow(node, expanded, onToggle, onRightClickNode, rowTag, showLoadingSpinner)
            ObjectTreeKind.TABLE -> TableRow(node, expanded, onToggle, onSelectTable, onRightClickNode, rowTag, twistyTag)
            ObjectTreeKind.COLUMN -> ColumnRow(node, onSelectColumn, onRightClickNode, rowTag)
        }
        if (expanded) {
            when (node.kind) {
                ObjectTreeKind.DATABASE -> DatabaseExpanded(node, expandedIds, onToggle, onSelectTable, onSelectColumn, onRightClickNode, rowTag, twistyTag, showLoadingSpinner)
                ObjectTreeKind.TABLE -> TableExpanded(node, onSelectColumn, onRightClickNode, rowTag)
                ObjectTreeKind.COLUMN -> Unit
            }
        }
    }
}

/**
 * 容器那一行 —— 展开箭头 + 数据库图标 + 名字。
 *
 * ## 为什么容器行的点击是「展开 / 收起」
 *
 * 容器里装的是一**批**表，点它没有「选中某一个」的意思，
 * 所以它的语义只有「展开 / 收起」这一种。
 */
@Composable
private fun DatabaseRow(
    node: ObjectTreeNode,
    expanded: Boolean,
    onToggle: (ObjectTreeNode) -> Unit,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
    showLoadingSpinner: Boolean,
) {
    val coords = rememberNodeCoordinates()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackNodeCoordinates(coords)
                .focusRing()
                .then(rightClickModifier(node, coords, onRightClickNode))
                .clickable { onToggle(node) }
                .testTag(rowTag(node))
                .padding(start = DATABASE_ROW_START, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = node.labelKey?.let { str(it) } ?: node.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        node.badge?.let { badge ->
            Box(
                modifier =
                    Modifier
                        .padding(start = 6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, winShape(3.dp))
                        .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (node.loading && showLoadingSpinner) {
            Spacer(Modifier.width(8.dp))
            WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * 容器展开后的内容 —— 表 + 库级对象组（视图 / 触发器 / 过程·函数）。
 *
 * ## 三种状态是**互斥**的，且顺序不能换
 *
 * 错误 → 加载中 → 空 → 内容。错误排第一是因为「加载失败」与
 * 「这里没有表」在界面上长得几乎一样（都是一行字），
 * 而两者的含义完全相反 —— 把失败显示成「空」等于骗用户说表丢了。
 */
@Composable
private fun DatabaseExpanded(
    node: ObjectTreeNode,
    expandedIds: Set<String>,
    onToggle: (ObjectTreeNode) -> Unit,
    onSelectTable: (ObjectTreeNode) -> Unit,
    onSelectColumn: ((ObjectTreeNode) -> Unit)?,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
    twistyTag: (ObjectTreeNode) -> String,
    showLoadingSpinner: Boolean,
) {
    val error = node.error
    val loading = node.loading
    val children = node.children
    when {
        error != null -> {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
            )
        }

        loading -> {
            Text(
                text = str(StrKey.TREE_LOADING),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
            )
        }

        // ⚠️ 判据是「**表**一张都没有」，不是「整棵树什么都没有」——
        // 库级对象（视图 / 触发器…）挂在库下面，表为空时它们也跟着不显示。
        // 这是浏览模式一直以来的样子：先有表，再谈别的。
        children.isEmpty() -> {
            Text(
                text = str(StrKey.TREE_EMPTY),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
            )
        }

        else -> {
            children.forEach { child ->
                ObjectTreeNodeView(
                    node = child,
                    expandedIds = expandedIds,
                    onToggle = onToggle,
                    onSelectTable = onSelectTable,
                    onSelectColumn = onSelectColumn,
                    onRightClickNode = onRightClickNode,
                    rowTag = rowTag,
                    twistyTag = twistyTag,
                    showLoadingSpinner = showLoadingSpinner,
                )
            }
            ObjectGroupsArea(node)
        }
    }
}

/**
 * 库级对象区 —— 一个小标题 + 若干组「标签：名字1, 名字2」。
 *
 * ## 为什么对象排在表**后面**
 *
 * 用户 90% 的时间在找表。把表放前面、对象放后面，
 * 免得每展开一个库都先滚过一屏用不上的东西。
 */
@Composable
private fun ObjectGroupsArea(node: ObjectTreeNode) {
    if (node.objectGroupsLoading) {
        Text(
            text = str(StrKey.TREE_OBJECTS_LOADING),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = OBJECT_GROUP_START, bottom = 6.dp),
        )
        return
    }
    if (node.objectGroups.isEmpty()) return
    Column(modifier = Modifier.padding(start = OBJECT_GROUP_START)) {
        node.objectGroupsTitle?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        node.objectGroups.forEach { group -> ObjectGroupRow(group) }
    }
}

/**
 * 对象分组下的一行：`标签: 名字1, 名字2 …`。空列表显示「(无)」。
 *
 * ## 错误必须**逐组**显示
 *
 * 不能「任意一组出错 → 整个对象区换成一行错误」：
 * SQLite 没有触发器与存储过程（`SQLiteDialect` 对它们抛
 * `UnsupportedOperationException`），于是原实现下 **视图也跟着消失了** ——
 * 用户看到的只是一行红字「对象加载失败」，而那恰恰是唯一有内容的那一类。
 */
@Composable
private fun ObjectGroupRow(group: ObjectTreeObjectGroup) {
    val error = group.error
    if (error != null) {
        Text(
            text = str(StrKey.OBJECT_LOAD_ERROR_PREFIX, group.label, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
        )
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "${group.label}：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (group.items.isEmpty()) str(StrKey.NONE_PAREN) else group.items.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 表那一行 —— 展开箭头 + 表图标 + 表名。
 *
 * ## 为什么「点行」与「点扭子」是**两个**动作
 *
 * 点整行 = 「选中这张表」（浏览模式打开预览；分析模式把表名加进编辑器）；
 * 点扭子 = 「展开它的字段」。同一个手势不能既选中又展开。
 */
@Composable
private fun TableRow(
    node: ObjectTreeNode,
    expanded: Boolean,
    onToggle: (ObjectTreeNode) -> Unit,
    onSelectTable: (ObjectTreeNode) -> Unit,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
    twistyTag: (ObjectTreeNode) -> String,
) {
    val coords = rememberNodeCoordinates()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackNodeCoordinates(coords)
                .focusRing()
                .then(rightClickModifier(node, coords, onRightClickNode))
                .clickable { onSelectTable(node) }
                .testTag(rowTag(node))
                .padding(start = TABLE_ROW_START, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 展开箭头：与容器行的 chevron 同一套视觉语言。
        // 单独一个小按钮而不是让整行双击 —— 整行单击已经占用在「选中表」上。
        //
        // ⚠️ 描述要说「字段与索引外键」而不是只有索引外键：读屏用户听到
        // 「展开 orders 的索引与外键」，展开后冒出几十个字段却毫无预期。
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription =
                if (expanded) {
                    str(StrKey.COLLAPSE_TABLE_DETAIL, node.label)
                } else {
                    str(StrKey.EXPAND_TABLE_DETAIL, node.label)
                },
            modifier =
                Modifier
                    .size(16.dp)
                    .testTag(twistyTag(node))
                    .clickable { onToggle(node) },
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Filled.TableChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = node.label,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 表展开后的内容 —— 字段组（标题 + 字段行）+ 表级对象组（索引 / 外键）。
 */
@Composable
private fun TableExpanded(
    node: ObjectTreeNode,
    onSelectColumn: ((ObjectTreeNode) -> Unit)?,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
) {
    Column(modifier = Modifier.padding(start = FIELD_GROUP_START)) {
        FieldGroup(node, onSelectColumn, onRightClickNode, rowTag)
        node.objectGroups.forEach { group -> ObjectGroupRow(group) }
    }
}

/**
 * 字段组 —— 「字段：」标题 + 字段行（带 loading / error / empty 三态）。
 *
 * ## 为什么自己拿列表，而不是塞一个 `content` lambda
 *
 * 早先的写法传的是 `content: @Composable ColumnScope.() -> Unit`，空判断靠一个
 * `var any by remember { mutableStateOf(false) }` —— 而 lambda 内部**没有**任何地方
 * 把它置 true，于是走 else 分支就必然打印一行「(无)」。
 * 真窗口上表现为：字段明明列了两行，下面还跟着一句「(无)」。
 *
 * 拿列表自己渲染就没有这个可能 —— **「有没有内容」和「渲染内容」在同一个作用域里**。
 */
@Composable
private fun FieldGroup(
    node: ObjectTreeNode,
    onSelectColumn: ((ObjectTreeNode) -> Unit)?,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
) {
    val columns = node.children.filter { it.kind == ObjectTreeKind.COLUMN }
    Column(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${str(StrKey.COLUMN)}：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.loading) {
                Spacer(Modifier.width(6.dp))
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(11.dp))
            }
        }
        val error = node.error
        when {
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = COLUMN_ROW_START, top = 1.dp),
                )
            }

            // 加载中也显示「(无)」的话，用户会以为这张表没有字段，而那只是一瞬间的假象。
            // 所以「(无)」这一支只在**不加载**时出现。
            !node.loading && columns.isEmpty() -> {
                Text(
                    text = str(StrKey.NONE_PAREN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = COLUMN_ROW_START, top = 1.dp),
                )
            }

            else -> {
                Column(modifier = Modifier.padding(start = COLUMN_ROW_START)) {
                    columns.forEach { column ->
                        ColumnRow(column, onSelectColumn, onRightClickNode, rowTag)
                    }
                }
            }
        }
    }
}

/**
 * 一个字段行：`列名  类型  [PK] [自增] [非空]`。
 *
 * ## 为什么标记要跟在类型后面而不是塞进类型文本
 *
 * `VARCHAR(64) PK` 读起来像是类型的一部分。而 `PK` / `AI` / `NOT NULL` 是**约束**，
 * 混进类型串里会让人以为「这是一种新类型」。分开写，扫一眼就能区分「这是什么」与
 * 「有什么限制」。
 *
 * ## 为什么字段行也必须可点击
 *
 * 库节点与表节点都挂了 `focusRing` + `clickable`，只有字段行两个都没有 ——
 * 于是鼠标划过字段区时**整片没有任何反应**：没有 hover 底色、没有按下反馈，
 * 用户既看不出自己悬停在哪一行，点下去也没有任何确认。
 * 字段行恰恰是树里**最长、最密**的一批行（一张表几十个字段），
 * 没有逐行反馈就完全靠读文字定位，扫视成本最高的地方反而反馈最少。
 */
@Composable
private fun ColumnRow(
    node: ObjectTreeNode,
    onSelectColumn: ((ObjectTreeNode) -> Unit)?,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
    rowTag: (ObjectTreeNode) -> String,
) {
    val column = node.column ?: return
    val coords = rememberNodeCoordinates()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackNodeCoordinates(coords)
                .focusRing()
                .then(rightClickModifier(node, coords, onRightClickNode))
                .then(
                    if (onSelectColumn != null) {
                        Modifier.clickable(
                            // 语义动作名：读屏用户听到的是「复制字段 USERNAME」而不是「按钮」
                            onClickLabel = str(StrKey.COPY_COLUMN, column.name),
                            role = Role.Button,
                        ) { onSelectColumn(node) }
                    } else {
                        Modifier
                    },
                )
                // ⚠️ tag 挂在**整行**而不是列名文本上：字段行加上 `clickable` 之后
                // **语义会被合并**（clickable 默认 `mergeDescendants = true`），
                // 挂在子 `Text` 上的 tag 会被吸收进 Row —— 合并后的语义树里再也查不到它。
                .testTag(rowTag(node))
                .padding(top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = column.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (column.primaryKey) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = column.type,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 约束标记。空列不占位，否则没有约束的普通字段会整体左移、参差不齐
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (column.primaryKey) ColumnBadge("PK")
            if (column.autoIncrement) ColumnBadge("AI")
            // 只在**不可空**时标记：「可空」是绝大多数列的默认，写出来全是噪音
            if (!column.nullable) ColumnBadge(str(StrKey.NOT_NULL))
        }
    }
}

/**
 * 记下本节点相对 root 的原点 —— 右键菜单要按 **root** 坐标定位。
 *
 * 为什么必须是 `positionInRoot` 而不是 `positionInParent`：节点可能在一个
 * `LazyColumn` 里（浏览模式就是），`positionInParent` 只给出「相对那一行 item」
 * 的偏移，中间隔着滚动偏移、面板标题栏、缩进层级，逐级上溯既繁琐又容易漏一级。
 */
@Composable
private fun rememberNodeCoordinates(): MutableState<androidx.compose.ui.layout.LayoutCoordinates?> = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

private fun Modifier.trackNodeCoordinates(
    coords: MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
): Modifier = this.onGloballyPositioned { coords.value = it }

/**
 * 右键处理 —— 把「节点局部坐标」换算成 **root 坐标**再交给调用方。
 *
 * 节点给的是**节点局部坐标**（`onRightClick` 回调里的位置就是相对挂
 * `pointerInput` 的那个节点），而菜单是屏级浮层，它要的是 root 坐标。
 * 两者必须靠节点的原点换算，否则菜单会偏掉整屏。
 *
 * ## 为什么是**普通函数**而不是 `Modifier` 的扩展
 *
 * 扩展函数在 `Modifier.a().b().then(rightClickModifier(…))` 这种链里当参数用时，
 * 接收者解析不到 —— 链首的 `Modifier` 是**伴生对象**（工厂），不是某个实例。
 * 与其跟这条规则较劲，不如把它写成普通函数：入参就是它要返回的那个 `Modifier`。
 */
private fun rightClickModifier(
    node: ObjectTreeNode,
    coords: MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
    onRightClickNode: ((ObjectTreeNode, Offset) -> Unit)?,
): Modifier =
    if (onRightClickNode == null) {
        Modifier
    } else {
        Modifier.onRightClick { offset ->
            // 当场读 `positionInRoot()` 而不是读缓存的 Offset：
            // 展开 / 滚动之后缓存值可能已经过期，而 `LayoutCoordinates`
            // 是个活对象，此刻读到的就是**此刻**的位置。
            val origin = coords.value?.positionInRoot() ?: Offset.Zero
            onRightClickNode(node, offset + origin)
        }
    }
