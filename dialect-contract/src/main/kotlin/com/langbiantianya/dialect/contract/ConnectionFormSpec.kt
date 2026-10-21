/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect.contract

import com.langbiantianya.i18n.StrKey

/**
 * 连接表单规格 —— 方言自描述「连得上要填什么」。
 *
 * ## 它解决什么
 *
 * 在它出现之前，连接向导的字段是**按方言写死的分支**（`ConnectionManagerScreen`
 * 里的 `when (editingConnection.connectionType)`）。加一个方言就要改前端，
 * 而「改前端」意味着：写死它的字段、顺序、标签、占位符、图标、必填性……
 * 漏一样就是一处**界面说能连、实际连不上**的静默错配。
 *
 * 现在这段知识**由方言自己声明**（`DatabaseDialect.connectionForm`），
 * 前端只负责把声明渲染出来 —— 新增一个方言的表单形状不需要改前端一行。
 *
 * ## 为什么是「一份 spec 多个 form」，而不是「一份 spec 一组字段」
 *
 * 同一个方言在不同**连接类型**下的表单形状是真的不同：
 *
 * | 方言 | 连接类型 | 差别 |
 * |---|---|---|
 * | H2 | `IN_MEMORY` | 标签是「数据库名称」，占位符是「例如: testdb」 |
 * | H2 | `FILE_BASED` | 标签是「数据库文件路径」，占位符是「例如: /path/to/data」 |
 * | MySQL | `CLIENT_SERVER` | 主机 / 端口 / 库名 / 账密 / JDBC URL 六格 |
 *
 * 硬塞进一份扁平的字段表，就只能取一个形状 —— 剩下那个连接类型要么显示错的标签，
 * 要么得在渲染层再写一次 `when`（那就白做了）。
 * 所以 spec 里是 **form 的列表**，每份 form 认一个连接类型。
 *
 * 连接类型用**字符串**而不是枚举：`:api` 与 `:shared` 各有一份
 * `ConnectionType` 枚举（前者是引擎 SPI 的，后者是持久化格式的一部分），
 * 契约模块若再定义第三份枚举，就是**同一个概念三处定义**——
 * 那正是本仓库反复在消灭的东西。两边都用各自枚举的 `name`，
 * 由 `desktopApp` 的一条契约测试钉住「同名集合一致」。
 *
 * @property forms 每种连接类型一份表单。空列表 = 「我不知道怎么连」——
 *   前端据此**回退旧的枚举分支**（不是渲染一个空表单）。
 * @property authMethods 该方言支持的认证方式。长度为 1 时不渲染下拉
 *   （只有一种选择时，多一个下拉只是噪音）。
 * @property defaultPort 默认端口（表单占位符 / 默认值）。`null` = 无默认。
 */
data class ConnectionFormSpec(
    val forms: List<ConnectionForm> = emptyList(),
    val authMethods: List<AuthMethod> = listOf(AuthMethod.PASSWORD),
    val defaultPort: Int? = null,
) {
    /**
     * 取某个连接类型对应的表单；没有匹配时返回 `null`。
     *
     * `null` 的含义是「这个方言在这个连接类型下没有声明表单」——
     * 调用方（前端）应当**回退旧的枚举分支**，而不是渲染空白。
     */
    fun formFor(connectionType: String): ConnectionForm? = forms.firstOrNull { it.connectionType == connectionType }

    /** 该 spec 是否什么都没声明 —— 等价于「前端应当回退旧分支」。 */
    val isEmpty: Boolean
        get() = forms.isEmpty()
}

/**
 * 一份具体的表单 —— 某个连接类型下的字段清单。
 *
 * @property connectionType 连接类型名（`ConnectionType.name`，如 `"CLIENT_SERVER"`）。
 * @property fields 字段清单，**顺序即渲染顺序**。
 */
data class ConnectionForm(
    val connectionType: String,
    val fields: List<ConnectionField> = emptyList(),
) {
    /** 按 id 取字段；不存在返回 `null`。 */
    fun field(id: String): ConnectionField? = fields.firstOrNull { it.id == id }
}

/**
 * 一个表单字段。
 *
 * ## id 决定它存到哪里
 *
 * 六个**约定 id**（见 [ConnectionFieldIds]）对应 `ConnectionConfig` 上的六个固定字段
 * （主机 / 端口 / 库名 / 用户名 / 密码 / JDBC URL）；
 * **其余 id 一律存进 `ConnectionConfig.properties`**（那个开放的键值容器）。
 *
 * 为什么要分开：前六个字段是**引擎建池时直接要**的
 * （`PoolManager` 按 host/port/database 拼池），放进 `properties` 里
 * 引擎还得再拆一次；而方言特有的参数（Kerberos 主体、ZooKeeper 发现串…）
 * 每种方言都不一样，固定字段的做法等于「每加一个方言改一次模型」。
 *
 * @property id 字段标识。约定 id 见 [ConnectionFieldIds]；其余是方言自定义的
 *   （存进 `ConnectionConfig.properties`）。
 * @property labelKey 字段标签的文案键。**用键而不是字面量** ——
 *   字面量在六语言界面里就是一处漏出。
 * @property type 字段类型（文本 / 密码 / 整数 / 下拉 / 只读展示）。
 * @property required 是否必填。
 * @property secret 是否是敏感值。敏感值在界面上要掩码、在「导出配置」时要剔除。
 * @property choices 下拉候选（[FieldType.CHOICE] 时有效）。
 * @property placeholderKey 占位提示的文案键（翻译过的）。
 * @property placeholder 占位提示的字面量（不翻译的，例如 JDBC URL 示例）。
 *   两者都为空表示没有占位提示；`placeholderKey` 优先。
 * @property authMethods 该字段在哪些认证方式下出现。空列表 = **所有**认证方式下都出现。
 * @property separatorBefore 该字段之前是否画一条分隔线。
 * @property multiline 该字段是否是多行输入（JDBC URL 这类长文本）。
 */
data class ConnectionField(
    val id: String,
    val labelKey: StrKey,
    val type: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    val secret: Boolean = false,
    val choices: List<String> = emptyList(),
    val placeholderKey: StrKey? = null,
    val placeholder: String? = null,
    val authMethods: List<AuthMethod> = emptyList(),
    val separatorBefore: Boolean = false,
    val multiline: Boolean = false,
)

/**
 * 字段类型。
 *
 * `DISPLAY` 存在的理由：嵌入式方言的凭据页**只展示**折算出来的 JDBC URL
 * （一个只读文本块），不是输入框。没有它就得让渲染层再写一次
 * 「这个 id 是只读的」——那又是一个按方言分支的地方。
 */
enum class FieldType {
    /** 单行文本。 */
    TEXT,

    /** 密码（掩码显示）。 */
    PASSWORD,

    /** 整数（端口等）。渲染层只保留数字字符。 */
    INT,

    /** 下拉（候选项在 [ConnectionField.choices] 里）。 */
    CHOICE,

    /** 只读展示：不是输入框，只把值显示出来。 */
    DISPLAY,
}

/**
 * 约定字段 id —— 与 `ConnectionConfig` 上的固定字段一一对应。
 *
 * 为什么要「约定」而不是随便起名：这几个 id 决定字段的值**从哪里读、往哪里写**，
 * 是契约的一部分，不是方言的自由。方言自定义的字段用别的 id（落进 `properties`）。
 */
object ConnectionFieldIds {
    /** 主机地址 → `ConnectionConfig.host`。 */
    const val HOST = "host"

    /** 端口 → `ConnectionConfig.port`。 */
    const val PORT = "port"

    /** 库名 / 文件路径 → `ConnectionConfig.database`。 */
    const val DATABASE = "database"

    /** 用户名 → `ConnectionConfig.username`。 */
    const val USERNAME = "username"

    /** 密码 → `ConnectionConfig.password`。 */
    const val PASSWORD = "password"

    /** 完整 JDBC URL → `ConnectionConfig.jdbcUrl`。 */
    const val JDBC_URL = "jdbcUrl"
}

/**
 * 契约里用到的连接类型名 —— 与 `:api` / `:shared` 两份 `ConnectionType` 枚举的
 * `name` 一致（`desktopApp` 的 `DialectFormContractTest` 钉住这条）。
 *
 * 之所以在这里再写一遍字符串，是为了让「契约认哪些连接类型」有一处**可读**的清单：
 * 枚举的 `name` 是隐式约定，而这里写下来，读者不必去翻别的模块。
 */
object ConnectionFormTypes {
    /** 客户端/服务端：主机 + 端口 + 库名 + 凭据。 */
    const val CLIENT_SERVER = "CLIENT_SERVER"

    /** 嵌入式：`database` 装的是路径或特殊值。 */
    const val EMBEDDED = "EMBEDDED"

    /** 纯内存：`database` 可空。 */
    const val IN_MEMORY = "IN_MEMORY"

    /** 文件型：`database` 即本地文件路径。 */
    const val FILE_BASED = "FILE_BASED"

    /** 自定义 JDBC URL —— 由前端的统一分支处理，**不由 spec 描述**。 */
    const val CUSTOM_URL = "CUSTOM_URL"
}
