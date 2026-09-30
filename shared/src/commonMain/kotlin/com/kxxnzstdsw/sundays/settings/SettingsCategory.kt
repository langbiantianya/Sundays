package com.kxxnzstdsw.sundays.settings

/**
 * 设置页的分类 —— 左侧列表项，右侧渲染对应内容。
 *
 * 新增分类只需加一个枚举项：左侧列表遍历 [entries] 自动出现，无需改设置页渲染代码。
 */
enum class SettingsCategory(val label: String) {
    /** 个性化：主题与日夜模式。 */
    PERSONALIZATION("个性化"),

    /** 系统信息：JVM / OS / 内存等运行时指标（来自引擎 `SYSTEM.INFO`）。 */
    SYSTEM_INFO("系统信息"),
}
