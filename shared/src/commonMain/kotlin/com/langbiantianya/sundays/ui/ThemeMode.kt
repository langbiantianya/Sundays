/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.langbiantianya.i18n.AppLanguage
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.settings.AppSettings
import com.langbiantianya.sundays.settings.SettingsStorage

/**
 * 明暗档位 —— 主题的**第二个轴**（第一个轴是 [ThemePalette]）。
 *
 * `SYSTEM`（跟随系统）/ `LIGHT` / `DARK` 三档。**为什么不是两档开关**：只做「日夜互切」
 * 的按钮一旦点下去就再也回不到「跟随系统」，而系统切换（笔记本合盖 / 系统深色模式改设置）
 * 是真实场景 —— 用户需要一个显式的「交还控制权」档位。按钮按 `SYSTEM → LIGHT → DARK → SYSTEM` 循环。
 *
 * 与 [ThemePalette] 正交：`BLUE_GRAY + DARK` 与 `CYBERPUNK + DARK` 是两套完全不同的配色，
 * 都合法。
 *
 * ## 为什么存 [StrKey] 而不是文案本身
 *
 * 构造参数里写死中文时，切语言换不掉它 —— 翻译只改 Composable 里的字面量，
 * 这里是数据，翻译器碰不到。枚举只存键、渲染时查表，所有入口才真正跟着语言走。
 */
enum class ThemeMode(val labelKey: StrKey, val descriptionKey: StrKey) {
    SYSTEM(StrKey.THEME_FOLLOW_SYSTEM, StrKey.THEME_FOLLOW_SYSTEM_DESC),
    LIGHT(StrKey.THEME_LIGHT, StrKey.THEME_LIGHT_DESC),
    DARK(StrKey.THEME_DARK, StrKey.THEME_DARK_DESC),
    ;

    /** 下一档 —— 按钮单击走这条路径。 */
    val next: ThemeMode
        get() = when (this) {
            SYSTEM -> LIGHT
            LIGHT -> DARK
            DARK -> SYSTEM
        }

    /** 上一档。 */
    val previous: ThemeMode
        get() = when (this) {
            SYSTEM -> DARK
            LIGHT -> SYSTEM
            DARK -> LIGHT
        }

    /**
     * 解析成实际生效的明暗值。
     *
     * [isSystemDark] 必须是**组合时**的当前系统值 —— 必须在 Composable 里就地调
     * `isSystemInDarkTheme()` 取，缓存到普通字段会漏掉运行中的系统切换。
     */
    fun resolveDark(isSystemDark: Boolean): Boolean = when (this) {
        SYSTEM -> isSystemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * 外观状态 —— 由**顶层**（desktopApp `main.kt`）持有，主题与应用内所有切换入口共用。
 *
 * ## 为什么状态必须提升到 `SundaysTheme` 之外
 *
 * `SundaysTheme` 接收的是配色与明暗**参数**而非读取某个状态：注入发生在组合的最外层。
 * 若把状态放在任一屏幕内部，那个屏幕的按钮只能改自己的局部位（改不到
 * `MaterialTheme.colorScheme`），切换就会「按钮变了、界面没变」。
 *
 * 正确归属：状态在 `main.kt` 持有 → `SundaysTheme` 消费 → 各屏幕拿到回调**回写同一实例**。
 *
 * ## 为什么落盘收在这里而不是设置页
 *
 * 切换入口现在**只有一处**（设置页「个性化 → 明暗档位」）—— 面板标题行的日夜按钮已移除。
 * 入口越少漏保存越不容易发生：任何一处漏掉保存，
 * 用户就遇到「这次改了、下次启动又变回去」—— 这类 bug 极难复现。收在状态对象里只有
 * 一个写入点（[persist]），新增入口自动继承。
 */
@Stable
class AppearanceState(
    initialPalette: ThemePalette = ThemePalette.BLUE_GRAY,
    initialMode: ThemeMode = ThemeMode.SYSTEM,
    initialSystemInfoRefresh: SystemInfoRefresh = SystemInfoRefresh.OFF,
    initialCompactMode: Boolean = false,
    initialOnboardingCompleted: Boolean = true,
    initialLanguage: AppLanguage = AppLanguage.DEFAULT,
    private val onChange: (AppearanceState) -> Unit = {},
) {

    /** 当前配色主题。 */
    var palette: ThemePalette by mutableStateOf(initialPalette)
        private set

    /** 当前明暗档位。 */
    var mode: ThemeMode by mutableStateOf(initialMode)
        private set

    /** 「系统信息」分类的自动刷新间隔。 */
    var systemInfoRefresh: SystemInfoRefresh by mutableStateOf(initialSystemInfoRefresh)
        private set

    /**
     * 紧凑模式 —— 第三根正交轴（配色 / 明暗之外）。
     *
     * 与 [palette] / [mode] 的差别：它是**布尔**而非枚举，且不像前两者那样由
     * [SundaysTheme] 的参数消费，而是经 [SundaysTheme] 的 `compact` 参数转成
     * `LocalDensity` 覆盖 —— 详见 [CompactMode.kt]。
     */
    var compactMode: Boolean by mutableStateOf(initialCompactMode)
        private set

    /**
     * 首次启动引导是否已完成 —— 顶层据此决定渲染引导页还是主界面。
     *
     * ## 为什么这个字段必须在 [AppearanceState] 里
     *
     * 看起来它与外观无关、似乎该由 `main.kt` 单独持有一个布尔量。但 [persist] 的写入载荷是
     * **整份** `AppSettings`：只要外观有任何一次变更（换配色、按明暗…），就会把一份
     * `onboardingCompleted = 默认值(false)` 的设置写回磁盘，把「已完成」的事实抹掉 ——
     * 于是用户完成引导、进主界面、改一次主题，下次启动又被引导页拦一次。
     *
     * 放进本类后 [persist] 的载荷自然带上它，[completeOnboarding] 也只有唯一写入点，
     * 与本类 KDoc 里「新增入口自动继承落盘」的设计意图一致。
     */
    var onboardingCompleted: Boolean by mutableStateOf(initialOnboardingCompleted)
        private set

    /**
     * 当前界面语言。
     *
     * ## 为什么语言也住在这个叫「外观」的类里
     *
     * 因为真正的约束是 [persist] 的写入载荷是**整份** `AppSettings`：语言若由 `main.kt`
     * 单独持有，`AppearanceState` 每改一次配色就会用一份**没有语言**的 `AppSettings`
     * 覆盖磁盘，把用户的语言选择悄悄抹掉 —— 与本类 KDoc 里 `onboardingCompleted`
     * 踩过的坑是**同一个**。放进本类后载荷自然带上它。
     *
     * 至于「外观」这个名字不再精确：那只是命名上的妥协，换来的是「所有落盘状态只有
     * 一个写入点」这条真正的设计约束不被破坏。
     */
    var language: AppLanguage by mutableStateOf(initialLanguage)
        private set

    private fun persist() = onChange(this)

    fun selectPalette(target: ThemePalette) {
        if (target == palette) return
        palette = target
        persist()
    }

    fun selectMode(target: ThemeMode) {
        if (target == mode) return
        mode = target
        persist()
    }

    /** 推进明暗到下一档（面板标题行的按钮走这条）。 */
    fun cycleMode() = selectMode(mode.next)

    fun selectSystemInfoRefresh(target: SystemInfoRefresh) {
        if (target == systemInfoRefresh) return
        systemInfoRefresh = target
        persist()
    }

    /**
     * 切换紧凑模式。
     *
     * 同样走 [persist]：入口越少漏保存越不容易发生（见本类 KDoc），
     * 新增入口只要调这一个方法即自动继承落盘。
     *
     * 命名与相邻的 `select*` 一族对齐，**不能**叫 `setCompactMode` —— 那会与 `compactMode`
     * 属性由 `private set` 生成的 JVM setter `setCompactMode(Z)V` 撞签名，编译直接失败。
     */
    fun selectCompactMode(target: Boolean) {
        if (target == compactMode) return
        compactMode = target
        persist()
    }

    /**
     * 标记首次启动引导已完成。
     *
     * 单独一个方法而不是让调用方直接写属性（`private set` 挡着），是为了让「完成引导」这件事
     * 也经由 [persist] 落盘 —— 否则这次点击只在本次会话生效，下次启动引导页会重新出现。
     */
    fun completeOnboarding() {
        if (onboardingCompleted) return
        onboardingCompleted = true
        persist()
    }

    /**
     * 切换界面语言 —— 引导页与设置页共用这一个写入点。
     *
     * 立刻生效：语言只被 [ProvideStrings] 消费，而后者在组合最外层，
     * 改它等于让整棵树重组，不需要重建任何屏幕（浏览屏的 sheet 与滚动位置都不丢）。
     */
    fun selectLanguage(target: AppLanguage) {
        if (target == language) return
        language = target
        persist()
    }

    /** 当前应生效的配色 —— 明暗由 [resolvedDark] 决定。 */    fun colorScheme(resolvedDark: Boolean) = palette.schemeFor(resolvedDark)

    /**
     * 明暗在当前系统主题下的解析结果。
     *
     * ⚠️ 在 Composable 之外调用会漏掉运行中的系统明暗切换，见 [ThemeMode.resolveDark]。
     */
    @Composable
    fun resolvedDark(): Boolean = mode.resolveDark(isSystemInDarkTheme())
}

/**
 * 首次启动判定 —— 是否**跳过**引导页。
 *
 * 两个输入里**任一**为真都算「不是首次启动」：
 *
 * - [fileExists]：设置文件此前就存在。老版本用户升级上来时 [AppSettings.onboardingCompleted]
 *   这个字段必然缺失、反序列化成默认 `false`，只看字段的话**每次升级**都会被引导页拦一次。
 * - [settings].[AppSettings.onboardingCompleted]：已经完成过引导（且文件仍在）。
 *
 * 提成独立函数而不是内联表达式：这是「老用户不该被升级打扰」这条产品决策的**唯一**实现点，
 * 值得被单独断言。`fileExists` 由调用方传入而非本函数自己去读文件，
 * 于是这条判据可以脱离文件系统被测试。
 */
internal fun resolveOnboardingCompleted(settings: AppSettings, fileExists: Boolean): Boolean =
    settings.onboardingCompleted || fileExists

/**
 * 首次启动的界面语言 —— 「系统语言探测」与「读设置」的分叉点。
 *
 * ## 判据与 [resolveOnboardingCompleted] 完全一致：看**文件是否存在**，不看字段
 *
 * 老版本用户升级上来时设置文件里没有 `language` 字段，反序列化成默认值
 * （简体中文）。若只看字段，德语系统上的老用户**每次升级**都会被悄悄改成中文界面。
 * 而他们恰恰是最该留在德语的那批人 —— 是升级**逼**他们回到中文的，这不合理。
 *
 * 反过来，真正的新装机器上文件不存在，系统语言探测的结果才是对的。
 *
 * @param systemLanguage 系统区域设置探测结果。抽成参数而非就地调
 *   [AppLanguage.detectSystemLanguage]，是为了让这条规则能脱离运行环境被测试 ——
 *   否则测试只会在「这台机器恰好是什么语言」时通过。
 */
internal fun resolveInitialLanguage(
    settings: AppSettings,
    fileExists: Boolean,
    systemLanguage: AppLanguage,
): AppLanguage = if (fileExists) settings.language else systemLanguage

/**
 * [AppearanceState] 的**完整落盘载荷**。
 *
 * 与 [rememberPersistentAppearanceState] 的 `onChange` 共用同一个构造点 ——
 * 这不是洁癖：[onboardingCompleted] 一旦在这里漏掉，用户完成引导后任何一次外观变更都会把
 * 标记重置为 `false`，下次启动又回到引导页。而这种 bug 只在「改完主题 → 重启」两步之后
 * 才显形，靠读代码极难发现，只能靠一条断言把它钉住。
 */
internal fun AppearanceState.toAppSettings(): AppSettings = AppSettings(
    palette = palette,
    themeMode = mode,
    systemInfoRefresh = systemInfoRefresh,
    compactMode = compactMode,
    onboardingCompleted = onboardingCompleted,
    language = language,
)

/** 创建并 [remember] 一个 [AppearanceState]（不落盘，供测试与预览）。 */
@Composable
fun rememberAppearanceState(): AppearanceState = remember { AppearanceState() }

/**
 * 创建并 [remember] 一个**从设置文件恢复**的 [AppearanceState]，且每次变更都回写设置文件。
 *
 * 供应用入口使用（desktopApp `main.kt`）。文件 I/O 在 setter 里同步执行：一次几百字节，
 * 且只在用户主动切换时发生，不值得为此引入异步与竞态。
 */
@Composable
fun rememberPersistentAppearanceState(): AppearanceState {
    val settings = remember { SettingsStorage.load() }
    // 文件是否存在要在**读设置之前**问一次：它决定「这是不是首启」，
    // 而首启决定语言是探测来的还是读来的（见 resolveInitialLanguage）。
    val fileExists = remember { SettingsStorage.exists() }
    return remember {
        AppearanceState(
            initialPalette = settings.palette,
            initialMode = settings.themeMode,
            initialSystemInfoRefresh = settings.systemInfoRefresh,
            initialCompactMode = settings.compactMode,
            // 判据见 resolveOnboardingCompleted：老用户升级上来时字段必然缺失，
            // 只看字段会让每次升级都被引导页拦一次。
            initialOnboardingCompleted = resolveOnboardingCompleted(settings, fileExists),
            initialLanguage = resolveInitialLanguage(
                settings = settings,
                fileExists = fileExists,
                systemLanguage = AppLanguage.detectSystemLanguage(),
            ),
            onChange = { state -> SettingsStorage.save(state.toAppSettings()) },
        )
    }
}
