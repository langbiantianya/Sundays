package com.kxxnzstdsw.sundays.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 日夜 / 系统主题切换按钮 —— 放在各面板的标题行里。
 *
 * ## 图标语义：**显示「点下去会变成什么」**，不是「当前是什么」
 *
 * 这是本按钮唯一容易做错的地方。用户在亮色界面看到月亮图标，合理预期是「点了会变暗」；
 * 若把图标做成「指示当前状态」（亮色时显示太阳），用户点之前就要反过来解读一次。
 * 因此图标取 **`mode.next`**（见 [icon]），按下按钮后落到的档位就是屏幕上显示的图标：
 *
 * | 当前档位 | 显示图标（= 下一档的图标） | 按钮描述 | 点下去 |
 * |---|---|---|---|
 * | [ThemeMode.SYSTEM] | ☀ `LightMode` | 主题：跟随系统，切换为浅色 | [ThemeMode.LIGHT] |
 * | [ThemeMode.LIGHT] | 🌙 `NightsStay` | 主题：浅色，切换为深色 | [ThemeMode.DARK] |
 * | [ThemeMode.DARK] | 🔆 `BrightnessAuto` | 主题：深色，切换为跟随系统 | [ThemeMode.SYSTEM] |
 *
 * 三个图标两两可区分：☀ = 将变浅色、🌙 = 将变深色、🔆 = 将交还系统。
 * 「跟随系统」刻意**不用**太阳：太阳已代表「浅色」，复用会让 SYSTEM 与 LIGHT 两档撞脸。
 *
 * **无障碍**：`IconButton` 自身没有 `contentDescription` 参数（它只是容器），语义由内层 `Icon`
 * 的 `contentDescription` 提供，且 `IconButton` 会合并子节点语义 —— 读屏会念
 * 「主题：深色，切换为跟随系统，按钮」。若给图标 `null` 再另找地方挂文案，读屏就只剩「按钮」，
 * 完全不知道这个按钮管什么。
 *
 * @param mode 当前主题档位
 * @param onCycle 点击回调 —— 调用方回写**顶层**的 [ThemeModeState]，见该类 KDoc
 */
@Composable
fun ThemeModeToggleButton(
    mode: ThemeMode,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onCycle,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = mode.next.icon(),
            contentDescription = mode.label(),
        )
    }
}

/**
 * 目标档位对应的图标。
 *
 * 注意这是**目标**（`mode.next`）的图标，不是当前状态的 —— 调用方传 `mode.next` 进来。
 * 三个目标必须视觉可区分：☀ 浅色 / 🌙 深色 / 🔆 交给系统。
 * 跟随系统曾用 `WbSunny`（太阳），与浅色档的 `LightMode` 撞脸，两档看上去一样；
 * 改用 `BrightnessAuto`（亮度自动）表达「由系统决定」这一层语义。
 */
private fun ThemeMode.icon() = when (this) {
    ThemeMode.LIGHT -> Icons.Filled.LightMode
    ThemeMode.DARK -> Icons.Filled.NightsStay
    ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
}

/** 当前档位的一句话描述 —— 按钮的无障碍名称，按「点下去会变成什么」措辞。 */
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "主题：跟随系统，切换为浅色"
    ThemeMode.LIGHT -> "主题：浅色，切换为深色"
    ThemeMode.DARK -> "主题：深色，切换为跟随系统"
}

/**
 * 设置入口按钮（⚙）—— 连接管理与数据库浏览两个面板标题行各放一个。
 *
 * 放在内容区之外的标题行里，用户在任何页面都能进入设置页。
 *
 * ⚠️ 同文件里的 [ThemeModeToggleButton]（日夜切换）**已不在任何面板标题行使用** ——
 * 明暗改到设置页「个性化 → 明暗档位」单选组，该按钮目前只被设置页引用。
 */
@Composable
fun SettingsEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "设置",
        )
    }
}
