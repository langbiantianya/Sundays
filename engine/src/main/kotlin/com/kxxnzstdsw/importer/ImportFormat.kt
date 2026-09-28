package com.kxxnzstdsw.importer

/**
 * 导入文件格式 —— 取值来自 proto 字段 `ImportRunRequest.format`（字符串）。
 *
 * 用枚举而不是到处散落字符串常量，是为了让「支持哪些格式」这件事只有一个真相来源：
 * [parse] 的报错信息、UI 的下拉选项、这里的 entries 都从这里派生。
 */
enum class ImportFormat {

    /** 逗号分隔文本，支持引号包裹与 `""` 转义 */
    CSV,

    /** JSON Lines：每行一个独立的 JSON 对象 */
    JSON_LINES;

    companion object {

        /** 面向用户的支持值列表（用于报错与文档） */
        val supported: List<String> = entries.map { it.name }

        /**
         * 解析 proto 传来的格式字符串，大小写不敏感（`json_lines` / `JSON_LINES` 等价）。
         *
         * 空串与未知值一律抛 [IllegalArgumentException] 并列出全部支持值：
         * 格式猜错会让整个文件以错误的解析方式入库，属于必须让调用方立刻看见的错误，
         * 不做「空串默认 CSV」这种静默兜底。
         */
        fun parse(raw: String): ImportFormat {
            val key = raw.trim().uppercase()
            return entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException(
                    "不支持的导入格式: '$raw'，支持的格式: ${supported.joinToString(", ")}"
                )
        }
    }
}
