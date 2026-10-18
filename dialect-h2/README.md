# dialect-h2 — H2 方言插件

idb_engine 的 **H2 方言实现** —— 嵌入式内存数据库，也是引擎**集成测试的主载体方言**
（方言级测试 + engine 端到端集成测试都跑在 H2 上）。

SPI 接口定义见 [`api/README.md`](../api/README.md)，引擎如何消费方言见
[`engine/README.md`](../engine/README.md)。

## 元数据

| 属性 | 取值 |
|---|---|
| `driverName` / `displayName` | `"H2"` / `"H2 (In-Memory)"` |
| `jdbcDriverClassName` | `"org.h2.Driver"` |
| `connectionType` | `IN_MEMORY` |
| `requiresHost` / `requiresPort` / `supportsUser` / `supportsPassword` | 全部 `false` |
| `jdbcUrlExample` | `jdbc:h2:mem:mydb;DB_CLOSE_DELAY=-1` |
| `capabilities` | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, MULTI_SCHEMA, EXPORT, DDL_TRANSACTION, EMBEDDED_MODE` |

**为什么没有 `TRIGGERS` capability**：H2 2.3.232 的 `INFORMATION_SCHEMA.TRIGGERS` 表存在，
但 schema 与列名跟其他方言差异较大，`DatabaseDialect` 的默认 `UnsupportedOperationException`
实现已覆盖这个语义；真要用触发器，生产场景走 MySQL / PostgreSQL 即可。
详见 [`H2Dialect.kt`](./src/main/kotlin/com/langbiantianya/dialect/H2Dialect.kt) 的类级 KDoc。

## 实现要点

**URL 约定** `jdbc:h2:mem:<database>;DB_CLOSE_DELAY=-1` —— host/port 一律忽略，
`database` 以 `/path/data.mv.db` 形式给出时切到文件持久化。
`DB_CLOSE_DELAY=-1` 让数据库活到 JVM 退出，**连接池关掉重建后数据还在**，测试才能断言
「写进去的东西还在」。

**流式查询必须临时关掉 `autoCommit`**（与 PostgreSQL 同理，H2 不认
`setFetchSize(Integer.MIN_VALUE)` 那条约定）。

**`DDL_TRANSACTION` 是软支持 —— H2 对 DDL 隐式提交。** 这是实测的：
`autoCommit = false` 的连接上执行 `CREATE TABLE`，**在任何 commit / rollback 之前**
另借一条连接就已经查得到这张表，说明它根本没进事务；`ROLLBACK` 撤不掉。
后果与 MySQL 一样：一次「保存表结构」算出的那串 `CREATE` / `ALTER` 中途失败时，
已生效的部分留在库里。界面对 H2 不能承诺「全成功或全不做」。
回归见 `DdlTransactionIntegrationTest.H2 does not roll DDL back`
（那条用例故意断言「回滚后表还在」—— 它记录的是**已知限制**，不是期望行为）。

**方言特性**：schema 导航用 `[conn.catalog]` + `INFORMATION_SCHEMA.SCHEMATA`；
`search_path` 用 `SET SCHEMA <schema>`；`GET_DDL` 从 `INFORMATION_SCHEMA.COLUMNS` +
`TABLE_CONSTRAINTS` 重建（过滤同名系统表）；Routines 走 `INFORMATION_SCHEMA.ROUTINES` +
`PARAMETERS`；Users/Privileges 走 `INFORMATION_SCHEMA.USERS` + `TABLE_PRIVILEGES`。

`validateSqlFragment` 无方言额外禁用项。

## 模块结构

```
dialect-h2/
├── build.gradle.kts              # 依赖 :api + kotlinx-coroutines + slf4j + JUnit 5 + h2
└── src/
    ├── main/kotlin/com/langbiantianya/dialect/
    │   └── H2Dialect.kt
    └── test/kotlin/com/langbiantianya/dialect/
        └── H2DialectTest.kt      # 方言级测试
```

**唯一带方言级单元测试的模块** —— 其它方言的端到端测试都在 `engine/src/test/kotlin/integration/`。
测试侧的 H2 fixture 见
[`H2Fixture.kt`](../engine/src/test/kotlin/com/langbiantianya/testutil/H2Fixture.kt)：
`runH2 { conn -> … }` 封装建表 + 清理，用完自动清理。

## 跨链接

| 文档 | 内容 |
|---|---|
| [`api/README.md`](../api/README.md) | `DatabaseDialect` SPI 接口定义 |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §5 | 五方言能力矩阵 |
