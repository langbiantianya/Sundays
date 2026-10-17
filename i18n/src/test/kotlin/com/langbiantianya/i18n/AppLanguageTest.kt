/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AppLanguage] 的匹配规则 —— 尤其是「繁体不能只看 language」与「落盘值非法要返回 null」。
 *
 * 这两处都是**静默出错**的形态：繁体判错时界面只是「看起来有点怪」，非法 tag 被悄悄
 * 换成默认语言时用户更是无从察觉。都属于只有钉住断言才拦得住的回归。
 */
class AppLanguageTest {
    // =========================================================================
    // 系统语言 → 应用语言
    // =========================================================================

    @Test
    fun `六种支持的语言各自认得自己`() {
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.US))
        assertEquals(AppLanguage.RU, AppLanguage.fromLocale(Locale.forLanguageTag("ru-RU")))
        assertEquals(AppLanguage.JA, AppLanguage.fromLocale(Locale.JAPANESE))
        assertEquals(AppLanguage.DE, AppLanguage.fromLocale(Locale.GERMAN))
    }

    /**
     * 繁体判定不能只看 `language` —— `zh-TW` 与 `zh-CN` 的 language 都是 `zh`。
     *
     * 这条是整个枚举里最容易写错的一处：`Locale("zh","TW")` 在真实机器上**不带 script**，
     * 只看 `script == "Hant"` 会把台湾用户发成简体界面，而且没有任何报错。
     */
    @Test
    fun `中文按地区分简繁 - 不看 script`() {
        // 只带地区码的常见形态（Windows 中文版的默认 locale 就是这样）
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale("zh", "TW")))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale("zh", "HK")))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale("zh", "MO")))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale("zh", "CN")))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE))
    }

    /** 带 script 的形态（macOS / ICU 风格）也要判对 —— script 与地区码是「或」关系。 */
    @Test
    fun `中文按 script 分简繁 - 不看地区码`() {
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hans")))
    }

    /**
     * 粤语 / 闽南语的书写系统是繁体 —— 不能按「非 zh → 回落到简体中文」处理。
     *
     * 这类用户的界面必须是繁体：那不是「换个语言」，那是同一种语言的另一种写法。
     */
    @Test
    fun `粤语与闽南语归繁体而不是默认简体`() {
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.forLanguageTag("yue-HK")))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.forLanguageTag("nan-TW")))
    }

    /**
     * 不支持的语言一律回落中文（**不是**英文）。
     *
     * 需求原文：「如果系统语言是不支持的默认选中中文」。同时把「地区码不影响语言判定」
     * 钉住 —— `de-AT`（奥地利）仍是德语，`ja-JP` 仍是日语。
     */
    @Test
    fun `不支持的语言回落简体中文`() {
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.FRANCE))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.KOREA))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.ROOT))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale("xx", "YY")))
    }

    /** 地区码不参与判定 —— 只取 language，否则 `de-AT` 会掉进默认分支。 */
    @Test
    fun `地区码不影响语言判定`() {
        assertEquals(AppLanguage.DE, AppLanguage.fromLocale(Locale.forLanguageTag("de-AT")))
        assertEquals(AppLanguage.DE, AppLanguage.fromLocale(Locale.forLanguageTag("de-CH")))
        assertEquals(AppLanguage.JA, AppLanguage.fromLocale(Locale.forLanguageTag("ja-JP")))
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.forLanguageTag("en-GB")))
        assertEquals(AppLanguage.RU, AppLanguage.fromLocale(Locale.forLanguageTag("ru-RU")))
        // `Locale.US` 的 language 是 "en"，地区码是 "US" —— 必须判成英文而不是默认中文。
        // 这条钉住「只取 language、不看地区」这个实现选择：若哪天改成也看地区码，它会红。
        assertEquals(AppLanguage.EN, AppLanguage.fromLocale(Locale.US))
    }

    /** `languageTag` 大小写混写是真实存在的 —— macOS 上会出现 `zh-hant-tw`。 */
    @Test
    fun `语言与地区码大小写不敏感`() {
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromLocale(Locale.forLanguageTag("ZH-HANT-TW")))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromLocale(Locale.forLanguageTag("ZH-Hans-CN")))
        assertEquals(AppLanguage.DE, AppLanguage.fromLocale(Locale.forLanguageTag("DE-de")))
    }

    /** 探测入口不能抛 —— 拿不到有效 locale 时也要给出可用答案。 */
    @Test
    fun `系统语言探测永远给出六种语言之一`() {
        assertTrue(
            AppLanguage.entries.contains(AppLanguage.detectSystemLanguage()),
            "探测结果必须落在六种语言之内",
        )
    }

    // =========================================================================
    // 落盘值 → 应用语言
    // =========================================================================

    @Test
    fun `落盘值原样往返`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTagOrNull(language.persistedValue()))
        }
    }

    /**
     * 设置文件是用户能手改的文本，故接受多种写法。
     *
     * 尤其 `_` 与 `-`：Windows 区域的「显示语言」下拉里就是 `zh_CN` 这种下划线写法，
     * 用户从那里抄进文件是很现实的路径。
     */
    @Test
    fun `落盘值接受前缀与下划线写法`() {
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromTagOrNull("zh"))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromTagOrNull("zh-CN"))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromTagOrNull("zh_CN"))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromTagOrNull("zh-TW"))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromTagOrNull("zh_Hant"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTagOrNull("en-US"))
        assertEquals(AppLanguage.DE, AppLanguage.fromTagOrNull("de_DE"))
    }

    /**
     * 非法值必须返回 `null`，**不能**回落到默认语言。
     *
     * 读设置时若把「不认识的值」悄悄换成简体中文，用户会发现自己的选择被篡改却无处可查；
     * 返回 `null` 让调用方把它当「字段缺失」，新机器因此还能拿到系统语言探测的初值。
     */
    @Test
    fun `非法落盘值返回 null 而不是默认语言`() {
        assertNull(AppLanguage.fromTagOrNull(""))
        assertNull(AppLanguage.fromTagOrNull("   "))
        assertNull(AppLanguage.fromTagOrNull("klingon"))
        assertNull(AppLanguage.fromTagOrNull("zh-CN-x-私有变体"))
    }

    /**
     * `zh` 不能被 `ZH_HANT` 的前缀规则抢走 —— 两个中文成员的 tag 只差 `-Hans` / `-Hant`。
     *
     * 前缀匹配要求前缀后紧跟 `-`，否则 `zh` 会命中 `zh-Hant` 之外的一切。
     * 同时钉住「`zh-Hans-CN` 归简体、`zh-Hant-TW` 归繁体」，防止有人把前缀匹配写成
     * `startsWith` 而让简体被繁体抢走。
     */
    @Test
    fun `前缀匹配不会让简体与繁体互相误配`() {
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromTagOrNull("zh-Hans-CN"))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromTagOrNull("zh-Hant-TW"))
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.fromTagOrNull("zh-Hans"))
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromTagOrNull("zh-Hant"))
    }

    /** 语言选择器显示母语名 —— 母语名必须**互不相同**，否则选择器里有两项长得一样。 */
    @Test
    fun `母语名两两不同`() {
        assertEquals(
            AppLanguage.entries.size,
            AppLanguage.entries
                .map { it.nativeName }
                .toSet()
                .size,
            "母语名重复会让语言选择器里出现两项看起来一样的条目",
        )
    }
}
