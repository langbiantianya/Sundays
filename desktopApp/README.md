# desktopApp — KMP Compose Desktop 客户端

`desktopApp/` 是 `sundays` 项目的 **前端模块**：使用 **Kotlin Multiplatform + Compose Multiplatform Desktop** 编写的桌面应用（当前启用 **JVM Desktop** 单平台目标，macOS / Linux / Windows 三端共享同一份 Compose Desktop 渲染）。

它通过 **v2.9 Direct 直接模式** 与 `engine/` 模块集成 —— `IdbEngine()` facade 直接方法调用引擎，**不启动子进程、不建立 gRPC channel、不走 IPC transport**，typed proto 消息在同一 JVM 内直传，零序列化、零桥接开销。

> **当前版本：v2.9** — KMP Desktop 前端 + Direct 模式
> 详细架构设计见本目录的 [`../ARCHITECTURE.md`](./ARCHITECTURE.md)；整体项目架构见 [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md)；引擎文档见 [`engine/README.md`](../engine/README.md)；共享 UI 组件见 [`shared/`](../shared/) 模块。

---

## 快速运行

```bash
# Hot reload 开发模式（推荐 —— 修改代码自动重启）
./gradlew :desktopApp:hotRun --auto

# 普通运行
./gradlew :desktopApp:run

# 打包发布版（生成 .dmg / .msi / .deb 三种原生安装包）
./gradlew :desktopApp:distributable
```

打包产物位于 `desktopApp/build/compose/binaries/`（按目标平台分子目录：`dmg/`、`msi/`、`deb/`）。

> **前置条件**：JDK 25、Kotlin 2.4.0、Compose Multiplatform 插件已就绪。`./gradlew :desktopApp:run` 会自动编译 `engine/`、`shared/` 模块及其方言插件（MySQL / PostgreSQL / H2 / DuckDB / SQLite），无需手动构建引擎。

---

## 演示功能

应用启动后顶层为一个 `SingleChoiceSegmentedButtonRow` 顶栏，提供两个演示 Tab 切换：

### Tab 1 — "代码编辑器"（演示 `CodeEditor` 组件）

- **SQL / Lua 双语言切换**：`CodeLanguageRegistry` 注册的内置语法高亮
- **行号 gutter**：与编辑区共用 `ScrollState`，滚动完全同步
- **工具栏插槽**：演示 `RowScope.() -> Unit` 注入（格式化按钮、语言切换下拉框等）
- **格式化**：`CodeFormatterRegistry` 自动应用（SQL 关键字大写化等）
- **右键菜单**：编辑器内置 copy / cut / paste / 全选等默认菜单项

演示数据：
```sql
SELECT id, name, email FROM users WHERE created_at > '2024-01-01' ORDER BY id DESC LIMIT 100
```
```lua
for i = 1, 100 do
  insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65)})
end
```

> **来源**：[`shared/`](../shared/) 模块的 `editor/` 子包；具体 API 见 [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md)。

### Tab 2 — "数据表格"（演示 `DataTable` 组件）

- **1000 行模拟用户数据**：`generateDemoUsers(count = 1000)` 生成的虚拟数据集
- **5 列**：`id` (Long, 主键) / `name` (String) / `email` (String) / `age` (Int) / `active` (Boolean)
- **虚拟滚动**：基于 `LazyColumn` 实现，item key = 主键，仅渲染可视区行
- **行号 / 主键**：第一列作为主键列显示
- **分页**：`PageSize` 枚举切换（S10 / S20 / S50 / S100 / S200 / S300 / S500 / ALL）
- **单行详情面板**：点击行 → 右侧详情面板
- **单元格可选中**：每行包裹 `SelectionContainer`，可在单元格内拖拽选中
- **右键菜单**（调用方注入）：
  - "复制主键 `<id>`"
  - "标记为已读"
  - "删除" —— 演示 **databind 自动重绘**（从 `rows` state 移除该行后，UI 自动更新）
- **状态格式化**：`active` 列通过 `formatter = { if (it == true) "✓" else "✗" }` 自定义渲染

> **来源**：[`shared/`](../shared/) 模块的 `table/` 子包。

---

## Direct 模式集成

`desktopApp/build.gradle.kts` 声明 `implementation(project(":engine"))`，把引擎作为**库**依赖引入：

```kotlin
// desktopApp/build.gradle.kts
dependencies {
    implementation(project(":shared"))
    // 直接模式：Compose UI 与引擎同 JVM，通过 IdbEngine facade 直接调用
    // 不走 gRPC / 子进程 / IPC transport —— 详见 engine/README.md §Dual-Mode Architecture
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)
}
```

启动流程（`main.kt`）：

```kotlin
fun main() = application {
    registerBuiltinEditors()                                       // 注册 SQL/Lua 编辑器
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = IdbEngine()                                       // 构造时自动 bootstrap（幂等）
    Window(
        onCloseRequest = {
            engine.close()                                         // 释放 PoolManager / DriverLoader / DialectLoader
            engineScope.coroutineContext[Job]?.cancel()
            exitApplication()
        },
        title = "sundays",
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            DemoApp()
        }
    }
}
```

**对比 gRPC 模式**：

| 维度 | Direct 模式（本应用使用） | gRPC 模式 |
|---|---|---|
| 进程模型 | 同 JVM | 独立子进程（`java -jar idb-engine.jar`） |
| 通信开销 | 0（typed 方法调用） | gRPC HTTP/2 + Protobuf + IPC transport |
| 启动延迟 | 0（构造即用） | ~50ms（JVM 启动 + 驱动加载 + server bind） |
| 跨语言 | ✗（仅 Kotlin/JVM） | ✓（Go / Python / 任何 gRPC 客户端） |
| 调试 | 直接 IDE 调试 | 跨进程 attach |
| 适用场景 | **KMP Compose Desktop**（首选） | 跨进程 / 跨语言 / 远程 / 子进程隔离 |

两种模式共享同一个 `RequestDispatcher`，envelope options（`traceId` / `dryRun` / `timeoutMs`）、流式 frame assembly、`if_exists` 语义完全一致 —— 详细对比见 [根目录 `README.md` §两种模式的对比](../README.md#两种模式的对比)。

---

## 演示截图位置

```text
待补充 —— 当前 desktopApp 的 demo 屏幕可视化展示
```

> **注意**：当前桌面截图尚未归档。如需 GUI 视觉示例，可通过 `./gradlew :desktopApp:run` 启动后手动截取。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`desktopApp/ARCHITECTURE.md`](./ARCHITECTURE.md) | desktopApp 内部架构（KMP 工程结构 / Direct 模式集成 / 演示屏幕设计 / 生命周期管理） |
| [根目录 `README.md`](../README.md) | 项目总览、模块结构、Direct 模式详解、运行命令 |
| [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | V2.9 完整架构设计文档（gRPC 协议 / handler 矩阵 / 方言特性 / 双模式架构） |
| [`engine/README.md`](../engine/README.md) | 引擎模块详细 README（CLI / 构建 / handler 路由 / API 参考） |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade 详解 / Dispatcher / Pool / Loader） |
| [`shared/`](../shared/) | KMP 共享代码（`CodeEditor` / `DataTable` / 右键菜单） |

---

## 技术栈

- **Kotlin 2.4.0 / JDK 25**
- **Kotlin Multiplatform** + **Compose Multiplatform Desktop**（`jvmMain` 单平台目标）
- **Material 3** 组件库（`androidx.compose.material3`）
- **kotlinx-coroutines 1.11.0**（含 `coroutines-swing` 用于协程 UI dispatch）
- **`:engine` 模块**（Direct 模式依赖）+ **`:shared` 模块**（CodeEditor / DataTable 组件）

详细依赖见 [`desktopApp/build.gradle.kts`](./build.gradle.kts) 与根目录 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。