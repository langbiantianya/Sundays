package com.kxxnzstdsw.sundays

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import com.kxxnzstdsw.sundays.ui.isCompactMode
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 紧凑模式接线契约 —— `SundaysTheme(compact = true)` 必须真的把**环境密度**缩掉。
 *
 * 缩放系数与 `fontScale` 补偿的精确数学由 `shared` 的 `CompactModeTest`（纯函数）钉住；
 * 这里守的是另一条**只能在组合里暴露**的链路：`AppearanceState.compactMode` → `main.kt`
 * 传参 → `SundaysTheme` → `CompositionLocalProvider(LocalDensity …)` → 组件拿到的 `LocalDensity`。
 *
 * 任何一环漏掉（例如把 `LocalDensity` 写进 `MaterialTheme` 里面、或者覆盖放在了
 * `SundaysTheme` 之外），`compactDensity` 的单元测试照样全绿，只有这里会红。
 */
@OptIn(ExperimentalTestApi::class)
class CompactThemeTest {

    private data class Measured(
        val density: Float,
        val boxPx: Float,
        val textPx: Float,
    )

    private fun measure(compact: Boolean): Measured {
        var result: Measured? = null
        runComposeUiTest {
            setContent {
                SundaysTheme(compact = compact) {
                    val density = LocalDensity.current
                    result = Measured(
                        density = density.density,
                        // 40dp ≈ M3 Button 最小高；13sp ≈ bodyMedium
                        boxPx = with(density) { 40.dp.toPx() },
                        textPx = with(density) { 13.sp.toPx() },
                    )
                }
            }
        }
        return requireNotNull(result) { "SundaysTheme(compact=$compact) 没有产出测量值" }
    }

    @Test
    fun `compact theme shrinks dp geometry noticeably`() {
        val normal = measure(compact = false)
        val compact = measure(compact = true)

        assertTrue(
            compact.density < normal.density,
            "紧凑档应降低 LocalDensity.density：${normal.density} → ${compact.density}",
        )
        val ratio = compact.boxPx / normal.boxPx
        assertTrue(
            ratio in 0.75f..0.95f,
            "控件几何应有可感知的缩小（期望 0.75~0.95），实际 $ratio",
        )
    }

    @Test
    fun `compact theme spares the font size`() {
        val normal = measure(compact = false)
        val compact = measure(compact = true)

        val geometryRatio = compact.boxPx / normal.boxPx
        val textRatio = compact.textPx / normal.textPx

        // 核心不变量：字号缩放必须比控件几何温和。反了的话这个开关就变成了「让字变小」，
        // 而不是用户要的「同屏多几行」—— 而那种观感在 100% DPI 屏上就是费眼。
        assertTrue(
            textRatio > geometryRatio,
            "字号缩放($textRatio)应比控件几何($geometryRatio)温和；" +
                "若相等说明 fontScale 的反向补偿丢了",
        )
    }

    @Test
    fun `compact flag is published to components`() {
        var underNormal = true
        var underCompact = true
        runComposeUiTest {
            setContent {
                SundaysTheme(compact = false) {
                    underNormal = isCompactMode
                    SundaysTheme(compact = true) {
                        underCompact = isCompactMode
                    }
                }
            }
        }
        assertFalse(underNormal, "默认档不应进入紧凑")
        assertTrue(underCompact, "SundaysTheme 应把 compact 标志透出给组件")
    }
}
