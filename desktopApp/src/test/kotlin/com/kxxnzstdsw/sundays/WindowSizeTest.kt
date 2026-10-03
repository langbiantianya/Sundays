package com.kxxnzstdsw.sundays

import androidx.compose.ui.unit.DpSize
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 窗口尺寸契约 —— 默认值、最小值、以及「默认必须放得下」的几条硬约束。
 *
 * 这些数字直接决定三段式布局（浏览屏「左树 + 右表 + 底栏」）会不会塌：
 * 数字本身没有代码依赖，但每一条都对应一个用户能立刻看到的坏结果。
 */
class WindowSizeTest {

    @Test
    fun `the default size is never clamped by the minimum`() {
        // 默认值若小于下限，`EnforceMinimumWindowSize` 会在启动第一帧就把窗口改写，
        // 用户看到的是「窗口自己跳了一下变大」—— 故默认必须 ≥ 下限。
        assertTrue(
            DEFAULT_WINDOW_SIZE.width >= MIN_WINDOW_SIZE.width &&
                DEFAULT_WINDOW_SIZE.height >= MIN_WINDOW_SIZE.height,
            "默认 ${DEFAULT_WINDOW_SIZE.width}×${DEFAULT_WINDOW_SIZE.height} 必须不小于下限 " +
                "${MIN_WINDOW_SIZE.width}×${MIN_WINDOW_SIZE.height}，否则启动时会被夹取逻辑改写",
        )
    }

    @Test
    fun `the default height fits on a 768 tall laptop screen`() {
        // 这正是把 820 改小的原因：1366×768 是仍有大量保有量的笔记本分辨率，
        // 820dp 的窗口比屏幕还高，第一眼看到的就是一个被截断的窗口。
        // 留 48dp 给任务栏。
        val laptopScreenHeight = 768f
        val taskbarAllowance = 48f
        val windowHeight = with(DEFAULT_WINDOW_SIZE) { height.value }
        assertTrue(
            windowHeight + taskbarAllowance <= laptopScreenHeight,
            "默认高度 ${windowHeight}dp 加任务栏 ${taskbarAllowance}dp 应不超过 ${laptopScreenHeight}dp，" +
                "否则 1366×768 的笔记本上窗口显示不全",
        )
    }

    @Test
    fun `the default leaves the browser three section layout room`() {
        // 浏览屏：左树固定 250dp + 右栏内容区（≥350dp）+ 分隔与内边距。
        // 右栏再扣掉滚动条、详情面板等，至少仍要放得下几列。
        val leftTree = 250f
        val rightContentMin = 350f
        val chrome = 32f          // 竖向分隔线 + 两侧内边距
        val available = with(DEFAULT_WINDOW_SIZE) { width.value }
        val rightWidth = available - leftTree - chrome
        assertTrue(
            rightWidth >= rightContentMin,
            "默认宽度 ${available}dp 留给右栏仅 ${rightWidth}dp，" +
                "低于内容区下限 ${rightContentMin}dp（左树 ${leftTree}dp + chrome ${chrome}dp）",
        )
        // 一列约 100~120dp，6 列是「不用横向滚动就能看到几列」的下限感
        assertTrue(
            rightWidth >= 6 * 100f,
            "右栏 ${rightWidth}dp 放不下 6 列，浏览屏首屏会显得很挤",
        )
    }

    @Test
    fun `the onboarding content column still fits at the default width`() {
        // 引导页内容限宽 960dp：窗口再窄就要换行成两排色卡（省高度但更绕）。
        val onboardingContentMax = 960f
        val horizontalPadding = 48f   // 24dp × 2，紧凑档下按非紧凑算（更保守）
        val available = with(DEFAULT_WINDOW_SIZE) { width.value }
        assertTrue(
            available - horizontalPadding >= onboardingContentMax,
            "默认宽度 ${available}dp 减去左右留白后放不下 ${onboardingContentMax}dp 的内容列，" +
                "5 张色卡会被迫换成两排",
        )
    }

    @Test
    fun `the default is a sane shape`() {
        // 纯防御：拦下「宽高写反」或「单位写成 px 当 dp 用」这类手滑
        val size: DpSize = DEFAULT_WINDOW_SIZE
        assertTrue(size.width.value > 0 && size.height.value > 0, "窗口尺寸必须为正")
        assertTrue(
            size.width.value > size.height.value,
            "桌面工具窗口应宽大于高，实际 ${size.width}×${size.height}（可能宽高写反了）",
        )
    }
}
