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
 * [labelKey] 是文案键而非文案本身 —— 导航条的 chip 文字同样要跟着界面语言走。
 */
enum class AppDestination(
    val labelKey: StrKey,
) {
    CONNECTIONS(StrKey.NAV_CONNECTIONS),
    DATABASE(StrKey.NAV_DATABASE),

    /**
     * 数据分析模式（v2.29）。
     *
     * 它是**顶层目标**而不是浏览模式的子页 —— 分析模式有自己的会话
     * （源会话 + 本地工作区），与浏览模式那套「按连接开 sheet」的模型不同，
     * 硬塞进 `DATABASE` 会让两套生命周期互相牵扯。
     */
    ANALYSIS(StrKey.NAV_ANALYSIS),

    SETTINGS(StrKey.SETTINGS_TITLE),
}
