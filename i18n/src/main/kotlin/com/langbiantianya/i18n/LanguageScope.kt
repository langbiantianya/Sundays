/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

import java.util.concurrent.atomic.AtomicReference

/**
 * 「当前请求用哪种语言」的上下文 —— `:engine` 侧取文案的入口。
 *
 * ## 为什么需要它，而不是让每个 handler 多带一个参数
 *
 * 引擎里的错误消息散落在十几个 handler 与若干工具类（`ExportProcessManager`、
 * `CsvReader`、`PoolManager`…）的 `error("…")` 上。让它们逐个多带一个
 * `language: AppLanguage` 参数，会把签名污染铺满整个服务端，而且**总有人会忘** ——
 * 漏传的那一处会在任何语言下都返回错误语言。
 *
 * 与其给上百个函数加参数，不如让「当前语言」是一次请求范围内的环境事实。
 *
 * ## 为什么是 ThreadLocal，以及它的坑
 *
 * 引擎是「一个进程服务一个 UI」：Direct 模式下引擎与界面同 JVM；gRPC 模式下引擎是
 * 独立进程、由同一个界面对话。语言在一次会话里是常量，故**不需要**为每个请求做隔离，
 * 只要能「设一次、之后各处都读得到」即可 —— ThreadLocal 正好够用，且零依赖。
 *
 * 但 ThreadLocal 有两个必须知道的边界：
 *
 * 1. **协程会换线程。** 若在 A 线程设置、在 B 线程读取，读到的是 `null`（回落系统语言）。
 *    `:engine` 用 `Dispatchers.IO` 执行 handler，因此 `:engine` 侧必须用
 *    `ThreadContextElement` 把这个 ThreadLocal **随协程一起搬**，否则语言会随机丢失。
 *    该适配器放在 `:engine`（它才有 kotlinx-coroutines），不在本模块。
 * 2. **线程池会复用线程。** 故 [clear] 与设置成对出现，绝不留下脏值给下一个请求。
 *
 * 未设置时的回落是**系统语言探测**（而不是 [AppLanguage.DEFAULT]）—— 引擎独立以 gRPC
 * 部署时进程所在机器的 locale 仍是一个有意义的初值。
 */
object LanguageScope {

    private val current = ThreadLocal<AppLanguage?>()

    /**
     * 进程级默认语言 —— ThreadLocal 没设时用。
     *
     * ## 为什么需要它（而不是只用 ThreadLocal）
     *
     * ThreadLocal 绑在**线程**上，但「用户选了哪种语言」是**整个进程**的事，
     * 且设它的那个地方（Compose 界面）与读它的那个地方（`IdbEngine.handle`，
     * 由各个状态机在自己的协程里发起）**几乎不可能是同一条线程**。
     *
     * 只靠 ThreadLocal 的后果：UI 切到德语、界面立刻变德语（因为界面读的是
     * CompositionLocal，与本类无关），但引擎报错仍是中文 —— 而引擎是在**另一个线程**
     * 上读 ThreadLocal 的。
     *
     * 优先级因此是三级的：**线程级 → 进程级 → 系统探测**。请求级作用域用线程级
     * （`withLanguage` / `EngineLanguageContext`），应用级设置用进程级。
     */
    private val processDefault = AtomicReference<AppLanguage?>(null)

    /**
     * 设置**整个进程**的默认语言。界面切换语言时调一次，与线程无关。
     *
     * 传 `null` 表示「回落到按系统区域设置探测」。
     */
    fun setProcessLanguage(language: AppLanguage?) {
        processDefault.set(language)
    }

    /** 当前进程级默认语言；未设置时为 `null`。 */
    fun processLanguageOrNull(): AppLanguage? = processDefault.get()

    /** 当前线程上生效的语言；未设置时按进程默认 → 系统区域设置逐级回落。 */
    fun currentLanguage(): AppLanguage =
        current.get() ?: processDefault.get() ?: AppLanguage.detectSystemLanguage()

    /**
     * 当前线程上生效的语言；**未设置时返回 `null`** 而不是探测结果。
     *
     * 供 `:engine` 的 `ThreadContextElement` 实现用 —— 它需要区分
     * 「之前就没有语言、走探测」与「之前是某个具体语言」，才能在协程结束时
     * 精确还原。若在这里返回探测值，还原时会把探测值**写死**成显式设置，
     * 使后续的 `clear()` 彻底失效。
     */
    fun currentLanguageOrNull(): AppLanguage? = current.get()

    /**
     * 直接设置当前线程的语言，不自动恢复。
     *
     * 与 [setLanguage] 的区别只在于不返回恢复函数 —— 给需要自己做
     * 「读旧值 → 写新值 → 之后还原旧值」这套流程的调用方（如协程上下文元素）。
     */
    fun setCurrentLanguage(language: AppLanguage) {
        current.set(language)
    }

    /** 当前线程上生效的文案表。 */
    fun currentStrings(): Strings = StringsRegistry.of(currentLanguage())

    /**
     * 在 [block] 期间把当前语言设为 [language]，结束后**恢复原值**。
     *
     * 必须恢复而不是简单清空：`:engine` 的线程池会复用线程，
     * 留下上一个请求的语言会让紧接着的请求拿到别人的语言 —— 那是最难查的一类串味。
     *
     * 这个重载是**同步**的（`block` 不是 `suspend`）。在协程里请用 `:engine` 侧的
     * 协程感知封装，否则 [currentLanguage] 可能在另一个线程上被读到。
     */
    fun <T> withLanguage(language: AppLanguage, block: () -> T): T {
        val previous = current.get()
        current.set(language)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /**
     * 显式设置当前线程的语言，**不**自动恢复 —— 供「设置一次、长期生效」的场合
     * （进程启动、Direct 模式下界面直接告知引擎）。
     *
     * 返回一个恢复函数，让调用方能在需要时手动还原；
     * 若调用方确定不管了（进程生命周期内不变），忽略返回值即可。
     */
    fun setLanguage(language: AppLanguage): () -> Unit {
        val previous = current.get()
        current.set(language)
        return {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** 清掉当前线程的语言，回到「按系统区域设置探测」。 */
    fun clear() {
        current.remove()
    }
}

/**
 * [LanguageScope] 的薄封装 —— 让引擎里的错误消息写成
 * `Msg.text(EngineMsg.EXPORT_FAILED)` 而不是两段式调用。
 *
 * 单独开一个名字是为了在栈里一眼区分「取文案」与「设上下文」，
 * 后者出现在分发层，前者出现在上百个抛错的地方。
 */
object Msg {

    /** 取一条文案。 */
    fun text(key: StrKey): String = LanguageScope.currentStrings()[key]

    /** 取一条带占位符的文案。 */
    fun format(key: StrKey, vararg args: Any?): String =
        LanguageScope.currentStrings().format(key, *args)

    /** 取一条按数量选变体的文案。 */
    fun plural(key: PluralStrKey, count: Int, vararg args: Any?): String =
        LanguageScope.currentStrings().plural(key, count, *args)
}
