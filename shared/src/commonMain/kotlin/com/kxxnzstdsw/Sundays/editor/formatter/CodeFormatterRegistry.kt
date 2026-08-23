package com.kxxnzstdsw.Sundays.editor.formatter

/**
 * 全局 formatter 注册表 — 按 [CodeFormatter.languageId] 索引。
 *
 * 与 [com.kxxnzstdsw.Sundays.editor.CodeLanguageRegistry] 对称设计：
 * - 启动时调用 [register] 注册内置 formatter
 * - UI 通过 `formatterRegistry.get(languageId)` 获取对应语言的 formatter
 * - 返回 `null` 表示该语言没有可用 formatter（UI 显示"格式化"按钮为 disabled）
 *
 * 注意：formatter 是**可选的** — 同一 language 不一定有 formatter，反之亦然。
 */
object CodeFormatterRegistry {

    private val lock = Any()
    private val formatters = mutableMapOf<String, CodeFormatter>()

    fun register(formatter: CodeFormatter) {
        synchronized(lock) {
            formatters[formatter.languageId] = formatter
        }
    }

    fun get(languageId: String): CodeFormatter? {
        synchronized(lock) {
            return formatters[languageId]
        }
    }

    fun unregister(languageId: String) {
        synchronized(lock) {
            formatters.remove(languageId)
        }
    }

    fun all(): List<CodeFormatter> {
        synchronized(lock) {
            return formatters.values.sortedBy { it.languageId }
        }
    }

    fun clear() {
        synchronized(lock) {
            formatters.clear()
        }
    }
}
