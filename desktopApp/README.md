# desktopApp — KMP Compose Desktop 客户端

`desktopApp/` 是 `sundays` 项目的 **前端模块**：使用 **Kotlin Multiplatform + Compose Multiplatform Desktop** 编写的桌面应用（当前启用 **JVM Desktop** 单平台目标，macOS / Linux / Windows 三端共享同一份 Compose Desktop 渲染）。

它通过 **`EngineClient` 接口**（`engine-protocol/`，`AutoCloseable`）与引擎集成 —— UI 层只面向接口编程，具体实现在 `main.kt` 这一个装配点决定：默认是**同 JVM 的 `IdbEngine()`**（不启动子进程、不建立 gRPC channel、不走 IPC transport，typed proto 消息在同一 JVM 内直传，零序列化、零桥接开销）；设置 `-Dsundays.engine.endpoint` 后切换为**跨进程 `GrpcEngineClient`**。两种模式下 UI 代码完全相同。

> **当前版本：v2.23** — SQL 工作台 + 造数工作台 + 库表树宽度可拖拽 + SQL 可取消与结果集封顶 + 浏览屏可见连接失败原因
>
> 📖 **功能文档见 [`./FEATURES.md`](./FEATURES.md)** —— 逐条列出「现在能干什么 / 引擎有但前端没接什么 /
> 与 DataGrip、Navicat 差在哪」，以及按投入产出比排序的路线建议。
> 详细架构设计见本目录的 [`./ARCHITECTURE.md`](./ARCHITECTURE.md)；整体项目架构见 [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md)；调用层接口见 [`../engine-protocol/README.md`](../engine-protocol/README.md)；gRPC 客户端见 [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md)；引擎文档见 [`engine/README.md`](../engine/README.md)；共享 UI 组件见 [`shared/`](../shared/) 模块。

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
# 前端端到端 / UI 测试（Compose UI 测试 + 真引擎；H2 内存库，无需外部数据库）
./gradlew :desktopApp:test
```

| 测试 | 覆盖 |
|---|---|
| `EngineClientSelectionTest` | 装配点实现选择：未设 / 空白属性 → `IdbEngine`；设 `host:port` / `unix://` → `GrpcEngineClient`；畸形端点 → `IllegalArgumentException`（不静默回落到本地引擎） |
| `ConnectionManagerFlowTest` | 真实点击向导；断言引擎侧效果（连接池建立 / 释放、状态流转、`connection.json` 落盘内容） |
| `DatabaseBrowserFlowTest` | 浏览状态机：拉库列表 → 展开取表 → 打开预览（行数据核对）→ 标签页去重 → `closeTab` 选中回退 |
| `DatabaseBrowserUiTest` | 真实双击表名 → 预览标签页出现且 `Role=TAB` 节点数恒为 1（重复双击不新增） |
| `MainScreenNavTest` | 顶层导航：默认落在连接管理，点击「数据库浏览」切到第二屏 |
| `DialectNameContractTest` | `DialectType.engineDriverName` 与引擎 `SYSTEM.LIST_DRIVERS` 注册名逐一比对（防 `MYSQL` vs `Mysql` 这类漂移） |

共 18 个测试（`./gradlew :desktopApp:test`）。

测试把 `user.home` 指向临时目录，不触碰真实配置。

> **前置条件**：JDK 25、Kotlin 2.4.0、Compose Multiplatform 插件已就绪。`./gradlew :desktopApp:run` 会自动编译 `engine/`、`shared/` 模块及其方言插件（MySQL / PostgreSQL / H2 / DuckDB / SQLite），无需手动构建引擎。

---

## 顶层导航（v2.13）

`main.kt` 的 `MainScreen` 按 `AppDestination`（`:shared`）分派当前目标屏幕，两个目标：
（⚠️ 文档曾称此处渲染 `TopNavBar` 导航条 —— 该组件现已无实现，实际只有目标分派，见
[`shared/ARCHITECTURE.md` §5.2](../shared/ARCHITECTURE.md)）

| 目标 | 屏幕 | 职责 |
|---|---|---|
| `CONNECTIONS`（默认） | `ConnectionManagerScreen` | 连接列表 / 向导 / 连接生命周期 |
| `DATABASE` | `DatabaseBrowserScreen` | 数据库 / 表浏览 + 表数据预览标签页 |

导航状态由 `MainScreen` 持有的 `AppDestination` 决定（枚举与导航条均在 `:shared`）；连接列表 / 会话状态由 `ConnectionSession`
持有（位于导航之上），因此**切换目标不会丢失连接**。

## 功能：连接管理（v2.12）

> 📖 完整功能清单（含 SQL 工作台、造数工作台、与 DataGrip / Navicat 的差距矩阵）见 [`./FEATURES.md`](./FEATURES.md)。
> 以下两节只讲这两个界面的**实现细节**；v2.14 之后新增的 SQL 工作台、造数工作台、拖拽、取消、
> 失败横幅等不在此重复，只在 `FEATURES.md` 汇总。

应用启动后落在 `ConnectionManagerScreen` —— 左侧连接列表 + 右侧引导式配置 / 连接总览（v2.10 已移除演示 `DemoApp`）。

- **方言**：MySQL / PostgreSQL / H2 / DuckDB / SQLite（方言插件 + JDBC 驱动随应用类路径加载，见下）
- **两种引导流程**（`WizardFlow` 标识，步骤指示器自适应）：
  - 普通新建 / 编辑：`BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE`（4 步）
  - 快速连接：`QUICK_CONNECT → CREDENTIALS → TEST_SAVE`（3 步）
- **JDBC URL 折算（真相源）**：`CLIENT_SERVER` 显示 host / port / database / username / password + JDBC URL 输入框，双向实时映射；嵌入式方言（H2 / DuckDB / SQLite）用单一目标字段折算 URL。字段不足时「下一步」/「保存」/「连接」/「测试连接」全部禁用，保证交给引擎的配置一定有合法 URL
- **连接生命周期**：
  - 「连接」→ `EngineClient.testConnection`：按需建/复用 HikariCP 连接池 + `isValid` 校验（即**初始化连接**）
  - 「断开」/ 删除连接 → `EngineClient.disconnect`：释放该配置的连接池（幂等）；编辑已保存连接且字段变化时也会先释放旧池
  - 窗口关闭 → `EngineClient.close()`：释放全部池 / 驱动 / 方言
  - 会话状态（未连接 / 连接中 / 已连接 / 失败）由 `ConnectionSession` 维护，回传给列表色点与连接总览
  - 语义对两种实现一致：Direct 由本地方法直接完成；gRPC 下 `testConnection` / `disconnect` 分别走 `SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT` 路由（连接池在引擎进程内，远程调用方需要有线上路由才能释放）
- **持久化**：`ConnectionStorage` 读写 `~/.config/sundays/connection.json`，只落盘 `id` / `name` / `dialect` / `jdbcUrl` / `username` / `password` + 时间戳；`host` / `port` / `database` / `connectionType` 在加载时由 `parseJdbcUrl` 重建（按 `version` 分派 v1 / v2，v1 自动迁移回写）
- **快速连接不持久化**：`QUICK_CONNECT` 流程最后一步是「连接」而非「保存」，仅设为当前选中并直接连库
- **测试覆盖**：`ConnectionManagerFlowTest` 用真引擎 + 真点击跑通「选方言 → 填字段 → 测试连接 → 连接 → 断开 → 重连」全链路（`./gradlew :desktopApp:test`）

> **来源**：[`shared/`](../shared/) 模块的 `connection/` 子包；具体 API 见 [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) §4。

## 功能：数据库浏览（v2.13）

第二个界面 `DatabaseBrowserScreen` —— **左侧数据库 / 表树 + 右侧表数据预览标签页**。

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ 当前连接 [下拉选择]   已连接 · H2     ⟳    [断开]                            │
├──────────────────────┬─────────────────────────────────────────────────────┤
│ 数据库 / 表           │  [ users ×] [ orders ×]                             │
│  ▾ PUBLIC            ├─────────────────────────────────────────────────────┤
│     · users          │  PUBLIC · USERS    共 2 行 · 第 1 页 · 每页 100       │
│     · orders         ├─────────────────────────────────────────────────────┤
│  ▸ OTHER_DB          │  ID │ NAME                                          │
│                      │  1  │ Alice                                         │
└──────────────────────┴─────────────────────────────────────────────────────┘
```

- **左侧树**：连接就绪（`ConnectionState.CONNECTED`）后自动调用 `SCHEMA.LIST level=database` 拉库列表；点击库节点展开时按需调用 `TABLE.LIST` 拉表列表（懒加载，见 `DatabaseBrowserState.loadTables`）
- **打开预览**：单击表名 → 右侧新增一个预览标签页，调用 `DATA.LIST`（默认 `page = 1`，`pageSize = 100`）取数，用 `shared` 的 `DataTable` 渲染。表名节点挂的是 `clickable` 而非裸手势，因此**读屏有「打开表 X」语义动作、键盘 Tab + Enter 也能打开**；双击同样有效（两次 `onClick` 命中 `openTab` 的去重分支，幂等）
- **预览分页**：分页器是**真分页**——`page` / `pageSize` 随选择下发给引擎，回包就是当前页。由于分页发生在引擎侧，`DataTable` 必须传 `serverSidePaging = true`（否则它会按本地分页再切一次，第 2 页起直接切成空表），并传 `totalCount = tab.total` 供页码计算。该模式下分页大小下拉自动隐藏「全部」——`pageSize = 0` 在 `DATA.LIST` 里是流式读取哨兵，不是一个合法分页大小
- **翻页代次**：`TablePreviewTab` 自带 `requestGeneration`（与 `SqlSheet.generation` 同构、作用域是单个 tab）。屏级 `generation` 只管连接切换，管不到「同一张表上连着翻两页」——两次请求并发在跑，没有这个令牌就是「谁后返回谁说了算」，慢的第 1 页会盖掉快的第 2 页，表现为「信息条写着第 2 页、表里是第 1 页的行」
- **标签页去重**：标签页主键为 `schema::table`；重复打开同一张表只**激活**已有标签页，不会新增（`DatabaseBrowserState.openTab` 先查 key 再决定是否追加）
- **关闭 / 切换**：标签条支持逐页关闭（关闭后 `selectedTabIndex` 自动回退到最后一张或 `-1`）；切换标签页只切显示，不重新拉数据
- **连接切换**：`bindConnection` 在连接 id 变化时清空库列表 / 展开状态 / 表缓存 / 全部标签页；代次自增与「正在执行 / 正在加载」标志的复位统一由 `releasePools` → `invalidateInFlight` 负责（`bindConnection` 内部只调 `releasePools`，不再另写一份 —— 两处各写一半正是「切连接后转圈停不下来」的来源）。in-flight 查询在挂起点后比对代次，**过期响应直接丢弃**，避免上一连接的旧数据落到新连接
- **断开也会作废**：`releasePools` 的 `invalidateInFlight` 在**任何**早退之前执行，且覆盖 SQL sheet / 造数 / 预览三类 in-flight 标志。断开时连接 id 未变，`bindConnection` 会早退，若不作废则：唯一会写 `running = false` 的协程已被代次挡住，界面**永远**停在「执行中…」；重连后旧行帧还会继续写进存活的 sheet
- **错误可读**：引擎报错常是几百字符，错误区一律用 `EmptyHint(centerContent = false)` 渲染（顶对齐 + 可纵向滚动）。早期版本用「`fillMaxSize` + 居中」，内容溢出时**首尾同时被裁**，而 `Caused by:` 恰好在末尾；预览顶部信息条里的错误则限一行省略号，避免把下方 `fillMaxSize()` 的表格挤成 0 高
- **连接池归属**：浏览另一个 catalog 会用到另一份 proto config（`database` 参与池 key），所以连接管理页的「断开」只释放它自己那份池。本屏用 `releasePools()` 释放自己建立的池 —— 触发点：切换连接、会话断开 / 失败；窗口关闭由 `EngineClient.close()` 兜底。释放是异步的，因此状态机由 `MainScreen` 持有一个长生命周期 `CoroutineScope`（组件自身的 scope 在 dispose 时已取消，会把释放动作丢掉）
- **状态归属**：`DatabaseBrowserState` 在 `MainScreen` 中 `remember`，**不在屏幕内部** —— 因此切到「连接管理」再切回来时已打开的标签页不丢失，同一份状态机也可脱离 UI 直接驱动
- **引擎耦合法**：`engine.invoke(connection, { category/action/… })` 走强类型 `SCHEMA.LIST` / `TABLE.LIST` / `DATA.LIST`，与连接管理一致（默认 Direct 模式，无 gRPC；设了端点属性则同一调用打到 gRPC 引擎进程）。请求的 `driver` 填 `DialectType.engineDriverName`（引擎注册键是 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`，与枚举常量名大小写不同）
- **测试覆盖**：`DatabaseBrowserFlowTest`（状态机：拉库 → 展开表 → 开标签页 → 去重 → 关闭）、`DatabaseBrowserUiTest`（真点击：双击开标签页 + 断言 `Role=Tab` 数量恒为 1）、`MainScreenNavTest`（顶层导航切换）、`DialectNameContractTest`（`engineDriverName` ↔ 引擎 `SYSTEM.LIST_DRIVERS` 一致性）

---

## 引擎集成（`EngineClient` 调用层）

UI 层只面向 `EngineClient` 接口（`engine-protocol/`，随 `:engine` 以 `api` 传递）编程；实现由 `main.kt` 的装配点选择。
`desktopApp/build.gradle.kts` 把两个实现都作为**库**依赖引入；方言插件与 JDBC 驱动以 `runtimeOnly` 依赖上到应用类路径 —— 应用不需要外部 `dialects/` / `drivers/` 目录：

```kotlin
// desktopApp/build.gradle.kts
dependencies {
    implementation(project(":shared"))
    // 调用层抽象：UI 只面向 EngineClient 接口编程。两个实现都在装配点可选：
    //   :engine            → IdbEngine（同 JVM 直接调用；默认，不启动子进程 / 不建 gRPC channel）
    //   :engine-grpc-client → GrpcEngineClient（跨进程 gRPC；-Dsundays.engine.endpoint=host:port 启用）
    // 选择逻辑见 main.kt 的 createEngineClient()
    implementation(project(":engine"))
    implementation(project(":engine-grpc-client"))

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

启动与实现选择（`main.kt`）：

```kotlin
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.client.grpc.GrpcClientConfig
import com.kxxnzstdsw.client.grpc.GrpcEngineClient
import com.kxxnzstdsw.engine.IdbEngine

/** 选择 gRPC 引擎端点的系统属性名。 */
const val ENDPOINT_PROPERTY = "sundays.engine.endpoint"

internal fun createEngineClient(): EngineClient {
    val endpoint = System.getProperty(ENDPOINT_PROPERTY)?.trim().orEmpty()
    return if (endpoint.isEmpty()) {
        IdbEngine()                                                   // 默认：同 JVM，构造时自动 bootstrap（幂等）：加载方言 + 驱动
    } else {
        GrpcEngineClient.connect(GrpcClientConfig.fromTarget(endpoint)) // 畸形端点 → IllegalArgumentException（不静默回落到本地）
    }
}

fun main() = application {
    val engine: EngineClient = createEngineClient()
    Window(
        onCloseRequest = {
            engine.close()                                         // 释放 PoolManager / DriverLoader / DialectLoader
            exitApplication()
        },
        title = "sundays",
    ) {
        SundaysTheme {                                             // :shared 的主题（跟随系统明暗，配色/形状/字号取自 SundaysPalette）
            MainScreen(engine)                                     // 绑定 ConnectionSession → ConnectionManagerScreen / DatabaseBrowserScreen
        }
    }
}
```

`ConnectionSession`（`ConnectionSession.kt`）与 `DatabaseBrowserState`（`DatabaseBrowserScreen.kt`）的构造参数类型均为 `EngineClient`，
因此实现选择对它们完全透明。

`MainScreen` 只做绑定：`ConnectionSession`（`ConnectionSession.kt`）持有连接列表 / 向导状态 / 引擎会话状态，
并实现全部回调（`connect` / `disconnect` / `testConnection` / `save` / `delete`）—— 因此同一份状态机可在测试中直接驱动。

**如何改走 gRPC**（可选，代码零改动）：

```bash
# 1. 先单独启动引擎进程
java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051

# 2. 再带端点属性启动应用（-D 需传给应用 JVM）
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051
```

`-Dsundays.engine.endpoint` 接受的格式（由 `GrpcClientConfig.fromTarget` 解析）：`host:port` / `tcp://host:port` / `unix://<path>` / `pipe:<name>`。不设（或设为空白）即回落到同进程 `IdbEngine`；端点格式非法时抛 `IllegalArgumentException`，不会静默回落到本地引擎。

**两种实现的对比**：

| 维度 | Direct / `IdbEngine`（默认） | gRPC / `GrpcEngineClient` |
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
| [`desktopApp/FEATURES.md`](./FEATURES.md) | **功能文档** —— 能力总览（三栏：已实现 / 引擎有但未接线 / 完全没有）、与 DataGrip & Navicat 的差距矩阵、按投入产出比排序的路线 |
| [`desktopApp/ARCHITECTURE.md`](./ARCHITECTURE.md) | desktopApp 内部架构（KMP 工程结构 / Direct 模式集成 / 连接管理与关键回调 / 生命周期管理） |
| [根目录 `README.md`](../README.md) | 项目总览、模块结构、Direct 模式详解、运行命令 |
| [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | V2.12 架构导航（双模式架构 / 模块结构 / 连接生命周期直连方法） |
| [`engine/README.md`](../engine/README.md) | 引擎模块详细 README（CLI / 构建 / handler 路由 / API 参考） |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade 详解 / Dispatcher / Pool / Loader） |
| [`shared/`](../shared/) | KMP 共享代码（`ConnectionManagerScreen` / `CodeEditor` / `DataTable` / 右键菜单） |
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | 调用层接口 `EngineClient`（`handle` / `invoke` / `testConnection` / `disconnect` / `close`） |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | `GrpcEngineClient` / `GrpcClientConfig`（端点格式解析与 channel 管理） |

---

## 技术栈

- **Kotlin 2.4.0 / JDK 25**
- **Kotlin Multiplatform** + **Compose Multiplatform Desktop**（`jvmMain` 单平台目标）
- **Material 3** 组件库（`androidx.compose.material3`）
- **kotlinx-coroutines 1.11.0**（含 `coroutines-swing` 用于协程 UI dispatch）
- **`:engine` + `:engine-grpc-client` 模块**（`EngineClient` 的两个实现）+ **`:shared` 模块**（`ConnectionManagerScreen` / `CodeEditor` / `DataTable` 组件）
- **protobuf-java + protobuf-kotlin-lite**（消费 `:engine` 的 typed proto —— `:engine` 以 `implementation` 声明，不向消费方编译类路径传递）

详细依赖见 [`desktopApp/build.gradle.kts`](./build.gradle.kts) 与根目录 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。