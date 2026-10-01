# sundays — Kotlin 数据库管理端架构导航（V2.16）

> **本文件仅作整体介绍与模块导航**。详细架构设计、handler 矩阵、方言特性、协议规范、调用层抽象等深度内容已分散到各子模块的 `ARCHITECTURE.md`（见下方"模块导航"）。

---

## 1. 项目定位

`sundays` 是一个**全 Kotlin** 实现的桌面数据库管理工具：

- **后端引擎**：可作 **gRPC 服务端**（默认 `:50051`）运行的无头数据库算力引擎，同时提供 **进程内 facade**
- **调用层**：统一的 `EngineClient` 接口 —— 同一份调用代码可跑在同进程或跨进程 gRPC 上
- **前端客户端**：**Kotlin Multiplatform + Compose Multiplatform Desktop** 桌面应用（macOS / Linux / Windows 三端共享 Compose Desktop Skia 渲染）
- **整条工具链 Kotlin 一统**：共享 protobuf 生成器 + `kotlinx-coroutines`，无语言边界、无桥接开销

**当前版本：v2.16** — 引擎能力补齐（查询取消 `SYSTEM.CANCEL` / 数据导入 `IMPORT.RUN_IMPORT` / 事务会话 `SYSTEM.BEGIN` 系列 / 多语句脚本）

---

## 2. 调用层架构（v2.15 核心）

调用方只面向 **`EngineClient` 接口**（`com.kxxnzstdsw.client.EngineClient`）编程，**不感知引擎在同进程还是跨进程**：

| 模式 | 实现 | 模块 | 通道 | 适用场景 |
|---|---|---|---|---|
| **Direct 直接模式（默认）** | `IdbEngine` | `engine/` | 同 JVM 直接方法调用 | Compose UI 同进程，零序列化、零子进程、零 IPC |
| **gRPC 模式** | `GrpcEngineClient` | `engine-grpc-client/` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、子进程隔离、远程调试 |

两条实现共用 `:engine-protocol` 中的同一份 `Request` / `Response` 消息与同一个 `RequestDispatcher` 路由集，
因此 envelope options（`traceId` / `dryRun` / `timeoutMs`）、流式 frame assembly、`if_exists` 语义、错误包装（`success=false, error=…`）**逐帧一致**。
`GrpcEngineClientTest` 用「同一 Request 走两条路径、断言 Response 列表相等」把这条平价钉住。

**模块依赖**（无环）：

```
engine-protocol  ──api──▶  engine（同进程实现 + gRPC 服务端）
      │
      └─────────api──▶  engine-grpc-client（跨进程实现）
```

**典型调用栈**：

```
Direct 模式（默认，KMP Desktop）:
[Compose UI] ── EngineClient.handle(req) ─→ [RequestDispatcher] ─→ [Handlers] ─→ [Dialects] ─→ [DB]
              （同一接口，同一实现类：IdbEngine）

gRPC 模式（跨进程 / 跨语言）:
[任意 gRPC client] ── gRPC stub ─→ [IdbEngineImpl] ─→ [EngineClient.handle] ─→ [RequestDispatcher] ─→ [Handlers] ─→ [DB]
[KMP Desktop]     ── EngineClient.handle(req) ─→ [GrpcEngineClient] ── gRPC over IPC ─→ [IdbEngineImpl] ─→ …
                              (IPC: TCP / UDS / Named Pipe)
```

**v2.15 — 远程连接生命周期**：连接池活在引擎进程内，远程调用方要「测试 / 断开」必须走线。
因此 `SYSTEM.TEST_CONNECTION`（v2.11 已有）与 **`SYSTEM.DISCONNECT`（v2.15 新增）** 成为 `EngineClient` 契约的一部分；
`disconnect` 幂等，且已纳入 `writeActions`（`dryRun` 不得真的掐断用户连接）。

**v2.16 — 写路径能力补齐**：同一份 `RequestDispatcher` 路由集新增 `Category.IMPORT` 与一组 SYSTEM 事务 action —— `IMPORT.RUN_IMPORT`（`Category.IMPORT = 14` / `Action.RUN_IMPORT = 21`）、`SYSTEM.CANCEL`（`Action.CANCEL = 20`）、`SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`（`Action.BEGIN = 22` / `COMMIT = 23` / `ROLLBACK = 24` / `SESSION_INFO = 25`）。
写路径的公共约束浓缩为一条：**连接的归属者决定它何时被释放** —— 不带会话时 handler 借用后归还，带会话（`Request.session_id`，字段 6）时这条钉住的连接归 `TransactionManager` 所有，直到 `COMMIT` / `ROLLBACK`；搞错这点会让事务静默退化成「只有最后一条语句」。
`dryRun` 短路集合（`writeActions`）新增 **`IMPORT.RUN_IMPORT`**（`truncate_first` 会清空目标表）与 v2.15 已有的 **`SYSTEM.DISCONNECT`**。此外 `SQL.EXECUTE` 不再是「单语句」——`SqlExecuteRequest.multi_statement` 启用后由 `SqlScriptSplitter` 只切**顶层** `;`，逐条执行并在 `StatementRegistry` 单独登记以便取消。

**前端选择实现**：`main.kt` 是唯一装配点 —— 设置 `-Dsundays.engine.endpoint=host:port`（或 `unix://path` / `pipe:name`）走 gRPC，
否则用同进程 `IdbEngine`。端点格式错误在装配时直接抛出，不会静默退回本地。

详细对比、最佳实践、流式响应契约、envelope options 见各子模块文档。


---

## 3. 模块结构

```
sundays/
├── api/                      公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── engine-protocol/          调用层契约（proto 协议 + 强类型消息 + EngineClient 接口，v2.15 新增）
├── dialect-mysql/            MySQL 方言插件 JAR
├── dialect-postgresql/       PostgreSQL 方言插件 JAR
├── dialect-h2/               H2 方言插件 JAR（嵌入式 + 测试载体）
├── dialect-duckdb/           DuckDB 方言插件 JAR（v2.7 新增，嵌入式 OLAP）
├── dialect-sqlite/           SQLite 方言插件 JAR（v2.8 新增，嵌入式关系型）
├── engine/                   引擎实现（14 个 handler + 5 方言 loader + IdbEngine 本地实现 + gRPC 服务端）
│                              v2.16：`engine/StatementRegistry`（查询取消）+ `engine/SqlScriptSplitter`（多语句）
│                              `pool/TransactionManager`（事务会话）+ `importer/`（CSV / JSON Lines 导入阅读器）
├── engine-grpc-client/       gRPC 调用模块（GrpcEngineClient 跨进程实现，v2.15 新增）
├── shared/                   KMP 共享 UI 组件（CodeEditor / DataTable / 右键菜单 / 顶层导航 / 应用主题）
└── desktopApp/               KMP Compose Desktop 应用（KMP 工程结构 / 顶层导航 + 数据库浏览第二屏）
```

### 模块导航

| 模块 | 入口 README | 内部架构 | 内容主题 |
|---|---|---|---|
| **engine-protocol/** | [`engine-protocol/README.md`](./engine-protocol/README.md) | — | 调用层契约：`EngineClient` 接口 / proto 协议 / `SYSTEM.DISCONNECT` 路由 |
| **engine/** | [`engine/README.md`](./engine/README.md) | [`engine/ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | gRPC 协议 / 14 个 handler 路由 / 强类型 envelope / 表驱动 Dispatcher / 流式响应 / 连接池 / IPC Transport SPI / `IdbEngine` 实现 / 导出子进程隔离 / v2.16 查询取消 + 事务会话 + 数据导入 |
| **engine-grpc-client/** | [`engine-grpc-client/README.md`](./engine-grpc-client/README.md) | — | `GrpcEngineClient` 跨进程实现 / 端点配置（TCP / UDS / pipe）/ 端到端测试 |
| **api/** | [`api/README.md`](./api/README.md) | — | DatabaseDialect SPI 接口定义 / ConnectionType / DialectCapability / 扩展自定义方言流程 |
| **dialect-mysql/** | [`dialect-mysql/README.md`](./dialect-mysql/README.md) | — | MySQL 方言特性 / SQL 危险关键词 / 已知约束 |
| **dialect-postgresql/** | [`dialect-postgresql/README.md`](./dialect-postgresql/README.md) | — | PostgreSQL 方言特性 / search_path / DDL 事务 / 已知约束 |
| **dialect-h2/** | [`dialect-h2/README.md`](./dialect-h2/README.md) | — | H2 嵌入式 / 集成测试 fixture / 已知约束 |
| **dialect-duckdb/** | [`dialect-duckdb/README.md`](./dialect-duckdb/README.md) | — | DuckDB 嵌入式 OLAP / Excel 预转换 / FK table-rebuild / 已知约束 |
| **dialect-sqlite/** | [`dialect-sqlite/README.md`](./dialect-sqlite/README.md) | — | SQLite 嵌入式 / INTEGER PRIMARY KEY / FK table-rebuild / ATTACH/DETACH / 已知约束 |
| **shared/** | [`shared/README.md`](./shared/README.md) | [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | KMP Compose 组件（CodeEditor / DataTable / 右键菜单 / AppDestination / SundaysTheme + SundaysPalette 视觉规范）/ 高度策略 / 可扩展插槽 / 与引擎解耦 |
| **desktopApp/** | [`desktopApp/README.md`](./desktopApp/README.md) | [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | KMP 工程结构 / Direct 模式集成 / 顶层导航 / 连接管理 / 数据库浏览第二屏 / 生命周期管理 |

---

## 4. 方言矩阵（5 个方言能力对比）

| 能力 / 方言 | MySQL | PostgreSQL | H2 | DuckDB | SQLite |
|---|---|---|---|---|---|
| **connectionType** | CLIENT_SERVER | CLIENT_SERVER | IN_MEMORY | EMBEDDED | FILE_BASED |
| **defaultPort** | 3306 | 5432 | — | — | — |
| **USERS** | ✓ | ✓ | ✓ | — | — |
| **PRIVILEGES** | ✓ | ✓ | ✓ | — | — |
| **ROUTINES** | ✓ | ✓ | ✓ | ✓ (MACRO) | — |
| **VIEWS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **INDEXES** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **FOREIGN_KEYS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **TRIGGERS** | ✓ | ✓ | ✓ | — | — |
| **MULTI_SCHEMA** | — | ✓ | ✓ | ✓ | — |
| **CROSS_DATABASE** | — | ✓ | ✓ | ✓ (ATTACH) | — (ATTACH 不暴露) |
| **EXPORT** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **DDL_TRANSACTION** | ✓ | ✓ | ✓ | — | — |
| **EMBEDDED_MODE** | — | — | ✓ | ✓ | ✓ |

各方言 SPI 元数据 + 详细特性 + 已知约束见对应 `README.md`。

---

## 5. 快速运行

```bash
# 运行 KMP Desktop 应用（Direct 模式，构造即 bootstrap 引擎 + 所有方言）
./gradlew :desktopApp:run

# Hot reload 开发模式
./gradlew :desktopApp:hotRun --auto

# 启动引擎 gRPC server standalone（独立进程）
./gradlew :engine:jar
cd engine/build/libs && java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051

# 前端改走 gRPC（连上面那个独立引擎进程）
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051

# 启动引擎 Direct 模式 standalone（不开 server）
java -jar idb-engine.jar --mode direct
```

详细 CLI 参数、IPC transport 切换、前端屏幕说明见 [`engine/README.md`](./engine/README.md) 与 [`desktopApp/README.md`](./desktopApp/README.md)。

---

## 6. 技术栈（简表）

- **Kotlin 2.4.20 / JDK 25**
- **KMP + Compose Multiplatform Desktop**（仅 `jvmMain` 单平台目标）
- **gRPC 1.83.1** + grpc-kotlin 1.5.0（Kotlin 协程服务端 + Kotlin DSL 生成）
- **protobuf-kotlin-lite 4.35.1**（DSL builder）
- **kotlinx-coroutines 1.11.0**
- **HikariCP 7.0.2**（SHA-256 缓存连接池）
- **数据库驱动**：MySQL 9.7.0 / PostgreSQL 42.7.11 / H2 2.3.232 / DuckDB 1.5.5.1 / SQLite 3.46.1.3
- **SLF4J 2.0.18 + Logback 1.5.13**
- **LuaJIT 4.1.0 + Lua 5.1~5.5**（造数引擎）
- **Apache POI 5.5.1**（Excel 流式导出 + DuckDB Excel 预转换）
- **Apache Parquet 1.17.1 + Hadoop 3.5.0**（Parquet 导出）

---

## 7. 架构升级历史（简表）

| 版本 | 主要变化 | 详情 |
|---|---|---|
| v1.0 | stdin/stdout + 4-byte BE uint32 长度前缀 + 自定义 protobuf（旧管道协议） | — |
| v2.0 | gRPC over HTTP/2 + 标准 google.protobuf.Value | — |
| v2.1 | gRPC + IPC Transport SPI（TCP / UDS / Named Pipe） | — |
| v2.2 | gRPC + 强类型 per-Category Request/Response + CLI Args | — |
| v2.3 | gRPC + 强类型 Handlers end-to-end（删除 `TypedRequestMapper`） | — |
| v2.4 | gRPC + 强类型 per-list-item 消息（typed `Row` wrapper） | — |
| v2.5 | gRPC 1.76 + grpc-kotlin 协程服务端 + Kotlin DSL end-to-end | — |
| v2.6 | 表驱动 Dispatcher + 跨切面 Envelope Options（`traceId` / `dryRun` / `timeoutMs`）+ `SQL.EXPLAIN` 路由 | — |
| v2.7 | DuckDB 方言插件（本地嵌入式 OLAP） | 详细：[`dialect-duckdb/README.md`](./dialect-duckdb/README.md) |
| v2.8 | SQLite 方言插件 + SPI 连接元数据扩展 + `SYSTEM.LIST_DRIVERS` | 详细：[`dialect-sqlite/README.md`](./dialect-sqlite/README.md) + [`api/README.md`](./api/README.md) |
| v2.9 | KMP Desktop 前端 + 双模式架构（gRPC + Direct） | 详细：[`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) |
| v2.12 | 连接管理流程与引擎打通 | 详细：[`README.md` 架构升级历史](./README.md#架构升级历史) |
| v2.13 | 顶层导航 + 数据库浏览第二屏 | 详细：[`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) |
| v2.14 | 共享代码上移 `shared` + 模块整理 | 详细：[`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md) |
| **v2.15** | **调用层抽象** —— proto 迁出为 `:engine-protocol` + `EngineClient` 接口；新增 `:engine-grpc-client` 跨进程实现；`SYSTEM.DISCONNECT` 路由；`IpcConfig` 修复 `--mode` 误判 | 详细：[`engine-protocol/README.md`](./engine-protocol/README.md) + [`engine-grpc-client/README.md`](./engine-grpc-client/README.md) |
| **v2.16** | **引擎能力补齐（关闭与 DBeaver / Navicat / DataGrip 的功能差距）** —— 查询取消 `SYSTEM.CANCEL`（`Action.CANCEL = 20`）+ `StatementRegistry`；数据导入 `IMPORT.RUN_IMPORT`（`Category.IMPORT = 14` / `Action.RUN_IMPORT = 21`）+ `importer` 包（`CsvReader` / `JsonLinesReader` / `ImportSourceFactory`）；事务会话 `SYSTEM.BEGIN/COMMIT/ROLLBACK/SESSION_INFO` + `Request.session_id` + `TransactionManager`；多语句脚本 `SqlExecuteRequest.multi_statement` + `SqlScriptSplitter`。`:engine:test` 188 → **277**，项目总计 **619** | 详细：[`README.md` 引擎新能力（v2.16）](./README.md#引擎新能力v216) |

> **v2.9 关键设计补充**：CodeEditor / DataTable 统一高度策略 —— `CodeEditor.maxLines` 默认 `null`（不施加高度上限，填充父容器剩余高度但不超父容器）；`DataTable.fillParentHeight` 默认 `true`（同语义）。两个组件均无需调用方显式指定高度即自适应父容器。详细见 [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md)。

本表为简表：每个版本的详细变更见该行「详情」列指向的文档；各模块当前的深度设计见上文「模块导航」的「内部架构」列。