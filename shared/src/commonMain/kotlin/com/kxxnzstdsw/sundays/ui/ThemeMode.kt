package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kxxnzstdsw.sundays.settings.AppSettings
import com.kxxnzstdsw.sundays.settings.SettingsStorage

/**
 * 明暗档位 —— 主题的**第二个轴**（第一个轴是 [ThemePalette]）。
 *
 * `SYSTEM`（跟随系统）/ `LIGHT` / `DARK` 三档。**为什么不是两档开关**：只做「日夜互切」
 * 的按钮一旦点下去就再也回不到「跟随系统」，而系统切换（笔记本合盖 / 系统深色模式改设置）
 * 是真实场景 —— 用户需要一个显式的「交还控制权」档位。按钮按 `SYSTEM → LIGHT → DARK → SYSTEM` 循环。
 *
 * 与 [ThemePalette] 正交：`BLUE_GRAY + DARK` 与 `CYBERPUNK + DARK` 是两套完全不同的配色，
 * 都合法。
 */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("跟随系统", "由操作系统的明暗设置决定"),
    LIGHT("浅色", "始终使用浅色配色"),
    DARK("深色", "始终使用深色配色"),
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
 * 切换入口有**四处**（两个面板标题行、设置页、未来的快捷键）。任何一处漏掉保存，
 * 用户就遇到「这次改了、下次启动又变回去」—— 这类 bug 极难复现。收在状态对象里只有
 * 一个写入点（[persist]），新增入口自动继承。
 */
@Stable
class AppearanceState(
    initialPalette: ThemePalette = ThemePalette.BLUE_GRAY,
    initialMode: ThemeMode = ThemeMode.SYSTEM,
    initialSystemInfoRefresh: SystemInfoRefresh = SystemInfoRefresh.OFF,
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

    /** 当前应生效的配色 —— 明暗由 [resolvedDark] 决定。 */
    fun colorScheme(resolvedDark: Boolean) = palette.schemeFor(resolvedDark)

    /**
     * 明暗在当前系统主题下的解析结果。
     *
     * ⚠️ 在 Composable 之外调用会漏掉运行中的系统明暗切换，见 [ThemeMode.resolveDark]。
     */
    @Composable
    fun resolvedDark(): Boolean = mode.resolveDark(isSystemInDarkTheme())
}

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
    return remember {
        AppearanceState(
            initialPalette = settings.palette,
            initialMode = settings.themeMode,
            initialSystemInfoRefresh = settings.systemInfoRefresh,
            onChange = { state ->
                SettingsStorage.save(
                    AppSettings(
                        palette = state.palette,
                        themeMode = state.mode,
                        systemInfoRefresh = state.systemInfoRefresh,
                    )
                )
            },
        )
    }
}
