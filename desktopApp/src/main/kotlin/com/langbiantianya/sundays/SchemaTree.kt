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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.table.TABLE_STRUCTURE_DELETE_CONFIRM_BTN_TAG
import com.langbiantianya.sundays.table.TableStructureDialog
import com.langbiantianya.sundays.tree.ObjectTree
import com.langbiantianya.sundays.tree.ObjectTreeColumn
import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode
import com.langbiantianya.sundays.tree.ObjectTreeObjectGroup
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.menuShape
import com.langbiantianya.sundays.ui.str
import kotlin.math.roundToInt

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

/**
 * 对象树右键菜单状态。
 *
 * 与 [com.langbiantianya.sundays.ui.ContextMenuState] 分开而不是复用：那个的 `position`
 * 语义是「相对 DataTable 的偏移」，而这里直接存「相对 root 的坐标」。
 *
 * ## ⚠️ [show] 的坐标换算是这里存在的全部理由
 *
 * 节点给的是**节点局部坐标**（[com.langbiantianya.sundays.ui.onRightClick] 回调里的
 * `PointerInputChange.position` 就是相对挂 `pointerInput` 的那个节点），
 * 直接透传给 `DropdownMenu(offset = …)` 的后果不是「偏几个像素」，而是「偏整屏」：
 * 节点在树里的第 N 行，它的局部 y 只有十几像素，却被当成了屏幕坐标，
 * 于是菜单恒定弹在面板顶部附近。
 *
 * 真窗口实测（1152×720 窗口，density 1.25）：右键第 5 行的 `postgres`，
 * 菜单顶边出现在 y≈145 而非 y≈559，**偏上约 414px**，且正好盖在 `examquestions`
 * 那一行上 —— 用户盯着 `examquestions` 点「复制名称」，剪贴板里进来的是 `postgres`。
 * 不报错、剪贴板确有内容，但**菜单与它作用的节点在视觉上对不上**，结果就是错的。
 *
 * ## ⚠️ `DropdownMenu.offset` 的基准既不是「调用它的节点」，也不是「菜单顶边」
 *
 * 这是本类最容易搞错的一处，且**文档没说**。第一版按「offset 相对宿主布局节点」实现，
 * 写成 `at + nodeOrigin - hostOrigin` —— 真窗口实测菜单位置仍偏上，偏的量恰好等于
 * `hostOrigin.y`（实测：减去时菜单顶边在屏幕 y≈165；不减去时 y≈298，正好差 133 = hostOrigin.y）。
 *
 * 结论是 Material3 的 `DropdownMenu` 把 `offset` 解析在 **root 坐标系**上，
 * 所以换算就是最直白的那个：**右键点的 root 坐标 = 节点原点 + 节点内偏移**。
 *
 * 而这个结论**最后还是被整个丢掉了** —— 见 [TreeContextMenu]：再往下量才证明
 * `DropdownMenu` 把 `offset.y` 解析成「菜单**底边**相对宿主的偏移」，
 * 菜单位置 = `宿主y + offset.y − 菜单高`，依赖运行时才知道的菜单高度，
 * 根本没法拿常量补。最终改用 `Popup` 自己定位：[offsetInRoot] 仍是 root 坐标，
 * [hostOrigin] 负责把它折算成 `Popup` parent 的局部坐标 —— 宿主原点只在这一步用得上。
 *
 * > 这条结论来自真窗口实测而不是文档推断。**UI 测试里量不到它**：
 * > Popup 内节点的 `getUnclippedBoundsInRoot()` 在 `runComposeUiTest` 下与真实渲染
 * > 位置对不上（诊断值 98 / 实测 161 / 反推基准 63，既不是 0 也不是 host=106）。
 * > 见 [TreeContextMenuUiTest] 里关于「为什么 UI 层不断言菜单位置」的说明。
 */
@Stable
class TreeContextMenuState {
    /** 被右键的节点；`null` = 菜单关闭。 */
    var target: DatabaseBrowserState.TreeTarget? by mutableStateOf(null)
        private set

    /** 右键点击点，相对 **root**（窗口内容区）。 */
    var offsetInRoot: androidx.compose.ui.geometry.Offset by
        mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
        private set

    /**
     * 菜单宿主（[TreeContextMenu] 的 `Popup` 所在的 `Column`）相对 root 的原点。
     *
     * `Popup` 的 `offset` 是文档写明的「相对 **parent** 左上角」，而 parent 就是这个
     * Column；[TreeContextMenu] 在弹出时用 `offsetInRoot − hostOrigin` 换算过去。
     * 由 [SchemaTreePanel] 在布局时写入。
     */
    var hostOrigin: androidx.compose.ui.geometry.Offset by
        mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
        private set

    fun updateHostOrigin(at: androidx.compose.ui.geometry.Offset) {
        hostOrigin = at
    }

    /**
     * @param at 右键点击点，**相对本节点**（`onRightClick` 回调的原样输出）
     * @param nodeOriginInRoot 本节点相对 root 的原点（`onGloballyPositioned` + `positionInRoot`）
     */
    fun show(
        at: androidx.compose.ui.geometry.Offset,
        nodeOriginInRoot: androidx.compose.ui.geometry.Offset,
        target: DatabaseBrowserState.TreeTarget,
    ) {
        offsetInRoot = at + nodeOriginInRoot
        this.target = target
    }

    /**
     * 直接用 **root 坐标**弹出菜单。
     *
     * ## 为什么多这一条
     *
     * [show] 收的是「节点局部坐标 + 节点原点」，让**调用方**做换算。
     * 而共享树组件（`com.langbiantianya.sundays.tree.ObjectTree`）自己知道
     * 节点在 root 里的位置 —— 它已经在节点上挂了 `onGloballyPositioned`。
     * 让它再把坐标换算一遍、只把 root 坐标交出来，菜单这边就少一次
     * 「这里到底是局部还是全局」的判断 —— 而这正是本类最容易搞错的一处。
     */
    fun showAt(
        offsetInRoot: androidx.compose.ui.geometry.Offset,
        target: DatabaseBrowserState.TreeTarget,
    ) {
        this.offsetInRoot = offsetInRoot
        this.target = target
    }

    fun dismiss() {
        target = null
    }
}

@Composable
private fun rememberTreeContextMenuState(): TreeContextMenuState = remember { TreeContextMenuState() }

// ============================================================================
// 左侧 schema/表 树
// ============================================================================

@Composable
internal fun SchemaTreePanel(
    state: DatabaseBrowserState,
    connected: Boolean,
    onOpenTable: (schema: String, table: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **必须显式持有** —— LazyColumn 不传 state 时内部用的是 `rememberLazyListState()`，
    // 而这个 remember 的宿主是**调用点的组合作用域**：右栏 pane 切换（表预览 ⇄ SQL ⇄ 造数）
    // 会把整棵子树移出再移回组合，remember 随之销毁、滚动位置回到顶部。
    // 结果就是「看一眼 SQL 回来，树又滚回顶部，得重新展开找到刚才那张表」——
    // 与本屏「每 sheet 状态互相独立、跨视图不丢失」的既有契约直接冲突。
    val listState = rememberLazyListState()

    // 右键菜单状态提到 LazyColumn **外面**：菜单是**屏级**的一个浮层，
    // 若把 `visible` 记在某个 item 的组合里，滚动时被回收的 item 会把菜单一起带走
    // （表现是「菜单自己关掉了」），且新 item 组合时又得重建一份状态。
    val treeMenu = rememberTreeContextMenuState()

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
                    TreeContextMenu(state = state, menu = treeMenu)
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
 * 树上右键菜单的状态 + 弹层。
 *
 * ## 为什么菜单挂在 [SchemaTreePanel] 这一层而不是每个节点内
 *
 * 三条理由，都是从「菜单到底属于谁」推出来的：
 *
 * 1. **生命周期**：菜单一旦打开，它的宿主就必须一直在组合里。节点在 `LazyColumn` 里，
 *    滚动时被回收 → 菜单跟着消失，用户正要点的项就没了。
 * 2. **唯一性**：同一时刻只应有一个菜单。挂在节点上会出现「两个节点各有一份状态、
 *    右键 B 时 A 的菜单还开着」的窗口。
 * 3. **坐标系**：菜单要按「面板内相对坐标」定位，而坐标是节点给的、面板是宿主 ——
 *    挂在面板上才有一致的参照系。
 *
 * @param menu 当前被右键的节点；`null` = 菜单关闭。
 */
@Composable
private fun TreeContextMenu(
    state: DatabaseBrowserState,
    menu: TreeContextMenuState,
) {
    val target = menu.target
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    /**
     * 菜单项的统一入口 —— 执行动作前**必须先关掉菜单**。
     *
     * 菜单不会因为「菜单项被点了」而自动关闭：它只认 dismiss 路径
     * （点在菜单外 / 按 Esc / 焦点丢失），菜单项的 `onClick` 是另一条路。
     * 第一版漏了 dismiss，真窗口上表现为：复制成功了、通知也弹了，
     * 但**那层菜单还杵在树上不消失**，用户接着右键别处时新菜单被压在旧 Popup 底下，
     * 于是「右键没反应了」。
     *
     * 纯状态机测试测不到这一层（菜单是 UI 的），它在
     * [TreeContextMenuUiTest.clickMenuItem] 的「菜单关闭」那一步被钉住。
     */
    @Composable
    fun item(
        tag: String,
        label: String,
        action: () -> Unit,
    ) {
        WinMenuItem(
            modifier = Modifier.testTag(tag),
            text = { Text(label) },
            onClick = {
                // 先关菜单再干活：复制 DDL 要发引擎往返（可能几十毫秒），
                // 菜单杵在这段时间里既挡视线又挡下一次右键
                menu.dismiss()
                action()
            },
        )
    }

    // 内容按 target==null 整块组合：Popup 没有 `expanded`，可见性就是「在不在组合里」。
    if (target == null) return

    // 边界钳位：菜单可能比窗口还高（三个菜单项 + 贴底右键），
    // 不钳位的话下半截直接跑出屏幕，用户点不到「复制建表 DDL」。
    // ⚠️ 全程用 **px**：`offsetInRoot` 是指针事件的原始像素值，混进 Dp 会差一个 density。
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val minWidthPx = with(density) { MENU_MIN_WIDTH.toPx() }
    val menuHeightPx = with(density) { MENU_ESTIMATED_HEIGHT.toPx() }
    val clampedX =
        menu.offsetInRoot.x
            .coerceIn(0f, (windowSize.width - minWidthPx).coerceAtLeast(0f))
    val clampedY =
        menu.offsetInRoot.y
            .coerceIn(0f, (windowSize.height - menuHeightPx).coerceAtLeast(0f))

    Popup(
        // ⚠️ 为什么不用 `DropdownMenu` —— 见 [TreeContextMenuState] 的 KDoc。
        // 简版：`DropdownMenu` 把 `offset.y` 解析成「菜单**底边**相对宿主的偏移」，
        // 于是菜单位置 = `宿主y + offset.y − 菜单高度`。菜单高度是运行时才知道的，
        // 换多少项就偏多少（真窗口实测：2 项菜单偏 +3.5，3 项菜单偏 −57.5，
        // 差值恰好是一个菜单项 60px 加 divider 1px）。
        // 「看起来对齐」纯属菜单高度与 offset 巧合相等 —— 加一项菜单就露馅。
        // `Popup` 的 offset 是文档写明的「相对 parent 左上角」，可以直接定位。
        //
        // ⚠️ `onDismissRequest` **必须显式传**：`Popup` 的默认值是空 lambda，
        // 漏掉的话点菜单外 / 按 Esc 只会关掉弹层而 `menu.target` 还在 ——
        // 组合里 Popup 一直在，于是「下一次右键别的节点，弹的还是上一次的菜单」。
        // 这正是真窗口走查时抓到的现象（点空白处后右键 postgres，弹出的仍是 biz_user 的菜单）。
        onDismissRequest = { menu.dismiss() },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        offset =
            IntOffset(
                x = (clampedX - menu.hostOrigin.x).roundToInt(),
                y = (clampedY - menu.hostOrigin.y).roundToInt(),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    // IntrinsicSize.Max：让菜单项的 fillMaxWidth 有意义 —— 宽度取内容最宽的那项，
                    // 而不是一个撑满屏幕的巨宽菜单。
                    .width(IntrinsicSize.Max)
                    // ⚠️ **`shadow` 必须在 `background` 之前**，顺序反了会同时坏掉两件事：
                    //
                    // `Modifier.shadow` 造的是 graphicsLayer，**只包含它链上后续的内容**。
                    // 写成 `.background(shape).border(shape).shadow(shape)` 时：
                    //   1. 圆角背景与边框落在 layer **之外** → 阴影的轮廓按 layer **内**的
                    //      菜单项矩形生成，于是「圆角菜单 + 直角阴影」对不上；
                    //   2. layer 的 `clip`（默认 elevation > 0 时为 true）裁不到
                    //      layer 外的背景，菜单项自己那层不透明背景也照原样画 →
                    //      菜单里出现一块**直角的内嵌方块**。
                    //
                    // 真窗口实测（修复前截图）：菜单项背景 `#211F26`(surfaceContainer)
                    // 呈直角，容器边框却是圆角，右侧还挂一条 `#1D1B21` 的阴影带。
                    //
                    // Material 自己的 `Modifier.surface` 也是这个顺序
                    // （`shadow(...).background(color, shape)`）—— 不是巧合。
                    .shadow(8.dp, menuShape())
                    .background(scheme.surfaceContainer, menuShape())
                    .border(1.dp, scheme.outlineVariant, menuShape())
                    .padding(vertical = 4.dp),
        ) {
            item(TREE_MENU_COPY_NAME_TAG, str(StrKey.COPY_NAME)) { state.copyTreeText(target, quoted = false) }
            item(
                TREE_MENU_COPY_REF_TAG,
                // 标签写清复制的是什么：库节点给库引用、字段节点给「表.字段」，
                // 三种形态长得不一样，只写「复制引用名」用户得自己猜
                if (target.column != null) str(StrKey.COPY_COLUMN_REF) else str(StrKey.COPY_REF_NAME),
            ) { state.copyTreeText(target, quoted = true) }

            // ⚠️ 判据必须是「表节点**且不是**字段节点」，而不是「有没有 table」——
            // 字段目标的 `table` 也是非空的。第一版只判 `target.table != null`，
            // 于是右键字段时菜单里也冒出「复制建表 DDL」，而 DDL 是**整张表**的，
            // 用户在字段上点它拿到的是一张毫不相干的表的建表语句。
            if (target.table != null && target.column == null) {
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                item(TREE_MENU_COPY_DDL_TAG, str(StrKey.COPY_CREATE_TABLE_DDL)) { state.copyTreeDdl(target) }
                item(TREE_MENU_EDIT_STRUCTURE_TAG, str(StrKey.TABLE_EDIT_STRUCTURE)) {
                    state.openTableStructureDialog(target)
                }
                item(TREE_MENU_DELETE_TABLE_TAG, str(StrKey.TABLE_DELETE)) {
                    state.requestDeleteTable(target)
                }
            }

            // 「新增表」挂在**库节点**上：表级区块里的三项（复制 DDL / 编辑表结构 /
            // 删除表）都是针对一张**已存在**的表，在库节点上全都无从谈起。
            // 而在表节点上放「新增表」会让人以为是在给这张表加一张附属表。
            if (target.table == null && target.column == null) {
                WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                item(TREE_MENU_NEW_TABLE_TAG, str(StrKey.TABLE_NEW)) {
                    state.openNewTableDialog(target)
                }
            }
        }
    }
}

/** 菜单最小宽度 —— 仅用于「贴到右边缘时往左收」的钳位计算。 */
private val MENU_MIN_WIDTH = 160.dp

/**
 * 菜单高度的**估算值**，只用于贴底时的钳位。
 *
 * 为什么只能是估算：真实高度要等布局完成才知道，而钳位发生在布局之前。
 * 偏大只是让贴底时往上多收一点（菜单不贴边），偏小才会截掉菜单项 ——
 * 所以宁可偏大：3 项菜单 ≈ 190dp，估 220dp。
 */
private val MENU_ESTIMATED_HEIGHT = 220.dp

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
