/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.editor

import com.langbiantianya.sundays.editor.formatter.CodeFormatterRegistry
import com.langbiantianya.sundays.editor.formatter.SqlFormatter
import com.langbiantianya.sundays.editor.language.SqlDialectProfile
import com.langbiantianya.sundays.editor.language.SqlLanguage

/**
 * SQL 方言档位注册表 —— 按 [SqlDialectProfile.languageId] 索引。
 *
 * ## 为什么要有这个注册表
 *
 * 它是 [CodeLanguageRegistry] 与 [CodeFormatterRegistry] 的**第三个**同构注册表，
 * 而这三者本该是一套：
 *
 * | 注册表 | 索引 | 管什么 |
 * |---|---|---|
 * | [CodeLanguageRegistry] | `languageId` | 语言（分词 + 补全） |
 * | [CodeFormatterRegistry] | `languageId` | 格式化器 |
 * | **本注册表** | `languageId` | **方言档位**（词表） |
 *
 * 在它出现之前，SQL 家族走的是一条旁路：[SqlDialectProfile.ALL] 是一行**硬编码 list**，
 * [SqlFormatter.register] 遍历它注册「语言 + formatter」配对。
 * 后果是：**加一个方言档位必须改 `:shared` 的核心文件** ——
 * 这与 `CodeLanguageRegistry` 的 KDoc 里写明的承诺（「第三方语言可在应用启动后调用
 * `register` 注入，**不需要修改编辑器组件源码**」）直接冲突。
 * SQL 家族只是没享受这个机制，不是这个机制不成立。
 *
 * ## 注册一个档位会发生什么
 *
 * [register] **一次**做完三件事：
 *
 * 1. 把档位存进本表；
 * 2. 用它构造 [SqlLanguage] 注册进 [CodeLanguageRegistry]；
 * 3. 用同一个 `languageId` 构造 [SqlFormatter] 注册进 [CodeFormatterRegistry]。
 *
 * 第 2、3 步**必须成对**—— [CodeEditor] 的 KDoc 里写明了那个「中间态」：
 * 注册了语言但没注册 formatter 时，高亮正常、但工作台的「格式化」按钮会**静默禁用**；
 * 反之则是按钮可点但点了没反应。分开注册等于把这种状态暴露给用户。
 *
 * ## 线程安全
 *
 * 与另两个注册表一致：用 `synchronized` 守护。KMP Desktop 的多协程并发调用是常态，
 * 而 [CodeLanguageRegistry] 的 KDoc 明确要求「注册表是线程安全的」。
 *
 * ## 与 [SqlDialectProfile.ALL] 的关系
 *
 * [SqlDialectProfile.ALL] 是「**内置**档位有哪些」的答案，也是
 * [registerBuiltins] 的来源；**运行时的真相源是本注册表**。
 * 两者不是一回事：内置清单是编译期常量，注册表是运行期状态 ——
 * 注册表里可以有内置清单之外的东西（测试注册的假档位、将来由方言插件注册的档位）。
 */
object SqlDialectProfileRegistry {
    private val lock = Any()
    private val profiles = linkedMapOf<String, SqlDialectProfile>()

    /**
     * 注册一个方言档位 —— 同时注册它的语言与格式化器（见类 KDoc「注册一个档位会发生什么」）。
     *
     * 幂等：同一个 `languageId` 重复注册会**覆盖**旧档位（与 [CodeLanguageRegistry.register] 一致）。
     */
    fun register(profile: SqlDialectProfile) {
        synchronized(lock) {
            profiles[profile.languageId] = profile
            CodeLanguageRegistry.register(SqlLanguage(profile))
            CodeFormatterRegistry.register(SqlFormatter(profile.languageId))
        }
    }

    /**
     * 把 [SqlDialectProfile.ALL] 里的**全部内置档位**注册进来。
     *
     * 幂等 —— 重复调用只是把同样的档位再写一遍（值相同）。
     * 应用启动时由 [com.langbiantianya.sundays.editor.ui.registerBuiltinEditors] 调用。
     */
    fun registerBuiltins() {
        SqlDialectProfile.ALL.forEach { register(it) }
    }

    /** 按 `languageId` 查找档位；找不到返回 `null`（不抛异常 —— 调用方决定降级策略）。 */
    fun get(languageId: String): SqlDialectProfile? {
        synchronized(lock) {
            return profiles[languageId]
        }
    }

    /** 是否已注册某 `languageId`。 */
    fun contains(languageId: String): Boolean {
        synchronized(lock) {
            return languageId in profiles
        }
    }

    /**
     * 返回所有已注册档位（按 `languageId` 字典序升序 —— 供 UI 下拉框稳定排序）。
     *
     * 排序而不是注册顺序：注册顺序取决于「谁先调用了 [register]」，
     * 那不是稳定的用户可见顺序。
     */
    fun all(): List<SqlDialectProfile> {
        synchronized(lock) {
            return profiles.values.sortedBy { it.languageId }
        }
    }

    /**
     * 移除已注册档位（测试 / 热重载用）。
     *
     * ⚠️ 只从本表移除 —— **不动** [CodeLanguageRegistry] / [CodeFormatterRegistry]：
     * 那两个注册表没有对应的 unregister 语义（`CodeFormatterRegistry` 有，但语言没有），
     * 半个撤销比不撤销更危险（档位没了、语言还在，编辑器仍在高亮）。
     */
    fun unregister(languageId: String) {
        synchronized(lock) {
            profiles.remove(languageId)
        }
    }

    /** 清空注册表（测试用）。 */
    fun clear() {
        synchronized(lock) {
            profiles.clear()
        }
    }
}
