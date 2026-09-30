package com.kxxnzstdsw.sundays.settings

/**
 * 系统信息面板的数据状态 —— 由调用方（desktopApp）从引擎 `SYSTEM.INFO` 填入。
 *
 * 定义在 `:shared` 而不是在 desktopApp，是为了让 [SettingsScreen] 能对
 * loading / 成功 / 失败三态写纯 UI 测试 —— 组件本身不需要起引擎。
 */
sealed interface SystemInfoState {

    /** 尚未请求（默认态）。 */
    data object Idle : SystemInfoState

    /** 请求中。 */
    data object Loading : SystemInfoState

    /** 成功。 */
    data class Loaded(val info: SystemInfo) : SystemInfoState

    /** 失败（引擎异常 / gRPC 不可达）。 */
    data class Failed(val message: String) : SystemInfoState

    /**
     * 是否已成功加载过 —— 设置页据此决定「切到系统信息分类」时是否要重新请求，
     * 避免用户每次切回来都打一次引擎。
     */
    val loaded: Boolean get() = this is Loaded
}

/**
 * 引擎 `SYSTEM.INFO` 的结果（`SystemHandler.info()` 的返回）。
 *
 * 字段与 `com.kxxnzstdsw.grpc.SystemInfoResponse` 一一对应，但**不直接用 proto 类型**：
 * `:shared` 不依赖 protobuf（它只依赖 Compose + kotlinx.serialization），直接用 proto 会
 * 打破模块边界。desktopApp 负责把 proto 映射成这里的数据类。
 *
 * 内存用扁平的四个 `Long` 而非嵌套的 `MemoryInfo` —— 渲染时不需要二级结构，
 * 扁平化省掉一层无意义的包装。
 */
data class SystemInfo(
    val jvmVersion: String = "",
    val jvmVendor: String = "",
    val jvmName: String = "",
    val osName: String = "",
    val osArch: String = "",
    val osVersion: String = "",
    val availableProcessors: Int = 0,
    val memoryMax: Long = 0,
    val memoryTotal: Long = 0,
    val memoryUsed: Long = 0,
    val memoryFree: Long = 0,
    val uptimeMillis: Long = 0,
    val pid: Long = 0,
)
