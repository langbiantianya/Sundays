/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.browser

import com.langbiantianya.sundays.connection.ConnectionStatus

/**
 * 一个 sheet 的**摘要** —— 标签条要显示的全部信息。
 *
 * ## 它为什么不是「直接给 `SheetDescriptor`」
 *
 * `SheetDescriptor`（在 `:desktopApp`）持有一整个 `DatabaseBrowserState`，
 * 而那个 state 是**引擎接线**的产物：它构造 proto 请求、调用引擎、处理响应。
 * 标签条一个字节都用不到这些，却因为「参数类型里带了它」而被绑死在 `:desktopApp`。
 *
 * 于是抽出这个**窄形状**：标签条只需要「谁的连接、叫什么、什么状态」三样。
 * 这与本仓库既有的做法一致 —— `ConnectionManagerScreen` 也是靠**回调注入**
 * 才进的 `:shared`（引擎调用由调用方提供），而不是把 `IdbEngine` 传进去。
 *
 * 代价是多一次映射（`SheetDescriptor.toSummary()`）；收益是标签条能进 `:shared`，
 * 而它正是分析模式（另一个工作台）要复用的东西。
 *
 * @property id 连接 id —— 同时是 sheet 标签的 UI 测试 tag 后缀。
 * @property name 连接名 —— 标签上显示的文字。
 * @property status 连接状态 —— 决定标签上那个状态色点。
 */
data class SheetSummary(
    val id: String,
    val name: String,
    val status: ConnectionStatus,
)
