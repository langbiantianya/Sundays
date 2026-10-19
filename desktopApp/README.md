# desktopApp — Compose Desktop 客户端

`desktopApp/` 是 sundays 的**桌面客户端模块**：Kotlin Multiplatform + Compose Multiplatform，
当前仅启用 **JVM Desktop** 单平台目标（macOS / Linux / Windows 共享同一份 Compose Desktop 渲染）。
9 个主源文件 —— 界面组件全在 `:shared`，本模块只剩「**平台窗口 + 引擎状态机接线**」。

> **本文档的形态**：**大纲**。窗口与状态机的设计决策见 [`./ARCHITECTURE.md`](./ARCHITECTURE.md)，
> 能力清单见 [`./FEATURES.md`](./FEATURES.md)，测试覆盖矩阵见 [`./TEST_CASES.md`](./TEST_CASES.md)。

---

## 1. 怎么运行

| 命令 | 用途 |
|---|---|
| `./gradlew :desktopApp:run` | 普通运行；会自动编译 `engine/`、`shared/` 及其方言插件，无需手动构建引擎 |
| `./gradlew :desktopApp:hotRun --auto` | Hot reload 开发模式（**推荐**）—— 修改代码自动重启 |
| `./gradlew :desktopApp:distributable` | 打包发布版，生成 `.dmg` / `.msi` / `.deb`，产物在 `desktopApp/build/compose/binaries/` |
| `./gradlew :desktopApp:test` | 端到端 / UI 测试：Compose UI 测试 + 真引擎 + H2 内存库，**无需外部数据库**（测试把 `user.home` 指向临时目录） |

前置条件：JDK 25、Kotlin 2.4.20、Compose Multiplatform 插件已就绪。

---

## 2. 两屏结构与状态机归属

[`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) 的 `MainScreen` 按 `AppDestination`
（枚举在 `:shared`）分派当前目标屏幕，两个目标：

| 目标 | 屏幕 | 状态机持有者 |
|---|---|---|
| `CONNECTIONS`（默认） | `ConnectionManagerScreen`（`:shared` 提供纯 UI） | [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) —— 连接列表 / 向导状态 / 引擎会话状态 / 多 sheet |
| `DATABASE` | `DatabaseBrowserScreen`（本模块，按功能域拆成 10 个文件，见 [`ARCHITECTURE.md` §2](./ARCHITECTURE.md)） | [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) —— 浏览 / SQL 工作台 / 造数工作台三套状态机 |

**状态机挂在导航之上**（在 `MainScreen` 里 `remember`，不在屏幕内部），因此切目标不丢连接、也不丢已打开的表预览标签页；
通知中心与长生命周期 `CoroutineScope` 同样在屏级。各状态归谁、切连接时什么时候复位，见
[`./ARCHITECTURE.md`](./ARCHITECTURE.md) 的状态归属速查。`MainScreen` 只做绑定，引擎回调全部由
`ConnectionSession` 实现 —— 同一份状态机因此可在测试中直接驱动。

---

## 3. 引擎接线：Direct 与 gRPC 两模式

UI 层只面向 **`EngineClient`** 接口（`engine-protocol/`，`AutoCloseable`）编程；实现在
[`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) 的 **`createEngineClient()`** 这一个装配点二选一：

| 模式 | 触发条件 | 实现 |
|---|---|---|
| **Direct（默认）** | 未设 `-Dsundays.engine.endpoint` | 同 JVM 的 `IdbEngine()` —— 不启动子进程、不建 gRPC channel、不走 IPC transport，typed proto 同一 JVM 内直传 |
| **gRPC** | 设了 `-Dsundays.engine.endpoint` | 跨进程 `GrpcEngineClient.connect(...)`；端点格式由 `GrpcClientConfig.fromTarget` 解析（`host:port` / `tcp://host:port` / `unix://<path>` / `pipe:<name>`） |

**两种模式下 UI 代码完全相同** —— `ConnectionSession` / `DatabaseBrowserState` 的构造参数类型都是
`EngineClient`，实现选择对它们透明。端点格式非法时抛 `IllegalArgumentException`，**不静默回落到本地引擎**。

[`build.gradle.kts`](./build.gradle.kts) 把两个实现都作为**库**依赖引入；5 个 `dialect-*` 插件与 JDBC 驱动以
`runtimeOnly` 上到应用类路径 —— **不需要外部 `dialects/` / `drivers/` 目录**，缺了它们引擎解析不出任何方言。
`protobuf-java` / `protobuf-kotlin-lite` 需在集成层显式补齐（`:engine` 以 `implementation` 声明，不向消费方传递）。

改走 gRPC（可选，代码零改动）：

```bash
# 1. 先单独启动引擎进程
java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051
# 2. 再带端点属性启动应用（-D 需传给应用 JVM）
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051
```

**窗口生命周期即引擎生命周期**：`onCloseRequest` 调 `engine.close()` 释放 PoolManager / 驱动 / 方言。
Direct 模式下 `engine` 是 JVM 内的 library 对象而非独立进程入口，因此不需要 Shutdown Hook。

---

## 4. 技术栈

- **Kotlin 2.4.20 / JDK 25**、Kotlin Multiplatform + Compose Multiplatform Desktop（`jvmMain` 单平台目标）
- **Material 3**（`androidx.compose.material3`）、**kotlinx-coroutines 1.11.0**（含 `coroutines-swing`）
- **`:shared`**（界面组件）+ **`:engine` / `:engine-grpc-client`**（`EngineClient` 的两个实现）+ **`:engine-protocol`**（调用层契约）
- **protobuf-java / protobuf-kotlin-lite**（集成层需要 typed proto 才能调 facade 的 Direct 模式 API）

完整依赖见 [`build.gradle.kts`](./build.gradle.kts) 与根目录 [`../gradle/libs.versions.toml`](../gradle/libs.versions.toml)。

---

## 5. 相关文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | **内部架构与设计决策索引**（本文不重复） |
| [`FEATURES.md`](./FEATURES.md) | 能力总览、引擎有但前端没接什么、与 DataGrip / Navicat 的差距 |
| [`TEST_CASES.md`](./TEST_CASES.md) | 测试用例与覆盖矩阵 |
| [`../shared/README.md`](../shared/README.md) | 界面组件来自哪里 |
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | 调用层契约 `EngineClient` |
| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | `GrpcEngineClient` / `GrpcClientConfig` |
| [`../engine/README.md`](../engine/README.md) | 引擎模块（CLI / handler 路由 / API 参考） |
| [根 `../README.md`](../README.md) / [`../ARCHITECTURE.md`](../ARCHITECTURE.md) | 项目总览与整体架构 |
