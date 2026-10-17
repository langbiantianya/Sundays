/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.FunctionCallRequest
import com.langbiantianya.grpc.FunctionCreateRequest
import com.langbiantianya.grpc.FunctionDebugRequest
import com.langbiantianya.grpc.FunctionDeleteRequest
import com.langbiantianya.grpc.FunctionInfoRequest
import com.langbiantianya.grpc.FunctionListRequest
import com.langbiantianya.grpc.FunctionValidateRequest
import com.langbiantianya.grpc.functionCreateRequest
import com.langbiantianya.grpc.functionDebugRequest
import com.langbiantianya.grpc.functionInfoRequest
import com.langbiantianya.grpc.functionListRequest
import com.langbiantianya.handlers.FunctionHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionHandlerIntegrationTest : H2Fixture() {
    @Test
    fun `LIST returns empty when no routines exist`() =
        runBlocking {
            val result =
                FunctionHandler.list(
                    config,
                    functionListRequest { schema = "PUBLIC" },
                )
            assertEquals(0, result.itemsCount)
        }

    @Test
    fun `CREATE a Java function alias via DDL then LIST shows it`() =
        runBlocking {
            val ddl = "CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\""
            val result =
                FunctionHandler.create(
                    config,
                    functionCreateRequest { this.ddl = ddl },
                )
            assertTrue(result.success)

            val list =
                FunctionHandler.list(
                    config,
                    functionListRequest { schema = "PUBLIC" },
                )
            assertTrue(list.itemsList.any { it.name.equals("my_func", ignoreCase = true) })
        }

    @Test
    fun `CALL a function alias returns result`() =
        runBlocking<Unit> {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS my_abs FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            val result =
                FunctionHandler.call(
                    config,
                    FunctionCallRequest
                        .newBuilder()
                        .setName("my_abs")
                        .setRoutineType("FUNCTION")
                        .setSchema("PUBLIC")
                        .addArgs("3.141592653589793")
                        .build(),
                )
            // Function.call 返回统一结构（Struct with key "result"）
            assertEquals(
                com.google.protobuf.Value.KindCase.STRUCT_VALUE,
                result.result.kindCase,
            )
            val struct = result.result.structValue.fieldsMap
            assertNotNull(struct["result"])
            val numberValue = struct["result"]!!.numberValue
            assertEquals(180.0, numberValue, 0.001)
        }

    @Test
    fun `INFO returns function info`() =
        runBlocking {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            val info =
                FunctionHandler.info(
                    config,
                    functionInfoRequest {
                        name = "my_func"
                        schema = "PUBLIC"
                    },
                )
            assertTrue(
                info.info.structValue.fieldsMap["name"]
                    ?.stringValue
                    ?.equals("my_func", ignoreCase = true) == true,
            )
            assertNotNull(info.info.structValue.fieldsMap["routine_type"])
        }

    @Test
    fun `DELETE drops a function`() =
        runBlocking {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS tmp_func FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            val delete =
                FunctionHandler.delete(
                    config,
                    FunctionDeleteRequest
                        .newBuilder()
                        .setName("tmp_func")
                        .setRoutineType("FUNCTION")
                        .setSchema("PUBLIC")
                        .setIfExists(true)
                        .build(),
                )
            assertTrue(delete.success)

            val after =
                FunctionHandler.list(
                    config,
                    functionListRequest { schema = "PUBLIC" },
                )
            assertTrue(after.itemsList.none { it.name.equals("tmp_func", ignoreCase = true) })
        }

    @Test
    fun `VALIDATE returns valid=true for good DDL`() =
        runBlocking {
            val result =
                FunctionHandler.validate(
                    config,
                    FunctionValidateRequest
                        .newBuilder()
                        .setDdl("CREATE ALIAS good_func FOR \"java.lang.Math.toDegrees\"")
                        .build(),
                )
            assertTrue(result.valid)
        }

    @Test
    fun `DEBUG returns EXPLAIN and INFO for function`() =
        runBlocking {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS debug_func FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            val debug =
                FunctionHandler.debug(
                    config,
                    functionDebugRequest {
                        name = "debug_func"
                        schema = "PUBLIC"
                    },
                )
            assertTrue(debug.itemsList.any { it.type in listOf("EXPLAIN", "INFO") })
        }

    @Test
    fun `CALL returns unified result with routine_type and schema fields`() =
        runBlocking {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS unified_func FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            val result =
                FunctionHandler.call(
                    config,
                    FunctionCallRequest
                        .newBuilder()
                        .setName("unified_func")
                        .setRoutineType("FUNCTION")
                        .setSchema("PUBLIC")
                        .addArgs("3.141592653589793")
                        .build(),
                )
            // Phase H 统一返回形状
            assertEquals(
                "FUNCTION",
                result.result.structValue.fieldsMap["routine_type"]
                    ?.stringValue,
            )
            assertNotNull(result.result.structValue.fieldsMap["schema"])
            assertNotNull(result.result.structValue.fieldsMap["result"])
        }

    @Test
    fun `CALL with null arg does not throw`() =
        runBlocking {
            FunctionHandler.create(
                config,
                FunctionCreateRequest
                    .newBuilder()
                    .setDdl("CREATE ALIAS to_deg_safe FOR \"java.lang.Math.toDegrees\"")
                    .build(),
            )
            // 单参方法，传 null — 应绑定为 SQL NULL，不报错
            val result =
                FunctionHandler.call(
                    config,
                    FunctionCallRequest
                        .newBuilder()
                        .setName("to_deg_safe")
                        .setRoutineType("FUNCTION")
                        .setSchema("PUBLIC")
                        .build(),
                )
            assertNotNull(result)
        }

    @Test
    fun `VALIDATE accepts valid ALIAS DDL`() =
        runBlocking {
            val result =
                FunctionHandler.validate(
                    config,
                    FunctionValidateRequest
                        .newBuilder()
                        .setDdl("""CREATE ALIAS "valid_one" FOR "java.lang.Math.toDegrees"""")
                        .build(),
                )
            assertTrue(result.valid)
        }

    @Test
    fun `VALIDATE rejects multi-statement`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                FunctionHandler.validate(
                    config,
                    FunctionValidateRequest
                        .newBuilder()
                        .setDdl("""CREATE ALIAS a FOR "java.lang.Math.toDegrees"; DROP ALIAS b""")
                        .build(),
                )
            }
        }
    }
}
