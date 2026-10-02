package com.kxxnzstdsw.sundays.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.SystemInfoRefresh
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemeModeToggleButton
import com.kxxnzstdsw.sundays.ui.ThemePalette
import com.kxxnzstdsw.sundays.ui.WinProgressIndicator
import com.kxxnzstdsw.sundays.ui.WinSwitch
import com.kxxnzstdsw.sundays.ui.selectionIndicatorColors
import com.kxxnzstdsw.sundays.ui.selectionContentColor
import com.kxxnzstdsw.sundays.ui.selectionContainerColor
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinTextButton
import com.kxxnzstdsw.sundays.ui.WinIconButton
import com.kxxnzstdsw.sundays.ui.winShape
import kotlinx.coroutines.delay

/**
 * 设置页 —— 左侧分类列表 + 右侧该分类的选项，左上角返回按钮。
 *
 * ## 模块边界：屏幕**不接触引擎**
 *
 * `shared` 没有任何 `:engine` 依赖（见 [`shared/ARCHITECTURE.md` §1.3]），因此系统信息
 * **不由本文件请求**。调用方（desktopApp）实现 [onRequestSystemInfo] 去调引擎
 * `SYSTEM.INFO`，把结果交给本文件渲染 —— 与 `ConnectionManagerScreen` 把引擎调用
 * 收进 `onTestConnection` 等回调是同一套手法。
 *
 * ## 自动刷新为什么由本文件驱动
 *
 * 定时器必须是 [LaunchedEffect]，其生命周期随组合 —— 放在这里就自动满足「离开设置页
 * 即停止轮询」「切到别的分类即停止轮询」两条要求，不需要调用方做任何清理。
 * 若把定时器放在调用方（`MainScreen` 的长生命周期 scope 上），离开页面后它会一直
 * 打引擎，得额外写「什么时候该取消」的逻辑。
 *
 * @param palette 当前配色主题
 * @param onPaletteChange 配色变更回调
 * @param themeMode 当前明暗档位
 * @param onThemeModeChange 明暗档位变更回调
 * @param systemInfoRefresh 系统信息自动刷新间隔
 * @param onSystemInfoRefreshChange 刷新间隔变更回调
 * @param compactMode 紧凑模式（控件尺寸整体缩小）
 * @param onCompactModeChange 紧凑模式变更回调
 * @param systemInfo 系统信息状态（loading / 成功 / 失败）
 * @param onRequestSystemInfo 请求系统信息；切到 [SettingsCategory.SYSTEM_INFO] 或点「刷新」、
 *   以及自动刷新到点时触发
 * @param onBack 返回上一屏（左上角按钮）
 */
@Composable
fun SettingsScreen(
    palette: ThemePalette,
    onPaletteChange: (ThemePalette) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    systemInfoRefresh: SystemInfoRefresh,
    onSystemInfoRefreshChange: (SystemInfoRefresh) -> Unit,
    systemInfo: SystemInfoState,
    onRequestSystemInfo: () -> Unit,
    onBack: () -> Unit,
    compactMode: Boolean = false,
    onCompactModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var category by remember { mutableStateOf(SettingsCategory.PERSONALIZATION) }

    // 自动刷新：仅在「系统信息」分类下、且间隔非「关闭」时运行。
    // 用 rememberUpdatedState 包住回调 —— 调用方传的 lambda 每次重组都可能是新实例，
    // 直接把它放进 LaunchedEffect 的 key 会让定时器每次重组都被重启（永远等不到下一次触发）。
    val requestInfo by rememberUpdatedState(onRequestSystemInfo)
    LaunchedEffect(category, systemInfoRefresh) {
        val interval = systemInfoRefresh.interval ?: return@LaunchedEffect
        if (category != SettingsCategory.SYSTEM_INFO) return@LaunchedEffect
        while (true) {
            delay(interval)
            requestInfo()
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // 左侧分类 —— 与 ConnectionManagerScreen 的左栏同款配色（surfaceVariant）
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 左上角返回
            Row(verticalAlignment = Alignment.CenterVertically) {
                WinIconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = "设置", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))

            SettingsCategory.entries.forEach { entry ->
                SettingsCategoryRow(
                    category = entry,
                    selected = entry == category,
                    onClick = {
                        category = entry
                        // 切到系统信息时按需拉取：已经成功加载过就不重复请求
                        // （避免每次切回来都打一次引擎）
                        if (entry == SettingsCategory.SYSTEM_INFO && !systemInfo.loaded) {
                            onRequestSystemInfo()
                        }
                    },
                )
            }
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 右侧内容
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (category) {
                SettingsCategory.PERSONALIZATION -> PersonalizationPane(
                    palette = palette,
                    onPaletteChange = onPaletteChange,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    compactMode = compactMode,
                    onCompactModeChange = onCompactModeChange,
                )
                SettingsCategory.SYSTEM_INFO -> SystemInfoPane(
                    state = systemInfo,
                    refresh = systemInfoRefresh,
                    onRefreshChange = onSystemInfoRefreshChange,
                    onRefreshNow = onRequestSystemInfo,
                )
            }
        }
    }
}

/** 左侧单个分类行。 */
@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = winShape(6.dp),
        contentColor = selectionContentColor(selected, MaterialTheme.colorScheme.onSurfaceVariant),
        color = selectionContainerColor(selected, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = category.icon(),
                contentDescription = null,
                tint = selectionContentColor(selected, MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = category.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = selectionContentColor(selected, MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.PERSONALIZATION -> Icons.Filled.Palette
    SettingsCategory.SYSTEM_INFO -> Icons.Filled.Computer
}

/**
 * 「个性化」内容 —— 配色主题 + 明暗档位（两个正交的单选组）+ 紧凑模式（一个开关）。
 *
 * 拆成两组而不是合成一个列表：配色与明暗是两件独立的事，`蓝灰 + 深色` 与
 * `赛博朋克 + 深色` 是不同外观，「赛博朋克 + 浅色」同样合法。紧凑模式是**尺度**轴，
 * 与前两者都正交，故单列为一个开关而非并入任何一组单选。
 */
@Composable
private fun PersonalizationPane(
    palette: ThemePalette,
    onPaletteChange: (ThemePalette) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    compactMode: Boolean,
    onCompactModeChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(text = "个性化", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "外观设置立即生效，并在下次启动时保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Text(text = "配色主题", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ThemePalette.entries.forEach { option ->
            ChoiceRow(
                title = option.label,
                description = option.description,
                selected = option == palette,
                onClick = { onPaletteChange(option) },
            )
        }

        Spacer(Modifier.height(24.dp))
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Text(text = "明暗模式", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ThemeMode.entries.forEach { option ->
            ChoiceRow(
                title = option.label,
                description = option.description,
                selected = option == themeMode,
                onClick = { onThemeModeChange(option) },
            )
        }

        Spacer(Modifier.height(24.dp))
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        // 快捷切换（明暗三档循环）—— 面板标题行的按钮已移除，这里是**唯一**的切换入口
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "快速切换明暗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            ThemeModeToggleButton(mode = themeMode, onCycle = { onThemeModeChange(themeMode.next) })
            Spacer(Modifier.width(8.dp))
            Text(
                text = "下一档：${themeMode.next.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Text(text = "界面密度", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // 立即生效：AppearanceState 变更 → SundaysTheme 的 compact 参数 → LocalDensity 覆盖，
        // 整棵树重组，**不需要**重启或重建当前屏幕。
        SwitchRow(
            title = "紧凑模式",
            description = "缩小控件与行距，同屏显示更多内容。",
            checked = compactMode,
            onCheckedChange = onCompactModeChange,
        )
    }
}

/**
 * 开关行（标题 + 说明 + 右侧开关）—— 布尔项的标准形态。
 *
 * 整行可点：与本文件的 [ChoiceRow] 同一手法（单选点自己挂 `onClick`，外层 Row 挂
 * `clickable`）。点击开关本身由 `Switch` 消费掉事件，不会与外层重复触发一次。
 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        WinSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 「系统信息」内容 —— 展示引擎 `SYSTEM.INFO` 的结果 + 自动刷新间隔。 */
@Composable
private fun SystemInfoPane(
    state: SystemInfoState,
    refresh: SystemInfoRefresh,
    onRefreshChange: (SystemInfoRefresh) -> Unit,
    onRefreshNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "系统信息", style = MaterialTheme.typography.titleLarge)
            WinTextButton(onClick = onRefreshNow, shape = SundaysPalette.buttonShape) { Text("刷新") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "来自引擎的运行时信息（SYSTEM.INFO）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(text = "自动刷新", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // 间隔档位用横向一组 chip：竖排 5 项会把数据挤到屏幕外，且这是一次性选择
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SystemInfoRefresh.entries.forEach { option ->
                RefreshOptionChip(
                    label = option.label,
                    selected = option == refresh,
                    onClick = { onRefreshChange(option) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // 按 sealed 子类型分派而非「取字段再判空」：sealed interface 的属性无法 smart-cast。
        when (val s = state) {
            SystemInfoState.Idle -> Text(
                text = "（尚未加载）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SystemInfoState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WinProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "读取系统信息…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is SystemInfoState.Failed -> Text(
                text = "读取失败：${s.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is SystemInfoState.Loaded -> {
                val info = s.info
                InfoRow("JVM 版本", info.jvmVersion)
                InfoRow("JVM 供应商", info.jvmVendor)
                InfoRow("JVM 名称", info.jvmName)
                WinDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                InfoRow("操作系统", "${info.osName} ${info.osVersion}".trim())
                InfoRow("系统架构", info.osArch)
                InfoRow("可用处理器", "${info.availableProcessors} 个")
                WinDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                InfoRow("JVM 堆已用", formatBytes(info.memoryUsed))
                InfoRow("JVM 堆已分配", formatBytes(info.memoryTotal))
                InfoRow("JVM 堆上限", formatBytes(info.memoryMax))
                InfoRow("JVM 堆空闲", formatBytes(info.memoryFree))
                WinDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                InfoRow("运行时长", formatDuration(info.uptimeMillis))
                InfoRow("进程 PID", "${info.pid}")
            }
        }
    }
}

/** 单选行（标题 + 说明 + 单选圆点）。 */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(colors = selectionIndicatorColors(), selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 刷新间隔选项（横向 chip）。 */
@Composable
private fun RefreshOptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = winShape(6.dp),
        contentColor = selectionContentColor(selected, MaterialTheme.colorScheme.onSurfaceVariant),
        color = selectionContainerColor(selected, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 字节 → 人类可读（固定单位，便于纵向对齐比较）。
 *
 * `public` 而非 `internal`：浏览屏底部状态栏点击后弹出的内存详情面板**也**用它
 * （见 desktopApp 的 `EngineMemoryStatusBar`）—— 同一个数字必须在设置页与状态栏
 * 弹窗里显示成同一个字符串。两处各写一个格式化器的话，迟早会漂移成
 * 「设置页 512.0 MB、状态栏 512M」，用户会以为其中一处算错了。
 *
 * 紧凑状态栏**那一行**另有 `formatHeapBytes`（`512M` / `2G`），那是另一种呈现密度，
 * 不归本函数管 —— 见该函数的 KDoc。
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 毫秒 → `3 天 4 小时 5 分`（只到分，秒级噪声对运行时长没意义）。 */
internal fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("${days} 天 ")
        if (days > 0 || hours > 0) append("${hours} 小时 ")
        append("${minutes} 分")
    }
}
