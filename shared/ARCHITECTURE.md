# `shared/` — KMP 共享 UI 组件架构

> **版本**：v2.14（与根 `../ARCHITECTURE.md` 同版本）
>
> **模块定位**：与 `:engine` 解耦的纯 UI 组件库，通过 KMP `commonMain` 单一 source set 承载所有业务组件，`jvm` 平台特定逻辑最小化。
>
> **本文档的形态**：**大纲 + 索引**。架构分层、组件清单、设计决策的「结论 + 代码链接」留在本文；
> **实现细节、踩坑记录、设计理由一律在对应源码的 KDoc / 注释里** —— 细节跟着它描述的代码走，
> 才不会与代码脱节。改代码时先看 KDoc，本文只回答「这个模块由什么组成、为什么这样分层」。

---

## 1. 概述

### 1.1 模块目的

`shared/` 提供面向 **Compose Multiplatform Desktop**（Kotlin 2.4.0 + Compose Foundation 1.x）的可扩展 UI 组件：

| 组件 | 一句话 | 源码 |
|---|---|---|
| **CodeEditor** | 语法高亮 + 行号 + 工具栏 + 格式化 + 补全 + 右键菜单 | [`editor/ui/CodeEditor.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CodeEditor.kt) |
| **DataTable** | 虚拟滚动 + 分页 + 详情面板 + 单元格可选中 / 可编辑 + 右键菜单 | [`table/DataTable.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/DataTable.kt) |
| **ConnectionManagerScreen** | 连接管理（左侧连接列表 + 右侧 4 步 / 3 步向导 + 连接总览） | [`connection/ConnectionManagerScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionManagerScreen.kt) |
| **AppDestination** | 顶层导航目标枚举 | [`navigation/AppDestination.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/navigation/AppDestination.kt) |
| **SundaysTheme / ThemePalette / SundaysPalette** | 三轴外观（配色 / 明暗 / 尺度）+ 视觉规范 | [`ui/Theme.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/Theme.kt) |
| **SettingsScreen / SettingsStorage** | 设置页（左分类 / 右内容）与设置持久化 | [`settings/SettingsScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/settings/SettingsScreen.kt) |
| **OnboardingScreen** | 首次启动外观引导（配色 / 明暗 / 密度 / 语言） | [`onboarding/OnboardingScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/onboarding/OnboardingScreen.kt) |
| **TableStructureDialog** | 表结构编辑弹窗（字段 / 主键 / 外键，类型下拉跟着方言走） | [`table/TableStructureDialog.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/TableStructureDialog.kt) |
| **SQL 文本工具** | 危险写操作识别与只读判定、WHERE 字面量转义与 CAST 类型、危险语句确认框 | [`sql/`](./src/commonMain/kotlin/com/langbiantianya/sundays/sql/) |
| **通用 UI 工具** | `ContextMenuState<T>` + `Modifier.onRightClick` + `DragHandle` + `FocusRing` + `EmptyHint` + `ColumnBadge` + `TabRenameDialog` + 复古控件包装 | [`ui/`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/) |

### 1.2 KMP Source Set 布局

```
shared/
├── commonMain/        所有业务 UI 组件（平台无关）：editor/ table/ connection/ navigation/ onboarding/ settings/ sql/ ui/
├── commonTest/        平台无关测试（tokenizer / 模型 / 集成 / 布局契约）
├── jvmMain/           当前为空 —— 无平台特定实现（`commonMain` 全量可用）
└── jvmTest/           JVM 特定测试（文件持久化与 POSIX 权限）
```

**当前仅启用 `jvm` 单一目标**（macOS / Linux / Windows Desktop）。KMP 工程结构天然支持后续扩展 `androidMain` / `iosMain` / `wasmJsMain` —— `commonMain` 中的组件零修改复用，只需新增 source set 提供平台特定的 `pointerInput` / `Okio` 适配。

### 1.3 为什么与 `:engine` 解耦

`shared/` **没有任何对 `:engine` 或 gRPC/protobuf 的依赖**，依赖方向是单向的 `desktopApp → {engine, shared}`，`engine ↮ shared`。动机有四：组件能在无引擎进程 / 无数据库连接的情况下跑单测；`CodeEditor` / `DataTable` 是通用 UI，不必带 `:engine` 这颗大依赖；`:engine` 不需要任何 UI 因此不存在循环；`shared/build.gradle.kts` 不依赖任何引擎模块 —— 这是物理上的保证。

**「什么算可上移的共享代码」的判据**：

| 代码 | 是否引用 `:engine` | 归属 |
|---|---|---|
| `AppDestination` / `SundaysTheme` / `ThemePalette` | 否（纯 Compose） | ✅ `shared/commonMain`（v2.14 上移） |
| `ConnectionManagerScreen`（回调注入） | 否（引擎调用由 `onTestConnection` 等回调注入） | ✅ `shared/commonMain` |
| `TableStructure*`（草稿 / 差分 / 校验 / 编辑弹窗） | 否（只依赖 Compose 与 `DialectType` / `ColumnTypeCatalog`） | ✅ `shared/commonMain` |
| `DangerousSql` / `SqlLiterals` / 危险语句确认框 | 否（纯字符串扫描与转义） | ✅ `shared/commonMain` |
| `ConnectionSession` | 是（`IdbEngine.testConnection` / `disconnect`） | ❌ `desktopApp` |
| `DatabaseBrowserState` / `DatabaseBrowserScreen` | 是（`IdbEngine.invoke` + proto 构造器） | ❌ `desktopApp` |

`:engine` 是纯 JVM 模块，因此任何引用 `IdbEngine` 或 proto 类型的代码**都无法进入 `commonMain`** —— 状态机与引擎接线必须留在 `desktopApp`。

> ⚠️ 这条边界**只在代码里引用了 proto 时才生效**，写一段 KDoc 提到 `com.langbiantianya.grpc.ColumnDef` 不会挡住搬迁。
> 判据是「代码引用」，不是「文本出现」—— 搬完顺手把 KDoc 里的全名改成短名，否则会留下一个在 commonMain 里永远解析不了的悬空链接。

> ⚠️ `internal` 在 Kotlin 里是**模块内**可见。代码从 `desktopApp` 搬到 `shared` 之后，原来只给同模块用的声明会突然编译不过；
> 反过来，不加区分地全改成 `public` 又会把测试 tag 契约变成一锅谁都能看见的公共面。逐个按「消费方真的引用到了吗」放开。

---

## 2. CodeEditor

主入口 [`editor/ui/CodeEditor.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CodeEditor.kt)（`CodeEditor` / `CodeEditorWithToolbar` / `CodeEditorState` / `registerBuiltinEditors`）。
扩展点：语言 [`editor/CodeLanguage.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/CodeLanguage.kt)、补全 [`editor/Completion.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/Completion.kt)、弹层 [`editor/ui/CompletionPopup.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CompletionPopup.kt)、格式化 [`editor/formatter/`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/formatter/)、方言档位 [`editor/language/SqlDialectProfile.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/language/SqlDialectProfile.kt)。

### 2.1 关键设计决策索引

| # | 决策 | 结论 | 位置 |
|---|---|---|---|
| 2.1 | **高度策略** | `maxLines = null`（默认）即 `fillMaxHeight()`：填满父容器剩余高度、不超父容器；显式传整数才启用 `heightIn` 上下限 | `CodeEditor` KDoc「高度策略」 |
| 2.1 | **`verticalScroll` 的子项无法 `fillMaxHeight`** | 尺寸必须由外层 `BoxWithConstraints` 在**滚动之前**量，再以 `heightIn(min=)` 撑满输入区，否则输入区只有一行高、点不到 | `CodeEditor` KDoc「[!] 陷阱」 |
| 2.2 | **`CodeEditorState` 归属** | 默认随组合存活；跨「工作台 ↔ 表预览」这类**会离开组合**的场景时，由调用方状态机持有并经 `editorState` 传入 | `CodeEditorState` KDoc |
| 2.2 | **`setText` 保留光标** | 受控输入下每个键都会回调它，光标弹到文末会让用户无法在文本中间编辑；文本未变时是空操作 | `CodeEditorState.setText` |
| 2.3 | **行号 gutter 同步滚动** | gutter **不**自己 `verticalScroll`，而是与 `BasicTextField` 同处一个 `verticalScroll` 容器、共享同一个 `ScrollState` | `LineNumberGutter` KDoc |
| 2.4 | **tokenize 在 UI 线程同步执行** | SQL / Lua 典型体量（< 10K 行）足够快；`remember(language, highlighter, text)` 复用 `SyntaxHighlighter` 与 `VisualTransformation` | `CodeVisualTransformation` / `rememberCodeHighlighter` |
| 2.5 | **格式化契约：空格只看 token 身份** | 「两向贴合」两个纯函数（`bindsLeft` / `bindsRight`），不用 `pendingSpace` 标志 —— 顺带消灭了「格式化两次才收敛」的一整类现象 | [`formatter/TokenSpacing.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/formatter/TokenSpacing.kt) |
| 2.5 | **缩进原样搬运、注释只读、空行至多留一个** | 重新缩进需要真正的语法分析，半吊子重排比不改更糟；注释里的代码样例不能被换行规则吞掉 | `formatter/SqlFormatter.kt`、`formatter/LuaFormatter.kt` |
| 2.6 | **SQL 方言档位 = 一种语言** | 档位的 `languageId` 就是 `CodeLanguage.id`，换方言 = 换语言；语言与 formatter **配对注册**，编辑器零改动 | `SqlDialectProfile` / `SqlFormatter.register` |
| 2.7 | **补全触发前缀 = 1 个字符** | 条数被 `maxCompletionItems` 封顶，「命中多」≠「显示多」；少一次按键的收益是每次都有的 | `Completion.kt` 的 `MIN_COMPLETION_PREFIX` |
| 2.7 | **词字符限定 ASCII** | `Char.isLetterOrDigit()` 对中文返回 `true`，`-- 查询sel` 的前缀会算成「查询sel」，补全直接失灵 | `Completion.kt` 的 `wordPrefixBefore` |
| 2.7 | **上下文专属候选走 `extraCompletions`** | 造数沙箱的 `insert` / `random_*` 只在那个沙箱里存在，塞进 `LuaLanguage` 会让**所有** Lua 补全推荐不存在的函数 | `CodeEditor` 参数表 + [`editor/GenerateHelpers.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/GenerateHelpers.kt) |
| 2.8 | **弹层贴光标 + 靠右翻转** | 翻转而非钳位：无论往哪边展开，锚点都在光标上；判据用 `>` 不用 `>=` | `CompletionPopup.kt` 的 `completionPopupMaxWidth` / `completionPopupX` |
| 2.9 | **未注册语言 → 静默退化为纯文本** | 不是崩溃也不是空屏，只是没颜色 —— 所以「界面突然没颜色」要先想到这条 | `CodeEditor.kt` 的 `registerBuiltinEditors` |

补全的其余契约（候选排序、SQL / Lua 大小写策略差异、接受候选替换整个词、`onPreviewKeyEvent` 与 `getCursorRect` 钳位）见 `Completion.kt` / `CompletionPopup.kt` / `CodeEditor.kt` 的 KDoc。

---

## 3. DataTable

主入口 [`table/DataTable.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/DataTable.kt)，行 / 列 / 分页 / 主题模型见 [`table/TableModels.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/TableModels.kt)。

### 3.1 关键设计决策索引

| # | 决策 | 结论 | 位置 |
|---|---|---|---|
| 3.1.1 | **表头 / 表体必须共用一个横向 `ScrollState`** | 两处各自 `rememberScrollState()` 就是「表头能滚、表体不能滚」的成因 —— 对数据库工具来说那是**读错数据**，不是体验问题 | `DataTable` 调用点 + `TableHeader` / `TableBody` |
| 3.1.2 | **⚠️ `width` 必须排在 `horizontalScroll` 内侧** | `Modifier.width` 是**按传入约束钳位**，排外面会被父视口宽钳死，表头与表体于是用**两套宽度基准**（实测 43dp vs 102dp），列名整体错位 | `TableHeader` KDoc |
| 3.1.3 | **横向滚动条必须常驻可见，拇指用墨色** | 表面色在深色档全挤在 `#232833~#333A48`，互相只有 1.1~1.3:1，等于没画；`cellText.color` 是 9.6:1。不做「溢出才出现」的条件渲染（列数会变，会引起布局跳动） | `DataTable` 调用点（`HorizontalScrollbar`）+ `TableHorizontalScrollbarTest` |
| 3.2 | **高度策略** | `fillParentHeight = true`（默认）即 `fillMaxSize()`，与 `CodeEditor.maxLines = null` 同语义 | `DataTable` 参数表 |
| 3.2.1 | **分页在哪一侧（`serverSidePaging`）** | 两种模式不能混用：混用会切出**静默的空表**（第 2 页起 `start >= rows.size`）。服务端模式必须成对传 `serverSidePaging = true` + 整表行数 `totalCount` | `DataTable` 的 `serverSidePaging` 参数表 |
| 3.2.2 | **分页底栏的降级顺序** | < 560dp 隐藏「首页 / 末页 / 共 N 条」（牺牲**描述**、保留**操作**），左组用 `weight(1f, fill=false)` 承担压缩，**翻页按钮绝不被压没** | `DataTable.kt` 的 `PAGINATION_COMPACT_WIDTH` |
| 3.3 | **详情面板插槽** | 不传 `detailPanel` 即用 `DefaultDetailPanel`（按列的 `formatter` 渲染 key-value）；`detailPanelRatio` 由 `require()` 校验 ∈ [0,1] | `DataTable` 参数表 |
| 3.4 | **单元格可编辑是显式开启** | 组件**不执行任何 SQL**，只报告「哪一行哪一列被改成什么」；把「这张表能不能改」做成独立参数是因为浏览屏今天接线已就绪但引擎 `DATA.LIST` 不回主键 | `DataTable` 的 `onCellEdit` / `cellEditable` 参数表 |
| 3.5 | **右键菜单插槽** | 目标行通过 `contextMenuItems` 的 `row: TableRow?` 参数或 `menuState.payload` 访问（唯一来源，无兼容别名） | `DataTable` 参数表 |

---

## 4. ConnectionManagerScreen

主入口 [`connection/ConnectionManagerScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionManagerScreen.kt)（左侧列表 + 向导 + 连接总览 + 状态色点，引擎调用全部由回调注入）。字段 ↔ URL 折算见 [`connection/JdbcUrl.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/JdbcUrl.kt)，持久化见 [`connection/ConnectionStorage.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionStorage.kt)，模型见 [`connection/ConnectionConfig.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionConfig.kt)。

### 4.1 关键设计决策索引

| # | 决策 | 结论 | 位置 |
|---|---|---|---|
| 4.1 | **`jdbcUrl` 是唯一真相源** | 引擎侧 `PoolManager` 收到非空 `jdbc_url` 就直接用它建池，方言由 URL scheme 反查 —— 因此 UI 的**所有**字段编辑都必须折算到 URL 上 | `JdbcUrl.kt` |
| 4.2 | **userinfo 只有 MySQL 写进 URL，且按 RFC 3986 percent-encode** | `org.postgresql.Driver` 根本不认 userinfo，会把整段当主机名 → PG 连不上。解析侧用 `lastIndexOf('@')` / `lastIndexOf('/')`，否则密码含 `@` `/` `:` 时会截断 host 或库名 | `JdbcUrl.kt` 的 `encodeUserInfo` / `decodeUserInfo` / `clientServerUrl` |
| 4.3 | **PG userinfo 的存量与粘贴修复** | `normalizeJdbcUrl` / `withParsedJdbcUrl` 是这两类修复的**唯一产地**；只在内存里归一化，不改磁盘格式与版本号 | `JdbcUrl.kt` |
| 4.4 | **URL 缺失不可放行** | 字段不足以折算 URL 时，「下一步 / 保存 / 连接 / 测试连接」全部禁用 —— 保证交给引擎的配置一定有合法 URL | `JdbcUrl.kt` 的 `buildJdbcUrl` + 向导各步 |
| 4.5 | **`DialectType.engineDriverName` 是跨模块名字契约** | 引擎注册键是 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite`，与本枚举常量名**大小写不同**；用 `Enum.name` 会让引擎抛 `No dialect plugin loaded for driver: MYSQL`。只有 H2 两侧同名，所以只测 H2 时发现不了 | `ConnectionConfig.kt` |
| 4.6 | **持久化只落 `jdbcUrl` + 凭据** | `host` / `port` / `database` / `connectionType` 加载时由 `parseJdbcUrl` 重建；按文件内 `version` 分派 v1/v2 并自动迁移 | `ConnectionStorage.kt` |
| 4.7 | **凭据文件权限收紧到 0700 / 0600** | 文件内含明文口令。**建目录与收权限两处都要兜底** —— 带 POSIX 属性的建目录在 Windows 上直接抛，整个保存静默失效 | `ConnectionStorage.kt`（含 Windows 差异结案记录） |
| 4.8 | **向导步骤单调、无本地字段副本** | 各步骤经 `onUpdateEditingConnection` 直写调用方状态，不存在「字段改了但配置没变」的丢失路径；URL 文本框与字段互为投影，不需要循环防护 flag | `ConnectionManagerScreen.kt` 各步骤页 |
| 4.9 | **`SqlIdentifier` 在前端侧维护第二份** | 「复制引用名」是纯本地动作，为此发一次引擎往返是倒退；代价是引号风格要两处同步，由 `SqlIdentifierQuoteTest` 钉住。与 H2 的 **不折叠大小写** 分歧是**故意的** | [`connection/SqlIdentifier.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/SqlIdentifier.kt) |

**两种引导流程**：`WizardFlow.NORMAL`（`BASIC_INFO → CONNECTION_TYPE → CREDENTIALS → TEST_SAVE`，4 步）与 `WizardFlow.QUICK_CONNECT`（`QUICK_CONNECT → CREDENTIALS → TEST_SAVE`，3 步）。调用方维护 `wizardFlow` 并在新建 / 快速连接 / 编辑三个入口同步设置；快速连接的「连接」**不落盘**。参考实现见 [`desktopApp/.../ConnectionSession.kt`](../desktopApp/src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt)。

---

## 5. 顶层导航与应用主题

### 5.1 `AppDestination`

[`navigation/AppDestination.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/navigation/AppDestination.kt) —— 纯枚举（`CONNECTIONS` / `DATABASE` / `SETTINGS`），无平台 / 引擎依赖。`labelKey` 是**文案键**而非文案本身（导航条要跟着界面语言走）。新增目标只需加一个枚举项，由调用方（desktopApp 的 `MainScreen`）按 `entries` 分派。

> ⚠️ `TopNavBar` **当前不存在**：全仓无 `fun TopNavBar` 定义，`navigation/` 下只有 `AppDestination.kt`。
> 曾有的导航条形态已删除，`MainScreen` 直接按 `AppDestination` 分派内容。历史残留章节已从本文移除。

### 5.2 三轴外观模型

| 轴 | 取值 | 决定 | 源码 |
|---|---|---|---|
| `ThemePalette` 配色 | 蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP | 色相体系 | [`ui/ThemePalette.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemePalette.kt) |
| `ThemeMode` 明暗 | `SYSTEM` / `LIGHT` / `DARK` | 亮度 | [`ui/ThemeMode.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemeMode.kt) |
| `compact` 尺度 | `false` / `true` | 控件几何与间距 | [`ui/CompactMode.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/CompactMode.kt) |

**拆成正交轴而不是一个枚举**：混进一个枚举会产生 5×3 = 15 项互相重复的组合，且新增明暗档会翻倍；拆开后设置页是两个独立单选组 —— 用户心里想的本来就是「换个配色」与「要不要跟着系统变暗」这两件不相干的事。每套主题**必须提供浅 / 深两版**（只写一版时配到另一档会渲染成错误底色），由 `SundaysPaletteTest` 遍历断言。

### 5.3 关键设计决策索引

| # | 决策 | 结论 | 位置 |
|---|---|---|---|
| 5.3.1 | **外观状态必须提升到 `SundaysTheme` 之外** | 状态放在任一屏幕内部时，那里的按钮只能改到自己的局部组合，改不到 `MaterialTheme.colorScheme` —— 表现为「图标变了、界面没变」 | [`ui/ThemeMode.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemeMode.kt) 的 `AppearanceState` |
| 5.3.2 | **`LocalDarkMode` 不可省** | 编辑器 / 结果表格的 `themed()` 不在 `MaterialTheme` 体系内，拿不到 `darkTheme` 参数；用 `colorScheme` 的亮度反推明暗等于写下「只要背景别太亮就算浅色」的隐含约定 | `ui/Theme.kt` |
| 5.3.3 | **必须由 `Surface` 兜底背景色** | M3 的 `MaterialTheme` **不注入** `LocalContentColor`（默认 `Color.Black`），少了这层未着色的 `Text` 在暗色下就是黑字贴黑底 | `ui/Theme.kt` |
| 5.3.4 | **按钮圆角必须逐个传 `shape`** | M3 的 `Button` 默认形状由 token 固定为 `CornerFull`（胶囊），**不读 `MaterialTheme.shapes`** —— 这是「调了主题但按钮还是胶囊」的根因 | [`ui/SundaysPalette.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/SundaysPalette.kt) |
| 5.3.5 | **`surfaceTint = Transparent` 即禁用 tonal 叠色** | 分层统一由 `HorizontalDivider(outlineVariant)` 表达；唯一该用阴影的是真正浮在内容之上的弹窗 | `ui/SundaysPalette.kt` |
| 5.3.6 | **浅色档不能用品牌色** | 荧光青压在浅底 1.46:1、品牌粉 2.64:1，都远低于 AA 4.5:1。深色档可以「还原」品牌色，浅色档必须主动牺牲明度 | `ui/SundaysPalette.kt` |
| 5.3.7 | **复古档靠形状 + 3D 斜面成立** | M3 的 `BorderStroke` 只有单色，表达不了「成对异色边」，因此斜面自绘。现代三套的 `winBevel` 是彻底的空操作，渲染路径与引入复古档之前逐像素一致 | [`ui/UiChrome.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/UiChrome.kt)、[`ui/WinControls.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/WinControls.kt) |
| 5.3.8 | **紧凑档缩放 `LocalDensity` 而非逐组件覆写** | 所有 `dp`（含 M3 的最小高度 / 内边距 / 圆角）都经 `Density.toPx()`，缩一次整棵树等比缩小；`sp` 由 `fontScale` 反向补偿，使控件缩 0.85 而字号只缩 0.92 | `ui/CompactMode.kt` |
| 5.3.9 | **明暗入口收敛到设置页一处** | `AppearanceState` 的落盘挂在状态对象上（`toAppSettings()` 是唯一构造点），入口越多越容易出现「某处改了没落盘」。面板标题行只留 ⚙ 设置入口 | [`ui/ThemeModeToggle.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemeModeToggle.kt) |

### 5.4 外观 token 的单一来源

```
ThemePalette ──uiTokens(useDark)──> UiThemeTokens（9 项决策）──LocalUiTokens──> 组件
   唯一需要改的地方                    用枚举而非布尔                      只读，不含主题名判断
```

组件侧只问「当前 token 的这个字段是什么」，主题判断（`when`）只出现在 `UiChrome.kt` 的派生函数里 → **新增主题 = 在 `ThemePalette.uiTokens` 加一个分支**。`UiTokensTest` 逐主题 × 明暗断言经典档把每一项都打开、现代档都关掉；派生函数刻意保持纯函数，这样测试不需要 `runComposeUiTest`（JUnit4 反射会把 `@Composable` 的合成 `composer` 形参当成测试方法参数而拒绝加载整个类）。

### 5.5 设置与引导

| 主题 | 结论 | 位置 |
|---|---|---|
| 设置页结构 | 左分类（枚举 `entries` 遍历，新增分类只需加枚举项）/ 右内容 | [`settings/SettingsScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/settings/SettingsScreen.kt) |
| 设置页**不接触引擎** | 系统信息由调用方经 `onRequestSystemInfo` 注入，与 `onTestConnection` 同源；`SystemInfo` 是扁平数据类，**刻意不用 proto** | `settings/SystemInfo.kt` |
| 自动刷新默认关闭 | `OFF` / `10s` / `5s` / `2s` / `1s`；gRPC 模式下每档都是真实的跨进程往返 | [`ui/SystemInfoRefresh.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/SystemInfoRefresh.kt) |
| 定时器登记在设置页内部 | `LaunchedEffect` 随组合，离开页面 / 切分类即自动停；回调必须用 `rememberUpdatedState` 包住，否则每次重组都重启定时器 | `settings/SettingsScreen.kt` |
| 设置持久化 | `settings.json` 与 `connection.json` **分文件**；原子写（`.tmp` → `ATOMIC_MOVE`），读取永远降级（损坏文件顺手重写为默认值＝自愈） | [`settings/SettingsStorage.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/settings/SettingsStorage.kt) |
| 首启判据 | `resolveOnboardingCompleted(settings, fileExists)` —— 判据是**文件是否存在**而不是那个字段，否则老用户每次升级都会被拦一次 | `ui/ThemeMode.kt` + `onboarding/OnboardingScreen.kt` |

---

## 6. 通用 UI 工具

| 组件 | 一句话 | 关键点（详见 KDoc） | 源码 |
|---|---|---|---|
| `ContextMenuState<T>` | 通用右键菜单状态 | 位置透明、payload 类型由调用方定、`@Stable`；editor / table 各自用类型别名封装 | [`ui/ContextMenuState.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ContextMenuState.kt) |
| `Modifier.onRightClick` | 右键检测 | 只在主指针通道消费；不复用 Foundation 的 `internal onRightClickDown`（跨模块不可见） | [`ui/RightClick.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/RightClick.kt) |
| `DragHandle` | 可拖拽分隔条 | 受控组件（宽度归调用方）；命中区 8dp / 画线 1dp；拖拽与双击复位**共用同一钳位** | [`ui/DragHandle.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/DragHandle.kt) |
| `FocusRing` | 可见焦点环 | 给「自带焦点能力、默认不画焦点指示」的容器补一圈焦点环 | [`ui/FocusRing.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/FocusRing.kt) |
| `LocalStrings` / `LanguagePicker` | 界面语言入口 | 文案表在 `:i18n`，本模块只提供 Composable 侧入口；语言名恒显母语名 | [`ui/LocalStrings.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/LocalStrings.kt)、[`ui/LanguagePicker.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/LanguagePicker.kt) |
| `WinControls` / `WinDivider` / `winShape` / `uiBevel` | 复古控件外观包装层 | 现代档透传 M3；形状解析只在经典档改形状 | [`ui/WinControls.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/WinControls.kt) |

界面语言的完整设计见 [`../i18n/README.md`](../i18n/README.md)。语言状态放在 `AppearanceState` 里 —— 落盘写的是**整份** `AppSettings`，语言若由调用方单独持有，用户改一次配色就会把已选语言抹掉。

---

## 7. 设计原则（跨组件）

这一节是**跨组件**的约定，不属于任何单个组件；各组件的 KDoc 里只留一句指向。

### 7.1 Slot-based 可扩展性

所有扩展点都是 **Composable lambda 插槽**而非 `List<UIElement>` 数据驱动：`CodeEditorWithToolbar.actions`、`CodeEditor.contextMenuItems`、`DataTable.contextMenuItems`、`DataTable.detailPanel`。理由：与 Compose 生态一致（`TopAppBar` / `Scaffold` 都是这种风格）、调用方自由控制视觉与状态、类型安全、无需预先枚举所有可能性。

### 7.2 状态由调用方管理（databind）

组件**不**持有 `text` / `rows` 的内部 state（除了 UI-only 的滚动位置、临时选中态），调用方经 `onTextChange` / 重新赋值触发重绘 —— 与 Compose 的 unidirectional data flow 一致。**唯一例外**：`DataTable` 在调用方未传 `selectedRowId` 时用内部 state 兜底，以保持调用方零样板代码。

### 7.3 合理默认值

| 参数 | 默认值 | 含义 |
|---|---|---|
| `CodeEditor.maxLines` | `null` | 不施加高度上限（填充父容器） |
| `CodeEditor.minLines` | `3` | 最小显示行数 |
| `CodeEditor.showLineNumbers` | `true` | IDE 习惯 |
| `CodeEditorWithToolbar.showLanguageSwitcher` | `true` | 启用语言切换 |
| `DataTable.fillParentHeight` | `true` | 填父容器 |
| `DataTable.pageSize` | `PageSize.DEFAULT`（= `S20`） | 默认 20 行 / 页 |
| `DataTable.detailPanelRatio` | `0.35f` | 主 65% / 详情 35% |
| `CodeLanguage.completionCandidates` | 返回空列表 | 只支持高亮的语言不必实现补全 |
| `SystemInfoRefresh` | `OFF` | 自动刷新必须显式打开 |

### 7.4 与 `:engine` 解耦的具体边界

**禁止**：`com.langbiantianya.engine.*`、protobuf、`com.langbiantianya.dialect.*` —— 任何情况下。
**允许**：`kotlinx-coroutines`（仅 `LaunchedEffect` 用到）、Compose Foundation / Material3、`androidx.lifecycle.viewmodelCompose`。

---

## 8. 测试

`shared/` 的测试按 source set 分两层：`commonTest/` 是平台无关的（tokenizer / 模型 / 集成 / 布局契约），`jvmTest/` 只测文件持久化与 POSIX 权限。

| 测试类 | 守护的契约 |
|---|---|
| `LuaTokenizerTest` / `SqlTokenizerTest` | 关键字 / 字符串 / 注释 / 数字 tokenize |
| `SqlDialectProfileTest` | 方言档位：id 唯一、词表分类互斥、标准档不认方言词、基集全档生效、语言与 formatter 配对注册 |
| `FormatterSpacingTest` | 格式化契约：两侧对称、缩进原样搬运、注释只读、空行折叠、**幂等性** |
| `CompletionTest` | 前缀切分、接受候选替换整个词、排序与上限、SQL/Lua 大小写策略差异、方言词表隔离、**宿主函数不得进 `LuaLanguage`** |
| `CompletionPopupLayoutTest` | 弹层定位：宽度上限两端夹取、贴光标、靠右**翻转**、`>` 与 `>=` 的边界差异 |
| `TableModelsTest` / `CellEditTest` | 列 / 行 / 分页 / 主题模型；单元格编辑只报不执行 |
| `JdbcUrlTest` / `SqlIdentifierQuoteTest` | 字段 ↔ URL 折算与回解析；引号风格与 5 个真实方言逐一比对 |
| `SundaysPaletteTest` / `UiTokensTest` | 文字对比度达 WCAG AA；逐主题 × 明暗断言经典档全开、现代档全关 |
| `ThemeModeTest` / `CompactModeTest` / `DragHandleTest` | 三档循环与互逆；密度缩放数学与字体补偿；拖拽算术必须经 density 换算 |
| `SettingsStorageTest` / `ConnectionStorageTest` | 逐档往返、原子写、损坏降级 + 自愈、v1 → v2 迁移 |
| `ConnectionStoragePermissionsTest` | POSIX 权限位（0600）。**Windows 上是 `assumeTrue` skip 而非断言失败** —— 那里访问控制走 ACL，断言失败等于要求 Windows 做不到的事 |
| `OnboardingStateTest` / `LanguageBootstrapTest` | 首启判据、**外观变更不得抹掉已完成标记**；语言表完整性 |

运行：

```bash
./gradlew :shared:jvmTest
./gradlew :shared:test          # 等价
```

> **一条从测试里总结出的教训**（记在这里，因为它不属于任何一个组件）：
> ① 「平台不支持」是最容易被用来解释失败的借口 —— 断言消息只说「实际是 0」，不看栈底的
> `UnsupportedOperationException` 很容易认定成「Windows 环境问题」，**必须翻到栈底**；
> ② **「一堆不相干的用例同时红」几乎总有同一个根因** —— 当时 11 条失败症状天差南
> （界面找不到预置连接 / JSON 读不出 / 往返缺 key / upsert 后为空），根子却是同一行。
> 逐条去改断言会把根因留在原地。

---

## 9. 已知约束与未来扩展

| 约束 | 影响 |
|---|---|
| `CodeEditor` tokenize 同步执行 | 大文本（> 50K 行）可能短暂卡帧；未来可拆 `LaunchedEffect` 异步化 |
| `CodeEditor` 不支持查找替换 | IDE 习惯功能；工具栏 `actions` 插槽可承载，纯 `commonMain` 增量 |
| 不支持 IME composition 输入 | 中文 / 日文输入法合成期间显示可能异常 |
| `DataTable` 不支持列拖拽 / 多列排序 / 过滤 | 调用方需自己用 `TableColumn.weight` 排列表头、在外层维护排序 / 过滤状态 |
| `Modifier.onRightClick` 仅响应鼠标右键 / 双指点击 | 触摸设备长按弹出菜单需另写 |
| `TopNavBar` 不存在 | 顶层导航由调用方按 `AppDestination` 分派；如需导航条请新增组件而不是找它 |

---

## 10. 跨链接

| 文档 | 内容 |
|---|---|
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | 整体架构（V2.9）、双模式架构（Direct / gRPC）、引擎方言矩阵、迁移历史 |
| [根 `../README.md`](../README.md) §"共享 UI 组件" | 顶层简短介绍 |
| [`README.md`](./README.md) | 用户视角：组件目录、快速上手、构建测试、扩展新语言 |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎设计 —— 解释 `shared/` 与引擎解耦的原因（在 `desktopApp/` 集成层组合） |
| [`../desktopApp/.../main.kt`](../desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt) | 端到端用法：`SundaysTheme` + `AppDestination` + `ConnectionManagerScreen` 的接线 |
| [`../i18n/README.md`](../i18n/README.md) | 界面语言与文案表设计 |

---

## 11. 架构升级历史

| 版本 | 主要变化 |
|---|---|
| v2.x | 新增 `editor/` + `table/` + `ui/`；`CodeEditor` 支持高亮 / 工具栏 / 右键菜单；`DataTable` 支持虚拟滚动 / 分页 / 详情面板 |
| v2.9 | **高度策略统一**：`CodeEditor.maxLines` 默认 `null`、`DataTable.fillParentHeight` 默认 `true`（均填父容器剩余高度）；新增 `ConnectionManagerScreen` + `ConnectionStorage` |
| v2.14 | **① 顶层导航与应用主题上移**：新增 `navigation/`（`AppDestination`）与 `ui/Theme.kt`（`SundaysTheme`），均自 `desktopApp` 提取；`ConnectionSession` / `DatabaseBrowserState` 因直连 `IdbEngine` 仍留在 `desktopApp`（见 §1.3）。**② 模块整理**：删除 KMP 脚手架样板与 `composeResources` logo，清理死代码与无调用方的兼容层。**③ 文档纠偏**：KDoc 中与实现不符的描述、失效链接，以及本文中「过度记录实现细节」的章节，按实现改正并把细节迁回源码 KDoc |
