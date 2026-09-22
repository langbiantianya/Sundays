# dialect-sqlite — SQLite 方言插件

idb_engine 的 **SQLite 方言实现** —— **本地嵌入式关系型数据库**。v2.8 新增，第 5 个方言。

> **当前版本**：v2.9
>
> 架构与 SPI 接口定义见 [`api/README.md`](../api/README.md)。

---

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` | `"Sqlite"` |
| `displayName` | `"SQLite (Embedded)"` |
| `jdbcDriverClassName` | `"org.sqlite.JDBC"` |
| `connectionType` | `FILE_BASED` |
| `requiresHost` / `requiresPort` | `false` |
| `supportsUser` / `supportsPassword` | `false` |
| `supportsSchema` | `false`（SQLite 只有 `main` / `temp` 内置 schema，业务层不暴露） |
| `supportsCrossDatabase` | `false`（业务层不暴露 `ATTACH`，嵌入场景罕见） |
| `jdbcUrlExample` | `jdbc:sqlite:/path/to/data.db`（或 `:memory:`） |
| `capabilities` | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` |

**最受限的能力方言**：

| 不支持的能力 | 抛出的异常 |
|---|---|
| `USERS` / `PRIVILEGES` | `UnsupportedOperationException("Sqlite 不支持用户管理")` |
| `TRIGGERS` | `UnsupportedOperationException`（SPI 默认实现） |
| `ROUTINES` | `UnsupportedOperationException("Sqlite 不支持函数/存储过程")` |
| `MULTI_SCHEMA` | schema 字段被忽略，传值也不报错 |
| `CROSS_DATABASE` | 数据库名字段即路径，无 database 切换 |

---

## 模块结构

```
dialect-sqlite/
├── build.gradle.kts              # 依赖 :api + kotlinx-coroutines + slf4j + JUnit 5 + sqlite
└── src/
    ├── main/kotlin/com/kxxnzstdsw/dialect/
    │   └── SQLiteDialect.kt      # 单文件实现（778 行）
    └── test/kotlin/com/kxxnzstdsw/dialect/
        └── SQLiteDialectTest.kt  # 62 个方言测试
```

---

## 连接约定

`buildJdbcUrl(host, port, database)` 返回 `jdbc:sqlite:<database>` —— host/port/user/password 全部忽略，**`database` 字段承载文件路径**。

| database 取值 | 实际 SQLite URL | 用途 |
|---|---|---|
| `""` 或 `":memory:"` | `jdbc:sqlite::memory:` | 进程内内存数据库（每连接私有） |
| `"/path/to/data.db"` | `jdbc:sqlite:/path/to/data.db` | 本地 SQLite 文件（多连接共享，文件锁） |

> ⚠️ **内存模式每连接私有** —— PoolManager 多连接不共享同一实例。集成测试一律走**临时 `.db` 文件**。

**流式查询配置**：与 PG 相同 —— 必须临时关闭 `autoCommit`。

---

## 方言特性

| 特性 | 实现 |
|---|---|
| **默认 schema** | SQLite 只有内置 `main` / `temp`，业务层不暴露 schema 字段 |
| **自增主键** | `INTEGER PRIMARY KEY AUTOINCREMENT`（**必须 `INTEGER` 类型**，必须 inline 在列定义里）<br>**`TableHandler.create` 检测到 `autoIncrementColumns.isNotEmpty()` 时不再追加表级 `PRIMARY KEY (cols)`** —— 否则 SQLite 报 "more than one primary key" 错误 |
| **GET_DDL** | 直接读 `sqlite_master.sql` 原文（用户当时建的原样） |
| **MODIFY COLUMN** | **仅支持 `RENAME COLUMN`**（SQLite 无 `ALTER COLUMN` 类型/默认值/可空性变更） |
| **FK 管理** | **table-rebuild 路径**：CREATE temp AS SELECT → DROP → CREATE with FK → INSERT → DROP temp<br>`addForeignKey` 不支持 `CONSTRAINT` 子句名（SQLite CREATE TABLE 语法限制） |
| **视图** | 直接读 `sqlite_master.sql`；DROP/GET_DDL 完整支持 |
| **索引** | `sqlite_master` + 解析 DDL 提取列名 + UNIQUE 判定 |
| **TRUNCATE** | `DELETE FROM <table>` + `DELETE FROM sqlite_sequence WHERE name = '<table>'`（重置自增） |
| **多 database** | `ATTACH DATABASE '<path>' AS <alias>`；删除走 `DETACH` |

### SQL 危险关键词（方言层 `validateSqlFragment`）

通用集合 + SQLite 特有禁用：`ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` / `VACUUM` / `REINDEX`。

---

## SQLite 自增主键的特殊处理（与其它方言的关键区别）

SQLite 自增主键**必须**：
1. 类型**只能**是 `INTEGER`（不能是 `INT` / `BIGINT`）
2. 必须 **inline 在列定义里**（不能是表级 `PRIMARY KEY`）
3. 完整语法：`name INTEGER PRIMARY KEY AUTOINCREMENT`

引擎 `TableHandler.create` 检测到 `autoIncrementColumns.isNotEmpty()` 时：
- ✅ 生成 `name INTEGER PRIMARY KEY AUTOINCREMENT`
- ❌ 跳过表级 `PRIMARY KEY (cols)` 子句（避免 SQLite "more than one primary key" 错误）

---

## 已知约束

- **不支持 `MODIFY COLUMN` 类型/默认值/可空性变更**：仅 `RENAME COLUMN`
- **FK 子句名被忽略**：`fk_name="my_fk"` 实际重建表时 SQlite 不保留 CONSTRAINT 子句名
- **内存模式不共享**：每个 `:memory:` 连接都是独立数据库实例（PoolManager 不会跨连接复用）
- **schema 字段不报错但被忽略**：传 `schema="public"` 给 SQLite 方言不会报错，但 SQLite 只有 `main` / `temp`
- **Routine 概念不适用**：SQLite 无过程/函数/FUNCTION/PROCEDURE/TRIGGER routine 概念（TRIGGER 在 SQLite 中是 DDL 但不是 ROUTINE 类型）

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | DatabaseDialect SPI 接口定义 |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) §5.14 | SQLite 端到端集成测试 |
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) §10 v2.8 迁移日志 | SQLite 引入的设计决策 |