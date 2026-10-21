/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.browser

/**
 * 右栏展示模式 —— 每 sheet 独立。
 *
 * - [TABLE] = 表预览（双击表节点打开的标签页区）；初始值
 * - [SQL] = SQL 工作台（编辑器 + 底部结果面板）；由工具栏切换
 * - [GENERATE] = 造数工作台（Lua 脚本编辑 + 造数结果面板）；由工具栏切换
 *
 * 三个 pane 是**同一份 sheet 状态**的多种渲染 —— 切换只改变渲染目标，不清空任何状态。
 *
 * ## 为什么住在 `:shared`
 *
 * 它是**纯枚举**：不碰引擎、不碰状态。工具栏（[BrowserToolBar]）要按它决定
 * 显示哪个工作台按钮，而工具栏是共享 UI —— 枚举不跟着走，工具栏就没法在
 * `:shared` 里表达「当前是哪个 pane」。
 */
enum class BrowserPane {
    TABLE,
    SQL,
    GENERATE,
}
