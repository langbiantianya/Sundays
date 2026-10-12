package com.kxxnzstdsw.i18n

/**
 * CLDR 复数类别 —— 六种语言里真正用得上的那几个。
 *
 * ## 为什么必须有它
 *
 * 本应用的文案里有二十来条带数量的句子（「共 N 行」「N 个文件已写出」）。中文和日语
 * 靠**量词**而不是词形变化，`共 1 行` 和 `共 5 行` 写法完全一样；俄语却要分三种：
 *
 * ```
 * 1  файл     ← one   （1, 21, 31…）
 * 2  файла    ← few   （2~4, 22~24…）
 * 5  файлов   ← many  （0, 5~9, 11~20…）
 * ```
 *
 * 德语与英语相近，只需 one / other 两档。
 *
 * 若图省事只做「数字 + 名词」拼接，俄语界面里会出现 `5 файлов` 之外还混着 `1 файлов`、
 * `2 файлов` 这类错误 —— 这正是「一眼看出机器翻译」的典型特征。宁可多写一张规则表。
 *
 * ## 为什么只列这六个
 *
 * CLDR 一共定义八类（`ZERO` / `TWO` / …）。六种目标语言里没有任何一种用到
 * `ZERO` 或 `TWO`（那是阿拉伯语、爱沙尼亚语、威尔士语系的特征），列出来只会诱使
 * 后来者以为本实现支持它们。**封闭集合**是这个项目一贯的选择（见 `UiChrome.kt`
 * 顶部「刻意用枚举而不是布尔开关」那段注释）。
 */
enum class PluralForm {
    /** `1` —— 英语 / 德语 / 俄语的单数。 */
    ONE,

    /** 俄语的「少」：2、3、4。 */
    FEW,

    /** 俄语的「多」：0、5~9、11~20。 */
    MANY,

    /** 兜底：中文、日语只有这一类；俄语的 `1.5` 之类小数也落这里。 */
    OTHER,
}

/**
 * 数词 → 复数类别。
 *
 * ## 俄语的规则为什么长这样
 *
 * CLDR 的俄语规则（`plurals.xml`）实质是：
 *
 * | 类别 | 条件 | 例 |
 * |---|---|---|
 * | one | `n % 10 = 1` 且 `n % 100 ≠ 11` | 1, 21, 31, 101 |
 * | few | `n % 10 ∈ {2,3,4}` 且 `n % 100 ∉ {12,13,14}` | 2, 3, 4, 22 |
 * | many | 其余整数 | 0, 5~9, 11~20, 100 |
 *
 * 只判 `n % 10` 会把 `11 / 21 / 31` 全判错 —— 它们以 1 结尾但要读成 many/one 之外的量，
 * `n % 100` 那一半正是为「十一 / 二十一」这批数存在的。
 *
 * @param count 数量。**只对正整数有意义**；负数与 0 一律落 OTHER（见函数体）。
 */
fun AppLanguage.pluralForm(count: Int): PluralForm = when (this) {
    // 中文与日语没有词形变化 —— 全部 OTHER。这不是「偷懒」，是这两门语言的正确规则。
    AppLanguage.ZH_HANS, AppLanguage.ZH_HANT, AppLanguage.JA -> PluralForm.OTHER

    // 英语与德语：只有「恰好 1」是单数。注意 `count == 1` 而非 `count % 10 == 1` ——
    // 11 行在德语里是复数（11 Zeilen），写成后者会得到 `11 Zeile`。
    AppLanguage.EN, AppLanguage.DE ->
        if (count == 1) PluralForm.ONE else PluralForm.OTHER

    AppLanguage.RU -> when {
        // 负数不进 one/few/many：n % 10 在 Kotlin 里可能为负，规则表是按自然数定义的。
        count <= 0 -> PluralForm.OTHER
        count % 10 == 1 && count % 100 != 11 -> PluralForm.ONE
        count % 10 in 2..4 && count % 100 !in 12..14 -> PluralForm.FEW
        else -> PluralForm.MANY
    }
}
