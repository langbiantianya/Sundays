/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.sundays.connection.ConnectionConfig

/**
 * UI 层的连接配置 → 引擎的**远端源会话**配置。
 *
 * ## 为什么要有这一层换算
 *
 * 应用里存在两套「连接配置」：
 *
 * | 模型 | 所在 | 用途 |
 * |---|---|---|
 * | `sundays.connection.ConnectionConfig` | `:shared` | 存盘、渲染表单、带 `id` / `name` |
 * | `grpc.ConnectionConfig` | `:engine-protocol` | 发给引擎，带 `driver` / `jdbcUrl` / 凭据 |
 *
 * 引擎只认后者。少一层换算，引擎侧就得认识 `id` / `name` 这些它用不上的字段 ——
 * 那是把「用户给连接起的名字」塞进「怎么连」的协议里，职责就糊了。
 *
 * ## 为什么只搬这几个字段
 *
 * 与浏览模式的 `DatabaseBrowserState.engineConnFor` **同口径**（同一个形状的换算，
 * 两处各写一遍就已经是两个真相源了，这里只做换算、不加不减）：
 *
 * - `driver` —— 引擎按 `driverName`（`Mysql` / `Postgresql` / …）注册方言，
 *   不是枚举常量名
 * - `jdbcUrl` —— 连接的**真相源**；池按它建，凭据也从它里面取
 * - `user` / `password` —— 凭据
 * - `database` —— catalog 维度：PG 上它是库名（决定列哪些 schema），
 *   MySQL 上它是库名（决定列哪些表）
 *
 * `properties` 不搬：方言特有参数（SSL、Kerberos 主体）本来就编在 `jdbcUrl` 的
 * 查询串里，再搬一遍等于让它有两份来源。
 *
 * ## 这里**不**设 `read_only`
 *
 * 「远端恒为只读」只有一个**执行点**：`AnalysisSession.sourceConfig()`
 * ——它在发请求之前把 `readOnly` 强制置上。
 * 若这里也置一份，就有了两个产地，而它们迟早会不一致
 * （改了一处忘了另一处，某个调用方就从只读滑回可写）。
 */
fun analysisSourceEngineConfig(source: ConnectionConfig): com.langbiantianya.grpc.ConnectionConfig =
    connectionConfig {
        driver = source.dialect.engineDriverName
        jdbcUrl = source.jdbcUrl
        user = source.username
        password = source.password
        if (source.database.isNotBlank()) {
            database = source.database
        }
    }
