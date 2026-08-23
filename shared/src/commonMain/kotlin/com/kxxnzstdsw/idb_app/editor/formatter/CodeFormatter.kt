package com.kxxnzstdsw.idb_app.editor.formatter

import com.kxxnzstdsw.idb_app.editor.CodeLanguage

/**
 * 代码格式化器接口 — 把"原始代码"转换为"规范化代码"。
 *
 * **与 [CodeLanguage] 的关系**：
 * - 一个 language 通常对应一个 formatter（SQL → SqlFormatter、 Lua → LuaFormatter）
 * - 但 formatter 是**可选的** — 某些语言（如 JSON、YAML）可能选择不格式化
 * - formatter 不依赖 highlighter — 可以独立替换（如把简单空白格式化换成 prettier-style 算法）
 *
 * **扩展指南**：实现 [format]，返回格式化后的字符串。
 * 调用方负责 try/catch — formatter 失败时保留原文本。
 *
 * @see com.kxxnzstdsw.idb_app.editor.formatter.SqlFormatter
 * @see com.kxxnzstdsw.idb_app.editor.formatter.LuaFormatter
 */
interface CodeFormatter {

    /** 该 formatter 服务的语言 ID（与 [CodeLanguage.id] 对齐） */
    val languageId: String

    /**
     * 格式化代码。
     *
     * **不变量**：
     * - 入参可能为空字符串 — 返回原值
     * - 返回字符串必须是合法的格式化后代码（即"再次 format 应当是 no-op"或接近 no-op）
     * - 实现必须是**纯函数** — 不修改入参、不持有可变状态
     *
     * @param source 原始代码
     * @return 格式化后的代码（出错时返回原 [source]）
     */
    fun format(source: String): String
}
