/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.ViewCreateRequest
import com.langbiantianya.grpc.ViewCreateResponse
import com.langbiantianya.grpc.ViewDeleteRequest
import com.langbiantianya.grpc.ViewDeleteResponse
import com.langbiantianya.grpc.ViewGetDdlRequest
import com.langbiantianya.grpc.ViewGetDdlResponse
import com.langbiantianya.grpc.ViewListRequest
import com.langbiantianya.grpc.ViewListResponse
import com.langbiantianya.grpc.viewCreateResponse
import com.langbiantianya.grpc.viewDeleteResponse
import com.langbiantianya.grpc.viewGetDdlResponse
import com.langbiantianya.grpc.viewListItem
import com.langbiantianya.grpc.viewListResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视图管理 Handler。视图列表的 schema 默认为 ""（PG 走 search_path）。
 */
object ViewHandler {

    /**
     * LIST — 列出 schema 下的视图
     */
    suspend fun list(config: ConnectionConfig, req: ViewListRequest): ViewListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listViews(conn, schema)
            viewListResponse {
                items.forEach { row ->
                    this.items += viewListItem {
                        name = row["name"] ?: ""
                        type = row["type"] ?: "VIEW"
                        definition = row["definition"] ?: ""
                    }
                }
            }
        }
    }

    /**
     * CREATE — 创建视图
     */
    suspend fun create(config: ConnectionConfig, req: ViewCreateRequest): ViewCreateResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        if (req.definition.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_DEFINITION))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.createView(conn, req.name, req.definition)
            viewCreateResponse { created = req.name }
        }
    }

    /**
     * DELETE — 删除视图
     */
    suspend fun delete(config: ConnectionConfig, req: ViewDeleteRequest): ViewDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropView(conn, req.name, req.ifExists)
            viewDeleteResponse { deleted = req.name }
        }
    }

    /**
     * GET_DDL — 获取视图完整定义
     */
    suspend fun getDDL(config: ConnectionConfig, req: ViewGetDdlRequest): ViewGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            viewGetDdlResponse { ddl = dialect.getViewDDL(conn, req.name, schema) }
        }
    }
}
