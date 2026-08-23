package com.kxxnzstdsw.server

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.grpc.IdbEngineGrpcKt
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * IdbEngine gRPC 服务实现（Kotlin 协程版）。
 *
 * 通过 [IdbEngineGrpcKt.IdbEngineCoroutineImplBase] 接入 gRPC-Kotlin stub 生成的服务基类，
 * `handle` 方法直接桥接到 [IdbEngine.handle] 暴露的 `Flow<Response>`。
 *
 * 这是 v2.9 双模式架构中的 gRPC 模式入口；direct 模式（KMP desktopApp）走
 * [com.kxxnzstdsw.engine.IdbEngine.handle] 而不经 gRPC。两条路径共用同一个
 * [com.kxxnzstdsw.dispatcher.RequestDispatcher] 与同一份 envelope options 语义。
 *
 * 异常由 [catch] 收口为一条 error Response 后正常完成流。
 */
class IdbEngineImpl(
    private val engine: IdbEngine = IdbEngine(),
) : IdbEngineGrpcKt.IdbEngineCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(IdbEngineImpl::class.java)

    override fun handle(request: Request): Flow<Response> = flow {
        logger.info("Handle request: id=${request.id} ${request.category}/${request.action}")
        // IdbEngine.handle 是 direct-mode 入口 — gRPC 服务复用它保证两条路径语义一致
        engine.handle(request).collect { emit(it) }
    }.catch { e ->
        logger.error("Error processing request ${request.id}", e)
        emit(
            response {
                id = request.id
                success = false
                error = e.message ?: e.javaClass.simpleName
            }
        )
    }
}
