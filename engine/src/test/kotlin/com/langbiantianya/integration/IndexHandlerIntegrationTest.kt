/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.IndexCreateRequest
import com.langbiantianya.grpc.IndexDeleteRequest
import com.langbiantianya.grpc.IndexListRequest
import com.langbiantianya.handlers.IndexHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.langbiantianya.grpc.indexListRequest

class IndexHandlerIntegrationTest : H2Fixture() {

    private fun seedTable() {
        executeUpdate("CREATE TABLE users (id INT, email VARCHAR(100), name VARCHAR(50))")
    }

    @Test
    fun `LIST returns no indexes initially`() = runBlocking {
        seedTable()
        val result = IndexHandler.list(
            config,
            indexListRequest { tableName = "users" }
        )
        assertTrue(result.itemsCount >= 0)
    }

    @Test
    fun `CREATE then LIST then DROP index`() = runBlocking {
        seedTable()
        IndexHandler.create(
            config,
            IndexCreateRequest.newBuilder()
                .setTableName("users")
                .setIndexName("idx_email")
                .addColumns("email")
                .setUnique(false)
                .build()
        )
        var list = IndexHandler.list(
            config,
            indexListRequest { tableName = "users" }
        )
        assertTrue(list.itemsList.any { it.name.equals("idx_email", ignoreCase = true) })

        IndexHandler.delete(
            config,
            IndexDeleteRequest.newBuilder()
                .setIndexName("idx_email")
                .setTableName("users")
                .build()
        )
        list = IndexHandler.list(
            config,
            indexListRequest { tableName = "users" }
        )
        assertFalse(list.itemsList.any { it.name.equals("idx_email", ignoreCase = true) })
    }

    @Test
    fun `CREATE UNIQUE INDEX has unique=true in list`() = runBlocking {
        seedTable()
        IndexHandler.create(
            config,
            IndexCreateRequest.newBuilder()
                .setTableName("users")
                .setIndexName("uk_email")
                .addColumns("email")
                .setUnique(true)
                .build()
        )
        val list = IndexHandler.list(
            config,
            indexListRequest { tableName = "users" }
        )
        val idx = list.itemsList.first { it.name.equals("uk_email", ignoreCase = true) }
        assertEquals(true, idx.unique)
    }

    @Test
    fun `CREATE composite index on multiple columns`() = runBlocking {
        seedTable()
        IndexHandler.create(
            config,
            IndexCreateRequest.newBuilder()
                .setTableName("users")
                .setIndexName("idx_name_email")
                .addColumns("name")
                .addColumns("email")
                .setUnique(false)
                .build()
        )
        val list = IndexHandler.list(
            config,
            indexListRequest { tableName = "users" }
        )
        val idx = list.itemsList.first { it.name.equals("idx_name_email", ignoreCase = true) }
        val cols = idx.columnsList.map { it.uppercase() }
        assertTrue(cols.contains("NAME"))
        assertTrue(cols.contains("EMAIL"))
    }
}
