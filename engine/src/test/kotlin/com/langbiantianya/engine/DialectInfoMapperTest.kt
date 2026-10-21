/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.engine

import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.dialect.MySQLDialect
import com.langbiantianya.dialect.contract.AuthMethod
import com.langbiantianya.dialect.contract.ConnectionForm
import com.langbiantianya.dialect.contract.ConnectionFormSpec
import com.langbiantianya.dialect.contract.FieldType
import com.langbiantianya.grpc.dialectFormField
import com.langbiantianya.grpc.toProtoOrNull
import com.langbiantianya.grpc.toSpec
import com.langbiantianya.i18n.StrKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 方言 → proto `DialectInfo` 的映射 —— 以及**往返**保真。
 *
 * ## 为什么要测往返
 *
 * spec 走 proto 跨进程：引擎「填进去」、前端「读出来」。两段代码在两个模块里，
 * 而它们的症状是**静默**的 —— 填的时候把 `labelKey` 写成了别的，读的时候
 * 认不出来就把那个字段**丢掉**，界面上的后果只是「那个字段没有名字了」：
 * 不报错、不崩。
 *
 * 所以「填进去再读出来还是原来那个」这条断言，是这条链路上**唯一**能拦住
 * 字段悄悄消失的东西。
 */
class DialectInfoMapperTest {
    // =========================================================================
    // 「没声明」与「声明了空表单」要分得开
    // =========================================================================

    /**
     * 没声明表单的方言 → proto 里**不设置** `connection_form`。
     *
     * 前端据此回退旧的枚举分支（照常能用）。若这里改成「设置一个空消息」，
     * 前端就分不出「没声明」与「声明了空表单」—— 后者会被当成「这个方言
     * 不要任何字段」，凭据页变成空白。
     */
    @Test
    fun `没声明表单的方言不带 connection_form`() {
        assertNull(ConnectionFormSpec().toProtoOrNull())
    }

    @Test
    fun `声明了表单的方言带 connection_form`() {
        // ⚠️ 「声明了」指的是**至少有一份 form** —— 一份 form 里可以一个字段都没有
        // （那是「这个方言不要任何字段」，与「没声明」是两件事）。
        val spec =
            ConnectionFormSpec(
                forms = listOf(ConnectionForm(connectionType = "CLIENT_SERVER")),
            )
        assertNotNull(spec.toProtoOrNull())
    }

    // =========================================================================
    // 真实方言的映射
    // =========================================================================

    @Test
    fun `H2 的两份表单都进了 proto`() {
        val info = DialectInfoMapper.toDialectInfo(H2Dialect())

        assertTrue(info.hasConnectionForm(), "H2 声明了表单，proto 里必须带上")
        val forms = info.connectionForm.formsList
        assertEquals(listOf("IN_MEMORY", "FILE_BASED"), forms.map { it.connectionType })
    }

    @Test
    fun `H2 的内存表单是两格 - 目标字段加只读 URL`() {
        val info = DialectInfoMapper.toDialectInfo(H2Dialect())
        val memory = info.connectionForm.formsList.first { it.connectionType == "IN_MEMORY" }

        assertEquals(listOf("database", "jdbcUrl"), memory.fieldsList.map { it.id })
        // ⚠️ 嵌入式方言的 URL 是**折算**出来的 —— 必须是 DISPLAY（只读），
        // 做成输入框就等于两处真相源。
        assertEquals("DISPLAY", memory.fieldsList[1].type)
    }

    @Test
    fun `MySQL 的客户端表单是六格 - 且 URL 是可编辑的多行输入`() {
        val info = DialectInfoMapper.toDialectInfo(MySQLDialect())
        val form = info.connectionForm.formsList.single { it.connectionType == "CLIENT_SERVER" }

        assertEquals(
            listOf("host", "port", "database", "username", "password", "jdbcUrl"),
            form.fieldsList.map { it.id },
        )
        assertEquals("INT", form.fieldsList[1].type)
        assertEquals("PASSWORD", form.fieldsList[4].type)
        assertTrue(form.fieldsList[4].secret, "密码字段必须标成敏感")
        assertEquals("TEXT", form.fieldsList[5].type)
        assertTrue(form.fieldsList[5].multiline, "JDBC URL 是多行输入")
        assertTrue(form.fieldsList[5].separatorBefore, "JDBC URL 之前要一条分隔线")
    }

    // =========================================================================
    // 往返
    // =========================================================================

    @Test
    fun `H2 的 spec 往返之后一模一样`() {
        val original = H2Dialect().connectionForm
        val info = DialectInfoMapper.toDialectInfo(H2Dialect())
        val restored = info.connectionForm.toSpec()

        assertEquals(original.forms.size, restored.forms.size)
        original.forms.forEachIndexed { index, form ->
            val back = restored.forms[index]
            assertEquals(form.connectionType, back.connectionType)
            assertEquals(form.fields.size, back.fields.size)
            form.fields.forEachIndexed { i, field ->
                val fieldBack = back.fields[i]
                assertEquals(field.id, fieldBack.id)
                assertEquals(field.labelKey, fieldBack.labelKey)
                assertEquals(field.type, fieldBack.type)
                assertEquals(field.required, fieldBack.required)
                assertEquals(field.secret, fieldBack.secret)
                assertEquals(field.choices, fieldBack.choices)
                assertEquals(field.placeholderKey, fieldBack.placeholderKey)
                assertEquals(field.placeholder, fieldBack.placeholder)
                assertEquals(field.authMethods, fieldBack.authMethods)
                assertEquals(field.separatorBefore, fieldBack.separatorBefore)
                assertEquals(field.multiline, fieldBack.multiline)
            }
        }
        assertEquals(original.authMethods, restored.authMethods)
        assertEquals(original.defaultPort, restored.defaultPort)
    }

    /**
     * 文案键走的是**名字**（不是译文）—— 所以往返之后仍然是同一个 `StrKey`。
     *
     * 这条断言守着「引擎发的是键名、前端拿键名反查」这条约定：
     * 万一有一侧改成了发译文，界面就会在非中文语言下显示成另一种语言。
     */
    @Test
    fun `文案键走名字 - 往返后仍是同一个 StrKey`() {
        val info = DialectInfoMapper.toDialectInfo(MySQLDialect())
        val form = info.connectionForm.formsList.single()
        val host = form.fieldsList.first { it.id == "host" }
        assertEquals(StrKey.HOST.name, host.labelKey)
        assertEquals(StrKey.HOST, StrKey.fromName(host.labelKey))
    }

    /**
     * 认不出来的文案键 → **丢掉那个字段**，而不是给个空标签。
     *
     * 宁可少给，不给一个点了必然失败的东西 —— 仓库一贯的原则。
     */
    @Test
    fun `认不出来的文案键会丢掉那个字段`() {
        val broken =
            dialectFormField {
                id = "weird"
                labelKey = "NO_SUCH_KEY"
                type = "TEXT"
            }
        assertNull(broken.toSpec())
    }

    @Test
    fun `认不出来的字段类型退成 TEXT 而不是丢字段`() {
        val weird =
            dialectFormField {
                id = "x"
                labelKey = StrKey.HOST.name
                type = "SOMETHING_NEW"
            }
        val field = weird.toSpec()
        assertNotNull(field, "类型认不出来不该丢字段 —— 字段照样显示，只是没有特殊处理")
        assertEquals(FieldType.TEXT, field.type)
    }

    @Test
    fun `认证方式按名字往返`() {
        val spec =
            ConnectionFormSpec(
                forms = listOf(ConnectionForm(connectionType = "CLIENT_SERVER")),
                authMethods = listOf(AuthMethod.PASSWORD, AuthMethod.KERBEROS),
            )
        val proto = spec.toProtoOrNull()!!
        val restored = proto.toSpec()
        assertEquals(listOf(AuthMethod.PASSWORD, AuthMethod.KERBEROS), restored.authMethods)
    }

    @Test
    fun `默认端口 0 表示「没有默认」`() {
        val spec =
            ConnectionFormSpec(
                forms = listOf(ConnectionForm(connectionType = "CLIENT_SERVER")),
                defaultPort = null,
            )
        val proto = spec.toProtoOrNull()!!
        assertNull(proto.toSpec().defaultPort)
    }
}
