# api — DatabaseDialect SPI

**零外部依赖的公共 SPI 模块** —— 定义方言插件必须实现的接口 + 连接元数据扩展枚举。引擎通过 `ServiceLoader<DatabaseDialect>` 自动发现并注册方言。

> **当前版本**：v2.12。v2.8 起 11 个连接元数据属性纳入 SPI（前端据此动态渲染连接表单）；v2.11 起 `jdbcUrlPrefix` 让引擎仅凭 JDBC URL 反查方言；v2.12 起 `connectionForm` 让方言自描述**完整的表单规格**（不只是布尔）。

## 模块结构

| 文件 | 内容 |
|---|---|
| [`DatabaseDialect.kt`](./src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) | 核心 SPI 接口 —— **逐方法 / 逐属性的语义与契约以该文件 KDoc 为权威** |
| [`ConnectionType.kt`](./src/main/kotlin/com/langbiantianya/dialect/ConnectionType.kt) | `CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY` |
| [`DialectCapability.kt`](./src/main/kotlin/com/langbiantianya/dialect/DialectCapability.kt) | 15 个能力标签（`USERS` / `VIEWS` / `ROUTINES` / `BROWSE_SOURCE` …） |
| [`DialectUtil.kt`](./src/main/kotlin/com/langbiantianya/dialect/DialectUtil.kt) | 共享工具方法（引用 / 转义等） |

**依赖关系**：`api/` ← `dialect-*`（实现）；`api/` ← `engine/`（由 `DialectLoader` 消费）。`api/` 自身**没有任何运行时依赖**（仅 JDK 标准库）。

## `DatabaseDialect` 接口

方言插件实现该接口的全部方法。新增的连接元数据属性都带默认实现，**向后兼容** —— 旧方言无需任何修改。方法清单：

| 类别 | 方法 / 属性 |
|---|---|
| **驱动识别** | `driverName` / `jdbcDriverClassName` / `buildJdbcUrl(...)` / `jdbcUrlPrefix` |
| **连接元数据（v2.8）** | `displayName` / `connectionType` / `requiresHost` / `requiresPort` / `defaultPort` / `supportsUser` / `supportsPassword` / `supportsSchema` / `supportsCrossDatabase` / `jdbcUrlExample` / `capabilities` |
| **连接表单规格（v2.12）** | `connectionForm` —— 方言自描述「连得上要填什么」（字段、顺序、标签、认证方式）；默认返回**空 spec** = 「没声明」，前端据此回退旧的枚举分支。类型住在 [`:dialect-contract`](../dialect-contract/README.md) 模块 |
| **流式查询配置** | `configureConnectionForStreaming` / `restoreConnectionAfterStreaming` |
| **Schema 上下文 / 操作** | `setSearchPath` / `buildSetSearchPathSql`；`listDatabases` / `listSchemas` / `createSchema` / `deleteSchema` |
| **Table / Column** | `listTables` / `listColumns` / `getCreateTableDDL` / `buildColumnDefinition` / `buildAddColumnSQL` / `buildDropColumnSQL` / `buildModifyColumnSQL` |
| **Table 操作 / 选项** | `renameTable` / `truncateTable`；`buildTableOptionsSQL` / `buildPreCreateStatements` / `buildPostCreateStatements` |
| **SQL 校验** | `validateSqlFragment` / `validateOrderBy` |
| **用户权限** | `listUsers` / `createUser` / `deleteUser` / `updatePassword` / `updatePrivileges` / `listPrivileges` / `listAllGrants` |
| **函数 / 存储过程** | `listRoutines` / `getRoutineDDL` / `getRoutineInfo` / `createRoutine` / `dropRoutine` / `callRoutine` / `debugRoutine` / `validateRoutineDDL` |
| **视图 / 索引 / 外键 / 触发器** | `listViews` `createView` `dropView` `getViewDDL`；`listIndexes` `createIndex` `dropIndex`；`listForeignKeys` `addForeignKey` `dropForeignKey`；`listTriggers` `getTriggerDDL` —— 这四组**默认抛 `UnsupportedOperationException`** |
| **SQL / Server** | `explainSQL` / `testConnection` / `getServerInfo` |

每个方法的语义、默认值、必须覆盖的情形写在 [`DatabaseDialect.kt`](./src/main/kotlin/com/langbiantianya/dialect/DatabaseDialect.kt) 的 KDoc 里，改接口时以那里为准。

连接元数据属性的唯一用途：`SYSTEM.LIST_DRIVERS` 枚举已加载方言并返回 `repeated DialectInfo`，前端据此**动态渲染「新建连接」表单** —— 无需硬编码每个 driver 的字段需求。

**`connectionForm`（v2.12）** 把这件事推进了一步：不再只给「需要主机 / 需要端口 / 支持账密」这类**布尔**，而是给出**完整的表单规格**（哪些字段、什么顺序、什么标签、哪些认证方式）。前端据此渲染，**新增一个方言的表单形状不需要改前端一行**。它随 `DialectInfo` 的 `connection_form` 字段下发（`SYSTEM.LIST_DRIVERS` 一次性返回，不是每次建连接都跑一趟）。

## `jdbcUrlPrefix` —— 凭 JDBC URL 反查方言（v2.11）

`DialectLoader.getDialectByJdbcUrl("jdbc:mysql://…")` 仅凭 JDBC URL 就能定位方言实例。三条关键结论：

- 5 个内置方言**全部显式覆盖**该属性，不依赖 `driverName` 命名巧合（`jdbc:mysql:` / `jdbc:postgresql:` / `jdbc:h2:` / `jdbc:duckdb:` / `jdbc:sqlite:`）。
- 多个方言同时匹配时取**最长前缀**，为将来更具体的嵌套前缀留余地。
- `PoolManager.resolveDialect(config)` 在 `connection.jdbc_url` 非空时按 URL 反查，**忽略** `driver` / `host` / `port` / `database`；反查失败抛可读错误而**不回退** `driver` —— 静默错配比连不上危险。

新方言的 `driverName` 与 URL scheme 不一致时（很常见）**必须**覆盖它。契约细节见 `DatabaseDialect.kt` 的 `jdbcUrlPrefix` KDoc，链路见 [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) §4.1（`jdbc_url` 字段）。

## 两个枚举

[`ConnectionType.kt`](./src/main/kotlin/com/langbiantianya/dialect/ConnectionType.kt) —— 四种连接模式的区别只在「`database` 字段被拿来装什么」：

| 值 | `database` 装的是 | 典型方言 | 表单字段 |
|---|---|---|---|
| `CLIENT_SERVER` | 库名（另需 host + port + user + password） | MySQL / PostgreSQL | host, port, user, password, database |
| `EMBEDDED` | 路径或特殊值（`.csv` / `.parquet` / `.xlsx` 等） | DuckDB | database |
| `FILE_BASED` | `.db` 文件路径 | SQLite | database |
| `IN_MEMORY` | 可空的内存库标识 | H2 / DuckDB `:memory:` | database（可选） |

[`DialectCapability.kt`](./src/main/kotlin/com/langbiantianya/dialect/DialectCapability.kt) —— 15 个能力标签：`USERS` / `PRIVILEGES` / `ROUTINES` / `VIEWS` / `INDEXES` / `FOREIGN_KEYS` / `TRIGGERS` / `CROSS_DATABASE` / `MULTI_SCHEMA` / `EXPORT` / `DDL_TRANSACTION` / `EMBEDDED_MODE` / `BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT`。每个标签的含义与内置方言分布、前端按钮显隐的对应关系，见该文件 KDoc。

> **能力标签是「声明」不是「探测」**：标签要么对应 SPI 里真的实现了的那组方法，要么对应默认抛 `UnsupportedOperationException` 的那批，**没有第三种**。所以加标签之前先确认底层方法可用 —— 前端据它隐藏的按钮，永远不该点到一个必然失败的调用上。

### 能力矩阵（方言 × 能力）

`✓` = 该方言**声明**了这个能力位，`—` = 没声明。这份表与 [`ARCHITECTURE.md` §5 方言矩阵](../ARCHITECTURE.md) 是**同一份数据**（那边是转置的「能力 × 方言」排版）；单个方言的声明位置是它自己的 `capabilities` 属性。

| 方言 / 能力 | USERS | PRIVILEGES | ROUTINES | VIEWS | INDEXES | FOREIGN_KEYS | TRIGGERS | CROSS_DATABASE | MULTI_SCHEMA | EXPORT | DDL_TRANSACTION | EMBEDDED_MODE | BROWSE_SOURCE | ANALYSIS_SOURCE | READ_ONLY_DIALECT |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| MySQL | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | ✓ (软) | — | — | — | — |
| PostgreSQL | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — |
| H2 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ | ✓ (软) | ✓ | — | — | — |
| DuckDB | — | — | ✓ (MACRO) | ✓ | ✓ | ✓ | — | ✓ (ATTACH) | ✓ | ✓ | — | ✓ | — | — | — |
| SQLite | — | — | — | ✓ | ✓ | ✓ | — | — (ATTACH 不暴露) | — | ✓ | — | ✓ | — | — | — |

> **最后三列（`BROWSE_SOURCE` / `ANALYSIS_SOURCE` / `READ_ONLY_DIALECT`）是为「数据分析模式」准备的**
> —— 只读连 Hive / Impala / DuckDB，做分析与 Excel 报表（设计文档见
> [`docs/analysis-mode/README.md`](../docs/analysis-mode/README.md)）。它们回答的问题与前面那批
> 标签**不同**：那批问「这个方言实现没实现某组 SPI 方法」，这三个问「这个连接出现在哪些模式里」
> 「它能不能写」。
>
> - `BROWSE_SOURCE` —— 该连接可作为**浏览模式**的数据源；`ANALYSIS_SOURCE` —— 可作为
>   **数据分析模式**的数据源。两者让**同一个连接**同时出现在两个模式里，读的是**同一份
>   `connection.json`** —— 前端按能力位筛选，不复制配置。
> - `READ_ONLY_DIALECT` —— **方言级**只读：引擎里根本没有往它写入的路径，从浏览模式进来
>   写不了，从分析模式进来同样写不了。它与会话级只读开关（分析会话带 `read_only` 标记 →
>   写类别一律拒绝）**不是一回事**：后者只是**兜底闸门**，存在理由是「将来可写方言被加进
>   分析模式」。
> - **为什么这三列现在全是 `—`**：5 个内置方言**一个都不声明**。这是**有意**的 ——
>   枚举值先落地、**声明**留给后续里程碑：一旦有方言声明，前端就会按能力位渲染出对应入口，
>   而底层（分析模式、只读方言、Hive / Impala 实现）还没就绪，渲染出来只会点到走不通的路。
>   能力位是**声明**不是**探测**，所以「有枚举值、暂无声明者」不会误导任何人 ——
>   没人声明，能力集合里就不会出现它。

## 扩展自定义方言

实现 `DatabaseDialect` + 注册到 `ServiceLoader`，即被引擎自动发现：

1. **Gradle 依赖**：新建 `dialect-mydb/` 模块，`implementation(project(":api"))`。
2. **实现接口**：`class MyDbDialect : DatabaseDialect { override val driverName = "Mydb" … }` —— 只需实现该方言真正支持的方法，其余落到默认实现。
3. **注册 SPI**：在 `dialect-mydb/src/main/resources/META-INF/services/com.langbiantianya.dialect.DatabaseDialect` 写入 `com.mydb.MyDbDialect`。
4. **构建 & 部署**：`./gradlew dialect-mydb:jar` → 把 jar 放到 `engine/build/libs/dialects/` → 启动引擎时 `DialectLoader` 自动扫描并注册。
5. **验证**：客户端发 `SYSTEM.LIST_DRIVERS` 即可看到该方言的元数据。

## 五方言一句话索引

| 方言 | 一句话 | 参考 |
|---|---|---|
| MySQL | 最典型的 client-server 方言，驱动识别 / 用户权限 / 例程的参照实现 | [`dialect-mysql/README.md`](../dialect-mysql/README.md) |
| PostgreSQL | schema 层级 + 跨 database 查询（`CROSS_DATABASE` 唯一实现者） | [`dialect-postgresql/README.md`](../dialect-postgresql/README.md) |
| H2 | in-memory 模式，也是集成测试的常用载体 | [`dialect-h2/README.md`](../dialect-h2/README.md) |
| DuckDB | 嵌入式 OLAP，Excel / CSV / Parquet 预转换 | [`dialect-duckdb/README.md`](../dialect-duckdb/README.md) |
| SQLite | 最简单的嵌入式关系型方言（`FILE_BASED`） | [`dialect-sqlite/README.md`](../dialect-sqlite/README.md) |

引擎如何消费本 SPI（`DialectLoader` + `ServiceLoader` + `SYSTEM.LIST_DRIVERS`）见 [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md)，整体模块导航见根 [`ARCHITECTURE.md`](../ARCHITECTURE.md)。
