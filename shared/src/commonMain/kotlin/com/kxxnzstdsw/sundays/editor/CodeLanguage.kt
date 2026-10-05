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
 * 3. （可选）重写 `completionCandidates` 提供补全 —— 不实现则只有高亮、没有「提示」
 * 4. 在 [CodeLanguageRegistry] 中 `register(MyLanguage())`
 * 5. UI 层调用 `CodeEditor(text, languageId = "my-lang")`
 *
 * **不需要修改任何现有代码** —— registry 模式支持运行时注册；`completionCandidates`
 * 自带默认实现（返回空列表），只支持高亮的语言不必碰它。
 *
 * @see SqlLanguage
 * @see LuaLanguage
 * @see CompletionItem
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

    /**
     * 补全候选 —— 编辑器「提示」功能的数据来源。
     *
     * **默认实现返回空列表**：语言只支持高亮、不支持补全是完全合法的（纯文本模式就是如此），
     * 因此这不是抽象方法。第三方语言无需实现即可照常工作 —— 与本接口「新增语言零改动」的
     * 扩展承诺一致。
     *
     * 实现要求：
     * - **必须是纯函数**（禁 IO / 全局可变状态），每次按键都会被调用
     * - 大小写匹配策略由语言自定：SQL 忽略大小写、Lua 敏感（见 [CompletionItem] 的说明）
     * - 返回顺序即展示顺序，建议短词 / 常用词靠前（可用 [buildCompletionPool] 预排序）
     * - 返回条数应 ≤ [limit]，且**不要**返回与前缀完全相同的项（用户没敲错时无需提示）
     *
     * @param prefix 光标前的当前词（由 [wordPrefixBefore] 算出）
     * @param limit 最多返回几条
     */
    fun completionCandidates(
        prefix: String,
        limit: Int = DEFAULT_COMPLETION_LIMIT,
    ): List<CompletionItem> = emptyList()
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
