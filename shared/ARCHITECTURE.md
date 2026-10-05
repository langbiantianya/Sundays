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
- **CompactMode** — 紧凑档（尺度轴）：缩放 `LocalDensity` 把 M3 触屏尺寸收到桌面尺度，见 §5.6
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
| **语法高亮** | `SyntaxHighlighter` + `CodeLanguageRegistry`（SPI 模式）；SQL 支持**方言档位**（关键字随连接的库变，见 §2.9） |
| **行号 gutter** | `LineNumberGutter` Composable；宽度按行数位数自适应（最少 2 位） |
| **工具栏** | `CodeEditorWithToolbar` 包裹 `EditorToolbar`（语言切换 + 格式化 + actions 插槽） |
| **格式化** | `CodeFormatterRegistry` 自动启用；工具栏"格式化"按钮按语言可用性启用 / 禁用；空格 / 缩进 / 注释契约见 §2.8 |
| **补全提示** | `CodeLanguage.completionCandidates`（默认返回空）；`Tab`/`Enter` 接受、`↑↓` 选择、`Esc` 关闭，契约见 §2.10 |
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

### 2.8 格式化契约 —— 空格只看 token 身份

`SqlFormatter` / `LuaFormatter` 走同一条规则，判定逻辑抽在 `TokenSpacing`：

```
两个相邻 token 之间要空格  ⟺  左 token 不贴右  且  右 token 不贴左
```

| 规则 | 效果 |
|---|---|
| `bindsRight`：`(` `[` `{` `.` `::` | `count(*)`、`t.c`、`a::int` |
| `bindsLeft`：闭括号 `,` `;` `.` `::` | `a, b`、`(1)` |
| 开括号 `(` `[` `{` **条件**贴左 | 跟在「可调用」token 后贴（`count(`、`t[`），跟在关键字/操作符后留空格（`IN (`、`= {`） |
| 其余一律两侧留一个空格 | `a > 1`、`a AND b` |

**为什么不用 `pendingSpace` 标志**：那个写法（写 token 之前决定补不补空格）必然踩三个坑 ——
逗号前多空格（`id , name`）、比较符左粘右不粘（`id> 1`），以及 token 说好贴紧、**空白 token 又把标志翻回去**
导致的反复横跳（`a:: int`、`t. col`、`( 1 )`）。最后一个最阴险：它看着像幂等性 bug，
其实只是判断依据被中途改写。改成两个纯函数后，**空格决策与输入原有空白完全无关**，
这同时消灭了「格式化两次才收敛」的一整类诡异现象。

**不做重新缩进**：缩进**原样搬运**（`select\n  a` → `SELECT\n  a`）。重新缩进需要真正的语法分析
（`()` 嵌套层级、`CASE` 块），半吊子重排比不改更糟 —— 用户的视觉分组被改乱却看不出原因。
早期 KDoc 声称「保持输入缩进」而实现实际**丢弃**了它，属文档与实现不符，已改正。

**注释是只读的**（`FormatterSpacingTest` 逐条锁定）：
- 注释内容原样搬运 —— 大小写、内部空格、分隔符一律不动（块注释里常放代码样例 / ASCII 图）
- 只清**行尾空白**：块注释每行 + 整个 token 结尾（行注释 `-- 备注␣␣␣` 是最常见的一类）
- 注释**不会**被主子句换行规则吞掉 —— 把 `-- 注释` 之后的真实 SQL 挪到注释行上，整条语句就废了
- 注释内的关键字不参与大写（`-- select from where` 保持原样）

**空行**：至多保留一个（2 个 `\n`）作为分段，多的压掉 —— 否则段间空行会逐次累积。

**幂等性**：格式化两次 ≡ 格式化一次。测试里有一批样本专门锁这条。

### 2.9 SQL 方言档位 —— 关键字随库变

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

### 2.10 补全（「提示」）—— 关键字 / 函数

输入时在光标下方弹出候选列表，`Tab` / `Enter` 接受、`↑` `↓` 选择、`Esc` 关闭。
`CodeEditor(enableCompletion = false)` 可整体关闭。

**SPI 形态**：`CodeLanguage.completionCandidates(prefix, limit)`，
**带默认实现返回空列表** —— 只支持高亮的语言（纯文本模式、第三方语言）不必碰它，
「新增语言零改动」的扩展承诺不变。词表在语言构造期合并成候选池并排好序，
每次按键只做一次线性过滤 + 截断。

| 决策 | 取值 | 理由 |
|---|---|---|
| 触发前缀长度 | ≥ 2（`MIN_COMPLETION_PREFIX`） | 单字符几乎命中整个语言（`a` → `AND`/`ADD`/`AVG`…），弹层刚开就铺满屏幕反而挡视线 |
| SQL 匹配 | **忽略大小写**，插入大写 | 与 `tokenize` 的 `word.uppercase()`、`SqlFormatter` 的大写三处口径统一 |
| Lua 匹配 | **大小写敏感** | Lua 标识符大小写敏感；把 `Pri` 补成 `print` 等于往用户代码里塞一个语义不同的标识符，是制造 bug 而非帮忙 |
| 排序 | 类别（关键字 → 类型 → 函数）→ 长度 → 字典序 | 先按长度：输入 `CO` 时 `COLUMN` 该赢过 `COLLATE`；全序保证列表在两次按键间不跳位 |
| 词字符 | **ASCII** `a-zA-Z0-9_` | 用 `Char.isLetterOrDigit()` 的话中文返回 `true`，`-- 查询sel` 的前缀会算成「查询sel」，补全在中文注释下直接失灵 |

**接受候选时替换整个词**（含光标**之后**的部分）：光标落在词中间时（`sel|ect`），
只替换前半段会得到 `SELECTect`。`wordBoundsAround` 返回半开区间 `start until end`，
闭区间在「词尾恰好是字符串末尾」时会多出 1，调用方 `last + 1` 取 `substring` 直接越界。

**弹层放在滚动容器内部**，用 `Modifier.atCaret(x, y)` 定位到光标正下方：
自绘布局向外报告 0×0（不撑大父级，否则编辑器内容高度被凭空拉长、滚动条跟着变），
再把内容 `place` 到指定坐标。因为在滚动容器内，弹层天然跟着代码一起滚，
不必手算滚动偏移 ——也就不会出现「代码滚了、弹层没滚」的错位。

**按键用 `onPreviewKeyEvent`**：`Tab` / `Enter` 在 `BasicTextField` 上有默认行为
（移焦 / 换行），只有 preview 阶段能先截住并 `consume`。

> **一个必须钳位的坑**：`onTextLayout` 拿到的 `TextLayoutResult` 可能比当前文本**旧**
> （打字时 selection 先于布局更新）。直接把 `selection.start` 喂给 `getBoundingBox`
> 会因越界抛 `IllegalArgumentException` —— 一次就足以打断整轮 recompose。
> 代码里用 `getCursorRect(selection.start.coerceIn(0, len - 1))` 兜住。
> 这个 bug 是被 `DatabaseBrowserUiTest > generate workbench inserts rows…` 抓到的。

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
| **列多的横向滚动** | 表头与表体**共用同一个 `ScrollState`**（见 §3.1.1） |

#### 3.1.1 表头 / 表体必须共用一个横向 `ScrollState`

列宽策略是 `TableColumn.width` 优先、否则按 `weight` 分配，**两者都不约束总宽度** ——
所以列多时内容必然超出视口。此时若表头与表体各持一份滚动状态（或表体干脆不加横向滚动），
就会出现「表头能滚、表体不能滚」的错位：用户按列名读数会读到**隔壁那一列**。
对数据库工具来说这是读错数据，不是体验问题。

| 决策 | 理由 |
|---|---|
| `hScroll` 由 `DataTable` **创建后下传** | 两处各自 `rememberScrollState()` 就是缺陷的成因。状态的所有权必须与「两组内容必须一致」这件事放在一起 |
| 表体 `LazyColumn` 也加 `horizontalScroll(hScroll)` | 纵向虚拟化与横向滚动正交，可以共存；不加则超出部分被裁且**滚不到** |
| 回归测试读语义的 `HorizontalScrollAxisRange` 而非渲染几何 | 视口外的子节点语义边界会被裁剪钳成 0，量出来的差值是假象（会看起来像「间距变成 0」）。`ScrollAxisRange.value` / `maxValue` 都是 `() -> Float`，**要调用** |

`TableColumnAlignmentTest` 断言：表体存在横向滚动轴（`maxValue > 0`）且滚动后表头与表体
`value` 相等。已做变异验证（把表体改回自己的 `ScrollState`，测试变红）。

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

### 3.2.1 分页发生在哪一侧（`serverSidePaging`）

`DataTable` 有**两种**分页模式，二者不能混用：

| `serverSidePaging` | `rows` 的含义 | 谁切页 | 用在哪 |
|---|---|---|---|
| `false`（**默认**） | **全量**行 | `DataTable` 内部 `rows.drop((page-1)*size).take(size)` | SQL 工作台结果、造数结果 |
| `true` | **数据源返回的当前页** | 调用方（收到 `onPageChange` 后重新取数） | 浏览屏表预览（`DATA.LIST` 引擎侧分页） |

**混用的后果是静默的空表**：服务端模式下 `rows` 只有一页那么多行，`DataTable` 若再按 `currentPage` 偏移一次，第 2 页起 `start >= rows.size` 命中 `emptyList()` —— 界面变成一张零行表，不报错、不转圈，看起来就像「这张表就这么多数据」。

因此服务端模式必须成对传入：

```kotlin
DataTable(
    rows = tab.rows,                 // 引擎返回的当前页
    pageSize = tab.pageSize.toPageSize(),
    currentPage = tab.page,
    onPageChange = { page -> state.goToTabPage(tab, page) },
    onPageSizeChange = { size -> state.changeTabPageSize(tab, size) },
    totalCount = tab.total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    serverSidePaging = true,         // ★
)
```

- `totalCount` 是**整表行数**（不是 `rows.size`），否则页码恒为 `1 / 1`、所有翻页按钮禁用。引擎回的是 `Long`，>21 亿行时 `toInt()` 会绕成负数，须先饱和钳位。
- `PageSize.ALL`（`value = 0`）在数据源侧是**流式读取哨兵**，不是一个合法分页大小。该模式下分页大小下拉**自动排除**它 —— 否则用户会选到一个静默退化成「每次取 1 行」的档位。
- 改分页大小应回到第 1 页（`changeTabPageSize`）：页大小变了以后旧页码多半越界，且 `DataTable` 的「越界自动回退」要等新 `totalCount` 才会触发。

契约由 `TableServerPagingTest` / `LocalPagingUnchangedTest` 钉住（后者专门防止新参数顺手改坏本地分页路径），均已做变异验证。

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
    palette: ThemePalette = ThemePalette.BLUE_GRAY,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalUiTokens provides palette.uiTokens(darkTheme),
        LocalBevelStyle provides palette.bevelStyle(darkTheme),
        LocalCompactMode provides compact,
        LocalDensity provides compactDensity(LocalDensity.current, compact),
    ) {
        MaterialTheme(
            colorScheme = palette.schemeFor(darkTheme),
            shapes = palette.shapes,
            typography = SundaysPalette.Typography,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
```

`isSystemInDarkTheme()` 本身即 `commonMain` API（`androidx.compose.foundation`），各平台入口
（desktop `Window` / 未来的 Android / iOS）只需创建平台容器并套上本主题，
明暗策略无需在每个平台重复。

`compact` 是第三根轴（尺度），与配色 / 明暗正交；它的实现只有 `LocalDensity` 覆盖那
一行，细节见 §5.6。⚠️ `compactDensity(LocalDensity.current, …)` 必须在 provider **之外**求值 ——
写在 provider 的参数位上时 `LocalDensity.current` 尚未生效，读到的仍是平台值。

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
| [`ThemePalette`] 配色主题 | `BLUE_GRAY` / `CYBERPUNK` / `BILI_PINK` / `WIN_2000` / `WIN_XP` | 色相体系 |
| [`ThemeMode`] 明暗 | `SYSTEM` / `LIGHT` / `DARK` | 亮度 |

5 主题 × 3 明暗 = **15 种外观**，且每套主题自带浅 / 深两版配色。

**为什么不用一个枚举列出全部组合**：那样是 10~15 项，每项都要重复描述一遍「明暗」，
且新增明暗档会翻倍。拆开之后设置页是两个独立单选组 —— 用户心里想的本来就是
「换个配色」与「要不要跟着系统变暗」这两件不相干的事。

**约束：每套主题必须提供浅 / 深两版**。只写一版（比如只做赛博朋克深色）时，
配到另一个明暗档就会渲染成错误底色。`SundaysPaletteTest` 遍历
`ThemePalette.entries × {light, dark}` 断言可读性与底色方向，**新增主题自动进入断言范围**。

#### 第三根正交轴：紧凑模式（`compact`）

| 轴 | 取值 | 决定 |
|---|---|---|
| **`compact` 紧凑模式** | `false`（标准）/ `true`（紧凑） | **尺度**：控件几何与间距 |

它与上面两轴正交 —— 5 主题 × 3 明暗 × 2 密度 = **30 种界面外观**。

Material3 出厂尺寸是**触屏语言**（`Button` 最小高 40dp、`OutlinedTextField` 56dp、
`ListItem` 72dp、`Switch` 轨道 52dp、导航栏 80dp）。这些数字在手指上刚好，在鼠标上是浪费 ——
同样的窗口高度，DBeaver / DataGrip 能多列出三四行。§5.4 的 `SundaysPalette` 只能改颜色 / 圆角 / 字号，
改不动这些尺寸：它们是 M3 组件内部写死的 token 常量。

| 决策 | 说明 |
|---|---|
| **缩放 `LocalDensity.density` 而非逐组件覆写** | 所有 `dp`（含 M3 的最小高度 / 内边距 / 间距 / 圆角 / 图标）都经 `Density.toPx()` 换算像素。缩一次 `density`，整棵树等比缩小，**30+ 个调用点一行都不用改**。逐个改则每加一个组件就要再改一次，漏一个就会在界面里留下「没缩小的控件」 |
| **字号单独一档，且比控件温和** | `sp` 的像素值是 `value × density × fontScale`，会**连带**被 density 缩放。靠 `fontScale` 反向补偿，使控件缩 `0.85` 而字号只缩 `0.92`。若不管它，13sp 正文会掉到 11.1sp，在 100% DPI 屏上开始费眼 —— 而「控件变小」不需要以牺牲可读性为代价 |
| **默认 `false`** | M3 出厂尺度是现状，不改变既有用户的观感；紧凑是显式选择 |
| **立即生效** | `AppearanceState` 变更 → `SundaysTheme` 的 `compact` 参数 → `LocalDensity` 覆盖 → 整棵树重组。不需要重启，也不需要重建当前屏幕 |

实现见 `ui/CompactMode.kt`。`CompactModeTest`（纯函数，钉住缩放数学与补偿恒等式）
+ `CompactThemeTest`（组合，钉住 `SundaysTheme` → `LocalDensity` 的接线）
+ `SettingsScreenTest`（钉住开关行为与落盘）。

#### 浅色档不能用品牌色：赛博朋克与哔哩粉踩的是同一个坑

信息密集型界面对比度是硬门槛（正文一律 AA 4.5:1），而**当代品牌色几乎都过不了这一关**。
这两套主题的浅色档都被迫偏离品牌色：

| 主题 | 品牌 / 标志性色 | 压在浅底上的对比度 | 浅色档实际取值 |
|---|---|---|---|
| 赛博朋克 | 荧光青 `#00E5FF` | **1.46:1** | 压深到 `#00697A`（青调保留，明度大幅下调） |
| 哔哩粉 | 品牌粉 `#FB7299` | **2.64:1** | 压深到 `#A81C4C`（玫瑰 → 酒红） |

两个数值都是实测的，且都远低于 4.5:1。结论是：
**深色档可以「还原」品牌色，浅色档必须主动牺牲品牌色的明度**——
哔哩粉的粉色主要活在深色档（`#FF93B6` 压在深色 surface 上 8.16:1），
浅色档只能以酒红示人。第三色刻意用暖琥珀而非第二个粉：整套界面若只有一种色相，
表格里「主色 / 第三色」两类状态标记会难以区分。

#### 复古配色（Win2000 / WinXP）：反例的方向

上面两套是「为可读性牺牲品牌色」，Win2000 / WinXP 恰好相反 —— 它们的招牌色
**本来就是为在 2001 年的 CRT 上读得清而设计的**，因此天然高对比，几乎无需让步：

| 主题 | 招牌色 | 配黑字的对比度 | 是否需为可读性让步 |
|---|---|---|---|
| Win2000 | 银灰控件面 `#C0C0C0` | **11.54:1** | 否，可直接用作 `background` |
| WinXP | Luna 奶油底 `#ECE9D8` | **17.21:1** | 底色不必动，但**选区蓝 `#316AC5` 需要** |

XP 是个典型陷阱：`#316AC5` 压在白色内容区有 5.25:1（看起来没问题），压回 Luna
奶油底却只剩 4.31:1 —— 只测 `surface` 会漏掉工具栏条这类压在 `background` 上的用法。
故改用同色系的 `#255EA1`（5.39:1 / 6.57:1）。

- **Win2000 银灰亮度余量极薄**。`#C0C0C0` 相对亮度 0.527，距「浅色档底色 > 0.5」这条
  硬约束只剩 0.027。该值正是 Win2000 的真实取值不宜改动，因此保留原值、依赖
  `SundaysPaletteTest` 守住 —— 若有人为「提亮一点」微调，测试会立即报错而不是悄悄
  退化成暗底。
- **深浅两版不能沿用同一组色值**。XP 的 `#255EA1` 压在其深色底 `#101820` 上只有
  2.72:1 —— 这正是「深浅两版共用一套色」最常见的翻车方式，深色档必须整体提亮重配。

### 5.6.1 复古控件外观：形状 + 3D 斜面

光换配色与圆角不足以让复古主题成立。经典 Win 控件的辨识度**主要来自双色 3D 斜面** ——
同一控件的左上边用亮色、右下边用暗色，夹一条外圈色，制造凸起 / 凹陷的立体错觉。
Material3 的 `BorderStroke` 只有单色，`Modifier.border` 也只接受一个颜色，**表达不了
「成对异色边」**，所以这套描边只能自绘。

复古两套因此比另外三套多出**两条**主题维度：形状与斜面。

| 维度 | 蓝灰 / 赛博朋克 / 哔哩粉 | Win2000 | WinXP |
|---|---|---|---|
| 形状阶梯 | 共用紧凑圆角 2~8dp | **全直角 0dp** | Luna 递增圆角 2~8dp（按钮档 3dp） |
| 3D 斜面 | **不启用**（`BevelStyle.NONE`） | 2px 双线：外圈同色 + 内圈亮暗成对 | 1px 单线，明暗差收得很小 |
| 按钮填充 | M3 默认 `primary` | **窗口面 `background` + 黑字** | 同左 |
| 输入框 | M3 默认 | 凹陷斜面 + `surfaceVariant` 灰面 | 同左 |

三条设计决定值得单独说明：

1. **现代三套必须是彻底的空操作**。`winBevel` 在 `BevelStyle.NONE` 下直接 `return this`，
   不产生任何绘制或图层；`WinControls.kt` 的四个包装函数也直接转调同名 M3 组件。
   这样现代主题的渲染路径与引入复古主题之前**逐像素一致**。
2. **按钮填充必须从 `primary` 换成窗口面**。M3 实心按钮用 `colorScheme.primary` 作容器色，
   而 Win2000 的 `primary` 是 navy `#000080` —— 那是它的**选区色**。不换的话按钮会变成
   深蓝底白字，和「银灰按钮配黑字」的实物完全对不上。斜面只能补回立体感，补不回色相。
3. **输入框用 `surfaceVariant` 而不是 `surface`**。Win2000 浅色档的 `surface` 是纯白
   `#FFFFFF`，而经典凹陷边有一侧就是纯白 `ButtonHighlight` —— 贴在纯白面上那一侧会
   **完全隐形**。退回「Windows Standard」的 `#D4D0C8` 灰面，亮边才读得出来。
   `WinChromeTest` 的「斜面必须比它所在的两种控件面都更亮 / 更暗」正是钉这一条
   （这个隐形问题是写测试时才被发现的）。

**已实现 / 未实现**：

| | 状态 |
|---|---|
| 逐主题形状阶梯 | ✅ `ThemePalette.shapes`，27 处 `buttonShape` 调用点**零改动** |
| 3D 斜面（按钮凸起 / 输入框凹陷 / 切换态凹陷） | ✅ `Modifier.winBevel(raised = …)` |
| 按钮与输入框的包装层 | ✅ `WinControls.kt`，42 处调用点已迁移 |
| 默认按钮的粗黑边框 | ❌ M3 没有「哪个按钮是回车默认项」的信息 |
| 按下瞬间的凸起↔凹陷互换 | ❌ M3 按下只改容器色，要翻转斜面需自行接管 `interactionSource` |
| 开关的立体斜面 | ❌ M3 把滑块 / 轨道的绘制写死在内部（与单选圆点同一限制），故 `WinSwitch` 只管颜色 |
| 虚线焦点框 / 标题栏 / 任务栏 | ❌ 超出 `MaterialTheme` 能表达的范围 |
| 逐主题字体 | ❌ **有意不做**：MS Sans Serif 8pt 与 Tahoma 8pt 都是位图点阵字体，换成系统默认无衬线后本就没有那个观感，强行缩小只会让信息密集界面更难读
| **界面硬编码圆角** | ✅ 11 处 `RoundedCornerShape(…)` 改走 `winShape(…)` |
| **列表选中态** | ✅ 复古档整行反色（`primary` + `onPrimary`），现代档维持淡色底 |
| **经典档分组线的亮边** | ✅ 取 `BevelStyle.light`。**原先取 `scheme.surface` 是错的**：蚀刻线画在面板面上，而面板面**就是** `surface` —— 浅色档两者同为亮面看着还行，深色档则是 `#1A1A1A` 压在 `#1A1A1A` 上，第二条线**完全隐形**、凹槽退化成一条平线 |
| **面板容器** | ✅ 3 个面板加凸起斜面 |
| **分割线** | ✅ 26 处改用 `WinDivider`（复古档为「暗 1px + 亮 1px」蚀刻线） |
| **标签页条底色** | ✅ 复古档贴窗口面而非白色内容面板 |
| **表格 / 编辑器配色** | ✅ 仅经典档走 token（现代档原样保留，见下） |

### 5.7 `SettingsScreen` —— 设置页（左分类 / 右内容）

第三个 `AppDestination.SETTINGS`，与「连接管理」「数据库浏览」平级。
入口是两个面板标题行上的 `SettingsEntryButton`（⚙）—— 它是**应用级**操作而非当前连接的操作，
故放在内容区之外的标题行。

⚠️ 标题行上原本还有**日夜切换按钮**，现已移除：明暗档位只从设置页「个性化 → 明暗档位」
单选组进入。收敛到单一入口的理由是 `AppearanceState` 的落盘只挂在状态对象上（见 `ThemeMode.kt`），
入口越多越容易出现「某处改了没落盘」。`ThemeToggleVisibilityTest` 以负向断言钉住这条契约。

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
| `compactMode` 存 `Boolean` 而非枚举 | 它没有「未知档位要降级」的语义。旧文件缺该字段时由 kotlinx.serialization 补 `false`（标准密度）即可 —— 新装用户与升级用户都拿到与升级前一致的观感 |

### 5.10 首次启动引导（`OnboardingScreen`）

`shared/commonMain/.../onboarding/OnboardingScreen.kt` —— 真装用户第一次看到的是这一页，
而不是主界面。三组控件：配色主题（5 张色卡）/ 明暗模式（3 档）/ 界面密度（开关）。

| 决策 | 理由 |
|---|---|
| 放在 `SundaysTheme` **内部** | 每次点选立刻重绘整页，**预览是免费的** —— 不需要「预览图 + 应用按钮」那套，本页自己就是预览 |
| 配色用色卡而不是纯文字单选 | 用户看到界面的第一眼就在判断外观；「哔哩粉」「赛博朋克」这类名字不直观，色卡直接把该主题**自己**的 `surface` / `primary` / `onSurface` 摆出来 |
| `dark` 由调用方传入 | 色卡要展示「各主题在**当前明暗**下的取色」。不能就地取 `MaterialTheme.colorScheme` 反推明暗 —— 那是**已选中**主题的配色，恰恰是待比较的其它主题要超越的对象 |
| 收尾条固定在滚动区**外面** | 三组控件在 1024×768 下约 900px 高。按钮原先放在滚动区内，语义 bounds 实测是 `Rect(0,0,0,0)` —— 一屏之内的引导，唯一的出口却完全在视口外，用户可能直接当成死路 |
| **滚动区必须 `fillMaxWidth()`**，且外层另包一层居中 `Box` | 两个轴各由一处负责：水平靠「内层 `fillMaxWidth()` + `horizontalAlignment`」把 960dp 内容块居中，垂直靠「外层 `Box(contentAlignment = Center)`」。漏掉 `fillMaxWidth()` 时 Column 按内容宽度收缩后被父级**左对齐**，`horizontalAlignment` 只在 960dp 内部生效 —— 窗口越宽右侧空得越离谱（实测 1580px 窗口右侧空 530px），紧凑档更明显（内容缩到 816px，1024px 窗口里标题中心偏左 84px）。而 `verticalScroll` 用无上界约束量子节点，垂直居中若交给同一层会恒等于顶对齐 |
| 配色卡用 `selectable` 而非 `clickable(role = RadioButton)` | 后者只设 role、**不设** `selected` 语义，读屏会念「单选按钮」却永远不说是否选中 —— 比不给 role 更糟。`selectable` 同时给出 role 与 selected |
| 不设独立「跳过」按钮 | 默认值本身就是一份合法答案，「开始使用」已兼任跳过；两个按钮只会让「跳过到底跳到哪」变模糊 |
| `firstRun` 参数（默认 `true`） | 「欢迎使用 / 先挑一套顺手的界面」是**首启**话术。从设置页主动重进时传 `false`，标题变「外观引导」、收尾变「完成」—— 否则用户在主界面里点一下又看到「欢迎使用」，会以为应用被重置了 |

**首次启动的判据是 `resolveOnboardingCompleted(settings, fileExists)`** ——
`settings.onboardingCompleted || fileExists`，两个输入任一为真即**不**引导：

- 真正的判据是 `SettingsStorage.exists()`（文件存不存在），**不是**那个字段本身。
  老版本用户升级上来时磁盘上的 JSON 里根本没有 `onboardingCompleted`，
  反序列化补成默认 `false`；只看字段的话**每次升级**都会被引导页拦一次，且用户无法关掉。
- `onboardingCompleted` 放在 `AppearanceState` 里而不是 `main.kt` 的局部 state：
  落盘载荷是**整份** `AppSettings`，载荷里一旦漏掉这个字段，用户完成引导后在设置页换一次配色，
  磁盘上的标记就被重置为 `false`，下次启动又被拦一次。这个 bug 要
  「完成 → 改主题 → 重启」三步才显形，靠读代码几乎发现不了 ——
  故载荷构造被提成 `AppearanceState.toAppSettings()` 这个**唯一**构造点，并由测试钉住。

`SettingsStorageTest` 覆盖往返（含逐档）、路径与版本字段、损坏文件降级 + 自愈、
未知枚举值降级、未知字段忽略、目录自动创建、临时文件不残留、
`compactMode` 两档往返（只存 `true` 的实现会让 `false` 分支永远没跑过）。

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
| `FormatterSpacingTest` | `commonTest/.../editor/FormatterSpacingTest.kt` | 27 | 格式化契约：标点 / 操作符两侧对称、缩进原样搬运、注释只读（不被换行规则吞、不改内容）、空行折叠、幂等性、无行尾空白 |
| `CompletionTest` | `commonTest/.../editor/CompletionTest.kt` | 21 | 补全契约：前缀切分（中文注释不吞词 / 越界光标夹取）、接受候选替换整个词、候选排序与上限、SQL 与 Lua 的大小写策略差异、方言词表隔离 |
| `TableModelsTest` | `commonTest/.../table/TableModelsTest.kt` | 16 | `TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` 模型 + `ContextMenuState` |
| `JdbcUrlTest` | `commonTest/.../connection/JdbcUrlTest.kt` | 12 | 连接字段 ↔ JDBC URL 折算 / 回解析 / 方言与类型切换 |
| `SundaysPaletteTest` | `commonTest/.../ui/SundaysPaletteTest.kt` | 4 | 浅 / 深配色的文字对比度达 WCAG AA（含语义色当文字色用的双重断言）/ 明暗亮度方向 / `surfaceTint` 透明 |
| `UiTokensTest` | `commonTest/.../ui/UiTokensTest.kt` | 8 | 逐主题 × 明暗断言经典档把**每一项**造型决策都打开、现代档都关掉；形状解析在现代档原样透传 / 经典档按主题抹平；反色选中行过 AA 4.5:1；斜面对两种控件面（按钮面 / 输入框面）均有明暗差 |
| `ThemeModeTest` | `commonTest/.../ui/ThemeModeTest.kt` | 13 | 三档循环顺序 / `next`↔`previous` 互逆 / 显式档位不受系统值影响 / 三击闭环 |
| `SettingsStorageTest` | `jvmTest/.../settings/SettingsStorageTest.kt` | 8 | 逐档往返 / 路径与版本字段 / 损坏文件降级 + 自愈 / 未知枚举值降级 / 未知字段忽略 / 目录自动创建 / 临时文件不残留 |
| `OnboardingStateTest` | `commonTest/.../ui/OnboardingStateTest.kt` | 9 | 首次启动判据（无文件=引导 / 已有文件=不打扰老用户 / 已完成=不引导）/ `exists()` 只看文件不解析内容 / 完成引导落盘 / **外观变更不得抹掉已完成标记** / 重复完成幂等 / 默认状态不拦截应用 |
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

#### 界面层：外观 token 单一来源

只换按钮与输入框不够 —— 界面骨架上还有七八项「长什么样」的决策（3D 斜面、按钮填充色、
输入框底色、选中态画法、分割线画法、容器描边、表格斑马纹、禁用文字色）。

**这些决策若散落在各组件里，加新主题就得翻遍全代码**：每套新外观要把所有 `if (isClassic)`
再挖一遍，漏一个判断就有一块控件出戏，而且**不会报任何错**。所以它们被抽成一份 token：

```
ThemePalette ──uiTokens(useDark)──> UiThemeTokens ──CompositionLocal(LocalUiTokens)──> 组件
   （唯一需要改的地方）              （8 项决策）                    （只读，不判断）
```

| token | 取值 | 界面表现 |
|---|---|---|
| `chrome` | `MODERN` / `CLASSIC` | 总档位；其余各项应当与它一致（由测试钉住） |
| `bevel` | `BevelStyle` | 3D 斜面：外圈 / 亮 / 暗 / 线宽 / 单双边 / 圆角 |
| `buttonFace` | `PRIMARY` / `WINDOW` | 实心按钮取 `primary` 还是取窗口面配黑字 |
| `fieldFace` | `SURFACE` / `SURFACE_VARIANT` | 输入框底色（见下方纯白陷阱） |
| `selection` | `TINTED` / `INVERTED` | 淡色容器底 还是 整行反色 |
| `divider` | `FLAT` / `ETCHED` | 实心单线 还是 暗 1px + 亮 1px 蚀刻凹槽 |
| `panelBorder` | `LINE` / `BEVEL` / `NONE` | 容器描边：单色细线 / 3D 斜面 / 无 |
| `zebraRows` | `Boolean` | 表格隔行底色（经典 Win 列表视图**没有**） |
| `disabledInk` | `Color` | 禁用态文字色；`Unspecified` 表示交回 M3 自己算 |

**用枚举而不是布尔**（`isRetro: Boolean`）：布尔会让「再加一种外观」变成给每个判断点加一个
`||`，而枚举是封闭集合 —— 新增一档只需在下面加一行，编译器会把所有 `when` 的漏网处指出来。

**调用点不含任何主题名**。组件侧只问「当前 token 的这个字段是什么」，主题判断（`when`）
只出现在 `UiChrome.kt` 的派生函数里。因此：

- **加新主题 = 在 `ThemePalette.uiTokens` 加一个分支**，其余组件自动生效；
- **现代档天然零风险** —— `UiThemeTokens.MODERN` 把每一项都设成「不做」，`uiBevel` 直接
  `return this`，`WinDivider` 委托给 M3 的 `HorizontalDivider`，渲染路径与引入本机制之前
  逐像素一致。

`UiTokensTest` 是这套契约的护栏：逐主题 × 明暗两档断言经典档把**每一项**都打开、现代档
把**每一项**都关掉。这条是「所有 UI 都要改」的机器可验证形式 —— 将来新增一项 token 而某个
主题忘了配，测试会直接点名。

**纯函数化的理由**：`uiTokens` / `shapeFor` / `selectionColorsFor` 都不依赖组合，因此
测试不需要 `runComposeUiTest`。这不是洁癖 —— JUnit4 反射会把 `@Composable` 的合成
`composer` 参数当成测试方法形参而拒绝加载整个类（`InvalidTestClassError: should have no
parameters`）。把纯逻辑抽出来后，测试与设计同时变简单。

#### 仍在组件侧、但已由 token 派生的取值

| 派生函数 | 供谁用 |
|---|---|
| `winShape(corner)` | 界面所有容器 / 卡片 / 徽标 —— 替掉硬编码的 `RoundedCornerShape(8/12.dp)` |
| `controlShape()` | 按钮 / 输入框 |
| `LocalBevelStyle.current.light` | **3D 斜面的高光边**。经典档的 `WinDivider` 亮线也取它（原先取 `surface` 是错的，见下） |
| `selectionContainerColor` / `selectionContentColor` | 列表项、方言选项、设置页分类 |
| `selectionIndicatorColors()` / `selectionSwitchColors()` | 单选圆点 / 开关 —— M3 的选中标记无法注入 token，只管颜色 |
| `buttonFaceColor` / `buttonInkColor` / `fieldFaceColor` | `WinControls.kt` 的按钮与输入框 |
| `panelBorderStroke()` | 面板 / 卡片描边 |
| `WinDivider` | 26 处分组线 |
| `tabStripContainerColor()` | 标签页条底色 |
| `zebraRowsEnabled()` | 表格隔行底色 |
| `Modifier.uiBevel(raised)` | 3D 斜面（凸起 / 凹陷） |

##### 纯白陷阱：为什么输入框不能用 `surface`

Win2000 浅色档的 `surface` 是纯白 `#FFFFFF`，而经典凹陷边有一侧就是纯白 `ButtonHighlight`
—— 贴在纯白面上那一侧会**完全隐形**，凹陷效果只剩一半。这正是真实 Windows 当年用
「Windows Standard」灰（`#D4D0C8`）而非纯白做控件面的原因。故 `FieldFace.SURFACE_VARIANT`。
`UiTokensTest.classic_bevel_contrasts_against_every_face_it_lands_on` 同时验两种面
（按钮面 `background` + 输入框面 `surfaceVariant`），这个隐形问题正是它先报出来的。

#### 编辑器 / 表格：现代档**原样保留**，只有经典档走 token

`CodeEditorTheme` 与 `DataTableTheme` 都是独立的写死色值，`default()` 按
`isSystemInDarkTheme()` 在 `Light` / `Dark` 两个常量间二选一。

| | 现代三套 | Win2000 / WinXP |
|---|---|---|
| 语法高亮 | 原样 `Light` / `Dark`（VS / Darcula） | `UiThemeTokens.syntax`（Delphi / VS6 系统色） |
| 编辑器底色 | 原样 `#FAFAFA` / `#1E232D` | `fieldFaceColor()` |
| 表格选行 | 原样 `#E3F2FD` / `#2C425E` | `primary` 实心 + `onPrimary` 文字（整行反色） |
| 表格斑马纹 | 保留 | **取消**（`rowBackgroundAlt = null`） |

**为什么现代档必须原样保留**：改造过程中曾把现代分支也改成从 `MaterialTheme.colorScheme`
取色，结果现代三套主题的表格与编辑器整体变色 —— 表头、行底、选行、边框、编辑器底色、
行号槽全部与改造前不同。工作台工具栏（SQL / 造数工作台的「执行」按钮所在那一行）跟着
一起「看着不对」，而根因离按钮有两层之远。

其中 `CodeEditorTheme.Light` 的底色 `#FAFAFA` 与行号槽 `#E8E8E8` 是**刻意**选出来的，
其 KDoc 写着「编辑器是嵌在应用界面里的一块*区域*，不是独立窗口…换成同色底色后工作台与
主界面糊成一团」。这不是随手取的色，不该在复古改造里被顺手改掉。

⚠️ **已知历史行为（非本次引入，未修）**：编辑器与表格按**系统**明暗切换，而非
`AppearanceState` 的明暗档。用户在设置页强制「始终浅色」而系统是深色时，这两块区域会与
界面相反。修它属于独立课题 —— 一旦动，就会再次改变现代主题的观感，需要单独评估。

#### 形状解析：包装层只在经典档改形状

`resolveControlShape(shape, modernDefault)` 是所有包装函数共用的形状解析入口：

| 调用方 | 现代档 | 经典档 |
|---|---|---|
| 显式传了 `shape` | 用传入值 | 用传入值 |
| **没传** | **该组件自己的 M3 默认形状** | 主题的复古形状 |

`modernDefault` **必须由各包装函数按自己的组件传入**，因为 M3 各组件的默认值互不相同：

| 组件 | M3 默认 | 观感 |
|---|---|---|
| `Button` / `OutlinedButton` / `TextButton` | `ButtonSmallTokens.ContainerShapeRound` | 胶囊（全圆角） |
| `OutlinedTextField` | `FilledTextFieldTokens.ContainerShape` = `CornerExtraSmallTop` | `extraSmall.top()`：只有上方两角 2dp，左 / 右 / 下是方角 |

对应 [WinControls] 里的 `resolveButtonShape` / `resolveTextFieldShape`。M3 的
`Shapes.fromToken` 与 `CornerBasedShape.top()` 都是 **internal**，外部调不到，故用
`squaredBottom()`（把下方两角置 0）显式复现。

**这里踩过两次**：① 把默认参数写成 `SundaysPalette.buttonShape`，导致 10 个没传 shape
的按钮在现代档集体变成 4dp 圆角 —— 而 M3 的 hover / press 状态层沿 shape 轮廓绘制，
于是表现为「M3 按钮的 hover 变了」；② 把按钮的胶囊当成统一兜底，导致 9 个输入框的左右边
全变成圆弧。两次都是「我替调用方做了决定」。

#### 经典档的按钮基座：`ClassicButtonBase`

经典 Win 按钮**没有投影**，立体感全部来自那圈 3D 斜边；M3 的悬停 / 按下还会盖一层
半透明高光，而经典控件完全没有这个反馈（鼠标移上去应当纹丝不动）。

试过 `LocalIndication provides …`，编译报错才查清：M3 的 `Surface(onClick=…)` 把
`indication = ripple()` **硬编码**在实现里，既不读 `LocalIndication`，`Button` 也不暴露
`indication` / `interactionSource` 形参 —— 保留 M3 按钮的前提下关不掉。故经典档改为
**不走 `Surface`**，自绘 `ClassicButtonBase`：`clickable(indication = null)` + 无 elevation。

尺寸直接取 `ButtonDefaults.MinWidth` / `MinHeight` / `contentPaddingFor`，**不自己拍
数字** —— 否则经典档按钮会比现代档大一号，而这类差异在混排时非常刺眼。

⚠️ 已知简化：按下时**没有**凹陷反馈（真实经典 Win 是凸起↔凹陷互换）。要做到需自行接管
`interactionSource` 的 pressed 状态并翻转斜面。

#### 平价契约由测试守住

`ModernThemeParityTest`（desktopApp，6 项）把「现代主题必须逐像素保持改造前」钉成契约：
按钮形状、输入框形状、编辑器与表格主题各自原样取回各自的 M3 默认值，并断言**输入框与
按钮的默认值必须不相等**（相等即说明其中一边的兜底又写错了）。

其中一项做过变异验证：把修复退回成原来的错误写法后 2 项立即变红 —— 证明断言真的咬住了
新代码，而不是因为没覆盖到才通过。
