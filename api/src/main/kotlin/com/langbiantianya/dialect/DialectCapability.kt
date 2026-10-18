/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect

/**
 * 方言能力标签 —— 用于告知前端该方言支持哪些数据库特性（按钮/菜单显隐）。
 *
 * ## 这是**声明**，不是探测
 *
 * 每个标签只有两种含义之一：要么 `DatabaseDialect` 真的实现了对应的一组 SPI 方法，
 * 要么它会落到 SPI 里默认抛 `UnsupportedOperationException` 的那批实现上。**没有第三种**
 * —— 前端据它隐藏的按钮，永远不会点到一个必然失败的调用上。
 *
 * 故新增标签时不要「顺手先加上」：标签一进集合，前端就会渲染出对应入口，
 * 而底层可能根本没有实现。加标签之前先确认该方言的对应方法确实可用。
 *
 * 前端拿到 `DialectInfo.capabilities` 后，按集合决定 UI 显隐：
 * 包含 [USERS] → 显示「用户管理」导航；不包含 [TRIGGERS] → 隐藏「触发器」标签页。
 *
 * 各标签在 5 个内置方言上的分布是**跨模块对照**，见 `api/README.md` 的能力矩阵；
 * 单个方言的声明位置是它自己的 `capabilities` 属性。
 */
enum class DialectCapability {
    /**
     * 用户管理（CREATE / DELETE / UPDATE PASSWORD / LIST）。
     *
     * 内置方言：MySQL / PostgreSQL / H2。SQLite 与 DuckDB 是嵌入式单用户库，没有这个概念。
     */
    USERS,

    /**
     * 权限管理（GRANT / REVOKE）。
     *
     * 内置方言：MySQL / PostgreSQL / H2。与 [USERS] 几乎总是同时出现 ——
     * 没有用户就谈不上给谁授权。
     */
    PRIVILEGES,

    /**
     * 函数 / 存储过程。
     *
     * 内置方言：MySQL / PostgreSQL / H2 / DuckDB。DuckDB 声明它只是为了映射到
     * `MACRO` 与标量 `FUNCTION`（见 `DuckDBDialect.listRoutines`）—— 没有存储过程。
     */
    ROUTINES,

    /** 视图。内置方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite（5 个全支持）。 */
    VIEWS,

    /** 索引。内置方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite（5 个全支持）。 */
    INDEXES,

    /** 外键。内置方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite（5 个全支持）。 */
    FOREIGN_KEYS,

    /**
     * 触发器。
     *
     * 内置方言：MySQL / PostgreSQL。**H2 故意不声明** —— 它的
     * `INFORMATION_SCHEMA.TRIGGERS` 表虽然存在，但 schema 与列名与 MySQL / PG 差异很大，
     * 沿用 SPI 默认的 `UnsupportedOperationException` 更诚实（见 `H2Dialect` 的说明）。
     */
    TRIGGERS,

    /**
     * 多 database（跨库查询）。
     *
     * 内置方言：PostgreSQL / MySQL / H2 / DuckDB。
     * 注意「声明了」只说明能跨库；**怎么切**由方言自己回答 ——
     * MySQL 走 `switchCatalog` 的 `USE`，PG 只能在建池时改 URL（`jdbcUrlForCatalog`）。
     */
    CROSS_DATABASE,

    /**
     * 多 schema（同一连接内 schema 切换）。
     *
     * 内置方言：PostgreSQL / H2 / DuckDB。MySQL 的 schema == database（双层导航无意义），
     * SQLite 只有 `main` / `temp` 两个内置 schema，都不声明。
     */
    MULTI_SCHEMA,

    /** 流式数据导出（CSV / JSON / SQL INSERT / Excel / Parquet 5 种）。内置方言：除 H2 外全部。 */
    EXPORT,

    /**
     * 支持 DDL 事务回滚（rollback DDL）。
     *
     * ## 「声明了」与「真能回滚」是两回事 —— MySQL **和 H2** 都属于软支持
     *
     * 这两个库都会对 DDL **隐式提交**。H2 这一条是实测出来的，不是推测：
     * 用裸 JDBC 做最小复现，`autoCommit = false` 的连接上执行 `CREATE TABLE`，
     * **在 commit 也好 rollback 也好之前**另借一条连接就已经能查到这张表了 ——
     * 说明它压根没进事务。回归见
     * `DdlTransactionIntegrationTest.H2 does not roll DDL back`。
     *
     * 后果一样：一次「保存表结构」算出的那串 `CREATE` / `ALTER`，中途失败时
     * 已经生效的部分留在库里。界面上因此不能对这两个方言承诺「全成功或全不做」，
     * 必须按方言如实提示（见前端 `DialectCapabilities`）。
     *
     * DuckDB / SQLite 干脆不声明：它们的 DDL 一旦提交就不可回滚。
     * 内置方言里**真正**支持事务性 DDL 的是 PostgreSQL。
     */
    DDL_TRANSACTION,

    /**
     * 嵌入式 / 本地运行（无远端服务端）。
     *
     * 内置方言：H2 / DuckDB / SQLite。连接表单据此隐藏 host / port / user / password。
     */
    EMBEDDED_MODE,
}
