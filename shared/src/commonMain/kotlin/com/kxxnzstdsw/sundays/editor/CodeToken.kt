package com.kxxnzstdsw.sundays.editor

/**
 * 代码文本中一个有意义的最小片段。
 *
 * 由 [CodeTokenizer] 把原始代码字符串解析为一系列 [CodeToken]，
 * 然后 [SyntaxHighlighter] 把每个 token 映射为 Compose [androidx.compose.ui.text.AnnotatedString] 样式。
 *
 * **位置语义**：
 * - [start] 是 UTF-16 code unit 索引（与 Kotlin [String.length] 一致）
 * - [end] 是开区间 — 实际范围为 `[start, end)`，长度为 `end - start`
 * - tokens 之间必须**不重叠**且**按 start 升序**排列
 *
 * @param start token 起始位置（包含）
 * @param end token 结束位置（不包含）
 * @param text 该位置的原始文本（避免外部重复 substring）
 * @param type token 的语义类型，决定高亮颜色
 */
data class CodeToken(
    val start: Int,
    val end: Int,
    val text: String,
    val type: TokenType,
) {
    init {
        require(end >= start) { "CodeToken end ($end) must be >= start ($start)" }
        require(text.length == end - start) {
            "CodeToken text length (${text.length}) must equal end - start (${end - start})"
        }
    }
}

/**
 * Token 的语义类型 — 高亮颜色、字体、字重的映射依据。
 *
 * **扩展规则**：新增语言时如果需要新 token 类型，向下追加（避免破坏既有的序列化兼容性）。
 * 高亮样式在 [com.kxxnzstdsw.sundays.editor.ui.CodeEditorTheme] 中按 type 映射颜色。
 */
enum class TokenType {
    /** 关键字 / 保留字（SQL: SELECT/INSERT/CREATE；Lua: and/or/function/end…） */
    KEYWORD,

    /** 内置函数 / 标准库函数（Lua: print/pairs/tonumber…） */
    BUILTIN,

    /** 类型名（SQL: INT/VARCHAR/DECIMAL；Lua: nil/true/false 字面量归入 [KEYWORD]） */
    TYPE,

    /** 字符串字面量（含 ' " [[ ]] 各类引号） */
    STRING,

    /** 数字字面量（整数 / 浮点 / 十六进制 / 科学计数法） */
    NUMBER,

    /** 注释（单行 / 块） */
    COMMENT,

    /** 操作符（= + - * / % == != < > 等） */
    OPERATOR,

    /** 标点（逗号、分号、括号、点等） */
    PUNCTUATION,

    /** 普通标识符（变量名、表名、列名） */
    IDENTIFIER,

    /** 空白字符 — 仅在没有合并到相邻 token 时单独出现；高亮时通常忽略样式 */
    WHITESPACE,

    /** 错误状态（未闭合的字符串、未闭合的注释等） — 高亮时通常用红色 */
    ERROR,
}
