/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect

/**
 * 方言连接模式 —— 前端用它决定连接表单的字段显隐与默认布局。
 *
 * 它是 `requiresHost` / `requiresPort` / `supportsUser` / `supportsPassword`
 * 这四个属性的**默认推导来源**（见 `DatabaseDialect` 的连接元数据区）。
 * 方言只有在个别字段上与本模式不一致时才覆盖对应属性 ——
 * 覆盖得越多，前端表单与实际连法脱节的机会就越大。
 *
 * 四种模式的区别只在**「`database` 这个字段被拿来装什么」**：
 * 库名、文件路径，还是一个可空的内存库标识。
 */
enum class ConnectionType {
    /**
     * 客户端/服务端模式 —— 需要 host + port + user + password。
     *
     * `database` 装的是**库名**。典型方言：MySQL / PostgreSQL。
     * 表单字段：host, port, user, password, database。
     */
    CLIENT_SERVER,

    /**
     * 嵌入式数据库 —— host / port 忽略。
     *
     * `database` 装的是**路径或特殊值**：DuckDB 甚至接受 `.csv` / `.parquet` /
     * `.json` / `.xlsx`（后两类经 `ExcelToDuckDbCache` 预转换）。
     * 典型方言：DuckDB。表单字段：database。
     */
    EMBEDDED,

    /**
     * 文件型数据库 —— `database` 即本地文件路径。
     *
     * 典型方言：SQLite。表单字段：database。
     * 注意 `:memory:` 会退化成每连接私有的实例，**多连接池不共享同一份数据**。
     */
    FILE_BASED,

    /**
     * 纯内存模式 —— `database` 可空，host / port 同样忽略。
     *
     * 典型方言：H2（`jdbc:h2:mem:<name>`）、DuckDB 的 `:memory:`。
     * 表单字段：database（可选）。
     */
    IN_MEMORY,
}
