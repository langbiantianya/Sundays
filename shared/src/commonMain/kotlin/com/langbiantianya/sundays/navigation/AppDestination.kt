/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

import com.langbiantianya.i18n.StrKey

/**
 * 顶层导航目标 —— 应用当前渲染哪个主屏幕。
 *
 * 由调用方的顶层屏幕持有（如 desktopApp 的 `MainScreen`）；切换路由会重建目标屏幕
 * （保留每个屏幕自身的 `remember` 状态）。
 *
 * ## 这里为什么**没有**文案键
 *
 * 早先每个目标带一个 `labelKey` —— 那是给**顶栏的目标切换 chip**用的。
 * chip 去掉之后（见 `docs/analysis-mode/README.md` v1.4），
 * 顶栏只剩品牌 + ⚙，而「数据分析」的入口文案在**连接管理的空闲面板**上
 * （那里直接用 `StrKey.NAV_ANALYSIS`）。
 *
 * 留着没人读的字段就是**可能对不上的第二份真相**：哪天有人改了 `labelKey`，
 * 界面却根本不显示它 —— 于是「改了没反应」。
 */
enum class AppDestination {
    /**
     * **启动首屏** —— 让用户选一个模式进去。
     *
     * 它是应用的**入口**：两种模式是两套会话、两种心智模型，
     * 「选模式」这件事放在进应用时问一次最合适（见 [StartScreen] 的 KDoc）。
     */
    START,

    CONNECTIONS,
    DATABASE,

    /**
     * 数据分析模式（v2.29）。
     *
     * 它是**顶层目标**而不是浏览模式的子页 —— 分析模式有自己的会话
     * （源会话 + 本地工作区），与浏览模式那套「按连接开 sheet」的模型不同，
     * 硬塞进 `DATABASE` 会让两套生命周期互相牵扯。
     */
    ANALYSIS,

    SETTINGS,
}

/**
 * 用户可见的**工作模式** —— 应用的两条主线。
 *
 * ## 为什么与 [AppDestination] 分成两个概念
 *
 * [AppDestination] 是**内部导航状态**，有五个取值；用户面对的却是**两种**模式。
 * 把两者混成一个枚举，界面上就会出现「当前：连接管理」这种用户从没听说过的说法，
 * 而「设置页要显示当前在哪个模式」这件事也无从回答。
 *
 * 所以这里显式地定义用户可见的那一份，并用 [AppDestination.mode] 把两者对上。
 * 映射是**一处**定义 —— 别处再写一遍就会漂移（改了一处忘了另一处，
 * 用户看到的「当前模式」就与实际所在的地方对不上）。
 *
 * ## 三个取值
 *
 * - [NONE]：还没选模式 —— 停在首屏，或者正站在横切所有模式的设置页上；
 * - [DATABASE_MANAGEMENT]：数据库管理模式 —— 连接管理 + 浏览表结构与数据；
 * - [DATA_ANALYSIS]：数据分析模式 —— 只读远端源 + 本地工作区里做分析。
 */
enum class AppMode {
    /** 还没选模式（首屏），或正站在不属于任何模式的全局页上。 */
    NONE,

    /** 数据库管理模式：连库、浏览表结构与数据、SQL 工作台。 */
    DATABASE_MANAGEMENT,

    /** 数据分析模式：只读数据源 + 本地工作区里的 SQL 与报表。 */
    DATA_ANALYSIS,
}

/**
 * 这个导航目标属于哪个**用户可见的模式**。
 *
 * ## 为什么 [AppDestination.SETTINGS] 映射成 [AppMode.NONE]
 *
 * 设置页是横切在两个模式之上的**全局页** —— 它自己不属于任何一个。
 * 设置页显示的「当前模式」取自**进来之前的那一屏**（desktopApp 侧记着
 * `destinationBeforeSettings`），而不是这个值 —— 否则设置页会永远显示
 * 「未选择模式」，而用户明明就在某个模式里。
 */
val AppDestination.mode: AppMode
    get() =
        when (this) {
            AppDestination.START -> AppMode.NONE
            AppDestination.CONNECTIONS, AppDestination.DATABASE -> AppMode.DATABASE_MANAGEMENT
            AppDestination.ANALYSIS -> AppMode.DATA_ANALYSIS
            AppDestination.SETTINGS -> AppMode.NONE
        }

/**
 * 这个模式在界面上叫什么 —— 文案**跟着界面语言走**，所以这里给的是键而不是文案。
 *
 * 与 [SettingsCategory.labelKey] 同一套做法：文案表在 `i18n` 模块，
 * 界面层只认键。
 */
val AppMode.labelKey: StrKey
    get() =
        when (this) {
            AppMode.NONE -> StrKey.MODE_NONE
            AppMode.DATABASE_MANAGEMENT -> StrKey.MODE_DATABASE_MANAGEMENT
            AppMode.DATA_ANALYSIS -> StrKey.MODE_DATA_ANALYSIS
        }
