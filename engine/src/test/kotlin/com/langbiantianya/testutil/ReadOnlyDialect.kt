/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.testutil

import com.langbiantianya.dialect.DatabaseDialect
import com.langbiantianya.dialect.DialectCapability
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import java.sql.Connection

/**
 * 测试用的**只读方言** —— 主设计 §3.6 第 1 类回归测试（L3 方言级）需要的那一个。
 *
 * ## 它模拟的是什么
 *
 * 真实的只读方言是 Hive / Impala（M3 才落地）。它们**整个 SPI 写方法面都不实现**，
 * 于是引擎调过去时，SPI 的默认实现替它们抛 `UnsupportedOperationException`。
 *
 * ## 为什么用「委托 + 覆写」而不是「整个实现一遍」
 *
 * `DatabaseDialect` 有四十来个成员，全部实现一遍全是 `TODO()` —— 那是一堆
 * **骗人的**代码（读起来像实现了，其实一调就炸）。委托给 [H2Dialect] 更诚实：
 * 这个假方言**只**比它多一样东西 —— 那个「只读」的能力位。
 *
 * ## ⚠️ 它的只读覆盖面是**有意的、不完整**的
 *
 * 委托意味着它继承了 H2 的写实现，所以：
 *
 * - **建表 / 写数据**（TABLE.CREATE / DATA.*）被挡住，靠的是
 *   [com.langbiantianya.handlers.requireWritable] 这道 L3 闸门 ——
 *   那正是本测试要验的东西（这两条路**根本不过 SPI 的写方法**）。
 * - **建用户**（USER.CREATE）被挡住，靠的是本类覆写 [createUser] 抛异常 ——
 *   真实只读方言的做法是「不实现这个 SPI 方法」，效果一样。
 * - 其余 SPI 写方法（建视图 / 建索引 / 改表名 …）这里仍然继承 H2 的实现，
 *   所以**这个假方言不是完整的只读方言**。真实的 Hive / Impala 不会有这个缺口，
 *   它们的整个写方法面都是空的。
 *
 * 这一点写在 KDoc 里，是因为「测试通过」很容易被读成「只读方言已经完备」——
 * 那是假的。
 *
 * @param base 被委托的底座方言（提供全部与只读无关的能力）
 */
class ReadOnlyDialect(
    private val base: DatabaseDialect = H2Dialect(),
) : DatabaseDialect by base {
    override val driverName = "ReadOnlyFake"

    /**
     * 加上 `READ_ONLY_DIALECT` 能力位。
     *
     * 这是「这个方言只读」的**唯一声明** —— 引擎的 L3 闸门读的就是它。
     */
    override val capabilities: Set<DialectCapability> =
        base.capabilities + DialectCapability.READ_ONLY_DIALECT

    /**
     * 建用户 —— 只读方言**不实现**这个 SPI 方法，调用方拿到的是明确错误。
     *
     * 真实只读方言靠的是「SPI 方法不覆写 → 默认实现抛异常」；
     * 这里必须显式覆写，因为本类委托给了 H2，而 H2 **实现了**它。
     */
    override suspend fun createUser(
        conn: Connection,
        user: String,
        password: String,
        host: String,
    ): Boolean = throw UnsupportedOperationException(Msg.format(StrKey.DIALECT_READ_ONLY, driverName))
}
