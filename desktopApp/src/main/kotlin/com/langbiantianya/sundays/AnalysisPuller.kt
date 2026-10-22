/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.connection.DialectType
import java.nio.file.Path

/*
 * 拉取链路的**契约**。
 *
 * 真正会碰引擎与文件系统的实现见 `EngineAnalysisPuller`；
 * 这里只说清「拉取」这件事的形状。
 */

/**
 * 拉取链路 —— 把远端的一张表搬进**本地 DuckDB 工作区**。
 *
 * ## 三段，零新协议
 *
 * ```
 * ① EXPORT.RUN_EXPORT   源会话（read_only）→ CSV      引擎的流式导出 + 进度帧
 * ② ——CSV on disk——     前端知道路径与大小
 * ③ IMPORT.RUN_IMPORT   工作区会话 → staging_<表名>    引擎的流式导入 + 进度帧
 * ```
 *
 * ## 为什么拆成两次既有调用，而不是一个 `ANALYSIS.PULL_SAMPLE`
 *
 * 拉取要碰**两个**数据库（远端读 + 本地写），而**一个 `Request` 只有一份
 * `ConnectionConfig`**。做成单路由，就得在请求体里再塞一份 workspace 配置 ——
 * 「哪个配置才是本次请求的连接」于是有了两个产地。拆成两次既有调用没有这个问题。
 *
 * ## 临时 CSV 的清理写在**一处** `finally` 里
 *
 * 成功 / 失败 / 取消**三条路径同一条语句**，不是三个分支各写一遍。
 * 漏一条的代价是「用户拉了一半就关掉窗口，磁盘上留下几百 MB 的 CSV」。
 *
 * ## 为什么它是个接口
 *
 * 会话（`AnalysisSession`）要靠它把「拉完了 / 拉挂了 / 拉取消了」映射成终态。
 * 那条映射是**状态机**的一部分，值得被确定性地测；
 * 而真实现要连远端、要起导出子进程 —— 在 CI 上既慢又不稳。
 * 拆成接口，才能给会话塞一个**立刻返回**的假实现，把三条终态路径各测一遍。
 */
interface AnalysisPuller {
    /**
     * 拉一张远端表进工作区。
     *
     * @param source 远端源的会话配置 —— **必须**带 `read_only = true`
     * @param workspace 本地工作区的会话配置 —— **不带** `read_only`（工作区要能写）
     * @param workspacePath 工作区数据库**文件**的路径 —— 用来在开写之前把目录建出来
     * @param dialect 源库的方言（决定命名空间的引用形式）
     * @param namespace 命名空间（MySQL 上是库，PG 上是 schema）
     * @param table 表名
     * @param onProgress 进度回调（在引擎的协程里调，不是主线程）
     */
    suspend fun pull(
        source: ConnectionConfig,
        workspace: ConnectionConfig,
        workspacePath: Path,
        dialect: DialectType,
        namespace: String,
        table: String,
        onProgress: suspend (PullProgress) -> Unit,
    ): PullResult
}
