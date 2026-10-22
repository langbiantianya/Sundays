/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 分析模式 UI 包的**结构边界**测试（主设计 §6.6）。
 *
 * ## 它守的是什么
 *
 * `shared/.../analysis/` 是跨平台界面代码。它必须满足三条：
 *
 * 1. **不 import `java.*` / `javax.*`** —— 那些是 JVM 专有的。
 *    一旦引入，`:shared` 就不再是跨平台模块，而本仓库的 KMP 布局
 *    （`:shared` = commonMain 单一 source set）会名存实亡。
 * 2. **不 import 引擎 / 协议层**（`com.langbiantianya.engine.*` /
 *    `com.langbiantianya.grpc.*` / `com.langbiantianya.client.*`）—— 界面不直接调引擎。
 *    它只接收**已经算好的**数据，把事件回调出去；
 *    连不连、怎么拉，全在 desktopApp 侧决定。
 *    界面直接依赖引擎的话，这套分层就名存实亡，而且跨平台那份就废了。
 * 3. **不出现字面量颜色** —— 颜色一律取自 `MaterialTheme.colorScheme`。
 *    写死的颜色在五套配色（蓝灰 / 赛博 / 哔哩粉 / Win2000 / WinXP）里
 *    只有一套是对的，而且不会跟着明暗档位走。
 *
 * ## 为什么用「读源文件」而不是「读字节码」
 *
 * 字节码里看到的是**类引用**，`import java.util.List` 这种只用了类型的 import
 * 未必留下痕迹；而这一条约束的本意就是「连 import 都不许出现」。
 * 读源文件是唯一能如实反映这一点的办法 ——
 * 仓库里 `GenerateSandboxContractTest` 也是同一个路子。
 */
class AnalysisModuleBoundaryTest {
    /** `:shared` 的分析模式包 —— Gradle 跑测试时工作目录是模块根（`desktopApp/`）。 */
    private val analysisDir = File("../shared/src/commonMain/kotlin/com/langbiantianya/sundays/analysis")

    private fun sources(): List<File> {
        assertTrue(
            analysisDir.isDirectory,
            "找不到分析模式 UI 包：${analysisDir.absolutePath}",
        )
        val all = analysisDir.listFiles() ?: emptyArray()
        val files = all.filter { it.extension == "kt" }.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "分析模式 UI 包里没有 .kt 文件：${analysisDir.absolutePath}")
        return files
    }

    /** 取出这个文件里所有满足 [predicate] 的 import 行。 */
    private fun importsMatching(
        file: File,
        predicate: (String) -> Boolean,
    ): List<String> {
        val hits = mutableListOf<String>()
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (!line.startsWith("import ")) continue
            if (predicate(line)) hits.add(line)
        }
        return hits
    }

    @Test
    fun `analysis ui does not import jvm only packages`() {
        for (file in sources()) {
            val bad = importsMatching(file) { line -> line.startsWith("import java.") || line.startsWith("import javax.") }
            assertTrue(
                bad.isEmpty(),
                "${file.name} 引入了 JVM 专有的 import（:shared 是跨平台模块）：\n${bad.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `analysis ui does not depend on the engine or the protocol layer`() {
        for (file in sources()) {
            val bad =
                importsMatching(file) { line ->
                    line.startsWith("import com.langbiantianya.engine.") ||
                        line.startsWith("import com.langbiantianya.grpc.") ||
                        line.startsWith("import com.langbiantianya.client.")
                }
            assertTrue(
                bad.isEmpty(),
                "${file.name} 直接依赖了引擎 / 协议层（界面只该接收算好的数据）：\n${bad.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `analysis ui does not hardcode colors`() {
        for (file in sources()) {
            val bad = mutableListOf<String>()
            for (raw in file.readLines()) {
                val line = raw.trim()
                if (!line.startsWith("//")) {
                    val hasLiteral =
                        line.contains("Color(0x", ignoreCase = true) ||
                            line.contains("Color.Red") ||
                            line.contains("Color.Blue") ||
                            line.contains("Color.Green")
                    if (hasLiteral) bad.add(line)
                }
            }
            assertTrue(
                bad.isEmpty(),
                "${file.name} 里出现了字面量颜色（颜色一律取自 MaterialTheme.colorScheme）：\n${bad.joinToString("\n")}",
            )
        }
    }
}
