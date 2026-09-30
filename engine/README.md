# IDB Engine — 数据库管理后端引擎

一个使用 Kotlin 编写的**无头**、**跨平台**的数据库管理引擎。**v2.9 起支持双模式架构**：

| 模式 | 入口 | 场景 |
|---|---|---|
| **gRPC 模式**（默认，向后兼容） | gRPC 服务端 over IPC transport (TCP / UDS / pipe) | 跨进程、跨语言客户端、远程调试、子进程隔离 |
| **Direct 直接模式**（v2.9 新增） | `IdbEngine().handle(request)` Kotlin facade | KMP Desktop Compose UI（同 JVM 库集成，零开销） |

5 个可插拔方言：MySQL、PostgreSQL、H2、DuckDB、SQLite（v2.8 新增）。

> **当前版本：v2.16**
> - **查询取消 `SYSTEM.CANCEL`（v2.16，Action = 20）** —— 协程取消**无法**中断阻塞的 JDBC 调用（`rs.next()` 跑在 `Dispatchers.IO` 线程上，取消上游 Flow 只是让协程挂起，数据库里那条语句照跑），因此新增按请求 id 对**正在执行**的 `Statement` 调 `Statement.cancel()` 的能力；被取消的请求由其自身的异常路径收口为 `success=false, error="cancelled"` 的终止帧（携带原请求 id）。多语句脚本按 `"<requestId>#<index>"` 逐条登记，`cancel` 精确匹配失败后会按 `requestId#` 前缀回退命中，因此调用方**只需传自己发的 `Request.id`**
> - **数据导入 `IMPORT.RUN_IMPORT`（v2.16，Category `IMPORT` = 14，Action = 21）** —— 与 EXPORT 对称的读方向，把本地 **CSV / JSON_LINES** 文件批量插入目标表；运行在**主进程**（无 POI / Parquet / Hadoop 负担），正因为如此批次语句才能登记到 `StatementRegistry`，`SYSTEM.CANCEL` 可立即打断正在执行的 `executeBatch()`。打开文件时会**剥离 UTF-8 BOM**（`ImportSourceFactory.BomStrippingInputStream`）—— 本项目的 `CsvWriter` 导出时写 BOM，导入侧不剥会让首个列名变成 `"\uFEFFid"`，导致「自己导出的文件自己导不回来」
> - **事务会话 `SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`（v2.16，Action = 22 / 23 / 24 / 25）** —— `Request.session_id` 留空 = 无事务，**行为与 v2.15 完全一致**（每条语句独立提交），旧调用方零改动；非空时 `BEGIN` 为会话**钉住一条连接**并置 `autocommit=false`，同一 `session_id` 的 `DATA.*` 写操作与 `SQL.EXECUTE` DML 全部落在这条连接上，直到 `COMMIT` / `ROLLBACK`
> - **多语句脚本（v2.16，`SQL.EXECUTE` 的 `multi_statement = true`）** —— 词法分句器 `SqlScriptSplitter` 只在**顶层** `;` 切分（跳过字符串 / 引号标识符 / 注释 / PostgreSQL dollar-quoting 内部的分号），按序执行、**遇错即停**，逐条结果回填 `SqlExecuteResponse.statements`
> - **调用层抽象（v2.15）** —— proto 契约与 `EngineClient` 接口下沉到新模块 **`:engine-protocol`**（`engine` 通过 `api(project(":engine-protocol"))` 依赖它）；新增 **`:engine-grpc-client`**，以 gRPC 提供 `EngineClient` 的跨进程实现。调用方只面向 `EngineClient` 编程，**不感知引擎在同进程还是跨进程** → 参见 [`:engine-protocol`](../engine-protocol/README.md) 与 [`:engine-grpc-client`](../engine-grpc-client/README.md)
> - **`SYSTEM.DISCONNECT`（v2.15，Action = 19）** —— 远端调用方终于能显式释放引擎侧连接池；幂等（第二次调用 `closed=false`），且因为「释放池」有副作用被列入 `writeActions`，`dryRun=true` 时短路
> - **`IpcConfig.fromArgs` 修复（v2.15）** —— `--mode <value>` 现在被跳过（由 `IdbEngineServer.parseMode` 单独解析），此前 `java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051` 会以 `Unknown argument: '--mode'` 失败
> - **619 个测试全通过（277 engine + 63 H2 + 81 DuckDB + 62 SQLite + 97 shared + 21 engine-grpc-client + 18 desktopApp；1 个 Windows-only `IpcConfigTest` 用例跳过）**
> - **双模式架构**：默认 `--mode grpc` 启动 gRPC server；`--mode direct` 启动 standalone direct 模式；KMP Desktop 通过 `implementation(project(":engine"))` 走 library 直调路径
> - **`IdbEngine` —— `EngineClient` 的同进程实现（v2.9）** —— `com.kxxnzstdsw.engine.IdbEngine`，KMP Desktop 与引擎共享同一个 JVM，直接调用 `engine.handle(request): Flow<Response>`，零 gRPC channel、零 IPC、零子进程；与 gRPC stub `Flow<Response>` 语义完全一致（含 `stream`/`end` 流式分帧、envelope options `traceId`/`dryRun`/`timeoutMs`）。`invoke` 与 `testConnection(jdbcUrl, user, password)` 是接口上的 default 方法，不在类里
> - **`IdbEngineImpl.handle` 薄壳化（v2.9）** —— gRPC server 重构为直接桥接 `IdbEngine.handle`，两条路径共享 `RequestDispatcher` 与全部 envelope options 语义
> - **`--mode <grpc|direct>` CLI（v2.9）** —— `IdbEngineServer` 新增 mode 选择；`direct` 模式仅 bootstrap drivers/dialects 后阻塞主线程，供 shell 测试或守护进程使用
> - **`RequestDispatcher.dispatch` catch 移到 `.catch{}` operator（v2.9）** —— 修复 `AbortFlowException`（kotlinx-coroutines 内部异常，下游取消时上游 emit 抛出）被 `flow{}` 内部 try/catch 错误捕获并再次 emit 而触发的 *"Flow exception transparency violated"* 异常；让 direct 调用方能正常用 `first()` / `takeWhile` 等短路算子
> - **KMP Desktop 端集成** —— `desktopApp/build.gradle.kts` 新增 `implementation(project(":engine"))`，Compose UI 与引擎共享方言 / 驱动 / 连接池生命周期
> - **gRPC 1.83 + grpc-kotlin 协程服务端** —— 服务端继承 `IdbEngineCoroutineImplBase`（`suspend handle()` → `Flow<Response>`）；proto 工具链锁定：`protoc 3.25.5` + `protoc-gen-grpc-java 1.68.0` + `protoc-gen-grpc-kotlin 1.4.1`；`protobuf-kotlin-lite` 4.35.1
> - **端到端强类型 Handler** —— 无 `JsonObject` 编解码；dispatcher 将 typed per-Category proto 路由到 typed handler 方法，返回 typed `<Category><Action>Response` 消息
> - **业务层 Kotlin DSL（v2.5）** —— 13 个 handler + `RequestDispatcher` + 11 个集成测试全部使用 protoc-gen-grpc-kotlin + protobuf-kotlin-lite 生成的 DSL builder（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`）；仅 `google.protobuf.Value`（Well-Known Type，无生成 DSL）仍使用 `Value.newBuilder()`
> - **强类型列表项 envelope** —— `TableListItem` / `ViewListItem` / `IndexListItem` / `ForeignKeyListItem` / `TriggerListItem` / `FunctionListItem` / `FunctionDebugItem` / `UserGrantItem` / typed `Row` 包装动态行；仅真正方言差异显著的 item shape（USER.LIST 重载、FUNCTION.CALL/INFO、SYSTEM.SERVER_INFO extras）保留 `google.protobuf.Value`
> - **跨切面请求选项（v2.6）** —— `Request.options { traceId, dryRun, timeoutMs }` 统一应用到所有 (Category, Action) 路由；`traceId` 通过 SLF4J MDC 透传；`dryRun=true` 时 write action 直接短路返回 success（不调用 handler、不修改数据库）；`timeoutMs > 0` 时用 `withTimeoutOrNull` 包 handler 调用，超时返回 `success=false, error="timeout"`
> - **`timeoutMs` 覆盖流式路由** —— 流式分支（`SQL.EXECUTE` / `DATA.LIST(pageSize=0)` / `DATA.GENERATE` / `EXPORT.RUN_EXPORT` / `IMPORT.RUN_IMPORT`）同样受 `timeoutMs` 约束。超时时先经 `StatementRegistry` 对运行中的目标发取消（协程取消对阻塞 JDBC 无效），再在 `CANCEL_GRACE_MS`（5s）宽限后强制收口为 `success=false, stream=true, end=true` 的终止帧
> - **表驱动 dispatcher（v2.6）** —— `RequestDispatcher` 用单个 typed `routes` map（`Pair<Category, Action>` → `Route{invoke, wrap}`）替代原来的 9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` `when` 分支；新增 (Category, Action) 仅需一个 map 条目；消除静默 `else -> {}` 兜底（不可能走到 —— 表查不到时直接抛 `UnsupportedOperationException`）；`SQL.EXPLAIN` 路由打通（之前 handler 存在但 dispatcher 从未路由）
> - **DuckDB 方言（v2.7）** —— 第 4 个方言插件，面向**本地嵌入式 OLAP** 场景（内存 / `.duckdb` / `.csv` / `.parquet` / `.json` / `.xlsx` via POI 预转换）；driver 名 `Duckdb`（JDBC `org.duckdb.DuckDBDriver` 1.5.5.1）；`host`/`port` 完全忽略，`database` 字段即路径；自增主键走 `SEQUENCE + DEFAULT nextval + 表级 PRIMARY KEY`（DuckDB 拒绝 `IDENTITY + 表级 PK` 组合，也不支持 `INTEGER PRIMARY KEY` ROWID 自填充）；FK 走 table-rebuild（DuckDB 无 `ALTER TABLE ADD/DROP CONSTRAINT`）；USER / PRIVILEGE / TRIGGER 抛 `UnsupportedOperationException`
> - **SQLite 方言（v2.8）** —— 第 5 个方言插件，面向**本地嵌入式关系型**场景（`:memory:` / `.db` 文件）；driver 名 `Sqlite`（JDBC `org.sqlite.JDBC` 3.46.1.3）；`host`/`port`/`user`/`password` 全部忽略，`database` 字段即路径；自增主键走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`（`TableHandler.create` 检测到自增 PK 时跳过表级 `PRIMARY KEY` 子句，避免 SQLite "more than one primary key"）；FK 走 table-rebuild（SQLite 无 `ALTER TABLE ADD/DROP CONSTRAINT`）；`MODIFY_COLUMN` 仅支持 RENAME（无 ALTER COLUMN）；`TRUNCATE` 用 `DELETE FROM` + `sqlite_sequence` 重置模拟；多 database 走 `ATTACH/DETACH`；USER / PRIVILEGE / TRIGGER / FUNCTION（routines 概念）抛 `UnsupportedOperationException`
> - **SPI 连接元数据扩展（v2.8）** —— `DatabaseDialect` 新增 11 个属性（`displayName` / `connectionType` / `requiresHost` / `requiresPort` / `defaultPort` / `supportsUser` / `supportsPassword` / `supportsSchema` / `supportsCrossDatabase` / `jdbcUrlExample` / `capabilities`），全部带默认实现（**完全向后兼容**）；新增 `ConnectionType` 枚举（`CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY`）+ `DialectCapability` 枚举（12 个能力标签）；5 个方言全部声明各自元数据
> - **`SYSTEM.LIST_DRIVERS` action（v2.8）** —— 新增 action 18 枚举所有已加载方言，返回 `repeated DialectInfo`（`driver_name` / `display_name` / `jdbc_driver_class_name` / `jdbc_url_example` / `connection_type` / `requires_host` / `requires_port` / `default_port` / `supports_user` / `supports_password` / `supports_schema` / `supports_cross_database` / `capabilities[]`），供前端**动态渲染"新建连接"表单**而无需硬编码 driver/port/user 需求；`DialectLoader.getAllDialects()` 提供枚举入口；返回顺序按 `driverName` 字典序升序

---

## 项目结构

```
idb_engine/
├── api/                  公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── dialect-mysql/        MySQL 方言插件 JAR
├── dialect-postgresql/   PostgreSQL 方言插件 JAR
├── dialect-h2/           H2 方言插件 JAR（嵌入式数据库 + 测试）
├── dialect-duckdb/       DuckDB 方言插件 JAR（v2.7 新增 — 本地嵌入式 OLAP）
├── dialect-sqlite/       SQLite 方言插件 JAR（v2.8 新增 — 本地嵌入式关系型）
├── engine-protocol/     proto 契约（idb_engine.proto + idb_export.proto）与 `EngineClient` 接口（v2.15 从 :engine 移出）
├── engine-grpc-client/  `EngineClient` 的跨进程 gRPC 实现 `GrpcEngineClient`（v2.15 新增；仅依赖 :engine-protocol）
└── engine/               主引擎
    ├── engine/           `IdbEngine` —— `EngineClient` 的同进程实现；**`StatementRegistry.kt`（v2.16）** 运行中请求 → `Statement` 注册表
    ├── server/           gRPC 服务端（IdbEngineServer + IdbEngineImpl）
    ├── ipc/              跨平台 IPC 传输 SPI（TCP / UDS / Named Pipe）
    ├── dispatcher/       Request → handler 路由（按 Category.Action 分发）
    ├── pool/             HikariCP 连接池管理（SHA-256 key 缓存）；**`TransactionManager.kt`（v2.16）** 事务会话（固定连接 + COMMIT/ROLLBACK）
    ├── importer/         数据导入读取器（v2.16 新增 — CSV / JSON_LINES → 目标表；`ImportSource` / `CsvReader` / `JsonLinesReader` / `ImportFormat` / `ImportSourceFactory`）
    ├── export/           数据导出（独立 JVM 子进程）
    ├── handlers/         14 个业务 handler（v2.16 新增 `ImportHandler`）
    └── loader/           ServiceLoader 动态加载 drivers/ + dialects/
```

> **v2.15 —— 调用层抽象**：proto 契约（`idb_engine.proto` / `idb_export.proto`）与 `EngineClient` 接口现在位于 **`:engine-protocol`**（[`engine-protocol/README.md`](../engine-protocol/README.md)），`:engine` 通过 `api(project(":engine-protocol"))` 依赖它并只保留 IPC transport 依赖；同一接口的**跨进程 gRPC 实现** `GrpcEngineClient` 位于 **`:engine-grpc-client`**（[`engine-grpc-client/README.md`](../engine-grpc-client/README.md)），它只依赖 `:engine-protocol`、不依赖 `:engine`。

---

## 构建与运行

### 构建

```bash
./gradlew engine:jar
```

产物位于 `engine/build/libs/`：
```
idb-engine.jar          主引擎瘦包（Main-Class: com.kxxnzstdsw.MainKt）
libs/                   运行时依赖（gRPC、HikariCP、LuaJIT、POI、Parquet…）
drivers/                JDBC 驱动（mysql-connector-j / postgresql / h2 / duckdb_jdbc / sqlite-jdbc）
dialects/               方言插件（idb-dialect-{mysql,postgresql,h2,duckdb,sqlite}.jar，5 个 SPI 动态加载）
```

> **坏插件不阻断启动** —— `drivers/` / `dialects/` 按 **JAR 逐个** 发现。某个 JAR 的
> `META-INF/services` 声明了无法加载的类时，`ServiceLoader` 迭代器抛出的
> `ServiceConfigurationError` 会被捕获并只跳过该 JAR（告警日志列出文件名），
> 其余插件照常注册。`IdbEngine.bootstrap` 也只在加载**成功后**才置幂等标志 ——
> 首次失败后再次调用会重试，不会把引擎永久留在「未初始化」状态。

### 运行

```bash
# TCP loopback（默认 127.0.0.1:50051，POSIX 上自动 fallback 到 unix）
cd engine/build/libs && java -jar idb-engine.jar

# 显式指定 TCP 端口
java -jar idb-engine.jar --ipc tcp --port 60000

# 对外暴露（无鉴权！请自行限制网络可达性）
java -jar idb-engine.jar --ipc tcp --host 0.0.0.0 --port 50051

# 显式指定 Unix Domain Socket（路径可换）
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock

# Windows 命名管道（客户端需 --ipc=pipe；服务端在 Windows 上 grpc-java 暂未开放公共 API，详见 ARCHITECTURE.md §3.6）
java -jar idb-engine.jar --ipc pipe --pipe-name idb-engine

# Direct 直接模式（v2.9 新增）—— 仅 bootstrap drivers/dialects 后阻塞主线程；
# 不启动 gRPC server；适合 shell 测试、守护进程、或被同 JVM 客户端直接调用
java -jar idb-engine.jar --mode direct

# 打印完整 CLI 帮助
java -jar idb-engine.jar --help
```

---

## 通信协议（gRPC + IPC Transport）

引擎是 **gRPC 服务端**，通过 IPC Transport 抽象层接收调用方的连接：

```proto
service IdbEngine {
  rpc Handle(Request) returns (stream Response);
}
```

`Handle` 是服务端流式 RPC：客户端发送一条 `Request`，服务端按需返回 1..N 条 `Response`。

### IPC 传输方式

| `--ipc` | 传输 | 平台 | 说明 |
|---|---|---|---|
| `tcp`（默认） | TCP `127.0.0.1:<port>` | 全平台 | 生产路径；`--port` 控制端口（默认 50051），`--host` 控制绑定地址 |
| `unix` | Unix Domain Socket | Linux / macOS / BSD | Linux 用 epoll native，macOS/BSD 用 NIO；UDS 文件权限 `rw-------`；默认路径 `/tmp/idb-engine.sock` |
| `pipe` | Windows 命名管道 | Windows | 客户端可用；grpc-java 1.76 无 server-side API，`serverBuilder()` 抛 `UnsupportedOperationException`；管道名默认 `idb-engine` |

**自动检测**：`--ipc` 缺省时，Windows → `pipe`，POSIX → `unix`。

> **⚠️ TCP 端口无鉴权** —— 连上即可执行任意 SQL（含 `SYSTEM.DISCONNECT` 踢掉他人连接）。
> 因此默认**只绑回环** `127.0.0.1`。确需跨机访问时显式 `--host 0.0.0.0`，并自行在网络层限制可达性。

**所有 CLI 选项**：
```
--mode <grpc|direct>      启动模式（默认 grpc；v2.9 新增 direct — 仅 bootstrap、不开 server）
--ipc <tcp|unix|pipe>     IPC 传输（默认自动检测；仅 grpc 模式生效）
--port <int>              TCP 端口（默认 50051，仅 tcp 模式生效）
--host <addr>             TCP 绑定地址（默认 127.0.0.1；传 0.0.0.0 对外暴露，无鉴权）
--uds-path <path>         UDS 文件路径（默认 /tmp/idb-engine.sock）
--pipe-name <name>        命名管道名称（默认 idb-engine；需匹配 ^[A-Za-z0-9_.-]{1,64}$）
--help / -h               打印 CLI 用法并退出
```

解析失败抛 `IllegalStateException`（启动入口打印到 stderr，`exitProcess(2)`）。

### Go 端连接示例

```go
import (
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
    pb "your/proto/gen"  // 由 idb_engine.proto 生成
)

// TCP（默认）
conn, _ := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
defer conn.Close()

// UDS（POSIX）
// conn, _ := grpc.Dial("unix:///run/idb/idb-engine.sock",
//     grpc.WithTransportCredentials(insecure.NewCredentials()))

// Windows Named Pipe
// conn, _ := grpc.Dial("pipe:idb-engine",
//     grpc.WithTransportCredentials(insecure.NewCredentials()))

client := pb.NewIdbEngineClient(conn)
stream, _ := client.Handle(ctx, &pb.Request{
    Id:       "req-001",
    Category: pb.Category_TABLE,
    Action:   pb.Action_LIST,
    Connection: &pb.ConnectionConfig{
        Driver: "Mysql", Host: "127.0.0.1", Port: 3306,
        User: "root", Password: "secret", Database: "test",
    },
    Body: &pb.Request_TableRequest{
        TableRequest: &pb.TableRequest{
            Body: &pb.TableRequest_List{
                List: &pb.TableListRequest{Schema: "public"},
            },
        },
    },
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End
    // typed body 用 switch resp.GetBody().(type) 分发到 13 个 Category
}
```

### 请求格式

`Request` envelope（`id` / `category` / `action` / `connection` / `session_id` + `oneof body` 路由到 13 个 Category 强类型消息）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 请求唯一 ID（`SYSTEM.CANCEL` 的 `target_request_id` 即以此为准） |
| `category` | enum | 13 个分类（详见下表） |
| `action` | enum | 25 个操作（详见下表） |
| `connection` | ConnectionConfig | 连接凭证（`driver`/`host`/`port`/`user`/`password`/`database`/`schema`/`use_ssl`/`properties`/**`jdbc_url`**）；`driver` 可为 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`；`jdbc_url`（v2.11）非空时**只依赖 URL**，其余连接字段被忽略，方言由 URL scheme 反查 |
| `session_id` | string | **v2.16** — 事务会话 id，由 `SYSTEM.BEGIN` 分配。**留空 = 无事务**（每条语句独立提交，即 v2.15 及以前的行为）；非空时该请求的 `DATA.*` 写操作与 `SQL.EXECUTE` DML 落在会话钉住的那条连接上 |
| `body` | `oneof` | 13 个 Category 强类型 message（`system_request` / `schema_request` / `user_request` / `table_request` / `data_request` / `sql_request` / `function_request` / `view_request` / `index_request` / `foreign_key_request` / `trigger_request` / `export_request` / `import_request`） |

每个 Category 消息内部也用 `oneof` 按 Action 派发（如 `TableRequest` → `list` / `column_list` / `create` / `update` / `get_ddl` / `rename` / `delete` / `truncate`）。每个 Action 子消息字段为 snake_case（protobuf 生成 camelCase getter）。`ColumnDef`、`GenerateTable` 等跨 Action 共享类型在顶层定义。

**Category 枚举**：`SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` / `VIEW` / `INDEX` / `FOREIGN_KEY` / `TRIGGER` / **`IMPORT`（= 14，v2.16）**

**Action 枚举**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `DEBUG` / `CALL` / `RUN_EXPORT` / `RENAME` / `TRUNCATE` / `EXPLAIN` / `TEST_CONNECTION` / `SERVER_INFO` / `LIST_DRIVERS` / `DISCONNECT`（19）/ **`CANCEL`（20）** / **`RUN_IMPORT`（21）** / **`BEGIN`（22）** / **`COMMIT`（23）** / **`ROLLBACK`（24）** / **`SESSION_INFO`（25）**（v2.16 新增 6 个）

> **重要**：`Action.EXPORT` 在 proto3 中与 `Category.EXPORT` 命名冲突，因此导出请求使用 **`Action.RUN_EXPORT`**（也是 `EXPORT` category 的唯一合法 action）；同理数据导入用 `IMPORT.RUN_IMPORT`（`IMPORT` category 的唯一合法 action）。

### 响应格式

`Response` envelope（`id` / `success` / `error` / `stream` / `end` + `oneof body` 路由到 13 个 Category 强类型消息 + 5 个流式帧类型）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 对应请求 ID |
| `success` | bool | 是否成功 |
| `error` | string | 错误信息；空串表示无错误 |
| `stream` | bool | 流式响应标记 |
| `end` | bool | 流式结束标记 |
| `body` | `oneof` | 13 个 Category 强类型 message（`schema` / `user` / `table` / `data` / `sql` / `system` / `function` / `view` / `index` / `foreign_key` / `trigger` / `export` / `import`） + 5 个流式帧（`data_row_frame` / `sql_row_frame` / `gen_progress_frame` / `import_progress`） + `generate_terminal` |

每个 Category 消息内部也用 `oneof` 按 Action 派发。**v2.4 起**，列表项已用强类型 per-item proto 替代 `repeated google.protobuf.Value`（`TableListItem` / `ViewListItem` / `IndexListItem` / `ForeignKeyListItem` / `TriggerListItem` / `FunctionListItem` / `FunctionDebugItem` / `UserGrantItem`），动态行使用 typed `Row { map<string, Value> values }` wrapper；仅确实按方言变化的 item shape（USER.LIST 在 MySQL 是 `{user, host}`、PG 是 `{user}`）与 dialect-specific extras（如 SYSTEM.SERVER_INFO 方言扩展、FUNCTION.CALL/INFO 返回）保留 `Value`。

**Handler 调用契约**：14 个业务 handler 全部直接接收 typed per-Category proto 消息、返回 typed per-Action proto 消息；`RequestDispatcher` 是 (Category, Action) → handler 的薄路由层，handler 返回的 typed 消息被装入 `Response.body` 对应 oneof 分支。**无 `JsonObject` 边界映射**。

---

## Direct 直接模式（v2.9 新增）

除了 gRPC 服务端入口外，引擎还提供**进程内实现** —— `com.kxxnzstdsw.engine.IdbEngine`，允许**同 JVM 客户端**（典型场景：KMP Desktop Compose Multiplatform 应用）直接调用引擎方法，**完全跳过 gRPC channel、IPC transport、序列化**。

> **v2.15 —— `IdbEngine` 只是 `EngineClient` 的两个实现之一。** 接口 `com.kxxnzstdsw.client.EngineClient`（在 `:engine-protocol`）是 transport-agnostic 的调用层契约：
>
> | 实现 | 模块 | 通道 | 场景 |
> |---|---|---|---|
> | `IdbEngine` | `:engine` | 同 JVM 直接方法调用 | Compose Desktop 同进程、shell 工具、嵌入式 |
> | `GrpcEngineClient` | `:engine-grpc-client` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、远程引擎 |
>
> 调用方**只面向 `EngineClient` 编程，不感知引擎在同进程还是跨进程**。`IdbEngine` 现在显式 `implements EngineClient`：类里只剩 `handle` / `testConnection(config)` / `disconnect` / `close` 四个 `override` 成员；`invoke(connection, configure)` 与 `testConnection(jdbcUrl, user, password)` 已上移为**接口上的 default 方法**（对两个实现通用），不再是 `IdbEngine` 自己的方法。`IdbEngine` 独有的 `companion object`（`bootstrap` / `runDirectMode`）仍只在本地实现上。

### 两种实现对照

| 维度 | `IdbEngine`（`:engine`，同进程） | `GrpcEngineClient`（`:engine-grpc-client`） |
|---|---|---|
| 通信开销 | 直接方法调用 + 内存对象引用 | gRPC channel + HTTP/2 + protobuf 编解码 + IPC transport |
| 进程约束 | 必须同 JVM（Kotlin / Java） | 任意（Go / Kotlin / TypeScript / Python） |
| `Request` 构造 | 必须 Kotlin（DSL builder 或 `Request.newBuilder()`） | 同左（Kotlin 客户端） |
| 流式响应 / envelope options / 错误包装 | ✓ 与 gRPC 路径完全一致（共用 `RequestDispatcher`） | ✓ |
| 依赖 | `api(project(":engine-protocol"))` | 仅 `:engine-protocol`（不依赖 `:engine`） |

详见 [`engine-grpc-client/README.md`](../engine-grpc-client/README.md) 与 [`engine-protocol/README.md`](../engine-protocol/README.md)。

### 为什么需要 Direct 模式

KMP Desktop 前端（Compose Multiplatform）与引擎部署在同一个 JVM 进程里，再绕一层 gRPC（建 channel、protobuf 编解码、HTTP/2 帧、IPC transport 派发）纯属浪费。Direct 模式让 UI 直接持有 `EngineClient.handle(request): Flow<Response>`，**流式响应语义、流式帧类型、envelope options（`traceId` / `dryRun` / `timeoutMs`）与 gRPC 路径完全一致** —— 两条路径共用同一个 `RequestDispatcher`，调用契约零差异。

### 入口 API

```kotlin
// 位于 :engine-protocol —— 两个实现（IdbEngine / GrpcEngineClient）共用的调用层契约
package com.kxxnzstdsw.client

interface EngineClient : AutoCloseable {
    /** 主入口：与 gRPC stub `IdbEngineCoroutineStub.handle()` 签名一致 */
    fun handle(request: Request): Flow<Response>

    /** 单次非流式便捷方法：default 方法 —— 用 Flow.first() 收集单条 Response */
    suspend fun invoke(
        connection: ConnectionConfig,
        configure: RequestKt.Dsl.() -> Unit,
    ): Response

    /** v2.11 直连：测试 / 初始化连接（不经 gRPC / IPC / RequestDispatcher envelope） */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse

    /** v2.11 直连便捷重载：default 方法 —— 仅凭 JDBC URL + 凭据，driver 由 URL scheme 反查 */
    suspend fun testConnection(
        jdbcUrl: String,
        user: String = "",
        password: String = "",
    ): SystemTestConnectionResponse

    /** v2.12 直连：断开连接 —— 释放该配置的连接池（含各 schema 维度）；true = 确实关闭了池 */
    suspend fun disconnect(config: ConnectionConfig): Boolean

    /** 释放实现自身持有的资源（本地实现释放连接池/驱动/方言；gRPC 实现关闭 channel），幂等 */
    override fun close()
}
```

同进程实现（`package com.kxxnzstdsw.engine`，位于 `:engine`）：

```kotlin
class IdbEngine(
    /** 构造时自动 bootstrap drivers/dialects（默认从 File("drivers") / File("dialects") 加载；幂等） */
    driversDir: File = File("drivers"),
    dialectsDir: File = File("dialects"),
) : EngineClient {
    override fun handle(request: Request): Flow<Response>
    override suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse
    override suspend fun disconnect(config: ConnectionConfig): Boolean
    override fun close() { /* PoolManager.closeAll() + DriverLoader.closeAll() + DialectLoader.closeAll() */ }
    // invoke / testConnection(jdbcUrl, …) 继承自接口 default 实现
    companion object {
        @JvmStatic fun bootstrap(driversDir: File, dialectsDir: File) { /* ... */ }
        @JvmStatic fun runDirectMode(driversDir: File, dialectsDir: File) { /* CLI --mode direct 实现 */ }
    }
}
```

### KMP Desktop 集成示例

`desktopApp/build.gradle.kts`：
```kotlin
dependencies {
    implementation(project(":shared"))
    implementation(project(":engine"))  // v2.9 直接模式

    // v2.12：方言插件 + JDBC 驱动随应用类路径加载（Direct 模式不需要外部 dialects/ drivers/ 目录）。
    // DialectLoader 先扫应用类路径 SPI（ServiceLoader），再用 dialects/ 目录覆盖同名方言；
    // 缺了这些依赖引擎将解析不出任何方言（"No dialect plugin matches JDBC URL"）
    runtimeOnly(project(":dialect-mysql"))
    runtimeOnly(project(":dialect-postgresql"))
    runtimeOnly(project(":dialect-h2"))
    runtimeOnly(project(":dialect-duckdb"))
    runtimeOnly(project(":dialect-sqlite"))
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.duckdb)
    runtimeOnly(libs.sqlite)

    implementation(compose.desktop.currentOs)
}
```

`desktopApp/src/main/kotlin/.../main.kt`：
```kotlin
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.engine.IdbEngine
import kotlinx.coroutines.flow.first

fun main() = application {
    val engine: EngineClient = IdbEngine()      // 自动 bootstrap；类型即接口
    Window(onCloseRequest = {
        engine.close()
        exitApplication()
    }) { SundaysTheme { MainScreen(engine) } }   // 主题 + 顶层导航来自 :shared
}

// 在 ViewModel 里调用（与 gRPC stub 同形）
class SchemaViewModel(private val engine: EngineClient) {
    // invoke / testConnection / disconnect / handle 均通过接口调用
    suspend fun loadDatabases(connection: ConnectionConfig): SchemaListResponse {
        val resp = engine.invoke(connection) {
            category = Category.SCHEMA
            action = Action.LIST
            schemaRequest = schemaRequest {
                list = schemaListRequest { level = "database" }
            }
        }
        return resp.schema.list                  // typed accessor — 无 JSON 解析
    }
}

// 流式响应（DATA.LIST pageSize=0 / SQL.EXECUTE SELECT / DATA.GENERATE / EXPORT）
engine.handle(request {
    id = UUID.randomUUID().toString()
    category = Category.DATA
    action = Action.LIST
    connection = connection
    dataRequest = dataRequest {
        list = dataListRequest {
            tableName = "users"
            pageSize = 0                          // 触发流式模式
        }
    }
}).collect { resp ->
    when {
        resp.dataRowFrame != null -> renderRow(resp.dataRowFrame)
        resp.end                  -> finishLoading()
        !resp.success             -> showError(resp.error)
    }
}

// 错误传播（direct 模式与 gRPC 模式一致：success=false + error 字段；不抛异常）
val resp = engine.handle(badRequest).first()
check(resp.success) { "engine error: ${resp.error}" }
```

### 与 gRPC 模式对比

| 维度 | gRPC 模式（`--mode grpc`，默认） | Direct 模式（`--mode direct` / 同 JVM 调用） |
|---|---|---|
| 入口 | gRPC server over IPC transport | `EngineClient` 的同进程实现 `IdbEngine().handle(request)` |
| 客户端进程 | 任意（Go / Kotlin / TypeScript / Python） | 必须同 JVM（Kotlin / Java） |
| 通信开销 | HTTP/2 + protobuf + IPC transport 派发 | 直接方法调用 + 内存对象引用 |
| 响应类型 | `Flow<Response>` from `IdbEngineCoroutineStub.handle()` | `Flow<Response>` from `IdbEngine.handle()` — **同一类型** |
| `Request` 构造 | 任意语言生成 protobuf | 必须 Kotlin（DSL builder 或 `Request.newBuilder()`） |
| 流式响应 | ✓ `DataRowFrame` / `SqlSelectRowFrame` / `GenerateProgressFrame` / `ImportProgressFrame` | ✓ 完全一致 |
| Envelope options（traceId / dryRun / timeoutMs） | ✓ | ✓ |
| 错误响应包装 | `success=false, error=...` 不抛异常 | **✓ 完全一致**（`RequestDispatcher.dispatch` 顶层 `.catch{}` operator 兜底） |
| 适用场景 | 跨进程、跨语言、远程调试、子进程隔离、远程 daemon | KMP Desktop UI（同 JVM 库集成）、shell 工具、嵌入式场景 |
| 启动命令 | `java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051` | `java -jar idb-engine.jar --mode direct`（block 主线程）或 `IdbEngine()`（同进程） |

### 契约测试

`engine/src/test/kotlin/com/kxxnzstdsw/integration/IdbEngineDirectTest.kt`（4 项）覆盖：
1. **非流式响应**：`handle(request).first()` 返回单条 `Response`（`stream=false, end=false`，proto 默认）
2. **`invoke` 便捷方法**：等价于 `handle().first()`，适合单条查询
3. **错误传播**：handler 抛异常 → `Response.success=false, error=<msg>`（不抛异常、不污染 Flow）
4. **Bootstrap 幂等性**：多个 `IdbEngine()` 实例共享同一组 driver / dialect 加载；第二次 bootstrap no-op

> ⚠️ v2.9 修复的细节：`RequestDispatcher.dispatch` 之前把 try/catch 放在 `flow{}` 内部，导致下游 `first()` / `takeWhile` 等短路算子取消时抛出的 `AbortFlowException` 被错误捕获并再次 emit，触发 *"Flow exception transparency violated"*。v2.9 把 try/catch 移到 `.catch{}` operator 外置，`AbortFlowException` 现在能正常向上传播，direct 调用方与 gRPC 调用方都受益。

---

## Handler 路由矩阵

| Category \ Action | LIST | CREATE | UPDATE | DELETE | EXECUTE | EXPLAIN | GET_DDL | INFO | GRANTS | GENERATE | DEBUG | CALL | RUN_EXPORT | RENAME | TRUNCATE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCHEMA      | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| USER        | ✓ | ✓ | ✓ | ✓ | — | — | — | — | ✓ | — | — | — | — | — | — |
| TABLE       | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | — | — | — | — | — | — | ✓ | ✓ |
| DATA        | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — | ✓ | — | — | — | — | — |
| SQL         | — | — | — | — | ✓ | ✓ | — | — | — | — | — | — | — | — | — |
| SYSTEM      | — | — | — | — | — | — | — | ✓ | — | — | — | — | — | — | — |
| FUNCTION    | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | ✓ | — | — | ✓ | ✓ | — | — | — |
| EXPORT      | — | — | — | — | — | — | — | — | — | — | — | — | ✓ | — | — |
| VIEW        | ✓ | ✓ | — | ✓ | — | — | ✓ | — | — | — | — | — | — | — | — |
| INDEX       | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| FOREIGN_KEY | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| TRIGGER     | ✓ | — | — | — | — | — | ✓ | — | — | — | — | — | — | — | — |

SYSTEM 还支持 `TEST_CONNECTION` / `SERVER_INFO` / **`LIST_DRIVERS`**（v2.8 新增 — 表中未列出）/ **`DISCONNECT`**（v2.15 新增 — 见下文 `SYSTEM.DISCONNECT`）/ **`CANCEL` / `BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`**（v2.16 新增）；`SQL.EXECUTE` 自 **v2.16** 起通过请求字段 `multi_statement = true` 支持**多语句脚本**（表内仍记为一行 `EXECUTE`）。

**v2.16 新增路由**（action 数值即 proto 枚举值）：

| Route | Action | Handler | 响应 |
|---|---|---|---|
| `SYSTEM.CANCEL` | `CANCEL = 20` | `SystemHandler.cancel(targetRequestId)` | `SystemCancelResponse`（`cancelled` / `request_id` / `error`） |
| `SYSTEM.BEGIN` | `BEGIN = 22` | `SystemHandler.begin(config)` | `SystemBeginResponse`（`session_id` / `started_at` / `driver` / `database`） |
| `SYSTEM.COMMIT` | `COMMIT = 23` | `SystemHandler.commit(sessionId)` | `SystemCommitResponse`（`session_id` / `committed` / `error`） |
| `SYSTEM.ROLLBACK` | `ROLLBACK = 24` | `SystemHandler.rollback(sessionId)` | `SystemRollbackResponse`（`session_id` / `rolled_back` / `error`） |
| `SYSTEM.SESSION_INFO` | `SESSION_INFO = 25` | `SystemHandler.sessionInfo(sessionId)` | `SystemSessionInfoResponse`（`active_sessions` / `sessions[]`） |
| `IMPORT.RUN_IMPORT` | Category `IMPORT = 14` · Action `RUN_IMPORT = 21` | `ImportHandler.executeInMainProcess(request)` | 流式：若干 `ImportProgressFrame` + 一条终止帧（携带 `ImportResultResponse`） |

> `IMPORT.RUN_IMPORT` 与 `SYSTEM.DISCONNECT` 同属 `writeActions`（`truncate_first` 会清空目标表），`dryRun=true` 时短路，不会真的导入。

---

## API 参考

> 下方 `payload.data` 字段均为业务层逻辑结构（JSON 形式便于阅读），实际 wire 是 `google.protobuf.Value` 的二进制编码。所有 `Response.data` 默认 `success=true`；失败响应使用 `success=false, error=<message>, data=null` 模式。

### SCHEMA — 架构管理

#### LIST — 两级导航

```json
// 列出所有 database（默认 level="database"）
{
  "id": "r1", "category": "SCHEMA", "action": "LIST",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},
  "payload": {}
}
// Response data: {"level":"database", "items":["information_schema","mysql","test_db"]}

// 列出 PG 某 database 下的 schema（必须传 database）
{
  "id": "r1b", "category": "SCHEMA", "action": "LIST",
  "connection": {"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},
  "payload": {"level":"schema", "database":"my_app_db"}
}
// Response data: {"level":"schema", "database":"my_app_db", "items":["public","myschema"]}
```

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库 | 单元素 `[database]` |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace` 过滤 `pg_%` / `information_schema` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |
| DuckDB | 文件路径 / `memory` / 单元素 | `information_schema.schemata` |
| SQLite | 单元素（`database` 路径或 `:memory:`） | 单元素（`main`） |

#### CREATE / DELETE

```json
// CREATE
{"id":"r2","category":"SCHEMA","action":"CREATE","connection":{...},"payload":{"name":"new_db","options":{"charset":"utf8mb4","collate":"utf8mb4_unicode_ci"}}}
// Response data: {"created":"new_db"}

// DELETE
{"id":"r3","category":"SCHEMA","action":"DELETE","connection":{...},"payload":{"name":"old_db"}}
// Response data: {"deleted":"old_db"}
```

`options` 仅 MySQL 支持（`charset` / `collate`）；PG / H2 忽略。

---

### USER — 用户权限管理

#### LIST — 用户列表 / 用户权限

```json
// 用户列表
{"id":"r4","category":"USER","action":"LIST","connection":{"driver":"Mysql",...},"payload":{}}
// Response (MySQL): [{"user":"root","host":"localhost"},{"user":"app_user","host":"%"}]
// Response (PG):     [{"user":"postgres"},{"user":"app_user"}]

// 指定用户权限（payload 含 user 时路由）
{"id":"r4b","category":"USER","action":"LIST","connection":{"driver":"Mysql",...},"payload":{"user":"dev","host":"%"}}
// Response (MySQL): [{"grant":"GRANT SELECT ON `test_db`.* TO 'dev'@'%'"}, ...]
// Response (PG):     [{"schema":"public","table":"users","privilege":"SELECT"}, ...]
```

#### GRANTS — 聚合表级授权

```json
{"id":"r5","category":"USER","action":"GRANTS","connection":{...},"payload":{"user":"dev","host":"%"}}
// Response: [{"schema":"test_db","table":"users","privileges":"SELECT, INSERT"}, ...]
```

#### CREATE / DELETE

```json
// CREATE
{"id":"r6","category":"USER","action":"CREATE","connection":{"driver":"Mysql",...},"payload":{"user":"new_user","password":"secret123","host":"%"}}
// Response: {"created":"new_user"}

// DELETE
{"id":"r6b","category":"USER","action":"DELETE","connection":{...},"payload":{"user":"old_user","host":"%"}}
// Response: {"deleted":"old_user"}
```

#### UPDATE — 修改密码 / 授权

```json
// 修改密码（payload 含 password 但无 privileges）
{"id":"r7","category":"USER","action":"UPDATE","connection":{"driver":"Mysql",...},"payload":{"user":"dev","password":"new_secret","host":"%"}}
// Response: {"user":"dev","action":"password_changed"}

// 授权（payload 含 privileges + isGrant=true）
{"id":"r8","category":"USER","action":"UPDATE","connection":{"driver":"Mysql",...},"payload":{"user":"dev","schema":"my_app_db","privileges":["SELECT","INSERT"],"isGrant":true,"tableName":"users","withGrantOption":false}}
// Response: {"user":"dev","schema":"my_app_db","table":"users","withGrantOption":false,"action":"granted"}

// 回收（isGrant=false）
{"id":"r8b","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["DELETE"],"isGrant":false}}
// Response: {"user":"dev","schema":"my_app_db","action":"revoked"}
```

---

### TABLE — 表结构元数据

#### LIST — 表列表 / 列列表

```json
// 表列表
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{}}
{"id":"r9","category":"TABLE","action":"LIST","connection":{"driver":"Postgresql",...},"payload":{"schema":"public"}}
// Response: [{"name":"users","type":"TABLE"},{"name":"orders","type":"TABLE"}]

// 列列表（payload 含 tableName 时自动路由）
{"id":"r10","category":"TABLE","action":"LIST","connection":{...},"payload":{"tableName":"users"}}
// Response: [
//   {"name":"id","type":"INT","size":10,"nullable":false,"isPrimaryKey":true,"defaultValue":null},
//   {"name":"name","type":"VARCHAR","size":255,"nullable":true,"isPrimaryKey":false,"defaultValue":null}
// ]
```

#### CREATE

```json
{
  "id": "r11", "category": "TABLE", "action": "CREATE",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},
  "payload": {
    "tableName": "products",
    "columns": [
      {"name":"id","type":"INT","nullable":false,"isPrimaryKey":true,"autoIncrement":true},
      {"name":"name","type":"VARCHAR","size":255,"nullable":false},
      {"name":"price","type":"DECIMAL","nullable":true,"defaultValue":"0.00"},
      {"name":"created_at","type":"TIMESTAMP","nullable":true,"defaultValue":"CURRENT_TIMESTAMP"}
    ],
    "options": {"engine":"InnoDB","charset":"utf8mb4","collate":"utf8mb4_unicode_ci","comment":"商品表"}
  }
}
// Response: {"created":"products"}
```

**列定义字段**：`name` (必填)、`type` (必填)、`size`、`nullable` (默认 `true`)、`isPrimaryKey` (默认 `false`)、`defaultValue`、`autoIncrement` (默认 `false`)

**表选项**：`options` 支持 MySQL `engine`/`charset`/`collate`/`comment`；PG 仅 `comment`（走 `COMMENT ON TABLE`）。

#### UPDATE — ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN

```json
// ADD_COLUMN
{"id":"r12","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"ADD_COLUMN","column":{"name":"description","type":"TEXT","nullable":true}}}
// Response: {"tableName":"products","operation":"ADD_COLUMN"}

// DROP_COLUMN
{"id":"r12b","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"DROP_COLUMN","columnName":"description"}}

// MODIFY_COLUMN（仅改类型）
{"id":"r13","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false}}}

// MODIFY_COLUMN（改类型 + 重命名）
{"id":"r13b","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false,"newName":"unit_price"}}}
```

#### GET_DDL / DELETE / RENAME / TRUNCATE

```json
// GET_DDL
{"id":"r14","category":"TABLE","action":"GET_DDL","connection":{...},"payload":{"tableName":"users"}}
// Response data: "CREATE TABLE `users` (\n  `id` INT NOT NULL,\n  `name` VARCHAR(255),\n  PRIMARY KEY (`id`)\n)"  (string)

// DELETE
{"id":"r15","category":"TABLE","action":"DELETE","connection":{...},"payload":{"tableName":"old_table"}}
// Response: {"deleted":"old_table"}

// RENAME（oldName 可用 tableName 作为别名）
{"id":"r16","category":"TABLE","action":"RENAME","connection":{...},"payload":{"oldName":"users","newName":"users_new"}}
// Response: {"renamed":"users","newName":"users_new"}

// TRUNCATE
{"id":"r17","category":"TABLE","action":"TRUNCATE","connection":{...},"payload":{"tableName":"users"}}
// Response: {"truncated":"users"}
```

---

### DATA — 表数据 CRUD

#### LIST — 分页 / 流式

```json
// 分页查询（默认 page=1, pageSize=50）
{"id":"r18","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":20}}
// Response data: {"total":120,"page":1,"pageSize":50,"rows":[{"id":"1","name":"Alice","avatar":"[LOB Data]"}, ...]}

// 带过滤与排序（原始 SQL 片段，方言层注入校验）
{"id":"r18b","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":20,"where":"age > 18 AND name LIKE '%Alice%'","orderBy":"created_at DESC"}}

// 流式全量查询（pageSize: 0 触发 JDBC 游标模式，PG 端临时 autoCommit=false）
{"id":"r18c","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","pageSize":0}}
// 流式响应：
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{...}]}}
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{...}]}}
// ...
// {"id":"r18c","success":true,"stream":true,"end":true,"data":null}
```

**LOB 列**：`BLOB` / `LONGTEXT` / `BYTEA` / `TEXT` 一律返回 `"[LOB Data]"`。

**`where` / `orderBy` 注入校验**：
- 通用：去除引号内容后禁止 `;` / `--` / `/*` 注释；禁止引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- MySQL：`ORDER BY` 标识符允许反引号
- PostgreSQL：`ORDER BY` 标识符允许双引号；额外禁止 `COPY` / `DO`

#### CREATE / UPDATE / DELETE

```json
// 插入
{"id":"r19","category":"DATA","action":"CREATE","connection":{...},"payload":{"tableName":"users","values":{"name":"Charlie","email":"charlie@example.com"}}}
// Response: {"affectedRows":1}

// 更新
{"id":"r20","category":"DATA","action":"UPDATE","connection":{...},"payload":{"tableName":"users","changes":{"name":"Alex","email":"alex@example.com"},"where":{"id":"1"}}}
// Response: {"affectedRows":1}

// 删除
{"id":"r21","category":"DATA","action":"DELETE","connection":{...},"payload":{"tableName":"users","where":{"id":"1"}}}
// Response: {"affectedRows":1}
```

所有 `values` / `changes` / `where` 值以字符串形式通过 `PreparedStatement` 绑定；列类型由方言层解析后 dispatch 到 `setLong` / `setDouble` / `setBoolean` / `setDate` / `setTime` / `setTimestamp` / `setBytes` / `setString`。

---

### SQL — 原生 SQL 引擎

```json
// 查询（流式）
{"id":"r22","category":"SQL","action":"EXECUTE","connection":{"driver":"Mysql",...},"payload":{"sql":"SELECT id, name FROM users WHERE id > 10 LIMIT 5"}}
// 流式响应（每行一帧，total: -1 表示无法预知总行数）：
// {"id":"r22","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id":"11","name":"Dave"}]}}
// ...

// PG 带 schema 上下文（自动 SET search_path TO <schema>）
{"id":"r22b","category":"SQL","action":"EXECUTE","connection":{"driver":"Postgresql",...},"payload":{"sql":"SELECT * FROM users LIMIT 5","schema":"public"}}

// 更新 / DDL（非流式）
{"id":"r23","category":"SQL","action":"EXECUTE","connection":{...},"payload":{"sql":"UPDATE users SET name = 'Frank' WHERE id = 3"}}
// Response: {"affectedRows":1}
```

> `Action.EXPLAIN` 已在 proto 中定义，**v2.6 起已由 dispatcher 路由**；可走 `SQL.EXPLAIN` 或 `SQL.EXECUTE` 直接提交 `EXPLAIN <sql>`。

#### 多语句脚本 — `multi_statement = true`（v2.16 新增）

`SqlExecuteRequest` 增加 `multi_statement`（字段 3，默认 `false`）。为 `true` 时，`sql` 字段被当作**整段脚本**：`com.kxxnzstdsw.engine.SqlScriptSplitter.split(script)` 在**词法层面**把它切成顶层语句列表，逐条按序执行。

```json
// 一次提交多条语句
{"id":"r23b","category":"SQL","action":"EXECUTE","connection":{...},"payload":{
  "sql":"CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2);",
  "multiStatement":true
}}
// Response data:
// {
//   "affectedRows": 2,                 // 所有「成功语句」的 affected_rows 合计
//   "statements": [
//     { "index": 0, "sql": "CREATE TABLE t (id INT)", "affectedRows": 0, "success": true },
//     { "index": 1, "sql": "INSERT INTO t VALUES (1)", "affectedRows": 1, "success": true },
//     { "index": 2, "sql": "INSERT INTO t VALUES (2)", "affectedRows": 1, "success": true }
//   ]
// }
```

| 要点 | 行为 |
|---|---|
| 执行顺序 | **按序执行，遇错即停**（与 psql / DBeaver 的默认脚本行为一致）：某条失败时在该 `index` 上记录 `success=false` + error，**其后的语句不再执行** |
| `affected_rows` | 所有**成功**语句行数之和；单条语句模式即该语句的行数 |
| 末尾无 `;` | 仍会执行（分句器保留最后一段非空片段） |
| 无有效语句 | 整个脚本没有可执行语句（如只有空白 / 注释）→ `success=false` + 非空 `error`（`"multi_statement script contains no executable statement"`） |
| 取消 | 每条语句按 `"$requestId#$index"` 单独登记到 `StatementRegistry`，`SYSTEM.CANCEL` 永远命中**当前正在跑的那一条** |
| 事务 | 传入 `Request.session_id` 时，脚本全部语句落在会话钉住的那条连接上（见 SYSTEM 事务会话） |

**分句器支持 / 不支持什么**（`SqlScriptSplitter`，单趟词法状态机，只在**顶层** `;` 切分）：

| 支持 | 说明 |
|---|---|
| `'...'` 单引号字符串 | 识别 `''` 与反斜杠转义 |
| `"..."` 双引号标识符 | 识别 `""` 转义（PostgreSQL） |
| `` `...` `` 反引号标识符 | 识别双反引号转义（MySQL） |
| `-- ...` / `# ...` 行注释 | 至行尾结束 |
| `/* ... */` 块注释 | 可跨行；**未闭合**时把剩余输入整体视为注释（不报错、不死循环） |
| PostgreSQL dollar-quoting | `$tag$ ... $tag$` / `$$ ... $$`，闭合 tag 必须与开启 tag **完全一致**；`$1` 这类占位符**不会**被误认作 dollar-quote |

- **注释文本原样保留**在语句中（不剔除）：MySQL 的「可执行注释」`/*! ... */` 携带真实语义，删掉等于改变脚本语义；注释的作用仅在于其内部的 `;` 不构成语句边界。
- 空白语句（`;;` 产生的空片段、纯空白）被丢弃；**引号内为空的语句会保留**（如脚本就是单个 `';'`），因为丢弃它等于改变程序语义。
- **嵌套块注释不支持**：PostgreSQL 允许块注释嵌套，MySQL/SQLite 不允许，本项目需同时覆盖两类方言。选择「不嵌套」时，PG 嵌套写法中第一个 `*/` 之后的文本会被当作正常 SQL —— 误判方向是**过度切分**，而不是静默吞掉一段内容。已明确记录为限制。

---

### SYSTEM — 系统信息

#### INFO — JVM 运行时信息（无需连接，connection 字段被忽略）

```json
{"id":"r24","category":"SYSTEM","action":"INFO","connection":{"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
// Response:
// {
//   "jvmVersion": "21.0.2", "jvmVendor": "...", "jvmName": "...",
//   "osName": "...", "osArch": "amd64", "osVersion": "...",
//   "availableProcessors": 16,
//   "memory": {"max":4294967296,"total":268435456,"used":134217728,"free":134217728},
//   "uptime": 120000, "pid": 12345
// }
```

`memory` 各字段单位为字节。

#### TEST_CONNECTION — 测试连接（失败也返回 `ok=false`，不抛异常）

```json
{"id":"r25","category":"SYSTEM","action":"TEST_CONNECTION","connection":{"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
// Success: {"ok":true,"driver":"Mysql","host":"localhost","port":3306,"database":"mysql"}
// Failure: {"ok":false,"error":"Communications link failure..."}
```

**v2.11 — 仅凭 JDBC URL**：`connection.jdbc_url` 非空时只依赖 URL（`driver`/`host`/`port`/`database` 全部忽略），方言由 URL scheme 反查；无匹配方言返回 `{"ok":false,"error":"No dialect plugin matches JDBC URL: ..."}`。

**v2.12 — 对称的断开**：`IdbEngine.disconnect(config)` 释放 `config` 对应的连接池（含该配置下各 schema 维度的池，返回是否真的关闭）。注意传入的 `config` 需与建池时的字段一致（`jdbcUrl` / 凭据 / 库名参与 pool key），否则定位不到池。删除连接、或改过字段再保存时应显式断开，避免留下取不到的僵尸池。

```json
{"id":"r25b","category":"SYSTEM","action":"TEST_CONNECTION","connection":{"jdbc_url":"jdbc:mysql://root:pass@localhost:3306/mysql?useSSL=false"},"payload":{}}
```

**直连（推荐，同 JVM）**：KMP Desktop 无需构造 `Request`，直接调 facade —— 该调用同时完成**连接池初始化**（首次调用建池，之后复用）：

```kotlin
val result = engine.testConnection(
    jdbcUrl = "jdbc:mysql://localhost:3306/mysql?useSSL=false",
    user = "root", password = "pass",
)
if (result.ok) println("connected via ${result.driver}") else println(result.error)
```

#### DISCONNECT — 释放该配置的连接池（v2.15 新增，`Action.DISCONNECT = 19`）

```json
{"id":"r25c","category":"SYSTEM","action":"DISCONNECT","connection":{"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
// Response data: { closed: true }    // 已关闭过 / 本无活跃池 → { closed: false }
```

| 环节 | 实现 |
|---|---|
| 消息 | `SystemDisconnectResponse { bool closed = 1; }`，挂在 `SystemResponse.disconnect = 5`（oneof） |
| Handler | `SystemHandler.disconnect(config): SystemDisconnectResponse` —— `suspend`，在 `withContext(Dispatchers.IO)` 中调用 `PoolManager.close(config)` |
| 路由 | `RequestDispatcher` 表驱动条目 `Category.SYSTEM to Action.DISCONNECT` → `SystemHandler.disconnect(c)`，包装为 `b.system = systemResponse { disconnect = ... }` |
| dryRun | **属于 `writeActions`** —— `dryRun=true` 时短路返回 success，不真正释放池（释放池有副作用，否则「试运行」会真的断掉用户的连接） |
| 幂等 | 是。第二次调用返回 `closed=false`（该配置当前无活跃池） |

**为什么需要**：连接池活在**引擎进程**里。同进程调用方早已有 `EngineClient.disconnect(config)`（v2.12），但**远端（gRPC）调用方此前没有任何途径释放引擎侧连接池** —— 它们只能等服务端空闲超时。v2.15 补上这条 wire 路由，让远端与本地生命周期语义完全对齐：`testConnection` 建池（= 初始化连接），`SYSTEM.DISCONNECT` 释放它。

同样地，池按 `PoolManager` 的两段式 key（`sha256(配置)#sha256(schema)`）按配置前缀定位，因此一次调用即可关掉该配置下**所有 schema 维度**的池；传入的 `connection` 需与建池时字段一致，否则定位不到池。

**直连（推荐，同 JVM）**：无需构造 `Request`，直接调接口方法（v2.12 已有，语义与 wire 路由一致）：

```kotlin
val closed = engine.disconnect(connection)   // true = 确实关掉了池
```

#### CANCEL — 取消正在执行的请求（v2.16 新增，`Action.CANCEL = 20`）

**为什么只靠协程取消不够**：`SqlEngineHandler.execute` 里的 `rs.next()` 跑在 `Dispatchers.IO` 线程上，是**阻塞的 JDBC 调用**。取消上游 Flow 只会让协程挂起，那条 IO 线程仍在数据库里把查询跑完 —— 只有对**那条正在执行的 `Statement`** 调 `Statement.cancel()` 才能真正让数据库侧停下来。`com.kxxnzstdsw.engine.StatementRegistry` 就是「运行中的请求 id → 可取消目标」的**单一取消通道**（基于 `ConcurrentHashMap<String, CancelTarget>`，`CancelTarget` 是一个 `fun interface { fun cancel() }`，因此既能包装 JDBC `Statement`，也能承载任意取消动作）。

```json
// 取消 id 为 "r22" 的请求（target_request_id 即被取消请求的 Request.id）
{"id":"rc1","category":"SYSTEM","action":"CANCEL","connection":{},"payload":{"targetRequestId":"r22"}}
// 命中:     { cancelled: true,  requestId: "r22" }
// 未命中:   { cancelled: false, requestId: "r22", error: "No running statement for request id 'r22' — it may have already finished" }
```

| 环节 | 实现 |
|---|---|
| 消息 | `SystemRequest { target_request_id, session_id }`；`SystemCancelResponse { cancelled, request_id, error }`，挂在 `SystemResponse.cancel = 6`（oneof） |
| Handler | `SystemHandler.cancel(requestId): SystemCancelResponse` —— 转调 `StatementRegistry.cancel(requestId)` |
| 路由 | `Category.SYSTEM to Action.CANCEL` → 读取 `systemRequest.targetRequestId` |
| 未命中 | **不抛异常**：返回 `cancelled=false` + 说明性 `error`；`target_request_id` 为空时返回 `cancelled=false` 并指明缺字段 |
| 被取消方 | 由其自身异常路径收口为**原请求 id** 上的一条终止帧：`success=false, error="cancelled"` |

**登记范围（v2.16）**：

| 路由 | 登记方式 |
|---|---|
| `SQL.EXECUTE` | `SqlEngineHandler.execute` 对每条语句 `StatementRegistry.register(requestId, stmt)`；多语句脚本按 `"$requestId#$index"` 逐条登记 / 注销，取消总是命中**当前正在跑的那一条** |
| `DATA.LIST`（`pageSize=0` 流式） | `DataHandler.list` 登记游标语句，使 `SYSTEM.CANCEL` 能中断大表全量读取的 `rs.next()` |
| `DATA.GENERATE` | Lua 在 JNI 里**阻塞**执行、协程取消打不进去，因此额外引入 `GenerateState.cancelled: AtomicBoolean`（Lua `insert(...)` 回调入口检查并抛异常，让异常穿过 `L.run()` 解开脚本）+ `GenerateState.activeStmt: AtomicReference<PreparedStatement?>`（在 `executeBatch()` 前后设置，可打断正在跑的批次）。**已执行的批次不回滚** —— `DATA.GENERATE` 不在事务语义内 |
| `IMPORT.RUN_IMPORT` | `ImportHandler` 用 `StatementRegistry.registerCanceler` 登记一个「置停标志 + 打断当前批次」的动作，批次之间读 `cancelled` 标志快速退出，同时 `cancel()` 正在执行的 `executeBatch()` |

#### BEGIN / COMMIT / ROLLBACK / SESSION_INFO — 事务会话（v2.16 新增，`Action = 22 / 23 / 24 / 25`）

**模型**：事务的前提是「一批写操作落在**同一条**连接上」，而连接池每次借出都可能是不同的物理连接。因此 `SystemHandler.begin` 从池里**钉住**一条连接、置 `autocommit=false`，把它交给 `com.kxxnzstdsw.pool.TransactionManager` 持有，直到 COMMIT / ROLLBACK 才归还。调用方拿到 `session_id` 后，随每个请求顶层的 `Request.session_id` 下发。

```json
// 开启会话
{"id":"rt1","category":"SYSTEM","action":"BEGIN","connection":{...},"payload":{}}
// Response data: { sessionId: "550e8400-...", startedAt: 1727500000000, driver: "Postgresql", database: "myapp_db" }

// 会话内写操作 —— session_id 在 Request 顶层，不在 body 里
{"id":"rt2","category":"DATA","action":"CREATE","connection":{...},"session_id":"550e8400-...","payload":{"tableName":"users","values":{"name":"Alice"}}}

// 提交 / 回滚
{"id":"rt3","category":"SYSTEM","action":"COMMIT","connection":{...},"payload":{"sessionId":"550e8400-..."}}
// Response data: { sessionId: "550e8400-...", committed: true }
{"id":"rt4","category":"SYSTEM","action":"ROLLBACK","connection":{...},"payload":{"sessionId":"550e8400-..."}}
// Response data: { sessionId: "550e8400-...", rolledBack: true }

// 会话状态（sessionId 留空则列出全部活跃会话）
{"id":"rt5","category":"SYSTEM","action":"SESSION_INFO","connection":{},"payload":{}}
// Response data: { activeSessions: 1, sessions: [ { sessionId, startedAt, driver, database, schema, autoCommit: false } ] }
```

| 环节 | 实现 |
|---|---|
| `BEGIN` | `TransactionManager.begin(config, schema)` 借连接 + `autoCommit=false`；返回 `SystemBeginResponse` |
| 会话连接 | `PoolManager.getConnection(config, schema, sessionId)` —— `sessionId` 非空时走 `TransactionManager.connectionFor(...)`，返回会话钉住的那条连接，并**仅在 schema 与上次不同时** `setSearchPath` |
| 参与的 Handler | `DataHandler.list/create/update/delete`、`SqlEngineHandler.execute`、`ImportHandler.runImport` 均接收 `sessionId`；会话激活时**不关闭连接**（`withBoundConnection` / `releaseIfUnbound`），否则连接会归还 HikariCP、下一条语句落到另一条物理连接上，事务被悄悄破坏 |
| `COMMIT` / `ROLLBACK` | `finish(...)` 提交或回滚后还原 `autoCommit=true` 并把连接归还池中。会话不存在 → `committed` / `rolled_back = false` + `error`，**不抛异常**（客户端可能断线后重发） |
| `SESSION_INFO` | `sessionId` 非空 → 返回该会话（不存在则 `active_sessions=0`）；留空 → 列出全部活跃会话 |
| 关闭顺序 | `PoolManager.close(config)` 先调 `TransactionManager.closeSessionsFor(config)`，`PoolManager.closeAll()` 先调 `TransactionManager.closeAll()` —— 否则钉住的连接会被「从会话底下」关掉，后续 COMMIT 会在一条死连接上失败 |

**向后兼容保证**：`Request.session_id` **留空 = 无事务**，行为与 v2.15 完全一致（每条语句独立提交）。旧调用方无需任何改动；`PoolManager.getConnection` 在 `sessionId` 为空时就是原有的「从池里借」。

**未知会话不静默降级**：写操作携带一个未知（或已被 COMMIT/ROLLBACK、或被引擎重启清掉）的 `sessionId` → **拒绝**（`success=false`），**绝不会**悄悄退化成自动提交 —— 否则用户以为在事务里，实际每条语句都已经落库。COMMIT / ROLLBACK 遇未知会话则返回 `false` + error，不抛异常。

**并发上限（刻意背压）**：`PoolManager` 每个配置的 `maximumPoolSize = 5`，因此同一配置最多 **5 个并发事务会话**；第 6 个 `BEGIN` 会在 5 秒后命中 HikariCP 的 `connectionTimeout`。这是**有意为之**的背压，不是 bug。

#### SERVER_INFO — 数据库服务器信息

```json
{"id":"r26","category":"SYSTEM","action":"SERVER_INFO","connection":{"driver":"Postgresql",...},"payload":{}}
// Response (PG):     {"version":"PostgreSQL 16.0 ...","current_database":"myapp_db",...}
// Response (MySQL):  {"version":"8.0.36","catalog":"def",...}
// Response (H2):     {"version":"2.3.232","mode":"REGULAR",...}
// Response (DuckDB): {"version":"v1.5.5","mode":"embedded",...}
// Response (SQLite): {"version":"3.46.1","mode":"embedded","product":"SQLite",...}
```

#### LIST_DRIVERS — 枚举已加载方言的连接元数据（v2.8 新增，无需 connection）

返回 `DialectLoader` 中所有已加载方言的连接元数据，**供前端动态渲染"新建连接"表单**——不需要硬编码 driver / port / 是否需要 user 等信息。

```json
{"id":"r26b","category":"SYSTEM","action":"LIST_DRIVERS","connection":{},"payload":{}}
// Response data: { items: [ DialectInfo, DialectInfo, ... ] }
```

`DialectInfo` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver_name` | string | 后端 driver 名（如 `"Mysql"` / `"Sqlite"`），用于 `ConnectionConfig.driver` |
| `display_name` | string | 前端展示用名（如 `"MySQL"` / `"SQLite (Embedded)"`） |
| `jdbc_driver_class_name` | string | JDBC driver 类全名（如 `"com.mysql.cj.jdbc.Driver"`） |
| `jdbc_url_example` | string | 示例 JDBC URL，用于前端 placeholder |
| `connection_type` | string | `CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY` |
| `requires_host` | bool | 是否需要 `host` 字段 |
| `requires_port` | bool | 是否需要 `port` 字段 |
| `default_port` | int32 | 默认端口（0 = 无） |
| `supports_user` | bool | 是否需要 `user` / `password` |
| `supports_password` | bool | 是否需要 `password` |
| `supports_schema` | bool | 是否需要 `schema` 字段（PG/H2 = true） |
| `supports_cross_database` | bool | 是否支持多 database 切换（PG = true） |
| `capabilities` | repeated string | 方言能力标签（`USERS` / `VIEWS` / `INDEXES` / `ROUTINES` / `TRIGGERS` / `FOREIGN_KEYS` / `EXPORT` / `EMBEDDED_MODE` / `MULTI_SCHEMA` / `CROSS_DATABASE` / `DDL_TRANSACTION` / `PRIVILEGES`） |

**5 个方言元数据快照**（按 `driverName` 字典序升序）：

| driver_name | display_name | connection_type | default_port | requiresHost | supportsUser | supportsSchema | 关键 capabilities |
|---|---|---|---|---|---|---|---|
| `Duckdb` | `DuckDB (Embedded OLAP)` | `EMBEDDED` | 0 | ✗ | ✗ | ✓ | `VIEWS, INDEXES, FOREIGN_KEYS, MULTI_SCHEMA, EXPORT, EMBEDDED_MODE` |
| `H2` | `H2 (In-Memory)` | `IN_MEMORY` | 0 | ✗ | ✗ | ✓ | `+ EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`) |
| `Mysql` | `MySQL` | `CLIENT_SERVER` | 3306 | ✓ | ✓ | ✗ | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, TRIGGERS, EXPORT, DDL_TRANSACTION` |
| `Postgresql` | `PostgreSQL` | `CLIENT_SERVER` | 5432 | ✓ | ✓ | ✓ | `+ MULTI_SCHEMA, CROSS_DATABASE` |
| `Sqlite` | `SQLite (Embedded)` | `FILE_BASED` | 0 | ✗ | ✗ | ✗ | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`/`ROUTINES`) |

---

### DATA.GENERATE — 造数引擎

基于嵌入式 Lua 脚本（LuaJIT + Lua 5.1~5.5），按表顺序逐条 INSERT，每条插入后回报进度。

```json
{
  "id": "r27", "category": "DATA", "action": "GENERATE",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},
  "payload": {
    "luaVersion": "luajit",
    "schema": "public",
    "tables": [
      {"script": "for i = 1, 100 do\n  insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65), phone=random_phone()})\nend"},
      {"script": "local catId = lastId()\nfor i = 1, 500 do\n  insert('orders', {user_id=random_int(1,100), amount=random_int(100,99999)/100.0, status=random_enum('pending','paid','shipped')})\nend"}
    ]
  }
}
```

**流式进度响应**：
```json
{"id":"r27","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":1,"scriptInserted":1,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (...) VALUES (?, ?, ?, ?)","data":{"name":"user_1","email":"user_...","age":42,"phone":"138..."}}}
...
{"id":"r27","success":true,"stream":true,"end":true,"data":null}
```

> **进度帧两个易错点**：`scriptIndex` 是 **1-based**（第 1 个脚本 = 1，定位请求里的 `tables` 要减 1）；
> 终止帧 `generateTerminal.tablesProcessed` = 本次请求下发的脚本数（全部脚本跑完才发）。

**Lua 内置函数**：

| 函数 | 签名 | 行为 |
|---|---|---|
| `insert` | `insert(tableName, rowTable)` | 立即执行单条 INSERT |
| `lastId` | `lastId()` | 返回上一条 INSERT 的自增 ID |
| `random_int` | `random_int(min, max)` | `[min, max]` 随机整数 |
| `random_float` | `random_float(min, max)` | `[min, max)` 随机浮点 |
| `random_string` | `random_string(length)` | 随机字母数字串（1..256） |
| `random_date` | `random_date(start, end)` | `YYYY-MM-DD` 区间随机 `LocalDate` |
| `random_datetime` | `random_datetime(start, end)` | `YYYY-MM-DD` 区间随机 `LocalDateTime` |
| `random_time` | `random_time()` | 随机 `LocalTime` |
| `random_email` | `random_email()` | `user_<random>@example.com` |
| `random_phone` | `random_phone()` | 11 位手机号 |
| `random_name` | `random_name()` | 中文 + 英文姓名池 |
| `random_enum` | `random_enum(...)` | 从参数中随机选一个 |
| `random_uuid` | `random_uuid()` | 标准 UUID |

**Lua 沙箱**：禁用 `os` / `io` / `debug` / `package` / `require` / `loadfile` / `dofile` / `loadstring` / `load` / `rawget` / `rawset` / `rawequal` / `setfenv` / `getfenv` / `newproxy`。

> ⚠️ 嵌套 Lua table 通过 `insert()` 传递时会丢失（`readLuaTable` 中 `isTable → null`），列值必须使用 string / number / boolean / nil / java.time 类型。

---

### FUNCTION — 函数与存储过程

> MySQL / PostgreSQL / H2 **三个方言均完整实现** Routine 管理。

#### LIST — 函数/存储过程/触发器列表

```json
{"id":"r28","category":"FUNCTION","action":"LIST","connection":{"driver":"Postgresql",...},"payload":{"schema":"public"}}
// Response: [
//   {"name":"get_user_by_id","routine_type":"FUNCTION","return_type":"SETOF users","language":"plpgsql","security_definer":"SECURITY INVOKER","volatility":"STABLE","arg_count":"1","arg_names":"user_id","schema":"public","description":"...","trigger_table":""},
//   {"name":"create_order","routine_type":"PROCEDURE",...},
//   {"name":"sync_users_trigger","routine_type":"TRIGGER","trigger_table":"users",...}
// ]
```

#### INFO / GET_DDL / CREATE / DELETE / CALL / DEBUG / UPDATE

```json
// INFO（自动解析 routineType）
{"id":"r29","category":"FUNCTION","action":"INFO","connection":{...},"payload":{"name":"func_sync_t2_to_t1","schema":"public"}}
// Response: {"name":"...","routine_type":"FUNCTION","schema":"public","language":"plpgsql","return_type":"TRIGGER","volatility":"VOLATILE","security_definer":"SECURITY INVOKER","arg_count":"0","arg_names":"","description":"...","trigger_table":""}

// GET_DDL
{"id":"r30","category":"FUNCTION","action":"GET_DDL","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response data: "CREATE OR REPLACE FUNCTION public.get_user_by_id(...) ..." (string)

// CREATE（直接传完整 DDL）
{"id":"r31","category":"FUNCTION","action":"CREATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION calculate_total(price DECIMAL, tax_rate DECIMAL DEFAULT 0.1) RETURNS DECIMAL LANGUAGE plpgsql AS $$ BEGIN RETURN price * (1 + tax_rate); END; $$"}}
// Response: {"success":true,"message":"函数/存储过程创建成功"}

// DELETE
{"id":"r32","category":"FUNCTION","action":"DELETE","connection":{...},"payload":{"name":"old_function","routineType":"FUNCTION","schema":"public","ifExists":true,"cascade":false}}
// Response: {"success":true,"message":"函数/存储过程删除成功","name":"old_function","routineType":"FUNCTION"}

// CALL 函数
{"id":"r33","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"calculate_total","routineType":"FUNCTION","schema":"public","args":["100.00","0.15"]}}
// Response: {"result":115.0,"row_count":1}

// CALL 存储过程
{"id":"r33b","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"create_order","routineType":"PROCEDURE","schema":"public","args":["1","100","5"]}}
// Response: {"update_count":1}

// DEBUG（EXPLAIN + INFO + DEPENDENCIES）
{"id":"r34","category":"FUNCTION","action":"DEBUG","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response: [
//   {"type":"EXPLAIN","output":"[{\"Plan\":...}]"},
//   {"type":"INFO","output":"Function: ..."},
//   {"type":"DEPENDENCIES","output":"TABLE: users\nVIEW: user_summary"}
// ]

// UPDATE — 验证 DDL 语法（不创建）
{"id":"r35","category":"FUNCTION","action":"UPDATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION test_func(x INTEGER) RETURNS INTEGER AS $$ BEGIN RETURN x * 2; END; $$ LANGUAGE plpgsql"}}
// Response: {"valid":true,"message":"DDL 语法验证通过"}
```

---

### VIEW / INDEX / FOREIGN_KEY / TRIGGER — 对象管理

#### VIEW

```json
// LIST
{"id":"r36","category":"VIEW","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// CREATE
{"id":"r37","category":"VIEW","action":"CREATE","connection":{...},"payload":{"name":"v_users","definition":"SELECT id, name FROM users WHERE active = true","schema":"public"}}
// Response: {"created":"v_users"}
// DELETE
{"id":"r38","category":"VIEW","action":"DELETE","connection":{...},"payload":{"name":"v_users","ifExists":true,"schema":"public"}}
// GET_DDL
{"id":"r39","category":"VIEW","action":"GET_DDL","connection":{...},"payload":{"name":"v_users","schema":"public"}}
```

#### INDEX

```json
// LIST
{"id":"r40","category":"INDEX","action":"LIST","connection":{...},"payload":{"tableName":"users","schema":"public"}}
// CREATE（unique 默认 false）
{"id":"r41","category":"INDEX","action":"CREATE","connection":{...},"payload":{"tableName":"users","indexName":"idx_email","columns":["email"],"unique":false,"schema":"public"}}
// Response: {"created":"idx_email","tableName":"users"}
// DELETE
{"id":"r42","category":"INDEX","action":"DELETE","connection":{...},"payload":{"indexName":"idx_email","tableName":"users","schema":"public"}}
```

#### FOREIGN_KEY

```json
// LIST
{"id":"r43","category":"FOREIGN_KEY","action":"LIST","connection":{...},"payload":{"tableName":"orders","schema":"public"}}
// CREATE
{"id":"r44","category":"FOREIGN_KEY","action":"CREATE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","columns":["user_id"],"refTable":"users","refColumns":["id"],"onDelete":"CASCADE","onUpdate":"RESTRICT","schema":"public"}}
// Response: {"created":"fk_orders_user","tableName":"orders"}
// DELETE
{"id":"r45","category":"FOREIGN_KEY","action":"DELETE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","schema":"public"}}
```

#### TRIGGER

> Trigger 创建/删除通过 `category=FUNCTION, routineType="TRIGGER"` 完成（见上节）。

```json
// LIST
{"id":"r46","category":"TRIGGER","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// GET_DDL
{"id":"r47","category":"TRIGGER","action":"GET_DDL","connection":{...},"payload":{"name":"sync_users_trigger","schema":"public"}}
// Response data: "CREATE OR REPLACE TRIGGER sync_users_trigger\n  STATEMENT AFTER DELETE\n  ON users ..." (string)
```

---

### EXPORT — 数据导出

独立子进程运行，5 种格式，全链路 JDBC 游标流式，内存占用与数据总量无关。

**支持格式**：

| 格式 | 扩展名 | 说明 |
|---|---|---|
| `CSV` | `.csv` | UTF-8 BOM，字段自动转义 |
| `JSON_LINES` | `.jsonl` | 每行一个独立 JSON 对象 |
| `SQL_INSERT` | `.sql` | 必传 `tableName` |
| `EXCEL` | `.xlsx` | POI SXSSF，100 万行/Sheet 自动分页 |
| `PARQUET` | `.parquet` | 动态 Schema |

**请求 payload**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sql` | string | ✓ | 自定义 SELECT SQL |
| `outputDir` | string | ✓ | 输出目录 |
| `fileName` | string | ✓ | 文件名前缀（不含扩展名） |
| `format` | string | ✓ | `CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET` |
| `tableName` | string | 条件 | `SQL_INSERT` 必填 |
| `fetchSize` | int | — | JDBC 拉取批次，默认 1000 |
| `stopExportId` | string | — | 传入则停止指定导出任务 |

```json
// 启动导出
{"id":"r48","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"sql":"SELECT * FROM users","outputDir":"D:/exports","fileName":"users_2024","format":"CSV","fetchSize":1000}}
// 流式响应（每 1000 行或 200ms 一帧）：
// {"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false,"filePath":null,"error":null}}
// {"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":2000,"columnCount":5,"completed":false}}
// ...
// {"id":"r48","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"D:\\exports\\users_2024.csv","error":null}}

// 停止导出
{"id":"r48stop","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"stopExportId":"r48"}}
// Response: {"stopped":"r48"}
```

**MySQL 特殊处理**：自动 `fetchSize = Integer.MIN_VALUE` 启用服务端流式游标。
**PostgreSQL 特殊处理**：自动临时 `autoCommit = false` 启用服务端游标，导出完成后恢复。

---

### IMPORT — 数据导入（v2.16 新增）

与 EXPORT 对称的**读方向**：把本地文件读成行，批量插入目标表。**运行在主进程**（不走子进程）—— 导入只是「读文件 + 批量 prepared insert」，没有 POI / Parquet / Hadoop 重依赖；放主进程反而让**取消**可用：批次语句登记到 `StatementRegistry`，`SYSTEM.CANCEL` 能立刻打断正在执行的 `executeBatch()`，而不必等一个子进程被 kill。

**支持格式**：

| 格式 | `format` | 说明 |
|---|---|---|
| CSV | `CSV` | RFC-4180 风格状态机（引号字段、`""` 转义、字段内换行 / 分隔符、CRLF、反斜杠转义）—— **不是** `split(",")` |
| JSON Lines | `JSON_LINES` | 每行一个 JSON 对象（kotlinx-serialization） |

**请求 payload**（`ImportRunRequest`）：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `file_path` | string | — | 待导入文件绝对路径（必填；不存在 / 不可读 / 空路径 → `IllegalArgumentException`，消息带路径） |
| `format` | string | — | `CSV` / `JSON_LINES`（大小写不敏感） |
| `table_name` | string | — | 目标表（必填） |
| `schema` | string | `""` | 目标 schema |
| `batch_size` | int32 | 500 | 每批 insert 的行数 |
| `delimiter` | string | `,` | CSV 专用；取**第一个字符**，空串默认 `,` |
| `has_header` | `optional bool` | `true` | CSV 专用；见下 |
| `encoding` | string | `UTF-8` | 空串默认 UTF-8 |
| `truncate_first` | bool | `false` | 导入前 `TRUNCATE` 目标表（委托方言 SPI `Dialect.truncateTable`） |
| `ignore_errors` | bool | `false` | 见下 |
| `stop_import_id` | string | `""` | 与 `stop_export_id` 同构的停止入口 |

```json
{"id":"ri1","category":"IMPORT","action":"RUN_IMPORT","connection":{...},"payload":{
  "filePath":"/data/users.csv","format":"CSV","tableName":"users","delimiter":",","hasHeader":true,"batchSize":500
}}
// 流式响应：
// {"id":"ri1","success":true,"stream":true,"end":false,"importProgress":{"rowsRead":5000,"rowsInserted":5000,"rowsFailed":0,"message":"read=5000 inserted=5000 failed=0"}}
// ...
// {"id":"ri1","success":true,"stream":true,"end":true,"import":{"result":{"rowsRead":13308,"rowsInserted":13308,"rowsFailed":0,"success":true,"error":""}}}
```

**帧序列**：若干 `ImportProgressFrame`（`stream=true, end=false`，每读入 5000 行推一帧）+ 一条**终止帧**（`end=true`，携带 `ImportResultResponse`）。`ImportResultResponse` 字段：`rows_read` / `rows_inserted` / `rows_failed` / `success` / `error`。

**`has_header` 为什么是 `optional bool`**：proto3 的裸 `bool` 无法区分「调用方没传」与「显式传 `false`」，而这两者意图**相反** —— 猜错会把首行数据当表头吃掉。因此 `has_header` 用 `optional` 保留 presence 语义，**未设置时默认 `true`**（与 `DataListRequest.page_size` 同一套处理）。CSV 语义：

- `has_header=true` → 首行是列名，**不作为数据行**输出；
- `has_header=false` → **每一行都是数据**，列名必须来自 `targetColumns`（引擎从目标表元数据 `listColumns` 解析）；
- 两者都拿不到列名时在**构造期直接失败**，**不会**用数据值臆造列名；
- 参差不齐的行被**补齐 / 截断**，不整行拒绝；空记录（空行）跳过。

**JSON_LINES 语义**：`null` → 空串；数字 / 布尔 → 字面文本；字符串 → 反转义；嵌套对象 / 数组 → 紧凑 JSON 文本（**绝不静默丢弃**）。列名 = 首行的键集合。**格式错误 / 非对象行**抛 `IllegalStateException`，消息带 **1-based 物理行号**（空行也计入）。
> **已知限制**：kotlinx.serialization 即使在 `isLenient = false` 下也接受**未加引号的字符串值**（`{"id": oops}` → `{"id":"oops"}`）。要拒绝它需手写 JSON 扫描器。结构性错误（对象截断、非对象行、键未加引号）仍会被拒绝。

**单趟读取**：`ImportSource.rows()` 返回 `Iterator`（不是 `Sequence`），以便引擎**提前停止**并及时释放文件句柄；同一 `ImportSource` **第二次调用 `rows()` 抛异常**（单趟语义）。`ImportSource` 是 `ApiAutoCloseable`，内置读取器由调用方 `use {}` 关闭。

**`ignore_errors` 的取舍（重要）**：H2 的 `PreparedStatement.setString` **不会急切转换** —— 类型 / 约束错误在 `executeBatch()` 时才浮出，而批量里一行失败**无法归因**到具体行；且 PostgreSQL 会把失败语句所在的隐式事务整体作废，「整批失败后再逐行重试」只会连锁报同样的错。因此：

| `ignore_errors` | 行为 |
|---|---|
| `true` | 改用**逐行 `executeUpdate`**（不是 `addBatch` / `executeBatch`）：坏行被跳过并计入 `rowsFailed`，`success=true`，第一条被跳过行的原因摘要放在 `error` 里。**代价是吞吐更低** —— 用户显式选择容忍坏行时，正确性优先于吞吐 |
| `false`（默认） | 保持批量 `addBatch` / `executeBatch`，**遇错即停**：抛出根因，前端看到的是原因而不是一个统计数字 |

**原子性注意**：导入是**非事务**的，`executeBatch` 被 H2 逐元素执行，因此中途失败可能留下**已经提交的更早批次**。需要「全有或全无」时，把导入放进事务会话：先 `SYSTEM.BEGIN` 拿到 `session_id`，随导入请求下发，失败后 `SYSTEM.ROLLBACK`（该组合由集成测试覆盖）。

---

## 错误响应

```json
{"id":"req-99","success":false,"error":"Communications link failure: Unable to connect to host","stream":false,"end":false,"data":null}
```

---

## 测试

**619 个测试全通过（0 失败 / 0 错误，1 个 Windows-only `IpcConfigTest` 用例 skip）**：

```bash
./gradlew test
```

测试报告：
- `engine/build/reports/tests/test/index.html`
- `dialect-h2/build/reports/tests/test/index.html`
- `dialect-duckdb/build/reports/tests/test/index.html`
- `dialect-sqlite/build/reports/tests/test/index.html`

| 模块 / 套件 | 测试数 | 范围 |
|---|---|---|
| `dialect-h2:test` | 63 | H2 方言 SPI 方法全量 |
| `dialect-duckdb:test` | 81 | DuckDB 方言 SPI 方法全量（v2.7 新增） |
| `dialect-sqlite:test` | **62** | **SQLite 方言 SPI 方法全量（v2.8 新增）** |
| `shared:test` | 97 | 跨平台共享模块（`:shared`） |
| `engine-grpc-client:test` | 21 | `EngineClient` 的跨进程 gRPC 实现（v2.15 新增模块） |
| `desktopApp:test` | 18 | Compose Desktop UI 端到端 |
| `engine:test` | **277** | —（**v2.16 新增 89**：`SqlScriptSplitter` + `importer/*` 单测，以及 4 个集成测试类 Cancel / MultiStatement / Transaction / Import） |
| └ `ipc/IpcConfigTest` | 22 | CLI 参数解析 + 自动平台检测 + 错误路径（1 个 Windows-only 跳过；v2.15 新增 `--mode` 接受 / `--mode` 缺值拒绝 2 项） |
| └ `ipc/IpcTransportTest` | 7 | SPI 各实现构造 |
| └ `ipc/TcpIpcTransportIntegrationTest` | 1 | TCP loopback + gRPC round-trip |
| └ `ipc/UnixSocketIpcTransportIntegrationTest` | 2 | UDS + gRPC round-trip（`@EnabledOnOs(LINUX, MAC, FREEBSD)`） |
| └ `ipc/NamedPipeIpcTransportIntegrationTest` | 2 | 客户端 channel + serverBuilder 限制 |
| └ `pool/PoolManagerTest` | 11 | SHA-256 key + closeAll |
| └ `loader/DialectLoaderTest` | 7 | SPI 自动发现 + JDBC URL 前缀反查 |
| └ `integration/*HandlerIntegrationTest` | 62 | 11 个 handler × H2Fixture（typed proto builders 直接调 handler） |
| └ `integration/TypedRequestEnvelopeIntegrationTest` | 7 | 端到端 typed Request → dispatcher → typed Response |
| └ `integration/UserGrantsIntegrationTest` | 2 | USER.GRANTS 路由，H2 限制场景 |
| └ `integration/DataGenerateIntegrationTest` | 2 | DATA.GENERATE 流式进度 + 错误路径 |
| └ `integration/FunctionGetDdlIntegrationTest` | 2 | FUNCTION.GET_DDL dispatcher 路由 + H2 限制 |
| └ `integration/SqlExplainRouteIntegrationTest` | 2 | SQL.EXPLAIN 端到端路由（v2.6 之前未实现） |
| └ `integration/EnvelopeOptionsIntegrationTest` | 4 | dryRun / timeoutMs envelope 跨切面 |
| └ `integration/DuckDBHandlerIntegrationTest` | 27 | DuckDB 端到端：SCHEMA/TABLE/DATA/SQL/VIEW/INDEX/FK/FUNCTION/SYSTEM/LOCAL FILES/EXPORT（v2.7 新增） |
| └ `integration/SQLiteHandlerIntegrationTest` | **7** | **SQLite 端到端：SCHEMA/TABLE/DATA/VIEW/INDEX/SYSTEM（v2.8 新增）** |
| └ `integration/SystemListDriversIntegrationTest` | **8** | **LIST_DRIVERS 元数据枚举 + 5 方言分别验证（v2.8 新增）** |
| └ `integration/IdbEngineDirectTest` | **4** | **Direct 模式契约测试：非流式 / `invoke` 便捷 / 错误传播 / bootstrap 幂等（v2.9 新增）** |
| └ `integration/SystemHandlerIntegrationTest` 中 DISCONNECT 用例 | **4** | **`SYSTEM.DISCONNECT`：幂等（第二次 `closed=false`）/ 无池 no-op / dispatcher 路由 / `dryRun` 短路（v2.15 新增）** |
| └ `engine/SqlScriptSplitterTest` | **14** | **多语句分句器：引号 / 注释 / PG dollar-quoting / 顶层 `;` 切分 / 未闭合块注释（v2.16 新增）** |
| └ `importer/CsvReaderTest` | **18** | **CSV 状态机：引号与 `""` 转义 / CRLF / 参差行补齐 / 表头语义（v2.16 新增）** |
| └ `importer/JsonLinesReaderTest` | **13** | **JSON Lines：类型映射（null / 数字 / 布尔 / 嵌套）/ 行号报错 / 单趟读取（v2.16 新增）** |
| └ `importer/ImportFormatTest` | **5** | **`ImportFormat.parse` 大小写不敏感 + 非法格式拒绝（v2.16 新增）** |
| └ `importer/ImportSourceFactoryTest` | **13** | **默认值解析（delimiter / encoding / `has_header` presence）/ 文件校验 / `targetColumns` 兜底（v2.16 新增）** |
| └ `integration/CancelIntegrationTest` | **4** | **`SYSTEM.CANCEL`：命中运行中请求 / 未命中 / 未知 id 不抛异常（v2.16 新增）** |
| └ `integration/MultiStatementIntegrationTest` | **6** | **多语句脚本：按序执行 / 遇错即停 / `affected_rows` 合计（v2.16 新增）** |
| └ `integration/TransactionIntegrationTest` | **7** | **事务会话：BEGIN 固定连接 / COMMIT / ROLLBACK / 未知会话拒绝 / 空 `session_id` 回落（v2.16 新增）** |
| └ `integration/ImportIntegrationTest` | **9** | **导入端到端：CSV / JSON_LINES / `truncate_first` / `ignore_errors` 逐行 / 取消 / 事务回滚（v2.16 新增）** |

---

## 添加新方言

1. 创建 Gradle 模块，依赖 `api` 项目
2. 实现 `DatabaseDialect` 接口，声明 `override val driverName = "YourDriver"`
3. 在 `src/main/resources/META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect` 中写入实现类全限定名
4. 构建后将 JAR 放入 `engine/build/libs/dialects/` 目录
5. 重启引擎即自动加载，无需修改主引擎代码

**DuckDB 连接示例**（v2.7 新增 — 仅本地嵌入式，`host`/`port` 完全忽略，`database` 字段就是路径）：

```json
// 内存模式
{"connection": {"driver":"Duckdb", "database":""}}

// .duckdb 文件
{"connection": {"driver":"Duckdb", "database":"/data/analytics.duckdb"}}

// CSV 直查（无需 ETL；DuckDB 自动 attach）
{"connection": {"driver":"Duckdb", "database":"/data/events.csv"}}

// Parquet / JSON
{"connection": {"driver":"Duckdb", "database":"/data/events.parquet"}}
{"connection": {"driver":"Duckdb", "database":"/data/payload.json"}}

// Excel 走 Apache POI 预转换 → 临时 DuckDB（缓存避免重复转换）
{"connection": {"driver":"Duckdb", "database":"/data/report.xlsx"}}
```

DuckDB 方言支持：SCHEMA / TABLE（含自增 PK 走 `SEQUENCE + DEFAULT nextval`）/ DATA CRUD / SQL EXECUTE+EXPLAIN / VIEW / INDEX / FOREIGN_KEY（table-rebuild）/ FUNCTION（仅 MACRO）/ SYSTEM / EXPORT（5 种格式全链路透传）。**不支持**：USER / PRIVILEGE / TRIGGER（抛 `UnsupportedOperationException`）；FK `ON DELETE/UPDATE CASCADE/SET NULL/SET DEFAULT`（自动改写 `NO ACTION`）；PG/MySQL 风格 `$$ ... $$` 函数体。

**SQLite 连接示例**（v2.8 新增 — 仅本地嵌入式，`host`/`port`/`user`/`password` 全部忽略，`database` 字段就是路径）：

```json
// 内存模式
{"connection": {"driver":"Sqlite", "database":":memory:"}}

// SQLite 文件
{"connection": {"driver":"Sqlite", "database":"/data/local.db"}}
```

SQLite 方言支持：SCHEMA / TABLE（含自增 PK 走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`）/ DATA CRUD / SQL EXECUTE+EXPLAIN / VIEW / INDEX / FOREIGN_KEY（table-rebuild）/ SYSTEM / EXPORT（5 种格式全链路透传）。**不支持**：USER / PRIVILEGE / TRIGGER / FUNCTION（routines 概念，抛 `UnsupportedOperationException`）；`ALTER COLUMN`（MODIFY_COLUMN 仅 RENAME）；`ALTER TABLE ADD/DROP CONSTRAINT`（FK 走 table-rebuild）；`$$ ... $$` PG 函数体；MySQL/PG `ENUM` 类型。

### `SYSTEM.LIST_DRIVERS` —— 前端动态渲染"新建连接"表单

```json
{"id":"ui-1","category":"SYSTEM","action":"LIST_DRIVERS","connection":{},"payload":{}}
// Response: { items: [{driver_name:"Mysql", display_name:"MySQL", connection_type:"CLIENT_SERVER", default_port:3306, requires_host:true, ...}, {driver_name:"Sqlite", ...}, ...] }
```

**为什么需要**：前端不需要硬编码"哪些 driver 需要 host / port / user"，而是在启动时调一次 `LIST_DRIVERS`，拿到所有已加载方言的元数据，按 `displayName` 渲染表单、按 `requiresHost` 等标志决定输入框显隐、按 `capabilities` 决定哪些按钮（如 TRIGGER.LIST）可用。**返回顺序按 `driverName` 字典序升序**，保证前端渲染顺序稳定。

---

## 架构特性

- **gRPC + 强类型 per-Category 消息**：13 个 Category 各有自己的 `oneof body` 消息，wire 上是标准 protobuf，无 stringly-typed payload；`repeated google.protobuf.Value` 承载方言差异化的 item 形状
- **gRPC 1.76 + grpc-kotlin 协程服务端（v2.5）**：`IdbEngineCoroutineImplBase` + suspend `handle()` → `Flow<Response>`；`addService(IdbEngineImpl().bindService())` 挂载服务
- **业务层 Kotlin DSL end-to-end（v2.5）**：13 个 handler + `RequestDispatcher` + 11 个集成测试全部以 `xxxRequest { ... }` / `xxxResponse { ... }` / `request { ... }` / `response { ... }` DSL 形态编写；`google.protobuf.Value` 因属 Well-Known Type 无生成 DSL，仍走 `Value.newBuilder()`
- **跨平台 IPC Transport**：TCP / UDS / Named Pipe 三实现，CLI `--ipc` 参数选择，业务层零感知
- **方言插件化**：方言以独立 JAR 通过 SPI 动态加载
- **绝对无状态**：每次请求携带完整连接凭证
- **连接池复用**：基于 SHA-256 Hash（包含 password）缓存 HikariCP 实例，10 分钟空闲自动释放
- **流式大数据**：DATA LIST（pageSize=0）/ SQL SELECT / GENERATE / EXPORT / IMPORT 均通过 JDBC 游标逐行拉取
- **SQL 注入防护**：DATA CRUD 强制 `PreparedStatement`；`where`/`orderBy` 片段方言级校验
- **损坏输入容错**：grpc 框架自动将传输层错误转为 `StatusException`
- **日志隔离**：所有日志输出到滚动文件 (`~/.config/idb/logs/idb-engine.log`)
- **导出子进程隔离**：防止大数据量导出时 OOM 主进程
- **LuaJIT 造数引擎**：LuaJIT + Lua 5.1~5.5 多版本切换、沙箱隔离、流式进度
- **查询取消（v2.16）**：`StatementRegistry` 统一登记「运行中请求 → 可取消目标」（`CancelTarget` 既包装 JDBC `Statement`，也承载任意取消动作），`SYSTEM.CANCEL` 立即 `Statement.cancel()`；`DATA.GENERATE` 因 Lua 阻塞在 JNI 里而额外用标志位 + `activeStmt` 阻断
- **数据导入（v2.16）**：主进程内 CSV / JSON_LINES → 批量 INSERT，流式进度 + 可取消 + 可事务回滚
- **事务会话（v2.16）**：`TransactionManager` 钉住一条连接，`session_id` 留空即完全回落到旧行为（向后兼容）
- **多语句脚本（v2.16）**：`SqlScriptSplitter` 词法分句，按序执行、遇错即停
- **完整 Routine / View / Index / FK / Trigger 管理**
- **端到端强类型 Handler**：14 个 handler 全部接收 typed per-Category proto 消息、返回 typed `<Category><Action>Response` 消息；无 `JsonObject` payload 解析；`RequestDispatcher` 是 (Category, Action) → handler 的薄路由层

---

## 技术栈

- Kotlin 2.4.0 / JDK 25
- grpc-netty-shaded 1.83.1 + grpc-stub + grpc-protobuf + grpc-kotlin-stub 1.5.0（协程服务端 + Kotlin DSL 生成）
- protoc 3.25.5 + protoc-gen-grpc-java 1.68.0 + protoc-gen-grpc-kotlin 1.4.1（工具链锁定）
- kotlinx-coroutines 1.11.0
- kotlinx-serialization-json 1.11.0
- protobuf-kotlin-lite 4.35.1（生成 *Kt DSL builder：`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }`）
- HikariCP 7.0.2
- MySQL Connector/J 9.7.0 / PostgreSQL JDBC 42.7.11 / H2 2.3.232 / DuckDB JDBC 1.5.5.1（v2.7 新增） / **SQLite JDBC 3.46.1.3（v2.8 新增，driver `Sqlite`）**
- SLF4J 2.0.18 + Logback 1.5.13
- LuaJIT 4.1.0（luajava）
- Apache POI 5.5.1（poi-ooxml — Excel 流式导出 + DuckDB Excel 预转换）
- Apache Parquet 1.17.1 + Hadoop 3.5.0

---

## 架构升级历史 (Architecture Migration Log)

| 版本 | 通信协议 | 备注 |
|---|---|---|
| v1.0 | stdin/stdout + 4-byte BE uint32 长度前缀 + 自定义 kotlinx-serialization-protobuf | 旧版管道协议 |
| v2.0 | gRPC over HTTP/2 + 标准 google.protobuf.Value | 替换为标准 gRPC；导出子进程同样切换为 gRPC；移除 stdin/stdout 依赖 |
| v2.1 | gRPC + 跨平台 IPC Transport SPI | 在 gRPC 之上抽象 `IpcTransport` 接口，默认 TCP；CLI 参数 `--ipc` 切换 UDS / Named Pipe |
| v2.2 | gRPC + 强类型 Request/Response + CLI Args | `Request.payload` 与 `Response.data` 由 `map<string, Value>` 替换为 per-Category typed protobuf 消息；IPC 选择改为 CLI 参数 |
| v2.3 | gRPC + 强类型 Handlers end-to-end | 13 个 handler 全部接收 typed proto 消息、返回 typed `<Category><Action>Response` 消息；`TypedRequestMapper` / `TypedResponseMapper` 删除；`RequestDispatcher` 简化为薄路由层；179 测试全通过 |
| v2.4 | gRPC + 强类型 per-list-item 消息 | 8 个 typed per-list-item 消息（`TableListItem` 等）取代遗留 `repeated google.protobuf.Value`；动态行用 typed `Row` wrapper |
| v2.5 | gRPC 1.76 + grpc-kotlin 协程服务端 + Kotlin DSL end-to-end | gRPC 依赖 `1.68.0` → `1.76.0`，接入 `grpc-kotlin-stub 1.4.1`；服务端 `IdbEngineCoroutineImplBase`（suspend `handle()` → `Flow<Response>`）；protoc 工具链锁定 `protoc 3.25.5` + `protoc-gen-grpc-java 1.68.0` + `protoc-gen-grpc-kotlin 1.4.1`；启用 `protobuf-kotlin-lite` 生成 Kotlin DSL；13 个 handler + `RequestDispatcher` + 11 个集成测试全部切到 DSL 形态；移除 `grpc-core` / `protobuf-java-util` / `ksp` / `kotlinx-serialization-protobuf` 无用依赖；179 测试全通过 |
| v2.6 | 表驱动 Dispatcher + 跨切面 Envelope Options | `RequestDispatcher` 重构：9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` when 分支 → 单个 typed `routes` map（`Pair<Category, Action>` → `Route`）；新 (Category, Action) 仅需一个 map 条目；消除 `wrapTypedResponse` 中静默 `else -> {}` 兜底；`SQL.EXPLAIN` 路由打通（之前 handler 存在但 dispatcher 未路由）。新增 `RequestOptions { trace_id, dry_run, timeout_ms }`：MDC 注入 `trace_id`；`dryRun=true` + write action 直接短路返回 success（不修改数据库）；`timeoutMs>0` 包 `withTimeoutOrNull` 超时返回 `error="timeout"`。`if_exists` / `if_not_exists` 在 SCHEMA/TABLE/INDEX/FOREIGN_KEY 路径下贯通（v2.6 之前仅 VIEW/FUNCTION.DELETE 支持）。191 测试全通过（128 engine + 63 H2）|
| **v2.7** | **DuckDB 方言插件（本地嵌入式 OLAP）** | 新增 `dialect-duckdb` 模块（driver `Duckdb`，JDBC `org.duckdb.DuckDBDriver` 1.5.5.1），仅本地嵌入式（内存 / `.duckdb` / `.csv` / `.parquet` / `.json` / `.xlsx`）；`host`/`port` 完全忽略，`database` 字段即路径。Excel 走 Apache POI 5.5.1 预转换为临时 DuckDB（`ExcelToDuckDbCache` 缓存避免重复转换）。**自增主键**用 `SEQUENCE + DEFAULT nextval + 表级 PRIMARY KEY` 兜底（因 DuckDB 1.5.5.1 不接受 `IDENTITY`+表级 PK 组合，也不支持 `INTEGER PRIMARY KEY` ROWID 自填充）。**FK** 走 table-rebuild（因 DuckDB 不支持 `ALTER TABLE ADD/DROP CONSTRAINT` + 忽略 `CONSTRAINT fk_name`，自动生成 `<table>_<cols>_fkey`）。`SHOW CREATE TABLE/VIEW` 解析失败 → 手动从 `information_schema` 重建 DDL。`duckdb_functions()` MACRO 列名 `macro_definition`（不是 `definition`/`description`）。`duckdb_constraints()` 无 `column_name`，只有 `constraint_column_names` (LIST)。FK `ON DELETE/UPDATE CASCADE/SET NULL/SET DEFAULT` 不支持 → 全部改写为 `NO ACTION`。同文件不同连接配置冲突 → 测试 fixture 强制走 `PoolManager`（不混用 `DriverManager`）。**新 SPI 方法** `DatabaseDialect.buildPreCreateStatements(tableName, autoIncrementColumns): List<String>`（默认空实现）。gRPC 依赖 `1.76.0` → `1.83.1`，`grpc-kotlin-stub` `1.4.1` → `1.5.0`，`protobuf-kotlin-lite` `3.25.8` → `4.35.1`。299 测试全通过（81 DuckDB + 63 H2 + 155 engine）|
| **v2.8** | **SQLite 方言插件 + SPI 连接元数据扩展 + SYSTEM.LIST_DRIVERS** | 新增 `dialect-sqlite` 模块（driver `Sqlite`，JDBC `org.sqlite.JDBC` 3.46.1.3），仅本地嵌入式（`:memory:` / `.db` 文件）；`host`/`port`/`user`/`password` 全部忽略，`database` 字段即路径。**自增主键**走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`（必须 INTEGER 类型；`TableHandler.create` 检测到 `autoIncrementColumns` 时跳过表级 `PRIMARY KEY` 子句避免 "more than one primary key"）。**FK** 走 table-rebuild（CREATE temp AS SELECT → DROP → CREATE with FK → INSERT → DROP temp），`addForeignKey` 不支持 CONSTRAINT 子句名（SQLite CREATE TABLE 语法）。`MODIFY_COLUMN` 仅支持 RENAME（SQLite 无 ALTER COLUMN）。`TRUNCATE` 用 `DELETE FROM` + 重置 `sqlite_sequence`。多 database 走 `ATTACH/DETACH`。USER / PRIVILEGE / TRIGGER / FUNCTION（routines）抛 `UnsupportedOperationException`。SQL 危险关键词额外禁用 `ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` / `VACUUM` / `REINDEX`。**SPI 元数据扩展**：`DatabaseDialect` 新增 `displayName` / `connectionType` / `requiresHost` / `requiresPort` / `defaultPort` / `supportsUser` / `supportsPassword` / `supportsSchema` / `supportsCrossDatabase` / `jdbcUrlExample` / `capabilities` 11 个属性（默认实现，**完全向后兼容**）。新增 `ConnectionType` 枚举（`CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY`）+ `DialectCapability` 枚举（12 个能力标签）。**`SYSTEM.LIST_DRIVERS` action（Action 18）**：枚举所有已加载方言并返回 `repeated DialectInfo` 元数据，供前端**动态渲染"新建连接"表单**；`RequestDispatcher` 表驱动新增 `(SYSTEM, LIST_DRIVERS) → Route` 一条；`DialectLoader.getAllDialects()` 提供枚举入口；`items` 按 `driverName` 字典序升序。**376 测试全通过（1 个 Windows-only skip），0 失败 / 0 错误**（62 SQLite + 81 DuckDB + 63 H2 + 170 engine）|
| **v2.9** | **KMP Desktop Direct 模式 + 双模式架构** | 前端框架从 Wails（v3 gRPC 子进程）正式迁移到 **Kotlin Multiplatform Compose Desktop**（`desktopApp/` Gradle 模块），引擎与 UI 同 JVM 部署。为消除 gRPC channel / IPC transport / protobuf 序列化等冗余开销，新增 **`com.kxxnzstdsw.engine.IdbEngine` facade**：`handle(Request): Flow<Response>` 与 gRPC stub 同形，`invoke(connection, configure): Response` 为单条便捷方法；`IdbEngineImpl` 重构为 `IdbEngine.handle()` 的薄壳，两条路径共享 `RequestDispatcher` 与全部 envelope options（`traceId` / `dryRun` / `timeoutMs`）。新增 CLI 参数 `--mode <grpc\|direct>`：`direct` 模式仅 bootstrap drivers/dialects 后阻塞主线程，供 shell 测试 / 守护进程 / 嵌入式场景使用。**`RequestDispatcher.dispatch` catch 移到 `.catch{}` operator**（v2.9 修复）：原 `flow{}` 内部 try/catch 会错误捕获下游短路算子（如 `first()` / `takeWhile`）抛出的 `AbortFlowException` 并再次 emit，触发 *"Flow exception transparency violated"*；外置后 `AbortFlowException` 正常向上传播。`desktopApp/build.gradle.kts` 新增 `implementation(project(":engine"))`，UI 与引擎共享方言 / 驱动 / 连接池 / 线程池生命周期。**新增 IdbEngineDirectTest（4 项 direct 模式契约测试）**：非流式响应、`invoke` 便捷、错误传播、bootstrap 幂等性。**380 测试全通过（1 个 Windows-only skip），0 失败 / 0 错误**（62 SQLite + 81 DuckDB + 63 H2 + 174 engine）|
| **v2.11** | **仅凭 JDBC URL 初始化连接 + 连接生命周期直连方法** | proto `ConnectionConfig` 新增 `jdbc_url`：非空时 `PoolManager` 直接用它建 HikariCP 池（`host`/`port`/`database` 忽略），pool key 纳入 `jdbc_url`。方言反查：`DatabaseDialect.jdbcUrlPrefix`（默认 `jdbc:<driverName 小写>:`）+ `DialectLoader.getDialectByJdbcUrl()` 最长前缀匹配；无匹配时 `PoolManager.resolveDialect` 抛可读错误而非回退 `driver`。`IdbEngine` facade 新增 `testConnection(config)` / `testConnection(jdbcUrl, user, password)` —— 不经 gRPC / IPC / `RequestDispatcher` envelope，直接调 `SystemHandler.testConnection`，首次调用即建/复用连接池（**连接初始化**）并做 JDBC `isValid(5)` |
| **v2.12** | **连接生命周期补全（disconnect）+ 方言装配双通道** | `IdbEngine.disconnect(config)` → `PoolManager.close(config)`：释放该配置下**所有** schema 维度的连接池，与 `testConnection` 构成对称生命周期；pool key 由单段 hash 改为两段式 `sha256(配置)#sha256(schema)`（单段 hash 无法按配置定位池），新增 `activePoolCount()` 供诊断 / 测试断言。`DialectLoader.loadFromDir` 新增**应用类路径 SPI**通道（`ServiceLoader<DatabaseDialect>`，用自身类加载器）并在其后用 `dialects/` 目录插件覆盖同名方言 —— Direct 模式（UI 与引擎同 JVM）无需外部 `dialects/` 目录，`desktopApp` 以 `runtimeOnly(project(":dialect-*"))` + 5 个 JDBC 驱动装配；修复了此前 Direct 模式下方言注册表为空、`testConnection` 必然报 `No dialect plugin matches JDBC URL` 的问题 |
| **v2.15** | **调用层抽象：`EngineClient` + proto 下沉 + `SYSTEM.DISCONNECT`** | 新增 **`EngineClient` 接口**（`:engine-protocol`，`package com.kxxnzstdsw.client`）：`handle` / `invoke`（default）/ `testConnection(config)` / `testConnection(jdbcUrl, user, password)`（default）/ `disconnect` / `close`。`IdbEngine` 改为其**同进程实现**（`invoke` 与 JDBC URL 重载从类中移除，成为接口 default 方法）。proto 契约（`idb_engine.proto` / `idb_export.proto`）与 protobuf gradle 插件块从 `:engine` 移入新模块 **`:engine-protocol`**（`engine` 改为 `api(project(":engine-protocol"))`）；新增 **`:engine-grpc-client`** 提供同一接口的**跨进程 gRPC 实现** `GrpcEngineClient`（仅依赖 `:engine-protocol`）。新增 `SYSTEM.DISCONNECT`（`Action = 19`）：`SystemDisconnectResponse { bool closed = 1; }` + `SystemResponse.disconnect = 5`，handler `SystemHandler.disconnect(config)`（`suspend`，`withContext(Dispatchers.IO)` 内 `PoolManager.close(config)`），dispatcher 表驱动路由 `SYSTEM/DISCONNECT` → `b.system = systemResponse { disconnect = ... }`，并列入 `writeActions` 使 `dryRun` 短路；**原因**：连接池在引擎进程内，远端调用方需要 wire 路由才能释放；**幂等**（第二次 `closed=false`）。`IpcConfig.fromArgs` 修复：`--mode <value>` 跳过（由 `IdbEngineServer.parseMode` 单独解析），此前 `java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051` 报 `Unknown argument: '--mode'`。**394 测试全通过（188 engine + 63 H2 + 81 DuckDB + 62 SQLite）** |
| **v2.16 (当前)** | **四大写路径能力：查询取消 + 数据导入 + 事务会话 + 多语句脚本** | 新增 6 个 action：`CANCEL = 20` / `RUN_IMPORT = 21` / `BEGIN = 22` / `COMMIT = 23` / `ROLLBACK = 24` / `SESSION_INFO = 25`，新 Category `IMPORT = 14`，`Request.session_id = 6`。**查询取消**：`com.kxxnzstdsw.engine.StatementRegistry`（`ConcurrentHashMap<String, CancelTarget>`，`CancelTarget` 为 `fun interface { fun cancel() }`）登记「运行中请求 → 可取消目标」，`SYSTEM.CANCEL` 对其调 `Statement.cancel()`（协程取消无法中断阻塞的 JDBC 调用）；接入 `SQL.EXECUTE` / `DATA.LIST`（`pageSize=0`）/ `DATA.GENERATE`（Lua 阻塞在 JNI，改由 `GenerateState.cancelled` 标志 + `activeStmt` 阻断，已跑批次不回滚）；被取消请求以**原 id** 返回 `success=false, error="cancelled"`。**数据导入**：新 Kotlin 包 `com.kxxnzstdsw.importer`（`ImportSource` / `ImportFormat` / `CsvReader`（RFC-4180 状态机）/ `JsonLinesReader` / `ImportSourceFactory`）+ `ImportHandler.executeInMainProcess`，**主进程**运行（正因如此批次可登记取消），CSV / JSON_LINES 批量插入；`has_header` 为 `optional bool`（未设置默认 `true`）；`ignore_errors=true` 改逐行 `executeUpdate`（坏行可归因，牺牲吞吐），默认 `false` 批量遇错即停；非事务导入失败可能残留已提交批次，需「全有或全无」时配合事务会话回滚。**事务**：`com.kxxnzstdsw.pool.TransactionManager` 钉住连接 + `autocommit=false`，`Request.session_id` 留空 = 无事务（与 v2.15 完全一致，向后兼容）；未知会话的写操作**拒绝**而非静默自动提交；`maximumPoolSize = 5` → 单配置最多 5 个并发会话（刻意背压）。**多语句**：`SqlScriptSplitter` 单趟词法分句（引号 / 注释 / PG dollar-quoting，仅顶层 `;`），按序执行、遇错即停，逐条结果入 `SqlExecuteResponse.statements`。**619 测试全通过（277 engine + 63 H2 + 81 DuckDB + 62 SQLite + 97 shared + 21 engine-grpc-client + 18 desktopApp）** |

---

## 相关文档

| 文档 | 内容 |
|---|---|
| [`../api/README.md`](../api/README.md) | 公共 SPI：`DatabaseDialect` + `ConnectionType` + `DialectCapability` |
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | proto 契约（`idb_engine.proto` / `idb_export.proto`）与 `EngineClient` 调用层接口（v2.15 起） |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | `EngineClient` 的跨进程 gRPC 实现 `GrpcEngineClient`（v2.15 新增） |
| [`../dialect-mysql/README.md`](../dialect-mysql/README.md) | MySQL 方言插件 |
| [`../dialect-postgresql/README.md`](../dialect-postgresql/README.md) | PostgreSQL 方言插件 |
| [`../dialect-h2/README.md`](../dialect-h2/README.md) | H2 方言插件 |
| [`../dialect-duckdb/README.md`](../dialect-duckdb/README.md) | DuckDB 方言插件 |
| [`../dialect-sqlite/README.md`](../dialect-sqlite/README.md) | SQLite 方言插件 |
| ARCHITECTURE.md | 引擎内部架构：dispatcher / 池 / 传输 / 协议演进 |
