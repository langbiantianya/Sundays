package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.grpc.PayloadAdapter
import com.google.protobuf.Value
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `PayloadAdapter` 的 **Value ↔ JsonElement 数值往返**测试。
 *
 * ## 这条测试在盯什么
 *
 * protobuf 的 [Value.number_value] 是 double，没有整数类型；业务层读字段却一律用
 * `jsonPrimitive?.longOrNull` / `intOrNull`。这中间的「整数被 double 化」如果不在
 * 边界还原，`"3.0".toLongOrNull()` 会返回 null，而调用方普遍写了 `?: 0` 兜底，
 * 于是数字安静地变 0 —— 不抛异常，只是错了。
 *
 * 实测症状（TEST_CASES.md §9.15）：导出完成帧里 `filePath` 完好，
 * `exportedRows` 恒为 0，导出进度弹窗永远停在「正在连接引擎…」。
 * 修法见 `PayloadAdapter.numberPrimitive`。
 *
 * 这类缺陷没法靠「导出成功了吗」来发现 —— 文件确实写出来了，
 * 坏的只是进度数字。所以边界必须有独立锚点。
 */
class PayloadAdapterNumberRoundTripTest {

    @Test
    fun `整数经 gRPC 往返后仍能被 longOrNull 读回`() {
        val original = buildJsonObject {
            put("exportedRows", 3L)
            put("columnCount", 5)
        }

        // JsonElement → Value（走真实边界，模拟请求下发）
        val value = PayloadAdapter.toValue(original)
        // Value → JsonElement（子进程回帧走这条路）
        val back = PayloadAdapter.toJsonElement(value).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals(3L, back["exportedRows"]?.jsonPrimitive?.longOrNull, "exportedRows 应能读回 3")
        assertEquals(5, back["columnCount"]?.jsonPrimitive?.intOrNull, "columnCount 应能读回 5")
    }

    @Test
    fun `大整数行数不被 double 精度与小数点吃掉`() {
        val value = Value.newBuilder()
            .setStructValue(
                com.google.protobuf.Struct.newBuilder()
                    .putFields("exportedRows", Value.newBuilder().setNumberValue(13_308.0).build())
                    .build()
            )
            .build()

        val back = PayloadAdapter.toJsonElement(value) as kotlinx.serialization.json.JsonObject

        assertEquals(13_308L, back["exportedRows"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `真正的非整数仍是 double，doubleOrNull 照常解析`() {
        val value = Value.newBuilder().setNumberValue(1.5).build()
        val back = PayloadAdapter.toJsonElement(value)

        assertEquals(1.5, back.jsonPrimitive.doubleOrNull, "小数不能被截成 1")
    }

    @Test
    fun `整数也能被 doubleOrNull 正确解析（两个 API 都得能用）`() {
        val value = Value.newBuilder().setNumberValue(3.0).build()
        val back = PayloadAdapter.toJsonElement(value)

        assertEquals(3.0, back.jsonPrimitive.doubleOrNull)
        assertEquals(3L, back.jsonPrimitive.longOrNull)
    }

    @Test
    fun `布尔与字符串不受影响`() {
        val value = Value.newBuilder().setBoolValue(true).build()
        assertEquals(true, PayloadAdapter.toJsonElement(value).jsonPrimitive.booleanOrNull)

        val str = Value.newBuilder().setStringValue("export.csv").build()
        val parsed = PayloadAdapter.toJsonElement(str).jsonPrimitive
        assertEquals("export.csv", parsed.content)
        // 数字字符串必须仍是字符串，否则 "007" 这类会被误当数字
        assertNull(parsed.doubleOrNull)
    }

    @Test
    fun `NaN 与无穷退回 double 形态而不是被当成整数`() {
        // 单测锚点：这两个值若被当整数处理会抛 NumberFormatException
        assertEquals(Double.NaN, PayloadAdapter.toJsonElement(Value.newBuilder().setNumberValue(Double.NaN).build()).jsonPrimitive.doubleOrNull)
        val inf = PayloadAdapter.toJsonElement(Value.newBuilder().setNumberValue(Double.POSITIVE_INFINITY).build()).jsonPrimitive
        assertEquals(Double.POSITIVE_INFINITY, inf.doubleOrNull)
    }
}
