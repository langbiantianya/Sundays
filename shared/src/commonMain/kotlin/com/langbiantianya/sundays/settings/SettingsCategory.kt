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
enum class SettingsCategory(
    val labelKey: StrKey,
) {
    /** 个性化：主题与日夜模式。 */
    PERSONALIZATION(StrKey.PERSONALIZATION),

    /** 系统信息：JVM / OS / 内存等运行时指标（来自引擎 `SYSTEM.INFO`）。 */
    SYSTEM_INFO(StrKey.SYSTEM_INFO),

    /**
     * 模式：切换「数据库管理模式 / 数据分析模式」。
     *
     * ## 为什么单独一栏，而不是塞进「个性化」
     *
     * 「个性化」里都是**外观**（配色、明暗、密度、语言）；「切换模式」是**导航**动作 ——
     * 按它会离开当前这一屏回到首屏。混在一起会让用户以为按下去改的是外观，
     * 而实际发生的是换了一整套工作环境。
     *
     * 它放在**末尾**而不是开头：前两栏是「打开设置先看的那两样」，
     * 而切模式是一个**偶尔**才用的动作，不该在每次进设置时抢最前面的位置。
     */
    MODE(StrKey.MODE),
}
