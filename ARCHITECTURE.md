# sundays — Kotlin 数据库管理端架构导航（V2.9）

> **本文件仅作整体介绍与模块导航**。详细架构设计、handler 矩阵、方言特性、协议规范、双模式对比等深度内容已分散到各子模块的 `ARCHITECTURE.md`（见下方"模块导航"）。

---

## 1. 项目定位

`sundays` 是一个**全 Kotlin** 实现的桌面数据库管理工具：

- **后端引擎**：以 **gRPC 服务端** 方式运行（默认 `:50051`）的无头数据库算力引擎；v2.9 起新增 **Direct 直接模式** facade
- **前端客户端**：**Kotlin Multiplatform + Compose Multiplatform Desktop** 桌面应用（macOS / Linux / Windows 三端共享 Compose Desktop Skia 渲染）
- **整条工具链 Kotlin 一统**：共享 `protobuf-kotlin-lite` DSL 生成器 + `kotlinx-coroutines`，无语言边界、无桥接开销

**当前版本：v2.9** — KMP Desktop 前端 + Direct 模式 + 双模式架构

---

## 2. 双模式架构（v2.9 核心）

| 模式 | 引擎入口 | 客户端 | 适用场景 |
|---|---|---|---|
| **Direct 直接模式（v2.9 推荐）** | `IdbEngine().handle(request): Flow<Response>` | Compose UI（同 JVM） | KMP Desktop 应用，零序列化、零子进程、零 IPC |
| **gRPC 模式（向后兼容）** | `IdbEngineServer`（gRPC server over IPC transport） | 任意 gRPC client | 跨进程、跨语言、子进程隔离、远程调试 |

两条路径共享同一个 `RequestDispatcher`，envelope options（`traceId` / `dryRun` / `timeoutMs`）、流式 frame assembly、`if_exists` 语义等横切关注点完全一致。

**典型调用栈**：

```
Direct 模式（KMP Desktop 推荐）:
[KMP Desktop Compose UI] ── IdbEngine.handle(req) ─→ [RequestDispatcher] ─→ [Handlers] ─→ [Dialects] ─→ [DB]
   (同 JVM, typed proto, 直接方法调用)

gRPC 模式（跨进程 / 跨语言）:
[任意 gRPC client] ── gRPC stub ─→ [IdbEngineImpl] ──[IdbEngine.handle]──→ [RequestDispatcher] ─→ [Handlers] ─→ [Dialects] ─→ [DB]
                              (IPC: TCP / UDS / Named Pipe)
```

详细对比、最佳实践、流式响应契约、envelope options 见各子模块文档。

---

## 3. 模块结构

```
sundays/
├── api/                      公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── dialect-mysql/            MySQL 方言插件 JAR
├── dialect-postgresql/       PostgreSQL 方言插件 JAR
├── dialect-h2/               H2 方言插件 JAR（嵌入式 + 测试载体）
├── dialect-duckdb/           DuckDB 方言插件 JAR（v2.7 新增，嵌入式 OLAP）
├── dialect-sqlite/           SQLite 方言插件 JAR（v2.8 新增，嵌入式关系型）
├── engine/                   主引擎模块（gRPC server + Direct facade + 13 个 handler + 5 方言 loader）
├── shared/                   KMP 共享 UI 组件（CodeEditor / DataTable / 右键菜单）
└── desktopApp/               KMP Compose Desktop 应用（v2.9 新前端，Direct 模式集成）
```

### 模块导航

| 模块 | 入口 README | 内部架构 CLAUDE | 内容主题 |
|---|---|---|---|
| **engine/** | [`engine/README.md`](./engine/README.md) | [`engine/ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | gRPC 协议 / 13 个 handler 路由 / 强类型 envelope / 表驱动 Dispatcher / 流式响应 / 连接池 / IPC Transport SPI / Direct 模式 facade / 导出子进程隔离 |
| **api/** | [`api/README.md`](./api/README.md) | — | DatabaseDialect SPI 接口定义 / ConnectionType / DialectCapability / 扩展自定义方言流程 |
| **dialect-mysql/** | [`dialect-mysql/README.md`](./dialect-mysql/README.md) | — | MySQL 方言特性 / SQL 危险关键词 / 已知约束 |
| **dialect-postgresql/** | [`dialect-postgresql/README.md`](./dialect-postgresql/README.md) | — | PostgreSQL 方言特性 / search_path / DDL 事务 / 已知约束 |
| **dialect-h2/** | [`dialect-h2/README.md`](./dialect-h2/README.md) | — | H2 嵌入式 / 集成测试 fixture / 已知约束 |
| **dialect-duckdb/** | [`dialect-duckdb/README.md`](./dialect-duckdb/README.md) | — | DuckDB 嵌入式 OLAP / Excel 预转换 / FK table-rebuild / 已知约束 |
| **dialect-sqlite/** | [`dialect-sqlite/README.md`](./dialect-sqlite/README.md) | — | SQLite 嵌入式 / INTEGER PRIMARY KEY / FK table-rebuild / ATTACH/DETACH / 已知约束 |
| **shared/** | [`shared/README.md`](./shared/README.md) | [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | KMP Compose 组件（CodeEditor / DataTable / 右键菜单）/ 高度策略 / 可扩展插槽 / 与引擎解耦 |
| **desktopApp/** | [`desktopApp/README.md`](./desktopApp/README.md) | [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | KMP 工程结构 / Direct 模式集成 / Demo 屏幕 / 生命周期管理 |
| **历史文档** | [`docs/architecture-history-v2.9.md`](./docs/architecture-history-v2.9.md) | — | v2.9 之前的根级完整架构设计（已归档保留） |

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

# 启动引擎 gRPC server standalone
./gradlew engine:jar
cd engine/build/libs && java -jar idb-engine.jar

# 启动引擎 Direct 模式 standalone（不开 server）
java -jar idb-engine.jar --mode direct
```

详细 CLI 参数、IPC transport 切换、Demo 屏幕说明见 [`engine/README.md`](./engine/README.md) 与 [`desktopApp/README.md`](./desktopApp/README.md)。

---

## 6. 技术栈（简表）

- **Kotlin 2.4.0 / JDK 25**
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
| **v2.9** | **KMP Desktop 前端 + 双模式架构（gRPC + Direct）** | 详细：[`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) + [`engine/ARCHITECTURE.md`](./engine/ARCHITECTURE.md) |

> **v2.9 关键设计补充**：CodeEditor / DataTable 统一高度策略 —— `CodeEditor.maxLines` 默认 `null`（不施加高度上限，填充父容器剩余高度但不超父容器）；`DataTable.fillParentHeight` 默认 `true`（同语义）。两个组件均无需调用方显式指定高度即自适应父容器。详细见 [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md)。

完整迁移日志与每版本详细变更见 [`docs/architecture-history-v2.9.md`](./docs/architecture-history-v2.9.md)（v2.9 之前的根级完整文档已归档）。