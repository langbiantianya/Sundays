# sundays — 功能文档

> 本文是**面向功能**的文档，回答「这个工具现在能干什么、不能干什么、与 DataGrip / Navicat 差在哪」。
> 架构与实现决策见 [`ARCHITECTURE.md`](./ARCHITECTURE.md)；引擎侧能力见 [`../engine/README.md`](../engine/README.md)。
>
> **本文的所有结论都来自代码**（`desktopApp` 3 个源文件 + `shared` 的 `ui/` `table/` `editor/` `connection/` `settings/`），
> 不是愿景。哪些还没做、哪些是引擎有但前端没接，都逐条标出。

---

## 1. 产品定位

**sundays 是一个跨平台的桌面数据库客户端**：Kotlin Multiplatform + Compose Multiplatform 编写，
macOS / Linux / Windows 三端共享同一套 Compose Desktop Skia 渲染；数据库算力由同仓的
`:engine` 提供，界面通过 `EngineClient` 接口调用（可同进程直连，也可走 gRPC 跨进程）。

**当前阶段：能连、能看、能查、能造数。** 离 DataGrip / Navicat 那类「完整数据库管理工具」
还有明确差距，本文第 5 节逐项列出，并把「引擎已实现但前端未接线」的部分单独标出 ——
那部分不需要动引擎，只需要做 UI。

与两者的定位差异，一句话版本：

| | DataGrip | Navicat Premium | sundays |
|---|---|---|---|
| 形态 | IDE（IntelliJ 平台） | 原生桌面应用 | 原生桌面应用（Skia，非原生控件） |
| 语言栈 | JVM（Kotlin/Java + IntelliJ 平台） | C++/Qt | **Kotlin 全栈**（界面 + 引擎 + 协议） |
| 方言覆盖 | 50+（含 NoSQL / 云厂商） | 9（MySQL/PG/SQLServer/Oracle/SQLite/MariaDB/MongoDB/Redis/Snowflake） | **5**（MySQL / PostgreSQL / H2 / DuckDB / SQLite） |
| 界面渲染 | Swing（自绘 LAF） | Qt | **Compose Multiplatform / Skia** |
| 差异化重点 | 代码智能（补全 / 检查 / 重构） | 数据建模 + BI + 迁移 | **编辑器的编辑体验** + **造数工作台**（见 §4.3） |

---

## 2. 界面总览

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│  连接管理        │        数据库浏览                    [⚙ 设置]  [☀/🌙 主题] │
├──────────────────┬───────────────────────────────────────────────────────────┤
│                  │  [DemoH2 ×] [+ 新建连接]                                    │  ← sheet 标签条
│  连接列表        ├───────────────────────────────────────────────────────────┤
│  ● Alpha   已连  │  [表预览] [▶ SQL 工作台] [造数工作台]        [重连] [断开]   │  ← 工具栏
│  ● Beta    未连  ├──────────────┬────────────────────────────────────────────┤
│                  │  数据库 / 表  │  [ users ×] [ orders ×]                      │  ← 预览标签条
│  ┌────────────┐  │  ▾ PUBLIC    ├────────────────────────────────────────────┤
│  │ 连接总览   │  │    · users   │  共 250 行 · 第 1 页 · 每页 100            │
│  │ 名称/方言  │  │    · orders  ├────────────────────────────────────────────┤
│  │ 主机/端口  │  │  ▸ OTHER_DB  │  ID │ USERNAME │ EMAIL                      │
│  │ 用户/密码  │  │              │   1 │ user_1  │ u1@example.com            │
│  │ [测试][连接]│  │         ┃   │   2 │ user_2  │ u2@example.com            │
│  └────────────┘  │    可拖拽   │                                            │
│                  │         ┃   │  （右侧 35% 为选中行详情面板）               │
│                  │              ├────────────────────────────────────────────┤
│                  │              │  堆 128M / 512M ▓▓▓░░░░  点击展开详情       │  ← 状态栏
└──────────────────┴──────────────┴────────────────────────────────────────────┘
```

---

## 3. 能力总览（三栏）

这一栏是本文最重要的地方。**很多能力引擎已经实现了，只是前端没接** —— 那一栏不需要动引擎。

### 3.1 已实现（界面可用）

| 领域 | 能力 | 落点 |
|---|---|---|
| **入口** | **启动首屏**：进应用先选一个模式（**连接管理** / **数据分析**）—— 两种活儿是两套会话、两种心智模型，「选模式」在进应用时问一次 | `StartScreen`（`:shared` 的 `navigation/`） |
| **连接** | 5 个方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite | `dialect-*` SPI 插件 |
| | 4 步引导 + 快速连接 | `ConnectionManagerScreen` |
| | **自定义 JDBC URL 连接方式**（每个方言都能选；只给一个 URL 输入框，不给主机/端口/库名 —— 避免两处真相源打架） | `ConnectionType.CUSTOM_URL` → `ConnectionManagerScreen` |
| | 认证方式（账密 / Kerberos / 无认证）与方言特有参数（Kerberos 主体、ZooKeeper 发现串…）随连接一起存 | `ConnectionConfig.authMethod` / `.properties` |
| | **凭据页由方言自描述驱动**：字段、顺序、标签、占位符、认证方式下拉全由方言声明；**新增一个方言的表单形状不需要改前端** | `DatabaseDialect.connectionForm` → `SYSTEM.LIST_DRIVERS` → `DialectFormRegistry` → `ConnectionFormFields` |
| | **文件当数据源**（DuckDB）：CSV / JSONL / Parquet 直开文件，Excel 经 POI 预转换；库名沿用文件名，表名取自 sheet 名 | `dialect-duckdb` |
| | 测试连接 / 连接 / 断开（`SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT`） | `ConnectionSession` |
| | 保存 / 编辑 / 删除连接 | `ConnectionSession` |
| | 连接总览（名称 / 方言 / 主机 / 端口 / 用户 / 状态） | `ConnectionManagerScreen` |
| | 凭据落盘 `~/.config/sundays/connection.json`（**v1→v2→v3** 迁移，文件权限 0600） | `ConnectionStorage` |
| | **多 sheet**：一个连接一个 sheet，各自持有独立浏览状态 | `SheetDescriptor` |
| **浏览** | 库列表（`SCHEMA.LIST`） | `SchemaTreePanel` |
| | 表列表懒加载（展开时才 `TABLE.LIST`） | `loadTables` |
| | **对象浏览**：库级「视图 / 触发器 / 过程·函数」+ 表级「索引 / 外键」 | `loadDatabaseObjects` / `loadTableObjects` |
| | **文件型数据源**（DuckDB）：CSV / JSON Lines / Parquet 直开，Excel 预转换 | `dialect-duckdb` 的 `buildJdbcUrl` |
| | 双击 / 单击打开预览标签页，同表去重 | `openTab` |
| | 引擎侧真分页（每页 10/20/50/100/200/300/500） | `DATA.LIST` + `DataTable` |
| | **过滤 / 排序 / 表内搜索**（全部下推给引擎，零本地筛） | `setTabFilter` / `setTabOrderBy` / `setTabSearch` |
| | 数据表格：虚拟滚动、列宽对齐、单元格可选中、右键菜单 | `shared/table/DataTable.kt` |
| | 选中行详情面板（宽度可调） | `DataTable.detailPanel` |
| | 树面板宽度**可拖拽**（双击复位，范围钳位），悬停显示**左右拉伸指针** | `shared/ui/DragHandle.kt` |
| | **对象树右键菜单**：复制名称 / 复制引用名（按方言限定）/ 复制建表 DDL | `SqlIdentifier` + `TABLE.GET_DDL` |
| **SQL 工作台** | 多 sheet，各自独立文本 / 光标 / 滚动 / 结果 | `SqlSheet` |
| | 语法高亮，**随连接方言切换词表** | `SqlDialectProfile` → `CodeEditor` |
| | SQL 方言档位**注册表化**：语言与 formatter **成对**注册，杜绝「只高亮不能格式化」的中间态 | `SqlDialectProfileRegistry` |
| | 补全：关键字 / 类型 / 内置函数 | `CodeLanguage.completionCandidates` |
| | 补全：**库 / 表 / 字段**（schema 感知，零额外请求） | `sqlSchemaCompletions` |
| | 补全：1 字符即触发，**上下文候选优先于关键字** | `CompletionTest` |
| | 补全弹层按内容自适应宽度，靠右时**向左翻转** | `CompletionPopup` |
| | SQL 格式化 | `Formatter` |
| | 执行（流式行帧）+ 结果表 + 结果分页 | `SQL.EXECUTE` |
| | **多语句脚本**（`CREATE…; INSERT…; INSERT…` 一次执行） | `SqlSheet.multiStatement` |
| | **事务**：`BEGIN` / `提交` / `回滚`，session id 透传 | `beginTransaction` / `commit` / `rollback` |
| | **只读模式**（发出去之前就拦，引擎那边什么都没发生） | `rejectIfReadOnly` |
| | **危险写操作确认**（`DROP` / 全表 `UPDATE` 等） | `DangerousSql` |
| | **停止执行**（`SYSTEM.CANCEL` → `Statement.cancel()`） | `cancelSql` |
| | 结果集**封顶 5000 行** + 显式截断提示 | `SQL_RESULT_MAX_ROWS` |
| | 非 SELECT 显示「已影响 N 行」 | `affectedRows` |
| **数据流转** | **导出**对话框（CSV / JSON / Excel / Markdown / Parquet） | `exportQuery` |
| **造数工作台** | 多脚本，各自独立文本与统计 | `GenerateScript` |
| | Lua 编辑 + 语法高亮 | `CodeEditor` |
| | 补全：关键字 + **引擎注入的 13 个沙箱宿主函数**（带签名） | `extraCompletions` |
| | Lua 版本切换 | `LUA_VERSIONS` |
| | 执行 + 流式进度回填（已插入行数 / 最后写入表） | `DATA.GENERATE` |
| **错误与状态** | 连接失败原因**常驻横幅** + 一键重试 | `ConnectionFailureBanner` |
| | 引擎报错顶对齐 + 可纵向滚动（关键信息在末尾 `Caused by`） | `EmptyHint` |
| | 底部状态栏：JVM 堆占用 + 点击展开详情面板 | `EngineMemoryStatusBar` |
| **外观** | 5 套配色（现代 3 / Win2000 直角 / WinXP Luna 圆角） | `SundaysPalette` |
| | 明暗主题（跟随系统 / 手动）+ 紧凑模式（缩放 density） | `ThemeMode` / `CompactMode` |
| **语言** | 6 种语言：简体中文 / 繁體中文 / English / Русский / 日本語 / Deutsch | `:i18n` + `str()` / `plural()` |
| | 首启在**引导页**按系统语言预选（不支持则落中文），引导页与设置页均可改 | `LanguagePicker` |
| | 引擎报错跟随界面语言（语言随请求过 proto 传到引擎进程） | `RequestOptions.language` |
| **工程** | Windows / macOS / Linux 原生安装包（`.msi` / `.dmg` / `.deb`） | `compose.desktop` |
| | 引擎可同进程直连，也可 gRPC 跨进程 | `EngineClient` 双实现 |
| | 前端方言元数据**收进一张表**（默认连接类型 / 端口 / 可选连接类型 / 引擎注册名 / URL 前缀 / 图标 / 说明文案 / 标识符引用符 / SQL 档位）—— **加一个方言 = 加一条元数据**，不再改 N 处分支 | `DialectMetadata` |
| **数据分析模式** | 独立于「连接管理 → 数据库浏览」的**顶层模式**：远端数据源**恒为只读**（引擎 L4 闸门 + 方言级只读），计算引擎是本地 **DuckDB 工作区**（可写） | `AnalysisScreen` / `AnalysisSession` |
| | **工作区树**：**根 = 工作区的各个 schema**（`main` 是工作区自己的表，每个**数据源**一个）；**与浏览模式的对象树共用同一个组件**（`shared/tree/ObjectTree.kt`），形状统一为「库 → 表 → **字段**」—— 展开一张表能看到它的字段（名字 / 类型 / `PK` / `非空`）；点表 → 把表名加进编辑器，点字段 → 把「表.字段」加进编辑器 | `shared/tree/ObjectTree.kt` |
| | **「+」添加数据源**（分析模式**专属**，不共用浏览模式的「添加连接」）：**远程数据库**（方言 / 主机 / 端口 / 库 / 用户 / 密码 → **用对应驱动预览**库与表 → **可多选可单选** → **DuckDB 官方扩展优先**，走不通退回 JDBC）与**文件 / 文件夹**（parquet / csv / Excel / jsonl → 直接用 DuckDB 的 `read_parquet` / `read_csv_auto` / `read_json_auto` / `read_xlsx` 建**视图**）。加完的源**直接在工作区 DuckDB 里**（登记表 `sundays.sources`），树里立刻多一根。路径可**调系统文件 / 文件夹选择器**挑（文本框仍在：网络路径与无头环境只能手输）；格式**能手选**（文件夹没有扩展名） | `AnalysisSourceDialog.kt` / `AnalysisSession` |
| | **拉取链路**：DuckDB **联邦直查**远端 JDBC 源 → 工作区表（**零 CSV 中转、零临时文件**），带行数进度与可取消 | `EngineAnalysisPuller` / `PullProgressIndicator` |
| | 拉下来的表**保留远端的类型**（`INT` 落下来还是整数，`SUM(id)` 直接能跑） | 引擎的 `JdbcScanTableFunction` |
| | 拉取可切到 DuckDB 的**原生扫描器**（可选加速，`SUNDAYS_DUCKDB_NATIVE_EXT=1` 才开）—— 走不通就**降级**回纯 Java 表函数，用户看到的仍是「拉取成功」 | 引擎的 `NativeExtensionScanner` |
| | **SQL 编辑器**（固定 DuckDB 方言）+ **只读结果表**（结果不是一张可以改的表） | `CodeEditorWithToolbar` / `DataTable` |
| | 点远端表 → 拉取；点工作区的表 → 把表名加进编辑器；点工作区的字段 → 把「表.字段」加进编辑器 | `AnalysisSession` |
| | **入口在连接管理的空闲面板**（顶栏的目标切换 chip 已去掉），分析工作台**没有返回按钮**（它是一块独立的工作区） | `ConnectionManagerScreen` / `AnalysisScreen` |

### 3.2 引擎已实现，**前端未接线**（不需要动引擎，做 UI 即可）

> ⚠️ 本表已剔除**后来补上**的几项（v2.16~v2.19 期间落地），它们现在是 §3.1 的内容：
> 多语句执行、事务控制、对象浏览（库级 + 表级）、过滤 / 排序 / 表内搜索、
> 只读模式 + 危险操作确认、导出 UI。执行证据见 [`TEST_CASES.md`](./TEST_CASES.md)。

| 引擎路由 | 能力 | 前端缺什么 |
|---|---|---|
| `TABLE.CREATE` / `UPDATE` / `DELETE` | 建表 / 改表 / 删表 | **DDL 编辑器**：字段 / 索引 / 约束的可视化编辑 + DDL 预览 |
| `TABLE.GET_DDL` | 取任意对象的 DDL 文本 | 对象详情面板里一个「查看 DDL」 |
| `TABLE.TRUNCATE` | 清空表 | 树节点右键菜单项 |
| `TABLE.RENAME` | 重命名表 | 同上 |
| `VIEW` / `INDEX` / `TRIGGER` / `FOREIGN_KEY` 的 **增删改** | 对象浏览目前**只读**（能列出来，不能建 / 改 / 删） | 树节点右键菜单 + 对象详情面板 |
| `FUNCTION` + `Action.CALL` | **调用**存储过程 / 函数 | 调用面板（列出来的那部分已做） |
| `USER` + `Action.GRANTS` | 用户与权限 | 用户管理面板 |
| `IMPORT.RUN_IMPORT` | CSV / JSON Lines 导入（可取消、可容错） | 导入向导（选文件 → 映射列 → 目标表 → 预览） |
| `SYSTEM.SESSION_INFO` | 事务会话信息 | 把当前 session 的隔离级别 / 是否自动提交显示出来 |
| `SQL.EXPLAIN` | 执行计划 | ⚠️ proto 里标了 `defined but not routed in dispatcher` —— **引擎侧也还没实现** |

> **对象浏览的一处方言限制**：`SQLiteDialect.listForeignKeys` 走
> `PRAGMA foreign_key_list`，而该 PRAGMA **不暴露约束名**，所以界面上显示的是方言拼出来的
> `fk_<表>_<序号>`，而不是建表时写的名字。要真名得解析 `sqlite_master` 的建表 SQL。
> H2 / PostgreSQL / MySQL 走 `INFORMATION_SCHEMA`，名字是真的。

### 3.3 完全没有（需要从零做）

按「对标 DataGrip / Navicat」的优先级排列。

**A. 数据库管理工具的基本盘**

> ⚠️ **「数据编辑」需要展开说明**：整条链路已实现（`DataTable` 的内联编辑器、`CellEdit` 入参、
> `DatabaseBrowserState.updateCell` 走 `DATA.UPDATE`、成功后回写本地行），**但浏览屏默认仍以
> 只读方式渲染**。原因只有一个：`DATA.LIST` 的响应**不回列定义**，而
> `ColumnDef.is_primary_key` 只出现在 `TABLE.CREATE` / `TABLE.UPDATE` 的请求侧 ——
> 前端无从知道哪一列是主键。
>
> **为什么不用「表里有 id 列」来凑**：那不等于「id 是主键」。一张表的 `id` 完全可能只是个
> 普通可重复列，那时的 `WHERE id = ?` 会**同时改掉多行** —— 静默的数据损坏比「不能编辑」
> 严重得多。所以 `isTableEditable` 明确返回 `false`。
>
> **解锁条件**：引擎在 `DataListPagedResponse` 里多回一个 `primary_key` 字段。之后只需把
> `TablePreviewTab.primaryKeyColumn` 填上，`DataTable` 与 `updateCell` **都不必改**。
> 它排在 P0 首位正因为差的是一行协议、不是功能。

| 能力 | DataGrip | Navicat | 说明 |
|---|---|---|---|
| **数据编辑**（单元格增删改 + 批量） | ✅ | ⚠️ | **基础设施已就绪但默认关闭** —— 见下方说明 |
| **对象浏览**（视图 / 索引 / 触发器 / 过程 / 函数 / 用户） | ✅ | ✅ | 树只有「库 / 表」两级 |
| **DDL 编辑**（可视化建表改表） | ✅ | ✅ | 见 §3.2 |
| **只读模式** | ✅ | — | 防误操作 |
| **危险操作预警**（`DROP TABLE` / 全表 `UPDATE` 前确认） | ✅ | — | |
| **执行计划**（表 / 图两种视图） | ✅ | ✅ | 引擎侧也缺 |
| **存储过程调试器**（断点 / 单步 / 变量 / 调用栈） | — | ✅ | 引擎有 `Action.DEBUG`，未实现 |

**B. SQL 编辑器的代码智能（DataGrip 的主战场）**

| 能力 | DataGrip | sundays 现状 |
|---|---|---|
| 补全 | schema 感知（外键推 JOIN、INSERT 自动填字段、缩写展开） | ✅ 库/表/字段 + 关键字，**不推 JOIN、不展开缩写** |
| 实时错误检测 | ✅ 检查 + 快速修复 | ❌ |
| 死代码检测 | ✅ | ❌ |
| 重构（重命名表 / 列并同步所有引用） | ✅ | ❌ |
| 活动模板（Live Templates） | ✅ | ❌ |
| 代码片段（Code Snippet） | ✅ | ❌ |
| DDL 生成 | ✅ | ❌ |
| Text-to-SQL / AI 助手 | ✅ | ❌ |

**C. 数据流转**

| 能力 | DataGrip | Navicat | sundays 现状 |
|---|---|---|---|
| 导入 | ✅ | ✅ | 引擎有，UI 无 |
| 导出 | ✅ 多格式 + 自定义格式 | ✅ | 引擎有，UI 无 |
| 结果集对比（diff） | ✅ | — | ❌ |
| 跨库复制数据 | ✅ | ✅ | ❌ |
| 结构 / 数据同步 | — | ✅ | ❌ |
| 数据字典 | — | ✅ | ❌ |
| 数据生成 | — | ✅ | ✅ **造数工作台**（见 §4.3） |
| 数据剖析（profiling） | ✅ | ✅ | ❌ |
| 备份 / 还原 | — | ✅ | ❌ |
| 调度自动化 | — | ✅ | ❌ |

**D. 浏览与分析体验**

| 能力 | DataGrip | Navicat | sundays 现状 |
|---|---|---|---|
| 表内文本搜索（`Cmd+F`） | ✅ | ✅ | ❌ |
| 过滤 / 排序面板 | ✅ | ✅ | ❌（只有分页） |
| 按外键跳转（Related Rows） | ✅ | — | ❌ |
| 聚合分析（count / sum / avg）+ 图表 | ✅ | ✅ BI | ❌ |
| ER 图 / 数据建模 | — | ✅ Model | ❌ |
| 对象过滤 / 收藏夹 | — | ✅ | ❌ |
| 虚拟分组 | — | ✅ | ❌ |
| 查询历史 / 本地历史 | ✅ | ✅ | ❌ |
| 版本控制集成 | ✅ | — | ❌ |
| 键位自定义 / 插件 | ✅ | — | ❌ |

**E. 方言覆盖**

DataGrip 50+（含 Oracle / SQL Server / MongoDB / Redis / Snowflake / ClickHouse / BigQuery…），
Navicat 9。sundays 目前 **5** 个。引擎侧是插件化 SPI（`DialectLoader` + `ServiceLoader`），
加一个方言 = 新增一个 `dialect-*` 模块，**不需要改引擎与 UI** —— 这是架构上已经铺好的扩展点。

---

## 4. 逐模块说明

各能力的用法、边界与踩过的坑都在对应源码的 KDoc 里，这里只给索引。

| 模块 | 能力 | 落点 |
|---|---|---|
| **连接管理** | 4 步引导 + 快速连接两种入口；凭据可随时测（`SYSTEM.TEST_CONNECTION` + JDBC `isValid(5)`）；`connection.json` 权限 0600、目录 0700、v1→v2 自动迁移；一个连接一个 sheet，切 sheet 不丢浏览进度 | [`ConnectionSession.kt`](./src/main/kotlin/com/langbiantianya/sundays/ConnectionSession.kt) · [`ConnectionManagerScreen`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionManagerScreen.kt) |
| **文件型数据源** | `database` 填 `.csv` / `.jsonl` / `.parquet` / `.xlsx` 路径即可当数据源。DuckDB 直接开前三种，视图名是去扩展名的文件名；`.xlsx` 由 POI 预转换，表名取自 sheet 名、首行表头用作列名。⚠️ 预转换只发生在 `dialect.buildJdbcUrl(...)` 内，预先算好 `jdbcUrl` 的调用方会绕过它 | [`ExcelToDuckDbCache.kt`](../dialect-duckdb/src/main/kotlin/com/langbiantianya/dialect/ExcelToDuckDbCache.kt) |
| **数据库浏览** | 库表树懒加载（展开才拉表）；分页在引擎侧，`DataTable` 必须传 `serverSidePaging = true`；窄窗口下底栏自动降级，翻页按钮绝不被挤没 | [`SchemaTree.kt`](./src/main/kotlin/com/langbiantianya/sundays/SchemaTree.kt) · [`TablePreview.kt`](./src/main/kotlin/com/langbiantianya/sundays/TablePreview.kt) |
| **表结构管理** | 右键表节点可编辑结构、右键库节点可新增表；主键 / 可空 / 自增 / 默认值 / 外键（含 `ON DELETE` / `ON UPDATE`）逐字段编辑，类型下拉**跟着方言走**。新增与编辑复用同一弹窗，批量 DDL 走事务会话、失败自动回滚并报引擎原文 | [`TableStructureDialog.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/table/TableStructureDialog.kt) · [`TableStructureDiff.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/table/TableStructureDiff.kt) |
| **造数工作台（差异化）** | 走 **Lua 脚本 + 引擎内沙箱**而非表单式生成 —— 表单表达不了「外键顺序」「幂等重跑」「按业务规则派生字段」。沙箱注入 13 个宿主函数，补全带签名列出且**只在这个工作台出现** | [`generateGenerateHandler`](../engine/src/main/kotlin/com/langbiantianya/handlers/GenerateHandler.kt) |
| **SQL 工作台** | 多 sheet 优先于多标签；补全 schema 感知但**零额外往返**（库 ← 连接时的 `SCHEMA.LIST`，表 ← 展开树时的 `TABLE.LIST`，字段 ← 已打开过预览的表）；长查询可「停止」走 `SYSTEM.CANCEL` | [`main.kt`](./src/main/kotlin/com/langbiantianya/sundays/main.kt) · [`CodeEditor`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/editor/ui/CodeEditor.kt) |

> **为什么补全的字段只覆盖「访问过的表」**：全库全表意味着每张表一次
> `TABLE.COLUMN_LIST`，200 张表就是 200 次串行往返，而用户敲 `sel` 时用不到其中 99%。
> 零等待、零额外往返，用得越多越全。
>
> **为什么取消必须走引擎**：跑在 IO 线程上的 `rs.next()` 即使协程被取消也继续阻塞，
> 只有 `Statement.cancel()` 能真正停掉数据库侧的工作。

## 5. 差距与建议路线

按「投入产出比」排序。**前三项都在 §3.2 —— 引擎已经做好了**。

| 优先级 | 事项 | 依据 |
|---|---|---|
| **P0** | **数据编辑**（单元格增删改 + 批量） | 现在 `DataTable` 只读，这是「能看」与「能用」的分界线。引擎侧可用 `SQL.EXECUTE` 走 `UPDATE`/`DELETE` 实现，不需要新路由 |
| **P0** | **导入 UI** | 引擎 `IMPORT.RUN_IMPORT` 完整可用（可取消、可容错），只差向导 |
| **P1** | **DDL 编辑 + 查看 DDL** | `TABLE.CREATE/UPDATE/DELETE/GET_DDL` 已通；可视化编辑器是 DataGrip / Navicat 的标配 |
| **P1** | 对象浏览的**增删改** + 对象详情面板 | 现在只能「列出来」，不能建 / 改 / 删，也看不到 DDL |
| **P1** | **存储过程 / 函数调用** | `Action.CALL` 已通，调用面板没有 |
| **P2** | **执行计划** | 需引擎侧补 `EXPLAIN` 路由（proto 已定义但未接） |
| **P2** | **实时错误检测 + 快速修复** | DataGrip 的核心竞争力，需要 SQL 解析器 |
| **P2** | **重构（重命名并同步引用）** | 同上 |
| **P3** | **ER 图 / 数据建模 / BI** | 工作量大，建议等 P0~P2 稳了再做 |
| **P3** | **更多方言** | 架构上零成本（插件 SPI），纯工作量 |
| **P3** | **AI 助手** | 需要先有可靠的 schema 上下文能力 |

---

## 6. 相关文档

| 文档 | 内容 |
|---|---|
| [`TEST_CASES.md`](./TEST_CASES.md) | **功能测试用例**（H2 / SQLite 双方言 GUI 走查，含实际结果与首轮发现的缺陷） |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | 前端架构：状态机、布局、契约、为什么这么写 |
| [`../engine/README.md`](../engine/README.md) | 引擎能力全集（含尚未被 UI 使用的部分） |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎架构：路由、方言 SPI、连接池、事务 |
| [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | 共享 UI 层：`CodeEditor` / `DataTable` / 主题 |
| [`../docs/analysis-mode/README.md`](../docs/analysis-mode/README.md) | **数据分析模式**：独立只读分析模式、组件复用与通用化、DuckDB / Hive / Impala 引擎、Excel 报表模型、迭代拆分（附 [UI 设计图](../docs/analysis-mode/ui-mockup.html)） |
| [`../i18n/README.md`](../i18n/README.md) | 六语言文案：键枚举 / 译文表 / 简繁判定 / 复数规则 / 语言传递链路 |
| [`../README.md`](../README.md) | 仓库总览、构建运行、架构升级历史 |
