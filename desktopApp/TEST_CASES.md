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