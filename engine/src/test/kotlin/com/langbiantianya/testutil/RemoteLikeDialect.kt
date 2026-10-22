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

/**
 * 一个**看起来像远端**的假方言 —— 用来测「本地嵌入式方言」那条边界。
 *
 * ## 它与 [ReadOnlyDialect] 的区别
 *
 * 两个假方言各自缺一样东西，缺的东西正好互补：
 *
 * - [ReadOnlyDialect] 声明 `READ_ONLY_DIALECT` —— 缺的是**写**的许可
 * - 本类**不**声明 `EMBEDDED_MODE` —— 缺的是「本地嵌入式」这个身份
 *
 * ## 为什么非得造一个
 *
 * `ANALYSIS.RUN_QUERY` 只允许打在声明了 `EMBEDDED_MODE` 的方言上
 * （主设计 §3.4）。要测「远端被拒」这条边界，就得有一个**真的**不声明
 * `EMBEDDED_MODE` 的方言 —— 现有五个内置方言里 MySQL / PostgreSQL 满足，
 * 但它们要真连库才能测；本类复用 H2 的全部实现，只摘掉那个能力位，
 * 于是「被拒」可以在**不连任何数据库**的前提下断言。
 *
 * ## 覆盖面
 *
 * 与 [ReadOnlyDialect] 一样，它的覆盖面是**有意的、不完整的**：
 * 它模拟的是「一个远端方言在分析模式里的样子」，不是「一个完整的远端方言」。
 * `supportsSchema = false` 是刻意设的 —— MySQL 的 schema == database，
 * 「命名空间是库」正是靠这条分支走通的。
 */
class RemoteLikeDialect : DatabaseDialect by H2Dialect() {
    override val driverName: String = "RemoteLike"

    /** 远端方言不是本地嵌入式 —— `ANALYSIS.RUN_QUERY` 必须拒绝它。 */
    override val capabilities: Set<DialectCapability> =
        H2Dialect().capabilities - DialectCapability.EMBEDDED_MODE

    /**
     * 远端方言没有「第二级 schema」这一层 —— 命名空间就是库。
     *
     * 与 MySQL 一样。
     */
    override val supportsSchema: Boolean = false
}
