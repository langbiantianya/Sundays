/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * 桌面端的指针图标 —— 平台专属的东西**全在这一处**。
 *
 * ## 为什么单独成文件
 *
 * Compose 的公共 API 只提供四种指针（默认箭头 / 十字 / 文本 / 手型），
 * 没有「左右拉伸」—— 而那正是分隔条该用的指针。桌面端能造出来，
 * 是因为它能直接用 `java.awt.Cursor`。
 *
 * 而 `:shared` 里的 [com.langbiantianya.sundays.ui.DragHandle] 住在 `commonMain`，
 * 不能引用任何平台类型。所以分工是：
 *
 * - `:shared` —— 决定**什么时候**该用这个指针（能不能拖、图标挂在哪）
 * - `:desktopApp`（本文件）—— 决定**这个指针长什么样**（平台专属）
 *
 * 这与本仓库既有的分界一致：`ClipboardWriter`（剪贴板）、`NativePathPicker`
 * （文件对话框）也都是「平台专属的能力留在 `desktopApp`，`:shared` 只管 UI」。
 *
 * ## 为什么是 `null` 而不是抛异常
 *
 * `java.awt.Cursor` 的创建要经过 AWT 的 Toolkit —— 无头环境（CI、headless JVM）
 * 下可能拿不到。拿不到时**返回 `null`**（= 不改指针，退回系统默认箭头），
 * 而不是让整个应用起不来：一个指针图标不值得让应用崩。
 *
 * 与 [ClipboardWriter] 里 `isHeadless()` 的判断是同一个道理。
 *
 * ## ⚠️ AWT 里**没有** `EW_RESIZE_CURSOR`，也**没有** `NS_RESIZE_CURSOR`
 *
 * 想要「左右双箭头 ↔」时，直觉会写 `Cursor.EW_RESIZE_CURSOR` —— 但 AWT 里
 * 没有这个常量，编译直接报 unresolved reference。实际是：
 *
 * | 常量 | 形状 | 用途 |
 * |---|---|---|
 * | `E_RESIZE_CURSOR` / `W_RESIZE_CURSOR` | ↔ **东西**向双箭头 | 竖向分隔条（左右拖） |
 * | `N_RESIZE_CURSOR` / `S_RESIZE_CURSOR` | ↕ **南北**向双箭头 | 横向分隔条（上下拖）—— **本组件要的就是它** |
 * | `NE_/NW_/SE_/SW_RESIZE_CURSOR` | ↗↖↘↙ 斜向双箭头 | 斜向缩放 |
 *
 * `E` 与 `W` 是同一个形状的两个热区（热点在左端还是右端），画出来一模一样；
 * `N` 与 `S` 同理。
 *
 * 也就是说：**两个方向各有一对同形不同热区的常量**，没有「东西向」「南北向」
 * 这种合起来的名字 —— 写 `NS_RESIZE_CURSOR` 会直接编译不过（真踩过）。
 *
 * ## 两种朝向**各有一份**图标，而不是一个「取中间值」的
 *
 * 竖向分隔条拖的是**宽度**、横向分隔条拖的是**高度** —— 指针画成 ↔ 而实际要上下拖，
 * 就是在**说反**。用户会照着指针的方向去试，试不动就以为坏了。
 */
private val ewResizeIcon: PointerIcon? by lazy {
    runCatching { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }.getOrNull()
}

/**
 * 「南北拉伸」指针 —— 拖**横向**分隔条（调上下两块的高度）时该用的那个。
 *
 * AWT 里没有 `NS_RESIZE_CURSOR`，南北向是 `N_RESIZE_CURSOR` / `S_RESIZE_CURSOR`
 * 这一对同形不同热区的常量（见本文件的说明）。
 *
 * @return `null` = 当前环境造不出这个指针（无头 AWT），调用方应当当作「不改指针」处理。
 */
private val nsResizeIcon: PointerIcon? by lazy {
    runCatching { PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)) }.getOrNull()
}

/**
 * 「左右拉伸」指针 —— 拖拽**竖向**分隔条时该用的那个。
 *
 * @return `null` = 当前环境造不出这个指针（无头 AWT），调用方应当当作「不改指针」处理。
 */
fun ewResizePointerIcon(): PointerIcon? = ewResizeIcon

/**
 * 「上下拉伸」指针 —— 拖拽**横向**分隔条时该用的那个。
 *
 * @return `null` = 当前环境造不出这个指针（无头 AWT），调用方应当当作「不改指针」处理。
 */
fun nsResizePointerIcon(): PointerIcon? = nsResizeIcon
