# desktopApp — KMP Compose Desktop 客户端内部架构（v2.10）

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
├── build.gradle.kts    # composeMultiplatform + compose.material3 + :engine / :shared 依赖
└── src/main/kotlin/com/kxxnzstdsw/sundays/
    └── main.kt         # 单一入口：main() + MainScreen() + WizardState + isSystemInDarkTheme()
```

**文件清单**：

| 文件 | 行数 | 职责 |
|---|---|---|
| `build.gradle.kts` | 31 | 声明 `kotlinJvm` / `composeMultiplatform` / `composeCompiler` 插件；`:engine` / `:shared` 依赖；原生分发目标 `Dmg` + `Msi` + `Deb` |
| `main.kt` | 158 | 应用入口（`application { Window { MainScreen() } }`）；`MainScreen` 维护 `WizardState` + `connectionList` + `selectedConnection` state；实现 `onSaveConnection` / `onQuickConnectDirect` / `onDeleteConnection` / `onUpdateEditingConnection` 等回调 |

**`main.kt` 内符号分解**（自顶向下）：

| 符号 | 可见性 | 职责 |
|---|---|---|
| `main()` | public | `application { ... }` 入口；构造 `IdbEngine()` + `engineScope`、创建 `Window`、安装 `MaterialTheme`、渲染 `MainScreen` |
| `MainScreen()` | private `@Composable` | 顶层屏幕：维护 `WizardState(editingConnection, wizardStep, flow)` + `connectionList` + `selectedConnection`，实现所有 `ConnectionManagerScreen` 回调，调用 `ConnectionManagerScreen` |
| `WizardState` | private `data class` | 三字段原子更新容器：`ConnectionConfig?` + `WizardStep` + `WizardFlow` |
| `isSystemInDarkTheme()` | private `@Composable` | 包装 `androidx.compose.foundation.isSystemInDarkTheme()`（避免导入冲突） |

---

## 连接管理演示 (`MainScreen`)

演示 `shared/connection/ConnectionManagerScreen` 的端到端用法：

- 从 `~/.config/sundays/connection.json` 加载连接列表（`ConnectionStorage.load()`）
- 维护 `WizardState`(editingConnection + wizardStep + flow) **data class** —— 单次赋值保证原子更新，避免 Compose recomposition 间隙 NPE
- 三个入口：
  - **左侧「快速连接」按钮**（⚡）→ `flow = QUICK_CONNECT`
  - **左侧「新建连接」按钮**（＋）→ `flow = NORMAL`，起始 `BASIC_INFO`
  - **列表项「编辑」菜单** → `flow = NORMAL`，起始 `BASIC_INFO`（保留原有配置）

### 流程对照表

| 入口 | flow | 步骤序列 | 最后一步 |
|---|---|---|---|
| 新建 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → `ConnectionStorage.upsert()` |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT → CREDENTIALS → TEST_SAVE` (3 步，跳过 BASIC_INFO / CONNECTION_TYPE) | 「连接」 → **仅设置 `selectedConnection`，不写入** `ConnectionStorage` |
| 编辑 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → 覆盖原配置 |

调用方在每次切换入口时**同步**设置 `flow`，确保 `ConnectionWizardPanel` 的步骤指示器自适应总数（4 vs 3）。

### 持久化路径

```text
~/.config/sundays/connection.json   ←  ConnectionStorage.load() / upsert() / delete()
                                              ↑
                                       NORMAL 流程「保存」时调用
                                       QUICK_CONNECT 流程「连接」时不调用
```

### 状态原子更新模式

```kotlin
private data class WizardState(
    val editingConnection: ConnectionConfig?,
    val wizardStep: WizardStep,
    val flow: WizardFlow,
)

// 边缘 —— 分两次赋值（崩溃风险：recomposition 间隙 editingConnection 为 null）
editingConnection = newCfg
wizardStep = WizardStep.QUICK_CONNECT

// 边缘 —— 单次赋值（安全）
wizardState = WizardState(editingConnection = newCfg, wizardStep = WizardStep.QUICK_CONNECT, flow = WizardFlow.QUICK_CONNECT)
```

### 关键回调实现

```kotlin
ConnectionManagerScreen(
        // ...
        onSaveConnection = { config ->
            connectionList = ConnectionStorage.upsert(config)
            selectedConnection = config
            wizardState = WizardState(null, WizardStep.IDLE, WizardFlow.NORMAL)
        },
        onQuickConnectDirect = { config ->
            // 快速连接：不写入 ConnectionStorage，仅设为当前选中
            selectedConnection = config
            wizardState = WizardState(null, WizardStep.IDLE, WizardFlow.NORMAL)
        },
        onUpdateEditingConnection = { config ->
            wizardState = wizardState.copy(editingConnection = config)
        },
        // ...
    )
```

`onSaveConnection` 与 `onQuickConnectDirect` 区别在于是否持久化：前者走 JSON 持久化，后者仅在内存中设为 `selectedConnection`（适合临时调试、演示场景）。

---

## Direct 模式集成架构

### 核心契约

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt
import com.kxxnzstdsw.engine.IdbEngine

val engine = IdbEngine()                                          // 构造时自动 bootstrap（幂等）
Window(
    onCloseRequest = {
        engine.close()                                            // 释放 PoolManager / DriverLoader / DialectLoader
        engineScope.coroutineContext[Job]?.cancel()
        exitApplication()
    },
) { /* Compose UI */ }
```

### 关键设计决策

#### 1. **构造即 bootstrap（幂等）**

`IdbEngine()` 构造函数内部触发 `DriverLoader` + `DialectLoader` 的 `ServiceLoader` 扫描。**重复构造是幂等的**（内部用 `lazy` / `synchronized` 单例保护），所以桌面应用启动时调用一次即可，无需担心后续 ViewModel 多次持有引用导致重复加载。

#### 2. **不启动子进程、不走 gRPC**

`desktopApp/build.gradle.kts` 显式声明：

```kotlin
implementation(project(":engine"))    // 直接依赖，无 IPC transport 依赖
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
        engine.close()                                             // ① 释放引擎资源
        engineScope.coroutineContext[Job]?.cancel()                 // ② 取消应用级 coroutine scope
        exitApplication()                                           // ③ 退出 application{}
    },
    title = "sundays",
) { ... }
```

**三步清理顺序**：

1. **`engine.close()`** — `IdbEngine` facade 内部调用 `PoolManager.closeAll()`（关闭所有 HikariCP 连接池）+ `DriverLoader.closeAll()`（卸载所有 JDBC 驱动）+ `DialectLoader.closeAll()`（关闭所有方言 SPI 实例）
2. **`engineScope.coroutineContext[Job]?.cancel()`** — 取消应用启动时创建的 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，阻止未完成的 ViewModel 协程继续运行（防止关闭后仍有后台连接泄漏）
3. **`exitApplication()`** — Compose Desktop `application{}` 的退出点，触发所有 `Window` 的销毁

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
| [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | 共享 UI 组件架构（`CodeEditor` / `DataTable` / `ConnectionManagerScreen` 含 JDBC URL 双向同步 / 右键菜单） |

---

## 后续迭代方向（v2.11+）

- **真正的数据库管理 UI**：连接面板（基于 `SYSTEM.LIST_DRIVERS` 动态渲染）/ Schema 导航 / SQL 编辑器面板（嵌入 `CodeEditor`）/ 查询结果表（嵌入 `DataTable`）
- **多 Window 支持**：当前 `main()` 仅创建单个 `Window`；后续按需支持多 Window（每个连接一个 Window）
- **KMP 平台扩展**：新增 `androidMain` / `iosMain` / `wasmJsMain` source set（共享 `commonMain` 业务层）
- **设置持久化**：连接列表 / 编辑器偏好（KMP `MultiplatformSettings`）