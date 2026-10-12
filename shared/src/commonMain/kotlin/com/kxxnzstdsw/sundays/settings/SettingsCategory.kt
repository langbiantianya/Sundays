package com.kxxnzstdsw.sundays.settings

import com.kxxnzstdsw.i18n.StrKey

/**
 * 设置页的分类 —— 左侧列表项，右侧渲染对应内容。
 *
 * 新增分类只需加一个枚举项：左侧列表遍历 [entries] 自动出现，无需改设置页渲染代码。
 *
 * [labelKey] 是文案键而非文案本身 —— 左侧分类名要跟着界面语言走。
 */
enum class SettingsCategory(val labelKey: StrKey) {
    /** 个性化：主题与日夜模式。 */
    PERSONALIZATION(StrKey.PERSONALIZATION),

    /** 系统信息：JVM / OS / 内存等运行时指标（来自引擎 `SYSTEM.INFO`）。 */
    SYSTEM_INFO(StrKey.SYSTEM_INFO),
}
