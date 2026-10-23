/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.analysis

import org.duckdb.DuckDBColumnType
import org.duckdb.DuckDBDataChunkWriter
import org.duckdb.DuckDBLogicalType
import org.duckdb.DuckDBTableFunction
import org.duckdb.DuckDBTableFunctionBindInfo
import org.duckdb.DuckDBTableFunctionCallInfo
import org.duckdb.DuckDBTableFunctionInitInfo
import org.duckdb.DuckDBWritableVector
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

/*
 * DuckDB 的**纯 Java 表函数** —— 让 DuckDB 直接把一个 JDBC 数据源当成一张表。
 *
 * ## 它替代了什么
 *
 * 早先的拉取链路是「远端 `EXPORT` → 临时 CSV → 工作区 `IMPORT`」。
 * 那条路要落一次盘、经一次 CSV 的编码往返，还踩过两个坑：
 * CSV 把 NULL 写成空串（于是导入端整行失败）、以及临时文件的清理。
 *
 * 而 DuckDB 自己就能当**联邦查询层**：它能直接把远端的行读进来。
 * 本文件就是那条路的实现 —— 把远端的一条 JDBC 查询注册成 DuckDB 的一个表函数，
 * 于是「拉一张远端表」在引擎侧就是一句：
 *
 * ```sql
 * CREATE TABLE staging_x AS SELECT * FROM jdbc_scan('scan-42')
 * ```
 *
 * 没有临时文件、没有 CSV、没有编码往返。
 *
 * ## 为什么用**纯 Java** 表函数，而不是 DuckDB 的原生扩展
 *
 * 原生扫描器（`mysql_scanner` / `postgres_scanner`）也有，
 * 而且它们**随 JDBC 驱动一起**发布（静态链接在驱动本体里，见
 * [NativeExtensionScanner] 的文件头）—— 所以「装不上扩展」从来不是理由。
 *
 * 真正的理由是**覆盖面**：原生扫描器只覆盖 MySQL / PostgreSQL，
 * 而纯 Java 表函数只要求「有 JDBC 驱动」—— SQLite、H2，
 * 将来的 Hive（只要 `hive-jdbc` 在手上）都能走同一条路。
 *
 * 原生那条路作为**可选加速**另开一扇门（见 [NativeExtensionScanner]），
 * 但**默认**走的是这条 —— 因为它的类型映射是**我们自己定的**，
 * 跨机器一致。
 *
 * ## 安全边界：远端**只读**是由「构造」保证的
 *
 * 本函数**不接受**调用方传入的 SQL —— 它只执行引擎自己拼的那一句
 * `SELECT * FROM <命名空间>.<表>`。也就是说：
 * 即使这条链路上的某处有 bug，传进来的也只可能是「选哪张表」，
 * 不可能是「执行什么语句」。
 *
 * 这比「会话级只读开关」更强：开关靠**每个写入口**都记得检查，
 * 而这里压根没有写入口。
 */

/**
 * 一次扫描的上下文 —— **每次拉取现给一份**。
 *
 * ## 为什么不能把它塞进「注册好的那个函数」里
 *
 * 表函数注册在 DuckDB **实例**上，生命周期比一次拉取长得多。
 * 而它闭包里的东西会**过期**：
 *
 * - 闭包里的 `Connection` —— 拉取一结束就归还给连接池了，
 *   于是「同一张表拉第二次」会撞上 `Connection is closed`（实测）；
 * - 闭包里的进度回调 —— 第二次拉取的进度会报到**第一次**那个回调上去。
 *
 * 所以上下文必须**每次拉取现给**，而表函数只在**绑定的那一刻**取走它
 * （绑定数据里存的是那一批的具体东西：结果集 + 列映射 + 回调）。
 *
 * 「这次是哪一个上下文」的凭据是一个**句柄**（字符串），
 * 通过 SQL 参数传给表函数：`SELECT * FROM jdbc_scan('scan-42')`。
 * 句柄由 [JdbcScanRegistry] 保管 —— 参数里能传的只有字符串，
 * 真正的东西（连接、回调）留在引擎这一侧。
 *
 * @param connection 远端连接 —— **由调用方借出并负责归还**（本类不关它）。
 *   理由：连接的归还时机必须由**发起方**决定 —— 只有它知道扫描什么时候真的结束了
 *   （取消 / 异常都可能让扫描半途而废，而那时调用方还握着 finally）。
 * @param selectSql 要执行的 SELECT —— **必须**由调用方按「只读」的形状拼好。
 * @param onChunk 每读完一块的回调（累计行数）
 */
internal class ScanContext(
    val connection: Connection,
    val selectSql: String,
    val onChunk: (Long) -> Unit,
)

/** 联邦扫描的列 —— JDBC 类型 → DuckDB 类型的映射结果。 */
internal enum class ScanColumnKind {
    BOOLEAN,
    INT32,
    INT64,
    DECIMAL,
    DOUBLE,
    FLOAT,
    TIMESTAMP,
    DATE,
    VARCHAR,
}

/**
 * 一列的映射结果。
 *
 * @param name 列名（用远端返回的标签，`getColumnLabel`）
 * @param kind 该列落到 DuckDB 的哪种类型
 * @param precision 精度（仅 [ScanColumnKind.DECIMAL] 用）
 * @param scale 标度（仅 [ScanColumnKind.DECIMAL] 用）
 */
internal data class ScanColumn(
    val name: String,
    val kind: ScanColumnKind,
    val precision: Int = 0,
    val scale: Int = 0,
)

/** `bind` 阶段交给 `apply` 的东西 —— 结果集 + 列映射 + 进度回调。 */
internal class JdbcScanBindData(
    val resultSet: ResultSet,
    val columns: List<ScanColumn>,
    /** 每读完一块回调一次（参数是累计已读行数）。 */
    val onChunk: (Long) -> Unit,
) {
    /** 累计已读行数。 */
    var rowsRead: Long = 0
}

/** `init` 阶段的产物 —— 目前是空的（只是为了把泛型填满）。 */
internal class JdbcScanInitData

/**
 * 把一条 JDBC 查询注册成 DuckDB 的表函数。
 *
 * ## 这个函数是**无状态**的
 *
 * 它**不**持有连接、SQL、回调 —— 那些都是**每次拉取**的东西，
 * 由 SQL 参数里的**句柄**指出来（见 [ScanContext]）。
 *
 * 这么做的原因：注册在 DuckDB 实例上的函数**活得比一次拉取久**，
 * 闭包里的连接和回调会过期（第一次拉取一结束，连接就还回池了 ——
 * 于是「同一张表拉第二次」会撞上 `Connection is closed`，实测）。
 *
 * 无状态还带来第二个好处：**注册一次就够**。
 * 实例的目录里永远只有一条 `jdbc_scan`，不会随着拉的次数堆积；
 * 凭据也不在注册物里（每次现给），所以**改密码不需要重新注册**。
 *
 * @param resolveContext 句柄 → 上下文（[JdbcScanRegistry] 管着那张表）
 */
internal class JdbcScanTableFunction(
    private val resolveContext: (String) -> ScanContext,
) : DuckDBTableFunction<JdbcScanBindData, JdbcScanInitData, Unit> {
    override fun bind(info: DuckDBTableFunctionBindInfo): JdbcScanBindData {
        // 参数索引是 **0 基**的（实测：getParameter(0) 取到第一个位置参数）。
        val parameter = info.getParameter(0)
        val handle =
            try {
                parameter.getString()
            } finally {
                parameter.close()
            }
        val context = resolveContext(handle)
        val statement = context.connection.createStatement()
        // fetchSize：一次从远端取多少行。
        // PG 等驱动据此**流式**取数（网络往返次数少、内存占用可控）；
        // MySQL 驱动默认把整个结果集materialize到客户端内存（见 AnalysisHandler 的说明），
        // 但**不会**落盘 —— 那正是我们要替掉的那一段。
        statement.fetchSize = FETCH_SIZE
        val resultSet = statement.executeQuery(context.selectSql)
        val meta = resultSet.metaData
        val columns = (1..meta.columnCount).map { scanColumnOf(meta, it) }
        columns.forEach { column ->
            when (val type = duckDbTypeOf(column)) {
                is DuckDBColumnType -> info.addResultColumn(column.name, type)
                is DuckDBLogicalType -> info.addResultColumn(column.name, type)
                else -> info.addResultColumn(column.name, DuckDBColumnType.VARCHAR)
            }
        }
        return JdbcScanBindData(resultSet, columns, context.onChunk)
    }

    override fun init(info: DuckDBTableFunctionInitInfo): JdbcScanInitData {
        // **单线程**读：一个 ResultSet 不能被两个线程同时推进。
        //
        // DuckDB 默认会按 CPU 数并行调用 `apply`；不压到 1 的话，
        // 两个线程会同时 `next()` 同一个游标 —— 结果是行数对不上、还偶发报错。
        // 这里的并行度换不来什么：真正的瓶颈在**远端**的网络往返上，
        // 而那本来就是串行的。
        info.setMaxThreads(1L)
        return JdbcScanInitData()
    }

    override fun apply(
        call: DuckDBTableFunctionCallInfo,
        writer: DuckDBDataChunkWriter,
    ): Long {
        val data = call.getBindData<JdbcScanBindData>()
        val capacity = writer.capacity()
        val vectors = data.columns.indices.map { writer.vector(it.toLong()) }
        var written = 0L
        while (written < capacity) {
            if (!data.resultSet.next()) break
            val row = written
            data.columns.forEachIndexed { index, column ->
                writeCell(column, data.resultSet, index + 1, vectors[index], row)
            }
            written++
        }
        data.rowsRead += written
        if (written > 0) {
            data.onChunk(data.rowsRead)
        }
        return written
    }

    companion object {
        /** 一次从远端取多少行。 */
        private const val FETCH_SIZE = 1000

        /**
         * 把一个 JDBC 列映射成 DuckDB 列。
         *
         * ## 为什么要这么挑
         *
         * 判据只有一条：**宁可类型粗一点，也不要让整次拉取失败**。
         * 一个装不下的值会让 DuckDB 在写入时报错，而那意味着**这张表整张拉不下来**。
         * 所以：
         *
         * - 整数一律**按实际宽度**选：MySQL 的 `INT UNSIGNED`（最大 42 亿）
         *   装不进 DuckDB 的 32 位 `INTEGER`（最大 21 亿）—— 那会让整次拉取**失败**，
         *   所以无符号的 32 位整数落到 64 位；无符号的 64 位整数（最大 1.8e19）
         *   装不进有符号 64 位，于是落到 `DECIMAL(20, 0)`（20 位十进制，够用）。
         * - `DECIMAL` 只在**精度落在 DuckDB 的范围里**（1..38）时才保留精度；
         *   否则落成文本 —— 宁可让下游用 `TRY_CAST` 建模，也不要在拉取时炸掉。
         * - `TIME` / `TIMESTAMP WITH TIME ZONE` 落成文本：DuckDB 的写出接口
         *   没有对应的类型（只有 `setDate` / `setTimestamp`），硬套会丢信息。
         */
        internal fun scanColumnOf(
            meta: ResultSetMetaData,
            index: Int,
        ): ScanColumn {
            val name = meta.getColumnLabel(index)
            val type = meta.getColumnType(index)
            val signed = meta.isSigned(index)
            return when (type) {
                Types.BIT, Types.BOOLEAN -> {
                    ScanColumn(name, ScanColumnKind.BOOLEAN)
                }

                Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                    if (signed) {
                        ScanColumn(name, ScanColumnKind.INT32)
                    } else {
                        ScanColumn(name, ScanColumnKind.INT64)
                    }
                }

                Types.BIGINT -> {
                    if (signed) {
                        ScanColumn(name, ScanColumnKind.INT64)
                    } else {
                        ScanColumn(name, ScanColumnKind.DECIMAL, precision = 20, scale = 0)
                    }
                }

                Types.REAL, Types.FLOAT -> {
                    ScanColumn(name, ScanColumnKind.FLOAT)
                }

                Types.DOUBLE -> {
                    ScanColumn(name, ScanColumnKind.DOUBLE)
                }

                Types.DECIMAL, Types.NUMERIC -> {
                    val precision = meta.getPrecision(index)
                    val scale = meta.getScale(index)
                    if (precision in 1..38 && scale in 0..precision) {
                        ScanColumn(name, ScanColumnKind.DECIMAL, precision, scale)
                    } else {
                        ScanColumn(name, ScanColumnKind.VARCHAR)
                    }
                }

                Types.DATE -> {
                    ScanColumn(name, ScanColumnKind.DATE)
                }

                Types.TIMESTAMP -> {
                    ScanColumn(name, ScanColumnKind.TIMESTAMP)
                }

                // 其余（含 VARCHAR / TEXT / BLOB / JSON / TIME / WITH TIME ZONE / 几何…）→ 文本
                else -> {
                    ScanColumn(name, ScanColumnKind.VARCHAR)
                }
            }
        }

        /** 列 → DuckDB 的逻辑类型（internal 供测试断言「每种 kind 都有对应的 DuckDB 类型」）。 */
        internal fun duckDbTypeOf(column: ScanColumn): Any =
            when (column.kind) {
                ScanColumnKind.BOOLEAN -> DuckDBColumnType.BOOLEAN
                ScanColumnKind.INT32 -> DuckDBColumnType.INTEGER
                ScanColumnKind.INT64 -> DuckDBColumnType.BIGINT
                ScanColumnKind.DECIMAL -> DuckDBLogicalType.decimal(column.precision, column.scale)
                ScanColumnKind.DOUBLE -> DuckDBColumnType.DOUBLE
                ScanColumnKind.FLOAT -> DuckDBColumnType.FLOAT
                ScanColumnKind.TIMESTAMP -> DuckDBColumnType.TIMESTAMP
                ScanColumnKind.DATE -> DuckDBColumnType.DATE
                ScanColumnKind.VARCHAR -> DuckDBColumnType.VARCHAR
            }

        /**
         * 把一行的第 [index] 列写进 [vector] 的第 [row] 行。
         *
         * 用**按类型的取值器 + `wasNull()`**，而不是 `getObject` 再转型 ——
         * 各驱动 `getObject` 的返回类型并不统一（PG 的 `numeric` 给 `BigDecimal`、
         * 某些驱动给 `PGobject`），转型失败会在**整次拉取**的中途炸掉。
         */
        private fun writeCell(
            column: ScanColumn,
            resultSet: ResultSet,
            index: Int,
            vector: DuckDBWritableVector,
            row: Long,
        ) {
            when (column.kind) {
                ScanColumnKind.BOOLEAN -> {
                    val value = resultSet.getBoolean(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setBoolean(row, value)
                }

                ScanColumnKind.INT32 -> {
                    val value = resultSet.getInt(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setInt(row, value)
                }

                ScanColumnKind.INT64 -> {
                    val value = resultSet.getLong(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setLong(row, value)
                }

                ScanColumnKind.DECIMAL -> {
                    val value = resultSet.getBigDecimal(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setBigDecimal(row, value)
                }

                ScanColumnKind.DOUBLE -> {
                    val value = resultSet.getDouble(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setDouble(row, value)
                }

                ScanColumnKind.FLOAT -> {
                    val value = resultSet.getFloat(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setFloat(row, value)
                }

                ScanColumnKind.TIMESTAMP -> {
                    val value = resultSet.getTimestamp(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setTimestamp(row, value)
                }

                ScanColumnKind.DATE -> {
                    val value = resultSet.getDate(index)
                    if (resultSet.wasNull()) vector.setNull(row) else vector.setDate(row, value)
                }

                ScanColumnKind.VARCHAR -> {
                    // 文本一律走 getString：驱动对 VARCHAR 的返回类型五花八门
                    // （有些给 Clob、有些给 byte[]），getString 是唯一稳的那条。
                    val value = resultSet.getString(index)
                    if (value == null) vector.setNull(row) else vector.setString(row, value)
                }
            }
        }
    }
}
