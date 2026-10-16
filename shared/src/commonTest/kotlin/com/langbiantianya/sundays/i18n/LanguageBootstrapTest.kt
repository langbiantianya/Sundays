/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.i18n

import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.sundays.settings.AppSettings
import com.langbiantianya.sundays.settings.SettingsStorage
import com.langbiantianya.sundays.ui.AppearanceState
import com.langbiantianya.sundays.ui.ThemeMode
import com.langbiantianya.sundays.ui.ThemePalette
import com.langbiantianya.sundays.ui.resolveInitialLanguage
import com.langbiantianya.sundays.ui.toAppSettings
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 界面语言的**启动契约** —— 首启怎么定、之后怎么记、以及最要命的那条「别把用户的选择抹掉」。
 *
 * 这些断言每一条都对应一个真实会发生的退化路径。它们无法脱离组合跑，故全部放在
 * `commonTest` 而不是 `desktopApp` 的 Compose 测试里。
 */
class LanguageBootstrapTest {

    private lateinit var tempHome: String
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        tempHome = Files.createTempDirectory("sundays-language-test").toString()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
    }

    // =========================================================================
    // 首启判定
    // =========================================================================

    /**
     * 首启（设置文件不存在）时按**系统语言**。
     *
     * 这是需求的核心：「程序启动时引导界面检测系统语言默认选中系统语言」。
     * 系统语言参数化传入，因此这条断言不依赖运行测试的机器是什么 locale。
     */
    @Test
    fun `首启按系统语言`() {
        AppLanguage.entries.forEach { system ->
            assertEquals(
                system,
                resolveInitialLanguage(AppSettings(), fileExists = false, systemLanguage = system),
                "首启（无设置文件）应采用系统语言 $system",
            )
        }
    }

    /** 非首启按**落盘值**，哪怕它与系统语言不同 —— 那说明用户**主动**选过。 */
    @Test
    fun `非首启按落盘值而非系统语言`() {
        AppLanguage.entries.forEach { stored ->
            val other = AppLanguage.entries.first { it != stored }
            assertEquals(
                stored,
                resolveInitialLanguage(
                    AppSettings(language = stored),
                    fileExists = true,
                    systemLanguage = other,
                ),
                "设置文件存在时，落盘的 $stored 必须压过系统语言 $other",
            )
        }
    }

    /**
     * 老版本用户升级上来 → 简体中文，**不是**系统语言。
     *
     * 这条刻意的取舍值得钉住：老用户已经完成过引导，不会再被引导页拦下，
     * 因此没有「按系统语言重设」的机会。给他一个明确的、能在设置里改的起点，
     * 好过偷偷按机器 locale 猜 —— 那会在用户没参与的情况下改变他的界面语言。
     */
    @Test
    fun `老用户升级得到中文而不是系统语言`() {
        AppLanguage.entries
            .filter { it != AppLanguage.ZH_HANS }
            .forEach { system ->
                assertEquals(
                    AppLanguage.ZH_HANS,
                    resolveInitialLanguage(AppSettings(), fileExists = true, systemLanguage = system),
                    "设置文件已存在但缺 language 字段时，不该按系统语言重设（系统为 $system）",
                )
            }
    }

    /** 系统语言不受支持时回落中文 —— 这是 `AppLanguage.DEFAULT` 的直接后果。 */
    @Test
    fun `系统语言不受支持时首启落到中文`() {
        // `fromLocale` 的回落行为单独在 AppLanguageTest 里钉住；这里只验组合后的结果
        val unsupported = AppLanguage.fromLocale(java.util.Locale.FRANCE)
        assertEquals(AppLanguage.ZH_HANS, unsupported)
        assertEquals(
            AppLanguage.ZH_HANS,
            resolveInitialLanguage(AppSettings(), fileExists = false, systemLanguage = unsupported),
        )
    }

    // =========================================================================
    // 落盘往返
    // =========================================================================

    @Test
    fun `语言能原样往返`() {
        AppLanguage.entries.forEach { language ->
            SettingsStorage.save(AppSettings(language = language))
            assertEquals(
                language,
                SettingsStorage.load().language,
                "$language 落盘再读回应当不变",
            )
        }
    }

    /** 非法值不得让整个设置文件作废 —— 降级到默认语言，其余字段照常读出。 */
    @Test
    fun `非法语言值降级但不影响其余字段`() {
        SettingsStorage.save(AppSettings(language = AppLanguage.JA, compactMode = true))
        // 手改成非法值，模拟用户编辑 / 降级安装
        val file = Files.createDirectories(
            java.nio.file.Paths.get(tempHome, ".config", "sundays")
        ).resolve("settings.json")
        val patched = Files.readString(file)
            .replace("\"language\": \"ja\"", "\"language\": \"kl\"")
        Files.writeString(file, patched)

        val loaded = SettingsStorage.load()
        assertEquals(AppLanguage.ZH_HANS, loaded.language, "认不出来的语言应回落默认")
        assertEquals(true, loaded.compactMode, "其余字段不应因语言非法而丢失")
    }

    // =========================================================================
    // 「别把用户的选择抹掉」
    // =========================================================================

    /**
     * 完成引导后再改配色，语言**不能**被重置。
     *
     * 这是 [AppearanceState] 存在的理由（它的 KDoc 里已记着 `onboardingCompleted` 踩过同一个坑）：
     * 落盘载荷是**整份** `AppSettings`。语言若由 `main.kt` 单独持有，
     * 每改一次配色就会用一份「没有语言」的设置覆盖磁盘 —— 用户会发现自己的语言选择被无声地抹掉。
     *
     * 这里走的是**真实**的持久化路径（`onChange` 回调即 `main.kt` 传的那个），
     * 而不是手工调 [toAppSettings] 再 [SettingsStorage.save] —— 后者会绕过
     * 「每次变更都写全量载荷」这条前提，测不到真正的回归。
     */
    @Test
    fun `改配色不会抹掉已选语言`() {
        val state = AppearanceState(
            onChange = { s -> SettingsStorage.save(s.toAppSettings()) },
        )
        state.selectLanguage(AppLanguage.DE)
        assertEquals(AppLanguage.DE, SettingsStorage.load().language)

        // 改一个与语言**完全无关**的字段 —— 这正是会连带覆盖整份设置的那种变更
        state.selectPalette(ThemePalette.CYBERPUNK)
        state.selectMode(ThemeMode.DARK)
        state.selectCompactMode(true)

        assertEquals(AppLanguage.DE, SettingsStorage.load().language, "换配色/明暗/密度后语言必须还在")
        assertEquals(
            ThemePalette.CYBERPUNK,
            SettingsStorage.load().palette,
            "顺带确认其它字段确实被写进去了（否则这条断言可能是假绿）",
        )
    }

    /** 落盘载荷里必须带语言 —— 与上一条是同一件事的静态版本。 */
    @Test
    fun `落盘载荷包含语言字段`() {
        val state = AppearanceState().apply { selectLanguage(AppLanguage.RU) }
        assertEquals(AppLanguage.RU, state.toAppSettings().language)
    }

    /** 首次启动时设置文件不存在 —— 这是 resolveInitialLanguage 里 `fileExists=false` 分支的物理依据。 */
    @Test
    fun `全新环境的设置文件不存在`() {
        assertFalse(SettingsStorage.exists(), "临时 HOME 下不应存在设置文件")
    }
}
