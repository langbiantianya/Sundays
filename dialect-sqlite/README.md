# dialect-sqlite — SQLite 方言插件

idb_engine 的 **SQLite 方言实现** —— 本地嵌入式关系型数据库。

SPI 接口定义见 [`api/README.md`](../api/README.md)，引擎如何消费方言见
[`engine/README.md`](../engine/README.md)。

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` / `displayName` | `"Sqlite"` / `"SQLite (Embedded)"` |
| `jdbcDriverClassName` | `"org.sqlite.JDBC"` |
| `connectionType` | `FILE_BASED` |
| `requiresHost` / `requiresPort` / `supportsUser` / `supportsPassword` | 全部 `false` |
| `jdbcUrlExample` | `jdbc:sqlite:/path/to/data.db`（或 `:memory:`） |
| `capabilities` | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` |

**最受限的能力方言**，不支持的走 `UnsupportedOperationException`：

| 不支持 | 表现 |
|---|---|
| `USERS` / `PRIVILEGES` | `"Sqlite 不支持用户管理"` |
| `ROUTINES` | `"Sqlite 不支持函数/存储过程"` |
| `TRIGGERS` | SPI 默认实现抛 UOE（SQLite 有 TRIGGER DDL，但它不是 ROUTINE 类型） |
| `MULTI_SCHEMA` | 传 `schema` 不报错但被忽略（SQLite 只有 `main` / `temp`） |
| `CROSS_DATABASE` | `ATTACH` 不对业务层暴露 |

## 实现要点

[`SQLiteDialect.kt`](./src/main/kotlin/com/langbiantianya/dialect/SQLiteDialect.kt) 的类级 KDoc
记了完整约束，几条最容易踩的：

- **流式查询必须临时关掉 `autoCommit`**（与 PG / H2 同理）。关掉后**必须恢复**，
  否则这条连接会一直留在未提交事务里，后续写语句被同一个事务吞掉，
  且可能被 `SQLITE_BUSY` 锁住读不到别人的未提交改动。
- **自增主键必须 inline 且类型必须是 `INTEGER`**：`name INTEGER PRIMARY KEY AUTOINCREMENT`。
  引擎 `TableHandler.create` 检测到 `autoIncrementColumns` 非空时会**跳过表级
  `PRIMARY KEY (cols)`**，否则 SQLite 报 "more than one primary key"。
- **`MODIFY COLUMN` 只支持 `RENAME COLUMN`** —— SQLite 没有改类型 / 默认值 / 可空性的语法。
- **FK 管理走 table-rebuild**：CREATE temp AS SELECT → DROP → CREATE with FK → INSERT → DROP temp；
  `addForeignKey` 不支持 `CONSTRAINT` 子句名。
- **`TRUNCATE`** = `DELETE FROM <table>` + 清 `sqlite_sequence`（否则自增不回零）。
- **内存模式每连接私有**：`:memory:` 的每个连接都是独立实例，连接池不跨连接复用 ——
  集成测试一律走临时 `.db` 文件。

`validateSqlFragment` 在通用集合外额外禁 `ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` /
`VACUUM` / `REINDEX`。

## 模块结构

```
dialect-sqlite/
├── build.gradle.kts              # 依赖 :api + kotlinx-coroutines + slf4j + JUnit 5 + sqlite
└── src/
    ├── main/kotlin/com/langbiantianya/dialect/
    │   └── SQLiteDialect.kt
    └── test/kotlin/com/langbiantianya/dialect/
        └── SQLiteDialectTest.kt
```

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | `DatabaseDialect` SPI 接口定义 |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §5 | 五方言能力矩阵 |
