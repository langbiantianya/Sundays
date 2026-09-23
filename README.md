# sundays — Kotlin 数据库管理端

一个使用 Kotlin 编写、面向桌面端的**数据库管理工具**。前端是 **Kotlin Multiplatform + Compose Multiplatform** 桌面应用（`desktopApp/` 模块），后端是 v2.9 起支持**双模式架构**的无头引擎（`engine/` 模块）：

| 模式 | 引擎入口 | 客户端 | 适用场景 |
|---|---|---|---|
| **Direct 直接模式（v2.9 推荐 · 默认）** | `IdbEngine().handle(request): Flow<Response>` | Compose UI（同 JVM） | KMP Desktop 应用，零序列化、零子进程 |
| **gRPC 模式（向后兼容）** | `IdbEngineServer`（gRPC server over IPC transport） | 任意 gRPC client | 跨进程、跨语言、子进程隔离、远程调试 |

引擎支持 **5 个** 可插拔方言：**MySQL** / **PostgreSQL** / **H2** / **DuckDB**（本地嵌入式 OLAP，v2.7） / **SQLite**（本地嵌入式关系型，v2.8）。

> **当前版本：v2.9** — KMP Desktop 前端 + Direct 模式 + 双模式架构
>
> 详细架构设计见 [`ARCHITECTURE.md`](ARCHITECTURE.md)（V2.9），引擎 README 见 [`engine/README.md`](./engine/README.md)。

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
│   ├── commonMain/       KMP 共享逻辑（与平台无关）—— 含 UI 组件（编辑器 / 表格 / 右键菜单）
│   │   ├── editor/       CodeEditor：可扩展代码编辑器（语法高亮 + 行号 + 工具栏 + 右键菜单）
│   │   ├── table/        DataTable：虚拟滚动数据表格（分页 + 详情面板 + 右键菜单）
│   │   ├── connection/   ConnectionManagerScreen：连接管理（左侧连接列表 + 右侧 4 步向导，JSON 持久化到 ~/.config/sundays/connection.json）
│   │   └── ui/           通用 UI 工具（ContextMenuState、onRightClick modifier）
│   └── jvmMain/          JVM 特定逻辑（如 Okio 文件系统等）
└── desktopApp/           Compose Multiplatform Desktop 应用（v2.9 新前端）
    ├── build.gradle.kts  dependencies 含 implementation(project(":engine")) — Direct 模式依赖
    └── src/main/kotlin/com/kxxnzstdsw/sundays/
        └── main.kt       KMP Desktop 入口：IdbEngine() 直接持有、Compose UI 渲染
```

---

## Direct 模式：KMP Desktop 与引擎的集成（v2.9 推荐）

**核心思路**：KMP Desktop 与引擎部署在**同一个 JVM 进程**，不通过 gRPC / IPC transport 通信，而是通过 `IdbEngine` facade **直接方法调用**。

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt
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

Go 客户端连接示例（`engine/README.md` §通信协议 与 `ARCHITECTURE.md` §8.3 有完整代码）：
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

## 共享 UI 组件（`shared/` 模块）

`shared/commonMain` 提供一组**面向 KMP Compose Desktop** 的可扩展 UI 组件（`CodeEditor` / `DataTable` / `ConnectionManagerScreen` + 通用 UI 工具），均与引擎无关、可单独使用：

### `CodeEditor` —— 可扩展代码编辑器

`com.kxxnzstdsw.sundays.editor.ui.CodeEditor` / `CodeEditorWithToolbar`

特性：
- 语法高亮（基于 `CodeLanguageRegistry`，支持 SQL / Lua；新增语言只需 `registry.register(...)`）
- **行号 gutter**（动态宽度，按行数位数自适应；与编辑区共用 `ScrollState`，滚动完全同步）
- **工具栏**：`RowScope.() -> Unit` 插槽注入自定义按钮（"执行"、"清空"、"复制"…）
- **语言切换下拉框**：可隐藏（`showLanguageSwitcher = false`）；语言可在实例化时直接指定
- **格式化**：`CodeFormatterRegistry` 注册的格式化器自动启用
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
- **右键菜单**：`@Composable (TableRow?) -> Unit` 插槽；通过 `ContextMenuState.targetRow` 拿目标行
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
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock
```

---

## 运行测试

```bash
# 全部 451 测试
./gradlew test

# 单个方言模块
./gradlew :dialect-h2:test          # 63 测试
./gradlew :dialect-duckdb:test      # 81 测试（v2.7）
./gradlew :dialect-sqlite:test      # 62 测试（v2.8）

# 引擎模块
./gradlew :engine:test              # 174 测试（含 v2.9 新增 4 项 IdbEngineDirectTest）

# Desktop App 共享代码测试
./gradlew :shared:jvmTest           # 共享 UI / 逻辑测试（编辑器 + 表格 + 右键菜单，约 71 项）
```

测试覆盖率：
- **engine:test**（174 项）：IPC config + transport round-trip + HikariCP pool + DialectLoader + 11 个 handler 集成（typed proto builders）+ envelope options + DuckDB / SQLite 端到端 + LIST_DRIVERS + **Direct 模式契约（v2.9 新增）**
- **dialect-h2 / -duckdb / -sqlite:test**：方言 SPI 方法全量覆盖（206 项）
- **shared:jvmTest**：Kotlin Multiplatform 共享代码 —— Lua tokenizer（30 项）+ SQL tokenizer（9 项）+ EditorIntegration（12 项）+ TableModels（18 项）+ SharedCommon / SharedLogicDesktop（各 1 项），合计 **71 项**
- **总计：451 测试，0 失败 / 0 错误（1 个 Windows-only IpcConfigTest 用例 skip）**

---

## 文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | **架构设计文档（V2.9）** —— gRPC 协议、handler 矩阵、方言特性、envelope options、双模式架构、迁移历史 |
| [`engine/README.md`](./engine/README.md) | 引擎模块详细 README —— CLI、构建运行、handler 路由矩阵、API 参考、Direct 模式示例 |
| [`shared/src/`](./shared/src) | KMP 共享代码 —— `commonMain/`（平台无关）/ `jvmMain/`（JVM 特定） |
| [`engine/src/main/proto/idb_engine.proto`](./engine/src/main/proto/idb_engine.proto) | gRPC service 定义 + 全部 typed message schemas |

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

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/).