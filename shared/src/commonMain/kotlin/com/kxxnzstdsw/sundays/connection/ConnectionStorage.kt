package com.kxxnzstdsw.sundays.connection

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 连接配置持久化存储 (v2.9).
 *
 * 保存位置: `~/.config/sundays/connection.json`
 *
 * 使用 [Json] + [kotlinx.serialization] 进行 JSON 序列化/反序列化。
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
     * 加载所有保存的连接配置.
     * @return 连接列表 (如果文件不存在或解析失败, 返回空列表)
     */
    fun load(): ConnectionList {
        val file = configFile()
        return try {
            if (Files.exists(file)) {
                val content = Files.readString(file)
                json.decodeFromString<ConnectionList>(content)
            } else {
                ConnectionList()
            }
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
        return try {
            val dir = configDir()
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            }
            val file = configFile()
            val content = json.encodeToString(connectionList)
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
        val index = existing.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            existing[index] = config.copy(updatedAt = System.currentTimeMillis())
        } else {
            existing.add(config)
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
