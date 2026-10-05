package com.kxxnzstdsw.sundays.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.editor.CompletionItem

/**
 * 弹层最高高度 —— 超过就内部滚动，别把半屏编辑器盖住。
 *
 * 定 220dp ≈ 7 行：既够放下 [DEFAULT_COMPLETION_LIMIT] 条，也留得住输入区的视线。
 */
private val MAX_VISIBLE_HEIGHT: Dp = 220.dp

/**
 * 把内容**定位**到父级内的指定像素坐标，且**不参与父级尺寸计算**。
 *
 * 为什么需要它：补全弹层所在父级是 `verticalScroll` 里的一个 `Box`。若弹层把自身高度
 * 计入父级测量，编辑器内容高度会被凭空撑大，滚动条跟着变长 —— 弹层一开，编辑区就跳一下。
 * 所以这里向外报告 0×0，把内容 `place` 到指定坐标。
 *
 * Compose 允许子节点被 place 到父级边界之外，越界部分由父级裁剪 —— 正是浮层该有的行为。
 */
internal fun Modifier.atCaret(x: Int, y: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        // 解除最小尺寸约束：否则父级要求「至少这么宽/高」时，弹层会被强行撑大
        constraints.copy(minWidth = 0, minHeight = 0),
    )
    layout(constraints.minWidth, constraints.minHeight) {
        placeable.place(x, y)
    }
}

/**
 * 补全候选弹层 —— 编辑器「提示」功能的呈现部分。
 *
 * ## 定位策略：贴光标
 *
 * 由 [CodeEditor] 通过 [atCaret] 定位到光标正下方（`x` = 光标左边界、`y` = 光标底边）。
 * **弹层放在滚动容器内部**，因此它天然跟着代码一起滚 —— 不用手算滚动偏移，
 * 也就不会出现「代码滚了、弹层没滚」的错位。
 *
 * @param items 候选列表（已按优先级排好序，见 `buildCompletionPool`）
 * @param selectedIndex 当前高亮的下标；状态由 [CodeEditor] 持有，上下键切换
 * @param onAccept 接受某条（鼠标点击，或 Tab / Enter）
 * @param maxWidth 弹层最大宽度 —— 窄编辑器里顶满整行会很难看
 * @param modifier 定位修饰符，由调用方给出
 */
@Composable
fun CompletionPopup(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onAccept: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(maxWidth)
            .heightIn(max = MAX_VISIBLE_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            // 描边：弹层浮在代码上，没有边界会和代码糊在一起
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(6.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) colors.primary.copy(alpha = 0.16f) else colors.surface)
                    .clickable { onAccept(item) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) colors.primary else colors.onSurface,
                )
                // 类别徽标 —— 让用户一眼分清「关键字」与「函数」，
                // 否则两坨词混排很难快速扫读
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.kind.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
