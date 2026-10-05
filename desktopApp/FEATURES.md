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
| **连接** | 5 个方言：MySQL / PostgreSQL / H2 / DuckDB / SQLite | `dialect-*` SPI 插件 |
| | 4 步引导 + 快速连接 | `ConnectionManagerScreen` |
| | 测试连接 / 连接 / 断开（`SYSTEM.TEST_CONNECTION` / `SYSTEM.DISCONNECT`） | `ConnectionSession` |
| | 保存 / 编辑 / 删除连接 | `ConnectionSession` |
| | 连接总览（名称 / 方言 / 主机 / 端口 / 用户 / 状态） | `ConnectionManagerScreen` |
| | 凭据落盘 `~/.config/sundays/connection.json`（v1→v2 迁移，文件权限 0600） | `ConnectionStorage` |
| | **多 sheet**：一个连接一个 sheet，各自持有独立浏览状态 | `SheetDescriptor` |
| **浏览** | 库列表（`SCHEMA.LIST`） | `SchemaTreePanel` |
| | 表列表懒加载（展开时才 `TABLE.LIST`） | `loadTables` |
| | 双击 / 单击打开预览标签页，同表去重 | `openTab` |
| | 引擎侧真分页（每页 10/20/50/100/200/300/500） | `DATA.LIST` + `DataTable` |
| | 数据表格：虚拟滚动、列宽对齐、单元格可选中、右键菜单 | `shared/table/DataTable.kt` |
| | 选中行详情面板（宽度可调） | `DataTable.detailPanel` |
| | 树面板宽度**可拖拽**（双击复位，范围钳位） | `shared/ui/DragHandle.kt` |
| **SQL 工作台** | 多 sheet，各自独立文本 / 光标 / 滚动 / 结果 | `SqlSheet` |
| | 语法高亮，**随连接方言切换词表** | `SqlDialectProfile` → `CodeEditor` |
| | 补全：关键字 / 类型 / 内置函数 | `CodeLanguage.completionCandidates` |
| | 补全：**库 / 表 / 字段**（schema 感知，零额外请求） | `sqlSchemaCompletions` |
| | 补全：1 字符即触发，**上下文候选优先于关键字** | `CompletionTest` |
| | 补全弹层按内容自适应宽度，靠右时**向左翻转** | `CompletionPopup` |
| | SQL 格式化 | `Formatter` |
| | 执行（流式行帧）+ 结果表 + 结果分页 | `SQL.EXECUTE` |
| | **停止执行**（`SYSTEM.CANCEL` → `Statement.cancel()`） | `cancelSql` |
| | 结果集**封顶 5000 行** + 显式截断提示 | `SQL_RESULT_MAX_ROWS` |
| | 非 SELECT 显示「已影响 N 行」 | `affectedRows` |
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
| **工程** | Windows / macOS / Linux 原生安装包（`.msi` / `.dmg` / `.deb`） | `compose.desktop` |
| | 引擎可同进程直连，也可 gRPC 跨进程 | `EngineClient` 双实现 |

### 3.2 引擎已实现，**前端未接线**（不需要动引擎，做 UI 即可）

这一栏是最高性价比的待办 —— 底层已就绪，只差界面。

| 引擎路由 | 能力 | 前端缺什么 |
|---|---|---|
| `TABLE.CREATE` / `UPDATE` / `DELETE` | 建表 / 改表 / 删表 | **DDL 编辑器**：字段 / 索引 / 约束的可视化编辑 + DDL 预览 |
| `TABLE.GET_DDL` | 取任意对象的 DDL 文本 | 对象详情面板里一个「查看 DDL」 |
| `TABLE.TRUNCATE` | 清空表 | 树节点右键菜单项 |
| `TABLE.RENAME` | 重命名表 | 同上 |
| `VIEW` / `INDEX` / `TRIGGER` / `FOREIGN_KEY` 五个 Category | 视图 / 索引 / 触发器 / 外键的增删改查 | 树里对应的节点分组（现在只有「库 / 表」两级） |
| `FUNCTION` + `Action.CALL` | 存储过程 / 函数 | 对象浏览 + 调用面板 |
| `USER` + `Action.GRANTS` | 用户与权限 | 用户管理面板 |
| `IMPORT.RUN_IMPORT` | CSV / JSON Lines 导入（可取消、可容错） | 导入向导（选文件 → 映射列 → 目标表 → 预览） |
| `EXPORT.RUN_EXPORT` | 导出为 CSV / JSON / Excel / Markdown / Parquet | 导出对话框（`DataTable` 上右键即可） |
| `SYSTEM.BEGIN` / `COMMIT` / `ROLLBACK` / `SESSION_INFO` | 事务会话（固定连接 + autocommit=false） | 事务模式开关 + 显式提交 / 回滚按钮 |
| `SQL.EXECUTE` + `multi_statement = true` | 多语句脚本（`SqlScriptSplitter` 只切顶层 `;`） | 前端目前**硬编码 `multiStatement = false`**，改一行即可放开 |
| `SQL.EXPLAIN` | 执行计划 | ⚠️ proto 里标了 `defined but not routed in dispatcher` —— **引擎侧也还没实现** |

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

### 4.1 连接管理

- **两种入口**：4 步引导（基础信息 → 连接类型 → 凭据 → 确认）与快速连接（选方言直达凭据）。
- **凭据可随时测**：向导内「测试连接」与总览面板的「连接」都走 `SYSTEM.TEST_CONNECTION`，
  建 HikariCP 池 + JDBC `isValid(5)` 校验，失败时把引擎原文（常含 `Caused by`）回显。
- **落盘安全**：`connection.json` 文件权限 0600、目录 0700；v1 → v2 自动迁移。
- **多 sheet 是刻意设计**：一个连接一个 sheet，各自持有 `DatabaseBrowserState`。
  切 sheet 不丢浏览进度（库展开、已开标签页、SQL 草稿都在）。

### 4.2 数据库浏览

- **懒加载是硬性的**：启动只拉库列表，展开某个库才拉它的表 —— 一个连接下几十个库时，
  全量拉表会让首屏卡住。
- **分页在引擎侧**：`DATA.LIST` 带 `page` / `pageSize`，所以 `DataTable` 必须传
  `serverSidePaging = true`，否则它会再切一次本地页，第 2 页起直接变成空表。
- **窄窗口下底栏会降级**：树面板拖到最宽 + 窗口收到最小时，底栏只剩约 250dp。
  此时自动隐藏「首页 / 末页 / 共 N 条」，且**左组（每页选择器）承担全部压缩**，
  翻页按钮绝不会被挤没或压成竖排。

### 4.3 造数工作台（差异化能力）

DataGrip 和 Navicat 的「数据生成」都是**表单式**的（选表 → 配规则 → 生成）。
sundays 走的是另一条路：**Lua 脚本 + 引擎内沙箱**。

```lua
for i = 1, 3 do
  insert('gen_target', {id = i, label = 'row_'..i})
end
```

- 引擎在沙箱里注入 13 个宿主函数（`insert` / `lastId` / `random_*` …），
  补全时**带签名**列出，且**只在这个工作台出现** —— 塞进 Lua 语言词表会让普通 Lua 编辑器
  推荐不存在的函数。
- 执行走 `DATA.GENERATE` 流式回填进度（已插入行数 / 最后写入的表）。
- 支持切换 Lua 版本。

**为什么值得保留**：造数往往要表达「外键顺序」「幂等重跑」「按业务规则派生字段」，
表单式工具表达不了这些，而脚本天然可以。

### 4.4 SQL 工作台

- **多 sheet 优先于多标签**：一个连接下开多个 SQL 草稿互不干扰，各自保留文本 / 光标 / 滚动 / 结果。
- **补全是 schema 感知的**，但**零额外往返**：库来自连接时的 `SCHEMA.LIST`，
  表来自展开树时的 `TABLE.LIST`，字段来自**已打开过预览的表**。
  字段只覆盖「访问过的表」是刻意的 —— 全库全表 = 每张表一次 `TABLE.COLUMN_LIST`，
  200 张表就是 200 次串行往返，而用户敲 `sel` 时用不到其中 99%。
- **执行可中断**：长查询点「停止」走 `SYSTEM.CANCEL`。
  必须走引擎而不是取消协程 —— 跑在 IO 线程上的 `rs.next()` 即使协程被取消也继续阻塞，
  只有 `Statement.cancel()` 能停掉数据库侧工作。

---

## 5. 差距与建议路线

按「投入产出比」排序。**前三项都在 §3.2 —— 引擎已经做好了**。

| 优先级 | 事项 | 依据 |
|---|---|---|
| **P0** | **数据编辑**（单元格增删改 + 批量） | 现在 `DataTable` 只读，这是「能看」与「能用」的分界线。引擎侧可用 `SQL.EXECUTE` 走 `UPDATE`/`DELETE` 实现，不需要新路由 |
| **P0** | **导入 / 导出 UI** | 引擎 `IMPORT.RUN_IMPORT` / `EXPORT.RUN_EXPORT` 完整可用（多格式、可取消、可容错），只差向导与对话框 |
| **P0** | **对象浏览**（视图 / 索引 / 触发器 / 外键 / 过程 / 函数） | 五个 Category 引擎侧已通，树里加节点分组即可 |
| **P1** | **DDL 编辑 + 查看 DDL** | `TABLE.CREATE/UPDATE/DELETE/GET_DDL` 已通；可视化编辑器是 DataGrip / Navicat 的标配 |
| **P1** | **放开多语句** | 前端硬编码 `multiStatement = false`，改一行 |
| **P1** | **事务控制 UI** | `BEGIN/COMMIT/ROLLBACK` 已通，只差开关与按钮 |
| **P1** | **过滤 / 排序 / 表内搜索** | 补全浏览体验的最小闭环 |
| **P2** | **执行计划** | 需引擎侧补 `EXPLAIN` 路由（proto 已定义但未接） |
| **P2** | **实时错误检测 + 快速修复** | DataGrip 的核心竞争力，需要 SQL 解析器 |
| **P2** | **重构（重命名并同步引用）** | 同上 |
| **P2** | **只读模式 + 危险操作预警** | 成本低、风险收益比高 |
| **P3** | **ER 图 / 数据建模 / BI** | 工作量大，建议等 P0~P2 稳了再做 |
| **P3** | **更多方言** | 架构上零成本（插件 SPI），纯工作量 |
| **P3** | **AI 助手** | 需要先有可靠的 schema 上下文能力 |

---

## 6. 相关文档

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | 前端架构：状态机、布局、契约、为什么这么写 |
| [`../engine/README.md`](../engine/README.md) | 引擎能力全集（含尚未被 UI 使用的部分） |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎架构：路由、方言 SPI、连接池、事务 |
| [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md) | 共享 UI 层：`CodeEditor` / `DataTable` / 主题 |
| [`../README.md`](../README.md) | 仓库总览、构建运行、架构升级历史 |
