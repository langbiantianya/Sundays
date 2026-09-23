package com.kxxnzstdsw.sundays

/**
 * 顶层导航目标 —— 应用当前渲染哪个主屏幕。
 *
 * 由 [MainScreen] 持有；切换路由会重建目标屏幕（保留每个屏幕自身的 `remember` 状态）。
 */
enum class AppDestination(val label: String) {
    CONNECTIONS("连接管理"),
    DATABASE("数据库浏览"),
}
