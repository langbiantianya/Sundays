/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.dialect.contract.AuthMethod
import com.langbiantianya.dialect.contract.ConnectionField
import com.langbiantianya.dialect.contract.ConnectionForm
import com.langbiantianya.dialect.contract.ConnectionFormSpec
import com.langbiantianya.dialect.contract.ConnectionFormTypes
import com.langbiantianya.dialect.contract.FieldType
import com.langbiantianya.i18n.StrKey

/**
 * 测试用假方言 —— 声明 **3 个自定义字段 + 2 种认证方式**。
 *
 * ## 它证明的是「前端零改动」
 *
 * 这个方言的三个字段（服务名 / Kerberos 主体 / keytab 路径）在前端**没有任何对应代码**：
 * 界面上的那几格完全由 [connectionForm] 驱动。所以「它们出现在凭据页上」这件事
 * 本身就证明了「新增一个方言的表单形状不需要改前端一行」。
 *
 * ## 为什么用接口委托而不是实现全部方法
 *
 * `DatabaseDialect` 有四十来个成员，全部实现一遍全是 `TODO()` —— 那是一堆
 * **骗人的**代码（读起来像实现了，其实一调就炸）。委托给一个真方言
 * （[base]）更诚实：这个假方言**只**比它多一样东西 —— 那份表单规格。
 *
 * @param base 被委托的底座方言（提供全部与表单无关的能力）
 */
class FakeDialect(
    base: DatabaseDialect,
) : DatabaseDialect by base {
    override val driverName = "Faketest"

    override val displayName = "Fake Dialect"

    /**
     * 这个假方言的全部「自述」—— 前端要照着渲染的就是它。
     *
     * - **服务名**：所有认证方式下都出现（数仓那边的通用参数）；
     * - **主体 / keytab 路径**：只在 Kerberos 下出现（账密时它们没有意义）；
     * - 两种认证方式：**账密** 与 **Kerberos**。
     */
    override val connectionForm =
        ConnectionFormSpec(
            forms =
                listOf(
                    ConnectionForm(
                        connectionType = ConnectionFormTypes.CLIENT_SERVER,
                        fields =
                            listOf(
                                ConnectionField(
                                    id = "serviceName",
                                    labelKey = StrKey.FIELD_SERVICE_NAME,
                                    type = FieldType.TEXT,
                                    required = true,
                                ),
                                ConnectionField(
                                    id = "principal",
                                    labelKey = StrKey.FIELD_KERBEROS_PRINCIPAL,
                                    type = FieldType.TEXT,
                                    authMethods = listOf(AuthMethod.KERBEROS),
                                ),
                                ConnectionField(
                                    id = "keytabPath",
                                    labelKey = StrKey.FIELD_KEYTAB_PATH,
                                    type = FieldType.TEXT,
                                    authMethods = listOf(AuthMethod.KERBEROS),
                                ),
                            ),
                    ),
                ),
            authMethods = listOf(AuthMethod.PASSWORD, AuthMethod.KERBEROS),
            defaultPort = 1234,
        )
}
