package com.kxxnzstdsw.sundays.navigation

/**
 * 顶层导航目标 —— 应用当前渲染哪个主屏幕。
 *
 * 由调用方的顶层屏幕持有（如 desktopApp 的 `MainScreen`）；切换路由会重建目标屏幕
 * （保留每个屏幕自身的 `remember` 状态）。
 */
enum class AppDestination(val label: String) {
    CONNECTIONS("连接管理"),
    DATABASE("数据库浏览"),
}
