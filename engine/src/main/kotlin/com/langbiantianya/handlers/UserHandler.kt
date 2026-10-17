/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.PayloadAdapter
import com.langbiantianya.grpc.UserCreateRequest
import com.langbiantianya.grpc.UserCreateResponse
import com.langbiantianya.grpc.UserDeleteRequest
import com.langbiantianya.grpc.UserDeleteResponse
import com.langbiantianya.grpc.UserGrantsRequest
import com.langbiantianya.grpc.UserGrantsResponse
import com.langbiantianya.grpc.UserListRequest
import com.langbiantianya.grpc.UserListResponse
import com.langbiantianya.grpc.UserUpdateRequest
import com.langbiantianya.grpc.UserUpdateResponse
import com.langbiantianya.grpc.userCreateResponse
import com.langbiantianya.grpc.userDeleteResponse
import com.langbiantianya.grpc.userGrantItem
import com.langbiantianya.grpc.userGrantsResponse
import com.langbiantianya.grpc.userListResponse
import com.langbiantianya.grpc.userUpdateResponse
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 用户与权限 Handler —— `USER` category 的 5 个 action。
 *
 * ## 两个「一个 action 两种语义」的约定
 *
 * - [list] 带 `user` → 列**该用户的权限**；不带 `user` → 列**用户清单**。
 * - [updatePrivileges] 带 `password` 且**不带** `privileges` → 改密码；
 *   带 `privileges` → 授予（`is_grant=true`）或回收（`is_grant=false`）。
 *
 * 这两处复用 action 是协议层的历史约定，不要为了“更清晰”拆成新 action ——
 * 那会让已发布的客户端全部失配。
 *
 * ## 为什么只有 `USER.LIST` 保留 `google.protobuf.Value`
 *
 * 权限条目的**形状**按方言差异显著：MySQL 返回 `SHOW GRANTS` 的原始文本
 * （`GRANT SELECT ON \`db\`.* TO 'dev'@'%'`），PostgreSQL / H2 返回结构化的
 * `{schema, table, privilege}`。强行统一成 typed item 会有一边永远是空的，
 * 因此这两处刻意保留 Value 打包。其余 action 全部 typed。
 */
object UserHandler {
    /**
     * LIST —— 见类 KDoc 的双语义约定。
     *
     * `host` 留空按 `%` 处理：MySQL 的账号是 `(user, host)` 二元组，不指定 host
     * 就取「任意主机」，与 `SHOW GRANTS FOR 'dev'@'%'` 的语义一致。
     */
    suspend fun list(
        config: ConnectionConfig,
        req: UserListRequest,
    ): UserListResponse =
        withContext(Dispatchers.IO) {
            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                userListResponse {
                    if (req.user.isNotBlank()) {
                        val targetHost = req.host.ifBlank { "%" }
                        val privileges = dialect.listPrivileges(conn, req.user, targetHost)
                        // Privileges shape varies by dialect (MySQL raw SHOW GRANTS text vs PG/H2 structured
                        // {schema, table, privilege}). Keep Value packing — genuinely dialect-specific.
                        privileges.forEach { row ->
                            val obj = JsonObject(row.mapValues { (_, v) -> JsonPrimitive(v) })
                            this.items += PayloadAdapter.toValue(obj)
                        }
                    } else {
                        val users = dialect.listUsers(conn)
                        users.forEach { row ->
                            val obj = JsonObject(row.mapValues { (_, v) -> JsonPrimitive(v) })
                            this.items += PayloadAdapter.toValue(obj)
                        }
                    }
                }
            }
        }

    /**
     * GRANTS —— 聚合成**表级**的授权清单（`schema` / `table` / `privileges`）。
     *
     * 与 `LIST` 带 user 的区别：这里返回的是结构化、可直接渲染成表格的
     * `UserGrantItem[]`；`privileges` 字段同时接受方言给的 `privileges` 与 `privilege`，
     * 拼写差异由这一行吸收。
     */
    suspend fun listAllGrants(
        config: ConnectionConfig,
        req: UserGrantsRequest,
    ): UserGrantsResponse =
        withContext(Dispatchers.IO) {
            if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
            val host = req.host.ifBlank { "%" }

            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                val grants = dialect.listAllGrants(conn, req.user, host)
                userGrantsResponse {
                    grants.forEach { row ->
                        this.items +=
                            userGrantItem {
                                schema = row["schema"] ?: ""
                                table = row["table"] ?: ""
                                privilege = row["privileges"] ?: row["privilege"] ?: ""
                            }
                    }
                }
            }
        }

    /**
     * CREATE —— `user` + `password` 都必填（`host` 默认 `%`）。
     *
     * **不支持用户体系的方言**（SQLite / DuckDB）在这里抛
     * `UnsupportedOperationException`，由方言自己声明「我不会」，引擎不做兜底。
     */
    suspend fun create(
        config: ConnectionConfig,
        req: UserCreateRequest,
    ): UserCreateResponse =
        withContext(Dispatchers.IO) {
            if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
            if (req.password.isBlank()) throw IllegalArgumentException("Missing 'password'")
            val host = req.host.ifBlank { "%" }

            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                dialect.createUser(conn, req.user, req.password, host)
                userCreateResponse { created = req.user }
            }
        }

    /** DELETE —— `user` 必填，`host` 默认 `%`。不静默吞「用户不存在」。 */
    suspend fun delete(
        config: ConnectionConfig,
        req: UserDeleteRequest,
    ): UserDeleteResponse =
        withContext(Dispatchers.IO) {
            if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
            val host = req.host.ifBlank { "%" }

            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            return@withContext connection.use { conn ->
                dialect.deleteUser(conn, req.user, host)
                userDeleteResponse { deleted = req.user }
            }
        }

    /**
     * UPDATE —— 改密码 / 授权 / 收权三合一（见类 KDoc 的双语义约定）。
     *
     * 走授权分支时 `schema` 必填（表级授权必须指明库），`tableName` 可空表示库级授权；
     * `with_grant_option` 只在 `is_grant=true` 时有意义。
     *
     * **判据是「password 非空 **且** privileges 为空」**，两个条件缺一不可：
     * 只看 password 会把「同时改密码并授权」的请求悄悄降级成只改密码。
     */
    suspend fun updatePrivileges(
        config: ConnectionConfig,
        req: UserUpdateRequest,
    ): UserUpdateResponse =
        withContext(Dispatchers.IO) {
            if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")

            val connection = PoolManager.getConnection(config)
            val dialect = DialectLoader.getDialect(config.driver)

            // 当 password 非空且 privileges 为空时走密码修改路径；否则走权限授予/回收
            val hasPrivileges = req.privilegesList.isNotEmpty()
            return@withContext connection.use { conn ->
                if (req.password.isNotBlank() && !hasPrivileges) {
                    val host = req.host.ifBlank { "%" }
                    dialect.updatePassword(conn, req.user, req.password, host)
                    userUpdateResponse {
                        user = req.user
                        action = "password_changed"
                    }
                } else {
                    if (req.schema.isBlank()) throw IllegalArgumentException("Missing 'schema'")
                    val isGrant = req.isGrant
                    val tableName = req.tableName.ifBlank { null }
                    val withGrantOption = req.withGrantOption

                    dialect.updatePrivileges(conn, req.user, req.schema, req.privilegesList, isGrant, tableName, withGrantOption)
                    userUpdateResponse {
                        user = req.user
                        schema = req.schema
                        action = if (isGrant) "granted" else "revoked"
                        if (tableName != null) table = tableName
                        if (withGrantOption) this.withGrantOption = true
                    }
                }
            }
        }
}
