/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.navigation

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
