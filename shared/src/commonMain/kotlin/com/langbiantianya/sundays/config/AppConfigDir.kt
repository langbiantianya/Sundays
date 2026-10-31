/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.config

import java.nio.file.Path
import java.nio.file.Paths

/*
 * 应用配置目录 —— `~/.config/sundays/`。
 *
 * ## 为什么这个概念要**只有一份实现**
 *
 * 应用自己的文件散在好几个模块里：
 *
 * | 文件 | 归谁写 |
 * |---|---|
 * | `connection.json` | 连接清单（含明文口令） |
 * | `settings.json` | 应用设置 |
 * | `workspace.duckdb` | 分析模式的工作区 |
 * | `reports.json` | **v2.40 新增**：报表设计 |
 *
 * 它们每一个都有自己的存储类，而每个存储类都要回答同一个问题：
 * **「这个文件该放哪」**。
 *
 * 那个答案如果是**各写各的**，就会变成四份字面量（`".config" / "sundays"`）。
 * 四份字面量今天一样，明天改目录名就只改了三份 —— 而漏掉的那一份，
 * 症状是「设置没了 / 报表没了」，而且用户完全不知道该去哪找。
 *
 * 所以把它收在这里：**一个地方答，四处用**。
 *
 * ## 为什么给两种形态（Path / 字符串）
 *
 * - [dir] 给**真正要碰文件系统**的地方（`Files` 那一套）；
 * - [dirText] 给**要拼进路径串**的地方 —— 比如 DuckDB 的工作区路径，
 *   它是以字符串传给引擎的，Windows 上用 `\` 分隔会出问题，
 *   所以那一处必须用 `/`。
 *
 * 两种形态**说的是同一个目录**，只是拼法不同 ——
 * 别把它们当成两个概念，也别让它们各写各的片段名。
 */

/** 配置目录里的第一级片段 —— `~/.config`。 */
const val CONFIG_DIR_NAME: String = ".config"

/** 配置目录里的第二级片段 —— `~/.config/sundays`（应用自己的那一层）。 */
const val APP_DIR_NAME: String = "sundays"

/**
 * 应用配置目录（**字符串**形态，用 `/` 分隔）。
 *
 * @param home 用户主目录
 * @return 形如 `/home/u/.config/sundays`
 */
fun appConfigDirText(home: String): String = "$home/$CONFIG_DIR_NAME/$APP_DIR_NAME"

/**
 * 应用配置目录（**Path** 形态）。
 *
 * @param home 用户主目录
 * @return 形如 `/home/u/.config/sundays` 的 [Path]
 */
fun appConfigDir(home: String = System.getProperty("user.home")): Path = Paths.get(home, CONFIG_DIR_NAME, APP_DIR_NAME)

/**
 * 配置目录下的某个文件。
 *
 * @param name 文件名
 * @param home 用户主目录
 * @return 该文件的完整路径
 */
fun appConfigFile(
    name: String,
    home: String = System.getProperty("user.home"),
): Path = appConfigDir(home).resolve(name)
