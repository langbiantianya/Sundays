package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.IndexCreateRequest
import com.langbiantianya.grpc.IndexCreateResponse
import com.langbiantianya.grpc.IndexDeleteRequest
import com.langbiantianya.grpc.IndexDeleteResponse
import com.langbiantianya.grpc.IndexListRequest
import com.langbiantianya.grpc.IndexListResponse
import com.langbiantianya.grpc.indexCreateResponse
import com.langbiantianya.grpc.indexDeleteResponse
import com.langbiantianya.grpc.indexListItem
import com.langbiantianya.grpc.indexListResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IndexHandler {

    /**
     * LIST — 列出表的所有索引
     */
    suspend fun list(config: ConnectionConfig, req: IndexListRequest): IndexListResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_TABLE_NAME))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listIndexes(conn, req.tableName)
            indexListResponse {
                items.forEach { row ->
                    val columnsStr = row["columns"].orEmpty()
                    val columnsList = if (columnsStr.isNotEmpty()) columnsStr.split(",") else emptyList()
                    this.items += indexListItem {
                        name = row["name"] ?: ""
                        table = req.tableName
                        this.columns += columnsList
                        unique = row["unique"]?.toBooleanStrictOrNull() ?: false
                        type = row["type"] ?: ""
                        definition = row["definition"] ?: ""
                    }
                }
            }
        }
    }

    /**
     * CREATE — 创建索引
     */
    suspend fun create(config: ConnectionConfig, req: IndexCreateRequest): IndexCreateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_TABLE_NAME))
        if (req.indexName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_INDEX_NAME))
        if (req.columnsList.isEmpty()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_COLUMNS))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val ifNotExists = req.hasIfNotExists() && req.ifNotExists
            dialect.createIndex(conn, req.tableName, req.indexName, req.columnsList, req.unique, ifNotExists)
            indexCreateResponse {
                created = req.indexName
                tableName = req.tableName
            }
        }
    }

    /**
     * DELETE — 删除索引
     */
    suspend fun delete(config: ConnectionConfig, req: IndexDeleteRequest): IndexDeleteResponse = withContext(Dispatchers.IO) {
        if (req.indexName.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_INDEX_NAME))
        val tableName = req.tableName.ifBlank { null }
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val ifExists = req.hasIfExists() && req.ifExists
            dialect.dropIndex(conn, req.indexName, tableName, ifExists)
            indexDeleteResponse { deleted = req.indexName }
        }
    }
}
