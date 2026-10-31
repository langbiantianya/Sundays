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

   │     ├── SchemaTree.kt             对象树 + 右键菜单的菜单项 + 表结构弹窗宿主（弹窗本体在 :shared）

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

| [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) | `main()`（Window + 主题 + 首启引导分流）、`createEngineClient()`（**唯一引擎装配点**）、`MainScreen`（顶层导航 + 三处长生命周期状态的归属 + **分析模式分支**：按能力位筛数据源 + **启动首屏分支**：选一个模式进去）、`fetchSystemInfo` |

| [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) | 连接列表 / 向导状态 / 引擎会话状态 / 多 sheet 的**唯一持有者**；全部回调（`connect` / `disconnect` / `testConnection` / `save` / `delete`）；`analysisCapableDialects` —— 声明了 `ANALYSIS_SOURCE` 的方言集合，**来自引擎** |

| [`DialectFormRegistryFactory.kt`](./src/main/kotlin/com/langbiantianya/sundays/DialectFormRegistryFactory.kt) | 引擎的方言列表 → 前端要的**两半**（连接表单注册表 + 能力位集合）；一次 `SYSTEM.LIST_DRIVERS`、一次换算（`DialectCatalog`） |

| [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) | **分析模式的状态机** —— 与 `ConnectionSession` / `DatabaseBrowserState` **并列**（不继承、不耦合）：远端源的连接配置、当前选中源、当前 SQL 与结果、运行态、工作区文件路径、**拉取进度与终态**、**数据源树与展开态**、**登记在册的数据源名**与**删除数据源的终态**。**两条会话的只读方向相反**且各只有一个执行点：远端源恒只读（`sourceConfig`）、工作区可写（`workspaceConnection`） |

| [`AnalysisSourceConnector.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSourceConnector.kt) | UI 层的 `ConnectionConfig` → 引擎的 `grpc.ConnectionConfig`（与浏览模式的 `engineConnFor` 同口径）；**不**在这里设 `read_only` —— 那是会话的唯一执行点，两处都设就又有了两个可能不一致的产地 |

| [`AnalysisPuller.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisPuller.kt) | 拉取链路的**契约**（接口）—— 抽出来是为了让会话的终态映射（完成 / 取消 / 失败）能被**确定性地**测 |

| [`EngineAnalysisPuller.kt`](./src/main/kotlin/com/langbiantianya/sundays/EngineAnalysisPuller.kt) | 拉取链路的**真实现**：一条 `ANALYSIS.PULL_TABLE` 路由 —— 引擎侧是 **DuckDB 联邦直查**（`CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>`），**没有导出、没有 CSV、没有临时文件**；另有 `cancelPull` 走 `SYSTEM.CANCEL` 叫停引擎那边正在跑的拉取 |



**浏览屏（第二屏）** —— 原本是一个 9600 行的 `DatabaseBrowserScreen.kt`，现按功能域拆开。

切分的判据是「改一处不用在几千行里滚着找另一处」，而不是按行数平摊：



| 文件 | 职责 |

|---|---|

| [`DatabaseBrowserScreen.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserScreen.kt) | 入口：算布局、把 sheet 内容交给 `ActiveSheetContent`、把屏级共享的副作用（内存面板 / 通知中心 / 表结构弹窗 / 删表确认）挂在最外层 |

| [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) | 加载 / 标签页 / 两个工作台的**全部状态机** + `TablePreviewTab` / `SqlSheet` / `BackgroundTask` / `ColumnInfo` / `TreeTarget`。**不能**出现任何 Composable 或 Modifier —— 同一份状态机要能脱离界面直接驱动（`DatabaseBrowserFlowTest`） |

| [`DatabaseBrowserTypes.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserTypes.kt) | 跨面板共享的小数据类型（`EngineMemory` / `SheetDescriptor` / `ErrorSource` / `SqlPreviewRows`）+ `SheetDescriptor.toSummary()`（转成 `:shared` 标签条要的窄形状） |

| [`BrowserChrome.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserChrome.kt) | 堆内存状态栏与详情面板、后台任务面板 —— 只读地展示状态，不接受领域事件；另含 `formatNotificationTime`（**本地时区**的时间格式化，平台知识） |

| [`BrowserToolBar.kt`](./src/main/kotlin/com/langbiantianya/sundays/BrowserToolBar.kt) | 激活 sheet 的内容编排（`ActiveSheetContent`）与连接失败横幅 —— 依赖 `DatabaseBrowserState`，留在本模块 |

| [`SchemaTree.kt`](./src/main/kotlin/com/langbiantianya/sundays/SchemaTree.kt) | 对象树渲染、**右键菜单的菜单项**（复制名称 / 复制引用 / 复制建表 DDL / 编辑表结构 / 删除表 / 新增表），以及由右键菜单进入的表结构弹窗宿主。菜单的**机制**（状态 / 弹层 / 定位 / 关闭）**已上移 `:shared`** 的 `tree/TreeContextMenu.kt` —— 分析模式那棵树用的是同一份 |

| [`TableStructure*.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/table/TableStructureDialog.kt) | **已上移 `:shared`**（草稿模型 / 校验与差分 / 类型候选 / 编辑弹窗）；本模块只剩 `SchemaTree.kt` 里的弹窗宿主与状态机里的 DDL 提交 |

| [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) | 预览标签条、筛选排序搜索工具条、数据表格、新增行弹窗、待提交编辑条 |

| [`ExportDialogs.kt`](./src/main/kotlin/com/langbiantianya/sundays/ExportDialogs.kt) | 危险语句确认框、导出配置弹窗、导出进度弹窗、结果预览表，及其纯函数（语句切分 / 文件名生成 / proto 值展开） |

| [`SqlWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/SqlWorkbench.kt) | SQL 工作台：多语句编辑、执行、事务开关、结果区、标签重命名；**编辑器高度可拖**（`HorizontalDragHandle`，双击复位） |

| [`GenerateWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateWorkbench.kt) | 造数工作台：生成 Lua 脚本、执行、**造数输出面板**（**实时日志** + 底部一行**状态行**，合成一块）、结果集铺表；**编辑器高度可拖**（`HorizontalDragHandle`，双击复位） |

| [`GenerateLog.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateLog.kt) | 造数日志的**数据模型与纯函数** —— 日志条目（脚本下标 / 表 / 行号 / 列值 / 失败原因）、列值的显示文本（按列名排序、NULL、超长截断）、「一帧进度 → 一行日志」的映射。上界常量（保留行数 / 成批丢的批量）也在这里 |



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
| **编辑器的高度是拖出来的，不是比例算出来的** | 60/40 的比例是**编译期**定死的，而「写长 SQL 时编辑器要够高」与「结果只有两行时编辑器该矮」这两种诉求打架，用户没有任何办法调。现在编辑器与结果区之间那条缝是 `HorizontalDragHandle`（`:shared` 的横向分隔条）：往下拖编辑器变高，双击复位回默认比例。**结果区的 180dp 下限同时是拖拽的硬边界** —— 编辑器最多长到「总高 − 180dp」，数据不会被拖没；且窗口很矮时**编辑器的下限要让位给结果区的下限**（编辑器矮一点只是少显示几行，结果区矮一点就看不见数据了） | [`SqlWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/SqlWorkbench.kt) 的 `SQL_EDITOR_DRAG_HANDLE_TAG` / `MIN_SQL_RESULT_HEIGHT` |
| 编辑器高度是**窗口级偏好** | 写在 `ActiveSheetContent` 里的话，那棵组合一旦被拆掉重建（关掉最后一个 sheet 再重开）高度就复位了 —— 「在 A 连接把编辑器拉高，关掉再打开又缩回去」。所以它与**树面板宽度**同一层：持有在 `DatabaseBrowserScreen` | [`DatabaseBrowserScreen.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserScreen.kt) 的 `sqlEditorHeight` |
| 造数日志的数据源是**引擎已经在发的进度帧** | `DATA.GENERATE` 的流里每条 INSERT 回一帧 `gen_progress_frame`（表名 / 该脚本内的行号 / 这一行的实际数据）—— 前端此前只拿它更新计数器，那些信息其实早就够拼出一份日志。**引擎一行代码都不用改**；协议里没有独立的日志消息，Lua 沙箱里也没有 `log()` 辅助函数 | [`GenerateLog.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateLog.kt) 的 `generateLogEntryOf` · [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) 的 `executeGenerate` |
| **每条 INSERT 一行**，不是每张表一行 | 「每张表一行」只剩一个总数，造数出错时（某个脚本写了别的表、或者数据类型不对）**看不出是哪一行出的问题**；而造数这种「批量写脏数据」的场景最需要的就是逐行看得见**写进去的是什么** | 同上 |
| 日志**有界**，且丢掉的行数**说出来** | 十万行造数不能把界面撑爆；但**静默截断的日志等于伪造了一份完整的日志** —— 用户以为看到了全部 5000 行，其实只看到了最后 1000 行。所以丢掉的行数记在 `generateLogDropped` 里，界面在标题栏明写 | [`GenerateLog.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateLog.kt) 的 `GENERATE_LOG_MAX_LINES` / `GENERATE_LOG_DROP_BATCH` |
| 丢行要**成批**丢 | 丢掉的是**最前面**的行，而 `SnapshotStateList.removeAt(0)` 是 O(n) 的数组搬移（十万行 = 5×10⁹ 次）；更要命的是删第 0 行会让**后面每一行都换位置**，可见的每一行都要重组一次。成批丢（每 128 行一次）把这笔开销摊薄到 1/128 | 同上 |
| 自动滚动盯**序号**，条数只是**下标**的来源 | 序号每次追加**只 +1** —— 「又来了一行」只有**一个**信号；而条数在丢行时**不是单调的**（一次成批丢行会让它从 1129 掉回 1000），拿它当触发信号就得同时处理「涨了要滚」与「跌了别滚回去」两种情况。条数仍然要读 —— 它就是**滚到第几行**，但**在组合期只读一次**：列表的 item 数与要滚的下标必须是同一个数。两者都进 effect 的 key：**序号是触发信号，条数保证下标不越界** | [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) 的 `generateLogSeq` · [`GenerateWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateWorkbench.kt) 的 `GenerateLogList` |
| 滚动用**挂起式** `scrollToItem`，不用 `requestScrollToItem` | `requestScrollToItem` 记下一个「下一次 remeasure 时生效、且只生效一次」的请求 —— 而 remeasure 常常**同时**要应用数据变更（行数变了），那一刻位置是按 **key** 维持的（列表是索引键），新行就落在视野之外；请求有没有抢赢取决于它与那次 remeasure 的先后 —— **竞态**。`scrollToItem` 在滚动块里**同步**做完一次 remeasure：位置一定落在最后一行，且与其它滚动互斥 | 同上 |
| **失败也进日志**，行号**跳号也不抹平** | 前者：一份不显示失败的日志是不完整的 —— 用户在日志里看到「插到第 37 行」然后就没了，与「日志里明写失败原因」差着一整个排查过程（脚本为空 / 引擎报错 / 异常兜底三条路径都留痕）。后者：引擎的帧通道满了会**丢最旧**的帧，行号会从 7 直接跳到 9 —— 行号是**引擎回传的真值**，抹平成连续的行号等于伪造一份不存在的日志 | [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) 的 `generateFailureEntry` · [`GenerateLog.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateLog.kt) |
| 造数日志属于**这一轮** | 重新执行 / 切连接都要清空 —— 否则两轮的行混在一起，看不出哪一行是哪一轮插的 | [`DatabaseBrowserState.kt`](./src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt) 的 `clearGenerateLog` |
| 造数工作台的**编辑器高度可拖**，日志与结果**合成一块** | 此前是**写死的比例**（编辑器 45% / 日志 25% / 结果 30%）：写一段几百行的 Lua 循环时编辑器太矮，而只想看一眼刚造的那批数据时输出区又占了七成屏。现在编辑器与输出面板之间那条缝是 `HorizontalDragHandle`（与浏览 / 分析两侧**同一份实现**），双击复位回默认比例；**输出面板的 260dp 下限是拖拽的硬边界** —— 日志看不见就等于没有日志，窗口很矮时**编辑器的下限要让位给它**。而下半部分从**两个面板合成一个**：日志与结果表本是同一件事的两面（过程 / 结论），此前却是两行标题栏、两条分隔线，且只有两三行的结论表分到了 30% 的高度 —— 真正需要地方的日志只拿到 25% |
| 统计结果**只占一行**，不为两三行内容留一整块地 | 合成输出面板之后，那块「统计结果」（每个脚本一行：脚本 / 目标表 / 插入行数）仍是 150dp 的一块地，而它实际只有两三行内容 —— **为两三行内容留一整块地，是把最稀缺的地方浪费掉**：那块地本该给日志（1000 行历史）。现在收成底部**一行**文本（造数中 / 跑完 / 出错三种）。**分脚本的明细并没有丢** —— 日志里**逐行写着**目标表名与该脚本内的行号，真要按表汇总，看日志比看一张两三行的表更快 | [`GenerateWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateWorkbench.kt) 的 `GenerateStatusLine` / `GENERATE_STATUS_LINE_HEIGHT` | [`GenerateWorkbench.kt`](./src/main/kotlin/com/langbiantianya/sundays/GenerateWorkbench.kt) 的 `GENERATE_EDITOR_DRAG_HANDLE_TAG` / `MIN_GENERATE_OUTPUT_HEIGHT` |



### 3.4 表预览与对象树



| 决策 | 结论 | 落点 |

|---|---|---|

| 引擎侧真分页 | `DataTable` 必须传 `serverSidePaging = true`，否则它会再切一次本地页，**第 2 页起直接变成空表** | [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) |

| 标签页主键 `schema::table` | `TablePreviewTab.key` 是**唯一去重依据**，与 `TableRow.id`（行主键）不是一回事 | 同上 |

| tab 级 `requestGeneration` | 屏级代次管不到「同一张表上连着翻两页」——两次请求并发在跑，慢的第 1 页会盖掉快的第 2 页 | 同上 |

| 树面板宽度放**屏级**不放 sheet 内 | 布局宽度是**窗口级偏好**，不是 per-sheet 的；放 sheet 内会在切 sheet 时被 `remember` 一起丢掉 | 同上 |

| 宽度上限按**比例**（容器宽 × 62%） | 窗口窄时若还允许拉到 600dp，右侧工作台会被挤到没法用 | 同上 |

| 树面板外壳**上移到 `:shared`**（`SidePaneShell`） | 浏览模式与分析模式是**同一个概念**（左侧的数据库树），此前却是两套实现：浏览模式能拖宽、分析模式写死 240dp。合成一个组件后，「折叠到侧边」「拖拽调宽」在两个模式里是同一份代码、同一套手感 | [`shared/…/ui/SidePaneShell.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/SidePaneShell.kt) |

| 折叠态是**贴到左边的 44dp 窄轨**，不是「把面板变窄」 | 44dp 塞不下树 —— 画一截残影既没法看也没法点。折叠时树**整体从组合里移除**，右边因此**真的宽出 276dp** | 同上 |

| 折叠**不丢**展开态，也**不丢**滚动位置 | 展开态（哪些库 / 哪些表是张开的）住在会话状态里；滚动位置也住在**面板之外**（浏览模式在 `DatabaseBrowserState`，每个连接各一棵）—— 折叠时整棵树从组合里移除，状态若记在树上就跟着没，展开后树回到顶部，用户得重新滚回去 | 同上 |

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
| 顶栏**没有**目标切换 chip，「数据分析」的入口在**连接管理的空闲面板** | 「连接管理」是首屏 —— chip 只是把当前项再显示一遍（切过去等于什么都不做）；而「数据分析」是**入口**，该待在用户真正需要它的地方（没选中任何连接时，右侧那一块本来就在问「接下来做什么」）。**选中连接后详情面板把空闲面板顶掉了，所以详情面板头部还有一个 ✕**（取消选中 → 回到空闲面板），否则那条入口选中一次之后就再也找不回来了 | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) + [`AnalysisScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisScreen.kt) |
| 启动落在**首屏**（选一个模式进去） | 两种活儿是两套会话、两种心智模型，多数时候用户心里**有答案**（要做分析就直接做分析）。chip 是**常驻**的，等于每次都要重新决定一次；「选模式」放在进应用时问一次，问完一路走下去。两个入口各自都有内容等着（没有可分析的数据源时，分析工作台会**说清**并指路连接管理） | [`StartScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/navigation/StartScreen.kt) + [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) |

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
| 拉取 = **DuckDB 联邦直查**远端 JDBC 源 | DuckDB 自己就能直接读远端（`CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>`）—— **不落盘、不绕 CSV**，而且保留远端的类型。**零新协议**的那条「`EXPORT` → CSV → `IMPORT`」被换掉了 | [`EngineAnalysisPuller.kt`](./src/main/kotlin/com/langbiantianya/sundays/EngineAnalysisPuller.kt) + 引擎的 `JdbcScanTableFunction.kt` |
| 拉取默认走**纯 Java 表函数**，原生扫描器只是**可选加速** | 两条路的**类型映射不同**（`SMALLINT` → 原生 `SMALLINT` / 我们 `INTEGER`）—— 「装没装扩展」不该隐式决定用户拿到的类型。原生那条路走不通就**降级**回纯 Java，用户看到的仍是「拉取成功」 | 引擎的 `NativeExtensionScanner.kt`（`SUNDAYS_DUCKDB_NATIVE_EXT=1` 才开） |
| 拉取的凭据**只在请求体**里，不进 SQL 文本 | 引擎把它变成一条**只发 SELECT** 的 JDBC 连接 —— 调用方能传的只有「选哪张表」。远端恒为只读由**构造**保证，不是靠开关 | 同上 |
| 拉取的**取消**要显式叫停引擎 | 引擎是**另一个进程**，取消前端协程并不会让它停下来；取消要两件事一起做（取消协程 + `SYSTEM.CANCEL` 带请求 id）。请求 id 由**前端**生成，否则取消方不知道该叫停哪一个 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 拉取的终态是一个 **sealed**，不是三个可空字段 | 「结果 + 错误」两个可空字段表达不了「取消」（两个都空 ⇒ 大概是取消了 —— 那是**推断**不是事实）。`PullOutcome`（`Done` / `Cancelled` / `Failed`）三种情况互斥且穷举，**取消不是失败**，界面上分开说 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) + [`AnalysisPull.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisPull.kt) |
| 树与展开态都归**会话**管 | 会话比界面活得久（顶层切走再回来，`AnalysisScreen` 会离开组合，而会话是 `remember(engine)` 建的、一直都在）。展开态放界面侧的话，用户「展开树 → 去连个库 → 回来」会发现树缩回去了 —— 那不是「界面没保存」，是**放错了地方** | 同上 |
| 树的展开是**懒加载**的 | 子节点在点开的时候才去查 —— 启动时把每个源的每个库都列一遍，意味着「打开分析模式」要先连一遍所有远端，而远端可能压根连不上 | 同上 |
| 分析树的**根只有工作区** | 连接配置**属于连接管理器** —— 同一份配置在浏览模式、连接管理器、分析树里各列一遍，就会变成**两份可能对不上的真相**（改了名字只更新一处、两处列的表不一样）。分析树是「**我的工作区里有什么**」的视图。代价：远端表不再出现在树上，「拉取远端表」在界面上暂时没有入口（能力仍在） | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 树加载**失败**不算「这个节点是空的」 | 保持 `loaded = false`（再点一次就能重试），错误原文摆在树脚下。把失败当成空列表，用户会以为是自己连错了库 | 同上 |
| 拉取要**显式传源 id** | 树上点的是**某一张具体的表**，它属于哪个源是树自己知道的；用「当前选中的源」会拉错库的同名表 —— 而那**不会报错**（只要恰好有那张表），只是数据悄悄换了来源 | 同上 |
| 分析工作台**没有「数据源」下拉框** | 它是**纯装饰**：拉取走的是树上那张表（表节点自带 `sourceId`），从不下拉框里取源 —— 那个 id 全程只有它自己在读。而它带来的是**两份可能对不上的真相**：下拉框里选了 A 源、树上点了 B 源的表，界面上「当前源是 A」而拉下去的却是 B 源的同名表。源只有一处能选：**左树** | [`AnalysisScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisScreen.kt) |
| 结果表的只读判据是**结构的** | 「点一下单元格看有没有冒出输入框」是**假绿**：实测把结果表改成可编辑后，点单元格照样不冒输入框（编辑手势挂在单元格的 `Box` 上，`Text` 自己不收指针事件）。改用「可编辑的单元格才有的标记」才有判别力 | [`AnalysisScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisScreen.kt) |
| 数据源的**增与删**是**一件事的两处** | 一个数据源在工作区里占**一个 schema**，同时在登记表（`sundays` schema）里占**一行**。只删一处的话：只删 schema → 重启后它又「回来」而表已经没了（用户看到的是一个**空**的数据源）；只删登记 → schema 还在、表也还在，但树上多一根**没有来由**的 schema。删除要**两处一起动** | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 删除的顺序是「**先 DROP 再 DELETE**」 | 反过来的话，`DROP` 失败就留下一个「有 schema、没登记」的**孤儿** —— 用户在树上看得见它，却**没法从菜单里删掉**（菜单只给登记在册的源）。先 DROP 的话，最坏情况是「schema 没了、登记还在」—— 那只是下次启动时一次没人管的清理（树列的是**实际存在的** schema，登记里的残留不会在树上冒出一根） | 同上 |
| 「能不能删」的判据是**登记表**，不是「树上有没有这一行」 | 树上那一行只说明「工作区里有个叫这个名字的 schema」；登记表才说明「它是本应用登记过的数据源」。用户在 DuckDB 里手工建的 schema 不是数据源，工作区自己的 `main` 更不是 —— 删它等于删掉分析模式**自带的计算引擎**。判据落在 `:shared` 的纯函数 `analysisDeleteSourceTarget` 上：界面按它决定「要不要弹菜单」，测试按它断言「这一下不该有删除项」 | [`AnalysisTreeMenu.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisTreeMenu.kt) + [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 动作那一层要**再核一次**清单 | 界面那一层回答的是「用户点了什么」，动作那一层回答的是「到底允许删什么」。两边对不上时**动作那一层说了算** —— 宁可拒绝，也不要 `DROP` 掉一个不该删的 schema | 同上 |
| 删除**不可撤销**，所以先问一句 | 删掉的是整个 schema（连同里面所有拉进来的表），DuckDB 里没有撤销，而远端表可能几十上百兆。确认框与浏览模式「删除表」那个框同构：标题是**问句**、正文是**后果** | [`AnalysisScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisScreen.kt) |
| 删除的结果是一个 **sealed** | 「删掉了」与「没删掉」要用**不同的颜色**呈现，而颜色不是字符串的一部分 —— 所以带一个**种类**（`DeleteSourceOutcome.Deleted` / `.Failed`），界面照着渲染即可，不必自己推断。与 `PullOutcome` 是同一个做法 | [`AnalysisModels.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisModels.kt) |
| 菜单机制**与浏览模式共用一份** | `TreeContextMenuState` / `TreeContextMenu` / `TreeContextMenuItem` 现在住在 `:shared` 的 `tree/TreeContextMenu.kt`。此前那套机制只在 `desktopApp` 里 —— 分析模式要么复制一份（同一个概念两份实现，改一处忘另一处），要么根本没有右键菜单。菜单项各写各的，但「点了菜单项**先关菜单**再干活」这条规矩写在共享组件里（漏了的后果：菜单杵在树上不消失，下一次右键被压在旧 Popup 底下，看起来像「右键没反应了」） | [`TreeContextMenu.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/tree/TreeContextMenu.kt) |

| 分析模式是**两个视图**，不是两个顶层页 | 工作台与报表设计共用**同一份工作区**（同一批拉进来的表、同一套 SQL 语义）。做成两个顶层页的话，「拉好的数据」与「做好的报表」就被分到了两个互不相干的地方 —— 而它们本来就是一件事的两个面。切换条在 `:shared` 的 `analysis/AnalysisModeBar.kt` | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) + [`AnalysisModeBar.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisModeBar.kt) |
| 报表状态住在**会话**里，而**报表本身落盘**（独立文件） | 报表 = 若干**部件**，每个部件 = **一条 SQL + 一种图表类型**。落盘走**独立**的 `~/.config/sundays/reports.json`（与 `connection.json` 同构），**不进工作区 DuckDB** —— 工作区是**用户自己的**数据，把应用的报表塞进去，用户在自己的工作区里就能查到一张**他没建过的表**；而「应用的文件都在哪」必须**只有一个答案**。「部件 = 一条 SQL」这个形状**从此不许改**：用户搭好的报表会因为形状变化而集体作废 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) + [`ReportStorage.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportStorage.kt) |
| 落盘的**只有形状**，没有查出来的数 | 部件数据是**派生**的：它由 SQL 在工作区上跑出来，一落盘就开始过时 —— 而**过时的数字比没有数字更危险**（用户会拿它当真的）。重开应用后点一次刷新，数据就回来了 | 同上 |
| 读不出来时**改名保留**，绝不静默清空 | 静默当成空集（`ConnectionStorage` 现在的做法）会把「文件坏了」变成「数据没了」，而后者**不可逆**。改名的文件**一个字节都没动**，用户可以自己打开看、自己改回来；应用按空集继续，并**明确告知**。连改名都失败时**停掉落盘** —— 绝不能往那个读不出来的文件上写 | 同上 |
| 磁盘上的 **id 原样保留**，计数器推到最大值 | id 是**计数器**发出来的。不推的话，重开应用后新建的报表会拿到一个**已被占用**的 id —— 两个报表共用一个 id：选中一个会选中两个，删一个会删掉两个，部件的 id 也一样，数据会**串** | 同上 |
| 部件排序用**按钮**，不用拖拽 | 拖拽要给「部件 = 一条 SQL」之外再加一层**几何信息**，而那对「一张报表」不是必需品。按钮不引入几何信息，那条取舍不用推翻 | 同上 |
| 「能不能挪」是**规则**，不是界面的判断 | 纯函数 `canMoveReportWidget()` —— 界面拿它**灰按钮**、会话拿它**执行**。两处各写一遍，迟早出现「界面说不行、会话说行」的裂缝 | [`ReportModels.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportModels.kt) |
| 复制出来的部件**把数据也带过去** | 复制的是**同一条 SQL** —— 结果一模一样，不必再跑一遍（工作区是**一个** DuckDB 文件，查询要排队）。前提由「SQL 一改、旧数据立刻作废」那条规则守着 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 改了 SQL 就把**旧数据丢掉** | 画布上那张图是**旧 SQL** 跑出来的，而用户刚改完 SQL 正是要确认「新 SQL 对不对」—— 留着旧数据就是让他对着旧结果确认新查询，那比没有数据更糟 | 同上 |
| 导出走 **desktopApp 侧直接 POI**，不走引擎 | 数据**已经在这儿**（前端的 `widgetResults`）。走引擎要把「部件清单 + 每块的数据」整个传过去，再让引擎写文件 —— 为了同一个结果，多出一条协议、一份 proto、一个路由。而且走引擎意味着**重跑一遍 SQL**，重跑的结果可能与屏幕上看到的不一样：**发出去的文件与眼前对不上**，比慢一点糟糕得多。**POI 早已随 `dialect-duckdb` 进了打包运行时**，所以这里**不新增任何运行时依赖**，jlink 的模块清单一个都不用加 | [`ReportXlsxWriter.kt`](./src/main/kotlin/com/langbiantianya/sundays/ReportXlsxWriter.kt) |
| 导出里图表落成**表格**（类别 / 数值 / 占比%） | POI 的 XDDF 能画真图表，但那份代码要伺候 Excel / WPS / LibreOffice 三方的差异，而「表格 + 占比」在哪儿都打得开。仓库原则是「宁可少给，不给一个点了必然失败的东西」—— 一个发出去打不开的 xlsx，比一个只有数字的 xlsx 糟得多 | 同上 |
| 表格部件的单元格**原样写成文本** | 那一列可能是编号（`007` 写成数字就变成 `7`）、可能是长 ID（写成数字会变科学计数法）。把用户的数据「顺手」转成数字，是**篡改** | 同上 |
| 数据画不出来时**原样导出** | 画不出来只说明**这个形状画不成图**（缺列 / 值不是数字 / 有负值），不说明数据没用。用户拿到的仍然是**那些数** —— 丢掉它们才是真正的损失 | 同上 |
| 导出**先刷新再写** | 导出的要是**导出那一刻**的真实数据。拿内存里那份导出，用户看到的与收到的可能不是一回事 | [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| 「另存为」是会话的**构造参数**（接缝） | 会话收的是「挑一个路径」这件事本身，不是「怎么挑」（弹框、预填文件名归 `NativePathPicker`）。理由是**导出流程必须能被确定性地测** —— 真实现会弹一个 AWT 模态框：无头环境弹不出来，有桌面的机器上弹得出来也**没人去点**，测试会**永远等下去**（模态框还占住 AWT 事件线程，测试进程退不出去）。与 `puller` 同一个道理：**接缝是为了让状态机可测** | 同上 + [`NativePathPicker.kt`](./src/main/kotlin/com/langbiantianya/sundays/NativePathPicker.kt) |
| 导出期间的状态提示**压过**「上一次的结果」 | 导出要**重跑每个部件的 SQL**，那不是一瞬间的事。按钮灰掉只说明「点不动了」，没告诉用户**正在忙**；而上一条「已导出到 …」还挂在屏上，会让人以为**导出已经结束了**。「正在导出」说的是**在等什么**，而不是**上一次发生了什么** | 同上（`reportStatus`） |
| 「刷新整张报表」「刷新一个部件」「导出」**互斥** | 三者都要动工作区这个**一个** DuckDB 文件 —— 并发查询会互相抢锁，而且后完成的会把先完成的盖掉（A 部件的位置上显示着 B 部件的数据）。判据收在一个 `workspaceBusy()` 里 | 同上 |
| 运行标志**同步**置位 | 它是「这把锁已经被占了」的凭据。放在协程里置位的话，连点两下会**两次都通过检查** —— 两个刷新一起跑，后完成的把先完成的盖掉。而标志必须**在调用返回之前**就置上，所以清标志用 `invokeOnCompletion`（协程**还没开始跑**就被取消时，`finally` 不会执行，标志会永远卡在 true 上） | 同上 |
| 「应用配置目录」只有**一份**实现 | `connection.json` / `settings.json` / `workspace.duckdb` / `reports.json` 各自都写了一遍 `~/.config/sundays`。四份字面量今天一样，明天改目录名就会漏掉一份 —— 而漏掉的那一份的症状是「设置没了 / 报表没了」，用户完全不知道该去哪找 | [`AppConfigDir.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/config/AppConfigDir.kt) |
| 部件的 **id 是保存时才有的** | 新建中的部件 id 是**空串**（`newReportWidgetDraft()`）：id 是「存下来之后」才有的身份 —— 它可能下一刻就被用户「取消」了。先发一个 id 出去等于承诺了一个它永远不会用到的身份，而这些 id 会一直留在会话的计数里，让后面的部件都带着一个空洞的序号 | 同上 |
| 删部件 / 删报表要**连它的数据一起删** | 删掉却不删，会留下一堆**永远没人引用**的查询结果 —— 地图越攒越大，而界面上再也看不到它们（而且按 id 找不回来，再也删不掉） | 同上 |
| 刷新一张报表**串行**跑它的部件 | 工作区是**一个** DuckDB 文件 —— 并发查询会互相抢锁，而且后完成的会把先完成的盖掉，用户看到的是**错配**的结果（A 部件的位置上显示着 B 部件的数据）。串行慢一点，但每个部件拿到的都是**自己**那份 | 同上（`refreshReport`） |
| 刷新**只认会话里有的**那张报表 | 认不出来就直接不刷 —— 否则会把**没人引用**的数据塞进会话：界面上根本没有这张报表，数据却占着位置 | 同上 |
| 部件的 SQL 与工作台的 SQL 是**同一个世界** | 部件的 SQL 跑在**工作区**上：工作台里能查到的表，部件里也能查。反过来不成立 —— 部件的 SQL 要是跑在远端上，用户刚拉下来的数据就用不上 | 同上（`previewSql`） |
| 样例数据是**真跑出来**的 | 部件的形状约定是「第一列类别、第二列数值」，而**约定只有看得见数据才能被遵守**。「预览」不是拿假数据糊弄界面，它真的在工作区上跑那条 SQL | 同上 |
| 报表设计里也有**数据树**，且与工作台**共用同一棵** | 部件的形状约定是「第一列类别、第二列数值」，而写出一条符合这个约定的 SQL，前提是知道**有哪些表、哪些字段**。没有树的话，用户只能切回工作台把名字**记下来**再凭记忆敲，**敲错了要等跑 SQL 时才报错**。树的状态（节点 / 展开态）住在会话里、**与工作台同一份** —— 那是同一棵工作区树，不该有两份状态（树上展开到一半，切去工作台再回来还是展开的） | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) + [`ReportDataTreePane.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportDataTreePane.kt) |
| 「点树上的一张表」在两处的后果**不同** | 画布上**还没有正在写的 SQL**，引用插给谁？所以那里的点表是「**开一个新部件**」，起手的 SQL 由部件编辑页接手；编辑页里点表则是「**把引用插进正在写的那条 SQL**」。同一个组件、同一份数据，只是**上下文**不同 | 同上 |
| 引用拼接的规则**住在 `:shared`** | 「树上的一个对象」到「SQL 里的一段引用」这条规则有两个调用方：分析工作台（会话的 `appendToSql` / `appendColumnToSql`）与报表设计（部件编辑页）。规则只写一份在 `AnalysisSqlRef.kt`（纯函数），两边都调它 —— 两份实现的话，改一处忘了另一处必然发生，而**两边各自的测试都是绿的** | [`AnalysisSqlRef.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/AnalysisSqlRef.kt) + [`AnalysisSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/AnalysisSession.kt) |
| **还没有报表时点树会先建一张** | 部件总得有个家。而「提示用户先建报表」等于让那一下点击**白点**（用户点了，什么也没发生）—— 仓库原则是「宁可少给，不给一个点了必然失败的东西」。用户在报表设计里点了一张表，他要的就是「把它放进报表」；报表还不存在只是因为还没建 | [`ReportDesignerScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportDesignerScreen.kt) |
| 报表设计页的布局是「**数据树（左）｜ 画布 ｜ 报表列表（最右）**」 | 三块各司其职，位置由「它是**哪一类**东西」决定：数据树是**输入**（有哪些表、哪些字段）→ 放左，与浏览模式、分析工作台**同一个方向**；画布是**主产物**（部件一块块往下排）→ 放中间，占最大那块地方；报表列表是**另一类产物**（一张张报表）→ 贴**窗口最右**，「它不参与画布的编辑」一眼看得出来。原先是「报表列表 ｜ 数据树 ｜ 画布」—— 输入与产物挤在左边，主产物被推到最后 | [`ReportDesignerScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportDesignerScreen.kt) |
| 报表设计的**两侧都能折叠收纳** | 树收成**窗口最左**的一条 44dp 窄轨，报表列表收成**窗口最右**的一条 —— **折叠的意义是把宽度让给画布**（画布才是这一页的主角）。手感与浏览模式 / 分析工作台**完全一致**（同一个 `SidePaneShell`），**两块面板各收各的**（树收起来不动列表，反之亦然） | 同上 |
| 「树面板外壳」改名「**侧边面板外壳**」 | `TreePaneShell` → `SidePaneShell`（并从 `tree/` 挪到 `ui/`）：它服务的是「**可折叠侧边面板**」这个概念，不再只是树 —— 名不副实的名字会让下一个人以为「树的东西不能乱用」。左右两侧的差异（分隔条位置、窄轨竖线位置、箭头朝向、拖动方向感）**全部由 `PaneEdge` 一个枚举驱动** —— 散成布尔参数的话，迟早漏掉一处箭头没镜像，而那种缺陷在界面上**极难看出来** | [`SidePaneShell.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/SidePaneShell.kt) |
| 镜像的**拖动方向**由分隔条**自己**处理 | `DragHandle` 新增 `HandleSide`：受控块在分隔条右边时，往左拖才变宽。这件事**必须**在分隔条内部做 —— 调用方若在回调里反算（`旧值 + (旧值 - 新值)`），**钳位一旦发生就会算错**：拖到边界时新值已被钳过，再反算就把钳位抵消了 | [`DragHandle.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/DragHandle.kt) |
| 报表设计两侧面板的**宽度上限**是窗口宽的 **32%**，不是共享的 62% | 那个 62% 是给**只有一块**侧边面板的界面用的（浏览模式 / 分析工作台）—— 62% 留给主区还剩 38%，够用。这一页有**两块**：各占 62% 的话两块加起来 124%，画布被挤成 0 宽 —— 而画布才是这一页的主角 | [`ReportDesignerScreen.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis/report/ReportDesignerScreen.kt) |


---



## 4. 状态归属速查



| 状态 | 持有者 | 跨 pane 切换 | 切连接 |

|---|---|---|---|

| SQL sheet 列表 / 选中项 / 文本 / 光标 / 滚动 / 执行结果 / 结果区视图 | `DatabaseBrowserState.sqlSheets[i]` | 保持 | 整体复位为单个空 sheet |

| SQL 编辑器方言档位 | `currentConnection.dialect` → `sqlDialectProfile()` | 保持 | 换档位 |

| 造数脚本与统计、运行态、结果区视图 | `generateScripts[i]` / `generate*` | 保持 | 复位为单个默认模板 |
| **造数实时日志**（行 / 丢行数 / 追加序号） | `DatabaseBrowserState.generateLog` / `generateLogDropped` / `generateLogSeq` | 保持 | 清空（属于上一连接） |

| 表预览标签页（列 / 行 / 分页 / 代次） | `DatabaseBrowserState.tabs[i]` | 保持 | 清空 |

| 库列表 / 展开状态 / 表缓存 | `DatabaseBrowserState`（per-sheet） | 保持 | 清空 |

| **树面板宽度** | `DatabaseBrowserScreen`（**屏级**） | 保持 | 保持（窗口级偏好，不落盘） |
| **SQL 编辑器高度** | `DatabaseBrowserScreen`（**屏级**，与树面板宽度同一层） | 保持 | 保持（窗口级偏好，不落盘） |
| **造数编辑器高度** | `DatabaseBrowserScreen`（**屏级**，与 SQL 编辑器高度同一层但**各存各的** —— 两个面板的默认比例不同，共用一个值会让「在 SQL 里拉高编辑器」顺带改掉造数工作台） | 保持 | 保持（窗口级偏好，不落盘） |

| **后台任务列表** | `DatabaseBrowserState`（per-sheet） | 保持 | 随 sheet 生命周期 |

| **通知中心** | `MainScreen`（**屏级**） | 保持 | 保持 |

| **报表与部件**（报表清单 / 选中项 / 部件数据 / 样例数据） | `AnalysisSession` | 保持 | 不适用（分析模式与连接无关） |
| **报表本身**（形状：id / 名字 / 部件的类型 / 标题 / SQL） | `AnalysisSession` + `~/.config/sundays/reports.json`（**每次改动自动落盘**） | 保持 | 不适用（**跨会话也在** —— 重开应用还在） |
| **报表的运行态**（正在刷新的部件 / 正在导出 / 状态提示） | `AnalysisSession` | 保持 | 不适用 |
| **分析模式的视图**（工作台 / 报表设计） | `AnalysisSession` | 保持 | 不适用 |
| **报表数据树**（节点 / 展开态） | `AnalysisSession`（**与工作台共用同一份**） | 保持 | 不适用 |
| **报表设计两侧面板的折叠态与宽度** | `ReportDesignerScreen`（**屏幕层**） | 保持 | 不适用（窗口级偏好，不落盘） |

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

