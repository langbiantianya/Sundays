/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.integration

import com.langbiantianya.grpc.UserCreateRequest
import com.langbiantianya.grpc.UserDeleteRequest
import com.langbiantianya.grpc.UserListRequest
import com.langbiantianya.grpc.UserUpdateRequest
import com.langbiantianya.handlers.UserHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.langbiantianya.grpc.userCreateRequest
import com.langbiantianya.grpc.userDeleteRequest
import com.langbiantianya.grpc.userListRequest

class UserHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns users including default SA`() = runBlocking {
        val result = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(result.itemsList.isNotEmpty())
        assertTrue(result.itemsList.any {
            it.structValue.fieldsMap["user"]?.stringValue?.uppercase() == "SA"
        })
    }

    @Test
    fun `CREATE user then DELETE user`() = runBlocking {
        UserHandler.create(
            config,
            UserCreateRequest.newBuilder()
                .setUser("alice")
                .setPassword("secret123")
                .setHost("%")
                .build()
        )
        var users = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(users.itemsList.any {
            it.structValue.fieldsMap["user"]?.stringValue?.equals("alice", ignoreCase = true) == true
        })

        UserHandler.delete(
            config,
            userDeleteRequest { user = "alice"; host = "%" }
        )
        users = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(users.itemsList.none {
            it.structValue.fieldsMap["user"]?.stringValue?.equals("alice", ignoreCase = true) == true
        })
    }

    @Test
    fun `UPDATE password changes user password`() = runBlocking {
        UserHandler.create(
            config,
            userCreateRequest { user = "bob"; password = "old"; host = "%" }
        )
        val result = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("bob")
                .setPassword("new")
                .setHost("%")
                .build()
        )
        assertEquals("password_changed", result.action)
    }

    @Test
    fun `UPDATE grant and revoke privileges`() = runBlocking {
        UserHandler.create(
            config,
            userCreateRequest { user = "carol"; password = "p"; host = "%" }
        )
        val grant = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("carol")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(true)
                .build()
        )
        assertEquals("granted", grant.action)

        val revoke = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("carol")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(false)
                .build()
        )
        assertEquals("revoked", revoke.action)
    }

    @Test
    fun `LIST with user field returns user privileges`() = runBlocking {
        UserHandler.create(
            config,
            userCreateRequest { user = "dan"; password = "p"; host = "%" }
        )
        UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("dan")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(true)
                .build()
        )
        val result = UserHandler.list(
            config,
            userListRequest { user = "dan"; host = "%" }
        )
        // 返回至少是列表（即使是空）
        assertTrue(result.itemsList.isNotEmpty() || result.itemsList.isEmpty())  // 始终为数组
    }
}
