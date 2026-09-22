# api — DatabaseDialect SPI

**零外部依赖的公共 SPI 模块** —— 定义方言插件必须实现的接口 + 连接元数据扩展枚举。引擎通过 `ServiceLoader<DatabaseDialect>` 自动发现并注册方言。

> **当前版本**：v2.9
>
> 元数据扩展（v2.8）：`displayName` / `connectionType` / `requiresHost` / `defaultPort` / `capabilities` 等 11 个属性已纳入 SPI，前端通过 `SYSTEM.LIST_DRIVERS` 动态渲染连接表单。

---

## 模块结构

```
api/
├── build.gradle.kts                                  # 零外部依赖
└── src/main/kotlin/com/kxxnzstdsw/dialect/
    ├── DatabaseDialect.kt                            # 核心 SPI 接口（621 行，~50 个方法/属性）
    ├── ConnectionType.kt                             # CLIENT_SERVER / EMBEDDED / FILE_BASED / IN_MEMORY
    ├── DialectCapability.kt                          # 12 个能力标签（USERS / VIEWS / ROUTINES ...）
    └── DialectUtil.kt                                # 共享工具方法（引用 / 转义等）
```

**依赖关系**：
- `api/` ← `dialect-*`（实现）
- `api/` ← `engine/`（通过 `ServiceLoader<DatabaseDialect>` 在 `DialectLoader` 中消费）
- `api/` **没有任何运行时依赖**（仅 JDK 标准库）

---

## `DatabaseDialect` 接口

方言插件实现该接口的全部方法。v2.8 起新增 **连接元数据扩展**（11 个 `val` 属性带默认实现），**完全向后兼容** —— 旧版方言无需任何修改。

### 接口分类

| 类别 | 方法/属性数 | 说明 |
|---|---|---|
| **驱动识别** | 3 | `driverName` / `jdbcDriverClassName` / `buildJdbcUrl(...)` |
| **连接元数据（v2.8）** | 11 | 决定前端表单字段显隐（详见下表） |
| **流式查询配置** | 2 | `configureConnectionForStreaming` / `restoreConnectionAfterStreaming` |
| **Schema 上下文** | 2 | `setSearchPath` / `buildSetSearchPathSql` |
| **Schema 操作** | 4 | `listDatabases` / `listSchemas` / `createSchema` / `deleteSchema` |
| **Table / Column** | 6 | `listTables` / `listColumns` / `getCreateTableDDL` / `buildColumnDefinition` / `buildAddColumnSQL` / `buildDropColumnSQL` / `buildModifyColumnSQL` |
| **Table 操作** | 2 | `renameTable` / `truncateTable` |
| **Table 选项** | 3 | `buildTableOptionsSQL` / `buildPostCreateStatements` / `buildPreCreateStatements` (v2.7 新增) |
| **SQL 校验** | 2 | `validateSqlFragment` / `validateOrderBy` |
| **用户权限** | 5 | `listUsers` / `createUser` / `deleteUser` / `updatePassword` / `updatePrivileges` / `listPrivileges` / `listAllGrants` |
| **函数/存储过程** | 6 | `listRoutines` / `getRoutineDDL` / `getRoutineInfo` / `createRoutine` / `dropRoutine` / `callRoutine` / `debugRoutine` / `validateRoutineDDL` |
| **视图** | 4 | `listViews` / `createView` / `dropView` / `getViewDDL`（默认抛 `UnsupportedOperationException`） |
| **索引** | 3 | `listIndexes` / `createIndex` / `dropIndex`（默认抛 UOE） |
| **外键** | 3 | `listForeignKeys` / `addForeignKey` / `dropForeignKey`（默认抛 UOE） |
| **触发器** | 2 | `listTriggers` / `getTriggerDDL`（默认抛 UOE） |
| **SQL / Server** | 2 | `explainSQL` / `testConnection` / `getServerInfo` |

---

## 连接元数据（v2.8 新增）

| 属性 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `displayName` | `String` | `driverName` | 前端展示名（如 `"MySQL"` / `"SQLite (Embedded)"`） |
| `connectionType` | `ConnectionType` | `CLIENT_SERVER` | 连接模式（见下表） |
| `requiresHost` | `Boolean` | `connectionType == CLIENT_SERVER` | 是否需要 host 字段 |
| `requiresPort` | `Boolean` | `connectionType == CLIENT_SERVER` | 是否需要 port 字段 |
| `defaultPort` | `Int?` | `null` | 默认端口（0 = 无） |
| `supportsUser` | `Boolean` | `connectionType == CLIENT_SERVER` | 是否需要 user 字段 |
| `supportsPassword` | `Boolean` | `supportsUser` | 是否需要 password 字段 |
| `supportsSchema` | `Boolean` | `false` | 是否支持 schema 概念（PG/H2/DuckDB = true） |
| `supportsCrossDatabase` | `Boolean` | `false` | 是否支持跨 database 查询（PG = true） |
| `jdbcUrlExample` | `String` | `"jdbc:example://host:1234/db"` | 示例 JDBC URL（前端 placeholder） |
| `capabilities` | `Set<DialectCapability>` | `emptySet()` | 方言能力标签（前端据此决定按钮显隐） |

**用途**：`SYSTEM.LIST_DRIVERS` action 枚举所有已加载方言，调用方得到 `repeated DialectInfo`，前端据此**动态渲染"新建连接"表单**——无需硬编码每个 driver 的字段需求。

---

## `ConnectionType` 枚举

| 值 | 含义 | 典型方言 | 表单字段 |
|---|---|---|---|
| `CLIENT_SERVER` | 客户端/服务端模式，需要 host + port + user + password | MySQL / PostgreSQL | host, port, user, password, database |
| `EMBEDDED` | 嵌入式进程内运行，host/port 全部忽略 | DuckDB | database (即文件路径或 `:memory:`) |
| `FILE_BASED` | 文件型嵌入式，database 即路径 | SQLite | database (即 `.db` 文件路径) |
| `IN_MEMORY` | 嵌入式纯内存变种，database 可空 | H2 / DuckDB `:memory:` | database 可选 |

---

## `DialectCapability` 枚举

12 个能力标签 —— 前端据此决定按钮/菜单是否显示：

| 标签 | 含义 | 典型方言 |
|---|---|---|
| `USERS` | 支持用户管理（USER.LIST/CREATE/UPDATE/DELETE） | MySQL / PG |
| `PRIVILEGES` | 支持权限管理（USER.GRANTS） | MySQL / PG |
| `ROUTINES` | 支持函数/存储过程 | MySQL / PG / H2 |
| `VIEWS` | 支持视图 | MySQL / PG / H2 / DuckDB / SQLite |
| `INDEXES` | 支持索引 | MySQL / PG / H2 / DuckDB / SQLite |
| `FOREIGN_KEYS` | 支持外键 | MySQL / PG / H2 / DuckDB / SQLite |
| `TRIGGERS` | 支持触发器 | MySQL / PG / H2（SPI 默认 UOE） |
| `CROSS_DATABASE` | 支持跨 database 查询 | PostgreSQL |
| `MULTI_SCHEMA` | 支持多 schema 导航 | PostgreSQL / H2 / DuckDB |
| `EXPORT` | 支持数据导出 | MySQL / PG / DuckDB / SQLite |
| `DDL_TRANSACTION` | DDL 在事务中可回滚 | MySQL / PG |
| `EMBEDDED_MODE` | 嵌入式运行 | H2 / DuckDB / SQLite |

---

## 扩展自定义方言

实现 `DatabaseDialect` 接口 + 注册到 `ServiceLoader` 即被引擎自动发现。流程：

1. **Gradle 依赖**：新建 `dialect-mydb/` 模块，`implementation(project(":api"))`
2. **实现接口**：创建 `class MyDbDialect : DatabaseDialect { override val driverName = "Mydb" ... }`
3. **注册 SPI**：在 `dialect-mydb/src/main/resources/META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect` 写入：
   ```
   com.mydb.MyDbDialect
   ```
4. **构建 & 部署**：`./gradlew dialect-mydb:jar` → 把 jar 放到 `engine/build/libs/dialects/` → 启动引擎时 `DialectLoader` 自动扫描并注册
5. **调用**：客户端发 `SYSTEM.LIST_DRIVERS` 即可看到该方言元数据

详细示例见 [`dialect-mysql/`](../../dialect-mysql/)（最典型的 client-server 方言）和 [`dialect-sqlite/`](../../dialect-sqlite/)（最简单的嵌入式方言）。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`engine/ARCHITECTURE.md`](../../engine/ARCHITECTURE.md) | 引擎如何消费 SPI：`DialectLoader` + `ServiceLoader` + `SYSTEM.LIST_DRIVERS` |
| [`dialect-mysql/README.md`](../../dialect-mysql/README.md) | MySQL 方言实现（client-server 模式参考） |
| [`dialect-postgresql/README.md`](../../dialect-postgresql/README.md) | PostgreSQL 方言（schema/cross-database 参考） |
| [`dialect-h2/README.md`](../../dialect-h2/README.md) | H2 方言（in-memory 模式 + 集成测试载体） |
| [`dialect-duckdb/README.md`](../../dialect-duckdb/README.md) | DuckDB 方言（v2.7 嵌入式 OLAP，Excel 预转换） |
| [`dialect-sqlite/README.md`](../../dialect-sqlite/README.md) | SQLite 方言（v2.8 嵌入式关系型） |
| [根 `../ARCHITECTURE.md`](../../ARCHITECTURE.md) | 整体架构与方言矩阵 |