package com.kxxnzstdsw.sundays.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 应用主题 —— 跟随系统明暗。
 *
 * 各平台入口（desktop `Window` / 未来的 Android / iOS）只负责创建平台容器，
 * 主题本身是纯 Compose 逻辑，放在 `commonMain` 供所有平台复用。
 *
 * **必须用 [Surface] 承接背景色**：Material3 的 `MaterialTheme` 只注入 colorScheme / shapes /
 * typography，**不注入** `LocalContentColor`（其默认值是 `Color.Black`）。未显式指定颜色的
 * `Text` 因此取黑色 —— 亮色主题下看不出问题，暗色主题下就是黑字贴黑底（连接列表标题、
 * 向导步骤标题、`labelLarge` 小标题等全部看不清）。由 [Surface] 统一提供
 * `background` + `onBackground` 后，所有未着色的文本在任何主题下都有正确对比度。
 *
 * @param darkTheme 是否暗色（默认跟随系统；显式传入便于测试与将来的外观设置）
 */
@Composable
fun SundaysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
