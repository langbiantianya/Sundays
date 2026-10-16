/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.ForeignKeyCreateRequest
import com.langbiantianya.grpc.ForeignKeyDeleteRequest
import com.langbiantianya.grpc.ForeignKeyListRequest
import com.langbiantianya.handlers.ForeignKeyHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.langbiantianya.grpc.foreignKeyListRequest

class ForeignKeyHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns no foreign keys initially`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY)")
        val result = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertEquals(0, result.itemsCount)
    }

    @Test
    fun `CREATE then LIST then DELETE foreign key`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(
            config,
            ForeignKeyCreateRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .addColumns("user_id")
                .setRefTable("users")
                .addRefColumns("id")
                .setOnDelete("CASCADE")
                .build()
        )

        var fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertTrue(fks.itemsList.any { it.name.equals("fk_orders_user", ignoreCase = true) }, "FK 应在列表中")

        ForeignKeyHandler.delete(
            config,
            ForeignKeyDeleteRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .build()
        )

        fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertFalse(fks.itemsList.any { it.name.equals("fk_orders_user", ignoreCase = true) })
    }

    @Test
    fun `LIST returns on_delete cascade rule`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(
            config,
            ForeignKeyCreateRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .addColumns("user_id")
                .setRefTable("users")
                .addRefColumns("id")
                .setOnDelete("CASCADE")
                .build()
        )

        val fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        val fk = fks.itemsList.first { it.name.equals("fk_orders_user", ignoreCase = true) }
        assertEquals("CASCADE", fk.onDelete)
    }
}
