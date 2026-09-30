package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 主题明暗偏好 —— `SYSTEM`（跟随系统）/ `LIGHT` / `DARK` 三档。
 *
 * **为什么是三档而不是开关**：只做「日夜互切」的按钮一旦点下去就再也回不到「跟随系统」，
 * 而系统切换（笔记本合盖 / 系统深色模式改设置）是真实场景 —— 用户需要一个显式的
 * 「交还控制权」档位。按钮按 `SYSTEM → LIGHT → DARK → SYSTEM` 循环。
 *
 * [next] 严格按上述顺序推进；[previous] 反向，供键盘绑定等场景使用。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
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
     * [isSystemDark] 必须是**组合时**的当前系统值 —— 传 0 / false 都会得到错误结果，
     * 必须在 Composable 里就地调 `isSystemInDarkTheme()` 取。
     */
    fun resolveDark(isSystemDark: Boolean): Boolean = when (this) {
        SYSTEM -> isSystemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * 主题模式状态 —— 由**顶层**（desktopApp `main.kt`）持有，主题与应用内所有切换按钮共用。
 *
 * ## 为什么状态必须提升到 `SundaysTheme` 之外
 *
 * `SundaysTheme` 接收的是 `darkTheme: Boolean` **参数**而非读取某个状态：配色的注入发生在
 * 组合的最外层。若把状态放在任一屏幕内部，那个屏幕的按钮只能改自己的局部位（改不到
 * `MaterialTheme.colorScheme`），切换就会「按钮变了、界面没变」。
 *
 * 正确归属：状态在 `main.kt` 持有 → `SundaysTheme(darkTheme = mode.resolveDark(...))`
 * 消费 → 各屏幕拿到 `onToggleTheme` 回调**回写同一个状态**。单一真相源，改一处全应用生效。
 */
@Stable
class ThemeModeState(initial: ThemeMode = ThemeMode.SYSTEM) {

    /** 当前档位。读写均触发重组 —— 因此按钮与 `SundaysTheme` 共享同一实例即可自动同步。 */
    var mode: ThemeMode by mutableStateOf(initial)
        private set

    /** 推进到下一档。 */
    fun cycle() {
        mode = mode.next
    }

    /** 直接切到指定档位。 */
    fun select(target: ThemeMode) {
        mode = target
    }
}

/** 创建并 [remember] 一个 [ThemeModeState]（默认 [ThemeMode.SYSTEM]）。 */
@Composable
fun rememberThemeModeState(): ThemeModeState = remember { ThemeModeState() }

/**
 * [ThemeModeState] 在**当前系统主题**下的解析结果。
 *
 * ⚠️ 必须在 Composable 中调用：它会读 `isSystemInDarkTheme()`，该值本身是组合作用域的状态，
 * 缓存到普通字段里会漏掉「运行中系统切换明暗」的变化。
 */
@Composable
fun ThemeModeState.isDark(): Boolean = mode.resolveDark(isSystemInDarkTheme())
