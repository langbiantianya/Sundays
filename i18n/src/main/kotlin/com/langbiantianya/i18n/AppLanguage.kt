/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

import java.util.Locale

/**
 * 应用支持的界面语言 —— 封闭集合，加语言就加一个枚举项。
 *
 * ## 为什么语言也是「枚举」而不是字符串
 *
 * 与主题档位（见 `UiChrome.kt` 顶部注释）同一套理由：语言是封闭集合，用字符串会让
 * 「拼错一个 tag」变成运行时静默回落到默认值，而不是编译期报错。枚举让
 * [fromTagOrNull] 之外的所有用法都**不可能**拿到非法值。
 *
 * ## 为什么默认落 [ZH_HANS] 而不是英文
 *
 * 需求明确：「系统语言不支持时默认中文」。这个选择对本项目尤其成立 ——
 * 应用名、错误提示、术语（表 / 字段 / 索引 / 视图）在中文语境下是**术语本身**
 * （`table` 在中文数据库文档里就写作「表」），而英文 UI 里这些词要占更多横向空间。
 * 英文是六种语言里唯一「几乎处处更长」的：按钮 `Save` → `保存` 变短，
 * 而 `Refresh` → `刷新` / `Cancel` → `取消` 则变长。故退化目标选中文而非英文。
 *
 * @property tag BCP-47 语言标签，**同时**是落盘值与系统语言匹配值
 * @property nativeName 母语名。语言选择器里**永远显示母语名**：
 *   一个看不懂 `Deutsch` 的人无法用搜索找到它，但看得懂 `Deutsch` 的人可能不会写。
 * @property extraTags 除 [tag] 外同样指代本语言的写法。仅供 [fromTagOrNull] 匹配，
 *   **不要**用它做前缀匹配 —— 见该方法 KDoc 里「裸 `zh` 为什么必须当别名而不是 tag」。
 */
enum class AppLanguage(
    val tag: String,
    val nativeName: String,
    private val extraTags: List<String> = emptyList(),
) {
    /** 简体中文。 */
    ZH_HANS("zh-Hans", "简体中文", extraTags = listOf("zh", "zh-CN", "zh-SG")),

    /** 繁体中文 —— 港台澳地区。 */
    ZH_HANT("zh-Hant", "繁體中文", extraTags = listOf("zh-TW", "zh-HK", "zh-MO")),

    /** 英文。 */
    EN("en", "English"),

    /** 俄语。 */
    RU("ru", "Русский"),

    /** 日语。 */
    JA("ja", "日本語"),

    /** 德语。 */
    DE("de", "Deutsch"),
    ;

    /**
     * 落盘用的稳定字符串 —— 与 [tag] 相同，单独开一个方法是为了让
     * `SettingsStorage` 的读写两侧都走**同一个**入口，将来若 tag 规则变了
     * （例如给中文补上地区码）只需改这一处。
     */
    fun persistedValue(): String = tag

    companion object {
        /** 退化目标 —— 系统语言不支持时、以及设置文件里存了非法值时都用它。 */
        val DEFAULT: AppLanguage = ZH_HANS

        /**
         * 地区码 → 繁体。港台澳三个地区默认繁体，其余中文地区默认简体。
         *
         * 注意 `MO`（澳门）：它与港台的书写习惯一致，用繁体而非简体。
         */
        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

        /**
         * 按当前 JVM 默认区域设置猜语言 —— **首次启动**引导页的初值来源。
         *
         * 直接读 [Locale.getDefault]：JDK 已经把操作系统的区域设置解析好了
         * （Windows 的「显示语言」、Linux 的 `LANG`、macOS 的系统语言都归口到这一个值），
         * 自己再去读环境变量或系统属性只会漏掉其中一两个平台。
         */
        fun detectSystemLanguage(): AppLanguage = fromLocale(Locale.getDefault())

        /**
         * [Locale] → 语言；无法识别时回落 [DEFAULT]。
         *
         * ## 为什么繁体不能只看 `language == "zh"`
         *
         * `Locale("zh", "TW")` 与 `Locale("zh", "CN")` 的 `language` 都是 `"zh"`，
         * 只看语言会把台湾用户的界面发成简体 —— 这正是本函数要单独判繁体的原因。
         * 两个判据都要看：
         *
         * | 判据 | 覆盖的系统设置 |
         * |---|---|
         * | `script == "Hant"` | 显式声明了「繁体」脚本的 locale（如 `zh-Hant-TW`） |
         * | `country ∈ {TW, HK, MO}` | 只填了地区码的常见情况（Windows 中文版默认如此） |
         *
         * 两者取**或**：Windows 上大量繁体环境只带地区码不带脚本，只判脚本会漏；
         * 反过来 macOS 会给出 `zh-Hant-HK` 这种带脚本的，只判地区码能覆盖但不够稳。
         *
         * ## 为什么粤语 / 客家话归到繁体
         *
         * `yue`（粤）与 `nan`（闽南，即所谓「台语」在台湾的日常标签）**书写系统上就是繁体**。
         * 它们的用户显然不该拿到简体界面。若一律按「非 zh → 回落到中文」处理，
         * 粤语用户会拿到简体 —— 这不是「回落到默认」，这是**回落到另一种语言**。
         *
         * @param locale 区域设置；`Locale.ROOT`（全空）等价于不支持。
         */
        fun fromLocale(locale: Locale): AppLanguage {
            val language = locale.language.lowercase(Locale.ROOT)
            return when (language) {
                "zh" -> if (isTraditional(locale)) ZH_HANT else ZH_HANS

                // 粤语与闽南语的书写系统都是繁体，见 KDoc。
                "yue", "nan" -> ZH_HANT

                "en" -> EN

                "ru" -> RU

                "ja" -> JA

                "de" -> DE

                else -> DEFAULT
            }
        }

        /** 是否繁体 —— 脚本标记与地区码取「或」，理由见 [fromLocale] 的 KDoc。 */
        private fun isTraditional(locale: Locale): Boolean =
            locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in TRADITIONAL_REGIONS

        /**
         * 解析落盘值 —— 非法输入返回 `null` 而不是默认值。
         *
         * ## 为什么不复用 [fromLocale] 那样「不认识就回落到默认值」
         *
         * 读设置文件时**必须**区分「这个用户显式选了繁体」与「这个值我不认识」。
         * 前者要老实还原，后者若悄悄变成简体中文，用户会发现自己的选择被篡改了却无处可查。
         * 判定权交给调用方：`SettingsStorage` 拿到 `null` 时按「字段缺失 → 用默认值且**不**
         * 当成用户的选择」处理，新装机器因此拿到系统语言探测的初值。
         *
         * 匹配放宽到**前缀**：接受 `zh`、`zh-CN`、`zh-Hans`、`zh_CN` 这些写法。
         * 设置文件是用户能直接打开编辑的文本，不该要求他照抄某个精确拼法。
         *
         * ## 裸 `zh` 为什么是别名而不是 tag
         *
         * 匹配分三步：tag 精确 → [extraTags] 精确 → tag 前缀。裸 `zh` 必须走第二步：
         * 若把它加成 tag，第三步的前缀规则会让 `zh-Hant-TW` 先命中它（`"zh-Hant-TW"`
         * 以 `"zh"` 开头），**繁体界面直接变简体**。它是全表里唯一一个有歧义的裸值 ——
         * CLDR 的 `likelySubtags` 也把 `zh` 补成 `zh-Hans-CN`，故归简体，与本表一致。
         *
         * 第三步要求前缀后紧跟 `-`，否则 `zh-HansCN` 这种粘连写法也会误配。
         */
        fun fromTagOrNull(raw: String): AppLanguage? {
            val normalized = raw.trim().replace('_', '-')
            if (normalized.isEmpty()) return null
            entries.firstOrNull { it.tag.equals(normalized, ignoreCase = true) }?.let { return it }
            entries
                .firstOrNull { candidate ->
                    candidate.extraTags.any { it.equals(normalized, ignoreCase = true) }
                }?.let { return it }
            // 前缀匹配：`zh-Hant-TW` / `zh-Hans-CN` / `en-US` 都能落到对应语言。
            // 顺序即 `entries` 声明序 —— 中文两个成员的 tag 只差 `Hans` / `Hant`，
            // 不会互相误配。
            return entries.firstOrNull { candidate ->
                normalized.startsWith(candidate.tag, ignoreCase = true) &&
                    normalized.length > candidate.tag.length &&
                    normalized[candidate.tag.length] == '-'
            }
        }
    }
}
