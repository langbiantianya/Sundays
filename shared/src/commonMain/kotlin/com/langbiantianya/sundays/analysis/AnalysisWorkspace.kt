/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

/*
 * 分析工作区的**纯规则** —— 工作区文件的位置、staging 表名怎么拼。
 *
 * ## 为什么这些规则放在 `:shared`
 *
 * 它们是**纯函数**：给定输入必然给出同一个输出，不碰引擎也不碰文件系统。
 * 放在跨平台模块里有两个好处：
 *
 * - 能进 `commonTest`，在**任何**平台上都能跑（不依赖 JVM 的文件系统 API）
 * - 将来做移动端 / Web 版时，这套规则能原样复用
 *
 * 真正会碰文件系统的部分（建目录、开 DuckDB 文件）在 `desktopApp` 的
 * `AnalysisSession` 里。
 *
 * ⚠️ 这里用**块注释**而不是 KDoc：本文件顶层的这段话不挂在任何一个声明上，
 * 写成 KDoc 就是一段「悬空 KDoc」—— ktlint 会直接报错
 * （`A dangling toplevel KDoc is not allowed`）。
 */

/** 工作区数据库文件名 —— 固定名，跨会话复用同一个文件。 */
const val WORKSPACE_FILE_NAME: String = "workspace.duckdb"

/**
 * 工作区文件的**完整路径**。
 *
 * @param home 用户主目录（通常是 `System.getProperty("user.home")`）
 * @param configDir 应用配置目录（通常是 `~/.config/sundays`）
 * @return 工作区数据库文件路径
 *
 * ## 为什么放在配置目录而不是「数据目录」
 *
 * 仓库现有的应用级文件（`connection.json`）就在 `~/.config/sundays/`。
 * 为一个文件另立一套目录规则，会让「应用的文件都在哪」这个问题多一个答案 ——
 * 而多一个答案就意味着将来清理 / 备份 / 迁移要记得两处。
 */
fun workspaceFilePath(
    home: String,
    configDir: String = "$home/.config/sundays",
): String = "$configDir/$WORKSPACE_FILE_NAME"

/**
 * staging 表名 —— 远端表被拉进工作区后的落点。
 *
 * 形如 `staging_<源库>_<表名>`。
 *
 * ## 为什么带源库前缀
 *
 * 不同源可能有**同名表**（两个 MySQL 库里都有一张 `orders`）。不带前缀的话，
 * 第二次拉取会与第一次**撞名** —— 要么覆盖、要么报错，两种都是错的：
 * 用户要的是「两个源里的两张 orders 都在工作区里」。
 *
 * ## 为什么要清洗
 *
 * 表名要拼进 SQL 标识符，而源库名 / 表名是**用户起的**，里面可能有空格、
 * 连字符、引号，甚至分号。不清洗就拼进 `CREATE TABLE` 就是 SQL 注入的口子。
 *
 * 清洗规则见 [sanitizeIdentifierPart]。
 */
fun stagingTableName(
    sourceNamespace: String,
    table: String,
): String = "staging_${sanitizeIdentifierPart(sourceNamespace)}_${sanitizeIdentifierPart(table)}"

/**
 * 标识符片段的清洗。
 *
 * ## 规则
 *
 * 1. 只保留**字母、数字、下划线**；其余字符（空格、连字符、引号、分号、点 …）一律换成 `_`
 * 2. **不能以数字开头** —— 那样拼出来的标识符在 SQL 里是语法错误，
 *    所以数字开头时前面补一个 `_`
 * 3. 空串 → `_`（保证拼出来的名字永远不为空，也不出现 `staging__` 这种双下划线的空洞）
 *
 * ## 为什么不是「直接引用」
 *
 * 也可以把原名原样用引号包起来（`"my table"`）—— 那样能保留原名。
 * 但那样一来：跨源撞名的问题没解决（`orders` 和 `orders` 还是一样），
 * 而且不同方言的引用符与转义规则还不一样。清洗成规范形状更省事。
 *
 * ⚠️ 清洗是**有损**的：`a-b` 与 `a_b` 会撞成同一个名字。
 * 这是刻意的取舍 —— 撞名时用户看到的是两个同名的 staging 表，
 * 而**不是**一条把别的库的表覆盖掉的查询。后者是不可逆的数据损失。
 */
fun sanitizeIdentifierPart(raw: String): String {
    if (raw.isEmpty()) return "_"
    val cleaned =
        buildString(raw.length) {
            for (ch in raw) {
                append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
            }
        }
    return if (cleaned.first().isDigit()) "_$cleaned" else cleaned
}
