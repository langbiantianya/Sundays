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
     * 获取连接（单参数版本 — 不设置 schema 上下文）。
     * 调用方负责必要时调用 [getConnection] 双参数版本或自行 setSearchPath。
     */
    fun getConnection(config: ConnectionConfig): Connection {
        val hashKey = generateHashKey(config, "")
        val dataSource = pools.computeIfAbsent(hashKey) { createDataSource(config, "") }
        return dataSource.connection
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
        val hashKey = generateHashKey(config, effectiveSchema)
        val dataSource = pools.computeIfAbsent(hashKey) { createDataSource(config, effectiveSchema) }
        val conn = dataSource.connection
        // 新建连接 (lazy 触发 createDataSource 中的 connectionInitSql) 需补 setSearchPath;
        // 已存在池里的连接 setSearchPath 也是幂等 SET,可保一致性。
        if (effectiveSchema.isNotBlank()) {
            resolveDialect(config).setSearchPath(conn, effectiveSchema)
        }
        return conn
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
     * 池缓存 key — 包含 driver + jdbcUrl + host + port + user + password + database + schema。
     *
     * password / schema / jdbcUrl 都参与 hash：同一 user/host/db 在不同 password、schema 或
     * JDBC URL（URL 上可能挂方言特定参数）下应使用不同连接池,避免凭据混淆或 search_path 串扰。
     */
    private fun generateHashKey(config: ConnectionConfig, schema: String): String {
        val input = "${config.driver}:${config.jdbcUrl}:${config.host}:${config.port}:${config.user}:${config.password}:${config.database}:$schema"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun createDataSource(config: ConnectionConfig, schema: String): HikariDataSource {
        val dialect = resolveDialect(config)
        // 局部名不能叫 jdbcUrl —— apply{} 内简单名会解析到 HikariConfig.jdbcUrl 上，导致自赋值
        val resolvedJdbcUrl = if (config.jdbcUrl.isNotBlank()) config.jdbcUrl
                              else dialect.buildJdbcUrl(config.host, config.port, config.database)

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
        logger.info("Closing all connection pools")
        pools.values.forEach { it.close() }
        pools.clear()
    }
}