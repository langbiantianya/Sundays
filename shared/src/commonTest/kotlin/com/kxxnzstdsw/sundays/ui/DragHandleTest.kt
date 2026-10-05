package com.kxxnzstdsw.sundays.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 分隔条拖拽数学 —— [nextPaneWidth] / [clampPaneWidth] 的纯函数契约。
 *
 * 这些断言看着琐碎，但它们钉的是一段**界面上无法证伪**的逻辑：拖拽本身需要真实鼠标事件，
 * 测不了；而「像素增量 → dp 换算 → 钳位」这段算术一旦写错，界面上只是「拖起来有点黏」或
 * 「能拖到 0 宽」这种说不清道不明的观感，没有人会报 bug，更没人说得清根因在换算还是在钳位。
 *
 * 钳位尤其要测足：分隔条可以**无限拖**（鼠标能一直往外走），所以「超过上限」不是边界情况，
 * 而是每一帧都在发生的常态。
 */
class DragHandleTest {

    /** 1x 密度，换算最直观：1px = 1dp。 */
    private val d1 = Density(density = 1f, fontScale = 1f)

    /** 2x 高分屏 —— 这是拖拽手感最容易出偏差的场合。 */
    private val d2 = Density(density = 2f, fontScale = 1f)

    private val min = 180.dp
    private val max = 600.dp

    @Test
    fun dragging_right_widens_the_pane() {
        assertEquals(
            400.dp,
            nextPaneWidth(current = 320.dp, dragDeltaPx = 80f, density = d1, minWidth = min, maxWidth = max),
            message = "向右拖应当按拖动距离等量加宽",
        )
    }

    @Test
    fun dragging_left_narrows_the_pane() {
        assertEquals(
            240.dp,
            nextPaneWidth(current = 320.dp, dragDeltaPx = -80f, density = d1, minWidth = min, maxWidth = max),
            message = "向左拖应当等量变窄，且不能反着来（正负号写反会让拖拽朝反方向响应）",
        )
    }

    @Test
    fun pixel_delta_is_converted_by_density_not_taken_as_dp() {
        // 2x 屏上拖 80px 只有 40dp。这条断言是整组测试里最要紧的一条：
        // 少写一层 `with(density) { toDp() }`，拖拽在高 DPI 下就会快一倍，
        // 而在 1x 开发机上完全正常 —— 属于「只有用户能发现」的缺陷。
        assertEquals(
            360.dp,
            nextPaneWidth(current = 320.dp, dragDeltaPx = 80f, density = d2, minWidth = min, maxWidth = max),
            message = "拖拽增量必须先经 density 换算成 dp，不能把像素直接当 dp 用",
        )
    }

    @Test
    fun stays_at_least_the_minimum_width() {
        assertEquals(
            min,
            nextPaneWidth(current = 200.dp, dragDeltaPx = -9999f, density = d1, minWidth = min, maxWidth = max),
            message = "一路拖到最左应停在下限，而不是被拖成 0 宽",
        )
    }

    @Test
    fun stays_at_most_the_maximum_width() {
        assertEquals(
            max,
            nextPaneWidth(current = 500.dp, dragDeltaPx = 9999f, density = d1, minWidth = min, maxWidth = max),
            message = "一路拖到最右应停在上限，否则右侧栏会被挤成一条缝",
        )
    }

    @Test
    fun an_oversized_delta_is_clamped_not_applied_in_full() {
        // 一次拖动的增量远大于剩余空间时，结果必须**一次性**落到边界，
        // 不能是先越界、等下一帧再被拉回来 —— 那会让分隔条抖一下。
        assertEquals(
            max,
            nextPaneWidth(current = 300.dp, dragDeltaPx = 5000f, density = d1, minWidth = min, maxWidth = max),
        )
    }

    @Test
    fun a_zero_delta_keeps_the_current_width() {
        assertEquals(
            320.dp,
            nextPaneWidth(current = 320.dp, dragDeltaPx = 0f, density = d1, minWidth = min, maxWidth = max),
            message = "拖拽过程中必然有增量为 0 的帧，此时宽度必须原地不动（否则分隔条会抖）",
        )
    }

    @Test
    fun incremental_dragging_accumulates() {
        // 逐帧累积与一次性总量等价 —— 这是「跟手」的实现基础（实时回调而非松手才跳）。
        var width = 320.dp
        repeat(10) { width = nextPaneWidth(width, 10f, d1, min, max) }
        assertEquals(
            420.dp,
            width,
            message = "10 次 10px 拖动应累积成 100dp",
        )
    }

    @Test
    fun clamp_keeps_values_inside_the_range_untouched() {
        assertEquals(
            320.dp,
            clampPaneWidth(320.dp, min, max),
            message = "区间内的宽度不应被钳位改动",
        )
    }

    @Test
    fun clamp_pulls_back_an_oversized_value() {
        assertEquals(max, clampPaneWidth(9999.dp, min, max))
        assertEquals(min, clampPaneWidth((-5).dp, min, max))
    }

    @Test
    fun clamp_pulls_back_when_the_container_shrinks_under_the_current_width() {
        // 窗口缩窄后 `maxWidth`（按容器比例算出）会小于调用方记忆中的当前宽度。
        // 此时必须**把宽度往回拽** —— 布局要服从当前窗口，而不是保留一个装不下的旧值。
        assertEquals(
            300.dp,
            clampPaneWidth(500.dp, min, 300.dp),
            message = "窗口缩窄后上限变小，旧宽度应被拉回上限而不是原样保留",
        )
    }

    @Test
    fun double_tap_reset_goes_through_the_same_clamp() {
        // 双击复位走 [clampPaneWidth] 而不是各写一份 coerceIn。默认值在极窄容器里
        // 可能已经超出上限；这条断言钉住「两条路径共用同一个钳位」。
        assertEquals(
            300.dp,
            clampPaneWidth(value = 320.dp, minWidth = min, maxWidth = 300.dp),
            message = "双击复位后的默认值同样要过钳位",
        )
    }
}
