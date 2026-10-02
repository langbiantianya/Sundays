package com.kxxnzstdsw.sundays.settings

import com.kxxnzstdsw.sundays.ui.SystemInfoRefresh
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemePalette
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置持久化测试 —— `~/.config/sundays/settings.json` 的往返、容错与原子写。
 *
 * `user.home` 指向临时目录，绝不碰真实的 `~/.config/sundays/`。
 */
class SettingsStorageTest {

    private lateinit var tempHome: String
    private lateinit var originalHome: String

    private val settingsFile: java.nio.file.Path
        get() = Paths.get(tempHome, ".config", "sundays", "settings.json")

    @BeforeTest
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-settings").toFile().absolutePath
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        Files.walk(Paths.get(tempHome)).sorted(Comparator.reverseOrder()).forEach {
            Files.deleteIfExists(it)
        }
    }

    @Test
    fun missing_file_yields_defaults() {
        assertFalse(Files.exists(settingsFile), "前置条件：文件不应存在")
        assertEquals(AppSettings(), SettingsStorage.load())
    }

    @Test
    fun theme_mode_round_trips() {
        SettingsStorage.save(AppSettings(themeMode = ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, SettingsStorage.load().themeMode)

        // 逐档往返 —— 不能只有 DARK 一档能存
        ThemeMode.entries.forEach { mode ->
            SettingsStorage.save(AppSettings(themeMode = mode))
            assertEquals(mode, SettingsStorage.load().themeMode, "档位 $mode 往返失败")
        }
    }

    @Test
    fun palette_round_trips_for_every_option() {
        // 逐配色往返：新增配色若忘了加进 PersistedSettings 映射，会在这里当场暴露
        ThemePalette.entries.forEach { palette ->
            SettingsStorage.save(AppSettings(palette = palette))
            assertEquals(palette, SettingsStorage.load().palette, "配色 $palette 往返失败")
        }
    }

    @Test
    fun system_info_refresh_round_trips_for_every_option() {
        SystemInfoRefresh.entries.forEach { refresh ->
            SettingsStorage.save(AppSettings(systemInfoRefresh = refresh))
            assertEquals(refresh, SettingsStorage.load().systemInfoRefresh, "刷新档 $refresh 往返失败")
        }
    }

    @Test
    fun all_three_axes_survive_together() {
        // 三轴同时非默认 —— 抓「只存了其中一个字段」这类漏写
        val settings = AppSettings(
            palette = ThemePalette.CYBERPUNK,
            themeMode = ThemeMode.DARK,
            systemInfoRefresh = SystemInfoRefresh.S1,
        )
        SettingsStorage.save(settings)
        assertEquals(settings, SettingsStorage.load(), "三项设置必须一起往返")
    }

    @Test
    fun compact_mode_round_trips_both_ways() {
        // 两档都验：只存 true 的实现，false 分支永远没跑过，重启后可能一直紧凑
        listOf(true, false).forEach { compact ->
            SettingsStorage.save(AppSettings(compactMode = compact))
            assertEquals(compact, SettingsStorage.load().compactMode, "紧凑档 $compact 往返失败")
        }
    }

    @Test
    fun every_axis_survives_together() {
        val settings = AppSettings(
            palette = ThemePalette.WIN_XP,
            themeMode = ThemeMode.LIGHT,
            systemInfoRefresh = SystemInfoRefresh.S5,
            compactMode = true,
        )
        SettingsStorage.save(settings)
        assertEquals(settings, SettingsStorage.load(), "全部设置项必须一起往返")
    }

    @Test
    fun missing_fields_in_old_file_use_defaults() {
        Files.createDirectories(settingsFile.parent)
        // 模拟旧版文件：只有 themeMode，没有 palette / systemInfoRefresh
        Files.writeString(settingsFile, """{"version":1,"themeMode":"DARK"}""")
        val loaded = SettingsStorage.load()
        assertEquals(ThemeMode.DARK, loaded.themeMode, "已有的字段应保留")
        assertEquals(ThemePalette.BLUE_GRAY, loaded.palette, "缺失字段回落默认配色")
        assertEquals(SystemInfoRefresh.OFF, loaded.systemInfoRefresh, "缺失字段回落「关闭」")
        assertFalse(loaded.compactMode, "旧文件没有 compactMode，应回落标准密度而非紧凑")
    }

    @Test
    fun file_lands_at_expected_path_with_version() {
        SettingsStorage.save(AppSettings(themeMode = ThemeMode.LIGHT))
        assertTrue(Files.exists(settingsFile), "设置应写到 ~/.config/sundays/settings.json")
        val content = Files.readString(settingsFile)
        assertTrue(content.contains("\"version\""), "应记录格式版本，实际：$content")
        assertTrue(content.contains("LIGHT"), "应记录档位名，实际：$content")
    }

    @Test
    fun corrupt_file_falls_back_and_self_heals() {
        Files.createDirectories(settingsFile.parent)
        Files.writeString(settingsFile, "{ this is not json ")

        // 损坏时返回默认值而不是抛异常 —— 配置坏掉不应让应用起不来
        assertEquals(ThemeMode.SYSTEM, SettingsStorage.load().themeMode)

        // 且顺手重写为合法 JSON：否则每次启动都走降级分支，用户永远看不到自己改过的设置
        assertEquals(ThemeMode.SYSTEM, SettingsStorage.load().themeMode)
        val healed = Files.readString(settingsFile)
        assertTrue(!healed.contains("this is not json"), "损坏内容应被覆盖，实际：$healed")
    }

    @Test
    fun unknown_theme_mode_degrades_to_system() {
        Files.createDirectories(settingsFile.parent)
        // 模拟手改文件 / 跨版本：枚举里没有的档位名
        Files.writeString(
            settingsFile,
            """{"version":1,"themeMode":"PURPLE_DISCO_MODE"}""",
        )
        assertEquals(ThemeMode.SYSTEM, SettingsStorage.load().themeMode)
    }

    @Test
    fun unknown_fields_are_ignored() {
        Files.createDirectories(settingsFile.parent)
        Files.writeString(
            settingsFile,
            """{"version":1,"themeMode":"DARK","someRemovedField":123}""",
        )
        assertEquals(ThemeMode.DARK, SettingsStorage.load().themeMode)
    }

    @Test
    fun save_creates_missing_directory() {
        assertFalse(Files.exists(settingsFile.parent), "前置条件：配置目录不应存在")
        SettingsStorage.save(AppSettings(themeMode = ThemeMode.DARK))
        assertTrue(Files.exists(settingsFile))
    }

    @Test
    fun save_leaves_no_temp_file_behind() {
        SettingsStorage.save(AppSettings(themeMode = ThemeMode.LIGHT))
        val tmp = settingsFile.parent.resolve("settings.json.tmp")
        assertFalse(Files.exists(tmp), "原子写完成后不应残留临时文件")
    }
}
