package com.kxxnzstdsw.handlers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * 造数沙箱的**宿主函数契约测试**。
 *
 * ## 它守的是什么
 *
 * UI 侧的补全清单（`shared` 的 `GenerateHelpers.completions`）是**静态复制**的一份：
 * `:shared` 不能反向依赖 `:engine`（依赖方向 `engine ↮ shared` 不变），所以拿不到运行时
 * 真相。这份契约测试就是那根**防漂移的钉子** —— 引擎侧一改 `registerHelpers` /
 * `applySandbox`，这里立刻变红，逼着同步去改 UI 清单。
 *
 * ## 为什么读源码而不是跑起来
 *
 * 直觉上应该建一个 Lua 状态、跑 `registerHelpers`、再枚举全局键，那样最「真」。但
 * `registerHelpers(L, state)` 要求一个持有 `Connection` / `DatabaseDialect` 的
 * `GenerateState`（`GenerateState` 是 `private` 嵌套类），为此把可见性放宽到 `internal`
 * 或用反射造实例，代价都比读源码大；而我们真正要守的只是**「注册了哪几个名字」**这一个事实。
 *
 * 顺带一提，本测试断言的是**集合相等**（不是「期望的名字都存在」）——
 * 只有相等才能同时挡住「引擎加了函数、UI 没同步」和「引擎删了函数、UI 还留着」两个方向。
 * 断言包含关系的话，加函数那个方向会静默溜过去。
 *
 * ## 同步义务
 *
 * 引擎侧增删宿主函数时，**两处都要改**：
 * 1. `GenerateHandler.registerHelpers` / `applySandbox`
 * 2. `shared` 的 `GenerateHelpers`（补全清单 + `sandboxDisabled`）
 */
class GenerateSandboxContractTest {

    /**
     * 宿主函数名 —— 必须与 `shared` 的 `GenerateHelpers.names` 完全一致。
     *
     * 这里只能**复制**一份：`shared` 是 KMP 模块，`:engine` 是纯 JVM 模块，
     * 依赖方向不允许反向引用。复制是模块边界的必然代价，靠本测试兜住。
     */
    private val expectedHelpers = setOf(
        "insert", "lastId",
        "random_int", "random_float", "random_string",
        "random_date", "random_datetime", "random_time",
        "random_email", "random_phone", "random_name",
        "random_enum", "random_uuid",
    )

    /** 沙箱置 nil 的入口 —— 必须与 `shared` 的 `GenerateHelpers.sandboxDisabled` 一致。 */
    private val expectedDisabled = setOf(
        "os", "io", "debug", "package", "require",
        "loadfile", "dofile", "loadstring", "load",
        "rawget", "rawset", "rawequal",
        "setfenv", "getfenv", "newproxy",
    )

    private val handlerSource: String by lazy {
        // Gradle 跑测试时工作目录是模块根（`engine/`），路径因此是稳定的
        val file = File("src/main/kotlin/com/kxxnzstdsw/handlers/GenerateHandler.kt")
        assertTrue(file.exists(), "找不到 GenerateHandler.kt（工作目录=${File(".").absolutePath}）")
        file.readText()
    }

    @Test
    fun `registered host helpers match the ui completion contract`() {
        val registered = Regex("""setGlobal\("([^"]+)"\)""").findAll(handlerSource)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            expectedHelpers, registered,
            """
            造数沙箱的宿主函数与 UI 补全清单不一致。
            引擎侧：`GenerateHandler.registerHelpers`
            UI 侧 ：`shared` 的 `GenerateHelpers.completions`
            两处都要改，否则补全会推荐不存在的函数（或漏掉已存在的）。
            """.trimIndent(),
        )
    }

    @Test
    fun `sandbox disabled entries match the documented contract`() {
        // applySandbox 里的名字是 listOf(...) 的**参数变量**（L.setGlobal(name)），
        // 所以取整个函数体里的字符串字面量，而不是 setGlobal 的实参。
        val body = handlerSource.substringAfter("private fun applySandbox")
            .substringBefore("private fun readLuaTable")
        val disabled = Regex("""\"([A-Za-z_][A-Za-z0-9_]*)\"""").findAll(body)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            expectedDisabled, disabled,
            "沙箱禁用清单与 UI 侧记录的契约不一致（GenerateHelpers.sandboxDisabled）",
        )
    }

    @Test
    fun `no helper name collides with a disabled sandbox entry`() {
        // 同一个名字既「注入」又「置 nil」时，最终值取决于调用顺序 ——
        // 那种代码能跑但行为不可读，属于必须提前挡掉的。
        val overlap = expectedHelpers intersect expectedDisabled
        assertTrue(overlap.isEmpty(), "宿主函数与沙箱禁用项重名：$overlap")
    }
}
