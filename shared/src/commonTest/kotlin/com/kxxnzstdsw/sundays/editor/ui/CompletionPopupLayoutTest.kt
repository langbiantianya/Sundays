package com.kxxnzstdsw.sundays.editor.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 补全弹层的定位与宽度契约 —— [completionPopupX] / [completionPopupMaxWidth]。
 *
 * 弹层能不能完整显示是**界面上无法证伪**的：它被放在滚动容器里，越界部分由父级裁掉，
 * 不会抛异常、不会打日志，只表现为「候选列表右半截不见了」。所以只能在这里钉死规则。
 */
class CompletionPopupLayoutTest {

    // ---------------------------------------------------------------- 宽度上限

    @Test
    fun popup_takes_seventy_percent_of_a_wide_editor() {
        // 600dp 是**尚未触到两端**的编辑器宽度：70% = 420dp，落在 160~460 之间。
        assertEquals(420.dp, completionPopupMaxWidth(600.dp))
    }

    @Test
    fun popup_width_is_capped_at_four_hundred_sixty_dp() {
        // 超宽编辑器 / 超宽窗口：再宽一行也读不完，宽出来的全是空白，还得遮住更多代码。
        assertEquals(460.dp, completionPopupMaxWidth(2000.dp))
        assertEquals(460.dp, completionPopupMaxWidth(1000.dp))
    }

    @Test
    fun popup_width_has_a_floor_for_a_tiny_editor() {
        // 极窄编辑器：按 70% 算会窄到一行放不下一个候选名，必须托底。
        assertEquals(160.dp, completionPopupMaxWidth(120.dp))
    }

    @Test
    fun popup_width_follows_the_editor_in_the_middle_range() {
        // 中间区间必须是等比的，否则「宽编辑器用 460 上限、窄编辑器用 160 下限」
        // 会在某个宽度上突然跳变，用户会觉得弹层「尺寸不稳定」。
        assertEquals(280.dp, completionPopupMaxWidth(400.dp))
        assertEquals(350.dp, completionPopupMaxWidth(500.dp))
    }

    // ---------------------------------------------------------------- 横向落点

    @Test
    fun popup_starts_at_the_caret_when_there_is_room_on_the_right() {
        assertEquals(
            100,
            completionPopupX(caretX = 100, editorWidth = 800, popupMaxWidthPx = 320),
            message = "右侧放得下时，弹层左边缘应与光标对齐",
        )
    }

    @Test
    fun popup_flips_left_when_it_would_run_past_the_editor_edge() {
        // 光标靠行尾：弹层在滚动容器内，越界会被裁掉，右半截会直接消失。
        assertEquals(
            700 - 320,
            completionPopupX(caretX = 700, editorWidth = 800, popupMaxWidthPx = 320),
            message = "放不下时应向左翻转，贴着光标展开",
        )
    }

    @Test
    fun flipped_popup_keeps_its_right_edge_on_the_caret() {
        // 翻转语义的核心不变量：弹层**右边缘**与光标对齐。
        // 无论往哪边展开，锚点都是光标 —— 这样「弹层属于这段代码」这件事始终看得出来。
        val caretX = 640
        val width = 320
        val x = completionPopupX(caretX = caretX, editorWidth = 800, popupMaxWidthPx = width)
        assertEquals(
            caretX,
            x + width,
            message = "翻转后弹层右边缘应与光标对齐",
        )
    }

    @Test
    fun popup_flips_even_when_it_only_overflows_by_one_pixel() {
        // 只超 1px 就翻：写成 `>=` 会在最后一像素越界，那一列候选会被裁掉半截。
        assertEquals(
            402 - 300,
            completionPopupX(caretX = 402, editorWidth = 701, popupMaxWidthPx = 300),
            message = "702 > 701，哪怕只超 1px 也必须翻转",
        )
    }

    @Test
    fun popup_does_not_flip_when_it_exactly_fits() {
        assertEquals(
            400,
            completionPopupX(caretX = 400, editorWidth = 800, popupMaxWidthPx = 400),
            message = "恰好放得下时不该翻转（翻转优先于钳位，边界上应保持贴光标）",
        )
    }

    @Test
    fun flipped_popup_never_gets_a_negative_x() {
        // 光标在开头、弹层又比编辑器还宽（极窄编辑器 + 160dp 下限仍装不下）时，
        // `caretX - width` 会是负数；负坐标会让弹层往左溢出滚动区，裁切点跑到内容之外。
        val x = completionPopupX(caretX = 0, editorWidth = 100, popupMaxWidthPx = 300)
        assertEquals(0, x, message = "翻转后坐标必须钳到 0")
        assertTrue(x >= 0, "x 永远不能为负")
    }

    @Test
    fun popup_prefers_flipping_over_clamping_into_view() {
        // 本项目的取舍：翻转 > 钳位。钳位（min(caretX, editorWidth - w)）虽然弹层完整可见，
        // 但右边缘会离光标很远，视觉上「不知道弹层属于谁」。这里钉住翻转语义不变。
        val x = completionPopupX(caretX = 750, editorWidth = 800, popupMaxWidthPx = 320)
        assertEquals(430, x, message = "应翻转贴住光标，而不是钳到 480 让弹层离光标 270px")
    }
}
