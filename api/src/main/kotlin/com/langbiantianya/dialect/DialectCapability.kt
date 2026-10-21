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
 * 例外只有一种，且必须是**有前置里程碑**的那种：设计文档已经写明了某个标签的
 * 语义、以及后续会有哪些方言去声明它（例如 `docs/analysis-mode/README.md` §5.5 的
 * `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT`）。这时**先把枚举值落地**
 * 是安全的 —— 前提是**还没有任何方言真的声明它**：没人声明，能力集合里就不会出现它，
 * 前端也就不会渲染出对应入口。本文件末尾那三个标签正属于这一类。
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

    // ── 模式相关能力位（数据分析模式，见 docs/analysis-mode/README.md §5.5）──
    //
    // 上面那批标签回答的是「这个方言实现没实现某组 SPI 方法」；
    // 下面这三个回答的是另一组问题：「这个连接出现在哪些模式里」「它能不能写」。
    // 语义不同，但**同样是声明而不是探测** —— 各自的 KDoc 里说清了为什么。

    /**
     * 该方言可作为**浏览模式**的数据源。
     *
     * ## 它表达什么
     *
     * 「浏览模式」（连接管理 → 浏览 → 看表结构 / 编辑 / 跑 SQL）里，这个连接该不该出现。
     * 前端按能力位筛选连接列表 —— **一份 `connection.json` 不复制**：同一个连接
     * 可以既被浏览模式看到、又被分析模式看到，两个模式读的是同一份配置。
     *
     * ## 谁会声明它
     *
     * 将来**所有**内置方言都会声明（对现有的 MySQL / PostgreSQL / H2 / DuckDB / SQLite
     * 而言是「行为不变的一句声明」—— 它们今天当然能浏览），外加将来的 Hive / Impala。
     *
     * **现在一个都没声明**：枚举值先落地，声明留给后续里程碑。
     *
     * ## 为什么是「声明」而不是「探测」
     *
     * 「能不能浏览」是方言的**静态属性**，不是连上库问一句就能得出结论的事；
     * 真去探测意味着为了画一个列表先去建连接，既慢、又在连不上时无从判定。
     * 写成声明则保证前端**不会看到一个点了必然走不通的入口** ——
     * 与本文件其余标签的语义完全一致。
     */
    BROWSE_SOURCE,

    /**
     * 该方言可作为**数据分析模式**的数据源。
     *
     * ## 它表达什么
     *
     * 「数据分析模式」（只读连远端 → 分析 → 出 Excel 报表）里，这个连接该不该出现。
     * 与 [BROWSE_SOURCE] **并列而非互斥**：一个连接可以两组都声明（DuckDB 将来就是）
     * —— 那意味着**同一份配置**在两个模式里各出现一次，不是两份配置。
     *
     * ## 谁会声明它
     *
     * 将来：**DuckDB / Hive / Impala**。
     * DuckDB 是本地分析引擎（远端数据拉进本地 DuckDB 再算）；Hive / Impala 是远端只读源。
     * MySQL / PostgreSQL **不**声明 —— 它们是**可写**方言，放进分析模式等于给分析工作区
     * 开了一条真能写库的路（那时才需要会话级只读闸门兜底，见
     * `docs/analysis-mode/README.md` §3.4）。
     *
     * **现在一个都没声明**：枚举值先落地，声明留给后续里程碑。
     *
     * ## 为什么是「声明」而不是「探测」
     *
     * 能不能当分析数据源，取决于**引擎侧有没有对应的拉取与分析路径** ——
     * 那不是运行期问一句「你连得上吗」能得出的结论。声明把这件事写进元数据，
     * 前端据此筛选，**不显示点进去才发现走不通的入口**。
     */
    ANALYSIS_SOURCE,

    /**
     * 该方言是**只读方言**：引擎里根本没有往它写入的路径。
     *
     * ## 它表达什么
     *
     * **方言级**的只读 —— 写方法（建表 / 改表 / 删表 / 建视图 / 建索引 / 建用户 …）
     * 在该方言上**显式抛 `UnsupportedOperationException`**，引擎里压根不存在能把它改掉的
     * 那条路。从**浏览模式**进来写不了，从**分析模式**进来同样写不了。
     *
     * ⚠️ **与会话级只读开关是两回事**：后者是**兜底闸门**（分析会话带 `read_only` 标记
     * → 写类别一律拒绝），它存在的理由是「将来可写方言被加进分析模式」；本标签说的是
     * **更强**的那件事：这个方言**从哪个模式进来都写不了**。前端据此显示**只读徽标** ⓘ，
     * 让用户看到 Hive 连接时就知道它**根本不能写** —— 而不是「忘了开只读」。
     *
     * ## 谁会声明它
     *
     * 将来：**Hive / Impala**（它们在引擎里只有读路径）。
     * DuckDB / MySQL / PostgreSQL / H2 / SQLite 都不声明 —— 它们都能写；DuckDB 尤其如此：
     * 分析模式里的 `CREATE TABLE tmp AS SELECT …` 是**允许**的
     * （那是分析产物，落在本地工作区文件里，不碰远端）。
     *
     * **现在一个都没声明**：枚举值先落地，声明留给后续里程碑。
     *
     * ## 为什么是「声明」而不是「探测」
     *
     * 「引擎里有没有写入路径」是**实现事实**，只能由方言实现者声明；运行时去「探测」它的
     * 办法，恰恰是它要阻止的那件事 —— 试着写一下看会不会失败。写成枚举值还能让**界面层**
     * 据此收紧入口（**不渲染**任何写入口），而不是等一次失败的调用来告诉用户「这个不能写」。
     */
    READ_ONLY_DIALECT,
}
