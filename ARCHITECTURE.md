# sundays — Kotlin 数据库管理端架构导航（v2.32）

> **本文件只做整体介绍与模块导航。** 契约、边界、设计理由都在对应类/函数的 KDoc 里，
> 各模块文档也只写大纲 —— **改代码前先读源码注释，那是唯一权威处**。

---

## 1. 项目定位

`sundays` 是一个**全 Kotlin** 实现的桌面数据库管理工具：

- **后端引擎**：无头数据库算力引擎，既能作为 **gRPC 服务端**（默认 `:50051`）运行，
  也提供**进程内 facade**
- **调用层**：统一的 `EngineClient` 接口 —— 同一份调用代码可跑在同进程或跨进程 gRPC 上
- **前端**：**Kotlin Multiplatform + Compose Multiplatform Desktop** 桌面应用
  （Windows / Linux / macOS 三端共享 Skia 渲染）
- **整条工具链 Kotlin 一统**：共享 protobuf 生成器 + `kotlinx-coroutines`，无语言边界、无桥接开销

方言通过 `ServiceLoader` 动态加载，各方言模块之间**没有编译期依赖**，可以单独替换或新增。
界面支持 6 种语言，引擎的错误消息跟随界面语言。

---

## 2. 调用层架构

调用方只面向 **`EngineClient` 接口**（`com.langbiantianya.client.EngineClient`）编程，
**不感知引擎在同进程还是跨进程**：

| 模式 | 实现 | 模块 | 通道 | 适用场景 |
| --- | --- | --- | --- | --- |
| **Direct 直接模式（默认）** | `IdbEngine` | `engine/` | 同 JVM 直接方法调用 | Compose UI 同进程，零序列化、零子进程 |
| **gRPC 模式** | `GrpcEngineClient` | `engine-grpc-client/` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、子进程隔离、远程调试 |

两条实现共用 `:engine-protocol` 的同一份 `Request` / `Response` 消息与同一个
`RequestDispatcher` 路由集，因此 envelope options、流式分帧、错误包装**逐帧一致**。
`GrpcEngineClientTest` 用「同一 Request 走两条路径、断言 Response 列表相等」把这条平价钉住。

**模块依赖**（无环）：

```
desktopApp ──▶ shared            （UI 组件，与引擎无关）
desktopApp ──▶ engine-protocol ──api──▶ engine           （同进程实现 + gRPC 服务端）
                        │                │
                        │                └──▶ dialect-contract ──▶ i18n
                        │            （连接表单契约：方言声明 / 前端渲染）
                        └───────api──▶ engine-grpc-client  （跨进程实现）
```

**典型调用栈**：

```
Direct 模式（默认）:
[Compose UI] ── EngineClient.handle(req) ─→ [RequestDispatcher] ─→ [Handlers] ─→ [Dialects] ─→ [DB]

gRPC 模式:
[KMP Desktop] ── EngineClient.handle(req) ─→ [GrpcEngineClient] ── gRPC over IPC ─→ [IdbEngineImpl] ─→ …
[任意语言]   ── gRPC stub ─→ [IdbEngineImpl] ──▶ [RequestDispatcher] ─→ [Handlers] ─→ [DB]
```

**装配点只有一个**：[`main.kt`](./desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt)
的 `createEngineClient()`，读 `-Dsundays.engine.endpoint`（`host:port` / `unix://path` /
`pipe:name`）决定实现。端点格式错误在装配时直接抛出，**不会静默退回本地**。

**写路径的一条总约束：连接的归属者决定它何时被释放。** 不带会话 id 时 handler 从池里借用
后归还；带会话 id（`Request.session_id`）时这条连接归 `TransactionManager` 所有，直到
`COMMIT` / `ROLLBACK`。搞错这点会让事务静默退化成「只有最后一条语句」。

契约细节、流式响应约定、envelope options 见
[`engine-protocol/README.md`](./engine-protocol/README.md)。

---

## 3. 国际化（i18n）

支持 **简体中文 / 繁體中文 / English / Русский / 日本語 / Deutsch**，文案表集中在零依赖的
`:i18n` 模块。

**为什么要单独建模块**：文案要同时喂给 `:shared`（UI 渲染）和 `:engine`（错误消息上屏），
而这两个模块互相看不见 —— `:i18n` 是它们唯一的交集，做成叶子模块后谁都能无环依赖。

两条值得记住的约束：

1. **键是枚举而非字符串**，拼错编译期即报；各语言的译文表拆成独立文件，互不干扰 diff，
   多个翻译任务可以并行各写各的。取值的 `when` 没有 `else`，加语言时编译器会报不完整。
2. **「这句话是不是报错」是状态，不是文案。** 严重级别必须走结构化字段，不许用
   `notice.startsWith("导出失败")` 这类解析文案的判断 —— 翻译之后这个判断恒为 false，
   错误会被静默染成成功色。

界面语言要跟着请求走到引擎进程（经 `RequestOptions.language` → `EngineLanguageContext` 上下文
元素），否则引擎抛的英文报错会在德语界面里上屏。取值 API、优先级链与跨进程传递的实现见
[`i18n/README.md`](./i18n/README.md) 与该模块的 KDoc。

---

## 4. 模块导航

| 模块 | 入口文档 | 内容主题 |
| --- | --- | --- |
| **`engine-protocol/`** | [`README.md`](./engine-protocol/README.md) | 调用层契约：`EngineClient` 接口 / proto 协议 |
| **`engine/`** | [`README.md`](./engine/README.md) · [`ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | CLI / handler 路由矩阵 / 流式响应 / 连接池与事务 / 导入导出 / IPC Transport / 插件加载 / gRPC 服务端 |
| **`engine-grpc-client/`** | [`README.md`](./engine-grpc-client/README.md) | `GrpcEngineClient` 跨进程实现 / 端点配置（TCP / UDS / pipe）/ 端到端测试 |
| **`api/`** | [`README.md`](./api/README.md) | `DatabaseDialect` SPI / `ConnectionType` / `DialectCapability` / 扩展自定义方言流程 |
| **`i18n/`** | [`README.md`](./i18n/README.md) | 六语言文案：键枚举 / 译文表 / 简繁判定 / 复数规则 / 降级链 |
| **`dialect-contract/`** | [`README.md`](./dialect-contract/README.md) | 连接表单契约：`ConnectionFormSpec` / `ConnectionField` / `AuthMethod` —— 方言自描述「连得上要填什么」，`:api` 声明、`:shared` 渲染 |
| **`dialect-mysql/`** | [`README.md`](./dialect-mysql/README.md) | MySQL 方言特性 / SQL 危险关键词 / 已知约束 |
| **`dialect-postgresql/`** | [`README.md`](./dialect-postgresql/README.md) | PostgreSQL 方言特性 / search_path / DDL 事务 / 已知约束 |
| **`dialect-h2/`** | [`README.md`](./dialect-h2/README.md) | H2 嵌入式 / 集成测试 fixture / 已知约束 |
| **`dialect-duckdb/`** | [`README.md`](./dialect-duckdb/README.md) | DuckDB 嵌入式 OLAP / Excel 预转换 / FK table-rebuild / 已知约束 |
| **`dialect-sqlite/`** | [`README.md`](./dialect-sqlite/README.md) | SQLite 嵌入式 / INTEGER PRIMARY KEY / FK table-rebuild / ATTACH/DETACH / 已知约束 |
| **`shared/`** | [`README.md`](./shared/README.md) · [`ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | CodeEditor / DataTable / ConnectionManagerScreen / **方言元数据表** / 表结构编辑弹窗 / SQL 文本工具 / **浏览器 chrome 与通知中心** / 右键菜单 / 主题与视觉规范 / 与引擎解耦的边界 |
| **`desktopApp/`** | [`README.md`](./desktopApp/README.md) · [`ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | Direct 模式集成 / 顶层导航 / 连接管理 / 数据库浏览屏 / 生命周期 |
| **`packaging/flatpak/`** | [`README.md`](./packaging/flatpak/README.md) | Flatpak 选型结论索引 |

> **在建：数据分析模式** —— 一个**独立于「连接管理 → 数据库浏览」**的新顶层模式：
> 远程数据源**恒为只读**（六层防线，不是可关的开关），计算引擎默认 **DuckDB**（可切
> Hive / Impala），当前阶段做 **Excel 报表**，后续迭代接入 AI 做智能分析。
> **连接配置 / 连接管理器 / 设置页与浏览模式共用同一份**（不另做一套），
> **通用控件也全部复用 `:shared` 的 `commonMain`**（分析模式只新增 5 个组件）。
> 进度：M2（只读地基：协议 + 引擎闸门 + 四条只读路由 + 顶层导航）**已落地**，
> M4'（分析工作台：本地 DuckDB 工作区 + 拉取链路 + 结果表）**八个分片全部落地** ——
> 工作区与状态机（第 4 片）、**拉取链路与三段进度**（第 5 片）、
> **数据源树与只读结果表 / 编辑器**（第 6 片；那棵树后来与浏览模式合成一棵，见下）、
> **拉取改走 DuckDB 联邦直查**（第 7 片，**去掉 CSV 中转**）、
> **原生扫描器作为可选加速**（第 8 片，默认关；走不通降级回纯 Java 表函数）；
> 另有两处界面收敛：**去掉分析工作台的「数据源」下拉框**（源只在左树选）、
> **顶栏去掉「连接管理 / 数据分析」chip**（分析模式的入口挪到连接管理的空闲面板）；
> 以及**启动首屏** —— 进应用先选一个模式（连接管理 / 数据分析）进去；
> 分析工作台的**树只显示 DuckDB 工作区**（保存的连接配置不进那棵树）；
> 分析工作台**没有独立工具条**了 —— 「▶ 运行」挪到编辑器的「格式化」旁边，
> 「⊘ 只读」徽标也去掉了（远端恒为只读的保证在引擎与方言层）；
> **两棵树合成一棵** —— 分析页的树与浏览模式的对象树**共用同一个组件**
> （`:shared` 的 `tree/ObjectTree.kt`），形状统一为「库 → 表 → 字段」，
> 分析页因此也能**展开到字段**；
> **两棵树的面板外壳也共用同一个组件** —— `:shared` 的 `tree/TreePaneShell.kt`：
> 展开态是「面板 + 可拖宽的分隔条」，折叠态收成贴到屏幕左边的 **44dp 窄轨**
> （一个展开按钮 + 一枚图标），把宽度**让给右边**；
> 浏览模式此前能拖宽、分析模式是写死的 240dp，现在两侧手感一致；
> 折叠时树整体从组合里移除，因此**滚动状态被提到面板之外**
> （浏览模式在会话里、分析模式在屏幕层）—— 展开后树还停在原来那个位置；
> **两个模式的 SQL 编辑器高度也能拖了** —— 编辑器与结果区之间那条缝是
> `:shared` 的**横向分隔条**（`HorizontalDragHandle`，与竖向的 `DragHandle`
> 是同一份实现，手势 / 画线 / 指针只有一份）：往下拖编辑器变高，
> 双击复位回默认比例。**结果区的下限同时是拖拽的硬边界**
> （浏览模式 180dp、分析模式 140dp）—— 数据看不见就等于白查；
> 高度是**窗口级偏好**（浏览模式提到屏级，分析模式在屏幕层）——
> 工作台被拆掉又重建后，拖好的高度还在；
> **分析模式能自己加数据源** —— 树头上一个「+」（分析模式专属的对话框，
> **不共用**浏览模式的「添加连接」）：远程库先用**对应驱动预览**、可多选可单选，
> 再用 **DuckDB 官方扩展优先**接入（走不通退回 JDBC）；文件 / 文件夹
> （parquet / csv / Excel / jsonl）直接用 DuckDB 自己的读法建**视图**。
> 加完的源**直接存在工作区 DuckDB 里**（分析模式自己的清单），
> 树随之变成「工作区的各个 schema」。
> 设计文档（只读防线、连接与组件通用化、报表模型、迭代拆分、风险登记）与可交互 UI 设计图见
> [`docs/analysis-mode/README.md`](./docs/analysis-mode/README.md)。
>
> **M1 第一片已落地**（通用化的「机制」部分）：前端方言元数据收进**一张表**
> （`shared/.../connection/DialectMetadata.kt`，原先散落成**八处**硬编码 `when`）、
> SQL 方言档位**注册表化**（语言与 formatter 成对注册）、`WinControls` 补 `WinDropdown`、
> 连接配置持久化升到 v3（新增 `authMethod` / `properties`）与 `ConnectionType.CUSTOM_URL`。
> **方言本体（Hive / Impala）留到 M3 与引擎方言同一个提交落地** ——
> `DialectNameContractTest` 是那道闸门：前端枚举里的每个方言，引擎都得真的实现了。

---

## 5. 方言矩阵

| 能力 / 方言 | MySQL | PostgreSQL | H2 | DuckDB | SQLite |
| --- | --- | --- | --- | --- | --- |
| **connectionType** | CLIENT_SERVER | CLIENT_SERVER | IN_MEMORY | EMBEDDED | FILE_BASED |
| **defaultPort** | 3306 | 5432 | — | — | — |
| **USERS** | ✓ | ✓ | ✓ | — | — |
| **PRIVILEGES** | ✓ | ✓ | ✓ | — | — |
| **ROUTINES** | ✓ | ✓ | ✓ | ✓ (MACRO) | — |
| **VIEWS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **INDEXES** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **FOREIGN_KEYS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **TRIGGERS** | ✓ | ✓ | ✓ | — | — |
| **MULTI_SCHEMA** | — | ✓ | ✓ | ✓ | — |
| **CROSS_DATABASE** | — | ✓ | ✓ | ✓ (ATTACH) | — (ATTACH 不暴露) |
| **EXPORT** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **DDL_TRANSACTION** | ✓ (软) | ✓ | ✓ (软) | — | — |
| **EMBEDDED_MODE** | — | — | ✓ | ✓ | ✓ |

`DialectCapability` 枚举是这份矩阵的单一事实来源 —— 不支持的能力由方言抛
`UnsupportedOperationException`，而不是静默返回空结果。各方言的详细特性与已知约束见
对应 `README.md`。

> ⚠️ **`DDL_TRANSACTION` 标了 ✓ 不等于「DDL 能回滚」。** MySQL 与 H2 都对 DDL
> **隐式提交**（矩阵里标「软」），批量改表结构中途失败时，已生效的部分留在库里。
> H2 这条是实测的：`autoCommit = false` 的连接上 `CREATE TABLE` 一执行，
> 另借连接就查得到，说明它根本没进事务 —— 回归见
> `DdlTransactionIntegrationTest.H2 does not roll DDL back`。
> 五个内置方言里真正支持事务性 DDL 的只有 **PostgreSQL**，界面上不能对别的方言
> 承诺「全成功或全不做」。

---

## 6. 快速运行

```bash
./gradlew :desktopApp:run                    # Direct 模式（构造即 bootstrap 引擎 + 所有方言）
./gradlew :desktopApp:hotRun --auto          # Hot reload

./gradlew :engine:jar                        # 引擎 standalone
java -jar engine/build/libs/idb-engine.jar --mode grpc --ipc tcp --port 50051
java -jar engine/build/libs/idb-engine.jar --mode direct

./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051   # 前端改走 gRPC
```

CLI 参数、IPC transport 切换、前端屏幕说明见
[`engine/README.md`](./engine/README.md) 与 [`desktopApp/README.md`](./desktopApp/README.md)。
测试怎么跑见 [`README.md`](./README.md#测试)，打包见 [`README.md`](./README.md#打包)。

---

## 7. 技术栈

- **Kotlin 2.4.20 / JDK 25**
- **KMP + Compose Multiplatform Desktop**（仅 `jvmMain` 单平台目标）
- **gRPC 1.83.1** + grpc-kotlin 1.5.0（Kotlin 协程服务端 + Kotlin DSL 生成）
- **protobuf-kotlin-lite 4.35.1**（DSL builder）
- **kotlinx-coroutines 1.11.0**
- **HikariCP 7.0.2**（SHA-256 缓存连接池）
- **数据库驱动**：MySQL 9.7.0 / PostgreSQL 42.7.11 / H2 2.3.232 / DuckDB 1.5.5.1 / SQLite 3.46.1.3
- **SLF4J 2.0.18 + Logback 1.5.13**
- **LuaJIT 4.1.0 + Lua 5.1~5.5**（造数引擎）
- **Apache POI 5.5.1**（Excel 流式导出 + DuckDB Excel 预转换）
- **Apache Parquet 1.17.1 + Hadoop 3.5.0**（Parquet 导出）

架构升级历史见 [`README.md`](./README.md#架构升级历史)。
