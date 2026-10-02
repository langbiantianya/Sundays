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
     * 设置文件**此前是否存在** —— 「是否首次启动」的判据。
     *
     * 为什么不直接看 `AppSettings.onboardingCompleted`：老版本用户升级上来时该字段必然缺失、
     * 反序列化成默认 `false`，于是每次升级都会被引导页拦一次。引导只该出现在**真的没配置过**
     * 的机器上，所以判据必须是「文件存不存在」而不是「某个字段是不是默认值」。
     *
     * 也因此这个判断**只在 [rememberPersistentAppearanceState] 的 `remember` 里问一次**：
     * 引导一旦完成，字段与文件同时为真，之后再问多少次都还是同一个答案。
     */
    fun exists(): Boolean = Files.exists(configFile())

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
                compactMode = parsed.compactMode,
                onboardingCompleted = parsed.onboardingCompleted,
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
                        compactMode = settings.compactMode,
                        onboardingCompleted = settings.onboardingCompleted,
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
    /**
     * 紧凑模式 —— 控件尺寸整体缩小（桌面密度）。默认 `false`（沿用 M3 出厂尺度）。
     *
     * 与前三项不同，它是 `Boolean` 而非枚举：没有「未知档位要降级」的语义，
     * 旧文件缺该字段时由 kotlinx.serialization 补 `false` 即可。
     */
    val compactMode: Boolean = false,
    /**
     * 首次启动引导是否已完成 —— 为 `false` 时应用渲染引导页而不是主界面。
     *
     * 默认 `false`（= 要引导）而不是 `true`：这是个**默认值即功能**的开关，
     * 只有显式完成引导后才会变真。
     *
     * ⚠️ 老版本用户升级上来时该字段一定缺失、必然是 `false`，若直接拿它当「首次启动」的判据，
     * 每次升级都会被拦一次。故真正的判据是 [SettingsStorage.exists]（见其 KDoc）。
     */
    val onboardingCompleted: Boolean = false,
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
    val compactMode: Boolean = false,
    val onboardingCompleted: Boolean = false,
)

/** 字符串 → 枚举；未知值（手改文件 / 降级安装）一律回落 [fallback]。 */
private inline fun <reified T : Enum<T>> String.toEnumOrDefault(fallback: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: fallback
