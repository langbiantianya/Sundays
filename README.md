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

> **当前版本：v2.28**
>
> 📖 **功能文档：[`desktopApp/FEATURES.md`](./desktopApp/FEATURES.md)** —— 这个工具现在能干什么、
> 引擎已实现但前端尚未接线的部分、以及与 **DataGrip / Navicat** 的逐项差距矩阵。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `api/` | 公共 SPI：`DatabaseDialect` / `ConnectionType` / `DialectCapability` |
| `dialect-contract/` | 连接表单契约：`ConnectionFormSpec` / `ConnectionField` / `AuthMethod`（**与 `:i18n` 同构** —— `:api` 声明、`:shared` 渲染，两边彼此看不见） |
| `dialect-{mysql,postgresql,h2,duckdb,sqlite}/` | 5 个方言插件，各自打成独立 JAR，经 `ServiceLoader` 加载 |
| `engine-protocol/` | 调用层契约：proto 协议 + `EngineClient` 接口 + `RequestLanguage`。零业务逻辑，依赖以 `api` 导出 |
| `engine/` | 引擎实现：请求路由（`dispatcher/`）、SQL 执行与查询取消、连接池与事务、导入导出、IPC、插件加载、gRPC 服务端 |
| `engine-grpc-client/` | `EngineClient` 的跨进程 gRPC 实现（TCP / UDS / 命名管道），**不拖入** Hadoop / POI / LuaJIT |
| `i18n/` | 六语言文案：键枚举 + 六份译文表 + 简繁判定 + 复数规则 |
| `shared/` | KMP 共享代码：CodeEditor / DataTable / ConnectionManagerScreen / 表结构编辑弹窗 / SQL 文本工具 / **方言元数据表** / 浏览器 chrome（标签条 / 工作台切换）/ 通知中心 / 主题等，全部与引擎无关 |
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

两项检查：格式走 **ktlint**，安全漏洞走 **CodeQL**。

| 检查 | 何时跑 | 怎么跑 |
| --- | --- | --- |
| ktlint | 本地 + CI（含 PR） | `./gradlew ktlintCheck`；改格式用 `ktlintFormat` |
| CodeQL | CI（含 PR、每周一） | [`codeql.yml`](./.github/workflows/codeql.yml)，结果进仓库的 Security 页 |

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

### 曾评估并否决：SonarQube

本机跑过社区版 26.10 的端到端分析后**移除了**。扫描本身没问题（281 个文件
全部进分析、145 条 Kotlin 规则全部激活），但规则集对不上这个项目的风险面：

- Kotlin 规则总数 **145**，Java 是 **791** —— 只有 18%；
- 31 条 Kotlin「安全」规则里约 12 条是 Android 专用，对桌面数据库客户端无意义；
- **Kotlin 规则集里一条 SQL 规则都没有**。Java 那边有 8 条，含 `java:S2077`
  （SQL 不得动态拼接）、`java:S6912`（JDBC 应用批处理）、`java:S1192`
  （字符串字面量不得重复）；而本仓库 `createStatement`/`prepareStatement`
  调用 350 处 / 41 个文件、SQL 字面量 771 处 / 79 个文件，恰好全在盲区里。

结果是 33,274 行代码只报 3 条问题 —— 读起来像「很干净」，实际是「不看最容易
出错的地方」。这种假安全感比没有工具更糟。

SQL 注入这一块由 CodeQL 的 `java/sql-injection` 查询集覆盖（它对 Kotlin 生效），
真正该看的地方并没有因此失去检查。评估过程与数据记在根
[`build.gradle.kts`](./build.gradle.kts) 顶部的注释里，免得将来重复评估。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`desktopApp/FEATURES.md`](./desktopApp/FEATURES.md) | **功能文档** —— 能力总览、与 DataGrip & Navicat 的差距矩阵、路线建议 |
| [`docs/analysis-mode/README.md`](./docs/analysis-mode/README.md) | **数据分析模式设计文档** —— 独立只读分析模式、连接与设置复用、**组件复用与通用化**、DuckDB / Hive / Impala 引擎、Excel 报表模型、迭代拆分（附 [UI 设计图](./docs/analysis-mode/ui-mockup.html)） |
| [`desktopApp/TEST_CASES.md`](./desktopApp/TEST_CASES.md) | GUI 走查用例的**覆盖矩阵**（用例详情在各测试函数的 KDoc 里） |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | **架构导航** —— 调用层、分层依赖、方言矩阵 |
| [`engine/README.md`](./engine/README.md) / [`engine/ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | 引擎模块：CLI、handler 路由、导入导出、IPC、连接池 |
| [`engine-protocol/README.md`](./engine-protocol/README.md) | **调用层契约** —— `EngineClient` 接口与 proto 协议 |
| [`engine-grpc-client/README.md`](./engine-grpc-client/README.md) | gRPC 调用模块：端点格式、端到端测试 |
| [`api/README.md`](./api/README.md) | 方言 SPI 与能力枚举 |
| [`shared/README.md`](./shared/README.md) / [`shared/ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | 共享 UI 组件（CodeEditor / DataTable / 连接管理 / 浏览器 chrome / 通知中心 / 主题） |
| [`desktopApp/README.md`](./desktopApp/README.md) / [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | 桌面端：浏览屏 / 连接管理的状态机与契约 |
| [`i18n/README.md`](./i18n/README.md) | 六语言文案：键枚举、译文表、简繁判定、复数规则 |
| [`dialect-contract/README.md`](./dialect-contract/README.md) | 连接表单契约：方言自描述「连得上要填什么」，`:api` 声明 / `:shared` 渲染 |
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
| v2.26 | **表格内数据操作与表结构管理** —— 批量删除 / 批量添加、NULL 写入约定、引擎 `TableCapabilities`；右键菜单新增表 / 编辑表结构 / 删除表并接入事务会话（**实测纠正 H2 对 DDL 隐式提交**，能力位标「软」）；9605 行的 `DatabaseBrowserScreen.kt` 拆成 10 个文件，平台无关部分上移 `:shared`（新增 `sql/` 包） |
| v2.27 | **真窗口实测揪出的 7 个建表缺陷** —— ① 建表时**主键写在哪由方言自报**：早先按「有没有自增列」判断，把 DuckDB / MySQL 的主键一并弄丢（MySQL 直接建不出表，DuckDB 建出一张没主键的表）；② 建表是**一条** CREATE 带全部列（逐列发的话第二列必然 `Table 'x' already exists`）；③ `createTable` 里 `runDdl(...) ?: 失败文案` 的反向 elvis 把「成功」翻译成「失败」——表建出来了，界面却报建表失败；④ 保存失败时弹窗**不显示错误**（`state.error` 被 remember 成快照，key 不变就停在初始值）；⑤ 读回表结构丢**自增标记**（各方言 `listColumns` 都不吐它，改为从 JDBC `IS_AUTOINCREMENT` 补）；⑥ 树的表列表是会话初期快照（展开库不重拉，且 `refreshTables` 还会把树折叠掉）；⑦ 读回表结构时「长度」照搬了引擎的**十进制位数**（`BIGINT` 显示成「长度 19」） |
| v2.28 | **鼠标停在可拖动的分隔条上，指针仍是默认箭头** —— 拖拽功能「存在但没人发现」。`DragHandle` 新增 `hoverIcon` 参数：`:shared` 只决定**什么时候**用拉伸指针，图标本身由平台侧造（桌面端新建 `PointerIcons.kt`，用 `java.awt.Cursor.E_RESIZE_CURSOR` —— AWT 里**没有** `EW_RESIZE_CURSOR` 这个常量）；且只在**真的拖得动**时才显示箭头（`canPaneWidthMove`），顶死在两端还摆「可拖」箭头是在骗用户 |

## 许可证

[MPL 2.0](./LICENSE)。所有源文件都带 SPDX 式的 MPL 声明头。
