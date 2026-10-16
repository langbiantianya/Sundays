package com.langbiantianya.i18n

import com.langbiantianya.i18n.tables.textsDe
import com.langbiantianya.i18n.tables.textsEn
import com.langbiantianya.i18n.tables.textsJa
import com.langbiantianya.i18n.tables.textsRu
import com.langbiantianya.i18n.tables.textsZhHant
import com.langbiantianya.i18n.tables.textsZhHans

/**
 * 普通文案表的语言分派 —— 一处 `when`，六份数据文件。
 *
 * ## 为什么要拆成六个独立文件
 *
 * 每份表约 500 条，六份就是 3000 行。放进同一个文件意味着：
 * 任何一次翻译改动都会让整个文件在版本控制里变成一个大 diff，**六个语言互相干扰**，
 * 审阅时看不出「德语这轮改了哪几条」。
 *
 * 分成 `TextsZhHans.kt` / `TextsEn.kt` / … 之后，改德语只动 `TextsDe.kt`，
 * diff 干净，且并行的多个翻译任务可以各写各的文件而互不冲突。
 *
 * 分派本身刻意只有一处：新增语言时编译器会把这个 `when` 报成
 * 「不完整」（`else` 缺席），漏不掉。
 */
internal object Texts {

    fun forLanguage(language: AppLanguage): Map<StrKey, String> = when (language) {
        AppLanguage.ZH_HANS -> textsZhHans
        AppLanguage.ZH_HANT -> textsZhHant
        AppLanguage.EN -> textsEn
        AppLanguage.RU -> textsRu
        AppLanguage.JA -> textsJa
        AppLanguage.DE -> textsDe
    }
}
