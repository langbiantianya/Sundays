package com.kxxnzstdsw.sundays.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.ThemeMode
import com.kxxnzstdsw.sundays.ui.ThemePalette
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.WinDivider
import com.kxxnzstdsw.sundays.ui.WinSwitch
import com.kxxnzstdsw.sundays.ui.selectionContainerColor
import com.kxxnzstdsw.sundays.ui.selectionContentColor
import com.kxxnzstdsw.sundays.ui.selectionIndicatorColors
import com.kxxnzstdsw.sundays.ui.winShape

/**
 * 首次启动引导页 —— 让用户在**进主界面之前**就挑好配色 / 明暗 / 界面密度。
 *
 * ## 为什么要单独一页，而不是把人直接丢进设置页
 *
 * 设置页的「个性化」是与「系统信息」并列的一个**分类**，用户完全可以一路不点开就走。
 * 但外观是纯粹的主观偏好、且**在第一次看到界面时就已经产生判断了** —— 让人先看一屏默认
 * 配色、再去某个二级分类里找「换个样子」，是把最该被引导的决策藏得最深。
 *
 * ## 这页最大的特点：预览是免费的
 *
 * 它渲染在 `SundaysTheme` **内部**，因此每一次点选都会立刻重绘整页 —— 配色、明暗、控件尺寸
 * 全部即时生效。不需要做「预览图」「应用」按钮那套东西，本页自己就是预览。
 *
 * ## 三组控件为什么是这个顺序
 *
 * 配色 → 明暗 → 密度，与设置页一致，也与「视觉冲击由强到弱」一致：
 * 换配色整屏变色（最显眼），换明暗次之，密度只缩 15%（最不易察觉，放最后当收尾）。
 *
 * @param palette 当前配色主题
 * @param onPaletteChange 配色变更（立即生效并落盘）
 * @param themeMode 当前明暗档位
 * @param onThemeModeChange 明暗档位变更
 * @param dark 当前**实际生效**的明暗（供色卡取对应明暗档的配色，见 [PaletteCard]）
 * @param compactMode 紧凑模式
 * @param onCompactModeChange 紧凑模式变更
 * @param onFinish 「开始使用」—— 首次启动时同时把引导标记为已完成并落盘
 * @param firstRun 是否为**首次启动**。默认 `true`；由设置页主动重进时传 `false` ——
 *   「欢迎使用 / 先挑一套顺手的界面」是**首启**的话术，用户在设置里点「重新打开引导」时
 *   再看到它会以为是应用重置了。同一个参数还把收尾按钮从「开始使用」改成「完成」，
 *   避免在主界面里说「开始使用」。
 */
@Composable
fun OnboardingScreen(
    palette: ThemePalette,
    onPaletteChange: (ThemePalette) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dark: Boolean,
    compactMode: Boolean,
    onCompactModeChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 内容区可滚，但**「开始使用」不能放在这里**。三组控件在 1024×768 下约 900px 高，
            // 按钮会被推到视口外（实测语义 bounds 直接是 Rect(0,0,0,0)）—— 一屏之内的引导
            // 却要滚动才够得着唯一的出口，用户很可能直接当成「没有下一步」而卡住。
            // 故底部收尾条固定在滚动区**外面**。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 限宽并居中：窗口默认就有 1280px 宽，不限的话内容会被拉到左右两端，
                // 单行文字拉得太长反而难读。960dp 刚好容纳 5 张 180dp 色卡 + 间距排成一行
                // （排成一行能把配色段从两行压到一行，省下约 130px 高度）。
                Column(
                    modifier = Modifier.widthIn(max = 960.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (firstRun) "欢迎使用 sundays" else "外观引导",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (firstRun) {
                            "先挑一套顺手的界面。每一项都立即生效，之后可在「设置 → 个性化」随时改。"
                        } else {
                            "和首次启动时一样，改动立即生效并落盘。改完点「完成」回到设置页。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(24.dp))

                    // ---- 配色主题 ----
                    SectionTitle("配色主题")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "色卡是各主题在当前明暗下的真实取色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ThemePalette.entries.forEach { option ->
                            PaletteCard(
                                option = option,
                                dark = dark,
                                selected = option == palette,
                                onClick = { onPaletteChange(option) },
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(18.dp))

                    // ---- 明暗模式 ----
                    SectionTitle("明暗模式")
                    Spacer(Modifier.height(10.dp))
                    ThemeMode.entries.forEach { option ->
                        ModeOption(
                            option = option,
                            selected = option == themeMode,
                            onClick = { onThemeModeChange(option) },
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(18.dp))

                    // ---- 界面密度 ----
                    SectionTitle("界面密度")
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 整行可点，与设置页的开关行同一手法
                            .clickable { onCompactModeChange(!compactMode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "紧凑模式",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "缩小控件与行距，同屏显示更多内容。开关拨动后本页也会跟着缩放。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        WinSwitch(checked = compactMode, onCheckedChange = onCompactModeChange)
                    }
                }
            }

            // ---- 固定在底部的收尾条 ----
            // 与内容区分离，保证「开始使用」在任何窗口高度 / 紧凑档下都**始终可见**。
            // 它同时兼任「跳过」：默认值本身就是一份合法答案，不必单列一个「跳过」按钮
            // （两个按钮反而会让「跳过到底跳到哪」变模糊）。
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                WinButton(
                    onClick = onFinish,
                    shape = SundaysPalette.buttonShape,
                    modifier = Modifier
                        .widthIn(max = 960.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = if (firstRun) "开始使用" else "完成",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 分组标题 —— 三个分组共用，避免各自重复一遍字号与间距。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 一张配色卡片 —— 取色卡 + 名称 + 说明。
 *
 * ## 色卡为什么不能硬编码颜色
 *
 * 卡片要展示的是**该主题自己**的配色。若写死几个示意色，用户在浅色下选赛博朋克，
 * 看到的是一张不代表任何东西的图；而 [ThemePalette.schemeFor] 能给出该主题在**当前明暗**
 * 下的真实 `ColorScheme`，取 `surface` / `primary` / `onSurface` 三色即可。
 *
 * ## [dark] 必须由调用方传入
 *
 * 不能就地取 `MaterialTheme.colorScheme` 反推明暗 —— 那是**已选中**主题的配色，
 * 恰恰是待比较的**其它**主题要超越的对象，拿它反推会得到错误答案。
 *
 * @param dark 当前实际生效的深浅
 */
@Composable
private fun PaletteCard(
    option: ThemePalette,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = option.schemeFor(dark)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            // 180dp × 5 + 4 × 10dp 间距 = 940dp，在 960dp 的内容宽里排成一行
            .width(180.dp)
            .clip(winShape(6.dp))
            .background(selectionContainerColor(selected, MaterialTheme.colorScheme.surface))
            .border(if (selected) 2.dp else 1.dp, borderColor, winShape(6.dp))
            // 必须是 `selectable` 而不是 `clickable(role = Role.RadioButton)`：
            // 后者只设 role、**不设** `selected` 语义，读屏会念「单选按钮」却永远不告诉用户
            // 「这一个选中了没有」—— 那比不给 role 更糟（它明确声称自己是单选，却缺了单选最关键的
            // 那个状态）。`selectable` 同时给出 role 与 selected，并顺带获得键盘焦点。
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(10.dp),
    ) {
        // 三色取色条：用该主题**真实**的 surface / primary / onSurface
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            ColorStrip(scheme.surface, Modifier.weight(2f))
            ColorStrip(scheme.primary, Modifier.weight(1f))
            ColorStrip(scheme.onSurface, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = selectionContentColor(selected, MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    // 选中态已由 Role=RadioButton 的 selected 语义 + 边框表达，不重复朗读
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = option.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 2 行封顶：说明文字再长也不该把色卡撑得高低不齐
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ColorStrip(color: Color, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().background(color))
}

/** 一个明暗档位选项（单选行）。 */
@Composable
private fun ModeOption(
    option: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点的便利区域；**选中语义由内部 RadioButton 承载**，
            // 故这里用普通 clickable 而不是 selectable —— 再加一个 RadioButton 角色
            // 会让读屏在同一个选项上念出两个「单选按钮」。
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(colors = selectionIndicatorColors(), selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = selectionContentColor(selected, MaterialTheme.colorScheme.onSurface),
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
