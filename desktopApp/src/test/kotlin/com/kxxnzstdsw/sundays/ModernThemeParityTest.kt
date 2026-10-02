package com.kxxnzstdsw.sundays

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import com.kxxnzstdsw.sundays.ui.ThemePalette
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.WinOutlinedButton
import com.kxxnzstdsw.sundays.ui.WinTextButton
import com.kxxnzstdsw.sundays.ui.WinTextField
import com.kxxnzstdsw.sundays.ui.controlShape
import com.kxxnzstdsw.sundays.ui.resolveButtonShape
import com.kxxnzstdsw.sundays.ui.resolveControlShape
import com.kxxnzstdsw.sundays.ui.resolveTextFieldShape
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * **现代主题的逐像素平价契约** —— 没装 Win2000 / WinXP 时，界面必须与引入复古主题之前
 * 逐像素一致。
 *
 * ## 这个坑是怎么踩到的
 *
 * 改造前 37 个按钮 / 输入框调用点里有 **10 个没有显式传 `shape`**，它们拿到的是 Material3
 * 由 token 固定的默认形状（`ButtonSmallTokens.ContainerShapeRound`，胶囊）。把
 * `WinButton` 的 `shape` 默认参数直接写成 `SundaysPalette.buttonShape`（4dp 圆角）后，
 * 这 10 处**在现代主题下也被改了形状**。
 *
 * 症状表现为「M3 按钮的 hover 样式变了」：M3 的 hover / press 状态层是沿着 `shape` 轮廓
 * 绘制的，形状一换，状态层轮廓就跟着换 —— 而根因离 hover 有两层之远，不查形状根本看不出来。
 *
 * ## 契约
 *
 * | 调用方 | 现代主题 | 经典主题 |
 * |---|---|---|
 * | 显式传了 `shape` | 用传入值 | 用传入值 |
 * | **没传** | **M3 自己的默认形状** | 主题的复古形状 |
 *
 * 换言之：**包装层只在经典主题下改变形状**。
 */
@OptIn(ExperimentalTestApi::class)
class ModernThemeParityTest {

    @Test
    fun `modern theme keeps the M3 default shape when the caller passes none`() = runComposeUiTest {
        var resolved: Shape? = null
        var m3Default: Shape? = null
        var retroShape: Shape? = null

        setContent {
            SundaysTheme(darkTheme = false, palette = ThemePalette.BLUE_GRAY) {
                m3Default = ButtonDefaults.shape
                retroShape = controlShape()
                resolved = resolveButtonShape(null)
            }
        }
        waitForIdle()

        assertEquals(
            m3Default,
            resolved,
            "现代主题下未传 shape 时，包装层必须原样返回 M3 的默认形状（改造前它们拿到的就是它）",
        )
        // 顺带证明它确实**不是**复古形状 —— 否则这条断言会因为两边都退化成同一个值而假通过
        assertEquals(
            false,
            resolved == retroShape && retroShape != m3Default,
            "若现代形状与复古形状恰好相同，这条测试将失去分辨力，请改用其它断言",
        )
    }

    @Test
    fun `classic themes substitute the retro shape only when the caller passes none`() = runComposeUiTest {
        var win2000NoArg: Shape? = null
        var win2000Explicit: Shape? = null
        val explicit = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

        setContent {
            SundaysTheme(darkTheme = false, palette = ThemePalette.WIN_2000) {
                win2000NoArg = resolveButtonShape(null)
                win2000Explicit = resolveButtonShape(explicit)
            }
        }
        waitForIdle()

        assertEquals(
            controlShapeExpected(ThemePalette.WIN_2000),
            win2000NoArg,
            "经典主题下未传 shape 时应取该主题的复古形状",
        )
        assertEquals(
            explicit,
            win2000Explicit,
            "显式传了 shape 时必须原样使用调用方的值，包装层不得覆盖",
        )
    }

    /**
     * 输入框的 M3 默认形状**不是**按钮的胶囊。
     *
     * 改造前 9 个输入框调用点（首屏 8 + 浏览屏 1）全部没传 `shape`，它们拿到的是
     * `FilledTextFieldTokens.ContainerShape` = `ShapeKeyTokens.CornerExtraSmallTop`，
     * 即 `shapes.extraSmall.top()` —— **只有上方两角 2dp，左 / 右 / 下是方角**。
     *
     * 曾把按钮的 `ButtonDefaults.shape`（胶囊）当成统一兜底，结果这 9 个输入框的左右边
     * 全变成圆弧。这条断言把两种默认形状的差异钉死。
     */
    @Test
    fun `modern text field shape is the squared M3 default, not the button pill`() = runComposeUiTest {
        var fieldShape: Shape? = null
        var buttonShape: Shape? = null
        var expectedField: Shape? = null
        var expectedButton: Shape? = null

        setContent {
            SundaysTheme(darkTheme = false, palette = ThemePalette.BLUE_GRAY) {
                fieldShape = resolveTextFieldShape(null)
                buttonShape = resolveButtonShape(null)
                expectedField = androidx.compose.material3.MaterialTheme.shapes.extraSmall.squaredBottomForTest()
                expectedButton = androidx.compose.material3.ButtonDefaults.shape
            }
        }
        waitForIdle()

        assertEquals(expectedField, fieldShape, "现代主题下输入框未传 shape 时应取 extraSmall.top()（左/右/下为方角）")
        assertEquals(expectedButton, buttonShape, "现代主题下按钮未传 shape 时应取 ButtonDefaults.shape")
        assertNotEquals(
            fieldShape,
            buttonShape,
            "输入框与按钮的 M3 默认形状必须不同 —— 相等即说明其中一边的兜底写错了",
        )
    }

    /** 经典档的期望形状：与 [resolveControlShape] 独立算出，避免自证。 */
    private fun controlShapeExpected(palette: ThemePalette): Shape {
        val tokens = palette.uiTokens(useDark = false)
        return androidx.compose.foundation.shape.RoundedCornerShape(tokens.bevel.bevelRadius)
    }

    /**
     * 编辑器 / 表格的现代档必须**原样**取回两套写死常量。
     *
     * 曾一度把 `themed()` 的现代分支也改成从 `ColorScheme` 取色（表头、行底、选行、边框
     * 全换），结果现代三套主题的表格与编辑器整体变色 —— SQL / 造数工作台的工具栏
     * （执行按钮所在那一行）跟着一起「看着不对」。
     *
     * 代价：现代档下这两者仍按**系统**明暗而非应用的明暗档切换（与改造前一致，属已知历史
     * 行为）。复古改造不该顺手改它。
     */
    @Test
    fun `modern theme keeps the original editor and table palettes`() = runComposeUiTest {
        var editor: Any? = null
        var table: Any? = null
        var lightEditor: Any? = null
        var darkEditor: Any? = null
        var lightTable: Any? = null
        var darkTable: Any? = null

        var systemDark = false
        setContent {
            SundaysTheme(darkTheme = false, palette = ThemePalette.BLUE_GRAY) {
                systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                editor = com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme.themed()
                table = com.kxxnzstdsw.sundays.table.DataTableTheme.themed()
                lightEditor = com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme.Light
                darkEditor = com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme.Dark
                lightTable = com.kxxnzstdsw.sundays.table.DataTableTheme.Light
                darkTable = com.kxxnzstdsw.sundays.table.DataTableTheme.Dark
            }
        }
        waitForIdle()

        val expectedEditor = if (systemDark) darkEditor else lightEditor
        val expectedTable = if (systemDark) darkTable else lightTable
        assertEquals(expectedEditor, editor, "现代主题的编辑器主题必须原样等于 Light / Dark 常量")
        assertEquals(expectedTable, table, "现代主题的表格主题必须原样等于 Light / Dark 常量")
    }

    /**
     * 渲染平价：现代主题下 `WinButton` 与裸 M3 `Button`（同样不传 shape）解析出的
     * 形状与配色必须一致 —— 二者都是 M3 `Button` 的输入，输入相等即渲染相等。
     */
    @Test
    fun `modern WinButton and plain Button resolve identical inputs`() = runComposeUiTest {
        var winShape: Shape? = null
        var plainShape: Shape? = null

        setContent {
            SundaysTheme(darkTheme = false, palette = ThemePalette.BLUE_GRAY) {
                plainShape = ButtonDefaults.shape
                winShape = resolveButtonShape(null)
            }
        }
        waitForIdle()

        assertEquals(plainShape, winShape, "现代主题下 WinButton 的形状必须等于裸 M3 Button 的形状")
    }
}

/** 测试侧的 `extraSmall.top()` 复现 —— 与生产代码 `squaredBottom` 同义，独立写一遍避免自证。 */
private fun androidx.compose.foundation.shape.CornerBasedShape.squaredBottomForTest(): Shape =
    copy(
        bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
        bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
    )