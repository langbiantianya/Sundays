/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// 左侧**对象树** —— 库/表/字段的展开渲染、右键菜单，以及由右键菜单进入的表结构弹窗宿主。
// 弹窗本体在 TableStructureDialog.kt（同包），这里只管把它挂起来。
// 菜单项的可见性判据只有一处：`TreeContextMenu` 里那个「表节点且不是字段节点」。

package com.langbiantianya.sundays

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.table.TABLE_STRUCTURE_DELETE_CONFIRM_BTN_TAG
import com.langbiantianya.sundays.table.TableStructureDialog
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeColumn
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.ObjectTreeObjectGroup
import com.langbiantianya.sundays.tree.TreeContextMenu
import com.langbiantianya.sundays.tree.TreeContextMenuItem
import com.langbiantianya.sundays.tree.TreeContextMenuState
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SidePaneToggleButton
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.str

/** 左侧库/表树的默认宽度 —— 双击分隔条即复位到这个值。 */
internal val SCHEMA_PANEL_DEFAULT_WIDTH: Dp = 320.dp

/** 左侧库/表树宽度的下限 —— 再窄就看不到表名了。 */
internal val SCHEMA_PANEL_MIN_WIDTH: Dp = 180.dp

/** 库/表树面板的 UI 测试 tag —— 面板本身无语义，量宽度得靠它。 */
internal const val SCHEMA_PANEL_TAG = "schemaTreePanel"

/** 分隔条的 UI 测试 tag —— 拖拽测试需要一个明确的落点，不能靠坐标猜。 */
internal const val SCHEMA_DRAG_HANDLE_TAG = "schemaDragHandle"

/** 库节点 / 表叶子 / 表的「索引·外键」展开箭头。 */
internal fun schemaDbNodeTag(database: String) = "schemaDb_$database"

internal fun schemaTableLeafTag(table: String) = "schemaTable_$table"

private fun schemaTableObjectsTag(table: String) = "schemaTableObjects_$table"

/**
 * 字段行的 tag。
 *
 * 给**字段整行**加，而不是只给列名文本那一行 —— 字段行挂了 `clickable` 之后语义会合并，
 * 挂在子 `Text` 上的 tag 会在合并后的语义树里消失（见 [ColumnRow] 里的说明）。
 * 不带 tag 的话测试只能靠文本匹配，于是「复制引用」拿到的是哪一行就成了运气。
 */
internal fun schemaColumnTag(column: String) = "schemaColumn_$column"

/** 树上右键菜单三项的 UI 测试 tag（菜单项不获取焦点，只能靠 tag 定位）。 */
internal const val TREE_MENU_COPY_NAME_TAG = "treeMenuCopyName"

internal const val TREE_MENU_COPY_REF_TAG = "treeMenuCopyRef"

internal const val TREE_MENU_COPY_DDL_TAG = "treeMenuCopyDdl"

private const val TREE_MENU_EDIT_STRUCTURE_TAG = "treeMenuEditStructure"

private const val TREE_MENU_DELETE_TABLE_TAG = "treeMenuDeleteTable"

private const val TREE_MENU_NEW_TABLE_TAG = "treeMenuNewTable"

// 对象树右键菜单的**状态**与**弹层**住在 `:shared`
// （`com.langbiantianya.sundays.tree.TreeContextMenuState` / `TreeContextMenu`）。
//
// 为什么挪走：分析模式那棵树也要右键菜单，而两棵树共用同一个树组件
// （`ObjectTree`）。菜单的「状态 + 定位 + 关闭」是**同一件事** ——
// 留在 `desktopApp` 就等于分析模式要么复制一份、要么根本没有菜单。
// 现在机制在 `:shared`，两边各写各的菜单项。
//
// 浏览模式的目标类型是 [DatabaseBrowserState.TreeTarget]（库 / 表 / 字段）。

// ============================================================================
// 左侧 schema/表 树
// ============================================================================

@Composable
internal fun SchemaTreePanel(
    state: DatabaseBrowserState,
    connected: Boolean,
    onOpenTable: (schema: String, table: String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 滚动状态**由外面持有**（[DatabaseBrowserState.treeScrollState]）——
    // 面板折叠时整棵树会从组合里移除，记在面板里的状态会跟着没，
    // 展开后树就回到顶部了。
    //
    // 放在会话里还有第二层好处：切右栏 pane（表预览 ⇄ SQL ⇄ 造数）时
    // 组合会重排，本屏「每 sheet 状态互相独立、跨视图不丢失」的契约要求
    // 滚动位置也不丢。
    val listState = state.treeScrollState

    // 右键菜单状态提到 LazyColumn **外面**：菜单是**屏级**的一个浮层，
    // 若把 `visible` 记在某个 item 的组合里，滚动时被回收的 item 会把菜单一起带走
    // （表现是「菜单自己关掉了」），且新 item 组合时又得重建一份状态。
    //
    // 菜单的**机制**在 `:shared`（`TreeContextMenuState` / `TreeContextMenu`）——
    // 分析模式那棵树用的是同一份，所以两棵树的手感必然一致。
    // 这里给的目标类型是浏览模式的三种节点（库 / 表 / 字段）。
    val treeMenu = remember { TreeContextMenuState<DatabaseBrowserState.TreeTarget>() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        // testTag：面板本身不带语义，测试量不到它的边界（里面的文字节点宽度会随表名变化）。
        // 宽度契约要断言的恰恰是**面板**，所以必须给它一个锚点。
        modifier = modifier.testTag(SCHEMA_PANEL_TAG),
    ) {
        // Popup 的 parent 就是这个 Column，而 `Popup.offset` 相对 parent ——
        // 所以菜单要把 root 坐标换算成面板内坐标，就靠这里上报的 origin。
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { treeMenu.updateHostOrigin(it.positionInRoot()) },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = str(StrKey.BROWSER_TITLE),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // 「收起左树」—— 与分析模式的树面板头**同一个按钮**
                // （`SidePaneToggleButton`）：两侧的手感必须一致，
                // 不能出现「这边能收、那边不能收」。
                Spacer(Modifier.weight(1f))
                SidePaneToggleButton(collapsed = false, onClick = onCollapse)
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                !connected -> {
                    EmptyHint(
                        title = str(StrKey.NOT_CONNECTED),
                        description = str(StrKey.NOT_CONNECTED_HINT),
                    )
                }

                state.loadingDatabases -> {
                    EmptyHint(title = str(StrKey.LOADING_DATABASES), description = null)
                }

                state.errorMessage != null -> {
                    EmptyHint(
                        title = str(StrKey.LOAD_FAILED),
                        description = state.errorMessage,
                    )
                }

                state.databases.isEmpty() -> {
                    EmptyHint(
                        title = str(StrKey.NO_DATABASES),
                        description = str(StrKey.NO_DATABASES_HINT),
                    )
                }

                else -> {
                    // 树的数据快照 —— 节点、展开态、以及每个节点的右键目标。
                    //
                    // ⚠️ **每次重组都重算**：它读的是 `DatabaseBrowserState` 里的
                    // 一堆 map（表、字段、对象、展开态），而那些 map 都是
                    // `mutableStateMapOf` —— 读它们就等于订阅它们，
                    // 任何一个变了都会让这里重算。缓存反而要额外的失效逻辑。
                    val snapshot = state.objectTreeSnapshot()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(snapshot.roots) { root ->
                            // 每个数据库一**项** —— 与从前一致：
                            // 库节点下面那一整支（表 / 字段 / 对象）在同一项里组合。
                            ObjectTree(
                                roots = listOf(root),
                                expandedIds = snapshot.expandedIds,
                                onToggle = { node -> state.toggleTreeNode(node) },
                                onSelectTable = { node -> node.parentLabel?.let { onOpenTable(it, node.label) } },
                                onSelectColumn = { node ->
                                    snapshot.targets[node.id]?.let { state.copyTreeText(it, quoted = true) }
                                },
                                onRightClickNode = { node, offset ->
                                    snapshot.targets[node.id]?.let { treeMenu.showAt(offset, it) }
                                },
                                rowTag = { node -> node.treeRowTag() },
                                twistyTag = { node -> schemaTableObjectsTag(node.label) },
                                // 浏览模式开着转圈：这里有真窗口的用户盯着，
                                // 而「还在加载」是必须看得见的。
                                showLoadingSpinner = true,
                            )
                        }
                    }
                    // 菜单**弹层**是 `:shared` 的那份（分析模式共用），
                    // 这里只给**浏览模式自己的菜单项**。
                    TreeContextMenu(menu = treeMenu) { target ->
                        BrowserTreeMenuItems(state = state, menu = treeMenu, target = target)
                    }
                }
            }
        }

        // 表结构弹窗挂在**面板这一层**而不是节点里：节点在 LazyColumn 里会被回收，
        // 弹窗跟着回收就变成「我正填着字窗口自己关了」。与 [TreeContextMenuState]
        // 同一个理由（宿主必须一直在组合里）。
        state.structureDialog?.let { dialog ->
            TableStructureDialog(
                state = dialog,
                dialect = state.currentConnection?.dialect ?: DialectType.UNKNOWN,
                loadRefColumns = { refTable ->
                    state.refColumnsFor(dialog.draft.database, dialog.draft.schema, refTable)
                },
                onSave = { state.saveTableStructure(it) },
                onDismiss = { state.dismissStructureDialog() },
            )
        }
        state.pendingDeleteTable?.let { target ->
            AlertDialog(
                onDismissRequest = { state.dismissDeleteTable() },
                title = { Text(str(StrKey.TABLE_DELETE_CONFIRM, target.table.orEmpty())) },
                text = { Text(str(StrKey.TABLE_DELETE_CONFIRM_HINT)) },
                confirmButton = {
                    WinButton(
                        onClick = { state.confirmDeleteTable() },
                        shape = SundaysPalette.buttonShape,
                        modifier = Modifier.testTag(TABLE_STRUCTURE_DELETE_CONFIRM_BTN_TAG),
                    ) {
                        Text(str(StrKey.TABLE_DELETE), maxLines = 1, softWrap = false)
                    }
                },
                dismissButton = {
                    WinTextButton(onClick = { state.dismissDeleteTable() }, shape = SundaysPalette.buttonShape) {
                        Text(str(StrKey.CANCEL), maxLines = 1, softWrap = false)
                    }
                },
            )
        }
    }
}

/**
 * 浏览模式树右键菜单的**菜单项**。
 *
 * ## 为什么菜单项与「弹层」分开写
 *
 * 弹层（定位、钳位、关闭）在 `:shared` 的 `TreeContextMenu` 里 ——
 * 分析模式那棵树用的是**同一份**。而**菜单项本身**两棵树完全不同：
 * 这里给的是「复制名称 / 复制引用 / 复制建表 DDL / 编辑表结构 / 删除表 / 新增表」。
 *
 * 「点了菜单项先关菜单」这条规矩写在 `:shared` 的 `TreeContextMenuItem` 里 ——
 * 两棵树共用一处，不会有一棵忘了（漏了的后果是菜单杵在树上不消失，
 * 下一次右键被压在旧 Popup 底下，看起来像「右键没反应了」）。
 *
 * @param state 浏览模式的状态机（复制、删表、开弹窗都走它）
 * @param menu 菜单状态（菜单项靠它把自己关掉）
 * @param target 这次右键作用的目标节点
 */
@Composable
private fun BrowserTreeMenuItems(
    state: DatabaseBrowserState,
    menu: TreeContextMenuState<DatabaseBrowserState.TreeTarget>,
    target: DatabaseBrowserState.TreeTarget,
) {
    TreeContextMenuItem(menu, TREE_MENU_COPY_NAME_TAG, str(StrKey.COPY_NAME)) {
        state.copyTreeText(target, quoted = false)
    }
    TreeContextMenuItem(
        menu,
        TREE_MENU_COPY_REF_TAG,
        // 标签写清复制的是什么：库节点给库引用、字段节点给「表.字段」，
        // 三种形态长得不一样，只写「复制引用名」用户得自己猜
        if (target.column != null) str(StrKey.COPY_COLUMN_REF) else str(StrKey.COPY_REF_NAME),
    ) {
        state.copyTreeText(target, quoted = true)
    }

    // ⚠️ 判据必须是「表节点**且不是**字段节点」，而不是「有没有 table」——
    // 字段目标的 `table` 也是非空的。第一版只判 `target.table != null`，
    // 于是右键字段时菜单里也冒出「复制建表 DDL」，而 DDL 是**整张表**的，
    // 用户在字段上点它拿到的是一张毫不相干的表的建表语句。
    if (target.table != null && target.column == null) {
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TreeContextMenuItem(menu, TREE_MENU_COPY_DDL_TAG, str(StrKey.COPY_CREATE_TABLE_DDL)) {
            state.copyTreeDdl(target)
        }
        TreeContextMenuItem(menu, TREE_MENU_EDIT_STRUCTURE_TAG, str(StrKey.TABLE_EDIT_STRUCTURE)) {
            state.openTableStructureDialog(target)
        }
        TreeContextMenuItem(menu, TREE_MENU_DELETE_TABLE_TAG, str(StrKey.TABLE_DELETE)) {
            state.requestDeleteTable(target)
        }
    }

    // 「新增表」挂在**库节点**上：表级区块里的三项（复制 DDL / 编辑表结构 /
    // 删除表）都是针对一张**已存在**的表，在库节点上全都无从谈起。
    // 而在表节点上放「新增表」会让人以为是在给这张表加一张附属表。
    if (target.table == null && target.column == null) {
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TreeContextMenuItem(menu, TREE_MENU_NEW_TABLE_TAG, str(StrKey.TABLE_NEW)) {
            state.openNewTableDialog(target)
        }
    }
}

/**
 * 树的一次快照 —— 节点、展开态、以及每个节点的右键目标。
 *
 * ## 为什么是「快照」而不是三个独立的 map
 *
 * 节点、展开态、右键目标是**同一份数据的三面**：某张表展开了，
 * 它的节点就带着字段、它的 id 就在展开集合里、它的右键目标也就存在。
 * 分成三份并行结构，就有了「三处可能对不上」的机会 —— 而对不上的后果
 * 是「点右键弹出来的菜单作用在别的表上」（本类历史上真踩过：
 * 右键第 5 行的库，菜单却弹在第 2 行那棵树上）。
 */
private class ObjectTreeSnapshot(
    val roots: List<ObjectTreeNode>,
    val expandedIds: Set<String>,
    /** 节点 id → 右键菜单的目标。 */
    val targets: Map<String, DatabaseBrowserState.TreeTarget>,
)

/**
 * 把浏览状态转成共享树的节点。
 *
 * ## 为什么这里**只**产出数据，界面全在共享组件里
 *
 * 树的画法（箭头、图标、字段行的 `PK / AI / 非空` 标记）现在由
 * `com.langbiantianya.sundays.tree.ObjectTree` 一处说了算 ——
 * 分析模式那棵树用的是**同一个**组件。两边若各画各的，
 * 同一个概念就有两份实现，改一处就忘了另一处。
 *
 * 这里负责的只有「**状态 → 节点**」这一层翻译，以及每个节点的
 * 右键目标（那要靠 `DatabaseBrowserState.TreeTarget`，共享组件不认识它）。
 */
@Composable
private fun DatabaseBrowserState.objectTreeSnapshot(): ObjectTreeSnapshot {
    val expanded = mutableSetOf<String>()
    val targets = mutableMapOf<String, DatabaseBrowserState.TreeTarget>()
    val roots =
        databases.map { db ->
            val dbId = schemaTreeDbNodeId(db)
            val dbExpanded = db in expandedDatabases
            if (dbExpanded) expanded += dbId
            val schema = schemasByDatabase[db]?.firstOrNull().orEmpty()
            targets[dbId] = DatabaseBrowserState.TreeTarget(database = db, schema = schema)
            val childTables =
                if (dbExpanded) {
                    (tablesByDatabase[db] ?: emptyList()).map { table ->
                        tableNode(db, table, schema, expanded, targets)
                    }
                } else {
                    emptyList()
                }
            ObjectTreeNode(
                id = dbId,
                label = db,
                kind = ObjectTreeKind.DATABASE,
                loading = db in loadingTables,
                error = tableLoadError[db],
                children = childTables,
                objectGroupsTitle = str(StrKey.OBJECT),
                objectGroups = databaseObjectGroups(db),
                // 库级对象还没回来时只显示「加载中」——
                // 不显示的话用户会以为这个库没有视图也没有触发器。
                objectGroupsLoading =
                    objectsByDatabase[db] == null && loadingObjects.any { it.first == db },
            )
        }
    return ObjectTreeSnapshot(roots = roots, expandedIds = expanded, targets = targets)
}

/**
 * 一张表的节点（连同它的字段与索引 / 外键）。
 *
 * @param db 所属库名
 * @param table 表名
 * @param schema 该库的 schema 名（空串 = 该方言没有独立 schema 层）
 * @param expanded 展开态的收集器 —— 表展开了就把它的 id 放进去
 * @param targets 右键目标的收集器
 */
@Composable
private fun DatabaseBrowserState.tableNode(
    db: String,
    table: String,
    schema: String,
    expanded: MutableSet<String>,
    targets: MutableMap<String, DatabaseBrowserState.TreeTarget>,
): ObjectTreeNode {
    val slot = "$db::$table"
    val tableId = schemaTreeTableNodeId(db, table)
    val tableExpanded = slot in expandedTableObjects
    if (tableExpanded) expanded += tableId
    targets[tableId] =
        DatabaseBrowserState.TreeTarget(database = db, schema = schema, table = table)

    val loaded = columnsOf(db, table)
    val columnNodes =
        loaded.orEmpty().map { col ->
            val colId = schemaTreeColumnNodeId(db, table, col.name)
            targets[colId] =
                DatabaseBrowserState.TreeTarget(
                    database = db,
                    schema = schema,
                    table = table,
                    column = col.name,
                )
            ObjectTreeNode(
                id = colId,
                label = col.name,
                kind = ObjectTreeKind.COLUMN,
                // 字段行的「上级」是表 —— 右键复制引用时要的是 `表.字段`。
                parentLabel = table,
                column =
                    ObjectTreeColumn(
                        name = col.name,
                        type = col.typeWithSize,
                        primaryKey = col.primaryKey,
                        autoIncrement = col.autoIncrement,
                        nullable = col.nullable,
                    ),
            )
        }

    return ObjectTreeNode(
        id = tableId,
        label = table,
        kind = ObjectTreeKind.TABLE,
        // 表行的「上级」是库 —— 点它要开的是「这个库里的这张表」的预览。
        parentLabel = db,
        children = columnNodes,
        // 展开后、字段还没回来之前也算「加载中」——
        // 否则那一瞬间会渲染成「(无)」，看起来像这张表没有字段。
        loading = isTableObjectLoading(db, table) || (loaded == null && tableExpanded),
        error = tableObjectError["$slot::${DatabaseBrowserState.TableObjectKind.COLUMNS.name}"],
        objectGroups =
            listOf(
                ObjectTreeObjectGroup(
                    label = str(StrKey.INDEX),
                    items = tableObjects[slot]?.get(DatabaseBrowserState.TableObjectKind.INDEX).orEmpty(),
                ),
                ObjectTreeObjectGroup(
                    label = str(StrKey.FOREIGN_KEY),
                    items = tableObjects[slot]?.get(DatabaseBrowserState.TableObjectKind.FOREIGN_KEY).orEmpty(),
                ),
            ),
    )
}

/**
 * 库级对象（视图 / 触发器 / 过程·函数）—— 每类一组。
 *
 * ## 为什么**逐类**带自己的错误
 *
 * SQLite 没有触发器与存储过程（`SQLiteDialect` 对它们抛
 * `UnsupportedOperationException`），于是「任意一类出错 → 整个对象区
 * 换成一行错误」会让**视图也跟着消失** —— 而那恰恰是唯一有内容的那一类。
 */
@Composable
private fun DatabaseBrowserState.databaseObjectGroups(db: String): List<ObjectTreeObjectGroup> =
    DatabaseBrowserState.DatabaseObjectKind.entries.map { kind ->
        ObjectTreeObjectGroup(
            label = str(kind.labelKey),
            items = objectsByDatabase[db]?.get(kind).orEmpty(),
            error = objectLoadError["$db::${kind.name}"],
        )
    }

/**
 * 树上的展开 / 收起 —— 按节点的种类分派。
 *
 * 容器节点展开 = 加载它的表；表节点展开 = 加载它的字段 / 索引 / 外键。
 * 字段是叶子，没有可展开的东西。
 */
private fun DatabaseBrowserState.toggleTreeNode(node: ObjectTreeNode) {
    when (node.kind) {
        ObjectTreeKind.DATABASE -> toggleDatabase(node.label)
        ObjectTreeKind.TABLE -> toggleTableTreeNode(node)
        ObjectTreeKind.COLUMN -> Unit
    }
}

/** 展开一张表 —— 加载它的字段 / 索引 / 外键。 */
private fun DatabaseBrowserState.toggleTableTreeNode(node: ObjectTreeNode) {
    val db = node.parentLabel
    if (db != null) toggleTableObjects(db, node.label)
}

/**
 * 一行的测试标记 —— **沿用从前的那些**，只是按节点种类分派。
 *
 * ## 为什么不让共享组件自己生成
 *
 * 那些 tag（`schemaDb_` / `schemaTable_` / `schemaColumn_`）已经被一堆
 * 测试用惯了，而它们承载的信息量并没有变 —— 改掉它们等于让所有引用点
 * 跟着改，却换不来任何好处。
 */
private fun ObjectTreeNode.treeRowTag(): String =
    when (kind) {
        ObjectTreeKind.DATABASE -> schemaDbNodeTag(label)
        ObjectTreeKind.TABLE -> schemaTableLeafTag(label)
        ObjectTreeKind.COLUMN -> schemaColumnTag(label)
    }

/** 容器节点的 id。 */
private fun schemaTreeDbNodeId(db: String): String = "db:$db"

/** 表节点的 id。 */
private fun schemaTreeTableNodeId(
    db: String,
    table: String,
): String = "table:$db::$table"

/** 字段节点的 id。 */
private fun schemaTreeColumnNodeId(
    db: String,
    table: String,
    column: String,
): String = "column:$db::$table::$column"
