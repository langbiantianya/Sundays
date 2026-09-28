package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 应用主题 —— 跟随系统明暗。
 *
 * 各平台入口（desktop `Window` / 未来的 Android / iOS）只负责创建平台容器，
 * 主题本身是纯 Compose 逻辑，放在 `commonMain` 供所有平台复用。
 */
@Composable
fun SundaysTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
