# engine-protocol — 引擎调用层契约

把「调用层」从 `engine` 里抽出来做成独立模块：**proto 协议 + 生成的强类型消息 + `EngineClient` 调用接口**。

本模块**不含任何业务逻辑**（无 handler、无连接池、无方言、无导出），因此服务端与客户端可以同时依赖它而不产生循环依赖，也不会把 Hadoop / POI / LuaJIT 等重依赖带进纯客户端进程。调用方只面向 `EngineClient` 编程，**换实现不改调用代码**：`:engine` 的 `IdbEngine` 是同进程实现，`:engine-grpc-client` 的 `GrpcEngineClient` 是跨进程实现。

> **当前版本**：v2.16。v2.15 抽出本模块；v2.16 在连接 / 查询 / 导出之外补齐四类功能：查询取消、数据导入、事务会话、多语句脚本。

## 模块结构

| 文件 | 内容 |
|---|---|
| [`EngineClient.kt`](./src/main/kotlin/com/langbiantianya/client/EngineClient.kt) | 调用层契约接口 —— **逐方法语义、生命周期、线程安全约定以该文件 KDoc 为权威** |
| [`RequestLanguage.kt`](./src/main/kotlin/com/langbiantianya/grpc/RequestLanguage.kt) | `Request.withLanguageTag(tag)` —— 出站请求的界面语言标记 |
| [`idb_engine.proto`](./src/main/proto/idb_engine.proto) | gRPC service + 全部 message schema（Category / Action + 各请求响应） |
| [`idb_export.proto`](./src/main/proto/idb_export.proto) | 导出子进程 RPC（ExportHub） |

**消息与字段的定义、默认值、逐字段注释都在 proto 文件里**，本 README 不重复罗列。

## `EngineClient` 接口

| 方法 | 语义与契约 |
|---|---|
| `handle(request): Flow<Response>` | **唯一主入口**，与 gRPC stub `IdbEngineCoroutineStub.handle(req)` 语义完全一致。非流式返回 1 条（`stream=false`）；流式返回 N 条，最后一条 `end=true`。流式分帧、envelope options（`traceId` / `dryRun` / `timeoutMs`）、错误帧三条在同进程与跨进程两条路径上必须一致 —— 每次 `handle` 是一次独立调用。 |
| `invoke(connection) { … }` | 单次**非流式**便捷方法：构造 Request 并 collect 终止帧。默认实现放在接口里，两个实现共用同一套逻辑因而不会漂移。流式调用请用 `handle` + `collect`。 |
| `testConnection(config)` | 建立（或复用）该配置的 HikariCP 连接池并做 JDBC `isValid(5)` 校验，即「初始化连接」。池按配置 hash key 缓存，重复调用不会重复建池。另有仅凭 `jdbcUrl` + 凭据的重载（driver 由 URL scheme 反查）。**必须可跨进程** —— 远程连接初始化是 gRPC 客户端的基本能力。 |
| `disconnect(config): Boolean` | 释放该配置下的**全部**连接池（含各 schema 维度），与 `testConnection` 对称。**必须可跨进程** —— 池活在引擎进程里，客户端本地无池可关。返回 `false` 表示该配置当前没有活跃池，是幂等结果而非错误。 |
| `close()` | 释放实现自身持有的资源，**幂等**：本地实现 = 池 / 驱动 / 方言，gRPC 实现 = channel。语义随实现而变，故收敛到接口而不是暴露具体类型。窗口关闭 / 进程退出时调用。 |

实现须**允许多个协程并发调用**。`close()` 之后的行为由各实现定义。

## 错误包装约定

**引擎侧**：业务异常一律由 `RequestDispatcher` 包成 `success=false, error=…` 的 Response，**不向调用方抛异常**。未知 session、未知 cancel id、缺字段等都是「`false` + 说明性 `error`」而非异常 —— 调用方只需检查 `success` / `ok`。

**传输侧**：gRPC 的 `StatusException`（引擎未启动、channel 已关、UDS 不存在）会以异常形式抛进 Flow，**实现必须收口成同样的 `success=false, error=<message>` 终止帧**，否则「换个传输就炸」，跨进程与同进程的契约就不再等价。

`testConnection` 拿不到 `system` 负载时（传输失败）返回 `ok=false` + `error`，与 `SystemHandler.testConnection` 自身的 catch 分支同形。

## 协议能力

| 版本 | 能力 | 关键结论 |
|---|---|---|
| v2.15 | `SYSTEM.DISCONNECT`（`Action.DISCONNECT = 19`） | 连接池由引擎进程持有，远程调用方必须有线上路由才能释放，否则 `EngineClient.disconnect` 对 gRPC 实现只能是空实现、接口就成了谎言。已加入 `writeActions`（`dryRun` 不得真的掐断用户连接），幂等。 |
| v2.16 | `SYSTEM.CANCEL`（`Action.CANCEL = 20`） | 协程取消**无法**中断阻塞中的 JDBC 调用（`Dispatchers.IO` 上的 `rs.next()` 不理会协程取消），只有 `Statement.cancel()` 能真正叫停数据库。未知 id 或缺 `target_request_id` 返回 `cancelled=false` + 说明性 `error`，**不抛异常**；被取消的请求走它自己的错误路径，以原请求 id 返回 `success=false, error="cancelled"` 的终止帧。 |
| v2.16 | 事务会话 `BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`（`Action` 22–25） | `Request.session_id` **留空 = 无事务**，每条语句各自独立提交 —— 旧调用方不传该字段即完全向后兼容。非空时所有 DATA 写操作与 `SQL.EXECUTE` 都落在 `BEGIN` 固定的那条连接上，直到 `COMMIT` / `ROLLBACK`；未知 `session_id` 的写操作**被拒绝**（`success=false`），绝不静默自动提交。未知会话的 `COMMIT` / `ROLLBACK` 返回 `false` + `error`（客户端断线后可能重试）。 |
| v2.16 | 多语句脚本 `SqlExecuteRequest.multi_statement` | `false`（默认）= 整段当一个语句执行，即 v2.15 及之前的行为；`true` = 按语句边界顺序拆分逐条执行。**遇错即停**（第一条失败后不再执行后续，与 psql / DBeaver 默认一致），失败语句在对应 `index` 上记 `success=false` + `error`，`affected_rows` 只累计成功语句。末尾不带 `;` 的语句仍会执行；整段无可执行语句时返回 `success=false` 且 `error` 非空。取消时以 `"$requestId#$index"` 为 key 精确指向当前运行的那条语句。 |
| v2.16 | 数据导入 `IMPORT.RUN_IMPORT`（`Category.IMPORT = 14` / `Action.RUN_IMPORT = 21`） | 进度帧走 `Response.import_progress`、终止帧走 `Response.import`，与 `DATA.LIST` 行帧 / `DATA.GENERATE` 进度帧同一分帧约定。`has_header` 是 `optional bool` 而非裸 `bool`：裸 `bool` 分不清「没传」与「显式传 false」，而这两者意图相反（若把 `false` 当成未传而套用默认 `true`，首行数据会被误当列名吃掉），故用 presence 语义、`unset` 才回退默认 —— 与 `DataListRequest.page_size` 同一手法。`stop_import_id` 设为运行中的导入 id 即取消该任务。 |

## 界面语言

`RequestLanguage.kt` 的 `Request.withLanguageTag(tag)` 给请求盖上 BCP-47 标签（空串 = 不标记，引擎按自身 locale 探测）。**两条实现路径都要用它**：gRPC 模式下引擎是独立进程，`ThreadLocal` 跨不过进程边界；同进程实现上 UI 线程的 `ThreadLocal` 也到不了 `Dispatchers.IO` 上的 dispatcher。落点统一是 `Request.options.language`。为何签名只收 `String` 而非 `AppLanguage`（避免 `:i18n` 反向依赖协议模块）见该文件 KDoc。

## 接入自己的实现

1. **依赖**：`api(project(":engine-protocol"))`。protobuf / grpc / coroutines / slf4j 全部以 `api` 声明 —— 它们出现在 `EngineClient` 的公开签名里，必须导出。
2. **实现 `EngineClient`**：`handle` 是唯一必须自己写的方法；`invoke` 与 `testConnection(jdbcUrl…)` 复用接口默认实现。`testConnection` / `disconnect` 想真正可用，就得有自己的连接管理。
3. **守住三条契约**：流式分帧一致、envelope options 一致、错误统一 `success=false, error=…`。这三条正是本模块存在的意义。
4. **挂到运行时**：同进程场景（desktopApp / shell）直接构造你的实现；跨进程场景不需要自己写，用 [`engine-grpc-client`](../engine-grpc-client/README.md) 的 `GrpcEngineClient` 即可。
5. 若目标是新增**数据库方言**而不是新引擎实现，步骤见 [`api/README.md`](../api/README.md)「扩展自定义方言」。

## 跨链接

| 文档 | 内容 |
|---|---|
| [`../engine/README.md`](../engine/README.md) | 引擎实现（`IdbEngine` 本地实现 + gRPC 服务端） |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | gRPC 客户端实现（`GrpcEngineClient`） |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | Dispatcher 路由表 / envelope / 连接池 |
| [`../api/README.md`](../api/README.md) | 方言 SPI 与自定义方言扩展 |
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | 整体架构与模块导航 |
