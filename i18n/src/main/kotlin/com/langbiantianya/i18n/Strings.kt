/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

/**
 * 一份「已按语言解析好」的文案表 —— 文案层唯一的读取入口。
 *
 * ## 降级链：当前语言 → 简体中文 → 键名
 *
 * 缺一条翻译的后果分两种，处理方式也不同：
 *
 * - **缺整条**（键在枚举里但译文表里没有）→ 回落到简体中文。界面照常显示，
 *   只是那一处是中文。选回落而不是回落到键名，是因为键名长得像 `rowsCount`
 *   这种东西出现在界面上比中文更糟；而「非中文界面里混进一处中文」是**用户能看见、
 *   我们能看见**的失败，比崩溃温和得多。
 * - **占位符对不上**（译文少了 `{1}`）→ 不做任何补齐，缺的位置原样留着 `{1}`。
 *   同样交给测试拦（见 `StringsIntegrityTest`），运行期不猜。
 *
 * 之所以敢让运行期安静降级，是因为**编译期与测试期各有一道闸**：
 * 键是枚举（拼错编译期就报），全键覆盖由 `StringsIntegrityTest` 逐语言断言。
 *
 * ## 线程安全性
 *
 * 构造后不可变，六个实例在 [StringsRegistry] 里各存一份，全局共享无锁。
 */
class Strings internal constructor(
    /** 本表对应的语言。 */
    val language: AppLanguage,
    private val texts: Map<StrKey, String>,
    private val plurals: Map<PluralStrKey, Map<PluralForm, String>>,
) {
    /**
     * 取一条文案。
     *
     * 刻意**不**提供无参默认：`operator fun get` 强制调用方写出键，
     * 漏传时是编译错误而不是空字符串上屏。
     */
    operator fun get(key: StrKey): String = texts[key] ?: fallbackText(key)

    /**
     * 取一条带占位符的文案并填参。
     *
     * 占位符写作 `{0}` / `{1}`，下标从 0 起。**刻意不用**
     * `String.format`（`%s`）或 `MessageFormat`（`{0}` 但带单引号转义规则）：
     * 前者的 `%` 在翻译文本里偶尔有别的用途，后者会把 `'` 吃掉变成转义符，
     * 于是「O'Brien」在德语译文里会静默变成 `OBrien`。自己替换最省心。
     *
     * @param args 依次对应 `{0}`、`{1}`…；多余的参数忽略，少的保持原样。
     */
    fun format(
        key: StrKey,
        vararg args: Any?,
    ): String = substitute(get(key), args)

    /**
     * 取一条**按数量选变体**的文案。
     *
     * 变体选择见 [pluralForm]（俄语三种、英语德语两种、中日只有一种）。
     * 某语言若没提供对应类别，逐级回落到 OTHER → 简体中文 → 键名。
     *
     * @param count 数量，**只用来选变体**；`{0}` 的取值默认也是它。
     * @param args 其余占位符对应 `{1}`、`{2}`…；省略时 `{0}` 自动取 [count]。
     */
    fun plural(
        key: PluralStrKey,
        count: Int,
        vararg args: Any?,
    ): String {
        val form = language.pluralForm(count)
        val variants = plurals[key]
        // 该语言没给这一档（如日语表里只有 OTHER）就退到 OTHER，再退到简体中文。
        val text =
            variants?.get(form)
                ?: variants?.get(PluralForm.OTHER)
                ?: fallback.plurals[key]?.get(PluralForm.OTHER)
                ?: key.name
        // 全部 31 条复数文案的 `{0}` 都是数量本身，所以调用点只传 count 不传 vararg
        // 时**直接用 count 顶上**。不补的话界面会渲染出字面量「共 {0} 条」，
        // 而失败症状是「找不到这个节点」，报错会把人引向「文案改了」而不是「少传了参数」。
        return substitute(text, if (args.isEmpty()) arrayOf<Any?>(count) else args)
    }

    /** 供完整性测试用：本表实际收录的键集合（不含降级来源）。 */
    internal fun declaredTextKeys(): Set<StrKey> = texts.keys

    /** 供完整性测试用：本表实际收录的复数键集合。 */
    internal fun declaredPluralKeys(): Set<PluralStrKey> = plurals.keys

    /** 供完整性测试用：某条复数文案在本语言下声明了哪些类别。 */
    internal fun declaredForms(key: PluralStrKey): Set<PluralForm> = plurals[key]?.keys.orEmpty()

    /** 回落简体中文的同一键。 */
    private fun fallbackText(key: StrKey): String = fallback.texts[key] ?: key.name

    override fun toString(): String = "Strings(${language.tag})"

    companion object {
        /**
         * 空表（构造期占位）。取任何键都返回键名，不会崩。
         *
         * 声明在 [fallback] **之前** —— Kotlin 的 `val` 必须先声明后引用，
         * 而 [fallback] 的初值就是它。两者互不递归，故这个顺序是安全的。
         */
        internal val EMPTY: Strings =
            Strings(AppLanguage.DEFAULT, emptyMap(), emptyMap())

        /**
         * 简体中文表 —— **降级的终点**，也是所有译文的对照源。
         *
         * 由 [StringsRegistry] 在六张表都装载完之后回填一次。
         */
        internal var fallback: Strings = EMPTY

        private val PLACEHOLDER = Regex("""\{(\d+)}""")

        /**
         * `{0}` → 第一个参数。越界的下标**原样保留**，不补空串 ——
         * 补空串会让「忘了传参数」变成一句读起来通顺但意思错了的话，
         * 原样保留则一眼看得出是哪里漏了。
         */
        private fun substitute(
            text: String,
            args: Array<out Any?>,
        ): String {
            if (args.isEmpty()) return text
            return PLACEHOLDER.replace(text) { match ->
                val index = match.groupValues[1].toInt()
                if (index in args.indices) args[index].toString() else match.value
            }
        }
    }
}

/**
 * 六张文案表的注册处 —— 语言到 [Strings] 的唯一映射。
 *
 * 刻意做成「启动时一次性装载 + 全局共享」而不是「每次读都现查」：
 * 文案表是纯编译期常量，切语言只该触发一次重组，不该每次重组都重建 Map。
 */
object StringsRegistry {
    private val tables: Map<AppLanguage, Strings> by lazy {
        val built =
            AppLanguage.entries.associateWith { language ->
                Strings(
                    language = language,
                    texts = Texts.forLanguage(language),
                    plurals = Plurals.forLanguage(language),
                )
            }
        // 降级终点 = 简体中文表。必须在六张表都建完之后再回填，
        // 否则构造 ZH_HANS 那一刻 fallback 还是 EMPTY。
        Strings.fallback = built.getValue(AppLanguage.ZH_HANS)
        built
    }

    /** 取指定语言的文案表。 [AppLanguage] 是封闭枚举，故永远取得到。 */
    fun of(language: AppLanguage): Strings = tables.getValue(language)

    /** 当前系统语言对应的文案表 —— 首次启动、设置未落盘时使用。 */
    fun systemDefault(): Strings = of(AppLanguage.detectSystemLanguage())

    /** 全部六张表 —— 供完整性测试遍历。 */
    fun all(): List<Strings> = AppLanguage.entries.map(::of)
}
