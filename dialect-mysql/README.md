# dialect-mysql — MySQL 方言插件

idb_engine 的 **MySQL 方言实现**，以 SPI 插件形式提供。引擎通过 `ServiceLoader<DatabaseDialect>` 动态加载并注册。

> **当前版本**：v2.9
>
> 架构与 SPI 接口定义见 [`api/README.md`](../api/README.md)。引擎如何消费方言见 [`engine/CLAUDE.md`](../engine/CLAUDE.md)。

---

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` | `"Mysql"` |
| `displayName` | `"MySQL"` |
| `jdbcDriverClassName` | `"com.mysql.cj.jdbc.Driver"` |
| `connectionType` | `CLIENT_SERVER` |
| `defaultPort` | `3306` |
| `requiresHost` / `requiresPort` | `true` |
| `supportsUser` / `supportsPassword` | `true` |
| `supportsSchema` | `false`（schema == database，无二级导航） |
| `supportsCrossDatabase` | `false`（单连接单库） |
| `jdbcUrlExample` | `jdbc:mysql://127.0.0.1:3306/mydb` |
| `capabilities` | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, TRIGGERS, EXPORT, DDL_TRANSACTION` |

**全功能方言**：5 个方言中**唯一**同时支持用户/权限/函数存储过程/触发器 + DDL 事务 + 导出 的方言。

---

## 模块结构

```
dialect-mysql/
├── build.gradle.kts          # 依赖 :api + kotlinx-coroutines
└── src/main/kotlin/com/kxxnzstdsw/dialect/
    └── MySQLDialect.kt       # 单文件实现（1144 行）
```

无单元测试（MySQL 方言的端到端覆盖在 `engine/src/test/kotlin/integration/`）。

---

## 连接约定

`buildJdbcUrl(host, port, database)` 返回 `jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`，默认 `useSSL=false`（开发友好；生产可通过 `ConnectionConfig.properties` 覆盖）。

**流式查询配置**：MySQL 流式读取用 `Statement.setFetchSize(Integer.MIN_VALUE)` 启用服务端游标，**不需要关闭 `autoCommit`**（与 PG / SQLite 相反）。

---

## 方言特性

| 特性 | 实现 |
|---|---|
| **Schema 导航** | `SHOW DATABASES` 过滤系统库（`information_schema` / `mysql` / `performance_schema` / `sys`）；`listSchemas` 返回单元素（schema == database） |
| **CREATE DATABASE** | 支持 `CHARACTER SET` / `COLLATE` 选项（从 `options` map 读取） |
| **TABLE CREATE** | 支持 `ENGINE` / `CHARSET` / `COLLATE` / `COMMENT` 选项 |
| **MODIFY COLUMN** | 标准 `ALTER TABLE MODIFY COLUMN`（改类型 / nullable / default） |
| **GET_DDL** | `SHOW CREATE TABLE` 原样返回（含 ENGINE / CHARSET / COLLATE / COMMENT） |
| **Routines** | `INFORMATION_SCHEMA.ROUTINES` + `PARAMETERS` + `EVENTS`（含 TRIGGER 类型） |
| **Users / Privileges** | `mysql.user` + `SHOW GRANTS` + 完整 GRANT/REVOKE 语句构造（含 `WITH GRANT OPTION`） |
| **Triggers** | `SHOW TRIGGERS` + `SHOW CREATE TRIGGER` |

### SQL 危险关键词（方言层 `validateSqlFragment`）

通用集合 + MySQL 额外允许：ORDER BY 标识符允许反引号 `` `col` ``。

---

## 已知约束

- **DDL 不在事务内**：MySQL 隐式提交 DDL，强制将 DDL_TRANSACTION capability 标注视为"软支持"；引擎遇 DDL 错误会回滚整批 CREATE TABLE 语句中的已成功部分
- **Schema 概念**：MySQL 单连接只对应一个 database，schema == database，`level=schema` 永远返回单元素
- **触发器 DDL**：依赖 `SHOW CREATE TRIGGER`，MySQL 5.7 / 8.0 行为有差异

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | DatabaseDialect SPI 接口定义 |
| [`engine/CLAUDE.md`](../engine/CLAUDE.md) §5 | 引擎如何调用方言 + handler 矩阵 |
| [根 `CLAUDE.md`](../CLAUDE.md) | 整体架构 + 双模式对比 |