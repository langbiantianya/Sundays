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
| [`TablePrimaryKeyDdlTest`](../engine/src/test/kotlin/com/langbiantianya/integration/TablePrimaryKeyDdlTest.kt)（在 `:engine`） | **建表 DDL 契约**：DuckDB 的自增主键表**必须真的有 PRIMARY KEY**（只断言「表建出来了」抓不住 —— DuckDB 建得出来的只是一张没主键的表）/ 重复主键被拒 / 自增序列照旧填值；SQLite 的**内联**主键不与表级约束打架 |

---

## 2. 覆盖矩阵

> 每个测试类的**用例 KDoc 里写着它自己为什么这样断言**；本表只回答「哪个类覆盖什么能力」。

### 2.1 连接与生命周期

| 测试类 | 覆盖的能力 |
|---|---|
| [`ConnectionManagerFlowTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionManagerFlowTest.kt) | 真实点击向导：选方言 → 填字段 → 测试连接 → 连接 → 断开 → `connection.json` 落盘；断言引擎侧效果（池建立/释放、状态流转）；**空闲面板上的「数据分析」入口**在且点了**真的回调**（顶栏的 chip 去掉后，这是分析模式的**唯一**入口）；**连接详情能关掉**（点 ✕ → 取消选中 → 空闲面板连同那个入口一起回来 —— 详情面板把空闲面板顶掉了，没有 ✕ 的话那条唯一的入口选中一次之后就再也找不回来） |
| [`ConnectionWizardMouseKeyboardTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionWizardMouseKeyboardTest.kt) | 鼠标点「新建连接」→ 点方言卡 → 连续点「下一步」到测试步（按钮 `enabled` 与字段联动） |
| [`WizardKeyboardOrderTest`](./src/test/kotlin/com/langbiantianya/sundays/WizardKeyboardOrderTest.kt) | 向导焦点顺序：按 N 次 `Tab` 再回车看 `wizard.step` 变没变 —— 把「猜焦点」变成「数焦点」；方言卡是 `clickable` 不是 `selectable` |
| [`TestConnectionDoesNotOpenSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/TestConnectionDoesNotOpenSessionTest.kt) | **「测试连接」是探测不是会话**：池归零 / 不动用户的活会话 / 探测失败也归还池 / 状态回填不被在途操作覆盖 |
| [`DeleteConnectionConfirmTest`](./src/test/kotlin/com/langbiantianya/sundays/DeleteConnectionConfirmTest.kt) | 删除连接的二次确认：两个入口各弹框、取消保留、确认后只删这一个 |
| [`ConnectionFailureVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionFailureVisibilityTest.kt) | 连接失败原因常驻横幅 + 一键重试 |
| [`ConnectionListKeyboardReachTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionListKeyboardReachTest.kt) | 连接列表的键盘可达性与 Tab 序 |
| [`ConnectionOverviewPrimaryActionVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectionOverviewPrimaryActionVisibilityTest.kt) | 最小窗口下总览面板三个按钮完整落在视口内；更矮时被顶出界且**滚动能带回来** |
| [`EngineClientSelectionTest`](./src/test/kotlin/com/langbiantianya/sundays/EngineClientSelectionTest.kt) | 装配点实现选择：未设 / 空白属性 → `IdbEngine`；设 `host:port` / `unix://` → `GrpcEngineClient`；畸形端点 → `IllegalArgumentException`（**响亮失败，不静默回落**） |
| [`WindowSizeTest`](./src/test/kotlin/com/langbiantianya/sundays/WindowSizeTest.kt) | 窗口契约：默认 ≥ 下限 / 默认高度放得下 768 屏 / 右栏放得下 6 列 / 引导页 960dp 内容列不换行 / **默认尺寸被夹到屏幕工作区以内**（`fitWindowSizeToScreen`：不大于默认、不小于下限、实际高度 ≤ 工作区；无屏幕时跳过） |
| [`WizardSmallWindowTest`](./src/test/kotlin/com/langbiantianya/sundays/WizardSmallWindowTest.kt) | **小窗口下向导走得完**：240 / 360 / 480dp 三档高度下「下一步」必须**显示且点得动**（点击计数 +1）；内容放不下时 `performScrollTo` 能把方言选项滚进来，且**滚完之后按钮仍然在**（底栏固定 ≠ 按钮跟着内容滚走） |
| [`SystemInfoFetchTest`](./src/test/kotlin/com/langbiantianya/sundays/SystemInfoFetchTest.kt) | `SYSTEM.INFO` 用空 `ConnectionConfig` 不会被当成真实连接去解析方言 |
| [`DialectFormSpecRenderingTest`](./src/test/kotlin/com/langbiantianya/sundays/DialectFormSpecRenderingTest.kt) | **凭据页按方言自描述的规格渲染**：假方言声明 3 个自定义字段 + 2 种认证方式 → 界面**自动**出现那 3 个字段与认证下拉，而 MySQL 的内置字段**不出现**（同一个方言换一份 spec 就换一套字段 ⇒ 前端没有按方言写死字段清单）；Kerberos 专属字段只在切到 Kerberos 后出现；只有一种认证方式时不渲染下拉；**没有规格时回退旧的枚举分支**；端到端：引擎 `SYSTEM.LIST_DRIVERS` → session → 界面 |
| [`DialectFormContractTest`](./src/test/kotlin/com/langbiantianya/sundays/DialectFormContractTest.kt) | **连接类型名的三处一致性**（契约字符串 / `:api` 枚举 / `:shared` 枚举）：拼写一字不差、契约认的类型前端都认得、引擎用的类型契约都认得、内置方言声明的 form 前端都查得到、同一份 form 的字段 id 不重复、约定字段 id 就那六个 —— 三处漂移的症状是**静默回退旧分支**（界面正常、spec 失效、无报错），只能靠测试钉住 |

### 2.2 浏览屏

| 测试类 | 覆盖的能力 |
|---|---|
| [`FeatureWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/FeatureWalkthroughTest.kt) | **`TC-OB1/OB2`、`TC-DQ1/DQ2/DQ3`、`TC-MS1`、`TC-TX1/TX2/TX3`、`TC-RO1/RO2`** —— 对象浏览 / 过滤·排序·搜索下推 / 多语句 / 事务 / 只读，H2 × SQLite 双方言 × 5 条 |
| [`DatabaseBrowserFlowTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserFlowTest.kt) | 浏览状态机全量：拉库 → 展开表 → 预览行数据 → 标签页去重 → `closeTab` 选中回退 / 三个 pane / `executeSql` SELECT+DDL / SQL 多 sheet 增删与逐 sheet 结果隔离 / 方言档位随连接变 / 造数落库与统计 / 补全候选覆盖库·表·已访问表的字段 / 缓存签名只跟 columns 不跟 rows / `releasePools` 清全部 in-flight running 标志 / **建表**：多列一次 CREATE 成功且真落库、建完表树里能看到新表且库**不**被折叠掉 |
| [`DatabaseBrowserUiTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserUiTest.kt) | 真点击：双击开标签页且 `Role=TAB` 数恒为 1 / SQL 工作台执行 + ＋ 新建 sheet / 切 sheet 显示各自文本与结果 / ✎ 重命名（确定提交、取消放弃）/ × 关闭 / 滚轮横向滚动 / 标题条方言档位与「格式化」可用性 / 造数工作台执行 / 切 pane 后文本·光标·滚动·结果回显 |
| [`DatabaseBrowserSheetsTest`](./src/test/kotlin/com/langbiantianya/sundays/DatabaseBrowserSheetsTest.kt) | 多 sheet 生命周期：增删、激活、切换不丢浏览进度 |
| [`ConnectedSourceEndToEndTest`](./src/test/kotlin/com/langbiantianya/sundays/ConnectedSourceEndToEndTest.kt) | **「连得上」与「连上之后能用」之间那道缝**：真引擎建库播种 → `ConnectionSession.connect()` 真连上 → 界面真点树与表 → 读数据 → 过滤/排序/搜索下推 → SQL 工作台执行 → 多语句/事务/只读 → 断开。**五个方言全过** |
| [`ColumnInfoDisplayTest`](./src/test/kotlin/com/langbiantianya/sundays/ColumnInfoDisplayTest.kt) | 字段展示：带长度拼类型 / 长度为 0 不加括号 / 字段名与类型独立 / 约束标记不混进类型 / 默认可空不标「非空」 |
| [`SchemaPanelWidthTest`](./src/test/kotlin/com/langbiantianya/sundays/SchemaPanelWidthTest.kt) | 树面板宽度：拖拽 / 双击复位 / 钳在 `[180dp, 容器宽 × 62%]` |
| [`TreePaneCollapseTest`](./src/test/kotlin/com/langbiantianya/sundays/TreePaneCollapseTest.kt) | **树面板的折叠到侧边与宽度可调（浏览与分析两侧都要成立）**：分析模式**能拖宽**（此前写死 240dp）、折叠后**树整体不在屏上**且右边**真的宽出空间**、窄轨**没有分隔条**（44dp 没有「可调宽」可言）、再点一下原样回来、浏览模式折叠**不丢**拖好的宽度 |
| [`TreePaneScrollTest`](./src/test/kotlin/com/langbiantianya/sundays/TreePaneScrollTest.kt) | **折叠不丢滚动位置**：分析模式量**那一行的 y**（树不虚拟化，量得到画面），浏览模式量**滚动状态自己的偏移量**（树在 `LazyColumn` 里，滚出去的行不在语义树里，量不到 y）—— 两侧都是「折叠前后同一件事没变」 |
| [`SqlEditorHeightTest`](./src/test/kotlin/com/langbiantianya/sundays/SqlEditorHeightTest.kt) | **SQL 编辑器高度可调（浏览与分析两侧都要成立）**：往下拖**编辑器变高**且**结果区正好让出同样的高度**（两者共享同一段空间）、往上拖编辑器变矮且高度**全部**进结果区、拖到底**结果区不跌破它的下限**（浏览 180dp / 分析 140dp —— 数据看不见就等于白跑）、高度是**窗口级偏好**（工作台被拆掉又重建后还在）。判据打在**行为**上（量高度差），不是「回调被调了」 |
| [`PointerIconsTest`](./src/test/kotlin/com/langbiantianya/sundays/PointerIconsTest.kt) | 平台专属指针图标：「左右拉伸」箭头造得出来、就是 `java.awt.Cursor.E_RESIZE_CURSOR`（**AWT 里没有 `EW_RESIZE_CURSOR`**）、且是**稳定单例**（每次重组新建 AWT 光标会让 modifier 节点反复比对，表现为光标抖一下） |
| [`TreeContextMenuStateTest`](./src/test/kotlin/com/langbiantianya/sundays/TreeContextMenuStateTest.kt) | 对象树右键菜单**浏览模式特有**的那部分：复制名称 / 复制引用名（按方言）/ 复制建表 DDL 的可见性与文案；PG 三段式与四段式、字段节点无 DDL 项、剪贴板注入。⚠️ 菜单**状态机本身**的用例已随机制上移 `:shared`（见下） |
| [`TreeContextMenuUiTest`](./src/test/kotlin/com/langbiantianya/sundays/TreeContextMenuUiTest.kt) | 右键菜单真点击：菜单项点完菜单消失 / 焦点环 / 字段行复制引用 |
| [`CrossDatabaseQueryTest`](./src/test/kotlin/com/langbiantianya/sundays/CrossDatabaseQueryTest.kt) | 跨库浏览（真 MySQL）：非默认库独有的表能查到 / **同名表读到的是选中库的内容** / 配置指定的库仍然正常 |
| [`PostgreSQLCrossDatabaseTest`](./src/test/kotlin/com/langbiantianya/sundays/PostgreSQLCrossDatabaseTest.kt) | PG 跨库（URL 指 A 选 B）：表列表来自 B / A 独有的表不混进来 / 同名表读到 B / A 本身仍正常 |
| [`PostgreSQLSystemObjectTest`](./src/test/kotlin/com/langbiantianya/sundays/PostgreSQLSystemObjectTest.kt) | PG 系统对象不进表列表 / `information_schema` 不进 / 非 public schema 的处理 / `SCHEMA.LIST` 干净 |
| [`MySQLRoutineTriggerQueryTest`](./src/test/kotlin/com/langbiantianya/sundays/MySQLRoutineTriggerQueryTest.kt) | 真 MySQL 的触发器 / 过程列表：列名必须是 `ROUTINE_COMMENT` 而不是 H2 的 `REMARKS`，且过程注释要**原样**读回 |
| [`DialectNameContractTest`](./src/test/kotlin/com/langbiantianya/sundays/DialectNameContractTest.kt) | `DialectType.engineDriverName` 与引擎 `SYSTEM.LIST_DRIVERS` 注册名逐一比对（防 `MYSQL` vs `Mysql` 漂移）—— **前端枚举里的每个方言，引擎都得真的实现了**；这也是「未落地的方言不进 `DialectType`」那道闸门 |
| [`TableStructureDiffTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/table/TableStructureDiffTest.kt) | 表结构草稿的校验与差分（弹窗本体在 `:shared`）：断言全钉在**不能少算**那一侧 —— 改名不能退化成「删 + 加」（中途失败就真的丢整列数据），外键改名 / 改内容必须落到 DROP + ADD |
| [`DialectMetadataTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/connection/DialectMetadataTest.kt) | 前端方言元数据表：**所有入口读的是同一张表**（默认连接类型 / 可选连接类型 / 引擎注册名 / URL 前缀）；未知方言走最保守的一份；URL 前缀与该方言实际折算出的 URL 一致 |
| [`JdbcUrlTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/connection/JdbcUrlTest.kt) | 字段 ↔ URL 折算与回解析；**自定义 URL 原样保留**、切到自定义 URL 时清空旧 URL、外来 URL 不按方言解析 |
| [`ConnectionStorageTest`](../shared/src/jvmTest/kotlin/com/langbiantianya/sundays/connection/ConnectionStorageTest.kt) | 连接配置落盘：逐档往返、**v1 → v2 → v3 迁移**、自定义 URL 的连接存盘再加载**仍是自定义 URL** |
| [`ColumnTypeCatalogLengthTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/table/ColumnTypeCatalogLengthTest.kt) | 读回表结构时「长度」的取值：引擎给的 `size` 对数值列是**十进制位数**（`BIGINT` 是 19）不是长度 —— 照搬就成了「BIGINT 长度 19」；还要挡掉 JDBC 的「不限长度」哨兵（PG 的无界 varchar = 2147483647） |

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
| [`SqlResultMinHeightTest`](./src/test/kotlin/com/langbiantianya/sundays/SqlResultMinHeightTest.kt) | **SQL 结果区不得被压成 0 高**：320dp 高的窗口里结果表数据区 `boundsInRoot.height > 0` —— 标题写着「查询结果 · 2 行」而数据区**零高度**，等于查了白查 |
| [`GenerateLogTest`](./src/test/kotlin/com/langbiantianya/sundays/GenerateLogTest.kt) | **造数实时日志（状态层与纯函数）** —— 用**真引擎**（H2 + 真 Lua 沙箱 + 真流式通道）验：每条 INSERT 一行且带表名 / 脚本内行号 / 列值（与库里那一行**对得上**）、日志里的列值与**实际写进库的**一致、重新执行与切连接都会清空、失败也留一行且带原因原文、有界（保留 + 丢掉的行数 = 追加的行数，一行都不凭空消失）、追加序号每次都递增。**为什么必须用真引擎**：「引擎每条 INSERT 回一帧」是协议契约，假的帧只能验「映射函数写对了」，验不了「引擎真的这么发」 |
| [`GenerateLogUiTest`](./src/test/kotlin/com/langbiantianya/sundays/GenerateLogUiTest.kt) | **造数实时日志（界面层）** —— 日志面板常驻、空态有提示、真实造数的每一行都出现在面板里、**自动滚到最新一行**（LazyColumn 只组合看得见的项：最后一行可见 = 滚动确实发生了）、日志溢出时标题栏**明写丢了多少行**（并钉住它在**标题栏里**，而不是混在日志流里等人翻）、跑完之后**完成摘要出现在底部状态行**。断言打在**列值文本**上（不受语言影响），而不是中文文案 |
| [`GenerateEditorHeightTest`](./src/test/kotlin/com/langbiantianya/sundays/GenerateEditorHeightTest.kt) | **造数工作台的编辑器高度可拖** —— 与浏览 / 分析两侧共用同一个横向分隔条：往下拖编辑器**真的变高**且**跟手**（不是松手才跳）、高度**守恒**（编辑器多出来的那块正好从输出面板来）、拖到底也**挤不没输出面板**（260dp 下限是硬边界）、窗口很矮时**编辑器的下限要让位给输出面板的下限**、高度是**窗口级偏好**（工作台被拆掉又重建后还在）。另有两条判据钉住「**统计结果只占一行**」（状态行 ≤ 48dp，且远小于日志区 —— 为两三行内容留一整块地，是把最稀缺的地方浪费掉）与「**日志与状态行在同一个面板里**」（共同的祖先面板）—— 此前它们是两个各自独立的 `Surface`，只断言「两个都存在」的话拆回去也照样通过 |

### 2.5 导出

| 测试类 | 覆盖的能力 |
|---|---|
| [`ExportSqlBuildingTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportSqlBuildingTest.kt) | 导出起手 SQL 带上过滤/排序/搜索 / 编号文件名 / 语句切分（用 `SqlScriptSplitter`，不是 `split(";")`）/ 预览白名单逐句判定 / **表名带库名限定**（MySQL → `` `库`.`表` ``；PG / H2 不把库名当 schema 用） |
| [`ExportCatalogTargetingTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportCatalogTargetingTest.kt) | **导出打的是被导出的那张表所在的库** —— 不是「树里第一个展开的库」。假引擎记下请求的 `connection.database`：显式传 `catalog` 时请求带它；不传才退回 `currentSchema()` |
| [`ExportQueryValidationTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportQueryValidationTest.kt) | 导出参数校验：格式、目录、文件名、目标表 |
| [`ExportAlwaysTerminatesTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportAlwaysTerminatesTest.kt) | **导出必定终止**：引擎只推进度帧永不推完成帧时必须返回失败而不是挂起 / 空文件名在发请求前被拒 / 成功结论带真实路径与行数 / 看门狗不短于一分钟 / 进度**逐帧**回调 |
| [`ExportEndToEndIntegrationTest`](./src/test/kotlin/com/langbiantianya/sundays/ExportEndToEndIntegrationTest.kt) | 桌面层 → 引擎 → 真子进程 → 磁盘有文件；进度里出现过非零行数 |
| [`NativePathPickerTest`](./src/test/kotlin/com/langbiantianya/sundays/NativePathPickerTest.kt) | 系统路径选择器：headless / 对话框不可用时**不抛异常**，安静返回 `null` 保留手动输入这条路 |

### 2.6 后台任务、通知与外观

| 测试类 | 覆盖的能力 |
|---|---|
| [`BackgroundTaskLifecycleTest`](./src/test/kotlin/com/langbiantianya/sundays/BackgroundTaskLifecycleTest.kt) | 后台任务：成功收口 / 失败也必须收口并带原因 / 徽标计数只数运行中 / 「清除已完成」不误伤运行中 / 批量登记 N 个且文件名带序号 / 空列表不登记 / 状态行不报 0 行 |
| [`ErrorReconciliationTest`](./src/test/kotlin/com/langbiantianya/sundays/ErrorReconciliationTest.kt) | 屏级错误**对账**：推送与遗忘必须用同一个 key —— 同一批错误反复对账只推一条；错误消失后再现会重新推送；错误文本变化视为新的一条；没有错误时不产生任何通知（通知中心本身的行为已随组件上移，见 [`shared/.../notifications/NotificationCenterTest.kt`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/notifications/NotificationCenterTest.kt)） |
| [`EngineMemoryStatusBarTest`](./src/test/kotlin/com/langbiantianya/sundays/EngineMemoryStatusBarTest.kt) | 底部堆占用状态栏：比例算法含钳位与除零 / 紧凑格式化 / 初始「—」/ null 与异常降级 / 空态仍在 / 详情面板四行与设置页一致 / 状态栏与面板的宽度定位契约 / 指针移出 200ms 宽限自动关闭 / 指针在面板内**不**关闭 |
| [`ModernThemeParityTest`](./src/test/kotlin/com/langbiantianya/sundays/ModernThemeParityTest.kt) | 现代配色档的组件取色一致性 |
| [`ThemeContentColorTest`](./src/test/kotlin/com/langbiantianya/sundays/ThemeContentColorTest.kt) | 主题内容色 |
| [`ThemeToggleVisibilityTest`](./src/test/kotlin/com/langbiantianya/sundays/ThemeToggleVisibilityTest.kt) | 主题切换入口的可见性 |
| [`CompactThemeTest`](./src/test/kotlin/com/langbiantianya/sundays/CompactThemeTest.kt) | 紧凑模式（缩放 density） |
| [`onboarding/OnboardingScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/onboarding/OnboardingScreenTest.kt) | 首次引导页 |
| [`settings/SettingsScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/settings/SettingsScreenTest.kt) | 设置页 |
| [`MainScreenNavTest`](./src/test/kotlin/com/langbiantianya/sundays/MainScreenNavTest.kt) | 顶层导航（**端到端**）：启动落在**首屏** → 选「数据分析」**直接**进分析工作台；选「连接管理」→ 空闲面板的「数据分析」入口 → 分析工作台。组件级用例只能证明「回调被调用了」，证明不了**装配点真的接到了目标切换上** |
| [`StartScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/StartScreenTest.kt) | **启动首屏**：两个模式都在（少一个就等于把这个模式藏起来了），且**各点各的**（两个入口接错线的症状是「点了进错屏」，只有真的点才抓得住） |
| [`TopNavBarTest`](./src/test/kotlin/com/langbiantianya/sundays/TopNavBarTest.kt) | **顶栏没有目标切换 chip**（只有品牌 + ⚙）：「连接管理」「数据分析」两个 chip 已去掉 —— 目标切换不再发生在顶栏，chip 很容易被「顺手加回来」，而「分析模式的入口在哪儿」是产品契约 |
| [`H2GuiWalkthroughTest`](./src/test/kotlin/com/langbiantianya/sundays/H2GuiWalkthroughTest.kt) | 真 H2 内存库（6 表 / 250 行）走完整主流程并截图：schema 树 → 预览 → 分页 → SQL 补全（表候选优先于关键字、靠右翻转）→ SQL 执行 → 造数并回引擎侧复查 `COUNT(*)` → 树面板宽度拖拽 |

### 2.7 数据分析模式

| 测试类 | 覆盖的能力 |
|---|---|
| [`AnalysisCapabilityContractTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisCapabilityContractTest.kt) | **能力位契约**：MySQL / PostgreSQL 声明 `ANALYSIS_SOURCE`（分析模式的远端数据源）；H2 / DuckDB / SQLite **不**声明（本地库不是数据源 —— 计算引擎是 DuckDB 自己）；能力位以**枚举名**下发 —— 前端比对的是名字而不是下标，下标会随枚举增删而漂移 |
| [`AnalysisModuleBoundaryTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisModuleBoundaryTest.kt) | `:shared` 的 `analysis/` 包的**结构边界**：不 import `java.*` / `javax.*`、不 import 引擎与协议层（界面只该接收**算好的**数据）、不出现字面量颜色。断言打在**源文件**上 —— 字节码里看不到「只用了类型」的 import |
| [`AnalysisScreenTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisScreenTest.kt) | 分析工作台的界面契约：**「▶ 运行」与「格式化」同一行**（判据是**几何** —— 垂直位置重合；父节点链会跳过没有语义的布局节点，Compose 改一改层级就断，而「同一行」本来就是几何事实）、**没有**只读徽标、**树渲染到字段**（按 `objectTreeRowTag` **精确定位到行**，不靠文本猜）、**源只在树里选**（工具条**没有**「数据源」下拉框 —— 那是**两份可能对不上的真相**：下拉框选了 A 源而树上点了 B 源的表，不报错却拉错库；判据是源的名字在屏幕上**只出现一次**）、无数据源时的空态文案**说清下一步做什么**、**结果表没有任何编辑入口**（判据是「可编辑的单元格才有的标记」—— 点一下看有没有输入框是**假绿**，实测可编辑时也点不出来）、结果表显示列与行、失败时显示错误原文、「▶ 运行」会回调且**运行中禁用**、编辑器里有当前 SQL |
| [`ObjectTreeTest`](./src/test/kotlin/com/langbiantianya/sundays/ObjectTreeTest.kt) | **共享树组件的界面契约**（浏览模式与分析模式**共用**它 —— 改坏它两边一起坏）：每行有标记、**固定名压过原名**（「本地工作区」是应用自带的引擎，名字由界面按 `StrKey` 取）、容器行带标签、点容器行回调「展开」、**点表回调「选中」而不是「展开」**（两个后果不同：选中远端表会**拉数据**）、**点表的扭子回调「展开」且不选中**（点整行已经占用在「选中表」上，同一个手势不能既选中又展开）、**字段渲染出类型与 `PK` 标记**、**点字段回调「选中」并带着它属于哪张表**、展开后子节点出现、空树不崩 |
| [`AnalysisTreeSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisTreeSessionTest.kt) | **会话里的树**：树里**只有工作区**（保存的连接配置**不在**分析树里 —— 那是连接管理器的事，两处都列一遍就成了两份可能对不上的真相）、工作区永远在第一位、工作区在树上、标签标出 DuckDB 引擎、展开工作区真能列出它的表（真 DuckDB）、**展开一张表真能列出它的字段**（名字 / 类型 / 主键 / 可空 —— 走 `ANALYSIS.LIST_COLUMNS`，与浏览模式**同一条**引擎路由）、点两次收起、**收起再展开不丢已加载的子节点**（收起只是「不看」，不是「忘掉」）、加载失败**留下原因**而不是变成「空列表」、**连接列表变了也不影响树**、把工作区的表名加进编辑器（空 / 非空两种） |
| [`AnalysisHandlerIntegrationTest`](../engine/src/test/kotlin/com/langbiantianya/integration/AnalysisHandlerIntegrationTest.kt)（在 `:engine`） | 四条分析路由的集成测试：命名空间按 `supportsSchema` 分派（schema 型 / 库型）、`RUN_QUERY` 打在**非嵌入式**方言上被拒、行数到上限时 `truncated` 置位、正好等于上限**不算**截断 |
| [`AnalysisSourceDialogTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisSourceDialogTest.kt) | **「添加数据源」对话框的界面契约**：对话框弹得出来、远程那一组参数都在、**路径框旁有「浏览」**（系统文件 / 文件夹选择器）、**格式能手选**（文件夹没有扩展名，推断不出来） |
| [`AnalysisSourceSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisSourceSessionTest.kt) | **「添加数据源」在会话里的行为**（真 DuckDB 工作区 + 真文件）：CSV 文件**真的在工作区里** —— 建的是**视图**（查得到两行，不是「拷了一份」）、它**出现在树上**（根 = 工作区的各个 schema）、**源登记在册**（换个会话读得到 —— 登记表在工作区 DuckDB 里）、**看不出格式就拒绝**（不猜，报错说清是「格式看不出来」）、**没有名字就不启动任何任务** |
| [`AnalysisSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisSessionTest.kt) | **分析状态机**：① 两条会话的**只读方向相反**（工作区可写 / 远端源只读）；② 浏览模式那份连接配置**不被就地改写**；③ 空语句不启动查询；④ DDL 的 `affectedRows` 是 -1（JDBC 的「不是更新计数」）而不是 0；⑤ SQL 出错收成**失败结果**而不是抛异常；⑥ 工作区目录会被自动建出来；⑦ **拉取的三条终态**（完成 / 取消 / 失败各落各的态 —— **取消不是失败**）；⑧ 没选源时拉取不启动 |
| [`AnalysisWorkspaceIntegrationTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisWorkspaceIntegrationTest.kt) | **工作区端到端**（真 DuckDB 文件、真引擎）：能建表、**重开连接表还在**（证明真的落盘而不是只在内存里）、目录会被建出来、查询能回行与列。连接配置**直接取会话那份** —— 早先这里照抄了一遍「工作区怎么连」，测试就会对着另一种连法发绿 |
| [`AnalysisPullIntegrationTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisPullIntegrationTest.kt) | **整条链路**端到端（前端拉取器 → 真引擎 → 联邦扫描 → 真 DuckDB 工作区）：表真的落进工作区、进度帧真的到得了调用方、失败**不留半张表**、**拉取全程不落盘**、用户取消被报成「取消」而不是「失败」 |
| [`AnalysisPullSmokeTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisPullSmokeTest.kt) | **真 MySQL** 端到端：行数对上、**全程不落盘**、进度报出行数、**拉下来的表保留远端类型**（`SUM(id)` 直接能跑，不必 `TRY_CAST`）、窗口函数聚合、工作区里 `CREATE TABLE tmp AS SELECT` 成功。内网 MySQL 不可达时**显式 skip**（报告里留一条 skipped），不是静默跳过 |
| [`AnalysisNativePullTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisNativePullTest.kt) | **原生扫描器**（可选加速）的端到端（真 MySQL → 真 DuckDB 工作区）：① 显式打开时走的**确实是**原生那条路（列类型是官方的 `SMALLINT`）；② **默认没打开**时走纯 Java 表函数（列类型是我们的 `INTEGER`）；③ 拉完之后工作区里**没有残留的远端挂载**（挂着的那条目录记录里**含明文密码**）。⚠️ 判据不能用 `TINYINT(1)`：MySQL Connector/J 把它报成 `Types.BIT`，**两条路都是 BOOLEAN** |
| [`AnalysisPullIndicatorTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisPullIndicatorTest.kt) | **拉取进度的界面契约**：「正在拉取 · 已读 N 行」、**界面上不再出现「临时文件」**（联邦直查不落盘）、取消按钮回调、没给取消回调时不画按钮、完成 / 取消 / 失败三种终态各说各的话、进行中**不显示**上一次的终态 |
| [`TreePaneCollapseTest`](./src/test/kotlin/com/langbiantianya/sundays/TreePaneCollapseTest.kt) | **分析模式左树的面板契约**：左树**能拖宽**（此前写死 240dp）、**能折叠成侧边窄轨**（树整体从屏上消失、右边结果区**真的变宽**）、窄轨上**没有**拖宽把手、再点一下原样回来 —— 与浏览模式**同一个组件**（`SidePaneShell`） |
| [`TreePaneScrollTest`](./src/test/kotlin/com/langbiantianya/sundays/TreePaneScrollTest.kt) | **分析模式左树折叠不丢滚动位置** —— 折叠时整棵树从组合里移除，滚动状态若记在树上就跟着没；提到屏幕层后，折叠再展开树还停在原来那个位置（量**那一行的 y**，不虚拟化所以量得到画面） |
| [`AnalysisRemoveSourceTest`](./src/test/kotlin/com/langbiantianya/sundays/AnalysisRemoveSourceTest.kt) | **「删除数据源」在会话里的行为**（真 DuckDB 工作区 + 真文件源）：删得掉（工作区里那个 schema 与登记表里那一行**都**没了 —— 只删一处的话，要么重启后它又「回来」而表已经没了，要么树上多一根没有来由的 schema）、**它带进来的表一起没了**、**不误伤**工作区自己的表、**工作区自己不能被删**（`DROP SCHEMA main CASCADE` 会删掉整个工作区）、**没登记过的 schema 不能被删**（用户手工建的东西不是数据源）、成功与失败各留下一个**说得清**的结果 |
| [`AnalysisTreeMenuTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/analysis/AnalysisTreeMenuTest.kt)（在 `:shared`） | **右键菜单的判据**（纯函数 `analysisDeleteSourceTarget`）：登记在册的源里的表给删除项且**删的是那个源**、源容器那一行也给、字段也归它所在的源；**工作区自己的表不给**、**工作区根不给**、**没登记过的 schema 不给**、清单为空时什么都不给 |
| [`TreeContextMenuStateTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/tree/TreeContextMenuStateTest.kt)（在 `:shared`） | **两棵树共用的右键菜单状态机**：坐标换算（节点局部 → **root**，原样透传会让菜单偏整屏 —— 真窗口实测偏上 414px 且正好盖在别的节点上）、换算**不含宿主信息**、目标被替换而不是累积、`dismiss` 只清目标不清坐标、`showAt` 直接用 root 坐标 |
| [`ReportSessionTest`](./src/test/kotlin/com/langbiantianya/sundays/ReportSessionTest.kt) | **报表在会话里的行为**（真 DuckDB 工作区 + 真引擎）：① 视图可切且**切完还在**（会话持有 —— 顶层页切换会重建组合，屏幕层的 `remember` 活不过那次切页）；② 新报表**被选中**、名字**先是空的**（「报表 N」那个「1」是要翻译的词，会话不是 Composable，取不到当前语言）；③ 重命名去首尾空白、**选中不存在的报表什么也不变**；④ 删掉选中的那一张会**顺手选中剩下的第一张**，删掉最后一张则**什么都不选**；⑤ 删报表 / 删部件**连它的数据一起删**（否则留下一堆没人引用的垃圾）；⑥ 新部件**保存时才拿到 id**，两个部件**id 不一样**（共用一个 id 会让数据互相盖掉），改一个部件**id 与位置都不变**；⑦ **样例数据是真跑出来的**（两列都带回来）、空语句**不启动查询**、失败的预览**报原因而不是抛异常**；⑧ 刷新一张报表**每个部件都拿到自己那份**（串行跑 —— 并发会让后完成的盖掉先完成的，界面上出现错配），刷新一张**不存在的**报表**什么也不发生** |
| [`ReportModelsTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/analysis/report/ReportModelsTest.kt)（在 `:shared`） | **报表的纯规则**：① 图表的**数据形状判据**（null 结果 / 查询失败 / 没有列 / 只有一列 / 没有数据行 / 两列正常 / **值不是数字** / **负值** / 空标签）；② 取值范围（没有数据不崩）与**颜色循环**（十几个类别是常事，超出色板要循环而不是抛异常）；③ 新部件**没有 id**（id 是存下来之后才有的身份）且**默认条形图**（人眼比长度比角度准得多）；④ 五种画不出来的原因**各有各的一句话**、三种类型**各有各的一句话**（合成一句等于什么都没说）；⑤ **样例数据过期**的判定（还没预览过 / 预览的就是当前 SQL / 预览之后又改了 SQL） |
| [`AnalysisSqlRefTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/analysis/AnalysisSqlRefTest.kt)（在 `:shared`） | **「树上的一个对象」到「SQL 里的一段引用」的纯规则**（分析工作台与报表设计**共用这一份**）：① `main` 里的表**不带** schema 前缀（每次都写 `"main"."orders"` 的话，用户复制到别处就多了一截多余的前缀）；② 没有命名空间 / 空白命名空间同样不带前缀；③ 数据源的表**必须带** schema（不带的话查的可能是 `main` 里的**同名表**，而且**不报错**）；④ 名字里的空格与内嵌双引号**各自**正确转义；⑤ 字段引用**必须带表名**（光有列名在多表查询里说不清是哪张表的列）；⑥ 空编辑器给一条**能直接跑**的语句（`SELECT * FROM "orders"` / `SELECT "orders"."id" FROM "orders"`），有内容时**追加一行**；⑦ 旧 SQL 的**尾部空白被裁掉**（不裁的话追加的那行前面会留一串空格）；⑧ 纯空白的编辑器**也算空** |
| [`SidePaneShellTest`](../shared/src/commonTest/kotlin/com/langbiantianya/sundays/ui/SidePaneShellTest.kt)（在 `:shared`） | **侧边面板的左右镜像规则**（纯函数 `sidePaneToggleIcon`）：收起时箭头指向**贴边的那一侧**（贴左 → 左箭头，贴右 → 右箭头），展开时指向**离开贴边的那一侧**；两侧的箭头必须**互为镜像**。箭头画反了程序**不会崩也不会报错** —— 界面上只是「点下去往反方向收」，那种别扭没有报错信息，很可能永远没人报 bug；而箭头的朝向在 UI 测试里**量不出来**，所以必须有一个能单测的纯函数守着这条规则 |
| [`ReportDesignerUiTest`](./src/test/kotlin/com/langbiantianya/sundays/ReportDesignerUiTest.kt) | **报表设计页面的界面契约**：① 报表列表**圈定范围内**显示报表名（同名会在画布标题再出现一次，不圈定就「找到 2 个」）；② 没名字的报表**按列表位置编号**（按创建顺序编号的话，删掉第一张后剩下的是「报表 2、报表 3」，列表里明明只有两张）；③ 加部件**打开编辑页**，三种图表类型都在；④ 预览**把这条部件的 SQL 交出去**；⑤ **样例数据摆在 SQL 旁边**（原始行可见 —— 圈定在样例数据表内，图例里也有相同的词）；⑥ **画不出图表要说清是哪一种**（「值不是数字」与「缺列」要能分开）；⑦ 失败的预览**显示错误原文**而不是空表；⑧ **删报表 / 删部件都要先问一句**（没确认时**绝不能**真的删 —— 那是不可恢复的）；⑨ 刷新时按钮**禁用**（还亮着就成了「点了没反应」）；⑩ 视图切换条**能切**；⑪ **报表设计页上有数据树**且**树里真有内容**（只摆一个空壳面板不算「显示了树」）；⑫ **点树上的表开一个新部件**，SQL 预填成 `SELECT * FROM "orders"`（用户不用记住表名再敲一遍）；⑬ **编辑页里点字段把引用插进 SQL**（`"orders"."amount"`，**必须带表名**）—— 圈定到编辑页那一棵树上（画布上那棵被编辑页盖住了但**还在组合里**，不圈定就会找到两行）；⑭ **还没有报表时点树先建一张**（部件总得有个家；「提示用户先建报表」等于让那一下点击**白点**）；⑮ **布局是「树 ｜ 画布 ｜ 报表列表」** —— 量的是**三个块的 x 坐标**（树 < 画布 < 列表），且报表列表的**右边缘贴着窗口的右边缘**（它就该在窗口最右）；⑯ **数据树能折叠成左边的一条窄轨** —— 树**整个不在屏上**、窄轨贴着窗口的**左**边缘、**画布真的变宽了**（折叠的意义就是把宽度让给主区）、另一侧**不受影响**、再点一下原样回来；⑰ **报表列表能折叠成右边的一条窄轨** —— 列表不在屏上、窄轨贴着窗口的**右**边缘、画布变宽、树不受影响；⑱ **两侧都收起来时画布占满除两条窄轨以外的全部宽度**（留着空白就是有一侧压根没被收走）；⑲ **两块面板的折叠按钮各有各的无障碍文案**（共用一句的话，读屏念「折叠树面板」，用户照着提示去点，收起来的却是**另一块** —— 提示反而在误导人） |

### 2.8 非测试支撑文件

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

### 4.5 指针形状探针：读得到光标，但读到的可能不是自己的光标

「鼠标停在分隔条上指针还是箭头」这类缺陷，界面截图里**根本看不出来**（光标不进 `CopyFromScreen`），只能读系统光标句柄：

```powershell
$ci = New-Object CURSORINFO; $ci.cbSize = [Marshal]::SizeOf($ci)
[Cur]::GetCursorInfo([ref]$ci)          # $ci.hCursor 就是当前光标句柄
[Cur]::LoadCursor([IntPtr]::Zero, 32644) # 32644 = IDC_SIZEWE（左右拉伸 ↔）
# 两个句柄相等 → 当前就是「左右拉伸」指针
```

坑有三层，前两层是**探针骗人**，第三层是**环境骗人**，症状全都长得像「修复没生效」：

1. **AWT 自建的光标是独立 native handle**，与系统 `IDC_*` 共享句柄**不是同一个**（实测 AWT 默认箭头 = `65561`，`LoadCursor(NULL, IDC_ARROW)` 匹配不上）。于是「匹配不上」只说明「这是 AWT 造的」，**不能**推出「不是拉伸箭头」。真要判形状，得先看 AWT 造出来的是哪一个。
2. **必须先把鼠标「抖一下」**。`SetCursorPos` 到**当前所在坐标**时不产生任何事件，读到的是上一次残留的光标 —— 表现为「同一个点两次读数不一样」。探针要先挪开 60px 再移回来。
3. **必须同时报告光标下的窗口**（`WindowFromPoint` + 类名 + pid）。桌面不是只跑着被测程序：本次先后撞上「设置」应用窗口、系统的「你要如何打开这个文件?」模态框（带 `Shell_LightDismissOverlay` 透明浮层）、以及被隐藏的 app 窗口 —— 三者都让**鼠标事件根本没进到被测窗口**，探针忠实地报告了「别人的光标」。症状：同一支脚本，改窗口位置前测得到 `IDC_SIZEWE`，改完之后就再也测不到了。

> 结论：「探针测不到」**不等于**「功能没生效」。判据只能是**先把探针自己的坐标系与遮挡关系钉死**，再让代码去迎合探针。

---

## 5. 跨模块缺陷留档

下列缺陷的修复落在 `:engine` / `dialect-*` / `:shared` / `:desktopApp`，**代码注释在那里**；本表只留一句索引，避免以后重复踩。

> 后三行是**一次真机走查**（本机 1536×864 @125% 缩放）里抓到的 ——
> 自动化测试全绿、界面却**点不动 / 看不见**的那一类。
> 共同教训：**「有控件」不等于「用户够得着」**，
> 断言要落在「**位置 / 尺寸**」上，而不是「节点存在」。

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
| 跨库导出**必然失败** | `TablePreviewTab.schema` 装的其实是**库名**，而导出请求用的是「树里第一个展开的库」——两者不是同一个；再加上预填 SQL 是**裸表名**（按会话默认库解析），于是必然报 `Table '<别的库>.<表>' doesn't exist` | `ExportSqlBuildingTest` / `ExportCatalogTargetingTest` |
| 小窗口下向导第 1 步走不完 | **两个根因**：按钮随内容一起滚走（整个向导列挂 `verticalScroll`）+ 默认窗口 720dp 比本机工作区（659dp）高 ⇒ 底边**在屏幕外**。只修布局那个，大窗口上好了、小屏照旧点不到 | `WizardSmallWindowTest` |
| SQL 结果表数据行被压没 | 结果区按比例分高度（`maxHeight * 0.4f`）而**没有下限** ⇒ 小窗口下数据区 0 高度，标题却写着「查询结果 · 2 行」 | `SqlResultMinHeightTest` |

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
| **「请求式」的滚动 API 与数据变更同时发生时是竞态** | `LazyListState.requestScrollToItem` 记下的是「下一次 remeasure 时生效、只生效一次」的请求，而 remeasure 常常**同时**要应用数据变更（行数变了）—— 那一刻位置按 **key** 维持，新行落在视野之外，请求有没有抢赢取决于先后。症状是**单跑全过、全量跑偶发挂**（负载改了时序）。改用**挂起式** `scrollToItem`（在滚动块里同步做完 remeasure）才稳 |
| **「前置条件不成立」的断言是**假绿** —— 它测的不是那件事** | 「工作区不能出现在『可删』清单里」这条断言，配套的源叫 `orders_csv` 时**恒真**：登记表里压根没有 `main`，把实现里的「排除工作区」去掉，断言照样过 —— 变异验证里它是唯一一条 **MISSED**。改成**特意登记一个名字就叫 `main` 的数据源**（前置条件真的成立）之后，同一处变异立刻被抓。**断言要先问：它成立的那条路径，缺陷版本里会不会也成立？** |

---

## 7. 相关文档

| 文档 | 内容 |
|---|---|
| [`FEATURES.md`](./FEATURES.md) | 能力总览（三栏：已实现 / 引擎有但前端未接线 / 完全没有）与路线建议 |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | 分层、依赖方向、关键设计决策索引 |
| [`README.md`](./README.md) | 运行 / 打包 / 引擎端点属性 |
| [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | `DataTable` / `CodeEditor` / `ConnectionManagerScreen` 的实现细节 |
