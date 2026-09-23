package com.kxxnzstdsw.sundays.connection

import kotlinx.serialization.Serializable

/**
 * 连接配置数据模型 (v2.9).
 *
 * 支持五种数据库方言：
 * - MySQL (CLIENT_SERVER, default port 3306)
 * - PostgreSQL (CLIENT_SERVER, default port 5432)
 * - H2 (IN_MEMORY / EMBEDDED)
 * - DuckDB (EMBEDDED)
 * - SQLite (FILE_BASED)
 */
@Serializable
data class ConnectionConfig(
    val id: String,                  // 唯一标识 (UUID)
    val name: String,                // 连接名称（用户自定义）
    val dialect: DialectType = DialectType.MYSQL,        // 数据库方言
    val host: String = "",          // 主机地址 (CLIENT_SERVER)
    val port: Int? = null,          // 端口 (CLIENT_SERVER)
    val database: String = "",       // 数据库名 / 嵌入式库名 / 文件路径（引擎语义：CLIENT_SERVER = 库名，EMBEDDED 系 = URL 主体）
    val username: String = "",       // 用户名
    val password: String = "",       // 密码
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    val jdbcUrl: String = "",        // 完整 JDBC URL —— 连接的真相源（方言由 URL scheme 反查）
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 获取显示用端口，CLIENT_SERVER 类型有默认值 */
    val displayPort: Int
        get() = port ?: when (dialect) {
            DialectType.MYSQL -> 3306
            DialectType.POSTGRESQL -> 5432
            else -> 0
        }

    /** 是否为嵌入式数据库 */
    val isEmbedded: Boolean
        get() = connectionType == ConnectionType.EMBEDDED || connectionType == ConnectionType.IN_MEMORY || connectionType == ConnectionType.FILE_BASED

    /** 生成连接字符串 (用于显示) */
    fun connectionString(): String = when (connectionType) {
        ConnectionType.CLIENT_SERVER -> "$host:$displayPort/$database"
        ConnectionType.FILE_BASED -> database.ifBlank { "(未指定文件)" }
        ConnectionType.EMBEDDED -> database.ifBlank { "(内存库)" }
        ConnectionType.IN_MEMORY -> "mem:$database"
        ConnectionType.UNKNOWN -> ""
    }

    companion object {
        /** 方言 → 默认连接类型 + 默认端口 */
        fun defaultsFor(dialect: DialectType): Pair<ConnectionType, Int?> = when (dialect) {
            DialectType.MYSQL -> ConnectionType.CLIENT_SERVER to 3306
            DialectType.POSTGRESQL -> ConnectionType.CLIENT_SERVER to 5432
            DialectType.H2 -> ConnectionType.IN_MEMORY to null
            DialectType.DUCKDB -> ConnectionType.EMBEDDED to null
            DialectType.SQLITE -> ConnectionType.FILE_BASED to null
            DialectType.UNKNOWN -> ConnectionType.UNKNOWN to null
        }

        /** 方言切换时重置为该方言的默认配置（URL 由调用方经 JDBC URL 编解码器重建） */
        fun resetFor(newDialect: DialectType, existing: ConnectionConfig): ConnectionConfig {
            val (defaultType, defaultPort) = defaultsFor(newDialect)
            return existing.copy(
                dialect = newDialect,
                connectionType = defaultType,
                port = defaultPort,
                host = if (defaultType == ConnectionType.CLIENT_SERVER) existing.host.ifBlank { "localhost" } else "",
                database = if (defaultType == ConnectionType.CLIENT_SERVER) existing.database else "",
                password = "",
                jdbcUrl = "",
            )
        }
    }
}

/** 数据库方言类型 */
@Serializable
enum class DialectType {
    MYSQL,
    POSTGRESQL,
    H2,
    DUCKDB,
    SQLITE,
    UNKNOWN;

    /**
     * 该方言在前端可选的连接类型 —— 与引擎方言的 `connectionType` 元数据一致：
     * `Mysql` / `Postgresql` = CLIENT_SERVER；`H2` = IN_MEMORY（`jdbc:h2:mem:`）或 FILE_BASED
     * （`jdbc:h2:file:`）；`Duckdb` = EMBEDDED；`Sqlite` = FILE_BASED。
     */
    val supportedConnectionTypes: List<ConnectionType>
        get() = when (this) {
            MYSQL, POSTGRESQL -> listOf(ConnectionType.CLIENT_SERVER)
            H2 -> listOf(ConnectionType.IN_MEMORY, ConnectionType.FILE_BASED)
            DUCKDB -> listOf(ConnectionType.EMBEDDED)
            SQLITE -> listOf(ConnectionType.FILE_BASED)
            UNKNOWN -> emptyList()
        }

    /**
     * 引擎 `DatabaseDialect.driverName` —— proto `ConnectionConfig.driver` 必须填这个名字。
     *
     * 引擎以 `driverName` 为键注册方言（`DialectLoader.getDialect`），5 个内置方言的取值是
     * `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite` —— 与本枚举**常量名大小写不同**，
     * 因此**不能直接用 `Enum.name`**（`MYSQL` 会让引擎报 `No dialect plugin loaded for driver`）。
     *
     * 注意只有部分 handler 需要它：`PoolManager` 会优先按 `jdbcUrl` scheme 反查方言，但
     * `SchemaHandler` / `TableHandler` 等直接按 `config.driver` 取方言，所以请求必须带上正确值。
     *
     * 两侧一致性由 `desktopApp` 的 `DialectNameContractTest` 对照引擎 `SYSTEM.LIST_DRIVERS`
     * 的真实返回值校验 —— 任一侧改名都会让测试失败。
     */
    val engineDriverName: String
        get() = when (this) {
            MYSQL -> "Mysql"
            POSTGRESQL -> "Postgresql"
            H2 -> "H2"
            DUCKDB -> "Duckdb"
            SQLITE -> "Sqlite"
            // 未知方言没有可映射的引擎名 —— 原样返回让引擎报出可读错误，而不是静默套用某个方言
            UNKNOWN -> name
        }

    companion object {
        fun fromString(value: String): DialectType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 连接类型 */
@Serializable
enum class ConnectionType {
    CLIENT_SERVER,
    EMBEDDED,
    IN_MEMORY,
    FILE_BASED,
    UNKNOWN;

    companion object {
        fun fromString(value: String): ConnectionType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 连接配置列表 (用于 JSON 持久化) */
@Serializable
data class ConnectionList(
    val connections: List<ConnectionConfig> = emptyList(),
    val version: Int = 1,
)

/**
 * 持久化用的精简模型 —— 仅保存 id / name / dialect / jdbcUrl / username / password / 时间戳
 * `host` / `port` / `database` / `connectionType` 在加载后从 `jdbcUrl` 重新解析得出
 */
@Serializable
data class PersistedConnectionConfig(
    val id: String,
    val name: String,
    val dialect: DialectType,
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 持久化列表 wrapper */
@Serializable
data class PersistedConnectionList(
    val connections: List<PersistedConnectionConfig> = emptyList(),
    val version: Int = 2,
)

/**
 * 切换方言 —— 重置为该方言的默认连接类型 / 端口 / 清空凭据，并**立即折算 JDBC URL**。
 *
 * URL 是引擎侧的唯一真相源，因此向导进入 CREDENTIALS 步骤前配置必须已带合法 URL；
 * 折算失败（字段不足）时 URL 为空串，由向导在该步骤内补齐。
 */
fun ConnectionConfig.withDialect(newDialect: DialectType): ConnectionConfig =
    ConnectionConfig.resetFor(newDialect, this).let { it.copy(jdbcUrl = buildJdbcUrl(it)) }

/** 切换连接类型 —— URL 形状随类型变化（如 H2 `mem:` ↔ `file:`），用当前字段立即重新折算 */
fun ConnectionConfig.withConnectionType(newType: ConnectionType): ConnectionConfig =
    copy(connectionType = newType).let { it.copy(jdbcUrl = buildJdbcUrl(it)) }

