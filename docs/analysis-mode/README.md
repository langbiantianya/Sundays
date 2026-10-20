# 数据分析模式 —— 设计文档

> **状态**：设计冻结待评审 · v0.1 · 2026-10-20
>
> **配套**：[`ui-mockup.html`](./ui-mockup.html)（可交互 UI 设计图） ·
> [`theme-tokens.json`](./theme-tokens.json)（从真实主题源码抽取的色值快照）
>
> **本文档的判据**：所有「现状」陈述都指��仓库里的真实文件与行号；所有「设计」陈述都写清了
> 为什么这么选、以及被否掉的方案是什么。**没读过代码就别改本文** —— 与仓库其余文档同一条规矩：
> 具体契约在 KDoc 里，设计理由在这里。

---

## 0. 一页速览

| 项 | 结论 |
|---|---|
| **是什么** | 一个**独立于现有「连接管理 → 数据库浏览」流程**的新顶层模式。职责只有两件：**数据分析**与**制作报表**。 |
| **远程数据源** | **恒为只读**——不是「有个只读开关」，是**结构上就没有写入路径**（见 §3）。 |
| **计算引擎** | **默认 DuckDB**（本地嵌入式分析引擎），可选 **Hive** / **Impala**。 |
| **当前阶段交付** | **Excel 报表**（.xlsx）。 |
| **后续迭代** | 接入 **AI** 做智能分析与报表生成（见 §6）。 |
| **样式** | **完全复用**现有主题系统：5 套配色 × 明暗 2 档，一行新样式代码都不写（见 §7）。 |
| **引擎侧改动** | 复用已有的 `EXPORT.RUN_EXPORT` / `IMPORT.RUN_IMPORT` / `ExportFormat.EXCEL` 链路；**新增** `Category.ANALYSIS` / `Category.REPORT` 两条只读路由。 |
| **最大风险** | `hive-jdbc` 依赖树与仓库现有 `hadoop-common 3.5.0` 的版本冲突（见 §10 R1），必须先做 spike。 |

---

## 1. 定位与边界

### 1.1 这个模式是什么

「数据分析模式」= 一个**面向分析的独立工作台**。它回答的问题是：

> 「我有一堆只读的数据源（数仓、库表），我想**看看数据长什么样**、**算出点东西**，
> 然后**做成一份能发给别人的 Excel 报表**。」

它**不是**「浏览模式换个皮」。两者的产品意图根本不同：

| | 数据库浏览模式（现有） | 数据分析模式（新增） |
|---|---|---|
| 用户身份 | 库的管理者 | 数据的**消费者** |
| 核心动作 | 浏览 / 编辑 / DDL / 造数 | 拉取 / 聚合 / 出报表 |
| 连接权限 | 可写（`readOnly` 是**可开关**的） | **恒为只读**（没有这个开关） |
| 数据位置 | 就在库里 | 远端只读源 → **本地物化** → 本地算 |
| 产出物 | 界面上的表格 | **一个 .xlsx 文件** |
| 连接配置 | `~/.config/sundays/connection.json` | `~/.config/sundays/analysis/sources.json`（**独立存储**） |

### 1.2 明确不做的事（v1）

写下来是为了**挡住范围蔓延**，每一条都对应一次「顺手就做了」的诱惑：

- ❌ 不做图表 / 仪表盘 / BI 画布 —— v1 只出**表格型** Excel 报表。
  （理由：POI 的图表 API 极不稳定，且报表的价值在数据与结论，不在图。）
- ❌ 不做定时任务 / 报表订阅 / 邮件推送。
- ❌ 不做数据清洗 / ETL 编排（只做「拉取 + 查询」，不做管道）。
- ❌ 不做多用户 / 权限模型。
- ❌ **不在分析模式里提供任何写入能力**——包括「建临时表到远端」「往本地 DuckDB 写东西」之外的一切。
  （本地 DuckDB 工作区**可以**写：那是分析产物，不是数据源。这条边界很重要，见 §3.3。）
- ❌ 不把 AI 能力混进 v1 的交付范围（只留接口与入口占位，见 §6）。

### 1.3 与现有浏览流程的关系：**并排，不嵌套**

设计上刻意让两者**互不可达**：

- 新增顶层导航目标 `AppDestination.ANALYSIS`
  （[`shared/src/commonMain/.../navigation/AppDestination.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/navigation/AppDestination.kt)），
  与现有的 `CONNECTIONS` / `DATABASE` / `SETTINGS` 并列。
- **不复用** `ConnectionSession` / `DatabaseBrowserState` / sheet 标签条 —— 那套状态机是
  「一个连接 = 一个可写浏览器」，与分析模式的工作流不匹配。
- **不复用** `ConnectionConfig` / `DialectType` —— 那两个是「管理连接」的模型（可写、五种方言、
  存 `connection.json`）。分析模式的数据源是**另一种东西**：只读、三种类型、独立存储。
  把它们塞进同一个枚举，会让「这个连接能不能写」变成一个**运行时才知道的字段**，
  而只读必须是**类型层面**就确定的事（见 §3）。

---

## 2. 总体架构

### 2.1 一句话架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  分析模式（desktopApp: AnalysisScreen / ReportDesignerScreen）        │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ EngineClient（既有接口，不改签名）
┌───────────────────────────▼──────────────────────────────────────────┐
│  引擎 :engine                                                       │
│    Category.ANALYSIS  ── 只读分析 SQL / 拉取样本                    │
│    Category.REPORT    ── ReportSpec(JSON) → .xlsx                    │
│    Category.EXPORT    ── 复用：远端 → CSV                            │
│    Category.IMPORT    ── 复用：CSV → DuckDB                          │
└────────────┬────────────────────────────────────┬────────────────────┘
             │                                    │
   ┌─────────▼──────────┐              ┌──────────▼──────────────┐
   │ 远程只读数据源       │              │ 计算引擎：DuckDB        │
   │ dialect-hive        │  拉取(CSV)   │ dialect-duckdb（复用）  │
   │ dialect-impala      │ ──────────▶ │ ~/.config/sundays/      │
   │ 写方法全部抛         │              │   analysis/workspace.  │
   │ UnsupportedOperation │              │   duckdb               │
   └─────────────────────┘              └─────────────────────────┘
```

### 2.2 三个角色，别混

| 角色 | 是什么 | 本期取值 |
|---|---|---|
| **计算引擎** | 跑分析 SQL 的地方 | **DuckDB**（默认）/ Hive / Impala |
| **数据源** | 数据**从哪来** | 远程只读 Hive / Impala / 本地文件 |
| **报表产物** | 数据**到哪去** | .xlsx |

> ⚠️ **「默认引擎为 DuckDB」的两种读法，本文选第二种**：
>
> - 读法 A：DuckDB 是「又一个可连的数据库」，和 MySQL 并列在连接列表里。
> - 读法 B（**本文采用**）：DuckDB 是**分析模式自带的计算引擎**，默认且常驻；
>   Hive / Impala 是**数据源**，需要时才连。
>
> 选 B 的理由：分析模式的产物是报表，报表要的是「能算」而不是「能浏览」；
> DuckDB 的向量化执行 + 零配置嵌入式正是为这件事准备的。若按读法 A，用户还得先手工建一个
> DuckDB 连接、再手工把远端数据灌进去——那不是「分析模式」，是「浏览模式 + 两次手工搬运」。
>
> 「可选 Hive 和 Impala」在读法 B 下的含义是：**这三者都可以作为计算引擎**
> （Hive 直接跑 HiveQL 出结果、Impala 直接跑 Impala SQL 出结果），
> 典型组合是「Hive 当引擎算 + Hive 当数据源查」或「Impala 当引擎算 + Hive 当数据源查」。

### 2.3 数据流（一期）

```
① 选远程表（只读浏览）      ② 拉取到本地         ③ 本地分析          ④ 出报表
   dialect-hive/impala   ──▶  DuckDB 工作区    ──▶  ReportSpec    ──▶  .xlsx
   listTables/listColumns     staging_<表名>        (标题/区块/列规格)   (POI)
   （只读 SPI）                （复用 EXPORT+IMPORT）  （可由人或 AI 生成）
```

**④ 的关键约束**：报表的每个数字都必须来自**一条用户看得见的 SQL**。
AI 也不例外——AI 只能产出 `ReportSpec`（含 SQL），不能直接产出文件（见 §6）。

### 2.4 拉取链路：为什么先走 CSV 中转

「远端 → DuckDB」有两条路：

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. DuckDB extension 直连** | DuckDB 的 `hive` / `httpfs` extension 直接读远端 | 无中转、快 | extension 要**联网下载**，内网/离线必挂；Impala **没有**对应 extension | ❌ 不做 |
| **B. 应用层流式搬运** | 引擎里逐行 `ResultSet` → DuckDB 批量 insert | 不依赖 extension | 要自己写搬运与类型映射 | ⏳ 作为 B2 优化 |
| **C. 复用现成导出/导入链路** | 远端 `EXPORT` → CSV → DuckDB `IMPORT` | **链路已存在且有测试**，零新协议，带进度与取消 | 多一次磁盘往返 | ✅ **一期采用** |

**一期选 C**，理由很实际：`EXPORT.RUN_EXPORT`（[engine/handlers/ExportHandler.kt`](../../engine/src/main/kotlin/com/langbiantianya/handlers/ExportHandler.kt)）
与 `IMPORT.RUN_IMPORT`（[engine/handlers/ImportHandler.kt`](../../engine/src/main/kotlin/com/langbiantianya/handlers/ImportHandler.kt)）
**都已经实现、都有集成测试**，而且导出跑在**独立子进程**里（`ExportProcessManager` / `ExportSubProcess`），
大查询不会把 UI 线程拖死。复用它的代价只是一次 CSV 落盘。

把 B（应用层直搬）列为二期性能项：十万行级别 C 完全够用，百万行再上 B。

> ⚠️ 代价要说清楚：C 的磁盘占用是**两份**（CSV + DuckDB 表）。
> 拉取面板必须显式显示预计占用与临时 CSV 的清理时机，不能让用户莫名其妙吃掉几个 G。

---

## 3. 只读保证：五层防线 ★

> 这是整个设计里**最不能出错**的一节。用户说「这里面的连接的远程数据库都只能只读」——
> 如果最后只做到「界面上没写按钮」，那这个承诺是不成立的：任何一个能改 URL、
> 任何一个走错路由的调用，都可能写进去。

### 3.1 五层，逐层收紧

| 层 | 位置 | 手段 | 挡得住什么 |
|---|---|---|---|
| **L1 账号** | 远端 Hive/Impala | 用**只读授权账号** | 一切（前提是运维配合） |
| **L2 连接** | JDBC | `Connection.setReadOnly(true)` + 只读连接池参数 | 部分驱动的写 |
| **L3 引擎闸门** | `RequestDispatcher` | 请求带 `read_only` 标记 → **写类别一律拒绝** | UI 漏了、后门路由 |
| **L4 API 面** | 协议 | 分析模式**只走** `Category.ANALYSIS` / `REPORT`，两条都是读路由 | 误用别的路由 |
| **L5 界面** | UI | 分析模式**不渲染**任何写入口（不是禁用，是不存在） | 用户误操作 |

L1 依赖运维配合、L2 在多数驱动上只是**提示**而非保证——真正兜底的是 **L3 + L4**。
L5 是体验层（让人不去找），**不是**安全层。

### 3.2 L3：引擎闸门怎么落

**不新增一个「只读模式」开关到既有请求里**（那会让所有既有请求都多一个待填字段）。
改为**新增一个专门的分析会话**：

```
ConnectionConfig  ──analysis_mode──▶  AnalysisSessionConfig
   （既有，五种方言）                    （新的，只读语义）
```

具体落法：

1. **协议**：`ConnectionConfig` 加一个 `bool read_only = 13;`（proto3 追加字段号，向后兼容）。
   `RequestDispatcher` 在**路由之前**检查：`read_only == true` 时，
   命中写类别（`TABLE.CREATE/UPDATE/DELETE/RENAME/TRUNCATE`、`DATA.INSERT/UPDATE/DELETE`、
   `IMPORT.RUN_IMPORT`、`USER.*`、`SCHEMA.CREATE/DELETE`、`VIEW.CREATE/DROP`、
   `INDEX.*`、`FOREIGN_KEY.*`、`TRIGGER.*`、以及 `SQL.EXECUTE` 里**非 SELECT** 的语句）
   一律直接返回错误，**不进入 handler**。
2. **SQL.EXECUTE 的特殊性**：它是「万能入口」，不能按类别拦。
   复用**已有的** `DangerousSql.scan(sql)`（[shared/src/commonMain/.../sql/DangerousSql.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/sql/DangerousSql.kt)）——
   它已经能剥注释、识别 `SELECT` / `WITH … SELECT` / 多语句里的写操作。
   浏览模式的 `readOnly` 拦截用的就是它（[desktopApp/.../DatabaseBrowserState.kt:2980](../../desktopApp/src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt)），
   但那是在**界面层**；分析模式要把它下沉到**引擎层**。
3. **不靠「字符串里有没有 INSERT」来判断**：`DangerousSql` 认的是**关键字集合**，
   而 Hive/Impala 允许 UDF、允许 `CALL`，理论上存在绕过。
   所以 L3 是**双保险**：`DangerousSql` 扫一遍 + **L4 的窄门**（分析会话根本不开 `SQL.EXECUTE` 路由）。

### 3.3 L4：API 面才是真窄门

`Category.ANALYSIS` 下的路由**只有读**：

| 路由 | 作用 |
|---|---|
| `ANALYSIS.LIST_SOURCES` | 列分析数据源 |
| `ANALYSIS.LIST_TABLES` | 列远端表（只读 SPI） |
| `ANALYSIS.LIST_COLUMNS` | 列远端列（只读 SPI） |
| `ANALYSIS.RUN_QUERY` | 在**计算引擎**上跑分析 SQL（只读引擎 / 本地 DuckDB） |
| `ANALYSIS.PULL_SAMPLE` | 从远端拉样本到本地工作区 |

`Category.REPORT` 下：

| 路由 | 作用 |
|---|---|
| `REPORT.BUILD_EXCEL` | `ReportSpec` → .xlsx |

**注意「本地 DuckDB 工作区可以写」这条边界**：`ANALYSIS.RUN_QUERY` 在 DuckDB 上执行时，
用户写的 `CREATE TABLE tmp AS SELECT …` 是**允许**的——那是分析产物，落在本地工作区文件里，
**不碰任何远端**。L3 的闸门因此**按「连接的是不是远端只读源」判定**，而不是「一律禁写」。
这条边界如果搞混，会把分析模式变成「连中间表都建不了」的死胡同。

### 3.4 方言层：只读是**实现出来的**，不是**配置出来的**

`api` 的 SPI 里，视图 / 索引 / 外键 / 触发器 / 改表名 / 清表这些方法**默认抛
`UnsupportedOperationException`**（[api/.../DatabaseDialect.kt](../../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt)）。
`dialect-hive` / `dialect-impala` 只需：

- **实现**：列库 / 列 schema / 列表 / 列列 / 引号 / 建列定义（供拼 SQL 用）
- **不实现**（保持默认抛错）：建 schema / 删 schema / 建用户 / 授权 / 建视图 / 建索引 …
- **显式抛错**：SPI 里少数是**抽象方法**（`createSchema` / `deleteSchema` / `createUser` /
  `deleteUser` / `updatePassword` / `updatePrivileges` / `listUsers` / `listPrivileges` /
  `listAllGrants`）——这两个方言里它们**显式抛 `UnsupportedOperationException`**。

同时**不声明**任何写相关能力位（`DialectCapability` 里 `USERS` / `PRIVILEGES` / `VIEWS` /
`INDEXES` / `FOREIGN_KEYS` / `TRIGGERS` / `DDL_TRANSACTION` 全部不声明）。
这与 `DialectCapability` 的既有语义一致：「标签是**声明**，不是探测；不声明的按钮前端不渲染，
用户永远点不到一个必然失败的调用上」。

### 3.5 验收：只读必须有测试钉住

**每一个写类别都要有一条「在只读会话里被拒」的回归测试**，并且断言的是
「引擎返回了明确的只读错误」而不是「抛了个奇怪的 SQLException」。

> 📌 **本仓库的教训**（见 `desktopApp/TEST_CASES.md` §6）：测试的判据要落在
> 「**发出去的那条语句**」上。「只读模式下写操作被拦」这个判据，必须真的发一条
> `INSERT` 给引擎、并断言引擎**在进入 handler 之前**就拒了它 —— 而不是断言
> 「某个 UI 按钮是 disabled」。后者在 UI 重构后依然绿，而功能其实已经漏了。

---

## 4. 数据源：Hive 与 Impala

### 4.1 驱动选型

| 目标 | 驱动 | 说明 |
|---|---|---|
| Hive | `org.apache.hive:hive-jdbc`（standalone 分类） | 走 HiveServer2 的 Thrift 协议。仓库现有 `hadoop-common 3.5.0` 已铺了 Thrift/HDFS 的底，但版本与 `hive-jdbc` 的 Hadoop 依赖**可能打架**（见 R1）。 |
| Impala | **同一个** `hive-jdbc` | Cloudera Impala 兼容 **HiveServer2 协议**，所以同一个驱动能连 —— ⚠️ **这是假设，必须先验证**（见 4.2）。 |
| DuckDB | `org.duckdb:duckdb_jdbc` | **仓库已有**（`libs.versions.toml` 的 `duckdb = 1.5.5.1`），`dialect-duckdb` 也已有，**零新增**。 |

> 💡 **Impala 走 HS2** 是这条路线能成立的关键。若实测不成立，退路是
> Impala 自带的 JDBC 驱动（Cloudera 分发，许可与获取渠道另议），
> 或把 Impala 降级为「通过 Hive Metastore + Parquet 直读」——那会重新引入 extension 下载问题。

### 4.2 ⚠️ 必须在写任何代码之前验证的假设

**M2 的第 0 号任务是一个 spike，不产出功能代码，只产出一条结论**：

> ① `hive-jdbc` 能否连上目标环境的 HiveServer2？
> ② 同一个驱动能否连上 Impala 的 HS2 端点？
> ③ `hive-jdbc` 与仓库现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 是否冲突？
> 冲突怎么解（`exclude` 降级 / 换版本 / 拆 ClassLoader）？
> ④ 目标环境是 CDH / HDP / Cloudera / EMR / 自建？**版本号是多少？**

**这四条里任何一条答不上来，M2 的方案就要改。** 在此之前写出来的 `dialect-hive` 可能是白写。

### 4.3 方言实现面

两个方言都是**只读子集**，实现量不大：

| SPI 方法 | Hive | Impala |
|---|---|---|
| `listDatabases` | `show databases` | `show databases` |
| `listSchemas` | Hive 无 schema 层 → 抛 `UnsupportedOperationException` | 同 Hive |
| `listTables` | `show tables` / `show tables in <db>` | `show tables` |
| `listColumns` | `describe <table>`（**不要**用 JDBC `getColumns`，Hive 的元数据走 Thrift 更准） | `describe <table>` |
| `quoteIdentifier` | 反引号 `` ` `` | 反引号 `` ` `` |
| `buildColumnDefinition` | 抛（只读） | 抛（只读） |
| `jdbcUrlPrefix` | `jdbc:hive2:` | `jdbc:hive2:` ⚠️ **与 Hive 同前缀** |

> ⚠️ **`jdbcUrlPrefix` 撞车**：Hive 与 Impala 用**同一个驱动、同一个 URL 形状**
> （`jdbc:hive2://host:port/db`），而引擎靠 URL 前缀反查方言
> （`PoolManager.resolveDialect`）。两个方言抢同一个前缀会让「按 URL 反查」**必然歧义**。
>
> 解法（spike 要一并验）：给 Impala 用一个**自定义 URL 形状**（例如
> `jdbc:impala2://…`），由 `dialect-impala` 把它翻译成驱动认识的 `jdbc:hive2://…`；
> 这样 `jdbcUrlPrefix` 唯一，`driverName` 仍分别是 `Hive` / `Impala`。
> **这条不能等到写代码时才发现。**

### 4.4 能力位

新增一个能力位：

```
DialectCapability.ANALYSIS_SOURCE
```

语义：**该方言可以作为分析模式的只读数据源**。内置分布：DuckDB / Hive / Impala 声明；
MySQL / PostgreSQL / H2 / SQLite **不声明**（v1 不让它们进分析模式 ——
理由见 §11 Q3）。

### 4.5 配置与凭据

- 存储：`~/.config/sundays/analysis/sources.json`（**独立**于 `connection.json`）
- 模型：`AnalysisSourceConfig`（**独立**于 `ConnectionConfig`）——
  `id` / `name` / `type`(DUCKDB|HIVE|IMPALA) / `host` / `port` / `database` /
  `user` / `password` / `jdbcUrl`
- 明文口令与现有 `connection.json` 同级（仓库现状如此），**不因为新功能引入更差的存储**；
  但**不能**因此在文档里说「已加密」——没有。
- 凭据**只用于只读分析**，不与浏览模式的连接池共享。

---

## 5. 报表模型与 Excel 输出

### 5.1 为什么不能直接复用 `ExportEngine`

仓库**已经有** Excel 导出：
[`engine/src/main/kotlin/com/langbiantianya/export/ExcelWriter.kt`](../../engine/src/main/kotlin/com/langbiantianya/export/ExcelWriter.kt)
（POI `SXSSFWorkbook` 流式写，单 Sheet 100 万行上限，超限自动开 `Sheet2`/`Sheet3`），
由 `ExportFormat.EXCEL` 驱动。

但**报表 ≠ 导出**：

| | `ExcelWriter`（导出） | 报表 |
|---|---|---|
| 结构 | 一个 Sheet、表头 + 数据行 | 标题 + 元信息 + **多个区块** |
| Sheet 命名 | `Sheet1` / `Sheet2`（按行数上限切） | **有业务含义**（`区域汇总` / `订单明细`） |
| 样式 | 无 | 表头底色、加粗、斑马纹、冻结首行、自动筛选 |
| 数字格式 | 无（`BigDecimal` 直接 `toString`） | 千分位 / 货币 / 百分比 / 日期 |
| 标题与来源 | 无 | 有（谁生成的、什么时候、什么过滤条件） |
| 产出物 | 数据 | **可发给他人的结论** |

结论：**新增 `engine/report/` 包**，与 `engine/export/` **并列**而不是合并。
理由是 `ExportWriter` 的契约是「**一行流式追加**」，报表是「**多区块布局**」——
两种数据流形状硬合并会让 5 个导出 writer 全部沾上报表概念。

**要复用的部分**：类型分派（`BigDecimal` / `Date` / `Timestamp` / `LocalDate` / `LocalDateTime`
→ 单元格）抽成共享的 `CellValueWriter`，两边都用。

### 5.2 `ReportSpec`（纯数据，不含 POI）

放在 `:shared` 的 `commonMain`（平台无关，且 UI 与引擎都要读它）。

```kotlin
@Serializable
data class ReportSpec(
    val schemaVersion: Int = 1,      // 演进用；引擎对不认识的版本明确报错
    val title: String,
    val subtitle: String? = null,
    val metadata: List<MetadataEntry> = emptyList(),  // 数据源 / 生成时间 / 过滤条件
    val sections: List<ReportSection>,
    val style: ReportStyle = ReportStyle(),
)

@Serializable
data class ReportSection(
    val id: String,
    val kind: SectionKind,            // TABLE | KPI | TEXT
    val title: String,
    val query: ReportQuery,           // { sql, params }
    val columns: List<ReportColumn> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ReportColumn(
    val key: String,                   // 结果集列名
    val header: String,               // 表头文字
    val type: ColumnType,             // TEXT | INTEGER | DECIMAL | PERCENT | CURRENCY | DATE | DATETIME
    val format: String? = null,       // Excel number format，如 "#,##0" / "¥#,##0.00" / "0.0%"
    val aggregate: Aggregate? = null, // SUM | AVG | COUNT | MAX | MIN（KPI 区块用）
    val width: Int? = null,
)

@Serializable
data class ReportStyle(
    val headerBackground: String? = null,  // 默认取主题 primaryContainer
    val zebra: Boolean = true,
    val freezeHeader: Boolean = true,
    val autoFilter: Boolean = true,
    val fontSize: Int = 11,
)
```

> **为什么 `ReportSpec` 在 `commonMain` 而不在 `desktopApp`**：它要被**三处**读 ——
> UI 的报表设计器（构造/编辑）、引擎的 `ReportBuilder`（渲染）、以及将来的 AI（生成）。
> 放在 `desktopApp` 会让引擎反过来依赖 UI 模块。

### 5.3 `ReportBuilder`（引擎侧，POI）

`engine/src/main/kotlin/com/langbiantianya/report/`：

- `ReportBuilder` —— `ReportSpec` + 数据 → `.xlsx`
- `ReportCellStyler` —— 把 `ReportStyle` / `ReportColumn` 落到 POI `CellStyle`（**样式对象要缓存复用**，否则百万行的表会创建百万个 style）
- `CellValueWriter` —— 从 `engine/export/ExcelWriter.kt` 抽出来的共享类型分派

> ⚠️ **POI 样式对象的坑**：`XSSFCellStyle` 每建一个就是一份 XML，开到几万行会 OOM。
> 报表的列数有限（通常 < 30），按「列类型 × 样式」缓存几十个 style 即可 ——
> 这条要写进 `ReportCellStyler` 的 KDoc，否则下一个人会 inline 写 `createCellStyle()`。

### 5.4 协议

```
Category.ANALYSIS = 15    // v2.29
Category.REPORT   = 16    // v2.29
Action.RUN_ANALYSIS_QUERY = 26
Action.PULL_SAMPLE        = 27
Action.LIST_SOURCES       = 28
Action.BUILD_EXCEL        = 29
```

`REPORT.BUILD_EXCEL` 的请求体：

```protobuf
message ReportRequest {
  string spec_json = 1;   // ReportSpec 的 JSON
  string output_path = 2; // 目标 .xlsx 路径
}
```

> **为什么 `spec_json` 走 JSON 而不是把 `ReportSpec` 铺成一棵 proto message**：
>
> ① `ReportSpec` **还在演进**（AI 迭代要加字段），proto message 一旦发布就要做兼容；
> ② 引擎**已经有** `kotlinx.serialization`（`ExportRequest` / `ExportCommand` 都在用），
>    JSON 零新增依赖；
> ③ proto 里铺一棵树会让 `idb_engine.proto` 膨胀 200+ 行，而它同时服务着 gRPC 与 Direct 两种模式。
>
> **代价**：失去 proto 的类型检查与向后兼容保证。
> **缓解**：`ReportSpec.schemaVersion` 必带，引擎遇到不认识的版本**明确报错**而不是猜。

---

## 6. AI 洞察：扩展点怎么留

> 用户要求「后续迭代接入 ai 实现智能分析制作报表」。v1 不做，但**接口要留对**——
> 留错了将来要动执行链路，那就是大改。

### 6.1 唯一的一条铁律

> **AI 只产出 `ReportSpec`，不产出文件。**

理由：

1. **安全**：执行链路不变，AI 挂掉不影响手动出报表。
2. **可回放**：`ReportSpec` 里存着 SQL 与参数，同一份 spec 永远出同一份文件。
3. **可审阅**：报表里每个数字都能追到一条用户看得见（且能改）的 SQL。
   AI 若是直接写文件，用户就永远不知道那些数字是怎么来的 —— **报表的可信度就断了**。
4. **可测试**：`ReportSpec` 是纯数据，AI 的产出可以直接断言「它选了哪张表、用了什么聚合」。

### 6.2 接口

```kotlin
// engine/src/main/kotlin/com/langbiantianya/analysis/InsightProvider.kt
interface InsightProvider {
    /** 输入：目标源的 schema 摘要 + 样本统计 + 用户问题 */
    suspend fun suggest(context: InsightContext): ReportSpec
}
```

- `InsightContext` 里放**受控的摘要**（表名、列名、类型、行数估计、少量样本统计），
  **不放原始数据**——把整表塞给模型既贵又不安全。
- 生成的 SQL 同样过 §3.2 的只读闸门。
- 引擎侧预留路由 `ANALYSIS.SUGGEST`（v1 不实现，返回「未启用」）。

### 6.3 UI 占位

分析工作台工具条右侧放一个「AI 洞察」按钮：v1 **禁用** + tooltip「后续迭代提供」。
位置摆在那里，是为了让 v1 的布局**不因 v6 而重排**。

---

## 7. 界面设计

完整设计图：[`ui-mockup.html`](./ui-mockup.html)（浏览器直接打开，顶部可切 5 套配色 × 明暗 × 4 个画面）。

### 7.1 样式完全复用现有资源

**一行新样式代码都不写。** 依据：

- 主题入口 [`shared/.../ui/Theme.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/Theme.kt)
  的 `SundaysTheme` —— 分析模式的所有界面都在它内部渲染，配色 / 明暗 / 紧凑档**自动继承**。
- 配色 [`shared/.../ui/ThemePalette.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemePalette.kt)：
  5 套（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）× 明暗 2 档 = 10 套 `ColorScheme`。
- 真实色值见 [`theme-tokens.json`](./theme-tokens.json) —— 这是**从
  `SundaysPalette.kt` 脚本抽取**的快照（10 套 × 28 个 Material3 角色 + 9 条字号阶梯），
  不是手抄的。设计图里的每一个颜色都能在这个文件里找到出处。
- 组件复用：`CodeEditor`（SQL 编辑）、`DataTable`（结果表）、`DragHandle`（面板宽度）、
  `EmptyHint`（空态）、`ContextMenuState`（右键菜单）—— 全在 `:shared` 的 `commonMain`，
  直接用。

### 7.2 设计约束（与现有界面一致）

来自 `SundaysPalette.kt` 的 KDoc，设计图必须体现：

1. **不用 tonalElevation 叠色分层** —— 层次靠 **1px `outlineVariant` 分隔线**，不靠阴影。
2. **圆角 4~8dp**（现代三套 6px；Win2000 全直角 0；WinXP Luna 递增）。
3. **字号比 M3 默认小 1sp**（`bodyMedium` 13sp / `labelSmall` 11sp）—— 信息密集的桌面观感。
4. **WCAG AA**（≥4.5:1）由 `SundaysPaletteTest` 逐对钉住，设计图不得引入新配色破坏它。

### 7.3 四个画面

| 画面 | 内容 |
|---|---|
| **① 顶层导航** | 新增「数据分析」顶层 chip（与「连接管理」并排）。下方说明卡片讲清本模式的边界。 |
| **② 分析工作区** | 左：数据源树（本地工作区 / 远程只读源，远程表按需拉取）。中：SQL 编辑器 + 结果表（带占比条形）。顶栏：`⊘ 只读` **不可关闭**的徽标 + 运行 / 生成报表。 |
| **③ 报表设计器** | 左：区块列表（可增删排序）。中：区块配置（列规格 / 类型 / 数字格式 / 样式）。右：Excel 观感预览（标题 + 元信息 + 表头 + 斑马纹）。 |
| **④ 数据源连接** | Hive / Impala / DuckDB 选择 + 主机端口账号 + **只读提示块**（`tertiaryContainer` 底）。 |

---

## 8. 代码落点

### 8.1 新增模块

| 路径 | 内容 |
|---|---|
| `shared/src/commonMain/.../analysis/` | `AnalysisSourceConfig`（只读数据源模型）、`ReportSpec.kt`（报表规格）、`AnalysisScreen.kt`、`ReportDesignerScreen.kt`、`AnalysisSourceDialog.kt` |
| `desktopApp/src/main/.../AnalysisSession.kt` | 分析模式的会话与状态机（与 `ConnectionSession` / `DatabaseBrowserState` **并列**，不继承） |
| `engine/src/main/.../analysis/AnalysisHandler.kt` | `Category.ANALYSIS` 的路由实现 |
| `engine/src/main/.../analysis/InsightProvider.kt` | AI 扩展点（v1 只有接口） |
| `engine/src/main/.../report/ReportBuilder.kt` | `ReportSpec` → .xlsx |
| `engine/src/main/.../report/ReportCellStyler.kt` | 样式映射（**带缓存**） |
| `dialect-hive/` | Hive 只读方言（SPI 注册照抄 `dialect-duckdb` 的 `META-INF/services`） |
| `dialect-impala/` | Impala 只读方言（**自定义 URL 前缀**，见 §4.3） |

### 8.2 改动既有文件

| 文件 | 改动 |
|---|---|
| `shared/.../navigation/AppDestination.kt` | 加 `ANALYSIS(StrKey.NAV_ANALYSIS)` |
| `desktopApp/.../main.kt` | `MainScreen` 的 destination 分支加分析模式；`AppDestination` 枚举扩容 |
| `api/.../DialectCapability.kt` | 加 `ANALYSIS_SOURCE` |
| `engine-protocol/.../idb_engine.proto` | `Category.ANALYSIS/REPORT` + `Action` + `ReportRequest` + `ConnectionConfig.read_only` |
| `engine/.../dispatcher/RequestDispatcher.kt` | 只读闸门（L3） |
| `engine/.../export/ExcelWriter.kt` | 类型分派抽到 `CellValueWriter`（**不改变现有行为**） |
| `i18n/src/main/.../StrKey` + 6 张文案表 | 新增文案键（**六语言必须同步**，仓库有 `TextsConsistencyTest` 之类的约束） |
| `settings.gradle.kts` | `include(":dialect-hive")` / `include(":dialect-impala")` |
| `desktopApp/build.gradle.kts` | `runtimeOnly(project(":dialect-hive"))` 等 + 驱动依赖 |
| `gradle/libs.versions.toml` | `hive-jdbc` 版本（spike 结论出来后定） |

### 8.3 打包要留意

`desktopApp/build.gradle.kts` 的 jlink `modules(...)` 清单是**手工枚举**的（12 个），
注释里写明「升级依赖后要重算一遍」。Hive 驱动会引入 Thrift / SLF4J / 可能的 SSL 依赖，
**新模块可能要补进 jlink 清单** —— 否则症状是「装得进去却启不了」（注释里已写明这个坑）。

---

## 9. 迭代任务拆分

> 每个里程碑都以「**用户能看见的东西**」结束，不以「代码写完了」结束。
> 每条验收标准都写成**可判定**的（能跑出一条命令 / 能点出一个现象），
> 避免「基本能用」这种没法验收的说法。

### M0 —— 设计冻结 ✅（本轮）

- **交付**：[本文](./README.md) + [UI 设计图](./ui-mockup.html) + [主题色值快照](./theme-tokens.json)
- **验收**：本文的每条「现状」陈述都能在仓库里找到对应文件；UI 设计图在 10 种配色 × 4 个画面下都能读。
- **产出**：待定问题（§11）逐条有结论。

### M1 —— 只读地基

- **交付**
  - proto：`Category.ANALYSIS` / `Category.REPORT` / 新 Action / `ConnectionConfig.read_only`
  - 引擎：`RequestDispatcher` 的只读闸门（L3）；`ANALYSIS.LIST_SOURCES` / `RUN_QUERY` 两条只读路由
  - UI：`AppDestination.ANALYSIS` + 空壳主屏 + **不可关闭**的只读徽标
  - 测试：每个写类别一条「只读会话里被拒」的回归测试（§3.5）
- **验收**
  - 在只读会话里发 `TABLE.CREATE` / `DATA.INSERT` / `IMPORT.RUN_IMPORT` / `SQL.EXECUTE("INSERT …")`
    —— **全部**返回明确的只读错误，且**不进入 handler**
  - 同一个 `INSERT` 在普通会话里照常成功（证明闸门只挡只读会话，没把功能打死）
  - 界面上「只读」徽章**没有关闭入口**
- **依赖**：无

### M2 —— 数据源接入（Hive / Impala）

- **交付**
  - **2.0 spike（先做，不产出功能代码）**：§4.2 的四条假设各给出一条**可复现**的结论
  - `dialect-hive` / `dialect-impala`（只读子集）
  - `DialectCapability.ANALYSIS_SOURCE`
  - `AnalysisSourceConfig` + 独立持久化 + 连接表单
- **验收**
  - 对着**真实** HiveServer2：能列出库 → 表 → 列，列名与类型正确
  - 对着**真实** Impala：同上（若 spike 结论是「HS2 不通」，本里程碑改为只交付 Hive，
    并在文档里如实记录 Impala 的状态 —— **不假装做完**）
  - 引擎对该数据源发一条 `INSERT` → 被拒（连接层 + 路由层两道都验）
  - `jdbcUrlPrefix` 无歧义（§4.3 的问题已解决并有测试钉住）
- **依赖**：M1
- **风险**：R1（依赖冲突）、R2（HS2 兼容性）

### M3 —— 分析工作台

- **交付**
  - DuckDB 工作区（**复用 `dialect-duckdb`，零新方言**）
  - 拉取链路：远端 `EXPORT` → CSV → DuckDB `IMPORT`（§2.4 的方案 C）
  - SQL 编辑（复用 `CodeEditor`）+ 结果表（复用 `DataTable`）
  - 拉取面板显示**预计占用**与临时文件清理时机
- **验收**
  - 拉一张 **10 万行**的表进 DuckDB（用 `sundays_smoke` 或本地造的等价数据），
    界面显示进度、可取消，落地后可查
  - 在工作区里跑一条**窗口函数**聚合（`OVER (PARTITION BY …)`），结果正确
  - 工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**（本地写允许，远端写禁止）
  - 拉取产生的临时 CSV 在**成功/失败/取消**三条路径上都被清理
- **依赖**：M2

### M4 —— 报表模型与 Excel 输出

- **交付**
  - `ReportSpec`（`:shared` commonMain，纯数据）+ `schemaVersion`
  - `engine/report/ReportBuilder` + `ReportCellStyler`（带缓存）
  - `REPORT.BUILD_EXCEL` 路由（`spec_json`）
  - 手工写一份 `spec_json` → 引擎产出 .xlsx
- **验收**
  - 产出文件用 POI **重新打开**能读到：标题行、元信息行、表头、**全部**数据行
  - 列的数字格式生效（`#,##0` → `1,284`；`¥#,##0.00`；`0.0%`）
  - 冻结首行 / 自动筛选 / 斑马纹都在
  - **10 万行**的报表生成不 OOM（`ReportCellStyler` 的缓存生效，style 数量不随行数增长）
  - `schemaVersion` 不认识时引擎**明确报错**（不是静默忽略字段）
- **依赖**：M1（路由）；M3（能拿到数据）

### M5 —— 报表设计器 UI

- **交付**：区块列表 / 区块配置（列规格、类型、数字格式、样式）/ Excel 预览 / 导出
- **验收**
  - 在设计器里把某一列的类型从 `DECIMAL` 改成 `PERCENT` → **预览立即变** → 导出的文件也一致
  - 增删区块 / 调区块顺序 → 导出文件的区块顺序一致
  - 预览与导出文件的**内容**逐格一致（预览不是另画一套，是同一份 spec 的渲染）
- **依赖**：M4

### M6 —— AI 洞察（后续迭代，不在 v1 范围）

- **交付**：`InsightProvider` 实现 + `ANALYSIS.SUGGEST` 路由 + UI 入口启用
- **验收**
  - AI 产出的 spec 经**同一套**校验与渲染链路（与手动出报表无差别）
  - AI 生成的 SQL 过只读闸门（构造一个含写操作的 prompt，断言被拒）
  - 模型不可用时手动出报表**不受影响**
- **依赖**：M4 / M5

### 横向任务（每个里程碑都要做）

| 项 | 说明 |
|---|---|
| **i18n 六语言** | 新文案键必须**六张表同步**（简 / 繁 / EN / RU / JA / DE），仓库有文案一致性测试盯着 |
| **文档同步** | `ARCHITECTURE.md` / `FEATURES.md` / `TEST_CASES.md` / 各模块 README —— 文档是契约 |
| **行尾与格式** | `ktlintFormat` → `fix-crlf.ps1` → **按字节校验**（`.sh` 保持 LF，其余 CRLF 无 BOM） |
| **打包** | 新依赖进来后**重算 jlink modules 清单**（见 §8.3 的坑） |

---

## 10. 风险登记

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| **R1** | `hive-jdbc` 依赖树与现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 版本冲突 | **高** | **M2.0 spike 必须先验**。备选：`hive-jdbc:standalone`（自带依赖、避免传递冲突）/ 降级 `hadoop-common` / 独立 ClassLoader 加载方言 |
| **R2** | Impala 的 HS2 兼容性未验证 | **中** | 同上 spike。若不通，Impala 降级为「后置里程碑」，**如实记录**，不假装做完 |
| **R3** | `jdbcUrlPrefix` 撞车（两个方言同一 URL 形状） | **中** | Impala 用自定义前缀（§4.3），spike 一并验 |
| **R4** | 拉取中转的磁盘占用是**两份** | **中** | 拉取面板显示预计占用；临时 CSV 三条路径都要清；提供「工作区清理」入口 |
| **R5** | POI 样式对象不缓存 → 大表 OOM | **中** | `ReportCellStyler` 按「列类型 × 样式」缓存，**验收里钉住**（M4） |
| **R6** | `spec_json` 无 proto 的兼容保证 | **中** | `schemaVersion` 必带，不认识的版本明确报错 |
| **R7** | 大数据量下 UI 卡顿 | **低** | 导出已在子进程跑（`ExportProcessManager`），拉取/报表沿用；结果表用 `DataTable` 的虚拟滚动 |
| **R8** | jlink 模块清单漏项 → 装得上启不来 | **低** | 新依赖进来后用 `jdeps` **重算**（`desktopApp/build.gradle.kts` 注释里有完整命令） |
| **R9** | AI 接入时的模型服务与凭据管理 | **低**（v6） | v1 不做；`InsightContext` 不含原始数据，只给摘要 |

---

## 11. 待定问题（**M0 结束前要逐条有结论**）

| # | 问题 | 为什么重要 | 建议默认 |
|---|---|---|---|
| **Q1** | 目标环境的 Hive / Impala **具体是什么发行版与版本**（CDH？HDP？EMR？自建？） | 决定驱动版本、SQL 方言、元数据查询写法 | —— 必须问 |
| **Q2** | 一次拉取的**数据量级**？（万 / 十万 / 千万行） | 决定要不要上方案 B（应用层直搬）、要不要分片 | 按 **十万行**设计，二期再优化 |
| **Q3** | 分析模式**是否允许连 MySQL / PostgreSQL**？ | 现在的设计是「只开放 DuckDB / Hive / Impala」 | **不允许**（用户明确只点了这三个）；但要不要留口子给未来？ |
| **Q4** | 报表**要不要图表**（柱状/折线）？ | POI 图表 API 不稳定，是 M5 的大变量 | v1 **不要**（§1.2） |
| **Q5** | AI 用哪家模型、云端还是本地？凭据存哪？ | 属 v6，但**接口**要在 M4 之前定死 | 不影响 v1；先留 SPI |
| **Q6** | 分析数据源要不要**复用浏览模式的连接**（同一台 MySQL 既能管理又能分析）？ | 复用会打破 §1.3 的「独立」；不复用则同一台库要配两遍 | **不复用**（Q3 已限定只有三个引擎） |
| **Q7** | 报表模板（样式预设）要不要内置几套？ | 影响 M5 的 `ReportStyle` 设计 | 先做 1 套默认 + 用户可调 |

---

## 12. 附录

### 12.1 主题色值快照

[`theme-tokens.json`](./theme-tokens.json) —— 10 套 `ColorScheme` × 28 个 Material3 角色 +
9 条字号阶梯，由脚本从
[`shared/src/commonMain/.../ui/SundaysPalette.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/SundaysPalette.kt)
抽取。**真相源永远是那个 Kotlin 文件**；本快照会过期，改主题后要重新抽。

默认配色（蓝灰）速查：

| 角色 | 浅色 | 深色 |
|---|---|---|
| `primary` | `#2F5C9E` | `#5B8DEF` |
| `onPrimary` | `#FFFFFF` | `#0B1220` |
| `primaryContainer` | `#DCE7F7` | `#25355A` |
| `background` | `#F4F5F7` | `#1B1F27` |
| `surface` | `#FFFFFF` | `#232833` |
| `surfaceVariant` | `#ECEFF3` | `#2C3240` |
| `onSurface` | `#1E2430` | `#D6DAE3` |
| `onSurfaceVariant` | `#5B6474` | `#99A1B3` |
| `outlineVariant` | `#DDE2E9` | `#333A48` |
| `tertiary` | `#177A6A` | `#4FB6A5` |
| `error` | `#B3261E` | `#E5715F` |

### 12.2 本文档引用的仓库事实

| 事实 | 出处 |
|---|---|
| 顶层导航现有 3 个目标 | `shared/.../navigation/AppDestination.kt` |
| 5 套配色 × 明暗 2 档 | `shared/.../ui/ThemePalette.kt` |
| 10 套 `ColorScheme` + 9 条字号 | `shared/.../ui/SundaysPalette.kt` |
| 引擎导出 5 种格式（含 `EXCEL`） | `engine/.../export/ExportModels.kt` |
| Excel 写出（POI SXSSF，100 万行/Sheet） | `engine/.../export/ExcelWriter.kt` |
| 导出跑在独立子进程 | `engine/.../export/ExportProcessManager.kt` / `ExportSubProcess.kt` |
| 导入支持 CSV / JSON_LINES | `engine/.../handlers/ImportHandler.kt`、`idb_engine.proto` 的 `IMPORT = 14` |
| 只读 SQL 扫描已有实现 | `shared/.../sql/DangerousSql.kt` |
| 现有只读拦截在**界面层** | `desktopApp/.../DatabaseBrowserState.kt:2980` |
| SPI 的写方法默认抛 `UnsupportedOperationException` | `api/.../DatabaseDialect.kt` |
| `DialectCapability` 是「声明」不是「探测」 | `api/.../DialectCapability.kt` |
| POI 5.5.1 / DuckDB 1.5.5.1 / hadoop-common 3.5.0 已在依赖里 | `gradle/libs.versions.toml` |
| 现有 5 个方言模块 | `settings.gradle.kts` |
| 方言靠 `META-INF/services` 注册 | `dialect-duckdb/src/main/resources/META-INF/services/…` |
| jlink 模块清单是手工枚举的 | `desktopApp/build.gradle.kts`（注释里有 `jdeps` 重算命令） |
