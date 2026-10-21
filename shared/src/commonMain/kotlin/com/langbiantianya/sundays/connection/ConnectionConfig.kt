/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import com.langbiantianya.i18n.StrKey
import kotlinx.serialization.Serializable

/**
 * 连接配置数据模型 (v2.9)。
 *
 * 支持的方言：
 * - MySQL (CLIENT_SERVER, default port 3306)
 * - PostgreSQL (CLIENT_SERVER, default port 5432)
 * - H2 (IN_MEMORY / EMBEDDED)
 * - DuckDB (EMBEDDED)
 * - SQLite (FILE_BASED)
 *
 * Hive / Impala 是 M3 的方言，届时与引擎方言插件同一个提交落地（见 [DialectType] 的说明）。
 *
 * ## 字段分三组
 *
 * | 组 | 字段 | 谁能改 |
 * |---|---|---|
 * | 身份 | [id] / [name] / [dialect] | 用户 |
 * | 连接目标 | [host] / [port] / [database] / [jdbcUrl] | 用户 / URL 编解码器 |
 * | 凭据 | [username] / [password] / [authMethod] / [properties] | 用户 |
 *
 * [properties] 是**方言特有字段的容器**（Kerberos 主体、ZooKeeper 发现串、SSL 参数…）。
 * 把它做成开放的 map 而不是加一堆固定字段，是因为那些字段**每种方言都不一样**，
 * 固定字段的做法等于「每加一个方言改一次模型」。
 */
@Serializable
data class ConnectionConfig(
    val id: String, // 唯一标识 (UUID)
    val name: String, // 连接名称（用户自定义）
    val dialect: DialectType = DialectType.MYSQL, // 数据库方言
    val host: String = "", // 主机地址 (CLIENT_SERVER)
    val port: Int? = null, // 端口 (CLIENT_SERVER)
    val database: String = "", // 数据库名 / 嵌入式库名 / 文件路径（引擎语义：CLIENT_SERVER = 库名，EMBEDDED 系 = URL 主体）
    val username: String = "", // 用户名
    val password: String = "", // 密码
    val connectionType: ConnectionType = ConnectionType.CLIENT_SERVER,
    /** 完整 JDBC URL —— 连接的真相源（方言由 URL scheme 反查） */
    val jdbcUrl: String = "",
    /**
     * 认证方式。
     *
     * ## 为什么它是「连接配置」而不是「方言属性」
     *
     * 同一个方言可以有多种认证：Hive 走 Kerberos 也可以走账密（LDAP 代理）。
     * 把认证方式挂在**连接**上，才表达得出「这一条连接用哪种认证」——
     * 挂在方言上就只剩一种可能了。
     */
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /**
     * 方言特有的连接参数 —— 键值对。
     *
     * 键是方言自己定的（`principal` / `zookeeper` / `ssl` …），本模块不解释它们，
     * 只负责**存着**。标注为 secret 的键在 UI 脱敏、在「导出配置」时剔除。
     */
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 获取显示用端口，CLIENT_SERVER 类型有默认值 */
    val displayPort: Int
        get() = port ?: DialectMetadata.of(dialect).defaultPort ?: 0

    /** 是否为嵌入式数据库 */
    val isEmbedded: Boolean
        get() = connectionType == ConnectionType.EMBEDDED || connectionType == ConnectionType.IN_MEMORY || connectionType == ConnectionType.FILE_BASED

    /**
     * 生成连接字符串 (用于显示)
     *
     * [noFileSpecified] / [inMemoryLabel] 是**占位文案**而非本模块能自取的常量：本函数是纯
     * Kotlin，拿不到 Compose 注入的文案表（`Msg.text` 读的是线程语言，而界面读的是
     * `LocalStrings`，两者未必一致）。因此由 Composable 侧先 `str(...)` 取好再传进来。
     */
    fun connectionString(
        noFileSpecified: String,
        inMemoryLabel: String,
    ): String =
        when (connectionType) {
            ConnectionType.CLIENT_SERVER -> "$host:$displayPort/$database"

            ConnectionType.FILE_BASED -> database.ifBlank { noFileSpecified }

            ConnectionType.EMBEDDED -> database.ifBlank { inMemoryLabel }

            ConnectionType.IN_MEMORY -> "mem:$database"

            // 自定义 URL 逃生舱：显示的就是用户自己写的那个 URL
            ConnectionType.CUSTOM_URL -> jdbcUrl

            ConnectionType.UNKNOWN -> ""
        }

    companion object {
        /** 方言 → 默认连接类型 + 默认端口 */
        fun defaultsFor(dialect: DialectType): Pair<ConnectionType, Int?> {
            val meta = DialectMetadata.of(dialect)
            return meta.defaultConnectionType to meta.defaultPort
        }

        /** 方言切换时重置为该方言的默认配置（URL 由调用方经 JDBC URL 编解码器重建） */
        fun resetFor(
            newDialect: DialectType,
            existing: ConnectionConfig,
        ): ConnectionConfig {
            val (defaultType, defaultPort) = defaultsFor(newDialect)
            return existing.copy(
                dialect = newDialect,
                connectionType = defaultType,
                port = defaultPort,
                host = if (defaultType == ConnectionType.CLIENT_SERVER) existing.host.ifBlank { "localhost" } else "",
                database = if (defaultType == ConnectionType.CLIENT_SERVER) existing.database else "",
                password = "",
                jdbcUrl = "",
                // 换方言 = 换一套字段语义 —— 旧方言的扩展参数对新手方言语义不明，
                // 留着只会变成「界面上有个看不懂的键」。凭据同样清空。
                authMethod = AuthMethod.PASSWORD,
                properties = emptyMap(),
            )
        }
    }
}

/**
 * 认证方式。
 *
 * ## 为什么不是「有密码 / 没密码」两个值
 *
 * 数仓环境的常态是 **Kerberos**（票据 / keytab），不是账密。把它单列成一个值，
 * 连接向导才能把「用户 / 主体 / keytab」这几个字段摆出来 ——
 * 继续用「账密 / 空」两个值表达 Kerberos，只能把它塞进 `properties` 里当无名键，
 * 界面与校验都无从谈起。
 */
@Serializable
enum class AuthMethod {
    /** 账密认证（用户名 + 密码）。 */
    PASSWORD,

    /** Kerberos 票据 / keytab 认证。 */
    KERBEROS,

    /** 无认证（本地嵌入式库，或服务端不校验身份）。 */
    NONE,
}

/** 数据库方言类型 */
@Serializable
enum class DialectType {
    MYSQL,
    POSTGRESQL,
    H2,
    DUCKDB,
    SQLITE,

    // ⚠️ Hive / Impala **刻意不在这里** —— 它们是 M3 的方言，M3 与引擎方言同一个提交落地。
    //
    // 为什么现在不加：前端枚举里的每个方言，引擎都得**真的实现了**才成 ——
    // 仓库的 `DialectNameContractTest` 直接向引擎要已注册方言列表来对照，
    // 多一个前端方言就会红。而红的时候它说的正是实话：「界面说有 Hive，引擎没这插件」。
    //
    // 更实际的问题：枚举里有了 Hive，向导的方言选择器就会**渲染出 Hive** ——
    // 用户能选中它、建连接，然后引擎报「No dialect plugin loaded for driver: Hive」。
    // 那正是仓库既有的原则要挡的事：**宁可少给，不给一个点了必然失败的东西**。
    //
    // M1 交付的是**机制**（见 `DialectMetadata`：加一个方言 = 加一条元数据），
    // M3 交付的是**方言本体**：引擎方言插件 + 这个枚举值 + 元数据表里的一行，
    // 三样在同一个提交里落地 —— `DialectNameContractTest` 就是那道闸门。
    UNKNOWN,
    ;

    /**
     * 该方言在前端可选的连接类型 —— 与引擎方言的 `connectionType` 元数据一致：
     * `Mysql` / `Postgresql` = CLIENT_SERVER；`H2` = IN_MEMORY（`jdbc:h2:mem:`）或 FILE_BASED
     * （`jdbc:h2:file:`）；`Duckdb` = EMBEDDED；`Sqlite` = FILE_BASED。
     *
     * 读 [DialectMetadata] 的同一张表 —— 不再自己维护一份 `when`。
     */
    val supportedConnectionTypes: List<ConnectionType>
        get() = DialectMetadata.of(this).supportedConnectionTypes

    /**
     * 引擎 `DatabaseDialect.driverName` —— proto `ConnectionConfig.driver` 必须填这个名字。
     *
     * 引擎以 `driverName` 为键注册方言（`DialectLoader.getDialect`），5 个内置方言的取值是
     * `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite` —— 与本枚举**常量名大小写不同**，
     * 因此**不能直接用 `Enum.name`**（`MYSQL` 会让引擎报 `No dialect plugin loaded for driver`）。
     *
     * 注意只有部分 handler 需要它：`PoolManager` 会优先按 `jdbcUrl` scheme 反查方言，但
     * `SchemaHandler` / `TableHandler` 等直接按 `config.driver` 取方言，所以请求必须带上正确值。
     *
     * 两侧一致性由 `desktopApp` 的 `DialectNameContractTest` 对照引擎 `SYSTEM.LIST_DRIVERS`
     * 的真实返回值校验 —— 任一侧改名都会让测试失败。
     */
    val engineDriverName: String
        get() = DialectMetadata.of(this).engineDriverName

    /**
     * 该方言的 JDBC URL 前缀 —— 用于从**裸 JDBC URL** 反查方言。
     *
     * ⚠️ **Hive 与 Impala 共用 `jdbc:hive2:`** —— 两者走同一个 HiveServer2 协议、
     * 用同一个 `hive-jdbc` 驱动，URL 形状本就相同。因此**不能只靠 URL 反查**：
     * 前端保留用户已选的方言，引擎用连接配置里显式的 `driver` 字段消歧。
     */
    val jdbcUrlPrefix: String
        get() = DialectMetadata.of(this).jdbcUrlPrefix

    companion object {
        fun fromString(value: String): DialectType = entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 连接类型 */
@Serializable
enum class ConnectionType {
    CLIENT_SERVER,
    EMBEDDED,
    IN_MEMORY,
    FILE_BASED,

    /**
     * 自定义 JDBC URL 逃生舱 —— 用户直接写完整 URL，方言由 URL scheme 反查。
     *
     * 引擎**已经支持**按 URL 前缀反查方言（`DialectLoader.getDialectByJdbcUrl`），
     * 前端缺的只是「让用户能写这个 URL」这一步。
     *
     * ⚠️ 它**不是**一个方言，而是一种**连接方式** —— 用户仍然先选方言
     * （决定默认端口 / 表单形状），再把连接方式换成「自定义 URL」。
     */
    CUSTOM_URL,

    UNKNOWN,
    ;

    /** 连接类型在界面上的标题（连接向导第 2 步的选项名）。 */
    val titleKey: StrKey
        get() =
            when (this) {
                CLIENT_SERVER -> StrKey.MODE_CLIENT_SERVER
                EMBEDDED -> StrKey.MODE_EMBEDDED
                IN_MEMORY -> StrKey.CONN_TYPE_IN_MEMORY
                FILE_BASED -> StrKey.CONN_TYPE_FILE
                CUSTOM_URL -> StrKey.MODE_CUSTOM_URL
                UNKNOWN -> StrKey.MODE_UNKNOWN
            }

    /** 连接类型在界面上的说明（`null` = 没有说明）。 */
    val descriptionKey: StrKey?
        get() =
            when (this) {
                CLIENT_SERVER -> StrKey.DESC_NEEDS_HOST_PORT
                EMBEDDED -> StrKey.DESC_EMBEDDED_IN_APP
                IN_MEMORY -> StrKey.DESC_IN_MEMORY_VOLATILE
                FILE_BASED -> StrKey.DESC_SINGLE_FILE
                CUSTOM_URL -> StrKey.DESC_CUSTOM_URL
                UNKNOWN -> null
            }

    companion object {
        fun fromString(value: String): ConnectionType = entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 连接配置列表 (用于 JSON 持久化) */
@Serializable
data class ConnectionList(
    val connections: List<ConnectionConfig> = emptyList(),
    val version: Int = 1,
)

/**
 * 持久化用的精简模型 —— 仅保存 id / name / dialect / jdbcUrl / username / password / 时间戳
 * `host` / `port` / `database` / `connectionType` 在加载后从 `jdbcUrl` 重新解析得出
 *
 * ## v3 新增两个字段
 *
 * [authMethod] / [properties] 是 v3 才加的（Kerberos 与方言特有参数）。
 * 两个字段都带**默认值**，因此 v2 的记录文件**不需要迁移也能读** ——
 * 缺字段时 kotlinx.serialization 取默认值。迁移只是「顺手把文件回写成 v3」。
 */
@Serializable
data class PersistedConnectionConfig(
    val id: String,
    val name: String,
    val dialect: DialectType,
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 持久化列表 wrapper */
@Serializable
data class PersistedConnectionList(
    val connections: List<PersistedConnectionConfig> = emptyList(),
    val version: Int = 3,
)

/**
 * 切换方言 —— 重置为该方言的默认连接类型 / 端口 / 清空凭据，并**立即折算 JDBC URL**。
 *
 * URL 是引擎侧的唯一真相源，因此向导进入 CREDENTIALS 步骤前配置必须已带合法 URL；
 * 折算失败（字段不足）时 URL 为空串，由向导在该步骤内补齐。
 */
fun ConnectionConfig.withDialect(newDialect: DialectType): ConnectionConfig = ConnectionConfig.resetFor(newDialect, this).let { it.copy(jdbcUrl = buildJdbcUrl(it)) }

/** 切换连接类型 —— URL 形状随类型变化（如 H2 `mem:` ↔ `file:`），用当前字段立即重新折算 */
fun ConnectionConfig.withConnectionType(newType: ConnectionType): ConnectionConfig =
    copy(connectionType = newType).let { cfg ->
        // 切到「自定义 URL」时把旧 URL **清空**。
        //
        // 为什么：CUSTOM_URL 下 `buildJdbcUrl` 原样返回 `jdbcUrl` —— 留着旧 URL
        // 等于给用户一个「看着能用、其实还是上一条连接」的初值：他没改它就保存，
        // 存下来的就是上一条连接的地址。清空之后「下一步」自然禁用（URL 为空），
        // 逼他真的写一个 URL 出来。
        val base = if (newType == ConnectionType.CUSTOM_URL) cfg.copy(jdbcUrl = "") else cfg
        base.copy(jdbcUrl = buildJdbcUrl(base))
    }
