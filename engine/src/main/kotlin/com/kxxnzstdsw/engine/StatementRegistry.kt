package com.kxxnzstdsw.engine

import org.slf4j.LoggerFactory
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行中请求 → JDBC [Statement] 的注册表 —— 查询取消（`SYSTEM.CANCEL`）的基础设施。
 *
 * ## 为什么需要它
 *
 * 协程的取消对阻塞 JDBC 调用无效：`SqlEngineHandler.execute` 里的 `rs.next()` 跑在
 * `Dispatchers.IO` 线程上，取消上游 Flow 只是把协程挂起，那条 IO 线程仍在数据库里跑。
 * 唯一能让数据库真正停下来的办法是对**那条正在执行的 Statement** 调 `Statement.cancel()`。
 *
 * 处理器在创建 Statement 后立即 [register]，在 `finally` 中 [unregister]。
 * 调用方拿到请求 id 后，用 `SYSTEM.CANCEL` 携带 `target_request_id` 即可中止。
 *
 * ## 线程安全
 *
 * 基于 [ConcurrentHashMap]，注册 / 注销 / 取消都是并发安全的。[cancel] 拿到 Statement 引用后
 * 驱动会抛 `SQLException`（多数驱动实现为 `SQLState 57014` query cancelled），
 * 由处理器原有的异常路径统一收口成 `success=false` 的终止帧。
 */
object StatementRegistry {
    private val logger = LoggerFactory.getLogger(StatementRegistry::class.java)
    private val running = ConcurrentHashMap<String, CancelTarget>()

    /** 可取消的目标 —— 抽象出「怎么停下来」，使 JDBC Statement 与导入任务共用同一条取消通道。 */
    fun interface CancelTarget {
        /** 尽力中止。实现内部吞掉异常，由调用方通过返回值判断是否成功。 */
        fun cancel()
    }

    /**
     * 登记一条正在执行的语句。同 id 重复登记以**最后一次**为准
     * （一次请求内可能先后建多条语句，例如多语句脚本）。
     */
    fun register(requestId: String, statement: Statement) {
        if (requestId.isBlank()) return
        running[requestId] = CancelTarget { statement.cancel() }
    }

    /**
     * 登记自定义取消动作（非 JDBC 目标，例如批量导入的「停标志 + 中断当前批次」）。
     */
    fun registerCanceler(requestId: String, action: () -> Unit) {
        if (requestId.isBlank()) return
        running[requestId] = CancelTarget { action() }
    }
    /** 注销。请求结束时必须调用，否则 [running] 会泄漏已关闭的 Statement 引用。 */
    fun unregister(requestId: String) {
        if (requestId.isBlank()) return
        running.remove(requestId)
    }

    /**
     * 取消指定请求正在执行的语句。
     *
     * **子 id 匹配**：一次请求内可能先后登记多条语句 —— 多语句脚本按 `"$requestId#$index"`
     * 逐条登记（`SqlEngineHandler.executeScript`）。调用方只知道自己发的 `Request.id`，
     * 拿不到带 `#index` 后缀的内部键，因此这里在精确匹配失败后按**前缀**回退：
     * `r2` 命中 `r2#0`。这让 `SYSTEM.CANCEL` 的文档语义（携带原请求 id 即可中止）
     * 在脚本模式下真正成立。
     *
     * @return true = 找到目标且 `cancel()` 未抛异常；false = 该请求不在运行或已结束
     */
    fun cancel(requestId: String): Boolean {
        val target = running[requestId]
            ?: running.entries.firstOrNull { (key, _) ->
                key.length > requestId.length && key.startsWith("$requestId#")
            }?.value
            ?: run {
                logger.debug("No running statement for request id={}", requestId)
                return false
            }
        return try {
            target.cancel()
            logger.info("Cancellation issued for request id={}", requestId)
            true
        } catch (e: Exception) {
            // Statement 已关闭 / 驱动不支持 —— 不视为致命，调用方收到 cancelled=false
            logger.warn("Statement.cancel() failed for request id={}: {}", requestId, e.message)
            false
        }
    }

    /** 当前正在执行的请求数（诊断 / 测试用）。 */
    fun activeCount(): Int = running.size

    /** 清空注册表。仅供测试与引擎关闭时兜底调用。 */
    fun clear() {
        running.clear()
    }
}
