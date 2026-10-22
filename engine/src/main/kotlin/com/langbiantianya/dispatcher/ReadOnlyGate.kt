/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dispatcher

import com.langbiantianya.grpc.Action
import com.langbiantianya.grpc.Category
import com.langbiantianya.grpc.Request
import com.langbiantianya.i18n.Msg
import com.langbiantianya.i18n.StrKey

/**
 * L4 —— **会话级只读闸门**（主设计 §3.1 的六层防线之一）。
 *
 * ## 它挡什么
 *
 * 客户端在请求的 [Request.connection] 上带 `read_only = true`（分析模式连远端时
 * 就会带），引擎在**进入 handler 之前**拒绝一切「可能改目标数据库」的路由。
 *
 * ## 为什么放在 dispatcher，而不是各 handler 内部
 *
 * 主设计 §3.6 的验收要求「**不进入 handler**」。如果判据写在 handler 里，
 * 测试就只能退化成「断言错误文案」—— 那抓不住「handler 已经被调进去了、
 * 只是最后抛了异常」的实现。闸门在 dispatcher，测试才能用**假 handler 计数**
 * 证明它根本没进去。
 *
 * ## 它和 [RequestDispatcher.writeActions] 有什么不同
 *
 * 两个集合长得像，问的却是**两个问题**：
 *
 * | | 问的问题 | `SYSTEM.DISCONNECT` | `EXPORT.RUN_EXPORT` | `FUNCTION.CALL` |
 * |---|---|---|---|---|
 * | `writeActions`（dryRun 用） | 这个路由**当场就看得出**副作用吗？试运行要跳过它 | 跳（会关掉用户的连接） | 跳 | 不跳（副作用在过程体里，看不出来） |
 * | 本闸门 | 这个路由会不会**改目标数据库**？ | 不改（关的是本地连接池） | 不改（写的是本地文件） | **可能改**（过程体不透明） |
 *
 * 所以本闸门的集合**不是** `writeActions` 的子集，也不是超集 —— 它们是两道
 * 不同的防线，各有各的判据。本闸门**不复用** `writeActions`：
 * 复用了就等于把「试运行跳过」与「只读拦截」两件事绑在一起，而它们的
 * 正确答案在 `DISCONNECT` 与 `CALL` 上正好相反。
 *
 * ## 为什么 `SQL.EXECUTE` 整类被拒，不做逐语句判断
 *
 * 仓库里那份 SQL 读写分类器在 `:shared` 的 `DangerousSql`，**引擎看不见
 * `:shared`**。复制一份就是第二个真相源，判据迟早不一致（主设计 §6.5 正是
 * 拿这条理由说明「组件层不判断」）。
 *
 * 而且分析模式**在远端根本不需要跑 SELECT**：远端只用来列元数据与拉取。
 * 整类拒绝对功能零损失。
 */
object ReadOnlyGate {
    /**
     * 会改**目标数据库**的 (Category, Action) 集合。
     *
     * 这是本闸门判据的**唯一产地** —— 别处再写一份就又有了两个真相源。
     */
    private val remoteWriteActions: Set<Pair<Category, Action>> =
        setOf(
            // SCHEMA —— 建 / 删库
            Category.SCHEMA to Action.CREATE,
            Category.SCHEMA to Action.DELETE,
            // USER —— 建 / 改 / 删账号与授权
            Category.USER to Action.CREATE,
            Category.USER to Action.UPDATE,
            Category.USER to Action.DELETE,
            // TABLE
            Category.TABLE to Action.CREATE,
            Category.TABLE to Action.UPDATE,
            Category.TABLE to Action.DELETE,
            Category.TABLE to Action.RENAME,
            Category.TABLE to Action.TRUNCATE,
            // DATA
            Category.DATA to Action.CREATE,
            Category.DATA to Action.UPDATE,
            Category.DATA to Action.DELETE,
            // SQL —— 任意语句，整类被拒（见本文件 KDoc 的「为什么整类被拒」）
            Category.SQL to Action.EXECUTE,
            // FUNCTION —— 存储过程体对引擎不透明，**可能**写
            Category.FUNCTION to Action.CREATE,
            Category.FUNCTION to Action.UPDATE,
            Category.FUNCTION to Action.DELETE,
            Category.FUNCTION to Action.CALL,
            // VIEW
            Category.VIEW to Action.CREATE,
            Category.VIEW to Action.DELETE,
            // INDEX
            Category.INDEX to Action.CREATE,
            Category.INDEX to Action.DELETE,
            // FOREIGN_KEY
            Category.FOREIGN_KEY to Action.CREATE,
            Category.FOREIGN_KEY to Action.DELETE,
            // IMPORT —— 往目标表插数据（truncate_first 还会先清空目标表）
            Category.IMPORT to Action.RUN_IMPORT,
        )

    /**
     * 这个 (Category, Action) 会不会改**目标数据库**。
     *
     * 注意 `SYSTEM.*` 与 `EXPORT.RUN_EXPORT` **不在**集合里：
     * 前者关的是**本地**连接池、后者写的是**本地**文件 —— 它们都不碰远端数据。
     * 把 `SYSTEM.DISCONNECT` 挡掉会导致用户**关不掉**一个只读连接，
     * 那是功能性 bug，不是安全性收益。
     */
    fun isRemoteWrite(
        category: Category,
        action: Action,
    ): Boolean = category to action in remoteWriteActions

    /**
     * 这条请求要不要被闸门拦下。
     *
     * @return `true` = 拦下（**不要**再调用 handler）。
     */
    fun rejects(request: Request): Boolean = request.connection.readOnly && isRemoteWrite(request.category, request.action)

    /**
     * 拦下时给用户看的话。
     *
     * 用界面语言书写 —— `Response.error` 是**原样上屏**的（见 `RequestDispatcher`
     * 的 catch 分支），所以这句话直接决定用户看到什么。
     */
    fun rejectionMessage(
        category: Category,
        action: Action,
    ): String = Msg.format(StrKey.ENGINE_READ_ONLY_REJECTED, "$category/$action")
}
