/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.langbiantianya.sundays.ui.WinMenuItem
import com.langbiantianya.sundays.ui.menuShape
import kotlin.math.roundToInt

/*
 * 对象树的**右键菜单** —— 状态、弹层、以及「点菜单项之前先关菜单」那条规矩。
 *
 * ## 为什么这一份**住在 `:shared`**
 *
 * 应用里有**两棵树**，都要右键菜单：
 *
 * | 树 | 住在 | 菜单里有什么 |
 * |---|---|---|
 * | 浏览模式的对象树 | `desktopApp` 的 `SchemaTree.kt` | 复制名称 / 复制引用 / 复制 DDL / 编辑表结构 / 删除表 / 新增表 |
 * | 分析模式的树 | `:shared` 的 `AnalysisScreen.kt` | 删除数据源 |
 *
 * 菜单项**各不相同**，但「菜单怎么弹、弹在哪、点了怎么关」是**同一件事**。
 * 早先这套机制只在 `desktopApp` 里（`TreeContextMenuState` + `TreeContextMenu`），
 * 于是分析模式要么复制一份（同一个概念两份实现，改一处忘另一处），
 * 要么就只能没有右键菜单。现在机制搬到这里，**两棵树共用一份**，菜单项各写各的。
 *
 * ## 菜单状态**为什么挂在宿主那一层**
 *
 * 三条理由，都是从「菜单到底属于谁」推出来的：
 *
 * 1. **生命周期**：菜单一旦打开，它的宿主就必须一直在组合里。节点在 `LazyColumn`
 *    里，滚动时被回收 → 菜单跟着消失，用户正要点的项就没了。
 * 2. **唯一性**：同一时刻只应有一个菜单。挂在节点上会出现「两个节点各有一份状态、
 *    右键 B 时 A 的菜单还开着」的窗口。
 * 3. **坐标系**：菜单要按「宿主内相对坐标」定位，而坐标是节点给的、宿主是参照系 ——
 *    挂在宿主上才有一致的参照系。
 *
 * ## ⚠️ 坐标换算是这里存在的全部理由
 *
 * 节点给的是**节点局部坐标**（`onRightClick` 回调里的位置就是相对挂
 * `pointerInput` 的那个节点），直接透传给菜单的后果不是「偏几个像素」，而是「偏整屏」：
 * 节点在树里的第 N 行，它的局部 y 只有十几像素，却被当成了屏幕坐标，
 * 于是菜单恒定弹在面板顶部附近。
 *
 * 真窗口实测（1152×720 窗口，density 1.25）：右键第 5 行的 `postgres`，
 * 菜单顶边出现在 y≈145 而非 y≈559，**偏上约 414px**，且正好盖在 `examquestions`
 * 那一行上 —— 用户盯着 `examquestions` 点「复制名称」，剪贴板里进来的是 `postgres`。
 * 不报错、剪贴板确有内容，但**菜单与它作用的节点在视觉上对不上**，结果就是错的。
 *
 * ## ⚠️ 为什么用 `Popup` 而不是 `DropdownMenu`
 *
 * `DropdownMenu.offset` 的基准**既不是**「调用它的节点」，也不是「菜单顶边」——
 * 第一版按「offset 相对宿主布局节点」实现，真窗口实测菜单位置仍偏上，
 * 偏的量恰好等于 `hostOrigin.y`。再往下量才证明 `DropdownMenu` 把 `offset.y`
 * 解析成「菜单**底边**相对宿主的偏移」，菜单位置 = `宿主y + offset.y − 菜单高`，
 * 依赖运行时才知道的菜单高度，根本没法拿常量补 —— 换多少项就偏多少
 * （真窗口实测：2 项菜单偏 +3.5，3 项菜单偏 −57.5，差值恰好是一个菜单项 60px 加
 * divider 1px）。「看起来对齐」纯属菜单高度与 offset 巧合相等，加一项菜单就露馅。
 *
 * `Popup` 的 offset 是文档写明的「相对 parent 左上角」，可以直接定位：
 * [TreeContextMenuState.offsetInRoot] 仍是 root 坐标，[TreeContextMenuState.hostOrigin]
 * 负责把它折算成 `Popup` parent 的局部坐标 —— 宿主原点只在这一步用得上。
 *
 * > 这条结论来自真窗口实测而不是文档推断。**UI 测试里量不到它**：
 * > Popup 内节点的 `getUnclippedBoundsInRoot()` 在 `runComposeUiTest` 下与真实渲染
 * > 位置对不上（诊断值 98 / 实测 161 / 反推基准 63，既不是 0 也不是 host=106）。
 * > 见 `desktopApp` 的 `TreeContextMenuUiTest` 里关于「为什么 UI 层不断言菜单位置」的说明。
 */

/**
 * 对象树右键菜单的**状态**。
 *
 * @param T 菜单的目标类型 —— 「这次右键作用在谁身上」。
 *   浏览模式是 `DatabaseBrowserState.TreeTarget`（库 / 表 / 字段），
 *   分析模式是数据源的 schema 名。两个模式的目标形状不同，
 *   但「记住是哪一个 + 记住弹在哪」的机制**完全一样**，所以做成泛型。
 */
@Stable
class TreeContextMenuState<T : Any> {
    /** 被右键的节点；`null` = 菜单关闭。 */
    var target: T? by mutableStateOf(null)
        private set

    /** 右键点击点，相对 **root**（窗口内容区）。 */
    var offsetInRoot: Offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * 菜单宿主（[TreeContextMenu] 的 `Popup` 所在的布局节点）相对 root 的原点。
     *
     * `Popup` 的 `offset` 是文档写明的「相对 **parent** 左上角」，而 parent 就是宿主；
     * [TreeContextMenu] 在弹出时用 `offsetInRoot − hostOrigin` 换算过去。
     * 由宿主持有者在布局时写入（`onGloballyPositioned` + `positionInRoot`）。
     */
    var hostOrigin: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 宿主布局变了就更新原点 —— 拖动分隔条改宽度时，菜单位置要跟着走。 */
    fun updateHostOrigin(at: Offset) {
        hostOrigin = at
    }

    /**
     * @param at 右键点击点，**相对本节点**（`onRightClick` 回调的原样输出）
     * @param nodeOriginInRoot 本节点相对 root 的原点（`onGloballyPositioned` + `positionInRoot`）
     * @param target 这次右键作用的目标
     */
    fun show(
        at: Offset,
        nodeOriginInRoot: Offset,
        target: T,
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
     * 而共享树组件（[ObjectTree]）自己知道节点在 root 里的位置 ——
     * 它已经在节点上挂了 `onGloballyPositioned`。
     * 让它再把坐标换算一遍、只把 root 坐标交出来，菜单这边就少一次
     * 「这里到底是局部还是全局」的判断 —— 而这正是本类最容易搞错的一处。
     */
    fun showAt(
        offsetInRoot: Offset,
        target: T,
    ) {
        this.offsetInRoot = offsetInRoot
        this.target = target
    }

    /** 关掉菜单。 */
    fun dismiss() {
        target = null
    }
}

/**
 * 树上右键菜单的**弹层** —— 定位、钳位、关闭全在这里。
 *
 * ## 为什么菜单挂在宿主这一层而不是每个节点内
 *
 * 见 [TreeContextMenuState] 的类 KDoc：生命周期、唯一性、坐标系，三条理由。
 *
 * @param menu 菜单状态
 * @param content 菜单项 —— 拿到的是**这次右键的目标**。
 *   用 [TreeContextMenuItem] 写菜单项，它会把「先关菜单再干活」这条规矩带上。
 */
@Composable
fun <T : Any> TreeContextMenu(
    menu: TreeContextMenuState<T>,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    // 内容按 target==null 整块组合：Popup 没有 `expanded`，可见性就是「在不在组合里」。
    val target = menu.target ?: return

    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    // 边界钳位：菜单可能比窗口还高（几个菜单项 + 贴底右键），
    // 不钳位的话下半截直接跑出屏幕，用户点不到最后一项。
    // ⚠️ 全程用 **px**：`offsetInRoot` 是指针事件的原始像素值，混进 Dp 会差一个 density。
    val windowSize = LocalWindowInfo.current.containerSize
    val minWidthPx = with(density) { MENU_MIN_WIDTH.toPx() }
    val menuHeightPx = with(density) { MENU_ESTIMATED_HEIGHT.toPx() }
    val clampedX =
        menu.offsetInRoot.x
            .coerceIn(0f, (windowSize.width - minWidthPx).coerceAtLeast(0f))
    val clampedY =
        menu.offsetInRoot.y
            .coerceIn(0f, (windowSize.height - menuHeightPx).coerceAtLeast(0f))

    Popup(
        // ⚠️ 为什么不用 `DropdownMenu` —— 见文件头 KDoc 的「为什么用 Popup 而不是 DropdownMenu」。
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
            content(target)
        }
    }
}

/**
 * 一个菜单项 —— **点了就先把菜单关掉**，再干活。
 *
 * ## 为什么「先关菜单」是菜单项自己的事
 *
 * 菜单不会因为「菜单项被点了」而自动关闭：它只认 dismiss 路径
 * （点在菜单外 / 按 Esc / 焦点丢失），菜单项的 `onClick` 是另一条路。
 *
 * 第一版漏了 dismiss，真窗口上表现为：复制成功了、通知也弹了，
 * 但**那层菜单还杵在树上不消失**，用户接着右键别处时新菜单被压在旧 Popup 底下，
 * 于是「右键没反应了」。
 *
 * 更要紧的是**顺序**：先关再干活。复制 DDL 要发引擎往返（可能几十毫秒），
 * 菜单杵在这段时间里既挡视线又挡下一次右键。
 *
 * 把这条规矩写在这里而不是让每个调用点自己记得 —— 规矩写在**唯一一处**，
 * 两棵树（浏览 / 分析）才不会有一棵忘了。
 *
 * @param menu 菜单状态（用来关掉自己）
 * @param tag 菜单项的 UI 测试 tag
 * @param label 菜单项文案
 * @param action 点了之后要做的事
 */
@Composable
fun <T : Any> TreeContextMenuItem(
    menu: TreeContextMenuState<T>,
    tag: String,
    label: String,
    action: () -> Unit,
) {
    WinMenuItem(
        modifier = Modifier.testTag(tag),
        text = { Text(label) },
        onClick = {
            menu.dismiss()
            action()
        },
    )
}

/** 菜单最小宽度 —— 仅用于「贴到右边缘时往左收」的钳位计算。 */
private val MENU_MIN_WIDTH: Dp = 160.dp

/**
 * 菜单高度的**估算值**，只用于贴底时的钳位。
 *
 * 为什么只能是估算：真实高度要等布局完成才知道，而钳位发生在布局之前。
 * 偏大只是让贴底时往上多收一点（菜单不贴边），偏小才会截掉菜单项 ——
 * 所以宁可偏大：浏览模式 6 项菜单 ≈ 300dp，估 320dp。
 */
private val MENU_ESTIMATED_HEIGHT: Dp = 320.dp
