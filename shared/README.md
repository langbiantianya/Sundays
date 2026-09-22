# shared — KMP 共享 UI 组件

面向 **Compose Multiplatform Desktop** 的可扩展 UI 组件库，与 `:engine` 解耦 —— 可在不带引擎依赖的情况下独立使用与测试。

> **当前版本**：v2.9
>
> 内部架构与设计决策见 [`shared/CLAUDE.md`](./CLAUDE.md)

---

## 模块结构

```
shared/
├── commonMain/          所有业务 UI 组件（平台无关）
│   ├── editor/          CodeEditor + 行号 + 高亮 + 工具栏 + 右键菜单
│   │   ├── ui/CodeEditor.kt          主 composable（CodeEditor / CodeEditorWithToolbar + 高度策略）
│   │   ├── EditorContextMenu.kt      EditorContextMenuPayload + rememberEditorContextMenuState
│   │   ├── CodeLanguage.kt           CodeLanguage SPI + CodeLanguageRegistry
│   │   ├── SyntaxHighlighter.kt      token → 颜色映射
│   │   ├── language/SqlLanguage.kt   SQL token + keyword 集合
│   │   ├── language/LuaLanguage.kt   Lua token + keyword 集合
│   │   ├── formatter/SqlFormatter.kt / LuaFormatter.kt / CodeFormatterRegistry.kt
│   │   └── EditorTheme.kt            CodeEditorTheme（Light / Dark / default）
│   ├── table/           DataTable + 虚拟滚动 + 分页 + 详情面板
│   │   ├── DataTable.kt              主 composable（DataTable + 高度策略）
│   │   └── TableModels.kt            TableColumn / TableRow / PageSize / DataTableTheme / ContextMenuState
│   └── ui/              通用 UI 工具
│       ├── ContextMenu.kt            ContextMenuState<T> 通用右键菜单状态
│       └── RightClick.kt             Modifier.onRightClick（鼠标右键检测 modifier）
├── commonTest/          平台无关测试（tokenizer / 模型 / 集成）
├── jvmMain/             JVM 特定实现（最小化：仅 Platform.jvm.kt）
└── jvmTest/             JVM 特定测试
```

**当前仅启用 `jvm` 单一目标**（macOS / Linux / Windows Desktop）。KMP 工程结构天然支持后续扩展 `androidMain` / `iosMain` / `wasmJsMain` —— `commonMain` 中的组件零修改复用，只需新增 source set 提供平台特定的 `pointerInput` / `Okio` 适配。

---

## 组件一览

| 组件 | 路径 | 用途 |
|---|---|---|
| `CodeEditor` | `commonMain/.../editor/ui/CodeEditor.kt` | 语法高亮代码编辑器；可独立使用 |
| `CodeEditorWithToolbar` | 同上 | `CodeEditor` + 工具栏（语言切换 + 格式化 + 自定义 actions） |
| `DataTable` | `commonMain/.../table/DataTable.kt` | 虚拟滚动数据表格；主键承载（数据库行标识） |
| `ContextMenuState<T>` | `commonMain/.../ui/ContextMenu.kt` | 通用右键菜单状态（被 editor / table 共用） |
| `Modifier.onRightClick` | `commonMain/.../ui/RightClick.kt` | 鼠标右键检测 modifier（基于 `awaitPointerEventScope`） |

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

完整 demo 见 [`sundays`](../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt)。

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

# 跑测试（71 项）
./gradlew :shared:jvmTest

# 跑测试（等价）
./gradlew :shared:test
```

测试分布：
- `LuaTokenizerTest` — 30 项（Lua 关键字 / 字符串 / 注释 / 数字 tokenize）
- `SqlTokenizerTest` — 9 项（SQL tokenize）
- `EditorIntegrationTest` — 12 项（`CodeEditor` / `CodeEditorWithToolbar` 集成）
- `TableModelsTest` — 18 项（`TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` + `ContextMenuState` 行为）
- `SharedCommonTest` / `SharedLogicDesktopTest` — 各 1 项（KMP 冒烟测试）

---

## 扩展自定义组件

| 场景 | 说明 |
|---|---|
| **新增语言** | 实现 `CodeLanguage` 接口 + 调用 `CodeLanguageRegistry.register(Language())`。编辑器零修改即支持 |
| **新增 formatter** | 实现 `CodeFormatter` 接口 + 注册到 `CodeFormatterRegistry`。工具栏"格式化"按钮自动启用 |
| **替换主题** | 提供自定义 `CodeEditorTheme` 即可 |
| **替换 token 颜色** | 在 `SyntaxHighlighter.DefaultLightColors` / `DarkColors` 追加键值对 |
| **替换上下文菜单项** | 通过 `contextMenuItems: @Composable (...) -> Unit` 插槽注入任意 `DropdownMenuItem` |

详见 [`shared/CLAUDE.md`](./CLAUDE.md) §5 设计原则。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [根 `README.md`](../README.md) §"共享 UI 组件" | 顶层简短介绍 |
| [`shared/CLAUDE.md`](./CLAUDE.md) | **内部架构设计**：高度策略、可扩展性、databind 模式、与引擎解耦边界 |
| [`engine/CLAUDE.md`](../engine/CLAUDE.md) | 引擎设计 —— 解释 `shared/` 与引擎解耦的原因（v2.9 Direct 模式下通过 `desktopApp/` 集成） |
| [`desktopApp/main.kt`](../desktopApp/src/main/kotlin/com/kxxnzstdsw/sundays/main.kt) | 端到端演示：编辑器 + 表格 + 右键菜单删除 |