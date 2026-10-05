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
import androidx.compose.foundation.layout.widthIn
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

/** 弹层宽度占编辑器宽度的比例 —— 剩下的留给代码本身，别把正在读的那行盖光。 */
private const val POPUP_WIDTH_RATIO = 0.70f

/** 弹层宽度下限：编辑器被压得极窄时，再窄就一行只放得下一个字符。 */
private val MIN_POPUP_WIDTH: Dp = 160.dp

/** 弹层宽度上限：再宽一行也读不完，宽出来的部分全是空白。 */
private val MAX_POPUP_WIDTH: Dp = 460.dp

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
 * 补全弹层的宽度上限 —— 编辑器宽度的 70%，再夹在 160dp~460dp 之间。
 *
 * 两个端点各有各的理由：
 * - 下限 160dp：编辑器被压得极窄时，弹层太窄会一行只放得下一个字符。
 * - 上限 460dp：再宽一行也读不完，宽出来的部分全是空白，还得遮住更多代码。
 *
 * 这是**上限**而非实际宽度 —— 弹层按内容自适应（见 [CompletionPopup] 里的 `widthIn`），
 * 所以两条短候选只会撑到两条短候选那么宽。
 */
internal fun completionPopupMaxWidth(editorWidth: Dp): Dp =
    (editorWidth * POPUP_WIDTH_RATIO).coerceIn(MIN_POPUP_WIDTH, MAX_POPUP_WIDTH)

/**
 * 补全弹层的横向落点 —— 靠右时向左翻转。
 *
 * ## 为什么需要翻转
 *
 * 弹层位于**滚动容器内部**，越界部分会被裁掉。光标靠在行尾时按「弹层左边缘 = 光标」摆，
 * 右半截就没了 —— 而且弹层一开就挡住光标右边那片代码，正是用户正在读的位置。
 *
 * ## 翻转 vs 钳位：为什么选翻转
 *
 * 也可以把弹层**往左钳**进可视区（`x = min(caretX, editorWidth - w)`）。那样弹层右边缘离光标
 * 可能隔着很远一段，点候选时鼠标要横跨这段距离去点，而且弹层不再贴着光标，视觉上「不知道它属于谁」。
 * 翻转则让弹层大致与光标右对齐 —— 无论往哪边展开，弹层的「锚点」都在光标上。
 *
 * ## 已知的近似
 *
 * 弹层实际宽度是**布局后**才知道的（内容自适应），而这里在布局前只能拿上限估算。
 * 所以弹层很窄时，翻转后会略微偏右一点。精确解法要用 `onSizeChanged` 拿实测宽度二次定位，
 * 代价是多一帧闪烁（弹层先出现、再跳一下）—— 对一个补全列表来说不值当。
 *
 * @param caretX 光标左边界（px）
 * @param editorWidth 编辑器可用宽度（px）
 * @param popupMaxWidthPx 弹层宽度上限（px），用于放置前估算
 * @return 弹层左边缘坐标（px），已保证非负
 */
internal fun completionPopupX(caretX: Int, editorWidth: Int, popupMaxWidthPx: Int): Int {
    val flipped = caretX + popupMaxWidthPx > editorWidth
    return if (flipped) (caretX - popupMaxWidthPx).coerceAtLeast(0) else caretX
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
 * @param maxWidth 弹层最大宽度上限 —— 实际宽度还会被 `minIntrinsicWidth` 抬高，
 *   因此**短候选**的弹层不会浪费一整行屏幕
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
            // **宽度按内容自适应**，而不是写死一个 `maxWidth`：
            // 写死的话，2 条短候选（`ID`、`NAME`）也会占掉 320dp，
            // 把下面好几行代码全遮住。这里让内容决定宽度，只用 maxWidth 封顶。
            // `widthIn(max=)` 而非 `width(max=)` —— 后者会把短内容**拉伸**到上限。
            .widthIn(min = 120.dp, max = maxWidth)
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
                // 签名（`detail`）—— 光有函数名不够：`random_int` 收什么参数、
                // 返回什么，只有签名能说明。造数沙箱那批函数尤其需要。
                if (!item.detail.isNullOrBlank()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
