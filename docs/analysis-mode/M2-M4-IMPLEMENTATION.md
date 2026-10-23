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
| `shared/.../analysis/AnalysisPull.kt` | **拉取的纯规则**：进度（已读行数）、结果、终态模型（`PullOutcome` 是 sealed）、用户取消的专用异常 | ✅ 已落地（第 5 片；**第 7 片重写**） |
| `shared/.../analysis/SourceTree.kt` | 新组件 1/6（主设计 §6.4）：连接 → 库 → 表 的三段树 | ✅ 已落地（第 6 片） |
| `shared/.../analysis/PullProgressIndicator.kt` | 拉取进度指示器（「正在拉取 · 已读 N 行」+ 取消按钮）+ 终态行 | ✅ 已落地（第 5 片；**第 7 片重写** —— 不再有「阶段」与「临时文件」） |
| `shared/.../analysis/ReadOnlyBadge.kt` | 新组件 4/6：`⊘ 只读`，**不可关闭** | ✅ 已落地 |
| `shared/.../analysis/AnalysisScreen.kt` | 分析工作区主屏（工具条 + 左树 + 中编辑器与结果 + 底栏） | ✅ 已落地（**第 6 片补齐了主区**：编辑器面板 + 只读结果表 + 状态栏 + 「▶ 运行」；**第 9 片**去掉「数据源」下拉框；**第 10 片**加返回按钮） |

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
| `engine/.../analysis/AnalysisHandler.kt` | `ANALYSIS` 五个路由的实现（含 `executePull` —— 联邦直查的编排） |
| `engine/.../analysis/JdbcScanTableFunction.kt` | **纯 Java 表函数**：把一条 JDBC 查询变成 DuckDB 里的一张表（类型映射 + 单线程 + 分块写出）。**无状态** —— 每次扫描的上下文由句柄给出（§6.2.4） |
| `engine/.../analysis/JdbcScanRegistry.kt` | 表函数的**幂等注册**（固定名 `jdbc_scan` + 查重 + 并发安全）与**扫描句柄表**（句柄 → 本次扫描的上下文） |
| `engine/.../analysis/NativeExtensionScanner.kt` | **可选加速**：原生扫描器的挂载 / 建表 / 摘除三段式（§6.2.7）。默认**关** |
| `engine/.../dispatcher/RequestDispatcher.kt` | **改动**：L4 闸门 + 五条新路由（`PULL_TABLE` 走流式路径） |
| `engine/.../handlers/ExportHandler.kt` / `ImportHandler.kt` | **零改动**（浏览模式的导出 / 导入还在用；分析模式的拉取不再走它们） |

### 5.4 测试

| 文件 | 内容 | 状态 |
|---|---|---|
| `engine/.../integration/ReadOnlySessionGateTest.kt` | §3.6 的**会话级**回归测试 | ✅ 已落地 |
| `engine/.../integration/ReadOnlyDialectTest.kt` | §3.6 的**方言级**回归测试 | ✅ 已落地 |
| `engine/.../integration/AnalysisHandlerIntegrationTest.kt` | 四条路由的集成测试：命名空间分派（schema 型 / 库型）/ 行数截断 / 远端方言被拒 / 只读会话里照常可列 | ✅ 已落地 |
| `engine/.../analysis/AnalysisPullFederationTest.kt` | **联邦直查的端到端**（真 H2 远端 → 真 DuckDB 工作区）：表真的落进工作区 / 值一字不差 / 失败**不留半张表** / 失败的重新拉取**保住旧表**（事务回滚）/ 缺参数被拒 / **同一张表拉两次**（第 8 片：表函数必须无状态） | ✅ 已落地（第 7 片 5 例 + 第 8 片 1 例，共 6 例） |
| `engine/.../analysis/JdbcScanTypeMappingTest.kt` | **类型映射**：整数按宽度选（无符号 32 位落到 64 位、无符号 64 位落到 `DECIMAL(20,0)`）、超范围的 DECIMAL 落成文本、未知精度落成文本、每种 kind 都有对应的 DuckDB 类型 | ✅ 已落地（第 7 片，8 例） |
| `engine/.../analysis/NativeExtensionScannerTest.kt` | **原生扫描器的纯规则**：默认关、方言 → 扫描器映射、MySQL 的 URI 不带库、PG 的 URI 带库、凭据百分号编码、扩展目录（显式配置以配置为准） | ✅ 已落地（第 8 片，8 例） |
| `engine/.../analysis/AnalysisPullNativeFallbackTest.kt` | **降级**（H2 没有扫描器 → 原生那条路确定走不通）：拉取照样成功、类型是**我们的**映射、开关不改变结果 | ✅ 已落地（第 8 片，2 例） |
| `engine/.../testutil/RemoteLikeDialect.kt` | 假方言：委托 H2 但摘掉 `EMBEDDED_MODE`、`supportsSchema = false` —— 让「远端被拒」这条边界在**不连任何数据库**的前提下可断言 | ✅ 已落地 |
| `desktopApp/.../AnalysisCapabilityContractTest.kt` | 能力位契约：MySQL / PG 声明 `ANALYSIS_SOURCE`，H2 / DuckDB / SQLite 不声明，能力位以**枚举名**下发 | ✅ 已落地 |
| `desktopApp/.../AnalysisModuleBoundaryTest.kt` | 主设计 §6.6 的结构测试（不 import `java.*` / `javax.*`、不 import 引擎与协议层、不出现字面量颜色）—— 落在 `desktopApp`：它读 `:shared` 的**源文件**，而 Gradle 跑测试时的工作目录是**模块根** | ✅ 已落地 |
| `desktopApp/.../AnalysisScreenTest.kt` | 工作区界面：只读徽标可见且**没有可点语义**、数据源树渲染、**源只在树里选**（工具条没有「数据源」下拉框 —— 源的名字在屏幕上只出现一次）、无数据源时的空态文案、**结果表没有编辑入口**、结果表显示列与行、失败结果显示原文、运行按钮回调且运行中禁用、编辑器里有当前 SQL | ✅ 已落地（第 3 片；第 6 片补 6 例；第 9 片补 1 例，共 10 例） |
| `desktopApp/.../AnalysisSourceTreeTest.kt` | **树的界面契约**：每行有标记、工作区那一行显示**固定名**、源行带方言标签、点扭子 / 点分组行都回调「展开」、**点表回调「选中」而不是「展开」**、展开后子节点出现、表行**没有扭子**、空树不崩 | ✅ 已落地（第 6 片，8 例） |
| `desktopApp/.../AnalysisTreeSessionTest.kt` | **会话里的树**：工作区永远在第一位、工作区在树上、方言标签、展开工作区真能列出它的表、点两次收起、收起再展开**不丢已加载的子节点**、加载失败**留下原因**而不是变成「空」、连接列表变了**不抹掉已加载的子节点**、删掉的源从树上消失、把表名加进编辑器（空 / 非空两种） | ✅ 已落地（第 6 片，12 例） |
| `desktopApp/.../AnalysisSessionTest.kt` | **状态机**：工作区可写 / 远端源只读（两条方向相反的不变量）、原配置不被就地改写、空语句不启动、DDL 回 -1、SQL 出错收成**失败结果**而不是异常、**拉取的三条终态**（完成 / 取消 / 失败各落各的态） | ✅ 已落地（第 4 片；第 5 片补 4 例；第 9 片删 1 例，共 13 例） |
| `desktopApp/.../AnalysisWorkspaceIntegrationTest.kt` | **工作区端到端**（真 DuckDB 文件）：能建表、重开连接表还在、目录会被自动建出来、查询能回行与列 | ✅ 已落地（第 4 片） |
| `desktopApp/.../AnalysisPullIntegrationTest.kt` | **整条链路**端到端（前端拉取器 → 真引擎 → 联邦扫描 → 真 DuckDB 工作区）：表真的落进工作区、进度帧真的到得了调用方、失败**不留半张表**、**拉取全程不落盘**、用户取消被报成「取消」而不是「失败」 | ✅ 已落地（第 7 片重写，5 例） |
| `desktopApp/.../AnalysisPullSmokeTest.kt` | **真 MySQL** 端到端：行数对上、**全程不落盘**、进度报出行数、**拉下来的表保留远端类型**（`SUM(id)` 直接能跑）、窗口函数聚合、`CREATE TABLE tmp AS SELECT` | ✅ 已落地（第 7 片重写，6 例；MySQL 不可达时显式 skip） |
| `desktopApp/.../AnalysisPullIndicatorTest.kt` | **拉取进度的界面契约**：「正在拉取 · 已读 N 行」、**不再出现「临时文件」**、取消按钮回调、没有取消回调时不画按钮、完成 / 取消 / 失败三种终态各说各的话、进行中**不显示**上一次的终态 | ✅ 已落地（第 7 片重写，9 例） |
| `shared/.../commonTest/analysis/AnalysisWorkspaceTest.kt` | 工作区纯规则：staging 表名、标识符清洗（引号 / 分号不得混入）、工作区文件路径 | ✅ 已落地（第 4 片，10 例） |
| `shared/.../commonTest/analysis/AnalysisPullTest.kt` | 拉取纯模型：进度只有一个数、结果说清落在哪张表与多少行、**终态三选一**、用户取消是**自己的异常**（不是协程取消） | ✅ 已落地（第 7 片重写，5 例） |
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
  `ANALYSIS_PULL_CANCELLED` / `ANALYSIS_PULL_FAILED`、`ANALYSIS_STAGING_TABLE`
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
> `ANALYSIS_PULL_CANCEL`（取消拉取）、`ANALYSIS_PULL_ROWS`（已读 {0} 行）、
> `ANALYSIS_PULL_SIZE`（临时文件 {0}）。
> 这三个是**界面件 `PullStageIndicator` 落地时才发现缺**的：
> 「取消拉取」没有按钮就点不着，「已读 N 行」与「临时文件 X」是那两行占用。
> 补键的规矩不变 —— **六语言一个都不能少**（`StringsIntegrityTest` 盯着）。
>
> ⚠️ **第 7 片把其中两个键删了**（随机制一起消失，见下）：
> `ANALYSIS_PULL_STAGE_EXPORT` / `_CSV` / `_IMPORT`（三段）与
> `ANALYSIS_PULL_SIZE`（临时文件）—— **联邦直查没有阶段，也没有临时文件**。
> `ANALYSIS_PULL_ROWS` 的文案也从「已处理 {0} 行」改成「已读 {0} 行」
> （联邦直查里那行数是**从远端读出来的**，还没落表）。
> 删除的键同样**六语言一起删** —— 少一个语言就留下一条悬空的文案。

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

### 6.2 拉取链路（DuckDB **联邦直查**，零 CSV 中转）

```
ANALYSIS.PULL_TABLE   远端 JDBC 源 →（DuckDB 联邦直查）→ 工作区表
```

引擎侧就一句话：

```sql
CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>()
```

**没有导出、没有 CSV、没有临时文件。**

#### 6.2.1 为什么换掉了「导出 CSV → 导入 CSV」

初稿（v2.29 第 5 片）走的是「远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`」。
那条路要落一次盘、经一次 CSV 的编码往返，还踩过两个坑：

- CSV 把 **NULL 写成空串** → 导入端 `INSERT INTO t (a) VALUES ('')` 在 `a INTEGER`
  上**整行失败**（所以那时的 staging 列一律 `VARCHAR`）；
- 临时文件要在**成功 / 失败 / 取消**三条路径上都被清理 —— 漏一条就是
  「用户拉了一半就关窗口，磁盘上留下几百 MB」。

而 DuckDB 自己就能当**联邦查询层**：它能直接把远端的行读进来。
于是那一整套（临时目录、CSV 编码往返、清理重试、`VARCHAR` 落地层）
**整体消失** —— 不是「做得更小心」，而是**不再需要**。

#### 6.2.2 为什么用**纯 Java 表函数**，而不是 DuckDB 的原生扩展

DuckDB 自带 `mysql_scanner` / `postgres_scanner`，
`ATTACH ... (TYPE mysql_scanner, READ_ONLY)` 就能把远端挂进来 ——
走原生那条路，数据从远端**直接**进 DuckDB，少一次拷贝
（纯 Java 那条是「远端 → Java → DuckDB」）。

> ⚠️ 初稿这里写的是「那些扩展要**联网下载**（`INSTALL mysql`），内网装不上」——
> **那是错的**（第 8 片实测推翻）：DuckDB JDBC 1.5.5.1 把这两个扫描器
> **静态链接**进了驱动本体（官方叫 core extension）：
>
> ```
> SELECT extension_name, installed_from, install_path FROM duckdb_extensions();
> -- mysql_scanner | core | <extension_directory>\v1.5.5\windows_amd64\mysql_scanner.duckdb_extension
> ```
>
> `installed_from = core` 意味着它**不来自磁盘上的文件** ——
> 把 `extension_directory` 与 `extension_directories` 全指到一个**空目录**，
> `LOAD mysql_scanner` 照样成功（实测）。
> 所以对 JDBC 驱动来说，**不需要额外安装**。

**真正的理由是覆盖面**：原生扫描器只覆盖 MySQL / PostgreSQL，
而纯 Java 表函数只要求「有 JDBC 驱动」—— SQLite、H2，
将来的 Hive（只要 `hive-jdbc` 在手上）都能走同一条路。

原生那条路作为**可选加速**另开一扇门（见 §6.2.6），但**默认**走纯 Java ——
因为它的类型映射是**我们自己定的**，跨机器一致。

#### 6.2.3 表函数的五条实测结论

写之前先在真驱动上验过五件事（结论直接决定了实现形状）：

| 问题 | 实测结论 | 对实现的影响 |
|---|---|---|
| 表函数**跨连接**可见吗？ | **可见** —— 同一 `.duckdb` 文件的**另一条连接**照样能查到它 | 注册一次就够，不用逐条连接注册 |
| 它会**落盘**吗？ | **不会** —— `.duckdb` 文件里既没有函数名，也没有闭包里的凭据 | 凭据只在内存里，不进文件 |
| 实例没了它还在吗？ | **不在** —— 它活在 DuckDB **实例**上 | 每次拉取前都要「确保已注册」（幂等） |
| 同一个名字能注册两次吗？ | **不能** | 查重后再注册，并发撞名要能吞掉 |
| 它能撑过**两次拉取**吗？ | **不能** —— 注册进去的函数**活得比一次拉取久**，闭包里的连接第一次拉完就归还连接池了 | 注册进去的函数必须**无状态**（见 §6.2.4） |

#### 6.2.4 注册进去的表函数必须**无状态**

第 7 片把「连接 + SQL + 进度回调」直接塞进了注册的闭包里，
于是**同一张表拉第二次必失败**（`Connection is closed`）——
症状极具迷惑性：第一次好好的，第二次必失败，
而失败信息指向「连接」，与「拉了两次同一张表」毫无关系。

第 8 片改成：**每次拉取现给一份上下文**（连接 / SQL / 进度回调），
用一个**句柄**（字符串）指出来，句柄作为 **SQL 参数**传进表函数：

```sql
CREATE TABLE <落点> AS SELECT * FROM jdbc_scan('scan-42')
```

表函数只在**绑定那一刻**按句柄去取上下文（绑定数据里存的是那一批的具体东西）。
于是：

- 注册进去的函数**无状态** → 实例的目录里永远只有一条 `jdbc_scan`，
  不会随着拉的次数堆积；
- 凭据**不在注册物里**（每次现给）→ 改密码不需要重新注册；
- 同一个表拉第几次都拿到**这一次**的连接 ✓。

> 实测：表函数的**位置参数索引是 0 基**的（`getParameter(0)` 取到第一个参数），
> 且**一条语句执行失败后，那条语句就被关闭了**（后续调用报 `Statement was closed`）——
> 所以任何一个阶段失败都得**换一条新语句**继续。

#### 6.2.5 远端**只读**是由「构造」保证的

扫描函数**不接受**调用方传入的 SQL —— 它只执行引擎自己拼的那一句
`SELECT * FROM <命名空间>.<表>`。也就是说：即使这条链路上某处有 bug，
传进来的也只可能是「选哪张表」，不可能是「执行什么语句」。

这比「会话级只读开关」更强：开关靠**每个写入口**都记得检查，
而这里**压根没有写入口**。

#### 6.2.6 凭据**不进 SQL 文本**

远端连接配置（含密码）在**请求体**里（`AnalysisPullRequest.remote`），
引擎把它变成一条**只发 SELECT** 的 JDBC 连接。

请求 id 由**前端**生成 —— 取消时「叫停引擎那边正在跑的拉取」要靠它
（引擎是另一个进程，取消前端协程并不会让它停下来；见 §6.5）。

#### 6.2.7 原生扫描器：**可选加速**（默认关）

| 旋钮 | 系统属性 | 环境变量 | 默认 |
|---|---|---|---|
| 是否允许用原生扫描器 | `sundays.duckdb.nativeExtension` | `SUNDAYS_DUCKDB_NATIVE_EXT` | **关** |
| 扩展搜索目录（`;` 分隔） | `sundays.duckdb.extensionDirs` | `SUNDAYS_DUCKDB_EXTENSION_DIRS` | 应用自带目录 + DuckDB 默认的用户目录 |

**为什么默认关**：两条路的**类型映射不一样**（见 §6.3 的对照表）。
如果「走哪条路」能**隐式**决定类型，同一张表在两台机器上拉出来类型就可能不同 ——
那正是「同一个判据写两遍，迟早会不一致」。所以做成**显式开关**：
默认永远是纯 Java 表函数（行为一致），要性能的用户自己开。

**降级**：原生那条路走不通（这个方言没有扫描器 / 连接串拼不出来 / 挂载失败）
就**降级**回纯 Java 表函数 —— 用户看到的是「拉取成功」，不是「你的扩展没装」。

**三段式，顺序不能换**：

| 阶段 | 在事务里吗 | 做什么 |
|---|---|---|
| 挂载 | **否** | `SET extension_directories` → `LOAD` → `ATTACH … (TYPE mysql_scanner, READ_ONLY)` |
| 建表 | **是** | `DROP TABLE IF EXISTS` → `CREATE TABLE … AS SELECT * FROM <别名>."<库>"."<表>"` |
| 摘除 | **否** | `DETACH <别名>` |

**摘除必须在提交之后**（实测）：

```
TransactionContext Error: Cannot detach database "m" because the current
transaction has outstanding work on it - commit or rollback first
```

而这条错误**会中止事务** → 提交落空 → **建出来的表整张消失**
（症状：「拉取报成功、行数也对，可工作区里查不到那张表」——
`duckdb_tables()` 是空的）。第 8 片的第一版就栽在这儿。

**必须摘除**：挂着的远端会留在 DuckDB 的目录表里，而那条记录的 `path`
**含明文密码**（实测：`mysql://root:666666@…`）——
用户在编辑器里 `SELECT * FROM duckdb_databases()` 就能看见。

**原生路径没有实时行数进度**：DuckDB 的 `CREATE TABLE … AS SELECT` 不吐行数，
JDBC 也没有公开的进度接口 —— 所以只在落地之后报一次总数。

**限定名**：MySQL 挂载（URI 不带库）时，扫描器把服务器上的**所有库**
都挂成该挂载点下的 schema，于是限定名统一是 `别名."<库>"."<表>"`；
PG 挂载（URI 带库）时限定名是 `别名."<schema>"."<表>"`。
「带不带库」的判据就是既有的 `DatabaseDialect.supportsSchema`。

### 6.3 类型映射：**宁可粗一点，也不要让整次拉取失败**

一个装不下的值会让 DuckDB 在写入时**报错** —— 而那意味着**这张表整张拉不下来**。
所以映射的判据只有这一条。

| 远端类型 | 落到 DuckDB | 为什么 |
|---|---|---|
| `BOOLEAN` / `BIT` | `BOOLEAN` | |
| `INT` / `SMALLINT` / `TINYINT` | `INTEGER`（**无符号则 `BIGINT`**） | MySQL 的 `INT UNSIGNED` 最大 42 亿，装不进 32 位 `INTEGER`（21 亿）—— 那会让整次拉取**失败** |
| `BIGINT` | `BIGINT`（**无符号则 `DECIMAL(20,0)`**） | 无符号 64 位最大 1.8e19，装不进有符号 64 位 |
| `FLOAT` / `REAL` | `FLOAT` | |
| `DOUBLE` | `DOUBLE` | |
| `DECIMAL(p,s)` | `DECIMAL(p,s)`（**p ≤ 38**），否则 `VARCHAR` | DuckDB 的 DECIMAL 上限 38 位；精度超范围的落成文本 —— 宁可让下游用 `TRY_CAST` 建模，也不要在拉取时炸掉 |
| `DATE` | `DATE` | |
| `TIMESTAMP` | `TIMESTAMP` | |
| 其余（`VARCHAR` / `TEXT` / `BLOB` / `JSON` / `TIME` / 带时区时间戳 / 几何…） | `VARCHAR` | DuckDB 的写出接口没有 `TIME` 与带时区时间戳的对应类型，硬套会丢信息 |

> ✅ **联邦扫描保留远端的类型** —— 这是它比 CSV 中转强的地方：
> `INT` 落下来还是整数，于是 `SUM(id)` **直接就能跑**，
> 不必再 `TRY_CAST`（那套「原始层存文本、类型由下游建模」的约定随 CSV 一起消失了）。

> **两条路的类型映射不一样**（原生扫描器用 DuckDB 官方规则，上表是我们的规则）：
>
> | 远端列 | 原生扫描器 | 纯 Java 表函数（**默认**） |
> |---|---|---|
> | `SMALLINT` | `SMALLINT` | `INTEGER` |
> | `INT UNSIGNED` | `UINTEGER` | `BIGINT` |
> | `BIGINT UNSIGNED` | `UBIGINT` | `DECIMAL(20,0)` |
> | `TINYINT(1)` | `BOOLEAN` | `BOOLEAN` ← **不区分** |
>
> ⚠️ `TINYINT(1)` **不能**用来判断「走的是哪条路」：MySQL Connector/J 把它
> 报成 `Types.BIT`（`tinyInt1isBit` 默认开），于是**两条路都是 BOOLEAN**。
> 真正能区分的是**宽度**（`SMALLINT`）—— 第 8 片一开始就是拿 `TINYINT(1)`
> 当判据，结论「原生把它落成 BOOLEAN」看着对，其实**两边都一样**。

### 6.4 半张表不会留下

`DROP TABLE IF EXISTS` 与 `CREATE TABLE … AS SELECT` 包在**一个事务**里
（JDBC 的 `setAutoCommit(false)`）：

- 失败 / 取消 → 回滚 → **旧表原样还在**，新表一点痕迹都不留；
- 成功 → 提交 → 新表替换旧表。

> ⚠️ 事务是**必需**的，不是锦上添花：实测把 `autoCommit` 打开（去掉事务），
> 连**成功的**拉取都会挂（`CREATE TABLE … AS SELECT` 在自动提交下与
> 表函数的交互不成），失败的重新拉取更会把旧表弄丢。

### 6.5 进度与取消

- **进度**：引擎每读完一批远端行推一帧（`AnalysisPullProgressFrame`），
  界面显示「正在拉取，已读 N 行」。**只有一段** —— 联邦直查没有
  「导出 / CSV / 导入」那种阶段可分（那三段是绕磁盘逼出来的）。
  （走原生扫描器时**没有**逐帧进度 —— DuckDB 的 CTAS 不吐行数，
  只在落地之后报一次总数。）
- **取消**：两件事一起做 ——
  ① 取消前端协程（不再等结果）；② 给引擎发一条 `SYSTEM.CANCEL`，
  带上**这次请求的 id**。引擎那边 `StatementRegistry` 找到**正在跑的那条语句**，
  `cancel()` 掉它 → 事务回滚 → 终止帧带上 `cancelled` 标志。
- **取消不是失败**：终止帧上有一个**独立的布尔**（`AnalysisPullResponse.cancelled`），
  前端据此抛 `AnalysisPullCancelledException`（与协程的 `CancellationException` 分开），
  界面显示「拉取已取消」而不是「拉取失败：…」。
  靠**比对错误文本**判断取消那种写法，在文案一改就会失效。

> ⚠️ 取消要打在**正在跑的那条语句**上（DuckDB JDBC 实测）：
> `Statement.cancel()` 的第一件事是 `if (!stmtRefLock.isLocked()) return;` ——
> 拿一条**新**语句去 cancel 是**空操作**（取消发出去，拉取照跑到底，还报「成功」）。
> 所以引擎把「本次事务正在用的那条语句」登记给 `StatementRegistry`，
> 取消时打的就是它。
>
> 取消也可能落在**扫描开始之前**（那时还没有语句可打断）——
> 所以提交前还要再看一眼取消标志：取消了就**回滚**（不留表）并收成「取消」。
> 光看「结果是取消」不够：引擎完全可以在扫完之后才发现标志、回滚了事 ——
> 用户看到的是「取消」，可那 20 万行**白拉了**。

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
「当前选中的源」。早先的写法读会话里的 `selectedSourceId`（那会儿工具条上还有
一个「数据源」下拉框；**现在那个下拉框已经去掉了** —— 源只有一处能选：左树）。

但树上点的是**某一张具体的表** —— 它属于哪个源是那棵树自己知道的，
而「当前选中的源」可能压根不是这张表的源。
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

主设计 §6.3.3 那张「`DataTable` 零改动」的用法表，本轮**逐行**落地情况：

| 参数 | 本轮传什么 | 状态 |
|---|---|---|
| `onCellEdit` | `null` | ✅ |
| `cellEditable` | `false`（显式，不靠 `onCellEdit` 的默认值联动） | ✅ |
| `isColumnEditable` / `isCellPending` / `isRowDeleted` | 保持默认 | ✅ |
| `pageSize` | `PageSize.ALL` —— 结果集是**一次查询返回的完整集合**，分页纯属客户端的视图安排；用默认的「每页 20 行」的话，一张 5000 行的结果得点 250 页 | ✅ |
| `serverSidePaging` | `false` | ✅ |
| `totalCount` | `rows.size`（默认值） | ✅ |
| `contextMenuItems` | 复制 / 导出为 CSV | ⏳ **未接** |

> ⚠️ `contextMenuItems` 那一行**还没接上**：写剪贴板（`ClipboardWriter`，`java.awt`）
> 与选保存路径（`NativePathPicker`）都在 `desktopApp`，而 `AnalysisScreen` 在 `:shared`，
> 只能把事件**回调**出去（`onCopy` / `onExportCsv` 两个回调，由 desktopApp 注入）。
> 它**不是** M4 的验收项，但它是设计的一部分 —— 所以显式记下缺口，不装作已有。
> 另注：只读表本来就有 `SelectionContainer`，单元格文本可以**拖选复制**，
> 右键菜单的增量是「按行 / 按整表复制」与「导出为 CSV」。

### 6.7 主设计 §6.5 的一条规则**作废**（实现期偏离）

主设计 §6.5 写着「组件层不提供写入口：…；SQL 编辑器只放行
`DangerousSql.isReadOnlyQuery` 判定为只读的语句」。

**这条按原文实现是不对的** —— 它与同一份设计的另一条验收直接冲突：

| 出处 | 原文 |
|---|---|
| 主设计 §6.5 | SQL 编辑器**只放行**判定为只读的语句 |
| 主设计 §11 M4 验收 | 工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功** |

分析模式的编辑器打的是**本地工作区**（DuckDB 文件），而工作区是**可写**的
（§6.1 的两条会话只读方向相反）。把编辑器拦成只读，那条验收就**永远通不过**。

**本轮的实现取法**：

- **编辑器不拦写** —— 它写的是工作区，工作区就该能写；
- **远端路径上根本没有可写的入口** ——
  ① 远端**没有** SQL 编辑器（`RUN_QUERY` 只允许打在声明了 `EMBEDDED_MODE` 的方言上，
  那是**引擎侧**的硬拦，不是前端自觉）；
  ② 远端的元数据只走 `LIST_*`；
  ③ 结果表 `onCellEdit = null` —— 分析结果不是一张可以改的表；
- 远端恒为只读的**保证**仍在引擎的 L3 / L4 —— 组件层一个判断都不做，
  正是 §6.5 自己那句「同一个判据写两遍，迟早会不一致」。

主设计 §6.5 已同步更正（v1.0）。

### 6.8 工具条**没有**「数据源」下拉框

分析工作台的顶栏只剩 `⊘ 只读` 徽标 + 「▶ 运行」。早先那里还有一个
「数据源」下拉框（`WinDropdown`），第 9 片**去掉**了。

**为什么去掉**：它是**纯装饰**。拉取走的是**树上那张具体的表**
（表节点自带 `sourceId`，见 §6.6.4）—— 下拉框里那个「当前选中的源」
**全程只有它自己在读**，没有任何一条路径去取它。

而它带来的是**两份可能对不上的真相**：用户在下拉框里选了 A 源，
却在树上点了 B 源的表 —— 界面上「当前源是 A」，拉下去的却是 B 源的
**同名表**，而那**不会报错**（只要 B 源里恰好有那张表），
只是数据悄悄换了来源。

源只有一处能选：**左树**。一处就够。

**连带清掉的**：

| 东西 | 为什么能删 |
|---|---|
| `AnalysisScreen` 的 `selectedSourceId` / `onSelectSource` 两个参数 | 只有下拉框在用 |
| 会话的 `selectedSourceId` / `selectSource()` 与「源没了就剪枝」 | 选中态整体不存在了（那正是**两份真相**的来源） |
| `analysisSourceRowTag()` | 它的存在理由是「名字同时出现在下拉框与树里，按文本找会命中两个节点」—— 下拉框没了，歧义也没了；而树里定位行本来就有 `analysisTreeRowTag()` |

**判据**：源的名字在屏幕上**只出现一次**（树里那一行）。
把下拉框加回去，那条断言立刻变红（变异验证过）。

### 6.9 顶层导航：目标切换 chip 去掉，入口挪进**连接管理的空闲面板**

第 10 片：**顶栏的「连接管理」「数据分析」两个 chip 去掉**，顶栏只剩**品牌 + ⚙ 设置**。

**为什么去掉**：

- 「连接管理」是**首屏** —— chip 只是把当前项再显示一遍（切过去等于什么都不做）；
- 「数据分析」是**入口** —— 入口该待在用户真正需要它的地方，而不是常驻在顶栏抢注意力。

**入口挪到哪儿**：**连接管理的空闲面板**（没选中任何连接时，右侧那一块本来就在问
「接下来做什么」）。它现在有三个按钮：快速连接 / 新建连接 / **数据分析**。

> 弹窗（`AddConnectionDialog`）复用同一个空闲面板，但**不给**这个入口 ——
> 弹窗是「加一个连接」，不是「换个模式」。

**那分析模式怎么回来**：分析工作台的**工具条**上多了一个**返回**按钮（←）。
顶层 chip 去掉后若不给返回路，用户从连接管理点进分析模式就**回不来了** ——
而「点进去就回不来」是这个仓库最不该出现的东西。
入口只有一条，就得也只进得出。

**连接详情也能关**：选中一条连接，右侧就从空闲引导页换成**连接详情**——
而空闲引导页上挂着「数据分析」那个入口。所以详情面板的头部有一个 **✕**：
点了就取消选中，右侧回到空闲引导页。
没有它的话，用户选中过一条连接之后，那条唯一的入口就再也找不回来了。

**连带清掉的**：`TopNavBar` 的 `current` / `onNavigate` 两个参数与 `NavChip` 组件
（芯片没了，选中态那套颜色解析也就没人用了）。

**判据**（三处，各做过变异验证）：

| 判据 | 变异 |
|---|---|
| 顶栏**没有**「连接管理」「数据分析」两个 chip | 把 chip 加回去 → 顶栏那条用例变红 |
| 空闲面板**有**「数据分析」入口，且点它**真的回调** | 不把入口传下去 → 连接管理那条用例变红 |
| 分析页**有**返回按钮，且点它**真的回调** | 不渲染返回按钮 → 分析界面那条用例变红 |


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

### 7.4 联邦拉取端到端（`AnalysisPullFederationTest` / `AnalysisPullIntegrationTest` / `AnalysisPullSmokeTest`）

- 真 H2（引擎侧）/ 真 MySQL（桌面侧）拉一张表进 DuckDB → `staging_<库>_<表>` 存在、**行数对得上**
- **值一字不差**地搬过来
- 拉取**全程不落盘** —— 拉完之后连一个临时文件都没有（这是「零 CSV 中转」的判据）
- 拉下来的表**保留远端的类型**（`INT` 落下来还是整数 → `SUM(id)` 直接能跑）
- 失败的拉取**不留半张表**；失败的**重新**拉取**保住旧表**（事务回滚）
- 用户取消被报成「取消」而不是「失败」（终止帧上带独立的 `cancelled` 标志）
- 工作区里跑一条窗口函数聚合 → 结果正确
- 工作区里 `CREATE TABLE tmp AS SELECT …` **成功**（本地写允许）

### 7.5 界面（`AnalysisScreenTest` / `AnalysisPullIndicatorTest` / `AnalysisSourceTreeTest`）

- 「⊘ 只读」徽标**没有关闭入口**
- 拉取时显示「正在拉取 · 已读 N 行」；**界面上不再出现「临时文件」**（联邦直查不落盘）
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
| 5 | `feat: 拉取链路 —— 远端 EXPORT 到工作区 IMPORT` | 三段编排 + `PullStageIndicator` + 临时 CSV 清理 | §7.4 全绿 | ✅ **已提交**（**第 7 片已替换**） |
| 6 | `feat: 分析工作区界面 —— SourceTree 与只读结果表` | `SourceTree` + 结果表只读 + 编辑器接通 | §7.5 全绿；端到端可截图 | ✅ **已提交** |
| 7 | `feat(analysis): 拉取改走 DuckDB 联邦直查 —— 去掉 CSV 中转` | 协议（`PULL_TABLE`）+ 引擎的**纯 Java 表函数** + 幂等注册 + 拉取路由 + 前端改接 + 模型与 i18n 重写 | §7.4 / §7.5 全绿；**变异验证**四处各被抓住 | ✅ **已提交** |
| 8 | `feat(analysis): 原生扫描器作为可选加速 —— 顺带修掉两个真 bug` | 引擎的 `NativeExtensionScanner`（三段式）+ 表函数改**无状态**（句柄参数）+ 取消打在**正在跑的语句**上 + 文档 | §7.4 / §7.5 全绿；**变异验证**四处各被抓住 | ✅ **已提交** |
| 9 | `refactor(analysis): 分析工作台去掉「数据源」下拉框` | 工具条（`:shared` 的 `AnalysisScreen`）+ 会话里的选中态 + `analysisSourceRowTag` + UI 设计图 | 源的名字在屏幕上**只出现一次**；**变异验证**：把下拉框加回去用例变红 | ✅ **已提交** |
| 10 | `refactor(analysis): 顶栏去掉「连接管理 / 数据分析」chip，入口挪到连接管理的空闲面板` | `TopNavBar`（只留品牌 + ⚙）+ `IdlePanel` 的「数据分析」入口 + `AnalysisScreen` 的返回按钮 + `main.kt` 接线 | 顶栏没有 chip；空闲面板有入口且点了回调；分析页有返回按钮；**变异验证**三处各被抓住 | ✅ **本轮落地** |

> 📌 **第 3 片的实际落点比原文多两处**，都是实现时定下来的：
> 一是**方言能力位声明**（`dialect-mysql` / `dialect-postgresql` 声明 `ANALYSIS_SOURCE`）——
> §4.4 决定的「加」，在这一片里真的写进了引擎，前端据此筛数据源；
> 二是**顶层导航条**（`shared/.../navigation/TopNavBar.kt`）——
> 原文第 3 片只写了「顶层导航」，没定它长什么样、落在哪；
> 落点在 `:shared` 的 `commonMain`（纯 Compose，不碰引擎），与 §1.3 的判据一致。
>
> ⚠️ **第 3 片没有做的**：编辑器与结果面板、工作区会话、拉取链路 ——
> 那是第 4~6 片。**第 3 片当时**的 `AnalysisScreen` 是**骨架**：工具条、数据源树、
> 只读徽标是真的，右侧主区仍走空态（到第 6 片才补齐，见 §8 的提交切分表）。
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

> 📌 **第 6 片的实际落点比原文多五处**：
> 一是**工作区那一层不摆命名空间**（§6.6.1）—— DuckDB 的工作区只有一个
> `main` schema，把它摆出来只会在每张表前面多插一个没有信息量的节点；
> 二是**拉取要显式传源 id**（§6.6.4）—— 用「当前选中的源」会拉错库的同名表，
> 而那**不会报错**，只是数据悄悄换了来源；
> 三是**树与展开态都归会话管**（§5.2）—— 会话比界面活得久，
> 展开态放界面侧的话，用户切走再回来会发现树缩回去了；
> 四是**结果表的只读判据是结构的**（§6.6.6）—— 见下；
> 五是**主设计 §6.5 的「编辑器只放行只读语句」那条作废**（§6.7）——
> 它与 M4 的验收「工作区里 `CREATE TABLE tmp AS SELECT …` 可以成功」直接冲突。
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
>
> 📌 **第 7 片：拉取链路整个换掉 —— 「导出 CSV → 导入 CSV」→「DuckDB 联邦直查」**
>
> 这是**用户指出**的一处设计错误：绕磁盘搬数据是**能跑通但不该这么跑**的做法。
> 实现上它牵动四层：协议（`PULL_TABLE` + 请求体带远端配置）、引擎（纯 Java 表函数 +
> 幂等注册 + 拉取路由）、前端（拉取器 / 会话 / 界面件 / i18n）、文档。
>
> 实测里最要紧的三条结论（都在 §6.2.3）：
> 表函数**跨连接可见**（注册一次就够）、**不落盘**（凭据不进文件）、
> 但**随实例消失**（所以每次拉取前都要幂等地「确保已注册」）。
>
> ✅ **第 7 片的变异验证（三处，各被抓住）**：
>
> | 变异 | 变红的用例 |
> |---|---|
> | 无符号整数**不扩宽**（`INT UNSIGNED` 也落成 32 位 `INTEGER`） | `an unsigned 32 bit integer widens instead of overflowing` + `an unsigned 64 bit integer becomes a decimal` |
> | 去掉**事务**（`autoCommit` 打开） | `a remote table lands in the workspace…` + `the scanned rows keep their values…` + `a failed re-pull keeps the old table` |
> | 前端**忽略**终止帧上的 `cancelled` 标志（取消被当成失败） | `the user cancel is reported as a cancel and not as a failure` |
>
> 📌 **第二条为什么值得单独记**：一开始的变异是「去掉那句显式的 `rollback()`」，
> 结果测试**照样绿** —— 因为连接归还给 Hikari 池时它自己会回滚未提交的事务。
> 也就是说「失败时旧表还在」这条保证，**真正的提供者是连接池的归还回滚**，
> 不是我们写的那句 `rollback()`。
> 把变异换成「**去掉事务**」（`autoCommit` 打开）才真正打到了它 ——
> 这也说明：断言要打在**行为**上，不能打在「我以为是谁在负责」上。
>
> 📌 **第 8 片：原生扫描器作为**可选加速**，顺带修掉两个真 bug**
>
> 落点是**引擎侧**（前端零改动）：拉取多一条后端 ——
> 显式打开（`SUNDAYS_DUCKDB_NATIVE_EXT=1`）且扩展可用时走
> DuckDB 的原生扫描器（`ATTACH` + `CREATE TABLE … AS SELECT`），
> 走不通就**降级**回纯 Java 表函数。**默认关** —— 两条路的**类型映射不同**，
> 「装没装扩展」不该隐式决定用户拿到的类型。
>
> 实测推翻了初稿的两处说法：「DuckDB 的 extension 要联网下载」**是错的**
> （它们**静态链接在 JDBC 驱动里**）；「`TINYINT(1)` 能用来判断走的是哪条路」
> **也是错的**（Connector/J 把它报成 `Types.BIT`，**两条路都是 BOOLEAN**）。
> 真能区分的是**宽度**（`SMALLINT`）。
>
> 这一片还修掉两个**实测抓出来的真 bug**：
> ① **同一张表拉第二次必失败**（表函数闭包握着已归还的连接 → 改成**无状态**，
> 每次拉取现给上下文，用**句柄**当 SQL 参数传进去）；
> ② **取消打不中**（DuckDB 的 `Statement.cancel()` 只认「本语句上正在跑的查询」，
> 拿一条新语句去 cancel 是**空操作** → 改成取消**正在跑的那条**）。
>
> ✅ **第 8 片的变异验证（四处，各被抓住）**：
>
> | 变异 | 变红的用例 |
> |---|---|
> | 原生那条路走不通时**不降级**（直接失败） | `the pull falls back to the pure java scan…` + `turning the native scanner on does not change the result…` |
> | 取消改回「拿一条**新**语句去 cancel」 | `the user cancel is reported as a cancel and not as a failure`（加强后：还断言**扫描真的被打断**了） |
> | `DETACH` 挪回**事务里**（提交之前） | `the native scanner is used when it is enabled and available`（表整张消失） |
> | 表函数**永远用第一次的上下文** | `the same table can be pulled twice` + 另外两条联邦用例 |
>
> 📌 **第三条为什么值得单独记**：取消那一例一开始**抓不住**那个变异 ——
> 因为「提交前再看一眼取消标志」这条兜底把结果也收成了「取消」。
> 于是把断言加强成「**扫描真的被打断**」（最后读到的行数远小于总数），
> 变异才现形。
> **断言要打在「行为真的发生了」上**，而不只是「结果看起来对」。

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
| 拉取把远端磁盘写满 | **不再落盘** —— 联邦直查没有临时文件（§6.2）；远端那侧的压力由用户自己的库承担，与工具无关 |
| 取消拉取后**远端查询还在跑** | 取消走 `SYSTEM.CANCEL`（带请求 id）→ 引擎 `cancel()` 掉那条 DuckDB 语句 → 远端连接随事务回滚关闭（§6.5） |
| 表函数的**类型映射**让整次拉取失败 | 映射的判据是「宁可粗一点，也不要让整次拉取失败」：装不下的类型一律落到更宽的类型或文本（§6.3） |
| 挂载的远端把**明文密码**留在工作区里 | 挂载点摘除在**提交之后**（事务里 DETACH 会中止事务 → 提交落空 → 表消失，§6.2.7）；摘不掉只记日志 —— 下一轮的别名是新的，不会撞名 |
| MySQL / PG 进分析模式削弱了安全性 | **这正是 L4 存在的理由**；且 §3.6 的两类回归测试是它的守门人 |
| 能力位在前后端两处各写一份 | 前端用引擎 `LIST_DRIVERS` 下发的 `DialectInfo.capabilities`，**不另立真相源** |

---

## 10. 待定（沿用主设计 §13）

- **Q8** AI API Key 存储 —— M7 再定
- **R1** `hive-jdbc` 与 `hadoop-common 3.5.0` 版本冲突 —— M3 spike
- **R2** Impala 走 HS2 的兼容性 —— M3 spike
- 目标环境 Hive / Impala 的发行版与版本 —— 未定，只影响 M3 的驱动取舍
- **结果表的右键菜单**（复制 / 导出为 CSV）—— 主设计 §6.3.3 的用法表里的一行，
  **还没接**（§6.6.6）。接线形态已定：`AnalysisScreen` 收 `onCopy` / `onExportCsv`
  两个回调，由 `desktopApp` 注入（剪贴板与文件对话框都是平台专属）。
  它**不是** M4 的验收项，所以不阻塞 M2 + M4' 的收尾。
- **活体联邦**（不落表，直接把远端表 JOIN 进查询）—— 联邦扫描已经具备这个能力
  （表函数注册在 DuckDB 实例上，SQL 里可以直接引用它），但**没有做界面**：
  用户得知道函数名与参数形状。本轮先做「拉取落表」这条最常用的路；
  活体联邦留给下一片（届时表函数要改成**按源注册**并长期驻留）。
