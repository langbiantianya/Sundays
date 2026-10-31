/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.config

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 应用配置目录 —— **唯一**的一份算法。
 *
 * ## 为什么这个概念要有自己的用例
 *
 * 应用的文件散在好几个模块里（`connection.json` / `settings.json` /
 * `workspace.duckdb` / `reports.json`）。它们每一个都要回答同一个问题：
 * **「这个文件该放哪」**。
 *
 * 那个答案如果是各写各的，就是四份字面量。四份字面量今天一样，
 * 明天改目录名就会漏掉一份 —— 而漏掉的那一份的症状是
 * 「设置没了 / 报表没了」，而用户完全不知道该去哪找。
 *
 * 所以这里钉住：**目录名只有一份实现**，各处都问它。
 */
class AppConfigDirTest {
    @Test
    fun `the config dir sits under the home directory`() {
        assertEquals(
            Paths.get("/home/u", ".config", "sundays"),
            appConfigDir("/home/u"),
        )
    }

    @Test
    fun `the text form always uses forward slashes`() {
        // 路径**串**要传给 DuckDB —— Windows 上的 `\` 会被当成转义符。
        assertEquals("/home/u/.config/sundays", appConfigDirText("/home/u"))
    }

    @Test
    fun `a file in the config dir is named after it`() {
        assertEquals(
            Paths.get("/home/u", ".config", "sundays", "reports.json"),
            appConfigFile("reports.json", "/home/u"),
        )
    }

    @Test
    fun `the workspace file lives in the same directory`() {
        // 工作区文件与报表文件**同住一个目录** —— 应用的文件都在 `~/.config/sundays/`，
        // 「应用的文件都在哪」必须只有一个答案。
        val dir = appConfigDirText("/home/u")
        assertTrue(dir.endsWith("/.config/sundays"))
    }
}
