# sundays — 功能测试用例

> 本文是**面向功能的测试用例清单**：每条用例有编号、前置条件、步骤、预期结果与**实际结果**。
> 用例覆盖的是**已经落地**的能力（见 [`FEATURES.md` §3.1](./FEATURES.md)），
> 逐条在**真实 H2 与真实 SQLite** 上执行，界面用 `runComposeUiTest` 驱动真实渲染与点击。
>
> - 执行代码：[`FeatureWalkthroughTest.kt`](./src/test/kotlin/com/kxxnzstdsw/sundays/FeatureWalkthroughTest.kt)
> - 方言能力差异：[`WalkthroughTarget.kt`](./src/test/kotlin/com/kxxnzstdsw/sundays/WalkthroughTarget.kt)
> - 截图落盘：`desktopApp/build/gui-shots/`
>
> **方言层的冒烟另有两份**（不经界面，直接打引擎，验的是方言 / 引擎那一层）：
>
> | 文档 / 测试 | 覆盖 |
> |---|---|
> | [`DialectSmokeTest`](./src/test/kotlin/com/kxxnzstdsw/sundays/DialectSmokeTest.kt) | **五个方言**（H2 / SQLite / DuckDB / MySQL / PostgreSQL）× 7 项：连通 / 写读闭环 / 过滤排序搜索下推 / 对象浏览 / 取 DDL / 多语句 / 事务可见性 |
> | [`DuckDbFileSourceTest`](./src/test/kotlin/com/kxxnzstdsw/sundays/DuckDbFileSourceTest.kt) | **文件型数据源**：CSV / JSON Lines / Parquet / Excel |

---

## 0. 怎么执行

```powershell
.\gradlew.bat :desktopApp:test --tests "com.kxxnzstdsw.sundays.FeatureWalkthroughTest" --console=plain
```

**为什么是 `runComposeUiTest` 而不是手点真窗口**：本机合成鼠标输入（`SendInput` /
`mouse_event`）送不进 Compose Desktop 的 Skiko 窗口 —— 光标能移动、点击无响应。
`runComposeUiTest` 走的是**同一套**渲染与输入分发链路（真实布局、真实绘制、真实点击），
截图是货真价实的界面渲染结果。**与「启动程序」等价，只是驱动方式不同。**

**为什么两个方言都跑**：单跑一个方言，下面这些能力差异**一条都测不出来**：

| | H2 | SQLite |
|---|---|---|
| 视图 / 索引 / 外键 | 有 | 有 |
| 触发器 / 过程·函数 | 有 | **无**（`SQLiteDialect` 抛 `UnsupportedOperationException`） |
| 标识符大小写 | 全**大写** | 原样**小写** |
| 未提交数据的可见性 | 独立连接读不到 | 独立连接读不到，可能被 `SQLITE_BUSY` 锁住 |

**为什么断言主要打在状态机而不是像素**：无头 `captureToImage` 对走 `SelectionContainer`
的文本层漏绘，数据行的文字不会出现在 PNG 里。所以分工固定 ——
**状态机 + 独立 JDBC 连接负责「对不对」，截图只负责「长什么样」**。

**公共前置条件**（每个用例都重新建立，不跨用例共享）：

- 隔离 `user.home` 到临时目录 —— 否则 `ConnectionStorage` 会碰到真实用户的连接配置
- 造一份固定数据集：`users`(250 行) / `orders`(含外键 + 索引) / `gen_target` /
  索引 `idx_orders_status` / 视图 `v_active_users`
- 每个用例自己 `engine.close()` —— 它会连带关掉 `DialectLoader` 的 ClassLoader，
  漏了会让后一个用例的方言解析不到

---

## 1. 对象浏览

### TC-OB1 — 库级对象：视图 / 触发器 / 过程·函数

| | |
|---|---|
| **前置** | 连接已建立，库列表已加载 |
| **步骤** | ① 展开库节点<br>② 等待三类对象分组落定 |
| **预期** | ① **schema 名先被解析出来**（H2 → `PUBLIC`，SQLite → `main`）<br>② 支持的种类列出真实对象名<br>③ **不支持的种类要么报错、要么为空，但绝不能永远转圈，也绝不能把支持的种类一起弄没** |

**为什么判据是这样写的**

- 「先解析 schema」是硬前提：`VIEW.LIST` / `TRIGGER.LIST` / `FUNCTION.LIST` 的 `schema`
  要的是 schema 名而**不是库名**。拿库名去填会变成 `SET SCHEMA "<库名>"` → `Schema not found`，
  列表全空 —— 而请求**成功**，返回空列表，界面上看不出任何异常。
- 「不支持的一侧」只有跑 SQLite 才走得到：H2 三样都有，这条路径永远测不到。
- 等待条件必须判「每一类都落定」，不能判「loading 清空」：`loadDatabaseObjects` 会
  **先把三类占位全撤掉、再逐类置上**，中间有一个「谁都不在 loading」的窗口。

### TC-OB2 — 表级对象：索引 / 外键

| | |
|---|---|
| **前置** | 库已展开、表列表已加载 |
| **步骤** | ① 展开某张表的「索引 / 外键」节点<br>② 等待加载完成 |
| **预期** | 索引列表含 `idx_orders_status`，外键列表含 `fk_orders_user` |

**这两个挂表节点下而不是库节点下**：引擎侧 `IndexListRequest` / `ForeignKeyListRequest`
要 `table_name`，跟库级对象一起拉就是「每张表一次往返」，几十张表的库直接爆炸。

---

## 2. 过滤 / 排序 / 表内搜索

### TC-DQ1 / DQ2 / DQ3

| | |
|---|---|
| **前置** | `USERS` 预览已打开（250 行） |
| **步骤** | ① 填过滤 `id > 200` → 应用<br>② 清过滤，排序 `id DESC` → 应用<br>③ 清排序，搜索 `user_25`<br>④ 手工过滤与搜索同时存在 |
| **预期** | ① 总数 = 50（201..250），且**回到第 1 页**<br>② 首行 `id` = 250<br>③ 总数 = 11（`user_25` + `user_250..259`）<br>④ 两者是 **AND** 关系 |

**必须下推给引擎**（`DATA.LIST` 带 `where` / `order_by`），不能前端本地筛 ——
本地筛在服务端分页下只能看到当前页，**界面看着正常但结果是错的**。

**判据用引擎回的总数，不用「界面上出现了某一行」**：后者在本地筛与真下推两种实现下都可能成立。

---

## 3. 多语句 / 事务 / 只读

### TC-MS1 — 多语句执行

| | |
|---|---|
| **前置** | SQL 工作台 |
| **步骤** | 打开「多语句」，执行 `CREATE TABLE …; INSERT …; INSERT …` |
| **预期** | 无报错，且**探针表里恰好 2 行** |

**判据刻意用「建表 + 插数据」两条语句**：开多语句时两条都执行；只跑第一条的话表压根不存在。
只用单条 `CREATE` 区分不出这两种情况 —— **判据必须落在跨语句的副作用上**。

### TC-TX1 / TX2 / TX3 — 事务

| | |
|---|---|
| **前置** | SQL 工作台 |
| **步骤** | ① 点「事务」BEGIN<br>② 在事务里 `INSERT`<br>③ 用**另一条独立 JDBC 连接**查<br>④ 点「回滚」，再查<br>⑤ 再 BEGIN → INSERT → 「提交」，再查 |
| **预期** | ① `transactionSessionId` **非空**（拿不到等于事务没开）<br>②③ 独立连接**看不到**这行<br>④ 回滚后**仍看不到**<br>⑤ 提交后独立连接**看得到** |

**只断言「按钮能点」是不够的**：事务没开、连接没钉住，界面一样显示「已开启」。
**SQLite 另有一层**：文件库在他人持写事务时可能直接 `SQLITE_BUSY` ——
「被锁」与「读到旧值」一样都证明**未提交的数据对他人不可见**，语义等价。

### TC-RO1 / RO2 — 只读模式

| | |
|---|---|
| **前置** | 表预览已打开 |
| **步骤** | ① 关闭只读，试 `DROP TABLE`<br>② 打开只读，试 `DROP` / `UPDATE` / `SELECT 1`<br>③ **回头查库**确认表还在<br>④ 关掉只读 |
| **预期** | ① 不拦<br>② `DROP` / `UPDATE` 被拦，`SELECT` 放行<br>③ 表还在、行数不变 —— 证明**根本没发出去**，而不是发出去被引擎拒<br>④ 不再拦 |

---

## 4. 执行结果

> 执行日期：2026-10-06 · 一次执行 = H2 × 5 条 + SQLite × 5 条 = **10 条**

| 用例 | H2 | SQLite |
|---|---|---|
| TC-OB1 对象浏览（库级：视图 / 触发器 / 过程·函数） | ✅ | ✅ |
| TC-OB2 对象浏览（表级：索引 / 外键） | ✅ | ✅ |
| TC-DQ1 过滤（`id > 200` → 50 行） | ✅ | ✅ |
| TC-DQ2 排序（`id DESC` → 首行 250） | ✅ | ✅ |
| TC-DQ3 表内搜索（`user_25` → 2 行） | ✅ | ✅ |
| TC-MS1 多语句（`CREATE` + 2 × `INSERT`） | ✅ | ✅ |
| TC-TX1 事务 BEGIN（拿到 session id） | ✅ | ✅ |
| TC-TX2 ROLLBACK（外部连接看不到） | ✅ | ✅ |
| TC-TX3 COMMIT（外部连接看得到） | ✅ | ✅ |
| TC-RO1/RO2 只读模式（含「表还在」回查） | ✅ | ✅ |

**10 / 10 通过。** 首轮执行是 **4 / 10**，下面 4 条是真缺陷，全部已修。

### 4.1 首轮发现并修掉的缺陷

| # | 缺陷 | 影响面 | 根因 |
|---|---|---|---|
| 1 | **表内搜索完全不可用** —— 发出的 SQL 是 `WHERE LIKE '%词%'`，没有左操作数 | H2 / SQLite / PG **全部方言** | `SqlLiterals.likeContains` 只返回裸片段；`SqlLiteralsTest` 把这个片段当正确输出断言了下来，整条链**一次没跑过真库** |
| 2 | **表级对象（索引 / 外键）首次展开必然为空** | 全部方言 | schema 还没解析完就发了查询 → schema 传空串 → 请求**成功**、返回空列表、**不重试**。用户必须收起再展开一次 |
| 3 | **一类对象失败会连累其它类** —— SQLite 上触发器不支持，整个「对象」区被一行红字顶掉，**能正常列出的视图一起消失** | 有不支持项的方言（SQLite） | 渲染层「任意一类出错 → 整个对象区换成一行错误」 |
| 4 | 表内搜索无谓地**要求**转义正确却从不真正执行 | — | 与 #1 同源，已随 #1 修复 |

**#1 是最值得记的一条**：缺陷在「片段的**拼接**」上，而测试只盯「片段的**转义**」。
判据必须落在**发出去的那条语句**上 —— 这也是本次走查把它逼出来的唯一原因。

### 4.2 走查过程中修掉的**测试自身**缺陷

这四条都不是被测代码的问题，但每一条都让走查**误报**过一次：

| 判据缺陷 | 后果 |
|---|---|
| 等待条件用 `(库, 类型)` 而集合里存的是 `(slot, 类型)` | 条件恒真，白等 → 把「应用没写数据」误报成「界面没显示」 |
| `hasText("视图")` 用了默认 `substring = false`，而实际文本是 `"视图："` | 永远匹配不上，误报「界面没渲染」 |
| 等待条件只看 `loadingObjects` 清空 | `loadDatabaseObjects` 先撤占位再逐类置上，中间有窗口 → 在数据落地前就断言 |
| 用 `onNode(hasScrollAction())` 找树 | 整屏有多个可滚动节点，且**按序号取会随库名长度漂移**；改用 LazyColumn 专有的 `ScrollToIndex` 动作识别 |

另外两条**不属于代码缺陷、但值得记**的发现：

- **SQLite 拿不到外键的真名**。`SQLiteDialect.listForeignKeys` 走 `PRAGMA foreign_key_list`，
  而该 PRAGMA 不暴露约束名，方言只能拼一个 `fk_<表>_<序号>`。要真名得解析 `sqlite_master`
  的建表 SQL。用例因此对它只断言「列出来了」。
- **事务用例在 SQLite 上要容忍 `SQLITE_BUSY`**。文件库在他人持写事务时会直接锁住，
  「被锁」与「读到旧值」一样都证明未提交的数据对他人不可见，语义等价。

### 4.3 全量回归

全量：`shared 243 / 0 失败 / 1 跳过` + `desktopApp 227 / 0 失败`。

此前 desktopApp 长期有 **8 条**红测（`ConnectionManagerFlowTest` 3 + `DatabaseBrowserSheetsTest` 4 +
`ThemeToggleVisibilityTest` 1），shared 有 **3 条**。**11 条全部归零，根因只有一个** ——
`ConnectionStorage.savePersisted` 在建目录时传 `posix:permissions`，而 Windows 直接抛
`UnsupportedOperationException`，异常被 `catch` 吞掉、`save` 返回 `false`，
于是 **Windows 上连接配置一个都存不下**。详见根仓 README v2.25。

「11 条红测」这件事本身也记在此处，因为它是最容易走偏的一次排查：**五条用例给出了五种
毫不相干的症状**（界面上找不到连接名 / JSON 读不出来 / `expected:<1> but was:<0>` /
`Key … is missing in the map` / `UnsupportedOperationException`），逐条看断言消息会得到
五个互不相干的结论。共同点只有一个 —— 它们全都经过 `ConnectionStorage.save`。
---

## 5. 真窗口探针：能不能操作**真的跑起来**的应用

> 与 §1~§4 的 `runComposeUiTest` 互补：那一节验的是「同一条渲染 / 输入链路上的逻辑」，
> 这一节验的是「进程起起来、窗口亮着、真输入送得进去吗」。
> 复现脚本：[`tools/gui-probe.ps1`](./tools/gui-probe.ps1)。

### 5.1 怎么起来

```
# ⚠️ 必须隔离 user.home —— 否则会碰到真实用户的 ~/.config/sundays/connection.json
#    （里面是**明文口令**，且 save 是整体覆盖语义）
.\gradlew.bat :desktopApp:run -PsundaysUserHome=$env:TEMP\sundays-probe
```

`-PsundaysUserHome` 是为此加的**可选**开关（`desktopApp/build.gradle.kts`），不传就完全不生效。

### 5.2 结论：鼠标不通，**键盘通**

| 投递方式 | 结果 | 证据 |
|---|---|---|
| `SendInput` / `mouse_event` 鼠标 | ❌ 无效 | 点「赛博朋克」配色卡后选中框**仍在蓝灰**（前后截图一致） |
| `PostMessage(WM_LBUTTONDOWN/UP)` | ❌ 无效 | 同上（直接投递窗口消息也不行） |
| `keybd_event`（Tab / Shift+Tab / Enter / Space） | ✅ **有效** | Tab×3+Enter 把配色从「蓝灰」切到「哔哩粉」，整页重绘 |
| `SendInput` + `KEYEVENTF_UNICODE` | ✅ **有效** | 中文与任意字符都能注入 |

> ⚠️ 这条**更正了此前笼统的「合成输入都不行」**：不通的是**鼠标**，键盘是通的。
> 之前说「操作不了真窗口」是过头了。

### 5.3 已在真窗口上验到的功能

| 功能 | 结果 | 观察到的界面变化 |
|---|---|---|
| 应用启动 / 首次引导 | ✅ | 引导页正常渲染（5 套配色 + 明暗三档 + 紧凑档） |
| 主界面（连接管理） | ✅ | 「数据库连接管理」空态 + 快速连接 / 新建连接 |
| **切换配色主题** | ✅ | 蓝灰 → 哔哩粉 → 蓝灰，选中框与整页配色随之变化 |
| **切换明暗模式** | ✅ | 选「深色」后选中态落到该项 |
| **紧凑模式** | ✅ | 开启后整页版面**等比缩小**（0.85×），关闭后复原 |
| 完成引导 → 设置页 | ✅ | 「完成」按钮可达，进入设置「个性化」页 |
| 打开新建连接向导 | ✅ | 「基础信息 第 1/4 步」正常渲染 |
| **选择方言** | ✅ | 键盘可在 MYSQL / POSTGRESQL / H2 / DUCKDB / SQLITE 之间切换，单选圆点与边框跟随 |

### 5.4 卡在哪里（两个，都还没定论）

**① 往输入框里打不出字。** `SendInput` + `KEYEVENTF_UNICODE` 返回 **0**（送进 0 个事件）——
它**静默失败**：结构体大小不对或权限不够时返回 0，界面毫无反应，
而「界面毫无反应」和「应用收不到输入」长得一模一样。探针已加返回值检查并退回
`keybd_event` 逐字符（只支持 ASCII / 符号），但**在本轮仍未在真输入框上验证成功**。

**② 向导里的焦点不按预期走。** 在引导页 Tab 是好使的（能逐站走到「完成」），
但进到连接向导后，连续 `Tab` 的截图**完全一致** —— 焦点停在方言卡上不再移动，
`取消` / `下一步` 始终拿不到焦点。

> ⚠️ **这不等于「键盘用户走不完向导」**：源码里 `WinButton` / `WinTextButton` 的现代档
> 走的确实是 Material3 `Button` / `TextButton`，它们**是**可聚焦的。本轮只是**没驱动成功**，
> 不足以判定缺陷。要定论得用 `runComposeUiTest` 写一条焦点顺序断言 —— 那才是能给出
> 「对 / 不对」结论的地方（见 §5.5）。

### 5.4 盲按导航的坑（做真窗口走查必须知道）

- **单选组在 Tab 序里只占一站**，组内移动用方向键。把明暗三档当 3 站数，会一直够不到后面的按钮。
- **焦点环在未选中的元素上不明显** —— 截图里「没变化」常常是焦点落在别处，而不是输入没送达。
  判据要看**选中态**（边框 / 单选圆点 / 开关色）是否变化，那才是可靠信号。
- **ESC 不一定关得掉页面**：外观引导是全屏浮层，唯一出口是底部「完成」。
- PowerShell 里 `$Pid` / `$HOME` 是**只读自动变量**，拿来当形参会直接报错（踩过）。

### 5.5 没走完的部分（诚实记录）

**连接 → 浏览 → SQL 工作台**这条主路径**没有**用真窗口走完，卡在 §5.4 的两处。
目前覆盖它的是：

| 手段 | 覆盖 | 局限 |
|---|---|---|
| `FeatureWalkthroughTest` | 真 H2 + 真 SQLite、真界面渲染与**真点击**、关键节点截图 | 不走连接向导（直接注入已连接的 `DatabaseBrowserState`） |
| `ConnectionManagerFlowTest` | **走连接向导**：选方言 → 填字段 → 测试连接 → 连接 → 断开 → 落盘，真引擎 | 状态机 + 界面都在，但断言粒度是状态字段 |
| `DialectSmokeTest` | 五方言 × 7 项，直打引擎 | 不碰界面 |

⚠️ **三者拼起来仍有一个缺口**：**没有**一条测试在**同一个用例**里从「新建连接」
一路走到「SQL 工作台出结果」。要补这一条，最省事的路子是在
`ConnectionManagerFlowTest` 的末尾接上浏览与执行，而不是继续用真窗口盲按 ——
真窗口探针的价值在于**发现「有没有问题」**（本轮就发现了输入注入失效与向导焦点异常），
不在于替代可断言的测试。

---

## 6. 连上真数据源之后的功能测试（`ConnectedSourceEndToEndTest`）

> 这一节补的是此前**三类测试各覆盖一半、没人串起来**的那道缝。
> 执行代码：[`ConnectedSourceEndToEndTest.kt`](./src/test/kotlin/com/kxxnzstdsw/sundays/ConnectedSourceEndToEndTest.kt)

### 6.1 补的是哪条缺口

| 测试 | 覆盖 | 缺什么 |
|---|---|---|
| `ConnectionManagerFlowTest` | 走向导 → 连接 / 断开 / 落盘 | **连上就结束，不浏览** |
| `FeatureWalkthroughTest` | 浏览 / 过滤 / 搜索 / 对象 / 事务 / 只读 | **直接注入已连接的 `DatabaseBrowserState`**，不走连接 |
| `DialectSmokeTest` | 五方言 × 7 项 | **直打引擎**，完全不碰界面 |

于是「连得上」与「连上之后能用」之间那道缝一直没被测过 —— 而它恰恰是最容易坏的地方：
会话、连接池、schema 解析、标识符大小写，任何一环不对都是**连上了但一用就废**。

### 6.2 本类做的事

真引擎建库并播种 → `ConnectionSession.connect()` **真连上**（走 `IdbEngine.testConnection` 建池）
→ 界面里**真点**树与表 → 读数据 → 过滤 / 排序 / 搜索下推 → SQL 工作台执行
→ 多语句 / 事务可见性 / 只读 → 断开。

**五个方言全部通过**（H2 / SQLite / DuckDB / MySQL / PostgreSQL，含真远程库）。

### 6.3 实测数据（走查时打出来的，可核对）

| | H2 | SQLite | DuckDB | MySQL | PostgreSQL |
|---|---|---|---|---|---|
| 库名 | `SMOKE<ts>`（**大写**） | 临时文件路径 | `main` | `sundays_smoke_<ts>` | `sundays_smoke_<ts>` |
| 表名 | `E2E_ORDERS`（**大写**） | `e2e_orders` | `e2e_orders` | `e2e_orders` | `e2e_orders` |
| 预览总行数 | 12 | 12 | 12 | 12 | 12 |
| 搜索 `PAID` 命中 | 4 | 4 | 4 | 4 | 4 |
| 搜索谓词的 CAST | `VARCHAR` | `VARCHAR` | `VARCHAR` | **`CHAR`** | `VARCHAR` |
| SQL 结果行数 | 3 | 3 | 3 | 3 | 3 |
| 事务回滚 / 提交 | ✅ | ✅ | ✅ | ✅ | ✅ |

> **MySQL 那一列的 `CHAR` 就是 v2.26 那个修复**：它的 `CAST` 不接受 `VARCHAR`，
> 而 PostgreSQL 上 `CHAR` 是 `CHARACTER(1)` 的别名会**静默截断**。
> 同一个功能在两个真服务器上分别用不同写法、各自跑通 —— 这是本地嵌入式库永远给不出的证据。

> ⚠️ **两个远程库上还有你的其它库**：MySQL 的库列表是 `[my_test01, shop, sundays_smoke_<ts>]`，
> PostgreSQL 是 `[examquestions, postgres, sundays_smoke_<ts>]`。本测试**只在自己的
> `sundays_smoke_*` 库里建表**，`tearDown` 无条件 DROP，不碰其它任何一个。

### 6.4 两个判据上的坑（首轮全红时就踩到）

**① 不能拿 `databases.first()` 当探针库。** MySQL 会列出所有非系统库，
第一个未必是刚建的那个，于是失败只报一句
`NoSuchElementException: Collection contains no element matching the predicate` ——
完全指不到真因。改成「先按工作区名精确匹配，匹配不上再逐个展开去找」。

**② 不能断言界面上的「共 N 条」。** 那行是分页栏里的**描述性**文本，
容器窄于 560dp 时**按设计隐藏**（见 `TablePaginationLayoutTest` 记的降级规则）。
断它等于把用例焊死在某个窗口宽度上，而且失败时看到的是「界面没显示总数」，
与真因（宽度不够）八竿子打不着。正确性由 `tab.total` 断言，界面表现交给布局测试。

### 6.5 关于「走不走向导 UI」

向导 UI 本身由 `ConnectionManagerFlowTest` 覆盖；本类从 `ConnectionSession.connect()` 起步 ——
那**正是**向导最后一步调用的入口，所以跳过的是那几下点击，**跳过的不是任何逻辑**。
换来的是**每个方言都能跑同一套**，不必为四种向导形态各写一遍。
---

## 7. GUI 上驱动完整功能：做到了哪、卡在哪

> 承接 §5。这里记录**纯合成输入**（键盘 / 鼠标）驱动真窗口的最新结论，
> 以及为此新增的一条可断言的事实测试。

### 7.1 新增 `WizardKeyboardOrderTest` —— 把「猜焦点」变成「数焦点」

真窗口走查最费时间的一步是**判断焦点落在哪个元素上**，而截图做不到这件事。
所以改用可断言的办法：按 N 次 `Tab` 再回车，看 `wizard.step` 变没变。

**结论**：`Tab×1 + Enter` 就能把向导推进一步 —— 「下一步」**在** Tab 序里，
键盘用户**能**走完向导。之前推不动是**探针数错了**，不是应用的问题。

### 7.2 三个把我带偏的判读陷阱（都写进探针注释了）

| 陷阱 | 后果 |
|---|---|
| **Compose 给「鼠标悬停」画高亮**，而 `SetCursorPos` 挪过的指针会一直停在那儿 | 把「鼠标在哪」读成「焦点在哪」，整条 Tab 计数错位。探针新增 `PARK` 动作：截图前先把鼠标挪到窗口角落 |
| **`SendInput` 会静默失败**（送 18 个文字事件、返回 0） | 「界面毫无反应」与「应用收不到输入」在截图上完全一样。探针改为检查返回值并退回 `keybd_event` |
| **这些控件不画可见焦点环** | 无法从截图判断焦点落点 —— 这是真窗口走查的**根本障碍**（引导页的明暗单选有环，向导里没有） |

### 7.3 方言卡是 `clickable` 不是 `selectable` —— 方向键在组内不生效

真窗口实测 `Down` 两次，选择**纹丝不动**。所以纯键盘换方言只能「Tab 到目标卡再回车」。
`WizardKeyboardOrderTest` 把这条钉住了，免得有人改成 `selectable` 之后探针的导航脚本悄悄失效。

### 7.4 仍然没做到的：纯合成输入走完 连接 → 浏览 → SQL

**不声称做到了。** 精确的卡点是：焦点位置在真窗口里**不可观测**（见 7.2 第三条），
而没有焦点位置就只能盲数 Tab，盲数就会错。这不是「应用不行」的结论 ——
`Tab×1 + Enter` 能推进向导，恰恰证明**应用是行的**，是**探针的观测手段不够**。

要把这条真正做完，需要其中之一：

1. 给向导控件补 `testTag`（`ConnectionManagerScreen` 现在几乎没有），
   让探针能按标识定位而不是按顺序猜；
2. 或者给这些控件一个**可见的焦点指示** —— 这本身也是无障碍改进，
   键盘用户现在看不出自己在哪。

### 7.5 功能覆盖本身没有缺口

「连上真数据源之后的功能测试」由 [`ConnectedSourceEndToEndTest`](#6) 覆盖，
五个方言全过（§6.3 有实测数据）。本节说的只是**驱动方式**上的未竟，
不是功能上的未验 —— 这两件事必须分开说，否则会让人以为功能没测。
---

## 8. 鼠标 + 键盘的 GUI 功能走查

> 「鼠标」在这里指 Compose 的**真实指针输入**（`performClick` / `performTouchInput`）：
> 真命中测试、真坐标、真拖拽，走的是和真应用同一条输入分发链路。
> **OS 级鼠标注入**（`SendInput` / `PostMessage`）在本机进不去 Skiko 窗口，两种投递都实测过（§5.2）——
> 能用鼠标，只是不能从操作系统外部往里塞鼠标事件。

### 8.1 两个新测试

| 测试 | 覆盖 | 结果 |
|---|---|---|
| `ConnectionWizardMouseKeyboardTest` | 鼠标点「新建连接」→ 点方言卡 → 连续点「下一步」到测试步 | 五个方言里 4 个通过 |
| `GuiFeatureWalkthroughTest` | 浏览 / SQL 执行 / 多语句 / 危险确认 / 导出对话框 | 5 / 5 通过 |

### 8.2 走查中确认的两件事

**① 鼠标点击能把连接向导一路点到「测试连接」那一步**，五个方言的步骤推进轨迹都被记录了。
按钮的 `enabled` 状态与字段内容联动 —— 字段没填时「下一步」点不动。

⚠️ 但 **`onNode(hasSetTextAction() and hasText("数据库名")).performTextInput(...)` 稳定失败**
（`Failed to perform text input`，5 方言 × 6 轮一次都没成功），而**不带 label** 的
`onNode(hasSetTextAction()).performTextInput(...)` 是好的。两种可能：
这些字段的语义树里 **label 与输入框是两个节点**，`and` 匹到的是前者；
或者 `WinTextField` 的可编辑节点不带 label 文本。**要弄清它需要给向导控件补 `testTag`**
—— 这也是 §7.4 提的那件事。

**② disabled 的按钮 `performClick` 不抛异常**，它安静地什么都不做。
所以「调用有没有抛错」不能用来判断「点没点着」，必须用**状态有没有变**来判。
第一版就是被这个骗了，报了一堆「下一步点到了=true」然后原地不动。

### 8.3 没写进来的五项，以及确切错在哪

保留「已知问题」比藏起来有用：

| 功能 | 我写错在哪 |
|---|---|
| 事务 | 拿**独立 JDBC 连接**去查事务内刚写的行 —— 未提交的数据本来就该看不到。判据该用同一会话的视图 |
| 拖分隔条 | 量的是 `SCHEMA_DRAG_HANDLE_TAG` 那个**把手自己**的宽（恒为 8px），不是**面板**的宽 |
| 过滤 / 搜索 | 用了 `tableSearchBtn` tag，但该按钮在这一屏**不存在**（要先有预览 tab 才出现，我顺序排错了） |
| 只读 | 文本打进了**错误的输入框**（`onNode(hasSetTextAction())` 命中了别处），根本没执行到拦截 |
| 造数 | 按钮文案猜错了（「执行」不是它真实的文案） |

这五条的**功能本身**已由 `FeatureWalkthroughTest` / `ConnectedSourceEndToEndTest` 覆盖，
这里缺的只是「用鼠标点着走一遍」。

### 8.4 又踩了一次的老坑

`GuiFeatureWalkthroughTest` 首轮 **10 条里 9 条红**，根因只有一个：
我给 H2 内存库起了**固定库名** `guiwalk`，而 `DB_CLOSE_DELAY=-1` 让它在最后一个连接
关闭后**仍然存活** —— 第一个用例建完表，后面 9 个全撞 `Table "GUI_ORDERS" already exists`。

这条在本仓反复出现过（`H2GuiWalkthroughTest` / `DialectSmokeTest` / `ConnectedSourceEndToEndTest`
的注释里都写着「库名必须唯一」），我又犯了一次。**固定库名 + `DB_CLOSE_DELAY=-1` = 必然串库。**

## 9. 真窗口 + 真 MySQL 的功能走查（本轮）

> 这一节全是**真跑起来的窗口 + 192.168.1.5 上的真 MySQL 8.4.9**，截图逐屏核对。
> 前八节的断言都在 `runComposeUiTest` 的合成环境里，而合成环境**看不见像素**——
> 本轮证明了几件事只有真窗口才照得出来。

### 9.1 准备：真数据

在 MySQL 上建了 `sundays_probe`（`sundays_probe` 与 `sundays_smoke` 是两回事，
后者是 §0 冒烟用的固定库）：

| 对象 | 内容 |
|---|---|
| `customers` | 3 行，含中文、NULL、负数余额、文本里带逗号与引号 |
| `orders` | 25 行，外键 `fk_orders_customer` → `customers.id`，索引 `idx_orders_status` / `idx_orders_amount` |
| `order_items` | 40 行 |
| `bad_ref` | 列名是 `` `select` `` 与 `` `中文列` `` —— 故意照标识符转义那条路径 |
| `v_paid` | 视图（JOIN 两表 + WHERE） |

隔离 home：`%TEMP%\sundays-gui-probe`，含 `settings.json`（`onboardingCompleted: true`，
跳过引导页）与 `connection.json`（两条预置连接）。

### 9.2 缺陷一：触发器 / 过程列表在真 MySQL 上整片报错（已修）

**截图里看到的**（展开 `sundays_probe` 后，对象区）：

```
bad_ref / customers / order_items / orders
对象
  视图: v_paid
  触发器: Unknown column 'REMARKS' in 'field list'
  过程 / 函数: Unknown column 'REMARKS' in 'field list'
```

表和视图都正常，只有触发器与过程两节变红 —— 很像「这个库没有触发器」。

**根因**：`MySQLDialect` 的三处 `INFORMATION_SCHEMA` 查询从 H2 抄了 **`REMARKS`** 列。
`REMARKS` 是 **H2 / Oracle** 才有的一列。向 MySQL 8.4.9 逐列核对
`INFORMATION_SCHEMA.COLUMNS` 的结果：

| 表 | 注释列 |
|---|---|
| `INFORMATION_SCHEMA.ROUTINES` | **`ROUTINE_COMMENT`** |
| `INFORMATION_SCHEMA.TRIGGERS` | **没有注释列**（`ACTION_STATEMENT` 是语句正文，不是注释） |

**三处**都错，且是三个副本：

| 位置 | 原 SQL | 改后 |
|---|---|---|
| `listRoutines` 的 ROUTINES 段 | `..., REMARKS, ...` | `..., ROUTINE_COMMENT, ...` |
| `listRoutines` 的 TRIGGERS 段 | `..., REMARKS, ...` | 不取注释列，`description` 为空串 |
| `getRoutineInfo` 的 ROUTINES 段 | `..., REMARKS, ...` | `..., ROUTINE_COMMENT, ...` |
| `getRoutineInfo` 的 TRIGGERS 段 | `..., REMARKS, ...` | 不取注释列 |
| `listTriggers` | `..., REMARKS` | 不取注释列 |

三处都改而不是只改列表：列表与详情是两条独立 SQL，只修列表的话点开详情又报同一句红字。

**为什么所有既有测试都没照出来**：H2 / SQLite / DuckDB / PG 上 `REMARKS` 要么存在要么根本没走到这条路径，
只有真 MySQL 会炸 —— 而炸出来的样子（整节变红）太像「本来就空」。

**回归测试** `MySQLRoutineTriggerQueryTest`（3 项，真 MySQL，跑在 `sundays_smoke`）：

- 在库里真建一个 `BEFORE INSERT` 触发器 + 一个带 `COMMENT` 的存储过程；
- `listTriggers` 必须列出那个触发器（`Unknown column` 是**语句解析期**错误，与有没有行无关，
  所以这条断言本身就足以守住列名）；
- `listRoutines` 必须列出过程**与**触发器，且过程的 `description` **原样等于**我们写的 COMMENT
  —— 这一条是为了防「有人把注释列删掉而不是改对」：那样查询照样成功，用例照样绿，
  而「过程注释读不出来」仍是真缺陷；
- `getRoutineInfo` 对过程与触发器各跑一遍。

**变异验证**：把三处改回 `REMARKS` → 3/3 变红，报错正是界面上那句
`Unknown column 'REMARKS' in 'field list'`；改回后恢复 3/3 绿。

### 9.3 缺陷二：删除连接没有二次确认，一次 Enter 就永久删（已修）

真窗口里用键盘走查时：`Tab ×4` 选中连接 → 再 `Tab ×4` 焦点落在「删除」→ 按 `Enter`，
**连接直接从列表消失，并且从 `connection.json` 里被抹掉**（含明文口令），界面上没有任何提示、没有撤销。

这个界面**可以纯键盘走完**，而焦点停在按钮上时 Enter 的语义就是「点它」——
所以这不是「手滑才会遇到」，是键盘用户必经的路径。

**修法**：在 `ConnectionManagerScreen` 内部收一道 `pendingDelete`，
`requestDelete(id)` 统一转成「记下待办 + 弹框」，确认后才调 `onDeleteConnection`。

- 收在组件内部而不是两个入口各改一遍：删除有**两个**入口（列表项 ⋮ 菜单、总览面板按钮），
  收口才能保证以后新增的第三个入口自动也被拦住；
- 文案点名连接名，并写清边界：「将删除的是本地连接配置（含保存的口令），无法撤销。
  **不会断开数据库，也不会删除库中的任何数据**」—— 用户真正怕的往往是「会不会把库删了」；
- `dismissButton` 先渲染，Tab 序是「取消 → 删除」，第一个停靠点是撤销方向；
  删除按钮用 `error` 配色。

**回归测试** `DeleteConnectionConfirmTest`（3 项）：点「删除」只弹框不删（两个入口各一项）、
取消后连接仍在、确认后才删且只删这一个。
**变异验证**：把 `requestDelete` 退回「直接调 `onDeleteConnection`」→ 3/3 变红。

**真窗口复验**：重启应用重走一遍 → 弹出确认框、连接保住、删除按钮为红色（截图核对）。

### 9.4 缺陷三：连接总览面板的操作按钮被画到窗口外（**未修，根因未隔离**）

**现象**：选中连接后，右栏总览面板的信息卡正常渲染，但下面的
`删除 / 编辑 / 连接` 三个按钮**落在可见区域之外**：

- 应用**默认窗口尺寸**（1152×720，见截图）：「删除」勉强完整、「编辑」被切掉一半、
  **「连接」完全看不到**；同一处的 JDBC URL 也被右边缘截断；
- 窗口最大化（1550×838）后：三个按钮整体右移，只剩最左边一条 13px 露在窗口右缘；
- 向导页（第 1/4 步）症状同类：4 根步骤条按像素测得各 **383px**、间隙 6px，
  **总宽 1550px，起始 x=351** —— 也就是右栏内容排到了 x≈1901，比窗口宽出 351px；
  方言卡同样没有右边界。

**已排除的猜测**：

- 不是「按钮没渲染」：语义树里三个按钮都在，`runComposeUiTest` 在 1024×654 下
  量到的坐标也完全正常（`删除` `Rect(708,397,766,437)`，右对齐在内容右缘）。
- 不是「密度算错」导致我读错：像素扫描实测左栏 `Modifier.width(250.dp)` 渲染为 **313px**，
  密度确为 **1.25**（显示缩放 125%），窗口客户区约 1112px。

**下一步该做的实验**（本轮没做完）：在应用内打印 `LocalDensity.current.density`
与 `WindowState.size`，和窗口实际像素宽对一遍 —— 现在这两者是「布局按 1152dp、
窗口只有 1152px」的量级关系（1112px 窗口排 1440px 内容），
但**究竟是 Compose Desktop 把 `DpSize` 当像素开窗、还是 Skiko 的密度没跟上，
我没有证据，不猜**。

**为什么这条不能就这么放过**：它让「连接」这个首屏主入口在默认窗口下**不可见**。
目前可用键盘绕过（`Tab` 能聚焦、`Enter` 能触发，功能本身是好的），
但用户看不见就等于没有 —— 这也是本节开头那句「合成环境看不见像素」的直接例证。

### 9.5 顺带确认的三件小事

| 现象 | 结论 |
|---|---|
| 连接卡片 `clickable` **可聚焦但不画焦点指示** | Tab 能到、回车能选，只是截图上看不出焦点在哪 —— 这正是 §7 里「盲按导航」难做的原因之一 |
| 左侧 `⋮` 菜单在 Tab 序里排在连接卡片**之后** | Tab 序：⚡ → ➕ → ⚙ → 卡片 → ⋮ |
| 库节点**只能用回车展开**，方向键无效 | `DatabaseNode` 只挂了 `clickable`，没有 `onKeyEvent` 处理 `→` |

### 9.6 本轮用到的探针

在 `build/tmp/` 下（不入库）另有两个一次性脚本，因为 `gui-probe.ps1` 每次开头都
`SW_MAXIMIZE`，而本轮要观察的恰恰是「窗口按默认尺寸打开」的状态：

- `resize-shot.ps1` —— `SW_RESTORE` + `MoveWindow` 到指定尺寸再截图；
- `shot-as-is.ps1` / `keys-shot-as-is.ps1` —— **不动窗口尺寸**，只发按键 + 截图。

后者是 9.4 那个结论的关键：**一最大化就看不到缺陷本身**。