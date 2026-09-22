# dialect-h2 — H2 方言插件

idb_engine 的 **H2 方言实现** —— **嵌入式内存数据库**，也是引擎**集成测试的主载体方言**（63 个方言级测试 + 60 个 engine 端到端集成测试 = 123 个测试基于 H2 运行）。

> **当前版本**：v2.9
>
> 架构与 SPI 接口定义见 [`api/README.md`](../api/README.md)。

---

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` | `"H2"` |
| `displayName` | `"H2 (In-Memory)"` |
| `jdbcDriverClassName` | `"org.h2.Driver"` |
| `connectionType` | `IN_MEMORY` |
| `requiresHost` / `requiresPort` | `false` |
| `supportsUser` / `supportsPassword` | `false` |
| `supportsSchema` | `true` |
| `supportsCrossDatabase` | `true` |
| `jdbcUrlExample` | `jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1` |
| `capabilities` | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, MULTI_SCHEMA, EXPORT, DDL_TRANSACTION, EMBEDDED_MODE` |

**为什么 H2 没有 TRIGGERS capability**：H2 2.3.232 `INFORMATION_SCHEMA.TRIGGERS` 表存在但 schema/列差异较大 —— SPI 默认 UOE 实现已覆盖；生产用例走 MySQL/PG 即可。

---

## 模块结构

```
dialect-h2/
├── build.gradle.kts              # 依赖 :api + kotlinx-coroutines + slf4j + JUnit 5 + h2
└── src/
    ├── main/kotlin/com/kxxnzstdsw/dialect/
    │   └── H2Dialect.kt          # 单文件实现（1342 行）
    └── test/kotlin/com/kxxnzstdsw/dialect/
        └── H2DialectTest.kt      # 63 个方言测试
```

**唯一一个有方言级单元测试模块** —— 其它方言（mysql / postgresql / duckdb / sqlite）的端到端测试均在 `engine/src/test/kotlin/integration/`。

---

## 连接约定

`buildJdbcUrl(host, port, database)` —— host/port 完全忽略，返回 `jdbc:h2:mem:$database;DB_CLOSE_DELAY=-1`（`DB_CLOSE_DELAY=-1` 让最后一个连接关闭后数据库仍存活到 JVM 退出，便于多连接共享数据）。

| database 取值 | H2 JDBC URL | 用途 |
|---|---|---|
| `""` 或 `"mydb"` | `jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1` | 内存数据库（多连接共享） |
| `"/path/data.mv.db"` | `jdbc:h2:file:/path/data;...` | H2 文件持久化 |

**流式查询配置**：与 PG 相同 —— 必须临时关闭 `autoCommit`。

---

## 方言特性

| 特性 | 实现 |
|---|---|
| **Schema 导航** | `[conn.catalog]` 单元素（database 级别）+ `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?`（schema 级别） |
| **search_path** | `SET SCHEMA <schema>`（等价于 PG search_path） |
| **TABLE CREATE** | 标准 SQL DDL；列定义支持 `DEFAULT` / `IDENTITY` / `PRIMARY KEY` inline |
| **MODIFY COLUMN** | `ALTER TABLE ALTER COLUMN`（类型 / nullable / default 改写） |
| **GET_DDL** | 从 `INFORMATION_SCHEMA.COLUMNS` + `TABLE_CONSTRAINTS` 重建（过滤同名系统表） |
| **Routines** | `INFORMATION_SCHEMA.ROUTINES` + `PARAMETERS`（FUNCTION / PROCEDURE / TRIGGER） |
| **Users / Privileges** | `INFORMATION_SCHEMA.USERS` + `INFORMATION_SCHEMA.TABLE_PRIVILEGES` |

### SQL 危险关键词（方言层 `validateSqlFragment`）

通用集合（无方言额外禁用项）。

---

## 集成测试的 H2 用法

`H2Fixture` 类（`engine/src/test/kotlin/integration/H2Fixture.kt`）封装测试用 H2 连接创建 + 建表 + 清理逻辑：

```kotlin
class MyTest {
    @Test
    fun `something`() = runH2 { conn ->
        // conn 是已建好测试 schema 的 H2 connection
        // 用完自动清理
    }
}
```

`H2DialectTest.kt` 自身 63 个测试用例覆盖：

- Schema 列表（database / schema 两级）
- Table CRUD + 列定义 + ALTER COLUMN
- Index / FK / View / Routine CRUD
- User / Privileges CRUD
- GENERATE 造数
- 大量并发边界

---

## 已知约束

- **嵌入式限制**：H2 文件模式（`.mv.db`）支持，但默认走内存模式（无文件持久化）
- **无 User 概念**：H2 有 USER 表但 schema 与 PG/MySQL 差异大，引擎级集成测试不依赖 USER CRUD
- **Trigger**：H2 2.x `CREATE TRIGGER` 语法与 PG 接近但语义有差异；引擎默认走 MySQL/PG trigger 路径

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | DatabaseDialect SPI 接口定义 |
| [`engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | 引擎如何消费方言 + DialectLoader |
| `engine/src/test/kotlin/integration/H2Fixture.kt` | 测试 fixture 实现 |