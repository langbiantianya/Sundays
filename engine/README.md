# IDB Engine — 数据库管理后端引擎（v2.16）

无头、跨平台的 Kotlin 数据库管理引擎：把一条 `Request`（`Category` + `Action`）路由到强类型业务 handler，经方言 SPI 操作 MySQL / PostgreSQL / H2 / DuckDB / SQLite 5 个方言。两种入口模式共用同一个 `RequestDispatcher`。

| 模式 | 入口 | 场景 |
|---|---|---|
| **gRPC 模式**（默认） | gRPC server over IPC transport（TCP / UDS / pipe） | 跨进程、跨语言客户端、远程引擎、子进程隔离 |
| **Direct 模式** | [`IdbEngine`](./src/main/kotlin/com/langbiantianya/engine/IdbEngine.kt) —— `EngineClient` 的同进程实现 | Compose Desktop（同 JVM 直调，零 gRPC/IPC 开销） |

> **阅读指引**：实现细节 —— 路由分支、handler 请求/响应语义、连接池、事务、导入导出、IPC、插件加载、查询取消、多语句 —— 已全部移入对应源文件的 **KDoc**；架构叙述与 wire 契约见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)。
> 本文件只保留**操作手册**与**源码索引**，不再是 API 参考手册。

---

## 构建

```bash
./gradlew engine:jar
```

产物位于 `engine/build/libs/`：

```
idb-engine.jar          主引擎瘦包（Main-Class: com.langbiantianya.MainKt）
libs/                   运行时依赖（gRPC、HikariCP、LuaJIT、POI、Parquet…）
drivers/                JDBC 驱动（mysql-connector-j / postgresql / h2 / duckdb_jdbc / sqlite-jdbc）
dialects/               方言插件（idb-dialect-{mysql,postgresql,h2,duckdb,sqlite}.jar，SPI 动态加载）
```

方言插件与 JDBC 驱动按 **JAR 逐个** 发现，坏插件只跳过自身、不阻断启动 —— 详见 [`DialectLoader`](./src/main/kotlin/com/langbiantianya/loader/DialectLoader.kt) 与 [`DriverLoader`](./src/main/kotlin/com/langbiantianya/loader/DriverLoader.kt) 的 KDoc。

## 运行（CLI 参数与启动方式）

```bash
# TCP loopback（默认 127.0.0.1:50051，POSIX 上自动 fallback 到 unix）
cd engine/build/libs && java -jar idb-engine.jar

# 显式指定 TCP 端口
java -jar idb-engine.jar --ipc tcp --port 60000

# 对外暴露（无鉴权！请自行限制网络可达性）
java -jar idb-engine.jar --ipc tcp --host 0.0.0.0 --port 50051

# Unix Domain Socket
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock

# Windows 命名管道（grpc-java 暂无 server-side 公共 API，详见 ARCHITECTURE.md §3.6）
java -jar idb-engine.jar --ipc pipe --pipe-name idb-engine

# Direct 模式 —— 仅 bootstrap drivers/dialects 后阻塞主线程，不开 server
java -jar idb-engine.jar --mode direct

# 打印完整 CLI 帮助
java -jar idb-engine.jar --help
```

**所有 CLI 选项**：

```
--mode <grpc|direct>      启动模式（默认 grpc；direct 仅 bootstrap、不开 server）
--ipc <tcp|unix|pipe>     IPC 传输（默认自动检测：Windows → pipe，POSIX → unix；仅 grpc 模式生效）
--port <int>              TCP 端口（默认 50051，仅 tcp 模式生效）
--host <addr>             TCP 绑定地址（默认 127.0.0.1；传 0.0.0.0 对外暴露，无鉴权）
--uds-path <path>         UDS 文件路径（默认 /tmp/idb-engine.sock）
--pipe-name <name>        命名管道名称（默认 idb-engine；需匹配 ^[A-Za-z0-9_.-]{1,64}$）
--help / -h               打印 CLI 用法并退出
```

参数解析实现见 [`IpcConfig`](./src/main/kotlin/com/langbiantianya/ipc/IpcConfig.kt)；`--mode` 由 [`IdbEngineServer.parseMode`](./src/main/kotlin/com/langbiantianya/server/IdbEngineServer.kt) 单独解析。解析失败抛 `IllegalStateException`，启动入口打印到 stderr 并 `exitProcess(2)`。

> **⚠️ TCP 端口无鉴权** —— 连上即可执行任意 SQL（含 `SYSTEM.DISCONNECT` 踢掉他人连接）。默认**只绑回环** `127.0.0.1`；确需跨机访问时显式 `--host 0.0.0.0`，并自行在网络层限制可达性。

---

## Handler 路由矩阵

`RequestDispatcher` 是 (Category, Action) → handler 的表驱动薄路由层：新增路由只需一个 map 条目，未命中直接抛 `UnsupportedOperationException`（无静默兜底）。完整路由表见 [`RequestDispatcher`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) 的 KDoc。

| Category | Actions | Handler 源码 |
|---|---|---|
| `SCHEMA` | LIST · CREATE · DELETE | [`SchemaHandler`](./src/main/kotlin/com/langbiantianya/handlers/SchemaHandler.kt) |
| `USER` | LIST · CREATE · UPDATE · DELETE · GRANTS | [`UserHandler`](./src/main/kotlin/com/langbiantianya/handlers/UserHandler.kt) |
| `TABLE` | LIST · CREATE · UPDATE · DELETE · GET_DDL · RENAME · TRUNCATE | [`TableHandler`](./src/main/kotlin/com/langbiantianya/handlers/TableHandler.kt) |
| `DATA` | LIST · CREATE · UPDATE · DELETE · GENERATE | [`DataHandler`](./src/main/kotlin/com/langbiantianya/handlers/DataHandler.kt) · [`GenerateHandler`](./src/main/kotlin/com/langbiantianya/handlers/GenerateHandler.kt) |
| `SQL` | EXECUTE · EXPLAIN | [`SqlEngineHandler`](./src/main/kotlin/com/langbiantianya/handlers/SqlEngineHandler.kt) |
| `SYSTEM` | INFO · TEST_CONNECTION · SERVER_INFO · LIST_DRIVERS · DISCONNECT · CANCEL · BEGIN · COMMIT · ROLLBACK · SESSION_INFO | [`SystemHandler`](./src/main/kotlin/com/langbiantianya/handlers/SystemHandler.kt) |
| `FUNCTION` | LIST · INFO · GET_DDL · CREATE · UPDATE · DELETE · CALL · DEBUG | [`FunctionHandler`](./src/main/kotlin/com/langbiantianya/handlers/FunctionHandler.kt) |
| `VIEW` | LIST · CREATE · DELETE · GET_DDL | [`ViewHandler`](./src/main/kotlin/com/langbiantianya/handlers/ViewHandler.kt) |
| `INDEX` | LIST · CREATE · DELETE | [`IndexHandler`](./src/main/kotlin/com/langbiantianya/handlers/IndexHandler.kt) |
| `FOREIGN_KEY` | LIST · CREATE · DELETE | [`ForeignKeyHandler`](./src/main/kotlin/com/langbiantianya/handlers/ForeignKeyHandler.kt) |
| `TRIGGER` | LIST · GET_DDL | [`TriggerHandler`](./src/main/kotlin/com/langbiantianya/handlers/TriggerHandler.kt) |
| `EXPORT` | RUN_EXPORT（`Action.EXPORT` 与 `Category.EXPORT` 在 proto3 命名冲突） | [`ExportHandler`](./src/main/kotlin/com/langbiantianya/handlers/ExportHandler.kt) |
| `IMPORT` | RUN_IMPORT | [`ImportHandler`](./src/main/kotlin/com/langbiantianya/handlers/ImportHandler.kt) |

**流式路由**（`Response.stream/end` 分帧，逐行推流而非一次性返回）：`DATA.LIST`（`pageSize=0`）· `SQL.EXECUTE` · `DATA.GENERATE` · `EXPORT.RUN_EXPORT` · `IMPORT.RUN_IMPORT`。

**跨切面 envelope options**（`traceId` → SLF4J MDC、`dryRun` 对写操作短路、`timeoutMs` 覆盖流式路由并先经 `StatementRegistry` 取消运行中的语句）统一在 `dispatch()` 内应用，见 `RequestDispatcher` KDoc 与 [`ARCHITECTURE.md` §4.1.1](./ARCHITECTURE.md)。

`Request.session_id` 留空 = 无事务（每条语句独立提交，与 v2.15 及以前完全一致）；非空时该请求的写操作落在事务会话钉住的连接上。

---

## 子系统清单

| 子系统 | 说明 | 源码 |
|---|---|---|
| 引擎门面 | `EngineClient` 的同进程实现，构造即 bootstrap drivers/dialects；`bootstrap` / `runDirectMode` 在 companion | [`IdbEngine`](./src/main/kotlin/com/langbiantianya/engine/IdbEngine.kt) |
| gRPC 服务端 | 协程 stub（`suspend handle()` → `Flow<Response>`），薄壳化直连 `IdbEngine.handle` | [`IdbEngineServer`](./src/main/kotlin/com/langbiantianya/server/IdbEngineServer.kt) · [`IdbEngineImpl`](./src/main/kotlin/com/langbiantianya/server/IdbEngineImpl.kt) |
| 请求路由 | 表驱动 (Category, Action) → handler；dryRun / timeout / traceId / 语言上下文 | [`RequestDispatcher`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) |
| 查询取消 | 「运行中请求 → 可取消目标」登记表，`SYSTEM.CANCEL` 据此调 `Statement.cancel()` | [`StatementRegistry`](./src/main/kotlin/com/langbiantianya/engine/StatementRegistry.kt) |
| 多语句脚本 | 单趟词法分句（跳过字符串 / 引号标识符 / 注释 / PG dollar-quoting），仅顶层 `;` 切分 | [`SqlScriptSplitter`](./src/main/kotlin/com/langbiantianya/engine/SqlScriptSplitter.kt) |
| 错误语言 | 决定 handler 错误消息的书写语言（`Response.error` 原样上屏） | [`EngineLanguageContext`](./src/main/kotlin/com/langbiantianya/engine/EngineLanguageContext.kt) |
| 连接池 | HikariCP 按 SHA-256 池键（`配置#schema` 两段式）缓存，空闲自动释放 | [`PoolManager`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) |
| 事务会话 | `session_id` 钉住一条连接 + `autocommit=false`，`COMMIT` / `ROLLBACK` 收口 | [`TransactionManager`](./src/main/kotlin/com/langbiantianya/pool/TransactionManager.kt) |
| IPC 传输 | 跨平台传输 SPI + CLI 参数选择，业务层零感知 | [`IpcTransport`](./src/main/kotlin/com/langbiantianya/ipc/IpcTransport.kt) · [`IpcConfig`](./src/main/kotlin/com/langbiantianya/ipc/IpcConfig.kt) · [`IpcTransportRegistry`](./src/main/kotlin/com/langbiantianya/ipc/IpcTransportRegistry.kt) · [`impl/`](./src/main/kotlin/com/langbiantianya/ipc/impl/TcpIpcTransport.kt) |
| 插件加载 | ServiceLoader 动态加载 JDBC driver 与方言（类路径 SPI + 目录 JAR 双通道） | [`DriverLoader`](./src/main/kotlin/com/langbiantianya/loader/DriverLoader.kt) · [`DialectLoader`](./src/main/kotlin/com/langbiantianya/loader/DialectLoader.kt) |
| 数据导出 | 独立 JVM 子进程隔离，5 种格式 writer | [`ExportEngine`](./src/main/kotlin/com/langbiantianya/export/ExportEngine.kt) · [`ExportProcessManager`](./src/main/kotlin/com/langbiantianya/export/ExportProcessManager.kt) · [`ExportSubProcess`](./src/main/kotlin/com/langbiantianya/export/ExportSubProcess.kt) · [`ExportWriter`](./src/main/kotlin/com/langbiantianya/export/ExportWriter.kt) · [`ExportModels`](./src/main/kotlin/com/langbiantianya/export/ExportModels.kt) |
| 数据导入 | 主进程内 CSV / JSON_LINES 批量插入，开文件时剥离 UTF-8 BOM | [`ImportSource`](./src/main/kotlin/com/langbiantianya/importer/ImportSource.kt) · [`ImportFormat`](./src/main/kotlin/com/langbiantianya/importer/ImportFormat.kt) · [`ImportSourceFactory`](./src/main/kotlin/com/langbiantianya/importer/ImportSourceFactory.kt) · [`CsvReader`](./src/main/kotlin/com/langbiantianya/importer/CsvReader.kt) · [`JsonLinesReader`](./src/main/kotlin/com/langbiantianya/importer/JsonLinesReader.kt) |
| gRPC 负载适配 | protobuf `Value` 与引擎内部结构的互转 | [`PayloadAdapter`](./src/main/kotlin/com/langbiantianya/grpc/PayloadAdapter.kt) |
| 业务 handler | 13 个 Category 共 14 个 handler，全部接收 typed per-Category proto 消息、返回 typed 响应，无 `JsonObject` 边界映射 | [`handlers/`](./src/main/kotlin/com/langbiantianya/handlers/SchemaHandler.kt) |

---

## 与其他模块的关系

- **依赖**：`:engine-protocol`（proto 契约 + `EngineClient` 接口，`api`）、`:api`（`DatabaseDialect` SPI）、`:i18n`
- **被依赖**：`:desktopApp`（`implementation(project(":engine"))`，Compose Desktop 走 Direct 模式）、`:engine-grpc-client`（仅测试期反向依赖，用于契约互验）
- **不依赖**：`:engine-grpc-client` 反过来只依赖 `:engine-protocol`，远程调用方无需引入 `:engine`
- **方言插件**：`:dialect-{mysql,postgresql,h2,duckdb,sqlite}` 以 JAR 形态由 SPI 加载，运行期解耦

## 测试

```bash
./gradlew engine:test          # 仅本模块
./gradlew test                 # 全仓库
```

报告：`engine/build/reports/tests/test/index.html`。测试按子系统分目录 —— `integration/`（handler × H2/DuckDB/SQLite 端到端）、`ipc/`（三种传输 + CLI 参数解析）、`pool/`、`loader/`、`importer/`、`engine/`（分句器）；fixture 见 [`src/test/kotlin/com/langbiantianya/testutil/`](./src/test/kotlin/com/langbiantianya/testutil/H2Fixture.kt)。

## 添加新方言

1. 创建 Gradle 模块，依赖 `api` 项目
2. 实现 [`DatabaseDialect`](../api/README.md)，声明 `override val driverName = "YourDriver"`
3. 在 `src/main/resources/META-INF/services/com.langbiantianya.dialect.DatabaseDialect` 写入实现类全限定名
4. 构建后将 JAR 放入 `engine/build/libs/dialects/`
5. 重启引擎即自动加载，无需修改主引擎代码

前端通过 `SYSTEM.LIST_DRIVERS` 动态枚举已加载方言的连接元数据来渲染「新建连接」表单，因此新增方言无需前端改动。

---

## 相关文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | 引擎架构：dispatcher / 连接池 / IPC / wire 契约 / 各 Category 详细设计 |
| [`../api/README.md`](../api/README.md) | 公共 SPI：`DatabaseDialect` + `ConnectionType` + `DialectCapability` |
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | proto 契约（`idb_engine.proto` / `idb_export.proto`）与 `EngineClient` 调用层接口 |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | `EngineClient` 的跨进程 gRPC 实现 `GrpcEngineClient` |
| [`../dialect-mysql/README.md`](../dialect-mysql/README.md) | MySQL 方言插件 |
| [`../dialect-postgresql/README.md`](../dialect-postgresql/README.md) | PostgreSQL 方言插件 |
| [`../dialect-h2/README.md`](../dialect-h2/README.md) | H2 方言插件 |
| [`../dialect-duckdb/README.md`](../dialect-duckdb/README.md) | DuckDB 方言插件（本地嵌入式 OLAP） |
| [`../dialect-sqlite/README.md`](../dialect-sqlite/README.md) | SQLite 方言插件（本地嵌入式关系型） |
