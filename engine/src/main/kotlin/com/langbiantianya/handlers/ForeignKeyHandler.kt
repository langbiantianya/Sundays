/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.ForeignKeyCreateRequest
import com.langbiantianya.grpc.ForeignKeyCreateResponse
import com.langbiantianya.grpc.ForeignKeyDeleteRequest
import com.langbiantianya.grpc.ForeignKeyDeleteResponse
import com.langbiantianya.grpc.ForeignKeyListRequest
import com.langbiantianya.grpc.ForeignKeyListResponse
import com.langbiantianya.grpc.foreignKeyCreateResponse
import com.langbiantianya.grpc.foreignKeyDeleteResponse
import com.langbiantianya.grpc.foreignKeyListItem
import com.langbiantianya.grpc.foreignKeyListResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外键管理 Handler。
 */
object ForeignKeyHandler {
    /**
     * LIST — 列出表的所有外键
     */
    suspend fun list(
        config: ConnectionConfig,
        req: ForeignKeyListRequest,
    ): ForeignKeyListResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_TABLE_NAME))
            val schema = req.schema

            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)
            return@withContext connection.use { conn ->
                val items = dialect.listForeignKeys(conn, req.tableName)
                // Accumulate columns / ref_columns per FK name (some dialects return one row per column)
                val acc = mutableMapOf<String, MutableMap<String, Any>>()
                items.forEach { row ->
                    val name = row["name"] ?: return@forEach
                    val existing =
                        acc.getOrPut(name) {
                            mutableMapOf(
                                "name" to name,
                                "table" to req.tableName,
                                "columns" to mutableListOf<String>(),
                                "ref_table" to (row["ref_table"] ?: ""),
                                "ref_columns" to mutableListOf<String>(),
                                "on_delete" to (row["on_delete"] ?: "NO ACTION"),
                                "on_update" to (row["on_update"] ?: "NO ACTION"),
                            )
                        }
                    (existing["columns"] as MutableList<String>).add(row["column"] ?: row["columns"] ?: "")
                    (existing["ref_columns"] as MutableList<String>).add(row["ref_column"] ?: row["ref_columns"] ?: "")
                }
                foreignKeyListResponse {
                    acc.values.forEach { m ->
                        this.items +=
                            foreignKeyListItem {
                                name = m["name"] as String
                                table = m["table"] as String
                                this.columns += m["columns"] as List<String>
                                refTable = m["ref_table"] as String
                                this.refColumns += m["ref_columns"] as List<String>
                                onDelete = m["on_delete"] as String
                                onUpdate = m["on_update"] as String
                            }
                    }
                }
            }
        }

    /**
     * CREATE — 添加外键
     */
    suspend fun create(
        config: ConnectionConfig,
        req: ForeignKeyCreateRequest,
    ): ForeignKeyCreateResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_TABLE_NAME))
            if (req.fkName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_FK_NAME))
            if (req.columnsList.isEmpty()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_COLUMNS))
            if (req.refTable.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_REF_TABLE))
            if (req.refColumnsList.isEmpty()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_REF_COLUMNS))
            val schema = req.schema
            val onDelete = req.onDelete.ifBlank { null }
            val onUpdate = req.onUpdate.ifBlank { null }

            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)
            return@withContext connection.use { conn ->
                dialect.addForeignKey(conn, req.tableName, req.fkName, req.columnsList, req.refTable, req.refColumnsList, onDelete, onUpdate)
                foreignKeyCreateResponse {
                    created = req.fkName
                    tableName = req.tableName
                }
            }
        }

    /**
     * DELETE — 删除外键
     */
    suspend fun delete(
        config: ConnectionConfig,
        req: ForeignKeyDeleteRequest,
    ): ForeignKeyDeleteResponse =
        withContext(Dispatchers.IO) {
            if (req.tableName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_TABLE_NAME))
            if (req.fkName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_FK_NAME))
            val schema = req.schema

            val connection = PoolManager.getConnection(config, schema)
            val dialect = DialectLoader.getDialect(config.driver)
            return@withContext connection.use { conn ->
                val ifExists = req.hasIfExists() && req.ifExists
                dialect.dropForeignKey(conn, req.tableName, req.fkName, ifExists)
                foreignKeyDeleteResponse { deleted = req.fkName }
            }
        }
}
