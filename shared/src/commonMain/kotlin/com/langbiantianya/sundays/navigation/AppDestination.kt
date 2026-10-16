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
