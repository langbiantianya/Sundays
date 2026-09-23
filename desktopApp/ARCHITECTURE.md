# desktopApp — KMP Compose Desktop 客户端内部架构（v2.12）

## 概述

`desktopApp/` 是 `sundays` 项目的**桌面客户端模块**。它使用 **Kotlin Multiplatform + Compose Multiplatform** 构建，**当前仅启用 JVM Desktop 单平台目标**（macOS / Linux / Windows 三端共享同一份 Compose Desktop (Skia) 渲染），通过 **v2.9 Direct 直接模式** 与引擎集成 —— `IdbEngine()` facade 直接方法调用，**typed proto 消息同 JVM 直传，零序列化、零子进程、零 gRPC channel、零 IPC transport**。

**未来扩展路径**：KMP 工程结构天然支持后续启用 `androidMain` / `iosMain` / `wasmJsMain` source set —— 只需新增对应平台特定的子进程拉起逻辑（如 Android 的 `bindService`、iOS 的 `NSXPCConnection`），`commonMain` 中的业务层零修改复用。当前 v2.9 demo 阶段仅暴露 `main` 单一 source set。

**关键设计原则**：

- **依赖方向**：`desktopApp` → `:shared`（UI 组件）+ `:engine`（业务引擎）。**反向依赖被严格禁止** —— 引擎与 shared 模块均不感知 desktopApp 存在。
- **同进程集成**：`desktopApp` 与 `:engine` **必须部署在同一 JVM**（Kotlin / Java），Direct 模式无 IPC 跨进程语义。
- **连接管理优先**：v2.10 起 `main.kt` 直接渲染 `ConnectionManagerScreen`（v2.9 演示阶段的 `DemoApp` / `DemoTabBar` / `EditorDemoScreen` / `TableDemoScreen` 已删除），后续 SQL 编辑器 / 数据表格模块按需独立接入。

---

## 工程结构

```text
desktopApp/
├── build.gradle.kts    # composeMultiplatform + compose.material3 + :engine / :shared + 方言/驱动 runtimeOnly 依赖
└── src/
    ├── main/kotlin/com/kxxnzstdsw/sundays/
    │   ├── main.kt             # 入口：main() + MainScreen() + isSystemInDarkTheme()
    │   └── ConnectionSession.kt # 连接会话状态机：列表 / 向导 / 引擎会话状态 + 全部回调
    └── test/kotlin/com/kxxnzstdsw/sundays/
        └── ConnectionManagerFlowTest.kt  # Compose UI 端到端测试（真引擎 + 真点击）
```

**文件清单**：

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 声明 `kotlinJvm` / `composeMultiplatform` / `composeCompiler` 插件；`:engine` / `:shared` 依赖 + `protobuf-java` / `protobuf-kotlin-lite`（消费 typed proto）；**5 个方言插件 + 5 个 JDBC 驱动以 `runtimeOnly` 上应用类路径**（Direct 模式无需外部 `dialects/` `drivers/` 目录）；`compose.uiTest` + JUnit4 测试依赖；原生分发目标 `Dmg` + `Msi` + `Deb` |
| `main.kt` | 应用入口（`application { Window { MainScreen(engine) } }`）；`MainScreen` 只把 `ConnectionSession` 绑定到 `ConnectionManagerScreen` |
| `ConnectionSession.kt` | 连接会话状态机（Compose 快照状态持有者）：`connectionList` / `selectedConnection` / `wizard` / `statuses` + `connect` / `disconnect` / `testConnection` / `save` / `delete` / 向导步进 |
| `ConnectionManagerFlowTest.kt` | 端到端流程测试：真 `IdbEngine` + 真点击（`runComposeUiTest`），断言连接池建立/释放、状态流转、`connection.json` 落盘 |

**`main.kt` 内符号分解**（自顶向下）：

| 符号 | 可见性 | 职责 |
|---|---|---|
| `main()` | public | `application { ... }` 入口；构造 `IdbEngine()`、创建 `Window`、安装 `MaterialTheme`、渲染 `MainScreen(engine)`；`onCloseRequest` 调 `engine.close()` |
| `MainScreen(engine)` | private `@Composable` | `remember { ConnectionSession(engine, rememberCoroutineScope()) }`，把会话状态与全部回调透传给 `ConnectionManagerScreen`（无业务逻辑） |
| `isSystemInDarkTheme()` | private `@Composable` | 包装 `androidx.compose.foundation.isSystemInDarkTheme()`（避免导入冲突） |

**`ConnectionSession.kt` 内符号分解**：

| 符号 | 职责 |
|---|---|
| `ConnectionSession(engine, scope)` | 状态 + 行为容器。Compose 快照状态：`connectionList`（`ConnectionStorage` 镜像）、`selectedConnection`、`wizard: WizardState`、`statuses: Map<String, ConnectionStatus>`；行为：`newConnection` / `quickConnect` / `edit` / `updateEditing` / `goToStep` / `back` / `cancelEdit` / `save` / `quickConnectDirect` / `delete` / `connect` / `disconnect` / `testConnection` |
| `WizardState(editingConnection, step, flow)` | 三字段原子更新容器（单次赋值，避免 recomposition 间隙 NPE）；`WizardState.Idle` 为空闲态常量 |
| `engineConfig(config)` | private：UI 配置 → proto `ConnectionConfig` 映射（只传 `jdbcUrl` + `user` + `password`，方言由 URL scheme 反查；**注意 DSL 内勿写 `jdbcUrl = jdbcUrl`**，会自赋值到 builder 属性） |

---

## 连接管理 (`ConnectionSession` + `MainScreen`)

`MainScreen` 只做绑定：`ConnectionSession` 持有全部状态与行为，`ConnectionManagerScreen` 是纯展示组件。

- 从 `~/.config/sundays/connection.json` 加载连接列表（`ConnectionStorage.load()`，`ConnectionSession` 初始化时读取）
- 维护 `WizardState(editingConnection, step, flow)` **data class** —— 单次赋值保证原子更新，避免 Compose recomposition 间隙 NPE
- 三个入口：
  - **左侧「快速连接」按钮**（⚡）→ `flow = QUICK_CONNECT`
  - **左侧「新建连接」按钮**（＋）→ `flow = NORMAL`，起始 `BASIC_INFO`
  - **列表项「编辑」菜单** → `flow = NORMAL`，起始 `BASIC_INFO`（保留原有配置）
- 新建 / 快速连接都经 `withDialect(MYSQL)` 播种：立即套用方言默认值并折算 JDBC URL，保证进入凭据步骤即有合法 URL

### 流程对照表

| 入口 | flow | 步骤序列 | 最后一步 |
|---|---|---|---|
| 新建 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → `ConnectionStorage.upsert()` |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT → CREDENTIALS → TEST_SAVE` (3 步，跳过 BASIC_INFO / CONNECTION_TYPE) | 「连接」 → **不写入** `ConnectionStorage`，直接 `engine.testConnection()` 连库 |
| 编辑 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → 覆盖原配置（字段变化时先释放旧连接池） |

`ConnectionSession` 在每次切换入口时**同步**设置 `flow`，确保 `ConnectionWizardPanel` 的步骤指示器自适应总数（4 vs 3）。

### 连接生命周期（引擎侧效果）

| UI 动作 | `ConnectionSession` | 引擎调用 | 结果 |
|---|---|---|---|
| 测试连接（`TEST_SAVE` 步骤） | `testConnection(config)` | `IdbEngine.testConnection` | 建/复用 HikariCP 池 + `isValid`，状态 → `CONNECTED` / `FAILED` |
| 连接（连接总览 / 快速连接末步） | `connect(config)` | `IdbEngine.testConnection` | 同上；状态先置 `CONNECTING` 再回填 |
| 断开（连接总览） | `disconnect(config)` | `IdbEngine.disconnect` | 释放该配置的池，状态 → `DISCONNECTED` |
| 删除连接 / 编辑后字段变化 | `delete` / `save` | `IdbEngine.disconnect` | 先释放旧配置的池再落盘删除 / 覆盖 |
| 窗口关闭 | — | `IdbEngine.close` | 释放全部池 / 驱动 / 方言 |

会话状态 `ConnectionStatus(state, message)`（`DISCONNECTED` / `CONNECTING` / `CONNECTED` / `FAILED` + 说明）
回传给 `ConnectionManagerScreen`：列表项色点、连接总览面板的状态行与「连接」/「断开」按钮的可用性都由它驱动。

### 状态原子更新模式

```kotlin
data class WizardState(
    val editingConnection: ConnectionConfig?,
    val step: WizardStep,
    val flow: WizardFlow,
) {
    companion object { val Idle = WizardState(null, WizardStep.IDLE, WizardFlow.NORMAL) }
}

// 边缘 —— 分两次赋值（崩溃风险：recomposition 间隙 editingConnection 为 null）
editingConnection = newCfg
step = WizardStep.QUICK_CONNECT

// 边缘 —— 单次赋值（安全）
wizard = WizardState(editingConnection = newCfg, step = WizardStep.QUICK_CONNECT, flow = WizardFlow.QUICK_CONNECT)
```

### 关键实现

```kotlin
// ConnectionSession.kt
class ConnectionSession(private val engine: IdbEngine, private val scope: CoroutineScope) {
    var connectionList by mutableStateOf(ConnectionStorage.load()); private set
    var selectedConnection by mutableStateOf<ConnectionConfig?>(null); private set
    var wizard by mutableStateOf(WizardState.Idle); private set
    var statuses by mutableStateOf<Map<String, ConnectionStatus>>(emptyMap()); private set

    fun connect(config: ConnectionConfig) {
        statuses = statuses + (config.id to ConnectionStatus(ConnectionState.CONNECTING))
        scope.launch {
            val status = runCatching { engine.testConnection(engineConfig(config)) }.fold(
                onSuccess = { resp ->
                    if (resp.ok) ConnectionStatus(ConnectionState.CONNECTED, resp.driver)
                    else ConnectionStatus(ConnectionState.FAILED, resp.error.ifBlank { "连接失败" })
                },
                onFailure = { ConnectionStatus(ConnectionState.FAILED, it.message ?: "Unknown error") },
            )
            statuses = statuses + (config.id to status)
        }
    }

    fun disconnect(config: ConnectionConfig) {
        scope.launch {
            runCatching { engine.disconnect(engineConfig(config)) }
            statuses = statuses + (config.id to ConnectionStatus())
        }
    }

    suspend fun testConnection(config: ConnectionConfig): TestResult { /* 建池 + 校验 + 同步状态 */ }

    fun save(config: ConnectionConfig) {
        // 编辑已有连接且字段变化 → 旧配置的连接池先释放（池按字段 hash 缓存，否则成为僵尸池）
        connectionList.connections.find { it.id == config.id }?.takeIf { it != config }?.let { disconnect(it) }
        connectionList = ConnectionStorage.upsert(config)
        selectedConnection = config
        wizard = WizardState.Idle
    }
}

/** UI 配置 → proto：只传 jdbcUrl + 凭据，方言由 URL scheme 反查 */
private fun engineConfig(config: ConnectionConfig) = connectionConfig {
    jdbcUrl = config.jdbcUrl
    user = config.username
    password = config.password
}
```

`onTestConnection` / `onConnect` 是**连接初始化**的入口 —— `IdbEngine.testConnection` 旁路 gRPC 与
`RequestDispatcher`，直接调 `SystemHandler`；首次调用用 `jdbc_url` 建 HikariCP 池（这一步就是“初始化连接”），
之后按 hash key 复用；`IdbEngine.disconnect` 用同一 key 定位并关闭该配置的所有池。

### 持久化路径

```text
~/.config/sundays/connection.json   ←  ConnectionStorage.load() / upsert() / delete()
                                              ↑
                                       NORMAL 流程「保存」时调用
                                       QUICK_CONNECT 流程「连接」时不调用
```

## Direct 模式集成架构

### 核心契约

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt
import com.kxxnzstdsw.engine.IdbEngine

val engine = IdbEngine()                                          // 构造时自动 bootstrap（幂等）
Window(
    onCloseRequest = {
        engine.close()                                            // 释放 PoolManager / DriverLoader / DialectLoader
        exitApplication()
    },
) { /* Compose UI */ }
```

### 关键设计决策

#### 1. **构造即 bootstrap（幂等）**

`IdbEngine()` 构造函数内部触发 `DriverLoader` + `DialectLoader` 的加载。**重复构造是幂等的**（`bootstrap` 用 `AtomicBoolean` 单例保护），所以桌面应用启动时调用一次即可，无需担心后续 ViewModel 多次持有引用导致重复加载。

`DialectLoader` 有两条方言来源，桌面应用走**第一条**：

1. **应用类路径 SPI**（`ServiceLoader<DatabaseDialect>`，使用 `DialectLoader` 自身类加载器）—— 方言插件作为普通依赖随应用类路径加载（`desktopApp` 的 `runtimeOnly(project(":dialect-*"))`），Direct 模式下 UI 与引擎同 JVM，接口类型必须由同一类加载器解析
2. **插件目录**（`dialects/` 目录 + JAR 同级 `dialects/`，`URLClassLoader`）—— JAR 分发场景；同名方言覆盖类路径版本

启动日志可确认：`Registered 5 dialect plugin(s) from application classpath` → `IdbEngine bootstrap complete`。JDBC 驱动同样以 `runtimeOnly` 上到应用类路径（HikariCP 按 `driverClassName` 直接实例化）。

#### 2. **不启动子进程、不走 gRPC**

`desktopApp/build.gradle.kts` 显式声明：

```kotlin
implementation(project(":engine"))    // 直接依赖，无 IPC transport 依赖
// :engine 以 implementation 声明 protobuf/grpc，不传递给消费方编译类路径 —— 集成层
// 需要 typed proto 类型（ConnectionConfig / SystemTestConnectionResponse / Response）才能
// 调用 facade 的 Direct 模式 API，因此显式补齐：
implementation(libs.protobuf.java)
implementation(libs.protobuf.kotlin.lite)
```

引擎作为**库**被引入，与 Compose UI **共享同一个 JVM、同一组类加载器、同一个 GC 堆**。调用方只需：

```kotlin
val resp: Response = engine.invoke(connectionConfig { ... }) {
    category = Category.SCHEMA
    action = Action.LIST
    schemaRequest = schemaRequest {
        list = schemaListRequest { level = "database" }
    }
}
return resp.schema.list.itemsList    // typed accessor —— 无 JSON 解析
```

或流式：

```kotlin
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

`engine.handle()` 返回 `Flow<Response>` 与 `gRPC IdbEngineCoroutineStub.handle()` **类型完全一致**，可以直接替换。

#### 3. **生命周期 = Window 生命周期**

| 操作 | gRPC 模式（独立子进程） | Direct 模式（library 集成） |
|---|---|---|
| 启动 | 父进程拉起 `java -jar idb-engine.jar` | 父 JVM 构造 `IdbEngine()` |
| 资源持有 | 子进程独立内存 | 父 JVM 共享类加载器 / 连接池 / 驱动 |
| 关闭清理 | 子进程 `destroy()` + JVM Shutdown Hook 调 `transport.cleanup()` + `PoolManager.closeAll()` + `Loader.closeAll()` | **仅需** 在 `Window.onCloseRequest` 中调 `engine.close()` —— **不需要** Shutdown Hook（library 不是独立进程，JVM 退出时 GC 自然回收） |
| 调试 | 跨进程 attach | 直接 IDE debug |

> **关键洞察**：Direct 模式下，**`engine.close()` 不再依赖 JVM Shutdown Hook 兜底** —— 因为 `engine` 不是独立进程的入口，而是 JVM 内的一个 library 对象。其生命周期完全由 Compose UI 的 `Window.onCloseRequest` 控制；JVM 退出后 GC 自然回收所有未显式关闭的资源（连接池、驱动、方言 SPI 实例）。

---

## 生命周期管理

### Window 关闭序列

```kotlin
Window(
    onCloseRequest = {
        engine.close()                                             // ① 释放引擎资源（全部连接池 / 驱动 / 方言）
        exitApplication()                                           // ② 退出 application{}
    },
    title = "sundays",
) { ... }
```

**两步清理顺序**：

1. **`engine.close()`** — `IdbEngine` facade 内部调用 `PoolManager.closeAll()`（关闭该应用建立的所有 HikariCP 连接池）+ `DriverLoader.closeAll()`（卸载所有 JDBC 驱动）+ `DialectLoader.closeAll()`（关闭所有方言 SPI 实例）
2. **`exitApplication()`** — Compose Desktop `application{}` 的退出点，触发所有 `Window` 的销毁

> 协程侧无需额外清理：`ConnectionSession` 的 `connect` / `disconnect` 跑在 `rememberCoroutineScope()` 上，
> 随组合销毁自动取消；引擎调用本身旁路 RPC，不持有长驻后台任务。

### 为什么不需要 Shutdown Hook

| 场景 | gRPC 模式（独立进程） | Direct 模式（library 集成） |
|---|---|---|
| 引擎进程边界 | `java -jar idb-engine.jar` 是独立 JVM | `:engine` 是 JVM 内的 library，无独立进程边界 |
| 关闭路径 | `Ctrl+C` → JVM Shutdown Hook → `transport.cleanup()` + `PoolManager.closeAll()` + ... | `Window.onCloseRequest` → `engine.close()`（**显式**） |
| 异常退出保护 | 必需 —— 否则子进程独立存活，连接池/驱动泄漏 | **不必要** —— JVM 退出后所有 library 对象随 GC 回收；HikariCP 在 `finalize()` 中也会兜底关闭 |

> **设计哲学**：Direct 模式下，引擎生命周期**完全受 UI 控制**。`onCloseRequest` 是**唯一**的清理入口；不需要 JVM Shutdown Hook 兜底，因为 library 不是独立进程。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`desktopApp/README.md`](./README.md) | desktopApp 用户级 README（运行命令 / 演示功能 / Direct 模式概述） |
| [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | V2.9 完整架构设计文档（gRPC 协议 / handler 矩阵 / 方言特性 / 双模式架构） |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade 详解 / `RequestDispatcher` / `PoolManager` / `Loader`） |
| [`engine/README.md`](../engine/README.md) | 引擎用户级 README（CLI / 构建运行 / API 参考 / Direct 模式示例） |
| [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | 共享 UI 组件架构（`CodeEditor` / `DataTable` / `ConnectionManagerScreen` 含 JDBC URL 折算与连接总览 / 右键菜单） |

---

## 后续迭代方向（v2.13+）

- **真正的数据库管理 UI**：Schema 导航（基于连接池后的 `SCHEMA.LIST`）/ SQL 编辑器面板（嵌入 `CodeEditor`）/ 查询结果表（嵌入 `DataTable`）；连接表单可进一步改为按 `SYSTEM.LIST_DRIVERS` 的 `DialectInfo` 动态渲染
- **连接重连与会话信息**：总览面板展示 `SYSTEM.SERVER_INFO`（版本 / 模式）；断线自动重连
- **多 Window 支持**：当前 `main()` 仅创建单个 `Window`；后续按需支持多 Window（每个连接一个 Window）
- **KMP 平台扩展**：新增 `androidMain` / `iosMain` / `wasmJsMain` source set（共享 `commonMain` 业务层）
- **设置持久化**：编辑器偏好等非连接配置（KMP `MultiplatformSettings`）