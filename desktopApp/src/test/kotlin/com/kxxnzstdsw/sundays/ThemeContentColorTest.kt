package com.kxxnzstdsw.sundays

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertTrue

/**
 * 主题契约：`SundaysTheme` 必须给「未显式指定颜色的 Text」一个可读的内容色。
 *
 * 回归背景：Material3 的 `MaterialTheme` 只注入 colorScheme / shapes / typography，**不注入**
 * `LocalContentColor`（其默认值是 `Color.Black`）。因此没有 `Surface` 兜底的区域（连接列表标题、
 * 向导步骤标题、`labelLarge` 小标题…）在暗色主题下会渲染成黑字贴黑底 —— 实测对比度 1.31:1，
 * 用户看到的就是「文字和背景太接近，看不清」。这里断言默认文本色与背景的对比度达 WCAG AA。
 */
@OptIn(ExperimentalTestApi::class)
class ThemeContentColorTest {

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `default text color stays readable in both themes`() {
        for (dark in listOf(false, true)) {
            var contentColor: Color? = null
            var backgroundColor: Color? = null
            runComposeUiTest {
                setContent {
                    SundaysTheme(darkTheme = dark) {
                        // 未显式指定颜色 —— 与向导 / 连接列表里的标题、labelLarge 同一取值路径
                        contentColor = LocalContentColor.current
                        backgroundColor = MaterialTheme.colorScheme.background
                        Text("文本")
                    }
                }
            }
            val fg = requireNotNull(contentColor) { "theme=${if (dark) "dark" else "light"}: 没有取到默认内容色" }
            val bg = requireNotNull(backgroundColor)
            assertTrue(
                contrastRatio(fg, bg) >= 4.5,
                "theme=${if (dark) "dark" else "light"} 默认文字色 $fg 与背景 $bg 对比度不足：" +
                    " ${contrastRatio(fg, bg)}",
            )
        }
    }
}
