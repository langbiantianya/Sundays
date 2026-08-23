# idb_app — Kotlin 数据库管理端

一个使用 Kotlin 编写、面向桌面端的**数据库管理工具**。前端是 **Kotlin Multiplatform + Compose Multiplatform** 桌面应用（`desktopApp/` 模块），后端是 v2.9 起支持**双模式架构**的无头引擎（`engine/` 模块）：

| 模式 | 引擎入口 | 客户端 | 适用场景 |
|---|---|---|---|
| **Direct 直接模式（v2.9 推荐 · 默认）** | `IdbEngine().handle(request): Flow<Response>` | Compose UI（同 JVM） | KMP Desktop 应用，零序列化、零子进程 |
| **gRPC 模式（向后兼容）** | `IdbEngineServer`（gRPC server over IPC transport） | 任意 gRPC client | 跨进程、跨语言、子进程隔离、远程调试 |

引擎支持 **5 个** 可插拔方言：**MySQL** / **PostgreSQL** / **H2** / **DuckDB**（本地嵌入式 OLAP，v2.7） / **SQLite**（本地嵌入式关系型，v2.8）。

> **当前版本：v2.9** — KMP Desktop 前端 + Direct 模式 + 双模式架构
>
> 详细架构设计见 [`CLAUDE.md`](./CLAUDE.md)（V2.9），引擎 README 见 [`engine/README.md`](./engine/README.md)。

---

## 模块结构

```
idb_app/
├── api/                  公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── dialect-mysql/        MySQL 方言插件 JAR
├── dialect-postgresql/   PostgreSQL 方言插件 JAR
├── dialect-h2/           H2 方言插件 JAR（嵌入式数据库 + 测试，63 测试）
├── dialect-duckdb/       DuckDB 方言插件 JAR（v2.7 新增，81 测试）
├── dialect-sqlite/       SQLite 方言插件 JAR（v2.8 新增，62 测试）
├── engine/               主引擎模块
│   ├── src/main/proto/   idb_engine.proto（gRPC service + 强类型 per-Category message schemas）
│   ├── src/main/kotlin/com/kxxnzstdsw/
│   │   ├── engine/       IdbEngine facade（v2.9 Direct 模式入口）
│   │   ├── grpc/         gRPC protobuf 边界
│   │   ├── dispatcher/   RequestDispatcher（Category.Action → handler 路由）
│   │   ├── handlers/     13 个业务 handler（typed proto）
│   │   ├── server/       IdbEngineServer（gRPC 模式入口，v2.9 增加 --mode CLI）
│   │   ├── pool/         HikariCP 连接池（SHA-256 key 缓存）
│   │   ├── export/       数据导出（独立子进程）
│   │   ├── ipc/          IPC Transport SPI（TCP / UDS / Named Pipe）
│   │   └── loader/       ServiceLoader 动态加载 drivers/ + dialects/
│   └── src/test/kotlin/  174 个 engine 测试（含 v2.9 新增 IdbEngineDirectTest 4 项）
├── shared/               KMP 共享代码（commonMain / jvmMain）
│   ├── commonMain/       KMP 共享逻辑（与平台无关）
│   └── jvmMain/          JVM 特定逻辑（如 Okio 文件系统等）
└── desktopApp/           Compose Multiplatform Desktop 应用（v2.9 新前端）
    ├── build.gradle.kts  dependencies 含 implementation(project(":engine")) — Direct 模式依赖
    └── src/main/kotlin/com/kxxnzstdsw/idb_app/
        └── main.kt       KMP Desktop 入口：IdbEngine() 直接持有、Compose UI 渲染
```

---

## Direct 模式：KMP Desktop 与引擎的集成（v2.9 推荐）

**核心思路**：KMP Desktop 与引擎部署在**同一个 JVM 进程**，不通过 gRPC / IPC transport 通信，而是通过 `IdbEngine` facade **直接方法调用**。

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/idb_app/main.kt
import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.*
import kotlinx.coroutines.flow.first

fun main() = application {
    val engine = IdbEngine()   // 构造时自动 bootstrap drivers/dialects（幂等）
    Window(
        onCloseRequest = {
            engine.close()    // PoolManager.closeAll() + DriverLoader.closeAll() + DialectLoader.closeAll()
            exitApplication()
        }
    ) { App() }
}

// 在 ViewModel 里调用 — 与 gRPC stub `IdbEngineCoroutineStub.handle()` 同形
class SchemaViewModel(private val engine: IdbEngine) {
    suspend fun listDatabases(connection: ConnectionConfig): List<String> {
        val resp = engine.invoke(connection) {
            category = Category.SCHEMA
            action = Action.LIST
            schemaRequest = schemaRequest {
                list = schemaListRequest { level = "database" }
            }
        }
        return resp.schema.list.itemsList         // typed accessor — 无 JSON 解析
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
            pageSize = 0    // 触发流式模式
        }
    }
}).collect { resp ->
    when {
        resp.dataRowFrame != null -> renderRow(resp.dataRowFrame)
        resp.end                  -> finishLoading()
        !resp.success             -> showError(resp.error)
    }
}
```

**Direct 模式契约**：
- `IdbEngine.handle(Request): Flow<Response>` —— 与 gRPC stub 完全相同的 `Flow<Response>` 类型
- `IdbEngine.invoke(connection, configure): Response` —— 单条非流式便捷方法（内部 `Flow.first()`）
- `Request.options { traceId, dryRun, timeoutMs }` —— envelope 跨切面，direct 与 gRPC 一致
- 错误响应统一包装：`success=false, error=<msg>` —— 不抛异常、不污染 Flow

---

## gRPC 模式（向后兼容 · 跨进程场景）

```bash
# 构建引擎 fat jar + drivers + dialects
./gradlew engine:jar

# 启动 gRPC server（默认 TCP :50051；POSIX 上自动 fallback 到 unix）
cd engine/build/libs && java -jar idb-engine.jar

# 显式指定 TCP 端口 / Unix Domain Socket / Windows Named Pipe
java -jar idb-engine.jar --ipc tcp --port 60000
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock

# Direct 模式 standalone 启动（不开 gRPC server，仅 bootstrap）
java -jar idb-engine.jar --mode direct
```

Go 客户端连接示例（`engine/README.md` §通信协议 与 `CLAUDE.md` §8.3 有完整代码）：
```go
conn, _ := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
client := pb.NewIdbEngineClient(conn)
stream, _ := client.Handle(ctx, &pb.Request{
    Id:       "req-001",
    Category: pb.Category_TABLE,
    Action:   pb.Action_LIST,
    Connection: &pb.ConnectionConfig{Driver: "Mysql", Host: "127.0.0.1", Port: 3306, User: "root", Password: "secret", Database: "test"},
    Body:     &pb.Request_TableRequest{TableRequest: &pb.TableRequest{Body: &pb.TableRequest_List{List: &pb.TableListRequest{Schema: "public"}}}},
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End；typed body switch resp.GetBody().(type)
}
```

---

## 两种模式的对比

| 维度 | Direct 模式（KMP Desktop 推荐） | gRPC 模式（跨进程场景） |
|---|---|---|
| 引擎入口 | `IdbEngine().handle(request)` Kotlin facade | gRPC server over IPC transport (TCP/UDS/pipe) |
| 客户端进程 | **必须同 JVM**（Kotlin / Java） | 任意（Go / Kotlin / TypeScript / Python） |
| 通信开销 | 直接方法调用 + 内存对象引用 | HTTP/2 + protobuf + IPC transport 派发 |
| 响应类型 | `Flow<Response>` | `Flow<Response>` from `IdbEngineCoroutineStub.handle()` |
| 流式响应 | ✓ 完全一致 | ✓ 完全一致 |
| Envelope options（traceId/dryRun/timeoutMs） | ✓ | ✓ |
| 错误响应包装 | ✓ `success=false, error=...` 不抛异常 | ✓ 完全一致 |
| 子进程隔离 | ✗（同进程） | ✓（Wails v3 / 独立 daemon） |
| 远程调试 | ✗ | ✓（任意 gRPC client） |
| 跨语言互操作 | ✗ | ✓（任何支持 gRPC 的语言） |

**结论**：KMP Desktop 应用**统一走 Direct 模式**（同 JVM）；需要跨进程、跨语言、子进程隔离时才切到 gRPC 模式。

---

## 运行 KMP Desktop 应用

```bash
# Hot reload 开发模式
./gradlew :desktopApp:hotRun --auto

# 普通运行
./gradlew :desktopApp:run
```

引擎和方言插件都通过 `:engine` 模块依赖直接共享，无需部署子进程。

---

## 运行引擎 standalone

```bash
# 1. 构建
./gradlew engine:jar

# 2. 启动引擎 gRPC server（默认 :50051）
cd engine/build/libs && java -jar idb-engine.jar

# 或 Direct 模式（不开 server，仅供本地脚本调用）
java -jar idb-engine.jar --mode direct

# 或 UDS 模式（POSIX）
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock
```

---

## 运行测试

```bash
# 全部 380 测试
./gradlew test

# 单个方言模块
./gradlew :dialect-h2:test          # 63 测试
./gradlew :dialect-duckdb:test      # 81 测试（v2.7）
./gradlew :dialect-sqlite:test      # 62 测试（v2.8）

# 引擎模块
./gradlew :engine:test              # 174 测试（含 v2.9 新增 4 项 IdbEngineDirectTest）

# Desktop App 共享代码测试
./gradlew :shared:jvmTest
```

测试覆盖率：
- **engine:test**（174 项）：IPC config + transport round-trip + HikariCP pool + DialectLoader + 11 个 handler 集成（typed proto builders）+ envelope options + DuckDB / SQLite 端到端 + LIST_DRIVERS + **Direct 模式契约（v2.9 新增）**
- **dialect-h2 / -duckdb / -sqlite:test**：方言 SPI 方法全量覆盖（206 项）
- **总计：380 测试，0 失败 / 0 错误（1 个 Windows-only IpcConfigTest 用例 skip）**

---

## 文档

| 文档 | 内容 |
|---|---|
| [`CLAUDE.md`](./CLAUDE.md) | **架构设计文档（V2.9）** —— gRPC 协议、handler 矩阵、方言特性、envelope options、双模式架构、迁移历史 |
| [`engine/README.md`](./engine/README.md) | 引擎模块详细 README —— CLI、构建运行、handler 路由矩阵、API 参考、Direct 模式示例 |
| [`shared/src/`](./shared/src) | KMP 共享代码 —— `commonMain/`（平台无关）/ `jvmMain/`（JVM 特定） |
| [`engine/src/main/proto/idb_engine.proto`](./engine/src/main/proto/idb_engine.proto) | gRPC service 定义 + 全部 typed message schemas |

---

## 技术栈

- **Kotlin 2.4.0 / JDK 25**
- **Compose Multiplatform Desktop**（KMP Desktop 前端）
- **gRPC 1.83.1** + grpc-kotlin 1.5.0（Kotlin 协程服务端 + Kotlin DSL 生成）
- **protobuf-kotlin-lite 4.35.1**（DSL builder：`xxxRequest { ... }` / `request { ... }`）
- **kotlinx-coroutines 1.11.0**
- **HikariCP 7.0.2**（SHA-256 缓存连接池）
- **MySQL Connector/J 9.7.0** / **PostgreSQL JDBC 42.7.11** / **H2 2.3.232** / **DuckDB JDBC 1.5.5.1**（v2.7） / **SQLite JDBC 3.46.1.3**（v2.8）
- **SLF4J 2.0.18 + Logback 1.5.13**
- **LuaJIT 4.1.0 + Lua 5.1~5.5**（造数引擎）
- **Apache POI 5.5.1**（Excel 流式导出 + DuckDB Excel 预转换）
- **Apache Parquet 1.17.1 + Hadoop 3.5.0**（Parquet 导出）

---

## 架构升级历史

| 版本 | 主要变化 |
|---|---|
| v1.0 | stdin/stdout + 4-byte BE uint32 长度前缀 + 自定义 protobuf（旧管道协议） |
| v2.0 | gRPC over HTTP/2 + 标准 google.protobuf.Value |
| v2.1 | gRPC + IPC Transport SPI（TCP / UDS / Named Pipe） |
| v2.2 | gRPC + 强类型 per-Category Request/Response + CLI Args |
| v2.3 | gRPC + 强类型 Handlers end-to-end（删除 `TypedRequestMapper`） |
| v2.4 | gRPC + 强类型 per-list-item 消息（typed `Row` wrapper） |
| v2.5 | gRPC 1.76 + grpc-kotlin 协程服务端 + Kotlin DSL end-to-end |
| v2.6 | 表驱动 Dispatcher + 跨切面 Envelope Options（traceId / dryRun / timeoutMs）+ `SQL.EXPLAIN` 路由 |
| v2.7 | DuckDB 方言插件（本地嵌入式 OLAP） |
| v2.8 | SQLite 方言插件 + SPI 连接元数据扩展 + `SYSTEM.LIST_DRIVERS` |
| **v2.9 (当前)** | **KMP Desktop Direct 模式 + 双模式架构** — 前端从 Wails v3 gRPC 子进程迁移到 KMP Compose Desktop（`desktopApp/`），引擎与 UI 同 JVM；新增 `IdbEngine` facade（`handle()` / `invoke()`），`IdbEngineImpl` 薄壳化；CLI `--mode <grpc\|direct>` 切换；`RequestDispatcher.dispatch` catch 外置到 `.catch{}` operator 修复 *Flow exception transparency violated* |

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/).