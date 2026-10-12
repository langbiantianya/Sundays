package com.kxxnzstdsw.sundays.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.kxxnzstdsw.i18n.AppLanguage
import com.kxxnzstdsw.i18n.PluralStrKey
import com.kxxnzstdsw.i18n.StrKey
import com.kxxnzstdsw.i18n.Strings
import com.kxxnzstdsw.i18n.StringsRegistry

/**
 * 当前生效的文案表 —— 界面层读文案的唯一入口。
 *
 * ## 为什么用 CompositionLocal，而不是把 `Strings` 层层往下传
 *
 * 文案表的使用点分布在一个 6800 行的浏览屏里的**几百个** `Text(text = …)` 上。
 * 逐层传参意味着每个中间 Composable 的签名都要多一个参数，而它们本身并不关心语言 ——
 * 绝大多数只是为了把它交给叶子。与 [LocalUiTokens] / [LocalDarkMode] 同一套手法：
 * 组合最外层注入一次，全树可读。
 *
 * ## 为什么默认是「按系统语言探测」而不是简体中文
 *
 * 默认值要同时保证两件事：**没套 [SundaysTheme] 的渲染路径不崩**
 * （Preview 与单元测试里大量存在这种直接调用），以及首次启动不必先绕一次路做
 * 「注入再取」。系统语言探测在绝大多数机器上就是正确答案。
 *
 * 这**不等于**「运行时永远是系统语言」—— 设置里选过之后，由 `main.kt` 注入的
 * 是用户的选择，本默认值根本轮不到被读到。
 */
val LocalStrings = staticCompositionLocalOf { StringsRegistry.systemDefault() }

/**
 * 当前生效的界面语言。
 *
 * 与 [LocalStrings] 分开，是因为语言本身要参与**逻辑**而不只是取文案：
 * 复数变体的选择（见 `PluralForm`）要的就是语言，而不是整张表。
 */
val LocalAppLanguage: AppLanguage
    @Composable @ReadOnlyComposable get() = LocalStrings.current.language

/** 取一条文案 —— `str(StrKey.CANCEL)`。 */
@Composable
@ReadOnlyComposable
fun str(key: StrKey): String = LocalStrings.current[key]

/**
 * 取一条带占位符的文案 —— `str(StrKey.CONFIRM_DELETE_CONNECTION, name)`。
 *
 * 占位符写作 `{0}` `{1}`。**刻意不用** `String.format`（`%s`）：译文里 `%` 有别的用途，
 * 而 `MessageFormat` 会把 `'` 当转义符吃掉（`O'Brien` → `OBrien`）。替换逻辑见
 * `Strings.substitute`。
 */
@Composable
@ReadOnlyComposable
fun str(key: StrKey, vararg args: Any?): String = LocalStrings.current.format(key, *args)

/**
 * 取一条**按数量选变体**的文案 —— `plural(StrKey.ROWS_COUNT, n)`。
 *
 * 俄语要 one/few/many 三种词形，调用方只管把数字交出来。
 */
@Composable
@ReadOnlyComposable
fun plural(key: PluralStrKey, count: Int, vararg args: Any?): String =
    LocalStrings.current.plural(key, count, *args)

/**
 * 提供文案表 —— 由 `main.kt` 在组合最外层调用。
 *
 * 放在最外层（与 [SundaysTheme] 同级或更外）而不是各屏幕内部，理由与 `AppearanceState`
 * 的 KDoc 同源：**语言一变，全树文案都要变**，把注入点放在深处必然漏掉某条分支。
 *
 * @param language 用户选定的语言。
 */
@Composable
fun ProvideStrings(language: AppLanguage, content: @Composable () -> Unit) {
    val strings = remember(language) { StringsRegistry.of(language) }
    CompositionLocalProvider(LocalStrings provides strings, content = content)
}
