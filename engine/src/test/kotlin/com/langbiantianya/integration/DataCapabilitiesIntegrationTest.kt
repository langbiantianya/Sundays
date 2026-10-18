/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.DataListRequest
import com.langbiantianya.grpc.EmptyValuePolicy
import com.langbiantianya.grpc.TableCapabilities
import com.langbiantianya.handlers.DataHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DATA.LIST` 随行回写的 [TableCapabilities] —— 前端据此决定「编辑 / 新增 / 删除」入口能不能用。
 *
 * 这些用例的价值不在于「按钮亮不亮」，而在于**亮错了的代价**：
 * 把视图当真表会把不可写的对象交给写入口，把二进制列判成可编辑会用 `[LOB Data]`
 * 占位串覆盖掉用户数据（不可逆）。所以「必须为 false」的那几条比「必须为 true」更关键。
 */
class DataCapabilitiesIntegrationTest : H2Fixture() {
    // H2 把未加引号的标识符存为大写，listColumns 回的列名也是大写 —— 比对一律忽略大小写
    private fun List<String>.hasColumn(name: String): Boolean = any { it.equals(name, ignoreCase = true) }

    private suspend fun capabilitiesOf(tableName: String): TableCapabilities {
        val result =
            DataHandler.list(
                config,
                DataListRequest
                    .newBuilder()
                    .setTableName(tableName)
                    .setPage(1)
                    .setPageSize(5)
                    .build(),
            )
        assertTrue(result.hasCapabilities(), "分页 LIST 必须回 capabilities")
        return result.capabilities
    }

    @Test
    fun `单列自增主键表三项能力全开，主键列不可编辑且不可新增`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE users (id INT AUTO_INCREMENT PRIMARY KEY, age INT, name VARCHAR(50))")
            executeUpdate("INSERT INTO users (age, name) VALUES (20, 'a')")

            val caps = capabilitiesOf("users")

            assertTrue(caps.canUpdate)
            assertTrue(caps.canDelete)
            assertTrue(caps.canInsert)
            // 主键列不进可编辑集 —— 改主键等于换一行
            assertTrue(caps.readOnlyColumnsList.hasColumn("id"))
            assertFalse(caps.editableColumnsList.hasColumn("id"))
            // 自增列交给数据库生成，前端不该提供输入框（填了会撞序列）
            assertFalse(caps.insertableColumnsList.hasColumn("id"))
            assertTrue(caps.insertableColumnsList.hasColumn("age"))
            assertTrue(caps.insertableColumnsList.hasColumn("name"))
        }

    @Test
    fun `没有主键的表不能改不能删，但照样能新增`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE logs (ts INT, msg VARCHAR(50))")

            val caps = capabilitiesOf("logs")

            // 没有主键就没有 WHERE 的锚点：批量改 / 删会波及整表，入口必须灭掉
            assertFalse(caps.canUpdate)
            assertFalse(caps.canDelete)
            // 但 INSERT 不需要主键 —— 这是本条用例最容易写反的地方
            assertTrue(caps.canInsert)
            assertTrue(caps.insertableColumnsList.hasColumn("ts"))
            assertTrue(caps.insertableColumnsList.hasColumn("msg"))
        }

    @Test
    fun `视图即使有列也不放行改与删`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE base (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO base VALUES (1, 'a')")
            executeUpdate("CREATE VIEW base_view AS SELECT id, name FROM base")

            // 先确认底表本身是能改的 —— 否则本用例证明不了「是视图这条规则在起作用」
            val baseCaps = capabilitiesOf("base")
            assertTrue(baseCaps.canUpdate)
            assertTrue(baseCaps.canDelete)

            val viewCaps = capabilitiesOf("base_view")
            assertFalse(viewCaps.canUpdate, "视图不得放行编辑")
            assertFalse(viewCaps.canDelete, "视图不得放行删除")
        }

    @Test
    fun `BLOB 与 VARBINARY 列进只读集且不进新增集，同表 VARCHAR 列不受影响`() =
        runBlocking<Unit> {
            executeUpdate(
                "CREATE TABLE assets (id INT PRIMARY KEY, label VARCHAR(50), blob_data BLOB, bin_data VARBINARY)",
            )

            val caps = capabilitiesOf("assets")

            // DATA.LIST 对二进制列回的是 "[LOB Data]" 占位符，写回去等于用提示语覆盖数据
            assertTrue(caps.readOnlyColumnsList.hasColumn("blob_data"))
            assertTrue(caps.readOnlyColumnsList.hasColumn("bin_data"))
            assertTrue(caps.readOnlyColumnsList.hasColumn("id"))
            assertFalse(caps.editableColumnsList.hasColumn("blob_data"))
            assertFalse(caps.editableColumnsList.hasColumn("bin_data"))
            assertFalse(caps.insertableColumnsList.hasColumn("blob_data"))
            assertFalse(caps.insertableColumnsList.hasColumn("bin_data"))
            // 同表的普通文本列不受牵连 —— 判据必须是「这一列」，不是「这张表有二进制列」
            assertTrue(caps.editableColumnsList.hasColumn("label"))
            assertTrue(caps.insertableColumnsList.hasColumn("label"))
        }

    @Test
    fun `空串语义固定为写空串`() =
        runBlocking<Unit> {
            executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")

            val caps = capabilitiesOf("t")

            assertEquals(EmptyValuePolicy.EMPTY_AS_STRING, caps.emptyValuePolicy)
        }

    @Test
    fun `流式分支不回 capabilities`() =
        runBlocking<Unit> {
            // 流式分支只用来逐行回 frame，前端不会拿它做写操作 —— 为它查元数据是纯浪费，
            // 回一份「看着能用其实没人用」的能力反而会诱使人拿流式响应当权限依据
            executeUpdate("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
            executeUpdate("INSERT INTO users VALUES (1, 'a')")

            val result =
                DataHandler.list(
                    config,
                    DataListRequest
                        .newBuilder()
                        .setTableName("users")
                        .setPageSize(0)
                        .build(),
                ) { _ -> }

            assertFalse(result.hasCapabilities(), "流式分支不应填 capabilities")
        }
}
