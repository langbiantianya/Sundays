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
import java.nio.file.attribute.PosixFilePermissions

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

    /** 凭据目录权限：`rwx------` —— 文件内含明文口令，不给 group / other 任何访问。 */
    private const val OWNER_ONLY_DIR = "rwx------"

    /** 凭据文件权限：`rw-------`。 */
    private const val OWNER_ONLY_FILE = "rw-------"

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

    /**
     * 直接写入持久化结构（迁移场景）。
     *
     * **权限**：文件里存着明文数据库口令，因此目录与文件都收紧到仅属主可读写（0700 / 0600）。
     * 默认 umask 下 `createDirectories` / `writeString` 产出的是 755 / 644 —— 也就是同机任何
     * 用户都能读到口令。同项目的 `UnixSocketIpcTransport` 早就是用 `PosixFilePermissions`
     * 收紧 socket 的，这里是同一类问题的补齐。
     *
     * 写完**再**收紧：文件已存在时 `createDirectories` 不会改权限，写入过程中也存在
     * 一小段「宽权限」窗口，收敛放在最后一步。
     *
     * ## ⚠️ 建目录与收权限**两处都要**兜底（这里曾漏掉一处，整个 Windows 上存不下来）
     *
     * 非 POSIX 文件系统（Windows）不支持 `posix:permissions`。原先只给
     * `Files.setPosixFilePermissions` 加了 `runCatching`，**建目录那一步漏了** ——
     * 而 Windows 的文件系统提供者在 `createDirectories` 传该属性时是直接抛的：
     *
     * ```
     * java.lang.UnsupportedOperationException: 'posix:permissions' not supported as initial attribute
     *     at WindowsSecurityDescriptor.fromAttribute
     *     at WindowsFileSystemProvider.createDirectory
     *     at Files.createDirectories
     *     at ConnectionStorage.savePersisted
     * ```
     *
     * 异常被下面的 `catch (e: Exception)` 吞掉、`save` 返回 `false`，于是
     * **Windows 用户永远存不下任何连接配置**，重启后列表永远是空的 ——
     * 而界面上没有任何提示，因为「存失败」这件事被完全吞掉了。
     *
     * 连带后果是 **11 条测试**全红（desktopApp 8 条 GUI + shared 3 条逻辑），
     * 症状五花八门：预置的连接在界面上找不到、JSON 文件读不出来。
     * **一个根因、十一处报错** —— 这也是为什么看到「一批不相干的用例同时红」时，
     * 该先去找那个共同的底层原因，而不是逐条去改断言。
     */
    private fun savePersisted(persisted: PersistedConnectionList): Boolean {
        return try {
            val dir = configDir()
            if (!Files.exists(dir)) {
                // ⚠️ 见文件头说明：带 POSIX 属性的建目录在 Windows 上会抛
                // UnsupportedOperationException，必须回退到不带属性的版本 ——
                // 否则**保存整个失效且静默失败**。
                runCatching {
                    Files.createDirectories(
                        dir,
                        PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString(OWNER_ONLY_DIR),
                        ),
                    )
                }.onFailure { err ->
                    Files.createDirectories(dir)
                    println(
                        "无法为 ${dir.toAbsolutePath()} 设置 POSIX 权限（当前平台不支持），" +
                            "已按平台默认权限创建: ${err.message}",
                    )
                }
            }
            val file = configFile()
            val content = json.encodeToString(persisted)
            Files.writeString(file, content)
            runCatching {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(OWNER_ONLY_FILE))
            }.onFailure { err ->
                // 非 POSIX 文件系统（Windows）没有权限属性，属预期情况
                println("无法收紧 ${file.toAbsolutePath()} 的权限（当前平台不支持 POSIX 权限）: ${err.message}")
            }
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
