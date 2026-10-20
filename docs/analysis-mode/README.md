# 数据分析模式 —— 设计文档

> **状态**：设计冻结待评审 · **v0.3** · 2026-10-21
>
> **v0.3 相对 v0.2 的修改**（评审意见：*组件和控件也尽量复用，可以优化组件更加通用*）：
>
> 1. **新增「组件复用与通用化」整节（§6）** —— 给出三条复用判据、**零改动的直接复用清单**、
>    **5 项通用化改造**、**6 个确需新建的组件**（附「为什么现有的不够用」）。
> 2. **原 §6~§13 顺延为 §7~§14** —— 本节插在「连接管理器通用化」之后，
>    因为两者是**同一件事的两半**：连接管理器通用化管「**表单**怎么描述」，
>    本节管「**控件**怎么描述」。
> 3. **§9（界面设计）补「界面区域 ↔ 复用组件」映射**，并新增设计图第 6 画面
>    **「组件复用对照」**（[`ui-mockup.html`](./ui-mockup.html)）。
> 4. **§11（迭代）M1 扩容**：方言档位**注册表化**、前端方言元数据**收进一处**、
>    `WinDropdown` 补齐 —— 三项都是「先通用化再接方言」的一部分。
> 5. **§12 新增 R12**（组件复用退化）、**§13 新增 Q9**（要不要顺手上移 `desktopApp` 的 UI 组件）。
> 6. **顶栏右上角只留一个 ⚙ 设置入口**（§9.3）—— 去掉主题与语言两个入口，
>    与真实实现对齐（明暗与配色**只在设置页**切换，见 `main.kt` 的 KDoc）。
>
> **v0.2 相对 v0.1 的三处结构性修改**（评审意见：*设置复用，连接管理器复用；需要一个更通用的连接管理器*）：
>
> 1. **连接管理器与设置页改为复用**，不再各做一套（§1.3）。
> 2. **新增「连接管理器通用化」前置里程碑**（§5）—— 现有连接管理器是五种方言的专用表单，
>    Hive / Impala 一进来就撞天花板；先把表单改成**方言自描述**驱动，再接新方言。
> 3. **只读保证的主防线从「模式级」升到「方言级」**（§3）—— 连接管理器复用后，
>    同一个 Hive 连接会同时出现在两个模式里，只读若只挂在模式上就会被绕开。
>
> **配套**：[`ui-mockup.html`](./ui-mockup.html)（可交互 UI 设计图） ·
> [`theme-tokens.json`](./theme-tokens.json)（从真实主题源码抽取的色值快照）
>
> **本文档的判据**：所有「现状」陈述都指向仓库里的真实文件与行号；所有「设计」陈述都写清了
> 为什么这么选、以及被否掉的方案是什么。**没读过代码就别改本文** —— 与仓库其余文档同一条规矩：
> 具体契约在 KDoc 里，设计理由在这里。

---

## 0. 一页速览

| 项 | 结论 |
|---|---|
| **是什么** | 一个**独立于现有「连接管理 → 数据库浏览」流程**的新顶层模式。职责只有两件：**数据分析**与**制作报表**。 |
| **远程数据源** | **恒为只读**——而且是**方言级**保证：Hive / Impala 在引擎里**根本没有写入路径**，与从哪个模式进来无关（§3）。 |
| **计算引擎** | **默认 DuckDB**（本地嵌入式分析引擎），可选 **Hive** / **Impala**。 |
| **当前阶段交付** | **Excel 报表**（.xlsx）。 |
| **后续迭代** | 接入 **AI** 做智能分析与报表生成（见 §8）。 |
| **复用什么** | **连接配置 + 连接管理器 + 连接向导 + 设置页**（同一份 `connection.json`，同一个设置界面）。 |
| **组件** | **通用控件全部来自 `:shared` 的 `commonMain`**（`CodeEditor` / `DataTable` / `DragHandle` / `EmptyHint` / `WinControls` / `ContextMenuState` / `DangerousSql` …），**零改动**；本模式**只新增 6 个组件**，且全部落在 `:shared`（见 §6）。 |
| **不复用什么** | 浏览屏的 sheet 标签条 / `DatabaseBrowserState` / 对象树 / 预览标签页 / SQL 工作台**屏幕**。 |
| **前置条件** | **连接管理器必须先通用化**（§5）—— 否则 Hive / Impala 会把专用表单的 if-else 再加两轮。**组件侧同样要先通用化**（§6.3）：方言档位注册表化、前端方言元数据收进一处。 |
| **样式** | **完全复用**现有主题系统：5 套配色 × 明暗 2 档，一行新样式代码都不写（见 §9.1）。 |
| **引擎侧改动** | 复用已有的 `EXPORT.RUN_EXPORT` / `IMPORT.RUN_IMPORT` / `ExportFormat.EXCEL` 链路；**新增** `Category.ANALYSIS` / `Category.REPORT` 两条只读路由。 |
| **最大风险** | `hive-jdbc` 依赖树与仓库现有 `hadoop-common 3.5.0` 的版本冲突（见 §12 R1），必须先做 spike。 |

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
| 连接权限 | 可写（`readOnly` 是**可开关**的） | 对**远程数据源恒为只读**（方言级，无开关可关） |
| 数据位置 | 就在库里 | 远端只读源 → **本地物化** → 本地算 |
| 产出物 | 界面上的表格 | **一个 .xlsx 文件** |
| 连接配置 | `~/.config/sundays/connection.json` | **同一份**（连接管理器复用，见 §1.3） |
| 设置 | `SettingsScreen` | **同一个设置页**（新增「数据分析」分类，见 §1.3） |
| 工作台 | 浏览屏（对象树 + 预览标签页） | 分析工作区（SQL 编辑器 + 结果表 + 报表区块） |

### 1.2 明确不做的事（v1）

写下来是为了**挡住范围蔓延**，每一条都对应一次「顺手就做了」的诱惑：

- ❌ 不做图表 / 仪表盘 / BI 画布 —— v1 只出**表格型** Excel 报表。
  （理由：POI 的图表 API 极不稳定，且报表的价值在数据与结论，不在图。）
- ❌ 不做定时任务 / 报表订阅 / 邮件推送。
- ❌ 不做数据清洗 / ETL 编排（只做「拉取 + 查询」，不做管道）。
- ❌ 不做多用户 / 权限模型。
- ❌ **不做独立的连接管理器**，也不做独立的设置页 —— 一律复用现有的（§1.3）。
- ❌ **不在分析模式里提供任何写入能力**——包括「建临时表到远端」「往本地 DuckDB 写东西」之外的一切。
  （本地 DuckDB 工作区**可以**写：那是分析产物，不是数据源。这条边界很重要，见 §3.4。）
- ❌ 不把 AI 能力混进 v1 的交付范围（只留接口与入口占位，见 §8）。

### 1.3 与现有浏览流程的关系：**屏幕独立，连接与设置复用**

**复用（同一个东西，不做第二份）**：

| 复用什么 | 位置 | 复用方式 |
|---|---|---|
| 连接配置模型 | `shared/.../connection/ConnectionConfig.kt` | **直接用**，加 `authMethod` / `properties` 两个字段（§5.4） |
| 连接配置存储 | `shared/.../connection/ConnectionStorage.kt` | **直接用**，同一份 `~/.config/sundays/connection.json` |
| 连接管理器界面 | `shared/.../connection/ConnectionManagerScreen.kt` | **直接用**，按能力位分组显示（§5.6） |
| 添加 / 向导 | `shared/.../connection/AddConnectionDialog.kt` | **直接用**，但表单渲染改成读方言的 `connectionForm`（§5.3） |
| 设置页 | `shared/.../settings/SettingsScreen.kt` | **直接用**，新增一个「数据分析」分类（AI 配置 / 报表默认样式 / 输出目录） |

> 💡 **复用连接管理器的收益是实打实的**：用户在连接管理器里建一次 Hive 连接，
> 就能在浏览模式与分析模式里都用；不必配两遍、不必记住两套凭据。
> 代价是**连接管理器必须先通用化**（§5）—— 这是本设计里唯一的「必须先做的事」。

**不复用（各自一套）**：

| 不复用什么 | 理由 |
|---|---|
| `ConnectionSession` / `DatabaseBrowserState` | 那套状态机是「一个连接 = 一个**可写**浏览器」，与分析工作流不匹配 |
| sheet 标签条（多连接标签） | 分析模式的工作单位是「**一次分析**」，不是「一个连接」 |
| 对象树 / 预览标签页 | 分析模式的对象是「数据集 + 报表区块」，不是「库表 + 预览」 |
| SQL 工作台**屏幕** | 但**组件**复用：`CodeEditor` / `DataTable` / `DangerousSql` 都直接用（§10.1） |

**两个模式怎么共用一份连接？** 靠**能力位**筛选，不靠复制配置（§5.6）：

```
connection.json （一份）
   ├─ 可浏览的连接（声明了 BROWSE_SOURCE）    → 浏览模式看到
   └─ 可分析的连接（声明了 ANALYSIS_SOURCE）  → 分析模式看到
```

DuckDB 两组都在 —— 它既能当管理用的本地库，也能当分析引擎。

### 1.4 为什么连接管理器必须先「通用化」

**这是本设计唯一的硬前置条件**，理由不是洁癖，是**加 Hive/Impala 就会撞天花板**：

| 现状 | 位置 | 撞到什么 |
|---|---|---|
| `ConnectionConfig` 字段硬编码（host/port/user/password/database） | `shared/.../connection/ConnectionConfig.kt` | Hive 的 Kerberos principal、Impala 的 TPCD 租户、ZooKeeper 发现串 —— **无处安放** |
| 认证只有账密两种 | 同上 | 大数据环境常态是 **Kerberos**，不是账密 |
| 表单按枚举分支渲染 | `AddConnectionDialog` / `ConnectionWizardContent` | 每加一个方言就要改前端 if-else，方言一多必然失控 |
| 方言元数据**有两处** | 前端 `DialectType.supportedConnectionTypes` vs 引擎 `DatabaseDialect.connectionType` | 仓库已有 `DialectNameContractTest` 在防漂移 —— 说明这块**确实脆** |
| 连接类型只有 4 种 | `ConnectionType` | 没有「直接填 JDBC URL」的逃生舱；而引擎**已经支持**按 URL 前缀反查方言（`DialectLoader.getDialectByJdbcUrl`） |

反过来做（先加方言、后补通用化）会把 if-else 再加两轮，而这两轮里每一处都是**只有加方言的人才看得懂**的分支。所以顺序是：**先通用化 → 再接方言**（§11 迭代）。

---

## 2. 总体架构

### 2.1 一句话架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  分析模式（desktopApp: AnalysisScreen / ReportDesignerScreen）        │
│    ↑ 读同一份连接配置 + 同一个设置页                                   │
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
   │ dialect-impala      │ ──────────▶ │ 本地工作区 workspace.   │
   │ ★ 只读方言：         │              │ .duckdb                 │
   │   写方法全部抛        │              └─────────────────────────┘
   │   UnsupportedOperation │
   └─────────────────────┘
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
AI 也不例外——AI 只能产出 `ReportSpec`（含 SQL），不能直接产出文件（见 §8）。

### 2.4 拉取链路：为什么先走 CSV 中转

「远端 → DuckDB」有三条路：

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. DuckDB extension 直连** | DuckDB 的 `hive` / `httpfs` extension 直接读远端 | 无中转、快 | extension 要**联网下载**，内网/离线必挂；Impala **没有**对应 extension | ❌ 不做 |
| **B. 应用层流式搬运** | 引擎里逐行 `ResultSet` → DuckDB 批量 insert | 不依赖 extension | 要自己写搬运与类型映射 | ⏳ 作为二期优化 |
| **C. 复用现成导出/导入链路** | 远端 `EXPORT` → CSV → DuckDB `IMPORT` | **链路已存在且有测试**，零新协议，带进度与取消 | 多一次磁盘往返 | ✅ **一期采用** |

**一期选 C**，理由很实际：`EXPORT.RUN_EXPORT`（[engine/handlers/ExportHandler.kt](../../engine/src/main/kotlin/com/langbiantianya/handlers/ExportHandler.kt)）
与 `IMPORT.RUN_IMPORT`（[engine/handlers/ImportHandler.kt](../../engine/src/main/kotlin/com/langbiantianya/handlers/ImportHandler.kt)）
**都已经实现、都有集成测试**，而且导出跑在**独立子进程**里（`ExportProcessManager` / `ExportSubProcess`），
大查询不会把 UI 线程拖死。复用它的代价只是一次 CSV 落盘。

把 B（应用层直搬）列为二期性能项：十万行级别 C 完全够用，百万行再上 B。

> ⚠️ 代价要说清楚：C 的磁盘占用是**两份**（CSV + DuckDB 表）。
> 拉取面板必须显式显示预计占用与临时 CSV 的清理时机，不能让用户莫名其妙吃掉几个 G。

---

## 3. 只读保证：六层防线 ★

> 这是整个设计里**最不能出错**的一节。用户说「这里面的连接的远程数据库都只能只读」——
> 如果最后只做到「界面上没写按钮」，那这个承诺是不成立的：任何一个能改 URL、
> 任何一个走错路由的调用，都可能写进去。

### 3.1 六层，逐层收紧

| 层 | 位置 | 手段 | 挡得住什么 |
|---|---|---|---|
| **L1 账号** | 远端 Hive/Impala | 用**只读授权账号** | 一切（前提是运维配合） |
| **L2 连接** | JDBC | `Connection.setReadOnly(true)` + 只读连接池参数 | 部分驱动的写 |
| **L3 方言级** ★ | `api` SPI | **只读方言不实现写方法** → 引擎里**根本不存在**往它写的路径 | 一切。**与从哪个模式进来无关** |
| **L4 会话级** | `RequestDispatcher` | 分析会话带 `read_only` 标记 → 写类别一律拒绝 | 「将来被加进分析模式的**可写**方言」 |
| **L5 API 面** | 协议 | 分析会话**只走** `Category.ANALYSIS` / `REPORT`（都是读路由） | 误用别的路由 |
| **L6 界面** | UI | 分析模式**不渲染**任何写入口（不是禁用，是不存在） | 用户误操作 |

L1 依赖运维配合、L2 在多数驱动上只是**提示**而非保证。真正兜底的是 **L3**，
**L4** 是它身后那道「防将来」的闸。

### 3.2 为什么主防线必须是「方言级」而不是「模式级」

这是 v0.2 最重要的一处修改，直接来自「连接管理器复用」这个决定。

连接管理器复用之后，**同一个 Hive 连接会同时出现在浏览模式与分析模式**。
若只读只挂在「分析模式」上，那么用户在浏览模式里打开同一个连接就能写 —— 承诺当场破了。

挂在**方言**上则天然成立：

> **Hive / Impala 在引擎里没有写入路径。**
> 从分析模式进来写不了，从浏览模式进来同样写不了。

于是「分析模式连的远程库只能只读」这句话，不再依赖「用户从哪个模式进来」，
也不再依赖「界面有没有藏按钮」。

### 3.3 L3 的实现：只读是**实现出来的**，不是**配置出来的**

`api` 的 SPI 里，视图 / 索引 / 外键 / 触发器 / 改表名 / 清表这些方法**默认抛
`UnsupportedOperationException`**（[api/.../DatabaseDialect.kt](../../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt)）。
`dialect-hive` / `dialect-impala` 只需：

- **实现**：列库 / 列 schema / 列表 / 列列 / 引号 / 建列定义（供拼 SQL 用）
- **不实现**（保持默认抛错）：建 schema / 删 schema / 建用户 / 授权 / 建视图 / 建索引 …
- **显式抛错**：SPI 里少数是**抽象方法**（`createSchema` / `deleteSchema` / `createUser` /
  `deleteUser` / `updatePassword` / `updatePrivileges` / `listUsers` / `listPrivileges` /
  `listAllGrants`）—— 这两个方言里它们**显式抛 `UnsupportedOperationException`**。

同时**显式声明** `DialectCapability.READ_ONLY_DIALECT`（§5.6），
前端据此在连接管理器与向导里显示**只读徽标** ⓘ ——
用户在浏览模式里看到 Hive 连接时，就知道它**根本不能写**（而不是「忘了开只读」）。

### 3.4 L4 的定位与一条容易搞混的边界

L4（会话级闸门）是**兜底**，不是主力。它的存在理由是**将来**：

> 若给 MySQL / PostgreSQL 也加上 `ANALYSIS_SOURCE` 能力位，它们是**可写方言** ——
> 此时若没有 L4，用户在分析工作区写一句 `DELETE FROM …` 就会真的删数据。

L4 的落法：

1. **协议**：`ConnectionConfig` 加 `bool read_only = 13;`（proto3 追加字段号，向后兼容）。
   `RequestDispatcher` 在**路由之前**检查：`read_only == true` 时，
   命中写类别（`TABLE.CREATE/UPDATE/DELETE/RENAME/TRUNCATE`、`DATA.INSERT/UPDATE/DELETE`、
   `IMPORT.RUN_IMPORT`、`USER.*`、`SCHEMA.CREATE/DELETE`、`VIEW.CREATE/DROP`、
   `INDEX.*`、`FOREIGN_KEY.*`、`TRIGGER.*`，以及 `SQL.EXECUTE` 里**非 SELECT** 的语句）
   一律直接返回错误，**不进入 handler**。
2. **`SQL.EXECUTE` 的特殊性**：它是「万能入口」，不能按类别拦。
   复用**已有的** `DangerousSql.scan(sql)`（[shared/src/commonMain/.../sql/DangerousSql.kt](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/sql/DangerousSql.kt)）——
   它已经能剥注释、识别 `SELECT` / `WITH … SELECT` / 多语句里的写操作。
   浏览模式的 `readOnly` 拦截用的就是它（[desktopApp/.../DatabaseBrowserState.kt:2980](../../desktopApp/src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt)），
   但那是在**界面层**；分析模式要把它下沉到**引擎层**。

> ⚠️ **边界：本地 DuckDB 工作区可以写。**
> `ANALYSIS.RUN_QUERY` 在 DuckDB 上执行时，用户写的 `CREATE TABLE tmp AS SELECT …`
> 是**允许**的 —— 那是分析产物，落在本地工作区文件里，**不碰任何远端**。
> L4 因此**按「连接的是不是远端只读源」判定**，而不是「一律禁写」。
> 这条边界如果搞混，会把分析模式变成「连中间表都建不了」的死胡同。

### 3.5 L5：API 面才是窄门

`Category.ANALYSIS` 下的路由**只有读**：

| 路由 | 作用 |
|---|---|
| `ANALYSIS.LIST_SOURCES` | 列分析数据源 |
| `ANALYSIS.LIST_TABLES` | 列远端表（只读 SPI） |
| `ANALYSIS.LIST_COLUMNS` | 列远端列（只读 SPI） |
| `ANALYSIS.RUN_QUERY` | 在**计算引擎**上跑分析 SQL |
| `ANALYSIS.PULL_SAMPLE` | 从远端拉样本到本地工作区 |

`Category.REPORT` 下：

| 路由 | 作用 |
|---|---|
| `REPORT.BUILD_EXCEL` | `ReportSpec` → .xlsx |

### 3.6 验收：只读必须有测试钉住

**每一个写类别都要有一条回归测试**，而且断言的是
「引擎返回了明确的只读错误」而不是「抛了个奇怪的 SQLException」。

两类都要有：

1. **方言级**：对一个只读方言发 `TABLE.CREATE` / `DATA.INSERT` / `USER.CREATE` → 被拒；
2. **会话级**：在一个 `read_only = true` 的会话里发写请求 → **不进入 handler** 就被拒。

> 📌 **本仓库的教训**（见 `desktopApp/TEST_CASES.md` §7）：测试的判据要落在
> 「**发出去的那条语句**」上。「只读模式下写操作被拦」这个判据，必须真的发一条
> `INSERT` 给引擎、并断言引擎**在进入 handler 之前**就拒了它 —— 而不是断言
> 「某个 UI 按钮是 disabled」。后者在 UI 重构后依然绿，而功能其实已经漏了。

---

## 4. 数据源：Hive 与 Impala

### 4.1 驱动选型

| 目标 | 驱动 | 说明 |
|---|---|---|
| Hive | `org.apache.hive:hive-jdbc`（standalone 分类） | 走 HiveServer2 的 Thrift 协议。仓库现有 `hadoop-common 3.5.0` 已铺了 Thrift/HDFS 的底，但版本与 `hive-jdbc` 的 Hadoop 依赖**可能打架**（见 §12 R1）。 |
| Impala | **同一个** `hive-jdbc` | Cloudera Impala 兼容 **HiveServer2 协议**，所以同一个驱动能连 —— ⚠️ **这是假设，必须先验证**（见 §4.2）。 |
| DuckDB | `org.duckdb:duckdb_jdbc` | **仓库已有**（`libs.versions.toml` 的 `duckdb = 1.5.5.1`），`dialect-duckdb` 也已有，**零新增**。 |

> 💡 **Impala 走 HS2** 是这条路线能成立的关键。若实测不成立，退路是
> Impala 自带的 JDBC 驱动（Cloudera 分发，许可与获取渠道另议），
> 或把 Impala 降级为「通过 Hive Metastore + Parquet 直读」——那会重新引入 extension 下载问题。
>
> 💡 **还有一条通用退路**：`ConnectionType.CUSTOM_URL`（§5.5）——
> 用户自己填 JDBC URL，方言由 URL 前缀反查。**大数据环境的 URL 形状千奇百怪**，
> 这条逃生舱让「专用方言写不出来」不至于卡死整个功能。

### 4.2 ⚠️ 必须在写任何代码之前验证的假设

**M3 的第 0 号任务是一个 spike，不产出功能代码，只产出一条结论**：

> ① `hive-jdbc` 能否连上目标环境的 HiveServer2？
> ② 同一个驱动能否连上 Impala 的 HS2 端点？
> ③ `hive-jdbc` 与仓库现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 是否冲突？
> 冲突怎么解（`exclude` 降级 / 换版本 / 拆 ClassLoader）？
> ④ 目标环境是 CDH / HDP / Cloudera / EMR / 自建？**版本号是多少？**

**这四条里任何一条答不上来，M3 的方案就要改。** 在此之前写出来的 `dialect-hive` 可能是白写。

### 4.3 方言实现面

两个方言都是**只读子集**（§3.3），实现量不大：

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

### 4.4 连接表单规格（通用化之后长什么样）

两个方言在**通用化后的连接管理器**里声明自己的表单（§5.3）：

| 字段 | Hive | Impala |
|---|---|---|
| `host` | TEXT，必填 | TEXT，必填 |
| `port` | INT，默认 **10000** | INT，默认 **21050** |
| `database` | TEXT | TEXT |
| `user` | TEXT | TEXT |
| `password` | PASSWORD | PASSWORD |
| `kerberosPrincipal` | TEXT（选填，Kerberos 认证时用） | TEXT（同） |
| `tpcService` | — | TEXT（选填，租户） |
| `authMethod` | PASSWORD / **KERBEROS** | PASSWORD / KERBEROS |

新增这两个方言时**前端一行不改** —— 表单是方言自己声明的。

### 4.5 配置与凭据

- 存储：`~/.config/sundays/connection.json`（**与浏览模式同一份**，§1.3）
- 模型：`ConnectionConfig`（**同一个**），新增 `authMethod` 与 `properties`（§5.4）
- 明文口令与现状同级，**不因为新功能引入更差的存储**；
  但**不能**因此在文档里说「已加密」——没有。
- 凭据**只用于只读分析**时，也可以在同一份配置里被浏览模式看到（同一个 Hive 连接两边都在）——
  这正是 L3 方言级只读存在的理由：**共用凭据不再意味着共用写权限**。

---

## 5. 连接管理器通用化（前置条件）★

> 完整背景见 §1.4。这一节是 §11 迭代里的 **M1**。

### 5.1 目标与判据

| 目标 | 判据（可判定） |
|---|---|
| 表单由**方言自描述**驱动 | 新增一个方言**不需要改任何前端代码**，表单自动出现 |
| 认证方式可扩展 | 方言声明支持的 `AuthMethod`，向导据此渲染；Kerberos 不需要改前端 |
| 方言特有字段能承载 | `ConnectionConfig.properties: Map<String,String>` 承载任意键值 |
| 有通用逃生舱 | `ConnectionType.CUSTOM_URL`：用户直接填 JDBC URL，方言由 URL 前缀反查 |
| 现有 5 个方言**零破坏** | 现有 `buildJdbcUrl(host, port, database)` 保留；新能力用**新重载**加 |
| 两个模式共用一份配置 | `connection.json` 一份；各模式按**能力位**筛出自己的子集 |

### 5.2 方言自描述的连接表单

```kotlin
// api/src/main/kotlin/com/langbiantianya/dialect/ConnectionFormSpec.kt
@Serializable
data class ConnectionFormSpec(
    val fields: List<ConnectionField> = emptyList(),
    val authMethods: List<AuthMethod> = listOf(AuthMethod.PASSWORD),
    val defaultPort: Int? = null,
)

@Serializable
data class ConnectionField(
    val id: String,                 // "host" / "port" / "kerberosPrincipal" / "tpcService" …
    val labelKey: StrKey,           // 文案键（i18n 六语言）
    val type: FieldType,            // TEXT / PASSWORD / INT / CHOICE
    val required: Boolean = false,
    val secret: Boolean = false,    // 存进 properties 时要脱敏
    val choices: List<String> = emptyList(),
    val placeholder: String? = null,
)

enum class AuthMethod { NONE, PASSWORD, KERBEROS, TOKEN }
```

`DatabaseDialect` 新增一个属性：

```kotlin
/** 该方言的连接表单规格 —— 前端按它渲染表单，不按枚举分支。 */
val connectionForm: ConnectionFormSpec get() = ConnectionFormSpec()
```

**默认实现让现有五种方言照常工作**：它们的字段（host / port / user / password / database）
刚好能用 `ConnectionField` 描述出来，所以前端只要改成**优先读 spec**、
读不到才回退旧的枚举分支，既有方言**不需要改**。

> 💡 **为什么不一次性重写前端表单**：那会让 5 个方言的既有测试全红，
> 而且「新旧两套渲染」在迁移期会分叉。判据是**新增方言零前端改动**，
> 而不是**旧方言全改成新写法** —— 后者是纯收益为零的重构。

### 5.3 `ConnectionConfig` 的扩展

```kotlin
data class ConnectionConfig(
    ...现有字段...
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** 方言特有键值（Kerberos principal / TPCD service / ZooKeeper / SSL 参数…） */
    val properties: Map<String, String> = emptyMap(),
)
```

- 持久化模型 `PersistedConnectionConfig` 同步加这两个字段。
- ⚠️ **向后兼容**：`PersistedConnectionList.version` 从 2 升到 3，
  加载时 v2 的记录按缺省值补齐（`authMethod = PASSWORD`、`properties = {}`）。
  仓库已有 `ConnectionStorageTest` 盯迁移，v2 → v3 要补一条。
- `properties` 里 `secret = true` 的字段在 UI 上要脱敏、在导出配置时要剔除。

### 5.4 新增 `ConnectionType.CUSTOM_URL`

```kotlin
enum class ConnectionType {
    CLIENT_SERVER, EMBEDDED, IN_MEMORY, FILE_BASED,
    CUSTOM_URL,      // 新增：用户直接填 JDBC URL
    UNKNOWN,
}
```

- 表单只显示一个「JDBC URL」字段。
- 方言由 **URL 前缀**反查 —— 引擎侧**已经有这个能力**
  （`DialectLoader.getDialectByJdbcUrl`，`DatabaseDialect.jdbcUrlPrefix`）。
- **这是 Hive / Impala 的保底路径**（§4.1）。
- ⚠️ 反查失败要**明确报错**（引擎现有行为就是「不回退 `driver`」——
  这个设计是对的：静默套用别的方言比连不上危险得多）。

### 5.5 连接列表按能力位分组

| 能力位 | 含义 | 内置分布 |
|---|---|---|
| `BROWSE_SOURCE` | 可在**浏览模式**使用 | MySQL / PG / H2 / DuckDB / SQLite |
| `ANALYSIS_SOURCE` | 可在**分析模式**使用 | DuckDB / Hive / Impala |
| `READ_ONLY_DIALECT` | **方言级只读**：写方法一律抛 `UnsupportedOperationException` | Hive / Impala |

- 连接管理器按这两组显示（「可浏览」/「可分析」），**同一份配置**。
- `READ_ONLY_DIALECT` 的连接在列表与向导里显示**只读徽标** ⓘ（§3.3）。
- DuckDB 两组都在。
- 现有 5 个方言**全部补声明 `BROWSE_SOURCE`**（这是行为不变的一句声明）。

### 5.6 SPI 的向后兼容策略

方言要拿到 `properties` 才能拼 URL，但 `buildJdbcUrl(host, port, database)` 只有三个参数。

**做法：加新重载，默认实现调旧方法。**

```kotlin
// api/DatabaseDialect.kt
fun buildJdbcUrl(spec: DialectConnectionSpec): String =
    buildJdbcUrl(spec.host, spec.port, spec.database)   // 默认：走老路径

fun buildJdbcUrl(host: String, port: Int, database: String): String   // 原样保留
```

- 现有 5 个方言**一行不改**。
- Hive / Impala **覆写新重载**，从 `spec.properties` 里取 Kerberos principal / TPCD service 等。
- 前端 `JdbcUrl.buildJdbcUrl(config)` 改成调新重载。

### 5.7 验收

- 新增一个**测试用假方言**（`FakeDialect`）声明 3 个自定义字段 + 2 种认证方式，
  断言：连接管理器**自动**渲染出这 3 个字段与认证下拉，**前端零改动**。
- `CUSTOM_URL` 连接：填一个引擎认识的 URL → 方言被正确反查；填一个不认识的 → 明确报错。
- v2 的 `connection.json` 加载后自动补齐新字段（迁移测试）。
- `properties` 里的 secret 字段在 UI 脱敏、在「导出配置」时剔除。
- **现有 5 个方言的连接向导测试全绿且未修改**（通用化没改变它们的行为）。

---

## 6. 组件复用与通用化 ★

> 本节回答评审意见的第三条：**组件和控件也尽量复用，可以优化组件更加通用**。
>
> **结论先行**：分析模式**不新写一套控件**。所有颜色 / 圆角 / 字号来自现有主题系统（§9.1），
> 所有通用控件来自 `:shared` 的 `commonMain`；本模式**只新增 6 个组件**，且**全部落在 `:shared`**
> —— 这样浏览模式将来也能用。

### 6.1 判据：什么叫「可以复用」

一个组件要被分析模式复用，必须同时满足三条：

| # | 判据 | 为什么是它 |
|---|---|---|
| 1 | **平台无关** | 不 import `java.*` / `javax.*`，也不 import `desktopApp` 包里的任何类型 —— 否则它进不了 `:shared` 的 `commonMain`（`:shared` 的定位是「commonMain 单一 source set 承载所有业务组件」，**不引入 expect/actual**，见 `shared/ARCHITECTURE.md`） |
| 2 | **状态无关** | 不直接依赖 `DatabaseBrowserState` 这类**大而具体**的状态机 —— 依赖它就等于把整个浏览模式拖进分析模式 |
| 3 | **可寻址** | 能说清「它出现在分析模式的哪一屏的哪个位置」—— 否则「复用」只是把代码搬了个地方 |

不满足第 2 条的组件**先解耦再复用**，而不是复制一份到 `desktopApp`。

### 6.2 直接复用清单（零改动）

下表每一项都**不需要改一行代码**：

| 组件 | 位置 | 用在哪 | 为什么够用 |
|---|---|---|---|
| `CodeEditor` / `CodeEditorWithToolbar` | `shared/.../editor/ui/CodeEditor.kt:246` / `:699` | 工作区 SQL 编辑区 | 组件只认 `languageId`；方言差异全在 `SqlDialectProfile` 里（§6.3.1） |
| `CodeEditorState` / `rememberCodeEditorState` | `shared/.../editor/ui/CodeEditor.kt` | 编辑器状态 | 纯状态持有者，与业务无关 |
| `DataTable` | `shared/.../table/DataTable.kt:187` | 查询结果表 | 已是通用键值模型，聚合结果的列天然可用（§6.3.3） |
| `TableColumn` / `TableRow` | `shared/.../table/TableModels.kt:52` / `:88` | 结果表的列与行 | `TableColumn(key, header, …)` + `TableRow(id, cells)` 不含「库表」语义 |
| `PageSize` | `shared/.../table/TableModels.kt:121` | 结果表分页 | `PageSize.ALL` 的 KDoc 明说「一次性渲染所有行（依赖 LazyColumn 虚拟滚动）」 |
| `DataTableTheme` | `shared/.../table/TableModels.kt:195` | 表格配色 | 已有「编辑 / 待提交 / 待删除」三种单元格状态色，只读场景一个都用不到 |
| `EmptyHint` | `shared/.../ui/EmptyHint.kt` | 所有空态 | 已是「标题 + 说明 + 可选居中」的通用形态 |
| `FocusRing` / `uiBevel` / `winShape` / `controlShape` / `selectionColorsFor` | `shared/.../ui/UiChrome.kt` | 焦点环 / 立体边 / 圆角 / 选中色 | 经典档与现代档的差异全在这一个文件里 |
| `WinButton` / `WinOutlinedButton` / `WinTextButton` / `WinTextField` / `WinIconButton` / `WinSwitch` / `WinMenuItem` / `WinSurface` / `WinProgressIndicator` / `WinDivider` | `shared/.../ui/WinControls.kt` | 全部按钮 / 输入框 / 开关 / 菜单项 / 进度条 | 现有控件集已覆盖分析模式的绝大多数控件，**唯独缺下拉**（§6.3.4） |
| `ContextMenuState` / `onRightClick` | `shared/.../ui/ContextMenuState.kt` / `RightClick.kt` | 右键菜单 | 泛型 `ContextMenuState<T>`；表格与编辑器各有一个类型别名 |
| `ColumnBadge` | `shared/.../ui/ColumnBadge.kt` | 数据源类型小徽标 | `surfaceVariant` 底的小徽标，正好是「类型标签」的视觉 |
| `DragHandle` | `shared/.../ui/DragHandle.kt` | 面板宽度拖拽 | 已支持 `hoverIcon`（指针图标由平台侧注入，桌面端 `PointerIcons.kt`） |
| `DangerousSql` / `DangerousSqlConfirmDialog` | `shared/.../sql/DangerousSql.kt` / `DangerousSqlConfirmDialog.kt` | SQL 执行前的只读校验 | `isReadOnlyQuery` 已能剥注释、识别 SELECT / WITH / 多语句写操作 |
| `SundaysTheme` / `ThemePalette` / `SundaysPalette` | `shared/.../ui/` | 主题 | 5 套配色 × 明暗 × 紧凑档，分析模式**一行新样式都不写** |
| `str` / `LocalStrings` / `ProvideStrings` | `shared/.../ui/LocalStrings.kt` | 国际化 | 新增文案键走 `i18n` 的 6 语言表，UI 里不写死中文 |
| `CompactMode` / `LocalCompactMode` / `compactDensity` | `shared/.../ui/CompactMode.kt` | 紧凑档 | 与主题一起继承 |
| `SettingsEntryButton` | `shared/.../ui/ThemeModeToggle.kt` | 顶栏右上角的 ⚙ 设置入口 | 图标按钮（`Icons.Filled.Settings`），与浏览模式的设置入口**同一个组件** |
| `SqlFormatter` / `CodeFormatterRegistry` | `shared/.../editor/formatter/` | SQL 格式化 | 格式化按钮按 `languageId` 找 formatter，Hive / Impala 档位注册后自动可用 |
| `TabRenameDialog` | `shared/.../ui/TabRenameDialog.kt` | 报表区块改名 | 已是通用的「重命名一个字符串」弹窗 |

### 6.3 通用化之后复用的（5 项）

#### 6.3.1 SQL 方言档位：注册表化（**必做**）

**现状**：`shared/.../editor/language/SqlDialectProfile.kt:341` 是一行硬编码：

```kotlin
val ALL: List<SqlDialectProfile> = listOf(STANDARD, MYSQL, POSTGRESQL, H2, DUCKDB, SQLITE)
```

它被两处消费：

- `shared/.../editor/formatter/SqlFormatter.kt:179-187` —— `register()` 遍历 `ALL`，
  为每个档位注册「语言 + formatter」配对
- `shared/src/commonTest/.../editor/SqlDialectProfileTest.kt` —— 遍历 `ALL`
  校验「档位即语言」「三类词互斥」两条不变量

**问题**：加 Hive / Impala 就要改 `:shared` 的核心文件 —— 这**直接违反** §5.7
「新增方言零前端改动」的判据。

更关键的是它**违背了仓库已有的设计**：`CodeLanguageRegistry`
（`shared/.../editor/CodeLanguage.kt:94`）的 KDoc 明确写着
「第三方语言可在应用启动后调用 `register` 注入，**不需要修改编辑器组件源码**」
—— SQL 家族只是走了 `ALL` 这条旁路，没享受这个机制。

**方案**：新增 `SqlDialectProfileRegistry`，与 `CodeLanguageRegistry` / `CodeFormatterRegistry`
**同构**（同样的 `synchronized` 守护 + `get` / `all` / `unregister`）：

```kotlin
object SqlDialectProfileRegistry {
    /** 注册一个方言档位 —— 同时注册语言与 formatter，保证「只高亮不能格式化」的中间态不会出现。 */
    fun register(profile: SqlDialectProfile)
    fun get(languageId: String): SqlDialectProfile?
    fun all(): List<SqlDialectProfile>
}
```

`ALL` **保留但降级**为「内置档位的快照」，仅为不破坏现有测试；**生产路径不再读它**。
`SqlFormatter.register()` 改成遍历注册表。

**验收**：

- 运行时注册一个假档位 → `CodeEditor(languageId = "sql-fake")` **立刻有高亮**、
  格式化按钮**可用**；且 `SqlDialectProfileTest` 的两条不变量对它**自动成立**
  （因为测试改成遍历注册表）
- 现有 5 个方言的注册结果与改造前**逐项相同**（顺序、id、词表）

**顺带修一处重复**：`desktopApp/.../DatabaseBrowserState.kt:149-157` 的
`DialectType.toSqlDialectProfile()` 是**又一份**方言 if-else ——
与 `ALL` 的硬编码是同一个问题的两个副本。通用化后它应从「方言 → 档位」的映射表读，
而不是各自维护一份。

#### 6.3.2 前端方言元数据：收进一处（**必做**）

**现状**：`DialectType`（`shared/.../connection/ConnectionConfig.kt:103`）在前端有**四处**硬编码 `when`：

| 位置 | 内容 |
|---|---|
| `ConnectionConfig.kt:72-80` `defaultsFor` | 方言 → 默认连接类型 + 默认端口 |
| `ConnectionConfig.kt:117-125` `supportedConnectionTypes` | 方言可选的连接类型 |
| `ConnectionConfig.kt:140-155` `engineDriverName` | 方言 → 引擎 `driver` 名 |
| `shared/.../table/DialectCapabilities.kt:52-101` `of(dialect)` | 方言 → 前端能力判断 |

**问题**：每加一个方言要改**四个地方**，漏一个就是「界面说能连、引擎说没这个驱动」这类错。
仓库已经有 `DialectNameContractTest` 在防漂移 —— 说明这块**确实脆**。

**方案**：与 §5.2 的 `ConnectionFormSpec` 合并成**一份**方言前端元数据：

```
DialectType.HIVE → ConnectionFormSpec(
    displayName = "Hive",
    authMethods = listOf(AuthMethod.PASSWORD, AuthMethod.KERBEROS),
    defaultConnectionType = ConnectionType.CLIENT_SERVER,
    defaultPort = 10000,
    engineDriverName = "Hive",
    frontendFeatures = DialectFeatures(...),
    fields = listOf(ConnectionField("host", ...), ConnectionField("principal", ...)),
)
```

四处 `when` 全部改成查这张表。**判据不变**：加一个方言，前端**只加一条元数据**，不再改分支。

#### 6.3.3 `DataTable`：只读与分页都**零改动**

`DataTable`（`shared/.../table/DataTable.kt:187`）的签名里有 20 多个参数，
其中**一半是写入相关**的。分析模式是只读的，用法必须写死成下面这样，**不加新参数**：

| 参数 | 分析模式传什么 | 为什么 |
|---|---|---|
| `onCellEdit` | `null` | 触发 `cellEditable` 的默认值 `= onCellEdit != null` → 自动为 `false`（KDoc 已写明这条默认联动） |
| `isColumnEditable` / `isCellPending` / `isRowDeleted` | 保持默认 | 只读场景这三种状态不出现 |
| `pageSize` | `PageSize.ALL` | 分析结果集是一次查询返回的**完整**集合，分页是**客户端**的；`PageSize.ALL` 的 KDoc 明说依赖 `LazyColumn` 虚拟滚动 |
| `serverSidePaging` | `false` | 同上 |
| `totalCount` | `rows.size` | 默认值即可 |
| `contextMenuItems` | 复制 / 导出为 CSV | 只读但**可取走** —— 复制与导出不是写 |

**结论**：`DataTable` **一行都不用改**。这是本节最省事的一处复用 —— 因为它当初就是按
「键值对 + 一张表」设计的，聚合结果的列（`sum_amt` / `cnt` / `ratio`）
与库表的列在它眼里**没有区别**。

> ⚠️ 一条必须写清的边界：**10 万行结果的「渲染」靠虚拟滚动，「导出」走引擎侧**（§7.1），
> 两者都不是 UI 的活。不要因为「预览要渲染 10 万行」就去改 `DataTable` 的分页语义 ——
> 那会把「浏览模式的分页」和「分析结果的完整集」搅成一件事。

#### 6.3.4 `WinControls`：补一个下拉

**现状**：`shared/.../ui/WinControls.kt` 提供了 16 个控件 —— 按钮 3 种、输入框、图标按钮、
表面、开关、菜单项、进度条、分隔线，加上形状 / 选中色解析。
**唯独没有「下拉选择器」**：`WinMenuItem` 是 `DropdownMenuItem` 的**外观包装**（菜单里的一项），
不是「点开一个列表选一个值」的选择器。

**为什么分析模式必须要**：连接向导要选**认证方式**（账密 / Kerberos / 自定义 URL），
报表设计器要选**数字格式**（文本 / 整数 / 小数 / 百分比 / 货币 / 日期）。
在现代三套配色下 Material3 的 `ExposedDropdownMenuBox` 能用；
但在 **Win2000 / WinXP** 两套经典配色下它**不会**自动变成 Win 控件外观 ——
而这两个配色在本项目里是一等公民（§9.1）。

**方案**：在 `WinControls.kt` 里**加** `WinDropdown`（配套 `WinDropdownMenu`），
沿用文件里既有的写法（`resolveControlShape` 取形状、`WinSurface` 画边框、经典档走 3D 斜面）。
这是**扩充现有控件集**，不是另起一套。

#### 6.3.5 `desktopApp` 的 UI 组件：不复用，但记一条先例

**一个必须知道的事实**：`desktopApp` 里的 8 个 UI 文件
（`SchemaTree` / `BrowserChrome` / `BrowserToolBar` / `TablePreview` / `SqlWorkbench` /
`ExportDialogs` / `GenerateWorkbench` / `DatabaseBrowserScreen`）
**没有一个 import `java.*`** —— 它们**已经是平台无关的 Compose 代码**。
它们留在 `desktopApp` 的真正原因不是平台依赖，而是**耦合 `DatabaseBrowserState`**（3952 行）。

耦合程度分级：

| 文件 | 对 `DatabaseBrowserState` 的耦合 | 能否上移 `:shared` |
|---|---|---|
| `BrowserToolBar.kt` | **0 处**引用 | ✅ 可直接上移（`internal` → `public`） |
| `BrowserChrome.kt` | 只有 `DatabaseBrowserState.BackgroundTask`（`:808`）一个小类型 | ⚠️ 抽出该类型后可上移 |
| `SchemaTree.kt` | 多处 `state: DatabaseBrowserState`（`:296` / `:445`） | ❌ 需先抽接口 |
| `TablePreview.kt` | `state: DatabaseBrowserState`（`:156`） | ❌ |
| `ExportDialogs.kt` | `state: DatabaseBrowserState`（`:267`） | ❌ |
| `GenerateWorkbench.kt` | `state: DatabaseBrowserState`（`:157` / `:303`） | ❌ |

**本项目的先例**：`desktopApp/.../main.kt:78-79` 的 KDoc 明确写着 ——
「导航条本体 (`TopNavBar` / `AppDestination`) 与应用主题 (`SundaysTheme`) 是平台无关的纯 UI，
**已上移到 `:shared` 的 `commonMain`** —— 本文件只剩『平台窗口 + 引擎实现装配』」。
所以「平台无关的纯 UI 上移到 `:shared`」在本项目是**既定做法**，不是新规矩。

**v1 的决定**：分析模式**不依赖**任何 `desktopApp` 组件上移。理由是：

- 分析模式要的是**数据源树**，而 `SchemaTree` 渲染的是「库 → 表 → 列」+ 右键菜单
  （建表 / 改表 / 删表）—— 分析模式这些写操作**一个都不要**（§3）。
- `ExportDialogs` 的 `ExportFormat` 是「一行流式追加」的单表导出（§7.1），不是报表。

→ 分析模式**自建** `SourceTree`（§6.4），但它**用** `:shared` 的 `EmptyHint` / `ColumnBadge` /
`focusRing` / `onRightClick` / `winShape` 拼出来 —— **不是**从零画一套。

**留下的问题**（→ Q9）：要不要顺手把 `BrowserToolBar` / `BrowserChrome` 上移？
本设计的立场是**不夹带** —— 那是浏览模式的内部重构，与数据分析模式无关，
混进来会让本设计的大范围改动更难评审。

### 6.4 确需新建的组件（6 个，全部落在 `:shared`）

| # | 组件 | 位置 | 为什么现有的不够用 |
|---|---|---|---|
| 1 | `SourceTree` | `shared/.../analysis/SourceTree.kt` | `desktopApp/SchemaTree.kt` 的语义是**可写浏览**（含建表 / 改表 / 删表的右键菜单），分析模式一个都不要；且它深度耦合 `DatabaseBrowserState`（§6.3.5） |
| 2 | `ReportBlockList` | `shared/.../analysis/ReportBlockList.kt` | 报表区块是**有序区块序列**（可增删、可上下移），`DataTable` 是**二维表** —— 不是同一件事的两种画法 |
| 3 | `ReportColumnSpecRow` | `shared/.../analysis/ReportColumnSpecRow.kt` | 区块的列配置 = 列 + **呈现类型** + **数字格式** + 对齐。`TableColumn` 只有 `key/header/width/weight/alignment/formatter`，**没有类型、没有数字格式**；而 `ColumnTypeCatalog`（`shared/.../table/ColumnTypeCatalog.kt`）是**数据库类型**（`VARCHAR` / `INT`…），报表要的是**呈现类型**（文本 / 整数 / 小数 / 百分比 / 货币 / 日期）—— 两者不是一回事，不能复用 |
| 4 | `ReadOnlyBadge` | `shared/.../analysis/ReadOnlyBadge.kt` | `ColumnBadge` 是「类型小标签」（`surfaceVariant` 底、11px 字），只读徽标要 **tertiary 底 + 不可关闭 + ⓘ 说明** —— 语义与视觉都不同 |
| 5 | `PullStageIndicator` | `shared/.../analysis/PullStageIndicator.kt` | 拉取是**三段**（EXPORT → CSV → IMPORT）跨进程的长任务；`WinProgressIndicator` 是单条进度条，画不出「现在卡在哪一段」 |
| 6 | `WinDropdown` | **加进** `shared/.../ui/WinControls.kt` | 见 §6.3.4 —— 它是**扩充**现有控件集，不是独立体系 |

**共同的底线**：这 6 个组件

- 一律落在 `:shared` 的 `commonMain`（**不引入 expect/actual**）
- 只用 `UiChrome.kt` 的 token（`winShape` / `controlShape` / `uiBevel` / `selectionColorsFor`）
  与 `SundaysPalette` 的 `ColorScheme`，**不新增任何颜色**
- 文字一律走 `str(StrKey)`，不写死中文
- 全部带 KDoc，写清「**为什么**这么设计」而不是复述代码在做什么

### 6.5 组件层怎么表达「只读」

只读的**保证在引擎与方言层**（§3 的 L3 / L4），组件层只做**表达**，不做**判断**。

| 层 | 做什么 | 不做什么 |
|---|---|---|
| 引擎 / 方言（L3 / L4） | 真正拦住写操作 | —— |
| 组件层 | 不提供写入口：`DataTable` 的 `onCellEdit = null`；SQL 编辑器只放行 `DangerousSql.isReadOnlyQuery` 判定为只读的语句；徽标**不可关闭** | **不自己判断「这条 SQL 算不算写」** |

> 为什么组件层不判断：同一个判据写两遍，迟早会不一致。而「界面拦住了、引擎放行了」这种不一致，
> 会让用户以为「这条语句不安全」—— 其实引擎是拦得住的。这是**降低**而非提高安全性，
> 与 §3.2「主防线必须在方言级」是同一条道理。

### 6.6 组件复用的可验证判据

「尽量复用」不能只靠自觉，加一条**结构测试**钉住：

`AnalysisModuleBoundaryTest`（`shared/src/commonTest/.../analysis/`）断言：

1. `shared/.../analysis/` 下的所有 `.kt` **不 import** `desktopApp` 包里的任何类型
2. `shared/.../analysis/` 下的所有 `.kt` **不 import** `java.*` / `javax.*`
3. 新文件里不出现**字面量颜色**（`Color(0x…)` / `"#RRGGBB"`）—— 颜色只能来自
   `MaterialTheme` / `LocalUiTokens`

这三条把「组件复用」从口号变成**可跑的检查**。

### 6.7 本节与迭代的对应

| 里程碑 | 本节对应的动作 |
|---|---|
| **M1** | §6.3.1（方言档位注册表化）、§6.3.2（前端方言元数据收进一处）、§6.3.4（`WinDropdown`） |
| **M2** | §6.5 的组件层表达（只读徽标、编辑器只读判据） |
| **M3** | Hive / Impala 档位**注册**进档位注册表 —— 前端零改动（这是 §6.3.1 的验收现场） |
| **M4** | `SourceTree` + `DataTable` 的只读用法（§6.3.3） |
| **M5 / M6** | `ReportBlockList` / `ReportColumnSpecRow` / `PullStageIndicator` |
| **横向** | §6.6 的结构测试，每个里程碑都跑 |

---

## 7. 报表模型与 Excel 输出

### 7.1 为什么不能直接复用 `ExportEngine`

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

### 7.2 `ReportSpec`（纯数据，不含 POI）

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

### 7.3 `ReportBuilder`（引擎侧，POI）

`engine/src/main/kotlin/com/langbiantianya/report/`：

- `ReportBuilder` —— `ReportSpec` + 数据 → `.xlsx`
- `ReportCellStyler` —— 把 `ReportStyle` / `ReportColumn` 落到 POI `CellStyle`（**样式对象要缓存复用**，否则百万行的表会创建百万个 style）
- `CellValueWriter` —— 从 `engine/export/ExcelWriter.kt` 抽出来的共享类型分派

> ⚠️ **POI 样式对象的坑**：`XSSFCellStyle` 每建一个就是一份 XML，开到几万行会 OOM。
> 报表的列数有限（通常 < 30），按「列类型 × 样式」缓存几十个 style 即可 ——
> 这条要写进 `ReportCellStyler` 的 KDoc，否则下一个人会 inline 写 `createCellStyle()`。

### 7.4 协议

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

## 8. AI 洞察：扩展点怎么留

> 用户要求「后续迭代接入 ai 实现智能分析制作报表」。v1 不做，但**接口要留对**——
> 留错了将来要动执行链路，那就是大改。

### 8.1 唯一的一条铁律

> **AI 只产出 `ReportSpec`，不产出文件。**

理由：

1. **安全**：执行链路不变，AI 挂掉不影响手动出报表。
2. **可回放**：`ReportSpec` 里存着 SQL 与参数，同一份 spec 永远出同一份文件。
3. **可审阅**：报表里每个数字都能追到一条用户看得见（且能改）的 SQL。
   AI 若是直接写文件，用户就永远不知道那些数字是怎么来的 —— **报表的可信度就断了**。
4. **可测试**：`ReportSpec` 是纯数据，AI 的产出可以直接断言「它选了哪张表、用了什么聚合」。

### 8.2 接口

```kotlin
// engine/src/main/kotlin/com/langbiantianya/analysis/InsightProvider.kt
interface InsightProvider {
    /** 输入：目标源的 schema 摘要 + 样本统计 + 用户问题 */
    suspend fun suggest(context: InsightContext): ReportSpec
}
```

- `InsightContext` 里放**受控的摘要**（表名、列名、类型、行数估计、少量样本统计），
  **不放原始数据**——把整表塞给模型既贵又不安全。
- 生成的 SQL 同样过 §3.4 的只读闸门。
- 引擎侧预留路由 `ANALYSIS.SUGGEST`（v1 不实现，返回「未启用」）。

### 8.3 配置落在共享设置页

AI 的模型端点 / 模型名 / API Key 等，**放在现有设置页的新增「数据分析」分类里**（§1.3），
不另起一个设置界面 —— 复用是本轮评审意见的主旨之一。

> 🔐 凭据怎么存：与现有连接配置同级的明文存储是**现状**，
> 但 AI 的 API Key 比数据库口令更敏感。v1 不做 AI，所以**这一项留到 M7 决定**，
> 不要在设计阶段假装它不存在（见 §13 Q8）。

### 8.4 UI 占位

分析工作台工具条右侧放一个「AI 洞察」按钮：v1 **禁用** + tooltip「后续迭代提供」。
位置摆在那里，是为了让 v1 的布局**不因 M7 而重排**。

---

## 9. 界面设计

完整设计图：[`ui-mockup.html`](./ui-mockup.html)（浏览器直接打开，顶部可切 5 套配色 × 明暗 × 6 个画面）。

### 9.1 样式完全复用现有资源

**一行新样式代码都不写。** 依据：

- 主题入口 [`shared/.../ui/Theme.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/Theme.kt)
  的 `SundaysTheme` —— 分析模式的所有界面都在它内部渲染，配色 / 明暗 / 紧凑档**自动继承**。
- 配色 [`shared/.../ui/ThemePalette.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemePalette.kt)：
  5 套（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）× 明暗 2 档 = 10 套 `ColorScheme`。
- 真实色值见 [`theme-tokens.json`](./theme-tokens.json) —— 这是**从
  `SundaysPalette.kt` 脚本抽取**的快照（10 套 × 28 个 Material3 角色 + 9 条字号阶梯），
  不是手抄的。设计图里的每一个颜色都能在这个文件里找到出处。
- 组件复用清单见 §6.2；控件外观统一走 `UiChrome.kt` 的 token
  （`winShape` / `controlShape` / `uiBevel` / `selectionColorsFor`）。

### 9.2 设计约束（与现有界面一致）

来自 `SundaysPalette.kt` 的 KDoc，设计图必须体现：

1. **不用 tonalElevation 叠色分层** —— 层次靠 **1px `outlineVariant` 分隔线**，不靠阴影。
2. **圆角 4~8dp**（现代三套 6px；Win2000 全直角 0；WinXP Luna 递增）。
3. **字号比 M3 默认小 1sp**（`bodyMedium` 13sp / `labelSmall` 11sp）—— 信息密集的桌面观感。
4. **WCAG AA**（≥4.5:1）由 `SundaysPaletteTest` 逐对钉住，设计图不得引入新配色破坏它。

### 9.3 每个界面区域 ↔ 复用的组件

**这张表是本节与 §6 的接口** —— 设计图上看到的每一块，都能在这里查到它由哪个现成组件渲染。

| 画面 | 界面区域 | 复用的组件（`:shared` commonMain） | 新增？ |
|---|---|---|---|
| ② 分析工作区 | 顶栏按钮 | `WinButton` / `WinOutlinedButton` / `WinIconButton` | —— |
| ② 分析工作区 | 顶栏「⊘ 只读」徽标 | `ReadOnlyBadge` | ★ 新增（§6.4-4） |
| ② 分析工作区 | 左侧数据源树 | `SourceTree`（内部用 `EmptyHint` / `ColumnBadge` / `focusRing` / `onRightClick` / `winShape`） | ★ 新增（§6.4-1） |
| ② 分析工作区 | 中间 SQL 编辑器 | `CodeEditorWithToolbar` / `CodeEditorState` / `SqlFormatter` | —— |
| ② 分析工作区 | 结果表 | `DataTable` + `TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` | —— |
| ② 分析工作区 | 中右分隔条 | `DragHandle`（`hoverIcon` 由桌面端注入） | —— |
| ② 分析工作区 | 底部状态条 | `WinProgressIndicator` / `WinTextField`（只读展示） | —— |
| ② 分析工作区 | 右键菜单 | `ContextMenuState` + `onRightClick` | —— |
| ③ 报表设计器 | 左侧区块列表 | `ReportBlockList` | ★ 新增（§6.4-2） |
| ③ 报表设计器 | 中间列规格 | `ReportColumnSpecRow` + `WinDropdown` | ★ 新增（§6.4-3、§6.3.4） |
| ③ 报表设计器 | 右侧 Excel 预览 | `DataTable`（只读）+ `WinSurface` | —— |
| ④ 连接管理器 | **整屏** | `ConnectionManagerScreen`（**同一个**，只换分组方式） | —— |
| ⑤ 连接向导 | **整屏** | `AddConnectionDialog` / `ConnectionWizardContent`（**同一个**） | —— |
| ⑤ 连接向导 | 认证方式下拉 | `WinDropdown` | ★ 新增（§6.3.4） |
| 全部 | 顶栏右上角 | `SettingsEntryButton`（⚙ 图标按钮） | —— |
| 全部 | 主题 / 圆角 / 立体边 / 选中色 | `SundaysTheme` / `UiChrome` token | —— |
| 全部 | 文案 | `str(StrKey)` + `i18n` 的 6 语言表 | —— |

> **统计**：本模式**零改动复用的组件 19 项**，**新增组件 6 项**（其中 1 项是扩充 `WinControls`）。
> 没有一项是「另写一套看起来差不多的」。

> ⚠️ **顶栏右上角只放一个 ⚙ 设置入口**（与浏览模式同一个 `SettingsEntryButton`），
> **不放主题切换、不放语言切换**。理由跟真实应用一致：
> `desktopApp/.../main.kt` 的 KDoc 写明「外观切换……**只在设置页**渲染明暗切换与配色选择」，
> `ConnectionManagerScreen.kt` 的 KDoc 也写着「『个性化 → 明暗档位』单选组里，
> 标题行**不再放应用级外观按钮**」。
> 顶栏挂「外观」「语言」两个入口，会让人以为点一下就能换配色，实际却只能先进设置页 ——
> 多出来的两个按钮既不兑现承诺，又和「明暗档位在设置页里」这条唯一真相源打架。

### 9.4 六个画面

| 画面 | 内容 |
|---|---|
| **① 顶层导航** | 新增「数据分析」顶层 chip（与「连接管理」并排）。下方说明卡片讲清本模式的边界与「复用什么」。 |
| **② 分析工作区** | 左：数据源树（**来自共享连接列表**，按 `ANALYSIS_SOURCE` 过滤；远程表按需拉取）。中：SQL 编辑器 + 结果表（带占比条形）。顶栏：`⊘ 只读` **不可关闭**的徽标 + 运行 / 生成报表。 |
| **③ 报表设计器** | 左：区块列表（可增删排序）。中：区块配置（列规格 / 类型 / 数字格式 / 样式）。右：Excel 观感预览（标题 + 元信息 + 表头 + 斑马纹）。 |
| **④ 连接管理器（复用 + 通用化后）** | **同一个**连接管理器：列表按「可浏览 / 可分析」分组、只读方言带 ⓘ 徽标；右侧是选中连接的详情（含「只读方言」说明与「在数据分析中打开」）。 |
| **⑤ 新增连接向导（复用 + 通用化后）** | **同一个**添加连接弹窗，但表单由**方言自描述**渲染：认证方式下拉（账密 / Kerberos）、方言特有字段、「高级属性」折叠区、以及**自定义 JDBC URL** 逃生舱。 |
| **⑥ 组件复用对照（v0.3 新增）** | 一张对照表：每个界面区域 → 复用的现成组件 / 新增的组件，并标出各自落在 `:shared` 的哪个文件。它把 §6 与 §9.3 变成**能看见的东西**。 |

> ⚠️ 画面 ④⑤ 与 v0.1 的差别：v0.1 画的是一个**专属的**「添加分析数据源」弹窗，
> v0.2 改成**复用的**连接管理器与向导 —— 这是第二轮评审意见的直接结果。
>
> ⚠️ 画面 ⑥ 是 v0.3 新增的：它不是「另一个界面」，而是**本设计的自证** ——
> 第三轮评审意见说「组件和控件也尽量复用」，这一屏就是「复用了哪些」的答案。

---

## 10. 代码落点

### 10.1 新增模块

| 路径 | 内容 |
|---|---|
| `shared/src/commonMain/.../analysis/` | `ReportSpec.kt`（报表规格）、`AnalysisScreen.kt`、`ReportDesignerScreen.kt` |
| `shared/src/commonMain/.../analysis/SourceTree.kt` | 数据源树（**新增组件 1/6**，§6.4） |
| `shared/src/commonMain/.../analysis/ReportBlockList.kt` | 报表区块列表（**新增组件 2/6**） |
| `shared/src/commonMain/.../analysis/ReportColumnSpecRow.kt` | 列规格行（**新增组件 3/6**） |
| `shared/src/commonMain/.../analysis/ReadOnlyBadge.kt` | 不可关闭的只读徽标（**新增组件 4/6**） |
| `shared/src/commonMain/.../analysis/PullStageIndicator.kt` | 拉取三段进度（**新增组件 5/6**） |
| `shared/src/commonMain/.../editor/language/SqlDialectProfileRegistry.kt` | 方言档位注册表（§6.3.1） |
| `shared/src/commonTest/.../analysis/AnalysisModuleBoundaryTest.kt` | 组件复用结构测试（§6.6） |
| `desktopApp/src/main/.../AnalysisSession.kt` | 分析模式的会话与状态机（与 `ConnectionSession` / `DatabaseBrowserState` **并列**，不继承） |
| `engine/src/main/.../analysis/AnalysisHandler.kt` | `Category.ANALYSIS` 的路由实现 |
| `engine/src/main/.../analysis/InsightProvider.kt` | AI 扩展点（v1 只有接口） |
| `engine/src/main/.../report/ReportBuilder.kt` | `ReportSpec` → .xlsx |
| `engine/src/main/.../report/ReportCellStyler.kt` | 样式映射（**带缓存**） |
| `api/src/main/.../dialect/ConnectionFormSpec.kt` | **方言自描述的连接表单规格**（§5.2） |
| `dialect-hive/` | Hive 只读方言（SPI 注册照抄 `dialect-duckdb` 的 `META-INF/services`） |
| `dialect-impala/` | Impala 只读方言（**自定义 URL 前缀**，见 §4.3） |

### 10.2 改动既有文件

| 文件 | 改动 |
|---|---|
| `shared/.../navigation/AppDestination.kt` | 加 `ANALYSIS(StrKey.NAV_ANALYSIS)` |
| `desktopApp/.../main.kt` | `MainScreen` 的 destination 分支加分析模式 |
| `shared/.../connection/ConnectionConfig.kt` | 加 `authMethod` / `properties`；`DialectType` 加 `HIVE` / `IMPALA`（含 `defaultsFor` 默认端口 10000 / 21050）；`ConnectionType` 加 `CUSTOM_URL`；**四处硬编码 `when` 收进方言元数据表**（§6.3.2） |
| `shared/.../connection/AddConnectionDialog.kt` | 表单渲染改为**优先读方言的 `connectionForm`**，读不到才回退旧分支 |
| `shared/.../connection/ConnectionStorage.kt` | 持久化版本 2 → 3，v2 记录补齐新字段 |
| `shared/.../settings/SettingsCategory.kt` | 加「数据分析」分类（AI 配置 / 报表默认样式 / 输出目录） |
| `shared/.../ui/WinControls.kt` | **加** `WinDropdown` / `WinDropdownMenu`（**新增组件 6/6**，§6.3.4） |
| `shared/.../editor/language/SqlDialectProfile.kt` | `ALL` 降级为快照；新增 `SqlDialectProfileRegistry`（§6.3.1） |
| `shared/.../editor/formatter/SqlFormatter.kt` | `register()` 改成遍历**注册表**而非 `ALL`（§6.3.1） |
| `shared/.../table/DialectCapabilities.kt` | `of(dialect)` 改查方言元数据表（§6.3.2） |
| `desktopApp/.../DatabaseBrowserState.kt` | `toSqlDialectProfile()` 改从「方言 → 档位」映射表读（§6.3.1） |
| `api/.../DatabaseDialect.kt` | 加 `connectionForm` 属性 + `buildJdbcUrl(spec)` 新重载 |
| `api/.../DialectCapability.kt` | 加 `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT` |
| `engine-protocol/.../idb_engine.proto` | `Category.ANALYSIS/REPORT` + 新 Action + `ReportRequest` + `ConnectionConfig.read_only` |
| `engine/.../dispatcher/RequestDispatcher.kt` | 只读闸门（L4） |
| `engine/.../export/ExcelWriter.kt` | 类型分派抽到 `CellValueWriter`（**不改变现有行为**） |
| `i18n/src/main/.../StrKey` + 6 张文案表 | 新增文案键（**六语言必须同步**） |
| `settings.gradle.kts` | `include(":dialect-hive")` / `include(":dialect-impala")` |
| `desktopApp/build.gradle.kts` | `runtimeOnly(project(":dialect-hive"))` 等 + 驱动依赖 |
| `gradle/libs.versions.toml` | `hive-jdbc` 版本（spike 结论出来后定） |

### 10.3 打包要留意

`desktopApp/build.gradle.kts` 的 jlink `modules(...)` 清单是**手工枚举**的（12 个），
注释里写明「升级依赖后要重算一遍」。Hive 驱动会引入 Thrift / SLF4J / 可能的 SSL 依赖，
**新模块可能要补进 jlink 清单** —— 否则症状是「装得进去却启不了」（注释里已写明这个坑）。

---

## 11. 迭代任务拆分

> 每个里程碑都以「**用户能看见的东西**」结束，不以「代码写完了」结束。
> 每条验收标准都写成**可判定**的（能跑出一条命令 / 能点出一个现象），
> 避免「基本能用」这种没法验收的说法。

### M0 —— 设计冻结 ✅（v0.1 + v0.2 + v0.3）

- **交付**：[本文](./README.md) + [UI 设计图](./ui-mockup.html) + [主题色值快照](./theme-tokens.json)
- **验收**：本文的每条「现状」陈述都能在仓库里找到对应文件；UI 设计图在 10 种配色 × 6 个画面下都能读。
- **产出**：待定问题（§13）逐条有结论。

### M1 —— 连接管理器与组件通用化 ★（前置）

> 标题从「连接管理器通用化」改成「连接管理器**与组件**通用化」：
> §5 通用化的是「**表单**怎么描述」，§6 通用化的是「**控件**怎么描述」——
> 两者是同一件事的两半，拆成两个里程碑反而会让「先通用化再接方言」这件事只做一半。

- **交付 —— §5 连接管理器侧**
  - `api`：`ConnectionFormSpec` / `ConnectionField` / `AuthMethod`；`DatabaseDialect.connectionForm`；
    `buildJdbcUrl(spec)` 新重载（**旧方法保留**）
  - `api`：`DialectCapability` 加 `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT`
  - `shared`：`ConnectionConfig` 加 `authMethod` / `properties`；`ConnectionType.CUSTOM_URL`；
    `DialectType` 加 HIVE / IMPALA（**先只有元数据，无方言实现**）
  - `shared`：连接向导按 `connectionForm` 渲染（优先读 spec，回退旧分支）
  - `shared`：连接持久化 v2 → v3 + 迁移
  - `shared`：连接管理器按能力位分组 + 只读徽标
- **交付 —— §6 组件侧**
  - `shared`：`SqlDialectProfileRegistry`（§6.3.1）；`SqlFormatter.register()` 改遍历注册表
  - `shared`：`DialectType` 的**四处**硬编码 `when` 收进方言元数据表（§6.3.2）
  - `shared`：`WinControls.kt` 加 `WinDropdown` / `WinDropdownMenu`（§6.3.4）
  - `shared`：`AnalysisModuleBoundaryTest` 结构测试（§6.6）
- **验收**
  - **加一个假方言**（3 个自定义字段 + 2 种认证方式）→ 连接管理器**自动**渲染出这 3 个字段与认证下拉，
    **前端零改动**（§5.7）
  - **现有 5 个方言的连接向导与 `connection.json` 测试全绿且未修改**
  - `CUSTOM_URL`：填认识的 URL → 正确反查方言；填不认识的 → 明确报错
  - v2 的 `connection.json` 加载后自动补齐新字段
  - `properties` 里的 secret 字段在 UI 脱敏、在导出配置时剔除
  - **运行时注册一个假方言档位** → `CodeEditor(languageId = "sql-fake")` 立刻有高亮、
    格式化按钮可用（§6.3.1）
  - `WinDropdown` 在 **10 种配色**下都不出现 Material3 圆角（现代三套 6px / Win2000 全 0 / WinXP 递增）
  - `AnalysisModuleBoundaryTest` 绿（无 `desktopApp` 依赖、无 `java.*`、无字面量颜色）
- **依赖**：无
- **风险**：R9（前端表单迁移期的新旧两套渲染分叉 —— 用「优先读 spec + 回退」控制）、
  R12（组件复用退化 —— 用 §6.6 的结构测试钉住）

### M2 —— 只读地基

- **交付**
  - proto：`Category.ANALYSIS` / `Category.REPORT` / 新 Action / `ConnectionConfig.read_only`
  - 引擎：`RequestDispatcher` 的只读闸门（L4）；`ANALYSIS.LIST_SOURCES` / `RUN_QUERY` 两条只读路由
  - UI：`AppDestination.ANALYSIS` + 空壳主屏 + **不可关闭**的只读徽标（§6.4-4）
  - 组件层只读表达（§6.5）：SQL 编辑器只放行 `DangerousSql.isReadOnlyQuery` 判定为只读的语句
  - 测试：§3.6 的两类回归测试（方言级 + 会话级）
- **验收**
  - 在只读会话里发 `TABLE.CREATE` / `DATA.INSERT` / `IMPORT.RUN_IMPORT` / `SQL.EXECUTE("INSERT …")`
    —— **全部**返回明确的只读错误，且**不进入 handler**
  - 同一个 `INSERT` 在普通会话里照常成功（证明闸门只挡只读会话，没把功能打死）
  - 界面上「只读」徽章**没有关闭入口**
- **依赖**：M1（能力位与方言位要先存在）

### M3 —— 数据源接入（Hive / Impala）

- **交付**
  - **3.0 spike（先做，不产出功能代码）**：§4.2 的四条假设各给出一条**可复现**的结论
  - `dialect-hive` / `dialect-impala`（只读子集 + 各自的 `connectionForm`）
  - Hive / Impala 的 `SqlDialectProfile` **注册**进档位注册表（§6.3.1）——
    **前端零改动**：编辑器高亮、格式化按钮自动可用
  - `AnalysisSourceConfig` → **不需要**（复用 `ConnectionConfig`）
- **验收**
  - 对着**真实** HiveServer2：能列出库 → 表 → 列，列名与类型正确
  - 对着**真实** Impala：同上（若 spike 结论是「HS2 不通」，本里程碑改为只交付 Hive，
    并在文档里如实记录 Impala 的状态 —— **不假装做完**）
  - 引擎对该数据源发一条 `INSERT` → 被拒（方言级 + 路由级两道都验）
  - `jdbcUrlPrefix` 无歧义（§4.3 的问题已解决并有测试钉住）
  - **同一个 Hive 连接在浏览模式与分析模式里都能看到**（复用验证），
    且在**浏览模式**里也**不能写**（L3 方言级验证）
  - **加 Hive 档位时 `:shared` 的编辑器模块零改动**（§6.3.1 的验收现场 ——
    这条是「组件也通用化」的全部意义所在）
- **依赖**：M1、M2
- **风险**：R1（依赖冲突）、R2（HS2 兼容性）、R3（URL 前缀撞车）

### M4 —— 分析工作台

- **交付**
  - DuckDB 工作区（**复用 `dialect-duckdb`，零新方言**）
  - 拉取链路：远端 `EXPORT` → CSV → DuckDB `IMPORT`（§2.4 的方案 C）
  - SQL 编辑（复用 `CodeEditor`）+ 结果表（复用 `DataTable`，**只读用法见 §6.3.3**）
  - `SourceTree`（§6.4-1）+ `PullStageIndicator`（§6.4-5，三段式拉取进度）
  - 拉取面板显示**预计占用**与临时文件清理时机
- **验收**
  - 拉一张 **10 万行**的表进 DuckDB（用 `sundays_smoke` 或本地造的等价数据），
    界面显示进度、可取消，落地后可查
  - 在工作区里跑一条**窗口函数**聚合（`OVER (PARTITION BY …)`），结果正确
  - 工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**（本地写允许，远端写禁止）
  - 拉取产生的临时 CSV 在**成功/失败/取消**三条路径上都被清理
  - 结果表**没有**任何编辑入口（双击单元格不进入编辑态）—— §6.3.3 的 `onCellEdit = null`
- **依赖**：M3

### M5 —— 报表模型与 Excel 输出

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
- **依赖**：M2（路由）；M4（能拿到数据）

### M6 —— 报表设计器 UI

- **交付**：`ReportBlockList`（§6.4-2）/ `ReportColumnSpecRow`（§6.4-3）+ 区块配置
  （列规格、类型、数字格式、样式）/ Excel 预览 / 导出
- **验收**
  - 在设计器里把某一列的类型从 `DECIMAL` 改成 `PERCENT` → **预览立即变** → 导出的文件也一致
  - 增删区块 / 调区块顺序 → 导出文件的区块顺序一致
  - 预览与导出文件的**内容**逐格一致（预览不是另画一套，是同一份 spec 的渲染）
  - 数字格式下拉在 **10 种配色**下都是 Win 控件外观（§6.3.4）
- **依赖**：M5

### M7 —— AI 洞察（后续迭代，不在 v1 范围）

- **交付**：`InsightProvider` 实现 + `ANALYSIS.SUGGEST` 路由 + 设置页「数据分析」分类里的模型配置
- **验收**
  - AI 产出的 spec 经**同一套**校验与渲染链路（与手动出报表无差别）
  - AI 生成的 SQL 过只读闸门（构造一个含写操作的 prompt，断言被拒）
  - 模型不可用时手动出报表**不受影响**
- **依赖**：M5 / M6

### 横向任务（每个里程碑都要做）

| 项 | 说明 |
|---|---|
| **i18n 六语言** | 新文案键必须**六张表同步**（简 / 繁 / EN / RU / JA / DE），仓库有文案一致性测试盯着 |
| **文档同步** | `ARCHITECTURE.md` / `FEATURES.md` / `TEST_CASES.md` / 各模块 README —— 文档是契约 |
| **行尾与格式** | `ktlintFormat` → `fix-crlf.ps1` → **按字节校验**（`.sh` 保持 LF，其余 CRLF 无 BOM） |
| **打包** | 新依赖进来后**重算 jlink modules 清单**（见 §10.3 的坑） |
| **组件边界** | `AnalysisModuleBoundaryTest`（§6.6）每个里程碑都跑：新组件不许 import `desktopApp`、不许 import `java.*`、不许写字面量颜色 |

---

## 12. 风险登记

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| **R1** | `hive-jdbc` 依赖树与现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 版本冲突 | **高** | **M3.0 spike 必须先验**。备选：`hive-jdbc:standalone`（自带依赖、避免传递冲突）/ 降级 `hadoop-common` / 独立 ClassLoader 加载方言 / 走 `CUSTOM_URL` |
| **R2** | Impala 的 HS2 兼容性未验证 | **中** | 同上 spike。若不通，Impala 降级为「后置里程碑」，**如实记录**，不假装做完 |
| **R3** | `jdbcUrlPrefix` 撞车（两个方言同一 URL 形状） | **中** | Impala 用自定义前缀（§4.3），spike 一并验 |
| **R4** | 拉取中转的磁盘占用是**两份** | **中** | 拉取面板显示预计占用；临时 CSV 三条路径都要清；提供「工作区清理」入口 |
| **R5** | POI 样式对象不缓存 → 大表 OOM | **中** | `ReportCellStyler` 按「列类型 × 样式」缓存，**验收里钉住**（M5） |
| **R6** | `spec_json` 无 proto 的兼容保证 | **中** | `schemaVersion` 必带，不认识的版本明确报错 |
| **R7** | 大数据量下 UI 卡顿 | **低** | 导出已在子进程跑（`ExportProcessManager`），拉取/报表沿用；结果表用 `DataTable` 的虚拟滚动 |
| **R8** | jlink 模块清单漏项 → 装得上启不来 | **低** | 新依赖进来后用 `jdeps` **重算**（`desktopApp/build.gradle.kts` 注释里有完整命令） |
| **R9** | **通用化迁移期新旧两套表单渲染分叉** | **中** | 「优先读 spec、读不到才回退」的单向迁移；**旧方言不改**；M1 验收里钉住「旧方言测试全绿且未修改」 |
| **R10** | **连接管理器复用后，用户可能期望在浏览模式里编辑 Hive 表** | **中** | 连接列表给只读方言加 ⓘ 徽标 + 明确的「此连接为只读方言」文案；浏览模式不渲染写入口 |
| **R11** | AI 接入时的模型服务与凭据管理 | **低**（M7） | v1 不做；`InsightContext` 不含原始数据，只给摘要；API Key 存法留到 M7 决定（§8.3） |
| **R12** | **组件复用退化** —— 实现时图省事，在 `desktopApp` 里另写一套外观相近的控件，导致「复用」名存实亡 | **中** | ① §6.6 的 `AnalysisModuleBoundaryTest` 把「不许 import `desktopApp` / 不许 import `java.*` / 不许写字面量颜色」钉成可跑的检查；② 新组件一律落在 `:shared` 的 `commonMain`；③ 评审时对着 §9.3 的映射表逐行核 |

---

## 13. 待定问题（**M0 结束前要逐条有结论**）

| # | 问题 | 为什么重要 | 建议默认 |
|---|---|---|---|
| **Q1** | 目标环境的 Hive / Impala **具体是什么发行版与版本**（CDH？HDP？EMR？自建？） | 决定驱动版本、SQL 方言、元数据查询写法 | —— 必须问 |
| **Q2** | 一次拉取的**数据量级**？（万 / 十万 / 千万行） | 决定要不要上方案 B（应用层直搬）、要不要分片 | 按 **十万行**设计，二期再优化 |
| **Q3** | 要不要给 **MySQL / PostgreSQL** 也加 `ANALYSIS_SOURCE` 能力位？ | 加了它们就是「可写方言」进分析模式 → L4 闸门从「兜底」变成「主力」；不加则分析模式只有三个引擎 | **不加**（用户明确只点了 DuckDB / Hive / Impala） |
| **Q4** | 报表**要不要图表**（柱状/折线）？ | POI 图表 API 不稳定，是 M6 的大变量 | v1 **不要**（§1.2） |
| **Q5** | AI 用哪家模型、云端还是本地？ | 属 M7，但**接口**要在 M5 之前定死 | 不影响 v1；先留 SPI |
| **Q6** | 通用化后，**要不要保留旧的枚举分支**作为回退？ | 决定 M1 的迁移策略与 R9 的处理 | **保留**（单向迁移：优先读 spec，读不到回退） |
| **Q7** | 报表模板（样式预设）要不要内置几套？ | 影响 M6 的 `ReportStyle` 设计 | 先做 1 套默认 + 用户可调 |
| **Q8** | AI 的 API Key **怎么存**？（明文同连接配置 / 系统钥匙串 / 加密） | AI 凭据比数据库口令敏感 | **M7 决定**；v1 不做 AI，不要现在假装有方案 |
| **Q9** | 要不要**顺手上移** `desktopApp` 的 UI 组件到 `:shared`？（`BrowserToolBar` 零耦合可直接上移；`BrowserChrome` 抽出 `BackgroundTask` 后可上移） | 能让「平台无关的纯 UI 一律在 `:shared`」这条规矩更彻底 | **不夹带**（§6.3.5）—— 那是浏览模式的内部重构，与本设计无关，混进来会让本轮的大范围改动更难评审。将来单独开一轮做 |
| ~~Q6（旧）~~ | ~~分析数据源要不要复用浏览模式的连接？~~ | —— | ✅ **已定：复用**（v0.2 采纳评审意见） |

---

## 14. 附录

### 14.1 主题色值快照

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

### 14.2 本文档引用的仓库事实

| 事实 | 出处 |
|---|---|
| 顶层导航现有 3 个目标 | `shared/.../navigation/AppDestination.kt` |
| 5 套配色 × 明暗 2 档 | `shared/.../ui/ThemePalette.kt` |
| 10 套 `ColorScheme` + 9 条字号 | `shared/.../ui/SundaysPalette.kt` |
| 连接配置模型与 `DialectType`（5 种方言） | `shared/.../connection/ConnectionConfig.kt` |
| 连接持久化版本 `v2` | `shared/.../connection/ConnectionStorage.kt` |
| 设置页分类枚举 | `shared/.../settings/SettingsCategory.kt` |
| 引擎导出 5 种格式（含 `EXCEL`） | `engine/.../export/ExportModels.kt` |
| Excel 写出（POI SXSSF，100 万行/Sheet） | `engine/.../export/ExcelWriter.kt` |
| 导出跑在独立子进程 | `engine/.../export/ExportProcessManager.kt` / `ExportSubProcess.kt` |
| 导入支持 CSV / JSON_LINES | `engine/.../handlers/ImportHandler.kt`、`idb_engine.proto` 的 `IMPORT = 14` |
| 只读 SQL 扫描已有实现 | `shared/.../sql/DangerousSql.kt` |
| 现有只读拦截在**界面层** | `desktopApp/.../DatabaseBrowserState.kt:2980` |
| SPI 的写方法默认抛 `UnsupportedOperationException` | `api/.../DatabaseDialect.kt` |
| 方言靠 `jdbcUrlPrefix` 反查（URL 优先于 driver） | `api/.../DatabaseDialect.kt`、`engine/.../pool/PoolManager.kt` |
| `DialectCapability` 是「声明」不是「探测」 | `api/.../DialectCapability.kt` |
| POI 5.5.1 / DuckDB 1.5.5.1 / hadoop-common 3.5.0 已在依赖里 | `gradle/libs.versions.toml` |
| 现有 5 个方言模块 | `settings.gradle.kts` |
| 方言靠 `META-INF/services` 注册 | `dialect-duckdb/src/main/resources/META-INF/services/…` |
| jlink 模块清单是手工枚举的（12 个） | `desktopApp/build.gradle.kts`（注释里有 `jdeps` 重算命令） |
| 导入支持格式仅 CSV / JSON_LINES | `engine/.../importer/ImportFormat.kt` |
| **方言档位 `ALL` 是硬编码 list**（加 Hive/Impala 必改 `:shared` 核心文件） | `shared/.../editor/language/SqlDialectProfile.kt:341` |
| `SqlFormatter.register()` 遍历 `ALL` 注册「语言 + formatter」配对 | `shared/.../editor/formatter/SqlFormatter.kt:179-187` |
| `CodeLanguageRegistry` 已是**运行时可注册**的（KDoc 明说「不需要修改编辑器组件源码」） | `shared/.../editor/CodeLanguage.kt:94` |
| `DialectType` 在前端有**四处**硬编码 `when` | `shared/.../connection/ConnectionConfig.kt:72-80` `:117-125` `:140-155`、`shared/.../table/DialectCapabilities.kt:52-101` |
| `DialectType` → 方言档位的又一份 if-else | `desktopApp/.../DatabaseBrowserState.kt:149-157` |
| `TableColumn` / `TableRow` 是**通用键值模型**（不含「库表」语义） | `shared/.../table/TableModels.kt:52` `:88` |
| `DataTable` 的 `cellEditable` 默认跟随 `onCellEdit != null`（只读用法零改动） | `shared/.../table/DataTable.kt:187` 的 KDoc |
| `WinControls.kt` 有 16 个控件，**唯独没有下拉选择器** | `shared/.../ui/WinControls.kt` |
| `ColumnTypeCatalog` 是**数据库类型**目录（与报表的「呈现类型」不是一回事） | `shared/.../table/ColumnTypeCatalog.kt` |
| **`desktopApp` 的 8 个 UI 文件没有一个 import `java.*`** —— 已是平台无关代码 | `desktopApp/.../SchemaTree.kt` / `BrowserChrome.kt` / `BrowserToolBar.kt` / `TablePreview.kt` / `SqlWorkbench.kt` / `ExportDialogs.kt` / `GenerateWorkbench.kt` / `DatabaseBrowserScreen.kt` |
| `BrowserToolBar.kt` 对 `DatabaseBrowserState` **零引用** | `desktopApp/.../BrowserToolBar.kt` |
| `BrowserChrome.kt` 只耦合 `DatabaseBrowserState.BackgroundTask` | `desktopApp/.../BrowserChrome.kt:808` |
| `SchemaTree` / `TablePreview` / `ExportDialogs` / `GenerateWorkbench` 深度耦合 `DatabaseBrowserState` | `desktopApp/.../SchemaTree.kt:296` `:445`、`TablePreview.kt:156`、`ExportDialogs.kt:267`、`GenerateWorkbench.kt:157` `:303` |
| 「平台无关的纯 UI 上移到 `:shared`」是本项目**既定做法** | `desktopApp/.../main.kt:78-79` 的 KDoc（`TopNavBar` / `SundaysTheme` 已上移） |
