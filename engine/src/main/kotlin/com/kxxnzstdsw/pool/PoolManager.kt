package com.kxxnzstdsw.pool

import com.kxxnzstdsw.dialect.DatabaseDialect
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

object PoolManager {
    private val logger = LoggerFactory.getLogger(PoolManager::class.java)
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    /**
     * 获取连接（单参数版本 — 不设置 schema / catalog 上下文）。
     * 调用方负责必要时调用 [getConnection] 双参数版本或自行 setSearchPath / setCatalog。
     */
    fun getConnection(config: ConnectionConfig): Connection {
        val hashKey = poolKey(config, "")
        val dataSource = pools.computeIfAbsent(hashKey) { createDataSource(config, "") }
        val conn = dataSource.connection
        applyCatalog(conn, config)
        return conn
    }

    /**
     * 获取连接并设置 schema 上下文（PostgreSQL: SET search_path, H2: SET SCHEMA, MySQL: 忽略）。
     * 用于需要指定 schema 的操作（TABLE/DATA/SQL 等）。
     *
     * 池 key 包含 effective schema — 同一 (driver/host/.../database) 在不同 schema 下
     * 使用不同连接池,避免 SET search_path 残留在 HikariCP close() 之后污染下一次借用。
     */
    fun getConnection(config: ConnectionConfig, schema: String): Connection {
        val effectiveSchema = schema.ifBlank { config.schema }
        val hashKey = poolKey(config, effectiveSchema)
        val dataSource = pools.computeIfAbsent(hashKey) { createDataSource(config, effectiveSchema) }
        val conn = dataSource.connection
        // 新建连接 (lazy 触发 createDataSource 中的 connectionInitSql) 需补 setSearchPath;
        // 已存在池里的连接 setSearchPath 也是幂等 SET,可保一致性。
        if (effectiveSchema.isNotBlank()) {
            resolveDialect(config).setSearchPath(conn, effectiveSchema)
        }
        // catalog 与 schema 同理：**每次借出都设一遍**，不靠建池时的 connectionInitSql。
        // 理由见 [applyCatalog]。
        applyCatalog(conn, config)
        return conn
    }

    /**
     * 把会话的默认 catalog 切到 [ConnectionConfig.database]。
     *
     * ## 为什么必须有这一步
     *
     * 前端在左侧树上点哪个库，就把哪个库名放进 `ConnectionConfig.database`
     * （它也参与 [configKey]，于是每个库拿到**各自的连接池**）。
     * 但每个池的 JDBC URL 都来自连接配置里的同一个 `config.jdbcUrl` ——
     * **URL 里钉死的就是连接时指定的那个库**。
     *
     * 于是 `database` 只是个「池的区分标签」，从来没被真正应用到会话上：
     * `DataHandler.list` 拼出来的是 `SELECT * FROM <table>`（裸表名），
     * 裸表名按会话默认库解析，于是
     *
     * ```
     * Table 'sundays_probe.orders' doesn't exist
     * ```
         * 而用户在树上点的是 `shop`。表现就是：**连接配置里指定的那个库能看，
     * 其他库一点就报错**，且错误信息里那个库名恰好是「我没点的那个」，
     * 很难自己联想到根因。
     *
     * 表列表为什么是好的？因为 `TableHandler.list` 走的是 `information_schema`，
     * 本来就跨库 —— 于是「表列得出来、点开就报错」，这个割裂正是本缺陷的指纹。
     *
     * ## 为什么下沉到方言，而不是这里直接 `conn.catalog = x`
     *
     * `ConnectionConfig.database` 这个字段**是重载的**：MySQL 放 catalog 名，
     * DuckDB 放的是 `.duckdb` **文件路径**（`DuckDbSmoke` 就这么配的）。
     * 而 DuckDB JDBC 的 `setCatalog(x)` 内部发的是 `SET schema = 'x'` ——
     * 直接报 `Catalog Error: No catalog + schema named "C:\...\xx.duckdb"`，
     * 连接整个建不起来（实测：`DialectSmokeTest` [DuckDB] 与
     * `DuckDBHandlerIntegrationTest` 全红）。
     *
     * 「有没有 catalog 可切」只有方言自己知道，答案在 [DatabaseDialect.switchCatalog]
     * （默认空实现 = 没有，永远不抛）。
     *
     * 与 schema 完全同构：不靠建池时的 `connectionInitSql`，**每次借出都设一遍**。
     */
    private fun applyCatalog(conn: Connection, config: ConnectionConfig) {
        val catalog = config.database
        if (catalog.isBlank()) return
        try {
            resolveDialect(config).switchCatalog(conn, catalog)
        } catch (e: java.sql.SQLException) {
            // 方言声称自己有 catalog（否则走不到这里），那失败就是**真实失败**
            // （库不存在 / 无权限）—— 必须抛出去。
            // 吞掉的话，后续查询会静默跑在**错误的库**上，读到别的库的同名表，
            // 比报错危险得多。把库名写进消息，界面上才看得出是哪一步失败的。
            logger.warn("Failed to switch catalog to '$catalog': ${e.message}")
            throw java.sql.SQLException("切换到数据库 '$catalog' 失败：${e.message}", e)
        }
    }

    /**
     * 解析 config 对应的方言实例。
     *
     * - `config.jdbcUrl` 非空：按 URL scheme 反查方言（忽略 `config.driver`）。查不到说明没有任何
     *   插件声明该前缀 —— 直接抛错，而不是回退到 driver（那会拿错误的方言去建池）。
     * - 否则：按 `config.driver` 精确匹配（v2.11 之前的行为）。
     */
    internal fun resolveDialect(config: ConnectionConfig): DatabaseDialect =
        if (config.jdbcUrl.isNotBlank()) {
            DialectLoader.getDialectByJdbcUrl(config.jdbcUrl)
                ?: throw UnsupportedOperationException(
                    "No dialect plugin matches JDBC URL: ${config.jdbcUrl}"
                )
        } else {
            DialectLoader.getDialect(config.driver)
        }

    /**
     * 池缓存 key 的**配置部分** — 包含 driver + jdbcUrl + host + port + user + password + database 的 SHA-256。
     *
     * password / jdbcUrl 都参与 hash：同一 user/host/db 在不同 password 或 JDBC URL（URL 上可能挂方言
     * 特定参数）下应使用不同连接池,避免凭据混淆或参数串扰。摘要而非明文 —— key 不进日志也不含凭据。
     */
    private fun configKey(config: ConnectionConfig): String {
        val input = "${config.driver}:${config.jdbcUrl}:${config.host}:${config.port}:${config.user}:${config.password}:${config.database}"
        return sha256(input)
    }

    /**
     * 完整池 key = 配置摘要 [+ `#` + schema 摘要]。
     *
     * 结构化的两段式 key 让 [close] 能按配置前缀定位该配置下的**所有** schema 维度连接池
     * （单段 hash 无法反查归属）。
     */
    private fun poolKey(config: ConnectionConfig, schema: String): String {
        val base = configKey(config)
        return if (schema.isBlank()) base else "$base#${sha256(schema)}"
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 当前活跃连接池数量（诊断 / 集成测试用）。 */
    fun activePoolCount(): Int = pools.size

    /**
     * 关闭 [config] 对应的所有连接池（含该配置下各 schema 维度的池）—— 即“断开连接”。
     *
     * 与 [getConnection] 对称：getConnection 按需建池，close 释放该配置的全部池。其他配置的池不受影响；
     * 之后再次 [getConnection] 会重建池。
     *
     * @return 是否至少关闭了一个池（false = 该配置当前没有活跃池）
     */
    fun close(config: ConnectionConfig): Boolean {
        // 先回滚并释放该配置下钉住的事务会话 —— 否则池被关掉时会话仍持有已失效的连接，
        // 后续 COMMIT 会在一条死连接上静默失败。
        TransactionManager.closeSessionsFor(config)
        val base = configKey(config)
        val victims = pools.keys.filter { it == base || it.startsWith("$base#") }
        var closed = false
        for (key in victims) {
            pools.remove(key)?.let {
                it.close()
                closed = true
            }
        }
        if (closed) {
            val target = config.jdbcUrl.ifBlank { "${config.host}:${config.port}/${config.database}" }
            logger.info("Closed ${victims.size} pool(s) for $target")
        }
        return closed
    }

    /**
     * 连接配置指纹 —— 不含 schema 的那一段 key。
     * 供 [TransactionManager] 把会话归到所属配置（断开连接时批量回滚）。
     */
    internal fun configKeyOf(config: ConnectionConfig): String = configKey(config)

    /**
     * 取连接 —— 事务优先。
     *
     * `sessionId` 非空时复用该会话钉住的连接（autocommit=false），否则与 v2.15 一致地从池里借。
     * 留空 sessionId 的调用方完全不受事务功能影响。
     */
    fun getConnection(
        config: ConnectionConfig,
        schema: String,
        sessionId: String,
    ): Connection =
        if (sessionId.isBlank()) getConnection(config, schema)
        else TransactionManager.connectionFor(sessionId, config, schema)

    private fun createDataSource(config: ConnectionConfig, schema: String): HikariDataSource {
        val dialect = resolveDialect(config)
        // 局部名不能叫 jdbcUrl —— apply{} 内简单名会解析到 HikariConfig.jdbcUrl 上，导致自赋值
        val rawJdbcUrl = if (config.jdbcUrl.isNotBlank()) config.jdbcUrl
                         else dialect.buildJdbcUrl(config.host, config.port, config.database)

        // 建池时就把 URL 指向 `config.database`。
        //
        // 对**能在已有连接上切**的方言（MySQL）这一步是 no-op，切换交给 [applyCatalog]。
        // 对**连上就锁死**的方言（PostgreSQL）这一步是唯一的机会 —— 数据库名是启动参数，
        // JDBC 没有 `USE`，建完池再切已经晚了。
        //
        // 漏掉它的后果：PG 上从连接配置（URL 写死 bootstrap 库）连进来后，点左侧树里的
        // 任何别的库，看到的都还是 bootstrap 库的内容 —— 静默读到错的库，比报错危险得多。
        //
        // 安全前提：[poolKey] 已按 `config.database` 分池，不同 catalog 拿到的是不同的池，
        // 改写后的 URL 不会被旧池挡下来。
        val resolvedJdbcUrl = dialect.jdbcUrlForCatalog(rawJdbcUrl, config.database)

        logger.info("Creating new connection pool for driver=${dialect.driverName} url=$resolvedJdbcUrl schema=$schema")

        // 将驱动 ClassLoader 设为当前线程上下文,确保 HikariCP 能找到动态加载的 JDBC 驱动
        // 注:Thread TCCL 是线程全局 — 此处只在 pool 创建时设置一次,后续 getConnection 不需重复。
        DriverLoader.getClassLoader()?.let {
            Thread.currentThread().contextClassLoader = it
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = resolvedJdbcUrl
            username = config.user
            password = config.password
            driverClassName = dialect.jdbcDriverClassName

            // Performance tuning for desktop app
            maximumPoolSize = 5
            minimumIdle = 0
            idleTimeout = 600_000 // 10 minutes
            connectionTimeout = 5_000 // 5 seconds
            maxLifetime = 1_800_000 // 30 minutes

            connectionTestQuery = "SELECT 1"

            // 新建连接时自动设置 search_path (PostgreSQL/H2/DuckDB)
            // SQLite 不支持 schema,setSearchPath 实现为空操作,initSql 也不会设置 — 安全。
            if (schema.isNotBlank()) {
                dialect.buildSetSearchPathSql(schema)?.let { connectionInitSql = it }
            }
        }

        return HikariDataSource(hikariConfig)
    }

    fun closeAll() {
        // 先回滚所有事务会话再关池，顺序与 close(config) 相同
        TransactionManager.closeAll()
        logger.info("Closing all connection pools")
        pools.values.forEach { it.close() }
        pools.clear()
    }
}

