/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect.contract

import com.langbiantianya.i18n.StrKey
import kotlinx.serialization.Serializable

/**
 * 认证方式。
 *
 * ## 为什么不是「有密码 / 没密码」两个值
 *
 * 数仓环境的常态是 **Kerberos**（票据 / keytab），不是账密。把它单列成一个值，
 * 连接向导才能把「用户 / 主体 / keytab」这几个字段摆出来 ——
 * 继续用「账密 / 空」两个值表达 Kerberos，只能把它塞进 `properties` 里当无名键，
 * 界面与校验都无从谈起。
 *
 * ## 为什么住在契约模块，而不是 `:shared`
 *
 * 它原本住在 `:shared` 的 `ConnectionConfig.kt` 里。但 `ConnectionFormSpec`
 * 要声明「本方言支持哪些认证方式」—— spec 在契约模块，`:shared` 又依赖契约模块，
 * 于是 `:shared` 里的这个枚举就成了**反向依赖**：契约模块看不见 `:shared`。
 *
 * 搬到这里之后，依赖方向是单向的：`:shared` → 契约模块。
 *
 * ## 为什么它是「连接配置」而不是「方言属性」
 *
 * 同一个方言可以有多种认证：Hive 走 Kerberos 也可以走账密（LDAP 代理）。
 * 把认证方式挂在**连接**上，才表达得出「这一条连接用哪种认证」——
 * 挂在方言上就只剩一种可能了。
 *
 * ## 为什么它是 `@Serializable`（而契约里的其它类型不是）
 *
 * 它是 `ConnectionConfig` 的字段，要跟着 `connection.json` 落盘
 * （v3 持久化格式的一部分）。枚举的**序列化名就是常量名**
 * （`PASSWORD` / `KERBEROS` / `NONE`），所以搬模块**不改动磁盘格式** ——
 * 存量 `connection.json` 照常读得出。
 *
 * @property titleKey 界面上的显示名（认证方式下拉的选项文字）。
 */
@Serializable
enum class AuthMethod(
    val titleKey: StrKey,
) {
    /** 账密认证（用户名 + 密码）。 */
    PASSWORD(StrKey.AUTH_METHOD_PASSWORD),

    /** Kerberos 票据 / keytab 认证。 */
    KERBEROS(StrKey.AUTH_METHOD_KERBEROS),

    /** 无认证（本地嵌入式库，或服务端不校验身份）。 */
    NONE(StrKey.AUTH_METHOD_NONE),
    ;

    companion object {
        /**
         * 按名字解析（proto 传的是枚举名）。
         *
         * 认不出来的名字一律落回 [PASSWORD] —— 认证方式是**连接配置**，
         * 猜错的后果是「按账密连」，而账密连不上会明确报错；
         * 反过来若落回 [NONE]，就会拿着空凭据去连，那才是静默错配。
         */
        fun fromName(value: String): AuthMethod = entries.firstOrNull { it.name == value } ?: PASSWORD
    }
}
