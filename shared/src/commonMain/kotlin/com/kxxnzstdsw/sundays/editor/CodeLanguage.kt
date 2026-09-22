package com.kxxnzstdsw.sundays.editor

/**
 * 代码语言的静态描述。
 *
 * 一个 [CodeLanguage] 实例代表一种语言（SQL / Lua / 未来的 Python / JSON 等），
 * 提供关键字集合 + tokenize 入口；**它是 stateless 的**（所有方法都是纯函数），
 * 因此同一个实例可以被多个 [CodeEditor] 共享。
 *
 * ## 扩展指南：新增语言
 * 1. 实现本接口（例如 `class MyLanguage : CodeLanguage`）
 * 2. 重写 [id] / [displayName] / [tokenize]
 * 3. 在 [CodeLanguageRegistry] 中 `register(MyLanguage())`
 * 4. UI 层调用 `CodeEditor(text, languageId = "my-lang")`
 *
 * **不需要修改任何现有代码** —— registry 模式支持运行时注册。
 *
 * @see SqlLanguage
 * @see LuaLanguage
 */
interface CodeLanguage {

    /** 语言唯一 ID（如 `"sql"` / `"lua"` / `"python"`），UI 通过此 ID 选择语言 */
    val id: String

    /** 用户可见的语言名称（如 `"SQL"` / `"Lua"` / `"Python"`），用于下拉框显示 */
    val displayName: String

    /**
     * 把源码字符串解析为 token 序列。
     *
     * 实现要求：
     * - tokens 按 [CodeToken.start] 升序
     * - tokens 之间不重叠、覆盖整个输入（未匹配部分按 [TokenType.IDENTIFIER] 或 [TokenType.WHITESPACE] 填充）
     * - 必须能在纯 CPU 上跑完（**禁止 IO / 网络 / 全局可变状态**），
     *   以保证 [CodeEditor] 在 recompose 时能稳定调用
     */
    fun tokenize(source: String): List<CodeToken>
}

/**
 * 全局语言注册表 — 按 [CodeLanguage.id] 索引。
 *
 * **可扩展性保证**：
 * - 内置语言通过 `register(...)` 在模块加载时注册（见 `language/SqlLanguage.kt` 等）
 * - 第三方语言可在应用启动后调用 [register] 注入，**不需要修改编辑器组件源码**
 * - 注册表是线程安全的（`synchronized` 守护），适合 KMP Desktop 多协程并发调用
 * - 已注册语言可被 [unregister] 移除（便于热重载测试场景）
 *
 * 典型用法：
 * ```kotlin
 * // 启动时一次性注册所有内置语言
 * CodeLanguageRegistry.register(SqlLanguage())
 * CodeLanguageRegistry.register(LuaLanguage())
 *
 * // 编辑器使用
 * CodeLanguageRegistry.get("sql")?.let { lang ->
 *     val tokens = lang.tokenize(text)
 *     // ... 传给 SyntaxHighlighter
 * }
 * ```
 */
object CodeLanguageRegistry {

    private val lock = Any()
    private val languages = mutableMapOf<String, CodeLanguage>()

    /** 注册一种语言。若 [CodeLanguage.id] 已存在则覆盖（用于测试场景）。 */
    fun register(language: CodeLanguage) {
        synchronized(lock) {
            languages[language.id] = language
        }
    }

    /** 按 [CodeLanguage.id] 查找语言；找不到返回 `null`（不抛异常 — 调用方决定降级策略）。 */
    fun get(id: String): CodeLanguage? {
        synchronized(lock) {
            return languages[id]
        }
    }

    /** 是否已注册某 ID。 */
    fun contains(id: String): Boolean {
        synchronized(lock) {
            return id in languages
        }
    }

    /** 移除已注册语言（测试 / 热重载用）。 */
    fun unregister(id: String) {
        synchronized(lock) {
            languages.remove(id)
        }
    }

    /** 返回所有已注册语言（按 [CodeLanguage.id] 字典序升序 — 供 UI 下拉框稳定排序）。 */
    fun all(): List<CodeLanguage> {
        synchronized(lock) {
            return languages.values.sortedBy { it.id }
        }
    }

    /** 清空注册表（测试用）。 */
    fun clear() {
        synchronized(lock) {
            languages.clear()
        }
    }
}
