package com.kxxnzstdsw.Sundays.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.Sundays.editor.CodeLanguage
import com.kxxnzstdsw.Sundays.editor.CodeLanguageRegistry
import com.kxxnzstdsw.Sundays.editor.EditorContextMenuPayload
import com.kxxnzstdsw.Sundays.editor.EditorContextMenuState
import com.kxxnzstdsw.Sundays.editor.SyntaxHighlighter
import com.kxxnzstdsw.Sundays.editor.formatter.CodeFormatterRegistry
import com.kxxnzstdsw.Sundays.editor.language.LuaLanguage
import com.kxxnzstdsw.Sundays.editor.language.SqlLanguage
import com.kxxnzstdsw.Sundays.editor.rememberEditorContextMenuState
import com.kxxnzstdsw.Sundays.ui.onRightClick

/**
 * 可扩展的代码编辑器 Composable —— 支持语法高亮、格式化、语言切换、行号显示。
 *
 * ## 设计要点
 *
 * - **解耦语言与 UI**：通过 [CodeLanguageRegistry] 查找语言，新增语言只需 `registry.register(...)`
 * - **tokenize 缓存**：使用 [rememberCodeHighlighter] 复用 [SyntaxHighlighter]，避免每次 recompose 创建
 * - **deferred 格式化**：[text] 与 [languageId] 变化时**异步** tokenize + 高亮，不阻塞 UI 帧
 * - **共享 ScrollState**：行号 gutter 与代码编辑区共用同一个 [ScrollState]，
 *   保证两者的垂直滚动完全同步（用户滚编辑器时行号同步移动，反之亦然）
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
 * - 新增 [TokenType] → 只需在 [SyntaxHighlighter.DefaultLightColors] / [DarkColors] 追加键值对
 * - 新增语言 → 实现 [CodeLanguage] + 注册；编辑器零修改即可显示
 * - 新增 formatter → 实现 [CodeFormatter] + 注册；工具栏"格式化"按钮自动启用
 * - 替换主题 → 提供自定义 [CodeEditorTheme]
 *
 * @param text 编辑器文本内容
 * @param onTextChange 文本变化回调（**不要在内部直接调用 `text = newText`** — 让上层管理 state）
 * @param languageId 当前语言 ID（传入 `null` 表示禁用高亮 — 纯文本模式）
 * @param modifier Compose modifier
 * @param theme 编辑器主题（默认 [CodeEditorTheme.default]）
 * @param showLineNumbers 是否显示行号（默认 true — IDE 习惯）
 * @param minLines 最小显示行数（不足时空行也填充）
 * @param maxLines 最大显示行数（超过则内部滚动）；**默认 `null`（不限制）** —— 编辑器会填充
 *   父容器剩余高度，不会超过父容器；调用方显式传入整数才启用高度上限
 * @param contextMenuState 右键菜单状态；通常用 [rememberEditorContextMenuState] 创建
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；
 *   payload 通过 [EditorContextMenuPayload]（包含当前 text + languageId）传入
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
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
) {
    val language = remember(languageId) {
        languageId?.let { CodeLanguageRegistry.get(it) }
    }
    val highlighter = rememberCodeHighlighter(theme)
    var fieldValue by remember { mutableStateOf(TextFieldValue(text = text, selection = androidx.compose.ui.text.TextRange.Zero)) }

    // 外部 text 变化（重置、格式化）需要同步到内部 TextFieldValue
    LaunchedEffect(text) {
        if (fieldValue.text != text) {
            fieldValue = fieldValue.copy(text = text)
        }
    }

    val transformation = remember(language, highlighter, fieldValue.text) {
        CodeVisualTransformation(fieldValue.text, language, highlighter)
    }

    val minHeight = (theme.textStyle.fontSize.value * minLines + 16).dp
    val maxHeight = maxLines?.let { (theme.textStyle.fontSize.value * it + 16).dp }

    // 共享 ScrollState — gutter 与 BasicTextField 共同放在 verticalScroll 容器内，
    // 二者的滚动位置由同一个 ScrollState 统一管理，保证行号与代码完全同步
    val sharedScrollState = rememberScrollState()

    // 计算行数（按 \n 分割 + 1，至少为 1）
    val lineCount = remember(text) {
        if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
    }

    // 高度策略：
    // - [maxLines] 已设置 → 显式上限（高度介于 [minHeight] 与 [maxHeight]）
    // - [maxLines] 未设置（默认 `null`）→ 不施加高度上限，填充父容器剩余高度
    //   （`fillMaxHeight()` 使编辑器在父容器剩余空间内自动展开；
    //    配合 [verticalScroll]，内容超过可用高度时仍可滚动而不溢出父容器）
    val sizeModifier = if (maxHeight != null) {
        Modifier.heightIn(min = minHeight, max = maxHeight)
    } else {
        Modifier.fillMaxHeight().heightIn(min = minHeight)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.backgroundColor, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .then(sizeModifier)
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
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                fieldValue = newValue
                if (newValue.text != text) onTextChange(newValue.text)
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = theme.textStyle,
            visualTransformation = transformation,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.textStyle.color),
        )
    }

    // 右键菜单 Popup —— 全局显示，根据 [contextMenuState] 渲染
    if (contextMenuState.visible) {
        val density = LocalDensity.current
        val menuOffset = androidx.compose.ui.unit.DpOffset(
            x = with(density) { contextMenuState.position.x.toDp() },
            y = with(density) { contextMenuState.position.y.toDp() },
        )
        Box {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { contextMenuState.dismiss() },
                offset = menuOffset,
            ) {
                contextMenuItems(contextMenuState.payload)
            }
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
 * - **滚动同步**：与编辑器共享 [ScrollState] — 用户滚动编辑器时行号同步移动
 * - **配色**：背景用 [CodeEditorTheme.gutterColor]；文字用主题文字色 + alpha 降低，使其"低调"但不消失
 * - **行高**：使用与编辑器相同的 [TextStyle]，自动匹配编辑器行高（含自定义 lineHeight）
 *
 * @param lineCount 总行数
 * @param theme 编辑器主题
 * @param scrollState 与编辑器共享的滚动状态
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
 *     actions = { Button(onClick = { execute(sql) }) { Text("执行") } },
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
 *     actions = { Button(onClick = { save() }) { Text("保存") } },
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
 * @param actions 自定义操作按钮插槽 — 渲染在内置按钮之后
 * @param contextMenuState 右键菜单状态；通常用 [rememberEditorContextMenuState] 创建
 * @param contextMenuItems 右键菜单插槽 —— 在 [DropdownMenuItem] 内调用；
 *   payload 通过 [EditorContextMenuPayload]（包含当前 text + languageId）传入
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
    actions: @Composable RowScope.() -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    contextMenuState: EditorContextMenuState = rememberEditorContextMenuState(),
    contextMenuItems: @Composable (EditorContextMenuPayload?) -> Unit = {},
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
            contextMenuState = contextMenuState,
            contextMenuItems = contextMenuItems,
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
        Button(
            onClick = onFormat,
            enabled = hasFormatter,
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
    private val source: String,
    private val language: CodeLanguage?,
    private val highlighter: SyntaxHighlighter,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val toHighlight = if (text.text == source) source else text.text
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
    if (!CodeLanguageRegistry.contains("sql")) {
        CodeLanguageRegistry.register(SqlLanguage())
    }
    if (!CodeLanguageRegistry.contains("lua")) {
        CodeLanguageRegistry.register(LuaLanguage())
    }
    if (CodeFormatterRegistry.get("sql") == null) {
        com.kxxnzstdsw.Sundays.editor.formatter.SqlFormatter.register()
    }
    if (CodeFormatterRegistry.get("lua") == null) {
        com.kxxnzstdsw.Sundays.editor.formatter.LuaFormatter.register()
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

/**
 * [CodeEditor] 浅色主题预览 — SQL 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / SQL / Light", widthDp = 600, heightDp = 280)
private fun CodeEditorSqlPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * [CodeEditor] 浅色主题预览 — Lua 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / Lua / Light", widthDp = 600, heightDp = 280)
private fun CodeEditorLuaPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * [CodeEditor] 深色主题预览 — SQL 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / SQL / Dark", widthDp = 600, heightDp = 280, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorSqlDarkPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * [CodeEditor] 深色主题预览 — Lua 样本 + 行号。
 */
@Composable
@Preview(name = "CodeEditor / Lua / Dark", widthDp = 600, heightDp = 280, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorLuaDarkPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * [CodeEditorWithToolbar] 浅色预览 — SQL（带语言切换 + 格式化按钮 + 行号）。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / SQL", widthDp = 600, heightDp = 320)
private fun CodeEditorWithToolbarSqlPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
            CodeEditorWithToolbar(
                text = PREVIEW_SQL_SAMPLE,
                onTextChange = {},
                languageId = "sql",
                onLanguageChange = {},
                theme = CodeEditorTheme.Light,
            )
        }
    }
}

/**
 * [CodeEditorWithToolbar] 深色预览 — Lua + 行号。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Lua / Dark", widthDp = 600, heightDp = 320, backgroundColor = 0xFF2B2B2B)
private fun CodeEditorWithToolbarLuaDarkPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
            CodeEditorWithToolbar(
                text = PREVIEW_LUA_SAMPLE,
                onTextChange = {},
                languageId = "lua",
                onLanguageChange = {},
                theme = CodeEditorTheme.Dark,
            )
        }
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
    registerBuiltinEditors()
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * 纯文本模式预览 — `languageId = null` 时禁用高亮（退化为普通 BasicTextField）。
 *
 * 用于验证 highlighter 关闭时的 fallback 表现（含行号）。
 */
@Composable
@Preview(name = "CodeEditor / Plain Text", widthDp = 600, heightDp = 200)
private fun CodeEditorPlainTextPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * 多行滚动同步预览 — 验证行号 gutter 与编辑器滚动完全同步。
 *
 * 使用 [PREVIEW_MULTILINE_SAMPLE]（~30 行）测试滚动时 gutter 跟随效果。
 */
@Composable
@Preview(name = "CodeEditor / Multi-line / Scroll Sync", widthDp = 600, heightDp = 300)
private fun CodeEditorMultilineScrollSyncPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * 关闭行号预览 — `showLineNumbers = false` 时不显示 gutter。
 */
@Composable
@Preview(name = "CodeEditor / No Line Numbers", widthDp = 600, heightDp = 240)
private fun CodeEditorNoLineNumbersPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
            CodeEditorWithToolbar(
                text = PREVIEW_SQL_SAMPLE,
                onTextChange = {},
                languageId = "sql",
                onLanguageChange = {},
                theme = CodeEditorTheme.Light,
                // 演示：调用方注入「执行」「清空」「复制」三个自定义按钮
                actions = {
                    Button(onClick = {}) {
                        Text("执行 ▶")
                    }
                    Button(onClick = {}) {
                        Text("清空")
                    }
                    Button(onClick = {}) {
                        Text("复制")
                    }
                },
            )
        }
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
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * 隐藏语言切换器 + 自定义 actions 预览 —— 综合演示：
 * - 固定语言（实例化时指定，隐藏切换器）
 * - 自定义 actions 插槽注入「保存」「执行」
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Fixed Lang + Actions", widthDp = 700, heightDp = 280)
private fun CodeEditorWithToolbarFixedLangActionsPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
            CodeEditorWithToolbar(
                text = PREVIEW_LUA_SAMPLE,
                onTextChange = {},
                languageId = "lua",
                theme = CodeEditorTheme.Light,
                showLanguageSwitcher = false,
                actions = {
                    Button(onClick = {}) { Text("保存") }
                    Button(onClick = {}) { Text("执行 ▶") }
                },
            )
        }
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
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}

/**
 * 工具栏 + 右键菜单综合预览 —— 在带工具栏的编辑器中演示右键菜单扩展。
 */
@Composable
@Preview(name = "CodeEditorWithToolbar / Context Menu", widthDp = 700, heightDp = 320)
private fun CodeEditorWithToolbarContextMenuPreview() {
    registerBuiltinEditors()
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(modifier = Modifier.padding(16.dp)) {
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
}