/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.dialect.DialectCapability
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey

/**
 * L3 —— **方言级只读闸门**（主设计 §3.1 六层防线之一）。
 *
 * ## 它挡什么
 *
 * 声明了 [DialectCapability.READ_ONLY_DIALECT] 的方言上的**一切**写入。
 * 与会话级的 L4（`ReadOnlyGate`）互补：L4 挡的是「这个**会话**不许写」，
 * L3 挡的是「这个**方言**根本不该被写」—— 后者**与从哪个模式进来无关**。
 *
 * ## 为什么需要它 —— SPI 的默认「抛异常」**挡不住建表与写数据**
 *
 * 方言 SPI 里大多数写方法都有「默认抛 `UnsupportedOperationException`」的实现
 * （createView / createIndex / addForeignKey / createUser …），
 * 所以只读方言**不实现**它们就够了 —— 引擎调过去就会拿到一个明确的错误。
 *
 * 但**建表与写数据不走 SPI 的写方法**：引擎自己按方言给的
 * `buildColumnDefinition` / `quoteIdentifier` 拼出 DDL 与 DML，再走 JDBC 执行
 * （见 `TableHandler.create` / `DataHandler.create`）。
 * 于是「只读方言不实现写方法」这条推理，对
 * **TABLE.CREATE / TABLE.UPDATE / TABLE.DELETE /
 * DATA.CREATE / DATA.UPDATE / DATA.DELETE** 一条都不成立 ——
 * 那几条路在只读方言上照样能跑通。
 *
 * 这个洞是主设计 §3.1 的 L3 那一行（「挡得住**一切**」）与现实之间的落差，
 * 本函数就是补这个落差的。
 *
 * ## 为什么判据是**能力位**，不是新加一个 SPI 成员
 *
 * `DialectCapability.READ_ONLY_DIALECT` 已经是「这个方言只读」的**唯一声明**
 * （`api/.../DialectCapability.kt` 的 KDoc 写明它就是「方言级只读」这一件事）。
 * 再加一个 `supportsWrites` 成员，等于给同一件事造第二个名字 ——
 * 两个名字迟早会不一致，而不一致的方向一定是「某个方言漏声明了，于是被写了」。
 */
internal fun requireWritable(dialect: DatabaseDialect) {
    if (dialect.capabilities.contains(DialectCapability.READ_ONLY_DIALECT)) {
        throw UnsupportedOperationException(Msg.format(StrKey.DIALECT_READ_ONLY, dialect.driverName))
    }
}
