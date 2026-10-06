package com.kxxnzstdsw.sundays.table

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kxxnzstdsw.sundays.ui.LocalUiTokens
import com.kxxnzstdsw.sundays.ui.SelectionMode
import com.kxxnzstdsw.sundays.ui.isDarkMode

// ============================================================================
// 列定义 (TableColumn)
// ============================================================================

/**
 * 表格列定义 —— 描述表格中的一列。
 *
 * ## 关键字段
 * - [key]：与 [TableRow.cells] 中的 key 对应（数据绑定点）
 * - [header]：表头显示文本
 * - [width] / [weight]：宽度配置；[width] 优先（`null` = 使用 [weight]）
 * - [alignment]：单元格内文本对齐
 * - [formatter]：单元格值 → 显示字符串的转换器；默认 `toString()`
 *
 * ## 用法
 * ```kotlin
 * val columns = listOf(
 *     TableColumn(key = "id", header = "ID", width = 80.dp, alignment = TextAlign.End),
 *     TableColumn(key = "name", header = "姓名"),
 *     TableColumn(key = "age", header = "年龄", formatter = { (it as? Int)?.toString() ?: "—" }),
 *     TableColumn(key = "active", header = "状态", formatter = { if (it == true) "✓" else "✗" }),
 * )
 * ```
 */
data class TableColumn(
    val key: String,
    val header: String,
    val width: Dp? = null,
    val weight: Float = 1f,
    val alignment: TextAlign = TextAlign.Start,
    val formatter: (Any?) -> String = { it?.toString().orEmpty() },
)

// ============================================================================
// 行定义 (TableRow)
// ============================================================================

/**
 * 表格行 —— 包含主键 + 单元格映射。
 *
 * ## 设计要点
 * - [id]：**主键**（任意类型：Long / String / UUID 等），用于选中状态识别 / 数据库查找
 * - [cells]：key → value 映射，与 [TableColumn.key] 对应
 *
 * ## 用法
 * ```kotlin
 * // 简单构造
 * TableRow(id = 1L, cells = mapOf("id" to 1L, "name" to "Alice", "age" to 30))
 *
 * // 便捷构造（vararg pairs）
 * TableRow(id = 1L,
 *     "id" to 1L,
 *     "name" to "Alice",
 *     "age" to 30,
 * )
 *
 * // 主键用作数据库行标识
 * val dbRow = TableRow(id = dbResult.getLong("id"), cells = dbRowMap)
 * ```
 */
data class TableRow(
    val id: Any,
    val cells: Map<String, Any?>,
) {
    /**
     * 便捷构造器 — 接受可变参数 `Pair<String, Any?>`。
     */
    constructor(id: Any, vararg pairs: Pair<String, Any?>) : this(id, pairs.toMap())

    /**
     * 取指定列的值 — 自动应用 [TableColumn.formatter]。
     */
    fun formatted(column: TableColumn): String = column.formatter(cells[column.key])
}

// ============================================================================
// 分页大小 (PageSize)
// ============================================================================

/**
 * 表格分页大小 —— 枚举所有合法值。
 *
 * ## 枚举值
 * - `S10/20/50/100/200/300/500` — 固定分页大小
 * - `ALL` (`value = 0`) — 一次性渲染所有行（依赖 LazyColumn 虚拟滚动）
 *
 * ## 默认
 * - [DEFAULT] = [S20]
 */
enum class PageSize(val value: Int, val label: String) {
    S10(10, "10"),
    S20(20, "20"),
    S50(50, "50"),
    S100(100, "100"),
    S200(200, "200"),
    S300(300, "300"),
    S500(500, "500"),
    ALL(0, "全部"),
    ;

    /** 是否为「全部」模式（不分页）。 */
    val isAll: Boolean get() = value == 0

    companion object {
        val DEFAULT: PageSize = S20
        /** 所有可选分页值（按枚举顺序 = 升序 + 全部在末尾）。 */
        val ALL_VALUES: List<PageSize> = entries.toList()
    }
}

// ============================================================================
// 右键菜单状态 (ContextMenuState) —— 表格专用别名
// ============================================================================
//
// 表格的右键菜单 payload 是 [TableRow]；基于通用 [com.kxxnzstdsw.sundays.ui.ContextMenuState]
// 提供带类型的别名，便于调用方书写：
//
// ```kotlin
// val state = rememberContextMenuState()  // 类型已推断为 ContextMenuState<TableRow>
// ```

/** 表格专用 `ContextMenuState<TableRow>` 类型别名。 */
typealias ContextMenuState = com.kxxnzstdsw.sundays.ui.ContextMenuState<TableRow>

/** 创建并 [remember] 一个 `ContextMenuState<TableRow>`。 */
@Composable
fun rememberContextMenuState(): ContextMenuState =
    androidx.compose.runtime.remember { com.kxxnzstdsw.sundays.ui.ContextMenuState<TableRow>() }

// ============================================================================
// 表格主题 (DataTableTheme)
// ============================================================================

/**
 * 表格视觉主题。
 *
 * ## 字段
 * - [headerBackground]：表头背景色
 * - [headerText]：表头文字样式（颜色 / 字号 / 字重）
 * - [rowBackground]：普通行背景色
 * - [rowBackgroundAlt]：斑马纹行背景色（`null` = 不使用斑马纹）
 * - [rowBackgroundSelected]：选中行背景色
 * - [cellText]：单元格普通文字样式
 * - [cellTextSelected]：选中行单元格文字样式（`null` = 用 [cellText]）
 * - [borderColor]：行 / 列分隔线颜色
 * - [paginationBackground]：分页栏背景色
 */
data class DataTableTheme(
    val headerBackground: Color,
    val headerText: TextStyle,
    val rowBackground: Color,
    val rowBackgroundAlt: Color?,
    val rowBackgroundSelected: Color,
    val cellText: TextStyle,
    val cellTextSelected: TextStyle?,
    val borderColor: Color,
    val paginationBackground: Color,
    /**
     * 单元格**编辑态**的背景色。
     *
     * 单独一个字段而不是复用 [rowBackground] / [rowBackgroundSelected]：编辑框只有一格宽，
     * 若与选中行的底色相同，用户会分不清「这一格正在编辑」还是「这一行被选中」—— 而这两件
     * 事的后果完全不同（一个还没落盘，一个已选中待查看）。
     */
    val cellEditingBackground: Color = rowBackground,
) {
    companion object {
        /**
         * 跟随应用主题的表格配色 —— **这是 [DataTable] 的实际默认值**。
         *
         * ## 为什么要从硬编码常量改成跟随配色
         *
         * 原先 `default()` 只按**系统**明暗在 [Light] / [Dark] 两个**写死色值**的
         * 常量间二选一，由此产生两个问题：
         *
         * 1. **无视用户选的明暗档**。`AppearanceState` 允许强制「始终浅色 / 始终深色」，
         *    而这里看的是系统设置 —— 用户强制浅色、系统是深色时，表格会与整个界面相反。
         * 2. **无视配色主题**。五套配色（含 Win2000 / WinXP 两套复古）下表格都是同一块
         *    `#FFFFFF` + `#E3F2FD` 淡蓝选行的现代表格，与复古主题并排时尤其刺眼。
         *
         * 复古档另有三处专门处理：
         * - **选行反色**：用 `primary` 实心填充 + `onPrimary` 文字（经典 Win 的列表选中即反蓝）。
         * - **取消斑马纹**：`rowBackgroundAlt = null`。Win98/2000/XP 的列表视图都没有隔行
         *   底色，斑马纹是现代表格的标志；在信息密集的行里它还会干扰跨行读数。
         * - **表头用面角色**而非更亮的档，凹陷感交给表头下方的蚀刻分割线表达。
         */
        @Composable
        @ReadOnlyComposable
        fun themed(scheme: ColorScheme = MaterialTheme.colorScheme): DataTableTheme {
            val tokens = LocalUiTokens.current
            // ⚠️ 现代档**必须原样返回** Dark / Light —— 这两套的行底 / 表头 / 选行色是
            // 独立调过的（表头比正文更亮形成凹槽感、选行 `#E3F2FD` 是淡蓝而非主色染）。
            // 曾一度在这里统一改成从 `ColorScheme` 取色，结果现代三套主题的表格整体变色。
            // ⚠️「原样返回常量」约束的是**颜色**，不是「谁决定选哪一套」。选档位读 [isDarkMode]
            //（应用生效档位）而不是 `isSystemInDarkTheme()`（系统设置）—— 后者与用户选的
            // 「始终浅色 / 始终深色」无关，一页浅色界面里嵌一块深色表格，会被当成区域损坏。
            if (!tokens.isClassic) return if (isDarkMode) Dark else Light

            return DataTableTheme(
                headerBackground = scheme.surfaceVariant,
                headerText = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                ),
                rowBackground = scheme.surface,
                rowBackgroundAlt = if (tokens.zebraRows) scheme.surface else null,
                rowBackgroundSelected = if (tokens.selection == SelectionMode.INVERTED) scheme.primary else scheme.primaryContainer,
                cellText = TextStyle(fontSize = 13.sp, color = scheme.onSurface),
                cellTextSelected = if (tokens.selection == SelectionMode.INVERTED) {
                    TextStyle(fontSize = 13.sp, color = scheme.onPrimary)
                } else {
                    TextStyle(fontSize = 13.sp, color = scheme.onPrimaryContainer)
                },
                borderColor = scheme.outlineVariant,
                paginationBackground = scheme.surfaceVariant,
            )
        }

        /**
         * 固定色值的浅 / 深配色常量。
         *
         * ⚠️ 它们**不跟随应用配色**，仅供需要与宿主外观解耦的场景（如导出预览、截图）使用。
         * 应用内的表格请用 [themed]。
         */
        @Deprecated(
            "不跟随应用配色，Retro 主题下会与界面割裂；应用内请改用 themed()",
            ReplaceWith("DataTableTheme.themed()"),
        )
        val Light: DataTableTheme = DataTableTheme(
            headerBackground = Color(0xFFF5F5F5),
            headerText = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF333333),
            ),
            rowBackground = Color(0xFFFFFFFF),
            rowBackgroundAlt = Color(0xFFFAFAFA),
            rowBackgroundSelected = Color(0xFFE3F2FD),
            cellText = TextStyle(fontSize = 13.sp, color = Color(0xFF000000)),
            cellTextSelected = null,
            borderColor = Color(0xFFE0E0E0),
            paginationBackground = Color(0xFFF5F5F5),
        )

        /**
         * 默认深色主题 — Intellij Darcula 风格，底色改用与 `SundaysPalette` 深色表面一致的
         * 蓝灰炭色系（与 `CodeEditorTheme.Dark` 同源）。
         *
         * 表头 / 分页栏用 `surfaceVariant` 档而非 Darcula 的 `#3C3F41`：深色界面里表头应当
         * 比正文**更亮**（凹槽感）才读得出是分区头；选行底 `#2C425E` 对应新的亮靛蓝主色，
         * 选中文字转白以保住对比度。
         */
        val Dark: DataTableTheme = DataTableTheme(
            headerBackground = Color(0xFF2C3240),
            headerText = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFBFC7D4),
            ),
            rowBackground = Color(0xFF232833),
            rowBackgroundAlt = Color(0xFF262B36),
            rowBackgroundSelected = Color(0xFF2C425E),
            cellText = TextStyle(fontSize = 13.sp, color = Color(0xFFCBD1DC)),
            cellTextSelected = TextStyle(fontSize = 13.sp, color = Color(0xFFFFFFFF)),
            borderColor = Color(0xFF333A48),
            paginationBackground = Color(0xFF2C3240),
        )

        /**
         * 默认主题 — 跟随应用配色与明暗档（见 [themed]）。
         * 必须在 `@Composable` 上下文调用。
         */
        @Composable
        @ReadOnlyComposable
        fun default(): DataTableTheme = themed()
    }
}