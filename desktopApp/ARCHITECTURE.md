# desktopApp — 前端架构（KMP Compose Desktop 客户端）



> **本文只回答三件事**：这个模块是干什么的、分层与依赖方向、关键设计决策的**索引**。

> 每条决策的**理由、踩过的坑、契约细节**都写在它描述的那个函数 / 类的 KDoc 上 ——

> 决策表里给出的是文件链接，点进去即是完整论述。



## 概述



`desktopApp/` 是 sundays 的**桌面客户端模块**：Kotlin Multiplatform + Compose Multiplatform，**当前仅启用 JVM Desktop 单平台目标**（macOS / Linux / Windows 共享同一份 Skia 渲染）。数据库算力由同仓的 `:engine` 提供，界面通过 `EngineClient` 接口（`:engine-protocol`）调用。



**模块边界**：`AppDestination`（导航目标枚举）、`SundaysTheme` / `SundaysPalette`（主题）、以及 `ConnectionManagerScreen` / `CodeEditor` / `DataTable` / `OnboardingScreen` / `SettingsScreen` 等 UI 都在 `:shared/commonMain` —— 它们是纯 Compose，不引用 `:engine`，可跨平台复用。本模块因此只剩「**平台窗口 + 引擎状态机接线**」，16 个源文件（见 §2）。



**未来扩展路径**：KMP 工程结构天然支持后续启用 `androidMain` / `iosMain` / `wasmJsMain`，只需新增平台特定的子进程拉起逻辑（如 Android 的 `bindService`、iOS 的 `NSXPCConnection`），业务层零修改复用。



---



## 1. 分层与依赖方向



```text

desktopApp （本模块）

   ├── main.kt                  平台窗口 + 引擎装配点 + 顶层导航接线

   ├── ConnectionSession.kt     连接会话状态机

   ├── 浏览屏 —— 按功能域拆成下列一组文件（曾经是一个 9600 行的 DatabaseBrowserScreen.kt）：

   │     ├── DatabaseBrowserScreen.kt   入口：布局 + 屏级副作用

   │     ├── DatabaseBrowserState.kt    浏览 / SQL / 造数三套状态机（不碰任何 Composable）

   │     ├── DatabaseBrowserTypes.kt   跨面板共享的小数据类型

   │     ├── BrowserChrome.kt          内存状态栏 / 通知中心 / 后台任务面板

   │     ├── BrowserToolBar.kt         工具栏 / sheet 标签 / 连接状态

   │     ├── SchemaTree.kt             对象树 + 右键菜单 + 表结构弹窗宿主（弹窗本体在 :shared）

   │     ├── TablePreview.kt           表格预览区 + 新增行弹窗

   │     ├── ExportDialogs.kt          导出弹窗 + 进度 + 导出用的纯函数

   │     ├── SqlWorkbench.kt           SQL 工作台

   │     └── GenerateWorkbench.kt      造数工作台

   ├── NativePathPicker.kt      系统路径选择器

   ├── Notifications.kt         通知中心（去重 / 已读 / 原文不截断）

   ├── PointerIcons.kt          平台专属指针图标（左右拉伸箭头）

   └── ClipboardWriter.kt       AWT 剪贴板唯一出口

        ↓ 只依赖 ↓

:shared（UI 组件 + 平台无关的逻辑）  :engine-protocol（EngineClient 契约）  :engine / :engine-grpc-client（两个实现）

```



> **留在本模块的判据只有一条**：代码引用了 proto（`com.langbiantianya.grpc.*` / `EngineClient` / `IdbEngine`）

> 或 JVM 专有 API。凡不引用它们的都归 `:shared` —— 目前已上移的有表结构编辑整套（草稿 / 差分 / 校验 / 弹窗）、

> `DangerousSql` / `SqlLiterals` 与危险语句确认框、以及 `EmptyHint` / `ColumnBadge` / `TabRenameDialog` 三个通用组件。

> **KDoc 里提到 proto 不算引用**，搬完顺手把全名改成短名，否则留下一个在 commonMain 里解析不了的悬空链接。



**反向依赖被严格禁止** —— 引擎与 `:shared` 均不感知 desktopApp 存在。



| 依赖 | 为什么必须有 |

|---|---|

| `:shared` | 全部界面组件（浏览器面板、代码编辑器、数据表格、右键菜单、主题、i18n 文案表） |

| `:engine-protocol` 的 `EngineClient` | UI 层只面向接口编程，实现选择对状态机透明 |

| `:engine` + `:engine-grpc-client` | 两个实现都在编译期可见，运行期由 [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) 的装配点二选一 |

| `protobuf-java` / `protobuf-kotlin-lite` | `:engine` 以 `implementation` 声明 protobuf/grpc，不向消费方传递编译类路径；集成层需要 typed proto 才能调 facade 的 Direct 模式 API，故显式补齐 |

| 5 个 `dialect-*` + 5 个 JDBC 驱动（`runtimeOnly`） | 方言插件与驱动随应用类路径加载，**不需要外部 `dialects/` `drivers/` 目录**；缺了引擎解析不出任何方言 |



---



## 2. 源文件与职责



| 文件 | 职责 |

|---|---|

| [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) | `main()`（Window + 主题 + 首启引导分流）、`createEngineClient()`（**唯一引擎装配点**）、`MainScreen`（顶层导航 + 三处长生命周期状态的归属 + **分析模式分支**：按能力位筛数据源）、`fetchSystemInfo` |

| [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) | 连接列表 / 向导状态 / 引擎会话状态 / 多 sheet 的**唯一持有者**；全部回调（`connect` / `disconnect` / `testConnection` / `save` / `delete`）；`analysisCapableDialects` —— 声明了 `ANALYSIS_SOURCE` 的方言集合，**来自引擎** |

| [`DialectFormRegistryFactory.kt`](./src/main/kotlin/com/langbiantianya/sundays/DialectFormRegistryFactory.kt) | 引擎的方言列表 → 前端要的**两半**（连接表单注册表 + 能力位集合）；一次 `SYSTEM.LIST_DRIVERS`、一次换算（`DialectCatalog`） |

| [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) | **分析模式的状态机** —— 与 `ConnectionSession` / `DatabaseBrowserState` **并列**（不继承、不耦合）：远端源的连接配置、当前选中源、当前 SQL 与结果、运行态、工作区文件路径。**两条会话的只读方向相反**且各只有一个执行点：远端源恒只读（`sourceConfig`）、工作区可写（`workspaceConnection`） |

| [`AnalysisSourceConnector.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSourceConnector.kt) | UI 层的 `ConnectionConfig` → 引擎的 `grpc.ConnectionConfig`（与浏览模式的 `engineConnFor` 同口径）；**不**在这里设 `read_only` —— 那是会话的唯一执行点，两处都设就又有了两个可能不一致的产地 |



**浏览屏（第二屏）** —— 原本是一个 9600 行的 `DatabaseBrowserScreen.kt`，现按功能域拆开。

切分的判据是「改一处不用在几千行里滚着找另一处」，而不是按行数平摊：



| 文件 | 职责 |

|---|---|

| [`DatabaseBrowserScreen.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserScreen.kt) | 入口：算布局、把 sheet 内容交给 `ActiveSheetContent`、把屏级共享的副作用（内存面板 / 通知中心 / 表结构弹窗 / 删表确认）挂在最外层 |

| [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) | 加载 / 标签页 / 两个工作台的**全部状态机** + `TablePreviewTab` / `SqlSheet` / `BackgroundTask` / `ColumnInfo` / `TreeTarget`。**不能**出现任何 Composable 或 Modifier —— 同一份状态机要能脱离界面直接驱动（`DatabaseBrowserFlowTest`） |

| [`DatabaseBrowserTypes.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserTypes.kt) | 跨面板共享的小数据类型（`EngineMemory` / `SheetDescriptor` / `ErrorSource` / `SqlPreviewRows`）+ `SheetDescriptor.toSummary()`（转成 `:shared` 标签条要的窄形状） |

| [`BrowserChrome.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserChrome.kt) | 堆内存状态栏与详情面板、后台任务面板 —— 只读地展示状态，不接受领域事件；另含 `formatNotificationTime`（**本地时区**的时间格式化，平台知识） |

| [`BrowserToolBar.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserToolBar.kt) | 激活 sheet 的内容编排（`ActiveSheetContent`）与连接失败横幅 —— 依赖 `DatabaseBrowserState`，留在本模块 |

| [`SchemaTree.kt`](./src/main/kotlin/com/langbiantianya/sundays/SchemaTree.kt) | 对象树渲染、右键菜单，以及由右键菜单进入的表结构弹窗宿主 |

| [`TableStructure*.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/table/TableStructureDialog.kt) | **已上移 `:shared`**（草稿模型 / 校验与差分 / 类型候选 / 编辑弹窗）；本模块只剩 `SchemaTree.kt` 里的弹窗宿主与状态机里的 DDL 提交 |

| [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) | 预览标签条、筛选排序搜索工具条、数据表格、新增行弹窗、待提交编辑条 |

| [`ExportDialogs.kt`](./src/main/kotlin/com/langbiantianya/sundays/ExportDialogs.kt) | 危险语句确认框、导出配置弹窗、导出进度弹窗、结果预览表，及其纯函数（语句切分 / 文件名生成 / proto 值展开） |

| [`SqlWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/SqlWorkbench.kt) | SQL 工作台：多语句编辑、执行、事务开关、结果区、标签重命名 |

| [`GenerateWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateWorkbench.kt) | 造数工作台：生成 Lua 脚本、执行、结果集铺表 |



| 文件 | 职责 |

|---|---|

| [`NativePathPicker.kt`](./src/main/kotlin/com/langbiantianya/sundays/NativePathPicker.kt) | 导出路径的系统选择器（不可用时安静降级） |

| **（原 `Notifications.kt`）** | **已上移 `:shared`** —— 通知中心（状态 + 面板 + 铃铛入口）整套住在 [`shared/.../notifications/`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/notifications/)，本模块只留 `formatNotificationTime`（本地时区，平台知识） |

| [`ClipboardWriter.kt`](./src/main/kotlin/com/langbiantianya/sundays/ClipboardWriter.kt) | AWT 剪贴板唯一出口，失败返回 `false` 而不是抛 |

| [`PointerIcons.kt`](./src/main/kotlin/com/langbiantianya/sundays/PointerIcons.kt) | 平台专属指针图标（左右拉伸箭头）。**`:shared` 只决定「什么时候用」，这里决定「长什么样」**；造不出来（无头 AWT）时返回 `null` 退回默认箭头，不抛 |



测试侧的清单与覆盖矩阵见 [`TEST_CASES.md` §2](./TEST_CASES.md)。



---



## 3. 关键设计决策索引



> **一格一句结论，点链接看完整论述。** 决策的理由、放弃过的方案、踩过的坑都在链接目标的 KDoc 里。



### 3.1 引擎集成



| 决策 | 结论 | 落点 |

|---|---|---|

| 默认同进程 | 未设 `-Dsundays.engine.endpoint` 时用 `IdbEngine()`：零序列化、零子进程、零 gRPC channel、零 IPC transport | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) |

| 端点非法**响亮失败** | `GrpcClientConfig.fromTarget` 抛 `IllegalArgumentException`，**不静默回落**到本地引擎 | 同上 |

| 构造即 bootstrap（幂等） | `IdbEngine()` 内部触发驱动 / 方言加载，`AtomicBoolean` 单例保护，重复构造无害 | 引擎侧 `IdbEngine` |

| 生命周期 = Window 生命周期 | Direct 绑定下 `engine` 是 JVM 内的 library 对象，不是独立进程入口 → **`onCloseRequest` 是唯一清理入口，不需要 Shutdown Hook** | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) |

| UI 层对实现无感 | `ConnectionSession` / `DatabaseBrowserState` 的构造参数类型都是 `EngineClient` | 两个状态机 |



### 3.2 窗口与导航



| 决策 | 结论 | 落点 |

|---|---|---|

| 默认 1152×720 | 原 1280×820 在 1366×768 这类笔记本上**根本放不下**，窗口比屏幕还高 | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) |

| 默认尺寸再**夹到屏幕工作区以内** | 1152×720 在 1366×768 上放得下，在本机（1536×864 物理 @125% 缩放 ⇒ 工作区只有 **659dp** 高）上**放不下** —— 窗口底边落在**屏幕外**，而向导的「下一步」正好钉在底部，**永远点不到**（应用内部滚动也救不了）。夹取在 `rememberWindowState` 的**参数**里做，不在组合里改 `state.size` —— 后者会让窗口「先大后小跳一下」 | 同上的 `fitWindowSizeToScreen` |

| 最小 1024×640 靠组合夹取 | Compose 的 `Window` **没有** `minSize` 参数，只能用 `snapshotFlow` 在组合里把尺寸夹回来 | 同上 |

| 状态机提升到 `MainScreen` | 浏览 sheet、通知中心、长生命周期 scope 都挂在这一层 —— 切目标只销毁屏幕组合，**不销毁状态** | 同上 |

| 引导页放在主题**内部** | 每次点选立刻重绘整页，预览是免费的，不需要「预览图 + 应用按钮」 | 同上 |

| 设置页里的引导是**浮层**不是替换 | 替换会让 `SettingsScreen` 离开组合、内部 `remember(category)` 销毁；更糟的是整个 `MainScreen` 重建会丢浏览 sheet | 同上 |

| 引擎在引导判定**之前**创建 | 装配点与用户是否看引导无关，提前创建避免「先看引导再连引擎」的时序耦合 | 同上 |

| 外观状态在 `SundaysTheme` **之外** | 配色由主题的 `darkTheme` 参数注入，是组合最外层；状态落在屏幕内部会出现「按钮变了、界面没变」 | 同上 |



### 3.3 浏览状态机



| 决策 | 结论 | 落点 |

|---|---|---|

| 状态机在 `MainScreen` 里 `remember`，**不在屏幕内部** | 切到连接管理再切回来时已打开的标签页不丢失，且同一份状态机可脱离 UI 直接驱动 | [`DatabaseBrowserScreen.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserScreen.kt) |

| `bindConnection` 只调 `releasePools` | 代次自增与「正在执行 / 正在加载」标志的复位**统一**由 `invalidateInFlight` 负责 —— 两处各写一半正是「切连接后转圈停不下来」的来源 | 同上 |

| `invalidateInFlight` 在**任何早退之前**执行 | 断开时连接 id 未变，`bindConnection` 会早退；若不作废，界面**永远**停在「执行中…」，重连后旧行帧还会继续写进存活的 sheet | 同上 |

| loading **先写数据、最后清** | `loading` 与数据是各自独立的快照状态，顺序反了会让「轮询 loading 再读数据」的调用方落在两次提交之间 | 同上 |

| 但**不要**改各协程的早退分支 | 判据是「代次为什么变了」而不是「早退发生在哪个函数里」；把 `refreshDatabases` 的早退补一行 loading 复位会让「断开瞬间转圈停但库列表空着」 | 同上 |

| catalog 与 schema 是**两个维度** | 库名只经 `engineConn(database = …)` 进 `ConnectionConfig.database`（参与池 key）；请求里的 `schema` 字段必须**留空** —— 写成库名会让 H2 执行 `SET SCHEMA <库名>` 而失败 | 同上 |

| 每个建池的 catalog 维度都要登记 | `activeDatabases` 是 `releasePools` 的唯一遍历来源，漏登记则关 sheet 时那个池永远不会被回收 | 同上 |

| `SQL.EXECUTE` 必须用 `handle` + `collect` | 它是流式通道，`invoke` 只收终止帧，会丢掉全部行帧 | 同上 |

| `pageSize = 0` 是流式哨兵 | 不是合法分页大小；预览固定走分页路径，故请求侧 `coerceAtLeast(1)`，分页大小下拉隐藏「全部」 | 同上 |

| 后台导出用**应用级 scope** | 「后台运行」此前挂在 `PreviewTabArea` 的 scope 上，composable 一离开组合协程就被取消 —— 那个按钮**恰恰在暗示它能活着跑完** | 同上 |

| 多语句导出 = N 个任务且**串行** | 面板存在的意义是回答「哪一条失败」；并发会把库压垮，而并发度不可控 | 同上 |

| SQL 结果区有**最小高度** | 结果区原先按 `maxHeight * 0.4f` 分高度且**没有下限** ⇒ 小窗口下数据区被压成 0 高，而标题照写着「查询结果 · 2 行」——**查了白查**。编辑器内部能滚、结果区不能，所以空间从编辑器借：结果区保底 180dp，编辑器拿剩下的 | [`SqlWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/SqlWorkbench.kt) 的 `MIN_SQL_RESULT_HEIGHT` |



### 3.4 表预览与对象树



| 决策 | 结论 | 落点 |

|---|---|---|

| 引擎侧真分页 | `DataTable` 必须传 `serverSidePaging = true`，否则它会再切一次本地页，**第 2 页起直接变成空表** | [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) |

| 标签页主键 `schema::table` | `TablePreviewTab.key` 是**唯一去重依据**，与 `TableRow.id`（行主键）不是一回事 | 同上 |

| tab 级 `requestGeneration` | 屏级代次管不到「同一张表上连着翻两页」——两次请求并发在跑，慢的第 1 页会盖掉快的第 2 页 | 同上 |

| 树面板宽度放**屏级**不放 sheet 内 | 布局宽度是**窗口级偏好**，不是 per-sheet 的；放 sheet 内会在切 sheet 时被 `remember` 一起丢掉 | 同上 |

| 宽度上限按**比例**（容器宽 × 62%） | 窗口窄时若还允许拉到 600dp，右侧工作台会被挤到没法用 | 同上 |

| 表级对象挂在**表节点**下 | 引擎侧 `IndexListRequest` / `ForeignKeyListRequest` 要 `table_name`，跟库级对象一起拉就是「每张表一次往返」 | 同上 |

| 对象类型**逐类**显示错误 | 「任意一类出错 → 整个对象区换成一行错误」会让**能正常列出的视图跟着消失** | 同上 |

| 「无」由分组自己拿列表渲染 | 用一个状态位代表「子组件渲染过没有」，状态位与实际渲染分居两处时几乎必然多打一行「(无)」 | 同上 |

| 建表 DDL 必须走引擎 | 五个方言的建表语句没有共性：PG 回填主键/UNIQUE/CHECK，SQLite 读 `sqlite_master.sql` 原文，MySQL 拼 ENGINE 与字符集。**对不上的 DDL 是静默的** | 同上 |

| 错误区顶对齐 + 可纵向滚动 | 引擎报错常是几百字符且 `Caused by` 恰在末尾；「`fillMaxSize` + 居中」在溢出时**首尾同时被裁** | 同上 |



### 3.5 导出



| 决策 | 结论 | 落点 |

|---|---|---|

| 起手 SQL = 屏幕上那张表 | 旧实现恒拼 `SELECT * FROM <表>`，于是筛出 3 行却导出全表 —— **所见非所导** | [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) |

| 起手 SQL **带库名限定** + 导出**打那张表所在的库** | 两处都得改：**只限定表名**则 PG 仍打错库（库 ≠ schema，库里没有同名表就查不到）；**只改 catalog**则 MySQL 仍可能因裸表名落在错库。限定名交给 `SqlIdentifier.tableRef` 按方言生成（MySQL → `` `库`.`表` ``；PG / H2 退回只引表名 —— 库名当 schema 用在 PG 里恒失败）。**刻意不给 schema 段**：PG 的 schema 列表按名字排序，第一个未必是 `public`，猜错比不带更糟 | 同上 + [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) 的 `tableReference()` / `exportQuery(…, catalog)` |

| 导出的 catalog = **这张表所在的库**，不是「树里第一个展开的库」 | `TablePreviewTab.schema` 这个字段名装的一直是**库名**（历史遗留，没改名）；导出请求若用 `currentSchema()`（= 第一个展开的库），两者**不是同一个**时就必然报 `Table '<别的库>.<表>' doesn't exist` | 同上 |

| 用 `effectiveWhere` 而非 `whereClause` | **表内搜索也是过滤**，用户搜到只剩匹配行、导出却带上不匹配的行同样是「所见非所导」 | 同上 |

| 编辑器直接复用 `CodeEditorWithToolbar` | 高亮、补全、格式化全部天然一致，不另造一个只会显示不能好好用的输入框 | 同上 |

| 预览**逐句**判只读 | 只看开头的话 `SELECT 1; DROP TABLE t` 会因为「第一个词是 SELECT」被放行 —— 而这正是导出对话框里最容易被粘贴出来的形态 | [`DangerousSql.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/sql/DangerousSql.kt) |

| 写语句在**切分之后、发送之前**剔掉 | 导出是把查询结果落成文件，用户点了个叫「导出」的按钮却改了数据是不能接受的 | [`ExportDialogs.kt`](./src/main/kotlin/com/langbiantianya/sundays/ExportDialogs.kt) |

| 切分用 `SqlScriptSplitter` | `split(";")` 会把字符串字面量、注释、PG 美元引用里的分号当成边界 | 同上 |

| 弹窗的 `presetSql` 用 `";\n"` 拼接 | 用 `"\n"` 拼会**丢分号**，`SqlScriptSplitter` 再切一次切不开，两行被当成一条整段发给引擎 | 同上 |

| 路径选择器 + 文本框**两条路都留** | 无头 / CI 弹不出对话框、UNC / 网络路径只能照粘 | [`NativePathPicker.kt`](./src/main/kotlin/com/langbiantianya/sundays/NativePathPicker.kt) |

| 进度走 **indeterminate** | 引擎的进度帧只有 `exported_rows` 没有总数，前端也不能拿预览行数凑百分比（预览带过滤、导出的是整表）。**报假的百分比比不报更糟** | [`ExportDialogs.kt`](./src/main/kotlin/com/langbiantianya/sundays/ExportDialogs.kt) |

| 没有「取消」按钮 | 取消要一路传到引擎子进程，而那条链路是本项目最不可靠的一段 —— 与其摆一个按了没反应的按钮，不如给一个**一定做得到**的动作 | 同上 |

| 成功提示走 `notice` 而不是 `error` | `error` 的渲染分支是把**整块表格**换成「读取失败」面板：导成功了显示「读取失败」、导失败了也显示「读取失败」 | 同上 |



### 3.6 连接管理



| 决策 | 结论 | 落点 |

|---|---|---|

| 向导状态是**三字段原子更新** | 分两次赋值会有 recomposition 间隙 NPE 的崩溃风险 | [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) |

| 快速连接**不持久化** | `QUICK_CONNECT` 流程最后一步是「连接」而非「保存」，仅设为当前选中并直接连库 | 同上 |

| 「测试连接」是**探测**，探测完必须把池还回去 | 引擎侧 `testConnection` 会建（或复用）HikariCP 池，「测过了」不等于「连上了」—— 否则保存后按钮是「断开」，用户点它想连上却拿到断开 | 同上 |

| 探测时**不动**用户已有的活会话 | 「本来就连着则保持不动」，不能顺手把用户的会话掐掉 | 同上 |

| 状态回填前 `bumpStatusGeneration` | 探测期间用户可能已经点了连接 / 断开，不作废它晚到的结果会把探测结论覆盖掉 | 同上 |

| 删除连接要二次确认 | 这个界面**可以纯键盘走完**，而焦点停在按钮上时 Enter 的语义就是「点它」 —— 不是「手滑才会遇到」 | `:shared` 的 `ConnectionManagerScreen` |

| 编辑字段变化时先释放旧池 | 池按字段 hash 缓存，不释放就留下永远取不到的僵尸池 | [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) |

| 连接池归属按 catalog | 浏览另一个库会用到另一份 proto config（`database` 参与池 key），连接管理页的「断开」只释放它自己那份 | [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) |



### 3.7 通知与剪贴板



| 决策 | 结论 | 落点 |

|---|---|---|

| 通知中心**屏级共享**不是 per-sheet | 连接 A 的导出失败、连接 B 的 SQL 报错，按连接分开就断了「刚才发生了什么」这条线 | [`shared/.../notifications/NotificationCenter.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/notifications/NotificationCenter.kt) |

| 错误靠屏级**对账**而不是逐点埋点 | 报错散落在五处，每处埋一次推送等于把「什么算一次错误」的判断散到五个文件里 | [`BrowserChrome.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserChrome.kt) |

| 去重键 = 结构标识 **+ 错误文本** | 连接失败是**持续状态**（每次重组都在），没有去重会刷出几十条一模一样的通知；但「`Table 'a'` 变成 `Table 'b'`」是新的一件事，必须报 | [`shared/.../notifications/NotificationCenter.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/notifications/NotificationCenter.kt) |

| 「什么算同一次」只能有**一个产地** | 两边算的 key 不是同一个时，每轮对账都把来源 forget 掉再当新的重推，去重形同虚设 | 同上 |

| 详情**原文不截断** | 引擎错误常是 `ORA-00933: table or view does not exist` 这类，省略号一截断就失去可诊断性 | 同上 |

| 通知中心的**时间格式化由调用方注入** | 「本地时区里的今天几点」是平台知识（`commonMain` 没有 `Calendar` 也没有时区转换）。为此引入 `kotlinx-datetime` 不值得 —— 与 `ClipboardWriter` / `NativePathPicker` 一样，平台能力留在平台侧 | [`BrowserChrome.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserChrome.kt) 的 `formatNotificationTime` + `shared` 面板的 `timeFormatter` 参数 |

| 剪贴板是**注入参数**不是直接调 `ClipboardWriter` | 本机 UI 测试其实**是有头**的（能写能读），直接调真剪贴板会让「无头必然失败」这个假设**本地绿、CI 红** | [`SchemaTree.kt`](./src/main/kotlin/com/langbiantianya/sundays/SchemaTree.kt) |

| 复制失败推 ERROR 通知 | 写失败时界面**完全没变化**，用户看到的是「我点了菜单项，什么都没发生」 | 同上 |

| `ClipboardWriter` 捕获 `Throwable` | 无头的 `HeadlessException`、剪贴板被占的 `IllegalStateException`、AWT 未初始化的 `AWTError`（是 `Error` 不是 `Exception`）三种都不该带走主流程 | [`ClipboardWriter.kt`](./src/main/kotlin/com/langbiantianya/sundays/ClipboardWriter.kt) |

| 空串直接返回 `false` | 「复制成功但剪贴板没变」比明确失败更糟 | 同上 |



### 3.8 WHERE 字面量



| 决策 | 结论 | 落点 |

|---|---|---|

| 必须转义（`'` 翻倍） | `DATA.LIST` 的 `where` 收的是**裸 SQL 片段**、没有占位符，搜索词是用户输入 | [`SqlLiterals.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/sql/SqlLiterals.kt) |

| 刻意**不**转义 `%` 与 `_` | 保留它们等于允许用户写通配查询 —— 那是特性不是漏洞 | 同上 |

| CAST 目标类型**逐方言** | MySQL 的 `CAST` 不接受 `VARCHAR`；换成 `CHAR` 在 PG 上会**静默截断成 1 个字符**，比报错更难发现 | 同上 |

| 搜索谓词必须带**列名** | 原先只返回 `"LIKE '%词%'"` —— 一个没有左操作数的片段，发出去的 SQL 在 H2/SQLite/PG 上全部语法错误 | 同上 |

| 排序不在这层管 | 引擎侧 `validateOrderBy` 已经挡了，前端再拼一套规则只会与它漂移 | 同上 |



### 3.9 数据分析模式的接线

> 界面本体在 `:shared`（`analysis/` + `navigation/TopNavBar.kt`），
> 这一层只做**装配**：把引擎侧的能力位换算成数据源、把导航目标分派到各屏。

| 决策 | 结论 | 落点 |

|---|---|---|

| `ANALYSIS` 是**并列的顶层目标**，不是浏览模式的子页 | 它有自己的会话（源会话 + 本地工作区），与浏览模式那套「按连接开 sheet」的模型不同；硬塞进 `DATABASE` 会让两套生命周期互相牵扯 | [`AppDestination.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/navigation/AppDestination.kt) |

| 顶层导航条**只在并列的顶层目标**渲染 | `CONNECTIONS` 与 `ANALYSIS` 渲染；浏览屏（`DATABASE`，下钻页）与设置页（`SETTINGS`，覆盖页）**不渲染** —— 给下钻页一个「切换模式」的入口，会让人以为能从分析屏直接跳到「某个连接的浏览页」，而那件事的语义是「选一个连接」 | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) |

| 分析屏**豁免**浏览模式那条自动切屏 | 浏览模式有「sheet 集合变化就自动切屏」的 `LaunchedEffect`；分析模式与浏览模式是两套独立会话，那条副作用会把分析屏抢走 | 同上 |

| 数据源按**引擎下发的能力位**过滤 | 判据是 `session.analysisCapableDialects`（引擎的 `DialectInfo.capabilities` 里带 `ANALYSIS_SOURCE` 的方言）。前端自己写一份「MySQL/PG 可分析」的名单就是**第二个真相源** —— 引擎改了声明而前端没改，界面就会给出「点进去必然失败」的入口 | 同上 + [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) |

| 能力位**一次查询、一次换算** | 连接表单规格与能力位都来自**同一次** `SYSTEM.LIST_DRIVERS`（`DialectCatalog`）；分两次拉会让两个快照**不在同一时刻** —— 方言插件热插拔时前后端看到的方言集合可能对不上 | [`DialectFormRegistryFactory.kt`](./src/main/kotlin/com/langbiantianya/sundays/DialectFormRegistryFactory.kt) |

| 桌面端直接引用**引擎侧同一个**能力位枚举 | `desktopApp` 加了 `implementation(project(":api"))` 就是为了用 `DialectCapability.ANALYSIS_SOURCE` —— 比对的是**枚举名**（下标会随枚举增删而漂移） | `desktopApp/build.gradle.kts` |

| 分析会话**放在 `MainScreen` 这一层** | 放在 `ANALYSIS` 分支里的 `remember` 会随顶层页切换被销毁 —— 用户选好的数据源、写了一半的 SQL 都会丢。而那恰恰是分析模式里用户攒下来的东西 | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) + [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |

| 源列表**不进会话**，只进**连接配置** | 源列表是「连接列表 × 可分析能力位」的**派生结果**；派生数据再存一份，就多一个可能对不上的副本（连接删了、引擎改了能力位声明，存的那份不会自己跟着变）。会话只收派生不出来的部分：带凭据的配置、选中项、SQL、结果 | 同上 |

| 两条会话的**只读方向相反**，各只有一个执行点 | 远端源 `read_only = true`（`AnalysisSession.sourceConfig`）、工作区**不**带（`workspaceConnection`）。两边都设就又有了两个可能不一致的产地；而且写反了**都不会立刻报错**（工作区带只读只是「建表失败」，远端漏只读只是「还能写」），所以各有断言钉住 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |

| 只读**用 `toBuilder()` 复制**后再设 | 原对象是连接管理器里的那一份，在**浏览模式**里是可写的；就地改掉，就把浏览模式也废了。只读是**这次会话**的性质，不是「这条连接是什么」的性质 | 同上 |

| 工作区放在**应用配置目录**（`~/.config/sundays/workspace.duckdb`） | 与 `connection.json` 同处，不另立一套目录规则 —— 「应用的文件都在哪」多一个答案，清理 / 备份 / 迁移就要记得两处 | 同上 |

| DuckDB 的上级目录**自己建** | DuckDB 建库时**不会**自动创建上级目录 —— 目录不存在就直接失败；而用户第一次打开分析模式时，那个目录**一定**不存在 | 同上（`ensureWorkspaceDir`） |


---



## 4. 状态归属速查



| 状态 | 持有者 | 跨 pane 切换 | 切连接 |

|---|---|---|---|

| SQL sheet 列表 / 选中项 / 文本 / 光标 / 滚动 / 执行结果 / 结果区视图 | `DatabaseBrowserState.sqlSheets[i]` | 保持 | 整体复位为单个空 sheet |

| SQL 编辑器方言档位 | `currentConnection.dialect` → `sqlDialectProfile()` | 保持 | 换档位 |

| 造数脚本与统计、运行态、结果区视图 | `generateScripts[i]` / `generate*` | 保持 | 复位为单个默认模板 |

| 表预览标签页（列 / 行 / 分页 / 代次） | `DatabaseBrowserState.tabs[i]` | 保持 | 清空 |

| 库列表 / 展开状态 / 表缓存 | `DatabaseBrowserState`（per-sheet） | 保持 | 清空 |

| **树面板宽度** | `DatabaseBrowserScreen`（**屏级**） | 保持 | 保持（窗口级偏好，不落盘） |

| **后台任务列表** | `DatabaseBrowserState`（per-sheet） | 保持 | 随 sheet 生命周期 |

| **通知中心** | `MainScreen`（**屏级**） | 保持 | 保持 |

| **长生命周期 `CoroutineScope`** | `MainScreen` | 保持 | 保持 |



归位时机：切连接 → 跨连接无意义，整体复位；重新执行 → 页码回第 1 页、清选中行与统计（SQL 只影响**当前 sheet**）。



---



## 5. 测试覆盖



浏览器、SQL 工作台、导出、连接向导等全部能力都有可断言的回归测试。**覆盖矩阵**（哪个测试类覆盖什么能力）见 [`TEST_CASES.md` §2](./TEST_CASES.md)；全局测试方法论（为什么用 `runComposeUiTest`、为什么两个方言都跑、断言打在哪）见 [`TEST_CASES.md` §1](./TEST_CASES.md)。



---



## 6. 相关文档



| 文档 | 内容 |

|---|---|

| [`FEATURES.md`](./FEATURES.md) | 能力总览（三栏）、与 DataGrip & Navicat 的差距矩阵、路线建议 |

| [`TEST_CASES.md`](./TEST_CASES.md) | 测试用例（编号全在 test 函数 KDoc 上）+ 覆盖矩阵 |

| [`README.md`](./README.md) | 运行 / 打包 / 引擎端点属性 |

| [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | `DataTable` / `CodeEditor` / `ConnectionManagerScreen` 的实现细节 |

| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade / Dispatcher / Pool / Loader） |

| [`../engine-protocol/README.md`](../engine-protocol/README.md) | 调用层契约 `EngineClient` |

| [`../engine-grpc-client/README.md`](../engine-grpc-client/README.md) | `GrpcEngineClient` / `GrpcClientConfig` |

