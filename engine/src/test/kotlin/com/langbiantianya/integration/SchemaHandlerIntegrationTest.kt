/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.SchemaCreateRequest
import com.langbiantianya.grpc.SchemaDeleteRequest
import com.langbiantianya.grpc.SchemaListRequest
import com.langbiantianya.grpc.schemaCreateRequest
import com.langbiantianya.grpc.schemaDeleteRequest
import com.langbiantianya.grpc.schemaListRequest
import com.langbiantianya.handlers.SchemaHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaHandlerIntegrationTest : H2Fixture() {
    @Test
    fun `LIST defaults to database level — H2 returns current catalog`() =
        runBlocking {
            // 默认 level=database → H2 listDatabases 返回 [config.database]
            val result = SchemaHandler.list(config, SchemaListRequest.getDefaultInstance())
            assertEquals("database", result.level)
            assertTrue(result.itemsList.size == 1)
            assertTrue(result.itemsList.any { it.equals(dbName, ignoreCase = true) })
        }

    @Test
    fun `LIST level=database returns catalog`() =
        runBlocking {
            val result =
                SchemaHandler.list(
                    config,
                    schemaListRequest { level = "database" },
                )
            assertEquals("database", result.level)
            assertTrue(result.itemsList.any { it.equals(dbName, ignoreCase = true) })
        }

    @Test
    fun `LIST level=schema returns all schemas including PUBLIC`() =
        runBlocking {
            val result =
                SchemaHandler.list(
                    config,
                    schemaListRequest {
                        level = "schema"
                        database = dbName
                    },
                )
            assertEquals("schema", result.level)
            assertEquals(dbName, result.database)
            assertTrue(result.itemsList.size >= 1)
            assertTrue(result.itemsList.any { it.equals("PUBLIC", ignoreCase = true) })
        }

    @Test
    fun `LIST level=schema without database throws`() =
        runBlocking {
            val ex =
                kotlin
                    .runCatching {
                        SchemaHandler.list(
                            config,
                            schemaListRequest { level = "schema" },
                        )
                    }.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException but got ${ex?.javaClass?.simpleName}")
        }

    @Test
    fun `LIST level=invalid throws`() =
        runBlocking {
            val ex =
                kotlin
                    .runCatching {
                        SchemaHandler.list(
                            config,
                            schemaListRequest { level = "garbage" },
                        )
                    }.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException)
        }

    @Test
    fun `CREATE schema then DELETE schema`() =
        runBlocking {
            SchemaHandler.create(
                config,
                schemaCreateRequest { name = "test_schema" },
            )
            assertTrue(schemaExists("test_schema"))

            SchemaHandler.delete(
                config,
                schemaDeleteRequest { name = "test_schema" },
            )
            assertFalse(schemaExists("test_schema"))
        }
}
