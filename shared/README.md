# shared — KMP 共享 UI 组件

面向 **Compose Multiplatform Desktop** 的可扩展 UI 组件库。97 个源文件（`commonMain` 69 / `commonTest` 25 /
`jvmTest` 3），KMP 工程结构，当前仅启用 `jvm` 单一目标（macOS / Linux / Windows Desktop），KMP 结构天然支持后续
扩展 `androidMain` / `iosMain`。

> **本文档的形态**：**大纲 + 组件目录**。「有哪些组件、各自一句话、源码在哪」留在这里；
> **组件内部的设计决策、踩过的坑与契约一律在 `.kt` 的 KDoc 与 [`ARCHITECTURE.md`](./ARCHITECTURE.md) 里**。

---

## 1. 提供哪些组件

| 组件 | 一句话 | 源码 |
|---|---|---|
| `CodeEditor` / `CodeEditorWithToolbar` | 语法高亮 + 行号 + 工具栏 + 格式化 + 补全 + 右键菜单 | [`editor/ui/CodeEditor.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CodeEditor.kt) |
| `CodeEditorState` / `rememberCodeEditorState` | 编辑器内部状态（文本 / 光标 / 滚动），可由调用方状态机持有 | 同上 |
| `CodeLanguage` / `CodeFormatter` | 新语言与新 formatter 的 SPI（配对注册，编辑器零改动即支持） | [`editor/CodeLanguage.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/CodeLanguage.kt)、[`formatter/`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/formatter/) |
| `DataTable` | 虚拟滚动 + 分页 + 详情面板 + 单元格可选中 / 可编辑 | [`table/DataTable.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/DataTable.kt) |
| `TableColumn` / `TableRow` / `PageSize` | 表格的列 / 行 / 分页模型 | [`table/TableModels.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/TableModels.kt) |
| `TableStructureDialog` / `TableStructureDraft` / `TableStructureDiff` / `typeChoices` | 表结构编辑整套：草稿校验与差分、类型候选跟着方言走（可自由输入）、编辑弹窗 | [`table/`](./src/commonMain/kotlin/com/langbiantianya/sundays/table/) |
| `ConnectionManagerScreen` | 连接管理（列表 + 引导式向导 + 连接总览 + 状态色点），**引擎调用全部由回调注入** | [`connection/ConnectionManagerScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionManagerScreen.kt) |
| `ConnectionStorage` / `buildJdbcUrl` / `parseJdbcUrl` / `DialectType` | 连接配置持久化、字段 ↔ JDBC URL 折算、方言枚举 | [`connection/`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/) |
| `DialectMetadata` | **前端方言元数据的唯一真相源**（默认连接类型 / 端口 / 可选连接类型 / 引擎注册名 / URL 前缀 / 图标 / 说明文案 / 标识符引用符 / SQL 档位）—— 加一个方言 = 加一条元数据 | [`connection/DialectMetadata.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/DialectMetadata.kt) |
| `DialectFormRegistry` / `ConnectionFormFields` | **按方言自描述的规格渲染凭据页**（优先读 spec，读不到回退旧的枚举分支）—— 新增一个方言的表单形状不需要改前端一行 | [`connection/DialectFormRegistry.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/DialectFormRegistry.kt)、[`connection/ConnectionFormFields.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionFormFields.kt) |
| `SqlDialectProfileRegistry` | SQL 方言档位注册表（**语言 + formatter 成对注册**，杜绝「只高亮不能格式化」的中间态） | [`editor/SqlDialectProfileRegistry.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/SqlDialectProfileRegistry.kt) |
| `SettingsScreen` / `SettingsStorage` / `SettingsCategory` | 设置页（左分类 / 右内容）、设置持久化、分类枚举 | [`settings/`](./src/commonMain/kotlin/com/langbiantianya/sundays/settings/) |
| `SystemInfo` / `SystemInfoState` | 系统信息数据与四态（**刻意不用 proto 类型**） | [`settings/SystemInfo.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/settings/SystemInfo.kt) |
| `OnboardingScreen` | 首次启动外观引导 | [`onboarding/OnboardingScreen.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/onboarding/OnboardingScreen.kt) |
| `AppDestination` | 顶层导航目标枚举 | [`navigation/AppDestination.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/navigation/AppDestination.kt) |
| `SundaysTheme` / `ThemePalette` / `ThemeMode` / `AppearanceState` / `SundaysPalette` | 三轴外观（配色 / 明暗 / 尺度）与视觉规范单例 | [`ui/`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/) |
| `UiThemeTokens` / `LocalUiTokens` / `UiChrome.kt` 派生函数 | 界面外观的**唯一真相来源**（斜面 / 填充 / 选中态 / 分割线…） | [`ui/UiChrome.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/UiChrome.kt) |
| `CodeEditorTheme` / `DataTableTheme` / `SyntaxHighlighter` | 编辑器与表格的主题档位 —— 换主题只需提供自己的实例，不必改组件 | [`editor/ui/CodeEditorTheme.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CodeEditorTheme.kt) 等 |
| `WinControls` / `FocusRing` / `DragHandle` / `ContextMenuState<T>` / `Modifier.onRightClick` | 通用 UI 工具：复古控件包装、可见焦点环、可拖拽分隔条、右键菜单 | [`ui/WinControls.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/WinControls.kt) 等 |
| `str(...)` / `plural(...)` / `ProvideStrings` / `LanguagePicker` | 界面语言入口（文案表在 `:i18n`，本模块只提供 Composable 侧） | [`ui/LocalStrings.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/LocalStrings.kt) |
| `SettingsEntryButton` / `ThemeModeToggleButton` / `SystemInfoRefresh` | 设置页入口按钮、日夜切换按钮、系统信息自动刷新间隔（默认关闭） | [`ui/ThemeModeToggle.kt`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemeModeToggle.kt) 等 |
| `DangerousSql` / `SqlLiterals` / `DangerousSqlConfirmDialog` | SQL 危险写操作识别与**逐句**只读判定、WHERE 字面量转义与 CAST 目标类型、危险语句确认框 | [`sql/`](./src/commonMain/kotlin/com/langbiantianya/sundays/sql/) |
| `EmptyHint` / `ColumnBadge` / `TabRenameDialog` | 空态提示、字段类型徽标、标签重命名弹窗 | [`ui/`](./src/commonMain/kotlin/com/langbiantianya/sundays/ui/) |
| `SheetTabRow` / `EmptySheetsHint` / `BrowserToolBar` / `BrowserPane` / `SheetSummary` | 浏览器的**标签条 / 工作台切换 / 空态引导** —— 纯展示，只认窄数据形状 | [`browser/`](./src/commonMain/kotlin/com/langbiantianya/sundays/browser/) |
| `NotificationCenter` / `NotificationCenterPanel` / `NotificationBell` | 通知中心（状态 + 面板 + 铃铛入口）—— 跨界面收集错误 | [`notifications/`](./src/commonMain/kotlin/com/langbiantianya/sundays/notifications/) |
| `ObjectTree` / `ObjectTreeModel` / `TreePaneShell` / `TreePaneToggleButton` | **两棵数据库树共用的组件与外壳**：库 → 表 → 字段的渲染与纯模型；面板外壳（**折叠到侧边的 44dp 窄轨** + **可拖拽调宽**的分隔条）与折叠 / 展开按钮 | [`tree/`](./src/commonMain/kotlin/com/langbiantianya/sundays/tree/) |

**扩展方式**：新增语言 / formatter 走 SPI 注册；替换菜单项、详情面板、工具栏动作走 Composable lambda 插槽
（`contextMenuItems` / `detailPanel` / `actions`）—— 不是 `List<UIElement>` 数据驱动。各扩展点的契约见
[`ARCHITECTURE.md`](./ARCHITECTURE.md) 与对应 KDoc。

**高度策略**：两个主组件都是「父容器约束，不溢出」，默认参数下无需显式指定尺寸 —— 具体语义见
[`ARCHITECTURE.md`](./ARCHITECTURE.md) 的设计决策索引与 `CodeEditor` / `DataTable` 的 KDoc。

---

## 2. 与 `:engine` 解耦的边界

**判据一句话**：**凡是引用 `IdbEngine` 或 proto 的代码都无法上移** —— `:engine` 是纯 JVM 模块，
这些类型进不了 `commonMain`。所以 `ConnectionManagerScreen`（引擎调用靠回调注入）能留在 `shared`，
而 `ConnectionSession` / `DatabaseBrowserState` 必须留在 `desktopApp`。

`shared/` **不依赖** `:engine`、gRPC、protobuf、任何 `dialect-*`；依赖方向是单向的
`desktopApp → {engine, shared}`，引擎与 desktopApp 都不感知本模块。系统信息刻意用扁平数据类而非 proto，
由调用方映射 —— 这是解耦的物理保证。边界判据与逐项归属见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)。

> ⚠️ **判据是「代码引用」，不是「import 列表」** —— `import` 里有 proto 类型不等于用到了它。
> 仓库曾有一批**从未被引用的 proto import**（某次重构后的残留），让「这个文件碰不碰引擎」
> 这件事在文件层面失真。本轮已清干净，判据重新可信 —— 但下次改完仍要**按引用判断**，别看 import 列表。

---

## 3. 怎么用它

1. 入口用 `SundaysTheme { … }` 包住，**外观状态提升到它之外**（状态放屏幕内部会出现「按钮变了、界面没变」）。
2. 顶层按 `AppDestination.entries` 分派屏幕 —— 本模块**不提供导航条**，只提供目标枚举。
3. 需要引擎数据的地方自己接 `EngineClient`，把结果经回调喂给 `ConnectionManagerScreen` / `DataTable`
   （`onTestConnection` 等回调为 `null` 时对应按钮自动禁用）。

编辑器与表格都是「默认参数下填满父容器剩余高度、不超父容器」，只在显式传尺寸时才启用硬上限。首次接入
编辑器需在启动时调一次 `registerBuiltinEditors()`（注册内置语言与 formatter，幂等）—— 未注册的语言会
**静默退化为纯文本**而不是报错。

端到端接线见 [`desktopApp/.../main.kt`](../desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt)；
可测的参考状态机见 [`desktopApp/.../ConnectionSession.kt`](../desktopApp/src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt)。

---

## 4. 构建与测试

```bash
./gradlew :shared:build     # 构建（KMP：当前编译 jvm 目标）
./gradlew :shared:jvmTest   # 跑测试
./gradlew :shared:test      # 等价
```

各测试类守护的契约见 [`ARCHITECTURE.md`](./ARCHITECTURE.md) 的测试一节（按 `commonTest` 平台无关 /
`jvmTest` 文件持久化两层划分）。

---

## 5. 相关文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | **内部架构与设计决策索引**（本文不重复） |
| [`../i18n/README.md`](../i18n/README.md) | 界面语言与文案表设计 |
| [`../desktopApp/README.md`](../desktopApp/README.md) | 装配点与运行方式 |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎设计 —— 解释为什么解耦要在 `desktopApp` 集成 |
| [根 `../README.md`](../README.md) | 顶层简短介绍 |
