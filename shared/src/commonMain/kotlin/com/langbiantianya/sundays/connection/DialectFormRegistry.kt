/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import com.langbiantianya.dialect.contract.ConnectionForm
import com.langbiantianya.dialect.contract.ConnectionFormSpec

/**
 * 方言 → 连接表单规格 的注册表 —— 前端「按 spec 渲染」的唯一入口。
 *
 * ## 它是**可空**的：查不到就回退旧的枚举分支
 *
 * 注册表里没有某个方言 → [of] 返回 `null` → 凭据页走**旧的按连接类型分支**。
 * 这不是「降级容错」，是**设计好的双向通道**（设计文档 §5.7 的 Q6 结论）：
 *
 * - 引擎比前端新（先发布引擎）→ 前端还没拿到 spec → 走旧分支，界面照常；
 * - 方言插件比引擎旧（没声明 `connectionForm`）→ 引擎不下发 spec → 走旧分支。
 *
 * 判据是「**有没有 spec**」，不是「方言是不是新的」。
 *
 * ## 为什么注册表是**注入**的，而不是组件自己去拉
 *
 * 组件（`:shared`）不依赖引擎，也**不该**依赖 —— 它只面向 [DialectFormRegistry] 编程。
 * 谁去引擎拉 spec、什么时候拉、拉失败怎么办，都是**装配点**（`:desktopApp`）的事。
 *
 * @param specs 方言 → 规格。键是前端的 [DialectType]（引擎那边用的是
 *   `driverName`，由装配点换算 —— 换算的那一处见 `:desktopApp` 的
 *   `DialectFormRegistryFactory`）。
 */
class DialectFormRegistry(
    private val specs: Map<DialectType, ConnectionFormSpec>,
) {
    /** 取某个方言的规格；没有声明过返回 `null`（调用方据此回退旧分支）。 */
    fun of(dialect: DialectType): ConnectionFormSpec? = specs[dialect]

    /**
     * 取某个方言在某个连接类型下的表单形状。
     *
     * 返回 `null` 的三种情形（都意味着**走旧分支**）：
     *
     * 1. 这个方言没有声明过 spec；
     * 2. 它声明了，但没有这个连接类型对应的 form；
     * 3. 连接类型是 `CUSTOM_URL` / `UNKNOWN` —— 这两个是**前端自己的**连接方式，
     *    不由方言声明（自定义 URL 的表单对所有方言都一样，只给一个 URL 输入框）。
     */
    fun formFor(
        dialect: DialectType,
        connectionType: ConnectionType,
    ): ConnectionForm? = specs[dialect]?.formFor(connectionType.name)

    /** 注册表里一个方言都没有 → 全部走旧分支。 */
    val isEmpty: Boolean
        get() = specs.isEmpty()

    companion object {
        /**
         * 空注册表 —— **所有**方言都回退旧的枚举分支。
         *
         * 它是 [ConnectionManagerScreen] 的默认值：调用方不传就是「老样子」。
         * 这样「接上引擎」是**可选**的，而不是「必须先拉一次才能用」。
         */
        val EMPTY = DialectFormRegistry(emptyMap())
    }
}
