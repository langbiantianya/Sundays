/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.table

import com.langbiantianya.sundays.connection.DialectMetadata
import com.langbiantianya.sundays.connection.DialectType

/**
 * 一个方言在前端侧的能力判断。
 *
 * @property supportsForeignKeys 该方言支不支持外键 —— 不支持就不画外键编辑区。
 * @property ddlRollbackable **DDL 能不能真的回滚**。这与引擎 `DialectCapability` 里
 *   那个同名标志**不是一回事**，见 [DialectCapabilities] 的说明。
 */
data class DialectFeatures(
    val supportsForeignKeys: Boolean,
    val ddlRollbackable: Boolean,
)

/**
 * 方言能力 —— 前端侧的单一事实来源。
 *
 * ## 为什么不直接用引擎的 `DialectCapability`
 *
 * 两个原因，第二个是致命的：
 *
 * 1. 那个枚举在 `:api` / 引擎侧，前端要拿到它得先 `SYSTEM.LIST_DRIVERS` 往返一趟；
 *    而这些判断在**画界面前**就要用，为了画个按钮发一次引擎请求不划算。
 * 2. **它表达的粒度不够。** `DialectCapability.DDL_TRANSACTION` 只说「引擎会把这批
 *    DDL 当成能包进事务的」——可 MySQL **和 H2** 都声明了它，而这两个库其实都会对
 *    DDL 隐式提交（见 `DialectCapability` 的 KDoc）。照着那个标志去写「全成功或全不做」
 *    的提示文案，就是在骗用户。
 *
 * 所以这里单开一份，并把「真能回滚」和「引擎会那样认为」拆成两件事。
 * 与引擎的一致性由 `DialectCapabilitiesContractTest` 对照真实
 * `SYSTEM.LIST_DRIVERS` 输出校验。
 *
 * ## 数据从哪来
 *
 * 全部读 [com.langbiantianya.sundays.connection.DialectMetadata] 的同一张表 ——
 * 与 `DialectType.supportedConnectionTypes` / `engineDriverName` / `defaultsFor`
 * 同源。加一个方言只需要在**那张表**里加一条，不必在这里再加一个分支。
 */
object DialectCapabilities {
    /**
     * 取某个方言的能力。
     *
     * [DialectType.UNKNOWN] 走**最保守**的一份：不放外键、不承诺回滚。
     * 没连上库 / 连不上时界面上的写入口应当一律收紧 —— 这是与
     * `TableCapabilities` 那条「老引擎一律按只读处理」同一个原则：
     * 宁可少给，不给一个点了必然失败的东西。
     */
    fun of(dialect: DialectType): DialectFeatures {
        val meta = DialectMetadata.of(dialect)
        return DialectFeatures(
            supportsForeignKeys = meta.supportsForeignKeys,
            ddlRollbackable = meta.ddlRollbackable,
        )
    }
}
