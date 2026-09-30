package com.kxxnzstdsw.sundays.ui

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
 */
enum class SystemInfoRefresh(val label: String, private val seconds: Int) {

    /** 不自动刷新（默认）。 */
    OFF("关闭", 0),
    S10("10 秒", 10),
    S5("5 秒", 5),
    S2("2 秒", 2),
    S1("1 秒", 1),
    ;

    /** 是否启用自动刷新。 */
    val enabled: Boolean get() = this != OFF

    /**
     * 刷新周期；[OFF] 返回 `null`（「没有周期」比「周期为 0」更准确，调用方不必特判 0）。
     */
    val interval: Duration? get() = if (enabled) seconds.seconds else null
}
