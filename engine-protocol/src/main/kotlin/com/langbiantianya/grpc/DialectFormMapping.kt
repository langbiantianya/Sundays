/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.grpc

import com.langbiantianya.dialect.contract.AuthMethod
import com.langbiantianya.dialect.contract.ConnectionField
import com.langbiantianya.dialect.contract.ConnectionForm
import com.langbiantianya.dialect.contract.ConnectionFormSpec
import com.langbiantianya.dialect.contract.FieldType
import com.langbiantianya.i18n.StrKey

// ============================================================================
// 连接表单规格 ⇄ proto 的双向映射
// ============================================================================
//
// ## 为什么两个方向放在**同一个文件**里
//
// 「填进去」与「读出来」必须**对得上**：填的时候写 `labelKey.name`，
// 读的时候就要按 `StrKey.name` 反查。两段代码分处两个模块时，
// 没人保证它们是互补的 —— 而这种不互补的症状是**界面上的标签变空**，
// 不报错、不崩，只是「那个字段没有名字了」。
//
// 放在一起，改一处就看得见另一处。
//
// ## 谁用哪个方向
//
// | 方向 | 用的人 | 场景 |
// |---|---|---|
// | 契约 → proto | `:engine`（`SystemHandler.listDrivers`） | 引擎把方言声明的表单规格下发 |
// | proto → 契约 | `:desktopApp` | 前端把下发的规格还原成可渲染的 spec |
//
// ## 认不出来的文案键**丢字段**
//
// proto 传的是 `StrKey` 的枚举名。万一对端发来一个本版本不认识的键
// （比如前端比引擎新），那个字段的标签就无从查起 —— 此时**丢掉这个字段**，
// 而不是给个空标签。
//
// 宁可少给，不给一个点了必然失败的东西 —— 这是仓库一贯的原则。

/** 契约层的 spec → proto。 */
fun ConnectionFormSpec.toProto(): DialectForm =
    dialectForm {
        forms.addAll(this@toProto.forms.map { it.toProto() })
        authMethods.addAll(this@toProto.authMethods.map { it.name })
        defaultPort = this@toProto.defaultPort ?: 0
    }

/**
 * 契约层的 spec → proto；**「没声明」时返回 `null`**。
 *
 * 为什么要有这个「可能为 null」的版本：proto 那边要表达的是
 * 「这个方言**没有**连接表单规格」与「这个方言的规格是空的」——
 * 前者让前端回退旧的枚举分支，后者是「真的要一个空表单」。
 * 用同一个空消息表达两者，前端就分不出来了。
 *
 * 所以：**没声明 → 不设置 `connection_form`**（`hasConnectionForm() == false`）。
 */
fun ConnectionFormSpec.toProtoOrNull(): DialectForm? = if (isEmpty) null else toProto()

/** 一份 form → proto。 */
private fun ConnectionForm.toProto(): DialectFormVariant =
    dialectFormVariant {
        connectionType = this@toProto.connectionType
        fields.addAll(this@toProto.fields.map { it.toProto() })
    }

/** 一个字段 → proto。 */
private fun ConnectionField.toProto(): DialectFormField =
    dialectFormField {
        id = this@toProto.id
        // 文案**键**的名字，不是译文 —— 译文由前端的文案表按界面语言给出。
        labelKey = this@toProto.labelKey.name
        type = this@toProto.type.name
        required = this@toProto.required
        secret = this@toProto.secret
        choices.addAll(this@toProto.choices)
        this@toProto.placeholderKey?.let { placeholderKey = it.name }
        this@toProto.placeholder?.let { placeholder = it }
        authMethods.addAll(this@toProto.authMethods.map { it.name })
        separatorBefore = this@toProto.separatorBefore
        multiline = this@toProto.multiline
    }

/** proto 的 `DialectForm` → 契约层的 spec。 */
fun DialectForm.toSpec(): ConnectionFormSpec =
    ConnectionFormSpec(
        forms = formsList.map { it.toSpec() },
        authMethods = authMethodsList.map { AuthMethod.fromName(it) },
        defaultPort = defaultPort.takeIf { it > 0 },
    )

/** proto 的一份 form → 契约层。 */
private fun DialectFormVariant.toSpec(): ConnectionForm =
    ConnectionForm(
        connectionType = connectionType,
        fields = fieldsList.mapNotNull { it.toSpec() },
    )

/**
 * proto 的一个字段 → 契约层；文案键认不出来时返回 `null`（丢掉这个字段）。
 *
 * 公开出来是为了能**单独**测「认不出来的键 / 类型会怎样」——
 * 那两条是这条链路上最容易悄悄退化的地方。
 */
fun DialectFormField.toSpec(): ConnectionField? {
    val label = StrKey.fromName(labelKey) ?: return null
    return ConnectionField(
        id = id,
        labelKey = label,
        // 认不出来的类型退成 TEXT —— 那是**最宽容**的一档：
        // 字段照样显示，只是没有掩码 / 下拉的特殊处理。
        // 丢掉整个字段反而更糟（用户会以为这个方言没这个参数）。
        type = FieldType.entries.firstOrNull { it.name == type } ?: FieldType.TEXT,
        required = required,
        secret = secret,
        choices = choicesList,
        placeholderKey = placeholderKey.takeIf { it.isNotEmpty() }?.let { StrKey.fromName(it) },
        placeholder = placeholder.takeIf { it.isNotEmpty() },
        authMethods = authMethodsList.map { AuthMethod.fromName(it) },
        separatorBefore = separatorBefore,
        multiline = multiline,
    )
}
