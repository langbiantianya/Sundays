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
    val database: String = "",       // 数据库名
    val username: String = "",       // 用户名
    val password: String = "",       // 密码
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    val filePath: String = "",       // 文件路径 (SQLite / H2 EMBEDDED)
    val useJdbcUrl: Boolean = false, // 是否使用 JDBC URL 配置
    val jdbcUrl: String = "",        // 自定义 JDBC URL (useJdbcUrl=true 时使用)
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
        ConnectionType.FILE_BASED -> filePath
        ConnectionType.EMBEDDED -> database
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

        /** 方言切换时重置为该方言的默认配置 */
        fun resetFor(newDialect: DialectType, existing: ConnectionConfig): ConnectionConfig {
            val (defaultType, defaultPort) = defaultsFor(newDialect)
            return existing.copy(
                dialect = newDialect,
                connectionType = defaultType,
                port = defaultPort,
                host = if (defaultType == ConnectionType.CLIENT_SERVER) existing.host.ifBlank { "localhost" } else "",
                database = if (defaultType == ConnectionType.CLIENT_SERVER) existing.database else "",
                password = "",
                filePath = "",
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
