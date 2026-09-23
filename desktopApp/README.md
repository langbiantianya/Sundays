# desktopApp — KMP Compose Desktop 客户端

`desktopApp/` 是 `sundays` 项目的 **前端模块**：使用 **Kotlin Multiplatform + Compose Multiplatform Desktop** 编写的桌面应用（当前启用 **JVM Desktop** 单平台目标，macOS / Linux / Windows 三端共享同一份 Compose Desktop 渲染）。

它通过 **v2.9 Direct 直接模式** 与 `engine/` 模块集成 —— `IdbEngine()` facade 直接方法调用引擎，**不启动子进程、不建立 gRPC channel、不走 IPC transport**，typed proto 消息在同一 JVM 内直传，零序列化、零桥接开销。

> **当前版本：v2.12** — KMP Desktop 前端 + Direct 模式 + 连接管理（连接生命周期直连引擎）
> 详细架构设计见本目录的 [`./ARCHITECTURE.md`](./ARCHITECTURE.md)；整体项目架构见 [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md)；引擎文档见 [`engine/README.md`](../engine/README.md)；共享 UI 组件见 [`shared/`](../shared/) 模块。

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

```bash
# 连接管理流程端到端测试（Compose UI 测试 + 真引擎；H2 内存库，无需外部数据库）
./gradlew :desktopApp:test
```

`ConnectionManagerFlowTest` 以 `runComposeUiTest` 真实点击向导，并断言引擎侧效果（连接池建立 / 释放、状态流转、
`connection.json` 落盘内容）；测试把 `user.home` 指向临时目录，不触碰真实配置。

> **前置条件**：JDK 25、Kotlin 2.4.0、Compose Multiplatform 插件已就绪。`./gradlew :desktopApp:run` 会自动编译 `engine/`、`shared/` 模块及其方言插件（MySQL / PostgreSQL / H2 / DuckDB / SQLite），无需手动构建引擎。

---

## 功能：连接管理（v2.12）

应用启动后**直接渲染** `ConnectionManagerScreen` —— 左侧连接列表 + 右侧引导式配置 / 连接总览，**无顶层 Tab 切换**（v2.10 已移除演示 `DemoApp`）。

- **方言**：MySQL / PostgreSQL / H2 / DuckDB / SQLite（方言插件 + JDBC 驱动随应用类路径加载，见下）
- **两种引导流程**（`WizardFlow` 标识，步骤指示器自适应）：
  - 普通新建 / 编辑：`BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE`（4 步）
  - 快速连接：`QUICK_CONNECT → CREDENTIALS → TEST_SAVE`（3 步）
- **JDBC URL 折算（真相源）**：`CLIENT_SERVER` 显示 host / port / database / username / password + JDBC URL 输入框，双向实时映射；嵌入式方言（H2 / DuckDB / SQLite）用单一目标字段折算 URL。字段不足时「下一步」/「保存」/「连接」/「测试连接」全部禁用，保证交给引擎的配置一定有合法 URL
- **连接生命周期（直连引擎）**：
  - 「连接」→ `IdbEngine.testConnection`：按需建/复用 HikariCP 连接池 + `isValid` 校验（即**初始化连接**）
  - 「断开」/ 删除连接 → `IdbEngine.disconnect`：释放该配置的连接池；编辑已保存连接且字段变化时也会先释放旧池
  - 窗口关闭 → `IdbEngine.close()`：释放全部池 / 驱动 / 方言
  - 会话状态（未连接 / 连接中 / 已连接 / 失败）由 `ConnectionSession` 维护，回传给列表色点与连接总览
- **持久化**：`ConnectionStorage` 读写 `~/.config/sundays/connection.json`，只落盘 `id` / `name` / `dialect` / `jdbcUrl` / `username` / `password` + 时间戳；`host` / `port` / `database` / `connectionType` 在加载时由 `parseJdbcUrl` 重建（按 `version` 分派 v1 / v2，v1 自动迁移回写）
- **快速连接不持久化**：`QUICK_CONNECT` 流程最后一步是「连接」而非「保存」，仅设为当前选中并直接连库
- **测试覆盖**：`ConnectionManagerFlowTest` 用真引擎 + 真点击跑通「选方言 → 填字段 → 测试连接 → 连接 → 断开」全链路（`./gradlew :desktopApp:test`）

> **来源**：[`shared/`](../shared/) 模块的 `connection/` 子包；具体 API 见 [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) §4。
>
> `CodeEditor` / `DataTable` 组件仍在 `shared/` 中维护，待后续接入真正的 SQL 编辑器与查询结果面板。

---

## Direct 模式集成

`desktopApp/build.gradle.kts` 声明 `implementation(project(":engine"))`，把引擎作为**库**依赖引入；
方言插件与 JDBC 驱动以 `runtimeOnly` 依赖上到应用类路径 —— Direct 模式不需要外部 `dialects/` / `drivers/` 目录：

```kotlin
// desktopApp/build.gradle.kts
dependencies {
    implementation(project(":shared"))
    // 直接模式：Compose UI 与引擎同 JVM，通过 IdbEngine facade 直接调用
    // 不走 gRPC / 子进程 / IPC transport —— 详见 engine/README.md §Dual-Mode Architecture
    implementation(project(":engine"))

    // 方言插件 + JDBC 驱动随应用类路径加载：DialectLoader 先扫应用类路径 SPI（ServiceLoader），
    // 再用 dialects/ 目录覆盖同名方言。缺了这些依赖引擎解析不出任何方言
    runtimeOnly(project(":dialect-mysql"))
    runtimeOnly(project(":dialect-postgresql"))
    runtimeOnly(project(":dialect-h2"))
    runtimeOnly(project(":dialect-duckdb"))
    runtimeOnly(project(":dialect-sqlite"))
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.duckdb)
    runtimeOnly(libs.sqlite)

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    // :engine 以 implementation 声明 protobuf/grpc，不向消费方编译类路径传递 ——
    // 集成层需要 typed proto 类型才能调用 facade 的 Direct 模式 API，故显式补齐
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin.lite)

    implementation(libs.compose.uiToolingPreview)

    // 连接管理流程的端到端测试（真引擎 + 真点击）
    testImplementation(libs.compose.uiTest)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}
```

启动流程（`main.kt`）：

```kotlin
fun main() = application {
    val engine = IdbEngine()                                       // 构造时自动 bootstrap（幂等）：加载方言 + 驱动
    Window(
        onCloseRequest = {
            engine.close()                                         // 释放 PoolManager / DriverLoader / DialectLoader
            exitApplication()
        },
        title = "sundays",
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            MainScreen(engine)                                     // 绑定 ConnectionSession → ConnectionManagerScreen
        }
    }
}
```

`MainScreen` 只做绑定：`ConnectionSession`（`ConnectionSession.kt`）持有连接列表 / 向导状态 / 引擎会话状态，
并实现全部回调（`connect` / `disconnect` / `testConnection` / `save` / `delete`）—— 因此同一份状态机可在测试中直接驱动。

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

## 界面截图位置

```text
待补充 —— 当前 desktopApp 的连接管理界面可视化展示
```

> **注意**：当前界面截图尚未归档。如需 GUI 视觉示例，可通过 `./gradlew :desktopApp:run` 启动后手动截取。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`desktopApp/ARCHITECTURE.md`](./ARCHITECTURE.md) | desktopApp 内部架构（KMP 工程结构 / Direct 模式集成 / 连接管理与关键回调 / 生命周期管理） |
| [根目录 `README.md`](../README.md) | 项目总览、模块结构、Direct 模式详解、运行命令 |
| [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | V2.12 架构导航（双模式架构 / 模块结构 / 连接生命周期直连方法） |
| [`engine/README.md`](../engine/README.md) | 引擎模块详细 README（CLI / 构建 / handler 路由 / API 参考） |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade 详解 / Dispatcher / Pool / Loader） |
| [`shared/`](../shared/) | KMP 共享代码（`ConnectionManagerScreen` / `CodeEditor` / `DataTable` / 右键菜单） |

---

## 技术栈

- **Kotlin 2.4.0 / JDK 25**
- **Kotlin Multiplatform** + **Compose Multiplatform Desktop**（`jvmMain` 单平台目标）
- **Material 3** 组件库（`androidx.compose.material3`）
- **kotlinx-coroutines 1.11.0**（含 `coroutines-swing` 用于协程 UI dispatch）
- **`:engine` 模块**（Direct 模式依赖）+ **`:shared` 模块**（`ConnectionManagerScreen` / `CodeEditor` / `DataTable` 组件）
- **protobuf-java + protobuf-kotlin-lite**（消费 `:engine` 的 typed proto —— `:engine` 以 `implementation` 声明，不向消费方编译类路径传递）

详细依赖见 [`desktopApp/build.gradle.kts`](./build.gradle.kts) 与根目录 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。