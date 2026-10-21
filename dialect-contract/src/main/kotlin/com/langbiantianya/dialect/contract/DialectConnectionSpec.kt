/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect.contract

/**
 * 一次连接尝试的**参数值** —— 方言拼 URL 时要用的那些具体值。
 *
 * ## 它与 [ConnectionFormSpec] 是一对，但方向相反
 *
 * | 类型 | 回答的问题 | 谁产生 |
 * |---|---|---|
 * | [ConnectionFormSpec] | **要填哪些字段**（形状） | 方言声明 |
 * | `DialectConnectionSpec` | **这次填了什么**（值） | 用户填 / 前端组装 |
 *
 * 分开是因为「形状」是**静态**的（方言写死的），而「值」每次连接都不同；
 * 把两者塞进一个类，就得让前端每次都重新构造一份形状 —— 而形状是引擎侧的东西，
 * 前端拿不到。
 *
 * ## 为什么它要带 [properties]
 *
 * 方言拼 URL 时可能需要**方言特有的参数**（Kerberos 主体、keytab 路径、
 * ZooKeeper 发现串、SSL 参数……）。这些参数以前没有位置放 ——
 * `buildJdbcUrl(host, port, database)` 只有三个参数，装不下。
 * 加上 `properties` 之后，方言可以在新重载里读它们，
 * 而**旧的三参数方法原样保留**（见 `DatabaseDialect.buildJdbcUrl(spec)`）。
 *
 * @property host 主机地址。
 * @property port 端口；`0` 表示「没有端口」（嵌入式方言）。
 * @property database 库名 / 文件路径 / 内存库名。
 * @property properties 方言特有的连接参数。
 * @property authMethod 认证方式。
 */
data class DialectConnectionSpec(
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val properties: Map<String, String> = emptyMap(),
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
)
