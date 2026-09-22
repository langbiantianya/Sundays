# desktopApp — KMP Compose Desktop 客户端内部架构（v2.9）

## 概述

`desktopApp/` 是 `sundays` 项目的**桌面客户端模块**。它使用 **Kotlin Multiplatform + Compose Multiplatform** 构建，**当前仅启用 JVM Desktop 单平台目标**（macOS / Linux / Windows 三端共享同一份 Compose Desktop (Skia) 渲染），通过 **v2.9 Direct 直接模式** 与引擎集成 —— `IdbEngine()` facade 直接方法调用，**typed proto 消息同 JVM 直传，零序列化、零子进程、零 gRPC channel、零 IPC transport**。

**未来扩展路径**：KMP 工程结构天然支持后续启用 `androidMain` / `iosMain` / `wasmJsMain` source set —— 只需新增对应平台特定的子进程拉起逻辑（如 Android 的 `bindService`、iOS 的 `NSXPCConnection`），`commonMain` 中的业务层零修改复用。当前 v2.9 demo 阶段仅暴露 `main` 单一 source set。

**关键设计原则**：

- **依赖方向**：`desktopApp` → `:shared`（UI 组件）+ `:engine`（业务引擎）。**反向依赖被严格禁止** —— 引擎与 shared 模块均不感知 desktopApp 存在。
- **同进程集成**：`desktopApp` 与 `:engine` **必须部署在同一 JVM**（Kotlin / Java），Direct 模式无 IPC 跨进程语义。
- **演示优先**：当前 `main.kt` 仅承载 **三个演示屏幕**（连接管理 + 代码编辑器 + 数据表格），用于验证 `shared/` UI 组件在 desktopApp 中的接线方式；真正的数据库管理 UI（连接面板 / Schema 导航 / SQL 编辑器面板 / 查询结果表）将由后续迭代替换。

---

## 工程结构

```text
desktopApp/
├── build.gradle.kts    # composeMultiplatform + compose.material3 + :engine / :shared 依赖
└── src/main/kotlin/com/kxxnzstdsw/sundays/
    └── main.kt         # 单一入口：main() + DemoApp() + DemoTabBar() + EditorDemoScreen() + TableDemoScreen()
```

**文件清单**：

| 文件 | 行数 | 职责 |
|---|---|---|
| `build.gradle.kts` | 31 | 声明 `kotlinJvm` / `composeMultiplatform` / `composeCompiler` 插件；`:engine` / `:shared` 依赖；原生分发目标 `Dmg` + `Msi` + `Deb` |
| `main.kt` | 261 | 应用入口（`application { Window { DemoApp() } }`）；三个 demo 屏幕实现；演示数据生成函数 |

**`main.kt` 内符号分解**（自顶向下）：

| 符号 | 可见性 | 职责 |
|---|---|---|
| `main()` | public | `application { ... }` 入口；注册内置编辑器、构造 `IdbEngine()`、创建 `Window`、安装主题 |
| `DemoApp()` | private `@Composable` | 顶层 demo 应用：管理 `selectedTab` state、`DemoTabBar` + `when (selectedTab)` 切换 |
| `DemoTab` | private enum | tab 枚举（`CONNECTION` / `EDITOR` / `TABLE`） |
| `DemoTabBar()` | private `@Composable` | 顶部 `SingleChoiceSegmentedButtonRow` 渲染 |
| `isSystemInDarkTheme()` | private `@Composable` | 包装 `androidx.compose.foundation.isSystemInDarkTheme()`（避免导入冲突） |
| `ConnectionDemoScreen()` | private `@Composable` | 连接管理演示：加载 `~/.config/sundays/connection.json`，维护 `WizardState`(editingConnection + wizardStep + flow)，调用 `ConnectionManagerScreen` |
| `EditorDemoScreen()` | private `@Composable` | 代码编辑器演示：`sqlText` / `luaText` / `currentLang` 三个 state，`when (currentLang)` 切换两套 `CodeEditorWithToolbar` |
| `TableDemoScreen()` | private `@Composable` | 数据表格演示：`rows` / `pageSize` / `currentPage` state、列定义、`DataTable` + 注入 `contextMenuItems` |
| `generateDemoUsers(count)` | private | 生成 1000 行模拟用户数据（id / name / email / age / active） |

---

## 连接管理演示 (`ConnectionDemoScreen`)

演示 `shared/connection/ConnectionManagerScreen` 的端到端用法：

- 从 `~/.config/sundays/connection.json` 加载连接列表
- 维护 `WizardState`(editingConnection + wizardStep + flow) **data class** —— 单次赋值保证原子更新，避免 Compose recomposition 间隙 NPE
- 三个入口：
  - **左侧「快速连接」按钮**（⚡）→ `flow = QUICK_CONNECT`
  - **左侧「新建连接」按钮**（＋）→ `flow = NORMAL`，起始 `BASIC_INFO`
  - **列表项「编辑」菜单** → `flow = NORMAL`，起始 `BASIC_INFO`（保留原有配置）

### 流程对照表

| 入口 | flow | 步骤序列 |
|---|---|---|
| 新建 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT → CREDENTIALS → TEST_SAVE` (3 步，跳过 BASIC_INFO / CONNECTION_TYPE) |
| 编辑 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) |

调用方在每次切换入口时**同步**设置 `flow`，确保 `ConnectionWizardPanel` 的步骤指示器自适应总数（4 vs 3）。

### 持久化路径

```text
~/.config/sundays/connection.json   ←  ConnectionStorage.load() / upsert() / delete()
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

## Demo 屏幕设计

### 顶层 `DemoApp`

```kotlin
@Composable
private fun DemoApp() {
    var selectedTab by remember { mutableStateOf(DemoTab.EDITOR) }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DemoTabBar(selected = selectedTab, onSelect = { selectedTab = it })
        when (selectedTab) {
            DemoTab.EDITOR -> EditorDemoScreen()
            DemoTab.TABLE  -> TableDemoScreen()
        }
    }
}
```

- **顶部 `SingleChoiceSegmentedButtonRow`**：`DemoTab.entries.forEachIndexed { ... SegmentedButton(...) }`，通过 `SegmentedButtonDefaults.itemShape(index, total)` 自动渲染首尾圆角、中段方角
- **下方 `when`**：根据 `selectedTab` 渲染对应 demo 屏幕
- **整体布局**：`Column.fillMaxSize().safeContentPadding().padding(16.dp)` + `Arrangement.spacedBy(12.dp)` —— 顶部 tab 与下方 demo 屏之间留 12dp 间隙

### `EditorDemoScreen` — 代码编辑器演示

**演示目的**：验证 `shared/editor/` 中 `CodeEditor` / `CodeEditorWithToolbar` 在桌面端的接线方式（语言切换、格式化、右键菜单、行号 gutter、工具栏插槽）。

```kotlin
@Composable
private fun EditorDemoScreen() {
    var sqlText by remember { mutableStateOf("SELECT id, name, ... LIMIT 100") }
    var luaText by remember { mutableStateOf("for i = 1, 100 do ... end") }
    var currentLang by remember { mutableStateOf("sql") }

    if (currentLang == "sql") {
        CodeEditorWithToolbar(
            text = sqlText,
            onTextChange = { sqlText = it },
            languageId = "sql",
            onLanguageChange = { currentLang = it },
        )
    } else {
        CodeEditorWithToolbar(
            text = luaText,
            onTextChange = { luaText = it },
            languageId = "lua",
            onLanguageChange = { currentLang = it },
        )
    }
}
```

**设计要点**：

- **三个独立 state**：`sqlText`（SQL 编辑内容）、`luaText`（Lua 编辑内容）、`currentLang`（当前语言 id）。SQL 与 Lua 内容分离 —— 切换语言时不会丢失另一语言的编辑历史
- **`when (currentLang)` 而非 `when` 表达式**：直接用 `if/else` + 渲染同一组件，避免 `CodeEditorWithToolbar` 在不同调用栈帧被 Compose 重组（保留各自的 state）
- **预填示例数据**：分别对应"SQL 编辑器面板"和"Lua 造数脚本编辑器"的典型使用场景
- **回调契约**：`onTextChange = { sqlText = it }` 把组件内部变化桥接到外层 state（**databind** 模式）

### `TableDemoScreen` — 数据表格演示

**演示目的**：验证 `shared/table/` 中 `DataTable` 在桌面端的接线方式（虚拟滚动、分页、详情面板、右键菜单、databind 重绘）。

```kotlin
@Composable
private fun TableDemoScreen() {
    // 模拟 1000 行数据库用户数据 —— 真实场景下 rows 由 engine 查询结果驱动
    var rows by remember { mutableStateOf(generateDemoUsers(count = 1000)) }
    var pageSize by remember { mutableStateOf(PageSize.S50) }
    var currentPage by remember { mutableStateOf(1) }
    val contextMenuState = rememberContextMenuState()

    val columns = remember {
        listOf(
            TableColumn(key = "id",     header = "ID",   width = 80.dp, alignment = TextAlign.End),
            TableColumn(key = "name",   header = "姓名"),
            TableColumn(key = "email",  header = "邮箱"),
            TableColumn(key = "age",    header = "年龄", width = 80.dp, alignment = TextAlign.End),
            TableColumn(key = "active", header = "状态", width = 80.dp, alignment = TextAlign.Center,
                        formatter = { if (it == true) "✓" else "✗" }),
        )
    }

    DataTable(
        columns = columns,
        rows = rows,
        theme = DataTableTheme.default(),
        pageSize = pageSize,
        onPageSizeChange = { pageSize = it; currentPage = 1 },
        currentPage = currentPage,
        onPageChange = { currentPage = it },
        totalCount = rows.size,
        contextMenuState = contextMenuState,
        contextMenuItems = { row ->
            DropdownMenuItem(text = { Text("复制主键 ${row?.id ?: ""}") }, onClick = { /* copyToClipboard */ })
            DropdownMenuItem(text = { Text("标记为已读") },                onClick = { /* ... */ })
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    // databind 演示：从 rows 中删除该行，UI 自动重绘
                    row?.let { r -> rows = rows.filter { it.id != r.id } }
                },
            )
        },
    )
}
```

**设计要点**：

- **三个 state**：`rows`（模拟数据）、`pageSize`（分页大小）、`currentPage`（当前页）
- **列定义 `remember`**：5 列定义包在 `remember { ... }` 中避免每次重组重建列表（性能优化 + Compose 跳过 key 匹配的稳定性）
- **`formatter` 插槽**：`active` 列通过 `{ if (it == true) "✓" else "✗" }` 把 `Boolean` 渲染为可视化符号
- **右键菜单注入**：调用方通过 `contextMenuItems = { row -> ... }` 插槽注入 3 个 `DropdownMenuItem`。**"删除" 演示 databind 自动重绘** —— 修改 `rows` state → `DataTable` 内部 `LazyColumn` 自动重排
- **真实场景替换**：`generateDemoUsers(count = 1000)` 后续会被 `engine.query("SELECT * FROM users")` 的结果（typed `TableRow` 列表）替换

**`generateDemoUsers` 函数**：

```kotlin
private fun generateDemoUsers(count: Int): List<TableRow> =
    (1..count).map { i ->
        TableRow(
            id = i.toLong(),
            "id"     to i.toLong(),
            "name"   to "user_$i",
            "email"  to "user$i@example.com",
            "age"    to (18 + i % 50),
            "active" to (i % 3 != 0),
        )
    }
```

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
| [根目录 `../CLAUDE.md`](../CLAUDE.md) | V2.9 完整架构设计文档（gRPC 协议 / handler 矩阵 / 方言特性 / 双模式架构） |
| [`engine/CLAUDE.md`](../engine/CLAUDE.md) | 引擎内部架构（`IdbEngine` facade 详解 / `RequestDispatcher` / `PoolManager` / `Loader`） |
| [`engine/README.md`](../engine/README.md) | 引擎用户级 README（CLI / 构建运行 / API 参考 / Direct 模式示例） |
| [`shared/CLAUDE.md`](../shared/CLAUDE.md) | 共享 UI 组件架构（`CodeEditor` / `DataTable` / 右键菜单） |

---

## 后续迭代方向（v2.10+）

- **真正的数据库管理 UI**：连接面板（基于 `SYSTEM.LIST_DRIVERS` 动态渲染）/ Schema 导航 / SQL 编辑器面板（替换 `EditorDemoScreen`）/ 查询结果表（替换 `TableDemoScreen`）
- **多 Window 支持**：当前 `main()` 仅创建单个 `Window`；后续按需支持多 Window（每个连接一个 Window）
- **KMP 平台扩展**：新增 `androidMain` / `iosMain` / `wasmJsMain` source set（共享 `commonMain` 业务层）
- **设置持久化**：连接列表 / 编辑器偏好（KMP `MultiplatformSettings`）