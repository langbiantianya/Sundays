package com.kxxnzstdsw.engine

import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内 IDB Engine —— [EngineClient] 的**同进程实现**。
 *
 * **调用层抽象**：调用方只面向 [EngineClient] 接口编程，本类与
 * `:engine-grpc-client` 的 `GrpcEngineClient` 是它的两个实现，调用代码零差异：
 *
 * | 实现 | 模块 | 通道 | 场景 |
 * |---|---|---|---|
 * | `IdbEngine`（本类） | `:engine` | 同 JVM 直接方法调用 | Compose Desktop 同进程、shell 工具、嵌入式 |
 * | `GrpcEngineClient` | `:engine-grpc-client` | gRPC over TCP / UDS / 命名管道 | 跨进程、跨语言、远程引擎 |
 *
 * 本类把请求交给 [RequestDispatcher]，因此 envelope options（`traceId` / `dryRun` / `timeoutMs`）、
 * 流式分帧、方言 SPI 分派与 gRPC 路径**逐帧一致**。
 *
 * **服务端入口**：`IdbEngineServer`（`--mode grpc`）以本类为后端挂载 gRPC service；
 * `--mode direct` 则只 bootstrap 后阻塞主线程。
 *
 * **生命周期**：
 * ```kotlin
 * IdbEngine.bootstrap()              // load JDBC drivers + dialect plugins once per JVM
 * val engine = IdbEngine()           // lightweight — just bootstraps if not done
 * val resp = engine.handle(request { ... }).first()
 * // ...
 * engine.close()                     // release pools / loaders on JVM shutdown
 * ```
 *
 * **Why a facade instead of calling handlers directly?** The dispatcher carries cross-cutting
 * concerns (MDC traceId, dryRun short-circuit, timeout wrapping, stream frame assembly) that
 * every (Category, Action) route needs. Handlers themselves only know typed proto messages;
 * they don't see envelopes. The gRPC client and the direct caller must go through the same
 * dispatcher for consistent semantics.
 *
 * **Thread safety**: `IdbEngine` instances are stateless wrappers around global singletons
 * (`PoolManager`, `DriverLoader`, `DialectLoader`). Multiple instances coexist; close() is
 * idempotent and idempotently releases global state on JVM shutdown.
 */
class IdbEngine(
    driversDir: File = File("drivers"),
    dialectsDir: File = File("dialects"),
) : EngineClient {

    init {
        // 构造即 bootstrap — 保证 IdbEngine() 拿到时 drivers/dialects 已加载
        // (幂等: DriverLoader/DialectLoader 内部用 ConcurrentHashMap 去重)
        bootstrap(driversDir, dialectsDir)
    }

    /**
     * 单一入口 — 与 gRPC stub `IdbEngineCoroutineStub.handle(req)` 语义完全一致：
     *
     * - 非流式 (SCHEMA / TABLE / DATA 写 / SQL.EXPLAIN / SYSTEM / FUNCTION 写 / VIEW / INDEX / FK / TRIGGER)：
     *   返回 1 条 Response，`stream=false, end=true`
     * - 流式 (DATA.LIST pageSize=0 / SQL.EXECUTE SELECT / DATA.GENERATE / EXPORT.RUN_EXPORT)：
     *   返回 N 条 Response，最后一条 `end=true`
     *
     * [EngineClient.invoke] / `testConnection` / `disconnect` 的默认实现或本地直连实现见下方。
     */
    override fun handle(request: Request): Flow<Response> = RequestDispatcher.dispatch(request)

    /**
     * 断开连接 —— 关闭 [config] 对应的 HikariCP 连接池（含该配置下各 schema 维度的池），
     * 与 [testConnection] 对称：`testConnection` 建池（“初始化连接”），`disconnect` 释放池。
     *
     * 池按 [PoolManager] 的 hash key 缓存，因此传入的 `config` 必须与建池时的字段
     * （`jdbcUrl` / `host` / `port` / `user` / `password` / `database`）一致才能定位到同一个池。
     * 其他配置的连接池不受影响；断开后再次 [testConnection] 会重建池。
     *
     * @return 是否真的关闭了连接池（false = 该配置当前没有活跃池）
     */
    override suspend fun disconnect(config: ConnectionConfig): Boolean =
        withContext(Dispatchers.IO) { PoolManager.close(config) }

    /**
     * 测试 / 初始化连接 —— **本地直连**：不经 gRPC server、不经 IPC transport，也不经
     * [RequestDispatcher] 的 envelope（无 traceId / dryRun / timeoutMs 包装），
     * 直接调用 [SystemHandler.testConnection]。
     *
     * 首次调用会用 `config` 创建（或复用）HikariCP 连接池 —— 这一步即“初始化连接”；
     * 随后借出一条连接做 JDBC `isValid(5)` 校验。池按 [PoolManager] 的 hash key 缓存，
     * 重复调用不会重复建池。
     *
     * `config.jdbcUrl` 非空时**只依赖 URL**：方言由 URL scheme 反查，
     * `driver` / `host` / `port` / `database` 均被忽略。
     */
    override suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse =
        SystemHandler.testConnection(config)

    /**
     * 关闭资源 — 调用 PoolManager.closeAll() / DriverLoader.closeAll() / DialectLoader.closeAll()。
     * 通常由 JVM ShutdownHook 触发，业务代码不必显式调用。
     */
    override fun close() {
        try { PoolManager.closeAll() } catch (e: Exception) { logger.warn("PoolManager.closeAll failed: ${e.message}") }
        try { DriverLoader.closeAll() } catch (e: Exception) { logger.warn("DriverLoader.closeAll failed: ${e.message}") }
        try { DialectLoader.closeAll() } catch (e: Exception) { logger.warn("DialectLoader.closeAll failed: ${e.message}") }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IdbEngine::class.java)
        private val bootstrapped = AtomicBoolean(false)

        /**
         * 全局初始化 (幂等) — 加载 JDBC drivers/ 目录 + dialects/ 目录。
         * 通常由 [IdbEngine] 构造时自动调用，业务代码不需显式调用。
         * 仅在需要预加载 (例如命令行工具启动前) 时单独调用。
         */
        @JvmStatic
        @JvmOverloads
        fun bootstrap(
            driversDir: File = File("drivers"),
            dialectsDir: File = File("dialects"),
        ) {
            if (bootstrapped.compareAndSet(false, true)) {
                DriverLoader.loadFromDir(driversDir)
                DialectLoader.loadFromDir(dialectsDir)
                logger.info("IdbEngine bootstrap complete (drivers={}, dialects={})",
                    driversDir.absolutePath, dialectsDir.absolutePath)
            }
        }

        /**
         * 直接模式入口 (CLI `--mode direct`) — 初始化后阻塞等待 stdin EOF / 信号，
         * 供 shell 测试 / 守护进程使用。KMP frontend 不走此入口 — 直接构造 [IdbEngine] 即可。
         */
        @JvmStatic
        fun runDirectMode(driversDir: File = File("drivers"), dialectsDir: File = File("dialects")) {
            bootstrap(driversDir, dialectsDir)
            logger.info("IdbEngine direct mode: drivers/dialects loaded, awaiting shutdown signal")
            // 注册 shutdown hook — 保持与其他 JVM 模式一致的优雅退出
            Runtime.getRuntime().addShutdownHook(Thread({
                logger.info("Direct mode shutdown hook triggered")
                try { PoolManager.closeAll() } catch (_: Exception) {}
                try { DriverLoader.closeAll() } catch (_: Exception) {}
                try { DialectLoader.closeAll() } catch (_: Exception) {}
            }, "idb-engine-direct-shutdown"))
            // 阻塞主线程 — 直到 JVM 关闭
            Thread.currentThread().join()
        }
    }
}
