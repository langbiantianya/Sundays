# Engine 架构（大纲）

> 引擎模块（`engine/`）内部架构的**索引页**：只保留模块定位、分层职责、关键设计决策的落点、以及请求链路。
> 每个决策的完整论证、字段语义与实现细节都在对应源文件的 KDoc 里，就地维护、以代码为准。
>
> - 用户级用法、构建与运行 CLI：[`README.md`](./README.md)
> - gRPC 协议字段与枚举：[`idb_engine.proto`](../engine-protocol/src/main/proto/idb_engine.proto)
> - 跨进程客户端：[`engine-grpc-client/README.md`](../engine-grpc-client/README.md)
> - 项目整体拓扑与版本演进：根目录 [`ARCHITECTURE.md`](../ARCHITECTURE.md)

## 1. 模块定位

引擎是**无头（headless）的数据库算力引擎**：对外以 gRPC 服务（或同进程直连）提供能力，对内做方言无关的请求分发与连接管理。
它不持久化业务状态、不认识界面语言以外的任何客户端语义 —— 每次请求自带完整连接凭证，连接用完即归还。

三条不变量贯穿整个模块：

- **强类型端到端** —— handler 直接收发 typed per-Category proto 消息，业务层没有 JSON 编解码边界。
- **绝对无状态** —— 不记忆「当前选中的数据库」，连接凭证随请求而来。
- **方言插件化** —— 新增一种数据库只需实现 `DatabaseDialect` SPI，不改引擎主代码。

## 2. 分层与包职责

| 包 | 一句话职责 |
|---|---|
| [`engine/`](./src/main/kotlin/com/langbiantianya/engine/IdbEngine.kt) | `IdbEngine`（`EngineClient` 的同进程实现）、`StatementRegistry`（取消通道）、`SqlScriptSplitter`（多语句分句）、`EngineLanguageContext`（请求语言上下文） |
| [`dispatcher/`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) | `RequestDispatcher`：`(Category, Action)` → handler 的薄路由；dryRun 短路、超时、语言上下文、异常兜底都在这里 |
| [`handlers/`](./src/main/kotlin/com/langbiantianya/handlers/SystemHandler.kt) | 14 个业务 handler，typed proto 进 / typed proto 出（System / Schema / User / Table / Data / SQL / Function / View / Index / ForeignKey / Trigger / Export / Import / Generate） |
| [`analysis/`](./src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt) | `AnalysisHandler`：`ANALYSIS` 四条路由（列命名空间 / 列表 / 列列 / 在计算引擎上跑分析 SQL）—— 数据分析模式的引擎侧入口 |
| [`metadata/`](./src/main/kotlin/com/langbiantianya/metadata/JdbcColumnMetadata.kt) | JDBC `getColumns` 的列元数据读取 —— **自增标记**的唯一来源（各方言的 `listColumns` 都不吐这一项） |
| [`pool/`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) | `PoolManager`（SHA-256 keyed HikariCP 连接池）与 `TransactionManager`（事务会话钉连接） |
| [`loader/`](./src/main/kotlin/com/langbiantianya/loader/DialectLoader.kt) | JDBC 驱动与方言 SPI 的双通道加载（应用类路径 + `dialects/` 目录） |
| [`ipc/`](./src/main/kotlin/com/langbiantianya/ipc/IpcTransport.kt) | IPC Transport SPI 与 CLI 配置解析；[`ipc/impl/`](./src/main/kotlin/com/langbiantianya/ipc/impl/TcpIpcTransport.kt) 是 TCP / UDS / Named Pipe 三种实现 |
| [`server/`](./src/main/kotlin/com/langbiantianya/server/IdbEngineServer.kt) | `IdbEngineServer`（bootstrap、CLI、优雅关闭）与 `IdbEngineImpl`（gRPC 路径薄壳） |
| [`grpc/`](./src/main/kotlin/com/langbiantianya/grpc/PayloadAdapter.kt) | `PayloadAdapter`：protobuf `Value` ↔ Kotlin 值的边界适配 |
| [`export/`](./src/main/kotlin/com/langbiantianya/export/ExportEngine.kt) | 导出引擎、各格式写器、以及导出子进程的编排与生命周期管理 |
| [`importer/`](./src/main/kotlin/com/langbiantianya/importer/ImportSource.kt) | 导入读源（CSV / JSON_LINES）、格式解析与默认值推导 |

方言 SPI 本身不在本模块：[`api/.../dialect/DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt)。

## 3. 关键设计决策索引

> 每行只给结论与落点；「为什么」与失败模式读 KDoc。改引擎前先读落点文件顶部注释。

| # | 决策 | 结论（一句话） | 源码 KDoc |
|---|---|---|---|
| 1 | 强类型端到端路由 | handler 只接 typed per-Category proto，dispatcher 只路由，无 `JsonObject` 中间层 | [`RequestDispatcher.kt`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) |
| 2 | 异常兜底外置 | try/catch 必须在 `.catch{}` **外侧**，否则 `AbortFlowException` 被吞、触发 flow 透明性违例 | [`RequestDispatcher.kt`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) |
| 3 | dryRun 短路含副作用路由 | `DISCONNECT` / `RUN_IMPORT` 列入 `writeActions`：预演必须真的跳过，否则会关掉线上池、清空目标表 | [`RequestDispatcher.kt`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) |
| 4 | 绝对无状态 | 引擎不记「当前库」，凭证随请求而来；连接池只是缓存，不是状态 | [`PoolManager.kt`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) |
| 5 | 两段式 pool key | `sha256(config)#sha256(schema)`，让 `close(config)` 能按配置前缀反查该配置下**所有** schema 维度的池 | [`PoolManager.kt`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) |
| 6 | 池容量即背压 | `maximumPoolSize=5` / `connectionTimeout=5s`；会话数触顶是刻意背压，不是 bug | [`PoolManager.kt`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) |
| 7 | catalog 运行时切换 | `switchCatalog` 默认空实现（不切换、永不抛），只有 MySQL 覆盖；每次借出连接时切一次 | [`DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) |
| 8 | catalog 建池时改写 | PG 的库是连接启动参数，`jdbcUrlForCatalog` 在建池时改 URL 的库名段，并校验 catalog 不重解析 URL 结构 | [`DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) |
| 9 | 切库失败必须照抛 | 吞掉异常会让查询静默跑在**错误的库**上；H2 的 `setCatalog` 是静默 no-op，跨库行为只能打真 MySQL 验证 | [`MySQLDialect.kt`](../dialect-mysql/src/main/kotlin/com/langbiantianya/dialect/MySQLDialect.kt) |
| 10 | 连接所有权决定归还时机 | 无 `session_id` 时 handler 在 `finally` 归还连接；有 `session_id` 时由会话钉住，直到 COMMIT / ROLLBACK | [`TransactionManager.kt`](./src/main/kotlin/com/langbiantianya/pool/TransactionManager.kt) |
| 11 | 关池前先关会话 | `PoolManager.close` 必须先 `closeSessionsFor`，否则钉住的连接被从会话底下关掉，COMMIT 落在死连接上 | [`PoolManager.kt`](./src/main/kotlin/com/langbiantianya/pool/PoolManager.kt) |
| 12 | 未知 session 直接拒绝 | 已提交 / 已回滚 / 引擎重启后的 `sessionId` 写操作失败，绝不静默降级成自动提交 | [`TransactionManager.kt`](./src/main/kotlin/com/langbiantianya/pool/TransactionManager.kt) |
| 13 | 单一取消通道 | `CancelTarget` 抽象成 `fun cancel()`，JDBC 语句 / 导入 / 造数共用一条 `SYSTEM.CANCEL` 路径 | [`StatementRegistry.kt`](./src/main/kotlin/com/langbiantianya/engine/StatementRegistry.kt) |
| 14 | LuaJIT 阻塞靠显式标志 | luajava 在 JNI 里阻塞，协程取消与 `Statement.cancel()` 都进不去：`cancelled` 入口检查抛异常解开脚本 + `activeStmt` 打断当前批次；已执行批次不回滚 | [`GenerateHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/GenerateHandler.kt) |
| 15 | 多语句词法分句 | 引号 / 注释 / dollar-quoting 感知的顶层 `;` 切分，按序执行、遇错即停 | [`SqlScriptSplitter.kt`](./src/main/kotlin/com/langbiantianya/engine/SqlScriptSplitter.kt) |
| 16 | 导入在主进程运行 | CSV / JSON_LINES 流式直插，主进程不背 POI / Parquet / Hadoop 负担 —— 这也是导入可取消的前提 | [`ImportHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/ImportHandler.kt) |
| 17 | 读源是状态机 | CSV 处理引号转义 / CRLF / 参差行补齐；JSON Lines 单趟读取并带行号报错 | [`CsvReader.kt`](./src/main/kotlin/com/langbiantianya/importer/CsvReader.kt) · [`JsonLinesReader.kt`](./src/main/kotlin/com/langbiantianya/importer/JsonLinesReader.kt) |
| 18 | 导出子进程隔离 | 导出跑在 `-Xmx512m` 子进程，父进程只转发进度帧，避免大导出让主进程 OOM | [`ExportProcessManager.kt`](./src/main/kotlin/com/langbiantianya/export/ExportProcessManager.kt) |
| 19 | 每层流式路由必须收口 | 缺一层收口就退化成静默失败，界面表现是「点了导出，什么也没发生」 | [`ExportHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/ExportHandler.kt) |
| 20 | `onNext` 不是线程安全的 | 并发导出时父子两侧都要加锁（子进程侧发帧锁、父进程侧发命令锁），否则整条流当场废掉 | [`ExportProcessManager.kt`](./src/main/kotlin/com/langbiantianya/export/ExportProcessManager.kt) |
| 21 | hub 地址写死 `127.0.0.1` | `localhost` 在 Windows 可能解析成 `::1`，同机进程没有「跨主机」，一律走 IPv4 回环 | [`ExportProcessManager.kt`](./src/main/kotlin/com/langbiantianya/export/ExportProcessManager.kt) |
| 22 | 子进程存活用两个判据 | `hasUsableChannel` 判「能不能发命令」、`Process.isAlive` 判「要不要重启」；重启前先 `stop()` 清陈旧标记 | [`ExportProcessManager.kt`](./src/main/kotlin/com/langbiantianya/export/ExportProcessManager.kt) |
| 23 | 导出排障入口 | 父子日志同落 `export-manager.log`、子进程 stderr 重定向到 `export-subprocess.log`；断流先看父进程侧 | [`ExportSubProcess.kt`](./src/main/kotlin/com/langbiantianya/export/ExportSubProcess.kt) |
| 24 | `Value` 只有 double | 整数经 protobuf 往返变 `3.0`，业务层 `?: 0` 兜底会安静地变成 0；边界统一还原 | [`PayloadAdapter.kt`](./src/main/kotlin/com/langbiantianya/grpc/PayloadAdapter.kt) |
| 25 | 方言双通道加载 | 先扫应用类路径（`ServiceLoader`），再扫 `dialects/` 目录（`URLClassLoader`，同名覆盖），Direct 模式必须同类加载器 | [`DialectLoader.kt`](./src/main/kotlin/com/langbiantianya/loader/DialectLoader.kt) |
| 26 | 调用层接口优先 | 调用方只面向 `EngineClient`；同进程 `IdbEngine` 与跨进程 `GrpcEngineClient` 是同一接口的可替换实现，共享 proto 与路由语义 | [`EngineClient.kt`](../engine-protocol/src/main/kotlin/com/langbiantianya/client/EngineClient.kt) · [`GrpcEngineClient.kt`](../engine-grpc-client/src/main/kotlin/com/langbiantianya/client/grpc/GrpcEngineClient.kt) |
| 27 | 两条路径汇合于一处 | gRPC 路径是薄壳，direct 路径跳过序列化与 IPC 派发，两者共用同一 dispatcher，横切语义完全一致 | [`IdbEngine.kt`](./src/main/kotlin/com/langbiantianya/engine/IdbEngine.kt) · [`IdbEngineImpl.kt`](./src/main/kotlin/com/langbiantianya/server/IdbEngineImpl.kt) |
| 28 | 连接生命周期对称 | `testConnection` 旁路 envelope 调 handler，首次调用即建池（= 初始化连接）；`disconnect` 释放同配置全部池，远程调用方经 `SYSTEM.DISCONNECT` 获得对等能力 | [`SystemHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/SystemHandler.kt) |
| 29 | 两级导航是引擎层契约 | `listDatabases` / `listSchemas` 每个方言都要按约定实现，前端据此构建 database → schema 两级导航 | [`SchemaHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/SchemaHandler.kt) |
| 30 | 错误原样上屏，语言随请求走 | `Response.error` 不翻译就是缺陷；语言经 `ThreadContextElement` + `flowOn` 搬进 handler，协程换线程才不丢 | [`EngineLanguageContext.kt`](./src/main/kotlin/com/langbiantianya/engine/EngineLanguageContext.kt) · [`RequestLanguage.kt`](../engine-protocol/src/main/kotlin/com/langbiantianya/grpc/RequestLanguage.kt) |
| 31 | 严重级别必须结构化 | 上层不得靠文案前缀（如 `startsWith("导出失败")`）判断错误，否则翻译后错误被静默染成成功色 | [`RequestDispatcher.kt`](./src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt) |
| 32 | IPC 传输 SPI | TCP / UDS / Named Pipe 三选一，业务层不感知底层传输；UDS 不选 Linux abstract namespace（macOS / BSD 不支持） | [`IpcTransport.kt`](./src/main/kotlin/com/langbiantianya/ipc/IpcTransport.kt) |
| 33 | Windows 命名管道无服务端 API | grpc-java 未公开 server 侧实现，`serverBuilder()` 抛 `UnsupportedOperationException`；Windows 上改用 `--ipc tcp` | [`NamedPipeIpcTransport.kt`](./src/main/kotlin/com/langbiantianya/ipc/impl/NamedPipeIpcTransport.kt) |
| 34 | `--mode` 归 server 解析 | `IpcConfig.fromArgs` 显式跳过 `--mode` 及其取值；缺取值时仍显式报错 | [`IpcConfig.kt`](./src/main/kotlin/com/langbiantianya/ipc/IpcConfig.kt) |
| 35 | 优雅关闭顺序 | shutdown hook 依次 transport cleanup → 关会话与连接池 → 关驱动与方言类加载器 | [`IdbEngineServer.kt`](./src/main/kotlin/com/langbiantianya/server/IdbEngineServer.kt) |
| 36 | SQL 注入防护 | DATA 强制 `PreparedStatement`；`where` / `orderBy` 原始片段由方言 `validateSqlFragment` / `validateOrderBy` 校验 | [`DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) |
| 37 | 主键写在哪由方言自报 | 建表时列定义与表级 `PRIMARY KEY` 只能留一个；判据是「主键写在哪」而不是「有没有自增列」—— 按后者判会把 DuckDB / MySQL 的主键一并弄丢 | [`DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) · [`TableHandler.kt`](./src/main/kotlin/com/langbiantianya/handlers/TableHandler.kt) |
| 38 | 自增标记只认 JDBC 元数据 | 各方言的 `listColumns` 都不吐 `autoIncrement`，读回表结构时必须从 `getColumns` 的 `IS_AUTOINCREMENT` 补，否则用户改别的列再保存会悄悄丢掉自增 | [`JdbcColumnMetadata.kt`](./src/main/kotlin/com/langbiantianya/metadata/JdbcColumnMetadata.kt) |
| 39 | 方言的连接表单规格随 `LIST_DRIVERS` 下发 | 规格是**静态**的（方言写死的），而 `LIST_DRIVERS` 是与 `INFO` 同性质的**一次性**纯元数据查询（不需要连接、不连库）—— 所以复用它既不违背「别让静态声明走运行时通道」那条，也不必新开 action。「没声明」用 proto3 消息字段的天然存在性表达，与「声明了但字段为空」分得开 | [`DialectInfoMapper.kt`](./src/main/kotlin/com/langbiantianya/engine/DialectInfoMapper.kt) · [`DialectFormMapping.kt`](../engine-protocol/src/main/kotlin/com/langbiantianya/grpc/DialectFormMapping.kt) |
| 40 | 映射规则抽出来才可单测 | `listDrivers()` 原来把 13 个字段内联在 `dialectInfo { … }` 块里；加上表单规格会变成 20 多个字段的一大块 —— 读起来像一堵墙，而且**没法单测**（要测它得拉起一个引擎、注册方言）。抽成独立对象后，映射规则可以脱离引擎单测 | [`DialectInfoMapper.kt`](./src/main/kotlin/com/langbiantianya/engine/DialectInfoMapper.kt) |
| 41 | 「命名空间」是中性叫法 | MySQL 上它是**库**、PG 上它是 **schema** —— 两者对 SPI 是 `listTables(conn, namespace)` 的同一个参数位。判据用既有的 `supportsSchema`，**不**用方言名硬判：按名字判等于把「哪个方言有什么概念」复制一份到引擎里，那份副本迟早与方言本身对不上 | [`AnalysisHandler.kt`](./src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt) |
| 42 | 分析查询只打本地嵌入式方言 | `ANALYSIS.RUN_QUERY` 必须能写（工作区里的 `CREATE TABLE tmp AS SELECT …` 是 M4 的验收要求），所以它**不能**被 L4 只读闸门挡；但它**绝不能**打在远端上 —— 否则它就是绕过 L4 的后门。判据用既有的 `EMBEDDED_MODE`，不新造同义能力位 | [`AnalysisHandler.kt`](./src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt) |

## 4. 请求处理链路

```
调用方（desktopApp / shell / 远程 JVM）
  └─ EngineClient.handle(Request): Flow<Response>        ← :engine-protocol 接口
       ├─ IdbEngine         同进程直调                     :engine
       └─ GrpcEngineClient  gRPC over TCP/UDS/Pipe         :engine-grpc-client
              │  [服务端 IdbEngineImpl 是薄壳，桥接回同一入口]
              ▼
       IdbEngine.handle(request)                          ← gRPC 与 direct 汇合点
              ▼
       RequestDispatcher.dispatch(request)                ← 薄路由：dryRun / timeoutMs / 语言上下文 / 异常兜底
              ▼
       <Category><Action>Handler(config, typedRequest)   ← 14 个 handler
              ├─ PoolManager / TransactionManager.getConnection(config, schema, sessionId)
              ├─ DialectLoader → DatabaseDialect（SPI）
              └─ StatementRegistry.register(requestId, …)  ← 可被 SYSTEM.CANCEL 命中
              ▼
       Flow<Response>  →  单次帧 或 流式帧（以 end=true 收口）
```

跨切面语义：envelope options（`traceId` / `dryRun` / `timeoutMs` / `language`）、流式帧分装、`if_exists` 语义。
逐跳的字段含义与 handler 行为，见各自 KDoc 与 proto：[`idb_engine.proto`](../engine-protocol/src/main/proto/idb_engine.proto)。

## 5. 与其他模块的依赖关系

| 模块 | 依赖 | 说明 |
|---|---|---|
| `:engine-protocol` | proto + protobuf 插件（自带 `src/main/proto/`） | proto 定义、生成的 gRPC stub、`EngineClient` 接口；**不依赖 `:engine`** |
| `:engine` | `api(:engine-protocol)` + `implementation(:api)` + `grpc-netty-shaded` | 引擎实现；`IdbEngine` 是 `EngineClient` 的同进程实现 |
| `:engine-grpc-client` | `api(:engine-protocol)` | `GrpcEngineClient`，`EngineClient` 的跨进程实现；**不依赖 `:engine`** |
| `:api` | — | `DatabaseDialect` SPI 的唯一归属；五个方言插件（MySQL / PostgreSQL / H2 / DuckDB / SQLite）各自独立成 JAR，经 `ServiceLoader` 加载 |

调用方只面向 `EngineClient` 编程，不感知引擎在同进程还是跨进程。

依赖版本不在本文件重复，统一见 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) 与
[`engine/build.gradle.kts`](./build.gradle.kts)：`:engine` 不再自行声明 protobuf / grpc-stub / grpc-kotlin，
统一经 `api(project(":engine-protocol"))` 传递继承，只保留 IPC 传输 SPI 所需的 `grpc-netty-shaded` 与方言 SPI。

## 6. 延伸阅读

- 用户视角（怎么连、怎么用、CLI 参数）：[`README.md`](./README.md)
- wire 契约（枚举值、消息字段、流式帧）：[`idb_engine.proto`](../engine-protocol/src/main/proto/idb_engine.proto)
- 方言 SPI 全量方法与能力声明：[`DatabaseDialect.kt`](../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt)
- 引擎测试（路由、事务、取消、多语句、导入导出的行为契约）：`engine/src/test/kotlin/com/langbiantianya/`
