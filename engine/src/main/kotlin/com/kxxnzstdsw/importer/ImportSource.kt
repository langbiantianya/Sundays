package com.kxxnzstdsw.importer

/**
 * 导入读取器接口 —— 与 export 侧的 [com.kxxnzstdsw.export.ExportWriter] 对称的读方向抽象。
 *
 * 与写出侧的差异只有一处，但这一处是刻意的：这里用 [Iterator] 而不是 [Sequence]。
 * 导入通常是「边读边批量 insert」，引擎可能因为目标表写入失败、用户取消或分页预览
 * 而提前停止；用 Iterator 可以在停止的瞬间丢掉迭代器并 [close] 释放文件句柄，
 * Sequence 的惰性链反而容易在协程里被挂住、迟迟不释放底层 Reader。
 */
interface ImportSource : AutoCloseable {

    /**
     * 目标列顺序。CSV 有表头时来自表头行；JSON_LINES 取首行出现过的键序。
     */
    val columns: List<String>

    /**
     * 逐行读取。每行是「列名 -> 原始字符串值」。空行应被跳过。
     */
    fun rows(): Iterator<Map<String, String>>

    override fun close() {}
}

/**
 * 把逐行读取包装成 [Sequence]，方便在表达式里做 map / take / toList 之类的组合。
 *
 * 注意这只是便利方法：底层仍然是单遍流式读取，重复遍历同一 Sequence 的行为
 * 与反复调用 [ImportSource.rows] 相同（见各实现的说明）。
 */
fun ImportSource.rowSequence(): Sequence<Map<String, String>> = Sequence { rows() }
