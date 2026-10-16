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
enum class AppDestination(val labelKey: StrKey) {
    CONNECTIONS(StrKey.NAV_CONNECTIONS),
    DATABASE(StrKey.NAV_DATABASE),
    SETTINGS(StrKey.SETTINGS_TITLE),
}
