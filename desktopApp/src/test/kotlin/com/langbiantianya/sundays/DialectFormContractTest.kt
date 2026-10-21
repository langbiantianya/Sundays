/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.dialect.DuckDBDialect
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.dialect.MySQLDialect
import com.langbiantianya.dialect.PostgreSQLDialect
import com.langbiantianya.dialect.SQLiteDialect
import com.langbiantianya.dialect.contract.ConnectionFieldIds
import com.langbiantianya.dialect.contract.ConnectionFormTypes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.langbiantianya.dialect.ConnectionType as ApiConnectionType
import com.langbiantianya.sundays.connection.ConnectionType as FrontendConnectionType

/**
 * **连接类型名的三处一致性** —— 契约层用字符串（`ConnectionFormTypes`），
 * 而 `:api`（引擎 SPI）与 `:shared`（持久化格式）各有一份 `ConnectionType` 枚举。
 *
 * ## 为什么这条必须钉住
 *
 * 三处只要有一处漂移，症状是**静默的**：方言声明了一份 form，
 * 前端按连接类型去查却查不到，于是**回退旧分支** —— 界面看着完全正常，
 * 但「spec 驱动」这条链在那个方言上根本没生效，**没有任何报错**。
 *
 * 这正是契约层用字符串而不是第三份枚举的代价：省掉了一处定义，
 * 换来了一份「必须保持同步」的约定。约定靠人记是记不住的，只能靠测试。
 *
 * 这个测试放在 `desktopApp`（不是 `dialect-contract`）是因为它是**唯一同时看得见
 * 三处**的模块 —— 契约模块不能依赖 `:api`（会成环），`:api` 看不见 `:shared`。
 */
class DialectFormContractTest {
    /**
     * 契约认的连接类型 —— **值必须与常量名一字不差**。
     *
     * 写成 `常量 to 期望字面量` 的映射，是为了让「拼错」也能被抓到：
     * 假如有人把 `const val EMBEDDED = "EMBEDED"` 写错，光比对集合是发现不了的
     * （集合照样有 5 个元素），但字面量对不上。
     */
    private val contractTypeNames: Map<String, String> =
        mapOf(
            ConnectionFormTypes.CLIENT_SERVER to "CLIENT_SERVER",
            ConnectionFormTypes.EMBEDDED to "EMBEDDED",
            ConnectionFormTypes.IN_MEMORY to "IN_MEMORY",
            ConnectionFormTypes.FILE_BASED to "FILE_BASED",
            ConnectionFormTypes.CUSTOM_URL to "CUSTOM_URL",
        )

    /** 5 个内置方言 —— 它们的 spec 是本轮交付的一部分，一起纳入一致性检查。 */
    private val builtInDialects =
        listOf(
            MySQLDialect(),
            PostgreSQLDialect(),
            H2Dialect(),
            DuckDBDialect(),
            SQLiteDialect(),
        )

    @Test
    fun `契约里的连接类型名与常量名一字不差`() {
        contractTypeNames.forEach { (actual, expected) ->
            assertEquals(expected, actual, "契约的连接类型常量值拼错了：应为 $expected")
        }
    }

    @Test
    fun `契约认的连接类型前端都认得`() {
        val frontendNames = FrontendConnectionType.entries.map { it.name }.toSet()
        val unknown = contractTypeNames.keys - frontendNames
        assertTrue(unknown.isEmpty(), "契约里的连接类型前端枚举不认得：$unknown")
    }

    @Test
    fun `引擎用到的连接类型契约都认得`() {
        val apiNames = ApiConnectionType.entries.map { it.name }.toSet()
        val missing = apiNames - contractTypeNames.keys
        assertTrue(missing.isEmpty(), "引擎的连接类型契约没认得：$missing")
    }

    @Test
    fun `内置方言声明的 form 前端都查得到`() {
        val frontendNames = FrontendConnectionType.entries.map { it.name }.toSet()
        builtInDialects.forEach { dialect ->
            dialect.connectionForm.forms.forEach { form ->
                assertTrue(
                    form.connectionType in frontendNames,
                    "${dialect.driverName} 声明了前端查不到的 form：${form.connectionType}",
                )
            }
        }
    }

    @Test
    fun `同一份 form 里的字段 id 不重复`() {
        builtInDialects.forEach { dialect ->
            dialect.connectionForm.forms.forEach { form ->
                val ids = form.fields.map { it.id }
                val counts = ids.groupingBy { it }.eachCount()
                val duplicated = counts.filterValues { it > 1 }.keys
                assertTrue(
                    duplicated.isEmpty(),
                    "${dialect.driverName} 的 ${form.connectionType} form 有重复字段 id：$duplicated",
                )
            }
        }
    }

    @Test
    fun `约定字段 id 就那六个`() {
        assertEquals(
            setOf("host", "port", "database", "username", "password", "jdbcUrl"),
            setOf(
                ConnectionFieldIds.HOST,
                ConnectionFieldIds.PORT,
                ConnectionFieldIds.DATABASE,
                ConnectionFieldIds.USERNAME,
                ConnectionFieldIds.PASSWORD,
                ConnectionFieldIds.JDBC_URL,
            ),
            "约定字段 id 变了 —— 它们决定字段的值存进 ConnectionConfig 的哪个固定字段",
        )
    }
}
