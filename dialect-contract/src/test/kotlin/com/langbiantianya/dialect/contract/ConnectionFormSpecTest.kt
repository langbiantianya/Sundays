/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 连接表单契约的规则 —— 形状查表、认证方式解析、以及**落盘格式**。
 *
 * 最后那一条最要紧：[AuthMethod] 是 `connection.json` 的一部分，
 * 枚举常量名一旦被改，存量连接的认证方式就会**静默变成默认值** ——
 * 而「连不上」要到连库那一刻才暴露。
 */
class ConnectionFormSpecTest {
    // =========================================================================
    // 查表
    // =========================================================================

    @Test
    fun `按连接类型取表单 - 取不到返回 null 而不是空表单`() {
        val spec =
            ConnectionFormSpec(
                forms =
                    listOf(
                        ConnectionForm(ConnectionFormTypes.IN_MEMORY, listOf(field("a"))),
                        ConnectionForm(ConnectionFormTypes.FILE_BASED, listOf(field("b"))),
                    ),
            )

        val inMemory = spec.formFor(ConnectionFormTypes.IN_MEMORY)
        val fileBased = spec.formFor(ConnectionFormTypes.FILE_BASED)
        assertEquals("a", inMemory?.fields?.single()?.id)
        assertEquals("b", fileBased?.fields?.single()?.id)
        // 关键：取不到必须能分辨出「没有这份表单」——
        // 前端据此回退旧的枚举分支，而不是渲染一个空表单。
        assertNull(spec.formFor(ConnectionFormTypes.CLIENT_SERVER))
    }

    @Test
    fun `空 spec 表示「没声明」- 前端据此回退旧分支`() {
        val spec = ConnectionFormSpec()
        assertTrue(spec.isEmpty)
        assertNull(spec.formFor(ConnectionFormTypes.CLIENT_SERVER))
    }

    // =========================================================================
    // 认证方式
    // =========================================================================

    @Test
    fun `认证方式按名字往返`() {
        AuthMethod.entries.forEach { method ->
            assertEquals(method, AuthMethod.fromName(method.name))
        }
    }

    @Test
    fun `认不出来的认证方式落回 PASSWORD 而不是 NONE`() {
        // 落回 NONE 会拿着空凭据去连 —— 那是静默错配；
        // 落回 PASSWORD 至多报一句「认证失败」，错得明显。
        assertEquals(AuthMethod.PASSWORD, AuthMethod.fromName("SOMETHING_ELSE"))
        assertEquals(AuthMethod.PASSWORD, AuthMethod.fromName(""))
    }

    /**
     * **落盘格式** —— 枚举常量名就是序列化名。
     *
     * 改任何一个常量名，存量 `connection.json` 里的 `authMethod` 就读不出来了
     * （kotlinx.serialization 对未知枚举值直接抛异常），用户的连接**全部打不开**。
     * 所以这条断言是「磁盘格式」的一部分，不是实现细节。
     */
    @Test
    fun `认证方式的序列化名是常量名 - 改了存量连接就读不出来`() {
        assertEquals("\"PASSWORD\"", Json.encodeToString(AuthMethod.PASSWORD))
        assertEquals("\"KERBEROS\"", Json.encodeToString(AuthMethod.KERBEROS))
        assertEquals("\"NONE\"", Json.encodeToString(AuthMethod.NONE))
        assertEquals(AuthMethod.KERBEROS, Json.decodeFromString<AuthMethod>("\"KERBEROS\""))
    }

    // =========================================================================
    // 常用形状
    // =========================================================================

    @Test
    fun `客户端服务端形状是六格 - 顺序固定`() {
        val form = StandardForms.clientServer("jdbc:example://host:1234/db")
        assertEquals(ConnectionFormTypes.CLIENT_SERVER, form.connectionType)
        assertEquals(
            listOf("host", "port", "database", "username", "password", "jdbcUrl"),
            form.fields.map { it.id },
        )
        // 端口是整数、密码是密码型且敏感
        assertEquals(FieldType.INT, form.field("port")?.type)
        assertEquals(FieldType.PASSWORD, form.field("password")?.type)
        assertTrue(form.field("password")?.secret == true)
        // 主机必填
        assertTrue(form.field("host")?.required == true)
    }

    @Test
    fun `嵌入式形状是「一个目标 + 只读 URL」`() {
        val form =
            StandardForms.embedded(
                connectionType = ConnectionFormTypes.EMBEDDED,
                labelKey = com.langbiantianya.i18n.StrKey.DATABASE_FILE_PATH_LABEL,
                placeholderKey = com.langbiantianya.i18n.StrKey.DATABASE_FILE_PATH_HINT_DUCKDB,
            )
        assertEquals(listOf("database", "jdbcUrl"), form.fields.map { it.id })
        // ⚠️ 嵌入式方言的 URL 是**折算**出来的，不是用户填的 —— 必须是只读展示。
        // 做成输入框就等于两处真相源（改字段会重写 URL，改 URL 会覆盖字段）。
        assertEquals(FieldType.DISPLAY, form.field("jdbcUrl")?.type)
    }

    private fun field(id: String) = ConnectionField(id = id, labelKey = com.langbiantianya.i18n.StrKey.DATABASE)
}
