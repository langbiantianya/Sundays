# `shared/` — KMP 共享 UI 组件架构设计文档

> **版本**：v2.9（与根 `../ARCHITECTURE.md` 同版本）
>
> **模块定位**：与 `:engine` 解耦的纯 UI 组件库，通过 KMP `commonMain` 单一 source set 承载所有业务组件，`jvm` 平台特定逻辑最小化。

---

## 1. 概述

### 1.1 模块目的

`shared/` 提供面向 **Compose Multiplatform Desktop**（Kotlin 2.4.0 + Compose Foundation 1.x）的可扩展 UI 组件：

- **CodeEditor** — 语法高亮 + 行号 + 工具栏 + 格式化 + 右键菜单
- **DataTable** — 虚拟滚动 + 分页 + 详情面板 + 单元格可选中 + 右键菜单
- **ConnectionManagerScreen** — 连接管理（左侧连接列表 + 右侧 4 步引导页面），支持 MySQL/PostgreSQL/H2/DuckDB/SQLite
- **通用 UI 工具** — `ContextMenuState<T>` + `Modifier.onRightClick`

### 1.2 KMP Source Set 布局

```
shared/
├── commonMain/        所有业务 UI 组件（平台无关）
├── commonTest/        平台无关测试（tokenizer / 模型 / 集成）
├── jvmMain/           JVM 特定实现（当前最小化：仅 Platform.jvm.kt）
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

---

## 2. CodeEditor 设计

### 2.1 核心能力

| 能力 | 实现机制 |
|---|---|
| **语法高亮** | `SyntaxHighlighter` + `CodeLanguageRegistry`（SPI 模式） |
| **行号 gutter** | `LineNumberGutter` Composable；宽度按行数位数自适应（最少 2 位） |
| **工具栏** | `CodeEditorWithToolbar` 包裹 `EditorToolbar`（语言切换 + 格式化 + actions 插槽） |
| **格式化** | `CodeFormatterRegistry` 自动启用；工具栏"格式化"按钮按语言可用性启用 / 禁用 |
| **右键菜单** | `Modifier.onRightClick` + `EditorContextMenuState` + `contextMenuItems` 插槽 |
| **语言切换下拉框** | `AssistChip` 触发；可隐藏（`showLanguageSwitcher = false`） |
| **滚动同步** | 行号 gutter 与 `BasicTextField` 共享同一个 `ScrollState` |

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
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
)
```

| `maxLines` | 行为 |
|---|---|
| `null`（**默认**） | 不施加高度上限 —— 编辑器**填充父容器剩余高度**（`Modifier.fillMaxHeight()`），但不会超过父容器；超出可滚动 |
| 传入整数（如 `15`） | 显式上下限（`Modifier.heightIn(min = minHeight, max = maxHeight)`）；高度夹在 `minLines` × `fontSize` 与 `maxLines` × `fontSize` 之间 |

```kotlin
// 默认行为 —— 填充父容器剩余高度（不超父容器）
CodeEditor(text = sql, onTextChange = { sql = it }, languageId = "sql")

// 显式高度上限
CodeEditor(text = sql, onTextChange = { sql = it }, languageId = "sql",
           minLines = 5, maxLines = 15)   // 超过则内部滚动
```

**设计动机**：v2.9 前调用方必须显式设置 `maxLines`，否则编辑器只占用最小行数（视觉突兀）。统一为 `maxLines = null → fillMaxHeight()` 后，**默认行为即自适应父容器**，调用方无需关心高度；只有需要硬上限时才显式传入。

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

### 2.5 行号 Gutter 同步滚动

```kotlin
val sharedScrollState = rememberScrollState()

Row(
    modifier = modifier
        .fillMaxWidth()
        // ...
        .verticalScroll(sharedScrollState)  // ← 父容器共享滚动状态
        .onRightClick { offset -> contextMenuState.show(offset, ...) },
) {
    if (showLineNumbers) {
        LineNumberGutter(lineCount = lineCount, theme = theme)  // ← 子组件在同一个 verticalScroll 容器内
    }
    BasicTextField(...)                                          // ← 编辑器
}
```

**实现要点**：
- `LineNumberGutter` 不单独 `verticalScroll`，而是依赖父容器的 `sharedScrollState`
- 当 `BasicTextField` 因内容溢出产生滚动时，整个 `Row`（含 gutter）一起移动
- gutter 宽度按行数位数自适应（`maxOf(2, lineCount.toString().length)`），保证行号始终右对齐不裁切

### 2.6 异步 Tokenize + 高亮

```kotlin
val transformation = remember(language, highlighter, fieldValue.text) {
    CodeVisualTransformation(fieldValue.text, language, highlighter)
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
    primaryKey: String = "id",
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

**目标行访问**：通过 `contextMenuItems` lambda 的 `row: TableRow?` 参数；也可通过 `menuState.payload`（旧字段 `menuState.targetRow` 仍兼容）。

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
| **左侧连接列表** | LazyColumn 展示所有保存的连接，带方言图标、高亮选中、编辑/删除菜单 |
| **右侧引导页面** | 4 步向导：基础信息 → 连接类型 → 连接详情 → 测试并保存 |
| **方言支持** | MySQL / PostgreSQL / H2 / DuckDB / SQLite |
| **连接类型** | CLIENT_SERVER / EMBEDDED / IN_MEMORY / FILE_BASED（按方言自动过滤） |
| **持久化** | 保存到 `~/.config/sundays/connection.json`（JSON + kotlinx.serialization） |
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
    val id: String,                  // UUID
    val name: String,                 // 连接名称
    val dialect: DialectType,        // MYSQL / POSTGRESQL / H2 / DUCKDB / SQLITE
    val host: String = "",           // 主机地址
    val port: Int? = null,           // 端口
    val database: String = "",        // 数据库名
    val username: String = "",        // 用户名
    val password: String = "",        // 密码
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    val filePath: String = "",       // 文件路径 (SQLite / H2 EMBEDDED)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

### 4.4 持久化

```kotlin
// 加载
val connectionList = ConnectionStorage.load()

// 添加/更新
val updated = ConnectionStorage.upsert(config)

// 删除
val updated = ConnectionStorage.delete(id)
```

**保存路径**: `~/.config/sundays/connection.json`

### 4.5 使用示例

```kotlin
var connectionList by remember { mutableStateOf(ConnectionStorage.load()) }
var selectedConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
var editingConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
var wizardStep by remember { mutableStateOf(WizardStep.IDLE) }

ConnectionManagerScreen(
    connections = connectionList.connections,
    selectedConnection = selectedConnection,
    editingConnection = editingConnection,
    wizardStep = wizardStep,
    onSelectConnection = { selectedConnection = it },
    onNewConnection = {
        editingConnection = ConnectionConfig(id = UUID.randomUUID().toString(), name = "新连接")
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
    onDeleteConnection = { id ->
        connectionList = ConnectionStorage.delete(id)
        if (selectedConnection?.id == id) selectedConnection = null
    },
    onCancelEdit = {
        editingConnection = null
        wizardStep = WizardStep.IDLE
    },
    onWizardNext = { wizardStep = it },
    onWizardBack = { wizardStep = it },
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
| `IDLE` | 空闲状态（未编辑） |
| `QUICK_CONNECT` | 快速连接：选方言（仅快速连接流程） |
| `BASIC_INFO` | 普通流程：连接名称 + 数据库方言选择 |
| `CONNECTION_TYPE` | 普通流程：连接类型（CLIENT_SERVER / EMBEDDED / IN_MEMORY / FILE_BASED） |
| `CREDENTIALS` | 主机/端口/用户名/密码 或 文件路径 |
| `TEST_SAVE` | 连接摘要 + 测试按钮 + 保存 |

调用方负责维护 `wizardFlow` 并在切换入口（新建 / 快速连接 / 编辑）时同步设置：

```kotlin
// 普通新建 → NORMAL 流程, 起始 BASIC_INFO
onNewConnection = { wizardState = WizardState(..., wizardStep = BASIC_INFO, flow = NORMAL) }
// 快速连接 → QUICK_CONNECT 流程, 起始 QUICK_CONNECT
onQuickConnect = { wizardState = WizardState(..., wizardStep = QUICK_CONNECT, flow = QUICK_CONNECT) }
// 编辑已有 → NORMAL 流程, 起始 BASIC_INFO
onEditConnection = { conn -> wizardState = WizardState(conn, BASIC_INFO, NORMAL) }
```

---

## 5. 通用 UI 工具

### 5.1 `ContextMenuState<T : Any>` —— 通用右键菜单状态

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

### 5.2 `Modifier.onRightClick` —— 右键检测

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

### 5.3 三步使用模式

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

## 6. 设计原则

### 6.1 Slot-based 可扩展性

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

### 6.2 状态由调用方管理（databind 模式）

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

### 6.3 类型别名复用

```kotlin
// editor 包
typealias EditorContextMenuState = ContextMenuState<EditorContextMenuPayload>

// table 包
typealias ContextMenuState = ContextMenuState<TableRow>   // 注意：表格包内的 ContextMenuState 是 ui.ContextMenuState 的别名
```

**避免每处都写泛型**：`rememberEditorContextMenuState()` / `rememberContextMenuState()`（表格版）已自动推断 payload 类型，调用方写起来像普通 state。

### 6.4 调用方零样板（Reasonable Defaults）

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

### 6.5 与 `:engine` 解耦的具体边界

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

## 7. 测试覆盖

`shared/` 共 **71 项测试**，分布如下：

| 测试类 | 路径 | 项数 | 说明 |
|---|---|---|---|
| `LuaTokenizerTest` | `commonTest/.../editor/LuaTokenizerTest.kt` | 30 | Lua 关键字 / 字符串 / 注释 / 数字 tokenize |
| `SqlTokenizerTest` | `commonTest/.../editor/SqlTokenizerTest.kt` | 9 | SQL 关键字 / 字符串 / 注释 tokenize |
| `EditorIntegrationTest` | `commonTest/.../editor/EditorIntegrationTest.kt` | 12 | `CodeEditor` / `CodeEditorWithToolbar` 集成（tokenize + 工具栏 + 菜单） |
| `TableModelsTest` | `commonTest/.../table/TableModelsTest.kt` | 18 | `TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` 模型 + `ContextMenuState` 行为 |
| `SharedCommonTest` | `commonTest/.../SharedCommonTest.kt` | 1 | KMP 公共冒烟测试 |
| `SharedLogicDesktopTest` | `jvmTest/.../SharedLogicDesktopTest.kt` | 1 | JVM 平台特定冒烟测试 |
| **合计** | | **71** | **0 失败 / 0 错误** |

运行命令：

```bash
./gradlew :shared:jvmTest
./gradlew :shared:test          # 等价
```

---

## 8. 已知约束与未来扩展

### 8.1 当前约束

| 约束 | 影响 |
|---|---|
| `CodeEditor` tokenize 同步执行 | 大文本（> 50K 行）可能短暂卡帧；未来可拆 `LaunchedEffect` 异步化 |
| `DataTable` 不支持列拖拽 / 列排序 | UI 层面缺失；调用方需自己用 `TableColumn.weight` 重新排列表头 |
| `DataTable` 不支持多列排序 / 过滤 | 调用方需在外层维护 `pageSize` / `currentPage` / `where` / `orderBy` 状态 |
| `CodeEditor` 不支持查找替换 | IDE 习惯功能；可作为未来增量 |
| 不支持 IME composition 输入 | 中文 / 日文输入法合成中文本期间显示可能异常 |
| `Modifier.onRightClick` 仅响应鼠标右键 / 双指点击 | 触摸设备长按弹出菜单需另写 |

### 8.2 未来扩展路径

| 方向 | 说明 |
|---|---|
| 新平台目标（`androidMain` / `iosMain` / `wasmJsMain`） | 现有 `commonMain` 零修改复用；仅需平台特定 `pointerInput` 适配 |
| `CodeEditor` 增加查找替换 | 工具栏 `actions` 插槽可承载；纯 `commonMain` 增量 |
| `CodeEditor` 异步 tokenize | `LaunchedEffect` + `produceState` 包装；不影响 API |
| `DataTable` 列拖拽 | 引入 `reorderable` 库或自实现 `Modifier.draggable` 包裹表头 |
| `DataTable` 多列排序 / 过滤 | 引入 `TableSortSpec` / `TableFilterSpec` 数据模型 + 工具栏插槽 |

---

## 9. 跨链接

| 文档 | 内容 |
|---|---|
| [根 `../ARCHITECTURE.md`](../../ARCHITECTURE.md) | 整体架构（V2.9）、双模式架构（Direct / gRPC）、引擎方言矩阵、迁移历史 |
| [根 `README.md`](../../README.md) §"共享 UI 组件" | 顶层简短介绍 |
| [`README.md`](./README.md) | 用户视角：组件目录、快速上手、构建测试、扩展新语言 |
| [`engine/ARCHITECTURE.md`](../../engine/ARCHITECTURE.md) | 引擎设计 —— 解释 `shared/` 与引擎解耦的原因（v2.9 Direct 模式架构下，二者在 `desktopApp/` 集成层组合） |
| [`sundays`](../../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt) | 演示 `CodeEditor` + `DataTable` + `ConnectionManagerScreen` 三个核心组件的端到端用法 |

---

## 10. 架构升级历史

| 版本 | 主要变化 |
|---|---|
| v1.x（KMP 初始） | `Greeting` / `Platform` 样板；尚未承载业务组件 |
| v2.x | 新增 `editor/` + `table/` + `ui/`；`CodeEditor` 支持语法高亮 + 工具栏 + 右键菜单；`DataTable` 支持虚拟滚动 + 分页 + 详情面板 |
| v2.5 | 引入 `protobuf-kotlin-lite`（仅 `engine/` 用）；`shared/` 不受影响 |
| v2.6 | 引入 `RequestDispatcher` envelope options（`traceId` / `dryRun` / `timeoutMs`）；`shared/` 不受影响 |
| v2.9 | **高度策略统一**：`CodeEditor.maxLines` 默认 `null`（填充父容器剩余高度但不超父容器）；`DataTable.fillParentHeight` 默认 `true`（同语义）。两个组件均无需调用方显式指定高度即自适应父容器；只在显式传入参数时才启用硬上限；新增 `ConnectionManagerScreen` 连接管理组件 + `ConnectionStorage` JSON 持久化 |