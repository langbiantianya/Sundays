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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.SqlIdentifier
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.TABLE_STRUCTURE_DELETE_CONFIRM_BTN_TAG
import com.langbiantianya.sundays.table.TableStructureDialog
import com.langbiantianya.sundays.ui.ColumnBadge
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.WinTextButton
import com.langbiantianya.sundays.ui.focusRing
import com.langbiantianya.sundays.ui.menuShape
import com.langbiantianya.sundays.ui.onRightClick
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

    fun dismiss() {
        target = null
    }
}

/**
 * 记录本节点相对 root 的原点，供右键时把局部坐标换算成 root 坐标。
 *
 * 为什么必须是 `positionInRoot` 而不是 `positionInParent`：节点在 `LazyColumn` 里，
 * `positionInParent` 只给出「相对那一行 item」的偏移，中间隔着 LazyColumn 的滚动偏移、
 * 面板标题栏、缩进层级，逐级上溯既繁琐又容易漏一级。`positionInRoot` 一次到位，
 * **节点在树里第几行、缩进多深、在不在 LazyColumn 里都不影响**。
 */
private fun Modifier.trackNodeCoordinates(
    coords: MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
): Modifier = this.onGloballyPositioned { coords.value = it }

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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.databases) { db ->
                            DatabaseNode(
                                name = db,
                                state = state,
                                onOpenTable = onOpenTable,
                                menu = treeMenu,
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

@Composable
private fun DatabaseNode(
    name: String,
    state: DatabaseBrowserState,
    onOpenTable: (schema: String, table: String) -> Unit,
    menu: TreeContextMenuState,
) {
    val expanded = name in state.expandedDatabases
    val tables = state.tablesByDatabase[name]
    val loading = name in state.loadingTables
    val error = state.tableLoadError[name]

    // 节点在 root 里的原点 —— 右键坐标是**节点局部**的，菜单要的是**root**坐标，
    // 两者必须靠它换算（见 TreeContextMenuState.show）。
    val nodeCoords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .trackNodeCoordinates(nodeCoords)
                    .focusRing()
                    .onRightClick { offset ->
                        menu.show(
                            offset,
                            // 当场读 `positionInRoot()` 而不是读缓存的 Offset：
                            // 展开 / 滚动之后缓存值可能已经过期，而 `LayoutCoordinates`
                            // 是个活对象，此刻读到的就是**此刻**的位置。
                            nodeCoords.value?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero,
                            DatabaseBrowserState.TreeTarget(
                                database = name,
                                schema = state.schemasByDatabase[name]?.firstOrNull().orEmpty(),
                            ),
                        )
                    }.clickable { state.toggleDatabase(name) }
                    .testTag(schemaDbNodeTag(name))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (loading) {
                Spacer(Modifier.width(8.dp))
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
        }
        if (expanded) {
            when {
                error != null -> {
                    Text(
                        text = str(StrKey.TREE_LOAD_FAILED, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                    )
                }

                tables == null -> {
                    Text(
                        text = str(StrKey.TREE_LOADING),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                    )
                }

                tables.isEmpty() -> {
                    Text(
                        text = str(StrKey.TREE_EMPTY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
                    )
                }

                else -> {
                    tables.forEach { tbl ->
                        val slot = "$name::$tbl"
                        TableLeaf(
                            tableName = tbl,
                            database = name,
                            schema = state.schemasByDatabase[name]?.firstOrNull().orEmpty(),
                            menu = menu,
                            // 字段行单击 = 复制字段引用，与右键菜单第二项同一个动作。
                            // 与右键的判据刻意对齐：右键能复制的行，左键也能。
                            onCopyColumn = { state.copyTreeText(it, quoted = true) },
                            onOpen = { onOpenTable(name, tbl) },
                            expanded = slot in state.expandedTableObjects,
                            onToggleObjects = { state.toggleTableObjects(name, tbl) },
                            columns = state.columnsOf(name, tbl),
                            // 展开后、字段还没回来之前也算「加载中」——
                            // 否则那一瞬间会渲染成「(无)」，看起来像这张表没有字段。
                            columnsLoading =
                                state.isTableObjectLoading(name, tbl) ||
                                    (state.columnsOf(name, tbl) == null && slot in state.expandedTableObjects),
                            columnsError = state.tableObjectError["$slot::${DatabaseBrowserState.TableObjectKind.COLUMNS.name}"],
                            indexNames =
                                state.tableObjects[slot]
                                    ?.get(DatabaseBrowserState.TableObjectKind.INDEX)
                                    .orEmpty(),
                            foreignKeyNames =
                                state.tableObjects[slot]
                                    ?.get(DatabaseBrowserState.TableObjectKind.FOREIGN_KEY)
                                    .orEmpty(),
                        )
                    }
                    // 库级对象（视图 / 触发器 / 过程·函数）排在表之后。
                    // 顺序有讲究：用户 90% 的时间在找表，把表放前面、对象放后面，
                    // 免得每展开一个库都先滚过一屏用不上的东西。
                    //
                    // ⚠️ 错误必须**逐类**显示，不能「任意一类出错 → 整个对象区换成一行错误」。
                    // SQLite 没有触发器与存储过程（`SQLiteDialect` 对它们抛
                    // UnsupportedOperationException），于是原实现下 **视图也跟着消失了** ——
                    // 用户看到的只是一行红字「对象加载失败」，而那恰恰是唯一有内容的那一类。
                    // 「一个 schema 失败不该让其他同类对象消失」在同一个 kind 内已经处理过，
                    // 但跨 kind 这层漏了；**只有同时跑两个方言才照得出来**。
                    val objBusy = state.loadingObjects.any { it.first == name }
                    val objs = state.objectsByDatabase[name]
                    if (objs == null && objBusy) {
                        Text(
                            text = str(StrKey.TREE_OBJECTS_LOADING),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                        )
                    } else {
                        Column(modifier = Modifier.padding(start = 24.dp)) {
                            Text(
                                text = str(StrKey.OBJECT),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DatabaseBrowserState.DatabaseObjectKind.entries.forEach { kind ->
                                val err = state.objectLoadError["$name::${kind.name}"]
                                if (err != null) {
                                    Text(
                                        text = str(StrKey.OBJECT_LOAD_ERROR_PREFIX, str(kind.labelKey), err),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                                    )
                                } else {
                                    ObjectGroupRow(
                                        label = str(kind.labelKey),
                                        items = objs?.get(kind).orEmpty(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 对象分组下的一行：`标签: 名字1, 名字2 …`。空列表显示「(无)」。 */
@Composable
private fun ObjectGroupRow(
    label: String,
    items: List<String>,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (items.isEmpty()) str(StrKey.NONE_PAREN) else items.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 树上的一个表叶子 —— 单击打开数据预览。
 *
 * ## 为什么从 `detectTapGestures(onDoubleTap)` 换成 `clickable`
 *
 * 旧写法只挂手势，**不带任何语义**：读屏用户听到的只是一个静态文本，没有「打开表」这个动作，
 * 键盘用户 Tab 过去按 Enter 也没反应 —— 等于这条路径对两类用户完全不存在。
 * 换用 `clickable` 后三者一起拿到：语义动作（`onClickLabel`）、键盘焦点与 Enter/Space 激活、
 * 鼠标单击。
 *
 * **双击不会因此失效**：一次双击会产生两次 `onClick`，第二次进 [DatabaseBrowserState.openTab]
 * 时该表已存在，按 key 命中「只激活、不新增」的分支，是幂等的。旧的手势检测器还要额外
 * 引入 `awaitPointerEventScope`，与 [reportsHover] 里同样的手写监听重复。
 */
@Composable
private fun TableLeaf(
    tableName: String,
    /** 所属库名 —— 限定引用名要用（`schema.table` 里的 schema 与表名两段都需要它）。 */
    database: String = "",
    /** 所属 schema 名；空串 = 该方言没有独立 schema 层或尚未解析（见 [SqlIdentifier.tableRef]）。 */
    schema: String = "",
    /** 右键菜单状态 —— 由 [SchemaTreePanel] 持有并下发（见 [TreeContextMenu]）。 */
    menu: TreeContextMenuState? = null,
    /**
     * 单击某个**字段行**时的动作（收到该字段的完整目标）；`null` = 字段行不可单击。
     *
     * 为什么由父级给而不是 `TableLeaf` 自己调 [DatabaseBrowserState]：
     * `TableLeaf` 不持有 `state`，动作要发剪贴板 / 推通知，那些都在 state 里。
     * 与 [TableLeaf.onOpen] 同一套「父级给动作」的写法。
     */
    onCopyColumn: ((DatabaseBrowserState.TreeTarget) -> Unit)? = null,
    onOpen: () -> Unit,
    expanded: Boolean = false,
    onToggleObjects: (() -> Unit)? = null,
    indexNames: List<String> = emptyList(),
    foreignKeyNames: List<String> = emptyList(),
    /** 字段列表；`null` = 还没加载完（与「加载完但为空」要区分开，见 [ColumnRowGroup]）。 */
    columns: List<DatabaseBrowserState.ColumnInfo>? = null,
    columnsLoading: Boolean = false,
    columnsError: String? = null,
) {
    // 节点在 root 里的原点 —— 右键坐标换算用（见 TreeContextMenuState.show）。
    val nodeCoords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .trackNodeCoordinates(nodeCoords)
                    .focusRing()
                    // 右键与左键**不冲突**，两者互不干扰：`clickable` 的 tap 检测只认主键，
                    // 右键是次键（`isSecondaryPressed`），压根不满足 tap 条件。
                    // 试过把 `onRightClick` 挪到 `clickable` **之后**（曾以为 `consume()`
                    // 会串味），实测两轮 UI 测试都没红 —— 顺序在这里**无关**，
                    // 但保留当前写法是因为它读起来就是「先识别手势、再挂动作」。
                    .then(
                        if (menu != null) {
                            Modifier.onRightClick { offset ->
                                menu.show(
                                    offset,
                                    nodeCoords.value?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero,
                                    DatabaseBrowserState.TreeTarget(
                                        database = database,
                                        schema = schema,
                                        table = tableName,
                                    ),
                                )
                            }
                        } else {
                            // 预览里没有右键菜单的用法（传 null 的调用点不期待菜单出现）。
                            // 挂一个空 Modifier 而不是硬传 —— 见 [TableLeaf.menu] 的 KDoc。
                            Modifier
                        },
                    ).clickable(
                        onClickLabel = str(StrKey.OPEN_TABLE, tableName),
                        role = Role.Button,
                        onClick = onOpen,
                    ).testTag(schemaTableLeafTag(tableName))
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleObjects != null) {
                // 展开箭头：与库节点的 chevron 同一套视觉语言。
                // 单独一个小按钮而不是让整行双击 —— 整行单击已经占用在「打开预览」上，
                // 同一个手势不能既开预览又展开对象。
                //
                // ⚠️ 描述要说「字段与索引外键」而不是只有索引外键：读屏用户听到
                // 「展开 orders 的索引与外键」，展开后冒出几十个字段却毫无预期。
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription =
                        if (expanded) {
                            str(StrKey.COLLAPSE_TABLE_DETAIL, tableName)
                        } else {
                            str(StrKey.EXPAND_TABLE_DETAIL, tableName)
                        },
                    modifier =
                        Modifier
                            .size(16.dp)
                            .testTag(schemaTableObjectsTag(tableName))
                            .clickable { onToggleObjects() },
                )
                Spacer(Modifier.width(2.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Icon(
                imageVector = Icons.Filled.TableChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tableName,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded) {
            // 字段 / 索引 / 外键放在**表节点下**而不是库节点下：引擎侧这几条路由要
            // `table_name`，跟着表走才对得上；放库下就得把整库每张表的都拉一遍。
            Column(modifier = Modifier.padding(start = 44.dp)) {
                // 字段**排在最前**：用户展开一张表，八成是想知道它有哪些列、都是什么类型，
                // 而不是先看索引。
                ColumnRowGroup(
                    label = str(StrKey.COLUMN),
                    columns = columns,
                    loading = columnsLoading,
                    error = columnsError,
                    menu = menu,
                    onCopyColumn = onCopyColumn,
                    target =
                        DatabaseBrowserState.TreeTarget(
                            database = database,
                            schema = schema,
                            table = tableName,
                        ),
                )
                ObjectGroupRow(str(StrKey.INDEX), indexNames)
                ObjectGroupRow(str(StrKey.FOREIGN_KEY), foreignKeyNames)
            }
        }
    }
}

/**
 * 带 loading / error / empty 三态的字段分组。
 *
 * ## 为什么自己拿列表，而不是塞一个 `content` lambda
 *
 * 第一版传的是 `content: @Composable ColumnScope.() -> Unit`，空判断靠一个
 * `var any by remember { mutableStateOf(false) }` —— 而 lambda 内部**没有**任何地方
 * 把它置 true，于是走 else 分支就必然打印一行「(无)」。
 * 真窗口上表现为：字段明明列了两行，下面还跟着一句「(无)」。
 *
 * 拿列表自己渲染就没有这个可能 —— **「有没有内容」和「渲染内容」在同一个作用域里**，
 * 不会再有中间那个观察不到的开关。
 *
 * @param columns `null` = 还没加载完（与「加载完但为空」要区分开，否则加载中的一瞬间
 *   会显示「(无)」，看起来像这张表没有字段）。
 */
@Composable
private fun ColumnRowGroup(
    label: String,
    columns: List<DatabaseBrowserState.ColumnInfo>?,
    loading: Boolean,
    error: String?,
    /** 字段行的右键菜单与目标（库/表信息）—— 传 null 则字段行不可右键，见 [ColumnRow]。 */
    menu: TreeContextMenuState? = null,
    /** 字段行单击动作；`null` = 不可单击（见 [TableLeaf.onCopyColumn]）。 */
    onCopyColumn: ((DatabaseBrowserState.TreeTarget) -> Unit)? = null,
    target: DatabaseBrowserState.TreeTarget? = null,
) {
    Column(modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$label：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Spacer(Modifier.width(6.dp))
                WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(11.dp))
            }
        }
        when {
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp, top = 1.dp),
                )
            }

            // 加载中也显示「(无)」的话，用户会以为这张表没有字段，而那只是一瞬间的假象。
            loading -> {
                Unit
            }

            columns.isNullOrEmpty() -> {
                Text(
                    text = str(StrKey.NONE_PAREN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 1.dp),
                )
            }

            else -> {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    columns.forEach { col ->
                        ColumnRow(
                            col = col,
                            menu = menu,
                            onCopyColumn = onCopyColumn,
                            target = target?.copy(column = col.name),
                        )
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
 * ## 为什么字段行也必须可点击（用户报：「不知道点了哪里」）
 *
 * 库节点与表节点都挂了 `focusRing` + `clickable`，只有字段行两个都没有 ——
 * 于是鼠标划过字段区时**整片没有任何反应**：没有 hover 底色、没有按下反馈，
 * 用户既看不出自己悬停在哪一行，点下去也没有任何确认。
 * 字段行恰恰是树里**最长、最密**的一批行（一张表几十个字段），
 * 没有逐行反馈就完全靠读文字定位，扫视成本最高的地方反而反馈最少。
 *
 * 动作选「复制字段引用」，与右键菜单的第二项完全一致：
 * 字段没有「打开预览」的语义（预览是**表**级的），复制引用是它唯一能做的事；
 * 而且点了立刻有反馈（通知 + 剪贴板），用户知道自己点到了哪一行。
 *
 * `focusRing` 放在 `clickable` **之前**：焦点环靠 `padding` 让出宽度、画在容器外侧，
 * 与「选中态」的背景区分得开 —— 两者在树里长得都是一圈高亮，不分开会误判。
 */
@Composable
private fun ColumnRow(
    col: DatabaseBrowserState.ColumnInfo,
    /** 非 null 时这一行可右键（复制字段引用）；null = 纯展示（表格编辑器里的同名列没有右键）。 */
    menu: TreeContextMenuState? = null,
    /** 非 null 时这一行可单击；null = 纯展示。与 [menu] 的判据刻意一致 —— 右键能复制，
     *  单击也该能复制同一个东西，不该出现「右键行可复制、左键行没反应」的分裂。 */
    onCopyColumn: ((DatabaseBrowserState.TreeTarget) -> Unit)? = null,
    target: DatabaseBrowserState.TreeTarget? = null,
) {
    // 同上：字段行的原点也要记，否则字段菜单会偏到面板顶部。
    val nodeCoords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackNodeCoordinates(nodeCoords)
                .focusRing()
                .then(
                    if (menu != null && target != null) {
                        Modifier.onRightClick { offset ->
                            menu.show(
                                offset,
                                nodeCoords.value?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero,
                                target,
                            )
                        }
                    } else {
                        Modifier
                    },
                ).then(
                    if (onCopyColumn != null && target != null) {
                        Modifier.clickable(
                            // 语义动作名：读屏用户听到的是「复制字段 USERNAME」而不是「按钮」
                            onClickLabel = str(StrKey.COPY_COLUMN, col.name),
                            role = Role.Button,
                        ) { onCopyColumn(target) }
                    } else {
                        Modifier
                    },
                )
                // ⚠️ tag 挂在**整行**而不是列名文本上，且这与 [TableLeaf] 一致
                // （clickable 与 testTag 都在 Row 上）：
                //
                // 字段行加上 `clickable` 之后**语义会被合并**（clickable 默认
                // `mergeDescendants = true`），原先挂在列名 `Text` 上的 tag 被吸收进 Row ——
                // 合并后的语义树里再也查不到它，而未合并的树里还在，于是测试报的是
                // 「could not find any node … However, the unmerged tree contains '1' node
                // that matches」，极难看出真实原因（这次真踩过）。
                //
                // 挂整行对测试只会更好：`rightClickOn(schemaColumnTag(...))` 右键的是**整行**，
                // 正是 [ColumnRow] 上那个 `onRightClick` 挂在的地方；而 tag 名本身就含列名，
                // 命中哪一行不存在歧义。
                .testTag(schemaColumnTag(col.name))
                .padding(top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = col.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (col.primaryKey) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = col.typeWithSize,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 约束标记。空列不占位，否则没有约束的普通字段会整体左移、参差不齐
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (col.primaryKey) ColumnBadge("PK")
            if (col.autoIncrement) ColumnBadge("AI")
            // 只在**不可空**时标记：「可空」是绝大多数列的默认，写出来全是噪音
            if (!col.nullable) ColumnBadge(str(StrKey.NOT_NULL))
        }
    }
}
