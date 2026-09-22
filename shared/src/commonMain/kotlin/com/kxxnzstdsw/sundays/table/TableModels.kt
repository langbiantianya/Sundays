package com.kxxnzstdsw.sundays.table

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

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

        fun fromInt(value: Int): PageSize = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

// ============================================================================
// 右键菜单状态 (ContextMenuState) —— 表格专用别名
// ============================================================================
//
// 表格的右键菜单 payload 是 [TableRow]；基于通用 [com.kxxnzstdsw.Sundays.ui.ContextMenuState]
// 提供带类型的别名，便于调用方书写：
//
// ```kotlin
// val state = rememberTableContextMenuState()  // 类型已推断为 ContextMenuState<TableRow>
// ```

/** 表格专用 `ContextMenuState<TableRow>` 类型别名。 */
typealias ContextMenuState = com.kxxnzstdsw.sundays.ui.ContextMenuState<TableRow>

/** 创建并 [remember] 一个 `ContextMenuState<TableRow>`。 */
@Composable
fun rememberContextMenuState(): ContextMenuState =
    androidx.compose.runtime.remember { com.kxxnzstdsw.sundays.ui.ContextMenuState<TableRow>() }

// ============================================================================
// 旧版兼容 — 早期 API 中 [ContextMenuState] 暴露 [targetRow] 字段，
// 新版使用泛型 [payload]，这里提供兼容属性 / 旧字段。
// ============================================================================

/**
 * 旧版 [targetRow] 字段的兼容别名 —— 实际为 `payload as? TableRow`。
 *
 * 已迁移到 [payload]，保留旧字段以便现有调用方（如 [DataTable] 的 [contextMenuItems]
 * 槽位仍通过 `state.targetRow` 读取）无需修改。
 */
val ContextMenuState.targetRow: TableRow?
    get() = payload

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
) {
    companion object {
        /** 默认浅色主题 — Material Design 风格。 */
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

        /** 默认深色主题 — Intellij Darcula 风格。 */
        val Dark: DataTableTheme = DataTableTheme(
            headerBackground = Color(0xFF3C3F41),
            headerText = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFBBBBBB),
            ),
            rowBackground = Color(0xFF2B2B2B),
            rowBackgroundAlt = Color(0xFF313335),
            rowBackgroundSelected = Color(0xFF214283),
            cellText = TextStyle(fontSize = 13.sp, color = Color(0xFFA9B7C6)),
            cellTextSelected = TextStyle(fontSize = 13.sp, color = Color(0xFFFFFFFF)),
            borderColor = Color(0xFF3C3F41),
            paginationBackground = Color(0xFF3C3F41),
        )

        /**
         * 默认主题 — 根据当前系统设置自动选择。
         * 必须在 `@Composable` 上下文调用（依赖 [isSystemInDarkTheme]）。
         */
        @Composable
        @ReadOnlyComposable
        fun default(): DataTableTheme =
            if (isSystemInDarkTheme()) Dark else Light
    }
}