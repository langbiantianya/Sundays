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
| `shared/.../analysis/AnalysisModels.kt` | 数据源描述（`AnalysisSourceItem`：id / 名称 / 方言标签）、行测试标记、只读徽标标记、`AnalysisColumn` / `AnalysisResult`、**树的模型与纯规则**（`AnalysisTreeKind` / `AnalysisTreeNode` / 节点 id / 按 id 替换 / 结果 → 表格的映射） | ✅ 已落地 |
| `shared/.../analysis/AnalysisWorkspace.kt` | **工作区的纯规则**：工作区文件路径、`staging_<源库>_<表名>` 的拼法与标识符清洗 | ✅ 已落地（第 4 片） |
| `shared/.../analysis/AnalysisPull.kt` | **拉取的纯规则**：三段枚举、进度 / 结果 / 终态模型、staging 列类型、建表与删表语句、导出的 SELECT、每段的文案键与状态判定 | ✅ 已落地（第 5 片） |
| `shared/.../analysis/SourceTree.kt` | 新组件 1/6（主设计 §6.4）：连接 → 库 → 表 的三段树 | ✅ 已落地（第 6 片） |
| `shared/.../analysis/PullStageIndicator.kt` | 新组件 5/6：EXPORT / CSV / IMPORT 三段 + 一行占用 + 终态行 | ✅ 已落地（第 5 片） |
| `shared/.../analysis/ReadOnlyBadge.kt` | 新组件 4/6：`⊘ 只读`，**不可关闭** | ✅ 已落地 |
| `shared/.../analysis/AnalysisScreen.kt` | 分析工作区主屏（顶栏 + 左树 + 中编辑器与结果 + 底栏） | ✅ 已落地（**第 6 片补齐了主区**：编辑器面板 + 只读结果表 + 状态栏 + 「▶ 运行」） |

### 5.2 `:desktopApp` 的状态机

`desktopApp/.../AnalysisSession.kt` —— 与 `ConnectionSession` / `DatabaseBrowserState`
**并列**（不继承、不耦合）：持有远端源的**连接配置**、当前选中源、当前 SQL 与结果、
运行态，以及工作区文件路径。

`desktopApp/.../AnalysisSourceConnector.kt` —— UI 层的 `ConnectionConfig` →
引擎的 `grpc.ConnectionConfig`（与浏览模式的 `engineConnFor` **同口径**）。

`desktopApp/.../AnalysisPuller.kt` —— 拉取链路的**契约**（接口）；
`desktopApp/.../EngineAnalysisPuller.kt` —— 它的**真实现**（引擎 + 临时文件）。

> 📌 **第 5 片的一处实现期决定：拉取器拆成「接口 + 实现」**。
> 真实现要连远端、还要起导出子进程，在 CI 上既慢又不稳；
> 而会话的**终态映射**（拉完了 / 拉挂了 / 拉取消了）是状态机的一部分，
> 必须能**确定性地**各测一遍。所以抽一个接口，测试塞一个立刻返回的假实现。
> 假实现还能把「导出**永远不结束**」钉死 —— 于是「取消发生在导出途中」
> 这件事从**竞态**变成**确定**，「取消时必须叫停导出子进程」那条断言才有判别力。
>
> 📌 **第 5 片的另一处决定：拉取的终态是一个 sealed，而不是三个可空字段**。
> 早先的写法是「结果 + 错误」两个可空字段，取消靠「两个都空」来**推断**；
> 那是推断不是事实 —— 一旦将来多出第四种「什么都没发生」的路径，
> 那两个字面量就再也分不出「取消」与「没开始」。改成
> `PullOutcome`（`Done` / `Cancelled` / `Failed`）之后，三种情况互斥且穷举。

> 📌 **第 4 片的一处实现期决定：源列表**不**进会话**。
> 源列表是「连接列表 × 声明了 `ANALYSIS_SOURCE` 的方言」的**派生结果**；
> 派生数据再存一份，就多一个可能对不上的副本（连接删了、引擎改了能力位声明，
> 存的那份不会自己跟着变）。所以会话只收**派生不出来的那部分** —— 带凭据的连接配置 ——
> 以及派生不出的**状态**：选中项、SQL、结果。
>
> 另一处：**两条会话的只读方向是相反的**，且各只有一个执行点 ——
>
> | 会话 | `read_only` | 执行点 | 弄反了会怎样 |
> |---|---|---|---|
> | 远端源 | **开** | `AnalysisSession.sourceConfig()` | 分析模式成了绕过 L4 的后门 |
> | 本地工作区 | **关** | `AnalysisSession.workspaceConnection()` | 工作区里连 `CREATE TABLE` 都不能做 |
>
> 两条都**不会立刻报错**（工作区带只读只是「建表失败」，远端漏只读只是「还能写」），
> 所以各有断言钉住。

> 📌 **第 6 片的实现期决定：树与展开态都归会话管。**
> 树的内容（已加载的子节点）是**派生数据**（由引擎的 `LIST_SOURCES` / `LIST_TABLES` 查出来），
> 放在界面侧就意味着界面自己管一份树数据 —— 而界面是纯 Compose、拿不到引擎；
> 展开态同理：**会话比界面活得久**（顶层切到「连接管理」再切回来，
> `AnalysisScreen` 会离开组合，而 `AnalysisSession` 是 `remember(engine)` 建的、一直都在）。
> 展开态放界面侧的话，用户「展开树 → 去连个库 → 回来」会发现树又缩回去了 ——
> 那不是「界面没保存」，是**放错了地方**。

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
| `desktopApp/.../AnalysisScreenTest.kt` | 工作区界面：只读徽标可见且**没有可点语义**、数据源树渲染、无数据源时的空态文案、**结果表没有编辑入口**、结果表显示列与行、失败结果显示原文、运行按钮回调且运行中禁用、编辑器里有当前 SQL | ✅ 已落地（第 3 片；第 6 片补 6 例，共 9 例） |
| `desktopApp/.../AnalysisSourceTreeTest.kt` | **树的界面契约**：每行有标记、工作区那一行显示**固定名**、源行带方言标签、点扭子 / 点分组行都回调「展开」、**点表回调「选中」而不是「展开」**、展开后子节点出现、表行**没有扭子**、空树不崩 | ✅ 已落地（第 6 片，8 例） |
| `desktopApp/.../AnalysisTreeSessionTest.kt` | **会话里的树**：工作区永远在第一位、工作区在树上、方言标签、展开工作区真能列出它的表、点两次收起、收起再展开**不丢已加载的子节点**、加载失败**留下原因**而不是变成「空」、连接列表变了**不抹掉已加载的子节点**、删掉的源从树上消失、把表名加进编辑器（空 / 非空两种） | ✅ 已落地（第 6 片，12 例） |
| `desktopApp/.../AnalysisSessionTest.kt` | **状态机**：工作区可写 / 远端源只读（两条方向相反的不变量）、原配置不被就地改写、空语句不启动、源没了选中项被剪枝、DDL 回 -1、SQL 出错收成**失败结果**而不是异常、**拉取的三条终态**（完成 / 取消 / 失败各落各的态） | ✅ 已落地（第 4 片；第 5 片补 4 例，共 14 例） |
| `desktopApp/.../AnalysisWorkspaceIntegrationTest.kt` | **工作区端到端**（真 DuckDB 文件）：能建表、重开连接表还在、目录会被自动建出来、查询能回行与列 | ✅ 已落地（第 4 片） |
| `desktopApp/.../AnalysisPullIntegrationTest.kt` | **导入那一半**端到端（真 DuckDB + 手写带 BOM 的 CSV）：行数对上、空串**不毁行**、重拉替换旧表、表头不匹配时失败且**不留半张表**、列名带空格、进度回调有帧、**失败路径不留临时目录** | ✅ 已落地（第 5 片，7 例） |
| `desktopApp/.../AnalysisPullSmokeTest.kt` | **整条链路**端到端（真 MySQL → CSV → 真 DuckDB）：行数对上、三段进度走全、临时目录清空、窗口函数聚合、`CREATE TABLE tmp AS SELECT`、**取消路径不留临时目录** | ✅ 已落地（第 5 片，5 例；MySQL 不可达时显式 skip） |
| `desktopApp/.../AnalysisPullCancelTest.kt` | **取消必须叫停导出子进程**（假引擎把导出钉成「永远不结束」，时机确定）：按**导出请求的 id** 叫停 + 取消路径清干净临时目录 | ✅ 已落地（第 5 片，2 例） |
| `desktopApp/.../AnalysisPullIndicatorTest.kt` | **拉取进度的界面契约**：三段都在、当前段被标出来（按段寻址）、行数与占用、没有文件时不报大小、取消按钮回调、完成 / 取消 / 失败三种终态各说各的话、进行中**不显示**上一次的终态 | ✅ 已落地（第 5 片，9 例） |
| `shared/.../commonTest/analysis/AnalysisWorkspaceTest.kt` | 工作区纯规则：staging 表名、标识符清洗（引号 / 分号不得混入）、工作区文件路径 | ✅ 已落地（第 4 片，10 例） |
| `shared/.../commonTest/analysis/AnalysisPullTest.kt` | 拉取纯规则：列名**引用**、引号转义、列一律 VARCHAR、删表 `IF EXISTS`、方言各自的引用形式、命名空间为空时退化、三段的状态判定、每段各有各的文案键 | ✅ 已落地（第 5 片，11 例） |
| `shared/.../commonTest/analysis/AnalysisTreeTest.kt` | **树的纯规则**：同名表在不同源下 id 不同、命名空间与表的 id 不撞、只有表是叶子、按 id 找到深处的节点、**按 id 替换**只换该换的那一处、找不到就原样返回、结果列的键**按下标**（重复列名不塌）、列与行用**同一套键**、NULL 仍是 NULL | ✅ 已落地（第 6 片，15 例） |

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
>
> ✅ **第 5 片再补 3 个键**（六语言同步，共 27 个）：
> `ANALYSIS_PULL_CANCEL`（取消拉取）、`ANALYSIS_PULL_ROWS`（已处理 {0} 行）、
> `ANALYSIS_PULL_SIZE`（临时文件 {0}）。
> 这三个是**界面件 `PullStageIndicator` 落地时才发现缺**的：
> 「取消拉取」没有按钮就点不着，「已处理 N 行」与「临时文件 X」是 §6.5 那两行占用。
> 补键的规矩不变 —— **六语言一个都不能少**（`StringsIntegrityTest` 盯着）。

> ✅ **第 6 片再补 9 个键**（六语言同步，共 **36** 个）：
> `ANALYSIS_RUN`（运行）、`ANALYSIS_EDITOR_TITLE`（SQL 编辑器）、
> `ANALYSIS_RESULT_EMPTY`（还没有结果）、`ANALYSIS_RESULT_FAILED`（查询失败）、
> `ANALYSIS_ROW_COUNT`（行数 {0}）、`ANALYSIS_ELAPSED`（耗时 {0} ms）、
> `ANALYSIS_ENGINE`（引擎 {0}）、`ANALYSIS_TREE_LOADING`（加载中…）、
> `ANALYSIS_TREE_ERROR`（加载失败：{0}）。
> 这九个是**工作台主区接上引擎时才发现缺**的 ——
> 主区一落地，标题 / 空态 / 状态栏 / 树的三种态就都要文案。
>
> ⚠️ **第 6 片还改了一个既有键的占位符**：
> `ANALYSIS_RESULT_TRUNCATED` 从「结果超过上限 {0} 行，只显示前 {1} 行」
> 改成「结果超过显示上限，只显示前 {0} 行」——
> **少一个占位符**。原因是上限那个数字前端**拿不到**：
> 引擎的 `AnalysisRunQueryResponse` 只回 `truncated` 标志，不回上限，
> 而前端硬抄一份 5000 就是**两份真相源**（引擎改宽了，前端还在说 5000）。
> 截断时 `rows.size` **就是**上限（引擎正好停在上限那一行），
> 所以一个占位符足够，而且它顺带把上限告诉了用户。

---

## 6. 工作区与拉取

### 6.1 工作区

- **形态**：一个 DuckDB **文件**，放在**应用配置目录**下（`~/.config/sundays/workspace.duckdb`）。
  **不是**用户连接——主设计 §2.2 读法 B：「DuckDB 是分析模式自带的计算引擎，
  默认且常驻」；读法 A（用户自己建 DuckDB 连接）被明确否掉了。
  放在配置目录（与 `connection.json` 同处）而不是另立一套目录规则 ——
  「应用的文件都在哪」多一个答案，就意味着清理 / 备份 / 迁移要记得两处。
- **生命周期**：跨会话常驻（文件复用），退出时正常关闭。
- **目录要自己建**：DuckDB 建库时**不会**自动创建上级目录 —— 目录不存在就直接失败。
  而用户第一次打开分析模式时，那个目录**一定**不存在。
  所以每次用工作区之前先 `ensureWorkspaceDir`（`AnalysisSession` 的伴生函数）。
- **staging 表名**：`staging_<源库>_<表名>`（源库与表名都做标识符清洗）。
  带源库前缀是必要的——不同源可能有同名表。
  清洗是**有损**的：`a-b` 与 `a_b` 会撞成同一个名字；这是刻意的取舍 ——
  撞名时用户看到的是两个同名的 staging 表，而**不是**一条把别的库的表覆盖掉的查询。

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

`finally` 里跑在 `withContext(NonCancellable)` 中——不这么做的话，
取消时协程已经是「取消中」态，里面的挂起调用会被立刻取消，清理根本跑不完。

**删除带 5 次重试**（每次间隔 200 ms）：取消时导出子进程可能**还没来得及**
关掉文件句柄，而 Windows 上文件被占用时删不掉。删一次失败就放弃，
留下的正是「取消之后仍然占着磁盘」的那个文件 —— 恰恰是这条验收要防的。

### 6.3.1 取消时必须**先叫停导出子进程**（实测抓出来的）

导出跑在**另一个进程**里，它正开着那个临时 CSV。Windows 上文件被占用时删不掉，
所以取消时的顺序必须是：**先叫停子进程（它放开文件）→ 再删目录**。

叫停走引擎既有的 `EXPORT.RUN_EXPORT` 停止分支（`stop_export_id`），
而 `stop_export_id` 要带的正是**导出请求自己的 id**。

> ⚠️ **这里的坑**：导出请求 id **不能在导出返回之后才记**。
> 取消发生在导出**途中**时，导出根本不会返回，于是「叫停」那一步永远拿不到 id ——
> 子进程继续开着 CSV，磁盘上留下一个删不掉的孤儿目录。
>
> 最初的写法把 id 记在 `exportToCsv` 的**返回值**里，实测确实抓到了残留
> （一个装着 `pull.csv` 的临时目录）。现在的写法：**id 在导出开始之前就定下来**，
> `finally` 里一定拿得到。

### 6.4 staging 表的列一律 `VARCHAR`

看着「把远端的 `INT` 映成 `INTEGER`」更贴心，但那条路会**整行丢数据**，实测如下：

1. CSV 导出把 **NULL 写成空串**（`CsvWriter.writeRow`：`it?.toString() ?: ""`）；
2. 导入侧按**字符串**写值（`ImportHandler.bindRow`：`stmt.setString`）；
3. 而 `INSERT INTO t (a) VALUES ('')` 在 `a INTEGER` 上**会失败** —— 空串转不成整数。

于是：源表里只要有一列可空且是数值，**每一行**都会导入失败。
打开 `ignore_errors` 也救不了 —— 失败的是**整行**，那一行的其它列也跟着没了。

staging 因此是**落地层**（数据仓库的惯例：原始层存文本，类型由下游建模决定）。
要按数值聚合，在工作区里建一张**建模表**即可 ——
`CREATE TABLE orders AS SELECT TRY_CAST(id AS BIGINT) AS id, … FROM staging_shop_orders`。
`TRY_CAST`（而不是 `CAST`）让「转不动」的值落成 NULL 而不是让整条查询失败。

> 实测补充：`sum()` 与窗口函数在 `VARCHAR` 上**不工作**（探针 PROBE-3/4 失败），
> 而 `TIMESTAMP` / `DATE` / `BOOLEAN` / `DECIMAL(38,10)` 接受字符串。
> 所以「一切皆 VARCHAR」是**对的方向**，但**必须配 `TRY_CAST`** 才能做聚合。

### 6.5 预计占用

拉取面板显示两行：CSV 文件大小（① 完成后可知）+ 行数（③ 的进度帧给出）。

大小在 ① 结束之前**没有值** —— 那时显示「临时文件 0 B」是**假信息**
（用户会以为文件已经建好却只有 0 字节），所以那之前**不显示**这一项。

### 6.6 数据源树（第 6 片）

### 6.6.1 树的形状：**根 → 命名空间 → 表**，工作区少一层

```
本地工作区 (DuckDB)          ← 工作区：恒在，且在第一位
  orders                     ← 工作区的子节点直接是表
hive_prod (MySQL)
  shop                       ← 命名空间（MySQL 的库 / PG 的 schema）
    orders                   ← 表
```

**工作区这一层不摆命名空间**：远端源的命名空间是**用户必须分辨**的东西
（MySQL 的库、PG 的 schema），那一层一定要摆出来；而工作区是应用自带的
DuckDB 文件，它的 schema 实际上只有 `main` 一个 —— 把它摆在每张表前面，
只会给每张表多加一个没有信息量的中间层。

> ⚠️ **代价**：用户在 DuckDB 里**自建**的其它 schema 不会出现在树里。
> 那是本轮的取舍，不是遗漏 —— 拉取进来的 staging 表全在 `main`，
> 而 `main` 正是「工作区里能查到的表」的集合。

工作区那一行的**名字是固定的**（「本地工作区」是应用自带的引擎，不是用户起名的），
由界面按 `StrKey.ANALYSIS_LOCAL_WORKSPACE` 取 —— 会话不传字符串，
也就传不错语言。

### 6.6.2 节点 id 与「按 id 替换」

节点 id 的约定：`workspace` / 源 id / `ns:<属主>:<命名空间>` /
`table:<属主>:<命名空间>:<表名>`。

展开一个节点时**只换掉那一个节点**（把它的子节点填上），其余原样保留 ——
用户的展开态与滚动位置都挂在这棵树上。整棵树重建会把「谁展开了」从树上抹掉。
所以 `replaceAnalysisNode(roots, id) { … }` 是**按 id 替换**，找不到就**原样返回**
（凭空造节点会让树里出现幽灵行）。

### 6.6.3 展开是**懒加载**的

子节点在**点开的时候**才去查 —— 不是启动时把每个源的每个库都列一遍。
后者意味着「打开分析模式」要先连一遍所有远端，用户没点任何东西就已经
等在了网络 I/O 上（而远端可能压根连不上）。

加载失败**不算**「这个节点是空的」：保持 `loaded = false`（用户再点一次就能重试），
错误原文摆在树脚下。把失败当成空列表，用户会以为是自己连错了库。

### 6.6.4 拉取要**显式**传源 id

`AnalysisSession.pull(sourceId, namespace, table)` —— 源 id 是**参数**，不是
「当前选中的源」。早先的写法用 `selectedSourceId`（工具条下拉框里的那个），
但树上点的是**某一张具体的表** —— 它属于哪个源是那棵树自己知道的。
两者不一致时，「拉 A 源里的表」会变成「拉 B 源里的同名表」——
而后者**不会报错**（只要 B 源里恰好有那张表），只是数据悄悄换了来源。

### 6.6.5 点工作区的表 → 把表名加进编辑器

- 编辑器**是空的** → 放一条能直接跑的语句（`SELECT * FROM "orders"`）；
- 编辑器**有内容** → 在末尾追加一行**引用的表名**（`"orders"`）。

**为什么不插到光标处**：光标位置住在编辑器的内部状态（`CodeEditorState`）里，
那是**界面**的东西；会话只拿着一个纯字符串。在会话里插光标位置就得把编辑器
状态也搬进会话 —— 而那会把「一段文本」与「一个编辑器控件」耦在一起。
追加到末尾虽然不一定是用户想要的位置，但它是**可预期**的。

### 6.6.6 结果表**只读**的判据是**结构**的

`onCellEdit = null` + `cellEditable = false`。判据用
「可编辑的单元格才会有的标记」（`sundays.cell.<列键>`）：

> ⚠️ **不能**写成「点一下单元格看有没有冒出输入框」—— 那是**假绿**：
> 实测过，把结果表改成可编辑后，点单元格**照样**不冒输入框
> （编辑手势挂在单元格的 `Box` 上，而 `Text` 自己不收指针事件），
> 于是那条断言无论只读还是可编辑都恒绿 —— 绿得像模像样，却什么都没守住。
> 一个测不出来的断言等于没有断言。


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

### 7.4 工作台端到端（`AnalysisPullSmokeTest` / `AnalysisPullIntegrationTest` / `AnalysisPullCancelTest`）

- 真 MySQL（`sundays_smoke`）拉一张表进 DuckDB → `staging_<库>_<表>` 存在、**行数对得上**
- 临时 CSV 在成功 / 失败 / 取消三条路径上都被清理
- 取消时按**导出请求的 id** 叫停导出子进程（假引擎把导出钉成「永远不结束」，时机确定）
- 工作区里跑一条窗口函数聚合 → 结果正确
- 工作区里 `CREATE TABLE tmp AS SELECT …` **成功**（本地写允许）

### 7.5 界面（`AnalysisScreenTest` / `AnalysisPullIndicatorTest` / `AnalysisSourceTreeTest`）

- 「⊘ 只读」徽标**没有关闭入口**
- 拉取三段各自标出「已完成 / 进行中 / 待执行」（按**段**寻址，不看颜色）
- 没有文件时不报大小（不显示「临时文件 0 B」）
- 完成 / 取消 / 失败三种终态各说各的话；**取消不是失败**
- 进行中**不显示**上一次的终态
- **结果表没有编辑入口**（`onCellEdit = null` + `cellEditable = false`）——
  判据用「可编辑的单元格才有的标记」，**不是**「点一下看有没有输入框」（§6.6.6）
- 结果表显示**列名与行值**；查询失败时显示**错误原文**而不是空表
- 「▶ 运行」点了会回调；**正在跑时禁用**（连点两下就是两条并发查询，
  而工作区是**一个** DuckDB 文件，后一条会把前一条的结果盖掉）
- 编辑器里能看到当前 SQL
- 树：每行有标记、工作区那一行显示**固定名**、源行带方言标签、
  **点表走「选中」而不是「展开」**、展开后子节点出现、表行**没有扭子**

---

## 8. 提交切分

每次提交都能编译、能跑、能看见。**代码与文档同提交**（仓库惯例）。

| # | 提交 | 内容 | 验收 | 状态 |
|---|---|---|---|---|
| 1 | `feat(protocol): 新增 ANALYSIS / REPORT 类别、五条分析动作、连接级只读开关` | proto（`Category.ANALYSIS` / `REPORT`、5 个 Action、`read_only`、`AnalysisRequest` / `AnalysisResponse`）+ Kotlin 映射 | 编译通过；`engine-protocol` 的映射测试全绿 | ✅ **已提交** |
| 2 | `feat(engine): 只读闸门 —— 会话级 L4 + 方言级 L3，配两级回归测试` | dispatcher 闸门 + `ReadOnlySessionGateTest` + `ReadOnlyDialectTest` | §7.1 / §7.2 全绿，且**变异验证**：去掉闸门后测试变红 | ✅ **已提交** |
| 3 | `feat(analysis): 分析模式入口 —— 四条只读路由 + 顶层导航 + 不可关闭的只读徽标` | `AnalysisHandler` + 四条路由注册 + `AppDestination.ANALYSIS` + `TopNavBar` + `ReadOnlyBadge` + 主屏骨架 + 能力位声明 + i18n 六语言 | 界面能进；徽标不可关；`AnalysisHandlerIntegrationTest` / `AnalysisCapabilityContractTest` / `AnalysisModuleBoundaryTest` / `AnalysisScreenTest` 全绿 | ✅ **本轮落地**（提交标题按此风格） |
| 4 | `feat: 分析工作台 —— DuckDB 本地工作区与状态机` | `AnalysisSession` + 工作区文件管理 + staging 表名 | 工作区能建表、能跑 SQL | ✅ **本轮落地** |
| 5 | `feat: 拉取链路 —— 远端 EXPORT 到工作区 IMPORT` | 三段编排 + `PullStageIndicator` + 临时 CSV 清理 | §7.4 全绿 | ✅ **本轮落地** |
| 6 | `feat: 分析工作区界面 —— SourceTree 与只读结果表` | `SourceTree` + 结果表只读 + 编辑器接通 | §7.5 全绿；端到端可截图 | ✅ **本轮落地** |

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
>
> 📌 **第 4 片的实际落点比原文多两处**：
> 一是**工作区文件放在配置目录**（`~/.config/sundays/workspace.duckdb`）而不是另立目录规则 ——
> 原文只说「应用数据目录」，实现时按「应用的文件都在哪只该有一个答案」收敛到既有目录；
> 二是**源列表不进会话**（§5.2）—— 源列表是派生数据，存一份就多一个可能对不上的副本。
>
> ✅ **第 4 片的变异验证（三处，各被抓住）**：
>
> | 变异 | 变红的用例 |
> |---|---|
> | 工作区连接标上 `read_only = true` | `the workspace connection is writable` |
> | 远端源不再强制 `read_only = true` | `the remote source session is read only` |
> | 去掉 `ensureWorkspaceDir` 调用 | `running a statement…` / `a select returns…` / `the workspace directory is created on demand` |
>
> 📌 **第 5 片的实际落点比原文多三处**：
> 一是**拉取器拆成「接口 + 实现」**（§5.2）—— 真实现要连远端、起导出子进程，
> 而会话的终态映射必须能**确定性地**测；假实现还能把导出钉成「永远不结束」，
> 于是「取消发生在导出途中」从竞态变成确定；
> 二是**拉取终态是 sealed**（`Done` / `Cancelled` / `Failed`），不是三个可空字段；
> 三是**导出请求 id 在导出开始之前就定下来**（§6.3.1）—— 这是实测抓出来的真 bug。
>
> ✅ **第 5 片的变异验证（四处，各被抓住）**：
>
> | 变异 | 变红的用例 |
> |---|---|
> | 三段的状态判定把「进行中」与「待执行」对调 | `AnalysisPullTest` 三例 + `AnalysisPullIndicatorTest` 的 `the indicator shows the three stages…` |
> | 取消被记成**失败**（`CancellationException` 落进 `Failed`） | `a cancelled pull lands in the cancelled outcome and not in the failed one` |
> | 去掉临时目录的删除（`deleteTempDir` 变成空操作） | `the temp csv is gone after a successful pull` / `a failed pull leaves no temp directory behind` / `a cancelled pull leaves no temp directory behind` |
> | 导出请求 id 只在导出**返回之后**才记（= 最初的 bug） | `a cancelled pull stops the export subprocess by the export request id` |
>
> 📌 **最后一条为什么值得单独记**：前三个变异是「设计上就该防住」的；
> 第四个是**实测才发现的真 bug** —— 最初的写法把导出请求 id 记在 `exportToCsv` 的
> **返回值**里，而取消发生在导出**途中**时那个返回值根本不会来，
> 于是「叫停子进程」被跳过，Windows 上留下一个装着 `pull.csv` 的孤儿目录。
> 它是被 `AnalysisPullSmokeTest` 的「取消路径不留临时目录」抓到的。
>
> ⚠️ 而那条冒烟测试本身**判别力不够**：3 行的表在两种写法下都能把目录清干净
> （导出在取消到达之前就结束了）。所以补了 `AnalysisPullCancelTest` ——
> 用假引擎把导出钉成「永远不结束」，「取消发生在导出途中」变成**确定**的事实。
> **一个测不出来的断言等于没有断言**：判别力不足的测试要主动补强，不能算数。

> 📌 **第 6 片的实际落点比原文多四处**：
> 一是**工作区那一层不摆命名空间**（§6.6.1）—— DuckDB 的工作区只有一个
> `main` schema，把它摆出来只会在每张表前面多插一个没有信息量的节点；
> 二是**拉取要显式传源 id**（§6.6.4）—— 用「当前选中的源」会拉错库的同名表，
> 而那**不会报错**，只是数据悄悄换了来源；
> 三是**树与展开态都归会话管**（§5.2）—— 会话比界面活得久，
> 展开态放界面侧的话，用户切走再回来会发现树缩回去了；
> 四是**结果表的只读判据是结构的**（§6.6.6）—— 见下。
>
> ✅ **第 6 片的变异验证（五处，各被抓住）**：
>
> | 变异 | 变红的用例 |
> |---|---|
> | 结果表改成**可编辑**（`onCellEdit = { }` + `cellEditable = true`） | `the result table has no editing entry` |
> | 结果列的键从**下标**改成**列名** | `the result columns are keyed by their index not by their name` + `the columns and the rows agree on the keys` |
> | `replaceAnalysisNode` 变成**空操作**（不替换） | `AnalysisTreeTest` 两例 + `AnalysisTreeSessionTest` 三例（展开工作区 / 收起再展开 / 刷新源后仍留着子节点） |
> | 树加载失败时把 `treeError` **吞掉** | `a failing load reports the reason instead of pretending the node is empty` |
> | `appendToSql` 在空编辑器时**不**给一条能跑的语句 | `appending a table name to an empty editor seeds a runnable statement` + `an appended table name is quoted` |
>
> 📌 **第 6 片抓出的一条「假绿」**：结果表只读这件事，最自然的写法是
> 「点一下单元格，看有没有冒出输入框」。实测发现那条断言**抓不住任何东西** ——
> 把结果表改成可编辑之后，点单元格**照样**不冒输入框
> （编辑手势挂在单元格的 `Box` 上，而 `Text` 自己不收指针事件）。
> 于是它无论只读还是可编辑都恒绿 —— 绿得像模像样，却什么都没守住。
> 换成**结构判据**（「可编辑的单元格才有的标记」不存在）之后才真正有判别力。
> **一个测不出来的断言等于没有断言**：宁可换判据，也不能留着一条恒绿的。

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
| 拉取把远端磁盘写满 | CSV 落临时目录 + 面板显示占用（§6.5）；三段路径都清理（§6.3） |
| 取消拉取后临时目录删不掉（Windows 上文件被占用） | 取消时**先叫停导出子进程**再删目录（§6.3.1），删除带 5 次重试 |
| MySQL / PG 进分析模式削弱了安全性 | **这正是 L4 存在的理由**；且 §3.6 的两类回归测试是它的守门人 |
| 能力位在前后端两处各写一份 | 前端用引擎 `LIST_DRIVERS` 下发的 `DialectInfo.capabilities`，**不另立真相源** |

---

## 10. 待定（沿用主设计 §13）

- **Q8** AI API Key 存储 —— M7 再定
- **R1** `hive-jdbc` 与 `hadoop-common 3.5.0` 版本冲突 —— M3 spike
- **R2** Impala 走 HS2 的兼容性 —— M3 spike
- 目标环境 Hive / Impala 的发行版与版本 —— 未定，只影响 M3 的驱动取舍
