package com.kxxnzstdsw.sundays.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 顶层导航条 —— 切换 [AppDestination].
 *
 * 设计要点:
 * - 横向 `Row`, 左对齐, 每个目标是一个 chip; 当前目标高亮.
 * - 纯展示: 选中态由 [current] 传入, 点击经 [onSelect] 回抛 —— 导航状态由调用方的顶层屏幕持有,
 *   目标屏幕的 `remember` 状态因此可跨导航切换保留。
 * - [destinations] 控制哪些目标渲染 chip —— 调用方可隐藏已不可达的入口
 *   （如连接管理是首屏，进了数据库浏览后不再返回连接管理，则不显示对应 chip）。
 *   空列表时整个条不渲染。
 */
@Composable
fun TopNavBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
    destinations: List<AppDestination> = AppDestination.entries,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            destinations.forEach { dest ->
                NavChip(
                    destination = dest,
                    selected = current == dest,
                    onClick = { onSelect(dest) },
                )
            }
        }
    }
}

@Composable
private fun NavChip(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon: ImageVector = when (destination) {
        AppDestination.CONNECTIONS -> Icons.Filled.Storage
        AppDestination.DATABASE -> Icons.Filled.TableChart
    }
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        color = bg.copy(alpha = if (selected) 1f else 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg)
            Text(
                text = destination.label,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
