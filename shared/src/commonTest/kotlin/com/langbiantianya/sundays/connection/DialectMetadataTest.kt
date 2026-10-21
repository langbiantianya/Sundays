/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 方言元数据表测试 —— 钉住「**一处**说清方言是什么」这个契约。
 *
 * ## 为什么这些断言看着像在测 getter
 *
 * 因为这张表存在的全部意义，就是**消灭**散落各处的硬编码 `when`。
 * 一旦有人在 `ConnectionConfig` / `DialectType` / `DialectCapabilities` 里
 * 偷偷加回一份自己的 `when`，表就会与它们**悄悄分叉** ——
 * 「界面说 3306、引擎那边是 5432」这类错配要等到连库失败才暴露。
 *
 * 所以这里断言的是「**所有入口读的是同一张表**」，而不是某个具体数值。
 */
class DialectMetadataTest {
    @Test
    fun `every dialect has a metadata entry`() {
        DialectType.entries.forEach { dialect ->
            val meta = DialectMetadata.of(dialect)
            assertEquals(dialect, meta.dialect, "元数据的 dialect 字段与查表键不一致")
            assertTrue(meta.displayName.isNotBlank(), "${dialect.name} 缺少显示名")
            assertTrue(meta.engineDriverName.isNotBlank(), "${dialect.name} 缺少引擎注册名")
        }
    }

    /**
     * 三个入口（默认连接类型 / 可选连接类型 / 引擎注册名）必须来自**同一张表**。
     *
     * 断言的是「一致」而不是「等于某个常量」—— 数值改了要改表，
     * 但**不允许**在表之外再有一份。
     */
    @Test
    fun `the three legacy lookups all read the same table`() {
        DialectType.entries.forEach { dialect ->
            val meta = DialectMetadata.of(dialect)
            val (defaultType, defaultPort) = ConnectionConfig.defaultsFor(dialect)
            assertEquals(meta.defaultConnectionType, defaultType, "${dialect.name} 默认连接类型与表不一致")
            assertEquals(meta.defaultPort, defaultPort, "${dialect.name} 默认端口与表不一致")
            assertEquals(meta.supportedConnectionTypes, dialect.supportedConnectionTypes, "${dialect.name} 可选连接类型与表不一致")
            assertEquals(meta.engineDriverName, dialect.engineDriverName, "${dialect.name} 引擎注册名与表不一致")
            assertEquals(meta.jdbcUrlPrefix, dialect.jdbcUrlPrefix, "${dialect.name} URL 前缀与表不一致")
        }
    }

    /** 未知方言走**最保守**的一份：不放外键、不承诺回滚、没有任何连接类型可选。 */
    @Test
    fun `unknown dialect is the most conservative one`() {
        val meta = DialectMetadata.of(DialectType.UNKNOWN)
        assertTrue(!meta.supportsForeignKeys, "未知方言不该放外键")
        assertTrue(!meta.ddlRollbackable, "未知方言不该承诺 DDL 可回滚")
        assertTrue(meta.supportedConnectionTypes.isEmpty(), "未知方言不该有可选连接类型")
        assertNull(meta.descriptionKey, "未知方言没有说明文案")
    }

    /**
     * 方言的 URL 前缀必须**与它自己折算出来的 URL 一致**。
     *
     * 这条前缀不只是给人看的：它是「这条连接是不是自定义 URL」的**唯一判据**
     * （见 `ConnectionStorage` 里 `toConnectionConfig` 的 `isCustomUrl`）——
     * 前缀对不上，就说明用户写的是一条自己的 URL。
     *
     * 所以前缀与折算规则**必须同源**：两者分家的话，
     * 「存进去再加载」会把自定义 URL 认成普通连接。
     */
    @Test
    fun `the url prefix agrees with what the dialect actually builds`() {
        DialectType.entries.filter { it != DialectType.UNKNOWN }.forEach { dialect ->
            val meta = DialectMetadata.of(dialect)
            val built =
                buildJdbcUrl(
                    ConnectionConfig(
                        id = "x",
                        name = "x",
                        dialect = dialect,
                        host = "example.invalid",
                        port = 1234,
                        database = "db",
                    ),
                )
            assertTrue(
                built.startsWith(meta.jdbcUrlPrefix, ignoreCase = true),
                "${dialect.name} 折算出的 URL [$built] 与前缀 [${meta.jdbcUrlPrefix}] 对不上",
            )
        }
    }

    /** 每个方言都至少有一个可选连接类型（否则向导第 2 步是空的）。 */
    @Test
    fun `every known dialect offers at least one connection type`() {
        DialectType.entries.filter { it != DialectType.UNKNOWN }.forEach { dialect ->
            assertTrue(
                DialectMetadata.of(dialect).supportedConnectionTypes.isNotEmpty(),
                "${dialect.name} 没有任何可选连接类型",
            )
        }
    }

    /** 自定义 URL 逃生舱：每个已知方言都能选它 —— 否则这条逃生舱形同虚设。 */
    @Test
    fun `every known dialect offers the custom url escape hatch`() {
        DialectType.entries.filter { it != DialectType.UNKNOWN }.forEach { dialect ->
            assertTrue(
                ConnectionType.CUSTOM_URL in DialectMetadata.of(dialect).supportedConnectionTypes,
                "${dialect.name} 不支持自定义 JDBC URL",
            )
        }
    }

    /** 方言描述文案键必须存在（`null` 只有未知方言允许）。 */
    @Test
    fun `known dialects have a description key`() {
        DialectType.entries.filter { it != DialectType.UNKNOWN }.forEach { dialect ->
            assertNotNull(DialectMetadata.of(dialect).descriptionKey, "${dialect.name} 缺少说明文案键")
        }
    }
}
