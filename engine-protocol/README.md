# engine-protocol — 引擎调用层契约

**v2.15 新增** —— 把「调用层」从 `engine` 里抽出来，做成独立模块：proto 协议 + 生成的强类型消息 + `EngineClient` 调用接口。

本模块**不含任何业务逻辑**（无 handler、无连接池、无方言、无导出），因此服务端与客户端可以同时依赖它而不产生循环依赖，也不会把 Hadoop / POI / LuaJIT 等重依赖带进纯客户端进程。

**v2.16 新增** —— 在上面的连接 / 查询 / 导出能力之外，协议补齐四类功能：**查询取消**（`SYSTEM.CANCEL`）、**数据导入**（`IMPORT.RUN_IMPORT`）、**事务会话**（`SYSTEM.BEGIN / COMMIT / ROLLBACK / SESSION_INFO`）、**多语句脚本**（`SQL.EXECUTE` 的 `multi_statement`）。均为新增枚举值与 message 字段，不改动既有路由。

> **当前版本**：v2.16

---

## 模块结构

```
engine-protocol/
├── build.gradle.kts                  # protobuf 插件 + api 依赖导出
└── src/main/
    ├── proto/
    │   ├── idb_engine.proto          # gRPC service + 全部 message schemas（Category/Action + 13 类请求响应，含 v2.16 的 IMPORT）
    │   └── idb_export.proto          # 导出子进程 RPC（ExportHub）
    └── kotlin/com/kxxnzstdsw/client/
        └── EngineClient.kt           # 调用层契约接口
```

---

## 为什么要有这个模块

v2.14 之前，proto 定义在 `engine/src/main/proto/`，调用方想用 gRPC 客户端就必须依赖整个 `engine`
（连带 Hadoop、POI、LuaJIT、5 个方言插件）。前端想「换个进程跑引擎」时没有干净的接缝。

v2.15 起：

```
              ┌──────────────────────────────┐
              │      engine-protocol         │   proto 契约 + EngineClient 接口
              │  Request/Response/IdbEngine  │   （无业务逻辑、无重依赖）
              └───────────┬──────────────────┘
                          │ api(...)
              ┌───────────┴───────────┐
              ▼                       ▼
      ┌───────────────┐       ┌────────────────────┐
      │    engine     │       │ engine-grpc-client │
      │  IdbEngine    │       │ GrpcEngineClient   │
      │  (同进程实现)  │       │ (跨进程 gRPC 实现)  │
      │  + gRPC 服务端 │       │                    │
      └───────────────┘       └────────────────────┘
```

调用方只面向 `EngineClient` 编程，**换实现不改调用代码**。

---

## `EngineClient` 接口

```kotlin
package com.kxxnzstdsw.client

interface EngineClient : AutoCloseable {
    /** 主入口 —— 与 gRPC stub `IdbEngineCoroutineStub.handle(req)` 语义完全一致 */
    fun handle(request: Request): Flow<Response>

    /** 单次非流式便捷方法（默认实现：构造 Request 并 collect 终止帧） */
    suspend fun invoke(connection: ConnectionConfig, configure: RequestKt.Dsl.() -> Unit): Response

    /** 建立（或复用）连接池 + `isValid(5)` 校验 —— 即「初始化连接」 */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse

    /** 仅凭 JDBC URL + 凭据（默认实现，driver 由 URL scheme 反查） */
    suspend fun testConnection(jdbcUrl: String, user: String = "", password: String = ""): SystemTestConnectionResponse

    /** 释放该配置的全部连接池；@return 是否真的关闭了池 */
    suspend fun disconnect(config: ConnectionConfig): Boolean

    /** 释放实现自身持有的资源（幂等） */
    override fun close()
}
```

| 方法 | 抽象原因 |
|---|---|
| `handle` | 流式分帧（`stream` / `end`）、envelope options（`traceId` / `dryRun` / `timeoutMs`）、错误帧（`success=false, error=…`）在两条路径上必须一致 |
| `invoke` | 默认实现放在接口里 —— 两个实现共用同一套「构造请求 + 收集终止帧」逻辑，不会漂移 |
| `testConnection` | **必须可跨进程** —— 远程连接初始化是 gRPC 客户端的基本能力 |
| `disconnect` | **必须可跨进程** —— 连接池活在引擎进程里，客户端本地无池可关（见下文 `SYSTEM.DISCONNECT`） |
| `close` | 语义随实现而变（本地=池/驱动/方言，gRPC=channel），故收敛到接口而不是暴露具体类型 |

---

## v2.15 协议新增：`SYSTEM.DISCONNECT`

`Action.DISCONNECT = 19` + `SystemDisconnectResponse { bool closed = 1; }`（`SystemResponse` oneof 新增 `disconnect = 5`）。

- **为什么加**：连接池由引擎进程持有。远程调用方要「断开连接」就必须有一条线上路由，否则 `EngineClient.disconnect` 对 gRPC 实现只能是空实现 —— 接口就成了谎言。
- **路由**：`RequestDispatcher.routes[SYSTEM to DISCONNECT]` → `SystemHandler.disconnect(config)` → `PoolManager.close(config)`（按两段式 key 一次关掉该配置下所有 schema 维度的池）
- **dryRun**：`DISCONNECT` 已加入 `writeActions` —— 「试运行」不得真的掐断用户的连接
- **幂等**：第二次调用返回 `closed=false`（当前没有活跃池），不是错误

---

## v2.16 协议新增：取消 / 导入 / 事务 / 多语句

### 枚举新增

| 枚举成员 | 值 | 含义 |
|---|---|---|
| `Category.IMPORT` | `14` | 数据导入（与 `EXPORT` 对称的读方向） |
| `Action.CANCEL` | `20` | 取消运行中的请求（`Statement.cancel`） |
| `Action.RUN_IMPORT` | `21` | 启动一次数据导入 |
| `Action.BEGIN` | `22` | 开启事务会话 |
| `Action.COMMIT` | `23` | 提交事务会话 |
| `Action.ROLLBACK` | `24` | 回滚事务会话 |
| `Action.SESSION_INFO` | `25` | 查询活跃事务会话 |

`Request.body` 相应新增 `import_request = 22`（`ImportRequest`）。

### `Request.session_id = 6`

顶层 `Request` 新增 `string session_id = 6`，由 `SYSTEM.BEGIN` 分配。

- **留空 = 无事务**：每条语句各自独立提交 —— 与 v2.15 及之前完全一致，这是本次的**向后兼容保证**，旧调用方不传该字段即可。
- **非空时**：该会话下的所有 DATA 写操作与 `SQL.EXECUTE` 都落在 `BEGIN` 时**固定（pin）的那条连接**上，直到 `COMMIT` / `ROLLBACK` 结束会话。
- **连接所有权**：无会话时 handler 借出连接后自行归还；有会话时连接归 `TransactionManager` 持有，handler 不得提前归还 —— 否则下一条语句会落到池里另一条物理连接上，事务会被**静默降级**成「只有最后一条语句在事务内」。

### `SYSTEM.CANCEL`（`Action.CANCEL = 20`）

**为什么需要它**：协程取消（coroutine cancellation）**无法**中断一个阻塞中的 JDBC 调用 —— 跑在 `Dispatchers.IO` 上的 `rs.next()` 不会因为协程被 cancel 就停下来，数据库侧的查询仍在继续。只有 `Statement.cancel()` 才能真正叫停数据库工作，因此协议必须提供一条「请求引擎对正在运行的 statement 调用 `Statement.cancel()`」的路由。

`SystemRequest` 由**空 message** 扩展为两个字段：

| 字段 | 号 | 说明 |
|---|---|---|
| `target_request_id` | `1` | `CANCEL` 指定要取消的请求 id |
| `session_id` | `2` | `SESSION_INFO` 指定会话（留空 = 列出全部活跃会话） |

`SystemResponse.cancel = 6`，携带 `SystemCancelResponse { bool cancelled = 1; string request_id = 2; string error = 3; }`：

- `cancelled=true` 表示找到目标并已发出取消。
- 未知 id 返回 `cancelled=false` + 说明性 `error`，**不抛异常**；`target_request_id` 留空同样返回 `cancelled=false` 并指出缺失字段。
- 被取消的请求走它**自己的错误路径**，以原请求 id 返回一条 `success=false, error="cancelled"` 的终止帧。

### 事务会话（`BEGIN / COMMIT / ROLLBACK / SESSION_INFO`）

`SystemResponse` oneof 新增四个分支及对应 message：

| oneof 分支 | 号 | message |
|---|---|---|
| `begin` | `7` | `SystemBeginResponse { session_id=1; started_at=2; driver=3; database=4; }` |
| `commit` | `8` | `SystemCommitResponse { session_id=1; committed=2; error=3; }` |
| `rollback` | `9` | `SystemRollbackResponse { session_id=1; rolled_back=2; error=3; }` |
| `session_info` | `10` | `SystemSessionInfoResponse { active_sessions=1; sessions=2; }` |

每个活跃会话由 `TransactionSession { session_id=1; started_at=2; driver=3; database=4; schema=5; auto_commit=6; }` 描述（`auto_commit` 在会话内恒为 `false`）。

- `BEGIN` 之后引擎为该会话固定一条池中连接并置 `autoCommit=false`；同一 `session_id` 的后续请求全部落在这条连接上，直到 `COMMIT` / `ROLLBACK`。
- 写操作若带上**未知** `session_id`，会被**拒绝**（`success=false`），绝不静默自动提交。
- `COMMIT` / `ROLLBACK` 对未知会话返回 `committed=false` / `rolled_back=false` + `error`，不抛异常（客户端断线后可能重试）。
- `SYSTEM.SESSION_INFO` 无参时列出全部活跃会话，有参时按 `session_id` 查询。

### 多语句脚本（`SqlExecuteRequest.multi_statement = 3`）

`SqlExecuteRequest` 新增 `bool multi_statement = 3`：

- `false`（默认）= 整段当一个语句执行，即 v2.15 及之前的行为。
- `true` = 按语句边界**顺序拆分逐条执行**，结果逐条回填到 `SqlExecuteResponse.statements`。

`SqlExecuteResponse.affected_rows = 1` 为**全部成功语句的行数合计**；新增 `repeated SqlStatementResult statements = 2`（仅 `multi_statement=true` 时填充，顺序即执行顺序）：

```
SqlStatementResult { int32 index = 1; string sql = 2; int32 affected_rows = 3; bool success = 4; string error = 5; }
```

**遇错即停（stop-on-error）**：语句按顺序执行，**第一条失败后不再执行后续语句**（与 psql / DBeaver 默认行为一致）。失败语句在对应 `index` 上记录 `success=false` + `error`，`affected_rows` 只累计成功语句；末尾不带 `;` 的语句仍会执行；整段没有任何可执行语句时返回 `success=false` 且 `error` 非空。取消时以 `"$requestId#$index"` 为 key 精确指向当前运行的那条语句。

### 数据导入（`IMPORT.RUN_IMPORT`，`Category.IMPORT = 14 / Action.RUN_IMPORT = 21`）

`ImportRequest { ImportRunRequest run_import = 1; }`：

| `ImportRunRequest` 字段 | 号 | 说明 |
|---|---|---|
| `file_path` | `1` | 待导入的本地文件绝对路径 |
| `format` | `2` | `CSV` / `JSON_LINES` |
| `table_name` | `3` | 目标表（必填） |
| `schema` | `4` | schema |
| `batch_size` | `5` | 每批 insert 行数，default `500` |
| `delimiter` | `6` | CSV 专用，default `","`（取首字符） |
| `has_header` | `7` | `optional bool`，default `true` |
| `encoding` | `8` | default `UTF-8` |
| `truncate_first` | `9` | 导入前 `TRUNCATE` 目标表 |
| `ignore_errors` | `10` | `true` = 跳过坏行继续并计入 `rows_failed`；`false` = 遇错即停 |
| `stop_import_id` | `11` | 设为运行中的导入 id 即取消该任务 |

响应：

| message | 字段 | 说明 |
|---|---|---|
| `ImportProgressFrame` | `rows_read=1; rows_inserted=2; rows_failed=3; message=4;` | 进度帧（`stream=true, end=false`） |
| `ImportResultResponse` | `rows_read=1; rows_inserted=2; rows_failed=3; success=4; error=5;` | 终止帧（`end=true`）统计 |
| `ImportResponse` | `oneof { progress=1; result=2; }` | 上面两者之一 |

`Response.import = 23` 承载终止帧，流式进度帧走 `Response.import_progress = 33`（与 `DATA.LIST` 行帧、`DATA.GENERATE` 进度帧同一分帧约定）。

**为什么 `has_header` 是 `optional bool`**：proto3 的裸 `bool` 无法区分「调用方没传」与「显式传 `false`」，而这两者意图**完全相反** —— 有表头 vs 无表头。若 `has_header=false` 被当成「未传」而套用默认值 `true`，首行数据就会被误当作列名吃掉。因此用 `optional` 保留 presence 语义，`unset` 才回退到默认 `true`；这与 `DataListRequest.page_size` 是同一套处理手法。

---

## 依赖

```kotlin
dependencies {
    api(project(":engine-protocol"))   // engine / engine-grpc-client / desktopApp 均经此拿到契约
}
```

本模块自身只导出 `protobuf-java` / `protobuf-kotlin-lite` / `grpc-stub` / `grpc-protobuf` / `grpc-kotlin-stub` / `kotlinx-coroutines-core` / `slf4j-api` —— 全部以 `api` 声明，因为它们出现在 `EngineClient` 的公开签名里。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`../engine/README.md`](../engine/README.md) | 引擎实现（`IdbEngine` 本地实现 + gRPC 服务端） |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | gRPC 客户端实现（`GrpcEngineClient`） |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | Dispatcher 路由表 / envelope / 连接池 |
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | 整体架构与模块导航 |
