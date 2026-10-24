# 数据分析模式 —— 设计文档

> **状态**：设计冻结待评审 · **v1.11** · 2026-10-25
>
> **v1.11 相对 v1.10 的修改**（**「添加数据源」对话框的两处收拾**）：
>
> 1. **样式跟着全局走** —— 对话框不再用 Material3 的 `AlertDialog`
>    （那在复古档里仍是一块**现代圆角卡片**，满屏 Win 风格中间浮一块 Material ✗），
>    改用仓库那套 Win 控件（`WinSurface` / `WinButton` / `WinTextField` / `WinDivider`）
>    自拼 —— 与 `desktopApp` 的**导出对话框**同一套做法 ✓。
> 2. **文件要有系统选择器** —— 路径框旁边有「浏览」，点它调
>    **系统**的文件 / 文件夹选择器（`NativePathPicker`）。
>    文本框**仍然保留**：网络路径、UNC、无头环境下手动输入是唯一走得通的路。
> 3. **格式能手选** —— 文件夹没有扩展名，「这个文件夹里是 csv 还是 parquet」
>    自动推断不出来，得让用户选。
>
> **v1.10 相对 v1.9 的修改**（**分析模式能自己加数据源了**）：
>
> 1. **树的头上多一个「+」** —— 打开分析模式**专属**的「添加数据源」对话框。
>    **不共用**浏览模式的「添加连接」：那加的是 `connection.json` 里的**连接**
>    （浏览模式的清单），这加的是**工作区 DuckDB 里**的**数据源**
>    （分析模式自己的清单）—— 两套会话、两份清单。
> 2. **两类数据源**：
>    - **远程数据库** → 先用**对应驱动预览**它的库与表（可多选也可单选），
>      再用 **DuckDB 官方扩展接入**（扩展优先，走不通就退回 JDBC）；
>    - **文件 / 文件夹**（parquet / csv / Excel / jsonl）→ 直接用 DuckDB 的
>      `read_parquet` / `read_csv_auto` / `read_json_auto` / `read_xlsx`，
>      在工作区里建一个**视图**（文件改了，查询跟着变）。
> 3. **源直接进 DuckDB 的树** —— 每个源在**工作区里占一个 schema**，
>    树的根于是变成「工作区的各个 schema」（`main` + 每个源一个），
>    形状仍然是「库 → 表 → 字段」。
> 4. **v1.7 记下的那个缺口补上了** —— 「拉取远端表在界面上没有入口」：
>    入口就是树头上那个「+」（加数据源 → 勾选表 → 直接接进工作区）。
>    而「拉取远端表」那条老路（落到 `main` 的 `staging_*` 表）**仍在**，
>    只是不再是界面上的主路径了。
> 5. **协议加了两个字段**（`AnalysisPullRequest`）：落点 schema 与访问方式
>    （扩展优先 / 纯 Java）—— 细节见
>    [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §3.5。
> 6. **§9.1 画面 ② 与 §9.3 组件对照表同步**。
>
> **v1.9 相对 v1.8 的修改**（**两棵树合成一棵**）：
>
> 1. **分析与浏览共用一个树组件** —— 分析页的树不再自己画，
>    改用浏览模式那套（提到 `:shared`，落 `tree/ObjectTree.kt`）。
>    理由：同一个概念（「库 → 表 → 字段」）有**两份实现**时，
>    改一处就忘了另一处 —— 而「两棵树长得不一样」本身就是缺陷：
>    用户的肌肉记忆会在两个模式之间失效。
>    **以浏览模式那套为准**：它信息更全（字段带类型与约束标记）也更成熟。
> 2. **分析页的树现在能展开到「字段」** —— 以前到表为止，
>    用户看不到「这张表有哪些列、都是什么类型」，
>    而这正是写分析 SQL 时最需要的对照信息。
>    展开一张表 → 走 `ANALYSIS.LIST_COLUMNS` 路由 → 与浏览模式**同一条**引擎路由。
> 3. **点字段 = 把「表.字段」加进编辑器**（点表 = 把表名加进编辑器）。
> 4. **§6.3 的「分析模式自建树」那条决定作废** —— 现在的做法恰好相反：
>    树**上移**到 `:shared`，两边共用（§6.4）。
> 5. **§9.1 画面 ② 与 §9.3 组件对照表同步**。
>
> **v1.8 相对 v1.7 的修改**（**分析工作台的顶栏整条去掉**）：
>
> 1. **「▶ 运行」挪进编辑器的工具条**，跟「格式化」**同一行** —— 它俩都是
>    **对编辑器里这段 SQL 的动作**，分开放两行就让人多扫一眼。
> 2. **「⊘ 只读」徽标去掉**，于是**那条只挂着徽标与运行按钮的工具条**整条
>    都不剩了 → **一整行空白**（原本就不含别的内容）一并去掉。
> 3. **§6.4 的组件清单从 6 个改成 5 个**（`ReadOnlyBadge` 连同它的两个
>    i18n 键一起删掉）—— 远端恒为只读这件事**没变**：保证在引擎的 L3 / L4
>    闸门与方言级只读上（§3），徽标从来只是**表达**。
> 4. **§9.1 画面 ② 与 §9.3 组件对照表同步**。
>
> **v1.7 相对 v1.6 的修改**（**分析工作台的树只显示 DuckDB 工作区**）：
>
> 1. **§9.1 画面 ② 与 §9.3 组件对照表同步** —— 分析工作台的左树
>    **只有本地工作区**（+ 里面已经拉进来的表）；**保存的连接配置不进这棵树**。
>    理由：连接配置**属于连接管理器** —— 同一份配置在浏览模式、连接管理器、
>    分析树里各列一遍，就会变成**两份可能对不上的真相**（改了名字只更新一处、
>    两处列的表不一样）。分析树是「**我的工作区里有什么**」的视图。
> 2. **连带影响（明确记下，不是遗漏）**：远端表不再出现在树上，
>    于是**「拉取远端表」在界面上没有入口**了 —— 拉取能力
>    （引擎的 `ANALYSIS.PULL_TABLE` 路由、会话的 `pull()`）**仍在**，
>    只是没有界面触发它。下一步要么给拉取另开一个入口
>    （在连接管理器里选一张表 → 拉进工作区），要么先让它只服务
>    「活体联邦」的方向。
>
> **v1.6 相对 v1.5 的修改**（**分析工作台去掉工具条上的返回按钮**）：
>
> 1. **§9.1 画面 ② 与 §9.3 组件对照表同步** —— 分析工作台的工具条上
>    **没有**返回按钮了，只有 `⊘ 只读` 徽标 + 运行 / 生成报表。
>    分析模式是一块**独立**的工作区（自己的会话、自己的工作区），
>    工具条上摆一个「←」会让人以为「退掉这个功能」，
>    而它的语义其实是「回到根」。
> 2. **连带**：§6.9（实现文档）里「入口只有一条，就得也只进得出」那条
>    判据随之作废 —— 分析模式**不再**提供回到连接管理的退路，
>    它就是进去做活的，做完关窗即可。
>
> **v1.5 相对 v1.4 的修改**（**启动首屏**：选一个模式进去）：
>
> 1. **新增「启动选择」屏（首屏）** —— 应用启动后先问一句「这次要干哪种活儿」：
>    「连接管理」（传统的连库与浏览工具）或「数据分析」（只读分析工作台，
>    **直接**进，不经过连接列表）。
>    - 为什么是**首屏**而不是顶栏上的 chip：这两种活儿是两套会话、两种心智模型，
>      多数时候用户心里是有答案的（要做分析就直接做分析）。
>      chip 是**常驻**的，等于每次都要重新决定一次；而「选模式」放在**进应用时**问一次，
>      问完一路走下去。
>    - 两个入口**各自都有内容等着**，都不是摆设：没有可分析的数据源时，
>      分析工作台会**说清**「没有可分析的数据源」并指路连接管理。
> 2. **§9.1 的画面清单与 §9.3 的组件对照表同步** —— 新增「启动选择」屏
>    （`StartScreen`：两张模式卡片）。
>
> **v1.4 相对 v1.3 的修改**（**顶栏去掉「连接管理 / 数据分析」两个 chip**）：
>
> 1. **§9.1 的画面 ① 与 ② 跟着改** —— 顶栏现在只留**品牌 + ⚙ 设置**：
>    - 「连接管理」是**首屏** —— chip 只是把当前项再显示一遍（切过去等于什么都不做）；
>    - 「数据分析」是**入口** —— 入口该待在用户真正需要它的地方，
>      现在在**连接管理的空闲面板**里（没选中任何连接时，右侧那一块本来就在问
>      「接下来做什么」）。
> 2. **分析工作区的工具条多一个「返回」（←）** —— 顶层 chip 去掉后若不给返回路，
>    用户从连接管理点进分析模式就**回不来了**，而「点进去就回不来」是这个仓库
>    最不该出现的东西。入口只有一条，就得也只进得出。
> 3. **§6.4 的组件清单与 UI 设计图同步**。
>
> **v1.3 相对 v1.2 的修改**（分析工作台**去掉「数据源」下拉框**）：
>
> 1. **§6.4-2 的分析工作台顶栏只剩 `⊘ 只读` 徽标 + 运行 / 生成报表** ——
>    「数据源」下拉框**去掉了**（UI 设计图同步改）。它是**纯装饰**：
>    拉取走的是**树上那张具体的表**（表节点自带 `sourceId`），
>    从不下拉框里取源 —— 那个 id 全程只有它自己在读。
>    而它带来的是**两份可能对不上的真相**：用户在下拉框里选了 A 源、
>    却在树上点了 B 源的表 —— 界面上「当前源是 A」，拉下去的却是 B 源的同名表，
>    而那**不会报错**，只是数据悄悄换了来源。
>    源只有一处能选：**左树**。
> 2. **§3.5 与实现文档 §6.6.4 的措辞跟着改** —— 「拉取要显式传源 id」这条判据
>    仍然成立（而且现在更彻底：连「当前源」这个状态都没有了）。
>
> **v1.2 相对 v1.1 的修改**（第 8 片：原生扫描器作为**可选加速**；
> 实现细节见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2.7）：
>
> 1. **§2.4 的方案表补上 E（原生扫描器）**，并**更正** A 的一处说法 ——
>    「DuckDB 的 extension 要联网下载，内网必挂」**是错的**：
>    `mysql_scanner` / `postgres_scanner` **静态链接在 JDBC 驱动里**
>    （`duckdb_extensions()` 的 `installed_from = core`）。
>    纯 Java 表函数当默认路径的真正理由因此变成**覆盖面**（任何有 JDBC 驱动的源）。
> 2. **原生那条路默认关**（`SUNDAYS_DUCKDB_NATIVE_EXT=1` 才开）——
>    两条路的**类型映射不同**，「装没装扩展」不该隐式决定用户拿到的类型；
>    走不通就**降级**回纯 Java，用户看到的仍是「拉取成功」。
> 3. **§11 的 M4 条目补上「第 8 片已落地」说明**。
>
> **v1.1 相对 v1.0 的修改**（拉取链路换成 DuckDB 联邦直查；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **§3.5 的路由表新增 `ANALYSIS.PULL_TABLE`** —— 拉取不再走
>    「远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`」，而是
>    **DuckDB 直接读远端的 JDBC 源**（联邦查询）：
>    `CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>`。
>    **没有临时文件、没有 CSV 的编码往返**。
> 2. **§6.4 的组件清单里 `PullStageIndicator` 改名 `PullProgressIndicator`** ——
>    联邦直查**没有阶段**可分（那三段是绕磁盘逼出来的），也没有「临时文件占多少磁盘」。
> 3. **§11 的 M4 条目补上「第 7 片已落地」说明**。
>
> **v1.0 相对 v0.9 的修改**（M4 第 6 片落地；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **M4 的第 6 片已落地 —— 数据源树与只读结果表** ——
>    `SourceTree`（工作区 + 远端源 → 命名空间 → 表，**懒加载**）、
>    右侧主区接上**编辑器 + 只读结果表 + 状态栏**、「▶ 运行」按钮、
>    点远端表**拉取**、点工作区的表**把表名加进编辑器**。
> 2. **§11 的 M4 条目补上「第 6 片已落地」说明** ——
>    M2 + M4' 的**六个分片至此全部落地**（见实现文档 §8 的提交切分表）。
> 3. **实现文档 §6.6 记下四处实现期决定** ——
>    工作区那一层**不摆命名空间**（DuckDB 只有一个 `main` schema）、
>    拉取要**显式传源 id**、树与展开态都归会话管、
>    结果表的只读判据必须是**结构的**（点一下看有没有输入框是假绿）。
>
> **v0.9 相对 v0.8 的修改**（M4 第 5 片落地；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **M4 的第 5 片已落地 —— 拉取链路与三段进度** ——
>    远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT` 的整条链路
>    （**零新协议**，复用既有两条路由）、
>    `PullStageIndicator`（三段 + 一行占用 + 终态行）、临时文件在
>    **成功 / 失败 / 取消**三条路径上都被清理。
> 2. **§11 的 M4 条目补上「第 5 片已落地」说明** ——
>    拉取终态是一个 `sealed`（`Done` / `Cancelled` / `Failed`）而不是三个可空字段；
>    取消**不是**失败，界面上分开说。
> 3. **实现文档 §6.3.1 记下一处实测抓出来的真 bug** ——
>    导出请求 id 必须在导出**开始之前**就定下来，
>    否则取消时叫停不了导出子进程，Windows 上留下删不掉的孤儿目录。
>
> **v0.8 相对 v0.7 的修改**（M2 分三片落地；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **M4 的第 4 片已落地 —— DuckDB 本地工作区与状态机** ——
>    `AnalysisSession`（与浏览模式**并列**的状态机）、工作区文件管理、
>    `staging_<源库>_<表名>` 的命名与清洗规则。
> 2. **§11 的 M4 条目补上「已落地」说明** —— 工作区落在 `~/.config/sundays/workspace.duckdb`，
>    跨会话常驻；**工作区可写、远端恒为只读**，两条方向相反的只读性各有一个执行点。
> 3. **实现文档 §6.1 的「应用数据目录」收敛为「应用配置目录」** ——
>    与 `connection.json` 同处，不另立一套目录规则
>    （「应用的文件都在哪」只该有一个答案）。
>
> **v0.7 相对 v0.6 的修改**（M2 分三片落地；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **M2 已落地（分三片）** —— 片1 **协议**（`Category.ANALYSIS` / `Category.REPORT`、
>    五个 Action、`ConnectionConfig.read_only`、`AnalysisRequest` / `AnalysisResponse`）、
>    片2 **只读闸门**（会话级 L4 + 方言级 L3，配两级回归测试）、
>    片3 **四条只读路由 + 顶层导航 + 只读徽标 + 能力位声明 + 24 个新文案键**。
> 2. **§3.5：四条 ANALYSIS 路由已落地** —— `LIST_SOURCES` / `LIST_TABLES` /
>    `LIST_COLUMNS` / `RUN_QUERY` 都在引擎里实现了；
>    `ANALYSIS.PULL_SAMPLE` **仍然不落地**（理由见下方说明，本版没有新增理由）。
> 3. **§13 的 Q3：MySQL / PostgreSQL 声明 `ANALYSIS_SOURCE` 已落地** —— 引擎侧真的声明了，
>    并由 `desktopApp` 的 `AnalysisCapabilityContractTest` 钉住「谁声明了什么」。
> 4. **§11：M2 补上「已落地」条目清单** —— 按三片逐条记，便于对着仓库核对。
>
> **v0.6 相对 v0.5 的修改**（M2 + M4' 实现期；实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)）：
>
> 1. **§5.5 与 §13 的 Q3：MySQL / PostgreSQL 也声明 `ANALYSIS_SOURCE`** ——
>    Q3 当初「不加」的理由是「L4 闸门从兜底变主力」，而 M2 正好把 L4 建起来；
>    补上它当时缺的那个前置条件后，加不加已不再是安全性问题。
> 2. **§3.5：`ANALYSIS.PULL_SAMPLE` 本轮不落地** —— 拉取要碰**两个**数据库
>    （远端读 + 本地写），而一个 `Request` 只有一份 `ConnectionConfig`；
>    按 §2.4 方案 C 拆成既有的 `EXPORT` + `IMPORT` 两次调用，零新协议。
> 3. **§11：M2 / M4 的本轮范围** —— 明确 M3 跳过、拉取源用现有 MySQL / PostgreSQL。
>
> **v0.3 相对 v0.2 的修改**（评审意见：*组件和控件也尽量复用，可以优化组件更加通用*）：
>
> 1. **新增「组件复用与通用化」整节（§6）** —— 给出三条复用判据、**零改动的直接复用清单**、
>    **5 项通用化改造**、**6 个确需新建的组件**（附「为什么现有的不够用」）。
> 2. **原 §6~§13 顺延为 §7~§14** —— 本节插在「连接管理器通用化」之后，
>    因为两者是**同一件事的两半**：连接管理器通用化管「**表单**怎么描述」，
>    本节管「**控件**怎么描述」。
> 3. **§9（界面设计）补「界面区域 ↔ 复用组件」映射**，并新增设计图第 6 画面
>    **「组件复用对照」**（[`ui-mockup.html`](./ui-mockup.html)）。
> 4. **§11（迭代）M1 扩容**：方言档位**注册表化**、前端方言元数据**收进一处**、
>    `WinDropdown` 补齐 —— 三项都是「先通用化再接方言」的一部分。
> 5. **§12 新增 R12**（组件复用退化）、**§13 新增 Q9**（要不要顺手上移 `desktopApp` 的 UI 组件）。
> 6. **顶栏右上角只留一个 ⚙ 设置入口**（§9.3）—— 去掉主题与语言两个入口，
>    与真实实现对齐（明暗与配色**只在设置页**切换，见 `main.kt` 的 KDoc）。
>
> **v0.2 相对 v0.1 的三处结构性修改**（评审意见：*设置复用，连接管理器复用；需要一个更通用的连接管理器*）：
>
> 1. **连接管理器与设置页改为复用**，不再各做一套（§1.3）。
> 2. **新增「连接管理器通用化」前置里程碑**（§5）—— 现有连接管理器是五种方言的专用表单，
>    Hive / Impala 一进来就撞天花板；先把表单改成**方言自描述**驱动，再接新方言。
> 3. **只读保证的主防线从「模式级」升到「方言级」**（§3）—— 连接管理器复用后，
>    同一个 Hive 连接会同时出现在两个模式里，只读若只挂在模式上就会被绕开。
>
> **配套**：[`ui-mockup.html`](./ui-mockup.html)（可交互 UI 设计图） ·
> [`theme-tokens.json`](./theme-tokens.json)（从真实主题源码抽取的色值快照）
>
> **本文档的判据**：所有「现状」陈述都指向仓库里的真实文件与行号；所有「设计」陈述都写清了
> 为什么这么选、以及被否掉的方案是什么。**没读过代码就别改本文** —— 与仓库其余文档同一条规矩：
> 具体契约在 KDoc 里，设计理由在这里。

---

## 0. 一页速览

| 项 | 结论 |
|---|---|
| **是什么** | 一个**独立于现有「连接管理 → 数据库浏览」流程**的新顶层模式。职责只有两件：**数据分析**与**制作报表**。 |
| **远程数据源** | **恒为只读**——而且是**方言级**保证：Hive / Impala 在引擎里**根本没有写入路径**，与从哪个模式进来无关（§3）。 |
| **计算引擎** | **默认 DuckDB**（本地嵌入式分析引擎），可选 **Hive** / **Impala**。 |
| **当前阶段交付** | **Excel 报表**（.xlsx）。 |
| **后续迭代** | 接入 **AI** 做智能分析与报表生成（见 §8）。 |
| **复用什么** | **连接配置 + 连接管理器 + 连接向导 + 设置页**（同一份 `connection.json`，同一个设置界面）。 |
| **组件** | **通用控件全部来自 `:shared` 的 `commonMain`**（`CodeEditor` / `DataTable` / `DragHandle` / `EmptyHint` / `WinControls` / `ContextMenuState` / `DangerousSql` …），**零改动**；本模式**只新增 5 个组件**，且全部落在 `:shared`（见 §6）。 |
| **不复用什么** | 浏览屏的 sheet 标签条 / `DatabaseBrowserState` / 对象树 / 预览标签页 / SQL 工作台**屏幕**。 |
| **前置条件** | **连接管理器必须先通用化**（§5）—— 否则 Hive / Impala 会把专用表单的 if-else 再加两轮。**组件侧同样要先通用化**（§6.3）：方言档位注册表化、前端方言元数据收进一处。 |
| **样式** | **完全复用**现有主题系统：5 套配色 × 明暗 2 档，一行新样式代码都不写（见 §9.1）。 |
| **引擎侧改动** | 复用已有的 `EXPORT.RUN_EXPORT` / `IMPORT.RUN_IMPORT` / `ExportFormat.EXCEL` 链路；**新增** `Category.ANALYSIS` / `Category.REPORT` 两条只读路由。 |
| **最大风险** | `hive-jdbc` 依赖树与仓库现有 `hadoop-common 3.5.0` 的版本冲突（见 §12 R1），必须先做 spike。 |

---

## 1. 定位与边界

### 1.1 这个模式是什么

「数据分析模式」= 一个**面向分析的独立工作台**。它回答的问题是：

> 「我有一堆只读的数据源（数仓、库表），我想**看看数据长什么样**、**算出点东西**，
> 然后**做成一份能发给别人的 Excel 报表**。」

它**不是**「浏览模式换个皮」。两者的产品意图根本不同：

| | 数据库浏览模式（现有） | 数据分析模式（新增） |
|---|---|---|
| 用户身份 | 库的管理者 | 数据的**消费者** |
| 核心动作 | 浏览 / 编辑 / DDL / 造数 | 拉取 / 聚合 / 出报表 |
| 连接权限 | 可写（`readOnly` 是**可开关**的） | 对**远程数据源恒为只读**（方言级，无开关可关） |
| 数据位置 | 就在库里 | 远端只读源 → **本地物化** → 本地算 |
| 产出物 | 界面上的表格 | **一个 .xlsx 文件** |
| 连接配置 | `~/.config/sundays/connection.json` | **同一份**（连接管理器复用，见 §1.3） |
| 设置 | `SettingsScreen` | **同一个设置页**（新增「数据分析」分类，见 §1.3） |
| 工作台 | 浏览屏（对象树 + 预览标签页） | 分析工作区（SQL 编辑器 + 结果表 + 报表区块） |

### 1.2 明确不做的事（v1）

写下来是为了**挡住范围蔓延**，每一条都对应一次「顺手就做了」的诱惑：

- ❌ 不做图表 / 仪表盘 / BI 画布 —— v1 只出**表格型** Excel 报表。
  （理由：POI 的图表 API 极不稳定，且报表的价值在数据与结论，不在图。）
- ❌ 不做定时任务 / 报表订阅 / 邮件推送。
- ❌ 不做数据清洗 / ETL 编排（只做「拉取 + 查询」，不做管道）。
- ❌ 不做多用户 / 权限模型。
- ❌ **不做独立的连接管理器**，也不做独立的设置页 —— 一律复用现有的（§1.3）。
- ❌ **不在分析模式里提供任何写入能力**——包括「建临时表到远端」「往本地 DuckDB 写东西」之外的一切。
  （本地 DuckDB 工作区**可以**写：那是分析产物，不是数据源。这条边界很重要，见 §3.4。）
- ❌ 不把 AI 能力混进 v1 的交付范围（只留接口与入口占位，见 §8）。

### 1.3 与现有浏览流程的关系：**屏幕独立，连接与设置复用**

**复用（同一个东西，不做第二份）**：

| 复用什么 | 位置 | 复用方式 |
|---|---|---|
| 连接配置模型 | `shared/.../connection/ConnectionConfig.kt` | **直接用**，加 `authMethod` / `properties` 两个字段（§5.4） |
| 连接配置存储 | `shared/.../connection/ConnectionStorage.kt` | **直接用**，同一份 `~/.config/sundays/connection.json` |
| 连接管理器界面 | `shared/.../connection/ConnectionManagerScreen.kt` | **直接用**，按能力位分组显示（§5.6） |
| 添加 / 向导 | `shared/.../connection/AddConnectionDialog.kt` | **直接用**，但表单渲染改成读方言的 `connectionForm`（§5.3） |
| 设置页 | `shared/.../settings/SettingsScreen.kt` | **直接用**，新增一个「数据分析」分类（AI 配置 / 报表默认样式 / 输出目录） |

> 💡 **复用连接管理器的收益是实打实的**：用户在连接管理器里建一次 Hive 连接，
> 就能在浏览模式与分析模式里都用；不必配两遍、不必记住两套凭据。
> 代价是**连接管理器必须先通用化**（§5）—— 这是本设计里唯一的「必须先做的事」。

**不复用（各自一套）**：

| 不复用什么 | 理由 |
|---|---|
| `ConnectionSession` / `DatabaseBrowserState` | 那套状态机是「一个连接 = 一个**可写**浏览器」，与分析工作流不匹配 |
| sheet 标签条（多连接标签） | 分析模式的工作单位是「**一次分析**」，不是「一个连接」 |
| 对象树 / 预览标签页 | 分析模式的对象是「数据集 + 报表区块」，不是「库表 + 预览」 |
| SQL 工作台**屏幕** | 但**组件**复用：`CodeEditor` / `DataTable` / `DangerousSql` 都直接用（§10.1） |

**两个模式怎么共用一份连接？** 靠**能力位**筛选，不靠复制配置（§5.6）：

```
connection.json （一份）
   ├─ 可浏览的连接（声明了 BROWSE_SOURCE）    → 浏览模式看到
   └─ 可分析的连接（声明了 ANALYSIS_SOURCE）  → 分析模式看到
```

DuckDB 两组都在 —— 它既能当管理用的本地库，也能当分析引擎。

### 1.4 为什么连接管理器必须先「通用化」

**这是本设计唯一的硬前置条件**，理由不是洁癖，是**加 Hive/Impala 就会撞天花板**：

| 现状 | 位置 | 撞到什么 |
|---|---|---|
| `ConnectionConfig` 字段硬编码（host/port/user/password/database） | `shared/.../connection/ConnectionConfig.kt` | Hive 的 Kerberos principal、Impala 的 TPCD 租户、ZooKeeper 发现串 —— **无处安放** |
| 认证只有账密两种 | 同上 | 大数据环境常态是 **Kerberos**，不是账密 |
| 表单按枚举分支渲染 | `AddConnectionDialog` / `ConnectionWizardContent` | 每加一个方言就要改前端 if-else，方言一多必然失控 |
| 方言元数据**有两处** | 前端 `DialectType.supportedConnectionTypes` vs 引擎 `DatabaseDialect.connectionType` | 仓库已有 `DialectNameContractTest` 在防漂移 —— 说明这块**确实脆** |
| 连接类型只有 4 种 | `ConnectionType` | 没有「直接填 JDBC URL」的逃生舱；而引擎**已经支持**按 URL 前缀反查方言（`DialectLoader.getDialectByJdbcUrl`） |

反过来做（先加方言、后补通用化）会把 if-else 再加两轮，而这两轮里每一处都是**只有加方言的人才看得懂**的分支。所以顺序是：**先通用化 → 再接方言**（§11 迭代）。

---

## 2. 总体架构

### 2.1 一句话架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  分析模式（desktopApp: AnalysisScreen / ReportDesignerScreen）        │
│    ↑ 读同一份连接配置 + 同一个设置页                                   │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ EngineClient（既有接口，不改签名）
┌───────────────────────────▼──────────────────────────────────────────┐
│  引擎 :engine                                                       │
│    Category.ANALYSIS  ── 只读分析 SQL / 拉取样本                    │
│    Category.REPORT    ── ReportSpec(JSON) → .xlsx                    │
│    Category.EXPORT    ── 复用：远端 → CSV                            │
│    Category.IMPORT    ── 复用：CSV → DuckDB                          │
└────────────┬────────────────────────────────────┬────────────────────┘
             │                                    │
   ┌─────────▼──────────┐              ┌──────────▼──────────────┐
   │ 远程只读数据源       │              │ 计算引擎：DuckDB        │
   │ dialect-hive        │  拉取(CSV)   │ dialect-duckdb（复用）  │
   │ dialect-impala      │ ──────────▶ │ 本地工作区 workspace.   │
   │ ★ 只读方言：         │              │ .duckdb                 │
   │   写方法全部抛        │              └─────────────────────────┘
   │   UnsupportedOperation │
   └─────────────────────┘
```

### 2.2 三个角色，别混

| 角色 | 是什么 | 本期取值 |
|---|---|---|
| **计算引擎** | 跑分析 SQL 的地方 | **DuckDB**（默认）/ Hive / Impala |
| **数据源** | 数据**从哪来** | 远程只读 Hive / Impala / 本地文件 |
| **报表产物** | 数据**到哪去** | .xlsx |

> ⚠️ **「默认引擎为 DuckDB」的两种读法，本文选第二种**：
>
> - 读法 A：DuckDB 是「又一个可连的数据库」，和 MySQL 并列在连接列表里。
> - 读法 B（**本文采用**）：DuckDB 是**分析模式自带的计算引擎**，默认且常驻；
>   Hive / Impala 是**数据源**，需要时才连。
>
> 选 B 的理由：分析模式的产物是报表，报表要的是「能算」而不是「能浏览」；
> DuckDB 的向量化执行 + 零配置嵌入式正是为这件事准备的。若按读法 A，用户还得先手工建一个
> DuckDB 连接、再手工把远端数据灌进去——那不是「分析模式」，是「浏览模式 + 两次手工搬运」。
>
> 「可选 Hive 和 Impala」在读法 B 下的含义是：**这三者都可以作为计算引擎**
> （Hive 直接跑 HiveQL 出结果、Impala 直接跑 Impala SQL 出结果），
> 典型组合是「Hive 当引擎算 + Hive 当数据源查」或「Impala 当引擎算 + Hive 当数据源查」。

### 2.3 数据流（一期）

```
① 选远程表（只读浏览）      ② 拉取到本地         ③ 本地分析          ④ 出报表
   dialect-hive/impala   ──▶  DuckDB 工作区    ──▶  ReportSpec    ──▶  .xlsx
   listTables/listColumns     staging_<表名>        (标题/区块/列规格)   (POI)
   （只读 SPI）                （复用 EXPORT+IMPORT）  （可由人或 AI 生成）
```

**④ 的关键约束**：报表的每个数字都必须来自**一条用户看得见的 SQL**。
AI 也不例外——AI 只能产出 `ReportSpec`（含 SQL），不能直接产出文件（见 §8）。

### 2.4 拉取链路：为什么先走 CSV 中转

「远端 → DuckDB」有三条路：

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. DuckDB extension 直连** | DuckDB 的 `hive` / `httpfs` extension 直接读远端 | 无中转、快 | Impala **没有**对应 extension；`hive` extension 不随 JDBC 驱动发布，要另行准备 | ⚠️ **部分可用**（见下） |
| **B. 应用层流式搬运** | 引擎里逐行 `ResultSet` → DuckDB 批量 insert | 不依赖 extension | 要自己写搬运与类型映射 | ⏳ 作为二期优化 |
| **C. 复用现成导出/导入链路** | 远端 `EXPORT` → CSV → DuckDB `IMPORT` | **链路已存在且有测试**，零新协议，带进度与取消 | 多一次磁盘往返 | ⚠️ **初稿采用，实施期被 D 替换** |
| **D. DuckDB 纯 Java 表函数（联邦直查）** | 把远端的一条 JDBC 查询注册成 DuckDB 里的**一张表**，然后 `CREATE TABLE … AS SELECT * FROM <它>` | **不落盘**、不绕 CSV、**保留远端的类型**；只用**已随包发布**的 JDBC 驱动（不联网） | 要写一个表函数（类型映射 + 分块写出） | ✅ **实施期采用（默认路径）** |
| **E. 原生扫描器（`ATTACH`）** | `ATTACH 'mysql://…' (TYPE mysql_scanner, READ_ONLY)`，再 `CREATE TABLE … AS SELECT` | 少一次拷贝；类型用 DuckDB 官方规则 | 只覆盖 MySQL / PostgreSQL；**类型映射与 D 不同**（跨机器会不一致）；没有逐行进度 | ⚠️ **可选加速，默认关**（第 8 片） |

**初稿选 C，理由很实际**：`EXPORT.RUN_EXPORT`（[engine/handlers/ExportHandler.kt](../../engine/src/main/kotlin/com/langbiantianya/handlers/ExportHandler.kt)）
与 `IMPORT.RUN_IMPORT`（[engine/handlers/ImportHandler.kt](../../engine/src/main/kotlin/com/langbiantianya/handlers/ImportHandler.kt)）
**都已经实现、都有集成测试**，而且导出跑在**独立子进程**里（`ExportProcessManager` / `ExportSubProcess`），
大查询不会把 UI 线程拖死。复用它的代价只是一次 CSV 落盘。

> ⚠️ **v1.1：初稿的 C 已被 D 替换**（实现期偏离，理由见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2）。
>
> 复用 C 的代价不只是「一次 CSV 落盘」—— 那一圈磁盘逼出了一整套东西：
> 临时目录、**CSV 把 NULL 写成空串**（于是 staging 列只能一律 `VARCHAR`、
> 下游还得 `TRY_CAST`）、以及在**成功 / 失败 / 取消**三条路径上都得清理临时文件
> （漏一条就是「用户拉了一半就关窗口，磁盘上留下几百 MB」）。
>
> 而 D（DuckDB 自己的**联邦查询**能力）把这一整套**整体消掉了**：
> 不落盘、类型原样带过来、也就没有「清理」这件事可漏。
>
> D 相对 B 的优势是**不用自己写搬运**：B 要自己处理
> `ResultSet` → DuckDB 的类型映射与分块写出，而 D 里那部分**是 DuckDB 自己的**代码
> （我们只写一个薄薄的表函数壳）。
>
> ⚠️ D 的代价要说清楚：它要**引擎编译依赖** `duckdb_jdbc`（表函数的接口在那个包里），
> 而 DuckDB 自带的 `mysql_scanner` / `postgres_scanner` 扩展**要联网下载** ——
> 内网部署装不上。所以我们走**纯 Java 表函数**：只用**已经随包发布**的 JDBC 驱动，
> 任何有 JDBC 驱动的数据源都能接（MySQL / PostgreSQL / SQLite，将来还有 Hive）。

> ⚠️ **v1.2：上面那段「要联网下载」的说法已被实测推翻**（第 8 片）——
> DuckDB JDBC 1.5.5.1 把 `mysql_scanner` / `postgres_scanner`
> **静态链接**进了驱动本体（`duckdb_extensions()` 里 `installed_from = core`），
> 把扩展目录全指到空目录也照样 `LOAD` 得起来。所以**不需要额外安装**。
>
> 真正的理由于是变成**覆盖面**：原生扫描器只覆盖 MySQL / PostgreSQL，
> 而纯 Java 表函数只要求「有 JDBC 驱动」（SQLite、H2，将来的 Hive 都行）。
>
> 原生那条路作为**可选加速**保留（方案 E）：显式打开
> （`SUNDAYS_DUCKDB_NATIVE_EXT=1`）才用，走不通就**降级**回 D。
> **默认关**的理由是**类型映射**：两条路落出来的类型不一样
> （`SMALLINT` → 原生 `SMALLINT` / 我们 `INTEGER`），
> 「装没装扩展」不该隐式决定用户拿到的类型。细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2.7。

---

## 3. 只读保证：六层防线 ★

> 这是整个设计里**最不能出错**的一节。用户说「这里面的连接的远程数据库都只能只读」——
> 如果最后只做到「界面上没写按钮」，那这个承诺是不成立的：任何一个能改 URL、
> 任何一个走错路由的调用，都可能写进去。

### 3.1 六层，逐层收紧

| 层 | 位置 | 手段 | 挡得住什么 |
|---|---|---|---|
| **L1 账号** | 远端 Hive/Impala | 用**只读授权账号** | 一切（前提是运维配合） |
| **L2 连接** | JDBC | `Connection.setReadOnly(true)` + 只读连接池参数 | 部分驱动的写 |
| **L3 方言级** ★ | `api` SPI | **只读方言不实现写方法** → 引擎里**根本不存在**往它写的路径 | 一切。**与从哪个模式进来无关** |
| **L4 会话级** | `RequestDispatcher` | 分析会话带 `read_only` 标记 → 写类别一律拒绝 | 「将来被加进分析模式的**可写**方言」 |
| **L5 API 面** | 协议 | 分析会话**只走** `Category.ANALYSIS` / `REPORT`（都是读路由） | 误用别的路由 |
| **L6 界面** | UI | 分析模式**不渲染**任何写入口（不是禁用，是不存在） | 用户误操作 |

L1 依赖运维配合、L2 在多数驱动上只是**提示**而非保证。真正兜底的是 **L3**，
**L4** 是它身后那道「防将来」的闸。

### 3.2 为什么主防线必须是「方言级」而不是「模式级」

这是 v0.2 最重要的一处修改，直接来自「连接管理器复用」这个决定。

连接管理器复用之后，**同一个 Hive 连接会同时出现在浏览模式与分析模式**。
若只读只挂在「分析模式」上，那么用户在浏览模式里打开同一个连接就能写 —— 承诺当场破了。

挂在**方言**上则天然成立：

> **Hive / Impala 在引擎里没有写入路径。**
> 从分析模式进来写不了，从浏览模式进来同样写不了。

于是「分析模式连的远程库只能只读」这句话，不再依赖「用户从哪个模式进来」，
也不再依赖「界面有没有藏按钮」。

### 3.3 L3 的实现：只读是**实现出来的**，不是**配置出来的**

`api` 的 SPI 里，视图 / 索引 / 外键 / 触发器 / 改表名 / 清表这些方法**默认抛
`UnsupportedOperationException`**（[api/.../DatabaseDialect.kt](../../api/src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt)）。
`dialect-hive` / `dialect-impala` 只需：

- **实现**：列库 / 列 schema / 列表 / 列列 / 引号 / 建列定义（供拼 SQL 用）
- **不实现**（保持默认抛错）：建 schema / 删 schema / 建用户 / 授权 / 建视图 / 建索引 …
- **显式抛错**：SPI 里少数是**抽象方法**（`createSchema` / `deleteSchema` / `createUser` /
  `deleteUser` / `updatePassword` / `updatePrivileges` / `listUsers` / `listPrivileges` /
  `listAllGrants`）—— 这两个方言里它们**显式抛 `UnsupportedOperationException`**。

同时**显式声明** `DialectCapability.READ_ONLY_DIALECT`（§5.6），
前端据此在连接管理器与向导里显示**只读徽标** ⓘ ——
用户在浏览模式里看到 Hive 连接时，就知道它**根本不能写**（而不是「忘了开只读」）。

### 3.4 L4 的定位与一条容易搞混的边界

L4（会话级闸门）是**兜底**，不是主力。它的存在理由是**将来**：

> 若给 MySQL / PostgreSQL 也加上 `ANALYSIS_SOURCE` 能力位，它们是**可写方言** ——
> 此时若没有 L4，用户在分析工作区写一句 `DELETE FROM …` 就会真的删数据。

L4 的落法：

1. **协议**：`ConnectionConfig` 加 `bool read_only = 13;`（proto3 追加字段号，向后兼容）。
   `RequestDispatcher` 在**路由之前**检查：`read_only == true` 时，
   命中写类别（`TABLE.CREATE/UPDATE/DELETE/RENAME/TRUNCATE`、`DATA.INSERT/UPDATE/DELETE`、
   `IMPORT.RUN_IMPORT`、`USER.*`、`SCHEMA.CREATE/DELETE`、`VIEW.CREATE/DROP`、
   `INDEX.*`、`FOREIGN_KEY.*`、`TRIGGER.*`，以及 `SQL.EXECUTE` 里**非 SELECT** 的语句）
   一律直接返回错误，**不进入 handler**。
2. **`SQL.EXECUTE` 的特殊性**：它是「万能入口」，不能按类别拦。
   复用**已有的** `DangerousSql.scan(sql)`（[shared/src/commonMain/.../sql/DangerousSql.kt](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/sql/DangerousSql.kt)）——
   它已经能剥注释、识别 `SELECT` / `WITH … SELECT` / 多语句里的写操作。
   浏览模式的 `readOnly` 拦截用的就是它（[desktopApp/.../DatabaseBrowserState.kt:2980](../../desktopApp/src/main/kotlin/com/langbiantianya/sundays/DatabaseBrowserState.kt)），
   但那是在**界面层**；分析模式要把它下沉到**引擎层**。

> ⚠️ **边界：本地 DuckDB 工作区可以写。**
> `ANALYSIS.RUN_QUERY` 在 DuckDB 上执行时，用户写的 `CREATE TABLE tmp AS SELECT …`
> 是**允许**的 —— 那是分析产物，落在本地工作区文件里，**不碰任何远端**。
> L4 因此**按「连接的是不是远端只读源」判定**，而不是「一律禁写」。
> 这条边界如果搞混，会把分析模式变成「连中间表都建不了」的死胡同。

### 3.5 L5：API 面才是窄门

`Category.ANALYSIS` 下的路由，**除了拉取**之外都只有读：

| 路由 | 作用 | 状态 |
|---|---|---|
| `ANALYSIS.LIST_SOURCES` | 列分析数据源 | ✅ 已落地 |
| `ANALYSIS.LIST_TABLES` | 列远端表（只读 SPI） | ✅ 已落地 |
| `ANALYSIS.LIST_COLUMNS` | 列远端列（只读 SPI） | ✅ 已落地 |
| `ANALYSIS.RUN_QUERY` | 在**计算引擎**上跑分析 SQL | ✅ 已落地 |
| `ANALYSIS.PULL_TABLE` | 把远端一张表拉进本地工作区（**写**的是工作区，不是远端） | ✅ 已落地 |
| `ANALYSIS.PULL_SAMPLE` | 从远端拉**样本**到本地工作区 | ⏳ 不落地（见下） |

> ✅ **v0.7：上面四条路由已落地** —— 实现见
> [`engine/src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt`](../../engine/src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt)，
> 路由注册见 [`engine/.../dispatcher/RequestDispatcher.kt`](../../engine/src/main/kotlin/com/langbiantianya/dispatcher/RequestDispatcher.kt)。
> 三处值得记的判据：
>
> - **「命名空间」是中性叫法** —— MySQL 上它是**库**，PG 上它是 **schema**，
>   判据是既有的 `DatabaseDialect.supportsSchema`（**不**用方言名硬判，
>   那等于把「哪个方言有什么概念」复制一份到引擎里）。
> - **`RUN_QUERY` 只允许打在声明了 `EMBEDDED_MODE` 的方言上**（H2 / DuckDB / SQLite）——
>   远端方言一律拒绝，错误文案是「分析查询只能跑在本地工作区上」。
>   它**必须**能写（工作区里的 `CREATE TABLE tmp AS SELECT …` 是 M4 的验收要求），
>   所以不能被 L4 闸门挡；但它**绝不能**打在远端上，否则就是绕过 L4 的后门。
> - **行数上限 5000**，与浏览模式的 SQL 结果上限同值；超出时响应里的
>   `truncated` **显式置位**（正好等于上限不算截断 —— 只有真的还有下一行才算）。
>
> ✅ **v1.1：`ANALYSIS.PULL_TABLE` 已落地** —— 实现见
> [`engine/src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt`](../../engine/src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt)
> 与 [`JdbcScanTableFunction.kt`](../../engine/src/main/kotlin/com/langbiantianya/analysis/JdbcScanTableFunction.kt)。
>
> 三处值得记的判据：
>
> - **它是 DuckDB 的「联邦直查」，不是「导出 CSV 再导入」** ——
>   DuckDB 自己就能直接读远端的 JDBC 数据源，于是拉取在引擎侧就是一句
>   `CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>`：
>   **没有临时文件、没有 CSV 的编码往返**。
> - **它写的是工作区，不是远端** —— 所以它**不是** L4 闸门要挡的写路由；
>   但为了兜底，它仍被列进写路由清单（万一有人拿一个 `read_only` 的连接打这条路由，
>   闸门会先拦下来）。
> - **远端的凭据在请求体里，不在 SQL 文本里** —— 引擎把它变成一条**只发 SELECT**
>   的 JDBC 连接：调用方能传的只有「选哪张表」，不可能是「执行什么语句」。
>
> ✅ **v1.2：拉取有**两条后端**（默认纯 Java 表函数，原生扫描器是可选加速）** ——
> 实现见 [`NativeExtensionScanner.kt`](../../engine/src/main/kotlin/com/langbiantianya/analysis/NativeExtensionScanner.kt)。
>
> - **原生那条路默认关**（`SUNDAYS_DUCKDB_NATIVE_EXT=1` 才开）——
>   两条路的**类型映射不同**，「装没装扩展」不该隐式决定用户拿到的类型。
> - **走不通就降级**（这个方言没有扫描器 / 连接串拼不出来 / 挂载失败）——
>   用户看到的是「拉取成功」，不是「你的扩展没装」。
> - **原生路径没有逐行进度**（DuckDB 的 CTAS 不吐行数），只在落地后报一次总数。

`Category.REPORT` 下：

| 路由 | 作用 |
|---|---|
| `REPORT.BUILD_EXCEL` | `ReportSpec` → .xlsx |

> ⚠️ **v0.6：`ANALYSIS.PULL_SAMPLE` 本轮不落地**（实现期偏离，理由见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2）：
> 拉取要碰**两个**数据库（远端读 + 本地写），而一个 `Request` 只有一份
> `ConnectionConfig`；做成单路由就得在请求体里再塞一份 workspace 配置，
> 「哪个配置才是本次请求的连接」于是有了两个产地。按 §2.4 方案 C 拆成
> 既有的 `EXPORT.RUN_EXPORT` + `IMPORT.RUN_IMPORT` 两次调用，零新协议，
> 三段进度各自已有实现与测试。
>
> ⚠️ **v1.1：上面那条理由已经被 D（联邦直查）解决** ——
> `ANALYSIS.PULL_TABLE` 把**远端配置放进请求体**（`Request.connection` 给工作区），
> 「两个产地」的问题就没有了；而 §2.4 方案 C 的「绕磁盘」代价也一并消失。
>
> ⚠️ **路由编号以实现篇为准**：§7.4 是 v0.3 的草图，它的路由名
> （`RUN_ANALYSIS_QUERY`）与本节的路由表对不上；本节为准，编号在实现篇重新排。

### 3.6 验收：只读必须有测试钉住

**每一个写类别都要有一条回归测试**，而且断言的是
「引擎返回了明确的只读错误」而不是「抛了个奇怪的 SQLException」。

两类都要有：

1. **方言级**：对一个只读方言发 `TABLE.CREATE` / `DATA.INSERT` / `USER.CREATE` → 被拒；
2. **会话级**：在一个 `read_only = true` 的会话里发写请求 → **不进入 handler** 就被拒。

> 📌 **本仓库的教训**（见 `desktopApp/TEST_CASES.md` §7）：测试的判据要落在
> 「**发出去的那条语句**」上。「只读模式下写操作被拦」这个判据，必须真的发一条
> `INSERT` 给引擎、并断言引擎**在进入 handler 之前**就拒了它 —— 而不是断言
> 「某个 UI 按钮是 disabled」。后者在 UI 重构后依然绿，而功能其实已经漏了。

---

## 4. 数据源：Hive 与 Impala

### 4.1 驱动选型

| 目标 | 驱动 | 说明 |
|---|---|---|
| Hive | `org.apache.hive:hive-jdbc`（standalone 分类） | 走 HiveServer2 的 Thrift 协议。仓库现有 `hadoop-common 3.5.0` 已铺了 Thrift/HDFS 的底，但版本与 `hive-jdbc` 的 Hadoop 依赖**可能打架**（见 §12 R1）。 |
| Impala | **同一个** `hive-jdbc` | Cloudera Impala 兼容 **HiveServer2 协议**，所以同一个驱动能连 —— ⚠️ **这是假设，必须先验证**（见 §4.2）。 |
| DuckDB | `org.duckdb:duckdb_jdbc` | **仓库已有**（`libs.versions.toml` 的 `duckdb = 1.5.5.1`），`dialect-duckdb` 也已有，**零新增**。 |

> 💡 **Impala 走 HS2** 是这条路线能成立的关键。若实测不成立，退路是
> Impala 自带的 JDBC 驱动（Cloudera 分发，许可与获取渠道另议），
> 或把 Impala 降级为「通过 Hive Metastore + Parquet 直读」——那会重新引入 extension 下载问题。
>
> 💡 **还有一条通用退路**：`ConnectionType.CUSTOM_URL`（§5.4）——
> 用户自己填 JDBC URL，方言由 URL 前缀反查。**大数据环境的 URL 形状千奇百怪**，
> 这条逃生舱让「专用方言写不出来」不至于卡死整个功能。

### 4.2 ⚠️ 必须在写任何代码之前验证的假设

**M3 的第 0 号任务是一个 spike，不产出功能代码，只产出一条结论**：

> ① `hive-jdbc` 能否连上目标环境的 HiveServer2？
> ② 同一个驱动能否连上 Impala 的 HS2 端点？
> ③ `hive-jdbc` 与仓库现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 是否冲突？
> 冲突怎么解（`exclude` 降级 / 换版本 / 拆 ClassLoader）？
> ④ 目标环境是 CDH / HDP / Cloudera / EMR / 自建？**版本号是多少？**

**这四条里任何一条答不上来，M3 的方案就要改。** 在此之前写出来的 `dialect-hive` 可能是白写。

> ✅ **④ 的兼容策略已在 v0.4 落定**（§4.6）：Hive「最近的几个大版本尽量向下兼容」、
> Impala「从兼容 hive jdbc 的版本开始支持」—— 由此推出四条工程决策 D1~D4，
> 并把 §4.3 的 `jdbcUrlPrefix` 撞车解法从「造自定义前缀」改成「靠 `driver` 消歧」。
>
> ⚠️ 但**具体发行版与版本号仍未落定**（用户尚未给出目标环境）——
> 它只影响 §12 R1 的**版本取舍**（用哪个 `hive-jdbc` 客户端版本），不影响 §4.6 的架构决策。
> ①②③ 仍然是 M3.0 spike 的必答项。

### 4.3 方言实现面

两个方言都是**只读子集**（§3.3），实现量不大：

| SPI 方法 | Hive | Impala |
|---|---|---|
| `listDatabases` | `show databases` | `show databases` |
| `listSchemas` | Hive 无 schema 层 → 抛 `UnsupportedOperationException` | 同 Hive |
| `listTables` | `show tables` / `show tables in <db>` | `show tables` |
| `listColumns` | `describe <table>`（**不要**用 JDBC `getColumns`，Hive 的元数据走 Thrift 更准） | `describe <table>` |
| `quoteIdentifier` | 反引号 `` ` `` | 反引号 `` ` `` |
| `buildColumnDefinition` | 抛（只读） | 抛（只读） |
| `jdbcUrlPrefix` | `jdbc:hive2:` | `jdbc:hive2:` ⚠️ **与 Hive 同前缀** |

> ⚠️ **`jdbcUrlPrefix` 撞车**：Hive 与 Impala 用**同一个驱动、同一个 URL 形状**
> （`jdbc:hive2://host:port/db`），而引擎靠 URL 前缀反查方言
> （`PoolManager.resolveDialect`）。两个方言抢同一个前缀会让「按 URL 反查」**必然歧义**。
>
> ✅ **v0.4 已定解法（§4.6 的 D2 + D4）**：**不**给 Impala 造自定义前缀
> （`jdbc:impala2://` 那条路作废 —— 用户口径明确「Impala 从兼容 hive jdbc 的版本起」），
> 而是让**连接配置里的显式 `driver` 字段**承担消歧：
> 前端保留用户已选的方言，proto 的 `ConnectionConfig.driver` 分别填 `Hive` / `Impala`，
> 引擎在 URL 前缀命中**多个**方言时改用 `driver` 消歧。
> `driverName` 仍是各自的 `Hive` / `Impala`（`DialectLoader` 的注册键）。
>
> **M3 必须改 `PoolManager.resolveDialect`**，让「URL 前缀唯一」不再是它的前置假设。

### 4.4 连接表单规格（通用化之后长什么样）

两个方言在**通用化后的连接管理器**里声明自己的表单（§5.2）：

| 字段 | Hive | Impala |
|---|---|---|
| `host` | TEXT，必填 | TEXT，必填 |
| `port` | INT，默认 **10000** | INT，默认 **21050** |
| `database` | TEXT | TEXT |
| `user` | TEXT | TEXT |
| `password` | PASSWORD | PASSWORD |
| `kerberosPrincipal` | TEXT（选填，Kerberos 认证时用） | TEXT（同） |
| `tpcService` | — | TEXT（选填，租户） |
| `authMethod` | PASSWORD / **KERBEROS** | PASSWORD / KERBEROS |

新增这两个方言时**前端一行不改** —— 表单是方言自己声明的。

### 4.5 配置与凭据

- 存储：`~/.config/sundays/connection.json`（**与浏览模式同一份**，§1.3）
- 模型：`ConnectionConfig`（**同一个**），新增 `authMethod` 与 `properties`（§5.3）
- 明文口令与现状同级，**不因为新功能引入更差的存储**；
  但**不能**因此在文档里说「已加密」——没有。
- 凭据**只用于只读分析**时，也可以在同一份配置里被浏览模式看到（同一个 Hive 连接两边都在）——
  这正是 L3 方言级只读存在的理由：**共用凭据不再意味着共用写权限**。

### 4.6 Q1 的结论：版本兼容策略（v0.4 落定）★

> 用户给出的口径（Q1）：
> **Hive「最近的几个大版本尽量向下兼容」**；
> **Impala「从兼容 hive jdbc 的版本开始支持」**。
> 这一节把口径翻成**四条可执行的工程决策**，并交代它们各自的边界。

| # | 决策 | 依据 | 影响面 |
|---|---|---|---|
| **D1** | **单一驱动**：`org.apache.hive:hive-jdbc`（standalone 分类），**Hive 与 Impala 共用同一个** | Impala 的 HS2 端点实现的就是 HiveServer2 协议 —— 同一个驱动能连 | §4.1 表、`libs.versions.toml` |
| **D2** | **单一 URL 前缀** `jdbc:hive2:` —— **不**给 Impala 造 `jdbc:impala2://` | 「Impala 从兼容 hive jdbc 的版本起」这句话本身就指向 HS2 兼容，而不是指向另一套协议 | §4.3 撞车问题（**改 R3 解法**） |
| **D3** | **客户端版本向前兼容**：用**较新**的 `hive-jdbc` 客户端连**较老**的 HiveServer2；反过来不保证 | HS2 的 Thrift 接口在 Hive 各大版本间保持稳定；新客户端带的新特性（SASL 协商、ZooKeeper 发现、`hive.server2.thrift.*` 参数）在老服务端上按「不认识就降级」处理 | §12 R1 的版本取舍 |
| **D4** | **消歧靠连接配置里的显式 `dialect` / `driver` 字段**，不靠 URL | D2 之后 URL 形状真的相同；而**用户意图的真相源是他在界面上选的方言**，不是 URL | `PoolManager.resolveDialect` 必须改 |

#### 「向下兼容」具体兼容什么

| 方向 | 兼容性 | 依据与后果 |
|---|---|---|
| 新客户端 → 老服务端 | ✅ **保证**（这是 D3 的主力方向） | HiveServer2 的 Thrift 接口在各大版本间基本稳定。`hive-jdbc` 的 `standalone` 分类自带依赖，客户端只依赖协议不依赖服务端实现 |
| 老客户端 → 新服务端 | ⚠️ **不保证** | 新服务端可能启用老客户端不认识的能力（新的 SASL 机制、新的 `hive.server2.*` 参数）。所以**客户端版本宁可新**——这一条直接否掉「为了兼容老环境而钉死老驱动」的做法 |
| Impala 的 HS2 → `hive-jdbc` 客户端 | ⚠️ **待 spike 验**（§4.2 的 ②） | Impala 从提供 HS2 的那个版本线起与 HiveServer2 协议兼容；「兼容 hive jdbc 的版本」= **Impala 的 HS2 实现与 `hive-jdbc` 客户端握手兼容的那条版本线**。**具体是哪条线，取决于目标环境的 CDH / EMR 版本号**——而版本号还没定（§4.2 的 ④） |

#### D4 的具体改法（引擎侧，M3 的活）

```kotlin
// engine/PoolManager.kt —— 现在是「URL 前缀唯一 → 唯一方言」的假设
// 改成：URL 前缀命中**多个**方言时，用连接配置里的 driver 字段消歧
fun resolveDialect(jdbcUrl: String, driver: String?): DatabaseDialect? {
    val byPrefix = dialects.filter { jdbcUrl.startsWith(it.jdbcUrlPrefix) }
    return when {
        byPrefix.size == 1 -> byPrefix.first()
        // ← D4 的分支：Hive / Impala 共用 jdbc:hive2:，靠 driver 定
        driver != null -> byPrefix.firstOrNull { it.driverName.equals(driver, ignoreCase = true) }
        else -> null // 歧义且无 driver → 明确失败，不要瞎猜
    }
}
```

- 前端已有 `DialectType.engineDriverName`（§6.3.2 的元数据表提供），值分别是 `Hive` / `Impala`。
- proto 的 `ConnectionConfig.driver` 字段**已经存在**，前端 `ConnectionManagerScreen` 一直在填 —— 不需要新增协议字段。
- ⚠️ 消歧失败时**明确报错**，不要「随便挑一个」。挑错的症状是
  「连上了 Hive 但列名/类型全不对」，比连不上更难排查。

#### 这四条决策**没有**回答什么

- ❌ **目标环境的发行版与版本号**（§4.2 的 ④）—— 仍然待定。
  它只影响 `hive-jdbc` **客户端版本**的取舍（D3），**不影响** D1~D4 本身。
- ❌ `hive-jdbc` 与仓库现有 Hadoop 依赖是否冲突（§12 R1）—— 仍然是 M3.0 spike 的必答项。
  倾向：`hive-jdbc:standalone` 分类（自带依赖，规避传递冲突，且天然兼容老服务端）。
- ❌ Impala 的 HS2 实测兼容性（§12 R2）—— 同上。

---

## 5. 连接管理器通用化（前置条件）★

> 完整背景见 §1.4。这一节是 §11 迭代里的 **M1**。

### 5.1 目标与判据

| 目标 | 判据（可判定） |
|---|---|
| 表单由**方言自描述**驱动 | 新增一个方言**不需要改任何前端代码**，表单自动出现 |
| 认证方式可扩展 | 方言声明支持的 `AuthMethod`，向导据此渲染；Kerberos 不需要改前端 |
| 方言特有字段能承载 | `ConnectionConfig.properties: Map<String,String>` 承载任意键值 |
| 有通用逃生舱 | `ConnectionType.CUSTOM_URL`：用户直接填 JDBC URL，方言由 URL 前缀反查 |
| 现有 5 个方言**零破坏** | 现有 `buildJdbcUrl(host, port, database)` 保留；新能力用**新重载**加 |
| 两个模式共用一份配置 | `connection.json` 一份；各模式按**能力位**筛出自己的子集 |

### 5.2 方言自描述的连接表单

> ✅ **M1 第二片已落地（v0.5）**：契约模块 `:dialect-contract` 已建，5 个内置方言已各自声明
> 规格，前端已按「优先读 spec、读不到回退旧分支」渲染。**落点与原文有三处不同**，
> 都写在下面 —— 每一处都有仓库事实支撑。

契约模块（`dialect-contract/src/main/kotlin/com/langbiantianya/dialect/contract/`）：

```kotlin
data class ConnectionFormSpec(
    val forms: List<ConnectionForm> = emptyList(),   // 每种连接类型一份
    val authMethods: List<AuthMethod> = listOf(AuthMethod.PASSWORD),
    val defaultPort: Int? = null,
)

data class ConnectionForm(
    val connectionType: String,                      // "CLIENT_SERVER" / "IN_MEMORY" / …
    val fields: List<ConnectionField> = emptyList(),
)

data class ConnectionField(
    val id: String,                 // "host" / "port" / "principal" / "serviceName" …
    val labelKey: StrKey,           // 文案键（i18n 六语言）
    val type: FieldType,            // TEXT / PASSWORD / INT / CHOICE / DISPLAY
    val required: Boolean = false,
    val secret: Boolean = false,    // 存进 properties 时要脱敏
    val choices: List<String> = emptyList(),
    val placeholderKey: StrKey? = null,   // 占位提示（翻译过的）
    val placeholder: String? = null,      // 占位提示（字面量，例如 JDBC URL 示例）
    val authMethods: List<AuthMethod> = emptyList(),  // 空 = 所有认证方式下都出现
    val separatorBefore: Boolean = false,
    val multiline: Boolean = false,
)
```

`DatabaseDialect` 新增一个属性：

```kotlin
/** 该方言的连接表单规格 —— 前端按它渲染表单，不按枚举分支。 */
val connectionForm: ConnectionFormSpec get() = ConnectionFormSpec()
```

**默认实现是「空 spec」** —— 表示「我没声明」，前端据此**回退旧的枚举分支**。
不默认给一套 host/port/database 的通用表单：那样「声明了」与「没声明」在数据上
分不出来，前端会以为所有方言都归它管，内置方言的行为悄悄变了。

> 💡 **为什么不一次性重写前端表单**：那会让 5 个方言的既有测试全红，
> 而且「新旧两套渲染」在迁移期会分叉。判据是**新增方言零前端改动**，
> 而不是**旧方言全改成新写法** —— 后者是纯收益为零的重构。

#### 三处与原文不同的地方（v0.5 的修正）

**① spec 是「多个 form」的列表，不是一份扁平的字段表。**

同一个方言在不同**连接类型**下的形状是真的不同：H2 的内存库填「库名」、
文件库填「文件路径」（连标签与占位提示都不同）。硬塞进一份扁平字段表，
就只能取一个形状 —— 剩下那个连接类型要么显示错的标签，要么得在渲染层再写一次
`when`（那就白做了）。

**② 连接类型用**字符串**，不定义第三个枚举。**

`:api`（引擎 SPI）与 `:shared`（持久化格式）各有一份 `ConnectionType` 枚举，
契约模块若再定义第三份，就是**同一个概念三处定义** —— 那正是本仓库反复在消灭的东西。
两边都用各自枚举的 `name`，由 `desktopApp` 的 `DialectFormContractTest` 钉住「同名集合一致」。

**③ proto 通道**复用了已有的 `SYSTEM.LIST_DRIVERS`**，不新开 action。**

原文那条「❌ 走 proto 把 spec 传给前端」反对的是**每次建连接**都白跑一趟序列化
（spec 是静态的，而 proto 是运行时连接状态的通道）。但 `LIST_DRIVERS` 恰恰是
**与 `SYSTEM.INFO` 同性质的纯元数据查询**（不需要连接、不连库），
引擎 v2.8 就建好了这条通道（13 个字段的前端表单元数据）—— **前端一直不调用它**。
复用它既不违背那条反对意见的本意，也省掉一个新 action。

规格作为 `DialectInfo` 的**新字段**下发；「没声明」用 **proto3 消息字段的天然存在性**
表达（`hasConnectionForm() == false`），与「声明了但字段为空」分得开。

#### 落点修正：为什么不能放在 `:api`（v0.4）

**仓库事实**（已查证，不是推测）：

| 模块 | 是什么 | project 依赖 |
|---|---|---|
| `:api` | plain `kotlin-jvm`，方言插件 SPI（含 `java.sql.Connection`） | `:i18n`、`:dialect-contract` |
| `:shared` | Kotlin Multiplatform（`jvm()` target），UI 层 | `:i18n`、`:dialect-contract` |
| `:engine` | 引擎实现 | `:api`、`:engine-protocol` |
| `:desktopApp` | 桌面应用 | `:shared`、`:engine` |

依赖方向是 `engine → api`、`desktopApp → engine`、`desktopApp → shared` ——
**`:shared` 看不到 `:api`**。所以：

- ❌ **把 spec 放 `:api`、让 `:shared` 依赖它** —— 那是**层级倒置**
  （UI 层依赖引擎 SPI），且 `:api` 的公共 API 带 `java.sql`，
  一旦 `:shared` 将来加移动端 target 就会断（`:i18n` 的注释里已经写过这条）。
- ✅ **新建一个契约模块** `:dialect-contract`（plain `kotlin-jvm`）——
  **与 `:i18n` 完全同构**：`:api` 依赖它（声明 spec），`:shared` 依赖它（渲染 spec），
  它自己依赖 `:i18n`（`labelKey: StrKey` 要用文案键）。

> 💡 **这个先例仓库里已经有了**：`:i18n` 正是为了「两边都要用、但彼此看不见」
> 才从 `:shared` 里下沉出来的（见 `shared/build.gradle.kts` 的注释）。
> 连接表单规格遇到的是**同一个问题**，所以用**同一个解法** ——
> 而不是发明第二套机制。

`AuthMethod` 同理：它原先住在 `:shared` 的 `ConnectionConfig.kt`，
spec 要引用它，就得跟着搬进契约模块。**序列化名不变**
（`PASSWORD` / `KERBEROS` / `NONE`），所以 `connection.json` 的存量记录照常读得出 ——
磁盘格式没有变化，这条由契约模块的测试钉住。

⚠️ **契约里只有 `AuthMethod` 是 `@Serializable`**：`StrKey` 在 `:i18n` 里
**刻意不带** kotlinx.serialization（文案表是编译期常量），而 `ConnectionField.labelKey`
持有 `StrKey` —— 所以 `ConnectionField` / `ConnectionFormSpec` 都**不**可序列化。
它们走 proto 跨进程，proto 自带序列化。

#### 契约的**边界**：spec 只描述「连得上」，不描述「写得动」

`ConnectionFormSpec` 描述的是**连接参数**（主机/端口/库名/凭据/认证方式），
**不包括**「这个方言能不能写」。后者是**方言级只读**（§3.3 L3）的职责，
由引擎的 `DatabaseDialect` 负责 —— 前端**不判断**「这条 SQL 算不算写」。
两件事混在一份 spec 里，就会出现「表单说能填，引擎说不能写」的错配。

### 5.3 `ConnectionConfig` 的扩展

```kotlin
data class ConnectionConfig(
    ...现有字段...
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** 方言特有键值（Kerberos principal / TPCD service / ZooKeeper / SSL 参数…） */
    val properties: Map<String, String> = emptyMap(),
)
```

- 持久化模型 `PersistedConnectionConfig` 同步加这两个字段。
- ⚠️ **向后兼容**：`PersistedConnectionList.version` 从 2 升到 3，
  加载时 v2 的记录按缺省值补齐（`authMethod = PASSWORD`、`properties = {}`）。
  仓库已有 `ConnectionStorageTest` 盯迁移，v2 → v3 要补一条。
- `properties` 里 `secret = true` 的字段在 UI 上要脱敏、在导出配置时要剔除。

### 5.4 新增 `ConnectionType.CUSTOM_URL`

```kotlin
enum class ConnectionType {
    CLIENT_SERVER, EMBEDDED, IN_MEMORY, FILE_BASED,
    CUSTOM_URL,      // 新增：用户直接填 JDBC URL
    UNKNOWN,
}
```

- 表单只显示一个「JDBC URL」字段。
- 方言由 **URL 前缀**反查 —— 引擎侧**已经有这个能力**
  （`DialectLoader.getDialectByJdbcUrl`，`DatabaseDialect.jdbcUrlPrefix`）。
- **这是 Hive / Impala 的保底路径**（§4.1）。
- ⚠️ 反查失败要**明确报错**（引擎现有行为就是「不回退 `driver`」——
  这个设计是对的：静默套用别的方言比连不上危险得多）。

### 5.5 连接列表按能力位分组

| 能力位 | 含义 | 内置分布 |
|---|---|---|
| `BROWSE_SOURCE` | 可在**浏览模式**使用 | MySQL / PG / H2 / DuckDB / SQLite |
| `ANALYSIS_SOURCE` | 可在**分析模式**使用 | **MySQL / PG**（v0.7 起已声明，见下方说明与 Q3） |
| `READ_ONLY_DIALECT` | **方言级只读**：写方法一律抛 `UnsupportedOperationException` | Hive / Impala |

- 连接管理器按这两组显示（「可浏览」/「可分析」），**同一份配置**。
- `READ_ONLY_DIALECT` 的连接在列表与向导里显示**只读徽标** ⓘ（§3.3）。
- **DuckDB 不在 `ANALYSIS_SOURCE` 那一组** —— 它是分析模式**自带的计算引擎**
  （§2.2 读法 B），不是「数据源」；本地库（H2 / SQLite）同理。
- `BROWSE_SOURCE` **目前还没有任何方言声明** —— 浏览模式是当前的默认模式，
  前端按「连接类型」分组，不需要这个能力位来筛选；
  等浏览模式也按能力位分组时再补（那是一次行为不变的一句声明）。
- **v0.7：MySQL / PostgreSQL 已声明 `ANALYSIS_SOURCE`**（引擎侧 `dialect-mysql` /
  `dialect-postgresql`）。理由见 Q3 的更新：当初「不加」是因为 L4 闸门还不存在，
  而 M2 正好把它建起来 —— 补上前置条件后，可写方言进分析模式是**安全的**。
- ⚠️ **DuckDB / H2 / SQLite 都不声明 `ANALYSIS_SOURCE`** —— 分析模式的计算引擎
  是 DuckDB **自己**（§2.2 读法 B），它不是「数据源」；把它列成数据源，
  用户会以为自己能「拉自己」。这条边界由
  [`desktopApp` 的 `AnalysisCapabilityContractTest`](../../desktopApp/src/test/kotlin/com/langbiantianya/sundays/AnalysisCapabilityContractTest.kt) 钉住。

### 5.6 SPI 的向后兼容策略

方言要拿到 `properties` 才能拼 URL，但 `buildJdbcUrl(host, port, database)` 只有三个参数。

**做法：加新重载，默认实现调旧方法。**

```kotlin
// api/DatabaseDialect.kt
fun buildJdbcUrl(spec: DialectConnectionSpec): String =
    buildJdbcUrl(spec.host, spec.port, spec.database)   // 默认：走老路径

fun buildJdbcUrl(host: String, port: Int, database: String): String   // 原样保留
```

- 现有 5 个方言**一行不改**。
- Hive / Impala **覆写新重载**，从 `spec.properties` 里取 Kerberos principal / TPCD service 等。
- 前端 `JdbcUrl.buildJdbcUrl(config)` 改成调新重载。

### 5.7 验收

> ✅ **M1 第二片已交付（v0.5）**。逐条对照：

| 验收项 | 状态 | 落在哪 |
|---|---|---|
| **测试用假方言**（`FakeDialect`）声明 3 个自定义字段 + 2 种认证方式，连接管理器**自动**渲染出来，**前端零改动** | ✅ | `desktopApp` 的 `DialectFormSpecRenderingTest`（10 条） |
| `CUSTOM_URL` 连接：填引擎认识的 URL → 方言被正确反查；不认识的 → 明确报错 | ✅（M1 第一片） | `ConnectionStorageTest` + `JdbcUrlTest` |
| v2 的 `connection.json` 加载后自动补齐新字段 | ✅（M1 第一片） | `ConnectionStorageTest` |
| `properties` 里的 secret 字段在 UI 脱敏、在「导出配置」时剔除 | ⏳ **未做** | secret 标记已在契约里，UI 脱敏与导出剔除留在 M3（那时才有真实的 Kerberos 字段要脱敏） |
| **现有 5 个方言的连接向导测试全绿且未修改** | ✅ | `ConnectionManagerFlowTest` / `ConnectionWizardMouseKeyboardTest` **一行未改** |
| **连接类型名的三处一致**（契约字符串 / `:api` 枚举 / `:shared` 枚举） | ✅ | `desktopApp` 的 `DialectFormContractTest`（6 条） |

**「前端零改动」怎么证明？** 靠**反证**：假方言那三个字段的标签
（服务名 / Kerberos 主体 / keytab 路径）在前端代码里**一个都没有**，
只有 `FakeDialect` 声明了它们 —— 它们能出现在界面上，就只能是 spec 渲染出来的。

而更狠的一条是「**MySQL 的内置字段不出现**」：同一个方言，换一份 spec，
界面就换了一套字段 —— 这说明前端**没有**按方言写死的字段清单。

#### ✅ Q6 已定（v0.4）：**保留旧的枚举分支**作为回退

用户拍板：**旧枚举分支保留**。所以迁移是**单向**的：

```
读 spec（connectionForm） ──有──▶ 按 spec 渲染
       │
      无
       ▼
回退旧的枚举分支（按 dialect / connectionType 分支渲染）
```

- **判据是「有没有 spec」，不是「方言是不是新的」** —— 现有 5 个方言在 M1
  第一片**还没有** spec，所以那一阶段走的都是回退分支，**行为零变化**；
  M1 第二片给它们补上 spec 之后，才切到 spec 分支。
- 旧分支**不删**。它的价值是：① spec 有 bug 时能立刻退回；② 老方言（引擎侧插件
  没有更新）依然能用；③ 迁移期两套渲染**分叉**的风险由 R9 盯着。
- 代价要说清：**两套渲染会分叉**。所以约定是「spec 分支一旦接管某个方言，
  旧分支就不再改」—— 旧分支只允许**修 bug**，不允许加功能。
  否则半年后没人说得清「MySQL 的表单到底按哪套渲染」。
- 什么时候能删旧分支？等**所有**方言都有 spec、且 spec 分支跑满一个版本之后，
  再单独开一轮清理 —— 不夹带在 M1 里（§6.3.5 记着同一条原则）。

**M1 第二片落地后的实际状态**：5 个内置方言都有 spec，且**形状与旧分支逐格对齐**
（字段、顺序、标签、占位符、只读 URL 展示、端口的默认端口占位）。
两套分支在**内置方言上产出同一个界面** —— 这正是「行为零变化」的兑现方式。
而 spec 分支的**独有**能力（认证方式下拉、按认证方式显隐的字段、
方言特有字段）目前只有假方言在用，等 M3 的 Hive / Impala 落地才是它的正式用途。

---

## 6. 组件复用与通用化 ★

> 本节回答评审意见的第三条：**组件和控件也尽量复用，可以优化组件更加通用**。
>
> **结论先行**：分析模式**不新写一套控件**。所有颜色 / 圆角 / 字号来自现有主题系统（§9.1），
> 所有通用控件来自 `:shared` 的 `commonMain`；本模式**只新增 5 个组件**，且**全部落在 `:shared`**
> —— 这样浏览模式将来也能用。

### 6.1 判据：什么叫「可以复用」

一个组件要被分析模式复用，必须同时满足三条：

| # | 判据 | 为什么是它 |
|---|---|---|
| 1 | **平台无关** | 不 import `java.*` / `javax.*`，也不 import `desktopApp` 包里的任何类型 —— 否则它进不了 `:shared` 的 `commonMain`（`:shared` 的定位是「commonMain 单一 source set 承载所有业务组件」，**不引入 expect/actual**，见 `shared/ARCHITECTURE.md`） |
| 2 | **状态无关** | 不直接依赖 `DatabaseBrowserState` 这类**大而具体**的状态机 —— 依赖它就等于把整个浏览模式拖进分析模式 |
| 3 | **可寻址** | 能说清「它出现在分析模式的哪一屏的哪个位置」—— 否则「复用」只是把代码搬了个地方 |

不满足第 2 条的组件**先解耦再复用**，而不是复制一份到 `desktopApp`。

### 6.2 直接复用清单（零改动）

下表每一项都**不需要改一行代码**：

| 组件 | 位置 | 用在哪 | 为什么够用 |
|---|---|---|---|
| `CodeEditor` / `CodeEditorWithToolbar` | `shared/.../editor/ui/CodeEditor.kt:246` / `:699` | 工作区 SQL 编辑区 | 组件只认 `languageId`；方言差异全在 `SqlDialectProfile` 里（§6.3.1） |
| `CodeEditorState` / `rememberCodeEditorState` | `shared/.../editor/ui/CodeEditor.kt` | 编辑器状态 | 纯状态持有者，与业务无关 |
| `DataTable` | `shared/.../table/DataTable.kt:187` | 查询结果表 | 已是通用键值模型，聚合结果的列天然可用（§6.3.3） |
| `TableColumn` / `TableRow` | `shared/.../table/TableModels.kt:52` / `:88` | 结果表的列与行 | `TableColumn(key, header, …)` + `TableRow(id, cells)` 不含「库表」语义 |
| `PageSize` | `shared/.../table/TableModels.kt:121` | 结果表分页 | `PageSize.ALL` 的 KDoc 明说「一次性渲染所有行（依赖 LazyColumn 虚拟滚动）」 |
| `DataTableTheme` | `shared/.../table/TableModels.kt:195` | 表格配色 | 已有「编辑 / 待提交 / 待删除」三种单元格状态色，只读场景一个都用不到 |
| `EmptyHint` | `shared/.../ui/EmptyHint.kt` | 所有空态 | 已是「标题 + 说明 + 可选居中」的通用形态 |
| `FocusRing` / `uiBevel` / `winShape` / `controlShape` / `selectionColorsFor` | `shared/.../ui/UiChrome.kt` | 焦点环 / 立体边 / 圆角 / 选中色 | 经典档与现代档的差异全在这一个文件里 |
| `WinButton` / `WinOutlinedButton` / `WinTextButton` / `WinTextField` / `WinIconButton` / `WinSwitch` / `WinMenuItem` / `WinSurface` / `WinProgressIndicator` / `WinDivider` | `shared/.../ui/WinControls.kt` | 全部按钮 / 输入框 / 开关 / 菜单项 / 进度条 | 现有控件集已覆盖分析模式的绝大多数控件，**唯独缺下拉**（§6.3.4） |
| `ContextMenuState` / `onRightClick` | `shared/.../ui/ContextMenuState.kt` / `RightClick.kt` | 右键菜单 | 泛型 `ContextMenuState<T>`；表格与编辑器各有一个类型别名 |
| `ColumnBadge` | `shared/.../ui/ColumnBadge.kt` | 数据源类型小徽标 | `surfaceVariant` 底的小徽标，正好是「类型标签」的视觉 |
| `DragHandle` | `shared/.../ui/DragHandle.kt` | 面板宽度拖拽 | 已支持 `hoverIcon`（指针图标由平台侧注入，桌面端 `PointerIcons.kt`） |
| `DangerousSql` / `DangerousSqlConfirmDialog` | `shared/.../sql/DangerousSql.kt` / `DangerousSqlConfirmDialog.kt` | SQL 执行前的只读校验 | `isReadOnlyQuery` 已能剥注释、识别 SELECT / WITH / 多语句写操作 |
| `SundaysTheme` / `ThemePalette` / `SundaysPalette` | `shared/.../ui/` | 主题 | 5 套配色 × 明暗 × 紧凑档，分析模式**一行新样式都不写** |
| `str` / `LocalStrings` / `ProvideStrings` | `shared/.../ui/LocalStrings.kt` | 国际化 | 新增文案键走 `i18n` 的 6 语言表，UI 里不写死中文 |
| `CompactMode` / `LocalCompactMode` / `compactDensity` | `shared/.../ui/CompactMode.kt` | 紧凑档 | 与主题一起继承 |
| `SettingsEntryButton` | `shared/.../ui/ThemeModeToggle.kt` | 顶栏右上角的 ⚙ 设置入口 | 图标按钮（`Icons.Filled.Settings`），与浏览模式的设置入口**同一个组件** |
| `SqlFormatter` / `CodeFormatterRegistry` | `shared/.../editor/formatter/` | SQL 格式化 | 格式化按钮按 `languageId` 找 formatter，Hive / Impala 档位注册后自动可用 |
| `TabRenameDialog` | `shared/.../ui/TabRenameDialog.kt` | 报表区块改名 | 已是通用的「重命名一个字符串」弹窗 |
| `NotificationCenter` / `NotificationCenterPanel` / `NotificationBell` | `shared/.../notifications/` | 分析模式的错误通知 | **本轮上移**：通知中心是纯状态 + 纯展示，分析模式要「导出失败 / 查询超时」这类提示时整套复用。时间格式化由调用方注入（本地时区是平台知识） |
| `BrowserToolBar` / `PaneToggleButton` 形态 / `EmptySheetsHint` | `shared/.../browser/BrowserToolBar.kt` | 分析工作台的顶部 chrome 与空态 | **本轮上移**：工作台切换按钮与空态引导是纯展示，认窄数据形状，不认引擎状态。分析模式若也要「结果 / 图表」双视图切换，同一套按钮直接用 |

### 6.3 通用化之后复用的（5 项）

#### 6.3.1 SQL 方言档位：注册表化（**必做**）

**现状**：`shared/.../editor/language/SqlDialectProfile.kt:341` 是一行硬编码：

```kotlin
val ALL: List<SqlDialectProfile> = listOf(STANDARD, MYSQL, POSTGRESQL, H2, DUCKDB, SQLITE)
```

它被两处消费：

- `shared/.../editor/formatter/SqlFormatter.kt:179-187` —— `register()` 遍历 `ALL`，
  为每个档位注册「语言 + formatter」配对
- `shared/src/commonTest/.../editor/SqlDialectProfileTest.kt` —— 遍历 `ALL`
  校验「档位即语言」「三类词互斥」两条不变量

**问题**：加 Hive / Impala 就要改 `:shared` 的核心文件 —— 这**直接违反** §5.7
「新增方言零前端改动」的判据。

更关键的是它**违背了仓库已有的设计**：`CodeLanguageRegistry`
（`shared/.../editor/CodeLanguage.kt:94`）的 KDoc 明确写着
「第三方语言可在应用启动后调用 `register` 注入，**不需要修改编辑器组件源码**」
—— SQL 家族只是走了 `ALL` 这条旁路，没享受这个机制。

**方案**：新增 `SqlDialectProfileRegistry`，与 `CodeLanguageRegistry` / `CodeFormatterRegistry`
**同构**（同样的 `synchronized` 守护 + `get` / `all` / `unregister`）：

```kotlin
object SqlDialectProfileRegistry {
    /** 注册一个方言档位 —— 同时注册语言与 formatter，保证「只高亮不能格式化」的中间态不会出现。 */
    fun register(profile: SqlDialectProfile)
    fun get(languageId: String): SqlDialectProfile?
    fun all(): List<SqlDialectProfile>
}
```

`ALL` **保留但降级**为「内置档位的快照」，仅为不破坏现有测试；**生产路径不再读它**。
`SqlFormatter.register()` 改成遍历注册表。

**验收**：

- 运行时注册一个假档位 → `CodeEditor(languageId = "sql-fake")` **立刻有高亮**、
  格式化按钮**可用**；且 `SqlDialectProfileTest` 的两条不变量对它**自动成立**
  （因为测试改成遍历注册表）
- 现有 5 个方言的注册结果与改造前**逐项相同**（顺序、id、词表）

**顺带修一处重复**：`desktopApp/.../DatabaseBrowserState.kt:149-157` 的
`DialectType.toSqlDialectProfile()` 是**又一份**方言 if-else ——
与 `ALL` 的硬编码是同一个问题的两个副本。通用化后它应从「方言 → 档位」的映射表读，
而不是各自维护一份。

#### 6.3.2 前端方言元数据：收进一处（**必做**）

**现状**：`DialectType`（`shared/.../connection/ConnectionConfig.kt:103`）在前端有**八处**硬编码 `when`
（比 v0.3 估的四处更多 —— 写文档时只数了 `:shared` 里的三处 + 引擎侧那份，
漏了 `desktopApp` 的第五份与 `ConnectionManagerScreen` / `SqlIdentifier` 的第六七八份）：

| 位置 | 内容 |
|---|---|
| `ConnectionConfig.kt` `defaultsFor` | 方言 → 默认连接类型 + 默认端口 |
| `ConnectionConfig.kt` `supportedConnectionTypes` | 方言可选的连接类型 |
| `ConnectionConfig.kt` `engineDriverName` | 方言 → 引擎 `driver` 名 |
| `shared/.../table/DialectCapabilities.kt` `of(dialect)` | 方言 → 前端能力判断 |
| `desktopApp/.../DatabaseBrowserState.kt` `toSqlDialectProfile()` | 方言 → SQL 高亮档位 |
| `shared/.../connection/ConnectionManagerScreen.kt` | 方言图标 |
| `shared/.../connection/ConnectionManagerScreen.kt` | 方言说明文案 |
| `shared/.../connection/SqlIdentifier.kt` | 标识符引用符 |

**问题**：每加一个方言要改**八个地方**，漏一个就是「界面说能连、引擎说没这个驱动」这类错。
仓库已经有 `DialectNameContractTest` 在防其中一处漂移 —— 说明这块**确实脆**，
而只防一处、其余七处靠自觉，是不保险的。

**✅ M1 已落地**：新增 `shared/.../connection/DialectMetadata.kt` —— 一张 `mapOf` 表，
上面八处全部改读它。**加一个方言 = 加一条元数据**，不再改分支。

> ⚠️ **一处显式例外**：**URL 形状仍留在 `JdbcUrl.kt` 的 `when` 里**。
> 每种方言的 URL 语法真的不同（PG 不带 userinfo、H2 分 `mem:` / `file:`、SQLite 空库名 = 内存库），
> 那是**行为**不是**元数据** —— 塞进表里会得到一张塞满分支的数据表，名不副实。

**M1 与 M3 的边界：未落地的方言不进 `DialectType`** ★

M1 要加 Hive / Impala 时，这里出现过一个**很值得记**的岔路：

- 枚举里加上 `HIVE` / `IMPALA` → 方言选择器就会**渲染出**它们 →
  用户能选中、能建连接 → 引擎报「No dialect plugin loaded for driver: Hive」。
  这正是仓库既有原则要挡的事：**宁可少给，不给一个点了必然失败的东西**。
- 而且 `DialectNameContractTest` 会**直接红**：
  「`DialectType.HIVE` maps to `'Hive'`, but the engine registered only `[Duckdb, H2, Mysql, Postgresql, Sqlite]`」——
  它说的正是实话。

**结论**：M1 只落**机制**（元数据表 + 注册表 + 通用控件），
方言**本体**（引擎插件 + `DialectType` 枚举值 + 元数据表里的一行）在 M3 **同一个提交**里落地 ——
`DialectNameContractTest` 就是那道闸门。**这条判据同样适用于 §5.2 的连接表单规格**：
spec 可以在 M1 就把类型准备好，但**别让前端先渲染出后端还没就绪的入口**。

> 💡 顺带修掉的一个**真问题**：加了方言之后，`BasicInfoStep` 的方言列表变长，
> 底部那个「下一步」被**顶出可视区** —— 用户得先滚动才看得见按钮，
> 自动化测试则表现为「点了没反应」（点击坐标落在窗口外）。
> **M3 加 Hive / Impala 之前必须先处理它**（给方言列表限高，或把动作栏固定在底部），
> 否则「新增方言零前端改动」这句话在**布局**上是假的 —— 记为 M3 的第 0 号 UI 任务。

> ✅ **这个「第 0 号 UI 任务」已经提前做掉了**（在一次真机走查里发现并修复，
> 比 M3 早得多 —— 留档见 [`desktopApp/TEST_CASES.md`](../../desktopApp/TEST_CASES.md) §5）。
> 真机实测把根因拆成了**两个**，两者都能单独解释症状，**缺一不可**：
>
> 1. **按钮随内容一起滚走** —— 原来整个向导列挂着 `verticalScroll`，
>    底部的「下一步」跟着内容滚，滚不到底就点不着。
>    现在 [`ConnectionManagerScreen.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/connection/ConnectionManagerScreen.kt)
>    的 `StepLayout` 改成「**头部固定 / 内容可滚 / 底部按钮固定**」。
> 2. **窗口比屏幕高** —— 默认窗口 720dp，而本机（1536×864 @125% 缩放）的
>    工作区只有 **659dp** 高，窗口底边落在**屏幕外**，应用内部滚动也救不了。
>    现在默认尺寸在**建窗口之前**就被 `fitWindowSizeToScreen` 夹到工作区以内
>    （见 [`main.kt`](../../desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt)）。
>
> **教训**：第 1 条是「窗口里的布局」，第 2 条是「窗口本身的大小」——
> **只修布局那个，在大窗口上看起来好了，换块小屏照旧点不到**。
> 所以两者各自钉一条回归测试
> （[`WizardSmallWindowTest`](../../desktopApp/src/test/kotlin/com/langbiantianya/sundays/WizardSmallWindowTest.kt)）。

#### 6.3.3 `DataTable`：只读与分页都**零改动**

`DataTable`（`shared/.../table/DataTable.kt:187`）的签名里有 20 多个参数，
其中**一半是写入相关**的。分析模式是只读的，用法必须写死成下面这样，**不加新参数**：

| 参数 | 分析模式传什么 | 为什么 |
|---|---|---|
| `onCellEdit` | `null` | 触发 `cellEditable` 的默认值 `= onCellEdit != null` → 自动为 `false`（KDoc 已写明这条默认联动） |
| `isColumnEditable` / `isCellPending` / `isRowDeleted` | 保持默认 | 只读场景这三种状态不出现 |
| `pageSize` | `PageSize.ALL` | 分析结果集是一次查询返回的**完整**集合，分页是**客户端**的；`PageSize.ALL` 的 KDoc 明说依赖 `LazyColumn` 虚拟滚动 |
| `serverSidePaging` | `false` | 同上 |
| `totalCount` | `rows.size` | 默认值即可 |
| `contextMenuItems` | 复制 / 导出为 CSV | 只读但**可取走** —— 复制与导出不是写 |

**结论**：`DataTable` **一行都不用改**。这是本节最省事的一处复用 —— 因为它当初就是按
「键值对 + 一张表」设计的，聚合结果的列（`sum_amt` / `cnt` / `ratio`）
与库表的列在它眼里**没有区别**。

> ⚠️ **v1.0：`contextMenuItems` 那一行还没接上**（其余五行已按表落地）。
>
> 「复制 / 导出为 CSV」要两样**平台专属**的东西：写剪贴板（`ClipboardWriter`，`java.awt`）
> 与选保存路径（`NativePathPicker`）—— 两者都在 `desktopApp`，而 `AnalysisScreen` 在
> `:shared`，它只能把事件**回调**出去。
>
> 所以接线形态是：`AnalysisScreen` 收两个回调（`onCopy` / `onExportCsv`），
> 由 `desktopApp` 注入实现。这条**不是** M4 的验收项（§11 M4 的验收里没有它），
> 但它是设计的一部分，所以在此显式记下**缺口**而不是装作已有。
> 另注：只读表本来就有 `SelectionContainer`，单元格文本可以**拖选复制** ——
> 右键菜单的增量是「按行 / 按整表复制」与「导出为 CSV」。

> ⚠️ 一条必须写清的边界：**10 万行结果的「渲染」靠虚拟滚动，「导出」走引擎侧**（§7.1），
> 两者都不是 UI 的活。不要因为「预览要渲染 10 万行」就去改 `DataTable` 的分页语义 ——
> 那会把「浏览模式的分页」和「分析结果的完整集」搅成一件事。

#### 6.3.4 `WinControls`：补一个下拉

**现状**：`shared/.../ui/WinControls.kt` 提供了 16 个控件 —— 按钮 3 种、输入框、图标按钮、
表面、开关、菜单项、进度条、分隔线，加上形状 / 选中色解析。
**唯独没有「下拉选择器」**：`WinMenuItem` 是 `DropdownMenuItem` 的**外观包装**（菜单里的一项），
不是「点开一个列表选一个值」的选择器。

**为什么分析模式必须要**：连接向导要选**认证方式**（账密 / Kerberos / 自定义 URL），
报表设计器要选**数字格式**（文本 / 整数 / 小数 / 百分比 / 货币 / 日期）。
在现代三套配色下 Material3 的 `ExposedDropdownMenuBox` 能用；
但在 **Win2000 / WinXP** 两套经典配色下它**不会**自动变成 Win 控件外观 ——
而这两个配色在本项目里是一等公民（§9.1）。

**方案**：在 `WinControls.kt` 里**加** `WinDropdown`（配套 `WinDropdownMenu`），
沿用文件里既有的写法（`resolveControlShape` 取形状、`WinSurface` 画边框、经典档走 3D 斜面）。
这是**扩充现有控件集**，不是另起一套。

#### 6.3.5 `desktopApp` 的 UI 组件：不复用，但记一条先例

**一个必须知道的事实**：`desktopApp` 里的 8 个 UI 文件
（`SchemaTree` / `BrowserChrome` / `BrowserToolBar` / `TablePreview` / `SqlWorkbench` /
`ExportDialogs` / `GenerateWorkbench` / `DatabaseBrowserScreen`）
**没有一个 import `java.*`** —— 它们**已经是平台无关的 Compose 代码**。
它们留在 `desktopApp` 的真正原因不是平台依赖，而是**耦合 `DatabaseBrowserState`**（3952 行）。

耦合程度分级：

| 文件 | 对 `DatabaseBrowserState` 的耦合 | 能否上移 `:shared` |
|---|---|---|
| `BrowserToolBar.kt` | **0 处**引用 | ✅ 可直接上移（`internal` → `public`） |
| `BrowserChrome.kt` | 只有 `DatabaseBrowserState.BackgroundTask`（`:808`）一个小类型 | ⚠️ 抽出该类型后可上移 |
| `SchemaTree.kt` | 多处 `state: DatabaseBrowserState`（`:296` / `:445`） | ❌ 需先抽接口 |
| `TablePreview.kt` | `state: DatabaseBrowserState`（`:156`） | ❌ |
| `ExportDialogs.kt` | `state: DatabaseBrowserState`（`:267`） | ❌ |
| `GenerateWorkbench.kt` | `state: DatabaseBrowserState`（`:157` / `:303`） | ❌ |

**本项目的先例**：`desktopApp/.../main.kt:78-79` 的 KDoc 明确写着 ——
「导航条本体 (`TopNavBar` / `AppDestination`) 与应用主题 (`SundaysTheme`) 是平台无关的纯 UI，
**已上移到 `:shared` 的 `commonMain`** —— 本文件只剩『平台窗口 + 引擎实现装配』」。
所以「平台无关的纯 UI 上移到 `:shared`」在本项目是**既定做法**，不是新规矩。

**v1 的决定**：分析模式**不依赖**任何 `desktopApp` 组件上移。理由是：

- 分析模式要的是**数据源树**，而 `SchemaTree` 渲染的是「库 → 表 → 列」+ 右键菜单
  （建表 / 改表 / 删表）—— 分析模式这些写操作**一个都不要**（§3）。
- `ExportDialogs` 的 `ExportFormat` 是「一行流式追加」的单表导出（§7.1），不是报表。

→ 分析模式**自建** `SourceTree`（§6.4），但它**用** `:shared` 的 `EmptyHint` / `ColumnBadge` /
`focusRing` / `onRightClick` / `winShape` 拼出来 —— **不是**从零画一套。

> ⚠️ **v1.9：上面这条决定作废（结论正好相反）。**
>
> 「分析模式要的树」与「浏览模式要的树」后来发现**是同一棵树** ——
> 形状都是「库 → 表 → 字段」，分析模式不要的只是**右键菜单里那三个写操作**
> （建表 / 改表 / 删表），而那些**本来就不在树组件里**（在 `desktopApp` 的
> `TreeContextMenu` 里）。
>
> 也就是说：v1 当年把「树」与「右键菜单」当成了一回事，
> 于是为「不要右键菜单」而**连树一起重画了一遍** ——
> 结果是两套画法、两套展开语义、两套「表能不能展开」，
> 而它们**本该是同一个东西**。
>
> 现在的做法：把**树**上移到 `:shared`（`tree/ObjectTree.kt`），两边共用；
> **右键菜单**留在 `desktopApp`，分析模式不传它就是了。
> 这也正是本项目**既定的做法**（「平台无关的纯 UI 上移到 `:shared`」，
> 见上面 `main.kt` 的 KDoc 引文）。

**留下的问题**（→ Q9）：要不要顺手把 `BrowserToolBar` / `BrowserChrome` 上移？
本设计的立场是**不夹带** —— 那是浏览模式的内部重构，与数据分析模式无关，
混进来会让本设计的大范围改动更难评审。

### 6.4 确需新建的组件（5 个，全部落在 `:shared`）

| # | 组件 | 位置 | 为什么现有的不够用 |
|---|---|---|---|
| 1 | `ObjectTree` | `shared/.../tree/ObjectTree.kt`（**两棵树共用**） | ⚠️ **v1.9**：原第 1 个组件是分析模式**自建**的 `SourceTree`，现在**删掉**，改用这一个 —— 形状（库 → 表 → 字段）与浏览模式的对象树**完全同形**。模型在 `tree/ObjectTreeModel.kt`，是**纯数据**（不碰引擎），所以两边都能喂它 |
| 2 | `ReportBlockList` | `shared/.../analysis/ReportBlockList.kt` | 报表区块是**有序区块序列**（可增删、可上下移），`DataTable` 是**二维表** —— 不是同一件事的两种画法 |
| 3 | `ReportColumnSpecRow` | `shared/.../analysis/ReportColumnSpecRow.kt` | 区块的列配置 = 列 + **呈现类型** + **数字格式** + 对齐。`TableColumn` 只有 `key/header/width/weight/alignment/formatter`，**没有类型、没有数字格式**；而 `ColumnTypeCatalog`（`shared/.../table/ColumnTypeCatalog.kt`）是**数据库类型**（`VARCHAR` / `INT`…），报表要的是**呈现类型**（文本 / 整数 / 小数 / 百分比 / 货币 / 日期）—— 两者不是一回事，不能复用 |
| 4 | `PullProgressIndicator` | `shared/.../analysis/PullProgressIndicator.kt` | 拉取是**跨进程的长任务**；`WinProgressIndicator` 是单条进度条，画不出「已经读了多少行」。⚠️ 原名 `PullStageIndicator`（三段）是**绕磁盘**时代的产物 —— 联邦直查（§3.5 的 `PULL_TABLE`）没有阶段可分，于是改名 |
| 5 | `WinDropdown` | **加进** `shared/.../ui/WinControls.kt` | 见 §6.3.4 —— 它是**扩充**现有控件集，不是独立体系 |

> ⚠️ **v1.9：第 1 个组件从「分析模式自建」变成「两棵树共用」。**
>
> 共享之后，三件事跟着变了：
> - **分析页的树能展开到字段** —— 以前到表为止（§9.1 画面 ②）。
>   展开一张表 → `ANALYSIS.LIST_COLUMNS` → 与浏览模式**同一条**引擎路由，
>   所以两边的字段信息来自同一处。
> - **浏览模式那套的「表 → 字段」形状被带过来了** —— 字段行带类型与
>   `PK / AI / 非空` 标记，点字段 = 复制它的引用。
> - **`SourceTree.kt` 整个删掉** —— 留着没人用的组件，下一轮就会有人照着它写代码。

> ⚠️ **v1.8：原第 4 个组件 `ReadOnlyBadge`（「⊘ 只读」徽标）被删掉了** ——
> 分析屏上不再摆它（那一条工具条整个去掉了）。远端恒为只读这件事**没变**：
> 保证在引擎的 L3 / L4 闸门与方言级只读上（§3），组件层从来只是**表达**。
> 徽标没了，**组件层就没有任何「可切换」的东西**了 ——
> 而那本来就是它存在的理由（「远端恒为只读，没有开关可拨」）。

**共同的底线**：这 5 个组件

- 一律落在 `:shared` 的 `commonMain`（**不引入 expect/actual**）
- 只用 `UiChrome.kt` 的 token（`winShape` / `controlShape` / `uiBevel` / `selectionColorsFor`）
  与 `SundaysPalette` 的 `ColorScheme`，**不新增任何颜色**
- 文字一律走 `str(StrKey)`，不写死中文
- 全部带 KDoc，写清「**为什么**这么设计」而不是复述代码在做什么

### 6.5 组件层怎么表达「只读」

只读的**保证在引擎与方言层**（§3 的 L3 / L4），组件层只做**表达**，不做**判断**。

| 层 | 做什么 | 不做什么 |
|---|---|---|
| 引擎 / 方言（L3 / L4） | 真正拦住写操作 | —— |
| 组件层 | 不提供**远端**的写入口：`DataTable` 的 `onCellEdit = null`；**远端没有 SQL 编辑器**（分析模式的 SQL 只打在本地工作区上） | **不自己判断「这条 SQL 算不算写」** |

> 为什么组件层不判断：同一个判据写两遍，迟早会不一致。而「界面拦住了、引擎放行了」这种不一致，
> 会让用户以为「这条语句不安全」—— 其实引擎是拦得住的。这是**降低**而非提高安全性，
> 与 §3.2「主防线必须在方言级」是同一条道理。

> ⚠️ **v1.0 更正：「SQL 编辑器只放行 `DangerousSql.isReadOnlyQuery` 判定为只读的语句」这条作废。**
>
> 原文把「编辑器只放行只读语句」当成通用规则，但**分析模式的编辑器不是远端会话的编辑器** ——
> 它打的是**本地工作区**（DuckDB 文件），而工作区是**可写**的：
> M4 的验收原文写着「工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**」（§11 M4 验收）。
> 按原文把编辑器拦成只读，那条验收就**永远通不过**。
>
> 真正的只读边界是**「远端不可写」**，它由三件事表达：
> ① 远端**根本没有** SQL 编辑器 —— `RUN_QUERY` 只允许打在声明了 `EMBEDDED_MODE`
> 的方言上（引擎侧硬拦，不是前端自觉）；② 远端的元数据只走 `LIST_*`；
> ③ 结果表 `onCellEdit = null` —— 分析结果不是一张可以改的表。
>
> 所以本轮实现是：**编辑器不拦写**（它写的是工作区），
> **远端路径上根本没有可写的入口**。远端恒为只读的**保证**仍在引擎的 L3 / L4 ——
> 组件层一个判断都不做。

### 6.6 组件复用的可验证判据

「尽量复用」不能只靠自觉，加一条**结构测试**钉住：

`AnalysisModuleBoundaryTest`（`shared/src/commonTest/.../analysis/`）断言：

1. `shared/.../analysis/` 下的所有 `.kt` **不 import** `desktopApp` 包里的任何类型
2. `shared/.../analysis/` 下的所有 `.kt` **不 import** `java.*` / `javax.*`
3. 新文件里不出现**字面量颜色**（`Color(0x…)` / `"#RRGGBB"`）—— 颜色只能来自
   `MaterialTheme` / `LocalUiTokens`

这三条把「组件复用」从口号变成**可跑的检查**。

### 6.7 本节与迭代的对应

| 里程碑 | 本节对应的动作 |
|---|---|
| **M1** | §6.3.1（方言档位注册表化）、§6.3.2（前端方言元数据收进一处）、§6.3.4（`WinDropdown`） |
| **M2** | §6.5 的组件层表达（结果表 `onCellEdit = null`；「编辑器只放行只读语句」那条已作废 —— 见 §6.5 的更正） |
| **M3** | Hive / Impala 档位**注册**进档位注册表 —— 前端零改动（这是 §6.3.1 的验收现场） |
| **M4** | `ObjectTree`（v1.9 起与浏览模式共用）+ `DataTable` 的只读用法（§6.3.3） |
| **M5 / M6** | `ReportBlockList` / `ReportColumnSpecRow` / `PullStageIndicator` |
| **横向** | §6.6 的结构测试，每个里程碑都跑 |

---

## 7. 报表模型与 Excel 输出

> ✅ **Q4 已定（v0.4）：v1 不做图表**（用户拍板「报表先不要图」）。
> 本节只设计**表格型**报表 —— 区块、列规格、样式映射，落到 `.xlsx` 的 Sheet 与单元格。
> 图表（柱状/折线）**不在 v1 范围**；真要做，届时是**报表旁边**的一层，
> 不影响本节的数据模型（区块与列规格是图表的数据源）。

### 7.1 为什么不能直接复用 `ExportEngine`

仓库**已经有** Excel 导出：
[`engine/src/main/kotlin/com/langbiantianya/export/ExcelWriter.kt`](../../engine/src/main/kotlin/com/langbiantianya/export/ExcelWriter.kt)
（POI `SXSSFWorkbook` 流式写，单 Sheet 100 万行上限，超限自动开 `Sheet2`/`Sheet3`），
由 `ExportFormat.EXCEL` 驱动。

但**报表 ≠ 导出**：

| | `ExcelWriter`（导出） | 报表 |
|---|---|---|
| 结构 | 一个 Sheet、表头 + 数据行 | 标题 + 元信息 + **多个区块** |
| Sheet 命名 | `Sheet1` / `Sheet2`（按行数上限切） | **有业务含义**（`区域汇总` / `订单明细`） |
| 样式 | 无 | 表头底色、加粗、斑马纹、冻结首行、自动筛选 |
| 数字格式 | 无（`BigDecimal` 直接 `toString`） | 千分位 / 货币 / 百分比 / 日期 |
| 标题与来源 | 无 | 有（谁生成的、什么时候、什么过滤条件） |
| 产出物 | 数据 | **可发给他人的结论** |

结论：**新增 `engine/report/` 包**，与 `engine/export/` **并列**而不是合并。
理由是 `ExportWriter` 的契约是「**一行流式追加**」，报表是「**多区块布局**」——
两种数据流形状硬合并会让 5 个导出 writer 全部沾上报表概念。

**要复用的部分**：类型分派（`BigDecimal` / `Date` / `Timestamp` / `LocalDate` / `LocalDateTime`
→ 单元格）抽成共享的 `CellValueWriter`，两边都用。

### 7.2 `ReportSpec`（纯数据，不含 POI）

放在 `:shared` 的 `commonMain`（平台无关，且 UI 与引擎都要读它）。

```kotlin
@Serializable
data class ReportSpec(
    val schemaVersion: Int = 1,      // 演进用；引擎对不认识的版本明确报错
    val title: String,
    val subtitle: String? = null,
    val metadata: List<MetadataEntry> = emptyList(),  // 数据源 / 生成时间 / 过滤条件
    val sections: List<ReportSection>,
    val style: ReportStyle = ReportStyle(),
)

@Serializable
data class ReportSection(
    val id: String,
    val kind: SectionKind,            // TABLE | KPI | TEXT
    val title: String,
    val query: ReportQuery,           // { sql, params }
    val columns: List<ReportColumn> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ReportColumn(
    val key: String,                   // 结果集列名
    val header: String,               // 表头文字
    val type: ColumnType,             // TEXT | INTEGER | DECIMAL | PERCENT | CURRENCY | DATE | DATETIME
    val format: String? = null,       // Excel number format，如 "#,##0" / "¥#,##0.00" / "0.0%"
    val aggregate: Aggregate? = null, // SUM | AVG | COUNT | MAX | MIN（KPI 区块用）
    val width: Int? = null,
)

@Serializable
data class ReportStyle(
    val headerBackground: String? = null,  // 默认取主题 primaryContainer
    val zebra: Boolean = true,
    val freezeHeader: Boolean = true,
    val autoFilter: Boolean = true,
    val fontSize: Int = 11,
)
```

> **为什么 `ReportSpec` 在 `commonMain` 而不在 `desktopApp`**：它要被**三处**读 ——
> UI 的报表设计器（构造/编辑）、引擎的 `ReportBuilder`（渲染）、以及将来的 AI（生成）。
> 放在 `desktopApp` 会让引擎反过来依赖 UI 模块。

### 7.3 `ReportBuilder`（引擎侧，POI）

`engine/src/main/kotlin/com/langbiantianya/report/`：

- `ReportBuilder` —— `ReportSpec` + 数据 → `.xlsx`
- `ReportCellStyler` —— 把 `ReportStyle` / `ReportColumn` 落到 POI `CellStyle`（**样式对象要缓存复用**，否则百万行的表会创建百万个 style）
- `CellValueWriter` —— 从 `engine/export/ExcelWriter.kt` 抽出来的共享类型分派

> ⚠️ **POI 样式对象的坑**：`XSSFCellStyle` 每建一个就是一份 XML，开到几万行会 OOM。
> 报表的列数有限（通常 < 30），按「列类型 × 样式」缓存几十个 style 即可 ——
> 这条要写进 `ReportCellStyler` 的 KDoc，否则下一个人会 inline 写 `createCellStyle()`。

### 7.4 协议

```
Category.ANALYSIS = 15    // v2.29
Category.REPORT   = 16    // v2.29
Action.RUN_ANALYSIS_QUERY = 26
Action.PULL_SAMPLE        = 27
Action.LIST_SOURCES       = 28
Action.BUILD_EXCEL        = 29
```

`REPORT.BUILD_EXCEL` 的请求体：

```protobuf
message ReportRequest {
  string spec_json = 1;   // ReportSpec 的 JSON
  string output_path = 2; // 目标 .xlsx 路径
}
```

> **为什么 `spec_json` 走 JSON 而不是把 `ReportSpec` 铺成一棵 proto message**：
>
> ① `ReportSpec` **还在演进**（AI 迭代要加字段），proto message 一旦发布就要做兼容；
> ② 引擎**已经有** `kotlinx.serialization`（`ExportRequest` / `ExportCommand` 都在用），
>    JSON 零新增依赖；
> ③ proto 里铺一棵树会让 `idb_engine.proto` 膨胀 200+ 行，而它同时服务着 gRPC 与 Direct 两种模式。
>
> **代价**：失去 proto 的类型检查与向后兼容保证。
> **缓解**：`ReportSpec.schemaVersion` 必带，引擎遇到不认识的版本**明确报错**而不是猜。

---

## 8. AI 洞察：扩展点怎么留

> 用户要求「后续迭代接入 ai 实现智能分析制作报表」。v1 不做，但**接口要留对**——
> 留错了将来要动执行链路，那就是大改。

### 8.1 唯一的一条铁律

> **AI 只产出 `ReportSpec`，不产出文件。**

理由：

1. **安全**：执行链路不变，AI 挂掉不影响手动出报表。
2. **可回放**：`ReportSpec` 里存着 SQL 与参数，同一份 spec 永远出同一份文件。
3. **可审阅**：报表里每个数字都能追到一条用户看得见（且能改）的 SQL。
   AI 若是直接写文件，用户就永远不知道那些数字是怎么来的 —— **报表的可信度就断了**。
4. **可测试**：`ReportSpec` 是纯数据，AI 的产出可以直接断言「它选了哪张表、用了什么聚合」。

### 8.2 接口

```kotlin
// engine/src/main/kotlin/com/langbiantianya/analysis/InsightProvider.kt
interface InsightProvider {
    /** 输入：目标源的 schema 摘要 + 样本统计 + 用户问题 */
    suspend fun suggest(context: InsightContext): ReportSpec
}
```

- `InsightContext` 里放**受控的摘要**（表名、列名、类型、行数估计、少量样本统计），
  **不放原始数据**——把整表塞给模型既贵又不安全。
- 生成的 SQL 同样过 §3.4 的只读闸门。
- 引擎侧预留路由 `ANALYSIS.SUGGEST`（v1 不实现，返回「未启用」）。

### 8.3 配置落在共享设置页

AI 的模型端点 / 模型名 / API Key 等，**放在现有设置页的新增「数据分析」分类里**（§1.3），
不另起一个设置界面 —— 复用是本轮评审意见的主旨之一。

> 🔐 凭据怎么存：与现有连接配置同级的明文存储是**现状**，
> 但 AI 的 API Key 比数据库口令更敏感。v1 不做 AI，所以**这一项留到 M7 决定**，
> 不要在设计阶段假装它不存在（见 §13 Q8）。

### 8.4 UI 占位

分析工作台工具条右侧放一个「AI 洞察」按钮：v1 **禁用** + tooltip「后续迭代提供」。
位置摆在那里，是为了让 v1 的布局**不因 M7 而重排**。

---

## 9. 界面设计

完整设计图：[`ui-mockup.html`](./ui-mockup.html)（浏览器直接打开，顶部可切 5 套配色 × 明暗 × 6 个画面）。

### 9.1 样式完全复用现有资源

**一行新样式代码都不写。** 依据：

- 主题入口 [`shared/.../ui/Theme.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/Theme.kt)
  的 `SundaysTheme` —— 分析模式的所有界面都在它内部渲染，配色 / 明暗 / 紧凑档**自动继承**。
- 配色 [`shared/.../ui/ThemePalette.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/ThemePalette.kt)：
  5 套（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）× 明暗 2 档 = 10 套 `ColorScheme`。
- 真实色值见 [`theme-tokens.json`](./theme-tokens.json) —— 这是**从
  `SundaysPalette.kt` 脚本抽取**的快照（10 套 × 28 个 Material3 角色 + 9 条字号阶梯），
  不是手抄的。设计图里的每一个颜色都能在这个文件里找到出处。
- 组件复用清单见 §6.2；控件外观统一走 `UiChrome.kt` 的 token
  （`winShape` / `controlShape` / `uiBevel` / `selectionColorsFor`）。

### 9.2 设计约束（与现有界面一致）

来自 `SundaysPalette.kt` 的 KDoc，设计图必须体现：

1. **不用 tonalElevation 叠色分层** —— 层次靠 **1px `outlineVariant` 分隔线**，不靠阴影。
2. **圆角 4~8dp**（现代三套 6px；Win2000 全直角 0；WinXP Luna 递增）。
3. **字号比 M3 默认小 1sp**（`bodyMedium` 13sp / `labelSmall` 11sp）—— 信息密集的桌面观感。
4. **WCAG AA**（≥4.5:1）由 `SundaysPaletteTest` 逐对钉住，设计图不得引入新配色破坏它。

### 9.3 每个界面区域 ↔ 复用的组件

**这张表是本节与 §6 的接口** —— 设计图上看到的每一块，都能在这里查到它由哪个现成组件渲染。

| 画面 | 界面区域 | 复用的组件（`:shared` commonMain） | 新增？ |
|---|---|---|---|
| ⓪ 启动选择 | 两张模式卡片（图标 + 标题 + 说明，整块可点） | `StartScreen`（`Surface` + `winShape` + `uiBevel`） | ★ 新增（`:shared` 的 `navigation/`） |
| ① 顶层导航 | 顶栏本体（品牌 + ⚙ 设置 —— **目标 chip 已去掉**，见 v1.4） | `TopNavBar` + `SettingsEntryButton` | ★ 新增（`:shared` 的 `navigation/`） |
| ② 分析工作区 | 编辑器工具条上的**「格式化」与「▶ 运行」**（同一行） | `WinButton` / `WinOutlinedButton` / `WinIconButton` | —— |
| ② 分析工作区 | **没有**「⊘ 只读」徽标（v1.8 删掉了 —— 远端恒为只读的保证在引擎与方言层，组件层不再摆它） | —— | —— |
| ② 分析工作区 | 左侧**工作区树**（`ObjectTree` —— **与浏览模式的对象树同一个组件**：库 → 表 → 字段；内部用 `EmptyHint` / `ColumnBadge` / `focusRing` / `onRightClick` / `winShape`） | ★ 新增（§6.4-1，**两棵树共用**） |
| ② 分析工作区 | 树头上 **「+ 添加数据源」**（分析模式**专属**——不共用浏览模式的「添加连接」） | `WinIconButton` + `Icons.Filled.Add` | —— |
| ② 分析工作区 | 中间 SQL 编辑器 | `CodeEditorWithToolbar` / `CodeEditorState` / `SqlFormatter` | —— |
| ② 分析工作区 | 结果表 | `DataTable` + `TableColumn` / `TableRow` / `PageSize` / `DataTableTheme` | —— |
| ② 分析工作区 | 中右分隔条 | `DragHandle`（`hoverIcon` 由桌面端注入） | —— |
| ② 分析工作区 | 底部状态条 | `WinProgressIndicator` / `WinTextField`（只读展示） | —— |
| ② 分析工作区 | 右键菜单 | `ContextMenuState` + `onRightClick` | —— |
| ③ 报表设计器 | 左侧区块列表 | `ReportBlockList` | ★ 新增（§6.4-2） |
| ③ 报表设计器 | 中间列规格 | `ReportColumnSpecRow` + `WinDropdown` | ★ 新增（§6.4-3、§6.3.4） |
| ③ 报表设计器 | 右侧 Excel 预览 | `DataTable`（只读）+ `WinSurface` | —— |
| ④ 连接管理器 | **整屏** | `ConnectionManagerScreen`（**同一个**，只换分组方式） | —— |
| ⑤ 连接向导 | **整屏** | `AddConnectionDialog` / `ConnectionWizardContent`（**同一个**） | —— |
| ⑤ 连接向导 | 认证方式下拉 | `WinDropdown` | ★ 新增（§6.3.4） |
| 全部 | 顶栏右上角 | `SettingsEntryButton`（⚙ 图标按钮） | —— |
| 全部 | 主题 / 圆角 / 立体边 / 选中色 | `SundaysTheme` / `UiChrome` token | —— |
| 全部 | 文案 | `str(StrKey)` + `i18n` 的 6 语言表 | —— |

> **统计**：本模式**零改动复用的组件 19 项**，**新增组件 5 项**（其中 1 项是扩充 `WinControls`）。
> 没有一项是「另写一套看起来差不多的」。

> ⚠️ **顶栏右上角只放一个 ⚙ 设置入口**（与浏览模式同一个 `SettingsEntryButton`），
> **不放主题切换、不放语言切换**。理由跟真实应用一致：
> `desktopApp/.../main.kt` 的 KDoc 写明「外观切换……**只在设置页**渲染明暗切换与配色选择」，
> `ConnectionManagerScreen.kt` 的 KDoc 也写着「『个性化 → 明暗档位』单选组里，
> 标题行**不再放应用级外观按钮**」。
> 顶栏挂「外观」「语言」两个入口，会让人以为点一下就能换配色，实际却只能先进设置页 ——
> 多出来的两个按钮既不兑现承诺，又和「明暗档位在设置页里」这条唯一真相源打架。

### 9.4 六个画面

| 画面 | 内容 |
|---|---|
| **⓪ 启动选择（首屏）** | 顶栏只有**品牌 + ⚙ 设置**；中间是「选择要进入的模式」+ 两张模式卡片（**连接管理** / **数据分析**），各带一句话说明。选完一路走下去 |
| **① 分析模式说明** | 讲清本模式的边界与「复用什么」。 |
| **② 分析工作区** | 左：**树**（**根 = 工作区的各个 schema**：`main` 是工作区自己的表，每个**数据源**一个 —— 树与浏览模式的对象树**同形**：库 → 表 → 字段；展开一张表能看到它的字段）。树的**头上**有一个 **「+」** —— 打开分析模式专属的「添加数据源」对话框（远程数据库 / 文件）。中：SQL 编辑器（**工具条上「格式化」旁边就是「▶ 运行」**）+ 结果表（带占比条形）。分析屏上**没有**独立工具条，也没有只读徽标。 |
| **③ 报表设计器** | 左：区块列表（可增删排序）。中：区块配置（列规格 / 类型 / 数字格式 / 样式）。右：Excel 观感预览（标题 + 元信息 + 表头 + 斑马纹）。 |
| **④ 连接管理器（复用 + 通用化后）** | **同一个**连接管理器：列表按「可浏览 / 可分析」分组、只读方言带 ⓘ 徽标；右侧是选中连接的详情（含「只读方言」说明与「在数据分析中打开」）。 |
| **⑤ 新增连接向导（复用 + 通用化后）** | **同一个**添加连接弹窗，但表单由**方言自描述**渲染：认证方式下拉（账密 / Kerberos）、方言特有字段、「高级属性」折叠区、以及**自定义 JDBC URL** 逃生舱。 |
| **⑥ 组件复用对照（v0.3 新增）** | 一张对照表：每个界面区域 → 复用的现成组件 / 新增的组件，并标出各自落在 `:shared` 的哪个文件。它把 §6 与 §9.3 变成**能看见的东西**。 |

> ⚠️ 画面 ④⑤ 与 v0.1 的差别：v0.1 画的是一个**专属的**「添加分析数据源」弹窗，
> v0.2 改成**复用的**连接管理器与向导 —— 这是第二轮评审意见的直接结果。
>
> ⚠️ 画面 ⑥ 是 v0.3 新增的：它不是「另一个界面」，而是**本设计的自证** ——
> 第三轮评审意见说「组件和控件也尽量复用」，这一屏就是「复用了哪些」的答案。

---

## 10. 代码落点

### 10.1 新增模块

| 路径 | 内容 |
|---|---|
| `shared/src/commonMain/.../analysis/` | `ReportSpec.kt`（报表规格）、`AnalysisScreen.kt`、`ReportDesignerScreen.kt` |
| `shared/src/commonMain/.../analysis/AnalysisModels.kt` | 数据源条目（id / 名称 / 方言标签）+ 结果模型 —— **只带界面显示需要的字段**：凭据与连接配置留在 desktopApp 侧，这一层不携带凭据、也不决定连不连 |
| `shared/src/commonMain/.../analysis/AnalysisPull.kt` | 拉取的**纯模型**：进度（已读行数）、结果、终态模型（sealed）、用户取消的专用异常 |
| `shared/src/commonMain/.../tree/ObjectTreeModel.kt` | **两棵树共用的模型**（纯数据）：节点种类（库 / 表 / 字段）、字段信息、对象组、「按 id 找 / 按 id 替换」等纯规则 |
| `shared/src/commonMain/.../tree/ObjectTree.kt` | **两棵树共用的组件**：库 → 表 → 字段的渲染（箭头、图标、字段行的 `PK/AI/非空` 标记）、展开 / 收起、点表 / 点字段的回调、右键回调 |
| `desktopApp/src/main/.../AnalysisSourceDialog.kt` | **「添加数据源」对话框** —— 分析模式**专属**（不共用浏览模式的「添加连接」）：远程数据库（方言 / 主机 / 端口 / 库 / 用户 / 密码 + **驱动预览**库与表 + **勾选**）与文件 / 文件夹（路径 + 格式） |
| `shared/src/commonMain/.../analysis/ReportBlockList.kt` | 报表区块列表（**新增组件 2/5**） |
| `shared/src/commonMain/.../analysis/ReportColumnSpecRow.kt` | 列规格行（**新增组件 3/5**） |
| `shared/src/commonMain/.../analysis/PullProgressIndicator.kt` | 拉取进度（**新增组件 4/5**；原名 `PullStageIndicator` —— 联邦直查没有「阶段」） |
| `shared/src/commonMain/.../navigation/TopNavBar.kt` | 顶层导航条（品牌 + 目标切换 chip + ⚙ 设置入口）—— 只在**并列的顶层目标**渲染 |
| `shared/src/commonMain/.../editor/language/SqlDialectProfileRegistry.kt` | 方言档位注册表（§6.3.1） |
| `desktopApp/src/test/.../AnalysisModuleBoundaryTest.kt` | 组件复用结构测试（§6.6）—— 落在 `desktopApp` 而不是 `:shared`：它要读 `:shared` 的**源文件**（按 import 行判定），而 Gradle 跑测试时的工作目录是**模块根** |
| `desktopApp/src/main/.../AnalysisSession.kt` | 分析模式的会话与状态机（与 `ConnectionSession` / `DatabaseBrowserState` **并列**，不继承） |
| `desktopApp/src/main/.../AnalysisPuller.kt` | 拉取链路的**契约**（接口）—— 抽出来是为了让会话的终态映射可被**确定性地**测 |
| `desktopApp/src/main/.../EngineAnalysisPuller.kt` | 拉取链路的**真实现**：一条 `ANALYSIS.PULL_TABLE` 路由（引擎侧是 **DuckDB 联邦直查**，**不落盘、不绕 CSV**）+ 取消时用 `SYSTEM.CANCEL` 叫停引擎 |
| `engine/src/main/.../analysis/AnalysisHandler.kt` | `Category.ANALYSIS` 的路由实现（含 `executePull` —— 联邦直查的编排） |
| `engine/src/main/.../analysis/JdbcScanTableFunction.kt` | **纯 Java 表函数**：把一条 JDBC 查询变成 DuckDB 里的一张表（类型映射 + 分块写出） |
| `engine/src/main/.../analysis/JdbcScanRegistry.kt` | 表函数的**幂等注册**（名字由远端配置派生、查重、并发安全） |
| `engine/src/main/.../analysis/InsightProvider.kt` | AI 扩展点（v1 只有接口） |
| `engine/src/main/.../report/ReportBuilder.kt` | `ReportSpec` → .xlsx |
| `engine/src/main/.../report/ReportCellStyler.kt` | 样式映射（**带缓存**） |
| `dialect-contract/src/main/.../contract/ConnectionFormSpec.kt` | **方言自描述的连接表单规格**（§5.2）—— ⚠️ **落点已改**：不在 `:api`（`:shared` 看不见它），而是一个新的契约模块，与 `:i18n` 同构。含 `ConnectionFormSpec` / `ConnectionForm` / `ConnectionField` / `FieldType` / `ConnectionFieldIds` / `ConnectionFormTypes` |
| `dialect-contract/src/main/.../contract/AuthMethod.kt` | 认证方式枚举（**从 `:shared` 搬来**；序列化名不变，磁盘格式无变化）+ `fromName` |
| `dialect-contract/src/main/.../contract/DialectConnectionSpec.kt` | 一次连接尝试的**参数值**（host / port / database / properties）—— 与「形状」是一对，方向相反 |
| `dialect-contract/src/main/.../contract/StandardForms.kt` | 常用形状的构造器（客户端/服务端六格、嵌入式两格）—— MySQL 与 PG 的形状完全相同，不该各写一遍 |
| `dialect-contract/src/test/.../ConnectionFormSpecTest.kt` | 契约的纯逻辑测试（查表规则 / 认证方式往返 / **序列化名 = 常量名** / 常用形状的字段清单） |
| `dialect-hive/` | Hive 只读方言（SPI 注册照抄 `dialect-duckdb` 的 `META-INF/services`） |
| `dialect-impala/` | Impala 只读方言（**与 Hive 共用 `jdbc:hive2:` 与同一个 `hive-jdbc` 驱动**，消歧靠连接配置里的 `driver` 字段 —— §4.6 的 D2 / D4，**不**用自定义 URL 前缀） |

### 10.2 改动既有文件

| 文件 | 改动 |
|---|---|
| `shared/.../navigation/AppDestination.kt` | 加 `ANALYSIS(StrKey.NAV_ANALYSIS)` |
| `desktopApp/.../main.kt` | `MainScreen` 的 destination 分支加分析模式 |
| `shared/.../connection/ConnectionConfig.kt` | 加 `authMethod` / `properties`；`ConnectionType` 加 `CUSTOM_URL`；**八处硬编码 `when` 收进方言元数据表**（§6.3.2）。⚠️ `DialectType` 加 `HIVE` / `IMPALA` 是 **M3** 的事（§6.3.2 的边界说明） |
| `shared/.../connection/AddConnectionDialog.kt` | 表单渲染改为**优先读方言的 `connectionForm`**，读不到才回退旧分支（M1 第二片） |
| `shared/.../connection/DialectFormRegistry.kt` | **新增**：方言 → 表单规格的注册表。**可空** = 回退旧分支（Q6 的双向通道） |
| `shared/.../connection/ConnectionFormFields.kt` | **新增**：按 spec 渲染字段（文本 / 密码 / 整数 / 下拉 / 只读展示）+ 认证方式下拉 + 字段值 ↔ 配置的读写 |
| `shared/.../notifications/NotificationCenter.kt` | **新增**（从 `desktopApp` 上移）：通知中心（屏级共享、按来源去重、原文不截断）+ `AppNotification` |
| `shared/.../notifications/NotificationCenterPanel.kt` | **新增**（从 `desktopApp` 上移）：通知面板；时间格式化走 `timeFormatter` **注入参数**（本地时区是平台知识） |
| `shared/.../notifications/NotificationBell.kt` | **新增**（从 `desktopApp` 上移）：通知铃入口按钮（铃铛 + 未读数） |
| `shared/.../browser/BrowserPane.kt` | **新增**（从 `desktopApp` 上移）：右栏展示模式枚举（TABLE / SQL / GENERATE） |
| `shared/.../browser/SheetSummary.kt` | **新增**：标签条要的**窄数据形状**（id / 名称 / 状态）—— 不认持有引擎状态的 `SheetDescriptor` |
| `shared/.../browser/BrowserToolBar.kt` | **新增**（从 `desktopApp` 上移）：sheet 标签条 / 工作台切换按钮 / 空态引导 |
| `engine-protocol/.../idb_engine.proto` | `DialectInfo.connection_form`（新消息 `DialectForm` / `DialectFormVariant` / `DialectFormField`） |
| `engine-protocol/.../grpc/DialectFormMapping.kt` | **新增**：契约 ⇄ proto 的**双向**映射（两个方向必须放一起 —— 见 §5.2 的落点修正） |
| `engine/.../engine/DialectInfoMapper.kt` | **新增**：方言 → proto `DialectInfo`（`listDrivers()` 原来内联 13 个字段，抽出来才可单测） |
| `desktopApp/.../DialectFormRegistryFactory.kt` | **新增**：引擎的方言列表 → 前端注册表（换算只用已有的 `engineDriverName` 表，不另写一份映射）；同一次 `SYSTEM.LIST_DRIVERS` 换算出**表单 + 能力位**两半（`DialectCatalog`）—— 分两次拉会让两个快照不在同一时刻 |
| `desktopApp/src/test/.../DialectFormContractTest.kt` | **新增**：连接类型名的**三处一致性**（契约字符串 / `:api` 枚举 / `:shared` 枚举）—— 漂移的症状是**静默回退旧分支**，只能靠测试钉住 |
| `desktopApp/.../ConnectionSession.kt` | 持有 `dialectForms` + `refreshDialectForms()`（启动时拉一次，失败保持空注册表）+ `analysisCapableDialects`（声明了 `ANALYSIS_SOURCE` 的方言集合，**来自引擎**） |
| `shared/.../connection/DialectMetadata.kt` | **新增**：前端方言元数据的唯一真相源（`mapOf` 表） |
| `shared/.../connection/ConnectionStorage.kt` | 持久化版本 2 → 3，v2 记录补齐新字段；**自定义 URL 靠「前缀对不上」识别**，加载后仍是自定义 URL |
| `shared/.../settings/SettingsCategory.kt` | 加「数据分析」分类（AI 配置 / 报表默认样式 / 输出目录） |
| `shared/.../ui/WinControls.kt` | **加** `WinDropdown` / `WinDropdownMenu`（**新增组件 5/5**，§6.3.4） |
| `shared/.../editor/SqlDialectProfileRegistry.kt` | **新增**：方言档位注册表（§6.3.1） |
| `shared/.../editor/language/SqlDialectProfile.kt` | `ALL` 降级为快照；新增 `SqlDialectProfileRegistry`（§6.3.1） |
| `shared/.../editor/formatter/SqlFormatter.kt` | `register()` 改成遍历**注册表**而非 `ALL`（§6.3.1） |
| `shared/.../table/DialectCapabilities.kt` | `of(dialect)` 改查方言元数据表（§6.3.2） |
| `desktopApp/.../DatabaseBrowserState.kt` | `toSqlDialectProfile()` 改从「方言 → 档位」映射表读（§6.3.1） |
| `api/.../DatabaseDialect.kt` | 加 `connectionForm` 属性 + `buildJdbcUrl(spec)` 新重载 |
| `api/.../DialectCapability.kt` | 加 `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT` |
| `engine-protocol/.../idb_engine.proto` | `Category.ANALYSIS/REPORT` + 新 Action + `ReportRequest` + `ConnectionConfig.read_only` |
| `engine/.../dispatcher/RequestDispatcher.kt` | 只读闸门（L4） |
| `engine/.../export/ExcelWriter.kt` | 类型分派抽到 `CellValueWriter`（**不改变现有行为**） |
| `i18n/src/main/.../StrKey` + 6 张文案表 | 新增文案键（**六语言必须同步**） |
| `settings.gradle.kts` | `include(":dialect-hive")` / `include(":dialect-impala")` |
| `desktopApp/build.gradle.kts` | `runtimeOnly(project(":dialect-hive"))` 等 + 驱动依赖；**加** `implementation(project(":api"))` —— 前端要按**引擎侧同一个** `DialectCapability` 枚举判「哪些方言可分析」，前端另写一份名单就是第二个真相源 |
| `gradle/libs.versions.toml` | `hive-jdbc` 版本（spike 结论出来后定） |

### 10.3 打包要留意

`desktopApp/build.gradle.kts` 的 jlink `modules(...)` 清单是**手工枚举**的（12 个），
注释里写明「升级依赖后要重算一遍」。Hive 驱动会引入 Thrift / SLF4J / 可能的 SSL 依赖，
**新模块可能要补进 jlink 清单** —— 否则症状是「装得进去却启不了」（注释里已写明这个坑）。

---

## 11. 迭代任务拆分

> 每个里程碑都以「**用户能看见的东西**」结束，不以「代码写完了」结束。
> 每条验收标准都写成**可判定**的（能跑出一条命令 / 能点出一个现象），
> 避免「基本能用」这种没法验收的说法。

### M0 —— 设计冻结 ✅（v0.1 + v0.2 + v0.3）

- **交付**：[本文](./README.md) + [UI 设计图](./ui-mockup.html) + [主题色值快照](./theme-tokens.json)
- **验收**：本文的每条「现状」陈述都能在仓库里找到对应文件；UI 设计图在 10 种配色 × 6 个画面下都能读。
- **产出**：待定问题（§13）逐条有结论。

### M1 —— 连接管理器与组件通用化 ★（前置）

> 标题从「连接管理器通用化」改成「连接管理器**与组件**通用化」：
> §5 通用化的是「**表单**怎么描述」，§6 通用化的是「**控件**怎么描述」——
> 两者是同一件事的两半，拆成两个里程碑反而会让「先通用化再接方言」这件事只做一半。

- **交付 —— §5 连接管理器侧**
  - `api`：`ConnectionFormSpec` / `ConnectionField` / `AuthMethod`；`DatabaseDialect.connectionForm`；
    `buildJdbcUrl(spec)` 新重载（**旧方法保留**）
    —— ⚠️ **落点已改**（§5.2）：`ConnectionFormSpec` 放**新的契约模块**（与 `:i18n` 同构），
    不放 `:api` —— `:shared` 看不见 `:api`
  - `api`：`DialectCapability` 加 `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT`
  - `shared`：`ConnectionConfig` 加 `authMethod` / `properties`；`ConnectionType.CUSTOM_URL`
  - `shared`：连接向导按 `connectionForm` 渲染（优先读 spec，回退旧分支）
  - `shared`：连接持久化 v2 → v3 + 迁移
  - `shared`：连接管理器按能力位分组 + 只读徽标
- **交付 —— §6 组件侧**
  - `shared`：`SqlDialectProfileRegistry`（§6.3.1）；`SqlFormatter.register()` 改遍历注册表
  - `shared`：`DialectType` 的**八处**硬编码 `when` 收进方言元数据表（§6.3.2）
  - `shared`：`WinControls.kt` 加 `WinDropdown` / `WinDropdownMenu`（§6.3.4）
  - `shared`：`AnalysisModuleBoundaryTest` 结构测试（§6.6）
- **M1 已落地（v0.4，第一片）** ✅
  - 方言元数据表 `DialectMetadata`（八处 `when` → 一张 `mapOf`）
  - `SqlDialectProfileRegistry`（语言 + formatter 成对注册）
  - `WinDropdown` / `WinDropdownMenu`（含 8 条纯逻辑测试）
  - `ConnectionConfig`：`authMethod` / `properties` / `ConnectionType.CUSTOM_URL`；
    持久化 **v2 → v3**（两字段带默认值，v2 无需迁移也能读）
  - `DialectCapability` 三个能力位（**先只加枚举值，不声明** —— 声明了前端就会渲染出对应入口）
  - i18n：`MODE_CUSTOM_URL` / `DESC_CUSTOM_URL` **六语言同步**
  - ⚠️ **Hive / Impala 的 `DialectType` 与元数据行留给 M3**（§6.3.2 的边界说明）
- **M1 已落地（v0.5，第二片）** ✅
  - **新模块 `:dialect-contract`**（与 `:i18n` 同构）：`ConnectionFormSpec` / `ConnectionForm` /
    `ConnectionField` / `FieldType` / `AuthMethod` / `DialectConnectionSpec` / `StandardForms`
  - `api`：`DatabaseDialect.connectionForm`（默认**空 spec** = 「没声明」）
    + `buildJdbcUrl(spec)` 新重载（旧方法原样保留）
  - **5 个内置方言各自声明规格**（MySQL / PG 六格、H2 两份 form、DuckDB / SQLite 两格），
    形状与旧分支**逐格对齐** → 两套分支产出同一个界面
  - `engine-protocol`：`DialectInfo.connection_form`（新消息 `DialectForm` / `DialectFormField`）
    + 契约 ⇄ proto 的双向映射（`DialectFormMapping.kt`）
  - `engine`：`DialectInfoMapper`（方言 → `DialectInfo`），`listDrivers()` 瘦身
  - `shared`：`DialectFormRegistry`（可空 = 回退旧分支）+ `ConnectionFormFields`（按 spec 渲染）
  - `desktopApp`：启动时拉一次 `SYSTEM.LIST_DRIVERS` → 注册表 → 喂给向导与弹窗
  - i18n：8 个新键（JDBC URL 标签 / 认证方式 ×3 / 认证方式标签 / 三个方言特有字段标签）
    **六语言同步**
- **共享 UI 上移（Q9 落地）** ✅ —— 独立于 M1 的结构整理，为 M2/M3 铺路
  - `shared/.../notifications/`：`NotificationCenter`（纯状态）+ `NotificationCenterPanel`
    + `NotificationBell` —— 整套从 `desktopApp` 上移，**时间格式化改为注入参数**
    （本地时区是平台知识，`commonMain` 没有 `Calendar` 也没有时区转换）
  - `shared/.../browser/`：`BrowserPane` 枚举 + `SheetSummary`（窄数据形状）
    + `SheetTabRow` / `EmptySheetsHint` / `BrowserToolBar` —— 从 `desktopApp` 上移
  - **顺带清掉 443 条未使用 import**（其中 **255 条是 proto import**）——
    仓库曾有一批重构残留的 proto import，让「这个文件碰不碰引擎」在**文件层面**失真，
    看 import 会误判一批纯 UI 组件「搬不动」。清理后 §1.3 的判据重新可信
  - ⚠️ **`SchemaTree` / `TablePreview` / `SqlWorkbench` / `ExportDialogs` / `GenerateWorkbench`
    仍搬不动**：它们的入参是 `DatabaseBrowserState`（4200 行、35 处 proto 请求构造）。
    要上移得先把它拆成「纯状态 + 引擎动作接口」两层 —— 那是**契约级**重构，
    归 M3，**不夹带**
- **M1 未做（留给 M3 或后续）**
  - `properties` 里 secret 字段的 UI 脱敏与导出剔除
  - 连接管理器按能力位分组 + 只读徽标（§5.5）—— 那要等 M3 的 `ANALYSIS_SOURCE` 真有成员
- **M1 验收**
  - **加一个假方言**（3 个自定义字段 + 2 种认证方式）→ 连接管理器**自动**渲染出这 3 个字段与认证下拉，
    **前端零改动**（§5.7）✅
  - **现有 5 个方言的连接向导与 `connection.json` 测试全绿且未修改** ✅
  - **连接类型名的三处一致**（契约字符串 / `:api` 枚举 / `:shared` 枚举）✅
    （`DialectFormContractTest` —— 漂移时症状是**静默回退旧分支**，界面正常但 spec 失效）
  - `CUSTOM_URL`：填认识的 URL → 正确反查方言；填不认识的 → 明确报错 ✅
  - v2 的 `connection.json` 加载后自动补齐新字段 ✅
  - `properties` 里的 secret 字段在 UI 脱敏、在导出配置时剔除 ⏳（M3）
  - **运行时注册一个假方言档位** → `CodeEditor(languageId = "sql-fake")` 立刻有高亮、
    格式化按钮可用（§6.3.1）✅ —— `SqlDialectProfileTest` 的
    `a profile registered at runtime gets a language and a formatter`
  - `WinDropdown` 在 **10 种配色**下都不出现 Material3 圆角（现代三套 6px / Win2000 全 0 / WinXP 递增）✅
  - `AnalysisModuleBoundaryTest` 绿（无 `desktopApp` 依赖、无 `java.*`、无字面量颜色）✅
    —— M2 建起 `analysis/` 包时一并落地（`desktopApp` 侧跑：它读 `:shared` 的**源文件**，
    而 Gradle 跑测试时的工作目录是**模块根**）
- **依赖**：无
- **风险**：R9（前端表单迁移期的新旧两套渲染分叉 —— 用「优先读 spec + 回退」控制）、
  R12（组件复用退化 —— 用 §6.6 的结构测试钉住）

### M2 —— 只读地基

> **v0.6：本轮落地（M2 + M4' 一起做）** —— 实现细节见
> [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md)。
>
> ✅ **v0.7：M2 已落地，分三片** —— 逐条清单见下。

- **交付**
  - proto：`Category.ANALYSIS` / `Category.REPORT` / 新 Action / `ConnectionConfig.read_only`
  - 引擎：`RequestDispatcher` 的只读闸门（L4）；`ANALYSIS` 的**四条**只读路由
    （`LIST_SOURCES` / `LIST_TABLES` / `LIST_COLUMNS` / `RUN_QUERY`）
  - UI：`AppDestination.ANALYSIS` + 分析工作台主屏 + **不可关闭**的只读徽标（§6.4-4）
  - 组件层只读表达（§6.5）：结果表 `onCellEdit = null`、**远端没有 SQL 编辑器**、
    徽标不可关闭（原文那条「编辑器只放行只读语句」已作废，见 §6.5 的更正）
  - 测试：§3.6 的两类回归测试（方言级 + 会话级）
- **M2 已落地（v0.7，分三片）** ✅
  - **片1 协议** —— `Category.ANALYSIS` / `Category.REPORT`、**五个** Action
    （`LIST_SOURCES` / `LIST_TABLES` / `LIST_COLUMNS` / `RUN_QUERY` / `BUILD_EXCEL`，
    最后一个只落枚举不实现路由）、`ConnectionConfig.read_only`、
    `AnalysisRequest` / `AnalysisResponse`（列表响应复用既有的 `TableListItem` /
    `ColumnDef` / `Row`，不另立消息类型）
  - **片2 只读闸门（L4）** —— 闸门放在 `RequestDispatcher` 的**查路由表之前**：
    `read_only = true` 的会话命中写路由一律拒绝，**不进入 handler**
    （判据写在 handler 里，测试就只能退化成「断言错误文案」）。
    配**方言级**（L3）与**会话级**（L4）两级回归测试
  - **片3 四条路由 + 顶层导航 + 只读徽标 + 能力位声明**
    - 引擎：[`engine/.../analysis/AnalysisHandler.kt`](../../engine/src/main/kotlin/com/langbiantianya/analysis/AnalysisHandler.kt)
      + `RequestDispatcher` 的四条路由注册；`RUN_QUERY` 只允许打在声明了
      `EMBEDDED_MODE` 的方言上，行数上限 5000、超出时 `truncated` 显式置位
    - 能力位：`dialect-mysql` / `dialect-postgresql` 声明 `ANALYSIS_SOURCE`；
      前端按**引擎下发的**能力位筛数据源（`DialectCatalog` 一次换算表单与能力位），
      **不另写一份**「MySQL / PG 可分析」的名单
    - UI：`AppDestination.ANALYSIS` + [`shared/.../navigation/TopNavBar.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/navigation/TopNavBar.kt)
      （品牌 + 两个 chip + ⚙ 设置入口）+ `shared/.../analysis/`
      （`AnalysisModels.kt` / `ReadOnlyBadge.kt` / `AnalysisScreen.kt`）
    - 测试：引擎的 `AnalysisHandlerIntegrationTest`（12 例）+ `desktopApp` 的
      `AnalysisCapabilityContractTest` / `AnalysisModuleBoundaryTest` / `AnalysisScreenTest`
    - i18n：**36 个**新文案键**六语言同步**（片3 的 24 + 片4/5/6 的 12）
  - ⏳ **M2 范围内还没做的**：无。
    （原文列的「§6.5 的组件层只读表达：编辑器只放行只读语句」已**作废** ——
    分析模式的编辑器打的是**可写**的工作区，按原文拦成只读会让 M4 的验收
    「工作区里 `CREATE TABLE tmp AS SELECT …` 可以成功」永远通不过。见 §6.5 的更正。）
- **验收**
  - 在只读会话里发 `TABLE.CREATE` / `DATA.INSERT` / `IMPORT.RUN_IMPORT` / `SQL.EXECUTE("INSERT …")`
    —— **全部**返回明确的只读错误，且**不进入 handler**
  - 同一个 `INSERT` 在普通会话里照常成功（证明闸门只挡只读会话，没把功能打死）
  - 界面上「只读」徽章**没有关闭入口**
- **依赖**：M1（能力位与方言位要先存在）

### M3 —— 数据源接入（Hive / Impala）

- **交付**
  - **3.0 spike（先做，不产出功能代码）**：§4.2 的四条假设各给出一条**可复现**的结论
  - `dialect-hive` / `dialect-impala`（只读子集 + 各自的 `connectionForm`）
  - Hive / Impala 的 `SqlDialectProfile` **注册**进档位注册表（§6.3.1）——
    **前端零改动**：编辑器高亮、格式化按钮自动可用
  - `AnalysisSourceConfig` → **不需要**（复用 `ConnectionConfig`）
- **验收**
  - 对着**真实** HiveServer2：能列出库 → 表 → 列，列名与类型正确
  - 对着**真实** Impala：同上（若 spike 结论是「HS2 不通」，本里程碑改为只交付 Hive，
    并在文档里如实记录 Impala 的状态 —— **不假装做完**）
  - 引擎对该数据源发一条 `INSERT` → 被拒（方言级 + 路由级两道都验）
  - `jdbcUrlPrefix` 无歧义（§4.3 的问题已解决并有测试钉住）
  - **同一个 Hive 连接在浏览模式与分析模式里都能看到**（复用验证），
    且在**浏览模式**里也**不能写**（L3 方言级验证）
  - **加 Hive 档位时 `:shared` 的编辑器模块零改动**（§6.3.1 的验收现场 ——
    这条是「组件也通用化」的全部意义所在）
- **依赖**：M1、M2
- **风险**：R1（依赖冲突）、R2（HS2 兼容性）、R3（URL 前缀撞车）

### M4 —— 分析工作台

> **v0.6：本轮落地 M4'**（M4 减去依赖 M3 的部分）—— **拉取源用现有 MySQL / PostgreSQL**，
> 其余交付项与 M4 一致。M3（Hive / Impala）整体后置到目标环境确定之后，
> 那时只要加方言即可，架构不变。
>
> ✅ **v0.8：第 4 片已落地（DuckDB 本地工作区与状态机）** ——
> `AnalysisSession` + 工作区文件管理 + staging 表名规则。
> 工作区是 `~/.config/sundays/workspace.duckdb`（**复用 `dialect-duckdb`，零新方言**），
> 跨会话常驻；工作区**可写**、远端源**恒为只读**，
> 两条方向相反的只读性各有**唯一执行点**与断言钉住。
> 拉取链路 / `SourceTree` / 结果表与编辑器是第 5~6 片。
> 实现细节见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §5.2 / §6.1。
>
> ✅ **v0.9：第 5 片已落地（拉取链路与三段进度）** ——
> 拉取 = 远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`（**零新协议**）；
> `PullStageIndicator` 把三段（导出 / CSV / 导入）各自标出
> 「已完成 / 进行中 / 待执行」，并显示行数与临时文件占用。
> 临时文件在**成功 / 失败 / 取消**三条路径上都被清理；
> 取消时**先叫停导出子进程**再删目录（Windows 上文件被占用时删不掉）。
> 拉取的终态是一个 `sealed`（`Done` / `Cancelled` / `Failed`）——
> **取消不是失败**，界面上分开说。
> `SourceTree` 与结果表 / 编辑器是第 6 片。
> 实现细节见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §5.2 / §6.2~§6.5。
>
> ✅ **v1.0：第 6 片已落地（数据源树与只读结果表）** ——
> `SourceTree` 的形状是「**根（工作区 + 远端源）→ 命名空间 → 表**」，
> 展开是**懒加载**的（点开才去查，不是启动时把每个源的每个库都列一遍）；
> 工作区那一层**不摆命名空间**（DuckDB 只有一个 `main` schema，
> 摆出来只会在每张表前面多插一个没有信息量的节点）。
> 右侧主区接上了**编辑器（固定 DuckDB 方言，隐藏语言切换）**、
> **只读结果表**（`onCellEdit = null` —— 结果不是一张可以改的表）、
> **状态栏**与「▶ 运行」按钮。
> 点**远端表** → 拉取；点**工作区的表** → 把表名加进编辑器。
> M2 + M4' 的**六个分片至此全部落地**。
> 实现细节见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §5.1 / §6.6。

> ✅ **v1.1：第 7 片已落地（拉取改成 DuckDB 联邦直查）** ——
> 拉取不再走「远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`」，
> 改为**DuckDB 直接读远端的 JDBC 源**（联邦查询）：
> `CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>`。
> **没有临时文件、没有 CSV 的编码往返**，而且拉下来的表**保留远端的类型**
> （`INT` 落下来还是整数，`SUM(id)` 直接能跑，不必再 `TRY_CAST`）。
> 实现见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2~§6.5。
>
> ✅ **v1.2：第 8 片已落地（原生扫描器作为**可选加速**）** ——
> 拉取有**两条后端**：默认仍是纯 Java 表函数；
> 显式打开（`SUNDAYS_DUCKDB_NATIVE_EXT=1`）且扩展可用时改走
> DuckDB 的原生扫描器（`ATTACH` + `CREATE TABLE … AS SELECT`），
> 走不通就**降级**回纯 Java。
> **默认关**是因为两条路的**类型映射不同**（`SMALLINT` → 原生 `SMALLINT` /
> 我们 `INTEGER`）—— 「装没装扩展」不该隐式决定用户拿到的类型。
>
> 这一片还修掉了两个**实测抓出来的真 bug**：
> ① **同一张表拉第二次必失败**（表函数闭包握着已归还的连接 → 改成
> **无状态** + 每次拉取现给上下文）；
> ② **取消打不中**（DuckDB 的 `Statement.cancel()` 只认「本语句上正在跑的查询」，
> 拿一条新语句去 cancel 是**空操作** → 改成取消**正在跑的那条**）。
> 实现见 [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) §6.2.4 / §6.2.7 / §6.5。
>
- **交付**
  - DuckDB 工作区（**复用 `dialect-duckdb`，零新方言**）
  - 拉取链路：**DuckDB 联邦直查**远端 JDBC 源 → 工作区表（§3.5 的 `PULL_TABLE`；
    **零 CSV 中转、零临时文件**）—— 可选切到 DuckDB 的**原生扫描器**（默认关，
    走不通自动降级回纯 Java 表函数）
  - SQL 编辑（复用 `CodeEditor`）+ 结果表（复用 `DataTable`，**只读用法见 §6.3.3**）
  - `ObjectTree`（§6.4-1，**与浏览模式的对象树共用同一个组件**）+ `PullProgressIndicator`（§6.4-4，拉取进度）
  - 结果表右键菜单（复制 / 导出为 CSV）—— ⏳ **还没接**（§6.3.3 的用法表里的一行；
    剪贴板与文件对话框都是平台专属，要由 `desktopApp` 注入回调）
  - 拉取面板显示**预计占用**与临时文件清理时机
- **验收**
  - 拉一张 **10 万行**的表进 DuckDB（用 `sundays_smoke` 或本地造的等价数据），
    界面显示进度、可取消，落地后可查
  - 在工作区里跑一条**窗口函数**聚合（`OVER (PARTITION BY …)`），结果正确
  - 工作区里 `CREATE TABLE tmp AS SELECT …` **可以成功**（本地写允许，远端写禁止）
  - 拉取**全程不落盘**（联邦直查没有临时文件）—— 拉进来的表**保留远端的类型**
  - 结果表**没有**任何编辑入口（点单元格不进编辑态）—— §6.3.3 的 `onCellEdit = null`
- **依赖**：M3

### M5 —— 报表模型与 Excel 输出

- **交付**
  - `ReportSpec`（`:shared` commonMain，纯数据）+ `schemaVersion`
  - `engine/report/ReportBuilder` + `ReportCellStyler`（带缓存）
  - `REPORT.BUILD_EXCEL` 路由（`spec_json`）
  - 手工写一份 `spec_json` → 引擎产出 .xlsx
- **验收**
  - 产出文件用 POI **重新打开**能读到：标题行、元信息行、表头、**全部**数据行
  - 列的数字格式生效（`#,##0` → `1,284`；`¥#,##0.00`；`0.0%`）
  - 冻结首行 / 自动筛选 / 斑马纹都在
  - **10 万行**的报表生成不 OOM（`ReportCellStyler` 的缓存生效，style 数量不随行数增长）
  - `schemaVersion` 不认识时引擎**明确报错**（不是静默忽略字段）
- **依赖**：M2（路由）；M4（能拿到数据）

### M6 —— 报表设计器 UI

- **交付**：`ReportBlockList`（§6.4-2）/ `ReportColumnSpecRow`（§6.4-3）+ 区块配置
  （列规格、类型、数字格式、样式）/ Excel 预览 / 导出
- **验收**
  - 在设计器里把某一列的类型从 `DECIMAL` 改成 `PERCENT` → **预览立即变** → 导出的文件也一致
  - 增删区块 / 调区块顺序 → 导出文件的区块顺序一致
  - 预览与导出文件的**内容**逐格一致（预览不是另画一套，是同一份 spec 的渲染）
  - 数字格式下拉在 **10 种配色**下都是 Win 控件外观（§6.3.4）
- **依赖**：M5

### M7 —— AI 洞察（后续迭代，不在 v1 范围）

- **交付**：`InsightProvider` 实现 + `ANALYSIS.SUGGEST` 路由 + 设置页「数据分析」分类里的模型配置
- **验收**
  - AI 产出的 spec 经**同一套**校验与渲染链路（与手动出报表无差别）
  - AI 生成的 SQL 过只读闸门（构造一个含写操作的 prompt，断言被拒）
  - 模型不可用时手动出报表**不受影响**
- **依赖**：M5 / M6

### 横向任务（每个里程碑都要做）

| 项 | 说明 |
|---|---|
| **i18n 六语言** | 新文案键必须**六张表同步**（简 / 繁 / EN / RU / JA / DE），仓库有文案一致性测试盯着 |
| **文档同步** | `ARCHITECTURE.md` / `FEATURES.md` / `TEST_CASES.md` / 各模块 README —— 文档是契约 |
| **行尾与格式** | `ktlintFormat` → `fix-crlf.ps1` → **按字节校验**（`.sh` 保持 LF，其余 CRLF 无 BOM） |
| **打包** | 新依赖进来后**重算 jlink modules 清单**（见 §10.3 的坑） |
| **组件边界** | `AnalysisModuleBoundaryTest`（§6.6）每个里程碑都跑：新组件不许 import `desktopApp`、不许 import `java.*`、不许写字面量颜色 |

---

## 12. 风险登记

| # | 风险 | 等级 | 应对 |
|---|---|---|---|
| **R1** | `hive-jdbc` 依赖树与现有 `hadoop-common 3.5.0` / `parquet-hadoop 1.17.1` 版本冲突 | **高** | **M3.0 spike 必须先验**。备选：`hive-jdbc:standalone`（自带依赖、避免传递冲突）/ 降级 `hadoop-common` / 独立 ClassLoader 加载方言 / 走 `CUSTOM_URL` |
| **R2** | Impala 的 HS2 兼容性未验证 | **中** | 同上 spike。若不通，Impala 降级为「后置里程碑」，**如实记录**，不假装做完 |
| **R3** | `jdbcUrlPrefix` 撞车（两个方言同一 URL 形状） | **中** | ✅ **v0.4 已定解法**（§4.6 的 D2 + D4）：**不**造自定义前缀，改为**连接配置里的显式 `driver` 字段**消歧 —— M3 要改 `PoolManager.resolveDialect`，让「URL 前缀唯一」不再是它的前置假设 |
| **R4** | 拉取中转的磁盘占用是**两份** | **中** | 拉取面板显示预计占用；临时 CSV 三条路径都要清；提供「工作区清理」入口 |
| **R5** | POI 样式对象不缓存 → 大表 OOM | **中** | `ReportCellStyler` 按「列类型 × 样式」缓存，**验收里钉住**（M5） |
| **R6** | `spec_json` 无 proto 的兼容保证 | **中** | `schemaVersion` 必带，不认识的版本明确报错 |
| **R7** | 大数据量下 UI 卡顿 | **低** | 导出已在子进程跑（`ExportProcessManager`），拉取/报表沿用；结果表用 `DataTable` 的虚拟滚动 |
| **R8** | jlink 模块清单漏项 → 装得上启不来 | **低** | 新依赖进来后用 `jdeps` **重算**（`desktopApp/build.gradle.kts` 注释里有完整命令） |
| **R9** | **通用化迁移期新旧两套表单渲染分叉** | **中** | ✅ **Q6 已定：保留旧枚举分支**（§5.7）—— 单向迁移「优先读 spec、读不到才回退」；**旧分支只许修 bug、不许加功能**；旧方言不改；M1 验收里钉住「旧方言测试全绿且未修改」 |
| **R10** | **连接管理器复用后，用户可能期望在浏览模式里编辑 Hive 表** | **中** | 连接列表给只读方言加 ⓘ 徽标 + 明确的「此连接为只读方言」文案；浏览模式不渲染写入口 |
| **R11** | AI 接入时的模型服务与凭据管理 | **低**（M7） | v1 不做；`InsightContext` 不含原始数据，只给摘要；API Key 存法留到 M7 决定（§8.3） |
| **R12** | **组件复用退化** —— 实现时图省事，在 `desktopApp` 里另写一套外观相近的控件，导致「复用」名存实亡 | **中** | ① §6.6 的 `AnalysisModuleBoundaryTest` 把「不许 import `desktopApp` / 不许 import `java.*` / 不许写字面量颜色」钉成可跑的检查；② 新组件一律落在 `:shared` 的 `commonMain`；③ 评审时对着 §9.3 的映射表逐行核 |

---

## 13. 待定问题（**M0 结束前要逐条有结论**）

| # | 问题 | 为什么重要 | 建议默认 |
|---|---|---|---|
| ~~Q1~~ | ~~目标环境的 Hive / Impala **具体是什么发行版与版本**~~ | 决定驱动版本、SQL 方言、元数据查询写法 | ✅ **v0.4 已定「兼容策略」**（§4.6 的 D1~D4）：Hive 支持最近几个大版本、尽量向下兼容；Impala 从「兼容 hive jdbc 的版本」起支持；两者共用 `hive-jdbc` + `jdbc:hive2:`，靠 `driver` 消歧。⚠️ **具体发行版与版本号仍未定** —— 只影响 R1 的客户端版本取舍，不影响架构决策 |
| **Q2** | 一次拉取的**数据量级**？（万 / 十万 / 千万行） | 决定要不要上方案 B（应用层直搬）、要不要分片 | 按 **十万行**设计，二期再优化 |
| **Q3** | 要不要给 **MySQL / PostgreSQL** 也加 `ANALYSIS_SOURCE` 能力位？ | 加了它们就是「可写方言」进分析模式 → L4 闸门从「兜底」变成「主力」；不加则分析模式只有三个引擎 | **v0.6 更新：加。** 原结论是「不加」（用户当时只点了 DuckDB / Hive / Impala），理由是 **L4 闸门还不存在**。M2 把 L4 建起来之后，这个顾虑消失了 —— 可写方言进分析模式，由引擎侧的闸门兜住，**不靠前端自觉**。代价是 L4 从「兜底」升为「主力」，而这正是它该在的位置（§3.6 的两类回归测试是它的守门人）。<br>✅ **v0.7 已落地**：引擎侧 `dialect-mysql` / `dialect-postgresql` 真的声明了 `ANALYSIS_SOURCE`，分析屏据此筛数据源。**这个决定现在有测试守着** —— [`desktopApp` 的 `AnalysisCapabilityContractTest`](../../desktopApp/src/test/kotlin/com/langbiantianya/sundays/AnalysisCapabilityContractTest.kt) 断言「MySQL / PG 声明它、H2 / DuckDB / SQLite 不声明它、能力位以**枚举名**下发」；任何一侧改了声明而另一侧没跟上，测试就会红 |
| ~~Q4~~ | ~~报表**要不要图表**（柱状/折线）？~~ | POI 图表 API 不稳定，是 M6 的大变量 | ✅ **v0.4 已定：v1 不做**（用户拍板「报表先不要图」）—— 与 §1.2「明确不做的事」一致；M6 只出**表格型** Excel 报表 |
| **Q5** | AI 用哪家模型、云端还是本地？ | 属 M7，但**接口**要在 M5 之前定死 | 不影响 v1；先留 SPI |
| ~~Q6~~ | ~~通用化后，**要不要保留旧的枚举分支**作为回退？~~ | 决定 M1 的迁移策略与 R9 的处理 | ✅ **v0.4 已定：保留**（用户拍板「旧枚举保留」）—— 单向迁移：优先读 spec，读不到回退；细节见 §5.7 |
| **Q7** | 报表模板（样式预设）要不要内置几套？ | 影响 M6 的 `ReportStyle` 设计 | 先做 1 套默认 + 用户可调 |
| **Q8** | AI 的 API Key **怎么存**？（明文同连接配置 / 系统钥匙串 / 加密） | AI 凭据比数据库口令敏感 | **M7 决定**；v1 不做 AI，不要现在假装有方案 |
| **Q9** | 要不要**顺手上移** `desktopApp` 的 UI 组件到 `:shared`？（`BrowserToolBar` 零耦合可直接上移；`BrowserChrome` 抽出 `BackgroundTask` 后可上移） | 能让「平台无关的纯 UI 一律在 `:shared`」这条规矩更彻底 | **不夹带**（§6.3.5）—— 那是浏览模式的内部重构，与本设计无关，混进来会让本轮的大范围改动更难评审。将来单独开一轮做 |
| ~~Q6（旧）~~ | ~~分析数据源要不要复用浏览模式的连接？~~ | —— | ✅ **已定：复用**（v0.2 采纳评审意见） |

---

## 14. 附录

### 14.1 主题色值快照

[`theme-tokens.json`](./theme-tokens.json) —— 10 套 `ColorScheme` × 28 个 Material3 角色 +
9 条字号阶梯，由脚本从
[`shared/src/commonMain/.../ui/SundaysPalette.kt`](../../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/SundaysPalette.kt)
抽取。**真相源永远是那个 Kotlin 文件**；本快照会过期，改主题后要重新抽。

默认配色（蓝灰）速查：

| 角色 | 浅色 | 深色 |
|---|---|---|
| `primary` | `#2F5C9E` | `#5B8DEF` |
| `onPrimary` | `#FFFFFF` | `#0B1220` |
| `primaryContainer` | `#DCE7F7` | `#25355A` |
| `background` | `#F4F5F7` | `#1B1F27` |
| `surface` | `#FFFFFF` | `#232833` |
| `surfaceVariant` | `#ECEFF3` | `#2C3240` |
| `onSurface` | `#1E2430` | `#D6DAE3` |
| `onSurfaceVariant` | `#5B6474` | `#99A1B3` |
| `outlineVariant` | `#DDE2E9` | `#333A48` |
| `tertiary` | `#177A6A` | `#4FB6A5` |
| `error` | `#B3261E` | `#E5715F` |

### 14.2 本文档引用的仓库事实

| 事实 | 出处 |
|---|---|
| 顶层导航现有 4 个目标（`CONNECTIONS` / `DATABASE` / `ANALYSIS` / `SETTINGS`） | `shared/.../navigation/AppDestination.kt` |
| 5 套配色 × 明暗 2 档 | `shared/.../ui/ThemePalette.kt` |
| 10 套 `ColorScheme` + 9 条字号 | `shared/.../ui/SundaysPalette.kt` |
| 连接配置模型与 `DialectType`（5 种方言） | `shared/.../connection/ConnectionConfig.kt` |
| 连接持久化版本 `v2` | `shared/.../connection/ConnectionStorage.kt` |
| 设置页分类枚举 | `shared/.../settings/SettingsCategory.kt` |
| 引擎导出 5 种格式（含 `EXCEL`） | `engine/.../export/ExportModels.kt` |
| Excel 写出（POI SXSSF，100 万行/Sheet） | `engine/.../export/ExcelWriter.kt` |
| 导出跑在独立子进程 | `engine/.../export/ExportProcessManager.kt` / `ExportSubProcess.kt` |
| 导入支持 CSV / JSON_LINES | `engine/.../handlers/ImportHandler.kt`、`idb_engine.proto` 的 `IMPORT = 14` |
| 只读 SQL 扫描已有实现 | `shared/.../sql/DangerousSql.kt` |
| 现有只读拦截在**界面层** | `desktopApp/.../DatabaseBrowserState.kt:2980` |
| SPI 的写方法默认抛 `UnsupportedOperationException` | `api/.../DatabaseDialect.kt` |
| 方言靠 `jdbcUrlPrefix` 反查（URL 优先于 driver） | `api/.../DatabaseDialect.kt`、`engine/.../pool/PoolManager.kt` |
| `DialectCapability` 是「声明」不是「探测」 | `api/.../DialectCapability.kt` |
| POI 5.5.1 / DuckDB 1.5.5.1 / hadoop-common 3.5.0 已在依赖里 | `gradle/libs.versions.toml` |
| 现有 5 个方言模块 | `settings.gradle.kts` |
| 方言靠 `META-INF/services` 注册 | `dialect-duckdb/src/main/resources/META-INF/services/…` |
| jlink 模块清单是手工枚举的（12 个） | `desktopApp/build.gradle.kts`（注释里有 `jdeps` 重算命令） |
| 导入支持格式仅 CSV / JSON_LINES | `engine/.../importer/ImportFormat.kt` |
| **方言档位 `ALL` 是硬编码 list**（加 Hive/Impala 必改 `:shared` 核心文件） | `shared/.../editor/language/SqlDialectProfile.kt:341` |
| `SqlFormatter.register()` 遍历 `ALL` 注册「语言 + formatter」配对 | `shared/.../editor/formatter/SqlFormatter.kt:179-187` |
| `CodeLanguageRegistry` 已是**运行时可注册**的（KDoc 明说「不需要修改编辑器组件源码」） | `shared/.../editor/CodeLanguage.kt:94` |
| `DialectType` 在前端有**四处**硬编码 `when` | `shared/.../connection/ConnectionConfig.kt:72-80` `:117-125` `:140-155`、`shared/.../table/DialectCapabilities.kt:52-101` |
| `DialectType` → 方言档位的又一份 if-else | `desktopApp/.../DatabaseBrowserState.kt:149-157` |
| `TableColumn` / `TableRow` 是**通用键值模型**（不含「库表」语义） | `shared/.../table/TableModels.kt:52` `:88` |
| `DataTable` 的 `cellEditable` 默认跟随 `onCellEdit != null`（只读用法零改动） | `shared/.../table/DataTable.kt:187` 的 KDoc |
| `WinControls.kt` 有 16 个控件，**唯独没有下拉选择器** | `shared/.../ui/WinControls.kt` |
| `ColumnTypeCatalog` 是**数据库类型**目录（与报表的「呈现类型」不是一回事） | `shared/.../table/ColumnTypeCatalog.kt` |
| **`desktopApp` 的 8 个 UI 文件没有一个 import `java.*`** —— 已是平台无关代码 | `desktopApp/.../SchemaTree.kt` / `BrowserChrome.kt` / `BrowserToolBar.kt` / `TablePreview.kt` / `SqlWorkbench.kt` / `ExportDialogs.kt` / `GenerateWorkbench.kt` / `DatabaseBrowserScreen.kt` |
| `BrowserToolBar.kt` 对 `DatabaseBrowserState` **零引用** | `desktopApp/.../BrowserToolBar.kt` |
| `BrowserChrome.kt` 只耦合 `DatabaseBrowserState.BackgroundTask` | `desktopApp/.../BrowserChrome.kt:808` |
| `SchemaTree` / `TablePreview` / `ExportDialogs` / `GenerateWorkbench` 深度耦合 `DatabaseBrowserState` | `desktopApp/.../SchemaTree.kt:296` `:445`、`TablePreview.kt:156`、`ExportDialogs.kt:267`、`GenerateWorkbench.kt:157` `:303` |
| 「平台无关的纯 UI 上移到 `:shared`」是本项目**既定做法** | `desktopApp/.../main.kt:78-79` 的 KDoc（`TopNavBar` / `SundaysTheme` 已上移） |
| **M2 + M4' 的实现设计**（协议字段 / 模块落点 / 判据 / 提交切分） | [`M2-M4-IMPLEMENTATION.md`](./M2-M4-IMPLEMENTATION.md) |
