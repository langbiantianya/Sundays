/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.client.EngineClient
import com.langbiantianya.dialect.DialectCapability
import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.DialectInfo
import com.langbiantianya.grpc.connectionConfig
import com.langbiantianya.grpc.toSpec
import com.langbiantianya.sundays.connection.DialectFormRegistry
import com.langbiantianya.sundays.connection.DialectType

/**
 * 引擎下发的方言列表 → 前端的连接表单注册表。
 *
 * ## 这一层只做**换算**，不解释
 *
 * 引擎那边用 `driverName`（`"Mysql"` / `"Postgresql"` / …）标识方言，
 * 前端这边用 [DialectType]（`MYSQL` / `POSTGRESQL` / …）。两边**大小写不同**，
 * 而且不是简单的大小写关系（`Duckdb` ↔ `DUCKDB` 还算，`Sqlite` ↔ `SQLITE` 也算，
 * 但 `Postgresql` ↔ `POSTGRESQL` 的大小写规则并不通用）。
 *
 * 所以换算走前端已有的那张表（[DialectType.engineDriverName]）——
 * 那张表**已经**被 `DialectNameContractTest` 钉住与引擎的真实注册名一致，
 * 不在这里另写一份映射（那会多一个能漂移的地方）。
 *
 * ## 换不出来的方言**丢掉**，不报错
 *
 * 引擎加载了一个前端还不认识的方言插件时，那条规格对前端没有意义
 * （前端连它的枚举值都没有）。丢掉它 = 那个方言**不出现**在表单里，
 * 而不是「出现一个点不动的选项」—— 后者正是仓库原则要挡的事：
 * **宁可少给，不给一个点了必然失败的东西**。
 */
object DialectFormRegistryFactory {
    /**
     * 把 `SYSTEM.LIST_DRIVERS` 的返回值换算成注册表。
     *
     * @param infos 引擎下发的方言列表
     */
    fun fromDialectInfos(infos: List<DialectInfo>): DialectFormRegistry = catalogFrom(infos).forms

    /**
     * 把 `SYSTEM.LIST_DRIVERS` 的返回值换算成**目录**（表单 + 能力位）。
     *
     * @param infos 引擎下发的方言列表
     */
    internal fun catalogFrom(infos: List<DialectInfo>): DialectCatalog {
        val pairs =
            infos.mapNotNull { info ->
                val dialect = dialectFor(info.driverName) ?: return@mapNotNull null
                // 没声明表单规格的方言**不登记** → 前端对它回退旧的枚举分支。
                if (!info.hasConnectionForm()) return@mapNotNull null
                dialect to info.connectionForm.toSpec()
            }
        // 能力位按**引擎说的**算，不在前端另写一份名单。
        // 引擎下发的 `capabilities` 是能力位枚举的**名字**（`DialectCapability.name`），
        // 所以这里比对枚举名而不是下标 —— 下标会随枚举增删而漂移。
        val analysisCapable =
            infos
                .filter { info -> info.capabilitiesList.contains(DialectCapability.ANALYSIS_SOURCE.name) }
                .mapNotNull { info -> dialectFor(info.driverName) }
                .toSet()
        return DialectCatalog(DialectFormRegistry(pairs.toMap()), analysisCapable)
    }

    /** 引擎的注册名 → 前端的方言枚举；认不出来返回 `null`。 */
    private fun dialectFor(engineDriverName: String): DialectType? =
        DialectType.entries.firstOrNull {
            it != DialectType.UNKNOWN && it.engineDriverName == engineDriverName
        }
}

/**
 * 引擎下发的一次方言元数据 —— 前端需要的**两半**。
 *
 * ## 为什么合成一个
 *
 * 两半都来自**同一次** `SYSTEM.LIST_DRIVERS`：
 *
 * - [forms] —— 连接表单规格（连得上要填什么）
 * - [analysisCapableDialects] —— 哪些方言声明了「可作为分析数据源」
 *
 * 分两次拉会让两个快照**不在同一时刻** —— 方言插件热插拔时，
 * 前后端看到的方言集合可能对不上。所以一次查询、一次换算。
 *
 * @param forms 连接表单注册表（按方言查规格）
 * @param analysisCapableDialects 声明了 `ANALYSIS_SOURCE` 的方言（前端枚举口径）
 */
internal data class DialectCatalog(
    val forms: DialectFormRegistry,
    val analysisCapableDialects: Set<DialectType>,
)

/**
 * 向引擎要一次方言列表，把其中的连接表单规格收进注册表。
 *
 * ## 这是**一次性**的元数据查询
 *
 * 与 `SYSTEM.INFO` 同一性质：不需要连接、不连库、只回静态声明。
 * 所以它在应用启动时拉一次就够 —— **不是**每次建连接都跑一趟。
 *
 * ## 拉失败 → 空注册表
 *
 * 空注册表意味着「所有方言都走旧的枚举分支」—— 界面照常能用。
 * 这比「引擎连不上就整个界面瘫掉」好得多：连接管理器的第一个用途
 * 恰恰是**去连一个还没连上的库**，而那时引擎可能正忙。
 */
internal suspend fun fetchDialectForms(engine: EngineClient): DialectFormRegistry = fetchDialectCatalog(engine).forms

/**
 * 向引擎要一次方言列表，换算成**目录**（连接表单 + 能力位）。
 *
 * ## 这是**一次性**的元数据查询
 *
 * 与 `SYSTEM.INFO` 同一性质：不需要连接、不连库、只回静态声明。
 * 所以它在应用启动时拉一次就够 —— **不是**每次建连接都跑一趟。
 *
 * ## 拉失败 → 空目录
 *
 * 空目录意味着「所有方言都走旧的枚举分支」—— 界面照常能用。
 * 这比「引擎连不上就整个界面瘫掉」好得多：连接管理器的第一个用途
 * 恰恰是**去连一个还没连上的库**，而那时引擎可能正忙。
 */
internal suspend fun fetchDialectCatalog(engine: EngineClient): DialectCatalog {
    val response =
        engine.invoke(connectionConfig { }) {
            category = Category.SYSTEM
            action = Action.LIST_DRIVERS
        }
    if (!response.success) return DialectCatalog(DialectFormRegistry.EMPTY, emptySet())
    return DialectFormRegistryFactory.catalogFrom(response.system.listDrivers.itemsList)
}
