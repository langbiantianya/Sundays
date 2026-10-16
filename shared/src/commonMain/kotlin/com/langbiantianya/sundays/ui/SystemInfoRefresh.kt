/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import com.langbiantianya.i18n.StrKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 「系统信息」自动刷新间隔。
 *
 * [OFF] 是默认值：自动刷新会周期性读 JVM 运行时数据（`SYSTEM.INFO`），在 **gRPC 模式下
 * 是真实的跨进程往返**。默认开启会让远程引擎在用户根本没看系统信息页时也持续收到请求，
 * 因此必须由用户显式打开。
 *
 * 间隔档位取 10 / 5 / 2 / 1 秒：1 秒是刷新类界面的常见上限（比人眼可感知的负载变化更快
 * 就没有信息增量了，且会让堆内存读数抖动得像故障）。
 *
 * ## 为什么存 [StrKey] 而不是文案本身
 *
 * [label] 曾经是构造参数里的硬编码中文。切换界面语言时它**不会**跟着变 ——
 * 翻译只替换了 Composable 里的字面量，而这里是数据，翻译器根本碰不到。
 * 枚举改成只存键，文案查表在渲染时发生，于是「换语言」才真的覆盖到所有入口。
 */
enum class SystemInfoRefresh(val labelKey: StrKey, private val seconds: Int) {

    /** 不自动刷新（默认）。 */
    OFF(StrKey.AUTO_REFRESH_OFF, 0),
    S10(StrKey.AUTO_REFRESH_10S, 10),
    S5(StrKey.AUTO_REFRESH_5S, 5),
    S2(StrKey.AUTO_REFRESH_2S, 2),
    S1(StrKey.AUTO_REFRESH_1S, 1),
    ;

    /** 是否启用自动刷新。 */
    val enabled: Boolean get() = this != OFF

    /**
     * 刷新周期；[OFF] 返回 `null`（「没有周期」比「周期为 0」更准确，调用方不必特判 0）。
     */
    val interval: Duration? get() = if (enabled) seconds.seconds else null
}
