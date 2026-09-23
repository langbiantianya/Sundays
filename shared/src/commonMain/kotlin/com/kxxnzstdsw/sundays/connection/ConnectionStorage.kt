package com.kxxnzstdsw.sundays.connection

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 连接配置持久化存储 (v2.11).
 *
 * 保存位置: `~/.config/sundays/connection.json`
 *
 * **持久化策略** (v2.11 起):
 * 仅持久化 [PersistedConnectionConfig]（id / name / dialect / jdbcUrl / username / password / 时间戳）。
 * `host` / `port` / `database` / `connectionType` / `filePath` 等**派生字段**在加载时通过 `parseJdbcUrl(jdbcUrl, dialect)` 重建 —— 简化数据模型，确保 URL 始终是真相源。
 *
 * 加载时若遇到 v1 格式（保存了完整 `ConnectionConfig`），自动迁移：剥离 `database` 等字段为 url-encoded query param。
 */
object ConnectionStorage {
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

    /**
     * 从 [PersistedConnectionConfig] 还原 [ConnectionConfig] —— 从 jdbcUrl 解析 host / port / database / connectionType / filePath。
     */
    private fun PersistedConnectionConfig.toConnectionConfig(): ConnectionConfig {
        val parts = parseJdbcUrl(jdbcUrl, dialect)
        val (defaultType, _) = ConnectionConfig.defaultsFor(dialect)
        return ConnectionConfig(
            id = id,
            name = name,
            dialect = dialect,
            host = parts.host,
            port = parts.port.toIntOrNull(),
            database = parts.database,
            username = username,
            password = password,
            connectionType = defaultType,
            filePath = "",
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

    /**
     * 加载所有保存的连接配置.
     * @return 连接列表 (如果文件不存在或解析失败, 返回空列表)
     *
     * 同时支持 v1 (ConnectionList) 和 v2 (PersistedConnectionList) 两种 JSON 结构。
     */
    fun load(): ConnectionList {
        val file = configFile()
        return try {
            if (!Files.exists(file)) return ConnectionList()
            val content = Files.readString(file)

            // 先尝试 v2 (PersistedConnectionList)
            val persistedV2 = runCatching {
                json.decodeFromString<PersistedConnectionList>(content)
            }.getOrNull()
            if (persistedV2 != null) {
                return ConnectionList(
                    connections = persistedV2.connections.map { it.toConnectionConfig() },
                    version = 2,
                )
            }

            // 兼容 v1 (ConnectionList) —— 旧格式含完整字段
            val oldV1 = runCatching {
                json.decodeFromString<ConnectionList>(content)
            }.getOrNull()
            if (oldV1 != null) {
                // 迁移：把旧格式写入 v2 文件，删除冗余字段
                val migrated = oldV1.connections.map { old ->
                    PersistedConnectionConfig(
                        id = old.id,
                        name = old.name,
                        dialect = old.dialect,
                        jdbcUrl = old.jdbcUrl.ifBlank {
                            buildJdbcUrl(old.dialect, old.host, old.displayPort.toString(), old.database, old.username, old.password)
                        },
                        username = old.username,
                        password = old.password,
                        createdAt = old.createdAt,
                        updatedAt = old.updatedAt,
                    ).toConnectionConfig()
                }
                savePersisted(PersistedConnectionList(connections = oldV1.connections.map { it.toPersisted() }))
                return ConnectionList(connections = migrated, version = 2)
            }

            ConnectionList()
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
            version = 2,
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
