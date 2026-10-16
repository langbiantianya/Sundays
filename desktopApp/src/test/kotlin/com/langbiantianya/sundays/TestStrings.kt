/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.runtime.Composable
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.i18n.StringsRegistry
import com.langbiantianya.sundays.ui.ProvideStrings

/**
 * Compose UI 测试的**钉死的文案语言** —— 断言文案的地方一律从这里取，不写中文字面量。
 *
 * ## 为什么必须显式钉住，不能靠默认语言
 *
 * 界面文案由 [ProvideStrings] 注入，而 [ProvideStrings] **不注入时**的兜底是
 * `StringsRegistry.systemDefault()` —— 即读 `Locale.getDefault()` 猜语言。
 * 于是「这台开发机是中文系统 → 测试碰巧过」会变成一种真实的假绿：
 * 换一台英文机器跑同一个测试，所有按中文定位的断言一起红，而红的原因与被测代码无关。
 *
 * 更糟的是它**看起来像被测代码坏了**：报的是 `onNodeWithText` 找不到节点，
 * 第一反应永远是「我把界面改坏了」，而真因是运行测试的机器换了 locale。
 *
 * 所以这里把语言收敛成**一个常量**：[LANGUAGE]。测试里 `setContent` 一律套
 * [ProvideTestStrings]，断言一律用 [zh] 取 —— 两者读同一个常量，
 * 于是「界面渲染成什么语言」与「断言拿什么文案去比」在结构上就不可能对不上。
 *
 * ## 为什么固定简体中文而不是跟着 `AppLanguage.DEFAULT`
 *
 * 因为**别的**测试已经在盯默认语言与首启判据了（`LanguageBootstrapTest` 参数化覆盖
 * 全部六种系统语言 / 落盘值 / 非法值回落；`AppLanguageTest` 覆盖 `Locale` 匹配规则）。
 * 那些测试里语言是**被测变量**；这里语言是**被固定的常量** —— 同一个测试类里
 * 两种角色混在一起会让「这条断言到底在验什么」变得不可读。
 *
 * 改这个常量等于一次性改掉所有 UI 测试的渲染语言，那正是我们要的显式性。
 */
object TestStrings {

    /**
     * UI 测试统一使用的界面语言。
     *
     * 刻意选简体中文：绝大多数断言原本就是按中文写的，改成固定中文等于
     * 「把隐式的依赖变成显式的约定」，而不是把测试整体翻译一遍。
     */
    val LANGUAGE: AppLanguage = AppLanguage.ZH_HANS

    private val table = StringsRegistry.of(LANGUAGE)

    /** 取一条界面文案 —— 与界面上的取值走**同一张表**。 */
    fun zh(key: StrKey): String = table[key]

    /** 取一条带占位符的界面文案。 */
    fun zh(key: StrKey, vararg args: Any?): String = table.format(key, *args)

    /**
     * 取一条按数量选变体的界面文案。
     *
     * ⚠️ [count] 会**传两次**：一次选复数变体（俄语 one/few/many 要靠它），
     * 一次作为 `{0}` 填进文案。这是 `Strings.plural` 的既有约定，生产侧同样如此
     * （见 `SettingsScreen` 的 `plural(PluralStrKey.AVAILABLE_PROCESSORS_COUNT, n, n)`）。
     *
     * 少传一次的后果很隐蔽：`Strings.substitute` 在 `args` 为空时**原样返回**，
     * 于是断言拿到的���`"查询结果 · {0} 行"` 而不是 `"查询结果 · 2 行"`，
     * 界面上当然找不到节点，报错还会指向「文案变了」而不是「调用方漏传参数」。
     */
    fun zh(key: PluralStrKey, count: Int, vararg args: Any?): String = table.plural(key, count, *args)
}

/** 简写 —— `zh(StrKey.REFRESH)`。测试里读起来就是「简体中文的这条文案」。 */
fun zh(key: StrKey): String = TestStrings.zh(key)

/** 简写（带参）。 */
fun zh(key: StrKey, vararg args: Any?): String = TestStrings.zh(key, *args)

/** 简写（复数）。 */
fun zh(key: PluralStrKey, count: Int, vararg args: Any?): String = TestStrings.zh(key, count, *args)

/**
 * 在 [TestStrings.LANGUAGE] 下渲染被测界面。
 *
 * 与 `setContent { MaterialTheme { … } }` 的区别只有一层：把文案表钉死成简体中文。
 * 少了它，被测界面就会按**运行测试的机器**的 locale 渲染，按中文定位的断言随之失效。
 */
@Composable
fun ProvideTestStrings(content: @Composable () -> Unit) {
    ProvideStrings(TestStrings.LANGUAGE, content)
}
