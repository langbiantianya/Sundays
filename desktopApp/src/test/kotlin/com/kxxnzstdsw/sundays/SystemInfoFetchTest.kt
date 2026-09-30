package com.kxxnzstdsw.sundays

import com.kxxnzstdsw.engine.IdbEngine
import com.kxxnzstdsw.pool.PoolManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 设置页「系统信息」的数据源契约 —— `SYSTEM.INFO` 路由必须真的能返回运行时信息。
 *
 * 为什么单独测：`fetchSystemInfo` 走的是「传一个空 [com.kxxnzstdsw.grpc.ConnectionConfig]
 * 让路由忽略它」这条捷径。空配置本身是合法的 proto 值，能编译、请求也能发出去，
 * 但只有真跑一次才知道 `RequestDispatcher` 不会在别处把它当成真实连接去解析方言。
 *
 * 这条测试跑的是**真引擎**（同进程 `IdbEngine`），因此覆盖到真实路由 + 真实 handler。
 */
class SystemInfoFetchTest {

    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        engine.close()
    }

    @Test
    fun `system info returns jvm and os runtime details`() = runBlocking {
        val info = fetchSystemInfo(engine)

        // 这些值来自 SystemHandler.info()：为空说明路由没走到 handler，或映射丢了字段
        assertTrue(info.jvmVersion.isNotBlank(), "jvmVersion 不应为空（实际 '${info.jvmVersion}'）")
        assertTrue(info.jvmVendor.isNotBlank(), "jvmVendor 不应为空（实际 '${info.jvmVendor}'）")
        assertTrue(info.jvmName.isNotBlank(), "jvmName 不应为空（实际 '${info.jvmName}'）")
        assertTrue(info.osName.isNotBlank(), "osName 不应为空（实际 '${info.osName}'）")
        assertTrue(info.osArch.isNotBlank(), "osArch 不应为空（实际 '${info.osArch}'）")

        assertTrue(info.availableProcessors > 0, "可用处理器数应 > 0，实际 ${info.availableProcessors}")
        assertTrue(info.pid > 0, "pid 应 > 0，实际 ${info.pid}")
        assertTrue(info.uptimeMillis > 0, "运行时长应 > 0，实际 ${info.uptimeMillis}")

        // 内存三档：max ≥ total ≥ used，且 used > 0（进程刚起来必然已用了一些堆）
        assertTrue(info.memoryMax > 0, "堆上限应 > 0")
        assertTrue(info.memoryTotal in 1..info.memoryMax, "堆已分配应在 (0, 上限] 内，实际 ${info.memoryTotal}")
        assertTrue(info.memoryUsed in 1..info.memoryTotal, "堆已用应在 (0, 已分配] 内，实际 ${info.memoryUsed}")
        assertTrue(info.memoryFree in 0..info.memoryTotal, "堆空闲应在 [0, 已分配] 内，实际 ${info.memoryFree}")
    }
}
