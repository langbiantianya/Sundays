package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 给「自带焦点能力、但默认不画焦点指示」的容器补一圈**可见焦点环**。
 *
 * ## 为什么必须补
 *
 * `Modifier.clickable` 在非触摸模式下会加入 Tab 序（`focusableInNonTouchMode`）——
 * 也就是说这些控件**本来就是键盘可达的**；但它不画任何焦点指示。
 * 于是：焦点确实在控件之间移动，界面看上去却毫无变化，
 * 键盘用户（以及真窗口 GUI 走查）完全不知道焦点在哪。
 *
 * 真窗口走查时这就是「数焦点」数到怀疑人生的根源：`Tab` 按了 30 次，
 * 截图里有好几格看不出落在哪，只能逐格猜；猜错就误触别的功能
 * （实测误开过「添加数据库连接」弹窗、误切过 Lua 版本档位）。
 *
 * ## 用法与位置
 *
 * ```kotlin
 * Row(Modifier.fillMaxWidth().focusRing().clickable { … })
 * ```
 *
 * **放在 `clickable` 之前**：焦点环靠 `padding` 让出宽度、画在容器**外侧**，
 * 于是既不遮住边框与文字，又能和「选中态」的背景/边框区分开 ——
 * 这两者在树里长得几乎一样（都是一圈高亮），不分开会把「焦点」误判成「选中了」。
 *
 * @param color 焦点环颜色；`null`（默认）= 取 `MaterialTheme.colorScheme.primary`。
 *   做成可空参数而不是直接给 `MaterialTheme.colorScheme.primary` 默认值，
 *   是因为 @Composable 的默认值求值位置容易踩到编译限制，放进函数体里最稳。
 * @param width 环宽，同时是内缩的 padding 宽度（环画在外侧）。
 */
@Composable
fun Modifier.focusRing(
    color: Color? = null,
    width: Dp = 2.dp,
): Modifier = composed {
    val ringColor = color ?: MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    this
        .padding(width)
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused) drawRect(color = ringColor, style = Stroke(width = width.toPx()))
        }
}
