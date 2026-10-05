# sundays — Kotlin 数据库管理端

一个使用 Kotlin 编写、面向桌面端的**数据库管理工具**。前端是 **Kotlin Multiplatform + Compose Multiplatform** 桌面应用（`desktopApp/` 模块），后端是**调用层抽象**的无头引擎：

| 模式 | 调用层实现 | 通道 | 适用场景 |
|---|---|---|---|
| **Direct 直接模式（默认）** | `IdbEngine`（`engine/`） | 同 JVM 直接方法调用 | KMP Desktop 应用，零序列化、零子进程 |
| **gRPC 模式** | `GrpcEngineClient`（`engine-grpc-client/`） | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、子进程隔离、远程调试 |

两者实现同一个接口 **`EngineClient`**（`com.kxxnzstdsw.client.EngineClient`，定义在 `engine-protocol/`）—— 调用方只面向接口编程，**换实现不改调用代码**。引擎侧另有 `IdbEngineServer` 提供 gRPC 服务端（`java -jar idb-engine.jar`）。

引擎支持 **5 个** 可插拔方言：**MySQL** / **PostgreSQL** / **H2** / **DuckDB**（本地嵌入式 OLAP，v2.7） / **SQLite**（本地嵌入式关系型，v2.8）。

> **当前版本：v2.16** — 引擎能力补齐（查询取消 / 数据导入 / 事务会话 / 多语句脚本）
>
> 详细架构设计见 [`ARCHITECTURE.md`](ARCHITECTURE.md)（V2.16），调用层契约见 [`engine-protocol/README.md`](./engine-protocol/README.md)，引擎 README 见 [`engine/README.md`](./engine/README.md)。

---

## 模块结构

```
sundays/
├── api/                  公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── dialect-mysql/        MySQL 方言插件 JAR
├── dialect-postgresql/   PostgreSQL 方言插件 JAR
├── dialect-h2/           H2 方言插件 JAR（嵌入式数据库 + 测试，63 测试）
├── dialect-duckdb/       DuckDB 方言插件 JAR（v2.7 新增，81 测试）
├── dialect-sqlite/       SQLite 方言插件 JAR（v2.8 新增，62 测试）
├── engine-protocol/      调用层契约（v2.15）：proto 协议 + 强类型消息 + EngineClient 接口
│   ├── src/main/proto/   idb_engine.proto + idb_export.proto（gRPC service + message schemas）
│   └── src/main/kotlin/com/kxxnzstdsw/client/
│       └── EngineClient.kt  调用层契约接口（handle / invoke / testConnection / disconnect / close）
├── engine/               引擎实现（主引擎模块）
│   ├── src/main/kotlin/com/kxxnzstdsw/
│   │   ├── engine/       IdbEngine（EngineClient 的同进程实现）+ StatementRegistry（v2.16 查询取消）+ SqlScriptSplitter（v2.16 多语句切分）
│   │   ├── grpc/         gRPC protobuf 边界
│   │   ├── dispatcher/   RequestDispatcher（Category.Action → handler 路由）
│   │   ├── handlers/     14 个业务 handler（typed proto，v2.16 新增 ImportHandler）
│   │   ├── server/       IdbEngineServer（gRPC 服务端入口，v2.9 增加 --mode CLI）
│   │   ├── pool/         HikariCP 连接池（SHA-256 key 缓存）+ TransactionManager（v2.16 事务会话）
│   │   ├── export/       数据导出（独立子进程）
│   │   ├── importer/     数据导入（v2.16 主进程内运行：CsvReader / JsonLinesReader / ImportSourceFactory）
│   │   ├── ipc/          IPC Transport SPI（TCP / UDS / Named Pipe）
│   │   └── loader/       ServiceLoader 动态加载 drivers/ + dialects/
│   └── src/test/kotlin/  277 个 engine 测试（v2.16：+89，含 splitter / importer / cancel / multi-statement / transaction / import 集成测试）
├── engine-grpc-client/   gRPC 调用模块（v2.15）：EngineClient 的跨进程 gRPC 实现，21 项测试
│   └── src/main/kotlin/com/kxxnzstdsw/client/grpc/
│       ├── GrpcEngineClient.kt  经 gRPC stub 实现 EngineClient
│       └── GrpcClientConfig.kt  端点配置（TCP / UDS / 命名管道）+ ChannelBuilder
├── shared/               KMP 共享代码（commonMain / jvmMain）
│   ├── commonMain/       KMP 共享逻辑（与平台无关）—— 含 UI 组件（编辑器 / 表格 / 右键菜单）
│   │   ├── editor/       CodeEditor：可扩展代码编辑器（语法高亮 + 行号 + 工具栏 + 右键菜单 + 格式化 + 补全提示)
│   │   ├── table/        DataTable：虚拟滚动数据表格（分页 + 详情面板 + 右键菜单）
│   │   ├── connection/   ConnectionManagerScreen：连接管理（左侧连接列表 + 右侧 4 步向导，JSON 持久化到 ~/.config/sundays/connection.json）
│   │   ├── navigation/   AppDestination：顶层导航目标枚举（平台无关，v2.14 自 desktopApp 上移）
│   │   └── ui/           通用 UI 工具（ContextMenuState、onRightClick modifier、SundaysTheme 应用主题 + SundaysPalette 蓝灰 IDE 配色）
│   └── jvmMain/          JVM 特定逻辑（如 Okio 文件系统等）
└── desktopApp/           Compose Multiplatform Desktop 应用（v2.9 新前端）
    ├── build.gradle.kts  依赖 :engine（本地实现）与 :engine-grpc-client（gRPC 实现）
    └── src/main/kotlin/com/kxxnzstdsw/sundays/
        ├── main.kt                  KMP Desktop 入口：Window + SundaysTheme、createEngineClient() 选实现、顶层导航分派
        │                            （AppDestination / SundaysTheme / SundaysPalette 已在 :shared/commonMain）
        ├── ConnectionSession.kt     连接列表 / 向导 / 引擎会话状态机（持有 EngineClient 接口）
        └── DatabaseBrowserScreen.kt 数据库浏览第二屏（库表树 + 表数据预览标签页）
```

---

## 调用层：KMP Desktop 与引擎的集成（v2.15）

**核心思路**：UI 只面向 **`EngineClient`** 接口编程，由 `main.kt` 这个装配点决定用哪个实现。
默认是同进程 `IdbEngine`（零序列化、零子进程）；设置 `-Dsundays.engine.endpoint` 则换成跨进程 `GrpcEngineClient`。

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt —— 唯一的装配点
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.client.grpc.GrpcClientConfig
import com.kxxnzstdsw.client.grpc.GrpcEngineClient
import com.kxxnzstdsw.engine.IdbEngine

const val ENDPOINT_PROPERTY = "sundays.engine.endpoint"

fun main() = application {
    val engine: EngineClient = createEngineClient()
    Window(onCloseRequest = { engine.close(); exitApplication() }) {
        SundaysTheme { MainScreen(engine) }        // 主题 + 顶层导航来自 :shared
    }
}

internal fun createEngineClient(): EngineClient {
    val endpoint = System.getProperty(ENDPOINT_PROPERTY).orEmpty().trim()
    if (endpoint.isEmpty()) return IdbEngine()    // 同进程直调（默认）
    return GrpcEngineClient.connect(GrpcClientConfig.fromTarget(endpoint))
}

// 调用方 —— 两种实现下代码完全相同
class SchemaViewModel(private val engine: EngineClient) {
    suspend fun listDatabases(connection: ConnectionConfig): List<String> {
        val resp = engine.invoke(connection) {
            category = Category.SCHEMA
            action = Action.LIST
            schemaRequest = SchemaRequest.newBuilder()
                .setList(schemaListRequest { level = "database" })
                .build()
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
        list = dataListRequest { tableName = "users"; pageSize = 0 }
    }
}).collect { resp ->
    when {
        resp.dataRowFrame != null -> renderRow(resp.dataRowFrame)
        resp.end                  -> finishLoading()
        !resp.success             -> showError(resp.error)
    }
}
```

**启动 gRPC 模式**（引擎跑独立进程）：

```bash
java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051     # 引擎进程
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051
```

**`EngineClient` 契约**（两条实现逐帧一致）：
- `handle(Request): Flow<Response>` —— 与 gRPC stub 相同的 `Flow<Response>` 类型
- `invoke(connection, configure): Response` —— 单条非流式便捷方法（接口默认实现）
- `testConnection(config)` / `disconnect(config)` —— 连接生命周期（远程调用方经 `SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT` 路由）
- `Request.options { traceId, dryRun, timeoutMs }` —— envelope 跨切面，两条路径一致
- 错误统一包装：`success=false, error=<msg>` —— 不抛异常、不污染 Flow（含 gRPC 传输层故障）
- `close()` —— 幂等释放（本地=池/驱动/方言，gRPC=channel）

---

## 引擎新能力（v2.16）

v2.16 补齐了与 DBeaver / Navicat / DataGrip 等同类工具相比缺失的四项引擎能力。以下从**使用者视角**说明各自解锁了什么。

### 查询取消（`SYSTEM.CANCEL`）

- **为什么需要**：协程取消无法打断阻塞中的 JDBC 调用 —— 跑在 `Dispatchers.IO` 上的 `rs.next()` 即使协程被取消仍会继续；真正能停掉数据库侧工作的只有 `Statement.cancel()`。
- 引擎通过 `StatementRegistry` 按 request id 登记正在执行的 `Statement`（或自定义 canceler），`SYSTEM.CANCEL` 携带 `target_request_id` 即可中断它。
- 覆盖 `SQL.EXECUTE`、流式 `DATA.LIST`（`pageSize=0`）、`DATA.GENERATE`。`DATA.GENERATE` 因 Lua 阻塞在 JNI 内，额外用 `GenerateState.cancelled` 在回调顶部检查并抛错，配合 `activeStmt` 中断进行中的 `executeBatch()`。
- 被取消的请求走各自错误路径，在**原 request id** 上返回 `success=false, error="cancelled"`。取消未知 id 返回 `cancelled=false` + 说明，不抛异常；空 id 同样返回 `cancelled=false` 并指明缺失字段。
- 注意：`DATA.GENERATE` 已执行的批次**不会回滚**（造数不参与事务语义）。

### 数据导入（`IMPORT.RUN_IMPORT`）

- 与 EXPORT **对称的读方向**：从本地 CSV / JSON Lines 文件把数据导入目标表。
- 导入**在主进程内运行**（不启子进程，不引入 POI / Parquet / Hadoop 依赖），这也是取消能生效的原因 —— 批量语句登记在 `StatementRegistry`。
- 支持 `CSV` / `JSON_LINES` 两种格式；`ImportSourceFactory` 负责解析格式 / 编码 / 分隔符 / 表头等默认值。
- 前端按流式帧先收进度（`importProgress`）后收终态（`import.result`）。
- **注意点（一）—— `ignore_errors = true` 用逐行执行**：H2 的绑定阶段不报错，类型 / 约束错误要到 `executeBatch()` 才暴露，无法把失败归因到具体某行；PostgreSQL 还会因失败语句整体作废隐式事务，逐行重试只会级联出错。因此该开关改为**逐行 `executeUpdate`**，代价是吞吐下降 —— 这是用户显式选择容忍坏行时的取舍。结果 `success=true`、`rows_failed` 统计跳过行数，第一条跳过行的原因汇总在 `error` 里。默认 `ignore_errors = false` 保持批处理，第一个失败批次即中止。
- **注意点（二）—— 原子性**：非事务导入若失败，可能留下**已提交的前期批次**（H2 的 `executeBatch` 逐条执行）。需要「全成或全不成」时，用事务会话包住导入（`SYSTEM.BEGIN` 取 `sessionId`，失败后 `ROLLBACK`）—— 该组合有测试覆盖。

### 事务会话（`SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`）

- `Request.session_id`（字段 6）：**留空 = 无事务**，即与之前完全一致（每条语句自动提交）—— 这是**向后兼容保证**。
- `SYSTEM.BEGIN` 从连接池钉住一条连接并设 `autoCommit=false`，返回 `sessionId`；后续请求带上该 `sessionId` 即落在同一物理连接上，`COMMIT` / `ROLLBACK` 结束会话。
- 写请求带上未知 `sessionId` 会被**拒绝**（`success=false`），绝不静默自动提交。`COMMIT` / `ROLLBACK` 遇未知会话返回 `committed/rolled_back = false` + error，不抛异常（客户端可在断线后重试）。
- 每个连接池配置上限 `maximumPoolSize = 5`，因此同一配置最多 5 个并发会话；第 6 个 `BEGIN` 会撞上 HikariCP 5s 连接超时 —— 这是有意设计的**背压**，不是缺陷。

### 多语句脚本（`SQL.EXECUTE` + `multi_statement = true`）

- 一次提交多条以 `;` 分隔的语句；`SqlScriptSplitter` 只切**顶层**分号，正确处理 `'...'` / `"..."` / 反引号、`--` 与 `#` 行注释、`/* */` 块注释、PostgreSQL `$tag$ ... $tag$` / `$$` 美元引用。注释文本**原样保留**在语句内（MySQL `/*! ... */` 可执行注释有真实语义），只把其中的分号中和。
- 语句**按顺序执行、首个失败即停**（对齐 psql / DBeaver 默认行为）；`affected_rows` 为成功语句之和，失败语句在其 `index` 记录 `success=false` + error，后续语句不再执行。末尾无 `;` 的语句照样执行。
- 每条语句单独在 `StatementRegistry` 登记（`"$requestId#$index"`），因此取消总是打到**当前正在执行**的那条。
- 已知限制：不支持**嵌套**块注释（PostgreSQL 允许，MySQL / SQLite 不允许；失败方向是多切分而非静默吞数据）。

---

## gRPC 模式（跨进程场景 · 服务端）

```bash
# 构建引擎 fat jar + drivers + dialects
./gradlew engine:jar

# 启动 gRPC server（默认 TCP :50051；POSIX 上自动 fallback 到 unix）
cd engine/build/libs && java -jar idb-engine.jar

# 显式指定运行模式 + TCP 端口 / Unix Domain Socket / Windows Named Pipe
java -jar idb-engine.jar --mode grpc --ipc tcp --port 60000
java -jar idb-engine.jar --mode grpc --ipc unix --uds-path /run/idb/engine.sock

# Direct 模式 standalone 启动（不开 gRPC server，仅 bootstrap）
java -jar idb-engine.jar --mode direct
```

Kotlin 客户端（`engine-grpc-client` 模块）连接任意端点：

```kotlin
val client: EngineClient = GrpcEngineClient.connect(
    GrpcClientConfig.fromTarget("localhost:50051")   // 或 unix:///run/idb/engine.sock / pipe:idb-engine
)
val resp = client.invoke(connection) {
    category = Category.SCHEMA
    action = Action.LIST
    schemaRequest = SchemaRequest.newBuilder()
        .setList(schemaListRequest { level = "database" })
        .build()
}
client.close()
```

Go 等其他语言的客户端不受调用层抽象约束，直接用生成的 stub（完整代码见 `engine/README.md` §通信协议）：
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

## 两种调用方式的对比

| 维度 | Direct 模式（默认） | gRPC 模式 |
|---|---|---|
| 调用层实现 | `IdbEngine`（`:engine`） | `GrpcEngineClient`（`:engine-grpc-client`） |
| 调用方类型 | `EngineClient` | `EngineClient`（**同一接口**） |
| 通道 | 同 JVM 直接方法调用 | gRPC over IPC transport（TCP / UDS / pipe） |
| 客户端进程 | **必须同 JVM**（Kotlin / Java） | 任意（Go / Kotlin / TypeScript / Python） |
| 通信开销 | 零序列化 + 内存对象引用 | HTTP/2 + protobuf 编解码 |
| 响应类型 | `Flow<Response>` | `Flow<Response>`（**同一类型**） |
| 流式响应 | ✓ | ✓ 完全一致 |
| Envelope options（traceId/dryRun/timeoutMs） | ✓ | ✓ 完全一致 |
| 错误响应包装 | ✓ `success=false, error=...` 不抛异常 | ✓ 完全一致（含传输层故障） |
| 连接生命周期 | 本地直调 `SystemHandler` | 经 `SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT` 路由 |
| 子进程隔离 | ✗（同进程） | ✓（独立 daemon） |
| 跨语言互操作 | ✗ | ✓（任何支持 gRPC 的语言） |

**结论**：默认走 Direct（同 JVM，零开销）；需要跨进程 / 跨语言 / 子进程隔离时，只改一个系统属性即可切到 gRPC —— **业务代码不动**。

---

## 运行 KMP Desktop 应用

```bash
# Hot reload 开发模式
./gradlew :desktopApp:hotRun --auto

# 普通运行（Direct 模式：引擎同进程，无需子进程）
./gradlew :desktopApp:run

# 改走 gRPC：先启动独立引擎进程，再指定端点
java -jar engine/build/libs/idb-engine.jar --mode grpc --ipc tcp --port 50051
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051
```

默认运行时，引擎和方言插件通过 `:engine` 依赖直接共享在同一 JVM，无需部署子进程；
设置 `-Dsundays.engine.endpoint` 后，UI 改由 `:engine-grpc-client` 经 gRPC 调用独立引擎进程。

### 首次启动引导

第一次运行（`~/.config/sundays/settings.json` **不存在**时）不会直接进主界面，而是先渲染一页引导，
让你先挑**配色主题 / 明暗模式 / 界面密度**：

- **预览是免费的** —— 引导页渲染在 `SundaysTheme` 内部，每点一下立刻重绘整页，
  配色色卡、明暗、控件尺寸全部即时生效，不需要「预览图 + 应用按钮」。
- 点「开始使用」即完成并落盘。默认值本身也是一份合法答案，所以**没有**单独的「跳过」按钮。
- 想重新挑一遍：**设置 → 个性化 → 底部「重新打开引导」**。它以浮层盖在设置页之上，
  点「完成」后原地回到设置页；此刻标题是「外观引导」而不是「欢迎使用」。
- **老用户不会被升级打扰**：判据是「设置文件此前存不存在」，而不是磁盘里那个
  `onboardingCompleted` 字段 —— 老版本用户升级上来时那个字段根本不存在，
  只看字段会导致每次升级都被引导页拦一次。
- 想彻底重置（连外观一起）：删掉 `~/.config/sundays/settings.json`。

---

## 共享 UI 组件（`shared/` 模块）

`shared/commonMain` 提供一组**面向 KMP Compose Desktop** 的可扩展 UI 组件（`CodeEditor` / `DataTable` / `ConnectionManagerScreen` + 通用 UI 工具），均与引擎无关、可单独使用：

### `CodeEditor` —— 可扩展代码编辑器

`com.kxxnzstdsw.sundays.editor.ui.CodeEditor` / `CodeEditorWithToolbar`

特性：
- 语法高亮（基于 `CodeLanguageRegistry`，支持 SQL / Lua；新增语言只需 `registry.register(...)`）
- **行号 gutter**（动态宽度，按行数位数自适应；与编辑区共用 `ScrollState`，滚动完全同步）
- **工具栏**：`RowScope.() -> Unit` 插槽注入自定义按钮（"执行"、"清空"、"复制"…）
- **语言切换下拉框**：可隐藏（`showLanguageSwitcher = false`）；语言可在实例化时直接指定
- **格式化**：`CodeFormatterRegistry` 注册的格式化器自动启用；空格判定走两向贴合（注释只读、缩进原样搬运、幂等 —— 见 [`shared/ARCHITECTURE.md` §2.8](./shared/ARCHITECTURE.md#28-格式化契约)）
- **补全提示**（默认 `on`）：输入关键字 / 类型 / 函数前缀弹出候选，`Tab`/`Enter` 接受、`↑↓` 选择、`Esc` 关闭；`extraCompletions` 可注入**上下文专属**候选 —— 造数工作台据此补出沙箱宿主函数（`insert` / `lastId` / `random_*`）。SQL 忽略大小写、Lua 大小写敏感（见 [`shared/ARCHITECTURE.md` §2.10](./shared/ARCHITECTURE.md#210-补全提示)）
- **右键菜单**：`@Composable (EditorContextMenuPayload?) -> Unit` 插槽注入菜单项

#### 高度策略（v2.9+）

| `maxLines` | 行为 |
|---|---|
| `null`（**默认**） | 不施加高度上限 — 编辑器**填充父容器剩余高度**，但不会超过父容器；超出可滚动 |
| 传入整数 | 显式上下限（介于 `minHeight` 与 `maxHeight` 之间） |

```kotlin
// 默认行为 — 填充父容器高度（不超父容器）
CodeEditor(
    text = sql,
    onTextChange = { sql = it },
    languageId = "sql",
)

// 显式高度上限
CodeEditor(
    text = sql,
    onTextChange = { sql = it },
    languageId = "sql",
    minLines = 5,
    maxLines = 15,    // 超过则内部滚动
)
```

### `DataTable` —— 虚拟滚动数据表格

`com.kxxnzstdsw.sundays.table.DataTable`

特性：
- **大量数据**：基于 `LazyColumn` 的虚拟滚动（item key = 主键）
- **可配置表头**：`TableColumn(key, header, width, alignment, formatter, weight)`
- **分页**：`PageSize` 枚举 — `S10` / `S20` / `S50` / `S100` / `S200` / `S300` / `S500` / `ALL`
- **databind**：行数据由调用方管理 state 传入，变化自动重绘
- **单行详情面板**：点击行 → 右侧详情面板（可隐藏；可自定义 `detailPanel` 插槽；`detailPanelRatio` 控制宽度比）
- **单元格可选中**：每行包裹 `SelectionContainer`，可在单元格内拖拽选中
- **右键菜单**：`@Composable (TableRow?) -> Unit` 插槽；通过 `ContextMenuState.payload` 拿目标行
- **数据库主键**：`TableRow.id` 承载主键，详情面板 / 选中状态识别

#### 高度策略（v2.9+）

| `fillParentHeight` | 行为 |
|---|---|
| `true`（**默认**） | `fillMaxSize()` — 填满父容器剩余空间，**不会超出父容器** |
| `false` | 按内容自适应高度（外部父容器需自己处理滚动 / 尺寸） |

```kotlin
DataTable(
    columns = listOf(TableColumn("id", "ID"), TableColumn("name", "姓名")),
    rows = rows,
    pageSize = PageSize.S50,
    onPageSizeChange = { ... },
    currentPage = 1,
    onPageChange = { ... },
    // fillParentHeight 默认 true —— 填满父容器高度
    contextMenuState = rememberContextMenuState(),
    contextMenuItems = { row ->
        DropdownMenuItem(text = { Text("删除 ${row?.id}") }, onClick = { ... })
    },
)
```

### 通用 UI 工具（`shared/.../ui/`）

| 类型 | 用途 |
|---|---|
| `ContextMenuState<T : Any>` | 通用右键菜单状态（位置 + 可见性 + payload），被 `EditorContextMenuState` / 表格的 `ContextMenuState` 共用 |
| `Modifier.onRightClick { Offset -> Unit }` | 鼠标右键检测 modifier（基于 `awaitPointerEventScope` + `event.buttons.isSecondaryPressed`） |
| `rememberContextMenuState()` / `rememberEditorContextMenuState()` | Composable 工厂 |

### `ConnectionManagerScreen` —— 连接管理

`com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen`

布局：**左侧连接列表 + 右侧引导式配置页面**。

支持方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite。

两种独立流程：

| 入口 | `WizardFlow` | 步骤序列 | 最后一步 |
|---|---|---|---|
| 新建连接 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」(持久化到 `connection.json`) |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT → CREDENTIALS → TEST_SAVE` (3 步) | 「连接」(直接连接，不持久化) |
| 编辑已有 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」(覆盖原配置) |

特性：
- **持久化**：`ConnectionStorage` 自动读写 `~/.config/sundays/connection.json`（`kotlinx.serialization` + JSON；只落 `jdbcUrl` + 凭据，按 `version` 分派 v1/v2 并自动迁移）
- **原子状态更新**：`WizardState(editingConnection, step, flow)` data class，单次赋值保证三字段同步，避免 Compose recomposition 间隙 NPE
- **步骤指示器自适应**：快速连接 3 段 / 普通 4 段
- **JDBC URL 折算（真相源）**：`JdbcUrl.kt` 的 `buildJdbcUrl(config, extraQuery)` / `parseJdbcUrl(url, dialect)` 覆盖 5 个方言 × 连接类型 —— `CLIENT_SERVER` 显示 5 个独立字段（host/port/database/username/password）+ JDBC URL 输入框互为投影；嵌入式方言（H2 / DuckDB / SQLite）用单一目标字段折算 URL；额外参数（`?useSSL=false&...`）始终保留，MySQL 无显式参数时补方言默认参数
- **URL 缺失不可放行**：字段不足以折算 URL 时「下一步」/「保存」/「连接」/「测试连接」全部禁用，保证交给引擎的配置一定有合法 URL
- **连接总览与状态**：选中连接时右侧展示状态（未连接 / 连接中 / 已连接 / 失败）+ 连接信息 + 「连接」/「断开」/「编辑」/「删除」；列表项带状态色点
- **快速连接不持久化**：`QUICK_CONNECT` 流程最后一步为「连接」而非「保存」，调用 `onQuickConnectDirect` 仅设置 `selectedConnection` 并由集成层直接连库，**不写入** `ConnectionStorage`
- **测试 / 连接 / 断开**：`onTestConnection` / `onConnect` / `onDisconnect` 回调注入 —— `shared` 仍**不依赖** `:engine`，由集成层（desktopApp）直连 `IdbEngine.testConnection`（建池 + `isValid`）与 `IdbEngine.disconnect`（释放该配置的连接池）

```kotlin
@Composable
fun ConnectionManagerScreen(
    connections: List<ConnectionConfig>,
    selectedConnection: ConnectionConfig?,
    editingConnection: ConnectionConfig?,
    wizardStep: WizardStep,
    wizardFlow: WizardFlow,
    onSelectConnection: (ConnectionConfig?) -> Unit,
    onNewConnection: () -> Unit,                  // 普通流程入口
    onQuickConnect: () -> Unit,                   // 快速连接入口
    onEditConnection: (ConnectionConfig) -> Unit,
    onSaveConnection: (ConnectionConfig) -> Unit,  // NORMAL 流程的「保存」按钮
    onQuickConnectDirect: (ConnectionConfig) -> Unit, // QUICK_CONNECT 流程的「连接」按钮
    onDeleteConnection: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onWizardNext: (WizardStep) -> Unit,
    onWizardBack: () -> Unit,
    onUpdateEditingConnection: (ConnectionConfig) -> Unit,
    onTestConnection: (suspend (ConnectionConfig) -> TestResult)? = null,  // 「测试连接」按钮
    connectionStatuses: Map<String, ConnectionStatus> = emptyMap(),        // 引擎侧会话状态（列表色点 + 总览）
    onConnect: (ConnectionConfig) -> Unit = {},                            // 总览「连接」：建池 + isValid
    onDisconnect: (ConnectionConfig) -> Unit = {},                         // 总览「断开」：释放连接池
)

// 持久化 API
ConnectionStorage.load()              // List<ConnectionConfig>
ConnectionStorage.upsert(config)      // 新增或更新，返回最新列表
ConnectionStorage.delete(id)          // 删除
ConnectionStorage.get(id)             // 单个查询
```

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
java -jar idb-engine.jar --mode grpc --ipc unix --uds-path /run/idb/engine.sock
```

---

## 运行测试

```bash
# 全部 619 测试
./gradlew test

# 单个方言模块
./gradlew :dialect-h2:test          # 63 测试
./gradlew :dialect-duckdb:test      # 81 测试（v2.7）
./gradlew :dialect-sqlite:test      # 62 测试（v2.8）

# 引擎模块
./gradlew :engine:test              # 277 测试（v2.16：+89，含 Direct 模式契约 + SYSTEM.DISCONNECT + 新引擎能力）

# gRPC 调用模块（v2.15）
./gradlew :engine-grpc-client:test  # 21 测试（真实 gRPC server + 真实 H2 端到端）

# Desktop App（含引擎实现装配测试）
./gradlew :desktopApp:test          # 18 测试

# Desktop App 共享代码测试
./gradlew :shared:jvmTest           # 共享 UI / 逻辑测试（编辑器 + 表格 + 右键菜单，97 项）
```

测试覆盖率：
- **engine:test**（277 项，v2.16 起）：IPC config + transport round-trip + HikariCP pool + DialectLoader + handler 集成（typed proto builders）+ envelope options + DuckDB / SQLite 端到端 + LIST_DRIVERS + **Direct 模式契约** + **SYSTEM.DISCONNECT 路由与 dryRun（v2.15）** + **v2.16 新引擎能力**：`SqlScriptSplitterTest`（多语句切分）、`importer/CsvReaderTest` / `JsonLinesReaderTest` / `ImportFormatTest` / `ImportSourceFactoryTest`（导入阅读器）、`integration/CancelIntegrationTest`（4 项查询取消）、`integration/MultiStatementIntegrationTest`（6 项多语句）、`integration/TransactionIntegrationTest`（7 项事务）、`integration/ImportIntegrationTest`（9 项导入）
- **engine-grpc-client:test**（21 项，v2.15）：**真实 gRPC 服务端**（`NettyServerBuilder.forPort(0)` + `IdbEngineImpl`）+ **真实 H2** —— 远程连接初始化 / **与同进程实现的响应平价** / 流式分帧 / 远程连接释放 / 传输层故障 / 端点解析边界
- **desktopApp:test**（18 项）：连接流程 / 数据库浏览 / 顶层导航 / 方言名契约 + **引擎实现装配（v2.15）**
- **dialect-h2 / -duckdb / -sqlite:test**：方言 SPI 方法全量覆盖（206 项）
- **shared:jvmTest**：Kotlin Multiplatform 共享代码（编辑器 / 表格 / 右键菜单，97 项）
- **总计：619 测试，0 失败 / 0 错误（1 个 Windows-only IpcConfigTest 用例 skip）**

---

## 文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | **架构导航（V2.16）** —— 调用层架构、模块依赖、方言特性、迁移历史 |
| [`engine-protocol/README.md`](./engine-protocol/README.md) | **调用层契约** —— `EngineClient` 接口、proto 协议、`SYSTEM.DISCONNECT` 路由 |
| [`engine-grpc-client/README.md`](./engine-grpc-client/README.md) | **gRPC 调用模块** —— `GrpcEngineClient` 跨进程实现、端点格式、端到端测试 |
| [`engine/README.md`](./engine/README.md) | 引擎模块详细 README —— CLI、构建运行、handler 路由矩阵、API 参考 |
| [`shared/src/`](./shared/src) | KMP 共享代码 —— `commonMain/`（平台无关）/ `jvmMain/`（JVM 特定） |
| [`engine-protocol/src/main/proto/idb_engine.proto`](./engine-protocol/src/main/proto/idb_engine.proto) | gRPC service 定义 + 全部 typed message schemas |

---

## 技术栈

- **Kotlin 2.4.20 / JDK 25**
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
| --- | --- |
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
| v2.9 | KMP Desktop Direct 模式 + 双模式架构：前端从 Wails v3 gRPC 子进程迁移到 KMP Compose Desktop（`desktopApp/`），引擎与 UI 同 JVM；新增 `IdbEngine` facade（`handle()` / `invoke()`），`IdbEngineImpl` 薄壳化；CLI `--mode grpc\|direct` 切换；`RequestDispatcher.dispatch` catch 外置到 `.catch{}` operator 修复 *Flow exception transparency violated*<br>CodeEditor / DataTable 高度策略统一：`CodeEditor.maxLines` 默认 `null`（不施加高度上限，填充父容器剩余高度但不超父容器）；`DataTable.fillParentHeight` 默认 `true`（同语义）。两个组件均无需调用方显式指定高度即自适应父容器；只在显式传入参数时才启用硬上限<br>`shared/connection/` —— 新增 `ConnectionManagerScreen`（左侧连接列表 + 右侧引导式配置）+ `ConnectionStorage` JSON 持久化到 `~/.config/sundays/connection.json`；支持 MySQL/PostgreSQL/H2/DuckDB/SQLite 五种方言；普通流程 4 步 + 快速连接 3 步两种独立引导（`WizardFlow` 标识） |
| v2.10 | 移除 `desktopApp` 演示 `DemoApp` 顶层 tab 切换，仅保留连接管理 (`ConnectionManagerScreen`)；`ConnectionConfig` 新增 `jdbcUrl` 字段支持标准 JDBC URL 持久化；`CredentialsStep` 实现字段 ↔ JDBC URL 双向同步（`buildJdbcUrl` / `parseJdbcUrl`），修改任一字段实时拼接/解析 URL，保留 `?额外参数`；默认填入 `host=localhost` 与方言默认端口（MySQL `3306` / PostgreSQL `5432`）；`ConnectionConfig` 新增 `username:password@` 凭据段拼接支持；快速连接流程（`WizardFlow.QUICK_CONNECT`）最后一步改为「连接」（`Bolt` 图标）调用 `onQuickConnectDirect`，**不写入** `ConnectionStorage`，仅设为 `selectedConnection`；`ConnectionSummary` 新增 `JDBC URL` 行 |
| v2.11 | **引擎支持仅凭 JDBC URL 初始化连接 + 连接生命周期直连方法**<br>proto `ConnectionConfig` 新增 `jdbc_url` 字段；`PoolManager.createDataSource` 在 `jdbc_url` 非空时直接用它建 HikariCP 池（`host`/`port`/`database` 忽略），连接池 hash key 纳入 `jdbc_url`<br>方言反查：`DatabaseDialect` 新增 `jdbcUrlPrefix`（默认 `jdbc:<driverName 小写>:`，5 个内置方言显式覆盖），`DialectLoader.getDialectByJdbcUrl()` 按最长前缀匹配；无匹配时 `PoolManager.resolveDialect` 抛出可读错误而非静默回退<br>`IdbEngine` facade 新增直连方法 `testConnection(config)` / `testConnection(jdbcUrl, user, password)` —— 不经 gRPC server、不经 IPC、不经 `RequestDispatcher` envelope，直接调 `SystemHandler.testConnection`；首次调用即创建/复用连接池（**连接初始化**）并做 JDBC `isValid(5)` 校验<br>`SYSTEM.TEST_CONNECTION` 响应的 `driver` 改由 URL 反查出的方言决定（不再回显 `config.driver`）<br>`ConnectionManagerScreen` 新增 `onTestConnection: (suspend (ConnectionConfig) -> TestResult)?` 回调，`TestSaveStep` 的「测试连接」按钮从空操作改为真实调用；`desktopApp` 通过 `engine.testConnection(jdbcUrl, username, password)` 直连引擎 |

| v2.12 | **连接管理流程与引擎打通：连接生命周期（连接 / 断开）+ 方言装配修复**<br>`IdbEngine` facade 新增 `disconnect(config)`（→ `PoolManager.close(config)`：释放该配置下**所有** schema 维度的连接池），与 `testConnection` 构成对称生命周期；pool key 改为两段式 `sha256(配置)#sha256(schema)` 以便按配置定位池，并新增 `activePoolCount()`（诊断 / 测试）<br>`DialectLoader` 新增**应用类路径 SPI**加载通道（`ServiceLoader`，`desktopApp` 用 `runtimeOnly` 引入 5 个方言插件 + 5 个 JDBC 驱动）—— 修复 Direct 模式下 `dialects/` 目录缺失导致 `No dialect plugin matches JDBC URL`、测试连接永远失败的问题<br>`shared/connection`：`JdbcUrl.kt` 独立出字段 ↔ JDBC URL 折算（覆盖 5 个方言 × 连接类型，MySQL 默认参数、H2 `mem:`/`file:`、DuckDB、SQLite），`ConnectionConfig` 删除死字段 `filePath` / `useJdbcUrl`（`database` 统一承载嵌入式 URL 主体），向导各步骤改为**直写调用方状态**（修复连接名称、连接类型选完即丢的问题），URL 折算不出时禁用放行按钮，新增**连接总览面板**（状态 + 连接/断开/编辑/删除）与列表状态色点；`ConnectionStorage` 按文件 `version` 分派 v1/v2 并真正迁移<br>`desktopApp`：连接列表 / 向导 / 引擎会话状态收敛到新的 `ConnectionSession` 状态机（`MainScreen` 只做绑定），新增 `ConnectionManagerFlowTest`（Compose UI 真点击 + 真引擎：选方言 → 填字段 → 测试连接 → 连接 → 断开，断言连接池建立与释放、`connection.json` 落盘） |
| v2.13 | **顶层导航 + 数据库浏览第二屏**<br>`desktopApp`：新增 `Navigation.kt`（`AppDestination` 枚举）与 `MainScreen` 顶层导航条（`TopNavBar` / `NavChip`），在「连接管理」与「数据库浏览」之间切换；连接列表仍由 `ConnectionSession` 持有（位于导航之上），切换不丢连接<br>新增 `DatabaseBrowserScreen.kt`：**左侧**库/表树（`SCHEMA.LIST level=database` 拉库 → 展开时懒加载 `TABLE.LIST`）+ **右侧** `SecondaryScrollableTabRow` 标签页与 `DataTable` 预览。**双击**表名打开预览标签页（`DATA.LIST`，`page=1` / `pageSize=100`），同一张表以 `schema::table` 为唯一键**去重** —— 重复双击只激活已有标签页、不重复加载；标签可逐页关闭且选中索引自动回退<br>`DatabaseBrowserState`：连接切换时清空全部派生状态（库 / 展开节点 / 表缓存 / 标签页）并自增**会话代次** `generation`，三个异步入口在挂起点后比对代次、**丢弃跨连接的过期响应**<br>注意点：proto `ConnectionConfig.driver` 是各 handler 取方言的必填字段；库名**不写入** `schema` 字段（否则 H2 `SET SCHEMA <dbname>` 失败）；`DATA.LIST` 的 `pageSize = 0` 是流式哨兵，预览请求侧 `coerceAtLeast(1)`<br>测试：`DatabaseBrowserFlowTest`（状态机全链路）、`DatabaseBrowserUiTest`（真点击 + `Role=TAB` 标签数恒为 1）、`MainScreenNavTest`（导航切换）<br>详细：[`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) §数据库浏览 |
| v2.14 | **共享代码上移 `shared`（模块边界整理）**<br>`desktopApp` 中不依赖 `:engine` 的纯 UI 迁入 `:shared` 的 `commonMain`：新增 `navigation/AppDestination.kt`（顶层导航目标枚举）、`navigation/TopNavBar.kt`（`TopNavBar` + 私有 `NavChip`）、`ui/Theme.kt`（`SundaysTheme`，跟随系统明暗）。`desktopApp/Navigation.kt` 删除，`main.kt` 瘦身为「平台窗口 + 引擎状态机接线」（203 → 114 行），同时消除原先为规避导入冲突而写的 `private fun isSystemInDarkTheme()` 包装<br>边界判据：`:engine` 是纯 JVM 模块（`kotlin("jvm")` + HikariCP/JDBC/gRPC/Hadoop），而 `shared` 业务代码全在 KMP `commonMain` —— 任何引用 `IdbEngine` / proto 的代码都无法上移。故 `ConnectionSession`（`IdbEngine.testConnection` / `disconnect`）与 `DatabaseBrowserState` / `DatabaseBrowserScreen`（`IdbEngine.invoke` + proto 构造器）**保留在 desktopApp**，`engine ↮ shared` 的依赖方向不变<br>`TopNavBar` 新增 `modifier` 参数，与 shared 其余组件的 Reasonable Defaults 约定一致 |
| v2.15 | **调用层抽象（invocation layer）**<br>新增 `engine-protocol/` 模块：proto 源从 `engine/src/main/proto/` 迁出（protobuf gradle 配置同步迁出），新增 `EngineClient` 接口（`handle` / `invoke` / `testConnection` / `disconnect` / `close`）—— 调用方只面向接口编程；该模块零业务逻辑，依赖以 `api` 导出，供服务端与客户端同时依赖而不成环<br>新增 `engine-grpc-client/` 模块：`GrpcEngineClient` 经 gRPC stub 实现同一接口（TCP / UDS / 命名管道），仅依赖 `:engine-protocol`，**不拖入** Hadoop / POI / LuaJIT / 方言插件；`GrpcClientConfig.fromTarget` 解析 `host:port` / `tcp://` / `unix://` / `pipe:` 端点，格式错误在连接前抛出<br>协议新增 `SYSTEM.DISCONNECT`（`Action.DISCONNECT = 19` + `SystemDisconnectResponse{closed}` + `SystemResponse.disconnect = 5`）—— 连接池活在引擎进程内，远程调用方需要线上路由才能释放；已纳入 `writeActions`（`dryRun` 短路，不得真的掐断用户连接）且幂等<br>`desktopApp`：`ConnectionSession` / `DatabaseBrowserState` / `MainScreen` 改持 `EngineClient`；`main.kt` 成为唯一装配点，`-Dsundays.engine.endpoint` 切换实现（默认同进程 `IdbEngine`）<br>修复 `IpcConfig.fromArgs` 把 `--mode` 误判为未知参数 —— 文档中的 `java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051` 此前必定退出失败，gRPC 客户端无从连上一个按文档启动的服务端<br>测试：530 项全通过（`engine` 188 / `engine-grpc-client` 21 / `desktopApp` 18 为新增） |

| v2.16 | **引擎能力补齐 —— 关闭与 DBeaver / Navicat / DataGrip 的功能差距**<br>**查询取消**：新增 `SYSTEM.CANCEL`（`Action.CANCEL = 20`）—— 协程取消无法打断阻塞的 JDBC 调用，只有 `Statement.cancel()` 能真正停掉数据库侧工作；新增 `StatementRegistry` 按 request id 登记 `Statement` / canceler，覆盖 `SQL.EXECUTE`、流式 `DATA.LIST`（`pageSize=0`）、`DATA.GENERATE`；被取消请求在原 id 上返回 `success=false, error="cancelled"`<br>**数据导入**：新增 `IMPORT.RUN_IMPORT`（`Category.IMPORT = 14` / `Action.RUN_IMPORT = 21`，与 EXPORT 对称），**主进程内运行**（不引入 POI / Parquet / Hadoop，故可取消）；新包 `com.kxxnzstdsw.importer`（`ImportSource` / `ImportFormat` / `CsvReader`（RFC-4180 状态机）/ `JsonLinesReader` / `ImportSourceFactory`）支持 CSV / JSON Lines；`ignore_errors=true` 改为逐行 `executeUpdate`（H2 绑定阶段不报错，批处理无法归因到行），代价是吞吐下降<br>**事务会话**：新增 `SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO`（`Action.BEGIN=22` / `COMMIT=23` / `ROLLBACK=24` / `SESSION_INFO=25`）+ `Request.session_id`（字段 6，**留空 = 无事务，向后兼容**）；`TransactionManager` 钉住连接并 `autoCommit=false`，连接所有权归会话直到 `COMMIT` / `ROLLBACK`<br>**多语句脚本**：`SqlExecuteRequest.multi_statement` + `SqlScriptSplitter`（只切顶层 `;`，正确处理引号 / 注释 / PostgreSQL 美元引用；注释文本原样保留），按序执行、首个失败即停<br>测试：`:engine:test` 188 → **277**（+89），项目总计 **619**，0 失败 |
| v2.17 | **`shared/editor` 编辑器能力补齐**<br>**两向贴合的空格判定**：抽 `formatter/TokenSpacing.kt`（两个纯函数 `bindsLeft` / `bindsRight`），规则「两相邻 token 之间要空格 ⟺ 左不贴右 且 右不贴左」。换掉旧版「写前决定下一个」的 `pendingSpace` 标志；彻底消灭 `id , name` / `id> 1` / `a:: int` / `t. col` / `( 1 )` 一类反复出现的畸形，以及「格式化两次才收敛」的伪幂等性。注释只读（块注释里常放代码样例，碰了就是破坏），只清行尾空白，**不被主子句换行规则吞掉**；缩进原样搬运；空行折叠至多一个；幂等性逐样本锁定<br>**编辑器补全（「提示」）**：`CodeLanguage` SPI 新增 `completionCandidates(prefix, limit)`，默认实现返回空列表（只支持高亮的语言零改动）。SQL 忽略大小写、Lua 大小写敏感 ——「谁拥有大小写规则谁说了算」，把 `Pri` 补成 `print` 是制造 bug。词字符限定 ASCII（`Char.isLetterOrDigit()` 对中文返回 `true`，会让中文注释吞掉英文词）。接受候选替换**整个词**（光标在词中间时），弹层置于滚动容器内 + `Modifier.atCaret` 自绘 0×0 报告（不撑大编辑器高度，天然跟着代码滚），按键走 `onPreviewKeyEvent` 才能 `consume` `Tab`/`Enter` 的默认行为<br>**造数沙箱宿主函数进入补全**：`CodeEditor(extraCompletions = …)` 注入**上下文专属**候选 —— 造数工作台据此补出引擎注入的 13 个全局（`insert` / `lastId` / `random_*`）并显示签名。它们**绝不进** `LuaLanguage.BUILTINS`：那批函数只在造数沙箱里存在，普通 Lua 编辑器中调用会报 `attempt to call a nil value`，塞进语言词表等于让所有 Lua 补全都推荐不存在的函数。清单是静态复制（`:shared` 不能反向依赖 `:engine`），由 `GenerateSandboxContractTest` 读引擎源码抽取实际注册的全局名并断言**集合相等**来防漂移<br>测试：`FormatterSpacingTest`（27 项）+ `CompletionTest`（27 项，含「宿主函数不得进 `LuaLanguage`」不变量）+ `GenerateSandboxContractTest`（3 项）。变异验证 7 处全部如期变红<br>详细：[`shared/ARCHITECTURE.md` §2.8 / §2.10](./shared/ARCHITECTURE.md) |

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/).
