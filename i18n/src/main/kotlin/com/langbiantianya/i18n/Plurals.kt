/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

import com.langbiantianya.i18n.tables.pluralsDe
import com.langbiantianya.i18n.tables.pluralsEn
import com.langbiantianya.i18n.tables.pluralsJa
import com.langbiantianya.i18n.tables.pluralsRu
import com.langbiantianya.i18n.tables.pluralsZhHans
import com.langbiantianya.i18n.tables.pluralsZhHant

/**
 * 复数文案表的语言分派 —— 结构与 [Texts] 相同。
 *
 * 与普通表的区别：值本身是 `Map<PluralForm, String>`，因为同一个键在一种语言里
 * 要按数量给出几种写法（俄语三种、英语德语两种、中日一种）。
 */
internal object Plurals {
    fun forLanguage(language: AppLanguage): Map<PluralStrKey, Map<PluralForm, String>> =
        when (language) {
            AppLanguage.ZH_HANS -> pluralsZhHans
            AppLanguage.ZH_HANT -> pluralsZhHant
            AppLanguage.EN -> pluralsEn
            AppLanguage.RU -> pluralsRu
            AppLanguage.JA -> pluralsJa
            AppLanguage.DE -> pluralsDe
        }
}
