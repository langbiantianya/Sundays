# desktopApp — 测试用例与覆盖矩阵

> **逐条用例的编号、前置、步骤、预期、实际结果，全部写在对应 test 函数的 KDoc 上**
> （以 `## TC-…` 二级标题开头），本文只保留**执行方式**、**覆盖矩阵**与**全局方法论**。
> 想找某一条用例，按编号直接搜 KDoc 即可；想找「哪个类覆盖什么能力」，看下面第 2 节。

- 执行入口：[`FeatureWalkthroughTest.kt`](./src/test/kotlin/com/langbiantianya/sundays/FeatureWalkthroughTest.kt)（`TC-*` 编号用例全在这里）
- 方言能力差异：[`WalkthroughTarget.kt`](./src/test/kotlin/com/langbiantianya/sundays/WalkthroughTarget.kt)
- 截图落盘：`desktopApp/build/gui-shots/`

---

## 1. 怎么执行

```powershell
.\gradlew.bat :desktopApp:test --tests "com.langbiantianya.sundays.FeatureWalkthroughTest" --console=plain
```

### 为什么是 `runComposeUiTest` 而不是手点真窗口

本机合成鼠标输入（`SendInput` / `mouse_event`）送不进 Compose Desktop 的 Skiko 窗口 —— 光标能移动、点击无响应。`runComposeUiTest` 走的是**同一套**渲染与输入分发链路（真实布局、真实绘制、真实点击），截图是货真价实的界面渲染结果。**与「启动程序」等价，只是驱动方式不同。**

（后续实测更正：不通的是**鼠标**，`keybd_event` / `SetCursorPos + mouse_event` 键盘与指针都是通的。见第 4 节。）

### 为什么两个方言都跑

单跑一个方言，下面这些能力差异**一条都测不出来**：

| | H2 | SQLite |
|---|---|---|
| 视图 / 索引 / 外键 | 有 | 有 |
| 触发器 / 过程·函数 | 有 | **无**（`SQLiteDialect` 抛 `UnsupportedOperationException`） |
| 标识符大小写 | 全**大写** | 原样**小写** |
| 未提交数据的可见性 | 独立连接读不到 | 独立连接读不到，可能被 `SQLITE_BUSY` 锁住 |

### 为什么断言主要打在状态机而不是像素

无头 `captureToImage` 对走 `SelectionContainer` 的文本层漏绘，数据行的文字不会出现在 PNG 里。所以分工固定 —— **状态机 + 独立 JDBC 连接负责「对不对」，截图只负责「长什么样」**。

> ⚠️ **唯一的例外**：当要断言的正是「用户看不看得见」时，语义树里**没有任何量**能回答（节点存在 ≠ 看得见），只能落到像素上。反过来，要断言「这个文本是什么 / 在不在」时**必须**走语义树。`TableHorizontalScrollbarTest` 就是量 `captureToImage()` 拿到滚动条节点的中线像素算 WCAG 对比度。

### 公共前置条件（每个用例都重新建立，不跨用例共享）

- 隔离 `user.home` 到临时目录 —— 否则 `ConnectionStorage` 会碰到真实用户的连接配置（**明文口令**，且 `save` 是整体覆盖语义）
- 造一份固定数据集：`users`(250 行) / `orders`(含外键 + 索引) / `gen_target` / 索引 `idx_orders_status` / 视图 `v_active_users`
- 每个用例自己 `engine.close()` —— 它会连带关掉 `DialectLoader` 的 ClassLoader，漏了会让后一个用例的方言解析不到
- **库名必须唯一** + H2 的 `DB_CLOSE_DELAY=-1`：固定库名必然串库（`H2GuiWalkthroughTest` / `DialectSmokeTest` / `ConnectedSourceEndToEndTest` 都踩过）

### 方言层的冒烟另有两份（不经界面，直接打引擎）

| 测试 | 覆盖 |
|---|---|
| [`DialectSmokeTest`](./src/test/kotlin/com/langbiantianya/sundays/DialectSmokeTest.kt) | **五个方言**（H2 / SQLite / DuckDB / MySQL / PostgreSQL）× 7 项：连通 / 写读闭环 / 过滤排序搜索下推 / 对象浏览 / 取 DDL / 多语句 / 事务可见性 |
| [`DuckDbFileSourceTest`](./src/test/kotlin/com/langbiantianya/sundays/DuckDbFileSourceTest.kt) | **文件型数据源**：CSV / JSON Lines / Parquet / Excel |

---

## 2. 覆盖矩阵

> 每个测试类的**用例 KDoc 里写着它自己为什么这样断言**；本表只回答「哪个类覆盖什么能力」。

### 2.1 连接与生命周期

| 测试类 | 覆盖的能力 |
|---|---|
| [`ConnectionManagerFlowTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionManagerFlowTest.kt) | 真实点击向导：选方言 → 填字段 → 测试连接 → 连接 → 断开 → `connection.json` 落盘；断言引擎侧效果（池建立/释放、状态流转） |
| [`ConnectionWizardMouseKeyboardTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionWizardMouseKeyboardTest.kt) | 鼠标点「新建连接」→ 点方言卡 → 连续点「下一步」到测试步（按钮 `enabled` 与字段联动） |
| [`WizardKeyboardOrderTest`](./src/test/kotlin/com/langbiantianya/sundays/WizardKeyboardOrderTest.kt) | 向导焦点顺序：按 N 次 `Tab` 再回车看 `wizard.step` 变没变 —— 把「猜焦点」变成「数焦点」；方言卡是 `clickable` 不是 `selectable` |
| [`TestConnectionDoesNotOpenSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/TestConnectionDoesNotOpenSessionTest.kt) | **「测试连接」是探测不是会话**：池归零 / 不动用户的活会话 / 探测失败也归还池 / 状态回填不被在途操作覆盖 |
| [`DeleteConnectionConfirmTest`](./src/test/kotlin/com/langbiantianya/sundays/DeleteConnectionConfirmTest.kt) | 删除连接的二次确认：两个入口各弹框、取消保留、确认后只删这一个 |
| [`ConnectionFailureVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionFailureVisibilityTest.kt) | 连接失败原因常驻横幅 + 一键重试 |
| [`ConnectionListKeyboardReachTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionListKeyboardReachTest.kt) | 连接列表的键盘可达性与 Tab 序 |
| [`ConnectionOverviewPrimaryActionVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionOverviewPrimaryActionVisibilityTest.kt) | 最小窗口下总览面板三个按钮完整落在视口内；更矮时被顶出界且**滚动能带回来** |
| [`EngineClientSelectionTest`](./src/test/kotlin/com/langbiantianya/sundays/EngineClientSelectionTest.kt) | 装配点实现选择：未设 / 空白属性 → `IdbEngine`；设 `host:port` / `unix://` → `GrpcEngineClient`；畸形端点 → `IllegalArgumentException`（**响亮失败，不静默回落**） |
| [`WindowSizeTest`](./src/test/kotlin/com/langbiantianya/sundays/WindowSizeTest.kt) | 窗口契约：默认 ≥ 下限 / 默认高度放得下 768 屏 / 右栏放得下 6 列 / 引导页 960dp 内容列不换行 |
| [`SystemInfoFetchTest`](./src/test/kotlin/com/langbiantianya/sundays/SystemInfoFetchTest.kt) | `SYSTEM.INFO` 用空 `ConnectionConfig` 不会被当成真实连接去解析方言 |

### 2.2 浏览屏

| 测试类 | 覆盖的能力 |
|---|---|
| [`FeatureWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/FeatureWalkthroughTest.kt) | **`TC-OB1/OB2`、`TC-DQ1/DQ2/DQ3`、`TC-MS1`、`TC-TX1/TX2/TX3`、`TC-RO1/RO2`** —— 对象浏览 / 过滤·排序·搜索下推 / 多语句 / 事务 / 只读，H2 × SQLite 双方言 × 5 条 |
| [`DatabaseBrowserFlowTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserFlowTest.kt) | 浏览状态机全量：拉库 → 展开表 → 预览行数据 → 标签页去重 → `closeTab` 选中回退 / 三个 pane / `executeSql` SELECT+DDL / SQL 多 sheet 增删与逐 sheet 结果隔离 / 方言档位随连接变 / 造数落库与统计 / 补全候选覆盖库·表·已访问表的字段 / 缓存签名只跟 columns 不跟 rows / `releasePools` 清全部 in-flight running 标志 |
| [`DatabaseBrowserUiTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserUiTest.kt) | 真点击：双击开标签页且 `Role=TAB` 数恒为 1 / SQL 工作台执行 + ＋ 新建 sheet / 切 sheet 显示各自文本与结果 / ✎ 重命名（确定提交、取消放弃）/ × 关闭 / 滚轮横向滚动 / 标题条方言档位与「格式化」可用性 / 造数工作台执行 / 切 pane 后文本·光标·滚动·结果回显 |
| [`DatabaseBrowserSheetsTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserSheetsTest.kt) | 多 sheet 生命周期：增删、激活、切换不丢浏览进度 |
| [`ConnectedSourceEndToEndTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectedSourceEndToEndTest.kt) | **「连得上」与「连上之后能用」之间那道缝**：真引擎建库播种 → `ConnectionSession.connect()` 真连上 → 界面真点树与表 → 读数据 → 过滤/排序/搜索下推 → SQL 工作台执行 → 多语句/事务/只读 → 断开。**五个方言全过** |
| [`ColumnInfoDisplayTest`](./src/test/kotlin/com/langbiantianya/sundays/ColumnInfoDisplayTest.kt) | 字段展示：带长度拼类型 / 长度为 0 不加括号 / 字段名与类型独立 / 约束标记不混进类型 / 默认可空不标「非空」 |
| [`SchemaPanelWidthTest`](./src/test/kotlin/com/langbiantianya/sundays/SchemaPanelWidthTest.kt) | 树面板宽度：拖拽 / 双击复位 / 钳在 `[180dp, 容器宽 × 62%]` |
| [`TreeContextMenuStateTest`](./src/test/kotlin/com/langbiantianya/sundays/TreeContextMenuStateTest.kt) | 对象树右键菜单状态机：复制名称 / 复制引用名（按方言）/ 复制建表 DDL 的可见性与文案；PG 三段式与四段式、字段节点无 DDL 项、剪贴板注入 |
| [`TreeContextMenuUiTest`](./src/test/kotlin/com/langbiantianya/sundays/TreeContextMenuUiTest.kt) | 右键菜单真点击：菜单项点完菜单消失 / 焦点环 / 字段行复制引用 |
| [`CrossDatabaseQueryTest`](./src/test/kotlin/com/langbiantianya/sundays/CrossDatabaseQueryTest.kt) | 跨库浏览（真 MySQL）：非默认库独有的表能查到 / **同名表读到的是选中库的内容** / 配置指定的库仍然正常 |
| [`PostgreSQLCrossDatabaseTest`](./src/test/kotlin/com/langbiantianya/sundays/PostgreSQLCrossDatabaseTest.kt) | PG 跨库（URL 指 A 选 B）：表列表来自 B / A 独有的表不混进来 / 同名表读到 B / A 本身仍正常 |
| [`PostgreSQLSystemObjectTest`](./src/test/kotlin/com/langbiantianya/sundays/PostgreSQLSystemObjectTest.kt) | PG 系统对象不进表列表 / `information_schema` 不进 / 非 public schema 的处理 / `SCHEMA.LIST` 干净 |
| [`MySQLRoutineTriggerQueryTest`](./src/test/kotlin/com/langbiantianya/sundays/MySQLRoutineTriggerQueryTest.kt) | 真 MySQL 的触发器 / 过程列表：列名必须是 `ROUTINE_COMMENT` 而不是 H2 的 `REMARKS`，且过程注释要**原样**读回 |
| [`DialectNameContractTest`](./src/test/kotlin/com/langbiantianya/sundays/DialectNameContractTest.kt) | `DialectType.engineDriverName` 与引擎 `SYSTEM.LIST_DRIVERS` 注册名逐一比对（防 `MYSQL` vs `Mysql` 漂移） |
| [`TableStructureDiffTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/table/TableStructureDiffTest.kt) | 表结构草稿的校验与差分（弹窗本体在 `:shared`）：断言全钉在**不能少算**那一侧 —— 改名不能退化成「删 + 加」（中途失败就真的丢整列数据），外键改名 / 改内容必须落到 DROP + ADD |

### 2.3 表格与分页

| 测试类 | 覆盖的能力 |
|---|---|
| [`TableColumnAlignmentTest`](./src/test/kotlin/com/langbiantianya/sundays/TableColumnAlignmentTest.kt) | 12 列定宽：行宽铺满视口 / 五格横坐标互不相同 / 表体各列与表头对齐 |
| [`TableWeightedColumnMinWidthTest`](./src/test/kotlin/com/langbiantianya/sundays/TableWeightedColumnMinWidthTest.kt) | 23 个加权列的最小宽度下限（能横滚）/ 4 列时不多滚 / **表头每一列都与表体对应列对齐**（量横坐标）/ 表头列宽不被视口摊薄 |
| [`TableHorizontalScrollbarTest`](./src/test/kotlin/com/langbiantianya/sundays/TableHorizontalScrollbarTest.kt) | 横向滚动条拇指与轨道底色的 WCAG 对比度 ≥ 3:1，明暗两档分别断言 |
| [`TableServerPagingTest`](./src/test/kotlin/com/langbiantianya/sundays/TableServerPagingTest.kt) | 引擎侧真分页：`serverSidePaging = true` + `totalCount = tab.total`；`pageSize = 0` 是流式哨兵不是合法分页大小 |
| [`TablePaginationLayoutTest`](./src/test/kotlin/com/langbiantianya/sundays/TablePaginationLayoutTest.kt) | 分页底栏三档窗口宽度契约（1024 / 700 / 390 → 660 / 450 / 253dp） |

### 2.4 SQL 工作台与造数工作台

| 测试类 | 覆盖的能力 |
|---|---|
| [`DangerousSqlTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/sql/DangerousSqlTest.kt) | 危险写操作识别（`DROP` / 全表 `UPDATE` …）+ `isReadOnlyQuery` **逐句**判定（`SELECT 1; DROP TABLE t` 必须拒；注释里的关键字不算语义） |
| [`SqlLiteralsTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/sql/SqlLiteralsTest.kt) | 字面量转义 / CAST 目标类型逐方言（MySQL 只能 `CHAR`）/ `likeContains` 必须产出**带左操作数**的完整谓词 |
| [`CodeEditorKeyboardReachTest`](./src/test/kotlin/com/langbiantianya/sundays/CodeEditorKeyboardReachTest.kt) | 代码编辑器的键盘可达性 |
| [`editor/CodeEditorThemeFollowTest`](./src/test/kotlin/com/langbiantianya/sundays/editor/CodeEditorThemeFollowTest.kt) | 编辑器高亮随主题 / 连接方言切换词表 |
| [`GuiFeatureWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/GuiFeatureWalkthroughTest.kt) | GUI 真点击走查：浏览 / SQL 执行 / 多语句 / 危险确认 / 导出对话框 |

### 2.5 导出

| 测试类 | 覆盖的能力 |
|---|---|
| [`ExportSqlBuildingTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportSqlBuildingTest.kt) | 导出起手 SQL 带上过滤/排序/搜索 / 编号文件名 / 语句切分（用 `SqlScriptSplitter`，不是 `split(";")`）/ 预览白名单逐句判定 |
| [`ExportQueryValidationTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportQueryValidationTest.kt) | 导出参数校验：格式、目录、文件名、目标表 |
| [`ExportAlwaysTerminatesTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportAlwaysTerminatesTest.kt) | **导出必定终止**：引擎只推进度帧永不推完成帧时必须返回失败而不是挂起 / 空文件名在发请求前被拒 / 成功结论带真实路径与行数 / 看门狗不短于一分钟 / 进度**逐帧**回调 |
| [`ExportEndToEndIntegrationTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportEndToEndIntegrationTest.kt) | 桌面层 → 引擎 → 真子进程 → 磁盘有文件；进度里出现过非零行数 |
| [`NativePathPickerTest`](./src/test/kotlin/com/langbiantianya/sundays/NativePathPickerTest.kt) | 系统路径选择器：headless / 对话框不可用时**不抛异常**，安静返回 `null` 保留手动输入这条路 |

### 2.6 后台任务、通知与外观

| 测试类 | 覆盖的能力 |
|---|---|
| [`BackgroundTaskLifecycleTest`](./src/test/kotlin/com/langbiantianya/sundays/BackgroundTaskLifecycleTest.kt) | 后台任务：成功收口 / 失败也必须收口并带原因 / 徽标计数只数运行中 / 「清除已完成」不误伤运行中 / 批量登记 N 个且文件名带序号 / 空列表不登记 / 状态行不报 0 行 |
| [`NotificationCenterTest`](./src/test/kotlin/com/langbiantianya/sundays/NotificationCenterTest.kt) | 通知中心：按来源去重 / 来源消失后可再报 / 徽标 / 打开面板即已读 / 详情原文不截断 |
| [`EngineMemoryStatusBarTest`](./src/test/kotlin/com/langbiantianya/sundays/EngineMemoryStatusBarTest.kt) | 底部堆占用状态栏：比例算法含钳位与除零 / 紧凑格式化 / 初始「—」/ null 与异常降级 / 空态仍在 / 详情面板四行与设置页一致 / 状态栏与面板的宽度定位契约 / 指针移出 200ms 宽限自动关闭 / 指针在面板内**不**关闭 |
| [`ModernThemeParityTest`](./src/test/kotlin/com/langbiantianya/sundays/ModernThemeParityTest.kt) | 现代配色档的组件取色一致性 |
| [`ThemeContentColorTest`](./src/test/kotlin/com/langbiantianya/sundays/ThemeContentColorTest.kt) | 主题内容色 |
| [`ThemeToggleVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ThemeToggleVisibilityTest.kt) | 主题切换入口的可见性 |
| [`CompactThemeTest`](./src/test/kotlin/com/langbiantianya/sundays/CompactThemeTest.kt) | 紧凑模式（缩放 density） |
| [`onboarding/OnboardingScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/onboarding/OnboardingScreenTest.kt) | 首次引导页 |
| [`settings/SettingsScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/settings/SettingsScreenTest.kt) | 设置页 |
| [`MainScreenNavTest`](./src/test/kotlin/com/langbiantianya/sundays/MainScreenNavTest.kt) | 顶层导航：默认落在连接管理，点击「数据库浏览」切到第二屏 |
| [`H2GuiWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/H2GuiWalkthroughTest.kt) | 真 H2 内存库（6 表 / 250 行）走完整主流程并截图：schema 树 → 预览 → 分页 → SQL 补全（表候选优先于关键字、靠右翻转）→ SQL 执行 → 造数并回引擎侧复查 `COUNT(*)` → 树面板宽度拖拽 |

### 2.7 非测试支撑文件

[`SmokeTarget.kt`](./src/test/kotlin/com/langbiantianya/sundays/SmokeTarget.kt)（五个方言的连接串与直连入口）、[`WalkthroughTarget.kt`](./src/test/kotlin/com/langbiantianya/sundays/WalkthroughTarget.kt)（走查双方言的能力矩阵）、[`TestStrings.kt`](./src/test/kotlin/com/langbiantianya/sundays/TestStrings.kt)。

---

## 3. 编号用例索引

| 编号 | 能力 | 落点 |
|---|---|---|
| `TC-OB1` | 库级对象：视图 / 触发器 / 过程·函数 | [`FeatureWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/FeatureWalkthroughTest.kt) · `OB1 object browser lists views and reports unsupported kinds` |
| `TC-OB2` | 表级对象：索引 / 外键 | 同上 · `OB2 table objects list indexes and foreign keys` |
| `TC-DQ1` | 过滤下推（`id > 200` → 50 行，回到第 1 页） | 同上 · `DQ1 filter sort and search are pushed down to the engine` |
| `TC-DQ2` | 排序下推（`id DESC` → 首行 250） | 同上 |
| `TC-DQ3` | 表内搜索下推（`user_25` → 2 行） | 同上 |
| `TC-MS1` | 多语句执行（`CREATE` + 2 × `INSERT`） | 同上 · `MS1 multi statement runs every statement in the script` |
| `TC-TX1` | 事务 BEGIN（拿到 session id） | 同上 · `TX1 rollback discards writes and commit keeps them` |
| `TC-TX2` | ROLLBACK（外部连接看不到） | 同上 |
| `TC-TX3` | COMMIT（外部连接看得到） | 同上 |
| `TC-RO1` | 只读拦截写操作 | 同上 · `RO1 read only blocks writes before they reach the engine` |
| `TC-RO2` | 只读放行 `SELECT` + 回头查库确认表还在 | 同上 |

---

## 4. 真窗口走查：结论与探针方法论

> `runComposeUiTest` 验的是「同一条渲染 / 输入链路上的逻辑」；真窗口验的是「进程起得来、
> 窗口亮着、输入送得进去吗」。两者**互补**，不可互相替代。复现脚本见 [`tools/`](./tools/)。

### 4.1 输入投递：鼠标不通，键盘通（且鼠标后来也通了）

| 投递方式 | 结果 |
|---|---|
| `SendInput` / `mouse_event` 鼠标（早期探针） | ❌ 无效 |
| `PostMessage(WM_LBUTTONDOWN/UP)` | ❌ 无效 |
| `keybd_event`（Tab / Shift+Tab / Enter / Space） | ✅ **有效** |
| `SendInput` + `KEYEVENTF_UNICODE` | ⚠️ 返回 0 = 静默失败；后来发现它会「打错」而不是「打不出来」 |
| `SetCursorPos` + `mouse_event`（`tools/click-window.ps1`） | ✅ **有效** —— 走查效率由此从「Tab 计数 + 截图猜」变成直接点 |

**更正记录**：早期「合成输入都不行 / 操作不了真窗口」的说法是**过头了**。不通的只是当时试的那两种投递方式。

### 4.2 探针自身骗过人的五种形态

1. **截屏把逻辑坐标和物理像素混着用**。PowerShell 默认 DPI-unaware：`GetWindowRect` 返回**逻辑**（1152），`CopyFromScreen` 截**物理**像素。于是按「1152×720 的逻辑矩形」去抓物理像素，**只截到窗口左上角一块**（实际 1440×900，右边 288px 从来没进过截图）。症状与真缺陷一模一样：卡片一路铺到截图右边缘 → 读成「右边距消失」；按钮区从没出现在图里 → 读成「按钮被画到窗口外」。**追了三轮生产代码，真因在探针里。** 修法：先 `SetProcessDPIAware()` / `SetProcessDpiAwarenessContext(-4)` 再取窗口矩形。
   > 教训：凡是「界面看起来不对」，**先确认自己看到的画面是完整的**。
2. **`TYPE:` 打 SQL 会被输入法吃掉**。本机默认中文拼音输入法，而 **Shift 会切换中/英**。`TYPE:amount >= 500` 实测得到 `amount= 500` —— `>` 丢了，语句还「像模像样」地被提交，报错指向别处。改用 `PASTE:`（剪贴板 + Ctrl+V，`WM_PASTE` 直送 Unicode，不经键盘布局与输入法）。
3. **`SendInput` 的 `KEYEVENTF_UNICODE` 是「打错」不是「失败」**。它返回的事件数等于请求数，于是永远走不到兜底；而打出来的是错的字符（`Send-AsciiChar` 把大写字母映射成大写 VK 码却**没按 Shift**）。这次差点被彻底掩盖：表内搜索编译成 `LIKE '%词%'`，MySQL 默认排序规则不区分大小写，**照样搜得到**。
4. **Windows 滚轮方向与直觉相反**：`WHEEL_DELTA` 正数向上。传 `Scroll * 120` 于是「向下滚」其实在往上滚；内容本来就在顶部，**看起来就像 `verticalScroll` 死了**，一度差点当成产品缺陷去改布局。
5. **`gui-probe.ps1` 会把窗口最大化**，它报的窗口尺寸与 `click-window.ps1` 的坐标系对不上 —— 混用两点全空。

> 共同点：**「界面没反应」有三种可能 —— 产品不响应、输入没送到、量的地方不对**，而它们长得一模一样。判据只能是**换一个独立通道再验一次**，不能靠同一套工具换个参数重试。

### 4.3 盲按导航的坑

- **单选组在 Tab 序里只占一站**，组内移动用方向键。把明暗三档当 3 站数，会一直够不到后面的按钮。
- **焦点环在未选中的元素上不明显** —— 截图里「没变化」常常是焦点落在别处。判据要看**选中态**（边框 / 单选圆点 / 开关色）是否变化。
- **disabled 的按钮不在 Tab 序里**，且 `performClick` **不抛异常**、安静地什么都不做 —— 「调用有没有抛错」不能用来判断「点没点着」。
- 焦点位置在真窗口里**不可观测**，只能盲数 Tab，盲数就会错。这是「纯合成输入走完 连接 → 浏览 → SQL」至今没做到的**唯一**卡点（**不是**功能未验 —— 那条路径由 `ConnectedSourceEndToEndTest` 覆盖，五个方言全过）。
- 解法：给向导 / 工具条 / 树节点补 `testTag` + 可见焦点指示。

### 4.4 Compose Desktop 上 `clickable` 没有 hover indication

逐点采样确认：悬停表节点与不悬停时，x=12/60/300/405 四处颜色**完全相同**。而 `TableLeaf` / `DatabaseNode` 早就是 `clickable` 了 —— 是这版 Compose（1.11.1）在 Desktop 上不给 indication，不是某处写漏了。
项目里已有成熟解法（`reportsHover` 监听 `PointerEventType.Enter/Exit` @ `PointerEventPass.Initial` + 手型指针 + `surfaceVariant` 底色 + 文字转 `onSurface`），但**给哪一层加尚未决定**，故未动。

---

## 5. 跨模块缺陷留档

下列缺陷的修复落在 `:engine` / `dialect-*` / `:shared`，**代码注释在那里**；本表只留一句索引，避免以后重复踩。

| 主题 | 一句话结论 | 回归测试 |
|---|---|---|
| MySQL 触发器/过程列表整片报错 | `INFORMATION_SCHEMA` 的注释列在 MySQL 上叫 `ROUTINE_COMMENT`，`TRIGGERS` 压根没有注释列；照 H2 写 `REMARKS` 会整节变红 | `MySQLRoutineTriggerQueryTest` |
| 表预览只有第一列有数据 | 表头与表体都挂在 `horizontalScroll` 里而滚动容器给子项**无界宽度** —— `fillMaxWidth()` 成了空操作、`weight(1f)` 每列分到 0 宽 | `TableColumnAlignmentTest` |
| 表头只显示第一个列名 | `Modifier.width` 是**按传入约束钳位**不是「设绝对宽度」；`.width(contentWidth)` 排在 `horizontalScroll` 外面时被钳回视口宽 | `TableWeightedColumnMinWidthTest` |
| 横向滚动条「看不见」 | 拇指取的是**表面色**，五套主题的表面色挤在一条窄带上（1.1~1.3:1）；控件要跳出色阶得走**墨色** | `TableHorizontalScrollbarTest` |
| PG 把系统目录当用户表 | `current_schemas(true)` 的 `true` 是 `include_implicit`，而 `pg_catalog` 正是隐式搜索路径第一项 | `PostgreSQLSystemObjectTest` |
| 「按 X 分池」≠「按 X 生效」 | 池按 catalog 分开了，会话却全连同一个库；PG 更极端 —— 库是**连接的启动参数**，需要建池时换 URL | `CrossDatabaseQueryTest` / `PostgreSQLCrossDatabaseTest` |
| 向导建出来的 PG 连接 100% 连不上 | 向导把凭据塞进 PG **不认**的 URL userinfo 段（MySQL 认，所以从没暴露）；探针数据的形状与真实路径不一致，缺陷就永远照不出来 | — |
| 导出不产出文件也不提示 | 那条流式路由没有任何一层保证「一定会结束」：命令没发出去 ⇒ 一个帧都没有 ⇒ `collect` 永久挂起 ⇒ 两个回调分支都不执行 | `ExportAlwaysTerminatesTest` / `ExportEndToEndIntegrationTest` |
| 并发导出搞坏 ExportHub 流 | gRPC 的 `StreamObserver.onNext` **不是线程安全的**；用户同时导两张表就是这个并发度 | `ExportPipelineIntegrationTest`（引擎侧） |
| protobuf 整数往返变成 `3.0` | `google.protobuf.Value` 只有 `number_value`（double），`JsonPrimitive(3.0).content` 是 `"3.0"`，`longOrNull` 一律返回 null | `PayloadAdapterNumberRoundTripTest`（引擎侧） |

---

## 6. 判据设计教训（留给下一个写 GUI 测试的人）

| 教训 | 说明 |
|---|---|
| **判据要落在「发出去的那条语句」上** | 片段的**转义**对不等于片段**拼起来**的 SQL 合法。表内搜索从上线起一次没成功执行过，全靠单测盯着转义 |
| **「量了一个对现象不敏感的量」** | 已复发四次：节点在不在 → 节点有多宽 → 有没有分到宽度 → ……全都不敏感（缺陷版本里每列确实分到了 44.5dp，当然 > 0）。**真正敏感的是横坐标与真实渲染像素的对比度**。写断言前先问：这个量真的会因为我要抓的缺陷而变化吗 |
| **「已渲染」不等于「用户看得见」** | 滚动条拇指渲染得一丝不差、像素扫描也确认它就在那儿 —— 但 1.15:1 的对比度让它**等于没画** |
| **量不到就要说量不到** | Popup 内节点在 `runComposeUiTest` 下的 `getUnclippedBoundsInRoot()` 与真实渲染对不上（诊断值 98 / 实测 161 / 反推基准 63）。位置断言是**主动删掉**的，不是漏写 —— 硬补只会得到恒绿或恒红的假信号，让下一个人以为「这里有测试守着」 |
| **诊断信息必须放进 `failure.message`，不能只 `println`** | `system-out` 在 Gradle 报告里**会被截断**。所有超时都把状态机快照 / 直连探测 / 语义树文本塞进异常消息 |
| **界面卡住时用「直连探测」把范围切成两半** | 直连数据库 / 活着的线程数 / 直连引擎同一请求，三条探测同时成立就把范围锁死在 UI 那一发。**只看界面永远分不清「引擎没返回」与「返回了但没回显」** |
| **`tearDown` 必须用 `engine.close()`** | 它会连带关掉 `DialectLoader` 的 ClassLoader；只关连接池会让注册跨用例累积，症状极像 flaky（谁排第 4 取决于 JUnit4 的执行顺序，每轮都不同） |
| **`hasText(substring = …)` 不跨 `AnnotatedString` segment 拼接** | 补全候选的语义节点是 `["USERS", "表", "SHOP_xxx · 表"]` **三段**。改判据前先 dump 一次真实 segment 结构，别靠读渲染结果猜 |
| **变异没变红时，先怀疑「被变异的东西根本不在这条路径上」** | 但测试没变红**不等于**可以不查 —— 查完才知道是 KDoc 写错了，不是实现错了 |
| **单跑通过不足以判定偶发** | 要看**第二轮全量**是否还复现。测试全量红**不等于**代码坏了，得先问「红的那一路和改动有没有关系」 |
| **凡是要「按来源去重」的地方，「什么算同一次」只能有一个产地** | 两边算的 key 不是同一个 → 每一轮对账都把所有来源 forget 掉再当新的重推一遍，去重形同虚设 |

---

## 7. 相关文档

| 文档 | 内容 |
|---|---|
| [`FEATURES.md`](./FEATURES.md) | 能力总览（三栏：已实现 / 引擎有但前端未接线 / 完全没有）与路线建议 |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | 分层、依赖方向、关键设计决策索引 |
| [`README.md`](./README.md) | 运行 / 打包 / 引擎端点属性 |
| [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | `DataTable` / `CodeEditor` / `ConnectionManagerScreen` 的实现细节 |
