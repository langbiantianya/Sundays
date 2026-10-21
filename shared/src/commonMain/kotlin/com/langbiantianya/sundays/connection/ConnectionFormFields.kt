/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.langbiantianya.dialect.contract.AuthMethod
import com.langbiantianya.dialect.contract.ConnectionField
import com.langbiantianya.dialect.contract.ConnectionFieldIds
import com.langbiantianya.dialect.contract.ConnectionForm
import com.langbiantianya.dialect.contract.ConnectionFormSpec
import com.langbiantianya.dialect.contract.FieldType
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.sundays.ui.WinDivider
import com.langbiantianya.sundays.ui.WinDropdown
import com.langbiantianya.sundays.ui.WinTextField
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.winShape

/**
 * 按 spec 渲染连接表单的字段 —— 「方言自描述」在前端这一侧的落点。
 *
 * ## 它替代的是什么
 *
 * 原来凭据页是 `when (connectionType)` 的一大段分支，每个分支手写字段、
 * 标签、占位符、图标、顺序。新增一个方言就要改这里 —— 而漏改一处就是一处
 * 「界面说能连、实际连不上」的静默错配。
 *
 * 现在这段知识由**方言自己声明**（`ConnectionFormSpec`），本文件只负责
 * **把声明画出来**。所以「新增一个方言」不需要改前端一行。
 *
 * ## 图标为什么还在这里
 *
 * 契约模块是 plain Kotlin（不依赖 Compose），带不了 `ImageVector`。
 * 而图标是**样式**不是**元数据** —— 它不影响「这个方言要填什么」，
 * 只影响「这一格看起来像什么」。所以图标映射留在这里，按**字段 id** 查
 * （而不是按方言查）：同一个 id 在任何方言里都是同一类东西。
 *
 * @param form 当前连接类型下的字段清单（顺序即渲染顺序）
 * @param spec 整个方言的规格（认证方式在这里面）
 * @param connection 当前的连接配置（字段的值从它读）
 * @param onFieldChange 字段值变化 —— 参数是（字段 id, 新值）
 * @param onAuthMethodChange 认证方式变化
 */
@Composable
internal fun ConnectionFormFields(
    form: ConnectionForm,
    spec: ConnectionFormSpec,
    connection: ConnectionConfig,
    onFieldChange: (String, String) -> Unit,
    onAuthMethodChange: (AuthMethod) -> Unit,
) {
    // 认证方式下拉 —— **只有一种时才不渲染**。
    //
    // 只有一种选择时，多一个下拉只是噪音（而且用户点开只能看到自己那一个）。
    // 内置的 5 个方言都只支持账密，所以界面上**看不到**这个下拉（行为零变化）。
    if (spec.authMethods.size > 1) {
        // 逐个取译文：不能用 `map { str(...) }` —— 那里的 lambda 不是组合上下文。
        val options = mutableListOf<Pair<AuthMethod, String>>()
        for (method in spec.authMethods) {
            options += method to str(method.titleKey)
        }
        WinDropdown(
            value = options.firstOrNull { it.first == connection.authMethod }?.second.orEmpty(),
            onValueChange = { text -> options.firstOrNull { it.second == text }?.first?.let(onAuthMethodChange) },
            items = options,
            itemLabel = { it.second },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(str(StrKey.FIELD_AUTH_METHOD)) },
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    form.fields.forEach { field ->
        // 字段可以声明「我在哪些认证方式下出现」——
        // 例如账密才要密码框，Kerberos 才要主体 / keytab。
        if (field.authMethods.isNotEmpty() && field.authMethods.none { it == connection.authMethod }) {
            return@forEach
        }

        if (field.separatorBefore) {
            WinDivider(modifier = Modifier.padding(vertical = 16.dp))
        }

        when (field.type) {
            FieldType.DISPLAY -> {
                DisplayField(field = field, value = connection.fieldValue(field.id))
            }

            FieldType.CHOICE -> {
                ChoiceField(
                    field = field,
                    value = connection.fieldValue(field.id),
                    onValueChange = { onFieldChange(field.id, it) },
                )
            }

            else -> {
                TextField(
                    field = field,
                    value = connection.fieldValue(field.id),
                    connectionType = connection.connectionType,
                    // 端口的占位提示是**默认端口** —— 那是这个方言的元数据，
                    // 由 spec 带来（`spec.defaultPort`），不是前端自己猜的。
                    defaultPort = if (field.id == ConnectionFieldIds.PORT) spec.defaultPort else null,
                    onValueChange = { onFieldChange(field.id, it) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * 文本型字段（TEXT / PASSWORD / INT）。
 *
 * `INT` 与 `TEXT` 的差别只在**输入过滤**：端口这类字段只保留数字字符。
 * 其余（多行、掩码、图标）都由字段声明驱动。
 */
@Composable
private fun TextField(
    field: ConnectionField,
    value: String,
    connectionType: ConnectionType,
    defaultPort: Int?,
    onValueChange: (String) -> Unit,
) {
    val icon = fieldIcon(field.id, connectionType)
    WinTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(str(field.labelKey)) },
        placeholder = {
            field.placeholderKey?.let { Text(str(it)) }
                ?: field.placeholder?.let { Text(it) }
                ?: defaultPort?.let { Text(it.toString()) }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = !field.multiline,
        minLines = if (field.multiline) 2 else 1,
        maxLines = if (field.multiline) 4 else 1,
        leadingIcon = icon?.let { { Icon(it, null) } },
        visualTransformation = if (field.type == FieldType.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/** 下拉型字段（CHOICE）。 */
@Composable
private fun ChoiceField(
    field: ConnectionField,
    value: String,
    onValueChange: (String) -> Unit,
) {
    WinDropdown(
        value = value,
        onValueChange = onValueChange,
        items = field.choices,
        itemLabel = { it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(str(field.labelKey)) },
    )
}

/**
 * 只读展示型字段（DISPLAY）—— 嵌入式方言那条「JDBC URL（由上方字段折算）」。
 *
 * 它的值是**折算**出来的（改上面的字段它就变），所以不能可编辑：
 * 做成输入框等于两处真相源 —— 改字段会重写它，改它会覆盖字段。
 */
@Composable
private fun DisplayField(
    field: ConnectionField,
    value: String,
) {
    Text(str(field.labelKey), style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = winShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = value.ifBlank { str(StrKey.FIELDS_PENDING) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/**
 * 字段 id → 图标。
 *
 * 按 **id** 查而不是按方言查：同一个 id 在任何方言里都是同一类东西
 * （主机就是主机、密码就是密码）。唯一的例外是「目标字段」：
 * 客户端/服务端模式下它是库名（存储图标），嵌入式模式下它是文件路径（文件夹图标）。
 */
private fun fieldIcon(
    fieldId: String,
    connectionType: ConnectionType,
): ImageVector? =
    when (fieldId) {
        ConnectionFieldIds.HOST -> {
            Icons.Default.Wifi
        }

        ConnectionFieldIds.DATABASE -> {
            if (connectionType == ConnectionType.CLIENT_SERVER) {
                Icons.Default.Storage
            } else {
                Icons.Default.FolderOpen
            }
        }

        ConnectionFieldIds.USERNAME -> {
            Icons.Default.Person
        }

        ConnectionFieldIds.PASSWORD -> {
            Icons.Default.Lock
        }

        ConnectionFieldIds.JDBC_URL -> {
            Icons.Default.Link
        }

        else -> {
            null
        }
    }

// ============================================================================
// 字段值 ↔ 连接配置
// ============================================================================

/**
 * 读一个字段的当前值。
 *
 * 六个**约定 id** 读 `ConnectionConfig` 的固定字段，其余读 `properties`
 * （方言特有的键值容器）—— 这与契约里的 [ConnectionFieldIds] 一一对应。
 */
internal fun ConnectionConfig.fieldValue(fieldId: String): String =
    when (fieldId) {
        ConnectionFieldIds.HOST -> {
            host
        }

        ConnectionFieldIds.PORT -> {
            port?.takeIf { it > 0 }?.toString().orEmpty()
        }

        ConnectionFieldIds.DATABASE -> {
            database
        }

        ConnectionFieldIds.USERNAME -> {
            username
        }

        ConnectionFieldIds.PASSWORD -> {
            password
        }

        ConnectionFieldIds.JDBC_URL -> {
            jdbcUrl
        }

        else -> {
            properties[fieldId].orEmpty()
        }
    }

/**
 * 写一个字段的新值。
 *
 * 端口只保留数字字符 —— 用户在端口框里敲个字母，那条连接**一定**连不上，
 * 而报错要到连库那一刻才出现（「尝试连线已失败」，一句没信息量的话）。
 * 挡在输入这一层更诚实。
 *
 * ⚠️ `JDBC_URL` 不走这里：它要**反解析**回其它字段（改了 URL 会覆盖字段），
 * 那件事是方言相关的，由 `withParsedJdbcUrl` 负责（见 `JdbcUrl.kt`）。
 */
internal fun ConnectionConfig.withFieldValue(
    fieldId: String,
    value: String,
): ConnectionConfig =
    when (fieldId) {
        ConnectionFieldIds.HOST -> {
            copy(host = value)
        }

        ConnectionFieldIds.PORT -> {
            copy(port = value.filter(Char::isDigit).toIntOrNull())
        }

        ConnectionFieldIds.DATABASE -> {
            copy(database = value)
        }

        ConnectionFieldIds.USERNAME -> {
            copy(username = value)
        }

        ConnectionFieldIds.PASSWORD -> {
            copy(password = value)
        }

        ConnectionFieldIds.JDBC_URL -> {
            copy(jdbcUrl = value)
        }

        else -> {
            copy(properties = properties + (fieldId to value))
        }
    }
