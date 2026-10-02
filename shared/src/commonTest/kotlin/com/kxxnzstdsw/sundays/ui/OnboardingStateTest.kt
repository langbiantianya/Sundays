package com.kxxnzstdsw.sundays.ui

import com.kxxnzstdsw.sundays.settings.AppSettings
import com.kxxnzstdsw.sundays.settings.SettingsStorage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 首次启动引导的**落盘契约** —— 判据、载荷、以及「老用户不该被升级打扰」。
 *
 * 这里的每条断言都对应一个真实踩过的坑形态：引导页的逻辑总共只有两处能出错 ——
 * 「什么时候该显示」和「完成后有没有被真的记住」。后者尤其阴险，因为它只在
 * 「完成引导 → 改一次主题 → 重启」这三步之后才显形。
 *
 * 界面部分（点选是否真的回调）由 desktopApp 的 `OnboardingScreenTest` 覆盖；
 * 本文件只钉**不依赖组合**的那一半，因此可以在 commonTest 里裸跑。
 */
class OnboardingStateTest {

    private lateinit var tempHome: String
    private lateinit var originalHome: String

    @BeforeTest
    fun setUp() {
        // SettingsStorage 的落盘位置是 `user.home/.config/sundays`，测试必须把它指向临时目录，
        // 否则会污染真实用户设置。
        tempHome = Files.createTempDirectory("sundays-onboarding-test").toString()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
    }

    // =========================================================================
    // 何时该显示引导
    // =========================================================================

    @Test
    fun a_machine_with_no_settings_file_is_a_first_run() {
        assertFalse(
            resolveOnboardingCompleted(AppSettings(), fileExists = false),
            "设置文件不存在 = 真的第一次启动，应当显示引导页",
        )
    }

    @Test
    fun a_machine_that_already_has_a_settings_file_is_not_disturbed() {
        // **老用户升级上来**的场景：那时磁盘上的 JSON 里根本没有 onboardingCompleted 字段，
        // 反序列化补成默认 false。若只认这个字段，每次升级都会被引导页拦一次 ——
        // 那是纯粹的骚扰，而且用户没有任何办法关掉它。
        assertTrue(
            resolveOnboardingCompleted(AppSettings(), fileExists = true),
            "设置文件已存在 = 老用户，不该被引导页拦下",
        )
    }

    @Test
    fun a_completed_onboarding_stays_completed() {
        // 删掉设置文件后重新判定（用户手动重置配置）仍应尊重已完成的标记
        assertTrue(
            resolveOnboardingCompleted(AppSettings(onboardingCompleted = true), fileExists = false),
            "字段已为真时不应再显示引导页",
        )
    }

    // =========================================================================
    // 完成后有没有被记住
    // =========================================================================

    @Test
    fun completing_the_onboarding_is_persisted() {
        SettingsStorage.save(AppSettings(onboardingCompleted = true))
        assertTrue(
            SettingsStorage.load().onboardingCompleted,
            "完成引导必须落盘，否则下次启动引导页会重新出现",
        )
    }

    @Test
    fun the_file_appears_only_after_the_first_save() {
        // exists() 是「老用户不被打扰」那条判据的输入，它本身必须可靠
        assertFalse(SettingsStorage.exists(), "尚未写入任何设置时文件不应存在")
        SettingsStorage.save(AppSettings())
        assertTrue(SettingsStorage.exists(), "save 之后文件应存在")
    }

    @Test
    fun an_existing_file_without_the_field_is_still_treated_as_existing() {
        // 模拟老版本写出的 JSON：字段缺失。`exists()` 只看文件在不在，不解析内容，
        // 因此损坏 / 缺字段都判为「已配置过」—— 这正是我们要的（别拿升级当首启）。
        SettingsStorage.save(AppSettings())
        val file = Files.createDirectories(
            java.nio.file.Paths.get(tempHome, ".config", "sundays"),
        ).resolve("settings.json")
        Files.writeString(file, """{"version":1,"palette":"BLUE_GRAY","themeMode":"SYSTEM"}""")

        assertTrue(SettingsStorage.exists(), "缺字段的老文件仍算「文件已存在」")
        // 注意本函数返回的是「**跳过**引导」，不是「要引导」
        assertTrue(
            resolveOnboardingCompleted(SettingsStorage.load(), fileExists = SettingsStorage.exists()),
            "老用户加载后不该被要求走引导（字段缺失必须由「文件已存在」兜住）",
        )
    }

    // =========================================================================
    // 载荷：外观变更不得抹掉「已完成」
    // =========================================================================

    @Test
    fun changing_the_appearance_does_not_reset_the_onboarding_flag() {
        // **本文件最重要的一条**。AppearanceState 的落盘载荷是整份 AppSettings：
        // 只要 payload 里漏了 onboardingCompleted，用户完成引导后在设置页换一次配色，
        // 磁盘上的标记就被重置为 false，下次启动又被引导页拦下 ——
        // 而这个 bug 要「完成 → 改主题 → 重启」三步才显形，靠读代码几乎发现不了。
        val state = AppearanceState(initialOnboardingCompleted = false)
        state.completeOnboarding()
        assertTrue(state.toAppSettings().onboardingCompleted, "完成后载荷里应带 true")

        // 触发一次外观变更，走的正是 onChange → toAppSettings 那条路
        state.selectPalette(ThemePalette.CYBERPUNK)
        state.selectMode(ThemeMode.DARK)
        state.selectCompactMode(true)

        val payload = state.toAppSettings()
        assertTrue(payload.onboardingCompleted, "换主题 / 明暗 / 密度都不该抹掉「已完成」")
        // 顺带确认其余字段确实跟着变了 —— 防止「为了保住标记干脆不落盘」
        assertEquals(ThemePalette.CYBERPUNK, payload.palette)
        assertEquals(ThemeMode.DARK, payload.themeMode)
        assertTrue(payload.compactMode)
    }

    @Test
    fun completing_twice_is_idempotent() {
        var persistCount = 0
        val state = AppearanceState(
            initialOnboardingCompleted = false,
            onChange = { persistCount++ },
        )
        state.completeOnboarding()
        val afterFirst = persistCount
        state.completeOnboarding()

        assertEquals(afterFirst, persistCount, "重复完成不应重复写盘")
        assertTrue(state.onboardingCompleted)
    }

    @Test
    fun a_default_appearance_state_does_not_gate_the_app() {
        // 默认值取 true =「不引导」：测试、预览、任何没显式指定初始值的调用方
        // 都不该突然被引导页拦住。只有应用入口那条真正读盘的路径才可能给出 false。
        assertTrue(AppearanceState().onboardingCompleted)
    }
}
