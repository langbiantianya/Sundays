/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.SchemaCreateRequest
import com.langbiantianya.grpc.SchemaCreateResponse
import com.langbiantianya.grpc.SchemaDeleteRequest
import com.langbiantianya.grpc.SchemaDeleteResponse
import com.langbiantianya.grpc.SchemaListRequest
import com.langbiantianya.grpc.SchemaListResponse
import com.langbiantianya.grpc.schemaCreateResponse
import com.langbiantianya.grpc.schemaDeleteResponse
import com.langbiantianya.grpc.schemaListResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SchemaHandler {
    /**
     * 导航层次分两级：
     * - req.level = "database"（默认） → 列出所有 database
     * - req.level = "schema" → 列出指定 database 下的 schema（必须同时传 req.database）
     */
    suspend fun list(config: ConnectionConfig, req: SchemaListRequest): SchemaListResponse = withContext(Dispatchers.IO) {
        val level = if (req.level.isBlank()) "database" else req.level
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            when (level) {
                "database" -> {
                    val items = dialect.listDatabases(conn)
                    schemaListResponse {
                        this.level = "database"
                        this.items += items
                    }
                }
                "schema" -> {
                    val database = req.database.ifBlank {
                        throw IllegalArgumentException(
                            Msg.text(StrKey.SCHEMA_LIST_REQUIRES_DATABASE)
                        )
                    }
                    val items = dialect.listSchemas(conn, database)
                    schemaListResponse {
                        this.level = "schema"
                        this.database = database
                        this.items += items
                    }
                }
                else -> throw IllegalArgumentException(
                    Msg.format(StrKey.UNSUPPORTED_SCHEMA_LEVEL, level)
                )
            }
        }
    }

    suspend fun create(config: ConnectionConfig, req: SchemaCreateRequest): SchemaCreateResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("Missing 'name'")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ifNotExists = req.hasIfNotExists() && req.ifNotExists
            dialect.createSchema(conn, req.name, req.optionsMap, ifNotExists)
            schemaCreateResponse { created = req.name }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: SchemaDeleteRequest): SchemaDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("Missing 'name'")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ifExists = req.hasIfExists() && req.ifExists
            dialect.deleteSchema(conn, req.name, ifExists)
            schemaDeleteResponse { deleted = req.name }
        }
    }
}
