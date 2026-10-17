# sundays — Kotlin 数据库管理端

一个使用 Kotlin 编写、面向桌面端的**数据库管理工具**。前端是 **Kotlin Multiplatform +
Compose Multiplatform** 桌面应用（`desktopApp/` + `shared/`），后端是**调用层抽象**的无头引擎：

| 模式 | 调用层实现 | 通道 | 适用场景 |
| --- | --- | --- | --- |
| **Direct 直接模式（默认）** | `IdbEngine`（`engine/`） | 同 JVM 直接方法调用 | KMP Desktop 应用，零序列化、零子进程 |
| **gRPC 模式** | `GrpcEngineClient`（`engine-grpc-client/`） | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、子进程隔离、远程调试 |

两者实现同一个接口 **`EngineClient`**（`com.langbiantianya.client.EngineClient`，定义在
`engine-protocol/`）—— 调用方只面向接口编程，**换实现不改调用代码**。装配点只有一个：
[`main.kt`](./desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt) 的 `createEngineClient()`，
用 `-Dsundays.engine.endpoint` 决定用哪个实现。契约细节见
[`engine-protocol/README.md`](./engine-protocol/README.md)。

引擎支持 **5 个**可插拔方言：**MySQL** / **PostgreSQL** / **H2** / **DuckDB**（本地嵌入式
OLAP） / **SQLite**（本地嵌入式关系型），通过 `ServiceLoader` 动态加载，互不编译依赖。
方言 SPI 见 [`api/README.md`](./api/README.md)，各方言的能力差异见
[`desktopApp/FEATURES.md`](./desktopApp/FEATURES.md) 的能力矩阵。

界面支持 **6 种语言**：简体中文 / 繁體中文 / English / Русский / 日本語 / Deutsch。首启在引导页按
系统语言预选，随时可在设置里改；引擎的错误消息也跟随界面语言。见
[`i18n/README.md`](./i18n/README.md)。

> **当前版本：v2.25**
>
> 📖 **功能文档：[`desktopApp/FEATURES.md`](./desktopApp/FEATURES.md)** —— 这个工具现在能干什么、
> 引擎已实现但前端尚未接线的部分、以及与 **DataGrip / Navicat** 的逐项差距矩阵。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `api/` | 公共 SPI：`DatabaseDialect` / `ConnectionType` / `DialectCapability` |
| `dialect-{mysql,postgresql,h2,duckdb,sqlite}/` | 5 个方言插件，各自打成独立 JAR，经 `ServiceLoader` 加载 |
| `engine-protocol/` | 调用层契约：proto 协议 + `EngineClient` 接口 + `RequestLanguage`。零业务逻辑，依赖以 `api` 导出 |
| `engine/` | 引擎实现：请求路由（`dispatcher/`）、SQL 执行与查询取消、连接池与事务、导入导出、IPC、插件加载、gRPC 服务端 |
| `engine-grpc-client/` | `EngineClient` 的跨进程 gRPC 实现（TCP / UDS / 命名管道），**不拖入** Hadoop / POI / LuaJIT |
| `i18n/` | 六语言文案：键枚举 + 六份译文表 + 简繁判定 + 复数规则 |
| `shared/` | KMP 共享代码：CodeEditor / DataTable / ConnectionManagerScreen / 主题等 UI 组件，全部与引擎无关 |
| `desktopApp/` | Compose Desktop 应用：窗口、连接会话状态机、数据库浏览屏 |
| `packaging/flatpak/` | Flatpak manifest 与桌面项 |

依赖方向是单向的：**`desktopApp` → `shared` → （无）**，**`desktopApp` → `engine-protocol` →
`api`**，`engine` 与 `engine-grpc-client` 都只依赖 `engine-protocol`，彼此不成环。
完整架构见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)。

## 运行

```bash
# Hot reload 开发模式
./gradlew :desktopApp:hotRun --auto

# 普通运行（Direct 模式：引擎同进程，无需子进程）
./gradlew :desktopApp:run

# 改走 gRPC：先启动独立引擎进程，再指定端点
java -jar engine/build/libs/idb-engine.jar --mode grpc --ipc tcp --port 50051
./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051
```

引擎 standalone 的构建与启动参数：

```bash
./gradlew engine:jar
cd engine/build/libs && java -jar idb-engine.jar                          # gRPC server，默认 TCP :50051
java -jar idb-engine.jar --mode direct                                     # 不开 server，仅 bootstrap
java -jar idb-engine.jar --mode grpc --ipc unix --uds-path /run/idb/engine.sock
```

CLI 参数、handler 路由矩阵与 API 参考见 [`engine/README.md`](./engine/README.md)。

## 测试

```bash
./gradlew test                              # 全量
./gradlew :engine:test                      # 引擎
./gradlew :desktopApp:test                  # 桌面端（含引擎装配）
./gradlew :shared:jvmTest                   # 共享 UI 与逻辑
./gradlew :dialect-h2:test                  # 单个方言模块
./gradlew :engine-grpc-client:test          # 真实 gRPC server + 真实 H2 端到端
```

GUI 走查用例（编号、前置、步骤、实际结果）已搬进对应 test 函数的 KDoc，
覆盖矩阵见 [`desktopApp/TEST_CASES.md`](./desktopApp/TEST_CASES.md)。

> ✅ Windows 下无基线失败。历史上长期挂着的 11 项根因是单个产品缺陷：
> `ConnectionStorage.savePersisted` 用 `Files.createDirectories(dir, posix:permissions)` 建目录，
> 而 Windows 文件系统提供者在该属性上直接抛异常，异常被吞掉后 `save` 返回 `false` ——
> **Windows 用户存不下任何连接配置**，界面上还没有任何提示。

## 打包

推 `main`、打 `v*` tag、或在 Actions 里手动触发，三条工作流各自产出对应平台的安装包。
打 tag 时每个工作流会额外跑一个 `release` 作业，把产物挂到该 tag 的 Release 上（默认建草稿）。

| 平台 | 产物 | 工作流 |
| --- | --- | --- |
| Windows | `sundays-windows-{x64,arm64}-msi`（约 281 MB）、`-portable.zip` | [`build-windows.yml`](./.github/workflows/build-windows.yml) |
| macOS | `sundays-macos-{x64,arm64}-dmg`（约 292 MB）、`-app.zip` | [`build-macos.yml`](./.github/workflows/build-macos.yml) |
| Linux | `sundays-flatpak-{amd64,arm64}.flatpak`（约 297 MB） | [`build-flatpak.yml`](./.github/workflows/build-flatpak.yml) |

本地打包：

```bash
./gradlew :desktopApp:packageMsi            # → desktopApp/build/compose/binaries/main/msi/*.msi
./gradlew :desktopApp:createDistributable   # → .../binaries/main/app/  （便携版）
```

**x64 与 arm64 两条腿并行构建，且产物架构会在上传之前自检** —— 「矩阵里写了 arm64」
不等于「包里真是 arm64」，工作流会读 PE header / `lipo -archs` / ELF 头逐个核对启动器、
`jvm.dll`/`libjvm.dylib` 与 skiko 原生库三处，对不上就不上传。

> **安装包目前未签名。** Windows 会被 SmartScreen 拦一下；macOS 首次打开会被 Gatekeeper
> 拦一下（arm64 上 Compose 自动打 ad-hoc 签名，x64 不签），右键「打开」或
> `xattr -dr com.apple.quarantine <路径>` 即可。

三平台打包踩过的坑（jlink 模块集必须自补否则报 `Failed to launch JVM`、Windows 快捷方式与
图标默认都不建、protoc 没有 `windows-aarch_64` classifier、Flatpak 的 JDK 来源与
`-Dswing.systemlaf`、CI 失败要看 annotation 而不是大步骤日志）都记在**工作流与组装脚本的
注释里** —— 改打包流程前先读那三个 yml 和
[`assemble-flatpak-app.sh`](./.github/scripts/assemble-flatpak-app.sh) 的段首注释。
Flatpak 的选型结论索引见 [`packaging/flatpak/README.md`](./packaging/flatpak/README.md)。

## 代码质量

三项检查：格式走 **ktlint**，安全漏洞走 **CodeQL**，指标与规则集走 **SonarQube**。

| 检查 | 何时跑 | 怎么跑 |
| --- | --- | --- |
| ktlint | 本地 + CI（含 PR） | `./gradlew ktlintCheck`；改格式用 `ktlintFormat` |
| CodeQL | CI（含 PR、每周一） | [`codeql.yml`](./.github/workflows/codeql.yml)，结果进仓库的 Security 页 |
| SonarQube | 本地 git hook | 见下 |

规则全在 [`.editorconfig`](./.editorconfig) 里，是格式的唯一事实来源 ——
本地、CI、IDE 保存时格式化读的是同一套，所以不存在「本地过了 CI 没过」。
其中两条是刻意关掉的：`max_line_length`（118 处全在 i18n 文案表与方言 SQL 字面量里，
ktlint 不会拆字符串）、`function-naming`（`@Composable` 按 Compose 编译器要求就是 PascalCase）。

仓库自带的体检脚本，都可以直接跑：

```bash
python desktopApp/tools/check-encoding.py        # U+FFFD / 裸 NUL / BOM / 夹带的 U+FEFF
python desktopApp/tools/check-config-syntax.py   # YAML / TOML / XML / JSON / proto
python desktopApp/tools/check-doc-links.py       # markdown 链接与锚点
```

### 本地 SonarQube（SonarScanner for Gradle）

分析走官方推荐的 **SonarScanner for Gradle**：就是一条普通的 `./gradlew sonar`，
扫描器引擎由插件自己下载，不需要另外装 sonar-scanner CLI。

```bash
docker run --rm -p 9000:9000 sonarqube:community     # 社区版，Kotlin 分析是全版本支持的
python desktopApp/tools/install-git-hooks.py         # 装 pre-commit hook（只需一次）
export SONAR_TOKEN=<在 http://localhost:9000 生成>

python desktopApp/tools/sonar-local-scan.py          # 跑一次完整分析
```

结果进 <http://localhost:9000/dashboard?id=langbiantianya_Sundays>。

**换用 Gradle 插件省掉了三样手工维护的东西**，这些现在全部由插件从 Gradle 对象
模型自动推导：`sonar.sources` / `sonar.tests`（原先要逐模块列 12 行，漏一个模块
就静默不扫且不报错）、`sonar.java.binaries`（编译输出目录）、`sonar.java.libraries`
（原先要从 Gradle 缓存里翻 253 个 jar 并剔掉 sources/javadoc，现在直接用
`sourceSets.main.compileClasspath`）。加新模块自动纳入，没有「忘了更新」这回事。

分析配置写在根 [`build.gradle.kts`](./build.gradle.kts) 的 `sonar { properties { … } }` 里。
官方文档明确 SonarScanner for Gradle **不读** `sonar-project.properties`，所以仓库里
不再保留那个文件 —— 留着只会让人改一个根本没人读的东西还以为生效了。

`sonar` 任务会先编译全部模块（12 个，本机约 3 分钟）以产出字节码：官方文档说
插件只用 `mustRunAfter` 保证顺序、不自动加依赖，字节码任务必须真的执行过，否则
类型解析会退化。刚编完只想重扫，用 `SONAR_GRADLE_ARGS="-x classes -x testClasses
-x jvmMainClasses -x jvmTestClasses"` 跳过编译。

**hook 默认只做前置检查、不跑分析** —— 把一次 3 分钟的全量编译塞进每次
`git commit` 是不能接受的设计。设 `SONAR_SCAN=1` 可让提交时也真扫一次。

**hook 默认只提示、不阻断提交** —— 因为绝大多数时候你本机并没有跑着 SonarQube，
那是正常状态而不是缺陷；把它当闸门只会挡住正常工作。要让它真拦，得设 `SONAR_REQUIRED=1`。
临时跳过用 `git commit --no-verify`。

| 环境变量 | 作用 |
| --- | --- |
| `SONAR_HOST_URL` | 服务器地址，默认 `http://localhost:9000` |
| `SONAR_TOKEN` | 认证令牌，**只从环境变量读，绝不写进仓库** |
| `SONAR_REQUIRED=1` | 条件不满足时判失败，hook 变成真正的闸门 |
| `SONAR_SKIP=1` | 完全跳过 |
| `SONAR_SCAN=1` | hook 模式下也真跑一次完整分析 |
| `SONAR_GRADLE_ARGS` | 追加给 Gradle 的参数，如 `-x classes -x testClasses` |

## 文档

| 文档 | 内容 |
| --- | --- |
| [`desktopApp/FEATURES.md`](./desktopApp/FEATURES.md) | **功能文档** —— 能力总览、与 DataGrip & Navicat 的差距矩阵、路线建议 |
| [`desktopApp/TEST_CASES.md`](./desktopApp/TEST_CASES.md) | GUI 走查用例的**覆盖矩阵**（用例详情在各测试函数的 KDoc 里） |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | **架构导航** —— 调用层、分层依赖、方言矩阵 |
| [`engine/README.md`](./engine/README.md) / [`engine/ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | 引擎模块：CLI、handler 路由、导入导出、IPC、连接池 |
| [`engine-protocol/README.md`](./engine-protocol/README.md) | **调用层契约** —— `EngineClient` 接口与 proto 协议 |
| [`engine-grpc-client/README.md`](./engine-grpc-client/README.md) | gRPC 调用模块：端点格式、端到端测试 |
| [`api/README.md`](./api/README.md) | 方言 SPI 与能力枚举 |
| [`shared/README.md`](./shared/README.md) / [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | 共享 UI 组件（CodeEditor / DataTable / 连接管理 / 主题） |
| [`desktopApp/README.md`](./desktopApp/README.md) / [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | 桌面端：浏览屏 / 连接管理的状态机与契约 |
| [`i18n/README.md`](./i18n/README.md) | 六语言文案：键枚举、译文表、简繁判定、复数规则 |
| [`packaging/flatpak/README.md`](./packaging/flatpak/README.md) | Flatpak 选型结论索引 |

各模块文档只写大纲；**具体契约、边界与设计理由都在对应类/函数的 KDoc 里**，改代码前先读那里。

## 技术栈

- **Kotlin 2.4.20 / JDK 25**
- **Compose Multiplatform Desktop**（KMP Desktop 前端）
- **gRPC 1.83.1** + grpc-kotlin 1.5.0（Kotlin 协程服务端 + Kotlin DSL 生成）
- **protobuf-kotlin-lite 4.35.1**（DSL builder：`xxxRequest { … }` / `request { … }`）
- **kotlinx-coroutines 1.11.0**
- **HikariCP 7.0.2**（SHA-256 缓存连接池）
- **MySQL Connector/J 9.7.0** / **PostgreSQL JDBC 42.7.11** / **H2 2.3.232** / **DuckDB JDBC 1.5.5.1** / **SQLite JDBC 3.46.1.3**
- **SLF4J 2.0.18 + Logback 1.5.13**
- **LuaJIT 4.1.0 + Lua 5.1~5.5**（造数引擎）
- **Apache POI 5.5.1**（Excel 流式导出 + DuckDB Excel 预转换）
- **Apache Parquet 1.17.1 + Hadoop 3.5.0**（Parquet 导出）

## 架构升级历史

每版只留结论，细节见对应模块文档与代码 KDoc。

| 版本 | 主要变化 |
| --- | --- |
| v1.0 | stdin/stdout + 长度前缀 + 自定义 protobuf（旧管道协议） |
| v2.0–v2.6 | gRPC over HTTP/2；IPC Transport SPI；强类型 per-Category 消息；表驱动 Dispatcher + Envelope Options |
| v2.7–v2.8 | DuckDB / SQLite 方言插件 + SPI 连接元数据扩展 + `SYSTEM.LIST_DRIVERS` |
| v2.9 | KMP Desktop Direct 模式，前端从 Wails v3 迁移到 Compose Desktop；CodeEditor / DataTable 高度策略统一 |
| v2.10–v2.12 | JDBC URL 双向折算；仅凭 URL 初始化连接；连接生命周期（连接/断开）；`ServiceLoader` 插件加载；连接状态收敛到 `ConnectionSession` |
| v2.13–v2.14 | 顶层导航 + 数据库浏览第二屏（库表树 + 预览标签页、会话代次丢弃过期响应）；共享代码上移 `shared/` |
| v2.15 | **调用层抽象** —— 新增 `engine-protocol`（`EngineClient` 接口）与 `engine-grpc-client`；`main.kt` 成为唯一装配点 |
| v2.16 | **引擎能力补齐** —— 查询取消 `SYSTEM.CANCEL`、数据导入 `IMPORT.RUN_IMPORT`、事务会话、多语句脚本 |
| v2.17–v2.20 | 编辑器格式化契约重写（两向贴合）、补全能力、SQL 工作台接入真实 schema、库/表树宽度拖拽 |
| v2.21–v2.22 | 分页底栏缺陷修复与降级顺序；in-flight 状态机契约的测试补强（撤回一处误判为缺陷的"修复"） |
| v2.23 | SQL 执行可取消、结果集封顶、浏览屏显示连接失败原因 |
| v2.24 | 修复代码编辑器不跟随主题系统（根因在明暗档而非配色），顺带修掉三个自己写的假绿测试 |
| v2.25 | **11 项长期红测归零** —— 根因是「Windows 上存不下任何连接配置」的产品缺陷 |

## 许可证

[MPL 2.0](./LICENSE)。所有源文件都带 SPDX 式的 MPL 声明头。
