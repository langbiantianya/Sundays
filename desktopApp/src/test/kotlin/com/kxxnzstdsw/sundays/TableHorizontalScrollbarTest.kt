package com.kxxnzstdsw.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.TABLE_BODY_TAG
import com.kxxnzstdsw.sundays.table.TABLE_HEADER_TAG
import com.kxxnzstdsw.sundays.table.TABLE_HSCROLLBAR_TAG
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归：**宽度溢出后要有「能看见、也能拖着走」的横向滚动条**。
 *
 * ## 没有它会怎样（真库走查）
 *
 * PG `examquestions.biz_user` 有 **23 个字段**。`contentWidthFor` 给加权列 100dp 下限，
 * 于是内容宽 2346dp 而视口只有约 640dp —— 右边 18 列**滚出屏幕外**。
 *
 * 而 `Modifier.horizontalScroll` 在桌面端**默认只响应触控板横扫 / Shift+滚轮**，
 * 界面上的滚动条是 `Modifier.android` 默认样式，在浅色主题下几乎看不见。
 * 结果是：**普通鼠标用户没有任何提示表明右边还有列**，只能靠 Shift+滚轮这种
 * 「没人知道它存在」的操作去翻。
 *
 * > ⚠️ 这一条是**真窗口测不出来**的：合成的 `mouse_event` 滚轮事件根本进不了
 * > Skiko 窗口（本项目 GUI 方法论表里记着「合成输入送不进 Skiko」，点击能进、
 * > 滚轮进不去），所以「横向滚轮在真窗口里能不能用」**至今没有真值**。
 * > 本类量的不是那个，而是**产品契约**：滚动条在、铺满、且拖得动。
 * > 前两者真窗口可验（截图里肉眼可见），第三个只能在这里验。
 *
 * ## 为什么要 tag
 *
 * 滚动条的拇指是一条光秃秃的 `Box`，既没有文本也没有自己的语义节点，
 * `onNodeWithTag(TABLE_HSCROLLBAR_TAG)` 量的是**轨道**——而轨道铺满整个表格宽度
 * 正是要断言的东西（「它横跨整张表」而不是「只有表格中间一小段」）。
 */
@OptIn(ExperimentalTestApi::class)
class TableHorizontalScrollbarTest {

    private fun weightedColumns(count: Int): List<TableColumn> =
        (1..count).map { TableColumn(key = "c$it", header = "column_name_$it") }

    private fun rows(cols: Int): List<TableRow> = (1..3).map { r ->
        TableRow(id = r, cells = (1..cols).associate { i -> "c$i" to "value-$r-$i" })
    }

    private fun ComposeUiTest.render(colCount: Int, dark: Boolean = false) {
        setContent {
            // ⚠️ 必须显式传 `darkTheme`：默认取 `isSystemInDarkTheme()`，而测试宿主上
            // 它返回**深色**。不显式指定的话「浅色档」那条用例其实一直在跑深色，
            // 两档量出完全一样的数 —— 看着两条都覆盖了，其实只覆盖了一档。
            SundaysTheme(darkTheme = dark) {
                DataTable(
                    columns = weightedColumns(colCount),
                    rows = rows(colCount),
                    showDetailPanel = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
    }

    /** WCAG 相对亮度。 */
    private fun relativeLuminance(argb: Int): Double {
        fun lin(channel: Int): Double {
            val s = channel / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度 = (亮 + 0.05) / (暗 + 0.05)。 */
    private fun contrastRatio(fg: Int, bg: Int): Double {
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /**
     * 量出「轨道中线那一行」上，从左端起、颜色明显不同于背景的连续色块 —— 那就是拇指。
     *
     * 取色方式：截取滚动条的**中线那一行**，左右各取 10% 宽度，用**众数**当色。
     *
     * ## 为什么不用「单点取样 + 与右端比对」
     *
     * 初版就是这么写的，量出来的拇指是 `#232833` —— 而 `#232833` 恰恰是**轨道底色**，
     * 也就是说它把轨道当成了拇指，还顺手给出一个 1.29:1 的假低分。两个错叠在一起：
     *
     * 1. 基准取 `x = w-1`。那一列不是轨道，是表格**外框的 1px 竖线**（`borderColor`
     *    `#333A48`）。跟它比，比的是「轨道 vs 外框」，不是「拇指 vs 轨道」。
     * 2. 扫描边界用同一条基准，于是**轨道本身**也被算进了「拇指色块」，
     *    色块一路延伸到右端，取中点自然落在轨道上。
     *
     * 改成众数取样后两个错一起消失：左 10% 是纯拇指（只剩圆角两三个像素是过渡色），
     * 右 10% 是纯轨道（只剩那 1px 外框），两边都稳。
     *
     * 背景色**量出来**而不是写死常量：写死等于把「滚动条背后是什么」这个前提
     * 抄进断言里，一旦布局改了一处就会得到一个与画面无关的比值。
     * 这里要的恰恰是「拇指相对它实际所处的背景够不够亮」。
     *
     * @return 拇指色与背景色
     */
    private fun ComposeUiTest.measureThumbAgainstBackground(): Pair<Int, Int> {
        val pixels = onNodeWithTag(TABLE_HSCROLLBAR_TAG).captureToImage().toPixelMap()
        val w = pixels.width
        val h = pixels.height
        require(w >= 40 && h >= 4) {
            "横向滚动条的像素区域异常：${w}x$h —— 量不到拇指与轨道就无从谈对比度"
        }

        // 中线那一行：上下对称，避开上下端的圆角
        val midY = h / 2
        val sampleWidth = maxOf(8, w / 10)

        /** 取 [from, to) 区间的众数色。 */
        fun modeOf(from: Int, to: Int): Int {
            val counts = HashMap<Int, Int>()
            for (x in from until to) {
                val c = pixels.get(x, midY).toArgb()
                counts[c] = (counts[c] ?: 0) + 1
            }
            return counts.maxBy { it.value }.key
        }

        // 横向滚动初始停在最左：拇指贴左端，轨道空在右端
        return modeOf(0, sampleWidth) to modeOf(w - sampleWidth, w)
    }

    /**
     * **拇指必须看得见** —— 断言的是真实渲染像素的 WCAG 对比度，不是「节点存在」。
     *
     * ## 为什么「节点存在」不够
     *
     * 本类前两条断言证明滚动条**被画出来了**、铺满、能拖。但「画出来了」与
     * 「看得见」是两件事：初版把拇指取成 `theme.headerBackground`（深色档 `#2C3240`），
     * 真窗口截图里它确实是 `#2C3240`、确实存在 —— 而它背后的轨道底色是行底
     * `#232833`，每通道只差 9/10/17，对比度 **1.15 : 1**。
     *
     * 1.15:1 在屏幕上是这样一条**几乎分辨不出**的细线。而这个缺陷的全部要害
     * 是「看不出右边还有列」，所以那种取值等于没修 —— 断言全绿、缺陷仍在。
     *
     * 这也是本条测试存在的唯一理由：**把「看得见」变成一个可以回归的数字**，
     * 以后谁再把拇指换回某个表面色，本条立刻变红，而不是靠人眼在真窗口里
     * 眯着眼猜「刚才那条线是不是变深了」。
     *
     * ## 阈值取 3:1
     *
     * WCAG 2.1 §1.4.11 对非文本 UI 部件（图形对象、控件外观）的下限就是 3:1。
     * 这里取同一道线，不另立标准。
     *
     * ## 明暗两档都要跑
     *
     * 拇指色取自 `cellText.color`，它在现代档是写死常量、在复古档取 `onSurface`，
     * 两者的对比度是**各自算出来的**，不相等。所以两条用例分别断言，不能只测一个档。
     */
    @Test
    fun `浅色档下拇指与轨道底色的对比度达标`() = assertThumbContrast(false)

    @Test
    fun `深色档下拇指与轨道底色的对比度达标`() = assertThumbContrast(true)

    private fun assertThumbContrast(dark: Boolean) = runComposeUiTest {
        render(colCount = 23, dark = dark)

        val (thumb, background) = measureThumbAgainstBackground()
        val thumbHex = "#%06X".format(thumb and 0xFFFFFF)
        val bgHex = "#%06X".format(background and 0xFFFFFF)
        assertTrue(
            thumb != background,
            "拇指色与轨道底色完全相同（$thumbHex）—— 拇指等于没画，" +
                "用户完全看不出右边还有列",
        )

        val ratio = contrastRatio(thumb, background)
        // ⚠️ 用模板插值而不是 `+ ".format(...)"`：`.` 的优先级高于 `+`，
        // 那样写 `format` 只会绑定到**最后一段**字面量，前一段的 `%06X` 会吃到
        // Double 直接抛 IllegalFormatConversionException —— 而且只在断言**失败**
        // 时才抛，测试因此以一个和被测性质无关的异常变红，把真正的原因盖掉。
        assertTrue(
            ratio >= 3.0,
            "滚动条拇指与轨道底色的对比度只有 ${"%.2f".format(ratio)}:1（下限 3:1）—— " +
                "拇指=$thumbHex，底色=$bgHex。低对比度的滚动条等于没有：用户依旧看不出右边还有列。",
        )
    }

    private fun ComposeUiTest.hScrollValue(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            ?.value
            ?.invoke()
            ?: error("节点 $tag 没有横向滚动轴")

    /** 横向滚动条**存在**，且横跨整个表格宽度。 */
    @Test
    fun `横向滚动条存在且铺满表格宽度`() = runComposeUiTest {
        render(colCount = 23)

        val bar = onNodeWithTag(TABLE_HSCROLLBAR_TAG).fetchSemanticsNode()
        val table = onNodeWithTag(TABLE_BODY_TAG).fetchSemanticsNode()

        assertTrue(bar.size.width > 0f, "横向滚动条宽度为 0 —— 它根本没画出来")
        assertEquals(
            table.boundsInRoot.width, bar.boundsInRoot.width, 2f,
            "滚动条应横跨整张表（表宽 ${table.boundsInRoot.width}，滚动条 ${bar.boundsInRoot.width}）—— " +
                "只盖住中间一小段就等于告诉用户「溢出区在别处」",
        )
    }

    /**
     * 滚动条**不是装饰**：拖着它要真的改变横向偏移，且表头与表体同步。
     *
     * 从轨道**最左端**起拖 —— 初始 `value = 0`，拇指就贴在左边，
     * 从那儿起手才落在拇指上（从中间起手落在空白轨道上，按的是分页而不是拖动）。
     *
     * ⚠️ `performMouseInput` 里的坐标是**节点局部**的（相对于该节点自身的边界），
     * 不是根坐标。传根坐标会被再加一次节点原点，直接算出 `y=1394` 这种落在
     * 根边界（768）之外的荒谬位置，报 "Cannot start a mouse gesture outside the
     * Compose root bounds" —— 报错点在鼠标，不在滚动条，很容易误判成组件没画出来。
     */
    @Test
    fun `拖动滚动条会横向滚动且表头表体同步`() = runComposeUiTest {
        render(colCount = 23)

        val bar = onNodeWithTag(TABLE_HSCROLLBAR_TAG).fetchSemanticsNode()
        val before = hScrollValue(TABLE_HEADER_TAG)
        assertEquals(0f, before, 0.01f, "初始应停在最左")

        val midY = bar.size.height / 2f
        onNodeWithTag(TABLE_HSCROLLBAR_TAG).performMouseInput {
            moveTo(Offset(4f, midY))
            press()
            moveTo(Offset(204f, midY))
            release()
        }
        waitForIdle()

        val after = hScrollValue(TABLE_HEADER_TAG)
        assertTrue(after > before, "拖动横向滚动条后横向偏移应变大（$before → $after）")
        assertEquals(
            hScrollValue(TABLE_HEADER_TAG), hScrollValue(TABLE_BODY_TAG), 0.01f,
            "拖动后表头与表体必须停在同一个横向偏移，否则列名与数据列会错位",
        )
    }

    /** 反向护栏：列不多（不溢出）时不该凭空多出横向可滚量。 */
    @Test
    fun `少列时不产生横向可滚量`() = runComposeUiTest {
        render(colCount = 4)
        val max = onNodeWithTag(TABLE_BODY_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]?.maxValue?.invoke() ?: -1f
        assertEquals(0f, max, 0.01f, "4 列不该有横向可滚量 —— 滚动条会让人以为右边还有东西")
    }
}
