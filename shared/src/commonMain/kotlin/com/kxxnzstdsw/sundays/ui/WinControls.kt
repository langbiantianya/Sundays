package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 复古控件包装层 —— 让 Win2000 / WinXP 两套主题的按钮与输入框长得像当年的控件。
 *
 * ## 为什么需要这一层
 *
 * 光换配色与圆角**不足以**让复古主题成立，有两处 M3 默认值必须一并改掉：
 *
 * 1. **填充色**。M3 的实心按钮用 `colorScheme.primary` 作容器色。Win2000 的 `primary` 是
 *    navy `#000080`（那是它的**选区色**），于是按钮会变成深蓝底白字 —— 而真实的 Win2000
 *    按钮是 `ButtonFace` 银灰 `#C0C0C0` 配黑字。WinXP 同理：Luna 按钮是奶油面配深色字，
 *    不是高亮蓝。所以复古档必须把容器色换成窗口面（`background`）配 `onBackground`。
 * 2. **边框**。M3 自带的单色描边会与 [winBevel] 的双色斜面叠在一起变成「双层边」，
 *    因此复古档要把 M3 的描边设为透明，只留斜面。
 *
 * ## 现代主题下完全透传
 *
 * `LocalBevelStyle.current.enabled` 为 `false` 时（蓝灰 / 赛博朋克 / 哔哩粉），每个包装函数
 * 都直接转调同名 M3 组件且不附加任何 Modifier —— 现代三套的渲染路径与改动前逐像素一致。
 *
 * ## 已知简化
 *
 * - **默认按钮的粗黑边框**没做。Win2000 的「默认按钮」外面会多一圈 1px 黑框表示回车默认项，
 *   M3 没有「哪个按钮是默认的」这一信息，包装层无从判断。
 * - **按下凹陷态**没做（仅切换态做了）。真实经典 Win 按钮按住时凸起↔凹陷互换；M3 的按钮
 *   按下只改容器色，要跟随翻转斜面需要自行接管 `interactionSource` 的 pressed 状态。
 *   工具栏的**切换**按钮不受此限 —— 它有显式的选中态，故用 [WinButton] 的 [selected] 表达。
 *
 * 本文件所有外观取值都来自 [LocalUiTokens] 的派生函数（[buttonFaceColor] /
 * [buttonInkColor] / [fieldFaceColor] / [disabledInkOrNull] / [panelBorderStroke]），
 * **不含任何主题名判断** —— 新增主题只需在 [ThemePalette.uiTokens] 加一个分支。
 */

/**
 * 解析控件形状 —— **所有包装函数共用的唯一入口**。
 *
 * ## 为什么要区分「有没有显式传 shape」
 *
 * 改造前 37 个按钮 / 输入框调用点中有 **19 个没有显式传 `shape`**，它们拿到的是各自
 * M3 组件由 token 决定的默认形状。若把 `WinButton` 的默认参数直接写成
 * `SundaysPalette.buttonShape`（4dp 圆角），这些点会在**现代主题下也被悄悄改掉形状** ——
 * 而 hover / press 的状态层是跟着形状轮廓走的，于是「M3 按钮的 hover 样式变了」这类问题
 * 会以完全看不出根因的形式出现。
 *
 * ## 为什么 [modernDefault] 必须由调用方传
 *
 * 各组件的 M3 默认形状**互不相同**，统一兜底成 `ButtonDefaults.shape` 就会串味：
 *
 * | 组件 | M3 默认形状 | 实际形状 |
 * |---|---|---|
 * | `Button` / `OutlinedButton` / `TextButton` | `ButtonSmallTokens.ContainerShapeRound` | **胶囊**（全圆角） |
 * | `OutlinedTextField` | `FilledTextFieldTokens.ContainerShape` = `CornerExtraSmallTop` | `shapes.extraSmall.top()` —— **只有上方两角 2dp，左 / 右 / 下是方角** |
 *
 * 曾一度对输入框也用 `ButtonDefaults.shape` 兜底，结果 9 个输入框的左右边全变成了圆弧。
 * [resolveTextFieldShape] 是正确的那个。
 *
 * 规则：**包装层只在经典主题下改变形状**；现代主题必须逐像素保持改造前的行为。
 *
 * @param modernDefault 该组件在 M3 里的默认形状（由各包装函数按自己的组件传入）
 */
@Composable
fun resolveControlShape(shape: Shape?, modernDefault: Shape): Shape =
    shape ?: if (isClassicChrome) controlShape() else modernDefault

/**
 * 只保留上方两角、下方两角置方 —— 复现 M3 `internal fun CornerBasedShape.top()`。
 *
 * M3 的 `Shapes.fromToken` 与 `CornerBasedShape.top()` 都是 **internal**，外部模块调不到，
 * 而输入框的默认形状恰恰是这个「上圆下方」的形状。故显式复现：把下方两角置 0。
 *
 * [MaterialTheme.shapes] 的各档都是 [CornerBasedShape]，因此 `copy` 可用。
 */
private fun Shape.squaredBottom(): Shape {
    // `MaterialTheme.shapes.extraSmall` 的静态类型是 Shape 而非 CornerBasedShape，
    // 故做一次运行时判定；非 CornerBasedShape（如 CircleShape）原样返回。
    if (this !is androidx.compose.foundation.shape.CornerBasedShape) return this
    return copy(
        bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
        bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
    )
}

/** 三个按钮的形状解析 —— M3 默认是胶囊。 */
@Composable
fun resolveButtonShape(shape: Shape?): Shape = resolveControlShape(shape, ButtonDefaults.shape)

/**
 * 输入框的形状解析 —— M3 默认是 `shapes.extraSmall.top()`，**不是**按钮的胶囊。
 *
 * `FilledTextFieldTokens.ContainerShape` 对应 `ShapeKeyTokens.CornerExtraSmallTop`，而
 * `Shapes.fromToken` 是 `internal`，外部调不到，故在此显式复现该映射（`extraSmall.top()`
 *）。若 M3 将来改了 token，这里需要同步 —— 见 `ModernThemeParityTest` 的断言。
 */
@Composable
fun resolveTextFieldShape(shape: Shape?): Shape =
    resolveControlShape(shape, MaterialTheme.shapes.extraSmall.squaredBottom())

/**
 * 空操作指示器占位（经典档不再使用）。
 *
 * 曾尝试用 `LocalIndication provides …` 关掉状态层，但 M3 的 `Surface(onClick)` 把
 * `indication = ripple()` **硬编码**在实现里、不读 `LocalIndication`，那条路无效。
 * 故经典档改为不走 `Surface`，直接用 [androidx.compose.foundation.clickable] 且
 * `indication = null`（该参数本身可空）。
 */
private object NoIndication

/**
 * 经典档的按钮基座 —— 窗口面 + 3D 斜边，**没有投影、没有悬停高光**。
 *
 * ## 为什么不直接用 M3 `Button` 再调参
 *
 * M3 的 `Button` 内部是 `Surface(onClick=…)`，而 `Surface` 把 `indication = ripple()`
 * 写死在实现里，`Button` 也不暴露 `indication` / `interactionSource` 形参 —— 想在
 * 保留 M3 按钮的前提下关掉悬停高光是做不到的。经典控件的交互反馈本就应该由那圈 3D
 * 斜边表达（真实 Win 按钮鼠标移上去纹丝不动），所以这里自绘基座。
 *
 * ## 尺寸必须与 M3 按钮一致
 *
 * 尺寸直接取 [ButtonDefaults.MinWidth] / [ButtonDefaults.MinHeight] / [contentPaddingFor]，
 * **不自己拍数字** —— 否则经典档的按钮会比现代档大一号，而这类差异在混排时非常刺眼
 * （此前就因为默认参数吃掉了调用方的形状，导致现代档按钮集体变形）。
 */
@Composable
private fun ClassicButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    raised: Boolean,
    shape: Shape,
    colors: ButtonColors,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = androidx.compose.material3.ButtonDefaults.MinWidth,
                minHeight = androidx.compose.material3.ButtonDefaults.MinHeight,
            )
            .clip(shape)
            .background(colors.containerColor)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        // 必须显式给 role：现代档转调 M3 Button，自带 role = Role.Button；
                        // 经典档是自绘基座，裸 clickable 的 role 默认为 null，读屏只会念
                        // 「可点击」而丢掉「按钮」—— 同一个 WinButton 的语义随主题漂移。
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            // 斜面画在最后：它是「内容之后」的一层，压在容器色之上
            .uiBevel(raised = raised)
            .padding(androidx.compose.material3.ButtonDefaults.contentPaddingFor(androidx.compose.material3.ButtonDefaults.MinHeight)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides (if (enabled) colors.contentColor else colors.disabledContentColor),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

/** 经典档的按钮配色：窗口面 + 前景色。容器色 / 文字色 / 禁用色全部由 token 派生。 */
@Composable
private fun classicButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = buttonFaceColor(),
        contentColor = buttonInkColor(),
        disabledContainerColor = buttonFaceColor(),
        // 禁用的控件不受 WCAG 1.4.3 约束（该条明确排除 inactive 组件）
        disabledContentColor = disabledInkOrNull() ?: MaterialTheme.colorScheme.outlineVariant,
    )

/**
 * 复古档的按钮 —— 窗口面 + 3D 凸起斜面。
 *
 * 形状默认取 [SundaysPalette.buttonShape]（随主题变化：Win2000 直角 / WinXP 3dp 圆角）。
 * 调用方若显式传 `shape` 则以传入值为准，斜面圆角仍取主题的 [BevelStyle.bevelRadius]。
 *
 * @param selected 切换态。经典 Win 的工具栏切换按钮选中时不是换个填充色，而是**斜面翻转为
 *   凹陷**（左上暗、右下亮）——这正是 [winBevel] 的 `raised = false`。现代主题下该参数
 *   被忽略，调用方应改用 [colors] 表达选中态。
 * @param colors 仅现代主题生效；复古档一律用窗口面配色（见 [classicButtonColors]）。
 */
@Composable
fun WinButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isClassicChrome) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = resolveButtonShape(shape),
            colors = colors ?: ButtonDefaults.buttonColors(),
            content = content,
        )
        return
    }
    ClassicButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        raised = !selected,
        shape = resolveButtonShape(shape),
        colors = classicButtonColors(),
        content = content,
    )
}

/** [WinButton] 的描边变体 —— 复古档下与 [WinButton] 表现一致（M3 描边被斜面取代）。 */
@Composable
fun WinOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isClassicChrome) {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = resolveButtonShape(shape), content = content)
        return
    }
    // 同 [WinButton]：经典档去掉投影与悬停状态层，立体感只由斜边表达
    ClassicButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        raised = true,
        shape = resolveButtonShape(shape),
        colors = classicButtonColors(),
        content = content,
    )
}

/** [WinButton] 的无背景变体 —— 复古档下同样画凸起斜面（经典 Win 的「文本按钮」也带边框）。 */
@Composable
fun WinTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isClassicChrome) {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = resolveButtonShape(shape), content = content)
        return
    }
    ClassicButtonBase(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        raised = true,
        shape = resolveButtonShape(shape),
        colors = classicButtonColors(),
        content = content,
    )
}

/**
 * 复古档的输入框 —— **凹陷**斜面（亮暗与按钮相反）。
 *
 * 经典 Win 的文本框是「陷进去」的：左上暗、右下亮。斜面画出来后 M3 自己的描边必须透明，
 * 否则会与斜面叠成两条边。
 */
@Composable
fun WinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    if (!isClassicChrome) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            shape = resolveTextFieldShape(shape),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.uiBevel(raised = false),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        // 输入框的形状解析与按钮不同（见 resolveTextFieldShape 的说明）
        shape = resolveTextFieldShape(shape),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            errorBorderColor = Color.Transparent,
            // ⚠️ 刻意用 surfaceVariant 而非 surface：Win2000 浅色档的 surface 是纯白
            // `#FFFFFF`，而经典 Win 的凹陷边有一侧就是 ButtonHighlight 纯白 ——
            // 贴在纯白面上那一侧会**完全隐形**，凹陷效果只剩一半。这里退回
            // 「Windows Standard」的 `#D4D0C8` 灰面，亮边才读得出来。
            // WinChromeTest 的「斜面必须比它所在的控件面更亮/更暗」正是钉这一条。
            focusedContainerColor = fieldFaceColor(),
            unfocusedContainerColor = fieldFaceColor(),
            disabledContainerColor = fieldFaceColor(),
        ),
    )
}

// ============================================================================
// 面板 / 图标按钮 / 单选 —— 同一套 token 的其余消费者
// ============================================================================

/**
 * 通用面板容器 —— 经典档给 3D 凸起斜面并关掉单色描边，现代档给 1dp 细线。
 *
 * 用途是把散落在各屏的 `Surface` / `Card` 收敛到一处：目前界面上有 20 多处容器，
 * 逐个加 modifier 容易漏，而漏掉一个就会在复古主题里留下一块没有立体感的"贴纸"。
 *
 * @param color 容器底色；`null` 取主题的 `surface`
 * @param onClick 非空则容器可点击（做成面板标题那种整块可点的区域）
 */
@Composable
fun WinSurface(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = winShape(6.dp)
    val container = if (color == Color.Unspecified) scheme.surface else color
    val border = panelBorderStroke()
    if (onClick != null) {
        androidx.compose.material3.Surface(
            onClick = onClick,
            modifier = modifier.uiBevel(raised = true),
            shape = shape,
            color = container,
            border = border,
            content = content,
        )
        return
    }
    androidx.compose.material3.Surface(
        modifier = modifier.uiBevel(raised = true),
        shape = shape,
        color = container,
        border = border,
        content = content,
    )
}

/**
 * 图标按钮 —— 经典 Win 的工具栏图标按钮同样是有边框的凸起方块，不是现代的裸图标。
 *
 * 经典档给窗口面 + 凸起斜面；现代档完全透传给 M3 的 [IconButton]（不加任何 modifier）。
 */
@Composable
fun WinIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!isClassicChrome) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.uiBevel(raised = true),
        enabled = enabled,
        shape = controlShape(),
        color = buttonFaceColor(),
        contentColor = buttonInkColor(),
    ) {
        Box(modifier = Modifier.padding(6.dp)) { content() }
    }
}

/**
 * 单选 / 复选按钮的着色 —— 经典档用 `primary` 画圆点与方框（经典 Win 的选中标记就是
 * 主色实心），现代档透传 M3 默认。
 *
 * 圆点 / 方框本身的 3D 斜面属于 M3 内部绘制，无法注入 token，故这里只管颜色。
 */
@Composable
fun selectionIndicatorColors(): androidx.compose.material3.RadioButtonColors =
    androidx.compose.material3.RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * 开关的着色 —— 经典档用 `primary` 画轨道（经典 Win 的选中态就是主色实心）、滑块取
 * `onPrimary` 压出「钮上的字」那层反白；现代档透传 M3 默认。
 *
 * 与 [selectionIndicatorColors] 同理：滑块 / 轨道的 3D 斜面属于 M3 内部绘制，无法注入
 * token，故这里只管颜色。
 */
@Composable
fun selectionSwitchColors(): androidx.compose.material3.SwitchColors =
    androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surface,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    )

/**
 * 开关 —— 设置页的布尔项（当前是「紧凑模式」）用它。
 *
 * 与 [WinButton] 等包装层不同，这里**现代档也要接管颜色**（[selectionSwitchColors]），
 * 而不是透传 M3 默认：M3 出厂开关是「紫灰轨道 + 白色滑块」的触控观感，与本项目低饱和靛蓝
 * 的桌面观感不搭。经典档的立体斜面则确实做不出来 —— M3 把滑块 / 轨道的绘制写死在内部，
 * 与 [selectionIndicatorColors] 面临同一限制，记在本文件「已知简化」里。
 *
 * 尺寸随 [LocalDensity] 自动缩放，故紧凑档下轨道从 52dp 收到 44.2dp，无需额外处理。
 */
@Composable
fun WinSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = selectionSwitchColors(),
    )
}

// ============================================================================
// 菜单 / 进度指示 —— 经典档的另外两处控件
// ============================================================================

/**
 * 下拉菜单项 —— 经典 Win 的弹出菜单是**凹陷**边框的灰底方块，且分隔符是蚀刻线。
 *
 * 实际效果来自给菜单容器加边框（见 [WinMenuContainer]）与分隔线（见 [WinDivider]）；
 * 这里额外把选中/悬停色交给 token，避免菜单里出现现代的淡紫圆角高亮。
 */
@Composable
fun WinMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: androidx.compose.material3.MenuItemColors = androidx.compose.material3.MenuDefaults.itemColors(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
    )
}

/** 菜单容器的形状 —— 经典档直角（经典 Win 菜单是方角），现代档沿用 M3 圆角菜单。 */
@Composable
fun menuShape(): Shape =
    if (isClassicChrome) RoundedCornerShape(LocalBevelStyle.current.bevelRadius)
    else androidx.compose.material3.MenuDefaults.shape

/**
 * 进度指示器 —— 经典 Win 的进度条是**分段块状**（XP 的蓝色方块条）而非现代的细圆环。
 *
 * 这里只换颜色与粗细：形状上的分段感需要自绘 `Canvas`，而进度指示器在界面里
 * 是「连接中」这类短暂状态（7 处），投入产出不划算。经典档用主色实心 + 加粗，
 * 已经比现代的细环更接近当年观感。
 */
@Composable
fun WinProgressIndicator(modifier: Modifier = Modifier, strokeWidth: androidx.compose.ui.unit.Dp = 4.dp) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = if (isClassicChrome) strokeWidth + 2.dp else strokeWidth,
    )
}