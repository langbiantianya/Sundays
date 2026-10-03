package com.kxxnzstdsw.sundays.editor.formatter

import com.kxxnzstdsw.sundays.editor.TokenType

/**
 * 格式化器的**空格判定规则** —— SQL / Lua 共用。
 *
 * ## 为什么是「两向」而不是「一个标志」
 *
 * 直觉写法是维护一个 `pendingSpace: Boolean`：上一个 token 想要空格就置 `true`，
 * 输出下一个 token 时看它决定补不补。这套写法有三个必然踩中的坑：
 *
 * 1. **空白 token 会把它冲掉。** `a :: int` —— `::` 明确不要后置空格（置 `false`），
 *    但紧接着的空白 token 又把标志置回 `true`，输出成 `a:: int`。`.` `)` 同理。
 * 2. **标志只能表达一个方向。** `)` 一边要贴左（`(1)`），一边又要贴右（`) t` / `(a) = (b)`）——
 *    一个布尔量装不下。
 * 3. **标志记的是「上一个」，不记「上一个是谁」。** `(` 要不要吃空格取决于前面是
 *    函数名（`count(`）还是关键字（`IN (`），光一个布尔量无从判断。
 *
 * 所以这里改成两个**纯函数**：每个 token 分别回答「我左边贴不贴」「我右边贴不贴」，
 * 由调用方合成一条规则：
 *
 * ```
 * 两个相邻 token 之间要空格  ⟺  左 token 不贴右  且  右 token 不贴左
 * ```
 *
 * ## 附带收益：空格决策与输入空白完全无关
 *
 * 规则只看 token 身份与邻居类型，**不再看输入里原本有没有空格**。这既简化了循环，
 * 也顺手消灭了一整类「再格式化一次就好」的诡异 bug —— 那种现象的成因从来不是幂等性，
 * 而是某个标志被中途改写了。
 *
 * @param extraTightLeft 语言私有的「额外贴左标点」（SQL 无；Lua 为 `:`，方法调用 `a:b()`）
 * @param extraTightRight 语言私有的「额外贴右标点」
 */
internal object TokenSpacing {

    /**
     * 本 token 是否**贴住左邻居**（输出时不留前导空格）。
     *
     * 恒贴左的：`)` `]` `}` `,` `;` `.`（闭括号/分隔符/成员访问点）与 `::`。
     * 条件贴左的：`(` `[` `{` —— 只有跟在「可调用 / 可下标」的东西之后才贴
     * （`count(`、`t[`、`{`），跟在关键字或操作符之后要留空格（`IN (`、`= {`）。
     */
    fun bindsLeft(
        text: String,
        type: TokenType,
        prevText: String?,
        prevType: TokenType?,
        extraTightLeft: Set<String>,
    ): Boolean {
        if (type == TokenType.OPERATOR) return text == "::"
        if (type != TokenType.PUNCTUATION) return false
        if (text in ALWAYS_BINDS_LEFT || text in extraTightLeft) return true
        if (text in OPENERS) {
            // `:` 在 Lua 里是方法调用前缀（`obj:method(`），后面的 `(` 同样贴左
            return prevType in CALLABLE_TYPES || prevText in CLOSERS_OR_COLON
        }
        return false
    }

    /**
     * 本 token 是否**贴住右邻居**（下一个 token 不带前导空格）。
     *
     * 恒贴右的：`.` `::` 与开括号 `(` `[` `{` —— 所以 `f(`、`t.`、`a::` 后面都不留空格。
     * 注意 `,` `;` **不**在此列：它们要「前不留后留」，即 `a, b`。
     */
    fun bindsRight(text: String, type: TokenType): Boolean = when (type) {
        TokenType.OPERATOR -> text == "::"
        TokenType.PUNCTUATION -> text in ALWAYS_BINDS_RIGHT
        else -> false
    }

    /** 任何语言都贴左的标点。 */
    private val ALWAYS_BINDS_LEFT: Set<String> = setOf(",", ";", ")", "]", "}", ".")

    /** 任何语言都贴右的标点（`.` 两向都贴，所以同时出现在两个集合里）。 */
    private val ALWAYS_BINDS_RIGHT: Set<String> = setOf("(", "[", "{", ".")

    /** 开括号 —— 贴不贴左取决于前一个 token 是不是「可调用」的东西。 */
    private val OPENERS: Set<String> = setOf("(", "[", "{")

    /** 紧跟其后开括号要贴左的前置 token。 */
    private val CLOSERS_OR_COLON: Set<String> = setOf("]", "}", ":")

    /** 可以被调用 / 被下标的 token 类型。 */
    private val CALLABLE_TYPES: Set<TokenType> =
        setOf(TokenType.IDENTIFIER, TokenType.BUILTIN, TokenType.TYPE, TokenType.NUMBER)
}
