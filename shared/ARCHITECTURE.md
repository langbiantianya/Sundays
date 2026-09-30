# `shared/` — KMP 共享 UI 组件架构设计文档

> **版本**：v2.14（与根 `../ARCHITECTURE.md` 同版本）
>
> **模块定位**：与 `:engine` 解耦的纯 UI 组件库，通过 KMP `commonMain` 单一 source set 承载所有业务组件，`jvm` 平台特定逻辑最小化。

---

## 1. 概述

### 1.1 模块目的

`shared/` 提供面向 **Compose Multiplatform Desktop**（Kotlin 2.4.0 + Compose Foundation 1.x）的可扩展 UI 组件：

- **CodeEditor** — 语法高亮 + 行号 + 工具栏 + 格式化 + 右键菜单
- **DataTable** — 虚拟滚动 + 分页 + 详情面板 + 单元格可选中 + 右键菜单
- **ConnectionManagerScreen** — 连接管理（左侧连接列表 + 右侧 4 步引导页面），支持 MySQL/PostgreSQL/H2/DuckDB/SQLite
- **AppDestination** — 顶层导航目标枚举（v2.14 自 `desktopApp` 上移；同批上移的 `TopNavBar` 已无实现，见 §5.2）
- **SundaysTheme / SundaysPalette** — 跟随系统明暗的应用主题（v2.14 自 `desktopApp` 上移）；配色 / 形状 / 字号规范见 §5.4
- **通用 UI 工具** — `ContextMenuState<T>` + `Modifier.onRightClick`

### 1.2 KMP Source Set 布局

```
shared/
├── commonMain/        所有业务 UI 组件（平台无关）：editor/ table/ connection/ navigation/ ui/
├── commonTest/        平台无关测试（tokenizer / 模型 / 集成）
├── jvmMain/           当前为空 —— 无平台特定实现（`commonMain` 全量可用）
└── jvmTest/           JVM 特定测试
```

**当前仅启用 `jvm` 单一目标**（macOS / Linux / Windows Desktop）。KMP 工程结构天然支持后续扩展 `androidMain` / `iosMain` / `wasmJsMain` —— `commonMain` 中的组件零修改复用，只需新增 source set 提供平台特定的 `pointerInput` / `Okio` 适配。

### 1.3 为什么与 `:engine` 解耦

`shared/` **没有任何对 `:engine` 或 gRPC/protobuf 的依赖**：

| 设计动机 | 解释 |
|---|---|
| **可独立测试** | 组件可在无引擎进程 / 无数据库连接的情况下运行单元测试（如 `LuaTokenizerTest` 30 项纯文本解析） |
| **可独立复用** | `CodeEditor` / `DataTable` 是通用 UI —— 任何 Compose Desktop 应用都能用，无需带 `:engine` 这颗大依赖 |
| **避免循环依赖** | `:engine` 不需要任何 UI；`:desktopApp` 同时依赖 `:engine` 与 `:shared`，但 `:shared` 不应反过来知道 `:engine` 存在 |
| **强制依赖方向** | 单向：`desktopApp → {engine, shared}`；`engine ↮ shared` |

**这条边界的物理约束**：`:engine` 是纯 JVM 模块（`engine/build.gradle.kts` 用 `kotlin("jvm")`，依赖
HikariCP / JDBC 驱动 / gRPC / Hadoop-Parquet），而 `shared` 的业务代码全部落在 KMP `commonMain`。
因此**任何引用 `IdbEngine` 或 proto 类型的代码都无法进入 `commonMain`** —— 状态机与引擎接线必须留在
`desktopApp`。这条判据决定了「什么算可上移的共享代码」：

| 代码 | 是否引用 `:engine` | 归属 |
|---|---|---|
| `AppDestination` / `SundaysTheme` / `SundaysPalette` | 否（纯 Compose） | ✅ `shared/commonMain`（v2.14 上移） |
| `ConnectionManagerScreen`（回调注入） | 否（引擎调用由 `onTestConnection` 等回调注入） | ✅ `shared/commonMain` |
| `ConnectionSession` | 是（`IdbEngine.testConnection` / `disconnect`） | ❌ 留在 `desktopApp` |
| `DatabaseBrowserState` / `DatabaseBrowserScreen` | 是（`IdbEngine.invoke` + proto 构造器） | ❌ 留在 `desktopApp` |

---

## 2. CodeEditor 设计

### 2.1 核心能力

| 能力 | 实现机制 |
|---|---|
| **语法高亮** | `SyntaxHighlighter` + `CodeLanguageRegistry`（SPI 模式）；SQL 支持**方言档位**（关键字随连接的库变，见 §2.8） |
| **行号 gutter** | `LineNumberGutter` Composable；宽度按行数位数自适应（最少 2 位） |
| **工具栏** | `CodeEditorWithToolbar` 包裹 `EditorToolbar`（语言切换 + 格式化 + actions 插槽） |
| **格式化** | `CodeFormatterRegistry` 自动启用；工具栏"格式化"按钮按语言可用性启用 / 禁用 |
| **右键菜单** | `Modifier.onRightClick` + `EditorContextMenuState` + `contextMenuItems` 插槽 |
| **语言切换下拉框** | `AssistChip` 触发；可隐藏（`showLanguageSwitcher = false`） |
| **滚动同步** | 行号 gutter 与 `BasicTextField` 共享同一个 `ScrollState` |
| **内部状态** | `CodeEditorState`（文本 + 光标/选区 + `ScrollState`）；默认 `rememberCodeEditorState` 随组合同生命周期，调用方也可持有并在重新进入组合时传回（见 2.5） |

### 2.2 高度策略（v2.9 统一）

```kotlin
@Composable
fun CodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    languageId: String?,
    modifier: Modifier = Modifier,
    theme: CodeEditorTheme = CodeEditorTheme.default(),
    showLineNumbers: Boolean = true,
    minLines: Int = 3,
    maxLines: Int? = null,           // ★ 默认 null —— 不施加高度上限
    editorState: CodeEditorState = rememberCodeEditorState(text),  // ★ 内部状态可由调用方持有
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
)
```

| `maxLines` | 行为 |
|---|---|
| `null`（**默认**） | 不施加高度上限 —— 编辑框**填充父容器剩余高度**（`Modifier.fillMaxHeight()`），但不会超过父容器；超出可滚动 |
| 传入整数（如 `15`） | 显式上下限（`Modifier.heightIn(min = minHeight, max = maxHeightDp)`）；高度夹在 `minLines` × `fontSize` 与 `maxLines` × `fontSize` 之间 |

```kotlin
// 默认行为 —— 填充父容器剩余高度（不超父容器）
CodeEditor(text = sql, onTextChange = { sql = it }, languageId = "sql")

// 显式高度上限
CodeEditor(text = sql, onTextChange = { sql = it }, languageId = "sql",
           minLines = 5, maxLines = 15)   // 超过则内部滚动
```

**设计动机**：v2.9 前调用方必须显式设置 `maxLines`，否则编辑器只占用最小行数（视觉突兀）。统一为 `maxLines = null → fillMaxHeight()` 后，**默认行为即自适应父容器**，调用方无需关心高度；只有需要硬上限时才显式传入。

> **[!] 陷阱 —— `verticalScroll` 的子项无法 `fillMaxHeight`**
>
> `Modifier.verticalScroll` 会用**无界**高度测量其内容，因此**滚动容器内部**的子项调用
> `fillMaxHeight()` 拿到的是 `Infinity`，会退化为 wrap content。若把 `fillMaxHeight()` 直接挂在
> `BasicTextField` 上，输入区就只有**一行高**（实测 19dp）—— 编辑框下方大片区域点不到，
> 表现为「点编辑器没反应、无法输入」。
>
> 正确做法（当前实现）：外层 `BoxWithConstraints` 持尺寸（`fillMaxHeight()` / `heightIn`），
> **在滚动之前**读取 `constraints.maxHeight`，再以 `heightIn(min = 框高)` 把 `BasicTextField`
> 撑满整框 —— 点击任意位置都能聚焦，内容溢出时仍由 `verticalScroll` 滚动。
>
> ```kotlin
> BoxWithConstraints(modifier = …then(sizeModifier)) {
>     val boxHeightDp =
>         if (constraints.hasBoundedHeight) with(LocalDensity.current) { constraints.maxHeight.toDp() }
>         else null                       // 父容器高度无界 → 退回 wrap content（旧行为）
>     Row(Modifier.fillMaxSize().verticalScroll(sharedScrollState)) {
>         LineNumberGutter(...)
>         BasicTextField(
>             modifier = Modifier.weight(1f)
>                 .then(if (boxHeightDp != null) Modifier.heightIn(min = boxHeightDp) else Modifier),
>             …
>         )
>     }
> }
> ```
>
> 注意**变量遮蔽**：`BoxWithConstraintsScope` 有 `maxHeight: Dp`，若函数内存在同名局部变量
> （如 `val maxHeight = maxLines?.let { … }`），lambda 内会优先解析到局部变量。因此该局部
> 已改名为 `maxHeightDp`，取框高时显式用 `constraints.maxHeight`。

### 2.3 工具栏扩展模式

```kotlin
@Composable
fun CodeEditorWithToolbar(
    // ...
    actions: @Composable RowScope.() -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    // ...
)
```

**Slot-based**（`@Composable RowScope.() -> Unit`）而非 **data-driven**（`List<EditorAction>`）：

- 与 Compose 生态一致（`TopAppBar` 等都是这种风格）
- 调用方自由控制按钮视觉 / 状态（`enabled` / `colors` / `icon`）
- 无需预先枚举所有可能的操作

**位置约定**：内置按钮（语言切换 + 格式化）在前，自定义 `actions` 在后 —— 避免破坏现有调用方的视觉惯例。

**`onLanguageChange` 可选**：当 `showLanguageSwitcher = false` 时回调不会被调用，因此默认为空 lambda 让调用方按需重写。

### 2.4 右键菜单扩展模式

```kotlin
data class EditorContextMenuPayload(
    val text: String,           // 当前编辑器的全部文本
    val languageId: String?,    // 当前语言 ID（null = 纯文本模式）
)

typealias EditorContextMenuState = ContextMenuState<EditorContextMenuPayload>

@Composable
fun rememberEditorContextMenuState(): EditorContextMenuState =
    remember { ContextMenuState<EditorContextMenuPayload>() }
```

```kotlin
CodeEditor(
    text = sql,
    onTextChange = { sql = it },
    languageId = "sql",
    contextMenuItems = { payload ->
        payload?.let { p ->
            DropdownMenuItem(text = { Text("复制") }, onClick = { copy(p.text) })
            DropdownMenuItem(text = { Text("清空") }, onClick = { onTextChange("") })
        }
    },
)
```

**Payload 设计**：payload 暴露当前编辑器快照（text + languageId），菜单项 lambda 可基于语言决定是否禁用某项（如"格式化"菜单项仅在 `languageId` 不为 null 时显示）。

### 2.5 内部状态归属（`CodeEditorState`）

`CodeEditor` 内部有三样状态：**文本值 + 光标/选区**（`TextFieldValue`）、**滚动位置**（行号与代码共享的 `ScrollState`）。
它们打包在 `CodeEditorState` 里，默认由 `rememberCodeEditorState(text)` 创建 —— **生命周期与组合绑定**：
组件一旦离开组合（典型场景：右栏在「表预览 ↔ 工作台」之间切换，编辑器被摘出组合），
再回来时文本虽然会从 `text` 参数恢复，但**光标回到文首、滚动回到顶部**。

需要跨这类切换保持一致时，**由调用方的状态机持有 `CodeEditorState` 并作为 `editorState` 传入**：

```kotlin
// 状态机（存活于组合之外，例如 desktopApp 的 DatabaseBrowserState）
val sqlEditor = CodeEditorState()
val sql: String get() = sqlEditor.text          // 文本视图
fun setSql(v: String) = sqlEditor.setText(v)    // 整段替换（光标/选区保持，越界夹紧）

// UI
CodeEditorWithToolbar(text = sql, onTextChange = { setSql(it) }, languageId = "sql", editorState = sqlEditor)
```

| 成员 | 用途 |
|---|---|
| `value: TextFieldValue` | 文本 + 光标 / 选区（用户输入经 `onValueChange` 写入） |
| `scrollState: ScrollState` | 行号 gutter 与代码区共享的滚动位置 |
| `text` / `setText(v)` | 文本视图 / 整段替换（文本未变时空操作；**保留光标与选区**，越界夹到新长度内） |

> **文本真相源仍是 `text` 参数** —— `CodeEditor` 内的 `LaunchedEffect(text)` 会把外部文本同步进
> `CodeEditorState`（格式化、状态机赋值都走这条路），因此 `editorState` 不构成第二真相源。
>
> **为什么 `setText` 保留光标**：受控输入下用户每敲一个键都会回调 `onTextChange` → `setText`。
> 若这里把光标弹到文末，用户就无法在文本中间编辑（每次输入都被顶到末尾）。文本未变时直接空操作，
> 也不动滚动位置 —— 滚动只应由用户手势或显式调用改变。

### 2.6 行号 Gutter 同步滚动

```kotlin
val sharedScrollState = rememberScrollState()

BoxWithConstraints(modifier = modifier.then(sizeModifier)) {   // ← 尺寸由外层持（见 §2.2 陷阱）
    val boxHeightDp = if (constraints.hasBoundedHeight) { … } else null
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(sharedScrollState)  // ← 滚动状态由 gutter 与输入区共享
            .onRightClick { offset -> contextMenuState.show(offset, ...) },
    ) {
        if (showLineNumbers) {
            LineNumberGutter(lineCount = lineCount, theme = theme)  // ← 同一 verticalScroll 容器内
        }
        BasicTextField(                                             // ← 编辑器
            modifier = Modifier.weight(1f).then(… heightIn(min = boxHeightDp) …),
            …
        )
    }
}
```

**实现要点**：
- `LineNumberGutter` 不单独 `verticalScroll`，而是依赖父容器的 `sharedScrollState`
- 当 `BasicTextField` 因内容溢出产生滚动时，整个 `Row`（含 gutter）一起移动
- gutter 宽度按行数位数自适应（`maxOf(2, lineCount.toString().length)`），保证行号始终右对齐不裁切

### 2.7 Tokenize + 高亮（UI 线程同步）

```kotlin
val transformation = remember(language, highlighter, fieldValue.text) {
    CodeVisualTransformation(language, highlighter)
}

BasicTextField(
    value = fieldValue,
    onValueChange = { ... },
    visualTransformation = transformation,  // ← 每次 text 变化重新计算
    // ...
)
```

- `rememberCodeHighlighter(theme)` 复用 `SyntaxHighlighter` 实例，避免每次 recompose 创建
- `transformation` 仅在 `language` / `highlighter` / `text` 变化时重新构造
- 大文本 tokenize 在 UI 线程同步执行 —— 对 SQL/Lua 长度（典型 < 10K 行）足够快；未来可拆 `LaunchedEffect` 异步化

### 2.8 SQL 方言档位 —— 关键字随库变

`SqlLanguage` 的基集是「SQL:2016 + 5 方言共有子集」；连上某个库后，编辑器在基集之上追加该方言
**特有**的关键字 / 类型 / 内置函数（`PRAGMA` 只在 SQLite 亮、`STRAIGHT_JOIN` 只在 MySQL 亮……）。

```kotlin
data class SqlDialectProfile(
    val languageId: String,     // = CodeLanguage.id（注册表主键），也是 formatter 的 languageId
    val displayName: String,    // 下拉框 / 工作台标题条显示的名字（"MySQL" / "PostgreSQL" / …）
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
) { companion object { val STANDARD, MYSQL, POSTGRESQL, H2, DUCKDB, SQLITE; val ALL } }

class SqlLanguage(profile: SqlDialectProfile = SqlDialectProfile.STANDARD) : CodeLanguage {
    override val id = profile.languageId
    override val displayName = profile.displayName
    // 基集 ∪ 方言词表在**构造期合并一次** —— tokenize 每次按键都会跑，不能在里面做集合运算
    private val keywords = BASE_KEYWORDS + profile.keywords
    …
}
```

**档位即一种语言**：换方言 = 换 `languageId`，因此 `CodeEditor` / `CodeEditorWithToolbar` 零改动，
高亮与「格式化」自动走同一档位（formatter 也按 `languageId` 索引 —— 若另起一套映射，方言档位下
「格式化」按钮会因找不到 formatter 而静默禁用）。`SqlFormatter(languageId)` 的默认参数即
`STANDARD.languageId`，`SqlFormatter.register()` 一次注册全部档位（语言 + formatter 配对注册）。

**不变量**（`SqlDialectProfileTest` 强制）：
- 档位 id 唯一，且「档位声明的每个词」都 tokenize 成声明的类型 —— 该断言同时挡住两类错误：
  同一个词被重复归类（KEYWORD → TYPE → BUILTIN 有优先级，重复归类会静默改色），
  以及档位词与基集冲突；
- 标准档位不认识任何方言词（否则档位形同虚设）；
- 基集词表在所有档位继续生效（档位只追加、不覆盖）；
- 每个档位都同时注册了语言与 formatter。

**映射在 desktopApp**：「连接方言 `DialectType` → 档位」的 `when` 穷举写在 `DatabaseBrowserScreen.kt`
（不写 `else`，新增方言时编译期报错）。编辑器模块因此**不依赖** `connection` 包，保持可复用的纯 UI 组件。

> **注册时机**：`registerBuiltinEditors()` 一次注册 SQL 家族全部档位 + Lua（`main()` 调用，幂等）。
> 未注册时 `CodeLanguageRegistry.get(languageId)` 返回 null —— 编辑器静默退化为无高亮纯文本。
> 反过来，**测试里直接渲染编辑器而不跑 `main()` 时必须自己调一次**（见 `DatabaseBrowserUiTest`
> 的 `sql workbench follows the connected dialect`）。

---

## 3. DataTable 设计

### 3.1 核心能力

| 能力 | 实现机制 |
|---|---|
| **大量数据** | 基于 `LazyColumn` 虚拟滚动（`item key = TableRow.id`） |
| **可配置表头** | `TableColumn(key, header, width, alignment, formatter, weight)` |
| **分页** | `PageSize` 枚举 `S10/S20/S50/S100/S200/S300/S500/ALL`（`ALL` 一次性渲染全部行，依赖 LazyColumn） |
| **databind** | `rows: List<TableRow>` 由调用方管理 state 传入 |
| **单行详情面板** | 点击行 → 右侧详情面板（默认 `DefaultDetailPanel`；可自定义 `detailPanel` 插槽） |
| **单元格可选中** | 每行包裹 `SelectionContainer`，可在单元格内拖拽选中 |
| **右键菜单** | `@Composable (TableRow?) -> Unit` 插槽 |
| **数据库主键承载** | `TableRow.id: Any` 承载主键（Long / String / UUID 等） |

### 3.2 高度策略（v2.9 统一）

```kotlin
@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    theme: DataTableTheme = DataTableTheme.default(),
    fillParentHeight: Boolean = true,           // ★ 默认 true —— 填父容器
    pageSize: PageSize = PageSize.DEFAULT,
    // ...
)
```

| `fillParentHeight` | 行为 |
|---|---|
| `true`（**默认**） | `Modifier.fillMaxSize()` —— **填满父容器剩余空间，不会超出父容器** |
| `false` | 仅 `modifier`，按内容自适应高度（外部父容器需自己处理滚动 / 尺寸） |

```kotlin
DataTable(
    columns = listOf(TableColumn("id", "ID"), TableColumn("name", "姓名")),
    rows = rows,
    pageSize = PageSize.S50,
    // fillParentHeight 默认为 true —— 填满父容器高度
)
```

**与 CodeEditor 高度策略保持一致**：`maxLines = null` ⇔ `fillParentHeight = true`，两者默认都不施加高度上限，而是填充父容器剩余空间。

### 3.3 详情面板插槽

```kotlin
DataTable(
    // ...
    showDetailPanel: Boolean = true,
    detailPanelRatio: Float = 0.35f,            // 主表格 65% / 详情 35%
    detailPanel: @Composable (TableRow?, DataTableTheme) -> Unit = { row, t ->
        DefaultDetailPanel(row, columns, t)
    },
)
```

- 不传 `detailPanel` → 使用 `DefaultDetailPanel`（自动用列的 `formatter` 渲染 key-value 列表）
- 自定义 `detailPanel` → 完全控制详情 UI（如 JSON 树、关系图、图表）
- `detailPanelRatio` 必须 ∈ `[0, 1]`，由 `require()` 校验

### 3.4 右键菜单插槽

```kotlin
val menuState = rememberContextMenuState()  // ContextMenuState<TableRow>

DataTable(
    rows = rows,
    contextMenuState = menuState,
    contextMenuItems = { row ->
        DropdownMenuItem(text = { Text("复制") }, onClick = { copyRow(row) })
        DropdownMenuItem(text = { Text("删除 ${row?.id}") }, onClick = { delete(row?.id) })
    },
)
```

**目标行访问**：通过 `contextMenuItems` lambda 的 `row: TableRow?` 参数；也可读 `menuState.payload`（唯一来源，无兼容别名）。

### 3.5 行模型 (`TableRow`)

```kotlin
data class TableRow(
    val id: Any,                    // 主键（任意类型：Long / String / UUID ...）
    val cells: Map<String, Any?>,   // key → value；与 TableColumn.key 对应
) {
    constructor(id: Any, vararg pairs: Pair<String, Any?>) : this(id, pairs.toMap())
    fun formatted(column: TableColumn): String = column.formatter(cells[column.key])
}
```

**便捷构造器**（vararg pairs）：

```kotlin
TableRow(id = 1L,
    "id" to 1L,
    "name" to "Alice",
    "age" to 30,
)
```

**主键承载**：`TableRow.id` 是数据库行的唯一标识 —— 选中状态识别、`DetailPanel` 标题、删除操作的目标均依赖此字段。

### 3.6 分页边界处理

```kotlin
val pageRows: List<TableRow> = if (pageSize.isAll) {
    rows
} else {
    val start = (currentPage - 1).coerceAtLeast(0) * pageSize.value
    if (start >= rows.size) emptyList() else rows.drop(start).take(pageSize.value)
}
val totalPages: Int = if (pageSize.isAll) 1
else maxOf(1, (totalCount + pageSize.value - 1) / pageSize.value)

LaunchedEffect(totalCount, pageSize, totalPages) {
    if (currentPage > totalPages) onPageChange(totalPages)   // 越界自动回退
}
```

`LaunchedEffect` 在 `totalCount` / `pageSize` 变化时检查当前页是否越界，若越界则回退到 `totalPages`，避免渲染空页。

---

## 4. ConnectionManagerScreen 设计

### 4.1 核心能力

| 能力 | 说明 |
|---|---|
| **左侧连接列表** | LazyColumn 展示所有保存的连接，带方言图标、状态色点（未连接 / 连接中 / 已连接 / 失败）、高亮选中、编辑/删除菜单 |
| **右侧引导页面** | 普通 4 步 / 快速 3 步向导：基础信息 → 连接类型 → 连接详情 → 测试并保存（保存或连接）；`IDLE` 且有选中连接时改为**连接总览面板** |
| **连接总览** | 选中连接的状态 + 连接信息 + 「连接」/「断开」/「编辑」/「删除」操作（`onConnect` / `onDisconnect` 回调注入） |
| **方言支持** | MySQL / PostgreSQL / H2 / DuckDB / SQLite |
| **连接类型** | CLIENT_SERVER / EMBEDDED / IN_MEMORY / FILE_BASED（由 `DialectType.supportedConnectionTypes` 按方言过滤） |
| **JDBC URL 折算（真相源）** | `JdbcUrl.kt` 的 `buildJdbcUrl(config, extraQuery)` / `parseJdbcUrl(url, dialect)` 覆盖全部 5 个方言；`CLIENT_SERVER` 显示 5 个字段 + URL 文本框双向同步，嵌入式方言用单一「目标」字段折算 URL；显式参数 (`?useSSL=false&...`) 始终保留，MySQL 无显式参数时补方言默认参数。**userinfo（用户名 / 密码）按 RFC 3986 percent-encode**（`encodeUserInfo` / `decodeUserInfo`），解析侧用 `lastIndexOf('@')` / `lastIndexOf('/')` —— 否则密码含 `@` `/` `:` 时会截断 host 或库名，而每次启动都要从 URL 重建字段，等于每次重启静默损坏连接 |
| **URL 缺失不可放行** | 字段不足以折算 URL 时「下一步」/「保存」/「连接」/「测试连接」全部禁用 —— 保证交给引擎的配置一定有合法 URL |
| **持久化** | 保存到 `~/.config/sundays/connection.json`（JSON + kotlinx.serialization，仅落 `jdbcUrl` + 凭据，按 `version` 分派 v1/v2 并自动迁移）。**文件内含明文口令**，因此目录 / 文件权限收紧到 `rwx------` / `rw-------`（非 POSIX 文件系统不支持该属性时按平台默认继续，那里 ACL 才是访问控制手段） |
| **快速连接不持久化** | `QUICK_CONNECT` 流程最后一步「连接」（`Bolt` 图标）调用 `onQuickConnectDirect`，**不写入** `ConnectionStorage` |
| **测试连接** | `onTestConnection: (suspend (ConnectionConfig) -> TestResult)?` 回调注入；`TEST_SAVE` 步骤的「测试连接」按钮调用它（回调为 null 或 URL 非法时按钮禁用）。组件本身**不依赖引擎** —— 由调用方在集成层（desktopApp）直连 `IdbEngine` |
| **步骤指示器** | 顶部进度条显示当前步骤 |

### 4.2 布局

```
┌─────────────────┬─────────────────────────────────────┐
│  连接列表        │  空闲状态 / 引导步骤页面              │
│  ┌───────────┐  │  ┌─────────────────────────────┐    │
│  │ MySQL     │  │  │  [⚡ 快速连接]  [＋ 新建]   │    │
│  │ 测试环境  │  │  ├─────────────────────────────┤    │
│  ├───────────┤  │  │                             │    │
│  │ PostgreSQL│  │  │  快速连接卡片:              │    │
│  │ 生产环境  │  │  │  ┌─────────────────────┐   │    │
│  └───────────┘  │  │  │ MySQL      :3306  →│   │    │
│                 │  │  ├─────────────────────┤   │    │
│  [+ 新建]       │  │  │ PostgreSQL  :5432  →│   │    │
│                 │  │  ├─────────────────────┤   │    │
│                 │  │  │ H2            →    │   │    │
│                 │  │  ├─────────────────────┤   │    │
│                 │  │  │ DuckDB        →    │   │    │
│                 │  │  ├─────────────────────┤   │    │
│                 │  │  │ SQLite        →    │   │    │
│                 │  │  └─────────────────────┘   │    │
│                 │  └─────────────────────────────┘    │
└─────────────────┴─────────────────────────────────────┘
```

### 4.3 数据模型

```kotlin
@Serializable
data class ConnectionConfig(
    val id: String,                   // UUID
    val name: String,                 // 连接名称
    val dialect: DialectType,         // MYSQL / POSTGRESQL / H2 / DUCKDB / SQLITE
    val host: String = "",            // 主机地址 (CLIENT_SERVER)
    val port: Int? = null,            // 端口 (CLIENT_SERVER)
    val database: String = "",        // 库名 / 嵌入式库名 / 文件路径（引擎语义：EMBEDDED 系 URL 主体）
    val username: String = "",        // 用户名
    val password: String = "",        // 密码
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    val jdbcUrl: String = "",         // 完整 JDBC URL (如 jdbc:mysql://user:pass@host:3306/db?params) —— 真相源
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

`jdbcUrl` 是**唯一真相源**：引擎侧 `PoolManager` 收到非空 `jdbc_url` 时直接用它建池、方言由 URL scheme 反查，
因此 UI 的所有字段编辑都必须折算到 URL 上（`JdbcUrl.kt`）：

- **字段 → URL**：`buildJdbcUrl(config, extraQuery = "")` 按方言产出 URL
  - `CLIENT_SERVER`：`scheme://[user[:pass]@]host[:port][/db][?params]`；无显式参数时补 MySQL 方言默认参数（`useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`），显式参数（`extraQuery`）完全替代默认值
  - `H2`：`jdbc:h2:mem:<db>;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE` / `jdbc:h2:file:<path>`
  - `DuckDB`：`jdbc:duckdb:<path>`（空 = 内存库）；`SQLite`：`jdbc:sqlite:<path>`（空 = `:memory:`）
  - 字段不足（CLIENT_SERVER 缺 host、H2 缺库名）→ **空串**，向导据此禁用放行按钮
- **URL → 字段**：`parseJdbcUrl(url, dialect)` 解析 `host` / `port` / `database` / `username` / `password` /
  `connectionType`（由 URL 形状反推，不识别时为 `UNKNOWN` → 加载端回退方言默认类型）
- **与引擎的一致性**：URL 形状镜像 `engine` 侧方言的 `DatabaseDialect.buildJdbcUrl`；新增方言或改动 URL 规则时**两处同步**（`JdbcUrl.kt` 顶部有对照表）
- **单调状态**：向导各步骤不再持有本地字段副本（名称 / 连接类型 / 凭据均经 `onUpdateEditingConnection` 直写调用方状态），因此不存在「字段改了但配置没变」的丢失路径；URL 文本框与字段互为投影，不需要循环防护 flag

#### `DialectType.engineDriverName` —— 跨模块的名字契约

```kotlin
enum class DialectType {
    MYSQL, POSTGRESQL, H2, DUCKDB, SQLITE, UNKNOWN;

    /** 引擎 `DatabaseDialect.driverName` —— proto `ConnectionConfig.driver` 必须填这个 */
    val engineDriverName: String
        get() = when (this) {
            MYSQL -> "Mysql"; POSTGRESQL -> "Postgresql"; H2 -> "H2"
            DUCKDB -> "Duckdb"; SQLITE -> "Sqlite"
            UNKNOWN -> name   // 无可映射名 —— 原样交给引擎报可读错误
        }
}
```

**为什么需要它**：`PoolManager` 建池时优先按 `jdbcUrl` scheme 反查方言，但 `SchemaHandler` /
`TableHandler` / `ExportEngine` 等**直接按 `config.driver` 取方言实例**（`DialectLoader.getDialect`），
而引擎的注册键是 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite` —— 与本枚举**常量名大小写不同**，
`Enum.name`（`MYSQL`）会让引擎抛 `No dialect plugin loaded for driver: MYSQL`。
只有 `H2` 两侧同名，所以这个坑在只测 H2 时不会被发现。

**契约校验**：`desktopApp` 的 `DialectNameContractTest` 把每个 `engineDriverName` 与引擎
`SYSTEM.LIST_DRIVERS` 的实际注册名逐一比对，任一侧改名即失败。

> `shared` 不依赖 `:engine`，这个映射是**纯字符串**，因此不会引入依赖；但它镜像了引擎的
> `driverName`，属于「两处同步」的约定之一（同 `JdbcUrl.kt` 与引擎 `buildJdbcUrl` 的关系）。

### 4.4 持久化

```kotlin
// 加载（按文件内 version 分派：2 = 精简格式；1 = 历史完整格式 → 折算 URL 后回写迁移）
val connectionList = ConnectionStorage.load()

// 添加/更新
val updated = ConnectionStorage.upsert(config)

// 删除
val updated = ConnectionStorage.delete(id)
```

**保存路径**: `~/.config/sundays/connection.json`

落盘结构 `PersistedConnectionList`（v2）只含 `id` / `name` / `dialect` / `jdbcUrl` / `username` / `password` + 时间戳；
`host` / `port` / `database` / `connectionType` 在加载时由 `parseJdbcUrl` 重建。

### 4.5 使用示例

```kotlin
var connectionList by remember { mutableStateOf(ConnectionStorage.load()) }
var selectedConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
var editingConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
var wizardStep by remember { mutableStateOf(WizardStep.IDLE) }
var statuses by remember { mutableStateOf<Map<String, ConnectionStatus>>(emptyMap()) }

ConnectionManagerScreen(
    connections = connectionList.connections,
    selectedConnection = selectedConnection,
    editingConnection = editingConnection,
    wizardStep = wizardStep,
    connectionStatuses = statuses,                 // 引擎侧会话状态（列表色点 + 总览）
    onSelectConnection = { selectedConnection = it },
    onNewConnection = {
        // withDialect 立即套用方言默认值 + 折算 URL（新配置进入凭据步骤前即带合法 URL）
        editingConnection = ConnectionConfig(id = UUID.randomUUID().toString(), name = "新连接")
            .withDialect(DialectType.MYSQL)
        wizardStep = WizardStep.BASIC_INFO
    },
    onEditConnection = { conn ->
        editingConnection = conn
        wizardStep = WizardStep.BASIC_INFO
    },
    onSaveConnection = { config ->
        connectionList = ConnectionStorage.upsert(config)
        editingConnection = null
        wizardStep = WizardStep.IDLE
    },
    onQuickConnectDirect = { config ->
        // 快速连接不落盘：选中 + 由集成层直接连库
        selectedConnection = config
    },
    onDeleteConnection = { id -> connectionList = ConnectionStorage.delete(id) },
    onCancelEdit = {
        editingConnection = null
        wizardStep = WizardStep.IDLE
    },
    onWizardNext = { wizardStep = it },
    onWizardBack = { wizardStep = it },
    onConnect = { config -> /* 集成层：IdbEngine.testConnection(config) */ },
    onDisconnect = { config -> /* 集成层：IdbEngine.disconnect(config) */ },
    onTestConnection = { config ->
        // 集成层直连引擎（shared 不依赖 engine）：只传 URL + 凭据
        val resp = engine.testConnection(config.jdbcUrl, config.username, config.password)
        TestResult(success = resp.ok, message = resp.error)
    },
)
```

### 4.6 引导步骤枚举

**两种独立流程** — `WizardFlow` 标识当前流程，步骤指示器自适应:

| 流程 | WizardFlow | 步骤序列 | 总步骤 |
|---|---|---|---|
| 普通新建 | `NORMAL` | `BASIC_INFO` → `CONNECTION_TYPE` → `CREDENTIALS` → `TEST_SAVE` | 4 |
| 快速连接 | `QUICK_CONNECT` | `QUICK_CONNECT` → `CREDENTIALS` → `TEST_SAVE` | 3 |
| 编辑已有 | `NORMAL` | `BASIC_INFO` → `CONNECTION_TYPE` → `CREDENTIALS` → `TEST_SAVE` | 4 |

| WizardStep | 内容 |
|---|---|
| `IDLE` | 空闲状态：无选中连接时显示引导面板；有选中连接时显示**连接总览**（状态 + 连接/断开/编辑/删除） |
| `QUICK_CONNECT` | 快速连接：选方言（仅快速连接流程） |
| `BASIC_INFO` | 普通流程：连接名称 + 数据库方言选择（编辑直写 `editingConnection`，切换方言经 `withDialect` 重置并折算 URL） |
| `CONNECTION_TYPE` | 普通流程：连接类型（选项来自 `DialectType.supportedConnectionTypes`；切换经 `withConnectionType` 重算 URL 形状） |
| `CREDENTIALS` | `CLIENT_SERVER`：主机/端口/数据库名/用户名/密码 + JDBC URL 文本框（互为投影）；嵌入式系：单一目标字段（库名或文件路径）+ 只读折算 URL。缺字段时「下一步」禁用 |
| `TEST_SAVE` | 连接摘要（含 JDBC URL 行）+ 测试按钮 + 「保存」（NORMAL）或「连接」（QUICK_CONNECT）；URL 非法时两者均禁用 |

调用方负责维护 `wizardFlow` 并在切换入口（新建 / 快速连接 / 编辑）时同步设置 —— 三个入口回调本身就是「同时设置 flow」的地方：

```kotlin
// 普通新建 → NORMAL 流程, 起始 BASIC_INFO（withDialect 套用默认值 + 折算 URL）
onNewConnection = { wizard = WizardState(draft().withDialect(MYSQL), BASIC_INFO, NORMAL) }
// 快速连接 → QUICK_CONNECT 流程, 起始 QUICK_CONNECT
onQuickConnect = { wizard = WizardState(draft().withDialect(MYSQL), QUICK_CONNECT, QUICK_CONNECT) }
// 编辑已有 → NORMAL 流程, 起始 BASIC_INFO（保留原配置）
onEditConnection = { conn -> wizard = WizardState(conn, BASIC_INFO, NORMAL) }
```

> 参考实现：`desktopApp/.../ConnectionSession.kt` 的 `newConnection()` / `quickConnect()` / `edit(config)`
> 就是这三条语句（外加连接列表与引擎会话状态的管理）。

---

## 5. 顶层导航与应用主题

### 5.1 `AppDestination` —— 导航目标

```kotlin
enum class AppDestination(val label: String) {
    CONNECTIONS("连接管理"),
    DATABASE("数据库浏览"),
}
```

纯枚举，无平台 / 引擎依赖。`label` 供导航条渲染文案；**新增目标只需加一个枚举项** ——
`MainScreen`（desktopApp）按 `entries` 分派目标屏幕，无需改枚举以外的代码。

### 5.2 `TopNavBar` —— 顶层导航条（⚠️ 已无实现，本节为历史残留）

```kotlin
@Composable
fun TopNavBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
)
```

> **[!] 文档漂移**：本节描述的 `TopNavBar` 在当前代码中**不存在** ——
> `navigation/` 下只有 `AppDestination.kt`，全仓 `.kt` 中没有 `fun TopNavBar` 定义，也无任何调用方。
> 同样地，`main.kt` 的 KDoc（`TopNavBar` / `NavChip`）与 `DatabaseBrowserSheetsTest` 的注释
> 仍在引用它。`MainScreen` 当前直接按 `AppDestination` 分派内容，不渲染导航条
> （`DatabaseBrowserSheetsTest` 断言首屏无「数据库浏览」chip，正对应这一现状）。
> 保留本节只为记录曾经的组件形态；**新增功能不要依赖它**。清理属另一件事，未在本次主题改造中处理。

### 5.3 `SundaysTheme` —— 应用主题

```kotlin
@Composable
fun SundaysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SundaysPalette.colorSchemeFor(darkTheme),
        shapes = SundaysPalette.Shapes,
        typography = SundaysPalette.Typography,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
```

`isSystemInDarkTheme()` 本身即 `commonMain` API（`androidx.compose.foundation`），各平台入口
（desktop `Window` / 未来的 Android / iOS）只需创建平台容器并套上本主题，
明暗策略无需在每个平台重复。

**必须由 `Surface` 兜底背景色**：Material3 的 `MaterialTheme` 只注入 colorScheme / shapes /
typography，**不注入** `LocalContentColor`（其默认值是 `Color.Black`）。少了这层 `Surface`，
未显式设色的 `Text` 在暗色下就是黑字贴黑底 —— 见 `desktopApp` 的 `ThemeContentColorTest`。

### 5.4 `SundaysPalette` —— 视觉规范（蓝灰专业 IDE 风格）

Material3 出厂默认是**触屏卡片**语言（大圆角、大字、色相叠色），搬到信息密集的桌面数据库工具
里并不合适 —— 胶囊按钮在标签条里噪音过重、12dp 圆角让控件显得松垮、tonal 叠色在灰底上糊出一层
色偏。`SundaysPalette` 把它换成 DBeaver / DataGrip 一类的桌面工具观感：
**低饱和靛蓝主色 + 4~8dp 圆角 + 靠分割线而非阴影分层 + 字号整体下调 1sp**。

| 项 | 取值 | 为什么 |
|---|---|---|
| 主色（浅 / 深） | `#2F5C9E` / `#5B8DEF` | 低饱和靛蓝，替代出厂紫；深色档提亮以满足对比度 |
| `background` / `surface`（浅） | `#F4F5F7` / `#FFFFFF` | 面板与底色差 1 档，靠色阶而非阴影分层 |
| `background` / `surface`（深） | `#1B1F27` / `#232833` | 蓝灰炭色，与 `CodeEditorTheme.Dark` / `DataTableTheme.Dark` 同源 |
| `surfaceTint` | `Color.Transparent` | **禁用 tonal 叠色**（见下） |
| `Shapes` | 2 / 4 / 6 / 8 / 8 dp | 按钮 4dp、chip 与输入框自动跟随、弹窗 8dp |
| `Typography` | `bodyMedium` 13sp / `bodySmall` 12sp / `labelSmall` 11sp | 桌面密度；保住表格分页栏行高 |

**三条关键约束**（改主题前必读）：

1. **按钮圆角必须逐个传 `shape = SundaysPalette.buttonShape`**。Material3 的 `Button` 默认形状
   取自 `ButtonDefaults.shape`，它由 token 固定为 `CornerFull`（**胶囊**），
   **不读 `MaterialTheme.shapes`**（`ButtonSmallTokens.ContainerShapeRound`）。
   改 `Shapes` 对按钮完全无效 —— 这是「调了主题但按钮还是胶囊」的根因。
   chip / `OutlinedTextField` / `Tab` 则走 `Shapes.fromToken(...)`，自动跟随。
2. **`surfaceTint = Color.Transparent` 即禁用 tonalElevation**。`Surface(tonalElevation = …)`
   仅在底色**等于** `surface` 时叠加 `surfaceTint`（`ColorScheme.applyTonalElevation`）；
   tint 透明后，即使某处仍写了 `tonalElevation` 也无视觉变化。分层因此统一由
   `HorizontalDivider(color = outlineVariant)` 表达。
3. **唯一该用阴影的是弹窗**（`DatabaseBrowserScreen` 的 sheet 重命名弹窗用 `shadowElevation`），
   因为它确实浮在内容之上；其余通栏工具栏一律不投影。

**对比度**：所有「文字 / 背景」组合达 WCAG AA（≥4.5:1），由
`SundaysPaletteTest` 逐对钉住（含 `primary` / `tertiary` / `error` 当文字色用在
`surface` **与** `background` 两处的双重断言 —— 工具栏条压在 `background` 上，只测 `surface` 会漏）。
`outline` / `outlineVariant` 是 1~2dp 边框与分割线角色，**不纳入**文字对比度断言：
要求它们也达 4.5:1 会让所有层级线变成刺眼粗线。

### 5.5 `ThemeMode` / `ThemeModeToggleButton` —— 日夜切换

三档循环：`SYSTEM`（跟随系统）→ `LIGHT` → `DARK` → `SYSTEM`。
两处入口：连接管理左栏标题行、数据库浏览的**最外层右上角**（与左侧「＋添加连接」同一行、
同高 —— 不占用内容区高度，也不随工作台切换 / sheet 内容变化而移动）。
浏览屏**空态**另有一个独立副本：此时没有标签条也没有工具栏，不单独放一个按钮就会彻底消失，
而空态恰恰是用户第一次打开应用最可能停留的地方。

> `AddConnectionDialog` **不提供**切换入口：它是模态弹窗，主题是应用级设置，
> 在弹窗里改全局外观会让人失去「当前处于什么主题」的判断。实现上靠
> `ConnectionManagerScreen(themeMode: ThemeMode? = null)` —— 弹窗不传该参数，按钮即不渲染。

**状态必须提升到 `SundaysTheme` 之外**。这是本功能唯一的技术要点：

```
main()  ── rememberThemeModeState()  ← 状态诞生
   │
   ├── SundaysTheme(darkTheme = themeMode.isDark())   ← 消费：配色注入最外层
   │
   └── MainScreen(engine, themeMode)
         ├── ConnectionManagerScreen(themeMode, onCycleTheme)  → 按钮回写
         └── DatabaseBrowserScreen(themeMode, onCycleTheme)    → 按钮回写
```

`SundaysTheme` 接收的是 `darkTheme: Boolean` **参数**而非读取状态。若把状态放在任一屏幕内部，
那里的按钮只能改到自己的局部组合，改不到 `MaterialTheme.colorScheme`，
表现为「按钮图标变了、整个界面没变」。因此单一真相源在 `main.kt`，各屏只拿到
`onCycleTheme` 回调**写回同一实例**。

| 设计点 | 说明 |
|---|---|
| 三档而非两档开关 | 两档开关一旦点下去就再也回不到「跟随系统」。系统改深色模式 / 笔记本合盖是真实场景，用户需要能主动「交还控制权」 |
| 图标 = `mode.next` | 显示「点下去会变成什么」而非「当前是什么」——用户在亮色界面看到月亮才会预期「点了变暗」。三档图标两两可区分：☀ `LightMode`（将变浅色）/ 🌙 `NightsStay`（将变深色）/ 🔆 `BrightnessAuto`（将交还系统） |
| 无障碍 | `IconButton` 自身无 `contentDescription` 参数（它只是容器），语义由内层 `Icon` 提供；`IconButton` 合并子节点语义，读屏念出「主题：深色，切换为跟随系统，按钮」 |
| `AddConnectionDialog` 不含入口 | 弹窗内嵌的是同一个 `ConnectionManagerScreen`，故参数是 **`themeMode: ThemeMode?`（默认 `null`）**，按钮仅在非 null 时渲染。模态弹窗里改全局主题会让人失去「当前处于什么主题」的判断。⚠️ 若把默认值写成 `ThemeMode.SYSTEM` 而按钮仍无条件渲染，弹窗里就会出现一个**点了没反应的死按钮** —— 编译与测试全绿，只有真打开弹窗才看得见，故由 `ThemeToggleVisibilityTest` 钉住 |
| 显式档位忽略系统值 | `LIGHT` / `DARK` 的 `resolveDark` 恒返回固定值 —— 否则「点了变深色但系统是浅色」永远切不过去 |

`ThemeModeTest` 钉住循环顺序、`next`/`previous` 互逆、显式档位不受系统值影响、
以及三击回到原点（否则「跟随系统」档会点丢）。

### 5.6 双轴外观模型：`ThemePalette` × `ThemeMode`

配色由「色相 + 明暗」共同决定，因此拆成两个**正交**的轴：

| 轴 | 取值 | 决定 |
|---|---|---|
| [`ThemePalette`] 配色主题 | `BLUE_GRAY` / `CYBERPUNK` | 色相体系 |
| [`ThemeMode`] 明暗 | `SYSTEM` / `LIGHT` / `DARK` | 亮度 |

2 主题 × 3 明暗 = **6 种外观**，且每套主题自带浅 / 深两版配色。

**为什么不用一个枚举列出全部组合**：那样是 4~6 项，每项都要重复描述一遍「明暗」，
且新增明暗档会翻倍。拆开之后设置页是两个独立单选组 —— 用户心里想的本来就是
「换个配色」与「要不要跟着系统变暗」这两件不相干的事。

**约束：每套主题必须提供浅 / 深两版**。只写一版（比如只做赛博朋克深色）时，
配到另一个明暗档就会渲染成错误底色。`SundaysPaletteTest` 遍历
`ThemePalette.entries × {light, dark}` 断言可读性与底色方向，**新增主题自动进入断言范围**。

赛博朋克深色用近黑紫底（`#0B0118`）+ 霓虹青 / 品红 / 荧光绿；浅色版**不能**直接复用
霓虹色 —— 荧光青 `#00E5FF` 在白底上对比度只有 1.46:1（实测，远低于 AA 4.5:1），
故浅色版整体降饱和压深。

### 5.7 `SettingsScreen` —— 设置页（左分类 / 右内容）

第三个 `AppDestination.SETTINGS`，与「连接管理」「数据库浏览」平级。
入口是两个面板标题行上的 `SettingsEntryButton`（⚙）—— 日夜按钮与设置按钮并列，
两者都是**应用级**操作而非当前连接的操作，故都放在内容区之外的标题行。

| 设计点 | 说明 |
|---|---|
| 分类用枚举 | `SettingsCategory.entries` 遍历渲染，新增分类只需加枚举项，不用改设置页 |
| 不接触引擎 | 系统信息**不由本文件请求**：`shared` 无 `:engine` 依赖（§1.3）。调用方实现 `onRequestSystemInfo` 去调引擎，把结果作为 `SystemInfoState` 传入 —— 与 `ConnectionManagerScreen` 把引擎调用收进 `onTestConnection` 同源。引擎调用是 `suspend` 且可能失败（gRPC 更跨进程），注入后本组件可对 loading / 成功 / 失败三态写纯 UI 测试 |
| `SystemInfo` 不直接用 proto | `:shared` 不依赖 protobuf；desktopApp 负责把 `SystemInfoResponse` 映射成扁平数据类。内存四值扁平化，渲染时不需要二级 `MemoryInfo` 包装 |
| 按 sealed 子类型分派 | `when (state)` 而非「取字段再判空」—— `sealed interface` 的属性无法 smart-cast，`state.loading` 写不出来 |
| 弹窗与设置页互斥 | `AddConnectionDialog` 不提供设置入口（模态弹窗里改全局设置会让人失去判断） |
| 左上角返回 | `onBack` 回调由 `MainScreen` 接到 `closeSettings()` —— 它记住**进入设置页前所在的屏**，返回时回到原处而非固定回首屏；若来处是浏览屏且 sheet 已被关空，则回连接管理首屏 |

### 5.8 系统信息自动刷新

间隔档位 `SystemInfoRefresh`：`OFF` / `10s` / `5s` / `2s` / `1s`，默认 **`OFF`**。

**默认关闭是刻意的**：自动刷新会周期性发 `SYSTEM.INFO`，在 **gRPC 模式下是真实的跨进程
往返**。默认开启会让远程引擎在用户根本没打开这一页时也持续收到请求。

**定时器登记在 `SettingsScreen` 内部**，理由有两条：

1. `LaunchedEffect` 的生命周期随组合 —— 放在这里自动满足「离开设置页即停止轮询」
   与「切到别的分类即停止轮询」，调用方不需要写任何清理逻辑。若把定时器放在
   `MainScreen` 的长生命周期 `scope` 上，就得额外实现「什么时候该取消」。
2. key 取 `(category, systemInfoRefresh)` —— 改间隔会立即以新周期重启定时器。

⚠️ 回调必须用 `rememberUpdatedState` 包住：调用方传的 lambda 每次重组都是新实例，
直接把它放进 `LaunchedEffect` 的 key 会让定时器**每次重组都被重启**，永远等不到下一次触发。

### 5.8.1 `SystemInfo` 数据源（引擎既有的 `SYSTEM.INFO`）

**`SystemInfo` 数据源是引擎既有的 `SYSTEM.INFO`**：`SystemHandler.info()` 已实现
（JVM 版本 / 供应商 / OS / 处理器数 / 堆四值 / 运行时长 / PID），`RequestDispatcher`
已注册 `Category.SYSTEM to Action.INFO` 路由 —— **本次未新增任何 proto 消息或路由**。
`RequestDispatcher` 对该路由的实现是 `invoke = { c, _ -> SystemHandler.info() }`，
忽略传入的 connection；而 `EngineClient.invoke` 的签名要求非空 `ConnectionConfig`，
故 `fetchSystemInfo` 传一个 driver/jdbcUrl 皆空的占位配置。这条捷径由
`SystemInfoFetchTest`（真引擎）验证「空配置不会被当成真实连接去解析方言」。

### 5.9 `SettingsStorage` —— 设置持久化

`~/.config/sundays/settings.json`，与 `connection.json` **分文件**：后者含明文口令
（0600），设置不含敏感信息但需独立演进；设置文件损坏时不应连带丢失用户的连接列表。

| 决策 | 理由 |
|---|---|
| 原子写（`.tmp` → `ATOMIC_MOVE`） | 直接覆盖时若进程在写一半被杀，会留下截断 JSON，下次启动设置整体读不出来。移动失败则退化为 `REPLACE_EXISTING` |
| 读取永远降级 | 文件缺失 / 解析失败 / 枚举值非法，一律回落默认值，绝不因配置损坏让应用起不来。损坏文件会被**顺手重写为默认值**（自愈），否则用户每次启动都走降级分支 |
| 磁盘记录与 `AppSettings` 解耦 | 枚举将来加档位时，未知 `themeMode` 字符串由 `toThemeMode()` 降级为 `SYSTEM`，而不会因反序列化失败把整个文件作废 |
| 落盘挂在 `ThemeModeState` 内部 | 切换入口有三处（两个面板 + 设置页），散在三个文件里。任何一处漏保存，用户就遇到「这次改了、下次启动变回去」——这类 bug 极难复现。收在状态对象里只有一个写入点，新增入口自动继承 |

`SettingsStorageTest` 覆盖往返（含逐档）、路径与版本字段、损坏文件降级 + 自愈、
未知枚举值降级、未知字段忽略、目录自动创建、临时文件不残留。

---

## 6. 通用 UI 工具

### 6.1 `ContextMenuState<T : Any>` —— 通用右键菜单状态

```kotlin
@Stable
class ContextMenuState<T : Any> {
    var position: Offset by mutableStateOf(Offset.Zero); private set
    var visible: Boolean by mutableStateOf(false); private set
    var payload: T? by mutableStateOf(null); private set

    fun show(atPosition: Offset, payload: T?) { ... }
    fun dismiss() { visible = false; payload = null }
}

@Composable
fun rememberContextMenuState(): ContextMenuState<Unit> = remember { ContextMenuState() }
```

**设计要点**：
- **通用组件**：不耦合特定 UI（既可用于表格，也可用于代码编辑器、列表等）
- **位置透明**：菜单位置由调用方在右键事件中传入
- **类型参数化**：`<T>` 让 payload 类型由调用方决定（`TableRow` / `EditorContextMenuPayload` / `Unit` 等）
- **`@Stable` 注解**：告知 Compose 编译器此对象在重组时可跳过相等性检查，提升性能

**类型别名复用**：
```kotlin
// 编辑器
typealias EditorContextMenuState = ContextMenuState<EditorContextMenuPayload>
@Composable fun rememberEditorContextMenuState(): EditorContextMenuState = ...

// 表格
typealias ContextMenuState = ContextMenuState<TableRow>   // 表格包内别名
@Composable fun rememberContextMenuState(): ContextMenuState = ...
```

### 6.2 `Modifier.onRightClick` —— 右键检测

```kotlin
fun Modifier.onRightClick(
    onRightClick: (Offset) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.buttons.isSecondaryPressed &&
            event.changes.fastAll { it.changedToDown() }
        ) {
            event.changes.forEach { it.consume() }
            onRightClick(event.changes[0].position)
            waitForUpOrCancellation()?.consume()
        }
    }
}
```

**实现原理**：

| 步骤 | 说明 |
|---|---|
| `awaitPointerEvent(PointerEventPass.Main)` | 仅在主指针通道消费事件，避免与父级其他手势冲突 |
| `event.buttons.isSecondaryPressed` | 判断是否按下右键（鼠标右键 / 双指点击） |
| `event.changes.fastAll { it.changedToDown() }` | 判断是否为 down 事件（避免误捕获拖拽中的持续右键） |
| `event.changes.forEach { it.consume() }` | 消费事件，防止冒泡到父级（避免与外层滚动冲突） |
| `waitForUpOrCancellation()` | 等到释放后才回到监听状态 —— 防止单次右键多次触发 |

**为何不复用 Compose Foundation 的 `internal suspend PointerInputScope.onRightClickDown`**：
该 API 是 `internal` 修饰符，在跨模块的 `shared/` 中不可见。本函数复制其公开 API 调用模式，达到相同效果。

### 6.3 三步使用模式

```kotlin
// 1. 创建状态（Composable 中）
val menuState = rememberContextMenuState()

// 2. 在目标 Composable 上监听右键
Row(modifier = Modifier
    .fillMaxWidth()
    .onRightClick { offset -> menuState.show(offset, payload = currentRow) }
)

// 3. 全局菜单 Popup
if (menuState.visible) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = { menuState.dismiss() },
        offset = DpOffset(menuState.position.x.toDp(), menuState.position.y.toDp()),
    ) {
        contextMenuItems(menuState.payload)
    }
}
```

---

## 7. 设计原则

### 7.1 Slot-based 可扩展性

所有扩展点都采用 **Composable lambda 插槽**（而非 `List<UIElement>` 数据驱动）：

| 组件 | 插槽 | 用途 |
|---|---|---|
| `CodeEditorWithToolbar` | `actions: @Composable RowScope.() -> Unit` | 工具栏自定义按钮 |
| `CodeEditor` / `CodeEditorWithToolbar` | `contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit` | 右键菜单项 |
| `DataTable` | `contextMenuItems: @Composable (TableRow?) -> Unit` | 右键菜单项 |
| `DataTable` | `detailPanel: @Composable (TableRow?, DataTableTheme) -> Unit` | 详情面板 |

**为何 slot API**：
- 与 Compose 生态一致（`TopAppBar` / `Scaffold` 等都是这种风格）
- 调用方自由控制视觉 / 状态，无需预先枚举所有可能性
- 类型安全（lambda 参数类型明确，无需运行时类型转换）

### 7.2 状态由调用方管理（databind 模式）

```kotlin
// 编辑器
var sql by remember { mutableStateOf("") }
CodeEditor(text = sql, onTextChange = { sql = it }, ...)   // ★ 调用方持有 state

// 表格
val rows = remember { ... }
DataTable(rows = rows, ...)                                 // ★ 调用方持有 state
```

**约定**：组件**不**持有 `text` / `rows` 的内部 state（除了 UI-only 的滚动位置、选中行等临时 state）。调用方通过 `onTextChange` / 重新赋值触发重绘 —— 与 Compose 标准的 unidirectional data flow 一致。

**唯一例外**：`DataTable` 内部 `internalSelectedRowId` 在调用方未传入 `selectedRowId` prop 时作为默认内部 state（用 `internalSelectedRowId` 的 if-else 合并）—— 保持调用方零样板代码。

### 7.3 类型别名复用

```kotlin
// editor 包
typealias EditorContextMenuState = ContextMenuState<EditorContextMenuPayload>

// table 包
typealias ContextMenuState = ContextMenuState<TableRow>   // 注意：表格包内的 ContextMenuState 是 ui.ContextMenuState 的别名
```

**避免每处都写泛型**：`rememberEditorContextMenuState()` / `rememberContextMenuState()`（表格版）已自动推断 payload 类型，调用方写起来像普通 state。

### 7.4 调用方零样板（Reasonable Defaults）

| 参数 | 默认值 | 含义 |
|---|---|---|
| `CodeEditor.maxLines` | `null` | 不施加高度上限（v2.9 统一） |
| `CodeEditor.minLines` | `3` | 最小显示行数 |
| `CodeEditor.showLineNumbers` | `true` | IDE 习惯 |
| `CodeEditorWithToolbar.showLanguageSwitcher` | `true` | 启用语言切换 |
| `DataTable.fillParentHeight` | `true` | 填父容器（v2.9 统一） |
| `DataTable.pageSize` | `PageSize.DEFAULT` (= `S20`) | 默认 20 行 / 页 |
| `DataTable.detailPanelRatio` | `0.35f` | 主 65% / 详情 35% |
| `DataTable.showDetailPanel` | `true` | 显示详情面板 |

调用方不传这些参数时组件行为合理；只在显式传入时才改变默认行为。

### 7.5 与 `:engine` 解耦的具体边界

| shared/ 是否能引用 | 是 / 否 |
|---|---|
| `com.kxxnzstdsw.engine.*` | ❌ 任何情况下禁止 |
| `idb_engine.proto` / protobuf | ❌ 禁止 |
| `com.kxxnzstdsw.dialect.*` | ❌ 禁止 |
| `kotlinx-coroutines` | ✅ 允许（仅 `LaunchedEffect` 用到） |
| Compose Foundation / Material3 | ✅ 允许 |
| `androidx.lifecycle.viewmodelCompose` | ✅ 允许（`shared/build.gradle.kts` 已声明） |

**唯一例外**：`shared/build.gradle.kts` 不依赖任何引擎模块 —— 这是物理上的强制保证。

---

## 8. 测试覆盖

`shared/` 共 **105 项测试**，分布如下：

| 测试类 | 路径 | 项数 | 说明 |
|---|---|---|---|
| `LuaTokenizerTest` | `commonTest/.../editor/LuaTokenizerTest.kt` | 30 | Lua 关键字 / 字符串 / 注释 / 数字 tokenize |
| `SqlTokenizerTest` | `commonTest/.../editor/SqlTokenizerTest.kt` | 23 | SQL 关键字 / 字符串 / 注释 tokenize |
| `SqlDialectProfileTest` | `commonTest/.../editor/SqlDialectProfileTest.kt` | 6 | 方言档位：id / 词表分类不变量 / 标准档位不认方言词 / 注册配对（语言 + formatter）/ 格式化随档位 |
| `EditorIntegrationTest` | `commonTest/.../editor/EditorIntegrationTest.kt` | 12 | `CodeEditor` / `CodeEditorWithToolbar` 集成（tokenize + 工具栏 + 格式化） |
| `TableModelsTest` | `commonTest/.../table/TableModelsTest.kt` | 16 | `TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` 模型 + `ContextMenuState` |
| `JdbcUrlTest` | `commonTest/.../connection/JdbcUrlTest.kt` | 12 | 连接字段 ↔ JDBC URL 折算 / 回解析 / 方言与类型切换 |
| `SundaysPaletteTest` | `commonTest/.../ui/SundaysPaletteTest.kt` | 4 | 浅 / 深配色的文字对比度达 WCAG AA（含语义色当文字色用的双重断言）/ 明暗亮度方向 / `surfaceTint` 透明 |
| `ThemeModeTest` | `commonTest/.../ui/ThemeModeTest.kt` | 8 | 三档循环顺序 / `next`↔`previous` 互逆 / 显式档位不受系统值影响 / 三击闭环 |
| `SettingsStorageTest` | `jvmTest/.../settings/SettingsStorageTest.kt` | 8 | 逐档往返 / 路径与版本字段 / 损坏文件降级 + 自愈 / 未知枚举值降级 / 未知字段忽略 / 目录自动创建 / 临时文件不残留 |
| `ConnectionStorageTest` | `jvmTest/.../connection/ConnectionStorageTest.kt` | 4 | 持久化往返重建派生字段 / upsert-delete / v1 → v2 迁移 |
| `ConnectionStoragePermissionsTest` | `jvmTest/.../connection/ConnectionStoragePermissionsTest.kt` | 2 | 凭据文件权限（0600）与目录权限 |
| **合计** | | **125** | **0 失败 / 0 错误** |

运行命令：

```bash
./gradlew :shared:jvmTest
./gradlew :shared:test          # 等价
```

---

## 9. 已知约束与未来扩展

### 9.1 当前约束

| 约束 | 影响 |
|---|---|
| `CodeEditor` tokenize 同步执行 | 大文本（> 50K 行）可能短暂卡帧；未来可拆 `LaunchedEffect` 异步化 |
| `DataTable` 不支持列拖拽 / 列排序 | UI 层面缺失；调用方需自己用 `TableColumn.weight` 重新排列表头 |
| `DataTable` 不支持多列排序 / 过滤 | 调用方需在外层维护 `pageSize` / `currentPage` / `where` / `orderBy` 状态 |
| `CodeEditor` 不支持查找替换 | IDE 习惯功能；可作为未来增量 |
| 不支持 IME composition 输入 | 中文 / 日文输入法合成中文本期间显示可能异常 |
| `Modifier.onRightClick` 仅响应鼠标右键 / 双指点击 | 触摸设备长按弹出菜单需另写 |

### 9.2 未来扩展路径

| 方向 | 说明 |
|---|---|
| 新平台目标（`androidMain` / `iosMain` / `wasmJsMain`） | 现有 `commonMain` 零修改复用；仅需平台特定 `pointerInput` 适配 |
| `CodeEditor` 增加查找替换 | 工具栏 `actions` 插槽可承载；纯 `commonMain` 增量 |
| `CodeEditor` 异步 tokenize | `LaunchedEffect` + `produceState` 包装；不影响 API |
| `DataTable` 列拖拽 | 引入 `reorderable` 库或自实现 `Modifier.draggable` 包裹表头 |
| `DataTable` 多列排序 / 过滤 | 引入 `TableSortSpec` / `TableFilterSpec` 数据模型 + 工具栏插槽 |

---

## 10. 跨链接

| 文档 | 内容 |
|---|---|
| [根 `../ARCHITECTURE.md`](../../ARCHITECTURE.md) | 整体架构（V2.9）、双模式架构（Direct / gRPC）、引擎方言矩阵、迁移历史 |
| [根 `README.md`](../../README.md) §"共享 UI 组件" | 顶层简短介绍 |
| [`README.md`](./README.md) | 用户视角：组件目录、快速上手、构建测试、扩展新语言 |
| [`engine/ARCHITECTURE.md`](../../engine/ARCHITECTURE.md) | 引擎设计 —— 解释 `shared/` 与引擎解耦的原因（v2.9 Direct 模式架构下，二者在 `desktopApp/` 集成层组合） |
| [`sundays`](../../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt) | 演示 `SundaysTheme` + `AppDestination` + `ConnectionManagerScreen` 的端到端用法 |

---

## 11. 架构升级历史

| 版本 | 主要变化 |
|---|---|
| v1.x（KMP 初始） | `Greeting` / `Platform` 样板；尚未承载业务组件 |
| v2.x | 新增 `editor/` + `table/` + `ui/`；`CodeEditor` 支持语法高亮 + 工具栏 + 右键菜单；`DataTable` 支持虚拟滚动 + 分页 + 详情面板 |
| v2.5 | 引入 `protobuf-kotlin-lite`（仅 `engine/` 用）；`shared/` 不受影响 |
| v2.6 | 引入 `RequestDispatcher` envelope options（`traceId` / `dryRun` / `timeoutMs`）；`shared/` 不受影响 |
| v2.9 | **高度策略统一**：`CodeEditor.maxLines` 默认 `null`（填充父容器剩余高度但不超父容器）；`DataTable.fillParentHeight` 默认 `true`（同语义）。两个组件均无需调用方显式指定高度即自适应父容器；只在显式传入参数时才启用硬上限；新增 `ConnectionManagerScreen` 连接管理组件 + `ConnectionStorage` JSON 持久化 |
| v2.14 | **① 顶层导航与应用主题上移**：新增 `navigation/`（`AppDestination` + `TopNavBar`）与 `ui/Theme.kt`（`SundaysTheme`），均自 `desktopApp` 的 `Navigation.kt` / `main.kt` 提取 —— 不引用 `:engine`，可在 `commonMain` 跨平台复用；`desktopApp` 瘦身为「平台窗口 + 引擎状态机接线」。`ConnectionSession` / `DatabaseBrowserState` 因直连 `IdbEngine` 仍留在 `desktopApp`（见 §1.3）<br>**② 模块整理**：删除 KMP 脚手架样板 `App` / `Greeting` / `getPlatform` 及 `composeResources` logo，移除 `compose.components.resources` 依赖（`jvmMain` 随之清空）；删除两个恒真冒烟测试（`assertEquals(3, 1 + 2)`）与无生产调用方的 `PageSize.fromInt` 及其 2 项测试，测试数 101 → 97<br>**死代码清理**：`DataTable.primaryKey`（从未被读取、无调用方、KDoc 描述的行为未实现）、`CodeVisualTransformation.source` 及其恒等三元式、两处无 modifier 单子节点的 `Box` 包裹、预览里从不重新赋值的 `mutableStateOf`、`QuickConnectStep.editingConnection` 未读参数<br>**去兼容层**：删除 `ContextMenuState.targetRow` 别名（唯一真实调用方 `DataTable` 改为直接读 `payload`）<br>**去重**：`DataTable` 表头/数据行的列布局抽为 `RowScope.TableRowCells`；`DialectOption` / `ConnectionTypeOption` 抽为 `SelectableOptionRow`；14 个编辑器预览共用 `EditorPreview` 外壳<br>**性能**：`TableBody` 的 `rows.indexOf(row)`（每可见行 O(n) 扫描）改为 `itemsIndexed` 下标<br>**文档纠偏**：KDoc 中「异步 tokenize」「滚动共享 `ScrollState` 参数」「空行填充」「`[DarkColors]` / `[TokenType]` / `[CodeFormatter]` / `[ScrollState]` / `[WizardState]` / `[JdbcUrl]` / `[DataTable.detailPanel]`」等与实现不符的描述或失效链接，以及 `WizardState` 字段名写错（`wizardStep` → `step`）的示例，全部按实现改正 |