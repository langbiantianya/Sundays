package com.langbiantianya.handlers

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.grpc.FunctionCallRequest
import com.langbiantianya.grpc.FunctionCallResponse
import com.langbiantianya.grpc.FunctionCreateRequest
import com.langbiantianya.grpc.FunctionCreateResponse
import com.langbiantianya.grpc.FunctionDebugRequest
import com.langbiantianya.grpc.FunctionDebugResponse
import com.langbiantianya.grpc.FunctionDeleteRequest
import com.langbiantianya.grpc.FunctionDeleteResponse
import com.langbiantianya.grpc.FunctionGetDdlRequest
import com.langbiantianya.grpc.FunctionGetDdlResponse
import com.langbiantianya.grpc.FunctionInfoRequest
import com.langbiantianya.grpc.FunctionInfoResponse
import com.langbiantianya.grpc.FunctionListRequest
import com.langbiantianya.grpc.FunctionListResponse
import com.langbiantianya.grpc.FunctionValidateRequest
import com.langbiantianya.grpc.FunctionValidateResponse
import com.langbiantianya.grpc.PayloadAdapter
import com.langbiantianya.grpc.functionCallResponse
import com.langbiantianya.grpc.functionCreateResponse
import com.langbiantianya.grpc.functionDebugItem
import com.langbiantianya.grpc.functionDebugResponse
import com.langbiantianya.grpc.functionDeleteResponse
import com.langbiantianya.grpc.functionGetDdlResponse
import com.langbiantianya.grpc.functionInfoResponse
import com.langbiantianya.grpc.functionListItem
import com.langbiantianya.grpc.functionListResponse
import com.langbiantianya.grpc.functionValidateResponse
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 数据库函数/存储过程（Routine）管理 Handler。
 */
object FunctionHandler {

    suspend fun list(config: ConnectionConfig, req: FunctionListRequest): FunctionListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val routines = dialect.listRoutines(conn, schema)
            functionListResponse {
                routines.forEach { row ->
                    this.items += functionListItem {
                        name = row["name"] ?: ""
                        routineType = row["routine_type"] ?: ""
                        returnType = row["return_type"] ?: ""
                        language = row["language"] ?: ""
                        securityDefiner = row["security_definer"] ?: ""
                        volatility = row["volatility"] ?: ""
                        argCount = row["arg_count"] ?: ""
                        argNames = row["arg_names"] ?: ""
                        this.schema = row["schema"] ?: ""
                        description = row["description"] ?: ""
                        triggerTable = row["trigger_table"] ?: ""
                    }
                }
            }
        }
    }

    suspend fun info(config: ConnectionConfig, req: FunctionInfoRequest): FunctionInfoResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val info = dialect.getRoutineInfo(conn, req.name, schema)
            // info shape is genuinely dialect-specific (e.g. TRIGGER has different fields),
            // so we keep packing as Value here.
            val obj = buildJsonObject {
                info.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            }
            functionInfoResponse { this.info = PayloadAdapter.toValue(obj) }
        }
    }

    suspend fun getDDL(config: ConnectionConfig, req: FunctionGetDdlRequest): FunctionGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            functionGetDdlResponse { ddl = dialect.getRoutineDDL(conn, req.name, schema) }
        }
    }

    suspend fun create(config: ConnectionConfig, req: FunctionCreateRequest): FunctionCreateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_DDL))

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createRoutine(conn, req.ddl)
            functionCreateResponse {
                success = true
                message = Msg.text(StrKey.ROUTINE_CREATED)
            }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: FunctionDeleteRequest): FunctionDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        if (req.routineType.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_ROUTINE_TYPE))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.dropRoutine(conn, req.name, req.routineType, schema, req.ifExists, req.cascade)
            functionDeleteResponse {
                success = true
                message = Msg.text(StrKey.ROUTINE_DROPPED)
                name = req.name
                routineType = req.routineType
            }
        }
    }

    suspend fun call(config: ConnectionConfig, req: FunctionCallRequest): FunctionCallResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        if (req.routineType.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_ROUTINE_TYPE))
        val schema = req.schema
        val args = req.argsList

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val result = dialect.callRoutine(conn, req.name, req.routineType, schema, args)
            // callRoutine result shape is genuinely dialect-specific (FUNCTION returns single value,
            // PROCEDURE returns update_count, etc.) — keep Value packing.
            val obj = buildJsonObject {
                result.forEach { (key, value) ->
                    put(key, value.toJsonElement())
                }
            }
            functionCallResponse { this.result = PayloadAdapter.toValue(obj) }
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = this as Map<String, Any?>
            buildJsonObject { m.forEach { (k, v) -> put(k, v.toJsonElement()) } }
        }
        is List<*> -> buildJsonArray { (this@toJsonElement as List<*>).forEach { add(it?.toJsonElement() ?: JsonNull) } }
        else -> JsonPrimitive(this.toString())
    }

    suspend fun debug(config: ConnectionConfig, req: FunctionDebugRequest): FunctionDebugResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_NAME))
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val debugInfo = dialect.debugRoutine(conn, req.name, schema)
            functionDebugResponse {
                debugInfo.forEach { row ->
                    this.items += functionDebugItem {
                        type = row["type"] ?: ""
                        output = row["output"] ?: ""
                    }
                }
            }
        }
    }

    suspend fun validate(config: ConnectionConfig, req: FunctionValidateRequest): FunctionValidateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException(Msg.text(StrKey.MISSING_PARAM_DDL))

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.validateRoutineDDL(conn, req.ddl)
            functionValidateResponse {
                valid = true
                message = Msg.text(StrKey.DDL_VALIDATED)
            }
        }
    }
}
