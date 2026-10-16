# dialect-mysql — MySQL 方言插件

idb_engine 的 **MySQL 方言实现**，以 SPI 插件形式提供，引擎经 `ServiceLoader` 动态加载。

SPI 接口定义见 [`api/README.md`](../api/README.md)，引擎如何消费方言见
[`engine/README.md`](../engine/README.md)。

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` / `displayName` | `"Mysql"` / `"MySQL"` |
| `jdbcDriverClassName` | `"com.mysql.cj.jdbc.Driver"` |
| `connectionType` / `defaultPort` | `CLIENT_SERVER` / `3306` |
| `jdbcUrlExample` | `jdbc:mysql://127.0.0.1:3306/mydb` |
| `capabilities` | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, TRIGGERS, EXPORT, DDL_TRANSACTION` |

**全功能方言**：5 个方言中唯一同时支持用户 / 权限 / 例程 / 触发器 + DDL 事务 + 导出 的方言。

## 实现要点

**连接与流式查询** —— [`MySQLDialect.kt`](./src/main/kotlin/com/langbiantianya/dialect/MySQLDialect.kt)
的类级 KDoc 记了三件事，都是踩出来的：

- `buildJdbcUrl` 返回 `jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`；
  默认 `useSSL=false` 是开发友好，生产可用 `ConnectionConfig.properties` 覆盖
- **流式查询靠 `setFetchSize(Integer.MIN_VALUE)` 打开服务端游标，不需要关 `autoCommit`** ——
  这是 5 个方言里唯一如此的（PG / H2 / SQLite 都必须临时关闭）
- `DDL_TRANSACTION` 在这里是**软支持**：MySQL 对 DDL 隐式提交，出错时前面已成功的
  `CREATE TABLE` 回滚不回来

**方言特性**：schema 导航用 `SHOW DATABASES` 过滤系统库；`CREATE DATABASE` 支持
`CHARACTER SET` / `COLLATE`；`GET_DDL` 用 `SHOW CREATE TABLE` 原样返回；Routines 走
`INFORMATION_SCHEMA.ROUTINES` + `PARAMETERS` + `EVENTS`；Users/Privileges 走 `mysql.user`
+ `SHOW GRANTS`；Triggers 走 `SHOW TRIGGERS` + `SHOW CREATE TRIGGER`。

`validateSqlFragment` 在通用集合之外，ORDER BY 的标识符额外允许反引号 `` `col` ``。

## 模块结构

```
dialect-mysql/
├── build.gradle.kts          # 依赖 :api + kotlinx-coroutines
└── src/main/kotlin/com/langbiantianya/dialect/
    └── MySQLDialect.kt       # 单文件实现
```

无单元测试 —— MySQL 的端到端覆盖在 `engine/src/test/kotlin/integration/`。

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | `DatabaseDialect` SPI 接口定义 |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §5 | 五方言能力矩阵 |
