# desktopApp — KMP Compose Desktop 客户端内部架构（v2.15）

## 概述

`desktopApp/` 是 `sundays` 项目的**桌面客户端模块**。它使用 **Kotlin Multiplatform + Compose Multiplatform** 构建，**当前仅启用 JVM Desktop 单平台目标**（macOS / Linux / Windows 三端共享同一份 Compose Desktop (Skia) 渲染），通过 **v2.15 调用层抽象** 与引擎集成 —— UI 只面向 `:engine-protocol` 的 `EngineClient` 接口编程，**默认绑定同进程 `IdbEngine`（`:engine`，typed proto 消息同 JVM 直传：零序列化、零子进程、零 gRPC channel、零 IPC transport）**；设置系统属性 `-Dsundays.engine.endpoint` 时改绑 `GrpcEngineClient`（`:engine-grpc-client`，跨进程 gRPC）。实现的选择集中在 `main.kt` 的 `createEngineClient()`，对下游状态机完全透明。

**引擎实现选择**（`createEngineClient()` 读取 `System.getProperty("sundays.engine.endpoint")` 并 `trim()`）：

| 端点属性 | 实现 | 模块 | 通道 |
|---|---|---|---|
| 未设置 / 空白（**默认**） | `IdbEngine` | `:engine` | 同 JVM 直接方法调用 |
| `host:port` / `tcp://host:port` / `unix:///path/to.sock` / `pipe:name` | `GrpcEngineClient` | `:engine-grpc-client` | gRPC over TCP / UDS / 命名管道 |

> 端点写法非法时 `GrpcClientConfig.fromTarget` 抛 `IllegalArgumentException` —— **不会静默回落**到本地引擎。
> gRPC 模式需先启动引擎进程：`java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051`，
> 再运行应用：`./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051`。

**v2.13 顶层导航**：`MainScreen` 渲染导航条（`AppDestination.CONNECTIONS` / `AppDestination.DATABASE`）+ 当前目标屏幕。连接列表由 `ConnectionSession` 持有（位于导航之上），切换目标不丢连接；`DatabaseBrowserScreen` 提供数据库 / 表浏览与表数据预览标签页。

**未来扩展路径**：KMP 工程结构天然支持后续启用 `androidMain` / `iosMain` / `wasmJsMain` source set —— 只需新增对应平台特定的子进程拉起逻辑（如 Android 的 `bindService`、iOS 的 `NSXPCConnection`），`commonMain` 中的业务层零修改复用。当前 v2.9 demo 阶段仅暴露 `main` 单一 source set。

**关键设计原则**：

- **依赖方向**：`desktopApp` → `:shared`（UI 组件）+ `:engine-protocol`（`EngineClient` 调用层契约）+ `:engine` / `:engine-grpc-client`（两个实现，装配点二选一）。**反向依赖被严格禁止** —— 引擎与 shared 模块均不感知 desktopApp 存在。
- **默认同进程**：`desktopApp` 与 `:engine` 默认部署在同一 JVM（Kotlin / Java），Direct 绑定无 IPC 跨进程语义；显式配置端点时改为跨进程 gRPC 调用，调用代码不变。
- **连接管理 + 数据库浏览双屏**：v2.10 起启动落在 `ConnectionManagerScreen`（v2.9 演示阶段的 `DemoApp` / `DemoTabBar` / `EditorDemoScreen` / `TableDemoScreen` 已删除）；v2.13 增顶层导航条 + `DatabaseBrowserScreen`，后续 SQL 编辑器模块按需独立接入。

---

## 工程结构

```text
desktopApp/
├── build.gradle.kts    # composeMultiplatform + compose.material3 + :shared + :engine / :engine-grpc-client（两个引擎实现）+ 方言/驱动 runtimeOnly 依赖
└── src/
    ├── main/kotlin/com/kxxnzstdsw/sundays/
    │   ├── main.kt                    # 入口：main()（Window + SundaysTheme）+ createEngineClient()（引擎实现装配点）+ MainScreen()（目标分派）
    │   │                              # 导航条（TopNavBar）与 AppDestination 已上移 :shared/commonMain
    │   ├── ConnectionSession.kt       # 连接会话状态机：列表 / 向导 / 引擎会话状态 + 全部回调
    │   └── DatabaseBrowserScreen.kt   # 第二屏 UI + DatabaseBrowserState / TablePreviewTab 状态机
    └── test/kotlin/com/kxxnzstdsw/sundays/
        ├── ConnectionManagerFlowTest.kt  # 连接流程端到端（真引擎 + 真点击）
        ├── DatabaseBrowserFlowTest.kt    # 浏览 + 两个工作台状态机：拉库 → 展开表 → 开标签页 → 去重 → 关闭 → SQL 多 sheet → 造数
        ├── DatabaseBrowserUiTest.kt      # 真点击：双击开标签页 + SQL 工作台（含多 sheet）执行 + 造数工作台执行 + 切 pane 状态保持
        ├── MainScreenNavTest.kt          # 顶层导航切换（连接管理 ↔ 数据库浏览）
        └── EngineClientSelectionTest.kt  # createEngineClient() 绑定逻辑（默认 / 配置端点 / 非法端点）
```

desktopApp 现有 **47 个测试**（`ConnectionManagerFlowTest` / `DatabaseBrowserFlowTest` / `DatabaseBrowserUiTest` / `MainScreenNavTest` / `DialectNameContractTest` / `EngineClientSelectionTest` 等）。

**文件清单**：

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 声明 `kotlinJvm` / `composeMultiplatform` / `composeCompiler` 插件；`:shared` 依赖 + **`:engine`（默认实现）与 `:engine-grpc-client`（端点实现）同时引入** + `protobuf-java` / `protobuf-kotlin-lite`（消费 typed proto）；**5 个方言插件 + 5 个 JDBC 驱动以 `runtimeOnly` 上应用类路径**（Direct 模式无需外部 `dialects/` `drivers/` 目录）；`material-icons-extended`；`compose.uiTest` + JUnit4 测试依赖（`testImplementation(project(":dialect-h2"))` 供 H2 内存库测试直接构造方言）；原生分发目标 `Dmg` + `Msi` + `Deb` |
| `main.kt` | 应用入口（`application { Window { SundaysTheme { MainScreen(engine) } } }`）；`MainScreen` 持有 `AppDestination` 状态、`ConnectionSession` 与 `DatabaseBrowserState`，渲染 `:shared` 的 `TopNavBar` + 当前目标屏幕。`main.kt` 同时是**唯一的引擎装配点**（`createEngineClient()`） |
| （已上移 `:shared`） | `AppDestination` / `TopNavBar` → [`shared/.../navigation/`](../shared/src/commonMain/kotlin/com/kxxnzstdsw/sundays/navigation/)；`SundaysTheme` → [`shared/.../ui/Theme.kt`](../shared/src/commonMain/kotlin/com/kxxnzstdsw/sundays/ui/Theme.kt)。三者不引用 `:engine`，故可跨平台复用 |
| `ConnectionSession.kt` | 连接会话状态机（Compose 快照状态持有者）：`connectionList` / `selectedConnection` / `wizard` / `statuses` + `connect` / `disconnect` / `testConnection` / `save` / `delete` / 向导步进 |
| `DatabaseBrowserScreen.kt` | 第二屏：`DatabaseBrowserScreen`（顶部连接条 + 左侧库/表树 + 右侧标签页预览）、`DatabaseBrowserState`（加载与标签页状态机）、`TablePreviewTab`（单表预览状态，`key = schema::table`）、`SqlSheet`（SQL 工作台单个 sheet 的编辑器 + 独立结果） |
| `ConnectionManagerFlowTest.kt` | 端到端流程测试：真 `IdbEngine`（按 `EngineClient` 传入）+ 真点击（`runComposeUiTest`），断言连接池建立/释放、状态流转、`connection.json` 落盘 |
| `DatabaseBrowserFlowTest.kt` | 状态机测试（H2 内存库）：库列表 / 表列表 / 预览行数据 / 标签页去重 / `closeTab` 选中回退 / `selectPane` 三 pane / `executeSql` SELECT+DDL / SQL sheet 增删、逐 sheet 结果隔离与重命名 / `executeGenerate` 造数落库与统计 / 造数脚本增删与重命名 |
| `DatabaseBrowserUiTest.kt` | 真点击测试：展开库 → 双击表 → 预览标签页出现且 `Role=TAB` 数量恒为 1；SQL 工作台执行 + 「＋」新建 sheet / 切 sheet 显示各自文本与结果 / 「✎」重命名（回车提交、Esc 取消）/ 「×」关闭 / 滚轮横向滚动；造数工作台执行；两个工作台切 pane 后文本 + 光标 + 滚动 + 结果回显 |
| `MainScreenNavTest.kt` | 顶层导航测试：默认连接管理，点「数据库浏览」切换后第二屏出现 |
| `EngineClientSelectionTest.kt` | 装配点绑定测试：未设置 / 空白端点属性 → `IdbEngine`；设置端点（含 `unix://`）→ `GrpcEngineClient`；非法端点 → `IllegalArgumentException`（**响亮失败，不静默回落**） |

**`main.kt` 内符号分解**（自顶向下）：

| 符号 | 可见性 | 职责 |
|---|---|---|
| `ENDPOINT_PROPERTY` | public `const val` | 系统属性名 `"sundays.engine.endpoint"` —— 选择 gRPC 引擎端点的唯一开关 |
| `createEngineClient()` | **internal** | **引擎实现装配点**：读 `System.getProperty(ENDPOINT_PROPERTY)` 并 `trim()`；空白 → `IdbEngine()`（同进程，默认），否则 `GrpcEngineClient.connect(GrpcClientConfig.fromTarget(endpoint))`；端点非法时由 `fromTarget` 抛 `IllegalArgumentException` |
| `main()` | public | `application { ... }` 入口；`val engine: EngineClient = createEngineClient()`、创建 `Window`、套 `:shared` 的 `SundaysTheme`、渲染 `MainScreen(engine)`；`onCloseRequest` 调 `engine.close()` |
| `MainScreen(engine: EngineClient)` | **internal** `@Composable` | 持有 `AppDestination` 状态 + `remember { ConnectionSession(engine, scope) }` + `remember { DatabaseBrowserState(engine, scope) }`，渲染 `:shared` 的 `TopNavBar` 并按目标分派到 `ConnectionManagerScreen` / `DatabaseBrowserScreen`（`internal` 便于导航测试渲染）。**两个状态机都在此持有**：切换目标只销毁屏幕组合，不销毁状态 —— 浏览标签页因此跨导航保留 |

> **模块边界**：导航条（`TopNavBar` / `NavChip`）、导航目标枚举（`AppDestination`）与应用主题（`SundaysTheme`）已上移 `:shared/commonMain` —— 它们是纯 Compose，不引用 `:engine`。本文件因此只剩「平台窗口 + 引擎状态机接线」。`ConnectionSession` 与 `DatabaseBrowserState` 依赖 `EngineClient` 调用层接口（来自 `:engine-protocol`，JVM-only 模块），**必须**留在 desktopApp —— 详见 [`shared/ARCHITECTURE.md` §1.3](../shared/ARCHITECTURE.md)。

**`DatabaseBrowserScreen.kt` 内符号分解**：

| 符号 | 可见性 | 职责 |
|---|---|---|
| `DatabaseBrowserScreen(sheets, activeSheetId, connections, onSelectSheet, onCloseSheet, onAddSheet, onConnect, onDisconnect, modifier)` | public `@Composable` | 第二屏根组合（纯展示 + 事件转发，状态由调用方按 sheet id 各自注入）。`ActiveSheetContent` 内 `LaunchedEffect(sheet.connection.id, sheet.status.state)`：先 `bindConnection`，已连接则 `refreshDatabases`，否则（断开 / 失败）`releasePools` |
| `SchemaTreePanel` / `DatabaseNode` / `TableLeaf` | private `@Composable` | 左侧树：库节点（点击展开，懒加载表）+ 表叶子（`detectTapGestures(onDoubleTap)` 打开预览） |
| `BrowserToolBar(activePane, connected, onSelectPane)` / `PaneToggleButton` | private `@Composable` | 激活 sheet 内容区顶部工具栏：**两个**工作台入口（「SQL 工作台」「造数工作台」）。当前正处于某个工作台时该按钮变「返回表预览」（实心），否则显示工作台名（描边）；未连接时禁用。渲染在 `ActiveSheetContent` 内（`SheetTabRow` 之下），工具与它作用的连接同属一个视觉块 |
| `PreviewTabArea` / `TabStrip` / `PreviewTabContent` | private `@Composable` | 右侧（`BrowserPane.TABLE`）：`SecondaryScrollableTabRow` + 关闭按钮；内容区信息条 + `DataTable` 渲染预览行 |
| `SqlWorkbenchPane` / `WorkbenchTabStrip` / `SqlResultArea` | private `@Composable` | 右侧（`BrowserPane.SQL`）：SQL sheet 标签条（＋ 新建 / ✎ 重命名 / × 删除 / 滚轮横向滚动，每个 sheet 一份独立 SQL 与独立结果）+ 上 `CodeEditorWithToolbar`（`:shared` editor 模块，`languageId = "sql"`，占 60%——内置格式化按钮 + `actions` 插槽注入「执行 SQL」）+ 下结果面板（占 40%，渲染**当前 sheet** 的结果）；结果四态 = `running` / `error` / `affectedRows` / `DataTable` |
| `GenerateWorkbenchPane` / `WorkbenchTabStrip` / `GenerateResultArea` | private `@Composable` | 右侧（`BrowserPane.GENERATE`）：标题条（schema 提示）+ 脚本标签条（**与 SQL 工作台共用 `WorkbenchTabStrip`**；＋ 新建 / ✎ 重命名 / × 删除，顺序 = 执行顺序）+ Lua 版本 chip 行 + `CodeEditorWithToolbar`（`languageId = "lua"`，`editorState` 取自状态机，占 60%——内置 Lua 格式化 + `actions` 插槽注入「执行造数」）+ 下结果面板（占 40%）；结果三态 = `running`（实时已插入行数）/ `error` / `DataTable`（每脚本一行：脚本 / 目标表 / 插入行数） |
| `WorkbenchTabStrip(leadingLabel, titles, selectedIndex, …)` / `TabRenameDialog` | private `@Composable` | 两个工作台共用的标签条：`SecondaryScrollableTabRow`（自带拖拽滚动 + 选中项自动滚入可视区）+ `verticalWheelScrollsHorizontally`（纵向滚轮 → 横向滚动）+ 「✎」为当前标签弹出 `TabRenameDialog`（确定提交 / 取消放弃，空名保持原名；底层 `Dialog`，同 `AddConnectionDialog`）。**不用双击**：`Tab` 的 `selectable` 已在 Main pass 消费 down（`Clickable.handleDownEvent`），外层 `detectTapGestures(onDoubleTap)` 收不到手势。**不用内联编辑**：把自抢焦点的 `BasicTextField` 放进 `Tab` 的文本槽（`SecondaryScrollableTabRow` 的 SubcomposeLayout 内）会让场景永远有待渲染帧 —— 实测 `SkikoComposeUiTest.waitForIdle()` 永不返回（界面卡死），弹窗把它与标签条的测量 / 焦点链路解耦 |
| `BrowserPane` | public enum | 右栏展示模式：`TABLE`（表预览，初始）/ `SQL`（SQL 工作台）/ `GENERATE`（造数工作台）；每 sheet 独立，三者共享同一份 sheet 状态 |
| `EmptyHint(title, description, modifier)` | private `@Composable` | 空态 / 错误态 / 未连接态的统一占位 |
| `TablePreviewTab(schema, tableName, title)` | public class | 单个预览标签页状态：`columns` / `rows` / `loading` / `error` / `total` / `page` / `pageSize`；`key = "$schema::$tableName"` 为去重主键 |
| `DatabaseBrowserState(engine: EngineClient, scope)` | public class | 状态机：`databases` / `expandedDatabases`（`SnapshotStateSet`）/ `tablesByDatabase` / `tabs` / `selectedTabIndex` / `activePane` + SQL 工作台**多 sheet** 状态（`sqlSheets: SnapshotStateList<SqlSheet>` / `selectedSqlIndex`，每个 `SqlSheet` 持有 `title`（可重命名）+ `editor: CodeEditorState`（文本 / 光标 / 滚动）+ 该 sheet 自己的执行结果 `running` / `columns` / `rows` / `rowCount` / `affectedRows` / `error` / `resultPage` / `resultPageSize` / `selectedRowId` + `generation` 失效代次）+ 造数工作台状态（`generateScripts`（每项 `title` 可重命名）/ `selectedGenerateIndex` / `generateRunning` / `generateTablesProcessed` / `generateError` / `generateLuaVersion` / 结果区视图）；行为 `bindConnection` / `refreshDatabases` / `toggleDatabase` / `openTab` / `selectTab` / `closeTab` / `releasePools` / `selectPane` / `executeSql` / `addSqlSheet` / `removeSqlSheet` / `selectSqlSheet` / `renameSqlSheet` / `executeGenerate` / `addGenerateScript` / `removeGenerateScript` / `selectGenerateScript` / `renameGenerateScript`；`generation` 代次用于丢弃跨连接过期响应，`SqlSheet.generation` / `generateGeneration` 用于丢弃两个工作台的过期执行结果…

**工作台状态保持（切 pane 契约）**：右栏三个 pane 是**同一份 sheet 状态**的不同渲染，切换只改变渲染目标，不清空任何状态。工作台被摘出组合的部分状态由状态机持有（SQL 与造数两个工作台都是**多 sheet/脚本**，且每个 sheet 一份独立状态）：

| 状态 | 持有者 | 跨 pane 切换 |
|---|---|---|
| SQL sheet 列表 / 选中项 | `DatabaseBrowserState.sqlSheets` / `selectedSqlIndex` | 保持（增删 sheet 不动其余 sheet 内容） |
| SQL / 脚本标签名（可自定义） | `sqlSheets[i].title` / `generateScripts[i].title`（经 `renameSqlSheet` / `renameGenerateScript` 写入，空名保持原名） | 保持 |
| SQL 编辑器文本 | `sqlSheets[i].editor: CodeEditorState`（文本视图）、`currentSqlSheet()!!.editor.text` | 保持 |
| SQL 编辑器光标 / 选区 / 滚动位置 | `sqlSheets[i].editor.value.selection` / `.scrollState` | 保持（此前会被重置到文首，后续输入插到 SQL 最前面） |
| SQL 执行结果（列 / 行 / 行数 / 受影响行数 / 错误） | `sqlSheets[i].columns` / `.rows` / `.rowCount` / `.affectedRows` / `.error` | 保持（**每个 sheet 独立**，切 sheet 不串显另一个 sheet 的结果） |
| SQL 结果区视图（页码 / 每页条数 / 选中行） | `sqlSheets[i].resultPage` / `.resultPageSize` / `.selectedRowId` | 保持（`DataTable` 全部由调用方托管） |
| 造数脚本（每个脚本独立一份文本 / 光标 / 滚动） | `generateScripts[i].editor: CodeEditorState` | 保持（增删脚本不动其余脚本内容） |
| 造数脚本统计（插入行数 / 目标表） | `generateScripts[i].inserted` / `.lastTable` | 保持 |
| 造数运行态（执行中 / 已处理脚本数 / 错误 / Lua 版本 / 选中脚本） | `generateRunning` / `generateTablesProcessed` / `generateError` / `generateLuaVersion` / `selectedGenerateIndex` | 保持 |
| 造数结果区视图（页码 / 每页条数 / 选中行） | `generateResultPage` / `generateResultPageSize` / `generateResultSelectedRowId` | 保持 |

归位时机：
- **切换连接**（`bindConnection`）—— 跨连接无意义：两个工作台整体复位（SQL sheet 回到单个空 sheet；造数脚本回到单个默认模板）
- **重新执行**（`executeSql` / `executeGenerate`）—— 新一轮结果 → 页码回第 1 页、清选中行与统计（SQL 只影响**当前 sheet**：别的 sheet 的文本与结果原样保留）

> **注意** 光标 / 滚动能保住，靠的是 `CodeEditor` 的 `editorState` 参数（`:shared` 的 `CodeEditorState`）——
> 默认实现是 `rememberCodeEditorState(text)`，随组合生死；不显式传入状态机持有的实例，编辑器一离开组合
> 就会退回文首 + 顶部。详见 [`shared/ARCHITECTURE.md` §2.5](../shared/ARCHITECTURE.md)。
>
> SQL 工作台的多 sheet 与造数工作台的脚本同构 —— 标签条（＋ / × / 切换）驱动 `sqlSheets`，`executeSql()`
> 只作用于 `currentSqlSheet()`。
>
> 回归测试：`DatabaseBrowserUiTest` 的 `sql text and result survive toggling back to table pane` /
> `sql workbench keeps per-sheet text and results` /
> `sql tab rename commits on confirm and discards on cancel` /
> `sql tab strip scrolls with the mouse wheel and follows the selection` /
> `generate workbench inserts rows and restores state after pane toggle` /
> `result page and selected row survive toggling back to table pane` /
> `editor scroll position survives toggling back to table pane`（真点击 + 真引擎 H2）；
> 状态层：`DatabaseBrowserFlowTest` 的 `sql sheets keep their own editor text and results` /
> `renameSqlSheet trims the name and ignores blank input`。

**`DatabaseBrowserState` 引擎调用矩阵**：

| 动作 | (Category, Action) | 请求负载 | 结果去向 |
|---|---|---|---|
| `refreshDatabases` | `SCHEMA.LIST` | `schemaListRequest { level = "database" }` | `databases`（失败 → `errorMessage`） |
| `toggleDatabase`（首次展开） | `TABLE.LIST` | `tableListRequest {}` | `tablesByDatabase[db]`（失败 → `tableLoadError[db]`） |
| `openTab`（新表） | `DATA.LIST` | `dataListRequest { tableName; page = 1; pageSize = tab.pageSize }` | `TablePreviewTab.columns` / `.rows` / `.total` |
| `executeSql`（当前 SQL sheet） | `SQL.EXECUTE` | `sqlRequest { execute = sqlExecuteRequest { sql; schema = "" } }`（`sql` 取自 `currentSqlSheet()!!.editor.text`） | SELECT：`sqlRowFrame` 逐帧攒成**该 sheet** 的 `columns` / `rows` / `rowCount`；非 SELECT：终止帧 `sql.execute.affectedRows` → 该 sheet 的 `affectedRows`；失败 → 该 sheet 的 `error` |
| `executeGenerate` | `DATA.GENERATE` | `dataRequest { generate = dataGenerateRequest { schema = ""; luaVersion; tables += generateTable { script } } }` | `gen_progress_frame` 逐帧回填 `generateScripts[frame.scriptIndex - 1].inserted`（**`script_index` 是 1-based**）/ `.lastTable`；终止帧 `generate_terminal.tables_processed` → `generateTablesProcessed`；失败 → `generateError` |

> **注意** `SQL.EXECUTE` 是**流式**通道（`RequestDispatcher.streamSqlExecute`），必须用 `EngineClient.handle` + `collect`，不能走 `invoke`（`invoke` 只收终止帧，会丢掉全部行帧）。SELECT 逐行推 `sql_row_frame` 且末尾空帧；非 SELECT 只推一条带 `execute` 的终止帧。

> **注意** `pageSize = 0` 在 `DATA.LIST` 中是**流式哨兵**（逐行 `DataRowFrame`，无 paged body）；预览固定走分页路径，故请求侧 `coerceAtLeast(1)`。
>
> proto `ConnectionConfig.driver` 是各 handler 的必填字段（`SchemaHandler.list` / `ExportEngine` 先按 `driver` 取方言），因此 `engineConn` 必须同时写 `driver = cfg.dialect.name`、`jdbcUrl` 与凭据；**不要把库名写进 `schema` 字段** —— H2 会执行 `SET SCHEMA <dbname>` 并失败（H2 的 schema 是 `PUBLIC`，与 catalog 名无关）。
>
> **catalog 与 schema 是两个维度**：库名（catalog）只经 `engineConn(database = …)` 进 `ConnectionConfig.database`，
> 它参与连接池 key 并决定「浏览另一个库 = 另一个池」；请求里的 `schema` 字段必须**留空**，
> 引擎会把它交给 `PoolManager.getConnection(config, req.schema)` → `dialect.setSearchPath`。
> 写成库名会让 SQL / 造数工作台在用户展开任意库后直接失败。
>
> **每个建池的 catalog 维度都要登记**：`DatabaseBrowserState.activeDatabases` 是 `releasePools` 的唯一遍历来源，
> 因此 `refreshDatabases`（`""`）、`loadTables`（`database`）、`loadTabPreview`（`tab.schema`）以及
> `executeSql` / `executeGenerate`（`currentSchema()`）发起请求后都必须 `activeDatabases.add(...)`，
> 否则关 sheet 时那个池永远不会被 `disconnect` 回收。

**`ConnectionSession.kt` 内符号分解**：

| 符号 | 职责 |
|---|---|
| `ConnectionSession(engine, scope)` | 状态 + 行为容器。Compose 快照状态：`connectionList`（`ConnectionStorage` 镜像）、`selectedConnection`、`wizard: WizardState`、`statuses: Map<String, ConnectionStatus>`；行为：`newConnection` / `quickConnect` / `edit` / `updateEditing` / `goToStep` / `back` / `cancelEdit` / `save` / `quickConnectDirect` / `delete` / `connect` / `disconnect` / `testConnection` |
| `WizardState(editingConnection, step, flow)` | 三字段原子更新容器（单次赋值，避免 recomposition 间隙 NPE）；`WizardState.Idle` 为空闲态常量 |
| `engineConfig(config)` | private：UI 配置 → proto `ConnectionConfig` 映射（只传 `jdbcUrl` + `user` + `password`，方言由 URL scheme 反查；**注意 DSL 内勿写 `jdbcUrl = jdbcUrl`**，会自赋值到 builder 属性） |

---

## 数据库浏览 (`DatabaseBrowserScreen` / `DatabaseBrowserState`)

第二屏。`DatabaseBrowserScreen` 是纯展示组件（除内部 `remember` 的 `DatabaseBrowserState`），
全部数据获取与标签页管理落在 `DatabaseBrowserState`，因此可脱离 UI 直接驱动（见 `DatabaseBrowserFlowTest`）。

### 布局与交互

| 区域 | 组件 | 行为 |
|---|---|---|
| sheet 标签条 | `SheetTabRow` | 左侧「＋」入口 + 每 sheet 一个标签（连接状态点 + 名称 + 「×」关闭，关闭时释放池并断开引擎会话） |
| 内容区顶部工具栏 | `BrowserToolBar` | 「SQL 工作台」「造数工作台」两个入口切换右栏内容（`BrowserPane`）；处于某个工作台时该按钮显示「返回表预览」；未连接时禁用。**位于 `ActiveSheetContent` 内**（sheet 标签条之下），工具与它作用的连接同属一个视觉块 |
| 左侧 | `SchemaTreePanel` → `DatabaseNode` → `TableLeaf` | `SCHEMA.LIST` 结果按库分组；点击库节点懒加载 `TABLE.LIST`；**双击**表叶子 → `openTab` |
| 右侧（表预览） | `TabStrip` + `PreviewTabContent` | `SecondaryScrollableTabRow` 标签条（可逐页关闭）；内容为信息条 + `DataTable` |
| 右侧（SQL 工作台） | `SqlWorkbenchPane` + `WorkbenchTabStrip` + `SqlResultArea` | SQL sheet 标签条（＋ 新建 / ✎ 重命名弹窗 / × 关闭 / 滚动，每个 sheet 一份独立 SQL 与独立结果）+ 上 60% `CodeEditorWithToolbar`（`:shared` editor 模块；SQL 高亮 + 格式化 + `actions` 插槽的「执行 SQL」）+ 底部 40% 结果面板；「执行 SQL」走 `SQL.EXECUTE` 流式通道，只作用于当前 sheet |
| 右侧（造数工作台） | `GenerateWorkbenchPane` + `WorkbenchTabStrip` + `GenerateResultArea` | 脚本标签条（多脚本按序执行，与 SQL 工作台共用同一标签条组件）+ Lua 版本 chip + 上 60% Lua 编辑器（`actions` 插槽的「执行造数」）+ 底部 40% 造数结果；「执行造数」走 `DATA.GENERATE` 流式通道，进度帧实时回填每个脚本的插入行数 |

> **注意** `:shared` 的编辑器语言与 formatter 由 `registerBuiltinEditors()` 注册到全局注册表，**app 启动时必须调一次**（`main()` 开头，幂等）。不注册则 `CodeLanguageRegistry.get("sql")` 返回 null，编辑器静默退化为无高亮纯文本。

### 标签页去重契约

```kotlin
fun openTab(schema: String, tableName: String) {
    val tabKey = "$schema::$tableName"
    val existingIndex = tabs.indexOfFirst { it.key == tabKey }
    if (existingIndex >= 0) {         // 已打开 —— 只激活，不重复加载
        selectedTabIndex = existingIndex
        return
    }
    val tab = TablePreviewTab(schema = schema, tableName = tableName)
    tabs = tabs + tab
    selectedTabIndex = tabs.size - 1
    loadTabPreview(tab)
}
```

`TablePreviewTab.key` 是**唯一去重依据**（`schema::table`），与 `TableRow.id`（行主键）不是一回事。

### 会话代次（丢弃过期响应）

```kotlin
fun bindConnection(config: ConnectionConfig?) {
    if (currentConnection?.id == config?.id) return   // 同连接是空操作
    currentConnection = config
    generation++                                      // 使所有 in-flight 响应作废
    databases = emptyList(); loadingDatabases = false; expandedDatabases.clear()
    _tablesByDatabase.clear(); loadingTables.clear(); _tableLoadError.clear()
    tabs = emptyList(); selectedTabIndex = -1
}

private fun loadTables(database: String) {
    val requestGeneration = generation                 // 捕获发起时代次
    …
    scope.launch {
        val result = runCatching { engine.invoke(…) }
        if (requestGeneration != generation) return@launch   // 连接已切换 → 丢弃
        result.fold(…)              // 先写数据
        loadingTables.remove(database)   // 最后清 loading —— 顺序不可交换
    }
}
```

三个异步入口（`refreshDatabases` / `loadTables` / `loadTabPreview`）都套用同一模式：
**发起时捕获 `generation`，挂起点之后比对，不一致则直接返回**，避免旧连接的结果污染新连接的已清空状态。

### loading 标志的写入顺序（不可交换）

三个异步入口都**先写数据、最后清 loading**：

```kotlin
if (requestGeneration != generation) return@launch   // 过期 → 什么都不碰
result.fold(onSuccess = { … tab.rows = … }, onFailure = { … })
tab.loading = false                                  // ← 必须在 fold 之后
```

顺序反了会有可观察的错误：`loading` 与数据是**各自独立的快照状态**，写在同一协程里也是两次提交。
任何「轮询 `loading` 直到为 false，再读数据」的调用方（`DatabaseBrowserFlowTest` 就是这样），
都可能正好落在两次提交之间 —— 看到 `loading == false` 却读到上一次的空数据，
表现为 `users row count expected:<2> but was:<0>` 的间歇性失败。

同理，`bindConnection` 必须复位 `loadingDatabases`：切换连接时若有 in-flight 刷新，
它的响应会被代次检查丢弃、协程直接 `return`，若不复位则 `loading` 永远为 `true`，
顶部刷新指示器会一直转。

### 连接池生命周期（浏览场景）

浏览另一个 catalog 会用到另一份 proto config（`database` 参与池 key），因此**连接管理页的「断开」
只释放它自己那份池**；本屏必须释放自己建立的池：

| 触发 | 动作 |
|---|---|
| 切换到另一个连接（`bindConnection` 检测 id 变化） | `releasePools()`（针对**旧**连接） |
| 会话状态变为断开 / 失败（`LaunchedEffect` 的 `else` 分支） | `releasePools()` |
| 窗口关闭 | `EngineClient.close()`（默认实现下即 `IdbEngine.close()` → `PoolManager.closeAll()` 兜底全部） |

`activeDatabases` 记录本屏请求过的 catalog 维度（含 `""` = 默认），`releasePools` 逐个
`engine.disconnect(engineConnFor(config, database))`。

> **必须由调用方的 scope 执行**：`releasePools` 是异步的（`engine.disconnect` 为挂起调用；Direct 实现内部 `withContext(IO)`）。
> 若用组件自己的 `rememberCoroutineScope()`，`onDispose` 时该 scope 已被取消 —— 释放动作会被丢弃。
> 因此 `DatabaseBrowserState` 在 `MainScreen` 中创建并复用其 `rememberCoroutineScope()`，这同时带来
> 第二个好处：切到「连接管理」再切回来时标签页仍在。

### H2 列名归一

H2 把未引用标识符归一为大写（`users` → `USERS`），MySQL 保持小写。预览行的主键承载因此做
**大小写不敏感**匹配（`k.equals("id", ignoreCase = true)`），找不到时退化为行号。
引擎 `DataHandler.buildRow` 对非 LOB 列一律 `rs.getString` —— 单元格值在 UI 侧都是字符串。

---

## 连接管理 (`ConnectionSession` + `MainScreen`)

`ConnectionSession` 持有全部状态与行为，`ConnectionManagerScreen` 是纯展示组件；
`MainScreen` 负责顶层导航（`AppDestination`）并把会话状态透传给两个屏幕。

- 从 `~/.config/sundays/connection.json` 加载连接列表（`ConnectionStorage.load()`，`ConnectionSession` 初始化时读取）
- 维护 `WizardState(editingConnection, step, flow)` **data class** —— 单次赋值保证原子更新，避免 Compose recomposition 间隙 NPE
- 三个入口：
  - **左侧「快速连接」按钮**（⚡）→ `flow = QUICK_CONNECT`
  - **左侧「新建连接」按钮**（＋）→ `flow = NORMAL`，起始 `BASIC_INFO`
  - **列表项「编辑」菜单** → `flow = NORMAL`，起始 `BASIC_INFO`（保留原有配置）
- 新建 / 快速连接都经 `withDialect(MYSQL)` 播种：立即套用方言默认值并折算 JDBC URL，保证进入凭据步骤即有合法 URL

### 流程对照表

| 入口 | flow | 步骤序列 | 最后一步 |
|---|---|---|---|
| 新建 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → `ConnectionStorage.upsert()` |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT → CREDENTIALS → TEST_SAVE` (3 步，跳过 BASIC_INFO / CONNECTION_TYPE) | 「连接」 → **不写入** `ConnectionStorage`，直接 `engine.testConnection()` 连库 |
| 编辑 | `NORMAL` | `BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE` (4 步) | 「保存」 → 覆盖原配置（字段变化时先释放旧连接池） |

`ConnectionSession` 在每次切换入口时**同步**设置 `flow`，确保 `ConnectionWizardPanel` 的步骤指示器自适应总数（4 vs 3）。

### 连接生命周期（引擎侧效果）

| UI 动作 | `ConnectionSession` | 引擎调用 | 结果 |
|---|---|---|---|
| 测试连接（`TEST_SAVE` 步骤） | `testConnection(config)` | `EngineClient.testConnection` | 建/复用 HikariCP 池 + `isValid`，状态 → `CONNECTED` / `FAILED` |
| 连接（连接总览 / 快速连接末步） | `connect(config)` | `EngineClient.testConnection` | 同上；状态先置 `CONNECTING` 再回填 |
| 断开（连接总览） | `disconnect(config)` | `EngineClient.disconnect` | 释放该配置的池，状态 → `DISCONNECTED` |
| 删除连接 / 编辑后字段变化 | `delete` / `save` | `EngineClient.disconnect` | 先释放旧配置的池再落盘删除 / 覆盖 |
| 窗口关闭 | — | `EngineClient.close` | 释放实现自身持有的资源（本地：池 / 驱动 / 方言；gRPC：channel） |

> **两种实现下调用代码完全相同**。默认的 `IdbEngine` 走同进程直调（`testConnection` / `disconnect` 旁路 gRPC 与
> `RequestDispatcher`）；gRPC 绑定下二者走线协议 `SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT` ——
> 后者是 v2.15 新增的路由（连接池活在引擎进程里，远程调用方需要一条线路由来释放它们）；`disconnect` 幂等。

会话状态 `ConnectionStatus(state, message)`（`DISCONNECTED` / `CONNECTING` / `CONNECTED` / `FAILED` + 说明）
回传给 `ConnectionManagerScreen`：列表项色点、连接总览面板的状态行与「连接」/「断开」按钮的可用性都由它驱动。

### 状态原子更新模式

```kotlin
data class WizardState(
    val editingConnection: ConnectionConfig?,
    val step: WizardStep,
    val flow: WizardFlow,
) {
    companion object { val Idle = WizardState(null, WizardStep.IDLE, WizardFlow.NORMAL) }
}

// 边缘 —— 分两次赋值（崩溃风险：recomposition 间隙 editingConnection 为 null）
editingConnection = newCfg
step = WizardStep.QUICK_CONNECT

// 边缘 —— 单次赋值（安全）
wizard = WizardState(editingConnection = newCfg, step = WizardStep.QUICK_CONNECT, flow = WizardFlow.QUICK_CONNECT)
```

### 关键实现

```kotlin
// ConnectionSession.kt
class ConnectionSession(private val engine: EngineClient, private val scope: CoroutineScope) {
    var connectionList by mutableStateOf(ConnectionStorage.load()); private set
    var selectedConnection by mutableStateOf<ConnectionConfig?>(null); private set
    var wizard by mutableStateOf(WizardState.Idle); private set
    var statuses by mutableStateOf<Map<String, ConnectionStatus>>(emptyMap()); private set

    fun connect(config: ConnectionConfig) {
        statuses = statuses + (config.id to ConnectionStatus(ConnectionState.CONNECTING))
        scope.launch {
            val status = runCatching { engine.testConnection(engineConfig(config)) }.fold(
                onSuccess = { resp ->
                    if (resp.ok) ConnectionStatus(ConnectionState.CONNECTED, resp.driver)
                    else ConnectionStatus(ConnectionState.FAILED, resp.error.ifBlank { "连接失败" })
                },
                onFailure = { ConnectionStatus(ConnectionState.FAILED, it.message ?: "Unknown error") },
            )
            statuses = statuses + (config.id to status)
        }
    }

    fun disconnect(config: ConnectionConfig) {
        scope.launch {
            runCatching { engine.disconnect(engineConfig(config)) }
            statuses = statuses + (config.id to ConnectionStatus())
        }
    }

    suspend fun testConnection(config: ConnectionConfig): TestResult { /* 建池 + 校验 + 同步状态 */ }

    fun save(config: ConnectionConfig) {
        // 编辑已有连接且字段变化 → 旧配置的连接池先释放（池按字段 hash 缓存，否则成为僵尸池）
        connectionList.connections.find { it.id == config.id }?.takeIf { it != config }?.let { disconnect(it) }
        connectionList = ConnectionStorage.upsert(config)
        selectedConnection = config
        wizard = WizardState.Idle
    }
}

/** UI 配置 → proto：只传 jdbcUrl + 凭据，方言由 URL scheme 反查 */
private fun engineConfig(config: ConnectionConfig) = connectionConfig {
    jdbcUrl = config.jdbcUrl
    user = config.username
    password = config.password
}
```

`onTestConnection` / `onConnect` 是**连接初始化**的入口 —— `EngineClient.testConnection`（默认的 Direct 实现
旁路 gRPC 与 `RequestDispatcher`，直接调 `SystemHandler`；gRPC 实现走线路由）；首次调用用 `jdbc_url` 建
HikariCP 池（这一步就是“初始化连接”），之后按 hash key 复用；`EngineClient.disconnect` 用同一 key 定位并
关闭该配置的所有池。

### 持久化路径

```text
~/.config/sundays/connection.json   ←  ConnectionStorage.load() / upsert() / delete()
                                              ↑
                                       NORMAL 流程「保存」时调用
                                       QUICK_CONNECT 流程「连接」时不调用
```

## 引擎集成架构（`EngineClient` 绑定）

### 核心契约

```kotlin
// desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt
const val ENDPOINT_PROPERTY = "sundays.engine.endpoint"

internal fun createEngineClient(): EngineClient {
    val endpoint = System.getProperty(ENDPOINT_PROPERTY).orEmpty().trim()
    if (endpoint.isEmpty()) return IdbEngine()                               // 默认：同进程
    return GrpcEngineClient.connect(GrpcClientConfig.fromTarget(endpoint))  // 显式：跨进程 gRPC
}

val engine: EngineClient = createEngineClient()                             // main() 内的唯一装配点
Window(
    onCloseRequest = {
        engine.close()                                                        // 释放实现自身资源（幂等）
        exitApplication()
    },
) { /* Compose UI */ }
```

UI 侧（`ConnectionSession` / `DatabaseBrowserState`）只声明 `EngineClient` 字段，**对绑定哪个实现完全无感**；
`EngineClientSelectionTest` 锁定 `createEngineClient()` 的绑定行为。

### 关键设计决策

#### 1. **构造即 bootstrap（幂等）**（仅默认的 Direct 绑定）

`IdbEngine()` 构造函数内部触发 `DriverLoader` + `DialectLoader` 的加载。**重复构造是幂等的**（`bootstrap` 用 `AtomicBoolean` 单例保护），所以桌面应用启动时调用一次即可，无需担心后续 ViewModel 多次持有引用导致重复加载。

`DialectLoader` 有两条方言来源，桌面应用走**第一条**：

1. **应用类路径 SPI**（`ServiceLoader<DatabaseDialect>`，使用 `DialectLoader` 自身类加载器）—— 方言插件作为普通依赖随应用类路径加载（`desktopApp` 的 `runtimeOnly(project(":dialect-*"))`），Direct 模式下 UI 与引擎同 JVM，接口类型必须由同一类加载器解析
2. **插件目录**（`dialects/` 目录 + JAR 同级 `dialects/`，`URLClassLoader`）—— JAR 分发场景；同名方言覆盖类路径版本

启动日志可确认：`Registered 5 dialect plugin(s) from application classpath` → `IdbEngine bootstrap complete`。JDBC 驱动同样以 `runtimeOnly` 上到应用类路径（HikariCP 按 `driverClassName` 直接实例化）。

#### 2. **默认不启动子进程、不走 gRPC**

这是**默认绑定**的行为（未设置 `sundays.engine.endpoint` 时）。设了端点属性则改为 `GrpcEngineClient`，
引擎在独立进程、应用经 gRPC 调用 —— 但 UI 侧代码一行不用改。

`desktopApp/build.gradle.kts` 显式声明（两个实现都在编译期可见，运行期由装配点二选一）：

```kotlin
implementation(project(":engine"))             // 默认实现：同进程直调，无 IPC transport 依赖
implementation(project(":engine-grpc-client")) // 端点实现：跨进程 gRPC
// :engine 以 implementation 声明 protobuf/grpc，不传递给消费方编译类路径 —— 集成层
// 需要 typed proto 类型（ConnectionConfig / SystemTestConnectionResponse / Response）才能
// 调用 EngineClient API，因此显式补齐：
implementation(libs.protobuf.java)
implementation(libs.protobuf.kotlin.lite)
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

| 操作 | gRPC 绑定（独立子进程） | Direct 绑定（默认，library 集成） |
|---|---|---|
| 启动 | 先起引擎进程 `java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051`，应用带 `-Dsundays.engine.endpoint=localhost:50051` 启动 | 父 JVM 构造 `IdbEngine()` |
| 资源持有 | 子进程独立内存（连接池 / 驱动 / 方言都在引擎进程内） | 父 JVM 共享类加载器 / 连接池 / 驱动 |
| 关闭清理 | 客户端侧 `engine.close()` 关闭 gRPC channel；引擎进程内的池 / 驱动 / 方言由**引擎进程**退出时释放 | **仅需** 在 `Window.onCloseRequest` 中调 `engine.close()` —— **不需要** Shutdown Hook（library 不是独立进程，JVM 退出时 GC 自然回收） |
| 调试 | 跨进程 attach | 直接 IDE debug |

> **关键洞察**：Direct 绑定下 `engine.close()` **不依赖 JVM Shutdown Hook 兜底** —— `engine` 不是独立进程的入口，
> 而是 JVM 内的一个 library 对象；其生命周期完全由 Compose UI 的 `Window.onCloseRequest` 控制。
>
> `EngineClient.close()` **对两种实现都幂等**，且只释放「实现自身持有的资源」：本地实现释放连接池 / 驱动 /
> 方言，gRPC 实现关闭 channel（channel 关闭后再发请求会失败）。gRPC 绑定下**引擎进程**另有自己的 Shutdown Hook
> 负责进程内资源 —— 那是引擎侧的事，不在 desktopApp 的清理路径上。

---

## 生命周期管理

### Window 关闭序列

```kotlin
Window(
    onCloseRequest = {
        engine.close()                                             // ① 释放引擎资源（全部连接池 / 驱动 / 方言）
        exitApplication()                                           // ② 退出 application{}
    },
    title = "sundays",
) { ... }
```

**两步清理顺序**：

1. **`engine.close()`** — `EngineClient.close()`，幂等。默认 Direct 绑定下即 `IdbEngine` facade 内部调用 `PoolManager.closeAll()`（关闭该应用建立的所有 HikariCP 连接池）+ `DriverLoader.closeAll()`（卸载所有 JDBC 驱动）+ `DialectLoader.closeAll()`（关闭所有方言 SPI 实例）；gRPC 绑定下关闭 gRPC channel，引擎进程内的资源由引擎进程自己负责
2. **`exitApplication()`** — Compose Desktop `application{}` 的退出点，触发所有 `Window` 的销毁

> 协程侧无需额外清理：`ConnectionSession` 的 `connect` / `disconnect` 跑在 `rememberCoroutineScope()` 上，
> 随组合销毁自动取消；引擎调用本身不持有长驻后台任务（Direct 走同进程直调，gRPC 走单次 RPC）。

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
| [`desktopApp/README.md`](./README.md) | desktopApp 用户级 README（运行命令 / 演示功能 / 端点属性与两种绑定） |
| [根目录 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | V2.9 完整架构设计文档（gRPC 协议 / handler 矩阵 / 方言特性 / 双模式架构） |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎内部架构（`IdbEngine` facade 详解 / `RequestDispatcher` / `PoolManager` / `Loader`） |
| [`engine/README.md`](../engine/README.md) | 引擎用户级 README（CLI / 构建运行 / API 参考 / Direct 模式示例） |
| [`engine-protocol/README.md`](../engine-protocol/README.md) | 调用层契约（`EngineClient` 接口 / 两个实现的对照 / 生命周期与线程安全约定） |
| [`engine-grpc-client/README.md`](../engine-grpc-client/README.md) | gRPC 引擎客户端（`GrpcEngineClient` / `GrpcClientConfig.fromTarget` 端点写法 / 启动与连接方式） |
| [`shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | 共享 UI 组件架构（`CodeEditor` / `DataTable` / `ConnectionManagerScreen` 含 JDBC URL 折算与连接总览 / 右键菜单） |

---

## 后续迭代方向（v2.15+）

- **真正的数据库管理 UI**：Schema 导航（基于连接池后的 `SCHEMA.LIST`）/ SQL 编辑器面板（嵌入 `CodeEditor`）/ 查询结果表（嵌入 `DataTable`）✅ 已落地（`SqlWorkbenchPane`）；连接表单可进一步改为按 `SYSTEM.LIST_DRIVERS` 的 `DialectInfo` 动态渲染
- **连接重连与会话信息**：总览面板展示 `SYSTEM.SERVER_INFO`（版本 / 模式）；断线自动重连
- **多 Window 支持**：当前 `main()` 仅创建单个 `Window`；后续按需支持多 Window（每个连接一个 Window）
- **KMP 平台扩展**：新增 `androidMain` / `iosMain` / `wasmJsMain` source set（共享 `commonMain` 业务层）
- **设置持久化**：编辑器偏好等非连接配置（KMP `MultiplatformSettings`）