# Engine Architecture (V2.9)

> 引擎模块（`engine/`）的内部架构设计文档，供在引擎内部工作的开发者使用。
> 完整模块拓扑、构建/运行 CLI 与用户级 API 示例见 `engine/README.md`，项目整体架构与版本演进历史见根目录 `../ARCHITECTURE.md`。

## 1. Overview

引擎模块是无头（headless）的数据库算力引擎，**对外通过 gRPC 协议提供服务，对内实现方言无关的请求分发与连接管理**。模块以多模块 Gradle 工程组织：`api/` 抽象 `DatabaseDialect` SPI；`dialect-mysql/` / `dialect-postgresql/` / `dialect-h2/` / `dialect-duckdb/` / `dialect-sqlite/` 五个方言插件以独立 JAR 通过 `ServiceLoader` 动态加载；`engine/` 是主模块，包含 gRPC 服务端、IPC 传输抽象、连接池、13 个业务 handler、RequestDispatcher 路由、导出子进程管理、LuaJIT 造数引擎。

**核心设计原则**：

- **强类型端到端**（v2.5 起）：13 个 handler 全部直接接收 `Request.body` oneof 中的 typed per-Category proto 消息、返回 typed `<Category><Action>Response` proto 消息；`RequestDispatcher` 是 (Category, Action) → handler 的薄路由层，业务层无任何 `JsonObject` 编解码、无 `Request.newBuilder()...build()` 残留。
- **绝对无状态**：每次请求必须携带完整连接凭证（driver/host/port/user/password/database），引擎内部不维护"当前选中的数据库"等业务状态。
- **方言插件化**：新增数据库方言只需实现 `DatabaseDialect` SPI 并提供 `META-INF/services` 注册，无需修改引擎主代码。

**双模式架构**（v2.9）：gRPC 模式（默认，`--mode grpc`）通过 IPC transport（TCP / UDS / Named Pipe）提供跨进程服务；Direct 直接模式（v2.9 新增，`--mode direct` 或同 JVM `IdbEngine().handle(request)`）跳过 gRPC channel、protobuf 序列化、IPC 派发，让同 JVM 客户端（同进程的 KMP Desktop Compose UI 或 shell 工具）以零开销调用引擎。两条路径共享同一个 `RequestDispatcher`，envelope options（`traceId` / `dryRun` / `timeoutMs`）、流式帧分装、横切语义完全一致。

## 2. Technology Stack

| 范畴 | 技术 | 版本 | 用途 |
|---|---|---|---|
| 核心语言 | Kotlin | 2.4.0 | 整个引擎 + 5 个方言 |
| 运行时 | JDK | 25 | 编译目标 |
| 异步框架 | kotlinx-coroutines | 1.11.0 | 协程服务端、流式响应聚合 |
| 序列化 | kotlinx-serialization-json | 1.11.0 | 业务层 JSON（仅方言差异显著的 item shape 保留 `google.protobuf.Value`） |
| gRPC 服务端 | grpc-netty-shaded | 1.83.1 | gRPC HTTP/2 + 传输层 |
|  | grpc-stub + grpc-protobuf | — | gRPC 运行时 |
|  | grpc-kotlin-stub | 1.5.0 | 协程服务端 `IdbEngineCoroutineImplBase` + Kotlin DSL 生成 |
| Protobuf | protobuf-kotlin-lite | 4.35.1 | 生成 *Kt DSL builder（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }`） |
| Protobuf 工具链 | protoc | 3.25.5 | proto3 编译（锁定） |
|  | protoc-gen-grpc-java | 1.68.0 | Java gRPC stub 生成（锁定） |
|  | protoc-gen-grpc-kotlin | 1.4.1 | Kotlin gRPC stub 生成（锁定） |
| 连接池 | HikariCP | 7.0.2 | 动态数据库连接池 |
| 数据库驱动 | MySQL Connector/J | 9.7.0 | MySQL 协议 |
|  | PostgreSQL JDBC | 42.7.11 | PostgreSQL 协议 |
|  | H2 | 2.3.232 | 嵌入式 + 测试 |
|  | DuckDB JDBC | 1.5.5.1 | 嵌入式 OLAP（v2.7 新增，driver 名 `Duckdb`） |
|  | SQLite JDBC | 3.46.1.3 | 嵌入式关系型（v2.8 新增，driver 名 `Sqlite`） |
| 脚本引擎 | LuaJIT + Lua 5.1~5.5 | luajava 4.1.0 | 造数引擎（`DATA.GENERATE`）沙箱 |
| Excel 导出 | Apache POI | 5.5.1 | SXSSF 流式 + DuckDB `.xlsx` 预转换 |
| Parquet 导出 | Apache Parquet + Hadoop | 1.17.1 / 3.5.0 | 动态 Schema Parquet 写出 |
| 日志 | SLF4J + Logback | 2.0.18 / 1.5.13 | 日志门面 + 实现 |
| 构建与分发 | Gradle + ShadowJar | — / 9.3.0+ | 多模块构建 + 瘦包 |
| 测试 | JUnit 5 + kotlin.test | — | 单元 + 集成测试 |

**强制依赖**：`protobuf-java 3.25.8`（grpc-protobuf 1.83 传递依赖，不可强制升至 4.x）；`grpc-core` / `protobuf-java-util` / `ksp` / `kotlinx-serialization-protobuf` 均为已移除的无用依赖。

## 3. Core Mechanisms

### 3.1 通信协议（gRPC）

引擎以 **gRPC 服务端** 方式运行（端口默认 `:50051`，可通过 `--port` 覆盖），调用方通过标准 gRPC stub 与引擎通信。

- **服务定义**（`engine/src/main/proto/idb_engine.proto`）：
  ```proto
  service IdbEngine {
    rpc Handle(Request) returns (stream Response);
  }
  ```
- **传输**：HTTP/2 + 标准 protobuf（强类型 per-Category 消息），由 `grpc-netty-shaded` 驱动
- **消息边界**：gRPC 自动处理帧切分，无需手动 length-prefix
- **Wire 类型**：`Request` 与 `Response` 均为强类型 — `Request` 使用 `oneof body { schema_request, user_request, ... }` 路由到 per-Category 消息（共 12 个：`SystemRequest`/`SchemaRequest`/`UserRequest`/`TableRequest`/`DataRequest`/`SqlRequest`/`FunctionRequest`/`ViewRequest`/`IndexRequest`/`ForeignKeyRequest`/`TriggerRequest`/`ExportRequest`）；`Response` 同样使用 `oneof body` 镜像 12 个 per-Category 响应消息 + 3 个流式帧类型（`DataRowFrame`/`SqlSelectRowFrame`/`GenerateProgressFrame`）+ `GenerateTerminalResponse`
- **业务层**：13 个 handler 全部直接接收 typed per-Category proto 消息（`ConnectionConfig` + `<Category><Action>Request`），返回 typed per-Action proto 消息（`<Category><Action>Response`）；`RequestDispatcher` 是按 (Category, Action) 路由的薄层，把 typed handler 返回值装入 `Response.body` 对应 oneof 分支。**无 `JsonObject` 边界映射**
- **Kotlin DSL（v2.5 起）**：业务层全部使用 protoc-gen-grpc-kotlin + protobuf-kotlin-lite 生成的 Kotlin DSL builder（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`），无 `Request.newBuilder()...build()` 残留；`google.protobuf.Value` 因属 Well-Known Type 仍用 `Value.newBuilder()`（无生成 DSL）
- **最大消息大小**：`maxInboundMessageSize = 256 MiB`
- **异步处理**：服务端基于 grpc-kotlin `IdbEngineCoroutineImplBase`（suspend `handle()` → `Flow<Response>`）+ Kotlin 协程 (`kotlinx-coroutines`)；`addService` 改为 `.addService(IdbEngineImpl().bindService())`
- **错误响应**：业务异常被 `RequestDispatcher` 拦截，提取 `e.message` 包装入 `Response.error`，`success` 置为 `false`，`id` 保持请求的 id

**Direct 直接模式契约（v2.9 起）**：

```kotlin
class IdbEngine : AutoCloseable {
    constructor(driversDir: File = File("drivers"), dialectsDir: File = File("dialects"))
    fun handle(request: Request): Flow<Response>
    suspend fun invoke(connection: ConnectionConfig, configure: RequestKt.Dsl.() -> Unit): Response
    override fun close()
}
```

`IdbEngineImpl.handle`（gRPC 路径）在 v2.9 重构为**薄壳** — 直接桥接 `IdbEngine.handle`，消除重复路由路径。两条路径共享 `RequestDispatcher`，因此 envelope options（`traceId`/`dryRun`/`timeoutMs`）、stream frame assembly、`if_exists` 语义等横切关注点完全一致。

**v2.9 修复**：`RequestDispatcher.dispatch` 之前把 try/catch 放在 `flow{}` 内部，导致下游 `first()` / `takeWhile` 等短路算子取消时抛出的 `AbortFlowException` 被错误捕获并再次 emit，触发 *"Flow exception transparency violated"*。v2.9 把 try/catch 移到 `.catch{}` operator 外置，`AbortFlowException` 现在能正常向上传播，direct 调用方与 gRPC 调用方都受益。

### 3.2 绝对无状态设计 (Stateless Design)

引擎进程不维护"当前选中的数据库"等业务状态。**每一次**请求都必须在其 protobuf `connection` 字段中携带完整的数据库连接凭证（driver / host / port / user / password / database）。

### 3.3 动态连接池管理器 (Dynamic Pool Manager)

为了解决无状态带来的频繁 TCP 握手开销，引擎内部实现基于 SHA-256 Hash Key 的智能缓存连接池。

1. **连接复用**：根据传入的凭证（driver + host + port + user + password + database）生成 SHA-256 Hash，若缓存中已有对应的 HikariCP 实例且活跃，则直接复用。
2. **资源自动回收**：`idleTimeout` 设为 10 分钟，`minimumIdle` 为 0。若某个库 10 分钟无操作，该连接池将自动缩容直至完全销毁。
3. **极限并发**：最大连接数 (`maximumPoolSize`) 限制为 5。
4. **连接超时**：`connectionTimeout` 设为 5 秒。
5. **最大生命周期**：`maxLifetime` 为 30 分钟。

### 3.4 导出子进程隔离机制 (Export Subprocess Isolation)

数据导出模块独立运行在子进程中，通过 `ExportProcessManager` 管理。

- **设计目标**：防止大数据量导出时 OOM 影响主进程稳定性；支持任务取消；主进程关闭时自动停止子进程
- **子进程模式**：`java -Didb.subprocess=true -jar idb-engine.jar`，由 `ExportProcessManager` 通过 gRPC `ExportHub` side server 编排
- **内存限制**：子进程 `-Xmx512m`
- **响应流管线**：子进程通过 `ExportHub` 将进度帧转发回主进程，主进程再 emit 到上游 gRPC StreamObserver

### 3.5 Schema 导航层级 (Navigation Hierarchy)

数据库连接后，前端导航分为两级（PG/H2 支持两级，MySQL 仅支持 database）：

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库（information_schema / mysql / performance_schema / sys） | 不支持 — 单元素列表（database == schema） |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname != 'information_schema'` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |
| DuckDB | 文件路径 / `memory` / 单元素 | `information_schema.schemata` |
| SQLite | 单元素（`database` 路径或 `:memory:`） | 单元素（`main`） |

> 上述行为表是**引擎层契约** — 每个方言插件必须按此规则实现 `listDatabases` / `listSchemas`，供前端构建两级导航 UI。

`SchemaHandler.list` 按 `payload["level"]` 分发（默认 `"database"`）：
- `level="database"` → `dialect.listDatabases(conn)` → 返回 `{level: "database", items: [...]}`
- `level="schema"` → 必须同时传 `payload["database"]` → `dialect.listSchemas(conn, database)` → 返回 `{level: "schema", database, items: [...]}`

所有 TABLE / DATA / SQL / FUNCTION / VIEW / INDEX / FOREIGN_KEY / TRIGGER 操作均支持可选 `schema` 参数（PostgreSQL 有效，MySQL 忽略，H2 默认 PUBLIC）；引擎自动 `SET search_path TO <schema>`（PG）或 `SET SCHEMA <schema>`（H2），确保无前缀表名能正确解析。

### 3.6 跨平台 IPC 传输抽象 (Cross-Platform IPC Transport Abstraction)

引擎在 gRPC 之上抽象了一层 **IPC Transport SPI**（`com.kxxnzstdsw.ipc.IpcTransport`），允许通过 CLI 参数在三种传输方式之间切换，无需修改业务代码。`IdbEngineServer` 在启动时调用 `IpcConfig.fromArgs(args)` 解析配置，再通过 `IpcTransportRegistry.resolve(cfg)` 获取传输实例，进而拿到 `ServerBuilder<*>` / `ManagedChannelBuilder<*>`，业务层完全不感知底层传输。

**三种传输方式**：

| `--ipc` | 传输方式 | 适用平台 | 服务端实现 | 客户端实现 |
|---|---|---|---|---|
| `tcp`（默认） | TCP loopback | 全平台 | `NettyServerBuilder.forPort(port)`（生产路径，失败回退 `ServerBuilder.forPort`） | `ManagedChannelBuilder.forAddress("localhost", port).usePlaintext()` |
| `unix` | Unix Domain Socket（filesystem namespace） | Linux / macOS / BSD | Linux + epoll native 可用：`EpollServerDomainSocketChannel` + `EpollEventLoopGroup`；其它：`NioServerDomainSocketChannel` + `NioEventLoopGroup` | `NettyChannelBuilder.forAddress(DomainSocketAddress(path))` + 对应 channelType + eventLoopGroup |
| `pipe` | Windows 命名管道 | Windows | grpc-java 1.68（及最新 1.83）**无公开 server-side API**，`serverBuilder()` 抛 `UnsupportedOperationException` | `Grpc.newChannelBuilder("pipe:<name>", InsecureChannelCredentials.create())` |

**CLI 参数**：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--mode <grpc\|direct>` | `grpc` | 启动模式（v2.9 新增 `direct`：仅 bootstrap 后阻塞主线程，不监听 IPC） |
| `--ipc <kind>` | OS 自动检测 | `tcp` / `unix` / `pipe`；未传时 Windows → `pipe`，POSIX → `unix` |
| `--port <int>` | `50051` | TCP 端口（仅 `tcp` 模式生效） |
| `--uds-path <path>` | `/tmp/idb-engine.sock` | UDS 文件路径（POSIX） |
| `--pipe-name <name>` | `idb-engine` | 命名管道名称（Windows） |
| `--help` / `-h` | — | 打印用法到 stdout 并退出 0 |

**SPI 接口**（`engine/.../ipc/IpcTransport.kt`）：

| 方法 | 职责 |
|---|---|
| `scheme()` | 诊断用标识（`tcp` / `unix` / `pipe`） |
| `displayTarget()` | 显示用地址（端口号 / UDS 路径 / `pipe:<name>`） |
| `prepare()` | 启动前资源准备（UDS 删除 stale 文件并 `Files.createFile` + `chmod 600`；Pipe 校验名称格式） |
| `serverBuilder()` | 返回 `ServerBuilder<*>`（UDS 路径下需额外配置 `bossEventLoopGroup` + `workerEventLoopGroup`） |
| `channelBuilder()` | 返回 `ManagedChannelBuilder<*>`（UDS 路径下需配置 `eventLoopGroup`） |
| `cleanup()` | shutdown 后资源回收（UDS 删除 socket 文件） |

**资源隔离**：
- EventLoopGroup 通过 `NettyServerBuilder.bossEventLoopGroup()` / `workerEventLoopGroup()` 设置后，grpc 在 `server.shutdown()` 时自动释放
- UDS 文件权限：默认 `rw-------`（POSIX）；启动时若存在则删除后重建
- 命名管道名称校验：`^[A-Za-z0-9_.-]{1,64}$`，由 `prepare()` 抛出 `IllegalArgumentException` 终止启动

**为什么不用 Linux abstract namespace UDS**：macOS / BSD 不支持 abstract namespace。

**Windows 服务端限制**：grpc-java 1.76.0（当前依赖版本，最新 1.83.0 亦未公开）未暴露用于 Windows Named Pipes 的 server-side API。`NamedPipeIpcTransport.serverBuilder()` 抛 `UnsupportedOperationException`，提示需 JNA + Win32 `CreateNamedPipe` 自实现（标记为未来工作）；客户端 `channelBuilder()` 可用。引擎在 Windows 上仍可通过切换到 `tcp` 模式运行（`--ipc tcp`）。

## 4. 数据交互契约 (gRPC Wire Contract)

引擎与调用方之间通过标准 gRPC 传递强类型结构化数据。所有 `Request` / `Response` 的字段含义通过 proto3 schema 定义；下文中的 JSON 示例仅为业务层理解用的逻辑表示，**实际 wire 上是 typed protobuf 的二进制紧凑编码**（不再使用 `map<string, Value>`）。

### 4.0 Wire 传输 (Wire Transport)

- **传输层**：gRPC over HTTP/2（`grpc-netty-shaded`）
- **消息类型**：强类型 per-Category protobuf 消息（`oneof body` 路由）
- **最大消息大小**：`maxInboundMessageSize = 256 MiB`
- **消息边界**：由 gRPC HTTP/2 帧头管理
- **流式响应**：客户端调用 `Handle(req)` 后持续 `stream.Recv()`，直到收到 `end: true` 的最后一帧 `Response`

### 4.1 统一请求体 (Request Envelope)

`Request` 的 protobuf schema（`engine/src/main/proto/idb_engine.proto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `string` | 请求唯一 ID |
| `category` | `enum Category` | `SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` / `VIEW` / `INDEX` / `FOREIGN_KEY` / `TRIGGER` |
| `action` | `enum Action` | `LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `DEBUG` / `CALL` / `RUN_EXPORT` / `RENAME` / `TRUNCATE` / `TEST_CONNECTION` / `SERVER_INFO` / `LIST_DRIVERS` |
| `connection` | `ConnectionConfig` | 见下表 |
| `body` | `oneof` | per-Category typed 消息（`schema_request`/`user_request`/.../`export_request`），见下方表格 |

> **注意**：`Action.EXPORT` 在 proto3 中与 `EXPORT` category 命名冲突，因此历史 wire 协议中的 `EXPORT` action 在新协议中重命名为 **`RUN_EXPORT`**（12），仍是 `EXPORT` category 的唯一合法 action。

**Per-Category Request 消息**（`Request.body` 选择项）：

| Category | Request body | 关键字段 |
|---|---|---|
| `SYSTEM` | `SystemRequest` | （无字段；INFO/TEST_CONNECTION/SERVER_INFO/LIST_DRIVERS 均无 payload） |
| `SCHEMA` | `SchemaRequest` | `list { level, database }` / `create { name, options }` / `delete { name }` |
| `USER` | `UserRequest` | `list { user, host }` / `create { user, password, host }` / `update { user, password, host, schema, privileges[], is_grant, table_name, with_grant_option }` / `delete { user, host }` / `grants { user, host }` |
| `TABLE` | `TableRequest` | `list { schema }` / `column_list { table_name, schema }` / `create { table_name, columns[], options, schema }` / `update { table_name, operation, column, column_name, schema }` / `get_ddl { table_name, schema }` / `rename { old_name, table_name, new_name, schema }` / `delete { table_name, schema }` / `truncate { table_name, schema }` |
| `DATA` | `DataRequest` | `list { table_name, page, page_size, where, order_by, schema }` / `create { table_name, values, schema }` / `update { table_name, changes, where, schema }` / `delete { table_name, where, schema }` / `generate { schema, tables[], lua_version }` |
| `SQL` | `SqlRequest` | `execute { sql, schema }` / `explain { sql, schema }` |
| `FUNCTION` | `FunctionRequest` | `list { schema }` / `info { name, schema }` / `get_ddl { name, schema }` / `create { ddl }` / `delete { name, routine_type, schema, if_exists, cascade }` / `call { name, routine_type, schema, args[] }` / `debug { name, schema }` / `update { ddl }` |
| `VIEW` | `ViewRequest` | `list { schema }` / `create { name, definition, schema }` / `delete { name, if_exists, schema }` / `get_ddl { name, schema }` |
| `INDEX` | `IndexRequest` | `list { table_name, schema }` / `create { table_name, index_name, columns[], unique, schema }` / `delete { index_name, table_name, schema }` |
| `FOREIGN_KEY` | `ForeignKeyRequest` | `list { table_name, schema }` / `create { table_name, fk_name, columns[], ref_table, ref_columns[], on_delete, on_update, schema }` / `delete { table_name, fk_name, schema }` |
| `TRIGGER` | `TriggerRequest` | `list { schema }` / `get_ddl { name, schema }` |
| `EXPORT` | `ExportRequest` | `run_export { sql, output_dir, file_name, format, table_name, fetch_size, stop_export_id }` |

**共享子消息**：

| 消息 | 字段 |
|---|---|
| `ColumnDef` | `name, type, size, nullable (optional, default true), is_primary_key, default_value, auto_increment, new_name` |
| `GenerateTable` | `script` |

`ConnectionConfig`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver` | `string` | `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite` |
| `host` | `string` | 数据库主机 |
| `port` | `int32` | 数据库端口 |
| `user` | `string` | 用户名 |
| `password` | `string` | 密码（参与 `toHashKey()` 连接池缓存 key） |
| `database` | `string` | 数据库名（PG 也可作为 schema 容器） |
| `schema` | `string` | 可选 — PG search_path 上下文；H2 视为 schema 名；MySQL 忽略 |
| `use_ssl` | `bool` | 是否启用 SSL |
| `properties` | `map<string,string>` | JDBC 扩展参数 / SSH 配置 |

### 4.2 统一响应体 (Response Envelope)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | `string` | — | 对应请求的 `id` |
| `success` | `bool` | `false` | 是否成功 |
| `error` | `string` | `""` | 错误信息；非空即视为错误响应 |
| `stream` | `bool` | `false` | 流式响应标记 |
| `end` | `bool` | `false` | 流式结束标记 |
| `body` | `oneof` | （空） | 业务结果 — per-Category typed 消息（`schema`/`user`/...） + 3 个流式帧（`data_row_frame`/`sql_row_frame`/`gen_progress_frame`） + `generate_terminal` |

**Per-Category Response 消息**（`Response.body` 单次响应选项）：

| Category | Response body | 关键字段 |
|---|---|---|
| `SCHEMA` | `SchemaResponse` | `list { level, database, items[] }` / `create { created }` / `delete { deleted }` |
| `USER` | `UserResponse` | `list { items[] }` / `create { created }` / `update { user, schema, table, action, with_grant_option }` / `delete { deleted }` / `grants { items[] }` |
| `TABLE` | `TableResponse` | `list { items[] }` / `columns { items[] }` / `create { created }` / `update { table_name, operation }` / `get_ddl { ddl }` / `rename { renamed, new_name }` / `delete { deleted }` / `truncate { truncated }` |
| `DATA` | `DataResponse` | `list { total, page, page_size, rows[] }` / `create { affected_rows }` / `update { affected_rows }` / `delete { affected_rows }` |
| `SQL` | `SqlResponse` | `execute { affected_rows }` / `explain { rows[] }` |
| `SYSTEM` | `SystemResponse` | `info { jvm_version, ..., memory { max, total, used, free }, uptime, pid }` / `test_connection { ok, driver, host, port, database, error }` / `server_info { version, catalog, current_database, mode, extras }` / `list_drivers { items[] }` |
| `FUNCTION` | `FunctionResponse` | `list { items[] }` / `info { info }` / `get_ddl { ddl }` / `create { success, message }` / `delete { success, message, name, routine_type }` / `call { result }` / `debug { items[] }` / `update { valid, message }` |
| `VIEW` | `ViewResponse` | `list { items[] }` / `create { created }` / `delete { deleted }` / `get_ddl { ddl }` |
| `INDEX` | `IndexResponse` | `list { items[] }` / `create { created, table_name }` / `delete { deleted }` |
| `FOREIGN_KEY` | `ForeignKeyResponse` | `list { items[] }` / `create { created, table_name }` / `delete { deleted }` |
| `TRIGGER` | `TriggerResponse` | `list { items[] }` / `get_ddl { ddl }` |
| `EXPORT` | `ExportResponse` | `progress { exported_rows, column_count, completed, file_path, error }` / `stop { stopped }` |

**流式帧类型**（`Response.body` 流式选项）：

| Frame | 字段 | 用途 |
|---|---|---|
| `DataRowFrame` | `total, page=0, page_size=1, row` | `DATA.LIST pageSize=0` 每行一帧 |
| `SqlSelectRowFrame` | `total=-1, page, page_size=1, row` | `SQL.EXECUTE SELECT` 每行一帧 |
| `GenerateProgressFrame` | `table, inserted, script_inserted, script_index, total_scripts, sql, data` | `DATA.GENERATE` 每条 INSERT 一帧 |
| `GenerateTerminalResponse` | `success, tables_processed` | `DATA.GENERATE` 终止帧（end=true） |
| `ExportProgressFrame` | （见 `EXPORT.progress`） | `EXPORT.RUN_EXPORT` 进度帧 |

**流式响应字段说明**：
- `stream: true` — 当前响应属于流式序列（一条请求产生多帧响应）
- `end: true` — 流式序列的最后一帧
- 普通（非流式）响应中 `stream` 和 `end` 均为 `false`

### 4.3 流式 vs 单次响应矩阵

| Category.Action | 响应模式 | 备注 |
|---|---|---|
| `DATA.LIST`（`pageSize == 0`） | **流式** | 每行一帧；JDBC 游标防 OOM；`end=true` 终止 |
| `SQL.EXECUTE`（SELECT） | **流式** | 每行一帧；PG 临时 `autoCommit=false` 启用服务端游标 |
| `DATA.GENERATE` | **流式** | 每条 INSERT 回报一次进度 |
| `EXPORT.RUN_EXPORT` | **流式** | 进度帧 throttled（每 1000 行或每 200ms） |
| 其它所有 `(Category, Action)` | 单次 | 一次性 Response |
| `SQL.EXPLAIN`（Action 15） | 单次 | 由 `SqlEngineHandler.explain` → `RequestDispatcher` 路由（v2.6 起打通） |

## 5. 功能模块详细设计 (Feature Modules)

采用 **方言抽象层 (Dialect Abstraction Layer) + SPI 插件动态加载** 设计模式：

- **DatabaseDialect SPI**（`api` 模块）：定义所有数据库特定操作的抽象方法 + 连接元数据扩展（v2.8）
- **MySQLDialect / PostgreSQLDialect / H2Dialect / DuckDBDialect（v2.7）/ SQLiteDialect（v2.8）**（独立插件模块）：具体方言实现
- **DialectLoader**（`engine` 模块）：启动时扫描 `dialects/` 目录，通过 `ServiceLoader<DatabaseDialect>` 自动发现并注册；提供 `getAllDialects()` 返回所有已加载方言供 `SYSTEM.LIST_DRIVERS` 路由使用

**关键事实**（v2.8）：五个方言均完整实现了 SPI 接口的**全部方法**（`listRoutines`、`getRoutineInfo`、`callRoutine`、`validateRoutineDDL`、`debugRoutine`、`createRoutine`、`dropRoutine` 等），不再有占位实现；并且**全部声明**各自连接元数据（`displayName` / `connectionType` / `requiresHost` / `defaultPort` / `capabilities` 等）。

> **Wire vs 业务层**：下面示例中的 JSON `payload` 字段展示的是**业务参数**的逻辑形状，便于理解业务参数。**实际 wire 上** 这些字段是强类型 per-Category protobuf 消息（`Request.body.schema_request.list.level` / `Request.body.schema_request.list.database` 等），handler 直接读取这些 typed 字段，不再经过任何 `JsonObject` 中间层。

### 5.1 架构管理 (Schema Management) — `category: "SCHEMA"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

**所有 Action 均需要 connection。**

#### LIST — 两级导航

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库 | 单元素 `[database]` |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace` 过滤 `pg_%` / `information_schema` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |

#### CREATE — 创建 Database / Schema

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✓ | schema 名称 |
| `options` | object | — | MySQL 支持 `charset` / `collate`；PG 忽略 |

#### DELETE — 删除 Database / Schema

```json
{"id":"r3","category":"SCHEMA","action":"DELETE","connection":{...},"payload":{"name":"old_db"}}
```

**Response data**：`{"deleted":"old_db"}`

### 5.2 用户权限 (User & Privilege) — `category: "USER"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `GRANTS`

#### LIST — 用户列表 或 用户权限列表

- payload 不含 `user` → 返回用户列表
- payload 含 `user` → 返回该用户的权限列表

#### UPDATE — 两种模式

**模式 1：修改密码**（payload 含 `password` 但无 `privileges`）

```json
{"id":"r7","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","password":"new_secret","host":"%"}}
// Response: {"user":"dev","action":"password_changed"}
```

**模式 2：授予/回收权限**（payload 含 `privileges`）

```json
// 授予
{"id":"r8","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["SELECT","INSERT","UPDATE"],"isGrant":true,"tableName":"users","withGrantOption":false}}
// Response: {"user":"dev","schema":"my_app_db","table":"users","withGrantOption":false,"action":"granted"}

// 回收
{"id":"r8b","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["DELETE"],"isGrant":false}}
// Response: {"user":"dev","schema":"my_app_db","action":"revoked"}
```

### 5.3 表结构元数据 (Table Metadata) — `category: "TABLE"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `GET_DDL` / `RENAME` / `TRUNCATE`

#### LIST — 表列表（payload 无 `tableName`）

```json
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{}}
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{"schema":"public"}}  // PG
// Response: [{"name":"users","type":"TABLE"}, {"name":"orders","type":"TABLE"}]
```

#### LIST — 列与主键（payload 含 `tableName`，自动路由到 `columnList`）

```json
{"id":"r10","category":"TABLE","action":"LIST","connection":{...},"payload":{"tableName":"users"}}
// Response: [{"name":"id","type":"INT","size":10,"nullable":false,"isPrimaryKey":true,"defaultValue":null}, ...]
```

#### CREATE — 创建表

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tableName` | string | ✓ | 表名 |
| `columns` | array | ✓ | 列定义数组 |
| `options` | object | — | MySQL: `engine`/`charset`/`collate`/`comment`；PG: `comment`（走 `COMMENT ON TABLE`） |
| `schema` | string | — | PG schema |

列定义（每个 column 对象）：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | ✓ | — | 列名 |
| `type` | string | ✓ | — | SQL 类型（如 `INT` / `VARCHAR` / `DECIMAL` / `TIMESTAMP`） |
| `size` | int | — | — | 类型尺寸（如 VARCHAR 长度） |
| `nullable` | bool | — | `true` | 是否可空 |
| `isPrimaryKey` | bool | — | `false` | 是否主键 |
| `defaultValue` | string | — | — | 默认值（字符串字面量，如 `"0.00"` / `"CURRENT_TIMESTAMP"`） |
| `autoIncrement` | bool | — | `false` | 自增主键（PG: `INT` → `SERIAL`，`BIGINT` → `BIGSERIAL`） |

#### UPDATE — 修改表结构（ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN）

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tableName` | string | ✓ | 目标表 |
| `operation` | string | ✓ | `ADD_COLUMN` / `DROP_COLUMN` / `MODIFY_COLUMN` |
| `schema` | string | — | PG schema |
| **ADD_COLUMN**：`column` | object | ✓ | 列定义（`name`, `type`, 可选 `size`/`nullable`/`defaultValue`） |
| **DROP_COLUMN**：`columnName` | string | ✓ | 要删除的列名 |
| **MODIFY_COLUMN**：`column` | object | ✓ | `name` 必填；`type` 或 `newName` 至少有其一；可选 `size`/`nullable`/`defaultValue` |

#### GET_DDL — 获取建表语句

MySQL 用 `SHOW CREATE TABLE`；PG 从 `information_schema` + `pg_catalog` 重建（含主键、UNIQUE、CHECK 约束及索引）；H2 列重建 + `TABLE_CONSTRAINTS` 过滤掉同名系统表。

### 5.4 表数据运维 (Table Data CRUD) — `category: "DATA"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE`（外加 `GENERATE` 造数，详见 §5.7）

#### LIST — 分页查询 / 流式查询

**分页模式**（`pageSize > 0`，默认 50）：

| payload 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `tableName` | string | ✓ | — | 表名 |
| `page` | int | — | 1 | 页码 |
| `pageSize` | int | — | 50 | 每页行数 |
| `where` | string | — | — | 原始 WHERE 片段（不含 `WHERE` 关键字） |
| `orderBy` | string | — | — | 原始 ORDER BY 片段（不含 `ORDER BY` 关键字） |
| `schema` | string | — | — | PG schema |

**流式模式**（`pageSize == 0`）：逐行流式返回，使用 JDBC 游标（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `fetchSize=100`），PostgreSQL 端临时关闭 `autoCommit` 启用服务端游标。

**LOB 列**（`BLOB` / `LONGTEXT` / `BYTEA` / `TEXT`）始终返回 `"[LOB Data]"`。

**安全校验**（方言层 `validateSqlFragment` / `validateOrderBy`）：
- **通用**：去除单引号内容后禁止分号 `;`、注释 `--` `/*`，禁止引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- **MySQL 额外**：ORDER BY 标识符允许反引号 `` `col` ``
- **PostgreSQL 额外**：ORDER BY 标识符允许双引号 `"col"`；额外禁止 `COPY`、`DO`

#### CREATE — 插入一行

```json
{"id":"r19","category":"DATA","action":"CREATE","connection":{...},"payload":{"tableName":"users","values":{"name":"Charlie","email":"charlie@example.com"}}}
// Response: {"affectedRows":1}
```

所有 `values` 值以字符串形式通过 `PreparedStatement.setString` 绑定；列类型由方言层 `listColumns` 解析后按类型 dispatch（详见 `DataHandler.bindTypedValue`：整数 → `setLong`，浮点 → `setDouble`，布尔 → `setBoolean`，日期/时间 → `setDate`/`setTime`/`setTimestamp`，二进制 → `setBytes`）。

### 5.5 原生 SQL 引擎 (Arbitrary SQL Engine) — `category: "SQL"`

**支持的 Action**：`EXECUTE` / `EXPLAIN`

#### EXECUTE — 接收任意 SQL

- **SELECT**：流式输出（每行一帧），`total: -1`，JDBC 游标防 OOM
- **非 SELECT**（INSERT/UPDATE/DELETE/DDL）：单次响应 `{"affectedRows": N}`
- **PostgreSQL schema 上下文**：payload 可选 `schema` 字段，引擎在执行 SQL 前自动 `SET search_path TO <schema>`
- **LOB 列**：`BLOB`/`LONGTEXT`/`BYTEA`/`TEXT` 返回 `"[LOB Data]"`

> **注意**：`Action.EXPLAIN`（15）由 `SqlEngineHandler.explain` → `RequestDispatcher` 路由（v2.6 起打通）；调用成功返回单次 `SqlResponse.explain` 响应。也可用 `SQL.EXECUTE` 直接提交 `EXPLAIN <sql>` 语句。

### 5.6 系统信息 (System Info) — `category: "SYSTEM"`

**支持的 Action**：`INFO` / `TEST_CONNECTION` / `SERVER_INFO` / `LIST_DRIVERS`

#### INFO — JVM 运行时信息（无需 connection，字段被忽略）

```
{
  "jvmVersion": "21.0.2",
  "jvmVendor": "Oracle Corporation",
  "jvmName": "OpenJDK 64-Bit Server VM",
  "osName": "Windows 11", "osArch": "amd64", "osVersion": "10.0",
  "availableProcessors": 16,
  "memory": {"max":4294967296,"total":268435456,"used":134217728,"free":134217728},
  "uptime": 120000, "pid": 12345
}
```

`memory` 各字段单位为字节（Bytes）。

#### TEST_CONNECTION — 测试连接（成功返回 `ok=true`，失败返回 `ok=false`，不抛异常）

成功 Response: `{"ok":true,"driver":"Mysql","host":"127.0.0.1","port":3306,"database":"mysql"}`；失败 Response: `{"ok":false,"error":"Communications link failure..."}`

#### LIST_DRIVERS — 枚举已加载方言的连接元数据（v2.8 起新增，无需 connection）

返回所有 `DialectLoader` 注册的方言的连接配置元数据，**供前端动态渲染"新建连接"表单**——不需要硬编码 driver / port / 是否需要 user 等信息。

`DialectInfo` 字段（proto3 `message DialectInfo`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver_name` | `string` | 后端 driver 名（如 `"Mysql"` / `"Sqlite"`），用于 `ConnectionConfig.driver` |
| `display_name` | `string` | 前端展示用名（如 `"MySQL"` / `"SQLite (Embedded)"`） |
| `jdbc_driver_class_name` | `string` | JDBC driver 类全名（如 `"com.mysql.cj.jdbc.Driver"`） |
| `jdbc_url_example` | `string` | 示例 JDBC URL，用于前端 placeholder |
| `connection_type` | `string` | `CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY` |
| `requires_host` | `bool` | 是否需要 `host` 字段 |
| `requires_port` | `bool` | 是否需要 `port` 字段 |
| `default_port` | `int32` | 默认端口（0 = 无） |
| `supports_user` | `bool` | 是否需要 `user` / `password` |
| `supports_password` | `bool` | 是否需要 `password` |
| `supports_schema` | `bool` | 是否需要 `schema` 字段（PG/H2 = true） |
| `supports_cross_database` | `bool` | 是否支持多 database 切换（PG = true） |
| `capabilities` | `repeated string` | 方言能力标签（`USERS` / `VIEWS` / `INDEXES` / `ROUTINES` / `TRIGGERS` / `FOREIGN_KEYS` / `EXPORT` / `EMBEDDED_MODE` / `MULTI_SCHEMA` / `CROSS_DATABASE` / `DDL_TRANSACTION` / `PRIVILEGES`） |

**5 个方言的元数据快照**（按 `driverName` 字典序升序）：

| driver_name | display_name | connection_type | default_port | requiresHost | supportsUser | supportsSchema | 关键 capabilities |
|---|---|---|---|---|---|---|---|
| `Duckdb` | `DuckDB (Embedded OLAP)` | `EMBEDDED` | 0 | ✗ | ✗ | ✓ | `VIEWS, INDEXES, FOREIGN_KEYS, MULTI_SCHEMA, EXPORT, EMBEDDED_MODE` |
| `H2` | `H2 (In-Memory)` | `IN_MEMORY` | 0 | ✗ | ✗ | ✓ | `+ EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`) |
| `Mysql` | `MySQL` | `CLIENT_SERVER` | 3306 | ✓ | ✓ | ✗ | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, TRIGGERS, EXPORT, DDL_TRANSACTION` |
| `Postgresql` | `PostgreSQL` | `CLIENT_SERVER` | 5432 | ✓ | ✓ | ✓ | `+ MULTI_SCHEMA, CROSS_DATABASE` |
| `Sqlite` | `SQLite (Embedded)` | `FILE_BASED` | 0 | ✗ | ✗ | ✗ | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`/`ROUTINES`) |

返回顺序按 `driverName` 字典序升序，确保前端渲染顺序稳定。

### 5.7 造数引擎 (Data Generation) — `category: "DATA"`, `action: "GENERATE"`

基于嵌入式 Lua 脚本引擎的造数功能，支持单表或多表按序造数（自动处理外键依赖）。

**核心机制**：
- 每张表创建独立的 Lua 虚拟机，`insert()` 调用时**逐条写库**（`executeUpdate` 单条 INSERT），不在内存中积累数据
- 每条插入后实时流式回报进度（`stream: true`）
- 表按 `tables` 数组顺序执行
- 通过 `RETURN_GENERATED_KEYS` 获取自增主键

**Lua 沙箱**：禁用 `os`、`io`、`debug`、`package`、`require`、`loadfile`、`dofile`、`loadstring`、`load`、`rawget`、`rawset`、`rawequal`、`setfenv`、`getfenv`、`newproxy` 等危险模块。

**Lua 版本**（通过 `payload.luaVersion` 选择，默认 `"luajit"`，支持 `"5.1"` / `"5.2"` / `"5.3"` / `"5.4"` / `"5.5"` 及短名 `"lua51"` 等）。

**Lua 内置辅助函数**：

| 函数 | 说明 |
|---|---|
| `insert(tableName, rowTable)` | 收集一行待插入数据，立即执行 INSERT。列值支持 `string`/`number`/`boolean`/`nil`/`LocalDate`/`LocalDateTime`/`LocalTime` |
| `lastId()` | 获取上一张表最后插入的自增 ID |
| `random_int(min, max)` | 随机整数 `[min, max]` |
| `random_float(min, max)` | 随机浮点数 `[min, max)` |
| `random_string(length)` | 指定长度的随机字母数字字符串（`a-zA-Z0-9`，长度 clamp 到 `[1, 256]`） |
| `random_date(start, end)` | 两个日期之间的随机日期（参数 `YYYY-MM-DD`，返回 `LocalDate`） |
| `random_datetime(start, end)` | 两个日期之间的随机时间戳（参数 `YYYY-MM-DD`，返回 `LocalDateTime`） |
| `random_time()` | 随机 `LocalTime`（`HH:mm:ss`） |
| `random_email()` | `user_<random>@example.com` |
| `random_phone()` | 11 位手机号 |
| `random_name()` | 随机姓名（中文 + 英文姓名池） |
| `random_enum(...)` | 从可变参数中随机选取一个值 |
| `random_uuid()` | 随机 UUID 字符串 |

> **注意**：嵌套 Lua table 通过 `insert()` 传递时会丢失（`readLuaTable` 中 `isTable -> null`），列值必须使用扁平 string / number / boolean / nil / java.time 类型。

### 5.8 函数与存储过程管理 (Routine Management) — `category: "FUNCTION"`

**支持的 Action**：`LIST` / `INFO` / `GET_DDL` / `CREATE` / `DELETE` / `CALL` / `DEBUG` / `UPDATE`（语法验证）

**所有三个方言均完整实现** Routine 管理（MySQL / PostgreSQL / H2 都通过 `INFORMATION_SCHEMA.ROUTINES` + `PARAMETERS` + `TRIGGERS` 查询）。

#### DELETE — 删除函数/存储过程

| payload 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | ✓ | — | 名称 |
| `routineType` | string | ✓ | — | `FUNCTION` / `PROCEDURE` |
| `schema` | string | — | — | schema 名 |
| `ifExists` | bool | — | `false` | `IF EXISTS` |
| `cascade` | bool | — | `false` | `CASCADE` |

### 5.9 视图管理 (View Management) — `category: "VIEW"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE` / `GET_DDL`

### 5.10 索引管理 (Index Management) — `category: "INDEX"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

### 5.11 外键管理 (Foreign Key Management) — `category: "FOREIGN_KEY"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

### 5.12 触发器管理 (Trigger Management) — `category: "TRIGGER"`

**支持的 Action**：`LIST` / `GET_DDL`

> Trigger 的创建/删除通过 `category=FUNCTION, routineType="TRIGGER"` 完成（见 §5.8）。

### 5.13 数据导出 (Data Export) — `category: "EXPORT"`, `action: "RUN_EXPORT"`

基于自定义 SQL 的 5 种格式数据导出，**独立子进程运行**，全链路流式处理。

> **子进程隔离**：导出任务运行在独立的 JVM 子进程中（通过 `ExportProcessManager` 管理），即使导出千万级数据也不会导致主进程 OOM。

**支持格式**：

| 格式 | 文件扩展名 | 依赖 | 说明 |
|---|---|---|---|
| `CSV` | .csv | 零依赖 | UTF-8 BOM 头，自动处理字段转义 |
| `JSON_LINES` | .jsonl | kotlinx-serialization | 每行一个独立 JSON 对象 |
| `SQL_INSERT` | .sql | 零依赖 | 逐行生成 INSERT 语句（**必须**传 `tableName`） |
| `EXCEL` | .xlsx | POI SXSSF 流式 | 100 万数据行/Sheet 自动分页，1000 行内存窗口 |
| `PARQUET` | .parquet | parquet-hadoop | 动态 Schema，类型智能推断 |

**请求 payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sql` | string | ✓ | 自定义 SELECT SQL |
| `outputDir` | string | ✓ | 输出目录路径（不存在会自动创建） |
| `fileName` | string | ✓ | 文件名前缀（不含扩展名） |
| `format` | string | ✓ | `CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET` |
| `tableName` | string | 条件 | `SQL_INSERT` 格式必填，用于生成 INSERT 语句前缀 |
| `fetchSize` | int | — | JDBC 游标拉取批次大小，默认 1000 |
| `stopExportId` | string | — | 传入则停止指定 ID 的导出任务（见下文"停止导出"） |

**流式进度响应**（每 1000 行或每 200ms 一帧）：
```json
{"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":0,"columnCount":5,"completed":false,"filePath":null,"error":null}}
{"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false}}
...
{"id":"r48","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"C:\\Users\\langb\\Desktop\\users_2024.csv","error":null}}
```

**MySQL 特殊配置**：MySQL 流式读取需 `fetchSize = Integer.MIN_VALUE` 启用服务端流式游标（引擎自动处理）。
**PostgreSQL 特殊配置**：自动临时关闭 `autoCommit` 启用服务端游标，导出完成后自动恢复。

**停止导出**：在新 EXPORT 请求中指定 `stopExportId` 即可取消对应的导出任务：

```json
{"id":"r48stop","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"stopExportId":"r48"}}
// Response: {"stopped":"r48"}
```

被取消的导出任务返回 `{"success": false, "error": "Export cancelled by user"}`。

### 5.14 SQLite 嵌入式关系型数据库 (v2.8 新增) — `driver: "Sqlite"`

> 本节描述 SQLite 方言的引擎层契约 — 具体方言实现细节见 `dialect-sqlite/README.md`（由另一个 agent 编写）。

**支持的 Action**：与 MySQL/PG 对齐（SCHEMA / TABLE / DATA / SQL / VIEW / INDEX / FOREIGN_KEY / FUNCTION / TRIGGER / EXPORT / SYSTEM），**不暴露** USER/GRANTS（抛 `UnsupportedOperationException`）；FUNCTION/TRIGGER 因 SQLite 无过程/函数概念而抛 `UnsupportedOperationException`。

#### 连接约定

`host` / `port` / `user` / `password` / `use_ssl` 字段全部忽略——SQLite 是嵌入式方言，**仅由 `database` 字段承载路径**：

| `database` 取值 | 实际 JDBC URL | 用途 |
|---|---|---|
| `""` 或 `":memory:"` | `jdbc:sqlite::memory:` | 进程内内存数据库（每连接私有） |
| `"/path/to/data.db"` | `jdbc:sqlite:/path/to/data.db` | 本地 SQLite 文件（多连接共享，文件锁） |

> 注意：SQLite 内存模式每连接私有，PoolManager 多连接不共享同一实例。集成测试一律走**临时 `.db` 文件**。

#### 方言特性

- **默认 schema**：SQLite 只有内置 `main` / `temp` schema，业务层不暴露 schema 字段
- **自增主键**：`INTEGER PRIMARY KEY AUTOINCREMENT`（必须 `INTEGER` 类型，必须 inline 在列定义里）。**`TableHandler.create` 检测到 `autoIncrementColumns.isNotEmpty()` 时不再追加表级 `PRIMARY KEY (cols)`，避免 SQLite "more than one primary key" 错误**
- **多 database**：`ATTACH DATABASE '<path>' AS <alias>`；删除走 `DETACH`
- **DDL 重建**：`getCreateTableDDL` 直接读取 `sqlite_master.sql` 原文（用户当时建的原样）
- **修改列**：仅支持 `RENAME COLUMN`（SQLite 不支持 `ALTER COLUMN` 类型/默认值/可空性变更）
- **FK 管理**：SQLite 不支持 `ALTER TABLE ADD/DROP CONSTRAINT`；通过 **table-rebuild** 路径（CREATE temp AS SELECT → DROP → CREATE with FK → INSERT → DROP temp）
- **视图**：直接读 `sqlite_master.sql`；DROP/GET_DDL 完整支持
- **索引**：`sqlite_master` + 解析 DDL 提取列名 + UNIQUE 判定
- **TRUNCATE**：用 `DELETE FROM <table>` + `DELETE FROM sqlite_sequence WHERE name = '<table>` 模拟

#### 不支持的能力

| 操作 | 抛出的异常 |
|---|---|
| `USER.LIST` / `USER.CREATE` / `USER.UPDATE` / `USER.DELETE` / `USER.GRANTS` | `UnsupportedOperationException("Sqlite 不支持...")` |
| `FUNCTION.*`（routines 概念） | `UnsupportedOperationException("Sqlite 不支持函数/存储过程")` |
| `TRIGGER.LIST` / `TRIGGER.GET_DDL` | `UnsupportedOperationException`（SPI 默认） |

#### SQL 危险关键词（方言层 `validateSqlFragment` 禁用）

通用集合 + SQLite 特有：`ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` / `VACUUM` / `REINDEX`。

#### DialectInfo 元数据

| 字段 | 取值 |
|---|---|
| `driver_name` | `"Sqlite"` |
| `display_name` | `"SQLite (Embedded)"` |
| `connection_type` | `FILE_BASED` |
| `requires_host` | `false` |
| `requires_port` | `false` |
| `default_port` | `0` |
| `supports_user` | `false` |
| `supports_password` | `false` |
| `supports_schema` | `false` |
| `supports_cross_database` | `false` |
| `capabilities` | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` |

> 注：其他方言的引擎层契约见对应章节：
> - DuckDB §5.7/5.13（嵌入式 OLAP，5 种数据源格式 + 自增 PK 走 `SEQUENCE + DEFAULT nextval`）
> - DuckDB/SQLite FK table-rebuild 通用策略（方言无 `ALTER TABLE ADD/DROP CONSTRAINT`）
> - 通用方言元数据快照见 §5.6 `LIST_DRIVERS`

## 6. 安全与健壮性保障 (Security & Reliability)

1. **防进程孤儿 (Graceful Shutdown)**：引擎由 `IdbEngineServer` 阻塞在 `server.awaitTermination()`；JVM Shutdown Hook 在终止时调用 `transport.cleanup()` + `PoolManager.closeAll()` + `DriverLoader.closeAll()` + `DialectLoader.closeAll()`。

2. **连接超时管控**：HikariCP `connectionTimeout = 5000`（5 秒）。

3. **全局异常捕获**：`RequestDispatcher.dispatch` 顶层 `try/catch`（v2.9 起外置为 `.catch{}` operator 以正确传播 `AbortFlowException`），所有 JDBC `SQLException` 提取 `e.message` 包装为 `Response(success=false, error=...)`。**绝对禁止**应用因未捕获异常而崩溃退出。

4. **SQL 注入防护**：DATA 模块强制 `PreparedStatement` 绑定参数；`where` / `orderBy` 原始片段由方言层 `validateSqlFragment` / `validateOrderBy` 校验。SQL 模块的 EXECUTE 接受原始 SQL，由调用方负责校验来源合法性。

## 7. Implementation Status — Engine Tests

**`engine:test` — 174 测试全通过（0 失败 / 0 错误；v2.9 新增 IdbEngineDirectTest 4 项）**：

| 测试套件 | 数量 | 范围 |
|---|---|---|
| `ipc/IpcConfigTest` | 20 | CLI 参数解析 + 自动平台检测 + 错误路径（1 个 Windows-only 跳过） |
| `ipc/IpcTransportTest` | 7 | SPI 各实现构造 |
| `ipc/TcpIpcTransportIntegrationTest` | 1 | TCP loopback + gRPC round-trip |
| `ipc/UnixSocketIpcTransportIntegrationTest` | 2 | UDS + gRPC round-trip（`@EnabledOnOs(LINUX, MAC, FREEBSD)`） |
| `ipc/NamedPipeIpcTransportIntegrationTest` | 2 | 客户端 channel + serverBuilder 限制 |
| `pool/PoolManagerTest` | 11 | SHA-256 key + closeAll |
| `loader/DialectLoaderTest` | 5 | SPI 自动发现 |
| `integration/*HandlerIntegrationTest` | 60 | 11 个 handler × H2Fixture（typed proto builders 直接调 handler） |
| `integration/TypedRequestEnvelopeIntegrationTest` | 7 | 端到端 typed Request → dispatcher → typed Response |
| `integration/UserGrantsIntegrationTest` | 2 | USER.GRANTS 路由，H2 限制场景 |
| `integration/DataGenerateIntegrationTest` | 2 | DATA.GENERATE 流式进度 + 错误路径 |
| `integration/FunctionGetDdlIntegrationTest` | 2 | FUNCTION.GET_DDL dispatcher 路由 + H2 限制 |
| `integration/SqlExplainRouteIntegrationTest` | 2 | SQL.EXPLAIN 端到端路由（v2.6 之前未实现） |
| `integration/EnvelopeOptionsIntegrationTest` | 4 | dryRun / timeoutMs envelope 跨切面 |
| `integration/DuckDBHandlerIntegrationTest` | 27 | DuckDB 端到端：SCHEMA/TABLE/DATA/SQL/VIEW/INDEX/FK/FUNCTION/SYSTEM/LOCAL FILES/EXPORT（v2.7 新增） |
| `integration/SQLiteHandlerIntegrationTest` | 7 | SQLite 端到端：SCHEMA/TABLE/DATA/VIEW/INDEX/SYSTEM（v2.8 新增） |
| `integration/SystemListDriversIntegrationTest` | 8 | LIST_DRIVERS 元数据枚举 + 5 方言分别验证（v2.8 新增） |
| `integration/IdbEngineDirectTest` | 4 | Direct 模式 facade 契约测试：非流式 / `invoke` 便捷 / 错误传播 / bootstrap 幂等（v2.9 新增） |
| **合计** | **174** | — |

> **方言插件测试**（位于 `dialect-*/test/`，不属于 `engine:test`）：`dialect-h2` 63 项 / `dialect-duckdb` 81 项（v2.7 新增）/ `dialect-sqlite` 62 项（v2.8 新增）。完整跨模块统计：**380 测试全通过**（1 个 Windows-only skip）。