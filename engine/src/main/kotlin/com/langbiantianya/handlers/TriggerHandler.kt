package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.TriggerGetDdlRequest
import com.langbiantianya.grpc.TriggerGetDdlResponse
import com.langbiantianya.grpc.TriggerListRequest
import com.langbiantianya.grpc.TriggerListResponse
import com.langbiantianya.grpc.triggerGetDdlResponse
import com.langbiantianya.grpc.triggerListItem
import com.langbiantianya.grpc.triggerListResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 触发器管理 Handler。
 * - LIST/GET_DDL 直接调用 dialect
 * - CREATE/DELETE 复用 FunctionHandler.createRoutine / dropRoutine（routineType="TRIGGER"）
 */
object TriggerHandler {

    /**
     * LIST — 列出 schema 下的触发器
     */
    suspend fun list(config: ConnectionConfig, req: TriggerListRequest): TriggerListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listTriggers(conn, schema)
            triggerListResponse {
                items.forEach { row ->
                    this.items += triggerListItem {
                        name = row["name"] ?: ""
                        table = row["table"] ?: ""
                        event = row["event"] ?: ""
                        timing = row["timing"] ?: ""
                        statement = row["statement"] ?: ""
                    }
                }
            }
        }
    }

    /**
     * GET_DDL — 获取触发器 DDL
     */
    suspend fun getDDL(config: ConnectionConfig, req: TriggerGetDdlRequest): TriggerGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            triggerGetDdlResponse { ddl = dialect.getTriggerDDL(conn, req.name, schema) }
        }
    }
}
