package com.langbiantianya.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 六份文案表的**一致性契约**。
 *
 * ## 为什么这类测试比任何单条翻译的「读起来对不对」更值钱
 *
 * 文案表会随开发不断增删键。六份表、六千行，任何一次改动都可能只改其中三份，
 * 而漏掉的那几份**在开发机上完全看不出来** —— 运行时降级链会把缺失的键悄悄换成
 * 简体中文，于是「德语界面里有一处中文」这种问题要到用户截图才暴露。
 *
 * 本文件把「六份表必须同步」这件事变成编译后立刻可见的红：
 *
 * | 断言 | 拦住的失败 |
 * |---|---|
 * | 全键覆盖 | 加了键却忘了翻译 → 界面混入中文 |
 * | 占位符下标集合一致 | 译文少写 `{1}` → 句子缺参数，且**编译与运行都不报错** |
 * | 非中文表无 CJK | 整条忘了翻译，只在审 diff 时才可能发现 |
 * | 复数类别齐备 | 俄语缺 `FEW` → `2 файлов` |
 * | 值非空白 | 复制粘贴漏了内容 → 界面上一个空按钮 |
 */
class StringsIntegrityTest {

    private val tables = StringsRegistry.all()

    private val zhHans = StringsRegistry.of(AppLanguage.ZH_HANS)

    // =========================================================================
    // 覆盖
    // =========================================================================

    @Test
    fun `六种语言都覆盖了全部 StrKey`() {
        val expected = StrKey.entries.toSet()
        tables.forEach { table ->
            val missing = expected - table.declaredTextKeys()
            if (missing.isNotEmpty()) {
                fail(
                    "${table.language.tag} 缺 ${missing.size} 条：\n" +
                        missing.joinToString("\n") { "  $it" }
                )
            }
        }
    }

    @Test
    fun `六种语言都覆盖了全部 PluralStrKey`() {
        val expected = PluralStrKey.entries.toSet()
        tables.forEach { table ->
            val missing = expected - table.declaredPluralKeys()
            if (missing.isNotEmpty()) {
                fail(
                    "${table.language.tag} 缺 ${missing.size} 条复数文案：\n" +
                        missing.joinToString("\n") { "  $it" }
                )
            }
        }
    }

    /** 文案表为空时所有断言都会「假绿」，故单独钉一条非空。 */
    @Test
    fun `简体中文表非空`() {
        assertTrue(StrKey.entries.size > 100, "StrKey 只有 ${StrKey.entries.size} 条，明显没填完")
        assertEquals(
            StrKey.entries.size,
            zhHans.declaredTextKeys().size,
            "简体中文表条目数必须等于枚举成员数",
        )
    }

    // =========================================================================
    // 占位符
    // =========================================================================

    /**
     * 占位符下标集合必须跨语言**完全一致**。
     *
     * 只比个数是不够的 —— 译文把 `{0} 行` 写成 `{1} 行`，个数一样但参数全错位，
     * 运行时会显示一个空值或别的参数。故比的是**下标集合**。
     */
    @Test
    fun `占位符下标集合跨语言一致`() {
        val problems = mutableListOf<String>()
        StrKey.entries.forEach { key ->
            val reference = zhHans[key].placeholders()
            tables.forEach { table ->
                val actual = table[key].placeholders()
                if (actual != reference) {
                    problems += "${table.language.tag} 的 $key：占位符 $actual ≠ 简体 $reference"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("占位符不一致：\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `复数文案的占位符下标集合跨语言一致`() {
        val problems = mutableListOf<String>()
        PluralStrKey.entries.forEach { key ->
            val reference = zhHans.plural(key, 2).placeholders()
            tables.forEach { table ->
                val actual = table.plural(key, 2).placeholders()
                if (actual != reference) {
                    problems += "${table.language.tag} 的 $key：占位符 $actual ≠ 简体 $reference"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("复数文案占位符不一致：\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    // =========================================================================
    // 未翻译残留
    // =========================================================================

    /**
     * 拉丁 / 西里尔字母的语言（英、德、俄）里**不许出现汉字**。
     *
     * 这是抓「忘了翻译」最省事的办法：漏一条译文的界面表现是「德语界面里一处中文」，
     * 而这条断言让它在 CI 就红。
     *
     * ## 为什么把日语排除在这条之外（它曾经误报 30+ 条）
     *
     * 日语**大量使用汉字** —— `確定` `削除` `編集` `保存` `設定` `失敗` 全是地道日语，
     * 一半以上的日文正文由汉字组成。「含汉字」对日语不但不是错误，恰恰是正确日语的标志。
     * 把日语一起扫，会把最好的译文判成「未翻译」。日语改用
     * [日语译文不含简体字形] 那条更精确的规则。
     *
     * 中文标点（`，` `。` `：`）也一并排除：非中文译文里混入一个全角逗号是排版瑕疵，
     * 值得顺手改，但不该让整条测试红掉 —— 那会让人养成「忽略这个测试」的习惯。
     */
    @Test
    fun `非中文的拉丁西里尔语言里没有汉字残留`() {
        val offenders = mutableListOf<String>()
        tables.filter { it.language in NON_CJK_LANGUAGES }.forEach { table ->
            StrKey.entries.forEach { key ->
                val text = table[key]
                if (text.any { it in CJK_RANGE }) offenders += "${table.language.tag}/$key = $text"
            }
        }
        if (offenders.isNotEmpty()) {
            fail("以下条目疑似未翻译：\n" + offenders.take(40).joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `非中文的拉丁西里尔语言的复数文案里没有汉字残留`() {
        val offenders = mutableListOf<String>()
        tables.filter { it.language in NON_CJK_LANGUAGES }.forEach { table ->
            PluralStrKey.entries.forEach { key ->
                val text = table.plural(key, 2)
                if (text.any { it in CJK_RANGE }) offenders += "${table.language.tag}/$key = $text"
            }
        }
        if (offenders.isNotEmpty()) {
            fail("以下复数条目疑似未翻译：\n" + offenders.joinToString("\n") { "  $it" })
        }
    }

    // =========================================================================
    // 未翻译残留（简繁 / 日）
    // =========================================================================

    /**
     * 繁体与日语里不许出现**简体字形**。
     *
     * ## 为什么不能沿用「含汉字即未翻译」
     *
     * 繁中和日语都与中文共享大量汉字，判据必须是「**字形**对不对」而不是「有没有汉字」。
     *
     * ## 黑名单怎么来的
     *
     * [SIMPLIFIED_ONLY] 收的是**简体独有、现代日文与现代繁体都不用**的字：连/设/语/读/写…
     *
     * 刻意**排除**两类容易误伤的字：
     *
     * | 看似简体 | 为什么不收 |
     * |---|---|
     * | 学 実 気 図 対 声 売 国 | 这些是日语「新字体」，日文里就是长这样的（学・実・気・図） |
     * | 点 午 白 早 書 | 简繁日**完全同形**，不是字形问题的判据 |
     *
     * 误报的代价是真译文被判红，久而久之就没人看这条测试了 —— 所以宁可漏收几个，
     * 也不能让黑名单误伤正确日语。
     */
    @Test
    fun `繁体与日语里没有简体字形`() {
        val offenders = mutableListOf<String>()
        tables.forEach { table ->
            val blacklist = when (table.language) {
                AppLanguage.ZH_HANT -> SIMPLIFIED_ONLY_ZH_HANT
                AppLanguage.JA -> SIMPLIFIED_ONLY_JA
                else -> return@forEach
            }
            StrKey.entries.forEach { key ->
                val hit = table[key].filter { it in blacklist }
                if (hit.isNotEmpty()) offenders += "${table.language.tag}/$key 含简体字「$hit」= ${table[key]}"
            }
        }
        if (offenders.isNotEmpty()) {
            fail("以下条目含简体字形：\n" + offenders.take(40).joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `繁体与日语的复数文案里没有简体字形`() {
        val offenders = mutableListOf<String>()
        tables.forEach { table ->
            val blacklist = when (table.language) {
                AppLanguage.ZH_HANT -> SIMPLIFIED_ONLY_ZH_HANT
                AppLanguage.JA -> SIMPLIFIED_ONLY_JA
                else -> return@forEach
            }
            PluralStrKey.entries.forEach { key ->
                val text = table.plural(key, 2)
                val hit = text.filter { it in blacklist }
                if (hit.isNotEmpty()) offenders += "${table.language.tag}/$key 含简体字「$hit」= $text"
            }
        }
        if (offenders.isNotEmpty()) {
            fail("以下复数条目含简体字形：\n" + offenders.joinToString("\n") { "  $it" })
        }
    }

    // =========================================================================
    // 值的基本卫生
    // =========================================================================

    @Test
    fun `没有空白或纯占位符的值`() {
        val offenders = mutableListOf<String>()
        tables.forEach { table ->
            StrKey.entries.forEach { key ->
                val text = table[key]
                if (text.isBlank()) offenders += "${table.language.tag}/$key 是空白"
            }
            PluralStrKey.entries.forEach { key ->
                PluralForm.entries.forEach { form ->
                    val declared = table.declaredForms(key)
                    if (form in declared && table.plural(key, sampleCountFor(form)).isBlank()) {
                        offenders += "${table.language.tag}/$key[$form] 是空白"
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail("空文案：\n" + offenders.joinToString("\n") { "  $it" })
        }
    }

    /**
     * 拉丁 / 西里尔语言的译文不能与简体中文**逐字相同**。
     *
     * 判据只在「简体值含汉字」时才成立 —— 否则 `sundays`、`JDBC`、`CSV` 这些各语言
     * 本就该原样保留的标识会被整片误报。已排除的还有日/繁：它们的正确译文本来就
     * 大量与简体同形（`保存` `通知` `停止` 在日语里一字不差），拿它们判「照抄」是错的。
     */
    @Test
    fun `拉丁西里尔语言的译文不能整条照抄简体`() {
        val offenders = mutableListOf<String>()
        tables.filter { it.language in NON_CJK_LANGUAGES }.forEach { table ->
            StrKey.entries.forEach { key ->
                val simplified = zhHans[key]
                val actual = table[key]
                if (actual == simplified && simplified.any { it in CJK_RANGE }) {
                    offenders += "${table.language.tag}/$key 照抄了简体「$simplified」"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail("以下条目疑似整条漏译：\n" + offenders.take(40).joinToString("\n") { "  $it" })
        }
    }

    // =========================================================================
    // 复数类别齐备
    // =========================================================================

    /**
     * 每种语言必须声明**它规则上用得到的**全部类别。
     *
     * 少了哪一档，界面上就会出现那种语言的「别扭形态」：俄语缺 FEW → `2 файлов`，
     * 英语缺 ONE → `1 rows`。这类错误在截图里一眼可见，但没人会去查文案表。
     */
    @Test
    fun `复数类别按各语言规则齐备`() {
        val problems = mutableListOf<String>()
        tables.forEach { table ->
            val required = when (table.language) {
                AppLanguage.RU -> setOf(PluralForm.ONE, PluralForm.FEW, PluralForm.MANY)
                AppLanguage.EN, AppLanguage.DE -> setOf(PluralForm.ONE, PluralForm.OTHER)
                AppLanguage.ZH_HANS, AppLanguage.ZH_HANT, AppLanguage.JA -> setOf(PluralForm.OTHER)
            }
            PluralStrKey.entries.forEach { key ->
                val declared = table.declaredForms(key)
                val missing = required - declared
                if (missing.isNotEmpty()) {
                    problems += "${table.language.tag}/$key 缺 ${missing.joinToString { it.name }}"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("复数类别缺失：\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    /** 取一个会命中该类别的样本数量，用于检查该档文案非空白。 */
    private fun sampleCountFor(form: PluralForm): Int = when (form) {
        PluralForm.ONE -> 1
        PluralForm.FEW -> 2
        PluralForm.MANY -> 5
        PluralForm.OTHER -> 3
    }

    companion object {
        /** 拉丁 / 西里尔字母的语言 —— 「含汉字 = 未翻译」在这几种语言上成立。 */
        private val NON_CJK_LANGUAGES = setOf(AppLanguage.EN, AppLanguage.RU, AppLanguage.DE)

        /** 用汉字书写的语言 —— 对它们只能查「字形」，不能查「有没有汉字」。 */
        private val SIMPLIFIED_FORM_CHECKED = setOf(AppLanguage.ZH_HANT, AppLanguage.JA)

        /** CJK 统一汉字区段（不含扩展区 —— 六种目标语言用不到）。 */
        private val CJK_RANGE = '一'..'鿿'

        /**
         * 简体独有、**现代繁体不用**的字 —— 用于检查繁体表。
         *
         * 只收高置信度条目：宁可漏掉几个真正的问题，也不能误伤正确译文 ——
         * 一条会误报的断言，跑几次之后就不会再有人看它的输出了。
         */
        private val SIMPLIFIED_ONLY_ZH_HANT: Set<Char> =
            ("连设语读写说课谁请调议论讲许认识记录终结经济贸银钱铁门间闻问开关闭进远运达过还边这" +
                "样时东车马鸟鱼风飞长电视听词译证变换图团园圆爱节艺苏药虑处备复头夹夺奖妇孙宁宝实审对寻" +
                "导层属岁币师带帮广庆库应废异张归当彻怀态战户执扩扫扬扰抢护报担拟挂换据摄摆摇敌数斋斩" +
                "昼显晒暂术朴机杀杂权条来杨杰极构柜标栋栏树桥检楼欢欧残汇汉汤沟泪济浓测涂涛润渐温湾满滨" +
                "灭灯灵灾炉炼烦烧热焕牵犹独狱猎猪猫献环现玛画疗疯监盘盖盗睁矫矿码砖础碍礼祸离秃种积称稳穷" +
                "窃窍窑窜窝竖竞笔笼筑筛签简篮粮紧红纤约级纪纬纯纱纲纳纵纷纸纹纺线练组细织绍绑绒结绕绘给络" +
                "绝统继绩绪续绳维绵综绿缓编缘缚缝缩网罗罚罢职联肃肠股肤肿胁胆胜胶脉脏脑脚脱腾舰舱艰艳荐莲莹" +
                "菲蒙蓝虏虫蚀蚁蜡蝇蝉补观规觅览觉触誉订认讨让训访评试诗诚询误诸谅谋谐谓谢谨谱贝负贡财责贤" +
                "败货质贩贪贫购贮贯贱贴贵贷费贺贼贿赁资赋赌赏赔赖赛赞赠赵赶趋跃践踪轨转轮软轻载较辆辈迟适选逊" +
                "递逻遗邓郑邻酝酱释里针钉钓钙钝钟钢钥钦钩铜铝铸铺链销锁锅锋错锡锣键镇闪闲闷闹阀阁阅队阳阴阵阶" +
                "阻附际陆陈险隐难雏雾静韦韩页顶项顺须顽顾顿颁颂预领颇颈频颗题颜额飘饭饮饱饲饶饼馆驾验骂骄骗鲁鲜" +
                "鸡鸣鸦鸭鸿鹅鹰麦黄齐齿龙" +
                // 体 / 类 / 杠 是补录的：都是简体独有（繁体作 體 / 類 / 槓），置信度高，
                // 且日语把 体 用在「本体」里合法 —— 已被 VALID_IN_JAPANESE 豁免，
                // 所以加进繁体表不会波及日语检查。
                "体类杠"
                ).toSet()

        /**
         * 现代日文里**本来就长这样**的字 —— 日语检查必须放过。
         *
         * ## 为什么日语要单独一张排除表
         *
         * 日语字体在 1981 年「字体整備」之后大量采用**新字体**，它们与繁体不同、
         * 却与简体同形。日语里这些就是正确写法，拿简体字表去判会误报：
         *
         * | 字 | 繁体 | 简体 | 日语实际用法 |
         * |---|---|---|---|
         * | 表 | 表 | 表 | 表 —— 三体同形 |
         * | 数 | 數 | 数 | 数 —— 日语标准字形 |
         * | 画 | 畫 | 画 | 画 —— 日语标准字形 |
         * | 国 | 國 | 国 | 国 —— 新字体 |
         * | 学 | 學 | 学 | 学 —— 新字体 |
         * | 黄 | 黃 | 黄 | 黄 —— 新字体 |
         * | 猫 | 貓 | 猫 | 猫 —— 新字体 |
         *
         * 正确日语被这条测试判红，后果比漏掉几条真正的简体字严重得多：
         * 它会让人直接忽略整条规则。
         */
        private val VALID_IN_JAPANESE: Set<Char> =
            ("表条数理画残温湾灯炉独猫碍礼称窃胆脚脱蝉誉阻附静麦黄国学実気図対声売点会个方干争体从众免兔" +
                "当里併")
                .toSet()

        /** 日语用简体字表 = 全量简体字表 − 日语合法字。 */
        private val SIMPLIFIED_ONLY_JA: Set<Char> =
            SIMPLIFIED_ONLY_ZH_HANT - VALID_IN_JAPANESE

        private val PLACEHOLDER = Regex("""\{(\d+)}""")

        private fun String.placeholders(): Set<Int> =
            PLACEHOLDER.findAll(this).map { it.groupValues[1].toInt() }.toSet()
    }
}
