/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.settings

import com.langbiantianya.i18n.StrKey

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
