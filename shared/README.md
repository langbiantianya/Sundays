# shared — KMP 共享 UI 组件

面向 **Compose Multiplatform Desktop** 的可扩展 UI 组件库，与 `:engine` 解耦 —— 可在不带引擎依赖的情况下独立使用与测试。

> **当前版本**：v2.14
>
> 内部架构与设计决策见 [`shared/ARCHITECTURE.md`](./ARCHITECTURE.md)

---

## 模块结构

```
shared/
├── commonMain/          所有业务 UI 组件（平台无关）
│   ├── editor/          CodeEditor + 行号 + 高亮 + 工具栏 + 右键菜单 + 格式化 + 补全
│   │   ├── ui/CodeEditor.kt          主 composable（CodeEditor / CodeEditorWithToolbar + 高度策略）
│   │   ├── EditorContextMenu.kt      EditorContextMenuPayload + rememberEditorContextMenuState
│   │   ├── Completion.kt             补全数据模型（CompletionItem / CompletionKind）+ 前缀切分 + 接受替换
│   │   ├── GenerateHelpers.kt        造数沙箱宿主函数清单（上下文专属候选 + 沙箱禁用项记录）
│   │   ├── ui/CompletionPopup.kt     补全弹层（贴光标，靠右时向左翻转；按内容自适应宽度 + 纯函数 completionPopupMaxWidth / completionPopupX；置于滚动容器内，不撑大编辑器高度）
│   │   ├── CodeLanguage.kt           CodeLanguage SPI（tokenize + completionCandidates 默认空）+ CodeLanguageRegistry
│   │   ├── SyntaxHighlighter.kt      token → 颜色映射
│   │   ├── language/SqlLanguage.kt   SQL token + keyword 集合 + 补全候选池（大小写不敏感）
│   │   ├── language/LuaLanguage.kt   Lua token + keyword 集合 + 补全候选池（大小写敏感）
│   │   ├── formatter/TokenSpacing.kt 共享的「两向贴合」空格判定（开括号条件贴左，其余按 token 类别）
│   │   ├── formatter/SqlFormatter.kt / LuaFormatter.kt / CodeFormatterRegistry.kt
│   │   └── CodeEditorTheme.kt        CodeEditorTheme（Light / Dark / default）
│   ├── table/           DataTable + 虚拟滚动 + 分页 + 详情面板
│   │   ├── DataTable.kt              主 composable（DataTable + 高度策略）
│   │   └── TableModels.kt            TableColumn / TableRow / PageSize / DataTableTheme / ContextMenuState
│   ├── connection/      连接管理（ConnectionManagerScreen + 配置持久化）
│   │   ├── ConnectionManagerScreen.kt   主 composable（左侧列表 + 4 步 / 3 步引导向导 + 连接总览 + 状态点）
│   │   ├── ConnectionConfig.kt          ConnectionConfig / DialectType / ConnectionType + 持久化精简模型 + withDialect/withConnectionType
│   │   ├── JdbcUrl.kt                   字段 ↔ JDBC URL 折算（覆盖 5 个方言 × 连接类型，URL 为真相源）
│   │   └── ConnectionStorage.kt         ~/.config/sundays/connection.json（按 version 分派 v1/v2，自动迁移）
│   ├── navigation/      顶层导航目标枚举
│   │   └── AppDestination.kt          AppDestination 枚举（CONNECTIONS / DATABASE）
│   │                                   ⚠️ 文档曾列出 TopNavBar.kt —— 该文件已不存在，见 ARCHITECTURE.md §5.2
│   └── ui/              通用 UI 工具
│       ├── Theme.kt                   SundaysTheme（配色/形状逐主题 + 注入 LocalPalette / LocalBevelStyle；字号全局共用）
│       ├── SundaysPalette.kt          视觉规范：5 套配色（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP，各带浅深两版）+ 逐主题形状 + 复古斜面色 + 桌面字号
│       ├── ThemeMode.kt               ThemeMode 明暗三档 + AppearanceState（双轴状态 + 落盘）
│       ├── ThemePalette.kt            ThemePalette 配色主题（蓝灰 IDE / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）+ LocalPalette
│       ├── UiChrome.kt                外观 token 单一来源：UiThemeTokens（8 项决策）+ 派生函数 winShape / selectionColorsFor / buttonFaceColor / WinDivider / tabStripContainerColor + Modifier.uiBevel
│       ├── WinControls.kt             WinButton / WinOutlinedButton / WinTextButton / WinTextField / WinIconButton / WinSurface（现代主题透传 M3）
│       ├── SystemInfoRefresh.kt       SystemInfoRefresh 自动刷新间隔（关闭/10s/5s/2s/1s）
│       ├── ThemeModeToggle.kt         SettingsEntryButton（⚙ 设置入口）+ ThemeModeToggleButton（日夜切换，仅设置页在用）
│       ├── ContextMenu.kt            ContextMenuState<T> 通用右键菜单状态
│       ├── DragHandle.kt             DragHandle 可拖拽分隔条（命中区 8dp / 画线 1dp / 双击复位）+ 纯函数 nextPaneWidth / clampPaneWidth
│       └── RightClick.kt             Modifier.onRightClick（鼠标右键检测 modifier）
│   ├── settings/        设置页 + 设置持久化
│   │   ├── SettingsScreen.kt          左分类 / 右内容（个性化 / 系统信息）
│   │   ├── SettingsCategory.kt        SettingsCategory 枚举（PERSONALIZATION / SYSTEM_INFO）
│   │   ├── SettingsStorage.kt         ~/.config/sundays/settings.json（原子写 + 损坏自愈）
│   │   └── SystemInfo.kt              SystemInfoState（Idle/Loading/Loaded/Failed）+ SystemInfo
├── commonTest/          平台无关测试（tokenizer / 模型 / 集成）
├── jvmMain/             当前为空 —— 无平台特定实现
└── jvmTest/             JVM 特定测试
```

**当前仅启用 `jvm` 单一目标**（macOS / Linux / Windows Desktop）。KMP 工程结构天然支持后续扩展 `androidMain` / `iosMain` / `wasmJsMain` —— `commonMain` 中的组件零修改复用，只需新增 source set 提供平台特定的 `pointerInput` / `Okio` 适配。

> KMP 脚手架样板（`App` / `Greeting` / `getPlatform` 与其 `composeResources` logo）已在 v2.14 删除 —— 入口由 `desktopApp` 持有，样板无任何调用方。

---

## 组件一览

| 组件 | 路径 | 用途 |
|---|---|---|
| `CodeEditor` | `commonMain/.../editor/ui/CodeEditor.kt` | 语法高亮代码编辑器；可独立使用；`enableCompletion` 默认开启关键字 / 类型 / 函数补全（§2.10），`extraCompletions` 可注入**上下文专属**候选（如造数沙箱的 `insert` / `random_*` —— 它们只在那个沙箱里存在，绝不能进 `LuaLanguage`） |
| `CodeEditorWithToolbar` | 同上 | `CodeEditor` + 工具栏（语言切换 + 格式化 + 自定义 actions） |
| `CodeEditorState` / `rememberCodeEditorState` | 同上 | 编辑器内部状态（文本 + 光标/选区 + 滚动）；由 `editorState` 参数注入，调用方状态机持有时可在组件离开组合后保持文本、光标与滚动（`setText` 保留光标，受控输入可在文本中间编辑） |
| `DataTable` | `commonMain/.../table/DataTable.kt` | 虚拟滚动数据表格；主键承载（数据库行标识） |
| `ContextMenuState<T>` | `commonMain/.../ui/ContextMenu.kt` | 通用右键菜单状态（被 editor / table 共用） |
| `Modifier.onRightClick` | `commonMain/.../ui/RightClick.kt` | 鼠标右键检测 modifier（基于 `awaitPointerEventScope`） |
| `DragHandle` | `commonMain/.../ui/DragHandle.kt` | 可拖拽竖向分隔条：拖动实时跟手、双击复位、宽度钳在 `[minWidth, maxWidth]`；**受控组件**（宽度由调用方持有），算术部分拆成纯函数 `nextPaneWidth` / `clampPaneWidth` 供单测。宽度该存哪一层见 [`ARCHITECTURE.md` §6.4](./ARCHITECTURE.md) |
| `ConnectionManagerScreen` | `commonMain/.../connection/ConnectionManagerScreen.kt` | 连接管理（左侧列表 + 引导式配置向导 + 连接总览）；回调注入，**不依赖 `:engine`** |
| `ConnectionStorage` | `commonMain/.../connection/ConnectionStorage.kt` | 连接配置 JSON 持久化（`~/.config/sundays/connection.json`） |
| `buildJdbcUrl` / `parseJdbcUrl` | `commonMain/.../connection/JdbcUrl.kt` | 连接字段 ↔ JDBC URL 折算（URL 是引擎侧真相源） |
| `DialectType.engineDriverName` | `commonMain/.../connection/ConnectionConfig.kt` | 方言枚举 → 引擎 `DatabaseDialect.driverName`（`MYSQL` → `Mysql`；proto `ConnectionConfig.driver` 必须填这个，**不能用 `Enum.name`**） |
| `TopNavBar` | **不存在** | ⚠️ 该组件已无实现（`navigation/` 下只有 `AppDestination.kt`），说明见 [`ARCHITECTURE.md` §5.2](./ARCHITECTURE.md) |
| `AppDestination` | `commonMain/.../navigation/AppDestination.kt` | 顶层导航目标枚举（`label` 供导航条渲染） |
| `SundaysTheme` | `commonMain/.../ui/Theme.kt` | 应用主题（`isSystemInDarkTheme()` → `SundaysPalette` 的深 / 浅配色）；各平台入口只需创建平台容器 |
| `SundaysPalette` | `commonMain/.../ui/SundaysPalette.kt` | 视觉规范单例：5 套配色（蓝灰 `LightColorScheme`/`DarkColorScheme`、赛博朋克 `Cyberpunk*`、哔哩粉 `BiliPink*`、Win2000 `Win2000*`、WinXP `WinXp*`，各带浅深两版）+ `Shapes` + `Typography` + `buttonShape`；设计约束与对比度见 [`ARCHITECTURE.md` §5.4](./ARCHITECTURE.md) |
| `ThemeMode` / `ThemePalette` / `AppearanceState` | `commonMain/.../ui/ThemeMode.kt`、`ThemePalette.kt` | 双轴外观：明暗三档 × 配色主题（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）+ 状态容器；**状态须提升到 `SundaysTheme` 之外**，见 [`ARCHITECTURE.md` §5.6](./ARCHITECTURE.md) |
| `UiThemeTokens` / `LocalUiTokens` | `commonMain/.../ui/UiChrome.kt` | 界面外观的**唯一真相来源**：3D 斜面 / 按钮填充 / 输入框底色 / 选中态画法 / 分割线画法 / 容器描边 / 斑马纹 / 禁用文字色。**新增主题只需在 `ThemePalette.uiTokens` 加一个分支**，组件侧不含任何主题名判断，见 [`ARCHITECTURE.md` §5.6.1](./ARCHITECTURE.md) |
| `SystemInfoRefresh` | `commonMain/.../ui/SystemInfoRefresh.kt` | 系统信息自动刷新间隔（关闭 / 10 / 5 / 2 / 1 秒）；默认关闭 |
| `SettingsEntryButton` | `commonMain/.../ui/ThemeModeToggle.kt` | ⚙ 设置入口；两个面板标题行各一个（弹窗内嵌时不渲染） |
| `ThemeModeToggleButton` | `commonMain/.../ui/ThemeModeToggle.kt` | 日夜切换按钮（图标显示「点下去会变成什么」）。**只在设置页使用** —— 面板标题行的按钮已移除，明暗档位是设置页「个性化」里唯一的切换入口 |
| `SettingsScreen` | `commonMain/.../settings/SettingsScreen.kt` | 设置页：左侧分类 + 右侧内容（个性化 / 系统信息）。**不接触引擎** —— 系统信息由调用方经 `onRequestSystemInfo` 回调喂进来 |
| `SettingsCategory` | `commonMain/.../settings/SettingsCategory.kt` | 设置分类枚举；新增分类只需加枚举项，列表自动出现 |
| `SettingsStorage` | `commonMain/.../settings/SettingsStorage.kt` | 设置持久化 `~/.config/sundays/settings.json`（原子写 + 损坏自愈 + 未知值降级） |
| `SystemInfo` / `SystemInfoState` | `commonMain/.../settings/SystemInfo.kt` | 系统信息数据与四态；**刻意不用 proto 类型**（`:shared` 不依赖 protobuf），由 desktopApp 负责映射 |

---

## 快速上手

### 1. CodeEditor（编辑器）

```kotlin
import com.kxxnzstdsw.sundays.editor.ui.CodeEditorWithToolbar
import com.kxxnzstdsw.sundays.editor.ui.registerBuiltinEditors

registerBuiltinEditors()  // 注册 SQL / Lua + formatter（启动时调一次，幂等）

@Composable
fun SqlEditor() {
    var sql by remember { mutableStateOf("SELECT * FROM users") }
    var lang by remember { mutableStateOf("sql") }

    CodeEditorWithToolbar(
        text = sql,
        onTextChange = { sql = it },
        languageId = lang,
        onLanguageChange = { lang = it },
    )
}
```

### 2. DataTable（数据表格）

```kotlin
import com.kxxnzstdsw.sundays.table.DataTable
import com.kxxnzstdsw.sundays.table.TableColumn
import com.kxxnzstdsw.sundays.table.TableRow
import com.kxxnzstdsw.sundays.table.PageSize

@Composable
fun UserTable() {
    val rows = remember {
        (1..1000).map { i ->
            TableRow(
                id = i.toLong(),
                "id" to i.toLong(),
                "name" to "user_$i",
                "email" to "user$i@example.com",
            )
        }
    }

    DataTable(
        columns = listOf(
            TableColumn(key = "id", header = "ID", width = 80.dp, alignment = TextAlign.End),
            TableColumn(key = "name", header = "姓名"),
            TableColumn(key = "email", header = "邮箱"),
        ),
        rows = rows,
        pageSize = PageSize.S50,
    )
}
```

### 3. ConnectionManagerScreen（连接管理）

```kotlin
import com.kxxnzstdsw.sundays.connection.ConnectionManagerScreen
import com.kxxnzstdsw.sundays.connection.ConnectionStorage
import com.kxxnzstdsw.sundays.connection.TestResult
import com.kxxnzstdsw.sundays.connection.WizardFlow
import com.kxxnzstdsw.sundays.connection.WizardStep

var list by remember { mutableStateOf(ConnectionStorage.load()) }
var selected by remember { mutableStateOf<ConnectionConfig?>(null) }
var editingConfig by remember { mutableStateOf<ConnectionConfig?>(null) }
var wizardStep by remember { mutableStateOf(WizardStep.IDLE) }
var wizardFlow by remember { mutableStateOf(WizardFlow.NORMAL) }
var statuses by remember { mutableStateOf<Map<String, ConnectionStatus>>(emptyMap()) }

ConnectionManagerScreen(
    connections = list.connections,
    selectedConnection = selected,
    editingConnection = editingConfig,
    wizardStep = wizardStep,
    wizardFlow = wizardFlow,
    connectionStatuses = statuses,                          // 引擎侧会话状态：列表色点 + 连接总览
    onSelectConnection = { selected = it },
    onNewConnection = {
        // withDialect 套用方言默认值 + 折算 URL（进入凭据步骤即有合法 URL）
        editingConfig = ConnectionConfig(id = UUID.randomUUID().toString(), name = "新连接")
            .withDialect(DialectType.MYSQL)
        wizardStep = WizardStep.BASIC_INFO
        wizardFlow = WizardFlow.NORMAL
    },
    onQuickConnect = { /* 同上，wizardStep = QUICK_CONNECT; wizardFlow = QUICK_CONNECT */ },
    onEditConnection = { /* editingConfig = it; wizardStep = BASIC_INFO; wizardFlow = NORMAL */ },
    onSaveConnection = { list = ConnectionStorage.upsert(it) },
    onQuickConnectDirect = { selected = it },              // 快速连接：不落盘
    onDeleteConnection = { list = ConnectionStorage.delete(it) },
    onCancelEdit = { /* 回到 IDLE */ },
    onWizardNext = { wizardStep = it },
    onWizardBack = { /* 依 flow 计算上一步 */ },
    onUpdateEditingConnection = { editingConfig = it },
    onConnect = { /* 集成层：IdbEngine.testConnection(cfg) —— 建池 + isValid */ },
    onDisconnect = { /* 集成层：IdbEngine.disconnect(cfg) —— 释放连接池 */ },
    onTestConnection = { cfg ->
        // 由集成层直连引擎 —— shared 不依赖 :engine；回调为 null 时按钮禁用
        val resp = engine.testConnection(cfg.jdbcUrl, cfg.username, cfg.password)
        TestResult(resp.ok, resp.error)
    },
)
```

> 调用方需自行维护 `WizardState(editingConnection, wizardStep, flow)`，并在切换入口（新建 / 快速连接 / 编辑）时**同步**设置 `flow` —— 详见 [`shared/ARCHITECTURE.md`](./ARCHITECTURE.md) §4.6。
>
> 完整的可测状态机实现见 [`desktopApp/.../ConnectionSession.kt`](../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/ConnectionSession.kt)：它把上述回调逻辑收敛成一个类，`ConnectionManagerFlowTest` 用真引擎 + 真点击跑通「选方言 → 填字段 → 测试 → 连接 → 断开 → 重连」全链路。

端到端 demo：见 [`desktopApp/main.kt`](../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt) —— `MainScreen` 用本模块的 `AppDestination` + `ConnectionManagerScreen` 组成顶层导航。

---

## 高度策略（v2.9 统一）

两个组件均采用"父容器约束，不溢出"的高度策略——**默认参数下无需调用方显式指定尺寸**：

| 组件 | 默认参数 | 默认行为 | 显式参数行为 |
|---|---|---|---|
| `CodeEditor` | `maxLines: Int? = null` | `fillMaxHeight()` — 填满父容器剩余高度，不超父容器；超出可滚动 | `heightIn(min, max)` 显式上下限 |
| `DataTable` | `fillParentHeight: Boolean = true` | 外层 `fillMaxSize()` — 填满父容器剩余高度，不超父容器 | `false` 时按内容自适应 |

调用方不传这些参数即自适应父容器；只在显式传入时才启用硬上限。

---

## 构建与测试

```bash
# 构建（KMP：当前编译 jvm 目标）
./gradlew :shared:build

# 跑测试（shared 模块当前 217 项 —— 含 FormatterSpacingTest 27 项、CompletionTest 32 项）
./gradlew :shared:jvmTest

# 跑测试（等价）
./gradlew :shared:test
```

测试分布：

- `LuaTokenizerTest` — 30 项（Lua 关键字 / 字符串 / 注释 / 数字 tokenize）
- `SqlTokenizerTest` — 23 项（SQL tokenize）
- `EditorIntegrationTest` — 12 项（`CodeEditor` / `CodeEditorWithToolbar` 集成）
- `FormatterSpacingTest` — 27 项（格式化契约：标点 / 操作符两侧对称、缩进原样搬运、注释只读、空行折叠、幂等性、无行尾空白 —— 见 [ARCHITECTURE.md §2.8](./ARCHITECTURE.md#28-格式化契约)）
- `CompletionTest` — 32 项（补全契约：前缀切分、接受替换整个词、候选排序与上限、SQL/Lua 大小写策略、方言词表隔离、上下文专属候选、**触发阈值 1 字符**与空前缀仍安静、**limit 是硬约束**、**宿主函数不得进 `LuaLanguage`**）
- `TableModelsTest` — 16 项（`TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` + `ContextMenuState` 行为）
- `JdbcUrlTest` — 12 项（连接字段 ↔ JDBC URL 折算：5 个方言 × 连接类型、参数保留、往返解析、方言/类型切换）
- `SundaysPaletteTest` — 4 项（浅 / 深两套配色的文字对比度达 WCAG AA、明暗亮度方向、`surfaceTint` 透明保证不叠 tonal 色）
- `UiTokensTest` — 8 项（逐主题 × 明暗：经典档每一项造型决策都打开 / 现代档都关掉；形状解析的透传与抹平；反色选中行对比度；斜面对两种控件面的明暗差）
- `ThemeModeTest` — 13 项（三档循环顺序、`next`/`previous` 互逆、显式档位不受系统值影响、三击闭环、逐配色解析双档）
- `SettingsStorageTest` — 8 项（jvmTest：逐档往返、路径与版本字段、损坏文件降级 + 自愈、未知枚举值降级、未知字段忽略、目录自动创建、临时文件不残留）
- `SettingsScreenTest` — 7 项（在 `:desktopApp` 跑：左分类/右内容结构、切分类触发加载、三档切换回写、落盘钩子被调用、loading/成功/失败三态渲染）
- `ConnectionStorageTest` — 4 项（jvmTest：持久化往返重建派生字段、upsert/delete、v1 → v2 迁移回写）

---

## 扩展自定义组件

| 场景 | 说明 |
|---|---|
| **新增语言** | 实现 `CodeLanguage` 接口 + 调用 `CodeLanguageRegistry.register(Language())`。编辑器零修改即支持 |
| **新增 formatter** | 实现 `CodeFormatter` 接口 + 注册到 `CodeFormatterRegistry`。工具栏"格式化"按钮自动启用。**空格判定走 [TokenSpacing](./ARCHITECTURE.md#28-格式化契约)** —— 实现者只管产 token 序列与换行 / 大写，空格 / 缩进 / 注释只读这三件事由共享的两端判定统一处理，新语言接入零特殊适配 |
| **替换编辑器 / 表格主题** | 提供自定义 `CodeEditorTheme` / `DataTableTheme` 即可。**现代档下它们与 `SundaysPalette` 无关**（这是刻意保留的平价契约）；复古档下 `themed()` 会改读 `UiThemeTokens` 的 `syntax` / 选行 / 斑马纹 |
| **改应用配色** | 改 `SundaysPalette` 里的 `ColorScheme` 常量 |
| **改形状** | 改 `SundaysPalette.Shapes` / `Win2000Shapes` / `WinXpShapes`。按钮圆角由 `resolveControlShape` 统一解析，**调用点不必再逐个传 `shape`** |
| **改界面造型（斜面 / 填充 / 选中态 / 分割线…）** | 改 `ThemePalette.uiTokens(useDark)` 里的 `UiThemeTokens`；**新增配色主题只需在这里加一个分支** |
| **改字号** | 改 `SundaysPalette.Typography`（全局共用，刻意不逐主题分叉） |
| **替换语法 token 颜色** | 现代档改 `SyntaxHighlighter.DefaultLightColors` / `DarkColors`；复古档改 `SundaysPalette.Win2000*Syntax` / `WinXp*Syntax`（每个槽位的对比度实测值记在该常量注释里） |
| **替换上下文菜单项** | 通过 `contextMenuItems: @Composable (...) -> Unit` 插槽注入任意 `DropdownMenuItem` |

详见 [`shared/ARCHITECTURE.md`](./ARCHITECTURE.md) §7 设计原则。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [根 `README.md`](../README.md) §"共享 UI 组件" | 顶层简短介绍 |
| [`shared/ARCHITECTURE.md`](./ARCHITECTURE.md) | **内部架构设计**：高度策略、可扩展性、databind 模式、与引擎解耦边界 |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎设计 —— 解释 `shared/` 与引擎解耦的原因（v2.9 Direct 模式下通过 `desktopApp/` 集成） |
| [`desktopApp/main.kt`](../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt) | 端到端演示：`AppDestination` 顶层导航 + `ConnectionManagerScreen` 连接管理 |