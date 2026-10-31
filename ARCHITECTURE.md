# sundays — Kotlin 数据库管理端架构导航（v2.40）

> **本文件只做整体介绍与模块导航。** 契约、边界、设计理由都在对应类/函数的 KDoc 里，
> 各模块文档也只写大纲 —— **改代码前先读源码注释，那是唯一权威处**。

---

## 1. 项目定位

`sundays` 是一个**全 Kotlin** 实现的桌面数据库管理工具：

- **后端引擎**：无头数据库算力引擎，既能作为 **gRPC 服务端**（默认 `:50051`）运行，
  也提供**进程内 facade**
- **调用层**：统一的 `EngineClient` 接口 —— 同一份调用代码可跑在同进程或跨进程 gRPC 上
- **前端**：**Kotlin Multiplatform + Compose Multiplatform Desktop** 桌面应用
  （Windows / Linux / macOS 三端共享 Skia 渲染）
- **整条工具链 Kotlin 一统**：共享 protobuf 生成器 + `kotlinx-coroutines`，无语言边界、无桥接开销

方言通过 `ServiceLoader` 动态加载，各方言模块之间**没有编译期依赖**，可以单独替换或新增。
界面支持 6 种语言，引擎的错误消息跟随界面语言。

---

## 2. 调用层架构

调用方只面向 **`EngineClient` 接口**（`com.langbiantianya.client.EngineClient`）编程，
**不感知引擎在同进程还是跨进程**：

| 模式 | 实现 | 模块 | 通道 | 适用场景 |
| --- | --- | --- | --- | --- |
| **Direct 直接模式（默认）** | `IdbEngine` | `engine/` | 同 JVM 直接方法调用 | Compose UI 同进程，零序列化、零子进程 |
| **gRPC 模式** | `GrpcEngineClient` | `engine-grpc-client/` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、子进程隔离、远程调试 |

两条实现共用 `:engine-protocol` 的同一份 `Request` / `Response` 消息与同一个
`RequestDispatcher` 路由集，因此 envelope options、流式分帧、错误包装**逐帧一致**。
`GrpcEngineClientTest` 用「同一 Request 走两条路径、断言 Response 列表相等」把这条平价钉住。

**模块依赖**（无环）：

```
desktopApp ──▶ shared            （UI 组件，与引擎无关）
desktopApp ──▶ engine-protocol ──api──▶ engine           （同进程实现 + gRPC 服务端）
                        │                │
                        │                └──▶ dialect-contract ──▶ i18n
                        │            （连接表单契约：方言声明 / 前端渲染）
                        └───────api──▶ engine-grpc-client  （跨进程实现）
```

**典型调用栈**：

```
Direct 模式（默认）:
[Compose UI] ── EngineClient.handle(req) ─→ [RequestDispatcher] ─→ [Handlers] ─→ [Dialects] ─→ [DB]

gRPC 模式:
[KMP Desktop] ── EngineClient.handle(req) ─→ [GrpcEngineClient] ── gRPC over IPC ─→ [IdbEngineImpl] ─→ …
[任意语言]   ── gRPC stub ─→ [IdbEngineImpl] ──▶ [RequestDispatcher] ─→ [Handlers] ─→ [DB]
```

**装配点只有一个**：[`main.kt`](./desktopApp/src/main/kotlin/com/langbiantianya/sundays/main.kt)
的 `createEngineClient()`，读 `-Dsundays.engine.endpoint`（`host:port` / `unix://path` /
`pipe:name`）决定实现。端点格式错误在装配时直接抛出，**不会静默退回本地**。

**写路径的一条总约束：连接的归属者决定它何时被释放。** 不带会话 id 时 handler 从池里借用
后归还；带会话 id（`Request.session_id`）时这条连接归 `TransactionManager` 所有，直到
`COMMIT` / `ROLLBACK`。搞错这点会让事务静默退化成「只有最后一条语句」。

契约细节、流式响应约定、envelope options 见
[`engine-protocol/README.md`](./engine-protocol/README.md)。

---

## 3. 国际化（i18n）

支持 **简体中文 / 繁體中文 / English / Русский / 日本語 / Deutsch**，文案表集中在零依赖的
`:i18n` 模块。

**为什么要单独建模块**：文案要同时喂给 `:shared`（UI 渲染）和 `:engine`（错误消息上屏），
而这两个模块互相看不见 —— `:i18n` 是它们唯一的交集，做成叶子模块后谁都能无环依赖。

两条值得记住的约束：

1. **键是枚举而非字符串**，拼错编译期即报；各语言的译文表拆成独立文件，互不干扰 diff，
   多个翻译任务可以并行各写各的。取值的 `when` 没有 `else`，加语言时编译器会报不完整。
2. **「这句话是不是报错」是状态，不是文案。** 严重级别必须走结构化字段，不许用
   `notice.startsWith("导出失败")` 这类解析文案的判断 —— 翻译之后这个判断恒为 false，
   错误会被静默染成成功色。

界面语言要跟着请求走到引擎进程（经 `RequestOptions.language` → `EngineLanguageContext` 上下文
元素），否则引擎抛的英文报错会在德语界面里上屏。取值 API、优先级链与跨进程传递的实现见
[`i18n/README.md`](./i18n/README.md) 与该模块的 KDoc。

---

## 4. 模块导航

| 模块 | 入口文档 | 内容主题 |
| --- | --- | --- |
| **`engine-protocol/`** | [`README.md`](./engine-protocol/README.md) | 调用层契约：`EngineClient` 接口 / proto 协议 |
| **`engine/`** | [`README.md`](./engine/README.md) · [`ARCHITECTURE.md`](./engine/ARCHITECTURE.md) | CLI / handler 路由矩阵 / 流式响应 / 连接池与事务 / 导入导出 / IPC Transport / 插件加载 / gRPC 服务端 |
| **`engine-grpc-client/`** | [`README.md`](./engine-grpc-client/README.md) | `GrpcEngineClient` 跨进程实现 / 端点配置（TCP / UDS / pipe）/ 端到端测试 |
| **`api/`** | [`README.md`](./api/README.md) | `DatabaseDialect` SPI / `ConnectionType` / `DialectCapability` / 扩展自定义方言流程 |
| **`i18n/`** | [`README.md`](./i18n/README.md) | 六语言文案：键枚举 / 译文表 / 简繁判定 / 复数规则 / 降级链 |
| **`dialect-contract/`** | [`README.md`](./dialect-contract/README.md) | 连接表单契约：`ConnectionFormSpec` / `ConnectionField` / `AuthMethod` —— 方言自描述「连得上要填什么」，`:api` 声明、`:shared` 渲染 |
| **`dialect-mysql/`** | [`README.md`](./dialect-mysql/README.md) | MySQL 方言特性 / SQL 危险关键词 / 已知约束 |
| **`dialect-postgresql/`** | [`README.md`](./dialect-postgresql/README.md) | PostgreSQL 方言特性 / search_path / DDL 事务 / 已知约束 |
| **`dialect-h2/`** | [`README.md`](./dialect-h2/README.md) | H2 嵌入式 / 集成测试 fixture / 已知约束 |
| **`dialect-duckdb/`** | [`README.md`](./dialect-duckdb/README.md) | DuckDB 嵌入式 OLAP / Excel 预转换 / FK table-rebuild / 已知约束 |
| **`dialect-sqlite/`** | [`README.md`](./dialect-sqlite/README.md) | SQLite 嵌入式 / INTEGER PRIMARY KEY / FK table-rebuild / ATTACH/DETACH / 已知约束 |
| **`shared/`** | [`README.md`](./shared/README.md) · [`ARCHITECTURE.md`](./shared/ARCHITECTURE.md) | CodeEditor / DataTable / ConnectionManagerScreen / **方言元数据表** / 表结构编辑弹窗 / SQL 文本工具 / **浏览器 chrome 与通知中心** / 右键菜单 / 主题与视觉规范 / 与引擎解耦的边界 |
| **`desktopApp/`** | [`README.md`](./desktopApp/README.md) · [`ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) | Direct 模式集成 / 顶层导航 / 连接管理 / 数据库浏览屏 / 生命周期 |
| **`packaging/flatpak/`** | [`README.md`](./packaging/flatpak/README.md) | Flatpak 选型结论索引 |

> **在建：数据分析模式** —— 一个**独立于「连接管理 → 数据库浏览」**的新顶层模式：
> 远程数据源**恒为只读**（六层防线，不是可关的开关），计算引擎默认 **DuckDB**（可切
> Hive / Impala），当前阶段做 **Excel 报表**，后续迭代接入 AI 做智能分析。
> **连接配置 / 连接管理器 / 设置页与浏览模式共用同一份**（不另做一套），
> **通用控件也全部复用 `:shared` 的 `commonMain`**（分析模式只新增 5 个组件）。
> 进度：M2（只读地基：协议 + 引擎闸门 + 四条只读路由 + 顶层导航）**已落地**，
> M4'（分析工作台：本地 DuckDB 工作区 + 拉取链路 + 结果表）**八个分片全部落地** ——
> 工作区与状态机（第 4 片）、**拉取链路与三段进度**（第 5 片）、
> **数据源树与只读结果表 / 编辑器**（第 6 片；那棵树后来与浏览模式合成一棵，见下）、
> **拉取改走 DuckDB 联邦直查**（第 7 片，**去掉 CSV 中转**）、
> **原生扫描器作为可选加速**（第 8 片，默认关；走不通降级回纯 Java 表函数）；
> 另有两处界面收敛：**去掉分析工作台的「数据源」下拉框**（源只在左树选）、
> **顶栏去掉「连接管理 / 数据分析」chip**（分析模式的入口挪到连接管理的空闲面板）；
> 以及**启动首屏** —— 进应用先选一个模式（连接管理 / 数据分析）进去；
> 分析工作台的**树只显示 DuckDB 工作区**（保存的连接配置不进那棵树）；
> 分析工作台**没有独立工具条**了 —— 「▶ 运行」挪到编辑器的「格式化」旁边，
> 「⊘ 只读」徽标也去掉了（远端恒为只读的保证在引擎与方言层）；
> **两棵树合成一棵** —— 分析页的树与浏览模式的对象树**共用同一个组件**
> （`:shared` 的 `tree/ObjectTree.kt`），形状统一为「库 → 表 → 字段」，
> 分析页因此也能**展开到字段**；
> **两棵树的面板外壳也共用同一个组件** —— `:shared` 的 `ui/SidePaneShell.kt`：
> 展开态是「面板 + 可拖宽的分隔条」，折叠态收成贴到屏幕左边的 **44dp 窄轨**
> （一个展开按钮 + 一枚图标），把宽度**让给右边**；
> 浏览模式此前能拖宽、分析模式是写死的 240dp，现在两侧手感一致；
> 折叠时树整体从组合里移除，因此**滚动状态被提到面板之外**
> （浏览模式在会话里、分析模式在屏幕层）—— 展开后树还停在原来那个位置；
> **两个模式的 SQL 编辑器高度也能拖了** —— 编辑器与结果区之间那条缝是
> `:shared` 的**横向分隔条**（`HorizontalDragHandle`，与竖向的 `DragHandle`
> 是同一份实现，手势 / 画线 / 指针只有一份）：往下拖编辑器变高，
> 双击复位回默认比例。**结果区的下限同时是拖拽的硬边界**
> （浏览模式 180dp、分析模式 140dp）—— 数据看不见就等于白查；
> 高度是**窗口级偏好**（浏览模式提到屏级，分析模式在屏幕层）——
> 工作台被拆掉又重建后，拖好的高度还在；
> **分析模式能自己加数据源** —— 树头上一个「+」（分析模式专属的对话框，
> **不共用**浏览模式的「添加连接」）：远程库先用**对应驱动预览**、可多选可单选，
> 再用 **DuckDB 官方扩展优先**接入（走不通退回 JDBC）；文件 / 文件夹
> （parquet / csv / Excel / jsonl）直接用 DuckDB 自己的读法建**视图**。
> 加完的源**直接存在工作区 DuckDB 里**（分析模式自己的清单），
> 树随之变成「工作区的各个 schema」。
> 设计文档（只读防线、连接与组件通用化、报表模型、迭代拆分、风险登记）与可交互 UI 设计图见
> [`docs/analysis-mode/README.md`](./docs/analysis-mode/README.md)。
>
> **M1 第一片已落地**（通用化的「机制」部分）：前端方言元数据收进**一张表**
> （`shared/.../connection/DialectMetadata.kt`，原先散落成**八处**硬编码 `when`）、
> SQL 方言档位**注册表化**（语言与 formatter 成对注册）、`WinControls` 补 `WinDropdown`、
> 连接配置持久化升到 v3（新增 `authMethod` / `properties`）与 `ConnectionType.CUSTOM_URL`。
> **方言本体（Hive / Impala）留到 M3 与引擎方言同一个提交落地** ——
> `DialectNameContractTest` 是那道闸门：前端枚举里的每个方言，引擎都得真的实现了。

> **报表真正能用了（v2.40）** —— v2.39 把报表的**形状**做对了，但「形状对」不等于「用得起来」。
> 这一轮补的是四个缺口：**存得住 / 给得出去 / 搭得省力 / 看得快**。
>
> **存得住 —— 报表落盘** —— `~/.config/sundays/reports.json`（**独立**文件，与 `connection.json` 同构）。
> 不进工作区 DuckDB：工作区是**用户自己的**数据，把应用的报表塞进去，用户在自己的工作区里
> 就能查到一张**他没建过的表**；而「应用的文件都在哪」必须**只有一个答案**。
> 只存**形状**（id / 名字 / 部件的类型 / 标题 / SQL），**不存查出来的数** ——
> 那是派生数据，一落盘就开始过时，而**过时的数字比没有数字更危险**。
> 写盘走「临时文件 + 原子替换」；**读不出来时绝不静默清空** —— 原文件改名保留，
> 应用按空集继续并明确告知（把「文件坏了」变成「数据没了」是不可逆的）。
>
> **给得出去 —— 导出 `.xlsx`** —— 走 **desktopApp 侧直接 POI**，不走引擎：
> 数据已经在前端的 `widgetResults` 里，而走引擎意味着**重跑一遍 SQL**，
> 发出去的文件可能与屏幕上看到的不一样；而且 POI 早已随 `dialect-duckdb` 进了打包运行时，
> **不新增任何运行时依赖**，jlink 的模块清单一个都不用加。
> 导出内容：第一个 Sheet 是「报表」（报表名 + 导出时间 + **每个部件的 SQL** ——
> 收件人一眼能看出每个数字是怎么来的），每个部件一个 Sheet；
> 表格部件原样铺开（**用户的数据不当成数字改写**），图表部件输出「类别 | 数值 | 占比%」。
> 导出**先刷新再写** —— 导出的要是**导出那一刻**的真实数据。
>
> **搭得省力 —— 复制 / 上移 / 下移** —— 部件卡片头现在有六个动作按钮。
> 复制出来的部件**插在原件后面**，并把**数据**一起带过去（SQL 一样，数据就一样）。
> 排序用**按钮**而不是拖拽：拖拽要给「部件 = 一条 SQL」之外再加一层几何信息，
> 而那对「一张报表」不是必需品。能不能挪是**规则**（纯函数 `canMoveReportWidget`），
> 界面拿它灰按钮、会话拿它执行 —— 两处各写一遍迟早出现「界面说不行、会话说行」的裂缝。
>
> **看得快 —— 单个部件刷新** —— 每个部件一个刷新按钮，只重跑它那一条 SQL。
> 它与「刷新整张报表」「导出」**共用一把锁**（工作区是**一个** DuckDB 文件，
> 并发查询会互相抢锁，后完成的会把先完成的盖掉）。
> 运行标志**同步**置位 —— 放在协程里置位的话，连点两下会两次都通过检查。
>
> **顺带修掉一个「应用配置目录」的多份实现** —— `connection.json` / `settings.json` /
> `workspace.duckdb` / `reports.json` 各自写了一遍 `~/.config/sundays`。
> 现在收进 `shared/.../config/AppConfigDir.kt`，**一个地方答，四处用**。

> **报表设计页面重排了布局，两侧都能折叠收纳（v2.39）** —— 报表设计视图从
> 「报表列表 ｜ 数据树 ｜ 画布」改成「**数据树（左）｜ 画布 ｜ 报表列表（窗口最右）**」。
>
> **为什么这样摆** —— 三块各司其职，位置由「它是**哪一类**东西」决定：
> **数据树是输入**（有哪些表、哪些字段）→ 放左，与浏览模式、分析工作台**同一个方向**；
> **画布是主产物**（部件一块块往下排）→ 放中间，占最大那块地方；
> **报表列表是另一类产物**（一张张报表）→ 贴**窗口最右**，
> 「它不参与画布的编辑」一眼看得出来。
>
> **两侧都能折叠** —— 树收成**窗口最左**的一条 44dp 窄轨，报表列表收成**窗口最右**的一条；
> 折叠的意义是**把宽度让给画布**（画布才是这一页的主角）。
> 手感与浏览模式 / 分析工作台**完全一致** —— 同一个 `SidePaneShell`。
>
> **左右差异只由一个枚举驱动** —— 贴在左边与贴在右边是**镜像**的
> （分隔条位置、窄轨竖线位置、箭头朝向、拖动方向感）；
> 散成几个布尔参数的话，迟早漏掉一处箭头没镜像，而那种缺陷界面上**极难看出来**。
> 所以 `SidePaneShell` 的 `PaneEdge` 决定外壳怎么摆，`DragHandle` 的 `HandleSide`
> 决定往哪个方向拖会变宽 —— 后者**必须**在分隔条**内部**处理：
> 调用方若在回调里反算（`旧值 + (旧值 - 新值)`），**钳位一旦发生就会算错**。
>
> **组件改名** —— `TreePaneShell` → `SidePaneShell`（并从 `tree/` 挪到 `ui/`）：
> 它服务的是「**可折叠侧边面板**」这个概念，不再只是树。
> 名不副实的名字会让下一个人以为「树的东西不能乱用」。
>
> **报表设计页面有了数据树（v2.38）** —— 报表设计视图里多了一棵**数据树**
> （工作区 → 表 → 字段），部件编辑页里也有一棵 —— 与分析工作台**同一棵**。
>
> **为什么必须有树** —— 部件的形状约定是「第一列类别、第二列数值」，
> 而写出一条符合这个约定的 SQL，前提是知道**有哪些表、哪些字段**。
> 没有树的话，用户只能切回工作台把名字**记下来**，再切回来凭记忆敲 ——
> 敲错了要等**跑 SQL** 时才报错。
>
> **两处树，两种后果** —— 画布上点表是「**开一个新部件**」（画布上还没有正在写的
> SQL，引用插给谁？），编辑页里点表是「**把引用插进正在写的那条 SQL**」。
> 同一个组件、同一份数据、同一份展开态 —— 只是**上下文**不同。
>
> **引用拼接的规则只有一份** —— `shared/.../analysis/AnalysisSqlRef.kt`
> （纯函数）：`"schema"."表"` 与 `"schema"."表"."字段"`，
> `main` 里的表**不带** schema 前缀。分析工作台与报表设计都调它 ——
> 两份实现的话，改一处忘了另一处必然发生，而**两边各自的测试都是绿的**。
>
> **还没有报表时点树会先建一张** —— 部件总得有个家；
> 而「提示用户先建报表」等于让那一下点击**白点**。
>
> **报表设计页面（v2.37）** —— 分析模式里多了一个**报表设计**视图：
> 一张报表由若干**部件**组成，每个部件就是「**一条 SQL + 一种图表类型**」
> （饼图 / 条形图 / 表格）。
>
> **为什么部件就是一条 SQL** —— 报表里的每一块背后都是**一次查询**。
> 把查询单独抽出来当「部件」，报表本身就成了「部件的清单」：
> 部件能**单独预览**（加部件时当场看见这条 SQL 跑出来的**样例数据**），
> 也能单独复用与重排。
>
> **数据形状的约定写死在代码里** —— 第一列 = 类别，第二列 = 数值。
> 说不清形状就直接说清「缺列 / 值不是数字 / 有负值」，
> **绝不做静默截断**（少一根柱子，用户不会知道少了什么）。
> 负值一律不画：饼图算不出角度，条形图画成正值长度是**误导**。
>
> **图表不用第三方库** —— `:shared` 的 `commonMain` 不能引 Android-only 依赖，
> 而手绘 `drawArc` + `drawRoundRect` 不到一百行；图例用普通 `Text`
> （Canvas 里画不了文字）。
>
> **报表暂不落盘** —— 报表住在会话里，**退出即失**。
> 持久化要动工作区 DuckDB 的表结构，风险大，这一轮先把**形状**做对；
> 但「部件 = 一条 SQL」这个形状**从此不许改** —— 用户搭好的报表会集体作废。
> 落点见 [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) §3.9。
>
> **造数工作台的统计结果收成底部一行（v2.36）** —— v2.35 把日志与结果合成一个面板，
> 但那块「统计结果」（每个脚本一行：脚本 / 目标表 / 插入行数）实际只有两三行内容，
> 却占着 150dp 的高度 —— 而那块地方本该给日志（1000 行历史）。
>
> **为两三行内容留一整块地，是把最稀缺的地方浪费掉** —— 现在收成底部**一行**文本：
> 造数中报「已插入 N 行」，跑完报「共 N 行 · 处理 M 个脚本」，出错报原因
> （一行放不下，省略号截断；完整原因在**日志里**，失败也会留一行，带引擎原文）。
>
> **分脚本的明细并没有丢** —— 日志里**逐行写着**目标表名与该脚本内的行号，
> 真要按表汇总，看日志比看一张两三行的表更快。
>
> 顺带清掉了随之失去用途的东西：结果表的分页 / 选中行状态、
> `generateResultRows()`、四个孤儿 i18n 键（`NO_GENERATE_EXECUTED` /
> `NO_GENERATE_EXECUTED_HINT` / `TARGET_TABLE` / `INSERTED_ROWS`），
> 另加一个带原因的新键 `GENERATE_FAILED_REASON`。
>> **造数工作台的编辑器能拖高度，日志与结果合成一块（v2.35）** —— 造数工作台此前
> 是**写死的比例**（编辑器 45% / 日志 25% / 结果 30%），而「写一段几百行的 Lua 循环」
> 与「只想看一眼刚造的那批数据」这两种诉求打架，用户没有任何办法调。
> 现在编辑器与输出面板之间那条缝能拖（双击复位），与浏览 / 分析两侧**共用同一个
> 横向分隔条**（`:shared` 的 `HorizontalDragHandle`）。
>
> **下半部分从两个面板合成一个** —— 日志与结果表本是同一件事的两面（过程 / 结论），
> 此前却是两个各自带标题栏的独立面板：两行标题栏、两条分隔线，而只有两三行的
> 结论表分到了 30% 的高度，真正需要地方的日志只拿到 25%。合成一块之后：标题栏
> 只有一行，日志吃掉全部剩余空间，结论收在底部**一行**文本里（见 v2.36）。
>
> **输出面板有下限**（260dp）—— 日志看不见就等于没有日志；高度是**窗口级偏好**，
> 工作台被拆掉又重建后还在。落点见
> [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) §3.3。
>> **分析模式的树有了右键菜单（v2.34）** —— 分析模式此前只能「**加**」数据源：
> 加错了、或者那个源已经不需要了，用户只能自己去 DuckDB 里手动 `DROP SCHEMA`
> —— 而他多半不知道数据源在工作区里占着一个 schema。
> 现在**右键数据源的那张表**就弹出菜单，菜单里是「删除数据源」。
>
> **菜单机制与浏览模式共用同一份** —— `:shared` 的 `tree/TreeContextMenu.kt`
> （状态 / 弹层 / 菜单项）。此前那套机制只在 `desktopApp` 里（浏览模式专用），
> 于是分析模式要么复制一份（同一个概念两份实现，改一处忘另一处）、
> 要么就只能没有右键菜单。现在两棵树共用一份：定位、边界钳位、关闭、
> 以及「点了菜单项**先关菜单**再干活」那条规矩只有一处实现。
>
> **判据是「登记表里有没有它」** —— 不是「树上有没有这一行 schema」：
> 用户在 DuckDB 里手工建的 schema 不是数据源，工作区自己的 `main` 更不是
> （那是分析模式**自带的计算引擎**，删它等于删掉整个工作区）。
> 判据落在 `:shared` 的纯函数 `analysisDeleteSourceTarget` 上，
> 界面按它决定「要不要弹菜单」，测试按它断言「这一下不该有删除项」。
>
> **删除要动两处** —— 工作区里那个 schema（`DROP SCHEMA … CASCADE`）
> 与登记表里那一行（`DELETE`）。只删一处的话，要么重启后它又「回来」
> 而表已经没了，要么树上多一根没有来由的 schema。顺序是「先 DROP 再 DELETE」：
> 反过来的话 `DROP` 失败会留下一个「有 schema、没登记」的孤儿 ——
> 用户看得见它，却**没法从菜单里删掉**。
> 落点见 [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) §3.3。
>
> **造数工作台的实时日志（v2.33）** —— 造数此前只有一个「共插入 N 行」的统计表，
> 出了错**看不出是哪一行**：一段 Lua 循环几千行 INSERT，中间哪一行列名写错了、
> 类型不对，造完只剩一句总数，排查无从下手。现在编辑器与结果表**之间**多了一条
> 实时日志，**每条 INSERT 一行**：目标表 · 该脚本内的行号 · 这一行的实际数据。
>
> **数据源是引擎已经在发的东西** —— `DATA.GENERATE` 的流里每条 INSERT 回一帧
> `gen_progress_frame`（表名 / 行号 / 这一行的数据），**引擎一行代码都不用改**；
> 前端此前只拿它更新计数器，那些信息其实早就够拼出一份日志了。
> 协议里**没有**独立的日志消息，Lua 沙箱里也**没有** `log()` 辅助函数 ——
> 这份日志是前端拿现成的进度帧拼出来的。
>
> 三条边界：日志**有界**（1000 行）且丢掉的行数**明写在标题栏** ——
> 静默截断等于伪造一份完整的日志；**失败也进日志**（脚本为空 / 引擎报错 /
> 异常兜底三条路径都留痕）；行号**可能跳号**（引擎的帧通道满了会丢最旧的帧）——
> 抹平成连续行号就是伪造日志。落点见
> [`desktopApp/ARCHITECTURE.md`](./desktopApp/ARCHITECTURE.md) §3.3。

---

## 5. 方言矩阵

| 能力 / 方言 | MySQL | PostgreSQL | H2 | DuckDB | SQLite |
| --- | --- | --- | --- | --- | --- |
| **connectionType** | CLIENT_SERVER | CLIENT_SERVER | IN_MEMORY | EMBEDDED | FILE_BASED |
| **defaultPort** | 3306 | 5432 | — | — | — |
| **USERS** | ✓ | ✓ | ✓ | — | — |
| **PRIVILEGES** | ✓ | ✓ | ✓ | — | — |
| **ROUTINES** | ✓ | ✓ | ✓ | ✓ (MACRO) | — |
| **VIEWS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **INDEXES** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **FOREIGN_KEYS** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **TRIGGERS** | ✓ | ✓ | ✓ | — | — |
| **MULTI_SCHEMA** | — | ✓ | ✓ | ✓ | — |
| **CROSS_DATABASE** | — | ✓ | ✓ | ✓ (ATTACH) | — (ATTACH 不暴露) |
| **EXPORT** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **DDL_TRANSACTION** | ✓ (软) | ✓ | ✓ (软) | — | — |
| **EMBEDDED_MODE** | — | — | ✓ | ✓ | ✓ |

`DialectCapability` 枚举是这份矩阵的单一事实来源 —— 不支持的能力由方言抛
`UnsupportedOperationException`，而不是静默返回空结果。各方言的详细特性与已知约束见
对应 `README.md`。

> ⚠️ **`DDL_TRANSACTION` 标了 ✓ 不等于「DDL 能回滚」。** MySQL 与 H2 都对 DDL
> **隐式提交**（矩阵里标「软」），批量改表结构中途失败时，已生效的部分留在库里。
> H2 这条是实测的：`autoCommit = false` 的连接上 `CREATE TABLE` 一执行，
> 另借连接就查得到，说明它根本没进事务 —— 回归见
> `DdlTransactionIntegrationTest.H2 does not roll DDL back`。
> 五个内置方言里真正支持事务性 DDL 的只有 **PostgreSQL**，界面上不能对别的方言
> 承诺「全成功或全不做」。

---

## 6. 快速运行

```bash
./gradlew :desktopApp:run                    # Direct 模式（构造即 bootstrap 引擎 + 所有方言）
./gradlew :desktopApp:hotRun --auto          # Hot reload

./gradlew :engine:jar                        # 引擎 standalone
java -jar engine/build/libs/idb-engine.jar --mode grpc --ipc tcp --port 50051
java -jar engine/build/libs/idb-engine.jar --mode direct

./gradlew :desktopApp:run -Dsundays.engine.endpoint=localhost:50051   # 前端改走 gRPC
```

CLI 参数、IPC transport 切换、前端屏幕说明见
[`engine/README.md`](./engine/README.md) 与 [`desktopApp/README.md`](./desktopApp/README.md)。
测试怎么跑见 [`README.md`](./README.md#测试)，打包见 [`README.md`](./README.md#打包)。

---

## 7. 技术栈

- **Kotlin 2.4.20 / JDK 25**
- **KMP + Compose Multiplatform Desktop**（仅 `jvmMain` 单平台目标）
- **gRPC 1.83.1** + grpc-kotlin 1.5.0（Kotlin 协程服务端 + Kotlin DSL 生成）
- **protobuf-kotlin-lite 4.35.1**（DSL builder）
- **kotlinx-coroutines 1.11.0**
- **HikariCP 7.0.2**（SHA-256 缓存连接池）
- **数据库驱动**：MySQL 9.7.0 / PostgreSQL 42.7.11 / H2 2.3.232 / DuckDB 1.5.5.1 / SQLite 3.46.1.3
- **SLF4J 2.0.18 + Logback 1.5.13**
- **LuaJIT 4.1.0 + Lua 5.1~5.5**（造数引擎）
- **Apache POI 5.5.1**（Excel 流式导出 + DuckDB Excel 预转换）
- **Apache Parquet 1.17.1 + Hadoop 3.5.0**（Parquet 导出）

架构升级历史见 [`README.md`](./README.md#架构升级历史)。
