# 数据分析模式 —— M2 + M4' 实现设计

> 本篇是 [`./README.md`](./README.md)（v0.3 主设计）的**实现期补充**：主设计冻结的是
> 「做什么」，本篇冻结的是「**怎么做**」——模块落点、协议字段、判据与提交切分。
> 两篇冲突时，**主设计的决策优先**；本篇只记录主设计没有定死、且实现时必须定的部分。

---

## 1. 本轮范围

| 做什么 | 不做什么（及原因） |
|---|---|
| **M2** 只读地基：proto 新类别 / 新动作 / `read_only` 标记、引擎 L4 闸门、`ANALYSIS` 四个读路由、顶层导航、不可关闭的只读徽标、§3.6 两类回归测试 | **M3** Hive / Impala：卡在驱动版本取舍（R1）与目标环境发行版版本未定，设计文档要求先对着真实 HiveServer2 做 spike |
| **M4'** 分析工作台：DuckDB 本地工作区、拉取链路、SQL 编辑 + 只读结果表、`SourceTree`、`PullStageIndicator` | **M5 / M6** 报表模型与报表设计器：等本轮的地基与工作台跑通后再做 |
| 能力位：给 **MySQL / PostgreSQL** 补声明 `ANALYSIS_SOURCE`（见 §4.1） | **M7** AI 洞察：不在 v1 范围 |

M4' 与 M4 的差别只有一处：**拉取源**从 Hive / Impala 换成现有可连的 MySQL / PostgreSQL。
其余交付项（工作区、拉取链路、只读结果表、`SourceTree`、`PullStageIndicator`、临时文件清理）
与 M4 原文一致——M4 的验收标准本来就写着「用 `sundays_smoke` 或本地造的等价数据」。

---

## 2. 架构：两个会话，一条数据流

主设计 §2.2/§2.3 已经把这件事定死了：**DuckDB 是分析模式自带的计算引擎（默认且常驻），
远端只是数据源**。因此分析模式里存在两个性质完全不同的会话：

```
┌─ 分析模式 ────────────────────────────────────────────────────────┐
│  源会话（远端 MySQL / PostgreSQL）        read_only = true        │
│    ├─ ANALYSIS.LIST_SOURCES / LIST_TABLES / LIST_COLUMNS          │
│    │     列源、列表、列列 —— 只读 SPI                              │
│    └─ EXPORT.RUN_EXPORT               拉取 → CSV（§2.4 方案 C）    │
│                        ↓ CSV                                      │
│  工作区会话（本地 DuckDB 文件）          read_only = false       │
│    ├─ IMPORT.RUN_IMPORT                CSV → staging_<源库>_<表>  │
│    └─ ANALYSIS.RUN_QUERY               在工作区跑分析 SQL（可写） │
└──────────────────────────────────────────────────────────────────┘
```

**为什么源会话要带 `read_only`**：源会话是**远端**的。L4 闸门是引擎侧的保证——
即使前端将来出现 bug、把一条写请求发到了源连接上，引擎也会挡下来。
**为什么工作区不带 `read_only`**：工作区是**本地** DuckDB 文件，写它不碰任何远端；
主设计 M4 的验收明确要求「工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**」。

---

## 3. 协议改动

### 3.1 `Category` / `Action`

```protobuf
enum Category {
  ...
  IMPORT       = 14;
  ANALYSIS     = 15;  // v2.29: 数据分析模式（源浏览 + 工作区查询）
  REPORT       = 16;  // v2.29: 报表生成（M5 落地）
}

enum Action {
  ...
  SESSION_INFO = 25;
  LIST_SOURCES = 26;  // v2.29: 列分析数据源（连接的库）
  LIST_TABLES  = 27;  // v2.29: 列远端表
  LIST_COLUMNS = 28;  // v2.29: 列远端列
  RUN_QUERY    = 29;  // v2.29: 在计算引擎（本地嵌入式）上跑分析 SQL
  BUILD_EXCEL  = 30;  // v2.29: ReportSpec → .xlsx（M5 落地，本轮不实现路由）
}
```

> ⚠️ **编号与主设计 §7.4 不同，这是有意的**：§7.4 是 v0.3 的草图，
> 它的路由名（`RUN_ANALYSIS_QUERY`）与 §3.5 的路由表（`RUN_QUERY` / `LIST_TABLES` /
> `LIST_COLUMNS`）**对不上**。本篇以 **§3.5** 为准（它在「六层防线」那一节，
> 是这套设计的骨架），编号重新排以保持连续。

`Category.REPORT` 与 `Action.BUILD_EXCEL` **本轮就落枚举**，但不实现路由——
发过去会得到明确报错 `No route for REPORT/BUILD_EXCEL — add an entry to
RequestDispatcher.routes`。**先落枚举不实现**在仓库里有先例：
`api/.../DialectCapability.kt` 的 `BROWSE_SOURCE` / `ANALYSIS_SOURCE` /
`READ_ONLY_DIALECT` 就是这么落的（该文件 KDoc 写明了「先把枚举值落地是安全的，
前提是还没有任何方言真的声明它」）。协议枚举不产生任何用户可见入口。

### 3.2 `ConnectionConfig.read_only`

```protobuf
message ConnectionConfig {
  ...
  string jdbc_url  = 10;

  // v2.29: 会话级只读标记（L4 闸门的判据）。
  // 置 true 时，引擎在**进入 handler 之前**拒绝一切写路由（见 §4.2）。
  // 它是**请求级**的，不落 connection.json —— 只读是「这次调用是什么会话」的性质，
  // 不是「这条连接是什么」的性质。
  bool read_only   = 11;
}
```

### 3.3 `AnalysisRequest` / `AnalysisResponse`

```protobuf
message AnalysisRequest {
  oneof body {
    AnalysisListSourcesRequest list_sources = 1;
    AnalysisListTablesRequest  list_tables  = 2;
    AnalysisListColumnsRequest list_columns = 3;
    AnalysisRunQueryRequest    run_query    = 4;
  }
}

message AnalysisListSourcesRequest { }                          // 无参：列该连接的「命名空间」
message AnalysisListTablesRequest  { string namespace = 1; }    // 列表
message AnalysisListColumnsRequest { string namespace = 1; string table = 2; }
message AnalysisRunQueryRequest    { string sql = 1; }
```

响应**复用既有消息**，不另立类型：

```protobuf
message AnalysisResponse {
  oneof body {
    AnalysisListSourcesResponse list_sources = 1;
    AnalysisListTablesResponse  list_tables  = 2;
    AnalysisListColumnsResponse list_columns = 3;
    AnalysisRunQueryResponse    run_query    = 4;
  }
}

message AnalysisListSourcesResponse { repeated string items = 1; }        // 命名空间名
message AnalysisListTablesResponse  { repeated TableListItem items = 1; } // 复用
message AnalysisListColumnsResponse { repeated ColumnDef items = 1; }     // 复用
message AnalysisRunQueryResponse {
  repeated Row rows = 1;              // 复用（map<string, Value>）
  repeated ColumnDef columns = 2;     // 复用
  int32  affected_rows = 3;
  bool   truncated = 4;               // 行数被截断（见下）
}
```

**「命名空间」是这一层的中性叫法**：MySQL 上它是**库**，PG 上它是 **schema**。
两者对 SPI 都是 `listTables(conn, namespace)` 的同一个参数位 —— 浏览模式早就是
这么用的（`TablePreviewTab.schema` 装的一直是**库名**，就是这层历史遗留）。
PG 的**库**在 `Request.connection.database` 里，不出现在树里。

**`RUN_QUERY` 是单次响应，不是流式**：行数上限复用 `SQL_RESULT_MAX_ROWS`（5000，
与浏览模式一致），`truncated` 显式告知被截断。理由：`DATA.LIST` 也是这么返回行的，
而流式要为分析模式另加一套帧类型 —— 收益只是「大结果集渐进显示」，
代价是一整套新协议。第一版不做，等真需要时再加。

### 3.4 `ANALYSIS.RUN_QUERY` 的目标约束 —— **这是防洞的关键**

`RUN_QUERY` 的语义是「在**计算引擎**上跑分析 SQL」（主设计 §3.5）。
它必须能写（工作区里的 `CREATE TABLE tmp AS SELECT …`），所以**不能**被 L4 闸门挡；
但它**绝不能**打在远端上 —— 否则它就是绕过 L4 的后门。

判据用**已有的**能力位 `EMBEDDED_MODE`：

> `ANALYSIS.RUN_QUERY` 只允许对**声明了 `EMBEDDED_MODE` 的方言**执行
> （DuckDB / H2 / SQLite）。远端方言一律拒绝，错误文案明确说明
> 「分析查询只能跑在本地工作区上」。

**为什么不新加一个能力位**：`EMBEDDED_MODE` 的语义正是「这个方言是本地嵌入式」，
与判据完全吻合；新加一个能力位就是给同一件事造第二个名字。

---

## 4. 只读闸门（L4）

### 4.1 落点

闸门放在 **`RequestDispatcher`** 里，**在查路由表之前**判定：

```kotlin
// 伪代码，位置：dispatch() 里 routes[...] 取用之前
if (request.connection.readOnly && isWriteRoute(request.category, request.action)) {
    return flowOf(Response { success = false; error = <只读错误文案> }.build())
}
```

**为什么在 dispatcher 而不是各 handler 内部**：§3.6 的验收要求「**不进入 handler**」。
在 handler 里判，测试就变成「断言错误文案」——那抓不住「handler 已经被调进去了、
只是最后抛了异常」的实现。闸门在 dispatcher，测试才能用**假 handler 计数**证明它没进去。

### 4.2 写路由清单

| 类别 | 被拒的动作 |
|---|---|
| SCHEMA | CREATE / DELETE |
| USER | CREATE / UPDATE / DELETE |
| TABLE | CREATE / UPDATE / DELETE / RENAME / TRUNCATE |
| DATA | CREATE / UPDATE / DELETE |
| FUNCTION | CREATE / UPDATE / DELETE / CALL |
| VIEW | CREATE / DELETE |
| INDEX | CREATE / DELETE |
| FOREIGN_KEY | CREATE / DELETE |
| IMPORT | RUN_IMPORT |
| SQL | EXECUTE |

允许（读 + 会话生命周期）：SCHEMA.LIST、TABLE.LIST / GET_DDL、DATA.LIST、
USER.LIST / GRANTS、VIEW / INDEX / FOREIGN_KEY / TRIGGER / FUNCTION 的读动作、
EXPORT.RUN_EXPORT、SYSTEM.* 全部。

> **`SYSTEM.*` 全放行**：里面有 `DISCONNECT`（关连接池）、`CANCEL`（取消执行）、
> `BEGIN/COMMIT/ROLLBACK`（事务控制）—— 它们不写远端数据；而把 `DISCONNECT` 挡掉
> 会导致用户**关不掉**一个只读连接，那是个功能性 bug，不是安全性收益。

### 4.3 为什么 `SQL.EXECUTE` 整类被拒，不做逐语句判断

主设计 §3.6 的验收里有一条「`SQL.EXECUTE("INSERT …")` 被拒」。若按语句判断，
引擎里就得有一份 SQL 读写分类器 —— 而仓库里那份在 `:shared` 的 `DangerousSql`，
**引擎看不见 `:shared`**。复制一份就是**第二个真相源**，判据迟早不一致
（主设计 §6.5 正是拿这条理由说明「组件层不判断」）。

而且分析模式**在远端根本不需要跑 SELECT**：远端只用来列元数据与拉取。
所以整类拒绝对功能零损失。

### 4.4 `ANALYSIS_SOURCE` 给 MySQL / PostgreSQL —— **更新 Q3 的决策记录**

主设计 §5.5 与 Q3 规定 `ANALYSIS_SOURCE` 只给 DuckDB / Hive / Impala，
**明确不给 MySQL / PostgreSQL**。理由记在 Q3 上：「加了它们就是可写方言进分析模式
→ L4 闸门从兜底变成主力」。

本轮跳过 M3 → Hive / Impala 不存在 → 按原决策，分析模式将**没有任何远端源**，
M4 的拉取链路整条无法实现。

**结论：给 MySQL / PostgreSQL 也声明 `ANALYSIS_SOURCE`，并更新 Q3 的决策记录。**

理由：Q3 的顾虑是「**L4 闸门缺失**」，而本轮 M2 正好把 L4 建起来。
这不是违背 Q3 的意图，而是**补上它当时缺的前置条件**——补上之后，
L4 从「兜底」升为「主力」，正是它该在的位置。

代价要说清楚：MySQL / PostgreSQL 是可写方言，它们进分析模式后，
**L4 是唯一挡在远端数据前面的引擎侧防线**。这正是本轮把闸门与它的回归测试
放在最前面的原因。

---

## 5. 模块落点

### 5.1 `:shared` 的 `analysis/` 包（纯 UI + 纯数据，不碰引擎）

| 文件 | 内容 | 状态 |
|---|---|---|
| `shared/.../analysis/AnalysisModels.kt` | 数据源描述（`AnalysisSourceItem`：id / 名称 / 方言标签）、工作区状态、拉取阶段枚举、staging 表名规则 | ✅ 已落地（**目前只有数据源条目与行测试标记**；工作区状态与拉取阶段留给第 4~5 片） |
| `shared/.../analysis/SourceTree.kt` | 新组件 1/6（主设计 §6.4）：连接 → 库 → 表 的三段树 | ⏳ 第 6 片 |
| `shared/.../analysis/PullStageIndicator.kt` | 新组件 5/6：EXPORT / CSV / IMPORT 三段 | ⏳ 第 5 片 |
| `shared/.../analysis/ReadOnlyBadge.kt` | 新组件 4/6：`⊘ 只读`，**不可关闭** | ✅ 已落地 |
| `shared/.../analysis/AnalysisScreen.kt` | 分析工作区主屏（顶栏 + 左树 + 中编辑器与结果 + 底栏） | ✅ 已落地**骨架**（工具条 / 数据源树 / 只读徽标是真的，右侧主区仍走空态） |

### 5.2 `:desktopApp` 的状态机

`desktopApp/.../AnalysisSession.kt` —— 与 `ConnectionSession` / `DatabaseBrowserState`
**并列**（不继承、不耦合）：持有源列表、工作区连接、当前 SQL 与结果、当前拉取任务。

### 5.3 `:engine`

| 文件 | 内容 |
|---|---|
| `engine/.../analysis/AnalysisHandler.kt` | `ANALYSIS` 四个路由的实现 |
| `engine/.../dispatcher/RequestDispatcher.kt` | **改动**：L4 闸门 + 四条新路由 |
| `engine/.../handlers/ExportHandler.kt` / `ImportHandler.kt` | **零改动**（拉取复用它们） |

### 5.4 测试

| 文件 | 内容 | 状态 |
|---|---|---|
| `engine/.../integration/ReadOnlySessionGateTest.kt` | §3.6 的**会话级**回归测试 | ✅ 已落地 |
| `engine/.../integration/ReadOnlyDialectTest.kt` | §3.6 的**方言级**回归测试 | ✅ 已落地 |
| `engine/.../integration/AnalysisHandlerIntegrationTest.kt` | 四条路由的集成测试：命名空间分派（schema 型 / 库型）/ 行数截断 / 远端方言被拒 / 只读会话里照常可列 | ✅ 已落地 |
| `engine/.../testutil/RemoteLikeDialect.kt` | 假方言：委托 H2 但摘掉 `EMBEDDED_MODE`、`supportsSchema = false` —— 让「远端被拒」这条边界在**不连任何数据库**的前提下可断言 | ✅ 已落地 |
| `desktopApp/.../AnalysisCapabilityContractTest.kt` | 能力位契约：MySQL / PG 声明 `ANALYSIS_SOURCE`，H2 / DuckDB / SQLite 不声明，能力位以**枚举名**下发 | ✅ 已落地 |
| `desktopApp/.../AnalysisModuleBoundaryTest.kt` | 主设计 §6.6 的结构测试（不 import `java.*` / `javax.*`、不 import 引擎与协议层、不出现字面量颜色）—— 落在 `desktopApp`：它读 `:shared` 的**源文件**，而 Gradle 跑测试时的工作目录是**模块根** | ✅ 已落地 |
| `desktopApp/.../AnalysisScreenTest.kt` | 工作区界面：只读徽标可见且**没有可点语义**、数据源列表渲染、无数据源时的空态文案 | ✅ 已落地 |
| `desktopApp/.../AnalysisPullTest.kt` | 拉取链路端到端（真 MySQL → DuckDB） | ⏳ 第 5 片 |
| `shared/.../commonTest/analysis/AnalysisWorkspaceTest.kt` | staging 表名、临时文件清理规则等纯逻辑 | ⏳ 第 4 片 |

### 5.5 i18n

新文案键**六语言同步**（`TextsZhHans` / `TextsEn` / `TextsJa` / `TextsDe` / `TextsRu` /
`TextsZhHant`）。键分两类：

- **前端文案**（走 `str(StrKey)`）：`NAV_ANALYSIS`、`ANALYSIS_READONLY_BADGE` /
  `ANALYSIS_READONLY_HINT`、`ANALYSIS_SOURCE_HEAD` / `_NOTE` / `_FOOT`、
  `ANALYSIS_NO_SOURCE` / `_HINT`、`ANALYSIS_LOCAL_WORKSPACE`、
  `ANALYSIS_WORKSPACE_EMPTY` / `_HINT`、`ANALYSIS_EDITOR_HINT`、
  `ANALYSIS_RESULT_ROWS` / `ANALYSIS_RESULT_TRUNCATED`、
  `ANALYSIS_PULL` / `ANALYSIS_PULLING` / `ANALYSIS_PULL_DONE` /
  `ANALYSIS_PULL_CANCELLED` / `ANALYSIS_PULL_FAILED`、`ANALYSIS_STAGING_TABLE`、
  `ANALYSIS_PULL_STAGE_EXPORT` / `_CSV` / `_IMPORT`
- **引擎文案**（走 `Msg.text` / `Msg.format(StrKey, …)`）：`ENGINE_READ_ONLY_REJECTED`（L4 闸门）、
  `DIALECT_READ_ONLY`（方言级只读）、`ENGINE_ANALYSIS_LOCAL_ONLY`（`RUN_QUERY` 打在远端）

> ✅ **第 3 片已落地**：本轮新增 **24 个**键（六语言同步）——
> 上面两组里除去第 2 片带来的 `ENGINE_READ_ONLY_REJECTED` / `DIALECT_READ_ONLY` 两个，
> 其余全是本轮的。带占位符的是 `ANALYSIS_SOURCE_FOOT`（引擎名）、
> `ANALYSIS_EDITOR_HINT`（工作区）、`ANALYSIS_RESULT_ROWS`（行数）、
> `ANALYSIS_RESULT_TRUNCATED`（上限与实际行数）、`ANALYSIS_PULL_FAILED`（原因）、
> `ANALYSIS_STAGING_TABLE`（staging 表名）。

---

## 6. 工作区与拉取

### 6.1 工作区

- **形态**：一个 DuckDB **文件**，放在应用数据目录下（`workspace.duckdb`）。
  **不是**用户连接——主设计 §2.2 读法 B：「DuckDB 是分析模式自带的计算引擎，
  默认且常驻」；读法 A（用户自己建 DuckDB 连接）被明确否掉了。
- **生命周期**：跨会话常驻（文件复用），退出时正常关闭。
- **staging 表名**：`staging_<源库>_<表名>`（源库与表名都做标识符清洗）。
  带源库前缀是必要的——不同源可能有同名表。

### 6.2 拉取链路（§2.4 方案 C，零新协议）

```
① EXPORT.RUN_EXPORT   源会话（read_only）→ CSV      ← 已有流式实现与进度帧
② ——CSV on disk——     前端知道路径与大小
③ IMPORT.RUN_IMPORT   工作区会话 → staging_<表名>    ← 已有流式实现与进度帧
```

**为什么拆成两次既有调用，而不是主设计 §3.5 里那张路由表上的单个
`ANALYSIS.PULL_SAMPLE`**：

- 拉取要碰**两个**数据库（远端读 + 本地写），而**一个 `Request` 只有一份
  `ConnectionConfig`**。做成单路由，就得在请求体里再塞一份 workspace 配置 ——
  「哪个配置才是本次请求的连接」于是有了两个产地。拆成两次既有调用没有这个问题。
- 两段进度**各自已有实现与测试**（`ExportProcessManager` 的进度帧、
  `ImportProgressFrame`），`PullStageIndicator` 的三段直接映射它们，
  **不需要新的帧类型**。
- 主设计 §2.4 方案 C 的原文要求是「**复用现成导出/导入链路，零新协议**」——
  拆开逐字满足。

**这是对主设计 §3.5 路由表的一处实现期偏离**，在此显式记录：
`ANALYSIS.PULL_SAMPLE` 本轮**不落地**；若 M3 之后确有需要（比如一次请求里
要带采样比例），再作为新路由补。

### 6.3 临时 CSV 的清理

清理写在**编排函数的一处 `finally`** 里——成功 / 失败 / 取消**三条路径同一条语句**，
不是三个分支各写一遍（M4 验收要求「三条路径上都被清理」）。

### 6.4 预计占用

拉取面板显示两行：CSV 文件大小（① 完成后可知）+ 行数（③ 的进度帧给出）。

---

## 7. 测试判据

### 7.1 §3.6 会话级（`ReadOnlySessionGateTest`）

| 断言 | 怎么判 |
|---|---|
| 只读会话里 `TABLE.CREATE` / `DATA.INSERT` / `IMPORT.RUN_IMPORT` / `SQL.EXECUTE("INSERT …")` 被拒 | **假 handler 计数 = 0** —— 证明没进 handler，不是断言错误文案 |
| 错误是「明确的只读错误」 | 错误文本含「只读」且不是 SQLException 原文 |
| **同一个 `INSERT` 在普通会话照常成功** | 证明闸门只挡只读会话，没把功能打死 |

### 7.2 §3.6 方言级（`ReadOnlyDialectTest`）

用一个**假只读方言**（写方法抛 `UnsupportedOperationException`，
声明 `READ_ONLY_DIALECT`）发 `TABLE.CREATE` / `DATA.INSERT` / `USER.CREATE` → 被拒。

> 为什么用假方言而不是真 Hive：M3 本轮不做，仓库里没有只读方言。
> 方言级的**机制**（写方法抛异常 → 引擎给出明确错误）才是这条测试要守的东西；
> 真正的只读方言落地时（M3）再补一条真方言的实例化测试。

### 7.3 §6.6 结构测试

`AnalysisModuleBoundaryTest` 断言 `shared/.../analysis/` 下的 `.kt`：
不 import desktopApp 的任何类型、不 import `java.*` / `javax.*`、不出现字面量颜色。

### 7.4 工作台端到端（`AnalysisPullTest`）

- 真 MySQL（`sundays_smoke`）拉一张表进 DuckDB → `staging_<库>_<表>` 存在、**行数对得上**
- 临时 CSV 在成功 / 失败 / 取消三条路径上都被清理
- 工作区里跑一条窗口函数聚合 → 结果正确
- 工作区里 `CREATE TABLE tmp AS SELECT …` **成功**（本地写允许）

### 7.5 界面（`AnalysisScreenTest`）

- 「⊘ 只读」徽标**没有关闭入口**
- 结果表双击单元格**不进编辑态**（`onCellEdit = null`）

---

## 8. 提交切分

每次提交都能编译、能跑、能看见。**代码与文档同提交**（仓库惯例）。

| # | 提交 | 内容 | 验收 | 状态 |
|---|---|---|---|---|
| 1 | `feat(protocol): 新增 ANALYSIS / REPORT 类别、五条分析动作、连接级只读开关` | proto（`Category.ANALYSIS` / `REPORT`、5 个 Action、`read_only`、`AnalysisRequest` / `AnalysisResponse`）+ Kotlin 映射 | 编译通过；`engine-protocol` 的映射测试全绿 | ✅ **已提交** |
| 2 | `feat(engine): 只读闸门 —— 会话级 L4 + 方言级 L3，配两级回归测试` | dispatcher 闸门 + `ReadOnlySessionGateTest` + `ReadOnlyDialectTest` | §7.1 / §7.2 全绿，且**变异验证**：去掉闸门后测试变红 | ✅ **已提交** |
| 3 | `feat(analysis): 分析模式入口 —— 四条只读路由 + 顶层导航 + 不可关闭的只读徽标` | `AnalysisHandler` + 四条路由注册 + `AppDestination.ANALYSIS` + `TopNavBar` + `ReadOnlyBadge` + 主屏骨架 + 能力位声明 + i18n 六语言 | 界面能进；徽标不可关；`AnalysisHandlerIntegrationTest` / `AnalysisCapabilityContractTest` / `AnalysisModuleBoundaryTest` / `AnalysisScreenTest` 全绿 | ✅ **本轮落地**（提交标题按此风格） |
| 4 | `feat: 分析工作台 —— DuckDB 本地工作区与状态机` | `AnalysisSession` + 工作区文件管理 + staging 表名 | 工作区能建表、能跑 SQL | ⏳ 未开始 |
| 5 | `feat: 拉取链路 —— 远端 EXPORT 到工作区 IMPORT` | 三段编排 + `PullStageIndicator` + 临时 CSV 清理 | §7.4 全绿 | ⏳ 未开始 |
| 6 | `feat: 分析工作区界面 —— SourceTree 与只读结果表` | `SourceTree` + 结果表只读 + 编辑器接通 | §7.5 全绿；端到端可截图 | ⏳ 未开始 |

> 📌 **第 3 片的实际落点比原文多两处**，都是实现时定下来的：
> 一是**方言能力位声明**（`dialect-mysql` / `dialect-postgresql` 声明 `ANALYSIS_SOURCE`）——
> §4.4 决定的「加」，在这一片里真的写进了引擎，前端据此筛数据源；
> 二是**顶层导航条**（`shared/.../navigation/TopNavBar.kt`）——
> 原文第 3 片只写了「顶层导航」，没定它长什么样、落在哪；
> 落点在 `:shared` 的 `commonMain`（纯 Compose，不碰引擎），与 §1.3 的判据一致。
>
> ⚠️ **第 3 片没有做的**：编辑器与结果面板、工作区会话、拉取链路 ——
> 那是第 4~6 片。`AnalysisScreen` 目前是**骨架**：工具条、数据源树、只读徽标是真的，
> 右侧主区仍走空态。

**文档同步**（每次提交内）：`docs/analysis-mode/README.md`（§3.5 路由表 / §5.5 能力位 /
§13 Q3 决策记录 / §11 里程碑状态）、`shared/ARCHITECTURE.md`、
`desktopApp/ARCHITECTURE.md`、`desktopApp/TEST_CASES.md`、根 `README.md` / `ARCHITECTURE.md`、
`api/README.md`（能力位分布表）。

---

## 9. 风险

| 风险 | 处置 |
|---|---|
| **L4 闸门漏了一条写路由** | 写路由清单集中在**一处**函数里（§4.2 的那张表就是它的唯一产地）；测试逐条覆盖 |
| `ANALYSIS.RUN_QUERY` 被打到远端 | 引擎侧校验 `EMBEDDED_MODE`（§3.4），不靠前端自觉 |
| 拉取把远端磁盘写满 | CSV 落临时目录 + 面板显示占用（§6.4）；三段路径都清理（§6.3） |
| MySQL / PG 进分析模式削弱了安全性 | **这正是 L4 存在的理由**；且 §3.6 的两类回归测试是它的守门人 |
| 能力位在前后端两处各写一份 | 前端用引擎 `LIST_DRIVERS` 下发的 `DialectInfo.capabilities`，**不另立真相源** |

---

## 10. 待定（沿用主设计 §13）

- **Q8** AI API Key 存储 —— M7 再定
- **R1** `hive-jdbc` 与 `hadoop-common 3.5.0` 版本冲突 —— M3 spike
- **R2** Impala 走 HS2 的兼容性 —— M3 spike
- 目标环境 Hive / Impala 的发行版与版本 —— 未定，只影响 M3 的驱动取舍
