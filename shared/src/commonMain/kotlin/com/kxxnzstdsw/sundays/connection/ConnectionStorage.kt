package com.kxxnzstdsw.sundays.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 连接配置持久化存储 (v2.12).
 *
 * 保存位置: `~/.config/sundays/connection.json`
 *
 * **持久化策略** (v2.11 起):
 * 仅持久化 [PersistedConnectionConfig]（id / name / dialect / jdbcUrl / username / password / 时间戳）。
 * `host` / `port` / `database` / `connectionType` 等**派生字段**在加载时通过 `parseJdbcUrl(jdbcUrl, dialect)`
 * 重建 —— 简化数据模型，确保 URL 始终是真相源。
 *
 * 加载时按文件中的 `version` 分派：
 * - **v2**（当前格式）→ [PersistedConnectionList]
 * - **v1**（历史格式，含 `host` / `port` / `database` / `filePath` 等完整字段）→ [LegacyConnectionList]，
 *   迁移时用 [buildJdbcUrl] 把派生字段折算成 URL（v1 的 `filePath` 参与折算），随后**回写为 v2**
 */
object ConnectionStorage {
    /** 当前持久化格式版本（[PersistedConnectionList] 的 version） */
    private const val PERSISTED_VERSION = 2

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 获取配置目录路径 */
    private fun configDir(): Path {
        val home = System.getProperty("user.home")
        return Paths.get(home, ".config", "sundays")
    }

    /** 获取配置文件路径 */
    private fun configFile(): Path {
        return configDir().resolve("connection.json")
    }

    /** 读取文件中的 `version` 字段；缺失 / 非数字时按当前格式（v2）处理 */
    private fun detectVersion(content: String): Int {
        return try {
            val version = (json.parseToJsonElement(content) as? JsonObject)
                ?.get("version")?.jsonPrimitive?.intOrNull
            version ?: PERSISTED_VERSION
        } catch (_: Exception) {
            PERSISTED_VERSION
        }
    }

    /**
     * 从 [PersistedConnectionConfig] 还原 [ConnectionConfig] —— 从 jdbcUrl 解析
     * host / port / database / connectionType（不可识别的 URL 回退到方言默认连接类型）。
     */
    private fun PersistedConnectionConfig.toConnectionConfig(): ConnectionConfig {
        val parts = parseJdbcUrl(jdbcUrl, dialect)
        val (defaultType, _) = ConnectionConfig.defaultsFor(dialect)
        return ConnectionConfig(
            id = id,
            name = name,
            dialect = dialect,
            host = parts.host,
            port = parts.port.toIntOrNull()?.takeIf { it > 0 },
            database = parts.database,
            username = username,
            password = password,
            connectionType = parts.connectionType.takeIf { it != ConnectionType.UNKNOWN } ?: defaultType,
            jdbcUrl = jdbcUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    /** 从 [ConnectionConfig] 生成 [PersistedConnectionConfig] —— 仅保留 jdbcUrl + credentials + meta。 */
    private fun ConnectionConfig.toPersisted(): PersistedConnectionConfig =
        PersistedConnectionConfig(
            id = id,
            name = name,
            dialect = dialect,
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    /** v1 记录 → v2 记录：`jdbcUrl` 为空时用派生字段（含 v1 的 `filePath`）折算 URL */
    private fun LegacyConnectionConfig.toPersisted(): PersistedConnectionConfig =
        PersistedConnectionConfig(
            id = id,
            name = name,
            dialect = dialect,
            jdbcUrl = jdbcUrl.ifBlank {
                buildJdbcUrl(
                    ConnectionConfig(
                        id = id,
                        name = name,
                        dialect = dialect,
                        host = host,
                        port = port,
                        database = database.ifBlank { filePath },
                        username = username,
                        password = password,
                        connectionType = connectionType,
                    )
                )
            },
            username = username,
            password = password,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    /**
     * 加载所有保存的连接配置.
     * @return 连接列表 (如果文件不存在或解析失败, 返回空列表)
     *
     * 同时支持 v1 (含完整字段) 和 v2 (仅 jdbcUrl) 两种 JSON 结构；v1 文件加载后自动迁移回写为 v2。
     */
    fun load(): ConnectionList {
        val file = configFile()
        return try {
            if (!Files.exists(file)) return ConnectionList()
            val content = Files.readString(file)

            if (detectVersion(content) >= PERSISTED_VERSION) {
                val persisted = json.decodeFromString<PersistedConnectionList>(content)
                return ConnectionList(
                    connections = persisted.connections.map { it.toConnectionConfig() },
                    version = PERSISTED_VERSION,
                )
            }

            // v1：完整字段格式 → 折算 URL 后迁移为 v2
            val legacy = json.decodeFromString<LegacyConnectionList>(content)
            val migrated = legacy.connections.map { it.toPersisted() }
            savePersisted(PersistedConnectionList(connections = migrated))
            ConnectionList(
                connections = migrated.map { it.toConnectionConfig() },
                version = PERSISTED_VERSION,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionList()
        }
    }

    /**
     * 保存连接配置列表.
     * @param connectionList 要保存的连接列表
     * @return 是否保存成功
     */
    fun save(connectionList: ConnectionList): Boolean {
        val persisted = PersistedConnectionList(
            connections = connectionList.connections.map { it.toPersisted() },
            version = PERSISTED_VERSION,
        )
        return savePersisted(persisted)
    }

    /** 直接写入持久化结构（迁移场景） */
    private fun savePersisted(persisted: PersistedConnectionList): Boolean {
        return try {
            val dir = configDir()
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            }
            val file = configFile()
            val content = json.encodeToString(persisted)
            Files.writeString(file, content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 添加或更新一个连接配置.
     * @param config 连接配置
     * @return 更新后的连接列表
     */
    fun upsert(config: ConnectionConfig): ConnectionList {
        val current = load()
        val existing = current.connections.toMutableList()
        val updated = config.copy(updatedAt = System.currentTimeMillis())
        val index = existing.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            existing[index] = updated
        } else {
            existing.add(updated)
        }
        val newList = ConnectionList(connections = existing)
        save(newList)
        return newList
    }

    /**
     * 删除一个连接配置.
     * @param id 要删除的连接 ID
     * @return 更新后的连接列表
     */
    fun delete(id: String): ConnectionList {
        val current = load()
        val newList = ConnectionList(connections = current.connections.filter { it.id != id })
        save(newList)
        return newList
    }

    /**
     * 获取单个连接配置.
     * @param id 连接 ID
     * @return 连接配置 (不存在返回 null)
     */
    fun get(id: String): ConnectionConfig? {
        return load().connections.find { it.id == id }
    }
}

/**
 * v1 磁盘记录 —— 含 `host` / `port` / `database` / `filePath` 等派生字段。
 * 仅供迁移读取，加载后立即折算为 [PersistedConnectionConfig]。
 */
@Serializable
private data class LegacyConnectionConfig(
    val id: String,
    val name: String,
    val dialect: DialectType = DialectType.MYSQL,
    val host: String = "",
    val port: Int? = null,
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    val filePath: String = "",
    val jdbcUrl: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** v1 列表 wrapper */
@Serializable
private data class LegacyConnectionList(
    val connections: List<LegacyConnectionConfig> = emptyList(),
    val version: Int = 1,
)
