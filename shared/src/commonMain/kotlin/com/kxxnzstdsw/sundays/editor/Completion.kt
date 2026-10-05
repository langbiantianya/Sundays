package com.kxxnzstdsw.sundays.editor

/**
 * 补全候选 —— 编辑器「提示」功能的数据模型。
 *
 * ## 为什么独立于 [CodeToken]
 *
 * 高亮回答「这个 token 是什么」，补全回答「此刻可以填什么」。前者是被动的（扫一遍已有文本），
 * 后者是主动的（要结合光标位置与用户意图），两者生命周期与数据量都不同，硬塞进一套模型只会互相拖累。
 *
 * ## 大小写策略由语言自己定
 *
 * SQL 关键字大小写不敏感（`select` = `SELECT`），补全时**忽略大小写**匹配、插入规范的大写形式 ——
 * 这与 `SqlFormatter` 的关键字大写行为一致，输入 `sel` 补成 `SELECT` 正是用户想要的。
 *
 * Lua 关键字大小写敏感（`If` ≠ `if`），若忽略大小写把 `Pri` 补成 `print`，等于**篡改用户标识符的语义**。
 * 因此 Lua 走**大小写敏感**匹配。这条策略不进本文件，由各自语言的
 * [CodeLanguage.completionCandidates] 决定 —— 谁拥有大小写规则，谁说了算。
 */

/** 候选类别 —— 决定弹层里的图标与配色。 */
enum class CompletionKind(val displayName: String) {
    KEYWORD("关键字"),
    TYPE("类型"),
    BUILTIN("函数"),
}

/**
 * 一条补全候选。
 *
 * @param label 插入文本的**规范形式**（SQL 为大写、Lua 为小写），接受后原样替换光标前的词
 * @param kind 类别，决定弹层展示
 * @param detail 补充说明（可空）—— 弹层宽度有限，非空时才显示
 */
data class CompletionItem(
    val label: String,
    val kind: CompletionKind,
    val detail: String? = null,
)

/** 默认最多同时展示的候选条数。 */
const val DEFAULT_COMPLETION_LIMIT: Int = 8

/**
 * 触发补全所需的最小前缀长度。
 *
 * 定 2 而不是 1：单字符前缀几乎命中整个语言（`a` → `AND` / `ADD` / `AVG` ...），
 * 弹层刚出现就铺满屏幕，反而挡视线。2 字符起命中率才有意义。
 */
const val MIN_COMPLETION_PREFIX: Int = 2

/**
 * 取光标前的「当前词」—— 也就是补全的前缀。
 *
 * **词字符刻意限定为 ASCII**（`a-zA-Z0-9_`），而不是 `Char.isLetterOrDigit()`。
 * 后者对中文返回 `true`，于是 `-- 查询sel` 里的前缀会算成 `查询sel` —— 中文注释把英文
 * 单词吞进前缀，补全立刻失灵。SQL / Lua 的标识符规则本来就是 ASCII 基础，这里与之一致。
 *
 * [caret] 可能越界（外部直接塞进来的 `TextFieldValue`），一律夹到 `text` 长度内，
 * 不抛异常 —— 这条函数在每次按键后都会跑。
 */
fun wordPrefixBefore(text: String, caret: Int): String {
    val pos = caret.coerceIn(0, text.length)
    return text.substring(wordBoundsAround(text, pos).first, pos)
}

/**
 * 光标所在的**整个词**的范围（半开区间 `start until end`）；光标前后都不是词字符时为空区间。
 *
 * 用**半开**区间（`until`）而不是闭区间（`..`）：闭区间下「词尾恰好在字符串末尾」会得到
 * `last == text.length`，调用方再拿 `last + 1` 去 `substring` 就会越界 —— 半开区间天然
 * 没有这个坑（空词即 `pos until pos`，`last + 1` 正好等于 `pos`）。
 *
 * [wordPrefixBefore] 只取词的前半段（用来决定「拿什么去匹配」），而接受候选时需要的是
 * 整个词 —— 光标落在词中间（`sel|ect`）时，接受 `SELECT` 应当得到 `SELECT` 而不是
 * `SELECTect`。两个需求方向不同，所以分成两个函数。
 */
fun wordBoundsAround(text: String, caret: Int): IntRange {
    val pos = caret.coerceIn(0, text.length)
    var start = pos
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = pos
    while (end < text.length && isWordChar(text[end])) end++
    return start until end
}

/** 标识符词字符 —— 与两个语言的 tokenizer 规则一致（见 [wordPrefixBefore] 的说明）。 */
private fun isWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/**
 * 用候选替换光标所在的**整个词**，返回「新文本 + 新光标位置」。
 *
 * 替换范围取 [wordBoundsAround]（含光标**之后**的词字符）：光标落在词中间时，
 * 若只替换前半段会留下 `sel` + `ect` → `SELECTect` 这种半吊子结果。
 */
fun applyCompletion(text: String, caret: Int, item: CompletionItem): Pair<String, Int> {
    val pos = caret.coerceIn(0, text.length)
    val bounds = wordBoundsAround(text, pos)
    val newText = text.substring(0, bounds.first) + item.label + text.substring(bounds.last + 1)
    return newText to (bounds.first + item.label.length)
}

/**
 * 从调用方传入的额外候选里挑出匹配 [prefix] 的前 [limit] 条。
 *
 * **大小写敏感**，与 [LuaLanguage] 的关键字策略一致。造数宿主函数（`insert` / `random_int`）
 * 是真正的大小写敏感全局 —— 把 `Insert` 补成 `insert` 会改变用户脚本的语义，
 * 属于制造 bug 而非帮忙。理由与 `selectCompletions` 的 `caseSensitive = true` 同源。
 *
 * 传入列表**保持原顺序**（不重排）：清单是按「最常用 → 最专用」人工排的，
 * 那个顺序比按字母排更有信息量。
 */
internal fun selectExtras(
    prefix: String,
    extras: List<CompletionItem>,
    limit: Int,
): List<CompletionItem> {
    if (prefix.isEmpty() || limit <= 0) return emptyList()
    val out = ArrayList<CompletionItem>(minOf(limit, 8))
    for (item in extras) {
        if (!item.label.startsWith(prefix)) continue
        out.add(item)
        if (out.size >= limit) break
    }
    return out
}

/**
 * 构造候选池并**预先排好序**，使过滤只需一次线性扫描。
 *
 * 排序键：类别（[CompletionKind] 声明序 = 关键字 → 类型 → 函数）→ 标签长度 → 字典序。
 * 先按类别再按长度是刻意的：`SELECT` 应该排在 `SELECTIVITY` 前面，
 * 而同类别里短词更可能是用户真正想要的（输入 `co` 时 `COLUMN` 该赢过 `COLLATE`）。
 *
 * 三个参数组按传入顺序决定类别优先级，因此**调用方无需再排序**。
 */
internal fun buildCompletionPool(
    vararg groups: Pair<CompletionKind, Set<String>>,
): List<CompletionItem> {
    val out = ArrayList<CompletionItem>()
    for ((kind, words) in groups) {
        for (w in words) out.add(CompletionItem(w, kind))
    }
    // sortedWith 是稳定排序，类别序即 groups 的传入序（键里已显式带 kind.ordinal，不依赖稳定性）
    out.sortWith(compareBy({ it.kind.ordinal }, { it.label.length }, { it.label }))
    return out
}

/**
 * 从 [pool] 里挑出匹配 [prefix] 的前 [limit] 条。
 *
 * **池已排好序**（见 [buildCompletionPool]），所以这里只做过滤 + 截断，不再排序 ——
 * 每次按键都会调用，省下的排序开销是实打实的。
 */
internal fun selectCompletions(
    pool: List<CompletionItem>,
    prefix: String,
    caseSensitive: Boolean,
    limit: Int,
): List<CompletionItem> {
    if (prefix.isEmpty() || limit <= 0) return emptyList()
    val out = ArrayList<CompletionItem>(minOf(limit, 8))
    for (item in pool) {
        if (!item.label.startsWith(prefix, ignoreCase = !caseSensitive)) continue
        out.add(item)
        if (out.size >= limit) break
    }
    return out
}
