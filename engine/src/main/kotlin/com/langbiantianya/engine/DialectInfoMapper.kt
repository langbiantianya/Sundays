/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.engine

import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.grpc.DialectInfo
import com.langbiantianya.grpc.dialectInfo
import com.langbiantianya.grpc.toProtoOrNull

/**
 * 方言 → proto `DialectInfo` 的映射。
 *
 * ## 为什么要单独一个文件
 *
 * `SystemHandler.listDrivers()` 原来把 13 个字段**内联**在那里的 `dialectInfo { … }`
 * 块里。现在要加上「连接表单规格」，内联就变成 20 多个字段的一大块 ——
 * 读起来像一堵墙，而且**没法单测**（要测它就得起一个引擎、注册方言）。
 *
 * 拆出来之后：
 *
 * - `listDrivers()` 只剩「遍历已加载的方言 → 映射 → 装进响应」；
 * - 映射规则本身可以单测（`DialectInfoMapperTest`），不用连库。
 *
 * ## 为什么表单规格要**可选**
 *
 * `DialectInfo.connection_form` 是**消息字段**，proto3 里天然带存在性。
 * 「该方言没声明表单」与「声明了但一个字段都没有」是**两件事**：
 *
 * - 前者 → 前端回退旧的枚举分支（照常能用）；
 * - 后者 → 真的是「这个方言不要任何字段」。
 *
 * 所以这里只在 spec **真的声明了 form** 时才设置 `connection_form`；
 * 留空 → `hasConnectionForm() == false` → 前端分得清。
 */
object DialectInfoMapper {
    /**
     * 把一个方言映射成 proto 的 `DialectInfo`。
     *
     * @param dialect 已加载的方言插件实例
     */
    fun toDialectInfo(dialect: DatabaseDialect): DialectInfo =
        dialectInfo {
            driverName = dialect.driverName
            displayName = dialect.displayName
            jdbcDriverClassName = dialect.jdbcDriverClassName
            jdbcUrlExample = dialect.jdbcUrlExample
            connectionType = dialect.connectionType.name
            requiresHost = dialect.requiresHost
            requiresPort = dialect.requiresPort
            defaultPort = dialect.defaultPort ?: 0
            supportsUser = dialect.supportsUser
            supportsPassword = dialect.supportsPassword
            supportsSchema = dialect.supportsSchema
            supportsCrossDatabase = dialect.supportsCrossDatabase
            capabilities.addAll(dialect.capabilities.map { it.name })

            // 「没声明」与「声明了空表单」必须分得开，所以这里只在
            // spec **真的声明了 form** 时才设置 connection_form。
            dialect.connectionForm.toProtoOrNull()?.let { connectionForm = it }
        }
}
