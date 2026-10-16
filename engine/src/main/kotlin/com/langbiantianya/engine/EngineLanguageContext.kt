/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.engine

import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.LanguageScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * 把「当前请求用哪种语言」**随协程一起搬**的适配器。
 *
 * ## 为什么它必须在 `:engine` 而不是 `:i18n`
 *
 * [LanguageScope] 用的是 ThreadLocal，而引擎的 handler 一律在
 * `Dispatchers.IO` 上跑 —— 一进 `withContext(IO)` 就换了线程，ThreadLocal 读不到，
 * 语言会随机丢失（表现为「德语界面里偶发中文报错」，极难复现）。
 *
 * `ThreadContextElement` 正是 kotlinx.coroutines 为这类「线程局部状态」提供的
 * 标准解：它在协程**恢复执行前**把值装到当前线程、**挂起/结束时**还原。
 * 嵌套的 `withContext(Dispatchers.IO)` 会把元素一并带过去，因为元素在协程上下文里，
 * 而不在某个线程上。
 *
 * 而 `kotlinx-coroutines` 只在 `:engine` 里 —— 为一个四十几行的适配器把协程库
 * 拉进 `:i18n`（那个模块刻意零依赖）不划算。故拆开：`LanguageScope` 负责存，
 * 本类负责搬。
 *
 * ## 为什么要精确保存「之前是 null 还是某个语言」
 *
 * 线程池会复用线程。若协程结束时无条件 `clear()`，那么「线程上原本有语言」
 * 的情形（进程启动时由 Direct 模式界面设过一次）就被抹掉了。
 * 故 [updateThreadContext] 返回旧值、[restoreThreadContext] 原样还回去。
 */
internal class EngineLanguageContext(private val language: AppLanguage) :
    ThreadContextElement<AppLanguage?>,
    AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<EngineLanguageContext>

    override fun updateThreadContext(context: CoroutineContext): AppLanguage? {
        val previous = LanguageScope.currentLanguageOrNull()
        LanguageScope.setCurrentLanguage(language)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: AppLanguage?) {
        if (oldState == null) LanguageScope.clear() else LanguageScope.setCurrentLanguage(oldState)
    }
}

/**
 * 从 `Request.options.language` 取出界面语言。
 *
 * 留空 / 认识不出来时返回 `null` —— 调用方据此**不安装**上下文元素，
 * 于是 [LanguageScope] 保持未设置状态、按系统区域设置探测。
 *
 * 刻意不回落 [AppLanguage.DEFAULT]（简体中文）：引擎可能独立于中文界面的客户端运行
 * （gRPC 模式下一个进程可以被任何客户端连），此时按**引擎自己所在机器**的 locale
 * 猜，比强行发简体中文更合理。
 */
internal fun requestLanguageOrNull(options: com.langbiantianya.grpc.RequestOptions): AppLanguage? {
    if (!options.language.isBlank()) return AppLanguage.fromTagOrNull(options.language)
    return null
}
