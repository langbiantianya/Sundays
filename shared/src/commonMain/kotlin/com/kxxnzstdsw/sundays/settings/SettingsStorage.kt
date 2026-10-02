package com.kxxnzstdsw.sundays.settings

import com.kxxnzstdsw.sundays.ui.SystemInfoRefresh
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemePalette
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 应用设置持久化 —— `~/.config/sundays/settings.json`。
 *
 * 与 [com.kxxnzstdsw.sundays.connection.ConnectionStorage] 分文件：连接清单里含明文口令
 * （故 `connection.json` 是 0600），设置不含敏感信息但需要独立演进，且设置文件损坏时
 * 不应连带丢失用户的连接列表。
 *
 * **写入策略是「先写临时文件再原子替换」**（`settings.json.tmp` → `ATOMIC_MOVE`）：
 * 直接覆盖写入时若进程在写一半被杀，文件会留下截断的 JSON，下次启动设置整体读不出来。
 *
 * 读取永远降级：文件缺失 / 解析失败 / 字段非法，一律回落到默认值，绝不因配置损坏而
 * 让应用起不来。
 */
object SettingsStorage {

    /** 持久化格式版本 —— 供将来字段演进时按版本分派。 */
    const val PERSISTED_VERSION = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun configDir(): Path =
        Paths.get(System.getProperty("user.home"), ".config", "sundays")

    private fun configFile(): Path = configDir().resolve("settings.json")

    /**
     * 读取设置；文件不存在或损坏时返回**默认值**而非抛异常。
     *
     * 损坏的 JSON 会被顺手重写为默认值（自愈）—— 否则用户每次启动都会看到一个静默失效的设置。
     */
    fun load(): AppSettings {
        val file = configFile()
        if (!Files.exists(file)) return AppSettings()
        return try {
            val content = Files.readString(file)
            val parsed = json.decodeFromString<PersistedSettings>(content)
            AppSettings(
                palette = parsed.palette.toEnumOrDefault(ThemePalette.BLUE_GRAY),
                themeMode = parsed.themeMode.toEnumOrDefault(ThemeMode.SYSTEM),
                systemInfoRefresh = parsed.systemInfoRefresh.toEnumOrDefault(SystemInfoRefresh.OFF),
            )
        } catch (_: Exception) {
            // 解析失败：重写为默认值，让文件恢复成合法 JSON，避免每次启动都走降级分支
            runCatching { save(AppSettings()) }
            AppSettings()
        }
    }

    /** 原子写入设置（先写临时文件再替换）。 */
    fun save(settings: AppSettings) {
        runCatching {
            val dir = configDir()
            Files.createDirectories(dir)
            val target = configFile()
            val tmp = dir.resolve("settings.json.tmp")
            Files.writeString(
                tmp,
                json.encodeToString(
                    PersistedSettings(
                        version = PERSISTED_VERSION,
                        palette = settings.palette.name,
                        themeMode = settings.themeMode.name,
                        systemInfoRefresh = settings.systemInfoRefresh.name,
                    ),
                ),
            )
            // 移动失败（非原子移动不支持时）退化为普通替换，仍然不会留下半截文件
            runCatching { Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching {
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                .getOrThrow()
        }
    }
}

/**
 * 应用设置 —— 后续设置（编辑器字号、分页大小默认…）在此追加字段，旧文件缺字段时由
 * kotlinx.serialization 的默认值补齐（`ignoreUnknownKeys` 保证删字段也不会崩）。
 */
data class AppSettings(
    /** 配色主题轴（蓝灰 / 赛博朋克 / 哔哩粉 / Win2000 / WinXP）。 */
    val palette: ThemePalette = ThemePalette.BLUE_GRAY,
    /** 明暗轴（跟随系统 / 浅 / 深）。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 「系统信息」自动刷新间隔。 */
    val systemInfoRefresh: SystemInfoRefresh = SystemInfoRefresh.OFF,
)

/**
 * 磁盘记录 —— 与 [AppSettings] 解耦，字段一律存**枚举名字符串**。
 *
 * 解耦的意义：枚举将来加档位时，未知名字由 [enumOrDefault] 降级为默认值，而不会因为
 * 反序列化失败把**整个**设置文件作废（用户只是升级了版本，不该丢掉全部设置）。
 */
@Serializable
private data class PersistedSettings(
    val version: Int = SettingsStorage.PERSISTED_VERSION,
    val palette: String = ThemePalette.BLUE_GRAY.name,
    val themeMode: String = ThemeMode.SYSTEM.name,
    val systemInfoRefresh: String = SystemInfoRefresh.OFF.name,
)

/** 字符串 → 枚举；未知值（手改文件 / 降级安装）一律回落 [fallback]。 */
private inline fun <reified T : Enum<T>> String.toEnumOrDefault(fallback: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: fallback
