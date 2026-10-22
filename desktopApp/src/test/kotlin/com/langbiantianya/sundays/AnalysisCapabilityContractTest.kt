/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.dialect.DialectCapability
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.handlers.SystemHandler
import com.langbiantianya.loader.DialectLoader
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分析模式的**能力位契约** —— 哪些方言能当分析数据源。
 *
 * ## 为什么要有这条测试
 *
 * 界面按能力位筛数据源（「哪些连接能进分析模式」），而能力位是**引擎侧**的声明。
 * 前端自己写一份「MySQL/PG 可分析」的名单就是第二个真相源 ——
 * 引擎改了声明而前端没改，界面就会给出「点进去必然失败」的入口。
 *
 * 所以这里直接向引擎索取真实的能力位集合，把「谁声明了什么」钉住：
 * 任何一侧改了声明而另一侧没跟上，这条测试都会红。
 *
 * ## 判据为什么是「远端 vs 本地」
 *
 * `ANALYSIS_SOURCE` 的语义是「这个方言可以作为分析模式的**远端数据源**」。
 * H2 / DuckDB / SQLite 是**本地**库 —— 分析模式的计算引擎是 DuckDB 自己，
 * 它不是「数据源」，所以这三个不声明。
 */
class AnalysisCapabilityContractTest {
    @Before
    fun setUp() {
        // IdbEngine bootstrap 是全局一次性的，其它测试类调过 engine.close() 会清空方言表。
        // loadFromDir 会重新扫一遍应用类路径 SPI（幂等，已存在的名字不覆盖）。
        IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
        DialectLoader.loadFromDir(File("/nonexistent"))
    }

    private fun capabilitiesOf(driverName: String): List<String> {
        val info =
            SystemHandler
                .listDrivers()
                .itemsList
                .firstOrNull { it.driverName == driverName }
                ?: error("引擎没有注册方言 $driverName")
        return info.capabilitiesList.toList()
    }

    @Test
    fun `mysql and postgresql declare ANALYSIS_SOURCE`() {
        // 这两个是**可写**方言 —— 它们进分析模式后，会话级只读闸门（L4）
        // 是唯一挡在数据前面的引擎侧防线。所以「它们声明了可分析」这件事
        // 必须与「L4 闸门存在且有回归测试」一起成立，否则就是开了个能写远端的口子。
        listOf("Mysql", "Postgresql").forEach { driver ->
            val caps = capabilitiesOf(driver)
            assertTrue(
                caps.contains(DialectCapability.ANALYSIS_SOURCE.name),
                "$driver 应当声明 ${DialectCapability.ANALYSIS_SOURCE.name}，实际能力位是 $caps",
            )
        }
    }

    @Test
    fun `local embedded dialects do not declare ANALYSIS_SOURCE`() {
        // 本地库不是「远端数据源」：分析模式的计算引擎是 DuckDB 自己，
        // 把它也列成数据源会让人以为可以拉自己。
        listOf("H2", "Duckdb", "Sqlite").forEach { driver ->
            val caps = capabilitiesOf(driver)
            assertFalse(
                caps.contains(DialectCapability.ANALYSIS_SOURCE.name),
                "$driver 是本地库，不该声明 ${DialectCapability.ANALYSIS_SOURCE.name}，实际能力位是 $caps",
            )
        }
    }

    @Test
    fun `the capability reaches the frontend under its enum name`() {
        // 前端比对的是**枚举名**（`DialectCapability.name`），不是下标 ——
        // 下标会随枚举增删而漂移。这条断言把「引擎下发的字符串」与
        // 「前端要比对的字符串」钉在同一处。
        val caps = capabilitiesOf("Mysql")
        assertTrue(
            caps.contains("ANALYSIS_SOURCE"),
            "引擎下发的能力位里应当有裸名字 ANALYSIS_SOURCE，实际是 $caps",
        )
    }
}
