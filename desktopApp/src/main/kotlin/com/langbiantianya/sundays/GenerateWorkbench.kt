/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

// **造数工作台** —— 生成 Lua 造数脚本、执行、把返回的结果集铺成表格。

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.PluralStrKey
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.editor.GenerateHelpers
import com.langbiantianya.sundays.editor.ui.CodeEditorState
import com.langbiantianya.sundays.editor.ui.CodeEditorWithToolbar
import com.langbiantianya.sundays.table.DataTable
import com.langbiantianya.sundays.table.TableColumn
import com.langbiantianya.sundays.ui.EmptyHint
import com.langbiantianya.sundays.ui.HorizontalDragHandle
import com.langbiantianya.sundays.ui.SundaysPalette
import com.langbiantianya.sundays.ui.WinButton
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinProgressIndicator
import com.langbiantianya.sundays.ui.plural
import com.langbiantianya.sundays.ui.str

// ============================================================================
// 造数工作台 —— Lua 脚本编辑 + 造数结果
// ============================================================================

/** 造数工作台的 Lua 版本候选 —— 对应引擎 `DataGenerateRequest.lua_version`。 */
private val LUA_VERSIONS = listOf("luajit", "5.1", "5.2", "5.3", "5.4", "5.5")

/** 新建造数脚本的初始内容 —— 顺带把引擎提供的 random_* 辅助函数列出来当速查表。 */
internal const val GENERATE_SCRIPT_TEMPLATE = """-- 造数脚本：insert(表名, { 列 = 值, … }) 逐条写库
-- 辅助：random_int / random_float / random_string / random_name / random_email / random_phone
--      random_date / random_datetime / random_time / random_uuid / random_enum / lastId()
for i = 1, 100 do
  insert("your_table", {
    name  = random_name(),
    email = random_email(),
    age   = random_int(18, 60),
  })
end"""

/** 造数日志面板的 UI 测试 tag —— 测试要确认「日志区真的在那儿、真的有内容」。 */
internal const val GENERATE_LOG_PANEL_TAG = "generateLogPanel"

/** 造数日志列表的 UI 测试 tag —— 日志行是惰性组合的，只有列表本身才有稳定边界。 */
internal const val GENERATE_LOG_LIST_TAG = "generateLogList"

/**
 * **造数输出面板**的 UI 测试 tag —— 日志与结果区**合并**后的那一个面板。
 *
 * 为什么要有这个 tag：合并之后，「日志与结果区在同一个面板里」这件事
 * 成了契约（此前它们是两个各自独立的 `Surface`，各自带标题栏）。
 * 测试要断言的正是这条 —— 两个区块各自都能找到这个祖先面板。
 */
internal const val GENERATE_OUTPUT_PANEL_TAG = "generateOutputPanel"

/** 造数结果区的 UI 测试 tag —— 拖拽测试要量「结果区被挤成多高了」。 */
internal const val GENERATE_RESULT_AREA_TAG = "generateResultArea"

/**
 * **输出面板标题栏**的 UI 测试 tag —— 面板级摘要（「已插入 N 行」）待在这儿。
 *
 * 为什么单独一个 tag：摘要说的是**整个面板**的状态，不是日志区或结果区其中一块的。
 * 钉住「摘要在标题栏里」需要能单独指认标题栏 —— 否则只能断言「这句话存在」，
 * 而它出现在面板里的任何位置都能通过那句话（哪怕它掉回结果区里了）。
 */
internal const val GENERATE_OUTPUT_HEADER_TAG = "generateOutputHeader"

/** 造数编辑器面板的 UI 测试 tag —— 拖拽测试要量「编辑器现在多高」。 */
internal const val GENERATE_EDITOR_PANE_TAG = "generateEditorPane"

/** 编辑器与输出面板之间那条分隔条的 UI 测试 tag —— 拖拽要有个明确的落点。 */
internal const val GENERATE_EDITOR_DRAG_HANDLE_TAG = "generateEditorDragHandle"

/**
 * 编辑器与输出面板之间那条分隔条的**命中区高度** —— 画线仍只有 1dp。
 *
 * 与 SQL 工作台那条缝**同一个值**：两条缝是同一个东西（都是「上编辑器 / 下输出」），
 * 两处各写一个数字，迟早会对不上，而对不上的表现是「底部多出一条缝」或「内容被顶出去」。
 */
private val GENERATE_EDITOR_HANDLE_HEIGHT = 8.dp

/**
 * 造数编辑器的**下限** —— 再矮就看不见工具栏 / 若干行代码了。
 *
 * 与 SQL 编辑器同一个值，理由相同：两个都是「写代码的地方」，下限该一致。
 */
private val MIN_GENERATE_EDITOR_HEIGHT = 96.dp

/**
 * **输出面板**的下限 —— 日志看不见就等于没有日志。
 *
 * ## 为什么输出面板的下限比编辑器**优先**
 *
 * 窗口很矮时两者会抢高度。这里的取舍与 SQL 工作台**同向**：
 * 优先保住输出面板（日志是造数过程的**唯一**窗口），编辑器让位 ——
 * 编辑器内部能滚，矮一点只是少显示几行；而日志区被压扁，用户连「刚才插了哪几行」
 * 都看不见，造数就变成了「按了按钮，什么都不知道」。
 *
 * ## 这条下限由谁钉
 *
 * 拖拽测试要断言「输出面板没被拖到低于它」，所以它是 `internal` 而不是 `private`。
 */
internal val MIN_GENERATE_OUTPUT_HEIGHT = 260.dp

/**
 * **输出面板**默认占「编辑器 + 输出面板」总高度的比例 —— 其余归编辑器。
 *
 * 沿用老布局的比例（编辑器 45% / 日志 25% / 结果 30%）里「输出占 55%」那一半：
 * 合并之后输出面板**就是**原来那两块（日志 + 结果）合起来，比例不变才不会
 * 「一升级，布局就变了」。
 */
private const val GENERATE_OUTPUT_DEFAULT_RATIO = 0.55f

/**
 * 输出面板里**结果区**的固定高度 —— 表头 + 数据行 + 分页栏。
 *
 * ## 为什么是固定高度而不是「按内容自适应」
 *
 * 结果表是「每个脚本一行」：脚本通常只有一两个，两行足够看。
 * 但如果按内容自适应，**整个面板的高度会在「跑完了」那一刻突变** ——
 * 用户刚拖好的编辑器高度看着好好的，结果区一出现就把日志挤扁了。
 * 固定高度换来「布局不动」。
 *
 * ## 为什么不再给它 30%
 *
 * 老布局给它 30%（900dp 窗口下约 270dp），而它实际只有两三行 ——
 * 那块空间原本是日志的。日志要的是**地方**（1000 行历史），
 * 结果表要的是**一眼能看到**。
 */
private val GENERATE_RESULT_AREA_HEIGHT = 150.dp

/**
 * 造数工作台 —— 上半脚本标签条 + Lua 版本 + 编辑器，下半**造数输出面板**。
 *
 * 与 [SqlWorkbenchPane] 同构：编辑器的 [CodeEditorState] 由状态机持有（[DatabaseBrowserState.generateScripts]），
 * 因此切到表预览再切回来时，脚本文本 / 光标 / 滚动位置都保持不变。
 *
 * ## 下半部分：**一个**面板，不是两个
 *
 * 此前下半是「日志面板」与「结果面板」两个各自独立的 `Surface`，
 * 各带一个标题栏、各占一块固定比例。它们的**关系**却是同一件事的两面：
 * 日志是「正在造什么」的**过程**，结果表是「造完了什么」的**结论**
 * （每个脚本一行）。拆成两个面板的结果是：两行标题栏、两条分隔线，
 * 而结论只占两三行却分到了 30% 的高度 —— 真正需要地方的日志被挤成了四分之一。
 *
 * 现在合成**一个**输出面板：标题栏一行（日志标题 + 丢行提示 + 摘要），
 * 下面是日志（占掉全部剩余空间）与结果表（固定高度）。
 *
 * ## 编辑器的高度**可拖**
 *
 * 比例（编辑器 45% / 输出 55%）是**编译期定死**的，而造数这两种诉求是
 * 互相打架的：写一段几百行的 Lua 循环时编辑器太矮；只想看一眼
 * 「刚才那批数据长什么样」时输出面板又占了七成屏。所以编辑器与输出面板
 * 之间那条缝是 [HorizontalDragHandle] —— 往下拖编辑器变高，双击复位回默认比例。
 *
 * @param editorHeight 编辑器的高度 —— **窗口级偏好**，由 [DatabaseBrowserScreen] 持有；
 *   `null` = 还没拖过，用默认比例（跟着窗口高度走）
 * @param onEditorHeightChange 拖动分隔条时的实时回调
 */
@Composable
internal fun GenerateWorkbenchPane(
    state: DatabaseBrowserState,
    connected: Boolean,
    schema: String,
    editorHeight: Dp? = null,
    onEditorHeightChange: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val script = state.currentGenerateScript()
    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = str(StrKey.GENERATE_WORKBENCH),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (schema.isNotBlank()) str(StrKey.SCHEMA_LABEL, schema) else str(StrKey.DEFAULT_CATALOG),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        WorkbenchTabStrip(
            leadingLabel = str(StrKey.SCRIPT),
            titles = state.generateScripts.map { it.title },
            selectedIndex = state.selectedGenerateIndex,
            addDescription = str(StrKey.NEW_GENERATE_SCRIPT),
            renameDescription = str(StrKey.RENAME_CURRENT_TAB),
            removeDescription = str(StrKey.CLOSE_SCRIPT),
            onSelect = state::selectGenerateScript,
            onAdd = state::addGenerateScript,
            onRemove = state::removeGenerateScript,
            onRename = state::renameGenerateScript,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = str(StrKey.LUA_VERSION),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LUA_VERSIONS.forEach { version ->
                val selected = state.generateLuaVersion == version
                AssistChip(
                    onClick = { state.generateLuaVersion = version },
                    enabled = connected,
                    label = { Text(version) },
                    colors =
                        if (selected) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                )
            }
        }
        WinDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 主体：上编辑器、下**输出面板**，中间一条**可拖**的缝。
        //
        // 比例是编译期定死的，而「写一段长 Lua」与「看一眼刚才那批数据」
        // 这两种诉求没法同时满足 —— 所以这条缝交给用户拖。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 四个高度先算清楚，**顺序不能反**（理由与 SQL 工作台完全一致）：
            // 1. `maxEditorHeight` —— 编辑器最多能有多高：要让出分隔条的命中区
            //    与输出面板的下限。
            // 2. `minEditorHeight` —— 编辑器的下限**要让位给**输出面板的下限
            //    （日志看不见就等于没有日志；编辑器内部能滚，矮一点只是少显示几行）。
            // 3. `defaultEditorHeight` —— **没拖过**时的初始高度（老比例 45/55），
            //    它随窗口高度走。
            // 4. `editorHeightNow` —— 实际生效的高度：拖过的用拖出来的值，
            //    没拖过的用默认值；两者都要过一遍钳位。
            //
            // 为什么每次都重新钳：窗口缩窄后上限会变小，
            // 而用户拖出来的那个绝对高度可能已经装不下了 —— 布局必须服从当前窗口。
            val maxEditorHeight =
                (maxHeight - GENERATE_EDITOR_HANDLE_HEIGHT - MIN_GENERATE_OUTPUT_HEIGHT)
                    .coerceAtLeast(0.dp)
            val minEditorHeight = MIN_GENERATE_EDITOR_HEIGHT.coerceAtMost(maxEditorHeight)
            val defaultEditorHeight =
                (
                    maxHeight -
                        GENERATE_EDITOR_HANDLE_HEIGHT -
                        (maxHeight * GENERATE_OUTPUT_DEFAULT_RATIO)
                ).coerceIn(minEditorHeight, maxEditorHeight)
            val editorHeightNow =
                (editorHeight ?: defaultEditorHeight)
                    .coerceIn(minEditorHeight, maxEditorHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                if (script == null) {
                    EmptyHint(
                        title = str(StrKey.NO_EDITABLE_SCRIPT),
                        description = str(StrKey.NO_EDITABLE_SCRIPT_HINT),
                        // 高度与「有脚本」时一致 —— 换句话说，
                        // 「没有可编辑的脚本」不该顺带把布局改了
                        modifier =
                            Modifier
                                .height(editorHeightNow)
                                .fillMaxWidth(),
                    )
                } else {
                    CodeEditorWithToolbar(
                        text = script.editor.text,
                        onTextChange = { script.editor.setText(it) },
                        editorState = script.editor,
                        languageId = "lua",
                        // 本工作台只处理 Lua —— 不暴露语言切换器
                        showLanguageSwitcher = false,
                        // 补全额外注入造数沙箱的宿主函数（`insert` / `lastId` / `random_*`）。
                        // 它们**只在这个沙箱里存在**，普通 Lua 编辑器里调用会报
                        // `attempt to call a nil value` —— 所以绝不能塞进 `LuaLanguage` 的
                        // BUILTINS，那会让所有 Lua 编辑器都开始推荐不存在的函数。
                        extraCompletions = GenerateHelpers.completions,
                        actions = {
                            WinButton(
                                onClick = { state.executeGenerate() },
                                enabled = connected && !state.generateRunning,
                                shape = SundaysPalette.buttonShape,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.generateRunning) str(StrKey.GENERATING) else str(StrKey.RUN_GENERATE))
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(editorHeightNow)
                                .testTag(GENERATE_EDITOR_PANE_TAG),
                    )
                }
                // 编辑器与输出面板之间那条**可拖**的缝 ——
                // 比例写死在代码里，而「写长脚本」与「只看结果」这两种诉求
                // 没法同时满足，所以这条缝交给用户拖；双击复位回默认比例。
                HorizontalDragHandle(
                    height = editorHeightNow,
                    onHeightChange = onEditorHeightChange,
                    defaultHeight = defaultEditorHeight,
                    minHeight = minEditorHeight,
                    maxHeight = maxEditorHeight,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    highlightColor = MaterialTheme.colorScheme.primary,
                    hitHeight = GENERATE_EDITOR_HANDLE_HEIGHT,
                    modifier = Modifier.testTag(GENERATE_EDITOR_DRAG_HANDLE_TAG),
                    // 悬停时换「上下拉伸」指针：分隔条在鼠标下必须看起来可拖，
                    // 否则拖拽功能「存在但没人发现」。
                    hoverIcon = nsResizePointerIcon(),
                )
                GenerateOutputArea(
                    state = state,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
            }
        }
    }
}

/**
 * 造数**结果区** —— 输出面板的**结论**那一半。
 *
 * 三态：进行中（一条转圈）/ 错误 / 结果表（每个脚本一行）；没跑过时是空态。
 *
 * ## 为什么它只有固定高度
 *
 * 见 [GENERATE_RESULT_AREA_HEIGHT]：结果表两三行就够，而按内容自适应会让
 * 整个面板在「跑完了」那一刻**突变高度** —— 用户刚拖好的编辑器高度看着好好的，
 * 结果区一出现就把日志挤扁了。固定高度换来「布局不动」。
 *
 * ## 为什么「已插入 N 行」不在这里
 *
 * 它说的是**整个面板**的状态，不是结果表的 —— 所以它放在输出面板的标题栏上，
 * 放在这里就成了同一句话出现两次（标题栏一次、这里一次）。
 */
@Composable
private fun GenerateResultArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        when {
            state.generateRunning -> {
                // 只留一个转圈 —— 「造数中… 已插入 N 行」已经写在**输出面板的标题栏**上了，
                // 这里再说一遍就是同一句话出现两次。
                //
                // 而这一格此时**本来就没有结论**：结果表要等跑完才有行可列。
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    WinProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                }
            }

            state.generateError != null -> {
                EmptyHint(
                    title = str(StrKey.GENERATE_FAILED),
                    description = state.generateError,
                    centerContent = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.hasGenerateResult -> {
                // 摘要（「共插入 N 行 · M 个脚本」）已经提到**输出面板的标题栏**上 ——
                // 它说的是**整个面板**，放在这儿就成了同一句话出现两次。
                DataTable(
                    columns = GENERATE_RESULT_COLUMNS,
                    rows = state.generateResultRows(),
                    modifier = Modifier.fillMaxSize(),
                    pageSize = state.generateResultPageSize,
                    onPageSizeChange = { state.generateResultPageSize = it },
                    currentPage = state.generateResultPage,
                    onPageChange = { state.generateResultPage = it },
                    selectedRowId = state.generateResultSelectedRowId,
                    onSelectedRowChange = { state.generateResultSelectedRowId = it?.id },
                    fillParentHeight = false,
                )
            }

            else -> {
                EmptyHint(
                    title = str(StrKey.NO_GENERATE_EXECUTED),
                    description = str(StrKey.NO_GENERATE_EXECUTED_HINT),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * **造数输出面板** —— 日志（过程）与结果表（结论）**合并**成的一块。
 *
 * ## 为什么合并
 *
 * 此前下半部分是两个各自独立的 `Surface`：日志面板一块、结果面板一块，
 * 各带一个标题栏、各占一块固定比例（25% / 30%）。而它们的**关系**是
 * 同一件事的两面：日志是「正在造什么」的**过程**，结果表是「造完了什么」的
 * **结论**（每个脚本一行）。
 *
 * 拆成两个面板的代价很具体：
 *
 * - **两行标题栏、两条分隔线** —— 屏幕就那么大，标题栏是实打实占地方的；
 * - **结论分到了 30% 的高度**，而它实际只有两三行；真正需要地方的
 *   日志（1000 行历史）只拿到 25%。
 *
 * 合成一块之后：标题栏只有一行（顺带把「已插入 N 行」这个**面板级**的摘要
 * 收了进来 —— 它说的是整个面板，不该只属于其中一块），日志吃掉全部剩余空间，
 * 结果表拿到一个固定的小高度。
 *
 * ## 日志的数据从哪来
 *
 * 引擎的 `DATA.GENERATE` 流里**每条 INSERT 回一帧** `gen_progress_frame`
 * （表名 / 该脚本已插入的行数 / 脚本下标 / 这一行的数据）。
 * 状态机把它们收进 [DatabaseBrowserState.generateLog]（有界，见 [GENERATE_LOG_MAX_LINES]）。
 *
 * 引擎协议里**没有**独立的日志消息 —— 这份日志是前端拿现成的进度帧拼出来的，
 * 引擎一行代码都不用改。引擎侧此前只把这些帧用来更新计数器，
 * 信息其实早就够了，只是没人把它铺出来。
 *
 * ## 为什么要「实时」而不是「造完再看」
 *
 * 造数是**批量写脏数据**：一段 Lua 循环几千行 INSERT。
 * 哪一行把列名写错了、哪一行类型不对，只有逐行看才能定位 ——
 * 造完之后只剩一句「共插入 3000 行」，错的那行已经无从查起。
 */
@Composable
private fun GenerateOutputArea(
    state: DatabaseBrowserState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag(GENERATE_OUTPUT_PANEL_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag(GENERATE_OUTPUT_HEADER_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(StrKey.GENERATE_LOG_TITLE),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // 丢行必须**说出来** —— 静默截断等于伪造了一份完整的日志。
                if (state.generateLogDropped > 0) {
                    Text(
                        text = str(StrKey.GENERATE_LOG_DROPPED, state.generateLogDropped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                // 面板级的摘要 —— 进行中报「已插入 N 行」，跑完报「共插入 N 行 · M 个脚本」。
                //
                // 为什么放在标题栏而不是结果区里：它说的是**整个面板**的状态
                // （这一轮造数造到哪儿了），不是结果表的 —— 放在结果区里
                // 就会变成「同一句话，一处在标题栏一处在结果区」。
                //
                // 两个分支互斥：进行中与「已完成」不可能同时成立。
                val summary: String? =
                    if (state.generateRunning) {
                        plural(
                            PluralStrKey.GENERATING_INSERTED,
                            state.generateTotalInserted.toInt(),
                            state.generateTotalInserted,
                        )
                    } else if (state.hasGenerateResult) {
                        plural(
                            PluralStrKey.GENERATE_COMPLETE_SUMMARY,
                            state.generateTotalInserted.toInt(),
                            state.generateTotalInserted,
                            state.generateTablesProcessed,
                        )
                    } else {
                        null
                    }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 日志区 —— 吃掉**全部剩余**空间（面板整体高度由编辑器那条缝决定）。
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(GENERATE_LOG_PANEL_TAG),
            ) {
                if (state.generateLog.isEmpty()) {
                    EmptyHint(
                        title = str(StrKey.GENERATE_LOG_EMPTY_TITLE),
                        description = str(StrKey.GENERATE_LOG_EMPTY_HINT),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    GenerateLogList(state)
                }
            }
            WinDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 结果区 —— 结论那一半，固定高度（见 [GENERATE_RESULT_AREA_HEIGHT]）。
            GenerateResultArea(
                state = state,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(GENERATE_RESULT_AREA_HEIGHT)
                        .testTag(GENERATE_RESULT_AREA_TAG),
            )
        }
    }
}

/**
 * 日志列表 —— 惰性渲染 + 自动滚到最新一行。
 *
 * ## 为什么用 [LazyColumn] 而不是 `Column` + `verticalScroll`
 *
 * 日志有 [GENERATE_LOG_MAX_LINES] 行的上限，1000 行全组合起来就是 1000 个文本节点，
 * 每来一行都要重新组合一遍 —— 造数到后段会肉眼可见地卡。
 * `LazyColumn` 只组合看得见的那几行。
 *
 * ## 自动滚动盯的是「序号」，条数只是**下标**的来源
 *
 * 条数在丢行时**不是单调的**（一次成批丢行会让它从 1129 掉回 1000），
 * 于是「条数变了」既可能是「又来了一行」，也可能是「旧的被丢了」。
 * [DatabaseBrowserState.generateLogSeq] 每次追加**只 +1** ——
 * 「又来了一行」因此只有**一个**信号。
 *
 * 而条数仍然要读 —— 它就是**滚到第几行**；只是**在组合期只读一次**：
 * 列表的 item 数与要滚的下标必须是**同一个数**，
 * 分两次读的话，中间来一次追加，滚到的下标就落在列表之外了。
 */
@Composable
private fun GenerateLogList(state: DatabaseBrowserState) {
    val listState = rememberLazyListState()
    // 条数在**组合期**只读一次 —— 列表的 item 数与要滚到的下标必须是**同一个数**。
    // 分两次读的话，中间来一次追加，滚到的下标就落在列表之外了。
    val count = state.generateLog.size
    LaunchedEffect(state.generateLogSeq, count) {
        if (count > 0) {
            // 用**挂起式**的 `LazyListState.scrollToItem`，不用 `requestScrollToItem`。
            //
            // 差别在于：`requestScrollToItem` 记下一个「下一次 remeasure 时生效、且只生效一次」
            // 的请求 —— 而 remeasure 常常**同时**要应用数据变更（行数变了）。
            // 那一刻位置是按**key** 维持的（列表是索引键），新行就落在视野之外了；
            // 请求有没有抢赢，取决于它与那次 remeasure 的先后 —— **竞态**。
            //
            // `scrollToItem` 则在滚动块里**同步**做完一次 remeasure：
            // 位置一定落在最后一行，且与其它滚动互斥（滚动块的锁）。
            listState.scrollToItem(count - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(GENERATE_LOG_LIST_TAG),
    ) {
        items(count) { index ->
            // `getOrNull` 而不是 `[index]` —— 丢行会让列表在**布局用到旧下标**之前
            // 短暂地比组合期读到的条数短（丢行发生在后台线程，落到界面线程是下一帧的事）。
            // 那种一帧里的下标越界会把**组合抛掉**（应用崩），而少画一行只是看不见。
            state.generateLog.getOrNull(index)?.let { GenerateLogRow(it) }
        }
    }
}

/**
 * 一行日志 —— 正常行显示「脚本 / 表 / 行号 / 列值」，失败行显示失败原因。
 *
 * 用**等宽字体**：列值是 `name=Alice, age=30` 这种键值对，
 * 等宽对齐才能一眼看出哪一段是哪个字段；比例字体的对齐全是假的。
 */
@Composable
private fun GenerateLogRow(entry: GenerateLogEntry) {
    val error = entry.error
    val text =
        if (error != null) {
            str(StrKey.GENERATE_LOG_ERROR, error)
        } else {
            str(
                StrKey.GENERATE_LOG_LINE,
                entry.scriptIndex,
                entry.totalScripts,
                entry.table,
                entry.rowNumber,
                formatGenerateLogValues(entry.values),
            )
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 造数结果表列定义（脚本 / 目标表 / 插入行数）。 */
private val GENERATE_RESULT_COLUMNS =
    listOf(
        TableColumn(key = "script", header = Msg.text(StrKey.SCRIPT)),
        TableColumn(key = "table", header = Msg.text(StrKey.TARGET_TABLE)),
        TableColumn(key = "inserted", header = Msg.text(StrKey.INSERTED_ROWS)),
    )
