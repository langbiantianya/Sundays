package com.kxxnzstdsw.sundays.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.kxxnzstdsw.sundays.editor.ui.CodeEditor
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors
import com.kxxnzstdsw.sundays.ui.SundaysTheme
import com.kxxnzstdsw.sundays.ui.ThemePalette
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代码编辑器**必须完整跟随应用主题**。
 *
 * ## 这条契约曾被破坏，且破坏得很隐蔽
 *
 * `CodeEditorTheme.themed()` 在**现代档**下按 `isSystemInDarkTheme()`（**系统设置**）在
 * [CodeEditorTheme.Light] / [CodeEditorTheme.Dark] 间二选一，而应用有自己的明暗档
 * （`AppearanceState` 允许强制「始终浅色 / 始终深色」）。于是：用户强制浅色、系统是深色时，
 * **整个界面是浅色的，唯独编辑器是深色的**。
 *
 * 这不是「风格统一」问题，是**反差** —— 深色编辑器块贴在浅色工作台里，用户会以为那块区域坏了。
 * 配色主题那部分一直是好的（`themed()` 读了 `LocalUiTokens`），坏的只有明暗档，所以
 * 「换了主题编辑器没跟着」这个报法本身把问题指错了地方。
 *
 * ## 为什么必须**钉住系统设置**来测
 *
 * 这是本文件最关键的设计。判据若写成「应用深色 → 编辑器深」而不控制系统值，那么它的成败
 * 取决于**跑测试的机器当时是深色还是浅色系统**：
 *
 * - 机器系统是**深色**时，错误实现恒返回 `Dark`。于是「应用深色 → 编辑器深」这一条**恰好蒙对**
 *   （测的是本机系统设置，不是应用档位），而「应用浅色 → 编辑器浅」会红。
 * - 机器系统是**浅色**时，对调过来：只有前一条红。
 *
 * 也就是说，**两个方向里永远有一个是废的**，而且废的那个还会在别人的机器上安静地通过 ——
 * 这正是本文件第一版栽的坑（变异验证时 9 项只红 2 项）。
 *
 * 解法：用 `LocalSystemTheme provides SystemTheme.Light / Dark` 把系统值**钉死**，
 * 让「应用档位」与「系统设置」**故意相反**。Compose Multiplatform 的
 * `isSystemInDarkTheme()` 读的就是 `LocalSystemTheme`，所以这一个 provider 就足以让错误实现
 * 稳定暴露 —— 且与本机设置无关，任何机器上四个组合的结果都一样。
 *
 * `LocalSystemTheme` 带 `@InternalComposeUiApi`，这里选择接受：它是**唯一**能把系统明暗
 * 当参数使的注入点，且失效方式是**编译报错**（响亮的失败，需要显式重写才会解冻），
 * 远好过「悄悄退化成依赖本机设置、假通过」。若 CMP 改版，本文件会立刻编不过，
 * 那时再找替代缝隙即可 —— 而不是今天就带着一个测不出东西的绿测试往前走。
 *
 * ## 为什么必须用 GUI 测试
 *
 * 这条不是纯函数能钉住的：`themed()` 读的是 `LocalDarkMode`，而它由
 * `SundaysTheme(darkTheme = …)` 注入 —— 只有真的把主题套上，才知道编辑器最终读到的是什么。
 *
 * 每个档位各一个测试：`runComposeUiTest` 里 `setContent` 只能调一次
 * （第二次会抛 "setContent called twice"），跨块比较则要嵌套 `runComposeUiTest`（也不行）。
 * 分成独立测试反而更简单，也各自能独立失败。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class CodeEditorThemeFollowTest {

    private val sql = "SELECT id, name FROM users WHERE id > 1"

    // ==========================================================================
    // 主题对象级 —— 应用档位 vs 系统设置
    // ==========================================================================

    /**
     * 用户报的原始症状：强制浅色、系统深色。
     *
     * 这是**唯一一个**在错误实现下必然失败的方向，且它恰好对应真实用户场景。
     */
    @Test
    fun `a light app keeps a light editor even when the system is dark`() {
        val editor = resolvedEditorTheme(appDark = false, systemDark = true)

        assertEquals(
            CodeEditorTheme.Light,
            editor,
            "应用强制浅色 + 系统深色时编辑器必须是浅色档 —— `themed()` 若读 " +
                "`isSystemInDarkTheme()`，这一格必然拿到 Dark。",
        )
        assertTrue(
            editor.backgroundColor.luminance() > 0.5f,
            "浅色档的底色应当是亮的，实际 ${editor.backgroundColor}",
        )
    }

    /** 上一条的反方向：强制深色、系统浅色。同样只有错误实现才会红。 */
    @Test
    fun `a dark app keeps a dark editor even when the system is light`() {
        val editor = resolvedEditorTheme(appDark = true, systemDark = false)

        assertEquals(
            CodeEditorTheme.Dark,
            editor,
            "应用强制深色 + 系统浅色时编辑器必须是深色档 —— `themed()` 若读 " +
                "`isSystemInDarkTheme()`，这一格必然拿到 Light。",
        )
        assertTrue(
            editor.backgroundColor.luminance() < 0.5f,
            "深色档的底色应当是暗的，实际 ${editor.backgroundColor}",
        )
    }

    /**
     * 穷举四种「应用档位 × 系统设置」组合。
     *
     * 前两条已单独覆盖；这里补上**两者一致**的两格：它们平时就该通过，但能在实现被改成
     * 「永远深色 / 永远浅色」这类更粗暴的错误时立刻报出来，且不依赖本机设置。
     */
    @Test
    fun `the editor follows the app tier in every app-versus-system combination`() {
        for (appDark in listOf(false, true)) {
            for (systemDark in listOf(false, true)) {
                val editor = resolvedEditorTheme(appDark = appDark, systemDark = systemDark)
                val expected = if (appDark) CodeEditorTheme.Dark else CodeEditorTheme.Light
                assertEquals(
                    expected,
                    editor,
                    "应用档位=$appDark / 系统设置=$systemDark 时编辑器应取 ${if (appDark) "Dark" else "Light"}",
                )
            }
        }
    }

    // ==========================================================================
    // 像素级 —— 主题对象对了不代表画出来对了
    // ==========================================================================

    /**
     * **像素级**兜底：`themed()` 与真正画出来的像素之间还隔着 `Box.background(…)`、
     * `BasicTextField(textStyle = …)` 一整条链，漏一环参数就白传了。
     *
     * 取样点选**编辑器节点自身**（`testTag` 命中根 `BoxWithConstraints`），而不是根节点 ——
     * 编辑器只占上方一部分，早期版本取根节点中心，量到的是 `SundaysTheme` 那层 `Surface`
     * 的底色。它当然会跟着应用档位变，于是**这条测试在 bug 存在时照样绿**，白写。
     */
    @Test
    fun `the rendered editor really turns dark when the app is dark`() {
        val luma = renderedCenterLuma(appDark = true, systemDark = false)
        assertTrue(
            luma < 0.5f,
            "应用深色（系统浅色）时渲染出来的编辑器应当是暗的，实际 luma=$luma —— " +
                "主题对象对了但没传进绘制链。",
        )
    }

    /** [the rendered editor really turns dark when the app is dark] 的浅色反向。 */
    @Test
    fun `the rendered editor really turns light when the app is light`() {
        val luma = renderedCenterLuma(appDark = false, systemDark = true)
        assertTrue(
            luma > 0.5f,
            "应用浅色（系统深色）时渲染出来的编辑器应当是亮的，实际 luma=$luma —— " +
                "主题对象对了但没传进绘制链。",
        )
    }

    // ==========================================================================
    // 辅助
    // ==========================================================================

    /**
     * 在指定「应用档位 + 系统设置」下解析 `CodeEditorTheme.themed()`。
     *
     * [systemDark] 通过 `LocalSystemTheme` 钉进组合：桌面端 `isSystemInDarkTheme()` 读的
     * 就是它，故这一个 provider 足以让「读系统设置」的错误实现稳定暴露。
     */
    private fun resolvedEditorTheme(appDark: Boolean, systemDark: Boolean): CodeEditorTheme {
        var result: CodeEditorTheme? = null
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides systemThemeOf(systemDark)) {
                    SundaysTheme(darkTheme = appDark, palette = ThemePalette.BLUE_GRAY) {
                        result = CodeEditorTheme.themed()
                    }
                }
            }
            waitForIdle()
        }
        return requireNotNull(result) {
            "SundaysTheme(darkTheme=$appDark) 下 CodeEditorTheme.themed() 没有返回值"
        }
    }

    /** 真渲染一遍 [CodeEditor] 并量编辑区域内的像素亮度。 */
    private fun renderedCenterLuma(appDark: Boolean, systemDark: Boolean): Float {
        var image: ImageBitmap? = null
        runComposeUiTest {
            registerBuiltinEditors()
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides systemThemeOf(systemDark)) {
                    SundaysTheme(darkTheme = appDark, palette = ThemePalette.BLUE_GRAY) {
                        Box(Modifier.fillMaxSize()) {
                            CodeEditor(
                                text = sql,
                                onTextChange = {},
                                languageId = "sql",
                                // testTag 落在 CodeEditor 的根 BoxWithConstraints 上 ——
                                // 截图范围因此正好是编辑器本身，而不是外层留白。
                                modifier = Modifier.testTag(EDITOR_TAG),
                                minLines = 8,
                                maxLines = 8,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            image = onNodeWithTag(EDITOR_TAG).captureToImage()
        }
        val img = requireNotNull(image) { "编辑器没有渲染出可截取的节点" }
        return editorAreaLuma(img)
    }

    /**
     * 编辑器**内容区**的亮度 —— 取右侧若干点（远离左侧行号槽，行文也很短，右侧必是纯底色）。
     *
     * 取多点最大值而不是单点中心：单点有可能落在字形或行高缝隙的抗锯齿边缘上，
     * 那会让断言偶尔差一点点；要求「右半区整体都暗 / 都亮」既稳又更贴近肉眼看到的那块区域。
     */
    private fun editorAreaLuma(img: ImageBitmap): Float {
        val px = img.asSkiaBitmap()
        var max = 0f
        var min = 1f
        for (fy in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            for (fx in listOf(0.7f, 0.8f, 0.9f)) {
                val l = Color(px.getColor((px.width * fx).toInt(), (px.height * fy).toInt())).luminance()
                if (l > max) max = l
                if (l < min) min = l
            }
        }
        // 取两个极值的中点：既躲开个别异常点，又能同时反映「整体偏亮 / 偏暗」
        return (max + min) / 2f
    }

    private fun systemThemeOf(dark: Boolean): SystemTheme =
        if (dark) SystemTheme.Dark else SystemTheme.Light

    private companion object {
        const val EDITOR_TAG = "code-editor"
    }
}