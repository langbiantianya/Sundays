package com.kxxnzstdsw.sundays.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 紧凑档缩放数学 —— `compactDensity` 的纯函数契约。
 *
 * 这些断言看着琐碎，但它们钉的是**唯一一处肉眼无法证伪的逻辑**：
 * 缩放 `density` 会连带把 `sp` 文字也缩小（`sp` 的像素值是 `value × density × fontScale`），
 * 所以必须靠 `fontScale` 反向补偿。补偿里那个除以 `UI_SCALE` 的系数写错一个符号时，
 * 界面上只是「字稍微大一点 / 小一点」，没人会报告，而密度算错会让所有控件大小失真。
 */
class CompactModeTest {

    /** 2x 缩放屏 + 系统字体 100%。 */
    private val base = Density(density = 2f, fontScale = 1f)

    @Test
    fun non_compact_returns_the_base_untouched() {
        // 必须是**同一个实例**：默认档若新建一个 Density 对象，会白白打断重组期的
        // 引用相等判断，也让「默认档逐像素等于改造前」这条断言无从谈起。
        assertSame(base, compactDensity(base, compact = false))
    }

    @Test
    fun dp_geometry_shrinks_by_the_ui_scale() {
        val compact = compactDensity(base, compact = true)
        // 用 M3 的按钮最小高做样本：40dp → 34dp，正是这个功能要的效果
        assertEquals(
            with(base) { 40.dp.toPx() } * COMPACT_UI_SCALE,
            with(compact) { 40.dp.toPx() },
            absoluteTolerance = 0.001f,
            message = "控件几何应按 COMPACT_UI_SCALE 等比缩小",
        )
    }

    @Test
    fun text_shrinks_by_the_text_scale_not_the_ui_scale() {
        val compact = compactDensity(base, compact = true)
        // 关键断言：bodyMedium 的 13sp 只按 COMPACT_TEXT_SCALE 缩。
        // 若这里变成 COMPACT_UI_SCALE，说明 fontScale 的反向补偿丢了 —— 文字会跟着
        // 控件一起掉到 11sp，在 100% DPI 屏上开始费眼。
        assertEquals(
            with(base) { 13.sp.toPx() } * COMPACT_TEXT_SCALE,
            with(compact) { 13.sp.toPx() },
            absoluteTolerance = 0.001f,
            message = "字号只应按 COMPACT_TEXT_SCALE 缩小（fontScale 补偿失效的信号）",
        )
    }

    @Test
    fun a_non_default_system_font_scale_is_carried_through() {
        // 系统字体放大过（fontScale 1.25）的用户切紧凑档，不能被重置回 100%
        val scaled = Density(density = 1f, fontScale = 1.25f)
        val compact = compactDensity(scaled, compact = true)
        assertEquals(
            with(scaled) { 13.sp.toPx() } * COMPACT_TEXT_SCALE,
            with(compact) { 13.sp.toPx() },
            absoluteTolerance = 0.001f,
            message = "系统 fontScale 应被保留，只再乘紧凑档的文本缩放",
        )
    }

    @Test
    fun text_is_scaled_gently_compared_to_geometry() {
        // 这是一条**设计约束**而非实现细节：紧凑档的意义是「同屏多几行」，
        // 不是「看得更费劲」。一旦有人把两项调成同一档或文本更狠，这条测试会拦住。
        assertTrue(
            COMPACT_TEXT_SCALE > COMPACT_UI_SCALE,
            "字号缩放($COMPACT_TEXT_SCALE)应比控件缩放($COMPACT_UI_SCALE)温和，" +
                "否则紧凑档变成「字变小」而不是「同屏多几行」",
        )
    }

    @Test
    fun both_scales_only_shrink() {
        for ((name, scale) in listOf("UI" to COMPACT_UI_SCALE, "TEXT" to COMPACT_TEXT_SCALE)) {
            assertTrue(scale in 0f..1f, "$name 缩放系数应在 (0, 1]，实际 $scale")
        }
    }
}
