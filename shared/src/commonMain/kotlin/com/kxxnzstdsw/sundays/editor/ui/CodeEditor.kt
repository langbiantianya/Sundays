package com.kxxnzstdsw.sundays.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.sundays.editor.CodeLanguage
import com.kxxnzstdsw.sundays.editor.CodeLanguageRegistry
import com.kxxnzstdsw.sundays.editor.CompletionItem
import com.kxxnzstdsw.sundays.editor.DEFAULT_COMPLETION_LIMIT
import com.kxxnzstdsw.sundays.editor.EditorContextMenuPayload
import com.kxxnzstdsw.sundays.editor.EditorContextMenuState
import com.kxxnzstdsw.sundays.editor.MIN_COMPLETION_PREFIX
import com.kxxnzstdsw.sundays.editor.SyntaxHighlighter
import com.kxxnzstdsw.sundays.editor.applyCompletion
import com.kxxnzstdsw.sundays.editor.formatter.CodeFormatterRegistry
import com.kxxnzstdsw.sundays.editor.language.LuaLanguage
import com.kxxnzstdsw.sundays.editor.language.SqlDialectProfile
import com.kxxnzstdsw.sundays.editor.rememberEditorContextMenuState
import com.kxxnzstdsw.sundays.editor.selectExtras
import com.kxxnzstdsw.sundays.editor.wordPrefixBefore
import com.kxxnzstdsw.sundays.ui.SundaysPalette
import com.kxxnzstdsw.sundays.ui.WinButton
import com.kxxnzstdsw.sundays.ui.onRightClick
import com.kxxnzstdsw.sundays.ui.winShape

/**
 * 编辑器内部状态 —— 文本 + 光标/选区 + 滚动位置。
 *
 * 默认由 [rememberCodeEditorState] 创建，生命周期与调用它的组合绑定；组件离开组合再回来
 * （例如「SQL 工作台 ↔ 表预览」切换把编辑器从组合里摘掉）时，光标会回到文首、滚动回到顶部。
 * 需要跨这种切换保持一致时，**由状态机持有本对象**并作为 [CodeEditor] / [CodeEditorWithToolbar]
 * 的 `editorState` 传入 —— 文本、选区、滚动位置即随状态机存活。
 *
 * 文本真相源仍是 `text` 参数（[CodeEditor] 内部会把外部 `text` 同步进本对象）；
 * [setText] 用于「整段替换」（用户输入、格式化、重置），**保留光标 / 选区**（越界时夹到新文本范围内）——
 * 受控输入每个键都会回调它，把光标弹到文末会让用户无法在文本中间编辑。
 */
class CodeEditorState(initialText: String = "") {

    /** 输入框的值（文本 + 光标/选区） */
    var value: TextFieldValue by mutableStateOf(TextFieldValue(initialText, TextRange.Zero))
        private set

    /** 行号 gutter 与代码区共享的滚动位置 */
    val scrollState: ScrollState = ScrollState(0)

    /** 当前文本（等价 `value.text`） */
    val text: String get() = value.text

    /** 用户输入（BasicTextField 回调） */
    fun onValueChange(newValue: TextFieldValue) {
        value = newValue
    }

    /**
     * 整段替换文本（用户输入 / 格式化 / 外部赋值）—— **保留光标与选区**（夹到新文本长度内），
     * 滚动位置不动。文本未变时是空操作。
     */
    fun setText(newText: String) {
        if (value.text == newText) return
        val len = newText.length
        val selection = value.selection
        value = value.copy(
            text = newText,
            selection = TextRange(
                selection.min.coerceIn(0, len),
                selection.max.coerceIn(0, len),
            ),
        )
    }
}

/**
 * 记住一个 [CodeEditorState]（默认用法：状态随组合存活）。
 * 传入 [initialText] 仅在**首次创建**时生效 —— 之后的文本变化由 [CodeEditor] 的 `text` 参数同步。
 */
@Composable
fun rememberCodeEditorState(initialText: String = ""): CodeEditorState =
    remember { CodeEditorState(initialText) }

/**
 * 可扩展的代码编辑器 Composable —— 支持语法高亮、格式化、语言切换、行号显示。
 *
 * ## 设计要点
 *
 * - **解耦语言与 UI**：通过 [CodeLanguageRegistry] 查找语言，新增语言只需 `registry.register(...)`
 * - **tokenize 缓存**：使用 [rememberCodeHighlighter] 复用 [SyntaxHighlighter]，避免每次 recompose 创建
 * - **tokenize 时机**：[text] 与 [languageId] 变化时重新 tokenize + 高亮，在 UI 线程同步执行 ——
   SQL / Lua 典型体量（< 10K 行）足够快；大文本如需异步化见 §8.2 未来扩展
 * - **共享 ScrollState**：行号 gutter 与代码编辑区是同一个 `verticalScroll` 容器的两个子节点，
 *   因此垂直滚动天然同步（用户滚编辑器时行号同步移动，反之亦然）
 *
 * ## 用法
 *
 * ```kotlin
 * var text by remember { mutableStateOf("SELECT * FROM users") }
 * var lang by remember { mutableStateOf("sql") }
 *
 * CodeEditorWithToolbar(
 *     text = text,
 *     onTextChange = { text = it },
 *     languageId = lang,
 *     onLanguageChange = { lang = it },
 * )
 * ```
 *
 * ## 扩展性保证
 *
 * - 新增 token 类型 → 只需在 [SyntaxHighlighter.DefaultLightColors] / [SyntaxHighlighter.DefaultDarkColors] 追加键值对
 * - 新增语言 → 实现 [CodeLanguage] + 注册；编辑器零修改即可显示
 * - 新增 formatter → 实现 `CodeFormatter` 接口 + 注册到 [CodeFormatterRegistry]；工具栏"格式化"按钮自动启用
 * - 替换主题 → 提供自定义 [CodeEditorTheme]
 *
 * @param text 编辑器文本内容
 * @param onTextChange 文本变化回调（**不要在内部直接调用 `text = newText`** — 让上层管理 state）
 * @param languageId 当前语言 ID（传入 `null` 表示禁用高亮 — 纯文本模式）
 * @param modifier Compose modifier
 * @param theme 编辑器主题（默认 [CodeEditorTheme.default]）
 * @param showLineNumbers 是否显示行号（默认 true — IDE 习惯）
 * @param minLines 最小显示行数（仅约束高度下限，不填充空行）
 * @param maxLines 最大显示行数（超过则内部滚动）；**默认 `null`（不限制）** —— 编辑器会填充
 *   父容器剩余高度，不会超过父容器；调用方显式传入整数才启用高度上限
 * @param editorState 编辑器内部状态（文本 / 光标 / 滚动）；默认 [rememberCodeEditorState]，生命周期与组合同。
 *   需要在组件离开组合后仍保持一致时（如工作台与表预览互切），由调用方的状态机持有并传入
 * @param contextMenuState 右键菜单状态；通常用 [rememberEditorContextMenuState] 创建
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；
 *   payload 通过 [EditorContextMenuPayload]（包含当前 text + languageId）传入
 * @param enableCompletion 是否启用关键字 / 函数补全（「提示」功能，见 §2.10）。默认开启；
 *   `languageId = null`（纯文本）或语言未实现 `completionCandidates` 时自动不生效
 * @param maxCompletionItems 弹层最多同时展示的候选条数
 * @param extraCompletions 调用方注入的额外候选，**优先于**语言自带候选。用于「当前上下文
 *   才有、但语言本身不认识」的符号 —— 如造数工作台注入 [GenerateHelpers.completions]
 *   （那些 `insert` / `random_*` 只在造数沙箱里存在，塞进 `LuaLanguage` 会误导普通 Lua 编辑器）
 */
@Composable
fun CodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    languageId: String?,
    modifier: Modifier = Modifier,
    theme: CodeEditorTheme = CodeEditorTheme.default(),
    showLineNumbers: Boolean = true,
    minLines: Int = 3,
    maxLines: Int? = null,
    editorState: CodeEditorState = rememberCodeEditorState(text),
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
    enableCompletion: Boolean = true,
    maxCompletionItems: Int = DEFAULT_COMPLETION_LIMIT,
    extraCompletions: List<CompletionItem> = emptyList(),
) {
    val language = remember(languageId) {
        languageId?.let { CodeLanguageRegistry.get(it) }
    }
    val highlighter = rememberCodeHighlighter(theme)
    val fieldValue = editorState.value

    // 外部 text 变化（重置、格式化）需要同步到内部 TextFieldValue
    LaunchedEffect(text) {
        if (editorState.value.text != text) {
            editorState.setText(text)
        }
    }

    val transformation = remember(language, highlighter, fieldValue.text) {
        CodeVisualTransformation(language, highlighter)
    }

    // 输入区内边距 —— 光标坐标取自**文本**布局，而文本原点在输入区内边距之后，
    // 所以补全弹层定位时必须把内边距加回去。用常量而非字面量，避免两处数值漂移。
    val EDITOR_PADDING_H = 12.dp
    val EDITOR_PADDING_V = 8.dp
    // 弹层单行高度估算（字体 14 + 上下各 6dp 内边距）—— 仅用于「上翻」判断，允许误差
    val COMPLETION_ROW_HEIGHT = 28.dp

    // =========================================================================
    // 补全（「提示」功能）状态
    //
    // 为什么用 LaunchedEffect 而不是 onValueChange 里直接算：候选取决于「光标位置 + 当前词」，
    // 而 onValueChange 只在**文本**变化时触发 —— 纯移动光标（如方向键跳到别处）不会重新计算，
    // 弹层会留在旧位置不跟。用 effect 把 selection 也纳入 key 就没这个问题。
    // =========================================================================
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var completionItems by remember { mutableStateOf(emptyList<CompletionItem>()) }
    var completionIndex by remember { mutableIntStateOf(0) }

    val selectionStart = fieldValue.selection.start
    val textNow = fieldValue.text
    val collapsedSelection = fieldValue.selection.collapsed

    LaunchedEffect(
        enableCompletion, language, textNow, selectionStart, collapsedSelection, extraCompletions,
    ) {
        val lang = language
        if (!enableCompletion || lang == null || !collapsedSelection) {
            completionItems = emptyList()
            return@LaunchedEffect
        }
        val prefix = wordPrefixBefore(textNow, selectionStart)
        completionItems = if (prefix.length < MIN_COMPLETION_PREFIX) {
            emptyList()
        } else {
            // **额外候选优先**：它们是当前工作台的核心语义（造数沙箱的 random_* 等），
            // 用户敲 `rand` 时最想看到的就是它们，语言候选在后兜底。
            val fromLanguage = lang.completionCandidates(prefix, maxCompletionItems)
            val fromExtras = selectExtras(prefix, extraCompletions, maxCompletionItems)
            (fromExtras + fromLanguage).take(maxCompletionItems)
        }
        // 候选集一变就回到第一条：保留旧下标会指向一条不相干的词
        completionIndex = 0
    }

    fun acceptCompletion(item: CompletionItem) {
        val (newText, newCaret) = applyCompletion(textNow, selectionStart, item)
        editorState.onValueChange(TextFieldValue(newText, TextRange(newCaret)))
        if (newText != text) onTextChange(newText)
        completionItems = emptyList()
    }

    /**
     * 弹层打开时的按键分发。
     *
     * 用 `onPreviewKeyEvent` 而非 `onKeyEvent`：Tab 与 Enter 在 `BasicTextField` 上有**默认行为**
     * （移动焦点 / 换行），只有 preview 阶段能先截住并 `consume` 掉。
     */
    fun onCompletionKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (completionItems.isEmpty()) return false
        val size = completionItems.size
        return when (event.key) {
            // 桌面端小键盘的回车同样上报为 Key.Enter，无需单独处理 NumPadEnter
            Key.Tab, Key.Enter -> {
                acceptCompletion(completionItems[completionIndex.coerceIn(0, size - 1)])
                true
            }
            Key.Escape -> {
                // 显式关闭：仅清候选即可，用户下次敲字会重新触发
                completionItems = emptyList()
                true
            }
            Key.DirectionDown -> {
                completionIndex = (completionIndex + 1) % size
                true
            }
            Key.DirectionUp -> {
                // Kotlin 的 % 会给出负余数，这里补 size 再取模实现**向上循环**
                completionIndex = (completionIndex - 1 + size) % size
                true
            }
            else -> false
        }
    }

    val minHeight = (theme.textStyle.fontSize.value * minLines + 16).dp
    // 变量名带 Dp 后缀：避免遮蔽 `BoxWithConstraintsScope.maxHeight`（同名局部变量会优先于
    // 隐式接收者成员解析，曾导致取其值得到本变量的 null 而非编辑框高度）。
    val maxHeightDp = maxLines?.let { (theme.textStyle.fontSize.value * it + 16).dp }

    // 共享 ScrollState — gutter 与 BasicTextField 共同放在 verticalScroll 容器内，
    // 二者的滚动位置由同一个 ScrollState 统一管理，保证行号与代码完全同步
    val sharedScrollState = editorState.scrollState

    // 计算行数（按 \n 分割 + 1，至少为 1）
    val lineCount = remember(text) {
        if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
    }

    // 高度策略：
    // - [maxLines] 已设置 → 显式上限（高度介于 [minHeight] 与 [maxHeight]）
    // - [maxLines] 未设置（默认 `null`）→ 不施加高度上限，填充父容器剩余高度
    //   （`fillMaxHeight()` 使编辑器在父容器剩余空间内自动展开；
    //    配合 [verticalScroll]，内容超过可用高度时仍可滚动而不溢出父容器）
    val sizeModifier = if (maxHeightDp != null) {
        Modifier.heightIn(min = minHeight, max = maxHeightDp)
    } else {
        Modifier.fillMaxHeight().heightIn(min = minHeight)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.backgroundColor, winShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, winShape(6.dp))
            .then(sizeModifier),
    ) {
        // 编辑框可视高度 —— 用于把 BasicTextField 撑满整框。
        //
        // 为什么必须在滚动容器**之外**量这个高度：`verticalScroll` 会用**无界**高度测量其内容，
        // 因此子项上的 `fillMaxHeight()` 不生效（拿到 Infinity 后退化为 wrap content）。
        // 结果是 BasicTextField 只有一行高（实测 19dp），编辑框下方大片区域点不到 —— 表现为
        // 「点编辑器没反应、无法输入」。这里在滚动之前取到框高，再以 `heightIn(min = …)`
        // 把输入区撑满，点击任意位置都能聚焦；内容超出时仍由 verticalScroll 滚动。
        //
        // 用 `constraints.maxHeight`（Int, px）而非 scope 的 `maxHeight`（Dp）：后者会被本函数
        // 同名的局部变量遮蔽（已改名 [maxHeightDp] 规避），这里保持显式以免重蹈覆辙。
        val boxHeightDp = if (constraints.hasBoundedHeight) {
            with(LocalDensity.current) { constraints.maxHeight.toDp() }
        } else {
            null
        }

        // 补全弹层「上翻」判断需要的可视边界 —— **必须在这里取**：
        // 弹层所在的 `Box` 也有自己的 `constraints` 接收者，在其内部读到的是那个（会遮蔽外层）。
        val editorMaxHeightPx = constraints.maxHeight
        val editorMaxWidthDp = maxWidth

        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(sharedScrollState)
                // 右键菜单 —— 监听容器内的右键点击
                .onRightClick { offset ->
                    contextMenuState.show(offset, EditorContextMenuPayload(text = text, languageId = languageId))
                },
        ) {
            if (showLineNumbers) {
                LineNumberGutter(
                    lineCount = lineCount,
                    theme = theme,
                )
            }
            // 弹层被放在这个 Box 里（**滚动容器内部**），因此天然跟着代码一起滚 ——
            // 不用手算滚动偏移，也就不会出现「代码滚了、弹层没滚」的错位。
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        editorState.onValueChange(newValue)
                        if (newValue.text != text) onTextChange(newValue.text)
                    },
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (boxHeightDp != null) Modifier.heightIn(min = boxHeightDp) else Modifier)
                        .padding(horizontal = EDITOR_PADDING_H, vertical = EDITOR_PADDING_V)
                        .onPreviewKeyEvent { onCompletionKey(it) },
                    textStyle = theme.textStyle,
                    visualTransformation = transformation,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.textStyle.color),
                )

                // 补全弹层 —— 定位到光标正下方；空间不够时上翻
                if (completionItems.isNotEmpty()) {
                    // **必须钳位**：`layoutResult` 来自上一次布局，可能比当前文本短
                    // （打字过程中 selection 先于布局更新）。直接传 selection.start
                    // 会让 `getBoundingBox` 因越界抛 IllegalArgumentException ——
                    // 一次就足以打断整轮 recompose，把弹层变成崩溃的开关。
                    // 用 `getCursorRect` 而非 `getBoundingBox`：前者才是「光标矩形」的正解，
                    // 光标落在末尾时也不会偏到最后一个字符的左边。
                    val caretRect = layoutResult?.let { lr ->
                        val len = lr.layoutInput.text.length
                        if (len <= 0) null
                        else lr.getCursorRect(fieldValue.selection.start.coerceIn(0, len - 1))
                    }
                    val density = LocalDensity.current
                    // 光标坐标取自**文本**布局，而文本原点落在输入区内边距之后，
                    // 所以这里要把内边距加回去，否则弹层会整体左上偏移一个内边距的距离。
                    val paddingH = with(density) { EDITOR_PADDING_H.roundToPx() }
                    val paddingV = with(density) { EDITOR_PADDING_V.roundToPx() }
                    val caretX = paddingH + (caretRect?.left ?: 0f).toInt()
                    val caretBottom = paddingV + (caretRect?.bottom ?: 0f).toInt()
                    val caretTop = paddingV + (caretRect?.top ?: 0f).toInt()
                    // 上翻阈值：按行高估算弹层高度，放不下就退到光标上方
                    val estimatedHeight = with(density) {
                        // Dp 的 `times` 只在接收者一侧，Int * Dp 不成立 —— 必须是 Dp * Int
                        (COMPLETION_ROW_HEIGHT * completionItems.size + 12.dp).roundToPx()
                    }
                    val y = if (caretBottom + estimatedHeight > editorMaxHeightPx) {
                        caretTop - estimatedHeight
                    } else {
                        caretBottom
                    }
                    CompletionPopup(
                        items = completionItems,
                        selectedIndex = completionIndex.coerceIn(
                            0,
                            (completionItems.size - 1).coerceAtLeast(0),
                        ),
                        onAccept = ::acceptCompletion,
                        maxWidth = (editorMaxWidthDp - 24.dp).coerceAtLeast(160.dp),
                        modifier = Modifier.atCaret(
                            x = caretX,
                            y = y.coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    // 右键菜单 —— 浮在编辑器之上，不绑定具体位置（位置由 EditorContextMenuState 记录）
    if (contextMenuState.visible) {
        val density = LocalDensity.current
        val menuOffset = androidx.compose.ui.unit.DpOffset(
            x = with(density) { contextMenuState.position.x.toDp() },
            y = with(density) { contextMenuState.position.y.toDp() },
        )
        DropdownMenu(
            expanded = true,
            onDismissRequest = { contextMenuState.dismiss() },
            offset = menuOffset,
        ) {
            contextMenuItems(contextMenuState.payload)
        }
    }
}

/**
 * 行号 gutter — 显示在编辑器左侧，与代码编辑区垂直同步滚动。
 *
 * ## 行为约定
 *
 * - **行数计算**：按 `\n` 分割 + 1；空文本显示 1 行
 * - **宽度自适应**：根据总行数的位数自动扩展（至少 2 位宽 → 容纳 "1, 2, ..., 99"）
 * - **滚动同步**：gutter 与编辑器同处一个 `verticalScroll` 容器（见 [CodeEditor]），随其一起移动
 * - **配色**：背景用 [CodeEditorTheme.gutterColor]；文字用主题文字色 + alpha 降低，使其"低调"但不消失
 * - **行高**：使用与编辑器相同的 [TextStyle]，自动匹配编辑器行高（含自定义 lineHeight）
 *
 * @param lineCount 总行数
 * @param theme 编辑器主题
 */
@Composable
private fun LineNumberGutter(
    lineCount: Int,
    theme: CodeEditorTheme,
) {
    // 计算 gutter 宽度：根据总行数位数 + 单字符宽度
    val digits = maxOf(2, lineCount.toString().length)
    val density = LocalDensity.current
    val fontSizeDp = with(density) { theme.textStyle.fontSize.toDp() }
    // 等宽字体下，单字符宽度约为 fontSize * 0.6
    val charWidthDp = fontSizeDp * 0.6f
    val gutterWidth = charWidthDp * digits + 12.dp

    // 行号文字色：使用主题文字色 + 降低 alpha，使其既可读又不抢戏
    val lineNumberColor = theme.textStyle.color.copy(alpha = 0.5f)

    Column(
        modifier = Modifier
            .width(gutterWidth)
            .background(theme.gutterColor)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        for (i in 1..lineCount) {
            Text(
                text = i.toString(),
                style = theme.textStyle.copy(color = lineNumberColor),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * 带工具栏的代码编辑器 —— 在 [CodeEditor] 之上增加：
 * - 语言切换下拉框（**可隐藏** — 通过 [showLanguageSwitcher] 关闭）
 * - "格式化"按钮（自动检查 [CodeFormatterRegistry] 是否有可用 formatter）
 * - **可扩展 actions 插槽** — 调用方可注入任意自定义按钮（"执行"、"清空"、"复制" 等）
 *
 * 工具栏使用 [Row] 横向排列；调用方可包裹 [Column] / [Surface] 自定义外观。
 *
 * ## 三种典型用法
 *
 * ### 1. 完整工具栏（语言可切换 + 格式化 + 自定义按钮）
 * ```kotlin
 * var lang by remember { mutableStateOf("sql") }
 * CodeEditorWithToolbar(
 *     text = sql,
 *     onTextChange = { sql = it },
 *     languageId = lang,
 *     onLanguageChange = { lang = it },
 *     actions = { WinButton(onClick = { execute(sql) }) { Text("执行") } },
 * )
 * ```
 *
 * ### 2. 固定语言（隐藏切换器，`onLanguageChange` 可省略）
 * ```kotlin
 * CodeEditorWithToolbar(
 *     text = sql,
 *     onTextChange = { sql = it },
 *     languageId = "sql",  // 在实例化时直接指定，无需外部 state
 *     // onLanguageChange 默认为空 lambda，工具栏不再暴露切换入口
 *     showLanguageSwitcher = false,
 * )
 * ```
 *
 * ### 3. 纯文本模式 + 自定义按钮
 * ```kotlin
 * CodeEditorWithToolbar(
 *     text = notes,
 *     onTextChange = { notes = it },
 *     languageId = "plain",
 *     showLanguageSwitcher = false,
 *     actions = { WinButton(onClick = { save() }) { Text("保存") } },
 * )
 * ```
 *
 * ## 扩展用法（actions 插槽）
 *
 * 调用方在 `actions` lambda 内可以放任意 Composable（[Button] / [androidx.compose.material3.IconButton] / [AssistChip] / [Icon]...），
 * 它们会按声明顺序追加到内置按钮（语言切换 + 格式化）**之后**。
 *
 * ## 设计权衡
 *
 * - **Slot-based**（`@Composable RowScope.() -> Unit`）vs **data-driven**（`List<EditorAction>`）：
 *   选择 slot API，因为它和 Compose 生态一致（[androidx.compose.material3.TopAppBar] 等都是这种风格），
 *   调用方可以自由控制按钮的视觉/状态（`enabled` / `colors` / `icon`），无需预先枚举所有可能性。
 * - **位置**：内置按钮在前，自定义按钮在后 — 避免破坏现有调用方的视觉惯例。
 * - **`onLanguageChange` 可选**：当 [showLanguageSwitcher] 关闭时，回调不会被调用，
 *   因此设为默认空 lambda 让调用方按需重写，减少无意义样板代码。
 *
 * @param text 编辑器文本内容
 * @param onTextChange 文本变化回调
 * @param languageId 当前语言 ID（**在实例化时直接指定**即可初始化语言；如需切换则通过 [onLanguageChange] 接收新 ID）
 * @param onLanguageChange 语言切换回调 — 当 [showLanguageSwitcher] = false 时不会被调用，可省略
 * @param modifier Compose modifier
 * @param theme 编辑器主题
 * @param showLineNumbers 是否显示行号（默认 true）
 * @param showLanguageSwitcher 是否显示语言切换下拉框（默认 true；设为 false 时隐藏并允许省略 [onLanguageChange]）
 * @param minLines 最小显示行数
 * @param maxLines 最大显示行数（超过则内部滚动）；**默认 `null`（不限制）** —— 编辑器会填充
 *   父容器剩余高度，不会超过父容器；调用方显式传入整数才启用高度上限
 * @param editorState 编辑器内部状态（文本 / 光标 / 滚动）；默认 [rememberCodeEditorState]，生命周期与组合同。
 *   需要在组件离开组合后仍保持一致时，由调用方的状态机持有并传入
 * @param actions 自定义操作按钮插槽 — 渲染在内置按钮之后
 * @param contextMenuState 右键菜单状态；通常用 [rememberEditorContextMenuState] 创建
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；
 *   payload 通过 [EditorContextMenuPayload]（包含当前 text + languageId）传入
 * @param enableCompletion 是否启用关键字 / 函数补全（「提示」功能）；默认开启
 * @param extraCompletions 注入上下文专属候选（如造数沙箱的宿主函数），优先于语言候选
 */
@Composable
fun CodeEditorWithToolbar(
    text: String,
    onTextChange: (String) -> Unit,
    languageId: String,
    modifier: Modifier = Modifier,
    theme: CodeEditorTheme = CodeEditorTheme.default(),
    showLineNumbers: Boolean = true,
    showLanguageSwitcher: Boolean = true,
    minLines: Int = 5,
    maxLines: Int? = null,
    editorState: CodeEditorState = rememberCodeEditorState(text),
    actions: @Composable RowScope.() -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
    enableCompletion: Boolean = true,
    extraCompletions: List<CompletionItem> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        EditorToolbar(
            languageId = languageId,
            onLanguageChange = onLanguageChange,
            onFormat = {
                CodeFormatterRegistry.get(languageId)?.let { formatter ->
                    try {
                        val formatted = formatter.format(text)
                        if (formatted != text) onTextChange(formatted)
                    } catch (e: Exception) {
                        // 格式化失败 — 保留原文本；调用方可在外层捕获并提示
                        e.printStackTrace()
                    }
                }
            },
            showLanguageSwitcher = showLanguageSwitcher,
            actions = actions,
        )
        CodeEditor(
            text = text,
            onTextChange = onTextChange,
            languageId = languageId,
            theme = theme,
            showLineNumbers = showLineNumbers,
            minLines = minLines,
            maxLines = maxLines,
            editorState = editorState,
            contextMenuState = contextMenuState,
            contextMenuItems = contextMenuItems,
            enableCompletion = enableCompletion,
            extraCompletions = extraCompletions,
        )
    }
}

/**
 * 工具栏私有 Composable —— 由 [CodeEditorWithToolbar] 内部使用。
 *
 * 包含：
 * 1. 语言切换下拉框（[AssistChip]）— 可通过 [showLanguageSwitcher] 隐藏
 * 2. 格式化按钮（自动根据 [CodeFormatterRegistry] 启用 / 禁用）
 * 3. 自定义 actions 插槽 — 调用方可在 [CodeEditorWithToolbar] 的 `actions` 参数中注入
 *
 * 所有按钮之间通过 [Arrangement.spacedBy]（8.dp）等距分隔，保证视觉一致性。
 */
@Composable
private fun EditorToolbar(
    languageId: String,
    onLanguageChange: (String) -> Unit,
    onFormat: () -> Unit,
    showLanguageSwitcher: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val languages = remember { CodeLanguageRegistry.all() }
    val hasFormatter = remember(languageId) { CodeFormatterRegistry.get(languageId) != null }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLanguageSwitcher) {
            Box {
                AssistChip(
                    onClick = { dropdownExpanded = true },
                    label = {
                        Text(
                            CodeLanguageRegistry.get(languageId)?.displayName ?: languageId,
                        )
                    },
                )
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.displayName) },
                            onClick = {
                                onLanguageChange(lang.id)
                                dropdownExpanded = false
                            },
                        )
                    }
                }
            }
        }
        WinButton(
            onClick = onFormat,
            enabled = hasFormatter,
            shape = SundaysPalette.buttonShape,
        ) {
            Text("格式化")
        }
        // 调用方注入的自定义按钮 — 渲染在内置按钮之后
        actions()
    }
}

/**
 * 缓存 [SyntaxHighlighter] 实例（避免每次 recompose 创建）。
 *
 * 用法：`val highlighter = rememberCodeHighlighter(theme)`
 */
@Composable
fun rememberCodeHighlighter(theme: CodeEditorTheme): SyntaxHighlighter =
    remember(theme) { SyntaxHighlighter(theme.colors) }

/**
 * Compose [VisualTransformation] — 把原始字符串渲染为带 token 颜色的 [TransformedText]。
 *
 * 实现细节：
 * - 每次 [VisualTransformation.filter] 调用都重新 tokenize —— 这是 Compose 的设计：
 *   `filter` 在每次文本变化时被调用，所以确保 tokenize 足够快（O(N) 单次扫描）
 * - 使用 [AnnotatedString] 包装 + [TransformedText] 提供给 [BasicTextField] 渲染
 * - 使用 [OffsetMapping.Identity] 因为我们只改样式、不改字符位置 — 选择位置保持稳定
 */
private class CodeVisualTransformation(
    private val language: CodeLanguage?,
    private val highlighter: SyntaxHighlighter,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val toHighlight = text.text
        val annotated = if (language != null) {
            val tokens = try {
                language.tokenize(toHighlight)
            } catch (_: Throwable) {
                emptyList()
            }
            highlighter.highlight(toHighlight, tokens)
        } else {
            AnnotatedString(toHighlight)
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * 启动时一次性注册内置语言 + 格式化器。
 *
 * **调用时机**：建议在 Desktop App 的 `main()` 函数、`App()` Composable 顶层，
 * 或 KMP `commonMain` 初始化路径中调用一次。
 *
 * 重复调用是**幂等**的（registry 内部用 `synchronized` + 覆盖语义）。
 */
fun registerBuiltinEditors() {
    // SQL 家族：SqlFormatter.register() 一次注册全部方言档位（"sql" / "sql-mysql" / …）的语言 + formatter
    if (!CodeLanguageRegistry.contains(SqlDialectProfile.STANDARD.languageId)) {
        com.kxxnzstdsw.sundays.editor.formatter.SqlFormatter.register()
    }
    if (!CodeLanguageRegistry.contains("lua")) {
        CodeLanguageRegistry.register(LuaLanguage())
    }
    if (CodeFormatterRegistry.get("lua") == null) {
        com.kxxnzstdsw.sundays.editor.formatter.LuaFormatter.register()
    }
}

// ============================================================================
// IntelliJ Compose Multiplatform Preview Composables
// ============================================================================
//
// 这些 `@Preview` 函数可在 IDE（IntelliJ IDEA / Android Studio Hedgehog+）的
// Compose Multiplatform Preview 面板中直接渲染，**无需运行整个应用**。
//
// **使用方式**：
// 1. 安装 IDEA 插件 [Compose Multiplatform IDE Support](https://plugins.jetbrains.com/plugin/16541-compose-multiplatform-ide-support)
// 2. 打开 CodeEditor.kt，定位到 `@Preview` 函数上方的 gutter 图标（绿/蓝眼睛） → 点击预览
// 3. 编辑器会自动调用 `registerBuiltinEditors()` 注册语言 + formatter
//
// **预览用途**：
// - 验证新增 token 颜色后的视觉一致性
// - 验证新增语言的高亮样式
// - 验证主题切换（浅色 / 深色）
// - 验证行号显示效果

/** 演示样本：典型 SQL（关键字 + 字符串 + 注释 + 数字 + 标识符全有）。 */
private const val PREVIEW_SQL_SAMPLE = """
-- 复杂查询示例
SELECT u.id, u.name AS display_name, COUNT(o.id) AS order_count
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
WHERE u.created_at > '2024-01-01'
  AND u.age >= 18
  AND u.email LIKE '%@example.com'
GROUP BY u.id, u.name
HAVING COUNT(o.id) > 0
ORDER BY order_count DESC
LIMIT 100;
"""

/** 演示样本：典型 Lua（关键字 + 长字符串 + 函数定义 + 内置）。 */
private const val PREVIEW_LUA_SAMPLE = """
-- 造数脚本示例
local function generate_users(count)
  local users = {}
  for i = 1, count do
    insert('users', {
      name = 'user_' .. i,
      email = random_email(),
      age = random_int(18, 65),
      created_at = random_datetime('2024-01-01', '2024-12-31'),
      bio = [[multi
line
bio]],
      active = random_enum(true, false, true),
    })
  end
  return users
end

return generate_users
"""

/**
 * 多行样本 — 用于展示行号滚动同步（>20 行，需要滚动查看）。
 */
private const val PREVIEW_MULTILINE_SAMPLE = """
-- 长时间脚本（用于行号 + 滚动同步验证）
local function main()
  local users = {}
  local orders = {}
  local products = {}

  -- 1. 初始化用户表
  for i = 1, 50 do
    insert('users', {
      name = 'user_' .. i,
      email = random_email(),
      age = random_int(18, 65),
      active = random_enum(true, false),
    })
  end

  -- 2. 初始化产品表
  for i = 1, 100 do
    insert('products', {
      name = 'product_' .. i,
      price = random_float(1.0, 999.99),
      stock = random_int(0, 1000),
    })
  end

  -- 3. 生成订单
  for i = 1, 200 do
    insert('orders', {
      user_id = random_int(1, 50),
      product_id = random_int(1, 100),
      quantity = random_int(1, 10),
      amount = random_float(10.0, 5000.0),
    })
  end

  return { users = users, orders = orders, products = products }
end

return main
"""


// ============================================================================
// 预览脚手架
// ============================================================================

/**
 * 预览外壳 —— 注册内置语言/formatter + 套 [MaterialTheme] + 统一内边距。
 *
 * 每个 `@Preview` 只描述自己**特有**的差异（样本文本 / 语言 / 主题 / 高度约束），
 * 共同的外壳收敛到这里。
 */
@Composable
private fun EditorPreview(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = colorScheme) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}
/**
 * [CodeEditor] 浅色主题预览 — SQL 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / SQL / Light", widthDp = 600, heightDp = 280)
private fun CodeEditorSqlPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            theme = CodeEditorTheme.Light,
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * [CodeEditor] 浅色主题预览 — Lua 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / Lua / Light", widthDp = 600, heightDp = 280)
private fun CodeEditorLuaPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_LUA_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            theme = CodeEditorTheme.Light,
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * [CodeEditor] 深色主题预览 — SQL 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / SQL / Dark", widthDp = 600, heightDp = 280, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorSqlDarkPreview() {
    EditorPreview(darkColorScheme()) {
        CodeEditor(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            theme = CodeEditorTheme.Dark,
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * [CodeEditor] 深色主题预览 — Lua 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / Lua / Dark", widthDp = 600, heightDp = 280, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorLuaDarkPreview() {
    EditorPreview(darkColorScheme()) {
        CodeEditor(
            text = PREVIEW_LUA_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            theme = CodeEditorTheme.Dark,
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * [CodeEditorWithToolbar] 浅色预览 — SQL（带语言切换 + 格式化按钮 + 行号）。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / SQL", widthDp = 600, heightDp = 320)
private fun CodeEditorWithToolbarSqlPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            onLanguageChange = {},
            theme = CodeEditorTheme.Light,
        )
    }
}

/**
 * [CodeEditorWithToolbar] 深色预览 — Lua + 行号。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Lua / Dark", widthDp = 600, heightDp = 320, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorWithToolbarLuaDarkPreview() {
    EditorPreview(darkColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_LUA_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            onLanguageChange = {},
            theme = CodeEditorTheme.Dark,
        )
    }
}

/**
 * 一键浅/深色对比预览（@PreviewLightDark 自动生成两个预览：Light + Dark）。
 *
 * 用于**快速核对**：单一眼图标即可看到 SQL 在浅色和深色主题下的视觉效果（含行号）。
 */
@Composable
@PreviewLightDark
@Preview(name = "CodeEditor / SQL / LightDark", widthDp = 600, heightDp = 280)
private fun CodeEditorSqlLightDarkPreview() {
    EditorPreview(if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            theme = CodeEditorTheme.default(),
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * 纯文本模式预览 — `languageId = null` 时禁用高亮（退化为普通 BasicTextField）。
 *
 * 用于验证 highlighter 关闭时的 fallback 表现（含行号）。
 */
@Composable
@Preview(name = "CodeEditor / Plain Text", widthDp = 600, heightDp = 200)
private fun CodeEditorPlainTextPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = "// 此处 languageId=null，无语法高亮\n" +
                "SELECT id, name FROM users -- 普通文本模式",
            onTextChange = {},
            languageId = null,
            theme = CodeEditorTheme.Light,
            minLines = 5,
            maxLines = 10,
        )
    }
}

/**
 * 多行滚动同步预览 — 验证行号 gutter 与编辑器滚动完全同步。
 *
 * 使用 [PREVIEW_MULTILINE_SAMPLE]（~30 行）测试滚动时 gutter 跟随效果。
 */
@Composable
@Preview(name = "CodeEditor / Multi-line / Scroll Sync", widthDp = 600, heightDp = 300)
private fun CodeEditorMultilineScrollSyncPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_MULTILINE_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            theme = CodeEditorTheme.Light,
            minLines = 10,
            maxLines = 15,
        )
    }
}

/**
 * 关闭行号预览 — `showLineNumbers = false` 时不显示 gutter。
 */
@Composable
@Preview(name = "CodeEditor / No Line Numbers", widthDp = 600, heightDp = 240)
private fun CodeEditorNoLineNumbersPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            theme = CodeEditorTheme.Light,
            showLineNumbers = false,
            minLines = 8,
            maxLines = 12,
        )
    }
}

/**
 * 自定义 actions 预览 —— 演示调用方如何向工具栏注入额外按钮。
 *
 * 调用方通过 `actions: @Composable RowScope.() -> Unit` 插槽传入任意 [Button] / [IconButton] 等，
 * 它们会按声明顺序追加到内置按钮（语言切换 + 格式化）之后。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Custom Actions", widthDp = 700, heightDp = 320)
private fun CodeEditorWithToolbarCustomActionsPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            onLanguageChange = {},
            theme = CodeEditorTheme.Light,
            // 演示：调用方注入「执行」「清空」「复制」三个自定义按钮
            actions = {
                WinButton(onClick = {}) {
                    Text("执行 ▶")
                }
                WinButton(onClick = {}) {
                    Text("清空")
                }
                WinButton(onClick = {}) {
                    Text("复制")
                }
            },
        )
    }
}

/**
 * 隐藏语言切换器预览 —— `showLanguageSwitcher = false` 时不显示 [AssistChip] 下拉框，
 * 只显示「格式化」按钮（如果有 formatter）。
 *
 * 此场景适合"固定语言"的编辑器：调用方在实例化时直接指定 `languageId`，
 * `onLanguageChange` 可省略（默认空 lambda）。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Fixed Language", widthDp = 600, heightDp = 280)
private fun CodeEditorWithToolbarFixedLanguagePreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",  // 在实例化时直接指定，固定为 SQL
            // onLanguageChange 省略 —— 切换器已隐藏，不会被调用
            theme = CodeEditorTheme.Light,
            showLanguageSwitcher = false,  // 隐藏切换下拉框
        )
    }
}

/**
 * 隐藏语言切换器 + 自定义 actions 预览 —— 综合演示：
 * - 固定语言（实例化时指定，隐藏切换器）
 * - 自定义 actions 插槽注入「保存」「执行」
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Fixed Lang + Actions", widthDp = 700, heightDp = 280)
private fun CodeEditorWithToolbarFixedLangActionsPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_LUA_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            theme = CodeEditorTheme.Light,
            showLanguageSwitcher = false,
            actions = {
                WinButton(onClick = {}) { Text("保存") }
                WinButton(onClick = {}) { Text("执行 ▶") }
            },
        )
    }
}

/**
 * 编辑器右键菜单扩展预览 —— 演示调用方如何注入自定义右键菜单项。
 *
 * 通过 `contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit` 插槽传入任意
 * [DropdownMenuItem]，在编辑器任意位置右键时弹出。
 */
@Composable
@Preview(name = "CodeEditor / Context Menu", widthDp = 700, heightDp = 320)
private fun CodeEditorContextMenuPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditor(
            text = PREVIEW_SQL_SAMPLE,
            onTextChange = {},
            languageId = "sql",
            theme = CodeEditorTheme.Light,
            contextMenuItems = { payload ->
                DropdownMenuItem(
                    text = { Text("复制全部 (${payload?.text?.length ?: 0} 字符)") },
                    onClick = { },
                )
                DropdownMenuItem(
                    text = { Text("在编辑器中查找") },
                    onClick = { },
                )
                DropdownMenuItem(
                    text = { Text("语言：${payload?.languageId ?: "纯文本"}") },
                    onClick = { },
                    enabled = false,
                )
            }
        )
    }
}

/**
 * 工具栏 + 右键菜单综合预览 —— 在带工具栏的编辑器中演示右键菜单扩展。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Context Menu", widthDp = 700, heightDp = 320)
private fun CodeEditorWithToolbarContextMenuPreview() {
    EditorPreview(lightColorScheme()) {
        CodeEditorWithToolbar(
            text = PREVIEW_LUA_SAMPLE,
            onTextChange = {},
            languageId = "lua",
            onLanguageChange = {},
            theme = CodeEditorTheme.Light,
            contextMenuItems = { payload ->
                DropdownMenuItem(
                    text = { Text("运行 Lua 脚本 (${payload?.languageId ?: "?"})") },
                    onClick = { },
                )
                DropdownMenuItem(
                    text = { Text("保存到剪贴板") },
                    onClick = { },
                )
            }
        )
    }
}
