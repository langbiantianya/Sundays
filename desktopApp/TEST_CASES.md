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

**下一轮追加的证据**（补齐焦点环 / testTag 之后又测了一轮，仍然没定位到根因）：

| 窗口像素宽 | 左栏实测 | 右栏内容起点 | 右栏内容宽（按步骤条反推） | 「窗口 − 左栏」 |
|---|---|---|---|---|
| 1550 | 313 | 351 | **1550**（4×383 + 3×6） | 1237 |
| 1024 | 320 | 354 | **906**（4×219 + 3×10） | 704 |

两行都满足 `内容宽 ≈ (窗口 − 左栏) × 密度`。像素扫描还看到一件很直接的事：
**信息卡从 x=351 一直延伸到窗口最后一列 1549，右侧完全没有 24dp 内边距** ——
右栏是按「从它自己的起点一直到窗口右缘」排版的，而不是按「面板宽度减去内边距」。

**这一轮排除掉的**：

- **不是右栏「按内容宽度」撑开**：把 `widthIn(min = 350.dp)` 改成 `fillMaxWidth()`
  （本意是让它老实占满剩余宽度）后**逐像素毫无变化** —— 步骤条仍是各 383px。
  该改动已回退，并在原地留下「为什么回退」。
- **不是紧凑模式把密度改了两遍**：`compactDensity(base, compact = false)`
  原样返回 `base`，`settings.json` 里 `compactMode` 也是 `false`。
- **不是 `main.kt` 的外层容器无界宽度**：`Box(fillMaxSize) { Column(fillMaxSize) { … } }`，
  两层都是有界的。
- **不是 `verticalScroll` 给了无界宽度**（本轮的首要猜测）：摘掉它之后仍然溢出。

**下一轮该做的实验**（还没做）：在应用内打印 `LocalDensity.current.density`、
`WindowState.size`、以及 `ConnectionManagerScreen` 根节点收到的 `Constraints.maxWidth`，
三者对一遍。现在能确定的是「内容宽 ≈ (窗口 − 左栏) × 密度」，
说明**有个地方把「像素宽」当成了 dp 又换算了一次**；但究竟是 Compose Desktop 的
`Window` 约束、Skiko 的密度、还是本仓某处，我没有证据，不猜。

**为什么这条不能就这么放过**：它让「连接」这个首屏主入口在默认窗口下**不可见**。
目前可用键盘绕过（`Tab` 能聚焦、`Enter` 能触发，功能本身是好的），
但用户看不见就等于没有 —— 这也是本节开头那句「合成环境看不见像素」的直接例证。

**它同时是剩余走查的唯一必经之路**：不连上库就没有可操作的界面，
于是导出对话框 / SQL 执行 / 表编辑器 / 分页这四项全都排在它后面（见 §9.9）。

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
### 9.7 连上真数据源之后的操作走查（续）

§9.1~§9.4 停在「连上 + 展开库」。这一节接着把**连上之后的操作**在真窗口里走了一遍。

隔离 home 改成**只留 MySQL 一条连接**：Tab 计数才可预测（两条连接时列表里有两个「连接」
按钮，改一次选中项就要重新数一遍）。

| 操作 | 结果 |
|---|---|
| 展开 `sundays_probe` → 看对象区 | OK「视图: v_paid / 触发器: (无) / 过程·函数: (无)」—— §9.2 的修复在真窗口生效 |
| 打开 `orders` 表预览 | **发现严重缺陷，见 §9.8**（修复前只有第一列有值） |
| 打开 `customers` 表预览 | 中文、负数、`NULL` 都对；`note`（MySQL `TEXT`）三行显示 `[LOB Data]` |
| 排序预设下拉 | 能打开，但**键盘不可用**：菜单打开后没有任何一项获得焦点，方向键与回车都无效 |
| 过滤面板 → 输入 `WHERE` → 应用 | 能提交；**错误就地显示**做得好（表头 + 内容区两处红色提示原文） |
| 成功过滤 / 导出对话框 / SQL 工作台 / 造数工作台 / 分页 | 没走完，原因见 §9.9 |

#### `[LOB Data]` 不是缺陷，是既定行为 —— 但值得商量

引擎对 `TEXT / LONGTEXT / BLOB / BYTEA` 一律返回 `"[LOB Data]"`，
`engine/README.md` 与 `engine/ARCHITECTURE.md` 都写明了。

问题是它把 MySQL 的**短 `TEXT`** 也一并挡了：`customers.note` 里存的是
「VIP 客户，备注里带逗号,和引号"测试"」这种 20 字内容，界面上仍然只显示 `[LOB Data]`。
不把整个 LOB 拉进内存是对的，但**按列的实际长度判断而不是按类型**才是该做的事。

### 9.8 表预览只有第一列有数据 —— 已修

**现象**：真窗口里打开 `orders`，列头 `id / customer_id / amount / status / placed_at`
五列都在，**每行却只有 `id` 那一列下方有内容**，其余四列全空；
把行放大看，能看到**同一位置上有几个数字叠在一起**。

**这不是数据问题**：直接把引擎的 `DATA.LIST` 原始返回打出来，五列俱全：

```
[id] = 1  [customer_id] = 2  [amount] = 123.45
[status] = PENDING  [placed_at] = 2024-02-02 01:01:00
```

**是渲染把五列叠到了一起。** 用 Compose 测试量每个单元格的边界，数字很干净：

| | 表头 | 数据行 |
|---|---|---|
| 修复前 | id@x20、customer_id@x221、amount@x421、status@x621、placed_at@x821 | **该行每一个值都在 `Rect(0, 41, 28, 77)`** |
| 修复后 | 同上 | `Rect(0, 41, 1024, 77)` |

数据行整行只有 **28px 宽** —— 五个 `weight` 列各自拿到 0 宽，全挤在 x=0。

**根因**：表头与表体都挂在 `Modifier.horizontalScroll` 里，而**滚动容器给子项的是无界宽度**。
无界宽度下两个惯用写法同时失效：`Modifier.fillMaxWidth()` 是空操作，
`Modifier.weight(1f)` 分不出剩余空间、**每列拿到 0 宽**。

**为什么所有既有测试都是绿的**：`TableColumnAlignmentTest` 用的是
12 列 × **140dp 定宽**，走 `Modifier.width(140.dp)` —— 那是**显式宽度**，
在无界容器里照样成立。而 `DatabaseBrowserScreen` 建列时只给 key 与 header
（`TableColumn(key, header)`），**全部走 `weight` 路径**。
于是：只要连上真数据库点开任意一张表就必然踩中，而测试因为只测定宽列而全放过了。

**修法**：`DataTable` 用 `BoxWithConstraints` 读出可视宽，算出**表头与表体共同的内容宽度**
（与 `Row` 的分配规则一致：先给定宽子项，剩下的按权重分），两边都用显式的
`Modifier.width(contentWidth)` 取代 `fillMaxWidth()`。两种情形都保住：
`weight` 列铺满视口，定宽列超出即横滚。

**验证**：`TableColumnAlignmentTest` 新增 3 条（行宽铺满视口 / 五格横坐标互不相同 /
表体各列与表头对齐）。变异验证 —— 把行宽退回 `fillMaxWidth()` →
**新增的 3 条全红，既有 2 条定宽用例仍绿**，定位精确；改回后 5/5 绿。

### 9.9 为什么后半程走不完（卡点，不是功能缺失）

键盘走查在这一段失效，卡在**焦点不可见**上：

| 障碍 | 后果 |
|---|---|
| 顶部「SQL 工作台 / 造数工作台」等按钮**不画焦点指示** | 截图上看不出焦点在哪，只能逐格试 |
| **disabled 的按钮不在 Tab 序里** | 「应用」按钮初始 disabled → Tab 直接跳过 → 我按「再一格就是它」去数，数到的其实是别的控件 |
| 打开 / 关闭弹窗、切换标签页会**改变焦点落点** | 跨运行的 Tab 计数不可复用，必须「同前缀量一次、再同前缀动一次」 |
| 连接卡片、TopNav 按钮同样不画焦点指示 | 同上 |

代价是每一次都要拍 8~9 张截图拼图反推位置，**正确率仍然不够**。
这不是产品功能的问题，是**可自动化性**的问题 —— 和 §7.4 记的是同一件事。

**要做完剩下的（成功过滤 / 导出对话框 / SQL 工作台 / 造数工作台 / 分页 / 表编辑器），
先决条件是给这批控件补 `testTag` 与可见焦点指示。** 这跟 §8.1 那条待办是同一件：
「给向导控件补 `testTag`」—— 现在可以确认它**不只**卡在向导上，
表预览工具条、顶部工作台按钮、树节点全都在同一批里。

### 9.10 走查途中修掉的**探针自身**两个缺陷

都是**测试工具**的错，但它们都会让「功能看起来正常」而实际没测到，所以单独记：

**① `TYPE:PAID` 打出来是 `paid`。**
`Send-AsciiChar` 把大写字母映射成大写的 VK 码，却**没按住 Shift**。

这次差点被彻底掩盖：表内搜索编译成 `LIKE '%词%'`，MySQL 默认排序规则不区分大小写，
**照样搜得到** —— 于是「打错字」被搜索结果吞掉，界面看着一切正常。
凡是靠大小写才能对上的操作（用户名、口令、标识符）都会静默出错。

**② `SendInput` 的 `KEYEVENTF_UNICODE` 通道不是「失败」，而是「打错」。**
原实现是「先 SendInput，失败才退回 `keybd_event`」。实测它**返回的事件数等于请求数**，
于是永远走不到兜底 —— 而打出来的是错的字符（同上）。
比「打不出来」危险得多：**字符表的缺口同样是静默的**，
`amount > 500` 会被打成 `AMOUNT, 500`，语句还「像模像样」地被提交，
报错指向别处。现在改成只用 `keybd_event`，并补齐 SQL 必需的符号
（`= < > ( ) ' " % * # @ ; |` 等），打不了的字符**明确报告**而不是悄悄跳过。

### 9.11 缺陷：向导「测试连接」把会话留下，保存后要点两次

**现象**：新建连接 → 填地址 → 「测试连接」→ 显示「连接成功」→ 保存 →
总览面板显示**「已连接」**，按钮是**「断开」**。用户点它想连上，拿到的是**断开**。
必须先手动断一次、再点一次才能真的连上。

**根因**：`ConnectionSession.testConnection` 只调了引擎的 `testConnection` 就把
`statuses[id]` 写成 `CONNECTED`，**从不归还池**。而引擎侧
（`IdbEngine.testConnection` 的 KDoc 自述）会**建（或复用）HikariCP 池**：

> 首次调用会用 config 创建连接池 —— 这一步即「初始化连接」

也就是说「探测」在引擎那一层根本不是只读的。新建连接时没有旧配置可断开，
池就原封不动留在了引擎里。

**修法**：探测结束时区分两种情况 ——
本来就连着（`wasConnected`）则保持不动，**不能顺手把用户的活会话掐掉**；
否则把池 `disconnect` 掉、状态保持 `DISCONNECTED`。「测过了」不等于「连上了」。
失败路径同样归还池（否则坏配置也会留下一个池）。
状态回填前 `bumpStatusGeneration`，把用户在探测期间发起的连接/断开作废。

**既有测试把 bug 写成了契约**：`ConnectionManagerFlowTest > quick connect to h2...`
原先断言「测试连接应初始化出一个连接池」且状态为 `CONNECTED` ——
那正是 bug 本身。已改写为「探测完池归零、状态回 `DISCONNECTED`，**再点连接**才建池」。

**验证**：新增 `TestConnectionDoesNotOpenSessionTest`（4 项，fake engine 记录
`disconnect` 调用次数：新建连接探测后池归零 / 已在连接时探测不动用户的池 /
探测失败也归还池 / 状态回填不被在途操作覆盖）。
变异验证 —— 去掉归还逻辑 → **2 条精确变红**；改回后 4/4 绿。
全量：shared 243/0（1 跳过）、desktopApp 300/0。

### 9.12 §9.4 结案：不是产品缺陷，是**探针的截屏只截了左上角一块**

§9.4 追了三轮（「删除 / 编辑 / 连接」被画到窗口外），一路排除了密度、主窗口尺寸、
`fillMaxWidth`、`verticalScroll`、紧凑模式……全都不是。**最后真因在探针里。**

#### ① 截屏把逻辑坐标和物理像素混着用

PowerShell 默认 **DPI-unaware**：

| API | 坐标系 |
|---|---|
| `GetWindowRect` | **逻辑**（本机 1152dp 的窗口返回 `1152`） |
| `CopyFromScreen` | **物理**像素 |

于是脚本按「1152×720 的逻辑矩形」去「抓 1152×720 的物理像素」——
**只截到窗口左上角 1152×720 的一块**。窗口实际是 `1440×900` 物理像素，
右边 288px、下边 180px 从来没进过任何一张截图。

症状与真缺陷**一模一样**：

- 卡片 / 步骤条一路铺到截图右边缘 → 读成「右边距消失」
- 按钮区从来没出现在图里 → 读成「按钮被画到窗口外」

修法是三行：先 `SetProcessDPIAware()`，再取窗口矩形。
`desktopApp/tools/gui-probe.ps1` 与新增的 `shot-window.ps1` / `click-window.ps1` 都已修。
修完复验：默认窗口（1152×720dp = 1440×900px）下总览面板**完整可见**，
卡片右缘距窗口右缘 40px，三个按钮都在 y=548，一行都没少。

**这条比缺陷本身更值得记**：一个「工具只截了 62% 的画面」的 bug，
能让人对着不存在的问题改三轮生产代码。凡是「界面看起来不对」，
先确认**自己看到的画面是完整的**。

#### ② 「鼠标进不去 Skiko 窗口」——也是错的

§5 当时只试了 `SendInput` 与 `PostMessage` 两种投递方式，没试 `mouse_event`，
而 `gui-probe.ps1` 本身只实现了键盘那一半，于是得出了「不能靠鼠标」的结论。
实测 `SetCursorPos` + `mouse_event` **完全可用**：点树节点展开、双击表名打开预览、
点工具条按钮、点分页控件，全部生效。新增 `desktopApp/tools/click-window.ps1`
（按窗口内相对坐标点击）。**走查效率由此从「Tab 计数 + 截图猜」变成直接点。**

#### ③ `TYPE:` 打 SQL 会被输入法吃掉

本机默认输入法是中文拼音，而 **Shift 会切换中/英**（微软拼音默认行为）。
SQL 里必须带 Shift 的字符一大半：`>` `'` `(` `)` `*` `%` `+` `:` `|` `!` `?`。

```
TYPE:a>b        → 输入框里得到「阿。b」
TYPE:amount >= 500 → amount= 500      （`>` 丢了，语句还「像模像样」）
```

第二条尤其阴险：语句看着没毛病，被提交后报错指向别处，
很容易误判成产品 bug。新增 **`PASTE:` 动作**（剪贴板 + Ctrl+V）：
`WM_PASTE` 直接送 Unicode 文本，不经键盘布局、不经输入法，
中文、SQL 符号、大小写全部原样到位。

顺带修掉 `Send-AsciiChar` 的一个静默错：成功路径没有 `return $true`，
而调用方写的是 `if (-not (Send-AsciiChar $ch))` —— `$null` 也算「打不了」，
于是**每一个字符都被误报成打不出来**，把排查带偏（真失败的那一个反而看不见）。

#### ④ 顺带量到一个真会咬人的数字

虽然 §9.4 是假的，但复验时量出：总览面板内容高约 **388dp**，
最小窗口（`MIN_WINDOW_SIZE` 1024×640dp）扣掉标题栏与内边距只剩约 **568dp**。
余量够，但只够再塞四行「名称 / 方言 / 端口…」——
哪天连接摘要多几行（加 SSH、证书、连接串），按钮就会被顶出可视区，
而用户看到的是「卡片正常、下面什么都没有、也没有滚动条提示」。

新增 `ConnectionOverviewPrimaryActionVisibilityTest`（3 项）钉住这件事：
最小窗口下三个按钮必须完整落在视口内（逐个量边界，不是数语义节点）；
视口更矮时它们必须被顶到界外**且滚动能带回来**。
变异验证 —— 把向导内边距从 24dp 撑到 160dp → **该用例如期变红**。

> 写这条时踩过一个坑：最初断言「被顶出界时按钮行是 0×0」，结果永远测不出东西。
> `getUnclippedBoundsInRoot()` 给的是**未裁剪**边界，按钮在视口外时仍报得出
> 真实宽高，只是位置在下沿之外。「不可见」只能靠**与视口比大小**判定。

### 9.13 缺陷：点「开始导出」后什么都没有 —— 文件没生成，也没有任何提示

**现象**：表预览 → 导出 → 填路径与文件名 → 「开始导出」。
对话框关掉了；全盘搜索**没有任何文件**；界面上**一个字都没有**。
既没有成功提示，也没有失败提示——用户既不知道成没成，也不知道该不该重试。

#### 根因：那条流式路由没有任何一层保证「一定会结束」

导出走 `EXPORT.RUN_EXPORT` → `ExportHandler.executeInMainProcess`：

```kotlin
ExportProcessManager.startExport(id, config, payload)   // ← 返回 Unit
ExportProcessManager.collectResponses(id).collect { emit(it) }   // ← 只有子进程回帧才被写
```

两个缺口叠在一起：

1. **`startExport` 失败时只写日志就 `return`。**
   「通道没就绪」时它 `logger.error(...)` 然后返回，而这条错误
   **既不抛、也不回帧**，调用方无从知道命令根本没发出去。
2. **命令没发出去 ⇒ 一个帧都没有 ⇒ `collect` 永久挂起。**
   于是 `Result` 既不 success 也不 failure，
   `.onSuccess { … }` 与 `.onFailure { … }` **两个分支都不会执行** ——
   界面上自然什么都没有。

实测确认：子进程（`ExportSubProcess`，PID 2020）**确实起来了**，
但整个过程既没有文件、也没有一帧回执、也没有日志进控制台。
**凡是「什么都不发生」，先怀疑没人保证它会结束。**

#### 修法：三层都要收口

| 层 | 改法 |
|---|---|
| `ExportProcessManager.startExport` | 返回 `Boolean`（「命令有没有真的交给 gRPC 流」） |
| `ExportHandler.executeInMainProcess` | **订阅早于下发**；`false` 就自己发一帧 `success=false`；流结束却没收到 `completed` 帧也发一帧失败 |
| 前端 `DatabaseBrowserState.exportQuery` | `withTimeoutOrNull(exportTimeoutMs)`（默认 5 分钟）兜底，超时也变成一句用户看得懂的失败 |

顺带修了一个 **replay=0 的竞态**：原来「先 `startExport` 后 `collect`」，
而 `collectResponses` 给的是 `replay = 0` 的 SharedFlow ——
**没有订阅者时 emit 出去的值直接丢**。小表（几十行）的整轮导出
可能在订阅建立之前就回完了，帧全丢。现在**先订阅再下发**。

#### 顺带修一个「报喜报成丧事」的显示错

导出成功原本写进 `tab.error`，而 `error` 的渲染分支是把**整块表格**换成
「读取失败」面板。于是：

- 导成功了 → 显示「读取失败」，用户刚导出的数据还从屏幕上消失了
- 导失败了 → 数据其实好好的，也显示成「读取失败」

新增 `TablePreviewTab.notice`（非读取结果的提示）单独一行显示，不动表格。

#### 验证

新增 `ExportAlwaysTerminatesTest`（4 项，fake engine）：
引擎只推进度帧、**永不推完成帧**时导出必须**返回**失败而不是挂起 /
空文件名在发请求前就被拒且原因指名道姓 / 成功时结论里带真实文件路径与行数 /
默认看门狗不短于一分钟（防止反向护栏：定太短会把大表导出砍掉）。

变异验证 —— 摘掉 `withTimeoutOrNull` → **精确 1 条变红**
（消息：`这里返回 null 表示 20 秒内一次都没结束`），其余 3 条仍绿。
全量：shared + desktopApp **550/0**（1 条已知跳过）。

> 写这条测试时踩过的坑：最初在 `withTimeoutOrNull` 里套了 `runBlocking`，
> 结果**取消信号传不进去**（内层开了新事件循环），挂死的实现把整个测试任务拖死。
> 变异验证必须先能「被测出来变红」，而不是把构建卡住。

#### 复验结果（默认窗口 + 真 MySQL）

三层收口都在真窗口上验过：

- **看门狗路径**：5 分钟后界面上出现红字
  「导出失败：导出超过 300 秒仍未返回，已中止」，**表格仍在原处**
  （修复前这里是「读取失败」+ 整块表格消失）。
- 查子进程命令行（`Win32_Process`）又挖出两个真因，都已修：

| 真因 | 后果 | 修法 |
|---|---|---|
| `executeAsSubprocess` 只 `catch (e: Exception)` | 驱动装不上抛的是 `Error`（`ServiceConfigurationError` / `NoClassDefFoundError`），**一帧终止帧都不发** | 改 catch `Throwable` |
| 子进程 classpath 只拼 `engine/build/libs/libs/*.jar` | **装不上 JDBC 驱动**（`mysql-connector-j` 在隔壁 `drivers/`，方言在 `dialects/`） | 按目录枚举 jar 目录，三个都带上 |

修完实测子进程 classpath 从 100 项变 110 项，`mysql-connector-j-9.7.0.jar`
与 5 个方言 jar 都在里面。

**仍未解决**：导出**依然没有产出文件**。子进程活着、不再秒退，但
`ExportHub` 的「命令下去 → 帧回来」这一段在本机 `:desktopApp:run` 下不闭环。
引擎侧**没有任何 `EXPORT.RUN_EXPORT` 的端到端集成测试**（只有前端的参数校验），
所以这条链路一直没人验过 —— 这是下一轮要补的第一件事：
先写一个「起子进程 → 真导一张 H2 表 → 断言文件存在」的引擎集成测试，
让这条链路有个能红的锚点，再谈修。

在那之前，**至少用户不再面对沉默**：无论引擎那边发生什么，
界面上一定会出现一句说人话的结论。

### 9.14 导出：系统路径选择器 + 进度弹窗

§9.13 把「有没有反馈」解决了，接下来的问题是**交互本身还欠账**：
路径只能靠手敲，导出期间只有一行提示文字。两样都补上。

#### ① 路径：系统选择器 + 手动输入，两条路都给

| 入口 | 作用 | 保留的原因 |
|---|---|---|
| 「浏览」 | `JFileChooser` 目录模式，只定目录 | UNC / 网络路径在文本框里照粘 |
| 「选文件」 | `JFileChooser` 保存模式，**目录 + 文件名一步定** | 最常用的一条路；文件名已按当前格式预填 |
| 两个文本框 | 任意绝对路径 | 无头 / CI 环境压根弹不出对话框时，这是唯一走得通的路 |

把文本框拿掉等于把后两种场景整个堵死。

> **踩坑记录：一开始用的是 `java.awt.FileDialog`（Windows 上映射到资源管理器的原生对话框），
> 观感确实更像「系统的」，但实测拿不到用户选的真实路径：**
>
> ```
> 用户在原生对话框里进到 build\、文件名保持 export.csv、点确定
>   → getFile()      == "export.csv"    ← 只有文件名，没有目录
>   → getDirectory() 仍是打开时的那个目录，导航后不回填
> ```
>
> 于是「我明明在 build 里选的」被解析成了进程工作目录下的 `export.csv`，
> 输出目录框**纹丝不动**。**写错路径比没有选择器更糟** —— 用户以为文件在 build 里，
> 实际上根本不在。所以选了返回值可靠的 `JFileChooser.getSelectedFile()`（完整绝对路径）。

#### ② 进度：不确定态 + 如实行数

引擎的 `ExportProgressFrame` 只有 `exported_rows`，**没有总数** ——
导出走的是一条普通 `SELECT`，引擎不会为了报进度先跑一遍 `COUNT(*)`。
前端也**不能**拿表预览那行的行数凑百分比：预览可能带过滤，导出的是整张表。
报一个假的百分比比不报更糟，所以：

- 进度条走 **indeterminate**
- 旁边如实显示「已导出 N 行…」；**第一帧还没到时显示「正在连接引擎…」而不是「0 行」**
  （「0 行」会被读成「导出失败」，那正是要避免的误读）

弹窗只能「后台运行」（关掉窗口、导出继续、结果照样写进顶部提示行），
**没有「取消」**：取消要一路传到引擎子进程（`EXPORT.STOP_EXPORT`），
而那条链路正是当前最不可靠的一段 —— 与其摆一个按了没反应的按钮，
不如给一个**一定做得到**的动作。

#### 验证

- `exportQuery` 新增 `onProgress` 回调，**逐帧**抛（不是只报最后一帧）
- `ExportAlwaysTerminatesTest` 新增 1 项：引擎按 100 / 500 / 1200 三帧推进度，
  断言回调收到的是 `[100, 500, 1200]`。
  理由：只报最后一帧的话，弹窗从头到尾纹丝不动，用户与面对 §9.13 那个静默缺陷时
  **分辨不出区别** —— 那一整轮改动就白做了
- 新增 `NativePathPickerTest`（3 项）：系统对话框不可用时**不抛异常**，
  安静返回 `null`，由调用方保留手动输入这条路。
  两条会真弹窗的用例加了 `assumeTrue(GraphicsEnvironment.isHeadless())` ——
  有显示器时它们会弹出模态框**把整个测试任务挂死**（本轮踩过一次）。
  临时加 `-Djava.awt.headless=true` 跑过一遍：3/3 绿，兜底路径确实有效
- 真窗口复验：选 `build` → 「浏览」→ 「选择」→ 输出目录写回 `...\desktopApp\build`；
  「开始导出」→ 进度弹窗显示该目录 + 「正在连接引擎…」+ 不确定进度条

### 9.15 引擎侧单测：导出链路一共有 5 个缺陷，而且**没有一个会被「导出成功了吗」发现**

§9.14 收尾时进度弹窗显示的永远是「正在连接引擎…」，导出完成帧的行数恒为 0。
这一节把整条链路拉到**引擎模块的单测**里逐段拆开 —— 用户的要求是
「从 engine 模块的单测验证，不行就多加点日志输出」。加了日志之后，
一共挖出 5 个真实缺陷，其中 4 个和这行 0 有关。

先说日志：桌面应用被 IDE / Gradle 拉起时 stdout 用户根本看不到，
SLF4J 在这个场景下还可能整个是 NOP。所以父子两侧关键点都直接
`System.err.println("[export]…")` / `"[export-sub]…"`，并且子进程的
stdout / stderr 被重定向到 **`engine/build/libs/export-subprocess.log`** ——
用户能看到的地方。定位「子进程到底有没有起来、发出了几帧、每帧几个字」全靠它。

#### 缺陷 1：`PayloadAdapter` 把整数变成 `3.0`，于是 `exportedRows` 读不回来

`google.protobuf.Value` 只有 `number_value`（double），没有整数类型。
于是 `3` 走完 gRPC 往返变成 `3.0`，而 `JsonPrimitive(3.0).content` 是字符串 `"3.0"`。
业务层读这些字段用的是 `jsonPrimitive?.longOrNull` —— 它走 `content.toLongOrNull()`，
**对 `"3.0"` 一律返回 null**。调用方基本都写了 `?: 0` 兜底，
于是数字安静地变成 0：不报错、不抛异常，只是值错了。

症状完全对得上：完成帧里 `filePath`（字符串，`content` 就是原文）完好无损，
**只有 `exportedRows` 恒为 0**。

修法：`PayloadAdapter.numberPrimitive` 在边界把能表示成整数的 double
还原成整数字面量。真正的非整数（1.5、金额、比率）仍按 double 输出。

> 这不只是导出的问题 —— 任何「响应里的整数经 protobuf 往返后再用
> `intOrNull`/`longOrNull` 读」的地方都中招。边界必须自己扛。

#### 缺陷 2：并发导出直接把 ExportHub 流搞坏

gRPC 的 `StreamObserver.onNext` **不是线程安全的**。子进程侧每个 `START_EXPORT`
都跑在 `Dispatchers.IO` 的独立协程里，两个导出重叠时就有两条线程同时对
同一条流调 `onNext`，内层 `ServerCallImpl.sendHeaders` 直接 checkState 失败，
抛 `sendHeaders has already been called`，**整条流当场废掉**。

真实场景不是测试造出来的：**用户同时导两张表，就是这个并发度**。
表现是「其中一个导出莫名其妙失败 / 进度条卡死」，而且偶发。

修法：`ExportSubProcess.stream()` 给每条流配一把对象锁（不同父进程连接互不阻塞），
所有发帧路径都过 `sendFrame`；父进程侧的 `commandObserver.onNext` 同样不线程安全，
也加了 `sendLock`（换流时连锁一起换，避免新流去抢旧流正在持有的锁）。

#### 缺陷 3：`localhost` 会被解析成 IPv6 `::1`

同一个 `localhost` 同时出现在建流和探活两处。Windows 上它同时解析出
`127.0.0.1` 和 `::1`，顺序还会变；一旦 gRPC 挑了 `::1`，而对端只监听 IPv4，
就得到一句莫名其妙的 `UNAVAILABLE: io exception`
（真实堆栈：`Connection refused: getsockopt: localhost/[0:0:0:0:0:0:0:1]:60467`）。

导出子进程永远和父进程同机，根本没有「跨主机」这一说。改成写死
`127.0.0.1`（`ExportProcessManager.EXPORT_HUB_HOST`）。

这是**偶发且无法复现**的那一类：一旦命中，用户看到的就是「导出无反应」。

#### 缺陷 4：已成功的导出后面还会跟一句失败

`publishResponse` 收到终止帧后**不能**立刻把 flow 从 map 里删 ——
`ExportHandler` 是先 `startExport`、**后** `collectResponses(id)` 的，
删早了它会 `computeIfAbsent` 造一条**全新的**空流，终止帧就永远送不到。
所以删除被 `delay(50)` 推后。

而这 50ms 里子进程一死，`failPendingExports` 就会给一条**已经成功**的导出
补一帧失败 —— 界面上「导出成功了」后面又跟一句失败。
新增 `endedExports` 集合：「已收口」不再用「已从 map 里消失」来表达。

#### 缺陷 5（探针自己的）：探活用的假导出污染了并发度

原先的探活是「先发一条 `START_EXPORT`，能发出去就说明对面起来了」。
两个问题：

- gRPC 客户端对**还没在监听**的端口**不会立刻抛**，`onNext` 照常返回，
  错误稍后才从 `onError` 异步回来 —— 于是这条「探活」命令本身就成了第一条失败帧，
  测试拿到的形态和真 bug 一模一样，看着像导出坏了，其实是对面还没起；
- 它自己也是一个并发导出，掩盖 / 诱发真正的并发竞态。

改成轮询 TCP 端口，**并且顺序要紧：先等端口、再建流** ——
`stub.stream()` 拿到的 `ClientCall` 会立刻去连，对面没 bind 就是 `Connection refused`，
而这个失败是异步回来的，等端口等到了也救不回来。
生产代码 `ExportProcessManager.awaitHubReadyOrReportFailure` 本来就是这个顺序。

#### 验证

- `PayloadAdapterNumberRoundTripTest`（6 项，新）：整数往返后 `longOrNull`/`intOrNull` 都能读回；
  小数仍是 `double`；NaN / 无穷退回 double 形态而不是被当整数处理
- `ExportPipelineIntegrationTest`（4 项，新）：
  - `subprocess export writes the file` —— 引擎本体，真连 H2 真写文件，
    中文 / 负数 / 含逗号字段都验
  - `a bad output directory fails loudly instead of silently` ——
    坏目录必须响亮失败（`end=true` + 非空 error），而不是「什么都没发生」
  - `the ExportHub stream carries progress back` —— gRPC 通道往返
  - `concurrent exports on one hub stream do not corrupt it` ——
    **24 个导出同时压一条流**，每个都必须成功收口、文件都在、流不能断
- `ExportEndToEndIntegrationTest`（桌面层 → 引擎 → **真子进程** → 磁盘有文件）：
  断言进度里出现过非零行数（就是缺陷 1 的那条断言）
- **变异验证**：`concurrent` 用例在 24 并发下，把 `synchronized(sendLock)` 摘掉
  连跑 3 次 → **3/3 红**；装回锁连跑 3 次 → **3/3 绿**。
  （4 并发时只抓到 1/3，锚不住，所以把并发度提到 24）
- 全量：`:engine:test :shared:jvmTest :desktopApp:test` → **631 项 / 0 失败**
  （engine 298、desktopApp 312、engine-grpc-client 21）

#### 教训

**测试必须在能跑的环境里可跑。** Gradle 测试的 classpath 上没有 jar，
导出子进程根本起不来 —— 这条链路只在打包产物里被验证过，这也正是它烂掉这么久的原因。
先给子进程启动加上「退回本进程 classpath」这条路，链路才第一次能在单测里被跑起来。

**并发缺陷的回归测试必须有牙齿。** 4 并发抓不住（1/3），
必须把并发度和帧密度拉到让竞态几乎必然发生（24/24），再用变异验证确认它真的会红。

### 9.16 缺陷：左侧树里选的库根本没进查询 —— 只有连接配置指定的库能看

用户在树上点别的库里的表，界面上立刻报错：

```
Table 'sundays_probe.orders' doesn't exist
```

而他点的是 `shop`。**错误信息里那个库名恰好是「我没点的那个」** ——
这就是为什么它不容易被自己联想到根因。

#### 根因：`database` 只是个「池的区分标签」，从来没被应用到会话上

链路本身是对的：树节点 `onOpenTable(name, tbl)` → `openTab(schema)` →
`TablePreviewTab.schema` → `engineConn(database = tab.schema)`，
一路都带着那个库名（`DatabaseBrowserScreen.kt` 逐层核对过，无需改）。

问题在引擎侧。`PoolManager`：

- `configKey` **含** `config.database` ⇒ 每个库拿到**各自的连接池**（这看着像已经处理了）
- 但 `createDataSource` 里 `resolvedJdbcUrl = config.jdbcUrl` ——
  **每个池都用同一个 URL**，而 URL 里钉死的就是连接时指定的那个库

所以「按库分池」这件事做了一半：池分开了，会话却全都在同一个库上。
而 `DataHandler.list` 拼的是裸表名（`SELECT * FROM <table>`），
裸表名按会话默认库解析 ⇒ 只有连接配置里那个库能查到。

表列表为什么是好的？因为 `TableHandler.list` 走的是
`dialect.listTables(conn, config.database, schema)`，查的是 `information_schema`，
**本来就跨库**。于是「表列得出来、点开就报错」—— 这个割裂正是本缺陷的指纹。

#### 真 MySQL 上的复现（不是推演）

```
jdbcUrl 里的库      = jdbc:mysql://192.168.1.5:3306/sundays_probe
会话默认 catalog    = sundays_probe
information_schema 里 shop 的表数 = 32          ← 表列得出来
裸表名 SELECT * FROM `user` -> 失败: Table 'sundays_probe.user' doesn't exist   ← 点开就报错
conn.setCatalog("shop") 后 catalog = shop      ← 切库本身是可行的
```

#### 修法

`PoolManager.applyCatalog` —— 每次借出连接时把会话 catalog 切到 `config.database`，
与既有的 `setSearchPath`（schema）完全同构：不靠建池时的 `connectionInitSql`，
**每次借出都设一遍**。

**第一版栽在 DuckDB 上。** 最初写成「无脑 `conn.catalog = x`，把 SQLState `0A000`
（feature not supported）当不支持方言放行」。全量回归直接红一片：

```
切换到数据库 'C:\...\sundays-smoke.duckdb' 失败：Catalog Error: SET schema:
No catalog + schema named "C:\...\sundays-smoke.duckdb"
```

`DuckDBHandlerIntegrationTest` / `DialectSmokeTest [DuckDB]` / `ConnectedSourceEndToEndTest [DuckDB]` 全中。

原因是 **`ConnectionConfig.database` 这个字段是重载的**：MySQL 放 catalog 名，
而 DuckDB 放的是 `.duckdb` **文件路径**（`DuckDbSmoke.config` 就是这么配的）。
DuckDB JDBC 的 `setCatalog(x)` 内部发的是 `SET schema = 'x'` —— 于是拿文件路径去
当 schema 用，直接炸。而且它抛的 SQLState 不是 `0A000`，容错分支根本接不住。

**教训：能不能切 catalog 只有方言自己知道。** 于是下沉成 SPI 方法
`DatabaseDialect.switchCatalog(conn, catalog)`，**默认空实现 = 没有，永远不抛**；
只有 `MySQLDialect` 覆盖它（`conn.catalog =`，即 `USE <db>`）。
「没有 catalog 概念」这件事由方言自己声明，不再靠猜异常类型。

**为什么真实失败不能吞**：若是「目标库不存在 / 无权限」，吞掉就会让查询静默跑在
**错误的库**上，读到别的库的同名表 —— 比报错危险得多。所以 MySQL 的实现让异常照抛，
`PoolManager` 只负责把库名补进消息里。

顺带修正一处**事实错误**：`MySQLDialect.supportsCrossDatabase` 原本是 `false`，
注释写「MySQL 单连接单库」。这不只是笔误 —— 它会让人以为「按 database 分池就够了」，
从而**漏掉真正要做的那一步**。MySQL 一个实例多个 database，同一连接 `USE` 一下即可，
已改为 `true`（`SystemListDriversIntegrationTest` 的对应断言、方言 README、
`DatabaseDialect` 的 KDoc 一并更正）。该标志目前只经 `SYSTEM.LIST_DRIVERS` 对外暴露，
不参与任何行为判定，所以改动是纯事实修正、无行为风险。

#### 为什么测试只能打真 MySQL

先试过用 H2 造等价场景（`CREATE DATABASE` + `conn.setCatalog`），
实测 **H2 的 `setCatalog` 是静默 no-op**：`otherdb` 被当成 schema 处理，
切完 catalog 纹丝不动，读出来的还是默认库的数据。

拿它写测试会得到一条**永远绿、但什么都验不到**的用例 —— 比没有测试更糟。
H2 / SQLite / DuckDB 在桌面端也各自只连一个库，不存在跨库浏览场景；
真正需要跨 catalog 的就是 MySQL（一个实例多个 database）。

#### 回归测试：`CrossDatabaseQueryTest`（3 项，真 MySQL，不可达时 assumeTrue 跳过）

**两条用例而不是一条** —— 只测「另一个库里的表能查到」有个致命漏洞：
只要 `setCatalog` 的异常被吞掉，查询就会静默跑在默认库上。
若目标表名在默认库里恰好不存在那是硬报错（好）；
但若**同名表两边都有**，就会安安静静返回**错的行**，而「能查到行」这种断言照样绿。

| 用例 | 钉住什么 |
|---|---|
| `非默认库独有的表能查到` | 完全没切库 → 修之前是 `Table '默认库.表名' doesn't exist` |
| `同名表读到的是选中库的内容` | 切库失败被吞掉 → 修之前**静默返回错的行** |
| `连接配置指定的库仍然正常` | 回归护栏：切库不能反过来把原本能用的默认库弄坏 |

#### 变异验证

摘掉 `applyCatalog(conn, config)` 调用后连跑：

```
同名表读到的是选中库的内容   FAILED  expected:<[另一个库的行]> but was:<[默认库的行]>
非默认库独有的表能查到       FAILED  Table 'sundays_smoke.probe_only_here' doesn't exist
连接配置指定的库仍然正常     通过                      ← 护栏是有效的，不是恒真
```

2/3 红，且**两种失败形态都精确复现了用户报的现象**。装回修复后 3/3 绿。

#### 验证

- `CrossDatabaseQueryTest` 3/3（真 MySQL，未被 skip）
- 全量 `:engine:test :shared:jvmTest :desktopApp:test` + 四个方言模块 → **849 项 / 0 失败**
  （engine 298、desktopApp 315、engine-grpc-client 21、dialect-h2 65、
  dialect-sqlite 62、dialect-duckdb 88）
- 跳过的 7 项全是平台/环境相关（POSIX-only 的 UDS 三项 + headless AWT 对话框两项 +
  IPC 配置两项），**没有一条是远程库 skip** —— 也就是说真 MySQL 的
  `DialectSmokeTest` / `CrossDatabaseQueryTest` / `ConnectedSourceEndToEndTest`
  这一轮都真的跑了。

#### 教训

**「按 X 分池」不等于「按 X 生效」。** `config.database` 进了池 key、每个库拿到了
各自的池，看起来这件事已经做了 —— 但池是按库分的，**连接却全都连到同一个库**。
分池和切库是两件事，只做前者等于没做。

**测试要挑能暴露该缺陷的那一侧。** H2 是这里最顺手的选项，也正因如此最危险：
它对 `setCatalog` 静默 no-op，写出来的测试会永远绿、却什么都验不到。

**「容错」要先问：这条路径上各方言的语义一致吗。** 第一版按 SQLState 容错，
看着稳妥，实际是拿一个统一假设去套四种不同的语义 —— DuckDB 直接把容错分支冲穿。
把判断交回给**知道答案的那一方**（方言），比在调用方猜异常类型可靠得多。

### 9.17 导出：起手 SQL 带上过滤与排序、可编辑、可预览；SQL 工作台也能导出（多语句多文件）

#### 三个需求，一处实现

1. 表预览的导出弹窗**默认带上当前的过滤与排序**（外加表内搜索）
2. 拼出来的那条 SQL **可编辑**，编辑器**复用 SQL 工作台那套**（高亮 / 补全 / 格式化）
3. 弹窗里能**预览**这条 SQL 的结果
4. SQL 工作台也加**导出**按钮，直接用编辑器里的查询 SQL；**多条 SQL 导出多个文件**

#### 改动 1：起手 SQL 与「屏幕上那张表」一致

旧实现在 [TableFilterBar] 里恒拼 `SELECT * FROM <表>`，注释还写着「导出**整张表**
（与当前页无关）」。于是：

- 屏幕上筛出 3 行、点导出拿到全表几万行
- 对话框里那句「将执行：SELECT …」还是**省略号小字**，用户无从判断将要导出什么

新的 [TablePreviewTab.buildExportSql] 用 [effectiveWhere] + `orderByClause` 拼：

```
SELECT * FROM orders WHERE status = 'paid' ORDER BY created_at DESC
```

用 [effectiveWhere] 而不是只取 `whereClause` 是因为**表内搜索也是过滤** ——
用户搜到只剩匹配行，导出却把不匹配的行也带上，同样是「所见非所导」。

#### 改动 2：`AlertDialog` → 全屏对话框

原来格式 / 目录 / 文件名 / 目标表全塞在 `AlertDialog.text` 里，现在还要再塞
**代码编辑器**和**结果预览表** —— 那点高度根本放不下，硬塞的结果是两个都被压没。
改成 `Dialog(usePlatformDefaultWidth = false)` + 可滚内容区。

编辑器**直接用 `CodeEditorWithToolbar`**（`:shared` 那个），于是高亮、库表字段补全、
**格式化按钮**全部天然一致 —— 不另造一个只会显示不能好好用的输入框。

#### 改动 3：预览的白名单 —— 这条测试抓到了一个真漏洞

预览是「点一下就发到数据库」的动作，而它就摆在一个用户刚编辑完的 SQL 编辑器旁边。
第一版判据写成「剥掉注释后**整段**是否以 SELECT / WITH 开头」。

写测试时立刻暴露了问题：

```
含写操作就不是纯只读
AssertionError  ← isReadOnlyQuery("SELECT 1; DROP TABLE t") 返回了 true
```

**只看开头是不够的**：`SELECT 1; DROP TABLE t` 因为「第一个词是 SELECT」被放行 ——
而这正是导出对话框里最容易被粘贴出来的形态（从别处抄来的一段脚本）。
点一下「预览」就把表删了。

改成**逐句判**：「每一句都以 SELECT / WITH 开头」，与 [DangerousSql.scan] 同样逐句遍历。
配套补了 4 条用例钉住两侧：

| 用例 | 钉住什么 |
|---|---|
| `只读语句后面跟一句写操作时必须拒绝` | `SELECT 1; DROP TABLE t` / `SELECT …; UPDATE …` |
| `多条纯 SELECT 全部放行` | 反向：不能因为「有第二条」就一律拒绝 |
| `空输入不算只读` | 返回 true 会让预览按钮亮着，点下去报莫名其妙的引擎错误 |
| `注释里的关键字不算语义` | `SELECT 1 -- DROP TABLE t` 是只读的（注释本来就没被执行） |

#### 改动 4：SQL 工作台的导出 + 多语句多文件

多语句**不是**把整段脚本塞给一次导出 —— 一段脚本里若有两条 SELECT，
一次导出只能拿到最后一组结果集，前一条的结果会被丢掉。
所以用引擎已有的 [SqlScriptSplitter] **逐条**导出（不是 `split(";")`，
那个会把字符串字面量、注释、PG 美元引用里的分号当成边界）。

文件命名 [numberedFileName]：`export.csv` → `export_1.csv` / `export_2.csv` …，
**只在确实多条时加序号** —— 单条却得到 `export_1.csv` 会让用户以为自己导了个片段。

**写语句在切分之后立刻被剔掉**（[exportStatementsFor] 只留 `isReadOnlyQuery` 的那些）。
导出是把查询结果落成文件，而 `UPDATE` / `DROP` 的副作用发生在**执行时** ——
用户点了个叫「导出」的按钮却改了数据，是不能接受的。

另给 `SqlSheet` 加了 `notice` 字段：导出成功不该被下一次 SELECT 前的清空抹掉，
也不该把结果区顶成「执行失败」（与 [TablePreviewTab.notice] 同一思路）。

#### 验证

- 新增 `ExportSqlBuildingTest`（8）、`NumberedFileNameTest`（5）、
  `ExportStatementSplittingTest`（6）、`ExportPreviewGuardTest`（8），
  与既有 `NativePathPickerTest` / `ExportAlwaysTerminatesTest` /
  `ExportEndToEndIntegrationTest` 一起覆盖导出全链路
- **变异验证 1**：`isReadOnlyQuery` 的第一版实现（只看开头）被
  `只读语句后面跟一句写操作时必须拒绝` 抓到
- **变异验证 2**：下面那个「起手 SQL 丢分号」的缺陷被真窗口抓到

#### 真窗口复验（真 MySQL，1440×900）

| 走查项 | 结果 |
|---|---|
| 表预览导出起手 SQL | `SELECT * FROM probe_shared_name WHERE id >= 1` ✅ `WHERE` 还被语法高亮标出来 |
| SQL 可编辑 + 格式化 | 编辑器带行号 / 高亮 / **「格式化」**按钮；库表字段补全也在工作（弹 `probe_shared_name 表 · sundays_xdb`） |
| 预览 | 点「预览前 100 行」返回表头 `id \| note` + 数据行 `1 \| 另一个库的行` |
| SQL 工作台导出 | 工具栏出现「导出」按钮（编辑器为空时禁用，输内容后点亮） |
| 多语句 → 多文件 | 标题显示「2 条语句将导出为 2 个文件」，产出 `export_1.csv`（`id,note` / `1,另一个库的行`）与 `export_2.csv`（`id` / `1`） |
| 成功提示 | 结果区顶部「✓ 已导出 2 个文件（共 2 条语句）：export_1.csv；export_2.csv」 |

#### 真窗口抓到的缺陷：起手 SQL 用 `"\n"` 拼 → 分号丢失

多语句导出第一次跑时报：

```
导出失败 1/2 条：You have an error in your SQL syntax; ... near 'SELECT id FROM
probe_only_here' at line 2
```

**标题明明写着「2 条语句将导出为 2 个文件」，实际却按 1 条跑** —— 两个信息互相矛盾，
而单测全绿（因为它测的是 `exportStatementsFor` 本身，没有覆盖「切完再拼回弹窗」这个往返）。

根因：弹窗的 `presetSql` 用 `statements.joinToString("\n")` 拼，
**分号被丢掉了**；而 [SqlScriptSplitter] 是按分号切的，于是用户在弹窗里点
「开始导出」时再切一次切不开，两行被当成**一条**整段发给引擎。

改成 `joinToString(";\n")`，并补 `拼回弹窗再切一次仍是原来的条数` 钉住往返 ——
它同时断言了「带分号切得开」和「换行拼接切不开（反例）」。

#### 顺带修掉的两个探针缺陷

1. **`gui-probe.ps1` 的动作分隔符 `;` 与 SQL 的分号冲突** ——
   `-Actions "PASTE:SELECT 1;SELECT 2"` 被解析成「一个 PASTE + 一个未知动作」，
   后者**被静默忽略**，结果是只粘进第一条，而截图上看起来「粘贴成功了」。
   这种「半个动作生效」的失败模式最容易把人带偏（实测：多语句验证时被它卡了一轮）。
   改成支持 `\;` 转义。
2. **编辑器高度把预览区顶出可视范围** ——
   `maxLines = 12` 时预览按钮在滚动区外，用户打开弹窗看不到。
   压到 7，并让结果表自己内部滚动。

### 9.18 后台任务列表 —— 而「后台运行」此前**根本不是后台的**

用户提出：「后台导出的时候需要一个后台任务列表入口来查看任务」。动手前先查现状，
发现比「少一个入口」严重得多。

#### 缺陷：「后台运行」是个谎，切 pane 导出就死了

进度弹窗上的「后台运行」只是把弹窗藏起来，而导出协程挂在
`PreviewTabArea` 的 `rememberCoroutineScope()` 上 —— **那个 composable 一离开组合，
协程就被取消**。于是：

- 用户点「后台运行」→ 切到 SQL 工作台 → **导出被杀**
- 进度状态也是 composable 的 `remember`，切 pane 后连「刚才在导出什么」都不记得
- 界面上不留任何痕迹：没有文件、没有提示、没有失败

而「后台运行」这个按钮**恰恰在暗示它能活着跑完**。

#### 三处改动

1. **任务模型进状态机** —— `DatabaseBrowserState.BackgroundTask` 存在
   `state.backgroundTasks`（`mutableStateListOf`，最新在前），于是任务活过 pane 切换，
   且**在任何 pane 都能看**（per-sheet，与 sheet 生命周期一致）。

2. **导出协程改用应用级 scope** —— `state.startExport` 在状态机持有的 scope 上跑，
   而那个 scope 来自 `main.kt`、活到应用结束。**这才是「后台」的字面意思。**
   顺带说明：单元格编辑这类**瞬时**操作仍留在组合作用域 ——
   随 pane 离开而被取消是对的。

3. **工具栏「任务」入口 + 徽标** —— 入口属于 `BrowserToolBar` 而不属于任何 pane：
   「后台运行」的语义正是「我先干别的，回头再看它」，所以这个入口必须切走之后还在。
   徽标数字只数**运行中**的：已完成的留在面板里，不该在工具栏上一直占位。

#### 面板里放什么、不放什么

每条任务：**状态图标 + 文件名 + 目录/格式 + 一行状态说明**。
状态行沿用「不确定态 + 如实行数」的既有原则（§9.14）：运行中且**还没收到第一帧**时
说「正在连接引擎…」而不是「已写出 0 行」—— 后者会被读成「导出失败」。

⚠️ 面板**刻意不提供「取消」**。取消要一路传到引擎子进程（`EXPORT.STOP_EXPORT`），
而那条链路是本项目最不可靠的一段（§9.15 那四层收口就是为它做的）。与其摆一个按了没反应的
按钮，不如只给「清除已完成」—— 那是**一定做得到**的动作。

#### 两条容易做错、测试专门盯住的细节

- **「清除已完成」不能碰运行中的**。用户点一下就把正在跑的导出从视野里弄没了，
  比不做这个功能更糟。`BackgroundTaskLifecycleTest` 用一个**可挂起**的 `GatedEngine`
  同时造出「一个已完成 + 一个运行中」—— 用立即完成的引擎做不到
  （等断言跑起来第二个早收口了，红的是时序不是被测逻辑）。
- **多语句导出 = N 个任务**，不是 1 个。面板存在的意义是回答「刚才那批导出怎么样了」，
  合成一条时用户只能看到「失败了」，看不出**哪一条**失败 —— 而多语句导出里单条失败
  恰恰是最常见的形态（一条 SQL 引用了不存在的列）。
  `DatabaseBrowserState.startExportBatch` **串行**跑：每个协程都驱动一次引擎导出，
  大表并发会把库压垮，而并发度不可控。

#### 验证

- `BackgroundTaskLifecycleTest`（8 项）：成功收口 / 失败也必须收口并带原因 /
  徽标计数进出 / 清除已完成不误伤运行中 / 批量登记 N 个且文件名带序号 /
  空列表不登记 / 状态行不报 0 行 / 状态行区分成功失败
- **变异验证**：`clearFinishedTasks` 改成 `clear()`（连运行中的一起清）后，
  `清除已完成不会误伤运行中的任务` 立刻变红；恢复后 8/8 绿

#### 真窗口复验

- 工具栏右侧「任务」入口就位（任何 pane 都看得见），面板空态显示
  「暂无后台任务」且「清除已完成」正确禁用
- 表预览导出成功后，面板里出现该条：`✓ export.csv` + 目录·格式 + `完成 · <路径>`；
  表预览顶部同时有「已导出 1 行 → …」
- 慢导出（`SELECT SLEEP(10)`）失败时，面板里那条显示 `✗` + **红字原因** ——
  面板不只是「成功列表」，失败同样看得见

#### 面板顺手暴露了一个**永久性**缺陷：断流后所有导出永久失败

第二次导出报「导出子进程通道未就绪（ExportHub 未连接），导出没有启动」。
这次面板有价值的地方在于：它**把失败连同原因一起呈现出来**，而不是像以前那样
一句话都没有 —— 于是这条缺陷第一次是可诊断的。

根因是两个状态会**不一致**：

| 时刻 | `isRunning` | `commandObserver` |
|---|---|---|
| 正常 | true | 非空 |
| hub 流断开后（`onError`） | **true**（子进程确实还活着） | **null** |

而 `ensureSubprocessRunning` 的判据是 `if (!isRunning)` —— 断流后判据**恒为真**，
「建流」分支永远被跳过，`startExport` 拿到 null observer 直接返回 false。
**它永远不会自愈，重启应用才恢复。**

修法三处：

1. `ExportProcessManager.hasUsableChannel`（= 进程在 **且** 流在），
   `ensureSubprocessRunning` 改用它 —— 通道不在就重建（进程还在就只重连）。
2. `onError` / `onCompleted` 里**释放旧 channel** —— 它已经废了，
   而重建时会新建一个；不关就是每次断流漏一条连接池。
3. 父进程日志也落盘到 `engine/build/libs/export-manager.log`。
   之前只有子进程有文件重定向，而**断流恰好发生在父进程这一侧** ——
   日志正好在盲区里，排查时只能看到子进程的「一切正常」。

#### 这条回归测试踩了两个坑，都值得记

**① 一开始测错了对象。** 第一版用 `ExportHandler.executeAsSubprocess` ——
那是**子进程侧**入口，直接调 `ExportEngine.export`，**根本不碰 `ExportProcessManager`**。
于是它永远成功、0.09 秒跑完，判据写对写错都是绿的。测「建流判据」必须走
**父进程侧**的 `executeInMainProcess`。

**② 单例状态污染。** `ExportProcessManager` 是单例，测试类里其它用例起子进程、
结束时 `stop()`；而 `stop()` 在 `_isRunning` 已是 false 时会**提前 return、
不清 `commandObserver`**。所以用例必须先显式 `stop()` 再摆漂移态，
否则执行顺序一变就**走不到前提**，测试还是绿的。

**③「挂起 + 按序号放行」扛不住并发。** 造「一个已完成 + 一个运行中」时，
第一版让引擎挂起、由测试按「第 0 次 `handle()` 调用」放行。但 `state.startExport`
是**异步**的（`scope.launch` 里才发请求），两次调用的到达顺序不保证 ——
单跑绿，**全量并发下第二次先到、放错了那一个**，`awaitSettled` 等 15 秒超时。
改成「第一次调用正常完成、第二次永不收口」，测试**串行**发起并等第一次收口，
顺序就由测试自己定死，完全不需要关于到达顺序的假设。

靠 `simulateStreamLostWhileProcessAlive()` 这个仅测试钩子摆出漂移态 ——
那个状态是 `onError` 的自然产物，正常路径下无法从外部构造，
而它恰恰是缺陷的触发条件。用反射改 private 字段既脆弱又绕过类型检查。

#### 验证（自愈用例）

- `ExportPipelineIntegrationTest` 新增 2 项：`hasUsableChannel requires both the
  process and the stream` + `流丢了之后下一次导出必须自愈而不是报通道未就绪`
- **变异验证**：把判据写回 `if (isRunning) return` 后，自愈用例变红，报的正是
  真窗口上那句 `导出子进程通道未就绪（ExportHub 未连接），导出没有启动`；
  恢复后修复版连跑 2 次全绿
- ⚠️ 前一版这条测试**变异下也是绿的** —— 就是上面那两个坑导致的。
  写完变异没变红时，正确反应是怀疑测试本身，而不是相信「大概是环境问题」

### 9.19 右下角通知中心：把散在五处的错误收成一条时间线

任务面板解决的是「后台任务怎么样了」，但用户还会问另一类问题：
**「刚才那个连接为什么失败了」「我那条 SQL 报了什么」「导出的文件在哪」** ——
这些在任务面板里一个都看不到（它只管后台任务），而它们散落在五个互不相干的地方。

#### 三个设计决定

1. **屏级共享，不是 per-sheet** —— 连接 A 的导出失败、连接 B 的 SQL 报错，
   按连接分开就断了「刚才发生了什么」这条线。所以 [main.kt] 里 `remember` 一个
   [NotificationCenter] 传给所有 `DatabaseBrowserState` 与屏本身。
   ⚠️ 屏里**不能另建一个**：那会出现「任务通知进 A 面板、报错进 B 面板」，
   用户会以为通知丢了。

2. **错误靠屏级「对账」而不是逐点埋点** —— 报错散落在
   `ConnectionStatus` / `TablePreviewTab.error` / `SqlSheet.error` /
   `generateError` / `errorMessage` 五处。每处埋一次推送，等于把
   「什么算一次错误」的判断散到五个文件里。改成用一个 `derivedStateOf` 算出
   **这一帧的错误指纹集合**，与上一帧比对：新出现的推一条，这一帧没有的**从去重集合移除**。

3. **去重是这里最要紧的一条** —— 连接失败是**持续状态**，不是一次事件：
   它出现在**每一次重组**里。没有去重就会在几秒内刷出几十条一模一样的通知，
   把真正值得看的那条挤出去。
   但去重不能做成「一辈子只报一次」：错误消失（改好密码重连成功）之后再次失败，
   用户是关心第二次的 —— 所以配了 `forgetSourcesNotIn` 让来源能「重新变得可报」。
   指纹用 `标题|详情` 而不只是 key：`Table 'a' doesn't exist` 变成
   `Table 'b' doesn't exist` 是**新的一件事**，必须报。

#### 任务通知怎么推

- 单条导出：`startExport` 成功/失败各推一条
- **多语句导出：按批次汇总成一条**，不逐条推 —— 一次导 20 条推 20 条会把面板刷爆，
  而用户真正想知道的只是「这批成了几条、哪几条挂了」（逐条明细在任务面板里）。
- 部分失败必须**说清是部分**（`导出部分失败 · 3/5 个文件`）：只报成功数
  会让用户以为全好了。

#### 入口与面板

右下角状态栏最右端，铃铛 + 未读数，与堆内存同行右对齐 —— 常驻、不抢视线，
又在「发生了什么」最该被看见的角落。面板与内存详情面板**同级**、同样锚在状态栏正上方
（画在 20dp 高的状态栏内部会浮出父级边界，点击**穿透**到下方表格行）。

每条给**完整原文不截断**：引擎错误常是 `ORA-00933: table or view does not exist`
这类，省略号一截断就失去可诊断性。

⚠️ 面板**不给「取消任务」** —— 同任务面板，取消链路不可靠（§9.15）。

#### 真窗口复验（1440×900，真 MySQL）

| 走查项 | 结果 |
|---|---|
| 右下角入口 | 铃铛就位于堆内存同行右端；无未读时不显示数字 |
| 错误通知 | 执行 `SELECT * FROM definitely_not_here_12345` → 徽标 `1`，面板里 `✗ SQL 执行失败` + **引擎原文完整**（`Table 'sundays_probe.definitely_not_here_12345' doesn't exist`）+ 时间戳 |
| 打开面板 | 未读自动清零（打开本身就是一次确认，不逼用户逐条点） |

#### ⚠️ 真窗口当场抓到：**去重完全没生效**

连点三次「执行 SQL」（同一个错误）后，徽标是 **2** 而不是 1。

根因是**两边算的 key 不是同一个**：

| 用途 | 实际算出来的 key |
|---|---|
| `pushOnce` 的指纹 | `标题|详情` |
| `forgetSourcesNotIn` 收的 | `mapValues { … }.keys` → `sql:连接id:SQL 1` |

两者对不上 ⇒ 每一轮对账都把所有来源 `forget` 掉、再当成**新的**重推一遍。
去重形同虚设，而屏里那个 `derivedStateOf` 测不到 —— 于是它一路活到真窗口才现形。

修法：把「这一帧有哪些错误」抽成纯函数 [collectErrorSources]，
**指纹在这里算一次，推送与遗忘共用同一个 key**。顺带它可单测。

#### 第二个缺陷：key 里必须带**错误文本**，不只是结构标识

写完测试立刻红了：`错误文本变化视为新的一件事` —— `expected:<2> but was:<1>`。

同一个 SQL sheet 里 `Table 'a' doesn't exist` 变成 `Table 'b' doesn't exist` 时，
结构标识（`sql:连接id:SQL 1`）没变 → 去重命中 → **用户看到的还是上一次的错误，
而界面上早就换了**。

于是 key = `结构标识#错误文本的哈希`。用哈希而不是原文：key 会进内存集合，
原文可能几百字还带换行；哈希撞上的表现是少报一次通知，不会误报或报错。

这两条都是**同一类错误**：在同一个地方算了两遍「什么算同一次」。
凡是要「按来源去重」的地方，这个值必须只有一个产地。

#### 一个测试自己也踩的坑

`failedSheet` 最初同时设了 `status = FAILED` **和** `sqlSheets.error` ——
那是**两个**错误来源，于是断言「1 条」拿到 2 条。
屏级对账收的是**所有**来源，测试里随手多设一个字段就多一条通知。
提醒：造这类全局状态的测试，字段要一个一个设。

#### 验证

- `NotificationCenterTest`（9 项）+ `ErrorReconciliationTest`（4 项）
- **变异验证 1**：`pushOnce` 去掉去重 → `不同来源各报一次` 变红
  （`expected:<2> but was:<3>`，正是「同一来源被重复推送」）
- **变异验证 2**：`keyOf` 退回不含错误文本 → `错误文本变化视为新的一件事` 变红
- 真窗口复验：徽标计数与去重（发现并修掉上面那个 bug）

### 9.20 树节点展开时显示字段信息

表节点展开后原本只有「索引 / 外键」两行。用户展开一张表，八成是想知道**它有哪些列、
都是什么类型**，于是把字段加进来并**排在最前**（先看列，再看约束）。

#### 数据来源与结构

走 `TABLE.LIST` 的 **`column_list`** 分支（`TableColumnListRequest`）——
⚠️ 注意 `TableRequest` 的 oneof 里 `list` 是**表列表**、`column_list` 才是**列列表**，
两个字段名只差一个前缀，写错编译期发现不了，只会拿到「空列表」的假成功。

新增 `DatabaseBrowserState.ColumnInfo` 作为 UI 侧展示形态，**不直接存 proto 的
`ColumnDef`** —— 那是引擎的传输结构。隔一层以后引擎加字段不会波及 UI，反过来 UI 想改
展示也不必动 proto 依赖。字段列表存**独立的** `_tableColumns` 而不复用
`_tableObjects`（后者是 `Map<kind, List<String>>`，塞进去就只剩名字了）。

#### 三处细节

1. **`typeWithSize` 不能无条件加括号。** proto 的 `ColumnDef.size` 是 `int32`，
   **未指定时是 0** —— 于是 `TEXT` 会变成 `TEXT(0)`，一个不存在的类型，
   用户看了会以为这张表有问题。`ColumnInfoDisplayTest` 钉住这一条。
2. **约束标记不混进类型文本。** `VARCHAR(64) PK` 读起来像「这是一种新类型」。
   `PK` / `AI` / `非空` 跟在类型后面分开写，扫一眼就能区分「这是什么」与
   「有什么限制」。且**只在不可空时**标「非空」—— 可空是绝大多数列的默认，
   写出来全是噪音。
3. **展开箭头的无障碍描述要跟着改。** 原来写「展开 orders 的索引与外键」，
   展开后冒出几十个字段却毫无预期。改成「字段、索引与外键」。

#### 真窗口当场抓到：「(无)」重复出现

```
字段：
  id      INT(10)    [PK] [非空]
  note    VARCHAR(64)
  (无)          ← 不该在这里
索引：PRIMARY
```

原因是 `ColumnRowGroup` 第一版收的是 `content: @Composable ColumnScope.() -> Unit`，
空判断靠一个 `var any by remember { mutableStateOf(false) }` —— 而 lambda 内部
**没有任何地方**把它置 true，于是走 else 分支就必然打印一行「(无)」。

改成让分组**自己拿列表**渲染：「有没有内容」和「渲染内容」在同一个作用域里，
中间不再有那个观察不到的开关。这类「用一个状态位代表『子组件渲染过没有』」的写法，
状态位与实际渲染分居两处时几乎必然出错。

#### 顺带修的：表级对象失败不再静默

`loadTableObjects` 原来只在 `resp.success` 时写结果，失败**什么都不做** ——
于是「请求成功但结果是空的」和「请求失败」在界面上长得一模一样，
用户看到的是「(无)」，以为这张表本来就没有索引 / 字段。
现在新增 `_tableObjectError`，失败就地留引擎给的原文（逐类显示，与库级对象同一套思路）。

#### 验证

- `ColumnInfoDisplayTest`（5 项）：带长度拼类型 / 长度为 0 不加括号 /
  字段名与类型独立 / 约束不混进类型 / 默认可空
- 真窗口复验（真 MySQL，展开 `sundays_xdb.probe_shared_name`）：
  `id INT(10) [PK] [非空]`、`note VARCHAR(64)`、索引 `PRIMARY`、外键 `(无)`，
  且修掉了重复的「(无)」
- 全量 `:engine:test :shared:jvmTest :desktopApp:test`


### 9.21 PostgreSQL 全功能走查：三个只有真库才暴露的缺陷

对 `192.168.1.5:5432`（探针 PG）做了一次完整 GUI 走查，
发现**三个互相叠加的缺陷**，每一个都需要真实 PG + 真实窗口才暴露。

#### 缺陷一：用户库里把整个系统目录当成用户的表

展开任意 PG 业务库，底下第一屏是

```
pg_aggregate / pg_am / pg_amop / pg_attrdef / pg_attribute /
pg_auth_members / pg_authid / pg_available_extension_versions / …
```

真正的业务表被挤到 60 多行之后。

**根因**：`PostgreSQLDialect.listTables` 在 `schema` 为空时走
`table_schema = ANY(current_schemas(true))` —— 那个 `true` 是 `include_implicit`，
而 **`pg_catalog` 正是隐式搜索路径的第一项**。实测：

```
current_schemas(true)  = {pg_catalog, public}
current_schemas(false) = {public}
```

致命的一环是 `information_schema.tables` **并不只暴露用户表**：PG 的系统目录表在里面
同样以 `BASE TABLE` 出现（实测目标库 **64 张系统表 + 80 个系统视图**），`table_schema`
就是 `pg_catalog`。两个条件一叠加，整个系统目录进了结果集。

改成 `current_schemas(false)`。

⚠️ **没有**再补 `NOT IN ('pg_catalog', …)`：试过，是多余的，两半都是死代码 ——
`pg_catalog` 在 `(false)` 下压根不返回，而 `information_schema` 连 `(true)` 都不在。

> **为什么 MySQL 侧从来没出过这个问题**：MySQL 的系统对象住在*独立的 database*
> （`information_schema` / `performance_schema` / `mysql` / `sys`），在**库列表**那一层
> 就被挡掉了，根本到不了表列表。PG 的系统对象藏在 **schema 层**，库列表挡不住 ——
> 同一份「不算系统对象」的意图，两个方言的过滤点天然不在一层。

#### 缺陷二：从树上选的库根本没生效（修完缺陷一才显形）

修完缺陷一、所有库都显示 `(空)` —— 因为**缺陷一和缺陷二叠在一起**：
之前那 144 张系统表是 `postgres` 库的，现在它们被正确滤掉了，
底下露出来的空就是缺陷二。

**根因**：PG 的**数据库是连接的启动参数**，JDBC 没有 MySQL 那种 `USE`，
连接一建立就锁死在 URL 指的那个库。而 `DatabaseDialect.switchCatalog` 对 PG 是空实现
（当时的判断写着「PG catalog == database，连上就锁死，无意义」）。

那个判断只覆盖了「连接配置的库就是唯一要用的库」这一种情形，漏掉了 sundays 的实际用法：
**一个连接浏览多个库**（PG 方言 `supportsCrossDatabase = true`）。
于是用户点 `examquestions`，看到的却是 `postgres` 库的表 —— **静默读到错的库**。

修法：新增 SPI `DatabaseDialect.jdbcUrlForCatalog(jdbcUrl, catalog)`（默认原样返回），
由 `PoolManager.createDataSource` 在**建池时**把 URL 的库名段换掉。
PG 方言覆盖它；MySQL 走 `switchCatalog`（`USE`），那条路不受影响。

`catalog` 直接来自用户在树上点的那一层，不可信 —— 库名里含 `/` `?` `#` 空白 `%`
一律**拒绝**（会重解析 URL 结构、连到别的库），而不是拼接。

#### 缺陷三：列一多，整张数据网格塌成不可读的宽度

双击 `examquestions.biz_user`（**23 个字段**）：网格里所有列被压成窄竖条，
表头只剩第一个列名 `id`（后面 22 个列名被压没了），数值叠成 `2 1 1 8 0 0` 这样一团。
而点开右侧「详情」面板读数完全正常 —— **数据是对的，只是网格没法看**。

**根因**：浏览屏建列只给 `TableColumn(key, header)`，`width` 为 null，全走 `weight` 路径。
而 `weight` 是在**给定内容宽度内**均分的，`DataTable.contentWidthFor` 当时直接取视口宽：

```
视口 ≈ 650dp ÷ 23 列 = 每列 28dp   // 宽不过两个字符
```

修法：给加权列一个**最小宽度下限** `MIN_WEIGHTED_COLUMN_WIDTH = 100.dp`，
列一多就让内容宽于视口、触发横向滚动。

> **为什么既有测试一直是绿的**：`TableColumnAlignmentTest` 的 12 列**全是 `width = 140.dp`
> 定宽**，走 `Modifier.width(...)` —— 显式宽度，下限根本管不着。
> 现有测试**测不到**浏览屏实际使用的那条路径（全是 weighted 列）。

#### 验证

- `PostgreSQLSystemObjectTest`（5 项，真 PG）：系统表不进列表 / `information_schema` 的表不进列表
  / 非 public schema 的处理 / SCHEMA.LIST 干净 / 显式 schema 分支干净
- `PostgreSQLJdbcUrlTest`（13 项，纯函数）：换库名段 / 参数逐字保留 / IPv6 / 无库名补段 /
  空 catalog 不动 / 非 PG URL 不动 / 切到同一库逐字不变 / 6 种危险库名一律拒绝
- `PostgreSQLCrossDatabaseTest`（4 项，真 PG）：URL 指 A 选 B 时表列表来自 B /
  A 独有的表不混进来 / 同名表读到 B 的内容 / A 本身仍正常
- `TableWeightedColumnMinWidthTest`（2 项）：23 个 weighted 列能横滚 / 4 列时不多滚
- **变异验证**（逐条确认测试有牙齿）：
  - `current_schemas(true)` → 「系统表混入」红
  - 硬编码 `= 'public'` → 「search_path 里的多个 schema 都可见」红
  - 列全部 schema → 「不在搜索路径里的 schema 只在显式点名时可见」红
  - 断开 `PoolManager` 的 `jdbcUrlForCatalog` 接线 → 3 条红，其中
    「静默读到了 postgres 的同名表」**精确复现 GUI 上的现象**
  - 去掉加权列下限 → 「23 列的表没有横向可滚量」红
- 真窗口复验：展开 `sundays_smoke` 列出 4 张业务表且对象组干净（无 pg_* 噪音）；
  `examquestions` 列出 `biz_user`/`t1`/`t2`/`t3` + 各自的触发器与函数
- 全量 `:engine:test :shared:jvmTest :dialect-postgresql:test :desktopApp:test`

#### 顺带记下两个**没有**修的观察

1. **`timestamptz(35)` 的长度是假的** —— 字段列表把 PG JDBC 的 `COLUMN_SIZE` 直接当长度显示，
   而 `timestamptz` 的 `COLUMN_SIZE` 是 35（不是 6）。`varchar(50)` 之类是对的，
   所以单看它不像 bug，但用户会当成 `timestamptz(35)` 读。
2. **一行里混着两个 schema 的表** —— `search_path` 有多个 schema 时，
   `TABLE.LIST` 是不带 schema 前缀的裸表名，同名表会**互相盖住**。
   这属于「多 schema 导航」整体没做完（UI 只展示 search_path 命中的一层），
   要动就是一次结构性改动，不该混在这次修复里。
### 9.22 导出通道：漂移态下「永远不自愈」的死角

不是走查发现的 —— 是 §9.21 改完跑全量回归时**稳定复现**的红条，
用「回退我的改动再跑一遍」证明与本次改动无关之后才动的手。

#### 症状

`ExportPipelineIntegrationTest` 的「流丢了之后下一次导出必须自愈」稳定红，
耗时 **30.14 秒**（同类的另外 5 条都 < 2 秒），报错：

```
漂移态下应当重建流并成功导出，而不是「通道未就绪」：
error='导出子进程通道未就绪（ExportHub 未连接），导出没有启动'
```

`engine/build/libs/export-manager.log` 里那三行说明了一切：

```
[export] 等待 hub 端口 50099 就绪超时（30000ms，observer=false）
[export] 通道未就绪，命令没发出去 id=r-exp-1（isRunning=true observer=false）
```

**注意后面完全没有「子进程已启动」** —— 它根本没去重启，只是死等。

#### 根因：判据问错了问题

`ExportHandler.ensureSubprocessRunning` 写的是

```kotlin
if (!ExportProcessManager.isRunning) { ExportProcessManager.start(jarPath) }
ExportProcessManager.awaitHubReadyOrReportFailure()
```

`isRunning` 的语义只是「**曾经**拉起过一个子进程」。子进程**自己崩掉**时
（OOM、驱动炸、被外部杀掉），标记还留在 true 上 —— 于是：

- 「能不能发命令」→ `hasUsableChannel` 判 false ✅（§9.x 修的就是这个）
- 「要不要重启」→ `isRunning` 说「在，不用重启」❌ **问错了问题**

结果漂移态下走的是「不重启、只等端口」那条路，等的是一个
**已经没人监听的端口**：白等满 30 秒后报「通道未就绪」，**永远不会自愈**。

> **教训**：「上次拉起时它在不在」和「它现在还在不在」是两个问题。
> 前者能挡住重复拉起，后者才能决定要不要重启 —— 两者都不能省。

修法：`ExportProcessManager` 加 `isProcessAlive`（判据是 `Process.isAlive`），
`ensureSubprocessRunning` 改用它，并在重启前先 `stop()`
（`start()` 的 CAS 要求 `_isRunning == false`，陈旧标记还占着 true 就拉不起来；
对已死进程 `stop()` 是安全 no-op —— SHUTDOWN 无人接收、`destroyForcibly()` 打在已退出进程上无害）。

修完那条用例 **30.14s → 2.787s**：从「白等 30 秒后失败」变成「2.8 秒自愈成功」。

顺带修正了 `simulateStreamLostWhileProcessAlive()` 的文档：它造的其实是
**更糟的一档** —— 调用前测试刚 `stop()` 过，**子进程是真的没了**，只是标记被强行按成 true。
所以它模拟的正是「子进程自己崩掉后的真实形态」。

#### 验证

- `ExportPipelineIntegrationTest` 6/6 全绿
- **变异验证**：把判据改回 `isRunning` → 立刻复现 **30.163s + 同样报错**
- 全量 `:engine:test :shared:jvmTest :dialect-postgresql:test :desktopApp:test`

### 9.23 顺带修掉的一个测试脆弱点：`resolveSchema()` 拿「第一个 schema」

`DialectSmokeTest.resolveSchema()` 原本取 `SCHEMA.LIST` 结果的 `firstOrNull()`。

`SCHEMA.LIST` 按 `nspname` 排序，而**用户自建的 schema 完全可能排在 `public` 前面** ——
库里只要有一个叫 `alpha` 的 schema，它就会被选中，于是库级对象查询
（视图 / 索引 / 外键）全打到**另一个 schema** 上，冒烟报出来的是
「视图列表为空」，而根因跟视图半点关系都没有。

真实踩到过：`PostgreSQLSystemObjectTest` 往同一个库建了 `probe_ns` / `probe_hidden`
（字母序 p-r-o-b < p-u-b），`DialectSmokeTest` 的 S3 立刻变红。

两处都修：

1. `resolveSchema()` 改为**点名 `public`**（忽略大小写），没有才退回第一个；
2. 两个新增的 PG 测试在 `@After` 里**把自己建的 schema / 表删掉** ——
   `sundays_smoke` 与 `postgres` 是**共享固定库**，不是它们私有的，
   留着只会给后来的人制造「这库里怎么有张没人认领的表」。

> **教训**：往共享夹具里加东西的人有责任收尾，而**依赖共享夹具的人**
> 也不该假设「列表的第一个就是我要的那个」——
> 前者让后者永远绿，后者让前者一改就炸。
> 两边都得修，才不靠运气。

变异验证：手工建一个 `aaa_temp` schema（排在 `public` 前），
`resolveSchema()` 改回 `firstOrNull()` → S3 立刻复现红；改回点名 `public` → 绿。

### 9.24 已发现但根因未确证：表头只显示第一个列名

§9.21 修掉「列塌成竖条」之后，网格能看了，但**表头仍只有一个 `id`**：

| | 表头 | 表体 |
|---|---|---|
| 修列宽之前 | 只有 `id` | 挤成一团 `2 1 1 8 0 0` |
| 修列宽之后 | **仍然只有 `id`** | `10 \| user_10 \| 13651640951 \| …` 清晰可读 |

表体 5 列清清楚楚，表头其余 22 个列名**不显示**。
用户按列名对不上号 —— 对数据库工具来说这就是读错数据。

#### 已经排除的两个方向（都做过变异，都证伪了）

**方向一：「`weight` 在无界宽度里分不到空间，表头列全塌成 0 宽」** ❌

假设：`TableHeader` 的 `width(contentWidth)` 排在 `horizontalScroll` **外面**，
于是 `weight` 的测量环境是无界的。把 `width` 挪到滚动层**内侧**再测 ——
表头各列的语义宽度**两种顺序下都 > 0**，真窗口里表头**照样只有一个列名**。
假设不成立，**改动已回退**（`DataTable.TableHeader` 现在只剩一段说明这个方向被证伪的注释）。

**方向二：「列名节点没被渲染」** ❌

第一版断言写的是「23 个列名的语义节点都在」——
**它在缺陷版本上也是绿的**：列宽塌成 0 时 `Text` 节点**依然存在于语义树**
（只是宽度为 0），`fetchSemanticsNodes()` 按文本照样查得到。

第二版改成「第 2~6 列的语义宽度 > 0」—— **也照样绿**，
因为 `SemanticsNode.size` 量的是**布局尺寸**，与「是否落在视口内、用户看不看得见」无关。

> 两次都栽在同一件事上：**量了一个对现象不敏感的量**。
> 第一次量「节点在不在」，第二次量「节点有多宽」——
> 两次都没量「**用户看不看得见**」。写断言前先问一句：
> **这个量真的会因为我要抓的缺陷而变化吗？**

#### 还剩什么没排除

表头与表体**共用**同一个 `hScroll` 与同一个 `contentWidth`（`DataTable` 调用点），
所以「表头滚动位置与表体不一致」这个方向还没查 ——
若两者实际滚到的偏移不同，表头看到的就可能是内容的最左边那一段，
而那一段恰好只有第一个列名可见。

⚠️ **下一步别急着改**：先在真窗口里横滚一下，看表头是**跟着动**还是**不动**。
一动就是滚位一致，问题在渲染；不动就是滚位真的不一致。
这一句观察就能把搜索空间砍掉一半，比读十遍 `TableHeader` 都有用。

要钉住它得先有一个**能量到「在视口内且可见」**的判据 ——
Compose UI 测试这边目前没有现成好用的（`boundsInRoot` 会不会被滚动容器裁剪钳住
还需要实测确认），与其写一条骗自己的绿断言，不如先把这句提醒留在
`TableWeightedColumnMinWidthTest` 的类注释里。

### 9.25 真窗口抓到：向导建出来的 PG 连接 100% 连不上

用户报「我自己测试发现 pg 连不上」。这次**严格只走 GUI**（不许改配置文件），
从「新建连接」一路点到「测试连接」。

#### 根因：向导把凭据塞进了 PG 不认的 URL userinfo 段

`JdbcUrl.buildJdbcUrl` 里 MYSQL 与 POSTGRESQL **共用同一个分支**，
生成 `jdbc:postgresql://user:pass@host:5432/db`。
拿 PG 驱动直接验（42.7.11）：

```
URL: jdbc:postgresql://postgres:666666@192.168.1.5:5432/postgres
  parseURL[PGHOST]   = postgres:666666@192.168.1.5   ← 整段被当成主机名
  parseURL[PGDBNAME] = postgres
  parseURL[PGPORT]   = 5432
  （没有任何 PGUSER —— 凭据压根没被识别）
  cause[1] java.net.UnknownHostException: postgres:666666@192.168.1.5
```

驱动按最后一个 `:` 切端口、其余整段当 host，于是这串"主机名"永远解析不出来，
外层再包成一句**无信息量**的「尝试连线已失败」。
换句话说：**PG 连接不是"可能连不上"，是必然连不上**，
且错误信息里看不出任何「用户名格式不对」的线索。

同一个探针里 `jdbc:postgresql://192.168.1.5:5432/postgres` + 独立凭据 → `CONNECT OK`（PG 18.4）。

#### 为什么只有 PG 中招

| 方言 | 驱动认不认 URL userinfo | 后果 |
|---|---|---|
| MySQL（Connector/J） | **认** | 照常带凭据，一直是好的 |
| PostgreSQL（`org.postgresql.Driver`） | **不认** | 必连不上 |

**MySQL 从来没有暴露过这个缺陷** —— 不是它更健壮，是它的驱动支持这个形状。

#### 为什么测试全绿、手写配置却是好的

这一条是本次最该记的：

- 所有 PG 测试的 URL 都来自 `SmokeTarget.PostgresSmoke.urlFor()`，
  写的是 `jdbc:postgresql://192.168.1.5:5432/$database` —— **无 userinfo**，天然正确；
- 此前自己调试时是**直接往 `connection.json` 里塞配置**的，
  手写的也恰好是无 userinfo 的形状。

于是「探针数据的形状」与「真实路径产出的形状」**不一致**，
真实路径上那个必然失败的形态，从头到尾没有任何一个测试碰过。

> 用户坚持「不要直接改配置文件，要走 GUI」是对的：
> **手写配置注入恰好绕开了向导的 URL 折算，也就绕开了缺陷本身。**
> 探针数据的形状必须与真实路径一致，否则覆盖的是自己造的形状，不是产品。

#### 修法（三处，缺一不可）

1. **`buildJdbcUrl` 拆分支**：MySQL 保留 userinfo，PG 恒不带。
   凭据改由已有的两条通路走 —— 引擎 `PoolManager` 把 `config.user/password`
   单独传给 HikariCP；`PersistedConnectionConfig` 也把两者**独立于** `jdbcUrl` 存盘。
   顺带收益：URL 不再承载明文口令，而建池日志 `url=$resolvedJdbcUrl` 会把 URL 打进日志。
2. **`withParsedJdbcUrl`（原 `applyUrl`）只在 URL 真的写了 userinfo 时才覆盖凭据**。
   否则 PG 的 URL 默认没有凭据，用户只要在 URL 框里补个 `?sslmode=require`、
   点一下输入框，用户名密码就被**静默清空**，而 `canProceed` 仍然成立 ——
   配置照存不误，直到连库才报「认证失败」。
   判据必须是 `UrlParts.hasUserInfo`，不是「`username` 是否为空」。
3. **加载时 `normalizeJdbcUrl`**：缺陷期间存下的每条 PG 连接，URL 里都带着那截 userinfo。
   只改 `buildJdbcUrl` 的话**老配置依然是坏的**，修复对老用户等于没生效。
   归一化只发生在内存里，不改磁盘格式、不升版本号。

配套新增：`UrlParts.hasUserInfo` 字段（把「URL 没写凭据」与「用户要清空凭据」分成两件事）。

#### 变异验证（5 组，逐条打红）

| 变异 | 打红的用例 |
|---|---|
| 去掉 `credPartFor` 的方言守卫（PG 凭据重回 URL） | 4 个：PG 无 userinfo / PG 往返 / 切方言 / 新建 PG 落盘 |
| 凭据无条件覆盖（去掉 `hasUserInfo` 判据） | `editing the url box does not wipe credentials…` |
| 加载不调 `normalizeJdbcUrl` | `legacy postgresql url is normalized on load…` |
| 粘贴时不调 `normalizeJdbcUrl` | `pasting a mysql shaped postgresql url…` |
| 加载时改成**从 URL 反解**凭据 | 2 个（老配置 + 新建）—— 守住「归一化不许偷走凭据」 |

顺带钉住一条**安全前提**：凭据不写进 URL，会不会因此丢密码？
不会，而且有测试守着 —— `PersistedConnectionConfig` 的 `username`/`password` 独立于 URL 存盘、
加载时也只从这两个字段取。少一个字段，用户的 PG 连接就会从「连不上」变成
「连上但认证失败」—— 换了个错法而已，同样致命。

#### 真窗口复验：全新 user home，全程只点鼠标

- 第 3 步底部的 **JDBC URL 实时显示 `jdbc:postgresql://192.168.1.5:5432/postgres`**（旧版这里会是 `jdbc:postgresql://postgres:666666@…`）
- 第 4 步点「测试连接」→ **「连接成功!」**
- 保存 → 连接 → 对象树列出 `examquestions` / `postgres` / `sundays_smoke`，
  展开 `examquestions → biz_user` 正常列出 23 个字段（`id int8(19) PK 非空` …），**无 pg_\* 系统对象**
- 落盘核对：`jdbcUrl` 无 userinfo，`username` / `password` 独立成字段

全量回归：**944 项 / 0 失败 / 8 跳过**（此前 935）。

#### 附带记下两个「工具自己骗人」的时刻

都是**先怀疑产品、再怀疑工具**才没走歪：

1. **Windows 滚轮方向与直觉相反**：`WHEEL_DELTA` **正数向上、负数向下**。
   最初 `click-window.ps1` 直接传 `Scroll * 120`，于是「向下滚」其实在往上滚；
   而内容本来就在顶部，**看起来就像 `verticalScroll` 死了**，
   一度差点当成产品缺陷去改布局。压小窗口、换成负值后才滚得动。
2. **`gui-probe.ps1` 会把窗口最大化**，于是它报的窗口尺寸（1938x1048）
   与 `click-window.ps1` 的坐标系对不上 —— 混用两点全空。
   `fill-fields.ps1` 改用绝对坐标、全程不动窗口尺寸，就是被这个坑出来的。

> 两次的共同点：**「界面没反应」有三种可能 —— 产品不响应、输入没送到、量的地方不对**，
> 而它们长得一模一样。判据只能是**换一个独立通道再验一次**，
> 不能靠同一套工具换个参数重试。

### 9.26 对象树右键：复制名称 / 引用名 / 建表 DDL

用户要的是「在树里右键就能把名字和 DDL 拿走」，以便粘进 SQL 编辑器。
这一节记的是**三件容易做错、且错了都不报错**的事。

#### 一、引用名必须按方言给，不能一律双引号

`SqlIdentifier` 是前端侧的第二份映射（不复用引擎的 `DatabaseDialect.quoteIdentifier`，
理由见 `shared/ARCHITECTURE.md` §4.7）。两条硬规则：

1. **MySQL 用反引号。** ANSI 双引号在 MySQL 里默认是**字符串字面量**，
   写成 `"biz_user"` 会被当成 `SELECT 'biz_user'` 而不是标识符。
2. **判据不是「该方言有没有独立 schema 层」，而是「这个 database 能不能写进引用」。**

> ⚠️ **第 2 条的第一版是错的，被用户实测推翻** —— 详见 §9.28。
> 原判据写的是「PG 里 `examquestions` 是 catalog，所以绝不能写进去」，
> 于是 PG 一律给两段式 `"public"."biz_user"`。真实情况是 PG **支持**三段式
> `"examquestions"."public"."biz_user"`，只是要求库名等于当前连接的库。

两段式那个坑本身**仍然成立**，但它证明的结论比当初以为的弱得多：
`"examquestions"."biz_user"` 报的是 **42P01**（被解释成「名为 examquestions 的 schema
下的 biz_user」），而三段式是**另一条路径**，压根不经过那个解释。
「这一种写法失败」推不出「catalog 这一级不能出现」。

| 方言 | `tableRef` 产出 | 依据 |
|---|---|---|
| MySQL（无 schema，有库名） | `` `sundays_probe`.`biz_user` `` | database 就是命名空间本身 |
| PostgreSQL（库 == 当前连接的库） | `"examquestions"."public"."biz_user"` | catalog 这一级可写 |
| PostgreSQL（库 != 当前连接的库） | `"public"."biz_user"` | 写了会被 PG 拒绝，退回恒可用形态 |
| H2 | `"PUBLIC"."USERS"` | catalog 与 schema 是两层 |
| 两者都空 | `"tbl"` | 最小可用形态 |

#### 二、**故意**不复制 `H2Dialect.quoteIdentifier` 的 uppercase

引擎那边 `quoteWith(identifier.uppercase(), '"')` 是有理由的：它拿到的名字**可能来自用户输入**，
不猜 H2 的折叠规则就命中不了。而复制场景的处境相反 —— **树上的名字就是库里真实存着的名字**：

| 建表写法 | 树上显示 | 折叠后的引用 | 对不对 |
|---|---|---|---|
| `CREATE TABLE USERS` | `USERS` | `"USERS"` | ✅ |
| `CREATE TABLE "users"` | `users` | `"USERS"` | ❌ **指向另一张表** |

剪贴板里放一个看着像对、实际查错表的引用，比不给引用更糟。
所以 `SqlIdentifierQuoteTest` 里的 H2 断言写的是 `"users"` 而不是 `"USERS"` ——
**这条断言就是防止有人日后「顺手对齐」把它改回 uppercase 的。**

#### 三、DDL 前端拼不出来，只能走引擎

`dialect.getCreateTableDDL` 五个方言没有共性可归纳：
PG 回填主键 / UNIQUE / CHECK，SQLite 直接读 `sqlite_master.sql` 的**原文**，MySQL 拼 ENGINE 与字符集。
前端自己拼一份的话，五种里总有几种对不上，而**对不上的 DDL 是静默的** —— 粘进 SQL 编辑器才炸。

发的是 `Category.TABLE` + `Action.GET_DDL`，且**先等 `resolveSchemas` 完成**再发，
否则 schema 还没落到状态里，`TableGetDdlRequest.schema` 会是空的。

> 协议上有个坑：`TableRequest` 的 oneof 字段名 `get_ddl` / `column_list` / `list`
> **只差一个前缀**，写错编译期发现不了（都编译得过）。只有跑起来看行为才知道。

#### 菜单挂在哪、什么条件下出现

- 菜单状态存在 `SchemaTreePanel` 这一层（**不是**每个节点）：LazyColumn 回收 item 会把菜单一起带走
- **DDL 项只在「表节点且非字段节点」出现**：字段目标的 `table` 也非空，
  第一版只判 `target.table != null`，于是字段上冒出了「复制建表 DDL」——复制出来是整张表
- 菜单项**先 `menu.dismiss()` 再执行动作**：`DropdownMenu` 只认 `onDismissRequest`，不认菜单项的 `onClick`
- `DropdownMenu` 用 `expanded = target != null` 且**始终留在组合里**，不写成 `if (target != null)`
- 右键与左键不冲突：`onRightClick` 挂在 `clickable` 前还是后都无所谓 ——
  `clickable` 的 tap 检测只认主键，右键是次键，压根不满足 tap 条件
  （这一条 KDoc 原本写反了，**靠变异测试没变红才发现**，见下）

#### 变异验证（6 组，逐条打红）

| 变异 | 打红的用例 |
|---|---|
| MySQL 也用双引号 | 8 项 |
| 所有方言都用库名限定（不分 schema） | 2 项 shared + 8 项 state |
| 去掉 `target.column == null` 判据（字段也显示 DDL） | `字段节点没有 DDL 项` |
| `clipboard(text) \|\| true`（把失败当成功） | 2 项 |
| 菜单项点了不 `dismiss()` | 3 项（`等待超时：菜单项 xxx 之后菜单关闭`） |
| `tableRef` 里 schema 判据优先级改成先 database | shared + state 多项 |

**一处变异没能打红，暴露的是文档写错了而不是测试弱**：
把 `onRightClick` 与 `clickable` 的顺序颠倒，「右键不应顺带打开预览」这条**没有变红**。
查下来原因是 `clickable` 的 tap 检测只认主键，右键压根进不了那条路径 ——
于是 KDoc 里「必须在 clickable 之前，否则右键会顺带打开预览」的说法是**错的**，已按实现改正。
> 这条的教训与 §9.25 那两条「工具自己骗人」同源：
> **变异没变红时，先怀疑「被变异的东西根本不在这条路径上」，再怀疑测试。**
> 但反过来，测试没变红**不等于**可以不查 —— 查完才知道是文档错，不是实现错。

#### 真窗口复验：全新 user home，真 PostgreSQL

- 快速连接 → PostgreSQL → `192.168.1.5:5432` / `examquestions` / `postgres` / `666666`
  → 「测试连接」**连接成功!** → 连接（PID 2720，窗口 1152x720）
- 树列出 3 个库，展开 `examquestions` → `biz_user` / `t1` / `t2` / `t3`
- 右键 `biz_user` → 菜单弹三项：**复制名称 / 复制引用名 / 复制建表 DDL**
- 点「复制引用名」→ 剪贴板读回 **`"public"."biz_user"`** ✅
- 点「复制建表 DDL」→ 剪贴板读回 **`CREATE TABLE "biz_user" (…23 字段…)`** ✅
- 通知中心铃铛未读数 1 → 2（两条 SUCCESS）

截图在 `build/tmp/shots/`：`r01`（菜单弹出）、`r02`（复制引用名后菜单已关闭）、
`r04`（复制 DDL 后菜单已关闭）。

#### 新增探针：`desktopApp/tools/right-click.ps1`

`click-abs.ps1` 只发左键（`MOUSEEVENTF_LEFTDOWN/UP`），对 `onRightClick` 那条
`buttons.isSecondaryPressed` 检测链**完全无效** —— 脚本跑成功、菜单就是不出来，
和 §9.25「工具自己骗人」是同一类坑。故新写一个：`MOUSEEVENTF_RIGHTDOWN = 0x0008` /
`RIGHTUP = 0x0010`，绝对坐标右键后跟一张整屏截图。

剪贴板也**不是**靠读脚本 stdout 验的：`ClipboardWriter` 走 AWT `SystemClipboard`，
与 PowerShell 的 `Get-Clipboard` 是**同一个系统剪贴板**，可以直接读回 —— 这条是真值，不是替身。

全量回归：**984 项 / 0 失败 / 8 跳过**（此前 944，新增 40 项）。

> 第一轮曾出现一条 `ConnectedSourceEndToEndTest [MySQL] Communications link failure`，
> 单跑通过、第二轮全量通过 → 环境偶发，与本次改动无关。**单跑通过不足以判定偶发**，
> 要看第二轮全量是否还复现。

### 9.27 右键菜单「打开位置不对」：两层根因 + 换掉 `DropdownMenu`

用户实测报「右键菜单的打开位置不对」。这一节记的是**为什么第一版的修法推理正确、却仍然错**。

#### 现象：危险的地方在于它不报错

右键树里第 5 行的 `postgres`（屏幕 y≈559），菜单顶边出现在 **y≈145**，偏上约 414px，
且正好盖在 `examquestions` 那一行上。

复制功能**照常工作、剪贴板确有内容**——用户盯着 `examquestions` 点「复制名称」，
剪贴板里进来的是 `postgres`。不抛异常、通知照样弹 SUCCESS，
任何自动断言都抓不到它，只能靠真窗口看。

#### 第一层根因：节点局部坐标被当成了屏幕坐标

`onRightClick` 回调里的 `PointerInputChange.position` 是**相对挂 `pointerInput` 的那个节点**的坐标，
第一版原样透传给 `DropdownMenu(offset = …)`。节点在树里第 N 行，局部 y 只有几十像素，
却被当成了屏幕坐标，于是菜单恒定弹在面板顶部附近。

#### 第二层：`DropdownMenu.offset` 的基准不是「调用它的节点」，是「菜单底边」

第一版的修法按「offset 相对宿主布局节点」的教科书理解写成 `at + nodeOrigin − hostOrigin`。
真窗口实测菜单位置**仍然偏上**，偏的量恰好等于 `hostOrigin.y`
（实测：减去时菜单顶边在屏幕 y≈165；不减去时 y≈298，正好差 133 = hostOrigin.y）。

到这里已经能判定「基准不是宿主节点」，但还差最后一步：**它到底是什么？**
于是做了一次基准实验 —— 给菜单项挂 `onGloballyPositioned`，量三个节点的真实渲染位置
（1152×720 窗口，density 1.25；菜单项高 60px=48dp，divider 1dp）：

| 节点 | `offset.y` | 宿主 y | 按「顶边对齐」预测的菜单底 | 实测菜单底 |
|---|---|---|---|---|
| `postgres`（2 项，无 divider） | 519.5 | 133 | 652.5 | 650 |
| `biz_user`（3 项，有 divider） | 254.5 | 133 | 387.5 | 385 |
| `t1`（3 项，有 divider） | 289.5 | 133 | 422.5 | 420 |

模型完全吻合：**菜单底 = 宿主y + `offset.y` − 菜单高**。也就是 `DropdownMenu` 把 `offset.y`
解析成「菜单**底边**相对宿主的偏移」。

- 2 项菜单「看起来对齐」纯属**菜单高度与 offset 巧合相等**
- 3 项菜单偏 −57.5px，恰好是一个菜单项（60px）+ divider（1px）
- 这个量依赖**运行时才知道的菜单高度** → **无法用补常数修正**

#### 结论：换掉 `DropdownMenu`，改用 `Popup` 自己定位

`Popup` 的 `offset` 是文档写明的「相对 **parent** 左上角」，可以直接定位：

```
Popup.offset = clamp(右键点 in root) − 宿主 Column 原点
```

配套的四点，缺一个就出别的毛病：

1. **`Popup` 没有 `expanded`** —— 可见性就是「在不在组合里」，
   所以是 `if (target == null) return`，而不是原先的 `expanded = target != null` + 始终组合
   （那是 `DropdownMenu` 为了播放关闭动画才需要的，换型后那个理由不存在了）
2. **`onDismissRequest` 必须显式传**：`Popup` 的默认值是**空 lambda**（与 `DropdownMenu` 不同）。
   漏传的话点菜单外 / 按 Esc 只会关掉弹层、`menu.target` 还在 →
   组合里 Popup 一直在，于是「下一次右键别的节点，弹的还是上一次的菜单且位置不动」。
   这正是真窗口走查当场抓到的现象
3. **存 `LayoutCoordinates` 活对象而不是缓存 `Offset`**：树节点展开/滚动后，缓存的坐标可能已过期；
   右键当场读 `positionInRoot()`
4. **全程用 px 做位置运算**：`offsetInRoot` 是指针事件的原始像素值，混进 Dp 会差一个 density

#### 边界钳位

菜单可能比窗口还高（三个菜单项 + 贴底右键），不钳位的话下半截跑出屏幕、用户点不到最后一项。
用 `LocalWindowInfo.containerSize` 钳位。`MENU_ESTIMATED_HEIGHT = 220.dp` 是**估算值**——
真实高度要布局后才知道，而钳位发生在布局之前，所以宁可偏大：贴底时多收一点，而不是截掉菜单项。

#### UI 测试里量不到菜单位置 —— 这不是「没测」，是「测不了」

Popup 内节点的 `getUnclippedBoundsInRoot()` 在 `runComposeUiTest` 下**与真实渲染对不上**：
诊断值 98 / 实测 161 / 反推基准 63，既不是 root(0) 也不是宿主(106)。

所以 `rightClickOn` 里的位置断言被**主动删掉**，而不是「补一条断言蒙混过去」。
硬写只会得到恒绿或恒红的假信号。位置契约由两处承担：
**状态机测试**（`offsetInRoot = at + nodeOrigin` 的换算公式）+ **真窗口走查**。
focusable `Popup` 的外部点击 / Esc 同理，量不到。

#### 顺带修掉一个既有测试的竞态（修的是测试，不是实现）

`clickMenuItem` 原来只等 `recorder.written.isNotEmpty()`，而「复制建表 DDL」要发引擎往返，
读 `.last()` 拿到的是**上一次的** `"PUBLIC"."USERS"`。改为先记 `val before = recorder.written.size`
再等 `size > before`。

> `Popup` 版无动画、菜单出现更快，反而让这个竞态**稳定暴露**出来。

#### 变异验证（2 组，全部打红）

| 变异 | 打红的用例数 |
|---|---|
| `offsetInRoot = at`（去掉节点原点换算） | 3 项 |
| `offsetInRoot = at + nodeOrigin − Offset(0, 133f)`（模拟减宿主原点） | 5 项 |

#### 真窗口复验（Popup 版，PID 14624，窗口 1152×720，density 1.25）

| 场景 | 右键点 | 菜单顶边 | 结论 |
|---|---|---|---|
| 表节点 `biz_user` | (150,294) | ≈295 | 对齐（此前偏 62）|
| 库节点 `postgres` | (150,559) | ≈562 | 对齐；菜单**正确只有 2 项**（无 DDL）|
| 字段 `l2` | (100,402) | ≈402 | 对齐；菜单为「复制名称 / 复制表.字段引用」两项、无 DDL |
| 点菜单外 (800,600) | — | — | 菜单消失，`onDismissRequest` 生效 |
| 关闭后再右键 `postgres` | (150,559) | ≈562 | 位置跟随新节点，项数换为 2 项 |
| 点「复制表.字段引用」 | — | — | 剪贴板读回 **`"public"."t1"."l2"`** ✅ |
| 贴底 `sundays_smoke` | y≈775 | ≈630 | 被钳位到窗口内，未截掉菜单项 |

截图在 `build/tmp/shots/`：`v01`–`v11`（修复前复现）、`z01`–`z03`（基准实验）、
`h01`–`h08`（菜单位置诊断）、`q01`–`q12`（Popup + `onDismissRequest` 终验）。

#### 全量回归：**989 项 / 0 失败 / 8 跳过**

| 模块 | 用例 | 失败 | 跳过 |
|---|---|---|---|
| desktopApp | 408 | 0 | 2 |
| shared | 268 | 0 | 1 |
| engine | 300 | 0 | 5 |
| dialect-postgresql | 13 | 0 | 0 |

> `--rerun` 必须**紧跟每个任务**写（`:engine:test --rerun :shared:jvmTest --rerun …`）。
> 只在命令行末尾写一次，它只作用于最后一个任务，另外三个模块会静默复用旧结果 ——
> 看起来跑了全量，其实其中三个是上一次的缓存。

**第一轮全量红了 9 条，全在外部数据库集成测试里，与本次改动无关**：

- `MySQLRoutineTriggerQueryTest` → `CommunicationsException: Communications link failure`
- `PostgreSQLCrossDatabaseTest` / `PostgreSQLSystemObjectTest` → `PSQLException: FATAL: the database system is starting up`

三类单跑全过、端口也通 → 环境偶发（PG 服务当时在重启）。
按 §9.26 那条教训，**单跑通过不足以判定偶发**，所以又跑了一轮全量确认不再复现。
树菜单相关的 29 项（`TreeContextMenuUiTest` 5 + `TreeContextMenuStateTest` 24）在第一轮
就是绿的，与本次改动直接相关的代码没有任何问题。

#### 教训

**UI 层断言了一条它根本量不到的属性时，你拿到的不是保护，是一个恒绿 / 恒红的假信号。**
量不到就要说量不到，别假装覆盖了 —— 否则下一个人会以为「这里有测试守着」。

#### 已知未验证：`ContextMenu.kt` 里另两处 `DropdownMenu`

`shared/.../ui/ContextMenu.kt` 的 `ContextMenuState<T>` 被 `DataTable` / `CodeEditor` 共用，
它们的菜单**同样用 `DropdownMenu`** 且同样吃 `offset` 语义，因此**可能有同一个缺陷**。
本轮没有真窗口验证过，不下结论 —— 按同样的方法（菜单项挂 `onGloballyPositioned` + 整屏截图）
去量一次就能定论。

### 9.28 PG 复制的引用少了一级：上一轮把「跨库不支持」误读成「catalog 不能写」

用户报「pg 复制的引用少了数据库的那一级」。当前实现给的是 `"public"."t1"`，
用户期望 `"examquestions"."public"."t1"`。

**结论：用户是对的，上一轮的判断是错的。** 而且错得很有代表性 —— 它是靠「读文档 + 推理」
下的结论，从没上过真库。

#### 错的推理长什么样

§9.26 当时的推理链是：

1. PG 里 `examquestions` 是 **catalog**，`public` 才是 **schema** —— 对
2. 写成 `"examquestions"."biz_user"` 会被解析成「名为 examquestions 的 schema 下的表」—— 对
3. 所以**判据**是「这个方言有没有独立 schema 层」，PG 有 → 不给 database 限定 ← **错在这**

第 2 步到第 3 步是一次**非法跳跃**：拿「一种两段式写法会失败」推出了「三段式也不行」。
而 PG 恰恰支持三段式，只是走的是另一条解析路径。
**「这种写法失败」不等于「这一级不能出现」** —— 这个坑和 §9.26 那条
「右键顺序变异没变红，暴露的是文档写错了」是同一类：推理链本身没人验。

#### PG 到底支持什么

PG 源码 `RangeVarGetCreationNamespace` 里判据**只有一条**：

```c
if (newRelation->catalogname) {
    if (strcmp(newRelation->catalogname, get_database_name(MyDatabaseId)) != 0)
        ereport(ERROR, ... "cross-database references are not implemented");
}
```

即 **库名必须等于当前连接的库**，仅此而已。这既不是 PG 18 才有的特性，
也不是「PG 不支持 catalog」—— 它是 SQL 标准的 `catalog.schema.table` 形态，
PG 一直都认，只是拒绝**真的**跨库。

真库实测（PG 18.4 / `examquestions`）：

| 连到 | 语句 | 结果 |
|---|---|---|
| `examquestions` | `FROM examquestions.public.t1` | ✅ |
| `examquestions` | `FROM "examquestions"."public"."t1"` | ✅ |
| `examquestions` | `UPDATE examquestions.public.t1 SET …` | ✅ |
| `examquestions` | `SELECT "examquestions"."public"."t1"."l2" FROM …` | ✅ 四段式字段 |
| `examquestions` | `FROM examquestions.pg_catalog.pg_class` | ✅ |
| `examquestions` | `FROM EXAMQUESTIONS.public.t1` | ✅ 未加引号会先小写折叠 |
| `postgres` | `FROM examquestions.public.t1` | ❌ `cross-database references are not implemented` |
| `sundays_smoke` | `FROM examquestions.public.t1` | ❌ 同上 |

对照两段式的失败形态（**注意 SQLState 不同**）：

| 语句 | 结果 |
|---|---|
| `FROM "examquestions"."biz_user"` | ❌ **42P01** `relation "examquestions.biz_user" does not exist` |
| `FROM nosuchdb.public.t1`（在本库） | ❌ **0A000** `cross-database references are not implemented` |

42P01 与 0A000 的区别就是上一轮推理失败的现场：只看到前者，
没去问「那三段式呢」。

#### 实现

判据是「**这个库是不是当前连接的库**」，而不是方言，也不是服务端版本 ——
因为真库实测证明三段式**所有 PG 版本都支持**，加版本判据反而会平白砍掉 PG 17 的用户。

| 位置 | 改动 |
|---|---|
| `SqlIdentifier` | 新增 `path(dialect, vararg parts)`（逐段引用、跳过空段）；`qualified` 改走它；`tableRef` 加第 5 参 `databaseIsCurrent: Boolean = false` |
| `treeReferenceText` | `dbIsCurrent = target.database.equals(currentConnection?.database, ignoreCase = true)` |

两个设计要点：

- **默认 `false`** —— 漏传参数只会退化成两段式（恒可用），不会静默产出三段式。
  默认值往**安全**的那一侧倒。
- **`ignoreCase = true`** —— PG 对**未加引号**的库名会先小写折叠（实测
  `FROM EXAMQUESTIONS.public.t1` 成功），而我们复制出去的是**带引号**的（保留大小写）。
  配置里写 `ExamQuestions` 而库里是 `examquestions` 时，不该判成「不是当前库」而白白丢掉这一级。

**`currentConnection.database` 拿得到吗？** 一度怀疑拿不到 —— v2 持久化格式
（`ConnectionStorage.toPersisted`）只存 `jdbcUrl` + 凭据 + meta，**没有 database 字段**。
但加载时 `PersistedConnectionConfig.toConnectionConfig`（`ConnectionStorage.kt:91`）
会 `parseJdbcUrl(normalizedUrl, dialect)` **从 URL 反解出 database**。
真窗口验证截图 `n02` 上「连接信息 → 数据库: examquestions」就是这条路径的产物。
**探针 connection.json 里没有 `database` 字段也不影响**。

#### 测试

`SqlIdentifierQuoteTest` 新增 / 改名 4 条：

- `postgres writes all three levels when the database is the current one`
- `postgres drops the catalog level when the database is not the current one`
  （并断言**不显式传参时默认就是退化那一侧**）
- `postgres three part form needs both database and schema`
- `never treats the database name as a schema` ← **原名** `tableRef never uses database as
  qualifier on dialects with a separate schema layer`。那个名字本身就是错误判断的产物：
  它宣称「有独立 schema 层的方言绝不用 database 限定」，而 PG 正是反例。

`TreeContextMenuStateTest` 新增 6 条（三段式表 / 四段式字段 / 节点库≠当前库时退回 /
大小写不一致仍认当前库 / 未绑定连接不崩 / MySQL 不受影响 / H2 不多写一级），
并给 `state()` 助手加了 `database` 参数以便构造「当前连接的是哪个库」。

变异验证 2 组全部打红：

| 变异 | 打红 |
|---|---|
| `dbIsCurrent = true \|\| …`（去掉当前库判据） | **5 项** |
| 三段式的两个 `isNotBlank` 改成 `isNotEmpty` | **1 项**（`postgres three part form needs both database and schema`） |

#### 真窗口走查：复制出来的到底能不能用

只看剪贴板不够 —— 用户要的是「能粘进 SQL 编辑器直接跑」。所以最后一步是把
树上复制到的引用**原样拼进查询、在应用自己的 SQL 工作台里执行**：

- 右键表 `t1` → 复制引用名 → 剪贴板读回 **`"examquestions"."public"."t1"`** ✅
- 右键字段 `l2` → 复制引用名 → 剪贴板读回 **`"examquestions"."public"."t1"."l2"`** ✅
- SQL 工作台粘 `SELECT "examquestions"."public"."t1"."l2" FROM "examquestions"."public"."t1" LIMIT 1;`
  → **执行成功，「查询结果 · 1 行」，`l2 = 1`** ✅（截图 `n10_exec_ok.png`）

> 中间踩了一次自己的坑：先写了 `SELECT l2 FROM "examquestions"."public"."t1"."l2"`
> —— **FROM 后面不能写四段**，报 `improper qualified name (too many dotted names)`。
> 四段式只出现在**字段位置**（SELECT 列表 / WHERE / 投影），
> FROM 位置永远到表为止（最多三段）。这不是产品的问题，是我探针 SQL 写错了。
> 记在这里是因为它很反直觉，值得钉住。

截图在 `build/tmp/shots/`：`n02_selected`（连接信息里 database 已从 URL 解析出来）、
`n03_browser`、`n05_menu_t1`、`n07_menu_l2`、`n09_exec`（故意写错的四段 FROM）、
`n10_exec_ok`（正确的四段字段引用执行成功）。

#### 全量回归：**1000 项 / 0 失败 / 8 跳过**（此前 989，+11）

| 模块 | 用例 | 变化 |
|---|---|---|
| desktopApp | 415 | +7（`TreeContextMenuStateTest` 新增 7 条） |
| shared | 272 | +4（`SqlIdentifierQuoteTest` 新增/改名 4 条 + `path` 1 条） |
| engine | 300 | — |
| dialect-postgresql | 13 | — |

**第一轮红了 2 条，均与本次改动无关**，且都在「跟改动完全不相干的地方」：

| 失败用例 | 报错 |
|---|---|
| `BackgroundTaskLifecycleTest` | `AssertionError: 刚登记就该是运行中` |
| `TreeContextMenuUiTest :: 右键字段 - 给表点字段引用且没有 DDL 项` | `等待超时：语义树里出现库节点 …（30003ms）` |

两个单跑全过 → 判定为 flaky。第二轮全量全绿确认不再复现。
后者是**已记录在案**的既有脆弱点：该类每条用例都新建 `IdbEngine` + H2 内存库 + 建表，
全量并发下这些资源是共享的，类 KDoc 里写着「拆成 10 个用例时实测每轮都有 1~2 条被掐断」。
本轮**没有**往 `TreeContextMenuUiTest` 加用例（新增的 7 条全在状态机层），
所以资源压力没变 —— 这也正好说明那个脆弱点还没解决，只是这轮没踩到。

> 按 §9.26 那条教训，单跑通过不足以判定偶发，所以跑了两轮全量。

#### 教训

**关于方言语法的任何「不能」，都该去真库上问一句，而不是靠推理。**
这一轮的推理链每一步单看都对，只有真库能证伪最后那一步 ——
而那恰好是唯一影响用户可见行为的一步。
和 §9.25「工具自己骗人」、§9.27「UI 层量不到就别硬断言」同源：
**凡是「我以为」，都要找一个能真值的地方去撞一下。**

